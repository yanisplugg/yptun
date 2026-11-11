//! The SSH layer under every installer (ported from SshSupport.kt), driven through a scripted VPS:
//! login normalisation, the progress log, error messages and the upload strategy.

mod common;

use common::{any_binary, sha256_hex, Call, FakeVps, Log};
use yptun_core::installer::freeturn::{self, FreeturnExit, FreeturnInstallOptions};
use yptun_core::installer::ssh::{one_shot, upload, SshAuth, SshTarget};

fn target() -> SshTarget {
    SshTarget {
        host: "203.0.113.7".into(),
        port: 22,
        login: "root".into(),
        password: "pw".into(),
        ..Default::default()
    }
}

#[test]
fn upload_goes_over_sftp_and_is_verified() {
    let vps = FakeVps::new();
    let log = Log::default();
    let data = common::fake_gz(3000);
    upload(&vps, &target(), &data, "/tmp/x.gz", &|m| log.push(m)).unwrap();
    assert_eq!(vps.file("/tmp/x.gz").unwrap(), data);
    let calls = vps.calls();
    assert_eq!(calls.len(), 2);
    assert!(matches!(&calls[0], Call::Sftp { path, size: 3000, .. } if path == "/tmp/x.gz"));
    assert_eq!(
        vps.commands(),
        ["if command -v sha256sum >/dev/null 2>&1; then sha256sum < '/tmp/x.gz' | cut -d' ' -f1; \
          else printf 'size:'; wc -c < '/tmp/x.gz'; fi"]
    );
    assert_eq!(log.lines(), ["…заливаю 2 КБ одним файлом (SFTP)"]);
}

#[test]
fn upload_falls_back_to_base64_chunks_when_sftp_fails() {
    let vps = FakeVps::with(|s| s.sftp_fails = true);
    let log = Log::default();
    let data = common::fake_gz(250_000);
    upload(&vps, &target(), &data, "/tmp/it's.gz", &|m| log.push(m)).unwrap();
    assert_eq!(vps.file("/tmp/it's.gz").unwrap(), data);
    let commands = vps.commands();
    assert_eq!(commands[0], r": > '/tmp/it'\''s.gz'");
    // 250 000 bytes are 333 336 base64 chars: four commands, each well under MAX_ARG_STRLEN.
    let chunks: Vec<_> = commands
        .iter()
        .filter(|c| c.starts_with("printf '%s' '"))
        .collect();
    assert_eq!(chunks.len(), 4);
    assert!(chunks
        .iter()
        .all(|c| c.len() < 131_072 && c.ends_with(r"| base64 -d >> '/tmp/it'\''s.gz'")));
    assert!(commands
        .last()
        .unwrap()
        .starts_with("if command -v sha256sum"));
    assert_eq!(
        log.lines(),
        [
            "…заливаю 244 КБ одним файлом (SFTP)",
            "…одним файлом не вышло (sftp subsystem unavailable), грузим частями",
            "…загружено 1/4 (29%)",
            "…загружено 2/4 (59%)",
            "…загружено 3/4 (89%)",
            "…загружено 4/4 (100%)",
        ]
    );
}

#[test]
fn upload_is_checked_by_size_without_sha256sum() {
    let vps = FakeVps::with(|s| s.no_sha256 = true);
    upload(&vps, &target(), &[1, 2, 3], "/tmp/x", &|_| {}).unwrap();
}

#[test]
fn a_mismatching_upload_is_reported_after_the_fallback_also_fails() {
    let vps = FakeVps::with(|s| s.corrupt = true);
    let log = Log::default();
    let data = [7u8; 10];
    let err = upload(&vps, &target(), &data, "/tmp/x", &|m| log.push(m)).unwrap_err();
    let expected = format!(
        "Загруженный файл не совпал с исходным (ожидали {}, получили deadbeef)",
        sha256_hex(&data)
    );
    assert_eq!(err.message(), expected);
    assert!(log.text().contains(&format!(
        "…одним файлом не вышло ({}), грузим частями",
        &expected[..200.min(expected.len())]
    )));
}

#[test]
fn a_failing_command_carries_its_exit_code_and_trimmed_output() {
    let vps = FakeVps::new().rule("false", 3, "\n  boom \n");
    let err = one_shot(&vps, &target(), "false", &|_| {}, false).unwrap_err();
    assert_eq!(err.message(), "Команда завершилась с кодом 3:\nboom");
}

