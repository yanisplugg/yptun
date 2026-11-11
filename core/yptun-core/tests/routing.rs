//! Ported from HappRoutingParserTest, RoutingProfileLinkTest and AsnTest.

use std::collections::BTreeMap;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use yptun_core::routing::*;

fn b64(s: &str) -> String {
    URL_SAFE_NO_PAD.encode(s)
}

#[test]
fn parses_happ_routing_json() {
    let body = r#"{
      "name": "RuNet",
      "blocksites": ["geosite:category-ads-all"],
      "directip": ["geoip:ru", "10.0.0.0/8"],
      "directsites": ["geosite:ru", "domain:vk.com", "domain:yandex.ru"],
      "dnshosts": {"cloudflare-dns.com": "1.1.1.1"},
      "domainstrategy": "IPIfNonMatch",
      "geoipurl": "https://example.com/geoip.dat",
      "geositeurl": "https://example.com/geosite.dat",
      "globalproxy": true,
      "routeorder": "block-direct-proxy"
    }"#;
    let p = parse_routing_link(&format!("happ://routing/add/{}", b64(body))).unwrap();
    assert_eq!(p.name, "RuNet");
    assert_eq!(p.block_sites, ["geosite:category-ads-all"]);
    assert_eq!(p.direct_ip, ["geoip:ru", "10.0.0.0/8"]);
    assert!(p.direct_sites.contains(&"domain:vk.com".to_string()));
    assert_eq!(p.dns_hosts["cloudflare-dns.com"], "1.1.1.1");
    assert_eq!(p.geoip_url, "https://example.com/geoip.dat");
    assert!(p.global_proxy && p.needs_geo_files());
}

#[test]
fn happ_pascal_case_and_string_booleans() {
    let body = r#"{"Name":"Happ","GlobalProxy":"false","DirectSites":["geosite:ru"],"RouteOrder":"direct-block-proxy"}"#;
    let p = parse_routing_profile(&format!("happ://routing/add/{}", b64(body))).unwrap();
    assert_eq!((p.name.as_str(), p.global_proxy), ("Happ", false));
    assert_eq!(p.ordered_buckets(), ["direct", "block", "proxy"]);
    assert!(is_routing_json(body));
}

