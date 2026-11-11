//! The add/edit location form (port of the editing half of `LocationViewModel`): every field the
//! screen edits, the engine switches with what they stash and give back, validation, and what a
//! save has to write. A screen keeps one [`LocationEditor`] per open form; the list it edits from
//! is passed in where the form needs it.

use std::collections::BTreeMap;

use crate::awg::{self, Knob, Section};
use crate::location::{
    normalize_provider, normalize_transport, LocationConfig, DEFAULT_BYPASS_PROVIDER,
    DEFAULT_UPDATE_INTERVAL_HOURS, MAX_EXTRA_ROOMS, PROVIDER_JITSI,
};
use crate::location_list::{is_locked, LocationItem};
use crate::model::{EngineType, ProxyCore, ProxyProfile, TYPE_AMNEZIAWG, TYPE_OPENVPN, TYPE_VLESS};
use crate::panel;
use crate::repository::Repository;
use crate::transports::{
    AdvancedCoreConfig, ExtraRoom, MasterDnsConfig, OpenFluxConfig, SnolcConfig, WarpMode,
    WarpProtocol, CORE_CSQTT, CORE_WDTT, OUTBOUND_PROXY,
};
use crate::util::{filter_digits, is_blank, to_int_or_null, utf16_len};
use crate::vkturn::{self, VkTurnDraft};
use crate::vless_form::is_manually_editable_vless;
use crate::{share, share_link};

/// Longest location name, in UTF-16 units like the Kotlin app counted.
pub const MAX_NAME_LENGTH: usize = 45;
/// Longest olcRTC room id / URL.
pub const MAX_ROOM_LENGTH: usize = 256;

/// What is wrong with a field. [`FieldError::message`] is the app's (English) text; a screen
/// may show its own translation per variant.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FieldError {
    NameEmpty,
    NameTooLong,
    /// `jitsi`: the room is a URL there, an id elsewhere.
    RoomEmpty {
        jitsi: bool,
    },
    RoomTooLong {
        jitsi: bool,
    },
    KeyEmpty,
    KeyNotHex,
    UnrecognizedProxy,
    /// The cascade proxy is the main proxy itself.
    SecondProxySameAsMain,
}

