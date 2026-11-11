//! Proxy share links → [`ProxyProfile`] (port of the app's `ShareLinkParser`, `VlessUriParser`,
//! `AmneziaWgParser` and `TrustTunnelParser`): vless, vmess, trojan, ss, hysteria2/hy2, naive,
//! amneziawg (link and raw INI), Mieru and tt:// (Trust Tunnel).

use std::collections::BTreeMap;

use base64::alphabet;
use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig, STANDARD};
use base64::engine::DecodePaddingMode;
use base64::Engine;

use crate::model::*;
use crate::subscription::{decode_base64_chunk, to_links};
use crate::uri::{after, before, parse_query, percent_decode, split_host_port, split_list};

/// Parses one share link of any supported protocol; None when it is not one or is malformed.
pub fn parse(uri: &str) -> Option<ProxyProfile> {
    let t = uri.trim();
    if starts_ci(t, "vless://") {
        parse_vless(t)
    } else if starts_ci(t, "vmess://") {
        parse_vmess(t)
    } else if starts_ci(t, "trojan://") {
        parse_trojan(t)
    } else if starts_ci(t, "ss://") {
        parse_shadowsocks(t)
    } else if starts_ci(t, "hysteria2://") || starts_ci(t, "hy2://") {
        parse_hysteria2(t)
    } else if starts_ci(t, "naive+") || starts_ci(t, "naive://") {
        parse_naive(t)
    } else if starts_ci(t, "mieru://") || starts_ci(t, "mierus://") {
        parse_mieru(t)
    } else if starts_ci(t, "amneziawg://") {
        // amneziawg://<base64url(wg-quick INI)>#name — the `#name` (the panel's host name) wins
        // over the INI's own comment.
        let rest = &t["amneziawg://".len()..];
        let name = percent_decode(after(rest, '#')).trim().to_string();
        let ini = decode_base64_chunk(before(rest, '#'))?;
        let mut p = parse_amneziawg(&ini)?;
        if !name.is_empty() {
            p.tag = name;
        }
        Some(p)
    } else if looks_like_amneziawg(t) {
        parse_amneziawg(t)
    } else if looks_like_trust_tunnel(t) {
        parse_trust_tunnel(t)
    } else {
        None
    }
}

/// Mieru's opaque standard link or the human-readable simple form.
pub fn parse_mieru(uri: &str) -> Option<ProxyProfile> {
    let t = uri.trim();
    if starts_ci(t, "mieru://") {
        let payload = before(&t["mieru://".len()..], '#');
        if payload.trim().is_empty() {
            return None;
        }
        let name = percent_decode(after(t, '#'));
        return Some(ProxyProfile {
            tag: if name.trim().is_empty() {
                "Mieru".into()
            } else {
                name
            },
            kind: TYPE_MIERU.into(),
            server: "mieru-config".into(),
            server_port: 1,
            mieru_config: t.into(),
            ..Default::default()
        });
    }
    if !starts_ci(t, "mierus://") {
        return None;
    }
    let body = &t["mierus://".len()..];
    let name = percent_decode(after(body, '#'));
    let before_fragment = before(body, '#');
    let query = after(before_fragment, '?');
    let authority = before(before_fragment, '?');
    let at = authority.rfind('@').filter(|&index| index > 0)?;
    let credentials = &authority[..at];
    let colon = credentials.find(':').filter(|&index| index > 0)?;
    let username = percent_decode(&credentials[..colon]);
    let password = percent_decode(&credentials[colon + 1..]);
    let server = percent_decode(&authority[at + 1..])
        .trim()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .to_string();
    if username.trim().is_empty() || password.trim().is_empty() || server.is_empty() {
        return None;
    }
    let params = parse_query(query);
    let server_port = params
        .get("port")
        .and_then(|value| value.split('-').next())
        .and_then(|value| value.parse::<i32>().ok())
        .filter(|port| (1..=65535).contains(port))
        .unwrap_or(1);
    let profile = params.get("profile").cloned().unwrap_or_default();
    Some(ProxyProfile {
        tag: if !name.trim().is_empty() {
            name
        } else if !profile.trim().is_empty() {
            profile
        } else {
            "Mieru".into()
        },
        kind: TYPE_MIERU.into(),
        server,
        server_port,
        username,
        password,
        mieru_config: t.into(),
        ..Default::default()
    })
}

