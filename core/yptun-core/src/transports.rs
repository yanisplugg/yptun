//! Per-engine transport settings stored inside a location (port of the transport classes in the
//! app's `LocationConfig.kt`). JSON field names match the Kotlin app.

use regex_lite::Regex;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::LazyLock;

use crate::model::{ProxyCore, ProxyProfile};
use crate::uri::{after, before, percent_decode};

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

fn not_blank_opt(s: &Option<String>) -> bool {
    s.as_deref().is_some_and(not_blank)
}

/// `substringAfterLast(':', "")`.
fn after_last_colon(s: &str) -> &str {
    s.rfind(':').map_or("", |i| &s[i + 1..])
}

// ---------------- WARP ----------------

/// Where the Cloudflare WARP hop sits next to the proxy.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
pub enum WarpMode {
    /// Client → WARP → proxy → Internet.
    #[serde(rename = "client")]
    #[default]
    Client,
    /// Client → proxy → WARP → Internet.
    #[serde(rename = "server")]
    Server,
}

impl WarpMode {
    pub fn from_value(value: Option<&str>) -> Self {
        match value.map(|v| v.trim().to_lowercase()).as_deref() {
            Some("server" | "egress" | "exit") => Self::Server,
            _ => Self::Client,
        }
    }
}

/// WARP tunnel protocol: plain WireGuard, or AmneziaWG (gets through where WireGuard is cut).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
pub enum WarpProtocol {
    #[serde(rename = "wg")]
    Wg,
    #[serde(rename = "awg")]
    #[default]
    Awg,
}

impl WarpProtocol {
    pub fn from_value(value: Option<&str>) -> Self {
        match value.map(|v| v.trim().to_lowercase()).as_deref() {
            Some("wg" | "wireguard") => Self::Wg,
            _ => Self::Awg,
        }
    }
}

// ---------------- VK-TURN ----------------

pub const OUTBOUND_WIREGUARD: &str = "wireguard";
pub const OUTBOUND_AMNEZIAWG: &str = "amneziawg";
pub const OUTBOUND_PROXY: &str = "proxy";
pub const CORE_FREETURN: &str = "freeturn";
pub const CORE_WDTT: &str = "wdtt";
pub const CORE_CSQTT: &str = "csqtt";
/// Worker cap of the csqtt client since 2.5.0.
pub const CSQTT_MAX_WORKERS: i32 = 100;
pub const DEFAULT_WDTT_PORT: i32 = 56000;
pub const DEFAULT_CSQTT_PORT: i32 = 46000;
pub const DEFAULT_FREETURN_PORT: i32 = 9000;

fn default_freeturn_port() -> i32 {
    DEFAULT_FREETURN_PORT
}
fn default_outbound() -> String {
    OUTBOUND_WIREGUARD.into()
}
fn default_core() -> String {
    CORE_FREETURN.into()
}

/// VK-TURN transport (freeturn / qWDTT / csqtt cores) of an [`crate::model::EngineType::VkTurn`] location.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct VkTurnConfig {
    /// The panel's freeturn:// link (freeturn core).
    pub uri: String,
    /// VK call links / hashes, one per line.
    #[serde(rename = "vk_link")]
    pub vk_link: String,
    #[serde(rename = "listen_port", default = "default_freeturn_port")]
    pub listen_port: i32,
    /// Parallel TURN streams (freeturn -n); 0 = client default.
    pub streams: i32,
    /// Proxy link chained on top of the WG-over-VK tunnel.
    #[serde(rename = "chain_proxy_link")]
    pub chain_proxy_link: String,
    /// What exits: wireguard / amneziawg (UDP mode) or proxy (TCP mode).
    #[serde(default = "default_outbound")]
    pub outbound: String,
    #[serde(rename = "outbound_proxy_link")]
    pub outbound_proxy_link: String,
    #[serde(rename = "proxy_core")]
    pub proxy_core: ProxyCore,
    /// freeturn / wdtt / csqtt.
    #[serde(default = "default_core")]
    pub core: String,
    #[serde(rename = "wdtt_peer")]
    pub wdtt_peer: String,
    #[serde(rename = "wdtt_port")]
    pub wdtt_port: i32,
    #[serde(rename = "wdtt_password")]
    pub wdtt_password: String,
    /// Unused since qWDTT; kept so stored locations load.
    #[serde(rename = "wdtt_fingerprint")]
    pub wdtt_fingerprint: String,
    #[serde(rename = "wdtt_workers")]
    pub wdtt_workers: i32,
    #[serde(rename = "wdtt_plus")]
    pub wdtt_plus: WdttPlusOptions,
    #[serde(rename = "csqtt_peer")]
    pub csqtt_peer: String,
    #[serde(rename = "csqtt_port")]
    pub csqtt_port: i32,
    #[serde(rename = "csqtt_password")]
    pub csqtt_password: String,
    #[serde(rename = "csqtt_workers")]
    pub csqtt_workers: i32,
    #[serde(rename = "csqtt_options")]
    pub csqtt: CsqttOptions,
    #[serde(rename = "freeturn_multi_server")]
    pub freeturn_multi_server: bool,
    #[serde(rename = "extra_freeturn_uris")]
    pub extra_freeturn_uris: Vec<String>,
}

