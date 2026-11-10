use std::io::{Cursor, Read, Write};
use std::net::{Shutdown, TcpListener};
use std::path::{Path, PathBuf};
use std::thread;

use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_vpn_manager::{DesktopMode, IP_ECHO_URLS, TUNNEL_HEALTH_HOSTS};
use yptun_core::desktop_vpn_runtime::*;
use yptun_core::desktop_vpn_start::{DesktopStartError, DesktopVpnSession, DesktopVpnStatus, TunStartRequest, START_SUPERSEDED};
use yptun_core::http_proxy_bridge::{http_port_for, socks_auth_request, HttpProxyBridge, HTTP_PORT_OFFSET};
use yptun_core::model::EngineType;

fn socks(port: i32) -> DesktopSocksProxySettings {
    DesktopSocksProxySettings {
        host: String::from("127.0.0.1"),
        port,
        username: String::from("u"),
        password: String::from("p"),
        secured: true,
        custom_port: None,
    }
}

fn tun_request(generation: u64) -> TunStartRequest {
    TunStartRequest {
        socks_port: 10808,
        request_generation: generation,
        bypass_server_ips: vec![String::from("1.2.3.4/32")],
        socks_username: String::from("u"),
        socks_password: String::from("p"),
        udp_over_tcp: false,
    }
}

fn superseded() -> DesktopStartError {
    DesktopStartError::Cancelled(String::from(START_SUPERSEDED))
}

// ------------------------------------------------------------------ start steps

struct Steps {
    generation: u64,
    bump_on_start: bool,
    olcrtc_running: bool,
    bridge_ok: bool,
    take_over: bool,
    calls: Vec<String>,
    logs: Vec<String>,
}

fn steps() -> Steps {
    Steps { generation: 1, bump_on_start: false, olcrtc_running: false, bridge_ok: true, take_over: true, calls: vec![], logs: vec![] }
}

impl Steps {
    fn started(&mut self, name: &str) {
        self.calls.push(name.to_string());
        if self.bump_on_start {
            self.generation += 1;
        }
    }
}

