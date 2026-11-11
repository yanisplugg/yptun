//! DesktopVpnManager part 4: public start/stop API decisions, pings and TUN bypass routes.
//! Straight port of the Kotlin manager (startVpn/stopVpn/beginStart, pingVerified, pingOnce,
//! tcpPing, icmpPing/systemPing, tunnelPing/socks5ConnectRtt, proxyUrlTest, trustTunnelUrlTest,
//! resolveBypassServerIps, vkTurnMediaPrefixes, openFluxCarrierPrefixes, awgEndpointHost,
//! subscriptionFetchProxy, VpnConflictPrompt).
//! Everything bound to the OS or to the native core (xray/awg delay probes, Trust Tunnel
//! client, ASN lookups, OlcRtc checker) goes through host traits or closures.

use std::collections::hash_map::RandomState;
use std::collections::HashSet;
use std::hash::{BuildHasher, Hasher};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, ToSocketAddrs};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use crate::desktop_native_assets::DesktopOs;
use crate::desktop_socks_settings::DesktopSocksProxySettings;
use crate::desktop_vpn_manager::{
    MAX_VK_TURN_BYPASS_PREFIXES, OPENFLUX_MAX_HOSTS, OPENFLUX_YANDEX_ASNS, TCP_PING_ATTEMPTS,
    TUNNEL_PROBE_HOST, TUNNEL_PROBE_PORT, VK_TURN_ASNS, VK_TURN_CONTROL_HOSTS,
};
use crate::desktop_vpn_start::{DesktopVpnSession, DesktopVpnStatus};
use crate::location::LocationConfig;
use crate::model::{EngineType, ProxyProfile, TYPE_AMNEZIAWG, TYPE_HYSTERIA2, TYPE_OPENVPN, TYPE_TRUSTTUNNEL};
use crate::settings::{PING_ICMP, PING_PROXY_GET, PING_PROXY_HEAD, PING_TCP};
use crate::transports::{WarpMode, OF_AVITO, OF_CUPS, OF_DIRECT, OF_MAILRU, OF_TELEMOST, OF_VYANDEX};

// ---------------------------------------------------------------------------------------------
// Public API: startVpn / stopVpn / beginStart / close
// ---------------------------------------------------------------------------------------------

pub const RESTART_LOG: &str = "Restarting desktop VPN for selected location";

/// Kotlin: status is Connected/Connecting/Reconnecting || process != null || tunProcess != null.
pub fn should_restart(status: &DesktopVpnStatus, has_process: bool, has_tun_process: bool) -> bool {
    matches!(
        status,
        DesktopVpnStatus::Connected | DesktopVpnStatus::Connecting | DesktopVpnStatus::Reconnecting
    ) || has_process
        || has_tun_process
}

/// Synchronous half of beginStart(auto): sets the reconnect flags and returns the new
/// request generation (the caller stores it as its generation counter).
pub fn begin_start_request(session: &mut DesktopVpnSession, generation: &mut u64, auto: bool) -> u64 {
    session.auto_reconnect = auto;
    if !auto {
        session.auto_reconnect_attempt = 0;
    }
    *generation += 1;
    *generation
}

/// Synchronous half of stopVpn(): returns the status to publish immediately (Stopping)
/// unless the manager is already disconnected.
pub fn stop_vpn_request(
    session: &mut DesktopVpnSession,
    generation: &mut u64,
    status: &DesktopVpnStatus,
) -> Option<DesktopVpnStatus> {
    session.auto_reconnect = false;
    *generation += 1;
    if !matches!(status, DesktopVpnStatus::Disconnected) {
        Some(DesktopVpnStatus::Stopping)
    } else {
        None
    }
}

/// The work beginStart launches under the operation mutex.
pub trait BeginStartHost {
    fn generation(&self) -> u64;
    fn status(&self) -> DesktopVpnStatus;
    fn has_process(&self) -> bool;
    fn has_tun_process(&self) -> bool;
    fn set_status(&mut self, status: DesktopVpnStatus);
    fn log(&mut self, message: String);
    fn stop_desktop_mode(&mut self, final_stop: bool);
    fn start_desktop_mode(&mut self, request_generation: u64, is_restart: bool);
}

pub fn run_begin_start(host: &mut dyn BeginStartHost, request_generation: u64) {
    if request_generation != host.generation() {
        return;
    }
    let restart = should_restart(&host.status(), host.has_process(), host.has_tun_process());
    if restart {
        host.set_status(DesktopVpnStatus::Reconnecting);
        host.log(RESTART_LOG.to_string());
        host.stop_desktop_mode(false);
        if request_generation != host.generation() {
            return;
        }
    }
    host.start_desktop_mode(request_generation, restart);
}

