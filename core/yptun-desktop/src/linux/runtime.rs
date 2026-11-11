use std::fs;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use yptun_core::desktop_engine::{DesktopEngineController, EngineHost};
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_proxy::{
    command_failed_error, LinuxProxyController, LinuxProxyEnable, ProxyCommandRunner,
};
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_stores::DesktopSocksSettingsStore;
use yptun_core::desktop_vpn_manager::{can_connect_to_socks, DesktopMode};
use yptun_core::desktop_vpn_probe::{resolve_bypass_server_ips, resolve_ipv4, BypassInputs};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::desktop_vpn_start::{session_socks_settings, udp_over_tcp};
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};
use yptun_core::linux_tun::{
    self, LinuxTunState, AUTH_READY_TIMEOUT_MS, ROUTE_CLEANUP_TIMEOUT_MS, TUN_READY_POLL_MS,
};
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, TYPE_TRUSTTUNNEL};
use yptun_core::vpn_settings::VpnSettings;
use yptun_core::yptun_core::YpTunCore;
use yptun_core::yptun_core_native::load_desktop;

use super::host::{LinuxAssets, LinuxEngineHost, SharedCore};

#[derive(Debug, Clone, PartialEq, Eq)]
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

    fn is_connected(&self) -> bool {
        matches!(self, Self::Connected)
    }
}

#[derive(Debug, Clone)]
pub struct LinuxRuntimeSnapshot {
    pub status: String,
    pub status_label: String,
    pub status_detail: String,
    pub is_connected: bool,
    pub runtime_available: bool,
    pub active_engine: String,
    pub connection_mode: String,
    pub engine_alive: bool,
    pub external_tun: bool,
}

pub struct LinuxRuntime {
    app_data_dir: PathBuf,
    device_id: String,
    journal: Arc<LogJournal>,
    core: Arc<YpTunCore>,
    controller: DesktopEngineController<SharedCore, LinuxEngineHost>,
    assets: LinuxAssets,
    system_proxy: LinuxSystemProxy,
    http_bridge: Option<HttpProxyBridge>,
    tun: LinuxTun,
    status: RuntimeStatus,
    active_engine: Option<EngineType>,
    connection_mode: String,
}

impl LinuxRuntime {
    pub fn new(app_data_dir: PathBuf, device_id: String, journal: Arc<LogJournal>) -> Self {
        let assets = LinuxAssets::new(&app_data_dir);
        let core_dir = assets.core_directory();
        let core = load_desktop(&core_dir, core_dir.to_str());
        let sink = Arc::clone(&journal);
        core.add_log_sink(move |line| {
            let stamp =
                yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
            sink.add(&format!("{stamp} {line}"));
        });
        let shared = SharedCore(Arc::clone(&core));
        let host = LinuxEngineHost::new(
            app_data_dir.clone(),
            assets.clone(),
            Arc::clone(&journal),
            shared.clone(),
        );
        let mut system_proxy = LinuxSystemProxy::new();
        system_proxy.clear_stale();
        Self {
            app_data_dir: app_data_dir.clone(),
            device_id,
            journal: Arc::clone(&journal),
            core,
            controller: DesktopEngineController::new(shared, host),
            assets,
            system_proxy,
            http_bridge: None,
            tun: LinuxTun::new(app_data_dir, journal),
            status: RuntimeStatus::Disconnected,
            active_engine: None,
            connection_mode: String::new(),
        }
    }

    pub fn is_available(&self) -> bool {
        self.core.is_available()
    }

    pub fn is_connected(&self) -> bool {
        self.status.is_connected()
    }

    pub fn start(&mut self, location: &LocationConfig, mode: &str) -> Result<(), String> {
        if self.is_connected() {
            return Ok(());
        }
        if let Some(reason) = unsupported_reason(location, mode) {
            self.status = RuntimeStatus::Error(reason.clone());
            return Err(reason);
        }
        if !self.core.is_available() {
            let reason = self.unavailable_error();
            self.status = RuntimeStatus::Error(reason.clone());
            return Err(reason);
        }

        self.status = RuntimeStatus::Connecting;
        self.connection_mode = mode.to_string();
        self.active_engine = Some(location.engine);
        let result = self.start_inner(location, mode);
        match result {
            Ok(()) => {
                self.status = RuntimeStatus::Connected;
                self.log(if mode == "proxy" {
                    "Desktop Linux proxy connected"
                } else {
                    "Desktop Linux TUN connected"
                });
                Ok(())
            }
            Err(error) => {
                self.cleanup();
                self.status = RuntimeStatus::Error(error.clone());
                Err(error)
            }
        }
    }

