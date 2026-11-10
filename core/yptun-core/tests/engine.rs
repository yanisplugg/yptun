//! Launch plan + connection state machine (the logic the Kotlin engine controllers duplicated).

use yptun_core::engine::*;
use yptun_core::location::LocationConfig;
use yptun_core::model::{EngineType, ProxyCore, ProxyProfile};
use yptun_core::routing::{RoutingProfile, TrafficSettings};
use yptun_core::transports::{VkTurnConfig, CORE_CSQTT};

fn vless(server: &str) -> ProxyProfile {
    ProxyProfile {
        kind: "vless".into(),
        server: server.into(),
        server_port: 443,
        uuid: "11111111-2222-3333-4444-555555555555".into(),
        ..ProxyProfile::default()
    }
}

fn hy2(server: &str) -> ProxyProfile {
    ProxyProfile { kind: "hysteria2".into(), server: server.into(), server_port: 443, ..ProxyProfile::default() }
}

fn standard(proxy: ProxyProfile) -> LocationConfig {
    LocationConfig { engine: EngineType::Standard, proxy: Some(proxy), ..LocationConfig::default() }
}

fn choose(loc: &LocationConfig, traffic: &TrafficSettings, routing: Option<&RoutingProfile>, global: ProxyCore, caps: &PlatformCaps, geo: bool) -> CoreChoice {
    let profile = loc.proxy.clone().unwrap();
    let (second, _) = resolve_second(&profile, loc.proxy2.as_ref());
    let inputs = CoreInputs {
        location: loc,
        profile: &profile,
        second: second.as_ref(),
        routing_profile: routing,
        traffic,
        global_core: global,
        caps,
    };
    choose_core(&inputs, || geo)
}

// ---- ports / topology ----

#[test]
fn port_offsets_match_kotlin() {
    let p = PortLayout::new(10808);
    assert_eq!(p.olcrtc_chain(), 10809);
    assert_eq!(p.awg_local(), 10810);
    assert_eq!(p.trust_tunnel(), 10813);
    assert_eq!(p.singbox_front(), 10814);
    assert!(p.is_valid());
    assert!(!PortLayout::new(65530).is_valid());
    assert!(!PortLayout::new(0).is_valid());
}

#[test]
fn stealth_is_a_single_olcrtc() {
    let t = topology(&LocationConfig::default(), 10808, &PlatformCaps::ios()).unwrap();
    assert_eq!(t.steps, vec![Step::OlcRtc { port: 10808 }]);
    assert!(!t.uses_proxy_core());
}

#[test]
fn chain_raises_olcrtc_then_core_through_it() {
    let loc = LocationConfig { engine: EngineType::Chain, ..standard(vless("a.example")) };
    let t = topology(&loc, 10808, &PlatformCaps::desktop()).unwrap();
    assert_eq!(
        t.steps,
        vec![Step::OlcRtc { port: 10809 }, Step::ProxyCore { port: 10808, upstream_port: Some(10809) }]
    );
    assert_eq!(t.stop_order().first(), Some(&Step::ProxyCore { port: 10808, upstream_port: Some(10809) }));
}

#[test]
fn standard_without_proxy_is_rejected() {
    let loc = LocationConfig { engine: EngineType::Standard, ..LocationConfig::default() };
    assert_eq!(topology(&loc, 10808, &PlatformCaps::android()), Err(PlanError::NoProxy));
}

#[test]
fn masterdns_with_and_without_proxy() {
    let plain = LocationConfig { engine: EngineType::MasterDns, ..LocationConfig::default() };
    assert_eq!(topology(&plain, 10808, &PlatformCaps::ios()).unwrap().steps, vec![Step::MasterDns { port: 10808 }]);
    let over = LocationConfig { engine: EngineType::MasterDns, proxy: Some(vless("a.example")), ..LocationConfig::default() };
    assert_eq!(
        topology(&over, 10808, &PlatformCaps::ios()).unwrap().steps,
        vec![Step::MasterDns { port: 10809 }, Step::ProxyCore { port: 10808, upstream_port: Some(10809) }]
    );
}

