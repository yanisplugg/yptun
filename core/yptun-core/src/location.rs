//! Locations: what the user picks in the list (port of `LocationConfig`, `LocationEntry`,
//! `LocationBundleV4`, `SubscriptionMetadata`, `LocationMetadata`). The stored form
//! ([`LocationEntry`] / [`LocationBundleV4`]) reads every legacy field the Kotlin app accepted.

use std::collections::BTreeMap;

use serde::{Deserialize, Deserializer, Serialize};

use crate::model::{EngineType, ProxyCore, ProxyProfile};
use crate::transports::*;

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

fn not_blank_opt(s: &Option<String>) -> bool {
    s.as_deref().is_some_and(not_blank)
}

// ---------------- olcRTC providers / transports ----------------

pub const PROVIDER_JAZZ: &str = "salutejazz";
pub const PROVIDER_TELEMOST: &str = "telemost";
pub const PROVIDER_WB_STREAM: &str = "wbstream";
pub const PROVIDER_JITSI: &str = "jitsi";
pub const PROVIDER_VK_CALLS: &str = "vkcalls";
pub const PROVIDER_MTS_LINK: &str = "mtslink";
pub const DEFAULT_BYPASS_PROVIDER: &str = PROVIDER_MTS_LINK;

pub const TRANSPORT_DATACHANNEL: &str = "datachannel";
pub const TRANSPORT_VP8CHANNEL: &str = "vp8channel";
pub const TRANSPORT_SEICHANNEL: &str = "seichannel";
pub const TRANSPORT_VIDEOCHANNEL: &str = "videochannel";
pub const DEFAULT_TRANSPORT: &str = TRANSPORT_VP8CHANNEL;

pub const DEFAULT_VP8_FPS: i32 = 60;
pub const DEFAULT_VP8_BATCH: i32 = 64;
/// Max additional multi-room rooms (up to MAX_EXTRA_ROOMS + 1 at once).
pub const MAX_EXTRA_ROOMS: usize = 4;
/// Server-side bond reassembler port.
pub const DEFAULT_BOND_PORT: i32 = 7700;

pub const SUPPORTED_BYPASS_PROVIDERS: [&str; 6] =
    [PROVIDER_MTS_LINK, PROVIDER_VK_CALLS, PROVIDER_JAZZ, PROVIDER_TELEMOST, PROVIDER_WB_STREAM, PROVIDER_JITSI];
pub const SUPPORTED_TRANSPORTS: [&str; 4] =
    [TRANSPORT_DATACHANNEL, TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL];

pub fn normalize_provider(value: &str) -> &'static str {
    match value.trim().to_lowercase().as_str() {
        "salutejazz" | "jazz" | "sberjazz" | "sber_jazz" => PROVIDER_JAZZ,
        "vkcalls" | "vk" | "vk_calls" | "vk-calls" => PROVIDER_VK_CALLS,
        "mtslink" | "mts" | "mts_link" | "mts-link" => PROVIDER_MTS_LINK,
        "telemost" | "yandex" | "yandex_telemost" => PROVIDER_TELEMOST,
        "wbstream" | "wb-stream" | "wildberries" => PROVIDER_WB_STREAM,
        "jitsi" | "jitsi-meet" | "jitsi_meet" | "meet" => PROVIDER_JITSI,
        _ => DEFAULT_BYPASS_PROVIDER,
    }
}

/// Transports per provider, best first (a hint, not a restriction — except Jazz/MTS/VK, whose SFUs
/// pass only some of them).
pub fn supported_transports_for_provider(provider: &str) -> Vec<&'static str> {
    match normalize_provider(provider) {
        PROVIDER_TELEMOST => vec![TRANSPORT_VP8CHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_DATACHANNEL],
        PROVIDER_WB_STREAM => vec![TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_DATACHANNEL],
        PROVIDER_JITSI => vec![TRANSPORT_DATACHANNEL, TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL],
        PROVIDER_JAZZ => vec![TRANSPORT_DATACHANNEL],
        PROVIDER_MTS_LINK => vec![TRANSPORT_VP8CHANNEL],
        PROVIDER_VK_CALLS => vec![TRANSPORT_VP8CHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL],
        _ => SUPPORTED_TRANSPORTS.to_vec(),
    }
}

fn canonical_transport(value: &str) -> &'static str {
    match value.trim().to_lowercase().as_str() {
        "datachannel" | "data" | "dc" => TRANSPORT_DATACHANNEL,
        "vp8channel" | "vp8" | "video_vp8" | "video-vp8" => TRANSPORT_VP8CHANNEL,
        "seichannel" | "sei" | "sei_channel" | "sei-channel" | "h264_sei" => TRANSPORT_SEICHANNEL,
        "videochannel" | "video" | "video_channel" | "video-channel" => TRANSPORT_VIDEOCHANNEL,
        _ => DEFAULT_TRANSPORT,
    }
}

pub fn normalize_transport(value: &str, provider: &str) -> &'static str {
    let t = canonical_transport(value);
    let supported = supported_transports_for_provider(provider);
    if supported.contains(&t) {
        t
    } else {
        supported.first().copied().unwrap_or(DEFAULT_TRANSPORT)
    }
}

