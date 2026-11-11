//! The hand-made VLESS form (VlessEditor): fields per transport/security, what each field writes,
//! and which main proxies the form may edit.

use yptun_core::model::*;
use yptun_core::vless_form::VlessField::*;
use yptun_core::vless_form::*;

fn profile(network: &str, security: &str) -> ProxyProfile {
    ProxyProfile {
        network: network.into(),
        security: security.into(),
        ..ProxyProfile::default()
    }
}

#[test]
fn a_fresh_profile_shows_the_plain_tcp_fields() {
    assert_eq!(ProxyProfile::default().kind, TYPE_VLESS);
    assert_eq!(
        visible_fields(None),
        [Server, Port, Uuid, Flow, Encryption, Network, Security]
    );
    assert!(!shows_allow_insecure(None));
}

#[test]
fn the_transport_adds_its_path_and_host() {
    assert_eq!(
        visible_fields(Some(&profile(NETWORK_WS, SECURITY_NONE))),
        [Server, Port, Uuid, Flow, Encryption, Network, Path, Host, Security]
    );
    // grpc has a serviceName and no host.
    let grpc = profile(NETWORK_GRPC, SECURITY_NONE);
    assert_eq!(
        visible_fields(Some(&grpc)),
        [Server, Port, Uuid, Flow, Encryption, Network, Path, Security]
    );
    assert_eq!(Path.label(&grpc), "serviceName");
    assert_eq!(Path.label(&profile(NETWORK_WS, SECURITY_NONE)), "Path");
    assert_eq!(
        visible_fields(Some(&profile(NETWORK_XHTTP, SECURITY_NONE))),
        [
            Server, Port, Uuid, Flow, Encryption, Network, Path, Host, XhttpMode, XhttpExtra,
            Security
        ]
    );
}

#[test]
fn security_adds_its_own_fields() {
    let tls = profile(NETWORK_TCP, SECURITY_TLS);
    assert_eq!(
        visible_fields(Some(&tls)),
        [
            Server,
            Port,
            Uuid,
            Flow,
            Encryption,
            Network,
            Security,
            Sni,
            Fingerprint,
            Alpn,
            PinnedCertSha256,
            EchConfig
        ]
    );
    assert!(shows_allow_insecure(Some(&tls)));
    let reality = profile(NETWORK_GRPC, SECURITY_REALITY);
    assert_eq!(
        visible_fields(Some(&reality)),
        [
            Server,
            Port,
            Uuid,
            Flow,
            Encryption,
            Network,
            Path,
            Security,
            Sni,
            Fingerprint,
            RealityPublicKey,
            RealityShortId,
            RealitySpiderX,
            RealityMldsa65Verify
        ]
    );
    assert!(!shows_allow_insecure(Some(&reality)));
}

#[test]
fn dropdowns_offer_the_editor_choices() {
    assert_eq!(Network.options().unwrap(), NETWORKS);
    assert_eq!(
        Security.options().unwrap(),
        [SECURITY_NONE, SECURITY_TLS, SECURITY_REALITY]
    );
    assert_eq!(Flow.options().unwrap(), ["", "xtls-rprx-vision"]);
    assert_eq!(Fingerprint.options().unwrap().len(), 11);
    assert_eq!(Fingerprint.options().unwrap()[0], "");
    assert_eq!(XhttpMode.options().unwrap(), XHTTP_MODES);
    assert!(Server.options().is_none());
    assert!(Alpn.options().is_none());
}

