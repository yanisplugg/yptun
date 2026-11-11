//! Desktop app helpers, ported from `org.olcbox.app.desktop`:
//! DesktopPaths, DesktopFileLog, DesktopRuntimeMode, DesktopElevation, DesktopRender,
//! DesktopSingleInstance, DesktopToast and the luma part of QrImageDecoder.
//!
//! Everything that decides something is a plain function so it can be tested; the thin wrappers
//! that read the environment / touch the OS sit next to it.

use std::fs;
use std::io::{self, BufRead, BufReader, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::desktop_native_assets::DesktopOs;

// ---- DesktopPaths ---------------------------------------------------------------------------

/// `os.name` -> DesktopOs, same rules as DesktopPaths.os.
pub fn os_from_name(name: &str) -> DesktopOs {
    let n = name.to_lowercase();
    if n.contains("mac") || n.contains("darwin") {
        DesktopOs::MacOs
    } else if n.contains("windows") {
        DesktopOs::Windows
    } else if n.contains("linux") {
        DesktopOs::Linux
    } else {
        DesktopOs::Other
    }
}

/// The OS this binary runs on (`std::env::consts::OS` is "macos" / "windows" / "linux" / ...).
pub fn current_os() -> DesktopOs {
    os_from_name(std::env::consts::OS)
}

/// `user.home`: USERPROFILE on Windows, HOME elsewhere.
pub fn home_dir() -> PathBuf {
    let v = if cfg!(windows) {
        std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME"))
    } else {
        std::env::var_os("HOME")
    };
    v.map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

/// The folder the data dir lives in.
pub fn app_data_base(os: DesktopOs, home: &Path, app_data: Option<&str>) -> PathBuf {
    match os {
        DesktopOs::MacOs => home.join("Library").join("Application Support"),
        DesktopOs::Windows => match app_data.filter(|s| !s.trim().is_empty()) {
            Some(a) => PathBuf::from(a),
            None => home.join("AppData").join("Roaming"),
        },
        DesktopOs::Linux | DesktopOs::Other => home.to_path_buf(),
    }
}

fn hidden_data_dir(os: DesktopOs) -> bool {
    matches!(os, DesktopOs::Linux | DesktopOs::Other)
}

/// `.yptun` on Linux/other, `YPtun` on Windows/macOS.
pub fn app_data_dir_name(os: DesktopOs) -> &'static str {
    if hidden_data_dir(os) {
        ".yptun"
    } else {
        "YPtun"
    }
}

/// The pre-rebrand dir name, migrated once.
pub fn legacy_app_data_dir_name(os: DesktopOs) -> &'static str {
    if hidden_data_dir(os) {
        ".olcbox"
    } else {
        "Olcbox"
    }
}

/// DesktopPaths.standardAppDataDir with explicit inputs: migrates the legacy dir and creates the dir.
pub fn standard_app_data_dir_in(os: DesktopOs, home: &Path, app_data: Option<&str>) -> io::Result<PathBuf> {
    let base = app_data_base(os, home, app_data);
    let dir = base.join(app_data_dir_name(os));
    if !dir.exists() {
        let legacy = base.join(legacy_app_data_dir_name(os));
        if legacy.exists() {
            let _ = fs::rename(&legacy, &dir);
        }
    }
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// The regular data dir of this machine (not the portable copy).
pub fn standard_app_data_dir() -> PathBuf {
    let os = current_os();
    let home = home_dir();
    let app_data = std::env::var("APPDATA").ok();
    match standard_app_data_dir_in(os, &home, app_data.as_deref()) {
        Ok(dir) => dir,
        Err(_) => app_data_base(os, &home, app_data.as_deref()).join(app_data_dir_name(os)),
    }
}

/// DesktopPaths.appDataDir: the standard dir, or the private copy of the portable bundle.
pub fn app_data_dir() -> PathBuf {
    crate::portable_data::resolve(standard_app_data_dir)
}

// ---- DesktopFileLog ---------------------------------------------------------------------------

/// Size past which yptun.log is dropped instead of appended to.
pub const FILE_LOG_MAX_BYTES: u64 = 32 * 1024 * 1024;
pub const FILE_LOG_NAME: &str = "yptun.log";
pub const LINE_SEPARATOR: &str = if cfg!(windows) { "\r\n" } else { "\n" };

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// Days since 1970-01-01 for a proleptic Gregorian date (inverse of civil_from_days).
pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = (y - era * 400) as u64;
    let mp = (if m > 2 { m - 3 } else { m + 9 }) as u64;
    let doy = (153 * mp + 2) / 5 + d as u64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe as i64 - 719_468
}

