//! Desktop network helpers shared by the Windows/Linux shells: TUN adapter constants (from
//! WindowsTunController/LinuxTunController), tunnel byte counters (DesktopTrafficStats, Linux
//! sysfs part) and the physical-uplink probe/fingerprint (PhysicalInterface).
//!
//! Windows counters (GetIfEntry2) and interface enumeration stay in the platform shell; it feeds
//! the adapters in as Nic values.

use std::net::{IpAddr, UdpSocket};
use std::path::Path;

use regex_lite::Regex;

pub const WINDOWS_TUN_NAME: &str = "YPtun";
pub const LINUX_TUN_NAME: &str = "olcbox0";
pub const TUN_MTU: i32 = 1500;
pub const TUN_IPV4_ADDRESS: &str = "10.0.88.88";
pub const TUN_IPV4_PREFIX_LENGTH: i32 = 24;
pub const MAPDNS_ADDRESS: &str = "1.1.1.1";
pub const LINUX_MAPDNS_NETWORK: &str = "100.64.0.0";
pub const LINUX_MAPDNS_NETMASK: &str = "255.192.0.0";
pub const LINUX_ROUTE_TABLE: &str = "51820";
pub const LINUX_ROOT_BYPASS_RULE_PREF: &str = "10";
pub const LINUX_UPSTREAM_BYPASS_RULE_PREF: &str = "15";
pub const LINUX_IPV6_LAN_RULE_PREF: &str = "19";
pub const LINUX_TUN_RULE_PREF: &str = "20";
pub const ELEVATED_START_ARGUMENT: &str = "--olcbox-start-vpn-after-elevation";

/// sing-box's in-core TUN inbound (desktop per-process split tunneling).
pub const SINGBOX_TUN_IPV4_ADDRESS: &str = "172.19.0.1";

/// Addresses that identify the tunnel adapter. Matching on the address rather than the adapter
/// name survives Windows handing the adapter a different name across reconnects.
pub const TUNNEL_ADDRESSES: [&str; 2] = [TUN_IPV4_ADDRESS, SINGBOX_TUN_IPV4_ADDRESS];

pub const PROBE_HOST: &str = "8.8.8.8";
pub const PROBE_PORT: u16 = 53;

const VIRTUAL_NAME: &str = "(?i)tun|tap|yptun|docker|veth|br-|virbr|vmnet|vmware|vbox|virtual|vethernet|hyper-v|wsl|tailscale|zerotier|isatap|teredo|bluetooth|pseudo";

// ---------------------------------------------------------------------------------------------
// Traffic counters
// ---------------------------------------------------------------------------------------------

/// Cumulative byte count of the tunnel adapter since it came up. rx = the user's download,
/// tx = the user's upload.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Counters {
    pub rx_bytes: i64,
    pub tx_bytes: i64,
}

pub fn is_tunnel_address(addr: &str) -> bool {
    TUNNEL_ADDRESSES.contains(&addr)
}

/// Linux: /sys/class/net/NAME/statistics/{rx,tx}_bytes.
pub fn read_linux_counters(interface_name: &str) -> Option<Counters> {
    read_counters_from(&Path::new("/sys/class/net").join(interface_name).join("statistics"))
}

pub fn read_counters_from(statistics_dir: &Path) -> Option<Counters> {
    let read = |name: &str| -> Option<i64> {
        std::fs::read_to_string(statistics_dir.join(name)).ok()?.trim().parse::<i64>().ok()
    };
    Some(Counters { rx_bytes: read("rx_bytes")?, tx_bytes: read("tx_bytes")? })
}

// ---------------------------------------------------------------------------------------------
// Physical uplink
// ---------------------------------------------------------------------------------------------

/// One OS network adapter as the platform sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Nic {
    pub name: String,
    pub display_name: String,
    pub index: i32,
    pub is_up: bool,
    pub is_loopback: bool,
    pub is_virtual: bool,
    pub addresses: Vec<IpAddr>,
}

fn virtual_name_regex() -> Regex {
    Regex::new(VIRTUAL_NAME).expect("valid regex")
}

/// Tunnel/virtual adapters by name (docker, Hyper-V, WSL, Tailscale, …).
pub fn is_virtual_name(name: &str, display_name: &str) -> bool {
    virtual_name_regex().is_match(&format!("{} {}", name, display_name))
}

/// Our own TUN adapter (a leftover from a crashed run counts too).
pub fn is_tunnel_adapter(name: &str, display_name: &str) -> bool {
    [name, display_name].iter().any(|n| {
        let l = n.to_lowercase();
        [WINDOWS_TUN_NAME, LINUX_TUN_NAME].iter().any(|t| l.contains(&t.to_lowercase()))
    })
}

/// Fingerprint of the real uplinks: up, non-tunnel, non-virtual adapters with an IPv4 address.
/// Empty = offline. Compared between polls to notice a network switch.
pub fn fingerprint(nics: &[Nic]) -> String {
    let re = virtual_name_regex();
    let mut parts: Vec<String> = nics
        .iter()
        .filter(|n| n.is_up && !n.is_loopback && !n.is_virtual)
        .filter(|n| !re.is_match(&format!("{} {}", n.name, n.display_name)))
        .filter_map(|n| {
            let v4: Vec<String> = n
                .addresses
                .iter()
                .filter_map(|a| match a {
                    IpAddr::V4(v) if !v.is_link_local() && !v.is_loopback() => Some(v.to_string()),
                    _ => None,
                })
                .collect();
            if v4.is_empty() {
                None
            } else {
                Some(format!("{}={}", n.name, v4.join(",")))
            }
        })
        .collect();
    parts.sort();
    parts.join("|")
}

/// OS interface index of the default route's adapter, or 0 when it is our TUN / unknown.
pub fn physical_index(nic: &Nic) -> i32 {
    if is_tunnel_adapter(&nic.name, &nic.display_name) || nic.index <= 0 {
        0
    } else {
        nic.index
    }
}

/// Source address the OS picks for the default route. A connected UDP socket sends nothing.
/// Resolve it BEFORE the TUN is raised, or it answers with the tunnel itself.
pub fn probe_local_address() -> Option<IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect((PROBE_HOST, PROBE_PORT)).ok()?;
    let local = socket.local_addr().ok()?.ip();
    if local.is_unspecified() || local.is_loopback() {
        None
    } else {
        Some(local)
    }
}