pub fn provider_display_name(provider: &str) -> &'static str {
    match normalize_provider(provider) {
        PROVIDER_JAZZ => "Jazz",
        PROVIDER_TELEMOST => "Telemost",
        PROVIDER_WB_STREAM => "WB Stream",
        PROVIDER_JITSI => "Jitsi",
        PROVIDER_VK_CALLS => "VK Calls",
        _ => "MTS Link",
    }
}

pub fn transport_display_name(transport: &str) -> &'static str {
    match canonical_transport(transport) {
        TRANSPORT_DATACHANNEL => "DataChannel",
        TRANSPORT_SEICHANNEL => "SEI",
        TRANSPORT_VIDEOCHANNEL => "Video",
        _ => "VP8",
    }
}

pub fn sanitize_vp8_fps(v: i32) -> i32 {
    v.clamp(1, 120)
}
pub fn sanitize_vp8_batch(v: i32) -> i32 {
    v.clamp(1, 64)
}

/// Lower-cased keys, trimmed values, no blanks — the payload of a `<key=value&…>` block.
pub fn normalize_transport_options(options: &BTreeMap<String, String>) -> BTreeMap<String, String> {
    options
        .iter()
        .map(|(k, v)| (k.trim().to_lowercase(), v.trim().to_string()))
        .filter(|(k, v)| !k.is_empty() && !v.is_empty())
        .collect()
}

/// seichannel parameters handed to the olcRTC core.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SeiOptions {
    pub fps: i32,
    pub batch: i32,
    pub fragment_size: i32,
    pub ack_timeout_ms: i32,
}

impl SeiOptions {
    pub const DEFAULT_FPS: i32 = 60;
    pub const DEFAULT_BATCH: i32 = 64;
    pub const DEFAULT_FRAGMENT: i32 = 900;
    pub const DEFAULT_ACK_MS: i32 = 2000;
}

/// videochannel parameters handed to the olcRTC core.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VideoOptions {
    pub width: i32,
    pub height: i32,
    pub fps: i32,
    pub qr_size: i32,
    pub qr_recovery: String,
    pub codec: String,
    pub tile_module: i32,
    pub tile_rs: i32,
}

impl VideoOptions {
    pub const CODEC_QRCODE: &'static str = "qrcode";
    pub const CODEC_TILE: &'static str = "tile";
    pub const DEFAULT_WIDTH: i32 = 1920;
    pub const DEFAULT_HEIGHT: i32 = 1080;
    pub const DEFAULT_FPS: i32 = 30;
    pub const QR_RECOVERY_LEVELS: [&'static str; 4] = ["low", "medium", "high", "highest"];
}

// ---------------- LocationConfig ----------------

fn default_provider() -> String {
    DEFAULT_BYPASS_PROVIDER.into()
}
fn default_transport() -> String {
    DEFAULT_TRANSPORT.into()
}
fn default_vp8_fps() -> i32 {
    DEFAULT_VP8_FPS
}
fn default_vp8_batch() -> i32 {
    DEFAULT_VP8_BATCH
}
fn default_true() -> bool {
    true
}

/// One location, as the engines see it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LocationConfig {
    pub name: String,
    /// Display-only description (panel `meta.serverDescription` / `#desc:`).
    pub description: String,
    /// olcRTC room id.
    pub id: String,
    /// olcRTC key (hex).
    pub key: String,
    #[serde(rename = "bypass_provider", default = "default_provider")]
    pub bypass_provider: String,
    #[serde(default = "default_transport")]
    pub transport: String,
    #[serde(rename = "vp8_fps", default = "default_vp8_fps")]
    pub vp8_fps: i32,
    #[serde(rename = "vp8_batch", default = "default_vp8_batch")]
    pub vp8_batch: i32,
    /// olcRTC URI `<key=value&…>` parameters, kept raw so a share link round-trips.
    #[serde(rename = "transport_options")]
    pub transport_options: BTreeMap<String, String>,
    pub engine: EngineType,
    /// Main proxy (Standard/Chain; the exit for VK-TURN).
    pub proxy: Option<ProxyProfile>,
    /// Optional second proxy (cascade) on top of [`Self::proxy`].
    pub proxy2: Option<ProxyProfile>,
    #[serde(rename = "warp_enabled")]
    pub warp_enabled: bool,
    #[serde(rename = "warp_mode")]
    pub warp_mode: WarpMode,
    #[serde(rename = "warp_protocol")]
    pub warp_protocol: WarpProtocol,
    /// Vestigial; the main proxy is always applied. Kept for old saved locations.
    #[serde(rename = "proxy_enabled", default = "default_true")]
    pub proxy_enabled: bool,
    pub core: ProxyCore,
    pub vkturn: Option<VkTurnConfig>,
    #[serde(rename = "masterdns")]
    pub master_dns: Option<MasterDnsConfig>,
    #[serde(rename = "openflux")]
    pub open_flux: Option<OpenFluxConfig>,
    pub snolc: Option<SnolcConfig>,
    pub advanced: Option<AdvancedCoreConfig>,
    /// Routing profile id; blank = global, `none` id = no profile.
    #[serde(rename = "routing_profile_id")]
    pub routing_profile_id: String,
    #[serde(rename = "fake_dns")]
    pub fake_dns: Option<FakeDnsSpec>,
    #[serde(rename = "multi_room")]
    pub multi_room_enabled: bool,
    #[serde(rename = "extra_rooms")]
    pub extra_rooms: Vec<ExtraRoom>,
    #[serde(rename = "multi_room_bond")]
    pub multi_room_bond: bool,
    #[serde(rename = "bond_port")]
    pub bond_port: i32,
}

