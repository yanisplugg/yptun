//! Desktop VPN manager, part 2: startDesktopMode.
//!
//! Port of DesktopVpnManager.startDesktopMode / scheduleAutoReconnect (jvmMain/vpn). The decisions
//! (mode, session SOCKS, interface pinning, engine vs olcRTC, bypass IPs, split tunnel, UDP over
//! TCP) are pure functions; the steps that touch the OS (TUN controllers, system proxy, engines,
//! olcRTC process, PAC server, outbound interface binding) go through [`DesktopStartHost`].
//! Order of steps, logs and statuses follow the Kotlin code.

use std::fmt;

use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::desktop_vpn_manager::{
    auto_reconnect_delay_ms, auto_reconnect_log, with_free_tun_port, DesktopMode, OlcRtcStartError,
};
use crate::location::LocationConfig;
use crate::model::EngineType;
use crate::vpn_settings::SplitTunnelState;

pub const NO_ACTIVE_LOCATION: &str = "No active location";
pub const ADD_VALID_LOCATION_LOG: &str = "Add a valid location before starting desktop proxy";
pub const PROXY_MODE_NO_AUTH_LOG: &str = "Proxy mode: local SOCKS runs without authentication (browsers and the Windows system proxy cannot authenticate)";
pub const NO_PHYSICAL_INTERFACE_LOG: &str = "Could not identify the physical network interface; direct-routed traffic may loop through the TUN";
pub const WINDOWS_TUN_IN_CORE_LOG: &str = "Windows TUN owned by sing-box (per-process split tunneling active)";
pub const START_SUPERSEDED: &str = "Desktop start superseded";
pub const START_CANCELLED_LOG: &str = "Desktop start cancelled";

/// The desktop side of VpnStatus as startDesktopMode uses it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopVpnStatus {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Stopping,
    Error(String),
}

/// Why a start step stopped the connect.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DesktopStartError {
    /// Kotlin CancellationException: a newer start/stop won.
    Cancelled(String),
    /// Any other exception; the String is its message.
    Failed(String),
}

impl DesktopStartError {
    pub fn message(&self) -> &str {
        match self {
            DesktopStartError::Cancelled(m) => m,
            DesktopStartError::Failed(m) => m,
        }
    }

    pub fn is_cancelled(&self) -> bool {
        matches!(self, DesktopStartError::Cancelled(_))
    }
}

impl fmt::Display for DesktopStartError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for DesktopStartError {}

impl From<OlcRtcStartError> for DesktopStartError {
    fn from(e: OlcRtcStartError) -> Self {
        match e {
            OlcRtcStartError::Superseded => DesktopStartError::Cancelled(String::from(START_SUPERSEDED)),
            OlcRtcStartError::Failed(m) => DesktopStartError::Failed(m),
        }
    }
}

/// Kotlin: Proxy connection mode forces SystemProxy, otherwise the OS default.
pub fn desktop_mode_for(os: DesktopOs, proxy_mode: bool) -> DesktopMode {
    if proxy_mode {
        DesktopMode::SystemProxy
    } else {
        DesktopMode::current(os)
    }
}

/// Proxy mode is a server the OS and other apps point at, and neither the system proxy nor a
/// browser can answer a SOCKS auth challenge: credentials are dropped there. TUN keeps them and
/// moves the port block when another program holds it.
pub fn session_socks_settings(
    mode: DesktopMode,
    configured: &DesktopSocksProxySettings,
    is_taken: impl Fn(i32) -> bool,
    log: &mut dyn FnMut(String),
) -> DesktopSocksProxySettings {
    if mode == DesktopMode::SystemProxy {
        let mut s = configured.clone();
        s.username = String::new();
        s.password = String::new();
        s
    } else {
        with_free_tun_port(configured, is_taken, log)
    }
}

