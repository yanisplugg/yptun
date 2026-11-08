//! Facade over `yptuncore` -- port of `YpTunCore.kt`.
//!
//! `yptuncore` is the desktop c-shared build of every YPtun proxy core (sing-box, xray, AmneziaWG,
//! Hysteria2, VK-TURN/freeturn, qWDTT, MasterDNS, olcrtc) in one shared Go runtime, mirroring the
//! Android gomobile AAR. Built from `cores/cmd/yptuncore`.
//!
//! The raw C ABI (dlopen/LoadLibrary, `YpFree` of returned C strings, UTF-8) lives behind
//! [YpTunCoreLib]: every method that returns a Go C string gets it already read and freed
//! (`None` = NULL = success / no data). This module turns error strings into `Err`, applies the
//! "library missing" defaults and pumps the Go log bus into the log sinks.

#![allow(clippy::too_many_arguments)]

use std::sync::{Arc, Mutex};

use crate::location::{LocationConfig, TRANSPORT_SEICHANNEL, TRANSPORT_VIDEOCHANNEL, TRANSPORT_VP8CHANNEL};

pub type CoreResult<T> = Result<T, String>;

pub const UNAVAILABLE_ERROR: &str = "yptuncore native library is not available on this platform";
pub const RESOURCE_DIR: &str = "native";
pub const TEMP_PREFIX: &str = "yptuncore-";
pub const LOG_THREAD_NAME: &str = "yptuncore-logs";
pub const LOG_POLL_TIMEOUT_MS: i32 = 1_000;

/// The exported C symbols of `yptuncore`, for the platform loader.
pub const SYMBOLS: &[&str] = &[
    "YpFree", "YpPollLog",
    "YpSbVersion", "YpSbStart", "YpSbStop", "YpSbRunning",
    "YpBindOutboundInterface", "YpAddNativeSearchPath",
    "YpXraySetAssetPath", "YpXrayVersion", "YpXrayStart", "YpXrayStop", "YpXrayRunning", "YpXrayMeasureDelay",
    "YpAwgVersion", "YpAwgStart", "YpAwgStop", "YpAwgRunning", "YpAwgProbe", "YpAwgMeasureDelay", "YpAwgGenerateKeyPair",
    "YpTgAwgStart", "YpTgAwgStop", "YpTgAwgRunning",
    "YpFtVersion", "YpFtStart", "YpFtStop", "YpFtRunning", "YpFtConnectedStreams", "YpFtCaptchaURL", "YpFtCaptchaActive",
    "YpWdttStart", "YpWdttLastError", "YpWdttWaitConfig", "YpWdttStop", "YpWdttRunning", "YpWdttPushCaptcha", "YpWdttVersion",
    "YpMasterDnsStart", "YpMasterDnsStop", "YpMasterDnsRunning", "YpMasterDnsLastError", "YpMasterDnsVersion",
    "YpRtcVersion", "YpRtcSetTransport", "YpRtcSetTelemostCookies", "YpRtcSetDNS", "YpRtcSetSocksListenHost",
    "YpRtcSetVP8Options", "YpRtcSetSEIOptions", "YpRtcSetVideoOptions", "YpRtcSetLivenessOptions",
    "YpRtcStart", "YpRtcStartRoom", "YpRtcStopRoom", "YpRtcStopAllRooms", "YpRtcRoomsRunning",
    "YpRtcRoomHealthy", "YpRtcRoomPort", "YpRtcWaitReady", "YpRtcStop", "YpRtcRunning", "YpRtcCheck", "YpRtcPing",
];

/// Raw `yptuncore` calls. `Option<String>` returns are Go C strings already read (UTF-8) and
/// released with `YpFree`; `None` is NULL. Ints are the C ints (1 = true).
pub trait YpTunCoreLib: Send + Sync {
    fn poll_log(&self, timeout_ms: i32) -> Option<String>;

    fn sb_version(&self) -> Option<String>;
    fn sb_start(&self, config_json: &str) -> Option<String>;
    fn sb_stop(&self);
    fn sb_running(&self) -> i32;

    fn bind_outbound_interface(&self, index: i32, pin_udp: i32);
    fn add_native_search_path(&self, dir: &str);

