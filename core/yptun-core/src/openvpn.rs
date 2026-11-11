//! Self-contained OpenVPN (`.ovpn`) profile → sing-box 1.14 `openvpn-client` endpoint.
//!
//! The imported file is persisted as JSON, so paths relative to the original file cannot keep
//! working. We therefore accept portable profiles with inline certificates/keys/credentials and
//! reject external material instead of importing a profile that will fail only at connect time.

use std::collections::BTreeMap;

use serde_json::{json, Map, Value};

use crate::model::{ProxyProfile, TYPE_OPENVPN};

const INLINE_BLOCKS: [&str; 8] = [
    "ca",
    "cert",
    "key",
    "tls-auth",
    "tls-crypt",
    "tls-crypt-v2",
    "secret",
    "auth-user-pass",
];

#[derive(Debug)]
struct Parsed {
    directives: Vec<Vec<String>>,
    blocks: BTreeMap<String, String>,
}

#[derive(Debug, Clone)]
struct Remote {
    host: String,
    port: i32,
    network: String,
}

pub fn looks_like(text: &str) -> bool {
    let p = split(text);
    let has = |name: &str| {
        p.directives
            .iter()
            .any(|d| d.first().is_some_and(|v| v.eq_ignore_ascii_case(name)))
    };
    has("remote")
        && (has("client")
            || has("tls-client")
            || has("secret")
            || p.blocks.contains_key("ca")
            || p.blocks.contains_key("secret"))
}

