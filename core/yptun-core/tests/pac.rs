use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use yptun_core::pac::{generate_pac, percent_encode_user_info, socks_proxy_target, PacServer, CONTENT_TYPE};

fn pac_for(target: &str) -> String {
    format!(
        "function FindProxyForURL(url, host) {{\n  if (isPlainHostName(host)) return \"DIRECT\";\n  if (host == \"localhost\" || host == \"127.0.0.1\" || host == \"::1\") return \"DIRECT\";\n  if (shExpMatch(host, \"127.*\")) return \"DIRECT\";\n  return \"SOCKS5 {target}; SOCKS {target}\";\n}}"
    )
}

fn http_get(addr: SocketAddr, path: &str) -> String {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    s.write_all(format!("GET {path} HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes()).unwrap();
    let mut out = String::new();
    s.read_to_string(&mut out).unwrap();
    out
}

#[test]
fn pac_text() {
    assert_eq!(generate_pac("127.0.0.1", 10808, "", ""), pac_for("127.0.0.1:10808"));
    assert_eq!(generate_pac(" ", 1080, "", "secret"), pac_for("127.0.0.1:1080"));
}

#[test]
fn user_info() {
    assert_eq!(percent_encode_user_info("aZ09-._~"), "aZ09-._~");
    assert_eq!(percent_encode_user_info("us er@:/"), "us%20er%40%3A%2F");
    assert_eq!(percent_encode_user_info("п"), "%D0%BF");
    assert_eq!(socks_proxy_target("10.0.0.1", 1080, "u", ""), "u@10.0.0.1:1080");
    assert_eq!(socks_proxy_target("10.0.0.1", 1080, "u", "p@ss:"), "u:p%40ss%3A@10.0.0.1:1080");
    assert_eq!(socks_proxy_target("", 1080, "  ", "p"), "127.0.0.1:1080");
}

#[test]
fn url_uses_configured_port() {
    assert_eq!(PacServer::default().url(), "http://127.0.0.1:10809/proxy.pac");
    assert_eq!(PacServer::new("0.0.0.0", 1234).url(), "http://0.0.0.0:1234/proxy.pac");
}

#[test]
fn serves_any_path_and_follows_target() {
    let mut server = PacServer::new("127.0.0.1", 0);
    server.start("127.0.0.1", 1080, "", "").unwrap();
    let addr = server.local_addr().unwrap();
    for path in ["/proxy.pac", "/", "/anything"] {
        let r = http_get(addr, path);
        assert!(r.starts_with("HTTP/1.1 200 OK\r\n"), "{r}");
        assert!(r.contains(&format!("Content-Type: {CONTENT_TYPE}\r\n")));
        assert!(r.ends_with(&pac_for("127.0.0.1:1080")));
    }
    server.update_socks_target("", 2000, "u", "");
    assert_eq!(server.current_pac_content(), pac_for("u@127.0.0.1:2000"));
    assert!(http_get(addr, "/").ends_with(&pac_for("u@127.0.0.1:2000")));

    server.start("10.0.0.1", 3000, "", "").unwrap();
    assert_eq!(server.local_addr(), Some(addr));
    assert!(http_get(addr, "/").ends_with(&pac_for("10.0.0.1:3000")));

    server.stop();
    assert!(!server.is_running());
    assert!(server.local_addr().is_none());
    assert!(TcpStream::connect_timeout(&addr, Duration::from_secs(1)).is_err());
    server.stop();
}

#[test]
fn bind_conflict_is_an_error() {
    let busy = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = busy.local_addr().unwrap().port();
    let mut server = PacServer::new("127.0.0.1", port);
    assert!(server.start("127.0.0.1", 1080, "", "").is_err());
    assert!(!server.is_running());
}
