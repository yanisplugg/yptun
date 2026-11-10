//! Pure parts of the desktop Linux TUN bridge (port of `LinuxTunController` and
//! `LinuxPrivilege`). The platform writes the files, spawns hev-socks5-tunnel under
//! pkexec/sudo and polls `ip`; this module builds every file, command and message and keeps
//! the cleanup bookkeeping.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use regex_lite::Regex;

use crate::desktop_net::{
    LINUX_IPV6_LAN_RULE_PREF, LINUX_MAPDNS_NETMASK, LINUX_MAPDNS_NETWORK,
    LINUX_ROOT_BYPASS_RULE_PREF, LINUX_ROUTE_TABLE, LINUX_TUN_NAME, LINUX_TUN_RULE_PREF,
    LINUX_UPSTREAM_BYPASS_RULE_PREF, MAPDNS_ADDRESS, TUN_IPV4_ADDRESS, TUN_MTU,
};
use crate::pac::LOCAL_SOCKS_HOST;
use crate::util::is_blank;

pub const CONFIG_FILE: &str = "linux-tun.yml";
pub const UP_SCRIPT: &str = "linux-tun-up.sh";
pub const DOWN_SCRIPT: &str = "linux-tun-down.sh";
pub const RUN_SCRIPT: &str = "linux-tun-run.sh";
pub const CLEAR_SCRIPT: &str = "linux-tun-clear.sh";
pub const TUN_READY_TIMEOUT_MS: u64 = 10_000;
/// The wait includes the pkexec password dialog.
pub const AUTH_READY_TIMEOUT_MS: u64 = 120_000;
pub const TUN_READY_POLL_MS: u64 = 100;
pub const ROUTE_CLEANUP_TIMEOUT_MS: u64 = 2_000;
pub const RELEASE_TIMEOUT_MS: u64 = 5_000;
pub const PRIVILEGE_ENV: &str = "OLCBOX_LINUX_PRIVILEGE";

const RESET_RULES: &str = r#"ip rule del uidrange 0-0 lookup main pref @ROOT@ 2>/dev/null || true
while ip rule del pref @UPSTREAM@ 2>/dev/null; do :; done
ip rule del lookup @TABLE@ pref @RULE@ 2>/dev/null || true
ip route flush table @TABLE@ 2>/dev/null || true"#;

const IPV6_CLEANUP: &str = r#"ip -6 rule del uidrange 0-0 lookup main pref @ROOT@ 2>/dev/null || true
ip -6 rule del lookup main suppress_prefixlength 0 pref @LAN@ 2>/dev/null || true
ip -6 rule del lookup @TABLE@ pref @RULE@ 2>/dev/null || true
ip -6 route flush table @TABLE@ 2>/dev/null || true"#;

const UP_PREPARE: &str = r#"sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
sysctl -w net.ipv4.conf.@TUN@.rp_filter=0 >/dev/null 2>&1 || true
ip link set @TUN@ up
ip rule add uidrange 0-0 lookup main pref @ROOT@"#;

const UP_ROUTES: &str = r#"ip route add default dev @TUN@ table @TABLE@
ip rule add lookup @TABLE@ pref @RULE@
# The TUN is IPv4-only: without this every AAAA answer went AROUND the tunnel. Make the
# IPv6 default unreachable instead (apps fall back to IPv4 at once), the way Android
# blocks a family the VPN does not route; LAN/link-local keep their own routes (pref
# @LAN@), root keeps IPv6 like it keeps IPv4 (olcRTC's own ICE). Best-effort:
# a kernel booted with IPv6 disabled has nothing to leak.
ip -6 rule add uidrange 0-0 lookup main pref @ROOT@ 2>/dev/null || true
ip -6 rule add lookup main suppress_prefixlength 0 pref @LAN@ 2>/dev/null || true
ip -6 route add unreachable default table @TABLE@ 2>/dev/null || true
ip -6 rule add lookup @TABLE@ pref @RULE@ 2>/dev/null || true
if command -v resolvectl >/dev/null 2>&1; then
  resolvectl dns @TUN@ @DNS@ >/dev/null 2>&1 || true
  resolvectl domain @TUN@ '~.' >/dev/null 2>&1 || true
  resolvectl default-route @TUN@ yes >/dev/null 2>&1 || true
