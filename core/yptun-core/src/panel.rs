//! Links of the tunnel engines and of the QuofortpostProtocols panel (port of `CsqttUriParser`,
//! `FreeturnUriParser`, `QwdttUriParser`, `OpenFluxUriParser`, `MasterDnsUriParser`,
//! `YptunInboundCodec` and the olcRTC link parser of the app's `LocationsDatasource`).

use std::collections::BTreeMap;

use base64::engine::general_purpose::{URL_SAFE, URL_SAFE_NO_PAD};
use base64::Engine;
use serde_json::{json, Map, Value};

use crate::crypto::{csqtt_decrypt, deflate, inflate};
use crate::location::*;
use crate::share_link::starts_ci;
use crate::subscription::decode_base64_chunk;
use crate::transports::*;
use crate::uri::{after, before, parse_query, percent_decode, percent_encode, split_host_port};

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

/// Splits on `,` `;` `+` and whitespace, blanks dropped (csqtt `hashes`).
fn split_hashes_plus(raw: &str) -> Vec<String> {
    raw.split([',', ';', '+', '\n', '\r', '\t', ' ']).map(str::trim).filter(|s| !s.is_empty()).map(String::from).collect()
}

/// Like the qWDTT core's `ParseHashes`: `,` `;` and whitespace.
fn split_hashes(raw: &str) -> Vec<String> {
    raw.split([',', ';', '\n', '\r', '\t', ' ']).map(str::trim).filter(|s| !s.is_empty()).map(String::from).collect()
}

