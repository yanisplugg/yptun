use std::collections::BTreeSet;

use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::DesktopSocksProxySettings;
use yptun_core::desktop_vpn_manager::{DesktopMode, OlcRtcStartError};
use yptun_core::desktop_vpn_start::*;
use yptun_core::location::LocationConfig;
use yptun_core::model::EngineType;
use yptun_core::vpn_settings::SplitTunnelState;

fn stealth() -> LocationConfig {
    let mut l = LocationConfig::default();
    l.name = String::from("Test");
    l.id = String::from("room1");
    l.key = String::from("00ff");
    l
}

fn with_engine(engine: EngineType) -> LocationConfig {
    let mut l = stealth();
    l.engine = engine;
    l
}

struct Host {
    calls: Vec<String>,
    logs: Vec<String>,
    statuses: Vec<DesktopVpnStatus>,
    generation: u64,
    bump_generation_on_wait: bool,
    location: Option<LocationConfig>,
    socks: DesktopSocksProxySettings,
    proxy_mode: bool,
    phys: i32,
    engine_supported: bool,
    tun_in_core: bool,
    olcrtc_result: Result<(), DesktopStartError>,
    last_proxy_socks: Option<DesktopSocksProxySettings>,
    last_tun: Option<TunStartRequest>,
}

fn host() -> Host {
    Host {
        calls: Vec::new(),
        logs: Vec::new(),
        statuses: Vec::new(),
        generation: 1,
        bump_generation_on_wait: false,
        location: Some(stealth()),
        socks: DesktopSocksProxySettings::default(),
        proxy_mode: false,
        phys: 3,
        engine_supported: true,
        tun_in_core: false,
        olcrtc_result: Ok(()),
        last_proxy_socks: None,
        last_tun: None,
    }
}

impl Host {
    fn called(&self, name: &str) -> bool {
        self.calls.iter().any(|c| c == name)
    }
}

impl DesktopStartHost for Host {
    fn set_status(&mut self, status: DesktopVpnStatus) {
        self.statuses.push(status);
    }
    fn log(&mut self, message: String) {
        self.logs.push(message);
    }
    fn generation(&self) -> u64 {
        self.generation
    }
    fn active_location(&mut self) -> Option<LocationConfig> {
        self.location.clone()
    }
    fn configured_socks(&self) -> DesktopSocksProxySettings {
        self.socks.clone()
    }
    fn proxy_mode(&self) -> bool {
        self.proxy_mode
    }
    fn resolve_conflicting_vpn_clients(&mut self) {
        self.calls.push(String::from("conflicts"));
    }
    fn port_taken(&self, _port: i32) -> bool {
        false
    }
    fn ensure_administrator_or_request_restart(&mut self) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("admin"));
        Ok(())
    }
    fn clear_stale_linux_tun(&mut self) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("clear_stale"));
        Ok(())
    }
    fn physical_interface_index(&mut self) -> i32 {
        self.calls.push(String::from("phys"));
        self.phys
    }
    fn bind_outbound_interface(&mut self, index: i32, pin_udp: bool) {
        self.calls.push(format!("bind {} {}", index, pin_udp));
    }
    fn unbind_outbound_interface(&mut self) {
        self.calls.push(String::from("unbind"));
    }
    fn engine_controller_supported(&self) -> bool {
        self.engine_supported
    }
    fn resolve_bypass_server_ips(&mut self, _location: &LocationConfig) -> Vec<String> {
        self.calls.push(String::from("bypass_ips"));
        vec![String::from("203.0.113.7")]
    }
    fn split_tunnel(&mut self) -> SplitTunnelState {
        SplitTunnelState::default()
    }
    fn start_engine(&mut self, _request: &EngineStartRequest) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("engine"));
        Ok(())
    }
    fn tun_handled_in_core(&self) -> bool {
        self.tun_in_core
    }
    fn start_olcrtc(
        &mut self,
        _location: &LocationConfig,
        _socks: &DesktopSocksProxySettings,
        privileged: bool,
    ) -> Result<(), DesktopStartError> {
        self.calls.push(format!("olcrtc {}", privileged));
        self.olcrtc_result.clone()
    }
    fn wait_for_olcrtc_ready(&mut self, _socks_port: i32, _request_generation: u64) -> Result<(), OlcRtcStartError> {
        self.calls.push(String::from("wait"));
        if self.bump_generation_on_wait {
            self.generation += 1;
        }
        Ok(())
    }
    fn start_linux_tun(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("linux_tun"));
        self.last_tun = Some(request.clone());
        Ok(())
    }
    fn start_windows_tun(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("windows_tun"));
        self.last_tun = Some(request.clone());
        Ok(())
    }
    fn start_system_proxy(
        &mut self,
        socks: &DesktopSocksProxySettings,
        _request_generation: u64,
    ) -> Result<(), DesktopStartError> {
        self.calls.push(String::from("system_proxy"));
        self.last_proxy_socks = Some(socks.clone());
        Ok(())
    }
    fn start_watchdog(&mut self, _generation: u64) {
        self.calls.push(String::from("watchdog"));
    }
    fn release_linux_tun(&mut self) {
        self.calls.push(String::from("release_linux_tun"));
    }
    fn stop_windows_tun(&mut self) -> Result<(), String> {
        self.calls.push(String::from("stop_windows_tun"));
        Ok(())
    }
    fn restore_system_proxy(&mut self) -> Result<(), String> {
        self.calls.push(String::from("restore_proxy"));
        Ok(())
    }
    fn stop_http_proxy_bridge(&mut self) {
        self.calls.push(String::from("stop_http_bridge"));
    }
    fn stop_pac_server(&mut self) {
        self.calls.push(String::from("stop_pac"));
    }
    fn stop_engines(&mut self) -> Result<(), String> {
        self.calls.push(String::from("stop_engines"));
        Err(String::from("busy"))
    }
    fn stop_olcrtc_process(&mut self, privileged: bool) {
        self.calls.push(format!("stop_olcrtc {}", privileged));
    }
    fn delete_olcrtc_config(&mut self) {
        self.calls.push(String::from("delete_config"));
    }
    fn schedule_restart(&mut self, generation: u64, delay_ms: u64) {
        self.calls.push(format!("restart {} {}", generation, delay_ms));
    }
}

