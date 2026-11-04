//! The editable VK-TURN draft and the composer that keeps its two derived artefacts in sync — the
//! freeturn:// link and the exit outbound (port of `VkTurnDraft`/`VkTurnComposer`). The link layout
//! mirrors the Go panel codec: `freeturn://<provider>?<transport><k=v&…&wg=b64>@<peer>#<obfKey>$<comment>`.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::model::*;
use crate::panel::FREETURN_SCHEME;
use crate::share_link;
use crate::subscription::decode_base64_chunk;
use crate::transports::*;
use crate::uri::{parse_query, split_host_port};

/// The settings UI's VK-TURN form. Numbers stay strings so the fields can hold partial input.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct VkTurnDraft {
    pub provider: String,
    pub transport: String,
    pub mode: String,
    pub obf_profile: String,
    pub peer_host: String,
    pub peer_port: String,
    pub obf_key: String,
    pub comment: String,
    pub vk_link: String,
    pub wg_private_key: String,
    pub wg_address: String,
    pub wg_dns: String,
    /// 1200: through TURN+DTLS+RTP-obf the path MTU is well under 1500.
    pub wg_mtu: String,
    pub wg_peer_public_key: String,
    pub wg_allowed_ips: String,
    pub wg_keepalive: String,
    /// Local freeturn listener = the WireGuard Endpoint port (127.0.0.1:listen_port).
    pub listen_port: String,
    pub streams: String,
    pub chain_proxy_link: String,
    /// wireguard | amneziawg | proxy.
    pub outbound: String,
    pub awg_jc: String,
    pub awg_jmin: String,
    pub awg_jmax: String,
    pub awg_s1: String,
    pub awg_s2: String,
    pub awg_h1: String,
    pub awg_h2: String,
    pub awg_h3: String,
    pub awg_h4: String,
    pub outbound_proxy_link: String,
    pub proxy_core: ProxyCore,
    /// freeturn | wdtt | csqtt.
    pub core: String,
    pub wdtt_peer: String,
    pub wdtt_port: String,
    pub wdtt_password: String,
    pub wdtt_fingerprint: String,
    pub wdtt_workers: String,
    pub wdtt_plus: WdttPlusOptions,
    pub csqtt_peer: String,
    pub csqtt_port: String,
    pub csqtt_password: String,
    pub csqtt_workers: String,
    pub csqtt: CsqttOptions,
    pub freeturn_multi_server: bool,
    /// Extra freeturn:// servers, one per line.
    pub extra_freeturn_uris: String,
}

impl Default for VkTurnDraft {
    fn default() -> Self {
        let s = |v: &str| v.to_string();
        Self {
            provider: s("vk"),
            transport: s("tcp"),
            mode: s("udp"),
            obf_profile: s("rtpopus"),
            peer_host: s(""),
            peer_port: s(""),
            obf_key: s(""),
            comment: s(""),
            vk_link: s(""),
            wg_private_key: s(""),
            wg_address: s("10.7.1.2/32"),
            wg_dns: s("1.1.1.1"),
            wg_mtu: s("1200"),
            wg_peer_public_key: s(""),
            wg_allowed_ips: s("0.0.0.0/0"),
            wg_keepalive: s("25"),
            listen_port: DEFAULT_FREETURN_PORT.to_string(),
            streams: s("10"),
            chain_proxy_link: s(""),
            outbound: s(OUTBOUND_WIREGUARD),
            awg_jc: s("4"),
            awg_jmin: s("40"),
            awg_jmax: s("70"),
            awg_s1: s("0"),
            awg_s2: s("0"),
            awg_h1: s("1"),
            awg_h2: s("2"),
            awg_h3: s("3"),
            awg_h4: s("4"),
            outbound_proxy_link: s(""),
            proxy_core: ProxyCore::Auto,
            core: s(CORE_FREETURN),
            wdtt_peer: s(""),
            wdtt_port: s(""),
            wdtt_password: s(""),
            wdtt_fingerprint: s("chrome"),
            wdtt_workers: s(""),
            wdtt_plus: WdttPlusOptions::default(),
            csqtt_peer: s(""),
            csqtt_port: s(""),
            csqtt_password: s(""),
            csqtt_workers: s(""),
            csqtt: CsqttOptions::default(),
            freeturn_multi_server: false,
            extra_freeturn_uris: s(""),
        }
    }
}

