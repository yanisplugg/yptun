use std::collections::BTreeMap;
use std::fs;
use std::io::{BufRead, BufReader};
use std::net::{IpAddr, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use yptun_core::desktop_cores::{
    exit_description, openflux_command, openflux_env, openflux_needs_key_file, openflux_start_log,
    snolc_command, snolc_files, snolc_start_log, ProcessState, OPENFLUX_KEY_FILE_DELETE_AFTER_MS,
    SNOLC_DIR, SNOLC_EXIT_ENV, SNOLC_MODULES_DIR, SNOLC_STATE_DIR,
};
use yptun_core::desktop_engine::{CsqttUp, EngineCore, EngineHost};
use yptun_core::desktop_native_assets::{
    hev_socks5_tunnel_file_name, openflux_file_name, snolc_file_name, DesktopOs,
};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::geo_assets::{self, AsnResolver, GeoPlatform, HttpGet};
use yptun_core::location::LocationConfig;
use yptun_core::model::ProxyProfile;
use yptun_core::panel;
use yptun_core::room_pool::{
    RoomCore, RoomPool, RoomSpec, DEFAULT_MAX_ROOMS, DEFAULT_READY_TIMEOUT_MS,
};
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::transports::{OpenFluxConfig, SnolcConfig};
use yptun_core::vpn_settings::VpnSettings;
use yptun_core::yptun_core::{CoreResult, MasterDnsStart, YpTunCore};
use yptun_core::yptun_core_native;

const LOOPBACK: &str = "127.0.0.1";

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

#[derive(Debug, Clone)]
pub struct LinuxAssets {
    pub(crate) directories: Vec<PathBuf>,
}

impl LinuxAssets {
    pub fn new(app_data_dir: &Path) -> Self {
        let mut directories = Vec::new();
        if let Some(path) = std::env::var_os("YPTUN_NATIVE_DIR").map(PathBuf::from) {
            directories.push(path);
        }
        if let Ok(executable) = std::env::current_exe() {
            if let Some(parent) = executable.parent() {
                directories.extend([
                    parent.join("Native"),
                    parent.join("native"),
                    parent.to_path_buf(),
                ]);
            }
        }
        directories.push(app_data_dir.join("bin"));
        if let Ok(cwd) = std::env::current_dir() {
            for root in cwd.ancestors().take(5) {
                directories.extend([
                    root.join("YPtun")
                        .join("desktopApp")
                        .join("build")
                        .join("generated")
                        .join("desktopNativeResources")
                        .join("native"),
                    root.join("desktopApp")
                        .join("build")
                        .join("generated")
                        .join("desktopNativeResources")
                        .join("native"),
                ]);
            }
        }
        let mut distinct = Vec::new();
        for directory in directories {
            if !distinct.contains(&directory) {
                distinct.push(directory);
            }
        }
        Self {
            directories: distinct,
        }
    }

    pub fn directories(&self) -> &[PathBuf] {
        &self.directories
    }

    pub fn find(&self, file_name: &str) -> Result<PathBuf, String> {
        self.directories
            .iter()
            .map(|directory| directory.join(file_name))
            .find(|path| path.is_file())
            .ok_or_else(|| {
                format!(
                    "Native component {file_name} was not found; searched {}",
                    self.directories
                        .iter()
                        .map(|path| path.display().to_string())
                        .collect::<Vec<_>>()
                        .join(", ")
                )
            })
    }

    pub fn core_directory(&self) -> PathBuf {
        let Some(file_name) = yptun_core_native::current_library_file_name() else {
            return self
                .directories
                .first()
                .cloned()
                .unwrap_or_else(|| PathBuf::from("."));
        };
        self.directories
            .iter()
            .find(|directory| directory.join(&file_name).is_file())
            .cloned()
            .or_else(|| self.directories.first().cloned())
            .unwrap_or_else(|| PathBuf::from("."))
    }

    pub fn hev_binary(&self) -> Result<PathBuf, String> {
        if let Some(path) = std::env::var_os("HEV_SOCKS5_TUNNEL_BINARY").map(PathBuf::from) {
            if path.is_file() {
                return Ok(path);
            }
        }
        let name = hev_socks5_tunnel_file_name(DesktopOs::Linux, std::env::consts::ARCH)?;
        self.find(&name).or_else(|_| self.find("hev-socks5-tunnel"))
    }
}

#[derive(Clone)]
pub struct SharedCore(pub Arc<YpTunCore>);

impl RoomCore for SharedCore {
    fn start_room(
        &self,
        carrier: &str,
        transport: &str,
        room: &str,
        client_id: &str,
        key_hex: &str,
        user: &str,
        pass: &str,
        ready_timeout_ms: i32,
    ) -> i64 {
        self.0
            .rtc_start_room(
                carrier,
                transport,
                room,
                client_id,
                key_hex,
                user,
                pass,
                ready_timeout_ms,
            )
            .unwrap_or(-1)
    }

    fn room_port(&self, handle: i64) -> i32 {
        self.0.rtc_room_port(handle)
    }

    fn room_healthy(&self, handle: i64) -> bool {
        self.0.rtc_room_healthy(handle)
    }

    fn stop_room(&self, handle: i64) {
        self.0.rtc_stop_room(handle)
    }

    fn stop_all_rooms(&self) {
        self.0.rtc_stop_all_rooms()
    }

    fn rooms_running(&self) -> i32 {
        self.0.rtc_rooms_running()
    }
}

impl EngineCore for SharedCore {
    fn rtc_set_socks_listen_host(&self, host: &str) -> CoreResult<()> {
        self.0.rtc_set_socks_listen_host(host)
    }
    fn rtc_set_telemost_cookies(&self, cookies: &str) -> CoreResult<()> {
        self.0.rtc_set_telemost_cookies(cookies)
    }
    fn rtc_apply_transport_options(&self, config: &LocationConfig) -> CoreResult<()> {
        self.0.rtc_apply_transport_options(config)
    }
    fn rtc_start(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        socks_user: &str,
        socks_pass: &str,
    ) -> CoreResult<()> {
        self.0.rtc_start(
            carrier, transport, room_id, client_id, key_hex, socks_port, socks_user, socks_pass,
        )
    }
    fn rtc_wait_ready(&self, timeout_ms: i32) -> CoreResult<()> {
        self.0.rtc_wait_ready(timeout_ms)
    }
    fn rtc_running(&self) -> bool {
        self.0.rtc_running()
    }
    fn rtc_rooms_running(&self) -> i32 {
        self.0.rtc_rooms_running()
    }
    fn sb_start(&self, config_json: &str) -> CoreResult<()> {
        self.0.sb_start(config_json)
    }
    fn sb_running(&self) -> bool {
        self.0.sb_running()
    }
    fn xray_set_asset_path(&self, dir: &str) -> CoreResult<()> {
        self.0.xray_set_asset_path(dir)
    }
    fn xray_start(&self, config_json: &str) -> CoreResult<()> {
        self.0.xray_start(config_json)
    }
    fn xray_running(&self) -> bool {
        self.0.xray_running()
    }
    fn awg_start(&self, ini_config: &str, listen_addr: &str) -> CoreResult<()> {
        self.0.awg_start(ini_config, listen_addr)
    }
    fn awg_stop(&self) {
        self.0.awg_stop()
    }
    fn ft_start(
        &self,
        uri: &str,
        listen_addr: &str,
        vk_link: &str,
        n_streams: i32,
    ) -> CoreResult<()> {
        self.0.ft_start(uri, listen_addr, vk_link, n_streams)
    }
    fn ft_running(&self) -> bool {
        self.0.ft_running()
    }
    fn ft_connected_streams(&self) -> i32 {
        self.0.ft_connected_streams()
    }
    fn ft_captcha_url(&self) -> String {
        self.0.ft_captcha_url()
    }
    fn ft_captcha_active(&self) -> bool {
        self.0.ft_captcha_active()
    }
    fn wdtt_start(&self, options_json: &str) -> CoreResult<()> {
        self.0.wdtt_start(options_json)
    }
    fn wdtt_last_error(&self) -> String {
        self.0.wdtt_last_error()
    }
    fn wdtt_wait_config(&self, timeout_ms: i32) -> CoreResult<Option<String>> {
        self.0.wdtt_wait_config(timeout_ms)
    }
    fn wdtt_running(&self) -> bool {
        self.0.wdtt_running()
    }
    fn master_dns_start(&self, args: &MasterDnsStart) -> CoreResult<()> {
        self.0.master_dns_start(args)
    }
    fn master_dns_stop(&self) {
        self.0.master_dns_stop()
    }
    fn master_dns_running(&self) -> bool {
        self.0.master_dns_running()
    }
    fn stop_all(&self) {
        self.0.stop_all()
    }
}

#[derive(Default)]
struct ProcessSlot {
    child: Mutex<Option<Child>>,
    last_exit: Mutex<Option<i32>>,
}

impl ProcessSlot {
    fn start(
        &self,
        command: &[String],
        cwd: Option<&Path>,
        environment: &[(String, String)],
        prefix: &'static str,
        journal: Arc<LogJournal>,
    ) -> Result<(), String> {
        self.stop();
        let (program, arguments) = command
            .split_first()
            .ok_or_else(|| "empty process command".to_string())?;
        let mut builder = Command::new(program);
        builder
            .args(arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(cwd) = cwd {
            builder.current_dir(cwd);
        }
        for (name, value) in environment {
            builder.env(name, value);
        }
        let mut child = builder.spawn().map_err(|error| error.to_string())?;
        if let Some(stdout) = child.stdout.take() {
            spawn_log_reader(stdout, prefix, Arc::clone(&journal));
        }
        if let Some(stderr) = child.stderr.take() {
            spawn_log_reader(stderr, prefix, journal);
        }
        *lock(&self.last_exit) = None;
        *lock(&self.child) = Some(child);
        Ok(())
    }

    fn is_running(&self) -> bool {
        let mut child = lock(&self.child);
        let Some(process) = child.as_mut() else {
            return false;
        };
        match process.try_wait() {
            Ok(None) => true,
            Ok(Some(status)) => {
                *lock(&self.last_exit) = status.code();
                false
            }
            Err(_) => false,
        }
    }

    fn state(&self) -> ProcessState {
        if self.is_running() {
            ProcessState::Alive
        } else {
            (*lock(&self.last_exit))
                .map(ProcessState::Exited)
                .unwrap_or(ProcessState::NotStarted)
        }
    }

    fn stop(&self) {
        let Some(mut child) = lock(&self.child).take() else {
            return;
        };
        drop(child.stdin.take());
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    *lock(&self.last_exit) = status.code();
                    return;
                }
                _ if std::time::Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(25));
                }
                _ => break,
            }
        }
        let _ = child.kill();
        let status = child.wait().ok().and_then(|status| status.code());
        *lock(&self.last_exit) = status;
    }
}

