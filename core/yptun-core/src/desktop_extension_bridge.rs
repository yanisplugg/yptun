//! Loopback control API for the Chrome extension -- port of `DesktopExtensionBridge.kt`.
//!
//! MV3 gives an extension no raw sockets and no way to listen on a port, so it can never speak
//! VLESS itself (and REALITY needs a custom TLS ClientHello Chrome will not script). The only shape
//! in which a browser gets tcp / xhttp / tls / REALITY is `chrome.proxy` pointed at a proxy on this
//! machine with a real core behind it: the extension pastes the link here, the app raises the tunnel
//! with whatever engine the link needs, and the browser talks to an HTTP->SOCKS bridge in front of
//! the core's local SOCKS (Chrome cannot answer a SOCKS5 auth challenge).
//!
//! Bound to loopback, and every request must carry [GUARD_HEADER]. A custom header forces a CORS
//! preflight, and the preflight is only answered for a `chrome-extension://` origin, so a web page
//! cannot reach these endpoints even though they sit on localhost.
//!
//! The HTTP listener, the VPN manager and the HTTP->SOCKS bridge stay platform-side behind
//! [ExtensionHost]; this module owns the request gate, routing, the connect flow and the payloads.

use std::collections::HashSet;
use std::sync::Mutex;

use crate::location::LocationEntry;
use crate::share_link;

pub const LOOPBACK: &str = "127.0.0.1";

/// Next to DesktopSingleInstance's 47638, clear of the app's own ports.
pub const CONTROL_PORT: u16 = 47_639;

/// The HTTP proxy the extension points `chrome.proxy` at.
pub const PROXY_PORT: u16 = 47_640;

/// Any request without it is refused -- and asking for it is what forces the CORS preflight.
pub const GUARD_HEADER: &str = "X-YPtun-Extension";

pub const CONNECT_TIMEOUT_MS: u64 = 60_000;
pub const STOP_TIMEOUT_MS: u64 = 10_000;

/// A cold /connect blocks for as long as the core takes to come up; a few listener threads keep
/// the popup's /status polls answered meanwhile.
pub const EXECUTOR_THREADS: usize = 4;
pub const THREAD_NAME: &str = "YPtunExtensionBridge";

pub const EXTENSION_ORIGIN_PREFIX: &str = "chrome-extension://";
pub const APP_NAME: &str = "YPtun";

pub const ERR_FORBIDDEN: &str = "forbidden";
pub const ERR_NOT_FOUND: &str = "not-found";
pub const ERR_BAD_LINK: &str = "bad-link";
pub const ERR_NO_LOCATION: &str = "no-location";
pub const ERR_TIMEOUT: &str = "timeout";
pub const ERR_PROXY_PORT_BUSY: &str = "proxy-port-busy";
pub const ERR_GENERIC: &str = "error";

/// One request as the platform listener saw it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BridgeRequest {
    pub method: String,
    pub path: String,
    /// `Origin` header, empty when absent.
    pub origin: String,
    /// Whether [GUARD_HEADER] was present (any value).
    pub has_guard: bool,
    pub body: String,
}

/// The reply the platform listener must send.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BridgeResponse {
    pub code: u16,
    pub body: String,
    pub headers: Vec<(String, String)>,
}

impl BridgeResponse {
    /// Length for `sendResponseHeaders`: -1 when there is no body.
    pub fn content_length(&self) -> i64 {
        if self.body.is_empty() {
            -1
        } else {
            self.body.len() as i64
        }
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }
}

/// How the VPN settled after `startVpn()`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VpnOutcome {
    Connected,
    Error(String),
    Disconnected,
}

/// The core's local SOCKS endpoint (already normalized by the platform).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SocksEndpoint {
    pub host: String,
    pub port: i32,
    pub username: String,
    pub password: String,
}

