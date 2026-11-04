//! ProxyProfile → Xray-core JSON (port of `XrayConfig`): the typed build, the verbatim-config
//! preparation (`prepare_raw`), the raw-config ping probe, the VK-TURN WireGuard balancer and the
//! xhttp `extra` sanitizer.

use std::collections::BTreeMap;

use serde_json::{json, Map, Value};

use super::blocklist::HOST_REGEXPS;
use super::routing as xr;
use crate::model::*;
use crate::routing::{RoutingProfile, TrafficSettings};
use crate::singbox::config::WarpRelay;
use crate::transports::FakeDnsSpec;

const PROXY_TAG: &str = "proxy";
const PROXY_BASE_TAG: &str = "proxy-base";
/// The exit added on top of a VERBATIM config (whose own main may already be "proxy").
const CASCADE_EXIT_TAG: &str = "cascade-exit";
/// SOCKS loopback that chains a 2nd proxy over an xhttp main (which cannot be a sub-dialer).
const CASCADE_LOOP_IN_TAG: &str = "cascade-loop-in";
const CASCADE_LOOP_OUT_TAG: &str = "cascade-loop-out";
const OLCRTC_TAG: &str = "olcrtc-out";
const WG_BASE_TAG: &str = "wireguard-base";
const WARP_RELAY_IN_TAG: &str = "warp-relay-in";
const WARP_OUT_TAG: &str = "warp-out";
const FAKEDNS_SERVER: &str = "fakedns";
const FAKEDNS_POOL: &str = "198.18.0.0/15";
const FAKEDNS_POOL6: &str = "fc00::/18";
const FAKEDNS_POOL_SIZE: i32 = 65535;
const WG_BAL_PREFIX: &str = "wg-bal-";
const WG_BALANCER_TAG: &str = "vk-bal";
const LAN_CIDRS: [&str; 5] = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16"];
const PROXY_PROTOCOLS: [&str; 4] = ["vless", "vmess", "trojan", "shadowsocks"];

type Obj = Map<String, Value>;

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

fn str_of<'a>(o: &'a Obj, k: &str) -> Option<&'a str> {
    o.get(k).and_then(Value::as_str)
}

fn parse_object(text: &str) -> Option<Obj> {
    match serde_json::from_str::<Value>(text.trim()) {
        Ok(Value::Object(o)) => Some(o),
        _ => None,
    }
}

fn pretty(v: &Value) -> String {
    serde_json::to_string_pretty(v).unwrap()
}

// ---------------- DNS helpers ----------------

/// `domainStrategy` of the `direct` freedom: Force* also refuses a wrong-family IP literal.
fn direct_domain_strategy(t: &TrafficSettings) -> &'static str {
    if t.domain_strategy == "ipv6_only" {
        "ForceIPv6"
    } else {
        "ForceIPv4"
    }
}

/// Providers serving DoH on their IP with an IP-SAN certificate (no bootstrap needed).
const DOH_IP_PROVIDERS: [&str; 8] = ["8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "223.5.5.5", "223.6.6.6"];
const DOH_HOST_IPS: [(&str, &str); 5] =
    [("cloudflare-dns.com", "1.1.1.1"), ("one.one.one.one", "1.1.1.1"), ("dns.google", "8.8.8.8"), ("dns.quad9.net", "9.9.9.9"), ("dns.alidns.com", "223.5.5.5")];

fn starts_ci(s: &str, p: &str) -> bool {
    s.len() >= p.len() && s.is_char_boundary(p.len()) && s[..p.len()].eq_ignore_ascii_case(p)
}

/// Xray has no DoT client: `tls://host` becomes DoH on the same host.
fn xray_dns(server: &str) -> String {
    let s = server.trim();
    if !starts_ci(s, "tls://") {
        return pin_known_doh_host(s);
    }
    let rest = s[6..].split('/').next().unwrap_or("");
    let host = if rest.starts_with('[') { format!("{}]", rest.split(']').next().unwrap_or("")) } else { rest.split(':').next().unwrap_or("").to_string() };
    pin_known_doh_host(&format!("https://{host}/dns-query"))
}

/// `https://cloudflare-dns.com/dns-query` → `https://1.1.1.1/dns-query` (no bootstrap lookup).
fn pin_known_doh_host(s: &str) -> String {
    if !starts_ci(s, "https://") {
        return s.into();
    }
    let rest = &s[8..];
    let (host, path) = match rest.split_once('/') {
        Some((h, p)) => (h.to_lowercase(), p),
        None => (rest.to_lowercase(), ""),
    };
    match DOH_HOST_IPS.iter().find(|(h, _)| *h == host) {
        Some((_, ip)) if path.is_empty() || path == "dns-query" => format!("https://{ip}/dns-query"),
        _ => s.into(),
    }
}

/// A proxied resolver that survives exits blocking port 53: major IPs → DoH, other IPs → TCP.
fn proxied_dns(server: &str) -> String {
    let s = xray_dns(server);
    if s.is_empty() || s.contains("://") || s == FAKEDNS_SERVER {
        return s;
    }
    if DOH_IP_PROVIDERS.contains(&s.as_str()) {
        return format!("https://{s}/dns-query");
    }
    let is_v4 = s.chars().all(|c| c.is_ascii_digit() || c == '.') && s.matches('.').count() == 3;
    let is_v6 = s.contains(':') && s.chars().all(|c| c.is_ascii_hexdigit() || c == ':');
    if is_v4 || is_v6 {
        format!("tcp://{s}")
    } else {
        s
    }
}

/// Hosts of the REMOTE resolvers as (`full:` domains, IPs), pinned to the proxy by routing.
pub fn remote_dns_endpoints(t: &TrafficSettings) -> (Vec<String>, Vec<String>) {
    let (mut domains, mut ips): (Vec<String>, Vec<String>) = (vec![], vec![]);
    for raw in [&t.remote_dns, &t.remote_dns2] {
        let s = proxied_dns(raw);
        let s = s.trim();
        if s.is_empty() || s == FAKEDNS_SERVER || s.eq_ignore_ascii_case("localhost") {
            continue;
        }
        let (scheme, rest) = s.split_once("://").unwrap_or(("", s));
        if scheme.contains("+local") {
            continue;
        }
        let authority = rest.split('/').next().unwrap_or("");
        let host = if authority.starts_with('[') {
            authority[1..].split(']').next().unwrap_or("").to_string()
        } else if authority.matches(':').count() == 1 {
            authority.split(':').next().unwrap_or("").to_string()
        } else {
            authority.to_string()
        };
        if host.trim().is_empty() {
            continue;
        }
        let is_ip = host.contains(':') || host.chars().all(|c| c.is_ascii_digit() || c == '.');
        let push = |v: &mut Vec<String>, x: String| {
            if !v.contains(&x) {
                v.push(x)
            }
        };
        if !is_ip {
            push(&mut domains, format!("full:{}", host.to_lowercase()));
        } else if !is_private_dns_host(&host) {
            push(&mut ips, host);
        }
    }
    (domains, ips)
}

fn is_private_dns_host(ip: &str) -> bool {
    if ip.contains(':') {
        return ip == "::1" || starts_ci(ip, "fe80") || ip.starts_with("fc") || ip.starts_with("fd");
    }
    let p: Vec<i32> = ip.split('.').filter_map(|x| x.parse().ok()).collect();
    if p.len() != 4 {
        return false;
    }
    p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && (16..=31).contains(&p[1])) || (p[0] == 169 && p[1] == 254)
}

// ---------------- FakeDNS building blocks ----------------

/// DNS (53 both networks, TCP/853) from the app's own SOCKS inbound → `dns-out`.
fn dns_hijack_rules() -> Vec<Value> {
    vec![
        json!({ "type": "field", "inboundTag": ["socks-in"], "port": 53, "outboundTag": "dns-out" }),
        json!({ "type": "field", "inboundTag": ["socks-in"], "network": "tcp", "port": 853, "outboundTag": "dns-out" }),
    ]
}

fn dns_out_outbound() -> Value {
    json!({ "tag": "dns-out", "protocol": "dns", "settings": { "nonIPQuery": "skip" } })
}

fn fake_dns_pool(ip_pool: Option<&str>) -> Value {
    json!({ "ipPool": ip_pool.filter(|p| not_blank(p)).unwrap_or(FAKEDNS_POOL), "poolSize": FAKEDNS_POOL_SIZE })
}