impl Default for VkTurnConfig {
    fn default() -> Self {
        Self {
            uri: String::new(),
            vk_link: String::new(),
            listen_port: DEFAULT_FREETURN_PORT,
            streams: 0,
            chain_proxy_link: String::new(),
            outbound: default_outbound(),
            outbound_proxy_link: String::new(),
            proxy_core: ProxyCore::Auto,
            core: default_core(),
            wdtt_peer: String::new(),
            wdtt_port: 0,
            wdtt_password: String::new(),
            wdtt_fingerprint: String::new(),
            wdtt_workers: 0,
            wdtt_plus: WdttPlusOptions::default(),
            csqtt_peer: String::new(),
            csqtt_port: 0,
            csqtt_password: String::new(),
            csqtt_workers: 0,
            csqtt: CsqttOptions::default(),
            freeturn_multi_server: false,
            extra_freeturn_uris: Vec::new(),
        }
    }
}

impl VkTurnConfig {
    /// Primary freeturn:// link plus the extra servers when multi-server is on.
    pub fn all_freeturn_uris(&self) -> Vec<String> {
        let mut all = vec![self.uri.clone()];
        if self.freeturn_multi_server {
            all.extend(self.extra_freeturn_uris.iter().cloned());
        }
        all.into_iter()
            .map(|u| u.trim().to_string())
            .filter(|u| crate::share_link::starts_ci(u, "freeturn://"))
            .collect()
    }

    pub fn uses_wdtt(&self) -> bool {
        self.core.eq_ignore_ascii_case(CORE_WDTT)
    }

    pub fn uses_csqtt(&self) -> bool {
        self.core.eq_ignore_ascii_case(CORE_CSQTT)
    }

    /// wdtt-server `host:port` ([`Self::wdtt_peer`] verbatim if it already carries a port).
    pub fn wdtt_peer_addr(&self) -> String {
        let host = self.wdtt_peer.trim();
        if host.is_empty() {
            return String::new();
        }
        if host.contains(':') && after_last_colon(host).parse::<i32>().is_ok() {
            return host.to_string();
        }
        let port = if (1..=65535).contains(&self.wdtt_port) { self.wdtt_port } else { DEFAULT_WDTT_PORT };
        format!("{host}:{port}")
    }

    /// Where the qWDTT core dials: the raw port in Raw mode, else [`Self::wdtt_peer_addr`].
    pub fn wdtt_dial_addr(&self) -> String {
        if !self.wdtt_plus.raw_mode {
            return self.wdtt_peer_addr();
        }
        let addr = self.wdtt_peer_addr();
        let host = addr.rfind(':').map_or(addr.as_str(), |i| &addr[..i]);
        if host.is_empty() {
            String::new()
        } else {
            format!("{host}:{}", self.wdtt_plus.raw_port_or_default())
        }
    }

    /// csqtt server `host:port` ([`Self::csqtt_peer`] verbatim if it already has a port; IPv6 bracketed).
    pub fn csqtt_peer_addr(&self) -> String {
        let host = self.csqtt_peer.trim();
        if host.is_empty() {
            return String::new();
        }
        let has_port = if host.starts_with('[') {
            host.contains("]:")
        } else {
            host.matches(':').count() == 1 && after_last_colon(host).parse::<i32>().is_ok()
        };
        if has_port {
            return host.to_string();
        }
        let port = if (1..=65535).contains(&self.csqtt_port) { self.csqtt_port } else { DEFAULT_CSQTT_PORT };
        let bare = host.trim_start_matches('[').trim_end_matches(']');
        if bare.contains(':') {
            format!("[{bare}]:{port}")
        } else {
            format!("{bare}:{port}")
        }
    }

