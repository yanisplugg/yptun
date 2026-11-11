use std::collections::BTreeMap;
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::net::{IpAddr, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use yptun_core::desktop_engine::{
    CsqttUp, DesktopEngineController, EngineCore, EngineHost, TunRequest,
};
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_stores::DesktopSocksSettingsStore;
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::desktop_vpn_start::split_tunnel_processes;
use yptun_core::geo_assets::{self, AsnResolver, GeoPlatform, HttpGet};
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, ProxyProfile};
use yptun_core::panel;
use yptun_core::room_pool::RoomSpec;
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::transports::{OpenFluxConfig, SnolcConfig};
use yptun_core::vpn_settings::VpnSettings;
use yptun_core::windows_tun::{
    install_routes_script, remove_routes_script, tun2socks_command, TUN_READY_POLL_MS,
    TUN_READY_TIMEOUT_MS,
};
use yptun_core::yptun_core::{CoreResult, MasterDnsStart, YpTunCore};
use yptun_core::yptun_core_native::load_desktop;

use crate::logging;
use crate::proxy_windows::SystemProxy;
use crate::store::device_identity;

const LOOPBACK: &str = "127.0.0.1";
const EXTERNAL_TUN_STATE_FILE: &str = "windows_tun_state.json";

#[derive(Debug, Default, Serialize, Deserialize)]
struct ExternalTunState {
    bypass_addresses: Vec<String>,
}

#[derive(Clone)]
struct SharedCore(Arc<YpTunCore>);

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

#[derive(Clone)]
struct WindowsEngineHost {
    app_data_dir: PathBuf,
    journal: Arc<LogJournal>,
    asn: Arc<AsnResolver>,
}

impl WindowsEngineHost {
    fn new(app_data_dir: PathBuf, journal: Arc<LogJournal>) -> Self {
        let asn = Arc::new(AsnResolver::new(
            app_data_dir.join(geo_assets::ASN_CACHE_DIR),
            GeoPlatform::Desktop,
        ));
        Self {
            app_data_dir,
            journal,
            asn,
        }
    }

    fn push_log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
        logging::append(&self.app_data_dir, message);
    }

    fn unsupported(&self, name: &str) -> String {
        format!("{name} пока не подключён к нативному WinUI-хосту")
    }
}

