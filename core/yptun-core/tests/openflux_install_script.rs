//! Ported from OpenFluxInstallScriptTest.kt, plus the install flow against a scripted VPS.

mod common;

use yptun_core::installer::openflux::*;
use yptun_core::transports::{OpenFluxConfig, OF_MAX, OF_VYANDEX, OF_YANDEX};

// A real Yandex Docs link is full of %XX (systemd unit specifiers) and may carry & and $.
const URL: &str = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Fabc&name=a\"b$c";

fn options() -> OpenFluxInstallOptions {
    OpenFluxInstallOptions {
        host: "203.0.113.7".into(),
        ssh_password: "x".into(),
        doc_url: URL.into(),
        ..Default::default()
    }
}

fn exec_start(script: &str) -> &str {
    let lines: Vec<&str> = script
        .lines()
        .filter(|l| l.starts_with("ExecStart="))
        .collect();
    assert_eq!(lines.len(), 1, "{script}");
    lines[0]
}

#[test]
fn carrier_secrets_live_in_the_environment_file_not_on_the_command_line() {
    let script = build_install_script(&options());
    let exec_start = exec_start(&script);
    assert!(!exec_start.contains("%3A"), "{exec_start}");
    assert!(
        exec_start.ends_with("--transport ${OPENFLUX_TRANSPORT} --url ${OPENFLUX_DOC_URL}"),
        "{exec_start}"
    );
    // Double-quoted, with the quote escaped; the quoted heredoc keeps $ literal.
    let env_line = format!("OPENFLUX_DOC_URL=\"{}\"", URL.replace('"', "\\\""));
    assert!(script.lines().any(|l| l == env_line), "{script}");
    assert!(script.contains("<<'ENVFILE'") && script.contains("<<'UNIT'"));
}

#[test]
fn l4_exit_replaces_old_install_and_drops_rst_rule() {
    let o = OpenFluxInstallOptions {
        transport: OF_MAX.into(),
        exit_max_token: "tok".into(),
        ..options()
    };
    let script = build_install_script(&o);
    // l4 exit: no host-wide RST drop any more; a leftover rule from an old install is removed.
    assert!(!script.lines().any(|l| l.starts_with("ExecStartPre=")));
    assert!(script.contains("while iptables -D OUTPUT -p tcp --tcp-flags RST RST -j DROP"));
    assert!(script.contains("systemctl disable --now openflux"));
    assert!(script.contains("--mode=l4"));
    assert!(script.lines().any(|l| l == "OPENFLUX_TRANSPORT=\"oneme\""));
    assert!(script.lines().any(|l| l == "OPENFLUX_MAX_TOKEN=\"tok\""));
    let exec_start = exec_start(&script);
    assert!(
        exec_start.ends_with("--transport ${OPENFLUX_TRANSPORT}"),
        "{exec_start}"
    );
    assert!(
        !exec_start.contains("--url"),
        "MAX transport should not have --url on ExecStart: {exec_start}"
    );
}

/// The node must run the SAME transport as the client - the new-editor one used to collapse to "yandex".
#[test]
fn new_editor_transport_reaches_the_node_with_its_document() {
    let script = build_install_script(&OpenFluxInstallOptions {
        transport: OF_VYANDEX.into(),
        ..options()
    });
    assert!(
        script
            .lines()
            .any(|l| l == "OPENFLUX_TRANSPORT=\"vyandex\""),
        "{script}"
    );
    let exec_start = exec_start(&script);
    assert!(
        exec_start.ends_with("--transport ${OPENFLUX_TRANSPORT} --url ${OPENFLUX_DOC_URL}"),
        "{exec_start}"
    );
    let config = |t: &str| {
        OpenFluxConfig {
            transport: t.into(),
            ..Default::default()
        }
        .normalized()
        .transport
    };
    assert_eq!(config("vyandex"), "vyandex");
    assert_eq!(config("bogus"), OF_YANDEX);
}

#[test]
fn script_is_valid_bash() {
    common::assert_bash_syntax("openflux", &build_install_script(&options()));
    let max = OpenFluxInstallOptions {
        transport: OF_MAX.into(),
        exit_max_token: "t'o\"k$".into(),
        ..options()
    };
    common::assert_bash_syntax("openflux max", &build_install_script(&max));
}

#[test]
fn install_uploads_the_node_and_reports_the_journal_verdict() {
    let vps = common::FakeVps::new().rule(
        "gunzip -f /tmp/openflux.gz",
        0,
        "  Служба openflux активна (транспорт: yandex)\n\n",
    );
    let log = common::Log::default();
    let message = install(&vps, &common::any_binary(3000), &options(), &|m| {
        log.push(m)
    })
    .unwrap();
    assert_eq!(message, "Выходная нода OpenFlux запущена на 203.0.113.7");
    assert!(vps.file("/tmp/openflux.gz").is_some());
    assert_eq!(
        log.lines().last().unwrap(),
        "Служба openflux активна (транспорт: yandex)"
    );

    let captcha = common::FakeVps::new().rule(
        "gunzip -f /tmp/openflux.gz",
        0,
        "x\n ОШИБКА: Яндекс показывает IP этого VPS капчу \n",
    );
    let err = install(&captcha, &common::any_binary(10), &options(), &|_| {}).unwrap_err();
    assert_eq!(err.message(), "Яндекс показывает IP этого VPS капчу");
}

#[test]
fn install_checks_the_carrier_before_connecting() {
    let vps = common::FakeVps::new();
    let cases = [
        (
            OpenFluxInstallOptions { transport: "avito".into(), ..options() },
            "Для Avito Code нода ставится вручную (ей нужны cookie аккаунта и она сама печатает список комнат): \
             по инструкции OpenFlux, потом вставь список комнат в приложение",
        ),
        (OpenFluxInstallOptions { transport: OF_MAX.into(), exit_max_token: " ".into(), ..options() }, "Не указан токен MAX выходной ноды"),
        (OpenFluxInstallOptions { doc_url: "docs.yandex.ru/x".into(), ..options() }, "Не указана ссылка на документ/комнату"),
        (OpenFluxInstallOptions { host: " ".into(), ..options() }, "Не указан IP/хост VPS"),
        (OpenFluxInstallOptions { ssh_password: String::new(), ..options() }, "Укажи пароль SSH или SSH-ключ"),
    ];
    for (o, message) in cases {
        assert_eq!(
            install(&vps, &common::any_binary(10), &o, &|_| {})
                .unwrap_err()
                .message(),
            message
        );
    }
    assert!(vps.calls().is_empty());
}
