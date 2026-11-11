use std::sync::{mpsc, Arc};

#[cfg(windows)]
use std::path::PathBuf;

pub trait DesktopBackend: Send + Sync {
    fn command(&self, request_json: &str) -> String;
}

type CommandCompletion = Box<dyn FnOnce(String) + Send + 'static>;

struct ActorCommand {
    request_json: String,
    completion: CommandCompletion,
}

#[derive(Clone)]
pub struct DesktopActor {
    sender: mpsc::Sender<ActorCommand>,
}

impl DesktopActor {
    pub fn spawn() -> Self {
        Self::with_backend(create())
    }

    fn with_backend(backend: Arc<dyn DesktopBackend>) -> Self {
        let (sender, receiver) = mpsc::channel::<ActorCommand>();
        std::thread::Builder::new()
            .name("yptun-desktop-application".into())
            .spawn(move || {
                while let Ok(command) = receiver.recv() {
                    let response = backend.command(&command.request_json);
                    (command.completion)(response);
                }
            })
            .expect("failed to start the desktop application actor");
        Self { sender }
    }

    pub fn command(&self, request_json: String, completion: impl FnOnce(String) + Send + 'static) {
        if let Err(error) = self.sender.send(ActorCommand {
            request_json,
            completion: Box::new(completion),
        }) {
            (error.0.completion)(
                serde_json::json!({
                    "ok": false,
                    "data": null,
                    "error": "Desktop application actor is not available"
                })
                .to_string(),
            );
        }
    }
}

#[cfg(windows)]
pub fn create() -> Arc<dyn DesktopBackend> {
    Arc::new(WindowsBackend::new())
}

#[cfg(target_os = "linux")]
pub fn create() -> Arc<dyn DesktopBackend> {
    Arc::new(LinuxBackend::new())
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn create() -> Arc<dyn DesktopBackend> {
    Arc::new(UnsupportedBackend)
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

#[cfg(target_os = "linux")]
struct LinuxBackend {
    app: crate::linux::LinuxApp,
}

#[cfg(target_os = "linux")]
impl LinuxBackend {
    fn new() -> Self {
        Self {
            app: crate::linux::LinuxApp::new(linux_data_dir(), "ru", env!("CARGO_PKG_VERSION")),
        }
    }
}

#[cfg(target_os = "linux")]
impl DesktopBackend for LinuxBackend {
    fn command(&self, request_json: &str) -> String {
        self.app.command_json(request_json)
    }
}

#[cfg(target_os = "linux")]
fn linux_data_dir() -> std::path::PathBuf {
    let home = std::env::var_os("HOME")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::current_dir().ok())
        .unwrap_or_else(|| std::path::PathBuf::from("."));
    linux_data_dir_from_home(&home)
}

#[cfg(any(target_os = "linux", test))]
fn linux_data_dir_from_home(home: &std::path::Path) -> std::path::PathBuf {
    let current = home.join(".yptun");
    let legacy = home.join(".olcbox");
    if !current.exists() && legacy.exists() {
        let _ = std::fs::rename(&legacy, &current);
    }
    let _ = std::fs::create_dir_all(&current);
    current
}

#[cfg(not(any(windows, target_os = "linux")))]
struct UnsupportedBackend;

#[cfg(not(any(windows, target_os = "linux")))]
impl DesktopBackend for UnsupportedBackend {
    fn command(&self, _request_json: &str) -> String {
        serde_json::json!({
            "ok": false,
            "data": null,
            "error": "This desktop platform is not supported"
        })
        .to_string()
    }
}

#[cfg(test)]
mod tests {
    use std::sync::mpsc;
    use std::thread;

    use super::{linux_data_dir_from_home, DesktopActor, DesktopBackend};

    #[test]
    fn migrates_legacy_linux_data_directory() {
        let temp = tempfile::tempdir().unwrap();
        let legacy = temp.path().join(".olcbox");
        std::fs::create_dir_all(&legacy).unwrap();
        std::fs::write(legacy.join("locations_v4.json"), "{}").unwrap();

        let current = linux_data_dir_from_home(temp.path());

        assert_eq!(current, temp.path().join(".yptun"));
        assert!(current.join("locations_v4.json").is_file());
        assert!(!legacy.exists());
    }

    struct ThreadBackend {
        caller: thread::ThreadId,
    }

    impl DesktopBackend for ThreadBackend {
        fn command(&self, request_json: &str) -> String {
            assert_ne!(thread::current().id(), self.caller);
            request_json.to_owned()
        }
    }

    #[test]
    fn actor_runs_commands_in_order_off_the_caller_thread() {
        let backend = std::sync::Arc::new(ThreadBackend {
            caller: thread::current().id(),
        });
        let actor = DesktopActor::with_backend(backend);
        let (sender, receiver) = mpsc::channel();

        for value in ["first", "second", "third"] {
            let sender = sender.clone();
            actor.command(value.into(), move |response| sender.send(response).unwrap());
        }
        drop(sender);

        assert_eq!(
            receiver.iter().collect::<Vec<_>>(),
            ["first", "second", "third"]
        );
    }
}
