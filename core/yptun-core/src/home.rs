//! Home-screen logic that is not UI (port of the pure parts of `HomeScreenViewModel`): what a
//! paste imports, why an import failed, the VPN button's state, the VK link prompt, subscription
//! expiry and announcements handed to the platform, the free-servers pick and save, and the logs
//! export.

use std::collections::{BTreeSet, HashSet};

use crate::location::{
    LocationBundleV4, LocationConfig, LocationEntry, LocationMetadata, SubscriptionMetadata,
};
use crate::location_list::LocationItem;
use crate::model::{EngineType, ProxyCore, ProxyProfile};
use crate::panel::{parse_inbound, YPTUN_INBOUND_PREFIX};
use crate::pings::eta_seconds;
use crate::routing::looks_like_routing_profile;
use crate::share_link;
use crate::subscription::to_link_text;
use crate::transports::{WarpMode, WarpProtocol};
use crate::util::{is_blank, kotlin_lines};

pub use crate::repository::FREE_SERVERS_URL;

// ---------------- import ----------------

pub const NO_CONFIG_TEXT: &str = "No config text found";
pub const NO_VALID_CONFIG: &str = "No valid YPtun config found";
pub const INVALID_ROUTING_PROFILE: &str = "Invalid routing profile";
pub const DAMAGED_INBOUND_LINK: &str =
    "This YPtun link is damaged — characters were lost in transit. Scan the QR instead, or send the link as a file.";
pub const UNSUPPORTED_OPENFLUX_LINK: &str = "This OpenFlux link can't be imported: only Yandex / Mail.ru / cups.online / Avito / direct transports are supported (a session needs a secret), or the link is damaged.";

/// What a paste (or an opened file) imports.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImportPlan {
    /// Nothing but whitespace: [`NO_CONFIG_TEXT`].
    Empty,
    /// Several subscription URLs (and maybe share links, grouped as the last item): imported one
    /// item at a time, see [`import_many_outcome`].
    Batch(Vec<String>),
    /// A `happ://routing/…` link or Happ routing JSON: a routing profile (trimmed), not a location.
    RoutingProfile(String),
    /// Everything else goes to the importer as it is.
    Single(String),
}

/// Sorts a paste out. It is a batch only when that is unambiguous: two http(s) URLs or more, or
/// URLs together with share links (a multi-line JSON, base64 blob or link list is one import).
pub fn plan_import(raw_text: &str) -> ImportPlan {
    if is_blank(raw_text) {
        return ImportPlan::Empty;
    }
    let lines: Vec<&str> = kotlin_lines(raw_text)
        .into_iter()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect();
    let is_http =
        |l: &str| starts_with_ignore_case(l, "http://") || starts_with_ignore_case(l, "https://");
    let http_urls: Vec<&str> = lines.iter().copied().filter(|l| is_http(l)).collect();
    let proto_lines: Vec<&str> = lines
        .iter()
        .copied()
        .filter(|l| !is_http(l) && l.contains("://"))
        .collect();
    if http_urls.len() >= 2 || (!http_urls.is_empty() && !proto_lines.is_empty()) {
        let mut items: Vec<String> = http_urls.iter().map(|u| u.to_string()).collect();
        if !proto_lines.is_empty() {
            items.push(proto_lines.join("\n"));
        }
        return ImportPlan::Batch(items);
    }
    if looks_like_routing_profile(raw_text) {
        return ImportPlan::RoutingProfile(raw_text.trim().to_string());
    }
    ImportPlan::Single(raw_text.to_string())
}

fn starts_with_ignore_case(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len()
        && s.is_char_boundary(prefix.len())
        && s[..prefix.len()].eq_ignore_ascii_case(prefix)
}

/// Why `raw_text` imported nothing. A `yptun://inbound` link that does not decode arrived damaged
/// (chats eat runs of `_` in its base64url), which the generic message would hide.
pub fn import_failure_reason(raw_text: &str) -> &'static str {
    let text = raw_text.trim();
    if starts_with_ignore_case(text, "openflux://") {
        return UNSUPPORTED_OPENFLUX_LINK;
    }
    let single_inbound = text.starts_with(YPTUN_INBOUND_PREFIX)
        && text
            .get(1..)
            .is_none_or(|rest| !rest.contains(YPTUN_INBOUND_PREFIX));
    if single_inbound && parse_inbound(text).is_none() {
        DAMAGED_INBOUND_LINK
    } else {
        NO_VALID_CONFIG
    }
}