#[test]
fn mode_follows_os_unless_proxy_mode() {
    assert_eq!(desktop_mode_for(DesktopOs::Linux, false), DesktopMode::LinuxTun);
    assert_eq!(desktop_mode_for(DesktopOs::Windows, false), DesktopMode::WindowsTun);
    assert_eq!(desktop_mode_for(DesktopOs::MacOs, false), DesktopMode::SystemProxy);
    assert_eq!(desktop_mode_for(DesktopOs::Linux, true), DesktopMode::SystemProxy);
    assert_eq!(desktop_mode_for(DesktopOs::Windows, true), DesktopMode::SystemProxy);
}

#[test]
fn proxy_mode_drops_socks_credentials() {
    let mut s = DesktopSocksProxySettings::default();
    s.username = String::from("u");
    s.password = String::from("p");
    let mut logs: Vec<String> = Vec::new();
    let out = session_socks_settings(DesktopMode::SystemProxy, &s, |_| true, &mut |m: String| logs.push(m));
    assert_eq!(out.username, "");
    assert_eq!(out.password, "");
    assert_eq!(out.port, s.port);
    assert!(logs.is_empty());
    assert!(drops_proxy_auth(DesktopMode::SystemProxy, &s));
    assert!(!drops_proxy_auth(DesktopMode::LinuxTun, &s));
    assert!(!drops_proxy_auth(DesktopMode::SystemProxy, &DesktopSocksProxySettings::default()));
}

#[test]
fn tun_mode_keeps_credentials_and_moves_taken_port() {
    let mut s = DesktopSocksProxySettings::default();
    s.port = 10808;
    s.username = String::from("u");
    let mut logs: Vec<String> = Vec::new();
    let out = session_socks_settings(DesktopMode::WindowsTun, &s, |p| p == 10808, &mut |m: String| logs.push(m));
    assert_eq!(out.username, "u");
    assert_eq!(out.port, 27960);
    assert_eq!(logs.len(), 1);
    let same = session_socks_settings(DesktopMode::LinuxTun, &s, |_| false, &mut |_m: String| ());
    assert_eq!(same, s);
}

