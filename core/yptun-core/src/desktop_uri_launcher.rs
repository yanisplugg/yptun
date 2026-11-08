//! DesktopUriLauncher + WindowsShellLaunch (org.olcbox.app.desktop): opening links and custom-scheme
//! URIs (`tg://...`) from the desktop app, de-elevated on Windows when the app runs elevated (TUN mode).
//!
//! The launch order is decided by [launch_plan] (pure); [open_uri] runs it through a [UriLaunchHost].
//! [SystemUriLauncher] is the real host: registry via RegGetValueW, de-elevated start via the shell's
//! token (GetShellWindow -> OpenProcessToken -> DuplicateTokenEx -> CreateProcessWithTokenW).

use std::process::{Command, Stdio};

use crate::desktop_native_assets::DesktopOs;

/// `"tg://socks?..."` -> `"tg"`.
pub fn scheme_of(uri: &str) -> String {
    let s = match uri.find("://") {
        Some(i) => &uri[..i],
        None => match uri.find(':') {
            Some(i) => &uri[..i],
            None => uri,
        },
    };
    s.trim().to_lowercase()
}

pub fn is_web_link(uri: &str) -> bool {
    matches!(scheme_of(uri).as_str(), "http" | "https" | "file")
}

/// Only http(s) belongs to a browser; `file:` stays with explorer.
pub fn is_http_link(uri: &str) -> bool {
    matches!(scheme_of(uri).as_str(), "http" | "https")
}

/// CommandLineToArgvW's rules, minus the backslash escapes no shell handler ever emits.
pub fn split_command_line(command_line: &str) -> Vec<String> {
    let mut argv = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut started = false;
    for ch in command_line.chars() {
        if ch == '"' {
            quoted = !quoted;
            started = true;
        } else if ch.is_whitespace() && !quoted {
            if started {
                argv.push(std::mem::take(&mut current));
                started = false;
            }
        } else {
            current.push(ch);
            started = true;
        }
    }
    if started {
        argv.push(current);
    }
    argv
}

const CHROMIUM_EXES: [&str; 10] = [
    "chrome.exe", "msedge.exe", "brave.exe", "opera.exe", "opera_gx.exe",
    "vivaldi.exe", "browser.exe", "yandex.exe", "chromium.exe", "thorium.exe",
];
const FIREFOX_EXES: [&str; 4] = ["firefox.exe", "waterfox.exe", "librewolf.exe", "palemoon.exe"];

/// Browser arguments that open `uri` as its own window, or None for an unknown browser.
pub fn app_window_args(exe: &str, uri: &str) -> Option<Vec<String>> {
    let after_bs = exe.rsplit('\\').next().unwrap_or(exe);
    let name = after_bs.rsplit('/').next().unwrap_or(after_bs).to_lowercase();
    if CHROMIUM_EXES.contains(&name.as_str()) {
        Some(vec![exe.to_string(), format!("--app={}", uri), String::from("--window-size=520,760")])
    } else if FIREFOX_EXES.contains(&name.as_str()) {
        Some(vec![exe.to_string(), String::from("-new-window"), uri.to_string()])
    } else {
        None
    }
}

/// A registry `shell\open\command` template with `%1` replaced (or the URI appended quoted).
pub fn handler_command_from_template(template: Option<&str>, uri: &str) -> Option<String> {
    let t = template?;
    if t.trim().is_empty() {
        return None;
    }
    if t.contains("%1") {
        Some(t.replace("%1", uri))
    } else {
        Some(format!("{} \"{}\"", t, uri))
    }
}

pub const BROWSER_USER_CHOICE_KEY: &str =
    "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\http\\UserChoice";

pub fn handler_key(scheme: &str) -> String {
    format!("{}\\shell\\open\\command", scheme)
}

