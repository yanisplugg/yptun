//! Runtime half of DesktopVpnManager (port): the TUN / system-proxy start steps, the olcRTC process
//! launch with binary fallback and its output reader, stopDesktopMode, the auto-reconnect watchdog,
//! speed sampling, the uptime stamp, the in-app journal buffer and the exit-IP checks.
//!
//! Everything that touches the OS (controllers, processes, sockets of the cores) stays behind small
//! host traits; the order of steps, the logs and the decisions are the Kotlin ones.

use std::collections::VecDeque;
use std::io::{self, BufRead, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::desktop_vpn_manager::{
    is_fatal_olcrtc_startup_line, is_public_ipv4, DesktopMode, HEV_LOG_PREFIX, IP_ECHO_URLS, MAX_LOG_ENTRIES,
    TUNNEL_HEALTH_HOSTS, TUNNEL_PROBE_FAIL_LIMIT, WATCHDOG_GRACE_MS, WATCHDOG_INTERVAL_MS,
};
use crate::desktop_vpn_start::{DesktopStartError, DesktopVpnSession, DesktopVpnStatus, TunStartRequest, START_SUPERSEDED};
use crate::http_proxy_bridge::http_port_for;
use crate::model::EngineType;

pub const PROXY_UNTOUCHED_LOG: &str =
    "Proxy mode: system proxy left untouched \u{2014} point apps at the addresses above by hand";
pub const LINUX_TUN_COMBINED_LOG: &str =
    "Linux TUN mode starts olcRTC and hev-socks5-tunnel together under one elevated authorization";
pub const OLCRTC_NO_PROXY: &str = "127.0.0.1,localhost";
pub const OLCRTC_READY_MARKER: &str = "socks5 server listening";
pub const OLCRTC_ALL_FAILED: &str = "olcRTC binary failed to start";
pub const WATCHDOG_DEAD_LOG: &str = "Watchdog: tunnel stopped unexpectedly \u{2014} reconnecting\u{2026}";
pub const WATCHDOG_NETWORK_LOG: &str = "Watchdog: network changed \u{2014} reconnecting\u{2026}";
pub const WATCHDOG_NO_TRAFFIC_LOG: &str = "Watchdog: no traffic through the tunnel \u{2014} reconnecting\u{2026}";
pub const IPIFY_PROXY_REQUEST: &str =
    "GET http://api.ipify.org/ HTTP/1.1\r\nHost: api.ipify.org\r\nUser-Agent: YPtun\r\nConnection: close\r\n\r\n";
pub const EXIT_IP_CONNECT_TIMEOUT_MS: u64 = 5_000;
pub const EXIT_IP_READ_TIMEOUT_MS: u64 = 15_000;
pub const EXIT_IP_MAX_LENGTH: usize = 45;

/// `requestGeneration != generation` -> CancellationException("Desktop start superseded").
pub fn check_generation(current: u64, requested: u64) -> Result<(), DesktopStartError> {
    if requested != current {
        Err(DesktopStartError::Cancelled(String::from(START_SUPERSEDED)))
    } else {
        Ok(())
    }
}

// ---------------------------------------------------------------------------------------------
// startLinuxTun / startWindowsTun / startSystemProxy
// ---------------------------------------------------------------------------------------------

pub trait LinuxTunSteps {
    fn generation(&self) -> u64;
    /// The olcRTC subprocess is running (its wrapper script already started hev).
    fn has_olcrtc_process(&self) -> bool;
    /// linuxTunController.start(...) + keep the tun process + start its stderr reader.
    fn start_hev(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError>;
    /// linuxTunController.awaitReady().
    fn await_hev_ready(&mut self) -> Result<(), DesktopStartError>;
}

pub fn start_linux_tun(host: &mut dyn LinuxTunSteps, request: &TunStartRequest) -> Result<(), DesktopStartError> {
    if host.has_olcrtc_process() {
        host.await_hev_ready()?;
    } else {
        host.start_hev(request)?;
    }
    check_generation(host.generation(), request.request_generation)
}

pub trait WindowsTunSteps {
    fn generation(&self) -> u64;
    /// windowsTunController.start(tun2socks, port, bypass, user, pass) + keep the tun process.
    fn start_tun2socks(&mut self, request: &TunStartRequest) -> Result<(), DesktopStartError>;
    fn start_tun_log_reader(&mut self);
}

pub fn start_windows_tun(host: &mut dyn WindowsTunSteps, request: &TunStartRequest) -> Result<(), DesktopStartError> {
    host.start_tun2socks(request)?;
    check_generation(host.generation(), request.request_generation)?;
    host.start_tun_log_reader();
    Ok(())
}

pub trait SystemProxySteps {
    fn generation(&self) -> u64;
    fn log(&mut self, message: String);
    /// Stops the previous HTTP bridge, starts a new one on `bridge_port` in front of the SOCKS
    /// (with its credentials). False when the port is busy (the host forgets the bridge then).
    fn start_http_bridge(&mut self, socks: &DesktopSocksProxySettings, bridge_port: i32) -> bool;
    /// setSystemProxyProvider(): false = "do not take over the system proxy".
    fn take_over_system_proxy(&self) -> bool;
    fn start_pac_server(&mut self, socks: &DesktopSocksProxySettings);
    fn pac_url(&self) -> String;
    fn enable_system_proxy(&mut self, http_proxy_host_port: &str, pac_url: &str) -> Result<(), DesktopStartError>;
    /// scope.launch { reportProxyExitIp(...) } -- see [report_proxy_exit_ip].
    fn report_exit_ip_in_background(&mut self, socks: &DesktopSocksProxySettings, bridge_port: i32);
}

pub fn proxy_mode_log(socks: &DesktopSocksProxySettings, bridge_port: i32) -> String {
    format!(
        "Proxy mode: SOCKS5 {}:{} \u{00b7} HTTP {}:{}",
        socks.host, socks.port, socks.host, bridge_port
    )
}

pub fn start_system_proxy(
    host: &mut dyn SystemProxySteps,
    socks: &DesktopSocksProxySettings,
    request_generation: u64,
    os: DesktopOs,
) -> Result<(), DesktopStartError> {
    let bridge_port = http_port_for(socks.port);
    if !host.start_http_bridge(socks, bridge_port) {
        return Err(DesktopStartError::Failed(format!("HTTP proxy port {} is already in use", bridge_port)));
    }
    host.log(proxy_mode_log(socks, bridge_port));

    if !host.take_over_system_proxy() {
        host.log(String::from(PROXY_UNTOUCHED_LOG));
    } else {
        // PAC is only used by macOS; Windows gets the fixed HTTP proxy.
        if matches!(os, DesktopOs::MacOs) {
            host.start_pac_server(socks);
        }
        let pac_url = host.pac_url();
        let http = format!("{}:{}", socks.host, bridge_port);
        host.enable_system_proxy(&http, &pac_url)?;
    }

    check_generation(host.generation(), request_generation)?;
    host.report_exit_ip_in_background(socks, bridge_port);
    Ok(())
}

// ---------------------------------------------------------------------------------------------
// reportProxyExitIp / checkExitIp
// ---------------------------------------------------------------------------------------------

/// `response.substringAfter("\r\n\r\n", "").trim()`.
pub fn http_response_body(response: &str) -> String {
    match response.find("\r\n\r\n") {
        Some(i) => response[i + 4..].trim().to_string(),
        None => String::new(),
    }
}

fn us_ascii_decode(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| if b < 0x80 { b as char } else { '\u{FFFD}' }).collect()
}

/// One real request through the HTTP bridge; Ok(body) or Err(reason).
pub fn fetch_exit_ip_via_http_proxy(host: &str, bridge_port: i32) -> Result<String, String> {
    let port = u16::try_from(bridge_port).map_err(|_| format!("invalid port {}", bridge_port))?;
    let addr = (host, port)
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or_else(|| format!("cannot resolve {}", host))?;
    let mut socket = TcpStream::connect_timeout(&addr, Duration::from_millis(EXIT_IP_CONNECT_TIMEOUT_MS))
        .map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_millis(EXIT_IP_READ_TIMEOUT_MS)))
        .map_err(|e| e.to_string())?;
    socket.write_all(IPIFY_PROXY_REQUEST.as_bytes()).map_err(|e| e.to_string())?;
    socket.flush().map_err(|e| e.to_string())?;
    let mut raw = Vec::new();
    socket.read_to_end(&mut raw).map_err(|e| e.to_string())?;
    Ok(http_response_body(&us_ascii_decode(&raw)))
}

