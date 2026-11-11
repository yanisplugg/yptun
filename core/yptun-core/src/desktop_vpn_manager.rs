//! Desktop VPN manager, part 1: core start/stop helpers.
//!
//! Port of the pure pieces of DesktopVpnManager.kt (jvmMain/vpn): companion constants, desktop
//! mode selection, auto-reconnect backoff, free TUN port block, Linux TUN launch script,
//! privileged stop script, olcRTC readiness wait, bounded stop steps, public IPv4 check.
//! Process spawning and the connect/disconnect state machine stay in the host for now.

use std::fmt;
use std::fs;
use std::io;
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::{DesktopSocksProxySettings, MAX_PORT};
use crate::pac::LOCAL_SOCKS_HOST;

pub const MAX_LOG_ENTRIES: usize = 5_000;

/// How long a connect waits for an answer to the "another VPN is running" prompt.
pub const CONFLICT_DECISION_TIMEOUT_MS: u64 = 60_000;

/// How long a burst of log lines is coalesced before the UI sees them.
pub const LOG_FLUSH_INTERVAL_MS: u64 = 250;

/// Sampling cadence of the Home speed line (matches Android's SPEED_INTERVAL_MS).
pub const SPEED_INTERVAL_MS: u64 = 2_000;
pub const WATCHDOG_GRACE_MS: u64 = 8_000;
pub const WATCHDOG_INTERVAL_MS: u64 = 5_000;
pub const TUNNEL_HEALTH_HOSTS: [&str; 3] = ["1.1.1.1", "8.8.8.8", "9.9.9.9"];
pub const TUNNEL_HEALTH_TIMEOUT_MS: u64 = 5_000;
pub const TUNNEL_PROBE_FAIL_LIMIT: u32 = 3;
pub const AUTO_RECONNECT_BASE_MS: u64 = 2_000;
pub const AUTO_RECONNECT_MAX_MS: u64 = 30_000;
pub const OLC_READY_TIMEOUT_MS: u64 = 25_000;
pub const OLC_STARTUP_STABILITY_MS: u64 = 1_500;
pub const READY_POLL_INTERVAL_MS: u64 = 200;
pub const TCP_CONNECT_TIMEOUT_MS: u64 = 250;
pub const STOP_STEP_TIMEOUT_MS: u64 = 10_000;

/// The cores derive helper ports up to +6 from the SOCKS port (DesktopEngineController).
pub const PORT_BLOCK: i32 = 8;
pub const TUN_FALLBACK_PORT: i32 = 27960;
pub const PROCESS_STOP_TIMEOUT_MS: u64 = 3_000;
pub const PROCESS_KILL_TIMEOUT_MS: u64 = 1_000;

/// Tags hev-socks5-tunnel's lines on the combined Linux TUN pipe.
pub const HEV_LOG_PREFIX: &str = "[hev] ";
pub const DEFAULT_LOCATION_PING_PARALLELISM: usize = 4;

/// VK / Mail.ru autonomous systems, whose prefixes carry the TURN relays VK-TURN rides.
pub const VK_TURN_ASNS: [&str; 2] = ["47541", "47764"];

/// VK/OK control plane the freeturn provider keeps using for anonymous call tokens.
pub const VK_TURN_CONTROL_HOSTS: [&str; 6] = [
    "login.vk.ru",
    "api.vk.ru",
    "id.vk.ru",
    "vk.ru",
    "calls.okcdn.ru",
    "ok.ru",
];

/// Yandex (Docs balancers live there): the OpenFlux Yandex Docs carrier.
pub const OPENFLUX_YANDEX_ASNS: [&str; 1] = ["13238"];

/// MAX control plane the OpenFlux MAX carrier talks to.
pub const OPENFLUX_MAX_HOSTS: [&str; 3] = ["web.max.ru", "ws-api.oneme.ru", "api.oneme.ru"];

/// Ceiling on the VK/OK carve-out so a surprising ASN answer can't install thousands of routes.
pub const MAX_VK_TURN_BYPASS_PREFIXES: usize = 400;

