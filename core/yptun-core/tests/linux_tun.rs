use std::path::PathBuf;

use yptun_core::linux_tun::*;

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

#[test]
fn config_matches_kotlin() {
    let got = config_content(10808, Some("/a/up.sh"), Some("/a/down.sh"), "u'x", "p", false);
    let want = r#"tunnel:
  name: olcbox0
  mtu: 1500
  multi-queue: false
  ipv4: 10.0.88.88
  post-up-script: /a/up.sh
  pre-down-script: /a/down.sh

socks5:
  address: 127.0.0.1
  port: 10808
  udp: 'udp'
  pipeline: false
  username: 'u''x'
  password: 'p'

mapdns:
  address: 1.1.1.1
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000

misc:
  task-stack-size: 24576
  tcp-buffer-size: 4096
  max-session-count: 1200
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-file: stderr
  log-level: warn"#;
    assert_eq!(got, want);

    let plain = config_content(10808, None, Some("  "), "", "secret", true);
    assert!(!plain.contains("post-up-script"));
    assert!(!plain.contains("pre-down-script"));
    assert!(!plain.contains("username"));
    assert!(!plain.contains("secret"));
    assert!(plain.contains("  ipv4: 10.0.88.88\n\nsocks5:"));
    assert!(plain.contains("  udp: 'tcp'\n"));
    assert!(!plain.ends_with('\n'));
}

#[test]
fn up_script_matches_kotlin_and_filters_bypass() {
    let got = up_script_content(&s(&[
        "1.2.3.4",
        "10.0.0.0/8",
        "1.2.3.4",
        "bad; rm -rf /",
        "1.2.3.4/123",
        "::1",
        "1234.1.1.1",
    ]));
    let want = r#"#!/bin/sh
set -eu
ip rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
while ip rule del pref 15 2>/dev/null; do :; done
ip rule del lookup 51820 pref 20 2>/dev/null || true
ip route flush table 51820 2>/dev/null || true
ip -6 rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
ip -6 rule del lookup main suppress_prefixlength 0 pref 19 2>/dev/null || true
ip -6 rule del lookup 51820 pref 20 2>/dev/null || true
ip -6 route flush table 51820 2>/dev/null || true
sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
sysctl -w net.ipv4.conf.olcbox0.rp_filter=0 >/dev/null 2>&1 || true
ip link set olcbox0 up
ip rule add uidrange 0-0 lookup main pref 10
ip rule add to 1.2.3.4 lookup main pref 15 2>/dev/null || true
ip rule add to 10.0.0.0/8 lookup main pref 15 2>/dev/null || true
ip route add default dev olcbox0 table 51820
ip rule add lookup 51820 pref 20
# The TUN is IPv4-only: without this every AAAA answer went AROUND the tunnel. Make the
# IPv6 default unreachable instead (apps fall back to IPv4 at once), the way Android
# blocks a family the VPN does not route; LAN/link-local keep their own routes (pref
# 19), root keeps IPv6 like it keeps IPv4 (olcRTC's own ICE). Best-effort:
# a kernel booted with IPv6 disabled has nothing to leak.
ip -6 rule add uidrange 0-0 lookup main pref 10 2>/dev/null || true
ip -6 rule add lookup main suppress_prefixlength 0 pref 19 2>/dev/null || true
ip -6 route add unreachable default table 51820 2>/dev/null || true
ip -6 rule add lookup 51820 pref 20 2>/dev/null || true
if command -v resolvectl >/dev/null 2>&1; then
  resolvectl dns olcbox0 1.1.1.1 >/dev/null 2>&1 || true
  resolvectl domain olcbox0 '~.' >/dev/null 2>&1 || true
  resolvectl default-route olcbox0 yes >/dev/null 2>&1 || true
elif command -v resolvconf >/dev/null 2>&1; then
  printf "nameserver %s\n" "1.1.1.1" | resolvconf -a "olcbox0" 2>/dev/null || true
fi"#;
    assert_eq!(got, want);

    let none = up_script_content(&[]);
    assert!(none.contains("ip rule add uidrange 0-0 lookup main pref 10\nip route add default dev olcbox0 table 51820\n"));
    assert!(!none.contains("pref 15 2>/dev/null || true\nip route add"));

    assert!(is_bypass_prefix("8.8.8.8"));
    assert!(is_bypass_prefix("8.8.0.0/16"));
    assert!(!is_bypass_prefix("8.8.8.8 "));
    assert!(!is_bypass_prefix("8.8.8"));
    assert!(!is_bypass_prefix("x8.8.8.8"));
}

#[test]
fn down_script_matches_kotlin() {
    let want = r#"#!/bin/sh
ip rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
while ip rule del pref 15 2>/dev/null; do :; done
ip rule del lookup 51820 pref 20 2>/dev/null || true
ip route flush table 51820 2>/dev/null || true
ip -6 rule del uidrange 0-0 lookup main pref 10 2>/dev/null || true
ip -6 rule del lookup main suppress_prefixlength 0 pref 19 2>/dev/null || true
ip -6 rule del lookup 51820 pref 20 2>/dev/null || true
ip -6 route flush table 51820 2>/dev/null || true
if command -v resolvectl >/dev/null 2>&1; then
  resolvectl revert olcbox0 >/dev/null 2>&1 || true
elif command -v resolvconf >/dev/null 2>&1; then
  resolvconf -d "olcbox0" 2>/dev/null || true
fi"#;
    assert_eq!(down_script_content(), want);
}