/// Kotlin liveSocks(): the session SOCKS when a session runs, else the normalized settings.
pub fn live_socks(
    session_socks: Option<&DesktopSocksProxySettings>,
    configured: &DesktopSocksProxySettings,
    os: DesktopOs,
) -> DesktopSocksProxySettings {
    match session_socks {
        Some(s) => s.clone(),
        None => configured.normalized(os),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubscriptionFetchProxy {
    pub host: String,
    pub port: i32,
    pub username: String,
    pub password: String,
}

/// Subscriptions are fetched through the tunnel only while it is up.
pub fn subscription_fetch_proxy(
    status: &DesktopVpnStatus,
    live: &DesktopSocksProxySettings,
) -> Option<SubscriptionFetchProxy> {
    match status {
        DesktopVpnStatus::Connected | DesktopVpnStatus::Reconnecting => Some(SubscriptionFetchProxy {
            host: live.host.clone(),
            port: live.port,
            username: live.username.clone(),
            password: live.password.clone(),
        }),
        _ => None,
    }
}

/// Kotlin VpnConflictPrompt(names, decision): close() answers true, ignore() answers false.
/// The decision completes once; later answers are dropped.
pub struct VpnConflictPrompt {
    pub names: Vec<String>,
    decision: mpsc::SyncSender<bool>,
}

impl VpnConflictPrompt {
    pub fn new(names: Vec<String>) -> (VpnConflictPrompt, mpsc::Receiver<bool>) {
        let (tx, rx) = mpsc::sync_channel(1);
        (VpnConflictPrompt { names, decision: tx }, rx)
    }
    pub fn close(&self) {
        let _ = self.decision.try_send(true);
    }
    pub fn ignore(&self) {
        let _ = self.decision.try_send(false);
    }
}

// ---------------------------------------------------------------------------------------------
// Pings
// ---------------------------------------------------------------------------------------------

/// pingVerified: proxy GET/HEAD modes already are URL tests; every other mode is confirmed
/// with a HEAD to the ping URL.
pub fn ping_needs_url_confirmation(ping_mode: &str) -> bool {
    ping_mode != PING_PROXY_GET && ping_mode != PING_PROXY_HEAD
}

/// pingInternal: `rounds` pings, the best wins; the first failure ends the run.
pub fn best_of_rounds(rounds: i32, mut once: impl FnMut() -> Option<u64>) -> Option<u64> {
    let mut best: Option<u64> = None;
    for _ in 0..rounds.max(0) {
        match once() {
            None => return best,
            Some(ms) => {
                if best.map_or(true, |b| ms < b) {
                    best = Some(ms);
                }
            }
        }
    }
    best
}

/// tcpPing/icmpPing/tunnelPing attempts: failures are skipped, the best success wins.
pub fn best_of_attempts(attempts: u32, mut once: impl FnMut() -> Option<u64>) -> Option<u64> {
    let mut best: Option<u64> = None;
    for _ in 0..attempts {
        if let Some(ms) = once() {
            if best.map_or(true, |b| ms < b) {
                best = Some(ms);
            }
        }
    }
    best
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PingMethod {
    Tcp { host: Option<String>, port: Option<i32> },
    Icmp(Option<String>),
    /// proxyUrlTest with "GET" or "HEAD".
    ProxyUrl(&'static str),
    /// SOCKS5 CONNECT RTT through the running tunnel.
    Tunnel,
    AwgProbe(String),
    /// OlcRtcConnectionChecker.ping(location, deviceId).
    OlcRtc,
}

/// pingOnce dispatch. `config` is the normalized location; `tt_endpoint` is
/// TrustTunnelParser.endpoints(ttConfig).first (only used for Trust Tunnel profiles).
pub fn ping_method(
    config: &LocationConfig,
    ping_mode: &str,
    tt_endpoint: Option<(String, i32)>,
    is_active: bool,
    is_connected: bool,
) -> PingMethod {
    let profile: Option<&ProxyProfile> = config.proxy.as_ref();
    let tt = match profile {
        Some(p) if p.kind == TYPE_TRUSTTUNNEL => tt_endpoint,
        _ => None,
    };
    let server: Option<String> = match &tt {
        Some((h, _)) => Some(h.clone()),
        None => profile.map(|p| p.server.clone()),
    };
    let port: Option<i32> = match &tt {
        Some((_, p)) => Some(*p),
        None => profile.map(|p| p.server_port),
    };
    if profile.is_some() {
        if ping_mode == PING_TCP {
            return PingMethod::Tcp { host: server, port };
        }
        if ping_mode == PING_ICMP {
            return PingMethod::Icmp(server);
        }
        if ping_mode == PING_PROXY_GET {
            return PingMethod::ProxyUrl("GET");
        }
        if ping_mode == PING_PROXY_HEAD {
            return PingMethod::ProxyUrl("HEAD");
        }
    }
    let live = is_active && is_connected;
    if config.engine == EngineType::VkTurn {
        return PingMethod::Tunnel;
    }
    if let Some(p) = profile {
        if p.kind == TYPE_AMNEZIAWG {
            return if live { PingMethod::Tunnel } else { PingMethod::AwgProbe(p.awg_config.clone()) };
        }
        if p.kind == TYPE_HYSTERIA2 {
            return if live { PingMethod::Tunnel } else { PingMethod::Icmp(Some(p.server.clone())) };
        }
        if p.kind == TYPE_TRUSTTUNNEL {
            return if live { PingMethod::Tunnel } else { PingMethod::Tcp { host: server, port } };
        }
    }
    if config.engine == EngineType::Standard || config.engine == EngineType::Chain {
        return PingMethod::Tcp {
            host: profile.map(|p| p.server.clone()),
            port: profile.map(|p| p.server_port),
        };
    }
    PingMethod::OlcRtc
}

fn elapsed_ms(start: Instant) -> u64 {
    start.elapsed().as_millis() as u64
}

fn resolve_first(host: &str, port: u16) -> Option<SocketAddr> {
    (host, port).to_socket_addrs().ok()?.next()
}

/// One TCP connect RTT (InetSocketAddress resolves before the timer starts).
pub fn tcp_connect_ms(host: &str, port: u16, timeout_ms: u64) -> Option<u64> {
    let addr = resolve_first(host, port)?;
    let start = Instant::now();
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms.max(1))).ok()?;
    let ms = elapsed_ms(start);
    drop(stream);
    Some(ms)
}

pub fn tcp_ping(host: Option<&str>, port: Option<i32>, timeout_ms: u64) -> Option<u64> {
    let host = host?.trim();
    if host.is_empty() {
        return None;
    }
    let port = port?;
    if !(1..=65535).contains(&port) {
        return None;
    }
    best_of_attempts(TCP_PING_ATTEMPTS, || tcp_connect_ms(host, port as u16, timeout_ms))
}

/// icmpPing: std has no raw ICMP, so every attempt goes to the system ping
/// (Kotlin tries InetAddress.isReachable first and falls back to the same command).
pub fn icmp_ping(host: Option<&str>, timeout_ms: u64, os: DesktopOs) -> Option<u64> {
    let host = host?.trim();
    if host.is_empty() {
        return None;
    }
    best_of_attempts(TCP_PING_ATTEMPTS, || system_ping(host, timeout_ms, os))
}

pub fn system_ping_command(os: DesktopOs, host: &str, timeout_ms: u64) -> Vec<String> {
    match os {
        DesktopOs::Windows => vec![
            "ping".into(),
            "-n".into(),
            "1".into(),
            "-w".into(),
            timeout_ms.to_string(),
            host.to_string(),
        ],
        _ => vec![
            "ping".into(),
            "-c".into(),
            "1".into(),
            "-W".into(),
            (timeout_ms / 1000).max(1).to_string(),
            host.to_string(),
        ],
    }
}

/// Kotlin regex `[=<]\s*(\d+)\s*(?:ms|\u043c\u0441)` (ignore case), first match.
pub fn parse_system_ping_ms(output: &str) -> Option<u64> {
    let chars: Vec<char> = output.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '=' || chars[i] == '<' {
            let mut j = i + 1;
            while j < chars.len() && chars[j].is_whitespace() {
                j += 1;
            }
            let digits_start = j;
            while j < chars.len() && chars[j].is_ascii_digit() {
                j += 1;
            }
            if j > digits_start {
                let digits: String = chars[digits_start..j].iter().collect();
                let mut k = j;
                while k < chars.len() && chars[k].is_whitespace() {
                    k += 1;
                }
                if k + 1 < chars.len() {
                    let a = chars[k].to_lowercase().next().unwrap_or(chars[k]);
                    let b = chars[k + 1].to_lowercase().next().unwrap_or(chars[k + 1]);
                    let unit = (a == 'm' && b == 's') || (a == '\u{43c}' && b == '\u{441}');
                    if unit {
                        if let Ok(ms) = digits.parse::<u64>() {
                            return Some(ms);
                        }
                    }
                }
            }
        }
        i += 1;
    }
    None
}

/// Runs the system ping, waits up to timeout + 2 s, non-zero exit or no RTT means failure.
pub fn system_ping(host: &str, timeout_ms: u64, os: DesktopOs) -> Option<u64> {
    let cmd = system_ping_command(os, host, timeout_ms);
    let mut child = Command::new(&cmd[0])
        .args(&cmd[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let mut stdout = child.stdout.take()?;
    let reader = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = stdout.read_to_end(&mut buf);
        String::from_utf8_lossy(&buf).into_owned()
    });
    let deadline = Instant::now() + Duration::from_millis(timeout_ms + 2000);
    let status = loop {
        match child.try_wait() {
            Ok(Some(s)) => break s,
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            Err(_) => return None,
        }
    };
    let output = reader.join().unwrap_or_default();
    if !status.success() {
        return None;
    }
    parse_system_ping_ms(&output)
}

/// awgProbePing: blank config -> None; probe error counts as -1.
pub fn awg_probe_ping(awg_config: &str, probe: impl FnOnce(&str) -> Result<i64, String>) -> Option<u64> {
    if awg_config.trim().is_empty() {
        return None;
    }
    let ms = probe(awg_config).unwrap_or(-1);
    if ms >= 0 {
        Some(ms as u64)
    } else {
        None
    }
}

pub fn socks5_greeting(use_auth: bool) -> Vec<u8> {
    if use_auth {
        vec![5, 2, 0, 2]
    } else {
        vec![5, 1, 0]
    }
}

pub fn socks5_auth_request(username: &str, password: &str) -> Vec<u8> {
    let u = username.as_bytes();
    let p = password.as_bytes();
    let mut out = Vec::with_capacity(3 + u.len() + p.len());
    out.push(1);
    out.push(u.len() as u8);
    out.extend_from_slice(u);
    out.push(p.len() as u8);
    out.extend_from_slice(p);
    out
}

pub fn socks5_connect_ipv4_request(ip: Ipv4Addr, port: u16) -> Vec<u8> {
    let o = ip.octets();
    vec![5, 1, 0, 1, o[0], o[1], o[2], o[3], (port >> 8) as u8, (port & 0xff) as u8]
}

fn read_exactly(stream: &mut TcpStream, len: usize) -> Option<Vec<u8>> {
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf).ok()?;
    Some(buf)
}