fn int(s: &str) -> Option<i32> {
    s.trim().parse().ok()
}
fn port(s: &str) -> i32 {
    int(s).filter(|p| (1..=65535).contains(p)).unwrap_or(0)
}
fn positive(s: &str) -> i32 {
    int(s).filter(|v| *v > 0).unwrap_or(0)
}
fn or<'a>(s: &'a str, d: &'a str) -> &'a str {
    if s.trim().is_empty() {
        d
    } else {
        s.trim()
    }
}
fn positive_text(v: i32) -> String {
    if v > 0 {
        v.to_string()
    } else {
        String::new()
    }
}

/// Builds the freeturn:// link + the chosen exit outbound for `draft`. `name` becomes the tag.
pub fn compose(draft: &VkTurnDraft, name: &str) -> (VkTurnConfig, ProxyProfile) {
    let listen_port = match port(&draft.listen_port) {
        0 => DEFAULT_FREETURN_PORT,
        p => p,
    };
    let peer_port = int(&draft.peer_port).unwrap_or(0);
    let mtu = int(&draft.wg_mtu).unwrap_or(0);
    let outbound = or(&draft.outbound, OUTBOUND_WIREGUARD).to_string();
    // WireGuard/AmneziaWG ride UDP (udprelay); a proxy exit is TCP (tcpfwd).
    let mode = if outbound == OUTBOUND_PROXY { "tcp" } else { "udp" };
    let wg_b64 = match outbound.as_str() {
        OUTBOUND_WIREGUARD => URL_SAFE_NO_PAD.encode(build_wg_conf(draft, listen_port, false)),
        OUTBOUND_AMNEZIAWG => URL_SAFE_NO_PAD.encode(build_wg_conf(draft, listen_port, true)),
        _ => String::new(),
    };
    let uri = build_uri(draft, peer_port, &wg_b64, mode);
    let tag = or(name, "VK-TURN").to_string();

    let proxy = match outbound.as_str() {
        OUTBOUND_AMNEZIAWG => ProxyProfile {
            tag,
            kind: TYPE_AMNEZIAWG.into(),
            server: draft.peer_host.trim().into(),
            server_port: peer_port,
            awg_config: build_wg_conf(draft, listen_port, true),
            ..Default::default()
        },
        OUTBOUND_PROXY => {
            // Dial the local freeturn TCP listener; SNI stays the real server (a link without sni=
            // would otherwise present "127.0.0.1" and the relayed TLS handshake is reset).
            let base = share_link::parse(draft.outbound_proxy_link.trim()).unwrap_or_default();
            let tag = if name.trim().is_empty() { or(&base.tag, "VK-TURN").to_string() } else { name.to_string() };
            let sni = if base.sni.trim().is_empty() { base.server.clone() } else { base.sni.clone() };
            ProxyProfile { tag, sni, server: "127.0.0.1".into(), server_port: listen_port, ..base }
        }
        _ => {
            let mut o = Map::new();
            o.insert("type".into(), json!("wireguard"));
            o.insert("server".into(), json!("127.0.0.1"));
            o.insert("server_port".into(), json!(listen_port));
            let addr = draft.wg_address.trim();
            o.insert("local_address".into(), if addr.is_empty() { json!([]) } else { json!([addr]) });
            o.insert("private_key".into(), json!(draft.wg_private_key.trim()));
            o.insert("peer_public_key".into(), json!(draft.wg_peer_public_key.trim()));
            if mtu > 0 {
                o.insert("mtu".into(), json!(mtu));
            }
            ProxyProfile {
                tag,
                kind: "wireguard".into(),
                server: draft.peer_host.trim().into(),
                server_port: peer_port,
                raw_outbound: Some(Value::Object(o).to_string()),
                ..Default::default()
            }
        }
    };

    let vkturn = VkTurnConfig {
        uri,
        vk_link: draft.vk_link.trim().into(),
        listen_port,
        streams: positive(&draft.streams),
        chain_proxy_link: draft.chain_proxy_link.trim().into(),
        outbound_proxy_link: if outbound == OUTBOUND_PROXY { draft.outbound_proxy_link.trim().into() } else { String::new() },
        outbound,
        proxy_core: draft.proxy_core,
        core: or(&draft.core, CORE_FREETURN).into(),
        wdtt_peer: draft.wdtt_peer.trim().into(),
        wdtt_port: port(&draft.wdtt_port),
        wdtt_password: draft.wdtt_password.trim().into(),
        wdtt_fingerprint: draft.wdtt_fingerprint.trim().into(),
        wdtt_workers: positive(&draft.wdtt_workers),
        wdtt_plus: draft.wdtt_plus.clone(),
        csqtt_peer: draft.csqtt_peer.trim().into(),
        csqtt_port: port(&draft.csqtt_port),
        csqtt_password: draft.csqtt_password.trim().into(),
        csqtt_workers: positive(&draft.csqtt_workers),
        csqtt: draft.csqtt.clone(),
        freeturn_multi_server: draft.freeturn_multi_server,
        extra_freeturn_uris: draft
            .extra_freeturn_uris
            .split('\n')
            .map(str::trim)
            .filter(|l| l.len() >= FREETURN_SCHEME.len() && l[..FREETURN_SCHEME.len()].eq_ignore_ascii_case(FREETURN_SCHEME))
            .map(str::to_string)
            .collect(),
        ..Default::default()
    };
    (vkturn, proxy)
}

