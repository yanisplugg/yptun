//! Telegram-over-WARP proxy: the platform-neutral half of DesktopTelegramProxy (desktop) and
//! TelegramProxyService (Android). A dedicated WARP AmneziaWG instance exposes a local SOCKS5 at
//! LISTEN_HOST:LISTEN_PORT for Telegram to point at.
//!
//! The core owns everything that is not a timer or an OS handle: the endpoint sweep, the INI
//! rewrites, the stable SOCKS credentials, the log filter, the Telegram-DC self-check and the
//! watchdog decisions. The platform implements TelegramTunnel (start/stop the AWG instance,
//! persistence, UI state) and calls HealthWatchdog::tick every HEALTH_TICK_MS.

use std::fs;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

/// Loopback-only listener.
pub const LISTEN_HOST: &str = "127.0.0.1";
/// Dedicated port, deliberately clear of the main VPN's local ports (10808 and its helpers).
pub const LISTEN_PORT: u16 = 12080;

pub const SELF_CHECK_CONNECT_TIMEOUT_MS: u64 = 3_000;
/// How long the Telegram-DC verification waits for the SOCKS CONNECT reply.
pub const SELF_CHECK_TIMEOUT_MS: u64 = 6_000;
pub const HEALTH_TICK_MS: i64 = 4_000;
pub const HEALTH_VERIFY_MS: i64 = 30_000;
pub const DOWN_RETRY_MAX_MS: i64 = 60_000;
pub const PORT_FREE_TIMEOUT_MS: u64 = 5_000;
pub const PORT_FREE_POLL_MS: u64 = 120;
/// WARP drops an idle session in ~15s, faster than the config's PersistentKeepalive=25.
pub const KEEPALIVE_SECONDS: i32 = 10;

/// Desktop files, next to the other settings JSON files.
pub const CONFIG_FILE_NAME: &str = "telegram_warp.conf";
pub const CREDS_FILE_NAME: &str = "telegram_proxy_creds.json";
/// Android DataStore keys.
pub const ANDROID_KEY_USER: &str = "olcbox_tg_proxy_user";
pub const ANDROID_KEY_PASS: &str = "olcbox_tg_proxy_pass";

/// Core log lines of the Telegram AWG instance carry this prefix.
pub const CORE_LOG_PREFIX: &str = "tgwarp:";

pub const TELEGRAM_DC_IP: [u8; 4] = [149, 154, 167, 51];
pub const TELEGRAM_DC_PORT: u16 = 443;

pub const USER_LEN: usize = 8;
pub const PASS_LEN: usize = 16;
pub const ALPHABET: &str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

pub const MSG_GENERATING: &str = "Telegram proxy: generating a WARP account…";
pub const MSG_STOPPED: &str = "Telegram proxy: stopped";
pub const ERR_GENERATION_FAILED: &str = "WARP config generation failed";
pub const ERR_FIRST_CONNECT: &str = "no working WARP relay yet — retrying";
pub const ERR_RECONNECTING: &str = "reconnecting…";

/// amneziawg-go internal per-goroutine churn — pure noise in the in-app journal.
pub const LOG_NOISE: [&str; 12] = [
    "Routine:",
    "UAPI:",
    "UDP bind has been updated",
    "Interface up requested",
    "Interface state was",
    "transport packet lined up",
    "Device closing",
    "Device closed",
    "- Starting",
    "- Stopping",
    "Sending keepalive packet",
    "Adding allowedip",
];

/// WARP endpoints to try, in order, until one carries traffic to Telegram. WARP is anycast, so the
/// same account works on every one; only IP+port vary to dodge throttling/DPI on the default
/// engage.cloudflareclient.com:2408. The cached/working endpoint is tried first.
pub const WARP_FALLBACK_ENDPOINTS: [&str; 12] = [
    "162.159.192.8:894",
    "engage.cloudflareclient.com:2408",
    "162.159.192.1:894",
    "162.159.192.8:2408",
    "162.159.192.1:2408",
    "162.159.193.10:2408",
    "188.114.96.1:2408",
    "188.114.97.1:2408",
    "162.159.192.1:945",
    "162.159.193.10:4500",
    "188.114.96.1:1701",
    "188.114.97.1:955",
];