/// socks5ConnectRtt: time from the TCP connect to the CONNECT reply (bound address read).
pub fn socks5_connect_rtt(
    proxy_host: &str,
    proxy_port: u16,
    username: &str,
    password: &str,
    target_ipv4: &str,
    target_port: u16,
    timeout_ms: u64,
) -> Option<u64> {
    let target: Ipv4Addr = target_ipv4.parse().ok()?;
    let addr = resolve_first(proxy_host, proxy_port)?;
    let timeout = Duration::from_millis(timeout_ms.max(1));
    let mut s = TcpStream::connect_timeout(&addr, timeout).ok()?;
    s.set_read_timeout(Some(timeout)).ok()?;
    s.set_write_timeout(Some(timeout)).ok()?;
    let start = Instant::now();
    let use_auth = !username.trim().is_empty();
    s.write_all(&socks5_greeting(use_auth)).ok()?;
    s.flush().ok()?;
    let method = read_exactly(&mut s, 2)?;
    if method[0] != 5 {
        return None;
    }
    match method[1] {
        0 => {}
        2 => {
            s.write_all(&socks5_auth_request(username, password)).ok()?;
            s.flush().ok()?;
            let auth = read_exactly(&mut s, 2)?;
            if auth[1] != 0 {
                return None;
            }
        }
        _ => return None,
    }
    s.write_all(&socks5_connect_ipv4_request(target, target_port)).ok()?;
    s.flush().ok()?;
    let head = read_exactly(&mut s, 4)?;
    if head[1] != 0 {
        return None;
    }
    let addr_len = match head[3] {
        1 => 4usize,
        4 => 16usize,
        3 => read_exactly(&mut s, 1)?[0] as usize,
        _ => return None,
    };
    read_exactly(&mut s, addr_len + 2)?;
    Some(elapsed_ms(start))
}