#[test]
fn csqtt_is_always_fronted_by_the_routing_core() {
    let loc = LocationConfig {
        engine: EngineType::VkTurn,
        vkturn: Some(VkTurnConfig {
            core: CORE_CSQTT.into(),
            listen_port: 10808,
            csqtt_peer: "198.51.100.7".into(),
            vk_link: "vk-hash".into(),
            ..VkTurnConfig::default()
        }),
        ..LocationConfig::default()
    };
    let t = topology(&loc, 10808, &PlatformCaps::ios()).unwrap();
    assert_eq!(t.steps, vec![Step::VkTurn { port: 10809 }, Step::ProxyCore { port: 10808, upstream_port: Some(10809) }]);
}

#[test]
fn ios_capabilities_match_the_in_process_apple_cores() {
    let of = LocationConfig { engine: EngineType::OpenFlux, ..LocationConfig::default() };
    assert!(topology(&of, 10808, &PlatformCaps::ios()).is_ok());
    assert!(topology(&of, 10808, &PlatformCaps::desktop()).is_ok());

    let snolc = LocationConfig { engine: EngineType::Snolc, ..LocationConfig::default() };
    assert!(topology(&snolc, 10808, &PlatformCaps::ios()).is_ok());
    assert!(topology(&snolc, 10808, &PlatformCaps::macos()).is_ok());

    let naive = standard(ProxyProfile { kind: "naive".into(), server: "n.example".into(), server_port: 443, ..ProxyProfile::default() });
    let err = check_supported(&naive, &PlatformCaps::ios()).unwrap_err();
    assert_eq!(err.message(), "NaïveProxy на iOS пока не поддерживается");
    assert!(check_supported(&naive, &PlatformCaps::desktop()).is_ok());
}

// ---- cascade ----

#[test]
fn cascade_into_the_same_node_is_dropped() {
    let main = vless("a.example");
    let (second, note) = resolve_second(&main, Some(&vless("a.example")));
    assert_eq!(second, None);
    assert_eq!(note, CascadeNote::DroppedSameNode);
}

#[test]
fn incomplete_second_is_dropped_and_other_node_is_used() {
    let main = vless("a.example");
    let broken = ProxyProfile { kind: "vless".into(), ..ProxyProfile::default() };
    assert_eq!(resolve_second(&main, Some(&broken)).1, CascadeNote::DroppedIncomplete);
    let (second, note) = resolve_second(&main, Some(&vless("b.example")));
    assert_eq!(second.unwrap().server, "b.example");
    assert_eq!(note, CascadeNote::Active);
    assert_eq!(resolve_second(&main, None), (None, CascadeNote::None));
}

#[test]
fn tunnel_second_cannot_ride_a_raw_xray_config() {
    let main = ProxyProfile { raw_xray_config: Some("{\"outbounds\":[]}".into()), ..vless("a.example") };
    assert_eq!(resolve_second(&main, Some(&hy2("b.example"))).1, CascadeNote::NotChainableOnRaw);
    assert_eq!(resolve_second(&main, Some(&vless("b.example"))).1, CascadeNote::ActiveOverRaw);
}

// ---- core choice ----

#[test]
fn desktop_auto_defaults_to_singbox_ios_prefers_xray() {
    let loc = standard(vless("a.example"));
    let t = TrafficSettings::default();
    let d = choose(&loc, &t, None, ProxyCore::Auto, &PlatformCaps::desktop(), true);
    assert_eq!(d, CoreChoice { core: ProxyCore::SingBox, reason: CoreReason::Resolved });
    let i = choose(&loc, &t, None, ProxyCore::Auto, &PlatformCaps::ios(), true);
    assert_eq!(i, CoreChoice { core: ProxyCore::Xray, reason: CoreReason::HappCompatible });
}

#[test]
fn explicit_choice_wins_over_happ_rule() {
    let loc = LocationConfig { core: ProxyCore::SingBox, ..standard(vless("a.example")) };
    let c = choose(&loc, &TrafficSettings::default(), None, ProxyCore::Auto, &PlatformCaps::ios(), true);
    assert_eq!(c.core, ProxyCore::SingBox);
    let c = choose(&standard(vless("a.example")), &TrafficSettings::default(), None, ProxyCore::Xray, &PlatformCaps::desktop(), true);
    assert_eq!(c.core, ProxyCore::Xray);
}

