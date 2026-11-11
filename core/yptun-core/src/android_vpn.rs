//! Pure Android VPN-service logic.
//!
//! Android still owns `VpnService.Builder`, process/network binding and the native Go/JNI calls.
//! This module owns the decisions and text that do not need the Android framework: connection and
//! split-tunnel modes, the TUN route plan, hev-socks5-tunnel configuration, transparent-proxy rules,
//! VK-TURN stream sizing, RTC health, traffic-stall detection and reconnect backoff. Keeping these
//! pieces here makes the Kotlin service a thin platform adapter and lets every decision run in normal
//! Rust tests without an emulator.

use std::collections::BTreeSet;
use std::net::Ipv4Addr;

use serde::{Deserialize, Serialize};

use crate::home::VpnStatus;

// -------------------------------------------------------------------------------------------------
// Android settings and per-session SOCKS options
// -------------------------------------------------------------------------------------------------

pub const DEFAULT_SOCKS_HOST: &str = "127.0.0.1";
pub const ALL_INTERFACES: &str = "0.0.0.0";
pub const DEFAULT_SOCKS_PORT: i32 = 10_808;
pub const MIN_SOCKS_PORT: i32 = 1_024;
pub const MAX_SOCKS_PORT: i32 = 65_535;
pub const MAX_SOCKS_HOST_LENGTH: usize = 255;
pub const SOCKS_TOKEN_BYTES: usize = 18;
pub const SOCKS_TOKEN_LENGTH: usize = SOCKS_TOKEN_BYTES * 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidConnectionMode {
    #[default]
    Tun,
    Proxy,
    Tproxy,
}

impl AndroidConnectionMode {
    pub fn from_value(value: Option<&str>) -> Self {
        match value {
            Some("proxy") => Self::Proxy,
            Some("tproxy") => Self::Tproxy,
            _ => Self::Tun,
        }
    }

    pub fn value(self) -> &'static str {
        match self {
            Self::Tun => "tun",
            Self::Proxy => "proxy",
            Self::Tproxy => "tproxy",
        }
    }

    pub fn is_tunless(self) -> bool {
        self != Self::Tun
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AndroidSplitTunnelMode {
    #[default]
    AllApps,
    ProxySelected,
    BypassSelected,
}

impl AndroidSplitTunnelMode {
    pub fn from_value(value: Option<&str>) -> Self {
        match value {
            Some("proxy_selected") => Self::ProxySelected,
            Some("bypass_selected") => Self::BypassSelected,
            _ => Self::AllApps,
        }
    }

    pub fn value(self) -> &'static str {
        match self {
            Self::AllApps => "all_apps",
            Self::ProxySelected => "proxy_selected",
            Self::BypassSelected => "bypass_selected",
        }
    }
}

pub fn sanitize_socks_host(host: Option<&str>) -> String {
    let cleaned: String = host
        .unwrap_or_default()
        .chars()
        .filter(|c| *c != '\r' && *c != '\n')
        .collect();
    let cleaned = cleaned.trim();
    if cleaned.is_empty() {
        DEFAULT_SOCKS_HOST.to_string()
    } else {
        cleaned.chars().take(MAX_SOCKS_HOST_LENGTH).collect()
    }
}

pub fn sanitize_socks_port(port: Option<i32>) -> i32 {
    port.filter(|p| (MIN_SOCKS_PORT..=MAX_SOCKS_PORT).contains(p))
        .unwrap_or(DEFAULT_SOCKS_PORT)
}

