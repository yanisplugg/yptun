use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;
use std::time::Duration;

use yptun_core::telegram_proxy::*;

const BASE: &str = "[Interface]\nPrivateKey = k\nAddress = 172.16.0.2/32\n\n[Peer]\nPublicKey = p\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:2408\nPersistentKeepalive = 25\n";

fn creds() -> Credentials {
    Credentials { user: "user1234".to_string(), pass: "pass5678pass5678".to_string() }
}

#[derive(Default)]
struct Fake {
    running: bool,
    degraded: bool,
    start_fail: Vec<String>,
    working: Vec<String>,
    current: Option<String>,
    logs: Vec<String>,
    saved: Vec<String>,
    states: Vec<TelegramProxyState>,
    started: Vec<String>,
    stops: usize,
    checks: usize,
}

impl Fake {
    fn has(&self, line: &str) -> bool {
        self.logs.iter().any(|l| l == line)
    }
}

impl TelegramTunnel for Fake {
    fn log(&mut self, line: &str) {
        self.logs.push(line.to_string());
    }
    fn start(&mut self, config: &str, listen: &str, _creds: &Credentials) -> Result<(), String> {
        assert_eq!(listen, "127.0.0.1:12080");
        assert!(config.contains("PersistentKeepalive = 10"));
        let ep = extract_endpoint(config).unwrap();
        self.started.push(ep.clone());
        if self.start_fail.contains(&ep) {
            return Err("boom".to_string());
        }
        self.current = Some(ep);
        self.running = true;
        Ok(())
    }
    fn stop(&mut self) {
        self.stops += 1;
        self.current = None;
        self.running = false;
    }
    fn running(&mut self) -> bool {
        self.running
    }
    fn save_config(&mut self, ini: &str) {
        self.saved.push(ini.to_string());
    }
    fn set_state(&mut self, state: TelegramProxyState) {
        self.states.push(state);
    }
    fn take_degraded(&mut self) -> bool {
        std::mem::replace(&mut self.degraded, false)
    }
    fn clear_degraded(&mut self) {
        self.degraded = false;
    }
    fn wait_listener_free(&mut self) {}
    fn reachable(&mut self, _creds: &Credentials) -> bool {
        self.checks += 1;
        match &self.current {
            Some(ep) => self.working.contains(ep),
            None => false,
        }
    }
}

#[test]
fn ini_rewrites() {
    assert_eq!(
        rewrite_endpoint(BASE, "5.6.7.8:894"),
        BASE.replace("Endpoint = 1.2.3.4:2408", "Endpoint = 5.6.7.8:894")
    );
    assert_eq!(rewrite_endpoint("[Peer]\r\n  endpoint=9.9.9.9:1\r\n", "x:1"), "[Peer]\nEndpoint = x:1\n");
    assert_eq!(rewrite_endpoint("[Peer]\nPublicKey = p", "x:1"), "[Peer]\nPublicKey = p\nEndpoint = x:1");

    assert_eq!(extract_endpoint(BASE).as_deref(), Some("1.2.3.4:2408"));
    assert_eq!(extract_endpoint("  ENDPOINT = h:1 ").as_deref(), Some("h:1"));
    assert_eq!(extract_endpoint("Endpoint =   "), None);
    assert_eq!(extract_endpoint("PublicKey = p"), None);

    assert_eq!(
        force_keepalive(BASE, 10),
        BASE.replace("PersistentKeepalive = 25", "PersistentKeepalive = 10")
    );
    assert_eq!(
        force_keepalive("[Interface]\n[Peer]\nPublicKey = p", 10),
        "[Interface]\n[Peer]\nPersistentKeepalive = 10\nPublicKey = p"
    );
    assert_eq!(force_keepalive("PublicKey = p", 7), "PublicKey = p\nPersistentKeepalive = 7");

    let cfg = endpoint_config(BASE, "5.6.7.8:894");
    assert!(cfg.contains("Endpoint = 5.6.7.8:894"));
    assert!(cfg.contains("PersistentKeepalive = 10"));
    assert!(!cfg.contains("PersistentKeepalive = 25"));
}

#[test]
fn sweep_order_and_hosts() {
    let all = endpoint_sweep(BASE, &[]);
    assert_eq!(all.len(), 13);
    assert_eq!(all[0], "1.2.3.4:2408");
    assert_eq!(all[1], "162.159.192.8:894");

    let own = rewrite_endpoint(BASE, "162.159.192.8:894");
    let dedup = endpoint_sweep(&own, &[]);
    assert_eq!(dedup.len(), 12);
    assert_eq!(dedup[0], "162.159.192.8:894");
    assert_eq!(dedup[1], "engage.cloudflareclient.com:2408");

    let avoided = endpoint_sweep(BASE, &["engage.cloudflareclient.com:2408".to_string()]);
    assert_eq!(avoided.len(), 12);
    assert!(!avoided.contains(&"engage.cloudflareclient.com:2408".to_string()));

    let hosts = candidate_endpoint_hosts();
    assert_eq!(hosts.len(), 12);
    assert_eq!(hosts[0], "162.159.192.8");
    assert_eq!(hosts[1], "engage.cloudflareclient.com");
}