/// Parses a subscription body across every protocol, keeping complete profiles only.
pub fn parse_subscription(body: &str) -> Vec<ProxyProfile> {
    to_links(body)
        .iter()
        .filter_map(|l| parse(l))
        .filter(ProxyProfile::is_complete)
        .collect()
}

/// VLESS only (the app's `VlessUriParser.parseSubscription`).
pub fn parse_vless_subscription(body: &str) -> Vec<ProxyProfile> {
    to_links(body)
        .iter()
        .filter_map(|l| parse_vless(l))
        .filter(ProxyProfile::is_complete)
        .collect()
}

pub(crate) fn starts_ci(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix.as_bytes())
}

fn network_of(value: Option<&str>) -> &'static str {
    match value.map(str::to_lowercase).as_deref() {
        Some("ws") => NETWORK_WS,
        Some("grpc") => NETWORK_GRPC,
        Some("http" | "h2") => NETWORK_HTTP,
        Some("httpupgrade") => NETWORK_HTTPUPGRADE,
        Some("xhttp" | "splithttp") => NETWORK_XHTTP,
        _ => NETWORK_TCP,
    }
}

fn get<'a>(params: &'a BTreeMap<String, String>, key: &str) -> &'a str {
    params.get(key).map(String::as_str).unwrap_or("")
}

/// `params["sni"] ?: params["peer"]` — an empty `sni=` still wins over `peer`.
fn sni_of(params: &BTreeMap<String, String>) -> String {
    params
        .get("sni")
        .or_else(|| params.get("peer"))
        .cloned()
        .unwrap_or_default()
}

fn flag(params: &BTreeMap<String, String>, key: &str) -> bool {
    matches!(params.get(key).map(String::as_str), Some("1" | "true"))
}

// --- vless://<uuid>@<host>:<port>?<query>#<remark> ---
pub fn parse_vless(uri: &str) -> Option<ProxyProfile> {
    let t = uri.trim();
    if !starts_ci(t, "vless://") {
        return None;
    }
    let rest = &t["vless://".len()..];
    let remark = rest
        .find('#')
        .map(|i| percent_decode(&rest[i + 1..]))
        .unwrap_or_default();
    let before_fragment = before(rest, '#');
    let (authority, query) = match before_fragment.find('?') {
        Some(i) => (&before_fragment[..i], &before_fragment[i + 1..]),
        None => (before_fragment, ""),
    };
    let at = authority.rfind('@').filter(|&i| i > 0)?;
    let uuid = percent_decode(&authority[..at]).trim().to_string();
    let (host, port) = split_host_port(&authority[at + 1..])?;
    if uuid.trim().is_empty() || host.trim().is_empty() || !(1..=65535).contains(&port) {
        return None;
    }
    let params = parse_query(query);
    let network = network_of(params.get("type").map(String::as_str));
    let security = match params.get("security").map(|s| s.to_lowercase()).as_deref() {
        Some("tls") => SECURITY_TLS,
        Some("reality") => SECURITY_REALITY,
        _ => SECURITY_NONE,
    };
    let path = if network == NETWORK_GRPC {
        get(&params, "serviceName")
    } else {
        get(&params, "path")
    };
    let p = ProxyProfile {
        tag: remark,
        kind: TYPE_VLESS.into(),
        server: host,
        server_port: port,
        uuid,
        flow: get(&params, "flow").into(),
        network: network.into(),
        security: security.into(),
        sni: sni_of(&params),
        alpn: split_list(get(&params, "alpn")),
        fingerprint: get(&params, "fp").into(),
        allow_insecure: flag(&params, "allowInsecure"),
        reality_public_key: get(&params, "pbk").into(),
        reality_short_id: get(&params, "sid").into(),
        path: path.into(),
        host: get(&params, "host").into(),
        ..Default::default()
    };
    Some(with_xray_params(p, &params))
}