// ---------------------------------------------------------------------------------------------
// Credentials + persistence
// ---------------------------------------------------------------------------------------------

/// SOCKS5 username/password, generated once and kept stable so what the user typed into
/// Telegram keeps working.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Credentials {
    pub user: String,
    pub pass: String,
}

impl Credentials {
    pub fn generate() -> Result<Credentials, String> {
        Ok(Credentials { user: random_token(USER_LEN)?, pass: random_token(PASS_LEN)? })
    }

    /// Stored values, or None when either is missing/blank.
    pub fn from_parts(user: Option<&str>, pass: Option<&str>) -> Option<Credentials> {
        match (user, pass) {
            (Some(u), Some(p)) if !u.trim().is_empty() && !p.trim().is_empty() => {
                Some(Credentials { user: u.to_string(), pass: p.to_string() })
            }
            _ => None,
        }
    }

    pub fn from_json(text: &str) -> Option<Credentials> {
        let c = serde_json::from_str::<Credentials>(text).ok()?;
        Credentials::from_parts(Some(&c.user), Some(&c.pass))
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).unwrap_or_default()
    }
}

/// Uniform token over ALPHABET from the OS CSPRNG.
pub fn random_token(length: usize) -> Result<String, String> {
    let alphabet = ALPHABET.as_bytes();
    let limit = alphabet.len() * 4; // 248: largest multiple of 62 that fits a byte
    let mut out = String::with_capacity(length);
    let mut buf = [0u8; 64];
    while out.len() < length {
        getrandom::getrandom(&mut buf).map_err(|e| e.to_string())?;
        for &b in buf.iter() {
            if out.len() == length {
                break;
            }
            if (b as usize) < limit {
                out.push(alphabet[(b as usize) % alphabet.len()] as char);
            }
        }
    }
    Ok(out)
}

pub fn config_path(settings_dir: &Path) -> PathBuf {
    settings_dir.join(CONFIG_FILE_NAME)
}

pub fn creds_path(settings_dir: &Path) -> PathBuf {
    settings_dir.join(CREDS_FILE_NAME)
}

/// The cached WARP INI, or None when the proxy has never been enabled.
pub fn load_config(settings_dir: &Path) -> Option<String> {
    fs::read_to_string(config_path(settings_dir)).ok().filter(|s| !s.trim().is_empty())
}

pub fn save_config(settings_dir: &Path, ini: &str) {
    let _ = fs::create_dir_all(settings_dir);
    let _ = fs::write(config_path(settings_dir), ini);
}

pub fn peek_credentials(settings_dir: &Path) -> Option<Credentials> {
    fs::read_to_string(creds_path(settings_dir)).ok().and_then(|t| Credentials::from_json(&t))
}

/// Stored credentials, generating and persisting a fresh pair on first call.
pub fn get_or_create_credentials(settings_dir: &Path) -> Result<Credentials, String> {
    if let Some(c) = peek_credentials(settings_dir) {
        return Ok(c);
    }
    let created = Credentials::generate()?;
    let _ = fs::create_dir_all(settings_dir);
    let _ = fs::write(creds_path(settings_dir), created.to_json());
    Ok(created)
}

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/// UI state of the Telegram-over-WARP background proxy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TelegramProxyState {
    Stopped,
    /// First-time WARP config generation in progress (requires internet).
    Generating,
    /// Proxy is up; Telegram uses SOCKS5 host:port with user/pass.
    Running { host: String, port: u16, user: String, pass: String },
    Error { message: String },
}

impl TelegramProxyState {
    pub fn running(creds: &Credentials) -> TelegramProxyState {
        TelegramProxyState::Running {
            host: LISTEN_HOST.to_string(),
            port: LISTEN_PORT,
            user: creds.user.clone(),
            pass: creds.pass.clone(),
        }
    }

    pub fn error(message: &str) -> TelegramProxyState {
        TelegramProxyState::Error { message: message.to_string() }
    }
}

// ---------------------------------------------------------------------------------------------
// WARP INI rewrites
// ---------------------------------------------------------------------------------------------

fn kotlin_lines(text: &str) -> Vec<&str> {
    text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).collect()
}

fn starts_with_ignore_case(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix.as_bytes())
}

fn contains_ignore_case(s: &str, needle: &str) -> bool {
    s.to_ascii_lowercase().contains(&needle.to_ascii_lowercase())
}

