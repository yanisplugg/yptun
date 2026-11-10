use std::fs;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Duration;

use yptun_core::desktop_app::*;
use yptun_core::desktop_native_assets::DesktopOs;

fn temp(name: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-app-{}-{}-{}", name, std::process::id(), nanos));
    fs::create_dir_all(&d).unwrap();
    d
}

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

#[test]
fn os_names() {
    assert_eq!(os_from_name("Mac OS X"), DesktopOs::MacOs);
    assert_eq!(os_from_name("macos"), DesktopOs::MacOs);
    assert_eq!(os_from_name("Windows 11"), DesktopOs::Windows);
    assert_eq!(os_from_name("windows"), DesktopOs::Windows);
    assert_eq!(os_from_name("Linux"), DesktopOs::Linux);
    assert_eq!(os_from_name("FreeBSD"), DesktopOs::Other);
}

#[test]
fn data_dir_names_and_bases() {
    let home = Path::new("/home/u");
    assert_eq!(app_data_dir_name(DesktopOs::Linux), ".yptun");
    assert_eq!(app_data_dir_name(DesktopOs::Windows), "YPtun");
    assert_eq!(legacy_app_data_dir_name(DesktopOs::MacOs), "Olcbox");
    assert_eq!(legacy_app_data_dir_name(DesktopOs::Other), ".olcbox");
    assert_eq!(app_data_base(DesktopOs::MacOs, home, None), home.join("Library").join("Application Support"));
    assert_eq!(app_data_base(DesktopOs::Windows, home, Some("R")), PathBuf::from("R"));
    assert_eq!(app_data_base(DesktopOs::Windows, home, Some(" ")), home.join("AppData").join("Roaming"));
    assert_eq!(app_data_base(DesktopOs::Linux, home, Some("R")), home.to_path_buf());
}

