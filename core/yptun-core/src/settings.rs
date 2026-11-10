//! App behaviour settings and Home-list folders (port of `AppBehaviorSettings`/`CustomGroup`).
//! JSON keys are the Kotlin property names (camelCase), so the stored settings load unchanged.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::model::ProxyCore;

pub const MEMBER_SUB_PREFIX: &str = "sub:";
pub const MEMBER_LOC_PREFIX: &str = "loc:";

/// A user folder on the Home list: whole subscriptions (`sub:<key>`) and custom locations (`loc:<id>`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct CustomGroup {
    pub id: String,
    pub name: String,
    pub members: Vec<String>,
    pub pinned: bool,
    pub collapsed: bool,
}

impl CustomGroup {
    pub fn sub_member(subscription_key: &str) -> String {
        format!("{MEMBER_SUB_PREFIX}{subscription_key}")
    }
    pub fn loc_member(storage_id: &str) -> String {
        format!("{MEMBER_LOC_PREFIX}{storage_id}")
    }
}

pub const SUB_UA_BOTH: &str = "both";
pub const SUB_UA_HAPP: &str = "happ";
pub const SUB_UA_YPTUN_ONLY: &str = "yptun_only";
pub const SUB_UA_V2RAYTUN: &str = "v2raytun";
/// The old two-way selector's "YPtun": behaved as [`SUB_UA_BOTH`].
pub const SUB_UA_YPTUN: &str = "yptun";
pub const HAPP_USER_AGENT: &str = "Happ/1.0";
pub const V2RAYTUN_UA_PREFIX: &str = "v2raytun/";
pub const SUBSCRIPTION_UA_MODES: [&str; 4] = [SUB_UA_BOTH, SUB_UA_HAPP, SUB_UA_YPTUN_ONLY, SUB_UA_V2RAYTUN];

pub const PING_AUTO: &str = "auto";
pub const PING_TCP: &str = "tcp";
pub const PING_ICMP: &str = "icmp";
pub const PING_PROXY_GET: &str = "proxy_get";
pub const PING_PROXY_HEAD: &str = "proxy_head";
pub const PING_MODES: [&str; 5] = [PING_AUTO, PING_TCP, PING_ICMP, PING_PROXY_GET, PING_PROXY_HEAD];
pub const PING_RESULT_TIME: &str = "time";
pub const PING_RESULT_ICON: &str = "icon";
pub const PING_RESULT_BOTH: &str = "both";
pub const PING_RESULT_MODES: [&str; 3] = [PING_RESULT_TIME, PING_RESULT_ICON, PING_RESULT_BOTH];
/// A tiny TCP 204 endpoint (google.com forces QUIC, which the tunnel blocks).
pub const DEFAULT_PING_URL: &str = "https://www.gstatic.com/generate_204";
pub const LEGACY_PING_URL: &str = "https://google.com";
pub const SUBSCRIPTION_EXPIRY_NOTIFY_DAYS: i32 = 3;
pub const MIN_PING_TIMEOUT_SEC: i32 = 2;
pub const MAX_PING_TIMEOUT_SEC: i32 = 15;
pub const DEFAULT_PING_TIMEOUT_SEC: i32 = 7;
pub const MIN_PING_PRECISION: i32 = 1;
pub const MAX_PING_PRECISION: i32 = 3;
pub const DEFAULT_PING_PRECISION: i32 = 1;
pub const FREE_SERVERS_UNLIMITED: i32 = 0;
pub const DEFAULT_FREE_SERVERS_LIMIT: i32 = FREE_SERVERS_UNLIMITED;
pub const FREE_SERVERS_LIMITS: [i32; 5] = [500, 1000, 2500, 5000, FREE_SERVERS_UNLIMITED];
pub const DEFAULT_PING_PARALLELISM: i32 = 5;
pub const MIN_PING_PARALLELISM: i32 = 1;
pub const MAX_PING_PARALLELISM: i32 = 30;
pub const ENERGY_SAVER_WATCHDOG_INTERVAL_MS: i64 = 60_000;

