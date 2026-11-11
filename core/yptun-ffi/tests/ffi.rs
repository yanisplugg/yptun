//! The FFI wrappers must return exactly what yptun-core returns.

use std::sync::{Arc, Mutex};

use yptun_core::delta_bundle;
use yptun_core::engine::plan::{self, PlatformCaps};
use yptun_core::i18n::{self, AppLanguage};
use yptun_core::integrity;
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, ProxyCore, ProxyProfile};
use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::share_link;
use yptun_core::warp;
use yptun_core::xray::config::{self as xray_config, XrayOptions};
use yptun_ffi::*;

const VLESS: &str = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp&sni=example.com#Test";

fn options() -> InboundOptions {
    InboundOptions {
        listen_host: "127.0.0.1".into(),
        listen_port: 10808,
        socks_username: "u".into(),
        socks_password: "p".into(),
        log_level: "warning".into(),
        routing_profile_id: None,
        tun_mode: false,
        hijack_dns: false,
        auto_detect_interface: false,
        mixed_inbound: false,
        block_quic: false,
        log_file_path: None,
        cache_file_path: None,
    }
}

#[test]
fn share_link_matches_core() {
    let json = parse_share_link(VLESS.into()).unwrap().expect("vless parsed");
    let p: ProxyProfile = serde_json::from_str(&json).unwrap();
    assert_eq!(Some(p.clone()), share_link::parse(VLESS));
    assert_eq!(parse_share_link("not a link".into()).unwrap().is_some(), share_link::parse("not a link").is_some());

    let info = profile_info(json).unwrap();
    assert_eq!(info.display_name, p.display_name());
    assert_eq!(info.display_server, share_link::display_server(&p));
    assert_eq!(info.complete, p.is_complete());
    assert_eq!(info.requires_xray, p.requires_xray());

    let subs: Vec<ProxyProfile> = serde_json::from_str(&parse_subscription(VLESS.into()).unwrap()).unwrap();
    assert_eq!(subs, share_link::parse_subscription(VLESS));
}

#[test]
fn bad_json_is_json_error() {
    assert!(matches!(profile_info("not json".into()), Err(FfiError::Json(_))));
    assert!(matches!(location_info("not json".into()), Err(FfiError::Json(_))));
    assert!(matches!(CoreSession::new().set_traffic("not json".into()), Err(FfiError::Json(_))));
}

#[test]
fn location_matches_core() {
    let loc = LocationConfig::default();
    let json = serde_json::to_string(&loc).unwrap();
    let n: LocationConfig = serde_json::from_str(&location_normalized(json.clone()).unwrap()).unwrap();
    assert_eq!(n, loc.normalized());
    for g in [CoreKind::Auto, CoreKind::SingBox, CoreKind::Xray] {
        assert_eq!(location_resolved_core(json.clone(), g).unwrap(), CoreKind::from(loc.resolved_core(g.into())));
    }
    let info = location_info(json).unwrap();
    assert_eq!(info.display_name, loc.display_name());
    assert_eq!(info.complete, loc.is_complete());
    assert_eq!(info.storable, loc.is_storable());
    assert_eq!(info.needs_vk_link, loc.needs_vk_link());
    assert_eq!(CoreKind::from(ProxyCore::from(CoreKind::SingBox)), CoreKind::SingBox);
}

#[test]
fn plan_matches_core() {
    let standard = LocationConfig {
        engine: EngineType::Standard,
        proxy: share_link::parse(VLESS),
        proxy_enabled: true,
        ..Default::default()
    };
    for loc in [LocationConfig::default(), standard] {
        let json = serde_json::to_string(&loc).unwrap();
        let core = plan::topology(&loc.normalized(), 1080, &PlatformCaps::android());
        match (core, plan_topology(json.clone(), 1080, TargetPlatform::Android)) {
            (Ok(t), Ok(r)) => {
                assert_eq!(r.socks_port, t.ports.socks);
                assert_eq!(r.steps.len(), t.steps.len());
                assert_eq!(r.uses_proxy_core, t.uses_proxy_core());
                let ports: Vec<i32> = t.steps.iter().map(|s| s.port()).collect();
                assert_eq!(r.steps.iter().map(|s| s.port).collect::<Vec<_>>(), ports);
                assert_eq!(r.stop_order.first(), r.steps.last());
            }
            (Err(e), Err(FfiError::Unsupported(m))) => assert_eq!(e.message(), m),
            (a, b) => panic!("mismatch: {a:?} vs {b:?}"),
        }
        let supported = plan::check_supported(&loc.normalized(), &PlatformCaps::ios()).is_ok();
        assert_eq!(plan_check_supported(json, TargetPlatform::Ios).is_ok(), supported);
    }
}

