//! Typed UniFFI surface shared by the native iOS and macOS clients.
//!
//! Swift owns NetworkExtension and engine calls. Rust owns the deterministic connection state
//! machine, so app, widget, and packet-tunnel entry points all make the same reconnect decisions.

use std::sync::{Arc, Mutex, MutexGuard};

use yptun_core::engine::machine::{Action, ConnectionMachine, Event, RetryPolicy, Status};
use yptun_core::engine::plan::{
    choose_core, resolve_second, singbox_blocks_quic, CoreInputs, PlatformCaps,
};
use yptun_core::importer;
use yptun_core::location::{LocationBundleV4, LocationConfig, LocationEntry, BUNDLE_VERSION};
use yptun_core::model::{EngineType, ProxyCore, ProxyProfile, TYPE_AMNEZIAWG, TYPE_MIERU};
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::share_link;
use yptun_core::singbox::config::{self as singbox_config, SingBoxOptions};
use yptun_core::warp;
use yptun_core::xray::config::{self as xray_config, RawOptions, XrayOptions};

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

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleLocationSummary {
    pub id: String,
    pub display_name: String,
    pub detail: String,
    pub complete: bool,
    pub needs_user_action: bool,
    pub from_subscription: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleLocationCatalog {
    pub active_location_id: Option<String>,
    pub locations: Vec<AppleLocationSummary>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleLocationSelection {
    pub normalized_bundle_json: String,
    pub selected_location_json: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleLocationImport {
    pub normalized_bundle_json: String,
    pub active_location_id: Option<String>,
    pub imported_locations: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleProxyRuntimeKind {
    SingBox,
    Xray,
    Mieru,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleProxyRuntimePlan {
    pub kind: AppleProxyRuntimeKind,
    pub listen_port: i32,
    /// sing-box/Xray JSON or a complete Mieru share link.
    pub config: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleOlcRtcRuntimePlan {
    pub listen_port: i32,
    pub provider: String,
    pub transport: String,
    pub room_id: String,
    pub key_hex: String,
    pub client_id: String,
    pub vp8_fps: i32,
    pub vp8_batch: i32,
    pub sei_fps: i32,
    pub sei_batch: i32,
    pub sei_fragment_size: i32,
    pub sei_ack_timeout_ms: i32,
    pub video_width: i32,
    pub video_height: i32,
    pub video_fps: i32,
    pub video_qr_size: i32,
    pub video_qr_recovery: String,
    pub video_codec: String,
    pub video_tile_module: i32,
    pub video_tile_rs: i32,
    /// Empty for one room; otherwise the capped Apple room pool payload.
    pub rooms_json: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleTurnableRuntimePlan {
    pub config: String,
    pub listen_address: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleAmneziaWgRuntimePlan {
    pub ini_config: String,
    pub listen_address: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleAuxiliaryRuntimeKind {
    FreeTurn,
    Wdtt,
    MasterDns,
    OpenFlux,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleAuxiliaryRuntimePlan {
    pub kind: AppleAuxiliaryRuntimeKind,
    pub options_json: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleGeneratedFile {
    pub relative_path: String,
    pub contents: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleSnolcRuntimePlan {
    pub directory: String,
    pub config_path: String,
    pub files: Vec<AppleGeneratedFile>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleCsqttRuntimePlan {
    pub options_json: String,
    pub bridge_socks_address: String,
    pub client_udp_address: String,
    pub mtu: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleNativeRuntimePlan {
    pub display_name: String,
    pub tunnel: AppleTunnelPlan,
    pub bridge: AppleBridgePlan,
    pub olcrtc: Option<AppleOlcRtcRuntimePlan>,
    pub amnezia_wg: Option<AppleAmneziaWgRuntimePlan>,
    pub turnable: Option<AppleTurnableRuntimePlan>,
    pub auxiliary: Vec<AppleAuxiliaryRuntimePlan>,
    pub snolc: Option<AppleSnolcRuntimePlan>,
    pub csqtt: Option<AppleCsqttRuntimePlan>,
    pub deferred_wireguard_proxy: bool,
    pub proxy: Option<AppleProxyRuntimePlan>,
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

#[uniffi::export]
pub fn apple_validate_app_behavior(app_behavior_json: String) -> Result<(), FfiError> {
    let _: AppBehaviorSettings = from_json("AppBehaviorSettings", &app_behavior_json)?;
    Ok(())
}

/// A compact, typed view of the location bundle for Apple lists and selection controls.
/// Swift never needs to duplicate the evolving Kotlin/Rust location wire model.
#[uniffi::export]
pub fn apple_location_catalog(bundle_json: String) -> Result<AppleLocationCatalog, FfiError> {
    let bundle: LocationBundleV4 = from_json("LocationBundle", &bundle_json)?;
    let normalized = bundle.normalized();
    let locations = normalized
        .locations
        .iter()
        .map(|entry| {
            let location = entry.location();
            let detail = if !location.description.trim().is_empty() {
                location.description.trim().to_string()
            } else {
                let provider = location.provider_name();
                let transport = location.transport_name();
                match (provider.trim().is_empty(), transport.trim().is_empty()) {
                    (false, false) if provider != transport => format!("{provider} · {transport}"),
                    (false, _) => provider.to_string(),
                    (_, false) => transport.to_string(),
                    _ => String::new(),
                }
            };
            AppleLocationSummary {
                id: entry.storage_id.clone(),
                display_name: location.display_name(),
                detail,
                complete: location.is_complete(),
                needs_user_action: location.needs_vk_link(),
                from_subscription: entry
                    .subscription_url
                    .as_deref()
                    .is_some_and(|url| !url.trim().is_empty()),
            }
        })
        .collect();
    Ok(AppleLocationCatalog {
        active_location_id: normalized.active_location_id,
        locations,
    })
}

/// Returns one normalized location payload without making Swift mirror the persisted wire model.
#[uniffi::export]
pub fn apple_location_json(
    bundle_json: String,
    location_id: String,
) -> Result<Option<String>, FfiError> {
    let bundle: LocationBundleV4 = from_json("LocationBundle", &bundle_json)?;
    let normalized = bundle.normalized();
    normalized
        .locations
        .iter()
        .find(|entry| entry.storage_id == location_id)
        .map(|entry| {
            serde_json::to_string(&entry.location())
                .map_err(|error| FfiError::Json(error.to_string()))
        })
        .transpose()
}

/// Changes only the active id and returns both values Swift needs to commit and connect.
#[uniffi::export]
pub fn apple_select_location(
    bundle_json: String,
    location_id: String,
) -> Result<AppleLocationSelection, FfiError> {
    let bundle: LocationBundleV4 = from_json("LocationBundle", &bundle_json)?;
    let mut normalized = bundle.normalized();
    let entry = normalized
        .locations
        .iter()
        .find(|entry| entry.storage_id == location_id)
        .ok_or_else(|| FfiError::Invalid(format!("Unknown location id: {location_id}")))?;
    let selected_location_json = serde_json::to_string(&entry.location())
        .map_err(|error| FfiError::Json(error.to_string()))?;
    normalized.active_location_id = Some(location_id);
    let normalized_bundle_json = serde_json::to_string_pretty(&normalized)
        .map_err(|error| FfiError::Json(error.to_string()))?;
    Ok(AppleLocationSelection {
        normalized_bundle_json,
        selected_location_json,
    })
}

/// Imports pasted config text or a downloaded subscription body into the existing native bundle.
#[uniffi::export]
pub fn apple_import_locations(
    bundle_json: String,
    text: String,
    subscription_url: Option<String>,
) -> Result<AppleLocationImport, FfiError> {
    let current = if bundle_json.trim().is_empty() {
        LocationBundleV4::default()
    } else {
        from_json::<LocationBundleV4>("LocationBundle", &bundle_json)?.normalized()
    };
    let parsed = importer::parse_import(
        &importer::normalized_import_text(&text),
        subscription_url.as_deref(),
        None,
        None,
    )
    .ok_or_else(|| FfiError::Invalid("The text contains no supported locations".into()))?;
    let imported_locations = parsed.bundle.locations.len() as u32;
    let merged = importer::merge_imported_bundle(
        Some(&current),
        &parsed.bundle,
        parsed.mode == importer::ImportMode::Restore,
    )
    .normalized();
    let active_location_id = merged.active_location_id.clone();
    let normalized_bundle_json =
        serde_json::to_string_pretty(&merged).map_err(|error| FfiError::Json(error.to_string()))?;
    Ok(AppleLocationImport {
        normalized_bundle_json,
        active_location_id,
        imported_locations,
    })
}

/// Creates or edits one location while preserving subscription metadata on edits.
#[uniffi::export]
pub fn apple_save_location(
    bundle_json: String,
    location_id: Option<String>,
    location_json: String,
) -> Result<AppleLocationSelection, FfiError> {
    let mut bundle = if bundle_json.trim().is_empty() {
        LocationBundleV4::default()
    } else {
        from_json::<LocationBundleV4>("LocationBundle", &bundle_json)?.normalized()
    };
    let location = from_json::<LocationConfig>("LocationConfig", &location_json)?.normalized();
    if !location.is_storable() {
        return Err(FfiError::Invalid(
            "The location is incomplete and cannot be saved".into(),
        ));
    }
    let id = location_id
        .as_deref()
        .map(str::trim)
        .filter(|id| !id.is_empty())
        .map(str::to_string)
        .unwrap_or_else(yptun_core::identity::generate_install_id);
    if let Some(index) = bundle
        .locations
        .iter()
        .position(|entry| entry.storage_id == id)
    {
        let old = &bundle.locations[index];
        bundle.locations[index] = LocationEntry::from(
            &id,
            &location,
            old.subscription_url.clone(),
            old.metadata.clone(),
        );
    } else {
        bundle
            .locations
            .push(LocationEntry::from(&id, &location, None, None));
    }
    bundle.active_location_id = Some(id);
    let normalized = bundle.normalized();
    let selected_location_json =
        serde_json::to_string(&location).map_err(|error| FfiError::Json(error.to_string()))?;
    let normalized_bundle_json = serde_json::to_string_pretty(&normalized)
        .map_err(|error| FfiError::Json(error.to_string()))?;
    Ok(AppleLocationSelection {
        normalized_bundle_json,
        selected_location_json,
    })
}

fn json_or_default<T>(what: &str, json: &str) -> Result<T, FfiError>
where
    T: serde::de::DeserializeOwned + Default,
{
    if json.trim().is_empty() {
        Ok(T::default())
    } else {
        from_json(what, json)
    }
}

fn apple_olcrtc_plan(
    location: &LocationConfig,
    listen_port: i32,
    device_id: &str,
) -> AppleOlcRtcRuntimePlan {
    let sei = location.sei_options();
    let video = location.video_options();
    let rooms_json = if location.uses_multi_room() {
        let rooms: Vec<serde_json::Value> = location
            .multi_room_specs()
            .into_iter()
            // Two peer connections are the safe ceiling in Apple's packet-tunnel process.
            .take(2)
            .map(|room| {
                serde_json::json!({
                    "carrier": room.provider,
                    "transport": room.transport,
                    "room": room.room,
                    "key": room.key,
                })
            })
            .collect();
        serde_json::to_string(&rooms).unwrap_or_default()
    } else {
        String::new()
    };
    AppleOlcRtcRuntimePlan {
        listen_port,
        provider: location.bypass_provider.clone(),
        transport: location.transport.clone(),
        room_id: location.id.clone(),
        key_hex: location.key.clone(),
        client_id: device_id.to_string(),
        vp8_fps: location.vp8_fps,
        vp8_batch: location.vp8_batch,
        sei_fps: sei.fps,
        sei_batch: sei.batch,
        sei_fragment_size: sei.fragment_size,
        sei_ack_timeout_ms: sei.ack_timeout_ms,
        video_width: video.width,
        video_height: video.height,
        video_fps: video.fps,
        video_qr_size: video.qr_size,
        video_qr_recovery: video.qr_recovery,
        video_codec: video.codec,
        video_tile_module: video.tile_module,
        video_tile_rs: video.tile_rs,
        rooms_json,
    }
}

fn apple_awg_ini(ini: &str) -> String {
    let mut has_allowed_ips = false;
    let mut has_dns = false;
    let mut lines: Vec<String> = ini
        .lines()
        .map(|line| {
            let trimmed = line.trim();
            if trimmed
                .split_once('=')
                .is_some_and(|(key, _)| key.trim().eq_ignore_ascii_case("AllowedIPs"))
            {
                has_allowed_ips = true;
                "AllowedIPs = 0.0.0.0/0, ::/0".into()
            } else {
                if trimmed
                    .split_once('=')
                    .is_some_and(|(key, _)| key.trim().eq_ignore_ascii_case("DNS"))
                {
                    has_dns = true;
                }
                line.to_string()
            }
        })
        .collect();
    if !has_allowed_ips {
        let peer = lines
            .iter()
            .rposition(|line| line.trim().eq_ignore_ascii_case("[Peer]"));
        lines.insert(
            peer.map_or(lines.len(), |index| index + 1),
            "AllowedIPs = 0.0.0.0/0, ::/0".into(),
        );
    }
    if !has_dns {
        if let Some(interface) = lines
            .iter()
            .position(|line| line.trim().eq_ignore_ascii_case("[Interface]"))
        {
            lines.insert(interface + 1, "DNS = 1.1.1.1, 8.8.8.8".into());
        }
    }
    lines.join("\n")
}

fn local_socks_profile(name: &str, port: i32) -> ProxyProfile {
    ProxyProfile {
        tag: name.to_string(),
        kind: "socks".into(),
        server: "127.0.0.1".into(),
        server_port: port,
        raw_outbound: Some(format!(
            r#"{{"type":"socks","server":"127.0.0.1","server_port":{port},"version":"5"}}"#
        )),
        ..ProxyProfile::default()
    }
}

fn openflux_session_specs(config: &yptun_core::transports::OpenFluxConfig) -> String {
    if !config.is_session() {
        return String::new();
    }
    serde_json::json!({
        "context": config.context,
        "transports": config.carriers.iter().map(|carrier| serde_json::json!({
            "name": carrier.kind,
            "type": carrier.kind,
            "url": carrier.url,
            "priority": carrier.priority,
            "params": if carrier.dial.trim().is_empty() {
                serde_json::json!({})
            } else {
                serde_json::json!({ "dial": carrier.dial })
            },
        })).collect::<Vec<_>>(),
    })
    .to_string()
}

fn proxy_from_text(text: &str) -> Option<ProxyProfile> {
    share_link::parse(text).or_else(|| share_link::parse_subscription(text).into_iter().next())
}

#[allow(clippy::too_many_arguments)]
fn apple_proxy_over_tunnel(
    profile: &ProxyProfile,
    core: ProxyCore,
    tunnel_port: i32,
    socks_port: i32,
    traffic: &TrafficSettings,
    routing: &RoutingRules,
    profiles: &RoutingProfilesState,
    routing_profile: Option<yptun_core::routing::RoutingProfile>,
    log_file_path: &str,
    cache_file_path: &str,
) -> Result<AppleProxyRuntimePlan, FfiError> {
    if profile
        .raw_xray_config
        .as_deref()
        .is_some_and(|raw| !raw.trim().is_empty())
    {
        return Err(FfiError::Unsupported(
            "A verbatim Xray config cannot be chained over this tunnel".into(),
        ));
    }
    match core {
        ProxyCore::Xray => Ok(AppleProxyRuntimePlan {
            kind: AppleProxyRuntimeKind::Xray,
            listen_port: socks_port,
            config: xray_config::build(
                profile,
                &XrayOptions {
                    listen_port: socks_port,
                    listen_host: "127.0.0.1".into(),
                    olcrtc_chain_port: Some(tunnel_port),
                    log_level: "warning".into(),
                    traffic: traffic.clone(),
                    routing_profile,
                    block_quic: true,
                    force_family_resolve: false,
                    chain_via_dialer_proxy: true,
                    direct_via_base: true,
                    bypass_lan: routing.bypass_lan,
                    handshake_timeout_sec: Some(30),
                    ..XrayOptions::default()
                },
            ),
        }),
        ProxyCore::SingBox | ProxyCore::Auto => Ok(AppleProxyRuntimePlan {
            kind: AppleProxyRuntimeKind::SingBox,
            listen_port: socks_port,
            config: singbox_config::build(
                profile,
                &SingBoxOptions {
                    listen_port: socks_port,
                    listen_host: "127.0.0.1".into(),
                    olcrtc_chain_port: Some(tunnel_port),
                    log_level: "warn".into(),
                    routing: routing.clone(),
                    traffic: traffic.clone(),
                    routing_profile,
                    singbox_geosite_base: profiles.singbox_geosite_base.clone(),
                    singbox_geoip_base: profiles.singbox_geoip_base.clone(),
                    block_quic: true,
                    force_family_resolve: false,
                    allow_local_resolve: false,
                    direct_via_base: true,
                    log_file_path: (!log_file_path.trim().is_empty())
                        .then(|| log_file_path.to_string()),
                    cache_file_path: (!cache_file_path.trim().is_empty())
                        .then(|| cache_file_path.to_string()),
                    ..SingBoxOptions::default()
                },
            )
            .map_err(|error| FfiError::Invalid(error.to_string()))?,
        }),
    }
}

/// Complete native execution plan for the first in-process Apple runtimes.
///
/// Rust owns all model/config decisions. Swift applies the tunnel settings and invokes Coreapi in
/// `olcrtc` then `proxy` order. Other engine families are added to this same typed surface.
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn apple_native_runtime_plan(
    location_json: String,
    traffic_json: String,
    routing_json: String,
    routing_profiles_json: String,
    app_behavior_json: String,
    warp_config: String,
    socks_port: i32,
    device_id: String,
    runtime_directory: String,
    log_file_path: String,
    cache_file_path: String,
) -> Result<AppleNativeRuntimePlan, FfiError> {
    if !(1..=65_528).contains(&socks_port) {
        return Err(FfiError::Invalid(format!(
            "SOCKS port {socks_port} leaves no room for Apple runtime ports"
        )));
    }
    let location = from_json::<LocationConfig>("LocationConfig", &location_json)?.normalized();
    if !location.is_complete() {
        return Err(FfiError::Invalid("Location is incomplete".into()));
    }
    let traffic =
        json_or_default::<TrafficSettings>("TrafficSettings", &traffic_json)?.normalized();
    let routing = json_or_default::<RoutingRules>("RoutingRules", &routing_json)?;
    let profiles =
        json_or_default::<RoutingProfilesState>("RoutingProfilesState", &routing_profiles_json)?;
    let behavior =
        json_or_default::<AppBehaviorSettings>("AppBehaviorSettings", &app_behavior_json)?;
    let routing_profile = profiles
        .resolve(Some(&location.routing_profile_id))
        .cloned();

    let olcrtc = match location.engine {
        EngineType::Stealth => Some(apple_olcrtc_plan(&location, socks_port, &device_id)),
        EngineType::Chain => Some(apple_olcrtc_plan(&location, socks_port + 1, &device_id)),
        _ => None,
    };

    let mut amnezia_wg = None;
    let mut turnable = None;
    let mut auxiliary = Vec::new();
    let mut snolc = None;
    let mut csqtt = None;
    let mut deferred_wireguard_proxy = false;

    let proxy = match location.engine {
        EngineType::Stealth => None,
        EngineType::Standard | EngineType::Chain => {
            let main = location
                .proxy
                .as_ref()
                .ok_or_else(|| FfiError::Invalid("No proxy configured".into()))?;
            if main.kind == TYPE_MIERU {
                if location.engine == EngineType::Chain {
                    return Err(FfiError::Unsupported(
                        "Mieru over olcRTC Chain is not supported".into(),
                    ));
                }
                Some(AppleProxyRuntimePlan {
                    kind: AppleProxyRuntimeKind::Mieru,
                    listen_port: socks_port,
                    config: main.mieru_config.clone(),
                })
            } else {
                let effective_main = if main.kind == TYPE_AMNEZIAWG {
                    let port = socks_port + 2;
                    amnezia_wg = Some(AppleAmneziaWgRuntimePlan {
                        ini_config: apple_awg_ini(&main.awg_config),
                        listen_address: format!("127.0.0.1:{port}"),
                    });
                    local_socks_profile(
                        if main.tag.trim().is_empty() {
                            "AmneziaWG"
                        } else {
                            &main.tag
                        },
                        port,
                    )
                } else {
                    main.clone()
                };
                let (mut second, _) = resolve_second(main, location.proxy2.as_ref());
                let mut warp_relay = None;
                let warp_eligible = location.warp_enabled
                    && location.engine == EngineType::Standard
                    && main.kind != TYPE_AMNEZIAWG
                    && (main
                        .raw_xray_config
                        .as_deref()
                        .is_some_and(|raw| !raw.trim().is_empty())
                        || matches!(
                            main.kind.as_str(),
                            "vless" | "vmess" | "trojan" | "shadowsocks"
                        ));
                if warp_eligible {
                    if warp_config.trim().is_empty() {
                        return Err(FfiError::Invalid(
                            "WARP is enabled but warp.conf is missing".into(),
                        ));
                    }
                    let relay_port = socks_port + 3;
                    let awg_port = socks_port + 2;
                    let (endpoint_host, endpoint_port) = warp::endpoint_of(&warp_config);
                    let relay = yptun_core::singbox::config::WarpRelay {
                        listen_port: relay_port,
                        target_host: warp::pinned_host(&endpoint_host),
                        target_port: endpoint_port,
                    };
                    let engine_ini = warp::engine_ini(
                        &warp_config,
                        location.warp_protocol == yptun_core::transports::WarpProtocol::Awg,
                        false,
                    );
                    amnezia_wg = Some(AppleAmneziaWgRuntimePlan {
                        ini_config: warp::with_endpoint(
                            &engine_ini,
                            &format!("127.0.0.1:{relay_port}"),
                        ),
                        listen_address: format!("127.0.0.1:{awg_port}"),
                    });
                    second = Some(local_socks_profile("WARP", awg_port));
                    warp_relay = Some(relay);
                }
                let caps = PlatformCaps::ios();
                let choice = choose_core(
                    &CoreInputs {
                        location: &location,
                        profile: &effective_main,
                        second: second.as_ref(),
                        routing_profile: routing_profile.as_ref(),
                        traffic: &traffic,
                        global_core: behavior.global_proxy_core,
                        caps: &caps,
                    },
                    || true,
                );
                let chain_port = (location.engine == EngineType::Chain).then_some(socks_port + 1);
                let log_file = (!log_file_path.trim().is_empty()).then_some(log_file_path);
                let cache_file = (!cache_file_path.trim().is_empty()).then_some(cache_file_path);
                match choice.core {
                    ProxyCore::Xray => {
                        let config = if let Some(raw) = effective_main
                            .raw_xray_config
                            .as_deref()
                            .filter(|raw| !raw.trim().is_empty())
                        {
                            xray_config::prepare_raw(
                                raw,
                                &RawOptions {
                                    listen_port: socks_port,
                                    listen_host: "127.0.0.1".into(),
                                    routing_profile: routing_profile.clone(),
                                    fake_dns_enabled: traffic.fake_dns_enabled,
                                    force_ipv4: traffic.domain_strategy == "ipv4_only",
                                    second_profile: second,
                                    warp_exit_port: amnezia_wg.as_ref().map(|_| socks_port + 2),
                                    warp_relay: warp_relay.clone(),
                                    ..RawOptions::new(socks_port)
                                },
                            )
                        } else {
                            xray_config::build(
                                &effective_main,
                                &XrayOptions {
                                    listen_port: socks_port,
                                    listen_host: "127.0.0.1".into(),
                                    olcrtc_chain_port: chain_port,
                                    log_level: "warning".into(),
                                    traffic: traffic.clone(),
                                    routing_profile: routing_profile.clone(),
                                    block_quic: true,
                                    bypass_lan: routing.bypass_lan,
                                    second_profile: second,
                                    warp_relay: warp_relay.clone(),
                                    fake_dns_spec: location.fake_dns.clone(),
                                    ..XrayOptions::default()
                                },
                            )
                        };
                        let _ = log_file;
                        Some(AppleProxyRuntimePlan {
                            kind: AppleProxyRuntimeKind::Xray,
                            listen_port: socks_port,
                            config,
                        })
                    }
                    ProxyCore::SingBox | ProxyCore::Auto => {
                        let config = singbox_config::build(
                            &effective_main,
                            &SingBoxOptions {
                                listen_port: socks_port,
                                listen_host: "127.0.0.1".into(),
                                olcrtc_chain_port: chain_port,
                                log_level: "warn".into(),
                                routing: routing.clone(),
                                traffic: traffic.clone(),
                                advanced: location.advanced.clone(),
                                routing_profile: routing_profile.clone(),
                                singbox_geosite_base: profiles.singbox_geosite_base.clone(),
                                singbox_geoip_base: profiles.singbox_geoip_base.clone(),
                                block_quic: singbox_blocks_quic(main),
                                second_profile: second,
                                warp_relay,
                                fake_dns_spec: location.fake_dns.clone(),
                                prefer_tcp_remote_dns: true,
                                log_file_path: log_file,
                                cache_file_path: cache_file,
                                ..SingBoxOptions::default()
                            },
                        )
                        .map_err(|error| FfiError::Invalid(error.to_string()))?;
                        Some(AppleProxyRuntimePlan {
                            kind: AppleProxyRuntimeKind::SingBox,
                            listen_port: socks_port,
                            config,
                        })
                    }
                }
            }
        }
        EngineType::VkTurn
            if location
                .vkturn
                .as_ref()
                .is_some_and(|config| config.uses_turnable()) =>
        {
            let vk = location.vkturn.as_ref().expect("checked above");
            let profile = location.proxy.as_ref().ok_or_else(|| {
                FfiError::Invalid("Turnable requires its WireGuard exit profile".into())
            })?;
            turnable = Some(AppleTurnableRuntimePlan {
                config: vk.turnable_config.clone(),
                listen_address: format!("127.0.0.1:{}", vk.listen_port),
            });
            let config = singbox_config::build(
                profile,
                &SingBoxOptions {
                    listen_port: socks_port,
                    listen_host: "127.0.0.1".into(),
                    log_level: "warn".into(),
                    routing: routing.clone(),
                    traffic: TrafficSettings {
                        domain_strategy: "ipv4_only".into(),
                        ..traffic.clone()
                    },
                    advanced: location.advanced.clone(),
                    routing_profile: routing_profile.clone(),
                    singbox_geosite_base: profiles.singbox_geosite_base.clone(),
                    singbox_geoip_base: profiles.singbox_geoip_base.clone(),
                    block_quic: false,
                    sniff_override_destination: true,
                    fake_dns_spec: location.fake_dns.clone(),
                    prefer_tcp_remote_dns: true,
                    log_file_path: (!log_file_path.trim().is_empty()).then_some(log_file_path),
                    cache_file_path: (!cache_file_path.trim().is_empty())
                        .then_some(cache_file_path),
                    ..SingBoxOptions::default()
                },
            )
            .map_err(|error| FfiError::Invalid(error.to_string()))?;
            Some(AppleProxyRuntimePlan {
                kind: AppleProxyRuntimeKind::SingBox,
                listen_port: socks_port,
                config,
            })
        }
        EngineType::VkTurn => {
            let vk = location
                .vkturn
                .as_ref()
                .ok_or_else(|| FfiError::Invalid("VK-TURN is not configured".into()))?;
            if vk.uses_csqtt() {
                let internal_port = socks_port + 1;
                let client_udp_port = internal_port + 4;
                let mut apple_vk = vk.clone();
                apple_vk.csqtt_workers = if vk.csqtt_workers <= 0 {
                    9
                } else {
                    vk.csqtt_workers.clamp(9, 18)
                };
                csqtt = Some(AppleCsqttRuntimePlan {
                    options_json: apple_vk.csqtt_core_options_json(
                        "in-process",
                        &format!("127.0.0.1:{client_udp_port}"),
                        &device_id,
                    ),
                    bridge_socks_address: format!("127.0.0.1:{internal_port}"),
                    client_udp_address: format!("127.0.0.1:{client_udp_port}"),
                    mtu: 1_300,
                });
                let profile = local_socks_profile("CSQTT", internal_port);
                let config = singbox_config::build(
                    &profile,
                    &SingBoxOptions {
                        listen_port: socks_port,
                        listen_host: "127.0.0.1".into(),
                        log_level: "warn".into(),
                        routing: routing.clone(),
                        traffic: TrafficSettings {
                            domain_strategy: "ipv4_only".into(),
                            ..traffic.clone()
                        },
                        routing_profile: routing_profile.clone(),
                        singbox_geosite_base: profiles.singbox_geosite_base.clone(),
                        singbox_geoip_base: profiles.singbox_geoip_base.clone(),
                        block_quic: true,
                        sniff_override_destination: true,
                        prefer_tcp_remote_dns: true,
                        log_file_path: (!log_file_path.trim().is_empty()).then_some(log_file_path),
                        cache_file_path: (!cache_file_path.trim().is_empty())
                            .then_some(cache_file_path),
                        ..SingBoxOptions::default()
                    },
                )
                .map_err(|error| FfiError::Invalid(error.to_string()))?;
                return Ok(AppleNativeRuntimePlan {
                    display_name: location.display_name(),
                    tunnel: apple_tunnel_plan(traffic.mtu, routing.bypass_lan),
                    bridge: apple_bridge_plan(
                        serde_json::to_string(&location)
                            .map_err(|error| FfiError::Json(error.to_string()))?,
                        serde_json::to_string(&traffic)
                            .map_err(|error| FfiError::Json(error.to_string()))?,
                        socks_port,
                        String::new(),
                        String::new(),
                    )?,
                    olcrtc,
                    amnezia_wg,
                    turnable,
                    auxiliary,
                    snolc,
                    csqtt,
                    deferred_wireguard_proxy,
                    proxy: Some(AppleProxyRuntimePlan {
                        kind: AppleProxyRuntimeKind::SingBox,
                        listen_port: socks_port,
                        config,
                    }),
                });
            }
            if vk.uses_wdtt() {
                auxiliary.push(AppleAuxiliaryRuntimePlan {
                    kind: AppleAuxiliaryRuntimeKind::Wdtt,
                    options_json: serde_json::json!({
                        "options_json": vk.wdtt_core_options_json(
                            &format!("127.0.0.1:{}", vk.listen_port),
                            &device_id,
                        )
                    })
                    .to_string(),
                });
                deferred_wireguard_proxy = true;
                None
            } else {
                auxiliary.push(AppleAuxiliaryRuntimePlan {
                    kind: AppleAuxiliaryRuntimeKind::FreeTurn,
                    options_json: serde_json::json!({
                        "uri": vk.uri,
                        "listen_addr": format!("127.0.0.1:{}", vk.listen_port),
                        "vk_link": vk.vk_link,
                        "streams": if vk.streams <= 0 { 2 } else { vk.streams.clamp(1, 3) },
                    })
                    .to_string(),
                });
                let profile = location.proxy.as_ref().ok_or_else(|| {
                    FfiError::Invalid("VK-TURN requires its WireGuard exit profile".into())
                })?;
                let config = singbox_config::build(
                    profile,
                    &SingBoxOptions {
                        listen_port: socks_port,
                        listen_host: "127.0.0.1".into(),
                        log_level: "warn".into(),
                        routing: routing.clone(),
                        traffic: TrafficSettings {
                            domain_strategy: "ipv4_only".into(),
                            ..traffic.clone()
                        },
                        advanced: location.advanced.clone(),
                        routing_profile: routing_profile.clone(),
                        singbox_geosite_base: profiles.singbox_geosite_base.clone(),
                        singbox_geoip_base: profiles.singbox_geoip_base.clone(),
                        block_quic: false,
                        sniff_override_destination: true,
                        fake_dns_spec: location.fake_dns.clone(),
                        prefer_tcp_remote_dns: true,
                        log_file_path: (!log_file_path.trim().is_empty()).then_some(log_file_path),
                        cache_file_path: (!cache_file_path.trim().is_empty())
                            .then_some(cache_file_path),
                        ..SingBoxOptions::default()
                    },
                )
                .map_err(|error| FfiError::Invalid(error.to_string()))?;
                Some(AppleProxyRuntimePlan {
                    kind: AppleProxyRuntimeKind::SingBox,
                    listen_port: socks_port,
                    config,
                })
            }
        }
        EngineType::MasterDns => {
            let config = location
                .master_dns
                .as_ref()
                .ok_or_else(|| FfiError::Invalid("MasterDNS is not configured".into()))?;
            let chained_proxy = config
                .has_proxy()
                .then(|| proxy_from_text(&config.proxy_link))
                .flatten();
            if config.has_proxy() && chained_proxy.is_none() {
                return Err(FfiError::Invalid(
                    "MasterDNS proxy link could not be parsed".into(),
                ));
            }
            let tunnel_port = if chained_proxy.is_some() {
                socks_port + 1
            } else {
                socks_port
            };
            auxiliary.push(AppleAuxiliaryRuntimePlan {
                kind: AppleAuxiliaryRuntimeKind::MasterDns,
                options_json: serde_json::json!({
                    "work_dir": format!("{runtime_directory}/masterdns"),
                    "domains": config.domains,
                    "key": config.encryption_key,
                    "encryption_method": config.encryption_method,
                    "resolvers": config.resolvers,
                    "listen_addr": format!("127.0.0.1:{tunnel_port}"),
                    "balancing_strategy": config.balancing_strategy,
                    "packet_duplication": config.packet_duplication,
                    "upload_compression": 0,
                    "download_compression": 0,
                })
                .to_string(),
            });
            chained_proxy
                .as_ref()
                .map(|profile| {
                    apple_proxy_over_tunnel(
                        profile,
                        config.resolved_proxy_core(Some(profile), behavior.global_proxy_core),
                        tunnel_port,
                        socks_port,
                        &traffic,
                        &routing,
                        &profiles,
                        routing_profile.clone(),
                        &log_file_path,
                        &cache_file_path,
                    )
                })
                .transpose()?
        }
        EngineType::OpenFlux => {
            let config = location
                .open_flux
                .as_ref()
                .ok_or_else(|| FfiError::Invalid("OpenFlux is not configured".into()))?;
            let chained_proxy = config
                .has_proxy()
                .then(|| proxy_from_text(&config.proxy_link))
                .flatten();
            if config.has_proxy() && chained_proxy.is_none() {
                return Err(FfiError::Invalid(
                    "OpenFlux proxy link could not be parsed".into(),
                ));
            }
            let tunnel_port = if chained_proxy.is_some() {
                socks_port + 1
            } else {
                socks_port
            };
            auxiliary.push(AppleAuxiliaryRuntimePlan {
                kind: AppleAuxiliaryRuntimeKind::OpenFlux,
                options_json: serde_json::json!({
                    "transport": config.transport,
                    "url": config.doc_url,
                    "secret": config.secret,
                    "max_token": config.max_token,
                    "max_uid": config.max_uid,
                    "avito_cookies": config.avito_cookies,
                    "session_specs_json": openflux_session_specs(config),
                    "listen_addr": format!("127.0.0.1:{tunnel_port}"),
                    "debug": config.debug,
                })
                .to_string(),
            });
            chained_proxy
                .as_ref()
                .map(|profile| {
                    apple_proxy_over_tunnel(
                        profile,
                        config.resolved_proxy_core(Some(profile), behavior.global_proxy_core),
                        tunnel_port,
                        socks_port,
                        &traffic,
                        &routing,
                        &profiles,
                        routing_profile.clone(),
                        &log_file_path,
                        &cache_file_path,
                    )
                })
                .transpose()?
        }
        EngineType::Snolc => {
            let config = location
                .snolc
                .as_ref()
                .ok_or_else(|| FfiError::Invalid("snolc is not configured".into()))?;
            let chained_proxy = config
                .has_proxy()
                .then(|| proxy_from_text(&config.proxy_link))
                .flatten();
            if config.has_proxy() && chained_proxy.is_none() {
                return Err(FfiError::Invalid(
                    "snolc proxy link could not be parsed".into(),
                ));
            }
            let tunnel_port = if chained_proxy.is_some() {
                socks_port + 1
            } else {
                socks_port
            };
            let directory = format!("{runtime_directory}/snolc");
            let mut files: Vec<AppleGeneratedFile> = yptun_core::snolc::client(
                "127.0.0.1",
                tunnel_port,
                &config.host,
                config.port,
                config.debug,
                "",
                "",
            )
            .into_iter()
            .map(|(relative_path, contents)| AppleGeneratedFile {
                relative_path,
                contents,
            })
            .collect();
            files.push(AppleGeneratedFile {
                relative_path: "pub.hex".into(),
                contents: config.public_key.clone(),
            });
            snolc = Some(AppleSnolcRuntimePlan {
                config_path: format!("{directory}/snolc.toml"),
                directory,
                files,
            });
            chained_proxy
                .as_ref()
                .map(|profile| {
                    apple_proxy_over_tunnel(
                        profile,
                        config.resolved_proxy_core(Some(profile), behavior.global_proxy_core),
                        tunnel_port,
                        socks_port,
                        &traffic,
                        &routing,
                        &profiles,
                        routing_profile.clone(),
                        &log_file_path,
                        &cache_file_path,
                    )
                })
                .transpose()?
        }
    };

    let normalized_location_json =
        serde_json::to_string(&location).map_err(|error| FfiError::Json(error.to_string()))?;
    let normalized_traffic_json =
        serde_json::to_string(&traffic).map_err(|error| FfiError::Json(error.to_string()))?;
    Ok(AppleNativeRuntimePlan {
        display_name: location.display_name(),
        tunnel: apple_tunnel_plan(traffic.mtu, routing.bypass_lan),
        bridge: apple_bridge_plan(
            normalized_location_json,
            normalized_traffic_json,
            socks_port,
            String::new(),
            String::new(),
        )?,
        olcrtc,
        amnezia_wg,
        turnable,
        auxiliary,
        snolc,
        csqtt,
        deferred_wireguard_proxy,
        proxy,
    })
}

/// Builds the routing front after qWDTT returns its negotiated WireGuard configuration.
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn apple_wireguard_runtime_proxy(
    wireguard_ini: String,
    traffic_json: String,
    routing_json: String,
    routing_profiles_json: String,
    location_json: String,
    socks_port: i32,
    log_file_path: String,
    cache_file_path: String,
) -> Result<AppleProxyRuntimePlan, FfiError> {
    let profile = warp::parse_warp_ini(&wireguard_ini, "qWDTT")
        .ok_or_else(|| FfiError::Invalid("qWDTT returned an invalid WireGuard config".into()))?;
    let traffic =
        json_or_default::<TrafficSettings>("TrafficSettings", &traffic_json)?.normalized();
    let routing = json_or_default::<RoutingRules>("RoutingRules", &routing_json)?;
    let profiles =
        json_or_default::<RoutingProfilesState>("RoutingProfilesState", &routing_profiles_json)?;
    let location = from_json::<LocationConfig>("LocationConfig", &location_json)?.normalized();
    let routing_profile = profiles
        .resolve(Some(&location.routing_profile_id))
        .cloned();
    let config = singbox_config::build(
        &profile,
        &SingBoxOptions {
            listen_port: socks_port,
            listen_host: "127.0.0.1".into(),
            log_level: "warn".into(),
            routing,
            traffic: TrafficSettings {
                domain_strategy: "ipv4_only".into(),
                ..traffic
            },
            routing_profile,
            singbox_geosite_base: profiles.singbox_geosite_base,
            singbox_geoip_base: profiles.singbox_geoip_base,
            block_quic: false,
            sniff_override_destination: true,
            prefer_tcp_remote_dns: true,
            log_file_path: (!log_file_path.trim().is_empty()).then_some(log_file_path),
            cache_file_path: (!cache_file_path.trim().is_empty()).then_some(cache_file_path),
            ..SingBoxOptions::default()
        },
    )
    .map_err(|error| FfiError::Invalid(error.to_string()))?;
    Ok(AppleProxyRuntimePlan {
        kind: AppleProxyRuntimeKind::SingBox,
        listen_port: socks_port,
        config,
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
        EngineType::MasterDns | EngineType::OpenFlux | EngineType::VkTurn
    );
    let drop_ipv6 = matches!(
        location.engine,
        EngineType::VkTurn | EngineType::MasterDns | EngineType::OpenFlux
    ) || traffic.domain_strategy == "ipv4_only";
    let ipv6 = if drop_ipv6 {
        "# ipv6 disabled (IPv4 only/preferred)".to_string()
    } else {
        format!("ipv6: '{TUN_IPV6_ADDRESS}'")
    };
    let connect_timeout = if slow_tunnel { 30_000 } else { 10_000 };
    let authentication = if socks_username.trim().is_empty() {
        "# no auth".to_string()
    } else {
        format!(
            "username: '{}'\n  password: '{}'",
            yaml_single_quoted(&socks_username),
            yaml_single_quoted(&socks_password)
        )
    };
    let yaml = format!(
        "tunnel:\n  mtu: {mtu}\n  ipv4: {ipv4}\n  {ipv6}\n\nsocks5:\n  address: 127.0.0.1\n  port: {socks_port}\n  udp: '{udp}'\n  pipeline: false\n  {authentication}\n\nmapdns:\n  address: {mapdns}\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 2048\n\nmisc:\n  task-stack-size: 16384\n  tcp-buffer-size: 4096\n  max-session-count: 512\n  connect-timeout: {connect_timeout}\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n",
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
