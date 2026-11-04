//! Cloudflare WARP helpers (port of `WarpHelper`): the INI the awgproxy engine runs, the endpoint
//! pinned to WARP's anycast IP, and the INI → WireGuard profile conversion.

use serde_json::{json, Map, Value};

use crate::model::ProxyProfile;

pub use crate::singbox::config::WarpRelay;

const DEFAULT_ENDPOINT: &str = "engage.cloudflareclient.com:2408";
const WARP_ENDPOINT_IP: &str = "162.159.192.1";
const OBFUSCATION_KEYS: [&str; 20] =
    ["jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime"];

fn is_ip_literal(host: &str) -> bool {
    if host.contains(':') {
        return true;
    }
    let parts: Vec<&str> = host.split('.').collect();
    parts.len() == 4 && parts.iter().all(|p| (1..=3).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit()))
}

fn split_endpoint(ep: &str) -> (String, i32) {
    match ep.rfind(':') {
        Some(c) if c > 0 => {
            let h = &ep[..c];
            let h = h.strip_prefix('[').unwrap_or(h);
            (h.strip_suffix(']').unwrap_or(h).to_string(), ep[c + 1..].parse().unwrap_or(2408))
        }
        _ => (ep.to_string(), 2408),
    }
}

fn is_endpoint_line(line: &str) -> bool {
    let t = line.trim();
    t.len() >= 8 && t[..8].eq_ignore_ascii_case("endpoint") && t.contains('=')
}

/// `host`/`port` of the INI's `Endpoint =` line (WARP's default when absent).
pub fn endpoint_of(ini: &str) -> (String, i32) {
    let ep = ini
        .lines()
        .find(|l| is_endpoint_line(l))
        .and_then(|l| l.split_once('=').map(|(_, v)| v.trim().to_string()))
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| DEFAULT_ENDPOINT.into());
    split_endpoint(&ep)
}

fn drop_keys(ini: &str, keys: &[&str]) -> String {
    ini.lines()
        .filter(|line| match line.find('=') {
            Some(eq) if eq > 0 => !keys.contains(&line[..eq].trim().to_lowercase().as_str()),
            _ => true,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Plain WireGuard: every AmneziaWG knob and `Reserved` dropped.
pub fn plain_wireguard(ini: &str) -> String {
    let mut keys = OBFUSCATION_KEYS.to_vec();
    keys.push("reserved");
    drop_keys(ini, &keys)
}

/// The INI awgproxy should run: `Reserved` always dropped (Cloudflare discards those handshakes);
/// AmneziaWG keeps its knobs, I1..I5 only when `init_packets` (server side: inside the proxy, no
/// DPI, I1 only delays the handshake); the endpoint pinned to an IP.
pub fn engine_ini(ini: &str, awg: bool, init_packets: bool) -> String {
    let base = if !awg {
        plain_wireguard(ini)
    } else if init_packets {
        drop_keys(ini, &["reserved"])
    } else {
        drop_keys(ini, &["reserved", "i1", "i2", "i3", "i4", "i5"])
    };
    let (host, port) = endpoint_of(ini);
    with_endpoint(&base, &format!("{}:{port}", pinned_host(&host)))
}

/// `host` when it is an IP literal, else WARP's anycast IP (no DNS lookup through our own tunnel).
pub fn pinned_host(host: &str) -> String {
    if is_ip_literal(host) {
        host.into()
    } else {
        WARP_ENDPOINT_IP.into()
    }
}

/// The INI with its `Endpoint =` line pointed at `host_port`.
pub fn with_endpoint(ini: &str, host_port: &str) -> String {
    ini.lines().map(|l| if is_endpoint_line(l) { format!("Endpoint = {host_port}") } else { l.to_string() }).collect::<Vec<_>>().join("\n")
}

/// A WARP WireGuard/AmneziaWG INI → a `wireguard` profile with a sing-box raw outbound.
pub fn parse_warp_ini(ini: &str, tag: &str) -> Option<ProxyProfile> {
    if ini.trim().is_empty() {
        return None;
    }
    let (mut private_key, mut peer, mut endpoint, mut mtu) = (None, None, None, None);
    let mut keepalive: Option<i32> = Some(25);
    let mut addresses: Vec<String> = Vec::new();
    for raw in ini.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') || line.starts_with('[') {
            continue;
        }
        let Some((k, v)) = line.split_once('=').filter(|(k, _)| !k.is_empty()) else { continue };
        let v = v.trim();
        match k.trim().to_lowercase().as_str() {
            "privatekey" => private_key = Some(v.to_string()),
            "publickey" => peer = Some(v.to_string()),
            "address" => addresses.extend(v.split(',').map(str::trim).filter(|a| !a.is_empty()).map(str::to_string)),
            "endpoint" => endpoint = Some(v.to_string()),
            "mtu" => mtu = v.parse::<i32>().ok(),
            "persistentkeepalive" => keepalive = v.parse().ok().or(keepalive),
            _ => {}
        }
    }
    let private_key = private_key.filter(|s| !s.trim().is_empty())?;
    let peer = peer.filter(|s| !s.trim().is_empty())?;
    let (host, port) = split_endpoint(endpoint.as_deref().filter(|e| !e.trim().is_empty()).unwrap_or(DEFAULT_ENDPOINT));
    // Never a DOMAIN endpoint: the core would resolve it through the WireGuard tunnel it is building.
    let host = pinned_host(&host);
    let mut o = Map::new();
    o.insert("type".into(), json!("wireguard"));
    o.insert("server".into(), json!(host));
    o.insert("server_port".into(), json!(port));
    o.insert("local_address".into(), json!(addresses));
    o.insert("private_key".into(), json!(private_key));
    o.insert("peer_public_key".into(), json!(peer));
    if let Some(m) = mtu.filter(|m| *m > 0) {
        o.insert("mtu".into(), json!(m));
    }
    if let Some(k) = keepalive.filter(|k| *k > 0) {
        o.insert("persistent_keepalive".into(), json!(k));
    }
    Some(ProxyProfile {
        tag: tag.into(),
        kind: "wireguard".into(),
        server: host,
        server_port: port,
        raw_outbound: Some(Value::Object(o).to_string()),
        ..Default::default()
    })
}