pub fn proxy_self_check_log(result: &Result<String, String>) -> String {
    match result {
        Ok(ip) if !ip.trim().is_empty() && ip.chars().count() <= EXIT_IP_MAX_LENGTH => {
            format!("Proxy self-check: traffic OK \u{2014} exit IP {}", ip)
        }
        Ok(_) => String::from("Proxy self-check: NO traffic through the HTTP proxy (empty response)"),
        Err(e) => format!("Proxy self-check: NO traffic through the HTTP proxy ({})", e),
    }
}

pub fn report_proxy_exit_ip(host: &str, bridge_port: i32, log: &mut dyn FnMut(String)) {
    let result = fetch_exit_ip_via_http_proxy(host, bridge_port);
    log(proxy_self_check_log(&result));
}

/// checkExitIp: only proxy mode needs an explicit SOCKS dial; TUN/disconnected use the default route.
pub fn exit_ip_needs_socks(is_connected: bool, proxy_mode: bool) -> bool {
    is_connected && proxy_mode
}

/// First IP_ECHO_URLS answer that is a public IPv4 (the host fetches with 8 s timeouts).
pub fn first_public_exit_ip(fetch: &mut dyn FnMut(&str) -> Option<String>) -> Option<String> {
    IP_ECHO_URLS
        .iter()
        .find_map(|url| fetch(url).map(|s| s.trim().to_string()).filter(|s| is_public_ipv4(s)))
}

