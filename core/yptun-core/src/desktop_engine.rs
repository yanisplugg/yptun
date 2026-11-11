//! Desktop engine controller — a straight port of `DesktopEngineController.kt`
//! (jvmMain, `vpn/desktop`).
//!
//! Starts the engine(s) of one location so that, on return, the local SOCKS5 endpoint accepts
//! connections: olcRTC (single / multi-room), sing-box or Xray (WARP, cascade, olcRTC chain,
//! sing-box front for the in-core TUN), OpenFlux, snolc, MasterDNS, VK-TURN (freeturn / qWDTT /
//! csqtt), plus the local AmneziaWG and Trust Tunnel SOCKS helpers.
//!
//! Native calls go through [`EngineCore`] (implemented for [`YpTunCore`]); everything else that
//! touches the OS — settings, sockets, DNS, files, sidecar processes, the browser — goes through
//! [`EngineHost`]. The Kotlin `suspend` waits are blocking loops on [`EngineHost::sleep_ms`].
//! Errors are the same user-facing messages Kotlin throws.

use std::collections::BTreeMap;
use std::net::IpAddr;
use std::path::{Path, PathBuf};

use crate::location::{normalize_provider, LocationConfig, PROVIDER_TELEMOST};
use crate::model::{
    EngineType, ProxyCore, ProxyProfile, TYPE_AMNEZIAWG, TYPE_HYSTERIA2, TYPE_OPENVPN, TYPE_SHADOWSOCKS,
    TYPE_TROJAN, TYPE_TRUSTTUNNEL, TYPE_VLESS, TYPE_VMESS,
};
use crate::room_pool::RoomSpec;
use crate::routing::{asn, RoutingProfile, RoutingProfilesState, RoutingRules, SingBoxRule, TrafficSettings};
use crate::settings::AppBehaviorSettings;
use crate::share_link;
use crate::singbox::config::{self as singbox_config, SingBoxOptions, WarpRelay, SPLIT_TUNNEL_ALL};
use crate::transports::{
    OpenFluxConfig, SnolcConfig, VkTurnConfig, WarpMode, WarpProtocol, MASTERDNS_ENCRYPTION_LABELS,
    OUTBOUND_AMNEZIAWG, OUTBOUND_PROXY, OUTBOUND_WIREGUARD,
};
use crate::vkturn::{clamp_vkturn_mtu, VKTURN_MAX_WG_MTU};
use crate::warp;
use crate::xray::config::{self as xray_config, RawOptions, XrayOptions};
use crate::yptun_core::{CoreResult, MasterDnsStart, YpTunCore};

// ---------------- constants ----------------

pub const MOBILE_READY_TIMEOUT_MS: u64 = 25_000;
pub const VKTURN_RELAY_READY_TIMEOUT_MS: u64 = 20_000;
pub const PORT_POLL_INTERVAL_MS: u64 = 30;
pub const PORT_RELEASE_TIMEOUT_MS: u64 = 5_000;
/// Connect timeout of the loopback port probe ([`EngineHost::is_local_port_open`]).
pub const PORT_PROBE_TIMEOUT_MS: u64 = 250;
pub const MAX_SINGBOX_LOG_BYTES: u64 = 32 * 1024 * 1024;
pub const XRAY_SUPPORTED_TYPES: [&str; 4] = [TYPE_VLESS, TYPE_VMESS, TYPE_TROJAN, TYPE_SHADOWSOCKS];

const LOOPBACK: &str = "127.0.0.1";

/// olcRTC's local SOCKS port when chaining; sing-box dials its outbound through it.
pub fn chain_olcrtc_port(socks_port: i32) -> i32 {
    socks_port + 1
}

/// AmneziaWG's local SOCKS port (awgproxy).
pub fn awg_local_port(socks_port: i32) -> i32 {
    socks_port + 2
}

/// Server-side WARP relay (loopback UDP → WARP endpoint through the main proxy).
pub fn warp_relay_port(socks_port: i32) -> i32 {
    socks_port + 3
}

/// Trust Tunnel's local SOCKS port (matches Android's offset).
pub fn trust_tunnel_local_port(socks_port: i32) -> i32 {
    socks_port + 5
}

/// Xray's port when sing-box fronts it with the in-core TUN.
pub fn sing_box_front_port(socks_port: i32) -> i32 {
    socks_port + 6
}

// ---------------- pure helpers ----------------

fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

fn has_text(v: &Option<String>) -> bool {
    v.as_deref().is_some_and(|s| !is_blank(s))
}

fn or_blank(v: &str, fallback: &str) -> String {
    if is_blank(v) {
        fallback.to_string()
    } else {
        v.to_string()
    }
}

pub fn is_xray_typed(kind: &str) -> bool {
    XRAY_SUPPORTED_TYPES.contains(&kind)
}

/// Kotlin: three dots and only digits/dots, or any ':' (IPv6).
pub fn looks_like_ip(host: &str) -> bool {
    (host.chars().filter(|c| *c == '.').count() == 3 && host.chars().all(|c| c.is_ascii_digit() || c == '.'))
        || host.contains(':')
}

fn socks_raw_outbound(port: i32, credentials: &str) -> String {
    let mut s = String::from("{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":");
    s.push_str(&port.to_string());
    s.push_str(",\"version\":\"5\"");
    s.push_str(credentials);
    s.push('}');
    s
}

/// A sing-box `socks` outbound to a local SOCKS5 on 127.0.0.1:[port].
pub fn local_socks_profile(tag: &str, port: i32) -> ProxyProfile {
    ProxyProfile {
        tag: tag.to_string(),
        kind: "socks".into(),
        server: LOOPBACK.into(),
        server_port: port,
        raw_outbound: Some(socks_raw_outbound(port, "")),
        ..Default::default()
    }
}

/// The outbound sing-box uses to front Xray (optionally with the SOCKS credentials).
pub fn front_profile(xray_port: i32, socks_username: &str, socks_password: &str) -> ProxyProfile {
    let credentials = if is_blank(socks_username) {
        String::new()
    } else {
        let mut c = String::from(",\"username\":\"");
        c.push_str(socks_username);
        c.push_str("\",\"password\":\"");
        c.push_str(socks_password);
        c.push('"');
        c
    };
    ProxyProfile {
        tag: "xray".into(),
        kind: "socks".into(),
        server: LOOPBACK.into(),
        server_port: xray_port,
        raw_outbound: Some(socks_raw_outbound(xray_port, &credentials)),
        ..Default::default()
    }
}

/// WireGuard outbound from the INI the wdtt-server hands back (GETCONF); MTU capped at 1200.
pub fn build_wdtt_wg_profile(wg_conf: &str, listen_port: i32) -> ProxyProfile {
    let mut private_key = String::new();
    let mut public_key = String::new();
    let mut address = String::new();
    let mut mtu: i32 = 0;
    for raw in wg_conf.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('[') || line.starts_with('#') {
            continue;
        }
        let eq = match line.find('=') {
            Some(i) if i > 0 => i,
            _ => continue,
        };
        let k = line[..eq].trim().to_lowercase();
        let v = line[eq + 1..].trim();
        match k.as_str() {
            "privatekey" => private_key = v.to_string(),
            "publickey" => public_key = v.to_string(),
            "address" => {
                if address.is_empty() {
                    address = v.split(',').next().unwrap_or("").trim().to_string();
                }
            }
            "mtu" => mtu = v.parse().unwrap_or(0),
            _ => {}
        }
    }
    let local_addr = if address.is_empty() { String::new() } else { format!("\"{address}\"") };
    let eff_mtu = (if mtu > 0 { mtu } else { 1200 }).min(1200);
    let mut json = String::from("{");
    json.push_str("\"type\":\"wireguard\",");
    json.push_str("\"server\":\"127.0.0.1\",");
    json.push_str(&format!("\"server_port\":{listen_port},"));
    json.push_str(&format!("\"local_address\":[{local_addr}],"));
    json.push_str(&format!("\"private_key\":\"{private_key}\","));
    json.push_str(&format!("\"peer_public_key\":\"{public_key}\","));
    json.push_str(&format!("\"mtu\":{eff_mtu}"));
    json.push('}');
    ProxyProfile {
        tag: "WDTT".into(),
        kind: "wireguard".into(),
        server: LOOPBACK.into(),
        server_port: listen_port,
        raw_outbound: Some(json),
        ..Default::default()
    }
}

/// VK-TURN exit type: WDTT Raw and csqtt serve an AmneziaWG-shaped local SOCKS exit.
pub fn vk_outbound_type(vk: Option<&VkTurnConfig>) -> String {
    let Some(vk) = vk else {
        return OUTBOUND_WIREGUARD.to_string();
    };
    let wdtt_raw = vk.uses_wdtt() && vk.wdtt_plus.raw_mode;
    if wdtt_raw || vk.uses_csqtt() {
        OUTBOUND_AMNEZIAWG.to_string()
    } else {
        or_blank(&vk.outbound, OUTBOUND_WIREGUARD)
    }
}

/// Whether the VK-TURN exit has what it needs (WDTT gets its config from the server; csqtt needs a peer).
pub fn vk_outbound_configured(vk: Option<&VkTurnConfig>, outbound_type: &str, profile: Option<&ProxyProfile>) -> bool {
    if vk.is_some_and(|v| v.uses_wdtt()) {
        true
    } else if vk.is_some_and(|v| v.uses_csqtt()) {
        vk.is_some_and(|v| !is_blank(&v.csqtt_peer))
    } else if outbound_type == OUTBOUND_AMNEZIAWG {
        profile.is_some_and(|p| !is_blank(&p.awg_config))
    } else if outbound_type == OUTBOUND_PROXY {
        profile.is_some_and(|p| !is_blank(&p.server) && (1..=65535).contains(&p.server_port))
    } else {
        profile.is_some_and(|p| has_text(&p.raw_outbound))
    }
}