#[test]
fn legacy_dir_is_migrated_once() {
    let home = temp("home");
    fs::create_dir_all(home.join(".olcbox")).unwrap();
    fs::write(home.join(".olcbox").join("profiles.json"), "[]").unwrap();
    let dir = standard_app_data_dir_in(DesktopOs::Linux, &home, None).unwrap();
    assert_eq!(dir, home.join(".yptun"));
    assert!(dir.join("profiles.json").is_file());
    assert!(!home.join(".olcbox").exists());
    let again = standard_app_data_dir_in(DesktopOs::Linux, &home, None).unwrap();
    assert_eq!(again, dir);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn timestamps() {
    assert_eq!(format_timestamp_with_offset(0, 0), "1970-01-01 00:00:00.000");
    assert_eq!(format_timestamp_with_offset(1_700_000_000_123, 0), "2023-11-14 22:13:20.123");
    assert_eq!(format_timestamp_with_offset(951_782_400_000, 0), "2000-02-29 00:00:00.000");
    assert_eq!(format_timestamp_with_offset(4_102_444_799_999, 0), "2099-12-31 23:59:59.999");
    assert_eq!(format_timestamp_with_offset(0, 3 * 3600), "1970-01-01 03:00:00.000");
    assert_eq!(format_timestamp_with_offset(0, -3600), "1969-12-31 23:00:00.000");
    assert_eq!(format_timestamp_with_offset(1_700_000_000_123, 5 * 3600 + 1800), "2023-11-15 03:43:20.123");
}

#[test]
fn civil_days_round_trip() {
    assert_eq!(days_from_civil(1970, 1, 1), 0);
    assert_eq!(days_from_civil(2000, 2, 29), 11_016);
    assert_eq!(days_from_civil(1969, 12, 31), -1);
    assert_eq!(days_from_civil(2099, 12, 31), 47_481);
}

#[test]
fn local_time_comes_from_the_os() {
    let now = 1_700_000_000_123u64;
    let off = local_offset_seconds(1_700_000_000);
    assert!(off.abs() <= 18 * 3600);
    assert_eq!(format_timestamp(now), format_timestamp_with_offset(now as i64, off));
    let summer = local_offset_seconds(1_689_000_000);
    assert!(summer.abs() <= 18 * 3600);
}

#[test]
fn file_log_appends_lines() {
    let d = temp("log");
    let p = d.join(FILE_LOG_NAME);
    append_log_to(&p, "2024-01-01 00:00:00.000", "one").unwrap();
    append_log_to(&p, "2024-01-01 00:00:01.000", "two").unwrap();
    let text = fs::read_to_string(&p).unwrap();
    let expected = format!(
        "{}{}",
        file_log_line("2024-01-01 00:00:00.000", "one"),
        file_log_line("2024-01-01 00:00:01.000", "two")
    );
    assert_eq!(text, expected);
    assert!(text.starts_with("2024-01-01 00:00:00.000 one"));
    assert_eq!(FILE_LOG_MAX_BYTES, 32 * 1024 * 1024);
    let _ = fs::remove_dir_all(&d);
}

#[test]
fn launchers() {
    assert!(is_real_launcher("C:\\YPtun\\YPtun.exe"));
    assert!(!is_real_launcher("C:\\jdk\\bin\\java.exe"));
    assert!(!is_real_launcher("C:\\jdk\\bin\\JAVAW.EXE"));
    assert!(!is_real_launcher("/usr/bin/yptun"));
    assert_eq!(launcher_dir_from(None), None);
    assert_eq!(launcher_dir_from(Some("/opt/java.exe")), None);
    assert_eq!(launcher_dir_from(Some("/opt/y/YPtun.exe")), Some(PathBuf::from("/opt/y")));
    assert_eq!(portable_executable_from(None), None);
    assert_eq!(portable_executable_from(Some("  ")), None);
    assert_eq!(portable_executable_from(Some("/definitely/missing/YPtun.exe")), None);
}

#[test]
fn install_roots_are_lowercased() {
    let roots = install_roots(Some("C:\\Program Files"), None, Some("C:\\Program Files"), Some("C:\\Users\\A\\AppData\\Local"));
    assert_eq!(roots, s(&["c:\\program files", "c:\\program files", "c:\\users\\a\\appdata\\local\\programs"]));
}

#[test]
fn portable_decision() {
    let d = temp("portable");
    assert!(!decide_portable(false, Some(&d), &[]));
    assert!(!decide_portable(true, None, &[]));
    assert!(decide_portable(true, Some(&d), &[]));
    let root = vec![d.to_string_lossy().to_lowercase()];
    assert!(!decide_portable(true, Some(&d), &root));
    fs::write(d.join("unins000.exe"), "").unwrap();
    assert!(!decide_portable(true, Some(&d), &[]));
    fs::write(d.join(PORTABLE_MARKER_FILE_NAME), "").unwrap();
    assert!(decide_portable(true, Some(&d), &root));
    let _ = fs::remove_dir_all(&d);
}

#[test]
fn powershell_quoting() {
    assert_eq!(powershell_literal("it's"), "'it''s'");
    assert_eq!(windows_command_line_argument(""), "\"\"");
    assert_eq!(windows_command_line_argument("abc"), "abc");
    assert_eq!(windows_command_line_argument("a b"), "\"a b\"");
    assert_eq!(windows_command_line_argument("a\"b"), "\"a\\\"b\"");
    assert_eq!(windows_command_line_argument("C:\\dir with space\\"), "\"C:\\dir with space\\\\\"");
    assert_eq!(windows_command_line_argument("C:\\no\\space"), "C:\\no\\space");
}

#[test]
fn administrator_script() {
    let script = restart_as_administrator_script("C:\\YPtun\\YPtun.exe", &s(&["--x", "a b"]), "C:\\YPtun");
    let expected = [
        "$ErrorActionPreference = 'Stop'",
        "$startArgs = @{",
        "  FilePath = 'C:\\YPtun\\YPtun.exe'",
        "  Verb = 'RunAs'",
        "  ArgumentList = '--x \"a b\"'",
        "  WorkingDirectory = 'C:\\YPtun'",
        "}",
        "Start-Process @startArgs | Out-Null",
    ]
    .join("\n");
    assert_eq!(script, expected);
    let no_wd = restart_as_administrator_script("y.exe", &[], " ");
    let lines: Vec<&str> = no_wd.lines().collect();
    assert_eq!(lines[4], "  ArgumentList = ''");
    assert_eq!(lines[5], "");
    assert_eq!(lines[6], "}");
}

#[test]
fn relaunch_args_and_saved_mode() {
    assert_eq!(
        relaunch_arguments(&s(&[STARTUP_ELEVATION_ARGUMENT, "tg://x"])),
        s(&["tg://x", STARTUP_ELEVATION_ARGUMENT])
    );
    assert!(!saved_mode_needs_admin(true, None));
    assert!(saved_mode_needs_admin(false, None));
    assert!(saved_mode_needs_admin(false, Some("not json")));
    assert!(saved_mode_needs_admin(false, Some("{\"other\":1}")));
    assert!(saved_mode_needs_admin(false, Some("{\"connectionMode\":\" \"}")));
    assert!(saved_mode_needs_admin(false, Some("{\"connectionMode\":\"tun\"}")));
    assert!(!saved_mode_needs_admin(false, Some("{\"connectionMode\":\"PROXY\"}")));
    assert_eq!(ui_settings_path(Path::new("d")), Path::new("d").join("settings").join("ui.json"));
    if !cfg!(windows) {
        assert!(!is_elevated());
        assert!(!needs_administrator_for_tun());
        assert!(!relaunch_elevated_for_startup(&[]));
    }
}

#[test]
fn render_settings() {
    let home = Path::new("h");
    assert_eq!(render_api_file(Some("L"), home), Path::new("L").join("YPtun").join("render-api.txt"));
    assert_eq!(render_api_file(None, home), home.join("YPtun").join("render-api.txt"));
    assert_eq!(saved_render_api(None), None);
    assert_eq!(saved_render_api(Some("  \n")), None);
    assert_eq!(saved_render_api(Some(" SOFTWARE_COMPAT\n")), Some(String::from("SOFTWARE_COMPAT")));
    let d = temp("render");
    let f = d.join("YPtun").join("render-api.txt");
    save_render_fallback(&f).unwrap();
    assert_eq!(fs::read_to_string(&f).unwrap(), RENDER_FALLBACK);
    let _ = fs::remove_dir_all(&d);
    assert!(render_watch_enabled(DesktopOs::Windows, false));
    assert!(!render_watch_enabled(DesktopOs::Windows, true));
    assert!(!render_watch_enabled(DesktopOs::Linux, false));
}

#[test]
fn blank_detection() {
    assert!(colors_close(0x101010, 0x151515));
    assert!(!colors_close(0x101010, 0x161010));
    assert_eq!(blank_capture_area(0, 0, 100, 100), Some((10, 10, 80, 80)));
    assert_eq!(blank_capture_area(0, 0, 49, 100), None);
    assert!(is_blank_capture(100, 100, |_, _| 0xFFFFFF));
    assert!(is_blank_capture(100, 100, |x, _| if x % 16 == 0 { 0xFFFFFF } else { 0 }));
    assert!(!is_blank_capture(100, 100, |x, y| if x == 32 && y == 48 { 0 } else { 0xFFFFFF }));
    assert!(!is_blank_capture(0, 10, |_, _| 0));
    let mut w = BlankWatchdog::default();
    assert_eq!(w.observe(false, Some(true)), RenderWatchAction::Continue);
    assert_eq!(w.observe(true, Some(true)), RenderWatchAction::Continue);
    assert_eq!(w.observe(true, Some(false)), RenderWatchAction::Continue);
    assert_eq!(w.observe(true, Some(true)), RenderWatchAction::Continue);
    assert_eq!(w.observe(true, Some(true)), RenderWatchAction::SwitchToFallback);
    assert_eq!(w.observe(true, None), RenderWatchAction::Stop);
    assert_eq!(render_relaunch_command(Some("java.exe"), &[]), None);
    assert_eq!(
        render_relaunch_command(Some("Y.exe"), &s(&["a"])),
        Some(s(&["Y.exe", "a", STARTUP_ELEVATION_ARGUMENT]))
    );
}

#[test]
fn single_instance_protocol() {
    assert_eq!(SINGLE_INSTANCE_PORT, 47638);
    assert_eq!(link_argument(&s(&["--x", "vless://a", "tg://b"])), Some(String::from("vless://a")));
    assert_eq!(link_argument(&s(&["--x"])), None);
    assert_eq!(show_command(None), "show");
    assert_eq!(show_command(Some("tg://x")), "show tg://x");
    assert_eq!(parse_show_line("show\r\n"), Some(None));
    assert_eq!(parse_show_line("show vless://a@b:1"), Some(Some(String::from("vless://a@b:1"))));
    assert_eq!(parse_show_line("show not-a-link"), None);
    assert_eq!(parse_show_line("show a b://c"), None);
    assert_eq!(parse_show_line("GET / HTTP/1.1"), None);
}

#[test]
fn single_instance_real_socket() {
    let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let (tx, rx) = mpsc::channel::<Option<String>>();
    let owner = SingleInstance::new(port);
    assert!(owner.claim(&[], move |link| {
        let _ = tx.send(link);
    }));
    let second = SingleInstance::new(port);
    assert!(!second.claim(&s(&["tg://proxy?x=1"]), |_| {}));
    assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap(), Some(String::from("tg://proxy?x=1")));
    notify_owner(port, "show");
    assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap(), None);
    owner.release();
    let successor = SingleInstance::new(port);
    assert!(successor.claim_after_predecessor_exits(|_| {}, 3_000));
    successor.release();
}

#[test]
fn toast_and_qr_luma() {
    let t = DesktopToast::new();
    assert_eq!(t.message(), None);
    t.show("hi");
    assert_eq!(t.message(), Some(String::from("hi")));
    t.consume();
    assert_eq!(t.message(), None);
    DESKTOP_TOAST.show("x");
    DESKTOP_TOAST.consume();
    assert_eq!(qr_luminances(&[0xFFFFFFFF, 0xFF000000, 0x00FF0000, 0x0000FF00]), vec![255, 0, 84, 86]);
}