    fn start_inner(&mut self, location: &LocationConfig, mode: &str) -> Result<(), String> {
        let configured = DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Linux)
            .load()
            .normalized(DesktopOs::Linux);
        let mut session_logs = Vec::new();
        let socks = session_socks(mode, configured, &mut |message| session_logs.push(message));
        for message in session_logs {
            self.log(&message);
        }

        let bypass = if mode == "tun" {
            self.tun.clear_stale(&self.assets)?;
            let physical = physical_interface_index();
            self.core.bind_outbound_interface(physical, true);
            if physical > 0 {
                self.log(&format!(
                    "Pinning Xray sockets to network interface #{physical}"
                ));
            } else {
                self.log("Could not identify the physical network interface; direct traffic may loop through the TUN");
            }
            self.resolve_bypass(location)
        } else {
            self.core.bind_outbound_interface(0, true);
            Vec::new()
        };

        self.controller.start(
            location,
            &socks.host,
            socks.port,
            &socks.username,
            &socks.password,
            &self.device_id,
            None,
        )?;

        if mode == "proxy" {
            self.start_proxy(&socks)
        } else {
            self.tun.start(
                &self.assets,
                socks.port,
                &socks.username,
                &socks.password,
                &bypass,
                udp_over_tcp(location.engine),
            )
        }
    }

    fn start_proxy(&mut self, socks: &DesktopSocksProxySettings) -> Result<(), String> {
        let bridge_port = u16::try_from(http_port_for(socks.port))
            .map_err(|_| "Invalid HTTP proxy port".to_string())?;
        let socks_port =
            u16::try_from(socks.port).map_err(|_| "Invalid SOCKS proxy port".to_string())?;
        let journal = Arc::clone(&self.journal);
        let bridge = HttpProxyBridge::new(
            &socks.host,
            bridge_port,
            &socks.host,
            socks_port,
            move |line| {
                let stamp =
                    yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
                journal.add(&format!("{stamp} {line}"));
            },
        );
        if !bridge.start() {
            return Err(format!("HTTP proxy port {bridge_port} is already in use"));
        }
        self.log(&format!(
            "Proxy mode: SOCKS5 {}:{} · HTTP {}:{}",
            socks.host, socks.port, socks.host, bridge_port
        ));
        self.http_bridge = Some(bridge);

        if VpnSettings::new(&self.app_data_dir)
            .load_app_behavior()
            .set_system_proxy
        {
            match self
                .system_proxy
                .enable(&format!("{}:{bridge_port}", socks.host))?
            {
                LinuxProxyEnable::Applied => self.log("Linux system proxy enabled"),
                LinuxProxyEnable::ManualSetup { host_port } => self.log(&format!(
                    "No GNOME/KDE system proxy API found; configure HTTP proxy {host_port} manually"
                )),
            }
        } else {
            self.log("Proxy mode: system proxy left untouched — use the local listeners manually");
        }
        Ok(())
    }

    fn resolve_bypass(&self, location: &LocationConfig) -> Vec<String> {
        let routing = VpnSettings::new(&self.app_data_dir).load_routing();
        let inputs = BypassInputs {
            warp_pinned_host: String::new(),
            telegram_proxy_enabled: false,
            telegram_candidate_hosts: Vec::new(),
            bypass_lan: routing.bypass_lan,
            ..BypassInputs::default()
        };
        let host = self.controller.host();
        let mut asn_prefixes = |numbers: &[&str]| {
            let keys = numbers
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>();
            let values = host.resolve_asns(&keys);
            keys.iter()
                .map(|key| values.get(key).cloned().unwrap_or_default())
                .collect()
        };
        let mut resolver = |name: &str| resolve_ipv4(name);
        let journal = Arc::clone(&self.journal);
        let mut logger = move |message: String| {
            let stamp =
                yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
            journal.add(&format!("{stamp} {message}"));
        };
        resolve_bypass_server_ips(
            location,
            &inputs,
            &mut asn_prefixes,
            &mut resolver,
            &mut logger,
        )
    }

    pub fn stop(&mut self) -> Result<(), String> {
        self.status = RuntimeStatus::Stopping;
        self.cleanup();
        self.status = RuntimeStatus::Disconnected;
        self.active_engine = None;
        self.connection_mode.clear();
        self.log("Desktop Linux runtime stopped");
        Ok(())
    }

    fn cleanup(&mut self) {
        self.tun.stop();
        self.system_proxy.restore();
        if let Some(bridge) = self.http_bridge.take() {
            bridge.stop();
        }
        self.controller.stop_all();
        self.core.bind_outbound_interface(0, true);
    }

    pub fn snapshot(
        &self,
        selected_name: Option<&str>,
        selected_mode: &str,
    ) -> LinuxRuntimeSnapshot {
        let active_engine = self
            .active_engine
            .map(engine_name)
            .unwrap_or_default()
            .to_string();
        let engine_alive = self
            .active_engine
            .is_some_and(|engine| self.controller.core_running(engine));
        let status_detail = match &self.status {
            RuntimeStatus::Disconnected => selected_name
                .map(|name| format!("Готово к подключению: {name}"))
                .unwrap_or_else(|| "Добавьте и выберите конфигурацию".into()),
            RuntimeStatus::Connecting => selected_name
                .map(|name| format!("Запуск {name}"))
                .unwrap_or_else(|| "Запуск VPN".into()),
            RuntimeStatus::Connected => selected_name
                .map(|name| format!("Трафик идёт через {name}"))
                .unwrap_or_else(|| "VPN активен".into()),
            RuntimeStatus::Stopping => "Останавливаем Linux VPN".into(),
            RuntimeStatus::Error(message) => message.clone(),
        };
        LinuxRuntimeSnapshot {
            status: self.status.wire_name().into(),
            status_label: self.status.label().into(),
            status_detail,
            is_connected: self.status.is_connected(),
            runtime_available: self.is_available(),
            active_engine,
            connection_mode: if self.connection_mode.is_empty() {
                selected_mode.to_string()
            } else {
                self.connection_mode.clone()
            },
            engine_alive,
            external_tun: self.tun.is_active(),
        }
    }

    fn unavailable_error(&self) -> String {
        format!(
            "Linux native core is unavailable; set YPTUN_NATIVE_DIR or install {} in one of: {}",
            yptun_core::yptun_core_native::current_library_file_name()
                .unwrap_or_else(|| "the platform yptuncore library".into()),
            self.assets
                .directories()
                .iter()
                .map(|path| path.display().to_string())
                .collect::<Vec<_>>()
                .join(", ")
        )
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
    }
}