/// The WireGuard profile of an EXTRA freeturn:// server dialing `local_port` (multi-server
/// load-balancer); `None` when the link carries no usable WireGuard config.
pub fn freeturn_uri_to_wg_profile(uri: &str, local_port: i32, name: &str) -> Option<ProxyProfile> {
    let t = uri.trim();
    if !(t.len() >= FREETURN_SCHEME.len() && t[..FREETURN_SCHEME.len()].eq_ignore_ascii_case(FREETURN_SCHEME)) {
        return None;
    }
    let vk = VkTurnConfig { uri: t.into(), outbound: OUTBOUND_WIREGUARD.into(), ..Default::default() };
    let draft = VkTurnDraft { listen_port: local_port.to_string(), outbound: OUTBOUND_WIREGUARD.into(), ..decompose(Some(&vk), None) };
    if draft.wg_private_key.trim().is_empty() || draft.wg_peer_public_key.trim().is_empty() {
        return None;
    }
    Some(compose(&draft, name).1)
}

/// Largest WireGuard/AmneziaWG MTU that survives the VK TURN path (a larger one fails silently).
pub const VKTURN_MAX_WG_MTU: i32 = 1200;

/// `profile` with its WG/AWG MTU clamped to `max_mtu` (a smaller server value kept, a missing one
/// filled in). Proxy exits are returned untouched.
pub fn clamp_vkturn_mtu(profile: &ProxyProfile, max_mtu: i32) -> ProxyProfile {
    let mut result = profile.clone();
    if max_mtu <= 0 {
        return result;
    }
    if let Some(raw) = profile.raw_outbound.as_deref().filter(|s| !s.trim().is_empty()) {
        if let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(raw) {
            if obj.get("type").and_then(Value::as_str) == Some("wireguard") {
                let current = obj.get("mtu").and_then(Value::as_i64).unwrap_or(0) as i32;
                let clamped = (if current > 0 { current } else { max_mtu }).min(max_mtu);
                if clamped != current {
                    let mut patched: Map<String, Value> = obj.into_iter().filter(|(k, _)| k != "mtu").collect();
                    patched.insert("mtu".into(), json!(clamped));
                    result.raw_outbound = Some(Value::Object(patched).to_string());
                }
            }
        }
    }
    if !profile.awg_config.trim().is_empty() {
        if let Some(c) = clamp_wg_conf_mtu(&profile.awg_config, max_mtu) {
            result.awg_config = c;
        }
    }
    result
}

/// The `MTU = …` of a wg-quick INI capped at `max_mtu` (added after `[Interface]` when absent);
/// `None` when nothing changed.
pub fn clamp_wg_conf_mtu(conf: &str, max_mtu: i32) -> Option<String> {
    let mut seen = false;
    let mut changed = false;
    let mut out: Vec<String> = conf
        .split('\n')
        .map(|raw| {
            let line = raw.trim().trim_end_matches('\r');
            let Some(eq) = line.find('=').filter(|e| *e > 0) else { return raw.to_string() };
            if !line[..eq].trim().eq_ignore_ascii_case("MTU") {
                return raw.to_string();
            }
            seen = true;
            let current: i32 = line[eq + 1..].trim().parse().unwrap_or(0);
            let clamped = (if current > 0 { current } else { max_mtu }).min(max_mtu);
            if clamped == current {
                raw.to_string()
            } else {
                changed = true;
                format!("MTU = {clamped}")
            }
        })
        .collect();
    if !seen {
        let at = out.iter().position(|l| l.trim().eq_ignore_ascii_case("[Interface]"))?;
        out.insert(at + 1, format!("MTU = {max_mtu}"));
        changed = true;
    }
    changed.then(|| out.join("\n"))
}