    /// Storable (shown in the list) before the user filled in the VK link.
    pub fn is_storable(&self) -> bool {
        (1..=65535).contains(&self.listen_port)
            && if self.uses_wdtt() {
                not_blank(&self.wdtt_peer)
            } else if self.uses_csqtt() {
                not_blank(&self.csqtt_peer)
            } else {
                self.uri.starts_with("freeturn://")
            }
    }

    pub fn is_complete(&self) -> bool {
        self.is_storable() && not_blank(&self.vk_link)
    }

    /// UDP payload (WireGuard/AmneziaWG) needs udprelay; a TCP proxy needs tcpfwd.
    pub fn required_mode(&self) -> &'static str {
        if self.outbound == OUTBOUND_PROXY {
            "tcp"
        } else {
            "udp"
        }
    }

    /// Auto → concrete core for the exit/chain proxy: explicit choice, then Xray-only profiles,
    /// then the app-wide default, then sing-box.
    pub fn resolved_proxy_core(&self, profile: Option<&ProxyProfile>, global: ProxyCore) -> ProxyCore {
        if self.proxy_core != ProxyCore::Auto {
            self.proxy_core
        } else if profile.is_some_and(|p| not_blank_opt(&p.raw_xray_config) || p.requires_xray()) {
            ProxyCore::Xray
        } else if global != ProxyCore::Auto {
            global
        } else {
            ProxyCore::SingBox
        }
    }

    /// qWDTT core options (wdttmobile.Options JSON). `listen` = local UDP for WireGuard, or the
    /// SOCKS5 the core serves in Raw mode.
    pub fn wdtt_core_options_json(&self, listen: &str, device_id: &str) -> String {
        let p = &self.wdtt_plus;
        json!({
            "peer": self.wdtt_dial_addr(),
            "raw": p.raw_mode,
            "vk_hashes": self.vk_link,
            "password": self.wdtt_password,
            "listen": listen,
            "workers": self.wdtt_workers,
            "device_id": device_id,
            "captcha_mode": "auto",
            "turn_host": p.turn_host.trim(),
            "turn_port": p.turn_port.trim(),
            "turn_tcp": p.rt_network_mode,
            "obfs": if p.obfs_video { "video" } else { "audio" },
            "go_dns": p.go_dns.trim(),
            "vk_anon_path": if p.vk_anon_legacy { "legacy" } else { "vkcalls" },
        })
        .to_string()
    }

    /// csqtt core options. 27 workers per VK hash (max 72) unless set; explicit values cap at 100.
    pub fn csqtt_core_options_json(&self, client: &str, listen: &str, device_id: &str) -> String {
        let o = &self.csqtt;
        let hashes: Vec<&str> =
            self.vk_link.split(['\n', '\r', '\t', ' ', ',', ';']).filter(|s| !s.trim().is_empty()).collect();
        let workers = if self.csqtt_workers > 0 {
            self.csqtt_workers.min(CSQTT_MAX_WORKERS)
        } else {
            (hashes.len().max(1) as i32 * 27).min(72)
        };
        json!({
            "client": client,
            "listen": listen,
            "peer": self.csqtt_peer_addr(),
            "vk_hashes": hashes.join(","),
            "password": self.csqtt_password,
            "workers": workers,
            "device_id": device_id,
            "obfs": if o.obfs_video { "video" } else { "audio" },
            "turn_tcp": o.turn_tcp,
            "fingerprint": if o.fingerprint.trim().is_empty() { CSQTT_DEFAULT_FINGERPRINT } else { o.fingerprint.as_str() },
            "client_ids": o.client_ids.trim(),
            "vk_auth_mode": if o.vk_auth_legacy { "legacy" } else { "vkcalls" },
            "captcha_mode": "auto",
            "turn_host": o.turn_host.trim(),
            "turn_port": o.turn_port.trim(),
            "redistribute": o.redistribute,
        })
        .to_string()
    }
}

pub const CSQTT_DEFAULT_FINGERPRINT: &str = "firefox";
pub const CSQTT_FINGERPRINTS: [&str; 5] = ["firefox", "chrome", "safari", "edge", "opera"];

fn default_true() -> bool {
    true
}
fn default_csqtt_fp() -> String {
    CSQTT_DEFAULT_FINGERPRINT.into()
}

/// Advanced options of the csqtt core.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct CsqttOptions {
    #[serde(rename = "obfs_video", default = "default_true")]
    pub obfs_video: bool,
    #[serde(rename = "turn_tcp")]
    pub turn_tcp: bool,
    #[serde(default = "default_csqtt_fp")]
    pub fingerprint: String,
    #[serde(rename = "client_ids")]
    pub client_ids: String,
    #[serde(rename = "vk_auth_legacy")]
    pub vk_auth_legacy: bool,
    pub redistribute: bool,
    #[serde(rename = "turn_host")]
    pub turn_host: String,
    #[serde(rename = "turn_port")]
    pub turn_port: String,
}

