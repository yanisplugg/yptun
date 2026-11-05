//! Ported from SnolcFilesTest (the config half; the install script is covered in tests/installer.rs).

use std::path::{Path, PathBuf};
use std::process::Command;

use yptun_core::snolc;
use yptun_core::transports::SnolcConfig;

const KEY: &str = "abababababababababababababababababababababababababababababababab";

#[test]
fn uri_round_trips() {
    let c = SnolcConfig {
        host: "203.0.113.7".into(),
        port: 8443,
        public_key: KEY.into(),
        ..Default::default()
    };
    let (parsed, name) = SnolcConfig::parse_uri(&c.to_uri("My node")).unwrap();
    assert_eq!(parsed, c);
    assert_eq!(name, "My node");
    assert!(SnolcConfig::parse_uri(&format!("snolc://[2001:db8::1]:443?key={KEY}")).is_some());
}

fn file<'a>(files: &'a [snolc::SnolcFile], name: &str) -> &'a str {
    &files
        .iter()
        .find(|(n, _)| n == name)
        .unwrap_or_else(|| panic!("no {name}"))
        .1
}

/// The raised limits stay consistent: yamux window = streams × 256 KiB, flows cover every stream.
#[test]
fn limits_are_consistent() {
    let engine = file(&snolc::server(443, "/k", "/s"), "snolc.toml").to_string();
    let num = |key: &str| -> i64 {
        let line = engine
            .lines()
            .find(|l| l.starts_with(&format!("{key} = ")))
            .unwrap();
        line.split(" = ").nth(1).unwrap().parse().unwrap()
    };
    let (sessions, flows, streams, window) = (
        num("max_sessions"),
        num("max_flows"),
        num("max_streams_per_session"),
        num("receive_window_bytes"),
    );
    assert_eq!(
        (sessions, flows, streams, window),
        (4, 512, 129, 33_816_576)
    );
    assert_eq!(window, streams * 262_144);
    assert!(flows >= sessions * (streams - 1));
    assert!(engine.ends_with("policy = \"modules/policy.toml\"\n"));
}

#[test]
fn server_and_client_files() {
    let srv = snolc::server(8443, "/etc/snolc/priv.hex", "/var/lib/snolc");
    let names: Vec<&str> = srv.iter().map(|(n, _)| n.as_str()).collect();
    assert_eq!(
        names,
        [
            "snolc.toml",
            "modules/direct.toml",
            "modules/noise.toml",
            "modules/tcp.toml",
            "modules/policy.toml"
        ]
    );
    assert!(file(&srv, "snolc.toml").contains("state = \"/var/lib/snolc\"\n"));
    assert!(file(&srv, "snolc.toml").contains("levels = [\"warning\", \"error\"]\n"));
    assert!(file(&srv, "modules/tcp.toml").contains("endpoint_ip = \"0.0.0.0:8443\""));
    assert_eq!(
        file(&srv, "modules/noise.toml"),
        "wire_version = 1\ninstance = \"noise-main\"\npackage = \"owenewans/protection-noise@0.0.4\"\nrole = \"server\"\n\n[options]\nmode = \"server\"\nprivate_key_file = \"/etc/snolc/priv.hex\"\n"
    );
    assert!(file(&srv, "modules/direct.toml").ends_with("[options]\ndns_mode = \"system\"\nmax_pending_opens = 64\nmax_resolved_addresses = 16\nresolve_timeout_ms = 15000\nconnect_timeout_ms = 15000\n"));

    let open = snolc::client("127.0.0.1", 1080, "2001:db8::1", 443, true, "", "");
    assert!(file(&open, "snolc.toml").contains("levels = [\"warning\", \"error\", \"debug\"]\n"));
    assert!(file(&open, "modules/tcp.toml").contains("endpoint_ip = \"[2001:db8::1]:443\""));
    assert!(file(&open, "modules/socks5.toml").contains("[options]\nlisten = \"127.0.0.1:1080\"\n"));
    assert!(!file(&open, "modules/socks5.toml").contains("username"));

    let login = snolc::client("127.0.0.1", 1080, "203.0.113.7", 443, false, "u\"1", "p\\1");
    let socks = file(&login, "modules/socks5.toml");
    assert!(
        socks.ends_with("\nusername = \"u\\\"1\"\npassword = \"p\\\\1\"\n"),
        "{socks}"
    );
}

fn prebuilt_engine() -> Option<PathBuf> {
    let os = match std::env::consts::OS {
        "linux" => "linux",
        "windows" => "windows",
        _ => return None,
    };
    let arch = match std::env::consts::ARCH {
        "x86_64" => "amd64",
        "aarch64" => "arm64",
        _ => return None,
    };
    let ext = if os == "windows" { ".exe" } else { "" };
    let exe = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join(format!("../../snolc/prebuilt/snolc-{os}-{arch}{ext}"));
    exe.is_file().then_some(exe)
}

fn write(root: &Path, files: &[snolc::SnolcFile]) {
    for (name, body) in files {
        let path = root.join(name);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, body).unwrap();
    }
}

/// The generated files must satisfy the real engine (skipped when no prebuilt binary for this machine).
#[test]
fn generated_configs_validate_with_the_real_engine() {
    let Some(prebuilt) = prebuilt_engine() else {
        return;
    };
    let dir = std::env::temp_dir().join(format!("yptun-snolc-test-{}", std::process::id()));
    let (srv, cli) = (dir.join("srv"), dir.join("cli"));
    std::fs::create_dir_all(&srv).unwrap();
    // Git does not keep the exec bit of the prebuilt binaries: run a private executable copy.
    let exe = dir.join(prebuilt.file_name().unwrap());
    std::fs::copy(&prebuilt, &exe).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&exe, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    std::fs::write(srv.join("priv.hex"), KEY).unwrap();
    let slash = |p: PathBuf| p.to_string_lossy().replace('\\', "/");
    write(
        &srv,
        &snolc::server(443, &slash(srv.join("priv.hex")), &slash(srv.join("state"))),
    );
    write(
        &cli,
        &snolc::client("127.0.0.1", 1080, "203.0.113.7", 443, false, "u\"1", "p\\1"),
    );
    std::fs::write(cli.join("pub.hex"), KEY).unwrap();
    for root in [&srv, &cli] {
        let out = Command::new(&exe)
            .arg("validate")
            .arg(root.join("snolc.toml"))
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "{}{}",
            String::from_utf8_lossy(&out.stdout),
            String::from_utf8_lossy(&out.stderr)
        );
    }
    let _ = std::fs::remove_dir_all(&dir);
}