/// `sniffing` with `fakedns` ensured in destOverride.
fn sniffing_with_fake_dns(sniffing: Option<&Obj>) -> Value {
    let mut existing: Vec<String> = sniffing
        .and_then(|s| s.get("destOverride"))
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_else(|| vec!["http".into(), "tls".into(), "quic".into()]);
    let mut m = Obj::new();
    if let Some(s) = sniffing {
        for (k, v) in s {
            if k != "destOverride" && k != "enabled" {
                m.insert(k.clone(), v.clone());
            }
        }
    }
    m.insert("enabled".into(), json!(true));
    if !existing.iter().any(|x| x == "fakedns") {
        existing.push("fakedns".into());
    }
    m.insert("destOverride".into(), json!(existing));
    Value::Object(m)
}

fn socks_inbound(host: &str, port: i32, user: &str, pass: &str) -> Obj {
    let mut settings = Obj::new();
    settings.insert("udp".into(), json!(true));
    if not_blank(user) {
        settings.insert("auth".into(), json!("password"));
        settings.insert("accounts".into(), json!([{ "user": user, "pass": pass }]));
    } else {
        settings.insert("auth".into(), json!("noauth"));
    }
    let mut m = Obj::new();
    m.insert("tag".into(), json!("socks-in"));
    m.insert("listen".into(), json!(host));
    m.insert("port".into(), json!(port));
    m.insert("protocol".into(), json!("socks"));
    m.insert("settings".into(), Value::Object(settings));
    m
}

fn loop_inbound(port: i32) -> Value {
    json!({ "tag": CASCADE_LOOP_IN_TAG, "listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": { "udp": true, "auth": "noauth" } })
}

fn loop_outbound(port: i32) -> Value {
    json!({ "tag": CASCADE_LOOP_OUT_TAG, "protocol": "socks", "settings": { "servers": [{ "address": "127.0.0.1", "port": port }] } })
}

fn warp_relay_inbound(r: &WarpRelay) -> Value {
    json!({ "tag": WARP_RELAY_IN_TAG, "listen": "127.0.0.1", "port": r.listen_port, "protocol": "dokodemo-door",
        "settings": { "address": r.target_host, "port": r.target_port, "network": "udp" } })
}

// ---------------- build ----------------

/// The typed build's options (Kotlin named parameters, same defaults).
#[derive(Debug, Clone)]
pub struct XrayOptions {
    pub listen_port: i32,
    pub listen_host: String,
    pub socks_username: String,
    pub socks_password: String,
    pub olcrtc_chain_port: Option<i32>,
    pub olcrtc_chain_user: String,
    pub olcrtc_chain_pass: String,
    pub log_level: String,
    pub traffic: TrafficSettings,
    pub wireguard_base: Option<ProxyProfile>,
    pub routing_profile: Option<RoutingProfile>,
    pub block_quic: bool,
    pub force_family_resolve: bool,
    /// Socket-level chaining (keeps vless+reality/vision intact over MasterDNS).
    pub chain_via_dialer_proxy: bool,
    pub direct_via_base: bool,
    pub bypass_lan: bool,
    pub second_profile: Option<ProxyProfile>,
    pub warp_relay: Option<WarpRelay>,
    /// WARP client mode: keep XTLS Vision flow over the chain.
    pub keep_flow_on_chain: bool,
    pub fake_dns_spec: Option<FakeDnsSpec>,
    pub handshake_timeout_sec: Option<i32>,
}

impl Default for XrayOptions {
    fn default() -> Self {
        Self {
            listen_port: 10808,
            listen_host: "127.0.0.1".into(),
            socks_username: String::new(),
            socks_password: String::new(),
            olcrtc_chain_port: None,
            olcrtc_chain_user: String::new(),
            olcrtc_chain_pass: String::new(),
            log_level: "debug".into(),
            traffic: TrafficSettings::default(),
            wireguard_base: None,
            routing_profile: None,
            block_quic: true,
            force_family_resolve: true,
            chain_via_dialer_proxy: false,
            direct_via_base: false,
            bypass_lan: true,
            second_profile: None,
            warp_relay: None,
            keep_flow_on_chain: false,
            fake_dns_spec: None,
            handshake_timeout_sec: None,
        }
    }
}

/// Knobs of one proxy outbound (the Kotlin `buildProxyOutbound` named parameters).
#[derive(Default, Clone, Copy)]
struct OutOpts<'a> {
    chained: bool,
    detour: Option<&'a str>,
    chain_via_dialer_proxy: bool,
    preserve_flow: bool,
    xhttp_high_concurrency: bool,
    suppress_mux: bool,
}

pub fn build(profile: &ProxyProfile, o: &XrayOptions) -> String {
    pretty(&build_value(profile, o))
}