impl Drop for LinuxRuntime {
    fn drop(&mut self) {
        self.cleanup();
    }
}

fn session_socks(
    mode: &str,
    settings: DesktopSocksProxySettings,
    log: &mut dyn FnMut(String),
) -> DesktopSocksProxySettings {
    let mode = if mode == "proxy" {
        DesktopMode::SystemProxy
    } else {
        DesktopMode::LinuxTun
    };
    session_socks_settings(mode, &settings, can_connect_to_socks, log)
}

pub fn unsupported_reason(location: &LocationConfig, mode: &str) -> Option<String> {
    if !matches!(mode, "proxy" | "tun") {
        return Some(format!("Unsupported Linux connection mode: {mode}"));
    }
    if location.engine == EngineType::VkTurn {
        return Some("VK-TURN is not supported by the Rust Linux runtime yet".into());
    }
    if location.warp_enabled {
        return Some("WARP wrapping is not supported by the Rust Linux runtime yet".into());
    }
    for profile in [location.proxy.as_ref(), location.proxy2.as_ref()]
        .into_iter()
        .flatten()
    {
        if profile.kind == TYPE_TRUSTTUNNEL {
            return Some("Trust Tunnel is not supported by the Rust Linux runtime yet".into());
        }
        if profile.kind == "mieru" {
            return Some("Mieru is available only in the Android runtime".into());
        }
    }
    let tunnel_proxy_links = [
        location
            .master_dns
            .as_ref()
            .map(|config| config.proxy_link.as_str()),
        location
            .open_flux
            .as_ref()
            .map(|config| config.proxy_link.as_str()),
        location
            .snolc
            .as_ref()
            .map(|config| config.proxy_link.as_str()),
    ];
    for profile in tunnel_proxy_links
        .into_iter()
        .flatten()
        .filter(|link| !link.trim().is_empty())
        .filter_map(parse_proxy_link)
    {
        if profile.kind == TYPE_TRUSTTUNNEL {
            return Some("Trust Tunnel is not supported by the Rust Linux runtime yet".into());
        }
        if profile.kind == "mieru" {
            return Some("Mieru is available only in the Android runtime".into());
        }
    }
    None
}

