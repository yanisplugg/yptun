use std::collections::{BTreeSet, VecDeque};
use std::net::Ipv4Addr;

use yptun_core::android_vpn::*;
use yptun_core::home::VpnStatus;

fn package_set(values: &[&str]) -> BTreeSet<String> {
    values.iter().map(|v| (*v).to_string()).collect()
}

fn route_covers(route: &IpPrefix, address: Ipv4Addr) -> bool {
    let Ok(base) = route.address.parse::<Ipv4Addr>() else {
        return false;
    };
    let prefix = route.prefix;
    let base = u32::from(base) as u64;
    let address = u32::from(address) as u64;
    let size = 1u64 << (32 - prefix);
    address >= base && address < base + size
}

fn covered(routes: &[IpPrefix], address: &str) -> bool {
    let address = address.parse::<Ipv4Addr>().unwrap();
    routes.iter().any(|route| route_covers(route, address))
}

#[test]
fn modes_hosts_and_ports_match_kotlin() {
    assert_eq!(
        AndroidConnectionMode::from_value(None),
        AndroidConnectionMode::Tun
    );
    assert_eq!(
        AndroidConnectionMode::from_value(Some("proxy")),
        AndroidConnectionMode::Proxy
    );
    assert_eq!(
        AndroidConnectionMode::from_value(Some("tproxy")),
        AndroidConnectionMode::Tproxy
    );
    assert_eq!(
        AndroidConnectionMode::from_value(Some("bad")),
        AndroidConnectionMode::Tun
    );
    assert!(AndroidConnectionMode::Proxy.is_tunless());
    assert!(!AndroidConnectionMode::Tun.is_tunless());

    assert_eq!(
        AndroidSplitTunnelMode::from_value(Some("proxy_selected")).value(),
        "proxy_selected"
    );
    assert_eq!(
        AndroidSplitTunnelMode::from_value(Some("bad")),
        AndroidSplitTunnelMode::AllApps
    );

    assert_eq!(sanitize_socks_host(Some("  10.0.0.1\r\n  ")), "10.0.0.1");
    assert_eq!(sanitize_socks_host(Some("\n\r")), DEFAULT_SOCKS_HOST);
    assert_eq!(
        sanitize_socks_host(Some(&"a".repeat(300))).len(),
        MAX_SOCKS_HOST_LENGTH
    );
    assert_eq!(sanitize_socks_port(Some(80)), DEFAULT_SOCKS_PORT);
    assert_eq!(sanitize_socks_port(Some(65_535)), 65_535);
    assert_eq!(socks_connect_host("0.0.0.0"), DEFAULT_SOCKS_HOST);
    assert_eq!(socks_connect_host("[::]"), DEFAULT_SOCKS_HOST);
}

#[test]
fn start_options_secure_tun_and_open_proxy() {
    let mut values = VecDeque::from([
        Ok("session-user".to_string()),
        Ok("session-pass".to_string()),
    ]);
    let tun = AndroidStartOptions {
        socks_listen_host: " host\n".into(),
        socks_listen_port: 1,
        socks_username: "kept only when both exist".into(),
        socks_password: " ".into(),
        split_tunnel_proxy_apps: package_set(&[" a ", "", "a"]),
        ..Default::default()
    };
    let tun = apply_start_options_with(tun, || values.pop_front().unwrap()).unwrap();
    assert_eq!(tun.socks_listen_host, "host");
    assert_eq!(tun.socks_listen_port, DEFAULT_SOCKS_PORT);
    assert_eq!(tun.socks_username, "session-user");
    assert_eq!(tun.socks_password, "session-pass");
    assert_eq!(tun.split_tunnel_proxy_apps, package_set(&["a"]));

    let proxy = AndroidStartOptions {
        connection_mode: AndroidConnectionMode::Proxy,
        socks_listen_host: "127.0.0.1".into(),
        socks_username: "user".into(),
        socks_password: "pass".into(),
        ..Default::default()
    };
    let proxy =
        apply_start_options_with(proxy, || panic!("proxy mode must not generate credentials"))
            .unwrap();
    assert_eq!(proxy.socks_listen_host, ALL_INTERFACES);
    assert!(proxy.socks_username.is_empty());
    assert!(proxy.socks_password.is_empty());

    let token = random_socks_token().unwrap();
    assert_eq!(token.len(), SOCKS_TOKEN_LENGTH);
    assert!(token.bytes().all(|b| b.is_ascii_alphanumeric()));
}

