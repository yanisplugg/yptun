use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::net::{IpAddr, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use yptun_core::desktop_api::RuntimeDiagnostics;
use yptun_core::desktop_cores::{
    openflux_command, openflux_env, openflux_needs_key_file, openflux_start_log, snolc_command,
    snolc_files, snolc_start_log, tt_await_tunnel_ready, tt_client_command, tt_compose_config,
    tt_wizard_command, tt_wizard_result, TrustTunnelLinks, OPENFLUX_KEY_FILE_DELETE_AFTER_MS,
    SNOLC_DIR, SNOLC_EXIT_ENV, SNOLC_MODULES_DIR, SNOLC_STATE_DIR, TT_CONFIG_FILE, TT_DIR,
    TT_KILL_TIMEOUT_MS, TT_STOP_TIMEOUT_MS, TT_WARMUP_TIMEOUT_MS, TT_WIZARD_ENV,
    TT_WIZARD_SETTINGS_FILE, TT_WIZARD_TIMEOUT_MS,
};
use yptun_core::desktop_engine::{
    CsqttUp, DesktopEngineController, EngineCore, EngineHost, TunRequest,
};
use yptun_core::desktop_native_assets::{
    csqtt_file_names, openflux_file_name, snolc_file_name, trust_tunnel_file_name, DesktopOs,
};
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_store::device_identity;
use yptun_core::desktop_stores::DesktopSocksSettingsStore;
use yptun_core::desktop_vpn_manager::{
    TUNNEL_PROBE_HOST, TUNNEL_PROBE_PORT, WATCHDOG_GRACE_MS, WATCHDOG_INTERVAL_MS,
};
use yptun_core::desktop_vpn_probe::{
    best_of_rounds, icmp_ping, ping_method, random_test_port, resolve_bypass_server_ips,
    resolve_ipv4, socks5_connect_rtt, tcp_ping, BypassInputs, PingMethod, WARP_ENGAGE_HOST,
};
use yptun_core::desktop_vpn_runtime::LogJournal;
use yptun_core::desktop_vpn_start::split_tunnel_processes;
use yptun_core::geo_assets::{self, AsnResolver, GeoPlatform, HttpGet};
use yptun_core::home::FreeServerItem;
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};
use yptun_core::location::LocationConfig;
use yptun_core::model::{
    EngineType, ProxyProfile, TYPE_AMNEZIAWG, TYPE_HYSTERIA2, TYPE_OPENVPN, TYPE_TRUSTTUNNEL,
};
use yptun_core::panel;
use yptun_core::repository::{HttpRequest, HttpResponse};
use yptun_core::room_pool::{
    RoomCore, RoomPool, RoomSpec, DEFAULT_MAX_ROOMS, DEFAULT_READY_TIMEOUT_MS,
};
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::telegram_proxy::{
    self, CoreLogFilter, HealthWatchdog, TelegramProxyState, TelegramTunnel,
};
use yptun_core::transports::{OpenFluxConfig, SnolcConfig};
use yptun_core::vpn_settings::VpnSettings;
use yptun_core::windows_tun::{
    install_routes_script, remove_routes_script, tun2socks_command, TUN_READY_POLL_MS,
    TUN_READY_TIMEOUT_MS,
};
use yptun_core::xray::config::{self as xray_config, XrayOptions};
use yptun_core::yptun_core::{CoreResult, MasterDnsStart, YpTunCore};
use yptun_core::yptun_core_native::load_desktop;
use yptun_core::{warp, warp_account};

use crate::logging;
use crate::proxy_windows::SystemProxy;
use crate::sidecars::{ChildJob, CsqttSidecar, ProcessSlot};

const LOOPBACK: &str = "127.0.0.1";
const EXTERNAL_TUN_STATE_FILE: &str = "windows_tun_state.json";

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

fn native_arch() -> &'static str {
    if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "amd64"
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct ExternalTunState {
    bypass_addresses: Vec<String>,
}

#[derive(Clone)]
struct SharedCore(Arc<YpTunCore>);

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

#[derive(Clone)]
struct WindowsEngineHost {
    app_data_dir: PathBuf,
    native_dir: PathBuf,
    journal: Arc<LogJournal>,
    asn: Arc<AsnResolver>,
    core: SharedCore,
    room_pool: Arc<Mutex<Option<RoomPool>>>,
    open_flux: ProcessSlot,
    snolc: ProcessSlot,
    trust_tunnel: ProcessSlot,
    trust_tunnel_links: Arc<Mutex<TrustTunnelLinks>>,
    csqtt: CsqttSidecar,
}

