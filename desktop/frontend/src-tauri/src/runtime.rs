use std::{fs::{self, File, OpenOptions}, io::{self, Read, Write}, path::{Path, PathBuf}};
use sha2::{Digest, Sha256};

include!(concat!(env!("OUT_DIR"), "/native_payload.rs"));

fn matches(path: &Path, hash: &str, size: u64) -> io::Result<bool> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_file() || metadata.len() != size { return Ok(false); }
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new(); let mut buffer = [0u8; 65536];
    loop { let read = file.read(&mut buffer)?; if read == 0 { break; } hasher.update(&buffer[..read]); }
    Ok(format!("{:x}", hasher.finalize()) == hash)
}

pub fn extract(base: &Path) -> io::Result<PathBuf> {
    let directory = base.join("runtime").join(REVISION);
    fs::create_dir_all(&directory)?;
    if fs::symlink_metadata(&directory)?.file_type().is_symlink() { return Err(io::ErrorKind::PermissionDenied.into()); }
    for (name, compressed, hash, size) in FILES {
        let target = directory.join(name);
        if target.exists() {
            if !matches(&target, hash, *size)? { return Err(io::Error::other("native_runtime_invalid")); }
            continue;
        }
        let temporary = directory.join(format!(".{name}.{}.tmp", std::process::id()));
        let result = (|| -> io::Result<()> {
            let mut file = OpenOptions::new().create_new(true).write(true).open(&temporary)?;
            let mut decoder = flate2::read::GzDecoder::new(*compressed).take(size + 1);
            if io::copy(&mut decoder, &mut file)? != *size { return Err(io::ErrorKind::InvalidData.into()); }
            file.flush()?; drop(file);
            if !matches(&temporary, hash, *size)? { return Err(io::ErrorKind::InvalidData.into()); }
            // Concurrent launches may have completed the same immutable bundle already.
            match fs::rename(&temporary, &target) {
                Ok(()) => Ok(()),
                Err(_) if matches(&target, hash, *size).unwrap_or(false) => Ok(()),
                Err(error) => Err(error),
            }
        })();
        let _ = fs::remove_file(&temporary);
        result?;
    }
    Ok(directory)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn embedded_runtime_reuses_valid_files_and_rejects_corruption() {
        let base = std::env::temp_dir().join(format!("geekoc-runtime-test-{}",std::process::id()));
        fs::create_dir_all(&base).unwrap();
        let directory = extract(&base).unwrap();
        assert!(directory.join("geekoctunnel-core.exe").is_file());
        assert_eq!(extract(&base).unwrap(), directory);
        fs::write(directory.join("geekoctunnel-helper.exe"), b"corrupt").unwrap();
        assert!(extract(&base).is_err());
        fs::remove_dir_all(base).unwrap();
    }
}