/// tunnelPing: SOCKS5 CONNECT RTT to TUNNEL_PROBE_HOST:TUNNEL_PROBE_PORT through the tunnel.
pub fn tunnel_ping(is_connected: bool, socks: &DesktopSocksProxySettings, timeout_ms: u64) -> Option<u64> {
    if !is_connected {
        return None;
    }
    if !(1..=65535).contains(&socks.port) {
        return None;
    }
    best_of_attempts(TCP_PING_ATTEMPTS, || {
        socks5_connect_rtt(
            &socks.host,
            socks.port as u16,
            &socks.username,
            &socks.password,
            TUNNEL_PROBE_HOST,
            TUNNEL_PROBE_PORT,
            timeout_ms,
        )
    })
}

// ---------------------------------------------------------------------------------------------
// Proxy URL tests
// ---------------------------------------------------------------------------------------------

/// (20_000..60_000).random()
pub fn random_test_port() -> i32 {
    let mut h = RandomState::new().build_hasher();
    h.write_u128(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0),
    );
    20_000 + (h.finish() % 40_001) as i32
}

pub fn trust_tunnel_ping_dir(port: i32) -> String {
    format!("trusttunnel-ping-{}", port)
}

pub trait UrlTestHost {
    fn log(&mut self, message: String);
    /// YpTunCore.awgMeasureDelay(cfg, url, method, timeout).
    fn awg_measure_delay(&mut self, awg_config: &str, url: &str, method: &str, timeout_ms: u64) -> Result<i64, String>;
    /// trustTunnelUrlTest (see run_trust_tunnel_url_test).
    fn trust_tunnel_url_test(&mut self, tt_config: &str, url: &str, method: &str) -> Option<u64>;
    /// XrayConfig.buildRawProxyPingConfig(raw, port, "127.0.0.1").
    fn build_raw_proxy_ping_config(&mut self, raw: &str, listen_port: i32, listen_host: &str) -> Option<String>;
    /// XrayConfig.build(profile, port, "127.0.0.1", "none", blockQuic = false).
    fn build_xray_config(&mut self, profile: &ProxyProfile, listen_port: i32, listen_host: &str) -> Result<String, String>;
    /// YpTunCore.xrayMeasureDelay(json, url, method, timeout).
    fn xray_measure_delay(&mut self, config_json: &str, url: &str, method: &str, timeout_ms: u64) -> Result<i64, String>;
}

