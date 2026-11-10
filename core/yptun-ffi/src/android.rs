//! Typed UniFFI surface for the Android VPN runtime.
//!
//! Kotlin keeps Android framework work (`VpnService.Builder`, `Network`, JNI/Go engines), while the
//! decisions live in `yptun-core::android_vpn`. No packet or log line crosses this boundary.

use std::collections::BTreeSet;
use std::sync::{Arc, Mutex, MutexGuard};

use yptun_core::android_vpn as core;
use yptun_core::home::VpnStatus;

use crate::FfiError;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidMode {
    Tun,
    Proxy,
    Tproxy,
}

impl From<AndroidMode> for core::AndroidConnectionMode {
    fn from(value: AndroidMode) -> Self {
        match value {
            AndroidMode::Tun => Self::Tun,
            AndroidMode::Proxy => Self::Proxy,
            AndroidMode::Tproxy => Self::Tproxy,
        }
    }
}

impl From<core::AndroidConnectionMode> for AndroidMode {
    fn from(value: core::AndroidConnectionMode) -> Self {
        match value {
            core::AndroidConnectionMode::Tun => Self::Tun,
            core::AndroidConnectionMode::Proxy => Self::Proxy,
            core::AndroidConnectionMode::Tproxy => Self::Tproxy,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidSplitMode {
    AllApps,
    ProxySelected,
    BypassSelected,
}

impl From<AndroidSplitMode> for core::AndroidSplitTunnelMode {
    fn from(value: AndroidSplitMode) -> Self {
        match value {
            AndroidSplitMode::AllApps => Self::AllApps,
            AndroidSplitMode::ProxySelected => Self::ProxySelected,
            AndroidSplitMode::BypassSelected => Self::BypassSelected,
        }
    }
}

impl From<core::AndroidSplitTunnelMode> for AndroidSplitMode {
    fn from(value: core::AndroidSplitTunnelMode) -> Self {
        match value {
            core::AndroidSplitTunnelMode::AllApps => Self::AllApps,
            core::AndroidSplitTunnelMode::ProxySelected => Self::ProxySelected,
            core::AndroidSplitTunnelMode::BypassSelected => Self::BypassSelected,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidBridgeEngine {
    Stealth,
    MasterDns,
    OpenFlux,
    Other,
}

impl From<AndroidBridgeEngine> for core::Tun2SocksEngine {
    fn from(value: AndroidBridgeEngine) -> Self {
        match value {
            AndroidBridgeEngine::Stealth => Self::Stealth,
            AndroidBridgeEngine::MasterDns => Self::MasterDns,
            AndroidBridgeEngine::OpenFlux => Self::OpenFlux,
            AndroidBridgeEngine::Other => Self::Other,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidStatusKind {
    Connected,
    Connecting,
    Reconnecting,
    Stopping,
    Disconnected,
    Error,
}

impl From<AndroidStatusKind> for VpnStatus {
    fn from(value: AndroidStatusKind) -> Self {
        match value {
            AndroidStatusKind::Connected => Self::Connected,
            AndroidStatusKind::Connecting => Self::Connecting,
            AndroidStatusKind::Reconnecting => Self::Reconnecting,
            AndroidStatusKind::Stopping => Self::Stopping,
            AndroidStatusKind::Disconnected => Self::Disconnected,
            AndroidStatusKind::Error => Self::Error(String::new()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidStartConfig {
    pub connection_mode: AndroidMode,
    pub socks_listen_host: String,
    pub socks_listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub split_tunnel_mode: AndroidSplitMode,
    pub split_tunnel_proxy_apps: Vec<String>,
    pub split_tunnel_bypass_apps: Vec<String>,
}

impl From<AndroidStartConfig> for core::AndroidStartOptions {
    fn from(value: AndroidStartConfig) -> Self {
        Self {
            connection_mode: value.connection_mode.into(),
            socks_listen_host: value.socks_listen_host,
            socks_listen_port: value.socks_listen_port,
            socks_username: value.socks_username,
            socks_password: value.socks_password,
            split_tunnel_mode: value.split_tunnel_mode.into(),
            split_tunnel_proxy_apps: value
                .split_tunnel_proxy_apps
                .into_iter()
                .collect::<BTreeSet<_>>(),
            split_tunnel_bypass_apps: value
                .split_tunnel_bypass_apps
                .into_iter()
                .collect::<BTreeSet<_>>(),
        }
    }
}

impl From<core::AndroidStartOptions> for AndroidStartConfig {
    fn from(value: core::AndroidStartOptions) -> Self {
        Self {
            connection_mode: value.connection_mode.into(),
            socks_listen_host: value.socks_listen_host,
            socks_listen_port: value.socks_listen_port,
            socks_username: value.socks_username,
            socks_password: value.socks_password,
            split_tunnel_mode: value.split_tunnel_mode.into(),
            split_tunnel_proxy_apps: value.split_tunnel_proxy_apps.into_iter().collect(),
            split_tunnel_bypass_apps: value.split_tunnel_bypass_apps.into_iter().collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidPortConfig {
    pub socks: i32,
    pub chain_olcrtc: i32,
    pub amneziawg: i32,
    pub warp_relay: i32,
    pub tproxy: i32,
    pub http_proxy: i32,
    pub trust_tunnel: i32,
    pub sing_box_front: i32,
}

impl From<core::AndroidPortPlan> for AndroidPortConfig {
    fn from(value: core::AndroidPortPlan) -> Self {
        Self {
            socks: value.socks,
            chain_olcrtc: value.chain_olcrtc,
            amneziawg: value.amneziawg,
            warp_relay: value.warp_relay,
            tproxy: value.tproxy,
            http_proxy: value.http_proxy,
            trust_tunnel: value.trust_tunnel,
            sing_box_front: value.sing_box_front,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidSplitPlan {
    pub allowed_packages: Vec<String>,
    pub disallowed_packages: Vec<String>,
    pub require_one_allowed: bool,
}

impl From<core::SplitTunnelPlan> for AndroidSplitPlan {
    fn from(value: core::SplitTunnelPlan) -> Self {
        Self {
            allowed_packages: value.allowed_packages,
            disallowed_packages: value.disallowed_packages,
            require_one_allowed: value.require_one_allowed,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidIpPrefix {
    pub address: String,
    pub prefix: i32,
}

impl From<core::IpPrefix> for AndroidIpPrefix {
    fn from(value: core::IpPrefix) -> Self {
        Self {
            address: value.address,
            prefix: i32::from(value.prefix),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidTunnelConfig {
    pub session_name: String,
    pub blocking: bool,
    pub mtu: i32,
    pub addresses: Vec<AndroidIpPrefix>,
    pub dns_servers: Vec<String>,
    pub routes: Vec<AndroidIpPrefix>,
}

impl From<core::AndroidTunnelPlan> for AndroidTunnelConfig {
    fn from(value: core::AndroidTunnelPlan) -> Self {
        Self {
            session_name: value.session_name,
            blocking: value.blocking,
            mtu: value.mtu,
            addresses: value.addresses.into_iter().map(Into::into).collect(),
            dns_servers: value.dns_servers,
            routes: value.routes.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidTun2SocksConfig {
    pub mtu: i32,
    pub drop_ipv6: bool,
    pub socks_listen_host: String,
    pub socks_listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub engine: AndroidBridgeEngine,
}

impl From<AndroidTun2SocksConfig> for core::Tun2SocksOptions {
    fn from(value: AndroidTun2SocksConfig) -> Self {
        Self {
            mtu: value.mtu,
            drop_ipv6: value.drop_ipv6,
            socks_listen_host: value.socks_listen_host,
            socks_listen_port: value.socks_listen_port,
            socks_username: value.socks_username,
            socks_password: value.socks_password,
            engine: value.engine.into(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct AndroidTunStats {
    pub tx_packets: i64,
    pub tx_bytes: i64,
    pub rx_packets: i64,
    pub rx_bytes: i64,
}

impl From<AndroidTunStats> for core::Tun2SocksStats {
    fn from(value: AndroidTunStats) -> Self {
        Self {
            tx_packets: value.tx_packets,
            tx_bytes: value.tx_bytes,
            rx_packets: value.rx_packets,
            rx_bytes: value.rx_bytes,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct AndroidSpeed {
    pub down_bytes_per_sec: i64,
    pub up_bytes_per_sec: i64,
}

impl From<core::AndroidSpeedSample> for AndroidSpeed {
    fn from(value: core::AndroidSpeedSample) -> Self {
        Self {
            down_bytes_per_sec: value.down_bytes_per_sec,
            up_bytes_per_sec: value.up_bytes_per_sec,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AndroidRecovery {
    pub reason: String,
    pub full_restart: bool,
}

impl From<core::RecoveryRequest> for AndroidRecovery {
    fn from(value: core::RecoveryRequest) -> Self {
        Self {
            reason: value.reason,
            full_restart: value.full_restart,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct AndroidRuntimeSnapshot {
    pub vpn_interface: bool,
    pub tun2socks_thread: bool,
    pub tun2socks_alive: bool,
    pub socks_proxy: bool,
    pub core_running: bool,
    pub startup_job_active: bool,
    pub cleanup_job_active: bool,
}

impl From<AndroidRuntimeSnapshot> for core::AndroidRuntimePresence {
    fn from(value: AndroidRuntimeSnapshot) -> Self {
        Self {
            vpn_interface: value.vpn_interface,
            tun2socks_thread: value.tun2socks_thread,
            tun2socks_alive: value.tun2socks_alive,
            socks_proxy: value.socks_proxy,
            core_running: value.core_running,
            startup_job_active: value.startup_job_active,
            cleanup_job_active: value.cleanup_job_active,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct AndroidStatusEffect {
    pub connected: bool,
    pub connected_since_ms: u64,
    pub reset_speed: bool,
}

#[uniffi::export]
pub fn android_prepare_start(options: AndroidStartConfig) -> Result<AndroidStartConfig, FfiError> {
    core::apply_start_options(options.into())
        .map(Into::into)
        .map_err(FfiError::Invalid)
}

#[uniffi::export]
pub fn android_port_plan(socks_port: i32) -> AndroidPortConfig {
    core::AndroidPortPlan::from_socks(socks_port).into()
}

#[uniffi::export]
pub fn android_split_tunnel_plan(
    mode: AndroidSplitMode,
    own_package: String,
    proxy_packages: Vec<String>,
    bypass_packages: Vec<String>,
) -> Result<AndroidSplitPlan, FfiError> {
    core::split_tunnel_plan(mode.into(), &own_package, proxy_packages, bypass_packages)
        .map(Into::into)
        .map_err(FfiError::Invalid)
}

#[uniffi::export]
pub fn android_tunnel_plan(mtu: i32, bypass_lan: bool) -> AndroidTunnelConfig {
    core::android_tunnel_plan(mtu, bypass_lan).into()
}

#[uniffi::export]
pub fn android_tun2socks_yaml(options: AndroidTun2SocksConfig) -> String {
    core::tun2socks_config(&options.into())
}

#[uniffi::export]
pub fn android_olcrtc_dns(upstream_address: Option<String>) -> String {
    core::olcrtc_dns_servers(upstream_address.as_deref())
}

#[uniffi::export]
pub fn android_vkturn_streams(vk_links: String, requested_streams: i32) -> i32 {
    core::vkturn_stream_count(&vk_links, requested_streams)
}

/// One newline-joined link group per freeturn server (nested sequences are avoided in generated APIs).
#[uniffi::export]
pub fn android_partition_vk_links(vk_links: String, servers: u32) -> Vec<String> {
    core::partition_vk_links(&vk_links, servers as usize)
        .into_iter()
        .map(|links| links.join("\n"))
        .collect()
}

#[uniffi::export]
pub fn android_tproxy_teardown_script() -> String {
    core::tproxy_teardown_lines().join("\n")
}

#[uniffi::export]
pub fn android_tproxy_apply_script(uid: u32, tproxy_port: i32) -> String {
    core::tproxy_apply_script(uid, tproxy_port)
}

#[uniffi::export]
pub fn android_tproxy_failure(execution_error: Option<String>, output: String) -> Option<String> {
    core::tproxy_failure(execution_error.as_deref(), &output)
}

#[uniffi::export]
pub fn android_can_reconnect_in_place(mode: AndroidMode, runtime: AndroidRuntimeSnapshot) -> bool {
    core::can_reconnect_transport_in_place(mode.into(), runtime.into())
}

#[uniffi::export]
pub fn android_should_restart(status: AndroidStatusKind, runtime: AndroidRuntimeSnapshot) -> bool {
    core::should_restart_for_start_command(&status.into(), runtime.into())
}

#[uniffi::export]
pub fn android_status_effects(
    status: AndroidStatusKind,
    connected_since_ms: u64,
    now_ms: u64,
) -> AndroidStatusEffect {
    let effect = core::android_status_effects(&status.into(), connected_since_ms, now_ms);
    AndroidStatusEffect {
        connected: effect.connected,
        connected_since_ms: effect.connected_since_ms,
        reset_speed: effect.reset_speed,
    }
}

struct RuntimeState {
    rtc: core::RtcHealth,
    stall: core::TunStallDetector,
    speed: core::AndroidSpeedMeter,
    retry: core::ReconnectBackoff,
}

/// Small, stateful part of the service. Kotlin owns scheduling and feeds log/stat samples here.
#[derive(uniffi::Object)]
pub struct AndroidVpnRuntime {
    state: Mutex<RuntimeState>,
}

impl AndroidVpnRuntime {
    fn lock(&self) -> MutexGuard<'_, RuntimeState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl AndroidVpnRuntime {
    #[uniffi::constructor]
    pub fn new(now_ms: u64) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(RuntimeState {
                rtc: core::RtcHealth::new(now_ms),
                stall: core::TunStallDetector::default(),
                speed: core::AndroidSpeedMeter::default(),
                retry: core::ReconnectBackoff::default(),
            }),
        })
    }

    pub fn observe_rtc_line(
        &self,
        line: String,
        now_ms: u64,
        status_connected: bool,
        mode: AndroidMode,
    ) -> Option<AndroidRecovery> {
        self.lock()
            .rtc
            .observe_line(&line, now_ms, status_connected, mode.into())
            .map(Into::into)
    }

    pub fn observe_tun_stats(&self, stats: Option<AndroidTunStats>, core_running: bool) -> bool {
        self.lock()
            .stall
            .observe(stats.map(Into::into), core_running)
    }

    pub fn speed_tick(
        &self,
        stats: Option<AndroidTunStats>,
        interval_ms: u64,
    ) -> Option<AndroidSpeed> {
        self.lock()
            .speed
            .tick(stats.map(Into::into), interval_ms)
            .map(Into::into)
    }

    pub fn next_retry_delay_ms(&self, base_delay_ms: u64) -> u64 {
        self.lock().retry.next_delay_ms(base_delay_ms)
    }

    pub fn reset_recovery(&self, now_ms: u64) {
        let mut state = self.lock();
        state.rtc.reset(now_ms);
        state.stall.reset();
        state.speed.reset();
        state.retry.reset();
    }
}