#[test]
fn credentials() {
    let c = Credentials::generate().unwrap();
    assert_eq!(c.user.len(), USER_LEN);
    assert_eq!(c.pass.len(), PASS_LEN);
    assert!(c.user.chars().chain(c.pass.chars()).all(|ch| ALPHABET.contains(ch)));
    assert_eq!(Credentials::from_json(&c.to_json()), Some(c.clone()));

    assert_eq!(Credentials::from_json(r#"{"user":"u","pass":" "}"#), None);
    assert_eq!(
        Credentials::from_json(r#"{"user":"u","pass":"p","extra":1}"#),
        Some(Credentials { user: "u".to_string(), pass: "p".to_string() })
    );
    assert_eq!(Credentials::from_parts(Some("u"), None), None);
    assert_eq!(Credentials::from_parts(Some("u"), Some("p")).map(|c| c.pass), Some("p".to_string()));

    let dir = std::env::temp_dir().join(format!("yptun-tg-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    assert_eq!(load_config(&dir), None);
    assert_eq!(peek_credentials(&dir), None);
    save_config(&dir, BASE);
    assert_eq!(load_config(&dir).as_deref(), Some(BASE));
    let first = get_or_create_credentials(&dir).unwrap();
    let second = get_or_create_credentials(&dir).unwrap();
    assert_eq!(first, second);
    assert_eq!(peek_credentials(&dir), Some(first));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn log_filter() {
    let mut f = CoreLogFilter::new();
    assert_eq!(f.on_core_log("other: line"), None);
    assert_eq!(f.on_core_log("tgwarp:   "), None);
    assert_eq!(f.on_core_log("tgwarp: peer(x) - Sending keepalive packet"), None);
    assert_eq!(
        f.on_core_log("tgwarp: Received handshake response").as_deref(),
        Some("Telegram: Received handshake response")
    );
    assert!(!f.is_degraded());
    assert_eq!(
        f.on_core_log("tgwarp: write udp: operation not permitted").as_deref(),
        Some("Telegram: UDP send blocked (EPERM) — will rotate/restart")
    );
    assert_eq!(f.on_core_log("tgwarp: write udp: operation not permitted"), None);
    assert!(f.take_degraded());
    assert!(!f.take_degraded());
    assert_eq!(
        f.on_core_log("tgwarp: Handshake did not complete after 5 seconds").as_deref(),
        Some("Telegram: Handshake did not complete after 5 seconds")
    );
    assert!(f.is_degraded());
}

fn read_n(s: &mut TcpStream, n: usize) -> Vec<u8> {
    let mut b = vec![0u8; n];
    s.read_exact(&mut b).unwrap();
    b
}

#[test]
fn self_check_authenticates_and_connects_to_telegram_dc() {
    assert_eq!(
        socks5_auth(&Credentials { user: "ab".to_string(), pass: "xyz".to_string() }),
        vec![1, 2, b'a', b'b', 3, b'x', b'y', b'z']
    );
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        assert_eq!(read_n(&mut s, 4), vec![5, 2, 0, 2]);
        s.write_all(&[5, 2]).unwrap();
        let head = read_n(&mut s, 2);
        assert_eq!(head[0], 1);
        let user = read_n(&mut s, head[1] as usize);
        let plen = read_n(&mut s, 1)[0] as usize;
        let pass = read_n(&mut s, plen);
        s.write_all(&[1, 0]).unwrap();
        let req = read_n(&mut s, 10);
        s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
        (String::from_utf8(user).unwrap(), String::from_utf8(pass).unwrap(), req)
    });
    let c = creds();
    assert!(telegram_reachable_with("127.0.0.1", port, &c, Duration::from_secs(3), Duration::from_secs(3)));
    let (u, p, req) = server.join().unwrap();
    assert_eq!(u, c.user);
    assert_eq!(p, c.pass);
    assert_eq!(req, vec![5, 1, 0, 1, 149, 154, 167, 51, 1, 187]);
    assert_eq!(req, telegram_connect_request().to_vec());
}

#[test]
fn self_check_failures() {
    // CONNECT refused by the SOCKS (no traffic through WARP).
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        read_n(&mut s, 4);
        s.write_all(&[5, 0]).unwrap();
        read_n(&mut s, 10);
        s.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
    });
    assert!(!telegram_reachable_with("127.0.0.1", port, &creds(), Duration::from_secs(3), Duration::from_secs(3)));
    server.join().unwrap();

    // Nothing listening.
    let closed = TcpListener::bind("127.0.0.1:0").unwrap();
    let closed_port = closed.local_addr().unwrap().port();
    drop(closed);
    assert!(!telegram_reachable_with("127.0.0.1", closed_port, &creds(), Duration::from_secs(1), Duration::from_secs(1)));
}

#[test]
fn sweep_skips_dead_relays_and_caches_the_working_one() {
    let mut f = Fake::default();
    f.start_fail = vec!["1.2.3.4:2408".to_string()];
    f.working = vec!["162.159.192.1:894".to_string()];
    let c = creds();
    let r = bring_up(BASE, &c, &[], &mut f);
    assert_eq!(r, SweepResult::Working("162.159.192.1:894".to_string()));
    assert_eq!(
        f.started,
        vec!["1.2.3.4:2408", "162.159.192.8:894", "engage.cloudflareclient.com:2408", "162.159.192.1:894"]
    );
    assert_eq!(f.stops, 3);
    assert_eq!(f.logs[0], "Telegram proxy: trying 13 relay(s) — verifying via Telegram DC");
    assert!(f.has("Telegram: start failed on 1.2.3.4:2408 — boom"));
    assert!(f.has("Telegram: [4/13] connecting via 162.159.192.1:894…"));
    assert!(f.has("Telegram: cached working endpoint 162.159.192.1:894"));
    assert_eq!(f.saved, vec![rewrite_endpoint(BASE, "162.159.192.1:894")]);
    assert_eq!(f.states.last(), Some(&TelegramProxyState::running(&c)));

    // The cached endpoint works: nothing to re-save.
    let mut g = Fake::default();
    g.working = vec!["1.2.3.4:2408".to_string()];
    assert!(bring_up(BASE, &c, &[], &mut g).is_working());
    assert_eq!(g.started.len(), 1);
    assert!(g.saved.is_empty());

    // Everything avoided: nothing tried.
    let mut h = Fake::default();
    let avoid = endpoint_sweep(BASE, &[]);
    assert_eq!(bring_up(BASE, &c, &avoid, &mut h), SweepResult::NoCandidates);
    assert!(h.logs.is_empty());

    // First connect failing leaves an error state for the UI.
    let mut k = Fake::default();
    assert_eq!(initial_bring_up(BASE, &c, &mut k), SweepResult::AllFailed);
    assert!(k.has("Telegram: first connect didn't land — watchdog will keep retrying"));
    assert_eq!(k.states.last(), Some(&TelegramProxyState::error("no working WARP relay yet — retrying")));
}

#[test]
fn watchdog_retries_while_down_and_on_wake() {
    let mut f = Fake::default();
    let mut wd = HealthWatchdog::new(BASE, &creds(), 0, None);
    wd.tick(4_000, &mut f);
    assert_eq!(f.started.len(), 13);
    assert!(f.has("Telegram: no working relay yet — retrying in 4s"));
    assert_eq!(wd.down_wait_ms(), 4_000);
    wd.tick(8_000, &mut f);
    assert_eq!(f.started.len(), 13);
    wd.tick(12_000, &mut f);
    assert_eq!(f.started.len(), 26);
    wd.tick(60_000, &mut f);
    assert!(f.has("Telegram: woke from sleep — re-checking tunnel now"));
    assert_eq!(f.started.len(), 39);
}

#[test]
fn watchdog_rotates_on_degrade() {
    let dead_ep = "162.159.192.8:894";
    let mut f = Fake::default();
    f.running = true;
    f.degraded = true;
    f.current = Some(dead_ep.to_string());
    f.working = vec!["188.114.96.1:2408".to_string()];
    let mut wd = HealthWatchdog::new(BASE, &creds(), 0, Some(dead_ep.to_string()));
    wd.tick(4_000, &mut f);
    assert!(f.has("Telegram: tunnel died — rotating relay"));
    assert!(f.has("Telegram proxy: trying 12 relay(s) (rotating past 1 dead) — verifying via Telegram DC"));
    assert!(!f.started.contains(&dead_ep.to_string()));
    assert!(f.states.contains(&TelegramProxyState::error("reconnecting…")));
    assert_eq!(f.states.last(), Some(&TelegramProxyState::running(&creds())));
    assert_eq!(wd.active_endpoint(), Some("188.114.96.1:2408"));
    assert!(wd.dead().is_empty());
    assert_eq!(f.saved.last(), Some(&rewrite_endpoint(BASE, "188.114.96.1:2408")));
}

#[test]
fn watchdog_heartbeat_needs_two_fails() {
    let ep = "162.159.192.8:894";
    let mut f = Fake::default();
    f.running = true;
    f.current = Some(ep.to_string());
    f.working = vec![ep.to_string()];
    let mut wd = HealthWatchdog::new(BASE, &creds(), 0, Some(ep.to_string()));
    for k in 1..=7i64 {
        wd.tick(k * 4_000, &mut f);
    }
    assert_eq!(f.checks, 0);
    wd.tick(32_000, &mut f);
    assert_eq!(f.checks, 1);

    f.working.clear();
    for k in 9..=16i64 {
        wd.tick(k * 4_000, &mut f);
    }
    assert_eq!(f.checks, 2);
    assert_eq!(f.stops, 0);

    for k in 17..=24i64 {
        wd.tick(k * 4_000, &mut f);
    }
    assert!(f.has("Telegram: tunnel died — rotating relay"));
    assert_eq!(wd.active_endpoint(), None);
    assert_eq!(wd.down_wait_ms(), HEALTH_TICK_MS);
    assert!(wd.dead().is_empty());
}