/// `yyyy-MM-dd HH:mm:ss.SSS` for a unix time in ms shifted by `offset_secs`.
pub fn format_timestamp_with_offset(unix_ms: i64, offset_secs: i64) -> String {
    let local_ms = unix_ms + offset_secs * 1000;
    let secs = local_ms.div_euclid(1000);
    let ms = local_ms.rem_euclid(1000);
    let (y, mo, d) = civil_from_days(secs.div_euclid(86_400));
    let sod = secs.rem_euclid(86_400);
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}", y, mo, d, sod / 3600, (sod / 60) % 60, sod % 60, ms)
}

/// Offset of the local zone from UTC in seconds at `unix_secs`, asked from the OS
/// (DST included). 0 when the OS cannot tell.
pub fn local_offset_seconds(unix_secs: i64) -> i64 {
    os_local_offset(unix_secs).filter(|o| o.abs() <= 18 * 3600).unwrap_or(0)
}

/// `yyyy-MM-dd HH:mm:ss.SSS` in local time, like Kotlin's SimpleDateFormat in the default zone.
pub fn format_timestamp(unix_ms: u64) -> String {
    let ms = unix_ms as i64;
    format_timestamp_with_offset(ms, local_offset_seconds(ms.div_euclid(1000)))
}

#[cfg(windows)]
fn os_local_offset(unix_secs: i64) -> Option<i64> {
    #[repr(C)]
    #[derive(Default, Clone, Copy)]
    #[allow(dead_code)]
    struct WinSystemTime {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn SystemTimeToTzSpecificLocalTime(
            zone: *const std::ffi::c_void,
            utc: *const WinSystemTime,
            local: *mut WinSystemTime,
        ) -> i32;
    }
    let (y, mo, d) = civil_from_days(unix_secs.div_euclid(86_400));
    if !(1601..=30827).contains(&y) {
        return None;
    }
    let sod = unix_secs.rem_euclid(86_400);
    let utc = WinSystemTime {
        year: y as u16,
        month: mo as u16,
        day_of_week: 0,
        day: d as u16,
        hour: (sod / 3600) as u16,
        minute: ((sod / 60) % 60) as u16,
        second: (sod % 60) as u16,
        milliseconds: 0,
    };
    let mut local = WinSystemTime::default();
    let ok = unsafe { SystemTimeToTzSpecificLocalTime(std::ptr::null(), &utc, &mut local) };
    if ok == 0 {
        return None;
    }
    let local_secs = days_from_civil(local.year as i64, local.month as u32, local.day as u32) * 86_400
        + local.hour as i64 * 3600
        + local.minute as i64 * 60
        + local.second as i64;
    Some(local_secs - unix_secs)
}

#[cfg(unix)]
fn os_local_offset(unix_secs: i64) -> Option<i64> {
    use std::os::raw::{c_char, c_int, c_long};
    #[repr(C)]
    #[allow(dead_code)]
    struct Tm {
        tm_sec: c_int,
        tm_min: c_int,
        tm_hour: c_int,
        tm_mday: c_int,
        tm_mon: c_int,
        tm_year: c_int,
        tm_wday: c_int,
        tm_yday: c_int,
        tm_isdst: c_int,
        tm_gmtoff: c_long,
        tm_zone: *const c_char,
    }
    unsafe extern "C" {
        fn localtime_r(time: *const c_long, out: *mut Tm) -> *mut Tm;
    }
    let t = c_long::try_from(unix_secs).ok()?;
    let mut tm = Tm {
        tm_sec: 0,
        tm_min: 0,
        tm_hour: 0,
        tm_mday: 0,
        tm_mon: 0,
        tm_year: 0,
        tm_wday: 0,
        tm_yday: 0,
        tm_isdst: 0,
        tm_gmtoff: 0,
        tm_zone: std::ptr::null(),
    };
    let res = unsafe { localtime_r(&t, &mut tm) };
    if res.is_null() {
        None
    } else {
        Some(tm.tm_gmtoff as i64)
    }
}

