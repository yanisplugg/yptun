//! Shared file-backed desktop repository and installation identity.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::UNIX_EPOCH;

use serde::{Deserialize, Serialize};

use crate::location::LocationBundleV4;
use crate::repository::Store;
use crate::settings::AppBehaviorSettings;
use crate::vpn_settings::VpnSettings;

pub const BUNDLE_FILE: &str = "locations_v4.json";
pub const LEGACY_BUNDLE_FILE: &str = "locations_v3.json";
const REPOSITORY_STATE_FILE: &str = "repository_state.json";
const DEVICE_IDENTITY_FILE: &str = "device_identity";

#[derive(Debug, Default, Serialize, Deserialize)]
struct RepositoryState {
    #[serde(default)]
    values: BTreeMap<String, String>,
}

/// File storage compatible with the legacy Compose desktop client.
pub struct DesktopFileStore {
    root: PathBuf,
    state_lock: Mutex<()>,
}

impl DesktopFileStore {
    pub fn new(root: PathBuf) -> Self {
        let _ = fs::create_dir_all(&root);
        Self {
            root,
            state_lock: Mutex::new(()),
        }
    }

    fn bundle_file(&self) -> PathBuf {
        self.root.join(BUNDLE_FILE)
    }

    fn legacy_bundle_file(&self) -> PathBuf {
        self.root.join(LEGACY_BUNDLE_FILE)
    }

    fn stored_bundle_file(&self) -> Option<PathBuf> {
        [self.bundle_file(), self.legacy_bundle_file()]
            .into_iter()
            .find(|path| path.is_file())
    }

    fn state_file(&self) -> PathBuf {
        self.root.join(REPOSITORY_STATE_FILE)
    }

    fn write_atomic(path: &Path, text: &str) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let temp = path.with_extension(format!("{}.tmp", std::process::id()));
        fs::write(&temp, text)?;
        match fs::rename(&temp, path) {
            Ok(()) => Ok(()),
            Err(_) if path.exists() => {
                fs::remove_file(path)?;
                fs::rename(temp, path)
            }
            Err(error) => Err(error),
        }
    }

    fn load_state(&self) -> RepositoryState {
        fs::read_to_string(self.state_file())
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    fn save_state(&self, state: &RepositoryState) {
        if let Ok(text) = serde_json::to_string_pretty(state) {
            let _ = Self::write_atomic(&self.state_file(), &text);
        }
    }
}

impl Store for DesktopFileStore {
    fn load_bundle(&self) -> Option<LocationBundleV4> {
        fs::read_to_string(self.stored_bundle_file()?)
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
    }

    fn save_bundle(&self, bundle: &LocationBundleV4) {
        if let Ok(text) = serde_json::to_string_pretty(&bundle.normalized()) {
            let _ = Self::write_atomic(&self.bundle_file(), &text);
        }
    }

    fn bundle_version_token(&self) -> Option<i64> {
        let metadata = fs::metadata(self.stored_bundle_file()?).ok()?;
        let modified = metadata
            .modified()
            .ok()?
            .duration_since(UNIX_EPOCH)
            .ok()?
            .as_nanos() as i64;
        Some(modified ^ metadata.len() as i64)
    }

    fn load_value(&self, key: &str) -> Option<String> {
        let _guard = self
            .state_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.load_state().values.get(key).cloned()
    }

    fn save_value(&self, key: &str, value: &str) {
        let _guard = self
            .state_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let mut state = self.load_state();
        state.values.insert(key.to_string(), value.to_string());
        self.save_state(&state);
    }

    fn app_behavior(&self) -> AppBehaviorSettings {
        VpnSettings::new(&self.root).load_app_behavior()
    }
}

pub fn device_identity(root: &Path) -> String {
    let path = root.join(DEVICE_IDENTITY_FILE);
    if let Ok(value) = fs::read_to_string(&path) {
        let value = value.trim();
        if !value.is_empty() {
            return value.to_string();
        }
    }

    let value = crate::identity::generate_install_id();
    let _ = fs::create_dir_all(root);
    let _ = fs::write(path, &value);
    value
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_dir(name: &str) -> PathBuf {
        let base = std::env::var_os("CARGO_TARGET_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir);
        let path = base.join(format!(
            "yptun-desktop-store-{name}-{}-{}",
            std::process::id(),
            crate::desktop_app::now_ms()
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }

    #[test]
    fn legacy_bundle_state_and_identity_share_one_store() {
        let root = test_dir("compatibility");
        fs::write(root.join(LEGACY_BUNDLE_FILE), "{}").unwrap();
        let store = DesktopFileStore::new(root.clone());

        assert!(store.load_bundle().is_some());
        assert!(store.bundle_version_token().is_some());
        store.save_value("key", "value");
        assert_eq!(store.load_value("key").as_deref(), Some("value"));

        let identity = device_identity(&root);
        assert_eq!(identity.len(), 32);
        assert!(identity.bytes().all(|byte| byte.is_ascii_hexdigit()));
        assert_eq!(device_identity(&root), identity);

        let _ = fs::remove_dir_all(root);
    }
}
