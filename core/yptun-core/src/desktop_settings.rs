//! Pure parts of DesktopSettingsController.kt: the desktop settings surface (routing, routing
//! profiles, traffic, app behavior, split tunneling, UI preferences) without StateFlows or Compose.
//! Every change is written straight away through [`VpnSettings`] / `settings/ui.json`, like the
//! Kotlin controller. Platform-side: ThemeState/LocalizationState, listing processes, deleting and
//! downloading the geo files.

use std::collections::BTreeSet;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::i18n::AppLanguage;
use crate::routing::{parse_routing_profile, RoutingProfile, RoutingProfilesState, RoutingRules, TrafficSettings};
use crate::settings::AppBehaviorSettings;
use crate::vpn_settings::{SplitTunnelState, VpnSettings};

pub const UI_FILE: &str = "ui.json";
pub const CONNECTION_MODE_TUN: &str = "tun";
pub const CONNECTION_MODE_PROXY: &str = "proxy";
pub const ROUTING_PROFILE_ID_PREFIX: &str = "rp-";
pub const GEO_DOWNLOAD_FAILED: &str = "download failed";

/// Desktop UI preferences persisted as one JSON file (theme, language, connection mode).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct DesktopUiSettings {
    pub language: String,
    pub connection_mode: String,
    pub dynamic_theme: bool,
    pub light_theme: bool,
    pub accent_argb: Option<i32>,
    pub text_argb: Option<i32>,
    pub background_argb: Option<i32>,
}

impl Default for DesktopUiSettings {
    fn default() -> Self {
        Self {
            language: "system".into(),
            connection_mode: CONNECTION_MODE_TUN.into(),
            dynamic_theme: false,
            light_theme: false,
            accent_argb: None,
            text_argb: None,
            background_argb: None,
        }
    }
}

/// What "System" resolves to for a `Locale.getDefault().language` code.
pub fn system_language(locale_language: &str) -> AppLanguage {
    match locale_language {
        "ru" => AppLanguage::Russian,
        "fa" => AppLanguage::Persian,
        "zh" => AppLanguage::Chinese,
        "sk" => AppLanguage::Slovak,
        "cs" => AppLanguage::Czech,
        "be" => AppLanguage::Belarusian,
        _ => AppLanguage::English,
    }
}

/// AndroidConnectionMode.fromValue: "proxy" or else tun.
pub fn connection_mode_from_value(value: &str) -> &'static str {
    if value == CONNECTION_MODE_PROXY {
        CONNECTION_MODE_PROXY
    } else {
        CONNECTION_MODE_TUN
    }
}