#[cfg(not(any(windows, unix)))]
fn os_local_offset(_unix_secs: i64) -> Option<i64> {
    None
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn file_log_line(timestamp: &str, message: &str) -> String {
    format!("{} {}{}", timestamp, message, LINE_SEPARATOR)
}

/// Appends one line, dropping the file first when it grew past FILE_LOG_MAX_BYTES.
pub fn append_log_to(path: &Path, timestamp: &str, message: &str) -> io::Result<()> {
    if let Ok(meta) = fs::metadata(path) {
        if meta.len() > FILE_LOG_MAX_BYTES {
            let _ = fs::remove_file(path);
        }
    }
    let mut f = fs::OpenOptions::new().create(true).append(true).open(path)?;
    f.write_all(file_log_line(timestamp, message).as_bytes())
}

/// `<data dir>/yptun.log`, or None when the dir cannot be created.
pub fn file_log_path() -> Option<PathBuf> {
    static PATH: OnceLock<Option<PathBuf>> = OnceLock::new();
    PATH.get_or_init(|| {
        let path = app_data_dir().join(FILE_LOG_NAME);
        let parent = path.parent()?.to_path_buf();
        fs::create_dir_all(parent).ok()?;
        Some(path)
    })
    .clone()
}

/// DesktopFileLog.append / appendToYptunLog. Never fails.
pub fn append_to_yptun_log(message: &str) {
    if let Some(path) = file_log_path() {
        let _ = append_log_to(&path, &format_timestamp(now_ms()), message);
    }
}

// ---- DesktopRuntimeMode -----------------------------------------------------------------------

pub const PORTABLE_MARKER_FILE_NAME: &str = ".portable";
/// Set by the portable launcher to the .exe the user started.
pub const PORTABLE_EXE_ENV: &str = "YPTUN_PORTABLE_EXE";

pub fn portable_executable_from(value: Option<&str>) -> Option<PathBuf> {
    let v = value?;
    if v.trim().is_empty() {
        return None;
    }
    let p = PathBuf::from(v);
    if p.is_file() {
        Some(p)
    } else {
        None
    }
}

/// The portable .exe the user double-clicked (the file a self-update replaces).
pub fn portable_executable() -> Option<PathBuf> {
    portable_executable_from(std::env::var(PORTABLE_EXE_ENV).ok().as_deref())
}

/// A real launcher, not a JDK one (gradle :run / IDE).
pub fn is_real_launcher(command: &str) -> bool {
    let lower = command.to_lowercase();
    lower.ends_with(".exe") && !lower.ends_with("java.exe") && !lower.ends_with("javaw.exe")
}

pub fn launcher_dir_from(command: Option<&str>) -> Option<PathBuf> {
    let c = command?;
    if !is_real_launcher(c) {
        return None;
    }
    Path::new(c).parent().map(|p| p.to_path_buf())
}

/// Directory the running launcher sits in.
pub fn launcher_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    launcher_dir_from(exe.to_str())
}

/// ProgramFiles, ProgramFiles(x86), ProgramW6432, `%LOCALAPPDATA%\Programs`, lowercased.
pub fn install_roots(
    program_files: Option<&str>,
    program_files_x86: Option<&str>,
    program_w6432: Option<&str>,
    local_app_data: Option<&str>,
) -> Vec<String> {
    let mut out = Vec::new();
    for v in [program_files, program_files_x86, program_w6432].into_iter().flatten() {
        out.push(v.to_lowercase());
    }
    if let Some(l) = local_app_data {
        out.push(format!("{}\\Programs", l).to_lowercase());
    }
    out
}

pub fn env_install_roots() -> Vec<String> {
    let get = |k: &str| std::env::var(k).ok();
    let (a, b, c, d) = (get("ProgramFiles"), get("ProgramFiles(x86)"), get("ProgramW6432"), get("LOCALAPPDATA"));
    install_roots(a.as_deref(), b.as_deref(), c.as_deref(), d.as_deref())
}

pub fn is_under_install_root(dir: &Path, roots: &[String]) -> bool {
    let abs = if dir.is_absolute() {
        dir.to_path_buf()
    } else {
        std::env::current_dir().map(|c| c.join(dir)).unwrap_or_else(|_| dir.to_path_buf())
    };
    let path = abs.to_string_lossy().to_lowercase();
    roots.iter().any(|r| path.starts_with(r.as_str()))
}

