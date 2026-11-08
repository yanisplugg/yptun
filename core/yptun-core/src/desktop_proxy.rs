//! Pure parts of DesktopProxyController.kt: the system-proxy switch for proxy mode.
//! Linux (GNOME gsettings / KDE kwriteconfig) and macOS (networksetup) run through a
//! [`ProxyCommandRunner`] the platform provides; Windows produces registry edits and the WinINET
//! per-connection options, which the platform applies (registry API + InternetSetOptionW).
//! Rule everywhere: never back up our own loopback proxy, so restoring never kills the internet.

pub const UNSUPPORTED_ERROR: &str = "System proxy mode is not supported on this platform";

pub fn command_failed_error(command: &[String], exit_code: i32, output: &str) -> String {
    format!("{} failed with code {exit_code}: {output}", command.join(" "))
}

/// Runs external commands (`run` = Kotlin runCommand: `Err` on a non-zero exit).
pub trait ProxyCommandRunner {
    fn executable_exists(&self, name: &str) -> bool;
    fn run(&mut self, command: &[String]) -> Result<String, String>;
}

fn cmd(parts: &[&str]) -> Vec<String> {
    parts.iter().map(|s| s.to_string()).collect()
}

fn is_loopback(text: &str) -> bool {
    text.contains("127.0.0.1") || text.contains("localhost")
}

fn run_all<R: ProxyCommandRunner>(runner: &mut R, commands: &[Vec<String>]) {
    for c in commands {
        let _ = runner.run(c);
    }
}

fn starts_with_ci(text: &str, prefix: &str) -> bool {
    text.get(..prefix.len()).is_some_and(|h| h.eq_ignore_ascii_case(prefix))
}

fn substring_after<'a>(text: &'a str, delimiter: char) -> &'a str {
    match text.find(delimiter) {
        Some(i) => &text[i + delimiter.len_utf8()..],
        None => text,
    }
}

fn lines(text: &str) -> impl Iterator<Item = &str> {
    text.split(['\n', '\r'])
}

// ---------------- Linux ----------------

pub const GNOME_IGNORE_HOSTS: &str = "['localhost', '127.0.0.0/8', '::1']";
pub const KDE_WRITE_BINARIES: [&str; 2] = ["kwriteconfig6", "kwriteconfig5"];
pub const KDE_READ_BINARIES: [&str; 2] = ["kreadconfig6", "kreadconfig5"];
pub const KDE_DEFAULT_WRITE_BINARY: &str = "kwriteconfig5";
pub const KDE_FILE: &str = "kioslaverc";
pub const KDE_GROUP: &str = "Proxy Settings";
pub const KDE_READ_MISSING_ERROR: &str = "kreadconfig5/6 not found";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinuxGnomeProxyState {
    pub mode: String,
    pub http_host: String,
    pub http_port: i32,
    pub https_host: String,
    pub https_port: i32,
    pub ignore_hosts: String,
}