impl EngineHost for WindowsEngineHost {
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
            .map(|items| {
                let mut addresses = Vec::new();
                for address in items {
                    if !addresses.contains(&address.ip()) {
                        addresses.push(address.ip());
                    }
                }
                addresses
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
        Vec::new()
    }
    fn parse_inbound_link(&self, link: &str) -> Option<ProxyProfile> {
        panel::parse_inbound(link).and_then(|location| location.proxy.or(location.proxy2))
    }
    fn ensure_geo_assets(&self, geoip_url: &str, geosite_url: &str) -> bool {
        let Ok(dir) = geo_assets::asset_dir(&self.app_data_dir, GeoPlatform::Desktop) else {
            return false;
        };
        let mut http = BytesHttp::new();
        geo_assets::ensure_assets(
            &mut http,
            GeoPlatform::Desktop,
            &dir,
            geoip_url,
            geosite_url,
        )
    }
    fn geo_asset_dir(&self) -> String {
        self.app_data_dir.join("geo").to_string_lossy().into_owned()
    }
    fn resolve_asns(&self, asns: &[String]) -> BTreeMap<String, Vec<String>> {
        let mut http = BytesHttp::new();
        self.asn.ensure(&mut http, asns, "")
    }
    fn warp_load_config(&self) -> Option<String> {
        fs::read_to_string(self.app_data_dir.join("warp.conf")).ok()
    }
    fn warp_save_config(&self, ini: &str) {
        let _ = fs::write(self.app_data_dir.join("warp.conf"), ini);
    }
    fn warp_generate(&self, _official_first: bool) -> Result<String, String> {
        Err(self.unsupported("Cloudflare WARP registration"))
    }
    fn room_pool_start(
        &self,
        _specs: &[RoomSpec],
        _host: &str,
        _port: i32,
        _user: &str,
        _pass: &str,
    ) -> bool {
        false
    }
    fn room_pool_stop(&self) {}
    fn trust_tunnel_start(&self, _tt_config: &str, _port: i32) -> Result<(), String> {
        Err(self.unsupported("Trust Tunnel"))
    }
    fn trust_tunnel_await_ready(&self, _port: i32) -> bool {
        false
    }
    fn trust_tunnel_stop(&self) {}
    fn open_flux_start(
        &self,
        _config: &OpenFluxConfig,
        _host: &str,
        _port: i32,
        _user: &str,
        _pass: &str,
    ) -> Result<(), String> {
        Err(self.unsupported("OpenFlux"))
    }
    fn open_flux_running(&self) -> bool {
        false
    }
    fn open_flux_exit_description(&self) -> String {
        String::new()
    }
    fn open_flux_stop(&self) {}
    fn snolc_start(
        &self,
        _config: &SnolcConfig,
        _host: &str,
        _port: i32,
        _user: &str,
        _pass: &str,
    ) -> Result<(), String> {
        Err(self.unsupported("snolc"))
    }
    fn snolc_running(&self) -> bool {
        false
    }
    fn snolc_exit_description(&self) -> String {
        String::new()
    }
    fn snolc_stop(&self) {}
    fn csqtt_binaries(&self) -> Result<(String, String), String> {
        Err(self.unsupported("csqtt"))
    }
    fn csqtt_launch(&self, _bridge_exe: &str, _options_json: &str) -> Result<(), String> {
        Err(self.unsupported("csqtt"))
    }
    fn csqtt_await_ready(&self, _timeout_ms: u64) -> Option<CsqttUp> {
        None
    }
    fn csqtt_no_tunnel_message(&self) -> String {
        self.unsupported("csqtt")
    }
    fn csqtt_running(&self) -> bool {
        false
    }
    fn csqtt_stop(&self) {}
    fn open_browser_window(&self, url: &str) -> bool {
        Command::new("rundll32.exe")
            .arg("url.dll,FileProtocolHandler")
            .arg(url)
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
        let client = Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(60))
            .build()
            .expect("HTTP client");
        Self { client }
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

pub struct WindowsRuntime {
    core: Arc<YpTunCore>,
    controller: DesktopEngineController<SharedCore, WindowsEngineHost>,
    app_data_dir: PathBuf,
    native_dir: PathBuf,
    journal: Arc<LogJournal>,
    http_bridge: Option<HttpProxyBridge>,
    system_proxy: SystemProxy,
    tun_process: Option<Child>,
    tun_bypass_addresses: Vec<String>,
    connected: bool,
    active_engine: Option<EngineType>,
}

impl WindowsRuntime {
    pub fn new(app_data_dir: PathBuf, native_dir: PathBuf, journal: Arc<LogJournal>) -> Self {
        let core = load_desktop(&native_dir, native_dir.to_str());
        let sink_journal = journal.clone();
        core.add_log_sink(move |line| {
            let stamp =
                yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
            sink_journal.add(&format!("{stamp} {line}"));
        });
        let host = WindowsEngineHost::new(app_data_dir.clone(), journal.clone());
        let controller = DesktopEngineController::new(SharedCore(core.clone()), host);
        let mut system_proxy = SystemProxy::new();
        system_proxy.clear_stale();
        let mut runtime = Self {
            core,
            controller,
            app_data_dir,
            native_dir,
            journal,
            http_bridge: None,
            system_proxy,
            tun_process: None,
            tun_bypass_addresses: Vec::new(),
            connected: false,
            active_engine: None,
        };
        let _ = runtime.cleanup_stale_external_tun();
        runtime
    }

    pub fn is_available(&self) -> bool {
        self.core.is_available()
    }

    pub fn start(&mut self, location: &LocationConfig, mode: &str) -> Result<(), String> {
        self.stop()?;
        if !self.is_available() {
            return Err("yptuncore native library is unavailable".into());
        }
        let engine_supported = match location.engine {
            EngineType::Standard
            | EngineType::Chain
            | EngineType::Stealth
            | EngineType::MasterDns => true,
            EngineType::VkTurn => {
                is_proxy_mode(mode)
                    && !location
                        .vkturn
                        .as_ref()
                        .is_some_and(|config| config.uses_csqtt())
            }
            EngineType::OpenFlux | EngineType::Snolc => false,
        };
        if !engine_supported {
            return Err(format!(
                "{} пока не подключён к нативному WinUI-хосту",
                engine_name(location.engine)
            ));
        }

        let socks_store = DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Windows);
        let mut socks = socks_store.load().normalized(DesktopOs::Windows);
        let is_proxy = is_proxy_mode(mode);
        if is_proxy {
            socks.username.clear();
            socks.password.clear();
        }

        let pin_udp = location.engine != EngineType::VkTurn;
        self.core
            .bind_outbound_interface(if is_proxy { 0 } else { best_interface_index() }, pin_udp);
        let split = VpnSettings::new(&self.app_data_dir).load_split_tunnel();
        let processes = split_tunnel_processes(&split);
        let exclude_addresses = bypass_addresses(location, &self.controller.host().load_routing());
        let tun = (!is_proxy).then(|| TunRequest {
            split_mode: split.mode,
            processes,
            exclude_addresses: exclude_addresses.clone(),
        });
        let identity = device_identity(&self.app_data_dir);
        if let Err(error) = self.controller.start(
            location,
            &socks.host,
            socks.port,
            &socks.username,
            &socks.password,
            &identity,
            tun,
        ) {
            self.controller.stop_all();
            self.core.bind_outbound_interface(0, true);
            return Err(error);
        }

        if !is_proxy && !self.controller.tun_handled_in_core() {
            if let Err(error) = self.start_external_tun(&socks, &exclude_addresses) {
                self.controller.stop_all();
                self.core.bind_outbound_interface(0, true);
                return Err(error);
            }
        }

        if is_proxy {
            if let Err(error) = self.start_system_proxy(&socks) {
                self.controller.stop_all();
                self.core.bind_outbound_interface(0, true);
                return Err(error);
            }
        }
        self.connected = true;
        self.active_engine = Some(location.engine);
        self.log(if is_proxy {
            "Windows proxy mode connected"
        } else if self.controller.tun_handled_in_core() {
            "Windows TUN connected through sing-box"
        } else {
            "Windows TUN connected through tun2socks"
        });
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), String> {
        let mut first_error = None;
        if let Err(error) = self.system_proxy.restore() {
            first_error = Some(error);
        }
        if let Some(bridge) = self.http_bridge.take() {
            bridge.stop();
        }
        if let Err(error) = self.stop_external_tun() {
            if first_error.is_none() {
                first_error = Some(error);
            }
        }
        self.controller.stop_all();
        self.core.bind_outbound_interface(0, true);
        self.connected = false;
        self.active_engine = None;
        match first_error {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    fn start_system_proxy(&mut self, socks: &DesktopSocksProxySettings) -> Result<(), String> {
        let port =
            u16::try_from(http_port_for(socks.port)).map_err(|_| "invalid HTTP proxy port")?;
        let socks_port = u16::try_from(socks.port).map_err(|_| "invalid SOCKS proxy port")?;
        let host = LOOPBACK;
        let journal = self.journal.clone();
        let bridge = HttpProxyBridge::new(host, port, &socks.host, socks_port, move |line| {
            let stamp =
                yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
            journal.add(&format!("{stamp} {line}"));
        });
        if !bridge.start() {
            return Err(format!("HTTP proxy port {port} is already in use"));
        }
        if VpnSettings::new(&self.app_data_dir)
            .load_app_behavior()
            .set_system_proxy
        {
            if let Err(error) = self.system_proxy.enable(&format!("{host}:{port}")) {
                bridge.stop();
                return Err(error);
            }
        } else {
            self.log(&format!(
                "Proxy mode: point applications at http://{host}:{port}"
            ));
        }
        self.http_bridge = Some(bridge);
        Ok(())
    }

    fn start_external_tun(
        &mut self,
        socks: &DesktopSocksProxySettings,
        bypass_addresses: &[String],
    ) -> Result<(), String> {
        if !yptun_core::desktop_app::is_elevated() {
            return Err("Режим TUN требует прав администратора".into());
        }
        self.cleanup_stale_external_tun()?;
        let arch = if cfg!(target_arch = "aarch64") {
            "arm64"
        } else {
            "amd64"
        };
        let binary = self
            .native_dir
            .join(format!("tun2socks-windows-{arch}.exe"));
        if !binary.is_file() {
            return Err(format!("Не найден {}", binary.display()));
        }
        let command = tun2socks_command(
            &binary.to_string_lossy(),
            u16::try_from(socks.port).map_err(|_| "invalid SOCKS port")?,
            &socks.username,
            &socks.password,
        );
        let mut builder = Command::new(&command[0]);
        builder
            .args(&command[1..])
            .current_dir(&self.native_dir)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .stdin(Stdio::null());
        hide_console(&mut builder);
        let mut child = builder
            .spawn()
            .map_err(|error| format!("tun2socks start failed: {error}"))?;
        pump_process_output("tun", child.stdout.take(), self.journal.clone());
        pump_process_output("tun", child.stderr.take(), self.journal.clone());

        let deadline = std::time::Instant::now() + Duration::from_millis(TUN_READY_TIMEOUT_MS);
        while std::time::Instant::now() < deadline {
            if let Some(status) = child.try_wait().map_err(|error| error.to_string())? {
                let _ = child.wait();
                return Err(format!(
                    "tun2socks exited before YPtun adapter was ready ({status})"
                ));
            }
            if interface_alias_exists("YPtun") {
                break;
            }
            thread::sleep(Duration::from_millis(TUN_READY_POLL_MS));
        }
        if !interface_alias_exists("YPtun") {
            let _ = child.kill();
            let _ = child.wait();
            return Err("YPtun adapter was not created".into());
        }

        if let Err(error) = self.save_external_tun_state(bypass_addresses) {
            let _ = child.kill();
            let _ = child.wait();
            return Err(error);
        }
        if let Err(error) = self.run_tun_script(&install_routes_script(bypass_addresses)) {
            let _ = child.kill();
            let _ = child.wait();
            let _ = self.run_tun_script(&remove_routes_script(bypass_addresses));
            self.clear_external_tun_state();
            return Err(error);
        }
        self.tun_bypass_addresses = bypass_addresses.to_vec();
        self.tun_process = Some(child);
        Ok(())
    }

    fn stop_external_tun(&mut self) -> Result<(), String> {
        let mut error = None;
        if self.tun_process.is_some() || !self.tun_bypass_addresses.is_empty() {
            if let Err(message) =
                self.run_tun_script(&remove_routes_script(&self.tun_bypass_addresses))
            {
                error = Some(message);
            }
        }
        self.tun_bypass_addresses.clear();
        if let Some(mut child) = self.tun_process.take() {
            stop_child(&mut child);
        }
        self.clear_external_tun_state();
        match error {
            Some(message) => Err(message),
            None => Ok(()),
        }
    }

    fn run_tun_script(&self, script: &str) -> Result<(), String> {
        let directory = self.app_data_dir.join("runtime");
        fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
        let path = directory.join(format!(
            "yptun-tun-{}-{}.ps1",
            std::process::id(),
            now_nanos()
        ));
        fs::write(&path, script).map_err(|error| error.to_string())?;
        let output = Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
            ])
            .arg(&path)
            .output();
        let _ = fs::remove_file(&path);
        let output = output.map_err(|error| format!("PowerShell start failed: {error}"))?;
        if output.status.success() {
            Ok(())
        } else {
            let message = String::from_utf8_lossy(&output.stderr).trim().to_string();
            Err(format!(
                "PowerShell failed with {}: {message}",
                output.status
            ))
        }
    }

    fn external_tun_state_path(&self) -> PathBuf {
        self.app_data_dir
            .join("runtime")
            .join(EXTERNAL_TUN_STATE_FILE)
    }

    fn save_external_tun_state(&self, bypass_addresses: &[String]) -> Result<(), String> {
        let path = self.external_tun_state_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let text = serde_json::to_string(&ExternalTunState {
            bypass_addresses: bypass_addresses.to_vec(),
        })
        .map_err(|error| error.to_string())?;
        fs::write(path, text).map_err(|error| error.to_string())
    }

    fn clear_external_tun_state(&self) {
        let _ = fs::remove_file(self.external_tun_state_path());
    }

    fn cleanup_stale_external_tun(&mut self) -> Result<(), String> {
        let path = self.external_tun_state_path();
        if !path.is_file() {
            return Ok(());
        }
        if !yptun_core::desktop_app::is_elevated() {
            return Ok(());
        }
        let state = fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<ExternalTunState>(&text).ok())
            .unwrap_or_default();
        self.run_tun_script(&remove_routes_script(&state.bypass_addresses))?;
        self.clear_external_tun_state();
        self.log("Cleaned stale Windows TUN routes from the previous run");
        Ok(())
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
        logging::append(&self.app_data_dir, message);
    }
}

