use std::fs;
use std::net::TcpListener;
use std::time::Duration;

use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_vpn_manager::*;

fn strings(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| s.to_string()).collect()
}

#[test]
fn desktop_mode_per_os() {
    assert_eq!(DesktopMode::current(DesktopOs::Linux), DesktopMode::LinuxTun);
    assert_eq!(DesktopMode::current(DesktopOs::Windows), DesktopMode::WindowsTun);
    assert_eq!(DesktopMode::current(DesktopOs::MacOs), DesktopMode::SystemProxy);
    assert_eq!(DesktopMode::current(DesktopOs::Other), DesktopMode::SystemProxy);
}

#[test]
fn auto_reconnect_backoff() {
    assert_eq!(auto_reconnect_delay_ms(0), 2_000);
    assert_eq!(auto_reconnect_delay_ms(1), 4_000);
    assert_eq!(auto_reconnect_delay_ms(3), 16_000);
    assert_eq!(auto_reconnect_delay_ms(4), 30_000);
    assert_eq!(auto_reconnect_delay_ms(20), 30_000);
    assert_eq!(
        auto_reconnect_log(None, 4_000),
        "Reconnect failed (unknown); retrying in 4s"
    );
    assert_eq!(
        auto_reconnect_log(Some("timeout"), 30_000),
        "Reconnect failed (timeout); retrying in 30s"
    );
}

#[test]
fn free_tun_port_block() {
    assert_eq!(free_tun_port_base(10808, |_| false), None);
    assert_eq!(free_tun_port_base(10808, |p| p == 10810), Some(27960));
    assert_eq!(free_tun_port_base(10808, |p| p == 10810 || p == 27961), Some(27968));
    // The block must fit under MAX_PORT.
    assert_eq!(free_tun_port_base(65530, |_| false), Some(27960));
    // Nothing free anywhere: keep the configured port.
    assert_eq!(free_tun_port_base(10808, |_| true), None);
}

#[test]
fn with_free_tun_port_moves_and_logs() {
    let settings = DesktopSocksProxySettings::default();
    let mut logs: Vec<String> = Vec::new();
    let same = with_free_tun_port(&settings, |_| false, &mut |m: String| logs.push(m));
    assert_eq!(same, settings);
    assert!(logs.is_empty());

    let moved = with_free_tun_port(&settings, |p| p == settings.port, &mut |m: String| logs.push(m));
    assert_eq!(moved.port, TUN_FALLBACK_PORT);
    assert_eq!(moved.host, settings.host);
    assert_eq!(
        logs,
        vec![format!(
            "Local port {} is taken by another program \u{2014} this session uses {}",
            settings.port, TUN_FALLBACK_PORT
        )]
    );
}

#[test]
fn shell_quoting() {
    assert_eq!(shell_quoted_command(&strings(&["a b", "it's"])), "'a b' 'it'\\''s'");
    assert_eq!(shell_quoted_command(&[]), "");
}

#[test]
fn linux_launch_script_text() {
    let script = linux_tun_launch_script(
        &strings(&["/opt/hev", "cfg.yml"]),
        &strings(&["/opt/olcrtc", "-c", "x.yaml"]),
    );
    assert_eq!(
        script,
        "#!/bin/sh\n'/opt/hev' 'cfg.yml' 2>&1 | sed -u 's/^/[hev] /' &\nexec '/opt/olcrtc' '-c' 'x.yaml'\n"
    );
}

