//! RoutingProfile / SingBoxRule → sing-box `route.rules` + `route.rule_set` (port of
//! `SingBoxRouting`). sing-box has no `geosite:`/`geoip:` selectors: they become remote `.srs`
//! rule-sets (`geosite-ru`, `geoip-ru`).

use serde_json::{json, Map, Value};

use crate::routing::{asn, RoutingProfile, SingBoxRule, ACTION_HIJACK_DNS, ACTION_REJECT, ACTION_RESOLVE, ACTION_ROUTE_OPTIONS, ACTION_SNIFF, OUT_BLOCK, OUT_DIRECT};

pub const PROXY_TAG: &str = "proxy";
pub const DIRECT_TAG: &str = "direct";
/// Rule-sets are fetched through the tunnel: raw.githubusercontent.com is blocked where the app is
/// used, and a failed initial fetch stops the whole core.
pub const RULE_SET_DOWNLOAD_TAG: &str = PROXY_TAG;
pub const DEFAULT_GEOSITE_BASE: &str = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/";
pub const DEFAULT_GEOIP_BASE: &str = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/";
const META_GEOIP_BASE: &str = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geoip/";

/// SagerNet/sing-geoip ships only country sets; other categories come from MetaCubeX.
fn geoip_url(ip_base: &str, custom_base: &str, tag: &str) -> String {
    if (custom_base.trim().is_empty() || custom_base == DEFAULT_GEOIP_BASE) && tag.chars().count() != 2 {
        format!("{META_GEOIP_BASE}{tag}.srs")
    } else {
        format!("{ip_base}geoip-{tag}.srs")
    }
}

#[derive(Default)]
struct Selectors {
    suffix: Vec<String>,
    exact: Vec<String>,
    keyword: Vec<String>,
    regex: Vec<String>,
    cidr: Vec<String>,
    geosite: Vec<String>,
    geoip: Vec<String>,
}

impl Selectors {
    fn add(&mut self, o: Selectors) {
        self.suffix.extend(o.suffix);
        self.exact.extend(o.exact);
        self.keyword.extend(o.keyword);
        self.regex.extend(o.regex);
        self.cidr.extend(o.cidr);
        self.geosite.extend(o.geosite);
        self.geoip.extend(o.geoip);
    }
    fn dedup(mut self) -> Self {
        for v in [&mut self.suffix, &mut self.exact, &mut self.keyword, &mut self.regex, &mut self.cidr, &mut self.geosite, &mut self.geoip] {
            distinct(v);
        }
        self
    }
}

fn distinct(v: &mut Vec<String>) {
    let mut seen = Vec::with_capacity(v.len());
    v.retain(|x| {
        if seen.contains(x) {
            false
        } else {
            seen.push(x.clone());
            true
        }
    });
}

fn starts_ci(s: &str, p: &str) -> bool {
    s.len() >= p.len() && s.is_char_boundary(p.len()) && s[..p.len()].eq_ignore_ascii_case(p)
}

fn after_colon(v: &str) -> String {
    v.split_once(':').map(|(_, r)| r).unwrap_or(v).trim().to_string()
}

/// v2ray `domain:` on sing-box: the label itself + a dotted suffix (never `metaru` for `ru`).
fn domain_and_subdomains(v: &str) -> Selectors {
    let v = v.trim();
    if v.is_empty() {
        return Selectors::default();
    }
    if v.starts_with('.') {
        return Selectors { suffix: vec![v.into()], ..Default::default() };
    }
    Selectors { exact: vec![v.into()], suffix: vec![format!(".{v}")], ..Default::default() }
}

fn is_cidr_or_ip(v: &str) -> bool {
    if v.contains('/') || v.matches(':').count() >= 2 {
        return true;
    }
    let parts: Vec<&str> = v.split('.').collect();
    parts.len() == 4 && parts.iter().all(|p| (1..=3).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit()))
}

/// A bare address becomes /32 (v4) or /128 (v6).
fn to_cidr(v: &str) -> String {
    if v.contains('/') {
        v.into()
    } else if v.contains(':') {
        format!("{v}/128")
    } else {
        format!("{v}/32")
    }
}

