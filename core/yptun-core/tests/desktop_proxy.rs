use std::collections::HashMap;

use yptun_core::desktop_proxy::*;

#[derive(Default)]
struct Fake {
    exists: Vec<&'static str>,
    replies: HashMap<String, String>,
    log: Vec<String>,
}

impl Fake {
    fn with(exists: &[&'static str], replies: &[(&str, &str)]) -> Self {
        Self {
            exists: exists.to_vec(),
            replies: replies.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
            log: vec![],
        }
    }
}

impl ProxyCommandRunner for Fake {
    fn executable_exists(&self, name: &str) -> bool {
        self.exists.contains(&name)
    }

    fn run(&mut self, command: &[String]) -> Result<String, String> {
        let line = command.join(" ");
        self.log.push(line.clone());
        Ok(self.replies.get(&line).cloned().unwrap_or_default())
    }
}

fn gnome(mode: &str, host: &str) -> Vec<(&'static str, String)> {
    vec![
        ("gsettings get org.gnome.system.proxy mode", mode.to_string()),
        ("gsettings get org.gnome.system.proxy.http host", host.to_string()),
        ("gsettings get org.gnome.system.proxy.http port", "8080\n".to_string()),
        ("gsettings get org.gnome.system.proxy.https host", host.to_string()),
        ("gsettings get org.gnome.system.proxy.https port", "x".to_string()),
        ("gsettings get org.gnome.system.proxy ignore-hosts", "['corp']".to_string()),
    ]
}

fn fake_gnome(mode: &str, host: &str) -> Fake {
    let replies = gnome(mode, host);
    let refs: Vec<(&str, &str)> = replies.iter().map(|(k, v)| (*k, v.as_str())).collect();
    Fake::with(&["gsettings"], &refs)
}

#[test]
fn host_port_split() {
    assert_eq!(split_proxy_host_port("127.0.0.1:10808"), Some(("127.0.0.1".into(), 10808)));
    assert_eq!(split_proxy_host_port("127.0.0.1:x"), None);
}

#[test]
fn loopback_detection() {
    let s = LinuxGnomeProxyState { mode: "'manual'".into(), http_host: "'127.0.0.1'".into(), http_port: 1, https_host: String::new(), https_port: 0, ignore_hosts: String::new() };
    assert!(s.looks_like_ours());
    assert!(!LinuxGnomeProxyState { mode: "'none'".into(), ..s.clone() }.looks_like_ours());
    let k = LinuxKdeProxyState { proxy_type: "1".into(), http_proxy: "http://localhost:1".into(), https_proxy: String::new() };
    assert!(k.looks_like_ours());
    assert!(!LinuxKdeProxyState { proxy_type: "0".into(), ..k }.looks_like_ours());
}

#[test]
fn gnome_restore_commands() {
    let s = LinuxGnomeProxyState { mode: "'manual'".into(), http_host: "'corp'".into(), http_port: 3128, https_host: "'corp'".into(), https_port: 3129, ignore_hosts: "['a']".into() };
    let c: Vec<String> = restore_gnome_proxy_commands(&s).iter().map(|c| c.join(" ")).collect();
    assert_eq!(c[0], "gsettings set org.gnome.system.proxy.http host corp");
    assert_eq!(c[3], "gsettings set org.gnome.system.proxy.https port 3129");
    assert_eq!(c[4], "gsettings set org.gnome.system.proxy ignore-hosts ['a']");
    assert_eq!(c[5], "gsettings set org.gnome.system.proxy mode manual");
    let none = LinuxGnomeProxyState { mode: "'none'".into(), ..s };
    assert_eq!(restore_gnome_proxy_commands(&none), disable_gnome_proxy_commands());
}

#[test]
fn linux_enable_backs_up_and_restores_foreign_proxy() {
    let mut r = fake_gnome("'manual'", "'corp'");
    let mut c = LinuxProxyController::new();
    assert_eq!(c.enable(&mut r, "127.0.0.1:10808").unwrap(), LinuxProxyEnable::Applied);
    assert!(r.log.contains(&"gsettings set org.gnome.system.proxy.http port 10808".to_string()));
    assert!(r.log.contains(&format!("gsettings set org.gnome.system.proxy ignore-hosts {GNOME_IGNORE_HOSTS}")));
    assert!(c.is_active());
    r.log.clear();
    c.restore(&mut r);
    assert!(r.log.contains(&"gsettings set org.gnome.system.proxy.http host corp".to_string()));
    assert!(r.log.contains(&"gsettings set org.gnome.system.proxy.https port 0".to_string()));
    r.log.clear();
    c.restore(&mut r);
    assert!(r.log.is_empty());
}

#[test]
fn linux_never_backs_up_own_proxy() {
    let mut r = fake_gnome("'manual'", "'127.0.0.1'");
    let mut c = LinuxProxyController::new();
    c.enable(&mut r, "127.0.0.1:10808").unwrap();
    r.log.clear();
    c.restore(&mut r);
    assert_eq!(r.log, vec!["gsettings set org.gnome.system.proxy mode none".to_string()]);
    r.log.clear();
    c.clear_stale_proxy(&mut r);
    assert_eq!(r.log.last().unwrap(), "gsettings set org.gnome.system.proxy mode none");
}

#[test]
fn linux_kde_and_manual_setup() {
    let mut none = Fake::default();
    let mut c = LinuxProxyController::new();
    assert_eq!(c.enable(&mut none, "127.0.0.1:10808").unwrap(), LinuxProxyEnable::ManualSetup { host_port: "127.0.0.1:10808".into() });
    assert!(!c.is_active());
    let mut kde = Fake::with(&["kwriteconfig6", "kreadconfig5"], &[]);
    c.enable(&mut kde, "127.0.0.1:10808").unwrap();
    assert!(kde.log.contains(&"kreadconfig5 --file kioslaverc --group Proxy Settings --key ProxyType".to_string()));
    assert!(kde.log.contains(&"kwriteconfig6 --file kioslaverc --group Proxy Settings --key httpProxy http://127.0.0.1:10808".to_string()));
    kde.log.clear();
    c.restore(&mut kde);
    assert_eq!(kde.log[0], "kwriteconfig6 --file kioslaverc --group Proxy Settings --key ProxyType 0");
}

#[test]
fn mac_parsing() {
    let services = parse_network_services("An asterisk (*) denotes that a network service is disabled.\nWi-Fi\n*Bluetooth PAN\r\n Ethernet \n\n");
    assert_eq!(services, vec!["Wi-Fi", "Ethernet"]);
    let s = parse_auto_proxy_state("Wi-Fi", "URL: http://127.0.0.1:10809/proxy.pac\nenabled: yes\n");
    assert!(s.enabled);
    assert_eq!(s.url.as_deref(), Some("http://127.0.0.1:10809/proxy.pac"));
    let s = parse_auto_proxy_state("Wi-Fi", "URL: (null)\nEnabled: No\n");
    assert!(!s.enabled);
    assert_eq!(s.url, None);
}

#[test]
fn mac_enable_and_restore() {
    let pac = "http://127.0.0.1:10809/proxy.pac";
    let mut r = Fake::with(&[], &[
        ("networksetup -listallnetworkservices", "Wi-Fi\n"),
        ("networksetup -getautoproxyurl Wi-Fi", "URL: http://corp/p.pac\nEnabled: Yes\n"),
    ]);
    let mut c = MacOsProxyController::new();
    c.enable(&mut r, pac).unwrap();
    assert!(r.log.contains(&format!("networksetup -setautoproxyurl Wi-Fi {pac}")));
    assert_eq!(r.log.last().unwrap(), "networksetup -setautoproxystate Wi-Fi on");
    r.log.clear();
    c.restore(&mut r);
    assert_eq!(r.log, vec!["networksetup -setautoproxyurl Wi-Fi http://corp/p.pac", "networksetup -setautoproxystate Wi-Fi on"]);
    let off = mac_restore_commands(&[MacOsAutoProxyState { service: "E".into(), enabled: false, url: None }]);
    assert_eq!(off[0].join(" "), "networksetup -setautoproxystate E off");
}

#[test]
fn windows_edits() {
    let e = enable_http_edits("127.0.0.1:10808");
    assert_eq!(e[0], RegistryEdit::Delete { name: "AutoConfigURL".into() });
    assert_eq!(e[3], RegistryEdit::SetDword { name: "ProxyEnable".into(), value: 1 });
    let state = WindowsProxyState { proxy_enable: Some("0x1".into()), proxy_server: Some("corp:3128".into()), proxy_override: None, auto_config_url: None };
    let r = restore_edits(&state);
    assert_eq!(r[0], RegistryEdit::SetDword { name: "ProxyEnable".into(), value: 1 });
    assert_eq!(r[1], RegistryEdit::SetString { name: "ProxyServer".into(), value: "corp:3128".into() });
    assert_eq!(r[2].name(), "ProxyOverride");
    let bad = WindowsProxyState { proxy_enable: Some("zz".into()), ..Default::default() };
    assert_eq!(restore_edits(&bad)[0], RegistryEdit::Delete { name: "ProxyEnable".into() });
    assert_eq!(proxy_enable_text(0), "0x0");
    assert_eq!(proxy_enable_text(-1), "0xffffffff");
    assert_eq!(registry_string(Some("  ")), None);
}

#[test]
fn windows_controller_backup_rules() {
    let mut c = WindowsProxyController::new();
    let ours = WindowsProxyState { proxy_enable: Some("0x1".into()), proxy_server: Some("127.0.0.1:10808".into()), ..Default::default() };
    let plan = c.enable(ours.clone(), "127.0.0.1:10808");
    assert_eq!(plan.per_connection.flags(), PROXY_TYPE_DIRECT | PROXY_TYPE_PROXY);
    assert_eq!(plan.per_connection.option_count(), 3);
    let restore = c.restore().unwrap();
    assert_eq!(restore.edits, restore_edits(&WindowsProxyState::disabled()));
    assert_eq!(restore.per_connection, PerConnectionOptions { proxy_server: None, bypass: PROXY_BYPASS.into() });
    assert!(c.restore().is_none());

    let foreign = WindowsProxyState { proxy_enable: None, proxy_server: Some("corp:1".into()), proxy_override: Some("<local>".into()), auto_config_url: None };
    c.enable(foreign, "127.0.0.1:10808");
    let restore = c.restore().unwrap();
    assert_eq!(restore.per_connection.proxy_server.as_deref(), Some("corp:1"));
    assert_eq!(restore.per_connection.bypass, "<local>");
    assert!(c.clear_stale_proxy(&ours).is_some());
    assert!(c.clear_stale_proxy(&WindowsProxyState::disabled()).is_none());
}

#[test]
fn per_connection_layout_offsets() {
    let w = per_connection_layout(true);
    assert_eq!((w.option_size, w.union_offset, w.list_size, w.count_offset, w.error_offset, w.options_offset), (16, 8, 32, 16, 20, 24));
    let n = per_connection_layout(false);
    assert_eq!((n.option_size, n.union_offset, n.list_size, n.count_offset, n.options_offset), (12, 4, 20, 8, 16));
}