impl Default for LocationConfig {
    fn default() -> Self {
        Self {
            name: String::new(),
            description: String::new(),
            id: String::new(),
            key: String::new(),
            bypass_provider: default_provider(),
            transport: default_transport(),
            vp8_fps: DEFAULT_VP8_FPS,
            vp8_batch: DEFAULT_VP8_BATCH,
            transport_options: BTreeMap::new(),
            engine: EngineType::Stealth,
            proxy: None,
            proxy2: None,
            warp_enabled: false,
            warp_mode: WarpMode::Client,
            warp_protocol: WarpProtocol::Awg,
            proxy_enabled: true,
            core: ProxyCore::Auto,
            vkturn: None,
            master_dns: None,
            open_flux: None,
            snolc: None,
            advanced: None,
            routing_profile_id: String::new(),
            fake_dns: None,
            multi_room_enabled: false,
            extra_rooms: Vec::new(),
            multi_room_bond: false,
            bond_port: 0,
        }
    }
}

impl LocationConfig {
    /// Main room + extra rooms with a room and key, capped at MAX_EXTRA_ROOMS + 1.
    pub fn multi_room_specs(&self) -> Vec<ExtraRoom> {
        std::iter::once(ExtraRoom {
            provider: self.bypass_provider.clone(),
            transport: self.transport.clone(),
            room: self.id.clone(),
            key: self.key.clone(),
        })
        .chain(self.extra_rooms.iter().cloned())
        .filter(|r| not_blank(&r.room) && not_blank(&r.key))
        .take(MAX_EXTRA_ROOMS + 1)
        .collect()
    }

    pub fn uses_multi_room(&self) -> bool {
        self.multi_room_enabled && self.extra_rooms.iter().any(|r| not_blank(&r.room) && not_blank(&r.key))
    }

    pub fn uses_multi_room_bond(&self) -> bool {
        self.multi_room_bond && self.uses_multi_room()
    }

    pub fn effective_bond_port(&self) -> i32 {
        if (1..=65535).contains(&self.bond_port) {
            self.bond_port
        } else {
            DEFAULT_BOND_PORT
        }
    }

    pub fn normalized(&self) -> Self {
        let provider = normalize_provider(&self.bypass_provider);
        Self {
            name: self.name.trim().into(),
            description: self.description.trim().into(),
            id: self.id.trim().into(),
            key: self.key.trim().into(),
            bypass_provider: provider.into(),
            transport: normalize_transport(&self.transport, provider).into(),
            vp8_fps: sanitize_vp8_fps(self.vp8_fps),
            vp8_batch: sanitize_vp8_batch(self.vp8_batch),
            transport_options: normalize_transport_options(&self.transport_options),
            master_dns: self.master_dns.as_ref().map(MasterDnsConfig::normalized),
            open_flux: self.open_flux.as_ref().map(OpenFluxConfig::normalized),
            snolc: self.snolc.as_ref().map(SnolcConfig::normalized),
            routing_profile_id: self.routing_profile_id.trim().into(),
            ..self.clone()
        }
    }

    /// Only xray-core can serve it (an Xray-only transport/knob on either proxy).
    pub fn requires_xray(&self) -> bool {
        self.proxy.iter().chain(self.proxy2.iter()).any(ProxyProfile::requires_xray)
    }

    /// Auto → concrete core: raw Xray config, then the per-location choice, then Xray-only
    /// transports, then the app-wide default, then sing-box.
    pub fn resolved_core(&self, global: ProxyCore) -> ProxyCore {
        if self.proxy.as_ref().is_some_and(|p| not_blank_opt(&p.raw_xray_config)) {
            ProxyCore::Xray
        } else if self.core != ProxyCore::Auto {
            self.core
        } else if self.proxy.as_ref().is_some_and(ProxyProfile::requires_xray) {
            ProxyCore::Xray
        } else if global != ProxyCore::Auto {
            global
        } else {
            ProxyCore::SingBox
        }
    }

    /// A VK-TURN location lacking nothing but the user's VK call link.
    pub fn needs_vk_link(&self) -> bool {
        self.engine == EngineType::VkTurn
            && self.vkturn.as_ref().is_some_and(|v| v.is_storable() && !not_blank(&v.vk_link))
            && self.vk_turn_exit_present()
    }

    fn vk_turn_exit_present(&self) -> bool {
        let Some(v) = &self.vkturn else { return self.vk_turn_exit_present_freeturn() };
        // WDTT/csqtt get the tunnel from the server at runtime; nothing stored is required.
        if v.uses_wdtt() || v.uses_csqtt() {
            return true;
        }
        self.vk_turn_exit_present_freeturn()
    }

