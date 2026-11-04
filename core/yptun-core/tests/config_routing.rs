//! Ported from XrayRoutingTest and SingBoxRoutingTest.

use serde_json::Value;
use yptun_core::routing::*;
use yptun_core::singbox::routing as sb;
use yptun_core::xray::routing as xr;

fn strs(v: &Value) -> Vec<String> {
    v.as_array().unwrap().iter().map(|x| x.as_str().map(str::to_string).unwrap_or_else(|| x.to_string())).collect()
}
fn sv(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

// ---------------- Xray ----------------

#[test]
fn xray_buckets_become_split_rules_in_route_order() {
    let p = RoutingProfile {
        block_sites: sv(&["geosite:category-ads-all"]),
        direct_sites: sv(&["geosite:ru", "domain:vk.com"]),
        direct_ip: sv(&["geoip:ru", "10.0.0.0/8"]),
        ..Default::default()
    };
    let routing = xr::routing_object(&p);
    assert_eq!(routing["domainStrategy"], "IPIfNonMatch");
    let rules = routing["rules"].as_array().unwrap();
    assert_eq!(rules.len(), 3);
    assert_eq!((rules[0]["outboundTag"].as_str(), rules[0]["domain"][0].as_str()), (Some("block"), Some("geosite:category-ads-all")));
    assert_eq!(strs(&rules[1]["domain"]), ["geosite:ru", "domain:vk.com"]);
    assert!(rules[1].get("ip").is_none());
    assert_eq!(strs(&rules[2]["ip"]), ["geoip:ru", "10.0.0.0/8"]);
    assert!(rules[2].get("domain").is_none());
}

#[test]
fn xray_non_global_adds_direct_fallthrough_and_strategies() {
    let p = RoutingProfile { proxy_sites: sv(&["domain:openai.com"]), global_proxy: false, route_order: "proxy".into(), ..Default::default() };
    let rules = xr::rules(&p);
    assert_eq!(rules.len(), 2);
    assert_eq!((rules[0]["outboundTag"].as_str(), rules[1]["outboundTag"].as_str()), (Some("proxy"), Some("direct")));
    assert_eq!(xr::domain_strategy(&RoutingProfile { domain_strategy: "weird".into(), ..Default::default() }), "AsIs");
    assert_eq!(xr::domain_strategy(&RoutingProfile { domain_strategy: "ipv4_only".into(), ..Default::default() }), "IPIfNonMatch");
    let expert = RoutingProfile { expert_enabled: true, xray_domain_strategy: "IPOnDemand".into(), ..Default::default() };
    assert_eq!(xr::domain_strategy(&expert), "IPOnDemand");
}

#[test]
fn xray_geosite_telegram_brings_geoip_telegram() {
    let p = RoutingProfile { proxy_sites: sv(&["geosite:telegram"]), global_proxy: false, ..Default::default() };
    let rules = xr::rules(&p);
    let ip = rules.iter().find(|r| r.get("ip").is_some()).unwrap();
    assert_eq!(strs(&ip["ip"]), ["geoip:telegram"]);
    let both = RoutingProfile { proxy_sites: sv(&["geosite:telegram"]), proxy_ip: sv(&["geoip:telegram", "asn:62041"]), ..Default::default() };
    let rules = xr::rules(&both);
    assert_eq!(strs(&rules.iter().find(|r| r.get("ip").is_some()).unwrap()["ip"]), ["geoip:telegram"]);
}

// ---------------- sing-box ----------------

#[test]
fn singbox_buckets_map_to_single_matcher_rules() {
    let p = RoutingProfile {
        block_sites: sv(&["geosite:category-ads-all"]),
        direct_sites: sv(&["geosite:ru", "domain:vk.com", "full:exact.example"]),
        direct_ip: sv(&["geoip:ru", "10.0.0.0/8"]),
        ..Default::default()
    };
    let rules = sb::rules(&p, false, sb::DIRECT_TAG);
    let block = rules.iter().find(|r| r["action"] == "reject").unwrap();
    assert_eq!(block["rule_set"][0], "geosite-category-ads-all");
    let directs: Vec<&Value> = rules.iter().filter(|r| r["outbound"] == "direct").collect();
    let with = |k: &str| directs.iter().find(|r| r.get(k).is_some()).unwrap();
    assert_eq!(strs(&with("rule_set")["rule_set"]), ["geosite-ru", "geoip-ru"]);
    assert_eq!(strs(&with("domain_suffix")["domain_suffix"]), [".vk.com"]);
    assert_eq!(strs(&with("domain")["domain"]), ["vk.com", "exact.example"]);
    assert_eq!(strs(&with("ip_cidr")["ip_cidr"]), ["10.0.0.0/8"]);
}

#[test]
fn singbox_hide_direct_ipv6_and_custom_direct_tag() {
    let p = RoutingProfile { direct_sites: sv(&["domain:ru", "1.2.3.4"]), route_order: "direct".into(), ..Default::default() };
    let rules = sb::rules(&p, true, "vkturn-base");
    assert_eq!(rules[0]["ip_version"], 6);
    assert_eq!(rules[0]["action"], "reject");
    assert!(rules.iter().filter(|r| r.get("ip_version").is_some()).all(|r| r.get("ip_cidr").is_none()));
    assert!(rules.iter().any(|r| r["outbound"] == "vkturn-base" && r.get("ip_cidr").is_some()));
}

#[test]
fn singbox_bare_ip_becomes_cidr_and_empty_profile() {
    let p = RoutingProfile { direct_ip: sv(&["1.2.3.4", "2606:4700::1111"]), route_order: "direct".into(), ..Default::default() };
    let rules = sb::rules(&p, false, "direct");
    assert_eq!(strs(&rules[0]["ip_cidr"]), ["1.2.3.4/32", "2606:4700::1111/128"]);
    assert!(sb::rules(&RoutingProfile::default(), false, "direct").is_empty());
    assert_eq!(sb::final_outbound(&RoutingProfile::default(), "direct"), "proxy");
    assert_eq!(sb::final_outbound(&RoutingProfile { global_proxy: false, ..Default::default() }, "direct"), "direct");
}

#[test]
fn singbox_rule_sets() {
    let p = RoutingProfile { direct_sites: sv(&["geosite:ru"]), block_ip: sv(&["geoip:cn"]), ..Default::default() };
    let sets = sb::rule_sets(&p, sb::DEFAULT_GEOSITE_BASE, sb::DEFAULT_GEOIP_BASE, sb::RULE_SET_DOWNLOAD_TAG);
    assert_eq!(sets.len(), 2);
    assert_eq!((sets[0]["tag"].as_str(), sets[0]["type"].as_str(), sets[0]["download_detour"].as_str()), (Some("geosite-ru"), Some("remote"), Some("proxy")));
    assert!(sets[0]["url"].as_str().unwrap().ends_with("geosite-ru.srs"));
    assert!(sets[1]["url"].as_str().unwrap().ends_with("geoip-cn.srs"));

    let tg = RoutingProfile { proxy_ip: sv(&["geoip:telegram", "geoip:ru"]), ..Default::default() };
    let urls: Vec<String> = sb::rule_sets(&tg, "", "", "proxy").iter().map(|s| s["url"].as_str().unwrap().to_string()).collect();
    assert!(urls.iter().any(|u| u.ends_with("MetaCubeX/meta-rules-dat/sing/geo/geoip/telegram.srs")));
    assert!(urls.iter().any(|u| u.ends_with("sing-geoip/rule-set/geoip-ru.srs")));

    let custom = sb::rule_sets(&RoutingProfile { direct_sites: sv(&["geosite:ru"]), ..Default::default() }, "https://example.com/rs", "", "proxy");
    assert_eq!(custom[0]["url"], "https://example.com/rs/geosite-ru.srs");
    let none = RoutingProfile { direct_sites: sv(&["domain:vk.com"]), direct_ip: sv(&["10.0.0.0/8"]), ..Default::default() };
    assert!(sb::rule_sets(&none, "", "", "proxy").is_empty());
}

#[test]
fn singbox_manual_rule_combines_fields() {
    let rule = SingBoxRule {
        outbound: OUT_DIRECT.into(),
        domains: sv(&["geosite:ru", "domain:vk.com", "full:exact.example"]),
        ip: sv(&["geoip:ru", "10.0.0.0/8"]),
        port: "443, 8000:9000".into(),
        network: "tcp".into(),
        protocol: sv(&["tls", "quic"]),
        ..Default::default()
    };
    let out = sb::manual_rules(&[rule], false, "direct");
    assert_eq!(out.len(), 1);
    let r = &out[0];
    assert_eq!(r["outbound"], "direct");
    assert_eq!(strs(&r["rule_set"]), ["geosite-ru", "geoip-ru"]);
    assert_eq!(strs(&r["domain_suffix"]), [".vk.com"]);
    assert_eq!(strs(&r["domain"]), ["vk.com", "exact.example"]);
    assert_eq!(strs(&r["ip_cidr"]), ["10.0.0.0/8"]);
    assert_eq!(r["port"], serde_json::json!([443]));
    assert_eq!(r["port_range"][0], "8000:9000");
    assert_eq!(r["network"], "tcp");
}

#[test]
fn singbox_manual_actions_and_fields() {
    let block = sb::manual_rules(&[SingBoxRule { outbound: OUT_BLOCK.into(), domains: sv(&["domain:ads.example"]), ..Default::default() }], false, "direct");
    assert_eq!(block[0]["action"], "reject");
    assert!(block[0].get("outbound").is_none());
    let skipped = [SingBoxRule { domains: sv(&["domain:a.com"]), enabled: false, ..Default::default() }, SingBoxRule::default()];
    assert!(sb::manual_rules(&skipped, false, "direct").is_empty());
    assert!(sb::manual_rule_sets(&skipped, "", "", "proxy").is_empty());

    let r = &sb::manual_rules(&[SingBoxRule {
        name: "label".into(),
        source: sv(&["192.168.0.0/16"]),
        source_port: "1000:2000,53".into(),
        network_type: sv(&["wifi", "cellular"]),
        client: sv(&["chromium"]),
        network_is_expensive: true,
        clash_mode: "Global".into(),
        package_names: sv(&["com.x.y"]),
        ..Default::default()
    }], false, "direct")[0];
    assert!(r.get("name").is_none());
    assert_eq!(r["source_ip_cidr"][0], "192.168.0.0/16");
    assert_eq!(r["source_port_range"][0], "1000:2000");
    assert_eq!(r["source_port"], serde_json::json!([53]));
    assert_eq!(strs(&r["network_type"]), ["wifi", "cellular"]);
    assert_eq!((r["client"][0].as_str(), r["network_is_expensive"].as_bool(), r["clash_mode"].as_str()), (Some("chromium"), Some(true), Some("Global")));
    assert_eq!(r["package_name"][0], "com.x.y");
    let desktop = sb::manual_rules(&[SingBoxRule { package_names: sv(&["app.exe"]), ..Default::default() }], true, "direct");
    assert_eq!(desktop[0]["process_name"][0], "app.exe");

    for token in ["route-options", "sniff", "resolve", "hijack-dns", "reject"] {
        let r = &sb::manual_rules(&[SingBoxRule { action: token.into(), domains: sv(&["domain:a.com"]), ..Default::default() }], false, "direct")[0];
        assert_eq!(r["action"], token);
        assert!(r.get("outbound").is_none());
    }
    let route = &sb::manual_rules(&[SingBoxRule { domains: sv(&["domain:a.com"]), ..Default::default() }], false, "direct")[0];
    assert_eq!(route["outbound"], "proxy");
    assert!(route.get("action").is_none());

    let sets = sb::manual_rule_sets(&[SingBoxRule { domains: sv(&["geosite:cn"]), ip: sv(&["geoip:cn"]), ..Default::default() }], "", "", "proxy");
    assert_eq!(sets.iter().map(|s| s["tag"].as_str().unwrap()).collect::<Vec<_>>(), ["geosite-cn", "geoip-cn"]);
}

#[test]
fn singbox_geosite_telegram_brings_geoip_telegram() {
    let p = RoutingProfile { proxy_sites: sv(&["geosite:google", "geosite:telegram"]), global_proxy: false, ..Default::default() };
    let tags: Vec<String> = sb::rule_sets(&p, "", "", "proxy").iter().map(|s| s["tag"].as_str().unwrap().to_string()).collect();
    assert!(tags.contains(&"geoip-telegram".into()) && tags.contains(&"geosite-telegram".into()));
    let rule = sb::rules(&p, false, "direct").into_iter().find(|r| r.get("rule_set").is_some()).unwrap();
    let used = strs(&rule["rule_set"]);
    assert!(used.contains(&"geoip-telegram".into()) && used.contains(&"geosite-google".into()));
    let google = sb::rule_sets(&RoutingProfile { proxy_sites: sv(&["geosite:google"]), ..Default::default() }, "", "", "proxy");
    assert!(google.iter().all(|s| !s["tag"].as_str().unwrap().starts_with("geoip-")));
}