/// The result of a batch import: `Err` when nothing imported, else `Ok` with a note on failures.
pub fn import_many_outcome(added: usize, failed: usize) -> Result<Option<String>, String> {
    if added == 0 {
        return Err(format!("No valid subscriptions found ({failed} failed)"));
    }
    Ok((failed > 0).then(|| format!("Imported {added}, {failed} failed")))
}

// ---------------- the VPN button ----------------

/// The tunnel's status as the platform reports it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VpnStatus {
    Connected,
    Connecting,
    Reconnecting,
    Stopping,
    Disconnected,
    Error(String),
}

/// What Home shows for a status: connected, busy, and a connect error to show once (`None` keeps
/// the last one).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VpnFlags {
    pub connected: bool,
    pub loading: bool,
    pub connect_error: Option<String>,
}

impl VpnStatus {
    pub fn flags(&self) -> VpnFlags {
        let (connected, loading) = match self {
            VpnStatus::Connected => (true, false),
            VpnStatus::Connecting => (false, true),
            VpnStatus::Reconnecting => (true, true),
            VpnStatus::Stopping | VpnStatus::Disconnected | VpnStatus::Error(_) => (false, false),
        };
        let connect_error = match self {
            VpnStatus::Error(message) => Some(message.clone()),
            _ => None,
        };
        VpnFlags {
            connected,
            loading,
            connect_error,
        }
    }

    /// A settings change restarts a running tunnel; a stopped (or stopping) one stays down.
    pub fn restarts_on_change(&self) -> bool {
        matches!(
            self,
            VpnStatus::Connected | VpnStatus::Connecting | VpnStatus::Reconnecting
        )
    }
}

pub const ADD_LOCATION_FIRST: &str = "Add a location first";
pub const COMPLETE_ACTIVE_LOCATION_FIRST: &str = "Complete active location first";
pub const ADD_VALID_LOCATION_FIRST: &str = "Add a valid location first";

/// What the VPN button does when tapped.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToggleAction {
    /// A connect is under way: stop it and show the button off at once.
    Cancel,
    /// Connected: stop (the status updates the button).
    Stop,
    Start,
    /// No complete active location: the button stays off with this reason.
    Blocked(&'static str),
}

/// `ToggleVpn`: busy → cancel, connected → stop; else start the active location if it can connect.
pub fn toggle_action(
    status: &VpnStatus,
    loading: bool,
    connected: bool,
    active: Option<&LocationEntry>,
) -> ToggleAction {
    if loading || matches!(status, VpnStatus::Connecting | VpnStatus::Reconnecting) {
        return ToggleAction::Cancel;
    }
    if connected || *status == VpnStatus::Connected {
        return ToggleAction::Stop;
    }
    match active {
        Some(entry) if entry.location().is_complete() => ToggleAction::Start,
        _ => ToggleAction::Blocked(ADD_VALID_LOCATION_FIRST),
    }
}

/// The active location as Home shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActiveLocation {
    pub selected: Option<LocationItem>,
    pub config: LocationConfig,
    pub can_start_vpn: bool,
    pub start_blocked_reason: Option<&'static str>,
}

pub fn active_location(active: Option<&LocationEntry>) -> ActiveLocation {
    let Some(entry) = active else {
        return ActiveLocation {
            selected: None,
            config: LocationConfig::default(),
            can_start_vpn: false,
            start_blocked_reason: Some(ADD_LOCATION_FIRST),
        };
    };
    let item = LocationItem::from_entry(entry);
    let config = item.config.clone().unwrap_or_default();
    let complete = config.is_complete();
    ActiveLocation {
        selected: Some(item),
        config,
        can_start_vpn: complete,
        start_blocked_reason: (!complete).then_some(COMPLETE_ACTIVE_LOCATION_FIRST),
    }
}

// ---------------- VK-TURN link prompt ----------------

/// Asks for the per-client VK Calls link of a freshly imported VK-TURN location.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VkTurnLinkPrompt {
    pub storage_id: String,
    pub location_name: String,
}

/// After an import: the active location is VK-TURN without its VK call link yet.
pub fn vk_turn_link_prompt(active: Option<&LocationEntry>) -> Option<VkTurnLinkPrompt> {
    let entry = active?;
    let config = entry.location();
    let needs_link = config.engine == EngineType::VkTurn
        && config.vkturn.as_ref().is_none_or(|v| is_blank(&v.vk_link));
    needs_link.then(|| VkTurnLinkPrompt {
        storage_id: entry.storage_id.clone(),
        location_name: config.display_name(),
    })
}

