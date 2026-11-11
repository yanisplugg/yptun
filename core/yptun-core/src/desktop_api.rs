//! Shared desktop command wire types and presentation helpers.
//!
//! Windows and Linux expose the same application contract to the Slint UI. Keeping these records
//! here prevents platform hosts from silently drifting in field names, defaults, or labels.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::location::{
    LocationConfig, LocationEntry, LocationMetadata, DEFAULT_UPDATE_INTERVAL_HOURS,
};
use crate::model::EngineType;
use crate::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use crate::settings::AppBehaviorSettings;
use crate::update::{AppUpdateSettings, AppUpdateSettingsStore};
use crate::vpn_settings::{SplitTunnelState, VpnSettings};

#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CommandRequest {
    pub command: String,
    pub text: String,
    pub id: String,
    pub mode: String,
}

impl Default for CommandRequest {
    fn default() -> Self {
        Self {
            command: "snapshot".into(),
            text: String::new(),
            id: String::new(),
            mode: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileItem {
    pub id: String,
    pub name: String,
    pub subtitle: String,
    pub engine: String,
    pub is_complete: bool,
    pub is_subscription: bool,
    pub subscription_name: Option<String>,
    pub ping_ms: Option<i32>,
    pub ping_tested: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionItem {
    pub url: String,
    pub name: String,
    pub custom_name: String,
    pub cascade_link: String,
    pub update_interval_hours: i32,
    pub auto_update_enabled: bool,
    pub location_count: usize,
    pub expires_at_epoch_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeDiagnostics {
    pub active_engine: String,
    pub connection_mode: String,
    pub engine_alive: bool,
    pub connected_since_epoch_ms: u64,
    pub in_core_tun: bool,
    pub external_tun: bool,
    pub bypass_route_count: usize,
    pub watchdog_probe_failures: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreSnapshot {
    pub status: String,
    pub status_label: String,
    pub status_detail: String,
    pub is_connected: bool,
    pub runtime_available: bool,
    pub connection_mode: String,
    pub active_profile_id: Option<String>,
    pub profiles: Vec<ProfileItem>,
    pub subscriptions: Vec<SubscriptionItem>,
    pub session: RuntimeDiagnostics,
    pub telegram_proxy_status: String,
    pub telegram_proxy_detail: String,
    pub logs: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocationEditorData {
    pub storage_id: String,
    pub location: LocationConfig,
    pub is_new: bool,
    pub is_subscription: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsSnapshot {
    pub traffic: TrafficSettings,
    pub routing: RoutingRules,
    pub routing_profiles: RoutingProfilesState,
    pub split_tunnel: SplitTunnelState,
    pub app_behavior: AppBehaviorSettings,
    pub socks: DesktopSocksProxySettings,
    pub updates: AppUpdateSettings,
}

impl SettingsSnapshot {
    pub fn load(
        app_data_dir: &std::path::Path,
        os: crate::desktop_native_assets::DesktopOs,
    ) -> Self {
        let vpn = VpnSettings::new(app_data_dir);
        Self {
            traffic: vpn.load_traffic(),
            routing: vpn.load_routing(),
            routing_profiles: vpn.load_routing_profiles(),
            split_tunnel: vpn.load_split_tunnel(),
            app_behavior: vpn.load_app_behavior(),
            socks: crate::desktop_stores::DesktopSocksSettingsStore::new(app_data_dir, os).load(),
            updates: crate::desktop_stores::FileAppUpdateSettingsStore::new(app_data_dir).load(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextPayload {
    pub text: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCheckPayload {
    pub version: String,
    pub html_url: String,
    pub asset_name: String,
    pub download_url: String,
    pub size_bytes: Option<i64>,
    pub sha256: Option<String>,
    pub is_update_available: bool,
}

#[derive(Serialize)]
pub struct Response<T: Serialize> {
    pub ok: bool,
    pub data: Option<T>,
    pub error: Option<String>,
}

impl<T: Serialize> Response<T> {
    pub fn success(data: T) -> Self {
        Self {
            ok: true,
            data: Some(data),
            error: None,
        }
    }

    pub fn error(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            data: None,
            error: Some(message.into()),
        }
    }
}

pub fn profile_item(
    entry: &LocationEntry,
    ping_results: &BTreeMap<String, Option<i32>>,
) -> ProfileItem {
    let location = entry.location();
    let subscription = entry.metadata.as_ref().and_then(subscription_name);
    let subtitle = match location.engine {
        EngineType::Stealth | EngineType::Chain => format!(
            "{} · {}",
            location.provider_name(),
            location.transport_name()
        ),
        EngineType::Standard => location
            .proxy
            .as_ref()
            .map(|proxy| format!("{} · {}:{}", proxy.kind, proxy.server, proxy.server_port))
            .unwrap_or_else(|| "Прокси не настроен".into()),
        EngineType::VkTurn => "VK-TURN".into(),
        EngineType::MasterDns => "MasterDNS".into(),
        EngineType::OpenFlux => "OpenFlux".into(),
        EngineType::Snolc => "snolc".into(),
    };
    ProfileItem {
        id: entry.storage_id.clone(),
        name: location.display_name(),
        subtitle,
        engine: engine_name(location.engine).into(),
        is_complete: location.is_complete(),
        is_subscription: entry
            .subscription_url
            .as_deref()
            .is_some_and(|value| !value.trim().is_empty()),
        subscription_name: subscription,
        ping_ms: ping_results.get(&entry.storage_id).copied().flatten(),
        ping_tested: ping_results.contains_key(&entry.storage_id),
    }
}

pub fn subscription_item(url: String, entries: &[LocationEntry]) -> SubscriptionItem {
    let metadata = entries
        .iter()
        .find_map(|entry| entry.metadata.as_ref()?.subscription.as_ref());
    let name = metadata
        .and_then(|subscription| subscription.name.as_deref())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(&url)
        .to_string();
    SubscriptionItem {
        url,
        name,
        custom_name: metadata
            .and_then(|subscription| subscription.custom_name.clone())
            .unwrap_or_default(),
        cascade_link: metadata
            .and_then(|subscription| subscription.cascade_link.clone())
            .unwrap_or_default(),
        update_interval_hours: metadata
            .and_then(|subscription| subscription.update_interval_hours)
            .unwrap_or(DEFAULT_UPDATE_INTERVAL_HOURS),
        auto_update_enabled: metadata.is_none_or(|subscription| subscription.auto_update_enabled),
        location_count: entries.len(),
        expires_at_epoch_ms: entries.iter().find_map(|entry| {
            entry
                .metadata
                .as_ref()?
                .subscription
                .as_ref()?
                .expires_at_epoch_ms
        }),
    }
}

fn subscription_name(metadata: &LocationMetadata) -> Option<String> {
    metadata
        .subscription
        .as_ref()
        .and_then(|subscription| subscription.name.clone())
        .filter(|name| !name.trim().is_empty())
}

fn engine_name(engine: EngineType) -> &'static str {
    match engine {
        EngineType::Stealth => "Stealth",
        EngineType::Standard => "Proxy",
        EngineType::Chain => "Chain",
        EngineType::VkTurn => "VK-TURN",
        EngineType::MasterDns => "MasterDNS",
        EngineType::OpenFlux => "OpenFlux",
        EngineType::Snolc => "snolc",
    }
}
