#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod backend;
#[cfg(any(target_os = "linux", test))]
mod http;
#[cfg(any(target_os = "linux", test))]
mod linux;

use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::Value;
use slint::{ModelRc, SharedString, Timer, TimerMode, VecModel};
use yptun_core::desktop_api::{CoreSnapshot, Response};

slint::include_modules!();

fn main() -> Result<(), slint::PlatformError> {
    let actor = backend::DesktopActor::spawn();
    let ui = AppWindow::new()?;
    let state = Arc::new(Mutex::new(UiState::default()));

    request_refresh(&ui, &actor, state.clone());

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_toggle_connection(move || {
            let Some(ui) = weak.upgrade() else { return };
            let connected = state.lock().map(|value| value.connected).unwrap_or(false);
            let command = if connected { "stop" } else { "start" };
            let mode = ui.get_connection_mode().to_string();
            let request = serde_json::json!({ "command": command, "mode": mode });
            ui.set_status_label(
                if connected {
                    "Отключение"
                } else {
                    "Подключение"
                }
                .into(),
            );
            ui.set_status_detail("Пожалуйста, подождите…".into());
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if !response_ok(&response) {
                    show_status_error(&ui, &response);
                }
                request_refresh(&ui, &refresh_actor, refresh_state);
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_delete_profile(move |id| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({ "command": "delete", "id": id.as_str() });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if response_ok(&response) {
                    ui.set_current_page(0);
                    ui.set_editor_id("".into());
                    ui.set_editor_name("".into());
                    ui.set_editor_json("".into());
                    request_refresh(&ui, &refresh_actor, refresh_state);
                } else {
                    ui.set_current_page(3);
                    ui.set_editor_error(response_error(&response).into());
                }
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_refresh_subscription(move |url| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({
                "command": "refresh_subscription",
                "id": url.as_str(),
            });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if response_ok(&response) {
                    request_refresh(&ui, &refresh_actor, refresh_state);
                } else {
                    show_status_error(&ui, &response);
                }
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        ui.on_edit_profile(move |id| {
            let Some(ui) = weak.upgrade() else { return };
            let id = id.to_string();
            let request = serde_json::json!({ "command": "get_profile", "id": id });
            submit_to_ui(
                &ui,
                &actor,
                request,
                move |ui, response| match response_data(&response) {
                    Some(data) => {
                        let location = data
                            .get("location")
                            .cloned()
                            .unwrap_or_else(|| serde_json::json!({}));
                        let name = location
                            .get("name")
                            .and_then(Value::as_str)
                            .unwrap_or_default();
                        ui.set_editor_id(
                            data.get("storageId")
                                .and_then(Value::as_str)
                                .unwrap_or(&id)
                                .into(),
                        );
                        ui.set_editor_name(name.into());
                        ui.set_editor_json(
                            serde_json::to_string_pretty(&location)
                                .unwrap_or_else(|_| location.to_string())
                                .into(),
                        );
                        ui.set_editor_subscription(
                            data.get("isSubscription")
                                .and_then(Value::as_bool)
                                .unwrap_or(false),
                        );
                        ui.set_editor_error("".into());
                    }
                    None => ui.set_editor_error(response_error(&response).into()),
                },
            );
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_save_profile(move |id, name, json| {
            let Some(ui) = weak.upgrade() else { return };
            let mut location = match serde_json::from_str::<Value>(json.as_str()) {
                Ok(value @ Value::Object(_)) => value,
                Ok(_) => {
                    ui.set_editor_error("Конфигурация должна быть JSON-объектом".into());
                    return;
                }
                Err(error) => {
                    ui.set_editor_error(format!("Некорректный JSON: {error}").into());
                    return;
                }
            };
            if let Some(object) = location.as_object_mut() {
                object.insert("name".into(), name.to_string().into());
            }
            let request = serde_json::json!({
                "command": "save_profile",
                "id": id.as_str(),
                "text": location.to_string(),
            });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if response_ok(&response) {
                    ui.set_current_page(0);
                    ui.set_editor_error("".into());
                    request_refresh(&ui, &refresh_actor, refresh_state);
                } else {
                    ui.set_editor_error(response_error(&response).into());
                }
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_ping_profile(move |id| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({ "command": "ping", "id": id.as_str() });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if response_ok(&response) {
                    request_refresh(&ui, &refresh_actor, refresh_state);
                } else {
                    show_status_error(&ui, &response);
                }
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_import_profile(move |text| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({ "command": "import", "text": text.as_str() });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if response_ok(&response) {
                    ui.set_import_text("".into());
                    ui.set_import_error("".into());
                    ui.set_current_page(0);
                    request_refresh(&ui, &refresh_actor, refresh_state);
                } else {
                    ui.set_import_error(response_error(&response).into());
                }
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_set_connection_mode(move |mode| {
            let Some(ui) = weak.upgrade() else { return };
            let mode = mode.to_string();
            let request = serde_json::json!({ "command": "set_mode", "mode": mode });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if !response_ok(&response) {
                    show_status_error(&ui, &response);
                }
                request_refresh(&ui, &refresh_actor, refresh_state);
            });
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_open_settings(move || {
            if let Some(ui) = weak.upgrade() {
                request_settings(&ui, &actor, state.clone());
            }
        });
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
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
                let settings_actor = actor.clone();
                let settings_state = state.clone();
                submit_to_ui(&ui, &actor, request, move |ui, response| {
                    if response_ok(&response) {
                        request_settings(&ui, &settings_actor, settings_state);
                    } else {
                        show_status_error(&ui, &response);
                    }
                });
            },
        );
    }

    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        ui.on_select_profile(move |id| {
            let Some(ui) = weak.upgrade() else { return };
            let request = serde_json::json!({ "command": "select", "id": id.as_str() });
            let refresh_actor = actor.clone();
            let refresh_state = state.clone();
            submit_to_ui(&ui, &actor, request, move |ui, response| {
                if !response_ok(&response) {
                    show_status_error(&ui, &response);
                }
                request_refresh(&ui, &refresh_actor, refresh_state);
            });
        });
    }

    let poll_timer = Timer::default();
    {
        let weak = ui.as_weak();
        let actor = actor.clone();
        let state = state.clone();
        poll_timer.start(TimerMode::Repeated, Duration::from_millis(900), move || {
            if let Some(ui) = weak.upgrade() {
                request_refresh(&ui, &actor, state.clone());
            }
        });
    }

    ui.run()
}

#[derive(Default)]
struct UiState {
    connected: bool,
    settings: Option<Value>,
    snapshot_pending: bool,
}

fn submit_to_ui(
    ui: &AppWindow,
    actor: &backend::DesktopActor,
    request: Value,
    completion: impl FnOnce(AppWindow, String) + Send + 'static,
) {
    let weak = ui.as_weak();
    actor.command(request.to_string(), move |response| {
        let _ = weak.upgrade_in_event_loop(move |ui| completion(ui, response));
    });
}

fn request_refresh(ui: &AppWindow, actor: &backend::DesktopActor, state: Arc<Mutex<UiState>>) {
    let should_start = state
        .lock()
        .map(|mut current| {
            if current.snapshot_pending {
                false
            } else {
                current.snapshot_pending = true;
                true
            }
        })
        .unwrap_or(false);
    if !should_start {
        return;
    }

    let completion_state = state.clone();
    submit_to_ui(
        ui,
        actor,
        serde_json::json!({ "command": "snapshot" }),
        move |ui, response| {
            if let Ok(mut current) = completion_state.lock() {
                current.snapshot_pending = false;
            }
            apply_snapshot_response(&ui, &response, &completion_state);
        },
    );
}

fn apply_snapshot_response(ui: &AppWindow, response: &str, state: &Mutex<UiState>) {
    let parsed = serde_json::from_str::<Response<CoreSnapshot>>(response);
    let snapshot = match parsed {
        Ok(Response {
            ok: true,
            data: Some(snapshot),
            ..
        }) => snapshot,
        Ok(Response { error, .. }) => {
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
    ui.set_selected_profile_id(selected.clone().into());
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

    let subscriptions = snapshot
        .subscriptions
        .into_iter()
        .map(|subscription| {
            let display_name = if subscription.custom_name.trim().is_empty() {
                subscription.name
            } else {
                subscription.custom_name
            };
            let automatic = if subscription.auto_update_enabled {
                "автообновление"
            } else {
                "вручную"
            };
            SubscriptionRow {
                url: subscription.url.into(),
                name: display_name.into(),
                detail: format!("{} локаций · {automatic}", subscription.location_count).into(),
            }
        })
        .collect::<Vec<_>>();
    ui.set_subscriptions(ModelRc::from(Rc::new(VecModel::from(subscriptions))));
}

fn request_settings(ui: &AppWindow, actor: &backend::DesktopActor, state: Arc<Mutex<UiState>>) {
    submit_to_ui(
        ui,
        actor,
        serde_json::json!({ "command": "settings" }),
        move |ui, response| apply_settings_response(&ui, &response, &state),
    );
}

fn apply_settings_response(ui: &AppWindow, response: &str, state: &Mutex<UiState>) {
    let parsed = serde_json::from_str::<Response<Value>>(response);
    let settings = match parsed {
        Ok(Response {
            ok: true,
            data: Some(settings),
            ..
        }) => settings,
        Ok(Response { error, .. }) => {
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

fn show_status_error(ui: &AppWindow, response: &str) {
    ui.set_status_label("Ошибка".into());
    ui.set_status_detail(response_error(response).into());
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

fn response_data(response: &str) -> Option<Value> {
    let value = serde_json::from_str::<Value>(response).ok()?;
    value
        .get("ok")?
        .as_bool()?
        .then(|| value.get("data").cloned())
        .flatten()
}