impl LinuxTunSteps for Steps {
    fn generation(&self) -> u64 {
        self.generation
    }
    fn has_olcrtc_process(&self) -> bool {
        self.olcrtc_running
    }
    fn start_hev(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError> {
        self.started(&format!("hev:{}", request.socks_port));
        Ok(())
    }
    fn await_hev_ready(&mut self) -> Result<(), DesktopStartError> {
        self.started("await");
        Ok(())
    }
}

impl WindowsTunSteps for Steps {
    fn generation(&self) -> u64 {
        self.generation
    }
    fn start_tun2socks(&mut self, _request: &TunStartRequest) -> Result<(), DesktopStartError> {
        self.started("tun2socks");
        Ok(())
    }
    fn start_tun_log_reader(&mut self) {
        self.calls.push(String::from("tunlog"));
    }
}

impl SystemProxySteps for Steps {
    fn generation(&self) -> u64 {
        self.generation
    }
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn start_http_bridge(&mut self, _socks: &DesktopSocksProxySettings, bridge_port: i32) -> bool {
        self.calls.push(format!("bridge:{}", bridge_port));
        self.bridge_ok
    }
    fn take_over_system_proxy(&self) -> bool {
        self.take_over
    }
    fn start_pac_server(&mut self, _socks: &DesktopSocksProxySettings) {
        self.calls.push(String::from("pac"));
    }
    fn pac_url(&self) -> String {
        String::from("http://127.0.0.1:1/proxy.pac")
    }
    fn enable_system_proxy(&mut self, http: &str, pac: &str) -> Result<(), DesktopStartError> {
        self.started(&format!("enable:{}|{}", http, pac));
        Ok(())
    }
    fn report_exit_ip_in_background(&mut self, _socks: &DesktopSocksProxySettings, bridge_port: i32) {
        self.calls.push(format!("report:{}", bridge_port));
    }
}

#[test]
fn check_generation_matches_kotlin() {
    assert_eq!(check_generation(3, 3), Ok(()));
    assert_eq!(check_generation(4, 3), Err(superseded()));
}

#[test]
fn linux_tun_starts_hev_or_waits_for_the_olcrtc_wrapper() {
    let mut s = steps();
    assert_eq!(start_linux_tun(&mut s, &tun_request(1)), Ok(()));
    assert_eq!(s.calls, vec!["hev:10808"]);

    let mut s = steps();
    s.olcrtc_running = true;
    assert_eq!(start_linux_tun(&mut s, &tun_request(1)), Ok(()));
    assert_eq!(s.calls, vec!["await"]);

    let mut s = steps();
    s.bump_on_start = true;
    assert_eq!(start_linux_tun(&mut s, &tun_request(1)), Err(superseded()));
}

#[test]
fn windows_tun_reads_logs_only_for_the_current_generation() {
    let mut s = steps();
    assert_eq!(start_windows_tun(&mut s, &tun_request(1)), Ok(()));
    assert_eq!(s.calls, vec!["tun2socks", "tunlog"]);

    let mut s = steps();
    s.bump_on_start = true;
    assert_eq!(start_windows_tun(&mut s, &tun_request(1)), Err(superseded()));
    assert_eq!(s.calls, vec!["tun2socks"]);
}

#[test]
fn http_port_is_socks_plus_four() {
    assert_eq!(HTTP_PORT_OFFSET, 4);
    assert_eq!(http_port_for(10808), 10812);
}

#[test]
fn system_proxy_on_macos_uses_pac_and_reports_exit_ip() {
    let mut s = steps();
    assert_eq!(start_system_proxy(&mut s, &socks(10808), 1, DesktopOs::MacOs), Ok(()));
    assert_eq!(
        s.calls,
        vec!["bridge:10812", "pac", "enable:127.0.0.1:10812|http://127.0.0.1:1/proxy.pac", "report:10812"]
    );
    assert_eq!(s.logs, vec!["Proxy mode: SOCKS5 127.0.0.1:10808 \u{00b7} HTTP 127.0.0.1:10812"]);
}

#[test]
fn system_proxy_on_windows_has_no_pac() {
    let mut s = steps();
    assert_eq!(start_system_proxy(&mut s, &socks(10808), 1, DesktopOs::Windows), Ok(()));
    assert!(!s.calls.iter().any(|c| c == "pac"));
    assert!(s.calls.iter().any(|c| c.starts_with("enable:127.0.0.1:10812")));
}

#[test]
fn system_proxy_left_untouched_when_not_taking_over() {
    let mut s = steps();
    s.take_over = false;
    assert_eq!(start_system_proxy(&mut s, &socks(10808), 1, DesktopOs::MacOs), Ok(()));
    assert_eq!(s.calls, vec!["bridge:10812", "report:10812"]);
    assert_eq!(s.logs[1], PROXY_UNTOUCHED_LOG);
}

#[test]
fn system_proxy_busy_port_and_superseded() {
    let mut s = steps();
    s.bridge_ok = false;
    assert_eq!(
        start_system_proxy(&mut s, &socks(10808), 1, DesktopOs::MacOs),
        Err(DesktopStartError::Failed(String::from("HTTP proxy port 10812 is already in use")))
    );
    assert!(s.logs.is_empty());

    let mut s = steps();
    s.bump_on_start = true;
    assert_eq!(start_system_proxy(&mut s, &socks(10808), 1, DesktopOs::Windows), Err(superseded()));
    assert!(!s.calls.iter().any(|c| c.starts_with("report")));
}

// ------------------------------------------------------------------ exit IP

#[test]
fn http_body_and_self_check_logs() {
    assert_eq!(http_response_body("HTTP/1.1 200 OK\r\nA: b\r\n\r\n 9.9.9.9\n"), "9.9.9.9");
    assert_eq!(http_response_body("garbage"), "");
    assert_eq!(
        proxy_self_check_log(&Ok(String::from("9.9.9.9"))),
        "Proxy self-check: traffic OK \u{2014} exit IP 9.9.9.9"
    );
    assert_eq!(
        proxy_self_check_log(&Ok(String::new())),
        "Proxy self-check: NO traffic through the HTTP proxy (empty response)"
    );
    assert_eq!(
        proxy_self_check_log(&Ok("x".repeat(46))),
        "Proxy self-check: NO traffic through the HTTP proxy (empty response)"
    );
    assert_eq!(
        proxy_self_check_log(&Err(String::from("Connection refused"))),
        "Proxy self-check: NO traffic through the HTTP proxy (Connection refused)"
    );
}

#[test]
fn exit_ip_picks_first_public_answer() {
    let mut asked = Vec::new();
    let ip = first_public_exit_ip(&mut |url: &str| {
        asked.push(url.to_string());
        if asked.len() == 1 {
            Some(String::from("10.0.0.1"))
        } else {
            Some(String::from(" 8.8.8.8\n"))
        }
    });
    assert_eq!(ip.as_deref(), Some("8.8.8.8"));
    assert_eq!(asked, vec![IP_ECHO_URLS[0], IP_ECHO_URLS[1]]);
    assert_eq!(first_public_exit_ip(&mut |_url: &str| None), None);
    assert!(exit_ip_needs_socks(true, true));
    assert!(!exit_ip_needs_socks(true, false));
    assert!(!exit_ip_needs_socks(false, true));
}

fn read_n(s: &mut std::net::TcpStream, n: usize) -> Vec<u8> {
    let mut buf = vec![0u8; n];
    s.read_exact(&mut buf).unwrap();
    buf
}

#[test]
fn bridge_authenticates_to_socks_and_self_check_sees_exit_ip() {
    let socks_listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let socks_port = socks_listener.local_addr().unwrap().port();
    let server = thread::spawn(move || {
        let (mut s, _) = socks_listener.accept().unwrap();
        let greeting = read_n(&mut s, 4);
        s.write_all(&[0x05, 0x02]).unwrap();
        let head = read_n(&mut s, 2);
        let user = read_n(&mut s, head[1] as usize);
        let plen = read_n(&mut s, 1);
        let pass = read_n(&mut s, plen[0] as usize);
        s.write_all(&[0x01, 0x00]).unwrap();
        let req = read_n(&mut s, 5);
        let target = read_n(&mut s, req[4] as usize + 2);
        s.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).unwrap();
        let mut seen = Vec::new();
        while !seen.ends_with(b"\r\n\r\n") {
            seen.extend(read_n(&mut s, 1));
        }
        s.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n203.0.113.9").unwrap();
        let _ = s.shutdown(Shutdown::Both);
        (greeting, String::from_utf8(user).unwrap(), String::from_utf8(pass).unwrap(), target, String::from_utf8_lossy(&seen).into_owned())
    });