/// True when PROXY_MODE_NO_AUTH_LOG should be logged.
pub fn drops_proxy_auth(mode: DesktopMode, configured: &DesktopSocksProxySettings) -> bool {
    mode == DesktopMode::SystemProxy && !configured.username.trim().is_empty()
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InterfacePinning {
    pub index: i32,
    pub pin_udp: bool,
    pub log: Option<String>,
}

/// TUN modes pin Xray's sockets to the physical adapter (no VpnService.protect() on desktop).
/// VK-TURN's exit reaches its WireGuard hop over UDP on 127.0.0.1, so UDP stays unpinned there.
pub fn interface_pinning(mode: DesktopMode, engine: EngineType, physical_index: i32) -> InterfacePinning {
    if mode == DesktopMode::SystemProxy {
        return InterfacePinning {
            index: 0,
            pin_udp: true,
            log: None,
        };
    }
    let pin_udp = engine != EngineType::VkTurn;
    if physical_index > 0 {
        InterfacePinning {
            index: physical_index,
            pin_udp,
            log: Some(format!(
                "Pinning Xray sockets to network interface #{} (keeps direct traffic out of the TUN)",
                physical_index
            )),
        }
    } else {
        InterfacePinning {
            index: 0,
            pin_udp,
            log: Some(String::from(NO_PHYSICAL_INTERFACE_LOG)),
        }
    }
}

/// Non-Stealth engines and multi-room Stealth run in-process; plain Stealth keeps the olcRTC process.
pub fn use_engine_controller(location: &LocationConfig, engine_controller_supported: bool) -> bool {
    (location.engine != EngineType::Stealth || location.uses_multi_room()) && engine_controller_supported
}

/// Bypass server IPs are resolved once per connect, only for in-process engines in TUN modes.
pub fn needs_bypass_server_ips(use_engine: bool, mode: DesktopMode) -> bool {
    use_engine && mode != DesktopMode::SystemProxy
}

pub fn split_tunnel_processes(split: &SplitTunnelState) -> Vec<String> {
    match split.mode.as_str() {
        "proxy_selected" => split.proxy_processes.iter().cloned().collect(),
        "bypass_selected" => split.bypass_processes.iter().cloned().collect(),
        _ => Vec::new(),
    }
}

/// Same split as Android's hev config: these SOCKS servers have no UDP ASSOCIATE.
pub fn udp_over_tcp(engine: EngineType) -> bool {
    engine == EngineType::MasterDns || engine == EngineType::OpenFlux
}

pub fn connected_log(mode: DesktopMode) -> &'static str {
    match mode {
        DesktopMode::LinuxTun => "Desktop Linux TUN connected",
        DesktopMode::WindowsTun => "Desktop Windows TUN connected",
        DesktopMode::SystemProxy => "Desktop proxy connected",
    }
}

/// Arguments of engineController.start (the host adds the device identity).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineStartRequest {
    pub location: LocationConfig,
    pub listen_host: String,
    pub listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub tun_via_sing_box: bool,
    pub split_tunnel_mode: String,
    pub split_tunnel_processes: Vec<String>,
    pub tun_exclude_addresses: Vec<String>,
}

/// Arguments of startLinuxTun / startWindowsTun (udp_over_tcp is Linux-only).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TunStartRequest {
    pub socks_port: i32,
    pub request_generation: u64,
    pub bypass_server_ips: Vec<String>,
    pub socks_username: String,
    pub socks_password: String,
    pub udp_over_tcp: bool,
}

/// Manager state startDesktopMode reads and writes (the Kotlin fields of the same names).
#[derive(Debug, Clone, Default)]
pub struct DesktopVpnSession {
    pub active_mode: Option<DesktopMode>,
    pub session_socks: Option<DesktopSocksProxySettings>,
    pub engine_location: Option<LocationConfig>,
    pub auto_reconnect: bool,
    pub auto_reconnect_attempt: u32,
}