impl Default for CsqttOptions {
    fn default() -> Self {
        Self {
            obfs_video: true,
            turn_tcp: false,
            fingerprint: default_csqtt_fp(),
            client_ids: String::new(),
            vk_auth_legacy: false,
            redistribute: false,
            turn_host: String::new(),
            turn_port: String::new(),
        }
    }
}

pub const WDTT_DEFAULT_RAW_PORT: i32 = 56003;

/// Advanced options of the qWDTT core (the name is left over from WDTT Plus).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct WdttPlusOptions {
    /// TURN over TCP instead of UDP.
    #[serde(rename = "rt_network_mode")]
    pub rt_network_mode: bool,
    #[serde(rename = "obfs_video")]
    pub obfs_video: bool,
    #[serde(rename = "go_dns")]
    pub go_dns: String,
    #[serde(rename = "vk_anon_legacy")]
    pub vk_anon_legacy: bool,
    #[serde(rename = "turn_host")]
    pub turn_host: String,
    #[serde(rename = "turn_port")]
    pub turn_port: String,
    /// Raw IP packets without WireGuard (server must run `-listen-raw`).
    #[serde(rename = "raw_mode")]
    pub raw_mode: bool,
    #[serde(rename = "raw_port")]
    pub raw_port: i32,
}

impl WdttPlusOptions {
    pub fn raw_port_or_default(&self) -> i32 {
        if (1..=65535).contains(&self.raw_port) {
            self.raw_port
        } else {
            WDTT_DEFAULT_RAW_PORT
        }
    }
}

// ---------------- proxy core knobs / FakeDNS ----------------

fn default_mux_protocol() -> String {
    "h2mux".into()
}
fn default_mux_streams() -> i32 {
    8
}

/// Advanced per-location options for sing-box / Xray.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AdvancedCoreConfig {
    #[serde(rename = "mux_enabled")]
    pub mux_enabled: bool,
    /// sing-box: smux | yamux | h2mux; Xray ignores it.
    #[serde(rename = "mux_protocol", default = "default_mux_protocol")]
    pub mux_protocol: String,
    #[serde(rename = "mux_max_streams", default = "default_mux_streams")]
    pub mux_max_streams: i32,
    #[serde(rename = "tcp_fast_open")]
    pub tcp_fast_open: bool,
    #[serde(default = "default_true")]
    pub sniff: bool,
    #[serde(rename = "tls_fragment")]
    pub tls_fragment: bool,
}

impl Default for AdvancedCoreConfig {
    fn default() -> Self {
        Self {
            mux_enabled: false,
            mux_protocol: default_mux_protocol(),
            mux_max_streams: 8,
            tcp_fast_open: false,
            sniff: true,
            tls_fragment: false,
        }
    }
}

fn default_inet4() -> String {
    "198.18.0.0/15".into()
}
fn default_inet6() -> String {
    "fc00::/18".into()
}

/// FakeDNS plumbing extracted from an imported Xray config, reproduced natively on sing-box.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FakeDnsSpec {
    #[serde(rename = "inet4_range", default = "default_inet4")]
    pub inet4_range: String,
    #[serde(rename = "inet6_range", default = "default_inet6")]
    pub inet6_range: String,
    /// `dns.hosts` regex entries that blackholed a domain (→ reject rules).
    #[serde(rename = "block_regex")]
    pub block_regex: Vec<String>,
}

impl Default for FakeDnsSpec {
    fn default() -> Self {
        Self { inet4_range: default_inet4(), inet6_range: default_inet6(), block_regex: Vec::new() }
    }
}

// ---------------- MasterDNS ----------------

pub const MASTERDNS_DEFAULT_ENCRYPTION: i32 = 1;
pub const MASTERDNS_ENCRYPTION_LABELS: [&str; 6] =
    ["Без шифрования", "XOR", "ChaCha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM"];

fn default_mdns_enc() -> i32 {
    MASTERDNS_DEFAULT_ENCRYPTION
}

