use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::{Mutex, atomic::{AtomicBool, AtomicU64, Ordering}};
use tauri::{Emitter, Manager, tray::{TrayIconBuilder, TrayIconEvent, MouseButton, MouseButtonState}};

#[derive(Clone, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields, rename_all="camelCase")]
pub struct Settings {
    schema_version: u32,
    close_behavior: String,
    minimize_to_tray: bool,
    lightweight_mode: bool,
    lightweight_delay_seconds: u64,
    pub auto_reconnect: bool,
    pub ignore_certificate_errors: bool,
}
impl Default for Settings {
    fn default() -> Self { Self {schema_version:1,close_behavior:"ask".into(),minimize_to_tray:true,
        lightweight_mode:false,lightweight_delay_seconds:60,auto_reconnect:true,ignore_certificate_errors:false} }
}
impl Settings {
    fn valid(&self) -> bool { self.schema_version==1 && matches!(self.close_behavior.as_str(),"ask"|"tray"|"exit")
        && (5..=3600).contains(&self.lightweight_delay_seconds) }
}
pub struct Desktop {
    settings: Mutex<Settings>, tray_available: AtomicBool, hidden: AtomicBool, epoch: AtomicU64,
    sleep_task: Mutex<Option<tauri::async_runtime::JoinHandle<()>>>,
}
#[cfg(target_os="linux")]
fn tray_host_available() -> bool {
    let result=(|| -> zbus::Result<bool> {
        let connection=zbus::blocking::connection::Builder::session()?.method_timeout(std::time::Duration::from_secs(1)).build()?;
        let proxy=zbus::blocking::fdo::DBusProxy::new(&connection)?;
        Ok(proxy.name_has_owner("org.kde.StatusNotifierWatcher".try_into()?)?)
    })();
    result.unwrap_or(false)
}
fn tray_available(app: &tauri::AppHandle) -> bool {
    let created=app.state::<Desktop>().tray_available.load(Ordering::SeqCst);
    #[cfg(target_os="linux")]
    { created && tray_host_available() }
    #[cfg(not(target_os="linux"))]
    { created }
}
pub fn settings(app: &tauri::AppHandle) -> Settings {
    app.state::<Desktop>().settings.lock().unwrap_or_else(|e|e.into_inner()).clone()
}
fn cancel_sleep(state: &Desktop) {
    state.epoch.fetch_add(1,Ordering::SeqCst);
    if let Some(task)=state.sleep_task.lock().unwrap_or_else(|e|e.into_inner()).take() {task.abort();}
}
fn schedule_sleep(app: &tauri::AppHandle) {
    let state=app.state::<Desktop>(); cancel_sleep(&state);
    let prefs=settings(app);
    if !prefs.lightweight_mode || !state.hidden.load(Ordering::SeqCst) || !tray_available(app) {return;}
    let epoch=state.epoch.load(Ordering::SeqCst);
    let handle=app.clone();
    let task=tauri::async_runtime::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(prefs.lightweight_delay_seconds)).await;
        let app=handle.clone();
        let _=handle.run_on_main_thread(move || {
            let state=app.state::<Desktop>();
            if state.epoch.load(Ordering::SeqCst)==epoch && state.hidden.load(Ordering::SeqCst) {
                if let Some(window)=app.get_webview_window("main") {let _=window.emit("desktop-prepare-release",epoch);}
            }
        });
    });
    *state.sleep_task.lock().unwrap_or_else(|e|e.into_inner())=Some(task);
}
pub fn show(app: &tauri::AppHandle) {
    let state=app.state::<Desktop>(); state.hidden.store(false,Ordering::SeqCst); cancel_sleep(&state);
    let handle=app.clone();
    let _=app.run_on_main_thread(move || {
        let window=handle.get_webview_window("main").or_else(|| {
            let config=handle.config().app.windows.first()?;
            tauri::WebviewWindowBuilder::from_config(&handle,config).ok()?.build().ok()
        });
        if let Some(window)=window {
            let _=window.show();let _=window.unminimize();let _=window.set_focus();
            let _=window.emit("desktop-visibility",true);
        }
    });
}
fn hide(app: &tauri::AppHandle) -> Result<(),String> {
    let state=app.state::<Desktop>();
    if !tray_available(app) {return Err("tray_unavailable".into());}
    let window=app.get_webview_window("main").ok_or("window_unavailable")?;
    window.hide().map_err(|_|"window_unavailable")?;
    state.hidden.store(true,Ordering::SeqCst);
    let _=window.emit("desktop-visibility",false);schedule_sleep(app);Ok(())
}
pub fn window_event(window: &tauri::Window, event: &tauri::WindowEvent) {
    let app=window.app_handle();
    if app.try_state::<Desktop>().is_none() {return;}
    match event {
        tauri::WindowEvent::CloseRequested{api,..}=>{api.prevent_close();close_requested(app);},
        tauri::WindowEvent::Resized(_) if window.is_minimized().unwrap_or(false)=>{
            if settings(app).minimize_to_tray && tray_available(app) {let _=hide(app);}
            else {
                app.state::<Desktop>().hidden.store(true,Ordering::SeqCst);
                let _=window.emit("desktop-visibility",false);schedule_sleep(app);
            }
        },
        tauri::WindowEvent::Focused(true)=>{
            let state=app.state::<Desktop>();state.hidden.store(false,Ordering::SeqCst);cancel_sleep(&state);
            let _=window.emit("desktop-visibility",true);
        },_=>{}
    }
}
fn exit(app: &tauri::AppHandle) {if !crate::vpn::close_requested(app) {app.exit(0);}}
#[tauri::command]
pub fn desktop_release_ready(app: tauri::AppHandle, epoch: u64) {
    let state=app.state::<Desktop>();
    if state.epoch.load(Ordering::SeqCst)==epoch && state.hidden.load(Ordering::SeqCst) && settings(&app).lightweight_mode && tray_available(&app) {
        if let Some(window)=app.get_webview_window("main") {let _=window.destroy();}
    }
}
#[tauri::command]
pub fn desktop_preferences(app: tauri::AppHandle) -> Value {
    let mut value=serde_json::to_value(settings(&app)).unwrap();
    value["trayAvailable"]=json!(tray_available(&app));value
}
#[tauri::command]
pub fn desktop_save_preferences(app: tauri::AppHandle, preferences: Settings) -> Result<Value,String> {
    if !preferences.valid() {return Err("invalid_preferences".into());}
    let state=app.state::<Desktop>();
    let directory=app.path().app_config_dir().map_err(|_|"preferences_write_failed")?;
    std::fs::create_dir_all(&directory).map_err(|_|"preferences_write_failed")?;
    let temporary=directory.join(format!("window-preferences.{}.tmp",std::process::id()));
    let mut guard=state.settings.lock().map_err(|_|"preferences_write_failed")?;
    let result=(|| {
        std::fs::write(&temporary,serde_json::to_vec(&preferences).unwrap())?;
        std::fs::rename(&temporary,directory.join("window-preferences.json"))
    })();
    let _=std::fs::remove_file(&temporary);
    result.map_err(|_|"preferences_write_failed")?;*guard=preferences;drop(guard);
    schedule_sleep(&app);
    let value=desktop_preferences(app.clone());let _=app.emit_to("main","desktop-preferences-changed",&value);Ok(value)
}
#[tauri::command]
pub fn desktop_set_close_behavior(app: tauri::AppHandle, behavior: String) -> Result<(),String> {
    let mut prefs=settings(&app);prefs.close_behavior=behavior;desktop_save_preferences(app,prefs).map(|_|())
}
#[tauri::command]
pub fn desktop_close_action(app: tauri::AppHandle, action: String, remember: bool) -> Result<(),String> {
    if !matches!(action.as_str(),"tray"|"exit") {return Err("invalid_request".into());}
    if action=="tray" && !tray_available(&app) {return Err("tray_unavailable".into());}
    if remember {desktop_set_close_behavior(app.clone(),action.clone())?;}
    if action=="tray" {hide(&app)} else {exit(&app);Ok(())}
}
fn close_requested(app: &tauri::AppHandle) {
    match settings(app).close_behavior.as_str() {
        "tray" if hide(app).is_ok()=>{},"exit"=>exit(app),
        _=>{if let Some(window)=app.get_webview_window("main") {let _=window.emit("desktop-close-requested",());}}
    }
}
pub fn setup(app: &mut tauri::App) -> Result<(),Box<dyn std::error::Error>> {
    let path=app.path().app_config_dir()?.join("window-preferences.json");
    let preferences=std::fs::read(path).ok().and_then(|bytes|serde_json::from_slice::<Settings>(&bytes).ok()).filter(Settings::valid).unwrap_or_default();
    app.manage(Desktop{settings:Mutex::new(preferences),tray_available:AtomicBool::new(false),hidden:AtomicBool::new(false),epoch:AtomicU64::new(0),sleep_task:Mutex::new(None)});
    app.manage(crate::tray_status::TrayState::default());
    let (icon,tooltip,menu)=crate::tray_status::initial(app.handle())?;
    let result=TrayIconBuilder::with_id("geekoctunnel").icon(icon).tooltip(tooltip).menu(&menu).show_menu_on_left_click(false)
        .on_menu_event(|app,event|match event.id.as_ref() {
            "show"=>show(app),"exit"=>exit(app),"ask"=>{let _=desktop_set_close_behavior(app.clone(),"ask".into());show(app);},_=>{}
        })
        .on_tray_icon_event(|tray,event|if matches!(event,TrayIconEvent::Click{button:MouseButton::Left,button_state:MouseButtonState::Up,..}) {show(tray.app_handle());}).build(app);
    if let Ok(tray)=result {app.manage(tray);app.state::<Desktop>().tray_available.store(true,Ordering::SeqCst);}
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "opens a real isolated WebView; run explicitly in a desktop session"]
    #[cfg(any(windows,target_os="linux"))]
    fn real_webview_is_released_and_recreated() {
        let mut context=tauri::generate_context!();
        context.config_mut().identifier="io.github.def-richard.geekoctunnel.lifecycle-verification".into();
        context.config_mut().app.windows[0].title="GeekOCTunnel · 窗口生命周期验证".into();
        let app=crate::app_builder().any_thread().build(context).unwrap();
        let passed=std::sync::Arc::new(AtomicBool::new(false));let result=passed.clone();
        let code=app.run_return(move |app,event| {
          if let tauri::RunEvent::ExitRequested{code:None,api,..}=&event {api.prevent_exit();}
          if matches!(event,tauri::RunEvent::Ready) {
            let handle=app.clone();let result=result.clone();
            {let state=handle.state::<Desktop>();let mut value=state.settings.lock().unwrap();value.lightweight_mode=true;value.lightweight_delay_seconds=5;}
            tauri::async_runtime::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            let binary=std::env::current_exe().unwrap();
            let arguments=["desktop::tests::secondary_instance_probe","--ignored","--exact","--test-threads=1"];
            let mut children=Vec::new();
            for _ in 0..3 {
                children.push(std::process::Command::new(&binary).args(arguments).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().unwrap());
            }
            for mut child in children {if !child.wait().unwrap().success() {handle.exit(2);return;}}
            if handle.webview_windows().len()!=1 {handle.exit(3);return;}
            println!("SINGLE_INSTANCE_BURST=passed");
            crate::vpn::tray_fixture_event(&handle,"fixture-home",json!({"event":"state","state":"connected","profileName":"Home"}));
            crate::vpn::tray_fixture_event(&handle,"fixture-office",json!({"event":"state","state":"reconnecting","profileName":"Office"}));
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let tip=crate::tray_status::verified_tooltip(&handle);
            if !tip.contains("Home：已连接") || !tip.contains("Office：正在重连") {handle.exit(6);return;}
            println!("TRAY_PARTIAL_STATUS=passed");
            let hidden=handle.clone();handle.run_on_main_thread(move || {let _=hide(&hidden);}).unwrap();
            tokio::time::sleep(std::time::Duration::from_secs(8)).await;
            if handle.get_webview_window("main").is_some() {handle.exit(1);return;}
            println!("LIGHTWEIGHT_RELEASED pid={}",std::process::id());
            crate::vpn::tray_fixture_event(&handle,"fixture-office",json!({"event":"state","state":"connected","profileName":"Office"}));
            tokio::time::sleep(std::time::Duration::from_secs(8)).await;
            if !crate::tray_status::verified_tooltip(&handle).contains("已连接 2 台") {handle.exit(7);return;}
            println!("TRAY_WITHOUT_WEBVIEW=passed");
            crate::vpn::sync_profiles(&handle,&json!({"document":{"profiles":[{"id":"fixture-home","name":"Renamed"}]}}));
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            let tip=crate::tray_status::verified_tooltip(&handle);
            if !tip.contains("Renamed：已连接") || tip.contains("Office") {handle.exit(8);return;}
            println!("TRAY_PROFILE_SYNC=passed");
            let status=std::process::Command::new(&binary).args(arguments).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status().unwrap();
            if !status.success() {handle.exit(4);return;}
            tokio::time::sleep(std::time::Duration::from_secs(4)).await;
            result.store(handle.get_webview_window("main").is_some(),Ordering::SeqCst);
            println!("LIGHTWEIGHT_RECREATED={}",result.load(Ordering::SeqCst));handle.exit(0);
            });
          }
        });
        assert_eq!(code,0);assert!(passed.load(Ordering::SeqCst));
    }
    #[test]
    #[ignore = "subprocess used by the real-window single-instance test"]
    #[cfg(any(windows,target_os="linux"))]
    fn secondary_instance_probe() {
        let mut context=tauri::generate_context!();
        context.config_mut().identifier="io.github.def-richard.geekoctunnel.lifecycle-verification".into();
        let app=crate::app_builder().any_thread().build(context).unwrap();
        // A secondary instance must exit in the plugin before reaching Ready.
        let code=app.run_return(|app,event|if matches!(event,tauri::RunEvent::Ready) {app.exit(5);});
        std::process::exit(if code==0 {5} else {code});
    }
    #[test]
    fn settings_migrate_safely_and_validate_limits() {
        let old:Settings=serde_json::from_value(json!({"schemaVersion":1,"closeBehavior":"tray"})).unwrap();
        assert!(old.valid() && old.auto_reconnect && !old.ignore_certificate_errors && !old.lightweight_mode);
        for delay in [0,4,3601,u64::MAX] {assert!(!Settings{lightweight_delay_seconds:delay,..Default::default()}.valid());}
        assert!(!Settings{close_behavior:"shutdown".into(),..Default::default()}.valid());
        assert!(serde_json::from_value::<Settings>(json!({"ignoreCertificateErrors":"true"})).is_err());
        assert!(serde_json::from_value::<Settings>(json!({"password":"synthetic"})).is_err());
    }
}
