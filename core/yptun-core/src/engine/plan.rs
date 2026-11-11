//! Launch plan: the pure decisions every platform's engine controller used to make on its own
//! (IosEngineController / DesktopEngineController / OlcboxVpnService). Which engines to raise, on
//! which local ports, which proxy core (sing-box or Xray), whether the cascade hop is usable.
//!
//! Nothing here touches the network or the filesystem: the one I/O-dependent question ("are the geo
//! databases available for Xray?") is asked lazily through a closure, only when the answer matters.

use crate::location::LocationConfig;
use crate::model::{
    EngineType, ProxyCore, ProxyProfile, TYPE_AMNEZIAWG, TYPE_HYSTERIA2, TYPE_NAIVE, TYPE_OPENVPN,
    TYPE_SHADOWSOCKS, TYPE_TROJAN, TYPE_TRUSTTUNNEL, TYPE_VLESS, TYPE_VMESS,
};
use crate::routing::{RoutingProfile, TrafficSettings};
use crate::util::is_blank;

/// Proxy types xray-core can serve from typed fields (same set on every platform).
pub const XRAY_SUPPORTED_TYPES: [&str; 4] = [TYPE_VLESS, TYPE_VMESS, TYPE_TROJAN, TYPE_SHADOWSOCKS];

pub fn is_xray_typed(kind: &str) -> bool {
    XRAY_SUPPORTED_TYPES.contains(&kind)
}

fn filled(s: &Option<String>) -> bool {
    s.as_deref().is_some_and(|v| !is_blank(v))
}

// ---------------------------------------------------------------------------------------------
// Ports

/// Local port layout derived from the main SOCKS port. The offsets are shared by all platforms:
/// +1 olcRTC chain (and Xray's cascade loopback), +2 AmneziaWG / WARP / VK-TURN local SOCKS,
/// +5 Trust Tunnel, +6..+7 the sing-box front placed before Xray on desktop.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PortLayout {
    pub socks: i32,
}

impl PortLayout {
    pub const CHAIN_OFFSET: i32 = 1;
    pub const AWG_OFFSET: i32 = 2;
    pub const TRUST_TUNNEL_OFFSET: i32 = 5;
    pub const SINGBOX_FRONT_OFFSET: i32 = 6;
    /// Highest offset in use (the sing-box front reserves +6 and +7).
    pub const MAX_OFFSET: i32 = 7;

    pub const fn new(socks: i32) -> Self {
        Self { socks }
    }

    /// True when every derived port fits into 1..=65535.
    pub fn is_valid(&self) -> bool {
        self.socks >= 1 && self.socks + Self::MAX_OFFSET <= 65535
    }

    pub fn olcrtc_chain(&self) -> i32 {
        self.socks + Self::CHAIN_OFFSET
    }

    pub fn awg_local(&self) -> i32 {
        self.socks + Self::AWG_OFFSET
    }

    pub fn trust_tunnel(&self) -> i32 {
        self.socks + Self::TRUST_TUNNEL_OFFSET
    }

    pub fn singbox_front(&self) -> i32 {
        self.socks + Self::SINGBOX_FRONT_OFFSET
    }
}

// ---------------------------------------------------------------------------------------------
// Platform capabilities

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Platform {
    Android,
    Ios,
    MacOs,
    Desktop,
}

/// What a platform build can actually run. Kept as data so a new platform (or a slimmer iOS
/// Network Extension build) is a preset, not another copy of the controller.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlatformCaps {
    pub platform: Platform,
    pub open_flux: bool,
    pub snolc: bool,
    pub trust_tunnel: bool,
    /// NaïveProxy outbound (the iOS cores are built without `with_naive_outbound`).
    pub naive: bool,
    /// With both the server core and the app-wide core on "Auto", prefer Xray for the types it can
    /// serve (Happ-compatible behaviour). iOS does this, desktop does not.
    pub xray_when_auto: bool,
}

impl PlatformCaps {
    pub const fn ios() -> Self {
        Self { platform: Platform::Ios, open_flux: false, snolc: false, trust_tunnel: false, naive: false, xray_when_auto: true }
    }

