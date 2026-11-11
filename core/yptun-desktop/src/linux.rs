#[cfg(test)]
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_settings::{DesktopSettings, CONNECTION_MODE_PROXY, CONNECTION_MODE_TUN};
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_stores::{DesktopSocksSettingsStore, FileAppUpdateSettingsStore};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::home;
use yptun_core::location::{
    LocationConfig, LocationEntry, LocationMetadata, DEFAULT_UPDATE_INTERVAL_HOURS,
};
use yptun_core::model::EngineType;
use yptun_core::repository::{ClientInfo, HttpFetcher, Repository};
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::update::{AppUpdateSettings, AppUpdateSettingsStore};
use yptun_core::vpn_settings::{SplitTunnelState, VpnSettings};

use crate::store::{device_identity, FileStore};

const RUNTIME_UNAVAILABLE: &str = "Linux VPN runtime is not available in this build";

#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct CommandRequest {
    command: String,
    text: String,
    id: String,
    mode: String,
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
struct ProfileItem {
    id: String,
    name: String,
    subtitle: String,
    engine: String,
    is_complete: bool,
    is_subscription: bool,
    subscription_name: Option<String>,
    ping_ms: Option<i32>,
    ping_tested: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SubscriptionItem {
    url: String,
    name: String,
    custom_name: String,
    cascade_link: String,
    update_interval_hours: i32,
    auto_update_enabled: bool,
    location_count: usize,
    expires_at_epoch_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeDiagnostics {
    active_engine: String,
    connection_mode: String,
    engine_alive: bool,
    connected_since_epoch_ms: u64,
    in_core_tun: bool,
    external_tun: bool,
    bypass_route_count: usize,
    watchdog_probe_failures: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CoreSnapshot {
    status: String,
    status_label: String,
    status_detail: String,
    is_connected: bool,
    runtime_available: bool,
    connection_mode: String,
    active_profile_id: Option<String>,
    profiles: Vec<ProfileItem>,
    subscriptions: Vec<SubscriptionItem>,
    session: RuntimeDiagnostics,
    telegram_proxy_status: String,
    telegram_proxy_detail: String,
    logs: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LocationEditorData {
    storage_id: String,
    location: LocationConfig,
    is_new: bool,
    is_subscription: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SettingsSnapshot {
    traffic: TrafficSettings,
    routing: RoutingRules,
    routing_profiles: RoutingProfilesState,
    split_tunnel: SplitTunnelState,
    app_behavior: AppBehaviorSettings,
    socks: DesktopSocksProxySettings,
    updates: AppUpdateSettings,
}

#[derive(Debug, Clone, Serialize)]
struct TextPayload {
    text: String,
}

#[derive(Serialize)]
struct Response<T: Serialize> {
    ok: bool,
    data: Option<T>,
    error: Option<String>,
}

impl<T: Serialize> Response<T> {
    fn success(data: T) -> Self {
        Self {
            ok: true,
            data: Some(data),
            error: None,
        }
    }

    fn error(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            data: None,
            error: Some(message.into()),
        }
    }
}

pub struct LinuxApp {
    app_data_dir: PathBuf,
    repository: Repository,
    settings: Mutex<DesktopSettings>,
    journal: Arc<LogJournal>,
}

impl LinuxApp {
    #[cfg(any(target_os = "linux", test))]
    pub fn new(app_data_dir: PathBuf, locale: &str, app_version: &str) -> Self {
        Self::with_fetcher(
            app_data_dir,
            locale,
            app_version,
            Box::new(crate::http::ReqwestFetcher::new()),
        )
    }

    fn with_fetcher(
        app_data_dir: PathBuf,
        locale: &str,
        app_version: &str,
        fetcher: Box<dyn HttpFetcher>,
    ) -> Self {
        let _ = std::fs::create_dir_all(&app_data_dir);
        let identity = device_identity(&app_data_dir);
        let repository = Repository::new(
            Box::new(FileStore::new(app_data_dir.clone())),
            fetcher,
            ClientInfo {
                user_agent: format!("YPtun/{app_version}"),
                app_version: app_version.to_string(),
                hwid: identity,
                app_id: "org.yptun.app".into(),
                device_os: "Linux".into(),
                os_version: linux_version(),
                device_model: std::env::var("HOSTNAME").unwrap_or_else(|_| "Linux PC".into()),
            },
        );
        let app = Self {
            repository,
            settings: Mutex::new(DesktopSettings::open(&app_data_dir, locale, false, false)),
            journal: Arc::new(LogJournal::new()),
            app_data_dir,
        };
        app.log("Linux data backend initialized");
        app
    }

    pub fn command_json(&self, request_json: &str) -> String {
        let request: CommandRequest = match serde_json::from_str(request_json) {
            Ok(value) => value,
            Err(error) => {
                return json(&Response::<CoreSnapshot>::error(format!(
                    "invalid request: {error}"
                )))
            }
        };

        match request.command.as_str() {
            "snapshot" => json(&Response::success(self.snapshot())),
            "import" => self.snapshot_result(self.import(&request.text)),
            "select" => self.snapshot_result(self.select(&request.id)),
            "delete" => self.snapshot_result(self.delete(&request.id)),
            "refresh_subscriptions" => self.snapshot_result(self.refresh_subscriptions()),
            "refresh_subscription" => self.snapshot_result(self.refresh_subscription(&request.id)),
            "set_mode" => self.snapshot_result(self.set_mode(&request.mode)),
            "start" => {
                let result = if request.mode.is_empty() {
                    Err(RUNTIME_UNAVAILABLE.to_string())
                } else {
                    self.set_mode(&request.mode)
                        .and_then(|_| Err(RUNTIME_UNAVAILABLE.to_string()))
                };
                self.snapshot_result(result)
            }
            "stop" | "ping" | "find_free_servers" | "exit_ip" => {
                self.result_json::<CoreSnapshot>(Err(RUNTIME_UNAVAILABLE.into()))
            }
            "settings" => self.result_json::<SettingsSnapshot>(Ok(self.settings_snapshot())),
            "save_settings" => self.result_json(
                self.save_settings(&request.id, &request.text)
                    .map(|_| self.settings_snapshot()),
            ),
            "get_profile" => self.result_json(self.get_profile(&request.id)),
            "save_profile" => self.result_json(
                self.save_profile(&request.id, &request.text)
                    .map(|_| self.snapshot()),
            ),
            "duplicate_profile" => {
                self.result_json(self.duplicate_profile(&request.id).map(|_| self.snapshot()))
            }
            "set_pinned" => self.result_json(
                self.set_pinned(&request.id, &request.text)
                    .map(|_| self.snapshot()),
            ),
            "set_vk_link" => self.result_json(
                self.set_vk_link(&request.id, &request.text)
                    .map(|_| self.snapshot()),
            ),
            "export_profile" => self.result_json(self.export_profile(&request.id)),
            "export_bundle" => self.result_json::<TextPayload>(Ok(TextPayload {
                text: self.repository.export_bundle(),
            })),
            "clear_logs" => {
                self.journal.clear();
                json(&Response::success(self.snapshot()))
            }
            other => self.result_json::<CoreSnapshot>(Err(format!("unknown command: {other}"))),
        }
    }

    fn snapshot_result(&self, result: Result<(), String>) -> String {
        self.result_json(result.map(|_| self.snapshot()))
    }

    fn result_json<T: Serialize>(&self, result: Result<T, String>) -> String {
        match result {
            Ok(data) => json(&Response::success(data)),
            Err(error) => {
                self.log(&format!("ERROR: {error}"));
                json(&Response::<T>::error(error))
            }
        }
    }

    fn import(&self, text: &str) -> Result<(), String> {
        let text = text.trim();
        if text.is_empty() {
            return Err("Вставленный текст пуст".into());
        }

        let vpn = VpnSettings::new(&self.app_data_dir);
        let routing = vpn.load_routing_profiles();
        if let Some(imported) =
            yptun_core::desktop_settings::import_routing_profile_link(&routing, text)
        {
            if !vpn.save_routing_profiles(&imported) {
                return Err("Не удалось сохранить профиль маршрутизации".into());
            }
            self.log("Routing profile imported");
            return Ok(());
        }

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
                    self.log(&note);
                }
            }
        }
        self.log("Configuration imported");
        Ok(())
    }

    fn select(&self, id: &str) -> Result<(), String> {
        if id.trim().is_empty() || self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.set_active_location_id(Some(id));
        self.log(&format!("Selected location {id}"));
        Ok(())
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        if id.trim().is_empty() || self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.delete_location(id);
        self.log(&format!("Deleted location {id}"));
        Ok(())
    }

    fn refresh_subscriptions(&self) -> Result<(), String> {
        let count = self.repository.refresh_subscriptions(None, None);
        self.log(&format!("Refreshed {count} subscription(s)"));
        Ok(())
    }

    fn refresh_subscription(&self, url: &str) -> Result<(), String> {
        let url = url.trim();
        if url.is_empty()
            || !self
                .repository
                .bundle()
                .locations
                .iter()
                .any(|entry| entry.subscription_url.as_deref().map(str::trim) == Some(url))
        {
            return Err("Подписка не найдена".into());
        }
        let count = self.repository.refresh_subscription(url, None);
        self.log(&format!("Refreshed subscription {url}: {count}"));
        Ok(())
    }

    fn set_mode(&self, mode: &str) -> Result<(), String> {
        let mode = match mode {
            CONNECTION_MODE_PROXY => CONNECTION_MODE_PROXY,
            CONNECTION_MODE_TUN | "" => CONNECTION_MODE_TUN,
            _ => return Err("Неизвестный режим подключения".into()),
        };
        self.settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .select_connection_mode(mode);
        self.log(&format!("Connection mode: {mode}"));
        Ok(())
    }

    fn get_profile(&self, id: &str) -> Result<LocationEditorData, String> {
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

    fn save_profile(&self, id: &str, text: &str) -> Result<(), String> {
        let location: LocationConfig = serde_json::from_str(text)
            .map_err(|error| format!("Некорректный JSON конфигурации: {error}"))?;
        if location.display_name().trim().is_empty() {
            return Err("Укажите название конфигурации или идентификатор комнаты".into());
        }
        self.repository.save_location(id, &location.normalized());
        self.log(if id.trim().is_empty() {
            "Created location"
        } else {
            "Updated location"
        });
        Ok(())
    }

    fn duplicate_profile(&self, id: &str) -> Result<(), String> {
        let mut location = self
            .repository
            .load_location(id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        location.name = format!("{} — копия", location.display_name());
        self.repository.save_location("", &location);
        self.log(&format!("Duplicated location {id}"));
        Ok(())
    }

    fn set_pinned(&self, id: &str, text: &str) -> Result<(), String> {
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

    fn set_vk_link(&self, id: &str, text: &str) -> Result<(), String> {
        if self.repository.load_location(id).is_none() {
            return Err("Конфигурация не найдена".into());
        }
        self.repository.set_location_vk_link(id, text);
        Ok(())
    }

    fn export_profile(&self, id: &str) -> Result<TextPayload, String> {
        let location = self
            .repository
            .load_location(id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        Ok(TextPayload {
            text: yptun_core::panel::compose_inbound(&location),
        })
    }

    fn settings_snapshot(&self) -> SettingsSnapshot {
        let vpn = VpnSettings::new(&self.app_data_dir);
        SettingsSnapshot {
            traffic: vpn.load_traffic(),
            routing: vpn.load_routing(),
            routing_profiles: vpn.load_routing_profiles(),
            split_tunnel: vpn.load_split_tunnel(),
            app_behavior: vpn.load_app_behavior(),
            socks: DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Linux).load(),
            updates: FileAppUpdateSettingsStore::new(&self.app_data_dir).load(),
        }
    }

    fn save_settings(&self, kind: &str, text: &str) -> Result<(), String> {
        let vpn = VpnSettings::new(&self.app_data_dir);
        let saved = match kind {
            "traffic" => vpn.save_traffic(&decode_settings::<TrafficSettings>(text)?),
            "routing" => vpn.save_routing(&decode_settings::<RoutingRules>(text)?),
            "routing_profiles" => {
                vpn.save_routing_profiles(&decode_settings::<RoutingProfilesState>(text)?)
            }
            "split_tunnel" => vpn.save_split_tunnel(&decode_settings::<SplitTunnelState>(text)?),
            "app_behavior" => vpn.save_app_behavior(&decode_settings::<AppBehaviorSettings>(text)?),
            "socks" => {
                DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Linux)
                    .save(&decode_settings::<DesktopSocksProxySettings>(text)?)
                    .map_err(|error| error.to_string())?;
                true
            }
            "updates" => {
                FileAppUpdateSettingsStore::new(&self.app_data_dir)
                    .try_save(&decode_settings::<AppUpdateSettings>(text)?)
                    .map_err(|error| error.to_string())?;
                true
            }
            _ => return Err(format!("Неизвестная группа параметров: {kind}")),
        };
        if !saved {
            return Err("Не удалось сохранить параметры".into());
        }
        self.log(&format!("Saved settings: {kind}"));
        Ok(())
    }

    fn snapshot(&self) -> CoreSnapshot {
        let bundle = self.repository.bundle();
        let mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            .to_string();
        let active_name = bundle.active_location_id.as_ref().and_then(|id| {
            bundle
                .locations
                .iter()
                .find(|entry| &entry.storage_id == id)
                .map(|entry| entry.location().display_name())
        });
        let detail = active_name
            .map(|name| format!("{RUNTIME_UNAVAILABLE}; selected: {name}"))
            .unwrap_or_else(|| RUNTIME_UNAVAILABLE.to_string());

        CoreSnapshot {
            status: "disconnected".into(),
            status_label: "Отключено".into(),
            status_detail: detail,
            is_connected: false,
            runtime_available: false,
            connection_mode: mode.clone(),
            active_profile_id: bundle.active_location_id.clone(),
            profiles: bundle.locations.iter().map(profile_item).collect(),
            subscriptions: yptun_core::importer::subscription_groups(&bundle)
                .into_iter()
                .map(|(url, entries)| subscription_item(url, &entries))
                .collect(),
            session: RuntimeDiagnostics {
                active_engine: String::new(),
                connection_mode: mode,
                engine_alive: false,
                connected_since_epoch_ms: 0,
                in_core_tun: false,
                external_tun: false,
                bypass_route_count: 0,
                watchdog_probe_failures: 0,
            },
            telegram_proxy_status: "unavailable".into(),
            telegram_proxy_detail: RUNTIME_UNAVAILABLE.into(),
            logs: self.journal.snapshot(),
        }
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
    }
}

fn profile_item(entry: &LocationEntry) -> ProfileItem {
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
        ping_ms: None,
        ping_tested: false,
    }
}

fn subscription_name(metadata: &LocationMetadata) -> Option<String> {
    metadata
        .subscription
        .as_ref()
        .and_then(|subscription| subscription.name.clone())
        .filter(|name| !name.trim().is_empty())
}

fn subscription_item(url: String, entries: &[LocationEntry]) -> SubscriptionItem {
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

fn linux_version() -> String {
    std::fs::read_to_string("/etc/os-release")
        .ok()
        .and_then(|text| {
            text.lines().find_map(|line| {
                line.strip_prefix("PRETTY_NAME=")
                    .map(|value| value.trim_matches('"').to_string())
            })
        })
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "Linux".into())
}

fn json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|error| {
        format!(
            "{{\"ok\":false,\"data\":null,\"error\":\"serialization failed: {}\"}}",
            error
        )
    })
}

