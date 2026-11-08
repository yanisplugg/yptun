//! Pure parts of the desktop Windows TUN bridge (port of `WindowsTunController`). The platform
//! checks elevation, spawns tun2socks, polls the adapter and runs the PowerShell scripts from a
//! temp .ps1; this module builds every command, script and message and keeps the route
//! bookkeeping.

use crate::desktop_net::{
    ELEVATED_START_ARGUMENT, MAPDNS_ADDRESS, TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH, TUN_MTU,
    WINDOWS_TUN_NAME,
};
use crate::pac::LOCAL_SOCKS_HOST;
use crate::util::is_blank;

pub const TUN_READY_TIMEOUT_MS: u64 = 10_000;
pub const TUN_READY_POLL_MS: u64 = 100;
pub const PROCESS_STOP_TIMEOUT_MS: u64 = 3_000;
pub const PROCESS_KILL_TIMEOUT_MS: u64 = 1_000;
pub const PS_SCRIPT_PREFIX: &str = "yptun-tun-";
pub const PS_SCRIPT_SUFFIX: &str = ".ps1";

/// PowerShell fallback for the elevation check when `shell32!IsUserAnAdmin` is unavailable.
pub const IS_ADMIN_SCRIPT: &str = r#"$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }"#;

pub fn parse_is_admin(output: &str) -> bool {
    output.trim().eq_ignore_ascii_case("true")
}

