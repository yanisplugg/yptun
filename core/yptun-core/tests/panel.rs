//! Ported from commonTest: CsqttUriParserTest, FreeturnUriParserTest, QwdttUriParserTest,
//! OpenFluxUriParserTest, YptunInboundCodecTest, plus the olcRTC panel links.

use base64::engine::general_purpose::{URL_SAFE, URL_SAFE_NO_PAD};
use base64::Engine;
use yptun_core::location::*;
use yptun_core::model::*;
use yptun_core::panel::*;
use yptun_core::transports::*;

// ---------------- csqtt ----------------

#[test]
fn csqtt_web_panel_link_with_hashes() {
    let l = parse_csqtt("csqtt://connect?v=2&host=203.0.113.7&peer=46000&password=p%40ss&hashes=aaa+bbb+ccc").unwrap();
    assert_eq!((l.host.as_str(), l.port, l.password.as_str()), ("203.0.113.7", 46000, "p@ss"));
    assert_eq!(l.hashes, "aaa\nbbb\nccc");
}

#[test]
fn csqtt_without_hashes_and_html_escaped_ampersands() {
    let l = parse_csqtt("csqtt://connect?v=2&amp;host=vpn.example.com&amp;peer=47000&amp;password=secret").unwrap();
    assert_eq!((l.host.as_str(), l.port, l.hashes.as_str()), ("vpn.example.com", 47000, ""));
}

#[test]
fn csqtt_legacy_link() {
    let l = parse_csqtt("csqtt://secret@203.0.113.7:46001").unwrap();
    assert_eq!((l.host.as_str(), l.port, l.password.as_str()), ("203.0.113.7", 46001, "secret"));
}

#[test]
fn csqtt_rejects_incomplete_and_foreign() {
    assert!(parse_csqtt("csqtt://connect?v=2&host=h&peer=46000").is_none());
    assert!(parse_csqtt("csqtt://connect?v=1&host=h&peer=46000&password=p").is_none());
    assert!(parse_csqtt("csqtt://connect?v=2&host=h&peer=99999&password=p").is_none());
    assert!(parse_csqtt("csqtt://connect?v=2&host=h&peer=46000&password=p&hashes=").is_none());
    assert!(parse_csqtt("qwdtt://config?peer=h&pass=p").is_none());
}

#[test]
fn csqtt_compose_round_trips() {
    let vk = VkTurnConfig {
        core: CORE_CSQTT.into(),
        csqtt_peer: "203.0.113.7".into(),
        csqtt_port: 46005,
        csqtt_password: "p@ss_word".into(),
        vk_link: "aaa\nbbb".into(),
        ..Default::default()
    };
    let back = parse_csqtt(&compose_csqtt(&vk, "")).unwrap();
    assert_eq!((back.host.as_str(), back.port, back.password.as_str(), back.hashes.as_str()), ("203.0.113.7", 46005, "p@ss_word", "aaa\nbbb"));
    assert_eq!(vk.csqtt_peer_addr(), "203.0.113.7:46005");
    let named = VkTurnConfig { csqtt_peer: "h".into(), csqtt_port: 46000, csqtt_password: "p".into(), vk_link: "aaa".into(), ..vk };
    assert_eq!(parse_csqtt(&compose_csqtt(&named, "Ams ter")).unwrap().name, "Ams ter");
}

#[test]
fn csqtt_name_from_fragment() {
    let l = parse_csqtt("csqtt://connect?v=2&host=h&peer=46000&password=p&hashes=aaa#%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B").unwrap();
    assert_eq!(l.name, "Нидерланды");
    assert_eq!(l.hashes, "aaa");
    assert_eq!(parse_csqtt("csqtt://secret@203.0.113.7:46001#My%20node").unwrap().name, "My node");
    assert_eq!(parse_csqtt("csqtt://secret@203.0.113.7:46001").unwrap().name, "");
}

#[test]
fn csqtt_encrypted_2_5_link() {
    let l = parse_csqtt(
        "csqtt://iitgKpTQ2GayAklkGYsgdXkcXxGJ7_0RoL0yyMNXLQIbfge3SJQ21_IgLVnthuji1tDjb59PZiIyEuio0cj7is_JC8nmvNnEjv_xn_mD86YMLOrl6bS7oHt9Re6aRig7#%D0%A2",
    )
    .unwrap();
    assert_eq!((l.host.as_str(), l.port, l.password.as_str(), l.hashes.as_str(), l.name.as_str()), ("1.2.3.4", 46000, "testpass", "aaa\nbbb", "Т"));
    assert!(parse_csqtt("csqtt://AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").is_none());
}

