//! Port of `DesktopSelfUpdate.kt` (jvmMain update): commits a staged update once this process has
//! exited. A small script waits for our PID to disappear, moves the rebuilt files over the old ones
//! (the classpath file last -- `delta_bundle::stage` orders the moves), removes what the new build
//! dropped and relaunches YPtun.
//!
//! * The script texts are built exactly like the Kotlin raw strings + `trimIndent()` (see
//!   `util::trim_indent`), CRLF for `.cmd`.
//! * Kotlin's globals become parameters: the OS, the app data dir (`<app_data_dir>/updates` holds
//!   the script), the launcher (`DesktopAppImage::launcher`) and whether the app already runs
//!   elevated (`DesktopElevation.isElevated`).
//! * `is_writable` stands in for `Files.isWritable`: it tries to create a probe file.
//! * The swapper's stdin/stdout/stderr go to null (Kotlin left an unread pipe).

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use regex_lite::Regex;

use crate::delta_bundle::DeltaPlan;
use crate::desktop_native_assets::DesktopOs;
use crate::util::trim_indent;

/// Last line of every .cmd: `(goto)` with a missing label aborts the script, so cmd never reads
/// past this line of a batch file that deleted itself.
pub const SELF_DELETE: &str = "(goto) 2>nul & del \"%~f0\"";
pub const CMD_SCRIPT: &str = "yptun-apply-update.cmd";
pub const SH_SCRIPT: &str = "yptun-apply-update.sh";
pub const ELEVATION_REFUSED: &str =
    "Administrator rights were not granted - the update was not applied";
pub const PORTABLE_WINDOWS_ONLY: &str = "portable self-update is Windows-only";
pub const SILENT_INSTALL_WINDOWS_ONLY: &str = "silent install is Windows-only";

/// Indent of the Kotlin raw strings these scripts come from.
const IND: &str = "            ";

/// A Kotlin raw string block with every line at the 12-space source indent (interpolated
/// multi-line values keep their own unindented continuation lines, as in Kotlin).
fn raw(lines: &[String]) -> String {
    let mut s = String::from("\n");
    for l in lines {
        s.push_str(IND);
        s.push_str(l);
        s.push('\n');
    }
    s.push_str(IND);
    s
}

fn abs(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|d| d.join(p))
            .unwrap_or_else(|_| p.to_path_buf())
    }
}

fn disp(p: &Path) -> String {
    abs(p).display().to_string()
}

fn parent_disp(p: &Path) -> String {
    let a = abs(p);
    a.parent().map(|x| x.display().to_string()).unwrap_or_else(|| a.display().to_string())
}