/// `config` with the typed VK call link; `None` when it is not a VK-TURN location.
pub fn with_vk_turn_link(config: &LocationConfig, vk_link: &str) -> Option<LocationConfig> {
    let mut vkturn = config.vkturn.clone()?;
    vkturn.vk_link = vk_link.trim().to_string();
    Some(LocationConfig {
        vkturn: Some(vkturn),
        ..config.clone()
    })
}

// ---------------- subscriptions handed to the platform ----------------

/// How often Home re-checks the subscriptions, and the delay before the first check of a launch.
pub const SUBSCRIPTION_AUTO_REFRESH_POLL_MS: u64 = 60 * 60 * 1_000;
pub const SUBSCRIPTION_AUTO_REFRESH_INITIAL_DELAY_MS: u64 = 15_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpiringSubscriptionInfo {
    pub name: String,
    pub expires_at_epoch_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PanelAnnouncementInfo {
    pub name: String,
    pub announce: String,
}

/// The subscriptions of `entries` (by trimmed URL, in first-seen order), each with its metadata
/// and display name: the panel's title, else its first named server, else the URL.
fn subscriptions(entries: &[LocationEntry]) -> Vec<(String, Option<&SubscriptionMetadata>)> {
    let mut order: Vec<String> = Vec::new();
    let mut seen = HashSet::new();
    for entry in entries {
        if let Some(url) = entry.subscription_url.as_deref().filter(|u| !is_blank(u)) {
            let url = url.trim().to_string();
            if seen.insert(url.clone()) {
                order.push(url);
            }
        }
    }
    order
        .into_iter()
        .map(|url| {
            let group: Vec<&LocationEntry> = entries
                .iter()
                .filter(|e| {
                    e.subscription_url
                        .as_deref()
                        .filter(|u| !is_blank(u))
                        .is_some_and(|u| u.trim() == url)
                })
                .collect();
            let meta = group
                .iter()
                .find_map(|e| e.metadata.as_ref().and_then(|m| m.subscription.as_ref()));
            let name = meta
                .and_then(|m| m.name.clone())
                .filter(|n| !is_blank(n))
                .or_else(|| {
                    group
                        .iter()
                        .find(|e| !is_blank(&e.name))
                        .map(|e| e.name.clone())
                })
                .unwrap_or(url);
            (name, meta)
        })
        .collect()
}

/// One (name → expiry) per subscription, for the platform's "runs out soon" warning.
pub fn expiring_subscriptions(entries: &[LocationEntry]) -> Vec<ExpiringSubscriptionInfo> {
    subscriptions(entries)
        .into_iter()
        .filter_map(|(name, meta)| {
            meta?
                .expires_at_epoch_ms
                .map(|expires_at_epoch_ms| ExpiringSubscriptionInfo {
                    name,
                    expires_at_epoch_ms,
                })
        })
        .collect()
}

/// One (name → announce) per subscription with a panel announcement.
pub fn panel_announcements(entries: &[LocationEntry]) -> Vec<PanelAnnouncementInfo> {
    subscriptions(entries)
        .into_iter()
        .filter_map(|(name, meta)| {
            let announce = meta?.announce.clone().filter(|a| !is_blank(a))?;
            Some(PanelAnnouncementInfo { name, announce })
        })
        .collect()
}

// ---------------- free servers ----------------

/// How often the free servers auto-update when the group's switch is on.
pub const FREE_AUTO_UPDATE_HOURS: i32 = 6;
/// The hidden GitHub Page with one file of vless links, used instead of [`FREE_SERVERS_SOURCES`]
/// when the «Пинг» setting is on ([`FREE_SERVERS_URL`] stays the group id either way).
pub const FREE_SERVERS_PAGES_URL: &str = "https://breead1337.github.io/ws-cache-9e41/v.txt";
/// The group name of the saved free servers.
pub const FREE_SERVERS_GROUP_NAME: &str = "Бесплатные серверы";
/// Protocols taken from the free lists: the ones the importer parses and xray checks through the
/// proxy (Hysteria2 would always look dead to that check).
pub const FREE_SERVER_SCHEMES: [&str; 4] = ["vless://", "vmess://", "trojan://", "ss://"];
/// Completed checks before the search shows an estimated time to the end.
pub const FREE_ETA_MIN_SAMPLES: usize = 5;

/// A free-server list: `max_count` unique servers taken from it (0 = all, unshuffled), `region` =
/// the app language it goes first for ("" = all).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FreeSource {
    pub url: &'static str,
    pub max_count: usize,
    pub region: &'static str,
}