/// The mode at startup and whether it must be saved. A portable copy that is not elevated always
/// starts in proxy mode (no UAC prompt on launch).
pub fn initial_connection_mode(saved: &str, portable: bool, elevated: bool) -> (&'static str, bool) {
    if portable && !elevated {
        (CONNECTION_MODE_PROXY, saved != CONNECTION_MODE_PROXY)
    } else {
        (connection_mode_from_value(saved), false)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SplitTunnelList {
    Proxy,
    Bypass,
}

pub fn toggled(set: &BTreeSet<String>, value: &str) -> BTreeSet<String> {
    let mut out = set.clone();
    if !out.remove(value) {
        out.insert(value.to_string());
    }
    out
}

pub fn with_split_tunnel_mode(state: &SplitTunnelState, mode: &str) -> SplitTunnelState {
    SplitTunnelState { mode: mode.to_string(), ..state.clone() }
}

pub fn toggle_split_tunnel_app(state: &SplitTunnelState, list: SplitTunnelList, process: &str) -> SplitTunnelState {
    let mut out = state.clone();
    match list {
        SplitTunnelList::Proxy => out.proxy_processes = toggled(&state.proxy_processes, process),
        SplitTunnelList::Bypass => out.bypass_processes = toggled(&state.bypass_processes, process),
    }
    out
}

pub fn set_split_tunnel_apps(state: &SplitTunnelState, list: SplitTunnelList, processes: BTreeSet<String>) -> SplitTunnelState {
    let mut out = state.clone();
    match list {
        SplitTunnelList::Proxy => out.proxy_processes = processes,
        SplitTunnelList::Bypass => out.bypass_processes = processes,
    }
    out
}

/// The file name of a process command (after the last `\\`, then after the last `/`).
pub fn process_exe_name(command: &str) -> &str {
    let a = command.rsplit('\\').next().unwrap_or(command);
    a.rsplit('/').next().unwrap_or(a)
}

fn ends_with_exe(name: &str) -> bool {
    name.len() >= 4 && name.get(name.len() - 4..).is_some_and(|t| t.eq_ignore_ascii_case(".exe"))
}

/// The desktop "installed apps" list: distinct exe names of the running process commands plus
/// everything already selected, sorted case-insensitively.
pub fn installed_apps(commands: &[String], state: &SplitTunnelState) -> Vec<String> {
    let mut running: Vec<String> = commands
        .iter()
        .filter(|c| !c.trim().is_empty())
        .map(|c| process_exe_name(c))
        .filter(|n| ends_with_exe(n) || !n.contains('.'))
        .map(String::from)
        .collect();
    running.sort();
    running.dedup();
    let selected = state.proxy_processes.iter().chain(state.bypass_processes.iter());
    let mut out: Vec<String> = Vec::new();
    for name in running.iter().chain(selected) {
        if !out.contains(name) {
            out.push(name.clone());
        }
    }
    out.sort_by_key(|n| n.to_lowercase());
    out
}

/// `rp-` + 8 random hex characters (Kotlin: first 8 characters of a random UUID).
pub fn new_routing_profile_id() -> String {
    let mut b = [0u8; 4];
    let _ = getrandom::getrandom(&mut b);
    format!("{ROUTING_PROFILE_ID_PREFIX}{}", b.iter().map(|x| format!("{x:02x}")).collect::<String>())
}

/// Adds or replaces [`profile`] (a blank id gets a fresh one) and returns its id.
pub fn save_routing_profile(state: &RoutingProfilesState, profile: RoutingProfile) -> (RoutingProfilesState, String) {
    let id = if profile.id.trim().is_empty() { new_routing_profile_id() } else { profile.id.clone() };
    let with_id = RoutingProfile { id: id.clone(), ..profile };
    let mut out = state.clone();
    out.profiles.retain(|p| p.id != id);
    out.profiles.push(with_id);
    (out, id)
}

pub fn delete_routing_profile(state: &RoutingProfilesState, id: &str) -> RoutingProfilesState {
    let mut out = state.clone();
    out.profiles.retain(|p| p.id != id);
    if out.global_profile_id == id {
        out.global_profile_id = String::new();
    }
    out
}

pub fn with_global_routing_profile(state: &RoutingProfilesState, id: &str) -> RoutingProfilesState {
    RoutingProfilesState { global_profile_id: id.to_string(), ..state.clone() }
}

/// A `yptun://routing/` link replaces the whole state (blank ids get fresh ones); otherwise a
/// Happ link/JSON is added as a new profile. `None` when the text is neither.
pub fn import_routing_profile_link(state: &RoutingProfilesState, link: &str) -> Option<RoutingProfilesState> {
    if let Some(mut imported) = RoutingProfilesState::from_routing_link(link) {
        for p in imported.profiles.iter_mut() {
            if p.id.trim().is_empty() {
                p.id = new_routing_profile_id();
            }
        }
        return Some(imported);
    }
    let parsed = parse_routing_profile(link)?;
    Some(save_routing_profile(state, RoutingProfile { id: String::new(), ..parsed }).0)
}

pub fn with_geo_sources(state: &RoutingProfilesState, geoip_url: &str, geosite_url: &str) -> RoutingProfilesState {
    RoutingProfilesState { geoip_url: geoip_url.trim().to_string(), geosite_url: geosite_url.trim().to_string(), ..state.clone() }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GeoUpdateStatus {
    Running,
    Success { updated_at: i64, bytes: i64 },
    Failed(String),
}

/// The result of a forced geo download: on success the timestamp is stored.
pub fn finish_geo_update(state: &RoutingProfilesState, ok: bool, now_ms: i64, bytes: i64) -> (RoutingProfilesState, GeoUpdateStatus) {
    if ok {
        (RoutingProfilesState { geo_last_updated: now_ms, ..state.clone() }, GeoUpdateStatus::Success { updated_at: now_ms, bytes })
    } else {
        (state.clone(), GeoUpdateStatus::Failed(GEO_DOWNLOAD_FAILED.into()))
    }
}

/// The controller: holds every settings model and persists each change.
pub struct DesktopSettings {
    store: VpnSettings,
    routing: RoutingRules,
    routing_profiles: RoutingProfilesState,
    traffic: TrafficSettings,
    app_behavior: AppBehaviorSettings,
    split_tunnel: SplitTunnelState,
    geo_update_status: Option<GeoUpdateStatus>,
    system_language: AppLanguage,
    language: AppLanguage,
    connection_mode: &'static str,
    ui: DesktopUiSettings,
}

impl DesktopSettings {
    pub fn open(app_data_dir: &Path, locale_language: &str, portable: bool, elevated: bool) -> Self {
        let store = VpnSettings::new(app_data_dir);
        let ui: DesktopUiSettings = store.load(UI_FILE);
        let (mode, save) = initial_connection_mode(&ui.connection_mode, portable, elevated);
        let mut s = Self {
            routing: store.load_routing(),
            routing_profiles: store.load_routing_profiles(),
            traffic: store.load_traffic(),
            app_behavior: store.load_app_behavior(),
            split_tunnel: store.load_split_tunnel(),
            geo_update_status: None,
            system_language: system_language(locale_language),
            language: AppLanguage::from_id(Some(&ui.language)),
            connection_mode: mode,
            ui,
            store,
        };
        if save {
            s.save_ui(|u| u.connection_mode = CONNECTION_MODE_PROXY.into());
        }
        s
    }

    pub fn routing(&self) -> &RoutingRules { &self.routing }
    pub fn routing_profiles(&self) -> &RoutingProfilesState { &self.routing_profiles }
    pub fn traffic(&self) -> &TrafficSettings { &self.traffic }
    pub fn app_behavior(&self) -> &AppBehaviorSettings { &self.app_behavior }
    pub fn split_tunnel(&self) -> &SplitTunnelState { &self.split_tunnel }
    pub fn geo_update_status(&self) -> Option<&GeoUpdateStatus> { self.geo_update_status.as_ref() }
    pub fn language(&self) -> AppLanguage { self.language }
    pub fn system_language(&self) -> AppLanguage { self.system_language }
    pub fn effective_language(&self) -> AppLanguage { self.language.effective(self.system_language) }
    pub fn connection_mode(&self) -> &'static str { self.connection_mode }
    /// The last UI preferences written (theme colors, dynamic/light theme).
    pub fn ui(&self) -> &DesktopUiSettings { &self.ui }

    fn save_ui(&mut self, f: impl FnOnce(&mut DesktopUiSettings)) {
        let mut ui: DesktopUiSettings = self.store.load(UI_FILE);
        f(&mut ui);
        let _ = self.store.save(UI_FILE, &ui);
        self.ui = ui;
    }

    fn set_routing_profiles_state(&mut self, value: RoutingProfilesState) {
        let _ = self.store.save_routing_profiles(&value);
        self.routing_profiles = value;
    }

    fn persist_split_tunnel(&mut self, value: SplitTunnelState) {
        let _ = self.store.save_split_tunnel(&value);
        self.split_tunnel = value;
    }

    pub fn select_split_tunnel_mode(&mut self, mode: &str) {
        let v = with_split_tunnel_mode(&self.split_tunnel, mode);
        self.persist_split_tunnel(v);
    }

    pub fn toggle_split_tunnel_app(&mut self, list: SplitTunnelList, process: &str) {
        let v = toggle_split_tunnel_app(&self.split_tunnel, list, process);
        self.persist_split_tunnel(v);
    }

    pub fn set_split_tunnel_apps(&mut self, list: SplitTunnelList, processes: BTreeSet<String>) {
        let v = set_split_tunnel_apps(&self.split_tunnel, list, processes);
        self.persist_split_tunnel(v);
    }

    /// [`commands`] = the command paths of the running processes (platform-side).
    pub fn installed_apps(&self, commands: &[String]) -> Vec<String> {
        installed_apps(commands, &self.split_tunnel)
    }

    pub fn set_dynamic_theme(&mut self, enabled: bool) {
        self.save_ui(|u| u.dynamic_theme = enabled);
    }

    pub fn set_light_theme(&mut self, enabled: bool) {
        self.save_ui(|u| u.light_theme = enabled);
    }

    pub fn set_routing(&mut self, value: RoutingRules) {
        let _ = self.store.save_routing(&value);
        self.routing = value;
    }

    pub fn save_routing_profile(&mut self, profile: RoutingProfile) -> String {
        let (state, id) = save_routing_profile(&self.routing_profiles, profile);
        self.set_routing_profiles_state(state);
        id
    }

    pub fn delete_routing_profile(&mut self, id: &str) {
        let v = delete_routing_profile(&self.routing_profiles, id);
        self.set_routing_profiles_state(v);
    }

    pub fn set_global_routing_profile(&mut self, id: &str) {
        let v = with_global_routing_profile(&self.routing_profiles, id);
        self.set_routing_profiles_state(v);
    }

    pub fn import_routing_profile_link(&mut self, link: &str) -> bool {
        match import_routing_profile_link(&self.routing_profiles, link) {
            Some(v) => {
                self.set_routing_profiles_state(v);
                true
            }
            None => false,
        }
    }

    pub fn set_geo_sources(&mut self, geoip_url: &str, geosite_url: &str) {
        let v = with_geo_sources(&self.routing_profiles, geoip_url, geosite_url);
        self.set_routing_profiles_state(v);
    }

    /// Starts a forced geo update: `None` while one is running, else the state to download with.
    pub fn begin_geo_update(&mut self) -> Option<RoutingProfilesState> {
        if self.geo_update_status == Some(GeoUpdateStatus::Running) {
            return None;
        }
        self.geo_update_status = Some(GeoUpdateStatus::Running);
        Some(self.routing_profiles.clone())
    }

    pub fn finish_geo_update(&mut self, ok: bool, now_ms: i64, bytes: i64) -> GeoUpdateStatus {
        let (state, status) = finish_geo_update(&self.routing_profiles, ok, now_ms, bytes);
        if ok {
            self.set_routing_profiles_state(state);
        }
        self.geo_update_status = Some(status.clone());
        status
    }

    pub fn set_traffic_settings(&mut self, value: &TrafficSettings) {
        let normalized = value.normalized();
        let _ = self.store.save_traffic(&normalized);
        self.traffic = normalized;
    }

    pub fn set_app_behavior(&mut self, value: AppBehaviorSettings) {
        let _ = self.store.save_app_behavior(&value);
        self.app_behavior = value;
    }

    pub fn set_language(&mut self, value: AppLanguage) {
        self.language = value;
        self.save_ui(|u| u.language = value.id().into());
    }

    pub fn select_connection_mode(&mut self, mode: &str) {
        let mode = connection_mode_from_value(mode);
        self.connection_mode = mode;
        self.save_ui(|u| u.connection_mode = mode.into());
    }

    pub fn set_accent_color(&mut self, argb: Option<i32>) {
        self.save_ui(|u| u.accent_argb = argb);
    }

    pub fn set_text_color(&mut self, argb: Option<i32>) {
        self.save_ui(|u| u.text_argb = argb);
    }

    pub fn set_background_color(&mut self, argb: Option<i32>) {
        self.save_ui(|u| u.background_argb = argb);
    }
}
