use yptun_core::location::LocationConfig;
use yptun_core::model::EngineType;
use yptun_core::routing::TrafficSettings;
use yptun_ffi::{
    apple_bridge_plan, apple_import_locations, apple_location_catalog, apple_location_json,
    apple_migrate_location_bundle, apple_native_runtime_plan, apple_save_location,
    apple_select_location, apple_tunnel_plan, AppleActionKind, AppleConnectionRuntime,
    AppleProxyRuntimeKind, AppleStatusKind,
};

fn kinds(actions: &[yptun_ffi::AppleConnectionAction]) -> Vec<AppleActionKind> {
    actions.iter().map(|action| action.kind).collect()
}

#[test]
fn connect_ready_stop_is_driven_by_ordered_actions() {
    let runtime = AppleConnectionRuntime::new(1_000, 30_000, 0);

    let start = runtime.start();
    assert_eq!(
        kinds(&start),
        vec![
            AppleActionKind::PublishStatus,
            AppleActionKind::StartEngines
        ]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Connecting);

    let ready = runtime.ready();
    assert_eq!(kinds(&ready), vec![AppleActionKind::PublishStatus]);
    assert_eq!(runtime.status().kind, AppleStatusKind::Connected);

    let stop = runtime.stop();
    assert_eq!(
        kinds(&stop),
        vec![AppleActionKind::PublishStatus, AppleActionKind::StopEngines]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Stopping);

    let stopped = runtime.engines_stopped();
    assert_eq!(kinds(&stopped), vec![AppleActionKind::PublishStatus]);
    assert_eq!(runtime.status().kind, AppleStatusKind::Disconnected);
}

#[test]
fn network_handoff_restarts_the_running_plan() {
    let runtime = AppleConnectionRuntime::new(1_000, 30_000, 0);
    runtime.start();
    runtime.ready();

    let actions = runtime.network_changed(true);
    assert_eq!(
        kinds(&actions),
        vec![AppleActionKind::PublishStatus, AppleActionKind::StopEngines]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Reconnecting);

    let restart = runtime.engines_stopped();
    assert_eq!(kinds(&restart), vec![AppleActionKind::StartEngines]);
}

#[test]
fn retry_and_user_required_states_keep_context() {
    let runtime = AppleConnectionRuntime::new(500, 2_000, 3);
    runtime.start();

    let failure = runtime.start_failed("temporary".into(), false);
    assert_eq!(
        kinds(&failure),
        vec![
            AppleActionKind::StopEngines,
            AppleActionKind::PublishStatus,
            AppleActionKind::ScheduleRetry,
        ]
    );
    assert_eq!(failure.last().and_then(|action| action.delay_ms), Some(500));
    assert_eq!(runtime.status().attempt, 1);

    runtime.stop();
    runtime.engines_stopped();
    runtime.start();
    let needs_user = runtime.vk_link_required();
    assert_eq!(
        kinds(&needs_user),
        vec![
            AppleActionKind::PublishStatus,
            AppleActionKind::RequestVkLink
        ]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::NeedsUser);
}

#[test]
fn tunnel_plan_keeps_fake_dns_inside_the_vpn_and_bypasses_lan() {
    let plan = apple_tunnel_plan(900, true);
    assert_eq!(plan.mtu, 1_280);
    assert_eq!(plan.ipv4_address, "198.51.100.1");
    assert_eq!(plan.ipv6_prefix_length, 126);
    assert_eq!(plan.dns_servers, vec!["1.1.1.1"]);
    assert_eq!(plan.dns_match_domains, vec![""]);
    assert!(plan.include_default_ipv4_route && plan.include_default_ipv6_route);
    assert!(plan
        .excluded_ipv4_routes
        .iter()
        .any(|route| route.address == "192.168.0.0" && route.subnet_mask == "255.255.0.0"));
    assert!(!plan
        .excluded_ipv4_routes
        .iter()
        .any(|route| route.address == "100.64.0.0"));
    assert!(apple_tunnel_plan(1_500, false)
        .excluded_ipv4_routes
        .is_empty());
}

