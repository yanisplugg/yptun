use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use yptun_core::desktop_native_assets::*;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("yptun-na-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

#[derive(Default)]
struct Res(HashMap<String, Vec<u8>>);

impl Res {
    fn with(items: &[(&str, &str)]) -> Self {
        Res(items.iter().map(|(k, v)| (k.to_string(), v.as_bytes().to_vec())).collect())
    }
}

impl NativeResources for Res {
    fn open(&self, name: &str) -> Option<Box<dyn Read>> {
        self.0.get(name).map(|b| Box::new(std::io::Cursor::new(b.clone())) as Box<dyn Read>)
    }
}

fn env(dir: &Path, os: DesktopOs, arch: &str) -> NativeAssetEnv {
    NativeAssetEnv {
        app_data_dir: dir.to_path_buf(),
        os,
        arch: arch.into(),
        cwd: dir.join("cwd"),
        olcrtc_binary: None,
        olcrtc_repo: None,
        hev_socks5_tunnel_binary: None,
        tun2socks_binary: None,
    }
}

#[test]
fn arch_mapping() {
    assert_eq!(desktop_arch("x86_64").unwrap(), "amd64");
    assert_eq!(desktop_arch("aarch64").unwrap(), "arm64");
    assert_eq!(desktop_arch_fallback("arm64").unwrap(), "amd64");
    assert_eq!(desktop_arch_fallback("amd64").unwrap(), "arm64");
    assert_eq!(desktop_arch("riscv64").unwrap_err(), "Unsupported desktop architecture: riscv64");
}

#[test]
fn file_names() {
    assert_eq!(olcrtc_file_names(DesktopOs::MacOs, "arm64").unwrap(), vec!["olcrtc-darwin-arm64", "olcrtc-darwin-amd64"]);
    assert_eq!(olcrtc_file_names(DesktopOs::Windows, "amd64").unwrap(), vec!["olcrtc-windows-amd64.exe", "olcrtc-windows-arm64.exe"]);
    assert_eq!(olcrtc_file_names(DesktopOs::Linux, "x86_64").unwrap(), vec!["olcrtc-linux-amd64"]);
    assert_eq!(olcrtc_file_names(DesktopOs::Other, "amd64").unwrap_err(), UNSUPPORTED_OS_ERROR);
    assert_eq!(missing_olcrtc_error(&["a".into(), "b".into()]), "Bundled native binary is missing: native/a, native/b");
    assert_eq!(hev_socks5_tunnel_file_name(DesktopOs::Linux, "arm64").unwrap(), "hev-socks5-tunnel-linux-arm64");
    assert!(hev_socks5_tunnel_file_name(DesktopOs::Windows, "arm64").is_err());
    assert_eq!(windows_tun2socks_file_name(DesktopOs::Windows, "amd64").unwrap(), "tun2socks-windows-amd64.exe");
    assert_eq!(cronet_file_name(DesktopOs::MacOs), "libcronet.dylib");
    assert_eq!(cronet_file_name(DesktopOs::Other), "libcronet.so");
    assert_eq!(trust_tunnel_file_name(DesktopOs::Windows, "amd64", "wizard").unwrap(), "trusttunnel-wizard-windows-amd64.exe");
    assert_eq!(trust_tunnel_file_name(DesktopOs::MacOs, "amd64", "client").unwrap_err(), "Trust Tunnel is not bundled for MacOS");
    assert_eq!(openflux_file_name(DesktopOs::Linux, "amd64").unwrap(), "openflux-linux-amd64");
    assert_eq!(csqtt_file_names(DesktopOs::Windows, "arm64").unwrap(), ("csqtthost-windows-arm64.exe".into(), "csqtt-windows-arm64.exe".into()));
    assert_eq!(csqtt_file_names(DesktopOs::Linux, "amd64").unwrap(), ("csqtthost-linux-amd64".into(), "csqtt-linux-amd64".into()));
    assert_eq!(snolc_file_name(DesktopOs::Other, "amd64").unwrap_err(), "snolc is not bundled for Other");
}

#[test]
fn olcrtc_candidates() {
    let c = olcrtc_source_candidates("olcrtc-linux-amd64", Some("/opt/olcrtc-linux-amd64"), Some("  "));
    assert_eq!(c[0], PathBuf::from("/opt/olcrtc-linux-amd64"));
    assert_eq!(c[1], Path::new("..").join("olcrtc").join("build").join("olcrtc-linux-amd64"));
    assert_eq!(c.len(), 4);
    let c = olcrtc_source_candidates("olcrtc-windows-amd64.exe", None, Some("/src/olcrtc"));
    assert_eq!(c[0], Path::new("/src/olcrtc").join("build").join("olcrtc-windows-amd64.exe"));
    assert_eq!(c[1], Path::new("/src/olcrtc").join("olcrtc-windows-amd64"));
    assert_eq!(c[2], Path::new("/src/olcrtc").join("olcrtc"));
    assert_eq!(c.len(), 6);
    let d = olcrtc_data_source_candidates("names", Some("/r"));
    assert_eq!(d, vec![Path::new("/r").join("data").join("names"), Path::new("..").join("olcrtc").join("data").join("names")]);
}

#[test]
fn tree_candidates() {
    let roots = project_root_candidates(Path::new("/a/b/c/d/e/f"));
    assert_eq!(roots.len(), 5);
    assert_eq!(roots[4], PathBuf::from("/a/b"));
    let roots = vec![PathBuf::from("/r")];
    let c = desktop_native_resource_candidates("x", &roots);
    assert_eq!(c.len(), 2);
    let h = hev_socks5_tunnel_source_candidates("h", Some("/bin/hev"), &roots);
    assert_eq!(h.len(), 5);
    assert_eq!(h[0], PathBuf::from("/bin/hev"));
    assert!(h[4].ends_with("hev-socks5-tunnel"));
    let t = windows_tun2socks_source_candidates("t", Some(""), &roots);
    assert_eq!(t.len(), 2);
}

#[test]
fn resolve_copies_resource_then_candidate() {
    let dir = temp_dir("resolve");
    let e = env(&dir, DesktopOs::Linux, "amd64");
    let res = Res::with(&[("native/openflux-linux-amd64", "bin")]);
    let a = DesktopNativeAssets::new(&e, &res);
    let path = a.resolve_openflux_binary().unwrap();
    assert_eq!(path, dir.join("bin").join("openflux-linux-amd64"));
    assert_eq!(std::fs::read_to_string(&path).unwrap(), "bin");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_ne!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o100, 0);
    }
    assert_eq!(a.resolve_snolc_binary().unwrap_err(), "Bundled native binary is missing: native/snolc-linux-amd64");
    let source = dir.join("src-file");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(&source, "dev").unwrap();
    let p = a.resolve_binary("tool", &[dir.join("nope"), source]).unwrap();
    assert_eq!(std::fs::read_to_string(p).unwrap(), "dev");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn olcrtc_falls_back_to_other_arch_and_data_dir() {
    let dir = temp_dir("olcrtc");
    let e = env(&dir, DesktopOs::Windows, "arm64");
    let res = Res::with(&[
        ("native/olcrtc-windows-amd64.exe", "x"),
        ("olcrtc-data/names", "n"),
        ("olcrtc-data/surnames", "s"),
        ("native/wintun.dll", "w"),
    ]);
    let a = DesktopNativeAssets::new(&e, &res);
    let found = a.resolve_olcrtc_binary_candidates().unwrap();
    assert_eq!(found, vec![dir.join("bin").join("olcrtc-windows-amd64.exe")]);
    let data = a.resolve_olcrtc_data_dir().unwrap();
    assert_eq!(std::fs::read_to_string(data.join("surnames")).unwrap(), "s");
    assert_eq!(a.resolve_cronet_library_dir().unwrap(), dir.join("bin"));
    assert_eq!(a.copy_runtime_asset("wintun.dll").unwrap(), dir.join("bin").join("wintun.dll"));
    assert_eq!(a.copy_runtime_asset("none.dll").unwrap_err(), "Bundled runtime asset is missing: native/none.dll");
    let empty = Res::default();
    let b = DesktopNativeAssets::new(&e, &empty);
    assert!(b.resolve_windows_tun2socks_binary().unwrap_err().starts_with("Bundled native binary is missing"));
    let _ = std::fs::remove_dir_all(&dir);
}
