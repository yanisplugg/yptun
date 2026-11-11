//! Tiny HTTP/HTTPS proxy that forwards everything through a local SOCKS5 (port of HttpProxyBridge).
//!
//! Browsers (Chrome, the Android Wi-Fi proxy, desktop-over-LAN) speak an HTTP proxy, NOT SOCKS, so a
//! SOCKS-only listener is silently bypassed and leaks the real IP. This bridge gives the app's local
//! proxy a real HTTP port, like Happ's local-proxy mode (SOCKS5 Port + HTTP Port). Engine-agnostic: it
//! dials the core's local SOCKS5 (user/pass when set), so it works for xray / sing-box / olcRTC / VK-TURN /
//! MasterDNS alike. Started ONLY in Proxy mode; bind 0.0.0.0 to be reachable from loopback AND the LAN.

use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const CLIENT_TIMEOUT_MS: u64 = 30_000;
pub const SOCKS_CONNECT_TIMEOUT_MS: u64 = 5_000;
pub const SOCKS_IO_TIMEOUT_MS: u64 = 20_000;
pub const MAX_HEAD_BYTES: usize = 16 * 1024;
const ACCEPT_POLL_MS: u64 = 25;

fn ms(v: u64) -> Duration {
    Duration::from_millis(v)
}

pub struct HttpProxyBridge {
    listen_host: String,
    listen_port: u16,
    socks_host: String,
    socks_port: u16,
    socks_username: String,
    socks_password: String,
    log: Arc<dyn Fn(&str) + Send + Sync>,
    stop: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    local: Mutex<Option<SocketAddr>>,
}

impl HttpProxyBridge {
    pub fn new(listen_host: &str, listen_port: u16, socks_host: &str, socks_port: u16, log: impl Fn(&str) + Send + Sync + 'static) -> Self {
        HttpProxyBridge {
            listen_host: listen_host.to_string(),
            listen_port,
            socks_host: socks_host.to_string(),
            socks_port,
            socks_username: String::new(),
            socks_password: String::new(),
            log: Arc::new(log),
            stop: Arc::new(AtomicBool::new(false)),
            worker: Mutex::new(None),
            local: Mutex::new(None),
        }
    }

    /// SOCKS5 username/password for the upstream (Kotlin socksUsername/socksPassword). A blank
    /// username keeps the no-auth handshake.
    pub fn with_auth(mut self, username: &str, password: &str) -> Self {
        self.socks_username = username.to_string();
        self.socks_password = password.to_string();
        self
    }

    /// The bound address while running (useful with port 0).
    pub fn local_addr(&self) -> Option<SocketAddr> {
        *self.local.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn start(&self) -> bool {
        let fail = |e: String| {
            (self.log)(&format!("HTTP proxy: failed to bind {}:{} — {}", self.listen_host, self.listen_port, e));
            false
        };
        let listener = match TcpListener::bind((self.listen_host.as_str(), self.listen_port)) {
            Ok(l) => l,
            Err(e) => return fail(e.to_string()),
        };
        if let Err(e) = listener.set_nonblocking(true) {
            return fail(e.to_string());
        }
        let local = listener.local_addr().ok();
        let stop = self.stop.clone();
        let (socks_host, socks_port) = (self.socks_host.clone(), self.socks_port);
        let (socks_user, socks_pass) = (self.socks_username.clone(), self.socks_password.clone());
        let spawned = thread::Builder::new().name("http-proxy-bridge".into()).spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((client, _)) => {
                        let host = socks_host.clone();
                        let (user, pass) = (socks_user.clone(), socks_pass.clone());
                        thread::spawn(move || handle(client, &host, socks_port, &user, &pass));
                    }
                    Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => thread::sleep(ms(ACCEPT_POLL_MS)),
                    Err(_) => return, // listener closed
                }
            }
        });
        match spawned {
            Ok(h) => {
                *self.local.lock().unwrap_or_else(|e| e.into_inner()) = local;
                *self.worker.lock().unwrap_or_else(|e| e.into_inner()) = Some(h);
                true
            }
            Err(e) => fail(e.to_string()),
        }
    }

    /// Closes the listener. Connections already being piped finish on their own, as in Kotlin.
    pub fn stop(&self) {
        self.stop.store(true, Ordering::SeqCst);
        let worker = self.worker.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(h) = worker {
            let _ = h.join();
        }
        *self.local.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }
}