/// Percent-encoding the way `java.net.URLEncoder` does it, with space as `%20`.
pub fn url_encoded(value: &str) -> String {
    let mut out = String::new();
    for b in value.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// tun2socks must authenticate against the core's SOCKS inbound, or the core rejects every
/// connection ("no matching auth method") and the tunnel carries nothing.
pub fn tun2socks_command(
    tun2socks_binary: &str,
    socks_port: u16,
    socks_username: &str,
    socks_password: &str,
) -> Vec<String> {
    let credentials = if !is_blank(socks_username) {
        format!("{}:{}@", url_encoded(socks_username), url_encoded(socks_password))
    } else {
        String::new()
    };
    vec![
        tun2socks_binary.to_string(),
        "--device".to_string(),
        WINDOWS_TUN_NAME.to_string(),
        "--proxy".to_string(),
        format!("socks5://{}{}:{}", credentials, LOCAL_SOCKS_HOST, socks_port),
        "--mtu".to_string(),
        TUN_MTU.to_string(),
        "--loglevel".to_string(),
        "warn".to_string(),
    ]
}

/// A bare address becomes a host route; anything already carrying a prefix length is kept.
pub fn to_destination_prefix(entry: &str) -> String {
    let t = entry.trim();
    if t.contains('/') {
        t.to_string()
    } else {
        format!("{}/32", t)
    }
}

/// PowerShell single-quoted literal.
pub fn powershell_literal(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

fn remove_route_lines(prefix: &str) -> String {
    format!(
        "Get-NetRoute -DestinationPrefix {} -ErrorAction SilentlyContinue |\n  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue",
        powershell_literal(prefix)
    )
}

fn capture_routes_removal() -> String {
    [
        "Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |",
        "  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue",
        "Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |",
        "  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue",
    ]
    .join("\n")
}

fn tunnel_address_removal() -> String {
    format!(
        "Get-NetIPAddress -InterfaceIndex $ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |\n  Where-Object {{ $_.IPAddress -eq '{}' }} |\n  Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue",
        TUN_IPV4_ADDRESS
    )
}

/// The whole route setup in ONE PowerShell run: tunnel address, `0.0.0.0/1` + `128.0.0.0/1`
/// capture, DNS, plus a route via the PHYSICAL gateway for every bypass entry (the engines'
/// own upstreams; Windows has no VpnService.protect()).
pub fn install_routes_script(bypass_prefixes: &[String]) -> String {
    let mut s = String::from("$ErrorActionPreference = 'Stop'\n");
    if !bypass_prefixes.is_empty() {
        let mut block: Vec<String> = vec![
            "$default = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |".to_string(),
            format!(
                "  Where-Object {{ $_.InterfaceAlias -ne '{}' -and $_.NextHop -ne '0.0.0.0' }} |",
                WINDOWS_TUN_NAME
            ),
            "  Sort-Object RouteMetric, InterfaceMetric |".to_string(),
            "  Select-Object -First 1".to_string(),
            "if ($null -eq $default) { throw 'No physical default gateway found' }".to_string(),
            "$gateway = $default.NextHop".to_string(),
            "$physIfIndex = $default.InterfaceIndex".to_string(),
        ];
        for entry in bypass_prefixes {
            let prefix = to_destination_prefix(entry);
            block.push(remove_route_lines(&prefix));
            block.push(format!(
                "New-NetRoute -InterfaceIndex $physIfIndex -DestinationPrefix {} -NextHop $gateway -RouteMetric 1 | Out-Null",
                powershell_literal(&prefix)
            ));
        }
        s.push_str(&block.join("\n"));
    }
    s.push_str("\n\n");
    s.push_str(&format!(
        "$adapter = Get-NetAdapter -Name '{}' -ErrorAction Stop\n$ifIndex = $adapter.ifIndex\n\n",
        WINDOWS_TUN_NAME
    ));
    s.push_str(&tunnel_address_removal());
    s.push_str("\n\n");
    s.push_str(&format!(
        "New-NetIPAddress -InterfaceIndex $ifIndex -IPAddress '{}' -PrefixLength {} -AddressFamily IPv4 | Out-Null\n\n",
        TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX_LENGTH
    ));
    s.push_str(&capture_routes_removal());
    s.push_str("\n\n");
    s.push_str("New-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null\n");
    s.push_str("New-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null\n");
    s.push_str(&format!(
        "Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ServerAddresses '{}'",
        MAPDNS_ADDRESS
    ));
    s
}

/// Undoes [`install_routes_script`]: bypass routes, capture routes, DNS, tunnel address.
pub fn remove_routes_script(bypass_prefixes: &[String]) -> String {
    let mut s = String::new();
    if !bypass_prefixes.is_empty() {
        let lines: Vec<String> = bypass_prefixes
            .iter()
            .map(|e| remove_route_lines(&to_destination_prefix(e)))
            .collect();
        s.push_str(&lines.join("\n"));
        s.push('\n');
    }
    s.push_str(&format!(
        "$adapter = Get-NetAdapter -Name '{}' -ErrorAction SilentlyContinue\n",
        WINDOWS_TUN_NAME
    ));
    s.push_str("if ($null -eq $adapter) { exit 0 }\n");
    s.push_str("$ifIndex = $adapter.ifIndex\n");
    s.push_str(&capture_routes_removal());
    s.push('\n');
    s.push_str("Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue\n");
    s.push_str(&tunnel_address_removal());
    s
}

/// How the platform runs a script file (a file, not `-Command`: VK-TURN bypass lists blow past
/// the 32 767-char command line).
pub fn powershell_command(script_path: &str) -> Vec<String> {
    ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script_path]
        .iter()
        .map(|s| s.to_string())
        .collect()
}

pub fn powershell_error(exit_code: i32, output: &str) -> String {
    format!("PowerShell failed with code {}: {}", exit_code, output)
}

/// Current arguments plus the elevation marker (once).
pub fn restart_arguments(current: &[String]) -> Vec<String> {
    let mut out = current.to_vec();
    if !out.iter().any(|a| a == ELEVATED_START_ARGUMENT) {
        out.push(ELEVATED_START_ARGUMENT.to_string());
    }
    out
}

/// Windows command-line quoting of one argument (CommandLineToArgvW rules).
pub fn windows_command_line_argument(value: &str) -> String {
    if value.is_empty() {
        return "\"\"".to_string();
    }
    if !value.chars().any(|c| c.is_whitespace() || c == '"') {
        return value.to_string();
    }
    let mut quoted = String::from("\"");
    let mut pending_backslashes = 0usize;
    for c in value.chars() {
        match c {
            '\\' => pending_backslashes += 1,
            '"' => {
                quoted.push_str(&"\\".repeat(pending_backslashes * 2 + 1));
                quoted.push(c);
                pending_backslashes = 0;
            }
            _ => {
                quoted.push_str(&"\\".repeat(pending_backslashes));
                pending_backslashes = 0;
                quoted.push(c);
            }
        }
    }
    quoted.push_str(&"\\".repeat(pending_backslashes * 2));
    quoted.push('"');
    quoted
}

/// Relaunches the app elevated (`Start-Process -Verb RunAs`).
pub fn restart_as_administrator_script(command: &str, arguments: &[String], working_directory: &str) -> String {
    let joined: Vec<String> = arguments.iter().map(|a| windows_command_line_argument(a)).collect();
    let quoted_arguments = powershell_literal(&joined.join(" "));
    let working_directory_line = if is_blank(working_directory) {
        String::new()
    } else {
        format!("  WorkingDirectory = {}", powershell_literal(working_directory))
    };
    let mut s = String::new();
    s.push_str("$ErrorActionPreference = 'Stop'\n");
    s.push_str("$startArgs = @{\n");
    s.push_str(&format!("  FilePath = {}\n", powershell_literal(command)));
    s.push_str("  Verb = 'RunAs'\n");
    s.push_str(&format!("  ArgumentList = {}\n", quoted_arguments));
    s.push_str(&working_directory_line);
    s.push('\n');
    s.push_str("}\n");
    s.push_str("Start-Process @startArgs | Out-Null");
    s
}

pub const REQUEST_ADMIN_LOG: &str = "Requesting Windows administrator privileges for TUN mode";

pub fn connected_log() -> String {
    format!("Windows TUN connected on {}", WINDOWS_TUN_NAME)
}

pub fn bypass_log(count: usize) -> String {
    format!("Installed {} bypass route(s) for proxy server(s)", count)
}

pub fn partial_cleanup_failed_log(message: &str) -> String {
    format!("Windows TUN partial route cleanup failed: {}", message)
}

pub fn cleanup_failed_log(message: &str) -> String {
    format!("Windows TUN route cleanup failed: {}", message)
}

/// tun2socks died before the adapter came up.
pub fn exited_before_ready_error(output: &str) -> String {
    let mut s = format!("tun2socks exited before {} was ready", WINDOWS_TUN_NAME);
    let t = output.trim();
    if !is_blank(t) {
        s.push_str(": ");
        s.push_str(t);
    }
    s
}

/// The adapter did not appear within [`TUN_READY_TIMEOUT_MS`].
pub fn adapter_missing_error(buffered_output: &str) -> String {
    let mut s = format!("{} adapter was not created", WINDOWS_TUN_NAME);
    let t = buffered_output.trim();
    if !is_blank(t) {
        s.push_str(": ");
        s.push_str(t);
    }
    s
}

/// Route bookkeeping of the controller (`routesInstalled` / `bypassIps`).
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct WindowsTunState {
    pub routes_installed: bool,
    pub bypass_ips: Vec<String>,
}

impl WindowsTunState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adapter is up: remembers the distinct bypass list and returns it for
    /// [`install_routes_script`].
    pub fn begin(&mut self, bypass_server_ips: &[String]) -> Vec<String> {
        let mut distinct: Vec<String> = Vec::new();
        for ip in bypass_server_ips {
            if !distinct.contains(ip) {
                distinct.push(ip.clone());
            }
        }
        self.bypass_ips = distinct.clone();
        distinct
    }

    /// Routes installed; returns the log lines.
    pub fn installed(&mut self) -> Vec<String> {
        let mut logs = Vec::new();
        if !self.bypass_ips.is_empty() {
            logs.push(bypass_log(self.bypass_ips.len()));
        }
        self.routes_installed = true;
        logs.push(connected_log());
        logs
    }

    /// Start failed after `begin`: returns the bypass list for [`remove_routes_script`].
    pub fn failed(&mut self) -> Vec<String> {
        self.routes_installed = false;
        std::mem::take(&mut self.bypass_ips)
    }

    /// `stop`: `Some(bypass list)` when routes must be removed first, `None` otherwise.
    pub fn stop(&mut self) -> Option<Vec<String>> {
        if self.routes_installed || !self.bypass_ips.is_empty() {
            self.routes_installed = false;
            Some(std::mem::take(&mut self.bypass_ips))
        } else {
            None
        }
    }
}
