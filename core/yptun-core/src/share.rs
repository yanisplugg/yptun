//! Sharing: proxy profile → share link (port of `ShareLinkComposer`) and the "Share" button's link
//! for a whole location (port of `ConfigShareService`).

use std::collections::BTreeMap;

use base64::engine::general_purpose::{STANDARD_NO_PAD, URL_SAFE_NO_PAD};
use base64::Engine;
use serde_json::json;

use crate::location::*;
use crate::model::*;
use crate::panel;
use crate::uri::percent_encode;

// ---------------- ProxyProfile → link ----------------

/// A share string for `p` (the reverse of [`crate::share_link::parse`]), or `None`.
pub fn compose(p: &ProxyProfile) -> Option<String> {
    if p.kind == TYPE_AMNEZIAWG {
        let ini = p.awg_config.trim();
        return (!ini.is_empty()).then(|| format!("amneziawg://{}", URL_SAFE_NO_PAD.encode(&p.awg_config)));
    }
    if p.kind == TYPE_TRUSTTUNNEL {
        return (!p.tt_config.trim().is_empty()).then(|| p.tt_config.clone());
    }
    if let Some(raw) = p.raw_xray_config.as_ref().filter(|s| !s.trim().is_empty()) {
        return Some(raw.clone());
    }
    match p.kind.as_str() {
        TYPE_VLESS => Some(format!("vless://{}@{}:{}?{}{}", percent_encode(&p.uuid), p.server, p.server_port, stream_query(p, true), remark(&p.tag))),
        TYPE_TROJAN => Some(format!("trojan://{}@{}:{}?{}{}", percent_encode(&p.password), p.server, p.server_port, stream_query(p, false), remark(&p.tag))),
        TYPE_SHADOWSOCKS => {
            let userinfo = URL_SAFE_NO_PAD.encode(format!("{}:{}", p.method, p.password));
            Some(format!("ss://{userinfo}@{}:{}{}", p.server, p.server_port, remark(&p.tag)))
        }
        TYPE_VMESS => Some(compose_vmess(p)),
        TYPE_HYSTERIA2 => Some(compose_hysteria2(p)),
        TYPE_NAIVE => Some(compose_naive(p)),
        _ => p.raw_outbound.clone().filter(|s| !s.trim().is_empty()),
    }
}

fn remark(tag: &str) -> String {
    if tag.trim().is_empty() {
        String::new()
    } else {
        format!("#{}", percent_encode(tag))
    }
}

fn query(params: &[(&str, String)]) -> String {
    params.iter().map(|(k, v)| format!("{k}={}", percent_encode(v))).collect::<Vec<_>>().join("&")
}

fn compose_naive(p: &ProxyProfile) -> String {
    let mut s = String::from(if p.naive_quic { "naive+quic://" } else { "naive+https://" });
    if !p.username.trim().is_empty() || !p.password.trim().is_empty() {
        s += &format!("{}:{}@", percent_encode(&p.username), percent_encode(&p.password));
    }
    s += &format!("{}:{}", p.server, p.server_port);
    if !p.sni.trim().is_empty() {
        s += &format!("?sni={}", percent_encode(&p.sni));
    }
    s + &remark(&p.tag)
}

fn compose_hysteria2(p: &ProxyProfile) -> String {
    let mut s = String::from("hysteria2://");
    if !p.password.trim().is_empty() {
        s += &format!("{}@", percent_encode(&p.password));
    }
    s += &format!("{}:{}", p.server, p.server_port);
    let mut params: Vec<(&str, String)> = Vec::new();
    let mut add = |k, v: &str| {
        if !v.trim().is_empty() {
            params.push((k, v.to_string()))
        }
    };
    add("sni", &p.sni);
    add("insecure", if p.allow_insecure { "1" } else { "" });
    add("alpn", &p.alpn.join(","));
    add("obfs", &p.hy2_obfs);
    add("obfs-password", &p.hy2_obfs_password);
    add("mport", &p.hy2_ports);
    if p.hy2_up_mbps > 0 {
        params.push(("up", p.hy2_up_mbps.to_string()));
    }
    if p.hy2_down_mbps > 0 {
        params.push(("down", p.hy2_down_mbps.to_string()));
    }
    if !params.is_empty() {
        s += &format!("/?{}", query(&params));
    }
    s + &remark(&p.tag)
}