/// Copies the Xray-only link params (3x-ui & co: encryption/mode/extra/fm/pcs/vcn/ech/pqv/spx).
/// `mode`/`extra` are xhttp's only — grpc reuses `mode` for `mode=multi`.
pub fn with_xray_params(p: ProxyProfile, params: &BTreeMap<String, String>) -> ProxyProfile {
    let xhttp = p.network == NETWORK_XHTTP;
    let encryption = get(params, "encryption");
    ProxyProfile {
        vless_encryption: if encryption.eq_ignore_ascii_case("none") {
            String::new()
        } else {
            encryption.into()
        },
        xhttp_mode: if xhttp {
            get(params, "mode").into()
        } else {
            String::new()
        },
        xhttp_extra: if xhttp {
            get(params, "extra").into()
        } else {
            String::new()
        },
        final_mask: get(params, "fm").into(),
        pinned_cert_sha256: get(params, "pcs")
            .split(',')
            .map(pin_hex)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join(","),
        verify_cert_by_name: get(params, "vcn").into(),
        ech_config_list: get(params, "ech").into(),
        reality_mldsa65_verify: get(params, "pqv").into(),
        reality_spider_x: get(params, "spx").into(),
        ..p
    }
}

/// xray-core only takes hex pins, but 3x-ui stores them as base64 too. Hex (bare or colon-separated)
/// → lowercase hex; a 32-byte base64 → hex; anything else unchanged.
pub fn pin_hex(pin: &str) -> String {
    let p = pin.trim().replace(' ', "+"); // a raw '+' in a query is decoded as a space
    let hex = p.replace(':', "");
    if hex.len() == 64 && hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return hex.to_lowercase();
    }
    let mut std = p.replace('-', "+").replace('_', "/");
    std.push_str(&"=".repeat((4 - std.len() % 4) % 4));
    match STANDARD.decode(&std) {
        Ok(bytes) if bytes.len() == 32 => bytes.iter().map(|b| format!("{b:02x}")).collect(),
        _ => p,
    }
}

// --- vmess://<base64 json> (v2rayN format) ---
fn parse_vmess(uri: &str) -> Option<ProxyProfile> {
    let payload = before(&uri["vmess://".len()..], '#');
    let json_text = decode_base64_chunk(payload)?;
    let obj: serde_json::Map<String, serde_json::Value> =
        match serde_json::from_str(&json_text).ok()? {
            serde_json::Value::Object(o) => o,
            _ => return None,
        };
    let str_of = |keys: &[&str]| -> String {
        for k in keys {
            let v = match obj.get(*k) {
                Some(serde_json::Value::String(s)) => s.clone(),
                Some(serde_json::Value::Number(n)) => n.to_string(),
                Some(serde_json::Value::Bool(b)) => b.to_string(),
                _ => continue,
            };
            if !v.trim().is_empty() {
                return v;
            }
        }
        String::new()
    };
    let server = str_of(&["add"]);
    let port: i32 = str_of(&["port"]).parse().ok()?;
    let uuid = str_of(&["id"]);
    if server.trim().is_empty() || uuid.trim().is_empty() {
        return None;
    }
    let network = network_of(Some(&str_of(&["net"])));
    let security = if str_of(&["tls"]).eq_ignore_ascii_case("tls")
        || str_of(&["security"]).eq_ignore_ascii_case("tls")
    {
        SECURITY_TLS
    } else {
        SECURITY_NONE
    };
    let path = if network == NETWORK_GRPC {
        str_of(&["path", "serviceName"])
    } else {
        str_of(&["path"])
    };
    let tag = {
        let ps = str_of(&["ps"]);
        if ps.trim().is_empty() {
            percent_decode(after(uri, '#'))
        } else {
            ps
        }
    };
    let insecure = str_of(&["allowInsecure", "skip-cert-verify", "insecure"]);
    let p = ProxyProfile {
        tag,
        kind: TYPE_VMESS.into(),
        server,
        server_port: port,
        uuid,
        alter_id: str_of(&["aid"]).parse().unwrap_or(0),
        cipher: Some(str_of(&["scy"]))
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| "auto".into()),
        network: network.into(),
        security: security.into(),
        sni: str_of(&["sni", "peer"]),
        alpn: split_list(&str_of(&["alpn"])),
        fingerprint: str_of(&["fp"]),
        path,
        host: str_of(&["host"]),
        // v2rayN-style vmess JSON: free lists mark self-signed / mismatched certs this way.
        allow_insecure: insecure == "1" || insecure.eq_ignore_ascii_case("true"),
        ..Default::default()
    };
    let params: BTreeMap<String, String> = ["mode", "fm", "pcs", "vcn", "ech"]
        .iter()
        .map(|k| (k.to_string(), str_of(&[k])))
        .collect();
    Some(with_xray_params(p, &params))
}