const fn source(url: &'static str, max_count: usize, region: &'static str) -> FreeSource {
    FreeSource {
        url,
        max_count,
        region,
    }
}

/// The free-server lists in priority order (see the Kotlin app for what each one is).
pub const FREE_SERVERS_SOURCES: [FreeSource; 12] = [
    source(FREE_SERVERS_URL, 0, "ru"),
    source("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt", 0, "ru"),
    source("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt", 0, "ru"),
    source("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-checked.txt", 0, "ru"),
    source("https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/mix.txt", 250, ""),
    source("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/vless_configs.txt", 250, ""),
    source("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt", 250, "fa"),
    source("https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt", 250, "fa"),
    source("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt", 250, "fa"),
    source("https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt", 0, "zh"),
    source("https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt", 0, "zh"),
    source("https://raw.githubusercontent.com/chengaopan/AutoMergePublicNodes/master/list.txt", 0, "zh"),
];

/// [`FREE_SERVERS_SOURCES`] with the lists of `language` first, then the common ones, then the
/// others; the order inside each group stays.
pub fn free_sources_for(language: &str) -> Vec<FreeSource> {
    let mut sources = FREE_SERVERS_SOURCES.to_vec();
    sources.sort_by_key(|s| {
        if s.region == language {
            0
        } else if s.region.is_empty() {
            1
        } else {
            2
        }
    });
    sources
}

/// The servers to check from the fetched lists (`(body, max_count)` per source, in order): every
/// protocol of [`FREE_SERVER_SCHEMES`], each server once, a source's quota spent only on servers no
/// earlier source gave, big lists sampled at random. `limit` caps the whole result (0 = no cap).
pub fn pick_free_servers(
    source_texts: &[(String, usize)],
    limit: usize,
) -> Vec<(String, ProxyProfile)> {
    pick_free_servers_with(source_texts, limit, &mut shuffle)
}

/// [`pick_free_servers`] with the shuffle of a sampled list supplied.
pub fn pick_free_servers_with(
    source_texts: &[(String, usize)],
    limit: usize,
    shuffle: &mut dyn FnMut(&mut [String]),
) -> Vec<(String, ProxyProfile)> {
    let mut seen = HashSet::new();
    let mut picked = Vec::new();
    for (body, max_count) in source_texts {
        if limit > 0 && picked.len() >= limit {
            break;
        }
        let text = to_link_text(body);
        let mut lines: Vec<String> = kotlin_lines(&text)
            .into_iter()
            .map(str::trim)
            .filter(|l| {
                FREE_SERVER_SCHEMES
                    .iter()
                    .any(|s| starts_with_ignore_case(l, s))
            })
            .map(str::to_string)
            .collect();
        if *max_count > 0 {
            shuffle(&mut lines);
        }
        let mut taken = 0;
        for line in lines {
            if *max_count > 0 && taken >= *max_count {
                break;
            }
            if limit > 0 && picked.len() >= limit {
                break;
            }
            let Some(profile) = share_link::parse(&line).filter(ProxyProfile::is_complete) else {
                continue;
            };
            if !seen.insert(dedup_key(&profile)) {
                continue;
            }
            picked.push((line, profile));
            taken += 1;
        }
    }
    picked
}

fn dedup_key(profile: &ProxyProfile) -> String {
    serde_json::to_string(&profile.dedup_normalized()).unwrap_or_default()
}

fn shuffle(lines: &mut [String]) {
    for i in (1..lines.len()).rev() {
        let mut bytes = [0u8; 8];
        let r = if getrandom::getrandom(&mut bytes).is_ok() {
            u64::from_le_bytes(bytes)
        } else {
            i as u64 * 2_654_435_761
        };
        lines.swap(i, (r % (i as u64 + 1)) as usize);
    }
}

/// A free server that answered the check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FreeServerItem {
    pub id: String,
    pub line: String,
    pub profile: ProxyProfile,
    pub display_name: String,
    pub ping_ms: i64,
}

/// The location a free server is checked (and saved) as.
pub fn free_server_config(profile: &ProxyProfile) -> LocationConfig {
    LocationConfig {
        name: profile.display_name(),
        engine: EngineType::Standard,
        proxy: Some(profile.clone()),
        ..LocationConfig::default()
    }
    .normalized()
}

