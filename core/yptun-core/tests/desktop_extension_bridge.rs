use std::cell::RefCell;
use std::collections::HashSet;

use yptun_core::desktop_extension_bridge::*;
use yptun_core::location::LocationEntry;
use yptun_core::model::{EngineType, ProxyProfile};

const UUID: &str = "11111111-2222-3333-4444-555555555555";

fn entry(id: &str, name: &str, server: &str, port: i32) -> LocationEntry {
    LocationEntry {
        storage_id: id.into(),
        name: name.into(),
        engine: Some(EngineType::Standard),
        proxy: Some(ProxyProfile {
            kind: "vless".into(),
            server: server.into(),
            server_port: port,
            uuid: UUID.into(),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn link(server: &str, port: i32) -> String {
    format!("vless://{UUID}@{server}:{port}?type=tcp&security=none#x")
}

struct Host {
    locations: RefCell<Vec<LocationEntry>>,
    import_adds: RefCell<Option<LocationEntry>>,
    import_ok: bool,
    active: RefCell<Option<String>>,
    connected: RefCell<bool>,
    start_outcome: Option<VpnOutcome>,
    socks: RefCell<SocksEndpoint>,
    bridge_ok: bool,
    calls: RefCell<Vec<String>>,
}

impl Host {
    fn new() -> Self {
        Host {
            locations: RefCell::new(vec![entry("a", "A", "ex.com", 443)]),
            import_adds: RefCell::new(None),
            import_ok: true,
            active: RefCell::new(Some("a".into())),
            connected: RefCell::new(false),
            start_outcome: Some(VpnOutcome::Connected),
            socks: RefCell::new(SocksEndpoint { host: "127.0.0.1".into(), port: 10808, ..Default::default() }),
            bridge_ok: true,
            calls: RefCell::new(Vec::new()),
        }
    }
    fn calls(&self) -> Vec<String> {
        self.calls.borrow().clone()
    }
    fn count(&self, prefix: &str) -> usize {
        self.calls.borrow().iter().filter(|c| c.starts_with(prefix)).count()
    }
}

impl ExtensionHost for Host {
    fn app_version(&self) -> String {
        "1.2.3".into()
    }
    fn all_locations(&self) -> Vec<LocationEntry> {
        self.locations.borrow().clone()
    }
    fn import_text(&self, link: &str) -> bool {
        self.calls.borrow_mut().push(format!("import {link}"));
        if !self.import_ok {
            return false;
        }
        if let Some(e) = self.import_adds.borrow_mut().take() {
            self.locations.borrow_mut().push(e);
        }
        true
    }
    fn set_active_location_id(&self, storage_id: &str) {
        self.calls.borrow_mut().push(format!("active {storage_id}"));
        *self.active.borrow_mut() = Some(storage_id.into());
    }
    fn active_location(&self) -> Option<LocationEntry> {
        let id = self.active.borrow().clone()?;
        self.locations.borrow().iter().find(|e| e.storage_id == id).cloned()
    }
    fn is_connected(&self) -> bool {
        *self.connected.borrow()
    }
    fn start_vpn_and_wait(&self, timeout_ms: u64) -> Option<VpnOutcome> {
        self.calls.borrow_mut().push(format!("start {timeout_ms}"));
        if self.start_outcome == Some(VpnOutcome::Connected) {
            *self.connected.borrow_mut() = true;
        }
        self.start_outcome.clone()
    }
    fn stop_vpn_and_wait(&self, timeout_ms: u64) {
        self.calls.borrow_mut().push(format!("stop {timeout_ms}"));
        *self.connected.borrow_mut() = false;
    }
    fn socks_endpoint(&self) -> SocksEndpoint {
        self.socks.borrow().clone()
    }
    fn start_http_bridge(&self, listen_host: &str, listen_port: u16, socks: &SocksEndpoint) -> bool {
        self.calls.borrow_mut().push(format!("bridge {listen_host}:{listen_port}->{}:{}", socks.host, socks.port));
        self.bridge_ok
    }
    fn stop_http_bridge(&self) {
        self.calls.borrow_mut().push("bridge-stop".into());
    }
    fn log(&self, line: &str) {
        self.calls.borrow_mut().push(format!("log {line}"));
    }
}

fn req(method: &str, path: &str, origin: &str, guard: bool, body: &str) -> BridgeRequest {
    BridgeRequest { method: method.into(), path: path.into(), origin: origin.into(), has_guard: guard, body: body.into() }
}

const EXT: &str = "chrome-extension://abcdef";

#[test]
fn json_string_escapes() {
    assert_eq!(json_string("a\"b\\c\nd\re\tf\u{1}g"), "\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"");
    assert_eq!(json_string("\u{44f}"), "\"\u{44f}\"");
    assert_eq!(json_string(""), "\"\"");
}

#[test]
fn payloads_and_logs() {
    assert_eq!(
        status_body("1.0", true, "Loc", "127.0.0.1:47640"),
        r#"{"app":"YPtun","version":"1.0","connected":true,"location":"Loc","proxy":"127.0.0.1:47640"}"#
    );
    assert_eq!(error_body("x"), r#"{"error":"x"}"#);
    assert_eq!(ExtensionBridge::listening_log(), "Extension bridge listening on 127.0.0.1:47639");
    assert_eq!(ExtensionBridge::port_busy_log("in use"), "Extension bridge: port 47639 busy - in use");
    let e = internal_error(None);
    assert_eq!(e.code, 500);
    assert_eq!(e.body, r#"{"error":"error"}"#);
    assert_eq!(e.headers.len(), 1);
    assert_eq!(internal_error(Some("boom")).body, r#"{"error":"boom"}"#);
}

#[test]
fn request_gate() {
    let host = Host::new();
    let b = ExtensionBridge::new();

    let r = b.handle(&host, &req("GET", "/status", "https://evil.example", true, ""));
    assert_eq!(r.code, 403);
    assert_eq!(r.body, r#"{"error":"forbidden"}"#);
    assert_eq!(r.header("Access-Control-Allow-Origin"), None);
    assert_eq!(r.header("Content-Type"), Some("application/json; charset=utf-8"));

    let r = b.handle(&host, &req("OPTIONS", "/connect", EXT, false, ""));
    assert_eq!(r.code, 204);
    assert_eq!(r.body, "");
    assert_eq!(r.content_length(), -1);
    assert_eq!(r.header("Access-Control-Allow-Origin"), Some(EXT));
    assert_eq!(r.header("Access-Control-Allow-Headers"), Some(GUARD_HEADER));
    assert_eq!(r.header("Access-Control-Allow-Methods"), Some("GET, POST, OPTIONS"));
    assert_eq!(r.header("Access-Control-Max-Age"), Some("600"));

    let r = b.handle(&host, &req("GET", "/status", EXT, false, ""));
    assert_eq!(r.code, 403);
    assert_eq!(r.header("Access-Control-Allow-Origin"), Some(EXT));

    let r = b.handle(&host, &req("GET", "/nope", EXT, true, ""));
    assert_eq!(r.code, 404);
    assert_eq!(r.body, r#"{"error":"not-found"}"#);

    let r = b.handle(&host, &req("GET", "/status", "", true, ""));
    assert_eq!(r.code, 200);
    assert_eq!(r.headers.len(), 1);
    assert_eq!(r.content_length(), r.body.len() as i64);
    assert_eq!(r.body, r#"{"app":"YPtun","version":"1.2.3","connected":false,"location":"A","proxy":""}"#);
    assert!(host.calls().is_empty());
}

#[test]
fn connect_fresh_import() {
    let host = Host::new();
    *host.import_adds.borrow_mut() = Some(entry("b", "B", "new.com", 8443));
    let b = ExtensionBridge::new();
    let l = link("new.com", 8443);
    let r = b.handle(&host, &req("POST", "/connect", EXT, true, &format!("  {l}\n")));
    assert_eq!(r.code, 200);
    assert_eq!(r.body, r#"{"app":"YPtun","version":"1.2.3","connected":true,"location":"B","proxy":"127.0.0.1:47640"}"#);
    assert_eq!(
        host.calls(),
        vec![
            format!("import {l}"),
            "active b".to_string(),
            "start 60000".to_string(),
            "bridge 127.0.0.1:47640->127.0.0.1:10808".to_string(),
            "log Extension proxy on 127.0.0.1:47640 -> SOCKS 127.0.0.1:10808".to_string(),
        ]
    );
    assert!(b.has_bridge());
}

#[test]
fn connect_target_prefers_new_then_same_server() {
    let entries = vec![entry("a", "A", "ex.com", 443), entry("b", "B", "other.com", 8443)];
    let before: HashSet<String> = ["a".to_string(), "b".to_string()].into_iter().collect();
    assert_eq!(connect_target(&before, &entries, &link("other.com", 8443)), Some("b".to_string()));
    assert_eq!(connect_target(&before, &entries, &link("other.com", 9999)), None);
    assert_eq!(connect_target(&before, &entries, "not a link"), None);
    let only_a: HashSet<String> = ["a".to_string()].into_iter().collect();
    assert_eq!(connect_target(&only_a, &entries, &link("ex.com", 443)), Some("b".to_string()));
}

#[test]
fn connect_errors() {
    let b = ExtensionBridge::new();

    let mut host = Host::new();
    host.import_ok = false;
    assert_eq!(b.connect(&host, &link("x.com", 1)), r#"{"error":"bad-link"}"#);

    let host = Host::new();
    *host.active.borrow_mut() = None;
    assert_eq!(b.connect(&host, ""), r#"{"error":"no-location"}"#);
    assert_eq!(host.count("start"), 0);

    let host = Host::new();
    host.locations.borrow_mut()[0].proxy = None;
    assert_eq!(b.connect(&host, "   "), r#"{"error":"no-location"}"#);
    assert_eq!(host.count("import"), 0);

    let mut host = Host::new();
    host.start_outcome = Some(VpnOutcome::Error("boom".into()));
    assert_eq!(b.connect(&host, ""), r#"{"error":"boom"}"#);

    let mut host = Host::new();
    host.start_outcome = Some(VpnOutcome::Disconnected);
    assert_eq!(b.connect(&host, ""), r#"{"error":"timeout"}"#);
    host.start_outcome = None;
    assert_eq!(b.connect(&host, ""), r#"{"error":"timeout"}"#);

    let mut host = Host::new();
    host.bridge_ok = false;
    assert_eq!(b.connect(&host, ""), r#"{"error":"proxy-port-busy"}"#);
    assert!(!b.has_bridge());
    assert_eq!(host.count("log"), 0);
}

#[test]
fn bridge_reused_and_rebuilt() {
    let host = Host::new();
    let b = ExtensionBridge::new();
    b.connect(&host, "");
    b.connect(&host, "");
    assert_eq!(host.count("start"), 1);
    assert_eq!(host.count("bridge 1"), 1);
    assert_eq!(host.count("bridge-stop"), 0);

    host.socks.borrow_mut().port = 20808;
    b.connect(&host, "");
    assert_eq!(host.count("bridge-stop"), 1);
    assert_eq!(host.calls().iter().filter(|c| c.as_str() == "bridge 127.0.0.1:47640->127.0.0.1:20808").count(), 1);
}

#[test]
fn disconnect_and_status() {
    let host = Host::new();
    let b = ExtensionBridge::new();
    *host.connected.borrow_mut() = true;
    assert!(b.status(&host).contains(r#""connected":true,"location":"A","proxy":"""#));

    b.connect(&host, "");
    let body = b.disconnect(&host);
    assert_eq!(body, r#"{"app":"YPtun","version":"1.2.3","connected":false,"location":"A","proxy":""}"#);
    assert!(!b.has_bridge());
    let calls = host.calls();
    let n = calls.len();
    assert_eq!(&calls[n - 2..], &["bridge-stop".to_string(), "stop 10000".to_string()]);

    b.stop(&host);
    assert_eq!(host.count("bridge-stop"), 1);
}
