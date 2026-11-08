use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use yptun_core::location::{LocationConfig, TRANSPORT_DATACHANNEL, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_VP8CHANNEL};
use yptun_core::yptun_core::*;

#[derive(Default)]
struct Fake {
    calls: Mutex<Vec<String>>,
    err: Mutex<Option<String>>,
    logs: Mutex<VecDeque<String>>,
    wait_config: Mutex<Option<String>>,
    running: i32,
}

impl Fake {
    fn rec(&self, s: String) {
        self.calls.lock().unwrap().push(s);
    }
    fn err(&self) -> Option<String> {
        self.err.lock().unwrap().clone()
    }
}

struct Shared(Arc<Fake>);

impl YpTunCoreLib for Shared {
    fn poll_log(&self, timeout_ms: i32) -> Option<String> {
        self.0.rec(format!("poll {timeout_ms}"));
        self.0.logs.lock().unwrap().pop_front()
    }
    fn sb_version(&self) -> Option<String> { Some("sb1".into()) }
    fn sb_start(&self, c: &str) -> Option<String> { self.0.rec(format!("sb_start {c}")); self.0.err() }
    fn sb_stop(&self) { self.0.rec("sb_stop".into()) }
    fn sb_running(&self) -> i32 { self.0.running }
    fn bind_outbound_interface(&self, index: i32, pin_udp: i32) { self.0.rec(format!("bind {index} {pin_udp}")) }
    fn add_native_search_path(&self, dir: &str) { self.0.rec(format!("search {dir}")) }
    fn xray_set_asset_path(&self, dir: &str) { self.0.rec(format!("assets {dir}")) }
    fn xray_version(&self) -> Option<String> { Some("x1".into()) }
    fn xray_start(&self, _c: &str) -> Option<String> { self.0.err() }
    fn xray_stop(&self) { self.0.rec("xray_stop".into()) }
    fn xray_running(&self) -> i32 { self.0.running }
    fn xray_measure_delay(&self, _c: &str, url: &str, method: &str, t: i32) -> i64 { self.0.rec(format!("xdelay {url} {method} {t}")); 42 }
    fn awg_version(&self) -> Option<String> { None }
    fn awg_start(&self, _i: &str, _l: &str) -> Option<String> { self.0.err() }
    fn awg_stop(&self) { self.0.rec("awg_stop".into()) }
    fn awg_running(&self) -> i32 { self.0.running }
    fn awg_probe(&self, _i: &str) -> i64 { 7 }
    fn awg_measure_delay(&self, _i: &str, _u: &str, _m: &str, _t: i32) -> i64 { 8 }
    fn awg_generate_key_pair(&self) -> Option<String> { Some("priv|pub".into()) }
    fn tg_awg_start(&self, _i: &str, _l: &str, _u: &str, _p: &str) -> Option<String> { self.0.err() }
    fn tg_awg_stop(&self) { self.0.rec("tg_awg_stop".into()) }
    fn tg_awg_running(&self) -> i32 { self.0.running }
    fn ft_version(&self) -> Option<String> { Some("ft1".into()) }
    fn ft_start(&self, _u: &str, _l: &str, _v: &str, _n: i32) -> Option<String> { self.0.err() }
    fn ft_stop(&self) { self.0.rec("ft_stop".into()) }
    fn ft_running(&self) -> i32 { self.0.running }
    fn ft_connected_streams(&self) -> i32 { 3 }
    fn ft_captcha_url(&self) -> Option<String> { Some("http://127.0.0.1:1/c".into()) }
    fn ft_captcha_active(&self) -> i32 { 1 }
    fn wdtt_start(&self, _o: &str) -> Option<String> { self.0.err() }
    fn wdtt_last_error(&self) -> Option<String> { None }
    fn wdtt_wait_config(&self, _t: i32) -> Option<String> { self.0.wait_config.lock().unwrap().clone() }
    fn wdtt_stop(&self) { self.0.rec("wdtt_stop".into()) }
    fn wdtt_running(&self) -> i32 { self.0.running }
    fn wdtt_push_captcha(&self, token: &str) { self.0.rec(format!("captcha {token}")) }
    fn wdtt_version(&self) -> Option<String> { Some("w1".into()) }
    fn master_dns_start(
        &self, w: &str, d: &str, k: &str, em: i32, r: &str, l: &str, u: &str, p: &str, b: i32, pd: i32, uc: i32, dc: i32,
    ) -> Option<String> {
        self.0.rec(format!("mdns {w} {d} {k} {em} {r} {l} {u} {p} {b} {pd} {uc} {dc}"));
        self.0.err()
    }
    fn master_dns_stop(&self) { self.0.rec("master_dns_stop".into()) }
    fn master_dns_running(&self) -> i32 { self.0.running }
    fn master_dns_last_error(&self) -> Option<String> { Some("gone".into()) }
    fn master_dns_version(&self) -> Option<String> { Some("m1".into()) }
    fn rtc_version(&self) -> Option<String> { Some("r1".into()) }
    fn rtc_set_transport(&self, t: &str) { self.0.rec(format!("transport {t}")) }
    fn rtc_set_telemost_cookies(&self, _c: &str) {}
    fn rtc_set_dns(&self, d: &str) { self.0.rec(format!("dns {d}")) }
    fn rtc_set_socks_listen_host(&self, _h: &str) {}
    fn rtc_set_vp8_options(&self, fps: i32, batch: i32) { self.0.rec(format!("vp8 {fps} {batch}")) }
    fn rtc_set_sei_options(&self, fps: i32, batch: i32, frag: i32, ack: i32) -> Option<String> {
        self.0.rec(format!("sei {fps} {batch} {frag} {ack}"));
        self.0.err()
    }
    fn rtc_set_video_options(&self, w: i32, h: i32, fps: i32, qr: i32, rec: &str, codec: &str, tm: i32, trs: i32) -> Option<String> {
        self.0.rec(format!("video {w} {h} {fps} {qr} {rec} {codec} {tm} {trs}"));
        self.0.err()
    }
    fn rtc_set_liveness_options(&self, _i: i32, _t: i32, _f: i32) {}
    fn rtc_start(&self, c: &str, t: &str, r: &str, id: &str, k: &str, port: i32, u: &str, p: &str) -> Option<String> {
        self.0.rec(format!("rtc_start {c} {t} {r} {id} {k} {port} {u} {p}"));
        self.0.err()
    }
    fn rtc_start_room(&self, c: &str, t: &str, r: &str, id: &str, k: &str, port: i32, u: &str, p: &str, ready: i32) -> i64 {
        self.0.rec(format!("room {c} {t} {r} {id} {k} {port} {u} {p} {ready}"));
        5
    }
    fn rtc_stop_room(&self, h: i64) { self.0.rec(format!("stop_room {h}")) }
    fn rtc_stop_all_rooms(&self) { self.0.rec("rtc_stop_all_rooms".into()) }
    fn rtc_rooms_running(&self) -> i32 { 2 }
    fn rtc_room_healthy(&self, _h: i64) -> i32 { 1 }
    fn rtc_room_port(&self, _h: i64) -> i32 { 40000 }
    fn rtc_wait_ready(&self, _t: i32) -> Option<String> { self.0.err() }
    fn rtc_stop(&self) { self.0.rec("rtc_stop".into()) }
    fn rtc_running(&self) -> i32 { self.0.running }
    fn rtc_check(&self, c: &str, t: &str, r: &str, id: &str, k: &str, port: i32, to: i32, fps: i32, batch: i32) -> i64 {
        self.0.rec(format!("check {c} {t} {r} {id} {k} {port} {to} {fps} {batch}"));
        120
    }
    fn rtc_ping(&self, _c: &str, _t: &str, _r: &str, _id: &str, _k: &str, _p: i32, _to: i32, _u: &str, _f: i32, _b: i32) -> i64 { 0 }
}