fn parse_proxy_link(link: &str) -> Option<yptun_core::model::ProxyProfile> {
    yptun_core::share_link::parse(link).or_else(|| {
        yptun_core::panel::parse_inbound(link)
            .and_then(|location| location.proxy.or(location.proxy2))
    })
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

struct SystemCommandRunner;

impl ProxyCommandRunner for SystemCommandRunner {
    fn executable_exists(&self, name: &str) -> bool {
        executable_exists(name)
    }

    fn run(&mut self, command: &[String]) -> Result<String, String> {
        let (program, arguments) = command
            .split_first()
            .ok_or_else(|| "empty command".to_string())?;
        let output = Command::new(program)
            .args(arguments)
            .output()
            .map_err(|error| error.to_string())?;
        let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
        text.push_str(&String::from_utf8_lossy(&output.stderr));
        if output.status.success() {
            Ok(text)
        } else {
            Err(command_failed_error(
                command,
                output.status.code().unwrap_or(-1),
                text.trim(),
            ))
        }
    }
}

struct LinuxSystemProxy {
    controller: LinuxProxyController,
    runner: SystemCommandRunner,
}

impl LinuxSystemProxy {
    fn new() -> Self {
        Self {
            controller: LinuxProxyController::new(),
            runner: SystemCommandRunner,
        }
    }

    fn enable(&mut self, host_port: &str) -> Result<LinuxProxyEnable, String> {
        self.controller.enable(&mut self.runner, host_port)
    }

    fn restore(&mut self) {
        self.controller.restore(&mut self.runner)
    }

    fn clear_stale(&mut self) {
        self.controller.clear_stale_proxy(&mut self.runner)
    }
}

fn executable_exists(name: &str) -> bool {
    std::env::var_os("PATH")
        .into_iter()
        .flat_map(|path| std::env::split_paths(&path).collect::<Vec<_>>())
        .map(|directory| directory.join(name))
        .any(|path| path.is_file())
}

fn is_root() -> bool {
    Command::new("id")
        .arg("-u")
        .output()
        .ok()
        .is_some_and(|output| linux_tun::uid_is_root(&String::from_utf8_lossy(&output.stdout)))
}

fn physical_interface_index() -> i32 {
    let Some(output) = Command::new("ip")
        .args(["route", "get", "8.8.8.8"])
        .output()
        .ok()
        .filter(|output| output.status.success())
    else {
        return 0;
    };
    let text = String::from_utf8_lossy(&output.stdout);
    let Some(device) = route_device(&text) else {
        return 0;
    };
    fs::read_to_string(Path::new("/sys/class/net").join(device).join("ifindex"))
        .ok()
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(0)
}

fn route_device(output: &str) -> Option<&str> {
    let words = output.split_whitespace().collect::<Vec<_>>();
    words
        .windows(2)
        .find(|pair| pair[0] == "dev")
        .map(|pair| pair[1])
}

struct LinuxTun {
    app_data_dir: PathBuf,
    journal: Arc<LogJournal>,
    state: LinuxTunState,
    launcher: Option<Child>,
}

struct TunLaunchPlan {
    command: Vec<String>,
}

impl LinuxTun {
    fn new(app_data_dir: PathBuf, journal: Arc<LogJournal>) -> Self {
        Self {
            app_data_dir,
            journal,
            state: LinuxTunState::new(),
            launcher: None,
        }
    }

    fn is_active(&self) -> bool {
        self.state.routes_installed || self.launcher.is_some()
    }

    fn prepare(
        &mut self,
        assets: &LinuxAssets,
        socks_port: i32,
        socks_username: &str,
        socks_password: &str,
        bypass: &[String],
        udp_over_tcp: bool,
    ) -> Result<TunLaunchPlan, String> {
        let hev = assets.hev_binary()?;
        let up_script = self.app_data_dir.join(linux_tun::UP_SCRIPT);
        let down_script = self.app_data_dir.join(linux_tun::DOWN_SCRIPT);
        let config = self.app_data_dir.join(linux_tun::CONFIG_FILE);
        let run_script = self.app_data_dir.join(linux_tun::RUN_SCRIPT);
        fs::create_dir_all(&self.app_data_dir).map_err(|error| error.to_string())?;
        fs::write(&up_script, linux_tun::up_script_content(bypass))
            .map_err(|error| error.to_string())?;
        fs::write(&down_script, linux_tun::down_script_content())
            .map_err(|error| error.to_string())?;
        fs::write(
            &config,
            linux_tun::config_content(
                u16::try_from(socks_port).map_err(|_| "Invalid SOCKS port".to_string())?,
                up_script.to_str(),
                down_script.to_str(),
                socks_username,
                socks_password,
                udp_over_tcp,
            ),
        )
        .map_err(|error| error.to_string())?;
        let hev_command = linux_tun::hev_command(&hev.to_string_lossy(), &config.to_string_lossy());
        fs::write(
            &run_script,
            linux_tun::run_script_content(&hev_command, &down_script.to_string_lossy()),
        )
        .map_err(|error| error.to_string())?;
        make_executable(&up_script);
        make_executable(&down_script);
        make_executable(&run_script);
        self.state.prepared(&hev.to_string_lossy());
        let command = linux_tun::privilege_command(
            &linux_tun::run_command(&run_script.to_string_lossy()),
            is_root(),
            std::env::var(linux_tun::PRIVILEGE_ENV).ok().as_deref(),
            executable_exists("pkexec"),
        );
        Ok(TunLaunchPlan { command })
    }

    fn start(
        &mut self,
        assets: &LinuxAssets,
        socks_port: i32,
        socks_username: &str,
        socks_password: &str,
        bypass: &[String],
        udp_over_tcp: bool,
    ) -> Result<(), String> {
        let plan = self.prepare(
            assets,
            socks_port,
            socks_username,
            socks_password,
            bypass,
            udp_over_tcp,
        )?;
        self.log(&linux_tun::start_log(
            &assets
                .hev_binary()?
                .file_name()
                .unwrap_or_default()
                .to_string_lossy(),
            u16::try_from(socks_port).map_err(|_| "Invalid SOCKS port".to_string())?,
        ));
        let (program, arguments) = plan
            .command
            .split_first()
            .ok_or_else(|| "empty Linux TUN command".to_string())?;
        let mut child = Command::new(program)
            .args(arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|error| error.to_string())?;
        if let Some(stdout) = child.stdout.take() {
            spawn_tun_reader(stdout, Arc::clone(&self.journal));
        }
        if let Some(stderr) = child.stderr.take() {
            spawn_tun_reader(stderr, Arc::clone(&self.journal));
        }
        let deadline = Instant::now() + Duration::from_millis(AUTH_READY_TIMEOUT_MS);
        loop {
            if interface_exists() && route_rule_exists() {
                let message = self.state.ready();
                self.log(&message);
                self.launcher = Some(child);
                return Ok(());
            }
            if let Ok(Some(status)) = child.try_wait() {
                return Err(self.state.launcher_failed(status.code().unwrap_or(-1)));
            }
            if Instant::now() >= deadline {
                drop(child.stdin.take());
                return Err(linux_tun::not_installed_error());
            }
            thread::sleep(Duration::from_millis(TUN_READY_POLL_MS));
        }
    }

    fn clear_stale(&mut self, assets: &LinuxAssets) -> Result<(), String> {
        if !interface_exists() && !route_rule_exists() {
            return Ok(());
        }
        let hev = assets.hev_binary()?;
        self.log(&linux_tun::stale_log());
        let down_script = self.app_data_dir.join(linux_tun::DOWN_SCRIPT);
        fs::write(&down_script, linux_tun::down_script_content())
            .map_err(|error| error.to_string())?;
        make_executable(&down_script);
        let clear_script = self.app_data_dir.join(linux_tun::CLEAR_SCRIPT);
        fs::write(
            &clear_script,
            linux_tun::clear_script_content(&hev.to_string_lossy(), &down_script.to_string_lossy()),
        )
        .map_err(|error| error.to_string())?;
        make_executable(&clear_script);
        let command = linux_tun::privilege_command(
            &linux_tun::run_command(&clear_script.to_string_lossy()),
            is_root(),
            std::env::var(linux_tun::PRIVILEGE_ENV).ok().as_deref(),
            executable_exists("pkexec"),
        );
        run_status(&command, Duration::from_millis(AUTH_READY_TIMEOUT_MS))?;
        let deadline = Instant::now() + Duration::from_millis(ROUTE_CLEANUP_TIMEOUT_MS);
        while interface_exists() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(TUN_READY_POLL_MS));
        }
        Ok(())
    }

    fn stop(&mut self) {
        if let Some(mut child) = self.launcher.take() {
            drop(child.stdin.take());
            let deadline = Instant::now() + Duration::from_millis(linux_tun::RELEASE_TIMEOUT_MS);
            while Instant::now() < deadline {
                if child.try_wait().ok().flatten().is_some() {
                    self.state.released(true);
                    return;
                }
                thread::sleep(Duration::from_millis(25));
            }
            let _ = child.kill();
            let _ = child.wait();
        }
        let commands = self.state.privileged_cleanup_commands(
            &self
                .app_data_dir
                .join(linux_tun::DOWN_SCRIPT)
                .to_string_lossy(),
        );
        if commands.is_empty() {
            return;
        }
        let script = self.app_data_dir.join("linux-tun-stop.sh");
        let body = format!("#!/bin/sh\n{}\n", commands.join("\n"));
        if fs::write(&script, body).is_ok() {
            make_executable(&script);
            let command = linux_tun::privilege_command(
                &linux_tun::run_command(&script.to_string_lossy()),
                is_root(),
                std::env::var(linux_tun::PRIVILEGE_ENV).ok().as_deref(),
                executable_exists("pkexec"),
            );
            let _ = run_status(&command, Duration::from_millis(AUTH_READY_TIMEOUT_MS));
        }
        self.state.stopped();
        self.state.released(true);
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
    }
}