    let bridge = HttpProxyBridge::new("127.0.0.1", 0, "127.0.0.1", socks_port, |_m: &str| {}).with_auth("alice", "s3cret");
    assert!(bridge.start());
    let port = bridge.local_addr().unwrap().port() as i32;
    let mut logs = Vec::new();
    report_proxy_exit_ip("127.0.0.1", port, &mut |m: String| logs.push(m));
    bridge.stop();

    let (greeting, user, pass, target, head) = server.join().unwrap();
    assert_eq!(greeting, vec![0x05, 0x02, 0x00, 0x02]);
    assert_eq!(user, "alice");
    assert_eq!(pass, "s3cret");
    assert_eq!(&target[..target.len() - 2], b"api.ipify.org");
    assert_eq!(&target[target.len() - 2..], &[0x00, 0x50]);
    assert!(head.starts_with("GET / HTTP/1.1\r\n"), "{}", head);
    assert_eq!(logs, vec!["Proxy self-check: traffic OK \u{2014} exit IP 203.0.113.9"]);
}

#[test]
fn socks_auth_request_layout() {
    assert_eq!(socks_auth_request("ab", "xyz"), vec![0x01, 2, b'a', b'b', 3, b'x', b'y', b'z']);
    assert_eq!(socks_auth_request("", ""), vec![0x01, 0, 0]);
}