impl Drop for HttpProxyBridge {
    fn drop(&mut self) {
        self.stop();
    }
}

fn latin1_decode(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

fn latin1_encode(s: &str) -> Vec<u8> {
    s.chars().map(|c| c as u32 as u8).collect()
}

fn handle(mut client: TcpStream, socks_host: &str, socks_port: u16, socks_user: &str, socks_pass: &str) {
    let _ = client.set_nonblocking(false);
    let _ = client.set_read_timeout(Some(ms(CLIENT_TIMEOUT_MS)));
    let Some(head) = read_http_head(&mut client) else { return };
    let first_line = head.split("\r\n").next().unwrap_or("");
    let tokens: Vec<&str> = first_line.split(' ').collect();
    if tokens.len() < 3 {
        return;
    }
    let method = tokens[0].to_uppercase();
    let target = tokens[1];
    let version = tokens[2];

    if method == "CONNECT" {
        // HTTPS: client wants a raw tunnel to host:port.
        let (host, port) = split_host_port(target, 443);
        let Some(remote) = dial_via_socks(socks_host, socks_port, socks_user, socks_pass, &host, port) else {
            write_error(&mut client, "502 Bad Gateway");
            return;
        };
        if client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").is_err() {
            return;
        }
        let _ = client.flush();
        let _ = client.set_read_timeout(None);
        pipe(client, remote);
    } else {
        // Plain HTTP: absolute-form request line (GET http://host/path ...). Dial host:port, rewrite the
        // request line to origin-form, forward the already-read head and then stream the rest.
        let Some((host, port)) = host_port_from_absolute_uri(target).or_else(|| host_from_headers(&head)) else {
            write_error(&mut client, "400 Bad Request");
            return;
        };
        let Some(mut remote) = dial_via_socks(socks_host, socks_port, socks_user, socks_pass, &host, port) else {
            write_error(&mut client, "502 Bad Gateway");
            return;
        };
        let rest = match head.find("\r\n") {
            Some(i) => &head[i + 2..],
            None => head.as_str(),
        };
        let rewritten = format!("{} {} {}\r\n{}", method, origin_form(target), version, rest);
        if remote.write_all(&latin1_encode(&rewritten)).is_err() {
            return;
        }
        let _ = remote.flush();
        let _ = client.set_read_timeout(None);
        pipe(client, remote);
    }
}

/// Opens the local SOCKS5 (username/password when set, else no-auth) and CONNECTs to host:port
/// (by domain). None on any failure.
fn dial_via_socks(
    socks_host: &str,
    socks_port: u16,
    socks_user: &str,
    socks_pass: &str,
    host: &str,
    port: i32,
) -> Option<TcpStream> {
    let addr = (socks_host, socks_port).to_socket_addrs().ok()?.next()?;
    let mut sock = TcpStream::connect_timeout(&addr, ms(SOCKS_CONNECT_TIMEOUT_MS)).ok()?;
    sock.set_read_timeout(Some(ms(SOCKS_IO_TIMEOUT_MS))).ok()?;

    let wants_auth = !socks_user.trim().is_empty();
    if wants_auth {
        sock.write_all(&[0x05, 0x02, 0x00, 0x02]).ok()?;
    } else {
        sock.write_all(&[0x05, 0x01, 0x00]).ok()?;
    }
    let mut method = [0u8; 2];
    sock.read_exact(&mut method).ok()?;
    if method[0] != 0x05 {
        return None;
    }
    match method[1] {
        0x00 => {}
        0x02 => {
            if !wants_auth {
                return None;
            }
            sock.write_all(&socks_auth_request(socks_user, socks_pass)).ok()?;
            let mut reply = [0u8; 2];
            sock.read_exact(&mut reply).ok()?;
            if reply[1] != 0x00 {
                return None;
            }
        }
        _ => return None,
    }

    let host_bytes: Vec<u8> = host.chars().map(|c| if c.is_ascii() { c as u8 } else { b'?' }).collect();
    let mut req = vec![0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8];
    req.extend_from_slice(&host_bytes);
    req.push((port >> 8) as u8);
    req.push(port as u8);
    sock.write_all(&req).ok()?;

    let mut rep = [0u8; 4];
    sock.read_exact(&mut rep).ok()?;
    if rep[1] != 0x00 {
        return None;
    }
    let bound_len = match rep[3] {
        0x01 => 4 + 2,
        0x04 => 16 + 2,
        0x03 => {
            let mut l = [0u8; 1];
            sock.read_exact(&mut l).ok()?;
            l[0] as usize + 2
        }
        _ => return None,
    };
    let mut bound = vec![0u8; bound_len];
    sock.read_exact(&mut bound).ok()?;
    sock.set_read_timeout(None).ok()?;
    Some(sock)
}

