use serde_json::{json, Value};
use std::{collections::{HashMap, BTreeMap, VecDeque}, sync::{atomic::{AtomicBool, Ordering}, Mutex}, process::Stdio, time::Duration};
use tauri::{Emitter, Manager};
use tokio::{io::{AsyncBufReadExt, AsyncWriteExt, BufReader}, sync::mpsc};

const LIMIT: usize = 65536;
struct Active { profile: String, sender: mpsc::Sender<Value> }
#[derive(Default)]
struct Replay { id: String, name: String, recent: VecDeque<Value>, latest: HashMap<String, Value> }
impl Replay {
    fn record(&mut self, event: &Value) {
        let kind=event["event"].as_str().unwrap_or("");
        if let Some(name)=event["profileName"].as_str() {self.name=name.chars().take(128).collect();}
        if matches!(kind,"state"|"network"|"statistics"|"transport"|"ended"|"error") {
            self.latest.insert(kind.into(),event.clone());
        }
        if kind=="permission" {
            let mut state=event.clone();state["state"]=json!("permission");self.latest.insert("state".into(),state);
        }
        if kind=="ended" || kind=="state" && matches!(event["state"].as_str(),Some("connected"|"disconnecting"|"idle"|"failed")) {
            self.latest.remove("challenge");
        }
        if kind=="state" && matches!(event["state"].as_str(),Some("preparing"|"connected")) { self.latest.remove("error"); }
        if kind=="authentication" || kind=="certificate" {
            self.latest.insert("challenge".into(),event.clone());
            let mut safe=event.clone();
            safe.as_object_mut().unwrap().retain(|key,_|matches!(key.as_str(),"sessionId"|"sequence"|"generation"|"timestamp"|"schemaVersion"));
            safe["event"]=json!("log");
            safe["code"]=json!(if kind=="certificate" { "certificate_invalid" } else if event["rejected"]==true {"authentication_rejected"} else {"authentication_waiting"});
            self.recent.push_back(safe);
        } else if kind!="statistics" { self.recent.push_back(event.clone()); }
        while self.recent.len()>200 { self.recent.pop_front(); }
    }
    fn events(&self) -> Vec<Value> {
        let mut ordered=BTreeMap::new();
        for event in &self.recent {
            ordered.insert(event["sequence"].as_u64().unwrap_or(0),event.clone());
        }
        for (kind,event) in &self.latest {
            if kind=="challenge" {
                ordered.insert(event["sequence"].as_u64().unwrap_or(0),event.clone());
                continue;
            }
            ordered.entry(event["sequence"].as_u64().unwrap_or(0)).or_insert_with(|| {
                let mut value=event.clone();value["replayOnly"]=json!(true);value
            });
        }
        ordered.into_values().collect()
    }
    fn tray_row(&self) -> crate::tray_status::Row {
        use crate::tray_status::Status;
        let value=|kind:&str|self.latest.get(kind).unwrap_or(&Value::Null);
        let ended=value("ended");
        let state=value("state")["state"].as_str().unwrap_or("preparing");
        let failed=!value("error").is_null();
        let status=if !ended.is_null() {
            if ended["cleanupConfirmed"]!=true {Status::CleanupFailed}
            else if ended["stopped"]==true {Status::Idle}
            else if failed || ended["error"].as_str().is_some_and(|e|!e.is_empty()) {Status::Failed} else {Status::Idle}
        } else if state=="disconnecting" {Status::Disconnecting}
        else if !value("challenge").is_null() {
            if value("challenge")["event"]=="certificate" {Status::Certificate} else {Status::Login}
        } else {match state {
            "connected"=>Status::Connected,"reconnecting"=>Status::Reconnecting,
            "authenticating"=>Status::Login,"permission"=>Status::Permission,
            "failed"=>Status::CleanupFailed,"idle"=>if failed {Status::Failed} else {Status::Idle},
            _=>Status::Connecting,
        }};
        crate::tray_status::Row{name:self.name.clone(),status}
    }
}
#[derive(Default)]
pub struct Sessions { active: Mutex<HashMap<String, Active>>, views: Mutex<HashMap<String, Replay>>, closing: AtomicBool, close_failed: AtomicBool }