/// Why WARP cannot wrap this location (None = it can).
pub fn warp_skip_reason(chained: bool, profile: &ProxyProfile) -> Option<String> {
    let is_raw_xray = has_text(&profile.raw_xray_config);
    if chained {
        Some("olcRTC chain mode already owns the base tunnel".into())
    } else if !is_raw_xray && !is_xray_typed(&profile.kind) {
        Some(format!("WARP supports VLESS/VMess/Trojan/Shadowsocks, this is {}", profile.kind))
    } else {
        None
    }
}

/// The Telemost cookies to hand to olcRTC (None = clear them).
pub fn telemost_cookies_to_apply(behavior: &AppBehaviorSettings, bypass_provider: &str) -> Option<String> {
    let use_them = behavior.telemost_cookies_enabled
        && !is_blank(&behavior.telemost_cookies)
        && normalize_provider(bypass_provider) == PROVIDER_TELEMOST;
    if use_them {
        Some(behavior.telemost_cookies.trim().to_string())
    } else {
        None
    }
}

/// Kotlin `mainIps intersect secondIps` (order of the first set, no duplicates).
pub fn shared_addresses(main: &[String], second: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for ip in main {
        if second.contains(ip) && !out.contains(ip) {
            out.push(ip.clone());
        }
    }
    out
}

pub fn raw_needs_geo(raw_xray_config: &str) -> bool {
    raw_xray_config.contains("geosite:") || raw_xray_config.contains("geoip:")
}

fn workers_label(workers: i32) -> String {
    if workers > 0 {
        workers.to_string()
    } else {
        "auto".into()
    }
}

pub fn masterdns_encryption_label(method: i32) -> &'static str {
    usize::try_from(method).ok().and_then(|i| MASTERDNS_ENCRYPTION_LABELS.get(i)).copied().unwrap_or("?")
}

fn error_suffix(err: &str) -> String {
    if is_blank(err) {
        String::new()
    } else {
        format!(" — {err}")
    }
}

// ---------------- I/O seams ----------------

/// The native core calls the controller makes (the YpTunCore singleton in Kotlin).
#[allow(clippy::too_many_arguments)]
pub trait EngineCore {
    fn rtc_set_socks_listen_host(&self, host: &str) -> CoreResult<()>;
    fn rtc_set_telemost_cookies(&self, cookies: &str) -> CoreResult<()>;
    fn rtc_apply_transport_options(&self, config: &LocationConfig) -> CoreResult<()>;
    fn rtc_start(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        socks_user: &str,
        socks_pass: &str,
    ) -> CoreResult<()>;
    fn rtc_wait_ready(&self, timeout_ms: i32) -> CoreResult<()>;
    fn rtc_running(&self) -> bool;
    fn rtc_rooms_running(&self) -> i32;
    fn sb_start(&self, config_json: &str) -> CoreResult<()>;
    fn sb_running(&self) -> bool;
    fn xray_set_asset_path(&self, dir: &str) -> CoreResult<()>;
    fn xray_start(&self, config_json: &str) -> CoreResult<()>;
    fn xray_running(&self) -> bool;
    fn awg_start(&self, ini_config: &str, listen_addr: &str) -> CoreResult<()>;
    fn awg_stop(&self);
    fn ft_start(&self, uri: &str, listen_addr: &str, vk_link: &str, n_streams: i32) -> CoreResult<()>;
    fn ft_running(&self) -> bool;
    fn ft_connected_streams(&self) -> i32;
    fn ft_captcha_url(&self) -> String;
    fn ft_captcha_active(&self) -> bool;
    fn wdtt_start(&self, options_json: &str) -> CoreResult<()>;
    fn wdtt_last_error(&self) -> String;
    fn wdtt_wait_config(&self, timeout_ms: i32) -> CoreResult<Option<String>>;
    fn wdtt_running(&self) -> bool;
    fn master_dns_start(&self, args: &MasterDnsStart) -> CoreResult<()>;
    fn master_dns_stop(&self);
    fn master_dns_running(&self) -> bool;
    fn stop_all(&self);
}

impl EngineCore for YpTunCore {
    fn rtc_set_socks_listen_host(&self, host: &str) -> CoreResult<()> {
        YpTunCore::rtc_set_socks_listen_host(self, host)
    }
    fn rtc_set_telemost_cookies(&self, cookies: &str) -> CoreResult<()> {
        YpTunCore::rtc_set_telemost_cookies(self, cookies)
    }
    fn rtc_apply_transport_options(&self, config: &LocationConfig) -> CoreResult<()> {
        YpTunCore::rtc_apply_transport_options(self, config)
    }
    fn rtc_start(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        socks_user: &str,
        socks_pass: &str,
    ) -> CoreResult<()> {
        YpTunCore::rtc_start(self, carrier, transport, room_id, client_id, key_hex, socks_port, socks_user, socks_pass)
    }
    fn rtc_wait_ready(&self, timeout_ms: i32) -> CoreResult<()> {
        YpTunCore::rtc_wait_ready(self, timeout_ms)
    }
    fn rtc_running(&self) -> bool {
        YpTunCore::rtc_running(self)
    }
    fn rtc_rooms_running(&self) -> i32 {
        YpTunCore::rtc_rooms_running(self)
    }
    fn sb_start(&self, config_json: &str) -> CoreResult<()> {
        YpTunCore::sb_start(self, config_json)
    }
    fn sb_running(&self) -> bool {
        YpTunCore::sb_running(self)
    }
    fn xray_set_asset_path(&self, dir: &str) -> CoreResult<()> {
        YpTunCore::xray_set_asset_path(self, dir)
    }
    fn xray_start(&self, config_json: &str) -> CoreResult<()> {
        YpTunCore::xray_start(self, config_json)
    }
    fn xray_running(&self) -> bool {
        YpTunCore::xray_running(self)
    }
    fn awg_start(&self, ini_config: &str, listen_addr: &str) -> CoreResult<()> {
        YpTunCore::awg_start(self, ini_config, listen_addr)
    }
    fn awg_stop(&self) {
        YpTunCore::awg_stop(self)
    }
    fn ft_start(&self, uri: &str, listen_addr: &str, vk_link: &str, n_streams: i32) -> CoreResult<()> {
        YpTunCore::ft_start(self, uri, listen_addr, vk_link, n_streams)
    }
    fn ft_running(&self) -> bool {
        YpTunCore::ft_running(self)
    }
    fn ft_connected_streams(&self) -> i32 {
        YpTunCore::ft_connected_streams(self)
    }
    fn ft_captcha_url(&self) -> String {
        YpTunCore::ft_captcha_url(self)
    }
    fn ft_captcha_active(&self) -> bool {
        YpTunCore::ft_captcha_active(self)
    }
    fn wdtt_start(&self, options_json: &str) -> CoreResult<()> {
        YpTunCore::wdtt_start(self, options_json)
    }
    fn wdtt_last_error(&self) -> String {
        YpTunCore::wdtt_last_error(self)
    }
    fn wdtt_wait_config(&self, timeout_ms: i32) -> CoreResult<Option<String>> {
        YpTunCore::wdtt_wait_config(self, timeout_ms)
    }
    fn wdtt_running(&self) -> bool {
        YpTunCore::wdtt_running(self)
    }
    fn master_dns_start(&self, args: &MasterDnsStart) -> CoreResult<()> {
        YpTunCore::master_dns_start(self, args)
    }
    fn master_dns_stop(&self) {
        YpTunCore::master_dns_stop(self)
    }
    fn master_dns_running(&self) -> bool {
        YpTunCore::master_dns_running(self)
    }
    fn stop_all(&self) {
        YpTunCore::stop_all(self)
    }
}

/// csqtt bridge "tunnel up" info (CsqttBridge.awaitReady).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CsqttUp {
    pub ip: String,
    pub dns: Vec<String>,
    pub mtu: i32,
}

/// Everything outside the native core: settings, sockets, files, sidecar processes, UI.
#[allow(clippy::too_many_arguments)]
pub trait EngineHost {
    fn log(&self, line: &str);
    /// DesktopPaths.appDataDir().
    fn app_data_dir(&self) -> PathBuf;
    fn load_traffic(&self) -> TrafficSettings;
    fn load_routing(&self) -> RoutingRules;
    fn load_routing_profiles(&self) -> RoutingProfilesState;
    fn load_app_behavior(&self) -> AppBehaviorSettings;

    fn now_ms(&self) -> u64;
    fn sleep_ms(&self, ms: u64);
    /// TCP connect to 127.0.0.1:[port] within [`PORT_PROBE_TIMEOUT_MS`].
    fn is_local_port_open(&self, port: i32) -> bool;
    /// InetAddress.getAllByName (empty on failure).
    fn resolve_all(&self, host: &str) -> Vec<IpAddr>;
    fn file_size(&self, path: &Path) -> Option<u64>;
    fn delete_file(&self, path: &Path);
    /// File names of the running processes (for package-regex rules).
    fn running_process_names(&self) -> Vec<String>;

    /// YptunInboundCodec.parse(link)?.let { it.proxy ?: it.proxy2 } — the fallback after share links.
    fn parse_inbound_link(&self, link: &str) -> Option<ProxyProfile>;
    /// JvmGeoAssets.ensureAssets.
    fn ensure_geo_assets(&self, geoip_url: &str, geosite_url: &str) -> bool;
    /// JvmGeoAssets.assetDir().
    fn geo_asset_dir(&self) -> String;
    /// JvmAsnResolver.ensure (empty map on failure).
    fn resolve_asns(&self, asns: &[String]) -> BTreeMap<String, Vec<String>>;

    /// TelegramProxyStore.loadConfig / saveConfig, WarpConfigGenerator.generate.
    fn warp_load_config(&self) -> Option<String>;
    fn warp_save_config(&self, ini: &str);
    fn warp_generate(&self, official_first: bool) -> Result<String, String>;

    /// DesktopRoomPool.
    fn room_pool_start(&self, specs: &[RoomSpec], host: &str, port: i32, user: &str, pass: &str) -> bool;
    fn room_pool_stop(&self);