#[test]
fn write_launch_script_to_runtime_dir() {
    let dir = std::env::temp_dir().join(format!("yptun-dvm-{}", std::process::id()));
    let path = write_linux_tun_launch_script(&dir, &strings(&["hev"]), &strings(&["olc"])).unwrap();
    assert_eq!(path, dir.join("runtime").join("linux-tun-launch.sh"));
    assert_eq!(
        fs::read_to_string(&path).unwrap(),
        linux_tun_launch_script(&strings(&["hev"]), &strings(&["olc"]))
    );
    let stop = write_privileged_stop_script(&dir, "true\n").unwrap();
    assert!(stop.file_name().unwrap().to_string_lossy().starts_with("linux-tun-stop-"));
    assert_eq!(fs::read_to_string(&stop).unwrap(), "true\n");
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn kill_script_text() {
    let expected = [
        "kill -TERM 42 2>/dev/null || true",
        "i=0",
        "while kill -0 42 2>/dev/null; do",
        "    i=$((i + 1))",
        "    if [ $i -ge 30 ]; then kill -KILL 42 2>/dev/null || true; break; fi",
        "    sleep 0.1",
        "done",
    ]
    .join("\n");
    assert_eq!(kill_with_escalation_script(42), expected);
}

#[test]
fn privileged_stop_script_contents() {
    assert_eq!(privileged_stop_script(None, &[]), None);
    assert_eq!(
        privileged_stop_script(None, &strings(&["ip link del tun0"])),
        Some(String::from("ip link del tun0\n"))
    );
    let both = privileged_stop_script(Some(7), &strings(&["cleanup"])).unwrap();
    assert!(both.starts_with("kill -TERM 7 2>/dev/null || true\n"));
    assert!(both.ends_with("done\ncleanup\n"));
}

#[test]
fn fatal_startup_lines() {
    assert!(is_fatal_olcrtc_startup_line("ERROR Failed to connect link"));
    assert!(is_fatal_olcrtc_startup_line("join room failed: 403"));
    assert!(is_fatal_olcrtc_startup_line("get room token: request failed"));
    assert!(is_fatal_olcrtc_startup_line("Transport connect FAILED"));
    assert!(!is_fatal_olcrtc_startup_line("get room token ok"));
    assert!(!is_fatal_olcrtc_startup_line("socks5 listening"));
}

#[test]
fn public_ipv4() {
    assert!(is_public_ipv4("8.8.8.8"));
    assert!(is_public_ipv4("172.32.0.1"));
    assert!(!is_public_ipv4("10.0.0.1"));
    assert!(!is_public_ipv4("127.0.0.1"));
    assert!(!is_public_ipv4("0.1.2.3"));
    assert!(!is_public_ipv4("169.254.1.1"));
    assert!(!is_public_ipv4("172.16.0.1"));
    assert!(!is_public_ipv4("192.168.1.1"));
    assert!(!is_public_ipv4("100.64.0.1"));
    assert!(!is_public_ipv4("256.1.1.1"));
    assert!(!is_public_ipv4("1.2.3"));
    assert!(!is_public_ipv4(""));
}

#[test]
fn socks_probe_sees_listener() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port() as i32;
    assert!(can_connect_to_socks(port));
    assert!(!can_connect_to_socks(-1));
    assert!(!can_connect_to_socks(70_000));
}

struct Fake {
    superseded: bool,
    failure: Option<String>,
    ready: bool,
    alive: bool,
}

impl OlcRtcStartup for Fake {
    fn superseded(&self) -> bool {
        self.superseded
    }
    fn startup_failure(&self) -> Option<String> {
        self.failure.clone()
    }
    fn ready(&self) -> bool {
        self.ready
    }
    fn is_alive(&mut self) -> bool {
        self.alive
    }
}

fn fake(superseded: bool, failure: Option<&str>, ready: bool, alive: bool) -> Fake {
    Fake {
        superseded,
        failure: failure.map(String::from),
        ready,
        alive,
    }
}

fn wait(f: &mut Fake, timeout_ms: u64) -> Result<(), OlcRtcStartError> {
    wait_for_olcrtc_ready_with(f, -1, Duration::from_millis(timeout_ms), Duration::from_millis(50))
}

#[test]
fn olcrtc_ready_wait_paths() {
    assert_eq!(wait(&mut fake(true, None, true, true), 1_000), Err(OlcRtcStartError::Superseded));
    assert_eq!(
        wait(&mut fake(false, Some("boom"), false, true), 1_000).unwrap_err().to_string(),
        "olcRTC failed before desktop proxy was enabled: boom"
    );
    assert_eq!(
        wait(&mut fake(false, None, false, false), 1_000).unwrap_err().to_string(),
        "olcRTC exited before SOCKS5 was ready"
    );
    assert_eq!(
        wait(&mut fake(false, None, true, false), 1_000).unwrap_err().to_string(),
        "olcRTC exited before desktop proxy was enabled"
    );
    assert_eq!(wait(&mut fake(false, None, true, true), 1_000), Ok(()));
    assert_eq!(
        wait(&mut fake(false, None, false, true), 0).unwrap_err().to_string(),
        "olcRTC start timed out"
    );
    assert_eq!(OlcRtcStartError::Superseded.to_string(), "Desktop start superseded");
}

#[test]
fn run_bounded_logging() {
    let mut logs: Vec<String> = Vec::new();
    run_bounded("ok", &mut |m: String| logs.push(m), || Ok(()));
    assert!(logs.is_empty());
    run_bounded("step", &mut |m: String| logs.push(m), || Err(String::from("bad")));
    assert_eq!(logs, vec![String::from("step failed: bad")]);
    logs.clear();
    run_bounded_with("slow", Duration::from_millis(50), &mut |m: String| logs.push(m), || {
        std::thread::sleep(Duration::from_millis(500));
        Ok(())
    });
    assert_eq!(
        logs,
        vec![String::from("slow did not finish in 0s \u{2014} continuing without it")]
    );
}

#[test]
fn constants_match_kotlin() {
    assert_eq!(PORT_BLOCK, 8);
    assert_eq!(TUN_FALLBACK_PORT, 27960);
    assert_eq!(HEV_LOG_PREFIX, "[hev] ");
    assert_eq!(VK_TURN_ASNS, ["47541", "47764"]);
    assert_eq!(IP_ECHO_URLS[0], "https://api.ipify.org");
    assert_eq!(MAX_LOG_ENTRIES, 5_000);
}