fn decode_settings<T: serde::de::DeserializeOwned>(text: &str) -> Result<T, String> {
    serde_json::from_str(text).map_err(|error| format!("Некорректный JSON параметров: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use yptun_core::repository::{HttpRequest, HttpResponse};
    use yptun_core::routing::RoutingProfile;

    #[derive(Clone, Default)]
    struct FakeFetcher {
        responses: Arc<Mutex<HashMap<String, String>>>,
    }

    impl FakeFetcher {
        fn set(&self, url: &str, body: &str) {
            self.responses
                .lock()
                .unwrap()
                .insert(url.to_string(), body.to_string());
        }
    }

    impl HttpFetcher for FakeFetcher {
        fn get(&self, request: &HttpRequest) -> Option<HttpResponse> {
            self.responses
                .lock()
                .unwrap()
                .get(&request.url)
                .cloned()
                .map(|body| HttpResponse {
                    status: 200,
                    headers: Vec::new(),
                    body,
                })
        }
    }

    fn make_app(root: &Path, fetcher: FakeFetcher) -> LinuxApp {
        LinuxApp::with_fetcher(root.to_path_buf(), "en", "4.0.0", Box::new(fetcher))
    }

    fn command(app: &LinuxApp, request: serde_json::Value) -> serde_json::Value {
        serde_json::from_str(&app.command_json(&request.to_string())).unwrap()
    }

    #[test]
    fn imports_selects_deletes_and_reloads_profiles() {
        let temp = tempfile::tempdir().unwrap();
        let first = "vless://11111111-1111-1111-1111-111111111111@one.example:443?security=tls#One";
        let second =
            "vless://22222222-2222-2222-2222-222222222222@two.example:443?security=tls#Two";
        let app = make_app(temp.path(), FakeFetcher::default());

        assert_eq!(
            command(
                &app,
                serde_json::json!({"command": "import", "text": first})
            )["ok"],
            true
        );
        let imported = command(
            &app,
            serde_json::json!({"command": "import", "text": second}),
        );
        let profiles = imported["data"]["profiles"].as_array().unwrap();
        assert_eq!(profiles.len(), 2);
        let one_id = profiles
            .iter()
            .find(|profile| profile["name"] == "One")
            .unwrap()["id"]
            .as_str()
            .unwrap()
            .to_string();
        let two_id = profiles
            .iter()
            .find(|profile| profile["name"] == "Two")
            .unwrap()["id"]
            .as_str()
            .unwrap()
            .to_string();
        assert_eq!(
            command(&app, serde_json::json!({"command": "select", "id": two_id}))["ok"],
            true
        );
        drop(app);

        assert!(temp.path().join(crate::store::BUNDLE_FILE).is_file());
        let reloaded = make_app(temp.path(), FakeFetcher::default());
        let snapshot = command(&reloaded, serde_json::json!({"command": "snapshot"}));
        assert_eq!(snapshot["data"]["activeProfileId"], two_id);
        assert_eq!(snapshot["data"]["profiles"].as_array().unwrap().len(), 2);

        let deleted = command(
            &reloaded,
            serde_json::json!({"command": "delete", "id": two_id}),
        );
        assert_eq!(deleted["ok"], true);
        assert_eq!(deleted["data"]["activeProfileId"], one_id);
        assert_eq!(deleted["data"]["profiles"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn persists_connection_mode_and_routing_import() {
        let temp = tempfile::tempdir().unwrap();
        let app = make_app(temp.path(), FakeFetcher::default());
        let mode = command(
            &app,
            serde_json::json!({"command": "set_mode", "mode": "proxy"}),
        );
        assert_eq!(mode["data"]["connectionMode"], "proxy");

        let profile = RoutingProfile {
            name: "Direct example".into(),
            direct_sites: vec!["example.com".into()],
            ..RoutingProfile::default()
        };
        let imported = command(
            &app,
            serde_json::json!({
                "command": "import",
                "text": serde_json::to_string(&profile).unwrap(),
            }),
        );
        assert_eq!(imported["ok"], true);
        drop(app);

        let reloaded = make_app(temp.path(), FakeFetcher::default());
        let snapshot = command(&reloaded, serde_json::json!({"command": "snapshot"}));
        assert_eq!(snapshot["data"]["connectionMode"], "proxy");
        let settings = command(&reloaded, serde_json::json!({"command": "settings"}));
        assert_eq!(
            settings["data"]["routingProfiles"]["profiles"][0]["name"],
            "Direct example"
        );
    }

    #[test]
    fn imports_and_refreshes_subscription_through_shared_repository() {
        let temp = tempfile::tempdir().unwrap();
        let fetcher = FakeFetcher::default();
        let url = "https://subscription.example.test/token";
        fetcher.set(
            url,
            "vless://11111111-1111-1111-1111-111111111111@one.example:443?security=tls#Before",
        );
        let app = make_app(temp.path(), fetcher.clone());
        let imported = command(&app, serde_json::json!({"command": "import", "text": url}));
        assert_eq!(imported["ok"], true);
        assert_eq!(imported["data"]["profiles"][0]["name"], "Before");
        assert_eq!(imported["data"]["subscriptions"][0]["url"], url);

        fetcher.set(
            url,
            "vless://22222222-2222-2222-2222-222222222222@two.example:443?security=tls#After",
        );
        let refreshed = command(
            &app,
            serde_json::json!({"command": "refresh_subscriptions"}),
        );
        assert_eq!(refreshed["ok"], true);
        assert_eq!(refreshed["data"]["profiles"][0]["name"], "After");
    }

    #[test]
    fn runtime_commands_fail_explicitly() {
        let temp = tempfile::tempdir().unwrap();
        let app = LinuxApp::new(temp.path().to_path_buf(), "en", "4.0.0");
        let snapshot = command(&app, serde_json::json!({"command": "snapshot"}));
        assert_eq!(snapshot["data"]["runtimeAvailable"], false);

        for runtime_command in ["start", "stop"] {
            let response = command(
                &app,
                serde_json::json!({"command": runtime_command, "mode": "tun"}),
            );
            assert_eq!(response["ok"], false);
            assert!(response["error"]
                .as_str()
                .unwrap()
                .contains("not available"));
        }
    }
}