/// An Inno `unins*.exe` next to the launcher means "installed", wherever that is.
pub fn has_uninstaller(dir: &Path) -> bool {
    let Ok(entries) = fs::read_dir(dir) else {
        return false;
    };
    entries.flatten().any(|e| {
        let name = e.file_name().to_string_lossy().to_lowercase();
        name.starts_with("unins") && name.ends_with(".exe")
    })
}

/// DesktopRuntimeMode.isPortable with explicit inputs.
pub fn decide_portable(is_windows: bool, launcher: Option<&Path>, roots: &[String]) -> bool {
    if !is_windows {
        return false;
    }
    let Some(dir) = launcher else {
        return false;
    };
    if dir.join(PORTABLE_MARKER_FILE_NAME).exists() {
        return true;
    }
    if has_uninstaller(dir) {
        return false;
    }
    !is_under_install_root(dir, roots)
}

pub fn is_portable() -> bool {
    static PORTABLE: OnceLock<bool> = OnceLock::new();
    *PORTABLE.get_or_init(|| decide_portable(cfg!(windows), launcher_dir().as_deref(), &env_install_roots()))
}

// ---- DesktopElevation -------------------------------------------------------------------------

/// Marks a process we relaunched ourselves (stops a relaunch loop; does NOT auto-connect).
pub const STARTUP_ELEVATION_ARGUMENT: &str = "--olcbox-elevated-at-startup";

#[cfg(windows)]
#[link(name = "shell32")]
unsafe extern "system" {
    fn IsUserAnAdmin() -> i32;
}

/// shell32!IsUserAnAdmin; false off Windows.
pub fn is_elevated() -> bool {
    #[cfg(windows)]
    let elevated = unsafe { IsUserAnAdmin() != 0 };
    #[cfg(not(windows))]
    let elevated = false;
    elevated
}

/// True when switching to TUN mode would have to come back through UAC.
pub fn needs_administrator_for_tun() -> bool {
    cfg!(windows) && !is_elevated()
}

pub fn powershell_literal(s: &str) -> String {
    format!("'{}'", s.replace('\'', "''"))
}

/// One argument quoted the CommandLineToArgvW way.
pub fn windows_command_line_argument(s: &str) -> String {
    if s.is_empty() {
        return String::from("\"\"");
    }
    if !s.chars().any(|c| c.is_whitespace() || c == '"') {
        return s.to_string();
    }
    let mut quoted = String::from("\"");
    let mut pending = 0usize;
    for ch in s.chars() {
        match ch {
            '\\' => pending += 1,
            '"' => {
                quoted.push_str(&"\\".repeat(pending * 2 + 1));
                quoted.push(ch);
                pending = 0;
            }
            _ => {
                quoted.push_str(&"\\".repeat(pending));
                pending = 0;
                quoted.push(ch);
            }
        }
    }
    quoted.push_str(&"\\".repeat(pending * 2));
    quoted.push('"');
    quoted
}

/// `Start-Process -Verb RunAs` script; exits non-zero when UAC is dismissed.
pub fn restart_as_administrator_script(command: &str, arguments: &[String], working_directory: &str) -> String {
    let joined: Vec<String> = arguments.iter().map(|a| windows_command_line_argument(a)).collect();
    let quoted_arguments = powershell_literal(&joined.join(" "));
    let wd_line = if working_directory.trim().is_empty() {
        String::new()
    } else {
        format!("  WorkingDirectory = {}", powershell_literal(working_directory))
    };
    let mut s = String::new();
    s.push_str("$ErrorActionPreference = 'Stop'\n");
    s.push_str("$startArgs = @{\n");
    s.push_str(&format!("  FilePath = {}\n", powershell_literal(command)));
    s.push_str("  Verb = 'RunAs'\n");
    s.push_str(&format!("  ArgumentList = {}\n", quoted_arguments));
    s.push_str(&wd_line);
    s.push('\n');
    s.push_str("}\n");
    s.push_str("Start-Process @startArgs | Out-Null");
    s
}

/// Current arguments without the marker, plus the marker at the end.
pub fn relaunch_arguments(current: &[String]) -> Vec<String> {
    let mut out: Vec<String> = current.iter().filter(|a| a.as_str() != STARTUP_ELEVATION_ARGUMENT).cloned().collect();
    out.push(STARTUP_ELEVATION_ARGUMENT.to_string());
    out
}

/// `<data dir>/settings/ui.json`.
pub fn ui_settings_path(data_dir: &Path) -> PathBuf {
    data_dir.join("settings").join("ui.json")
}

