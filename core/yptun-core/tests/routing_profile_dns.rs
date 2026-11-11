//! Ported from RoutingProfileDnsTest: a routing profile's own DNS (the China preset) overrides the
//! traffic settings; other profiles don't.

use std::collections::BTreeMap;

use serde_json::Value;
use yptun_core::model::*;
use yptun_core::routing::*;
use yptun_core::singbox::config::SingBoxOptions;
use yptun_core::xray::config::XrayOptions;

fn proxy() -> ProxyProfile {
    ProxyProfile {
        kind: TYPE_VLESS.into(),
        server: "vbn.azz.su".into(),
        server_port: 443,
        uuid: "11111111-1111-1111-1111-111111111111".into(),
        network: NETWORK_TCP.into(),
        security: SECURITY_TLS.into(),
        sni: "vbn.azz.su".into(),
        ..Default::default()
    }
}

fn sing_box_servers(routing: Option<RoutingProfile>) -> BTreeMap<String, String> {
    let o = SingBoxOptions {
        listen_port: 10808,
        traffic: TrafficSettings::default().normalized(),
        routing_profile: routing,
        ..Default::default()
    };
    let root: Value =
        serde_json::from_str(&yptun_core::singbox::config::build(&proxy(), &o).unwrap()).unwrap();
    root["dns"]["servers"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|s| {
            Some((
                s["tag"].as_str()?.to_string(),
                s["server"].as_str()?.to_string(),
            ))
        })
        .collect()
}

#[test]
fn china_preset_sets_remote_via_vpn_and_chinese_direct() {
    let servers = sing_box_servers(Some(RoutingProfile::china_direct()));
    assert_eq!(servers["remote"], "1.1.1.1");
    assert_eq!(servers["direct"], "223.5.5.5");
}

#[test]
fn no_profile_or_plain_profile_keeps_the_app_settings() {
    let defaults = TrafficSettings::default().normalized();
    for routing in [None, Some(RoutingProfile::russia_direct())] {
        let servers = sing_box_servers(routing);
        assert_eq!(servers["remote"], defaults.remote_dns);
        assert_eq!(servers["direct"], defaults.direct_dns);
    }
}

#[test]
fn xray_reads_the_same_fields() {
    let o = XrayOptions {
        listen_port: 10808,
        traffic: TrafficSettings::default().normalized(),
        routing_profile: Some(RoutingProfile::china_direct()),
        ..Default::default()
    };
    let root: Value = serde_json::from_str(&yptun_core::xray::config::build(&proxy(), &o)).unwrap();
    let servers: Vec<&str> = root["dns"]["servers"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s.as_str().unwrap())
        .collect();
    assert_eq!(servers, ["https://1.1.1.1/dns-query", "223.5.5.5"]);
}

#[test]
fn happ_style_doh_and_editor_round_trip() {
    // Happ carries a DoH resolver as type=DoH + a domain; a bare host gets the standard path.
    let doh = RoutingProfile {
        remote_dns_type: "DoH".into(),
        remote_dns_domain: "dns.google".into(),
        ..Default::default()
    };
    assert_eq!(
        doh.remote_dns_address().as_deref(),
        Some("https://dns.google/dns-query")
    );
    assert_eq!(RoutingProfile::default().remote_dns_address(), None);
    // The editor writes a single address back; an unchanged one must not rewrite the stored fields.
    assert_eq!(doh.with_remote_dns("https://dns.google/dns-query"), doh);
    assert_eq!(
        doh.with_remote_dns("9.9.9.9")
            .remote_dns_address()
            .as_deref(),
        Some("9.9.9.9")
    );
    assert_eq!(doh.with_remote_dns("").remote_dns_address(), None);
}

#[test]
fn china_preset_shape() {
    let cn = RoutingProfile::china_direct_named("Китай напрямую");
    assert_eq!(cn.name, "Китай напрямую");
    assert_eq!(cn.id, PRESET_CN_DIRECT_ID);
    assert_eq!(
        RoutingProfile::china_direct().name,
        CHINA_DIRECT_DEFAULT_NAME
    );
    assert!(cn.needs_geo_files());
    // Without the geo files the plain domains and resolver CIDRs still route direct.
    let fallback = cn.without_geo_selectors();
    assert!(fallback
        .direct_sites
        .iter()
        .all(|s| s.starts_with("domain:")));
    assert_eq!(
        fallback.direct_ip,
        [
            "223.5.5.5/32",
            "223.6.6.6/32",
            "119.29.29.29/32",
            "114.114.114.114/32"
        ]
    );
    assert_eq!(cn.domestic_dns_address().as_deref(), Some("223.5.5.5"));
    assert_eq!(
        cn.with_domestic_dns("https://dns.alidns.com/dns-query")
            .domestic_dns_type,
        "DoH"
    );
    // Survives the happ:// round trip with its DNS fields.
    let back = parse_routing_link(&cn.to_happ_link()).unwrap();
    assert_eq!(back.remote_dns_address().as_deref(), Some("1.1.1.1"));
    assert_eq!(back.domestic_dns_address().as_deref(), Some("223.5.5.5"));
    let traffic = TrafficSettings::default().with_profile_dns(Some(&cn));
    assert_eq!(
        (traffic.remote_dns.as_str(), traffic.direct_dns.as_str()),
        ("1.1.1.1", "223.5.5.5")
    );
    assert_eq!(
        TrafficSettings::default().with_profile_dns(None),
        TrafficSettings::default()
    );
}
