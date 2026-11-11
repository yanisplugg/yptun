//! Stateless calls: share links, locations, routing profiles, WARP, i18n, bytes.

use std::collections::HashMap;

use yptun_core::delta_bundle;
use yptun_core::i18n::{self, AppLanguage};
use yptun_core::integrity;
use yptun_core::location::{self, LocationConfig};
use yptun_core::model::ProxyProfile;
use yptun_core::routing::{self, RoutingProfile};
use yptun_core::share_link;
use yptun_core::warp;

use crate::{from_json, to_json, CoreKind, FfiError};

// ---------------- share links ----------------

/// Share link -> ProxyProfile JSON (None = not a supported link).
#[uniffi::export]
pub fn parse_share_link(uri: String) -> Result<Option<String>, FfiError> {
    share_link::parse(&uri).map(|p| to_json(&p)).transpose()
}

/// Subscription body -> JSON array of ProxyProfile.
#[uniffi::export]
pub fn parse_subscription(body: String) -> Result<String, FfiError> {
    to_json(&share_link::parse_subscription(&body))
}

#[uniffi::export]
pub fn looks_like_amneziawg(text: String) -> bool {
    share_link::looks_like_amneziawg(&text)
}

#[uniffi::export]
pub fn parse_amneziawg(text: String) -> Result<Option<String>, FfiError> {
    share_link::parse_amneziawg(&text).map(|p| to_json(&p)).transpose()
}

#[uniffi::export]
pub fn looks_like_trust_tunnel(text: String) -> bool {
    share_link::looks_like_trust_tunnel(&text)
}

