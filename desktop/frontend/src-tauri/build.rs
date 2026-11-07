use std::{fs, io::Write, path::PathBuf};
use sha2::{Digest, Sha256};

fn embed_windows_runtime() {
    let out = PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let mut source = String::from("pub static FILES: &[(&str, &[u8], &str, u64)] = &[\n");
    let mut bundle = Sha256::new();
    let mut files: Vec<_> = fs::read_dir("resources/native").expect("prepare native components first")
        .map(|entry| entry.unwrap().path()).filter(|path| path.is_file()).collect();
    files.sort();
    for path in files {
        let name = path.file_name().unwrap().to_str().unwrap();
        assert!(name.bytes().all(|c| c.is_ascii_alphanumeric() || b".-_".contains(&c)));
        assert!(name.ends_with(".exe") || name.ends_with(".dll"));
        println!("cargo:rerun-if-changed={}", path.display());
        let bytes = fs::read(&path).unwrap();
        let hash = format!("{:x}", Sha256::digest(&bytes));
        bundle.update(name.as_bytes()); bundle.update(hash.as_bytes());
        let compressed = out.join(format!("{name}.gz"));
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(&bytes).unwrap();
        fs::write(&compressed, encoder.finish().unwrap()).unwrap();
        source.push_str(&format!("({name:?}, include_bytes!({:?}), {hash:?}, {}),\n", compressed, bytes.len()));
    }
    source.push_str(&format!("];\npub const REVISION: &str = {:?};\n", format!("{:x}", bundle.finalize())));
    fs::write(out.join("native_payload.rs"), source).unwrap();
}

fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") { embed_windows_runtime(); }
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(
            tauri_build::AppManifest::new().commands(&["desktop_request", "vpn_start", "vpn_send", "vpn_snapshot", "vpn_clear_log", "desktop_preferences", "desktop_close_action", "desktop_set_close_behavior", "desktop_save_preferences", "desktop_release_ready"]),
        ),
    ).expect("Tauri build configuration failed");
}
