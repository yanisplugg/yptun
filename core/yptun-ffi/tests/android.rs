use yptun_ffi::*;

fn start(mode: AndroidMode) -> AndroidStartConfig {
    AndroidStartConfig {
        connection_mode: mode,
        socks_listen_host: "127.0.0.1".into(),
        socks_listen_port: 10_808,
        socks_username: String::new(),
        socks_password: String::new(),
        split_tunnel_mode: AndroidSplitMode::AllApps,
        split_tunnel_proxy_apps: Vec::new(),
        split_tunnel_bypass_apps: Vec::new(),
    }
}

fn stats(tx_packets: i64, tx_bytes: i64, rx_packets: i64, rx_bytes: i64) -> AndroidTunStats {
    AndroidTunStats {
        tx_packets,
        tx_bytes,
        rx_packets,
        rx_bytes,
    }
}

#[test]
fn start_split_and_ports_cross_ffi() {
    let tun = android_prepare_start(start(AndroidMode::Tun)).unwrap();
    assert_eq!(tun.connection_mode, AndroidMode::Tun);
    assert_eq!(tun.socks_username.len(), 36);
    assert_eq!(tun.socks_password.len(), 36);

    let mut proxy = start(AndroidMode::Proxy);
    proxy.socks_username = "ignored".into();
    proxy.socks_password = "ignored".into();
    let proxy = android_prepare_start(proxy).unwrap();
    assert_eq!(proxy.socks_listen_host, "0.0.0.0");
    assert!(proxy.socks_username.is_empty() && proxy.socks_password.is_empty());

    let plan = android_split_tunnel_plan(
        AndroidSplitMode::ProxySelected,
        "org.yptun.app".into(),
        vec!["b".into(), "a".into(), "a".into(), "org.yptun.app".into()],
        Vec::new(),
    )
    .unwrap();
    assert_eq!(plan.allowed_packages, vec!["a", "b"]);
    assert!(plan.require_one_allowed);
    assert!(android_split_tunnel_plan(
        AndroidSplitMode::ProxySelected,
        "org.yptun.app".into(),
        Vec::new(),
        Vec::new(),
    )
    .is_err());

    let ports = android_port_plan(10_808);
    assert_eq!(ports.chain_olcrtc, 10_809);
    assert_eq!(ports.tproxy, 10_812);
    assert_eq!(ports.sing_box_front, 10_814);
}

#[test]
fn tunnel_yaml_and_dns_cross_ffi() {
    let plan = android_tunnel_plan(1_280, true);
    assert_eq!(plan.mtu, 1_280);
    assert!(plan
        .addresses
        .iter()
        .any(|a| a.address == "10.0.88.88" && a.prefix == 24));
    assert!(plan
        .routes
        .iter()
        .any(|r| r.address == "::" && r.prefix == 0));
    assert!(!plan
        .routes
        .iter()
        .any(|r| r.address == "0.0.0.0" && r.prefix == 0));

    let yaml = android_tun2socks_yaml(AndroidTun2SocksConfig {
        mtu: 1_280,
        drop_ipv6: true,
        socks_listen_host: "0.0.0.0".into(),
        socks_listen_port: 10_808,
        socks_username: "u".into(),
        socks_password: "p".into(),
        engine: AndroidBridgeEngine::OpenFlux,
    });
    assert!(yaml.contains("address: 127.0.0.1"));
    assert!(yaml.contains("udp: 'tcp'"));
    assert!(yaml.contains("connect-timeout: 30000"));
    assert!(yaml.contains("# ipv6 disabled"));

    assert_eq!(
        android_olcrtc_dns(Some("2001:db8::1".into())),
        "[2001:db8::1]:53,77.88.8.8:53,8.8.8.8:53,1.1.1.1:53"
    );
}

#[test]
fn tproxy_and_vk_helpers_cross_ffi() {
    let script = android_tproxy_apply_script(1001, 10_812);
    assert!(script.contains("--uid-owner 1001"));
    assert!(script.contains("--on-port 10812"));
    assert!(android_tproxy_teardown_script().contains("lookup 2026 pref 90"));
    assert_eq!(android_tproxy_failure(None, "YPT_OK".into()), None);
    assert!(android_tproxy_failure(Some("denied".into()), String::new())
        .unwrap()
        .contains("denied"));

    assert_eq!(android_vkturn_streams("a,b,c".into(), 0), 12);
    assert_eq!(
        android_partition_vk_links("a,b,c,d,e".into(), 3),
        vec!["a\nd", "b\ne", "c"]
    );
}

#[test]
fn stateful_health_stats_and_backoff_cross_ffi() {
    let runtime = AndroidVpnRuntime::new(1_000);
    assert_eq!(
        runtime.observe_rtc_line(
            "ICE connection state changed: failed".into(),
            4_000,
            true,
            AndroidMode::Tun,
        ),
        Some(AndroidRecovery {
            reason: "RTC failed".into(),
            full_restart: true
        })
    );

    assert!(!runtime.observe_tun_stats(Some(stats(0, 0, 0, 0)), true));
    assert!(!runtime.observe_tun_stats(Some(stats(8, 10, 0, 0)), true));
    assert!(!runtime.observe_tun_stats(Some(stats(16, 20, 0, 0)), true));
    assert!(runtime.observe_tun_stats(Some(stats(24, 30, 0, 0)), true));

    assert_eq!(runtime.speed_tick(Some(stats(0, 100, 0, 200)), 2_000), None);
    assert_eq!(
        runtime.speed_tick(Some(stats(0, 300, 0, 600)), 2_000),
        Some(AndroidSpeed {
            down_bytes_per_sec: 200,
            up_bytes_per_sec: 100
        })
    );
    assert_eq!(runtime.next_retry_delay_ms(4_000), 4_000);
    assert_eq!(runtime.next_retry_delay_ms(4_000), 8_000);
    runtime.reset_recovery(10_000);
    assert_eq!(runtime.next_retry_delay_ms(4_000), 4_000);
    assert_eq!(runtime.speed_tick(Some(stats(0, 900, 0, 900)), 2_000), None);
}

#[test]
fn runtime_and_status_decisions_cross_ffi() {
    let tun = AndroidRuntimeSnapshot {
        vpn_interface: true,
        tun2socks_thread: true,
        tun2socks_alive: true,
        ..Default::default()
    };
    assert!(android_can_reconnect_in_place(AndroidMode::Tun, tun));
    assert!(android_should_restart(AndroidStatusKind::Error, tun));
    assert!(!android_should_restart(
        AndroidStatusKind::Error,
        AndroidRuntimeSnapshot::default()
    ));

    let connected = android_status_effects(AndroidStatusKind::Connected, 0, 42);
    assert_eq!(connected.connected_since_ms, 42);
    assert!(connected.connected);
    let error = android_status_effects(AndroidStatusKind::Error, 42, 100);
    assert_eq!(error.connected_since_ms, 42);
    assert!(!error.reset_speed);
    let stopped = android_status_effects(AndroidStatusKind::Stopping, 42, 100);
    assert_eq!(stopped.connected_since_ms, 0);
    assert!(stopped.reset_speed);
}
