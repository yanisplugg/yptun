//! Ported from ThreeXuiLinkTest.shareRoundTrips, YptunInboundCodecTest.shareLinkFollowsTheToggle and the
//! ConfigShareService parts of LocationsRepositoryImplTest.

use yptun_core::location::*;
use yptun_core::model::*;
use yptun_core::panel::*;
use yptun_core::share::*;
use yptun_core::share_link;
use yptun_core::transports::*;
use yptun_core::uri::percent_encode;

fn link(host: &str, params: &[(&str, &str)]) -> String {
    let q: Vec<String> = params.iter().map(|(k, v)| format!("{k}={}", percent_encode(v))).collect();
    format!("vless://u-u-i-d@{host}:443?{}#3x", q.join("&"))
}

#[test]
fn proxy_links_round_trip() {
    let extra = r#"{"xmux":{"maxConcurrency":"16-32"},"noGRPCHeader":false}"#;
    let fm = r#"{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200"}}]}"#;
    let xhttp = link("1.2.3.4", &[
        ("type", "xhttp"), ("encryption", "mlkem768x25519plus.native.0rtt.KEY+/="), ("path", "/x"), ("host", ""),
        ("mode", "packet-up"), ("extra", extra), ("fm", fm), ("security", "tls"), ("sni", "example.com"),
        ("fp", "chrome"), ("alpn", "h2,http/1.1"), ("pcs", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
        ("vcn", "example.com"), ("flow", "xtls-rprx-vision"),
    ]);
    for l in [
        xhttp.as_str(),
        "vless://u@h.example:443?type=tcp&security=reality&pbk=K&sid=1&sni=a.com&fp=chrome&flow=xtls-rprx-vision#r",
        "vless://u@h:443?type=grpc&serviceName=s&security=tls#g",
        "trojan://p%40ss@t.example:443?type=ws&security=tls&path=%2Fws&host=cdn.example#Trojan%20WS",
        "ss://YWVzLTI1Ni1nY206cGFzcw@1.2.3.4:8388#SS",
        "hysteria2://pw@hy.example:443/?sni=hy.example&insecure=1&obfs=salamander&obfs-password=o&mport=20000-30000#HY",
        "naive+https://user:pa%20ss@n.example:443?sni=n.example#Naive",
        "naive+quic://n.example:443#Q",
    ] {
        let p = share_link::parse(l).unwrap_or_else(|| panic!("parse {l}"));
        let back = compose(&p).unwrap();
        assert_eq!(share_link::parse(&back).as_ref(), Some(&p), "{l}\n→ {back}");
    }
}

#[test]
fn vmess_awg_and_tt_round_trip() {
    let vmess = ProxyProfile {
        tag: "VM".into(), kind: TYPE_VMESS.into(), server: "v.example".into(), server_port: 443, uuid: "id".into(),
        network: NETWORK_WS.into(), security: SECURITY_TLS.into(), sni: "v.example".into(), path: "/w".into(),
        host: "v.example".into(), ..Default::default()
    };
    let back = share_link::parse(&compose(&vmess).unwrap()).unwrap();
    assert_eq!((back.tag.as_str(), back.server.as_str(), back.uuid.as_str(), back.path.as_str()), ("VM", "v.example", "id", "/w"));
    assert_eq!(back.network, NETWORK_WS);

    let ini = "[Interface]\nPrivateKey = a\nAddress = 10.0.0.2/32\nJc = 4\n\n[Peer]\nPublicKey = b\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0\n";
    let awg = share_link::parse(ini).unwrap();
    let l = compose(&awg).unwrap();
    assert!(l.starts_with("amneziawg://") && !l.contains('='));
    assert_eq!(share_link::parse(&l).unwrap().awg_config, awg.awg_config);
    assert!(compose(&ProxyProfile { kind: TYPE_AMNEZIAWG.into(), ..Default::default() }).is_none());

    let raw = ProxyProfile { raw_xray_config: Some(r#"{"outbounds":[]}"#.into()), ..vmess };
    assert_eq!(compose(&raw).as_deref(), Some(r#"{"outbounds":[]}"#));
}

fn vless_location() -> LocationConfig {
    LocationConfig {
        name: "My Server".into(),
        engine: EngineType::Standard,
        proxy: Some(ProxyProfile { kind: TYPE_VLESS.into(), server: "example.com".into(), server_port: 443, uuid: "abc".into(), ..Default::default() }),
        ..Default::default()
    }
}

#[test]
fn share_link_follows_the_toggle() {
    assert!(share_link(&vless_location(), true).starts_with(YPTUN_INBOUND_PREFIX));
    assert!(share_link(&vless_location(), false).starts_with("vless://"));
    let flux = LocationConfig {
        name: "F".into(),
        engine: EngineType::OpenFlux,
        open_flux: Some(OpenFluxConfig { transport: OF_YANDEX.into(), doc_url: "https://x".into(), ..Default::default() }),
        ..Default::default()
    };
    assert!(share_link(&flux, false).starts_with(YPTUN_INBOUND_PREFIX));
}

#[test]
fn olcrtc_share_round_trips_transport_options() {
    let config = LocationConfig {
        name: "Shared".into(), id: "room".into(), key: "d".repeat(64), bypass_provider: PROVIDER_WB_STREAM.into(),
        transport: TRANSPORT_VP8CHANNEL.into(), vp8_fps: 48, vp8_batch: 32, ..Default::default()
    };
    let shared = olcrtc_uri(&config);
    assert!(!shared.contains('%'), "{shared}");
    let back = parse_olcrtc(&shared).unwrap().location;
    assert_eq!((back.vp8_fps, back.vp8_batch, back.name.as_str()), (48, 32, "Shared"));

    let sei = LocationConfig { transport: TRANSPORT_SEICHANNEL.into(), bypass_provider: PROVIDER_TELEMOST.into(), ..config.clone() };
    assert!(olcrtc_uri(&sei).contains("seichannel<fps=60&batch=64&frag=900&ack-ms=2000>"), "{}", olcrtc_uri(&sei));
    let video = LocationConfig { transport: TRANSPORT_VIDEOCHANNEL.into(), bypass_provider: PROVIDER_TELEMOST.into(), ..config };
    assert_eq!(parse_olcrtc(&olcrtc_uri(&video)).unwrap().location.video_options(), video.normalized().video_options());
}

#[test]
fn multi_room_link_round_trips_all_rooms() {
    let (k1, k2) = ("a".repeat(64), "b".repeat(64));
    let link = format!("olcrtc://mtslink?vp8channel<vp8-fps=30&vp8-batch=8>@room1#{k1}%cl!mtslink?vp8channel@room2#{k2}$Мультикомната");
    let config = parse_olcrtc(&link).unwrap().location;
    assert_eq!((config.name.as_str(), config.id.as_str()), ("Мультикомната", "room1"));
    assert!(config.uses_multi_room());
    let shared = olcrtc_uri(&config);
    assert!(shared.contains(&format!("@room1#{k1}!")) && shared.contains(&format!("@room2#{k2}")), "{shared}");
    assert_eq!(parse_olcrtc(&shared).unwrap().location.extra_rooms, config.extra_rooms);
}

#[test]
fn vkturn_shares_its_own_link() {
    let wdtt = LocationConfig {
        name: "W".into(),
        engine: EngineType::VkTurn,
        vkturn: Some(VkTurnConfig { core: CORE_WDTT.into(), wdtt_peer: "1.2.3.4".into(), wdtt_password: "p".into(), vk_link: "h".into(), ..Default::default() }),
        ..Default::default()
    };
    assert!(olcrtc_uri(&wdtt).starts_with("qwdtt://"));
    let csqtt = LocationConfig {
        vkturn: Some(VkTurnConfig { core: CORE_CSQTT.into(), csqtt_peer: "1.2.3.4".into(), csqtt_password: "p".into(), vk_link: "h".into(), ..Default::default() }),
        ..wdtt.clone()
    };
    assert!(olcrtc_uri(&csqtt).starts_with("csqtt://"));
}

#[test]
fn subscription_sharing_lists_distinct_urls() {
    let mk = |id: &str, room: &str, key: char, url: &str| {
        let c = LocationConfig { name: id.into(), id: room.into(), key: key.to_string().repeat(64), bypass_provider: PROVIDER_WB_STREAM.into(), ..Default::default() };
        LocationEntry::from(id, &c, Some(url.into()), None)
    };
    let items = subscription_share_items(&[
        mk("First", "room-a", 'a', "https://example.test/a"),
        mk("Second", "room-b", 'b', "https://example.test/b"),
        mk("Third", "room-c", 'c', "https://example.test/a"),
        mk("Local", "room-d", 'd', "file:///x"),
    ]);
    assert_eq!(items.iter().map(|i| i.url.as_str()).collect::<Vec<_>>(), ["https://example.test/a", "https://example.test/b"]);
    assert_eq!(items[0].location_count, 2);
    assert_eq!(items[0].name, "First");
    assert_eq!(subscription_qr_text(" https://example.test/b \n"), "https://example.test/b");
}