// --- trojan://password@host:port?params#name ---
fn parse_trojan(uri: &str) -> Option<ProxyProfile> {
    let rest = &uri["trojan://".len()..];
    let remark = percent_decode(after(rest, '#'));
    let before_fragment = before(rest, '#');
    let query = after(before_fragment, '?');
    let authority = before(before_fragment, '?');
    let at = authority.rfind('@').filter(|&i| i > 0)?;
    let password = percent_decode(&authority[..at]);
    let (host, port) = split_host_port(&authority[at + 1..])?;
    if password.trim().is_empty() {
        return None;
    }
    let params = parse_query(query);
    let network = network_of(params.get("type").map(String::as_str));
    // Trojan implies TLS unless explicitly "none".
    let security = match params.get("security") {
        Some(s) if s.eq_ignore_ascii_case("none") => SECURITY_NONE,
        Some(s) if s.eq_ignore_ascii_case("reality") => SECURITY_REALITY,
        _ => SECURITY_TLS,
    };
    let path = if network == NETWORK_GRPC {
        get(&params, "serviceName")
    } else {
        get(&params, "path")
    };
    let p = ProxyProfile {
        tag: remark,
        kind: TYPE_TROJAN.into(),
        server: host,
        server_port: port,
        password,
        network: network.into(),
        security: security.into(),
        sni: sni_of(&params),
        alpn: split_list(get(&params, "alpn")),
        fingerprint: get(&params, "fp").into(),
        allow_insecure: flag(&params, "allowInsecure"),
        reality_public_key: get(&params, "pbk").into(),
        reality_short_id: get(&params, "sid").into(),
        path: path.into(),
        host: get(&params, "host").into(),
        ..Default::default()
    };
    Some(with_xray_params(p, &params))
}