fn spawn_log_reader(
    reader: impl std::io::Read + Send + 'static,
    prefix: &'static str,
    journal: Arc<LogJournal>,
) {
    let _ = thread::Builder::new()
        .name(format!("{prefix}-log"))
        .spawn(move || {
            for line in BufReader::new(reader).lines().map_while(Result::ok) {
                if !line.trim().is_empty() {
                    let stamp = yptun_core::desktop_app::format_timestamp(
                        yptun_core::desktop_app::now_ms(),
                    );
                    journal.add(&format!("{stamp} {prefix}: {line}"));
                }
            }
        });
}

#[derive(Clone)]
pub struct LinuxEngineHost {
    app_data_dir: PathBuf,
    assets: LinuxAssets,
    journal: Arc<LogJournal>,
    asn: Arc<AsnResolver>,
    core: SharedCore,
    room_pool: Arc<Mutex<Option<RoomPool>>>,
    open_flux: Arc<ProcessSlot>,
    snolc: Arc<ProcessSlot>,
}

impl LinuxEngineHost {
    pub fn new(
        app_data_dir: PathBuf,
        assets: LinuxAssets,
        journal: Arc<LogJournal>,
        core: SharedCore,
    ) -> Self {
        Self {
            asn: Arc::new(AsnResolver::new(
                app_data_dir.join(geo_assets::ASN_CACHE_DIR),
                GeoPlatform::Desktop,
            )),
            app_data_dir,
            assets,
            journal,
            core,
            room_pool: Arc::new(Mutex::new(None)),
            open_flux: Arc::new(ProcessSlot::default()),
            snolc: Arc::new(ProcessSlot::default()),
        }
    }