#[test]
fn split_tunnel_plans_are_normalized() {
    let own = "org.yptun.app";
    let all =
        split_tunnel_plan(AndroidSplitTunnelMode::AllApps, own, Vec::new(), Vec::new()).unwrap();
    assert_eq!(all.disallowed_packages, vec![own]);
    assert!(!all.require_one_allowed);

    let selected = split_tunnel_plan(
        AndroidSplitTunnelMode::ProxySelected,
        own,
        vec![" b ".into(), own.into(), "a".into(), "a".into(), "".into()],
        Vec::new(),
    )
    .unwrap();
    assert_eq!(selected.allowed_packages, vec!["a", "b"]);
    assert!(selected.require_one_allowed);
    assert!(split_tunnel_plan(
        AndroidSplitTunnelMode::ProxySelected,
        own,
        vec![own.into()],
        Vec::new()
    )
    .is_err());

    let bypass = split_tunnel_plan(
        AndroidSplitTunnelMode::BypassSelected,
        own,
        Vec::new(),
        vec!["z".into(), " a ".into(), own.into()],
    )
    .unwrap();
    assert_eq!(bypass.disallowed_packages, vec![own, "a", "z"]);
}

#[test]
fn lan_exclusion_routes_cover_everything_else() {
    let routes = ipv4_routes_excluding_lan();
    for address in [
        "1.1.1.1",
        "8.8.8.8",
        "100.64.0.1",
        "223.255.255.255",
        "240.0.0.1",
    ] {
        assert!(covered(&routes, address), "{address} should enter the TUN");
    }
    for address in [
        "10.0.0.1",
        "127.0.0.1",
        "169.254.1.1",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.1.1",
        "224.0.0.251",
        "255.255.255.255",
    ] {
        assert!(
            !covered(&routes, address),
            "{address} should stay on the LAN"
        );
    }
    assert!(routes.iter().all(|r| r.prefix <= 32));
}

#[test]
fn tunnel_plan_always_captures_ipv6_and_mapdns() {
    let full = android_tunnel_plan(1_420, false);
    assert_eq!(full.session_name, "YPtun");
    assert!(full.blocking);
    assert_eq!(full.mtu, 1_420);
    assert_eq!(full.dns_servers, vec![MAPDNS_ADDRESS]);
    assert!(full.addresses.contains(&IpPrefix {
        address: TUN_IPV4_ADDRESS.into(),
        prefix: 24
    }));
    assert!(full.addresses.contains(&IpPrefix {
        address: TUN_IPV6_ADDRESS.into(),
        prefix: 126
    }));
    assert!(full.routes.contains(&IpPrefix {
        address: "0.0.0.0".into(),
        prefix: 0
    }));
    assert!(full.routes.contains(&IpPrefix {
        address: "::".into(),
        prefix: 0
    }));

    let bypass = android_tunnel_plan(TUN_MTU, true);
    assert!(!covered(&bypass.routes, "192.168.1.2"));
    assert!(covered(&bypass.routes, "100.64.0.2"));
    assert!(bypass.routes.contains(&IpPrefix {
        address: "::".into(),
        prefix: 0
    }));
}

#[test]
fn tun2socks_config_matches_modes_and_quotes_secrets() {
    let mut options = Tun2SocksOptions {
        mtu: 1_280,
        drop_ipv6: false,
        socks_listen_host: ALL_INTERFACES.into(),
        socks_listen_port: 10_808,
        socks_username: "u'ser".into(),
        socks_password: "p'ass".into(),
        engine: Tun2SocksEngine::Other,
    };
    let normal = tun2socks_config(&options);
    assert!(normal.contains("ipv6: 'fdfe:dcba:9876::1'"));
    assert!(normal.contains("address: 127.0.0.1"));
    assert!(normal.contains("udp: 'udp'"));
    assert!(normal.contains("username: 'u''ser'"));
    assert!(normal.contains("password: 'p''ass'"));
    assert!(normal.contains("connect-timeout: 10000"));

    options.drop_ipv6 = true;
    options.engine = Tun2SocksEngine::MasterDns;
    let dns = tun2socks_config(&options);
    assert!(dns.contains("# ipv6 disabled (IPv4 only/preferred)"));
    assert!(dns.contains("udp: 'tcp'"));
    assert!(dns.contains("connect-timeout: 30000"));
}

