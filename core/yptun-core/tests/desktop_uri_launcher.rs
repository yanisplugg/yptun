use yptun_core::desktop_native_assets::DesktopOs;
use yptun_core::desktop_uri_launcher::*;

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

struct FakeHost {
    os: DesktopOs,
    handler: Option<String>,
    browser: Option<String>,
    elevated: bool,
    ok_after: usize,
    ran: Vec<LaunchStep>,
}

impl FakeHost {
    fn new(os: DesktopOs) -> Self {
        FakeHost { os, handler: None, browser: None, elevated: false, ok_after: 0, ran: Vec::new() }
    }
}

impl UriLaunchHost for FakeHost {
    fn os(&self) -> DesktopOs {
        self.os
    }
    fn handler_command_line(&mut self, _scheme: &str, uri: &str) -> Option<String> {
        handler_command_from_template(self.handler.as_deref(), uri)
    }
    fn browser_command_line(&mut self, uri: &str) -> Option<String> {
        handler_command_from_template(self.browser.as_deref(), uri)
    }
    fn is_elevated(&mut self) -> bool {
        self.elevated
    }
    fn run(&mut self, step: &LaunchStep) -> Result<(), String> {
        self.ran.push(step.clone());
        if self.ran.len() > self.ok_after {
            Ok(())
        } else {
            Err(String::from("fail"))
        }
    }
}

#[test]
fn schemes() {
    assert_eq!(scheme_of("tg://socks?server=1"), "tg");
    assert_eq!(scheme_of(" HTTPS://x"), "https");
    assert_eq!(scheme_of("mailto:a@b.c"), "mailto");
    assert_eq!(scheme_of("plain"), "plain");
    assert!(is_web_link("file:///C:/x"));
    assert!(is_web_link("http://x"));
    assert!(!is_web_link("tg://x"));
    assert!(is_http_link("https://x"));
    assert!(!is_http_link("file:///x"));
}

#[test]
fn command_lines() {
    assert_eq!(
        split_command_line("\"C:\\Program Files\\Telegram\\Telegram.exe\" -- \"tg://x\""),
        s(&["C:\\Program Files\\Telegram\\Telegram.exe", "--", "tg://x"])
    );
    assert_eq!(split_command_line("  a   b "), s(&["a", "b"]));
    assert_eq!(split_command_line("a \"\" b"), s(&["a", "", "b"]));
    assert!(split_command_line("   ").is_empty());
    assert_eq!(handler_command_from_template(None, "tg://x"), None);
    assert_eq!(handler_command_from_template(Some(" "), "tg://x"), None);
    assert_eq!(
        handler_command_from_template(Some("\"T.exe\" -- \"%1\""), "tg://x"),
        Some(String::from("\"T.exe\" -- \"tg://x\""))
    );
    assert_eq!(handler_command_from_template(Some("T.exe"), "tg://x"), Some(String::from("T.exe \"tg://x\"")));
    assert_eq!(handler_key("tg"), "tg\\shell\\open\\command");
    assert_eq!(quote_app_args(&s(&["C:\\Program Files\\b.exe", "--app=x"])), "\"C:\\Program Files\\b.exe\" --app=x");
}

#[test]
fn browser_window_args() {
    assert_eq!(
        app_window_args("C:\\Google\\Chrome.EXE", "https://a"),
        Some(s(&["C:\\Google\\Chrome.EXE", "--app=https://a", "--window-size=520,760"]))
    );
    assert_eq!(app_window_args("/x/firefox.exe", "https://a"), Some(s(&["/x/firefox.exe", "-new-window", "https://a"])));
    assert_eq!(app_window_args("C:\\x\\iexplore.exe", "https://a"), None);
}