impl LinuxGnomeProxyState {
    pub fn looks_like_ours(&self) -> bool {
        is_loopback(&self.http_host) && (self.mode == "'manual'" || self.mode == "manual")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinuxKdeProxyState {
    pub proxy_type: String,
    pub http_proxy: String,
    pub https_proxy: String,
}

impl LinuxKdeProxyState {
    pub fn looks_like_ours(&self) -> bool {
        is_loopback(&self.http_proxy) && (self.proxy_type == "1" || self.proxy_type == "2")
    }
}

/// `host:port` -> (substringBefore(':'), substringAfter(':').toInt()); `None` if the port is not a number.
pub fn split_proxy_host_port(host_port: &str) -> Option<(String, i32)> {
    let host = host_port.split(':').next().unwrap_or(host_port);
    let port = substring_after(host_port, ':').parse::<i32>().ok()?;
    Some((host.to_string(), port))
}

fn gsettings_set(schema: &str, key: &str, value: &str) -> Vec<String> {
    cmd(&["gsettings", "set", schema, key, value])
}

pub fn enable_gnome_proxy_commands(host: &str, port: i32) -> Vec<Vec<String>> {
    let port = port.to_string();
    vec![
        gsettings_set("org.gnome.system.proxy.http", "host", host),
        gsettings_set("org.gnome.system.proxy.http", "port", &port),
        gsettings_set("org.gnome.system.proxy.https", "host", host),
        gsettings_set("org.gnome.system.proxy.https", "port", &port),
        gsettings_set("org.gnome.system.proxy", "ignore-hosts", GNOME_IGNORE_HOSTS),
        gsettings_set("org.gnome.system.proxy", "mode", "manual"),
    ]
}

pub fn disable_gnome_proxy_commands() -> Vec<Vec<String>> {
    vec![gsettings_set("org.gnome.system.proxy", "mode", "none")]
}

pub fn restore_gnome_proxy_commands(state: &LinuxGnomeProxyState) -> Vec<Vec<String>> {
    if state.mode == "'none'" || state.mode == "none" {
        return disable_gnome_proxy_commands();
    }
    vec![
        gsettings_set("org.gnome.system.proxy.http", "host", state.http_host.trim_matches('\'')),
        gsettings_set("org.gnome.system.proxy.http", "port", &state.http_port.to_string()),
        gsettings_set("org.gnome.system.proxy.https", "host", state.https_host.trim_matches('\'')),
        gsettings_set("org.gnome.system.proxy.https", "port", &state.https_port.to_string()),
        gsettings_set("org.gnome.system.proxy", "ignore-hosts", &state.ignore_hosts),
        gsettings_set("org.gnome.system.proxy", "mode", state.mode.trim_matches('\'')),
    ]
}

/// The six `gsettings get` reads, in [`gnome_state_from_outputs`] order.
pub fn gnome_read_commands() -> Vec<Vec<String>> {
    vec![
        cmd(&["gsettings", "get", "org.gnome.system.proxy", "mode"]),
        cmd(&["gsettings", "get", "org.gnome.system.proxy.http", "host"]),
        cmd(&["gsettings", "get", "org.gnome.system.proxy.http", "port"]),
        cmd(&["gsettings", "get", "org.gnome.system.proxy.https", "host"]),
        cmd(&["gsettings", "get", "org.gnome.system.proxy.https", "port"]),
        cmd(&["gsettings", "get", "org.gnome.system.proxy", "ignore-hosts"]),
    ]
}

pub fn gnome_state_from_outputs(outputs: &[String]) -> Option<LinuxGnomeProxyState> {
    let get = |i: usize| outputs.get(i).map(|s| s.trim().to_string());
    Some(LinuxGnomeProxyState {
        mode: get(0)?,
        http_host: get(1)?,
        http_port: get(2)?.parse().unwrap_or(0),
        https_host: get(3)?,
        https_port: get(4)?.parse().unwrap_or(0),
        ignore_hosts: get(5)?,
    })
}

fn kde_write(binary: &str, key: &str, value: &str) -> Vec<String> {
    cmd(&[binary, "--file", KDE_FILE, "--group", KDE_GROUP, "--key", key, value])
}

pub fn enable_kde_proxy_commands(proxy_url: &str, binary: &str) -> Vec<Vec<String>> {
    vec![
        kde_write(binary, "ProxyType", "1"),
        kde_write(binary, "httpProxy", proxy_url),
        kde_write(binary, "httpsProxy", proxy_url),
    ]
}

pub fn disable_kde_proxy_commands(binary: &str) -> Vec<Vec<String>> {
    vec![
        kde_write(binary, "ProxyType", "0"),
        kde_write(binary, "httpProxy", ""),
        kde_write(binary, "httpsProxy", ""),
    ]
}

pub fn restore_kde_proxy_commands(state: &LinuxKdeProxyState, binary: &str) -> Vec<Vec<String>> {
    let proxy_type = if state.proxy_type.trim().is_empty() { "0" } else { state.proxy_type.as_str() };
    vec![
        kde_write(binary, "ProxyType", proxy_type),
        kde_write(binary, "httpProxy", &state.http_proxy),
        kde_write(binary, "httpsProxy", &state.https_proxy),
    ]
}

/// The three kreadconfig reads (ProxyType, httpProxy, httpsProxy).
pub fn kde_read_commands(binary: &str) -> Vec<Vec<String>> {
    ["ProxyType", "httpProxy", "httpsProxy"]
        .iter()
        .map(|key| cmd(&[binary, "--file", KDE_FILE, "--group", KDE_GROUP, "--key", key]))
        .collect()
}

pub fn kde_state_from_outputs(outputs: &[String]) -> Option<LinuxKdeProxyState> {
    let get = |i: usize| outputs.get(i).map(|s| s.trim().to_string());
    Some(LinuxKdeProxyState { proxy_type: get(0)?, http_proxy: get(1)?, https_proxy: get(2)? })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LinuxProxyEnable {
    Applied,
    /// Neither gsettings nor kwriteconfig: the UI shows `proxyManualSetup(host_port)`.
    ManualSetup { host_port: String },
}

#[derive(Debug, Default)]
pub struct LinuxProxyController {
    gnome_backup: Option<LinuxGnomeProxyState>,
    kde_backup: Option<LinuxKdeProxyState>,
    active: bool,
}

fn first_existing<R: ProxyCommandRunner>(runner: &R, names: &[&'static str]) -> Option<&'static str> {
    names.iter().copied().find(|n| runner.executable_exists(n))
}

fn run_reads<R: ProxyCommandRunner>(runner: &mut R, commands: &[Vec<String>]) -> Option<Vec<String>> {
    commands.iter().map(|c| runner.run(c).ok()).collect()
}

impl LinuxProxyController {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn enable<R: ProxyCommandRunner>(&mut self, runner: &mut R, host_port: &str) -> Result<LinuxProxyEnable, String> {
        let (host, port) = split_proxy_host_port(host_port)
            .ok_or_else(|| format!("For input string: \"{}\"", substring_after(host_port, ':')))?;
        let has_gsettings = runner.executable_exists("gsettings");
        let kde = first_existing(runner, &KDE_WRITE_BINARIES);
        if !has_gsettings && kde.is_none() {
            return Ok(LinuxProxyEnable::ManualSetup { host_port: host_port.to_string() });
        }
        self.active = true;
        if has_gsettings {
            if let Some(current) = Self::read_gnome(runner) {
                if !current.looks_like_ours() {
                    self.gnome_backup = Some(current);
                }
            }
            run_all(runner, &enable_gnome_proxy_commands(&host, port));
        }
        if let Some(binary) = kde {
            if let Some(current) = Self::read_kde(runner) {
                if !current.looks_like_ours() {
                    self.kde_backup = Some(current);
                }
            }
            run_all(runner, &enable_kde_proxy_commands(&format!("http://{host_port}"), binary));
        }
        Ok(LinuxProxyEnable::Applied)
    }

    pub fn restore<R: ProxyCommandRunner>(&mut self, runner: &mut R) {
        if !self.active {
            return;
        }
        self.active = false;
        if runner.executable_exists("gsettings") {
            let commands = match &self.gnome_backup {
                Some(b) => restore_gnome_proxy_commands(b),
                None => disable_gnome_proxy_commands(),
            };
            run_all(runner, &commands);
            self.gnome_backup = None;
        }
        if let Some(binary) = first_existing(runner, &KDE_WRITE_BINARIES) {
            let commands = match &self.kde_backup {
                Some(b) => restore_kde_proxy_commands(b, binary),
                None => disable_kde_proxy_commands(binary),
            };
            run_all(runner, &commands);
            self.kde_backup = None;
        }
    }

    pub fn clear_stale_proxy<R: ProxyCommandRunner>(&mut self, runner: &mut R) {
        if runner.executable_exists("gsettings") {
            if Self::read_gnome(runner).is_some_and(|s| s.looks_like_ours()) {
                run_all(runner, &disable_gnome_proxy_commands());
            }
        }
        if let Some(binary) = first_existing(runner, &KDE_WRITE_BINARIES) {
            if Self::read_kde(runner).is_some_and(|s| s.looks_like_ours()) {
                run_all(runner, &disable_kde_proxy_commands(binary));
            }
        }
    }

    fn read_gnome<R: ProxyCommandRunner>(runner: &mut R) -> Option<LinuxGnomeProxyState> {
        gnome_state_from_outputs(&run_reads(runner, &gnome_read_commands())?)
    }

    fn read_kde<R: ProxyCommandRunner>(runner: &mut R) -> Option<LinuxKdeProxyState> {
        let binary = first_existing(runner, &KDE_READ_BINARIES)?;
        kde_state_from_outputs(&run_reads(runner, &kde_read_commands(binary))?)
    }
}

// ---------------- macOS ----------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacOsAutoProxyState {
    pub service: String,
    pub enabled: bool,
    pub url: Option<String>,
}

pub fn list_network_services_command() -> Vec<String> {
    cmd(&["networksetup", "-listallnetworkservices"])
}

pub fn get_auto_proxy_url_command(service: &str) -> Vec<String> {
    cmd(&["networksetup", "-getautoproxyurl", service])
}

fn set_auto_proxy_state(service: &str, on: bool) -> Vec<String> {
    cmd(&["networksetup", "-setautoproxystate", service, if on { "on" } else { "off" }])
}

fn set_auto_proxy_url(service: &str, url: &str) -> Vec<String> {
    cmd(&["networksetup", "-setautoproxyurl", service, url])
}

pub fn parse_network_services(output: &str) -> Vec<String> {
    lines(output)
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("An asterisk") && !l.starts_with('*'))
        .map(String::from)
        .collect()
}

pub fn parse_auto_proxy_state(service: &str, output: &str) -> MacOsAutoProxyState {
    let enabled = lines(output)
        .find(|l| starts_with_ci(l, "Enabled:"))
        .is_some_and(|l| substring_after(l, ':').trim().eq_ignore_ascii_case("Yes"));
    let url = lines(output)
        .find(|l| starts_with_ci(l, "URL:"))
        .map(|l| substring_after(l, ':').trim())
        .filter(|u| !u.is_empty() && *u != "(null)")
        .map(String::from);
    MacOsAutoProxyState { service: service.to_string(), enabled, url }
}

pub fn mac_enable_commands(services: &[String], pac_url: &str) -> Vec<Vec<String>> {
    services.iter().flat_map(|s| [set_auto_proxy_url(s, pac_url), set_auto_proxy_state(s, true)]).collect()
}

pub fn mac_restore_commands(states: &[MacOsAutoProxyState]) -> Vec<Vec<String>> {
    states
        .iter()
        .flat_map(|s| match s.url.as_deref() {
            Some(url) if s.enabled && !url.trim().is_empty() => {
                vec![set_auto_proxy_url(&s.service, url), set_auto_proxy_state(&s.service, true)]
            }
            _ => vec![set_auto_proxy_state(&s.service, false)],
        })
        .collect()
}

#[derive(Debug, Default)]
pub struct MacOsProxyController {
    backup: Option<Vec<MacOsAutoProxyState>>,
}

impl MacOsProxyController {
    pub fn new() -> Self {
        Self::default()
    }

    fn read_states<R: ProxyCommandRunner>(runner: &mut R, services: &[String]) -> Result<Vec<MacOsAutoProxyState>, String> {
        services.iter().map(|s| Ok(parse_auto_proxy_state(s, &runner.run(&get_auto_proxy_url_command(s))?))).collect()
    }

    pub fn enable<R: ProxyCommandRunner>(&mut self, runner: &mut R, pac_url: &str) -> Result<(), String> {
        let services = parse_network_services(&runner.run(&list_network_services_command())?);
        let captured = Self::read_states(runner, &services)?;
        if !captured.iter().any(|s| s.url.as_deref() == Some(pac_url)) {
            self.backup = Some(captured);
        }
        for c in mac_enable_commands(&services, pac_url) {
            runner.run(&c)?;
        }
        Ok(())
    }

    pub fn restore<R: ProxyCommandRunner>(&mut self, runner: &mut R) {
        let Some(states) = self.backup.take() else { return };
        run_all(runner, &mac_restore_commands(&states));
    }

    pub fn clear_stale_proxy<R: ProxyCommandRunner>(&mut self, runner: &mut R) {
        let services = runner.run(&list_network_services_command()).map(|o| parse_network_services(&o)).unwrap_or_default();
        for service in services {
            let Ok(output) = runner.run(&get_auto_proxy_url_command(&service)) else { continue };
            let state = parse_auto_proxy_state(&service, &output);
            if state.enabled && state.url.as_deref().is_some_and(|u| u.contains("127.0.0.1")) {
                let _ = runner.run(&set_auto_proxy_state(&service, false));
            }
        }
    }
}

// ---------------- Windows ----------------

/// Kept verbatim from the Kotlin side (used with HKEY_CURRENT_USER).
pub const REGISTRY_KEY: &str = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
/// What never goes through the proxy (v2rayN's default bypass list).
pub const PROXY_BYPASS: &str = "<local>;localhost;127.*;10.*;172.16.*;192.168.*";
pub const INTERNET_OPTION_PER_CONNECTION_OPTION: i32 = 75;
pub const INTERNET_OPTION_SETTINGS_CHANGED: i32 = 39;
pub const INTERNET_OPTION_REFRESH: i32 = 37;
pub const OPTION_FLAGS: i32 = 1;
pub const OPTION_PROXY_SERVER: i32 = 2;
pub const OPTION_PROXY_BYPASS: i32 = 3;
pub const PROXY_TYPE_DIRECT: i32 = 1;
pub const PROXY_TYPE_PROXY: i32 = 2;

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WindowsProxyState {
    /// "0x" + hex of the ProxyEnable DWORD.
    pub proxy_enable: Option<String>,
    pub proxy_server: Option<String>,
    pub proxy_override: Option<String>,
    pub auto_config_url: Option<String>,
}

impl WindowsProxyState {
    /// "No proxy configured": restoring to this leaves a clean, online machine.
    pub fn disabled() -> Self {
        Self { proxy_enable: Some("0x0".into()), ..Self::default() }
    }

    pub fn looks_like_ours(&self) -> bool {
        self.proxy_server.as_deref().is_some_and(is_loopback) || self.auto_config_url.as_deref().is_some_and(is_loopback)
    }
}

/// "0x" + Integer.toHexString (two's complement for negatives).
pub fn proxy_enable_text(value: i32) -> String {
    format!("0x{:x}", value as u32)
}

/// Registry value strings are dropped when blank (Kotlin `takeIf { it.isNotBlank() }`).
pub fn registry_string(value: Option<&str>) -> Option<String> {
    value.filter(|v| !v.trim().is_empty()).map(String::from)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RegistryEdit {
    SetString { name: String, value: String },
    SetDword { name: String, value: i32 },
    Delete { name: String },
}

impl RegistryEdit {
    pub fn name(&self) -> &str {
        match self {
            RegistryEdit::SetString { name, .. } | RegistryEdit::SetDword { name, .. } | RegistryEdit::Delete { name } => name,
        }
    }
}

pub fn enable_http_edits(host_port: &str) -> Vec<RegistryEdit> {
    vec![
        RegistryEdit::Delete { name: "AutoConfigURL".into() },
        RegistryEdit::SetString { name: "ProxyServer".into(), value: host_port.into() },
        RegistryEdit::SetString { name: "ProxyOverride".into(), value: PROXY_BYPASS.into() },
        RegistryEdit::SetDword { name: "ProxyEnable".into(), value: 1 },
    ]
}

fn string_edit(name: &str, value: Option<&str>) -> RegistryEdit {
    match value {
        Some(v) => RegistryEdit::SetString { name: name.into(), value: v.into() },
        None => RegistryEdit::Delete { name: name.into() },
    }
}

fn dword_edit(name: &str, value: Option<&str>) -> RegistryEdit {
    let parsed = value.and_then(|v| {
        i32::from_str_radix(v.strip_prefix("0x").unwrap_or(v), 16).ok().or_else(|| v.parse::<i32>().ok())
    });
    match parsed {
        Some(p) => RegistryEdit::SetDword { name: name.into(), value: p },
        None => RegistryEdit::Delete { name: name.into() },
    }
}

pub fn restore_edits(state: &WindowsProxyState) -> Vec<RegistryEdit> {
    vec![
        dword_edit("ProxyEnable", state.proxy_enable.as_deref()),
        string_edit("ProxyServer", state.proxy_server.as_deref()),
        string_edit("ProxyOverride", state.proxy_override.as_deref()),
        string_edit("AutoConfigURL", state.auto_config_url.as_deref()),
    ]
}

/// INTERNET_OPTION_PER_CONNECTION_OPTION for the LAN connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PerConnectionOptions {
    pub proxy_server: Option<String>,
    pub bypass: String,
}

impl PerConnectionOptions {
    pub fn new(proxy_server: Option<&str>, bypass: Option<&str>) -> Self {
        Self {
            proxy_server: proxy_server.map(String::from),
            bypass: bypass.filter(|b| !b.trim().is_empty()).unwrap_or(PROXY_BYPASS).to_string(),
        }
    }

    pub fn flags(&self) -> i32 {
        if self.proxy_server.is_none() { PROXY_TYPE_DIRECT } else { PROXY_TYPE_DIRECT | PROXY_TYPE_PROXY }
    }

    pub fn option_count(&self) -> i32 {
        if self.proxy_server.is_none() { 1 } else { 3 }
    }
}

/// Byte offsets of INTERNET_PER_CONN_OPTION(_LIST) for 64-bit (`wide`) and 32-bit processes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PerConnectionLayout {
    pub option_size: usize,
    pub union_offset: usize,
    pub list_size: usize,
    pub connection_offset: usize,
    pub count_offset: usize,
    pub error_offset: usize,
    pub options_offset: usize,
}

pub fn per_connection_layout(wide: bool) -> PerConnectionLayout {
    let count_offset = if wide { 16 } else { 8 };
    PerConnectionLayout {
        option_size: if wide { 16 } else { 12 },
        union_offset: if wide { 8 } else { 4 },
        list_size: if wide { 32 } else { 20 },
        connection_offset: if wide { 8 } else { 4 },
        count_offset,
        error_offset: count_offset + 4,
        options_offset: if wide { 24 } else { 16 },
    }
}

/// What the platform applies: registry edits (each best-effort), then the per-connection
/// options, then SETTINGS_CHANGED + REFRESH.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WindowsProxyPlan {
    pub edits: Vec<RegistryEdit>,
    pub per_connection: PerConnectionOptions,
}

#[derive(Debug, Default)]
pub struct WindowsProxyController {
    backup: Option<WindowsProxyState>,
    active: bool,
}

impl WindowsProxyController {
    pub fn new() -> Self {
        Self::default()
    }