// --- hysteria2://auth@host:port/?sni=&insecure=&obfs=salamander&obfs-password=&mport=&up=&down=#name ---
fn parse_hysteria2(uri: &str) -> Option<ProxyProfile> {
    let scheme_len = if starts_ci(uri, "hy2://") {
        "hy2://".len()
    } else {
        "hysteria2://".len()
    };
    let rest = &uri[scheme_len..];
    let remark = percent_decode(after(rest, '#'));
    let before_fragment = before(rest, '#');
    let query = after(before_fragment, '?');
    let mut authority = before(before_fragment, '?')
        .trim_end_matches('/')
        .to_string();

    let mut auth = String::new();
    if let Some(at) = authority.rfind('@') {
        auth = percent_decode(&authority[..at]);
        authority = authority[at + 1..].to_string();
    }
    // Official hy2 form carries port hopping in the authority: host:443,5000-6000 / host:20000-30000.
    let mut port_spec = String::new();
    let colon = authority.rfind(':').map(|i| i as isize).unwrap_or(-1);
    let bracket = authority.rfind(']').map(|i| i as isize).unwrap_or(-1);
    if colon > bracket {
        let tail = &authority[colon as usize + 1..];
        if !tail.is_empty()
            && tail
                .chars()
                .all(|c| c.is_ascii_digit() || c == ',' || c == '-')
            && tail.parse::<i32>().is_err()
        {
            port_spec = tail.to_string();
            authority.truncate(colon as usize);
        }
    }
    let host_port = split_host_port(&authority);
    let host = host_port
        .as_ref()
        .map(|h| h.0.clone())
        .unwrap_or_else(|| authority.trim_matches(['[', ']']).to_string());
    let port = host_port.map(|h| h.1).unwrap_or_else(|| {
        port_spec
            .split(',')
            .find_map(|s| before(s, '-').parse::<i32>().ok())
            .unwrap_or(443)
    });
    if host.trim().is_empty() {
        return None;
    }
    let params = parse_query(query);
    // up/down may be "100" or "100 mbps" — take the leading integer.
    let mbps = |v: &str| -> i32 {
        v.trim()
            .chars()
            .take_while(char::is_ascii_digit)
            .collect::<String>()
            .parse()
            .unwrap_or(0)
    };
    // pinSHA256 pins the certificate (self-signed servers); sing-box only pins the public key, so a
    // pinned link falls back to insecure like other sing-box clients do.
    let insecure = !get(&params, "pinSHA256").trim().is_empty()
        || ["insecure", "allowInsecure", "skip-cert-verify"]
            .iter()
            .any(
                |k| matches!(params.get(*k), Some(v) if v == "1" || v.eq_ignore_ascii_case("true")),
            );
    let ports = params
        .get("mport")
        .or_else(|| params.get("ports"))
        .cloned()
        .or_else(|| (!port_spec.is_empty()).then(|| port_spec.clone()))
        .unwrap_or_default();
    Some(ProxyProfile {
        tag: remark,
        kind: TYPE_HYSTERIA2.into(),
        server: host,
        server_port: port,
        password: auth,
        security: SECURITY_TLS.into(), // hysteria2 always runs over QUIC+TLS
        sni: sni_of(&params),
        alpn: split_list(get(&params, "alpn")),
        allow_insecure: insecure,
        hy2_obfs: get(&params, "obfs").to_lowercase(),
        hy2_obfs_password: get(&params, "obfs-password").into(),
        hy2_up_mbps: mbps(get(&params, "up")),
        hy2_down_mbps: mbps(get(&params, "down")),
        hy2_ports: ports,
        ..Default::default()
    })
}

// --- naive+https://user:pass@host:port?sni=#name (naive+quic:// for QUIC) ---
fn parse_naive(uri: &str) -> Option<ProxyProfile> {
    let scheme = ["naive+https://", "naive+quic://", "naive://"]
        .into_iter()
        .find(|s| starts_ci(uri, s))?;
    let quic = scheme == "naive+quic://";
    let rest = &uri[scheme.len()..];
    let remark = percent_decode(after(rest, '#'));
    let before_fragment = before(rest, '#');
    let query = after(before_fragment, '?');
    let mut authority = before(before_fragment, '?')
        .trim_end_matches('/')
        .to_string();
    let (mut user, mut pass) = (String::new(), String::new());
    if let Some(at) = authority.rfind('@') {
        let user_info = authority[..at].to_string();
        user = percent_decode(before(&user_info, ':'));
        pass = percent_decode(after(&user_info, ':'));
        authority = authority[at + 1..].to_string();
    }
    let host_port = split_host_port(&authority);
    let host = host_port
        .as_ref()
        .map(|h| h.0.clone())
        .unwrap_or_else(|| authority.trim_matches(['[', ']']).to_string());
    let port = host_port.map(|h| h.1).unwrap_or(443);
    if host.trim().is_empty() {
        return None;
    }
    let params = parse_query(query);
    Some(ProxyProfile {
        tag: remark,
        kind: TYPE_NAIVE.into(),
        server: host,
        server_port: port,
        username: user,
        password: pass,
        // Naive IS HTTPS (or QUIC): TLS mandatory; only sni is carried over.
        security: SECURITY_TLS.into(),
        sni: sni_of(&params),
        naive_quic: quic || get(&params, "network").eq_ignore_ascii_case("quic"),
        ..Default::default()
    })
}