#[test]
fn udp_tunnels_stay_on_singbox() {
    let loc = LocationConfig { core: ProxyCore::Xray, ..standard(hy2("h.example")) };
    let t = TrafficSettings { block_ru_domains: true, ..TrafficSettings::default() };
    let c = choose(&loc, &t, None, ProxyCore::Xray, &PlatformCaps::ios(), true);
    assert_eq!(c, CoreChoice { core: ProxyCore::SingBox, reason: CoreReason::LocalUdpTunnel });
    assert!(!singbox_blocks_quic(&hy2("h.example")));
    assert!(singbox_blocks_quic(&vless("a.example")));
}

#[test]
fn ru_blocklist_and_dns_hosts_switch_to_xray() {
    let loc = standard(vless("a.example"));
    let t = TrafficSettings { block_ru_domains: true, ..TrafficSettings::default() };
    let c = choose(&loc, &t, None, ProxyCore::Auto, &PlatformCaps::desktop(), true);
    assert_eq!(c, CoreChoice { core: ProxyCore::Xray, reason: CoreReason::RuDomainBlocklist });

    let mut r = RoutingProfile::default();
    r.dns_hosts.insert("example.com".into(), "1.2.3.4".into());
    let c = choose(&loc, &TrafficSettings::default(), Some(&r), ProxyCore::Auto, &PlatformCaps::desktop(), true);
    assert_eq!(c, CoreChoice { core: ProxyCore::Xray, reason: CoreReason::RoutingProfileDnsHosts });
}

#[test]
fn missing_geo_files_fall_back_to_singbox() {
    let loc = LocationConfig { core: ProxyCore::Xray, ..standard(vless("a.example")) };
    let r = RoutingProfile { proxy_sites: vec!["geosite:google".into()], ..RoutingProfile::default() };
    let c = choose(&loc, &TrafficSettings::default(), Some(&r), ProxyCore::Auto, &PlatformCaps::desktop(), false);
    assert_eq!(c, CoreChoice { core: ProxyCore::SingBox, reason: CoreReason::GeoUnavailable });
    let c = choose(&loc, &TrafficSettings::default(), Some(&r), ProxyCore::Auto, &PlatformCaps::desktop(), true);
    assert_eq!(c.core, ProxyCore::Xray);
}

#[test]
fn geo_probe_is_lazy() {
    let loc = standard(vless("a.example"));
    let profile = loc.proxy.clone().unwrap();
    let traffic = TrafficSettings::default();
    let caps = PlatformCaps::desktop();
    let inputs = CoreInputs { location: &loc, profile: &profile, second: None, routing_profile: None, traffic: &traffic, global_core: ProxyCore::Auto, caps: &caps };
    let c = choose_core(&inputs, || panic!("geo must not be probed for sing-box without a routing profile"));
    assert_eq!(c.core, ProxyCore::SingBox);
}

#[test]
fn xhttp_second_forces_xray() {
    let loc = LocationConfig {
        proxy2: Some(ProxyProfile { network: "xhttp".into(), ..vless("b.example") }),
        core: ProxyCore::SingBox,
        ..standard(vless("a.example"))
    };
    let c = choose(&loc, &TrafficSettings::default(), None, ProxyCore::Auto, &PlatformCaps::desktop(), true);
    assert_eq!(c, CoreChoice { core: ProxyCore::Xray, reason: CoreReason::SecondRequiresXray });
}

// ---- state machine ----

fn published(actions: &[Action]) -> Vec<Status> {
    actions.iter().filter_map(|a| if let Action::PublishStatus(s) = a { Some(s.clone()) } else { None }).collect()
}

#[test]
fn happy_path_connect_and_disconnect() {
    let mut m = ConnectionMachine::default();
    let a = m.handle(Event::Start);
    assert_eq!(a, vec![Action::PublishStatus(Status::Connecting), Action::StartEngines]);
    assert_eq!(m.handle(Event::Ready), vec![Action::PublishStatus(Status::Connected)]);
    assert_eq!(m.handle(Event::Stop), vec![Action::PublishStatus(Status::Stopping), Action::StopEngines]);
    assert_eq!(m.handle(Event::Stopped), vec![Action::PublishStatus(Status::Disconnected)]);
}