#[test]
fn bridge_plan_matches_engine_and_ip_family_rules() {
    let mut location = LocationConfig::default();
    location.engine = EngineType::MasterDns;
    let mut traffic = TrafficSettings::default();
    traffic.mtu = 1_420;
    traffic.domain_strategy = "prefer_ipv6".into();
    let plan = apple_bridge_plan(
        serde_json::to_string(&location).unwrap(),
        serde_json::to_string(&traffic).unwrap(),
        10_808,
        "u'ser".into(),
        "pass".into(),
    )
    .unwrap();

    assert!(plan.tcp_only_udp);
    assert!(plan.slow_tunnel);
    assert!(plan.drop_ipv6);
    assert!(plan.yaml.contains("mtu: 1420"));
    assert!(plan.yaml.contains("udp: 'tcp'"));
    assert!(plan.yaml.contains("username: 'u''ser'"));
    assert!(plan.yaml.contains("connect-timeout: 30000"));
    assert!(plan.yaml.contains("# ipv6 disabled"));
    assert!(plan.yaml.contains("cache-size: 2048"));
    assert!(plan.yaml.contains("task-stack-size: 16384"));
    assert!(plan.yaml.contains("max-session-count: 512"));

    location.engine = EngineType::VkTurn;
    let vk = apple_bridge_plan(
        serde_json::to_string(&location).unwrap(),
        serde_json::to_string(&traffic).unwrap(),
        10_808,
        "u".into(),
        "p".into(),
    )
    .unwrap();
    assert!(vk.drop_ipv6);
    assert!(vk.slow_tunnel);
    assert!(vk.yaml.contains("# ipv6 disabled"));

    location.engine = EngineType::Standard;
    traffic.domain_strategy = "prefer_ipv4".into();
    let prefer_ipv4 = apple_bridge_plan(
        serde_json::to_string(&location).unwrap(),
        serde_json::to_string(&traffic).unwrap(),
        10_808,
        String::new(),
        String::new(),
    )
    .unwrap();
    assert!(!prefer_ipv4.drop_ipv6);
    assert!(prefer_ipv4.yaml.contains("# no auth"));
}