/// Everything the bridge needs from the app.
pub trait ExtensionHost {
    fn app_version(&self) -> String;
    fn all_locations(&self) -> Vec<LocationEntry>;
    /// `repository.importText(link)`; a failure must be reported as `false`.
    fn import_text(&self, link: &str) -> bool;
    fn set_active_location_id(&self, storage_id: &str);
    fn active_location(&self) -> Option<LocationEntry>;
    /// `vpnManager.status.value is Connected`.
    fn is_connected(&self) -> bool;
    /// `startVpn()`, then wait up to `timeout_ms` for Connected / Error / Disconnected.
    /// `None` = nothing settled in time.
    fn start_vpn_and_wait(&self, timeout_ms: u64) -> Option<VpnOutcome>;
    /// `stopVpn()`, then wait up to `timeout_ms` for Disconnected / Error.
    fn stop_vpn_and_wait(&self, timeout_ms: u64);
    fn socks_endpoint(&self) -> SocksEndpoint;
    /// Starts the HTTP->SOCKS bridge; `false` when the listen port is busy.
    fn start_http_bridge(&self, listen_host: &str, listen_port: u16, socks: &SocksEndpoint) -> bool;
    fn stop_http_bridge(&self);
    fn log(&self, line: &str);
}

/// Bridge state: the SOCKS endpoint the running HTTP bridge points at (`None` = no bridge).
#[derive(Debug, Default)]
pub struct ExtensionBridge {
    bridge_socks: Mutex<Option<(String, i32)>>,
}

impl ExtensionBridge {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn listening_log() -> String {
        format!("Extension bridge listening on {LOOPBACK}:{CONTROL_PORT}")
    }

    pub fn port_busy_log(message: &str) -> String {
        format!("Extension bridge: port {CONTROL_PORT} busy - {message}")
    }

    /// Whether an HTTP bridge is up.
    pub fn has_bridge(&self) -> bool {
        self.lock().is_some()
    }

    /// Tears the proxy bridge down (the platform stops its listener).
    pub fn stop(&self, host: &dyn ExtensionHost) {
        let had = self.lock().take().is_some();
        if had {
            host.stop_http_bridge();
        }
    }

    /// Gate + routing of one request.
    pub fn handle(&self, host: &dyn ExtensionHost, req: &BridgeRequest) -> BridgeResponse {
        let origin = req.origin.as_str();
        if !origin.is_empty() && !origin.starts_with(EXTENSION_ORIGIN_PREFIX) {
            return reply(403, &error_body(ERR_FORBIDDEN), "");
        }
        if req.method == "OPTIONS" {
            return reply(204, "", origin);
        }
        if !req.has_guard {
            return reply(403, &error_body(ERR_FORBIDDEN), origin);
        }
        match req.path.as_str() {
            "/status" => reply(200, &self.status(host), origin),
            "/connect" => reply(200, &self.connect(host, req.body.trim()), origin),
            "/disconnect" => reply(200, &self.disconnect(host), origin),
            _ => reply(404, &error_body(ERR_NOT_FOUND), origin),
        }
    }

    /// Imports `link`, makes it the active location and raises the tunnel. A blank link connects
    /// whatever is already active (how the extension reconnects to a stored location).
    pub fn connect(&self, host: &dyn ExtensionHost, link: &str) -> String {
        let link = link.trim();
        if !link.is_empty() {
            let before: HashSet<String> = host.all_locations().into_iter().map(|e| e.storage_id).collect();
            if !host.import_text(link) {
                return error_body(ERR_BAD_LINK);
            }
            let entries = host.all_locations();
            if let Some(target) = connect_target(&before, &entries, link) {
                host.set_active_location_id(&target);
            }
        }
        match host.active_location() {
            Some(active) if active.location().is_complete() => {}
            _ => return error_body(ERR_NO_LOCATION),
        }
        if !host.is_connected() {
            match host.start_vpn_and_wait(CONNECT_TIMEOUT_MS) {
                Some(VpnOutcome::Connected) => {}
                Some(VpnOutcome::Error(message)) => return error_body(&message),
                _ => return error_body(ERR_TIMEOUT),
            }
        }
        if !self.start_proxy_bridge(host) {
            return error_body(ERR_PROXY_PORT_BUSY);
        }
        self.status(host)
    }

