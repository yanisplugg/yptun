use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use serde::{Deserialize, Serialize};
use yptun_core::desktop_settings::{DesktopSettings, CONNECTION_MODE_PROXY, CONNECTION_MODE_TUN};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::location::{LocationEntry, LocationMetadata};
use yptun_core::model::EngineType;
use yptun_core::repository::{ClientInfo, Repository};

use crate::http::ReqwestFetcher;
use crate::logging;
use crate::runtime::WindowsRuntime;
use crate::store::{device_identity, FileStore};

#[derive(Debug, Clone, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct CommandRequest {
    pub command: String,
    pub text: String,
    pub id: String,
    pub mode: String,
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
pub struct ProfileItem {
    pub id: String,
    pub name: String,
    pub subtitle: String,
    pub engine: String,
    pub is_complete: bool,
    pub is_subscription: bool,
    pub subscription_name: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreSnapshot {
    pub status: String,
    pub status_label: String,
    pub status_detail: String,
    pub is_connected: bool,
    pub runtime_available: bool,
    pub connection_mode: String,
    pub active_profile_id: Option<String>,
    pub profiles: Vec<ProfileItem>,
    pub logs: Vec<String>,
}

#[derive(Debug, Clone)]
enum RuntimeStatus {
    Disconnected,
    Connecting,
    Connected,
    Stopping,
    Error(String),
}

impl RuntimeStatus {
    fn wire_name(&self) -> &'static str {
        match self {
            Self::Disconnected => "disconnected",
            Self::Connecting => "connecting",
            Self::Connected => "connected",
            Self::Stopping => "stopping",
            Self::Error(_) => "error",
        }
    }

    fn label(&self) -> &'static str {
        match self {
            Self::Disconnected => "Отключено",
            Self::Connecting => "Подключение…",
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

pub struct WindowsApp {
    app_data_dir: PathBuf,
    native_dir: PathBuf,
    repository: Repository,
    settings: Mutex<DesktopSettings>,
    journal: Arc<LogJournal>,
    status: RwLock<RuntimeStatus>,
    runtime: Mutex<WindowsRuntime>,
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
        let store = FileStore::new(app_data_dir.clone());
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
        let app = Self {
            app_data_dir,
            native_dir,
            repository,
            settings: Mutex::new(settings),
            journal,
            status: RwLock::new(RuntimeStatus::Disconnected),
            runtime: Mutex::new(runtime),
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

    pub fn command_json(&self, request_json: &str) -> String {
        let request: CommandRequest = match serde_json::from_str(request_json) {
            Ok(value) => value,
            Err(error) => {
                return json(&Response::<CoreSnapshot>::error(format!(
                    "invalid request: {error}"
                )))
            }
        };
        json(&self.command(request))
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
        if text.trim().is_empty() {
            return Err("Вставленный текст пуст".into());
        }
        if !self.repository.import_text(text, None) {
            return Err("Конфигурация или подписка не распознана".into());
        }
        self.log("Configuration imported");
        Ok(())
    }

    fn select(&self, id: &str) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем смените конфигурацию")?;
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
        if self
            .status
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected()
        {
            return Err("Сначала отключите VPN".into());
        }
        self.repository.delete_location(id);
        self.log(&format!("Deleted location {id}"));
        Ok(())
    }

    fn refresh_subscriptions(&self) -> Result<(), String> {
        self.require_disconnected("Сначала отключите VPN, затем обновите подписки")?;
        let count = self.repository.refresh_subscriptions(None, None);
        self.log(&format!("Refreshed {count} subscription(s)"));
        Ok(())
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

        CoreSnapshot {
            status: status.wire_name().into(),
            status_label: status.label().into(),
            status_detail: status.detail(active_name.as_deref()),
            is_connected: status.is_connected(),
            runtime_available: self
                .runtime
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .is_available(),
            connection_mode: mode,
            active_profile_id: bundle.active_location_id,
            profiles: bundle.locations.iter().map(profile_item).collect(),
            logs: self.journal.snapshot(),
        }
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        let line = format!("{stamp} {message}");
        self.journal.add(&line);
        logging::append(&self.app_data_dir, message);
    }
}

impl Drop for WindowsApp {
    fn drop(&mut self) {
        let _ = self.stop();
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
    }
}

fn subscription_name(metadata: &LocationMetadata) -> Option<String> {
    metadata
        .subscription
        .as_ref()
        .and_then(|subscription| subscription.name.clone())
        .filter(|name| !name.trim().is_empty())
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
}
