use yptun_core::windows_tun::*;

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

#[test]
fn tun2socks_command_and_encoding() {
    assert_eq!(
        tun2socks_command("C:\\y\\tun2socks.exe", 10808, "", "x"),
        s(&[
            "C:\\y\\tun2socks.exe",
            "--device",
            "YPtun",
            "--proxy",
            "socks5://127.0.0.1:10808",
            "--mtu",
            "1500",
            "--loglevel",
            "warn",
        ])
    );
    let c = tun2socks_command("t", 2000, "us er+", "p@ss/\u{fc}*~");
    assert_eq!(c[4], "socks5://us%20er%2B:p%40ss%2F%C3%BC*%7E@127.0.0.1:2000");
    assert_eq!(tun2socks_command("t", 1, "  ", "x")[4], "socks5://127.0.0.1:1");
    assert_eq!(url_encoded("a.b-c_d*"), "a.b-c_d*");
}

#[test]
fn destination_prefixes() {
    assert_eq!(to_destination_prefix(" 1.2.3.4 "), "1.2.3.4/32");
    assert_eq!(to_destination_prefix("5.6.0.0/16"), "5.6.0.0/16");
    assert_eq!(powershell_literal("o'k"), "'o''k'");
}

#[test]
fn install_script_without_bypass() {
    let want = r#"$ErrorActionPreference = 'Stop'


$adapter = Get-NetAdapter -Name 'YPtun' -ErrorAction Stop
$ifIndex = $adapter.ifIndex

Get-NetIPAddress -InterfaceIndex $ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.IPAddress -eq '10.0.88.88' } |
  Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue

New-NetIPAddress -InterfaceIndex $ifIndex -IPAddress '10.0.88.88' -PrefixLength 24 -AddressFamily IPv4 | Out-Null

Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue

New-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
New-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ServerAddresses '1.1.1.1'"#;
    let got = install_routes_script(&[]);
    assert_eq!(got, want);
    assert!(!got.contains("$gateway"));
}

#[test]
fn install_script_with_bypass() {
    let got = install_routes_script(&s(&["1.2.3.4", " 5.6.0.0/16 ", "1.2.3.4'; Remove-Item x; '"]));
    assert!(got.starts_with("$ErrorActionPreference = 'Stop'\n$default = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |\n"));
    assert!(got.contains("  Where-Object { $_.InterfaceAlias -ne 'YPtun' -and $_.NextHop -ne '0.0.0.0' } |\n"));
    assert!(got.contains("if ($null -eq $default) { throw 'No physical default gateway found' }\n"));
    let phys = got.find("$physIfIndex = $default.InterfaceIndex").unwrap();
    let first = got
        .find("Get-NetRoute -DestinationPrefix '1.2.3.4/32' -ErrorAction SilentlyContinue |\n  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue\nNew-NetRoute -InterfaceIndex $physIfIndex -DestinationPrefix '1.2.3.4/32' -NextHop $gateway -RouteMetric 1 | Out-Null")
        .unwrap();
    let second = got.find("-DestinationPrefix '5.6.0.0/16' -NextHop $gateway").unwrap();
    let adapter = got.find("$adapter = Get-NetAdapter -Name 'YPtun' -ErrorAction Stop").unwrap();
    assert!(phys < first && first < second && second < adapter);
    assert!(got.contains("'1.2.3.4''; Remove-Item x; ''/32'"));
    assert!(got.ends_with("Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ServerAddresses '1.1.1.1'"));
}

#[test]
fn remove_script() {
    let want = r#"$adapter = Get-NetAdapter -Name 'YPtun' -ErrorAction SilentlyContinue
if ($null -eq $adapter) { exit 0 }
$ifIndex = $adapter.ifIndex
Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
Get-NetRoute -InterfaceIndex $ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
Get-NetIPAddress -InterfaceIndex $ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.IPAddress -eq '10.0.88.88' } |
  Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue"#;
    assert_eq!(remove_routes_script(&[]), want);
    let with = remove_routes_script(&s(&["1.2.3.4", "9.9.0.0/16"]));
    assert!(with.starts_with(
        "Get-NetRoute -DestinationPrefix '1.2.3.4/32' -ErrorAction SilentlyContinue |\n  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue\nGet-NetRoute -DestinationPrefix '9.9.0.0/16' -ErrorAction SilentlyContinue |\n  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue\n$adapter = "
    ));
    assert!(with.ends_with(want));
}

