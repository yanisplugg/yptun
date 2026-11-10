//! PAC file + tiny PAC HTTP server (port of the desktop PacServer).
//!
//! Every path answers the same PAC: plain host names and loopback go DIRECT, everything else goes to
//! the local SOCKS ("SOCKS5 t; SOCKS t", with percent-encoded user info when the SOCKS has auth).

use std::io::{ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

pub const PAC_HOST: &str = "127.0.0.1";
pub const PAC_PORT: u16 = 10809;
pub const LOCAL_SOCKS_HOST: &str = "127.0.0.1";
pub const LOCAL_SOCKS_PORT: u16 = 10808;
pub const CONTENT_TYPE: &str = "application/x-ns-proxy-autoconfig; charset=utf-8";

const MAX_REQUEST_HEAD: usize = 16 * 1024;

pub fn generate_pac(socks_host: &str, socks_port: u16, socks_username: &str, socks_password: &str) -> String {
    let target = socks_proxy_target(socks_host, socks_port, socks_username, socks_password);
    let mut s = String::new();
    s.push_str("function FindProxyForURL(url, host) {\n");
    s.push_str("  if (isPlainHostName(host)) return \"DIRECT\";\n");
    s.push_str("  if (host == \"localhost\" || host == \"127.0.0.1\" || host == \"::1\") return \"DIRECT\";\n");
    s.push_str("  if (shExpMatch(host, \"127.*\")) return \"DIRECT\";\n");
    s.push_str("  return \"SOCKS5 ");
    s.push_str(&target);
    s.push_str("; SOCKS ");
    s.push_str(&target);
    s.push_str("\";\n");
    s.push('}');
    s
}

fn or_local(host: &str) -> &str {
    if host.trim().is_empty() {
        LOCAL_SOCKS_HOST
    } else {
        host
    }
}

pub fn socks_proxy_target(socks_host: &str, socks_port: u16, socks_username: &str, socks_password: &str) -> String {
    let host_port = format!("{}:{}", or_local(socks_host), socks_port);
    if socks_username.trim().is_empty() {
        return host_port;
    }
    let mut out = percent_encode_user_info(socks_username);
    if !socks_password.is_empty() {
        out.push(':');
        out.push_str(&percent_encode_user_info(socks_password));
    }
    out.push('@');
    out.push_str(&host_port);
    out
}

/// RFC 3986 unreserved characters stay, every other UTF-8 byte becomes %XX.
pub fn percent_encode_user_info(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || b == b'-' || b == b'.' || b == b'_' || b == b'~' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct SocksTarget {
    host: String,
    port: u16,
    username: String,
    password: String,
}

impl SocksTarget {
    fn new(host: &str, port: u16, username: &str, password: &str) -> Self {
        SocksTarget { host: or_local(host).to_string(), port, username: username.into(), password: password.into() }
    }

    fn pac(&self) -> String {
        generate_pac(&self.host, self.port, &self.username, &self.password)
    }
}

pub struct PacServer {
    host: String,
    port: u16,
    target: Arc<Mutex<SocksTarget>>,
    worker: Option<(Arc<AtomicBool>, JoinHandle<()>)>,
    local: Option<SocketAddr>,
}

impl Default for PacServer {
    fn default() -> Self {
        PacServer::new(PAC_HOST, PAC_PORT)
    }
}

impl PacServer {
    pub fn new(host: &str, port: u16) -> Self {
        PacServer {
            host: host.to_string(),
            port,
            target: Arc::new(Mutex::new(SocksTarget::new(LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT, "", ""))),
            worker: None,
            local: None,
        }
    }

    pub fn url(&self) -> String {
        format!("http://{}:{}/proxy.pac", self.host, self.port)
    }

    /// The bound address while running (useful with port 0).
    pub fn local_addr(&self) -> Option<SocketAddr> {
        self.local
    }

    pub fn is_running(&self) -> bool {
        self.worker.is_some()
    }

    /// Updates the target; binds and serves only if not already running.
    pub fn start(&mut self, socks_host: &str, socks_port: u16, socks_username: &str, socks_password: &str) -> Result<(), String> {
        self.update_socks_target(socks_host, socks_port, socks_username, socks_password);
        if self.worker.is_some() {
            return Ok(());
        }
        let listener = TcpListener::bind((self.host.as_str(), self.port)).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let local = listener.local_addr().ok();
        let stop = Arc::new(AtomicBool::new(false));
        let (stop2, target) = (stop.clone(), self.target.clone());
        let handle = thread::Builder::new()
            .name("OlcboxPacServer".into())
            .spawn(move || {
                while !stop2.load(Ordering::SeqCst) {
                    match listener.accept() {
                        Ok((s, _)) => {
                            let body = target.lock().unwrap_or_else(|e| e.into_inner()).pac();
                            thread::spawn(move || respond(s, &body));
                        }
                        Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => {
                            thread::sleep(Duration::from_millis(25))
                        }
                        Err(_) => break,
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        self.local = local;
        self.worker = Some((stop, handle));
        Ok(())
    }

    pub fn update_socks_target(&self, socks_host: &str, socks_port: u16, socks_username: &str, socks_password: &str) {
        *self.target.lock().unwrap_or_else(|e| e.into_inner()) = SocksTarget::new(socks_host, socks_port, socks_username, socks_password);
    }

    pub fn current_pac_content(&self) -> String {
        self.target.lock().unwrap_or_else(|e| e.into_inner()).pac()
    }

    pub fn stop(&mut self) {
        if let Some((stop, handle)) = self.worker.take() {
            stop.store(true, Ordering::SeqCst);
            let _ = handle.join();
        }
        self.local = None;
    }
}

impl Drop for PacServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn respond(mut s: TcpStream, body: &str) {
    let _ = s.set_nonblocking(false);
    let _ = s.set_read_timeout(Some(Duration::from_secs(5)));
    // Drain the request head; any path gets the PAC.
    let mut byte = [0u8; 1];
    let mut last4: u32 = 0;
    let mut n = 0;
    while n < MAX_REQUEST_HEAD {
        match s.read(&mut byte) {
            Ok(1) => {
                n += 1;
                last4 = (last4 << 8) | byte[0] as u32;
                if last4 == 0x0D0A_0D0A {
                    break;
                }
            }
            _ => break,
        }
    }
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        CONTENT_TYPE,
        body.len()
    );
    let _ = s.write_all(head.as_bytes());
    let _ = s.write_all(body.as_bytes());
    let _ = s.flush();
}