pub fn proxy_url_test(
    host: &mut dyn UrlTestHost,
    location: &LocationConfig,
    url: &str,
    method: &str,
    timeout_ms: u64,
    listen_port: i32,
) -> Option<u64> {
    let profile = match location.proxy.as_ref() {
        Some(p) => p,
        None => {
            host.log(format!("Proxy {} ping: location has no proxy", method));
            return None;
        }
    };
    if profile.kind == TYPE_AMNEZIAWG {
        if profile.awg_config.trim().is_empty() {
            return None;
        }
        let ms = host.awg_measure_delay(&profile.awg_config, url, method, timeout_ms).unwrap_or(-1);
        return if ms >= 0 { Some(ms as u64) } else { None };
    }
    if profile.kind == TYPE_TRUSTTUNNEL {
        return host.trust_tunnel_url_test(&profile.tt_config, url, method);
    }
    if profile.kind == TYPE_HYSTERIA2 {
        host.log(format!("Proxy {} ping: Hysteria2 not supported (use Auto/ICMP)", method));
        return None;
    }
    if profile.kind == TYPE_OPENVPN {
        host.log(format!("Proxy {} ping: OpenVPN is not supported by the Xray probe (use Auto/ICMP)", method));
        return None;
    }
    if profile.server.trim().is_empty() {
        return None;
    }
    let raw = profile.raw_xray_config.as_deref().unwrap_or("");
    let json = if !raw.trim().is_empty() {
        match host.build_raw_proxy_ping_config(raw, listen_port, "127.0.0.1") {
            Some(j) => j,
            None => {
                host.log(format!(
                    "Proxy {} ping: no proxy outbound in verbatim config for {}",
                    method, profile.server
                ));
                return None;
            }
        }
    } else {
        match host.build_xray_config(profile, listen_port, "127.0.0.1") {
            Ok(j) => j,
            Err(e) => {
                host.log(format!("Proxy {} ping: failed to build test config: {}", method, e));
                return None;
            }
        }
    };
    let ms = match host.xray_measure_delay(&json, url, method, timeout_ms) {
        Ok(v) => v,
        Err(e) => {
            host.log(format!("Proxy {} ping error for {}: {}", method, profile.server, e));
            -1
        }
    };
    if ms >= 0 {
        Some(ms as u64)
    } else {
        None
    }
}