#[test]
fn powershell_runner_and_admin() {
    assert_eq!(
        powershell_command("C:\\t\\yptun-tun-1.ps1"),
        s(&["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", "C:\\t\\yptun-tun-1.ps1"])
    );
    assert_eq!(powershell_error(1, "boom"), "PowerShell failed with code 1: boom");
    assert_eq!(PS_SCRIPT_PREFIX, "yptun-tun-");
    assert_eq!(PS_SCRIPT_SUFFIX, ".ps1");
    assert!(parse_is_admin(" True\r\n"));
    assert!(!parse_is_admin("false"));
    assert!(IS_ADMIN_SCRIPT.contains("[Security.Principal.WindowsBuiltInRole]::Administrator"));
    assert_eq!(REQUEST_ADMIN_LOG, "Requesting Windows administrator privileges for TUN mode");
}

#[test]
fn command_line_quoting() {
    assert_eq!(windows_command_line_argument(""), r#""""#);
    assert_eq!(windows_command_line_argument("abc"), "abc");
    assert_eq!(windows_command_line_argument(r"C:\x"), r"C:\x");
    assert_eq!(windows_command_line_argument("a b"), r#""a b""#);
    assert_eq!(windows_command_line_argument(r#"a"b"#), r#""a\"b""#);
    assert_eq!(windows_command_line_argument(r"C:\dir name\"), r#""C:\dir name\\""#);
    assert_eq!(windows_command_line_argument(r#"a\\"b"#), r#""a\\\\\"b""#);
    assert_eq!(windows_command_line_argument("tab\there"), "\"tab\there\"");
}

#[test]
fn restart_script_and_arguments() {
    let args = restart_arguments(&s(&["--x", "a b"]));
    assert_eq!(args, s(&["--x", "a b", "--olcbox-start-vpn-after-elevation"]));
    assert_eq!(restart_arguments(&args), args);

    let want = r#"$ErrorActionPreference = 'Stop'
$startArgs = @{
  FilePath = 'C:\Program Files\YPtun\YPtun.exe'
  Verb = 'RunAs'
  ArgumentList = '--x "a b" --olcbox-start-vpn-after-elevation'
  WorkingDirectory = 'C:\Users\o''k'
}
Start-Process @startArgs | Out-Null"#;
    assert_eq!(restart_as_administrator_script(r"C:\Program Files\YPtun\YPtun.exe", &args, r"C:\Users\o'k"), want);

    let no_dir = restart_as_administrator_script("y.exe", &[], "");
    assert_eq!(
        no_dir,
        "$ErrorActionPreference = 'Stop'\n$startArgs = @{\n  FilePath = 'y.exe'\n  Verb = 'RunAs'\n  ArgumentList = ''\n\n}\nStart-Process @startArgs | Out-Null"
    );
}

#[test]
fn errors_and_logs() {
    assert_eq!(exited_before_ready_error("  "), "tun2socks exited before YPtun was ready");
    assert_eq!(exited_before_ready_error(" wintun failed \n"), "tun2socks exited before YPtun was ready: wintun failed");
    assert_eq!(adapter_missing_error(""), "YPtun adapter was not created");
    assert_eq!(adapter_missing_error("x"), "YPtun adapter was not created: x");
    assert_eq!(partial_cleanup_failed_log("e"), "Windows TUN partial route cleanup failed: e");
    assert_eq!(cleanup_failed_log("e"), "Windows TUN route cleanup failed: e");
    assert_eq!(TUN_READY_TIMEOUT_MS, 10_000);
    assert_eq!(TUN_READY_POLL_MS, 100);
    assert_eq!(PROCESS_STOP_TIMEOUT_MS, 3_000);
    assert_eq!(PROCESS_KILL_TIMEOUT_MS, 1_000);
}

#[test]
fn route_bookkeeping() {
    let mut st = WindowsTunState::new();
    assert_eq!(st.stop(), None);
    let list = st.begin(&s(&["1.1.1.1", "2.2.2.2", "1.1.1.1"]));
    assert_eq!(list, s(&["1.1.1.1", "2.2.2.2"]));
    assert_eq!(
        st.installed(),
        s(&["Installed 2 bypass route(s) for proxy server(s)", "Windows TUN connected on YPtun"])
    );
    assert_eq!(st.stop(), Some(s(&["1.1.1.1", "2.2.2.2"])));
    assert_eq!(st, WindowsTunState::default());

    st.begin(&[]);
    assert_eq!(st.installed(), s(&["Windows TUN connected on YPtun"]));
    assert_eq!(st.stop(), Some(vec![]));

    st.begin(&s(&["3.3.3.3"]));
    assert_eq!(st.failed(), s(&["3.3.3.3"]));
    assert_eq!(st.stop(), None);
}
