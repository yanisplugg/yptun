use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;

use serde::Serialize;
use yptun_core::desktop_api::{
    profile_item, subscription_item, CommandRequest, CoreSnapshot, LocationEditorData, Response,
    SettingsSnapshot, TextPayload, UpdateCheckPayload,
};
use yptun_core::desktop_data::{DesktopData, SettingsEffect};
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_settings::{DesktopSettings, CONNECTION_MODE_PROXY, CONNECTION_MODE_TUN};
use yptun_core::desktop_store::{device_identity, DesktopFileStore};
use yptun_core::desktop_stores::DesktopSocksSettingsStore;
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::home;
use yptun_core::repository::{ClientInfo, HttpFetcher, HttpRequest, Repository};
use yptun_core::update::{AppUpdateService, ReleaseChannel};
use yptun_core::vpn_settings::VpnSettings;

use crate::http::ReqwestFetcher;
use crate::logging;
use crate::runtime::WindowsRuntime;

#[derive(Debug, Clone)]
enum RuntimeStatus {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Stopping,
    Error(String),
}

impl RuntimeStatus {
    fn wire_name(&self) -> &'static str {
        match self {
            Self::Disconnected => "disconnected",
            Self::Connecting => "connecting",
            Self::Reconnecting => "reconnecting",
            Self::Connected => "connected",
            Self::Stopping => "stopping",
            Self::Error(_) => "error",
        }
    }

    fn label(&self) -> &'static str {
        match self {
            Self::Disconnected => "Отключено",
            Self::Connecting => "Подключение…",
            Self::Reconnecting => "Переподключение…",
            Self::Connected => "Подключено",
            Self::Stopping => "Отключение…",
            Self::Error(_) => "Ошибка",
        }
    }

    fn detail(&self, active_name: Option<&str>) -> String {
        match self {
            Self::Disconnected => active_name
                .map(|name| format!("Готово к подключению: {name}"))
                .unwrap_or_else(|| "Добавьте и выберите конфигурацию".into()),
            Self::Connecting => active_name
                .map(|name| format!("Запуск {name}"))
                .unwrap_or_else(|| "Запуск VPN".into()),
            Self::Reconnecting => active_name
                .map(|name| format!("Восстановление {name}"))
                .unwrap_or_else(|| "Восстановление VPN".into()),
            Self::Connected => active_name
                .map(|name| format!("Трафик идёт через {name}"))
                .unwrap_or_else(|| "VPN активен".into()),
            Self::Stopping => "Останавливаем VPN-движки".into(),
            Self::Error(message) => message.clone(),
        }
    }

    fn is_connected(&self) -> bool {
        matches!(self, Self::Connected)
    }
}

pub struct WindowsApp {
    app_data_dir: PathBuf,
    native_dir: PathBuf,
    app_version: String,
    repository: Repository,
    settings: Mutex<DesktopSettings>,
    journal: Arc<LogJournal>,
    status: RwLock<RuntimeStatus>,
    runtime: Mutex<WindowsRuntime>,
    ping_results: Mutex<BTreeMap<String, Option<i32>>>,
}

impl WindowsApp {
    pub fn new(
        app_data_dir: PathBuf,
        native_dir: PathBuf,
        locale: &str,
        app_version: &str,
    ) -> Self {
        let _ = std::fs::create_dir_all(&app_data_dir);
        let identity = device_identity(&app_data_dir);
        let store = DesktopFileStore::new(app_data_dir.clone());
        let client = ClientInfo {
            user_agent: format!("YPtun/{app_version}"),
            app_version: app_version.to_string(),
            hwid: identity,
            app_id: "org.yptun.app".into(),
            device_os: "Windows".into(),
            os_version: windows_version(),
            device_model: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows PC".into()),
        };
        let repository = Repository::new(Box::new(store), Box::new(ReqwestFetcher::new()), client);
        let settings = DesktopSettings::open(&app_data_dir, locale, false, is_elevated());
        let journal = Arc::new(LogJournal::new());
        let runtime =
            WindowsRuntime::new(app_data_dir.clone(), native_dir.clone(), journal.clone());
        let behavior = VpnSettings::new(&app_data_dir).load_app_behavior();
        let ping_results = if behavior.save_ping_results {
            behavior.last_ping_results
        } else {
            BTreeMap::new()
        };
        let app = Self {
            app_data_dir,
            native_dir,
            app_version: app_version.to_string(),
            repository,
            settings: Mutex::new(settings),
            journal,
            status: RwLock::new(RuntimeStatus::Disconnected),
            runtime: Mutex::new(runtime),
            ping_results: Mutex::new(ping_results),
        };
        app.log("WinUI 3 native host initialized");
        app
    }

