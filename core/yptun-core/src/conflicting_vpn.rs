//! Other VPN clients running on this machine (desktop). Port of ConflictingVpnDetector.
//!
//! Two clients cannot share the machine: whoever raised a TUN first owns the default route, and
//! in proxy mode they fight over the system proxy. The core owns the known-products table, the
//! tasklist parser and the grouping; enumerating our own descendants and terminating processes
//! stays in the platform shell.

use std::collections::HashSet;

/// Product shown to the user and its executable names (lowercase). Deliberately NOT here:
/// tor.exe — it takes no adapter and conflicts with nothing.
pub const KNOWN: &[(&str, &[&str])] = &[
    ("AmneziaVPN", &["amneziavpn.exe", "amneziavpn-service.exe", "amneziavpn-gui.exe"]),
    ("Hiddify", &["hiddify.exe", "hiddifycli.exe", "hiddifynext.exe"]),
    ("v2rayN", &["v2rayn.exe", "v2rayn-core.exe"]),
    ("Nekoray", &["nekoray.exe", "nekobox.exe", "nekobox_core.exe", "nekoray_core.exe"]),
    ("Clash", &["clash-verge.exe", "clash-win64.exe", "clash for windows.exe", "clash.exe", "clash-meta.exe"]),
    ("Mihomo", &["verge-mihomo.exe", "mihomo.exe"]),
    ("sing-box", &["sing-box.exe"]),
    ("Xray", &["xray.exe", "v2ray.exe", "wv2ray.exe"]),
    ("Happ", &["happ.exe"]),
    ("Outline", &["outline.exe", "outline-client.exe"]),
    ("ProtonVPN", &["protonvpn.exe", "protonvpnservice.exe", "protonvpn.client.exe"]),
    ("NordVPN", &["nordvpn.exe", "nordvpn-service.exe"]),
    ("ExpressVPN", &["expressvpn.exe", "expressvpnd.exe"]),
    ("Surfshark", &["surfshark.exe", "surfsharkservice.exe"]),
    ("Windscribe", &["windscribe.exe", "windscribeservice.exe"]),
    ("WireGuard", &["wireguard.exe", "wgnt.exe"]),
    ("OpenVPN", &["openvpn.exe", "openvpn-gui.exe", "openvpnserv.exe", "openvpnconnect.exe"]),
    ("Cloudflare WARP", &["cloudflare warp.exe", "warp-svc.exe", "warp-cli.exe"]),
    ("Tailscale", &["tailscale-ipn.exe", "tailscaled.exe"]),
    ("ZeroTier", &["zerotier one.exe", "zerotier_desktop_ui.exe"]),
    ("Psiphon", &["psiphon3.exe", "psiphon-tunnel-core.exe"]),
    ("Netch", &["netch.exe"]),
    ("Shadowsocks", &["shadowsocks.exe", "ss-local.exe"]),
    ("Radmin VPN", &["radminvpn.exe"]),
    ("Hola VPN", &["hola.exe", "hola_svc.exe"]),
    ("Lantern", &["lantern.exe"]),
];

/// Windows process table: image names come from the kernel, so services running as SYSTEM are
/// listed even for an unelevated app.
pub const TASKLIST_COMMAND: [&str; 4] = ["tasklist.exe", "/FO", "CSV", "/NH"];

/// Grace period for a GUI client to exit before it is killed.
pub const TERMINATE_GRACE_MS: u64 = 3_000;
pub const TERMINATE_POLL_MS: u64 = 150;
pub const AFTER_KILL_WAIT_MS: u64 = 300;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunningVpnApp {
    pub display_name: String,
    pub pids: Vec<i64>,
}

fn product_exact(exe: &str) -> Option<&'static str> {
    for (product, exes) in KNOWN.iter() {
        if exes.iter().any(|e| *e == exe) {
            return Some(*product);
        }
    }
    None
}

/// Product for an executable name. Linux/macOS binaries carry no ".exe": the same table
/// covers them.
pub fn product_for(exe: &str) -> Option<&'static str> {
    let exe = exe.to_lowercase();
    product_exact(&exe).or_else(|| product_exact(&format!("{}.exe", exe)))
}

/// pid → lowercase image name from "tasklist /FO CSV /NH" output.
pub fn parse_tasklist_csv(output: &str) -> Vec<(i64, String)> {
    output
        .lines()
        .filter_map(|row| {
            // "image name","pid","session","session#","mem usage"
            let fields: Vec<&str> = row.split("\",\"").collect();
            if fields.len() < 2 {
                return None;
            }
            let name = fields[0].strip_prefix('"').unwrap_or(fields[0]).trim().to_lowercase();
            let pid = fields[1].trim().parse::<i64>().ok()?;
            Some((pid, name))
        })
        .collect()
}

/// Lowercase file name of a process command path (fallback when tasklist is unavailable).
pub fn exe_name_from_command(command: &str) -> String {
    let a = command.rsplit('\\').next().unwrap_or(command);
    let b = a.rsplit('/').next().unwrap_or(a);
    b.to_lowercase()
}

/// Every other VPN client in processes, grouped by product in first-seen order. Our own pids
/// (this process and its helpers) are never a conflict.
pub fn detect(processes: &[(i64, String)], ours: &HashSet<i64>) -> Vec<RunningVpnApp> {
    let mut found: Vec<RunningVpnApp> = Vec::new();
    for (pid, exe) in processes {
        if ours.contains(pid) {
            continue;
        }
        let product = match product_for(exe) {
            Some(p) => p,
            None => continue,
        };
        match found.iter_mut().find(|a| a.display_name == product) {
            Some(app) => app.pids.push(*pid),
            None => found.push(RunningVpnApp { display_name: product.to_string(), pids: vec![*pid] }),
        }
    }
    found
}

/// The Windows process table via tasklist; empty on failure.
#[cfg(windows)]
pub fn tasklist_executables() -> Vec<(i64, String)> {
    match std::process::Command::new(TASKLIST_COMMAND[0]).args(&TASKLIST_COMMAND[1..]).output() {
        Ok(out) => parse_tasklist_csv(&String::from_utf8_lossy(&out.stdout)),
        Err(_) => Vec::new(),
    }
}