fn parse(selectors: &[String]) -> Selectors {
    let mut out = Selectors::default();
    for raw in selectors {
        let v = raw.trim();
        if v.is_empty() {
            continue;
        }
        out.add(if starts_ci(v, "geosite:") {
            let tag = after_colon(v).to_lowercase();
            // Telegram dials its data centres by IP: the geoip list rides along.
            let geoip = if tag == "telegram" { vec![tag.clone()] } else { vec![] };
            Selectors { geosite: vec![tag], geoip, ..Default::default() }
        } else if starts_ci(v, "geoip:") {
            Selectors { geoip: vec![after_colon(v).to_lowercase()], ..Default::default() }
        } else if starts_ci(v, "domain:") {
            domain_and_subdomains(&after_colon(v))
        } else if starts_ci(v, "full:") {
            Selectors { exact: vec![after_colon(v)], ..Default::default() }
        } else if starts_ci(v, "keyword:") {
            Selectors { keyword: vec![after_colon(v)], ..Default::default() }
        } else if starts_ci(v, "regexp:") || starts_ci(v, "regex:") {
            Selectors { regex: vec![after_colon(v)], ..Default::default() }
        } else if is_cidr_or_ip(v) {
            Selectors { cidr: vec![to_cidr(v)], ..Default::default() }
        } else {
            domain_and_subdomains(v)
        });
    }
    out
}

fn parse_ip(selectors: &[String]) -> Selectors {
    let mut out = Selectors::default();
    for raw in selectors {
        let v = raw.trim();
        if v.is_empty() || asn::is_selector(v) {
            continue;
        }
        out.add(if starts_ci(v, "geoip:") {
            Selectors { geoip: vec![after_colon(v).to_lowercase()], ..Default::default() }
        } else if starts_ci(v, "geosite:") {
            Selectors { geosite: vec![after_colon(v).to_lowercase()], ..Default::default() }
        } else {
            Selectors { cidr: vec![to_cidr(v)], ..Default::default() }
        });
    }
    out
}

/// Site + ip selectors of one bucket, geoip tags from both.
fn bucket(sites: &[String], ips: &[String]) -> Selectors {
    let s = parse(sites);
    let i = parse_ip(ips);
    let mut all = Selectors::default();
    all.add(s);
    // geoip order: ip list first, then the site list (Kotlin `(i + s).flatMap { geoipTags }`).
    let site_geoip = std::mem::take(&mut all.geoip);
    all.add(i);
    all.geoip.extend(site_geoip);
    all.dedup()
}

fn match_rule(key: &str, values: Value, reject: bool, outbound: Option<&str>, ip_version: Option<i32>) -> Value {
    let mut m = Map::new();
    m.insert(key.into(), values);
    if let Some(v) = ip_version {
        m.insert("ip_version".into(), json!(v));
    }
    if reject {
        m.insert("action".into(), json!("reject"));
    } else {
        m.insert("outbound".into(), json!(outbound.unwrap_or(PROXY_TAG)));
    }
    Value::Object(m)
}

fn emit_bucket(out: &mut Vec<Value>, sites: &[String], ips: &[String], reject: bool, outbound: Option<&str>, ip_version: Option<i32>) {
    let b = bucket(sites, ips);
    if !b.geosite.is_empty() || !b.geoip.is_empty() {
        let tags: Vec<String> = b.geosite.iter().map(|t| format!("geosite-{t}")).chain(b.geoip.iter().map(|t| format!("geoip-{t}"))).collect();
        out.push(match_rule("rule_set", json!(tags), reject, outbound, ip_version));
    }
    for (key, list) in [("domain_suffix", &b.suffix), ("domain", &b.exact), ("domain_keyword", &b.keyword), ("domain_regex", &b.regex)] {
        if !list.is_empty() {
            out.push(match_rule(key, json!(list), reject, outbound, ip_version));
        }
    }
    // An IPv6-reject pass over ip_cidr entries would never match.
    if !b.cidr.is_empty() && ip_version.is_none() {
        out.push(match_rule("ip_cidr", json!(b.cidr), reject, outbound, None));
    }
}