    /// While active, the platform's exit hook must call [`Self::restore`].
    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn enable(&mut self, current: WindowsProxyState, http_proxy_host_port: &str) -> WindowsProxyPlan {
        if !current.looks_like_ours() {
            self.backup = Some(current);
        } else if self.backup.is_none() {
            self.backup = Some(WindowsProxyState::disabled());
        }
        self.active = true;
        WindowsProxyPlan {
            edits: enable_http_edits(http_proxy_host_port),
            per_connection: PerConnectionOptions::new(Some(http_proxy_host_port), Some(PROXY_BYPASS)),
        }
    }

    pub fn restore(&mut self) -> Option<WindowsProxyPlan> {
        self.active = false;
        let state = self.backup.take()?;
        let server = state.proxy_server.as_deref().filter(|_| state.proxy_enable.as_deref() != Some("0x0"));
        Some(WindowsProxyPlan {
            edits: restore_edits(&state),
            per_connection: PerConnectionOptions::new(server, state.proxy_override.as_deref()),
        })
    }

    pub fn clear_stale_proxy(&self, current: &WindowsProxyState) -> Option<WindowsProxyPlan> {
        if !current.looks_like_ours() {
            return None;
        }
        Some(WindowsProxyPlan {
            edits: restore_edits(&WindowsProxyState::disabled()),
            per_connection: PerConnectionOptions::new(None, None),
        })
    }
}