pub fn tray_rows(app: &tauri::AppHandle) -> Vec<crate::tray_status::Row> {
    app.state::<Sessions>().views.lock().unwrap_or_else(|e|e.into_inner()).values().map(Replay::tray_row).collect()
}
pub fn sync_profiles(app: &tauri::AppHandle, reply: &Value) {
    let Some(profiles)=reply["document"]["profiles"].as_array() else {return;};
    let state=app.state::<Sessions>();
    let active=state.active.lock().unwrap_or_else(|e|e.into_inner());
    let mut views=state.views.lock().unwrap_or_else(|e|e.into_inner());
    views.retain(|id,view| {
        if let Some(profile)=profiles.iter().find(|p|p["id"].as_str()==Some(id.as_str())) {
            if let Some(name)=profile["name"].as_str() {view.name=name.chars().take(128).collect();}
            true
        } else {active.values().any(|session|&session.profile==id)}
    });
    drop(views);drop(active);
    crate::tray_status::refresh(app);
}

#[cfg(test)]
pub fn tray_fixture_event(app: &tauri::AppHandle, profile: &str, event: Value) {
    app.state::<Sessions>().views.lock().unwrap().entry(profile.into()).or_default();
    publish(app,profile,event);
}

#[tauri::command]
pub fn vpn_snapshot(state: tauri::State<'_, Sessions>) -> Value {
    let views=state.views.lock().unwrap_or_else(|e|e.into_inner());
    json!(views.iter().map(|(profile,view)|json!({"profileId":profile,"sessionId":view.id,"events":view.events()})).collect::<Vec<_>>())
}
#[tauri::command]
pub fn vpn_clear_log(state: tauri::State<'_, Sessions>, profile_id: String) {
    if let Some(view)=state.views.lock().unwrap_or_else(|e|e.into_inner()).get_mut(&profile_id) { view.recent.clear(); }
}
fn publish(app: &tauri::AppHandle, profile: &str, event: Value) {
    let state=app.state::<Sessions>();
    if let Some(view)=state.views.lock().unwrap_or_else(|e|e.into_inner()).get_mut(profile) { view.record(&event); }
    let needs_input=matches!(event["event"].as_str(),Some("authentication"|"certificate"));
    let changes_tray=matches!(event["event"].as_str(),Some("state"|"authentication"|"certificate"|"permission"|"error"|"ended"));
    let _=app.emit_to("main","vpn-event",json!({"profileId":profile,"event":event}));
    if changes_tray {crate::tray_status::refresh(app);}
    if needs_input { crate::desktop::show(app); }
}

fn can_start(active: &HashMap<String, Active>, id: &str, profile: &str) -> bool {
    active.len() < 8 && !active.contains_key(id) && !active.values().any(|session| session.profile == profile)
}

fn uuid(value: &str) -> bool {
    value.len() == 36 && value.bytes().enumerate().all(|(i, c)|
        if [8,13,18,23].contains(&i) { c == b'-' } else { c.is_ascii_hexdigit() && !c.is_ascii_uppercase() })
}

