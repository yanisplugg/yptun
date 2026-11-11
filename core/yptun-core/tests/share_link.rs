//! Ported from the app's commonTest: VlessUriParserTest, ShareLinkParserTest, ThreeXuiLinkTest
//! (parsing part) and TrustTunnelParserTest — same inputs, same expectations.

use base64::engine::general_purpose::{STANDARD, URL_SAFE};
use base64::Engine;
use yptun_core::model::*;
use yptun_core::share_link::{
    self, parse, parse_subscription, parse_vless, parse_vless_subscription,
};

// ---------------- VlessUriParserTest ----------------

#[test]
fn parses_reality_vision_link() {
    let uri = "vless://11111111-2222-3333-4444-555555555555@example.com:443\
        ?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome\
        &pbk=ABCDEF&sid=0123&flow=xtls-rprx-vision#My%20Server";
    let p = parse_vless(uri).unwrap();
    assert_eq!(p.tag, "My Server");
    assert_eq!(p.server, "example.com");
    assert_eq!(p.server_port, 443);
    assert_eq!(p.uuid, "11111111-2222-3333-4444-555555555555");
    assert_eq!(p.network, NETWORK_TCP);
    assert_eq!(p.security, SECURITY_REALITY);
    assert_eq!(p.sni, "www.microsoft.com");
    assert_eq!(p.fingerprint, "chrome");
    assert_eq!(p.reality_public_key, "ABCDEF");
    assert_eq!(p.reality_short_id, "0123");
    assert_eq!(p.flow, "xtls-rprx-vision");
    assert!(p.is_complete());
}

#[test]
fn parses_websocket_tls_link() {
    let uri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@1.2.3.4:8443\
        ?type=ws&security=tls&sni=cdn.example.com&host=cdn.example.com&path=%2Fwss#WS";
    let p = parse_vless(uri).unwrap();
    assert_eq!(p.network, NETWORK_WS);
    assert_eq!(p.security, SECURITY_TLS);
    assert_eq!(p.path, "/wss");
    assert_eq!(p.host, "cdn.example.com");
    assert_eq!(p.sni, "cdn.example.com");
}

#[test]
fn parses_ipv6_host() {
    let p = parse_vless("vless://uuid-uuid@[2001:db8::1]:443?security=tls#v6").unwrap();
    assert_eq!(p.server, "2001:db8::1");
    assert_eq!(p.server_port, 443);
}

#[test]
fn rejects_non_vless_scheme() {
    assert!(parse_vless("vmess://something").is_none());
    assert!(parse_vless("https://example.com").is_none());
}

#[test]
fn parses_plain_subscription_list() {
    let body = "vless://uuid-a@a.com:443?security=tls#A\n\
                vless://uuid-b@b.com:8443?type=ws&security=tls&path=/x#B\n\
                trojan://ignored@c.com:443#C";
    let profiles = parse_vless_subscription(body);
    assert_eq!(profiles.len(), 2);
    assert_eq!(profiles[0].server, "a.com");
    assert_eq!(profiles[1].server, "b.com");
}

#[test]
fn parses_real_vision_tcp_link() {
    let uri = "vless://fa4a1163-ace3-457a-9b5a-57f9d19d2c24@vbn.azz.su:443\
        ?encryption=none&flow=xtls-rprx-vision&type=tcp&security=tls\
        &sni=vbn.azz.su&fp=firefox&alpn=http%2F1.1#%F0%9F%87%AB%F0%9F%87%AEfinland";
    let p = parse_vless(uri).unwrap();
    assert_eq!(p.uuid, "fa4a1163-ace3-457a-9b5a-57f9d19d2c24");
    assert_eq!(p.server, "vbn.azz.su");
    assert_eq!(p.server_port, 443);
    assert_eq!(p.network, NETWORK_TCP);
    assert_eq!(p.security, SECURITY_TLS);
    assert_eq!(p.sni, "vbn.azz.su");
    assert_eq!(p.fingerprint, "firefox");
    assert_eq!(p.flow, "xtls-rprx-vision");
    assert_eq!(p.alpn, vec!["http/1.1"]);
    assert_eq!(p.tag, "🇫🇮finland");
    assert_eq!(p.vless_encryption, ""); // encryption=none is no encryption
    assert!(p.is_complete());
}