pub fn build_value(profile: &ProxyProfile, o: &XrayOptions) -> Value {
    let traffic = &o.traffic;
    let rp = o.routing_profile.as_ref();
    let fake_enabled = traffic.fake_dns_enabled || o.fake_dns_spec.is_some();
    let fake_block: Vec<String> = o.fake_dns_spec.as_ref().map(|s| s.block_regex.iter().filter(|r| not_blank(r)).cloned().collect()).unwrap_or_default();
    let main_is_xhttp = profile.network == NETWORK_XHTTP;
    let second_is_warp = o.second_profile.as_ref().is_some_and(|s| s.kind == "wireguard") || o.warp_relay.is_some();
    let loop_active = o.second_profile.as_ref().is_some_and(|s| s.is_complete()) && main_is_xhttp && !second_is_warp;
    let loop_port = o.listen_port + 1;

    let mut config = Obj::new();
    config.insert("log".into(), json!({ "loglevel": o.log_level }));
    if let Some(h) = o.handshake_timeout_sec {
        config.insert("policy".into(), json!({ "levels": { "0": { "handshake": h, "connIdle": 600, "uplinkOnly": 30, "downlinkOnly": 30 } } }));
    }

    // ---- dns ----
    let mut dns = Obj::new();
    let profile_hosts: BTreeMap<String, String> = rp.map(|p| p.dns_hosts.clone()).unwrap_or_default();
    if traffic.block_ru_domains || !profile_hosts.is_empty() || !fake_block.is_empty() {
        let mut hosts = Obj::new();
        if traffic.block_ru_domains {
            for r in HOST_REGEXPS {
                hosts.insert((*r).into(), json!("0.0.0.0"));
            }
        }
        for r in &fake_block {
            hosts.insert(format!("regexp:{r}"), json!("0.0.0.0"));
        }
        for (k, v) in &profile_hosts {
            hosts.insert(k.clone(), json!(v));
        }
        dns.insert("hosts".into(), Value::Object(hosts));
    }
    let mut servers: Vec<String> = Vec::new();
    if fake_enabled {
        servers.push(FAKEDNS_SERVER.into());
    }
    servers.push(proxied_dns(&traffic.remote_dns));
    if not_blank(&traffic.remote_dns2) {
        servers.push(proxied_dns(&traffic.remote_dns2));
    }
    servers.push(xray_dns(&traffic.direct_dns));
    dns.insert("servers".into(), json!(servers));
    dns.insert("queryStrategy".into(), json!(traffic.xray_query_strategy()));
    config.insert("dns".into(), Value::Object(dns));
    if fake_enabled {
        config.insert("fakedns".into(), json!([fake_dns_pool(o.fake_dns_spec.as_ref().map(|s| s.inet4_range.as_str()))]));
    }

    // ---- inbounds ----
    let mut socks = socks_inbound(&o.listen_host, o.listen_port, &o.socks_username, &o.socks_password);
    let expert = rp.is_some_and(|p| p.expert_enabled);
    let mut dest = vec!["http", "tls", "quic"];
    if fake_enabled {
        dest.push("fakedns");
    }
    let mut sniffing = Obj::new();
    sniffing.insert("enabled".into(), json!(if expert { rp.unwrap().xray_sniffing } else { true }));
    sniffing.insert("destOverride".into(), json!(dest));
    if expert && rp.unwrap().xray_route_only {
        sniffing.insert("routeOnly".into(), json!(true));
    }
    socks.insert("sniffing".into(), Value::Object(sniffing));
    let mut inbounds = vec![Value::Object(socks)];
    if let Some(r) = &o.warp_relay {
        inbounds.push(warp_relay_inbound(r));
    }
    if loop_active {
        inbounds.push(loop_inbound(loop_port));
    }
    config.insert("inbounds".into(), Value::Array(inbounds));

    // ---- outbounds ----
    let wg_base = o.wireguard_base.as_ref().and_then(|p| wireguard_base_outbound(p, WG_BASE_TAG, None));
    let base_detour = wg_base.as_ref().map(|_| WG_BASE_TAG);
    let base_exit_tag = if wg_base.is_some() {
        Some(WG_BASE_TAG)
    } else if o.olcrtc_chain_port.is_some() {
        Some(OLCRTC_TAG)
    } else {
        None
    };
    let direct_dials_base = o.direct_via_base && base_exit_tag.is_some();
    let second = o.second_profile.as_ref().filter(|s| s.is_complete());
    let chained = o.olcrtc_chain_port.is_some();
    let mut outbounds: Vec<Value> = Vec::new();
    if let Some(second) = second {
        if o.warp_relay.is_some() {
            outbounds.push(proxy_outbound(second, traffic, PROXY_TAG, OutOpts::default()));
        } else if second.kind == "wireguard" {
            if let Some(wg) = wireguard_base_outbound(second, PROXY_TAG, Some(PROXY_BASE_TAG)) {
                outbounds.push(wg);
            }
        } else {
            outbounds.push(proxy_outbound(
                second,
                traffic,
                PROXY_TAG,
                OutOpts {
                    detour: Some(if loop_active { CASCADE_LOOP_OUT_TAG } else { PROXY_BASE_TAG }),
                    chain_via_dialer_proxy: loop_active,
                    preserve_flow: loop_active,
                    ..Default::default()
                },
            ));
        }
        outbounds.push(proxy_outbound(
            profile,
            traffic,
            PROXY_BASE_TAG,
            OutOpts { chained, detour: base_detour, xhttp_high_concurrency: loop_active, suppress_mux: loop_active, ..Default::default() },
        ));
        if loop_active {
            outbounds.push(loop_outbound(loop_port));
        }
    } else {
        outbounds.push(proxy_outbound(
            profile,
            traffic,
            PROXY_TAG,
            OutOpts { chained, detour: base_detour, chain_via_dialer_proxy: o.chain_via_dialer_proxy, preserve_flow: o.keep_flow_on_chain, ..Default::default() },
        ));
    }
    if let Some(wg) = wg_base {
        outbounds.push(wg);
    }
    let mut direct = Obj::new();
    direct.insert("tag".into(), json!("direct"));
    direct.insert("protocol".into(), json!("freedom"));
    direct.insert("settings".into(), json!({ "domainStrategy": direct_domain_strategy(traffic) }));
    if direct_dials_base {
        direct.insert("streamSettings".into(), json!({ "sockopt": { "dialerProxy": base_exit_tag } }));
    }
    outbounds.push(Value::Object(direct));
    outbounds.push(json!({ "tag": "block", "protocol": "blackhole" }));
    if fake_enabled {
        outbounds.push(dns_out_outbound());
    }
    if traffic.fragment_enabled && o.olcrtc_chain_port.is_none() {
        outbounds.push(json!({ "tag": "fragment", "protocol": "freedom", "settings": { "fragment": {
            "packets": traffic.fragment_packets, "length": traffic.fragment_length, "interval": traffic.fragment_interval } } }));
    }
    if let Some(port) = o.olcrtc_chain_port {
        outbounds.push(olcrtc_socks_outbound(OLCRTC_TAG, port, &o.olcrtc_chain_user, &o.olcrtc_chain_pass));
    }
    config.insert("outbounds".into(), Value::Array(outbounds));

    // ---- routing ----
    let exit_tag = if o.direct_via_base { PROXY_BASE_TAG } else { PROXY_TAG };
    let family_rule = if !o.force_family_resolve {
        None
    } else if traffic.domain_strategy == "ipv4_only" || traffic.domain_strategy == "prefer_ipv4" {
        Some(json!({ "type": "field", "ip": ["::/0"], "outboundTag": "block" }))
    } else if traffic.domain_strategy == "ipv6_only" {
        Some(json!({ "type": "field", "ip": ["0.0.0.0/0"], "outboundTag": "block" }))
    } else {
        None
    };
    let force_family = family_rule.is_some();
    let mut rules: Vec<Value> = Vec::new();
    if loop_active {
        rules.push(json!({ "type": "field", "inboundTag": [CASCADE_LOOP_IN_TAG], "outboundTag": PROXY_BASE_TAG }));
    }
    if o.warp_relay.is_some() {
        rules.push(json!({ "type": "field", "inboundTag": [WARP_RELAY_IN_TAG], "outboundTag": PROXY_BASE_TAG }));
    }
    if fake_enabled {
        rules.extend(dns_hijack_rules());
    }
    let (dns_domains, dns_ips) = remote_dns_endpoints(traffic);
    if !dns_domains.is_empty() {
        rules.push(json!({ "type": "field", "domain": dns_domains, "outboundTag": exit_tag }));
    }
    if !dns_ips.is_empty() {
        rules.push(json!({ "type": "field", "ip": dns_ips, "outboundTag": exit_tag }));
    }
    // A FakeDNS address is synthetic: it must reach the proxy before LAN/geoip:private rules.
    if fake_enabled {
        let spec = o.fake_dns_spec.as_ref();
        let v4 = spec.map(|s| s.inet4_range.as_str()).filter(|s| not_blank(s)).unwrap_or(FAKEDNS_POOL);
        let v6 = spec.map(|s| s.inet6_range.as_str()).filter(|s| not_blank(s)).unwrap_or(FAKEDNS_POOL6);
        rules.push(json!({ "type": "field", "ip": [v4, v6], "outboundTag": exit_tag }));
    }
    if o.bypass_lan && !o.direct_via_base {
        rules.push(json!({ "type": "field", "ip": LAN_CIDRS, "outboundTag": "direct" }));
    }
    if o.block_quic {
        rules.push(json!({ "type": "field", "network": "udp", "port": 443, "outboundTag": "block" }));
    }
    if let Some(r) = family_rule {
        rules.push(r);
    }
    if traffic.block_ru_domains || !fake_block.is_empty() {
        rules.push(json!({ "type": "field", "ip": ["0.0.0.0"], "outboundTag": "block" }));
    }
    let strategy = match rp {
        Some(p) => {
            rules.extend(xr::rules(p));
            if force_family {
                "IPIfNonMatch".to_string()
            } else {
                xr::domain_strategy(p)
            }
        }
        None => (if force_family { "IPIfNonMatch" } else { "AsIs" }).to_string(),
    };
    config.insert("routing".into(), json!({ "domainStrategy": strategy, "rules": rules }));
    Value::Object(config)
}

// ---------------- prepare_raw ----------------

/// Options of [`prepare_raw`].
#[derive(Debug, Clone, Default)]
pub struct RawOptions {
    pub listen_port: i32,
    pub listen_host: String,
    pub socks_username: String,
    pub socks_password: String,
    /// Merged only into configs WITHOUT their own routing.
    pub routing_profile: Option<RoutingProfile>,
    /// Inject FakeDNS unless the config already defines `fakedns`.
    pub fake_dns_enabled: bool,
    /// Drop geosite:/geoip: from the config's own routing/dns (missing .dat files).
    pub strip_geo_selectors: bool,
    pub force_ipv4: bool,
    pub second_profile: Option<ProxyProfile>,
    pub warp_chain_port: Option<i32>,
    pub warp_exit_port: Option<i32>,
    pub warp_relay: Option<WarpRelay>,
}

impl RawOptions {
    pub fn new(listen_port: i32) -> Self {
        Self { listen_port, listen_host: "127.0.0.1".into(), ..Default::default() }
    }
}