fn core_with(fake: Fake) -> (YpTunCore, Arc<Fake>) {
    let fake = Arc::new(fake);
    (YpTunCore::new(Some(Box::new(Shared(fake.clone())))), fake)
}

fn calls(f: &Fake) -> Vec<String> {
    f.calls.lock().unwrap().clone()
}

#[test]
fn library_file_names() {
    assert_eq!(library_file_name("Windows 11", "amd64").as_deref(), Some("yptuncore-windows-amd64.dll"));
    assert_eq!(library_file_name("Linux", "aarch64").as_deref(), Some("yptuncore-linux-arm64.so"));
    assert_eq!(library_file_name("Mac OS X", "x86_64").as_deref(), Some("yptuncore-darwin-amd64.dylib"));
    assert_eq!(library_file_name("Mac OS X", "ARM64").as_deref(), Some("yptuncore-darwin-arm64.dylib"));
    assert_eq!(library_file_name("Linux", "x86"), None);
    assert_eq!(library_file_name("FreeBSD", "amd64"), None);
    assert_eq!(resource_path("yptuncore-linux-amd64.so"), "native/yptuncore-linux-amd64.so");
    assert!(SYMBOLS.contains(&"YpFree") && SYMBOLS.contains(&"YpRtcPing"));
}

#[test]
fn check_maps_error_strings() {
    assert_eq!(check(None, "x"), Ok(()));
    assert_eq!(check(Some("  ".into()), "x"), Ok(()));
    assert_eq!(check(Some("bad".into()), "what"), Err("what: bad".to_string()));
}