/// Joins argv for a shell-token start, quoting arguments that contain a space.
pub fn quote_app_args(args: &[String]) -> String {
    args.iter()
        .map(|a| if a.contains(' ') { format!("\"{}\"", a) } else { a.clone() })
        .collect::<Vec<_>>()
        .join(" ")
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LaunchStep {
    /// Start this command line with the shell's (de-elevated) token; fails when that is impossible.
    ShellUser(String),
    /// Split this registry command line into argv and start it.
    CommandLine(String),
    /// Start this argv as is.
    Exec(Vec<String>),
    /// The platform's generic "browse" (Desktop.browse in Kotlin).
    Browse(String),
}

fn exec(args: &[&str]) -> LaunchStep {
    LaunchStep::Exec(args.iter().map(|s| s.to_string()).collect())
}

/// The order in which DesktopUriLauncher.open tries launchers. `handler_command` is the resolved
/// handler of a custom scheme, `browser_command` the default browser (both Windows only).
pub fn launch_plan(
    os: DesktopOs,
    uri: &str,
    handler_command: Option<String>,
    browser_command: Option<String>,
    elevated: bool,
) -> Vec<LaunchStep> {
    let mut steps = Vec::new();
    match os {
        DesktopOs::Windows if !is_web_link(uri) => {
            if let Some(c) = handler_command {
                if elevated {
                    steps.push(LaunchStep::ShellUser(c.clone()));
                }
                steps.push(LaunchStep::CommandLine(c));
            }
            steps.push(exec(&["rundll32.exe", "url.dll,FileProtocolHandler", uri]));
            steps.push(LaunchStep::Browse(uri.to_string()));
        }
        DesktopOs::Windows if is_http_link(uri) => {
            if let Some(c) = browser_command {
                if elevated {
                    steps.push(LaunchStep::ShellUser(c.clone()));
                }
                steps.push(LaunchStep::CommandLine(c));
            }
            steps.push(exec(&["rundll32.exe", "url.dll,FileProtocolHandler", uri]));
            steps.push(exec(&["explorer.exe", uri]));
            steps.push(LaunchStep::Browse(uri.to_string()));
        }
        DesktopOs::Windows => {
            steps.push(exec(&["explorer.exe", uri]));
            steps.push(exec(&["rundll32.exe", "url.dll,FileProtocolHandler", uri]));
            steps.push(LaunchStep::Browse(uri.to_string()));
        }
        DesktopOs::MacOs => {
            steps.push(exec(&["open", uri]));
            steps.push(LaunchStep::Browse(uri.to_string()));
        }
        _ => {
            steps.push(exec(&["xdg-open", uri]));
            steps.push(LaunchStep::Browse(uri.to_string()));
        }
    }
    steps
}

pub trait UriLaunchHost {
    fn os(&self) -> DesktopOs;
    /// HKCR `<scheme>\shell\open\command` with %1 filled in.
    fn handler_command_line(&mut self, scheme: &str, uri: &str) -> Option<String>;
    /// The default browser's command line with %1 filled in.
    fn browser_command_line(&mut self, uri: &str) -> Option<String>;
    fn is_elevated(&mut self) -> bool;
    fn run(&mut self, step: &LaunchStep) -> Result<(), String>;
}

/// DesktopUriLauncher.open. False only when every mechanism failed.
pub fn open_uri(host: &mut dyn UriLaunchHost, uri: &str) -> bool {
    if uri.trim().is_empty() {
        return false;
    }
    let os = host.os();
    let windows = os == DesktopOs::Windows;
    let handler = if windows && !is_web_link(uri) { host.handler_command_line(&scheme_of(uri), uri) } else { None };
    let browser = if windows && is_http_link(uri) { host.browser_command_line(uri) } else { None };
    let elevated = if handler.is_some() || browser.is_some() { host.is_elevated() } else { false };
    for step in launch_plan(os, uri, handler, browser, elevated) {
        if host.run(&step).is_ok() {
            return true;
        }
    }
    false
}

/// DesktopUriLauncher.openBrowserWindow: the user's browser in app mode, else [open_uri].
pub fn open_browser_window(host: &mut dyn UriLaunchHost, uri: &str) -> bool {
    if uri.trim().is_empty() {
        return false;
    }
    if host.os() == DesktopOs::Windows {
        let exe = host
            .browser_command_line(uri)
            .and_then(|c| split_command_line(&c).into_iter().next())
            .filter(|e| !e.trim().is_empty());
        if let Some(args) = exe.and_then(|e| app_window_args(&e, uri)) {
            if host.is_elevated() && host.run(&LaunchStep::ShellUser(quote_app_args(&args))).is_ok() {
                return true;
            }
            if host.run(&LaunchStep::Exec(args)).is_ok() {
                return true;
            }
        }
    }
    open_uri(host, uri)
}

// ---- the real host ----------------------------------------------------------------------------

#[cfg(windows)]
mod win {
    use std::ffi::c_void;
    use std::ptr::{null, null_mut};

    type Handle = *mut c_void;

    #[allow(dead_code)]
    #[repr(C)]
    struct StartupInfoW {
        cb: u32,
        lp_reserved: *mut u16,
        lp_desktop: *mut u16,
        lp_title: *mut u16,
        dw_x: u32,
        dw_y: u32,
        dw_x_size: u32,
        dw_y_size: u32,
        dw_x_count_chars: u32,
        dw_y_count_chars: u32,
        dw_fill_attribute: u32,
        dw_flags: u32,
        w_show_window: u16,
        cb_reserved2: u16,
        lp_reserved2: *mut u8,
        h_std_input: Handle,
        h_std_output: Handle,
        h_std_error: Handle,
    }

    #[allow(dead_code)]
    #[repr(C)]
    struct ProcessInformation {
        h_process: Handle,
        h_thread: Handle,
        dw_process_id: u32,
        dw_thread_id: u32,
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        fn GetShellWindow() -> Handle;
        fn GetWindowThreadProcessId(hwnd: Handle, pid: *mut u32) -> u32;
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
    }

    #[link(name = "advapi32")]
    unsafe extern "system" {
        fn OpenProcessToken(process: Handle, access: u32, token: *mut Handle) -> i32;
        fn DuplicateTokenEx(
            existing: Handle,
            access: u32,
            attributes: *mut c_void,
            level: i32,
            token_type: i32,
            new_token: *mut Handle,
        ) -> i32;
        fn CreateProcessWithTokenW(
            token: Handle,
            logon_flags: u32,
            application_name: *const u16,
            command_line: *mut u16,
            creation_flags: u32,
            environment: *mut c_void,
            current_directory: *const u16,
            startup_info: *mut StartupInfoW,
            process_information: *mut ProcessInformation,
        ) -> i32;
        fn RegGetValueW(
            hkey: Handle,
            sub_key: *const u16,
            value: *const u16,
            flags: u32,
            value_type: *mut u32,
            data: *mut c_void,
            data_len: *mut u32,
        ) -> i32;
    }

    const PROCESS_QUERY_INFORMATION: u32 = 0x0400;
    const TOKEN_DUPLICATE: u32 = 0x0002;
    const TOKEN_QUERY: u32 = 0x0008;
    const TOKEN_ALL_ACCESS: u32 = 0xF01FF;
    const SECURITY_IMPERSONATION: i32 = 2;
    const TOKEN_PRIMARY: i32 = 1;
    const CREATE_UNICODE_ENVIRONMENT: u32 = 0x0000_0400;
    const RRF_RT_REG_SZ: u32 = 0x0000_0002;
    const RRF_RT_REG_EXPAND_SZ: u32 = 0x0000_0004;
    const RRF_NOEXPAND: u32 = 0x1000_0000;

    pub const HKEY_CLASSES_ROOT: isize = 0x8000_0000u32 as i32 as isize;
    pub const HKEY_CURRENT_USER: isize = 0x8000_0001u32 as i32 as isize;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// A REG_SZ / REG_EXPAND_SZ value (not expanded), None when missing.
    pub fn reg_string(root: isize, key: &str, value: &str) -> Option<String> {
        let k = wide(key);
        let v = wide(value);
        let flags = RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ | RRF_NOEXPAND;
        let mut len: u32 = 0;
        let rc = unsafe { RegGetValueW(root as Handle, k.as_ptr(), v.as_ptr(), flags, null_mut(), null_mut(), &mut len) };
        if rc != 0 || len == 0 {
            return None;
        }
        let mut buf = vec![0u16; (len as usize).div_ceil(2) + 1];
        let mut len2 = (buf.len() * 2) as u32;
        let rc = unsafe {
            RegGetValueW(root as Handle, k.as_ptr(), v.as_ptr(), flags, null_mut(), buf.as_mut_ptr() as *mut c_void, &mut len2)
        };
        if rc != 0 {
            return None;
        }
        let end = buf.iter().position(|c| *c == 0).unwrap_or(buf.len());
        Some(String::from_utf16_lossy(&buf[..end]))
    }

    pub fn start_as_shell_user(command_line: &str) -> bool {
        unsafe {
            let shell = GetShellWindow();
            if shell.is_null() {
                return false;
            }
            let mut pid = 0u32;
            GetWindowThreadProcessId(shell, &mut pid);
            if pid == 0 {
                return false;
            }
            let process = OpenProcess(PROCESS_QUERY_INFORMATION, 0, pid);
            if process.is_null() {
                return false;
            }
            let mut started = false;
            let mut token: Handle = null_mut();
            if OpenProcessToken(process, TOKEN_DUPLICATE | TOKEN_QUERY, &mut token) != 0 {
                let mut primary: Handle = null_mut();
                let duplicated = DuplicateTokenEx(
                    token,
                    TOKEN_ALL_ACCESS,
                    null_mut(),
                    SECURITY_IMPERSONATION,
                    TOKEN_PRIMARY,
                    &mut primary,
                );
                if duplicated != 0 {
                    let mut si: StartupInfoW = std::mem::zeroed();
                    si.cb = std::mem::size_of::<StartupInfoW>() as u32;
                    let mut pi: ProcessInformation = std::mem::zeroed();
                    let mut buffer = wide(command_line);
                    started = CreateProcessWithTokenW(
                        primary,
                        0,
                        null(),
                        buffer.as_mut_ptr(),
                        CREATE_UNICODE_ENVIRONMENT,
                        null_mut(),
                        null(),
                        &mut si,
                        &mut pi,
                    ) != 0;
                    if started {
                        if !pi.h_thread.is_null() {
                            CloseHandle(pi.h_thread);
                        }
                        if !pi.h_process.is_null() {
                            CloseHandle(pi.h_process);
                        }
                    }
                    CloseHandle(primary);
                }
                CloseHandle(token);
            }
            CloseHandle(process);
            started
        }
    }
}

/// WindowsShellLaunch.handlerCommandLine: HKCR `<scheme>\shell\open\command` with %1 filled in.
pub fn windows_handler_command_line(scheme: &str, uri: &str) -> Option<String> {
    if scheme.trim().is_empty() {
        return None;
    }
    #[cfg(windows)]
    let template = win::reg_string(win::HKEY_CLASSES_ROOT, &handler_key(scheme), "");
    #[cfg(not(windows))]
    let template: Option<String> = None;
    handler_command_from_template(template.as_deref(), uri)
}

/// WindowsShellLaunch.browserCommandLine: the UserChoice ProgId first, then the machine http handler.
pub fn windows_browser_command_line(uri: &str) -> Option<String> {
    #[cfg(windows)]
    let prog_id = win::reg_string(win::HKEY_CURRENT_USER, BROWSER_USER_CHOICE_KEY, "ProgId").filter(|s| !s.trim().is_empty());
    #[cfg(not(windows))]
    let prog_id: Option<String> = None;
    prog_id.and_then(|p| windows_handler_command_line(&p, uri)).or_else(|| windows_handler_command_line("http", uri))
}

/// WindowsShellLaunch.startAsShellUser; false off Windows or when any step fails.
pub fn start_as_shell_user(command_line: &str) -> bool {
    if command_line.trim().is_empty() {
        return false;
    }
    #[cfg(windows)]
    let ok = win::start_as_shell_user(command_line);
    #[cfg(not(windows))]
    let ok = false;
    ok
}

/// Whether HKCR has a handler for `scheme`; false off Windows.
pub fn scheme_registered(scheme: &str) -> bool {
    if !cfg!(windows) || scheme.trim().is_empty() {
        return false;
    }
    #[cfg(windows)]
    let found = win::reg_string(win::HKEY_CLASSES_ROOT, &handler_key(scheme), "").is_some();
    #[cfg(not(windows))]
    let found = false;
    found
}

fn spawn(argv: &[String]) -> Result<(), String> {
    let (first, rest) = argv.split_first().ok_or_else(|| String::from("empty command"))?;
    Command::new(first)
        .args(rest)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

/// The real host.
pub struct SystemUriLauncher;

impl UriLaunchHost for SystemUriLauncher {
    fn os(&self) -> DesktopOs {
        crate::desktop_app::current_os()
    }
    fn handler_command_line(&mut self, scheme: &str, uri: &str) -> Option<String> {
        windows_handler_command_line(scheme, uri)
    }
    fn browser_command_line(&mut self, uri: &str) -> Option<String> {
        windows_browser_command_line(uri)
    }
    fn is_elevated(&mut self) -> bool {
        crate::desktop_app::is_elevated()
    }
    fn run(&mut self, step: &LaunchStep) -> Result<(), String> {
        match step {
            LaunchStep::ShellUser(c) => {
                if start_as_shell_user(c) {
                    Ok(())
                } else {
                    Err(String::from("shell-token launch failed"))
                }
            }
            LaunchStep::CommandLine(c) => {
                let argv = split_command_line(c);
                if argv.is_empty() {
                    return Err(String::from("empty handler command line"));
                }
                spawn(&argv)
            }
            LaunchStep::Exec(argv) => spawn(argv),
            LaunchStep::Browse(uri) => {
                let argv: Vec<String> = match self.os() {
                    DesktopOs::Windows => vec!["cmd", "/C", "start", "", uri.as_str()].into_iter().map(String::from).collect(),
                    DesktopOs::MacOs => vec![String::from("open"), uri.clone()],
                    _ => vec![String::from("xdg-open"), uri.clone()],
                };
                spawn(&argv)
            }
        }
    }
}

/// DesktopUriLauncher.open with the real host.
pub fn open(uri: &str) -> bool {
    open_uri(&mut SystemUriLauncher, uri)
}

/// DesktopUriLauncher.openBrowserWindow with the real host.
pub fn open_browser_window_system(uri: &str) -> bool {
    open_browser_window(&mut SystemUriLauncher, uri)
}