    fn xray_set_asset_path(&self, dir: &str);
    fn xray_version(&self) -> Option<String>;
    fn xray_start(&self, config_json: &str) -> Option<String>;
    fn xray_stop(&self);
    fn xray_running(&self) -> i32;
    fn xray_measure_delay(&self, config_json: &str, url: &str, method: &str, timeout_ms: i32) -> i64;

    fn awg_version(&self) -> Option<String>;
    fn awg_start(&self, ini_config: &str, listen_addr: &str) -> Option<String>;
    fn awg_stop(&self);
    fn awg_running(&self) -> i32;
    fn awg_probe(&self, ini_config: &str) -> i64;
    fn awg_measure_delay(&self, ini_config: &str, url: &str, method: &str, timeout_ms: i32) -> i64;
    fn awg_generate_key_pair(&self) -> Option<String>;

    fn tg_awg_start(&self, ini_config: &str, listen_addr: &str, user: &str, pass: &str) -> Option<String>;
    fn tg_awg_stop(&self);
    fn tg_awg_running(&self) -> i32;

    fn ft_version(&self) -> Option<String>;
    fn ft_start(&self, uri: &str, listen_addr: &str, vk_link: &str, n_streams: i32) -> Option<String>;
    fn ft_stop(&self);
    fn ft_running(&self) -> i32;
    fn ft_connected_streams(&self) -> i32;
    fn ft_captcha_url(&self) -> Option<String>;
    fn ft_captcha_active(&self) -> i32;

    fn wdtt_start(&self, options_json: &str) -> Option<String>;
    fn wdtt_last_error(&self) -> Option<String>;
    fn wdtt_wait_config(&self, timeout_ms: i32) -> Option<String>;
    fn wdtt_stop(&self);
    fn wdtt_running(&self) -> i32;
    fn wdtt_push_captcha(&self, token: &str);
    fn wdtt_version(&self) -> Option<String>;

    fn master_dns_start(
        &self,
        work_dir: &str,
        domains: &str,
        encryption_key: &str,
        encryption_method: i32,
        resolvers: &str,
        listen_addr: &str,
        socks_user: &str,
        socks_pass: &str,
        balancing_strategy: i32,
        packet_duplication: i32,
        upload_compression: i32,
        download_compression: i32,
    ) -> Option<String>;
    fn master_dns_stop(&self);
    fn master_dns_running(&self) -> i32;
    fn master_dns_last_error(&self) -> Option<String>;
    fn master_dns_version(&self) -> Option<String>;

    fn rtc_version(&self) -> Option<String>;
    fn rtc_set_transport(&self, transport: &str);
    fn rtc_set_telemost_cookies(&self, cookies: &str);
    fn rtc_set_dns(&self, dns_server: &str);
    fn rtc_set_socks_listen_host(&self, host: &str);
    fn rtc_set_vp8_options(&self, fps: i32, batch_size: i32);
    fn rtc_set_sei_options(&self, fps: i32, batch_size: i32, fragment_size: i32, ack_timeout_ms: i32) -> Option<String>;
    fn rtc_set_video_options(
        &self,
        width: i32,
        height: i32,
        fps: i32,
        qr_size: i32,
        qr_recovery: &str,
        codec: &str,
        tile_module: i32,
        tile_rs: i32,
    ) -> Option<String>;
    fn rtc_set_liveness_options(&self, interval_ms: i32, timeout_ms: i32, failures: i32);
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
    ) -> Option<String>;
    fn rtc_start_room(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        socks_user: &str,
        socks_pass: &str,
        ready_timeout_ms: i32,
    ) -> i64;
    fn rtc_stop_room(&self, handle: i64);
    fn rtc_stop_all_rooms(&self);
    fn rtc_rooms_running(&self) -> i32;
    fn rtc_room_healthy(&self, handle: i64) -> i32;
    fn rtc_room_port(&self, handle: i64) -> i32;
    fn rtc_wait_ready(&self, timeout_ms: i32) -> Option<String>;
    fn rtc_stop(&self);
    fn rtc_running(&self) -> i32;
    fn rtc_check(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        timeout_ms: i32,
        vp8_fps: i32,
        vp8_batch: i32,
    ) -> i64;
    fn rtc_ping(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        timeout_ms: i32,
        ping_url: &str,
        vp8_fps: i32,
        vp8_batch: i32,
    ) -> i64;
}