/// A user's full Xray JSON made runnable through the bridge: only the SOCKS inbound is rewritten;
/// everything else stays as authored (plus the optional FakeDNS/cascade/WARP/profile plumbing).
/// Unparseable text is returned unchanged.
pub fn prepare_raw(raw_config_json: &str, o: &RawOptions) -> String {
    let Some(root) = parse_object(raw_config_json) else { return raw_config_json.to_string() };
    let (config_dns, config_routing) =
        lift_hosts_into_routing(root.get("dns").and_then(Value::as_object).cloned(), root.get("routing").and_then(Value::as_object).cloned());
    let inject_fake = o.fake_dns_enabled && !root.contains_key("fakedns");

    let existing_inbounds: Vec<Obj> = root.get("inbounds").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_object().cloned()).collect()).unwrap_or_default();
    let template_socks = existing_inbounds.iter().find(|i| str_of(i, "protocol") == Some("socks"));
    let non_socks: Vec<&Obj> = existing_inbounds.iter().filter(|i| str_of(i, "protocol") != Some("socks")).collect();

    let mut socks = socks_inbound(&o.listen_host, o.listen_port, &o.socks_username, &o.socks_password);
    let sniffing = template_socks.and_then(|t| t.get("sniffing")).and_then(Value::as_object);
    let sniff = if inject_fake {
        sniffing_with_fake_dns(sniffing)
    } else if let Some(s) = sniffing {
        Value::Object(s.clone())
    } else {
        json!({ "enabled": true, "destOverride": ["http", "tls", "quic"] })
    };
    socks.insert("sniffing".into(), sniff);

    let user_outbounds: Vec<Obj> = root.get("outbounds").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_object().cloned()).collect()).unwrap_or_default();
    let user_tags: Vec<&str> = user_outbounds.iter().filter_map(|ob| str_of(ob, "tag")).collect();

    let cascade_types = [TYPE_VLESS, TYPE_VMESS, TYPE_TROJAN, TYPE_SHADOWSOCKS];
    let cascade_second = o.second_profile.as_ref().filter(|s| s.is_complete() && cascade_types.contains(&s.kind.as_str()));
    let main = user_outbounds.iter().find(|ob| str_of(ob, "protocol").is_some_and(|p| cascade_types.contains(&p.to_lowercase().as_str())));
    let main_tag: Option<String> = main.and_then(|m| str_of(m, "tag")).map(str::to_string);
    let main_network = main.and_then(|m| m.get("streamSettings")).and_then(|s| s.get("network")).and_then(Value::as_str).map(str::to_lowercase);
    let main_is_xhttp = matches!(main_network.as_deref(), Some("xhttp") | Some("splithttp"));
    let loop_port = o.listen_port + 1;
    let warp_client = o.warp_chain_port.is_some() && main_tag.is_some();
    let warp_server = !warp_client && o.warp_exit_port.is_some() && o.warp_relay.is_some() && main_tag.is_some();
    let cascade_outbound: Option<Value> = if warp_server {
        Some(olcrtc_socks_outbound(CASCADE_EXIT_TAG, o.warp_exit_port.unwrap(), "", ""))
    } else if let (Some(second), Some(mt)) = (cascade_second, main_tag.as_deref()) {
        let traffic = TrafficSettings::default();
        Some(if main_is_xhttp {
            proxy_outbound(second, &traffic, CASCADE_EXIT_TAG, OutOpts { detour: Some(CASCADE_LOOP_OUT_TAG), chain_via_dialer_proxy: true, preserve_flow: true, ..Default::default() })
        } else {
            proxy_outbound(second, &traffic, CASCADE_EXIT_TAG, OutOpts { detour: Some(mt), chain_via_dialer_proxy: second.network == NETWORK_XHTTP, ..Default::default() })
        })
    } else {
        None
    };
    let loop_active = !warp_server && cascade_outbound.is_some() && main_tag.is_some() && main_is_xhttp;

    // The config's own routing wins; the profile is merged only into configs without one.
    let user_routing = config_routing;
    let user_has_routing = user_routing.as_ref().and_then(|r| r.get("rules")).and_then(Value::as_array).is_some_and(|a| !a.is_empty());
    let merged_routing: Option<Obj> = if user_has_routing {
        None
    } else {
        o.routing_profile.as_ref().map(|p| {
            let ds = user_routing.as_ref().and_then(|r| str_of(r, "domainStrategy")).map(str::to_string).unwrap_or_else(|| xr::domain_strategy(p));
            let mut rules = xr::bucket_rules(p);
            if let Some(a) = user_routing.as_ref().and_then(|r| r.get("rules")).and_then(Value::as_array) {
                rules.extend(a.iter().cloned());
            }
            rules.extend(xr::catch_all_direct_rule(p));
            let mut m = Obj::new();
            m.insert("domainStrategy".into(), json!(ds));
            m.insert("rules".into(), Value::Array(rules));
            m
        })
    };

    let augmented_dns: Option<Obj> = inject_fake.then(|| {
        let mut m = Obj::new();
        if let Some(d) = &config_dns {
            for (k, v) in d {
                if k != "servers" {
                    m.insert(k.clone(), v.clone());
                }
            }
        }
        let mut servers = vec![json!("fakedns")];
        match config_dns.as_ref().and_then(|d| d.get("servers")).and_then(Value::as_array) {
            Some(a) => servers.extend(a.iter().cloned()),
            None => servers.push(json!("1.1.1.1")),
        }
        m.insert("servers".into(), Value::Array(servers));
        if config_dns.as_ref().and_then(|d| d.get("queryStrategy")).is_none() {
            m.insert("queryStrategy".into(), json!("UseIP"));
        }
        m
    });

    let base_routing = merged_routing.clone().or_else(|| user_routing.clone());
    let final_routing: Option<Obj> = if inject_fake {
        let mut m = Obj::new();
        if let Some(b) = &base_routing {
            for (k, v) in b {
                if k != "rules" {
                    m.insert(k.clone(), v.clone());
                }
            }
        }
        if base_routing.as_ref().and_then(|b| b.get("domainStrategy")).is_none() {
            m.insert("domainStrategy".into(), json!("AsIs"));
        }
        let mut rules = dns_hijack_rules();
        if let Some(a) = base_routing.as_ref().and_then(|b| b.get("rules")).and_then(Value::as_array) {
            rules.extend(a.iter().cloned());
        }
        m.insert("rules".into(), Value::Array(rules));
        Some(m)
    } else {
        base_routing
    };

    let base_dns = augmented_dns.or(config_dns);
    let stripped_dns = if o.strip_geo_selectors { strip_geo_from_dns(base_dns) } else { base_dns };
    let out_dns = match stripped_dns {
        Some(d) if o.force_ipv4 => {
            let mut m: Obj = d.into_iter().filter(|(k, _)| k != "queryStrategy").collect();
            m.insert("queryStrategy".into(), json!("UseIPv4"));
            Some(m)
        }
        other => other,
    };
    let geo_stripped = if o.strip_geo_selectors && user_has_routing { strip_geo_from_routing(final_routing) } else { final_routing };
    let after_cascade = match (&cascade_outbound, &main_tag) {
        (Some(_), Some(mt)) => rewrite_outbound_tag(geo_stripped, mt, CASCADE_EXIT_TAG),
        _ => geo_stripped,
    };
    let prepend = |routing: Option<Obj>, rule: Value| -> Option<Obj> {
        let mut m = Obj::new();
        if let Some(r) = &routing {
            for (k, v) in r {
                if k != "rules" {
                    m.insert(k.clone(), v.clone());
                }
            }
        }
        let mut rules = vec![rule];
        if let Some(a) = routing.as_ref().and_then(|r| r.get("rules")).and_then(Value::as_array) {
            rules.extend(a.iter().cloned());
        }
        m.insert("rules".into(), Value::Array(rules));
        Some(m)
    };
    let to_write = if loop_active {
        prepend(after_cascade, json!({ "type": "field", "inboundTag": [CASCADE_LOOP_IN_TAG], "outboundTag": main_tag }))
    } else {
        after_cascade
    };
    let routing_final = if warp_server {
        prepend(to_write, json!({ "type": "field", "inboundTag": [WARP_RELAY_IN_TAG], "outboundTag": main_tag }))
    } else {
        to_write
    };

    let mut new_root = Obj::new();
    for (k, v) in &root {
        if !["inbounds", "outbounds", "routing", "dns"].contains(&k.as_str()) && !(inject_fake && k == "fakedns") {
            new_root.insert(k.clone(), v.clone());
        }
    }
    if let Some(d) = out_dns {
        new_root.insert("dns".into(), Value::Object(d));
    }
    if inject_fake {
        new_root.insert("fakedns".into(), json!([fake_dns_pool(None)]));
    }
    let mut inbounds = vec![Value::Object(socks)];
    if loop_active {
        inbounds.push(loop_inbound(loop_port));
    }
    if warp_server {
        inbounds.push(warp_relay_inbound(o.warp_relay.as_ref().unwrap()));
    }
    inbounds.extend(non_socks.into_iter().map(|i| Value::Object(i.clone())));
    new_root.insert("inbounds".into(), Value::Array(inbounds));

    let mut outbounds: Vec<Value> = Vec::new();
    outbounds.extend(cascade_outbound);
    if loop_active {
        outbounds.push(loop_outbound(loop_port));
    }
    for raw in &user_outbounds {
        let ob = if warp_client { with_dialer_proxy(raw, WARP_OUT_TAG) } else { raw.clone() };
        let lifted = lift_xhttp_extra_host_path(&ob);
        let tuned = if loop_active && str_of(&ob, "tag") == main_tag.as_deref() { with_xhttp_high_concurrency(&lifted) } else { lifted };
        outbounds.push(Value::Object(if o.force_ipv4 { force_ipv4_freedom(&tuned) } else { tuned }));
    }
    // LAST: unmatched traffic goes to the FIRST outbound, which must stay the config's own proxy.
    if warp_client {
        outbounds.push(olcrtc_socks_outbound(WARP_OUT_TAG, o.warp_chain_port.unwrap(), "", ""));
    }
    if merged_routing.is_some() && !user_tags.contains(&"direct") {
        outbounds.push(json!({ "tag": "direct", "protocol": "freedom", "settings": { "domainStrategy": "ForceIPv4" } }));
    }
    if merged_routing.is_some() && !user_tags.contains(&"block") {
        outbounds.push(json!({ "tag": "block", "protocol": "blackhole" }));
    }
    if inject_fake && !user_tags.contains(&"dns-out") {
        outbounds.push(dns_out_outbound());
    }
    new_root.insert("outbounds".into(), Value::Array(outbounds));
    if let Some(r) = routing_final {
        new_root.insert("routing".into(), Value::Object(r));
    }
    pretty(&Value::Object(new_root))
}

