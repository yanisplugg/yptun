//! Ported from MasterDnsInstallScriptTest.kt.

mod common;

use yptun_core::installer::masterdns::*;

fn options() -> MasterDnsInstallOptions {
    MasterDnsInstallOptions {
        host: "h".into(),
        domain: "v.x".into(),
        ..Default::default()
    }
}

#[test]
fn upstream_is_sanitised_and_port_defaulted() {
    let s = build_install_script(&MasterDnsInstallOptions {
        dns_upstream: "9.9.9.9, 1.1.1.1:5353; $(rm -rf /)".into(),
        ..options()
    });
    assert!(s.contains("DNS_UPSTREAM_SERVERS = [\"9.9.9.9:53\", \"1.1.1.1:5353\"]"));
    assert!(!s.contains("rm -rf"));
}

#[test]
fn compression_off_and_port_check() {
    let s = build_install_script(&MasterDnsInstallOptions {
        allow_compression: false,
        ..options()
    });
    assert!(s.contains("SUPPORTED_UPLOAD_COMPRESSION_TYPES = [0]"));
    assert!(s.contains("systemctl restart masterdns-server"));
    assert!(s.contains("sport = :5300"));
}

#[test]
fn freeing_port_53_keeps_systemd_resolved_running() {
    let s = build_install_script(&MasterDnsInstallOptions {
        udp_port: 53,
        free_port: true,
        ..options()
    });
    assert!(s.contains("DNSStubListener=no"));
    assert!(s.contains("systemctl restart systemd-resolved"));
    assert!(!s.contains("disable --now systemd-resolved"));
}

#[test]
fn script_parses_as_bash() {
    common::assert_bash_syntax("install", &build_install_script(&options()));
    let awkward = MasterDnsInstallOptions {
        domain: "it's.example".into(),
        free_port: true,
        ..options()
    };
    common::assert_bash_syntax("awkward domain", &build_install_script(&awkward));
}

#[test]
fn install_reads_the_key_marker_and_remote_errors() {
    let options = MasterDnsInstallOptions {
        host: "h".into(),
        ssh_password: "pw".into(),
        ..Default::default()
    };
    let needle = "gunzip -f /tmp/masterdns-server.gz";
    let vps =
        common::FakeVps::new().rule(needle, 0, "слушает\nMASTERDNS_KEY= abc \nMASTERDNS_KEY=\n");
    let log = common::Log::default();
    let result = install(&vps, &common::any_binary(10), &options, &|m| log.push(m)).unwrap();
    assert_eq!(result.encryption_key, "abc");
    assert_eq!(
        result.message,
        "MasterDNS-сервер установлен и запущен на h:5300 (резолвер: h:5300)"
    );
    let text = log.text();
    assert!(
        text.contains("слушает\nMASTERDNS_KEY=\nКлюч шифрования получен"),
        "{text}"
    );

    let vps = common::FakeVps::new().rule(needle, 0, "ОШИБКА: UDP-порт 5300 уже занят\n");
    let err = install(&vps, &common::any_binary(10), &options, &|_| {}).unwrap_err();
    assert_eq!(err.message(), "UDP-порт 5300 уже занят");
    let vps = common::FakeVps::new();
    let err = install(&vps, &common::any_binary(10), &options, &|_| {}).unwrap_err();
    assert_eq!(
        err.message(),
        "Не удалось получить ключ шифрования с сервера"
    );
}
