//! The location list as the screens show it (port of `LocationItem` and the list helpers of
//! `LocationViewModel` / `HomeScreen`): rows built from the bundle or the cold-start index, which
//! row stays selected after a reload, and the clean-up and auto-connect picks over the list.

use std::collections::HashSet;

use crate::location::{
    LocationBundleV4, LocationConfig, LocationEntry, LocationMetadata, LocationViewIndex,
    LocationViewItem,
};
use crate::model::{EngineType, ProxyProfile, TYPE_VLESS};
use crate::pings::Pings;
use crate::util::is_blank;

/// How many candidates "Auto = fastest" tries before giving up.
pub const AUTO_CONNECT_MAX_ATTEMPTS: usize = 6;
/// The connect budget of one auto-connect attempt.
pub const AUTO_CONNECT_ATTEMPT_TIMEOUT_MS: u64 = 15_000;
/// How long a stop may take to settle before the next attempt.
pub const STOP_SETTLE_TIMEOUT_MS: u64 = 6_000;

/// One row of the list.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LocationItem {
    pub storage_id: String,
    pub full_name: String,
    pub config: Option<LocationConfig>,
    pub subscription_url: Option<String>,
    pub metadata: Option<LocationMetadata>,
}

impl LocationItem {
    /// The row of a stored entry, with its full (connectable) config.
    pub fn from_entry(entry: &LocationEntry) -> Self {
        let config = entry.location();
        Self {
            storage_id: entry.storage_id.clone(),
            full_name: config.display_name(),
            config: Some(config),
            subscription_url: entry.subscription_url.clone(),
            metadata: entry.metadata.clone(),
        }
    }

    /// A display-only row from the cold-start index: label, engine and a `type + server` proxy stub
    /// so the subtitle reads "VLESS · host" — not complete, so it is neither pinged nor connected
    /// until the full load replaces it.
    pub fn from_view_item(item: &LocationViewItem) -> Self {
        let proxy =
            (!is_blank(&item.proxy_type) || !is_blank(&item.proxy_server)).then(|| ProxyProfile {
                kind: if is_blank(&item.proxy_type) {
                    TYPE_VLESS.to_string()
                } else {
                    item.proxy_type.clone()
                },
                server: item.proxy_server.clone(),
                ..ProxyProfile::default()
            });
        Self {
            storage_id: item.storage_id.clone(),
            full_name: item.name.clone(),
            config: Some(LocationConfig {
                name: item.name.clone(),
                description: item.description.clone(),
                engine: item.engine,
                bypass_provider: item.auth_provider.clone(),
                transport: item.transport.clone(),
                proxy,
                ..LocationConfig::default()
            }),
            subscription_url: item.subscription_url.clone(),
            metadata: item.metadata.clone(),
        }
    }

    /// The row comes from a subscription (not a location the user added).
    pub fn is_subscription(&self) -> bool {
        self.subscription_url
            .as_deref()
            .is_some_and(|url| !is_blank(url))
    }

    /// The row's config can connect (and be pinged).
    pub fn is_complete(&self) -> bool {
        self.config
            .as_ref()
            .is_some_and(LocationConfig::is_complete)
    }

    fn engine(&self) -> Option<EngineType> {
        self.config.as_ref().map(|c| c.engine)
    }
}

/// The list after a load and the row to keep selected.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LoadedList {
    pub items: Vec<LocationItem>,
    pub selected_id: Option<String>,
    /// The selection moved off a missing or blank active id: store it as the active location.
    pub persist_selection: bool,
}

/// The rows of `bundle` and the selection (`loadLocations`): the active location, or the first row
/// when the active id is blank or no longer in the list.
pub fn load_list(bundle: &LocationBundleV4) -> LoadedList {
    let items: Vec<LocationItem> = bundle
        .locations
        .iter()
        .map(LocationItem::from_entry)
        .collect();
    let (selected_id, persist_selection) =
        resolve_selection(&items, bundle.active_location_id.as_deref());
    LoadedList {
        items,
        selected_id,
        persist_selection,
    }
}

/// Which row stays selected for the stored active id `current`, and whether that has to be saved.
pub fn resolve_selection(items: &[LocationItem], current: Option<&str>) -> (Option<String>, bool) {
    let missing = current.is_none_or(is_blank)
        || !items
            .iter()
            .any(|it| Some(it.storage_id.as_str()) == current);
    let next = if !items.is_empty() && missing {
        items.first().map(|it| it.storage_id.clone())
    } else {
        current.map(str::to_string)
    };
    let persist = next.as_deref() != current
        && items
            .iter()
            .any(|it| Some(it.storage_id.as_str()) == next.as_deref());
    (next, persist)
}

