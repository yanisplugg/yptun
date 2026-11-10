//! Typed UniFFI surface shared by the native iOS and macOS clients.
//!
//! Swift owns NetworkExtension and engine calls. Rust owns the deterministic connection state
//! machine, so app, widget, and packet-tunnel entry points all make the same reconnect decisions.

use std::sync::{Arc, Mutex, MutexGuard};

use yptun_core::engine::machine::{Action, ConnectionMachine, Event, RetryPolicy, Status};
use yptun_core::location::{LocationBundleV4, LocationConfig, BUNDLE_VERSION};
use yptun_core::model::EngineType;
use yptun_core::routing::TrafficSettings;

use crate::{from_json, FfiError};

const TUN_IPV4_ADDRESS: &str = "198.51.100.1";
const TUN_IPV4_REMOTE: &str = "198.51.100.2";
const TUN_IPV4_SUBNET_MASK: &str = "255.255.255.0";
const TUN_IPV6_ADDRESS: &str = "fdfe:dcba:9876::1";
const MAPDNS_ADDRESS: &str = "1.1.1.1";

const LAN_ROUTES: [(&str, &str); 7] = [
    ("10.0.0.0", "255.0.0.0"),
    ("127.0.0.0", "255.0.0.0"),
    ("169.254.0.0", "255.255.0.0"),
    ("172.16.0.0", "255.240.0.0"),
    ("192.168.0.0", "255.255.0.0"),
    ("224.0.0.0", "240.0.0.0"),
    ("255.255.255.255", "255.255.255.255"),
];

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleIpv4Route {
    pub address: String,
    pub subnet_mask: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleTunnelPlan {
    pub tunnel_remote_address: String,
    pub mtu: i32,
    pub ipv4_address: String,
    pub ipv4_subnet_mask: String,
    pub include_default_ipv4_route: bool,
    pub excluded_ipv4_routes: Vec<AppleIpv4Route>,
    pub ipv6_address: String,
    pub ipv6_prefix_length: i32,
    pub include_default_ipv6_route: bool,
    pub dns_servers: Vec<String>,
    pub dns_match_domains: Vec<String>,
}

/// The platform-neutral values Swift applies to `NEPacketTunnelNetworkSettings`.
#[uniffi::export]
pub fn apple_tunnel_plan(mtu: i32, bypass_lan: bool) -> AppleTunnelPlan {
    AppleTunnelPlan {
        tunnel_remote_address: TUN_IPV4_REMOTE.into(),
        mtu: mtu.clamp(1_280, 9_000),
        ipv4_address: TUN_IPV4_ADDRESS.into(),
        ipv4_subnet_mask: TUN_IPV4_SUBNET_MASK.into(),
        include_default_ipv4_route: true,
        excluded_ipv4_routes: if bypass_lan {
            LAN_ROUTES
                .into_iter()
                .map(|(address, subnet_mask)| AppleIpv4Route {
                    address: address.into(),
                    subnet_mask: subnet_mask.into(),
                })
                .collect()
        } else {
            Vec::new()
        },
        // IPv6 is always captured. When the selected strategy is IPv4-only, the bridge omits its
        // IPv6 address and blackholes packets rather than allowing them to bypass the VPN.
        ipv6_address: TUN_IPV6_ADDRESS.into(),
        ipv6_prefix_length: 126,
        include_default_ipv6_route: true,
        dns_servers: vec![MAPDNS_ADDRESS.into()],
        dns_match_domains: vec![String::new()],
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleBridgePlan {
    pub yaml: String,
    pub tcp_only_udp: bool,
    pub drop_ipv6: bool,
    pub slow_tunnel: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleLocationMigration {
    pub normalized_json: String,
    pub source_version: i32,
    pub target_version: i32,
    pub locations_before: u32,
    pub locations_after: u32,
    pub active_location_id: Option<String>,
}

/// Validates and normalizes a Kotlin-era location bundle before Swift replaces the original file.
/// Swift owns the backup and atomic rename; Rust owns wire compatibility and model normalization.
#[uniffi::export]
pub fn apple_migrate_location_bundle(
    bundle_json: String,
) -> Result<AppleLocationMigration, FfiError> {
    let source: LocationBundleV4 = from_json("LocationBundle", &bundle_json)?;
    let source_version = source.version;
    let locations_before = source.locations.len() as u32;
    let normalized = source.normalized();
    let locations_after = normalized.locations.len() as u32;
    let active_location_id = normalized.active_location_id.clone();
    let normalized_json = serde_json::to_string_pretty(&normalized)
        .map_err(|error| FfiError::Json(error.to_string()))?;
    Ok(AppleLocationMigration {
        normalized_json,
        source_version,
        target_version: BUNDLE_VERSION,
        locations_before,
        locations_after,
        active_location_id,
    })
}

fn yaml_single_quoted(value: &str) -> String {
    value.replace('\'', "''")
}

/// Builds the deterministic hev-socks5-tunnel configuration formerly assembled in Kotlin.
#[uniffi::export]
pub fn apple_bridge_plan(
    location_json: String,
    traffic_json: String,
    socks_port: i32,
    socks_username: String,
    socks_password: String,
) -> Result<AppleBridgePlan, FfiError> {
    let location: LocationConfig =
        from_json::<LocationConfig>("LocationConfig", &location_json)?.normalized();
    let traffic = from_json::<TrafficSettings>("TrafficSettings", &traffic_json)?.normalized();
    if !(1..=65_535).contains(&socks_port) {
        return Err(FfiError::Invalid(format!(
            "SOCKS port {socks_port} is out of range"
        )));
    }

    let tcp_only_udp = matches!(
        location.engine,
        EngineType::Stealth | EngineType::MasterDns | EngineType::OpenFlux
    );
    let slow_tunnel = matches!(
        location.engine,
        EngineType::MasterDns | EngineType::OpenFlux
    );
    let drop_ipv6 = location.engine == EngineType::VkTurn
        || matches!(
            traffic.domain_strategy.as_str(),
            "ipv4_only" | "prefer_ipv4"
        );
    let ipv6 = if drop_ipv6 {
        "# ipv6 disabled (IPv4 only/preferred)".to_string()
    } else {
        format!("ipv6: '{TUN_IPV6_ADDRESS}'")
    };
    let connect_timeout = if slow_tunnel { 30_000 } else { 10_000 };
    let username = yaml_single_quoted(&socks_username);
    let password = yaml_single_quoted(&socks_password);
    let yaml = format!(
        "tunnel:\n  mtu: {mtu}\n  ipv4: {ipv4}\n  {ipv6}\n\nsocks5:\n  address: 127.0.0.1\n  port: {socks_port}\n  udp: '{udp}'\n  pipeline: false\n  username: '{username}'\n  password: '{password}'\n\nmapdns:\n  address: {mapdns}\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n\nmisc:\n  task-stack-size: 24576\n  tcp-buffer-size: 4096\n  max-session-count: 1200\n  connect-timeout: {connect_timeout}\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n",
        mtu = traffic.mtu,
        ipv4 = TUN_IPV4_ADDRESS,
        udp = if tcp_only_udp { "tcp" } else { "udp" },
        mapdns = MAPDNS_ADDRESS,
    );

    Ok(AppleBridgePlan {
        yaml,
        tcp_only_udp,
        drop_ipv6,
        slow_tunnel,
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleStatusKind {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Stopping,
    NeedsUser,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleConnectionStatus {
    pub kind: AppleStatusKind,
    pub attempt: u32,
    pub message: Option<String>,
}

impl From<&Status> for AppleConnectionStatus {
    fn from(status: &Status) -> Self {
        match status {
            Status::Disconnected => Self::plain(AppleStatusKind::Disconnected),
            Status::Connecting => Self::plain(AppleStatusKind::Connecting),
            Status::Connected => Self::plain(AppleStatusKind::Connected),
            Status::Reconnecting { attempt } => Self {
                kind: AppleStatusKind::Reconnecting,
                attempt: *attempt,
                message: None,
            },
            Status::Stopping => Self::plain(AppleStatusKind::Stopping),
            Status::NeedsUser => Self::plain(AppleStatusKind::NeedsUser),
            Status::Error(message) => Self {
                kind: AppleStatusKind::Error,
                attempt: 0,
                message: Some(message.clone()),
            },
        }
    }
}

impl AppleConnectionStatus {
    fn plain(kind: AppleStatusKind) -> Self {
        Self {
            kind,
            attempt: 0,
            message: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleActionKind {
    StartEngines,
    StopEngines,
    ScheduleRetry,
    CancelRetry,
    RequestVkLink,
    PublishStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleConnectionAction {
    pub kind: AppleActionKind,
    pub delay_ms: Option<u64>,
    pub status: Option<AppleConnectionStatus>,
}

impl From<Action> for AppleConnectionAction {
    fn from(action: Action) -> Self {
        match action {
            Action::StartEngines => Self::plain(AppleActionKind::StartEngines),
            Action::StopEngines => Self::plain(AppleActionKind::StopEngines),
            Action::ScheduleRetry { delay_ms } => Self {
                kind: AppleActionKind::ScheduleRetry,
                delay_ms: Some(delay_ms),
                status: None,
            },
            Action::CancelRetry => Self::plain(AppleActionKind::CancelRetry),
            Action::RequestVkLink => Self::plain(AppleActionKind::RequestVkLink),
            Action::PublishStatus(status) => Self {
                kind: AppleActionKind::PublishStatus,
                delay_ms: None,
                status: Some((&status).into()),
            },
        }
    }
}

impl AppleConnectionAction {
    fn plain(kind: AppleActionKind) -> Self {
        Self {
            kind,
            delay_ms: None,
            status: None,
        }
    }
}

/// Stateful connection decisions shared by the app and packet-tunnel extension.
///
/// The object is internally synchronized because UniFFI may call it from Swift tasks on different
/// executors. Actions are returned as a batch and must be executed by Swift in array order.
#[derive(uniffi::Object)]
pub struct AppleConnectionRuntime {
    machine: Mutex<ConnectionMachine>,
}

impl AppleConnectionRuntime {
    fn lock(&self) -> MutexGuard<'_, ConnectionMachine> {
        self.machine
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }

    fn handle(&self, event: Event) -> Vec<AppleConnectionAction> {
        self.lock()
            .handle(event)
            .into_iter()
            .map(Into::into)
            .collect()
    }
}

#[uniffi::export]
impl AppleConnectionRuntime {
    #[uniffi::constructor]
    pub fn new(base_retry_ms: u64, max_retry_ms: u64, max_attempts: u32) -> Arc<Self> {
        let policy = RetryPolicy {
            base_delay_ms: base_retry_ms.max(1),
            max_delay_ms: max_retry_ms.max(base_retry_ms.max(1)),
            max_attempts,
        };
        Arc::new(Self {
            machine: Mutex::new(ConnectionMachine::new(policy)),
        })
    }

    pub fn status(&self) -> AppleConnectionStatus {
        self.lock().status().into()
    }

    pub fn start(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Start)
    }

    pub fn stop(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Stop)
    }

    pub fn ready(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Ready)
    }

    pub fn start_failed(&self, message: String, fatal: bool) -> Vec<AppleConnectionAction> {
        self.handle(Event::StartFailed { message, fatal })
    }

    /// A plan requested a VK call link that only the foreground app can obtain.
    pub fn vk_link_required(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::PlanRejected(
            yptun_core::engine::plan::PlanError::VkLinkRequired,
        ))
    }

    pub fn engine_died(&self, message: String) -> Vec<AppleConnectionAction> {
        self.handle(Event::EngineDied { message })
    }

    pub fn network_changed(&self, available: bool) -> Vec<AppleConnectionAction> {
        self.handle(Event::NetworkChanged { available })
    }

    pub fn server_switched(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::ServerSwitched)
    }

    pub fn vk_link_provided(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::VkLinkProvided)
    }

    pub fn retry_timer_fired(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::RetryTimer)
    }

    pub fn engines_stopped(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Stopped)
    }
}