#[test]
fn olcrtc_dns_prefers_upstream_then_fallbacks() {
    assert_eq!(dns_endpoint("192.0.2.1"), "192.0.2.1:53");
    assert_eq!(dns_endpoint("2001:db8::1"), "[2001:db8::1]:53");
    assert_eq!(olcrtc_dns_servers(None), FALLBACK_OLCRTC_DNS_SERVERS);
    assert_eq!(
        olcrtc_dns_servers(Some("2001:db8::53")),
        format!("[2001:db8::53]:53,{FALLBACK_OLCRTC_DNS_SERVERS}")
    );
}

#[test]
fn vkturn_streams_scale_gently_and_links_partition() {
    assert_eq!(vkturn_stream_count("", 0), 10);
    assert_eq!(vkturn_stream_count("a,b", 0), 12);
    assert_eq!(vkturn_stream_count("a b c d", 0), 12);
    assert_eq!(vkturn_stream_count("a", 20), 20);
    assert_eq!(vkturn_stream_count("a", 500), 64);

    let parts = partition_vk_links("a,b,c,d,e", 3);
    assert_eq!(parts, vec![vec!["a", "d"], vec!["b", "e"], vec!["c"]]);
    let wrapped = partition_vk_links("a,b", 4);
    assert_eq!(wrapped, vec![vec!["a"], vec!["b"], vec!["a"], vec!["b"]]);
    assert!(partition_vk_links("a", 0).is_empty());
}

#[test]
fn tproxy_scripts_are_scoped_and_verdict_is_parsed() {
    let teardown = tproxy_teardown_lines().join("\n");
    assert!(teardown.contains("-D OUTPUT -j YPT_OUT"));
    assert!(teardown.contains("fwmark 0x7e1 lookup 2026 pref 90"));

    let script = tproxy_apply_script(10_123, 10_812);
    assert!(script.starts_with("export PATH="));
    assert!(script.contains("--uid-owner 10123 -j RETURN"));
    assert!(script.contains("--on-port 10812 --tproxy-mark 0x7e1"));
    assert!(script.contains("-p udp --dport 53 -j MARK"));
    assert!(script.ends_with("then echo YPT_OK; else echo YPT_FAIL; fi"));

    assert_eq!(tproxy_failure(None, "noise\nYPT_OK"), None);
    assert_eq!(
        tproxy_failure(Some("denied"), ""),
        Some("Root (su) unavailable: denied".into())
    );
    assert_eq!(
        tproxy_failure(None, "line1\nline2\nline3\nline4\nYPT_FAIL"),
        Some("line2 | line3 | line4".into())
    );
}

#[test]
fn rtc_health_uses_grace_windows_and_thresholds() {
    let mut health = RtcHealth::new(1_000);
    assert_eq!(
        health.observe_line(
            "ICE connection state changed: failed",
            2_000,
            true,
            AndroidConnectionMode::Tun
        ),
        None
    );
    assert_eq!(
        health.observe_line(
            "ICE connection state changed: failed",
            4_000,
            true,
            AndroidConnectionMode::Tun
        ),
        Some(RecoveryRequest {
            reason: "RTC failed".into(),
            full_restart: true
        })
    );

    assert_eq!(
        health.observe_line(
            "socks5 server listening",
            5_000,
            true,
            AndroidConnectionMode::Tun
        ),
        None
    );
    assert_eq!(
        health.observe_line(
            "peer connection state changed: closed",
            8_000,
            true,
            AndroidConnectionMode::Proxy
        ),
        None
    );
    assert_eq!(
        health.observe_line(
            "peer connection state changed: closed",
            9_000,
            true,
            AndroidConnectionMode::Proxy
        ),
        Some(RecoveryRequest {
            reason: "RTC closed".into(),
            full_restart: false
        })
    );
    assert_eq!(
        health.observe_line(
            "network is unreachable",
            20_000,
            false,
            AndroidConnectionMode::Tun
        ),
        None
    );
}