    /// Same restrictions as iOS until the macOS Swift client says otherwise.
    pub const fn macos() -> Self {
        Self { platform: Platform::MacOs, ..Self::ios() }
    }

    pub const fn desktop() -> Self {
        Self { platform: Platform::Desktop, open_flux: true, snolc: true, trust_tunnel: true, naive: true, xray_when_auto: false }
    }

    pub const fn android() -> Self {
        Self { platform: Platform::Android, open_flux: true, snolc: true, trust_tunnel: true, naive: true, xray_when_auto: false }
    }

    pub const fn for_platform(platform: Platform) -> Self {
        match platform {
            Platform::Android => Self::android(),
            Platform::Ios => Self::ios(),
            Platform::MacOs => Self::macos(),
            Platform::Desktop => Self::desktop(),
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Errors

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PlanError {
    /// Standard/Chain without a complete main proxy.
    NoProxy,
    EngineUnsupported { engine: EngineType, platform: Platform },
    ProxyTypeUnsupported { kind: String, platform: Platform },
    /// VK-TURN needs a fresh VK call link; the UI must ask the user (from a widget: notify "open the app").
    VkLinkRequired,
    /// The SOCKS port leaves no room for the derived ports.
    BadPort(i32),
}

fn platform_name(p: Platform) -> &'static str {
    match p {
        Platform::Android => "Android",
        Platform::Ios => "iOS",
        Platform::MacOs => "macOS",
        Platform::Desktop => "ПК",
    }
}

impl PlanError {
    /// User-facing text (same wording the Kotlin controllers threw).
    pub fn message(&self) -> String {
        match self {
            Self::NoProxy => "No proxy configured".into(),
            Self::EngineUnsupported { engine: EngineType::OpenFlux, platform } => {
                format!("OpenFlux на {} пока не поддерживается", platform_name(*platform))
            }
            Self::EngineUnsupported { engine: EngineType::Snolc, platform } => {
                format!("snolc на {} не поддерживается", platform_name(*platform))
            }
            Self::EngineUnsupported { engine, platform } => {
                format!("{engine:?} на {} не поддерживается", platform_name(*platform))
            }
            Self::ProxyTypeUnsupported { kind, platform } => {
                let name = match kind.as_str() {
                    TYPE_TRUSTTUNNEL => "Trust Tunnel",
                    TYPE_NAIVE => "NaïveProxy",
                    other => other,
                };
                format!("{name} на {} пока не поддерживается", platform_name(*platform))
            }
            Self::VkLinkRequired => "Нужна ссылка на звонок VK — откройте приложение".into(),
            Self::BadPort(p) => format!("SOCKS port {p} is out of range"),
        }
    }
}

impl std::fmt::Display for PlanError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message())
    }
}

impl std::error::Error for PlanError {}