// Ping probes (same values as AndroidVpnManager).
pub const TCP_PING_ATTEMPTS: u32 = 1;
pub const TUNNEL_PROBE_HOST: &str = "1.1.1.1";
pub const TUNNEL_PROBE_PORT: u16 = 443;
pub const TUNNEL_PING_TIMEOUT_MS: u64 = 6_000;

/// Plain-text IP echo services asked by checkExitIp, in order.
pub const IP_ECHO_URLS: [&str; 4] = [
    "https://api.ipify.org",
    "https://ipv4.icanhazip.com",
    "https://ifconfig.me/ip",
    "https://ipinfo.io/ip",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopMode {
    LinuxTun,
    WindowsTun,
    SystemProxy,
}

impl DesktopMode {
    pub fn current(os: DesktopOs) -> Self {
        match os {
            DesktopOs::Linux => DesktopMode::LinuxTun,
            DesktopOs::Windows => DesktopMode::WindowsTun,
            DesktopOs::MacOs | DesktopOs::Other => DesktopMode::SystemProxy,
        }
    }
}

/// Delay before auto-reconnect attempt number `attempt` (0-based): base shl min(attempt, 4), capped.
pub fn auto_reconnect_delay_ms(attempt: u32) -> u64 {
    (AUTO_RECONNECT_BASE_MS << attempt.min(4)).min(AUTO_RECONNECT_MAX_MS)
}

pub fn auto_reconnect_log(reason: Option<&str>, delay_ms: u64) -> String {
    format!(
        "Reconnect failed ({}); retrying in {}s",
        reason.unwrap_or("unknown"),
        delay_ms / 1_000
    )
}

/// TUN mode's SOCKS listener is internal to the TUN bridge, so a port another program holds must
/// not fail the connect: move the whole block the cores derive their helper ports from.
/// Returns the new base port, or None when the configured block is free (or no fallback is).
pub fn free_tun_port_base(port: i32, is_taken: impl Fn(i32) -> bool) -> Option<i32> {
    let block_free = |base: i32| {
        base + PORT_BLOCK <= MAX_PORT && (0..PORT_BLOCK).all(|i| !is_taken(base + i))
    };
    if block_free(port) {
        return None;
    }
    (0..64)
        .map(|k| TUN_FALLBACK_PORT + k * PORT_BLOCK)
        .find(|base| block_free(*base))
}

/// Kotlin withFreeTunPort with the probe and the log passed in.
pub fn with_free_tun_port(
    settings: &DesktopSocksProxySettings,
    is_taken: impl Fn(i32) -> bool,
    log: &mut dyn FnMut(String),
) -> DesktopSocksProxySettings {
    match free_tun_port_base(settings.port, is_taken) {
        Some(base) => {
            log(format!(
                "Local port {} is taken by another program \u{2014} this session uses {}",
                settings.port, base
            ));
            let mut moved = settings.clone();
            moved.port = base;
            moved
        }
        None => settings.clone(),
    }
}

/// POSIX single-quoted: the only escape inside is closing the quote, inserting a literal ', reopening.
pub fn shell_quoted_command(command: &[String]) -> String {
    command
        .iter()
        .map(|it| format!("'{}'", it.replace('\'', "'\\''")))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Text of runtime/linux-tun-launch.sh: hev in the background with tagged output, olcRTC exec'd.
pub fn linux_tun_launch_script(hev_command: &[String], olcrtc_command: &[String]) -> String {
    let mut s = String::new();
    s.push_str("#!/bin/sh\n");
    s.push_str(&shell_quoted_command(hev_command));
    s.push_str(" 2>&1 | sed -u 's/^/");
    s.push_str(HEV_LOG_PREFIX);
    s.push_str("/' &\n");
    s.push_str("exec ");
    s.push_str(&shell_quoted_command(olcrtc_command));
    s.push('\n');
    s
}

pub fn write_linux_tun_launch_script(
    app_data_dir: &Path,
    hev_command: &[String],
    olcrtc_command: &[String],
) -> io::Result<PathBuf> {
    let runtime_dir = app_data_dir.join("runtime");
    fs::create_dir_all(&runtime_dir)?;
    let script = runtime_dir.join("linux-tun-launch.sh");
    fs::write(&script, linux_tun_launch_script(hev_command, olcrtc_command))?;
    // Kotlin ignores setExecutable's result too.
    let _ = set_owner_executable(&script);
    Ok(script)
}

#[cfg(unix)]
fn set_owner_executable(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut perms = fs::metadata(path)?.permissions();
    perms.set_mode(perms.mode() | 0o100);
    fs::set_permissions(path, perms)
}

#[cfg(not(unix))]
fn set_owner_executable(_path: &Path) -> io::Result<()> {
    Ok(())
}

/// SIGTERM, poll `kill -0` up to PROCESS_STOP_TIMEOUT_MS, then SIGKILL; all inside one privileged
/// script so one pkexec prompt covers the whole escalation.
pub fn kill_with_escalation_script(pid: u32) -> String {
    let poll_iterations = PROCESS_STOP_TIMEOUT_MS / 100;
    [
        format!("kill -TERM {} 2>/dev/null || true", pid),
        String::from("i=0"),
        format!("while kill -0 {} 2>/dev/null; do", pid),
        String::from("    i=$((i + 1))"),
        format!(
            "    if [ $i -ge {} ]; then kill -KILL {} 2>/dev/null || true; break; fi",
            poll_iterations, pid
        ),
        String::from("    sleep 0.1"),
        String::from("done"),
    ]
    .join("\n")
}

/// The privileged Linux TUN stop script: the elevated olcRTC kill (when it is still alive) plus
/// LinuxTunController.privilegedCleanupCommands. None when nothing ever ran as root.
pub fn privileged_stop_script(alive_pid: Option<u32>, cleanup_commands: &[String]) -> Option<String> {
    let mut script = String::new();
    if let Some(pid) = alive_pid {
        script.push_str(&kill_with_escalation_script(pid));
        script.push('\n');
    }
    for command in cleanup_commands {
        script.push_str(command);
        script.push('\n');
    }
    if script.trim().is_empty() {
        None
    } else {
        Some(script)
    }
}

/// Writes the stop script to runtime/linux-tun-stop-*.sh (Kotlin Files.createTempFile).
pub fn write_privileged_stop_script(app_data_dir: &Path, script: &str) -> io::Result<PathBuf> {
    let runtime_dir = app_data_dir.join("runtime");
    fs::create_dir_all(&runtime_dir)?;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let path = runtime_dir.join(format!("linux-tun-stop-{}-{}.sh", std::process::id(), nanos));
    fs::write(&path, script)?;
    Ok(path)
}

pub fn is_fatal_olcrtc_startup_line(line: &str) -> bool {
    let text = line.to_lowercase();
    text.contains("failed to connect link")
        || text.contains("join room failed")
        || (text.contains("get room token") && text.contains("failed"))
        || (text.contains("transport connect") && text.contains("failed"))
}

/// TCP connect to the local SOCKS port with TCP_CONNECT_TIMEOUT_MS.
pub fn can_connect_to_socks(port: i32) -> bool {
    let port = match u16::try_from(port) {
        Ok(p) => p,
        Err(_) => return false,
    };
    let addr: SocketAddr = match format!("{}:{}", LOCAL_SOCKS_HOST, port).parse() {
        Ok(a) => a,
        Err(_) => return false,
    };
    TcpStream::connect_timeout(&addr, Duration::from_millis(TCP_CONNECT_TIMEOUT_MS)).is_ok()
}

/// What waitForOlcRtcReady looks at while the olcRTC process starts.
pub trait OlcRtcStartup {
    /// A newer start request won (Kotlin: requestGeneration != generation). False when not tracked.
    fn superseded(&self) -> bool;
    /// A fatal startup line was seen (see is_fatal_olcrtc_startup_line).
    fn startup_failure(&self) -> Option<String>;
    /// olcRTC reported readiness on its output.
    fn ready(&self) -> bool;
    fn is_alive(&mut self) -> bool;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OlcRtcStartError {
    /// Kotlin CancellationException("Desktop start superseded").
    Superseded,
    Failed(String),
}

impl fmt::Display for OlcRtcStartError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            OlcRtcStartError::Superseded => f.write_str("Desktop start superseded"),
            OlcRtcStartError::Failed(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for OlcRtcStartError {}

fn failed(message: &str) -> OlcRtcStartError {
    OlcRtcStartError::Failed(String::from(message))
}

fn failed_before_proxy(reason: &str) -> OlcRtcStartError {
    OlcRtcStartError::Failed(format!(
        "olcRTC failed before desktop proxy was enabled: {}",
        reason
    ))
}

pub fn wait_for_olcrtc_ready(
    startup: &mut dyn OlcRtcStartup,
    socks_port: i32,
) -> Result<(), OlcRtcStartError> {
    wait_for_olcrtc_ready_with(
        startup,
        socks_port,
        Duration::from_millis(OLC_READY_TIMEOUT_MS),
        Duration::from_millis(OLC_STARTUP_STABILITY_MS),
    )
}

pub fn wait_for_olcrtc_ready_with(
    startup: &mut dyn OlcRtcStartup,
    socks_port: i32,
    ready_timeout: Duration,
    stability: Duration,
) -> Result<(), OlcRtcStartError> {
    let deadline = Instant::now() + ready_timeout;
    while Instant::now() < deadline {
        if startup.superseded() {
            return Err(OlcRtcStartError::Superseded);
        }
        if let Some(reason) = startup.startup_failure() {
            return Err(failed_before_proxy(&reason));
        }
        if startup.ready() || can_connect_to_socks(socks_port) {
            return wait_for_olcrtc_startup_stability(startup, stability);
        }
        if !startup.is_alive() {
            return Err(failed("olcRTC exited before SOCKS5 was ready"));
        }
        thread::sleep(Duration::from_millis(READY_POLL_INTERVAL_MS));
    }
    Err(failed("olcRTC start timed out"))
}

fn wait_for_olcrtc_startup_stability(
    startup: &mut dyn OlcRtcStartup,
    stability: Duration,
) -> Result<(), OlcRtcStartError> {
    let deadline = Instant::now() + stability;
    while Instant::now() < deadline {
        if startup.superseded() {
            return Err(OlcRtcStartError::Superseded);
        }
        if let Some(reason) = startup.startup_failure() {
            return Err(failed_before_proxy(&reason));
        }
        if !startup.is_alive() {
            return Err(failed("olcRTC exited before desktop proxy was enabled"));
        }
        thread::sleep(Duration::from_millis(READY_POLL_INTERVAL_MS));
    }
    Ok(())
}

/// Kotlin runBounded: one stop step, logged on failure, abandoned after STOP_STEP_TIMEOUT_MS.
pub fn run_bounded<F>(label: &str, log: &mut dyn FnMut(String), block: F)
where
    F: FnOnce() -> Result<(), String> + Send + 'static,
{
    run_bounded_with(label, Duration::from_millis(STOP_STEP_TIMEOUT_MS), log, block)
}

pub fn run_bounded_with<F>(label: &str, timeout: Duration, log: &mut dyn FnMut(String), block: F)
where
    F: FnOnce() -> Result<(), String> + Send + 'static,
{
    let (tx, rx) = mpsc::channel();
    thread::spawn(move || {
        let _ = tx.send(block());
    });
    match rx.recv_timeout(timeout) {
        Ok(Ok(())) => {}
        Ok(Err(message)) => log(format!("{} failed: {}", label, message)),
        Err(mpsc::RecvTimeoutError::Disconnected) => log(format!("{} failed: panic", label)),
        Err(mpsc::RecvTimeoutError::Timeout) => log(format!(
            "{} did not finish in {}s \u{2014} continuing without it",
            label,
            timeout.as_secs()
        )),
    }
}

pub fn is_public_ipv4(ip: &str) -> bool {
    let o: Vec<i32> = ip.split('.').filter_map(|it| it.parse::<i32>().ok()).collect();
    if o.len() != 4 || o.iter().any(|it| !(0..=255).contains(it)) {
        return false;
    }
    let private = o[0] == 10
        || o[0] == 127
        || o[0] == 0
        || (o[0] == 169 && o[1] == 254)
        || (o[0] == 172 && (16..=31).contains(&o[1]))
        || (o[0] == 192 && o[1] == 168)
        || (o[0] == 100 && (64..=127).contains(&o[1]));
    !private
}