pub fn parse(text: &str) -> Option<ProxyProfile> {
    if !looks_like(text) {
        return None;
    }
    let p = split(text);
    let all = |name: &str| -> Vec<&Vec<String>> {
        p.directives
            .iter()
            .filter(|d| d.first().is_some_and(|v| v.eq_ignore_ascii_case(name)))
            .collect()
    };
    let first = |name: &str| -> Option<&Vec<String>> {
        p.directives
            .iter()
            .find(|d| d.first().is_some_and(|v| v.eq_ignore_ascii_case(name)))
    };
    let value = |name: &str| -> Option<&str> {
        first(name)?
            .get(1)
            .map(String::as_str)
            .filter(|v| !v.trim().is_empty())
    };
    let flag = |name: &str| first(name).is_some();

    let default_network = normalize_network(value("proto").unwrap_or(""));
    let remotes: Vec<Remote> = all("remote")
        .into_iter()
        .filter_map(|parts| {
            let host = parts.get(1)?.trim();
            if host.is_empty() {
                return None;
            }
            let explicit_port = parts
                .get(2)
                .and_then(|v| v.parse::<i32>().ok())
                .filter(|v| (1..=65535).contains(v));
            let port = explicit_port.unwrap_or(1194);
            let network = parts
                .get(if explicit_port.is_some() { 3 } else { 2 })
                .map(|v| normalize_network(v))
                .filter(|v| !v.is_empty())
                .unwrap_or_else(|| {
                    if default_network.is_empty() {
                        "udp".into()
                    } else {
                        default_network.clone()
                    }
                });
            Some(Remote {
                host: host.into(),
                port,
                network,
            })
        })
        .collect();
    let remote = remotes.first()?.clone();

    let static_key = p.blocks.get("secret").filter(|v| !v.trim().is_empty());
    let mode = if static_key.is_some() || first("secret").is_some() {
        "static_key"
    } else {
        "tls"
    };
    let ca = p.blocks.get("ca").filter(|v| !v.trim().is_empty());
    let fingerprints: Vec<String> = all("peer-fingerprint")
        .into_iter()
        .filter_map(|d| d.get(1))
        .filter_map(|raw| {
            let mut v = raw.trim().to_lowercase();
            if let Some(rest) = v.strip_prefix("sha256:") {
                v = rest.to_string();
            }
            v.retain(|c| c != ':');
            (v.len() == 64 && v.chars().all(|c| c.is_ascii_hexdigit())).then_some(v)
        })
        .collect();
    if mode == "tls" && ca.is_none() && fingerprints.is_empty() {
        return None;
    }
    // Any referenced file would be lost after text import.
    for name in [
        "ca",
        "cert",
        "key",
        "tls-auth",
        "tls-crypt",
        "tls-crypt-v2",
        "secret",
    ] {
        if first(name).is_some() && p.blocks.get(name).is_none_or(|v| v.trim().is_empty()) {
            return None;
        }
    }
    if p.blocks.contains_key("cert") != p.blocks.contains_key("key") {
        return None;
    }
    if first("auth-user-pass").is_some()
        && p.blocks
            .get("auth-user-pass")
            .is_none_or(|v| v.trim().is_empty())
    {
        return None;
    }
    if mode == "static_key"
        && p.blocks
            .get("auth-user-pass")
            .is_some_and(|v| !v.trim().is_empty())
    {
        return None;
    }
    if mode == "static_key" && first("ifconfig").is_none_or(|v| v.len() < 3) {
        return None;
    }

    let mut endpoint = Map::new();
    endpoint.insert("type".into(), json!(TYPE_OPENVPN));
    endpoint.insert("tag".into(), json!(format!("OpenVPN {}", remote.host)));
    endpoint.insert("mode".into(), json!(mode));
    endpoint.insert(
        "network".into(),
        json!(if default_network.is_empty() {
            remote.network.clone()
        } else {
            default_network
        }),
    );
    if remotes.len() == 1 {
        endpoint.insert("server".into(), json!(remote.host));
        endpoint.insert("server_port".into(), json!(remote.port));
    } else {
        endpoint.insert(
            "servers".into(),
            Value::Array(
                remotes
                    .iter()
                    .map(|r| json!({ "server": r.host, "server_port": r.port, "network": r.network }))
                    .collect(),
            ),
        );
    }
    if flag("remote-random") {
        endpoint.insert("remote_random".into(), json!(true));
    }
    copy_string(&mut endpoint, "topology", value("topology"));
    copy_string(&mut endpoint, "auth_retry", value("auth-retry"));
    copy_u32(&mut endpoint, "mtu", value("tun-mtu"));
    copy_u32(&mut endpoint, "mss_fix", value("mssfix"));
    copy_string(&mut endpoint, "auth", value("auth"));
    if mode == "tls" {
        if let Some(ciphers) = value("data-ciphers") {
            let values: Vec<&str> = ciphers
                .split(':')
                .filter(|v| !v.trim().is_empty())
                .collect();
            if !values.is_empty() {
                endpoint.insert("data_ciphers".into(), json!(values));
            }
        }
        copy_string(
            &mut endpoint,
            "data_ciphers_fallback",
            value("data-ciphers-fallback").or_else(|| value("cipher")),
        );
    } else {
        copy_string(&mut endpoint, "cipher", value("cipher"));
    }
    copy_string(&mut endpoint, "compression", value("compress"));
    copy_string(&mut endpoint, "compression_lzo", value("comp-lzo"));
    copy_string(
        &mut endpoint,
        "allow_compression",
        value("allow-compression"),
    );
    if mode == "tls" && (flag("route-nopull") || flag("route-no-pull")) {
        endpoint.insert("route_no_pull".into(), json!(true));
    }
    if flag("block-ipv6") {
        endpoint.insert("block_ipv6".into(), json!(true));
    }
    if let Some(redirect) = first("redirect-gateway") {
        endpoint.insert("redirect_gateway".into(), json!(true));
        if redirect.len() > 1 {
            endpoint.insert("redirect_gateway_flags".into(), json!(&redirect[1..]));
        }
    }
    copy_duration(&mut endpoint, "ping_interval", value("ping"));
    copy_duration(&mut endpoint, "ping_restart", value("ping-restart"));
    if let Some(keepalive) = first("keepalive") {
        copy_duration(
            &mut endpoint,
            "ping_interval",
            keepalive.get(1).map(String::as_str),
        );
        copy_duration(
            &mut endpoint,
            "ping_restart",
            keepalive.get(2).map(String::as_str),
        );
    }
    if mode == "tls" {
        if let Some(reneg) = value("reneg-sec") {
            if reneg == "0" {
                endpoint.insert("renegotiate_disabled".into(), json!(true));
            } else {
                copy_duration(&mut endpoint, "renegotiate_interval", Some(reneg));
            }
        }
        copy_duration(&mut endpoint, "tls_timeout", value("tls-timeout"));
        copy_duration(&mut endpoint, "handshake_window", value("hand-window"));
    }
    copy_u32(
        &mut endpoint,
        "explicit_exit_notify",
        value("explicit-exit-notify"),
    );
    let pull_filters: Vec<Value> = (if mode == "tls" {
        all("pull-filter")
    } else {
        Vec::new()
    })
    .into_iter()
    .filter_map(|d| {
        let action = d.get(1)?.to_lowercase();
        if !matches!(action.as_str(), "accept" | "ignore" | "reject") {
            return None;
        }
        let text = d.get(2..)?.join(" ");
        (!text.trim().is_empty()).then(|| json!({ "action": action, "text": text }))
    })
    .collect();
    if !pull_filters.is_empty() {
        endpoint.insert("pull_filters".into(), Value::Array(pull_filters));
    }

    if let Some(credentials) = p.blocks.get("auth-user-pass") {
        let values: Vec<&str> = credentials
            .lines()
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .collect();
        copy_string(&mut endpoint, "username", values.first().copied());
        copy_string(&mut endpoint, "password", values.get(1).copied());
    }

    if mode == "static_key" {
        endpoint.insert("static_key".into(), json!([static_key?]));
        if let Some(d) = key_direction(value("key-direction")) {
            endpoint.insert("key_direction".into(), json!(d));
        }
        if let Some(ifconfig) = first("ifconfig") {
            let local = ifconfig.get(1)?;
            let address = if local.contains('/') {
                local.clone()
            } else if local.contains(':') {
                format!("{local}/128")
            } else {
                format!("{local}/32")
            };
            endpoint.insert("address".into(), json!([address]));
            if let Some(peer) = ifconfig.get(2) {
                endpoint.insert(
                    if peer.contains(':') {
                        "peer_address_ipv6".into()
                    } else {
                        "peer_address".into()
                    },
                    json!(peer),
                );
            }
        }
    } else {
        let mut tls = Map::new();
        if let Some(ca) = ca {
            tls.insert("certificate".into(), json!([ca]));
        }
        if let Some(cert) = p.blocks.get("cert") {
            tls.insert("client_certificate".into(), json!([cert]));
        }
        if let Some(key) = p.blocks.get("key") {
            tls.insert("client_key".into(), json!([key]));
        }
        if !fingerprints.is_empty() {
            tls.insert("peer_fingerprint".into(), json!(fingerprints));
        }
        if let Some(verify) = first("verify-x509-name") {
            copy_string(&mut tls, "server_name", verify.get(1).map(String::as_str));
            if let Some(t) = verify.get(2).map(String::as_str).and_then(name_type) {
                tls.insert("server_name_type".into(), json!(t));
            }
        }
        copy_string(&mut tls, "remote_certificate_tls", value("remote-cert-tls"));
        copy_string(&mut tls, "remote_certificate_eku", value("remote-cert-eku"));
        copy_string(&mut tls, "certificate_profile", value("tls-cert-profile"));
        copy_string(&mut tls, "ns_certificate_type", value("ns-cert-type"));
        let ku: Vec<&str> = all("remote-cert-ku")
            .into_iter()
            .flat_map(|d| d.iter().skip(1).map(String::as_str))
            .collect();
        if !ku.is_empty() {
            tls.insert("remote_certificate_ku".into(), json!(ku));
        }
        copy_string(
            &mut tls,
            "version_min",
            value("tls-version-min").map(|v| v.trim_end_matches("-or-highest")),
        );
        copy_string(&mut tls, "version_max", value("tls-version-max"));
        copy_string(&mut tls, "cipher", value("tls-cipher"));
        copy_string(&mut tls, "groups", value("tls-groups"));
        for name in ["tls-auth", "tls-crypt", "tls-crypt-v2"] {
            if let Some(key) = p.blocks.get(name) {
                let mut wrap = Map::new();
                wrap.insert("type".into(), json!(name.replace('-', "_")));
                wrap.insert("key".into(), json!([key]));
                if name == "tls-auth" {
                    if let Some(d) = key_direction(value("key-direction")) {
                        wrap.insert("direction".into(), json!(d));
                    }
                }
                tls.insert("control_wrap".into(), Value::Object(wrap));
                break;
            }
        }
        endpoint.insert("tls".into(), Value::Object(tls));
    }

    let raw = Value::Object(endpoint).to_string();
    Some(ProxyProfile {
        tag: format!("OpenVPN {}", remote.host),
        kind: TYPE_OPENVPN.into(),
        server: remote.host,
        server_port: remote.port,
        raw_outbound: Some(raw),
        ..Default::default()
    })
}