/// "Настройки приложения".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AppBehaviorSettings {
    pub auto_connect_on_launch: bool,
    pub show_auto_button: bool,
    pub show_group_auto_pick: bool,
    pub confirm_before_delete: bool,
    pub show_speed_in_notification: bool,
    pub show_speed_on_home: bool,
    pub show_rooms_in_notification: bool,
    pub ping_own_line_in_notification: bool,
    pub notif_ping_auto: bool,
    pub notif_ping_interval_min: i32,
    pub experimental_unlocked: bool,
    pub legacy_ui: bool,
    pub hide_ssh_install: bool,
    pub hide_session_button: bool,
    pub allow_vps_auto_install: bool,
    pub telemost_cookies: String,
    pub telemost_cookies_enabled: bool,
    pub hide_tun_interface: bool,
    pub share_vpn_hotspot: bool,
    pub collapsed_subscription_groups: BTreeSet<String>,
    /// Pin order.
    pub pinned_subscription_groups: Vec<String>,
    pub ping_sorted_subscription_groups: BTreeSet<String>,
    pub ping_sort_descending_subscription_groups: BTreeSet<String>,
    pub pinned_custom_locations: Vec<String>,
    pub custom_locations_ping_sorted: bool,
    pub custom_locations_ping_sort_descending: bool,
    pub ping_mode: String,
    pub ping_url: String,
    pub ping_result_display: String,
    pub ping_parallelism: i32,
    pub save_ping_results: bool,
    /// storage id → ms; `null` = probed and unreachable.
    pub last_ping_results: BTreeMap<String, Option<i32>>,
    pub show_subscription_expiry: bool,
    pub notify_subscription_expiry: bool,
    pub notify_panel_announcements: bool,
    pub show_subscription_alive_count: bool,
    pub show_subscription_description: bool,
    /// 3, 5 or 0 = unlimited.
    pub subscription_description_lines: i32,
    pub show_subscription_icons: bool,
    pub hide_endpoint_when_description: bool,
    pub custom_groups: Vec<CustomGroup>,
    pub two_column_layout: bool,
    pub subscription_user_agent: String,
    /// Used only when a location's own core is Auto.
    pub global_proxy_core: ProxyCore,
    pub energy_saver: bool,
    pub provider_report_enabled: bool,
    pub send_device_info_to_subscriptions: bool,
    /// Unused (build 421); kept so its settings load.
    pub custom_hwid: String,
    pub telegram_proxy_enabled: bool,
    /// Vestigial; kept so old settings load.
    pub hide_telegram_proxy_notification: bool,
    pub set_system_proxy: bool,
    pub free_servers_limit: i32,
    pub ping_timeout_sec: i32,
    pub ping_precision: i32,
    pub show_ping_controls: bool,
    pub free_servers_from_pages: bool,
    pub allow_config_editing: bool,
    pub share_universal_link: bool,
}

