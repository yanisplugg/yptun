use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_vpn_probe::*;
use yptun_core::desktop_vpn_start::{DesktopVpnSession, DesktopVpnStatus};
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, ProxyProfile, TYPE_AMNEZIAWG, TYPE_HYSTERIA2, TYPE_TRUSTTUNNEL};
use yptun_core::settings::{PING_AUTO, PING_ICMP, PING_PROXY_GET, PING_PROXY_HEAD, PING_TCP};
use yptun_core::transports::{OpenFluxCarrier, OpenFluxConfig, OF_DIRECT, OF_MAILRU};

fn proxy_location(kind: &str, server: &str, port: i32) -> LocationConfig {
    let mut l = LocationConfig::default();
    l.engine = EngineType::Standard;
    let mut p = ProxyProfile::default();
    p.kind = kind.to_string();
    p.server = server.to_string();
    p.server_port = port;
    l.proxy = Some(p);
    l
}

// ---- public API ------------------------------------------------------------------------

#[test]
fn restart_needed_when_running_or_processes_alive() {
    assert!(should_restart(&DesktopVpnStatus::Connected, false, false));
    assert!(should_restart(&DesktopVpnStatus::Connecting, false, false));
    assert!(should_restart(&DesktopVpnStatus::Reconnecting, false, false));
    assert!(should_restart(&DesktopVpnStatus::Disconnected, true, false));
    assert!(should_restart(&DesktopVpnStatus::Disconnected, false, true));
    assert!(!should_restart(&DesktopVpnStatus::Disconnected, false, false));
    assert!(!should_restart(&DesktopVpnStatus::Error(String::from("x")), false, false));
}

#[test]
fn begin_and_stop_requests_bump_generation() {
    let mut s = DesktopVpnSession::default();
    s.auto_reconnect_attempt = 3;
    let mut g = 5u64;
    assert_eq!(begin_start_request(&mut s, &mut g, true), 6);
    assert!(s.auto_reconnect);
    assert_eq!(s.auto_reconnect_attempt, 3);
    assert_eq!(begin_start_request(&mut s, &mut g, false), 7);
    assert_eq!(s.auto_reconnect_attempt, 0);
    s.auto_reconnect = true;
    let st = stop_vpn_request(&mut s, &mut g, &DesktopVpnStatus::Connected);
    assert!(matches!(st, Some(DesktopVpnStatus::Stopping)));
    assert!(!s.auto_reconnect);
    assert_eq!(g, 8);
    assert!(stop_vpn_request(&mut s, &mut g, &DesktopVpnStatus::Disconnected).is_none());
    assert_eq!(g, 9);
}

struct FakeStart {
    generation: u64,
    status: DesktopVpnStatus,
    process: bool,
    bump_on_stop: bool,
    events: Vec<String>,
}

impl BeginStartHost for FakeStart {
    fn generation(&self) -> u64 {
        self.generation
    }
    fn status(&self) -> DesktopVpnStatus {
        self.status.clone()
    }
    fn has_process(&self) -> bool {
        self.process
    }
    fn has_tun_process(&self) -> bool {
        false
    }
    fn set_status(&mut self, status: DesktopVpnStatus) {
        self.events.push(format!("status {:?}", status));
    }
    fn log(&mut self, message: String) {
        self.events.push(message);
    }
    fn stop_desktop_mode(&mut self, final_stop: bool) {
        self.events.push(format!("stop {}", final_stop));
        if self.bump_on_stop {
            self.generation += 1;
        }
    }
    fn start_desktop_mode(&mut self, request_generation: u64, is_restart: bool) {
        self.events.push(format!("start {} {}", request_generation, is_restart));
    }
}

fn fake_start(status: DesktopVpnStatus, process: bool, bump: bool) -> FakeStart {
    FakeStart { generation: 4, status, process, bump_on_stop: bump, events: Vec::new() }
}

#[test]
fn begin_start_plain_start() {
    let mut h = fake_start(DesktopVpnStatus::Disconnected, false, false);
    run_begin_start(&mut h, 4);
    assert_eq!(h.events, vec![String::from("start 4 false")]);
}