fn file_name(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// `<app_data_dir>/updates`.
pub fn script_dir(app_data_dir: &Path) -> PathBuf {
    app_data_dir.join("updates")
}

fn ensure_script_dir(app_data_dir: &Path) -> Result<PathBuf, String> {
    let dir = script_dir(app_data_dir);
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// Whether this process may create files in `dir`.
pub fn is_writable(dir: &Path) -> bool {
    if !dir.is_dir() {
        return false;
    }
    let probe = dir.join(format!(".yptun-write-probe-{}", std::process::id()));
    match fs::OpenOptions::new().write(true).create(true).truncate(true).open(&probe) {
        Ok(f) => {
            drop(f);
            let _ = fs::remove_file(&probe);
            true
        }
        Err(_) => false,
    }
}

/// The cmd block that loops until `pid` is gone.
pub fn windows_wait_for_exit(pid: u32) -> String {
    [
        "@echo off".to_string(),
        ":wait".to_string(),
        tasklist_line(pid),
        "if not errorlevel 1 (".to_string(),
        "  ping -n 2 127.0.0.1 >nul".to_string(),
        "  goto wait".to_string(),
        ")".to_string(),
    ]
    .join("\n")
}

fn tasklist_line(pid: u32) -> String {
    format!(
        "%SystemRoot%\\System32\\tasklist.exe /FI \"PID eq {pid}\" 2>nul | %SystemRoot%\\System32\\find.exe \"{pid}\" >nul"
    )
}

/// YPtun-3.6.2-portable.exe + update to ...3.6.3... -> YPtun-3.6.3-portable.exe; custom names stay.
pub fn renamed_for_version(target: &Path, fresh_name: &str) -> PathBuf {
    let ver = Regex::new(r"\d+\.\d+\.\d+").expect("version regex");
    let Some(new_ver) = ver.find(fresh_name).map(|m| m.as_str().to_string()) else {
        return target.to_path_buf();
    };
    let name = file_name(target);
    let Some(old) = ver.find(&name) else {
        return target.to_path_buf();
    };
    let renamed = format!("{}{}{}", &name[..old.start()], new_ver, &name[old.end()..]);
    target.with_file_name(renamed)
}

/// `writeWindowsScript` text (CRLF).
pub fn windows_swap_script(pid: u32, plan: &DeltaPlan, launcher: Option<&Path>) -> String {
    let moves = plan
        .moves
        .iter()
        .map(|(from, to)| {
            format!(
                "if not exist \"{p}\" mkdir \"{p}\"\r\nmove /y \"{f}\" \"{t}\" >nul",
                p = parent_disp(to),
                f = disp(from),
                t = disp(to)
            )
        })
        .collect::<Vec<_>>()
        .join("\r\n");
    let deletes = plan
        .deletions
        .iter()
        .map(|p| format!("del /f /q \"{}\" >nul 2>&1", disp(p)))
        .collect::<Vec<_>>()
        .join("\r\n");
    let relaunch = launcher
        .map(|l| format!("start \"\" \"{}\"", disp(l)))
        .unwrap_or_default();
    let lines = vec![
        "@echo off".to_string(),
        ":wait".to_string(),
        tasklist_line(pid),
        "if not errorlevel 1 (".to_string(),
        "  ping -n 2 127.0.0.1 >nul".to_string(),
        "  goto wait".to_string(),
        ")".to_string(),
        moves,
        deletes,
        format!("rmdir /s /q \"{}\" >nul 2>&1", disp(&plan.staging_dir)),
        relaunch,
        SELF_DELETE.to_string(),
    ];
    trim_indent(&raw(&lines)).replace('\n', "\r\n")
}

/// `writeUnixScript` text.
pub fn unix_swap_script(pid: u32, plan: &DeltaPlan, launcher: Option<&Path>) -> String {
    let moves = plan
        .moves
        .iter()
        .map(|(from, to)| {
            format!(
                "mkdir -p \"{}\" && mv -f \"{}\" \"{}\" || exit 1",
                parent_disp(to),
                disp(from),
                disp(to)
            )
        })
        .collect::<Vec<_>>()
        .join("\n");
    let deletes = plan
        .deletions
        .iter()
        .map(|p| format!("rm -f \"{}\"", disp(p)))
        .collect::<Vec<_>>()
        .join("\n");
    let relaunch = launcher
        .map(|l| format!("\"{}\" >/dev/null 2>&1 &", disp(l)))
        .unwrap_or_default();
    let lines = vec![
        "#!/bin/sh".to_string(),
        format!("while kill -0 {pid} 2>/dev/null; do sleep 0.5; done"),
        moves,
        deletes,
        format!("rm -rf \"{}\"", disp(&plan.staging_dir)),
        relaunch,
        "rm -f \"$0\"".to_string(),
    ];
    trim_indent(&raw(&lines))
}

/// `scheduleReplacePortable` script text (CRLF).
pub fn portable_script(pid: u32, new_exe: &Path, target: Option<&Path>) -> String {
    let fresh = disp(new_exe);
    let swap = match target {
        None => format!("start \"\" \"{fresh}\""),
        Some(t) => {
            let target_abs = abs(t);
            let dest_path = renamed_for_version(&target_abs, &file_name(&abs(new_exe)));
            let dest = dest_path.display().to_string();
            let drop_old = if dest_path != target_abs {
                format!("del /f /q \"{}\" >nul 2>&1", target_abs.display())
            } else {
                String::new()
            };
            let lines = vec![
                "set n=0".to_string(),
                ":swap".to_string(),
                format!("move /y \"{fresh}\" \"{dest}\" >nul 2>&1"),
                "if not errorlevel 1 goto moved".to_string(),
                "set /a n+=1".to_string(),
                "if %n% GEQ 30 goto fallback".to_string(),
                "ping -n 2 127.0.0.1 >nul".to_string(),
                "goto swap".to_string(),
                ":moved".to_string(),
                drop_old,
                format!("start \"\" \"{dest}\""),
                "goto done".to_string(),
                ":fallback".to_string(),
                format!("start \"\" \"{fresh}\""),
                ":done".to_string(),
            ];
            trim_indent(&raw(&lines))
        }
    };
    (windows_wait_for_exit(pid) + "\n" + &swap + "\n" + SELF_DELETE).replace('\n', "\r\n")
}

/// `scheduleInstaller` script text (CRLF): silent Inno Setup / MSI run, delete, relaunch.
pub fn installer_script(pid: u32, installer: &Path, launcher: Option<&Path>) -> String {
    let file = disp(installer);
    let run = if file_name(installer).to_lowercase().ends_with(".msi") {
        format!("msiexec /i \"{file}\" /qn /norestart")
    } else {
        format!("\"{file}\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-")
    };
    let relaunch = launcher
        .map(|l| format!("start \"\" \"{}\"", disp(l)))
        .unwrap_or_default();
    [
        windows_wait_for_exit(pid),
        format!("start \"\" /wait {run}"),
        format!("del /f /q \"{file}\" >nul 2>&1"),
        relaunch,
        SELF_DELETE.to_string(),
    ]
    .join("\n")
    .replace('\n', "\r\n")
}

/// The command that runs the swapper, elevating only when needed.
pub fn start_command(os: DesktopOs, script: &Path, needs_elevation: bool) -> Vec<String> {
    let s = disp(script);
    if os == DesktopOs::Windows && needs_elevation {
        let ps = "try { Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','\"".to_string()
            + &s
            + "\"' -Verb RunAs -WindowStyle Hidden -ErrorAction Stop } catch { exit 1 }";
        vec![
            "powershell.exe".into(),
            "-NoProfile".into(),
            "-NonInteractive".into(),
            "-Command".into(),
            ps,
        ]
    } else if os == DesktopOs::Windows {
        // `start "" x.cmd` runs the script under `cmd /K`, so its console window stayed open after the
        // update. An explicit inner `cmd /c` exits with the script; /b keeps it hidden.
        ["cmd.exe", "/c", "start", "", "/b", "cmd.exe", "/c"].into_iter().map(String::from).chain([s]).collect()
    } else if needs_elevation {
        vec!["pkexec".into(), "sh".into(), s]
    } else {
        vec!["sh".into(), s]
    }
}

fn start(dir: &Path, os: DesktopOs, script: &Path, needs_elevation: bool) -> Result<(), String> {
    let command = start_command(os, script, needs_elevation);
    let mut child = Command::new(&command[0])
        .args(&command[1..])
        .current_dir(dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;
    if os == DesktopOs::Windows && needs_elevation {
        // Start-Process returns once the UAC prompt is answered; a refusal exits non-zero.
        let deadline = Instant::now() + Duration::from_secs(120);
        let granted = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status.success(),
                Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(100)),
                _ => break false,
            }
        };
        if !granted {
            let _ = child.kill();
            let _ = fs::remove_file(script);
            return Err(ELEVATION_REFUSED.to_string());
        }
    }
    Ok(())
}

