//! The real `yptuncore` C ABI over `libloading` -- the Rust side of `Native.load(...)` in
//! `YpTunCore.kt` (desktop only: Windows, Linux, macOS).
//!
//! * Every Go C string a call returns is read as UTF-8 and released with `YpFree`
//!   (`takeString` in Kotlin); NULL is `None`.
//! * Rust strings are already UTF-8, which is what the Go side expects (the Kotlin loader had to
//!   force JNA's string encoding to UTF-8 for Cyrillic user dirs).
//! * A Go c-shared runtime cannot be unloaded, so the library handle is never closed (the Kotlin
//!   object also lives for the whole process).
//! * All symbols are resolved once at load time; a library missing one of them fails to load.

use std::ffi::{CStr, CString};
use std::mem::ManuallyDrop;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use libloading::Library;

use crate::yptun_core::{library_file_name, YpTunCore, YpTunCoreLib, TEMP_PREFIX};

type P = *mut c_char;
type S = *const c_char;

/// The library file for the platform this binary was built for.
pub fn current_library_file_name() -> Option<String> {
    library_file_name(std::env::consts::OS, std::env::consts::ARCH)
}

/// Rust strings may contain NUL; the C side would stop at the first one anyway.
pub fn c_string(value: &str) -> CString {
    let head = value.split('\0').next().unwrap_or("");
    CString::new(head).unwrap_or_default()
}

/// Writes bundled library bytes to a fresh temp file (`yptuncore-...-<file>`), like the Kotlin
/// loader does with the classpath resource.
pub fn extract_to_temp(file_name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let path = std::env::temp_dir().join(format!("{TEMP_PREFIX}{}-{nanos}-{file_name}", std::process::id()));
    std::fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(path)
}

/// Loads `<dir>/<platform library>` and wires it up the way the Kotlin `libOrNull` does: start
/// the log pump, then point the core at the cronet directory. Any failure is logged and yields
/// an unavailable core (every call then reports the "not available" error).
pub fn load_desktop(dir: &Path, cronet_library_dir: Option<&str>) -> Arc<YpTunCore> {
    let Some(file_name) = current_library_file_name() else {
        eprintln!("YpTunCore: unsupported platform");
        return Arc::new(YpTunCore::new(None));
    };
    let path = dir.join(&file_name);
    if !path.is_file() {
        eprintln!("YpTunCore: library not found: {}", path.display());
        return Arc::new(YpTunCore::new(None));
    }
    match NativeYpTunCoreLib::load(&path) {
        Ok(lib) => {
            let core = Arc::new(YpTunCore::new(Some(Box::new(lib))));
            let _ = YpTunCore::start_log_pump(core.clone());
            core.on_loaded(cronet_library_dir);
            core
        }
        Err(e) => {
            eprintln!("YpTunCore: failed to load native library: {e}");
            Arc::new(YpTunCore::new(None))
        }
    }
}

unsafe fn sym<T: Copy>(lib: &Library, name: &str) -> Result<T, String> {
    let mut bytes = name.as_bytes().to_vec();
    bytes.push(0);
    lib.get::<T>(&bytes).map(|s| *s).map_err(|e| format!("{name}: {e}"))
}

struct Fns {
    free: unsafe extern "C" fn(P),
    poll_log: unsafe extern "C" fn(i32) -> P,

    sb_version: unsafe extern "C" fn() -> P,
    sb_start: unsafe extern "C" fn(S) -> P,
    sb_stop: unsafe extern "C" fn(),
    sb_running: unsafe extern "C" fn() -> i32,

    bind_outbound_interface: unsafe extern "C" fn(i32, i32),
    add_native_search_path: unsafe extern "C" fn(S),

    xray_set_asset_path: unsafe extern "C" fn(S),
    xray_version: unsafe extern "C" fn() -> P,
    xray_start: unsafe extern "C" fn(S) -> P,
    xray_stop: unsafe extern "C" fn(),
    xray_running: unsafe extern "C" fn() -> i32,
    xray_measure_delay: unsafe extern "C" fn(S, S, S, i32) -> i64,

    awg_version: unsafe extern "C" fn() -> P,
    awg_start: unsafe extern "C" fn(S, S) -> P,
    awg_stop: unsafe extern "C" fn(),
    awg_running: unsafe extern "C" fn() -> i32,
    awg_probe: unsafe extern "C" fn(S) -> i64,
    awg_measure_delay: unsafe extern "C" fn(S, S, S, i32) -> i64,
    awg_generate_key_pair: unsafe extern "C" fn() -> P,