/// MasterDnsVPN DNS tunnel of a MasterDns location.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct MasterDnsConfig {
    /// Tunnel domain(s), comma/newline separated.
    pub domains: String,
    /// Shared secret (the server's encrypt_key.txt).
    #[serde(rename = "key")]
    pub encryption_key: String,
    /// 0=none 1=XOR 2=ChaCha20 3..5=AES-GCM.
    #[serde(rename = "encryption", default = "default_mdns_enc")]
    pub encryption_method: i32,
    /// Resolvers `ip` or `ip:port`, comma/newline separated.
    pub resolvers: String,
    #[serde(rename = "balancing")]
    pub balancing_strategy: i32,
    #[serde(rename = "duplication")]
    pub packet_duplication: i32,
    #[serde(rename = "proxy_link")]
    pub proxy_link: String,
    #[serde(rename = "proxy_core")]
    pub proxy_core: ProxyCore,
}

impl Default for MasterDnsConfig {
    fn default() -> Self {
        Self {
            domains: String::new(),
            encryption_key: String::new(),
            encryption_method: MASTERDNS_DEFAULT_ENCRYPTION,
            resolvers: String::new(),
            balancing_strategy: 0,
            packet_duplication: 0,
            proxy_link: String::new(),
            proxy_core: ProxyCore::Auto,
        }
    }
}

/// Comma, semicolon or any whitespace separate entries; trimmed, de-duplicated (first wins).
fn split_entries(value: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for e in value.split(|c: char| c == ',' || c == ';' || c.is_whitespace()) {
        let e = e.trim();
        if !e.is_empty() && !out.iter().any(|x| x == e) {
            out.push(e.to_string());
        }
    }
    out
}

impl MasterDnsConfig {
    pub fn is_complete(&self) -> bool {
        !self.domain_list().is_empty() && not_blank(&self.encryption_key) && !self.resolver_list().is_empty()
    }
    pub fn has_proxy(&self) -> bool {
        not_blank(&self.proxy_link)
    }
    pub fn domain_list(&self) -> Vec<String> {
        split_entries(&self.domains)
    }
    pub fn resolver_list(&self) -> Vec<String> {
        split_entries(&self.resolvers)
    }
    /// Resolver hosts only (what a desktop TUN must route around the tunnel).
    pub fn resolver_hosts(&self) -> Vec<String> {
        self.resolver_list()
            .into_iter()
            .map(|e| {
                if let Some(rest) = e.strip_prefix('[') {
                    before(rest, ']').to_string()
                } else if e.matches(':').count() == 1 {
                    before(&e, ':').to_string()
                } else {
                    e
                }
            })
            .filter(|h| not_blank(h))
            .collect()
    }
    pub fn resolved_proxy_core(&self, profile: Option<&ProxyProfile>, global: ProxyCore) -> ProxyCore {
        over_tunnel_proxy_core(self.proxy_core, profile, global)
    }
    pub fn normalized(&self) -> Self {
        Self {
            domains: self.domain_list().join(","),
            encryption_key: self.encryption_key.trim().to_string(),
            encryption_method: self.encryption_method.clamp(0, 5),
            resolvers: self.resolver_list().join(","),
            balancing_strategy: self.balancing_strategy.clamp(0, 8),
            packet_duplication: self.packet_duplication.clamp(0, 10),
            proxy_link: self.proxy_link.trim().to_string(),
            proxy_core: self.proxy_core,
        }
    }
}

/// Core for a proxy chained over a tunnel's local SOCKS: like VK-TURN, but Auto means Xray
/// (socket-level dialerProxy keeps reality/xtls-vision intact over the tunnel).
pub fn over_tunnel_proxy_core(chosen: ProxyCore, profile: Option<&ProxyProfile>, global: ProxyCore) -> ProxyCore {
    if chosen != ProxyCore::Auto {
        chosen
    } else if profile.is_some_and(|p| not_blank_opt(&p.raw_xray_config) || p.requires_xray()) {
        ProxyCore::Xray
    } else if global != ProxyCore::Auto {
        global
    } else {
        ProxyCore::Xray
    }
}

// ---------------- OpenFlux ----------------

pub const OF_YANDEX: &str = "yandex";
pub const OF_VYANDEX: &str = "vyandex";
pub const OF_MAX: &str = "oneme";
pub const OF_MAILRU: &str = "mailru";
pub const OF_CUPS: &str = "cupsonline";
pub const OF_TELEMOST: &str = "telemost";
pub const OF_AVITO: &str = "avito";
pub const OF_DIRECT: &str = "direct";
pub const OF_TRANSPORTS: [&str; 7] = [OF_YANDEX, OF_VYANDEX, OF_MAILRU, OF_CUPS, OF_AVITO, OF_TELEMOST, OF_MAX];
pub const OF_SESSION_CARRIERS: [&str; 6] = [OF_DIRECT, OF_YANDEX, OF_VYANDEX, OF_MAILRU, OF_CUPS, OF_AVITO];
pub const OF_DEFAULT_DNS: &str = "1.1.1.1:53";
pub const OF_DEFAULT_PRIORITY: i32 = 50;

