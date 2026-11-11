//! ProxyProfile → sing-box 1.14 JSON (port of `SingBoxConfig`). One SOCKS (or mixed) inbound for
//! the TUN bridge plus the proxy outbound(s); optional olcRTC/WireGuard base hops, a second
//! (cascade) proxy, FakeDNS, routing profile, manual rules and the IPv4/IPv6 family guards.

use serde_json::{json, Map, Value};

use super::routing as sbr;
use crate::model::*;
use crate::routing::{RoutingProfile, RoutingRules, TrafficSettings};
use crate::transports::{AdvancedCoreConfig, FakeDnsSpec};

const PROXY_TAG: &str = "proxy";
/// The main (first-hop) proxy when a second/cascade proxy exits in front of it.
const PROXY_BASE_TAG: &str = "proxy-base";
const OLCRTC_TAG: &str = "olcrtc-out";
const WG_BASE_TAG: &str = "wireguard-base";
const WARP_RELAY_IN_TAG: &str = "warp-relay-in";
const SOCKS_IN_TAG: &str = "socks-in";
const TUN_IN_TAG: &str = "tun-in";
const TPROXY_IN_TAG: &str = "tproxy-in";

pub const SPLIT_TUNNEL_ALL: &str = "all_apps";
pub const SPLIT_TUNNEL_PROXY: &str = "proxy_selected";
pub const SPLIT_TUNNEL_BYPASS: &str = "bypass_selected";

/// App-level DoH endpoints rejected by domain under a strict family / FakeDNS.
const DOH_BLOCK_SUFFIXES: [&str; 10] = [
    "cloudflare-dns.com", "dns.google", "dns.google.com", "one.one.one.one", "dns.quad9.net", "doh.opendns.com",
    "dns.nextdns.io", "doh.cleanbrowsing.org", "dns.adguard.com", "dns.adguard-dns.com",
];

/// Raw-outbound types without sing-box smux support.
const RAW_OUTBOUND_NO_MUX: [&str; 8] = [
    "wireguard", "openvpn-client", "hysteria2", "hysteria", "tuic", "endpoint", "socks", "naive",
];

/// AmneziaWG-on-server WARP: a loopback UDP listener forwarding AWG's packets to the WARP endpoint
/// through the main proxy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WarpRelay {
    pub listen_port: i32,
    pub target_host: String,
    pub target_port: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigError {
    /// xhttp/splithttp needs the Xray core.
    XhttpNeedsXray,
}

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("xhttp/splithttp transport requires the Xray core, which is not supported yet")
    }
}

impl std::error::Error for ConfigError {}

/// Everything `build` takes besides the profile (the Kotlin named parameters, same defaults).
#[derive(Debug, Clone)]
pub struct SingBoxOptions {
    pub listen_port: i32,
    pub listen_host: String,
    pub socks_username: String,
    pub socks_password: String,
    pub olcrtc_chain_port: Option<i32>,
    pub olcrtc_chain_user: String,
    pub olcrtc_chain_pass: String,
    pub log_level: String,
    pub auto_detect_interface: bool,
    pub routing: RoutingRules,
    pub traffic: TrafficSettings,
    /// VK-TURN chain: this WireGuard profile is the base the proxy dials through.
    pub wireguard_base: Option<ProxyProfile>,
    pub dns_strategy_override: Option<String>,
    pub advanced: Option<AdvancedCoreConfig>,
    /// Happ-style profile; combined with the toggles and manual rules.
    pub routing_profile: Option<RoutingProfile>,
    pub singbox_geosite_base: String,
    pub singbox_geoip_base: String,
    /// Reject QUIC; must be false for UDP-capable tunnels.
    pub block_quic: bool,
    /// Resolve sniffed domains locally and reject the other family; false for full UDP tunnels.
    pub force_family_resolve: bool,
    /// False on very slow tunnels (MasterDNS): no per-connection DNS round-trip.
    pub allow_local_resolve: bool,
    /// VK-TURN / MasterDNS: the `direct` bucket rides the base tunnel.
    pub direct_via_base: bool,
    pub sniff_override_destination: bool,
    /// A SECOND proxy chained on top of the main one (the exit).
    pub second_profile: Option<ProxyProfile>,
    pub warp_relay: Option<WarpRelay>,
    pub fake_dns_spec: Option<FakeDnsSpec>,
    /// Desktop: sing-box owns the TUN (auto_route).
    pub tun_mode: bool,
    pub split_tunnel_mode: String,
    pub split_tunnel_processes: Vec<String>,
    pub tun_exclude_addresses: Vec<String>,
    pub hijack_dns: bool,
    pub log_file_path: Option<String>,
    pub remote_dns_over_https: bool,
    pub force_fake_dns: bool,
    pub mixed_inbound: bool,
    pub match_apps_by_process: bool,
    pub tproxy_port: Option<i32>,
    pub prefer_tcp_remote_dns: bool,
    /// `experimental.cache_file` path (persists rule-sets and the fakeip table).
    pub cache_file_path: Option<String>,
}

