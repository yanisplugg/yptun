//! Ported from SingBoxDnsFormatTest, SingBoxRuleSetDetourTest, SingBoxWireguardLoopbackBindTest,
//! SingBoxFakeIpCacheTest, SingBoxConfigDesktopTest and the build half of EmbeddedSingBoxRouteTest.

use serde_json::Value;
use yptun_core::model::*;
use yptun_core::routing::*;
use yptun_core::singbox::config::*;
use yptun_core::transports::{AdvancedCoreConfig, FakeDnsSpec};

fn vless() -> ProxyProfile {
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

fn build(p: &ProxyProfile, o: SingBoxOptions) -> Value {
    serde_json::from_str(&yptun_core::singbox::config::build(p, &o).unwrap()).unwrap()
}

fn arr<'a>(v: &'a Value, path: &[&str]) -> Vec<&'a Value> {
    let mut cur = v;
    for k in path {
        cur = &cur[*k];
    }
    cur.as_array().map(|a| a.iter().collect()).unwrap_or_default()
}

fn wg_raw(server: &str) -> String {
    format!(r#"{{"type":"wireguard","server":"{server}","server_port":9000,"local_address":["10.0.0.2/32"],"private_key":"aGVsbG8=","peer_public_key":"d29ybGQ=","mtu":1200}}"#)
}

#[test]
fn every_dns_server_is_typed() {
    let traffic = TrafficSettings { remote_dns: "https://dns.google/dns-query".into(), remote_dns2: "tls://1.1.1.1".into(), direct_dns: "8.8.8.8:5353".into(), ..Default::default() };
    let root = build(&vless(), SingBoxOptions { traffic, ..Default::default() });
    let servers = arr(&root, &["dns", "servers"]);
    assert!(servers.iter().all(|s| s.get("address").is_none() && s.get("type").is_some()));
    let by = |t: &str| *servers.iter().find(|s| s["tag"] == t).unwrap();
    assert_eq!((by("remote")["type"].as_str(), by("remote")["server"].as_str()), (Some("https"), Some("dns.google")));
    assert!(by("remote").get("path").is_none());
    assert_eq!(by("remote2")["type"], "tls");
    assert_eq!((by("direct")["type"].as_str(), by("direct")["server"].as_str(), by("direct")["server_port"].as_i64()), (Some("udp"), Some("8.8.8.8"), Some(5353)));
}

#[test]
fn direct_outbound_never_carries_a_detour() {
    let root = build(&vless(), SingBoxOptions { olcrtc_chain_port: Some(10811), direct_via_base: true, ..Default::default() });
    let direct = arr(&root, &["outbounds"]).into_iter().find(|o| o["tag"] == "direct").unwrap();
    assert!(direct.get("detour").is_none());
    let private = arr(&root, &["route", "rules"]).into_iter().find(|r| r.get("ip_is_private").is_some()).unwrap();
    assert_eq!(private["outbound"], "olcrtc-out");
    for root in [build(&vless(), SingBoxOptions::default()), root.clone()] {
        assert!(arr(&root, &["dns", "servers"]).iter().all(|s| s["detour"] != "direct"));
    }
    let chained = arr(&root, &["dns", "servers"]).into_iter().find(|s| s["tag"] == "direct").unwrap();
    assert_eq!(chained["detour"], "olcrtc-out");
}

#[test]
fn rule_sets_download_through_the_proxy_unless_wireguard() {
    let profile = RoutingProfile { id: "ru".into(), direct_sites: vec!["geosite:ru".into()], direct_ip: vec!["geoip:ru".into()], ..Default::default() };
    let root = build(&vless(), SingBoxOptions { routing_profile: Some(profile), ..Default::default() });
    let sets = arr(&root, &["route", "rule_set"]);
    assert!(!sets.is_empty() && sets.iter().all(|s| s["download_detour"] == "proxy"));

    let root = build(&vless(), SingBoxOptions { routing: RoutingRules { bypass_russia: true, block_ads: true, ..Default::default() }, ..Default::default() });
    let sets = arr(&root, &["route", "rule_set"]);
    assert_eq!(sets.len(), 3);
    assert!(sets.iter().all(|s| s["download_detour"] == "proxy"));

    let wg = ProxyProfile { kind: "wireguard".into(), server: "10.0.0.1".into(), server_port: 51820, raw_outbound: Some(wg_raw("10.0.0.1")), ..Default::default() };
    let tg = RoutingProfile { id: "tg".into(), proxy_sites: vec!["geosite:telegram".into()], proxy_ip: vec!["geoip:telegram".into()], ..Default::default() };
    let root = build(&vless(), SingBoxOptions { wireguard_base: Some(wg), routing_profile: Some(tg), ..Default::default() });
    let sets = arr(&root, &["route", "rule_set"]);
    assert_eq!(sets.len(), 2);
    assert!(sets.iter().all(|s| s["download_detour"] == "direct"));
    let proxy = arr(&root, &["outbounds"]).into_iter().find(|o| o["tag"] == "proxy").unwrap();
    assert_eq!(proxy["detour"], "wireguard-base");
    assert_eq!(arr(&root, &["endpoints"])[0]["tag"], "wireguard-base");
}

#[test]
fn explicit_routes_precede_russia_and_lan_bypasses() {
    let profile = RoutingProfile {
        proxy_sites: vec!["domain:myserver.lan".into()],
        route_order: "proxy".into(),
        ..Default::default()
    };
    let routing = RoutingRules { bypass_lan: true, bypass_russia: true, ..Default::default() };
    let root = build(&vless(), SingBoxOptions { routing, routing_profile: Some(profile), ..Default::default() });
    let rules = arr(&root, &["route", "rules"]);
    let explicit = rules.iter().position(|r| r["domain_suffix"].as_array().is_some_and(|v| v.iter().any(|x| x == ".myserver.lan"))).unwrap();
    let russia = rules.iter().position(|r| r["rule_set"].as_array().is_some_and(|v| v.iter().any(|x| x == "geosite-ru") && v.iter().any(|x| x == "geoip-ru"))).unwrap();
    let private = rules.iter().position(|r| r.get("ip_is_private").is_some()).unwrap();
    assert_eq!(rules[explicit]["outbound"], "proxy");
    assert!(explicit < russia && russia < private);
}

#[test]
fn wireguard_loopback_bind() {
    let endpoint = |server: &str, auto: bool| {
        let p = ProxyProfile { tag: "WDTT".into(), kind: "wireguard".into(), server: server.into(), server_port: 9000, raw_outbound: Some(wg_raw(server)), ..Default::default() };
        let root = build(&p, SingBoxOptions { auto_detect_interface: auto, ..Default::default() });
        let e = arr(&root, &["endpoints"]);
        assert_eq!(e.len(), 1);
        assert!(arr(&root, &["outbounds"]).iter().all(|o| o["tag"] != "proxy"), "the WG exit is the endpoint");
        e[0].clone()
    };
    assert_eq!(endpoint("127.0.0.1", true)["inet4_bind_address"], "0.0.0.0");
    assert!(endpoint("203.0.113.7", true).get("inet4_bind_address").is_none());
    let android = endpoint("127.0.0.1", false);
    assert!(android.get("inet4_bind_address").is_none());
    assert_eq!((android["tag"].as_str(), android["mtu"].as_i64(), android["peers"][0]["port"].as_i64()), (Some("proxy"), Some(1200), Some(9000)));
}

#[test]
fn fake_ip_cache_file() {
    let root = build(&vless(), SingBoxOptions { fake_dns_spec: Some(FakeDnsSpec::default()), cache_file_path: Some("/data/singbox/cache.db".into()), ..Default::default() });
    let cache = &root["experimental"]["cache_file"];
    assert_eq!((cache["enabled"].as_bool(), cache["store_fakeip"].as_bool(), cache["path"].as_str()), (Some(true), Some(true), Some("/data/singbox/cache.db")));
    let fake = arr(&root, &["dns", "servers"]).into_iter().find(|s| s["type"] == "fakeip").unwrap();
    assert_eq!(fake["inet4_range"], "198.18.0.0/15");
    assert!(fake.get("detour").is_none());
    // The synthetic pool reaches the proxy BEFORE the private/LAN rule.
    let rules = arr(&root, &["route", "rules"]);
    let fake_rule = rules.iter().position(|r| r["ip_cidr"][0] == "198.18.0.0/15").unwrap();
    let private = rules.iter().position(|r| r.get("ip_is_private").is_some()).unwrap();
    assert!(fake_rule < private);

    let no_fake = build(&vless(), SingBoxOptions { cache_file_path: Some("/c.db".into()), ..Default::default() });
    assert!(no_fake["experimental"]["cache_file"].get("store_fakeip").is_none());
    assert!(build(&vless(), SingBoxOptions { fake_dns_spec: Some(FakeDnsSpec::default()), ..Default::default() }).get("experimental").is_none());
}

#[test]
fn desktop_tun_mixed_inbound_and_xudp() {
    let p = ProxyProfile { flow: "xtls-rprx-vision".into(), ..vless() };
    let root = build(&p, SingBoxOptions { tun_mode: true, mixed_inbound: true, tun_exclude_addresses: vec!["1.2.3.4".into(), "::1".into(), "1.2.3.4".into()], ..Default::default() });
    let tun = arr(&root, &["inbounds"]).into_iter().find(|i| i["type"] == "tun").unwrap();
    assert_eq!((tun["stack"].as_str(), tun["mtu"].as_i64(), tun["auto_route"].as_bool()), (Some("mixed"), Some(9000), Some(true)));
    assert_eq!(tun["route_exclude_address"], serde_json::json!(["1.2.3.4/32", "::1/128"]));
    let out = arr(&root, &["outbounds"]).into_iter().find(|o| o["type"] == "vless").unwrap();
    assert_eq!((out["flow"].as_str(), out["packet_encoding"].as_str()), (Some("xtls-rprx-vision"), Some("xudp")));
    assert!(arr(&root, &["route", "rules"]).iter().any(|r| r["action"] == "hijack-dns"));
    let socks_type = |mixed| {
        let r = build(&vless(), SingBoxOptions { mixed_inbound: mixed, ..Default::default() });
        let i = arr(&r, &["inbounds"]).into_iter().find(|i| i["tag"] == "socks-in").unwrap().clone();
        assert!(arr(&r, &["inbounds"]).iter().all(|i| i["type"] != "tun"));
        i["type"].as_str().unwrap().to_string()
    };
    assert_eq!(socks_type(true), "mixed");
    assert_eq!(socks_type(false), "socks");
}

#[test]
fn embedded_route_wins_over_the_app_profile() {
    let route = r#"{"final":"proxy","rules":[{"rule_set":"geosite-ru","outbound":"direct"},{"domain_suffix":["ads.example"],"action":"reject"},{"domain":["mail.ru"],"outbound":"proxy"}],
        "rule_set":[{"type":"remote","tag":"geosite-ru","format":"binary","url":"https://example.invalid/geosite-ru.srs","download_detour":"direct"}]}"#;
    let p = ProxyProfile { raw_singbox_route: Some(route.into()), ..vless() };
    let root = build(&p, SingBoxOptions {
        routing_profile: Some(RoutingProfile { id: "p".into(), direct_sites: vec!["domain:ru".into()], ..Default::default() }),
        routing: RoutingRules { bypass_russia: true, ..Default::default() },
        ..Default::default()
    });
    assert_eq!(root["route"]["final"], "proxy");
    let rules = arr(&root, &["route", "rules"]);
    assert!(rules.iter().any(|r| r["domain_suffix"][0] == "ads.example"));
    assert!(rules.iter().all(|r| !r.to_string().contains("geoip-ru")));
    assert!(rules.iter().any(|r| r["action"] == "resolve"), "the embedded rule_set rule needs a resolve");
    let sets = arr(&root, &["route", "rule_set"]);
    assert_eq!(sets.len(), 1);
    assert_eq!(sets[0]["url"], "https://example.invalid/geosite-ru.srs");
}