/// A throwaway config for pinging a VERBATIM config: its real proxy outbound (transport intact) as
/// the only exit, no routing/dns. `None` when it has no proxy outbound.
pub fn build_raw_proxy_ping_config(raw_config_json: &str, listen_port: i32, listen_host: &str) -> Option<String> {
    let root = parse_object(raw_config_json)?;
    let outbounds = root.get("outbounds")?.as_array()?;
    let proxy = outbounds.iter().filter_map(Value::as_object).find(|ob| str_of(ob, "protocol").is_some_and(|p| PROXY_PROTOCOLS.contains(&p.to_lowercase().as_str())))?;
    let lifted = lift_xhttp_extra_host_path(proxy);
    let mut ping = Obj::new();
    let mut tagged = false;
    for (k, v) in lifted {
        if k == "tag" {
            ping.insert("tag".into(), json!(PROXY_TAG));
            tagged = true;
        } else {
            ping.insert(k, v);
        }
    }
    if !tagged {
        ping.insert("tag".into(), json!(PROXY_TAG));
    }
    let socks = json!({ "tag": "socks-in", "listen": listen_host, "port": listen_port, "protocol": "socks", "settings": { "udp": true, "auth": "noauth" } });
    Some(pretty(&json!({ "inbounds": [socks], "outbounds": [ping] })))
}

fn rewrite_outbound_tag(routing: Option<Obj>, from: &str, to: &str) -> Option<Obj> {
    let mut routing = routing?;
    if let Some(Value::Array(rules)) = routing.get_mut("rules") {
        for rule in rules.iter_mut() {
            if let Some(r) = rule.as_object_mut() {
                if r.get("outboundTag").and_then(Value::as_str) == Some(from) {
                    r.insert("outboundTag".into(), json!(to));
                }
            }
        }
    }
    Some(routing)
}

/// Rebuilds `obj` with `key` replaced (appended last, like Kotlin's buildJsonObject re-put).
fn with_key_last(obj: &Obj, key: &str, value: Value) -> Obj {
    let mut m: Obj = obj.iter().filter(|(k, _)| *k != key).map(|(k, v)| (k.clone(), v.clone())).collect();
    m.insert(key.into(), value);
    m
}

/// Copies `xhttpSettings.extra.host/path` up when the top level is empty (xray overrides extra's
/// with the top-level values) and sanitizes the block. A valid block is returned unchanged.
fn lift_xhttp_extra_host_path(outbound: &Obj) -> Obj {
    let Some(stream) = outbound.get("streamSettings").and_then(Value::as_object) else { return outbound.clone() };
    let Some(xhttp) = stream.get("xhttpSettings").and_then(Value::as_object) else { return outbound.clone() };
    let safe = sanitize_xhttp_extra(xhttp);
    let safe_extra = safe.get("extra").and_then(Value::as_object);
    let top_host = safe.get("host").and_then(Value::as_str).map(str::to_string);
    let top_path = safe.get("path").and_then(Value::as_str).map(str::to_string);
    let extra_host = safe_extra.and_then(|e| e.get("host")).and_then(Value::as_str).map(str::to_string);
    let extra_path = safe_extra.and_then(|e| e.get("path")).and_then(Value::as_str).map(str::to_string);
    let blank = |s: &Option<String>| s.as_deref().map_or(true, |x| x.trim().is_empty());
    let new_host = if blank(&top_host) && !blank(&extra_host) { extra_host } else { top_host.clone() };
    let new_path = if blank(&top_path) && !blank(&extra_path) { extra_path } else { top_path.clone() };
    if new_host == top_host && new_path == top_path && &safe == xhttp {
        return outbound.clone();
    }
    let mut nx: Obj = safe.into_iter().filter(|(k, _)| k != "host" && k != "path").collect();
    if let Some(h) = new_host.filter(|h| not_blank(h)) {
        nx.insert("host".into(), json!(h));
    }
    if let Some(p) = new_path.filter(|p| not_blank(p)) {
        nx.insert("path".into(), json!(p));
    }
    let ns = with_key_last(stream, "xhttpSettings", Value::Object(nx));
    with_key_last(outbound, "streamSettings", Value::Object(ns))
}

/// A high-concurrency `xmux` (+ mux) on an xhttp outbound that has none of its own.
fn with_xhttp_high_concurrency(outbound: &Obj) -> Obj {
    let Some(stream) = outbound.get("streamSettings").and_then(Value::as_object) else { return outbound.clone() };
    let Some(xhttp) = stream.get("xhttpSettings").and_then(Value::as_object) else { return outbound.clone() };
    if xhttp.contains_key("xmux") {
        return outbound.clone();
    }
    let mut nx = xhttp.clone();
    nx.insert("xmux".into(), json!({ "maxConnections": "4-8", "cMaxReuseTimes": "64-128", "cMaxLifetimeMs": 0 }));
    let ns = with_key_last(stream, "xhttpSettings", Value::Object(nx));
    let mut out: Obj = outbound.iter().filter(|(k, _)| *k != "streamSettings" && *k != "mux").map(|(k, v)| (k.clone(), v.clone())).collect();
    out.insert("streamSettings".into(), Value::Object(ns));
    out.insert("mux".into(), outbound.get("mux").filter(|m| m.is_object()).cloned().unwrap_or_else(|| json!({ "enabled": true, "concurrency": 8 })));
    out
}

/// Moves `dns.hosts` entries steered by an ip-only routing rule into `domain` rules right before
/// it (a forced-family freedom would otherwise dial the synthetic 198.18.0.x address).
pub fn lift_hosts_into_routing(dns: Option<Obj>, routing: Option<Obj>) -> (Option<Obj>, Option<Obj>) {
    let Some(hosts) = dns.as_ref().and_then(|d| d.get("hosts")).and_then(Value::as_object).cloned() else { return (dns, routing) };
    let Some(rules) = routing.as_ref().and_then(|r| r.get("rules")).and_then(Value::as_array).cloned() else { return (dns, routing) };
    let rule_for = |ip: &str| -> Option<usize> {
        rules.iter().position(|rule| {
            let Some(r) = rule.as_object() else { return false };
            r.contains_key("outboundTag")
                && r.keys().all(|k| ["type", "ip", "outboundTag", "ruleTag"].contains(&k.as_str()))
                && r.get("ip").and_then(Value::as_array).is_some_and(|a| a.iter().any(|c| ipv4_in_cidr(ip, c.as_str())))
        })
    };
    let mut lifted: BTreeMap<usize, Vec<String>> = BTreeMap::new();
    let mut kept = Obj::new();
    for (key, value) in &hosts {
        let single = match value {
            Value::Array(a) if a.len() == 1 => &a[0],
            v => v,
        };
        match single.as_str().and_then(rule_for) {
            Some(at) => {
                // An unprefixed hosts key is a FULL match; in routing it would be a substring.
                let domain = if key.split_once(':').map_or(true, |(p, _)| p.is_empty()) { format!("full:{key}") } else { key.clone() };
                lifted.entry(at).or_default().push(domain);
            }
            None => {
                kept.insert(key.clone(), value.clone());
            }
        }
    }
    if lifted.is_empty() {
        return (dns, routing);
    }
    let mut new_dns = Obj::new();
    for (k, v) in dns.as_ref().unwrap() {
        if k == "hosts" {
            if !kept.is_empty() {
                new_dns.insert(k.clone(), Value::Object(kept.clone()));
            }
        } else {
            new_dns.insert(k.clone(), v.clone());
        }
    }
    let mut new_rules = Vec::new();
    for (i, rule) in rules.iter().enumerate() {
        if let Some(domains) = lifted.get(&i) {
            new_rules.push(json!({ "type": "field", "domain": domains, "outboundTag": rule["outboundTag"] }));
        }
        new_rules.push(if rule.is_object() { rule.clone() } else { json!({}) });
    }
    let new_routing = with_key_last(routing.as_ref().unwrap(), "rules", Value::Array(new_rules));
    (Some(new_dns), Some(new_routing))
}