impl WindowsEngineHost {
    fn new(
        app_data_dir: PathBuf,
        native_dir: PathBuf,
        journal: Arc<LogJournal>,
        core: SharedCore,
    ) -> Self {
        let asn = Arc::new(AsnResolver::new(
            app_data_dir.join(geo_assets::ASN_CACHE_DIR),
            GeoPlatform::Desktop,
        ));
        Self {
            app_data_dir,
            native_dir,
            journal,
            asn,
            core,
            room_pool: Arc::new(Mutex::new(None)),
            open_flux: ProcessSlot::default(),
            snolc: ProcessSlot::default(),
            trust_tunnel: ProcessSlot::default(),
            trust_tunnel_links: Arc::new(Mutex::new(TrustTunnelLinks::new())),
            csqtt: CsqttSidecar::default(),
        }
    }

    fn push_log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
        logging::append(&self.app_data_dir, message);
    }

    fn binary(&self, name: &str) -> Result<PathBuf, String> {
        let path = self.native_dir.join(name);
        if path.is_file() {
            Ok(path)
        } else {
            Err(format!("Не найден нативный компонент {}", path.display()))
        }
    }

    fn trust_tunnel_endpoint_table(&self, deep_link: &str) -> Result<String, String> {
        let host = self.clone();
        lock(&self.trust_tunnel_links)
            .endpoint_table(deep_link, &mut |link| host.decode_trust_tunnel_link(link))
    }

    fn trust_tunnel_endpoint_hosts(&self, deep_link: &str) -> Vec<String> {
        let host = self.clone();
        lock(&self.trust_tunnel_links)
            .endpoint_hosts(deep_link, &mut |link| host.decode_trust_tunnel_link(link))
    }

    fn decode_trust_tunnel_link(&self, deep_link: &str) -> Result<String, String> {
        let wizard_name = trust_tunnel_file_name(DesktopOs::Windows, native_arch(), "wizard")?;
        let wizard = self.binary(&wizard_name)?;
        let directory = self.app_data_dir.join(TT_DIR);
        fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
        let settings = directory.join(TT_WIZARD_SETTINGS_FILE);
        let _ = fs::remove_file(&settings);
        let command = tt_wizard_command(&wizard.to_string_lossy(), deep_link, &settings);
        let outcome = run_command_with_timeout(
            &command,
            &directory,
            &[(TT_WIZARD_ENV.0.to_string(), TT_WIZARD_ENV.1.to_string())],
            Duration::from_millis(TT_WIZARD_TIMEOUT_MS),
        )?;
        if outcome.timed_out {
            return Err(yptun_core::desktop_cores::TT_ERR_WIZARD_TIMEOUT.into());
        }
        let document = fs::read_to_string(&settings).ok();
        tt_wizard_result(outcome.exit_code, document.as_deref(), &outcome.output)
    }

    fn bypass_addresses(&self, location: &LocationConfig) -> Vec<String> {
        let routing = self.load_routing();
        let trust_tunnel_endpoint_hosts = location
            .proxy
            .as_ref()
            .filter(|profile| profile.kind == TYPE_TRUSTTUNNEL)
            .map(|profile| self.trust_tunnel_endpoint_hosts(&profile.tt_config))
            .unwrap_or_default();
        let behavior = self.load_app_behavior();
        let inputs = BypassInputs {
            trust_tunnel_endpoint_hosts,
            warp_pinned_host: warp::pinned_host(WARP_ENGAGE_HOST),
            telegram_proxy_enabled: behavior.telegram_proxy_enabled,
            telegram_candidate_hosts: telegram_proxy::candidate_endpoint_hosts(),
            bypass_lan: routing.bypass_lan,
        };
        let asn = self.asn.clone();
        let mut resolve_asns = |numbers: &[&str]| {
            let owned: Vec<String> = numbers.iter().map(|value| (*value).to_string()).collect();
            let mut http = BytesHttp::new();
            let resolved = asn.ensure(&mut http, &owned, "");
            owned
                .iter()
                .map(|number| resolved.get(number).cloned().unwrap_or_default())
                .collect()
        };
        let mut resolve = |host: &str| resolve_ipv4(host);
        let logger = self.clone();
        let mut log = move |message: String| logger.push_log(&message);
        resolve_bypass_server_ips(location, &inputs, &mut resolve_asns, &mut resolve, &mut log)
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
        let mut command = Command::new("tasklist.exe");
        command.args(["/FO", "CSV", "/NH"]);
        hide_console(&mut command);
        let Ok(output) = command.output() else {
            return Vec::new();
        };
        String::from_utf8_lossy(&output.stdout)
            .lines()
            .filter_map(|line| {
                let value = line.trim().trim_start_matches('\u{feff}');
                value
                    .strip_prefix('"')
                    .and_then(|rest| rest.find('"').map(|end| rest[..end].to_string()))
            })
            .collect()
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
    fn warp_generate(&self, official_first: bool) -> Result<String, String> {
        let http = WarpReqwestHttp::new();
        let logger = self.clone();
        warp_account::generate(&http, official_first, &mut |line| logger.push_log(line))
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
            self.push_log("multiroom: invalid balancer port");
            return false;
        };
        let logger = self.clone();
        let core: Arc<dyn RoomCore> = Arc::new(self.core.clone());
        let pool = RoomPool::new(core, move |line| logger.push_log(line));
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
    fn trust_tunnel_start(&self, tt_config: &str, port: i32) -> Result<(), String> {
        self.trust_tunnel_stop();
        let endpoint = self.trust_tunnel_endpoint_table(tt_config)?;
        let directory = self.app_data_dir.join(TT_DIR);
        fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
        let config_file = directory.join(TT_CONFIG_FILE);
        fs::write(&config_file, tt_compose_config(&endpoint, port))
            .map_err(|error| error.to_string())?;
        let binary_name = trust_tunnel_file_name(DesktopOs::Windows, native_arch(), "client")?;
        let binary = self.binary(&binary_name)?;
        let command = tt_client_command(&binary.to_string_lossy(), &config_file);
        self.push_log(&yptun_core::desktop_cores::tt_start_log(port));
        self.trust_tunnel.start(
            &command,
            Some(&directory),
            &[],
            "trusttunnel",
            self.journal.clone(),
            None,
        )
    }
    fn trust_tunnel_await_ready(&self, port: i32) -> bool {
        let Ok(port) = u16::try_from(port) else {
            return false;
        };
        tt_await_tunnel_ready(port, TT_WARMUP_TIMEOUT_MS, &|| {
            self.trust_tunnel.is_running()
        })
    }
    fn trust_tunnel_stop(&self) {
        self.trust_tunnel.stop(
            None,
            Duration::from_millis(TT_STOP_TIMEOUT_MS),
            Duration::from_millis(TT_KILL_TIMEOUT_MS),
        );
    }
    fn open_flux_start(
        &self,
        config: &OpenFluxConfig,
        host: &str,
        port: i32,
        user: &str,
        pass: &str,
    ) -> Result<(), String> {
        self.open_flux_stop();
        let name = openflux_file_name(DesktopOs::Windows, native_arch())?;
        let binary = self.binary(&name)?;
        let runtime_dir = self.app_data_dir.join("runtime");
        fs::create_dir_all(&runtime_dir).map_err(|error| error.to_string())?;
        let key_file = if openflux_needs_key_file(config) {
            let path = runtime_dir.join(format!(
                "openflux-key-{}-{}.txt",
                std::process::id(),
                now_nanos()
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
        let environment = openflux_env(config, user, pass);
        self.push_log(&openflux_start_log(config, host, port));
        if let Err(error) = self.open_flux.start(
            &command,
            Some(&self.native_dir),
            &environment,
            "openflux",
            self.journal.clone(),
            None,
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
        self.open_flux.exit_description()
    }
    fn open_flux_stop(&self) {
        self.open_flux
            .stop(None, Duration::from_secs(2), Duration::from_secs(2));
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
        let name = snolc_file_name(DesktopOs::Windows, native_arch())?;
        let binary = self.binary(&name)?;
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
        let command = snolc_command(&binary.to_string_lossy(), &directory);
        let environment = vec![(SNOLC_EXIT_ENV.0.to_string(), SNOLC_EXIT_ENV.1.to_string())];
        self.push_log(&snolc_start_log(config, host, port));
        self.snolc.start(
            &command,
            Some(&directory),
            &environment,
            "snolc",
            self.journal.clone(),
            None,
        )
    }
    fn snolc_running(&self) -> bool {
        self.snolc.is_running()
    }
    fn snolc_exit_description(&self) -> String {
        self.snolc.exit_description()
    }
    fn snolc_stop(&self) {
        self.snolc
            .stop(None, Duration::from_secs(2), Duration::from_secs(2));
    }
    fn csqtt_binaries(&self) -> Result<(String, String), String> {
        let (bridge, client) = csqtt_file_names(DesktopOs::Windows, native_arch())?;
        Ok((
            self.binary(&bridge)?.to_string_lossy().into_owned(),
            self.binary(&client)?.to_string_lossy().into_owned(),
        ))
    }
    fn csqtt_launch(&self, bridge_exe: &str, options_json: &str) -> Result<(), String> {
        self.csqtt
            .launch(Path::new(bridge_exe), options_json, self.journal.clone())
    }
    fn csqtt_await_ready(&self, timeout_ms: u64) -> Option<CsqttUp> {
        self.csqtt.await_ready(Duration::from_millis(timeout_ms))
    }
    fn csqtt_no_tunnel_message(&self) -> String {
        self.csqtt.no_tunnel_message()
    }
    fn csqtt_running(&self) -> bool {
        self.csqtt.is_running()
    }
    fn csqtt_stop(&self) {
        self.csqtt.stop()
    }
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

struct WarpReqwestHttp {
    client: Client,
}

#[derive(Clone)]
struct TelegramProxyManager {
    core: Arc<YpTunCore>,
    app_data_dir: PathBuf,
    journal: Arc<LogJournal>,
    generation: Arc<AtomicU64>,
    state: Arc<Mutex<TelegramProxyState>>,
    log_filter: Arc<Mutex<CoreLogFilter>>,
}

impl TelegramProxyManager {
    fn new(
        core: Arc<YpTunCore>,
        app_data_dir: PathBuf,
        journal: Arc<LogJournal>,
        log_filter: Arc<Mutex<CoreLogFilter>>,
    ) -> Self {
        Self {
            core,
            app_data_dir,
            journal,
            generation: Arc::new(AtomicU64::new(0)),
            state: Arc::new(Mutex::new(TelegramProxyState::Stopped)),
            log_filter,
        }
    }

    fn configure(&self, enabled: bool) {
        let token = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.core.tg_awg_stop();
        if !enabled {
            *lock(&self.state) = TelegramProxyState::Stopped;
            self.log("Telegram proxy: stopped");
            return;
        }
        *lock(&self.state) = TelegramProxyState::Generating;
        let manager = self.clone();
        let _ = thread::Builder::new()
            .name("telegram-warp".into())
            .spawn(move || manager.run(token));
    }

    fn run(&self, token: u64) {
        let creds = match telegram_proxy::get_or_create_credentials(&self.app_data_dir) {
            Ok(value) => value,
            Err(error) => {
                self.fail(token, &error);
                return;
            }
        };
        let config = match telegram_proxy::load_config(&self.app_data_dir) {
            Some(value) => value,
            None => {
                self.log(telegram_proxy::MSG_GENERATING);
                let http = WarpReqwestHttp::new();
                let logger = self.clone();
                match warp_account::generate(&http, false, &mut |line| logger.log(line)) {
                    Ok(value) => {
                        telegram_proxy::save_config(&self.app_data_dir, &value);
                        value
                    }
                    Err(error) => {
                        self.fail(
                            token,
                            &format!("{}: {error}", telegram_proxy::ERR_GENERATION_FAILED),
                        );
                        return;
                    }
                }
            }
        };
        if self.generation.load(Ordering::SeqCst) != token {
            return;
        }
        let mut tunnel = WindowsTelegramTunnel {
            core: self.core.clone(),
            app_data_dir: self.app_data_dir.clone(),
            journal: self.journal.clone(),
            generation: self.generation.clone(),
            token,
            state: self.state.clone(),
            log_filter: self.log_filter.clone(),
        };
        let result = telegram_proxy::initial_bring_up(&config, &creds, &mut tunnel);
        let mut watchdog =
            HealthWatchdog::new(&config, &creds, now_epoch_ms() as i64, result.endpoint());
        while self.generation.load(Ordering::SeqCst) == token {
            for _ in 0..(telegram_proxy::HEALTH_TICK_MS / 250) {
                if self.generation.load(Ordering::SeqCst) != token {
                    return;
                }
                thread::sleep(Duration::from_millis(250));
            }
            watchdog.tick(now_epoch_ms() as i64, &mut tunnel);
        }
    }

    fn fail(&self, token: u64, message: &str) {
        if self.generation.load(Ordering::SeqCst) == token {
            *lock(&self.state) = TelegramProxyState::error(message);
            self.log(&format!("Telegram proxy: {message}"));
        }
    }

    fn log(&self, message: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {message}"));
        logging::append(&self.app_data_dir, message);
    }

    fn status(&self) -> (String, String) {
        match &*lock(&self.state) {
            TelegramProxyState::Stopped => ("stopped".into(), "Отключён".into()),
            TelegramProxyState::Generating => {
                ("generating".into(), "Получение WARP-конфигурации…".into())
            }
            TelegramProxyState::Running {
                host, port, user, ..
            } => (
                "running".into(),
                format!("SOCKS5 {host}:{port}, пользователь {user}"),
            ),
            TelegramProxyState::Error { message } => ("error".into(), message.clone()),
        }
    }

    fn stop(&self) {
        self.generation.fetch_add(1, Ordering::SeqCst);
        self.core.tg_awg_stop();
        *lock(&self.state) = TelegramProxyState::Stopped;
    }
}

struct WindowsTelegramTunnel {
    core: Arc<YpTunCore>,
    app_data_dir: PathBuf,
    journal: Arc<LogJournal>,
    generation: Arc<AtomicU64>,
    token: u64,
    state: Arc<Mutex<TelegramProxyState>>,
    log_filter: Arc<Mutex<CoreLogFilter>>,
}

impl TelegramTunnel for WindowsTelegramTunnel {
    fn log(&mut self, line: &str) {
        let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
        self.journal.add(&format!("{stamp} {line}"));
        logging::append(&self.app_data_dir, line);
    }

    fn start(
        &mut self,
        config: &str,
        listen: &str,
        creds: &telegram_proxy::Credentials,
    ) -> Result<(), String> {
        if self.generation.load(Ordering::SeqCst) != self.token {
            return Err("cancelled".into());
        }
        self.core
            .tg_awg_start(config, listen, &creds.user, &creds.pass)
    }

    fn stop(&mut self) {
        self.core.tg_awg_stop();
    }

    fn running(&mut self) -> bool {
        self.generation.load(Ordering::SeqCst) == self.token && self.core.tg_awg_running()
    }

    fn save_config(&mut self, ini: &str) {
        telegram_proxy::save_config(&self.app_data_dir, ini);
    }

    fn set_state(&mut self, state: TelegramProxyState) {
        *lock(&self.state) = state;
    }

    fn take_degraded(&mut self) -> bool {
        lock(&self.log_filter).take_degraded()
    }

    fn clear_degraded(&mut self) {
        lock(&self.log_filter).clear();
    }
}

impl WarpReqwestHttp {
    fn new() -> Self {
        let client = Client::builder()
            .redirect(reqwest::redirect::Policy::limited(10))
            .connect_timeout(Duration::from_secs(8))
            .tcp_keepalive(Duration::from_secs(30))
            .build()
            .expect("WARP HTTP client");
        Self { client }
    }

    fn response(
        &self,
        mut request: reqwest::blocking::RequestBuilder,
        source: &HttpRequest,
        timeout_ms: u64,
    ) -> Option<HttpResponse> {
        for (name, value) in &source.headers {
            request = request.header(name, value);
        }
        let response = request
            .timeout(Duration::from_millis(timeout_ms.max(1)))
            .send()
            .ok()?;
        let status = response.status().as_u16();
        let mut headers = Vec::new();
        for name in response.headers().keys() {
            for value in response.headers().get_all(name) {
                headers.push((
                    name.as_str().to_string(),
                    value.to_str().unwrap_or_default().to_string(),
                ));
            }
        }
        Some(HttpResponse {
            status,
            headers,
            body: response.text().ok()?,
        })
    }
}

impl warp_account::WarpHttp for WarpReqwestHttp {
    fn get(&self, request: &HttpRequest, timeout_ms: u64) -> Option<HttpResponse> {
        self.response(self.client.get(&request.url), request, timeout_ms)
    }

    fn post_json(
        &self,
        request: &HttpRequest,
        body: &str,
        timeout_ms: u64,
    ) -> Option<HttpResponse> {
        self.response(
            self.client.post(&request.url).body(body.to_string()),
            request,
            timeout_ms,
        )
    }
}

struct CommandOutcome {
    exit_code: i32,
    output: String,
    timed_out: bool,
}

fn run_command_with_timeout(
    command: &[String],
    working_dir: &Path,
    environment: &[(String, String)],
    timeout: Duration,
) -> Result<CommandOutcome, String> {
    if command.is_empty() {
        return Err("empty command".into());
    }
    let mut builder = Command::new(&command[0]);
    builder
        .args(&command[1..])
        .current_dir(working_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (name, value) in environment {
        builder.env(name, value);
    }
    hide_console(&mut builder);
    let mut child = builder
        .spawn()
        .map_err(|error| format!("failed to start {}: {error}", command[0]))?;
    let _job = ChildJob::assign(&child).ok();
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let stdout_reader = thread::spawn(move || read_stream(stdout));
    let stderr_reader = thread::spawn(move || read_stream(stderr));
    let deadline = Instant::now() + timeout;
    let (status, timed_out) = loop {
        match child.try_wait() {
            Ok(Some(status)) => break (Some(status), false),
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(40)),
            Ok(None) => {
                let _ = child.kill();
                break (child.wait().ok(), true);
            }
            Err(error) => return Err(error.to_string()),
        }
    };
    let mut output = stdout_reader.join().unwrap_or_default();
    let error = stderr_reader.join().unwrap_or_default();
    if !error.trim().is_empty() {
        if !output.is_empty() && !output.ends_with('\n') {
            output.push('\n');
        }
        output.push_str(&error);
    }
    Ok(CommandOutcome {
        exit_code: status.and_then(|value| value.code()).unwrap_or(-1),
        output,
        timed_out,
    })
}

fn read_stream<R: Read>(stream: Option<R>) -> String {
    let Some(mut stream) = stream else {
        return String::new();
    };
    let mut bytes = Vec::new();
    let _ = stream.read_to_end(&mut bytes);
    String::from_utf8_lossy(&bytes).into_owned()
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
    tun_job: Option<ChildJob>,
    tun_bypass_addresses: Vec<String>,
    connected: bool,
    active_engine: Option<EngineType>,
    telegram_proxy: TelegramProxyManager,
    connection_mode: String,
    connected_since_epoch_ms: u64,
    watchdog_last_poll: Option<Instant>,
    watchdog_probe_failures: u32,
}

impl WindowsRuntime {
    pub fn new(app_data_dir: PathBuf, native_dir: PathBuf, journal: Arc<LogJournal>) -> Self {
        let core = load_desktop(&native_dir, native_dir.to_str());
        let sink_journal = journal.clone();
        let telegram_log_filter = Arc::new(Mutex::new(CoreLogFilter::new()));
        let sink_filter = telegram_log_filter.clone();
        core.add_log_sink(move |line| {
            let stamp =
                yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
            if line.starts_with(telegram_proxy::CORE_LOG_PREFIX) {
                if let Some(filtered) = lock(&sink_filter).on_core_log(line) {
                    sink_journal.add(&format!("{stamp} {filtered}"));
                }
            } else {
                sink_journal.add(&format!("{stamp} {line}"));
            }
        });
        let telegram_proxy = TelegramProxyManager::new(
            core.clone(),
            app_data_dir.clone(),
            journal.clone(),
            telegram_log_filter,
        );
        let shared_core = SharedCore(core.clone());
        let host = WindowsEngineHost::new(
            app_data_dir.clone(),
            native_dir.clone(),
            journal.clone(),
            shared_core.clone(),
        );
        let controller = DesktopEngineController::new(shared_core, host);
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
            tun_job: None,
            tun_bypass_addresses: Vec::new(),
            connected: false,
            active_engine: None,
            telegram_proxy,
            connection_mode: String::new(),
            connected_since_epoch_ms: 0,
            watchdog_last_poll: None,
            watchdog_probe_failures: 0,
        };
        let _ = runtime.cleanup_stale_external_tun();
        let telegram_enabled = VpnSettings::new(&runtime.app_data_dir)
            .load_app_behavior()
            .telegram_proxy_enabled;
        if telegram_enabled {
            runtime.telegram_proxy.configure(true);
        }
        runtime
    }

    pub fn is_available(&self) -> bool {
        self.core.is_available()
    }

    pub fn configure_telegram_proxy(&self, enabled: bool) {
        self.telegram_proxy.configure(enabled);
    }

    pub fn telegram_proxy_status(&self) -> (String, String) {
        self.telegram_proxy.status()
    }

    pub fn diagnostics(&self) -> RuntimeDiagnostics {
        let engine_alive = self
            .active_engine
            .is_some_and(|engine| self.controller.core_running(engine));
        RuntimeDiagnostics {
            active_engine: self
                .active_engine
                .map(engine_wire_name)
                .unwrap_or_default()
                .to_string(),
            connection_mode: self.connection_mode.clone(),
            engine_alive,
            connected_since_epoch_ms: self.connected_since_epoch_ms,
            in_core_tun: self.controller.tun_handled_in_core(),
            external_tun: self.tun_process.is_some(),
            bypass_route_count: self.tun_bypass_addresses.len(),
            watchdog_probe_failures: self.watchdog_probe_failures,
        }
    }

    /// Called by the state poll. It is deliberately cheap on most polls and performs one SOCKS
    /// CONNECT only every watchdog interval after the initial grace period.
    pub fn watchdog_reason(&mut self) -> Option<String> {
        if !self.connected || self.connected_since_epoch_ms == 0 {
            return None;
        }
        let now = now_epoch_ms();
        if now.saturating_sub(self.connected_since_epoch_ms) < WATCHDOG_GRACE_MS {
            return None;
        }
        if self
            .watchdog_last_poll
            .is_some_and(|last| last.elapsed() < Duration::from_millis(WATCHDOG_INTERVAL_MS))
        {
            return None;
        }
        self.watchdog_last_poll = Some(Instant::now());
        if let Some(engine) = self.active_engine {
            if !self.controller.core_running(engine) {
                return Some("VPN-движок завершился — автоматическое переподключение".into());
            }
        }
        let socks = DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Windows)
            .load()
            .normalized(DesktopOs::Windows);
        let reachable = u16::try_from(socks.port).ok().is_some_and(|port| {
            socks5_connect_rtt(
                &socks.host,
                port,
                &socks.username,
                &socks.password,
                TUNNEL_PROBE_HOST,
                TUNNEL_PROBE_PORT,
                1_500,
            )
            .is_some()
        });
        if reachable {
            self.watchdog_probe_failures = 0;
            return None;
        }
        self.watchdog_probe_failures += 1;
        (self.watchdog_probe_failures >= 3)
            .then(|| "Туннель не передаёт трафик — автоматическое переподключение".into())
    }

    pub fn ping(&self, location: &LocationConfig, is_active: bool) -> Option<u64> {
        let behavior = VpnSettings::new(&self.app_data_dir).load_app_behavior();
        let timeout_ms = behavior.effective_ping_timeout_ms().max(1) as u64;
        let rounds = behavior.effective_ping_precision();
        let connected = self.connected;
        let socks = DesktopSocksSettingsStore::new(&self.app_data_dir, DesktopOs::Windows)
            .load()
            .normalized(DesktopOs::Windows);
        let method = ping_method(location, &behavior.ping_mode, None, is_active, connected);

        best_of_rounds(rounds, || match &method {
            PingMethod::Tcp { host, port } => tcp_ping(host.as_deref(), *port, timeout_ms),
            PingMethod::Icmp(host) => icmp_ping(host.as_deref(), timeout_ms, DesktopOs::Windows),
            PingMethod::Tunnel => {
                let port = u16::try_from(socks.port).ok()?;
                socks5_connect_rtt(
                    &socks.host,
                    port,
                    &socks.username,
                    &socks.password,
                    TUNNEL_PROBE_HOST,
                    TUNNEL_PROBE_PORT,
                    timeout_ms,
                )
            }
            PingMethod::AwgProbe(config) => self
                .core
                .awg_measure_delay(
                    config,
                    &behavior.effective_ping_url(),
                    "HEAD",
                    timeout_ms as i32,
                )
                .ok()
                .filter(|value| *value >= 0)
                .map(|value| value as u64),
            PingMethod::ProxyUrl(method) => {
                self.proxy_url_ping(location, &behavior.effective_ping_url(), method, timeout_ms)
            }
            // olcRTC has no standalone probe in the Windows ABI. An active olcRTC session is
            // still measured through its SOCKS endpoint; inactive rooms are reported unknown.
            PingMethod::OlcRtc if is_active && connected => {
                let port = u16::try_from(socks.port).ok()?;
                socks5_connect_rtt(
                    &socks.host,
                    port,
                    &socks.username,
                    &socks.password,
                    TUNNEL_PROBE_HOST,
                    TUNNEL_PROBE_PORT,
                    timeout_ms,
                )
            }
            PingMethod::OlcRtc => None,
        })
    }

    fn proxy_url_ping(
        &self,
        location: &LocationConfig,
        url: &str,
        method: &str,
        timeout_ms: u64,
    ) -> Option<u64> {
        let profile = location.proxy.as_ref()?;
        if profile.kind == TYPE_AMNEZIAWG {
            return self
                .core
                .awg_measure_delay(&profile.awg_config, url, method, timeout_ms as i32)
                .ok()
                .filter(|value| *value >= 0)
                .map(|value| value as u64);
        }
        if matches!(
            profile.kind.as_str(),
            TYPE_HYSTERIA2 | TYPE_OPENVPN | TYPE_TRUSTTUNNEL
        ) {
            return icmp_ping(Some(&profile.server), timeout_ms, DesktopOs::Windows);
        }

        let port = random_test_port();
        let config = profile
            .raw_xray_config
            .as_deref()
            .filter(|value| !value.trim().is_empty())
            .and_then(|raw| xray_config::build_raw_proxy_ping_config(raw, port, LOOPBACK))
            .unwrap_or_else(|| {
                xray_config::build(
                    profile,
                    &XrayOptions {
                        listen_port: port,
                        listen_host: LOOPBACK.into(),
                        block_quic: false,
                        ..XrayOptions::default()
                    },
                )
            });
        self.core
            .xray_measure_delay(&config, url, method, timeout_ms as i32)
            .ok()
            .filter(|value| *value >= 0)
            .map(|value| value as u64)
    }

    pub fn check_free_servers(
        &self,
        candidates: Vec<(String, ProxyProfile)>,
        parallelism: usize,
        url: &str,
        timeout_ms: i32,
    ) -> Vec<FreeServerItem> {
        let queue = Arc::new(Mutex::new(
            candidates.into_iter().enumerate().collect::<VecDeque<_>>(),
        ));
        let results = Arc::new(Mutex::new(Vec::new()));
        let workers = parallelism.max(1).min(lock(&queue).len().max(1));
        let timeout_ms = timeout_ms.max(1) as u64;

        thread::scope(|scope| {
            for _ in 0..workers {
                let queue = queue.clone();
                let results = results.clone();
                let core = self.core.clone();
                scope.spawn(move || loop {
                    let Some((index, (line, profile))) = lock(&queue).pop_front() else {
                        break;
                    };
                    let port = random_test_port();
                    let config = profile
                        .raw_xray_config
                        .as_deref()
                        .filter(|value| !value.trim().is_empty())
                        .and_then(|raw| {
                            xray_config::build_raw_proxy_ping_config(raw, port, LOOPBACK)
                        })
                        .unwrap_or_else(|| {
                            xray_config::build(
                                &profile,
                                &XrayOptions {
                                    listen_port: port,
                                    listen_host: LOOPBACK.into(),
                                    block_quic: false,
                                    ..XrayOptions::default()
                                },
                            )
                        });
                    let ping = core
                        .xray_measure_delay(&config, url, "HEAD", timeout_ms as i32)
                        .ok()
                        .filter(|value| *value >= 0)
                        .map(|value| value as u64);
                    if let Some(ping_ms) = ping.filter(|value| *value > 0) {
                        lock(&results).push(FreeServerItem {
                            id: format!(
                                "free_{}_{}_{}",
                                profile.server, profile.server_port, index
                            ),
                            line,
                            display_name: profile.display_name(),
                            profile,
                            ping_ms: ping_ms as i64,
                        });
                    }
                });
            }
        });

        let mut output = lock(&results).clone();
        output.sort_by_key(|item| item.ping_ms);
        output
    }

    pub fn start(&mut self, location: &LocationConfig, mode: &str) -> Result<(), String> {
        self.stop()?;
        if !self.is_available() {
            return Err("yptuncore native library is unavailable".into());
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
        let exclude_addresses = self.controller.host().bypass_addresses(location);
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
        self.connection_mode = mode.to_string();
        self.connected_since_epoch_ms = now_epoch_ms();
        self.watchdog_last_poll = None;
        self.watchdog_probe_failures = 0;
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
        self.connection_mode.clear();
        self.connected_since_epoch_ms = 0;
        self.watchdog_last_poll = None;
        self.watchdog_probe_failures = 0;
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
        let job = match ChildJob::assign(&child) {
            Ok(job) => Some(job),
            Err(error) => {
                self.log(&format!(
                    "tun2socks crash cleanup job could not be created: {error}"
                ));
                None
            }
        };
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
        self.tun_job = job;
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
        self.tun_job = None;
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

impl Drop for WindowsRuntime {
    fn drop(&mut self) {
        self.telegram_proxy.stop();
        let _ = self.stop();
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

fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as u64)
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

fn is_proxy_mode(mode: &str) -> bool {
    mode == "proxy"
}

fn engine_wire_name(engine: EngineType) -> &'static str {
    match engine {
        EngineType::Stealth => "stealth",
        EngineType::Standard => "standard",
        EngineType::Chain => "chain",
        EngineType::VkTurn => "vkturn",
        EngineType::MasterDns => "masterdns",
        EngineType::OpenFlux => "openflux",
        EngineType::Snolc => "snolc",
    }
}
