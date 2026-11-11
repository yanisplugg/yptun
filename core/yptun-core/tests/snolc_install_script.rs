//! Ported from SnolcFilesTest.kt (the install-script half), plus the install flow against a scripted VPS.

mod common;

use yptun_core::installer::snolc::*;

const KEY: &str = "abababababababababababababababababababababababababababababababab";

fn options() -> SnolcInstallOptions {
    SnolcInstallOptions {
        host: "203.0.113.7".into(),
        ssh_password: "pw".into(),
        listen_port: 8443,
        ..Default::default()
    }
}

#[test]
fn install_script_keeps_keys_and_prints_public_key() {
    let script = build_install_script(8443);
    assert!(script.contains("if [ ! -s /etc/snolc/priv.hex ]"));
    assert!(script.contains("<<'SNOLCEOF'") && script.contains("<<'UNIT'"));
    assert!(script.contains(&format!("echo \"{KEY_MARK}")));
    assert!(script.contains("cat > /etc/snolc/snolc.toml <<'SNOLCEOF'"));
    assert!(script.contains("endpoint_ip = \"0.0.0.0:8443\""));
    common::assert_bash_syntax("snolc", &script);
}

#[test]
fn install_returns_the_link_of_the_node() {
    let output = format!(
        "ufw: порт 8443/tcp открыт\nСлужба snolc активна, слушает порт 8443\n  {KEY_MARK} {KEY} \n"
    );
    let vps = common::FakeVps::new().rule("gunzip -f /tmp/snolc.gz", 0, &output);
    let log = common::Log::default();
    let link = install(&vps, &common::any_binary(2000), &options(), &|m| {
        log.push(m)
    })
    .unwrap();
    assert_eq!(link, format!("snolc://203.0.113.7:8443?key={KEY}"));
    // The key line is the result, not a log line.
    assert!(!log.text().contains(KEY_MARK));
    assert!(log
        .text()
        .contains("Служба snolc активна, слушает порт 8443"));

    let v6 = SnolcInstallOptions {
        host: " 2001:db8::1 ".into(),
        listen_port: 443,
        ..options()
    };
    let vps =
        common::FakeVps::new().rule("gunzip -f /tmp/snolc.gz", 0, &format!("{KEY_MARK}{KEY}"));
    assert_eq!(
        install(&vps, &common::any_binary(10), &v6, &|_| {}).unwrap(),
        format!("snolc://[2001:db8::1]:443?key={KEY}")
    );
}

#[test]
fn install_reports_a_missing_or_broken_key() {
    let run = |output: &str| {
        let vps = common::FakeVps::new().rule("gunzip -f /tmp/snolc.gz", 0, output);
        install(&vps, &common::any_binary(10), &options(), &|_| {})
            .unwrap_err()
            .message()
            .to_string()
    };
    assert_eq!(
        run("Служба snolc активна\n"),
        "Нода запущена, но публичный ключ не получен"
    );
    assert_eq!(
        run(&format!("{KEY_MARK}zz\n")),
        "Нода вернула некорректный ключ: zz"
    );
    assert_eq!(
        run(&format!(
            "{KEY_MARK}{KEY}\nОШИБКА: порт 8443 не слушается\n"
        )),
        "порт 8443 не слушается"
    );
}

#[test]
fn install_checks_the_port_before_connecting() {
    let vps = common::FakeVps::new();
    for port in [0, 65536] {
        let o = SnolcInstallOptions {
            listen_port: port,
            ..options()
        };
        assert_eq!(
            install(&vps, &common::any_binary(10), &o, &|_| {})
                .unwrap_err()
                .message(),
            "Порт вне диапазона 1–65535"
        );
    }
    assert!(vps.calls().is_empty());
}