/// Reconstructs the editable draft from a stored config + exit profile.
pub fn decompose(vkturn: Option<&VkTurnConfig>, proxy: Option<&ProxyProfile>) -> VkTurnDraft {
    let mut d = VkTurnDraft::default();
    if let Some(vk) = vkturn {
        let uri = vk.uri.trim();
        if uri.len() >= FREETURN_SCHEME.len() && uri[..FREETURN_SCHEME.len()].eq_ignore_ascii_case(FREETURN_SCHEME) {
            apply_uri(&mut d, uri);
        }
        d.vk_link = vk.vk_link.clone();
        d.listen_port = vk.listen_port.to_string();
        d.streams = positive_text(vk.streams);
        d.chain_proxy_link = vk.chain_proxy_link.clone();
        d.outbound = or(&vk.outbound, OUTBOUND_WIREGUARD).into();
        d.outbound_proxy_link = vk.outbound_proxy_link.clone();
        d.proxy_core = vk.proxy_core;
        d.core = or(&vk.core, CORE_FREETURN).into();
        d.wdtt_peer = vk.wdtt_peer.clone();
        d.wdtt_port = positive_text(vk.wdtt_port);
        d.wdtt_password = vk.wdtt_password.clone();
        d.wdtt_fingerprint = or(&vk.wdtt_fingerprint, "chrome").into();
        d.wdtt_workers = positive_text(vk.wdtt_workers);
        d.wdtt_plus = vk.wdtt_plus.clone();
        d.csqtt_peer = vk.csqtt_peer.clone();
        d.csqtt_port = positive_text(vk.csqtt_port);
        d.csqtt_password = vk.csqtt_password.clone();
        d.csqtt_workers = positive_text(vk.csqtt_workers);
        d.csqtt = vk.csqtt.clone();
        d.freeturn_multi_server = vk.freeturn_multi_server;
        d.extra_freeturn_uris = vk.extra_freeturn_uris.join("\n");
    }
    // The stored outbound is authoritative for the keys/address/mtu actually dialled.
    let outbound = vkturn.map(|v| v.outbound.as_str());
    match outbound {
        Some(OUTBOUND_AMNEZIAWG) => {
            if let Some(p) = proxy {
                apply_wg_conf(&mut d, &p.awg_config, true);
            }
        }
        Some(OUTBOUND_PROXY) => {}
        _ => {
            if let Some(Ok(Value::Object(o))) = proxy.and_then(|p| p.raw_outbound.as_deref()).map(serde_json::from_str::<Value>) {
                if let Some(v) = o.get("private_key").and_then(Value::as_str) {
                    d.wg_private_key = v.into();
                }
                if let Some(v) = o.get("peer_public_key").and_then(Value::as_str) {
                    d.wg_peer_public_key = v.into();
                }
                if let Some(v) = o.get("local_address").and_then(Value::as_array).and_then(|a| a.first()).and_then(Value::as_str) {
                    d.wg_address = v.into();
                }
                if let Some(v) = o.get("mtu").and_then(Value::as_i64) {
                    d.wg_mtu = v.to_string();
                }
            }
        }
    }
    // A proxy exit's server was rewritten to 127.0.0.1: keep the peer from the URI then.
    if let Some(p) = proxy {
        if !p.server.trim().is_empty() && outbound != Some(OUTBOUND_PROXY) {
            d.peer_host = p.server.clone();
            d.peer_port = p.server_port.to_string();
        }
    }
    d
}

fn build_uri(d: &VkTurnDraft, peer_port: i32, wg_b64: &str, mode: &str) -> String {
    let mut params: Vec<String> = Vec::new();
    if !mode.trim().is_empty() {
        params.push(format!("mode={}", mode.trim()));
    }
    if !d.obf_profile.trim().is_empty() {
        params.push(format!("obf-profile={}", d.obf_profile.trim()));
    }
    // bond=1 is no longer written: free-turn-proxy 3.2.0 dropped byte striping.
    if !wg_b64.is_empty() {
        params.push(format!("wg={wg_b64}"));
    }
    let transport = d.transport.trim();
    let transport = if params.is_empty() { transport.to_string() } else { format!("{transport}<{}>", params.join("&")) };
    let mut s = format!("{FREETURN_SCHEME}{}", or(&d.provider, "vk"));
    if !transport.trim().is_empty() {
        s += &format!("?{transport}");
    }
    let host = d.peer_host.trim();
    if !host.is_empty() {
        s += &format!("@{host}");
        if peer_port > 0 {
            s += &format!(":{peer_port}");
        }
    }
    if !d.obf_key.trim().is_empty() {
        s += &format!("#{}", d.obf_key.trim());
    }
    if !d.comment.trim().is_empty() {
        s += &format!("${}", d.comment.trim());
    }
    s
}