fn pump_process_output<R: Read + Send + 'static>(
    prefix: &'static str,
    stream: Option<R>,
    journal: Arc<LogJournal>,
) {
    let Some(stream) = stream else { return };
    let _ = thread::Builder::new()
        .name(format!("{prefix}-log"))
        .spawn(move || {
            for line in BufReader::new(stream).lines().map_while(Result::ok) {
                if line.trim().is_empty() {
                    continue;
                }
                let stamp =
                    yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
                journal.add(&format!("{stamp} {prefix}: {}", line.trim_end()));
            }
        });
}

fn stop_child(child: &mut Child) {
    if child.try_wait().ok().flatten().is_some() {
        return;
    }
    let _ = child.kill();
    let _ = child.wait();
}

fn now_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_nanos())
        .unwrap_or(0)
}

#[cfg(windows)]
fn hide_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_console(_command: &mut Command) {}

#[cfg(windows)]
fn interface_alias_exists(alias: &str) -> bool {
    #[link(name = "iphlpapi")]
    unsafe extern "system" {
        fn ConvertInterfaceAliasToLuid(alias: *const u16, luid: *mut u64) -> u32;
        fn ConvertInterfaceLuidToIndex(luid: *const u64, index: *mut u32) -> u32;
    }
    let wide: Vec<u16> = alias.encode_utf16().chain(std::iter::once(0)).collect();
    let mut luid = 0u64;
    let mut index = 0u32;
    // SAFETY: all pointers refer to live buffers of the sizes required by iphlpapi.
    unsafe {
        ConvertInterfaceAliasToLuid(wide.as_ptr(), &mut luid) == 0
            && ConvertInterfaceLuidToIndex(&luid, &mut index) == 0
            && index > 0
    }
}