    pub fn disconnect(&self, host: &dyn ExtensionHost) -> String {
        self.stop(host);
        host.stop_vpn_and_wait(STOP_TIMEOUT_MS);
        self.status(host)
    }

    /// One HTTP proxy in front of the core's SOCKS on a port of our own; rebuilt when the SOCKS
    /// endpoint moved (the "secured SOCKS" toggle changes the port and the credentials).
    pub fn start_proxy_bridge(&self, host: &dyn ExtensionHost) -> bool {
        let socks = host.socks_endpoint();
        let endpoint = (socks.host.clone(), socks.port);
        let mut guard = self.lock();
        if guard.as_ref() == Some(&endpoint) {
            return true;
        }
        if guard.is_some() {
            host.stop_http_bridge();
        }
        if !host.start_http_bridge(LOOPBACK, PROXY_PORT, &socks) {
            *guard = None;
            return false;
        }
        *guard = Some(endpoint);
        drop(guard);
        host.log(&format!("Extension proxy on {LOOPBACK}:{PROXY_PORT} -> SOCKS {}:{}", socks.host, socks.port));
        true
    }

    pub fn status(&self, host: &dyn ExtensionHost) -> String {
        let connected = host.is_connected();
        let name = host.active_location().map(|e| e.name).unwrap_or_default();
        let proxy = if connected && self.has_bridge() { format!("{LOOPBACK}:{PROXY_PORT}") } else { String::new() };
        status_body(&host.app_version(), connected, &name, &proxy)
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Option<(String, i32)>> {
        self.bridge_socks.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The entry an import produced: the one new storage id, or -- when the link was already in the
/// list and the merge deduped it -- the entry pointing at the same server:port.
pub fn connect_target(before: &HashSet<String>, entries: &[LocationEntry], link: &str) -> Option<String> {
    if let Some(fresh) = entries.iter().find(|e| !before.contains(&e.storage_id)) {
        return Some(fresh.storage_id.clone());
    }
    let parsed = share_link::parse(link)?;
    entries
        .iter()
        .find(|e| e.proxy.as_ref().map_or(false, |p| p.server == parsed.server && p.server_port == parsed.server_port))
        .map(|e| e.storage_id.clone())
}

/// The reply for an exception thrown while handling (`500`, no CORS headers).
pub fn internal_error(message: Option<&str>) -> BridgeResponse {
    reply(500, &error_body(message.unwrap_or(ERR_GENERIC)), "")
}

pub fn reply(code: u16, body: &str, origin: &str) -> BridgeResponse {
    let mut headers = Vec::new();
    if !origin.is_empty() {
        headers.push(("Access-Control-Allow-Origin".to_string(), origin.to_string()));
        headers.push(("Access-Control-Allow-Headers".to_string(), GUARD_HEADER.to_string()));
        headers.push(("Access-Control-Allow-Methods".to_string(), "GET, POST, OPTIONS".to_string()));
        headers.push(("Access-Control-Max-Age".to_string(), "600".to_string()));
    }
    headers.push(("Content-Type".to_string(), "application/json; charset=utf-8".to_string()));
    BridgeResponse { code, body: body.to_string(), headers }
}

pub fn status_body(version: &str, connected: bool, location: &str, proxy: &str) -> String {
    format!(
        "{{\"app\":\"{APP_NAME}\",\"version\":{},\"connected\":{connected},\"location\":{},\"proxy\":{}}}",
        json_string(version),
        json_string(location),
        json_string(proxy)
    )
}

pub fn error_body(code: &str) -> String {
    format!("{{\"error\":{}}}", json_string(code))
}

pub fn json_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    out.push('"');
    for c in value.chars() {
        match c {
            '"' | '\\' => {
                out.push('\\');
                out.push(c);
            }
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