    fn push_log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
    }
}

impl EngineHost for LinuxEngineHost {
    fn log(&self, line: &str) {
        self.push_log(line)
    }
    fn app_data_dir(&self) -> PathBuf {
        self.app_data_dir.clone()
    }
    fn load_traffic(&self) -> TrafficSettings {
        VpnSettings::new(&self.app_data_dir).load_traffic()
    }
    fn load_routing(&self) -> RoutingRules {
        VpnSettings::new(&self.app_data_dir).load_routing()
    }
    fn load_routing_profiles(&self) -> RoutingProfilesState {
        VpnSettings::new(&self.app_data_dir).load_routing_profiles()
    }
    fn load_app_behavior(&self) -> AppBehaviorSettings {
        VpnSettings::new(&self.app_data_dir).load_app_behavior()
    }
    fn now_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|value| value.as_millis() as u64)
            .unwrap_or(0)
    }
    fn sleep_ms(&self, ms: u64) {
        thread::sleep(Duration::from_millis(ms))
    }
    fn is_local_port_open(&self, port: i32) -> bool {
        let Ok(port) = u16::try_from(port) else {
            return false;
        };
        let Ok(address) = format!("{LOOPBACK}:{port}").parse() else {
            return false;
        };
        TcpStream::connect_timeout(&address, Duration::from_millis(250)).is_ok()
    }
    fn resolve_all(&self, host: &str) -> Vec<IpAddr> {
        (host, 0)
            .to_socket_addrs()
            .map(|addresses| {
                let mut unique = Vec::new();
                for address in addresses {
                    if !unique.contains(&address.ip()) {
                        unique.push(address.ip());
                    }
                }
                unique
            })
            .unwrap_or_default()
    }
    fn file_size(&self, path: &Path) -> Option<u64> {
        fs::metadata(path).ok().map(|metadata| metadata.len())
    }
    fn delete_file(&self, path: &Path) {
        let _ = fs::remove_file(path);
    }
    fn running_process_names(&self) -> Vec<String> {
        #[cfg(target_os = "linux")]
        {
            let Ok(entries) = fs::read_dir("/proc") else {
                return Vec::new();
            };
            let mut names = Vec::new();
            for entry in entries.flatten() {
                if !entry
                    .file_name()
                    .to_string_lossy()
                    .chars()
                    .all(|c| c.is_ascii_digit())
                {
                    continue;
                }
                if let Ok(name) = fs::read_to_string(entry.path().join("comm")) {
                    let name = name.trim().to_string();
                    if !name.is_empty() && !names.contains(&name) {
                        names.push(name);
                    }
                }
            }
            names
        }
        #[cfg(not(target_os = "linux"))]
        Vec::new()
    }
    fn parse_inbound_link(&self, link: &str) -> Option<ProxyProfile> {
        panel::parse_inbound(link).and_then(|location| location.proxy.or(location.proxy2))
    }
    fn ensure_geo_assets(&self, geoip_url: &str, geosite_url: &str) -> bool {
        let Ok(directory) = geo_assets::asset_dir(&self.app_data_dir, GeoPlatform::Desktop) else {
            return false;
        };
        geo_assets::ensure_assets(
            &mut BytesHttp::new(),
            GeoPlatform::Desktop,
            &directory,
            geoip_url,
            geosite_url,
        )
    }
    fn geo_asset_dir(&self) -> String {
        self.app_data_dir.join("geo").to_string_lossy().into_owned()
    }
    fn resolve_asns(&self, asns: &[String]) -> BTreeMap<String, Vec<String>> {
        self.asn.ensure(&mut BytesHttp::new(), asns, "")
    }
    fn warp_load_config(&self) -> Option<String> {
        fs::read_to_string(self.app_data_dir.join("warp.conf")).ok()
    }
    fn warp_save_config(&self, ini: &str) {
        let _ = fs::write(self.app_data_dir.join("warp.conf"), ini);
    }
    fn warp_generate(&self, official_first: bool) -> Result<String, String> {
        let _ = official_first;
        Err("WARP wrapping is not supported by the Rust Linux runtime yet".into())
    }
    fn room_pool_start(
        &self,
        specs: &[RoomSpec],
        host: &str,
        port: i32,
        user: &str,
        pass: &str,
    ) -> bool {
        self.room_pool_stop();
        let Ok(port) = u16::try_from(port) else {
            return false;
        };
        let logger = self.clone();
        let pool = RoomPool::new(Arc::new(self.core.clone()), move |line| {
            logger.push_log(line)
        });
        if !pool.start(
            specs,
            host,
            port,
            user,
            pass,
            DEFAULT_MAX_ROOMS,
            DEFAULT_READY_TIMEOUT_MS,
        ) {
            return false;
        }
        *lock(&self.room_pool) = Some(pool);
        true
    }
    fn room_pool_stop(&self) {
        if let Some(pool) = lock(&self.room_pool).take() {
            pool.stop();
        }
    }
    fn trust_tunnel_start(&self, _tt_config: &str, _port: i32) -> Result<(), String> {
        Err("Trust Tunnel is not supported by the Rust Linux runtime yet".into())
    }
    fn trust_tunnel_await_ready(&self, _port: i32) -> bool {
        false
    }
    fn trust_tunnel_stop(&self) {}
    fn open_flux_start(
        &self,
        config: &OpenFluxConfig,
        host: &str,
        port: i32,
        user: &str,
        pass: &str,
    ) -> Result<(), String> {
        self.open_flux_stop();
        let name = openflux_file_name(DesktopOs::Linux, std::env::consts::ARCH)?;
        let binary = self.assets.find(&name)?;
        let runtime_dir = self.app_data_dir.join("runtime");
        fs::create_dir_all(&runtime_dir).map_err(|error| error.to_string())?;
        let key_file = if openflux_needs_key_file(config) {
            let path = runtime_dir.join(format!(
                "openflux-key-{}-{}.txt",
                std::process::id(),
                self.now_ms()
            ));
            fs::write(&path, &config.secret).map_err(|error| error.to_string())?;
            Some(path)
        } else {
            None
        };
        let command = openflux_command(
            &binary.to_string_lossy(),
            config,
            host,
            port,
            key_file
                .as_ref()
                .map(|path| path.to_string_lossy())
                .as_deref(),
        );
        self.push_log(&openflux_start_log(config, host, port));
        if let Err(error) = self.open_flux.start(
            &command,
            binary.parent(),
            &openflux_env(config, user, pass),
            "openflux",
            Arc::clone(&self.journal),
        ) {
            if let Some(path) = key_file {
                let _ = fs::remove_file(path);
            }
            return Err(error);
        }
        if let Some(path) = key_file {
            let _ = thread::Builder::new()
                .name("openflux-key-cleanup".into())
                .spawn(move || {
                    thread::sleep(Duration::from_millis(OPENFLUX_KEY_FILE_DELETE_AFTER_MS));
                    let _ = fs::remove_file(path);
                });
        }
        Ok(())
    }
    fn open_flux_running(&self) -> bool {
        self.open_flux.is_running()
    }
    fn open_flux_exit_description(&self) -> String {
        exit_description(self.open_flux.state())
    }
    fn open_flux_stop(&self) {
        self.open_flux.stop()
    }
    fn snolc_start(
        &self,
        config: &SnolcConfig,
        host: &str,
        port: i32,
        user: &str,
        pass: &str,
    ) -> Result<(), String> {
        self.snolc_stop();
        let name = snolc_file_name(DesktopOs::Linux, std::env::consts::ARCH)?;
        let binary = self.assets.find(&name)?;
        let directory = self.app_data_dir.join(SNOLC_DIR);
        fs::create_dir_all(directory.join(SNOLC_STATE_DIR))
            .and_then(|_| fs::create_dir_all(directory.join(SNOLC_MODULES_DIR)))
            .map_err(|error| error.to_string())?;
        let resolved_host = self
            .resolve_all(&config.host)
            .into_iter()
            .find(IpAddr::is_ipv4)
            .or_else(|| self.resolve_all(&config.host).into_iter().next())
            .map(|address| address.to_string())
            .unwrap_or_else(|| config.host.clone());
        for (name, body) in snolc_files(config, &resolved_host, host, port, user, pass) {
            let path = directory.join(name);
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent).map_err(|error| error.to_string())?;
            }
            fs::write(path, body).map_err(|error| error.to_string())?;
        }
        self.push_log(&snolc_start_log(config, host, port));
        self.snolc.start(
            &snolc_command(&binary.to_string_lossy(), &directory),
            Some(&directory),
            &[(SNOLC_EXIT_ENV.0.into(), SNOLC_EXIT_ENV.1.into())],
            "snolc",
            Arc::clone(&self.journal),
        )
    }
    fn snolc_running(&self) -> bool {
        self.snolc.is_running()
    }
    fn snolc_exit_description(&self) -> String {
        exit_description(self.snolc.state())
    }
    fn snolc_stop(&self) {
        self.snolc.stop()
    }
    fn csqtt_binaries(&self) -> Result<(String, String), String> {
        Err("VK-TURN csqtt is not supported by the Rust Linux runtime yet".into())
    }
    fn csqtt_launch(&self, _bridge_exe: &str, _options_json: &str) -> Result<(), String> {
        Err("VK-TURN csqtt is not supported by the Rust Linux runtime yet".into())
    }
    fn csqtt_await_ready(&self, _timeout_ms: u64) -> Option<CsqttUp> {
        None
    }
    fn csqtt_no_tunnel_message(&self) -> String {
        "VK-TURN csqtt is not supported by the Rust Linux runtime yet".into()
    }
    fn csqtt_running(&self) -> bool {
        false
    }
    fn csqtt_stop(&self) {}
    fn open_browser_window(&self, url: &str) -> bool {
        Command::new("xdg-open")
            .arg(url)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .is_ok()
    }
    fn show_captcha_toast(&self) {
        self.push_log("VK captcha requires attention")
    }
}