/// Everything startDesktopMode does to the outside world.
pub trait DesktopStartHost {
    fn set_status(&mut self, status: DesktopVpnStatus);
    fn log(&mut self, message: String);
    /// The manager's current generation (bumped by every start/stop request).
    fn generation(&self) -> u64;
    /// locationsRepository.getActiveLocation()?.location (not yet normalized).
    fn active_location(&mut self) -> Option<LocationConfig>;
    /// The stored SOCKS settings (not yet normalized).
    fn configured_socks(&self) -> DesktopSocksProxySettings;
    /// connectionModeProvider() == Proxy.
    fn proxy_mode(&self) -> bool;
    /// Offers to close another VPN client; returns once answered or timed out.
    fn resolve_conflicting_vpn_clients(&mut self);
    /// Port probe for the TUN port block (Kotlin canConnectToSocks).
    fn port_taken(&self, port: i32) -> bool;
    fn ensure_administrator_or_request_restart(&mut self) -> Result<(), DesktopStartError>;
    /// linuxTunController.clearStale(hev binary).
    fn clear_stale_linux_tun(&mut self) -> Result<(), DesktopStartError>;
    /// PhysicalInterface.index().
    fn physical_interface_index(&mut self) -> i32;
    /// YpTunCore.bindOutboundInterface(index, pinUdp).
    fn bind_outbound_interface(&mut self, index: i32, pin_udp: bool);
    /// YpTunCore.bindOutboundInterface(0) on rollback.
    fn unbind_outbound_interface(&mut self);
    fn engine_controller_supported(&self) -> bool;
    fn resolve_bypass_server_ips(&mut self, location: &LocationConfig) -> Vec<String>;
    /// JvmVpnSettings.loadSplitTunnel().
    fn split_tunnel(&mut self) -> SplitTunnelState;
    fn start_engine(&mut self, request: &EngineStartRequest) -> Result<(), DesktopStartError>;
    fn tun_handled_in_core(&self) -> bool;
    /// startOlcRtcProcessWithFallback; the host keeps the process.
    fn start_olcrtc(
        &mut self,
        location: &LocationConfig,
        socks: &DesktopSocksProxySettings,
        privileged: bool,
    ) -> Result<(), DesktopStartError>;
    /// waitForOlcRtcReady on the started process (see desktop_vpn_manager::wait_for_olcrtc_ready).
    fn wait_for_olcrtc_ready(&mut self, socks_port: i32, request_generation: u64) -> Result<(), OlcRtcStartError>;
    fn start_linux_tun(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError>;
    fn start_windows_tun(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError>;
    fn start_system_proxy(
        &mut self,
        socks: &DesktopSocksProxySettings,
        request_generation: u64,
    ) -> Result<(), DesktopStartError>;
    fn start_watchdog(&mut self, generation: u64);
    // Rollback steps.
    /// tunProcess?.let(linuxTunController::release); tunProcess = null.
    fn release_linux_tun(&mut self);
    /// windowsTunController.stop(tunProcess); tunProcess = null.
    fn stop_windows_tun(&mut self) -> Result<(), String>;
    /// proxyController.restore() (bounded by the host, see run_bounded).
    fn restore_system_proxy(&mut self) -> Result<(), String>;
    fn stop_http_proxy_bridge(&mut self);
    fn stop_pac_server(&mut self);
    /// engineController.stopAll() (bounded by the host, see run_bounded).
    fn stop_engines(&mut self) -> Result<(), String>;
    /// stopProcess(process, privileged); process = null.
    fn stop_olcrtc_process(&mut self, privileged: bool);
    fn delete_olcrtc_config(&mut self);
    /// After delay_ms: if generation is still `generation` and auto-reconnect is on, start again (auto).
    fn schedule_restart(&mut self, generation: u64, delay_ms: u64);
}

/// Kotlin scheduleAutoReconnect: 2s, 4s, 8s... capped at 30s.
pub fn schedule_auto_reconnect(
    host: &mut dyn DesktopStartHost,
    session: &mut DesktopVpnSession,
    generation: u64,
    reason: Option<&str>,
) {
    let delay_ms = auto_reconnect_delay_ms(session.auto_reconnect_attempt);
    session.auto_reconnect_attempt = session.auto_reconnect_attempt.saturating_add(1);
    host.set_status(DesktopVpnStatus::Reconnecting);
    host.log(auto_reconnect_log(reason, delay_ms));
    host.schedule_restart(generation, delay_ms);
}

/// Kotlin startDesktopMode.
pub fn start_desktop_mode(
    host: &mut dyn DesktopStartHost,
    session: &mut DesktopVpnSession,
    os: DesktopOs,
    request_generation: u64,
    is_restart: bool,
) {
    host.set_status(if is_restart {
        DesktopVpnStatus::Reconnecting
    } else {
        DesktopVpnStatus::Connecting
    });

    let location = match host.active_location().map(|l| l.normalized()) {
        Some(l) if l.is_complete() => l,
        _ => {
            host.set_status(DesktopVpnStatus::Error(String::from(NO_ACTIVE_LOCATION)));
            host.log(String::from(ADD_VALID_LOCATION_LOG));
            return;
        }
    };

    // Another VPN client owning the adapter (or the system proxy) looks exactly like a broken
    // YPtun: ask about it before anything is started.
    host.resolve_conflicting_vpn_clients();

    let error = match try_start(host, session, os, request_generation, &location) {
        Ok(mode) => {
            host.set_status(DesktopVpnStatus::Connected);
            session.auto_reconnect = false;
            session.auto_reconnect_attempt = 0;
            host.start_watchdog(request_generation);
            host.log(String::from(connected_log(mode)));
            return;
        }
        Err(e) => e,
    };

    if error.is_cancelled() {
        host.log(String::from(START_CANCELLED_LOG));
    } else {
        host.log(format!("Desktop start failed: {}", error.message()));
    }

    let cleanup_mode = session.active_mode.unwrap_or_else(|| DesktopMode::current(os));
    match cleanup_mode {
        // The standalone bridge's root wrapper tears itself down; olcRTC's hev and routes are
        // cleaned in one pkexec call by stop_olcrtc_process below.
        DesktopMode::LinuxTun => host.release_linux_tun(),
        DesktopMode::WindowsTun => {
            if let Err(e) = host.stop_windows_tun() {
                host.log(format!("Windows TUN cleanup failed: {}", e));
            }
        }
        DesktopMode::SystemProxy => {
            if let Err(e) = host.restore_system_proxy() {
                host.log(format!("Proxy restore failed: {}", e));
            }
            host.stop_http_proxy_bridge();
        }
    }

    host.stop_pac_server();
    if let Err(e) = host.stop_engines() {
        host.log(format!("Engine stop failed: {}", e));
    }
    host.unbind_outbound_interface();
    session.engine_location = None;
    host.stop_olcrtc_process(cleanup_mode == DesktopMode::LinuxTun);
    host.delete_olcrtc_config();

    if !error.is_cancelled() && request_generation == host.generation() {
        if session.auto_reconnect {
            schedule_auto_reconnect(host, session, request_generation, Some(error.message()));
        } else {
            host.set_status(DesktopVpnStatus::Error(String::from(error.message())));
        }
    }
}

fn try_start(
    host: &mut dyn DesktopStartHost,
    session: &mut DesktopVpnSession,
    os: DesktopOs,
    request_generation: u64,
    location: &LocationConfig,
) -> Result<DesktopMode, DesktopStartError> {
    let mode = desktop_mode_for(os, host.proxy_mode());
    session.active_mode = Some(mode);

    let configured = host.configured_socks().normalized(os);
    let mut logs: Vec<String> = Vec::new();
    let socks = {
        let probe = |port: i32| host.port_taken(port);
        session_socks_settings(mode, &configured, probe, &mut |m: String| logs.push(m))
    };
    for m in logs {
        host.log(m);
    }
    session.session_socks = Some(socks.clone());
    if drops_proxy_auth(mode, &configured) {
        host.log(String::from(PROXY_MODE_NO_AUTH_LOG));
    }

    if mode == DesktopMode::WindowsTun {
        host.ensure_administrator_or_request_restart()?;
    }
    if mode == DesktopMode::LinuxTun {
        // Before the interface probe and the cores: a leftover TUN would capture both.
        host.clear_stale_linux_tun()?;
    }

    let physical_index = if mode != DesktopMode::SystemProxy {
        host.physical_interface_index()
    } else {
        0
    };
    let pinning = interface_pinning(mode, location.engine, physical_index);
    host.bind_outbound_interface(pinning.index, pinning.pin_udp);
    if let Some(m) = pinning.log {
        host.log(m);
    }

    let use_engine = use_engine_controller(location, host.engine_controller_supported());
    let bypass_server_ips = if needs_bypass_server_ips(use_engine, mode) {
        host.resolve_bypass_server_ips(location)
    } else {
        Vec::new()
    };

    if use_engine {
        session.engine_location = Some(location.clone());
        let split = host.split_tunnel();
        let wants_in_core_tun = mode == DesktopMode::WindowsTun;
        let tun_exclude_addresses = if wants_in_core_tun {
            bypass_server_ips.clone()
        } else {
            Vec::new()
        };
        if !tun_exclude_addresses.is_empty() {
            host.log(format!(
                "Excluding {} upstream address(es) from the in-core TUN",
                tun_exclude_addresses.len()
            ));
        }
        let request = EngineStartRequest {
            location: location.clone(),
            listen_host: socks.host.clone(),
            listen_port: socks.port,
            socks_username: socks.username.clone(),
            socks_password: socks.password.clone(),
            tun_via_sing_box: wants_in_core_tun,
            split_tunnel_processes: split_tunnel_processes(&split),
            split_tunnel_mode: split.mode,
            tun_exclude_addresses,
        };
        host.start_engine(&request)?;
    } else {
        host.start_olcrtc(location, &socks, mode == DesktopMode::LinuxTun)?;
        host.wait_for_olcrtc_ready(socks.port, request_generation)?;
    }

    if request_generation != host.generation() {
        return Err(DesktopStartError::Cancelled(String::from(START_SUPERSEDED)));
    }

    let tun_request = TunStartRequest {
        socks_port: socks.port,
        request_generation,
        bypass_server_ips,
        socks_username: socks.username.clone(),
        socks_password: socks.password.clone(),
        udp_over_tcp: mode == DesktopMode::LinuxTun && udp_over_tcp(location.engine),
    };
    match mode {
        DesktopMode::LinuxTun => host.start_linux_tun(&tun_request)?,
        DesktopMode::WindowsTun => {
            if host.tun_handled_in_core() {
                host.log(String::from(WINDOWS_TUN_IN_CORE_LOG));
            } else {
                host.start_windows_tun(&tun_request)?;
            }
        }
        DesktopMode::SystemProxy => host.start_system_proxy(&socks, request_generation)?,
    }
    Ok(mode)
}