/// The list item of server `index` of the search when its check answered (`ping > 0`).
pub fn free_server_item(
    index: usize,
    line: &str,
    profile: &ProxyProfile,
    ping: Option<i64>,
) -> Option<FreeServerItem> {
    let ping_ms = ping.filter(|&ms| ms > 0)?;
    Some(FreeServerItem {
        id: format!("free_{}_{}_{}", profile.server, profile.server_port, index),
        line: line.to_string(),
        profile: profile.clone(),
        display_name: profile.display_name(),
        ping_ms,
    })
}

/// Seconds left in a free-servers search (`freeEta`): `None` while paused or with few samples.
pub fn free_eta(
    paused: bool,
    active_ms: i64,
    run_checks: usize,
    total: usize,
    checked: usize,
) -> Option<i32> {
    if paused || run_checks < FREE_ETA_MIN_SAMPLES {
        return None;
    }
    eta_seconds(active_ms, run_checks, total.saturating_sub(checked))
}

fn is_free(entry: &LocationEntry) -> bool {
    entry
        .subscription_url
        .as_deref()
        .is_some_and(|u| u.trim() == FREE_SERVERS_URL)
}

fn is_pinned(entry: &LocationEntry) -> bool {
    entry.metadata.as_ref().is_some_and(|m| m.pinned)
}

/// The subscription metadata the free-servers group had (its switch and interval carry over).
pub fn previous_free_subscription(bundle: &LocationBundleV4) -> Option<SubscriptionMetadata> {
    bundle
        .locations
        .iter()
        .filter(|e| is_free(e))
        .find_map(|e| e.metadata.as_ref()?.subscription.clone())
}

/// The stored entries of the picked free servers: xray core (the one that checked them), WARP at
/// the exit for `warp_ids` — AmneziaWG on Russian servers, where plain WireGuard is cut.
pub fn free_server_entries(
    selected: &[FreeServerItem],
    warp_ids: &BTreeSet<String>,
    previous: Option<&SubscriptionMetadata>,
    now_ms: i64,
) -> Vec<LocationEntry> {
    selected
        .iter()
        .enumerate()
        .map(|(index, item)| {
            let profile = &item.profile;
            let location = LocationConfig {
                name: item.display_name.clone(),
                engine: EngineType::Standard,
                proxy: Some(profile.clone()),
                core: ProxyCore::Xray,
                warp_enabled: warp_ids.contains(&item.id),
                warp_mode: WarpMode::Server,
                warp_protocol: if item.display_name.contains("🇷🇺") {
                    WarpProtocol::Awg
                } else {
                    WarpProtocol::Wg
                },
                ..LocationConfig::default()
            }
            .normalized();
            let base: String = format!("{}_{}", profile.server, profile.server_port)
                .to_lowercase()
                .chars()
                .map(|c| match c {
                    c if u32::from(c) > 0xFFFF => "__".to_string(),
                    c if c.is_alphanumeric() => c.to_string(),
                    _ => "_".to_string(),
                })
                .collect();
            let storage_id = format!("free_{base}_{now_ms}_{index}");
            let metadata = LocationMetadata {
                subscription: Some(SubscriptionMetadata {
                    name: Some(FREE_SERVERS_GROUP_NAME.to_string()),
                    update_interval_hours: Some(
                        previous.and_then(|p| p.update_interval_hours).unwrap_or(0),
                    ),
                    auto_update_enabled: previous.is_some_and(|p| p.auto_update_enabled),
                    last_refresh_at_epoch_ms: Some(now_ms),
                    last_attempt_at_epoch_ms: Some(now_ms),
                    ..SubscriptionMetadata::default()
                }),
                ..LocationMetadata::default()
            };
            LocationEntry::from(
                &storage_id,
                &location,
                Some(FREE_SERVERS_URL.to_string()),
                Some(metadata),
            )
        })
        .collect()
}