#[test]
fn csqtt_core_options_carry_everything() {
    let vk = VkTurnConfig { core: CORE_CSQTT.into(), csqtt_peer: "203.0.113.7".into(), csqtt_password: "pw".into(), vk_link: "aaa\nbbb ccc,ddd".into(), ..Default::default() };
    let j: serde_json::Value = serde_json::from_str(&vk.csqtt_core_options_json("/lib/libcsqtt.so", "127.0.0.1:10810", "dev1")).unwrap();
    assert_eq!(j["client"], "/lib/libcsqtt.so");
    assert_eq!(j["listen"], "127.0.0.1:10810");
    assert_eq!(j["peer"], "203.0.113.7:46000");
    assert_eq!(j["vk_hashes"], "aaa,bbb,ccc,ddd");
    assert_eq!(j["workers"], 72); // 4 hashes × 27, capped at 72
    assert_eq!(j["obfs"], "video");
    assert_eq!(j["fingerprint"], "firefox");
    assert_eq!(j["vk_auth_mode"], "vkcalls");
    assert_eq!(j["device_id"], "dev1");
}

// ---------------- freeturn ----------------

const WG_CONF: &str = "[Interface]\nPrivateKey = QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMjM0NTY3OD0=\nAddress = 10.7.3.2/32\nDNS = 1.1.1.1\nMTU = 1280\n\n[Peer]\nPublicKey = cGVlcl9wdWJsaWNfa2V5X2Jhc2U2NF8zMl9ieXRlc19vaz0=\nEndpoint = 127.0.0.1:9000\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25";

fn freeturn_link() -> String {
    let wg = URL_SAFE_NO_PAD.encode(WG_CONF);
    format!("freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg={wg}>@203.0.113.7:56000#deadbeef$Demo VK-TURN")
}

#[test]
fn freeturn_peer_and_embedded_wireguard() {
    let p = parse_freeturn(&freeturn_link()).unwrap();
    assert_eq!((p.server_ip.as_str(), p.server_port, p.listen_port), ("203.0.113.7", 56000, 9000));
    assert_eq!(p.comment, "Demo VK-TURN");
    let wg = &p.wg_outbound_json;
    for needle in [r#""type":"wireguard""#, r#""server":"127.0.0.1""#, r#""server_port":9000"#, "10.7.3.2/32", r#""private_key""#, r#""peer_public_key""#, r#""mtu":1280"#] {
        assert!(wg.contains(needle), "{wg}");
    }
}

#[test]
fn freeturn_vk_call_links_from_panel_link() {
    let calls = "https://vk.com/call/join/AAA\nhttps://vk.com/call/join/BBB";
    let with_vk = freeturn_link().replace("&wg=", &format!("&vk={}&wg=", URL_SAFE_NO_PAD.encode(calls)));
    assert_eq!(parse_freeturn(&with_vk).unwrap().vk_link, calls);
    assert_eq!(parse_freeturn(&freeturn_link()).unwrap().vk_link, "");
}

#[test]
fn freeturn_wire_format_with_cid() {
    let json = serde_json::json!({"v":1,"provider":"vk","peer":"203.0.113.7:56000","cid":"abc123","name":"W","wg": WG_CONF}).to_string();
    let p = parse_freeturn(&format!("freeturn://{}", URL_SAFE_NO_PAD.encode(json))).unwrap();
    assert_eq!((p.server_ip.as_str(), p.listen_port, p.comment.as_str()), ("203.0.113.7", 9000, "W"));
}

#[test]
fn freeturn_tcp_exit_proxy_and_rejections() {
    let exit = URL_SAFE_NO_PAD.encode("vless://u@exit.example:443?security=tls#exit");
    let p = parse_freeturn(&format!("freeturn://vk?tcp<mode=tcp&bond=1&vless={exit}>@203.0.113.9:56000#ab$Stealth DE-vk")).unwrap();
    assert_eq!((p.mode.as_str(), p.listen_port), ("tcp", 0));
    assert!(p.exit_proxy_link.starts_with("vless://"));
    assert!(parse_freeturn("vless://whatever@host:443").is_none());
    assert!(parse_freeturn("freeturn://vk?tcp<mode=udp>@203.0.113.7:56000$NoWG").is_none());
}