/// RFC 1929 username/password sub-negotiation (lengths are one byte each, as in Kotlin).
pub fn socks_auth_request(username: &str, password: &str) -> Vec<u8> {
    let user = username.as_bytes();
    let pass = password.as_bytes();
    let mut auth = Vec::with_capacity(3 + user.len() + pass.len());
    auth.push(0x01);
    auth.push(user.len() as u8);
    auth.extend_from_slice(user);
    auth.push(pass.len() as u8);
    auth.extend_from_slice(pass);
    auth
}

/// Offset of the HTTP port from the configured SOCKS port (same +4 Android uses).
pub const HTTP_PORT_OFFSET: i32 = 4;

pub fn http_port_for(socks_port: i32) -> i32 {
    socks_port + HTTP_PORT_OFFSET
}

fn pipe(a: TcpStream, b: TcpStream) {
    let (Ok(mut a_read), Ok(mut b_write)) = (a.try_clone(), b.try_clone()) else { return };
    let up = thread::spawn(move || {
        let _ = io::copy(&mut a_read, &mut b_write);
        let _ = b_write.shutdown(Shutdown::Write);
    });
    let (mut b_read, mut a_write) = (&b, &a);
    let _ = io::copy(&mut b_read, &mut a_write);
    let _ = a.shutdown(Shutdown::Write);
    let deadline = Instant::now() + ms(1000);
    while !up.is_finished() && Instant::now() < deadline {
        thread::sleep(ms(10));
    }
    let _ = a.shutdown(Shutdown::Both);
    let _ = b.shutdown(Shutdown::Both);
}

fn read_http_head(input: &mut TcpStream) -> Option<String> {
    let mut buf = Vec::new();
    let mut last4: u32 = 0;
    let mut byte = [0u8; 1];
    while buf.len() < MAX_HEAD_BYTES {
        match input.read(&mut byte) {
            Ok(0) => return if buf.is_empty() { None } else { Some(latin1_decode(&buf)) },
            Ok(_) => {
                buf.push(byte[0]);
                last4 = (last4 << 8) | byte[0] as u32;
                if last4 == 0x0D0A_0D0A {
                    return Some(latin1_decode(&buf));
                }
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    }
    None
}

fn write_error(out: &mut TcpStream, status: &str) {
    let _ = out.write_all(format!("HTTP/1.1 {status}\r\nConnection: close\r\n\r\n").as_bytes());
    let _ = out.flush();
}

fn trim_brackets(s: &str) -> String {
    s.trim_matches(|c| c == '[' || c == ']').to_string()
}

/// "host:port", "[v6]:port", bare host or bare [v6] (then the default port).
pub fn split_host_port(s: &str, default: i32) -> (String, i32) {
    let close = s.rfind(']').map(|x| x as isize).unwrap_or(-1);
    match s.rfind(':') {
        Some(i) if i > 0 && (i as isize) >= close => {
            let port = s[i + 1..].parse::<i32>().unwrap_or(default);
            (trim_brackets(&s[..i]), port)
        }
        _ => (trim_brackets(s), default),
    }
}

pub fn host_port_from_absolute_uri(uri: &str) -> Option<(String, i32)> {
    let scheme = uri.find("://")?;
    let rest = &uri[scheme + 3..];
    let authority = rest.split('/').next().unwrap_or("");
    if authority.is_empty() {
        return None;
    }
    Some(split_host_port(authority, 80))
}

pub fn host_from_headers(head: &str) -> Option<(String, i32)> {
    let line = head.lines().find(|l| l.len() >= 5 && l.is_char_boundary(5) && l[..5].eq_ignore_ascii_case("host:"))?;
    let value = line[5..].trim();
    Some(split_host_port(value, 80))
}

pub fn origin_form(uri: &str) -> String {
    let Some(scheme) = uri.find("://") else { return uri.to_string() }; // already origin-form
    let rest = &uri[scheme + 3..];
    match rest.find('/') {
        Some(slash) => rest[slash..].to_string(),
        None => "/".to_string(),
    }
}