#[tauri::command]
pub async fn vpn_start(app: tauri::AppHandle, session_id: String, profile_id: String) -> Result<(), String> {
    if !uuid(&session_id) || profile_id.is_empty() || profile_id.len() > 128 { return Err("invalid_request".into()); }
    let path = crate::native_directory(&app)?.join(
        if cfg!(windows) { "geekoctunnel-core.exe" } else { "geekoctunnel-core" });
    let (sender, receiver) = mpsc::channel(16);
    let state = app.state::<Sessions>();
    let mut active = state.active.lock().map_err(|_| "session_failed")?;
    if !can_start(&active, &session_id, &profile_id) || state.closing.load(Ordering::SeqCst) { return Err("session_busy".into()); }
    let mut command = tokio::process::Command::new(path);
    command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let child = command.spawn().map_err(|_| "vpn_backend_unavailable")?;
    if active.is_empty() { state.close_failed.store(false, Ordering::SeqCst); }
    active.insert(session_id.clone(), Active { profile: profile_id.clone(), sender });
    let mut views=state.views.lock().map_err(|_| "session_failed")?;
    if views.len()>=128 && !views.contains_key(&profile_id) {
        if let Some(old)=views.keys().find(|key|!active.values().any(|a|&a.profile==*key)).cloned() { views.remove(&old); }
    }
    views.insert(profile_id.clone(),Replay{id:session_id.clone(),..Default::default()});
    drop(views);
    drop(active);
    crate::tray_status::refresh(&app);
    let policy=crate::desktop::settings(&app);
    tauri::async_runtime::spawn(run(app, session_id, profile_id, child, receiver, policy));
    Ok(())
}

#[tauri::command]
pub fn vpn_send(state: tauri::State<'_, Sessions>, request: Value) -> Result<(), String> {
    let bytes = serde_json::to_vec(&request).map_err(|_| "invalid_request")?;
    if bytes.len() > LIMIT || !["stop", "stats", "reply"].contains(&request["action"].as_str().unwrap_or("")) {
        return Err("invalid_request".into());
    }
    let guard = state.active.lock().map_err(|_| "session_failed")?;
    let active = guard.get(request["sessionId"].as_str().ok_or("invalid_request")?).ok_or("stale_session")?;
    let clear=matches!(request["action"].as_str(),Some("reply"|"stop"));
    active.sender.try_send(request).map_err(|_| "session_busy".to_string())?;
    if clear {
        if let Some(view)=state.views.lock().unwrap_or_else(|e|e.into_inner()).get_mut(&active.profile) { view.latest.remove("challenge"); }
    }
    Ok(())
}

pub fn close_requested(app: &tauri::AppHandle) -> bool {
    let state = app.state::<Sessions>();
    let guard = state.active.lock().unwrap_or_else(|e| e.into_inner());
    if !guard.is_empty() {
        state.closing.store(true, Ordering::SeqCst);
        for (id, active) in guard.iter() { let _ = active.sender.try_send(json!({"action":"stop", "sessionId":id})); }
        true
    } else { false }
}

async fn read_event(reader: &mut BufReader<tokio::process::ChildStdout>) -> Result<Option<Value>, &'static str> {
    use tokio::io::AsyncReadExt;
    let mut bytes = Vec::new();
    (&mut *reader).take((LIMIT + 1) as u64).read_until(b'\n', &mut bytes).await.map_err(|_| "session_read_failed")?;
    if bytes.is_empty() { return Ok(None); }
    if bytes.len() > LIMIT || bytes.last() != Some(&b'\n') { return Err("invalid_session_event"); }
    serde_json::from_slice(&bytes).map(Some).map_err(|_| "invalid_session_event")
}

fn cleanup_after_event(previous: bool, event: &Value) -> bool {
    if event["event"]!="state" {return previous;}
    if event["state"]=="preparing" {return false;}
    event["cleanupConfirmed"].as_bool().unwrap_or(previous)
}