impl Default for SingBoxOptions {
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
            auto_detect_interface: false,
            routing: RoutingRules::default(),
            traffic: TrafficSettings::default(),
            wireguard_base: None,
            dns_strategy_override: None,
            advanced: None,
            routing_profile: None,
            singbox_geosite_base: String::new(),
            singbox_geoip_base: String::new(),
            block_quic: true,
            force_family_resolve: true,
            allow_local_resolve: true,
            direct_via_base: false,
            sniff_override_destination: false,
            second_profile: None,
            warp_relay: None,
            fake_dns_spec: None,
            tun_mode: false,
            split_tunnel_mode: SPLIT_TUNNEL_ALL.into(),
            split_tunnel_processes: vec![],
            tun_exclude_addresses: vec![],
            hijack_dns: false,
            log_file_path: None,
            remote_dns_over_https: false,
            force_fake_dns: false,
            mixed_inbound: false,
            match_apps_by_process: false,
            tproxy_port: None,
            prefer_tcp_remote_dns: false,
            cache_file_path: None,
        }
    }
}

type Obj = Map<String, Value>;

fn not_blank(s: &str) -> bool {
    !s.trim().is_empty()
}

fn parse_object(raw: Option<&str>) -> Option<Obj> {
    match serde_json::from_str::<Value>(raw?.trim()) {
        Ok(Value::Object(o)) => Some(o),
        _ => None,
    }
}

/// A verbatim JSON array string → its elements (blank/invalid → empty).
fn parse_json_array(raw: &str) -> Vec<Value> {
    if raw.trim().is_empty() {
        return vec![];
    }
    match serde_json::from_str::<Value>(raw) {
        Ok(Value::Array(a)) => a,
        _ => vec![],
    }
}

/// Builds the sing-box JSON (pretty-printed).
pub fn build(profile: &ProxyProfile, o: &SingBoxOptions) -> Result<String, ConfigError> {
    Ok(serde_json::to_string_pretty(&build_value(profile, o)?).unwrap())
}