static OF_ROOM_LIST: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_-]{16,}$").unwrap());

fn default_of_transport() -> String {
    OF_YANDEX.into()
}
fn default_of_dns() -> String {
    OF_DEFAULT_DNS.into()
}
fn default_of_priority() -> i32 {
    OF_DEFAULT_PRIORITY
}

fn starts_http(s: &str) -> bool {
    crate::share_link::starts_ci(s, "http")
}

/// OpenFlux carriers of an OpenFlux location.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct OpenFluxConfig {
    #[serde(default = "default_of_transport")]
    pub transport: String,
    #[serde(rename = "doc_url")]
    pub doc_url: String,
    #[serde(rename = "max_token")]
    pub max_token: String,
    #[serde(rename = "max_uid")]
    pub max_uid: String,
    #[serde(rename = "dns", default = "default_of_dns")]
    pub dns_server: String,
    pub debug: bool,
    #[serde(rename = "proxy_link")]
    pub proxy_link: String,
    #[serde(rename = "proxy_core")]
    pub proxy_core: ProxyCore,
    /// Exit node's AES-256-GCM secret; blank = unencrypted classic.
    pub secret: String,
    /// Explicit KDF context.
    pub context: String,
    /// Session-only negotiation, no classic fallback.
    pub negotiate: bool,
    #[serde(rename = "avito_cookies")]
    pub avito_cookies: String,
    /// Multi-transport session members (empty = single carrier).
    pub carriers: Vec<OpenFluxCarrier>,
}

impl Default for OpenFluxConfig {
    fn default() -> Self {
        Self {
            transport: default_of_transport(),
            doc_url: String::new(),
            max_token: String::new(),
            max_uid: String::new(),
            dns_server: default_of_dns(),
            debug: false,
            proxy_link: String::new(),
            proxy_core: ProxyCore::Auto,
            secret: String::new(),
            context: String::new(),
            negotiate: false,
            avito_cookies: String::new(),
            carriers: Vec::new(),
        }
    }
}

impl OpenFluxConfig {
    pub fn uses_max(&self) -> bool {
        self.transport == OF_MAX && !self.is_session()
    }
    pub fn is_session(&self) -> bool {
        !self.carriers.is_empty()
    }

    /// Carrier part of the core's command line.
    pub fn carrier_args(&self) -> Vec<String> {
        let mut out = Vec::new();
        if self.is_session() {
            out.push("--transports".into());
            out.push(self.carriers.iter().map(|c| format!("{}:{}", c.kind, c.priority)).collect::<Vec<_>>().join(","));
            for c in &self.carriers {
                if c.kind == OF_DIRECT {
                    out.extend(["--direct-dial".to_string(), c.dial.clone()]);
                } else {
                    out.extend([format!("--{}-url", c.kind), c.url.clone()]);
                }
            }
        } else {
            out.extend(["--transport".to_string(), self.transport.clone()]);
            if self.uses_max() {
                out.extend(["--maxUid".to_string(), self.max_uid.clone()]);
            } else {
                out.extend(["--url".to_string(), self.doc_url.clone()]);
            }
        }
        out
    }

    /// Multi-transport on (the current carrier becomes the first member) or off (back to one).
    pub fn with_session(&self, on: bool) -> Self {
        if on && !self.is_session() {
            let kind = if OF_SESSION_CARRIERS.contains(&self.transport.as_str()) { self.transport.clone() } else { OF_YANDEX.into() };
            Self {
                carriers: vec![OpenFluxCarrier { kind, url: self.doc_url.clone(), priority: 100, dial: String::new() }],
                ..self.clone()
            }
        } else if !on && self.is_session() {
            // maxByOrNull: the first of the highest priorities.
            let mut best: Option<&OpenFluxCarrier> = None;
            for c in self.carriers.iter().filter(|c| c.kind != OF_DIRECT) {
                if best.is_none_or(|b| c.priority > b.priority) {
                    best = Some(c);
                }
            }
            match best {
                Some(c) => Self { transport: c.kind.clone(), doc_url: c.url.clone(), carriers: Vec::new(), ..self.clone() },
                None => Self { carriers: Vec::new(), ..self.clone() },
            }
        } else {
            self.clone()
        }
    }