/// True when the saved connection mode is TUN (nothing saved = TUN). The portable never elevates at launch.
pub fn saved_mode_needs_admin(is_portable: bool, ui_json: Option<&str>) -> bool {
    if is_portable {
        return false;
    }
    let mode = ui_json
        .and_then(|t| serde_json::from_str::<serde_json::Value>(t).ok())
        .and_then(|v| v.as_object().and_then(|o| o.get("connectionMode").cloned()))
        .and_then(|v| match v {
            serde_json::Value::String(s) => Some(s),
            serde_json::Value::Number(n) => Some(n.to_string()),
            serde_json::Value::Bool(b) => Some(b.to_string()),
            _ => None,
        })
        .map(|s| s.trim().to_string());
    match mode {
        None => true,
        Some(m) if m.is_empty() => true,
        Some(m) => !m.eq_ignore_ascii_case("proxy"),
    }
}

/// Runs a PowerShell script; true only when it exited 0 within 60 s.
pub fn run_powershell(script: &str) -> bool {
    let child = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn();
    let Ok(mut child) = child else {
        return false;
    };
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return status.success(),
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return false;
                }
                thread::sleep(Duration::from_millis(100));
            }
            Err(_) => return false,
        }
    }
}

fn relaunch_elevated(current_arguments: &[String]) -> bool {
    let Some(command) = std::env::current_exe().ok().and_then(|p| p.to_str().map(|s| s.to_string())) else {
        return false;
    };
    if !is_real_launcher(&command) {
        return false;
    }
    let wd = std::env::current_dir().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    run_powershell(&restart_as_administrator_script(&command, &relaunch_arguments(current_arguments), &wd))
}

/// Relaunches elevated at launch when TUN mode will need it. True = exit now without a window.
pub fn relaunch_elevated_for_startup(args: &[String]) -> bool {
    if !cfg!(windows) {
        return false;
    }
    if args.iter().any(|a| a == STARTUP_ELEVATION_ARGUMENT) {
        return false;
    }
    if is_elevated() {
        return false;
    }
    let portable = is_portable();
    let json = if portable { None } else { fs::read_to_string(ui_settings_path(&app_data_dir())).ok() };
    if !saved_mode_needs_admin(portable, json.as_deref()) {
        return false;
    }
    relaunch_elevated(args)
}

/// The user just switched to TUN mode without rights. True = the elevated copy is starting.
pub fn relaunch_elevated_now() -> bool {
    if !cfg!(windows) || is_elevated() {
        return false;
    }
    let current: Vec<String> = std::env::args().skip(1).collect();
    relaunch_elevated(&current)
}

// ---- DesktopRender ----------------------------------------------------------------------------

pub const RENDER_PROPERTY: &str = "skiko.renderApi";
pub const RENDER_ENVIRONMENT: &str = "SKIKO_RENDER_API";
pub const RENDER_FALLBACK: &str = "SOFTWARE_COMPAT";
pub const RENDER_WATCH_ROUNDS: u32 = 12;
pub const RENDER_WATCH_INTERVAL_MS: u64 = 5_000;
pub const RENDER_BLANK_STEP: i32 = 16;

/// `%LOCALAPPDATA%\YPtun\render-api.txt` (home when LOCALAPPDATA is unset). Not under the data dir.
pub fn render_api_file(local_app_data: Option<&str>, home: &Path) -> PathBuf {
    let base = match local_app_data.filter(|s| !s.trim().is_empty()) {
        Some(l) => PathBuf::from(l),
        None => home.to_path_buf(),
    };
    base.join("YPtun").join("render-api.txt")
}

pub fn default_render_api_file() -> PathBuf {
    render_api_file(std::env::var("LOCALAPPDATA").ok().as_deref(), &home_dir())
}

/// The saved render API word, if any.
pub fn saved_render_api(content: Option<&str>) -> Option<String> {
    let t = content?.trim();
    if t.is_empty() {
        None
    } else {
        Some(t.to_string())
    }
}

pub fn save_render_fallback(file: &Path) -> io::Result<()> {
    if let Some(p) = file.parent() {
        fs::create_dir_all(p)?;
    }
    fs::write(file, RENDER_FALLBACK)
}