    pub fn app_data_dir(&self) -> &Path {
        &self.app_data_dir
    }

    pub fn native_dir(&self) -> &Path {
        &self.native_dir
    }

    fn data(&self) -> DesktopData<'_> {
        let connected = self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected();
        DesktopData::new(
            &self.app_data_dir,
            &self.repository,
            DesktopOs::Windows,
            connected,
        )
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
            "settings" => self.result_json::<SettingsSnapshot>(Ok(self.settings_snapshot())),
            "save_settings" => self.result_json(
                self.save_settings(&request.id, &request.text)
                    .map(|_| self.settings_snapshot()),
            ),
            "export_profile" => self.result_json(self.export_profile(&request.id)),
            "export_bundle" => self.result_json::<TextPayload>(Ok(TextPayload {
                text: self.repository.export_bundle(),
            })),
            "check_update" => self.result_json(self.check_update()),
            "exit_ip" => self.result_json(self.check_exit_ip()),
            "ping" => self.result_json(self.ping(&request.id).map(|_| self.snapshot())),
            "find_free_servers" => {
                self.result_json(self.find_free_servers().map(|_| self.snapshot()))
            }
            "save_subscription" => self.result_json(
                self.save_subscription(&request.id, &request.text)
                    .map(|_| self.snapshot()),
            ),
            "delete_subscription" => self.result_json(
                self.delete_subscription(&request.id)
                    .map(|_| self.snapshot()),
            ),
            "refresh_subscription" => self.result_json(
                self.refresh_subscription(&request.id)
                    .map(|_| self.snapshot()),
            ),
            "clear_logs" => {
                self.journal.clear();
                self.result_json::<CoreSnapshot>(Ok(self.snapshot()))
            }
            _ => json(&self.command(request)),
        }
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

    fn command(&self, request: CommandRequest) -> Response<CoreSnapshot> {
        let result = match request.command.as_str() {
            "snapshot" => Ok(()),
            "import" => self.import(&request.text),
            "select" => self.select(&request.id),
            "delete" => self.delete(&request.id),
            "refresh_subscriptions" => self.refresh_subscriptions(),
            "set_mode" => self.set_mode(&request.mode),
            "start" => self.start(&request.mode),
            "stop" => self.stop(),
            other => Err(format!("unknown command: {other}")),
        };

        match result {
            Ok(()) => Response::success(self.snapshot()),
            Err(error) => {
                self.log(&format!("ERROR: {error}"));
                Response::error(error)
            }
        }
    }

    fn import(&self, text: &str) -> Result<(), String> {
        for message in self.data().import(text)? {
            self.log(&message);
        }
        Ok(())
    }

    fn get_profile(&self, id: &str) -> Result<LocationEditorData, String> {
        self.data().get_profile(id)
    }

    fn save_profile(&self, id: &str, text: &str) -> Result<(), String> {
        self.data().save_profile(id, text)?;
        self.log(if id.trim().is_empty() {
            "Created location"
        } else {
            "Updated location"
        });
        Ok(())
    }

    fn duplicate_profile(&self, id: &str) -> Result<(), String> {
        self.data().duplicate_profile(id)?;
        self.log(&format!("Duplicated location {id}"));
        Ok(())
    }

    fn set_pinned(&self, id: &str, text: &str) -> Result<(), String> {
        self.data().set_pinned(id, text)
    }

    fn set_vk_link(&self, id: &str, text: &str) -> Result<(), String> {
        self.data().set_vk_link(id, text)
    }

    fn export_profile(&self, id: &str) -> Result<TextPayload, String> {
        self.data().export_profile(id)
    }

    fn settings_snapshot(&self) -> SettingsSnapshot {
        self.data().settings()
    }

    fn check_update(&self) -> Result<UpdateCheckPayload, String> {
        let portable = std::env::var("YPTUN_PORTABLE_EXE")
            .ok()
            .is_some_and(|value| !value.trim().is_empty());
        let service = AppUpdateService::new(Box::new(ReqwestFetcher::new()), &self.app_version)
            .prefer_portable_asset(portable);
        let info = service
            .check(ReleaseChannel::Stable, None)
            .map_err(|error| error.to_string())?;
        Ok(UpdateCheckPayload {
            version: info.version,
            html_url: info.html_url,
            asset_name: info.asset.name,
            download_url: info.asset.download_url,
            size_bytes: info.asset.size_bytes,
            sha256: info.asset.sha256,
            is_update_available: info.is_update_available,
        })
    }

    fn check_exit_ip(&self) -> Result<TextPayload, String> {
        let connected = self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected();
        let proxy_mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            == CONNECTION_MODE_PROXY;
        let proxy = if connected && proxy_mode {
            self.subscription_proxy()?
        } else {
            None
        };
        self.data()
            .check_exit_ip(&ReqwestFetcher::new(), &self.app_version, proxy)
    }

    fn save_settings(&self, kind: &str, text: &str) -> Result<(), String> {
        if let SettingsEffect::AppBehavior(value) = self.data().save_settings(kind, text)? {
            self.runtime
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .configure_telegram_proxy(value.telegram_proxy_enabled);
        }
        self.log(&format!("Saved settings: {kind}"));
        Ok(())
    }

    fn select(&self, id: &str) -> Result<(), String> {
        self.data().select(id)?;
        self.log(&format!("Selected location {id}"));
        Ok(())
    }

    fn ping(&self, id: &str) -> Result<(), String> {
        let location = self
            .repository
            .load_location(id)
            .ok_or_else(|| "Конфигурация не найдена".to_string())?;
        let active = self.repository.active_location_id().as_deref() == Some(id);
        let result = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .ping(&location, active)
            .and_then(|value| i32::try_from(value).ok());
        self.ping_results
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .insert(id.to_string(), result);
        let vpn = VpnSettings::new(&self.app_data_dir);
        let mut behavior = vpn.load_app_behavior();
        if behavior.save_ping_results {
            behavior.last_ping_results.insert(id.to_string(), result);
            let _ = vpn.save_app_behavior(&behavior);
        }
        let message = match result {
            Some(ms) => format!("Ping {id}: {ms} ms"),
            None => format!("Ping {id}: unreachable"),
        };
        self.log(&message);
        Ok(())
    }

    fn find_free_servers(&self) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем запустите поиск")?;
        let behavior = VpnSettings::new(&self.app_data_dir).load_app_behavior();
        let sources: Vec<(usize, String, usize)> = if behavior.free_servers_from_pages {
            vec![(0, home::FREE_SERVERS_PAGES_URL.to_string(), 0)]
        } else {
            home::free_sources_for("ru")
                .into_iter()
                .enumerate()
                .map(|(index, source)| (index, source.url.to_string(), source.max_count))
                .collect()
        };
        self.log(&format!(
            "Free servers: fetching {} source(s)",
            sources.len()
        ));
        let fetched = Arc::new(Mutex::new(Vec::<(usize, String, usize)>::new()));
        thread::scope(|scope| {
            for (index, url, max_count) in sources {
                let fetched = fetched.clone();
                scope.spawn(move || {
                    let request = HttpRequest {
                        url,
                        headers: vec![("User-Agent".into(), "YPtun/4.0.0".into())],
                        proxy: None,
                    };
                    if let Some(response) = ReqwestFetcher::new().get(&request) {
                        if response.ok() {
                            fetched
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .push((index, response.body, max_count));
                        }
                    }
                });
            }
        });
        let mut fetched = fetched
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
        fetched.sort_by_key(|value| value.0);
        let source_texts: Vec<(String, usize)> = fetched
            .into_iter()
            .map(|(_, body, max_count)| (body, max_count))
            .collect();
        if source_texts.is_empty() {
            return Err("Источники бесплатных серверов недоступны".into());
        }
        let limit = behavior.free_servers_limit.max(0) as usize;
        let candidates = home::pick_free_servers(&source_texts, limit);
        if candidates.is_empty() {
            return Err("В источниках не найдено поддерживаемых серверов".into());
        }
        self.log(&format!(
            "Free servers: checking {} candidate(s)",
            candidates.len()
        ));
        let selected = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .check_free_servers(
                candidates,
                behavior.ping_parallelism.max(1) as usize,
                &behavior.effective_ping_url(),
                behavior.effective_ping_timeout_ms(),
            );
        if selected.is_empty() {
            return Err("Ни один бесплатный сервер не ответил".into());
        }
        let bundle = self.repository.bundle();
        let previous = home::previous_free_subscription(&bundle);
        let entries = home::free_server_entries(
            &selected,
            &BTreeSet::new(),
            previous.as_ref(),
            yptun_core::desktop_app::now_ms() as i64,
        );
        let (bundle, added) = home::merge_free_servers(&bundle, entries);
        self.repository.save_bundle(bundle);
        self.log(&format!("Free servers: saved {added} working server(s)"));
        Ok(())
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        self.data().delete(id)?;
        self.log(&format!("Deleted location {id}"));
        Ok(())
    }

    fn save_subscription(&self, old_url: &str, text: &str) -> Result<(), String> {
        let new_url = self.data().save_subscription(old_url, text)?;
        self.log(&format!("Updated subscription {new_url}"));
        Ok(())
    }

    fn delete_subscription(&self, url: &str) -> Result<(), String> {
        let ids = self.data().delete_subscription(url)?;
        let mut pings = self
            .ping_results
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        for id in &ids {
            pings.remove(id);
        }
        drop(pings);
        self.log(&format!("Deleted subscription {url}"));
        Ok(())
    }

    fn refresh_subscription(&self, url: &str) -> Result<(), String> {
        let proxy = self.subscription_proxy()?;
        let count = self.data().refresh_subscription(url, proxy.as_deref())?;
        self.log(&format!("Refreshed subscription {url}: {count}"));
        Ok(())
    }

    fn refresh_subscriptions(&self) -> Result<(), String> {
        let proxy = self.subscription_proxy()?;
        let count = self.data().refresh_subscriptions(proxy.as_deref());
        self.log(&format!("Refreshed {count} subscription(s)"));
        Ok(())
    }

    fn subscription_proxy(&self) -> Result<Option<String>, String> {
        if self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected()
        {
            let socks =
                DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Windows).load();
            Ok(Some(socks.proxy_url(DesktopOs::Windows)?))
        } else {
            Ok(None)
        }
    }

    fn set_mode(&self, mode: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем смените режим")?;
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

    fn require_disconnected(&self, message: &str) -> Result<(), String> {
        if self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected()
        {
            Err(message.to_string())
        } else {
            Ok(())
        }
    }

    fn start(&self, requested_mode: &str) -> Result<(), String> {
        if !requested_mode.is_empty() {
            self.set_mode(requested_mode)?;
        }
        let active = self
            .repository
            .active_location()
            .ok_or_else(|| "Нет активной конфигурации".to_string())?;
        if !active.location().is_complete() {
            return Err("Активная конфигурация заполнена не полностью".into());
        }

        *self
            .status
            .write()
            .unwrap_or_else(|error| error.into_inner()) = RuntimeStatus::Connecting;
        let location = active.location();
        let mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            .to_string();
        match self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .start(&location, &mode)
        {
            Ok(()) => {
                *self
                    .status
                    .write()
                    .unwrap_or_else(|error| error.into_inner()) = RuntimeStatus::Connected;
                Ok(())
            }
            Err(error) => {
                *self
                    .status
                    .write()
                    .unwrap_or_else(|failure| failure.into_inner()) =
                    RuntimeStatus::Error(error.clone());
                Err(error)
            }
        }
    }

    fn stop(&self) -> Result<(), String> {
        *self
            .status
            .write()
            .unwrap_or_else(|error| error.into_inner()) = RuntimeStatus::Stopping;
        let result = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .stop();
        *self
            .status
            .write()
            .unwrap_or_else(|error| error.into_inner()) = RuntimeStatus::Disconnected;
        self.log("VPN stopped");
        result
    }

    pub fn snapshot(&self) -> CoreSnapshot {
        self.maintain_connection();
        let bundle = self.repository.bundle();
        let active_name = bundle
            .active_location_id
            .as_ref()
            .and_then(|id| {
                bundle
                    .locations
                    .iter()
                    .find(|entry| &entry.storage_id == id)
            })
            .map(|entry| entry.location().display_name());
        let status = self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
        let mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            .to_string();

        let runtime = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let runtime_available = runtime.is_available();
        let (telegram_proxy_status, telegram_proxy_detail) = runtime.telegram_proxy_status();
        let session = runtime.diagnostics();
        drop(runtime);
        let ping_results = self
            .ping_results
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
        CoreSnapshot {
            status: status.wire_name().into(),
            status_label: status.label().into(),
            status_detail: status.detail(active_name.as_deref()),
            is_connected: status.is_connected(),
            runtime_available,
            connection_mode: mode,
            active_profile_id: bundle.active_location_id.clone(),
            profiles: bundle
                .locations
                .iter()
                .map(|entry| profile_item(entry, &ping_results))
                .collect(),
            subscriptions: yptun_core::importer::subscription_groups(&bundle)
                .into_iter()
                .map(|(url, entries)| subscription_item(url, &entries))
                .collect(),
            session,
            telegram_proxy_status,
            telegram_proxy_detail,
            logs: self.journal.snapshot(),
        }
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        let line = format!("{stamp} {message}");
        self.journal.add(&line);
        logging::append(&self.app_data_dir, message);
    }

    fn maintain_connection(&self) {
        if !matches!(
            *self
                .status
                .read()
                .unwrap_or_else(|error| error.into_inner()),
            RuntimeStatus::Connected
        ) {
            return;
        }
        let reason = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .watchdog_reason();
        let Some(reason) = reason else { return };
        self.log(&reason);
        let Some(active) = self.repository.active_location() else {
            *self
                .status
                .write()
                .unwrap_or_else(|error| error.into_inner()) =
                RuntimeStatus::Error("Активная конфигурация исчезла".into());
            return;
        };
        let mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            .to_string();
        *self
            .status
            .write()
            .unwrap_or_else(|error| error.into_inner()) = RuntimeStatus::Reconnecting;
        let result = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .start(&active.location(), &mode);
        *self
            .status
            .write()
            .unwrap_or_else(|error| error.into_inner()) = match result {
            Ok(()) => RuntimeStatus::Connected,
            Err(error) => RuntimeStatus::Error(error),
        };
    }
}