// ---------------- qWDTT ----------------

const QWDTT: &str = "qwdtt://config?name=qWDTT+-+Main+%2851.15.1.2%29&peer=51.15.1.2&hashes=ab12%2Ccd34&workers=9&port=9000&pass=p%40ss+word";

#[test]
fn qwdtt_server_quick_link() {
    let l = parse_qwdtt(QWDTT).unwrap();
    assert_eq!(l.name, "qWDTT - Main (51.15.1.2)");
    assert_eq!(l.peer, "51.15.1.2");
    assert_eq!(l.hashes, "ab12\ncd34");
    assert_eq!(l.password, "p@ss word");
    assert_eq!((l.listen_port, l.workers), (9000, 9));
    assert!(parse_qwdtt("freeturn://vk?<mode=udp>@1.2.3.4:5").is_none());
    assert!(parse_qwdtt("qwdtt://config?name=x&port=9000").is_none());
}

#[test]
fn qwdtt_compose_round_trips_and_is_connectable() {
    let l = parse_qwdtt(QWDTT).unwrap();
    let vk = VkTurnConfig {
        core: CORE_WDTT.into(),
        wdtt_peer: l.peer.clone(),
        wdtt_password: l.password.clone(),
        wdtt_workers: l.workers,
        vk_link: l.hashes.clone(),
        listen_port: l.listen_port,
        ..Default::default()
    };
    let cfg = LocationConfig { name: l.name.clone(), engine: EngineType::VkTurn, vkturn: Some(vk.clone()), ..Default::default() };
    assert!(cfg.is_storable() && vk.is_complete());
    assert_eq!(parse_qwdtt(&compose_qwdtt(&l.name, &vk)).unwrap(), l);
}

// ---------------- OpenFlux ----------------

fn of(json: &str) -> String {
    // The Kotlin test deflates with java.util.zip (zlib); both forms must import.
    let z = yptun_core::crypto::deflate(json.as_bytes()).unwrap();
    format!("openflux://v1/{}", URL_SAFE.encode(z).trim_end_matches('='))
}

