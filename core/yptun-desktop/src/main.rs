mod backend;

use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::Deserialize;
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
}

fn refresh(ui: &AppWindow, backend: &dyn backend::DesktopBackend, state: &Mutex<UiState>) {
    let response = backend.command(r#"{"command":"snapshot"}"#);
    let parsed = serde_json::from_str::<CoreResponse<Snapshot>>(&response);
    let snapshot = match parsed {
        Ok(CoreResponse { ok: true, data: Some(snapshot), .. }) => snapshot,
        Ok(CoreResponse { error, .. }) => {
            ui.set_status_label("Ошибка".into());
            ui.set_status_detail(error.unwrap_or_else(|| "Не удалось прочитать состояние".into()).into());
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