#[test]
fn unavailable_library_defaults() {
    let core = YpTunCore::new(None);
    assert!(!core.is_available());
    assert_eq!(core.sb_start("{}"), Err(UNAVAILABLE_ERROR.to_string()));
    assert_eq!(core.sb_version(), Err(UNAVAILABLE_ERROR.to_string()));
    assert_eq!(core.xray_measure_delay("{}", "u", "GET", 1), Err(UNAVAILABLE_ERROR.to_string()));
    assert!(!core.sb_running() && !core.xray_running() && !core.rtc_running() && !core.ft_captcha_active());
    assert_eq!(core.ft_connected_streams(), 0);
    assert_eq!(core.ft_captcha_url(), "");
    assert_eq!(core.wdtt_last_error(), "");
    assert_eq!(core.rtc_rooms_running(), 0);
    assert_eq!(core.rtc_room_port(1), 0);
    assert!(!core.rtc_room_healthy(1));
    core.bind_outbound_interface(3, true);
    core.stop_all();
    core.on_loaded(Some("/x"));
    assert!(!core.pump_log_once(10));
    assert!(YpTunCore::start_log_pump(Arc::new(core)).is_none());
}

#[test]
fn start_errors_are_prefixed() {
    let (core, fake) = core_with(Fake::default());
    assert_eq!(core.sb_start("{}"), Ok(()));
    *fake.err.lock().unwrap() = Some("boom".into());
    assert_eq!(core.sb_start("{}"), Err("sing-box start failed: boom".into()));
    assert_eq!(core.xray_start("{}"), Err("xray start failed: boom".into()));
    assert_eq!(core.awg_start("i", "l"), Err("AmneziaWG start failed: boom".into()));
    assert_eq!(core.tg_awg_start("i", "l", "u", "p"), Err("Telegram WARP start failed: boom".into()));
    assert_eq!(core.ft_start("u", "l", "v", 1), Err("VK-TURN start failed: boom".into()));
    assert_eq!(core.wdtt_start("{}"), Err("WDTT start failed: boom".into()));
    assert_eq!(core.master_dns_start(&MasterDnsStart::default()), Err("MasterDNS start failed: boom".into()));
    assert_eq!(core.rtc_start("c", "t", "r", "id", "k", 1, "u", "p"), Err("olcRTC start failed: boom".into()));
    assert_eq!(core.rtc_wait_ready(1), Err("olcRTC not ready: boom".into()));
}

#[test]
fn passthrough_values() {
    let (core, fake) = core_with(Fake { running: 1, ..Default::default() });
    assert!(core.is_available());
    assert_eq!(core.sb_version(), Ok("sb1".into()));
    assert_eq!(core.awg_version(), Ok(String::new()));
    assert_eq!(core.awg_generate_key_pair(), Ok("priv|pub".into()));
    assert_eq!(core.awg_probe("i"), Ok(7));
    assert_eq!(core.xray_measure_delay("{}", "https://x", "HEAD", 500), Ok(42));
    assert!(core.sb_running() && core.xray_running() && core.awg_running() && core.tg_awg_running());
    assert!(core.ft_running() && core.wdtt_running() && core.master_dns_running() && core.rtc_running());
    assert_eq!(core.ft_connected_streams(), 3);
    assert_eq!(core.ft_captcha_url(), "http://127.0.0.1:1/c");
    assert!(core.ft_captcha_active());
    assert_eq!(core.master_dns_last_error(), "gone");
    assert_eq!(core.wdtt_wait_config(10), Ok(None));
    *fake.wait_config.lock().unwrap() = Some("  ".into());
    assert_eq!(core.wdtt_wait_config(10), Ok(None));
    *fake.wait_config.lock().unwrap() = Some("[Interface]".into());
    assert_eq!(core.wdtt_wait_config(10), Ok(Some("[Interface]".into())));
    assert_eq!(core.rtc_rooms_running(), 2);
    assert!(core.rtc_room_healthy(5));
    assert_eq!(core.rtc_room_port(5), 40000);

    core.bind_outbound_interface(3, false);
    core.bind_outbound_interface(4, true);
    core.wdtt_push_captcha("tok").unwrap();
    core.rtc_set_dns("1.1.1.1").unwrap();
    assert_eq!(
        calls(&fake),
        vec!["xdelay https://x HEAD 500", "bind 3 0", "bind 4 1", "captcha tok", "dns 1.1.1.1"]
    );
}

