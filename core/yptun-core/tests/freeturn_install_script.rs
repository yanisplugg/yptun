//! Ported from FreeturnInstallScriptTest.kt. The install script runs as root on someone else's VPS,
//! where a broken quote or a shifted heredoc terminator is found too late - so this checks exactly
//! what breaks silently: indentation of the glued pieces, heredoc closing, and that the tunnel's
//! port matches the server's `-connect`.

mod common;

use yptun_core::installer::freeturn::*;
use yptun_core::util::kotlin_lines;

fn script(exit: FreeturnExit, port: i32) -> String {
    build_install_script(&FreeturnInstallOptions {
        host: "203.0.113.7".into(),
        freeturn_port: port,
        exit,
        ..Default::default()
    })
}

fn all_scripts() -> Vec<(String, String)> {
    let mut out: Vec<_> = FreeturnExit::ALL
        .iter()
        .map(|e| (e.label().to_string(), script(*e, 56000)))
        .collect();
    out.push(("uninstall".into(), build_uninstall_script(56000)));
    out
}

/// A heredoc terminator closes the document ONLY in column zero - otherwise everything below is eaten.
#[test]
fn heredoc_terminators_start_at_column_zero() {
    for (exit, text) in all_scripts() {
        let lines = kotlin_lines(&text);
        for terminator in ["EOF", "UNIT", "RUNNER", "AWGUNIT"] {
            let opens = lines
                .iter()
                .filter(|l| l.trim_end().ends_with(&format!("<<{terminator}")))
                .count();
            let closes = lines.iter().filter(|l| **l == terminator).count();
            assert_eq!(
                opens, closes,
                "{exit}: {terminator} открыт {opens} раз, закрыт {closes}"
            );
        }
    }
}

/// The pieces are glued after trimIndent - no line may drift right.
#[test]
fn no_stray_indentation() {
    for (exit, text) in all_scripts() {
        let indented: Vec<_> = kotlin_lines(&text)
            .into_iter()
            .filter(|l| l.starts_with("    "))
            .collect();
        assert!(
            indented.is_empty(),
            "{exit}: строки с лишним отступом: {indented:?}"
        );
    }
}

/// The backend port in -connect must be the port the tunnel listens on.
#[test]
fn backend_port_matches_connect() {
    let port = 56000;
    let expected = 51820 + (port % 250 + 1);
    assert!(
        script(FreeturnExit::WireGuard, port).contains(&format!("-connect 127.0.0.1:{expected}"))
    );
    assert!(script(FreeturnExit::WireGuard, port).contains(&format!("ListenPort = {expected}")));
    assert!(
        script(FreeturnExit::AmneziaWG, port).contains(&format!("-connect 127.0.0.1:{expected}"))
    );
    assert!(script(FreeturnExit::AmneziaWG, port).contains("listen_port=%s"));
    assert!(script(FreeturnExit::AmneziaWG, port).contains(&format!("\"{expected}\"")));
}

/// The AmneziaWG exit must report its obfuscation parameters - without them the client builds plain WG.
#[test]
fn amnezia_exit_reports_obfuscation_params() {
    let awg = script(FreeturnExit::AmneziaWG, 56000);
    assert!(awg
        .contains("RESULT::$key|$spub|$cpriv|10.7.1.2/32|$jc,$jmin,$jmax,$s1,$s2,$h1,$h2,$h3,$h4"));
    // jc/s1/h1 are known only to UAPI: wg setconf rejects them, so the config goes through the socket.
    assert!(awg.contains("iface=ftawg56000"));
    // The outer shell expands $iface when it writes the runner script, so here it is still text.
    assert!(awg.contains("socat - UNIX-CONNECT:/var/run/amneziawg/$iface.sock"));
    assert!(!awg.contains("wg setconf"));
    // The WireGuard exit has no parameter tail.
    let wg = script(FreeturnExit::WireGuard, 56000);
    assert!(wg.contains("RESULT::$key|$spub|$cpriv|10.7.1.2/32\""));
    assert!(!wg.contains("$jc"));
}

/// Uninstall must remove BOTH possible backends: the exit type cannot be told from the port.
#[test]
fn uninstall_removes_both_backends() {
    let s = build_uninstall_script(56000);
    assert!(s.contains("wg-quick down ftwg56000"));
    assert!(s.contains("systemctl disable --now ftawg56000"));
    assert!(s.contains("rm -f /etc/wireguard/ftwg56000.conf"));
    assert!(s.contains("ufw delete allow 56000/udp"));
    assert!(s.contains("REMOVED::56000"));
}

/// The binaries are shared by every port: removing them unconditionally kills neighbour servers.
#[test]
fn uninstall_keeps_shared_binaries_while_other_servers_remain() {
    let s = build_uninstall_script(56000);
    let guard = s
        .find("ls /etc/systemd/system/freeturn-server-*.service")
        .expect("guard");
    let removal = s
        .find("rm -f /usr/local/bin/freeturn-server")
        .expect("removal");
    assert!(
        guard < removal,
        "удаление бинарников не под проверкой других служб"
    );
}

/// Install reuses the very same teardown - otherwise reinstall and uninstall drift apart.
#[test]
fn install_reuses_the_same_teardown() {
    let teardown = teardown_block(56000);
    assert!(script(FreeturnExit::WireGuard, 56000).contains(&teardown));
    assert!(build_uninstall_script(56000).contains(&teardown));
}

/// Different ports on one VPS share neither a subnet nor an interface.
#[test]
fn ports_get_their_own_subnet_and_interface() {
    let a = script(FreeturnExit::AmneziaWG, 56000);
    let b = script(FreeturnExit::AmneziaWG, 56001);
    assert!(a.contains("ftawg56000") && b.contains("ftawg56001"));
    assert!(a.contains("10.7.1.1/24") && b.contains("10.7.2.1/24"));
}

/// The obf profile lands bare in the unit file: only letters and digits survive, blank means the default.
#[test]
fn obf_profile_is_filtered_to_a_safe_token() {
    let with = |p: &str| {
        build_install_script(&FreeturnInstallOptions {
            obf_profile: p.into(),
            ..Default::default()
        })
    };
    assert!(with("RTP-Opus; rm -rf /").contains("-obf-profile rtpopusrmrf -obf-key"));
    assert!(with(" -;").contains("-obf-profile rtpopus -obf-key"));
}

#[test]
fn scripts_parse_as_bash() {
    for (exit, text) in all_scripts() {
        common::assert_bash_syntax(&exit, &text);
    }
}