pub trait TrustTunnelTestHost {
    fn log(&mut self, message: String);
    fn now_ms(&self) -> u64;
    fn sleep_ms(&mut self, ms: u64);
    /// DesktopTrustTunnel.start(ttConfig, port, dir).
    fn start_client(&mut self, tt_config: &str, socks_port: i32, work_dir: &str) -> Result<(), String>;
    fn client_running(&mut self) -> bool;
    /// Socket().connect(127.0.0.1:port, 200).
    fn local_port_open(&mut self, port: i32) -> bool;
    /// XrayConfig.build(socks profile tag "trusttunnel" -> 127.0.0.1:socks_port, listen_port, ...).
    fn build_socks_xray_config(&mut self, socks_port: i32, listen_port: i32) -> Result<String, String>;
    fn xray_measure_delay(&mut self, config_json: &str, url: &str, method: &str, timeout_ms: u64) -> Result<i64, String>;
    fn stop_client(&mut self);
    fn delete_work_dir(&mut self, work_dir: &str);
}

pub fn run_trust_tunnel_url_test(
    host: &mut dyn TrustTunnelTestHost,
    tt_config: &str,
    url: &str,
    method: &str,
    timeout_ms: u64,
    socks_port: i32,
    listen_port: i32,
) -> Option<u64> {
    let dir = trust_tunnel_ping_dir(socks_port);
    let result = trust_tunnel_url_test_inner(host, tt_config, url, method, timeout_ms, socks_port, listen_port, &dir);
    let out = match result {
        Ok(v) => v,
        Err(e) => {
            host.log(format!("Proxy {} ping (Trust Tunnel) failed: {}", method, e));
            None
        }
    };
    host.stop_client();
    host.delete_work_dir(&dir);
    out
}

#[allow(clippy::too_many_arguments)]
fn trust_tunnel_url_test_inner(
    host: &mut dyn TrustTunnelTestHost,
    tt_config: &str,
    url: &str,
    method: &str,
    timeout_ms: u64,
    socks_port: i32,
    listen_port: i32,
    dir: &str,
) -> Result<Option<u64>, String> {
    host.start_client(tt_config, socks_port, dir)?;
    let deadline = host.now_ms() + timeout_ms + 5000;
    while host.now_ms() < deadline && host.client_running() && !host.local_port_open(socks_port) {
        host.sleep_ms(100);
    }
    if !host.client_running() {
        host.log(format!("Proxy {} ping: Trust Tunnel client exited", method));
        return Ok(None);
    }
    let json = host.build_socks_xray_config(socks_port, listen_port)?;
    let ms = match host.xray_measure_delay(&json, url, method, timeout_ms) {
        Ok(v) => v,
        Err(e) => {
            host.log(format!("Proxy {} ping (Trust Tunnel) error: {}", method, e));
            -1
        }
    };
    if ms < 0 {
        host.log(format!("Proxy {} ping: no response via Trust Tunnel for {}", method, url));
        return Ok(None);
    }
    Ok(Some(ms as u64))
}

// ---------------------------------------------------------------------------------------------
// TUN bypass routes
// ---------------------------------------------------------------------------------------------

pub const LAN_BYPASS_PREFIXES: [&str; 3] = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"];
pub const WARP_ENGAGE_HOST: &str = "engage.cloudflareclient.com";

/// Values the manager reads from other helpers before collecting bypass hosts.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BypassInputs {
    /// DesktopTrustTunnel.endpointHosts(ttConfig) for a Trust Tunnel proxy.
    pub trust_tunnel_endpoint_hosts: Vec<String>,
    /// WarpHelper.pinnedHost(WARP_ENGAGE_HOST).
    pub warp_pinned_host: String,
    pub telegram_proxy_enabled: bool,
    /// DesktopTelegramProxy.candidateEndpointHosts().
    pub telegram_candidate_hosts: Vec<String>,
    /// routing.bypassLan
    pub bypass_lan: bool,
}

fn distinct(items: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    items.into_iter().filter(|s| seen.insert(s.clone())).collect()
}

fn before_last_colon(s: &str) -> &str {
    match s.rfind(':') {
        Some(i) => &s[..i],
        None => s,
    }
}

fn trim_brackets(s: &str) -> &str {
    s.trim_matches(|c| c == '[' || c == ']')
}

