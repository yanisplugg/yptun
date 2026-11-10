//! Ported from WarpConfigDumpTest and WarpRawConfigTest: the four WARP combinations (client/server ×
//! WG/AWG) for both cores, and WARP over a JSON subscription (verbatim Xray config). When
//! `WARP_DUMP_DIR` is set the configs are also written there, so they can be fed to a real
//! `sing-box check` / `xray -test`.

use serde_json::Value;
use yptun_core::model::*;
use yptun_core::singbox::config::{self as singbox, SingBoxOptions, WarpRelay};
use yptun_core::warp;
use yptun_core::xray::config::{self as xray, RawOptions, XrayOptions};

const INI: &str = "[Interface]
PrivateKey = YFPiOJCBPN8R4H2zXlGdfIcHq8Z0y1vC2m5qL7sZ2G4=
Address = 172.16.0.2/32, 2606:4700:110:8c45:519f:f2ca:bcc5:227c/128
MTU = 1280
[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = engage.cloudflareclient.com:2408";

fn vless() -> ProxyProfile {
    ProxyProfile {
        tag: "main".into(),
        kind: TYPE_VLESS.into(),
        server: "127.0.0.1".into(),
        server_port: 18991,
        uuid: "11111111-2222-3333-4444-555555555555".into(),
        flow: "xtls-rprx-vision".into(),
        ..Default::default()
    }
}

/// awgproxy's local SOCKS (the AWG flavour of WARP).
fn local_socks(port: i32) -> ProxyProfile {
    ProxyProfile {
        tag: "WARP".into(),
        kind: "socks".into(),
        server: "127.0.0.1".into(),
        server_port: port,
        raw_outbound: Some(format!(
            r#"{{"type":"socks","server":"127.0.0.1","server_port":{port},"version":"5"}}"#
        )),
        ..Default::default()
    }
}

fn relay(target_host: &str) -> WarpRelay {
    WarpRelay {
        listen_port: 18993,
        target_host: target_host.into(),
        target_port: 2408,
    }
}

/// Writes the config to `WARP_DUMP_DIR` (when set) and checks it is valid JSON.
fn dump(name: &str, text: &str) -> Value {
    if let Ok(dir) = std::env::var("WARP_DUMP_DIR") {
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            std::path::Path::new(&dir).join(format!("{name}.json")),
            text,
        )
        .unwrap();
    }
    serde_json::from_str(text).expect("must be valid JSON")
}

fn tagged<'a>(o: &'a Value, list: &str, tag: &str) -> &'a Value {
    o[list]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["tag"] == tag)
        .unwrap()
}

fn tags(o: &Value) -> Vec<&str> {
    o["outbounds"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|v| v["tag"].as_str())
        .collect()
}

fn sing_box(o: SingBoxOptions) -> String {
    singbox::build(
        &vless(),
        &SingBoxOptions {
            listen_port: 18990,
            ..o
        },
    )
    .unwrap()
}

fn xray(o: XrayOptions) -> String {
    xray::build(
        &vless(),
        &XrayOptions {
            listen_port: 18990,
            ..o
        },
    )
}

#[test]
fn wg_plain_endpoint_helpers() {
    let (host, port) = warp::endpoint_of(INI);
    assert_eq!((host.as_str(), port), ("engage.cloudflareclient.com", 2408));
    assert!(warp::with_endpoint(INI, "127.0.0.1:18993").contains("Endpoint = 127.0.0.1:18993"));
}

#[test]
fn sing_box_combinations() {
    let wg = warp::parse_warp_ini(INI, "WARP").unwrap();
    // client + WG
    let c_wg = sing_box(SingBoxOptions {
        wireguard_base: Some(wg.clone()),
        ..Default::default()
    });
    dump("sb-client-wg", &c_wg);
    assert!(c_wg.contains("\"wireguard-base\""));
    // server + WG
    dump(
        "sb-server-wg",
        &sing_box(SingBoxOptions {
            second_profile: Some(wg),
            ..Default::default()
        }),
    );
    // client + AWG (main dials through awgproxy's SOCKS)
    dump(
        "sb-client-awg",
        &sing_box(SingBoxOptions {
            olcrtc_chain_port: Some(18995),
            ..Default::default()
        }),
    );
    // server + AWG (exit = awgproxy SOCKS, relay inbound → main)
    let s_awg = sing_box(SingBoxOptions {
        second_profile: Some(local_socks(18995)),
        warp_relay: Some(relay("engage.cloudflareclient.com")),
        ..Default::default()
    });
    let o = dump("sb-server-awg", &s_awg);
    assert_eq!(
        tagged(&o, "inbounds", "warp-relay-in")["listen_port"],
        18993
    );
    assert_eq!(o["route"]["rules"][0]["outbound"], "proxy-base");
    let tags = tags(&o);
    assert!(
        tags.contains(&"proxy") && tags.contains(&"proxy-base"),
        "{tags:?}"
    );
}

