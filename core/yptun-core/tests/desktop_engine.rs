//! Tests for the DesktopEngineController port (pure helpers + stop/running matrix with mocks).

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use yptun_core::desktop_engine::{
    build_wdtt_wg_profile, front_profile, local_socks_profile, looks_like_ip, masterdns_encryption_label,
    raw_needs_geo, shared_addresses, vk_outbound_configured, vk_outbound_type, warp_skip_reason, CsqttUp,
    DesktopEngineController, EngineCore, EngineHost,
};
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, ProxyCore, ProxyProfile};
use yptun_core::room_pool::RoomSpec;
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::transports::{
    OpenFluxConfig, SnolcConfig, VkTurnConfig, CORE_CSQTT, CORE_FREETURN, MASTERDNS_ENCRYPTION_LABELS,
    OUTBOUND_AMNEZIAWG, OUTBOUND_PROXY, OUTBOUND_WIREGUARD,
};
use yptun_core::yptun_core::{CoreResult, MasterDnsStart};

#[derive(Default)]
struct State {
    calls: RefCell<Vec<String>>,
    logs: RefCell<Vec<String>>,
    now: Cell<u64>,
    busy_ports: RefCell<BTreeSet<i32>>,
    sb: Cell<bool>,
    xray: Cell<bool>,
    rtc: Cell<bool>,
    rooms: Cell<i32>,
    ft: Cell<bool>,
    wdtt: Cell<bool>,
    md: Cell<bool>,
    of: Cell<bool>,
    snolc: Cell<bool>,
    csqtt: Cell<bool>,
}

impl State {
    fn call(&self, s: &str) {
        self.calls.borrow_mut().push(s.to_string());
    }
    fn called(&self, s: &str) -> bool {
        self.calls.borrow().iter().any(|c| c == s)
    }
}

struct MockCore(Rc<State>);
struct MockHost(Rc<State>);

impl EngineCore for MockCore {
    fn rtc_set_socks_listen_host(&self, _host: &str) -> CoreResult<()> {
        Ok(())
    }
    fn rtc_set_telemost_cookies(&self, _cookies: &str) -> CoreResult<()> {
        Ok(())
    }
    fn rtc_apply_transport_options(&self, _config: &LocationConfig) -> CoreResult<()> {
        Ok(())
    }
    fn rtc_start(
        &self,
        _carrier: &str,
        _transport: &str,
        _room_id: &str,
        _client_id: &str,
        _key_hex: &str,
        _socks_port: i32,
        _socks_user: &str,
        _socks_pass: &str,
    ) -> CoreResult<()> {
        self.0.call("rtc_start");
        self.0.rtc.set(true);
        Ok(())
    }
    fn rtc_wait_ready(&self, _timeout_ms: i32) -> CoreResult<()> {
        Ok(())
    }
    fn rtc_running(&self) -> bool {
        self.0.rtc.get()
    }
    fn rtc_rooms_running(&self) -> i32 {
        self.0.rooms.get()
    }
    fn sb_start(&self, _config_json: &str) -> CoreResult<()> {
        self.0.call("sb_start");
        self.0.sb.set(true);
        Ok(())
    }
    fn sb_running(&self) -> bool {
        self.0.sb.get()
    }
    fn xray_set_asset_path(&self, _dir: &str) -> CoreResult<()> {
        Ok(())
    }
    fn xray_start(&self, _config_json: &str) -> CoreResult<()> {
        self.0.call("xray_start");
        self.0.xray.set(true);
        Ok(())
    }
    fn xray_running(&self) -> bool {
        self.0.xray.get()
    }
    fn awg_start(&self, _ini_config: &str, _listen_addr: &str) -> CoreResult<()> {
        self.0.call("awg_start");
        Ok(())
    }
    fn awg_stop(&self) {
        self.0.call("awg_stop");
    }
    fn ft_start(&self, _uri: &str, _listen_addr: &str, _vk_link: &str, _n_streams: i32) -> CoreResult<()> {
        self.0.call("ft_start");
        Ok(())
    }
    fn ft_running(&self) -> bool {
        self.0.ft.get()
    }
    fn ft_connected_streams(&self) -> i32 {
        0
    }
    fn ft_captcha_url(&self) -> String {
        String::new()
    }
    fn ft_captcha_active(&self) -> bool {
        false
    }
    fn wdtt_start(&self, _options_json: &str) -> CoreResult<()> {
        self.0.call("wdtt_start");
        Ok(())
    }
    fn wdtt_last_error(&self) -> String {
        String::new()
    }
    fn wdtt_wait_config(&self, _timeout_ms: i32) -> CoreResult<Option<String>> {
        Ok(None)
    }
    fn wdtt_running(&self) -> bool {
        self.0.wdtt.get()
    }
    fn master_dns_start(&self, _args: &MasterDnsStart) -> CoreResult<()> {
        self.0.call("master_dns_start");
        Ok(())
    }
    fn master_dns_stop(&self) {
        self.0.call("master_dns_stop");
    }
    fn master_dns_running(&self) -> bool {
        self.0.md.get()
    }
    fn stop_all(&self) {
        self.0.call("core_stop_all");
        self.0.sb.set(false);
        self.0.xray.set(false);
        self.0.rtc.set(false);
    }
}