fn replace_lines(config: &str, key: &str, replacement: &str) -> String {
    kotlin_lines(config)
        .into_iter()
        .map(|line| {
            if starts_with_ignore_case(line.trim_start(), key) {
                replacement.to_string()
            } else {
                line.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Replaces (or appends) the "Endpoint = …" line so the same account uses endpoint.
pub fn rewrite_endpoint(config: &str, endpoint: &str) -> String {
    if !contains_ignore_case(config, "Endpoint") {
        return format!("{}\nEndpoint = {}", config, endpoint);
    }
    replace_lines(config, "Endpoint", &format!("Endpoint = {}", endpoint))
}

pub fn extract_endpoint(config: &str) -> Option<String> {
    let line = kotlin_lines(config)
        .into_iter()
        .find(|l| starts_with_ignore_case(l.trim_start(), "Endpoint"))?;
    let value = match line.find('=') {
        Some(i) => &line[i + 1..],
        None => line,
    };
    let value = value.trim();
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

/// Sets PersistentKeepalive to seconds (replacing it, or inserting it under [Peer]).
pub fn force_keepalive(config: &str, seconds: i32) -> String {
    if contains_ignore_case(config, "PersistentKeepalive") {
        return replace_lines(config, "PersistentKeepalive", &format!("PersistentKeepalive = {}", seconds));
    }
    if config.contains("[Peer]") {
        config.replace("[Peer]", &format!("[Peer]\nPersistentKeepalive = {}", seconds))
    } else {
        format!("{}\nPersistentKeepalive = {}", config, seconds)
    }
}

/// The config actually started for one sweep step: endpoint swapped, short keepalive forced.
pub fn endpoint_config(base_config: &str, endpoint: &str) -> String {
    force_keepalive(&rewrite_endpoint(base_config, endpoint), KEEPALIVE_SECONDS)
}

/// Sweep order: the config's own endpoint first, then the fallbacks; distinct, minus avoid.
pub fn endpoint_sweep(base_config: &str, avoid: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let cached = extract_endpoint(base_config);
    for ep in cached.into_iter().chain(WARP_FALLBACK_ENDPOINTS.iter().map(|s| s.to_string())) {
        if !out.contains(&ep) && !avoid.contains(&ep) {
            out.push(ep);
        }
    }
    out
}

/// Endpoint hosts that must never be routed into the main TUN.
pub fn candidate_endpoint_hosts() -> Vec<String> {
    WARP_FALLBACK_ENDPOINTS
        .iter()
        .map(|ep| match ep.rfind(':') {
            Some(i) => ep[..i].to_string(),
            None => ep.to_string(),
        })
        .collect()
}

// ---------------------------------------------------------------------------------------------
// Core log filter
// ---------------------------------------------------------------------------------------------

/// Filters the Telegram AWG instance's log lines for the journal and raises the degraded flag
/// the watchdog reacts to.
#[derive(Debug, Default, Clone)]
pub struct CoreLogFilter {
    degraded: bool,
}

impl CoreLogFilter {
    pub fn new() -> CoreLogFilter {
        CoreLogFilter::default()
    }

    /// Feeds one core log line; returns the line to show in the journal, if any.
    pub fn on_core_log(&mut self, line: &str) -> Option<String> {
        let rest = line.strip_prefix(CORE_LOG_PREFIX)?;
        let t = rest.trim();
        if t.is_empty() {
            return None;
        }
        if t.contains("operation not permitted") {
            // amneziawg spams EPERM dozens of times/sec — surface only the first.
            let first = !self.degraded;
            self.degraded = true;
            return if first {
                Some("Telegram: UDP send blocked (EPERM) — will rotate/restart".to_string())
            } else {
                None
            };
        }
        if t.contains("stopped hearing back") || t.contains("Handshake did not complete") {
            self.degraded = true;
            return Some(format!("Telegram: {}", t));
        }
        if LOG_NOISE.iter().any(|n| t.contains(n)) {
            return None;
        }
        Some(format!("Telegram: {}", t))
    }

    pub fn is_degraded(&self) -> bool {
        self.degraded
    }

    pub fn take_degraded(&mut self) -> bool {
        std::mem::replace(&mut self.degraded, false)
    }

    pub fn clear(&mut self) {
        self.degraded = false;
    }
}

// ---------------------------------------------------------------------------------------------
// SOCKS5 self-check through the tunnel
// ---------------------------------------------------------------------------------------------

/// Greeting offering no-auth + user/pass.
pub fn socks5_greeting() -> [u8; 4] {
    [0x05, 0x02, 0x00, 0x02]
}

pub fn socks5_auth(creds: &Credentials) -> Vec<u8> {
    let u = creds.user.as_bytes();
    let p = creds.pass.as_bytes();
    let mut out = Vec::with_capacity(3 + u.len() + p.len());
    out.push(0x01);
    out.push(u.len() as u8);
    out.extend_from_slice(u);
    out.push(p.len() as u8);
    out.extend_from_slice(p);
    out
}

/// CONNECT 149.154.167.51:443 (ATYP=IPv4).
pub fn telegram_connect_request() -> [u8; 10] {
    let ip = TELEGRAM_DC_IP;
    let port = TELEGRAM_DC_PORT.to_be_bytes();
    [0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3], port[0], port[1]]
}

/// Decisive verdict that the tunnel CARRIES traffic: authenticate on the local SOCKS5 and
/// CONNECT to a real Telegram DC. A success reply means the SYN/ACK round-tripped through WARP.
pub fn telegram_reachable(host: &str, port: u16, creds: &Credentials) -> bool {
    telegram_reachable_with(
        host,
        port,
        creds,
        Duration::from_millis(SELF_CHECK_CONNECT_TIMEOUT_MS),
        Duration::from_millis(SELF_CHECK_TIMEOUT_MS),
    )
}

pub fn telegram_reachable_with(
    host: &str,
    port: u16,
    creds: &Credentials,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> bool {
    self_check(host, port, creds, connect_timeout, io_timeout).unwrap_or(false)
}

fn self_check(
    host: &str,
    port: u16,
    creds: &Credentials,
    connect_timeout: Duration,
    io_timeout: Duration,
) -> io::Result<bool> {
    let addr = (host, port)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no address"))?;
    let mut s = TcpStream::connect_timeout(&addr, connect_timeout)?;
    s.set_read_timeout(Some(io_timeout))?;
    s.set_write_timeout(Some(io_timeout))?;
    s.write_all(&socks5_greeting())?;
    s.flush()?;
    let mut m = [0u8; 2];
    s.read_exact(&mut m)?;
    if m[0] != 0x05 {
        return Ok(false);
    }
    if m[1] == 0x02 {
        s.write_all(&socks5_auth(creds))?;
        s.flush()?;
        let mut ar = [0u8; 2];
        s.read_exact(&mut ar)?;
        if ar[1] != 0x00 {
            return Ok(false);
        }
    } else if m[1] != 0x00 {
        return Ok(false);
    }
    s.write_all(&telegram_connect_request())?;
    s.flush()?;
    let mut rep = [0u8; 4];
    s.read_exact(&mut rep)?;
    Ok(rep[1] == 0x00)
}

/// Blocks until host:port is bindable again (the previous listener is freed asynchronously).
pub fn await_port_free(host: &str, port: u16, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if TcpListener::bind((host, port)).is_ok() {
            return true;
        }
        thread::sleep(Duration::from_millis(PORT_FREE_POLL_MS));
    }
    false
}

// ---------------------------------------------------------------------------------------------
// Endpoint sweep + watchdog
// ---------------------------------------------------------------------------------------------

/// What the platform provides: the dedicated AWG instance (YpTgAwg*), persistence and UI state.
pub trait TelegramTunnel {
    fn log(&mut self, line: &str);
    /// Starts the AWG instance with config, SOCKS listen "host:port" and creds.
    fn start(&mut self, config: &str, listen: &str, creds: &Credentials) -> Result<(), String>;
    fn stop(&mut self);
    fn running(&mut self) -> bool;
    /// Persists the base INI with the working endpoint so the next start is instant.
    fn save_config(&mut self, ini: &str);
    fn set_state(&mut self, state: TelegramProxyState);
    /// Reads and resets the degraded flag (see CoreLogFilter::take_degraded).
    fn take_degraded(&mut self) -> bool;
    fn clear_degraded(&mut self);

    /// The previous instance's listener is freed asynchronously; starting too soon fails with
    /// "bind: address already in use".
    fn wait_listener_free(&mut self) {
        await_port_free(LISTEN_HOST, LISTEN_PORT, Duration::from_millis(PORT_FREE_TIMEOUT_MS));
    }

    fn reachable(&mut self, creds: &Credentials) -> bool {
        telegram_reachable(LISTEN_HOST, LISTEN_PORT, creds)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SweepResult {
    /// Every candidate was in avoid; nothing was tried.
    NoCandidates,
    AllFailed,
    Working(String),
}

impl SweepResult {
    pub fn is_working(&self) -> bool {
        matches!(self, SweepResult::Working(_))
    }

    pub fn endpoint(&self) -> Option<String> {
        match self {
            SweepResult::Working(ep) => Some(ep.clone()),
            _ => None,
        }
    }
}

/// Sweeps the WARP endpoints and keeps the first that actually reaches a Telegram DC; the
/// working endpoint is cached so the next start is instant.
pub fn bring_up(
    base_config: &str,
    creds: &Credentials,
    avoid: &[String],
    tunnel: &mut dyn TelegramTunnel,
) -> SweepResult {
    let cached = extract_endpoint(base_config);
    let endpoints = endpoint_sweep(base_config, avoid);
    if endpoints.is_empty() {
        return SweepResult::NoCandidates;
    }
    let rotating = if avoid.is_empty() {
        String::new()
    } else {
        format!(" (rotating past {} dead)", avoid.len())
    };
    tunnel.log(&format!(
        "Telegram proxy: trying {} relay(s){} — verifying via Telegram DC",
        endpoints.len(),
        rotating
    ));
    let listen = format!("{}:{}", LISTEN_HOST, LISTEN_PORT);
    let total = endpoints.len();
    for (i, ep) in endpoints.iter().enumerate() {
        let cfg = endpoint_config(base_config, ep);
        tunnel.log(&format!("Telegram: [{}/{}] connecting via {}…", i + 1, total, ep));
        tunnel.wait_listener_free();
        if let Err(e) = tunnel.start(&cfg, &listen, creds) {
            tunnel.log(&format!("Telegram: start failed on {} — {}", ep, e));
            tunnel.stop();
            continue;
        }
        if tunnel.reachable(creds) {
            tunnel.clear_degraded();
            tunnel.set_state(TelegramProxyState::running(creds));
            tunnel.log(&format!(
                "Telegram: ✅ working via {} — SOCKS5 {}:{}, point Telegram here",
                ep, LISTEN_HOST, LISTEN_PORT
            ));
            if cached.as_deref() != Some(ep.as_str()) {
                tunnel.save_config(&rewrite_endpoint(base_config, ep));
                tunnel.log(&format!("Telegram: cached working endpoint {}", ep));
            }
            return SweepResult::Working(ep.clone());
        }
        tunnel.log(&format!("Telegram: {} handshake up but NO traffic to Telegram — trying next", ep));
        tunnel.stop();
    }
    tunnel.log(&format!("Telegram: ❌ all {} relays unreachable for Telegram on this network", total));
    SweepResult::AllFailed
}

/// The first bring-up on enable. The platform starts the watchdog EITHER WAY, so a network that
/// isn't ready yet keeps being retried instead of leaving the proxy dead until re-toggled.
pub fn initial_bring_up(
    base_config: &str,
    creds: &Credentials,
    tunnel: &mut dyn TelegramTunnel,
) -> SweepResult {
    let result = bring_up(base_config, creds, &[], tunnel);
    if !result.is_working() {
        tunnel.log("Telegram: first connect didn't land — watchdog will keep retrying");
        tunnel.set_state(TelegramProxyState::error(ERR_FIRST_CONNECT));
    }
    result
}

/// Keeps the tunnel alive for as long as the proxy is enabled — it never permanently gives up.
/// The platform calls tick every HEALTH_TICK_MS with the wall clock; reset its degraded flag
/// when creating the watchdog.
#[derive(Debug, Clone)]
pub struct HealthWatchdog {
    base_config: String,
    creds: Credentials,
    consecutive_fails: u32,
    since_verify_ms: i64,
    dead: Vec<String>,
    last_wall_ms: i64,
    down_wait_ms: i64,
    active_endpoint: Option<String>,
}

impl HealthWatchdog {
    pub fn new(
        base_config: &str,
        creds: &Credentials,
        now_ms: i64,
        active_endpoint: Option<String>,
    ) -> HealthWatchdog {
        HealthWatchdog {
            base_config: base_config.to_string(),
            creds: creds.clone(),
            consecutive_fails: 0,
            since_verify_ms: 0,
            dead: Vec::new(),
            last_wall_ms: now_ms,
            down_wait_ms: 0,
            active_endpoint,
        }
    }

    /// The WARP endpoint the live tunnel uses — the desktop routes it around the main TUN.
    pub fn active_endpoint(&self) -> Option<&str> {
        self.active_endpoint.as_deref()
    }

    pub fn down_wait_ms(&self) -> i64 {
        self.down_wait_ms
    }

    pub fn dead(&self) -> &[String] {
        &self.dead
    }

    fn note_sweep(&mut self, result: &SweepResult) {
        match result {
            SweepResult::Working(ep) => self.active_endpoint = Some(ep.clone()),
            SweepResult::AllFailed => self.active_endpoint = None,
            SweepResult::NoCandidates => {}
        }
    }

    fn revive(&mut self, tunnel: &mut dyn TelegramTunnel) -> bool {
        let first = bring_up(&self.base_config, &self.creds, &self.dead, tunnel);
        self.note_sweep(&first);
        let mut ok = first.is_working();
        if !ok && !self.dead.is_empty() {
            self.dead.clear();
            let second = bring_up(&self.base_config, &self.creds, &[], tunnel);
            self.note_sweep(&second);
            ok = second.is_working();
        }
        if ok {
            self.dead.clear();
            self.down_wait_ms = 0;
            self.consecutive_fails = 0;
            self.since_verify_ms = 0;
        }
        ok
    }

    pub fn tick(&mut self, now_ms: i64, tunnel: &mut dyn TelegramTunnel) {
        // A wall-clock gap much larger than the tick means the machine was suspended: forget
        // dead relays and treat it as a fresh chance.
        let wake = now_ms - self.last_wall_ms > HEALTH_TICK_MS * 3;
        self.last_wall_ms = now_ms;
        if wake {
            self.dead.clear();
            self.down_wait_ms = 0;
            self.consecutive_fails = 0;
            tunnel.log("Telegram: woke from sleep — re-checking tunnel now");
        }

        // TUNNEL DOWN: keep trying to bring it back, with a capped backoff.
        if !tunnel.running() {
            if !wake && self.down_wait_ms > 0 {
                self.down_wait_ms -= HEALTH_TICK_MS;
                return;
            }
            if !self.revive(tunnel) {
                let next = if self.down_wait_ms <= 0 { HEALTH_TICK_MS } else { self.down_wait_ms * 2 };
                self.down_wait_ms = next.min(DOWN_RETRY_MAX_MS);
                tunnel.log(&format!(
                    "Telegram: no working relay yet — retrying in {}s",
                    self.down_wait_ms / 1000
                ));
            }
            return;
        }

        // TUNNEL UP: verify on a degrade signal, a wake, or the slow heartbeat.
        let saw_degrade = tunnel.take_degraded();
        self.since_verify_ms += HEALTH_TICK_MS;
        if !saw_degrade && !wake && self.since_verify_ms < HEALTH_VERIFY_MS {
            return;
        }
        self.since_verify_ms = 0;

        if tunnel.reachable(&self.creds) {
            self.consecutive_fails = 0;
            return;
        }
        // A degrade signal or a wake is decisive; an ordinary blip waits for 2 fails.
        if !saw_degrade && !wake {
            self.consecutive_fails += 1;
            if self.consecutive_fails < 2 {
                return;
            }
        }
        self.consecutive_fails = 0;
        if let Some(ep) = self.active_endpoint.clone() {
            if !self.dead.contains(&ep) {
                self.dead.push(ep);
            }
        }
        tunnel.log("Telegram: tunnel died — rotating relay");
        tunnel.stop();
        tunnel.set_state(TelegramProxyState::error(ERR_RECONNECTING));
        if !self.revive(tunnel) {
            self.down_wait_ms = HEALTH_TICK_MS;
        }
    }
}