elif command -v resolvconf >/dev/null 2>&1; then
  printf "nameserver %s\n" "@DNS@" | resolvconf -a "@TUN@" 2>/dev/null || true
fi"#;

const DOWN_DNS: &str = r#"if command -v resolvectl >/dev/null 2>&1; then
  resolvectl revert @TUN@ >/dev/null 2>&1 || true
elif command -v resolvconf >/dev/null 2>&1; then
  resolvconf -d "@TUN@" 2>/dev/null || true
fi"#;

fn fill(template: &str) -> String {
    template
        .replace("@TUN@", LINUX_TUN_NAME)
        .replace("@TABLE@", LINUX_ROUTE_TABLE)
        .replace("@ROOT@", LINUX_ROOT_BYPASS_RULE_PREF)
        .replace("@UPSTREAM@", LINUX_UPSTREAM_BYPASS_RULE_PREF)
        .replace("@RULE@", LINUX_TUN_RULE_PREF)
        .replace("@LAN@", LINUX_IPV6_LAN_RULE_PREF)
        .replace("@DNS@", MAPDNS_ADDRESS)
}

/// POSIX single-quoted: the only escape inside is closing the quote, a literal ', reopening.
pub fn shell_quoted(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

/// YAML single-quoted scalar: the only escape inside is a doubled quote.
pub fn yaml_quoted(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

fn ipv4_prefix() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$").unwrap())
}

/// Bypass entries land in a script run as root and partly come off the network (ASN lists),
/// so anything that is not strictly a dotted IPv4 (optionally /len) is dropped.
pub fn is_bypass_prefix(value: &str) -> bool {
    ipv4_prefix().is_match(value)
}

/// hev-socks5-tunnel yaml. Carries the core's SOCKS credentials when the user set them,
/// otherwise the core rejects every connection and TUN mode looks dead.
pub fn config_content(
    socks_port: u16,
    post_up_script: Option<&str>,
    pre_down_script: Option<&str>,
    socks_username: &str,
    socks_password: &str,
    udp_over_tcp: bool,
) -> String {
    let mut l: Vec<String> = Vec::new();
    l.push("tunnel:".to_string());
    l.push(format!("  name: {}", LINUX_TUN_NAME));
    l.push(format!("  mtu: {}", TUN_MTU));
    l.push("  multi-queue: false".to_string());
    l.push(format!("  ipv4: {}", TUN_IPV4_ADDRESS));
    if let Some(s) = post_up_script.filter(|s| !is_blank(s)) {
        l.push(format!("  post-up-script: {}", s));
    }
    if let Some(s) = pre_down_script.filter(|s| !is_blank(s)) {
        l.push(format!("  pre-down-script: {}", s));
    }
    l.push(String::new());
    l.push("socks5:".to_string());
    l.push(format!("  address: {}", LOCAL_SOCKS_HOST));
    l.push(format!("  port: {}", socks_port));
    l.push(format!("  udp: '{}'", if udp_over_tcp { "tcp" } else { "udp" }));
    l.push("  pipeline: false".to_string());
    if !is_blank(socks_username) {
        l.push(format!("  username: {}", yaml_quoted(socks_username)));
        l.push(format!("  password: {}", yaml_quoted(socks_password)));
    }
    l.push(String::new());
    l.push("mapdns:".to_string());
    l.push(format!("  address: {}", MAPDNS_ADDRESS));
    l.push("  port: 53".to_string());
    l.push(format!("  network: {}", LINUX_MAPDNS_NETWORK));
    l.push(format!("  netmask: {}", LINUX_MAPDNS_NETMASK));
    l.push("  cache-size: 10000".to_string());
    l.push(String::new());
    l.push("misc:".to_string());
    l.push("  task-stack-size: 24576".to_string());
    l.push("  tcp-buffer-size: 4096".to_string());
    l.push("  max-session-count: 1200".to_string());
    l.push("  connect-timeout: 10000".to_string());
    l.push("  tcp-read-write-timeout: 300000".to_string());
    l.push("  udp-read-write-timeout: 60000".to_string());
    l.push("  log-file: stderr".to_string());
    l.push("  log-level: warn".to_string());
    l.join("\n").trim_end().to_string()
}

/// Root wrapper around hev: hev in the background, block on stdin until YPtun closes it (on
/// purpose or by dying), then take hev and its routes down.
pub fn run_script_content(hev_command: &[String], down_script: &str) -> String {
    let cmd: Vec<String> = hev_command.iter().map(|s| shell_quoted(s)).collect();
    let mut s = String::new();
    s.push_str("#!/bin/sh\n");
    s.push_str(&cmd.join(" "));
    s.push_str(" &\n");
    s.push_str("HEV=$!\n");
    s.push_str("cat >/dev/null\n");
    s.push_str("kill $HEV 2>/dev/null\n");
    s.push_str("wait $HEV 2>/dev/null\n");
    s.push_str(&format!("sh {} >/dev/null 2>&1\n", shell_quoted(down_script)));
    s.push_str("exit 0\n");
    s
}

/// Script that removes a tunnel left behind by a crashed session before the next start.
pub fn clear_script_content(hev_binary: &str, down_script: &str) -> String {
    format!(
        "#!/bin/sh\npkill -f {} >/dev/null 2>&1\nsh {} >/dev/null 2>&1\nexit 0\n",
        shell_quoted(hev_binary),
        shell_quoted(down_script)
    )
}

pub fn up_script_content(bypass_prefixes: &[String]) -> String {
    let mut seen: Vec<&str> = Vec::new();
    for p in bypass_prefixes {
        if !seen.contains(&p.as_str()) {
            seen.push(p.as_str());
        }
    }
    let mut bypass_rules = String::new();
    for p in seen.into_iter().filter(|p| is_bypass_prefix(p)) {
        // One bad entry must not abort the whole `set -e` script and with it the TUN.
        bypass_rules.push_str(&format!(
            "\nip rule add to {} lookup main pref {} 2>/dev/null || true",
            p, LINUX_UPSTREAM_BYPASS_RULE_PREF
        ));
    }
    let mut s = String::from("#!/bin/sh\nset -eu\n");
    s.push_str(&fill(RESET_RULES));
    s.push('\n');
    s.push_str(&fill(IPV6_CLEANUP));
    s.push('\n');
    s.push_str(&fill(UP_PREPARE));
    s.push_str(&bypass_rules);
    s.push('\n');
    s.push_str(&fill(UP_ROUTES));
    s
}

pub fn down_script_content() -> String {
    let mut s = String::from("#!/bin/sh\n");
    s.push_str(&fill(RESET_RULES));
    s.push('\n');
    s.push_str(&fill(IPV6_CLEANUP));
    s.push('\n');
    s.push_str(&fill(DOWN_DNS));
    s
}

/// `[hev, config]`: what `prepareHevLaunch` returns.
pub fn hev_command(hev_binary: &str, config_path: &str) -> Vec<String> {
    vec![hev_binary.to_string(), config_path.to_string()]
}

/// Command the platform runs to start the root wrapper: `sh <run script>` under the privilege.
pub fn run_command(run_script: &str) -> Vec<String> {
    vec!["sh".to_string(), run_script.to_string()]
}

pub fn start_log(hev_file_name: &str, socks_port: u16) -> String {
    format!("Starting Linux TUN bridge: {} -> 127.0.0.1:{}", hev_file_name, socks_port)
}

pub fn connected_log() -> String {
    format!("Linux TUN connected on {}", LINUX_TUN_NAME)
}

pub fn stale_log() -> String {
    format!(
        "A previous {} tunnel is still up \u{2014} removing it before connecting",
        LINUX_TUN_NAME
    )
}

pub fn not_installed_error() -> String {
    format!("{} routes were not installed", LINUX_TUN_NAME)
}

/// pkexec's "dismissed"/"not authorized" exit codes: nothing ran as root.
pub fn is_auth_cancelled(code: i32) -> bool {
    code == 126 || code == 127
}

pub fn launcher_exit_error(code: i32) -> String {
    format!(
        "Linux TUN bridge exited with code {}{}",
        code,
        if is_auth_cancelled(code) { " (authorization cancelled)" } else { "" }
    )
}

/// Whether `ip rule show` output carries the TUN rule hev's up-script installs.
pub fn route_rule_present(ip_rule_output: &str) -> bool {
    let pref_prefix = format!("{}:", LINUX_TUN_RULE_PREF);
    let pref_word = format!("pref {}", LINUX_TUN_RULE_PREF);
    let lookup = format!("lookup {}", LINUX_ROUTE_TABLE);
    ip_rule_output.lines().any(|line| {
        let t = line.trim();
        (t.starts_with(&pref_prefix) || t.contains(&pref_word)) && t.contains(&lookup)
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReadyPoll {
    Ready,
    /// The launcher exited non-zero (pkexec does that when the dialog is dismissed).
    Failed(i32),
    Wait,
}

/// One poll of `awaitReady`. `launcher_exit` is the exit code once the launcher has exited.
pub fn ready_poll(interface_exists: bool, rule_exists: bool, launcher_exit: Option<i32>) -> ReadyPoll {
    if interface_exists && rule_exists {
        return ReadyPoll::Ready;
    }
    match launcher_exit {
        Some(code) if code != 0 => ReadyPoll::Failed(code),
        _ => ReadyPoll::Wait,
    }
}

/// Cleanup bookkeeping of the controller (`routesInstalled` / `hevBinary`).
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct LinuxTunState {
    pub routes_installed: bool,
    pub hev_binary: Option<String>,
}

impl LinuxTunState {
    pub fn new() -> Self {
        Self::default()
    }

    /// `prepareHevLaunch` remembered the binary.
    pub fn prepared(&mut self, hev_binary: &str) {
        self.hev_binary = Some(hev_binary.to_string());
    }

    /// Interface and rule are up; returns the log line.
    pub fn ready(&mut self) -> String {
        self.routes_installed = true;
        connected_log()
    }

    /// The launcher exited non-zero; returns the error message.
    pub fn launcher_failed(&mut self, code: i32) -> String {
        if is_auth_cancelled(code) {
            self.hev_binary = None;
        }
        launcher_exit_error(code)
    }

    /// `release`: `exited` = the wrapper exited within [`RELEASE_TIMEOUT_MS`].
    pub fn released(&mut self, exited: bool) {
        if exited {
            self.hev_binary = None;
            self.routes_installed = false;
        }
    }

    /// Best-effort root cleanup fragments to bundle into one privileged call.
    pub fn privileged_cleanup_commands(&self, down_script: &str) -> Vec<String> {
        let mut commands = Vec::new();
        if let Some(binary) = &self.hev_binary {
            commands.push(format!("pkill -f {} >/dev/null 2>&1 || true", shell_quoted(binary)));
        }
        if self.routes_installed || self.hev_binary.is_some() {
            commands.push(format!("sh {} >/dev/null 2>&1 || true", shell_quoted(down_script)));
        }
        commands
    }

    /// `onStopped`: true when the platform should wait for the routes to disappear.
    pub fn stopped(&mut self) -> bool {
        let was = self.routes_installed;
        self.routes_installed = false;
        was
    }
}

/// `LinuxPrivilege.command`.
pub fn privilege_command(
    command: &[String],
    is_root: bool,
    preferred: Option<&str>,
    pkexec_exists: bool,
) -> Vec<String> {
    if is_root {
        return command.to_vec();
    }
    let preferred = preferred.map(|p| p.to_lowercase());
    let mut out: Vec<String> = match preferred.as_deref() {
        Some("sudo") => vec!["sudo".to_string(), "-n".to_string()],
        Some("pkexec") => vec!["pkexec".to_string()],
        _ if pkexec_exists => vec!["pkexec".to_string()],
        _ => vec!["sudo".to_string(), "-n".to_string()],
    };
    out.extend(command.iter().cloned());
    out
}

/// `id -u` output means root.
pub fn uid_is_root(id_output: &str) -> bool {
    id_output.trim() == "0"
}

/// Candidate paths of `name` on a `PATH` value; the platform checks which exists and is executable.
pub fn path_candidates(path_env: &str, name: &str) -> Vec<PathBuf> {
    path_env
        .split(':')
        .filter(|p| !is_blank(p))
        .map(|p| Path::new(p).join(name))
        .collect()
}