#[test]
fn typed_values_are_cleaned_as_the_editor_did() {
    let p = ProxyProfile::default();
    let p = Server.apply(&p, "  example.com ");
    assert_eq!(p.server, "example.com");
    // The port keeps its first five digits.
    let p = Port.apply(&p, "44a3");
    assert_eq!(p.server_port, 443);
    assert_eq!(Port.apply(&p, "1234567").server_port, 12345);
    assert_eq!(Port.apply(&p, "").server_port, 0);
    assert_eq!(Port.value(&Port.apply(&p, "x")), "");
    assert_eq!(Port.value(&p), "443");
    // `none` encryption is stored blank.
    assert_eq!(Encryption.apply(&p, " none ").vless_encryption, "");
    let enc = Encryption.apply(&p, "mlkem768x25519plus.native.0rtt.abc");
    assert_eq!(enc.vless_encryption, "mlkem768x25519plus.native.0rtt.abc");
    assert!(enc.requires_xray());
    // ALPN is a comma list.
    let p = Alpn.apply(&p, " h2 , ,http/1.1 ");
    assert_eq!(p.alpn, ["h2", "http/1.1"]);
    assert_eq!(Alpn.value(&p), "h2,http/1.1");
    assert!(Alpn.apply(&p, " , ").alpn.is_empty());
    // Dropdown picks are stored as picked.
    let p = Network.apply(&p, NETWORK_GRPC);
    let p = Path.apply(&p, " svc ");
    assert_eq!((p.network.as_str(), p.path.as_str()), ("grpc", "svc"));
    assert_eq!(Flow.apply(&p, "xtls-rprx-vision").flow, "xtls-rprx-vision");
}

#[test]
fn every_field_reads_back_what_it_wrote() {
    let fields = [
        Server,
        Uuid,
        Flow,
        Encryption,
        Network,
        Path,
        Host,
        XhttpMode,
        XhttpExtra,
        Security,
        Sni,
        Fingerprint,
        PinnedCertSha256,
        EchConfig,
        RealityPublicKey,
        RealityShortId,
        RealitySpiderX,
        RealityMldsa65Verify,
    ];
    let mut p = ProxyProfile::default();
    for (i, field) in fields.iter().enumerate() {
        let value = format!("v{i}");
        p = field.apply(&p, &value);
        assert_eq!(field.value(&p), value, "{field:?}");
    }
    for (i, field) in fields.iter().enumerate() {
        assert_eq!(
            field.value(&p),
            format!("v{i}"),
            "{field:?} was overwritten"
        );
    }
}

#[test]
fn labels_and_placeholders() {
    let p = ProxyProfile::default();
    assert_eq!(CAPTION, "VLESS — ручная настройка");
    assert_eq!(Server.label(&p), "Адрес сервера");
    assert_eq!(Security.label(&p), "Безопасность");
    assert_eq!(RealityPublicKey.label(&p), "Public key (pbk)");
    assert_eq!(Encryption.placeholder(), "none");
    assert_eq!(Alpn.placeholder(), "h2,http/1.1");
    assert_eq!(XhttpExtra.placeholder(), "{\"xPaddingBytes\":\"100-1000\"}");
    assert_eq!(Sni.placeholder(), "");
}

#[test]
fn only_plain_vless_main_proxies_are_editable_by_hand() {
    assert!(is_manually_editable_vless(None));
    assert!(is_manually_editable_vless(Some(&ProxyProfile::default())));
    let raw_xray = ProxyProfile {
        raw_xray_config: Some("{\"outbounds\":[]}".into()),
        ..ProxyProfile::default()
    };
    assert!(!is_manually_editable_vless(Some(&raw_xray)));
    let blank_raw = ProxyProfile {
        raw_outbound: Some("  ".into()),
        ..ProxyProfile::default()
    };
    assert!(is_manually_editable_vless(Some(&blank_raw)));
    let raw_outbound = ProxyProfile {
        raw_outbound: Some("{\"type\":\"vless\"}".into()),
        ..ProxyProfile::default()
    };
    assert!(!is_manually_editable_vless(Some(&raw_outbound)));
    let trojan = ProxyProfile {
        kind: TYPE_TROJAN.into(),
        ..ProxyProfile::default()
    };
    assert!(!is_manually_editable_vless(Some(&trojan)));
}
