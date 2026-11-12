//! Platform-neutral desktop profile, subscription, and settings operations.

use std::path::Path;

use serde::Deserialize;
use url::Url;

use crate::desktop_api::{LocationEditorData, SettingsSnapshot, TextPayload};
use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::desktop_stores::{DesktopSocksSettingsStore, FileAppUpdateSettingsStore};
use crate::desktop_vpn_runtime::first_public_exit_ip;
use crate::home;
use crate::location::LocationConfig;
use crate::repository::{HttpFetcher, HttpRequest, Repository};
use crate::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use crate::settings::AppBehaviorSettings;
use crate::update::AppUpdateSettings;
use crate::vpn_settings::{SplitTunnelState, VpnSettings};

#[derive(Debug, Clone, PartialEq)]
pub enum SettingsEffect {
    None,
    AppBehavior(AppBehaviorSettings),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct SubscriptionEditRequest {
    url: String,
    custom_name: String,
    cascade_link: String,
    update_interval_hours: i32,
    auto_update_enabled: bool,
}

impl Default for SubscriptionEditRequest {
    fn default() -> Self {
        Self {
            url: String::new(),
            custom_name: String::new(),
            cascade_link: String::new(),
            update_interval_hours: crate::location::DEFAULT_UPDATE_INTERVAL_HOURS,
            auto_update_enabled: true,
        }
    }
}

#[derive(Clone, Copy)]
pub struct DesktopData<'a> {
    app_data_dir: &'a Path,
    repository: &'a Repository,
    os: DesktopOs,
    connected: bool,
}

impl<'a> DesktopData<'a> {
    pub fn new(
        app_data_dir: &'a Path,
        repository: &'a Repository,
        os: DesktopOs,
        connected: bool,
    ) -> Self {
        Self {
            app_data_dir,
            repository,
            os,
            connected,
        }
    }

    pub fn import(&self, text: &str) -> Result<Vec<String>, String> {
        let text = text.trim();
        if text.is_empty() {
            return Err("Вставленный текст пуст".into());
        }

        let vpn = VpnSettings::new(self.app_data_dir);
        let routing = vpn.load_routing_profiles();
        if let Some(imported) = crate::desktop_settings::import_routing_profile_link(&routing, text)
        {
            if !vpn.save_routing_profiles(&imported) {
                return Err("Не удалось сохранить профиль маршрутизации".into());
            }
            return Ok(vec!["Routing profile imported".into()]);
        }

        let mut messages = Vec::new();
        match home::plan_import(text) {
            home::ImportPlan::Empty => return Err("Вставленный текст пуст".into()),
            home::ImportPlan::RoutingProfile(_) => {
                return Err("Профиль маршрутизации повреждён".into())
            }
            home::ImportPlan::Single(value) => {
                if !self.repository.import_text(&value, None) {
                    return Err(home::import_failure_reason(&value).into());
                }
            }
            home::ImportPlan::Batch(values) => {
                let mut added = 0;
                let mut failed = 0;
                for value in values {
                    if self.repository.import_text(&value, None) {
                        added += 1;
                    } else {
                        failed += 1;
                    }
                }
                if let Some(note) = home::import_many_outcome(added, failed)? {
                    messages.push(note);
                }
            }
        }
        messages.push("Configuration imported".into());
        Ok(messages)
    }

