use std::collections::BTreeMap;
#[cfg(test)]
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use serde::Serialize;
use yptun_core::desktop_api::{
    profile_item, subscription_item, CommandRequest, CoreSnapshot, LocationEditorData, Response,
    RuntimeDiagnostics, SettingsSnapshot, TextPayload,
};
use yptun_core::desktop_data::DesktopData;
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_settings::{DesktopSettings, CONNECTION_MODE_PROXY, CONNECTION_MODE_TUN};
use yptun_core::desktop_store::{device_identity, DesktopFileStore};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::repository::{ClientInfo, HttpFetcher, Repository};

mod host;
mod runtime;

use runtime::LinuxRuntime;

const AUXILIARY_RUNTIME_UNAVAILABLE: &str = "This Linux runtime command is not available yet";

pub struct LinuxApp {
    app_data_dir: PathBuf,
    repository: Repository,
    settings: Mutex<DesktopSettings>,
    journal: Arc<LogJournal>,
    runtime: Mutex<LinuxRuntime>,
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
        let journal = Arc::new(LogJournal::new());
        let runtime =
            LinuxRuntime::new(app_data_dir.clone(), identity.clone(), Arc::clone(&journal));
        let repository = Repository::new(
            Box::new(DesktopFileStore::new(app_data_dir.clone())),
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
            journal,
            runtime: Mutex::new(runtime),
            app_data_dir,
        };
        app.log("Linux data backend initialized");
        app
    }

    fn data(&self) -> DesktopData<'_> {
        let connected = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected();
        DesktopData::new(
            &self.app_data_dir,
            &self.repository,
            DesktopOs::Linux,
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
            "snapshot" => json(&Response::success(self.snapshot())),
            "import" => self.snapshot_result(self.import(&request.text)),
            "select" => self.snapshot_result(self.select(&request.id)),
            "delete" => self.snapshot_result(self.delete(&request.id)),
            "refresh_subscriptions" => self.snapshot_result(self.refresh_subscriptions()),
            "refresh_subscription" => self.snapshot_result(self.refresh_subscription(&request.id)),
            "save_subscription" => {
                self.snapshot_result(self.save_subscription(&request.id, &request.text))
            }
            "delete_subscription" => self.snapshot_result(self.delete_subscription(&request.id)),
            "set_mode" => self.snapshot_result(self.set_mode(&request.mode)),
            "start" => self.snapshot_result(self.start(&request.mode)),
            "stop" => self.snapshot_result(self.stop()),
            "ping" | "find_free_servers" | "exit_ip" => {
                self.result_json::<CoreSnapshot>(Err(AUXILIARY_RUNTIME_UNAVAILABLE.into()))
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
        for message in self.data().import(text)? {
            self.log(&message);
        }
        Ok(())
    }

    fn select(&self, id: &str) -> Result<(), String> {
        self.data().select(id)?;
        self.log(&format!("Selected location {id}"));
        Ok(())
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        self.data().delete(id)?;
        self.log(&format!("Deleted location {id}"));
        Ok(())
    }

    fn refresh_subscriptions(&self) -> Result<(), String> {
        let count = self.data().refresh_subscriptions(None);
        self.log(&format!("Refreshed {count} subscription(s)"));
        Ok(())
    }

    fn refresh_subscription(&self, url: &str) -> Result<(), String> {
        let count = self.data().refresh_subscription(url, None)?;
        self.log(&format!("Refreshed subscription {url}: {count}"));
        Ok(())
    }

    fn save_subscription(&self, old_url: &str, text: &str) -> Result<(), String> {
        let new_url = self.data().save_subscription(old_url, text)?;
        self.log(&format!("Updated subscription {new_url}"));
        Ok(())
    }

    fn delete_subscription(&self, url: &str) -> Result<(), String> {
        self.data().delete_subscription(url)?;
        self.log(&format!("Deleted subscription {}", url.trim()));
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

    fn start(&self, requested_mode: &str) -> Result<(), String> {
        if !requested_mode.is_empty() {
            self.set_mode(requested_mode)?;
        }
        let active = self
            .repository
            .active_location()
            .ok_or_else(|| "Нет активной конфигурации".to_string())?;
        let location = active.location();
        if !location.is_complete() {
            return Err("Активная конфигурация заполнена не полностью".into());
        }
        let mode = self
            .settings
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .connection_mode()
            .to_string();
        self.runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .start(&location, &mode)
    }

    fn stop(&self) -> Result<(), String> {
        self.runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .stop()
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

    fn save_settings(&self, kind: &str, text: &str) -> Result<(), String> {
        self.data().save_settings(kind, text)?;
        self.log(&format!("Saved settings: {kind}"));
        Ok(())
    }

    fn require_disconnected(&self, message: &str) -> Result<(), String> {
        if self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .is_connected()
        {
            Err(message.to_string())
        } else {
            Ok(())
        }
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
        let runtime = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .snapshot(active_name.as_deref(), &mode);

        CoreSnapshot {
            status: runtime.status,
            status_label: runtime.status_label,
            status_detail: runtime.status_detail,
            is_connected: runtime.is_connected,
            runtime_available: runtime.runtime_available,
            connection_mode: mode.clone(),
            active_profile_id: bundle.active_location_id.clone(),
            profiles: {
                let ping_results = BTreeMap::new();
                bundle
                    .locations
                    .iter()
                    .map(|entry| profile_item(entry, &ping_results))
                    .collect()
            },
            subscriptions: yptun_core::importer::subscription_groups(&bundle)
                .into_iter()
                .map(|(url, entries)| subscription_item(url, &entries))
                .collect(),
            session: RuntimeDiagnostics {
                active_engine: runtime.active_engine,
                connection_mode: runtime.connection_mode,
                engine_alive: runtime.engine_alive,
                connected_since_epoch_ms: 0,
                in_core_tun: false,
                external_tun: runtime.external_tun,
                bypass_route_count: 0,
                watchdog_probe_failures: 0,
            },
            telegram_proxy_status: "unavailable".into(),
            telegram_proxy_detail: AUXILIARY_RUNTIME_UNAVAILABLE.into(),
            logs: self.journal.snapshot(),
        }
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
    }
}

impl Drop for LinuxApp {
    fn drop(&mut self) {
        let _ = self
            .runtime
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .stop();
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

        assert!(temp
            .path()
            .join(yptun_core::desktop_store::BUNDLE_FILE)
            .is_file());
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

        let edited = command(
            &app,
            serde_json::json!({
                "command": "save_subscription",
                "id": url,
                "text": serde_json::json!({
                    "customName": "Work",
                    "updateIntervalHours": 12,
                    "autoUpdateEnabled": false,
                }).to_string(),
            }),
        );
        assert_eq!(edited["ok"], true);
        assert_eq!(edited["data"]["subscriptions"][0]["customName"], "Work");
        assert_eq!(
            edited["data"]["subscriptions"][0]["updateIntervalHours"],
            12
        );

        let deleted = command(
            &app,
            serde_json::json!({"command": "delete_subscription", "id": url}),
        );
        assert_eq!(deleted["ok"], true);
        assert!(deleted["data"]["profiles"].as_array().unwrap().is_empty());
        assert!(deleted["data"]["subscriptions"]
            .as_array()
            .unwrap()
            .is_empty());
    }

    #[test]
    fn unavailable_runtime_start_fails_but_stop_cleans_up() {
        let temp = tempfile::tempdir().unwrap();
        let app = LinuxApp::new(temp.path().to_path_buf(), "en", "4.0.0");
        let snapshot = command(&app, serde_json::json!({"command": "snapshot"}));
        assert_eq!(snapshot["data"]["runtimeAvailable"], false);

        let imported = command(
            &app,
            serde_json::json!({
                "command": "import",
                "text": "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#Test"
            }),
        );
        assert_eq!(imported["ok"], true);
        let started = command(&app, serde_json::json!({"command": "start", "mode": "tun"}));
        assert_eq!(started["ok"], false);
        assert!(started["error"]
            .as_str()
            .unwrap()
            .contains("native core is unavailable"));

        let stopped = command(&app, serde_json::json!({"command": "stop"}));
        assert_eq!(stopped["ok"], true);
        assert_eq!(stopped["data"]["status"], "disconnected");
    }
}