struct BytesHttp {
    client: Client,
}

impl BytesHttp {
    fn new() -> Self {
        Self {
            client: Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(15))
                .timeout(Duration::from_secs(60))
                .build()
                .expect("HTTP client"),
        }
    }
}

impl HttpGet for BytesHttp {
    fn get(
        &mut self,
        url: &str,
        headers: &[(&str, &str)],
    ) -> Result<geo_assets::HttpResponse, String> {
        let mut request = self.client.get(url);
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let response = request.send().map_err(|error| error.to_string())?;
        let status = response.status().as_u16();
        let location = response
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|value| value.to_str().ok())
            .map(str::to_string);
        let body = response
            .bytes()
            .map_err(|error| error.to_string())?
            .to_vec();
        Ok(geo_assets::HttpResponse {
            status,
            location,
            body,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asset_search_reports_every_candidate() {
        let temp = tempfile::tempdir().unwrap();
        let assets = LinuxAssets {
            directories: vec![temp.path().join("one"), temp.path().join("two")],
        };
        let error = assets.find("missing.so").unwrap_err();
        assert!(error.contains("missing.so"));
        assert!(error.contains("one"));
        assert!(error.contains("two"));
    }

    #[test]
    fn process_state_text_is_stable_without_starting_a_process() {
        let slot = ProcessSlot::default();
        assert_eq!(slot.state(), ProcessState::NotStarted);
        slot.stop();
        assert_eq!(slot.state(), ProcessState::NotStarted);
    }
}