    fn vk_turn_exit_present_freeturn(&self) -> bool {
        match self.vkturn.as_ref().map(|v| v.outbound.as_str()) {
            Some(OUTBOUND_PROXY) => self.proxy.as_ref().is_some_and(ProxyProfile::is_complete),
            Some(OUTBOUND_AMNEZIAWG) => self.proxy.as_ref().is_some_and(|p| not_blank(&p.awg_config)),
            _ => self.proxy.as_ref().is_some_and(|p| not_blank_opt(&p.raw_outbound)),
        }
    }

    /// Everything the engine needs to connect.
    pub fn is_complete(&self) -> bool {
        let room = not_blank(&self.id) && not_blank(&self.key);
        let proxy = self.proxy.as_ref().is_some_and(ProxyProfile::is_complete);
        match self.engine {
            EngineType::Stealth => room,
            EngineType::Standard => proxy,
            EngineType::Chain => proxy && room,
            EngineType::VkTurn => self.vkturn.as_ref().is_some_and(VkTurnConfig::is_complete) && self.vk_turn_exit_present(),
            EngineType::MasterDns => self.master_dns.as_ref().is_some_and(MasterDnsConfig::is_complete),
            EngineType::OpenFlux => self.open_flux.as_ref().is_some_and(OpenFluxConfig::is_complete),
            EngineType::Snolc => self.snolc.as_ref().is_some_and(SnolcConfig::is_complete),
        }
    }

    /// Enough to keep in the list (VK-TURN without the VK link is kept; the user fills it in).
    pub fn is_storable(&self) -> bool {
        match self.engine {
            EngineType::VkTurn => self.vkturn.as_ref().is_some_and(VkTurnConfig::is_storable) && self.vk_turn_exit_present(),
            _ => self.is_complete(),
        }
    }

    /// Duplicate-detection identity: connection fields only (names/tags blanked).
    pub fn dedup_key(&self) -> Self {
        let n = self.normalized();
        Self {
            name: String::new(),
            proxy: n.proxy.as_ref().map(ProxyProfile::dedup_normalized),
            proxy2: n.proxy2.as_ref().map(ProxyProfile::dedup_normalized),
            ..n
        }
    }

    pub fn display_name(&self) -> String {
        if not_blank(&self.name) {
            self.name.clone()
        } else {
            self.id.clone()
        }
    }

    pub fn provider_name(&self) -> &'static str {
        provider_display_name(&self.bypass_provider)
    }

    pub fn transport_name(&self) -> &'static str {
        transport_display_name(&self.transport)
    }

    fn option_int(&self, key: &str) -> Option<i32> {
        self.transport_options.get(key).and_then(|v| v.trim().parse().ok())
    }

    /// seichannel settings: the URI's payload over the olcRTC defaults.
    pub fn sei_options(&self) -> SeiOptions {
        SeiOptions {
            fps: self.option_int("fps").unwrap_or(SeiOptions::DEFAULT_FPS).clamp(1, 120),
            batch: self.option_int("batch").unwrap_or(SeiOptions::DEFAULT_BATCH).max(1),
            fragment_size: self.option_int("frag").unwrap_or(SeiOptions::DEFAULT_FRAGMENT).max(1),
            ack_timeout_ms: self.option_int("ack-ms").unwrap_or(SeiOptions::DEFAULT_ACK_MS).max(1),
        }
    }

    /// videochannel settings (the tile codec only runs at exactly 1080x1080).
    pub fn video_options(&self) -> VideoOptions {
        let codec = self
            .transport_options
            .get("video-codec")
            .map(|c| c.trim().to_lowercase())
            .filter(|c| c == VideoOptions::CODEC_QRCODE || c == VideoOptions::CODEC_TILE)
            .unwrap_or_else(|| VideoOptions::CODEC_QRCODE.into());
        let tile = codec == VideoOptions::CODEC_TILE;
        VideoOptions {
            width: if tile { 1080 } else { self.option_int("video-w").unwrap_or(VideoOptions::DEFAULT_WIDTH).max(1) },
            height: if tile { 1080 } else { self.option_int("video-h").unwrap_or(VideoOptions::DEFAULT_HEIGHT).max(1) },
            fps: self.option_int("video-fps").unwrap_or(VideoOptions::DEFAULT_FPS).clamp(1, 120),
            qr_size: self.option_int("video-qr-size").unwrap_or(0).max(0),
            qr_recovery: self
                .transport_options
                .get("video-qr-recovery")
                .map(|r| r.trim().to_lowercase())
                .filter(|r| VideoOptions::QR_RECOVERY_LEVELS.contains(&r.as_str()))
                .unwrap_or_else(|| "low".into()),
            codec,
            tile_module: self.option_int("video-tile-module").unwrap_or(4).clamp(1, 270),
            tile_rs: self.option_int("video-tile-rs").unwrap_or(0).clamp(0, 200),
        }
    }
}

// ---------------- transport (stored form) ----------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Vp8TransportConfig {
    #[serde(default = "default_vp8_fps")]
    pub fps: i32,
    #[serde(default = "default_vp8_batch")]
    pub batch: i32,
}

impl Default for Vp8TransportConfig {
    fn default() -> Self {
        Self { fps: DEFAULT_VP8_FPS, batch: DEFAULT_VP8_BATCH }
    }
}