#[test]
fn rooms_check_and_master_dns_args() {
    let (core, fake) = core_with(Fake::default());
    assert_eq!(core.rtc_start_room("c", "t", "r", "id", "k", "u", "p", 9000), Ok(5));
    assert_eq!(core.rtc_check("c", "t", "r", "id", "k", 1080, 3000), Ok(120));
    let a = MasterDnsStart {
        work_dir: "w".into(),
        domains: "d".into(),
        encryption_key: "k".into(),
        encryption_method: 2,
        resolvers: "r".into(),
        listen_addr: "l".into(),
        socks_user: "u".into(),
        socks_pass: "p".into(),
        balancing_strategy: 1,
        ..Default::default()
    };
    core.master_dns_start(&a).unwrap();
    core.rtc_stop_room(5);
    assert_eq!(
        calls(&fake),
        vec![
            "room c t r id k 0 u p 9000",
            "check c t r id k 1080 3000 0 0",
            "mdns w d k 2 r l u p 1 0 0 0",
            "stop_room 5",
        ]
    );
}

#[test]
fn transport_options() {
    let (core, fake) = core_with(Fake::default());
    let vp8 = LocationConfig { transport: TRANSPORT_VP8CHANNEL.into(), vp8_fps: 30, vp8_batch: 16, ..Default::default() };
    core.rtc_apply_transport_options(&vp8).unwrap();

    let sei = LocationConfig { transport: TRANSPORT_SEICHANNEL.into(), ..Default::default() };
    core.rtc_apply_transport_options(&sei).unwrap();
    let s = sei.sei_options();

    let video = LocationConfig { transport: TRANSPORT_VIDEOCHANNEL.into(), ..Default::default() };
    core.rtc_apply_transport_options(&video).unwrap();
    let v = video.video_options();

    let dc = LocationConfig { transport: TRANSPORT_DATACHANNEL.into(), ..Default::default() };
    core.rtc_apply_transport_options(&dc).unwrap();

    assert_eq!(
        calls(&fake),
        vec![
            "vp8 30 16".to_string(),
            format!("sei {} {} {} {}", s.fps, s.batch, s.fragment_size, s.ack_timeout_ms),
            format!("video {} {} {} {} {} {} {} {}", v.width, v.height, v.fps, v.qr_size, v.qr_recovery, v.codec, v.tile_module, v.tile_rs),
        ]
    );

    *fake.err.lock().unwrap() = Some("bad".into());
    assert_eq!(core.rtc_apply_transport_options(&sei), Err("olcRTC SEI options: bad".into()));
    assert_eq!(core.rtc_apply_transport_options(&video), Err("olcRTC video options: bad".into()));
    assert_eq!(core.rtc_apply_transport_options(&vp8), Ok(()));
}

#[test]
fn stop_all_order() {
    let (core, fake) = core_with(Fake::default());
    core.stop_all();
    assert_eq!(
        calls(&fake),
        vec!["sb_stop", "xray_stop", "ft_stop", "wdtt_stop", "master_dns_stop", "awg_stop", "rtc_stop", "rtc_stop_all_rooms"]
    );
}

#[test]
fn log_pump_and_search_path() {
    let (core, fake) = core_with(Fake::default());
    core.on_loaded(Some("/data/bin"));
    core.on_loaded(None);
    let got = Arc::new(Mutex::new(Vec::<String>::new()));
    let g = got.clone();
    core.add_log_sink(|line: &str| {
        if !line.is_empty() {
            panic!("sink failure must not stop the others");
        }
    });
    core.add_log_sink(move |line: &str| g.lock().unwrap().push(line.to_string()));
    fake.logs.lock().unwrap().push_back("xray: started".into());
    assert!(core.pump_log_once(LOG_POLL_TIMEOUT_MS));
    assert!(core.pump_log_once(LOG_POLL_TIMEOUT_MS));
    assert_eq!(*got.lock().unwrap(), vec!["xray: started".to_string()]);
    assert_eq!(calls(&fake), vec!["search /data/bin", "poll 1000", "poll 1000"]);
}