    tg_awg_start: unsafe extern "C" fn(S, S, S, S) -> P,
    tg_awg_stop: unsafe extern "C" fn(),
    tg_awg_running: unsafe extern "C" fn() -> i32,

    ft_version: unsafe extern "C" fn() -> P,
    ft_start: unsafe extern "C" fn(S, S, S, i32) -> P,
    ft_stop: unsafe extern "C" fn(),
    ft_running: unsafe extern "C" fn() -> i32,
    ft_connected_streams: unsafe extern "C" fn() -> i32,
    ft_captcha_url: unsafe extern "C" fn() -> P,
    ft_captcha_active: unsafe extern "C" fn() -> i32,

    wdtt_start: unsafe extern "C" fn(S) -> P,
    wdtt_last_error: unsafe extern "C" fn() -> P,
    wdtt_wait_config: unsafe extern "C" fn(i32) -> P,
    wdtt_stop: unsafe extern "C" fn(),
    wdtt_running: unsafe extern "C" fn() -> i32,
    wdtt_push_captcha: unsafe extern "C" fn(S),
    wdtt_version: unsafe extern "C" fn() -> P,

    #[allow(clippy::type_complexity)]
    master_dns_start: unsafe extern "C" fn(S, S, S, i32, S, S, S, S, i32, i32, i32, i32) -> P,
    master_dns_stop: unsafe extern "C" fn(),
    master_dns_running: unsafe extern "C" fn() -> i32,
    master_dns_last_error: unsafe extern "C" fn() -> P,
    master_dns_version: unsafe extern "C" fn() -> P,

    rtc_version: unsafe extern "C" fn() -> P,
    rtc_set_transport: unsafe extern "C" fn(S),
    rtc_set_telemost_cookies: unsafe extern "C" fn(S),
    rtc_set_dns: unsafe extern "C" fn(S),
    rtc_set_socks_listen_host: unsafe extern "C" fn(S),
    rtc_set_vp8_options: unsafe extern "C" fn(i32, i32),
    rtc_set_sei_options: unsafe extern "C" fn(i32, i32, i32, i32) -> P,
    rtc_set_video_options: unsafe extern "C" fn(i32, i32, i32, i32, S, S, i32, i32) -> P,
    rtc_set_liveness_options: unsafe extern "C" fn(i32, i32, i32),
    rtc_start: unsafe extern "C" fn(S, S, S, S, S, i32, S, S) -> P,
    rtc_start_room: unsafe extern "C" fn(S, S, S, S, S, i32, S, S, i32) -> i64,
    rtc_stop_room: unsafe extern "C" fn(i64),
    rtc_stop_all_rooms: unsafe extern "C" fn(),
    rtc_rooms_running: unsafe extern "C" fn() -> i32,
    rtc_room_healthy: unsafe extern "C" fn(i64) -> i32,
    rtc_room_port: unsafe extern "C" fn(i64) -> i32,
    rtc_wait_ready: unsafe extern "C" fn(i32) -> P,
    rtc_stop: unsafe extern "C" fn(),
    rtc_running: unsafe extern "C" fn() -> i32,
    rtc_check: unsafe extern "C" fn(S, S, S, S, S, i32, i32, i32, i32) -> i64,
    #[allow(clippy::type_complexity)]
    rtc_ping: unsafe extern "C" fn(S, S, S, S, S, i32, i32, S, i32, i32) -> i64,
}

/// A loaded `yptuncore` shared library.
pub struct NativeYpTunCoreLib {
    f: Fns,
    path: PathBuf,
    _lib: ManuallyDrop<Library>,
}