impl EngineHost for MockHost {
    fn log(&self, line: &str) {
        self.0.logs.borrow_mut().push(line.to_string());
    }
    fn app_data_dir(&self) -> PathBuf {
        PathBuf::from("/tmp/yptun-test")
    }
    fn load_traffic(&self) -> TrafficSettings {
        TrafficSettings::default()
    }
    fn load_routing(&self) -> RoutingRules {
        RoutingRules::default()
    }
    fn load_routing_profiles(&self) -> RoutingProfilesState {
        RoutingProfilesState::default()
    }
    fn load_app_behavior(&self) -> AppBehaviorSettings {
        AppBehaviorSettings::default()
    }
    fn now_ms(&self) -> u64 {
        self.0.now.get()
    }
    fn sleep_ms(&self, ms: u64) {
        self.0.now.set(self.0.now.get() + ms.max(1));
    }
    fn is_local_port_open(&self, port: i32) -> bool {
        self.0.busy_ports.borrow().contains(&port)
    }
    fn resolve_all(&self, _host: &str) -> Vec<IpAddr> {
        vec![]
    }
    fn file_size(&self, _path: &Path) -> Option<u64> {
        None
    }
    fn delete_file(&self, _path: &Path) {}
    fn running_process_names(&self) -> Vec<String> {
        vec![]
    }
    fn parse_inbound_link(&self, _link: &str) -> Option<ProxyProfile> {
        None
    }
    fn ensure_geo_assets(&self, _geoip_url: &str, _geosite_url: &str) -> bool {
        true
    }
    fn geo_asset_dir(&self) -> String {
        "/tmp/yptun-test/geo".into()
    }
    fn resolve_asns(&self, _asns: &[String]) -> BTreeMap<String, Vec<String>> {
        BTreeMap::new()
    }
    fn warp_load_config(&self) -> Option<String> {
        None
    }
    fn warp_save_config(&self, _ini: &str) {}
    fn warp_generate(&self, _official_first: bool) -> Result<String, String> {
        Err("no warp in tests".into())
    }
    fn room_pool_start(&self, _specs: &[RoomSpec], _host: &str, _port: i32, _user: &str, _pass: &str) -> bool {
        self.0.call("room_pool_start");
        false
    }
    fn room_pool_stop(&self) {
        self.0.call("room_pool_stop");
    }
    fn trust_tunnel_start(&self, _tt_config: &str, _port: i32) -> Result<(), String> {
        Ok(())
    }
    fn trust_tunnel_await_ready(&self, _port: i32) -> bool {
        true
    }
    fn trust_tunnel_stop(&self) {
        self.0.call("tt_stop");
    }
    fn open_flux_start(&self, _c: &OpenFluxConfig, _h: &str, _p: i32, _u: &str, _pw: &str) -> Result<(), String> {
        Ok(())
    }
    fn open_flux_running(&self) -> bool {
        self.0.of.get()
    }
    fn open_flux_exit_description(&self) -> String {
        String::new()
    }
    fn open_flux_stop(&self) {
        self.0.call("of_stop");
    }
    fn snolc_start(&self, _c: &SnolcConfig, _h: &str, _p: i32, _u: &str, _pw: &str) -> Result<(), String> {
        Ok(())
    }
    fn snolc_running(&self) -> bool {
        self.0.snolc.get()
    }
    fn snolc_exit_description(&self) -> String {
        String::new()
    }
    fn snolc_stop(&self) {
        self.0.call("snolc_stop");
    }
    fn csqtt_binaries(&self) -> Result<(String, String), String> {
        Err("no csqtt".into())
    }
    fn csqtt_launch(&self, _bridge_exe: &str, _options_json: &str) -> Result<(), String> {
        Ok(())
    }
    fn csqtt_await_ready(&self, _timeout_ms: u64) -> Option<CsqttUp> {
        None
    }
    fn csqtt_no_tunnel_message(&self) -> String {
        "csqtt: no tunnel".into()
    }
    fn csqtt_running(&self) -> bool {
        self.0.csqtt.get()
    }
    fn csqtt_stop(&self) {
        self.0.call("csqtt_stop");
    }
    fn open_browser_window(&self, _url: &str) -> bool {
        false
    }
    fn show_captcha_toast(&self) {}
}