/// The bundled library for a JVM-style `os.name` / `os.arch` pair (checked in the Kotlin order).
pub fn library_file_name(os_name: &str, os_arch: &str) -> Option<String> {
    let os = os_name.to_lowercase();
    let arch = match os_arch.to_lowercase().as_str() {
        "aarch64" | "arm64" => "arm64",
        "x86_64" | "amd64" => "amd64",
        _ => return None,
    };
    if os.contains("win") {
        Some(format!("yptuncore-windows-{arch}.dll"))
    } else if os.contains("linux") {
        Some(format!("yptuncore-linux-{arch}.so"))
    } else if os.contains("mac") || os.contains("darwin") {
        Some(format!("yptuncore-darwin-{arch}.dylib"))
    } else {
        None
    }
}

/// Classpath/resource path of the bundled library.
pub fn resource_path(file_name: &str) -> String {
    format!("{RESOURCE_DIR}/{file_name}")
}

/// `Err("what: err")` when the call returned a non-blank error string.
pub fn check(err: Option<String>, what: &str) -> CoreResult<()> {
    match err {
        Some(e) if !e.trim().is_empty() => Err(format!("{what}: {e}")),
        _ => Ok(()),
    }
}

/// MasterDNS start arguments; zero for any tuning knob keeps the MasterDnsVPN default.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct MasterDnsStart {
    pub work_dir: String,
    pub domains: String,
    pub encryption_key: String,
    pub encryption_method: i32,
    pub resolvers: String,
    pub listen_addr: String,
    pub socks_user: String,
    pub socks_pass: String,
    pub balancing_strategy: i32,
    pub packet_duplication: i32,
    pub upload_compression: i32,
    pub download_compression: i32,
}

type LogSink = Box<dyn Fn(&str) + Send + Sync>;

/// The facade. `lib` is `None` when the library could not be loaded on this platform.
pub struct YpTunCore {
    lib: Option<Box<dyn YpTunCoreLib>>,
    log_sinks: Mutex<Vec<LogSink>>,
}

impl YpTunCore {
    pub fn new(lib: Option<Box<dyn YpTunCoreLib>>) -> Self {
        Self { lib, log_sinks: Mutex::new(Vec::new()) }
    }

    /// After a successful load: NaiveProxy's cronet is loaded lazily by name from PATH, so point
    /// the core at the directory the bundled natives are unpacked into.
    pub fn on_loaded(&self, cronet_library_dir: Option<&str>) {
        if let (Some(lib), Some(dir)) = (self.lib.as_deref(), cronet_library_dir) {
            lib.add_native_search_path(dir);
        }
    }

    pub fn is_available(&self) -> bool {
        self.lib.is_some()
    }

    fn lib(&self) -> CoreResult<&dyn YpTunCoreLib> {
        self.lib.as_deref().ok_or_else(|| UNAVAILABLE_ERROR.to_string())
    }

    fn opt(&self) -> Option<&dyn YpTunCoreLib> {
        self.lib.as_deref()
    }

    // logs ----------------------------------------------------------------------------------

    /// External log consumers; each line is already "tag: message".
    pub fn add_log_sink(&self, sink: impl Fn(&str) + Send + Sync + 'static) {
        self.log_sinks.lock().unwrap_or_else(|e| e.into_inner()).push(Box::new(sink));
    }