impl NativeYpTunCoreLib {
    /// dlopen/LoadLibrary `path` and resolve every exported function.
    pub fn load(path: &Path) -> Result<Self, String> {
        // SAFETY: loading runs the Go runtime initialisers of our own c-shared build; the
        // resolved signatures mirror cores/cmd/yptuncore (the same ones YpTunCoreLib in Kotlin uses).
        unsafe {
            let lib = Library::new(path).map_err(|e| format!("{}: {e}", path.display()))?;
            let f = Fns {
                free: sym(&lib, "YpFree")?,
                poll_log: sym(&lib, "YpPollLog")?,
                sb_version: sym(&lib, "YpSbVersion")?,
                sb_start: sym(&lib, "YpSbStart")?,
                sb_stop: sym(&lib, "YpSbStop")?,
                sb_running: sym(&lib, "YpSbRunning")?,
                bind_outbound_interface: sym(&lib, "YpBindOutboundInterface")?,
                add_native_search_path: sym(&lib, "YpAddNativeSearchPath")?,
                xray_set_asset_path: sym(&lib, "YpXraySetAssetPath")?,
                xray_version: sym(&lib, "YpXrayVersion")?,
                xray_start: sym(&lib, "YpXrayStart")?,
                xray_stop: sym(&lib, "YpXrayStop")?,
                xray_running: sym(&lib, "YpXrayRunning")?,
                xray_measure_delay: sym(&lib, "YpXrayMeasureDelay")?,
                awg_version: sym(&lib, "YpAwgVersion")?,
                awg_start: sym(&lib, "YpAwgStart")?,
                awg_stop: sym(&lib, "YpAwgStop")?,
                awg_running: sym(&lib, "YpAwgRunning")?,
                awg_probe: sym(&lib, "YpAwgProbe")?,
                awg_measure_delay: sym(&lib, "YpAwgMeasureDelay")?,
                awg_generate_key_pair: sym(&lib, "YpAwgGenerateKeyPair")?,
                tg_awg_start: sym(&lib, "YpTgAwgStart")?,
                tg_awg_stop: sym(&lib, "YpTgAwgStop")?,
                tg_awg_running: sym(&lib, "YpTgAwgRunning")?,
                ft_version: sym(&lib, "YpFtVersion")?,
                ft_start: sym(&lib, "YpFtStart")?,
                ft_stop: sym(&lib, "YpFtStop")?,
                ft_running: sym(&lib, "YpFtRunning")?,
                ft_connected_streams: sym(&lib, "YpFtConnectedStreams")?,
                ft_captcha_url: sym(&lib, "YpFtCaptchaURL")?,
                ft_captcha_active: sym(&lib, "YpFtCaptchaActive")?,
                wdtt_start: sym(&lib, "YpWdttStart")?,
                wdtt_last_error: sym(&lib, "YpWdttLastError")?,
                wdtt_wait_config: sym(&lib, "YpWdttWaitConfig")?,
                wdtt_stop: sym(&lib, "YpWdttStop")?,
                wdtt_running: sym(&lib, "YpWdttRunning")?,
                wdtt_push_captcha: sym(&lib, "YpWdttPushCaptcha")?,
                wdtt_version: sym(&lib, "YpWdttVersion")?,
                master_dns_start: sym(&lib, "YpMasterDnsStart")?,
                master_dns_stop: sym(&lib, "YpMasterDnsStop")?,
                master_dns_running: sym(&lib, "YpMasterDnsRunning")?,
                master_dns_last_error: sym(&lib, "YpMasterDnsLastError")?,
                master_dns_version: sym(&lib, "YpMasterDnsVersion")?,
                rtc_version: sym(&lib, "YpRtcVersion")?,
                rtc_set_transport: sym(&lib, "YpRtcSetTransport")?,
                rtc_set_telemost_cookies: sym(&lib, "YpRtcSetTelemostCookies")?,
                rtc_set_dns: sym(&lib, "YpRtcSetDNS")?,
                rtc_set_socks_listen_host: sym(&lib, "YpRtcSetSocksListenHost")?,
                rtc_set_vp8_options: sym(&lib, "YpRtcSetVP8Options")?,
                rtc_set_sei_options: sym(&lib, "YpRtcSetSEIOptions")?,
                rtc_set_video_options: sym(&lib, "YpRtcSetVideoOptions")?,
                rtc_set_liveness_options: sym(&lib, "YpRtcSetLivenessOptions")?,
                rtc_start: sym(&lib, "YpRtcStart")?,
                rtc_start_room: sym(&lib, "YpRtcStartRoom")?,
                rtc_stop_room: sym(&lib, "YpRtcStopRoom")?,
                rtc_stop_all_rooms: sym(&lib, "YpRtcStopAllRooms")?,
                rtc_rooms_running: sym(&lib, "YpRtcRoomsRunning")?,
                rtc_room_healthy: sym(&lib, "YpRtcRoomHealthy")?,
                rtc_room_port: sym(&lib, "YpRtcRoomPort")?,
                rtc_wait_ready: sym(&lib, "YpRtcWaitReady")?,
                rtc_stop: sym(&lib, "YpRtcStop")?,
                rtc_running: sym(&lib, "YpRtcRunning")?,
                rtc_check: sym(&lib, "YpRtcCheck")?,
                rtc_ping: sym(&lib, "YpRtcPing")?,
            };
            Ok(Self { f, path: path.to_path_buf(), _lib: ManuallyDrop::new(lib) })
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Reads a Go C string and frees it (`takeString`).
    fn take(&self, p: P) -> Option<String> {
        if p.is_null() {
            return None;
        }
        // SAFETY: non-NULL results are NUL-terminated C strings allocated by Go, freed exactly once.
        unsafe {
            let s = CStr::from_ptr(p).to_string_lossy().into_owned();
            (self.f.free)(p);
            Some(s)
        }
    }
}

// SAFETY (all calls below): plain C functions of the loaded library; string arguments are
// NUL-terminated and live until the call returns.
impl YpTunCoreLib for NativeYpTunCoreLib {
    fn poll_log(&self, timeout_ms: i32) -> Option<String> {
        self.take(unsafe { (self.f.poll_log)(timeout_ms) })
    }

    fn sb_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.sb_version)() })
    }
    fn sb_start(&self, config_json: &str) -> Option<String> {
        let a = c_string(config_json);
        self.take(unsafe { (self.f.sb_start)(a.as_ptr()) })
    }
    fn sb_stop(&self) {
        unsafe { (self.f.sb_stop)() }
    }
    fn sb_running(&self) -> i32 {
        unsafe { (self.f.sb_running)() }
    }

    fn bind_outbound_interface(&self, index: i32, pin_udp: i32) {
        unsafe { (self.f.bind_outbound_interface)(index, pin_udp) }
    }
    fn add_native_search_path(&self, dir: &str) {
        let a = c_string(dir);
        unsafe { (self.f.add_native_search_path)(a.as_ptr()) }
    }

    fn xray_set_asset_path(&self, dir: &str) {
        let a = c_string(dir);
        unsafe { (self.f.xray_set_asset_path)(a.as_ptr()) }
    }
    fn xray_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.xray_version)() })
    }
    fn xray_start(&self, config_json: &str) -> Option<String> {
        let a = c_string(config_json);
        self.take(unsafe { (self.f.xray_start)(a.as_ptr()) })
    }
    fn xray_stop(&self) {
        unsafe { (self.f.xray_stop)() }
    }
    fn xray_running(&self) -> i32 {
        unsafe { (self.f.xray_running)() }
    }
    fn xray_measure_delay(&self, config_json: &str, url: &str, method: &str, timeout_ms: i32) -> i64 {
        let (a, b, c) = (c_string(config_json), c_string(url), c_string(method));
        unsafe { (self.f.xray_measure_delay)(a.as_ptr(), b.as_ptr(), c.as_ptr(), timeout_ms) }
    }

    fn awg_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.awg_version)() })
    }
    fn awg_start(&self, ini_config: &str, listen_addr: &str) -> Option<String> {
        let (a, b) = (c_string(ini_config), c_string(listen_addr));
        self.take(unsafe { (self.f.awg_start)(a.as_ptr(), b.as_ptr()) })
    }
    fn awg_stop(&self) {
        unsafe { (self.f.awg_stop)() }
    }
    fn awg_running(&self) -> i32 {
        unsafe { (self.f.awg_running)() }
    }
    fn awg_probe(&self, ini_config: &str) -> i64 {
        let a = c_string(ini_config);
        unsafe { (self.f.awg_probe)(a.as_ptr()) }
    }
    fn awg_measure_delay(&self, ini_config: &str, url: &str, method: &str, timeout_ms: i32) -> i64 {
        let (a, b, c) = (c_string(ini_config), c_string(url), c_string(method));
        unsafe { (self.f.awg_measure_delay)(a.as_ptr(), b.as_ptr(), c.as_ptr(), timeout_ms) }
    }
    fn awg_generate_key_pair(&self) -> Option<String> {
        self.take(unsafe { (self.f.awg_generate_key_pair)() })
    }

    fn tg_awg_start(&self, ini_config: &str, listen_addr: &str, user: &str, pass: &str) -> Option<String> {
        let (a, b, c, d) = (c_string(ini_config), c_string(listen_addr), c_string(user), c_string(pass));
        self.take(unsafe { (self.f.tg_awg_start)(a.as_ptr(), b.as_ptr(), c.as_ptr(), d.as_ptr()) })
    }
    fn tg_awg_stop(&self) {
        unsafe { (self.f.tg_awg_stop)() }
    }
    fn tg_awg_running(&self) -> i32 {
        unsafe { (self.f.tg_awg_running)() }
    }

    fn ft_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.ft_version)() })
    }
    fn ft_start(&self, uri: &str, listen_addr: &str, vk_link: &str, n_streams: i32) -> Option<String> {
        let (a, b, c) = (c_string(uri), c_string(listen_addr), c_string(vk_link));
        self.take(unsafe { (self.f.ft_start)(a.as_ptr(), b.as_ptr(), c.as_ptr(), n_streams) })
    }
    fn ft_stop(&self) {
        unsafe { (self.f.ft_stop)() }
    }
    fn ft_running(&self) -> i32 {
        unsafe { (self.f.ft_running)() }
    }
    fn ft_connected_streams(&self) -> i32 {
        unsafe { (self.f.ft_connected_streams)() }
    }
    fn ft_captcha_url(&self) -> Option<String> {
        self.take(unsafe { (self.f.ft_captcha_url)() })
    }
    fn ft_captcha_active(&self) -> i32 {
        unsafe { (self.f.ft_captcha_active)() }
    }

    fn wdtt_start(&self, options_json: &str) -> Option<String> {
        let a = c_string(options_json);
        self.take(unsafe { (self.f.wdtt_start)(a.as_ptr()) })
    }
    fn wdtt_last_error(&self) -> Option<String> {
        self.take(unsafe { (self.f.wdtt_last_error)() })
    }
    fn wdtt_wait_config(&self, timeout_ms: i32) -> Option<String> {
        self.take(unsafe { (self.f.wdtt_wait_config)(timeout_ms) })
    }
    fn wdtt_stop(&self) {
        unsafe { (self.f.wdtt_stop)() }
    }
    fn wdtt_running(&self) -> i32 {
        unsafe { (self.f.wdtt_running)() }
    }
    fn wdtt_push_captcha(&self, token: &str) {
        let a = c_string(token);
        unsafe { (self.f.wdtt_push_captcha)(a.as_ptr()) }
    }
    fn wdtt_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.wdtt_version)() })
    }

    #[allow(clippy::too_many_arguments)]
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
    ) -> Option<String> {
        let (a, b, c) = (c_string(work_dir), c_string(domains), c_string(encryption_key));
        let (d, e, u, p) = (c_string(resolvers), c_string(listen_addr), c_string(socks_user), c_string(socks_pass));
        self.take(unsafe {
            (self.f.master_dns_start)(
                a.as_ptr(),
                b.as_ptr(),
                c.as_ptr(),
                encryption_method,
                d.as_ptr(),
                e.as_ptr(),
                u.as_ptr(),
                p.as_ptr(),
                balancing_strategy,
                packet_duplication,
                upload_compression,
                download_compression,
            )
        })
    }
    fn master_dns_stop(&self) {
        unsafe { (self.f.master_dns_stop)() }
    }
    fn master_dns_running(&self) -> i32 {
        unsafe { (self.f.master_dns_running)() }
    }
    fn master_dns_last_error(&self) -> Option<String> {
        self.take(unsafe { (self.f.master_dns_last_error)() })
    }
    fn master_dns_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.master_dns_version)() })
    }

    fn rtc_version(&self) -> Option<String> {
        self.take(unsafe { (self.f.rtc_version)() })
    }
    fn rtc_set_transport(&self, transport: &str) {
        let a = c_string(transport);
        unsafe { (self.f.rtc_set_transport)(a.as_ptr()) }
    }
    fn rtc_set_telemost_cookies(&self, cookies: &str) {
        let a = c_string(cookies);
        unsafe { (self.f.rtc_set_telemost_cookies)(a.as_ptr()) }
    }
    fn rtc_set_dns(&self, dns_server: &str) {
        let a = c_string(dns_server);
        unsafe { (self.f.rtc_set_dns)(a.as_ptr()) }
    }
    fn rtc_set_socks_listen_host(&self, host: &str) {
        let a = c_string(host);
        unsafe { (self.f.rtc_set_socks_listen_host)(a.as_ptr()) }
    }
    fn rtc_set_vp8_options(&self, fps: i32, batch_size: i32) {
        unsafe { (self.f.rtc_set_vp8_options)(fps, batch_size) }
    }
    fn rtc_set_sei_options(&self, fps: i32, batch_size: i32, fragment_size: i32, ack_timeout_ms: i32) -> Option<String> {
        self.take(unsafe { (self.f.rtc_set_sei_options)(fps, batch_size, fragment_size, ack_timeout_ms) })
    }
    #[allow(clippy::too_many_arguments)]
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
    ) -> Option<String> {
        let (a, b) = (c_string(qr_recovery), c_string(codec));
        self.take(unsafe {
            (self.f.rtc_set_video_options)(width, height, fps, qr_size, a.as_ptr(), b.as_ptr(), tile_module, tile_rs)
        })
    }
    fn rtc_set_liveness_options(&self, interval_ms: i32, timeout_ms: i32, failures: i32) {
        unsafe { (self.f.rtc_set_liveness_options)(interval_ms, timeout_ms, failures) }
    }
    #[allow(clippy::too_many_arguments)]
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
    ) -> Option<String> {
        let (a, b, c, d, e) = (c_string(carrier), c_string(transport), c_string(room_id), c_string(client_id), c_string(key_hex));
        let (u, p) = (c_string(socks_user), c_string(socks_pass));
        self.take(unsafe {
            (self.f.rtc_start)(a.as_ptr(), b.as_ptr(), c.as_ptr(), d.as_ptr(), e.as_ptr(), socks_port, u.as_ptr(), p.as_ptr())
        })
    }
    #[allow(clippy::too_many_arguments)]
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
    ) -> i64 {
        let (a, b, c, d, e) = (c_string(carrier), c_string(transport), c_string(room_id), c_string(client_id), c_string(key_hex));
        let (u, p) = (c_string(socks_user), c_string(socks_pass));
        unsafe {
            (self.f.rtc_start_room)(
                a.as_ptr(),
                b.as_ptr(),
                c.as_ptr(),
                d.as_ptr(),
                e.as_ptr(),
                socks_port,
                u.as_ptr(),
                p.as_ptr(),
                ready_timeout_ms,
            )
        }
    }
    fn rtc_stop_room(&self, handle: i64) {
        unsafe { (self.f.rtc_stop_room)(handle) }
    }
    fn rtc_stop_all_rooms(&self) {
        unsafe { (self.f.rtc_stop_all_rooms)() }
    }
    fn rtc_rooms_running(&self) -> i32 {
        unsafe { (self.f.rtc_rooms_running)() }
    }
    fn rtc_room_healthy(&self, handle: i64) -> i32 {
        unsafe { (self.f.rtc_room_healthy)(handle) }
    }
    fn rtc_room_port(&self, handle: i64) -> i32 {
        unsafe { (self.f.rtc_room_port)(handle) }
    }
    fn rtc_wait_ready(&self, timeout_ms: i32) -> Option<String> {
        self.take(unsafe { (self.f.rtc_wait_ready)(timeout_ms) })
    }
    fn rtc_stop(&self) {
        unsafe { (self.f.rtc_stop)() }
    }
    fn rtc_running(&self) -> i32 {
        unsafe { (self.f.rtc_running)() }
    }
    #[allow(clippy::too_many_arguments)]
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
    ) -> i64 {
        let (a, b, c, d, e) = (c_string(carrier), c_string(transport), c_string(room_id), c_string(client_id), c_string(key_hex));
        unsafe {
            (self.f.rtc_check)(a.as_ptr(), b.as_ptr(), c.as_ptr(), d.as_ptr(), e.as_ptr(), socks_port, timeout_ms, vp8_fps, vp8_batch)
        }
    }
    #[allow(clippy::too_many_arguments)]
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
    ) -> i64 {
        let (a, b, c, d, e) = (c_string(carrier), c_string(transport), c_string(room_id), c_string(client_id), c_string(key_hex));
        let u = c_string(ping_url);
        unsafe {
            (self.f.rtc_ping)(
                a.as_ptr(),
                b.as_ptr(),
                c.as_ptr(),
                d.as_ptr(),
                e.as_ptr(),
                socks_port,
                timeout_ms,
                u.as_ptr(),
                vp8_fps,
                vp8_batch,
            )
        }
    }
}
