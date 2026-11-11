use yptun_core::importer;
use yptun_core::model::{ProxyCore, TYPE_OPENVPN};
use yptun_core::openvpn;
use yptun_core::singbox::config::{self as singbox, SingBoxOptions};

const OVPN: &str = r#"client
dev tun
proto udp
remote vpn.example.com 1194 udp
remote backup.example.com 443 tcp
remote-random
auth SHA256
cipher AES-256-CBC
data-ciphers AES-256-GCM:AES-128-GCM
remote-cert-tls server
verify-x509-name vpn.example.com name
keepalive 10 60
reneg-sec 0
pull-filter ignore "redirect-gateway ipv6"
<ca>
-----BEGIN CERTIFICATE-----
TEST-CA
-----END CERTIFICATE-----
</ca>
<cert>
-----BEGIN CERTIFICATE-----
TEST-CLIENT
-----END CERTIFICATE-----
</cert>
<key>
-----BEGIN PRIVATE KEY-----
TEST-KEY
-----END PRIVATE KEY-----
</key>
<tls-crypt>
-----BEGIN OpenVPN Static key V1-----
TEST-CONTROL-KEY
-----END OpenVPN Static key V1-----
</tls-crypt>
<auth-user-pass>
alice
correct horse battery staple
</auth-user-pass>"#;

#[test]
fn inline_ovpn_becomes_a_native_endpoint_profile() {
    assert!(openvpn::looks_like(OVPN));
    let profile = openvpn::parse(OVPN).unwrap();
    assert_eq!(profile.kind, TYPE_OPENVPN);
    assert_eq!(
        (profile.server.as_str(), profile.server_port),
        ("vpn.example.com", 1194)
    );
    let raw: serde_json::Value =
        serde_json::from_str(profile.raw_outbound.as_deref().unwrap()).unwrap();
    assert_eq!(raw["servers"].as_array().unwrap().len(), 2);
    assert_eq!(raw["servers"][1]["network"], "tcp");
    assert_eq!(raw["username"], "alice");
    assert_eq!(raw["data_ciphers_fallback"], "AES-256-CBC");
    assert_eq!(raw["ping_restart"], "60s");
    assert_eq!(raw["tls"]["control_wrap"]["type"], "tls_crypt");
}

#[test]
fn paste_import_is_singbox_only() {
    let parsed = importer::parse_import(OVPN, None, None, None).unwrap();
    let location = parsed.bundle.locations[0].location();
    assert_eq!(location.proxy.as_ref().unwrap().kind, TYPE_OPENVPN);
    assert_eq!(location.core, ProxyCore::SingBox);
    let mut forced_xray = location.clone();
    forced_xray.core = ProxyCore::Xray;
    assert_eq!(
        forced_xray.resolved_core(ProxyCore::Xray),
        ProxyCore::SingBox
    );
}

#[test]
fn generated_config_uses_endpoints_not_outbounds() {
    let profile = openvpn::parse(OVPN).unwrap();
    let json: serde_json::Value = serde_json::from_str(
        &singbox::build(
            &profile,
            &SingBoxOptions {
                block_quic: false,
                ..Default::default()
            },
        )
        .unwrap(),
    )
    .unwrap();
    let endpoints = json["endpoints"].as_array().unwrap();
    assert_eq!(endpoints.len(), 1);
    assert_eq!(endpoints[0]["type"], TYPE_OPENVPN);
    assert_eq!(endpoints[0]["tag"], "proxy");
    assert_eq!(endpoints[0]["system"], false);
    assert!(json["outbounds"]
        .as_array()
        .unwrap()
        .iter()
        .all(|o| o["type"] != TYPE_OPENVPN));
    assert_eq!(json["route"]["final"], "proxy");
    assert!(json["route"]["rules"]
        .as_array()
        .unwrap()
        .iter()
        .all(|r| r["protocol"] != serde_json::json!(["quic"])));
}

#[test]
fn openvpn_can_be_the_second_cascade_endpoint() {
    let openvpn = openvpn::parse(OVPN).unwrap();
    let main = yptun_core::model::ProxyProfile {
        kind: yptun_core::model::TYPE_VLESS.into(),
        server: "main.example".into(),
        server_port: 443,
        uuid: "11111111-2222-3333-4444-555555555555".into(),
        security: yptun_core::model::SECURITY_TLS.into(),
        ..Default::default()
    };
    let json: serde_json::Value = serde_json::from_str(
        &singbox::build(
            &main,
            &SingBoxOptions {
                second_profile: Some(openvpn),
                block_quic: false,
                ..Default::default()
            },
        )
        .unwrap(),
    )
    .unwrap();
    let endpoint = &json["endpoints"][0];
    assert_eq!(endpoint["tag"], "proxy");
    assert_eq!(endpoint["detour"], "proxy-base");
    let main_outbound = json["outbounds"]
        .as_array()
        .unwrap()
        .iter()
        .find(|o| o["type"] == "vless")
        .unwrap();
    assert_eq!(main_outbound["tag"], "proxy-base");
}

#[test]
fn external_material_is_rejected_instead_of_silently_lost() {
    assert!(openvpn::parse(
        "client\nremote vpn.example 1194\nca ca.crt\ncert client.crt\nkey client.key"
    )
    .is_none());
    assert!(
        openvpn::parse("client\nremote vpn.example 1194\nauth-user-pass\n<ca>\nCA\n</ca>")
            .is_none()
    );
}

#[test]
fn full_singbox_json_imports_openvpn_from_endpoints() {
    let text = r#"{
      "endpoints":[{"type":"openvpn-client","tag":"office-vpn","server":"office.example","server_port":1194,
        "network":"udp","tls":{"certificate":["TEST-CA"]}}],
      "outbounds":[{"type":"direct","tag":"direct"}],
      "route":{"final":"office-vpn","rules":[{"domain_suffix":["lan.example"],"outbound":"direct"}]}
    }"#;
    let parsed = importer::parse_import(text, None, None, None).unwrap();
    let location = parsed.bundle.locations[0].location();
    let profile = location.proxy.unwrap();
    assert_eq!(profile.kind, TYPE_OPENVPN);
    assert_eq!(profile.server, "office.example");
    assert_eq!(location.core, ProxyCore::SingBox);
    assert!(profile.raw_singbox_route.unwrap().contains("lan.example"));
}

#[test]
fn static_key_mode_keeps_cipher_out_of_tls_fallback() {
    let p = openvpn::parse(
        "dev tun\nproto udp\nremote 198.51.100.20 1194\nifconfig 10.8.0.2 10.8.0.1\n\
         cipher AES-256-CBC\n<secret>\n-----BEGIN OpenVPN Static key V1-----\nTEST\n\
         -----END OpenVPN Static key V1-----\n</secret>",
    )
    .unwrap();
    let raw: serde_json::Value = serde_json::from_str(p.raw_outbound.as_deref().unwrap()).unwrap();
    assert_eq!(raw["mode"], "static_key");
    assert_eq!(raw["cipher"], "AES-256-CBC");
    assert!(raw.get("data_ciphers_fallback").is_none());
    assert_eq!(raw["address"], serde_json::json!(["10.8.0.2/32"]));
    assert_eq!(raw["peer_address"], "10.8.0.1");
}