    /// DesktopTrustTunnel.
    fn trust_tunnel_start(&self, tt_config: &str, port: i32) -> Result<(), String>;
    fn trust_tunnel_await_ready(&self, port: i32) -> bool;
    fn trust_tunnel_stop(&self);

    /// DesktopOpenFlux.
    fn open_flux_start(&self, config: &OpenFluxConfig, host: &str, port: i32, user: &str, pass: &str) -> Result<(), String>;
    fn open_flux_running(&self) -> bool;
    fn open_flux_exit_description(&self) -> String;
    fn open_flux_stop(&self);

    /// DesktopSnolc.
    fn snolc_start(&self, config: &SnolcConfig, host: &str, port: i32, user: &str, pass: &str) -> Result<(), String>;
    fn snolc_running(&self) -> bool;
    fn snolc_exit_description(&self) -> String;
    fn snolc_stop(&self);

    /// DesktopNativeAssets.resolveCsqttBinaries() → (bridge exe, client exe).
    fn csqtt_binaries(&self) -> Result<(String, String), String>;
    /// CsqttBridge.
    fn csqtt_launch(&self, bridge_exe: &str, options_json: &str) -> Result<(), String>;
    fn csqtt_await_ready(&self, timeout_ms: u64) -> Option<CsqttUp>;
    /// CsqttBridge.noTunnelMessage(lastError()).
    fn csqtt_no_tunnel_message(&self) -> String;
    fn csqtt_running(&self) -> bool;
    fn csqtt_stop(&self);

    /// DesktopUriLauncher.openBrowserWindow.
    fn open_browser_window(&self, url: &str) -> bool;
    /// DesktopToast.show(strings.vkCaptchaTitle).
    fn show_captcha_toast(&self);
}

/// Desktop TUN mode: sing-box owns the TUN with per-process split tunneling.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TunRequest {
    pub split_mode: String,
    pub processes: Vec<String>,
    pub exclude_addresses: Vec<String>,
}

impl Default for TunRequest {
    fn default() -> Self {
        Self { split_mode: SPLIT_TUNNEL_ALL.to_string(), processes: vec![], exclude_addresses: vec![] }
    }
}

// ---------------- controller ----------------

pub struct DesktopEngineController<C: EngineCore, H: EngineHost> {
    core: C,
    host: H,
    active_proxy_core: ProxyCore,
    tun_request: Option<TunRequest>,
    tun_handled_in_core: bool,
    master_dns_proxy_active: bool,
    open_flux_proxy_active: bool,
    snolc_proxy_active: bool,
    sing_box_front_active: bool,
    room_pool_active: bool,
}

impl<C: EngineCore, H: EngineHost> DesktopEngineController<C, H> {
    pub fn new(core: C, host: H) -> Self {
        Self {
            core,
            host,
            active_proxy_core: ProxyCore::SingBox,
            tun_request: None,
            tun_handled_in_core: false,
            master_dns_proxy_active: false,
            open_flux_proxy_active: false,
            snolc_proxy_active: false,
            sing_box_front_active: false,
            room_pool_active: false,
        }
    }

    pub fn core(&self) -> &C {
        &self.core
    }

    pub fn host(&self) -> &H {
        &self.host
    }

    pub fn active_proxy_core(&self) -> ProxyCore {
        self.active_proxy_core
    }

    /// True when sing-box owns the TUN itself (per-process split); otherwise the caller runs tun2socks.
    pub fn tun_handled_in_core(&self) -> bool {
        self.tun_handled_in_core
    }

    pub fn sing_box_front_active(&self) -> bool {
        self.sing_box_front_active
    }

    fn log(&self, line: &str) {
        self.host.log(line)
    }

    fn sing_box_cache_path(&self) -> String {
        self.host.app_data_dir().join("singbox-cache.db").to_string_lossy().into_owned()
    }

    fn sing_box_log_path(&self) -> String {
        let file = self.host.app_data_dir().join("singbox.log");
        if self.host.file_size(&file).is_some_and(|s| s > MAX_SINGBOX_LOG_BYTES) {
            self.host.delete_file(&file);
        }
        file.to_string_lossy().into_owned()
    }

    fn tun_fields(&self) -> (String, Vec<String>, Vec<String>) {
        match &self.tun_request {
            Some(t) => (t.split_mode.clone(), t.processes.clone(), t.exclude_addresses.clone()),
            None => (SPLIT_TUNNEL_ALL.to_string(), vec![], vec![]),
        }
    }

    /// Starts the location's engine(s); on Ok the SOCKS5 on listen_host:listen_port accepts
    /// connections. `tun` = desktop TUN mode with per-process split (sing-box owns the TUN).
    #[allow(clippy::too_many_arguments)]
    pub fn start(
        &mut self,
        location: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        device_id: &str,
        tun: Option<TunRequest>,
    ) -> Result<(), String> {
        self.tun_request = tun;
        self.tun_handled_in_core = false;
        self.master_dns_proxy_active = false;
        self.open_flux_proxy_active = false;
        self.snolc_proxy_active = false;
        self.sing_box_front_active = false;
        self.room_pool_active = false;
        let config = location.normalized();
        match config.engine {
            EngineType::Stealth => {
                self.start_stealth(&config, listen_host, listen_port, socks_username, socks_password, device_id)?
            }
            EngineType::Standard | EngineType::Chain => {
                self.start_sing_box_or_xray(&config, listen_host, listen_port, socks_username, socks_password, device_id)?
            }
            EngineType::VkTurn => {
                self.start_vk_turn(&config, listen_host, listen_port, socks_username, socks_password, device_id)?
            }
            EngineType::MasterDns => {
                self.start_master_dns(&config, listen_host, listen_port, socks_username, socks_password)?
            }
            EngineType::OpenFlux => {
                self.start_open_flux(&config, listen_host, listen_port, socks_username, socks_password)?
            }
            EngineType::Snolc => self.start_snolc(&config, listen_host, listen_port, socks_username, socks_password)?,
        }
        if self.tun_request.is_some() && !self.tun_handled_in_core {
            self.log(&format!(
                "Per-process split tunneling unavailable (core is {:?}); falling back to tun2socks for all apps",
                self.active_proxy_core
            ));
        }
        Ok(())
    }

    pub fn stop_all(&mut self) {
        self.host.trust_tunnel_stop();
        self.host.open_flux_stop();
        self.host.snolc_stop();
        self.host.csqtt_stop();
        self.host.room_pool_stop();
        self.room_pool_active = false;
        self.core.stop_all();
        self.tun_handled_in_core = false;
        self.master_dns_proxy_active = false;
        self.open_flux_proxy_active = false;
        self.snolc_proxy_active = false;
        self.sing_box_front_active = false;
    }

    pub fn core_running(&self, engine: EngineType) -> bool {
        let proxy = || {
            if self.active_proxy_core == ProxyCore::Xray {
                self.core.xray_running() && (!self.sing_box_front_active || self.core.sb_running())
            } else {
                self.core.sb_running()
            }
        };
        let olcrtc = || {
            if self.room_pool_active {
                self.core.rtc_rooms_running() > 0
            } else {
                self.core.rtc_running()
            }
        };
        match engine {
            EngineType::Stealth => olcrtc(),
            EngineType::Standard => proxy(),
            EngineType::Chain => olcrtc() && proxy(),
            EngineType::VkTurn => {
                (self.core.ft_running() || self.core.wdtt_running() || self.host.csqtt_running()) && proxy()
            }
            EngineType::MasterDns => self.core.master_dns_running() && (!self.master_dns_proxy_active || proxy()),
            EngineType::OpenFlux => {
                self.host.open_flux_running()
                    && if self.open_flux_proxy_active {
                        proxy()
                    } else {
                        !self.sing_box_front_active || self.core.sb_running()
                    }
            }
            EngineType::Snolc => self.host.snolc_running() && (!self.snolc_proxy_active || proxy()),
        }
    }

    // ---------------- olcRTC ----------------

    fn start_stealth(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        device_id: &str,
    ) -> Result<(), String> {
        self.require_port_free(listen_port, &format!("SOCKS port {listen_port} is still in use"))?;
        self.core.rtc_set_socks_listen_host(listen_host)?;
        self.apply_telemost_cookies(config);
        self.core.rtc_apply_transport_options(config)?;
        self.start_olcrtc_socks(config, listen_host, listen_port, socks_username, socks_password, device_id)?;
        self.log(&format!("olcRTC ready on {listen_host}:{listen_port}"));
        Ok(())
    }

    /// Single room, or several rooms behind a round-robin balancer (falls back to one room).
    fn start_olcrtc_socks(
        &mut self,
        config: &LocationConfig,
        host: &str,
        port: i32,
        socks_username: &str,
        socks_password: &str,
        device_id: &str,
    ) -> Result<(), String> {
        if config.uses_multi_room() {
            let specs: Vec<RoomSpec> = config
                .multi_room_specs()
                .iter()
                .map(|it| RoomSpec {
                    carrier: or_blank(&it.provider, &config.bypass_provider),
                    transport: or_blank(&it.transport, &config.transport),
                    room: it.room.clone(),
                    client_id: device_id.to_string(),
                    key_hex: it.key.clone(),
                })
                .collect();
            self.log(&format!(
                "Starting olcRTC MULTI-ROOM: {} room(s) + round-robin balancer on {host}:{port}",
                specs.len()
            ));
            self.room_pool_active = true;
            if self.host.room_pool_start(&specs, host, port, socks_username, socks_password) {
                return Ok(());
            }
            self.log("Multi-room failed to start — falling back to single room");
            self.host.room_pool_stop();
            self.room_pool_active = false;
        }
        self.log(&format!(
            "Starting olcRTC provider={}, transport={}, room={}",
            config.bypass_provider, config.transport, config.id
        ));
        self.core.rtc_start(
            &config.bypass_provider,
            &config.transport,
            &config.id,
            device_id,
            &config.key,
            port,
            socks_username,
            socks_password,
        )?;
        self.core.rtc_wait_ready(MOBILE_READY_TIMEOUT_MS as i32)?;
        Ok(())
    }