#[test]
fn self_check_reports_connection_errors() {
    let free = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = free.local_addr().unwrap().port() as i32;
    drop(free);
    let mut logs = Vec::new();
    report_proxy_exit_ip("127.0.0.1", port, &mut |m: String| logs.push(m));
    assert_eq!(logs.len(), 1);
    assert!(logs[0].starts_with("Proxy self-check: NO traffic through the HTTP proxy ("), "{}", logs[0]);
    assert!(fetch_exit_ip_via_http_proxy("127.0.0.1", 70000).is_err());
}

// ------------------------------------------------------------------ olcRTC process

#[test]
fn olcrtc_start_log_and_env() {
    assert_eq!(
        olcrtc_start_log("telemost", "datachannel", "room1", 10808),
        "Starting olcRTC provider=telemost, transport=datachannel, room=room1, port=10808"
    );
    assert_eq!(olcrtc_environment(), [("NO_PROXY", "127.0.0.1,localhost"), ("no_proxy", "127.0.0.1,localhost")]);
}

#[test]
fn fallback_tries_next_binary_and_returns_last_error() {
    let bins = vec![PathBuf::from("/opt/a/olcrtc-v3"), PathBuf::from("/opt/b/olcrtc")];
    let mut logs = Vec::new();
    let mut tried = Vec::new();
    let r = start_olcrtc_with_fallback(&bins, &mut |m: String| logs.push(m), |p: &Path| {
        tried.push(p.to_path_buf());
        if tried.len() == 1 {
            Err(String::from("exec format error"))
        } else {
            Ok(7)
        }
    });
    assert_eq!(r, Ok(7));
    assert_eq!(logs, vec!["olcRTC start failed for olcrtc-v3: exec format error. Retrying with fallback binary."]);

    let mut logs = Vec::new();
    let r: Result<i32, String> = start_olcrtc_with_fallback(&bins, &mut |m: String| logs.push(m), |p: &Path| {
        Err(format!("bad {}", p.display()))
    });
    assert_eq!(r, Err(String::from("bad /opt/b/olcrtc")));
    assert_eq!(logs.len(), 1);

    let r: Result<i32, String> = start_olcrtc_with_fallback(&[], &mut |_m: String| {}, |_p: &Path| Ok(1));
    assert_eq!(r, Err(String::from(OLCRTC_ALL_FAILED)));
}

#[test]
fn olcrtc_lines_are_classified() {
    let hev = classify_olcrtc_output_line("[hev] tunnel up", true);
    assert_eq!(hev, OlcRtcLineOutcome { log: Some(String::from("tun: tunnel up")), ready: false, fatal: false });
    let hev_quiet = classify_olcrtc_output_line("[hev] join room failed", false);
    assert_eq!(hev_quiet, OlcRtcLineOutcome::default());
    let ready = classify_olcrtc_output_line("2026 SOCKS5 Server Listening on 127.0.0.1:10808", true);
    assert!(ready.ready && !ready.fatal);
    assert_eq!(ready.log.as_deref(), Some("rtc: 2026 SOCKS5 Server Listening on 127.0.0.1:10808"));
    let fatal = classify_olcrtc_output_line("join room failed: 403", false);
    assert!(fatal.fatal && !fatal.ready && fatal.log.is_none());
}

struct Sink {
    logs: Vec<String>,
    ready: u32,
    failures: Vec<String>,
}

impl OlcRtcOutputSink for Sink {
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn ready(&mut self) {
        self.ready += 1;
    }
    fn startup_failure(&mut self, line: String) {
        self.failures.push(line);
    }
}

#[test]
fn pump_reads_crlf_lines_until_eof() {
    let input = Cursor::new(b"hello\r\n[hev] up\nSOCKS5 server listening\njoin room failed".to_vec());
    let mut sink = Sink { logs: vec![], ready: 0, failures: vec![] };
    pump_olcrtc_output(input, true, &|| true, &mut sink).unwrap();
    assert_eq!(
        sink.logs,
        vec!["rtc: hello", "tun: up", "rtc: SOCKS5 server listening", "rtc: join room failed"]
    );
    assert_eq!(sink.ready, 1);
    assert_eq!(sink.failures, vec!["join room failed"]);

    let mut idle = Sink { logs: vec![], ready: 0, failures: vec![] };
    pump_olcrtc_output(Cursor::new(b"a\nb\n".to_vec()), true, &|| false, &mut idle).unwrap();
    assert!(idle.logs.is_empty());
}