#[test]
fn run_and_clear_scripts() {
    let got = run_script_content(&s(&["/opt/y p/hev", "/cfg/it's.yml"]), "/d/down.sh");
    let want = r#"#!/bin/sh
'/opt/y p/hev' '/cfg/it'\''s.yml' &
HEV=$!
cat >/dev/null
kill $HEV 2>/dev/null
wait $HEV 2>/dev/null
sh '/d/down.sh' >/dev/null 2>&1
exit 0
"#;
    assert_eq!(got, want);

    assert_eq!(
        clear_script_content("/opt/hev", "/d/down.sh"),
        "#!/bin/sh\npkill -f '/opt/hev' >/dev/null 2>&1\nsh '/d/down.sh' >/dev/null 2>&1\nexit 0\n"
    );
    assert_eq!(shell_quoted("a'b"), r#"'a'\''b'"#);
    assert_eq!(yaml_quoted("a'b"), "'a''b'");
    assert_eq!(hev_command("/opt/hev", "/c/linux-tun.yml"), s(&["/opt/hev", "/c/linux-tun.yml"]));
    assert_eq!(run_command("/c/linux-tun-run.sh"), s(&["sh", "/c/linux-tun-run.sh"]));
    assert_eq!(CONFIG_FILE, "linux-tun.yml");
    assert_eq!(UP_SCRIPT, "linux-tun-up.sh");
    assert_eq!(DOWN_SCRIPT, "linux-tun-down.sh");
    assert_eq!(RUN_SCRIPT, "linux-tun-run.sh");
    assert_eq!(CLEAR_SCRIPT, "linux-tun-clear.sh");
}

#[test]
fn rule_detection_and_ready_poll() {
    assert!(route_rule_present("0:\tfrom all lookup local\n20:\tfrom all lookup 51820\n"));
    assert!(route_rule_present("from all lookup 51820 pref 20"));
    assert!(!route_rule_present("20:\tfrom all lookup main"));
    assert!(!route_rule_present("200:\tfrom all lookup 51820"));
    assert!(!route_rule_present(""));

    assert_eq!(ready_poll(true, true, Some(1)), ReadyPoll::Ready);
    assert_eq!(ready_poll(true, false, None), ReadyPoll::Wait);
    assert_eq!(ready_poll(false, false, Some(0)), ReadyPoll::Wait);
    assert_eq!(ready_poll(false, true, Some(126)), ReadyPoll::Failed(126));
}

#[test]
fn state_and_cleanup_commands() {
    let mut st = LinuxTunState::new();
    assert!(st.privileged_cleanup_commands("/d/down.sh").is_empty());
    st.prepared("/opt/hev");
    assert_eq!(
        st.privileged_cleanup_commands("/d/down.sh"),
        s(&["pkill -f '/opt/hev' >/dev/null 2>&1 || true", "sh '/d/down.sh' >/dev/null 2>&1 || true"])
    );
    assert_eq!(st.ready(), "Linux TUN connected on olcbox0");
    assert!(st.routes_installed);
    st.released(false);
    assert!(st.routes_installed && st.hev_binary.is_some());
    assert!(st.stopped());
    assert!(!st.stopped());

    let mut routes_only = LinuxTunState { routes_installed: true, hev_binary: None };
    assert_eq!(routes_only.privileged_cleanup_commands("/d"), s(&["sh '/d' >/dev/null 2>&1 || true"]));
    routes_only.released(true);
    assert_eq!(routes_only, LinuxTunState::default());

    let mut cancelled = LinuxTunState::new();
    cancelled.prepared("/h");
    assert_eq!(cancelled.launcher_failed(126), "Linux TUN bridge exited with code 126 (authorization cancelled)");
    assert!(cancelled.hev_binary.is_none());

    let mut failed = LinuxTunState::new();
    failed.prepared("/h");
    assert_eq!(failed.launcher_failed(1), "Linux TUN bridge exited with code 1");
    assert!(failed.hev_binary.is_some());
}

#[test]
fn privilege_and_messages() {
    let cmd = s(&["sh", "/r.sh"]);
    assert_eq!(privilege_command(&cmd, true, Some("sudo"), true), cmd);
    assert_eq!(privilege_command(&cmd, false, Some("SUDO"), true), s(&["sudo", "-n", "sh", "/r.sh"]));
    assert_eq!(privilege_command(&cmd, false, Some("pkexec"), false), s(&["pkexec", "sh", "/r.sh"]));
    assert_eq!(privilege_command(&cmd, false, None, true), s(&["pkexec", "sh", "/r.sh"]));
    assert_eq!(privilege_command(&cmd, false, Some("doas"), false), s(&["sudo", "-n", "sh", "/r.sh"]));
    assert!(uid_is_root("0\n"));
    assert!(!uid_is_root("1000"));
    assert_eq!(
        path_candidates("/usr/bin::/bin", "pkexec"),
        vec![PathBuf::from("/usr/bin").join("pkexec"), PathBuf::from("/bin").join("pkexec")]
    );
    assert_eq!(PRIVILEGE_ENV, "OLCBOX_LINUX_PRIVILEGE");

    assert_eq!(start_log("hev", 10808), "Starting Linux TUN bridge: hev -> 127.0.0.1:10808");
    assert_eq!(stale_log(), "A previous olcbox0 tunnel is still up \u{2014} removing it before connecting");
    assert_eq!(not_installed_error(), "olcbox0 routes were not installed");
    assert_eq!(TUN_READY_TIMEOUT_MS, 10_000);
    assert_eq!(AUTH_READY_TIMEOUT_MS, 120_000);
    assert_eq!(TUN_READY_POLL_MS, 100);
    assert_eq!(ROUTE_CLEANUP_TIMEOUT_MS, 2_000);
    assert_eq!(RELEASE_TIMEOUT_MS, 5_000);
}