/// The list painted from the cold-start index before the full load lands: its rows and the
/// active id (the first row when none is stored).
pub fn seed_list(index: &LocationViewIndex) -> LoadedList {
    let items: Vec<LocationItem> = index
        .items
        .iter()
        .map(LocationItem::from_view_item)
        .collect();
    let selected_id = index
        .active_location_id
        .clone()
        .or_else(|| items.first().map(|it| it.storage_id.clone()));
    LoadedList {
        items,
        selected_id,
        persist_selection: false,
    }
}

/// The edited location comes from an encrypted subscription: its settings are not shown at all.
pub fn is_locked(items: &[LocationItem], storage_id: Option<&str>) -> bool {
    storage_id.is_some_and(|id| {
        items
            .iter()
            .find(|it| it.storage_id == id)
            .and_then(|it| it.metadata.as_ref())
            .is_some_and(|m| m.locked)
    })
}

/// Engines that can only be measured through their own live tunnel, never off-tunnel.
pub fn is_tunnel_only(engine: EngineType) -> bool {
    matches!(
        engine,
        EngineType::VkTurn | EngineType::MasterDns | EngineType::OpenFlux | EngineType::Snolc
    )
}

/// Storage ids of every subscription row ("delete all subscriptions" keeps the user's own ones).
pub fn subscription_location_ids(items: &[LocationItem]) -> Vec<String> {
    items
        .iter()
        .filter(|it| it.is_subscription())
        .map(|it| it.storage_id.clone())
        .collect()
}

/// Own (non-subscription) rows the last ping pass found unreachable: probed, with a `None` result.
/// Tunnel-only engines show `None` until connected, so they are never counted.
pub fn unreachable_custom_location_ids(items: &[LocationItem], pings: &Pings) -> Vec<String> {
    items
        .iter()
        .filter(|it| !it.is_subscription())
        .filter(|it| !it.engine().is_some_and(is_tunnel_only))
        .filter(|it| matches!(pings.get(&it.storage_id), Some(None)))
        .map(|it| it.storage_id.clone())
        .collect()
}

/// Own rows whose configuration repeats another row's. Subscription rows are seen first, so an own
/// copy of a subscription server is the duplicate; among own rows the first one stays. Subscription
/// rows are never returned.
pub fn duplicate_custom_location_ids(items: &[LocationItem]) -> Vec<String> {
    let key = |it: &LocationItem| {
        it.config
            .as_ref()
            .map(|c| serde_json::to_string(&c.dedup_key()).unwrap_or_default())
    };
    let mut seen: HashSet<String> = items
        .iter()
        .filter(|it| it.is_subscription())
        .filter_map(key)
        .collect();
    items
        .iter()
        .filter(|it| !it.is_subscription())
        .filter(|it| key(it).is_some_and(|k| !seen.insert(k)))
        .map(|it| it.storage_id.clone())
        .collect()
}

/// The rows "Auto = fastest" may connect to: complete ones, limited to `only_ids` when given.
pub fn auto_connect_candidates<'a>(
    items: &'a [LocationItem],
    only_ids: Option<&[String]>,
) -> Vec<&'a LocationItem> {
    items
        .iter()
        .filter(|it| it.is_complete() && only_ids.is_none_or(|ids| ids.contains(&it.storage_id)))
        .collect()
}

/// The order to try `candidates` in after their ping pass: the ones that answered, fastest first;
/// when none did, all of them in list order.
pub fn auto_connect_order(candidates: &[&LocationItem], pings: &Pings) -> Vec<String> {
    let mut reachable: Vec<(&str, i32)> = candidates
        .iter()
        .filter_map(|it| {
            pings
                .get(&it.storage_id)
                .copied()
                .flatten()
                .map(|ms| (it.storage_id.as_str(), ms))
        })
        .collect();
    reachable.sort_by_key(|&(_, ms)| ms);
    if reachable.is_empty() {
        candidates.iter().map(|it| it.storage_id.clone()).collect()
    } else {
        reachable
            .into_iter()
            .map(|(id, _)| id.to_string())
            .collect()
    }
}

/// The part of `order` an auto-connect run actually tries (`autoConnectInOrder`).
pub fn auto_connect_attempts(order: &[String]) -> &[String] {
    &order[..order.len().min(AUTO_CONNECT_MAX_ATTEMPTS)]
}

/// How a location is pinged from the list.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PingPlan {
    /// A tunnel-only engine that is not the connected location: probing would measure the wrong
    /// tunnel, so the row keeps its neutral dash.
    Skip,
    /// Probe it; `is_active` = it is the connected (active) location.
    Probe { is_active: bool },
}

/// `performPingFor`: whether and how `config` is pinged while `active` is the active location.
pub fn ping_plan(config: &LocationConfig, active: &LocationConfig) -> PingPlan {
    let is_active = config.normalized() == *active;
    if is_tunnel_only(config.engine) && !is_active {
        PingPlan::Skip
    } else {
        PingPlan::Probe { is_active }
    }
}
