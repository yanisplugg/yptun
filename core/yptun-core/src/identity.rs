//! Who this install is to subscription panels, ported from the Kotlin
//! `PersistentDeviceIdentityProvider`: the HWID (platform-stable when the platform has such an id)
//! and the random per-install app id sent as `x-app-id`.

use std::sync::Mutex;

/// Where the platform keeps the two ids.
pub trait IdentityStore: Send + Sync {
    fn load_device_identity(&self) -> Option<String>;
    fn save_device_identity(&self, value: &str);
    fn load_app_install_id(&self) -> Option<String> {
        None
    }
    fn save_app_install_id(&self, _value: &str) {}
    /// An id that survives reinstalls and data clears (Android's ANDROID_ID); `None` elsewhere.
    fn platform_stable_id(&self) -> Option<String> {
        None
    }
}

/// Creates each id once and returns the stored one from then on.
pub struct DeviceIdentityProvider {
    store: Box<dyn IdentityStore>,
    // One creator at a time: two first calls must not store two different ids.
    lock: Mutex<()>,
}

impl DeviceIdentityProvider {
    pub fn new(store: Box<dyn IdentityStore>) -> Self {
        Self {
            store,
            lock: Mutex::new(()),
        }
    }

    /// The HWID panels count devices by. A platform-stable id is preferred so it survives
    /// reinstalls, closer to how v2rayTun/Hiddify report devices; otherwise a random install id.
    pub fn hwid(&self) -> String {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(existing) = self.store.load_device_identity() {
            // Migrates legacy ids that had an "install-" prefix.
            let cleaned = existing.strip_prefix("install-").unwrap_or(&existing);
            if cleaned != existing {
                self.store.save_device_identity(cleaned);
            }
            return cleaned.to_string();
        }
        let identity = self
            .store
            .platform_stable_id()
            .filter(|id| !id.trim().is_empty())
            .unwrap_or_else(generate_install_id);
        self.store.save_device_identity(&identity);
        identity
    }

    /// Stable, app-specific install id sent as the `x-app-id` header on subscription requests.
    /// Unlike [`Self::hwid`] it is always random, so a panel owner can register and track OUR app
    /// (e.g. in Remnawave). Generated on first use.
    pub fn app_id(&self) -> String {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(existing) = self
            .store
            .load_app_install_id()
            .filter(|id| !id.trim().is_empty())
        {
            return existing;
        }
        let identity = generate_install_id();
        self.store.save_app_install_id(&identity);
        identity
    }
}

/// 16 random bytes as 32 lowercase hex characters.
pub fn generate_install_id() -> String {
    crate::installer::random_bytes(16)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
