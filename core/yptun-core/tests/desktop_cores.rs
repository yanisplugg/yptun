use std::cell::Cell;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::Path;
use std::thread;

use yptun_core::desktop_cores::*;
use yptun_core::snolc;
use yptun_core::transports::{OpenFluxConfig, SnolcConfig};

fn v(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| s.to_string()).collect()
}

#[test]
fn process_state_and_log_lines() {
    assert_eq!(exit_description(ProcessState::NotStarted), "not started");
    assert_eq!(exit_description(ProcessState::Alive), "still starting");
    assert_eq!(exit_description(ProcessState::Exited(3)), "exited with 3");
    assert_eq!(core_log_line("snolc", "  up  \r"), Some("snolc:   up".to_string()));
    assert_eq!(core_log_line("openflux", "   "), None);
    assert_eq!(stop_failed_log("OpenFlux", "boom"), "OpenFlux stop failed: boom");
}

#[test]
fn snolc_files_and_command() {
    let mut c = SnolcConfig::default();
    c.host = "node.example".to_string();
    c.public_key = "ab".repeat(32);
    c.debug = true;
    let files = snolc_files(&c, "203.0.113.5", "127.0.0.1", 10808, "u", "p");
    let expected = snolc::client("127.0.0.1", 10808, "203.0.113.5", c.port, true, "u", "p");
    assert_eq!(files.len(), expected.len() + 1);
    assert_eq!(&files[..expected.len()], &expected[..]);
    assert_eq!(files.last().unwrap(), &("pub.hex".to_string(), "ab".repeat(32)));

    let dir = Path::new("/data/snolc");
    assert_eq!(
        snolc_command("/bin/snolc", dir),
        vec![
            "/bin/snolc".to_string(),
            "run".to_string(),
            dir.join("snolc.toml").to_string_lossy().into_owned(),
        ]
    );
    assert_eq!(
        snolc_start_log(&c, "127.0.0.1", 10808),
        format!("Starting snolc ({}) on 127.0.0.1:10808", c.summary())
    );
    assert_eq!(SNOLC_EXIT_ENV, ("SNOLC_EXIT_ON_STDIN_EOF", "1"));
}

#[test]
fn openflux_command_env_and_log() {
    let mut c = OpenFluxConfig::default();
    c.debug = false;
    c.dns_server = String::new();
    c.secret = String::new();
    let mut expected = v(&["of", "--role=client", "--inbound=socks5", "--socks5", "127.0.0.1:10808"]);
    expected.extend(c.carrier_args());
    expected.push("--exit-on-stdin-eof".to_string());
    assert_eq!(openflux_command("of", &c, "127.0.0.1", 10808, None), expected);
    assert!(!openflux_needs_key_file(&c));
    assert_eq!(
        openflux_start_log(&c, "127.0.0.1", 10808),
        format!("Starting OpenFlux ({}) on 127.0.0.1:10808, dns=system", c.summary())
    );

    c.debug = true;
    c.dns_server = "1.1.1.1".to_string();
    c.secret = "s3cret".to_string();
    c.max_token = "tok".to_string();
    c.avito_cookies = "ck".to_string();
    assert!(openflux_needs_key_file(&c));
    let mut full = v(&["of", "--role=client", "--inbound=socks5", "--socks5", "127.0.0.1:10808"]);
    full.extend(c.carrier_args());
    full.extend(v(&["--dns", "1.1.1.1"]));
    full.extend(c.encryption_args("/tmp/k.txt"));
    full.extend(v(&["--debug", "--exit-on-stdin-eof"]));
    assert_eq!(openflux_command("of", &c, "127.0.0.1", 10808, Some("/tmp/k.txt")), full);
    assert!(!openflux_command("of", &c, "127.0.0.1", 10808, None).iter().any(|a| a.contains("s3cret")));
    assert_eq!(
        openflux_env(&c, "u", "p"),
        vec![
            ("OPENFLUX_MAX_TOKEN".to_string(), "tok".to_string()),
            ("OPENFLUX_AVITO_COOKIES".to_string(), "ck".to_string()),
            ("OPENFLUX_SOCKS_USER".to_string(), "u".to_string()),
            ("OPENFLUX_SOCKS_PASS".to_string(), "p".to_string()),
        ]
    );
    assert!(openflux_start_log(&c, "h", 1).ends_with(", dns=1.1.1.1"));
}

#[test]
fn trust_tunnel_config_and_commands() {
    assert_eq!(
        tt_compose_config("  [endpoint]\nhostname = \"x\"\n\n", 1090),
        "loglevel = \"info\"\nvpn_mode = \"general\"\nkillswitch_enabled = false\n\n[endpoint]\nhostname = \"x\"\n\n[listener.socks]\naddress = \"127.0.0.1:1090\"\n"
    );
    let cfg = Path::new("/w/client.toml");
    assert_eq!(
        tt_client_command("ttc", cfg),
        vec!["ttc".to_string(), "--config".to_string(), cfg.to_string_lossy().into_owned()]
    );
    let set = Path::new("/w/wizard-settings.toml");
    assert_eq!(
        tt_wizard_command("wiz", "tt://abc", set),
        vec![
            "wiz".to_string(),
            "--mode".to_string(),
            "non-interactive".to_string(),
            "--deeplink".to_string(),
            "tt://abc".to_string(),
            "--settings".to_string(),
            set.to_string_lossy().into_owned(),
        ]
    );
    assert_eq!(tt_start_log(1090), "Starting Trust Tunnel SOCKS on 127.0.0.1:1090");
    assert_eq!(TT_WIZARD_ENV, ("__COMPAT_LAYER", "RunAsInvoker"));
}