#[test]
fn parses_json_subscription_with_links_array() {
    let body = r#"{"isFound":true,"user":{"shortUuid":"abc"},
         "links":[
           "vless://uuid-a@vbn.azz.su:443?security=tls&flow=xtls-rprx-vision#A",
           "vless://uuid-b@vbn2.azz.su:443?type=ws&security=tls&path=/x#B"
         ],
         "subscriptionUrl":"https://example"}"#;
    let profiles = parse_vless_subscription(body);
    assert_eq!(profiles.len(), 2);
    assert_eq!(profiles[0].server, "vbn.azz.su");
    assert_eq!(profiles[0].flow, "xtls-rprx-vision");
    assert_eq!(profiles[1].server, "vbn2.azz.su");
    assert_eq!(profiles[1].network, NETWORK_WS);
}

#[test]
fn parses_base64_subscription() {
    let raw = "vless://uuid-a@a.com:443?security=tls#A\nvless://uuid-b@b.com:8443?security=tls#B";
    let profiles = parse_vless_subscription(&STANDARD.encode(raw));
    assert_eq!(profiles.len(), 2);
    assert_eq!(profiles[0].server, "a.com");
    assert_eq!(profiles[1].server, "b.com");
}

// ---------------- ShareLinkParserTest ----------------

#[test]
fn parses_vmess_base64_json() {
    let json = r#"{"v":"2","ps":"node","add":"vm.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555",
         "aid":"0","scy":"auto","net":"ws","host":"vm.example.com","path":"/ray","tls":"tls","sni":"vm.example.com"}"#;
    let p = parse(&format!("vmess://{}", STANDARD.encode(json))).unwrap();
    assert_eq!(p.kind, TYPE_VMESS);
    assert_eq!(p.server, "vm.example.com");
    assert_eq!(p.server_port, 443);
    assert_eq!(p.uuid, "11111111-2222-3333-4444-555555555555");
    assert_eq!(p.network, NETWORK_WS);
    assert_eq!(p.security, SECURITY_TLS);
    assert_eq!(p.path, "/ray");
    assert_eq!(p.tag, "node");
    assert!(p.is_complete());
}

#[test]
fn vmess_numeric_port_and_insecure_flag() {
    let json = r#"{"add":"v.example","port":8443,"id":"u","net":"grpc","path":"svc","skip-cert-verify":"true"}"#;
    let p = parse(&format!(
        "vmess://{}#Fallback%20name",
        STANDARD.encode(json)
    ))
    .unwrap();
    assert_eq!(p.server_port, 8443);
    assert_eq!(p.network, NETWORK_GRPC);
    assert_eq!(p.path, "svc");
    assert!(p.allow_insecure);
    assert_eq!(p.tag, "Fallback name");
}

#[test]
fn parses_trojan_link() {
    let p =
        parse("trojan://secretpass@tj.example.com:443?sni=tj.example.com&type=ws&path=/tj#Trojan")
            .unwrap();
    assert_eq!(p.kind, TYPE_TROJAN);
    assert_eq!(p.server, "tj.example.com");
    assert_eq!(p.server_port, 443);
    assert_eq!(p.password, "secretpass");
    assert_eq!(p.security, SECURITY_TLS); // trojan implies TLS
    assert_eq!(p.network, NETWORK_WS);
    assert_eq!(p.path, "/tj");
    assert!(p.is_complete());
}

