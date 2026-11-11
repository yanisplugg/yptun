//! Pure parts of the desktop subprocess cores: snolc, OpenFlux and the Trust Tunnel CLI.
//! Port of DesktopSnolc.kt, DesktopOpenFlux.kt and DesktopTrustTunnel.kt.
//!
//! Platform-side: spawning, the log-pump threads, stop/kill with timeouts, the temp key file and
//! running `setup_wizard`. Here: command lines, environments, config files, log lines, the `tt://`
//! endpoint parsing/cache and the SOCKS5 warm-up probe.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::Path;
use std::time::{Duration, Instant};

use crate::snolc::{self, SnolcFile};
use crate::transports::{OpenFluxConfig, SnolcConfig};
use crate::util::{is_blank, kotlin_lines};

// ---------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------

/// snolc / OpenFlux: `destroy()`, wait, then `destroyForcibly()`, wait.
pub const STOP_TIMEOUT_MS: u64 = 2_000;
pub const KILL_TIMEOUT_MS: u64 = 2_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProcessState {
    NotStarted,
    Alive,
    Exited(i32),
}

/// Exit text for a start that never opened its port.
pub fn exit_description(state: ProcessState) -> String {
    match state {
        ProcessState::NotStarted => "not started".to_string(),
        ProcessState::Alive => "still starting".to_string(),
        ProcessState::Exited(code) => format!("exited with {code}"),
    }
}

/// A core's output line as logged (`"<prefix>: <line>"`); `None` for blank lines.
pub fn core_log_line(prefix: &str, line: &str) -> Option<String> {
    if is_blank(line) {
        None
    } else {
        Some(format!("{prefix}: {}", line.trim_end()))
    }
}

/// `"<core> stop failed: <message>"` (`core` = `snolc`, `OpenFlux`, `Trust Tunnel`).
pub fn stop_failed_log(core: &str, message: &str) -> String {
    format!("{core} stop failed: {message}")
}

fn take_last_chars(s: &str, n: usize) -> String {
    let count = s.chars().count();
    s.chars().skip(count.saturating_sub(n)).collect()
}

// ---------------------------------------------------------------------------------------------
// snolc
// ---------------------------------------------------------------------------------------------

pub const SNOLC_DIR: &str = "snolc";
pub const SNOLC_STATE_DIR: &str = "state";
pub const SNOLC_MODULES_DIR: &str = "modules";
pub const SNOLC_CONFIG_FILE: &str = "snolc.toml";
pub const SNOLC_PUBKEY_FILE: &str = "pub.hex";
pub const SNOLC_EXIT_ENV: (&str, &str) = ("SNOLC_EXIT_ON_STDIN_EOF", "1");
pub const SNOLC_LOG_PREFIX: &str = "snolc";

/// Files to write under `<appData>/snolc/` (after creating `state/` and `modules/`): the client
/// config set plus `pub.hex`. [`resolved_host`] is the node host after platform resolution
/// (Kotlin `resolveSnolcHost(config.host)`).
pub fn snolc_files(
    config: &SnolcConfig,
    resolved_host: &str,
    listen_host: &str,
    listen_port: i32,
    socks_username: &str,
    socks_password: &str,
) -> Vec<SnolcFile> {
    let mut files = snolc::client(
        listen_host,
        listen_port,
        resolved_host,
        config.port,
        config.debug,
        socks_username,
        socks_password,
    );
    files.push((SNOLC_PUBKEY_FILE.to_string(), config.public_key.clone()));
    files
}

/// `<binary> run <dir>/snolc.toml`, working directory [`dir`], env [`SNOLC_EXIT_ENV`].
pub fn snolc_command(binary: &str, dir: &Path) -> Vec<String> {
    vec![
        binary.to_string(),
        "run".to_string(),
        dir.join(SNOLC_CONFIG_FILE).to_string_lossy().into_owned(),
    ]
}

pub fn snolc_start_log(config: &SnolcConfig, listen_host: &str, listen_port: i32) -> String {
    format!("Starting snolc ({}) on {listen_host}:{listen_port}", config.summary())
}

