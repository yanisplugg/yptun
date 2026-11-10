//! Ported from OlcRtcInstallScriptTest.kt.

mod common;

use yptun_core::installer::olcrtc::*;
use yptun_core::installer::InstallError;
use yptun_core::util::kotlin_lines;

fn options(rooms: &[&str]) -> OlcRtcInstallOptions {
    OlcRtcInstallOptions {
        host: "203.0.113.7".into(),
        ssh_password: "x".into(),
        provider: "jitsi".into(),
        transport: "vp8channel".into(),
        rooms: rooms.iter().map(|r| r.to_string()).collect(),
        ..Default::default()
    }
}

fn script() -> String {
    build_install_script(&options(&[
        "https://meet.example.org/olcabc",
        "https://meet.example.org/olcdef",
    ]))
    .unwrap()
}

#[test]
fn heredoc_terminators_start_at_column_zero() {
    for text in [script(), build_uninstall_script()] {
        let lines = kotlin_lines(&text);
        for terminator in ["YAML", "UNIT"] {
            let opens = lines
                .iter()
                .filter(|l| {
                    let l = l.trim_end();
                    l.ends_with(&format!("<<{terminator}"))
                        || l.ends_with(&format!("<<'{terminator}'"))
                })
                .count();
            let closes = lines.iter().filter(|l| **l == terminator).count();
            assert_eq!(
                opens, closes,
                "{terminator}: открыт {opens} раз, закрыт {closes}"
            );
        }
    }
}

#[test]
fn no_stray_indentation() {
    for text in [script(), build_uninstall_script()] {
        // 8 spaces = the Kotlin raw string's base indent, i.e. a piece glued without trimIndent.
        // Smaller indents are legitimate nesting of shell blocks.
        let indented: Vec<_> = kotlin_lines(&text)
            .into_iter()
            .filter(|l| l.starts_with("        "))
            .collect();
        assert!(
            indented.is_empty(),
            "строки с лишним отступом: {indented:?}"
        );
    }
}

/// One process per room: the client runs as many, and they must match one to one.
#[test]
fn one_unit_per_room() {
    let script = script();
    assert!(script.contains("/etc/olcrtc/srv-1.yaml"));
    assert!(script.contains("/etc/olcrtc/srv-2.yaml"));
    assert!(script.contains("systemctl enable --now olcrtc-srv-1"));
    assert!(script.contains("systemctl enable --now olcrtc-srv-2"));
    assert!(!script.contains("olcrtc-srv-3"));
    assert!(script.contains(r#"id: "https://meet.example.org/olcabc""#));
}

/// The key goes to its own file and the config points at it: only then can the heredoc be quoted,
/// i.e. the shell is guaranteed not to look into the config at all.
#[test]
fn key_goes_to_its_own_file_and_config_heredoc_is_quoted() {
    let script = script();
    assert!(script.contains("key_file: \"olcrtc.key\""));
    assert!(script.contains("<<'YAML'"));
    assert!(script.contains("chmod 600 /etc/olcrtc/olcrtc.key"));
    assert!(script.contains("RESULT::$key"));
}

/// A room from the input field can neither break the YAML nor reach the shell.
#[test]
fn rooms_are_validated_not_escaped() {
    for bad in [
        "https://host/room\nmode: cnc",
        "https://host/room\"",
        "https://host/room; rm -rf /",
        "$(id)",
        "",
    ] {
        let err = build_install_script(&options(&[bad]))
            .expect_err(&format!("пропущена комната «{bad}»"));
        assert!(matches!(err, InstallError::InvalidInput(_)), "{err:?}");
    }
}

#[test]
fn unknown_provider_or_transport_is_rejected() {
    let skype = OlcRtcInstallOptions {
        provider: "skype".into(),
        ..options(&["https://h/r"])
    };
    assert!(matches!(
        build_install_script(&skype),
        Err(InstallError::InvalidInput(_))
    ));
    let pigeon = OlcRtcInstallOptions {
        transport: "carrierpigeon".into(),
        ..options(&["https://h/r"])
    };
    assert!(matches!(
        build_install_script(&pigeon),
        Err(InstallError::InvalidInput(_))
    ));
}

/// A reinstall with fewer rooms must not leave stray processes sitting in the old rooms.
#[test]
fn install_reuses_the_same_teardown() {
    assert!(script().contains(&teardown()));
    assert!(build_uninstall_script().contains(&teardown()));
}

#[test]
fn generated_jitsi_rooms_are_unique_and_well_formed() {
    let rooms = generate_jitsi_rooms("meet.example.org", 5);
    assert_eq!(rooms.len(), 5);
    assert_eq!(
        rooms.iter().collect::<std::collections::HashSet<_>>().len(),
        5
    );
    for room in &rooms {
        assert!(room.starts_with("https://meet.example.org/olc"), "{room}");
    }
    // Generated names must pass the same check as hand-typed ones.
    let refs: Vec<&str> = rooms.iter().map(String::as_str).collect();
    assert!(build_install_script(&options(&refs))
        .unwrap()
        .contains(rooms.last().unwrap()));
}

#[test]
fn scripts_parse_as_bash() {
    common::assert_bash_syntax("install", &script());
    common::assert_bash_syntax("uninstall", &build_uninstall_script());
}
