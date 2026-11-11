//! RoutingProfile → Xray `routing` (port of `XrayRouting`). Xray takes `geosite:`/`geoip:`/`domain:`
//! selectors natively, so the buckets map almost 1:1.

use serde_json::{json, Value};

use crate::routing::{asn, RoutingProfile};

pub const DIRECT_TAG: &str = "direct";
pub const BLOCK_TAG: &str = "block";
pub const PROXY_TAG: &str = "proxy";

/// Xray's `domainStrategy` for the profile (or the expert override).
pub fn domain_strategy(p: &RoutingProfile) -> String {
    if p.expert_enabled && !p.xray_domain_strategy.trim().is_empty() {
        return p.xray_domain_strategy.clone();
    }
    match p.domain_strategy.as_str() {
        "IPIfNonMatch" | "IPOnDemand" | "AsIs" => p.domain_strategy.clone(),
        "ipv4_only" | "ipv6_only" | "prefer_ipv4" | "prefer_ipv6" => "IPIfNonMatch".into(),
        _ => "AsIs".into(),
    }
}

/// `{ domainStrategy, rules }`.
pub fn routing_object(p: &RoutingProfile) -> Value {
    json!({ "domainStrategy": domain_strategy(p), "rules": rules(p) })
}

pub fn rules(p: &RoutingProfile) -> Vec<Value> {
    let mut out = bucket_rules(p);
    out.extend(catch_all_direct_rule(p));
    out
}

/// The ordered direct/block/proxy field rules (no fall-through).
pub fn bucket_rules(p: &RoutingProfile) -> Vec<Value> {
    p.ordered_buckets()
        .into_iter()
        .flat_map(|b| match b {
            "block" => split_rules(&p.block_sites, &p.block_ip, BLOCK_TAG),
            "direct" => split_rules(&p.direct_sites, &p.direct_ip, DIRECT_TAG),
            _ => split_rules(&p.proxy_sites, &p.proxy_ip, PROXY_TAG),
        })
        .collect()
}

/// "Everything unmatched → direct" for a non-global profile.
pub fn catch_all_direct_rule(p: &RoutingProfile) -> Option<Value> {
    (!p.global_proxy).then(|| json!({ "type": "field", "network": ["tcp", "udp"], "outboundTag": DIRECT_TAG }))
}

/// SEPARATE domain and ip rules: Xray AND-matches the two inside one rule, so `domain:ru → direct`
/// would only apply to .ru sites on Russian IPs.
fn split_rules(sites: &[String], ips: &[String], tag: &str) -> Vec<Value> {
    let s: Vec<&str> = sites.iter().map(|x| x.trim()).filter(|x| !x.is_empty()).collect();
    let mut i: Vec<String> = ips.iter().map(|x| x.trim()).filter(|x| !x.is_empty() && !asn::is_selector(x)).map(str::to_string).collect();
    // Telegram dials its data centres by IP: geosite:telegram alone never matches them.
    if s.iter().any(|x| x.eq_ignore_ascii_case("geosite:telegram")) && !i.iter().any(|x| x.eq_ignore_ascii_case("geoip:telegram")) {
        i.push("geoip:telegram".into());
    }
    let mut out = Vec::new();
    if !s.is_empty() {
        out.push(json!({ "type": "field", "domain": s, "outboundTag": tag }));
    }
    if !i.is_empty() {
        out.push(json!({ "type": "field", "ip": i, "outboundTag": tag }));
    }
    out
}