// ---------------------------------------------------------------------------------------------
// OpenFlux
// ---------------------------------------------------------------------------------------------

pub const OPENFLUX_LOG_PREFIX: &str = "openflux";
pub const OPENFLUX_KEY_FILE_PREFIX: &str = "openflux-key";
pub const OPENFLUX_KEY_FILE_SUFFIX: &str = ".txt";
/// The core reads the key file once at start; the platform deletes it after this delay.
pub const OPENFLUX_KEY_FILE_DELETE_AFTER_MS: u64 = 5_000;

/// A temp key file holding `config.secret` is needed (the secret never goes through argv).
pub fn openflux_needs_key_file(config: &OpenFluxConfig) -> bool {
    !is_blank(&config.secret)
}

/// Client command line; [`key_file`] is the temp file with the secret, if one was written.
pub fn openflux_command(
    binary: &str,
    config: &OpenFluxConfig,
    listen_host: &str,
    listen_port: i32,
    key_file: Option<&str>,
) -> Vec<String> {
    let mut cmd = vec![
        binary.to_string(),
        "--role=client".to_string(),
        "--inbound=socks5".to_string(),
        "--socks5".to_string(),
        format!("{listen_host}:{listen_port}"),
    ];
    cmd.extend(config.carrier_args());
    if !is_blank(&config.dns_server) {
        cmd.push("--dns".to_string());
        cmd.push(config.dns_server.clone());
    }
    if let Some(k) = key_file {
        cmd.extend(config.encryption_args(k));
    }
    if config.debug {
        cmd.push("--debug".to_string());
    }
    cmd.push("--exit-on-stdin-eof".to_string());
    cmd
}

/// Secrets passed through the environment.
pub fn openflux_env(
    config: &OpenFluxConfig,
    socks_username: &str,
    socks_password: &str,
) -> Vec<(String, String)> {
    vec![
        ("OPENFLUX_MAX_TOKEN".to_string(), config.max_token.clone()),
        ("OPENFLUX_AVITO_COOKIES".to_string(), config.avito_cookies.clone()),
        ("OPENFLUX_SOCKS_USER".to_string(), socks_username.to_string()),
        ("OPENFLUX_SOCKS_PASS".to_string(), socks_password.to_string()),
    ]
}

pub fn openflux_start_log(config: &OpenFluxConfig, listen_host: &str, listen_port: i32) -> String {
    let dns = if is_blank(&config.dns_server) { "system" } else { config.dns_server.as_str() };
    format!(
        "Starting OpenFlux ({}) on {listen_host}:{listen_port}, dns={dns}",
        config.summary()
    )
}

// ---------------------------------------------------------------------------------------------
// Trust Tunnel
// ---------------------------------------------------------------------------------------------

pub const TT_DIR: &str = "trusttunnel";
pub const TT_CONFIG_FILE: &str = "client.toml";
pub const TT_WIZARD_SETTINGS_FILE: &str = "wizard-settings.toml";
pub const TT_LOG_PREFIX: &str = "trusttunnel";
pub const TT_WIZARD_TIMEOUT_MS: u64 = 15_000;
pub const TT_WARMUP_TIMEOUT_MS: u64 = 15_000;
pub const TT_WARMUP_ATTEMPT_MS: u64 = 8_000;
pub const TT_WARMUP_RETRY_MS: u64 = 300;
pub const TT_PROBE_CONNECT_TIMEOUT_MS: u64 = 1_000;
pub const TT_STOP_TIMEOUT_MS: u64 = 3_000;
pub const TT_KILL_TIMEOUT_MS: u64 = 1_000;
/// `setup_wizard.exe` has no manifest; without this Windows' installer heuristic asks for UAC.
pub const TT_WIZARD_ENV: (&str, &str) = ("__COMPAT_LAYER", "RunAsInvoker");

pub const TT_ERR_WIZARD_TIMEOUT: &str =
    "Trust Tunnel: setup_wizard timed out decoding the tt:// link";