#[test]
fn engine_death_retries_with_backoff() {
    let policy = RetryPolicy { base_delay_ms: 1_000, max_delay_ms: 4_000, max_attempts: 0 };
    let mut m = ConnectionMachine::new(policy);
    m.handle(Event::Start);
    m.handle(Event::Ready);
    let a = m.handle(Event::EngineDied { message: "sing-box exited".into() });
    assert!(a.contains(&Action::StopEngines));
    assert!(a.contains(&Action::ScheduleRetry { delay_ms: 1_000 }));
    assert_eq!(m.status(), &Status::Reconnecting { attempt: 1 });
    let a = m.handle(Event::RetryTimer);
    assert_eq!(a, vec![Action::StartEngines]);
    let a = m.handle(Event::StartFailed { message: "port".into(), fatal: false });
    assert!(a.contains(&Action::ScheduleRetry { delay_ms: 2_000 }));
    assert_eq!(policy.delay_ms(10), 4_000);
}

#[test]
fn retries_give_up_after_max_attempts() {
    let mut m = ConnectionMachine::new(RetryPolicy { base_delay_ms: 10, max_delay_ms: 10, max_attempts: 1 });
    m.handle(Event::Start);
    m.handle(Event::StartFailed { message: "a".into(), fatal: false });
    m.handle(Event::RetryTimer);
    let a = m.handle(Event::StartFailed { message: "boom".into(), fatal: false });
    assert_eq!(published(&a), vec![Status::Error("boom".into())]);
}

#[test]
fn fatal_error_does_not_retry() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    let a = m.handle(Event::PlanRejected(PlanError::NoProxy));
    assert!(!a.iter().any(|x| matches!(x, Action::ScheduleRetry { .. })));
    assert_eq!(m.status(), &Status::Error("No proxy configured".into()));
    assert_eq!(m.handle(Event::Stop), vec![Action::PublishStatus(Status::Disconnected)]);
}

#[test]
fn vk_link_prompt_then_connect() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    let a = m.handle(Event::PlanRejected(PlanError::VkLinkRequired));
    assert_eq!(a, vec![Action::PublishStatus(Status::NeedsUser), Action::RequestVkLink]);
    let a = m.handle(Event::VkLinkProvided);
    assert_eq!(a, vec![Action::PublishStatus(Status::Connecting), Action::StartEngines]);
}

#[test]
fn network_loss_waits_and_network_back_restarts() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    m.handle(Event::Ready);
    let a = m.handle(Event::NetworkChanged { available: false });
    assert_eq!(a, vec![Action::PublishStatus(Status::Reconnecting { attempt: 1 })]);
    let a = m.handle(Event::NetworkChanged { available: true });
    assert_eq!(a, vec![Action::StopEngines]);
    assert_eq!(m.handle(Event::Stopped), vec![Action::StartEngines]);
    assert_eq!(m.handle(Event::Ready), vec![Action::PublishStatus(Status::Connected)]);
}

#[test]
fn server_switch_restarts_the_plan() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    m.handle(Event::Ready);
    let a = m.handle(Event::ServerSwitched);
    assert_eq!(a, vec![Action::PublishStatus(Status::Connecting), Action::StopEngines]);
    assert_eq!(m.handle(Event::Stopped), vec![Action::StartEngines]);
}

#[test]
fn stop_cancels_pending_retry_and_late_events_are_ignored() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    m.handle(Event::EngineDied { message: "x".into() });
    let a = m.handle(Event::Stop);
    assert!(a.contains(&Action::CancelRetry));
    assert!(m.handle(Event::RetryTimer).is_empty());
    assert!(m.handle(Event::Ready).is_empty());
    m.handle(Event::Stopped);
    assert_eq!(m.status(), &Status::Disconnected);
}

#[test]
fn start_while_stopping_restarts_after_stop() {
    let mut m = ConnectionMachine::default();
    m.handle(Event::Start);
    m.handle(Event::Ready);
    m.handle(Event::Stop);
    assert!(m.handle(Event::Start).is_empty());
    assert_eq!(m.handle(Event::Stopped), vec![Action::PublishStatus(Status::Connecting), Action::StartEngines]);
}
