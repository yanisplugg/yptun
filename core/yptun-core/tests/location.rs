//! Ported from commonTest (MasterDnsConfigTest, ProxyProfileSameNodeTest, SnolcNameTest) plus
//! checks that locations stored by the Kotlin app load and behave the same.

use yptun_core::location::*;
use yptun_core::model::*;
use yptun_core::transports::*;

// ---------------- MasterDnsConfigTest ----------------

fn mdns(domains: &str, key: &str, resolvers: &str) -> MasterDnsConfig {
    MasterDnsConfig { domains: domains.into(), encryption_key: key.into(), resolvers: resolvers.into(), ..Default::default() }
}

#[test]
fn masterdns_lists_split_on_commas_whitespace_and_newlines() {
    let c = mdns("v.example.com, v2.example.com\nv3.example.com", "secret", "1.1.1.1, 8.8.8.8:5300;9.9.9.9\n1.1.1.1");
    assert_eq!(c.domain_list(), vec!["v.example.com", "v2.example.com", "v3.example.com"]);
    assert_eq!(c.resolver_list(), vec!["1.1.1.1", "8.8.8.8:5300", "9.9.9.9"]);
}

#[test]
fn masterdns_resolver_hosts_drop_port_and_keep_ipv6() {
    let c = mdns("", "", "1.1.1.1, 8.8.8.8:5300, [2001:4860:4860::8888]:53");
    assert_eq!(c.resolver_hosts(), vec!["1.1.1.1", "8.8.8.8", "2001:4860:4860::8888"]);
}

#[test]
fn masterdns_completeness() {
    let full = mdns("v.example.com", "k", "1.1.1.1");
    assert!(full.is_complete());
    assert!(!MasterDnsConfig { domains: "  ".into(), ..full.clone() }.is_complete());
    assert!(!MasterDnsConfig { encryption_key: "".into(), ..full.clone() }.is_complete());
    assert!(!MasterDnsConfig { resolvers: " , ".into(), ..full.clone() }.is_complete());
}

#[test]
fn masterdns_normalized_rewrites_lists_and_clamps() {
    let n = MasterDnsConfig {
        domains: " v.example.com \n v2.example.com ".into(),
        encryption_key: "  k  ".into(),
        encryption_method: 9,
        resolvers: "1.1.1.1 8.8.8.8".into(),
        balancing_strategy: 42,
        packet_duplication: -1,
        proxy_link: "  vless://x  ".into(),
        ..Default::default()
    }
    .normalized();
    assert_eq!(n.domains, "v.example.com,v2.example.com");
    assert_eq!(n.encryption_key, "k");
    assert_eq!(n.resolvers, "1.1.1.1,8.8.8.8");
    assert_eq!(n.encryption_method, 5);
    assert_eq!(n.balancing_strategy, 8);
    assert_eq!(n.packet_duplication, 0);
    assert_eq!(n.proxy_link, "vless://x");
}

#[test]
fn masterdns_proxy_over_tunnel_defaults_to_xray() {
    let c = mdns("v.example.com", "k", "1.1.1.1");
    assert!(!c.has_proxy());
    assert!(MasterDnsConfig { proxy_link: "vless://x".into(), ..c.clone() }.has_proxy());
    assert_eq!(c.resolved_proxy_core(None, ProxyCore::Auto), ProxyCore::Xray);
    assert_eq!(
        MasterDnsConfig { proxy_core: ProxyCore::SingBox, ..c }.resolved_proxy_core(None, ProxyCore::Auto),
        ProxyCore::SingBox
    );
}

// ---------------- ProxyProfileSameNodeTest ----------------

fn main_node() -> ProxyProfile {
    ProxyProfile {
        tag: "Finland".into(),
        kind: TYPE_VLESS.into(),
        server: "fin.example.com".into(),
        server_port: 8443,
        uuid: "uuid-main".into(),
        network: NETWORK_XHTTP.into(),
        security: SECURITY_REALITY.into(),
        ..Default::default()
    }
}