#[test]
fn warp_matches_core() {
    let ini = "[Interface]\nPrivateKey = x\nAddress = 172.16.0.2/32\n[Peer]\nPublicKey = y\nEndpoint = 162.159.192.1:2408\n";
    let (host, port) = warp::endpoint_of(ini);
    assert_eq!(warp_endpoint(ini.into()), WarpEndpoint { host, port });
    assert_eq!(warp_engine_ini(ini.into(), true, false), warp::engine_ini(ini, true, false));
    assert_eq!(warp_plain_wireguard(ini.into()), warp::plain_wireguard(ini));
    let parsed = warp_parse_ini(ini.into(), "warp".into()).unwrap();
    let core = warp::parse_warp_ini(ini, "warp");
    assert_eq!(parsed.map(|j| serde_json::from_str::<ProxyProfile>(&j).unwrap()), core);
}

#[test]
fn i18n_matches_core() {
    assert_eq!(i18n_language_ids().len(), AppLanguage::ALL.len());
    let id = Some(AppLanguage::Russian.id().to_string());
    let ru = i18n_catalog(id.clone());
    let core = i18n::catalog(AppLanguage::Russian);
    assert_eq!(ru.len(), core.len());
    for (k, v) in core.iter().take(50) {
        assert_eq!(ru.get(k), Some(v));
        assert_eq!(i18n_text(id.clone(), k.clone()), *v);
    }
    let sys = Some(AppLanguage::System.id().to_string());
    let en = Some(AppLanguage::English.id().to_string());
    assert_eq!(i18n_effective_language(sys, en), AppLanguage::System.effective(AppLanguage::English).id());
}

#[test]
fn bytes_match_core() {
    assert_eq!(sha256_hex(b"abc".to_vec()).to_lowercase(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert_eq!(sha256_hex(b"abc".to_vec()), integrity::sha256_hex(b"abc"));
    assert!(!is_official_signing_sha256(None));
    assert!(!is_official_signing_sha256(Some("00".into())));

    let bytes = br#"{"format":1,"from":"1.0.0","to":"1.0.1","target":"android","ops":[]}"#.to_vec();
    match (delta_bundle::parse_manifest(&bytes), delta_parse_manifest(bytes.clone())) {
        (Ok(m), Ok(j)) => assert_eq!(serde_json::from_str::<delta_bundle::Manifest>(&j).unwrap(), m),
        (Err(e), Err(FfiError::Invalid(m))) => assert_eq!(e, m),
        (a, b) => panic!("mismatch: {a:?} vs {b:?}"),
    }
}

#[test]
fn session_builds_same_configs_as_core() {
    let s = CoreSession::new();
    s.set_traffic(serde_json::to_string(&TrafficSettings::default()).unwrap()).unwrap();
    s.set_routing(serde_json::to_string(&RoutingRules::default()).unwrap()).unwrap();
    s.set_routing_profiles(serde_json::to_string(&RoutingProfilesState::default()).unwrap()).unwrap();
    assert_eq!(s.global_core(), CoreKind::Auto);
    s.set_global_core(CoreKind::Xray);
    assert_eq!(s.global_core(), CoreKind::Xray);

    let profile = share_link::parse(VLESS).unwrap();
    let pj = serde_json::to_string(&profile).unwrap();
    let expected = xray_config::build(
        &profile,
        &XrayOptions {
            listen_port: 10808,
            listen_host: "127.0.0.1".into(),
            socks_username: "u".into(),
            socks_password: "p".into(),
            log_level: "warning".into(),
            traffic: TrafficSettings::default(),
            routing_profile: RoutingProfilesState::default().resolve(None).cloned(),
            block_quic: false,
            bypass_lan: RoutingRules::default().bypass_lan,
            ..XrayOptions::default()
        },
    );
    assert_eq!(s.build_xray_config(pj.clone(), options()).unwrap(), expected);

    let sb = s.build_singbox_config(pj, options()).unwrap();
    assert!(serde_json::from_str::<serde_json::Value>(&sb).is_ok());
    assert!(!s.prepare_raw_xray("not json".into(), options()).is_empty());
}

struct Collect(Mutex<Vec<String>>);

impl LogSink for Collect {
    fn on_logs(&self, lines: Vec<String>) {
        self.0.lock().unwrap().extend(lines);
    }
}

#[test]
fn log_buffer_batches_and_drops_oldest() {
    let b = LogBuffer::new(3);
    for i in 0..5 {
        b.push(format!("l{i}"));
    }
    let batch = b.drain(0);
    assert_eq!(batch.lines, vec!["l2", "l3", "l4"]);
    assert_eq!(batch.dropped, 2);
    assert!(b.is_empty());
    assert_eq!(b.drain(0).dropped, 0);

    b.push_all(vec!["a".into(), "b".into()]);
    b.push_str("c");
    let sink = Arc::new(Collect(Mutex::new(Vec::new())));
    assert_eq!(b.flush_to(sink.clone(), 2), 2);
    assert_eq!(b.len(), 1);
    assert_eq!(*sink.0.lock().unwrap(), vec!["a".to_string(), "b".to_string()]);
    assert_eq!(b.flush_to(sink.clone(), 0), 1);
    assert_eq!(b.flush_to(sink, 0), 0);
}