#[test]
fn location_migration_preserves_valid_kotlin_bundle_data() {
    let legacy = serde_json::json!({
        "version": 4,
        "active_location_id": "keep",
        "locations": [
            {
                "storage_id": "keep",
                "name": "Москва",
                "engine": "stealth",
                "id": "room",
                "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            },
            {
                "storage_id": "keep",
                "name": "duplicate",
                "engine": "stealth",
                "id": "other",
                "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            }
        ]
    });

    let migrated = apple_migrate_location_bundle(legacy.to_string()).unwrap();
    assert_eq!(migrated.source_version, 4);
    assert_eq!(migrated.target_version, 5);
    assert_eq!(migrated.locations_before, 2);
    assert_eq!(migrated.locations_after, 1);
    assert_eq!(migrated.active_location_id.as_deref(), Some("keep"));
    assert!(migrated.normalized_json.contains("Москва"));
}

#[test]
fn location_catalog_preserves_selection_and_returns_ui_fields() {
    let bundle = serde_json::json!({
        "version": 5,
        "active_location_id": "selected",
        "locations": [
            {
                "storage_id": "selected",
                "name": "Основная",
                "description": "Надёжное подключение",
                "engine": "stealth",
                "id": "room",
                "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "subscription_url": "https://example.com/sub"
            }
        ]
    });

    let catalog = apple_location_catalog(bundle.to_string()).unwrap();
    assert_eq!(catalog.active_location_id.as_deref(), Some("selected"));
    assert_eq!(catalog.locations.len(), 1);
    let location = &catalog.locations[0];
    assert_eq!(location.id, "selected");
    assert_eq!(location.display_name, "Основная");
    assert_eq!(location.detail, "Надёжное подключение");
    assert!(location.complete);
    assert!(location.from_subscription);
    assert!(!location.needs_user_action);
}

#[test]
fn location_selection_returns_payload_and_changes_only_the_active_id() {
    let bundle = serde_json::json!({
        "version": 5,
        "active_location_id": "first",
        "locations": [
            {
                "storage_id": "first",
                "name": "First",
                "engine": "stealth",
                "id": "one",
                "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            },
            {
                "storage_id": "second",
                "name": "Second",
                "engine": "stealth",
                "id": "two",
                "key": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            }
        ]
    });

    let second = apple_location_json(bundle.to_string(), "second".into())
        .unwrap()
        .expect("second location");
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&second).unwrap()["name"],
        "Second"
    );

    let selection = apple_select_location(bundle.to_string(), "second".into()).unwrap();
    let selected: serde_json::Value =
        serde_json::from_str(&selection.selected_location_json).unwrap();
    let stored: serde_json::Value =
        serde_json::from_str(&selection.normalized_bundle_json).unwrap();
    assert_eq!(selected["name"], "Second");
    assert_eq!(stored["active_location_id"], "second");
    assert_eq!(stored["locations"].as_array().unwrap().len(), 2);
    assert!(apple_select_location(bundle.to_string(), "missing".into()).is_err());
}

#[test]
fn native_runtime_plan_builds_standard_chain_and_stealth_without_swift_models() {
    let standard = serde_json::json!({
        "name": "Standard",
        "engine": "standard",
        "proxy": {
            "type": "vless",
            "server": "example.com",
            "server_port": 443,
            "uuid": "11111111-2222-3333-4444-555555555555",
            "security": "tls",
            "sni": "example.com"
        }
    });
    let plan = apple_native_runtime_plan(
        standard.to_string(),
        String::new(),
        String::new(),
        String::new(),
        r#"{"globalProxyCore":"singbox"}"#.into(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        "tunnel.log".into(),
        "cache.db".into(),
    )
    .unwrap();
    assert!(plan.olcrtc.is_none());
    assert_eq!(
        plan.proxy.as_ref().unwrap().kind,
        AppleProxyRuntimeKind::SingBox
    );
    assert!(plan.proxy.as_ref().unwrap().config.contains("example.com"));

    let stealth = serde_json::json!({
        "name": "Stealth",
        "engine": "stealth",
        "id": "room",
        "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    });
    let plan = apple_native_runtime_plan(
        stealth.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        String::new(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert_eq!(plan.olcrtc.as_ref().unwrap().room_id, "room");
    assert!(plan.proxy.is_none());

    let chain = serde_json::json!({
        "name": "Chain",
        "engine": "chain",
        "id": "room",
        "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "proxy": {
            "type": "vless",
            "server": "example.com",
            "server_port": 443,
            "uuid": "11111111-2222-3333-4444-555555555555"
        }
    });
    let plan = apple_native_runtime_plan(
        chain.to_string(),
        String::new(),
        String::new(),
        String::new(),
        r#"{"globalProxyCore":"singbox"}"#.into(),
        String::new(),
        10_808,
        "device".into(),
        String::new(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert_eq!(plan.olcrtc.as_ref().unwrap().listen_port, 10_809);
    assert!(plan.proxy.as_ref().unwrap().config.contains("olcrtc-out"));
}

#[test]
fn native_runtime_plan_keeps_mieru_link_for_the_isolated_runtime() {
    let location = serde_json::json!({
        "name": "Mieru",
        "engine": "standard",
        "proxy": {
            "type": "mieru",
            "server": "mieru-config",
            "server_port": 1,
            "mieru_config": "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3pp"
        }
    });
    let plan = apple_native_runtime_plan(
        location.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        String::new(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert_eq!(
        plan.proxy.as_ref().unwrap().kind,
        AppleProxyRuntimeKind::Mieru
    );
    assert!(plan.proxy.as_ref().unwrap().config.starts_with("mieru://"));
}

#[test]
fn apple_import_adds_mieru_without_dropping_existing_locations() {
    let current = serde_json::json!({
        "version": 5,
        "active_location_id": "existing",
        "locations": [{
            "storage_id": "existing",
            "name": "Existing",
            "engine": "stealth",
            "id": "room",
            "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }]
    });
    let imported = apple_import_locations(
        current.to_string(),
        "mierus://user:p%40ss@203.0.113.9?profile=fast&port=4443&protocol=TCP#Tokyo".into(),
        Some("https://example.com/sub".into()),
    )
    .unwrap();
    let bundle: serde_json::Value = serde_json::from_str(&imported.normalized_bundle_json).unwrap();
    assert_eq!(imported.imported_locations, 1);
    assert_eq!(bundle["locations"].as_array().unwrap().len(), 2);
    assert_eq!(bundle["active_location_id"], "existing");
    assert!(imported.normalized_bundle_json.contains("mieru_config"));
}

#[test]
fn apple_save_location_preserves_subscription_metadata_when_editing() {
    let current = serde_json::json!({
        "version": 5,
        "active_location_id": "node",
        "locations": [{
            "storage_id": "node",
            "name": "Before",
            "engine": "stealth",
            "id": "room",
            "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "subscription_url": "https://example.com/sub"
        }]
    });
    let edited = serde_json::json!({
        "name": "After",
        "engine": "stealth",
        "id": "room",
        "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    });
    let saved =
        apple_save_location(current.to_string(), Some("node".into()), edited.to_string()).unwrap();
    let bundle: serde_json::Value = serde_json::from_str(&saved.normalized_bundle_json).unwrap();
    assert_eq!(bundle["locations"][0]["name"], "After");
    assert_eq!(
        bundle["locations"][0]["subscription_url"],
        "https://example.com/sub"
    );
    assert_eq!(bundle["active_location_id"], "node");
}

#[test]
fn native_runtime_plan_starts_turnable_and_slow_tunnel_engines() {
    let turnable = serde_json::json!({
        "name": "Turnable",
        "engine": "vkturn",
        "vkturn": {
            "core": "turnable",
            "listen_port": 9000,
            "turnable_config": "turnable://user:call@vk.com/wireguard?type=relay"
        },
        "proxy": {
            "type": "wireguard",
            "rawOutbound": "{\"type\":\"wireguard\",\"server\":\"127.0.0.1\",\"server_port\":9000,\"local_address\":[\"10.9.0.2/32\"],\"private_key\":\"K\",\"peer_public_key\":\"P\"}"
        }
    });
    let plan = apple_native_runtime_plan(
        turnable.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert!(plan.turnable.is_some());
    assert_eq!(
        plan.proxy.as_ref().unwrap().kind,
        AppleProxyRuntimeKind::SingBox
    );

    let master_dns = serde_json::json!({
        "name": "MasterDNS",
        "engine": "masterdns",
        "masterdns": {
            "domains": "t.example",
            "key": "secret",
            "resolvers": "1.1.1.1"
        }
    });
    let plan = apple_native_runtime_plan(
        master_dns.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert_eq!(plan.auxiliary.len(), 1);
    assert!(plan.proxy.is_none());

    let open_flux = serde_json::json!({
        "name": "OpenFlux",
        "engine": "openflux",
        "openflux": {
            "transport": "yandex",
            "doc_url": "https://docs.yandex.ru/docs/view?url=test"
        }
    });
    let plan = apple_native_runtime_plan(
        open_flux.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert_eq!(plan.auxiliary.len(), 1);
    assert!(plan.proxy.is_none());

    let snolc = serde_json::json!({
        "name": "snolc",
        "engine": "snolc",
        "snolc": {
            "host": "203.0.113.10",
            "port": 443,
            "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    });
    let plan = apple_native_runtime_plan(
        snolc.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert!(plan
        .snolc
        .as_ref()
        .unwrap()
        .config_path
        .ends_with("snolc/snolc.toml"));

    let csqtt = serde_json::json!({
        "name": "CSQTT",
        "engine": "vkturn",
        "vkturn": {
            "core": "csqtt",
            "listen_port": 9000,
            "csqtt_peer": "203.0.113.20:46000",
            "vk_link": "hash"
        }
    });
    let plan = apple_native_runtime_plan(
        csqtt.to_string(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        String::new(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    assert!(plan.csqtt.is_some());
    assert_eq!(
        plan.proxy.as_ref().unwrap().kind,
        AppleProxyRuntimeKind::SingBox
    );
}

#[test]
fn native_runtime_plan_keeps_existing_warp_config_in_the_proxy_chain() {
    let location = serde_json::json!({
        "name": "WARP",
        "engine": "standard",
        "warp_enabled": true,
        "warp_protocol": "awg",
        "proxy": {
            "type": "vless",
            "server": "example.com",
            "server_port": 443,
            "uuid": "11111111-2222-3333-4444-555555555555"
        }
    });
    let warp = "[Interface]\nPrivateKey = private\nAddress = 172.16.0.2/32\n[Peer]\nPublicKey = public\nEndpoint = engage.cloudflareclient.com:2408\n";
    let plan = apple_native_runtime_plan(
        location.to_string(),
        String::new(),
        String::new(),
        String::new(),
        r#"{"globalProxyCore":"singbox"}"#.into(),
        warp.into(),
        10_808,
        "device".into(),
        "runtime".into(),
        String::new(),
        String::new(),
    )
    .unwrap();
    let awg = plan.amnezia_wg.expect("WARP AWG runtime");
    assert!(awg.ini_config.contains("Endpoint = 127.0.0.1:10811"));
    assert!(plan.proxy.unwrap().config.contains("warp-relay-in"));
}