/// A wildcard listen address cannot be dialled; local probes and tun2socks use loopback instead.
pub fn socks_connect_host(listen_host: &str) -> String {
    let host = sanitize_socks_host(Some(listen_host));
    match host.as_str() {
        "0.0.0.0" | "::" | "[::]" => DEFAULT_SOCKS_HOST.to_string(),
        _ => host,
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidStartOptions {
    pub connection_mode: AndroidConnectionMode,
    pub socks_listen_host: String,
    pub socks_listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub split_tunnel_mode: AndroidSplitTunnelMode,
    #[serde(default)]
    pub split_tunnel_proxy_apps: BTreeSet<String>,
    #[serde(default)]
    pub split_tunnel_bypass_apps: BTreeSet<String>,
}

impl Default for AndroidStartOptions {
    fn default() -> Self {
        Self {
            connection_mode: AndroidConnectionMode::Tun,
            socks_listen_host: DEFAULT_SOCKS_HOST.to_string(),
            socks_listen_port: DEFAULT_SOCKS_PORT,
            socks_username: String::new(),
            socks_password: String::new(),
            split_tunnel_mode: AndroidSplitTunnelMode::AllApps,
            split_tunnel_proxy_apps: BTreeSet::new(),
            split_tunnel_bypass_apps: BTreeSet::new(),
        }
    }
}

fn normalized_packages(packages: BTreeSet<String>) -> BTreeSet<String> {
    packages
        .into_iter()
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_empty())
        .collect()
}

impl AndroidStartOptions {
    pub fn normalized(mut self) -> Self {
        self.socks_listen_host = sanitize_socks_host(Some(&self.socks_listen_host));
        self.socks_listen_port = sanitize_socks_port(Some(self.socks_listen_port));
        self.socks_username = self.socks_username.trim().to_string();
        self.socks_password = self.socks_password.trim().to_string();
        self.split_tunnel_proxy_apps = normalized_packages(self.split_tunnel_proxy_apps);
        self.split_tunnel_bypass_apps = normalized_packages(self.split_tunnel_bypass_apps);
        self
    }
}

/// Per-session TUN credential: 18 CSPRNG bytes as lowercase hex, matching
/// `OlcboxVpnService.randomSocksToken` exactly.
pub fn random_socks_token() -> Result<String, String> {
    let mut bytes = [0u8; SOCKS_TOKEN_BYTES];
    getrandom::getrandom(&mut bytes).map_err(|e| format!("OS random source unavailable: {e}"))?;
    let mut out = String::with_capacity(SOCKS_TOKEN_LENGTH);
    const HEX: &[u8; 16] = b"0123456789abcdef";
    for byte in bytes {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    Ok(out)
}

/// Applies the security-sensitive mode rules from `OlcboxVpnService.applyStartOptions`.
///
/// TUN's internal listener must never be unauthenticated: when either configured credential is
/// blank, both are replaced with independent per-session tokens. Proxy/Tproxy are the user-facing
/// loopback/LAN service, so they bind all interfaces and deliberately use no SOCKS authentication.
pub fn apply_start_options(options: AndroidStartOptions) -> Result<AndroidStartOptions, String> {
    apply_start_options_with(options, random_socks_token)
}

pub fn apply_start_options_with<F>(
    options: AndroidStartOptions,
    mut token: F,
) -> Result<AndroidStartOptions, String>
where
    F: FnMut() -> Result<String, String>,
{
    let mut applied = options.normalized();
    match applied.connection_mode {
        AndroidConnectionMode::Tun => {
            if applied.socks_username.is_empty() || applied.socks_password.is_empty() {
                applied.socks_username = token()?;
                applied.socks_password = token()?;
            }
        }
        AndroidConnectionMode::Proxy | AndroidConnectionMode::Tproxy => {
            applied.socks_listen_host = ALL_INTERFACES.to_string();
            applied.socks_username.clear();
            applied.socks_password.clear();
        }
    }
    Ok(applied)
}

// Helper ports derived from the SOCKS base. Keep these offsets synchronized with every engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidPortPlan {
    pub socks: i32,
    pub chain_olcrtc: i32,
    pub amneziawg: i32,
    pub warp_relay: i32,
    pub tproxy: i32,
    pub http_proxy: i32,
    pub trust_tunnel: i32,
    pub sing_box_front: i32,
}

impl AndroidPortPlan {
    pub fn from_socks(socks: i32) -> Self {
        Self {
            socks,
            chain_olcrtc: socks + 1,
            amneziawg: socks + 2,
            warp_relay: socks + 3,
            // These are intentionally the same in the Kotlin service. Tproxy's transparent socket and
            // the optional HTTP bridge are never supposed to own this TCP port at the same time.
            tproxy: socks + 4,
            http_proxy: socks + 4,
            trust_tunnel: socks + 5,
            sing_box_front: socks + 6,
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Split tunnelling and VpnService.Builder route plan
// -------------------------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SplitTunnelPlan {
    pub allowed_packages: Vec<String>,
    pub disallowed_packages: Vec<String>,
    /// `ProxySelected` must successfully apply at least one allowed app in `VpnService.Builder`.
    pub require_one_allowed: bool,
}

pub fn split_tunnel_plan(
    mode: AndroidSplitTunnelMode,
    own_package: &str,
    proxy_packages: impl IntoIterator<Item = String>,
    bypass_packages: impl IntoIterator<Item = String>,
) -> Result<SplitTunnelPlan, String> {
    let clean = |items: Vec<String>| {
        items
            .into_iter()
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty() && p != own_package)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect::<Vec<_>>()
    };
    match mode {
        AndroidSplitTunnelMode::AllApps => Ok(SplitTunnelPlan {
            allowed_packages: Vec::new(),
            disallowed_packages: vec![own_package.to_string()],
            require_one_allowed: false,
        }),
        AndroidSplitTunnelMode::ProxySelected => {
            let allowed_packages = clean(proxy_packages.into_iter().collect());
            if allowed_packages.is_empty() {
                return Err("Select apps for split tunneling".to_string());
            }
            Ok(SplitTunnelPlan {
                allowed_packages,
                disallowed_packages: Vec::new(),
                require_one_allowed: true,
            })
        }
        AndroidSplitTunnelMode::BypassSelected => {
            let mut disallowed_packages = vec![own_package.to_string()];
            disallowed_packages.extend(clean(bypass_packages.into_iter().collect()));
            Ok(SplitTunnelPlan {
                allowed_packages: Vec::new(),
                disallowed_packages,
                require_one_allowed: false,
            })
        }
    }
}

pub const TUN_MTU: i32 = 1_500;
pub const TUN_IPV4_ADDRESS: &str = "10.0.88.88";
pub const TUN_IPV4_PREFIX: u8 = 24;
pub const TUN_IPV6_ADDRESS: &str = "fdfe:dcba:9876::1";
pub const TUN_IPV6_PREFIX: u8 = 126;
pub const MAPDNS_ADDRESS: &str = "1.1.1.1";
pub const MAPDNS_NETWORK: &str = "100.64.0.0";
pub const MAPDNS_NETMASK: &str = "255.192.0.0";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpPrefix {
    pub address: String,
    pub prefix: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidTunnelPlan {
    pub session_name: String,
    pub blocking: bool,
    pub mtu: i32,
    pub addresses: Vec<IpPrefix>,
    pub dns_servers: Vec<String>,
    pub routes: Vec<IpPrefix>,
}

fn cidr_range(address: Ipv4Addr, prefix: u8) -> (u64, u64) {
    let base = u32::from(address) as u64;
    let size = 1u64 << (32 - prefix);
    (base, base + size - 1)
}

fn append_cidrs(mut current: u64, end: u64, out: &mut Vec<IpPrefix>) {
    while current <= end {
        let mut size = if current == 0 {
            1u64 << 32
        } else {
            current & current.wrapping_neg()
        };
        let remaining = end - current + 1;
        while size > remaining {
            size >>= 1;
        }
        out.push(IpPrefix {
            address: Ipv4Addr::from(current as u32).to_string(),
            prefix: 32 - size.trailing_zeros() as u8,
        });
        current += size;
    }
}

/// Minimal CIDR cover of IPv4 excluding RFC-1918, loopback, link-local, multicast and broadcast.
/// `100.64.0.0/10` is deliberately kept so mapped-DNS addresses still enter the TUN.
pub fn ipv4_routes_excluding_lan() -> Vec<IpPrefix> {
    let mut excluded = [
        cidr_range(Ipv4Addr::new(10, 0, 0, 0), 8),
        cidr_range(Ipv4Addr::new(127, 0, 0, 0), 8),
        cidr_range(Ipv4Addr::new(169, 254, 0, 0), 16),
        cidr_range(Ipv4Addr::new(172, 16, 0, 0), 12),
        cidr_range(Ipv4Addr::new(192, 168, 0, 0), 16),
        cidr_range(Ipv4Addr::new(224, 0, 0, 0), 4),
        cidr_range(Ipv4Addr::new(255, 255, 255, 255), 32),
    ];
    excluded.sort_by_key(|r| r.0);

    let mut routes = Vec::new();
    let mut cursor = 0u64;
    for (start, end) in excluded {
        if start > cursor {
            append_cidrs(cursor, start - 1, &mut routes);
        }
        cursor = cursor.max(end.saturating_add(1));
    }
    if cursor <= u32::MAX as u64 {
        append_cidrs(cursor, u32::MAX as u64, &mut routes);
    }
    routes
}

pub fn android_tunnel_plan(mtu: i32, bypass_lan: bool) -> AndroidTunnelPlan {
    let mut routes = if bypass_lan {
        ipv4_routes_excluding_lan()
    } else {
        vec![IpPrefix {
            address: "0.0.0.0".to_string(),
            prefix: 0,
        }]
    };
    // IPv6 is always captured. IPv4-only transports blackhole it in the bridge rather than leaking.
    routes.push(IpPrefix {
        address: "::".to_string(),
        prefix: 0,
    });
    AndroidTunnelPlan {
        session_name: "YPtun".to_string(),
        blocking: true,
        mtu,
        addresses: vec![
            IpPrefix {
                address: TUN_IPV4_ADDRESS.to_string(),
                prefix: TUN_IPV4_PREFIX,
            },
            IpPrefix {
                address: TUN_IPV6_ADDRESS.to_string(),
                prefix: TUN_IPV6_PREFIX,
            },
        ],
        dns_servers: vec![MAPDNS_ADDRESS.to_string()],
        routes,
    }
}

// -------------------------------------------------------------------------------------------------
// hev-socks5-tunnel, DNS and VK-TURN helpers
// -------------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Tun2SocksEngine {
    Stealth,
    MasterDns,
    OpenFlux,
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tun2SocksOptions {
    pub mtu: i32,
    pub drop_ipv6: bool,
    pub socks_listen_host: String,
    pub socks_listen_port: i32,
    pub socks_username: String,
    pub socks_password: String,
    pub engine: Tun2SocksEngine,
}

fn yaml_single_quoted(value: &str) -> String {
    value.replace('\'', "''")
}

pub fn tun2socks_config(options: &Tun2SocksOptions) -> String {
    let ipv6 = if options.drop_ipv6 {
        "# ipv6 disabled (IPv4 only/preferred)".to_string()
    } else {
        format!("ipv6: '{TUN_IPV6_ADDRESS}'")
    };
    let tcp_udp = if matches!(
        options.engine,
        Tun2SocksEngine::Stealth | Tun2SocksEngine::MasterDns | Tun2SocksEngine::OpenFlux
    ) {
        "tcp"
    } else {
        "udp"
    };
    let connect_timeout = if matches!(
        options.engine,
        Tun2SocksEngine::MasterDns | Tun2SocksEngine::OpenFlux
    ) {
        30_000
    } else {
        10_000
    };
    let host = yaml_single_quoted(&socks_connect_host(&options.socks_listen_host));
    let user = yaml_single_quoted(&options.socks_username);
    let pass = yaml_single_quoted(&options.socks_password);
    format!(
        "tunnel:\n  name: tun0\n  mtu: {}\n  multi-queue: false\n  ipv4: {}\n  {}\n\nsocks5:\n  address: {}\n  port: {}\n  udp: '{}'\n  pipeline: false\n  username: '{}'\n  password: '{}'\n\nmapdns:\n  address: {}\n  port: 53\n  network: {}\n  netmask: {}\n  cache-size: 10000\n\nmisc:\n  task-stack-size: 24576\n  tcp-buffer-size: 4096\n  max-session-count: 1200\n  connect-timeout: {}\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-file: stderr\n  log-level: warn",
        options.mtu,
        TUN_IPV4_ADDRESS,
        ipv6,
        host,
        options.socks_listen_port,
        tcp_udp,
        user,
        pass,
        MAPDNS_ADDRESS,
        MAPDNS_NETWORK,
        MAPDNS_NETMASK,
        connect_timeout,
    )
}

pub const FALLBACK_OLCRTC_DNS_SERVERS: &str = "77.88.8.8:53,8.8.8.8:53,1.1.1.1:53";

pub fn dns_endpoint(address: &str) -> String {
    let address = address.trim();
    if address.contains(':') {
        format!("[{address}]:53")
    } else {
        format!("{address}:53")
    }
}

pub fn olcrtc_dns_servers(upstream_address: Option<&str>) -> String {
    match upstream_address.map(str::trim).filter(|s| !s.is_empty()) {
        Some(address) => format!("{},{FALLBACK_OLCRTC_DNS_SERVERS}", dns_endpoint(address)),
        None => FALLBACK_OLCRTC_DNS_SERVERS.to_string(),
    }
}

pub const VKTURN_STREAMS_BASE: i32 = 10;
pub const VKTURN_STREAMS_PER_EXTRA_CALL: i32 = 3;
pub const VKTURN_STREAMS_AUTO_MAX: i32 = 12;
pub const VKTURN_STREAMS_HARD_MAX: i32 = 64;

pub fn split_vk_links(links: &str) -> Vec<String> {
    links
        .split(|c| matches!(c, '\n' | '\r' | '\t' | ' ' | ','))
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect()
}

pub fn vkturn_stream_count(links: &str, requested_streams: i32) -> i32 {
    let link_count = split_vk_links(links).len().max(1) as i32;
    let auto = (VKTURN_STREAMS_BASE + (link_count - 1) * VKTURN_STREAMS_PER_EXTRA_CALL)
        .min(VKTURN_STREAMS_AUTO_MAX);
    requested_streams.max(auto).min(VKTURN_STREAMS_HARD_MAX)
}

/// Round-robin partitions call links between parallel freeturn servers. When there are fewer links
/// than servers, links wrap so every server still gets one call, matching the Kotlin controller.
pub fn partition_vk_links(links: &str, servers: usize) -> Vec<Vec<String>> {
    if servers == 0 {
        return Vec::new();
    }
    let all = split_vk_links(links);
    (0..servers)
        .map(|server| {
            let own: Vec<String> = all
                .iter()
                .enumerate()
                .filter(|(index, _)| index % servers == server)
                .map(|(_, link)| link.clone())
                .collect();
            if own.is_empty() && !all.is_empty() {
                vec![all[server % all.len()].clone()]
            } else {
                own
            }
        })
        .collect()
}

// -------------------------------------------------------------------------------------------------
// Root transparent-proxy rules
// -------------------------------------------------------------------------------------------------

pub const TPROXY_MARK: &str = "0x7e1";
pub const TPROXY_TABLE: i32 = 2_026;
pub const TPROXY_PREF: i32 = 90;

pub fn tproxy_teardown_lines() -> Vec<String> {
    vec![
        "export PATH=/system/bin:/system/xbin:/vendor/bin:$PATH".to_string(),
        "while iptables -t mangle -D OUTPUT -j YPT_OUT 2>/dev/null; do :; done".to_string(),
        "while iptables -t mangle -D PREROUTING -j YPT_PRE 2>/dev/null; do :; done".to_string(),
        "while ip6tables -D OUTPUT -j YPT_OUT6 2>/dev/null; do :; done".to_string(),
        "iptables -t mangle -F YPT_OUT 2>/dev/null; iptables -t mangle -X YPT_OUT 2>/dev/null".to_string(),
        "iptables -t mangle -F YPT_PRE 2>/dev/null; iptables -t mangle -X YPT_PRE 2>/dev/null".to_string(),
        "ip6tables -F YPT_OUT6 2>/dev/null; ip6tables -X YPT_OUT6 2>/dev/null".to_string(),
        format!("while ip rule del fwmark {TPROXY_MARK} lookup {TPROXY_TABLE} pref {TPROXY_PREF} 2>/dev/null; do :; done"),
        format!("ip route flush table {TPROXY_TABLE} 2>/dev/null"),
    ]
}

pub fn tproxy_apply_script(uid: u32, tproxy_port: i32) -> String {
    let lan = [
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
    ];
    let out = "iptables -t mangle -A YPT_OUT";
    let mut lines = tproxy_teardown_lines();
    lines.extend([
        format!("ip rule add fwmark {TPROXY_MARK} lookup {TPROXY_TABLE} pref {TPROXY_PREF}"),
        format!("ip route add local 0.0.0.0/0 dev lo table {TPROXY_TABLE}"),
        "iptables -t mangle -N YPT_OUT; iptables -t mangle -N YPT_PRE".to_string(),
        format!("{out} -m owner --uid-owner {uid} -j RETURN"),
        format!("{out} -o lo -j RETURN"),
        format!("{out} -m addrtype --dst-type LOCAL -j RETURN"),
        format!("{out} -p udp --dport 53 -j MARK --set-mark {TPROXY_MARK}"),
        format!("{out} -p tcp --dport 53 -j MARK --set-mark {TPROXY_MARK}"),
    ]);
    lines.extend(
        lan.into_iter()
            .map(|cidr| format!("{out} -d {cidr} -j RETURN")),
    );
    lines.extend([
        format!("{out} -p tcp -j MARK --set-mark {TPROXY_MARK}"),
        format!("{out} -p udp -j MARK --set-mark {TPROXY_MARK}"),
        "iptables -t mangle -I OUTPUT -j YPT_OUT".to_string(),
        format!("iptables -t mangle -A YPT_PRE -m mark ! --mark {TPROXY_MARK} -j RETURN"),
        format!("iptables -t mangle -A YPT_PRE -p tcp -j TPROXY --on-port {tproxy_port} --tproxy-mark {TPROXY_MARK}"),
        format!("iptables -t mangle -A YPT_PRE -p udp -j TPROXY --on-port {tproxy_port} --tproxy-mark {TPROXY_MARK}"),
        "iptables -t mangle -I PREROUTING -j YPT_PRE".to_string(),
        "ip6tables -N YPT_OUT6".to_string(),
        format!("ip6tables -A YPT_OUT6 -m owner --uid-owner {uid} -j RETURN"),
        "ip6tables -A YPT_OUT6 -o lo -j RETURN".to_string(),
        "ip6tables -A YPT_OUT6 -d fe80::/10 -j RETURN".to_string(),
        "ip6tables -A YPT_OUT6 -d fc00::/7 -j RETURN".to_string(),
        "ip6tables -A YPT_OUT6 -d ff00::/8 -j RETURN".to_string(),
        "ip6tables -A YPT_OUT6 -j REJECT".to_string(),
        "ip6tables -I OUTPUT -j YPT_OUT6".to_string(),
        format!(
            "if iptables -t mangle -S YPT_PRE | grep -q TPROXY && ip rule | grep -q ' {TPROXY_TABLE}'; then echo YPT_OK; else echo YPT_FAIL; fi"
        ),
    ]);
    lines.join("\n")
}

/// Parses the root command's output. `None` is success; `Some` is the user-facing failure text.
pub fn tproxy_failure(execution_error: Option<&str>, output: &str) -> Option<String> {
    if let Some(error) = execution_error {
        return Some(format!("Root (su) unavailable: {error}"));
    }
    let lines: Vec<&str> = output.lines().collect();
    if lines.last().is_some_and(|line| line.trim() == "YPT_OK") {
        return None;
    }
    let detail = lines
        .into_iter()
        .filter(|line| line.trim() != "YPT_FAIL")
        .rev()
        .take(3)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>()
        .join(" | ");
    Some(if detail.is_empty() {
        "iptables TPROXY rules were not installed (root denied or kernel lacks TPROXY)".to_string()
    } else {
        detail
    })
}

// -------------------------------------------------------------------------------------------------
// RTC health, TUN traffic, speed and reconnect control
// -------------------------------------------------------------------------------------------------

pub const WATCHDOG_STALLED_TX_PACKET_DELTA: i64 = 8;
pub const WATCHDOG_STALLED_SAMPLE_LIMIT: u32 = 3;
pub const RTC_RECOVERY_GRACE_MS: u64 = 2_500;
pub const RTC_FAILURE_WINDOW_MS: u64 = 6_000;
pub const RECONNECT_RETRY_MAX_DELAY_MS: u64 = 30_000;
pub const MAX_RECONNECT_BACKOFF_POWER: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tun2SocksStats {
    pub tx_packets: i64,
    pub tx_bytes: i64,
    pub rx_packets: i64,
    pub rx_bytes: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidSpeedSample {
    pub down_bytes_per_sec: i64,
    pub up_bytes_per_sec: i64,
}

pub fn android_speed_sample(
    previous: Tun2SocksStats,
    current: Tun2SocksStats,
    interval_ms: u64,
) -> AndroidSpeedSample {
    let seconds = (interval_ms as f64 / 1_000.0).max(0.5);
    AndroidSpeedSample {
        down_bytes_per_sec: ((current.rx_bytes - previous.rx_bytes).max(0) as f64 / seconds) as i64,
        up_bytes_per_sec: ((current.tx_bytes - previous.tx_bytes).max(0) as f64 / seconds) as i64,
    }
}

#[derive(Debug, Clone, Default)]
pub struct AndroidSpeedMeter {
    previous: Option<Tun2SocksStats>,
}

impl AndroidSpeedMeter {
    pub fn tick(
        &mut self,
        current: Option<Tun2SocksStats>,
        interval_ms: u64,
    ) -> Option<AndroidSpeedSample> {
        let sample = match (self.previous, current) {
            (Some(previous), Some(now)) => Some(android_speed_sample(previous, now, interval_ms)),
            _ => None,
        };
        self.previous = current;
        sample
    }

    pub fn reset(&mut self) {
        self.previous = None;
    }
}

#[derive(Debug, Clone, Default)]
pub struct TunStallDetector {
    previous: Option<Tun2SocksStats>,
    stalled_samples: u32,
}

impl TunStallDetector {
    pub fn observe(&mut self, stats: Option<Tun2SocksStats>, core_running: bool) -> bool {
        let Some(stats) = stats else { return false };
        let previous = self.previous.replace(stats);
        let Some(previous) = previous else {
            return false;
        };
        let tx_delta = stats.tx_packets - previous.tx_packets;
        let rx_delta = stats.rx_packets - previous.rx_packets;
        if tx_delta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rx_delta <= 0 && core_running {
            self.stalled_samples = self.stalled_samples.saturating_add(1);
        } else if rx_delta > 0 || tx_delta <= 0 {
            self.stalled_samples = 0;
        }
        self.stalled_samples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    pub fn reset(&mut self) {
        self.previous = None;
        self.stalled_samples = 0;
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryRequest {
    pub reason: String,
    pub full_restart: bool,
}

#[derive(Debug, Clone)]
pub struct RtcHealth {
    last_connected_at_ms: u64,
    last_failure_at_ms: u64,
    failure_count: u32,
}

impl RtcHealth {
    pub fn new(now_ms: u64) -> Self {
        Self {
            last_connected_at_ms: now_ms,
            last_failure_at_ms: 0,
            failure_count: 0,
        }
    }

    pub fn reset(&mut self, now_ms: u64) {
        self.last_connected_at_ms = now_ms;
        self.last_failure_at_ms = 0;
        self.failure_count = 0;
    }

    pub fn observe_line(
        &mut self,
        line: &str,
        now_ms: u64,
        status_connected: bool,
        mode: AndroidConnectionMode,
    ) -> Option<RecoveryRequest> {
        let lower = line.to_lowercase();
        if lower.contains("ice connection state changed: connected")
            || lower.contains("peer connection state changed: connected")
            || lower.contains("socks5 server listening")
        {
            self.reset(now_ms);
            return None;
        }

        let (reason, full_restart, threshold) = if lower
            .contains("ice connection state changed: failed")
            || lower.contains("peer connection state changed: failed")
        {
            ("RTC failed", mode == AndroidConnectionMode::Tun, 1)
        } else if lower.contains("ice connection state changed: closed")
            || lower.contains("peer connection state changed: closed")
        {
            ("RTC closed", mode == AndroidConnectionMode::Tun, 2)
        } else if lower.contains("network is unreachable")
            || lower.contains("use of closed network connection")
            || lower.contains("read/write on closed pipe")
        {
            ("RTC network path is closed", false, 3)
        } else {
            return None;
        };

        if !status_connected
            || now_ms.saturating_sub(self.last_connected_at_ms) < RTC_RECOVERY_GRACE_MS
        {
            return None;
        }
        self.failure_count =
            if now_ms.saturating_sub(self.last_failure_at_ms) <= RTC_FAILURE_WINDOW_MS {
                self.failure_count.saturating_add(1)
            } else {
                1
            };
        self.last_failure_at_ms = now_ms;
        (self.failure_count >= threshold).then(|| RecoveryRequest {
            reason: reason.to_string(),
            full_restart,
        })
    }
}

#[derive(Debug, Clone, Default)]
pub struct ReconnectBackoff {
    attempt: u32,
}

impl ReconnectBackoff {
    pub fn attempt(&self) -> u32 {
        self.attempt
    }

    pub fn next_delay_ms(&mut self, base_delay_ms: u64) -> u64 {
        let multiplier = 1u64 << self.attempt.min(MAX_RECONNECT_BACKOFF_POWER);
        self.attempt = self.attempt.saturating_add(1);
        base_delay_ms
            .saturating_mul(multiplier)
            .min(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    pub fn reset(&mut self) {
        self.attempt = 0;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct AndroidRuntimePresence {
    pub vpn_interface: bool,
    pub tun2socks_thread: bool,
    pub tun2socks_alive: bool,
    pub socks_proxy: bool,
    pub core_running: bool,
    pub startup_job_active: bool,
    pub cleanup_job_active: bool,
}

pub fn can_reconnect_transport_in_place(
    mode: AndroidConnectionMode,
    runtime: AndroidRuntimePresence,
) -> bool {
    match mode {
        AndroidConnectionMode::Tun => runtime.vpn_interface && runtime.tun2socks_alive,
        AndroidConnectionMode::Proxy | AndroidConnectionMode::Tproxy => runtime.core_running,
    }
}

pub fn should_restart_for_start_command(
    status: &VpnStatus,
    runtime: AndroidRuntimePresence,
) -> bool {
    matches!(
        status,
        VpnStatus::Connected
            | VpnStatus::Connecting
            | VpnStatus::Reconnecting
            | VpnStatus::Stopping
    ) || runtime.startup_job_active
        || runtime.cleanup_job_active
        || runtime.vpn_interface
        || runtime.tun2socks_thread
        || runtime.socks_proxy
        || runtime.core_running
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AndroidStatusEffects {
    pub connected: bool,
    pub connected_since_ms: u64,
    pub reset_speed: bool,
}

/// Android keeps the session clock through Connecting/Reconnecting/Error. Only an explicit
/// Disconnected/Stopping clears it; Connected stamps it lazily if no persisted value was restored.
pub fn android_status_effects(
    status: &VpnStatus,
    connected_since_ms: u64,
    now_ms: u64,
) -> AndroidStatusEffects {
    let connected = matches!(status, VpnStatus::Connected);
    let terminal = matches!(status, VpnStatus::Disconnected | VpnStatus::Stopping);
    let connected_since_ms = if terminal {
        0
    } else if connected && connected_since_ms == 0 {
        now_ms
    } else {
        connected_since_ms
    };
    AndroidStatusEffects {
        connected,
        connected_since_ms,
        reset_speed: terminal,
    }
}