    pub fn uses_carrier(&self, kind: &str) -> bool {
        if self.is_session() {
            self.carriers.iter().any(|c| c.kind == kind)
        } else {
            self.transport == kind
        }
    }

    /// Encryption flags (the key goes through a file, never in argv).
    pub fn encryption_args(&self, key_file: &str) -> Vec<String> {
        if !not_blank(&self.secret) {
            return Vec::new();
        }
        let mut out = vec!["--encryption-key-file".to_string(), key_file.to_string()];
        if not_blank(&self.context) {
            out.extend(["--session-context".to_string(), self.context.clone()]);
        }
        if self.negotiate {
            out.push("--negotiate".into());
        }
        out
    }

    pub fn has_proxy(&self) -> bool {
        not_blank(&self.proxy_link)
    }

    pub fn resolved_proxy_core(&self, profile: Option<&ProxyProfile>, global: ProxyCore) -> ProxyCore {
        over_tunnel_proxy_core(self.proxy_core, profile, global)
    }

    pub fn is_complete(&self) -> bool {
        if self.uses_carrier(OF_AVITO) && !not_blank(&self.avito_cookies) {
            return false;
        }
        if self.is_session() {
            return not_blank(&self.secret) && self.carriers.iter().all(OpenFluxCarrier::is_complete);
        }
        let url = self.doc_url.trim();
        match self.transport.as_str() {
            OF_MAX => not_blank(&self.max_token) && self.max_uid.trim().parse::<i64>().is_ok(),
            OF_CUPS | OF_AVITO => starts_http(url) || OF_ROOM_LIST.is_match(url),
            _ => starts_http(url),
        }
    }

    pub fn normalized(&self) -> Self {
        let mut seen = Vec::new();
        let carriers = self
            .carriers
            .iter()
            .map(OpenFluxCarrier::normalized)
            .filter(|c| OF_SESSION_CARRIERS.contains(&c.kind.as_str()))
            .filter(|c| {
                let fresh = !seen.contains(&c.kind);
                seen.push(c.kind.clone());
                fresh
            })
            .collect();
        Self {
            transport: if OF_TRANSPORTS.contains(&self.transport.as_str()) { self.transport.clone() } else { OF_YANDEX.into() },
            doc_url: self.doc_url.trim().into(),
            max_token: self.max_token.trim().into(),
            max_uid: self.max_uid.trim().into(),
            dns_server: self.dns_server.trim().into(),
            proxy_link: self.proxy_link.trim().into(),
            avito_cookies: self.avito_cookies.trim().into(),
            carriers,
            secret: self.secret.trim().into(),
            context: self.context.trim().into(),
            negotiate: self.negotiate && not_blank(&self.secret),
            ..self.clone()
        }
    }

    /// One-line summary for the location list.
    pub fn summary(&self) -> String {
        if self.is_session() {
            let mut cs = self.carriers.clone();
            cs.sort_by(|a, b| b.priority.cmp(&a.priority));
            return format!("Мультитранспорт · {}", cs.iter().map(|c| carrier_label(&c.kind)).collect::<Vec<_>>().join(" + "));
        }
        match self.transport.as_str() {
            OF_MAX => format!("MAX · звонок {}", self.max_uid),
            OF_VYANDEX => "Яндекс Документы (новый редактор)".into(),
            OF_MAILRU => "Mail.ru Документы".into(),
            OF_CUPS => "cups.online".into(),
            OF_AVITO => "Avito Code".into(),
            OF_TELEMOST => "Яндекс Телемост".into(),
            _ => "Яндекс Документы".into(),
        }
    }
}

pub fn carrier_label(kind: &str) -> &'static str {
    match kind {
        OF_DIRECT => "Напрямую",
        OF_VYANDEX => "Яндекс (новый редактор)",
        OF_MAILRU => "Mail.ru",
        OF_CUPS => "cups.online",
        OF_AVITO => "Avito Code",
        OF_MAX => "MAX",
        _ => "Яндекс Документы",
    }
}

/// One member of a multi-transport OpenFlux session.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct OpenFluxCarrier {
    #[serde(rename = "type", default = "default_of_transport")]
    pub kind: String,
    /// Document / room link (or a room list); unused by `direct`.
    pub url: String,
    #[serde(default = "default_of_priority")]
    pub priority: i32,
    /// `host:port` of the exit's direct listener (`direct` only).
    pub dial: String,
}

impl Default for OpenFluxCarrier {
    fn default() -> Self {
        Self { kind: default_of_transport(), url: String::new(), priority: OF_DEFAULT_PRIORITY, dial: String::new() }
    }
}