/// java.net.URI(uri).host; None for unparsable or host-less strings.
pub fn uri_host(uri: &str) -> Option<String> {
    let uri = uri.trim();
    let idx = uri.find("://")?;
    let rest = &uri[idx + 3..];
    let end = rest.find(|c| c == '/' || c == '?' || c == '#').unwrap_or(rest.len());
    let authority = &rest[..end];
    let host_port = match authority.rfind('@') {
        Some(i) => &authority[i + 1..],
        None => authority,
    };
    let host = if host_port.starts_with('[') {
        match host_port.find(']') {
            Some(i) => &host_port[..=i],
            None => return None,
        }
    } else {
        match host_port.rfind(':') {
            Some(i) => &host_port[..i],
            None => host_port,
        }
    };
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// awgEndpointHost: host part of the first `Endpoint = host:port` line.
pub fn awg_endpoint_host(awg_config: &str) -> Option<String> {
    if awg_config.trim().is_empty() {
        return None;
    }
    let line = awg_config
        .lines()
        .map(|l| l.trim())
        .find(|l| l.get(..8).map_or(false, |p| p.eq_ignore_ascii_case("endpoint")) && l.contains('='))?;
    let value = line[line.find('=')? + 1..].trim();
    let host = trim_brackets(before_last_colon(value));
    if host.trim().is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

/// Hostnames resolveBypassServerIps resolves (proxy2 deliberately gets no bypass).
pub fn bypass_hosts(config: &LocationConfig, inputs: &BypassInputs) -> Vec<String> {
    let mut hosts: Vec<String> = Vec::new();
    if let Some(p) = config.proxy.as_ref() {
        if p.kind == TYPE_TRUSTTUNNEL {
            hosts.extend(inputs.trust_tunnel_endpoint_hosts.iter().cloned());
        } else if !p.server.trim().is_empty() && p.server != "127.0.0.1" {
            hosts.push(p.server.clone());
        }
        if let Some(h) = awg_endpoint_host(&p.awg_config) {
            hosts.push(h);
        }
    }
    if let Some(s) = config.snolc.as_ref() {
        if !s.host.trim().is_empty() {
            hosts.push(s.host.clone());
        }
    }
    if config.warp_enabled && matches!(config.warp_mode, WarpMode::Client) && !inputs.warp_pinned_host.is_empty() {
        hosts.push(inputs.warp_pinned_host.clone());
    }
    if let Some(vk) = config.vkturn.as_ref() {
        if let Some(h) = uri_host(&vk.uri) {
            hosts.push(h);
        }
        if vk.uses_wdtt() && !vk.wdtt_peer.trim().is_empty() {
            hosts.push(vk.wdtt_peer.clone());
        }
        if vk.uses_csqtt() {
            let peer = vk.csqtt_peer_addr();
            let h = before_last_colon(&peer);
            let h = h.strip_prefix('[').unwrap_or(h);
            let h = h.strip_suffix(']').unwrap_or(h);
            if !h.trim().is_empty() {
                hosts.push(h.to_string());
            }
        }
        hosts.extend(VK_TURN_CONTROL_HOSTS.iter().map(|s| s.to_string()));
    }
    if let Some(md) = config.master_dns.as_ref() {
        hosts.extend(md.resolver_hosts());
    }
    if let Some(of) = config.open_flux.as_ref() {
        if of.uses_max() {
            hosts.extend(OPENFLUX_MAX_HOSTS.iter().map(|s| s.to_string()));
        } else {
            let urls: Vec<String> = if of.is_session() {
                of.carriers.iter().map(|c| c.url.clone()).collect()
            } else {
                vec![of.doc_url.clone()]
            };
            hosts.extend(urls.iter().filter_map(|u| uri_host(u)));
            let types: Vec<String> = if of.is_session() {
                of.carriers.iter().map(|c| c.kind.clone()).collect()
            } else {
                vec![of.transport.clone()]
            };
            if types.iter().any(|t| t == OF_DIRECT) {
                if let Some(c) = of.carriers.iter().find(|c| c.kind == OF_DIRECT) {
                    let h = trim_brackets(before_last_colon(c.dial.trim()));
                    if !h.trim().is_empty() {
                        hosts.push(h.to_string());
                    }
                }
            }
            for t in types.iter().filter(|t| t.as_str() != OF_DIRECT) {
                let extra: &[&str] = if t == OF_MAILRU {
                    &["cloud.mail.ru", "docs.datacloudmail.ru"]
                } else if t == OF_CUPS {
                    &["interview.cups.online"]
                } else if t == OF_AVITO {
                    &["code.avito.ru"]
                } else if t == OF_TELEMOST {
                    &["telemost.yandex.ru"]
                } else if t == OF_VYANDEX {
                    &["docs.yandex.ru", "volga.yandex.ru", "push.yandex.ru", "disk.yandex.ru"]
                } else {
                    &["docs.yandex.ru"]
                };
                hosts.extend(extra.iter().map(|s| s.to_string()));
            }
        }
    }
    if inputs.telegram_proxy_enabled {
        hosts.extend(inputs.telegram_candidate_hosts.iter().cloned());
    }
    hosts
}

/// Flattens ASN prefix lists, keeps IPv4 ones, distinct, at most `limit`.
pub fn ipv4_prefixes(per_asn: &[Vec<String>], limit: usize) -> Vec<String> {
    let flat: Vec<String> = per_asn.iter().flatten().filter(|p| p.contains('.')).cloned().collect();
    distinct(flat).into_iter().take(limit).collect()
}

pub fn vk_turn_prefix_log(count: usize) -> String {
    if count == 0 {
        "VK-TURN: could not resolve VK/OK prefixes \u{2014} the relay may be captured by the TUN".to_string()
    } else {
        format!("VK-TURN: routing {} VK/OK prefix(es) around the TUN so the relay keeps its own path", count)
    }
}

pub fn open_flux_prefix_log(count: usize, uses_max: bool) -> String {
    format!(
        "OpenFlux: routing {} {} prefix(es) around the TUN",
        count,
        if uses_max { "VK/MAX" } else { "Yandex" }
    )
}

pub fn resolve_ipv4(host: &str) -> Result<Vec<String>, String> {
    let addrs = (host, 0u16).to_socket_addrs().map_err(|e| e.to_string())?;
    Ok(addrs
        .filter_map(|a| match a {
            SocketAddr::V4(v4) => Some(v4.ip().to_string()),
            SocketAddr::V6(_) => None,
        })
        .collect())
}

/// The final bypass list: resolved IPs, VK-TURN and OpenFlux carrier prefixes, LAN ranges.
pub fn finalize_bypass(resolved: Vec<String>, vk: Vec<String>, open_flux: Vec<String>, lan: Vec<String>) -> Vec<String> {
    let mut all = resolved;
    all.extend(vk);
    all.extend(open_flux);
    all.extend(lan);
    distinct(all).into_iter().filter(|s| s != "127.0.0.1").collect()
}

/// resolveBypassServerIps. `asn_prefixes(asns)` is JvmAsnResolver.ensure(asns) (one list per
/// ASN, in order); `resolve(host)` returns IPv4 addresses.
pub fn resolve_bypass_server_ips(
    config: &LocationConfig,
    inputs: &BypassInputs,
    asn_prefixes: &mut dyn FnMut(&[&str]) -> Vec<Vec<String>>,
    resolve: &mut dyn FnMut(&str) -> Result<Vec<String>, String>,
    log: &mut dyn FnMut(String),
) -> Vec<String> {
    let mut resolved: Vec<String> = Vec::new();
    for host in distinct(bypass_hosts(config, inputs)) {
        match resolve(&host) {
            Ok(ips) => resolved.extend(ips),
            Err(e) => log(format!("Could not resolve {} for TUN bypass route: {}", host, e)),
        }
    }
    let vk = if config.vkturn.is_some() {
        let p = ipv4_prefixes(&asn_prefixes(&VK_TURN_ASNS[..]), MAX_VK_TURN_BYPASS_PREFIXES);
        log(vk_turn_prefix_log(p.len()));
        p
    } else {
        Vec::new()
    };
    let of = match config.open_flux.as_ref() {
        Some(of) => {
            let uses_max = of.uses_max();
            let asns: &[&str] = if uses_max { &VK_TURN_ASNS[..] } else { &OPENFLUX_YANDEX_ASNS[..] };
            let p = ipv4_prefixes(&asn_prefixes(asns), MAX_VK_TURN_BYPASS_PREFIXES);
            log(open_flux_prefix_log(p.len(), uses_max));
            p
        }
        None => Vec::new(),
    };
    let lan: Vec<String> = if inputs.bypass_lan {
        LAN_BYPASS_PREFIXES.iter().map(|s| s.to_string()).collect()
    } else {
        Vec::new()
    };
    finalize_bypass(resolved, vk, of, lan)
}
