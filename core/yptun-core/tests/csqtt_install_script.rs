//! Ported from CsqttInstallScriptTest.kt, plus the install flow against a scripted VPS.

mod common;

use base64::Engine as _;
use yptun_core::installer::csqtt::*;

fn options() -> CsqttInstallOptions {
    CsqttInstallOptions {
        host: "203.0.113.7".into(),
        ssh_port: 2222,
        password: "pa'ss\"w".into(),
        ..Default::default()
    }
}

const PORTS: CsqttPorts = CsqttPorts {
    peer: 46001,
    web: 46003,
};

fn b64(text: &str) -> String {
    base64::engine::general_purpose::STANDARD.encode(text)
}

#[test]
fn deploy_runs_the_csqtt_installer_with_ports_and_secrets() {
    let command = build_deploy_command(&options(), &PORTS, "admin", "web\\pass", 2222);
    assert!(command.contains(
        "CSQTT_PEER_PORT=46001 CSQTT_SSH_PORT=2222 CSQTT_WEB_PORT=46003 CSQTT_DEPLOY_MODE=systemd bash /tmp/deploy.sh install"
    ));
    // Secrets go through base64, so no password can break the shell quoting.
    assert!(!command.contains("pa'ss"));
    let overrides = b64(r#"{"main_password":"pa'ss\"w","device_id":""}"#);
    assert!(command.contains(&format!(
        "printf '%s' '{overrides}' | base64 -d > /tmp/.csqtt-upload-overrides.json"
    )));
    let env = b64("CSQTT_WEB_USER=\"admin\"\nCSQTT_WEB_PASS=\"web\\\\pass\"\n");
    assert!(command.contains(&format!(
        "printf '%s' '{env}' | base64 -d > /tmp/.csqtt-upload-web.env"
    )));
    assert!(command.contains("echo \"CSQTT_DEPLOY_EXIT=$?\""));
}

#[test]
fn prepare_picks_free_ports_but_keeps_own_csqtt() {
    let script = build_prepare_script(46000, 46002);
    assert!(script.contains("PEER=46000") && script.contains("WEB=46002"));
    assert!(
        script.contains("!= csqtt"),
        "a redeploy must be allowed to take over its own ports"
    );
    assert!(script.contains("echo \"CSQTT_PORTS=$PEER|$WEB\""));
}

#[test]
fn scripts_are_valid_bash() {
    let scripts = [
        build_prepare_script(46000, 46002),
        build_deploy_command(&options(), &PORTS, "admin", "x", 22),
        build_verify_script(),
        as_root(&build_verify_script()),
    ];
    for script in scripts {
        common::assert_bash_syntax(&script[..20], &script);
    }
}

/// `YPTUN_DUMP_CSQTT_SCRIPTS=<dir>` writes the exact scripts the installer sends, to run them on a
/// throwaway Linux box (a VPS rehearsal without the app); a no-op otherwise.
#[test]
fn dump_scripts_for_a_rehearsal() {
    let Some(dir) = std::env::var("YPTUN_DUMP_CSQTT_SCRIPTS")
        .ok()
        .filter(|d| !d.trim().is_empty())
    else {
        return;
    };
    let out = std::path::Path::new(&dir);
    std::fs::create_dir_all(out).unwrap();
    let ports = CsqttPorts {
        peer: 46000,
        web: 46002,
    };
    let rehearsal = CsqttInstallOptions {
        password: "rehearsal-pass".into(),
        ..options()
    };
    std::fs::write(
        out.join("prepare.sh"),
        as_root(&build_prepare_script(46000, 46002)),
    )
    .unwrap();
    std::fs::write(
        out.join("deploy-cmd.sh"),
        as_root(&build_deploy_command(
            &rehearsal,
            &ports,
            "admin",
            "rehearsal-web",
            2222,
        )),
    )
    .unwrap();
    std::fs::write(out.join("verify.sh"), as_root(&build_verify_script())).unwrap();
}

#[test]
fn reads_ports_and_architecture() {
    assert_eq!(
        parse_ports("Порт 46000/udp занят\nCSQTT_PORTS=46001|46003\n"),
        Some(CsqttPorts {
            peer: 46001,
            web: 46003
        })
    );
    assert_eq!(parse_ports("CSQTT_PORTS=46001|x"), None);
    assert_eq!(parse_ports("CSQTT_PORTS=46001"), None);
    assert_eq!(server_arch("x86_64"), Some("amd64"));
    assert_eq!(server_arch("aarch64"), Some("arm64"));
    assert_eq!(server_arch("armv7l"), Some("armv7"));
    assert_eq!(server_arch("riscv64"), None);
}

#[test]
fn reads_the_installer_result() {
    let ok = read_deploy_output(
        "\u{1B}[0;32m[✓]\u{1B}[0m Готово\nCSQTT_PROGRESS|0.05|Очистка...\nGet:1 http://deb.debian.org stable InRelease\n\
         CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=0\n",
    );
    assert!(ok.ok);
    assert_eq!(ok.lines, ["[✓] Готово", "• Очистка..."]);

    // The success marker alone is not enough when the script itself failed.
    assert!(!read_deploy_output("CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=1").ok);
    let failed =
        read_deploy_output("CSQTT_DEPLOY_ERROR|preflight|порт занят\nCSQTT_DEPLOY_EXIT=20");
    assert!(!failed.ok);
    assert_eq!(failed.lines, ["ОШИБКА (preflight): порт занят"]);
}

fn binaries(p: &str) -> Option<Vec<u8>> {
    match p {
        "csqtt/deploy.sh" => Some(b"#!/bin/bash\r\necho hi\r\n".to_vec()),
        p if p.ends_with(".gz") => Some(common::fake_gz(100)),
        _ => None,
    }
}

fn scripted_vps() -> common::FakeVps {
    common::FakeVps::new()
        .rule(
            "udp_owner()",
            0,
            "Порт 46000/udp занят (xray) — пробую следующий\nCSQTT_PORTS=46001|46002\n",
        )
        .rule(
            "bash /tmp/deploy.sh install",
            0,
            "CSQTT_PROGRESS|0.5|Сеть\nCSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=0\n",
        )
        .rule("NRestarts", 0, "CSQTT_SERVICE_STABLE\n")
}

#[test]
fn install_prepares_uploads_deploys_and_verifies() {
    let vps = scripted_vps();
    let log = common::Log::default();
    let o = CsqttInstallOptions {
        ssh_password: "pw".into(),
        ..options()
    };
    let result = install(&vps, &binaries, &o, &|m| log.push(m)).unwrap();
    assert_eq!(
        (result.peer_port, result.web_port, result.web_login.as_str()),
        (46001, 46002, "admin")
    );
    // No web password asked for: a fresh one without look-alike characters.
    assert_eq!(result.web_password.len(), 12);
    assert!(result
        .web_password
        .chars()
        .all(|c| c.is_ascii_alphanumeric() && !"lo01IO".contains(c)));
    assert!(result.message.starts_with("csqtt установлен и запущен на 203.0.113.7:46001. Веб-панель: https://203.0.113.7:46002 (admin / "));
    // CRLF is stripped from the bundled deploy.sh before it is uploaded.
    assert_eq!(
        vps.file("/tmp/deploy.sh").unwrap(),
        b"#!/bin/bash\necho hi\n"
    );
    // Every step runs through the root wrapper; the deploy step carries the web credentials.
    let scripts = vps.scripts();
    let deploy = scripts
        .iter()
        .find(|s| s.contains("bash /tmp/deploy.sh install"))
        .unwrap();
    let env = b64(&format!(
        "CSQTT_WEB_USER=\"admin\"\nCSQTT_WEB_PASS=\"{}\"\n",
        result.web_password
    ));
    assert!(deploy.contains(&format!(
        "printf '%s' '{env}' | base64 -d > /tmp/.csqtt-upload-web.env"
    )));
    assert!(deploy.contains("CSQTT_PEER_PORT=46001 CSQTT_SSH_PORT=2222 CSQTT_WEB_PORT=46002"));
    let text = log.text();
    assert!(text.contains("Порт 46000/udp занят (xray) — пробую следующий"));
    assert!(text.contains("Порты: peer 46001/udp, веб-панель 46002/tcp"));
    assert!(text.contains("• Сеть"));
    assert!(!text.contains("CSQTT_PORTS="));
}

#[test]
fn install_reports_a_failed_deploy_and_an_unstable_service() {
    let o = CsqttInstallOptions {
        ssh_password: "pw".into(),
        web_login: "boss".into(),
        web_password: "Pa55".into(),
        ..options()
    };
    let failed = common::FakeVps::new()
        .rule("udp_owner()", 0, "CSQTT_PORTS=46000|46002\n")
        .rule(
            "bash /tmp/deploy.sh install",
            0,
            "CSQTT_DEPLOY_ERROR|network|нет сети\nCSQTT_DEPLOY_EXIT=3\n",
        );
    let log = common::Log::default();
    let err = install(&failed, &binaries, &o, &|m| log.push(m)).unwrap_err();
    assert_eq!(
        err.message(),
        "Установщик csqtt не завершился успехом (код 3). Подробности — в строках журнала выше."
    );
    assert!(log
        .lines()
        .contains(&"ОШИБКА (network): нет сети".to_string()));

    let unstable = common::FakeVps::new()
        .rule("udp_owner()", 0, "CSQTT_PORTS=46000|46002\n")
        .rule(
            "bash /tmp/deploy.sh install",
            0,
            "CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=0\n",
        )
        .rule("NRestarts", 0, "Состояние: activating, перезапусков: 3\n");
    let err = install(&unstable, &binaries, &o, &|_| {}).unwrap_err();
    assert_eq!(err.message(), "Служба csqtt не держится после запуска. Журнал сервера:\nСостояние: activating, перезапусков: 3");

    let silent = common::FakeVps::new();
    let err = install(&silent, &binaries, &o, &|_| {}).unwrap_err();
    assert_eq!(err.message(), "VPS не сообщил свободные порты:\n");
}

#[test]
fn install_checks_the_password_before_connecting() {
    let vps = scripted_vps();
    for (password, message) in [
        (" ", "Не указан пароль csqtt"),
        ("a b", "В пароле csqtt не должно быть пробелов"),
        ("a\tb", "В пароле csqtt не должно быть пробелов"),
    ] {
        let o = CsqttInstallOptions {
            ssh_password: "pw".into(),
            password: password.into(),
            ..options()
        };
        assert_eq!(
            install(&vps, &binaries, &o, &|_| {}).unwrap_err().message(),
            message
        );
    }
    assert!(vps.calls().is_empty());
}