/// Each channel differs by less than 6.
pub fn colors_close(a: u32, b: u32) -> bool {
    let ch = |v: u32, s: u32| ((v >> s) & 255) as i32;
    (ch(a, 16) - ch(b, 16)).abs() < 6 && (ch(a, 8) - ch(b, 8)).abs() < 6 && (ch(a, 0) - ch(b, 0)).abs() < 6
}

/// The middle 80% of the window, or None when it is too small to judge.
pub fn blank_capture_area(x: i32, y: i32, width: i32, height: i32) -> Option<(i32, i32, i32, i32)> {
    let area = (x + width / 10, y + height / 10, width * 8 / 10, height * 8 / 10);
    if area.2 < 40 || area.3 < 40 {
        None
    } else {
        Some(area)
    }
}

/// True when the capture is one flat colour (sampled every 16 px).
pub fn is_blank_capture(width: i32, height: i32, rgb: impl Fn(i32, i32) -> u32) -> bool {
    if width <= 0 || height <= 0 {
        return false;
    }
    let first = rgb(0, 0);
    let mut y = 0;
    while y < height {
        let mut x = 0;
        while x < width {
            if !colors_close(first, rgb(x, y)) {
                return false;
            }
            x += RENDER_BLANK_STEP;
        }
        y += RENDER_BLANK_STEP;
    }
    true
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RenderWatchAction {
    Continue,
    Stop,
    SwitchToFallback,
}

/// The watchdog's state between looks: two blank looks in a row switch to software rendering.
#[derive(Debug, Default)]
pub struct BlankWatchdog {
    pub blank_in_a_row: u32,
}

impl BlankWatchdog {
    /// `blank` None = the screenshot failed (the Kotlin thread then gives up).
    pub fn observe(&mut self, visible: bool, blank: Option<bool>) -> RenderWatchAction {
        if !visible {
            return RenderWatchAction::Continue;
        }
        let Some(b) = blank else {
            return RenderWatchAction::Stop;
        };
        self.blank_in_a_row = if b { self.blank_in_a_row + 1 } else { 0 };
        if self.blank_in_a_row >= 2 {
            RenderWatchAction::SwitchToFallback
        } else {
            RenderWatchAction::Continue
        }
    }
}

pub fn render_watch_enabled(os: DesktopOs, chosen_explicitly: bool) -> bool {
    os == DesktopOs::Windows && !chosen_explicitly
}

pub const RENDER_SWITCH_LOG: &str = "render: window stayed blank, switching to SOFTWARE_COMPAT and restarting";

/// The argv of a fresh copy of this launcher (marker appended), or None from a JDK run.
pub fn render_relaunch_command(command: Option<&str>, args: &[String]) -> Option<Vec<String>> {
    let c = command?;
    if !is_real_launcher(c) {
        return None;
    }
    let mut argv = vec![c.to_string()];
    argv.extend(relaunch_arguments(args));
    Some(argv)
}

/// Starts a fresh copy of this launcher with the same arguments.
pub fn render_relaunch() -> bool {
    let command = std::env::current_exe().ok().and_then(|p| p.to_str().map(|s| s.to_string()));
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(argv) = render_relaunch_command(command.as_deref(), &args) else {
        return false;
    };
    let dir = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    Command::new(&argv[0]).args(&argv[1..]).current_dir(dir).spawn().is_ok()
}

// ---- DesktopSingleInstance --------------------------------------------------------------------

/// Fixed loopback port (clear of 10808 SOCKS, 10809 PAC, 10812 HTTP bridge).
pub const SINGLE_INSTANCE_PORT: u16 = 47_638;
pub const SHOW_COMMAND: &str = "show";

/// The share link the app was launched with.
pub fn link_argument(args: &[String]) -> Option<String> {
    args.iter().find(|a| a.contains("://")).cloned()
}

pub fn show_command(link: Option<&str>) -> String {
    match link {
        Some(l) => format!("{} {}", SHOW_COMMAND, l),
        None => SHOW_COMMAND.to_string(),
    }
}

/// `Some(None)` = plain show, `Some(Some(link))` = show and import, None = ignore
/// (anything can connect to a loopback port, an HTTP request line included).
pub fn parse_show_line(line: &str) -> Option<Option<String>> {
    let line = line.trim();
    if line == SHOW_COMMAND {
        return Some(None);
    }
    let link = line.strip_prefix("show ")?;
    if link.contains("://") && !link.contains(' ') {
        Some(Some(link.to_string()))
    } else {
        None
    }
}

fn loopback(port: u16) -> SocketAddr {
    SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port))
}