    fn apply_telemost_cookies(&self, config: &LocationConfig) {
        let behavior = self.host.load_app_behavior();
        let cookies = telemost_cookies_to_apply(&behavior, &config.bypass_provider);
        let _ = self.core.rtc_set_telemost_cookies(cookies.as_deref().unwrap_or(""));
        if cookies.is_some() {
            self.log("Applied Telemost cookies");
        }
    }

    // ---------------- proxy helpers ----------------

    fn host_ips(&self, host: &str) -> Vec<String> {
        if is_blank(host) {
            return vec![];
        }
        self.host.resolve_all(host.trim()).iter().map(|a| a.to_string()).collect()
    }

    fn warn_if_cascade_hops_share_an_address(&self, main: &ProxyProfile, second: &ProxyProfile) {
        let shared = shared_addresses(&self.host_ips(&main.server), &self.host_ips(&second.server));
        if shared.is_empty() {
            return;
        }
        self.log(&format!(
            "WARNING: the cascade's two hops resolve to the SAME address ({}) — '{}' would have to dial '{}' on its own machine, which most servers refuse. If nothing loads, pick a 2nd proxy on a different server.",
            shared.join(", "),
            main.display_name(),
            second.display_name()
        ));
    }

    /// Pre-resolve the server on the host so the core dials an IP (SNI keeps the name).
    fn dial_by_server_ip(&self, profile: &ProxyProfile) -> ProxyProfile {
        let host = profile.server.trim();
        if host.is_empty() || host == LOOPBACK || looks_like_ip(host) {
            return profile.clone();
        }
        let ip = self.host.resolve_all(host).into_iter().find(|a| a.is_ipv4()).map(|a| a.to_string());
        let Some(ip) = ip else {
            self.log(&format!("Could not host-resolve {host}; letting the core resolve it"));
            return profile.clone();
        };
        let sni = or_blank(&profile.sni, host);
        self.log(&format!("Dialing proxy server by IP {ip} (SNI={sni})"));
        ProxyProfile { server: ip, sni, ..profile.clone() }
    }

    fn require_port_free(&self, port: i32, message: &str) -> Result<(), String> {
        let deadline = self.host.now_ms() + PORT_RELEASE_TIMEOUT_MS;
        while self.host.is_local_port_open(port) {
            if self.host.now_ms() >= deadline {
                return Err(message.to_string());
            }
            self.host.sleep_ms(150);
        }
        Ok(())
    }

    fn await_socks_port_open(&self, port: i32, timeout_ms: u64) -> bool {
        let deadline = self.host.now_ms() + timeout_ms;
        loop {
            if self.host.is_local_port_open(port) {
                return true;
            }
            if self.host.now_ms() >= deadline {
                return false;
            }
            self.host.sleep_ms(PORT_POLL_INTERVAL_MS);
        }
    }

    /// Parse a proxy link: share link first, then the YPtun inbound codec.
    fn parse_proxy_link(&self, link: &str) -> Option<ProxyProfile> {
        if is_blank(link) {
            return None;
        }
        let link = link.trim();
        share_link::parse(link).or_else(|| self.host.parse_inbound_link(link)).filter(|p| p.is_complete())
    }

    // ---------------- sing-box / Xray ----------------