pub fn build_value(profile: &ProxyProfile, o: &SingBoxOptions) -> Result<Value, ConfigError> {
    // The routing profile may carry its own DNS (e.g. the China preset); see `with_profile_dns`.
    let traffic = &o.traffic.with_profile_dns(o.routing_profile.as_ref());
    let routing = &o.routing;
    let effective_strategy = o.dns_strategy_override.clone().unwrap_or_else(|| traffic.domain_strategy.clone());
    let base_exit_tag = if o.wireguard_base.is_some() {
        Some(WG_BASE_TAG)
    } else if o.olcrtc_chain_port.is_some() {
        Some(OLCRTC_TAG)
    } else {
        None
    };
    // sing-box 1.14 forbids a `detour` on `direct`: never-bypass tunnels point the bucket at the
    // base tunnel BY TAG instead.
    let direct_tag = match base_exit_tag {
        Some(t) if o.direct_via_base => t,
        _ => "direct",
    };
    let fake_enabled = traffic.fake_dns_enabled || o.fake_dns_spec.is_some() || o.force_fake_dns;
    let fake4 = o.fake_dns_spec.as_ref().map(|s| s.inet4_range.clone()).filter(|s| not_blank(s)).unwrap_or_else(|| "198.18.0.0/15".into());
    let fake6 = o.fake_dns_spec.as_ref().map(|s| s.inet6_range.clone()).filter(|s| not_blank(s)).unwrap_or_else(|| "fc00::/18".into());

    let mut config = Obj::new();

    // ---- log ----
    let mut log = Obj::new();
    log.insert("level".into(), json!(o.log_level));
    log.insert("timestamp".into(), json!(true));
    if let Some(p) = o.log_file_path.as_deref().filter(|p| not_blank(p)) {
        log.insert("output".into(), json!(p));
    }
    config.insert("log".into(), Value::Object(log));

    // ---- dns ----
    let remote_address = if o.remote_dns_over_https && !traffic.remote_dns.contains("://") {
        format!("https://{}/dns-query", traffic.remote_dns)
    } else {
        traffic.remote_dns.clone()
    };
    let mut servers = vec![dns_server("remote", &maybe_tcp_dns(&remote_address, o.prefer_tcp_remote_dns), Some(PROXY_TAG), None, None)];
    if not_blank(&traffic.remote_dns2) {
        servers.push(dns_server("remote2", &maybe_tcp_dns(&traffic.remote_dns2, o.prefer_tcp_remote_dns), Some(PROXY_TAG), None, None));
    }
    servers.push(dns_server("direct", &traffic.direct_dns, Some(direct_tag), None, None));
    if fake_enabled {
        servers.push(dns_server("fake", "fakeip", None, Some(&fake4), Some(&fake6)));
    }
    let dns_rules: Vec<Value> = if fake_enabled { vec![json!({ "query_type": ["A", "AAAA"], "server": "fake" })] } else { vec![] };
    config.insert(
        "dns".into(),
        json!({ "servers": servers, "rules": dns_rules, "final": "remote", "strategy": effective_strategy }),
    );

    // ---- inbounds ----
    let mut inbounds = Vec::new();
    if let Some(r) = &o.warp_relay {
        inbounds.push(json!({
            "type": "direct", "tag": WARP_RELAY_IN_TAG, "listen": "127.0.0.1", "listen_port": r.listen_port,
            "network": "udp", "override_address": r.target_host, "override_port": r.target_port,
        }));
    }
    if o.tun_mode {
        let mut tun = Obj::new();
        tun.insert("type".into(), json!("tun"));
        tun.insert("tag".into(), json!(TUN_IN_TAG));
        tun.insert("address".into(), json!(["172.19.0.1/28", "fdfe:dcba:9876::1/126"]));
        tun.insert("mtu".into(), json!(9000));
        tun.insert("auto_route".into(), json!(true));
        tun.insert("strict_route".into(), json!(false));
        tun.insert("stack".into(), json!("mixed"));
        let mut excluded: Vec<String> = Vec::new();
        for a in o.tun_exclude_addresses.iter().map(|a| a.trim()).filter(|a| !a.is_empty()) {
            let c = if a.contains('/') { a.to_string() } else if a.contains(':') { format!("{a}/128") } else { format!("{a}/32") };
            if !excluded.contains(&c) {
                excluded.push(c);
            }
        }
        if !excluded.is_empty() {
            tun.insert("route_exclude_address".into(), json!(excluded));
        }
        inbounds.push(Value::Object(tun));
    }
    let mut socks = Obj::new();
    socks.insert("type".into(), json!(if o.mixed_inbound { "mixed" } else { "socks" }));
    socks.insert("tag".into(), json!(SOCKS_IN_TAG));
    socks.insert("listen".into(), json!(o.listen_host));
    socks.insert("listen_port".into(), json!(o.listen_port));
    if not_blank(&o.socks_username) {
        socks.insert("users".into(), json!([{ "username": o.socks_username, "password": o.socks_password }]));
    }
    inbounds.push(Value::Object(socks));
    if let Some(port) = o.tproxy_port {
        inbounds.push(json!({ "type": "tproxy", "tag": TPROXY_IN_TAG, "listen": o.listen_host, "listen_port": port }));
    }
    config.insert("inbounds".into(), Value::Array(inbounds));

    // ---- outbounds + endpoints ----
    // WireGuard (1.13+) and OpenVPN (1.14+) are native endpoints, not outbounds. An endpoint tag is
    // routable and can be used as a cascade detour exactly like an outbound tag.
    let wg_base_endpoint = o.wireguard_base.as_ref().and_then(|p| wireguard_endpoint(p, WG_BASE_TAG, o.auto_detect_interface, None));
    let second = o.second_profile.as_ref().filter(|p| p.is_complete());
    let base_detour = if wg_base_endpoint.is_some() {
        Some(WG_BASE_TAG)
    } else if o.olcrtc_chain_port.is_some() {
        Some(OLCRTC_TAG)
    } else {
        None
    };
    let main_tag = if second.is_some() { PROXY_BASE_TAG } else { PROXY_TAG };
    let main_endpoint = native_endpoint(profile, main_tag, o.auto_detect_interface, base_detour);
    let second_endpoint = second.and_then(|p| native_endpoint(p, PROXY_TAG, o.auto_detect_interface, Some(PROXY_BASE_TAG)));
    let main_is_endpoint = main_endpoint.is_some();
    let second_is_endpoint = second_endpoint.is_some();

    let mut outbounds = Vec::new();
    if let Some(second) = second {
        if !main_is_endpoint {
            outbounds.push(proxy_outbound(profile, false, traffic, base_detour, None, PROXY_BASE_TAG)?);
        }
        if o.warp_relay.is_some() {
            outbounds.push(proxy_outbound(second, false, traffic, None, None, PROXY_TAG)?);
        } else if !second_is_endpoint {
            outbounds.push(proxy_outbound(second, false, traffic, Some(PROXY_BASE_TAG), o.advanced.as_ref(), PROXY_TAG)?);
        }
    } else if !main_is_endpoint {
        outbounds.push(proxy_outbound(profile, false, traffic, base_detour, o.advanced.as_ref(), PROXY_TAG)?);
    }
    if let Some(port) = o.olcrtc_chain_port {
        outbounds.push(olcrtc_socks_outbound(OLCRTC_TAG, port, &o.olcrtc_chain_user, &o.olcrtc_chain_pass));
    }
    let mut direct = Obj::new();
    direct.insert("type".into(), json!("direct"));
    direct.insert("tag".into(), json!("direct"));
    // Hybrid IPv6 modes: bypass traffic never egresses over the user's real IPv6.
    if effective_strategy == "prefer_ipv4" || effective_strategy == "prefer_ipv6" {
        direct.insert("domain_resolver".into(), json!({ "server": "direct", "strategy": "ipv4_only" }));
    }
    outbounds.push(Value::Object(direct));
    config.insert("outbounds".into(), Value::Array(outbounds));
    let endpoints: Vec<Value> = second_endpoint.into_iter().chain(main_endpoint).chain(wg_base_endpoint).collect();
    if !endpoints.is_empty() {
        config.insert("endpoints".into(), Value::Array(endpoints));
    }

    // ---- route ----
    // A sing-box JSON subscription's own route takes precedence over the app's rules.
    let embedded_route = parse_object(profile.raw_singbox_route.as_deref().filter(|s| not_blank(s)));
    let embedded_rules: Vec<Obj> = embedded_route
        .as_ref()
        .and_then(|r| r.get("rules"))
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|v| v.as_object().cloned()).collect())
        .unwrap_or_default();
    let has_embedded = !embedded_rules.is_empty();
    let embedded_uses_ip = embedded_rules.iter().any(|r| r.contains_key("ip_cidr") || r.contains_key("rule_set") || r.contains_key("ip_is_private"));
    let rp = o.routing_profile.as_ref();

    let final_tag: String = if has_embedded {
        embedded_route.as_ref().and_then(|r| r.get("final")).and_then(Value::as_str).unwrap_or(PROXY_TAG).to_string()
    } else if let Some(p) = rp {
        sbr::final_outbound(p, direct_tag).to_string()
    } else {
        PROXY_TAG.to_string()
    };

    let mut rules: Vec<Value> = Vec::new();
    if o.warp_relay.is_some() {
        rules.push(json!({ "inbound": [WARP_RELAY_IN_TAG], "outbound": PROXY_BASE_TAG }));
    }
    let sb_expert = rp.is_some_and(|p| p.expert_enabled);
    let sb_expert_strategy = rp.filter(|p| p.expert_enabled).map(|p| p.singbox_domain_strategy.clone()).filter(|s| not_blank(s));
    if o.sniff_override_destination || (o.advanced.as_ref().map_or(true, |a| a.sniff) && (!sb_expert || rp.unwrap().singbox_sniff)) {
        rules.push(json!({ "action": "sniff" }));
    }
    if o.tun_mode || o.hijack_dns {
        rules.push(json!({ "protocol": ["dns"], "action": "hijack-dns" }));
    }
    let split: Vec<&String> = o.split_tunnel_processes.iter().filter(|p| not_blank(p)).collect();
    if o.tun_mode && !split.is_empty() {
        match o.split_tunnel_mode.as_str() {
            SPLIT_TUNNEL_BYPASS => rules.push(json!({ "process_name": split, "outbound": direct_tag })),
            SPLIT_TUNNEL_PROXY => rules.push(json!({ "process_name": split, "invert": true, "outbound": direct_tag })),
            _ => {}
        }
    }
    if o.block_quic {
        rules.push(json!({ "protocol": ["quic"], "action": "reject" }));
        rules.push(json!({ "network": "udp", "port": [443], "action": "reject" }));
    }
    if fake_enabled || effective_strategy == "ipv4_only" || effective_strategy == "ipv6_only" {
        rules.push(json!({ "domain_suffix": DOH_BLOCK_SUFFIXES, "action": "reject" }));
        rules.push(json!({ "domain": ["use-application-dns.net"], "action": "reject" }));
    }
    if let Some(spec) = o.fake_dns_spec.as_ref().filter(|s| !s.block_regex.is_empty()) {
        rules.push(json!({ "domain_regex": spec.block_regex, "action": "reject" }));
    }
    // A FakeDNS address is synthetic and must reach the proxy — before the private/LAN rule
    // (fc00::/18 sits inside fc00::/7, which ip_is_private matches).
    if fake_enabled {
        rules.push(json!({ "ip_cidr": [fake4, fake6], "outbound": PROXY_TAG }));
    }
    if rp.is_some() || routing.bypass_lan {
        rules.push(json!({ "ip_is_private": true, "outbound": direct_tag }));
    }
    let expert_strategy = sb_expert_strategy.clone().unwrap_or_else(|| effective_strategy.clone());
    let force_family = o.force_family_resolve && (expert_strategy == "ipv4_only" || expert_strategy == "ipv6_only");
    let manual_use_ip = routing.rules.iter().any(|r| r.enabled && !r.ip.is_empty());
    if o.allow_local_resolve
        && (rp.is_some_and(|p| p.uses_ip_rules())
            || routing.bypass_russia
            || force_family
            || manual_use_ip
            || embedded_uses_ip
            || (sb_expert && rp.unwrap().singbox_resolve))
    {
        rules.push(json!({ "action": "resolve", "strategy": expert_strategy }));
    }
    // Drop (not RST) the other family so Happy-Eyeballs falls back softly.
    if force_family {
        match expert_strategy.as_str() {
            "ipv4_only" => rules.push(json!({ "ip_cidr": ["::/0"], "action": "reject", "method": "drop" })),
            "ipv6_only" => rules.push(json!({ "ip_cidr": ["0.0.0.0/0"], "action": "reject", "method": "drop" })),
            _ => {}
        }
    }
    if has_embedded {
        for mut rule in embedded_rules {
            if direct_tag != "direct" && rule.get("outbound").and_then(Value::as_str) == Some("direct") {
                rule.insert("outbound".into(), json!(direct_tag));
            }
            rules.push(Value::Object(rule));
        }
    } else {
        rules.extend(parse_json_array(&routing.custom_rules_json));
        rules.extend(sbr::manual_rules(&routing.rules, o.match_apps_by_process, direct_tag));
        if !routing.block_domains.is_empty() {
            rules.push(json!({ "domain_suffix": routing.block_domains, "action": "reject" }));
        }
        if routing.block_ads {
            // Core Google infrastructure is not ads; the category-ads-all set would take it down.
            rules.push(json!({ "domain_suffix": ["google.com", "gstatic.com", "googleapis.com"], "outbound": PROXY_TAG }));
            rules.push(json!({ "rule_set": "geosite-ads", "action": "reject" }));
        }
        if let Some(p) = rp {
            let hide_v6 = effective_strategy == "prefer_ipv4" || effective_strategy == "prefer_ipv6";
            rules.extend(sbr::rules(p, hide_v6, direct_tag));
        }
        if !routing.direct_domains.is_empty() {
            rules.push(json!({ "domain_suffix": routing.direct_domains, "outbound": direct_tag }));
        }
        if routing.bypass_russia {
            rules.push(json!({ "rule_set": ["geoip-ru", "geosite-ru"], "outbound": direct_tag }));
        }
    }

    // Rule-sets are fetched before endpoints start: WireGuard tunnels fetch over `direct`.
    let rule_set_detour = if o.wireguard_base.is_some() || main_is_endpoint { "direct" } else { sbr::RULE_SET_DOWNLOAD_TAG };
    let mut sets: Vec<Value> = Vec::new();
    if let Some(a) = embedded_route.as_ref().and_then(|r| r.get("rule_set")).and_then(Value::as_array) {
        sets.extend(a.iter().filter(|v| v.is_object()).cloned());
    }
    if !has_embedded {
        if let Some(p) = rp {
            sets.extend(sbr::rule_sets(p, &o.singbox_geosite_base, &o.singbox_geoip_base, rule_set_detour));
        }
        sets.extend(parse_json_array(&routing.custom_rule_sets_json).into_iter().filter(Value::is_object));
        sets.extend(sbr::manual_rule_sets(&routing.rules, &o.singbox_geosite_base, &o.singbox_geoip_base, rule_set_detour));
        let remote = |tag: &str, url: &str| json!({ "type": "remote", "tag": tag, "format": "binary", "url": url, "download_detour": rule_set_detour });
        if routing.block_ads {
            sets.push(remote("geosite-ads", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"));
        }
        if routing.bypass_russia {
            sets.push(remote("geoip-ru", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"));
            sets.push(remote("geosite-ru", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"));
        }
    }
    // De-duplicate by tag (a duplicate tag is a hard error): the LAST definition wins, at the
    // position of the first (Kotlin `associateBy`).
    let mut merged: Vec<(String, Value)> = Vec::new();
    for s in sets {
        let key = s.get("tag").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| s.to_string());
        match merged.iter_mut().find(|(k, _)| *k == key) {
            Some(slot) => slot.1 = s,
            None => merged.push((key, s)),
        }
    }

    let mut route = Obj::new();
    route.insert("final".into(), json!(final_tag));
    route.insert("auto_detect_interface".into(), json!(o.auto_detect_interface));
    route.insert("default_domain_resolver".into(), json!({ "server": "direct" }));
    route.insert("rules".into(), Value::Array(rules));
    if !merged.is_empty() {
        route.insert("rule_set".into(), Value::Array(merged.into_iter().map(|(_, v)| v).collect()));
    }
    config.insert("route".into(), Value::Object(route));

    if let Some(path) = o.cache_file_path.as_deref().filter(|p| not_blank(p)) {
        let mut cache = Obj::new();
        cache.insert("enabled".into(), json!(true));
        cache.insert("path".into(), json!(path));
        if fake_enabled {
            cache.insert("store_fakeip".into(), json!(true));
        }
        config.insert("experimental".into(), json!({ "cache_file": cache }));
    }
    Ok(Value::Object(config))
}

/// One `dns.servers` entry in the 1.14 typed form (`type` + `server`/`server_port`/`path`).
fn dns_server(tag: &str, address: &str, detour: Option<&str>, fake4: Option<&str>, fake6: Option<&str>) -> Value {
    let raw = address.trim();
    let (scheme, rest) = match raw.split_once("://") {
        Some((s, r)) => (s.to_lowercase(), r),
        None => (String::new(), raw),
    };
    let (host_part, path) = match rest.split_once('/') {
        Some((h, p)) if !p.trim().is_empty() => (h, Some(format!("/{p}"))),
        Some((h, _)) => (h, None),
        None => (rest, None),
    };
    let port_sep = if let Some(b) = host_part.rfind(']') {
        host_part[b..].find(':').map(|i| b + i)
    } else if host_part.matches(':').count() == 1 {
        host_part.find(':')
    } else {
        None
    };
    let (host, port) = match port_sep.filter(|i| *i > 0) {
        Some(i) => (&host_part[..i], host_part[i + 1..].parse::<i32>().ok()),
        None => (host_part, None),
    };
    let kind = if !scheme.is_empty() {
        scheme
    } else if raw.eq_ignore_ascii_case("local") || raw.eq_ignore_ascii_case("localhost") || raw.is_empty() {
        "local".into()
    } else if raw.eq_ignore_ascii_case("fakeip") {
        "fakeip".into()
    } else {
        "udp".into()
    };
    let mut m = Obj::new();
    m.insert("tag".into(), json!(tag));
    m.insert("type".into(), json!(kind));
    match kind.as_str() {
        "local" | "fakeip" => {}
        "dhcp" => {
            if not_blank(host) && !host.eq_ignore_ascii_case("auto") {
                m.insert("interface".into(), json!(host));
            }
        }
        _ => {
            let h = host.strip_prefix('[').unwrap_or(host);
            let h = h.strip_suffix(']').unwrap_or(h);
            m.insert("server".into(), json!(h));
            if let Some(p) = port {
                m.insert("server_port".into(), json!(p));
            }
            if let Some(p) = path.filter(|p| p != "/dns-query" && (kind == "https" || kind == "h3")) {
                m.insert("path".into(), json!(p));
            }
        }
    }
    if kind == "fakeip" {
        m.insert("inet4_range".into(), json!(fake4.unwrap_or("198.18.0.0/15")));
        m.insert("inet6_range".into(), json!(fake6.unwrap_or("fc00::/18")));
    }
    // 1.14 refuses a detour to the bare `direct` outbound; local/fakeip dial nothing.
    if let Some(d) = detour.filter(|d| *d != "direct" && kind != "local" && kind != "fakeip") {
        m.insert("detour".into(), json!(d));
    }
    Value::Object(m)
}

/// `8.8.8.8` / `udp://…` → `tcp://…` when `prefer_tcp` (rides SOCKS CONNECT, not UDP-ASSOCIATE).
fn maybe_tcp_dns(address: &str, prefer_tcp: bool) -> String {
    if !prefer_tcp {
        return address.into();
    }
    let a = address.trim();
    if a.is_empty() {
        return address.into();
    }
    if a.len() >= 6 && a[..6].eq_ignore_ascii_case("udp://") {
        return format!("tcp://{}", &a[6..]);
    }
    if a.contains("://") || (!a.contains('.') && !a.contains(':')) {
        return address.into();
    }
    format!("tcp://{a}")
}

fn proxy_outbound(
    p: &ProxyProfile,
    chained: bool,
    traffic: &TrafficSettings,
    detour_override: Option<&str>,
    advanced: Option<&AdvancedCoreConfig>,
    tag: &str,
) -> Result<Value, ConfigError> {
    let detour = detour_override.or(if chained { Some(OLCRTC_TAG) } else { None });
    let tfo = advanced.is_some_and(|a| a.tcp_fast_open);
    // A raw sing-box outbound is used verbatim (tag/detour injected).
    if let Some(raw) = p.raw_outbound.as_deref().filter(|r| not_blank(r)) {
        if let Some(obj) = parse_object(Some(raw)) {
            let no_mux = obj.get("type").and_then(Value::as_str).is_some_and(|t| RAW_OUTBOUND_NO_MUX.contains(&t));
            let mut m: Obj = obj.into_iter().filter(|(k, _)| k != "tag" && k != "detour").collect();
            m.insert("tag".into(), json!(tag));
            if let Some(d) = detour {
                m.insert("detour".into(), json!(d));
            }
            if tfo && !no_mux && !raw.contains("tcp_fast_open") {
                m.insert("tcp_fast_open".into(), json!(true));
            }
            if !no_mux && !raw.contains("multiplex") {
                if let Some(mux) = multiplex(traffic, advanced) {
                    m.insert("multiplex".into(), mux);
                }
            }
            return Ok(Value::Object(m));
        }
    }
    let mut m = Obj::new();
    m.insert("type".into(), json!(p.kind));
    m.insert("tag".into(), json!(tag));
    m.insert("server".into(), json!(p.server));
    m.insert("server_port".into(), json!(p.server_port));
    match p.kind.as_str() {
        TYPE_VLESS => {
            m.insert("uuid".into(), json!(p.uuid));
            if not_blank(&p.flow) {
                m.insert("flow".into(), json!(p.flow));
            }
            // xudp makes UDP (DNS/QUIC) ride the vless tunnel; it coexists with vision.
            m.insert("packet_encoding".into(), json!("xudp"));
        }
        TYPE_VMESS => {
            m.insert("uuid".into(), json!(p.uuid));
            m.insert("alter_id".into(), json!(p.alter_id));
            m.insert("security".into(), json!(if not_blank(&p.cipher) { p.cipher.as_str() } else { "auto" }));
        }
        TYPE_TROJAN => {
            m.insert("password".into(), json!(p.password));
        }
        TYPE_SHADOWSOCKS => {
            m.insert("method".into(), json!(p.method));
            m.insert("password".into(), json!(p.password));
        }
        TYPE_HYSTERIA2 => {
            m.insert("password".into(), json!(p.password));
            if p.hy2_up_mbps > 0 {
                m.insert("up_mbps".into(), json!(p.hy2_up_mbps));
            }
            if p.hy2_down_mbps > 0 {
                m.insert("down_mbps".into(), json!(p.hy2_down_mbps));
            }
            if p.hy2_obfs == "salamander" && not_blank(&p.hy2_obfs_password) {
                m.insert("obfs".into(), json!({ "type": "salamander", "password": p.hy2_obfs_password }));
            }
            let ranges = hy2_server_ports(&p.hy2_ports);
            if !ranges.is_empty() {
                m.insert("server_ports".into(), json!(ranges));
            }
        }
        TYPE_NAIVE => {
            if not_blank(&p.username) {
                m.insert("username".into(), json!(p.username));
            }
            if not_blank(&p.password) {
                m.insert("password".into(), json!(p.password));
            }
            if p.naive_quic {
                m.insert("quic".into(), json!(true));
            }
        }
        _ => {}
    }
    if p.kind != TYPE_SHADOWSOCKS {
        if let Some(tls) = tls(p) {
            m.insert("tls".into(), tls);
        }
        if p.kind != TYPE_HYSTERIA2 && p.kind != TYPE_NAIVE {
            if let Some(t) = transport(p)? {
                m.insert("transport".into(), t);
            }
        }
    }
    if let Some(d) = detour {
        m.insert("detour".into(), json!(d));
    }
    if p.kind != TYPE_HYSTERIA2 && p.kind != TYPE_NAIVE {
        if tfo {
            m.insert("tcp_fast_open".into(), json!(true));
        }
        if let Some(mux) = multiplex(traffic, advanced) {
            m.insert("multiplex".into(), mux);
        }
    }
    Ok(Value::Object(m))
}

/// `443,2000-3000` → `["443:443","2000:3000"]` (invalid chunks dropped).
fn hy2_server_ports(spec: &str) -> Vec<String> {
    spec.split(',')
        .filter_map(|chunk| {
            let part = chunk.trim();
            if part.is_empty() {
                return None;
            }
            let bits: Vec<&str> = part.split(['-', ':']).map(str::trim).collect();
            let start: i32 = bits.first()?.parse().ok()?;
            let end: i32 = if bits.len() > 1 { bits[1].parse().ok()? } else { start };
            ((1..=65535).contains(&start) && (1..=65535).contains(&end) && end >= start).then(|| format!("{start}:{end}"))
        })
        .collect()
}

fn olcrtc_socks_outbound(tag: &str, port: i32, user: &str, pass: &str) -> Value {
    let mut m = Obj::new();
    m.insert("type".into(), json!("socks"));
    m.insert("tag".into(), json!(tag));
    m.insert("server".into(), json!("127.0.0.1"));
    m.insert("server_port".into(), json!(port));
    m.insert("version".into(), json!("5"));
    if not_blank(user) {
        m.insert("username".into(), json!(user));
        m.insert("password".into(), json!(pass));
    }
    Value::Object(m)
}

fn is_loopback_host(host: &str) -> bool {
    let h = host.trim().trim_matches(['[', ']']).to_lowercase();
    h == "localhost" || h == "::1" || h.starts_with("127.")
}

/// Native tunnel protocols represented by top-level sing-box `endpoints`, never by outbounds.
fn native_endpoint(p: &ProxyProfile, tag: &str, auto_detect_interface: bool, detour: Option<&str>) -> Option<Value> {
    match p.kind.as_str() {
        "wireguard" => wireguard_endpoint(p, tag, auto_detect_interface, detour),
        TYPE_OPENVPN => openvpn_endpoint(p, tag, detour),
        _ => None,
    }
}

/// Re-tag the imported sing-box 1.14 `openvpn-client` JSON as a top-level endpoint.
fn openvpn_endpoint(p: &ProxyProfile, tag: &str, detour: Option<&str>) -> Option<Value> {
    let obj = parse_object(p.raw_outbound.as_deref().filter(|r| not_blank(r)))?;
    if obj.get("type").and_then(Value::as_str) != Some(TYPE_OPENVPN) {
        return None;
    }
    let mut m: Obj = obj.into_iter().filter(|(k, _)| k != "tag" && k != "detour").collect();
    m.insert("tag".into(), json!(tag));
    // YPtun owns the platform VPN/TUN; the endpoint must expose userspace TCP/UDP flows to sing-box.
    m.entry("system").or_insert_with(|| json!(false));
    if let Some(d) = detour {
        m.insert("detour".into(), json!(d));
    }
    Some(Value::Object(m))
}

/// A stored WireGuard outbound JSON → a sing-box 1.13+ WireGuard `endpoint`.
fn wireguard_endpoint(p: &ProxyProfile, tag: &str, auto_detect_interface: bool, detour: Option<&str>) -> Option<Value> {
    let obj = parse_object(p.raw_outbound.as_deref().filter(|r| not_blank(r)))?;
    if obj.get("type").and_then(Value::as_str) != Some("wireguard") {
        return None;
    }
    let server = obj.get("server").and_then(Value::as_str).unwrap_or("127.0.0.1").to_string();
    let port = obj.get("server_port").and_then(Value::as_i64)?;
    let private_key = obj.get("private_key").and_then(Value::as_str)?;
    let peer_public_key = obj.get("peer_public_key").and_then(Value::as_str)?;
    let addrs = obj.get("local_address").and_then(Value::as_array).cloned().unwrap_or_default();
    let mtu = obj.get("mtu").and_then(Value::as_i64);
    let mut m = Obj::new();
    m.insert("type".into(), json!("wireguard"));
    m.insert("tag".into(), json!(tag));
    if let Some(d) = detour {
        m.insert("detour".into(), json!(d));
    }
    m.insert("system".into(), json!(false));
    // Desktop: keep the bind-to-interface control off a loopback peer (WSAEADDRNOTAVAIL otherwise).
    if auto_detect_interface && is_loopback_host(&server) {
        m.insert("inet4_bind_address".into(), json!("0.0.0.0"));
    }
    if let Some(mtu) = mtu.filter(|m| *m > 0) {
        m.insert("mtu".into(), json!(mtu));
    }
    m.insert("address".into(), Value::Array(addrs));
    m.insert("private_key".into(), json!(private_key));
    m.insert("peers".into(), json!([{ "address": server, "port": port, "public_key": peer_public_key, "allowed_ips": ["0.0.0.0/0"] }]));
    Some(Value::Object(m))
}

fn multiplex(traffic: &TrafficSettings, advanced: Option<&AdvancedCoreConfig>) -> Option<Value> {
    if let Some(a) = advanced {
        return a.mux_enabled.then(|| json!({ "enabled": true, "protocol": a.mux_protocol, "max_streams": a.mux_max_streams }));
    }
    traffic.mux_enabled.then(|| json!({ "enabled": true, "protocol": traffic.mux_protocol, "max_connections": traffic.mux_max_connections }))
}

fn tls(p: &ProxyProfile) -> Option<Value> {
    if p.security != SECURITY_TLS && p.security != SECURITY_REALITY {
        return None;
    }
    let mut m = Obj::new();
    m.insert("enabled".into(), json!(true));
    m.insert("server_name".into(), json!(if not_blank(&p.sni) { &p.sni } else { &p.server }));
    m.insert("insecure".into(), json!(p.allow_insecure));
    if !p.alpn.is_empty() {
        m.insert("alpn".into(), json!(p.alpn));
    }
    if not_blank(&p.fingerprint) {
        m.insert("utls".into(), json!({ "enabled": true, "fingerprint": p.fingerprint }));
    }
    if p.security == SECURITY_REALITY && not_blank(&p.reality_public_key) {
        m.insert("reality".into(), json!({ "enabled": true, "public_key": p.reality_public_key, "short_id": p.reality_short_id }));
    }
    Some(Value::Object(m))
}

fn transport(p: &ProxyProfile) -> Result<Option<Value>, ConfigError> {
    let mut m = Obj::new();
    match p.network.as_str() {
        NETWORK_WS => {
            m.insert("type".into(), json!("ws"));
            if not_blank(&p.path) {
                m.insert("path".into(), json!(p.path));
            }
            if not_blank(&p.host) {
                m.insert("headers".into(), json!({ "Host": p.host }));
            }
        }
        NETWORK_HTTPUPGRADE => {
            m.insert("type".into(), json!("httpupgrade"));
            if not_blank(&p.path) {
                m.insert("path".into(), json!(p.path));
            }
            if not_blank(&p.host) {
                m.insert("host".into(), json!(p.host));
            }
        }
        NETWORK_GRPC => {
            m.insert("type".into(), json!("grpc"));
            if not_blank(&p.path) {
                m.insert("service_name".into(), json!(p.path));
            }
        }
        NETWORK_HTTP => {
            m.insert("type".into(), json!("http"));
            if not_blank(&p.path) {
                m.insert("path".into(), json!(p.path));
            }
            if not_blank(&p.host) {
                m.insert("host".into(), json!([p.host]));
            }
        }
        NETWORK_XHTTP => return Err(ConfigError::XhttpNeedsXray),
        _ => return Ok(None),
    }
    Ok(Some(Value::Object(m)))
}