#[test]
fn ignores_unknown_keys_and_defaults() {
    let p = parse_routing_link(&format!("happ://routing/add/{}", b64(r#"{"name":"Minimal","somethingNew":42}"#))).unwrap();
    assert_eq!(p.name, "Minimal");
    assert!(p.direct_sites.is_empty() && !p.needs_geo_files());
    assert!(p.global_proxy && p.xray_sniffing && p.singbox_sniff);
}

#[test]
fn parses_routing_scheme() {
    let payload = b64(r#"{"name":"RoutingScheme","directsites":["domain:ru"]}"#);
    for link in [format!("routing://routing/add/{payload}"), format!("routing://add/{payload}"), format!("routing://{payload}")] {
        assert!(is_routing_link(&link), "{link}");
        let p = parse_routing_profile(&link).unwrap();
        assert_eq!(p.name, "RoutingScheme");
        assert_eq!(p.direct_sites, ["domain:ru"]);
    }
}

#[test]
fn rejects_non_happ_links() {
    assert!(parse_routing_link("https://example.com").is_none());
    assert!(parse_routing_link("vless://uuid@host:443").is_none());
    assert!(parse_routing_link("happ://routing/add/").is_none());
    assert!(!is_routing_link("happ://something/else"));
    assert!(is_routing_link("happ://routing/add/abc"));
    assert!(!is_routing_json(r#"{"outbounds":[]}"#));
}

#[test]
fn happ_link_round_trips() {
    let original = RoutingProfile {
        name: "RuNet".into(),
        direct_sites: vec!["geosite:ru".into(), "domain:vk.com".into()],
        direct_ip: vec!["geoip:ru".into(), "10.0.0.0/8".into()],
        block_sites: vec!["geosite:category-ads-all".into()],
        global_proxy: false,
        geoip_url: "https://example.com/geoip.dat".into(),
        id: "local-only".into(),
        ..Default::default()
    };
    let link = original.to_happ_link();
    assert!(is_routing_link(&link));
    let json = String::from_utf8(URL_SAFE_NO_PAD.decode(&link[HAPP_SCHEME.len()..]).unwrap()).unwrap();
    assert!(!json.contains("_id") && !json.contains("domainstrategy"), "{json}");
    assert_eq!(parse_routing_link(&link).unwrap(), RoutingProfile { id: String::new(), ..original });
}

#[test]
fn needs_geo_files_detects_geo_selectors() {
    assert!(RoutingProfile { direct_sites: vec!["geosite:ru".into()], ..Default::default() }.needs_geo_files());
    assert!(RoutingProfile { block_ip: vec!["GEOIP:cn".into()], ..Default::default() }.needs_geo_files());
    assert!(!RoutingProfile { direct_sites: vec!["domain:vk.com".into()], ..Default::default() }.needs_geo_files());
    let stripped = RoutingProfile { direct_sites: vec!["geosite:ru".into(), "domain:ru".into()], ..Default::default() }.without_geo_selectors();
    assert_eq!(stripped.direct_sites, ["domain:ru"]);
}

#[test]
fn resolve_honours_override_and_global() {
    let a = RoutingProfile { name: "A".into(), id: "a".into(), ..Default::default() };
    let b = RoutingProfile { name: "B".into(), id: "b".into(), ..Default::default() };
    let state = RoutingProfilesState { profiles: vec![a, b], global_profile_id: "a".into(), ..Default::default() };
    assert_eq!(state.resolve(Some("")).unwrap().id, "a");
    assert_eq!(state.resolve(None).unwrap().id, "a");
    assert_eq!(state.resolve(Some("b")).unwrap().id, "b");
    assert!(state.resolve(Some(NONE_ID)).is_none());

    let link = state.to_routing_link();
    assert!(link.starts_with(ROUTING_LINK_PREFIX));
    assert_eq!(RoutingProfilesState::from_routing_link(&link), Some(state));
}

#[test]
fn routing_state_reads_kotlin_camel_case() {
    let json = r#"{"profiles":[{"name":"K","_id":"k"}],"globalProfileId":"k","geoLastUpdated":5}"#;
    let s = RoutingProfilesState::from_routing_link(&format!("yptun://routing/{}", b64(json))).unwrap();
    assert_eq!((s.global_profile_id.as_str(), s.geo_last_updated, s.geoip_url.as_str()), ("k", 5, DEFAULT_GEOIP_URL));
    assert_eq!(s.resolve(None).unwrap().name, "K");
}

// ---------------- ASN ----------------

#[test]
fn asn_normalizes_every_spelling() {
    for s in ["asn:13335", "AS13335", "as13335", "asn:AS13335", "  13335 "] {
        assert_eq!(asn::normalize(s).as_deref(), Some("13335"), "{s}");
    }
    assert!(asn::normalize("telegram").is_none() && asn::normalize("asn:").is_none());
    assert!(asn::is_selector("ASN:62041") && !asn::is_selector("geoip:ru") && !asn::is_selector("10.0.0.0/8"));
}

#[test]
fn asn_collect_and_expand() {
    let sel: Vec<String> = ["asn:62041", "geoip:ru", "asn:AS13335", "asn:62041", "1.2.3.4/32"].map(String::from).to_vec();
    assert_eq!(asn::collect(&sel), ["62041", "13335"]);
    let map = BTreeMap::from([("62041".to_string(), vec!["91.108.4.0/22".to_string(), "149.154.160.0/20".to_string()])]);
    let got = asn::expand(&["geoip:ru", "asn:62041", "10.0.0.0/8"].map(String::from), &map);
    assert_eq!(got, ["geoip:ru", "91.108.4.0/22", "149.154.160.0/20", "10.0.0.0/8"]);
    assert_eq!(asn::expand(&["asn:1", "1.2.3.4/32"].map(String::from), &BTreeMap::new()), ["1.2.3.4/32"]);
}

#[test]
fn profile_and_rules_expand_asn() {
    let map = BTreeMap::from([("13335".to_string(), vec!["104.16.0.0/13".to_string()])]);
    let profile = RoutingProfile { direct_ip: vec!["asn:13335".into(), "geoip:ru".into()], proxy_sites: vec!["domain:youtube.com".into()], ..Default::default() };
    assert_eq!(profile.referenced_asns(), ["13335"]);
    let e = profile.expand_asn(&map);
    assert_eq!(e.direct_ip, ["104.16.0.0/13", "geoip:ru"]);
    assert_eq!(e.proxy_sites, ["domain:youtube.com"]);

    let rules = vec![
        SingBoxRule { name: "tg".into(), ip: vec!["asn:13335".into()], ..Default::default() },
        SingBoxRule { name: "plain".into(), ip: vec!["8.8.8.8".into()], ..Default::default() },
    ];
    assert_eq!(SingBoxRule::collect_asns(&rules), ["13335"]);
    let x = SingBoxRule::expand_asn(&rules, &map);
    assert_eq!((x[0].ip.clone(), x[1].ip.clone()), (vec!["104.16.0.0/13".to_string()], vec!["8.8.8.8".to_string()]));
}

#[test]
fn package_regex_and_settings_helpers() {
    let rules = vec![SingBoxRule { package_regex: vec!["^org\\.telegram".into(), "(".into()], package_names: vec!["a.b".into()], ..Default::default() }];
    let x = SingBoxRule::expand_package_regex(&rules, &["org.telegram.messenger".into(), "com.vk".into()]);
    assert_eq!(x[0].package_names, ["a.b", "org.telegram.messenger"]);
    assert!(!SingBoxRule::default().has_matcher());
    assert_eq!(SingBoxRule::text_to_list("a, b;\nc"), ["a", "b", "c"]);
    assert_eq!(RoutingRules::parse_domains("https://vk.com/ ya.ru;x.org"), ["vk.com", "ya.ru", "x.org"]);
    assert!(RoutingRules::is_valid_rules_json(" ") && RoutingRules::is_valid_rules_json("[]") && !RoutingRules::is_valid_rules_json("{}"));
    let rules: RoutingRules = serde_json::from_str(r#"{"blockAds":true,"rules":[{"domains":["x"]}]}"#).unwrap();
    assert!(rules.bypass_lan && rules.block_ads && rules.rules[0].enabled);

    let t = TrafficSettings { direct_dns: "223.5.5.5".into(), mtu: 100, mux_max_connections: 999, domain_strategy: "weird".into(), fake_dns_enabled: true, ..Default::default() }.normalized();
    assert_eq!((t.direct_dns.as_str(), t.mtu, t.mux_max_connections, t.domain_strategy.as_str(), t.fake_dns_enabled), ("77.88.8.8", 1280, 64, "ipv4_only", false));
    assert_eq!(TrafficSettings { domain_strategy: "prefer_ipv4".into(), ..Default::default() }.xray_query_strategy(), "UseIPv4");
    assert_eq!(TrafficSettings { domain_strategy: "prefer_ipv6".into(), ..Default::default() }.xray_query_strategy(), "UseIP");
}