/// Replaces the free servers of `bundle` with `new_entries`: only the old free servers go — the
/// pinned ones stay, and a new server equal to a pinned one is not added twice. The active location
/// moves to the first new (else pinned) server when its own row was dropped. Returns the bundle and
/// how many servers were added.
pub fn merge_free_servers(
    bundle: &LocationBundleV4,
    new_entries: Vec<LocationEntry>,
) -> (LocationBundleV4, usize) {
    let old_free: Vec<&LocationEntry> = bundle.locations.iter().filter(|e| is_free(e)).collect();
    let kept_pinned: Vec<&LocationEntry> =
        old_free.iter().copied().filter(|e| is_pinned(e)).collect();
    let pinned_profiles: HashSet<String> = kept_pinned
        .iter()
        .filter_map(|e| e.proxy.as_ref())
        .map(dedup_key)
        .collect();
    let fresh: Vec<LocationEntry> = new_entries
        .into_iter()
        .filter(|e| {
            !e.proxy
                .as_ref()
                .is_some_and(|p| pinned_profiles.contains(&dedup_key(p)))
        })
        .collect();
    let dropped: HashSet<&str> = old_free
        .iter()
        .filter(|e| !kept_pinned.contains(e))
        .map(|e| e.storage_id.as_str())
        .collect();
    let active = match bundle.active_location_id.as_deref() {
        Some(id) if dropped.contains(id) => fresh
            .first()
            .or(kept_pinned.first().copied())
            .map(|e| e.storage_id.clone())
            .or_else(|| bundle.active_location_id.clone()),
        _ => bundle.active_location_id.clone(),
    };
    let added = fresh.len();
    let mut locations: Vec<LocationEntry> = bundle
        .locations
        .iter()
        .filter(|e| !is_free(e))
        .cloned()
        .collect();
    locations.extend(kept_pinned.into_iter().cloned());
    locations.extend(fresh);
    (
        LocationBundleV4 {
            locations,
            active_location_id: active,
            ..bundle.clone()
        },
        added,
    )
}

/// Free servers saved before they were pinned to xray (with the core on «Авто») get xray, the core
/// that checked them; a core picked by hand stays. `None` when nothing changes.
pub fn migrate_free_servers_to_xray(bundle: &LocationBundleV4) -> Option<LocationBundleV4> {
    let fixed: Vec<LocationEntry> = bundle
        .locations
        .iter()
        .map(|e| {
            let unset = e.core.is_none_or(|c| c == ProxyCore::Auto);
            if unset && is_free(e) {
                LocationEntry {
                    core: Some(ProxyCore::Xray),
                    ..e.clone()
                }
            } else {
                e.clone()
            }
        })
        .collect();
    (fixed != bundle.locations).then(|| LocationBundleV4 {
        locations: fixed,
        ..bundle.clone()
    })
}

/// The auto-update must not run now: a search runs, the picker is open, a stopped search waits to
/// be continued, or the tunnel runs over an unpinned free server the save would replace.
pub fn free_auto_update_blocked(
    search_running: bool,
    picker_open: bool,
    resumable: usize,
    vpn_connected: bool,
    selected: Option<&LocationItem>,
) -> bool {
    if search_running || picker_open || resumable > 0 {
        return true;
    }
    vpn_connected
        && selected.is_some_and(|s| {
            s.subscription_url
                .as_deref()
                .is_some_and(|u| u.trim() == FREE_SERVERS_URL)
                && !s.metadata.as_ref().is_some_and(|m| m.pinned)
        })
}

/// The free servers' auto-update is due: the group exists with its switch on and its interval
/// (default [`FREE_AUTO_UPDATE_HOURS`]) has passed since the last refresh, attempt or
/// `last_auto_attempt_ms`.
pub fn free_auto_update_due(
    entries: &[LocationEntry],
    last_auto_attempt_ms: i64,
    now_ms: i64,
) -> bool {
    let subs: Vec<&SubscriptionMetadata> = entries
        .iter()
        .filter(|e| is_free(e))
        .filter_map(|e| e.metadata.as_ref()?.subscription.as_ref())
        .collect();
    if subs.is_empty() || subs.iter().any(|s| !s.auto_update_enabled) {
        return false;
    }
    let hours = subs
        .iter()
        .find_map(|s| s.update_interval_hours.filter(|&h| h > 0))
        .unwrap_or(FREE_AUTO_UPDATE_HOURS);
    let last_sub = subs
        .iter()
        .map(|s| {
            s.last_refresh_at_epoch_ms
                .unwrap_or(0)
                .max(s.last_attempt_at_epoch_ms.unwrap_or(0))
        })
        .max()
        .unwrap_or(0);
    let last = last_auto_attempt_ms.max(last_sub);
    now_ms - last >= i64::from(hours) * 3_600_000
}

// ---------------- logs ----------------

pub const LOGS_FILE_NAME: &str = "olcbox-logs.txt";

/// The text of a logs export: a header and the numbered lines.
pub fn build_logs_export(logs: &[String]) -> String {
    let mut out = format!("YPtun application logs\nEntries: {}\n\n", logs.len());
    for (index, line) in logs.iter().enumerate() {
        out.push_str(&format!("{}. {}\n", index + 1, line));
    }
    out
}
