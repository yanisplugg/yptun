use yptun_core::olcrtc_native::{current_library_spec, olcrtc_native_library_spec};

fn file(os: &str, arch: &str) -> Option<String> {
    olcrtc_native_library_spec(Some(os), Some(arch)).map(|s| s.file_name)
}

#[test]
fn spec_table() {
    assert_eq!(file("Mac OS X", "aarch64").as_deref(), Some("libolcrtc-darwin-arm64.dylib"));
    assert_eq!(file("macos", "x86_64").as_deref(), Some("libolcrtc-darwin-amd64.dylib"));
    assert_eq!(file("Linux", "amd64").as_deref(), Some("libolcrtc-linux-amd64.so"));
    assert_eq!(file("linux", "arm64").as_deref(), Some("libolcrtc-linux-arm64.so"));
    assert_eq!(file("Windows 11", "amd64").as_deref(), Some("olcrtc-windows-amd64.dll"));
    assert_eq!(file("windows", "x86_64").as_deref(), Some("olcrtc-windows-amd64.dll"));
    assert_eq!(file("Windows 11", "aarch64"), None);
    assert_eq!(file("Linux", "x86"), None);
    assert_eq!(file("FreeBSD", "amd64"), None);
    assert_eq!(olcrtc_native_library_spec(None, None), None);
}

#[cfg(any(windows, target_os = "linux", target_os = "macos"))]
#[test]
fn current_platform_and_missing_library() {
    if cfg!(any(target_arch = "x86_64", all(target_arch = "aarch64", not(windows)))) {
        assert!(current_library_spec().is_some());
    }
    let missing = std::env::temp_dir().join("yptun-no-such-olcrtc-lib.bin");
    assert!(yptun_core::olcrtc_native::OlcRtcNative::load(&missing).is_err());
    assert!(yptun_core::olcrtc_native::OlcRtcNative::load_current(&|_| None).is_none());
}