// ------------------------------------------------------------------ stop

struct StopHost {
    idle: bool,
    windows_fails: bool,
    restore_fails: bool,
    calls: Vec<String>,
    logs: Vec<String>,
    statuses: Vec<DesktopVpnStatus>,
}

fn stop_host() -> StopHost {
    StopHost { idle: false, windows_fails: false, restore_fails: false, calls: vec![], logs: vec![], statuses: vec![] }
}

impl DesktopStopHost for StopHost {
    fn is_idle(&self) -> bool {
        self.idle
    }
    fn set_status(&mut self, status: DesktopVpnStatus) {
        self.statuses.push(status);
    }
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn release_linux_tun(&mut self) {
        self.calls.push(String::from("release"));
    }
    fn stop_windows_tun(&mut self) -> Result<(), String> {
        self.calls.push(String::from("wintun"));
        if self.windows_fails {
            Err(String::from("busy"))
        } else {
            Ok(())
        }
    }
    fn restore_system_proxy(&mut self) -> Result<(), String> {
        self.calls.push(String::from("restore"));
        if self.restore_fails {
            Err(String::from("timeout"))
        } else {
            Ok(())
        }
    }
    fn stop_http_proxy_bridge(&mut self) {
        self.calls.push(String::from("bridge"));
    }
    fn stop_pac_server(&mut self) {
        self.calls.push(String::from("pac"));
    }
    fn stop_engines(&mut self) -> Result<(), String> {
        self.calls.push(String::from("engines"));
        Ok(())
    }
    fn unbind_outbound_interface(&mut self) {
        self.calls.push(String::from("unbind"));
    }
    fn stop_olcrtc_process(&mut self, privileged: bool) {
        self.calls.push(format!("olcrtc:{}", privileged));
    }
    fn delete_olcrtc_config(&mut self) {
        self.calls.push(String::from("config"));
    }
    fn cancel_jobs(&mut self) {
        self.calls.push(String::from("jobs"));
    }
}

fn busy_session(mode: Option<DesktopMode>) -> DesktopVpnSession {
    let mut s = DesktopVpnSession::default();
    s.active_mode = mode;
    s.session_socks = Some(socks(10808));
    s
}

#[test]
fn stop_is_a_noop_when_idle() {
    let mut h = stop_host();
    h.idle = true;
    let mut s = busy_session(Some(DesktopMode::LinuxTun));
    stop_desktop_mode(&mut h, &mut s, DesktopOs::Linux, true);
    assert!(h.calls.is_empty() && h.statuses.is_empty());
    assert!(s.session_socks.is_some());
}

#[test]
fn stop_linux_tun_full_sequence() {
    let mut h = stop_host();
    let mut s = busy_session(Some(DesktopMode::LinuxTun));
    stop_desktop_mode(&mut h, &mut s, DesktopOs::Linux, true);
    assert_eq!(h.calls, vec!["release", "pac", "engines", "unbind", "olcrtc:true", "config", "jobs"]);
    assert_eq!(h.statuses, vec![DesktopVpnStatus::Stopping, DesktopVpnStatus::Disconnected]);
    assert_eq!(h.logs, vec!["Desktop Linux TUN stopped"]);
    assert!(s.session_socks.is_none() && s.engine_location.is_none());
}

#[test]
fn stop_windows_tun_logs_failure() {
    let mut h = stop_host();
    h.windows_fails = true;
    let mut s = busy_session(None);
    stop_desktop_mode(&mut h, &mut s, DesktopOs::Windows, true);
    assert_eq!(h.calls[0], "wintun");
    assert_eq!(h.logs, vec!["Windows TUN stop failed: busy", "Desktop Windows TUN stopped"]);
    assert!(h.calls.contains(&String::from("olcrtc:false")));
}