/// The profile's route rules in its order. `hide_direct_ipv6` rejects IPv6 to the direct bucket
/// first (prefer_* hybrids); `direct_tag` is the base tunnel's tag for tunnels that must not leak.
pub fn rules(p: &RoutingProfile, hide_direct_ipv6: bool, direct_tag: &str) -> Vec<Value> {
    let mut out = Vec::new();
    for b in p.ordered_buckets() {
        match b {
            "block" => emit_bucket(&mut out, &p.block_sites, &p.block_ip, true, None, None),
            "direct" => {
                if hide_direct_ipv6 {
                    emit_bucket(&mut out, &p.direct_sites, &p.direct_ip, true, None, Some(6));
                }
                emit_bucket(&mut out, &p.direct_sites, &p.direct_ip, false, Some(direct_tag), None);
            }
            _ => emit_bucket(&mut out, &p.proxy_sites, &p.proxy_ip, false, Some(PROXY_TAG), None),
        }
    }
    out
}

/// Proxy for a global profile, direct otherwise.
pub fn final_outbound<'a>(p: &RoutingProfile, direct_tag: &'a str) -> &'a str {
    if p.global_proxy {
        PROXY_TAG
    } else {
        direct_tag
    }
}

fn base(b: &str, default: &str) -> String {
    let b = if b.trim().is_empty() { default } else { b };
    if b.ends_with('/') {
        b.into()
    } else {
        format!("{b}/")
    }
}

fn rule_set_defs(geosite: &[String], geoip: &[String], geosite_base: &str, geoip_base: &str, detour: &str) -> Vec<Value> {
    let site_base = base(geosite_base, DEFAULT_GEOSITE_BASE);
    let ip_base = base(geoip_base, DEFAULT_GEOIP_BASE);
    let remote = |tag: String, url: String| json!({ "type": "remote", "tag": tag, "format": "binary", "url": url, "download_detour": detour });
    geosite
        .iter()
        .map(|t| remote(format!("geosite-{t}"), format!("{site_base}geosite-{t}.srs")))
        .chain(geoip.iter().map(|t| remote(format!("geoip-{t}"), geoip_url(&ip_base, geoip_base, t))))
        .collect()
}

/// Remote `.srs` definitions for every geo tag the profile references.
pub fn rule_sets(p: &RoutingProfile, geosite_base: &str, geoip_base: &str, download_detour: &str) -> Vec<Value> {
    let mut site = parse(&p.block_sites);
    site.add(parse(&p.direct_sites));
    site.add(parse(&p.proxy_sites));
    let mut ip = parse_ip(&p.block_ip);
    ip.add(parse_ip(&p.direct_ip));
    ip.add(parse_ip(&p.proxy_ip));
    let mut geosite = site.geosite.clone();
    distinct(&mut geosite);
    let mut geoip: Vec<String> = ip.geoip.into_iter().chain(site.geoip).collect();
    distinct(&mut geoip);
    rule_set_defs(&geosite, &geoip, geosite_base, geoip_base, download_detour)
}

/// "443, 1000:2000" → single ports and `lo:hi` ranges.
fn split_ports(raw: &str) -> (Vec<i64>, Vec<String>) {
    let (mut single, mut ranges) = (Vec::new(), Vec::new());
    for tok in raw.split([',', ';', ' ', '\n']).map(str::trim).filter(|t| !t.is_empty()) {
        if tok.contains(':') || tok.contains('-') {
            let parts: Vec<&str> = tok.split([':', '-']).map(str::trim).collect();
            if parts.len() == 2 && parts[0].parse::<i32>().is_ok() && parts[1].parse::<i32>().is_ok() {
                ranges.push(format!("{}:{}", parts[0], parts[1]));
            }
        } else if let Ok(p) = tok.parse::<i32>() {
            single.push(p as i64);
        }
    }
    (single, ranges)
}