#[uniffi::export]
pub fn parse_trust_tunnel(uri: String) -> Result<Option<String>, FfiError> {
    share_link::parse_trust_tunnel(&uri).map(|p| to_json(&p)).transpose()
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ProfileInfo {
    pub display_name: String,
    pub display_server: String,
    pub complete: bool,
    pub requires_xray: bool,
}

/// Everything the UI shows about a profile, in one call.
#[uniffi::export]
pub fn profile_info(profile_json: String) -> Result<ProfileInfo, FfiError> {
    let p: ProxyProfile = from_json("ProxyProfile", &profile_json)?;
    Ok(ProfileInfo {
        display_name: p.display_name(),
        display_server: share_link::display_server(&p),
        complete: p.is_complete(),
        requires_xray: p.requires_xray(),
    })
}

// ---------------- locations ----------------

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LocationInfo {
    pub display_name: String,
    pub provider_name: String,
    pub transport_name: String,
    pub complete: bool,
    pub storable: bool,
    pub requires_xray: bool,
    pub needs_vk_link: bool,
    pub uses_multi_room: bool,
}

#[uniffi::export]
pub fn location_normalized(location_json: String) -> Result<String, FfiError> {
    let l: LocationConfig = from_json("LocationConfig", &location_json)?;
    to_json(&l.normalized())
}

/// Everything the UI checks about a location, in one call.
#[uniffi::export]
pub fn location_info(location_json: String) -> Result<LocationInfo, FfiError> {
    let l: LocationConfig = from_json("LocationConfig", &location_json)?;
    Ok(LocationInfo {
        display_name: l.display_name(),
        provider_name: l.provider_name().to_string(),
        transport_name: l.transport_name().to_string(),
        complete: l.is_complete(),
        storable: l.is_storable(),
        requires_xray: l.requires_xray(),
        needs_vk_link: l.needs_vk_link(),
        uses_multi_room: l.uses_multi_room(),
    })
}

#[uniffi::export]
pub fn location_resolved_core(location_json: String, global: CoreKind) -> Result<CoreKind, FfiError> {
    let l: LocationConfig = from_json("LocationConfig", &location_json)?;
    Ok(l.resolved_core(global.into()).into())
}

#[uniffi::export]
pub fn normalize_provider(value: String) -> String {
    location::normalize_provider(&value).to_string()
}

#[uniffi::export]
pub fn supported_transports_for_provider(provider: String) -> Vec<String> {
    location::supported_transports_for_provider(&provider).into_iter().map(str::to_string).collect()
}

#[uniffi::export]
pub fn provider_display_name(provider: String) -> String {
    location::provider_display_name(&provider).to_string()
}

#[uniffi::export]
pub fn transport_display_name(transport: String) -> String {
    location::transport_display_name(&transport).to_string()
}

// ---------------- routing profiles ----------------

#[uniffi::export]
pub fn is_routing_link(link: String) -> bool {
    routing::is_routing_link(&link)
}

#[uniffi::export]
pub fn looks_like_routing_profile(text: String) -> bool {
    routing::looks_like_routing_profile(&text)
}

/// Routing link or JSON -> RoutingProfile JSON (None = not a routing profile).
#[uniffi::export]
pub fn parse_routing_profile(text: String) -> Result<Option<String>, FfiError> {
    routing::parse_routing_profile(&text).map(|p| to_json(&p)).transpose()
}

#[uniffi::export]
pub fn routing_profile_happ_link(profile_json: String) -> Result<String, FfiError> {
    let p: RoutingProfile = from_json("RoutingProfile", &profile_json)?;
    Ok(p.to_happ_link())
}

/// ASNs the profile references (to download their CIDRs).
#[uniffi::export]
pub fn routing_profile_asns(profile_json: String) -> Result<Vec<String>, FfiError> {
    let p: RoutingProfile = from_json("RoutingProfile", &profile_json)?;
    Ok(p.referenced_asns())
}

// ---------------- WARP ----------------

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WarpEndpoint {
    pub host: String,
    pub port: i32,
}

#[uniffi::export]
pub fn warp_endpoint(ini: String) -> WarpEndpoint {
    let (host, port) = warp::endpoint_of(&ini);
    WarpEndpoint { host, port }
}

#[uniffi::export]
pub fn warp_parse_ini(ini: String, tag: String) -> Result<Option<String>, FfiError> {
    warp::parse_warp_ini(&ini, &tag).map(|p| to_json(&p)).transpose()
}

#[uniffi::export]
pub fn warp_engine_ini(ini: String, awg: bool, init_packets: bool) -> String {
    warp::engine_ini(&ini, awg, init_packets)
}

#[uniffi::export]
pub fn warp_plain_wireguard(ini: String) -> String {
    warp::plain_wireguard(&ini)
}

// ---------------- i18n ----------------

fn lang(id: &Option<String>) -> AppLanguage {
    AppLanguage::from_id(id.as_deref())
}

#[uniffi::export]
pub fn i18n_language_ids() -> Vec<String> {
    AppLanguage::ALL.iter().map(|l| l.id().to_string()).collect()
}

/// System -> the device language; others stay as they are.
#[uniffi::export]
pub fn i18n_effective_language(language_id: Option<String>, system_language_id: Option<String>) -> String {
    lang(&language_id).effective(lang(&system_language_id)).id().to_string()
}

/// The whole catalog in one call (load once, look up locally).
#[uniffi::export]
pub fn i18n_catalog(language_id: Option<String>) -> HashMap<String, String> {
    i18n::catalog(lang(&language_id)).iter().map(|(k, v)| (k.clone(), v.clone())).collect()
}

#[uniffi::export]
pub fn i18n_text(language_id: Option<String>, key: String) -> String {
    i18n::text(lang(&language_id), &key).to_string()
}

#[uniffi::export]
pub fn i18n_format(language_id: Option<String>, key: String, args: HashMap<String, String>) -> String {
    let pairs: Vec<(&str, &str)> = args.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
    i18n::format(lang(&language_id), &key, &pairs)
}

// ---------------- bytes: integrity / delta updates ----------------

#[uniffi::export]
pub fn sha256_hex(bytes: Vec<u8>) -> String {
    integrity::sha256_hex(&bytes)
}

/// True when the signing-certificate SHA-256 is the official key's.
#[uniffi::export]
pub fn is_official_signing_sha256(sha256: Option<String>) -> bool {
    integrity::matches_official(sha256.as_deref())
}

/// Delta-update manifest bytes -> Manifest JSON.
#[uniffi::export]
pub fn delta_parse_manifest(bytes: Vec<u8>) -> Result<String, FfiError> {
    let m = delta_bundle::parse_manifest(&bytes).map_err(FfiError::Invalid)?;
    to_json(&m)
}