fn apply_uri(d: &mut VkTurnDraft, uri: &str) {
    let mut s = uri.trim()[FREETURN_SCHEME.len()..].to_string();
    if let Some(i) = s.find('$') {
        d.comment = s[i + 1..].into();
        s.truncate(i);
    }
    if let Some(i) = s.find('#') {
        d.obf_key = s[i + 1..].into();
        s.truncate(i);
    }
    if let Some(i) = s.rfind('@') {
        let peer = s[i + 1..].to_string();
        s.truncate(i);
        match split_host_port(&peer) {
            Some((h, p)) => {
                d.peer_host = h;
                d.peer_port = p.to_string();
            }
            None => d.peer_host = peer,
        }
    }
    let (provider, transport) = match s.split_once('?') {
        Some((a, b)) => (a.to_string(), Some(b.to_string())),
        None => (s.clone(), None),
    };
    d.provider = or(&provider, "vk").into();
    if let Some(t) = transport {
        match (t.find('<'), t.rfind('>')) {
            (Some(open), Some(close)) if close > open => {
                d.transport = t[..open].into();
                let params = parse_query(&t[open + 1..close]);
                if let Some(v) = params.get("mode") {
                    d.mode = v.clone();
                }
                if let Some(v) = params.get("obf-profile") {
                    d.obf_profile = v.clone();
                }
                if let Some(conf) = params.get("wg").and_then(|w| decode_base64_chunk(w)) {
                    apply_wg_conf(d, &conf, false);
                }
            }
            _ => d.transport = t,
        }
    }
}

/// WG keys/address/mtu/DNS/keepalive/AllowedIPs (+ AmneziaWG knobs when `awg`) from a wg-quick INI.
fn apply_wg_conf(d: &mut VkTurnDraft, conf: &str, awg: bool) {
    for raw in share_link::lines(conf) {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('[') || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=').filter(|(k, _)| !k.is_empty()) else { continue };
        let v = v.trim().to_string();
        let first = || v.split(',').next().unwrap_or("").trim().to_string();
        match k.trim().to_lowercase().as_str() {
            "privatekey" => d.wg_private_key = v,
            "publickey" => d.wg_peer_public_key = v,
            "address" => d.wg_address = first(),
            "mtu" => d.wg_mtu = v,
            "dns" => d.wg_dns = v,
            "persistentkeepalive" => d.wg_keepalive = v,
            "allowedips" => d.wg_allowed_ips = v,
            "jc" if awg => d.awg_jc = v,
            "jmin" if awg => d.awg_jmin = v,
            "jmax" if awg => d.awg_jmax = v,
            "s1" if awg => d.awg_s1 = v,
            "s2" if awg => d.awg_s2 = v,
            "h1" if awg => d.awg_h1 = v,
            "h2" if awg => d.awg_h2 = v,
            "h3" if awg => d.awg_h3 = v,
            "h4" if awg => d.awg_h4 = v,
            _ => {}
        }
    }
}

/// The wg-quick INI carried as `wg=` (with the AmneziaWG knobs when `awg`).
fn build_wg_conf(d: &VkTurnDraft, listen_port: i32, awg: bool) -> String {
    let mut s = format!("[Interface]\nPrivateKey = {}\nAddress = {}\n", d.wg_private_key.trim(), d.wg_address.trim());
    if !d.wg_dns.trim().is_empty() {
        s += &format!("DNS = {}\n", d.wg_dns.trim());
    }
    if !d.wg_mtu.trim().is_empty() {
        s += &format!("MTU = {}\n", d.wg_mtu.trim());
    }
    if awg {
        for (k, v, def) in [
            ("Jc", &d.awg_jc, "4"), ("Jmin", &d.awg_jmin, "40"), ("Jmax", &d.awg_jmax, "70"), ("S1", &d.awg_s1, "0"),
            ("S2", &d.awg_s2, "0"), ("H1", &d.awg_h1, "1"), ("H2", &d.awg_h2, "2"), ("H3", &d.awg_h3, "3"), ("H4", &d.awg_h4, "4"),
        ] {
            s += &format!("{k} = {}\n", or(v, def));
        }
    }
    s += &format!(
        "\n[Peer]\nPublicKey = {}\nEndpoint = 127.0.0.1:{listen_port}\nAllowedIPs = {}\n",
        d.wg_peer_public_key.trim(),
        or(&d.wg_allowed_ips, "0.0.0.0/0")
    );
    if !d.wg_keepalive.trim().is_empty() {
        s += &format!("PersistentKeepalive = {}\n", d.wg_keepalive.trim());
    }
    s
}