/// One `route.rules` object per enabled manual rule (fields ANDed). On desktop apps are matched by
/// `process_name` instead of Android's `package_name`.
pub fn manual_rules(rules: &[SingBoxRule], match_apps_by_process: bool, direct_tag: &str) -> Vec<Value> {
    rules
        .iter()
        .filter(|r| r.enabled && r.has_matcher())
        .map(|r| {
            let s = parse(&r.domains).dedup();
            let i = parse_ip(&r.ip).dedup();
            let src = parse_ip(&r.source).dedup();
            let mut tags: Vec<String> = s.geosite.iter().map(|t| format!("geosite-{t}")).collect();
            tags.extend(s.geoip.iter().chain(&i.geoip).map(|t| format!("geoip-{t}")));
            distinct(&mut tags);
            let (ports, port_ranges) = split_ports(&r.port);
            let (src_ports, src_ranges) = split_ports(&r.source_port);
            let mut m = Map::new();
            let mut put_list = |k: &str, v: Value, non_empty: bool| {
                if non_empty {
                    m.insert(k.into(), v);
                }
            };
            put_list("rule_set", json!(tags), !tags.is_empty());
            put_list("domain_suffix", json!(s.suffix), !s.suffix.is_empty());
            put_list("domain", json!(s.exact), !s.exact.is_empty());
            put_list("domain_keyword", json!(s.keyword), !s.keyword.is_empty());
            put_list("domain_regex", json!(s.regex), !s.regex.is_empty());
            put_list("ip_cidr", json!(i.cidr), !i.cidr.is_empty());
            put_list("source_ip_cidr", json!(src.cidr), !src.cidr.is_empty());
            put_list("port", json!(ports), !ports.is_empty());
            put_list("port_range", json!(port_ranges), !port_ranges.is_empty());
            put_list("source_port", json!(src_ports), !src_ports.is_empty());
            put_list("source_port_range", json!(src_ranges), !src_ranges.is_empty());
            put_list("network", json!(r.network), !r.network.trim().is_empty());
            put_list("network_type", json!(r.network_type), !r.network_type.is_empty());
            put_list("protocol", json!(r.protocol), !r.protocol.is_empty());
            put_list("client", json!(r.client), !r.client.is_empty());
            put_list("network_is_expensive", json!(true), r.network_is_expensive);
            put_list("clash_mode", json!(r.clash_mode), !r.clash_mode.trim().is_empty());
            put_list(if match_apps_by_process { "process_name" } else { "package_name" }, json!(r.package_names), !r.package_names.is_empty());
            let (k, v) = match r.action.as_str() {
                ACTION_REJECT => ("action", "reject"),
                ACTION_HIJACK_DNS => ("action", "hijack-dns"),
                ACTION_SNIFF => ("action", "sniff"),
                ACTION_RESOLVE => ("action", "resolve"),
                ACTION_ROUTE_OPTIONS => ("action", "route-options"),
                _ => match r.outbound.as_str() {
                    OUT_BLOCK => ("action", "reject"),
                    OUT_DIRECT => ("outbound", direct_tag),
                    _ => ("outbound", PROXY_TAG),
                },
            };
            m.insert(k.into(), json!(v));
            Value::Object(m)
        })
        .collect()
}

/// Remote `.srs` definitions for every geo tag the manual rules reference.
pub fn manual_rule_sets(rules: &[SingBoxRule], geosite_base: &str, geoip_base: &str, download_detour: &str) -> Vec<Value> {
    let enabled: Vec<&SingBoxRule> = rules.iter().filter(|r| r.enabled && r.has_matcher()).collect();
    let mut geosite: Vec<String> = enabled.iter().flat_map(|r| parse(&r.domains).geosite).collect();
    distinct(&mut geosite);
    let mut geoip: Vec<String> = enabled.iter().flat_map(|r| parse(&r.domains).geoip.into_iter().chain(parse_ip(&r.ip).geoip)).collect();
    distinct(&mut geoip);
    rule_set_defs(&geosite, &geoip, geosite_base, geoip_base, download_detour)
}