#[test]
fn ipv4_only_family_guards_and_quic() {
    let root = build(&vless(), SingBoxOptions::default());
    let rules = arr(&root, &["route", "rules"]);
    assert_eq!(rules[0]["action"], "sniff");
    assert!(rules.iter().any(|r| r["protocol"][0] == "quic"));
    assert!(rules.iter().any(|r| r["domain"][0] == "use-application-dns.net"));
    let resolve = rules.iter().position(|r| r["action"] == "resolve").unwrap();
    let drop = rules.iter().position(|r| r["method"] == "drop").unwrap();
    assert!(resolve < drop);
    assert_eq!(rules[drop]["ip_cidr"][0], "::/0");
    assert_eq!(root["dns"]["strategy"], "ipv4_only");
    assert_eq!(root["route"]["final"], "proxy");

    let udp = build(&vless(), SingBoxOptions { block_quic: false, force_family_resolve: false, ..Default::default() });
    let rules = arr(&udp, &["route", "rules"]);
    assert!(rules.iter().all(|r| r["protocol"] != serde_json::json!(["quic"]) && r.get("method").is_none() && r["action"] != "resolve"));

    let hybrid = build(&vless(), SingBoxOptions { traffic: TrafficSettings { domain_strategy: "prefer_ipv4".into(), ..Default::default() }, ..Default::default() });
    let direct = arr(&hybrid, &["outbounds"]).into_iter().find(|o| o["tag"] == "direct").unwrap();
    assert_eq!(direct["domain_resolver"]["strategy"], "ipv4_only");
}