#[test]
fn plans() {
    let p = launch_plan(DesktopOs::Windows, "tg://x", Some(String::from("T.exe \"tg://x\"")), None, true);
    assert_eq!(
        p,
        vec![
            LaunchStep::ShellUser(String::from("T.exe \"tg://x\"")),
            LaunchStep::CommandLine(String::from("T.exe \"tg://x\"")),
            LaunchStep::Exec(s(&["rundll32.exe", "url.dll,FileProtocolHandler", "tg://x"])),
            LaunchStep::Browse(String::from("tg://x")),
        ]
    );
    let p = launch_plan(DesktopOs::Windows, "tg://x", None, None, true);
    assert_eq!(p.len(), 2);
    let p = launch_plan(DesktopOs::Windows, "https://a", Some(String::from("ignored")), Some(String::from("B.exe https://a")), false);
    assert_eq!(
        p,
        vec![
            LaunchStep::CommandLine(String::from("B.exe https://a")),
            LaunchStep::Exec(s(&["rundll32.exe", "url.dll,FileProtocolHandler", "https://a"])),
            LaunchStep::Exec(s(&["explorer.exe", "https://a"])),
            LaunchStep::Browse(String::from("https://a")),
        ]
    );
    let p = launch_plan(DesktopOs::Windows, "file:///C:/x", None, None, false);
    assert_eq!(p[0], LaunchStep::Exec(s(&["explorer.exe", "file:///C:/x"])));
    assert_eq!(p.len(), 3);
    assert_eq!(
        launch_plan(DesktopOs::MacOs, "tg://x", None, None, false),
        vec![LaunchStep::Exec(s(&["open", "tg://x"])), LaunchStep::Browse(String::from("tg://x"))]
    );
    assert_eq!(launch_plan(DesktopOs::Linux, "tg://x", None, None, false)[0], LaunchStep::Exec(s(&["xdg-open", "tg://x"])));
}

#[test]
fn open_tries_until_one_works() {
    let mut h = FakeHost::new(DesktopOs::Windows);
    h.handler = Some(String::from("T.exe -- %1"));
    h.elevated = true;
    h.ok_after = 1;
    assert!(open_uri(&mut h, "tg://x"));
    assert_eq!(
        h.ran,
        vec![LaunchStep::ShellUser(String::from("T.exe -- tg://x")), LaunchStep::CommandLine(String::from("T.exe -- tg://x"))]
    );
    let mut h = FakeHost::new(DesktopOs::Linux);
    h.ok_after = 10;
    assert!(!open_uri(&mut h, "tg://x"));
    assert_eq!(h.ran.len(), 2);
    let mut h = FakeHost::new(DesktopOs::Linux);
    assert!(!open_uri(&mut h, "  "));
    assert!(h.ran.is_empty());
}

#[test]
fn browser_window() {
    let mut h = FakeHost::new(DesktopOs::Windows);
    h.browser = Some(String::from("\"C:\\Program Files\\Chrome\\chrome.exe\" -- \"%1\""));
    h.elevated = true;
    assert!(open_browser_window(&mut h, "https://a"));
    assert_eq!(
        h.ran,
        vec![LaunchStep::ShellUser(String::from("\"C:\\Program Files\\Chrome\\chrome.exe\" --app=https://a --window-size=520,760"))]
    );
    let mut h = FakeHost::new(DesktopOs::Windows);
    h.browser = Some(String::from("chrome.exe %1"));
    assert!(open_browser_window(&mut h, "https://a"));
    assert_eq!(h.ran, vec![LaunchStep::Exec(s(&["chrome.exe", "--app=https://a", "--window-size=520,760"]))]);
    let mut h = FakeHost::new(DesktopOs::MacOs);
    assert!(open_browser_window(&mut h, "https://a"));
    assert_eq!(h.ran, vec![LaunchStep::Exec(s(&["open", "https://a"]))]);
}

#[test]
fn system_helpers_are_safe() {
    assert!(!start_as_shell_user("  "));
    assert!(!scheme_registered(""));
    assert_eq!(windows_handler_command_line("", "x"), None);
    if !cfg!(windows) {
        assert!(!start_as_shell_user("x"));
        assert!(!scheme_registered("tg"));
        assert_eq!(windows_browser_command_line("https://a"), None);
    }
}