#[test]
fn stop_system_proxy_without_final_status() {
    let mut h = stop_host();
    h.restore_fails = true;
    let mut s = busy_session(Some(DesktopMode::SystemProxy));
    stop_desktop_mode(&mut h, &mut s, DesktopOs::Windows, false);
    assert_eq!(&h.calls[..3], &["restore", "bridge", "pac"]);
    assert_eq!(h.statuses, vec![DesktopVpnStatus::Stopping]);
    assert_eq!(h.logs, vec!["Proxy restore failed: timeout"]);
    assert_eq!(stopped_log(DesktopOs::MacOs), "Desktop proxy stopped");
    assert_eq!(stopped_log(DesktopOs::Other), "Desktop proxy stopped");
}

// ------------------------------------------------------------------ speed / status

#[test]
fn speed_samples_floor_resets() {
    let a = TrafficCounters { rx_bytes: 1000, tx_bytes: 500 };
    let b = TrafficCounters { rx_bytes: 5000, tx_bytes: 300 };
    assert_eq!(speed_sample(a, b, 2000), SpeedSample { down_bytes_per_sec: 2000, up_bytes_per_sec: 0 });

    let mut m = SpeedMeter::new();
    assert_eq!(m.tick(true, Some(a), 2000), None);
    assert_eq!(m.tick(true, Some(b), 2000), Some(SpeedSample { down_bytes_per_sec: 2000, up_bytes_per_sec: 0 }));
    assert_eq!(m.tick(true, None, 2000), None);
    assert_eq!(m.tick(true, Some(a), 2000), None);
    assert_eq!(m.tick(false, Some(b), 2000), Some(ZERO_SPEED));
    assert_eq!(m.tick(true, Some(b), 2000), None);
}

#[test]
fn status_effects_keep_uptime_stamp() {
    let e = status_effects(&DesktopVpnStatus::Connected, false, 0, 100);
    assert_eq!(e, StatusEffects { connected: true, start_speed_updater: true, stop_speed_updater: false, connected_since_ms: 100 });
    let e = status_effects(&DesktopVpnStatus::Connected, true, 50, 100);
    assert_eq!(e.connected_since_ms, 50);
    assert!(!e.start_speed_updater);
    let e = status_effects(&DesktopVpnStatus::Reconnecting, true, 50, 100);
    assert_eq!(e, StatusEffects { connected: false, start_speed_updater: false, stop_speed_updater: true, connected_since_ms: 0 });
}

#[test]
fn tunnel_and_engine_liveness() {
    let mut probed = Vec::new();
    assert!(tunnel_alive_with(&mut |h: &str| {
        probed.push(h.to_string());
        probed.len() == 2
    }));
    assert_eq!(probed, vec![TUNNEL_HEALTH_HOSTS[0], TUNNEL_HEALTH_HOSTS[1]]);
    assert!(!tunnel_alive_with(&mut |_h: &str| false));

    assert!(is_active_engine_alive(Some(EngineType::Standard), &|e: EngineType| e == EngineType::Standard, false));
    assert!(!is_active_engine_alive(Some(EngineType::Chain), &|e: EngineType| e == EngineType::Standard, true));
    assert!(is_active_engine_alive(None, &|_e: EngineType| false, true));
}

// ------------------------------------------------------------------ watchdog

struct Probe {
    connected: bool,
    alive: bool,
    nets: Vec<&'static str>,
    tunnel: Vec<bool>,
    probes: u32,
    generation: u64,
    sleeps: Vec<u64>,
    max_sleeps: usize,
    logs: Vec<String>,
    restarts: u32,
}

fn probe(nets: Vec<&'static str>, tunnel: Vec<bool>) -> Probe {
    Probe { connected: true, alive: true, nets, tunnel, probes: 0, generation: 1, sleeps: vec![], max_sleeps: 100, logs: vec![], restarts: 0 }
}

impl WatchdogProbe for Probe {
    fn is_connected(&self) -> bool {
        self.connected
    }
    fn engine_alive(&mut self) -> bool {
        self.alive
    }
    fn network_fingerprint(&mut self) -> String {
        if self.nets.len() > 1 {
            self.nets.remove(0).to_string()
        } else {
            self.nets[0].to_string()
        }
    }
    fn tunnel_alive(&mut self) -> bool {
        self.probes += 1;
        if self.tunnel.len() > 1 {
            self.tunnel.remove(0)
        } else {
            self.tunnel[0]
        }
    }
}