impl Vp8TransportConfig {
    pub fn normalized(&self) -> Self {
        Self { fps: sanitize_vp8_fps(self.fps), batch: sanitize_vp8_batch(self.batch) }
    }
}

/// Stored transport: an object `{type, vp8, options}` — or a bare string in old saves.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct LocationTransportConfig {
    #[serde(rename = "type")]
    pub kind: String,
    pub vp8: Option<Vp8TransportConfig>,
    pub options: BTreeMap<String, String>,
}

impl Default for LocationTransportConfig {
    fn default() -> Self {
        Self { kind: DEFAULT_TRANSPORT.into(), vp8: None, options: BTreeMap::new() }
    }
}

#[derive(Deserialize)]
#[serde(default)]
struct TransportSurrogate {
    #[serde(rename = "type")]
    kind: String,
    vp8: Option<Vp8TransportConfig>,
    options: BTreeMap<String, String>,
}

impl Default for TransportSurrogate {
    fn default() -> Self {
        Self { kind: DEFAULT_TRANSPORT.into(), vp8: None, options: BTreeMap::new() }
    }
}

impl<'de> Deserialize<'de> for LocationTransportConfig {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let v = serde_json::Value::deserialize(d)?;
        Ok(match v {
            serde_json::Value::String(s) => Self { kind: s, ..Default::default() },
            serde_json::Value::Number(n) => Self { kind: n.to_string(), ..Default::default() },
            serde_json::Value::Bool(b) => Self { kind: b.to_string(), ..Default::default() },
            serde_json::Value::Object(_) => {
                let s: TransportSurrogate = serde_json::from_value(v).map_err(serde::de::Error::custom)?;
                Self { kind: s.kind, vp8: s.vp8, options: s.options }
            }
            _ => Self::default(),
        })
    }
}

