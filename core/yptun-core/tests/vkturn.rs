//! Ported from VkTurnMtuClampTest plus compose/decompose round-trips of the VK-TURN editor.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use yptun_core::model::*;
use yptun_core::panel::parse_freeturn;
use yptun_core::transports::*;
use yptun_core::vkturn::*;

fn wg_outbound(mtu: Option<i32>) -> String {
    let m = mtu.map(|m| format!(",\"mtu\":{m}")).unwrap_or_default();
    format!(r#"{{"type":"wireguard","server":"127.0.0.1","server_port":9000,"local_address":["10.7.1.2/32"],"private_key":"priv","peer_public_key":"pub"{m}}}"#)
}

fn mtu_of(p: &ProxyProfile) -> i64 {
    serde_json::from_str::<serde_json::Value>(p.raw_outbound.as_deref().unwrap()).unwrap()["mtu"].as_i64().unwrap()
}

fn wg(raw: String) -> ProxyProfile {
    ProxyProfile { kind: "wireguard".into(), raw_outbound: Some(raw), ..Default::default() }
}

#[test]
fn wireguard_mtu_clamp() {
    assert_eq!(mtu_of(&clamp_vkturn_mtu(&wg(wg_outbound(Some(1420))), VKTURN_MAX_WG_MTU)), 1200);
    assert_eq!(mtu_of(&clamp_vkturn_mtu(&wg(wg_outbound(Some(1000))), VKTURN_MAX_WG_MTU)), 1000);
    let filled = clamp_vkturn_mtu(&wg(wg_outbound(None)), VKTURN_MAX_WG_MTU);
    assert_eq!(mtu_of(&filled), 1200);
    let raw = filled.raw_outbound.unwrap();
    assert!(raw.contains(r#""peer_public_key":"pub""#) && raw.contains(r#""local_address":["10.7.1.2/32"]"#), "{raw}");
    assert!(raw.starts_with(r#"{"type":"wireguard""#));
}

#[test]
fn amneziawg_ini_clamp() {
    let ini = "[Interface]\nPrivateKey = priv\nAddress = 10.7.1.2/32\nDNS = 1.1.1.1\nMTU = 1280\nJc = 4\n\n[Peer]\nPublicKey = pub\nEndpoint = 127.0.0.1:9000\nAllowedIPs = 0.0.0.0/0";
    let c = clamp_vkturn_mtu(&ProxyProfile { kind: TYPE_AMNEZIAWG.into(), awg_config: ini.into(), ..Default::default() }, VKTURN_MAX_WG_MTU);
    assert!(c.awg_config.contains("MTU = 1200") && c.awg_config.contains("Jc = 4") && c.awg_config.contains("Endpoint = 127.0.0.1:9000"));
    let bare = "[Interface]\nPrivateKey = priv\nAddress = 10.7.1.2/32\n\n[Peer]\nPublicKey = pub\n";
    let c = clamp_vkturn_mtu(&ProxyProfile { kind: TYPE_AMNEZIAWG.into(), awg_config: bare.into(), ..Default::default() }, VKTURN_MAX_WG_MTU);
    assert!(c.awg_config.starts_with("[Interface]\nMTU = 1200\n"));
    assert_eq!(clamp_wg_conf_mtu("[Interface]\nMTU = 1000", 1200), None);
}

#[test]
fn proxy_exit_is_untouched() {
    let p = ProxyProfile { kind: TYPE_VLESS.into(), server: "example.com".into(), server_port: 443, uuid: "u".into(), ..Default::default() };
    assert_eq!(clamp_vkturn_mtu(&p, VKTURN_MAX_WG_MTU), p);
}

fn draft() -> VkTurnDraft {
    VkTurnDraft {
        peer_host: "203.0.113.7".into(),
        peer_port: "56000".into(),
        obf_key: "deadbeef".into(),
        comment: "Demo".into(),
        vk_link: "https://vk.com/call/join/AAA".into(),
        wg_private_key: "PRIV=".into(),
        wg_peer_public_key: "PUB=".into(),
        wg_mtu: "1200".into(),
        ..Default::default()
    }
}

#[test]
fn wireguard_draft_round_trips() {
    let (vk, proxy) = compose(&draft(), "Demo VK");
    assert!(vk.uri.starts_with("freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg="), "{}", vk.uri);
    assert!(vk.uri.ends_with("@203.0.113.7:56000#deadbeef$Demo"));
    let raw: serde_json::Value = serde_json::from_str(proxy.raw_outbound.as_deref().unwrap()).unwrap();
    assert_eq!((raw["server"].as_str(), raw["server_port"].as_i64(), raw["mtu"].as_i64()), (Some("127.0.0.1"), Some(9000), Some(1200)));
    assert_eq!(proxy.tag, "Demo VK");

    // The panel parser reads our link the same way.
    let p = parse_freeturn(&vk.uri).unwrap();
    assert_eq!((p.server_ip.as_str(), p.server_port, p.listen_port), ("203.0.113.7", 56000, 9000));

    let back = decompose(Some(&vk), Some(&proxy));
    assert_eq!(back, VkTurnDraft { streams: "10".into(), ..draft() });
}

#[test]
fn amneziawg_and_proxy_exits() {
    let awg = VkTurnDraft { outbound: OUTBOUND_AMNEZIAWG.into(), awg_jc: "7".into(), wg_dns: "9.9.9.9".into(), ..draft() };
    let (vk, proxy) = compose(&awg, "A");
    assert_eq!(proxy.kind, TYPE_AMNEZIAWG);
    assert!(proxy.awg_config.contains("Jc = 7") && proxy.awg_config.contains("Endpoint = 127.0.0.1:9000"));
    let back = decompose(Some(&vk), Some(&proxy));
    assert_eq!((back.awg_jc.as_str(), back.wg_dns.as_str(), back.wg_private_key.as_str()), ("7", "9.9.9.9", "PRIV="));

    let px = VkTurnDraft { outbound: OUTBOUND_PROXY.into(), outbound_proxy_link: "vless://u@exit.example:443?security=tls&type=tcp#X".into(), ..draft() };
    let (vk, proxy) = compose(&px, "");
    assert!(vk.uri.contains("<mode=tcp&obf-profile=rtpopus>") && !vk.uri.contains("wg="), "{}", vk.uri);
    assert_eq!((proxy.server.as_str(), proxy.server_port, proxy.sni.as_str(), proxy.tag.as_str()), ("127.0.0.1", 9000, "exit.example", "X"));
    let back = decompose(Some(&vk), Some(&proxy));
    assert_eq!((back.peer_host.as_str(), back.outbound_proxy_link.as_str()), ("203.0.113.7", px.outbound_proxy_link.as_str()));
}

#[test]
fn extra_server_becomes_a_wireguard_profile() {
    let conf = "[Interface]\nPrivateKey = K=\nAddress = 10.7.3.2/32\nMTU = 1280\n\n[Peer]\nPublicKey = P=\nEndpoint = 127.0.0.1:9000\n";
    let uri = format!("freeturn://vk?tcp<mode=udp&wg={}>@198.51.100.2:56000$Second", URL_SAFE_NO_PAD.encode(conf));
    let p = freeturn_uri_to_wg_profile(&uri, 9101, "S2").unwrap();
    let raw: serde_json::Value = serde_json::from_str(p.raw_outbound.as_deref().unwrap()).unwrap();
    assert_eq!((raw["server_port"].as_i64(), raw["private_key"].as_str(), raw["local_address"][0].as_str()), (Some(9101), Some("K="), Some("10.7.3.2/32")));
    assert_eq!(p.server, "198.51.100.2");
    assert!(freeturn_uri_to_wg_profile("freeturn://vk?tcp<mode=tcp>@1.2.3.4:5", 9101, "x").is_none());
    assert!(freeturn_uri_to_wg_profile("vless://x", 9101, "x").is_none());
}

#[test]
fn extra_uris_and_cores() {
    let d = VkTurnDraft {
        extra_freeturn_uris: "freeturn://a\n  junk \nFREETURN://b".into(),
        core: CORE_CSQTT.into(),
        csqtt_port: "70000".into(),
        csqtt_workers: "-1".into(),
        ..draft()
    };
    let (vk, _) = compose(&d, "x");
    assert_eq!(vk.extra_freeturn_uris, ["freeturn://a", "FREETURN://b"]);
    assert_eq!((vk.core.as_str(), vk.csqtt_port, vk.csqtt_workers), (CORE_CSQTT, 0, 0));
}
