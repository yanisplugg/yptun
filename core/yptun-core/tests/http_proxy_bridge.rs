use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use yptun_core::http_proxy_bridge::{self as hb, HttpProxyBridge};

type Seen = Arc<Mutex<Vec<String>>>;

fn serve(mut s: TcpStream, fail: bool, seen: &Seen) -> std::io::Result<()> {
    let mut hello = [0u8; 3];
    s.read_exact(&mut hello)?;
    assert_eq!(hello, [5, 1, 0]);
    s.write_all(&[5, 0])?;
    let mut h = [0u8; 5];
    s.read_exact(&mut h)?;
    assert_eq!(&h[..4], &[5, 1, 0, 3]);
    let mut host = vec![0u8; h[4] as usize];
    s.read_exact(&mut host)?;
    let mut p = [0u8; 2];
    s.read_exact(&mut p)?;
    seen.lock().unwrap().push(format!("{}:{}", String::from_utf8_lossy(&host), u16::from_be_bytes(p)));
    if fail {
        return s.write_all(&[5, 1, 0, 1, 0, 0, 0, 0, 0, 0]);
    }
    s.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0])?;
    let mut data = Vec::new();
    s.read_to_end(&mut data)?;
    let mut out = b"echo:".to_vec();
    out.extend(data);
    s.write_all(&out)
}

fn fake_socks(fail: bool) -> (u16, Seen) {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    let seen: Seen = Arc::new(Mutex::new(Vec::new()));
    let s2 = seen.clone();
    thread::spawn(move || {
        for s in l.incoming().flatten() {
            let seen = s2.clone();
            thread::spawn(move || {
                let _ = serve(s, fail, &seen);
            });
        }
    });
    (port, seen)
}

fn bridge(socks_port: u16) -> (HttpProxyBridge, SocketAddr) {
    let b = HttpProxyBridge::new("127.0.0.1", 0, "127.0.0.1", socks_port, |_: &str| {});
    assert!(b.start());
    let addr = b.local_addr().unwrap();
    (b, addr)
}

fn exchange(addr: SocketAddr, req: &[u8]) -> String {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    s.write_all(req).unwrap();
    s.shutdown(Shutdown::Write).unwrap();
    let mut out = Vec::new();
    let _ = s.read_to_end(&mut out);
    String::from_utf8_lossy(&out).into_owned()
}

#[test]
fn helpers() {
    assert_eq!(hb::split_host_port("example.com:443", 80), ("example.com".to_string(), 443));
    assert_eq!(hb::split_host_port("example.com", 80), ("example.com".to_string(), 80));
    assert_eq!(hb::split_host_port("[::1]:8443", 443), ("::1".to_string(), 8443));
    assert_eq!(hb::split_host_port("[::1]", 443), ("::1".to_string(), 443));
    assert_eq!(hb::split_host_port("host:abc", 80), ("host".to_string(), 80));
    assert_eq!(hb::host_port_from_absolute_uri("http://a.b:8080/x"), Some(("a.b".to_string(), 8080)));
    assert_eq!(hb::host_port_from_absolute_uri("http://a.b"), Some(("a.b".to_string(), 80)));
    assert_eq!(hb::host_port_from_absolute_uri("/x"), None);
    assert_eq!(hb::host_port_from_absolute_uri("http:///x"), None);
    assert_eq!(hb::host_from_headers("GET / HTTP/1.1\r\nhost: h.example:81\r\n\r\n"), Some(("h.example".to_string(), 81)));
    assert_eq!(hb::host_from_headers("GET / HTTP/1.1\r\n\r\n"), None);
    assert_eq!(hb::origin_form("http://a/b?c"), "/b?c");
    assert_eq!(hb::origin_form("http://a"), "/");
    assert_eq!(hb::origin_form("/p"), "/p");
}

#[test]
fn connect_tunnel() {
    let (port, seen) = fake_socks(false);
    let (_b, addr) = bridge(port);
    let r = exchange(addr, b"CONNECT secure.test:8443 HTTP/1.1\r\nHost: secure.test:8443\r\n\r\nping");
    assert_eq!(r, "HTTP/1.1 200 Connection Established\r\n\r\necho:ping");
    assert_eq!(seen.lock().unwrap().as_slice(), ["secure.test:8443"]);
}

#[test]
fn plain_http_is_rewritten_to_origin_form() {
    let (port, seen) = fake_socks(false);
    let (_b, addr) = bridge(port);
    let r = exchange(addr, b"GET http://example.com:8080/a?b=1 HTTP/1.1\r\nHost: example.com:8080\r\nX: y\r\n\r\n");
    assert_eq!(r, "echo:GET /a?b=1 HTTP/1.1\r\nHost: example.com:8080\r\nX: y\r\n\r\n");
    let r = exchange(addr, b"get /p HTTP/1.0\r\nHost: h.test\r\n\r\n");
    assert_eq!(r, "echo:GET /p HTTP/1.0\r\nHost: h.test\r\n\r\n");
    assert_eq!(seen.lock().unwrap().as_slice(), ["example.com:8080", "h.test:80"]);
}

#[test]
fn errors() {
    let (port, _) = fake_socks(true);
    let (_b, addr) = bridge(port);
    assert_eq!(exchange(addr, b"CONNECT a.test:443 HTTP/1.1\r\n\r\n"), "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
    assert_eq!(exchange(addr, b"GET /x HTTP/1.1\r\n\r\n"), "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    assert_eq!(exchange(addr, b"HELLO\r\n\r\n"), "");

    let dead = TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let (_b2, addr2) = bridge(dead);
    assert_eq!(exchange(addr2, b"GET http://a.test/ HTTP/1.1\r\n\r\n"), "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
}

#[test]
fn bind_conflict_and_stop() {
    let busy = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = busy.local_addr().unwrap().port();
    let logs = Arc::new(Mutex::new(Vec::<String>::new()));
    let l = logs.clone();
    let b = HttpProxyBridge::new("127.0.0.1", port, "127.0.0.1", 1, move |s: &str| l.lock().unwrap().push(s.to_string()));
    assert!(!b.start());
    let line = logs.lock().unwrap()[0].clone();
    assert!(line.starts_with(&format!("HTTP proxy: failed to bind 127.0.0.1:{port} — ")), "{line}");

    let (b, addr) = bridge(1);
    b.stop();
    assert!(b.local_addr().is_none());
    assert!(TcpStream::connect_timeout(&addr, Duration::from_secs(1)).is_err());
}