fn ipv4_in_cidr(ip: &str, cidr: Option<&str>) -> bool {
    let Some(cidr) = cidr else { return false };
    let to_u32 = |v: &str| -> Option<u32> {
        let parts: Vec<&str> = v.split('.').collect();
        if parts.len() != 4 {
            return None;
        }
        parts.iter().try_fold(0u32, |acc, p| p.parse::<u8>().ok().map(|b| (acc << 8) | b as u32))
    };
    let (Some(addr), Some(base)) = (to_u32(ip), to_u32(cidr.split('/').next().unwrap_or(""))) else { return false };
    let bits = match cidr.split_once('/') {
        Some((_, b)) => match b.parse::<u32>() {
            Ok(n) if n <= 32 => n,
            _ => return false,
        },
        None => 32,
    };
    let mask = if bits == 0 { 0 } else { u32::MAX << (32 - bits) };
    addr & mask == base & mask
}

fn force_ipv4_freedom(outbound: &Obj) -> Obj {
    if str_of(outbound, "protocol") != Some("freedom") {
        return outbound.clone();
    }
    let mut settings: Obj = outbound.get("settings").and_then(Value::as_object).map(|s| s.iter().filter(|(k, _)| *k != "domainStrategy").map(|(k, v)| (k.clone(), v.clone())).collect()).unwrap_or_default();
    settings.insert("domainStrategy".into(), json!("ForceIPv4"));
    with_key_last(outbound, "settings", Value::Object(settings))
}

fn is_geo(v: &Value) -> bool {
    v.as_str().is_some_and(|s| {
        let s = s.to_lowercase();
        s.starts_with("geosite:") || s.starts_with("geoip:")
    })
}

/// Geo selectors out of the config's own rules; a rule whose every matcher was geo is dropped.
fn strip_geo_from_routing(routing: Option<Obj>) -> Option<Obj> {
    let routing = routing?;
    let Some(rules) = routing.get("rules").and_then(Value::as_array) else { return Some(routing) };
    let cleaned: Vec<Value> = rules
        .iter()
        .filter_map(|el| {
            let Some(rule) = el.as_object() else { return Some(el.clone()) };
            let strip = |k: &str| rule.get(k).and_then(Value::as_array).map(|a| a.iter().filter(|v| !is_geo(v)).cloned().collect::<Vec<_>>());
            let (nd, ni) = (strip("domain"), strip("ip"));
            let (d_empty, i_empty) = (nd.as_ref().is_some_and(Vec::is_empty), ni.as_ref().is_some_and(Vec::is_empty));
            match (nd.is_some(), ni.is_some()) {
                (true, true) if d_empty && i_empty => return None,
                (true, false) if d_empty => return None,
                (false, true) if i_empty => return None,
                _ => {}
            }
            let mut m = Obj::new();
            for (k, v) in rule {
                match k.as_str() {
                    "domain" if nd.is_some() => {
                        if !d_empty {
                            m.insert(k.clone(), json!(nd.clone().unwrap()));
                        }
                    }
                    "ip" if ni.is_some() => {
                        if !i_empty {
                            m.insert(k.clone(), json!(ni.clone().unwrap()));
                        }
                    }
                    _ => {
                        m.insert(k.clone(), v.clone());
                    }
                }
            }
            Some(Value::Object(m))
        })
        .collect();
    Some(with_key_last(&routing, "rules", Value::Array(cleaned)))
}

/// geosite: entries out of each dns server's `domains` (a server left with none is dropped).
fn strip_geo_from_dns(dns: Option<Obj>) -> Option<Obj> {
    let dns = dns?;
    let Some(servers) = dns.get("servers").and_then(Value::as_array) else { return Some(dns) };
    let cleaned: Vec<Value> = servers
        .iter()
        .filter_map(|el| {
            let Some(server) = el.as_object() else { return Some(el.clone()) };
            let Some(domains) = server.get("domains").and_then(Value::as_array) else { return Some(el.clone()) };
            let nd: Vec<Value> = domains.iter().filter(|v| !is_geo(v)).cloned().collect();
            if nd.is_empty() {
                return None;
            }
            let mut m = server.clone();
            m.insert("domains".into(), Value::Array(nd));
            Some(Value::Object(m))
        })
        .collect();
    Some(with_key_last(&dns, "servers", Value::Array(cleaned)))
}

// ---------------- WireGuard balancer ----------------

/// Load-balanced WireGuard exits, one per VK-TURN freeturn server (leastLoad + health probes).
pub fn build_wireguard_balancer(wg_profiles: &[ProxyProfile], listen_port: i32, listen_host: &str, socks_user: &str, socks_pass: &str, log_level: &str, traffic: &TrafficSettings, bypass_lan: bool) -> String {
    let bases: Vec<Value> = wg_profiles.iter().enumerate().filter_map(|(i, p)| wireguard_base_outbound(p, &format!("{WG_BAL_PREFIX}{i}"), None)).collect();
    let count = bases.len();
    let fake = traffic.fake_dns_enabled;
    let mut config = Obj::new();
    config.insert("log".into(), json!({ "loglevel": log_level }));
    let mut servers: Vec<String> = Vec::new();
    if fake {
        servers.push(FAKEDNS_SERVER.into());
    }
    servers.push(xray_dns(&traffic.remote_dns));
    if not_blank(&traffic.remote_dns2) {
        servers.push(xray_dns(&traffic.remote_dns2));
    }
    servers.push(xray_dns(&traffic.direct_dns));
    config.insert("dns".into(), json!({ "servers": servers, "queryStrategy": traffic.xray_query_strategy() }));
    if fake {
        config.insert("fakedns".into(), json!([fake_dns_pool(None)]));
    }
    let mut socks = socks_inbound(listen_host, listen_port, socks_user, socks_pass);
    let mut dest = vec!["http", "tls", "quic"];
    if fake {
        dest.push("fakedns");
    }
    socks.insert("sniffing".into(), json!({ "enabled": true, "destOverride": dest }));
    config.insert("inbounds".into(), json!([socks]));
    let mut outbounds = bases;
    outbounds.push(json!({ "tag": "direct", "protocol": "freedom", "settings": { "domainStrategy": direct_domain_strategy(traffic) } }));
    outbounds.push(json!({ "tag": "block", "protocol": "blackhole" }));
    if fake {
        outbounds.push(dns_out_outbound());
    }
    config.insert("outbounds".into(), Value::Array(outbounds));
    if count > 1 {
        config.insert(
            "burstObservatory".into(),
            json!({ "subjectSelector": [WG_BAL_PREFIX], "pingConfig": { "destination": "http://connectivitycheck.gstatic.com/generate_204", "interval": "10s", "sampling": 2, "timeout": "8s" } }),
        );
    }
    let mut rules: Vec<Value> = Vec::new();
    if fake {
        rules.extend(dns_hijack_rules());
    }
    if bypass_lan {
        rules.push(json!({ "type": "field", "ip": LAN_CIDRS, "outboundTag": "direct" }));
    }
    rules.push(json!({ "type": "field", "ip": ["::/0"], "outboundTag": "block" }));
    rules.push(json!({ "type": "field", "network": "tcp,udp", "balancerTag": WG_BALANCER_TAG }));
    config.insert(
        "routing".into(),
        json!({
            "domainStrategy": "AsIs",
            "balancers": [{ "tag": WG_BALANCER_TAG, "selector": [WG_BAL_PREFIX], "fallbackTag": format!("{WG_BAL_PREFIX}0"),
                "strategy": { "type": "leastLoad", "settings": { "expected": count, "baselines": ["3000ms"] } } }],
            "rules": rules,
        }),
    );
    pretty(&Value::Object(config))
}

