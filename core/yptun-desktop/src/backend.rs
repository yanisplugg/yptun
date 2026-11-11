use std::path::PathBuf;
use std::sync::Arc;

pub trait DesktopBackend: Send + Sync {
    fn command(&self, request_json: &str) -> String;
}

#[cfg(windows)]
pub fn create() -> Arc<dyn DesktopBackend> {
    Arc::new(WindowsBackend::new())
}

#[cfg(not(windows))]
pub fn create() -> Arc<dyn DesktopBackend> {
    Arc::new(PendingLinuxBackend)
}

#[cfg(windows)]
struct WindowsBackend {
    app: yptun_windows::WindowsApp,
}

#[cfg(windows)]
impl WindowsBackend {
    fn new() -> Self {
        let executable = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("yptun"));
        let executable_dir = executable
            .parent()
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        let portable_carrier = std::env::var_os("YPTUN_PORTABLE_EXE").map(PathBuf::from);
        let portable_root = portable_carrier
            .as_deref()
            .filter(|path| path.is_file())
            .and_then(|path| path.parent())
            .map(PathBuf::from);
        let data_dir = portable_root
            .map(|root| root.join("Data"))
            .unwrap_or_else(|| {
                std::env::var_os("APPDATA")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| executable_dir.clone())
                    .join("YPtun")
            });
        let native_dir = executable_dir.join("Native");
        Self {
            app: yptun_windows::WindowsApp::new(
                data_dir,
                native_dir,
                "ru",
                env!("CARGO_PKG_VERSION"),
            ),
        }
    }
}

#[cfg(windows)]
impl DesktopBackend for WindowsBackend {
    fn command(&self, request_json: &str) -> String {
        self.app.command_json(request_json)
    }
}

#[cfg(not(windows))]
struct PendingLinuxBackend;

#[cfg(not(windows))]
impl DesktopBackend for PendingLinuxBackend {
    fn command(&self, request_json: &str) -> String {
        let command = serde_json::from_str::<serde_json::Value>(request_json)
            .ok()
            .and_then(|value| value.get("command")?.as_str().map(str::to_owned))
            .unwrap_or_default();
        if command == "snapshot" {
            serde_json::json!({
                "ok": true,
                "data": {
                    "status": "disconnected",
                    "statusLabel": "Отключено",
                    "statusDetail": "Linux runtime adapter is being connected to the shared core",
                    "isConnected": false,
                    "runtimeAvailable": false,
                    "connectionMode": "tun",
                    "activeProfileId": null,
                    "profiles": [],
                    "logs": []
                },
                "error": null
            })
            .to_string()
        } else {
            serde_json::json!({
                "ok": false,
                "data": null,
                "error": "Linux runtime adapter is not implemented yet"
            })
            .to_string()
        }
    }
}