impl FieldError {
    pub fn message(self) -> String {
        let room = |jitsi: bool| if jitsi { "Room URL" } else { "Room ID" };
        match self {
            FieldError::NameEmpty => "Name cannot be empty".to_string(),
            FieldError::NameTooLong => format!("Name is too long (max {MAX_NAME_LENGTH} chars)"),
            FieldError::RoomEmpty { jitsi } => format!("{} cannot be empty", room(jitsi)),
            FieldError::RoomTooLong { jitsi } => format!("{} is too long", room(jitsi)),
            FieldError::KeyEmpty => "Key cannot be empty".to_string(),
            FieldError::KeyNotHex => "Key must be 64 hex characters".to_string(),
            FieldError::UnrecognizedProxy => {
                "Unrecognized proxy link or sing-box config".to_string()
            }
            FieldError::SecondProxySameAsMain => {
                "This is the same server as the main proxy — a cascade into itself makes no sense"
                    .to_string()
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
struct ProviderDraft {
    room: String,
    key: String,
}

/// What [`LocationEditor::save`] decided.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SaveOutcome {
    /// Not saved: a field is invalid (the errors are set) or a save is already running.
    Invalid,
    /// A new location identical to a saved one: select that one instead of adding a copy.
    SelectExisting { storage_id: String },
    /// Store `config` under `storage_id` and make it the active location.
    Save {
        storage_id: String,
        config: Box<LocationConfig>,
        /// The edited location's subscription and its new refresh interval (hours).
        subscription_interval: Option<(String, i32)>,
    },
}

impl SaveOutcome {
    /// Performs the writes on `repo`; true when something was written.
    pub fn apply(&self, repo: &Repository) -> bool {
        match self {
            SaveOutcome::Invalid => false,
            SaveOutcome::SelectExisting { storage_id } => {
                repo.set_active_location_id(Some(storage_id.as_str()));
                true
            }
            SaveOutcome::Save {
                storage_id,
                config,
                subscription_interval,
            } => {
                repo.save_location(storage_id, config);
                if let Some((url, hours)) = subscription_interval {
                    repo.set_subscription_update_interval(url, *hours);
                }
                repo.set_active_location_id(Some(storage_id.as_str()));
                true
            }
        }
    }
}

/// The open add/edit form.
#[derive(Debug, Clone)]
pub struct LocationEditor {
    pub editing_config: LocationConfig,
    pub editing_name: String,
    pub editing_id: Option<String>,
    /// Editable OpenFlux fields for the OpenFlux engine.
    pub editing_open_flux: OpenFluxConfig,
    editing_subscription_url: Option<String>,
    editing_subscription_interval_hours: String,
    editing_service_provider: String,
    is_saving: bool,
    name_error: Option<FieldError>,
    server_error: Option<FieldError>,
    key_error: Option<FieldError>,
    /// The pasted share link / raw sing-box outbound of the main proxy (display text).
    editing_proxy_link: String,
    editing_vk_turn: VkTurnDraft,
    editing_master_dns: MasterDnsConfig,
    editing_snolc: SnolcConfig,
    proxy_error: Option<FieldError>,
    /// The pasted link of the optional second (cascade) proxy.
    editing_proxy2_link: String,
    proxy2_error: Option<FieldError>,
    is_vless_engine: bool,
    // What the AmneziaWG/WG engine pushed aside when it was picked, and the obfuscation removed by
    // switching to plain WireGuard.
    wg_stashed_proxy: Option<ProxyProfile>,
    wg_stashed_link: String,
    awg_backup: Vec<(String, String)>,
    provider_drafts: BTreeMap<String, ProviderDraft>,
}

impl Default for LocationEditor {
    fn default() -> Self {
        Self::new()
    }
}

impl LocationEditor {
    pub fn new() -> Self {
        Self {
            editing_config: LocationConfig::default(),
            editing_name: String::new(),
            editing_id: None,
            editing_open_flux: OpenFluxConfig::default(),
            editing_subscription_url: None,
            editing_subscription_interval_hours: DEFAULT_UPDATE_INTERVAL_HOURS.to_string(),
            editing_service_provider: DEFAULT_BYPASS_PROVIDER.to_string(),
            is_saving: false,
            name_error: None,
            server_error: None,
            key_error: None,
            editing_proxy_link: String::new(),
            editing_vk_turn: VkTurnDraft::default(),
            editing_master_dns: MasterDnsConfig::default(),
            editing_snolc: SnolcConfig::default(),
            proxy_error: None,
            editing_proxy2_link: String::new(),
            proxy2_error: None,
            is_vless_engine: false,
            wg_stashed_proxy: None,
            wg_stashed_link: String::new(),
            awg_backup: Vec::new(),
            provider_drafts: BTreeMap::new(),
        }
    }

    // ---------------- read-only state ----------------

    pub fn editing_subscription_url(&self) -> Option<&str> {
        self.editing_subscription_url.as_deref()
    }
    pub fn editing_subscription_interval_hours(&self) -> &str {
        &self.editing_subscription_interval_hours
    }
    /// The service picked for the olcRTC room (Jitsi is never shown as the picked one).
    pub fn editing_service_provider(&self) -> &str {
        &self.editing_service_provider
    }
    pub fn is_saving(&self) -> bool {
        self.is_saving
    }
    pub fn name_error(&self) -> Option<FieldError> {
        self.name_error
    }
    pub fn server_error(&self) -> Option<FieldError> {
        self.server_error
    }
    pub fn key_error(&self) -> Option<FieldError> {
        self.key_error
    }
    pub fn proxy_error(&self) -> Option<FieldError> {
        self.proxy_error
    }
    pub fn proxy2_error(&self) -> Option<FieldError> {
        self.proxy2_error
    }
    pub fn editing_proxy_link(&self) -> &str {
        &self.editing_proxy_link
    }
    pub fn editing_proxy2_link(&self) -> &str {
        &self.editing_proxy2_link
    }
    /// Editable VK-TURN (freeturn + WireGuard) fields for the VK-TURN engine.
    pub fn editing_vk_turn(&self) -> &VkTurnDraft {
        &self.editing_vk_turn
    }
    /// Editable MasterDNS fields for the MasterDNS engine.
    pub fn editing_master_dns(&self) -> &MasterDnsConfig {
        &self.editing_master_dns
    }
    /// Editable snolc fields for the snolc engine.
    pub fn editing_snolc(&self) -> &SnolcConfig {
        &self.editing_snolc
    }
    /// The «VLESS» entry of the type picker: a Standard location with a hand-made VLESS proxy.
    pub fn is_vless_engine(&self) -> bool {
        self.is_vless_engine
    }

    /// The edited location comes from an encrypted subscription: its settings are not shown.
    pub fn editing_locked(&self, items: &[LocationItem]) -> bool {
        is_locked(items, self.editing_id.as_deref())
    }

    /// The «AmneziaWG/WG» engine: a Standard location whose main proxy is a wg-quick INI.
    pub fn is_wireguard_engine(&self) -> bool {
        self.editing_config.engine == EngineType::Standard && self.wireguard_proxy().is_some()
    }

    fn wireguard_proxy(&self) -> Option<&ProxyProfile> {
        self.editing_config
            .proxy
            .as_ref()
            .filter(|p| p.kind == TYPE_AMNEZIAWG)
    }

    /// The INI has every parameter the engine refuses to start without (an empty template does not).
    fn wireguard_config_usable(&self) -> bool {
        self.wireguard_proxy()
            .is_none_or(|p| awg::missing(&p.awg_config).is_empty())
    }

    /// What the INI still lacks, for the editor's hint.
    pub fn wireguard_missing(&self) -> Vec<&'static str> {
        self.wireguard_proxy()
            .map(|p| awg::missing(&p.awg_config))
            .unwrap_or_default()
    }

    fn olcrtc_fields_valid(&self) -> bool {
        self.server_error.is_none()
            && self.key_error.is_none()
            && !is_blank(&self.editing_config.id)
            && !is_blank(&self.editing_config.key)
    }

    /// The freeturn peer + WireGuard keys needed to reach the tunnel (the per-client VK call link
    /// is not needed to save). WDTT and csqtt only need their server and a local listener.
    fn vk_turn_fields_valid(&self) -> bool {
        let d = &self.editing_vk_turn;
        let port_ok = |v: &str| (1..=65535).contains(&to_int_or_null(v.trim()).unwrap_or(0));
        if d.core.eq_ignore_ascii_case(CORE_WDTT) {
            return !is_blank(&d.wdtt_peer) && port_ok(&d.listen_port);
        }
        if d.core.eq_ignore_ascii_case(CORE_CSQTT) {
            return !is_blank(&d.csqtt_peer) && port_ok(&d.listen_port);
        }
        let peer_ok = !is_blank(&d.peer_host) && port_ok(&d.peer_port) && port_ok(&d.listen_port);
        let exit_ok = if d.outbound == OUTBOUND_PROXY {
            share_link::parse(d.outbound_proxy_link.trim()).is_some_and(|p| p.is_complete())
        } else {
            !is_blank(&d.wg_private_key)
                && !is_blank(&d.wg_peer_public_key)
                && !is_blank(&d.wg_address)
        };
        peer_ok && exit_ok
    }

    pub fn is_form_valid(&self) -> bool {
        let c = &self.editing_config;
        let main_proxy_ok = c.proxy.as_ref().is_some_and(ProxyProfile::is_complete);
        self.name_error.is_none()
            && !is_blank(&self.editing_name)
            && match c.engine {
                EngineType::Stealth => self.olcrtc_fields_valid(),
                EngineType::Standard => main_proxy_ok && self.wireguard_config_usable(),
                EngineType::Chain => main_proxy_ok && self.olcrtc_fields_valid(),
                EngineType::VkTurn => self.vk_turn_fields_valid(),
                EngineType::MasterDns => self.editing_master_dns.is_complete(),
                EngineType::OpenFlux => self.editing_open_flux.is_complete(),
                EngineType::Snolc => self.editing_snolc.is_complete(),
            }
    }

    // ---------------- opening the form ----------------

    /// Opens the form for the location `id` of `items`, or for a new one (`None`).
    pub fn start_editing(&mut self, items: &[LocationItem], id: Option<&str>) {
        self.name_error = None;
        self.server_error = None;
        self.key_error = None;
        self.proxy_error = None;
        self.proxy2_error = None;
        self.editing_proxy_link.clear();
        self.editing_proxy2_link.clear();
        self.is_saving = false;
        self.provider_drafts.clear();
        self.wg_stashed_proxy = None;
        self.wg_stashed_link.clear();
        self.awg_backup.clear();

        match id {
            None => {
                self.editing_id = None;
                // A new location starts with the (vestigial) proxy switch off; saved ones keep theirs.
                self.editing_config = LocationConfig {
                    proxy_enabled: false,
                    ..LocationConfig::default()
                };
                self.editing_name.clear();
                self.editing_subscription_url = None;
                self.editing_subscription_interval_hours =
                    DEFAULT_UPDATE_INTERVAL_HOURS.to_string();
            }
            Some(id) => {
                let location = items.iter().find(|it| it.storage_id == id);
                self.editing_id = Some(id.to_string());
                self.editing_config = location
                    .and_then(|l| l.config.as_ref())
                    .map(LocationConfig::normalized)
                    .unwrap_or_default();
                self.editing_name = self.editing_config.display_name();
                // Display-only: saving keeps the stored proxies unless the field is edited.
                self.editing_proxy_link = self
                    .editing_config
                    .proxy
                    .as_ref()
                    .and_then(share::compose)
                    .unwrap_or_default();
                self.editing_proxy2_link = self
                    .editing_config
                    .proxy2
                    .as_ref()
                    .and_then(share::compose)
                    .unwrap_or_default();
                self.editing_subscription_url = location.and_then(|l| l.subscription_url.clone());
                self.editing_subscription_interval_hours = location
                    .and_then(|l| l.metadata.as_ref())
                    .and_then(|m| m.subscription.as_ref())
                    .and_then(|s| s.update_interval_hours)
                    .unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS)
                    .to_string();
            }
        }
        let c = &self.editing_config;
        self.editing_vk_turn = if c.engine == EngineType::VkTurn {
            vkturn::decompose(c.vkturn.as_ref(), c.proxy.as_ref())
        } else {
            VkTurnDraft::default()
        };
        self.editing_master_dns = c.master_dns.clone().unwrap_or_default();
        self.editing_open_flux = c.open_flux.clone().unwrap_or_default();
        self.editing_snolc = c.snolc.clone().unwrap_or_default();
        self.is_vless_engine = c.engine == EngineType::Standard
            && c.proxy
                .as_ref()
                .is_some_and(|p| p.kind == TYPE_VLESS && is_manually_editable_vless(Some(p)));
        let provider = normalize_provider(&c.bypass_provider);
        self.editing_service_provider = if provider == PROVIDER_JITSI {
            DEFAULT_BYPASS_PROVIDER
        } else {
            provider
        }
        .to_string();
        self.provider_drafts.insert(
            provider.to_string(),
            ProviderDraft {
                room: c.id.clone(),
                key: c.key.clone(),
            },
        );
    }

    // ---------------- olcRTC ----------------

    pub fn on_name_changed(&mut self, value: &str) {
        self.editing_name = value.to_string();
        self.validate_name(value);
        // The derived VK-TURN proxy tag follows the name.
        if self.editing_config.engine == EngineType::VkTurn {
            self.update_vk_turn_draft(|d| d);
        }
    }

    pub fn on_server_changed(&mut self, value: &str) {
        self.editing_config.id = value.to_string();
        self.validate_server(value);
    }

    pub fn on_sni_changed(&mut self, _value: &str) {}

    pub fn on_password_changed(&mut self, value: &str) {
        self.editing_config.key = value.to_string();
        self.validate_key(value);
    }

    /// Lays an olcRTC VPS install out on the location: the first room is the main one, the others
    /// become extra rooms (multi-room on). Key, service and transport match the server's.
    pub fn apply_olcrtc_server_install(
        &mut self,
        provider: &str,
        transport: &str,
        rooms: &[String],
        key: &str,
    ) {
        let clean: Vec<String> = rooms
            .iter()
            .map(|r| r.trim().to_string())
            .filter(|r| !is_blank(r))
            .collect();
        let Some(first) = clean.first().cloned() else {
            return;
        };
        let extras: Vec<ExtraRoom> = clean[1..]
            .iter()
            .take(MAX_EXTRA_ROOMS)
            .map(|room| ExtraRoom {
                provider: provider.to_string(),
                transport: transport.to_string(),
                room: room.clone(),
                key: key.to_string(),
            })
            .collect();
        let c = &mut self.editing_config;
        c.bypass_provider = provider.to_string();
        c.transport = normalize_transport(transport, provider).to_string();
        c.id = first.clone();
        c.key = key.to_string();
        c.multi_room_enabled = !extras.is_empty();
        c.extra_rooms = extras;
        self.validate_server(&first);
        self.validate_key(key);
    }

    pub fn on_multi_room_toggle(&mut self, enabled: bool) {
        self.editing_config.multi_room_enabled = enabled;
    }

    /// Chain: bond the rooms into one reassembled stream instead of round-robin.
    pub fn on_multi_room_bond_toggle(&mut self, enabled: bool) {
        self.editing_config.multi_room_bond = enabled;
    }

    pub fn on_bond_port_changed(&mut self, value: &str) {
        self.editing_config.bond_port = to_int_or_null(&filter_digits(value)).unwrap_or(0);
    }

    pub fn on_extra_room_add(&mut self) {
        let c = &mut self.editing_config;
        if c.extra_rooms.len() >= MAX_EXTRA_ROOMS {
            return;
        }
        let room = ExtraRoom {
            provider: c.bypass_provider.clone(),
            transport: c.transport.clone(),
            ..ExtraRoom::default()
        };
        c.extra_rooms.push(room);
    }

    pub fn on_extra_room_changed(
        &mut self,
        index: usize,
        transform: impl FnOnce(ExtraRoom) -> ExtraRoom,
    ) {
        if let Some(room) = self.editing_config.extra_rooms.get_mut(index) {
            *room = transform(std::mem::take(room));
        }
    }

    pub fn on_extra_room_removed(&mut self, index: usize) {
        if index < self.editing_config.extra_rooms.len() {
            self.editing_config.extra_rooms.remove(index);
        }
    }

    pub fn on_bypass_provider_changed(&mut self, value: &str) {
        let provider = normalize_provider(value);
        let current = normalize_provider(&self.editing_config.bypass_provider);
        if provider == current {
            return;
        }
        let draft = ProviderDraft {
            room: self.editing_config.id.clone(),
            key: self.editing_config.key.clone(),
        };
        self.provider_drafts.insert(current.to_string(), draft);
        if provider != PROVIDER_JITSI {
            self.editing_service_provider = provider.to_string();
        }
        let restored = self
            .provider_drafts
            .get(provider)
            .cloned()
            .unwrap_or_default();
        let c = &mut self.editing_config;
        c.bypass_provider = provider.to_string();
        c.transport = normalize_transport(&c.transport, provider).to_string();
        c.id = restored.room;
        c.key = restored.key;
        self.server_error = None;
        self.key_error = None;
    }

    pub fn on_transport_changed(&mut self, value: &str) {
        let c = &mut self.editing_config;
        c.transport = normalize_transport(value, &c.bypass_provider).to_string();
    }

    pub fn on_vp8_fps_changed(&mut self, value: &str) {
        self.editing_config.vp8_fps = to_int_or_null(&filter_digits(value)).unwrap_or(0);
    }

    pub fn on_vp8_batch_changed(&mut self, value: &str) {
        self.editing_config.vp8_batch = to_int_or_null(&filter_digits(value)).unwrap_or(0);
    }

    pub fn on_subscription_interval_changed(&mut self, value: &str) {
        self.editing_subscription_interval_hours = filter_digits(value).chars().take(3).collect();
    }

    // ---------------- cores, WARP, routing, advanced ----------------

    /// OpenVPN is sing-box-only; xhttp is Xray-only. Coerce stale/programmatic choices too.
    pub fn on_core_changed(&mut self, core: ProxyCore) {
        let has_openvpn = self
            .editing_config
            .proxy
            .iter()
            .chain(self.editing_config.proxy2.iter())
            .any(|p| p.kind == TYPE_OPENVPN);
        let effective = if has_openvpn {
            ProxyCore::SingBox
        } else if core == ProxyCore::SingBox && self.editing_config.requires_xray() {
            ProxyCore::Xray
        } else {
            core
        };
        self.editing_config.core = effective;
    }

    pub fn on_proxy_enabled_changed(&mut self, enabled: bool) {
        self.editing_config.proxy_enabled = enabled;
    }

    pub fn on_warp_enabled_changed(&mut self, enabled: bool) {
        self.editing_config.warp_enabled = enabled;
    }

    pub fn on_warp_mode_changed(&mut self, mode: WarpMode) {
        self.editing_config.warp_mode = mode;
    }

    pub fn on_warp_protocol_changed(&mut self, protocol: WarpProtocol) {
        self.editing_config.warp_protocol = protocol;
    }

    /// "" = the global default profile, the `none` id = no profile.
    pub fn on_routing_profile_changed(&mut self, id: &str) {
        self.editing_config.routing_profile_id = id.to_string();
    }

    /// Edits the per-location core options (mux / tfo / sniff / fragment).
    pub fn update_advanced(
        &mut self,
        transform: impl FnOnce(AdvancedCoreConfig) -> AdvancedCoreConfig,
    ) {
        let current = self.editing_config.advanced.take().unwrap_or_default();
        self.editing_config.advanced = Some(transform(current));
    }

    // ---------------- VK-TURN, MasterDNS, OpenFlux, snolc ----------------

    /// The per-client VK Calls join link (not carried in the share link).
    pub fn on_vk_link_changed(&mut self, value: &str) {
        self.update_vk_turn_draft(|d| VkTurnDraft {
            vk_link: value.to_string(),
            ..d
        });
    }

    /// Edits the VK-TURN draft and rebuilds the freeturn link + exit outbound it derives.
    pub fn update_vk_turn_draft(&mut self, transform: impl FnOnce(VkTurnDraft) -> VkTurnDraft) {
        let updated = transform(std::mem::take(&mut self.editing_vk_turn));
        let name = if is_blank(&self.editing_name) {
            "VK-TURN"
        } else {
            self.editing_name.as_str()
        };
        let (vkturn, proxy) = vkturn::compose(&updated, name);
        self.editing_config.vkturn = Some(vkturn);
        self.editing_config.proxy = Some(proxy);
        self.editing_vk_turn = updated;
    }

    pub fn update_open_flux(&mut self, transform: impl FnOnce(OpenFluxConfig) -> OpenFluxConfig) {
        self.editing_open_flux = transform(std::mem::take(&mut self.editing_open_flux));
        self.editing_config.open_flux = Some(self.editing_open_flux.clone());
    }

    pub fn update_snolc(&mut self, transform: impl FnOnce(SnolcConfig) -> SnolcConfig) {
        self.editing_snolc = transform(std::mem::take(&mut self.editing_snolc));
        self.editing_config.snolc = Some(self.editing_snolc.clone());
    }

    pub fn update_master_dns(
        &mut self,
        transform: impl FnOnce(MasterDnsConfig) -> MasterDnsConfig,
    ) {
        self.editing_master_dns = transform(std::mem::take(&mut self.editing_master_dns));
        self.editing_config.master_dns = Some(self.editing_master_dns.clone());
    }

    // ---------------- engines ----------------

    pub fn on_engine_changed(&mut self, engine: EngineType) {
        self.is_vless_engine = false;
        if self.is_wireguard_engine() {
            // Leaving AmneziaWG/WG: give back the proxy it replaced (none if it was always WG).
            self.editing_config.proxy = self.wg_stashed_proxy.take();
            self.editing_proxy_link = std::mem::take(&mut self.wg_stashed_link);
            self.proxy_error = None;
        }
        let previous = self.editing_config.engine;
        self.editing_config.engine = engine;
        if engine == EngineType::Standard {
            self.server_error = None;
            self.key_error = None;
        }
        if engine == EngineType::VkTurn {
            // A freshly picked VK-TURN location is consistent at once.
            self.update_vk_turn_draft(|d| d);
        } else if previous == EngineType::VkTurn {
            // The derived WireGuard outbound must not leak into proxy engines.
            self.editing_config.vkturn = None;
            self.editing_config.proxy = None;
            self.editing_config.proxy2 = None;
            self.editing_proxy_link.clear();
            self.editing_proxy2_link.clear();
            self.proxy_error = None;
            self.proxy2_error = None;
        }
        if engine == EngineType::MasterDns {
            self.editing_config.master_dns = Some(self.editing_master_dns.clone());
        } else if previous == EngineType::MasterDns {
            self.editing_config.master_dns = None;
        }
        if engine == EngineType::OpenFlux {
            self.editing_config.open_flux = Some(self.editing_open_flux.clone());
        } else if previous == EngineType::OpenFlux {
            self.editing_config.open_flux = None;
        }
        if engine == EngineType::Snolc {
            self.editing_config.snolc = Some(self.editing_snolc.clone());
        } else if previous == EngineType::Snolc {
            self.editing_config.snolc = None;
        }
    }

    /// Picks the «VLESS» entry: a Standard location whose main proxy is edited field by field.
    pub fn on_vless_engine_selected(&mut self) {
        if self.is_vless_engine {
            return;
        }
        self.on_engine_changed(EngineType::Standard);
        if self
            .editing_config
            .proxy
            .as_ref()
            .is_none_or(|p| p.kind != TYPE_VLESS)
        {
            self.editing_config.proxy = None;
            self.editing_proxy_link.clear();
            self.proxy_error = None;
        }
        self.is_vless_engine = true;
    }

    /// Picks the AmneziaWG/WG engine: an empty AmneziaWG template to fill in or paste over.
    pub fn on_wireguard_engine_selected(&mut self) {
        if self.is_wireguard_engine() {
            return;
        }
        self.on_engine_changed(EngineType::Standard);
        self.wg_stashed_proxy = self.editing_config.proxy.clone();
        self.wg_stashed_link = std::mem::take(&mut self.editing_proxy_link);
        self.proxy_error = None;
        self.apply_wireguard_ini(awg::skeleton(true));
    }

    /// AmneziaWG (obfuscation parameters) or plain WireGuard (none); the removed parameters come
    /// back on the way back.
    pub fn on_wireguard_protocol_changed(&mut self, amnezia: bool) {
        let Some(ini) = self.wireguard_proxy().map(|p| p.awg_config.clone()) else {
            return;
        };
        if amnezia == awg::is_awg(&ini) {
            return;
        }
        if amnezia {
            let restored = self.awg_backup.iter().fold(ini, |acc, (key, value)| {
                awg::set(&acc, key, value, Section::Interface)
            });
            self.apply_wireguard_ini(awg::to_amneziawg(&restored));
        } else {
            self.awg_backup = awg::obfuscation(&ini);
            self.apply_wireguard_ini(awg::to_wireguard(&ini));
        }
    }

    /// The whole INI typed or pasted into the text field.
    pub fn on_wireguard_ini_changed(&mut self, text: &str) {
        self.apply_wireguard_ini(text.replace("\r\n", "\n").replace('\r', "\n"));
    }

    /// Fine-tuning: sets one parameter of the main proxy's INI (blank removes it); the rest stays.
    pub fn on_awg_knob_changed(&mut self, knob: &Knob, value: &str) {
        let Some(ini) = self.wireguard_proxy().map(|p| p.awg_config.clone()) else {
            return;
        };
        self.apply_wireguard_ini(awg::set(
            &ini,
            knob.key,
            &awg::filter(value, knob.kind),
            knob.section,
        ));
    }

    /// Stores `ini` as the main proxy, keeping its tag; server/port follow the Endpoint for the
    /// list and de-duplication.
    fn apply_wireguard_ini(&mut self, ini: String) {
        let (host, port) = awg::endpoint(&ini);
        let previous = self.wireguard_proxy().cloned();
        let mut profile = previous.unwrap_or_else(|| ProxyProfile {
            tag: "AmneziaWG".to_string(),
            kind: TYPE_AMNEZIAWG.to_string(),
            ..ProxyProfile::default()
        });
        profile.server = if is_blank(&host) {
            "amneziawg".to_string()
        } else {
            host
        };
        profile.server_port = port;
        profile.awg_config = ini;
        self.editing_config.proxy = Some(profile);
    }

    // ---------------- proxies ----------------

    /// The manual VLESS form: applies `edit` to the main proxy (a fresh VLESS one when there is none).
    pub fn edit_vless(&mut self, edit: impl FnOnce(ProxyProfile) -> ProxyProfile) {
        let current = self
            .editing_config
            .proxy
            .clone()
            .filter(|p| p.kind == TYPE_VLESS)
            .unwrap_or_default();
        let profile = edit(current);
        self.editing_proxy_link = share::compose(&profile).unwrap_or_default();
        self.editing_config.proxy = Some(profile);
        self.proxy_error = None;
    }

    /// A pasted share link or raw sing-box outbound becomes the main proxy.
    pub fn on_proxy_link_changed(&mut self, value: &str) {
        self.editing_proxy_link = value.to_string();
        let trimmed = value.trim();
        if is_blank(trimmed) {
            self.editing_config.proxy = None;
            self.proxy_error = None;
            return;
        }
        match share_link::parse(trimmed).or_else(|| raw_outbound_profile(trimmed)) {
            Some(profile) if profile.is_complete() => {
                self.editing_config.proxy = Some(profile);
                self.proxy_error = None;
            }
            _ => {
                self.editing_config.proxy = None;
                self.proxy_error = Some(FieldError::UnrecognizedProxy);
            }
        }
    }

    /// A pasted link for the optional second (cascade) proxy. While a Standard/Chain location has
    /// no complete main proxy yet, the link becomes the main one (that field is hidden there); the
    /// main proxy itself is refused as its own cascade exit.
    pub fn on_proxy2_link_changed(&mut self, value: &str) {
        self.editing_proxy2_link = value.to_string();
        let trimmed = value.trim();
        if is_blank(trimmed) {
            self.editing_config.proxy2 = None;
            self.proxy2_error = None;
            return;
        }
        let Some(profile) = proxy_from_any_link(trimmed).filter(ProxyProfile::is_complete) else {
            self.editing_config.proxy2 = None;
            self.proxy2_error = Some(FieldError::UnrecognizedProxy);
            return;
        };
        let chain_or_standard = matches!(
            self.editing_config.engine,
            EngineType::Standard | EngineType::Chain
        );
        let main = self
            .editing_config
            .proxy
            .as_ref()
            .filter(|p| p.is_complete());
        if chain_or_standard && main.is_none() {
            self.editing_config.proxy = Some(profile);
            self.editing_proxy_link = trimmed.to_string();
            self.proxy2_error = None;
        } else if main.is_some_and(|m| m.is_same_node_as(&profile)) {
            self.editing_config.proxy2 = None;
            self.proxy2_error = Some(FieldError::SecondProxySameAsMain);
        } else {
            self.editing_config.proxy2 = Some(profile);
            self.proxy2_error = None;
        }
    }

    // ---------------- validation and saving ----------------

    fn validate_name(&mut self, name: &str) {
        self.name_error = if is_blank(name) {
            Some(FieldError::NameEmpty)
        } else if utf16_len(name) > MAX_NAME_LENGTH {
            Some(FieldError::NameTooLong)
        } else {
            None
        };
    }

    fn validate_server(&mut self, server: &str) {
        let jitsi = self.editing_config.bypass_provider == PROVIDER_JITSI;
        self.server_error = if is_blank(server) {
            Some(FieldError::RoomEmpty { jitsi })
        } else if utf16_len(server) > MAX_ROOM_LENGTH {
            Some(FieldError::RoomTooLong { jitsi })
        } else {
            None
        };
    }

    fn validate_key(&mut self, key: &str) {
        self.key_error = if is_blank(key) {
            Some(FieldError::KeyEmpty)
        } else if !is_olcrtc_key(key) {
            Some(FieldError::KeyNotHex)
        } else {
            None
        };
    }

    /// Validates the form and says what to write. A brand-new location identical to a saved one
    /// selects that one instead. Marks the form as saving until [`Self::finish_saving`].
    pub fn save(&mut self, items: &[LocationItem]) -> SaveOutcome {
        let name = self.editing_name.clone();
        self.validate_name(&name);
        // The olcRTC fields are only required off the Standard engine.
        if self.editing_config.engine != EngineType::Standard {
            let (id, key) = (
                self.editing_config.id.clone(),
                self.editing_config.key.clone(),
            );
            self.validate_server(&id);
            self.validate_key(&key);
        } else {
            self.server_error = None;
            self.key_error = None;
        }
        if !self.is_form_valid() || self.is_saving {
            return SaveOutcome::Invalid;
        }
        self.is_saving = true;
        let config = LocationConfig {
            name,
            ..self.editing_config.clone()
        }
        .normalized();
        if self.editing_id.is_none() {
            let key = config.dedup_key();
            if let Some(existing) = items
                .iter()
                .find(|it| it.config.as_ref().is_some_and(|c| c.dedup_key() == key))
            {
                return SaveOutcome::SelectExisting {
                    storage_id: existing.storage_id.clone(),
                };
            }
        }
        let storage_id = self
            .editing_id
            .clone()
            .unwrap_or_else(|| new_custom_id(items));
        let subscription_interval = self.editing_subscription_url.clone().map(|url| {
            (
                url,
                to_int_or_null(&self.editing_subscription_interval_hours)
                    .unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS),
            )
        });
        SaveOutcome::Save {
            storage_id,
            config: Box::new(config),
            subscription_interval,
        }
    }