#[test]
fn interface_pinning_rules() {
    let p = interface_pinning(DesktopMode::SystemProxy, EngineType::Standard, 7);
    assert_eq!(p, InterfacePinning { index: 0, pin_udp: true, log: None });
    let p = interface_pinning(DesktopMode::WindowsTun, EngineType::Standard, 7);
    assert_eq!(p.index, 7);
    assert!(p.pin_udp);
    assert_eq!(
        p.log.as_deref(),
        Some("Pinning Xray sockets to network interface #7 (keeps direct traffic out of the TUN)")
    );
    let p = interface_pinning(DesktopMode::LinuxTun, EngineType::VkTurn, 0);
    assert_eq!(p.index, 0);
    assert!(!p.pin_udp);
    assert_eq!(p.log.as_deref(), Some(NO_PHYSICAL_INTERFACE_LOG));
}

#[test]
fn engine_controller_choice() {
    assert!(!use_engine_controller(&stealth(), true));
    assert!(use_engine_controller(&with_engine(EngineType::Standard), true));
    assert!(use_engine_controller(&with_engine(EngineType::VkTurn), true));
    assert!(!use_engine_controller(&with_engine(EngineType::Standard), false));
    assert!(needs_bypass_server_ips(true, DesktopMode::WindowsTun));
    assert!(needs_bypass_server_ips(true, DesktopMode::LinuxTun));
    assert!(!needs_bypass_server_ips(true, DesktopMode::SystemProxy));
    assert!(!needs_bypass_server_ips(false, DesktopMode::WindowsTun));
}

#[test]
fn split_tunnel_and_udp_rules() {
    let mut split = SplitTunnelState::default();
    split.proxy_processes = BTreeSet::from([String::from("a.exe")]);
    split.bypass_processes = BTreeSet::from([String::from("b.exe")]);
    assert!(split_tunnel_processes(&split).is_empty());
    split.mode = String::from("proxy_selected");
    assert_eq!(split_tunnel_processes(&split), vec![String::from("a.exe")]);
    split.mode = String::from("bypass_selected");
    assert_eq!(split_tunnel_processes(&split), vec![String::from("b.exe")]);
    assert!(udp_over_tcp(EngineType::MasterDns));
    assert!(udp_over_tcp(EngineType::OpenFlux));
    assert!(!udp_over_tcp(EngineType::Stealth));
    assert!(!udp_over_tcp(EngineType::VkTurn));
    assert_eq!(connected_log(DesktopMode::LinuxTun), "Desktop Linux TUN connected");
    assert_eq!(connected_log(DesktopMode::WindowsTun), "Desktop Windows TUN connected");
    assert_eq!(connected_log(DesktopMode::SystemProxy), "Desktop proxy connected");
}

#[test]
fn missing_location_stops_before_anything() {
    let mut h = host();
    h.location = None;
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Linux, 1, false);
    assert_eq!(
        h.statuses,
        vec![
            DesktopVpnStatus::Connecting,
            DesktopVpnStatus::Error(String::from(NO_ACTIVE_LOCATION))
        ]
    );
    assert_eq!(h.logs, vec![String::from(ADD_VALID_LOCATION_LOG)]);
    assert!(h.calls.is_empty());
}

#[test]
fn incomplete_location_is_rejected() {
    let mut h = host();
    let mut l = stealth();
    l.key = String::new();
    h.location = Some(l);
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Linux, 1, true);
    assert_eq!(h.statuses[0], DesktopVpnStatus::Reconnecting);
    assert_eq!(h.statuses[1], DesktopVpnStatus::Error(String::from(NO_ACTIVE_LOCATION)));
}

#[test]
fn linux_stealth_runs_privileged_olcrtc_then_tun() {
    let mut h = host();
    let mut s = DesktopVpnSession::default();
    s.auto_reconnect = true;
    s.auto_reconnect_attempt = 3;
    start_desktop_mode(&mut h, &mut s, DesktopOs::Linux, 1, false);
    assert_eq!(
        h.calls,
        vec!["conflicts", "clear_stale", "phys", "bind 3 true", "olcrtc true", "wait", "linux_tun", "watchdog"]
    );
    assert_eq!(h.statuses.last(), Some(&DesktopVpnStatus::Connected));
    assert_eq!(h.logs.last().map(String::as_str), Some("Desktop Linux TUN connected"));
    assert_eq!(s.active_mode, Some(DesktopMode::LinuxTun));
    assert!(!s.auto_reconnect);
    assert_eq!(s.auto_reconnect_attempt, 0);
    let tun = h.last_tun.clone().unwrap();
    assert!(!tun.udp_over_tcp);
    assert!(tun.bypass_server_ips.is_empty());
}