impl LocationTransportConfig {
    pub fn from_config(config: &LocationConfig) -> Self {
        let n = config.normalized();
        Self {
            vp8: (n.transport == TRANSPORT_VP8CHANNEL)
                .then(|| Vp8TransportConfig { fps: n.vp8_fps, batch: n.vp8_batch }.normalized()),
            kind: n.transport,
            options: n.transport_options,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct LocationEndpointConfig {
    #[serde(rename = "room_id")]
    pub room_id: String,
    pub key: String,
    #[serde(rename = "client_id", skip_serializing_if = "Option::is_none")]
    pub legacy_client_id: Option<String>,
}

// ---------------- metadata ----------------

pub const DEFAULT_UPDATE_INTERVAL_HOURS: i32 = 24;
pub const MIN_UPDATE_INTERVAL_HOURS: i32 = 1;
pub const MAX_UPDATE_INTERVAL_HOURS: i32 = 720;

fn clean(v: &Option<String>) -> Option<String> {
    v.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

fn blank(v: &Option<String>) -> bool {
    !not_blank_opt(v)
}

/// What the panel says about a subscription (title, traffic, expiry, links, announce …).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SubscriptionMetadata {
    pub name: Option<String>,
    pub update: Option<String>,
    pub refresh: Option<String>,
    pub color: Option<String>,
    pub icon: Option<String>,
    pub used: Option<String>,
    pub available: Option<String>,
    #[serde(rename = "update_interval_hours")]
    pub update_interval_hours: Option<i32>,
    #[serde(rename = "last_refresh_at_epoch_ms")]
    pub last_refresh_at_epoch_ms: Option<i64>,
    #[serde(rename = "expires_at_epoch_ms")]
    pub expires_at_epoch_ms: Option<i64>,
    #[serde(rename = "last_attempt_at_epoch_ms")]
    pub last_attempt_at_epoch_ms: Option<i64>,
    #[serde(rename = "auto_update_enabled", default = "default_true")]
    pub auto_update_enabled: bool,
    #[serde(rename = "support_url")]
    pub support_url: Option<String>,
    #[serde(rename = "web_page_url")]
    pub web_page_url: Option<String>,
    #[serde(rename = "icon_url")]
    pub icon_url: Option<String>,
    pub announce: Option<String>,
    #[serde(rename = "provider_id")]
    pub provider_id: Option<String>,
    /// Name the user gave the subscription (the panel's [`Self::name`] stays the group key).
    #[serde(rename = "custom_name")]
    pub custom_name: Option<String>,
    /// Cascade (second proxy) the user set for the whole subscription.
    #[serde(rename = "cascade_link")]
    pub cascade_link: Option<String>,
}

impl Default for SubscriptionMetadata {
    fn default() -> Self {
        Self {
            name: None,
            update: None,
            refresh: None,
            color: None,
            icon: None,
            used: None,
            available: None,
            update_interval_hours: None,
            last_refresh_at_epoch_ms: None,
            expires_at_epoch_ms: None,
            last_attempt_at_epoch_ms: None,
            auto_update_enabled: true,
            support_url: None,
            web_page_url: None,
            icon_url: None,
            announce: None,
            provider_id: None,
            custom_name: None,
            cascade_link: None,
        }
    }
}

impl SubscriptionMetadata {
    pub fn normalized(&self) -> Self {
        Self {
            name: clean(&self.name),
            update: clean(&self.update),
            refresh: clean(&self.refresh),
            color: clean(&self.color),
            icon: clean(&self.icon),
            used: clean(&self.used),
            available: clean(&self.available),
            update_interval_hours: self
                .update_interval_hours
                .map(|h| h.clamp(MIN_UPDATE_INTERVAL_HOURS, MAX_UPDATE_INTERVAL_HOURS)),
            last_refresh_at_epoch_ms: self.last_refresh_at_epoch_ms.filter(|&v| v > 0),
            expires_at_epoch_ms: self.expires_at_epoch_ms.filter(|&v| v > 0),
            last_attempt_at_epoch_ms: self.last_attempt_at_epoch_ms.filter(|&v| v > 0),
            auto_update_enabled: self.auto_update_enabled,
            support_url: clean(&self.support_url),
            web_page_url: clean(&self.web_page_url),
            icon_url: clean(&self.icon_url),
            announce: clean(&self.announce),
            provider_id: clean(&self.provider_id),
            custom_name: clean(&self.custom_name),
            cascade_link: clean(&self.cascade_link),
        }
    }

    pub fn is_empty(&self) -> bool {
        blank(&self.name)
            && blank(&self.update)
            && blank(&self.refresh)
            && blank(&self.color)
            && blank(&self.icon)
            && blank(&self.used)
            && blank(&self.available)
            && self.update_interval_hours.is_none()
            && self.last_refresh_at_epoch_ms.is_none()
            && self.expires_at_epoch_ms.is_none()
            && self.last_attempt_at_epoch_ms.is_none()
            && self.auto_update_enabled
            && blank(&self.support_url)
            && blank(&self.web_page_url)
            && blank(&self.icon_url)
            && blank(&self.announce)
            && blank(&self.provider_id)
            && blank(&self.cascade_link)
    }
}

/// Per-location extras: panel display fields, the subscription, pin/lock state.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct LocationMetadata {
    pub name: Option<String>,
    pub color: Option<String>,
    pub icon: Option<String>,
    pub used: Option<String>,
    pub available: Option<String>,
    pub ip: Option<String>,
    pub comment: Option<String>,
    pub mimo: Option<String>,
    pub subscription: Option<SubscriptionMetadata>,
    /// Pinned free server: survives the next free-servers search.
    pub pinned: bool,
    /// From an encrypted subscription: no address/token/key shown, shared or exported.
    pub locked: bool,
    /// VK call links typed for a locked VK-TURN location; kept over refreshes.
    #[serde(rename = "vk_link_override")]
    pub vk_link_override: Option<String>,
}

impl LocationMetadata {
    pub fn normalized(&self) -> Self {
        Self {
            name: clean(&self.name),
            color: clean(&self.color),
            icon: clean(&self.icon),
            used: clean(&self.used),
            available: clean(&self.available),
            ip: clean(&self.ip),
            comment: clean(&self.comment),
            mimo: clean(&self.mimo),
            vk_link_override: clean(&self.vk_link_override),
            subscription: self.subscription.as_ref().map(SubscriptionMetadata::normalized).filter(|s| !s.is_empty()),
            pinned: self.pinned,
            locked: self.locked,
        }
    }

    pub fn is_empty(&self) -> bool {
        blank(&self.name)
            && blank(&self.color)
            && blank(&self.icon)
            && blank(&self.used)
            && blank(&self.available)
            && blank(&self.ip)
            && blank(&self.comment)
            && blank(&self.mimo)
            && !self.pinned
            && !self.locked
            && blank(&self.vk_link_override)
            && self.subscription.as_ref().is_none_or(SubscriptionMetadata::is_empty)
    }
}

// ---------------- stored entry / bundle ----------------

/// A stored location (the on-disk form). Reads every legacy key the Kotlin app accepted.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LocationEntry {
    #[serde(rename = "storage_id")]
    pub storage_id: String,
    pub name: String,
    pub description: String,
    #[serde(rename = "subscription_url")]
    pub subscription_url: Option<String>,
    pub endpoint: Option<LocationEndpointConfig>,
    pub engine: Option<EngineType>,
    pub proxy: Option<ProxyProfile>,
    pub proxy2: Option<ProxyProfile>,
    #[serde(rename = "warp_enabled")]
    pub warp_enabled: bool,
    #[serde(rename = "warp_mode")]
    pub warp_mode: WarpMode,
    #[serde(rename = "warp_protocol")]
    pub warp_protocol: WarpProtocol,
    #[serde(rename = "proxy_enabled", default = "default_true")]
    pub proxy_enabled: bool,
    pub core: Option<ProxyCore>,
    pub vkturn: Option<VkTurnConfig>,
    #[serde(rename = "masterdns")]
    pub master_dns: Option<MasterDnsConfig>,
    #[serde(rename = "openflux")]
    pub open_flux: Option<OpenFluxConfig>,
    pub snolc: Option<SnolcConfig>,
    pub advanced: Option<AdvancedCoreConfig>,
    #[serde(rename = "fake_dns")]
    pub fake_dns: Option<FakeDnsSpec>,
    #[serde(rename = "routing_profile_id")]
    pub routing_profile_id: Option<String>,
    #[serde(rename = "auth_provider")]
    pub auth_provider: Option<String>,
    #[serde(rename = "carrier", skip_serializing_if = "Option::is_none")]
    pub legacy_carrier: Option<String>,
    pub transport: Option<LocationTransportConfig>,
    pub metadata: Option<LocationMetadata>,
    #[serde(rename = "subscriptionUrl", skip_serializing_if = "Option::is_none")]
    pub legacy_subscription_url: Option<String>,
    #[serde(rename = "id", skip_serializing_if = "Option::is_none")]
    pub legacy_id: Option<String>,
    #[serde(rename = "room_id", skip_serializing_if = "Option::is_none")]
    pub legacy_room_id: Option<String>,
    #[serde(rename = "server", skip_serializing_if = "Option::is_none")]
    pub legacy_server: Option<String>,
    #[serde(rename = "client_id", skip_serializing_if = "Option::is_none")]
    pub legacy_client_id: Option<String>,
    #[serde(rename = "clientId", skip_serializing_if = "Option::is_none")]
    pub legacy_client_id_camel: Option<String>,
    #[serde(rename = "key", skip_serializing_if = "Option::is_none")]
    pub legacy_key: Option<String>,
    #[serde(rename = "password", skip_serializing_if = "Option::is_none")]
    pub legacy_password: Option<String>,
    #[serde(rename = "bypass_provider", skip_serializing_if = "Option::is_none")]
    pub legacy_bypass_provider: Option<String>,
    #[serde(rename = "bypassProvider", skip_serializing_if = "Option::is_none")]
    pub legacy_bypass_provider_camel: Option<String>,
    #[serde(rename = "provider", skip_serializing_if = "Option::is_none")]
    pub legacy_provider: Option<String>,
    #[serde(rename = "vp8_fps", skip_serializing_if = "Option::is_none")]
    pub legacy_vp8_fps: Option<i32>,
    #[serde(rename = "vp8Fps", skip_serializing_if = "Option::is_none")]
    pub legacy_vp8_fps_camel: Option<i32>,
    #[serde(rename = "vp8_batch", skip_serializing_if = "Option::is_none")]
    pub legacy_vp8_batch: Option<i32>,
    #[serde(rename = "vp8Batch", skip_serializing_if = "Option::is_none")]
    pub legacy_vp8_batch_camel: Option<i32>,
    #[serde(rename = "multi_room")]
    pub multi_room_enabled: bool,
    #[serde(rename = "extra_rooms")]
    pub extra_rooms: Vec<ExtraRoom>,
    #[serde(rename = "multi_room_bond")]
    pub multi_room_bond: bool,
    #[serde(rename = "bond_port")]
    pub bond_port: i32,
}

impl Default for LocationEntry {
    fn default() -> Self {
        Self {
            storage_id: String::new(),
            name: String::new(),
            description: String::new(),
            subscription_url: None,
            endpoint: None,
            engine: None,
            proxy: None,
            proxy2: None,
            warp_enabled: false,
            warp_mode: WarpMode::Client,
            warp_protocol: WarpProtocol::Awg,
            proxy_enabled: true,
            core: None,
            vkturn: None,
            master_dns: None,
            open_flux: None,
            snolc: None,
            advanced: None,
            fake_dns: None,
            routing_profile_id: None,
            auth_provider: None,
            legacy_carrier: None,
            transport: None,
            metadata: None,
            legacy_subscription_url: None,
            legacy_id: None,
            legacy_room_id: None,
            legacy_server: None,
            legacy_client_id: None,
            legacy_client_id_camel: None,
            legacy_key: None,
            legacy_password: None,
            legacy_bypass_provider: None,
            legacy_bypass_provider_camel: None,
            legacy_provider: None,
            legacy_vp8_fps: None,
            legacy_vp8_fps_camel: None,
            legacy_vp8_batch: None,
            legacy_vp8_batch_camel: None,
            multi_room_enabled: false,
            extra_rooms: Vec::new(),
            multi_room_bond: false,
            bond_port: 0,
        }
    }
}

fn first_not_blank(values: &[Option<&str>]) -> String {
    values.iter().flatten().find(|v| not_blank(v)).map(|v| v.to_string()).unwrap_or_default()
}

impl LocationEntry {
    /// The location this entry describes (legacy keys folded in), normalized.
    pub fn location(&self) -> LocationConfig {
        let provider = first_not_blank(&[
            self.auth_provider.as_deref(),
            self.legacy_carrier.as_deref(),
            self.legacy_bypass_provider.as_deref(),
            self.legacy_bypass_provider_camel.as_deref(),
            self.legacy_provider.as_deref(),
        ]);
        let transport = self.transport.clone().unwrap_or_default();
        let vp8 = transport.vp8.as_ref();
        let endpoint = self.endpoint.as_ref();
        LocationConfig {
            name: self.name.clone(),
            description: self.description.clone(),
            id: first_not_blank(&[
                endpoint.map(|e| e.room_id.as_str()),
                self.legacy_id.as_deref(),
                self.legacy_room_id.as_deref(),
                self.legacy_server.as_deref(),
            ]),
            key: first_not_blank(&[endpoint.map(|e| e.key.as_str()), self.legacy_key.as_deref(), self.legacy_password.as_deref()]),
            bypass_provider: provider,
            transport: transport.kind.clone(),
            vp8_fps: vp8.map(|v| v.fps).or(self.legacy_vp8_fps).or(self.legacy_vp8_fps_camel).unwrap_or(DEFAULT_VP8_FPS),
            vp8_batch: vp8
                .map(|v| v.batch)
                .or(self.legacy_vp8_batch)
                .or(self.legacy_vp8_batch_camel)
                .unwrap_or(DEFAULT_VP8_BATCH),
            transport_options: transport.options.clone(),
            engine: self.engine.unwrap_or(EngineType::Stealth),
            proxy: self.proxy.clone(),
            proxy2: self.proxy2.clone(),
            warp_enabled: self.warp_enabled,
            warp_mode: self.warp_mode,
            warp_protocol: self.warp_protocol,
            proxy_enabled: self.proxy_enabled,
            core: self.core.unwrap_or(ProxyCore::Auto),
            vkturn: self.vkturn.clone(),
            master_dns: self.master_dns.clone(),
            open_flux: self.open_flux.clone(),
            snolc: self.snolc.clone(),
            advanced: self.advanced.clone(),
            fake_dns: self.fake_dns.clone(),
            routing_profile_id: self.routing_profile_id.clone().unwrap_or_default(),
            multi_room_enabled: self.multi_room_enabled,
            extra_rooms: self.extra_rooms.clone(),
            multi_room_bond: self.multi_room_bond,
            bond_port: self.bond_port,
        }
        .normalized()
    }