/// Rejects what the platform cannot run, before anything is started.
pub fn check_supported(location: &LocationConfig, caps: &PlatformCaps) -> Result<(), PlanError> {
    let unsupported = |engine| Err(PlanError::EngineUnsupported { engine, platform: caps.platform });
    match location.engine {
        EngineType::OpenFlux if !caps.open_flux => return unsupported(EngineType::OpenFlux),
        EngineType::Snolc if !caps.snolc => return unsupported(EngineType::Snolc),
        _ => {}
    }
    if matches!(location.engine, EngineType::Standard | EngineType::Chain) {
        if let Some(p) = &location.proxy {
            let bad = (p.kind == TYPE_TRUSTTUNNEL && !caps.trust_tunnel) || (p.kind == TYPE_NAIVE && !caps.naive);
            if bad {
                return Err(PlanError::ProxyTypeUnsupported { kind: p.kind.clone(), platform: caps.platform });
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// Cascade (2nd proxy)

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CascadeNote {
    /// No second proxy configured.
    None,
    /// Exit via the 2nd proxy, dialed through the main.
    Active,
    /// Exit via the 2nd proxy over the user's verbatim Xray config.
    ActiveOverRaw,
    /// The 2nd proxy is a client tunnel (AWG/WG/Hy2…) and cannot be chained over a raw Xray config.
    NotChainableOnRaw,
    /// The 2nd proxy points at the same node as the main — a cascade into itself is impossible.
    DroppedSameNode,
    /// A 2nd proxy is set but its link is incomplete/unparsed.
    DroppedIncomplete,
}

impl CascadeNote {
    pub fn log_line(&self, main: &ProxyProfile, second: Option<&ProxyProfile>) -> Option<String> {
        let second_name = second.map(ProxyProfile::display_name).unwrap_or_default();
        Some(match self {
            Self::None => return None,
            Self::Active => format!("Cascade: exit via 2nd proxy '{second_name}' over main '{}'", main.display_name()),
            Self::ActiveOverRaw => format!("Cascade: exit via 2nd proxy '{second_name}' over the custom Xray config"),
            Self::NotChainableOnRaw => format!(
                "WARNING: the 2nd proxy is a '{}' CLIENT TUNNEL, not an Xray exit outbound — it cannot cascade over a custom Xray config and is ignored.",
                second.map(|s| s.kind.as_str()).unwrap_or("")
            ),
            Self::DroppedSameNode => {
                "2nd (cascade) proxy is the same node as the main — a cascade into itself is impossible; ignoring it (exit via the main)".into()
            }
            Self::DroppedIncomplete => {
                "Cascade: a 2nd proxy IS set but its link is incomplete/unparsed — dropped, exit via the 1st proxy".into()
            }
        })
    }
}

/// Picks the usable 2nd proxy. `None` + a note explaining why when it is dropped.
pub fn resolve_second(main: &ProxyProfile, proxy2: Option<&ProxyProfile>) -> (Option<ProxyProfile>, CascadeNote) {
    let Some(second) = proxy2 else { return (None, CascadeNote::None) };
    if !second.is_complete() {
        return (None, CascadeNote::DroppedIncomplete);
    }
    if main.is_complete() && main.is_same_node_as(second) {
        return (None, CascadeNote::DroppedSameNode);
    }
    let note = if filled(&main.raw_xray_config) {
        if is_xray_typed(&second.kind) { CascadeNote::ActiveOverRaw } else { CascadeNote::NotChainableOnRaw }
    } else {
        CascadeNote::Active
    };
    (Some(second.clone()), note)
}

// ---------------------------------------------------------------------------------------------
// Proxy core choice

/// Why the final core was chosen — for the connection log.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoreReason {
    /// Per-server choice / app-wide choice / default (LocationConfig::resolved_core).
    Resolved,
    /// AmneziaWG, Hysteria2, Naive, Trust Tunnel: UDP-capable local tunnels only sing-box carries.
    LocalUdpTunnel,
    RawXrayConfig,
    RoutingProfileDnsHosts,
    RuDomainBlocklist,
    /// Both choices on Auto and the platform prefers Xray (iOS).
    HappCompatible,
    /// Xray wanted the geo databases for the routing profile and they are unavailable.
    GeoUnavailable,
    /// The cascade hop needs xhttp / Xray-only features.
    SecondRequiresXray,
}

impl CoreReason {
    pub fn log_line(&self, profile: &ProxyProfile) -> Option<String> {
        Some(match self {
            Self::Resolved => return None,
            Self::LocalUdpTunnel => format!("{} outbound: native sing-box (QUIC allowed)", profile.kind),
            Self::RawXrayConfig => "Raw Xray config present → forcing Xray core".into(),
            Self::RoutingProfileDnsHosts => "Switching to Xray core for routing profile (native domain:/geoip: matching)".into(),
            Self::RuDomainBlocklist => "Switching to Xray core for RU-domain blocklist".into(),
            Self::HappCompatible => format!("Using Xray core for {} (Happ-compatible)", profile.kind),
            Self::GeoUnavailable => "Geo databases unavailable for Xray → using sing-box for routing".into(),
            Self::SecondRequiresXray => "Second (cascade) proxy uses xhttp → forcing Xray core".into(),
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CoreChoice {
    /// Never `Auto`.
    pub core: ProxyCore,
    pub reason: CoreReason,
}

/// Types that run as a local UDP-capable tunnel and therefore always on sing-box.
pub fn forces_singbox(kind: &str) -> bool {
    matches!(kind, TYPE_AMNEZIAWG | TYPE_HYSTERIA2 | TYPE_NAIVE | TYPE_TRUSTTUNNEL | TYPE_OPENVPN)
}

pub struct CoreInputs<'a> {
    pub location: &'a LocationConfig,
    /// The main profile actually dialed (after AWG/Trust Tunnel are swapped for their local SOCKS).
    pub profile: &'a ProxyProfile,
    pub second: Option<&'a ProxyProfile>,
    pub routing_profile: Option<&'a RoutingProfile>,
    pub traffic: &'a TrafficSettings,
    pub global_core: ProxyCore,
    pub caps: &'a PlatformCaps,
}

/// The sing-box/Xray decision shared by every controller. `geo_available` is called at most once,
/// and only when Xray would need the geo databases for the routing profile.
pub fn choose_core(i: &CoreInputs<'_>, geo_available: impl FnOnce() -> bool) -> CoreChoice {
    let p = i.profile;
    let main_kind = i.location.proxy.as_ref().map_or(p.kind.as_str(), |m| m.kind.as_str());
    let (mut core, mut reason) = if forces_singbox(main_kind) {
        (ProxyCore::SingBox, CoreReason::LocalUdpTunnel)
    } else {
        let c = match i.location.resolved_core(i.global_core) {
            ProxyCore::Auto => ProxyCore::SingBox,
            c => c,
        };
        (c, CoreReason::Resolved)
    };

    let profile_wants_xray = i.routing_profile.is_some_and(|r| !r.dns_hosts.is_empty());
    if filled(&p.raw_xray_config) {
        if core != ProxyCore::Xray {
            reason = CoreReason::RawXrayConfig;
        }
        core = ProxyCore::Xray;
    }
    let happ = i.caps.xray_when_auto && i.location.core == ProxyCore::Auto && i.global_core == ProxyCore::Auto;
    if core == ProxyCore::SingBox
        && reason != CoreReason::LocalUdpTunnel
        && !filled(&p.raw_outbound)
        && is_xray_typed(&p.kind)
        && (i.traffic.block_ru_domains || profile_wants_xray || happ)
    {
        core = ProxyCore::Xray;
        reason = if profile_wants_xray {
            CoreReason::RoutingProfileDnsHosts
        } else if i.traffic.block_ru_domains {
            CoreReason::RuDomainBlocklist
        } else {
            CoreReason::HappCompatible
        };
    }
    if core == ProxyCore::Xray
        && i.routing_profile.is_some_and(RoutingProfile::needs_geo_files)
        && !filled(&p.raw_xray_config)
        && !p.requires_xray()
        && !filled(&p.raw_outbound)
        && !geo_available()
    {
        core = ProxyCore::SingBox;
        reason = CoreReason::GeoUnavailable;
    }
    if i.second.is_some_and(ProxyProfile::requires_xray) && core != ProxyCore::Xray {
        core = ProxyCore::Xray;
        reason = CoreReason::SecondRequiresXray;
    }
    CoreChoice { core, reason }
}

/// sing-box must block QUIC unless the outbound itself carries UDP/QUIC.
pub fn singbox_blocks_quic(profile: &ProxyProfile) -> bool {
    !(profile.kind == TYPE_AMNEZIAWG
        || profile.kind == TYPE_HYSTERIA2
        || profile.kind == TYPE_TRUSTTUNNEL
        || profile.kind == TYPE_OPENVPN
        || (profile.kind == TYPE_NAIVE && profile.naive_quic))
}

// ---------------------------------------------------------------------------------------------
// Engine topology

/// One process/runtime the platform has to raise, in start order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Step {
    /// olcRTC room serving SOCKS on `port`.
    OlcRtc { port: i32 },
    /// AmneziaWG/WireGuard local SOCKS (awgproxy) on `port`.
    AmneziaWg { port: i32 },
    /// Trust Tunnel client in SOCKS-only mode on `port`.
    TrustTunnel { port: i32 },
    /// VK-TURN relay (freeturn / WDTT / csqtt) whose local endpoint lives on `port`.
    VkTurn { port: i32 },
    /// MasterDNS tunnel serving SOCKS on `port`.
    MasterDns { port: i32 },
    OpenFlux { port: i32 },
    Snolc { port: i32 },
    /// sing-box or Xray serving the main SOCKS. `upstream_port` is the local hop it dials through.
    ProxyCore { port: i32, upstream_port: Option<i32> },
}

impl Step {
    pub fn port(&self) -> i32 {
        match self {
            Self::OlcRtc { port }
            | Self::AmneziaWg { port }
            | Self::TrustTunnel { port }
            | Self::VkTurn { port }
            | Self::MasterDns { port }
            | Self::OpenFlux { port }
            | Self::Snolc { port }
            | Self::ProxyCore { port, .. } => *port,
        }
    }
}

/// The ordered list of runtimes for a location. The core *kind* (sing-box vs Xray) is decided
/// separately by [`choose_core`] because it depends on settings and geo availability.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Topology {
    pub ports: PortLayout,
    pub steps: Vec<Step>,
}

impl Topology {
    pub fn uses_proxy_core(&self) -> bool {
        self.steps.iter().any(|s| matches!(s, Step::ProxyCore { .. }))
    }

    /// Runtimes to stop, in reverse start order.
    pub fn stop_order(&self) -> Vec<Step> {
        self.steps.iter().rev().cloned().collect()
    }
}

fn proxy_usable(location: &LocationConfig) -> Option<&ProxyProfile> {
    location.proxy.as_ref().filter(|p| location.proxy_enabled && p.is_complete())
}

/// Builds the start order for `location` on `socks_port`.
pub fn topology(location: &LocationConfig, socks_port: i32, caps: &PlatformCaps) -> Result<Topology, PlanError> {
    let ports = PortLayout::new(socks_port);
    if !ports.is_valid() {
        return Err(PlanError::BadPort(socks_port));
    }
    check_supported(location, caps)?;
    let mut steps = Vec::new();
    let main = socks_port;
    match location.engine {
        EngineType::Stealth => steps.push(Step::OlcRtc { port: main }),
        EngineType::Standard | EngineType::Chain => {
            let profile = location.proxy.as_ref().filter(|p| p.is_complete()).ok_or(PlanError::NoProxy)?;
            let mut upstream = None;
            if location.engine == EngineType::Chain {
                steps.push(Step::OlcRtc { port: ports.olcrtc_chain() });
                upstream = Some(ports.olcrtc_chain());
            }
            if profile.kind == TYPE_AMNEZIAWG {
                steps.push(Step::AmneziaWg { port: ports.awg_local() });
            } else if profile.kind == TYPE_TRUSTTUNNEL {
                steps.push(Step::TrustTunnel { port: ports.trust_tunnel() });
            }
            steps.push(Step::ProxyCore { port: main, upstream_port: upstream });
        }
        EngineType::VkTurn => {
            if location.needs_vk_link() {
                return Err(PlanError::VkLinkRequired);
            }
            steps.push(Step::VkTurn { port: ports.awg_local() });
            steps.push(Step::ProxyCore { port: main, upstream_port: Some(ports.awg_local()) });
        }
        EngineType::MasterDns => {
            if proxy_usable(location).is_some() {
                steps.push(Step::MasterDns { port: ports.olcrtc_chain() });
                steps.push(Step::ProxyCore { port: main, upstream_port: Some(ports.olcrtc_chain()) });
            } else {
                steps.push(Step::MasterDns { port: main });
            }
        }
        EngineType::OpenFlux => {
            if proxy_usable(location).is_some() {
                steps.push(Step::OpenFlux { port: ports.olcrtc_chain() });
                steps.push(Step::ProxyCore { port: main, upstream_port: Some(ports.olcrtc_chain()) });
            } else {
                steps.push(Step::OpenFlux { port: main });
            }
        }
        EngineType::Snolc => {
            if proxy_usable(location).is_some() {
                steps.push(Step::Snolc { port: ports.olcrtc_chain() });
                steps.push(Step::ProxyCore { port: main, upstream_port: Some(ports.olcrtc_chain()) });
            } else {
                steps.push(Step::Snolc { port: main });
            }
        }
    }
    Ok(Topology { ports, steps })
}