#[test]
fn tun_stall_needs_three_busy_no_reply_samples() {
    let stats = |tx, rx| Tun2SocksStats {
        tx_packets: tx,
        rx_packets: rx,
        ..Default::default()
    };
    let mut detector = TunStallDetector::default();
    assert!(!detector.observe(Some(stats(0, 0)), true));
    assert!(!detector.observe(Some(stats(8, 0)), true));
    assert!(!detector.observe(Some(stats(16, 0)), true));
    assert!(detector.observe(Some(stats(24, 0)), true));
    assert!(!detector.observe(Some(stats(32, 1)), true));
    assert!(!detector.observe(None, true));
    detector.reset();
    assert!(!detector.observe(Some(stats(100, 0)), true));
}

#[test]
fn speed_and_retry_floor_resets_and_cap() {
    let previous = Tun2SocksStats {
        tx_bytes: 100,
        rx_bytes: 200,
        ..Default::default()
    };
    let current = Tun2SocksStats {
        tx_bytes: 300,
        rx_bytes: 600,
        ..Default::default()
    };
    assert_eq!(
        android_speed_sample(previous, current, 2_000),
        AndroidSpeedSample {
            down_bytes_per_sec: 200,
            up_bytes_per_sec: 100
        }
    );
    assert_eq!(
        android_speed_sample(current, previous, 0),
        AndroidSpeedSample::default()
    );
    let mut meter = AndroidSpeedMeter::default();
    assert_eq!(meter.tick(Some(previous), 2_000), None);
    assert_eq!(
        meter.tick(Some(current), 2_000).unwrap().down_bytes_per_sec,
        200
    );
    meter.reset();
    assert_eq!(meter.tick(Some(current), 2_000), None);

    let mut backoff = ReconnectBackoff::default();
    assert_eq!(
        (0..5)
            .map(|_| backoff.next_delay_ms(4_000))
            .collect::<Vec<_>>(),
        vec![4_000, 8_000, 16_000, 30_000, 30_000]
    );
    assert_eq!(backoff.attempt(), 5);
    backoff.reset();
    assert_eq!(backoff.next_delay_ms(8_000), 8_000);
}

#[test]
fn runtime_status_clock_and_port_decisions() {
    let tun_up = AndroidRuntimePresence {
        vpn_interface: true,
        tun2socks_alive: true,
        ..Default::default()
    };
    assert!(can_reconnect_transport_in_place(
        AndroidConnectionMode::Tun,
        tun_up
    ));
    assert!(!can_reconnect_transport_in_place(
        AndroidConnectionMode::Tun,
        AndroidRuntimePresence::default()
    ));
    let core_up = AndroidRuntimePresence {
        core_running: true,
        ..Default::default()
    };
    assert!(can_reconnect_transport_in_place(
        AndroidConnectionMode::Proxy,
        core_up
    ));
    assert!(!should_restart_for_start_command(
        &VpnStatus::Error("x".into()),
        AndroidRuntimePresence::default()
    ));
    assert!(should_restart_for_start_command(
        &VpnStatus::Error("x".into()),
        core_up
    ));
    assert!(should_restart_for_start_command(
        &VpnStatus::Stopping,
        AndroidRuntimePresence::default()
    ));

    let connected = android_status_effects(&VpnStatus::Connected, 0, 9_000);
    assert!(connected.connected);
    assert_eq!(connected.connected_since_ms, 9_000);
    let reconnecting = android_status_effects(&VpnStatus::Reconnecting, 9_000, 12_000);
    assert_eq!(reconnecting.connected_since_ms, 9_000);
    assert!(!reconnecting.reset_speed);
    let error = android_status_effects(&VpnStatus::Error("temporary".into()), 9_000, 12_000);
    assert_eq!(error.connected_since_ms, 9_000);
    let stopped = android_status_effects(&VpnStatus::Disconnected, 9_000, 12_000);
    assert_eq!(stopped.connected_since_ms, 0);
    assert!(stopped.reset_speed);

    assert_eq!(
        AndroidPortPlan::from_socks(10_808),
        AndroidPortPlan {
            socks: 10_808,
            chain_olcrtc: 10_809,
            amneziawg: 10_810,
            warp_relay: 10_811,
            tproxy: 10_812,
            http_proxy: 10_812,
            trust_tunnel: 10_813,
            sing_box_front: 10_814,
        }
    );
}