    fn start_sing_box_or_xray(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        device_id: &str,
    ) -> Result<(), String> {
        let profile = match config.proxy.as_ref() {
            Some(p) if p.is_complete() => p.clone(),
            _ => return Err("No proxy configured".into()),
        };
        let chained = config.engine == EngineType::Chain;
        let chain_port = chain_olcrtc_port(listen_port);
        let configured_second: Option<ProxyProfile> = match config.proxy2.as_ref().filter(|s| s.is_complete()) {
            Some(second) if profile.is_complete() && profile.is_same_node_as(second) => {
                self.log("2nd (cascade) proxy is the same node as the main — a cascade into itself is impossible; ignoring it (exit via the main)");
                None
            }
            Some(second) => {
                self.warn_if_cascade_hops_share_an_address(&profile, second);
                Some(second.clone())
            }
            None => None,
        };

        let mut warp_awg_ini: Option<String> = None;
        let mut warp_chain_port: Option<i32> = None;
        let mut warp_relay: Option<WarpRelay> = None;
        let mut second_profile = configured_second;
        if config.warp_enabled {
            let server_side = config.warp_mode == WarpMode::Server;
            let awg = config.warp_protocol == WarpProtocol::Awg;
            self.log(&format!(
                "WARP: enabled - {}, protocol {}",
                if server_side { "server side (VLESS -> WARP)" } else { "client side (WARP -> VLESS)" },
                if awg { "AmneziaWG" } else { "WireGuard" }
            ));
            if !server_side && !awg {
                self.log("WARP: plain WireGuard on the client side goes to Cloudflare directly over UDP and many ISPs block it - if the handshake never completes, choose AmneziaWG");
            }
            if let Some(reason) = warp_skip_reason(chained, &profile) {
                self.log(&format!("WARNING: WARP skipped: {reason}"));
            } else {
                let mut ini = self.host.warp_load_config();
                if ini.as_deref().is_some_and(|i| !is_blank(i) && !i.to_lowercase().contains("privatekey")) {
                    ini = None;
                }
                if !has_text(&ini) {
                    self.log("WARP: generating Cloudflare WARP config...");
                    ini = match self.host.warp_generate(!awg) {
                        Ok(v) => Some(v),
                        Err(e) => {
                            self.log(&format!("WARP: generation failed: {e}"));
                            None
                        }
                    };
                    if let Some(v) = ini.as_deref() {
                        self.host.warp_save_config(v);
                    }
                }
                match ini {
                    None => self.log("WARNING: failed to obtain a WARP config, proceeding WITHOUT WARP"),
                    Some(ini) => {
                        let engine_ini = warp::engine_ini(&ini, awg, !server_side);
                        if server_side {
                            let (raw_host, port) = warp::endpoint_of(&ini);
                            let relay = WarpRelay {
                                listen_port: warp_relay_port(listen_port),
                                target_host: warp::pinned_host(&raw_host),
                                target_port: port,
                            };
                            warp_awg_ini =
                                Some(warp::with_endpoint(&engine_ini, &format!("127.0.0.1:{}", relay.listen_port)));
                            warp_relay = Some(relay);
                            second_profile = Some(local_socks_profile("WARP", awg_local_port(listen_port)));
                        } else {
                            warp_awg_ini = Some(engine_ini);
                            warp_chain_port = Some(awg_local_port(listen_port));
                        }
                    }
                }
            }
        }

        self.require_port_free(listen_port, &format!("SOCKS port {listen_port} is still in use"))?;
        if let (Some(ini), None) = (warp_awg_ini.as_deref(), warp_relay.as_ref()) {
            self.start_warp_awg(ini, listen_port)?;
        }
        if chained {
            self.apply_telemost_cookies(config);
            self.core.rtc_apply_transport_options(config)?;
            self.start_olcrtc_socks(config, LOOPBACK, chain_port, socks_username, socks_password, device_id)?;
            self.log(&format!("olcRTC chain ready on 127.0.0.1:{chain_port}"));
        }

        let is_awg = profile.kind == TYPE_AMNEZIAWG;
        let is_hy2 = profile.kind == TYPE_HYSTERIA2;
        let is_trust_tunnel = profile.kind == TYPE_TRUSTTUNNEL;
        let is_openvpn = profile.kind == TYPE_OPENVPN;
        let has_openvpn = is_openvpn || second_profile.as_ref().is_some_and(|p| p.kind == TYPE_OPENVPN);
        let effective = if is_awg {
            self.prepare_amnezia_wg_proxy(&profile, listen_port)?
        } else if is_trust_tunnel {
            self.prepare_trust_tunnel_proxy(&profile, listen_port)?
        } else if is_hy2 {
            profile.clone()
        } else {
            self.dial_by_server_ip(&profile)
        };
        let is_local_udp_tunnel = is_awg || is_hy2 || is_trust_tunnel || has_openvpn;

        let traffic = self.host.load_traffic();
        let routing = self.load_routing_expanding_asn();
        let profiles_state = self.host.load_routing_profiles();
        let routing_profile = self.resolve_profile_expanding_asn(&profiles_state, &config.routing_profile_id);
        match &routing_profile {
            None => self.log("Routing: NO profile applied — all traffic via proxy"),
            Some(p) => self.log(&format!("Routing: applying '{}'", p.display_name())),
        }

        let global_core = self.host.load_app_behavior().global_proxy_core;
        self.active_proxy_core =
            if is_local_udp_tunnel { ProxyCore::SingBox } else { config.resolved_core(global_core) };
        let profile_wants_xray = routing_profile.as_ref().is_some_and(|p| !p.dns_hosts.is_empty());
        if has_text(&effective.raw_xray_config) {
            if self.active_proxy_core != ProxyCore::Xray {
                self.log("Raw Xray config present → forcing Xray core");
            }
            self.active_proxy_core = ProxyCore::Xray;
        }
        if self.active_proxy_core == ProxyCore::SingBox
            && (traffic.block_ru_domains || profile_wants_xray)
            && !has_text(&effective.raw_outbound)
            && is_xray_typed(&effective.kind)
        {
            self.active_proxy_core = ProxyCore::Xray;
            self.log(if profile_wants_xray {
                "Switching to Xray core for routing profile (native domain:/geoip: matching)"
            } else {
                "Switching to Xray core for RU-domain blocklist"
            });
        }
        if self.active_proxy_core == ProxyCore::Xray
            && routing_profile.as_ref().is_some_and(|p| p.needs_geo_files())
            && !has_text(&effective.raw_xray_config)
            && !effective.requires_xray()
            && !has_text(&effective.raw_outbound)
            && self.ensure_geo_asset_path(routing_profile.as_ref()).is_empty()
        {
            self.active_proxy_core = ProxyCore::SingBox;
            self.log("Geo databases unavailable for Xray → using sing-box for routing");
        }
        if second_profile.as_ref().is_some_and(|s| s.requires_xray()) && self.active_proxy_core != ProxyCore::Xray {
            self.log("Second (cascade) proxy uses xhttp → forcing Xray core");
            self.active_proxy_core = ProxyCore::Xray;
        }
        let raw_present = has_text(&effective.raw_xray_config);
        match &second_profile {
            Some(second) if raw_present && !is_xray_typed(&second.kind) => self.log(&format!(
                "WARNING: the 2nd proxy is a '{}' CLIENT TUNNEL, not an Xray exit outbound — it cannot cascade over a custom Xray config and is ignored. Use vless/vmess/trojan/ss for that.",
                second.kind
            )),
            Some(second) if raw_present => self.log(&format!(
                "Cascade: exit via 2nd proxy '{}' over the custom Xray config",
                second.display_name()
            )),
            Some(second) => self.log(&format!(
                "Cascade: exit via 2nd proxy '{}' over main '{}'",
                second.display_name(),
                effective.display_name()
            )),
            None if config.proxy2.is_some() => self.log(
                "Cascade: a 2nd proxy IS set but its link is incomplete/unparsed — dropped, exit via the 1st proxy",
            ),
            None => {}
        }

        let requested_tun = self.tun_request.is_some();
        let (split_mode, split_processes, tun_exclude) = self.tun_fields();
        if self.active_proxy_core == ProxyCore::Xray {
            let front_xray = requested_tun;
            let xray_port = if front_xray { sing_box_front_port(listen_port) } else { listen_port };
            let xray_host = if front_xray { LOOPBACK.to_string() } else { listen_host.to_string() };
            let raw_xray = effective.raw_xray_config.clone().filter(|r| !is_blank(r));
            let asset_path: String;
            let json = if let Some(raw) = raw_xray.as_deref() {
                let needs_geo = raw_needs_geo(raw);
                asset_path = if needs_geo {
                    self.ensure_raw_config_geo_asset_path(routing_profile.as_ref())
                } else if routing_profile.is_some() {
                    self.ensure_geo_asset_path(routing_profile.as_ref())
                } else {
                    String::new()
                };
                let strip_geo = needs_geo && asset_path.is_empty();
                let mut msg = String::from("Starting Xray with custom config (embedded routing honored");
                if needs_geo && !asset_path.is_empty() {
                    msg.push_str(", geo db loaded for its geosite:/geoip:");
                }
                if strip_geo {
                    msg.push_str(", geo db unavailable -> geo selectors stripped");
                }
                if let Some(p) = &routing_profile {
                    msg.push_str(&format!(" + routing profile '{}'", p.display_name()));
                }
                msg.push(')');
                self.log(&msg);
                let options = RawOptions {
                    listen_host: xray_host.clone(),
                    socks_username: socks_username.to_string(),
                    socks_password: socks_password.to_string(),
                    routing_profile: self.xray_routing_profile(routing_profile.as_ref(), &asset_path),
                    fake_dns_enabled: traffic.fake_dns_enabled,
                    strip_geo_selectors: strip_geo,
                    force_ipv4: traffic.domain_strategy == "ipv4_only" || traffic.domain_strategy == "prefer_ipv4",
                    second_profile: second_profile.clone(),
                    warp_chain_port,
                    warp_exit_port: if warp_relay.is_some() { Some(awg_local_port(listen_port)) } else { None },
                    warp_relay: warp_relay.clone(),
                    ..RawOptions::new(xray_port)
                };
                xray_config::prepare_raw(raw, &options)
            } else {
                asset_path = self.ensure_geo_asset_path(routing_profile.as_ref());
                let mut xray_traffic = traffic.clone();
                if let Some(a) = &config.advanced {
                    xray_traffic.mux_enabled = a.mux_enabled;
                    xray_traffic.mux_protocol = a.mux_protocol.clone();
                    xray_traffic.mux_max_connections = a.mux_max_streams;
                    xray_traffic.fragment_enabled = a.tls_fragment;
                }
                let options = XrayOptions {
                    listen_port: xray_port,
                    listen_host: xray_host.clone(),
                    socks_username: socks_username.to_string(),
                    socks_password: socks_password.to_string(),
                    olcrtc_chain_port: if chained { Some(chain_port) } else { warp_chain_port },
                    olcrtc_chain_user: if chained { socks_username.to_string() } else { String::new() },
                    olcrtc_chain_pass: if chained { socks_password.to_string() } else { String::new() },
                    traffic: xray_traffic,
                    chain_via_dialer_proxy: warp_chain_port.is_some(),
                    keep_flow_on_chain: warp_chain_port.is_some(),
                    warp_relay: warp_relay.clone(),
                    routing_profile: self.xray_routing_profile(routing_profile.as_ref(), &asset_path),
                    second_profile: second_profile.clone(),
                    bypass_lan: routing.bypass_lan,
                    fake_dns_spec: config.fake_dns.clone(),
                    ..Default::default()
                };
                xray_config::build(&effective, &options)
            };
            if let (Some(fd), None) = (config.fake_dns.as_ref(), raw_xray.as_ref()) {
                self.log(&format!(
                    "FakeDNS spec present -> enabling Xray fakedns (pool {}, {} block rules)",
                    fd.inet4_range,
                    fd.block_regex.len()
                ));
            }
            self.log(&format!(
                "Starting Xray engine={:?}, server={}:{}",
                config.engine, effective.server, effective.server_port
            ));
            if !asset_path.is_empty() {
                self.core.xray_set_asset_path(&asset_path)?;
            }
            self.core.xray_start(&json)?;
            if front_xray {
                self.start_sing_box_front(xray_port, listen_host, listen_port, socks_username, socks_password, &routing, &traffic, false)?;
            }
        } else {
            if is_awg {
                self.log("AmneziaWG outbound: QUIC allowed + sniff-override→IPv4");
            }
            if is_hy2 {
                self.log("Hysteria2 outbound: QUIC allowed + sniff-override→IPv4");
            }
            if has_openvpn {
                self.log("OpenVPN endpoint: native sing-box 1.14 (TCP/UDP allowed)");
            }
            let options = SingBoxOptions {
                listen_port,
                listen_host: listen_host.to_string(),
                socks_username: socks_username.to_string(),
                socks_password: socks_password.to_string(),
                olcrtc_chain_port: if chained { Some(chain_port) } else { warp_chain_port },
                olcrtc_chain_user: if chained { socks_username.to_string() } else { String::new() },
                olcrtc_chain_pass: if chained { socks_password.to_string() } else { String::new() },
                warp_relay: warp_relay.clone(),
                auto_detect_interface: true,
                routing: routing.clone(),
                match_apps_by_process: true,
                traffic: traffic.clone(),
                advanced: config.advanced.clone(),
                routing_profile: routing_profile.clone(),
                singbox_geosite_base: profiles_state.singbox_geosite_base.clone(),
                singbox_geoip_base: profiles_state.singbox_geoip_base.clone(),
                block_quic: !is_local_udp_tunnel,
                sniff_override_destination: is_local_udp_tunnel,
                second_profile: second_profile.clone(),
                fake_dns_spec: config.fake_dns.clone(),
                tun_mode: requested_tun,
                split_tunnel_mode: split_mode,
                split_tunnel_processes: split_processes,
                tun_exclude_addresses: tun_exclude,
                hijack_dns: true,
                remote_dns_over_https: false,
                prefer_tcp_remote_dns: is_trust_tunnel && second_profile.is_none(),
                force_family_resolve: false,
                mixed_inbound: true,
                log_file_path: Some(self.sing_box_log_path()),
                cache_file_path: Some(self.sing_box_cache_path()),
                ..Default::default()
            };
            let json = singbox_config::build(&effective, &options).map_err(|e| e.to_string())?;
            self.log(&format!(
                "Starting sing-box engine={:?} via {}:{}{}",
                config.engine,
                effective.server,
                effective.server_port,
                if requested_tun { " (in-core TUN + per-process rules)" } else { "" }
            ));
            self.core.sb_start(&json)?;
            self.tun_handled_in_core = requested_tun;
        }
        if !self.await_socks_port_open(listen_port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("Proxy SOCKS port {listen_port} did not open"));
        }
        self.log(&format!("Proxy core ready on {listen_host}:{listen_port}"));
        if let (Some(_), Some(ini)) = (warp_relay.as_ref(), warp_awg_ini.as_deref()) {
            self.start_warp_awg(ini, listen_port)?;
        }
        Ok(())
    }

    fn start_warp_awg(&self, ini: &str, socks_port: i32) -> Result<(), String> {
        self.log(&format!(
            "WARP: starting the WireGuard/AmneziaWG engine on 127.0.0.1:{}",
            awg_local_port(socks_port)
        ));
        let warp_profile = ProxyProfile {
            tag: "WARP".into(),
            kind: TYPE_AMNEZIAWG.into(),
            awg_config: ini.to_string(),
            ..Default::default()
        };
        self.prepare_amnezia_wg_proxy(&warp_profile, socks_port)?;
        Ok(())
    }

    /// sing-box in front of Xray: it owns the in-core TUN (per-process split) and forwards to Xray.
    #[allow(clippy::too_many_arguments)]
    fn start_sing_box_front(
        &mut self,
        xray_port: i32,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        routing: &RoutingRules,
        traffic: &TrafficSettings,
        block_quic: bool,
    ) -> Result<(), String> {
        if !self.await_socks_port_open(xray_port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("Xray SOCKS port {xray_port} did not open"));
        }
        let front = front_profile(xray_port, socks_username, socks_password);
        let mut front_traffic = traffic.clone();
        front_traffic.fake_dns_enabled = false;
        front_traffic.block_ru_domains = false;
        let (split_mode, split_processes, tun_exclude) = self.tun_fields();
        let options = SingBoxOptions {
            listen_port,
            listen_host: listen_host.to_string(),
            socks_username: socks_username.to_string(),
            socks_password: socks_password.to_string(),
            auto_detect_interface: true,
            routing: routing.clone(),
            match_apps_by_process: true,
            traffic: front_traffic,
            routing_profile: None,
            block_quic,
            force_family_resolve: false,
            prefer_tcp_remote_dns: true,
            tun_mode: true,
            split_tunnel_mode: split_mode,
            split_tunnel_processes: split_processes,
            tun_exclude_addresses: tun_exclude,
            mixed_inbound: true,
            log_file_path: Some(self.sing_box_log_path()),
            cache_file_path: Some(self.sing_box_cache_path()),
            ..Default::default()
        };
        let json = singbox_config::build(&front, &options).map_err(|e| e.to_string())?;
        self.log(&format!(
            "Fronting Xray with sing-box: in-core TUN on {listen_host}:{listen_port} → 127.0.0.1:{xray_port}"
        ));
        self.core.sb_start(&json)?;
        self.tun_handled_in_core = true;
        self.sing_box_front_active = true;
        Ok(())
    }

    // ---------------- OpenFlux / snolc / MasterDNS ----------------

    fn start_open_flux(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
    ) -> Result<(), String> {
        let of = match config.open_flux.as_ref() {
            Some(o) if o.is_complete() => o.clone(),
            _ => return Err("OpenFlux not configured".into()),
        };
        let proxy = self.parse_proxy_link(&of.proxy_link);
        if of.has_proxy() && proxy.is_none() {
            self.log("OpenFlux: proxy link present but could not be parsed — exiting via the exit node directly (no proxy)");
        }
        self.open_flux_proxy_active = proxy.is_some();
        let front = self.tun_request.is_some() && proxy.is_none();
        let port = if proxy.is_some() {
            chain_olcrtc_port(listen_port)
        } else if front {
            sing_box_front_port(listen_port)
        } else {
            listen_port
        };
        let host = if proxy.is_some() || front { LOOPBACK.to_string() } else { listen_host.to_string() };
        self.require_port_free(port, &format!("OpenFlux port {port} is still in use"))?;
        let (user, pass) = if proxy.is_some() { ("", "") } else { (socks_username, socks_password) };
        self.host.open_flux_start(&of, &host, port, user, pass)?;
        if !self.await_socks_port_open(port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("OpenFlux SOCKS port {port} did not open ({})", self.host.open_flux_exit_description()));
        }
        self.log(&format!("OpenFlux ready on {host}:{port}"));
        if let Some(p) = proxy {
            let resolve = |pp: &ProxyProfile, g: ProxyCore| of.resolved_proxy_core(Some(pp), g);
            self.start_proxy_over_tunnel("OpenFlux", config, &p, port, listen_host, listen_port, socks_username, socks_password, &resolve)?;
        } else if front {
            let routing = self.load_routing_expanding_asn();
            let traffic = self.host.load_traffic();
            self.start_sing_box_front(port, listen_host, listen_port, socks_username, socks_password, &routing, &traffic, true)?;
        }
        Ok(())
    }

    fn start_snolc(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
    ) -> Result<(), String> {
        let sc = match config.snolc.as_ref() {
            Some(s) if s.is_complete() => s.clone(),
            _ => return Err("snolc not configured".into()),
        };
        let proxy = self.parse_proxy_link(&sc.proxy_link);
        if sc.has_proxy() && proxy.is_none() {
            self.log("snolc: proxy link present but could not be parsed — exiting via the exit node directly (no proxy)");
        }
        self.snolc_proxy_active = proxy.is_some();
        let port = if proxy.is_some() { chain_olcrtc_port(listen_port) } else { listen_port };
        let host = if proxy.is_some() { LOOPBACK.to_string() } else { listen_host.to_string() };
        self.require_port_free(port, &format!("snolc port {port} is still in use"))?;
        let (user, pass) = if proxy.is_some() { ("", "") } else { (socks_username, socks_password) };
        self.host.snolc_start(&sc, &host, port, user, pass)?;
        if !self.await_socks_port_open(port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("snolc SOCKS port {port} did not open ({})", self.host.snolc_exit_description()));
        }
        self.log(&format!("snolc ready on {host}:{port}"));
        if let Some(p) = proxy {
            let resolve = |pp: &ProxyProfile, g: ProxyCore| sc.resolved_proxy_core(Some(pp), g);
            self.start_proxy_over_tunnel("snolc", config, &p, port, listen_host, listen_port, socks_username, socks_password, &resolve)?;
        }
        Ok(())
    }

    /// MasterDNS (DNS tunnel) serves a real SOCKS5; with a proxy link it moves to the internal chain
    /// port (no auth) and an Xray/sing-box core fronts it on listen_port.
    fn start_master_dns(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
    ) -> Result<(), String> {
        let md = match config.master_dns.as_ref() {
            Some(m) if m.is_complete() => m.clone(),
            _ => return Err("MasterDNS not configured".into()),
        };
        let proxy = self.parse_proxy_link(&md.proxy_link);
        if !is_blank(&md.proxy_link) && proxy.is_none() {
            self.log("MasterDNS: proxy link present but could not be parsed — exiting via the MasterDNS SOCKS directly (no proxy)");
        }
        let use_proxy = proxy.is_some();
        self.master_dns_proxy_active = use_proxy;
        let md_port = if use_proxy { chain_olcrtc_port(listen_port) } else { listen_port };
        self.require_port_free(listen_port, &format!("SOCKS port {listen_port} is still in use"))?;
        if use_proxy {
            self.require_port_free(md_port, &format!("MasterDNS internal port {md_port} is still in use"))?;
        }
        let md_addr = format!("{listen_host}:{md_port}");
        self.log(&format!(
            "Starting MasterDNS on {md_addr} (domains={}, resolvers={}, encryption={})",
            md.domains,
            md.resolver_list().len(),
            masterdns_encryption_label(md.encryption_method)
        ));
        self.core.master_dns_stop();
        let args = MasterDnsStart {
            work_dir: self.host.app_data_dir().join("masterdns").to_string_lossy().into_owned(),
            domains: md.domains.clone(),
            encryption_key: md.encryption_key.clone(),
            encryption_method: md.encryption_method,
            resolvers: md.resolvers.clone(),
            listen_addr: md_addr.clone(),
            socks_user: if use_proxy { String::new() } else { socks_username.to_string() },
            socks_pass: if use_proxy { String::new() } else { socks_password.to_string() },
            balancing_strategy: md.balancing_strategy,
            packet_duplication: md.packet_duplication,
            ..Default::default()
        };
        self.core.master_dns_start(&args)?;
        if !self.await_socks_port_open(md_port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("MasterDNS SOCKS port {md_port} did not open"));
        }
        self.log(&format!("MasterDNS ready on {md_addr}"));
        let Some(p) = proxy else {
            return Ok(());
        };
        let resolve = |pp: &ProxyProfile, g: ProxyCore| md.resolved_proxy_core(Some(pp), g);
        self.start_proxy_over_tunnel("MasterDNS", config, &p, md_port, listen_host, listen_port, socks_username, socks_password, &resolve)
    }

    /// apps → core → proxy → tunnel SOCKS (tunnel_port, no auth) → tunnel exit → internet.
    #[allow(clippy::too_many_arguments)]
    fn start_proxy_over_tunnel(
        &mut self,
        label: &str,
        config: &LocationConfig,
        proxy: &ProxyProfile,
        tunnel_port: i32,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        resolve_core: &dyn Fn(&ProxyProfile, ProxyCore) -> ProxyCore,
    ) -> Result<(), String> {
        let traffic = self.host.load_traffic();
        let routing = self.load_routing_expanding_asn();
        let profiles_state = self.host.load_routing_profiles();
        let routing_profile = self.resolve_profile_expanding_asn(&profiles_state, &config.routing_profile_id);
        let global_core = self.host.load_app_behavior().global_proxy_core;
        let profile_wants_xray = routing_profile
            .as_ref()
            .is_some_and(|p| p.needs_geo_files() || !p.dns_hosts.is_empty())
            && is_xray_typed(&proxy.kind);
        let use_xray = resolve_core(proxy, global_core) == ProxyCore::Xray || profile_wants_xray;
        self.log(&format!(
            "{label} chaining proxy {} over the tunnel ({})",
            proxy.display_name(),
            if use_xray { "Xray" } else { "sing-box" }
        ));
        let requested_tun = self.tun_request.is_some();
        if use_xray {
            let front_xray = requested_tun;
            let xray_port = if front_xray { sing_box_front_port(listen_port) } else { listen_port };
            let xray_host = if front_xray { LOOPBACK.to_string() } else { listen_host.to_string() };
            let asset_path = self.ensure_geo_asset_path(routing_profile.as_ref());
            let options = XrayOptions {
                listen_port: xray_port,
                listen_host: xray_host,
                socks_username: socks_username.to_string(),
                socks_password: socks_password.to_string(),
                olcrtc_chain_port: Some(tunnel_port),
                traffic: traffic.clone(),
                routing_profile: self.xray_routing_profile(routing_profile.as_ref(), &asset_path),
                block_quic: true,
                force_family_resolve: false,
                chain_via_dialer_proxy: true,
                handshake_timeout_sec: Some(30),
                direct_via_base: true,
                ..Default::default()
            };
            let xray_json = xray_config::build(proxy, &options);
            self.active_proxy_core = ProxyCore::Xray;
            if !asset_path.is_empty() {
                self.core.xray_set_asset_path(&asset_path)?;
            }
            self.core.xray_start(&xray_json)?;
            if front_xray {
                self.start_sing_box_front(xray_port, listen_host, listen_port, socks_username, socks_password, &routing, &traffic, false)?;
            }
        } else {
            let (split_mode, split_processes, tun_exclude) = self.tun_fields();
            let options = SingBoxOptions {
                listen_port,
                listen_host: listen_host.to_string(),
                socks_username: socks_username.to_string(),
                socks_password: socks_password.to_string(),
                olcrtc_chain_port: Some(tunnel_port),
                auto_detect_interface: true,
                routing: routing.clone(),
                match_apps_by_process: true,
                traffic: traffic.clone(),
                routing_profile: routing_profile.clone(),
                singbox_geosite_base: profiles_state.singbox_geosite_base.clone(),
                singbox_geoip_base: profiles_state.singbox_geoip_base.clone(),
                block_quic: true,
                force_family_resolve: false,
                allow_local_resolve: false,
                direct_via_base: true,
                mixed_inbound: true,
                hijack_dns: true,
                tun_mode: requested_tun,
                split_tunnel_mode: split_mode,
                split_tunnel_processes: split_processes,
                tun_exclude_addresses: tun_exclude,
                log_file_path: Some(self.sing_box_log_path()),
                cache_file_path: Some(self.sing_box_cache_path()),
                ..Default::default()
            };
            let json = singbox_config::build(proxy, &options).map_err(|e| e.to_string())?;
            self.active_proxy_core = ProxyCore::SingBox;
            self.core.sb_start(&json)?;
            self.tun_handled_in_core = requested_tun;
        }
        if !self.await_socks_port_open(listen_port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("{label} proxy SOCKS port {listen_port} did not open"));
        }
        self.log(&format!("{label} proxy ready on {listen_host}:{listen_port}"));
        Ok(())
    }

    // ---------------- VK-TURN ----------------

    fn start_vk_turn(
        &mut self,
        config: &LocationConfig,
        listen_host: &str,
        listen_port: i32,
        socks_username: &str,
        socks_password: &str,
        device_id: &str,
    ) -> Result<(), String> {
        let vk_opt = config.vkturn.clone();
        let mut profile: Option<ProxyProfile> = config.proxy.as_ref().map(|p| clamp_vkturn_mtu(p, VKTURN_MAX_WG_MTU));
        let uses_wdtt = vk_opt.as_ref().is_some_and(|v| v.uses_wdtt());
        let wdtt_raw = uses_wdtt && vk_opt.as_ref().is_some_and(|v| v.wdtt_plus.raw_mode);
        let uses_csqtt = vk_opt.as_ref().is_some_and(|v| v.uses_csqtt());
        let outbound_type = vk_outbound_type(vk_opt.as_ref());
        let outbound_configured = vk_outbound_configured(vk_opt.as_ref(), &outbound_type, profile.as_ref());
        let vk = match vk_opt {
            Some(v) if v.is_complete() && outbound_configured => v,
            _ => return Err("VK-TURN not configured".into()),
        };
        self.require_port_free(listen_port, &format!("SOCKS port {listen_port} is still in use"))?;
        let listen_addr = format!("127.0.0.1:{}", vk.listen_port);
        let awg_port = awg_local_port(listen_port);
        if uses_csqtt {
            let (bridge_exe, client_exe) = self.host.csqtt_binaries()?;
            self.log(&format!(
                "Starting VK-TURN csqtt core (peer={}, workers={}, turn-tcp={}, obfs={}, fp={})",
                vk.csqtt_peer_addr(),
                workers_label(vk.csqtt_workers),
                vk.csqtt.turn_tcp,
                if vk.csqtt.obfs_video { "video" } else { "audio" },
                vk.csqtt.fingerprint
            ));
            let options = vk.csqtt_core_options_json(&client_exe, &format!("127.0.0.1:{awg_port}"), device_id);
            self.host.csqtt_launch(&bridge_exe, &options)?;
        } else if uses_wdtt {
            let peer_addr = vk.wdtt_dial_addr();
            let core_listen = if wdtt_raw { format!("127.0.0.1:{awg_port}") } else { listen_addr.clone() };
            self.log(&format!(
                "Starting VK-TURN qWDTT core on {core_listen} (mode={}, peer={peer_addr}, workers={}, turn-tcp={}, obfs={})",
                if wdtt_raw { "raw" } else { "wg" },
                workers_label(vk.wdtt_workers),
                vk.wdtt_plus.rt_network_mode,
                if vk.wdtt_plus.obfs_video { "video" } else { "audio" }
            ));
            self.core.wdtt_start(&vk.wdtt_core_options_json(&core_listen, device_id))?;
        } else {
            self.log(&format!("Starting VK-TURN freeturn listener on {listen_addr}"));
            self.core.ft_start(&vk.uri, &listen_addr, &vk.vk_link, vk.streams)?;
        }

        let routing = self.load_routing_expanding_asn();
        let mut traffic = self.host.load_traffic();
        traffic.domain_strategy = "ipv4_only".into();
        let profiles_state = self.host.load_routing_profiles();
        let routing_profile = self.resolve_profile_expanding_asn(&profiles_state, &config.routing_profile_id);
        match &routing_profile {
            None => self.log("Routing: NO profile applied — all traffic via the VK-TURN tunnel"),
            Some(p) => self.log(&format!("Routing: applying '{}'", p.display_name())),
        }

        if uses_csqtt {
            let Some(up) = self.host.csqtt_await_ready(VKTURN_RELAY_READY_TIMEOUT_MS) else {
                return Err(self.host.csqtt_no_tunnel_message());
            };
            profile = Some(local_socks_profile("csqtt", awg_port));
            self.log(&format!(
                "VK-TURN csqtt up ({}, DNS {}, MTU {}); SOCKS on {awg_port}",
                up.ip,
                up.dns.join(","),
                up.mtu
            ));
        } else if uses_wdtt {
            let wg_conf = self.core.wdtt_wait_config(VKTURN_RELAY_READY_TIMEOUT_MS as i32)?;
            match wg_conf {
                Some(c) if wdtt_raw && c.starts_with("RAWCONF:") => {
                    profile = Some(local_socks_profile("qWDTT Raw", awg_port));
                    self.log(&format!(
                        "VK-TURN WDTT Raw up ({}); SOCKS on {awg_port}",
                        c.strip_prefix("RAWCONF:").unwrap_or(&c)
                    ));
                }
                _ if wdtt_raw => {
                    return Err(format!(
                        "WDTT Raw: no address from the server — is -listen-raw on port {} enabled? (redeploy with auto-install){}",
                        vk.wdtt_plus.raw_port_or_default(),
                        error_suffix(&self.core.wdtt_last_error())
                    ));
                }
                Some(c) => {
                    profile = Some(build_wdtt_wg_profile(&c, vk.listen_port));
                    self.log("VK-TURN WDTT relay up; WireGuard config from server applied");
                }
                None if profile.as_ref().is_some_and(|p| has_text(&p.raw_outbound)) => {
                    self.log("VK-TURN WDTT: no GETCONF — falling back to the stored WireGuard config");
                }
                None => {
                    return Err(format!(
                        "WDTT: no WireGuard config from server (GETCONF) and none stored{}",
                        error_suffix(&self.core.wdtt_last_error())
                    ));
                }
            }
        } else if self.await_vk_turn_relay_ready(VKTURN_RELAY_READY_TIMEOUT_MS) {
            self.log(&format!(
                "VK-TURN relay up ({} stream(s)); starting WireGuard",
                self.core.ft_connected_streams()
            ));
        } else {
            self.log("VK-TURN relay not ready yet; starting outbound anyway (will retry)");
        }

        self.active_proxy_core = ProxyCore::SingBox;
        let exit_profile = profile.ok_or_else(|| "Required value was null.".to_string())?;
        let chain_proxy = if outbound_type != OUTBOUND_PROXY && !is_blank(&vk.chain_proxy_link) {
            share_link::parse(&vk.chain_proxy_link).filter(|p| p.is_complete())
        } else {
            None
        };
        let awg_socks = if outbound_type == OUTBOUND_AMNEZIAWG {
            Some(self.prepare_amnezia_wg_proxy(&exit_profile, listen_port)?)
        } else {
            None
        };
        let proxy_for_core = if outbound_type == OUTBOUND_PROXY { Some(exit_profile.clone()) } else { chain_proxy.clone() };
        let profile_wants_xray = routing_profile
            .as_ref()
            .is_some_and(|p| p.needs_geo_files() || !p.dns_hosts.is_empty())
            && proxy_for_core.as_ref().is_some_and(|p| is_xray_typed(&p.kind));
        // Kotlin: vk.resolvedProxyCore(proxyForCore) — global left at its default (Auto).
        let use_xray = proxy_for_core
            .as_ref()
            .is_some_and(|p| vk.resolved_proxy_core(Some(p), ProxyCore::Auto) == ProxyCore::Xray || profile_wants_xray);
        let requested_tun = self.tun_request.is_some();

        if use_xray {
            let asset_path = self.ensure_geo_asset_path(routing_profile.as_ref());
            let xray_profile = self.xray_routing_profile(routing_profile.as_ref(), &asset_path);
            let front_xray = requested_tun;
            let xray_port = if front_xray { sing_box_front_port(listen_port) } else { listen_port };
            let xray_host = if front_xray { LOOPBACK.to_string() } else { listen_host.to_string() };
            let base = XrayOptions {
                listen_port: xray_port,
                listen_host: xray_host,
                socks_username: socks_username.to_string(),
                socks_password: socks_password.to_string(),
                log_level: "debug".into(),
                traffic: traffic.clone(),
                routing_profile: xray_profile,
                block_quic: false,
                ..Default::default()
            };
            let xray_json = if outbound_type == OUTBOUND_PROXY {
                self.log(&format!("VK-TURN exit: proxy {} over VK (tcp, Xray)", exit_profile.display_name()));
                xray_config::build(&exit_profile, &base)
            } else if outbound_type == OUTBOUND_AMNEZIAWG {
                let chain = chain_proxy.clone().ok_or_else(|| "Required value was null.".to_string())?;
                self.log(&format!("VK-TURN chaining proxy {} over AmneziaWG (Xray)", chain.display_name()));
                let options = XrayOptions {
                    olcrtc_chain_port: Some(awg_port),
                    chain_via_dialer_proxy: true,
                    direct_via_base: true,
                    ..base
                };
                xray_config::build(&chain, &options)
            } else {
                let chain = chain_proxy.clone().ok_or_else(|| "Required value was null.".to_string())?;
                self.log(&format!("VK-TURN chaining proxy {} over WireGuard (Xray)", chain.display_name()));
                let options = XrayOptions { wireguard_base: Some(exit_profile.clone()), ..base };
                xray_config::build(&chain, &options)
            };
            self.active_proxy_core = ProxyCore::Xray;
            self.log(&format!("Starting Xray (VK-TURN, {outbound_type}) via {listen_addr}"));
            if !asset_path.is_empty() {
                self.core.xray_set_asset_path(&asset_path)?;
            }
            self.core.xray_start(&xray_json)?;
            if front_xray {
                self.start_sing_box_front(xray_port, listen_host, listen_port, socks_username, socks_password, &routing, &traffic, false)?;
            }
        } else {
            let (split_mode, split_processes, tun_exclude) = self.tun_fields();
            let base = SingBoxOptions {
                listen_port,
                listen_host: listen_host.to_string(),
                socks_username: socks_username.to_string(),
                socks_password: socks_password.to_string(),
                auto_detect_interface: true,
                routing: routing.clone(),
                match_apps_by_process: true,
                traffic: traffic.clone(),
                routing_profile: routing_profile.clone(),
                singbox_geosite_base: profiles_state.singbox_geosite_base.clone(),
                singbox_geoip_base: profiles_state.singbox_geoip_base.clone(),
                log_level: "debug".into(),
                dns_strategy_override: Some("ipv4_only".into()),
                block_quic: false,
                tun_mode: requested_tun,
                split_tunnel_mode: split_mode,
                split_tunnel_processes: split_processes,
                tun_exclude_addresses: tun_exclude,
                mixed_inbound: true,
                log_file_path: Some(self.sing_box_log_path()),
                cache_file_path: Some(self.sing_box_cache_path()),
                ..Default::default()
            };
            let (outbound, options) = if outbound_type == OUTBOUND_AMNEZIAWG {
                match &chain_proxy {
                    Some(c) => self.log(&format!("VK-TURN chaining proxy {} over AmneziaWG", c.display_name())),
                    None => self.log("VK-TURN exit: AmneziaWG over VK"),
                }
                let outbound = match chain_proxy.clone().or_else(|| awg_socks.clone()) {
                    Some(p) => p,
                    None => return Err("Required value was null.".into()),
                };
                let options = SingBoxOptions {
                    olcrtc_chain_port: if chain_proxy.is_some() { Some(awg_port) } else { None },
                    sniff_override_destination: true,
                    prefer_tcp_remote_dns: chain_proxy.is_none(),
                    direct_via_base: chain_proxy.is_some(),
                    ..base
                };
                (outbound, options)
            } else if outbound_type == OUTBOUND_PROXY {
                self.log(&format!("VK-TURN exit: proxy {} over VK (tcp)", exit_profile.display_name()));
                (exit_profile.clone(), base)
            } else if let Some(c) = &chain_proxy {
                self.log(&format!("VK-TURN chaining proxy {} over WireGuard", c.display_name()));
                (c.clone(), SingBoxOptions { wireguard_base: Some(exit_profile.clone()), ..base })
            } else {
                (exit_profile.clone(), base)
            };
            let json = singbox_config::build(&outbound, &options).map_err(|e| e.to_string())?;
            self.log(&format!(
                "Starting sing-box (VK-TURN, {outbound_type}) via {listen_addr}{}",
                if requested_tun { " (in-core TUN)" } else { "" }
            ));
            self.core.sb_start(&json)?;
            self.tun_handled_in_core = requested_tun;
        }
        if !self.await_socks_port_open(listen_port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("VK-TURN SOCKS port {listen_port} did not open"));
        }
        self.log(&format!("VK-TURN ready on {listen_host}:{listen_port}"));
        Ok(())
    }

    /// Waits for the freeturn relay; a VK captcha opens once in its own browser window and keeps
    /// extending the deadline while it is being solved.
    fn await_vk_turn_relay_ready(&self, timeout_ms: u64) -> bool {
        let mut deadline = self.host.now_ms() + timeout_ms;
        let mut opened_captcha = String::new();
        while self.host.now_ms() < deadline {
            if self.core.ft_connected_streams() > 0 {
                return true;
            }
            let captcha = self.core.ft_captcha_url();
            if !is_blank(&captcha) && captcha != opened_captcha {
                opened_captcha = captcha.clone();
                self.log(&format!("VK просит капчу — открываю {captcha} в отдельном окне браузера"));
                if !self.host.open_browser_window(&captcha) {
                    self.log(&format!("Не удалось открыть браузер — решите капчу вручную по адресу {captcha}"));
                }
                self.host.show_captcha_toast();
            }
            if self.core.ft_captcha_active() {
                deadline = self.host.now_ms() + timeout_ms;
            }
            self.host.sleep_ms(50);
        }
        false
    }

    // ---------------- local SOCKS helpers ----------------

    fn prepare_amnezia_wg_proxy(&self, profile: &ProxyProfile, socks_port: i32) -> Result<ProxyProfile, String> {
        if profile.kind != TYPE_AMNEZIAWG {
            return Ok(profile.clone());
        }
        self.core.awg_stop();
        let port = awg_local_port(socks_port);
        let listen = format!("127.0.0.1:{port}");
        self.log(&format!("Starting AmneziaWG SOCKS on {listen}"));
        self.core.awg_start(&profile.awg_config, &listen)?;
        if !self.await_socks_port_open(port, MOBILE_READY_TIMEOUT_MS) {
            return Err(format!("AmneziaWG SOCKS port {port} did not open"));
        }
        Ok(local_socks_profile(&or_blank(&profile.tag, "AmneziaWG"), port))
    }

    fn prepare_trust_tunnel_proxy(&self, profile: &ProxyProfile, socks_port: i32) -> Result<ProxyProfile, String> {
        if profile.kind != TYPE_TRUSTTUNNEL {
            return Ok(profile.clone());
        }
        let port = trust_tunnel_local_port(socks_port);
        self.host.trust_tunnel_start(&profile.tt_config, port)?;
        if !self.await_socks_port_open(port, MOBILE_READY_TIMEOUT_MS) {
            self.host.trust_tunnel_stop();
            return Err(format!("Trust Tunnel SOCKS port {port} did not open"));
        }
        if !self.host.trust_tunnel_await_ready(port) {
            self.log("Trust Tunnel: endpoint session not confirmed, continuing");
        }
        Ok(local_socks_profile(&or_blank(&profile.tag, "Trust Tunnel"), port))
    }

    // ---------------- geo assets / routing ----------------

    /// Geo .dat for a verbatim config's own geosite:/geoip: selectors (not gated on a profile).
    fn ensure_raw_config_geo_asset_path(&self, profile: Option<&RoutingProfile>) -> String {
        let state = self.host.load_routing_profiles();
        let geoip = profile.map(|p| p.geoip_url.clone()).filter(|u| !is_blank(u)).unwrap_or(state.geoip_url.clone());
        let geosite = profile.map(|p| p.geosite_url.clone()).filter(|u| !is_blank(u)).unwrap_or(state.geosite_url.clone());
        if self.host.ensure_geo_assets(&geoip, &geosite) {
            self.host.geo_asset_dir()
        } else {
            String::new()
        }
    }

    fn ensure_geo_asset_path(&self, profile: Option<&RoutingProfile>) -> String {
        let Some(profile) = profile.filter(|p| p.needs_geo_files()) else {
            return String::new();
        };
        let state = self.host.load_routing_profiles();
        let geoip = or_blank(&profile.geoip_url, &state.geoip_url);
        let geosite = or_blank(&profile.geosite_url, &state.geosite_url);
        if self.host.ensure_geo_assets(&geoip, &geosite) {
            self.log(&format!("Geo databases ready for routing profile '{}'", profile.display_name()));
            self.host.geo_asset_dir()
        } else {
            self.log("Geo databases unavailable; profile geo rules will be skipped on Xray");
            String::new()
        }
    }

    /// The profile Xray gets: without geo selectors when the .dat files are unavailable.
    fn xray_routing_profile(&self, profile: Option<&RoutingProfile>, asset_path: &str) -> Option<RoutingProfile> {
        let profile = profile?;
        if profile.needs_geo_files() && asset_path.is_empty() {
            self.log(&format!(
                "Geo databases unavailable — applying '{}' without geo selectors",
                profile.display_name()
            ));
            Some(profile.without_geo_selectors())
        } else {
            Some(profile.clone())
        }
    }

    /// Routing rules with ASN selectors expanded to CIDRs and package regexes to running processes.
    fn load_routing_expanding_asn(&self) -> RoutingRules {
        let mut routing = self.host.load_routing();
        let asns = asn::collect(routing.rules.iter().flat_map(|r| r.ip.iter()));
        if !asns.is_empty() {
            let cidrs = self.host.resolve_asns(&asns);
            routing.rules = SingBoxRule::expand_asn(&routing.rules, &cidrs);
        }
        if routing.rules.iter().any(|r| !r.package_regex.is_empty()) {
            routing.rules = SingBoxRule::expand_package_regex(&routing.rules, &self.host.running_process_names());
        }
        routing
    }

    fn resolve_profile_expanding_asn(&self, state: &RoutingProfilesState, profile_id: &str) -> Option<RoutingProfile> {
        let profile = state.resolve(Some(profile_id)).cloned()?;
        let asns = profile.referenced_asns();
        if asns.is_empty() {
            return Some(profile);
        }
        let cidrs = self.host.resolve_asns(&asns);
        self.log(&format!("Routing: expanded {}/{} ASN selector(s) to CIDRs", cidrs.len(), asns.len()));
        Some(profile.expand_asn(&cidrs))
    }
}