fn compose_vmess(p: &ProxyProfile) -> String {
    // v2rayN vmess://base64(json) layout, keys in the Kotlin order.
    let obj = json!({
        "v": "2", "ps": p.tag, "add": p.server, "port": p.server_port.to_string(), "id": p.uuid,
        "aid": p.alter_id.to_string(), "scy": p.cipher, "net": network_name(&p.network), "type": "none",
        "host": p.host, "path": p.path, "tls": if p.security == SECURITY_NONE { "" } else { "tls" },
        "sni": p.sni, "alpn": p.alpn.join(","),
    });
    format!("vmess://{}", STANDARD_NO_PAD.encode(obj.to_string()))
}

/// The shared `type/security/sni/...` query of vless & trojan links.
fn stream_query(p: &ProxyProfile, vless: bool) -> String {
    let mut params: Vec<(&str, String)> = vec![("type", network_name(&p.network).to_string())];
    let mut add = |k, v: &str| {
        if !v.trim().is_empty() {
            params.push((k, v.to_string()))
        }
    };
    add("security", if p.security == SECURITY_NONE { "" } else { &p.security });
    add("sni", &p.sni);
    add("fp", &p.fingerprint);
    add("alpn", &p.alpn.join(","));
    add("flow", if vless { &p.flow } else { "" });
    add("pbk", &p.reality_public_key);
    add("sid", &p.reality_short_id);
    add("allowInsecure", if p.allow_insecure { "1" } else { "" });
    add("host", &p.host);
    add(if p.network == NETWORK_GRPC { "serviceName" } else { "path" }, &p.path);
    add("encryption", if vless { &p.vless_encryption } else { "" });
    add("mode", &p.xhttp_mode);
    add("extra", &p.xhttp_extra);
    add("fm", &p.final_mask);
    add("pcs", &p.pinned_cert_sha256);
    add("vcn", &p.verify_cert_by_name);
    add("ech", &p.ech_config_list);
    add("pqv", &p.reality_mldsa65_verify);
    add("spx", &p.reality_spider_x);
    query(&params)
}

fn network_name(network: &str) -> &'static str {
    match network {
        NETWORK_WS => "ws",
        NETWORK_GRPC => "grpc",
        NETWORK_HTTP => "http",
        NETWORK_HTTPUPGRADE => "httpupgrade",
        NETWORK_XHTTP => "xhttp",
        _ => "tcp",
    }
}

// ---------------- location → link ----------------

/// The link the "Share" button shows. `universal` = yptun:// (carries the whole inbound); otherwise
/// the engine's own link. MasterDNS/OpenFlux/Snolc have none and always use yptun://.
pub fn share_link(config: &LocationConfig, universal: bool) -> String {
    let has_own_link = !matches!(config.engine, EngineType::MasterDns | EngineType::OpenFlux | EngineType::Snolc);
    if universal || !has_own_link {
        panel::compose_inbound(config)
    } else {
        olcrtc_uri(config)
    }
}