#[test]
fn login_is_normalised_and_announced_once() {
    let vps = FakeVps::new();
    let log = Log::default();
    let t = SshTarget {
        login: " ".into(),
        password: " pw\n".into(),
        port: 2222,
        ..target()
    };
    one_shot(&vps, &t, "true", &|m| log.push(m), true).unwrap();
    one_shot(&vps, &t, "true", &|m| log.push(m), false).unwrap();
    assert_eq!(
        log.lines(),
        [
            "⚠ Пароль содержал пробелы/перевод строки по краям — убрал их (было 4, стало 2 симв.). \
             Если пароль реально оканчивается пробелом — напиши, отключу очистку.",
            "Подключение к 203.0.113.7:2222 (пользователь 'root', пароль 2 симв.)…",
            "ssh: negotiation",
        ]
    );
    let calls = vps.calls();
    let Call::Exec {
        login,
        verbose: true,
        ..
    } = &calls[0]
    else {
        panic!("{calls:?}")
    };
    assert_eq!(
        (login.user.as_str(), login.port, &login.auth),
        ("root", 2222, &SshAuth::Password("pw".into()))
    );
    assert!(matches!(&calls[1], Call::Exec { verbose: false, .. }));
}

#[test]
fn a_key_wins_over_the_password() {
    let vps = FakeVps::new();
    let log = Log::default();
    let t = SshTarget {
        login: "admin".into(),
        private_key: "\n KEY \n".into(),
        passphrase: " pp".into(),
        password: " x ".into(),
        ..target()
    };
    one_shot(&vps, &t, "true", &|m| log.push(m), true).unwrap();
    // No password clean-up warning in key mode.
    assert_eq!(
        log.lines(),
        [
            "Подключение к 203.0.113.7:22 (пользователь 'admin', ключ)…",
            "ssh: negotiation"
        ]
    );
    let Call::Exec { login, .. } = &vps.calls()[0] else {
        panic!()
    };
    assert_eq!(
        login.auth,
        SshAuth::Key {
            private_key: "KEY".into(),
            passphrase: " pp".into()
        }
    );
}

#[test]
fn refused_logins_explain_what_to_check() {
    let vps = FakeVps::with(|s| s.auth_fail = true);
    let pw = one_shot(&vps, &target(), "true", &|_| {}, false).unwrap_err();
    assert!(pw.message().starts_with(
        "Не удалось войти под 'root' (Auth fail for methods 'publickey,password'). Проверь по порядку: \
         1) в пароле нет лишних пробелов/символов"
    ));
    assert!(pw
        .message()
        .ends_with("Подробности SSH-согласования — в логе выше (строки 'ssh:')."));
    let key = one_shot(
        &vps,
        &SshTarget {
            private_key: "KEY".into(),
            ..target()
        },
        "true",
        &|_| {},
        false,
    )
    .unwrap_err();
    assert!(key.message().starts_with("Не удалось войти под 'root' по ключу (Auth fail for methods 'publickey,password'). Проверь: "));
    assert!(key
        .message()
        .contains("4) на VPS PubkeyAuthentication yes."));

    let vps = FakeVps::with(|s| s.bad_key = true);
    let err = one_shot(
        &vps,
        &SshTarget {
            private_key: "KEY".into(),
            ..target()
        },
        "true",
        &|_| {},
        false,
    )
    .unwrap_err();
    assert_eq!(
        err.message(),
        "Не удалось прочитать SSH-ключ: invalid privatekey. Убедись, что вставлен ПРИВАТНЫЙ ключ целиком \
         (строки BEGIN/END включительно). Если ключ с паролем — заполни поле «Пароль ключа»."
    );
}

#[test]
fn freeturn_install_runs_uname_upload_and_one_script() {
    let vps = FakeVps::new().rule(
        "gunzip -f /tmp/freeturn-server.gz",
        0,
        "Служба активна\n RESULT::OBF|SPUB|CPRIV|10.7.1.2/32 \n",
    );
    let log = Log::default();
    let options = FreeturnInstallOptions {
        host: "203.0.113.7".into(),
        ssh_password: "pw".into(),
        ..Default::default()
    };
    let result = freeturn::install(&vps, &any_binary(2048), &options, &|m| log.push(m)).unwrap();
    assert_eq!(
        (
            result.obf_key.as_str(),
            result.server_wg_public_key.as_str(),
            result.client_wg_private_key.as_str()
        ),
        ("OBF", "SPUB", "CPRIV")
    );
    assert_eq!(result.client_wg_address, "10.7.1.2/32");
    assert_eq!(
        result.status,
        "freeturn-server (WireGuard) установлен и запущен на :56000"
    );
    assert_eq!(result.awg, None);
    let commands = vps.commands();
    assert_eq!(commands.len(), 3);
    assert_eq!(commands[0], "uname -m");
    assert_eq!(commands[2], freeturn::build_install_script(&options));
    let text = log.text();
    assert!(text.contains("Архитектура VPS: x86_64 → amd64"));
    assert!(text.contains("Служба активна"));
    assert!(!text.contains("RESULT::"));
    assert!(text.ends_with("Готово: freeturn-server на 203.0.113.7:56000"));
}