#[test]
fn same_node_detection() {
    let main = main_node();
    assert!(main.is_same_node_as(&ProxyProfile { tag: "pasted again".into(), ..main.clone() }));
    let as_tcp = ProxyProfile { tag: "x".into(), network: NETWORK_TCP.into(), flow: "xtls-rprx-vision".into(), ..main.clone() };
    assert!(main.is_same_node_as(&as_tcp));
    let other = ProxyProfile { server: "nbg.example.com".into(), server_port: 9444, tag: "Nuremberg".into(), ..main.clone() };
    assert!(!main.is_same_node_as(&other));
    assert!(!main.is_same_node_as(&ProxyProfile { server_port: 9444, ..main.clone() }));
    let a = ProxyProfile { tag: "a".into(), raw_xray_config: Some(r#"{"outbounds":[]}"#.into()), ..Default::default() };
    let b = ProxyProfile { tag: "b".into(), raw_xray_config: Some(r#"{"outbounds":[]}"#.into()), ..Default::default() };
    assert!(a.is_same_node_as(&b));
}

// ---------------- SnolcNameTest ----------------

#[test]
fn snolc_flag_and_cyrillic_name_survive_the_fragment() {
    let key = "ab".repeat(32);
    let enc = "%F0%9F%87%B3%F0%9F%87%B1%D0%90%D0%BC%D1%81%D1%82%D0%B5%D1%80%D0%B4%D0%B0%D0%BC%20%28SNOLC%29";
    let (cfg, name) = SnolcConfig::parse_uri(&format!("snolc://195.133.76.133:8444?key={key}#{enc}")).unwrap();
    assert_eq!(name, "🇳🇱Амстердам (SNOLC)");
    assert_eq!((cfg.host.as_str(), cfg.port), ("195.133.76.133", 8444));
    assert_eq!(SnolcConfig::parse_uri(&cfg.to_uri("")).unwrap().0, cfg);
    assert!(SnolcConfig::parse_uri("snolc://h:1?key=short").is_none());
}

// ---------------- transports ----------------

#[test]
fn csqtt_peer_addr_forms() {
    let v = |peer: &str, port: i32| VkTurnConfig { csqtt_peer: peer.into(), csqtt_port: port, ..Default::default() }.csqtt_peer_addr();
    assert_eq!(v("1.2.3.4", 0), "1.2.3.4:46000");
    assert_eq!(v("1.2.3.4:5000", 0), "1.2.3.4:5000");
    assert_eq!(v("h.example", 7000), "h.example:7000");
    assert_eq!(v("2001:db8::1", 0), "[2001:db8::1]:46000");
    assert_eq!(v("[2001:db8::1]:9", 0), "[2001:db8::1]:9");
}

#[test]
fn csqtt_options_json_workers() {
    let mut vk = VkTurnConfig { core: CORE_CSQTT.into(), csqtt_peer: "1.2.3.4".into(), vk_link: "h1\nh2".into(), ..Default::default() };
    let j: serde_json::Value = serde_json::from_str(&vk.csqtt_core_options_json("c", "127.0.0.1:1", "dev")).unwrap();
    assert_eq!(j["workers"], 54);
    assert_eq!(j["vk_hashes"], "h1,h2");
    assert_eq!(j["obfs"], "video");
    vk.csqtt_workers = 500;
    let j: serde_json::Value = serde_json::from_str(&vk.csqtt_core_options_json("c", "l", "d")).unwrap();
    assert_eq!(j["workers"], CSQTT_MAX_WORKERS);
}

#[test]
fn openflux_completeness_and_session_toggle() {
    let single = OpenFluxConfig { doc_url: "https://docs.yandex.ru/x".into(), ..Default::default() };
    assert!(single.is_complete());
    assert_eq!(single.carrier_args(), vec!["--transport", "yandex", "--url", "https://docs.yandex.ru/x"]);
    let session = single.with_session(true);
    assert!(session.is_session());
    assert!(!session.is_complete()); // a session needs the secret
    assert!(OpenFluxConfig { secret: "s".into(), ..session.clone() }.is_complete());
    assert_eq!(session.with_session(false).doc_url, "https://docs.yandex.ru/x");
    let avito = OpenFluxConfig { transport: OF_AVITO.into(), doc_url: "ROOMLISTROOMLIST1234567890".into(), ..Default::default() };
    assert!(!avito.is_complete()); // Avito needs the account cookies
    assert!(OpenFluxConfig { avito_cookies: "sid=1".into(), ..avito }.is_complete());
    let max = OpenFluxConfig { transport: OF_MAX.into(), max_token: "t".into(), max_uid: "777".into(), ..Default::default() };
    assert!(max.is_complete());
    assert_eq!(max.carrier_args(), vec!["--transport", "oneme", "--maxUid", "777"]);
}

// ---------------- locations ----------------

#[test]
fn provider_and_transport_normalization() {
    assert_eq!(normalize_provider("jazz"), PROVIDER_JAZZ);
    assert_eq!(normalize_provider("???"), PROVIDER_MTS_LINK);
    // MTS Link only carries VP8; Jitsi accepts datachannel.
    assert_eq!(normalize_transport("datachannel", "mtslink"), TRANSPORT_VP8CHANNEL);
    assert_eq!(normalize_transport("dc", "jitsi"), TRANSPORT_DATACHANNEL);
    assert_eq!(transport_display_name("sei"), "SEI");
}

#[test]
fn completeness_per_engine() {
    let stealth = LocationConfig { id: "room".into(), key: "k".into(), ..Default::default() };
    assert!(stealth.is_complete());
    let vless = yptun_core::share_link::parse("vless://u@h.com:443?security=tls").unwrap();
    let standard = LocationConfig { engine: EngineType::Standard, proxy: Some(vless.clone()), ..Default::default() };
    assert!(standard.is_complete());
    assert!(!LocationConfig { engine: EngineType::Chain, proxy: Some(vless), ..Default::default() }.is_complete());

    // VK-TURN on csqtt: storable without the VK link, complete with it, and it asks for it.
    let vk = LocationConfig {
        engine: EngineType::VkTurn,
        vkturn: Some(VkTurnConfig { core: CORE_CSQTT.into(), csqtt_peer: "1.2.3.4".into(), ..Default::default() }),
        ..Default::default()
    };
    assert!(vk.is_storable());
    assert!(!vk.is_complete());
    assert!(vk.needs_vk_link());
}

#[test]
fn resolved_core_precedence() {
    let xhttp = yptun_core::share_link::parse("vless://u@h.com:443?type=xhttp&security=tls").unwrap();
    let tcp = yptun_core::share_link::parse("vless://u@h.com:443?security=tls").unwrap();
    let loc = |p: ProxyProfile, core: ProxyCore| LocationConfig { engine: EngineType::Standard, proxy: Some(p), core, ..Default::default() };
    assert_eq!(loc(xhttp.clone(), ProxyCore::Auto).resolved_core(ProxyCore::SingBox), ProxyCore::Xray);
    assert_eq!(loc(xhttp, ProxyCore::SingBox).resolved_core(ProxyCore::Auto), ProxyCore::SingBox);
    assert_eq!(loc(tcp.clone(), ProxyCore::Auto).resolved_core(ProxyCore::Auto), ProxyCore::SingBox);
    assert_eq!(loc(tcp, ProxyCore::Auto).resolved_core(ProxyCore::Xray), ProxyCore::Xray);
}

#[test]
fn multi_room_specs() {
    let loc = LocationConfig {
        id: "main".into(),
        key: "k0".into(),
        multi_room_enabled: true,
        extra_rooms: (1..=6).map(|i| ExtraRoom { room: format!("r{i}"), key: format!("k{i}"), ..Default::default() }).collect(),
        ..Default::default()
    };
    assert!(loc.uses_multi_room());
    let specs = loc.multi_room_specs();
    assert_eq!(specs.len(), MAX_EXTRA_ROOMS + 1);
    assert_eq!(specs[0].room, "main");
}

#[test]
fn legacy_stored_entry_loads() {
    // Old flat form: room/key/provider/vp8 at the top level and the transport as a bare string.
    let stored = r#"{"storage_id":"a","name":" Old ","room_id":"room1","password":"k1",
        "bypassProvider":"jazz","transport":"data","vp8Fps":200,"subscriptionUrl":"https://s"}"#;
    let e: LocationEntry = serde_json::from_str(stored).unwrap();
    let loc = e.location();
    assert_eq!(loc.name, "Old");
    assert_eq!(loc.id, "room1");
    assert_eq!(loc.key, "k1");
    assert_eq!(loc.bypass_provider, PROVIDER_JAZZ);
    assert_eq!(loc.transport, TRANSPORT_DATACHANNEL);
    assert_eq!(loc.vp8_fps, 120);
    let n = e.normalized();
    assert_eq!(n.subscription_url.as_deref(), Some("https://s"));
    assert_eq!(n.endpoint.as_ref().unwrap().room_id, "room1");
    // And the canonical form round-trips through JSON unchanged.
    let back: LocationEntry = serde_json::from_str(&serde_json::to_string(&n).unwrap()).unwrap();
    assert_eq!(back.normalized(), n);
}

#[test]
fn bundle_normalization_drops_unstorable_and_duplicates() {
    let good = LocationEntry::from("a", &LocationConfig { id: "r".into(), key: "k".into(), ..Default::default() }, None, None);
    let bad = LocationEntry::from("b", &LocationConfig::default(), None, None);
    let b = LocationBundleV4 {
        version: 3,
        active_location_id: Some("missing".into()),
        locations: vec![good.clone(), bad, good.clone()],
    }
    .normalized();
    assert_eq!(b.version, BUNDLE_VERSION);
    assert_eq!(b.locations.len(), 1);
    assert_eq!(b.active_location_id.as_deref(), Some("a"));
}

#[test]
fn metadata_cleanup() {
    let m = LocationMetadata {
        name: Some("  ".into()),
        subscription: Some(SubscriptionMetadata { name: Some(" Sub ".into()), update_interval_hours: Some(10_000), ..Default::default() }),
        ..Default::default()
    }
    .normalized();
    assert_eq!(m.name, None);
    let s = m.subscription.unwrap();
    assert_eq!(s.name.as_deref(), Some("Sub"));
    assert_eq!(s.update_interval_hours, Some(MAX_UPDATE_INTERVAL_HOURS));
    assert!(LocationMetadata::default().normalized().is_empty());
}

#[test]
fn view_index_projects_rows_without_payloads() {
    let cfg = LocationConfig {
        name: "VL".into(),
        engine: EngineType::Standard,
        proxy: Some(ProxyProfile { kind: TYPE_VLESS.into(), server: "h.example".into(), server_port: 443, uuid: "secret-uuid".into(), ..Default::default() }),
        ..Default::default()
    };
    let bundle = LocationBundleV4 {
        active_location_id: Some("a".into()),
        locations: vec![LocationEntry::from("a", &cfg, Some("https://sub.example/x".into()), None)],
        ..Default::default()
    };
    let idx = LocationViewIndex::from_bundle(&bundle);
    let item = &idx.items[0];
    assert_eq!((item.storage_id.as_str(), item.name.as_str(), item.proxy_type.as_str()), ("a", "VL", TYPE_VLESS));
    assert_eq!(item.engine, EngineType::Standard);
    assert!(item.proxy_server.contains("h.example"));
    assert_eq!(item.subscription_url.as_deref(), Some("https://sub.example/x"));
    let json = serde_json::to_string(&idx).unwrap();
    assert!(!json.contains("secret-uuid") && json.contains("\"storage_id\":\"a\""), "{json}");
}
