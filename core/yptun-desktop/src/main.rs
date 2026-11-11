mod backend;

use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Deserialize;
use serde_json::Value;
use slint::{ModelRc, SharedString, Timer, TimerMode, VecModel};

slint::include_modules!();

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CoreResponse<T> {
    ok: bool,
    data: Option<T>,
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Snapshot {
    status_label: String,
    status_detail: String,
    is_connected: bool,
    runtime_available: bool,
    connection_mode: String,
    active_profile_id: Option<String>,
    profiles: Vec<Profile>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Profile {
    id: String,
    name: String,
    subtitle: String,
    engine: String,
    is_complete: bool,
    ping_ms: Option<i32>,
    ping_tested: bool,
}

fn main() -> Result<(), slint::PlatformError> {
    let backend = backend::create();
    let ui = AppWindow::new()?;
    let state = Arc::new(Mutex::new(UiState::default()));

    refresh(&ui, backend.as_ref(), &state);

    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        ui.on_toggle_connection(move || {
            let Some(ui) = weak.upgrade() else { return };
            let connected = state.lock().map(|value| value.connected).unwrap_or(false);
            let command = if connected { "stop" } else { "start" };
            let mode = ui.get_connection_mode().to_string();
            let request = serde_json::json!({ "command": command, "mode": mode });
            let _ = backend.command(&request.to_string());
            refresh(&ui, backend.as_ref(), &state);
        });
    }

    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        ui.on_set_connection_mode(move |mode| {
            let Some(ui) = weak.upgrade() else { return };
            let mode = mode.to_string();
            let request = serde_json::json!({ "command": "set_mode", "mode": mode });
            let _ = backend.command(&request.to_string());
            refresh(&ui, backend.as_ref(), &state);
        });
    }

    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        ui.on_open_settings(move || {
            if let Some(ui) = weak.upgrade() {
                load_settings(&ui, backend.as_ref(), &state);
            }
        });
    }

    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        ui.on_save_app_settings(
            move |auto_connect, system_proxy, telegram_proxy, energy_saver| {
                let Some(ui) = weak.upgrade() else { return };
                let mut app_behavior = state
                    .lock()
                    .ok()
                    .and_then(|value| value.settings.clone())
                    .and_then(|value| value.get("appBehavior").cloned())
                    .unwrap_or_else(|| serde_json::json!({}));
                if let Some(object) = app_behavior.as_object_mut() {
                    object.insert("autoConnectOnLaunch".into(), auto_connect.into());
                    object.insert("setSystemProxy".into(), system_proxy.into());
                    object.insert("telegramProxyEnabled".into(), telegram_proxy.into());
                    object.insert("energySaver".into(), energy_saver.into());
                }
                let request = serde_json::json!({
                    "command": "save_settings",
                    "id": "app_behavior",
                    "text": app_behavior.to_string(),
                });
                let response = backend.command(&request.to_string());
                if response_ok(&response) {
                    load_settings(&ui, backend.as_ref(), &state);
                } else {
                    ui.set_status_label("Ошибка".into());
                    ui.set_status_detail(response_error(&response).into());
                }
            },
        );
    }

    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        ui.on_select_profile(move |id| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({ "command": "select", "id": id.as_str() });
            let _ = backend.command(&request.to_string());
            refresh(&ui, backend.as_ref(), &state);
        });
    }

    let poll_timer = Timer::default();
    {
        let weak = ui.as_weak();
        let backend = backend.clone();
        let state = state.clone();
        poll_timer.start(TimerMode::Repeated, Duration::from_millis(900), move || {
            if let Some(ui) = weak.upgrade() {
                refresh(&ui, backend.as_ref(), &state);
            }
        });
    }

    ui.run()
}

#[derive(Default)]
struct UiState {
    connected: bool,
    settings: Option<Value>,
}