#[test]
fn xray_combinations() {
    let wg = warp::parse_warp_ini(INI, "WARP").unwrap();
    let c_wg = xray(XrayOptions {
        wireguard_base: Some(wg.clone()),
        chain_via_dialer_proxy: true,
        keep_flow_on_chain: true,
        ..Default::default()
    });
    dump("xr-client-wg", &c_wg);
    assert!(
        c_wg.contains("xtls-rprx-vision"),
        "Vision flow must survive the WARP chain"
    );
    dump(
        "xr-server-wg",
        &xray(XrayOptions {
            second_profile: Some(wg),
            ..Default::default()
        }),
    );
    let c_awg = xray(XrayOptions {
        olcrtc_chain_port: Some(18995),
        chain_via_dialer_proxy: true,
        keep_flow_on_chain: true,
        ..Default::default()
    });
    dump("xr-client-awg", &c_awg);
    assert!(c_awg.contains("xtls-rprx-vision"));
    let s_awg = xray(XrayOptions {
        second_profile: Some(local_socks(18995)),
        warp_relay: Some(relay("engage.cloudflareclient.com")),
        ..Default::default()
    });
    let o = dump("xr-server-awg", &s_awg);
    assert_eq!(
        tagged(&o, "inbounds", "warp-relay-in")["protocol"],
        "dokodemo-door"
    );
    let rules = o["routing"]["rules"].as_array().unwrap();
    assert!(rules.iter().any(|r| r["inboundTag"]
        .as_array()
        .is_some_and(|t| t.iter().any(|t| t == "warp-relay-in"))));
}

// ---------------- WARP over a verbatim Xray config ----------------

fn raw(server: &str, port: i32, flow: &str) -> String {
    r#"{
  "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
  "outbounds": [
    { "tag": "proxy", "protocol": "vless",
      "settings": { "vnext": [{ "address": "$SERVER", "port": $PORT,
        "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" $FLOW }] }] },
      "streamSettings": { "network": "tcp", "security": "none", "sockopt": { "mark": 255 } } },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": { "domainStrategy": "AsIs", "rules": [
    { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
    { "type": "field", "network": "tcp,udp", "outboundTag": "proxy" } ] }
}"#
    .replace("$SERVER", server)
    .replace("$PORT", &port.to_string())
    .replace("$FLOW", flow)
}

