#![cfg(any(windows, target_os = "linux", target_os = "macos"))]

use yptun_core::yptun_core_native::*;

#[test]
fn c_string_stops_at_nul() {
    assert_eq!(c_string("abc").as_bytes(), b"abc");
    assert_eq!(c_string("ab\0cd").as_bytes(), b"ab");
    assert_eq!(c_string("").as_bytes(), b"");
    assert_eq!(c_string("\u{43f}\u{443}\u{442}\u{44c}").as_bytes().len(), 8);
}

#[test]
fn current_library_matches_platform() {
    let arch_known = matches!(std::env::consts::ARCH, "x86_64" | "aarch64");
    let name = current_library_file_name();
    if !arch_known {
        assert_eq!(name, None);
        return;
    }
    let name = name.unwrap();
    let ext = if cfg!(windows) {
        ".dll"
    } else if cfg!(target_os = "macos") {
        ".dylib"
    } else {
        ".so"
    };
    assert!(name.starts_with("yptuncore-") && name.ends_with(ext), "{name}");
}

#[test]
fn missing_or_broken_library_fails_cleanly() {
    let missing = std::env::temp_dir().join("yptuncore-definitely-missing.bin");
    assert!(NativeYpTunCoreLib::load(&missing).is_err());

    let path = extract_to_temp("garbage.bin", b"not a shared library").unwrap();
    let file = path.file_name().unwrap().to_string_lossy().into_owned();
    assert!(file.starts_with("yptuncore-") && file.ends_with("-garbage.bin"), "{file}");
    assert_eq!(std::fs::read(&path).unwrap(), b"not a shared library");
    match NativeYpTunCoreLib::load(&path) {
        Ok(_) => panic!("garbage must not load"),
        Err(e) => assert!(!e.is_empty()),
    }
    let _ = std::fs::remove_file(&path);
}

#[test]
fn load_desktop_without_library_is_unavailable() {
    let dir = std::env::temp_dir().join(format!("yptuncore-empty-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let core = load_desktop(&dir, Some("/nowhere"));
    assert!(!core.is_available());
    assert!(core.sb_start("{}").is_err());
    assert!(!core.sb_running());
    let _ = std::fs::remove_dir_all(&dir);
}