impl Default for AppBehaviorSettings {
    fn default() -> Self {
        Self {
            auto_connect_on_launch: false,
            show_auto_button: true,
            show_group_auto_pick: true,
            confirm_before_delete: true,
            show_speed_in_notification: false,
            show_speed_on_home: false,
            show_rooms_in_notification: false,
            ping_own_line_in_notification: false,
            notif_ping_auto: false,
            notif_ping_interval_min: 5,
            experimental_unlocked: false,
            legacy_ui: false,
            hide_ssh_install: false,
            hide_session_button: false,
            allow_vps_auto_install: false,
            telemost_cookies: String::new(),
            telemost_cookies_enabled: false,
            hide_tun_interface: false,
            share_vpn_hotspot: false,
            collapsed_subscription_groups: BTreeSet::new(),
            pinned_subscription_groups: vec![],
            ping_sorted_subscription_groups: BTreeSet::new(),
            ping_sort_descending_subscription_groups: BTreeSet::new(),
            pinned_custom_locations: vec![],
            custom_locations_ping_sorted: false,
            custom_locations_ping_sort_descending: false,
            ping_mode: PING_PROXY_HEAD.into(),
            ping_url: DEFAULT_PING_URL.into(),
            ping_result_display: PING_RESULT_ICON.into(),
            ping_parallelism: DEFAULT_PING_PARALLELISM,
            save_ping_results: false,
            last_ping_results: BTreeMap::new(),
            show_subscription_expiry: false,
            notify_subscription_expiry: false,
            notify_panel_announcements: false,
            show_subscription_alive_count: false,
            show_subscription_description: false,
            subscription_description_lines: 0,
            show_subscription_icons: true,
            hide_endpoint_when_description: true,
            custom_groups: vec![],
            two_column_layout: false,
            subscription_user_agent: SUB_UA_BOTH.into(),
            global_proxy_core: ProxyCore::Auto,
            energy_saver: false,
            provider_report_enabled: true,
            send_device_info_to_subscriptions: true,
            custom_hwid: String::new(),
            telegram_proxy_enabled: false,
            hide_telegram_proxy_notification: false,
            set_system_proxy: true,
            free_servers_limit: DEFAULT_FREE_SERVERS_LIMIT,
            ping_timeout_sec: DEFAULT_PING_TIMEOUT_SEC,
            ping_precision: DEFAULT_PING_PRECISION,
            show_ping_controls: false,
            free_servers_from_pages: false,
            allow_config_editing: false,
            share_universal_link: true,
        }
    }
}

impl AppBehaviorSettings {
    /// A subscription's group key changed with its URL: pins, collapse, ping-sort and folder
    /// membership follow `new_key`.
    pub fn with_subscription_key_renamed(&self, old_key: &str, new_key: &str) -> Self {
        if old_key == new_key {
            return self.clone();
        }
        let moved = |set: &BTreeSet<String>| {
            let mut s = set.clone();
            if s.remove(old_key) {
                s.insert(new_key.to_string());
            }
            s
        };
        let (old_member, new_member) = (CustomGroup::sub_member(old_key), CustomGroup::sub_member(new_key));
        Self {
            collapsed_subscription_groups: moved(&self.collapsed_subscription_groups),
            pinned_subscription_groups: self.pinned_subscription_groups.iter().map(|k| if k == old_key { new_key.to_string() } else { k.clone() }).collect(),
            ping_sorted_subscription_groups: moved(&self.ping_sorted_subscription_groups),
            ping_sort_descending_subscription_groups: moved(&self.ping_sort_descending_subscription_groups),
            custom_groups: self
                .custom_groups
                .iter()
                .map(|g| CustomGroup {
                    members: g.members.iter().map(|m| if *m == old_member { new_member.clone() } else { m.clone() }).collect(),
                    ..g.clone()
                })
                .collect(),
            ..self.clone()
        }
    }

    /// The legacy "yptun" and anything unknown mean [`SUB_UA_BOTH`].
    pub fn effective_subscription_ua(&self) -> &str {
        if SUBSCRIPTION_UA_MODES.contains(&self.subscription_user_agent.as_str()) {
            &self.subscription_user_agent
        } else {
            SUB_UA_BOTH
        }
    }

    /// Blank or the legacy google.com → [`DEFAULT_PING_URL`].
    pub fn effective_ping_url(&self) -> String {
        let t = self.ping_url.trim();
        if t.is_empty() || t == LEGACY_PING_URL {
            DEFAULT_PING_URL.into()
        } else {
            t.into()
        }
    }

    pub fn effective_ping_timeout_ms(&self) -> i32 {
        self.ping_timeout_sec.clamp(MIN_PING_TIMEOUT_SEC, MAX_PING_TIMEOUT_SEC) * 1000
    }

    pub fn effective_ping_precision(&self) -> i32 {
        self.ping_precision.clamp(MIN_PING_PRECISION, MAX_PING_PRECISION)
    }

    pub fn effective_ping_parallelism(&self) -> i32 {
        self.ping_parallelism.clamp(MIN_PING_PARALLELISM, MAX_PING_PARALLELISM)
    }
}