#[test]
fn cascade_mux_and_xhttp() {
    let second = ProxyProfile { kind: TYPE_TROJAN.into(), server: "exit.example".into(), server_port: 443, password: "p".into(), security: SECURITY_TLS.into(), ..Default::default() };
    let advanced = AdvancedCoreConfig { mux_enabled: true, mux_protocol: "smux".into(), mux_max_streams: 4, tcp_fast_open: true, sniff: true, ..Default::default() };
    let root = build(&vless(), SingBoxOptions { second_profile: Some(second), advanced: Some(advanced), ..Default::default() });
    let outs = arr(&root, &["outbounds"]);
    assert_eq!((outs[0]["tag"].as_str(), outs[1]["tag"].as_str(), outs[1]["detour"].as_str()), (Some("proxy-base"), Some("proxy"), Some("proxy-base")));
    assert!(outs[0].get("multiplex").is_none());
    assert_eq!((outs[1]["multiplex"]["max_streams"].as_i64(), outs[1]["tcp_fast_open"].as_bool()), (Some(4), Some(true)));

    let xhttp = ProxyProfile { network: NETWORK_XHTTP.into(), ..vless() };
    assert_eq!(yptun_core::singbox::config::build(&xhttp, &SingBoxOptions::default()), Err(ConfigError::XhttpNeedsXray));

    let hy2 = ProxyProfile { kind: TYPE_HYSTERIA2.into(), server: "h".into(), server_port: 443, password: "pw".into(), hy2_ports: "443,2000-3000,bad,9-1".into(), hy2_obfs: "salamander".into(), hy2_obfs_password: "o".into(), security: SECURITY_TLS.into(), ..Default::default() };
    let out = arr(&build(&hy2, SingBoxOptions { traffic: TrafficSettings { mux_enabled: true, ..Default::default() }, ..Default::default() }), &["outbounds"])[0].clone();
    assert_eq!(out["server_ports"], serde_json::json!(["443:443", "2000:3000"]));
    assert_eq!(out["obfs"]["type"], "salamander");
    assert!(out.get("multiplex").is_none() && out.get("transport").is_none());
    assert_eq!(out["tls"]["server_name"], "h");
}
