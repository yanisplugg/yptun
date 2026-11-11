//! Ports of `JvmDesktopSocksProxySettingsStore.kt` and `JvmUpdateSettingsStore.kt`: JSON files in
//! the desktop app data directory, written the way kotlinx.serialization wrote them (pretty print
//! with a 4-space indent, defaults included, unknown keys ignored on read).
//!
//! * load: a missing file gives the plain default; a broken one gives the default too; otherwise the
//!   decoded value, normalized.
//! * save: normalizes, creates the directory and writes. Kotlin threw on I/O errors; `try_save`
//!   returns them, the `AppUpdateSettingsStore::save` trait method (no Result) drops them.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::update::{AppUpdateSettings, AppUpdateSettingsStore};

pub const SOCKS_SETTINGS_FILE: &str = "desktop_socks_proxy_settings.json";
pub const UPDATE_SETTINGS_FILE: &str = "update_settings.json";

/// kotlinx `Json { prettyPrint = true }` output: 4-space indent, `"key": value`.
pub fn kotlin_pretty_json<T: Serialize>(value: &T) -> Result<String, serde_json::Error> {
    let mut buf = Vec::new();
    let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut buf, formatter);
    value.serialize(&mut ser)?;
    Ok(String::from_utf8(buf).unwrap_or_default())
}

fn read_json<T: DeserializeOwned>(file: &Path) -> Option<Option<T>> {
    if !file.exists() {
        return Some(None);
    }
    let text = fs::read_to_string(file).ok()?;
    serde_json::from_str::<T>(&text).ok().map(Some)
}

fn write_json<T: Serialize>(file: &Path, value: &T) -> io::Result<()> {
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent)?;
    }
    let text = kotlin_pretty_json(value).map_err(io::Error::other)?;
    fs::write(file, text)
}

/// `JvmDesktopSocksProxySettingsStore`. Holds the OS because normalizing depends on it.
#[derive(Debug, Clone)]
pub struct DesktopSocksSettingsStore {
    file: PathBuf,
    os: DesktopOs,
}

impl DesktopSocksSettingsStore {
    /// `<app_data_dir>/desktop_socks_proxy_settings.json`.
    pub fn new(app_data_dir: &Path, os: DesktopOs) -> Self {
        Self::with_file(app_data_dir.join(SOCKS_SETTINGS_FILE), os)
    }

    pub fn with_file(file: PathBuf, os: DesktopOs) -> Self {
        Self { file, os }
    }

    pub fn file(&self) -> &Path {
        &self.file
    }

    pub fn load(&self) -> DesktopSocksProxySettings {
        match read_json::<DesktopSocksProxySettings>(&self.file) {
            Some(Some(v)) => v.normalized(self.os),
            _ => DesktopSocksProxySettings::default(),
        }
    }

    pub fn save(&self, settings: &DesktopSocksProxySettings) -> io::Result<()> {
        write_json(&self.file, &settings.normalized(self.os))
    }
}

/// `JvmUpdateSettingsStore`.
#[derive(Debug, Clone)]
pub struct FileAppUpdateSettingsStore {
    file: PathBuf,
}

impl FileAppUpdateSettingsStore {
    /// `<app_data_dir>/update_settings.json`.
    pub fn new(app_data_dir: &Path) -> Self {
        Self::with_file(app_data_dir.join(UPDATE_SETTINGS_FILE))
    }

    pub fn with_file(file: PathBuf) -> Self {
        Self { file }
    }

    pub fn file(&self) -> &Path {
        &self.file
    }

    pub fn try_save(&self, settings: &AppUpdateSettings) -> io::Result<()> {
        write_json(&self.file, &settings.normalized())
    }
}

impl AppUpdateSettingsStore for FileAppUpdateSettingsStore {
    fn load(&self) -> AppUpdateSettings {
        match read_json::<AppUpdateSettings>(&self.file) {
            Some(Some(v)) => v.normalized(),
            _ => AppUpdateSettings::default(),
        }
    }

    fn save(&self, settings: &AppUpdateSettings) {
        let _ = self.try_save(settings);
    }
}
