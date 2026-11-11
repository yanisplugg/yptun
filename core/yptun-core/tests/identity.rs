//! `PersistentDeviceIdentityProvider` behaviour (no Kotlin test existed).

use std::sync::{Arc, Mutex};
use std::thread;

use yptun_core::identity::{generate_install_id, DeviceIdentityProvider, IdentityStore};

#[derive(Default)]
struct Saved {
    device: Option<String>,
    app: Option<String>,
    device_saves: Vec<String>,
}

#[derive(Clone, Default)]
struct MemoryStore {
    saved: Arc<Mutex<Saved>>,
    stable: Option<String>,
}

impl IdentityStore for MemoryStore {
    fn load_device_identity(&self) -> Option<String> {
        self.saved.lock().unwrap().device.clone()
    }
    fn save_device_identity(&self, value: &str) {
        let mut saved = self.saved.lock().unwrap();
        saved.device = Some(value.to_string());
        saved.device_saves.push(value.to_string());
    }
    fn load_app_install_id(&self) -> Option<String> {
        self.saved.lock().unwrap().app.clone()
    }
    fn save_app_install_id(&self, value: &str) {
        self.saved.lock().unwrap().app = Some(value.to_string());
    }
    fn platform_stable_id(&self) -> Option<String> {
        self.stable.clone()
    }
}

fn is_install_id(id: &str) -> bool {
    id.len() == 32 && id.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

#[test]
fn install_ids_are_32_random_hex_chars() {
    let a = generate_install_id();
    let b = generate_install_id();
    assert!(is_install_id(&a), "{a}");
    assert_ne!(a, b);
}

#[test]
fn hwid_is_created_once_and_reused() {
    let store = MemoryStore::default();
    let provider = DeviceIdentityProvider::new(Box::new(store.clone()));

    let first = provider.hwid();
    assert!(is_install_id(&first));
    assert_eq!(first, provider.hwid());
    assert_eq!(
        vec![first.clone()],
        store.saved.lock().unwrap().device_saves
    );
    // A new provider over the same storage (the VPN extension, the next launch) sees the same id.
    assert_eq!(first, DeviceIdentityProvider::new(Box::new(store)).hwid());
}

#[test]
fn hwid_prefers_the_platform_stable_id() {
    let store = MemoryStore {
        stable: Some("a1b2c3d4e5f60718".into()),
        ..Default::default()
    };
    assert_eq!(
        "a1b2c3d4e5f60718",
        DeviceIdentityProvider::new(Box::new(store.clone())).hwid()
    );
    assert_eq!(
        Some("a1b2c3d4e5f60718".to_string()),
        store.saved.lock().unwrap().device
    );

    let blank = MemoryStore {
        stable: Some("  ".into()),
        ..Default::default()
    };
    assert!(is_install_id(
        &DeviceIdentityProvider::new(Box::new(blank)).hwid()
    ));
}

#[test]
fn legacy_install_prefix_is_migrated() {
    let store = MemoryStore::default();
    store.saved.lock().unwrap().device = Some("install-0123abcd".into());
    let provider = DeviceIdentityProvider::new(Box::new(store.clone()));

    assert_eq!("0123abcd", provider.hwid());
    assert_eq!("0123abcd", provider.hwid());
    // Saved once, stripped; a stored id is never replaced by the platform-stable one.
    assert_eq!(vec!["0123abcd"], store.saved.lock().unwrap().device_saves);
}

#[test]
fn app_id_is_separate_random_and_persistent() {
    let store = MemoryStore {
        stable: Some("android-id".into()),
        ..Default::default()
    };
    store.saved.lock().unwrap().app = Some(" ".into());
    let provider = DeviceIdentityProvider::new(Box::new(store.clone()));

    let app = provider.app_id();
    assert!(is_install_id(&app));
    assert_ne!(provider.hwid(), app);
    assert_eq!(app, provider.app_id());
    assert_eq!(Some(app), store.saved.lock().unwrap().app);
}

#[test]
fn concurrent_first_calls_agree() {
    let store = MemoryStore::default();
    let provider = Arc::new(DeviceIdentityProvider::new(Box::new(store.clone())));

    let ids: Vec<String> = (0..8)
        .map(|_| {
            let provider = Arc::clone(&provider);
            thread::spawn(move || provider.hwid())
        })
        .collect::<Vec<_>>()
        .into_iter()
        .map(|handle| handle.join().unwrap())
        .collect();

    assert!(ids.windows(2).all(|pair| pair[0] == pair[1]));
    assert_eq!(1, store.saved.lock().unwrap().device_saves.len());
}