#[test]
fn parses_shadowsocks_sip002() {
    let user_info = URL_SAFE.encode("aes-128-gcm:ss-password");
    let p = parse(&format!(
        "ss://{}@ss.example.com:8388#SS",
        user_info.trim_end_matches('=')
    ))
    .unwrap();
    assert_eq!(p.kind, TYPE_SHADOWSOCKS);
    assert_eq!(p.server, "ss.example.com");
    assert_eq!(p.server_port, 8388);
    assert_eq!(p.method, "aes-128-gcm");
    assert_eq!(p.password, "ss-password");
    assert!(p.is_complete());
}

#[test]
fn parses_shadowsocks_legacy_whole_base64() {
    let p = parse(&format!(
        "ss://{}#Old",
        STANDARD.encode("chacha20-ietf-poly1305:pw@1.2.3.4:8388")
    ))
    .unwrap();
    assert_eq!(p.method, "chacha20-ietf-poly1305");
    assert_eq!(p.password, "pw");
    assert_eq!(p.server, "1.2.3.4");
    assert_eq!(p.tag, "Old");
}

#[test]
fn parses_mixed_protocol_subscription() {
    let body = "vless://uuid-a@a.com:443?security=tls&flow=xtls-rprx-vision#A\n\
                trojan://pw@b.com:443?sni=b.com#B\n\
                ss://YWVzLTEyOC1nY206cHc@c.com:8388#C\n\
                hysteria2://pw@d.com:443?sni=d.com&obfs=salamander&obfs-password=op#D";
    let profiles = parse_subscription(body);
    assert_eq!(profiles.len(), 4);
    assert_eq!(profiles[0].kind, TYPE_VLESS);
    assert_eq!(profiles[1].kind, TYPE_TROJAN);
    assert_eq!(profiles[2].kind, TYPE_SHADOWSOCKS);
    assert_eq!(profiles[3].kind, TYPE_HYSTERIA2);
    assert_eq!(profiles[3].server, "d.com");
    assert_eq!(profiles[3].hy2_obfs, "salamander");
}

#[test]
fn parses_hysteria2_port_hopping_in_authority() {
    let p = parse("hy2://pw@d.com:443,5000-6000/?insecure=1#D").unwrap();
    assert_eq!(p.server, "d.com");
    assert_eq!(p.server_port, 443);
    assert_eq!(p.hy2_ports, "443,5000-6000");
    assert!(p.allow_insecure);

    let range = parse("hysteria2://pw@d.com:20000-30000?sni=x.com").unwrap();
    assert_eq!(range.server, "d.com");
    assert_eq!(range.server_port, 20000);
    assert_eq!(range.hy2_ports, "20000-30000");
}

#[test]
fn raw_outbound_profile_is_complete() {
    let raw = r#"{"type":"hysteria2","server":"h.example.com","server_port":443,"password":"x"}"#;
    let p = ProxyProfile {
        tag: "raw".into(),
        raw_outbound: Some(raw.into()),
        ..Default::default()
    };
    assert!(p.is_complete());
}

#[test]
fn parses_naive_https_and_quic() {
    let p = parse("naive+https://user:p%40ss@n.example.com?sni=n.example.com#N").unwrap();
    assert_eq!(p.kind, TYPE_NAIVE);
    assert_eq!((p.username.as_str(), p.password.as_str()), ("user", "p@ss"));
    assert_eq!(p.server_port, 443);
    assert!(!p.naive_quic);
    assert!(
        parse("naive+quic://u:p@n.example.com:8443")
            .unwrap()
            .naive_quic
    );
}

#[test]
fn parses_amneziawg_ini_and_link() {
    let ini =
        "[Interface]\nPrivateKey = x\nJc = 4\n# My AWG\n[Peer]\nEndpoint = 203.0.113.7:51820\n";
    let p = parse(ini).unwrap();
    assert_eq!(p.kind, TYPE_AMNEZIAWG);
    assert_eq!(p.tag, "My AWG");
    assert_eq!((p.server.as_str(), p.server_port), ("203.0.113.7", 51820));
    let link = format!(
        "amneziawg://{}#Stealth%20FI",
        URL_SAFE.encode(ini).trim_end_matches('=')
    );
    assert_eq!(parse(&link).unwrap().tag, "Stealth FI");
}