impl Drop for WindowsApp {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|error| {
        format!(
            "{{\"ok\":false,\"data\":null,\"error\":\"serialization failed: {}\"}}",
            error
        )
    })
}

fn windows_version() -> String {
    std::env::var("OS").unwrap_or_else(|_| "Windows".into())
}

fn is_elevated() -> bool {
    yptun_core::desktop_app::is_elevated()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};
    use yptun_core::location::{LocationMetadata, SubscriptionMetadata};
    use yptun_core::routing::RoutingProfile;

    fn temp_dir() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("yptun-windows-test-{}-{nonce}", std::process::id()))
    }

    #[test]
    fn imports_and_selects_profile_through_command_api() {
        let root = temp_dir();
        let app = WindowsApp::new(root.clone(), root.join("native"), "ru", "4.0.0");
        let response = app.command_json(
            r#"{"command":"import","text":"vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#Test"}"#,
        );
        let value: serde_json::Value = serde_json::from_str(&response).unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["data"]["profiles"].as_array().unwrap().len(), 1);
        assert_eq!(value["data"]["profiles"][0]["name"], "Test");
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn rejects_unknown_command() {
        let root = temp_dir();
        let app = WindowsApp::new(root.clone(), root.join("native"), "en", "4.0.0");
        let response = app.command_json(r#"{"command":"explode"}"#);
        let value: serde_json::Value = serde_json::from_str(&response).unwrap();
        assert_eq!(value["ok"], false);
        assert!(value["error"].as_str().unwrap().contains("unknown command"));
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn imports_routing_profile_through_the_same_import_command() {
        let root = temp_dir();
        let app = WindowsApp::new(root.clone(), root.join("native"), "ru", "4.0.0");
        let profile = RoutingProfile {
            name: "Direct example".into(),
            direct_sites: vec!["example.com".into()],
            ..RoutingProfile::default()
        };
        let request = serde_json::json!({
            "command": "import",
            "text": serde_json::to_string(&profile).unwrap(),
        });
        let response = app.command_json(&request.to_string());
        let value: serde_json::Value = serde_json::from_str(&response).unwrap();
        assert_eq!(value["ok"], true);

        let settings = app.command_json(r#"{"command":"settings"}"#);
        let value: serde_json::Value = serde_json::from_str(&settings).unwrap();
        assert_eq!(
            value["data"]["routingProfiles"]["profiles"][0]["name"],
            "Direct example"
        );
        assert_eq!(
            value["data"]["routingProfiles"]["profiles"][0]["directsites"][0],
            "example.com"
        );
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn edits_and_deletes_a_subscription_as_one_group() {
        let root = temp_dir();
        let app = WindowsApp::new(root.clone(), root.join("native"), "ru", "4.0.0");
        assert!(app.repository.import_text(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#One",
            None,
        ));
        let url = "https://subscription.example.test/token";
        let mut bundle = app.repository.bundle();
        bundle.locations[0].subscription_url = Some(url.into());
        bundle.locations[0].metadata = Some(LocationMetadata {
            subscription: Some(SubscriptionMetadata {
                name: Some("Panel name".into()),
                update_interval_hours: Some(24),
                ..SubscriptionMetadata::default()
            }),
            ..LocationMetadata::default()
        });
        app.repository.save_bundle(bundle);

        let snapshot: serde_json::Value =
            serde_json::from_str(&app.command_json(r#"{"command":"snapshot"}"#)).unwrap();
        assert_eq!(snapshot["data"]["subscriptions"][0]["locationCount"], 1);
        assert_eq!(snapshot["data"]["subscriptions"][0]["name"], "Panel name");

        let edit = serde_json::json!({
            "url": url,
            "customName": "Work",
            "cascadeLink": "",
            "updateIntervalHours": 12,
            "autoUpdateEnabled": false,
        });
        let request = serde_json::json!({
            "command": "save_subscription",
            "id": url,
            "text": edit.to_string(),
        });
        let edited: serde_json::Value =
            serde_json::from_str(&app.command_json(&request.to_string())).unwrap();
        assert_eq!(edited["ok"], true);
        assert_eq!(edited["data"]["subscriptions"][0]["customName"], "Work");
        assert_eq!(
            edited["data"]["subscriptions"][0]["updateIntervalHours"],
            12
        );
        assert_eq!(
            edited["data"]["subscriptions"][0]["autoUpdateEnabled"],
            false
        );

        let request = serde_json::json!({ "command": "delete_subscription", "id": url });
        let deleted: serde_json::Value =
            serde_json::from_str(&app.command_json(&request.to_string())).unwrap();
        assert_eq!(deleted["ok"], true);
        assert_eq!(deleted["data"]["profiles"].as_array().unwrap().len(), 0);
        assert_eq!(
            deleted["data"]["subscriptions"].as_array().unwrap().len(),
            0
        );
        let _ = std::fs::remove_dir_all(root);
    }
}