    /// The save's writes are done (or failed): the form can save again.
    pub fn finish_saving(&mut self) {
        self.is_saving = false;
    }
}

/// An olcRTC key: 64 hex characters.
pub fn is_olcrtc_key(key: &str) -> bool {
    key.len() == 64 && key.bytes().all(|b| b.is_ascii_hexdigit())
}

/// A storage id for a new own location: `custom_` + a random three-digit number no row uses yet
/// (a longer one only when all 900 are taken).
pub fn new_custom_id(items: &[LocationItem]) -> String {
    let taken = |id: &str| items.iter().any(|it| it.storage_id == id);
    for _ in 0..64 {
        let id = format!("custom_{}", 100 + random_u32() % 900);
        if !taken(&id) {
            return id;
        }
    }
    if let Some(id) = (100..1000)
        .map(|n| format!("custom_{n}"))
        .find(|id| !taken(id))
    {
        return id;
    }
    loop {
        let id = format!("custom_{}_{}", 100 + random_u32() % 900, random_u32());
        if !taken(&id) {
            return id;
        }
    }
}

fn random_u32() -> u32 {
    let mut bytes = [0u8; 4];
    if getrandom::getrandom(&mut bytes).is_err() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.subsec_nanos());
        return nanos;
    }
    u32::from_le_bytes(bytes)
}

