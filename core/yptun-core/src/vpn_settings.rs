//! Desktop VPN settings storage: each settings model is one JSON file under `<appData>/settings/`.
//! Port of JvmVpnSettings.kt (desktop counterpart of Android's `vpnPrefDataStore`). Read on every
//! connect, so external edits take effect on reconnect.
//!
//! Same JSON rules as the Kotlin side: unknown keys ignored, defaults encoded, pretty-printed with a
//! 4-space indent; a missing or unreadable file gives the default value, a failed save is ignored.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use crate::settings::AppBehaviorSettings;

pub const DIR: &str = "settings";
pub const TRAFFIC_FILE: &str = "traffic.json";
pub const ROUTING_FILE: &str = "routing.json";
pub const ROUTING_PROFILES_FILE: &str = "routing_profiles.json";
pub const SPLIT_TUNNEL_FILE: &str = "split_tunnel.json";
pub const APP_BEHAVIOR_FILE: &str = "app_behavior.json";

pub const SPLIT_MODE_ALL_APPS: &str = "all_apps";

/// Desktop per-process split tunneling: exe-name lists.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SplitTunnelState {
    pub mode: String,
    pub proxy_processes: BTreeSet<String>,
    pub bypass_processes: BTreeSet<String>,
}

impl Default for SplitTunnelState {
    fn default() -> Self {
        Self {
            mode: SPLIT_MODE_ALL_APPS.to_string(),
            proxy_processes: BTreeSet::new(),
            bypass_processes: BTreeSet::new(),
        }
    }
}

/// Pretty JSON with a 4-space indent (Kotlin `prettyPrint`).
pub fn to_pretty_json<T: Serialize>(value: &T) -> Option<String> {
    let mut buf = Vec::new();
    let fmt = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut buf, fmt);
    value.serialize(&mut ser).ok()?;
    String::from_utf8(buf).ok()
}

#[derive(Debug, Clone)]
pub struct VpnSettings {
    dir: PathBuf,
}

impl VpnSettings {
    pub fn new(app_data_dir: &Path) -> Self {
        Self { dir: app_data_dir.join(DIR) }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn path(&self, file_name: &str) -> PathBuf {
        self.dir.join(file_name)
    }

    /// The decoded file, or `T::default()` when it is missing or not valid.
    pub fn load<T: DeserializeOwned + Default>(&self, file_name: &str) -> T {
        let _ = fs::create_dir_all(&self.dir);
        match fs::read_to_string(self.path(file_name)) {
            Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
            Err(_) => T::default(),
        }
    }

    /// Writes [`value`]; `false` if that failed (the Kotlin side ignored failures).
    pub fn save<T: Serialize>(&self, file_name: &str, value: &T) -> bool {
        if fs::create_dir_all(&self.dir).is_err() {
            return false;
        }
        match to_pretty_json(value) {
            Some(text) => fs::write(self.path(file_name), text).is_ok(),
            None => false,
        }
    }

    pub fn load_traffic(&self) -> TrafficSettings {
        self.load::<TrafficSettings>(TRAFFIC_FILE).normalized()
    }

    pub fn save_traffic(&self, value: &TrafficSettings) -> bool {
        self.save(TRAFFIC_FILE, value)
    }

    pub fn load_routing(&self) -> RoutingRules {
        self.load(ROUTING_FILE)
    }

    pub fn save_routing(&self, value: &RoutingRules) -> bool {
        self.save(ROUTING_FILE, value)
    }

    pub fn load_routing_profiles(&self) -> RoutingProfilesState {
        self.load(ROUTING_PROFILES_FILE)
    }

    pub fn save_routing_profiles(&self, value: &RoutingProfilesState) -> bool {
        self.save(ROUTING_PROFILES_FILE, value)
    }

    pub fn load_split_tunnel(&self) -> SplitTunnelState {
        self.load(SPLIT_TUNNEL_FILE)
    }

    pub fn save_split_tunnel(&self, value: &SplitTunnelState) -> bool {
        self.save(SPLIT_TUNNEL_FILE, value)
    }

    pub fn load_app_behavior(&self) -> AppBehaviorSettings {
        self.load(APP_BEHAVIOR_FILE)
    }

    pub fn save_app_behavior(&self, value: &AppBehaviorSettings) -> bool {
        self.save(APP_BEHAVIOR_FILE, value)
    }
}