impl OpenFluxCarrier {
    pub fn is_complete(&self) -> bool {
        match self.kind.as_str() {
            OF_DIRECT => {
                let port_ok = after_last_colon(&self.dial).parse::<i32>().is_ok_and(|p| (1..=65535).contains(&p));
                let host = self.dial.rfind(':').map_or(self.dial.as_str(), |i| &self.dial[..i]);
                port_ok && not_blank(host)
            }
            OF_CUPS | OF_AVITO => starts_http(&self.url) || self.url.chars().count() >= 16,
            _ => starts_http(&self.url),
        }
    }
    pub fn normalized(&self) -> Self {
        Self {
            url: self.url.trim().into(),
            dial: self.dial.trim().into(),
            priority: self.priority.clamp(1, 1000),
            ..self.clone()
        }
    }
}

// ---------------- snolc ----------------

pub const SNOLC_DEFAULT_PORT: i32 = 443;
static HEX_KEY: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[0-9a-fA-F]{64}$").unwrap());

fn default_snolc_port() -> i32 {
    SNOLC_DEFAULT_PORT
}

/// snolc (Noise NK over TCP) node of a Snolc location.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SnolcConfig {
    pub host: String,
    #[serde(default = "default_snolc_port")]
    pub port: i32,
    /// Node's Noise static public key (64 hex chars).
    #[serde(rename = "key")]
    pub public_key: String,
    pub debug: bool,
    #[serde(rename = "proxy_link")]
    pub proxy_link: String,
    #[serde(rename = "proxy_core")]
    pub proxy_core: ProxyCore,
}

impl Default for SnolcConfig {
    fn default() -> Self {
        Self {
            host: String::new(),
            port: SNOLC_DEFAULT_PORT,
            public_key: String::new(),
            debug: false,
            proxy_link: String::new(),
            proxy_core: ProxyCore::Auto,
        }
    }
}

impl SnolcConfig {
    pub fn has_proxy(&self) -> bool {
        not_blank(&self.proxy_link)
    }
    pub fn resolved_proxy_core(&self, profile: Option<&ProxyProfile>, global: ProxyCore) -> ProxyCore {
        over_tunnel_proxy_core(self.proxy_core, profile, global)
    }
    pub fn is_complete(&self) -> bool {
        not_blank(&self.host) && (1..=65535).contains(&self.port) && HEX_KEY.is_match(&self.public_key)
    }
    pub fn normalized(&self) -> Self {
        Self {
            host: self.host.trim().into(),
            public_key: self.public_key.trim().to_lowercase(),
            proxy_link: self.proxy_link.trim().into(),
            ..self.clone()
        }
    }
    pub fn summary(&self) -> String {
        format!("snolc · {}:{}", self.host, self.port)
    }
    /// `snolc://host:port?key=<hex>#name`.
    pub fn to_uri(&self, name: &str) -> String {
        let h = if self.host.contains(':') { format!("[{}]", self.host) } else { self.host.clone() };
        let frag = if not_blank(name) { format!("#{}", name.replace(' ', "%20")) } else { String::new() };
        format!("snolc://{h}:{}?key={}{frag}", self.port, self.public_key)
    }
    /// Parses `snolc://host:port?key=<hex>#name` → (config, name); None unless complete.
    pub fn parse_uri(text: &str) -> Option<(Self, String)> {
        let t = text.trim();
        if !crate::share_link::starts_ci(t, "snolc://") {
            return None;
        }
        let rest = &t[8..];
        let name = percent_decode(after(rest, '#'));
        let main = before(rest, '#');
        let host_port = before(main, '?').trim_end_matches('/');
        let key = after(main, '?').split('&').find_map(|kv| kv.strip_prefix("key=")).unwrap_or("").to_string();
        let (host, port) = if let Some(r) = host_port.strip_prefix('[') {
            let host = before(r, ']').to_string();
            let port = host_port.split_once("]:").map(|(_, p)| p).unwrap_or("").parse().unwrap_or(SNOLC_DEFAULT_PORT);
            (host, port)
        } else {
            let host = host_port.rfind(':').map_or(host_port, |i| &host_port[..i]).to_string();
            (host, after_last_colon(host_port).parse().unwrap_or(SNOLC_DEFAULT_PORT))
        };
        let cfg = Self { host, port, public_key: key, ..Default::default() }.normalized();
        cfg.is_complete().then_some((cfg, name))
    }
}

/// One additional olcRTC room for multi-room aggregation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct ExtraRoom {
    pub provider: String,
    pub transport: String,
    pub room: String,
    pub key: String,
}