pub const TT_ERR_NO_ENDPOINT: &str = "Trust Tunnel: setup_wizard produced no [endpoint] table";

/// SOCKS5 greeting (no auth) and CONNECT 1.1.1.1:443 used by the warm-up probe.
pub const TT_PROBE_GREETING: [u8; 3] = [5, 1, 0];
pub const TT_PROBE_CONNECT: [u8; 10] = [5, 1, 0, 1, 1, 1, 1, 1, 0x01, 0xBB];

pub fn tt_client_command(binary: &str, config_file: &Path) -> Vec<String> {
    vec![
        binary.to_string(),
        "--config".to_string(),
        config_file.to_string_lossy().into_owned(),
    ]
}

/// `setup_wizard` in non-interactive mode (never prompts there); env [`TT_WIZARD_ENV`].
pub fn tt_wizard_command(wizard: &str, deep_link: &str, settings_file: &Path) -> Vec<String> {
    vec![
        wizard.to_string(),
        "--mode".to_string(),
        "non-interactive".to_string(),
        "--deeplink".to_string(),
        deep_link.to_string(),
        "--settings".to_string(),
        settings_file.to_string_lossy().into_owned(),
    ]
}

pub fn tt_start_log(socks_port: i32) -> String {
    format!("Starting Trust Tunnel SOCKS on 127.0.0.1:{socks_port}")
}

/// `client.toml`: SOCKS-only (our TUN owns routing), kill switch off.
pub fn tt_compose_config(endpoint_toml: &str, socks_port: i32) -> String {
    let mut s = String::new();
    s.push_str("loglevel = \"info\"\n");
    s.push_str("vpn_mode = \"general\"\n");
    s.push_str("killswitch_enabled = false\n");
    s.push('\n');
    s.push_str(endpoint_toml.trim());
    s.push('\n');
    s.push('\n');
    s.push_str("[listener.socks]\n");
    s.push_str(&format!("address = \"127.0.0.1:{socks_port}\"\n"));
    s
}

/// The `[endpoint]` table (header included) of a full settings document: everything up to the next
/// top-level table header.
pub fn extract_endpoint_table(document: &str) -> Option<String> {
    let lines = kotlin_lines(document);
    let start = lines.iter().position(|l| l.trim() == "[endpoint]")?;
    let rest = &lines[start + 1..];
    let end = rest.iter().position(|l| l.trim_start().starts_with('[')).unwrap_or(rest.len());
    let mut out: Vec<&str> = vec!["[endpoint]"];
    out.extend_from_slice(&rest[..end]);
    Some(out.join("\n").trim_end().to_string())
}

/// Outcome of a finished (not timed out) `setup_wizard` run -> the `[endpoint]` table or the
/// user-facing error. [`settings`] is the settings file text, `None` if it was not written.
pub fn tt_wizard_result(exit_code: i32, settings: Option<&str>, output: &str) -> Result<String, String> {
    let doc = match settings {
        Some(d) if exit_code == 0 => d,
        _ => {
            return Err(format!(
                "Trust Tunnel: invalid tt:// link (setup_wizard exited {exit_code}): {}",
                take_last_chars(output.trim(), 400)
            ))
        }
    };
    extract_endpoint_table(doc).ok_or_else(|| TT_ERR_NO_ENDPOINT.to_string())
}

/// `1.2.3.4:443` / `[2001:db8::1]:443` / `vpn.example.com:443` -> the host part.
pub fn strip_port(value: &str) -> String {
    let t = value.trim();
    if let Some(after) = t.strip_prefix('[') {
        return after.split(']').next().unwrap_or("").to_string();
    }
    // A bare IPv6 literal has several colons and no port; only strip when there is exactly one.
    if t.matches(':').count() != 1 {
        return t.to_string();
    }
    t[..t.rfind(':').unwrap_or(t.len())].to_string()
}