// ---------------- outbounds ----------------

/// `streamSettings.sockopt.dialerProxy = tag` on a protocol outbound without its own chain.
fn with_dialer_proxy(ob: &Obj, tag: &str) -> Obj {
    if !str_of(ob, "protocol").is_some_and(|p| PROXY_PROTOCOLS.contains(&p.to_lowercase().as_str())) || ob.contains_key("proxySettings") {
        return ob.clone();
    }
    let stream = ob.get("streamSettings").and_then(Value::as_object).cloned().unwrap_or_default();
    let mut sockopt = stream.get("sockopt").and_then(Value::as_object).cloned().unwrap_or_default();
    if sockopt.contains_key("dialerProxy") {
        return ob.clone();
    }
    sockopt.insert("dialerProxy".into(), json!(tag));
    let mut ns = stream;
    ns.insert("sockopt".into(), Value::Object(sockopt));
    let mut out = ob.clone();
    out.insert("streamSettings".into(), Value::Object(ns));
    out
}

fn olcrtc_socks_outbound(tag: &str, port: i32, user: &str, pass: &str) -> Value {
    let mut server = Obj::new();
    server.insert("address".into(), json!("127.0.0.1"));
    server.insert("port".into(), json!(port));
    if not_blank(user) {
        server.insert("users".into(), json!([{ "user": user, "pass": pass }]));
    }
    json!({ "tag": tag, "protocol": "socks", "settings": { "servers": [server] } })
}

/// A stored sing-box WireGuard outbound → an Xray `wireguard` outbound (IPv4 only).
fn wireguard_base_outbound(p: &ProxyProfile, tag: &str, detour: Option<&str>) -> Option<Value> {
    let o = parse_object(p.raw_outbound.as_deref().filter(|r| not_blank(r))?)?;
    let num = |k: &str| -> Option<i64> {
        match o.get(k)? {
            Value::Number(n) => n.as_i64(),
            Value::String(s) => s.parse().ok(),
            _ => None,
        }
    };
    let secret = str_of(&o, "private_key")?;
    let peer = str_of(&o, "peer_public_key")?;
    let server = str_of(&o, "server").unwrap_or("127.0.0.1");
    let port = num("server_port")?;
    let addrs: Vec<String> = o.get("local_address").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect()).unwrap_or_default();
    let mtu = num("mtu");
    let keepalive = num("persistent_keepalive_interval").or_else(|| num("persistent_keepalive")).unwrap_or(25);
    let mut m = Obj::new();
    m.insert("tag".into(), json!(tag));
    m.insert("protocol".into(), json!("wireguard"));
    if let Some(d) = detour {
        m.insert("streamSettings".into(), json!({ "sockopt": { "dialerProxy": d } }));
    }
    let mut settings = Obj::new();
    settings.insert("secretKey".into(), json!(secret));
    settings.insert("address".into(), json!(addrs));
    settings.insert("domainStrategy".into(), json!("ForceIPv4"));
    settings.insert("peers".into(), json!([{ "publicKey": peer, "endpoint": format!("{server}:{port}"), "allowedIPs": ["0.0.0.0/0"], "keepAlive": keepalive }]));
    if let Some(mtu) = mtu {
        settings.insert("mtu".into(), json!(mtu));
    }
    m.insert("settings".into(), Value::Object(settings));
    Some(Value::Object(m))
}

fn proxy_outbound(p: &ProxyProfile, traffic: &TrafficSettings, tag: &str, o: OutOpts) -> Value {
    let detour = o.detour.or(if o.chained { Some(OLCRTC_TAG) } else { None });
    let mut m = Obj::new();
    m.insert("tag".into(), json!(tag));
    m.insert("protocol".into(), json!(p.kind));
    let settings = match p.kind.as_str() {
        TYPE_VLESS | TYPE_VMESS => {
            let mut user = Obj::new();
            user.insert("id".into(), json!(p.uuid));
            if p.kind == TYPE_VLESS {
                user.insert("encryption".into(), json!(if not_blank(&p.vless_encryption) { p.vless_encryption.as_str() } else { "none" }));
                // XTLS Vision splices raw TLS to its own server: dropped on chained hops.
                if not_blank(&p.flow) && (detour.is_none() || o.preserve_flow) {
                    user.insert("flow".into(), json!(p.flow));
                }
            } else {
                user.insert("alterId".into(), json!(p.alter_id));
                user.insert("security".into(), json!(if not_blank(&p.cipher) { p.cipher.as_str() } else { "auto" }));
            }
            json!({ "vnext": [{ "address": p.server, "port": p.server_port, "users": [user] }] })
        }
        TYPE_TROJAN => json!({ "servers": [{ "address": p.server, "port": p.server_port, "password": p.password }] }),
        TYPE_SHADOWSOCKS => json!({ "servers": [{ "address": p.server, "port": p.server_port, "method": p.method, "password": p.password }] }),
        // Local SOCKS hop (the AmneziaWG base via awgproxy).
        "socks" => {
            let mut s = Obj::new();
            s.insert("address".into(), json!(p.server));
            s.insert("port".into(), json!(p.server_port));
            if not_blank(&p.password) {
                s.insert("users".into(), json!([{ "user": "", "pass": p.password }]));
            }
            json!({ "servers": [s] })
        }
        _ => json!({}),
    };
    m.insert("settings".into(), settings);
    let local_socks = p.kind == "socks";
    // dialerProxy (socket-level) keeps the hop's own transport; proxySettings drops it.
    let needs_dialer = detour.is_some_and(|d| d == PROXY_BASE_TAG || p.network == NETWORK_XHTTP || o.chain_via_dialer_proxy);
    let dialer_tag = if needs_dialer { detour } else { None };
    if !local_socks {
        m.insert("streamSettings".into(), stream_settings(p, traffic.fragment_enabled && detour.is_none(), dialer_tag, o.xhttp_high_concurrency));
    }
    if let (Some(d), None) = (detour, dialer_tag) {
        m.insert("proxySettings".into(), json!({ "tag": d }));
    }
    if traffic.mux_enabled && !local_socks && !o.suppress_mux {
        m.insert("mux".into(), json!({ "enabled": true, "concurrency": traffic.mux_max_connections }));
    }
    Value::Object(m)
}