#[cfg(not(windows))]
fn interface_alias_exists(_alias: &str) -> bool {
    false
}

fn bypass_addresses(location: &LocationConfig, routing: &RoutingRules) -> Vec<String> {
    let mut output = Vec::new();
    let mut hosts = Vec::new();
    if let Some(proxy) = &location.proxy {
        if !proxy.server.trim().is_empty() && proxy.server != LOOPBACK {
            hosts.push((proxy.server.clone(), proxy.server_port));
        }
    }
    if let Some(master_dns) = &location.master_dns {
        hosts.extend(
            master_dns
                .resolver_hosts()
                .into_iter()
                .map(|host| (host, 53)),
        );
    }
    if let Some(snolc) = &location.snolc {
        hosts.push((snolc.host.clone(), snolc.port));
    }
    for (host, port) in hosts {
        let Ok(port) = u16::try_from(port) else {
            continue;
        };
        if let Ok(addresses) = (host.as_str(), port).to_socket_addrs() {
            for address in addresses.filter(|address| address.is_ipv4()) {
                let ip = address.ip().to_string();
                if !output.contains(&ip) {
                    output.push(ip);
                }
            }
        }
    }
    if routing.bypass_lan {
        output.extend(
            ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]
                .into_iter()
                .map(str::to_string),
        );
    }
    output
}

fn is_proxy_mode(mode: &str) -> bool {
    mode == "proxy"
}

#[cfg(windows)]
fn best_interface_index() -> i32 {
    #[link(name = "iphlpapi")]
    unsafe extern "system" {
        fn GetBestInterface(destination: u32, best_interface_index: *mut u32) -> u32;
    }
    let destination = u32::from_be_bytes([8, 8, 8, 8]);
    let mut index = 0u32;
    // SAFETY: the output pointer refers to a live u32 and GetBestInterface writes only that value.
    let result = unsafe { GetBestInterface(destination, &mut index) };
    if result == 0 {
        index as i32
    } else {
        0
    }
}

#[cfg(not(windows))]
fn best_interface_index() -> i32 {
    0
}

fn engine_name(engine: EngineType) -> &'static str {
    match engine {
        EngineType::Stealth => "Stealth",
        EngineType::Standard => "Standard proxy",
        EngineType::Chain => "Chain",
        EngineType::VkTurn => "VK-TURN",
        EngineType::MasterDns => "MasterDNS",
        EngineType::OpenFlux => "OpenFlux",
        EngineType::Snolc => "snolc",
    }
}