    pub fn bypass_provider(&self) -> String {
        self.location().bypass_provider
    }

    /// The canonical stored form (legacy keys dropped, metadata cleaned).
    pub fn normalized(&self) -> Self {
        let c = self.location();
        let sub = first_not_blank(&[self.subscription_url.as_deref(), self.legacy_subscription_url.as_deref()]);
        Self::build(
            self.storage_id.trim().to_string(),
            &c,
            (!sub.is_empty()).then_some(sub),
            self.metadata.as_ref().map(LocationMetadata::normalized).filter(|m| !m.is_empty()),
        )
    }

    /// A stored entry for `location` (the app's `LocationEntry.from`).
    pub fn from(storage_id: &str, location: &LocationConfig, subscription_url: Option<String>, metadata: Option<LocationMetadata>) -> Self {
        Self::build(storage_id.to_string(), &location.normalized(), subscription_url, metadata).normalized()
    }

    fn build(storage_id: String, c: &LocationConfig, subscription_url: Option<String>, metadata: Option<LocationMetadata>) -> Self {
        Self {
            storage_id,
            name: c.name.clone(),
            description: c.description.clone(),
            subscription_url,
            endpoint: Some(LocationEndpointConfig { room_id: c.id.clone(), key: c.key.clone(), legacy_client_id: None }),
            engine: Some(c.engine),
            proxy: c.proxy.clone(),
            proxy2: c.proxy2.clone(),
            warp_enabled: c.warp_enabled,
            warp_mode: c.warp_mode,
            warp_protocol: c.warp_protocol,
            proxy_enabled: c.proxy_enabled,
            core: Some(c.core),
            vkturn: c.vkturn.clone(),
            master_dns: c.master_dns.clone(),
            open_flux: c.open_flux.clone(),
            snolc: c.snolc.clone(),
            advanced: c.advanced.clone(),
            fake_dns: c.fake_dns.clone(),
            routing_profile_id: (!c.routing_profile_id.trim().is_empty()).then(|| c.routing_profile_id.clone()),
            auth_provider: Some(c.bypass_provider.clone()),
            transport: Some(LocationTransportConfig::from_config(c)),
            multi_room_enabled: c.multi_room_enabled,
            extra_rooms: c.extra_rooms.clone(),
            multi_room_bond: c.multi_room_bond,
            bond_port: c.bond_port,
            metadata,
            ..Default::default()
        }
    }
}

pub const BUNDLE_VERSION: i32 = 5;

fn default_bundle_version() -> i32 {
    BUNDLE_VERSION
}

/// Every stored location plus the active one.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LocationBundleV4 {
    #[serde(default = "default_bundle_version")]
    pub version: i32,
    #[serde(rename = "active_location_id")]
    pub active_location_id: Option<String>,
    pub locations: Vec<LocationEntry>,
}

impl Default for LocationBundleV4 {
    fn default() -> Self {
        Self { version: BUNDLE_VERSION, active_location_id: None, locations: Vec::new() }
    }
}

impl LocationBundleV4 {
    /// Normalized entries, unstorable/duplicate ids dropped, active id kept valid.
    pub fn normalized(&self) -> Self {
        let mut seen = std::collections::HashSet::new();
        let locations: Vec<LocationEntry> = self
            .locations
            .iter()
            .map(LocationEntry::normalized)
            .filter(|e| not_blank(&e.storage_id) && e.location().is_storable())
            .filter(|e| seen.insert(e.storage_id.clone()))
            .collect();
        let active = self
            .active_location_id
            .clone()
            .filter(|id| locations.iter().any(|e| &e.storage_id == id))
            .or_else(|| locations.first().map(|e| e.storage_id.clone()));
        Self { version: BUNDLE_VERSION, active_location_id: active, locations }
    }
}