// --- ss:// (SIP002: base64(method:password)@host:port, or legacy base64(everything)) ---
fn parse_shadowsocks(uri: &str) -> Option<ProxyProfile> {
    let rest = &uri["ss://".len()..];
    let remark = percent_decode(after(rest, '#'));
    let body = before(before(rest, '#'), '?');
    let (method, password, host, port);
    if let Some(at) = body.rfind('@').filter(|&i| i > 0) {
        // SIP002: userinfo may be base64(method:password) or plain method:password.
        let raw = &body[..at];
        let user_info = if raw.contains(':') {
            raw.to_string()
        } else {
            decode_base64_chunk(raw)?
        };
        let colon = user_info.find(':').filter(|&i| i > 0)?;
        method = user_info[..colon].to_string();
        password = user_info[colon + 1..].to_string();
        (host, port) = split_host_port(&body[at + 1..])?;
    } else {
        let decoded = decode_base64_chunk(body)?;
        let at = decoded.rfind('@').filter(|&i| i > 0)?;
        let user_info = &decoded[..at];
        let colon = user_info.find(':').filter(|&i| i > 0)?;
        method = user_info[..colon].to_string();
        password = user_info[colon + 1..].to_string();
        (host, port) = split_host_port(&decoded[at + 1..])?;
    }
    if method.trim().is_empty() || password.trim().is_empty() || host.trim().is_empty() {
        return None;
    }
    Some(ProxyProfile {
        tag: remark,
        kind: TYPE_SHADOWSOCKS.into(),
        server: host,
        server_port: port,
        method,
        password,
        ..Default::default()
    })
}

// --- AmneziaWG wg-quick INI (Jc/Jmin/Jmax/S1/S2/H1..H4) ---

const AWG_KEYS: [&str; 9] = ["jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4"];

/// Lines split like Kotlin's `lineSequence` (`\r\n`, `\n` or `\r`).
pub(crate) fn lines(text: &str) -> impl Iterator<Item = &str> {
    text.split("\r\n").flat_map(|l| l.split(['\n', '\r']))
}

/// True when `text` is a wg-quick config with `[Interface]` and at least one AmneziaWG knob.
pub fn looks_like_amneziawg(text: &str) -> bool {
    if !text.to_lowercase().contains("[interface]") {
        return false;
    }
    lines(text).any(|l| {
        l.split_once('=')
            .is_some_and(|(k, _)| AWG_KEYS.contains(&k.trim().to_lowercase().as_str()))
    })
}

/// The whole INI stays in [`ProxyProfile::awg_config`]; server/port come from the peer Endpoint.
pub fn parse_amneziawg(text: &str) -> Option<ProxyProfile> {
    if !looks_like_amneziawg(text) {
        return None;
    }
    let (mut endpoint, mut name) = (String::new(), String::new());
    for raw in lines(text) {
        let line = raw.trim();
        if let Some(c) = line.strip_prefix('#') {
            if !c.trim().is_empty() {
                name = c.trim().to_string();
            }
            continue;
        }
        let Some(eq) = line.find('=').filter(|&i| i > 0) else {
            continue;
        };
        if line[..eq].trim().eq_ignore_ascii_case("endpoint") {
            endpoint = line[eq + 1..].trim().to_string();
        }
    }
    let (host, port) = Some(endpoint)
        .filter(|e| !e.trim().is_empty())
        .and_then(|e| split_host_port(&e))
        .unwrap_or_else(|| ("amneziawg".into(), 0));
    let tag = if !name.trim().is_empty() {
        name
    } else if !host.trim().is_empty() {
        format!("AmneziaWG {host}")
    } else {
        "AmneziaWG".into()
    };
    Some(ProxyProfile {
        tag,
        kind: TYPE_AMNEZIAWG.into(),
        server: host,
        server_port: port,
        awg_config: text.trim().to_string(),
        ..Default::default()
    })
}

// --- tt://<base64url TLV> (AdGuard Trust Tunnel deep link) ---

pub const TT_SCHEME: &str = "tt://";
const TT_HOSTNAME: i64 = 1;
const TT_ADDRESS: i64 = 2;
const TT_NAME: i64 = 12;

pub fn looks_like_trust_tunnel(text: &str) -> bool {
    starts_ci(text.trim(), TT_SCHEME)
}

