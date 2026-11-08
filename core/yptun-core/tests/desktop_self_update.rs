use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::delta_bundle::DeltaPlan;
use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_self_update::{
    installer_script, is_writable, portable_script, renamed_for_version, schedule_installer,
    schedule_replace_portable, start_command, unix_swap_script, windows_swap_script,
    windows_wait_for_exit, SELF_DELETE,
};

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&d).unwrap();
    d
}

fn d(p: &PathBuf) -> String {
    p.display().to_string()
}

fn plan(root: &PathBuf) -> DeltaPlan {
    DeltaPlan {
        moves: vec![(root.join(".yptun-update").join("a.jar"), root.join("app").join("a.jar"))],
        deletions: vec![root.join("app").join("old.jar")],
        staging_dir: root.join(".yptun-update"),
        root_dir: root.clone(),
    }
}

#[test]
fn renames_versioned_portable() {
    let dir = std::env::temp_dir();
    let target = dir.join("YPtun-3.6.2-portable.exe");
    assert_eq!(
        renamed_for_version(&target, "YPtun-3.6.3-portable.exe"),
        dir.join("YPtun-3.6.3-portable.exe")
    );
    let custom = dir.join("MyVPN.exe");
    assert_eq!(renamed_for_version(&custom, "YPtun-3.6.3-portable.exe"), custom);
    assert_eq!(renamed_for_version(&target, "YPtun-portable.exe"), target);
}

#[test]
fn wait_block_is_exact() {
    assert_eq!(
        windows_wait_for_exit(42),
        "@echo off\n:wait\n%SystemRoot%\\System32\\tasklist.exe /FI \"PID eq 42\" 2>nul | %SystemRoot%\\System32\\find.exe \"42\" >nul\nif not errorlevel 1 (\n  ping -n 2 127.0.0.1 >nul\n  goto wait\n)"
    );
}

#[test]
fn unix_script_single_move() {
    let root = temp_dir("swap-unix");
    let p = plan(&root);
    let launcher = root.join("bin").join("YPtun");
    let s = unix_swap_script(7, &p, Some(&launcher));
    let expected = format!(
        "#!/bin/sh\nwhile kill -0 7 2>/dev/null; do sleep 0.5; done\nmkdir -p \"{}\" && mv -f \"{}\" \"{}\" || exit 1\nrm -f \"{}\"\nrm -rf \"{}\"\n\"{}\" >/dev/null 2>&1 &\nrm -f \"$0\"",
        d(&root.join("app")),
        d(&p.moves[0].0),
        d(&p.moves[0].1),
        d(&p.deletions[0]),
        d(&p.staging_dir),
        d(&launcher)
    );
    assert_eq!(s, expected);
    fs::remove_dir_all(&root).ok();
}

#[test]
fn windows_script_is_crlf() {
    let root = temp_dir("swap-win");
    let p = plan(&root);
    let s = windows_swap_script(9, &p, None);
    assert!(!s.replace("\r\n", "").contains('\n'));
    assert!(s.contains(&format!("move /y \"{}\" \"{}\" >nul", d(&p.moves[0].0), d(&p.moves[0].1))));
    assert!(s.contains(&format!("del /f /q \"{}\" >nul 2>&1", d(&p.deletions[0]))));
    assert!(s.contains(&format!("rmdir /s /q \"{}\" >nul 2>&1", d(&p.staging_dir))));
    assert!(s.trim_end().ends_with(SELF_DELETE));
    fs::remove_dir_all(&root).ok();
}

#[test]
fn installer_scripts() {
    let root = temp_dir("inst");
    let exe = root.join("YPtun-setup.exe");
    let f = d(&exe);
    let expected = [
        windows_wait_for_exit(5),
        format!("start \"\" /wait \"{f}\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-"),
        format!("del /f /q \"{f}\" >nul 2>&1"),
        String::new(),
        SELF_DELETE.to_string(),
    ]
    .join("\n")
    .replace('\n', "\r\n");
    assert_eq!(installer_script(5, &exe, None), expected);
    let msi = root.join("YPtun.MSI");
    let s = installer_script(5, &msi, Some(&root.join("YPtun.exe")));
    assert!(s.contains(&format!("start \"\" /wait msiexec /i \"{}\" /qn /norestart", d(&msi))));
    assert!(s.contains(&format!("start \"\" \"{}\"", d(&root.join("YPtun.exe")))));
    fs::remove_dir_all(&root).ok();
}

#[test]
fn portable_scripts() {
    let root = temp_dir("portable");
    let fresh = root.join("updates").join("YPtun-3.6.3-portable.exe");
    let expected = format!("{}\nstart \"\" \"{}\"\n{}", windows_wait_for_exit(3), d(&fresh), SELF_DELETE)
        .replace('\n', "\r\n");
    assert_eq!(portable_script(3, &fresh, None), expected);

    let target = root.join("YPtun-3.6.2-portable.exe");
    let dest = root.join("YPtun-3.6.3-portable.exe");
    let s = portable_script(3, &fresh, Some(&target));
    assert!(s.contains(&format!("move /y \"{}\" \"{}\" >nul 2>&1", d(&fresh), d(&dest))));
    assert!(s.contains(&format!("del /f /q \"{}\" >nul 2>&1", d(&target))));
    assert!(s.contains("if %n% GEQ 30 goto fallback"));
    assert!(!s.replace("\r\n", "").contains('\n'));

    let custom = root.join("MyVPN.exe");
    let s = portable_script(3, &fresh, Some(&custom));
    assert!(!s.contains("del /f /q"));
    assert!(s.contains(&format!("start \"\" \"{}\"", d(&custom))));
    fs::remove_dir_all(&root).ok();
}

#[test]
fn start_commands() {
    let root = temp_dir("startcmd");
    let script = root.join("yptun-apply-update.cmd");
    let s = d(&script);
    assert_eq!(start_command(DesktopOs::Linux, &script, false), vec!["sh".to_string(), s.clone()]);
    assert_eq!(
        start_command(DesktopOs::Linux, &script, true),
        vec!["pkexec".to_string(), "sh".to_string(), s.clone()]
    );
    assert_eq!(
        start_command(DesktopOs::Windows, &script, false),
        vec!["cmd.exe", "/c", "start", "/min", "", s.as_str()]
            .into_iter()
            .map(String::from)
            .collect::<Vec<_>>()
    );
    let elevated = start_command(DesktopOs::Windows, &script, true);
    assert_eq!(elevated[0], "powershell.exe");
    let expected_ps = "try { Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','\"".to_string()
        + &s
        + "\"' -Verb RunAs -WindowStyle Hidden -ErrorAction Stop } catch { exit 1 }";
    assert_eq!(elevated[4], expected_ps);
    fs::remove_dir_all(&root).ok();
}

#[test]
fn windows_only_paths_refuse_elsewhere() {
    let root = temp_dir("winonly");
    let exe = root.join("a.exe");
    assert_eq!(
        schedule_replace_portable(&root, DesktopOs::Linux, &exe, None),
        Err("portable self-update is Windows-only".to_string())
    );
    assert_eq!(
        schedule_installer(&root, DesktopOs::MacOs, &exe, None, false),
        Err("silent install is Windows-only".to_string())
    );
    assert!(is_writable(&root));
    assert!(!is_writable(&root.join("missing")));
    fs::remove_dir_all(&root).ok();
}