    pub fn get_profile(&self, id: &str) -> Result<LocationEditorData, String> {
        if id.trim().is_empty() {
            return Ok(LocationEditorData {
                storage_id: String::new(),
                location: LocationConfig::default(),
                is_new: true,
                is_subscription: false,
            });
        }
        let bundle = self.repository.bundle();
        let entry = bundle
            .locations
            .iter()
            .find(|entry| entry.storage_id == id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        Ok(LocationEditorData {
            storage_id: entry.storage_id.clone(),
            location: entry.location(),
            is_new: false,
            is_subscription: entry
                .subscription_url
                .as_deref()
                .is_some_and(|value| !value.trim().is_empty()),
        })
    }

    pub fn save_profile(&self, id: &str, text: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем измените конфигурацию")?;
        let location: LocationConfig = serde_json::from_str(text)
            .map_err(|error| format!("Некорректный JSON конфигурации: {error}"))?;
        if location.display_name().trim().is_empty() {
            return Err("Укажите название конфигурации или идентификатор комнаты".into());
        }
        self.repository.save_location(id, &location.normalized());
        Ok(())
    }

    pub fn duplicate_profile(&self, id: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN")?;
        let mut location = self
            .repository
            .load_location(id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        location.name = format!("{} — копия", location.display_name());
        self.repository.save_location("", &location);
        Ok(())
    }

    pub fn set_pinned(&self, id: &str, text: &str) -> Result<(), String> {
        let pinned = text
            .trim()
            .parse::<bool>()
            .map_err(|_| "Некорректное значение закрепления".to_string())?;
        if self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.set_location_pinned(id, pinned);
        Ok(())
    }

    pub fn set_vk_link(&self, id: &str, text: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN")?;
        if self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.set_location_vk_link(id, text);
        Ok(())
    }

    pub fn export_profile(&self, id: &str) -> Result<TextPayload, String> {
        let location = self
            .repository
            .load_location(id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        Ok(TextPayload {
            text: crate::panel::compose_inbound(&location),
        })
    }

    pub fn select(&self, id: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем смените конфигурацию")?;
        if id.trim().is_empty() || self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.set_active_location_id(Some(id));
        Ok(())
    }

    pub fn delete(&self, id: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN")?;
        if id.trim().is_empty() || self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.delete_location(id);
        Ok(())
    }

    pub fn settings(&self) -> SettingsSnapshot {
        SettingsSnapshot::load(self.app_data_dir, self.os)
    }

    pub fn check_exit_ip(
        &self,
        fetcher: &dyn HttpFetcher,
        app_version: &str,
        proxy: Option<String>,
    ) -> Result<TextPayload, String> {
        let mut fetch = |url: &str| {
            fetcher
                .get(&HttpRequest {
                    url: url.to_string(),
                    headers: vec![("User-Agent".into(), format!("YPtun/{app_version}"))],
                    proxy: proxy.clone(),
                })
                .filter(|response| response.ok())
                .map(|response| response.body)
        };
        first_public_exit_ip(&mut fetch)
            .map(|text| TextPayload { text })
            .ok_or_else(|| "Не удалось определить внешний IPv4-адрес".into())
    }

    pub fn save_settings(&self, kind: &str, text: &str) -> Result<SettingsEffect, String> {
        self.require_disconnected("Сначала отключите VPN, затем измените параметры")?;
        let vpn = VpnSettings::new(self.app_data_dir);
        let (saved, effect) = match kind {
            "traffic" => (
                vpn.save_traffic(&decode_settings::<TrafficSettings>(text)?),
                SettingsEffect::None,
            ),
            "routing" => (
                vpn.save_routing(&decode_settings::<RoutingRules>(text)?),
                SettingsEffect::None,
            ),
            "routing_profiles" => (
                vpn.save_routing_profiles(&decode_settings::<RoutingProfilesState>(text)?),
                SettingsEffect::None,
            ),
            "split_tunnel" => (
                vpn.save_split_tunnel(&decode_settings::<SplitTunnelState>(text)?),
                SettingsEffect::None,
            ),
            "app_behavior" => {
                let value = decode_settings::<AppBehaviorSettings>(text)?;
                (
                    vpn.save_app_behavior(&value),
                    SettingsEffect::AppBehavior(value),
                )
            }
            "socks" => {
                DesktopSocksSettingsStore::new(self.app_data_dir, self.os)
                    .save(&decode_settings::<DesktopSocksProxySettings>(text)?)
                    .map_err(|error| error.to_string())?;
                (true, SettingsEffect::None)
            }
            "updates" => {
                FileAppUpdateSettingsStore::new(self.app_data_dir)
                    .try_save(&decode_settings::<AppUpdateSettings>(text)?)
                    .map_err(|error| error.to_string())?;
                (true, SettingsEffect::None)
            }
            _ => return Err(format!("Неизвестная группа параметров: {kind}")),
        };
        if !saved {
            return Err("Не удалось сохранить параметры".into());
        }
        Ok(effect)
    }

    pub fn save_subscription(&self, old_url: &str, text: &str) -> Result<String, String> {
        let old_url = old_url.trim();
        if !self.has_subscription(old_url) {
            return Err("Подписка не найдена".into());
        }
        let edit: SubscriptionEditRequest = serde_json::from_str(text)
            .map_err(|error| format!("Некорректные параметры подписки: {error}"))?;
        let new_url = if edit.url.trim().is_empty() {
            old_url
        } else {
            edit.url.trim()
        };
        let parsed_url = Url::parse(new_url)
            .map_err(|_| "Укажите корректный HTTP(S)-адрес подписки".to_string())?;
        if !matches!(parsed_url.scheme(), "http" | "https")
            || new_url.chars().any(char::is_whitespace)
        {
            return Err("Укажите корректный HTTP(S)-адрес подписки".into());
        }

        let cascade = edit.cascade_link.trim();
        if !cascade.is_empty()
            && crate::share_link::parse(cascade)
                .filter(|profile| profile.is_complete())
                .is_none()
        {
            return Err("Каскадная конфигурация не распознана".into());
        }

        if new_url != old_url && !self.repository.change_subscription_url(old_url, new_url) {
            return Err("Не удалось изменить адрес: возможно, такая подписка уже добавлена".into());
        }
        self.repository
            .set_subscription_custom_name(new_url, edit.custom_name.trim());
        self.repository
            .set_subscription_update_interval(new_url, edit.update_interval_hours);
        self.repository
            .set_subscription_auto_update(new_url, edit.auto_update_enabled);
        if !self.repository.set_subscription_cascade(new_url, cascade) {
            return Err("Не удалось сохранить каскадную конфигурацию".into());
        }
        Ok(new_url.to_string())
    }

    pub fn delete_subscription(&self, url: &str) -> Result<Vec<String>, String> {
        self.require_disconnected("Сначала отключите VPN, затем удалите подписку")?;
        let url = url.trim();
        let ids: Vec<String> = self
            .repository
            .bundle()
            .locations
            .iter()
            .filter(|entry| entry.subscription_url.as_deref().map(str::trim) == Some(url))
            .map(|entry| entry.storage_id.clone())
            .collect();
        if ids.is_empty() {
            return Err("Подписка не найдена".into());
        }
        self.repository.delete_locations(&ids);
        Ok(ids)
    }

    pub fn refresh_subscription(&self, url: &str, proxy: Option<&str>) -> Result<i32, String> {
        let url = url.trim();
        if !self.has_subscription(url) {
            return Err("Подписка не найдена".into());
        }
        Ok(self.repository.refresh_subscription(url, proxy))
    }

    pub fn refresh_subscriptions(&self, proxy: Option<&str>) -> i32 {
        self.repository.refresh_subscriptions(None, proxy)
    }

    fn has_subscription(&self, url: &str) -> bool {
        !url.is_empty()
            && self
                .repository
                .bundle()
                .locations
                .iter()
                .any(|entry| entry.subscription_url.as_deref().map(str::trim) == Some(url))
    }

    fn require_disconnected(&self, message: &str) -> Result<(), String> {
        if self.connected {
            Err(message.to_string())
        } else {
            Ok(())
        }
    }
}

fn decode_settings<T: serde::de::DeserializeOwned>(text: &str) -> Result<T, String> {
    serde_json::from_str(text).map_err(|error| format!("Некорректный JSON параметров: {error}"))
}