fn controller() -> (Rc<State>, DesktopEngineController<MockCore, MockHost>) {
    let st = Rc::new(State::default());
    let c = DesktopEngineController::new(MockCore(st.clone()), MockHost(st.clone()));
    (st, c)
}

#[test]
fn looks_like_ip_matches_kotlin() {
    assert!(looks_like_ip("1.2.3.4"));
    assert!(looks_like_ip("::1"));
    assert!(looks_like_ip("2001:db8::1"));
    assert!(!looks_like_ip("example.com"));
    assert!(!looks_like_ip("1.2.3"));
    assert!(!looks_like_ip("a.b.c.d"));
}

#[test]
fn local_socks_profile_raw() {
    let p = local_socks_profile("olcrtc", 10808);
    assert_eq!(p.tag, "olcrtc");
    assert_eq!(p.kind, "socks");
    assert_eq!(p.server, "127.0.0.1");
    assert_eq!(p.server_port, 10808);
    assert_eq!(
        p.raw_outbound.as_deref(),
        Some("{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":10808,\"version\":\"5\"}")
    );
}

#[test]
fn front_profile_with_and_without_credentials() {
    let plain = front_profile(2000, "", "");
    assert_eq!(plain.tag, "xray");
    assert_eq!(
        plain.raw_outbound.as_deref(),
        Some("{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":2000,\"version\":\"5\"}")
    );
    let auth = front_profile(2000, "u", "p");
    assert_eq!(
        auth.raw_outbound.as_deref(),
        Some("{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":2000,\"version\":\"5\",\"username\":\"u\",\"password\":\"p\"}")
    );
}

#[test]
fn wdtt_wg_profile_parses_and_clamps_mtu() {
    let conf = "[Interface]\nPrivateKey = PRIV\nAddress = 10.0.0.2/32, fd00::2/128\nMTU = 1420\n# c\n[Peer]\nPublicKey = PUB\n";
    let p = build_wdtt_wg_profile(conf, 51820);
    assert_eq!(p.tag, "WDTT");
    assert_eq!(p.kind, "wireguard");
    let raw = p.raw_outbound.unwrap();
    assert!(raw.contains("\"server_port\":51820"));
    assert!(raw.contains("\"local_address\":[\"10.0.0.2/32\"]"));
    assert!(raw.contains("\"private_key\":\"PRIV\""));
    assert!(raw.contains("\"peer_public_key\":\"PUB\""));
    assert!(raw.contains("\"mtu\":1200"));
    let small = build_wdtt_wg_profile("MTU = 1000", 1).raw_outbound.unwrap();
    assert!(small.contains("\"mtu\":1000"));
    assert!(small.contains("\"local_address\":[]"));
}

#[test]
fn vk_outbound_type_and_configured() {
    assert_eq!(vk_outbound_type(None), OUTBOUND_WIREGUARD);
    let ft = VkTurnConfig { core: CORE_FREETURN.into(), outbound: OUTBOUND_PROXY.into(), ..Default::default() };
    assert_eq!(vk_outbound_type(Some(&ft)), OUTBOUND_PROXY);
    let cs = VkTurnConfig { core: CORE_CSQTT.into(), ..Default::default() };
    assert_eq!(vk_outbound_type(Some(&cs)), OUTBOUND_AMNEZIAWG);
    assert!(!vk_outbound_configured(Some(&cs), OUTBOUND_AMNEZIAWG, None));
    let cs_peer = VkTurnConfig { csqtt_peer: "peer:1".into(), ..cs };
    assert!(vk_outbound_configured(Some(&cs_peer), OUTBOUND_AMNEZIAWG, None));
    let good = ProxyProfile { server: "h".into(), server_port: 443, ..Default::default() };
    let bad = ProxyProfile { server: "h".into(), server_port: 0, ..Default::default() };
    assert!(vk_outbound_configured(Some(&ft), OUTBOUND_PROXY, Some(&good)));
    assert!(!vk_outbound_configured(Some(&ft), OUTBOUND_PROXY, Some(&bad)));
    let awg = ProxyProfile { awg_config: "[Interface]".into(), ..Default::default() };
    assert!(vk_outbound_configured(None, OUTBOUND_AMNEZIAWG, Some(&awg)));
    assert!(!vk_outbound_configured(None, OUTBOUND_WIREGUARD, Some(&good)));
}