/// Keeps ONE YPtun running per machine.
pub struct SingleInstance {
    port: u16,
    running: Mutex<Option<Arc<AtomicBool>>>,
}

impl SingleInstance {
    pub fn new(port: u16) -> Self {
        SingleInstance { port, running: Mutex::new(None) }
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    fn try_bind(&self) -> Option<TcpListener> {
        TcpListener::bind(loopback(self.port)).ok()
    }

    /// True = this is the one instance; false = another copy was told to show itself, exit now.
    pub fn claim<F>(&self, args: &[String], on_show_requested: F) -> bool
    where
        F: Fn(Option<String>) + Send + 'static,
    {
        match self.try_bind() {
            Some(listener) => {
                self.start_listening(listener, on_show_requested);
                true
            }
            None => {
                notify_owner(self.port, &show_command(link_argument(args).as_deref()));
                false
            }
        }
    }

    /// Waits for the predecessor (the copy being replaced) to let go; runs anyway after the timeout.
    pub fn claim_after_predecessor_exits<F>(&self, on_show_requested: F, timeout_ms: u64) -> bool
    where
        F: Fn(Option<String>) + Send + 'static,
    {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        while Instant::now() < deadline {
            if let Some(listener) = self.try_bind() {
                self.start_listening(listener, on_show_requested);
                return true;
            }
            thread::sleep(Duration::from_millis(200));
        }
        true
    }

    /// Releases the port so a successor can take over.
    pub fn release(&self) {
        if let Some(flag) = self.running.lock().unwrap().take() {
            flag.store(false, Ordering::SeqCst);
        }
        thread::sleep(Duration::from_millis(120));
    }

    fn start_listening<F>(&self, listener: TcpListener, on_show_requested: F)
    where
        F: Fn(Option<String>) + Send + 'static,
    {
        let flag = Arc::new(AtomicBool::new(true));
        *self.running.lock().unwrap() = Some(flag.clone());
        let _ = listener.set_nonblocking(true);
        let _ = thread::Builder::new().name(String::from("YPtunSingleInstance")).spawn(move || {
            while flag.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => handle_client(stream, &on_show_requested),
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(50)),
                    Err(_) => return,
                }
            }
        });
    }
}

fn handle_client<F: Fn(Option<String>)>(stream: TcpStream, on_show_requested: &F) {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(Duration::from_millis(2_000)));
    let mut line = String::new();
    if BufReader::new(stream).read_line(&mut line).is_err() {
        return;
    }
    if let Some(request) = parse_show_line(&line) {
        on_show_requested(request);
    }
}

/// Best-effort "you are already running, come to the front".
pub fn notify_owner(port: u16, command: &str) {
    if let Ok(mut s) = TcpStream::connect_timeout(&loopback(port), Duration::from_millis(2_000)) {
        let _ = s.write_all(format!("{}\n", command).as_bytes());
        let _ = s.flush();
    }
}

// ---- DesktopToast -----------------------------------------------------------------------------

/// The desktop stand-in for Android's Toast: screens post here, the window shows and consumes it.
#[derive(Debug, Default)]
pub struct DesktopToast {
    message: Mutex<Option<String>>,
}

impl DesktopToast {
    pub const fn new() -> Self {
        DesktopToast { message: Mutex::new(None) }
    }
    pub fn show(&self, text: &str) {
        *self.message.lock().unwrap() = Some(text.to_string());
    }
    pub fn consume(&self) {
        *self.message.lock().unwrap() = None;
    }
    pub fn message(&self) -> Option<String> {
        self.message.lock().unwrap().clone()
    }
}

pub static DESKTOP_TOAST: DesktopToast = DesktopToast::new();

// ---- QrImageDecoder (luma part) ---------------------------------------------------------------

/// ARGB pixels -> luminance bytes, `(r*33 + g*34 + b*33) / 100` as in QrImageDecoder.
/// The QR decoding itself (zxing in Kotlin) needs a QR library and stays in the UI layer.
pub fn qr_luminances(pixels: &[u32]) -> Vec<u8> {
    pixels
        .iter()
        .map(|p| {
            let r = (p >> 16) & 0xFF;
            let g = (p >> 8) & 0xFF;
            let b = p & 0xFF;
            ((r * 33 + g * 34 + b * 33) / 100) as u8
        })
        .collect()
}