#[test]
fn begin_start_restarts_running_session() {
    let mut h = fake_start(DesktopVpnStatus::Connected, false, false);
    run_begin_start(&mut h, 4);
    assert_eq!(h.events[0], "status Reconnecting");
    assert_eq!(h.events[1], RESTART_LOG);
    assert_eq!(h.events[2], "stop false");
    assert_eq!(h.events[3], "start 4 true");
}

#[test]
fn begin_start_superseded() {
    let mut h = fake_start(DesktopVpnStatus::Disconnected, true, true);
    run_begin_start(&mut h, 4);
    assert_eq!(h.events.last().unwrap(), "stop false");
    let mut h = fake_start(DesktopVpnStatus::Disconnected, false, false);
    run_begin_start(&mut h, 3);
    assert!(h.events.is_empty());
}

#[test]
fn fetch_proxy_only_while_up() {
    let mut s = DesktopSocksProxySettings::default();
    s.host = String::from("127.0.0.1");
    s.port = 10808;
    s.username = String::from("u");
    s.password = String::from("p");
    let p = subscription_fetch_proxy(&DesktopVpnStatus::Connected, &s).unwrap();
    assert_eq!(p, SubscriptionFetchProxy { host: s.host.clone(), port: 10808, username: s.username.clone(), password: s.password.clone() });
    assert!(subscription_fetch_proxy(&DesktopVpnStatus::Reconnecting, &s).is_some());
    assert!(subscription_fetch_proxy(&DesktopVpnStatus::Connecting, &s).is_none());
    assert!(subscription_fetch_proxy(&DesktopVpnStatus::Disconnected, &s).is_none());
}

#[test]
fn live_socks_prefers_session() {
    let mut session = DesktopSocksProxySettings::default();
    session.port = 12345;
    let configured = DesktopSocksProxySettings::default();
    assert_eq!(live_socks(Some(&session), &configured, DesktopOs::Linux).port, 12345);
    assert_eq!(live_socks(None, &configured, DesktopOs::Linux).port, configured.normalized(DesktopOs::Linux).port);
}

#[test]
fn conflict_prompt_answers_once() {
    let (p, rx) = VpnConflictPrompt::new(vec![String::from("OtherVPN")]);
    assert_eq!(p.names, vec![String::from("OtherVPN")]);
    p.close();
    p.ignore();
    assert_eq!(rx.recv().unwrap(), true);
    assert!(rx.try_recv().is_err());
    let (p, rx) = VpnConflictPrompt::new(Vec::new());
    p.ignore();
    assert_eq!(rx.recv().unwrap(), false);
}

// ---- pings -------------------------------------------------------------------------------

#[test]
fn url_confirmation_modes() {
    assert!(ping_needs_url_confirmation(PING_AUTO));
    assert!(ping_needs_url_confirmation(PING_TCP));
    assert!(ping_needs_url_confirmation(PING_ICMP));
    assert!(!ping_needs_url_confirmation(PING_PROXY_GET));
    assert!(!ping_needs_url_confirmation(PING_PROXY_HEAD));
}

#[test]
fn rounds_stop_on_first_failure() {
    let mut v = vec![Some(30u64), Some(10), None, Some(1)].into_iter();
    assert_eq!(best_of_rounds(4, || v.next().unwrap()), Some(10));
    let mut v = vec![None, Some(1u64)].into_iter();
    assert_eq!(best_of_rounds(2, || v.next().unwrap()), None);
    assert_eq!(best_of_rounds(0, || Some(1)), None);
}

#[test]
fn attempts_skip_failures() {
    let mut v = vec![None, Some(20u64), Some(15)].into_iter();
    assert_eq!(best_of_attempts(3, || v.next().unwrap()), Some(15));
    assert_eq!(best_of_attempts(2, || None), None);
}

#[test]
fn ping_method_explicit_modes() {
    let l = proxy_location("vless", "example.com", 443);
    assert_eq!(
        ping_method(&l, PING_TCP, None, false, false),
        PingMethod::Tcp { host: Some(String::from("example.com")), port: Some(443) }
    );
    assert_eq!(ping_method(&l, PING_ICMP, None, false, false), PingMethod::Icmp(Some(String::from("example.com"))));
    assert_eq!(ping_method(&l, PING_PROXY_GET, None, false, false), PingMethod::ProxyUrl("GET"));
    assert_eq!(ping_method(&l, PING_PROXY_HEAD, None, false, false), PingMethod::ProxyUrl("HEAD"));
}

