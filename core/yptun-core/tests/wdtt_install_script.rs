//! Ported from WdttInstallScriptTest.kt.

mod common;

use base64::Engine as _;
use yptun_core::installer::wdtt::*;

fn options() -> WdttInstallOptions {
    WdttInstallOptions {
        host: "203.0.113.7".into(),
        ssh_port: 2222,
        wdtt_port: 56000,
        wdtt_password: "pa'ss".into(),
        dns: "1.1.1.1, 8.8.8.8".into(),
        ..Default::default()
    }
}

const PORTS: WdttPorts = WdttPorts {
    dtls: 56003,
    wg: 56001,
    admin: 56002,
    raw: 56004,
};

#[test]
fn prepare_looks_for_old_wdtt_and_picks_free_ports() {
    let script = build_prepare_script(56000);
    assert!(script.contains("for P in 56000 56001; do"));
    assert!(
        script.contains("\"main_password\""),
        "a WDTT Plus database must be set aside"
    );
    assert!(script.contains("echo \"WDTT_PORTS=$DTLS|$WG|$ADMIN|$RAW\""));
    assert!(
        script.contains("RAW=56003"),
        "Raw is always enabled, on qWDTT's default port"
    );
}

#[test]
fn deploy_runs_the_qwdtt_installer_with_ports_and_secrets() {
    let command = build_deploy_command(&options(), &PORTS, "abc");
    assert!(command.contains(
        "WDTT_DTLS_PORT=56003 WDTT_WG_PORT=56001 WDTT_ADMIN_PORT=56002 WDTT_RAW_PORT=56004 WDTT_SSH_PORT=2222 bash /tmp/deploy.sh"
    ));
    assert!(command.contains("WDTT_DNS_SERVERS='1.1.1.1,8.8.8.8'"));
    // Secrets go through base64, so no password can break the shell quoting.
    assert!(!command.contains("pa'ss"));
    let b64 = base64::engine::general_purpose::STANDARD.encode("pa'ss");
    assert!(command.contains(&format!(
        "printf '%s' '{b64}' | base64 -d > /tmp/wdtt-main.password"
    )));
    assert!(command.contains("echo \"WDTT_DEPLOY_EXIT=$?\""));
}

#[test]
fn scripts_are_valid_bash() {
    for script in [
        build_prepare_script(56000),
        build_deploy_command(&options(), &PORTS, "t"),
        build_verify_script(),
    ] {
        common::assert_bash_syntax(&script[..20], &script);
    }
}

#[test]
fn reads_ports_and_the_installer_result() {
    assert_eq!(
        parse_ports("Порт 56000/udp занят\nWDTT_PORTS=56003|56001|56002|56004\n"),
        Some(WdttPorts {
            dtls: 56003,
            wg: 56001,
            admin: 56002,
            raw: 56004
        })
    );
    assert_eq!(parse_ports("WDTT_PORTS=56003|x|56002|56004"), None);
    assert_eq!(parse_ports("WDTT_PORTS=56003|56001|56002"), None);

    let ok = read_deploy_output(
        "\u{1B}[0;32m[✓]\u{1B}[0m Готово\nWDTT_PROGRESS|0.05|Очистка...\nGet:1 http://deb.debian.org stable InRelease\n\
         WDTT_ADMIN_PIN|sha256/abc=\nWDTT_DEPLOY_OK\nWDTT_DEPLOY_EXIT=0\n",
    );
    assert!(ok.ok);
    assert_eq!(ok.lines, ["[✓] Готово", "• Очистка..."]);

    // The success marker alone is not enough when the script itself failed.
    assert!(!read_deploy_output("WDTT_DEPLOY_OK\nWDTT_DEPLOY_EXIT=1").ok);
    assert!(!read_deploy_output("WDTT_DEPLOY_SERVICE_FAILED\nWDTT_DEPLOY_EXIT=0").ok);
}

#[test]
fn install_prepares_uploads_deploys_and_verifies() {
    let vps = common::FakeVps::new()
        .rule(
            "systemctl stop wdtt >/dev/null",
            0,
            "WDTT_PORTS=56005|56001|56002|56003\n",
        )
        .rule(
            "bash /tmp/deploy.sh",
            0,
            "WDTT_PROGRESS|0.5|Сеть\nWDTT_DEPLOY_OK\nWDTT_DEPLOY_EXIT=0\n",
        )
        .rule("NRestarts", 0, "WDTT_SERVICE_STABLE\n");
    let binaries = |p: &str| match p {
        "wdtt/deploy.sh" => Some(b"#!/bin/bash\r\necho hi\r\n".to_vec()),
        p if p.ends_with(".gz") => Some(common::fake_gz(100)),
        _ => None,
    };
    let log = common::Log::default();
    let o = WdttInstallOptions {
        ssh_password: "pw".into(),
        wdtt_password: "secret".into(),
        ..options()
    };
    let result = install(&vps, &binaries, &o, &|m| log.push(m)).unwrap();
    assert_eq!(
        result,
        WdttInstallResult {
            message: "qWDTT установлен и запущен на 203.0.113.7:56005 (Raw — 56003)".into(),
            port: 56005,
            raw_port: 56003
        }
    );
    // CRLF is stripped from the bundled deploy.sh before it is uploaded.
    assert_eq!(
        vps.file("/tmp/deploy.sh").unwrap(),
        b"#!/bin/bash\necho hi\n"
    );
    let deploy = vps
        .commands()
        .into_iter()
        .find(|c| c.contains("bash /tmp/deploy.sh"))
        .unwrap();
    // A fresh 24-byte admin token, hex, through base64.
    let token = deploy
        .split("printf '%s' '")
        .nth(2)
        .unwrap()
        .split('\'')
        .next()
        .unwrap();
    let token = base64::engine::general_purpose::STANDARD
        .decode(token)
        .unwrap();
    assert!(
        token.len() == 48
            && token
                .iter()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
    );
    assert!(log.text().contains("Порты: DTLS 56005/udp, WireGuard 56001/udp (только локально), админка 56002/tcp, Raw 56003/udp"));
    assert!(log.text().contains("• Сеть"));
}