fn balanced_raw() -> &'static str {
    r#"{
  "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
  "outbounds": [
    { "tag": "proxy-a", "protocol": "vless",
      "settings": { "vnext": [{ "address": "a.example", "port": 443,
        "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
      "streamSettings": { "network": "tcp", "security": "tls" } },
    { "tag": "proxy-b", "protocol": "trojan",
      "settings": { "servers": [{ "address": "b.example", "port": 443, "password": "secret" }] },
      "streamSettings": { "network": "tcp", "security": "tls" } },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "balancers": [{ "tag": "proxy-balance", "selector": ["proxy-"], "fallbackTag": "proxy-a",
      "strategy": { "type": "roundRobin" } }],
    "rules": [
      { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
      { "type": "field", "network": "tcp,udp", "balancerTag": "proxy-balance" }
    ]
  }
}"#
}

/// A panel subscription whose routing balancer selects intermediate chain outbounds rather than the
/// typed VLESS/Trojan nodes directly.
fn chained_balanced_raw() -> &'static str {
    r#"{
  "inbounds": [{ "tag": "socks", "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
  "outbounds": [
    { "tag": "node-a", "protocol": "vless",
      "settings": { "vnext": [{ "address": "a.example", "port": 443,
        "users": [{ "id": "11111111-2222-3333-4444-555555555555", "encryption": "none" }] }] },
      "streamSettings": { "network": "tcp", "security": "tls" } },
    { "tag": "node-b", "protocol": "trojan",
      "settings": { "servers": [{ "address": "b.example", "port": 443, "password": "secret" }] },
      "streamSettings": { "network": "tcp", "security": "tls" } },
    { "tag": "chain-a", "protocol": "freedom", "proxySettings": { "tag": "node-a" } },
    { "tag": "chain-b", "protocol": "freedom", "proxySettings": { "tag": "node-b" } },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "balancers": [
      { "tag": "node-balance", "selector": ["node-"],
        "strategy": { "type": "roundRobin" } },
      { "tag": "subscription-balance", "selector": ["chain-"],
        "strategy": { "type": "roundRobin" } }
    ],
    "rules": [
      { "type": "field", "domain": ["domain:example.com"], "balancerTag": "node-balance" },
      { "type": "field", "domain": ["domain:ru"], "outboundTag": "direct" },
      { "type": "field", "network": "tcp,udp", "balancerTag": "subscription-balance" }
    ]
  }
}"#
}

#[test]
fn client_side_every_proxy_outbound_dials_through_the_warp_socks() {
    let json = xray::prepare_raw(
        &raw("127.0.0.1", 18991, r#","flow":"xtls-rprx-vision""#),
        &RawOptions {
            warp_chain_port: Some(18900),
            ..RawOptions::new(18081)
        },
    );
    let o = dump("raw-client", &json);
    let proxy = tagged(&o, "outbounds", "proxy");
    let sockopt = &proxy["streamSettings"]["sockopt"];
    assert_eq!(sockopt["dialerProxy"], "warp-out");
    assert_eq!(sockopt["mark"], 255, "the config's own sockopt is kept");
    assert!(
        proxy.to_string().contains("xtls-rprx-vision"),
        "the Vision flow survives"
    );
    assert_eq!(tagged(&o, "outbounds", "warp-out")["protocol"], "socks");
    // The default exit (the first outbound) stays the config's own proxy; the WARP SOCKS is never it.
    let tags = tags(&o);
    assert_eq!(tags.first(), Some(&"proxy"));
    assert_eq!(tags.last(), Some(&"warp-out"));
    let direct = tagged(&o, "outbounds", "direct");
    assert!(
        !direct["streamSettings"].to_string().contains("dialerProxy"),
        "direct is not chained"
    );
}

#[test]
fn server_side_awgproxy_is_the_exit_and_its_udp_rides_the_main_proxy() {
    let json = xray::prepare_raw(
        &raw("127.0.0.1", 18991, ""),
        &RawOptions {
            warp_exit_port: Some(18900),
            warp_relay: Some(relay("162.159.192.1")),
            ..RawOptions::new(18081)
        },
    );
    let o = dump("raw-server", &json);
    assert_eq!(o["outbounds"][0]["tag"], "cascade-exit");
    assert_eq!(
        tagged(&o, "inbounds", "warp-relay-in")["protocol"],
        "dokodemo-door"
    );
    let rules = o["routing"]["rules"].as_array().unwrap();
    assert_eq!(
        rules[0]["outboundTag"], "proxy",
        "relay rule goes to the real main proxy"
    );
    assert_eq!(rules[0]["inboundTag"], serde_json::json!(["warp-relay-in"]));
    assert_eq!(
        rules.last().unwrap()["outboundTag"],
        "cascade-exit",
        "everything else leaves through WARP"
    );
    assert_eq!(
        rules[1]["outboundTag"], "direct",
        "the config's direct rule stays"
    );
}

#[test]
fn client_side_warp_keeps_injected_outbound_out_of_raw_balancer() {
    let json = xray::prepare_raw(
        balanced_raw(),
        &RawOptions { warp_chain_port: Some(18900), ..RawOptions::new(18081) },
    );
    let o = dump("raw-balanced-client", &json);
    assert_eq!(tagged(&o, "outbounds", "proxy-a")["streamSettings"]["sockopt"]["dialerProxy"], "warp-out");
    assert_eq!(tagged(&o, "outbounds", "proxy-b")["streamSettings"]["sockopt"]["dialerProxy"], "warp-out");
    assert_eq!(o["routing"]["balancers"][0]["selector"], serde_json::json!(["proxy-a", "proxy-b"]));
    assert!(o["routing"]["balancers"][0].get("fallbackTag").is_none());
}

#[test]
fn server_side_warp_redirects_balanced_traffic_but_relays_through_balancer() {
    let json = xray::prepare_raw(
        balanced_raw(),
        &RawOptions {
            warp_exit_port: Some(18900),
            warp_relay: Some(relay("162.159.192.1")),
            ..RawOptions::new(18081)
        },
    );
    let o = dump("raw-balanced-server", &json);
    let rules = o["routing"]["rules"].as_array().unwrap();
    assert_eq!(rules[0]["balancerTag"], "proxy-balance");
    assert!(rules[0].get("outboundTag").is_none());
    assert_eq!(rules.last().unwrap()["outboundTag"], "cascade-exit");
    assert!(rules.last().unwrap().get("balancerTag").is_none());
    assert_eq!(rules[1]["outboundTag"], "direct");
    assert_eq!(o["routing"]["balancers"][0]["selector"], serde_json::json!(["proxy-a", "proxy-b"]));
    assert!(o["routing"]["balancers"][0].get("fallbackTag").is_none());
}

#[test]
fn server_side_warp_recognizes_balancer_over_subscription_chain_outbounds() {
    let json = xray::prepare_raw(
        chained_balanced_raw(),
        &RawOptions {
            warp_exit_port: Some(18900),
            warp_relay: Some(relay("162.159.192.1")),
            ..RawOptions::new(18081)
        },
    );
    let o = dump("raw-subscription-balanced-server", &json);
    let rules = o["routing"]["rules"].as_array().unwrap();
    assert_eq!(rules[0]["balancerTag"], "subscription-balance");
    assert_eq!(rules[1]["outboundTag"], "cascade-exit");
    assert_eq!(rules[2]["outboundTag"], "direct");
    assert_eq!(rules.last().unwrap()["outboundTag"], "cascade-exit");
    assert!(rules.last().unwrap().get("balancerTag").is_none());
    assert_eq!(o["routing"]["balancers"][1]["selector"], serde_json::json!(["chain-a", "chain-b"]));
}