/// The link is kept verbatim in [`ProxyProfile::tt_config`] (the native client decodes it at
/// connect time); `#name` or the blob's own name (tag 12) becomes the display tag.
pub fn parse_trust_tunnel(uri: &str) -> Option<ProxyProfile> {
    let t = uri.trim();
    if !looks_like_trust_tunnel(t) {
        return None;
    }
    let after_scheme = &t[TT_SCHEME.len()..];
    let payload = before(after_scheme, '#');
    let payload = payload.strip_prefix('?').unwrap_or(payload).trim();
    if payload.is_empty() {
        return None;
    }
    let fragment = percent_decode(after(after_scheme, '#'));
    let tag = if fragment.trim().is_empty() {
        tt_name(t).unwrap_or_else(|| "Trust Tunnel".into())
    } else {
        fragment
    };
    Some(ProxyProfile {
        tag,
        kind: TYPE_TRUSTTUNNEL.into(),
        server: "trusttunnel".into(),
        server_port: 0,
        tt_config: t.to_string(),
        ..Default::default()
    })
}

/// Text TLV items of a `tt://` blob (tag/length are QUIC varints); empty when unreadable.
fn tt_items(link: &str) -> Vec<(i64, String)> {
    let t = link.trim();
    let t = if starts_ci(t, TT_SCHEME) {
        &t[TT_SCHEME.len()..]
    } else {
        t
    };
    let payload = before(t, '#');
    let payload = payload.strip_prefix('?').unwrap_or(payload).trim();
    let engine = GeneralPurpose::new(
        &alphabet::URL_SAFE,
        GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
    );
    let Ok(bytes) = engine.decode(payload) else {
        return Vec::new();
    };
    let mut i = 0usize;
    let varint = |i: &mut usize| -> Option<i64> {
        let first = *bytes.get(*i)?;
        let len = 1usize << (first >> 6);
        if *i + len > bytes.len() {
            return None;
        }
        let mut v = (first & 0x3F) as i64;
        for k in 1..len {
            v = (v << 8) | bytes[*i + k] as i64;
        }
        *i += len;
        Some(v)
    };
    let mut out = Vec::new();
    while i < bytes.len() {
        let Some(tag) = varint(&mut i) else { break };
        let Some(len) = varint(&mut i) else { break };
        if len < 0 || i as i64 + len > bytes.len() as i64 {
            break;
        }
        let len = len as usize;
        if tag == TT_HOSTNAME || tag == TT_ADDRESS || tag == TT_NAME {
            out.push((
                tag,
                String::from_utf8_lossy(&bytes[i..i + len]).into_owned(),
            ));
        }
        i += len;
    }
    out
}

/// Display name inside the blob (tag 12), if any.
pub fn tt_name(link: &str) -> Option<String> {
    tt_items(link)
        .into_iter()
        .find(|(t, _)| *t == TT_NAME)
        .map(|(_, v)| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// Real `host`/`port` endpoints inside a `tt://` blob, for pings; `hostname:443` when no address.
pub fn tt_endpoints(link: &str) -> Vec<(String, i32)> {
    let all = tt_items(link);
    let hostname = all
        .iter()
        .find(|(t, _)| *t == TT_HOSTNAME)
        .map(|(_, v)| v.clone())
        .unwrap_or_default();
    let mut raw: Vec<String> = all
        .iter()
        .filter(|(t, _)| *t == TT_ADDRESS)
        .map(|(_, v)| v.clone())
        .collect();
    if raw.is_empty() && !hostname.trim().is_empty() {
        raw.push(format!("{hostname}:443"));
    }
    raw.iter()
        .filter_map(|addr| {
            let (h, p) = addr.rsplit_once(':')?;
            let host = h
                .strip_prefix('[')
                .and_then(|x| x.strip_suffix(']'))
                .unwrap_or(h);
            let port: i32 = p.parse().ok()?;
            (!host.trim().is_empty() && (1..=65535).contains(&port))
                .then(|| (host.to_string(), port))
        })
        .collect()
}

/// Server to show in the UI (Trust Tunnel keeps a placeholder server; read the real one from the link).
pub fn display_server(p: &ProxyProfile) -> String {
    if p.kind == TYPE_TRUSTTUNNEL {
        tt_endpoints(&p.tt_config)
            .into_iter()
            .next()
            .map(|e| e.0)
            .unwrap_or_else(|| p.server.clone())
    } else {
        p.server.clone()
    }
}