/// Hosts the client dials directly (they must bypass the TUN), from the `addresses = [...]` line.
pub fn endpoint_hosts_from_table(table: &str) -> Vec<String> {
    let line = match kotlin_lines(table)
        .into_iter()
        .map(str::trim)
        .find(|l| l.starts_with("addresses") && l.contains('='))
    {
        Some(l) => l,
        None => return Vec::new(),
    };
    let value = &line[line.find('=').map_or(0, |i| i + 1)..];
    let re = match regex_lite::Regex::new(r#""([^"]+)""#) {
        Ok(re) => re,
        Err(_) => return Vec::new(),
    };
    let mut out: Vec<String> = Vec::new();
    for c in re.captures_iter(value) {
        let host = strip_port(&c[1]);
        if !is_blank(&host) && !out.contains(&host) {
            out.push(host);
        }
    }
    out
}

/// Decoded `tt://` links (deep links are immutable, so the link is the cache key). The platform
/// `decode` runs `setup_wizard` and maps its outcome with [`tt_wizard_result`] (or returns
/// [`TT_ERR_WIZARD_TIMEOUT`]); failures are not cached.
#[derive(Debug, Clone, Default)]
pub struct TrustTunnelLinks {
    cache: HashMap<String, String>,
}

impl TrustTunnelLinks {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn endpoint_table(
        &mut self,
        deep_link: &str,
        decode: &mut dyn FnMut(&str) -> Result<String, String>,
    ) -> Result<String, String> {
        if let Some(t) = self.cache.get(deep_link) {
            return Ok(t.clone());
        }
        let table = decode(deep_link)?;
        self.cache.insert(deep_link.to_string(), table.clone());
        Ok(table)
    }

    /// Never fails: a bad link gives no hosts and surfaces later, at connect time.
    pub fn endpoint_hosts(
        &mut self,
        deep_link: &str,
        decode: &mut dyn FnMut(&str) -> Result<String, String>,
    ) -> Vec<String> {
        match self.endpoint_table(deep_link, decode) {
            Ok(t) => endpoint_hosts_from_table(&t),
            Err(_) => Vec::new(),
        }
    }
}

fn socks_reply_ok(reply: &[u8]) -> bool {
    reply.len() >= 2 && reply[1] == 0
}

/// Up to `n` bytes (fewer on EOF); `None` on a read error / timeout.
fn read_up_to(stream: &mut TcpStream, n: usize) -> Option<Vec<u8>> {
    let mut buf = vec![0u8; n];
    let mut got = 0;
    while got < n {
        match stream.read(&mut buf[got..]) {
            Ok(0) => break,
            Ok(k) => got += k,
            Err(_) => return None,
        }
    }
    buf.truncate(got);
    Some(buf)
}

/// SOCKS5 CONNECT to 1.1.1.1:443 through the local listener; true on a success reply.
pub fn tt_probe_connect(socks_port: u16) -> bool {
    let addr = SocketAddr::from(([127, 0, 0, 1], socks_port));
    let mut s = match TcpStream::connect_timeout(
        &addr,
        Duration::from_millis(TT_PROBE_CONNECT_TIMEOUT_MS),
    ) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let to = Some(Duration::from_millis(TT_WARMUP_ATTEMPT_MS));
    let _ = s.set_read_timeout(to);
    let _ = s.set_write_timeout(to);
    if s.write_all(&TT_PROBE_GREETING).and_then(|_| s.flush()).is_err() {
        return false;
    }
    match read_up_to(&mut s, 2) {
        Some(r) if socks_reply_ok(&r) => {}
        _ => return false,
    }
    if s.write_all(&TT_PROBE_CONNECT).and_then(|_| s.flush()).is_err() {
        return false;
    }
    matches!(read_up_to(&mut s, 10), Some(r) if socks_reply_ok(&r))
}

/// Waits until the client has opened its tunnel session (the first CONNECT stalls ~6 s), so the
/// stall isn't paid by the first DNS lookups. Best-effort: false just means we gave up.
pub fn tt_await_tunnel_ready(socks_port: u16, timeout_ms: u64, is_running: &dyn Fn() -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    while is_running() && Instant::now() < deadline {
        if tt_probe_connect(socks_port) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(TT_WARMUP_RETRY_MS));
    }
    false
}