#[test]
fn openflux_single_plain_transport() {
    let (name, c) = parse_openflux(&of(r#"{"name":"Home","transports":[{"type":"vyandex","url":"https://docs.yandex.ru/d/1"}]}"#)).unwrap();
    assert_eq!(name, "Home");
    assert_eq!((c.transport.as_str(), c.doc_url.as_str()), (OF_VYANDEX, "https://docs.yandex.ru/d/1"));
    // The core's own raw-DEFLATE share link imports the same.
    let raw = compose_openflux_raw(r#"{"name":"Home","transports":[{"type":"vyandex","url":"https://docs.yandex.ru/d/1"}]}"#);
    assert_eq!(parse_openflux(&raw).unwrap().1, c);
}

#[test]
fn openflux_encrypted_link_keeps_secret_context_negotiate() {
    let t = r#"{"type":"mailru","url":"https://cloud.mail.ru/public/x"}"#;
    let c = parse_openflux(&of(&format!(r#"{{"negotiate":true,"secret":"abcdefghijklmnop","context":"ctx","transports":[{t}]}}"#))).unwrap().1;
    assert_eq!((c.secret.as_str(), c.context.as_str(), c.negotiate), ("abcdefghijklmnop", "ctx", true));
    assert_eq!(c.encryption_args("k"), vec!["--encryption-key-file", "k", "--session-context", "ctx", "--negotiate"]);
    let classic = parse_openflux(&of(&format!(r#"{{"secret":"abcdefghijklmnop","transports":[{t}]}}"#))).unwrap().1;
    assert_eq!(classic.encryption_args("k"), vec!["--encryption-key-file", "k"]);
}

#[test]
fn openflux_rejections() {
    let t = r#"{"type":"yandex","url":"https://x"}"#;
    assert!(parse_openflux(&of(&format!(r#"{{"negotiate":true,"transports":[{t}]}}"#))).is_none());
    assert!(parse_openflux(&of(&format!(r#"{{"mode":"stream","secret":"abcdefghijklmnop","transports":[{t}]}}"#))).is_none());
    assert!(parse_openflux(&of(&format!(r#"{{"transports":[{t},{t}]}}"#))).is_none());
    assert!(parse_openflux(&of(r#"{"transports":[{"type":"direct","dial":"1.2.3.4:5"}]}"#)).is_none());
    assert!(parse_openflux("openflux://v1/!!!").is_none());
}

#[test]
fn openflux_session_link_and_core_args() {
    let c = parse_openflux(&of(r#"{"secret":"abcdefghijklmnop","transports":[
        {"type":"direct","dial":"203.0.113.5:8445","priority":100},
        {"type":"yandex","url":"https://docs.yandex.ru/d/1","priority":50},
        {"type":"mailru","url":"https://cloud.mail.ru/public/x","priority":10}]}"#))
    .unwrap()
    .1;
    assert!(c.is_session() && c.is_complete());
    assert_eq!(
        c.carrier_args(),
        vec![
            "--transports", "direct:100,yandex:50,mailru:10", "--direct-dial", "203.0.113.5:8445", "--yandex-url",
            "https://docs.yandex.ru/d/1", "--mailru-url", "https://cloud.mail.ru/public/x",
        ]
    );
    let y = r#"{"type":"yandex","url":"https://x"}"#;
    assert!(parse_openflux(&of(&format!(r#"{{"secret":"abcdefghijklmnop","transports":[{y},{y}]}}"#))).is_none());
    assert!(parse_openflux(&of(&format!(r#"{{"secret":"abcdefghijklmnop","transports":[{y},{{"type":"direct","dial":"host"}}]}}"#))).is_none());
}

#[test]
fn openflux_max_and_avito() {
    let c = parse_openflux(&of(r#"{"transports":[{"type":"oneme","uid":"777","token":"cli-tok"}]}"#)).unwrap().1;
    assert_eq!((c.transport.as_str(), c.max_uid.as_str(), c.max_token.as_str()), (OF_MAX, "777", "cli-tok"));
    assert!(parse_openflux(&of(r#"{"transports":[{"type":"oneme","uid":"x1","token":"t"}]}"#)).is_none());
    assert!(parse_openflux(&of(r#"{"transports":[{"type":"oneme","uid":"777"}]}"#)).is_none());
    let a = parse_openflux(&of(r#"{"transports":[{"type":"avito","url":"ROOMLISTROOMLIST1234567890","cookies":"sid=c1"}]}"#)).unwrap().1;
    assert_eq!((a.transport.as_str(), a.doc_url.as_str(), a.avito_cookies.as_str()), (OF_AVITO, "ROOMLISTROOMLIST1234567890", "sid=c1"));
    let avito = OpenFluxConfig { transport: OF_AVITO.into(), doc_url: "eyJ2IjoyLCJyb29tcyI6WyJhIl19".into(), ..Default::default() };
    assert!(!avito.is_complete());
    assert!(OpenFluxConfig { avito_cookies: "a=b".into(), ..avito.clone() }.is_complete());
    assert_eq!(avito.with_session(true).with_session(false).transport, avito.transport);
}

// ---------------- MasterDNS ----------------

#[test]
fn masterdns_panel_link() {
    let (name, c) = parse_masterdns("masterdns://v1?domains=v.example.com,w.example.com&key=s3cret%2B&enc=1&resolvers=203.0.113.5%3A53&bal=2#Stealth%20NL-dns").unwrap();
    assert_eq!(name, "Stealth NL-dns");
    assert_eq!(c.domains, "v.example.com,w.example.com");
    assert_eq!(c.encryption_key, "s3cret+");
    assert_eq!((c.resolvers.as_str(), c.balancing_strategy), ("203.0.113.5:53", 2));
    assert!(parse_masterdns("masterdns://v2?domains=a&key=b&resolvers=c").is_none());
    assert!(parse_masterdns("masterdns://v1?domains=a&resolvers=c").is_none()); // no key
}

// ---------------- olcRTC ----------------

#[test]
fn olcrtc_panel_link_with_client_and_name() {
    let p = parse_olcrtc(&format!(
        "olcrtc://wbstream?seichannel@room123#{}%b7856bde$Stealth NL-olc",
        "ef".repeat(32)
    ))
    .unwrap();
    assert_eq!(p.location.name, "Stealth NL-olc");
    assert_eq!(p.location.id, "room123");
    assert_eq!(p.location.key, "ef".repeat(32)); // `%client` is not part of the key
    assert_eq!(p.location.bypass_provider, PROVIDER_WB_STREAM);
    assert_eq!(p.location.transport, TRANSPORT_SEICHANNEL);
    assert_eq!(p.mimo.as_deref(), Some("Stealth NL-olc"));
}

#[test]
fn olcrtc_multi_room_and_transport_options() {
    let p = parse_olcrtc("olcrtc://jitsi?datachannel@r1#k1%c!vkcalls?vp8channel<fps=30&batch=8>@r2#k2$Two").unwrap();
    assert_eq!(p.location.name, "Two");
    assert!(p.location.uses_multi_room());
    assert_eq!(p.location.extra_rooms.len(), 1);
    assert_eq!(p.location.extra_rooms[0].room, "r2");
    assert_eq!(p.location.extra_rooms[0].provider, PROVIDER_VK_CALLS);
    let sei = parse_olcrtc("olcrtc://telemost?seichannel<frag=700&ack-ms=1500>@r#k").unwrap().location;
    assert_eq!(sei.sei_options().fragment_size, 700);
    assert_eq!(sei.sei_options().ack_timeout_ms, 1500);
    assert!(parse_olcrtc("olcrtc://broken").is_none());
}

#[test]
fn refresh_interval() {
    assert_eq!(refresh_interval_hours(Some("6h")), Some(6));
    assert_eq!(refresh_interval_hours(Some("90m")), Some(2));
    assert_eq!(refresh_interval_hours(Some("1d")), Some(24));
    assert_eq!(refresh_interval_hours(Some("5")), Some(1));
    assert_eq!(refresh_interval_hours(Some("x")), None);
}

// ---------------- yptun://inbound ----------------

fn sample() -> LocationConfig {
    LocationConfig {
        name: "My Server".into(),
        engine: EngineType::Standard,
        core: ProxyCore::Xray,
        routing_profile_id: "ru-direct".into(),
        proxy: Some(ProxyProfile { kind: TYPE_VLESS.into(), server: "example.com".into(), server_port: 443, uuid: "abc-123-uuid".into(), ..Default::default() }),
        ..Default::default()
    }
}

#[test]
fn inbound_round_trips_the_whole_config() {
    let link = compose_inbound(&sample());
    assert!(link.starts_with(YPTUN_INBOUND_PREFIX));
    assert!(link.contains("?v=2&c=") || link.contains("?v=1&d="));
    assert_eq!(parse_inbound(&link), Some(sample().normalized()));
    let wrapped: Vec<String> = link.as_bytes().chunks(40).map(|c| String::from_utf8(c.to_vec()).unwrap()).collect();
    assert_eq!(parse_inbound(&wrapped.join("\n")), Some(sample().normalized()));
}

#[test]
fn inbound_plain_and_fragment_forms() {
    let json = serde_json::to_string(&sample()).unwrap();
    let d = URL_SAFE_NO_PAD.encode(json);
    assert_eq!(parse_inbound(&format!("yptun://inbound?v=1&d={d}")), Some(sample().normalized()));
    assert_eq!(parse_inbound(&format!("yptun://inbound#{d}")), Some(sample().normalized()));
    assert!(parse_inbound("vless://abc@example.com:443").is_none());
    assert!(parse_inbound("yptun://inbound?v=1&d=!!!notbase64!!!").is_none());
}

#[test]
fn inbound_link_from_kotlin_minimal_json_loads() {
    // encodeDefaults=false on the Kotlin side: only the non-default fields are present.
    let json = r#"{"name":"K","engine":"standard","proxy":{"server":"k.example","server_port":443,"uuid":"u"}}"#;
    let c = parse_inbound(&format!("yptun://inbound?v=1&d={}", URL_SAFE_NO_PAD.encode(json))).unwrap();
    assert_eq!(c.engine, EngineType::Standard);
    assert!(c.is_complete());
}