impl WatchdogHost for Probe {
    fn generation(&self) -> u64 {
        self.generation
    }
    fn sleep_ms(&mut self, ms: u64) -> bool {
        self.sleeps.push(ms);
        self.sleeps.len() <= self.max_sleeps
    }
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn begin_auto_start(&mut self) {
        self.restarts += 1;
        self.generation += 1;
    }
}

#[test]
fn watchdog_skips_when_not_connected_and_reconnects_dead_engine() {
    let mut p = probe(vec!["A"], vec![true]);
    let mut w = WatchdogState::new(String::from("A"));
    p.connected = false;
    assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    p.connected = true;
    p.alive = false;
    assert_eq!(w.poll(&mut p), WatchdogAction::Reconnect(WATCHDOG_DEAD_LOG));
}

#[test]
fn watchdog_needs_two_identical_new_fingerprints() {
    let mut p = probe(vec!["B", "C", "C"], vec![true]);
    let mut w = WatchdogState::new(String::from("A"));
    assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    assert_eq!(w.poll(&mut p), WatchdogAction::Reconnect(WATCHDOG_NETWORK_LOG));
}

#[test]
fn watchdog_offline_then_same_network_still_reconnects() {
    let mut p = probe(vec!["", "A", "A"], vec![true]);
    let mut w = WatchdogState::new(String::from("A"));
    assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    assert_eq!(p.probes, 0);
    assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    assert_eq!(w.poll(&mut p), WatchdogAction::Reconnect(WATCHDOG_NETWORK_LOG));
}

#[test]
fn watchdog_probes_every_other_tick_and_gives_up_after_limit() {
    let mut p = probe(vec!["A"], vec![false]);
    let mut w = WatchdogState::new(String::from("A"));
    for _ in 0..5 {
        assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    }
    assert_eq!(p.probes, 2);
    assert_eq!(w.poll(&mut p), WatchdogAction::Reconnect(WATCHDOG_NO_TRAFFIC_LOG));
    assert_eq!(p.probes, 3);

    let mut p = probe(vec!["A"], vec![false, false, true, false]);
    let mut w = WatchdogState::new(String::from("A"));
    for _ in 0..8 {
        assert_eq!(w.poll(&mut p), WatchdogAction::Continue);
    }
}

#[test]
fn run_watchdog_waits_grace_then_reconnects_once() {
    let mut p = probe(vec!["A"], vec![true]);
    p.alive = false;
    run_watchdog(&mut p, 1);
    assert_eq!(p.sleeps, vec![8_000, 5_000]);
    assert_eq!(p.logs, vec![WATCHDOG_DEAD_LOG]);
    assert_eq!(p.restarts, 1);
}

#[test]
fn run_watchdog_stops_on_cancel_or_new_generation() {
    let mut p = probe(vec!["A"], vec![true]);
    p.max_sleeps = 3;
    run_watchdog(&mut p, 1);
    assert_eq!(p.sleeps.len(), 4);
    assert_eq!(p.restarts, 0);

    let mut p = probe(vec!["A"], vec![true]);
    p.alive = false;
    run_watchdog(&mut p, 0);
    assert_eq!(p.restarts, 0);
    assert!(p.logs.is_empty());
}

// ------------------------------------------------------------------ journal

#[test]
fn journal_trims_bounds_and_coalesces_flushes() {
    let j = LogJournal::with_max_entries(3);
    assert_eq!(j.add("one\n"), (String::from("one"), true));
    assert_eq!(j.add("two  "), (String::from("two"), false));
    j.add("three");
    j.add("four");
    assert_eq!(j.flush(), vec!["two", "three", "four"]);
    assert_eq!(j.add("five"), (String::from("five"), true));
    assert_eq!(j.snapshot(), vec!["three", "four", "five"]);
    j.clear();
    assert!(j.snapshot().is_empty());
    assert_eq!(LogJournal::new().snapshot().len(), 0);
}