async fn run(app: tauri::AppHandle, id: String, profile: String, mut child: tokio::process::Child,
    mut commands: mpsc::Receiver<Value>, policy: crate::desktop::Settings) {
    let mut clean = false;
    let mut sequence = 0_u64;
    let mut generation = 1_u64;
    let mut stopped=false;
    let outcome: Result<(), &'static str> = async {
        let mut input = child.stdin.take().ok_or("session_start_failed")?;
        let mut output = BufReader::new(child.stdout.take().ok_or("session_start_failed")?);
        let start = json!({"schemaVersion":1,"sessionId":id,"profileId":profile,
            "autoReconnect":policy.auto_reconnect,"ignoreCertificateErrors":policy.ignore_certificate_errors}).to_string() + "\n";
        input.write_all(start.as_bytes()).await.map_err(|_| "session_start_failed")?;
        let mut stop_deadline: Option<tokio::time::Instant> = None;
        // Keep the read future alive across command arrivals: read_until is not cancellation safe.
        let mut next = Box::pin(read_event(&mut output));
        loop {
            tokio::select! {
                value = &mut next => {
                    let Some(event) = value? else { break; };
                    let next_generation = event["generation"].as_u64().unwrap_or(0);
                    if event["schemaVersion"] != 1 || event["sessionId"] != id || next_generation < generation || next_generation > generation + 1
                        || event["sequence"].as_u64().unwrap_or(0) <= sequence { return Err("invalid_session_event"); }
                    sequence = event["sequence"].as_u64().unwrap_or(0);
                    generation = next_generation;
                    clean=cleanup_after_event(clean,&event);
                    publish(&app,&profile,event);
                    drop(next);
                    next = Box::pin(read_event(&mut output));
                }
                Some(request) = commands.recv() => {
                    if request["action"] == "stop" && stop_deadline.is_none() {
                        stopped=true;
                        stop_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(25));
                    }
                    let mut bytes = (request.to_string() + "\n").into_bytes();
                    let result = tokio::time::timeout(Duration::from_secs(2), input.write_all(&bytes)).await;
                    bytes.fill(0);
                    result.map_err(|_| "session_write_failed")?.map_err(|_| "session_write_failed")?;
                }
                _ = async {
                    match stop_deadline {
                        Some(deadline) => tokio::time::sleep_until(deadline).await,
                        None => std::future::pending::<()>().await,
                    }
                } => { return Err("session_stop_timeout"); }
            }
        }
        tokio::time::timeout(Duration::from_secs(5), child.wait()).await.map_err(|_| "session_stop_timeout")?
            .map_err(|_| "session_failed")?;
        Ok(())
    }.await;
    if outcome.is_err() { let _ = child.kill().await; }
    let state = app.state::<Sessions>();
    if !clean { state.close_failed.store(true, Ordering::SeqCst); }
    let empty = {
        let mut active = state.active.lock().unwrap_or_else(|e| e.into_inner());
        active.remove(&id);
        active.is_empty()
    };
    publish(&app,&profile,json!({"schemaVersion":1,"event":"ended","sessionId":id,"sequence":sequence+1,"generation":generation,
        "cleanupConfirmed":clean,"stopped":stopped,"error":outcome.err().unwrap_or(if clean { "" } else { "network_cleanup_unconfirmed" })}));
    if empty && state.closing.load(Ordering::SeqCst) {
        if !state.close_failed.load(Ordering::SeqCst) { app.exit(0); }
        else { state.closing.store(false, Ordering::SeqCst); crate::desktop::show(&app); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn previous_generation_cleanup_cannot_certify_a_crashed_reconnection() {
        let clean=cleanup_after_event(false,&json!({"event":"state","state":"idle","cleanupConfirmed":true}));
        assert!(clean);
        let clean=cleanup_after_event(clean,&json!({"event":"state","state":"preparing","generation":2}));
        assert!(!clean);
        assert!(!cleanup_after_event(clean,&json!({"event":"state","state":"connected"})));
        assert!(!cleanup_after_event(true,&json!({"event":"state","state":"failed","cleanupConfirmed":false})));
    }
    #[test]
    fn tray_tracks_native_events_without_a_webview_and_distinguishes_stop_from_failure() {
        use crate::tray_status::Status;
        let mut view=Replay::default();
        view.record(&json!({"event":"state","state":"preparing","profileName":"Example"}));
        assert_eq!(view.tray_row().name,"Example");assert_eq!(view.tray_row().status,Status::Connecting);
        view.record(&json!({"event":"certificate"}));assert_eq!(view.tray_row().status,Status::Certificate);
        view.latest.remove("challenge");
        view.record(&json!({"event":"permission"}));assert_eq!(view.tray_row().status,Status::Permission);
        view.record(&json!({"event":"state","state":"connected"}));assert_eq!(view.tray_row().status,Status::Connected);
        view.record(&json!({"event":"error","code":"connection_lost"}));
        view.record(&json!({"event":"state","state":"reconnecting"}));assert_eq!(view.tray_row().status,Status::Reconnecting);
        view.record(&json!({"event":"state","state":"preparing"}));assert!(view.latest.get("error").is_none());
        view.record(&json!({"event":"error","code":"authentication_rejected"}));
        view.record(&json!({"event":"ended","cleanupConfirmed":true}));assert_eq!(view.tray_row().status,Status::Failed);
        view.record(&json!({"event":"ended","cleanupConfirmed":true,"stopped":true}));assert_eq!(view.tray_row().status,Status::Idle);
        view.record(&json!({"event":"ended","cleanupConfirmed":false,"stopped":true}));assert_eq!(view.tray_row().status,Status::CleanupFailed);
    }
    #[test]
    fn window_rebuild_replays_bounded_history_and_current_state_without_submitted_secrets() {
        let mut view=Replay{id:"session".into(),..Default::default()};
        view.record(&json!({"sequence":1,"event":"network","details":{"address":"192.0.2.10"}}));
        view.record(&json!({"sequence":2,"event":"state","state":"connected"}));
        view.record(&json!({"sequence":3,"event":"authentication","challengeId":"challenge","message":"private prompt","fields":[]}));
        assert!(view.events().iter().any(|e|e["challengeId"]=="challenge"));
        view.latest.remove("challenge");
        for sequence in 4..504 {view.record(&json!({"sequence":sequence,"event":"log","code":"tcp_connected"}));}
        view.record(&json!({"sequence":504,"event":"statistics","received":1024}));
        let replay=view.events();
        assert!(replay.len()<=204);
        assert!(replay.iter().any(|e|e["event"]=="network"));
        assert!(replay.iter().any(|e|e["state"]=="connected"));
        assert!(replay.windows(2).all(|pair|pair[0]["sequence"].as_u64()<pair[1]["sequence"].as_u64()));
        assert!(!serde_json::to_string(&replay).unwrap().contains("private prompt"));
        view.record(&json!({"sequence":505,"event":"ended","cleanupConfirmed":true}));
        assert_eq!(view.events().last().unwrap()["event"],"ended");
        view.recent.clear();
        assert!(view.events().iter().all(|e|e["replayOnly"]==true));
    }
    #[test]
    fn session_ids_cannot_select_paths_or_other_pipes() {
        assert!(uuid("c5cd1e41-4111-4777-8222-acdafafab123"));
        for id in ["", "../helper", "c5cd1e414411144777482224acdafafab123", "C5cd1e41-4111-4777-8222-acdafafab123"] { assert!(!uuid(id)); }
    }
    #[test]
    fn separate_profiles_can_connect_but_duplicates_and_excess_sessions_cannot() {
        let mut active = HashMap::new();
        let (sender, _) = mpsc::channel(16);
        active.insert("one".into(), Active {profile:"a".into(), sender:sender.clone()});
        assert!(can_start(&active,"two","b"));
        assert!(!can_start(&active,"two","a"));
        assert!(!can_start(&active,"one","b"));
        for i in 1..8 { active.insert(i.to_string(), Active {profile:format!("profile-{i}"),sender:sender.clone()}); }
        assert!(!can_start(&active,"extra","extra"));
        active.remove("one");
        assert!(can_start(&active,"new","a"));
    }
}