fn refresh(ui: &AppWindow, backend: &dyn backend::DesktopBackend, state: &Mutex<UiState>) {
    let response = backend.command(r#"{"command":"snapshot"}"#);
    let parsed = serde_json::from_str::<CoreResponse<Snapshot>>(&response);
    let snapshot = match parsed {
        Ok(CoreResponse {
            ok: true,
            data: Some(snapshot),
            ..
        }) => snapshot,
        Ok(CoreResponse { error, .. }) => {
            ui.set_status_label("Ошибка".into());
            ui.set_status_detail(
                error
                    .unwrap_or_else(|| "Не удалось прочитать состояние".into())
                    .into(),
            );
            return;
        }
        Err(error) => {
            ui.set_status_label("Ошибка".into());
            ui.set_status_detail(format!("Некорректный ответ ядра: {error}").into());
            return;
        }
    };

    if let Ok(mut current) = state.lock() {
        current.connected = snapshot.is_connected;
    }
    ui.set_status_label(snapshot.status_label.into());
    ui.set_status_detail(snapshot.status_detail.into());
    ui.set_is_connected(snapshot.is_connected);
    ui.set_runtime_available(snapshot.runtime_available);
    ui.set_connection_mode(snapshot.connection_mode.into());

    let selected = snapshot.active_profile_id.unwrap_or_default();
    let rows = snapshot
        .profiles
        .into_iter()
        .map(|profile| LocationRow {
            id: SharedString::from(profile.id.clone()),
            name: profile.name.into(),
            detail: if profile.subtitle.trim().is_empty() {
                profile.engine
            } else {
                profile.subtitle
            }
            .into(),
            selected: profile.id == selected,
            enabled: profile.is_complete,
            ping: if profile.ping_tested {
                profile
                    .ping_ms
                    .map(|value| format!("{value} мс"))
                    .unwrap_or_else(|| "—".into())
            } else {
                String::new()
            }
            .into(),
        })
        .collect::<Vec<_>>();
    ui.set_locations(ModelRc::from(Rc::new(VecModel::from(rows))));
}

fn load_settings(ui: &AppWindow, backend: &dyn backend::DesktopBackend, state: &Mutex<UiState>) {
    let response = backend.command(r#"{"command":"settings"}"#);
    let parsed = serde_json::from_str::<CoreResponse<Value>>(&response);
    let settings = match parsed {
        Ok(CoreResponse {
            ok: true,
            data: Some(settings),
            ..
        }) => settings,
        Ok(CoreResponse { error, .. }) => {
            ui.set_status_label("Ошибка".into());
            ui.set_status_detail(
                error
                    .unwrap_or_else(|| "Не удалось загрузить настройки".into())
                    .into(),
            );
            return;
        }
        Err(error) => {
            ui.set_status_label("Ошибка".into());
            ui.set_status_detail(format!("Некорректный ответ настроек: {error}").into());
            return;
        }
    };
    let behavior = settings.get("appBehavior");
    ui.set_auto_connect(json_bool(behavior, "autoConnectOnLaunch", false));
    ui.set_system_proxy(json_bool(behavior, "setSystemProxy", true));
    ui.set_telegram_proxy(json_bool(behavior, "telegramProxyEnabled", false));
    ui.set_energy_saver(json_bool(behavior, "energySaver", false));
    if let Ok(mut current) = state.lock() {
        current.settings = Some(settings);
    }
}

fn json_bool(parent: Option<&Value>, name: &str, fallback: bool) -> bool {
    parent
        .and_then(|value| value.get(name))
        .and_then(Value::as_bool)
        .unwrap_or(fallback)
}

fn response_ok(response: &str) -> bool {
    serde_json::from_str::<Value>(response)
        .ok()
        .and_then(|value| value.get("ok")?.as_bool())
        .unwrap_or(false)
}

fn response_error(response: &str) -> String {
    serde_json::from_str::<Value>(response)
        .ok()
        .and_then(|value| value.get("error")?.as_str().map(str::to_owned))
        .unwrap_or_else(|| "Не удалось сохранить настройки".into())
}