// ---------------- ThreeXuiLinkTest (parsing) ----------------

/// 3x-ui's Go side: url.Values.Encode — sorted keys, QueryEscape.
fn query_escape(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

const EXTRA: &str = r#"{"mode":"packet-up","xPaddingBytes":"100-1000","xPaddingObfsMode":true}"#;
const FM: &str =
    r#"{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200"}}]}"#;
const PIN_B64: &str = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="; // bytes 0..31

fn pin_hex_0_31() -> String {
    (0..32).map(|i| format!("{i:02x}")).collect()
}

fn xhttp_tls_link() -> String {
    let mut params = std::collections::BTreeMap::new();
    for (k, v) in [
        ("type", "xhttp"),
        ("encryption", "mlkem768x25519plus.native.0rtt.KEY+/="),
        ("path", "/x"),
        ("host", ""),
        ("mode", "packet-up"),
        ("extra", EXTRA),
        ("fm", FM),
        ("security", "tls"),
        ("sni", "example.com"),
        ("fp", "chrome"),
        ("alpn", "h2,http/1.1"),
        ("pcs", PIN_B64),
        ("vcn", "example.com"),
        ("flow", "xtls-rprx-vision"),
    ] {
        params.insert(k, v);
    }
    let q: Vec<String> = params
        .iter()
        .map(|(k, v)| format!("{k}={}", query_escape(v)))
        .collect();
    format!(
        "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?{}#3x-ui%20node",
        q.join("&")
    )
}

#[test]
fn parses_xray_only_params() {
    let p = parse(&xhttp_tls_link()).unwrap();
    assert_eq!(p.vless_encryption, "mlkem768x25519plus.native.0rtt.KEY+/=");
    assert_eq!(p.xhttp_mode, "packet-up");
    assert_eq!(p.xhttp_extra, EXTRA);
    assert_eq!(p.final_mask, FM);
    assert_eq!(p.pinned_cert_sha256, pin_hex_0_31()); // base64 → hex, xray only takes hex
    assert_eq!(p.verify_cert_by_name, "example.com");
    assert_eq!(p.tag, "3x-ui node");
    assert!(p.requires_xray());
}

#[test]
fn grpc_mode_is_not_xhttp_mode() {
    let p = parse("vless://u@h.com:443?type=grpc&serviceName=svc&mode=multi&security=tls").unwrap();
    assert_eq!(p.network, NETWORK_GRPC);
    assert_eq!(p.path, "svc");
    assert_eq!(p.xhttp_mode, "");
    assert!(!p.requires_xray());
}

#[test]
fn pin_formats() {
    let hex = pin_hex_0_31();
    assert_eq!(share_link::pin_hex(&hex.to_uppercase()), hex);
    let colon: Vec<String> = hex
        .as_bytes()
        .chunks(2)
        .map(|c| String::from_utf8(c.to_vec()).unwrap())
        .collect();
    assert_eq!(share_link::pin_hex(&colon.join(":")), hex);
    assert_eq!(
        share_link::pin_hex(
            &PIN_B64
                .trim_end_matches('=')
                .replace('+', "-")
                .replace('/', "_")
        ),
        hex
    );
    assert_eq!(share_link::pin_hex("junk"), "junk");
}

// ---------------- TrustTunnelParserTest ----------------

fn tlv(tag: u8, value: &str) -> Vec<u8> {
    let v = value.as_bytes();
    // QUIC varint: lengths >= 64 take two bytes with the 01 prefix.
    let mut out = vec![tag];
    if v.len() < 64 {
        out.push(v.len() as u8);
    } else {
        out.push(0x40 | (v.len() >> 8) as u8);
        out.push((v.len() & 0xFF) as u8);
    }
    out.extend_from_slice(v);
    out
}

fn tt_link(parts: &[Vec<u8>]) -> String {
    format!(
        "tt://?{}",
        URL_SAFE.encode(parts.concat()).trim_end_matches('=')
    )
}

#[test]
fn reads_address_not_hostname() {
    let l = tt_link(&[
        tlv(1, "tls.example.com"),
        tlv(5, "user"),
        tlv(2, "203.0.113.7:8443"),
        tlv(8, &"x".repeat(300)),
    ]);
    assert_eq!(
        share_link::tt_endpoints(&format!("{l}#name")),
        vec![("203.0.113.7".to_string(), 8443)]
    );
}

#[test]
fn falls_back_to_hostname_443_and_keeps_all_addresses() {
    assert_eq!(
        share_link::tt_endpoints(&tt_link(&[tlv(1, "h.example.com")])),
        vec![("h.example.com".to_string(), 443)]
    );
    assert_eq!(
        share_link::tt_endpoints(&tt_link(&[tlv(2, "a.example:1"), tlv(2, "b.example:2")])),
        vec![("a.example".to_string(), 1), ("b.example".to_string(), 2)]
    );
}

#[test]
fn garbage_gives_empty() {
    assert!(share_link::tt_endpoints("tt://?!!!").is_empty());
    assert!(share_link::tt_endpoints("tt://").is_empty());
}

#[test]
fn parses_mieru_standard_and_simple_links() {
    let opaque = "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3pp#Opaque%20Node";
    let standard = parse(opaque).expect("opaque Mieru link");
    assert_eq!(standard.kind, TYPE_MIERU);
    assert_eq!(standard.tag, "Opaque Node");
    assert_eq!(standard.mieru_config, opaque);
    assert!(standard.is_complete());

    let simple = "mierus://user:p%40ss@203.0.113.9?profile=fast&port=443-444#Simple";
    let parsed = parse(simple).expect("simple Mieru link");
    assert_eq!(parsed.kind, TYPE_MIERU);
    assert_eq!(parsed.server, "203.0.113.9");
    assert_eq!(parsed.server_port, 443);
    assert_eq!(parsed.username, "user");
    assert_eq!(parsed.password, "p@ss");
    assert_eq!(parsed.tag, "Simple");
    assert!(parsed.is_complete());
}

#[test]
fn name_comes_from_the_blob_unless_a_fragment_overrides_it() {
    let l = tt_link(&[
        tlv(1, "h.example.com"),
        tlv(2, "203.0.113.7:443"),
        tlv(12, "🇷🇺Москва (TT)"),
    ]);
    assert_eq!(parse(&l).unwrap().tag, "🇷🇺Москва (TT)");
    let frag = format!("{l}#%D0%98%D0%B7%20%D1%84%D1%80%D0%B0%D0%B3%D0%BC%D0%B5%D0%BD%D1%82%D0%B0");
    assert_eq!(parse(&frag).unwrap().tag, "Из фрагмента");
    assert_eq!(
        parse(&tt_link(&[tlv(1, "h.example.com")])).unwrap().tag,
        "Trust Tunnel"
    );
}

// ---------------- model / storage compatibility ----------------

#[test]
fn json_field_names_match_the_kotlin_app() {
    let p = parse("vless://u@h.com:443?type=xhttp&security=tls&mode=auto&pcs=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D#N").unwrap();
    let v: serde_json::Value = serde_json::to_value(&p).unwrap();
    for key in [
        "server_port",
        "allowInsecure",
        "realityPublicKey",
        "xhttp_mode",
        "pinned_cert_sha256",
        "type",
    ] {
        assert!(v.get(key).is_some(), "missing {key}");
    }
    // A location stored by the Kotlin app (only some fields present) loads with defaults.
    let stored =
        r#"{"tag":"A","type":"trojan","server":"t.com","server_port":443,"password":"pw"}"#;
    let back: ProxyProfile = serde_json::from_str(stored).unwrap();
    assert_eq!(back.cipher, "auto");
    assert_eq!(back.network, NETWORK_TCP);
    assert!(back.is_complete());
}
