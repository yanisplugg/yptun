use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_stores::{DesktopSocksSettingsStore, FileAppUpdateSettingsStore};
use yptun_core::update::{AppUpdateSettings, AppUpdateSettingsStore};

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&d).unwrap();
    d
}

#[test]
fn socks_missing_file_is_raw_default() {
    let dir = temp_dir("socks-missing");
    let store = DesktopSocksSettingsStore::new(&dir, DesktopOs::Windows);
    assert_eq!(store.load(), DesktopSocksProxySettings::default());
    assert_eq!(store.load().port, 10808);
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn socks_save_writes_kotlin_pretty_json() {
    let dir = temp_dir("socks-save");
    let store = DesktopSocksSettingsStore::new(&dir.join("sub"), DesktopOs::Windows);
    store.save(&DesktopSocksProxySettings::default()).unwrap();
    let text = fs::read_to_string(store.file()).unwrap();
    let expected = "{\n    \"host\": \"127.0.0.1\",\n    \"port\": 8080,\n    \"username\": \"\",\n    \"password\": \"\",\n    \"secured\": false,\n    \"customPort\": null\n}";
    assert_eq!(text, expected);
    assert_eq!(store.load().port, 8080);
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn socks_garbage_and_partial_files() {
    let dir = temp_dir("socks-garbage");
    let store = DesktopSocksSettingsStore::new(&dir, DesktopOs::Linux);
    fs::write(store.file(), "not json").unwrap();
    assert_eq!(store.load(), DesktopSocksProxySettings::default());
    fs::write(
        store.file(),
        r#"{"secured":true,"port":5000,"username":"u","password":"p","extra":1}"#,
    )
    .unwrap();
    let s = store.load();
    assert_eq!(s.host, "127.0.0.1");
    assert_eq!(s.port, 5000);
    assert!(s.secured);
    assert!(s.is_configured());
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn update_settings_roundtrip() {
    let dir = temp_dir("upd-store");
    let store = FileAppUpdateSettingsStore::new(&dir);
    assert_eq!(store.load(), AppUpdateSettings::default());
    let settings = AppUpdateSettings::default();
    store.try_save(&settings).unwrap();
    assert!(fs::read_to_string(store.file()).unwrap().starts_with("{\n    \""));
    assert_eq!(store.load(), settings.normalized());
    fs::write(store.file(), "[oops").unwrap();
    assert_eq!(store.load(), AppUpdateSettings::default());
    fs::remove_dir_all(&dir).ok();
}