/// A pasted cascade link as a proxy: a share link, a raw sing-box outbound, or a `yptun://inbound`
/// link — whose exit (its second proxy, else its main one) becomes the hop, so the public IP
/// matches the shared location's.
pub fn proxy_from_any_link(trimmed: &str) -> Option<ProxyProfile> {
    if let Some(p) = share_link::parse(trimmed) {
        return Some(p);
    }
    if let Some(p) = raw_outbound_profile(trimmed) {
        return Some(p);
    }
    panel::parse_inbound(trimmed).and_then(|config| config.proxy2.or(config.proxy))
}

/// A raw sing-box outbound (`{"type": …, "server": …}`) as a proxy run verbatim.
pub fn raw_outbound_profile(text: &str) -> Option<ProxyProfile> {
    if !text.starts_with('{') {
        return None;
    }
    let value: serde_json::Value = serde_json::from_str(text).ok()?;
    let obj = value.as_object()?;
    let content = |key: &str| match obj.get(key)? {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Number(n) => Some(n.to_string()),
        serde_json::Value::Bool(b) => Some(b.to_string()),
        _ => None,
    };
    let server = content("server")?;
    let kind = content("type")?;
    let port = match obj.get("server_port") {
        Some(serde_json::Value::Number(n)) => n.as_i64().and_then(|v| i32::try_from(v).ok()),
        Some(serde_json::Value::String(s)) => s.parse::<i32>().ok(),
        _ => None,
    }
    .unwrap_or(0);
    let tag = content("tag")
        .filter(|t| !is_blank(t))
        .unwrap_or_else(|| server.clone());
    Some(ProxyProfile {
        tag,
        kind,
        server,
        server_port: port,
        raw_outbound: Some(text.to_string()),
        ..ProxyProfile::default()
    })
}
