#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{process::Stdio, time::Duration};
use tauri::Manager;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
mod vpn;
mod desktop;
mod tray_status;
#[cfg(windows)]
mod runtime;
struct NativeDirectory(std::path::PathBuf);
#[cfg(target_os="linux")]
struct InstanceFile { _file: std::fs::File }

const MAX_BYTES: usize = 300 * 1024;

fn native_directory(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    if let Some(directory) = app.try_state::<NativeDirectory>() { return Ok(directory.0.clone()); }
    let resources = app.path().resource_dir().map_err(|_| "native_bridge_missing")?.join("native");
    if resources.is_dir() { return Ok(resources); }
    // Standalone Linux development installs keep native components beside the executable.
    let executable = std::env::current_exe().map_err(|_| "native_bridge_missing")?;
    Ok(executable.parent().ok_or("native_bridge_missing")?.join("native"))
}

#[tauri::command]
async fn desktop_request(app: tauri::AppHandle, request: serde_json::Value) -> Result<serde_json::Value, String> {
    let executable = native_directory(&app)?.join(if cfg!(windows) {
        "geekoctunnel-bridge.exe"
    } else { "geekoctunnel-bridge" });
    let command = tokio::process::Command::new(executable);
    let reply=exchange(command, request).await?;
    if reply["ok"]==true {vpn::sync_profiles(&app,&reply);}
    Ok(reply)
}

async fn exchange(mut command: tokio::process::Command, request: serde_json::Value) -> Result<serde_json::Value, String> {
    let bytes = serde_json::to_vec(&request).map_err(|_| "invalid_request")?;
    if bytes.len() > MAX_BYTES { return Err("request_too_large".into()); }
    command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x08000000); // CREATE_NO_WINDOW: the bridge has no interactive console.
    let mut child = command.spawn().map_err(|_| "native_bridge_missing")?;
    let mut input = child.stdin.take().ok_or("native_bridge_failed")?;
    let output = child.stdout.take().ok_or("native_bridge_failed")?;
    let operation = async {
        input.write_all(&bytes).await.map_err(|_| "native_bridge_failed")?;
        input.shutdown().await.map_err(|_| "native_bridge_failed")?;
        drop(input);
        let mut result = Vec::new();
        output.take((MAX_BYTES + 1) as u64).read_to_end(&mut result).await.map_err(|_| "native_bridge_failed")?;
        if result.len() > MAX_BYTES { return Err("invalid_native_reply"); }
        let status = child.wait().await.map_err(|_| "native_bridge_failed")?;
        if !status.success() { return Err("native_bridge_failed"); }
        let value: serde_json::Value = serde_json::from_slice(&result).map_err(|_| "invalid_native_reply")?;
        if value["schemaVersion"] != 1 || value["requestId"] != request["requestId"] {
            return Err("invalid_native_reply");
        }
        Ok(value)
    };
    tokio::time::timeout(Duration::from_secs(10), operation).await
        .map_err(|_| "native_bridge_timeout".to_string())?
        .map_err(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    #[test]
    fn real_native_transport_and_size_limit() {
        let binary = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/native").join(
            if cfg!(windows) { "geekoctunnel-bridge.exe" } else { "geekoctunnel-bridge" });
        tauri::async_runtime::block_on(async {
            let mut command = tokio::process::Command::new(&binary);
            command.arg("--smoke-test");
            let request = serde_json::json!({"schemaVersion": 1, "requestId": "rust-test", "operation": "snapshot"});
            let reply = exchange(command, request).await.expect("native snapshot failed");
            assert_eq!(reply["ok"], true);
            assert_eq!(reply["document"]["profiles"], serde_json::json!([]));
            assert_eq!(reply["capabilities"]["vpn"], false);
            let command = tokio::process::Command::new(&binary);
            assert_eq!(exchange(command, serde_json::json!({"data": "x".repeat(MAX_BYTES)})).await.unwrap_err(), "request_too_large");
        });
    }
}

fn app_builder() -> tauri::Builder<tauri::Wry> {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app,_,_| {
            if app.try_state::<desktop::Desktop>().is_some() {desktop::show(app);}
        }))
        .manage(vpn::Sessions::default())
        .setup(|app| {
            #[cfg(target_os="linux")]
            {
                let directory=app.path().app_config_dir()?;
                std::fs::create_dir_all(&directory)?;
                let lock=std::fs::OpenOptions::new().create(true).truncate(false).write(true).open(directory.join("instance.lock"))?;
                // The plugin can only forward activation when a desktop session bus is available.
                lock.try_lock().map_err(|_|std::io::Error::other("GeekOCTunnel is already running. Open it from the system tray."))?;
                app.manage(InstanceFile { _file: lock });
            }
            #[cfg(windows)]
            app.manage(NativeDirectory(runtime::extract(&app.path().app_local_data_dir()?)?));
            desktop::setup(app)
        })
        .on_window_event(desktop::window_event)
        .invoke_handler(tauri::generate_handler![desktop_request, vpn::vpn_start, vpn::vpn_send, vpn::vpn_snapshot, vpn::vpn_clear_log,
            desktop::desktop_preferences, desktop::desktop_set_close_behavior, desktop::desktop_close_action, desktop::desktop_save_preferences, desktop::desktop_release_ready])
}
fn main() {
    app_builder().build(tauri::generate_context!())
        .expect("Unable to start GeekOCTunnel")
        .run(|_,event| {
            // Destroying the last WebView is lightweight mode; explicit app.exit still exits.
            if let tauri::RunEvent::ExitRequested {code:None,api,..}=event {api.prevent_exit();}
        });
}