    pub fn dispatch_log(&self, line: &str) {
        let sinks = self.log_sinks.lock().unwrap_or_else(|e| e.into_inner());
        for sink in sinks.iter() {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| sink(line)));
        }
    }

    /// One poll of the Go log bus; `false` when there is no library.
    pub fn pump_log_once(&self, timeout_ms: i32) -> bool {
        let Some(lib) = self.opt() else { return false };
        if let Some(line) = lib.poll_log(timeout_ms) {
            self.dispatch_log(&line);
        }
        true
    }

    /// The daemon log thread (Kotlin `startLogPump`).
    pub fn start_log_pump(core: Arc<Self>) -> Option<std::thread::JoinHandle<()>> {
        if !core.is_available() {
            return None;
        }
        std::thread::Builder::new()
            .name(LOG_THREAD_NAME.to_string())
            .spawn(move || {
                while core.pump_log_once(LOG_POLL_TIMEOUT_MS) {}
            })
            .ok()
    }

    // sing-box ------------------------------------------------------------------------------

    pub fn sb_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.sb_version().unwrap_or_default())
    }
    pub fn sb_start(&self, config_json: &str) -> CoreResult<()> {
        check(self.lib()?.sb_start(config_json), "sing-box start failed")
    }
    pub fn sb_stop(&self) -> CoreResult<()> {
        self.lib()?.sb_stop();
        Ok(())
    }
    pub fn sb_running(&self) -> bool {
        self.opt().map_or(false, |l| l.sb_running() == 1)
    }

    /// Pins the sockets xray opens to the physical adapter `index` (0 = off) -- the Windows
    /// stand-in for VpnService.protect(). `pin_udp` must be false for a config whose UDP goes to a
    /// local hop (the VK-TURN WireGuard-over-Xray exit).
    pub fn bind_outbound_interface(&self, index: i32, pin_udp: bool) {
        if let Some(l) = self.opt() {
            l.bind_outbound_interface(index, if pin_udp { 1 } else { 0 });
        }
    }

    // xray ----------------------------------------------------------------------------------

    pub fn xray_set_asset_path(&self, dir: &str) -> CoreResult<()> {
        self.lib()?.xray_set_asset_path(dir);
        Ok(())
    }
    pub fn xray_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.xray_version().unwrap_or_default())
    }
    pub fn xray_start(&self, config_json: &str) -> CoreResult<()> {
        check(self.lib()?.xray_start(config_json), "xray start failed")
    }
    pub fn xray_stop(&self) -> CoreResult<()> {
        self.lib()?.xray_stop();
        Ok(())
    }
    pub fn xray_running(&self) -> bool {
        self.opt().map_or(false, |l| l.xray_running() == 1)
    }
    pub fn xray_measure_delay(&self, config_json: &str, url: &str, method: &str, timeout_ms: i32) -> CoreResult<i64> {
        Ok(self.lib()?.xray_measure_delay(config_json, url, method, timeout_ms))
    }

    // AmneziaWG -----------------------------------------------------------------------------

    pub fn awg_start(&self, ini_config: &str, listen_addr: &str) -> CoreResult<()> {
        check(self.lib()?.awg_start(ini_config, listen_addr), "AmneziaWG start failed")
    }
    pub fn awg_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.awg_version().unwrap_or_default())
    }
    pub fn awg_stop(&self) {
        if let Some(l) = self.opt() {
            l.awg_stop();
        }
    }
    pub fn awg_running(&self) -> bool {
        self.opt().map_or(false, |l| l.awg_running() == 1)
    }
    pub fn awg_probe(&self, ini_config: &str) -> CoreResult<i64> {
        Ok(self.lib()?.awg_probe(ini_config))
    }
    pub fn awg_measure_delay(&self, ini_config: &str, url: &str, method: &str, timeout_ms: i32) -> CoreResult<i64> {
        Ok(self.lib()?.awg_measure_delay(ini_config, url, method, timeout_ms))
    }
    /// "privateKey|publicKey", base64 -- for the WARP generator's direct-registration fallback.
    pub fn awg_generate_key_pair(&self) -> CoreResult<String> {
        Ok(self.lib()?.awg_generate_key_pair().unwrap_or_default())
    }

    // Telegram-over-WARP proxy (its OWN AmneziaWG instance) ----------------------------------

    pub fn tg_awg_start(&self, ini_config: &str, listen_addr: &str, user: &str, pass: &str) -> CoreResult<()> {
        check(self.lib()?.tg_awg_start(ini_config, listen_addr, user, pass), "Telegram WARP start failed")
    }
    pub fn tg_awg_stop(&self) {
        if let Some(l) = self.opt() {
            l.tg_awg_stop();
        }
    }
    pub fn tg_awg_running(&self) -> bool {
        self.opt().map_or(false, |l| l.tg_awg_running() == 1)
    }

    // VK-TURN -------------------------------------------------------------------------------

    pub fn ft_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.ft_version().unwrap_or_default())
    }
    pub fn ft_start(&self, uri: &str, listen_addr: &str, vk_link: &str, n_streams: i32) -> CoreResult<()> {
        check(self.lib()?.ft_start(uri, listen_addr, vk_link, n_streams), "VK-TURN start failed")
    }
    pub fn ft_stop(&self) {
        if let Some(l) = self.opt() {
            l.ft_stop();
        }
    }
    pub fn ft_running(&self) -> bool {
        self.opt().map_or(false, |l| l.ft_running() == 1)
    }
    pub fn ft_connected_streams(&self) -> i32 {
        self.opt().map_or(0, |l| l.ft_connected_streams())
    }
    /// URL of a pending manual VK captcha; empty when there is none.
    pub fn ft_captcha_url(&self) -> String {
        self.opt().and_then(|l| l.ft_captcha_url()).unwrap_or_default()
    }
    /// True while the user is solving a VK captcha.
    pub fn ft_captcha_active(&self) -> bool {
        self.opt().map_or(0, |l| l.ft_captcha_active()) == 1
    }

    // VK-TURN / qWDTT core --------------------------------------------------------------------

    pub fn wdtt_start(&self, options_json: &str) -> CoreResult<()> {
        check(self.lib()?.wdtt_start(options_json), "WDTT start failed")
    }
    /// Why the core stopped on its own ("" while fine).
    pub fn wdtt_last_error(&self) -> String {
        self.opt().and_then(|l| l.wdtt_last_error()).unwrap_or_default()
    }
    /// The wdtt-server's WireGuard config, or `None` if it didn't arrive in time.
    pub fn wdtt_wait_config(&self, timeout_ms: i32) -> CoreResult<Option<String>> {
        Ok(self.lib()?.wdtt_wait_config(timeout_ms).filter(|s| !s.trim().is_empty()))
    }
    pub fn wdtt_stop(&self) {
        if let Some(l) = self.opt() {
            l.wdtt_stop();
        }
    }
    pub fn wdtt_running(&self) -> bool {
        self.opt().map_or(false, |l| l.wdtt_running() == 1)
    }
    pub fn wdtt_push_captcha(&self, token: &str) -> CoreResult<()> {
        self.lib()?.wdtt_push_captcha(token);
        Ok(())
    }
    pub fn wdtt_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.wdtt_version().unwrap_or_default())
    }

    // MasterDNS -------------------------------------------------------------------------------

    pub fn master_dns_start(&self, a: &MasterDnsStart) -> CoreResult<()> {
        check(
            self.lib()?.master_dns_start(
                &a.work_dir,
                &a.domains,
                &a.encryption_key,
                a.encryption_method,
                &a.resolvers,
                &a.listen_addr,
                &a.socks_user,
                &a.socks_pass,
                a.balancing_strategy,
                a.packet_duplication,
                a.upload_compression,
                a.download_compression,
            ),
            "MasterDNS start failed",
        )
    }
    pub fn master_dns_stop(&self) {
        if let Some(l) = self.opt() {
            l.master_dns_stop();
        }
    }
    pub fn master_dns_running(&self) -> bool {
        self.opt().map_or(false, |l| l.master_dns_running() == 1)
    }
    /// Why the tunnel's run loop exited; empty while it is alive.
    pub fn master_dns_last_error(&self) -> String {
        self.opt().and_then(|l| l.master_dns_last_error()).unwrap_or_default()
    }
    pub fn master_dns_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.master_dns_version().unwrap_or_default())
    }

    // olcrtc ----------------------------------------------------------------------------------

    pub fn rtc_version(&self) -> CoreResult<String> {
        Ok(self.lib()?.rtc_version().unwrap_or_default())
    }
    pub fn rtc_set_transport(&self, transport: &str) -> CoreResult<()> {
        self.lib()?.rtc_set_transport(transport);
        Ok(())
    }
    pub fn rtc_set_telemost_cookies(&self, cookies: &str) -> CoreResult<()> {
        self.lib()?.rtc_set_telemost_cookies(cookies);
        Ok(())
    }
    pub fn rtc_set_dns(&self, dns_server: &str) -> CoreResult<()> {
        self.lib()?.rtc_set_dns(dns_server);
        Ok(())
    }
    pub fn rtc_set_socks_listen_host(&self, host: &str) -> CoreResult<()> {
        self.lib()?.rtc_set_socks_listen_host(host);
        Ok(())
    }

    /// Per-transport options of a location (vp8 / sei / video), before [Self::rtc_start].
    pub fn rtc_apply_transport_options(&self, config: &LocationConfig) -> CoreResult<()> {
        match config.transport.as_str() {
            TRANSPORT_VP8CHANNEL => {
                self.lib()?.rtc_set_vp8_options(config.vp8_fps, config.vp8_batch);
                Ok(())
            }
            TRANSPORT_SEICHANNEL => {
                let o = config.sei_options();
                check(
                    self.lib()?.rtc_set_sei_options(o.fps, o.batch, o.fragment_size, o.ack_timeout_ms),
                    "olcRTC SEI options",
                )
            }
            TRANSPORT_VIDEOCHANNEL => {
                let o = config.video_options();
                check(
                    self.lib()?.rtc_set_video_options(
                        o.width,
                        o.height,
                        o.fps,
                        o.qr_size,
                        &o.qr_recovery,
                        &o.codec,
                        o.tile_module,
                        o.tile_rs,
                    ),
                    "olcRTC video options",
                )
            }
            _ => Ok(()),
        }
    }

    pub fn rtc_start(
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
        check(
            self.lib()?.rtc_start(carrier, transport, room_id, client_id, key_hex, socks_port, socks_user, socks_pass),
            "olcRTC start failed",
        )
    }

    /// One multi-room instance on an OS-assigned loopback port; its handle, or -1 on failure.
    pub fn rtc_start_room(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_user: &str,
        socks_pass: &str,
        ready_timeout_ms: i32,
    ) -> CoreResult<i64> {
        Ok(self.lib()?.rtc_start_room(carrier, transport, room_id, client_id, key_hex, 0, socks_user, socks_pass, ready_timeout_ms))
    }

    pub fn rtc_stop_room(&self, handle: i64) {
        if let Some(l) = self.opt() {
            l.rtc_stop_room(handle);
        }
    }
    pub fn rtc_stop_all_rooms(&self) {
        if let Some(l) = self.opt() {
            l.rtc_stop_all_rooms();
        }
    }
    pub fn rtc_rooms_running(&self) -> i32 {
        self.opt().map_or(0, |l| l.rtc_rooms_running())
    }
    pub fn rtc_room_healthy(&self, handle: i64) -> bool {
        self.opt().map_or(false, |l| l.rtc_room_healthy(handle) == 1)
    }
    pub fn rtc_room_port(&self, handle: i64) -> i32 {
        self.opt().map_or(0, |l| l.rtc_room_port(handle))
    }
    pub fn rtc_wait_ready(&self, timeout_ms: i32) -> CoreResult<()> {
        check(self.lib()?.rtc_wait_ready(timeout_ms), "olcRTC not ready")
    }
    pub fn rtc_stop(&self) {
        if let Some(l) = self.opt() {
            l.rtc_stop();
        }
    }
    pub fn rtc_running(&self) -> bool {
        self.opt().map_or(false, |l| l.rtc_running() == 1)
    }

    pub fn rtc_check(
        &self,
        carrier: &str,
        transport: &str,
        room_id: &str,
        client_id: &str,
        key_hex: &str,
        socks_port: i32,
        timeout_ms: i32,
    ) -> CoreResult<i64> {
        Ok(self.lib()?.rtc_check(carrier, transport, room_id, client_id, key_hex, socks_port, timeout_ms, 0, 0))
    }

    /// Stops every core (used on disconnect; mirrors Android stopMobile). Errors are ignored.
    pub fn stop_all(&self) {
        let _ = self.sb_stop();
        let _ = self.xray_stop();
        self.ft_stop();
        self.wdtt_stop();
        self.master_dns_stop();
        self.awg_stop();
        self.rtc_stop();
        self.rtc_stop_all_rooms();
    }
}