const DOC: &str = "loglevel = \"info\"\r\n\r\n[endpoint]\r\nhostname = \"vpn.example.com\"\r\naddresses = [\"1.2.3.4:443\", \"[2001:db8::1]:443\"]\r\ncertificate = \"\"\"\r\n-----BEGIN CERTIFICATE-----\r\nMII\r\n-----END CERTIFICATE-----\r\n\"\"\"\r\n\r\n[listener.tun]\r\nmtu = 1500\r\n";

const TABLE: &str = "[endpoint]\nhostname = \"vpn.example.com\"\naddresses = [\"1.2.3.4:443\", \"[2001:db8::1]:443\"]\ncertificate = \"\"\"\n-----BEGIN CERTIFICATE-----\nMII\n-----END CERTIFICATE-----\n\"\"\"";

#[test]
fn endpoint_table_extraction() {
    assert_eq!(extract_endpoint_table(DOC).as_deref(), Some(TABLE));
    assert_eq!(extract_endpoint_table("[listener.tun]\nmtu = 1\n"), None);
    assert_eq!(
        extract_endpoint_table("a = 1\n  [endpoint]  \nx = 2\n\n").as_deref(),
        Some("[endpoint]\nx = 2")
    );
}

#[test]
fn endpoint_hosts_and_ports() {
    let t = "[endpoint]\n  addresses = [\"1.2.3.4:443\", \"[2001:db8::1]:443\", \"vpn.example.com:8443\", \"2001:db8::2\", \"1.2.3.4:80\", \" \"]\n";
    assert_eq!(
        endpoint_hosts_from_table(t),
        v(&["1.2.3.4", "2001:db8::1", "vpn.example.com", "2001:db8::2"])
    );
    assert!(endpoint_hosts_from_table("[endpoint]\nhostname = \"x\"\n").is_empty());
    assert_eq!(strip_port("[::1]"), "::1");
    assert_eq!(strip_port(" host "), "host");
    assert_eq!(strip_port("h:1"), "h");
}

#[test]
fn wizard_result_and_cache() {
    assert_eq!(tt_wizard_result(0, Some(DOC), "").unwrap(), TABLE);
    assert_eq!(
        tt_wizard_result(1, Some(DOC), "boom\n").unwrap_err(),
        "Trust Tunnel: invalid tt:// link (setup_wizard exited 1): boom"
    );
    assert_eq!(
        tt_wizard_result(0, None, " x ").unwrap_err(),
        "Trust Tunnel: invalid tt:// link (setup_wizard exited 0): x"
    );
    assert_eq!(tt_wizard_result(0, Some("[other]\n"), "").unwrap_err(), TT_ERR_NO_ENDPOINT);
    let long = format!("{}b", "a".repeat(500));
    let err = tt_wizard_result(2, None, &long).unwrap_err();
    assert!(err.ends_with(&format!("{}b", "a".repeat(399))));
    assert!(!err.contains(&"a".repeat(400)));

    let calls = Cell::new(0);
    let mut links = TrustTunnelLinks::new();
    let mut decode = |link: &str| -> Result<String, String> {
        calls.set(calls.get() + 1);
        if link == "tt://good" {
            Ok(TABLE.to_string())
        } else {
            Err("bad".to_string())
        }
    };
    assert_eq!(links.endpoint_table("tt://good", &mut decode).unwrap(), TABLE);
    assert_eq!(links.endpoint_hosts("tt://good", &mut decode), v(&["1.2.3.4", "2001:db8::1"]));
    assert_eq!(calls.get(), 1);
    assert!(links.endpoint_table("tt://bad", &mut decode).is_err());
    assert!(links.endpoint_hosts("tt://bad", &mut decode).is_empty());
    assert_eq!(calls.get(), 3);
}

fn socks_server(reply: Vec<u8>) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        let mut greet = [0u8; 3];
        s.read_exact(&mut greet).unwrap();
        assert_eq!(greet, TT_PROBE_GREETING);
        s.write_all(&reply).unwrap();
        if reply[1] == 0 {
            let mut req = [0u8; 10];
            s.read_exact(&mut req).unwrap();
            assert_eq!(req, TT_PROBE_CONNECT);
            s.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]).unwrap();
        }
    });
    port
}

#[test]
fn warmup_probe() {
    assert!(tt_probe_connect(socks_server(vec![5, 0])));
    assert!(!tt_probe_connect(socks_server(vec![5, 0xFF])));

    let dead = {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    assert!(!tt_probe_connect(dead));
    assert!(!tt_await_tunnel_ready(dead, 500, &|| false));
}