fn split(text: &str) -> Parsed {
    let mut lines = Vec::new();
    let mut blocks = BTreeMap::new();
    let normalized = text.replace("\r\n", "\n");
    let mut name: Option<String> = None;
    let mut body: Vec<&str> = Vec::new();
    for raw in normalized.lines() {
        let line = raw.trim();
        if let Some(current) = name.as_deref() {
            if line.eq_ignore_ascii_case(&format!("</{current}>")) {
                blocks.insert(current.to_string(), body.join("\n").trim().to_string());
                name = None;
                body.clear();
            } else {
                body.push(raw);
            }
            continue;
        }
        let opening = line
            .strip_prefix('<')
            .and_then(|v| v.strip_suffix('>'))
            .map(str::trim)
            .map(str::to_lowercase);
        if let Some(opening) =
            opening.filter(|v| !v.starts_with('/') && INLINE_BLOCKS.contains(&v.as_str()))
        {
            name = Some(opening);
        } else if !line.is_empty() && !line.starts_with('#') && !line.starts_with(';') {
            let tokens = tokenize(line);
            if !tokens.is_empty() {
                lines.push(tokens);
            }
        }
    }
    Parsed {
        directives: lines,
        blocks,
    }
}

fn tokenize(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut escaped = false;
    for ch in line.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
        } else if ch == '\\' && quote != Some('\'') {
            escaped = true;
        } else if quote == Some(ch) {
            quote = None;
        } else if quote.is_none() && matches!(ch, '\'' | '"') {
            quote = Some(ch);
        } else if quote.is_none() && ch.is_whitespace() {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
        } else {
            current.push(ch);
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

fn normalize_network(value: &str) -> String {
    match value.trim().to_lowercase().as_str() {
        "tcp-client" => "tcp".into(),
        "tcp" | "tcp4" | "tcp6" | "udp" | "udp4" | "udp6" => value.trim().to_lowercase(),
        _ => String::new(),
    }
}

fn duration(value: &str) -> String {
    let v = value.trim();
    if v.chars().all(|c| c.is_ascii_digit()) {
        format!("{v}s")
    } else {
        v.into()
    }
}

fn copy_string(map: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    if let Some(v) = value.filter(|v| !v.trim().is_empty()) {
        map.insert(key.into(), json!(v));
    }
}

fn copy_u32(map: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    if let Some(v) = value.and_then(|v| v.parse::<u32>().ok()).filter(|v| *v > 0) {
        map.insert(key.into(), json!(v));
    }
}

fn copy_duration(map: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    if let Some(v) = value.filter(|v| !v.trim().is_empty()) {
        map.insert(key.into(), json!(duration(v)));
    }
}

fn key_direction(value: Option<&str>) -> Option<&'static str> {
    match value?.trim().to_lowercase().as_str() {
        "1" | "client" => Some("client"),
        "0" | "server" => Some("server"),
        _ => None,
    }
}

fn name_type(value: &str) -> Option<&str> {
    match value.to_lowercase().as_str() {
        "name" => Some("name"),
        "name-prefix" => Some("name-prefix"),
        "subject" => Some("subject"),
        _ => None,
    }
}