// ---------------------------------------------------------------------------------------------
// startOlcRtcProcess(WithFallback)
// ---------------------------------------------------------------------------------------------

pub fn olcrtc_start_log(provider: &str, transport: &str, room: &str, port: i32) -> String {
    format!(
        "Starting olcRTC provider={}, transport={}, room={}, port={}",
        provider, transport, room, port
    )
}

/// Extra environment for the olcRTC process.
pub fn olcrtc_environment() -> [(&'static str, &'static str); 2] {
    [("NO_PROXY", OLCRTC_NO_PROXY), ("no_proxy", OLCRTC_NO_PROXY)]
}

fn file_name_of(path: &Path) -> String {
    path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// Tries every candidate binary in order; logs and moves on unless it was the last one.
pub fn start_olcrtc_with_fallback<P>(
    binaries: &[PathBuf],
    log: &mut dyn FnMut(String),
    mut start: impl FnMut(&Path) -> Result<P, String>,
) -> Result<P, String> {
    let mut last_error: Option<String> = None;
    for (index, binary) in binaries.iter().enumerate() {
        match start(binary) {
            Ok(process) => return Ok(process),
            Err(e) => {
                if index + 1 == binaries.len() {
                    last_error = Some(e);
                    break;
                }
                log(format!(
                    "olcRTC start failed for {}: {}. Retrying with fallback binary.",
                    file_name_of(binary),
                    e
                ));
                last_error = Some(e);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| String::from(OLCRTC_ALL_FAILED)))
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct OlcRtcLineOutcome {
    pub log: Option<String>,
    pub ready: bool,
    pub fatal: bool,
}

/// One line of the olcRTC pipe. hev's tagged lines are only logged (none of olcRTC's checks apply).
pub fn classify_olcrtc_output_line(line: &str, log_output: bool) -> OlcRtcLineOutcome {
    if let Some(rest) = line.strip_prefix(HEV_LOG_PREFIX) {
        return OlcRtcLineOutcome {
            log: if log_output { Some(format!("tun: {}", rest)) } else { None },
            ready: false,
            fatal: false,
        };
    }
    OlcRtcLineOutcome {
        log: if log_output { Some(format!("rtc: {}", line)) } else { None },
        ready: line.to_lowercase().contains(OLCRTC_READY_MARKER),
        fatal: is_fatal_olcrtc_startup_line(line),
    }
}

pub trait OlcRtcOutputSink {
    fn log(&mut self, message: String);
    fn ready(&mut self);
    fn startup_failure(&mut self, line: String);
}

/// The reader loop: lossy UTF-8 lines (\n or \r\n), skipped once the job is no longer active.
/// EOF (the process closed its pipe) ends the loop normally; read errors are returned.
pub fn pump_olcrtc_output<R: BufRead>(
    mut reader: R,
    log_output: bool,
    is_active: &dyn Fn() -> bool,
    sink: &mut dyn OlcRtcOutputSink,
) -> io::Result<()> {
    let mut buf = Vec::new();
    loop {
        buf.clear();
        if reader.read_until(b'\n', &mut buf)? == 0 {
            return Ok(());
        }
        if buf.last() == Some(&b'\n') {
            buf.pop();
            if buf.last() == Some(&b'\r') {
                buf.pop();
            }
        }
        if !is_active() {
            continue;
        }
        let line = String::from_utf8_lossy(&buf).into_owned();
        let outcome = classify_olcrtc_output_line(&line, log_output);
        if let Some(message) = outcome.log {
            sink.log(message);
        }
        if outcome.ready {
            sink.ready();
        }
        if outcome.fatal {
            sink.startup_failure(line);
        }
    }
}

// ---------------------------------------------------------------------------------------------
// stopDesktopMode
// ---------------------------------------------------------------------------------------------

pub trait DesktopStopHost {
    /// Status is Disconnected and neither the olcRTC process nor a tun process exists.
    fn is_idle(&self) -> bool;
    fn set_status(&mut self, status: DesktopVpnStatus);
    fn log(&mut self, message: String);
    fn release_linux_tun(&mut self);
    fn stop_windows_tun(&mut self) -> Result<(), String>;
    /// Bounded by the host (run_bounded).
    fn restore_system_proxy(&mut self) -> Result<(), String>;
    fn stop_http_proxy_bridge(&mut self);
    fn stop_pac_server(&mut self);
    /// Bounded by the host (run_bounded).
    fn stop_engines(&mut self) -> Result<(), String>;
    fn unbind_outbound_interface(&mut self);
    fn stop_olcrtc_process(&mut self, privileged: bool);
    fn delete_olcrtc_config(&mut self);
    /// Cancels the olcRTC log reader, the tun log reader and the watchdog.
    fn cancel_jobs(&mut self);
}

pub fn stopped_log(os: DesktopOs) -> &'static str {
    match os {
        DesktopOs::Linux => "Desktop Linux TUN stopped",
        DesktopOs::Windows => "Desktop Windows TUN stopped",
        _ => "Desktop proxy stopped",
    }
}

pub fn stop_desktop_mode(host: &mut dyn DesktopStopHost, session: &mut DesktopVpnSession, os: DesktopOs, final_status: bool) {
    if host.is_idle() {
        return;
    }
    host.set_status(DesktopVpnStatus::Stopping);

    let mode = session.active_mode.unwrap_or_else(|| DesktopMode::current(os));
    match mode {
        DesktopMode::LinuxTun => host.release_linux_tun(),
        DesktopMode::WindowsTun => {
            if let Err(e) = host.stop_windows_tun() {
                host.log(format!("Windows TUN stop failed: {}", e));
            }
        }
        DesktopMode::SystemProxy => {
            if let Err(e) = host.restore_system_proxy() {
                host.log(format!("Proxy restore failed: {}", e));
            }
            host.stop_http_proxy_bridge();
        }
    }

    host.stop_pac_server();
    if let Err(e) = host.stop_engines() {
        host.log(format!("Engine stop failed: {}", e));
    }
    host.unbind_outbound_interface();
    session.engine_location = None;
    session.session_socks = None;

    host.stop_olcrtc_process(mode == DesktopMode::LinuxTun);
    host.delete_olcrtc_config();
    host.cancel_jobs();

    if final_status {
        host.set_status(DesktopVpnStatus::Disconnected);
        host.log(String::from(stopped_log(os)));
    }
}

// ---------------------------------------------------------------------------------------------
// setStatus / startSpeedUpdater
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SpeedSample {
    pub down_bytes_per_sec: i64,
    pub up_bytes_per_sec: i64,
}

pub const ZERO_SPEED: SpeedSample = SpeedSample { down_bytes_per_sec: 0, up_bytes_per_sec: 0 };

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrafficCounters {
    pub rx_bytes: i64,
    pub tx_bytes: i64,
}

/// Counters reset when the adapter is recreated; a negative delta is floored at zero.
pub fn speed_sample(previous: TrafficCounters, current: TrafficCounters, interval_ms: u64) -> SpeedSample {
    let seconds = interval_ms as f64 / 1000.0;
    let down = ((current.rx_bytes - previous.rx_bytes).max(0) as f64 / seconds) as i64;
    let up = ((current.tx_bytes - previous.tx_bytes).max(0) as f64 / seconds) as i64;
    SpeedSample { down_bytes_per_sec: down, up_bytes_per_sec: up }
}

/// One iteration of the speed loop. Some(value) = publish it.
#[derive(Debug, Clone, Default)]
pub struct SpeedMeter {
    previous: Option<TrafficCounters>,
}

impl SpeedMeter {
    pub fn new() -> Self {
        SpeedMeter { previous: None }
    }

    pub fn tick(&mut self, sampling_enabled: bool, current: Option<TrafficCounters>, interval_ms: u64) -> Option<SpeedSample> {
        if !sampling_enabled {
            self.previous = None;
            return Some(ZERO_SPEED);
        }
        let sample = match (self.previous, current) {
            (Some(last), Some(now)) => Some(speed_sample(last, now, interval_ms)),
            _ => None,
        };
        self.previous = current;
        sample
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StatusEffects {
    pub connected: bool,
    pub start_speed_updater: bool,
    pub stop_speed_updater: bool,
    pub connected_since_ms: u64,
}

/// What setStatus does besides storing the status.
pub fn status_effects(status: &DesktopVpnStatus, speed_running: bool, connected_since_ms: u64, now_ms: u64) -> StatusEffects {
    let connected = matches!(status, DesktopVpnStatus::Connected);
    let since = if connected && connected_since_ms == 0 {
        now_ms
    } else if connected {
        connected_since_ms
    } else {
        0
    };
    StatusEffects {
        connected,
        start_speed_updater: connected && !speed_running,
        stop_speed_updater: !connected,
        connected_since_ms: since,
    }
}

// ---------------------------------------------------------------------------------------------
// startWatchdog / tunnelAlive / isActiveEngineAlive
// ---------------------------------------------------------------------------------------------

pub fn tunnel_alive_with(probe: &mut dyn FnMut(&str) -> bool) -> bool {
    TUNNEL_HEALTH_HOSTS.iter().any(|host| probe(host))
}

pub fn is_active_engine_alive(engine: Option<EngineType>, core_running: &dyn Fn(EngineType) -> bool, process_alive: bool) -> bool {
    match engine {
        Some(e) => core_running(e),
        None => process_alive,
    }
}

pub trait WatchdogProbe {
    fn is_connected(&self) -> bool;
    /// runCatching { isActiveEngineAlive() }.getOrDefault(true)
    fn engine_alive(&mut self) -> bool;
    /// PhysicalInterface.fingerprint(); empty = offline.
    fn network_fingerprint(&mut self) -> String;
    fn tunnel_alive(&mut self) -> bool;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WatchdogAction {
    Continue,
    Reconnect(&'static str),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchdogState {
    net_base: String,
    net_down: bool,
    net_pending: Option<String>,
    tick: u32,
    probe_fails: u32,
}

impl WatchdogState {
    pub fn new(net_base: String) -> Self {
        WatchdogState { net_base, net_down: false, net_pending: None, tick: 0, probe_fails: 0 }
    }

    /// One poll after WATCHDOG_INTERVAL_MS (the caller has checked the generation).
    pub fn poll<P: WatchdogProbe + ?Sized>(&mut self, probe: &mut P) -> WatchdogAction {
        if !probe.is_connected() {
            return WatchdogAction::Continue;
        }
        if !probe.engine_alive() {
            return WatchdogAction::Reconnect(WATCHDOG_DEAD_LOG);
        }
        let net = probe.network_fingerprint();
        if net.is_empty() {
            self.net_down = true;
            self.net_pending = None;
        } else if net == self.net_base && !self.net_down {
            self.net_pending = None;
        } else if self.net_pending.as_deref() != Some(net.as_str()) {
            self.net_pending = Some(net.clone());
        } else {
            self.net_base = net;
            return WatchdogAction::Reconnect(WATCHDOG_NETWORK_LOG);
        }
        if net.is_empty() {
            self.probe_fails = 0;
            return WatchdogAction::Continue;
        }
        self.tick = self.tick.wrapping_add(1);
        if self.tick % 2 == 0 {
            if probe.tunnel_alive() {
                self.probe_fails = 0;
            } else {
                self.probe_fails += 1;
                if self.probe_fails >= TUNNEL_PROBE_FAIL_LIMIT {
                    return WatchdogAction::Reconnect(WATCHDOG_NO_TRAFFIC_LOG);
                }
            }
        }
        WatchdogAction::Continue
    }
}

pub trait WatchdogHost: WatchdogProbe {
    fn generation(&self) -> u64;
    /// delay(ms); return false when the job was cancelled.
    fn sleep_ms(&mut self, ms: u64) -> bool;
    fn log(&mut self, message: String);
    /// beginStart(auto = true): bumps the generation, a fresh watchdog follows the reconnect.
    fn begin_auto_start(&mut self);
}

pub fn run_watchdog<H: WatchdogHost>(host: &mut H, generation_at_connect: u64) {
    if !host.sleep_ms(WATCHDOG_GRACE_MS) {
        return;
    }
    let base = host.network_fingerprint();
    let mut state = WatchdogState::new(base);
    while generation_at_connect == host.generation() {
        if !host.sleep_ms(WATCHDOG_INTERVAL_MS) {
            return;
        }
        if generation_at_connect != host.generation() {
            return;
        }
        match state.poll(host) {
            WatchdogAction::Continue => {}
            WatchdogAction::Reconnect(message) => {
                // Stopped while probing: never fight an intentional disconnect.
                if message == WATCHDOG_NO_TRAFFIC_LOG && generation_at_connect != host.generation() {
                    return;
                }
                host.log(String::from(message));
                host.begin_auto_start();
                return;
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// addLog / scheduleLogFlush
// ---------------------------------------------------------------------------------------------

/// Bounded in-app journal: O(1) append, snapshot published at most once per flush interval.
pub struct LogJournal {
    buffer: Mutex<VecDeque<String>>,
    flush_pending: AtomicBool,
    max_entries: usize,
}

impl Default for LogJournal {
    fn default() -> Self {
        Self::new()
    }
}

impl LogJournal {
    pub fn new() -> Self {
        Self::with_max_entries(MAX_LOG_ENTRIES)
    }

    pub fn with_max_entries(max_entries: usize) -> Self {
        LogJournal {
            buffer: Mutex::new(VecDeque::new()),
            flush_pending: AtomicBool::new(false),
            max_entries: max_entries.max(1),
        }
    }

    /// Stores the line (trailing whitespace trimmed: the Go cores hand over their "\n").
    /// Returns the stored line (for the log file) and whether the caller must schedule a flush.
    pub fn add(&self, raw: &str) -> (String, bool) {
        let message = raw.trim_end().to_string();
        {
            let mut buffer = self.buffer.lock().unwrap_or_else(|e| e.into_inner());
            if buffer.len() >= self.max_entries {
                buffer.pop_front();
            }
            buffer.push_back(message.clone());
        }
        let schedule = self
            .flush_pending
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok();
        (message, schedule)
    }

    /// The flush itself: clears the pending flag BEFORE copying, then returns the snapshot.
    pub fn flush(&self) -> Vec<String> {
        self.flush_pending.store(false, Ordering::SeqCst);
        self.snapshot()
    }

    pub fn snapshot(&self) -> Vec<String> {
        self.buffer.lock().unwrap_or_else(|e| e.into_inner()).iter().cloned().collect()
    }

    pub fn clear(&self) {
        self.buffer.lock().unwrap_or_else(|e| e.into_inner()).clear();
    }
}