/// `scheduleSwap`: write the swapper for `plan` and start it (elevated when the image root is not
/// writable). Safe before the app's own shutdown: the script polls for the process to exit first.
pub fn schedule_swap(
    app_data_dir: &Path,
    os: DesktopOs,
    plan: &DeltaPlan,
    launcher: Option<&Path>,
) -> Result<(), String> {
    let pid = std::process::id();
    let dir = ensure_script_dir(app_data_dir)?;
    let script = if os == DesktopOs::Windows {
        let script = dir.join(CMD_SCRIPT);
        fs::write(&script, windows_swap_script(pid, plan, launcher)).map_err(|e| e.to_string())?;
        script
    } else {
        let script = dir.join(SH_SCRIPT);
        fs::write(&script, unix_swap_script(pid, plan, launcher)).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&script, fs::Permissions::from_mode(0o700));
        }
        script
    };
    start(&dir, os, &script, !is_writable(&plan.root_dir))
}

/// `scheduleReplacePortable` (Windows only): put `new_exe` in place of `target` and run it; with no
/// target the download is run from where it lies.
pub fn schedule_replace_portable(
    app_data_dir: &Path,
    os: DesktopOs,
    new_exe: &Path,
    target: Option<&Path>,
) -> Result<(), String> {
    if os != DesktopOs::Windows {
        return Err(PORTABLE_WINDOWS_ONLY.to_string());
    }
    let pid = std::process::id();
    let dir = ensure_script_dir(app_data_dir)?;
    let script = dir.join(CMD_SCRIPT);
    fs::write(&script, portable_script(pid, new_exe, target)).map_err(|e| e.to_string())?;
    let needs_elevation = target.is_some_and(|t| match abs(t).parent() {
        Some(p) => !is_writable(p),
        None => true,
    });
    start(&dir, os, &script, needs_elevation)
}

/// `scheduleInstaller` (Windows only): run the installer silently, delete it, start YPtun again.
/// Elevated unless the app already is; a declined UAC prompt is an Err while the app still runs.
pub fn schedule_installer(
    app_data_dir: &Path,
    os: DesktopOs,
    installer: &Path,
    launcher: Option<&Path>,
    already_elevated: bool,
) -> Result<(), String> {
    if os != DesktopOs::Windows {
        return Err(SILENT_INSTALL_WINDOWS_ONLY.to_string());
    }
    let pid = std::process::id();
    let dir = ensure_script_dir(app_data_dir)?;
    let script = dir.join(CMD_SCRIPT);
    fs::write(&script, installer_script(pid, installer, launcher)).map_err(|e| e.to_string())?;
    start(&dir, os, &script, !already_elevated)
}