fn make_executable(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(metadata) = fs::metadata(path) {
            let mut permissions = metadata.permissions();
            permissions.set_mode(permissions.mode() | 0o100);
            let _ = fs::set_permissions(path, permissions);
        }
    }
    #[cfg(not(unix))]
    let _ = path;
}

fn interface_exists() -> bool {
    Command::new("ip")
        .args(["link", "show", yptun_core::desktop_net::LINUX_TUN_NAME])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

fn route_rule_exists() -> bool {
    Command::new("ip")
        .args(["rule", "show"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .is_some_and(|output| {
            linux_tun::route_rule_present(&String::from_utf8_lossy(&output.stdout))
        })
}

fn run_status(command: &[String], timeout: Duration) -> Result<(), String> {
    let (program, arguments) = command
        .split_first()
        .ok_or_else(|| "empty command".to_string())?;
    let mut child = Command::new(program)
        .args(arguments)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| error.to_string())?;
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child.try_wait().map_err(|error| error.to_string())? {
            return if status.success() {
                Ok(())
            } else {
                Err(format!("{} exited with {status}", command.join(" ")))
            };
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            return Err(format!("{} timed out", command.join(" ")));
        }
        thread::sleep(Duration::from_millis(25));
    }
}

fn spawn_tun_reader(reader: impl std::io::Read + Send + 'static, journal: Arc<LogJournal>) {
    let _ = thread::Builder::new()
        .name("linux-tun-log".into())
        .spawn(move || {
            for line in BufReader::new(reader).lines().map_while(Result::ok) {
                if !line.trim().is_empty() {
                    let stamp = yptun_core::desktop_app::format_timestamp(
                        yptun_core::desktop_app::now_ms(),
                    );
                    journal.add(&format!("{stamp} [hev] {line}"));
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    use yptun_core::model::ProxyProfile;

    #[test]
    fn unsupported_matrix_is_explicit() {
        let mut location = LocationConfig {
            engine: EngineType::VkTurn,
            ..LocationConfig::default()
        };
        assert!(unsupported_reason(&location, "proxy")
            .unwrap()
            .contains("VK-TURN"));

        location.engine = EngineType::Standard;
        location.warp_enabled = true;
        assert!(unsupported_reason(&location, "tun")
            .unwrap()
            .contains("WARP"));

        location.warp_enabled = false;
        location.proxy = Some(ProxyProfile {
            kind: TYPE_TRUSTTUNNEL.into(),
            ..ProxyProfile::default()
        });
        assert!(unsupported_reason(&location, "proxy")
            .unwrap()
            .contains("Trust Tunnel"));
        assert!(unsupported_reason(&LocationConfig::default(), "invalid").is_some());
    }

    #[test]
    fn prioritized_engines_are_admitted_in_proxy_and_tun_modes() {
        for engine in [
            EngineType::Standard,
            EngineType::Stealth,
            EngineType::Chain,
            EngineType::MasterDns,
            EngineType::OpenFlux,
            EngineType::Snolc,
        ] {
            let location = LocationConfig {
                engine,
                ..LocationConfig::default()
            };
            assert_eq!(unsupported_reason(&location, "proxy"), None, "{engine:?}");
            assert_eq!(unsupported_reason(&location, "tun"), None, "{engine:?}");
        }
    }

    #[test]
    fn proxy_mode_drops_local_auth_but_tun_keeps_it() {
        let settings = DesktopSocksProxySettings {
            username: "user".into(),
            password: "pass".into(),
            secured: true,
            ..DesktopSocksProxySettings::default()
        };
        let proxy = session_socks("proxy", settings.clone(), &mut |_| {});
        assert!(proxy.username.is_empty());
        assert!(proxy.password.is_empty());
        let tun = session_socks("tun", settings, &mut |_| {});
        assert_eq!(tun.username, "user");
        assert_eq!(tun.password, "pass");
    }

    #[test]
    fn parses_physical_interface_from_ip_route() {
        assert_eq!(
            route_device("8.8.8.8 via 192.168.1.1 dev enp3s0 src 192.168.1.9"),
            Some("enp3s0")
        );
        assert_eq!(route_device("unreachable 8.8.8.8"), None);
    }

    #[test]
    fn tun_plan_uses_privileged_wrapper_and_shared_helpers() {
        let temp = tempfile::tempdir().unwrap();
        let hev = temp.path().join("hev-socks5-tunnel");
        fs::write(&hev, "stub").unwrap();
        let assets = LinuxAssets {
            directories: vec![temp.path().to_path_buf()],
        };
        let journal = Arc::new(LogJournal::new());
        let mut tun = LinuxTun::new(temp.path().to_path_buf(), journal);
        let plan = tun
            .prepare(&assets, 10808, "u", "p", &["203.0.113.8".into()], true)
            .unwrap();
        assert!(plan
            .command
            .iter()
            .any(|part| part.ends_with("linux-tun-run.sh")));
        assert!(temp.path().join("linux-tun-down.sh").is_file());
        let config = fs::read_to_string(temp.path().join(linux_tun::CONFIG_FILE)).unwrap();
        assert!(config.contains("port: 10808"));
        assert!(config.contains("username: 'u'"));
        let up = fs::read_to_string(temp.path().join(linux_tun::UP_SCRIPT)).unwrap();
        assert!(up.contains("203.0.113.8"));
    }

    #[test]
    fn runtime_status_transitions_report_errors_and_disconnect() {
        let error = RuntimeStatus::Error("boom".into());
        assert_eq!(error.wire_name(), "error");
        assert!(!error.is_connected());
        assert_eq!(RuntimeStatus::Connected.wire_name(), "connected");
        assert!(RuntimeStatus::Connected.is_connected());
        assert_eq!(RuntimeStatus::Disconnected.label(), "Отключено");
    }

    #[test]
    fn tun_cleanup_state_covers_binary_and_routes() {
        let mut state = LinuxTunState::new();
        state.prepared("/opt/yptun/hev-socks5-tunnel");
        assert!(!state.ready().is_empty());
        let commands = state.privileged_cleanup_commands("/tmp/linux-tun-down.sh");
        assert_eq!(commands.len(), 2);
        assert!(commands[0].contains("pkill"));
        assert!(commands[1].contains("linux-tun-down.sh"));
        assert!(state.stopped());
        state.released(true);
        assert!(!state.routes_installed);
        assert!(state.hev_binary.is_none());
    }
}