#[test]
fn windows_stealth_needs_admin_and_external_tun() {
    let mut h = host();
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Windows, 1, false);
    assert_eq!(
        h.calls,
        vec!["conflicts", "admin", "phys", "bind 3 true", "olcrtc false", "wait", "windows_tun", "watchdog"]
    );
    assert_eq!(h.logs.last().map(String::as_str), Some("Desktop Windows TUN connected"));
}

#[test]
fn windows_tun_owned_by_sing_box_skips_external_bridge() {
    let mut h = host();
    h.tun_in_core = true;
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Windows, 1, false);
    assert!(!h.called("windows_tun"));
    assert!(h.logs.iter().any(|l| l == WINDOWS_TUN_IN_CORE_LOG));
}

#[test]
fn mac_uses_system_proxy_without_interface_probe() {
    let mut h = host();
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::MacOs, 1, false);
    assert_eq!(
        h.calls,
        vec!["conflicts", "bind 0 true", "olcrtc false", "wait", "system_proxy", "watchdog"]
    );
    assert_eq!(h.last_proxy_socks.clone().unwrap().username, "");
    assert_eq!(h.logs.last().map(String::as_str), Some("Desktop proxy connected"));
}

#[test]
fn failure_rolls_back_and_reports_error() {
    let mut h = host();
    h.olcrtc_result = Err(DesktopStartError::Failed(String::from("boom")));
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Linux, 1, false);
    assert!(h.logs.iter().any(|l| l == "Desktop start failed: boom"));
    assert!(h.logs.iter().any(|l| l == "Engine stop failed: busy"));
    let tail: Vec<&str> = h.calls.iter().skip(5).map(String::as_str).collect();
    assert_eq!(
        tail,
        vec!["release_linux_tun", "stop_pac", "stop_engines", "unbind", "stop_olcrtc true", "delete_config"]
    );
    assert_eq!(h.statuses.last(), Some(&DesktopVpnStatus::Error(String::from("boom"))));
    assert!(s.engine_location.is_none());
}

#[test]
fn failure_with_auto_reconnect_schedules_backoff() {
    let mut h = host();
    h.olcrtc_result = Err(DesktopStartError::Failed(String::from("boom")));
    let mut s = DesktopVpnSession::default();
    s.auto_reconnect = true;
    s.auto_reconnect_attempt = 1;
    start_desktop_mode(&mut h, &mut s, DesktopOs::MacOs, 1, true);
    assert!(h.called("restore_proxy"));
    assert!(h.called("stop_http_bridge"));
    assert!(h.called("stop_olcrtc false"));
    assert_eq!(h.calls.last().map(String::as_str), Some("restart 1 4000"));
    assert_eq!(h.statuses.last(), Some(&DesktopVpnStatus::Reconnecting));
    assert_eq!(h.logs.last().map(String::as_str), Some("Reconnect failed (boom); retrying in 4s"));
    assert_eq!(s.auto_reconnect_attempt, 2);
}

#[test]
fn superseded_start_is_cancelled_quietly() {
    let mut h = host();
    h.bump_generation_on_wait = true;
    let mut s = DesktopVpnSession::default();
    start_desktop_mode(&mut h, &mut s, DesktopOs::Windows, 1, false);
    assert!(h.logs.iter().any(|l| l == START_CANCELLED_LOG));
    assert!(!h.called("windows_tun"));
    assert!(h.called("stop_windows_tun"));
    assert_eq!(h.statuses, vec![DesktopVpnStatus::Connecting]);
}

#[test]
fn olcrtc_error_maps_to_start_error() {
    assert_eq!(
        DesktopStartError::from(OlcRtcStartError::Superseded),
        DesktopStartError::Cancelled(String::from(START_SUPERSEDED))
    );
    assert_eq!(
        DesktopStartError::from(OlcRtcStartError::Failed(String::from("x"))),
        DesktopStartError::Failed(String::from("x"))
    );
    assert!(DesktopStartError::Cancelled(String::new()).is_cancelled());
}