/// `JsonPrimitive.content` (strings unquoted, numbers/bools as text), trimmed; "" otherwise.
fn jstr(obj: &Map<String, Value>, key: &str) -> String {
    match obj.get(key) {
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

// ---------------- csqtt ----------------

pub const CSQTT_SCHEME: &str = "csqtt://";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CsqttLink {
    pub host: String,
    pub port: i32,
    pub password: String,
    /// VK hashes, one per line (the storage format of `VkTurnConfig.vk_link`).
    pub hashes: String,
    /// `#fragment` name; blank when absent.
    pub name: String,
}

/// `csqtt://connect?v=2&host=&peer=&password=[&hashes=a+b]`, the encrypted 2.5.0 form
/// `csqtt://<base64url>`, or legacy `csqtt://<password>@host:port`; `#name` optional.
pub fn parse_csqtt(uri: &str) -> Option<CsqttLink> {
    let t = uri.trim();
    if !starts_ci(t, CSQTT_SCHEME) {
        return None;
    }
    let frag = after(t, '#');
    let name = if frag.trim().is_empty() { String::new() } else { percent_decode(frag).trim().to_string() };
    let rest = before(&t[CSQTT_SCHEME.len()..], '#');
    let query = after(rest, '?');
    let with_name = |l: CsqttLink| CsqttLink { name: name.clone(), ..l };
    if before(rest, '?').trim_end_matches('/').eq_ignore_ascii_case("connect") && !query.trim().is_empty() {
        return parse_csqtt_v2(query).map(with_name);
    }
    if !rest.contains('@') {
        if let Some(plain) = csqtt_decrypt(rest) {
            return parse_csqtt(&format!("{CSQTT_SCHEME}{plain}")).map(with_name);
        }
    }
    parse_csqtt_legacy(rest).map(with_name)
}

fn parse_csqtt_v2(query: &str) -> Option<CsqttLink> {
    let p = parse_query(&query.replace("&amp;", "&"));
    if p.get("v").map(|v| v.trim()) != Some("2") {
        return None;
    }
    let host = p.get("host").map(|v| v.trim().to_string()).unwrap_or_default();
    let port = p.get("peer").and_then(|v| v.trim().parse::<i32>().ok()).filter(|p| (1..=65535).contains(p))?;
    let password = p.get("password").map(|v| v.trim().to_string()).unwrap_or_default();
    if host.is_empty() || password.is_empty() || host.chars().any(char::is_whitespace) || password.chars().any(char::is_whitespace) {
        return None;
    }
    let hashes = split_hashes_plus(p.get("hashes").map(String::as_str).unwrap_or(""));
    if p.contains_key("hashes") && hashes.is_empty() {
        return None;
    }
    Some(CsqttLink { host, port, password, hashes: hashes.join("\n"), name: String::new() })
}

fn parse_csqtt_legacy(rest: &str) -> Option<CsqttLink> {
    let authority = before(before(before(rest, '/'), '?'), '#');
    let at = authority.rfind('@')?;
    let raw_pass = &authority[..at];
    if raw_pass.trim().is_empty() {
        return None;
    }
    let password = percent_decode(raw_pass);
    let host_port = &authority[at + 1..];
    let port = host_port.rfind(':').and_then(|i| host_port[i + 1..].parse::<i32>().ok()).filter(|p| (1..=65535).contains(p))?;
    let host = host_port[..host_port.rfind(':')?].trim_start_matches('[').trim_end_matches(']').to_string();
    if host.trim().is_empty() {
        return None;
    }
    Some(CsqttLink { host, port, password, hashes: String::new(), name: String::new() })
}

/// Re-emits the connection link for a stored csqtt location (round-trips [`parse_csqtt`]).
pub fn compose_csqtt(vk: &VkTurnConfig, name: &str) -> String {
    let addr = vk.csqtt_peer_addr();
    let (host, port) = match addr.rfind(':') {
        Some(i) => (&addr[..i], addr[i + 1..].parse().unwrap_or(DEFAULT_CSQTT_PORT)),
        None => (addr.as_str(), DEFAULT_CSQTT_PORT),
    };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let hashes = split_hashes_plus(&vk.vk_link);
    let mut s = format!("{CSQTT_SCHEME}connect?v=2&host={}&peer={port}&password={}", percent_encode(host), percent_encode(vk.csqtt_password.trim()));
    if !hashes.is_empty() {
        s.push_str("&hashes=");
        s.push_str(&hashes.iter().map(|h| percent_encode(h)).collect::<Vec<_>>().join("+"));
    }
    if not_blank(name) {
        s.push('#');
        s.push_str(&percent_encode(name.trim()));
    }
    s
}

// ---------------- freeturn ----------------

pub const FREETURN_SCHEME: &str = "freeturn://";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FreeturnLink {
    /// The original link, passed verbatim to the Go client.
    pub uri: String,
    pub server_ip: String,
    pub server_port: i32,
    /// Local port the client raises (= WireGuard Endpoint port in udp mode; 0 in tcp).
    pub listen_port: i32,
    /// "udp" (WireGuard) or "tcp" (proxy/bonded).
    pub mode: String,
    /// sing-box wireguard outbound (udp only).
    pub wg_outbound_json: String,
    /// Exit proxy share link from `vless=` (tcp only).
    pub exit_proxy_link: String,
    pub comment: String,
    /// VK call links from the panel (`vk=`); blank = the user adds them.
    pub vk_link: String,
}

/// `freeturn://vk?<transport><mode=..&wg=<b64>&vk=<b64>>@ip:port#obfKey$comment`, or the wire form
/// `freeturn://<base64url(json)>`.
pub fn parse_freeturn(uri: &str) -> Option<FreeturnLink> {
    let t = uri.trim();
    if !starts_ci(t, FREETURN_SCHEME) {
        return None;
    }
    let mut s = t[FREETURN_SCHEME.len()..].to_string();
    if let Some(l) = parse_freeturn_wire(t, &s) {
        return Some(l);
    }
    let mut comment = String::new();
    if let Some(i) = s.rfind('$') {
        comment = s[i + 1..].to_string();
        s.truncate(i);
    }
    if let Some(i) = s.rfind('#') {
        s.truncate(i);
    }
    let mut peer = String::new();
    if let Some(i) = s.rfind('@') {
        peer = s[i + 1..].to_string();
        s.truncate(i);
    }
    if peer.trim().is_empty() {
        return None;
    }
    let (server_ip, server_port) = split_host_port(&peer)?;
    let params = angle_params(&s);
    let comment = percent_decode(&comment);
    let wg_b64 = params.get("wg");
    let exit_b64 = params.get("vless").or_else(|| params.get("proxy"));
    let mode = params.get("mode").map(|m| m.trim().to_lowercase()).filter(|m| !m.is_empty());
    let vk_link = params.get("vk").and_then(|v| decode_base64_chunk(v)).map(|v| v.trim().to_string()).unwrap_or_default();
    if let Some(exit_b64) = exit_b64 {
        if mode.as_deref() == Some("tcp") || wg_b64.is_none() {
            let exit = decode_base64_chunk(exit_b64).map(|e| e.trim().to_string()).unwrap_or_default();
            if exit.is_empty() {
                return None;
            }
            return Some(FreeturnLink {
                uri: t.into(),
                server_ip,
                server_port,
                listen_port: 0,
                mode: "tcp".into(),
                wg_outbound_json: String::new(),
                exit_proxy_link: exit,
                comment,
                vk_link,
            });
        }
    }
    let wg = parse_wg_conf(&decode_base64_chunk(wg_b64?)?)?;
    Some(FreeturnLink {
        uri: t.into(),
        server_ip,
        server_port,
        listen_port: wg.0,
        mode: "udp".into(),
        wg_outbound_json: wg.1,
        exit_proxy_link: String::new(),
        comment,
        vk_link,
    })
}

fn parse_freeturn_wire(uri: &str, payload: &str) -> Option<FreeturnLink> {
    let text = decode_base64_chunk(payload)?;
    let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(&text) else { return None };
    let peer = jstr(&obj, "peer");
    if peer.is_empty() || jstr(&obj, "provider").is_empty() {
        return None;
    }
    let (server_ip, server_port) = split_host_port(&peer)?;
    let wg_raw = jstr(&obj, "wg");
    let conf = if wg_raw.contains('[') { wg_raw } else { decode_base64_chunk(&wg_raw).unwrap_or_default() };
    let wg = parse_wg_conf(&conf)?;
    Some(FreeturnLink {
        uri: uri.into(),
        server_ip,
        server_port,
        listen_port: wg.0,
        mode: "udp".into(),
        wg_outbound_json: wg.1,
        exit_proxy_link: String::new(),
        comment: jstr(&obj, "name"),
        vk_link: jstr(&obj, "vk"),
    })
}

/// The `key=val&…` map of the first `<…>` block.
fn angle_params(s: &str) -> BTreeMap<String, String> {
    let (Some(open), Some(close)) = (s.find('<'), s.rfind('>')) else { return BTreeMap::new() };
    if close <= open {
        return BTreeMap::new();
    }
    s[open + 1..close]
        .split('&')
        .filter_map(|kv| kv.find('=').filter(|&i| i > 0).map(|i| (kv[..i].to_string(), kv[i + 1..].to_string())))
        .collect()
}

/// wg-quick client INI → (endpoint port, sing-box legacy wireguard outbound JSON).
pub fn parse_wg_conf(conf: &str) -> Option<(i32, String)> {
    let (mut private_key, mut address, mut mtu, mut public_key, mut endpoint) = (String::new(), String::new(), 0, String::new(), String::new());
    for raw in crate::share_link::lines(conf) {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('[') || line.starts_with('#') {
            continue;
        }
        let Some(eq) = line.find('=').filter(|&i| i > 0) else { continue };
        let value = line[eq + 1..].trim();
        match line[..eq].trim().to_lowercase().as_str() {
            "privatekey" => private_key = value.into(),
            "address" if address.trim().is_empty() => address = before(value, ',').trim().into(),
            "mtu" => mtu = value.parse().unwrap_or(0),
            "publickey" => public_key = value.into(),
            "endpoint" => endpoint = value.into(),
            _ => {}
        }
    }
    if private_key.trim().is_empty() || public_key.trim().is_empty() || address.trim().is_empty() {
        return None;
    }
    let (host, port) = split_host_port(&endpoint)?;
    let mut out = json!({
        "type": "wireguard",
        "server": host,
        "server_port": port,
        "local_address": [address],
        "private_key": private_key,
        "peer_public_key": public_key,
    });
    if mtu > 0 {
        out["mtu"] = json!(mtu);
    }
    Some((port, out.to_string()))
}

// ---------------- qWDTT ----------------

pub const QWDTT_SCHEME: &str = "qwdtt://";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QwdttLink {
    pub name: String,
    /// wdtt-server host (the DTLS port defaults to 56000).
    pub peer: String,
    /// VK hashes, one per line.
    pub hashes: String,
    pub password: String,
    /// Local WireGuard listen port; 0 = default.
    pub listen_port: i32,
    /// Workers per hash; 0 = core default.
    pub workers: i32,
}

/// `qwdtt://config?name=&peer=&hashes=&workers=&port=&pass=`.
pub fn parse_qwdtt(uri: &str) -> Option<QwdttLink> {
    let t = uri.trim();
    if !starts_ci(t, QWDTT_SCHEME) {
        return None;
    }
    let query = after(t, '?');
    if query.trim().is_empty() {
        return None;
    }
    let p = parse_query(query);
    let g = |k: &str| p.get(k).map(|v| v.trim().to_string()).unwrap_or_default();
    let (peer, password) = (g("peer"), g("pass"));
    if peer.is_empty() || password.is_empty() {
        return None;
    }
    Some(QwdttLink {
        name: g("name"),
        peer,
        hashes: split_hashes(p.get("hashes").map(String::as_str).unwrap_or("")).join("\n"),
        password,
        listen_port: g("port").parse().ok().filter(|p| (1..=65535).contains(p)).unwrap_or(0),
        workers: g("workers").parse().ok().filter(|&w| w > 0).unwrap_or(0),
    })
}

/// Re-emits the quick link for a stored WDTT location (round-trips [`parse_qwdtt`]).
pub fn compose_qwdtt(name: &str, vk: &VkTurnConfig) -> String {
    let port = if (1..=65535).contains(&vk.listen_port) { vk.listen_port } else { DEFAULT_FREETURN_PORT };
    let mut params: Vec<(String, String)> = Vec::new();
    if not_blank(name) {
        params.push(("name".into(), name.into()));
    }
    params.push(("peer".into(), vk.wdtt_peer.trim().into()));
    let hashes = split_hashes(&vk.vk_link);
    if !hashes.is_empty() {
        params.push(("hashes".into(), hashes.join(",")));
    }
    if vk.wdtt_workers > 0 {
        params.push(("workers".into(), vk.wdtt_workers.to_string()));
    }
    params.push(("port".into(), port.to_string()));
    params.push(("pass".into(), vk.wdtt_password.trim().into()));
    format!("{QWDTT_SCHEME}config?{}", params.iter().map(|(k, v)| format!("{k}={}", percent_encode(v))).collect::<Vec<_>>().join("&"))
}

// ---------------- OpenFlux ----------------

pub const OPENFLUX_SCHEME: &str = "openflux://";
const OPENFLUX_PREFIX: &str = "openflux://v1/";

/// `openflux://v1/<base64url(raw DEFLATE(JSON))>` (the exit's `--share` link, plus the panel's MAX
/// `uid`/`token` and Avito `cookies`) → (name, config). Unrunnable links yield None.
pub fn parse_openflux(link: &str) -> Option<(String, OpenFluxConfig)> {
    let t = link.trim();
    let body = t.strip_prefix(OPENFLUX_PREFIX)?;
    let body: String = body.chars().filter(|c| !c.is_whitespace() && *c != '\u{200B}').collect();
    let packed = crate::crypto::b64url_lenient(&body)?;
    let raw = inflate(&packed)?;
    let Ok(Value::Object(root)) = serde_json::from_slice::<Value>(&raw) else { return None };
    if !jstr(&root, "mode").is_empty() {
        return None;
    }
    let secret = jstr(&root, "secret");
    let negotiate = root.get("negotiate") == Some(&Value::Bool(true))
        || matches!(root.get("negotiate"), Some(Value::String(s)) if s == "true");
    if negotiate && secret.is_empty() {
        return None;
    }
    let items: Vec<&Map<String, Value>> = root.get("transports")?.as_array()?.iter().map(|v| v.as_object()).collect::<Option<_>>()?;
    if items.is_empty() {
        return None;
    }
    let name = jstr(&root, "name");
    let context = jstr(&root, "context");
    if let [single] = items.as_slice() {
        let kind = jstr(single, "type");
        if kind != OF_DIRECT {
            if !OF_TRANSPORTS.contains(&kind.as_str()) {
                return None;
            }
            if kind == OF_MAX {
                let (uid, token) = (jstr(single, "uid"), jstr(single, "token"));
                if uid.parse::<i64>().is_err() || token.is_empty() {
                    return None;
                }
                let c = OpenFluxConfig { transport: kind, max_uid: uid, max_token: token, secret, context, negotiate, ..Default::default() };
                return Some((name, c.normalized()));
            }
            let url = jstr(single, "url");
            let room = kind == OF_CUPS || kind == OF_AVITO;
            if !(starts_ci(&url, "http") || (room && url.chars().count() >= 16)) {
                return None;
            }
            let cookies = if kind == OF_AVITO { jstr(single, "cookies") } else { String::new() };
            let c = OpenFluxConfig { transport: kind, doc_url: url, secret, context, negotiate, avito_cookies: cookies, ..Default::default() };
            return Some((name, c.normalized()));
        }
    }
    // A session: the core only runs one with an encryption key.
    if secret.is_empty() {
        return None;
    }
    let mut carriers = Vec::new();
    for tr in items {
        let kind = jstr(tr, "type");
        if !OF_SESSION_CARRIERS.contains(&kind.as_str()) {
            return None;
        }
        let c = OpenFluxCarrier {
            kind,
            url: jstr(tr, "url"),
            dial: jstr(tr, "dial"),
            priority: jstr(tr, "priority").parse().unwrap_or(OF_DEFAULT_PRIORITY),
        };
        if !c.is_complete() {
            return None;
        }
        carriers.push(c);
    }
    let mut kinds: Vec<&str> = carriers.iter().map(|c| c.kind.as_str()).collect();
    kinds.sort_unstable();
    kinds.dedup();
    if kinds.len() != carriers.len() {
        return None;
    }
    Some((name, OpenFluxConfig { carriers, secret, context, negotiate, ..Default::default() }.normalized()))
}

/// Builds an `openflux://v1/` link (tests and sharing).
pub fn compose_openflux_raw(json_text: &str) -> String {
    let mut enc = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    std::io::Write::write_all(&mut enc, json_text.as_bytes()).unwrap();
    format!("{OPENFLUX_PREFIX}{}", URL_SAFE_NO_PAD.encode(enc.finish().unwrap()))
}

// ---------------- MasterDNS ----------------

pub const MASTERDNS_SCHEME: &str = "masterdns://";
const MASTERDNS_PREFIX: &str = "masterdns://v1";

/// `masterdns://v1?domains=&key=&enc=&resolvers=[&bal=][&dup=][&proxy=<base64url link>]#name`.
pub fn parse_masterdns(link: &str) -> Option<(String, MasterDnsConfig)> {
    let t = link.trim();
    if !starts_ci(t, MASTERDNS_PREFIX) {
        return None;
    }
    let rest = &t[MASTERDNS_PREFIX.len()..];
    let name = percent_decode(after(rest, '#'));
    let q = parse_query(after(before(rest, '#'), '?'));
    let g = |k: &str| q.get(k).cloned().unwrap_or_default();
    let cfg = MasterDnsConfig {
        domains: g("domains"),
        encryption_key: g("key"),
        encryption_method: q.get("enc").and_then(|v| v.parse().ok()).unwrap_or(MASTERDNS_DEFAULT_ENCRYPTION),
        resolvers: g("resolvers"),
        balancing_strategy: q.get("bal").and_then(|v| v.parse().ok()).unwrap_or(0),
        packet_duplication: q.get("dup").and_then(|v| v.parse().ok()).unwrap_or(0),
        proxy_link: q.get("proxy").and_then(|v| decode_base64_chunk(v)).unwrap_or_default(),
        ..Default::default()
    }
    .normalized();
    cfg.is_complete().then_some((name, cfg))
}

// ---------------- olcRTC ----------------

pub const OLCRTC_SCHEME: &str = "olcrtc://";

/// A parsed `olcrtc://` link: the location plus its `$name` (mimo), if any.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OlcRtcLink {
    pub location: LocationConfig,
    pub mimo: Option<String>,
}

/// `olcrtc://provider?transport<k=v&…>@room#key[%client][$Name]`, several rooms joined with `!`
/// (the first is the main room, the rest become multi-room extras).
pub fn parse_olcrtc(line: &str) -> Option<OlcRtcLink> {
    let payload = line.strip_prefix(OLCRTC_SCHEME).unwrap_or(line);
    let name_start = payload.find('$').unwrap_or(payload.len());
    let segments: Vec<&str> = payload[..name_start].split('!').collect();
    if segments.len() == 1 {
        return parse_olcrtc_segment(payload);
    }
    let mut main = parse_olcrtc_segment(&format!("{}{}", segments[0], &payload[name_start..]))?;
    let extras: Vec<ExtraRoom> = segments[1..]
        .iter()
        .filter_map(|s| parse_olcrtc_segment(s))
        .map(|p| ExtraRoom {
            provider: p.location.bypass_provider,
            transport: p.location.transport,
            room: p.location.id,
            key: p.location.key,
        })
        .take(MAX_EXTRA_ROOMS)
        .collect();
    if !extras.is_empty() {
        main.location = LocationConfig { multi_room_enabled: true, extra_rooms: extras, ..main.location }.normalized();
    }
    Some(main)
}

fn find_from(s: &str, c: char, from: usize) -> Option<usize> {
    s.get(from..)?.find(c).map(|i| i + from)
}

fn parse_olcrtc_segment(payload: &str) -> Option<OlcRtcLink> {
    let transport_marker = payload.find('?');
    let room_marker = find_from(payload, '@', transport_marker.map_or(0, |i| i + 1));
    let key_marker = room_marker.and_then(|r| find_from(payload, '#', r + 1));
    let (Some(tm), Some(rm), Some(km)) = (transport_marker, room_marker, key_marker) else { return None };
    if tm == 0 || rm <= tm || km <= rm {
        return None;
    }
    let client_marker = find_from(payload, '%', km + 1);
    let mimo_marker = find_from(payload, '$', km + 1);
    let key_end = [client_marker, mimo_marker].into_iter().flatten().min().unwrap_or(payload.len());
    let provider = payload[..tm].trim();
    let (transport, options) = parse_transport_token(payload[tm + 1..rm].trim());
    let room = payload[rm + 1..km].trim();
    let key = payload[km + 1..key_end].trim();
    let mimo = mimo_marker.map(|i| payload[i + 1..].trim().to_string()).unwrap_or_default();
    let opt_int = |a: &str, b: &str| options.get(a).or_else(|| options.get(b)).and_then(|v| v.parse::<i32>().ok());
    let location = LocationConfig {
        name: if mimo.trim().is_empty() { room.into() } else { mimo.clone() },
        id: room.into(),
        key: key.into(),
        bypass_provider: provider.into(),
        transport: transport.clone(),
        vp8_fps: opt_int("vp8-fps", "fps").unwrap_or(DEFAULT_VP8_FPS),
        vp8_batch: opt_int("vp8-batch", "batch").unwrap_or(DEFAULT_VP8_BATCH),
        transport_options: options,
        ..Default::default()
    }
    .normalized();
    location.is_complete().then(|| OlcRtcLink { location, mimo: (!mimo.trim().is_empty()).then_some(mimo) })
}

/// `<Transport><key=value&…>` → transport and its (lower-cased key) parameters.
fn parse_transport_token(token: &str) -> (String, BTreeMap<String, String>) {
    let (Some(open), Some(close)) = (token.find('<'), token.rfind('>')) else { return (token.into(), BTreeMap::new()) };
    if close <= open {
        return (token.into(), BTreeMap::new());
    }
    let options = token[open + 1..close]
        .split('&')
        .filter_map(|part| {
            let sep = part.find('=').filter(|&i| i > 0)?;
            let value = part[sep + 1..].trim();
            (!value.is_empty()).then(|| (part[..sep].trim().to_lowercase(), value.to_string()))
        })
        .collect();
    (token[..open].trim().into(), options)
}

/// `#refresh:` of an olcRTC subscription ("5s", "10m", "6h", "1d"; bare = seconds) → whole hours.
pub fn refresh_interval_hours(value: Option<&str>) -> Option<i32> {
    let v = value?.trim().to_lowercase();
    let (num, unit) = match v.chars().last() {
        Some(c @ ('s' | 'm' | 'h' | 'd')) => (v[..v.len() - 1].trim(), c),
        _ => (v.as_str(), 's'),
    };
    if num.is_empty() || !num.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let amount: i64 = num.parse().ok()?;
    let seconds = amount.checked_mul(match unit {
        'm' => 60,
        'h' => 3600,
        'd' => 86_400,
        _ => 1,
    })?;
    if seconds <= 0 {
        return None;
    }
    Some(((seconds + 3599) / 3600).clamp(MIN_UPDATE_INTERVAL_HOURS as i64, MAX_UPDATE_INTERVAL_HOURS as i64) as i32)
}

// ---------------- yptun://inbound ----------------

pub const YPTUN_INBOUND_PREFIX: &str = "yptun://inbound";

/// Removes object keys whose value equals the defaults (Kotlin's `encodeDefaults = false`).
fn strip_defaults(value: &mut Value, defaults: &Value) {
    if let (Value::Object(map), Value::Object(def)) = (value, defaults) {
        map.retain(|k, v| def.get(k) != Some(v));
        for key in ["proxy", "proxy2"] {
            if let Some(p) = map.get_mut(key) {
                strip_defaults(p, &serde_json::to_value(crate::model::ProxyProfile::default()).unwrap());
            }
        }
    }
}

/// The app's universal share link for one location: `yptun://inbound?v=2&c=<base64url(zlib(JSON))>`
/// (or `v=1&d=` when compression does not help).
pub fn compose_inbound(config: &LocationConfig) -> String {
    let mut value = serde_json::to_value(config.normalized()).unwrap();
    strip_defaults(&mut value, &serde_json::to_value(LocationConfig::default()).unwrap());
    let payload = value.to_string().into_bytes();
    if let Some(c) = deflate(&payload).filter(|c| c.len() < payload.len()) {
        return format!("{YPTUN_INBOUND_PREFIX}?v=2&c={}", URL_SAFE_NO_PAD.encode(c));
    }
    format!("{YPTUN_INBOUND_PREFIX}?v=1&d={}", URL_SAFE_NO_PAD.encode(payload))
}

/// Decodes a `yptun://inbound…` link (`c=` compressed, `d=`/`data=` plain, or `#<payload>`).
pub fn parse_inbound(uri: &str) -> Option<LocationConfig> {
    let t = uri.trim();
    let rest = t.strip_prefix(YPTUN_INBOUND_PREFIX)?;
    let (value, compressed) = inbound_data(rest)?;
    // Long links arrive wrapped across lines; base64 must not see the whitespace.
    let payload: String = value.chars().filter(|c| !c.is_whitespace()).collect();
    let padded = format!("{payload}{}", "=".repeat((4 - payload.len() % 4) % 4));
    let raw = URL_SAFE.decode(padded).ok()?;
    let bytes = if compressed { inflate(&raw)? } else { raw };
    serde_json::from_slice::<LocationConfig>(&bytes).ok().map(|c| c.normalized())
}

fn inbound_data(rest: &str) -> Option<(String, bool)> {
    let q = before(after(rest, '?'), '#');
    if !q.is_empty() {
        for pair in q.split(['&', ';']) {
            let (k, v) = (before(pair, '='), after(pair, '='));
            if k == "c" && not_blank(v) {
                return Some((v.into(), true));
            }
            if (k == "d" || k == "data") && not_blank(v) {
                return Some((v.into(), false));
            }
        }
    }
    let frag = after(rest, '#');
    not_blank(frag).then(|| (frag.to_string(), false))
}