#[test]
fn ping_method_auto() {
    let l = proxy_location("vless", "example.com", 443);
    assert_eq!(
        ping_method(&l, PING_AUTO, None, true, true),
        PingMethod::Tcp { host: Some(String::from("example.com")), port: Some(443) }
    );
    let mut awg = proxy_location(TYPE_AMNEZIAWG, "a", 1);
    awg.proxy.as_mut().unwrap().awg_config = String::from("cfg");
    assert_eq!(ping_method(&awg, PING_AUTO, None, false, false), PingMethod::AwgProbe(String::from("cfg")));
    assert_eq!(ping_method(&awg, PING_AUTO, None, true, true), PingMethod::Tunnel);
    let hy = proxy_location(TYPE_HYSTERIA2, "h.example", 443);
    assert_eq!(ping_method(&hy, PING_AUTO, None, true, false), PingMethod::Icmp(Some(String::from("h.example"))));
    assert_eq!(ping_method(&hy, PING_AUTO, None, true, true), PingMethod::Tunnel);
    let tt = proxy_location(TYPE_TRUSTTUNNEL, "", 0);
    assert_eq!(
        ping_method(&tt, PING_AUTO, Some((String::from("tt.example"), 8443)), false, true),
        PingMethod::Tcp { host: Some(String::from("tt.example")), port: Some(8443) }
    );
    let mut vk = proxy_location("vless", "x", 1);
    vk.engine = EngineType::VkTurn;
    assert_eq!(ping_method(&vk, PING_AUTO, None, false, false), PingMethod::Tunnel);
    let mut olc = LocationConfig::default();
    olc.engine = EngineType::Stealth;
    olc.proxy = None;
    assert_eq!(ping_method(&olc, PING_TCP, None, false, false), PingMethod::OlcRtc);
}

#[test]
fn tcp_ping_validates_and_measures() {
    assert_eq!(tcp_ping(None, Some(80), 500), None);
    assert_eq!(tcp_ping(Some(" "), Some(80), 500), None);
    assert_eq!(tcp_ping(Some("127.0.0.1"), None, 500), None);
    assert_eq!(tcp_ping(Some("127.0.0.1"), Some(0), 500), None);
    assert_eq!(tcp_ping(Some("127.0.0.1"), Some(70000), 500), None);
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port() as i32;
    assert!(tcp_ping(Some("127.0.0.1"), Some(port), 2000).is_some());
}

#[test]
fn system_ping_commands() {
    assert_eq!(system_ping_command(DesktopOs::Windows, "h", 3000), vec!["ping", "-n", "1", "-w", "3000", "h"]);
    assert_eq!(system_ping_command(DesktopOs::Linux, "h", 3000), vec!["ping", "-c", "1", "-W", "3", "h"]);
    assert_eq!(system_ping_command(DesktopOs::MacOs, "h", 500), vec!["ping", "-c", "1", "-W", "1", "h"]);
}

#[test]
fn system_ping_output_parse() {
    // Same as the Kotlin regex: a fractional Linux RTT does not match.
    assert_eq!(parse_system_ping_ms("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms"), None);
    assert_eq!(parse_system_ping_ms("64 bytes: icmp_seq=1 ttl=57 time=12 ms"), Some(12));
    assert_eq!(parse_system_ping_ms("Reply from 1.1.1.1: bytes=32 time=23ms TTL=57"), Some(23));
    assert_eq!(parse_system_ping_ms("Reply from 1.1.1.1: bytes=32 time<1ms TTL=57"), Some(1));
    assert_eq!(parse_system_ping_ms("TIME = 7 MS"), Some(7));
    assert_eq!(parse_system_ping_ms("\u{432}\u{440}\u{435}\u{43c}\u{44f}=15\u{43c}\u{441}"), Some(15));
    assert_eq!(parse_system_ping_ms("Request timed out."), None);
}

#[test]
fn awg_probe() {
    assert_eq!(awg_probe_ping("  ", |_| Ok(5)), None);
    assert_eq!(awg_probe_ping("cfg", |_| Ok(42)), Some(42));
    assert_eq!(awg_probe_ping("cfg", |_| Ok(-1)), None);
    assert_eq!(awg_probe_ping("cfg", |_| Err(String::from("boom"))), None);
}