#[test]
fn warp_skip_reason_rules() {
    let vless = ProxyProfile { kind: "vless".into(), ..Default::default() };
    assert_eq!(warp_skip_reason(false, &vless), None);
    assert!(warp_skip_reason(true, &vless).unwrap().contains("chain"));
    let hy2 = ProxyProfile { kind: "hysteria2".into(), ..Default::default() };
    assert!(warp_skip_reason(false, &hy2).unwrap().ends_with("this is hysteria2"));
    let raw = ProxyProfile { kind: "hysteria2".into(), raw_xray_config: Some("{}".into()), ..Default::default() };
    assert_eq!(warp_skip_reason(false, &raw), None);
}

#[test]
fn shared_addresses_and_geo_and_labels() {
    let a: Vec<String> = vec!["1.1.1.1".into(), "2.2.2.2".into(), "1.1.1.1".into()];
    let b: Vec<String> = vec!["1.1.1.1".into(), "3.3.3.3".into()];
    assert_eq!(shared_addresses(&a, &b), vec!["1.1.1.1".to_string()]);
    assert!(shared_addresses(&b[1..], &a).is_empty());
    assert!(raw_needs_geo("{\"domain\":[\"geosite:ru\"]}"));
    assert!(!raw_needs_geo("{}"));
    assert_eq!(masterdns_encryption_label(0), MASTERDNS_ENCRYPTION_LABELS[0]);
    assert_eq!(masterdns_encryption_label(-1), "?");
    assert_eq!(masterdns_encryption_label(99), "?");
}

#[test]
fn stop_all_stops_every_sidecar_and_core() {
    let (st, mut c) = controller();
    c.stop_all();
    for name in ["tt_stop", "of_stop", "snolc_stop", "csqtt_stop", "room_pool_stop", "core_stop_all"] {
        assert!(st.called(name), "{name} not called");
    }
    assert!(!c.tun_handled_in_core());
    assert!(!c.sing_box_front_active());
}

#[test]
fn core_running_matrix_defaults_to_sing_box() {
    let (st, c) = controller();
    assert_eq!(c.active_proxy_core(), ProxyCore::SingBox);
    assert!(!c.core_running(EngineType::Standard));
    st.sb.set(true);
    assert!(c.core_running(EngineType::Standard));
    assert!(!c.core_running(EngineType::Stealth));
    assert!(!c.core_running(EngineType::Chain));
    st.rtc.set(true);
    assert!(c.core_running(EngineType::Stealth));
    assert!(c.core_running(EngineType::Chain));
    assert!(!c.core_running(EngineType::VkTurn));
    st.ft.set(true);
    assert!(c.core_running(EngineType::VkTurn));
    st.ft.set(false);
    st.csqtt.set(true);
    assert!(c.core_running(EngineType::VkTurn));
    assert!(!c.core_running(EngineType::MasterDns));
    st.md.set(true);
    assert!(c.core_running(EngineType::MasterDns));
    assert!(!c.core_running(EngineType::OpenFlux));
    st.of.set(true);
    assert!(c.core_running(EngineType::OpenFlux));
    assert!(!c.core_running(EngineType::Snolc));
    st.snolc.set(true);
    assert!(c.core_running(EngineType::Snolc));
}

#[test]
fn stealth_start_fails_when_socks_port_busy() {
    let (st, mut c) = controller();
    st.busy_ports.borrow_mut().insert(10808);
    let loc = LocationConfig { engine: EngineType::Stealth, id: "room".into(), key: "00".into(), ..Default::default() };
    let err = c.start(&loc, "127.0.0.1", 10808, "", "", "dev", None).unwrap_err();
    assert_eq!(err, "SOCKS port 10808 is still in use");
    assert!(!st.called("rtc_start"));
}