#[test]
fn freeturn_amnezia_install_uploads_both_binaries_and_needs_the_params() {
    let vps = FakeVps::with(|s| s.machine = "aarch64".into()).rule(
        "gunzip -f /tmp/freeturn-server.gz",
        0,
        "RESULT::K|S|C||5,40,70,20,30,100,101,102,103\n",
    );
    let options = FreeturnInstallOptions {
        host: "h".into(),
        ssh_password: "pw".into(),
        exit: FreeturnExit::AmneziaWG,
        ..Default::default()
    };
    let asked = std::cell::RefCell::new(Vec::new());
    let binaries = |p: &str| {
        asked.borrow_mut().push(p.to_string());
        p.ends_with(".gz").then(|| common::fake_gz(10))
    };
    let result = freeturn::install(&vps, &binaries, &options, &|_| {}).unwrap();
    assert_eq!(
        *asked.borrow(),
        [
            "freeturn/freeturn-server-linux-arm64",
            "freeturn/freeturn-server-linux-arm64.gz",
            "freeturn/amneziawg-go-linux-arm64",
            "freeturn/amneziawg-go-linux-arm64.gz",
        ]
    );
    assert!(vps.file("/tmp/amneziawg-go.gz").is_some());
    assert_eq!(result.client_wg_address, "10.7.1.2/32");
    assert_eq!(result.awg.unwrap().h4, "103");

    let vps = FakeVps::new().rule(
        "gunzip -f /tmp/freeturn-server.gz",
        0,
        "RESULT::K|S|C|a|1,2\n",
    );
    let err = freeturn::install(&vps, &any_binary(10), &options, &|_| {}).unwrap_err();
    assert_eq!(
        err.message(),
        "AmneziaWG-выход установлен, но параметры обфускации не вернулись — см. лог выше"
    );
}

#[test]
fn freeturn_rejects_bad_input_before_connecting() {
    let vps = FakeVps::new();
    let ok = FreeturnInstallOptions {
        host: "h".into(),
        ssh_password: "pw".into(),
        ..Default::default()
    };
    for (options, message) in [
        (
            FreeturnInstallOptions {
                host: " ".into(),
                ..ok.clone()
            },
            "Не указан IP/хост VPS",
        ),
        (
            FreeturnInstallOptions {
                ssh_password: " ".into(),
                ..ok.clone()
            },
            "Укажи пароль SSH или SSH-ключ",
        ),
        (
            FreeturnInstallOptions {
                freeturn_port: 65536,
                ..ok.clone()
            },
            "Некорректный порт freeturn",
        ),
    ] {
        let err = freeturn::install(&vps, &any_binary(1), &options, &|_| {}).unwrap_err();
        assert_eq!(
            err,
            yptun_core::installer::InstallError::InvalidInput(message.into())
        );
    }
    assert!(vps.calls().is_empty());
}

#[test]
fn freeturn_uninstall_needs_the_confirmation_marker() {
    let options = FreeturnInstallOptions {
        host: "h".into(),
        ssh_password: "pw".into(),
        freeturn_port: 56001,
        ..Default::default()
    };
    let vps = FakeVps::new().rule("ufw delete allow 56001/udp", 0, "удалил\nREMOVED::56001\n");
    let log = Log::default();
    let done = freeturn::uninstall(&vps, &options, &|m| log.push(m)).unwrap();
    assert_eq!(
        done,
        "freeturn на :56001 удалён (служба, туннель, конфиги, правило фаервола)"
    );
    assert_eq!(log.lines(), ["Удаляю freeturn с h:56001…", "удалил"]);
    let vps = FakeVps::new();
    let err = freeturn::uninstall(&vps, &options, &|_| {}).unwrap_err();
    assert_eq!(
        err.message(),
        "Сервер не подтвердил удаление — см. лог выше"
    );
}