/// The engine's own link: freeturn/qwdtt/csqtt for VK-TURN, the proxy link for Standard/Chain,
/// olcrtc:// (all rooms) otherwise.
pub fn olcrtc_uri(config: &LocationConfig) -> String {
    let n = config.normalized();
    if n.engine == EngineType::VkTurn {
        return match &n.vkturn {
            Some(vk) if vk.uses_wdtt() => panel::compose_qwdtt(&n.name, vk),
            Some(vk) if vk.uses_csqtt() => panel::compose_csqtt(vk, &n.name),
            Some(vk) => vk.uri.clone(),
            None => String::new(),
        };
    }
    if matches!(n.engine, EngineType::Standard | EngineType::Chain) {
        if let Some(link) = n.proxy.as_ref().and_then(compose).filter(|s| !s.trim().is_empty()) {
            return link;
        }
    }
    let mut s = format!("olcrtc://{}", room_link(&n, &n.bypass_provider, &n.transport, &n.id, &n.key));
    if n.uses_multi_room() {
        for r in n.extra_rooms.iter().filter(|r| !r.room.trim().is_empty() && !r.key.trim().is_empty()).take(MAX_EXTRA_ROOMS) {
            let provider = if r.provider.trim().is_empty() { &n.bypass_provider } else { &r.provider };
            let transport = if r.transport.trim().is_empty() { &n.transport } else { &r.transport };
            s.push('!');
            s += &room_link(&n, provider, transport, &r.room, &r.key);
        }
    }
    let name = n.display_name();
    if !name.trim().is_empty() && name != n.id {
        s.push('$');
        s += &name;
    }
    s
}

/// `provider?transport@room#key` — one room of an olcrtc:// link.
fn room_link(c: &LocationConfig, provider: &str, transport: &str, room: &str, key: &str) -> String {
    let t = match transport {
        TRANSPORT_VP8CHANNEL => format!("vp8channel<vp8-fps={}&vp8-batch={}>", c.vp8_fps, c.vp8_batch),
        TRANSPORT_SEICHANNEL => {
            let o = c.sei_options();
            format!("seichannel<fps={}&batch={}&frag={}&ack-ms={}>", o.fps, o.batch, o.fragment_size, o.ack_timeout_ms)
        }
        TRANSPORT_VIDEOCHANNEL => {
            let o = c.video_options();
            format!(
                "videochannel<video-w={}&video-h={}&video-fps={}&video-codec={}&video-qr-size={}&video-qr-recovery={}&video-tile-module={}&video-tile-rs={}>",
                o.width, o.height, o.fps, o.codec, o.qr_size, o.qr_recovery, o.tile_module, o.tile_rs
            )
        }
        other => other.to_string(),
    };
    format!("{provider}?{t}@{room}#{key}")
}

// ---------------- subscriptions ----------------

pub fn subscription_qr_text(url: &str) -> String {
    url.trim().to_string()
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct SubscriptionShareItem {
    pub url: String,
    pub name: String,
    pub update_interval_hours: Option<i32>,
    pub last_refresh_at_epoch_ms: Option<i64>,
    pub location_count: usize,
    pub support_url: Option<String>,
    pub web_page_url: Option<String>,
    pub announce: Option<String>,
}

/// One item per distinct http(s) subscription URL, sorted by URL.
pub fn subscription_share_items(entries: &[LocationEntry]) -> Vec<SubscriptionShareItem> {
    let mut groups: BTreeMap<String, Vec<&LocationEntry>> = BTreeMap::new();
    for e in entries {
        let Some(url) = e.subscription_url.as_deref().map(str::trim) else { continue };
        if url.starts_with("https://") || url.starts_with("http://") {
            groups.entry(url.to_string()).or_default().push(e);
        }
    }
    groups
        .into_iter()
        .map(|(url, list)| {
            let first = list[0];
            let meta = list.iter().find_map(|e| e.metadata.as_ref().and_then(|m| m.subscription.clone()));
            let name = meta
                .as_ref()
                .and_then(|m| m.name.clone())
                .filter(|s| !s.trim().is_empty())
                .or_else(|| (!first.name.trim().is_empty()).then(|| first.name.clone()))
                .unwrap_or_else(|| first.location().display_name());
            SubscriptionShareItem {
                url,
                name,
                update_interval_hours: meta.as_ref().and_then(|m| m.update_interval_hours),
                last_refresh_at_epoch_ms: meta.as_ref().and_then(|m| m.last_refresh_at_epoch_ms),
                location_count: list.len(),
                support_url: meta.as_ref().and_then(|m| m.support_url.clone()),
                web_page_url: meta.as_ref().and_then(|m| m.web_page_url.clone()),
                announce: meta.and_then(|m| m.announce),
            }
        })
        .collect()
}