#[test]
fn socks5_frames() {
    assert_eq!(socks5_greeting(false), vec![5, 1, 0]);
    assert_eq!(socks5_greeting(true), vec![5, 2, 0, 2]);
    assert_eq!(socks5_auth_request("ab", "c"), vec![1, 2, b'a', b'b', 1, b'c']);
    assert_eq!(socks5_connect_ipv4_request("1.1.1.1".parse().unwrap(), 443), vec![5, 1, 0, 1, 1, 1, 1, 1, 1, 187]);
}

fn fake_socks(auth: bool, reply: u8, atyp: u8) -> u16 {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    thread::spawn(move || {
        let (mut s, _) = l.accept().unwrap();
        let mut g = [0u8; 2];
        s.read_exact(&mut g).unwrap();
        let mut m = vec![0u8; g[1] as usize];
        s.read_exact(&mut m).unwrap();
        if auth {
            s.write_all(&[5, 2]).unwrap();
            let mut h = [0u8; 2];
            s.read_exact(&mut h).unwrap();
            let mut u = vec![0u8; h[1] as usize];
            s.read_exact(&mut u).unwrap();
            let mut pl = [0u8; 1];
            s.read_exact(&mut pl).unwrap();
            let mut p = vec![0u8; pl[0] as usize];
            s.read_exact(&mut p).unwrap();
            let ok = u == b"user" && p == b"pass";
            s.write_all(&[1, if ok { 0 } else { 1 }]).unwrap();
            if !ok {
                return;
            }
        } else {
            s.write_all(&[5, 0]).unwrap();
        }
        let mut req = [0u8; 10];
        s.read_exact(&mut req).unwrap();
        match atyp {
            3 => s.write_all(&[5, reply, 0, 3, 3, b'a', b'b', b'c', 0, 80]).unwrap(),
            _ => s.write_all(&[5, reply, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap(),
        }
    });
    port
}

#[test]
fn socks5_rtt_no_auth() {
    let port = fake_socks(false, 0, 1);
    assert!(socks5_connect_rtt("127.0.0.1", port, "", "", "1.1.1.1", 443, 3000).is_some());
}

#[test]
fn socks5_rtt_auth_and_domain_reply() {
    let port = fake_socks(true, 0, 3);
    assert!(socks5_connect_rtt("127.0.0.1", port, "user", "pass", "1.1.1.1", 443, 3000).is_some());
}

#[test]
fn socks5_rtt_failures() {
    let port = fake_socks(true, 0, 1);
    assert!(socks5_connect_rtt("127.0.0.1", port, "user", "bad", "1.1.1.1", 443, 3000).is_none());
    let port = fake_socks(false, 5, 1);
    assert!(socks5_connect_rtt("127.0.0.1", port, "", "", "1.1.1.1", 443, 3000).is_none());
    assert!(socks5_connect_rtt("127.0.0.1", 1, "", "", "not-an-ip", 443, 300).is_none());
}

#[test]
fn tunnel_ping_requires_connection() {
    let mut s = DesktopSocksProxySettings::default();
    s.port = fake_socks(false, 0, 1) as i32;
    s.host = String::from("127.0.0.1");
    assert_eq!(tunnel_ping(false, &s, 1000), None);
}

#[test]
fn test_port_range() {
    for _ in 0..50 {
        let p = random_test_port();
        assert!((20_000..=60_000).contains(&p));
    }
    assert_eq!(trust_tunnel_ping_dir(25000), "trusttunnel-ping-25000");
}

// ---- URL tests --------------------------------------------------------------------------

struct FakeUrl {
    logs: Vec<String>,
    raw: Option<String>,
    build_err: bool,
    delay: Result<i64, String>,
    built_for: Vec<i32>,
}

impl Default for FakeUrl {
    fn default() -> Self {
        FakeUrl { logs: Vec::new(), raw: None, build_err: false, delay: Ok(0), built_for: Vec::new() }
    }
}

impl UrlTestHost for FakeUrl {
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn awg_measure_delay(&mut self, _c: &str, _u: &str, _m: &str, _t: u64) -> Result<i64, String> {
        Ok(33)
    }
    fn trust_tunnel_url_test(&mut self, _c: &str, _u: &str, _m: &str) -> Option<u64> {
        Some(77)
    }
    fn build_raw_proxy_ping_config(&mut self, _r: &str, _p: i32, _h: &str) -> Option<String> {
        self.raw.clone()
    }
    fn build_xray_config(&mut self, _p: &ProxyProfile, port: i32, _h: &str) -> Result<String, String> {
        self.built_for.push(port);
        if self.build_err {
            Err(String::from("bad"))
        } else {
            Ok(String::from("json"))
        }
    }
    fn xray_measure_delay(&mut self, _j: &str, _u: &str, _m: &str, _t: u64) -> Result<i64, String> {
        self.delay.clone()
    }
}

#[test]
fn url_test_dispatch() {
    let mut h = FakeUrl { delay: Ok(120), ..Default::default() };
    let none = LocationConfig { proxy: None, ..LocationConfig::default() };
    assert_eq!(proxy_url_test(&mut h, &none, "u", "HEAD", 1000, 30000), None);
    assert_eq!(h.logs[0], "Proxy HEAD ping: location has no proxy");

    let mut awg = proxy_location(TYPE_AMNEZIAWG, "a", 1);
    assert_eq!(proxy_url_test(&mut h, &awg, "u", "GET", 1000, 30000), None);
    awg.proxy.as_mut().unwrap().awg_config = String::from("cfg");
    assert_eq!(proxy_url_test(&mut h, &awg, "u", "GET", 1000, 30000), Some(33));

    let tt = proxy_location(TYPE_TRUSTTUNNEL, "", 0);
    assert_eq!(proxy_url_test(&mut h, &tt, "u", "GET", 1000, 30000), Some(77));

    let hy = proxy_location(TYPE_HYSTERIA2, "h", 1);
    assert_eq!(proxy_url_test(&mut h, &hy, "u", "GET", 1000, 30000), None);
    assert_eq!(h.logs.last().unwrap(), "Proxy GET ping: Hysteria2 not supported (use Auto/ICMP)");

    let blank = proxy_location("vless", " ", 1);
    assert_eq!(proxy_url_test(&mut h, &blank, "u", "GET", 1000, 30000), None);

    let v = proxy_location("vless", "srv", 443);
    assert_eq!(proxy_url_test(&mut h, &v, "u", "GET", 1000, 30001), Some(120));
    assert_eq!(h.built_for, vec![30001]);
}

#[test]
fn url_test_errors_are_logged() {
    let v = proxy_location("vless", "srv", 443);
    let mut h = FakeUrl { build_err: true, delay: Ok(1), ..Default::default() };
    assert_eq!(proxy_url_test(&mut h, &v, "u", "GET", 1000, 30000), None);
    assert_eq!(h.logs.last().unwrap(), "Proxy GET ping: failed to build test config: bad");

    let mut h = FakeUrl { delay: Err(String::from("timeout")), ..Default::default() };
    assert_eq!(proxy_url_test(&mut h, &v, "u", "HEAD", 1000, 30000), None);
    assert_eq!(h.logs.last().unwrap(), "Proxy HEAD ping error for srv: timeout");

    let mut raw = proxy_location("vless", "srv", 443);
    raw.proxy.as_mut().unwrap().raw_xray_config = Some(String::from("{ }"));
    let mut h = FakeUrl { raw: None, delay: Ok(1), ..Default::default() };
    assert_eq!(proxy_url_test(&mut h, &raw, "u", "GET", 1000, 30000), None);
    assert_eq!(h.logs.last().unwrap(), "Proxy GET ping: no proxy outbound in verbatim config for srv");
    let mut h = FakeUrl { raw: Some(String::from("j")), delay: Ok(9), ..Default::default() };
    assert_eq!(proxy_url_test(&mut h, &raw, "u", "GET", 1000, 30000), Some(9));
    assert!(h.built_for.is_empty());
}

struct FakeTt {
    now: u64,
    running: bool,
    open_after: u64,
    delay: Result<i64, String>,
    start_err: bool,
    logs: Vec<String>,
    stopped: bool,
    deleted: Vec<String>,
}

impl TrustTunnelTestHost for FakeTt {
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn now_ms(&self) -> u64 {
        self.now
    }
    fn sleep_ms(&mut self, ms: u64) {
        self.now += ms;
    }
    fn start_client(&mut self, _c: &str, _p: i32, _d: &str) -> Result<(), String> {
        if self.start_err {
            Err(String::from("no binary"))
        } else {
            Ok(())
        }
    }
    fn client_running(&mut self) -> bool {
        self.running
    }
    fn local_port_open(&mut self, _p: i32) -> bool {
        self.now >= self.open_after
    }
    fn build_socks_xray_config(&mut self, _s: i32, _l: i32) -> Result<String, String> {
        Ok(String::from("json"))
    }
    fn xray_measure_delay(&mut self, _j: &str, _u: &str, _m: &str, _t: u64) -> Result<i64, String> {
        self.delay.clone()
    }
    fn stop_client(&mut self) {
        self.stopped = true;
    }
    fn delete_work_dir(&mut self, d: &str) {
        self.deleted.push(d.to_string());
    }
}

fn fake_tt(running: bool, delay: Result<i64, String>) -> FakeTt {
    FakeTt { now: 0, running, open_after: 300, delay, start_err: false, logs: Vec::new(), stopped: false, deleted: Vec::new() }
}

#[test]
fn trust_tunnel_url_test_paths() {
    let mut h = fake_tt(true, Ok(55));
    assert_eq!(run_trust_tunnel_url_test(&mut h, "cfg", "u", "GET", 1000, 25000, 26000), Some(55));
    assert!(h.now >= 300);
    assert!(h.stopped);
    assert_eq!(h.deleted, vec![String::from("trusttunnel-ping-25000")]);

    let mut h = fake_tt(false, Ok(55));
    assert_eq!(run_trust_tunnel_url_test(&mut h, "cfg", "u", "GET", 1000, 25000, 26000), None);
    assert_eq!(h.logs[0], "Proxy GET ping: Trust Tunnel client exited");
    assert!(h.stopped);

    let mut h = fake_tt(true, Err(String::from("x")));
    assert_eq!(run_trust_tunnel_url_test(&mut h, "cfg", "http://t", "HEAD", 1000, 25000, 26000), None);
    assert_eq!(h.logs[0], "Proxy HEAD ping (Trust Tunnel) error: x");
    assert_eq!(h.logs[1], "Proxy HEAD ping: no response via Trust Tunnel for http://t");

    let mut h = fake_tt(true, Ok(1));
    h.start_err = true;
    assert_eq!(run_trust_tunnel_url_test(&mut h, "cfg", "u", "GET", 1000, 25000, 26000), None);
    assert_eq!(h.logs[0], "Proxy GET ping (Trust Tunnel) failed: no binary");
    assert!(h.stopped);
    assert_eq!(h.deleted.len(), 1);
}

// ---- bypass -----------------------------------------------------------------------------

#[test]
fn uri_hosts() {
    assert_eq!(uri_host("https://vk.com/call/join/abc"), Some(String::from("vk.com")));
    assert_eq!(uri_host("turn://user:pw@relay.example:3478?x=1"), Some(String::from("relay.example")));
    assert_eq!(uri_host("http://[::1]:80/"), Some(String::from("[::1]")));
    assert_eq!(uri_host("no scheme"), None);
    assert_eq!(uri_host("https:///path"), None);
}

#[test]
fn awg_endpoint_hosts() {
    let cfg = "[Interface]\nPrivateKey = x\n[Peer]\n  endpoint = 203.0.113.5:51820\n";
    assert_eq!(awg_endpoint_host(cfg), Some(String::from("203.0.113.5")));
    assert_eq!(awg_endpoint_host("Endpoint = [2001:db8::1]:51820"), Some(String::from("2001:db8::1")));
    assert_eq!(awg_endpoint_host("Endpoint"), None);
    assert_eq!(awg_endpoint_host(""), None);
}

#[test]
fn bypass_hosts_for_proxy() {
    let mut l = proxy_location("vless", "srv.example", 443);
    l.proxy.as_mut().unwrap().awg_config = String::from("Endpoint = awg.example:1");
    let hosts = bypass_hosts(&l, &BypassInputs::default());
    assert_eq!(hosts, vec![String::from("srv.example"), String::from("awg.example")]);

    let local = proxy_location("vless", "127.0.0.1", 1);
    assert!(bypass_hosts(&local, &BypassInputs::default()).is_empty());

    let tt = proxy_location(TYPE_TRUSTTUNNEL, "ignored", 1);
    let inputs = BypassInputs {
        trust_tunnel_endpoint_hosts: vec![String::from("tt1"), String::from("tt2")],
        telegram_proxy_enabled: true,
        telegram_candidate_hosts: vec![String::from("tg")],
        ..Default::default()
    };
    assert_eq!(bypass_hosts(&tt, &inputs), vec![String::from("tt1"), String::from("tt2"), String::from("tg")]);
}

#[test]
fn bypass_hosts_for_open_flux() {
    let mut l = LocationConfig::default();
    l.proxy = None;
    let mut of = OpenFluxConfig::default();
    let mut direct = OpenFluxCarrier::default();
    direct.kind = OF_DIRECT.to_string();
    direct.dial = String::from("[198.51.100.7]:9000");
    let mut mail = OpenFluxCarrier::default();
    mail.kind = OF_MAILRU.to_string();
    mail.url = String::from("https://cloud.mail.ru/public/x");
    of.carriers = vec![direct, mail];
    l.open_flux = Some(of);
    let hosts = bypass_hosts(&l, &BypassInputs::default());
    assert!(hosts.contains(&String::from("198.51.100.7")));
    assert!(hosts.contains(&String::from("cloud.mail.ru")));
    assert!(hosts.contains(&String::from("docs.datacloudmail.ru")));
}

#[test]
fn prefixes_and_final_list() {
    let per = vec![
        vec![String::from("1.0.0.0/24"), String::from("2a00::/32")],
        vec![String::from("1.0.0.0/24"), String::from("2.0.0.0/16"), String::from("3.0.0.0/16")],
    ];
    assert_eq!(ipv4_prefixes(&per, 2), vec![String::from("1.0.0.0/24"), String::from("2.0.0.0/16")]);
    assert!(vk_turn_prefix_log(0).contains("could not resolve"));
    assert_eq!(vk_turn_prefix_log(3), "VK-TURN: routing 3 VK/OK prefix(es) around the TUN so the relay keeps its own path");
    assert_eq!(open_flux_prefix_log(2, true), "OpenFlux: routing 2 VK/MAX prefix(es) around the TUN");
    assert_eq!(open_flux_prefix_log(2, false), "OpenFlux: routing 2 Yandex prefix(es) around the TUN");
    let out = finalize_bypass(
        vec![String::from("9.9.9.9"), String::from("127.0.0.1"), String::from("9.9.9.9")],
        vec![String::from("1.0.0.0/24")],
        Vec::new(),
        vec![String::from("10.0.0.0/8")],
    );
    assert_eq!(out, vec![String::from("9.9.9.9"), String::from("1.0.0.0/24"), String::from("10.0.0.0/8")]);
}

#[test]
fn resolve_bypass_with_closures() {
    let l = proxy_location("vless", "srv.example", 443);
    let inputs = BypassInputs { bypass_lan: true, ..Default::default() };
    let mut logs: Vec<String> = Vec::new();
    let out = resolve_bypass_server_ips(
        &l,
        &inputs,
        &mut |_asns: &[&str]| Vec::new(),
        &mut |h: &str| if h == "srv.example" { Ok(vec![String::from("203.0.113.9")]) } else { Err(String::from("nx")) },
        &mut |m: String| logs.push(m),
    );
    let mut want = vec![String::from("203.0.113.9")];
    want.extend(LAN_BYPASS_PREFIXES.iter().map(|s| s.to_string()));
    assert_eq!(out, want);
    assert!(logs.is_empty());

    let bad = proxy_location("vless", "nx.example", 443);
    let mut logs: Vec<String> = Vec::new();
    let out = resolve_bypass_server_ips(
        &bad,
        &BypassInputs::default(),
        &mut |_asns: &[&str]| Vec::new(),
        &mut |_h: &str| Err(String::from("nx")),
        &mut |m: String| logs.push(m),
    );
    assert!(out.is_empty());
    assert_eq!(logs, vec![String::from("Could not resolve nx.example for TUN bypass route: nx")]);
}

#[test]
fn resolve_ipv4_localhost() {
    let ips = resolve_ipv4("127.0.0.1").unwrap();
    assert_eq!(ips, vec![String::from("127.0.0.1")]);
}
