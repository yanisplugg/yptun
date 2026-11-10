//! The verbatim-config path of [`super`]: a user's full Xray JSON made runnable through the bridge
//! (`prepare_raw`), the raw-config ping probe and the helpers they share. Split out of
//! `xray/config.rs` unchanged, to keep each source file small.

use super::*;

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
    let proxy_tags: Vec<String> = user_outbounds
        .iter()
        .filter(|ob| str_of(ob, "protocol").is_some_and(|p| cascade_types.contains(&p.to_lowercase().as_str())))
        .filter_map(|ob| str_of(ob, "tag").map(str::to_string))
        .collect();
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
    let proxy_balancers = raw_proxy_balancer_plan(user_routing.as_ref(), &proxy_tags);
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
        (Some(_), Some(mt)) if warp_server => rewrite_raw_exit_routes(geo_stripped, mt, &proxy_balancers, CASCADE_EXIT_TAG),
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
        let mut relay_rule = Obj::new();
        relay_rule.insert("type".into(), json!("field"));
        relay_rule.insert("inboundTag".into(), json!([WARP_RELAY_IN_TAG]));
        if let Some(tag) = proxy_balancers.primary_tag.as_deref() {
            relay_rule.insert("balancerTag".into(), json!(tag));
        } else {
            relay_rule.insert("outboundTag".into(), json!(main_tag));
        }
        prepend(to_write, Value::Object(relay_rule))
    } else {
        to_write
    };
    let routing_final = if (warp_client || warp_server) && !proxy_balancers.selections.is_empty() {
        freeze_raw_proxy_balancer_selectors(
            routing_final,
            &proxy_balancers,
            root.contains_key("observatory") || root.contains_key("burstObservatory"),
        )
    } else {
        routing_final
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

#[derive(Debug, Clone, Default)]
struct RawProxyBalancerPlan {
    /// `(balancer tag, original proxy outbound tags selected by it)`, in config order.
    selections: Vec<(String, Vec<String>)>,
    /// Last proxy balancer referenced by routing rules (normally the catch-all), else the first.
    primary_tag: Option<String>,
}

impl RawProxyBalancerPlan {
    fn selection(&self, tag: &str) -> Option<&Vec<String>> {
        self.selections.iter().find(|(candidate, _)| candidate == tag).map(|(_, selected)| selected)
    }

    fn contains(&self, tag: &str) -> bool {
        self.selection(tag).is_some()
    }
}

fn raw_proxy_balancer_plan(routing: Option<&Obj>, proxy_tags: &[String]) -> RawProxyBalancerPlan {
    let Some(routing) = routing else { return RawProxyBalancerPlan::default() };
    if proxy_tags.is_empty() {
        return RawProxyBalancerPlan::default();
    }
    let mut selections: Vec<(String, Vec<String>)> = Vec::new();
    if let Some(balancers) = routing.get("balancers").and_then(Value::as_array) {
        for balancer in balancers.iter().filter_map(Value::as_object) {
            let Some(tag) = str_of(balancer, "tag") else { continue };
            let selectors: Vec<&str> = balancer
                .get("selector")
                .and_then(Value::as_array)
                .map(|items| items.iter().filter_map(Value::as_str).collect())
                .unwrap_or_default();
            let selected: Vec<String> = proxy_tags
                .iter()
                .filter(|outbound| selectors.iter().any(|selector| outbound.starts_with(selector)))
                .cloned()
                .collect();
            if !selected.is_empty() {
                selections.push((tag.to_string(), selected));
            }
        }
    }
    let primary_tag = routing
        .get("rules")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_object)
        .filter_map(|rule| rule.get("balancerTag").and_then(Value::as_str))
        .filter(|tag| selections.iter().any(|(candidate, _)| candidate == tag))
        .last()
        .map(str::to_string)
        .or_else(|| selections.first().map(|(tag, _)| tag.clone()));
    RawProxyBalancerPlan { selections, primary_tag }
}

/// Redirect app traffic that used either the old main outbound or one of its balancers to WARP.
fn rewrite_raw_exit_routes(routing: Option<Obj>, from_outbound: &str, plan: &RawProxyBalancerPlan, to: &str) -> Option<Obj> {
    let mut routing = routing?;
    if let Some(Value::Array(rules)) = routing.get_mut("rules") {
        for rule in rules.iter_mut().filter_map(Value::as_object_mut) {
            let outbound_match = rule.get("outboundTag").and_then(Value::as_str) == Some(from_outbound);
            let balancer_match = rule.get("balancerTag").and_then(Value::as_str).is_some_and(|tag| plan.contains(tag));
            if outbound_match || balancer_match {
                rule.remove("outboundTag");
                rule.remove("balancerTag");
                rule.insert("outboundTag".into(), json!(to));
            }
        }
    }
    Some(routing)
}

/// Keep broad prefix selectors from selecting helper outbounds injected after the raw config was read.
fn freeze_raw_proxy_balancer_selectors(routing: Option<Obj>, plan: &RawProxyBalancerPlan, has_observatory: bool) -> Option<Obj> {
    let mut routing = routing?;
    if let Some(Value::Array(balancers)) = routing.get_mut("balancers") {
        for balancer in balancers.iter_mut().filter_map(Value::as_object_mut) {
            let selected = str_of(balancer, "tag").and_then(|tag| plan.selection(tag)).cloned();
            if let Some(selected) = selected {
                let strategy = balancer
                    .get("strategy")
                    .and_then(Value::as_object)
                    .and_then(|strategy| strategy.get("type"))
                    .and_then(Value::as_str)
                    .unwrap_or("random")
                    .to_lowercase();
                if !has_observatory && matches!(strategy.as_str(), "random" | "roundrobin") {
                    // Xray makes fallbackTag on these strategies depend on an Observatory feature;
                    // without one the config aborts with "not all dependencies are resolved".
                    balancer.remove("fallbackTag");
                }
                balancer.insert("selector".into(), json!(selected));
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
