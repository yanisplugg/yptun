use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_socks_settings::{
    is_valid_port, sanitize_port, unsecured_port, DesktopSocksProxySettings,
};

#[test]
fn defaults_match_kotlin() {
    let s = DesktopSocksProxySettings::default();
    assert_eq!(s.host, "127.0.0.1");
    assert_eq!(s.port, 10808);
    assert!(!s.secured);
    assert_eq!(s.custom_port, None);
    assert!(!s.is_configured());
}

#[test]
fn unsecured_uses_standard_port_and_drops_credentials() {
    let s = DesktopSocksProxySettings {
        host: " ".into(),
        username: "u".into(),
        password: "p".into(),
        ..Default::default()
    };
    let w = s.normalized(DesktopOs::Windows);
    assert_eq!(w.host, "127.0.0.1");
    assert_eq!(w.port, 8080);
    assert_eq!(w.username, "");
    assert_eq!(w.password, "");
    assert_eq!(s.normalized(DesktopOs::Linux).port, 10808);
    assert_eq!(s.normalized(DesktopOs::MacOs).port, 8080);
    assert_eq!(unsecured_port(DesktopOs::Other), 8080);
}

#[test]
fn custom_port_only_when_valid() {
    let ok = DesktopSocksProxySettings {
        custom_port: Some(2000),
        ..Default::default()
    };
    let n = ok.normalized(DesktopOs::Windows);
    assert_eq!((n.port, n.custom_port), (2000, Some(2000)));
    let bad = DesktopSocksProxySettings {
        custom_port: Some(80),
        ..Default::default()
    };
    let n = bad.normalized(DesktopOs::Windows);
    assert_eq!((n.port, n.custom_port), (8080, None));
}

#[test]
fn secured_sanitizes_port_and_truncates_credentials() {
    let s = DesktopSocksProxySettings {
        secured: true,
        port: 80,
        username: "a".repeat(70),
        password: "\u{1F600}".repeat(40),
        ..Default::default()
    };
    let n = s.normalized(DesktopOs::Windows);
    assert_eq!(n.port, 10808);
    assert_eq!(n.username.len(), 64);
    assert_eq!(n.password.chars().count(), 32);
    assert!(n.secured);
    let kept = DesktopSocksProxySettings {
        secured: true,
        port: 5000,
        ..Default::default()
    };
    assert_eq!(kept.normalized(DesktopOs::Linux).port, 5000);
}

#[test]
fn configured_and_ports() {
    let s = DesktopSocksProxySettings {
        username: "u".into(),
        password: "p".into(),
        ..Default::default()
    };
    assert!(s.is_configured());
    let blank = DesktopSocksProxySettings {
        username: " ".into(),
        password: "p".into(),
        ..Default::default()
    };
    assert!(!blank.is_configured());
    assert!(!is_valid_port(1023));
    assert!(is_valid_port(1024));
    assert!(is_valid_port(65535));
    assert!(!is_valid_port(65536));
    assert_eq!(sanitize_port(None), 10808);
    assert_eq!(sanitize_port(Some(3000)), 3000);
}

#[test]
fn proxy_url_uses_normalized_platform_settings_and_auth() {
    let plain = DesktopSocksProxySettings::default();
    assert_eq!(
        plain.proxy_url(DesktopOs::Linux).unwrap(),
        "socks5h://127.0.0.1:10808"
    );
    assert_eq!(
        plain.proxy_url(DesktopOs::Windows).unwrap(),
        "socks5h://127.0.0.1:8080"
    );

    let secured = DesktopSocksProxySettings {
        host: "::1".into(),
        port: 12000,
        username: "user name".into(),
        password: "p@ss".into(),
        secured: true,
        custom_port: None,
    };
    assert_eq!(
        secured.proxy_url(DesktopOs::Linux).unwrap(),
        "socks5h://user%20name:p%40ss@[::1]:12000"
    );
}