fn stream_settings(p: &ProxyProfile, fragment_dialer: bool, dialer_tag: Option<&str>, high_concurrency: bool) -> Value {
    let mut m = Obj::new();
    if let Some(t) = dialer_tag {
        m.insert("sockopt".into(), json!({ "dialerProxy": t }));
    } else if fragment_dialer {
        m.insert("sockopt".into(), json!({ "dialerProxy": "fragment" }));
    }
    let network = match p.network.as_str() {
        NETWORK_WS => "ws",
        NETWORK_GRPC => "grpc",
        NETWORK_HTTP => "http",
        NETWORK_HTTPUPGRADE => "httpupgrade",
        NETWORK_XHTTP => "xhttp",
        _ => "tcp",
    };
    m.insert("network".into(), json!(network));
    match p.security.as_str() {
        SECURITY_TLS => {
            m.insert("security".into(), json!("tls"));
            let mut tls = Obj::new();
            // Blank SNI: the HTTP Host for CDN-fronted ws/http, else the address.
            let sni = if not_blank(&p.sni) {
                p.sni.clone()
            } else {
                let host = p.host.split(',').next().unwrap_or("").trim();
                if !host.is_empty() && network != "tcp" && network != "grpc" { host.to_string() } else { p.server.clone() }
            };
            tls.insert("serverName".into(), json!(sni));
            tls.insert("allowInsecure".into(), json!(p.allow_insecure));
            if not_blank(&p.fingerprint) {
                tls.insert("fingerprint".into(), json!(p.fingerprint));
            }
            if !p.alpn.is_empty() {
                tls.insert("alpn".into(), json!(p.alpn));
            }
            if not_blank(&p.pinned_cert_sha256) {
                tls.insert("pinnedPeerCertSha256".into(), json!(p.pinned_cert_sha256));
            }
            if not_blank(&p.verify_cert_by_name) {
                tls.insert("verifyPeerCertByName".into(), json!(p.verify_cert_by_name));
            }
            if not_blank(&p.ech_config_list) {
                tls.insert("echConfigList".into(), json!(p.ech_config_list));
            }
            m.insert("tlsSettings".into(), Value::Object(tls));
        }
        SECURITY_REALITY => {
            m.insert("security".into(), json!("reality"));
            let mut r = Obj::new();
            r.insert("serverName".into(), json!(if not_blank(&p.sni) { &p.sni } else { &p.server }));
            if not_blank(&p.fingerprint) {
                r.insert("fingerprint".into(), json!(p.fingerprint));
            }
            r.insert("publicKey".into(), json!(p.reality_public_key));
            r.insert("shortId".into(), json!(p.reality_short_id));
            if not_blank(&p.reality_mldsa65_verify) {
                r.insert("mldsa65Verify".into(), json!(p.reality_mldsa65_verify));
            }
            if not_blank(&p.reality_spider_x) {
                r.insert("spiderX".into(), json!(p.reality_spider_x));
            }
            m.insert("realitySettings".into(), Value::Object(r));
        }
        _ => {
            m.insert("security".into(), json!("none"));
        }
    }
    if let Some(fm) = parse_object(&p.final_mask).filter(|_| not_blank(&p.final_mask)) {
        m.insert("finalmask".into(), Value::Object(fm));
    }
    let mut ts = Obj::new();
    let put_nb = |ts: &mut Obj, k: &str, v: &str| {
        if not_blank(v) {
            ts.insert(k.into(), json!(v));
        }
    };
    match network {
        "ws" => {
            put_nb(&mut ts, "path", &p.path);
            if not_blank(&p.host) {
                ts.insert("headers".into(), json!({ "Host": p.host }));
            }
            m.insert("wsSettings".into(), Value::Object(ts));
        }
        "grpc" => {
            put_nb(&mut ts, "serviceName", &p.path);
            m.insert("grpcSettings".into(), Value::Object(ts));
        }
        "xhttp" => {
            put_nb(&mut ts, "path", &p.path);
            put_nb(&mut ts, "host", &p.host);
            ts.insert("mode".into(), json!(if not_blank(&p.xhttp_mode) { p.xhttp_mode.as_str() } else { "auto" }));
            // Xray only applies its xmux defaults when the whole block is absent: restate them.
            let xmux = high_concurrency.then(|| json!({ "maxConnections": "4-8", "cMaxReuseTimes": "64-128", "hMaxRequestTimes": "600-900", "hMaxReusableSecs": "1800-3000" }));
            match parse_object(&p.xhttp_extra).filter(|_| not_blank(&p.xhttp_extra)).map(|e| sanitize_xhttp_extra(&e)) {
                Some(mut extra) => {
                    if let Some(x) = xmux {
                        extra.entry("xmux").or_insert(x);
                    }
                    ts.insert("extra".into(), Value::Object(extra));
                }
                None => {
                    if let Some(x) = xmux {
                        ts.insert("xmux".into(), x);
                    }
                }
            }
            m.insert("xhttpSettings".into(), Value::Object(ts));
        }
        "httpupgrade" => {
            put_nb(&mut ts, "path", &p.path);
            put_nb(&mut ts, "host", &p.host);
            m.insert("httpupgradeSettings".into(), Value::Object(ts));
        }
        "http" => {
            put_nb(&mut ts, "path", &p.path);
            if not_blank(&p.host) {
                ts.insert("host".into(), json!([p.host]));
            }
            m.insert("httpSettings".into(), Value::Object(ts));
        }
        _ => {}
    }
    Value::Object(m)
}

// ---------------- xhttp `extra` sanitizer ----------------

/// xray `Int32Range` fields: an integer, an "a-b" range string or "".
const XHTTP_INT32_RANGE_KEYS: [&str; 11] = [
    "xPaddingBytes", "sessionIDLength", "uplinkChunkSize", "scMaxEachPostBytes", "scMinPostsIntervalMs", "scStreamUpServerSecs",
    "maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs",
];
const XHTTP_INT_KEYS: [&str; 3] = ["scMaxBufferedPosts", "serverMaxHeaderBytes", "hKeepAlivePeriod"];
const XHTTP_BOOL_KEYS: [&str; 3] = ["xPaddingObfsMode", "noGRPCHeader", "noSSEHeader"];
const XHTTP_STRING_KEYS: [&str; 17] = [
    "host", "path", "mode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement", "xPaddingMethod", "uplinkHTTPMethod", "sessionIDPlacement",
    "sessionIDKey", "sessionIDTable", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey", "sessionPlacement", "sessionKey",
];

/// Coerces or drops wrong-typed xhttp fields (float → int, "true" → true, number → string) so one
/// odd panel value cannot abort the whole build; valid values stay untouched. Recursive.
pub fn sanitize_xhttp_extra(obj: &Obj) -> Obj {
    let mut out = Obj::new();
    for (k, v) in obj {
        let key = k.as_str();
        let value = if XHTTP_INT32_RANGE_KEYS.contains(&key) {
            normalize_int32_range(v)
        } else if XHTTP_INT_KEYS.contains(&key) {
            normalize_int(v)
        } else if XHTTP_BOOL_KEYS.contains(&key) {
            normalize_bool(v)
        } else if XHTTP_STRING_KEYS.contains(&key) {
            normalize_string(v)
        } else if key == "headers" {
            v.as_object().map(|h| Value::Object(h.iter().filter_map(|(hk, hv)| normalize_string(hv).map(|s| (hk.clone(), s))).collect()))
        } else if key == "downloadSettings" {
            v.as_object().map(|d| {
                Value::Object(
                    d.iter()
                        .map(|(dk, dv)| match (dk.as_str(), dv.as_object()) {
                            ("xhttpSettings", Some(x)) => (dk.clone(), Value::Object(sanitize_xhttp_extra(x))),
                            _ => (dk.clone(), dv.clone()),
                        })
                        .collect(),
                )
            })
        } else if let Some(o) = v.as_object() {
            Some(Value::Object(sanitize_xhttp_extra(o)))
        } else {
            Some(v.clone())
        };
        if let Some(v) = value {
            out.insert(k.clone(), v);
        }
    }
    out
}

fn num_text(v: &Value) -> Option<String> {
    match v {
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        Value::String(s) => Some(s.clone()),
        _ => None,
    }
}

fn float_to_int(s: &str) -> Option<Value> {
    s.trim().parse::<f64>().ok().map(|d| json!(d as i64))
}

fn normalize_int32_range(v: &Value) -> Option<Value> {
    match v {
        Value::String(s) => {
            static RANGE: std::sync::OnceLock<regex_lite::Regex> = std::sync::OnceLock::new();
            let range = RANGE.get_or_init(|| regex_lite::Regex::new(r"^-?\d+--?\d+$").unwrap());
            if s.is_empty() || s.parse::<i32>().is_ok() || range.is_match(s) {
                Some(v.clone())
            } else {
                float_to_int(s)
            }
        }
        Value::Number(n) => {
            if n.as_i64().is_some_and(|i| i32::try_from(i).is_ok()) {
                Some(v.clone())
            } else {
                n.as_f64().map(|d| json!(d as i64))
            }
        }
        _ => None,
    }
}

fn normalize_int(v: &Value) -> Option<Value> {
    match v {
        Value::Number(n) if n.as_i64().is_some() => Some(v.clone()),
        Value::Number(n) => n.as_f64().filter(|d| d.is_finite()).map(|d| json!(d as i64)),
        Value::String(s) => match s.parse::<i64>() {
            Ok(i) => Some(json!(i)),
            Err(_) => s.parse::<f64>().ok().filter(|d| d.is_finite()).map(|d| json!(d as i64)),
        },
        _ => None,
    }
}

fn normalize_bool(v: &Value) -> Option<Value> {
    if v.is_boolean() {
        return Some(v.clone());
    }
    match num_text(v)?.to_lowercase().as_str() {
        "true" | "1" | "1.0" => Some(json!(true)),
        "false" | "0" | "0.0" => Some(json!(false)),
        _ => None,
    }
}

fn normalize_string(v: &Value) -> Option<Value> {
    match v {
        Value::String(_) => Some(v.clone()),
        Value::Number(_) | Value::Bool(_) => num_text(v).map(Value::String),
        _ => None,
    }
}
