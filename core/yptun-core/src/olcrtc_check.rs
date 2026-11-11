//! olcRTC connection check and ping (port of OlcRtcConnectionChecker).
//!
//! Two paths, as in Kotlin:
//! * native: the core's Check / Ping (latency in ms, < 0 = failure) on a freshly allocated SOCKS port;
//! * process fallback: start the olcrtc binary (first candidate, then fallbacks), wait until it
//!   prints "SOCKS5 server listening" or its SOCKS port accepts, then measure: check = time to
//!   ready, ping = one HTTP GET through the SOCKS (200..399 is success).
//! Attempts: check 2, ping 1. A check result of 0 ms counts as failure, a ping of 0 ms does not.
//!
//! The caller normalises the location and returns None when it is incomplete, as Kotlin does before
//! anything else. Building the command line / YAML config and the HTTP-over-SOCKS client stay with
//! the platform ([RtcLauncher], [SocksHttp]); [spawn_watched] covers the process plumbing.

use std::io::{BufRead, BufReader, Read};
use std::net::{Ipv4Addr, TcpListener, TcpStream, ToSocketAddrs};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

pub const CONNECTION_CHECK_ATTEMPTS: u32 = 2;
pub const HTTP_PING_ATTEMPTS: u32 = 1;
pub const OLC_READY_TIMEOUT_MS: u64 = 8_000;
pub const READY_POLL_INTERVAL_MS: u64 = 100;
pub const TCP_CONNECT_TIMEOUT_MS: u64 = 250;
pub const HTTP_PING_TIMEOUT_MS: u64 = 8_000;
pub const HTTP_CONNECT_TIMEOUT_MS: u64 = 8_000;
pub const HTTP_READ_TIMEOUT_MS: u64 = 8_000;
pub const HTTP_PING_URL: &str = "https://www.google.com/generate_204";
pub const PROCESS_STOP_TIMEOUT_MS: u64 = 3_000;
pub const PROCESS_KILL_TIMEOUT_MS: u64 = 1_000;
pub const DEFAULT_DEVICE_ID: &str = "olcbox";
pub const LOCAL_SOCKS_HOST: &str = "127.0.0.1";
pub const READY_LINE: &str = "SOCKS5 server listening";

/// What the native Check / Ping take from a normalised, complete location.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RtcParams {
    /// LocationConfig.bypassProvider
    pub carrier: String,
    pub transport: String,
    /// LocationConfig.id
    pub room_id: String,
    pub device_id: String,
    /// LocationConfig.key
    pub key_hex: String,
    pub vp8_fps: i64,
    pub vp8_batch: i64,
}

/// The core's olcRTC Check / Ping.
pub trait RtcNative {
    fn check(&mut self, p: &RtcParams, socks_port: u16, ready_timeout_ms: u64) -> Result<i64, String>;
    fn ping(&mut self, p: &RtcParams, socks_port: u16, timeout_ms: u64, url: &str) -> Result<i64, String>;
}

/// A started olcrtc process.
pub trait RtcProcess {
    /// The process printed READY_LINE.
    fn ready_seen(&self) -> bool;
    fn is_alive(&mut self) -> bool;
    fn stop(&mut self);
}

/// One olcrtc binary candidate: builds the config and the command and starts it on socks_port.
pub trait RtcLauncher {
    fn name(&self) -> String;
    fn start(&mut self, socks_port: u16) -> Result<Box<dyn RtcProcess>, String>;
}

/// GET url through a SOCKS5 proxy without following redirects; returns the HTTP status.
pub trait SocksHttp {
    fn get_status(&mut self, socks_host: &str, socks_port: u16, url: &str, connect_timeout_ms: u64, read_timeout_ms: u64) -> Result<u16, String>;
}

fn ms(v: u64) -> Duration {
    Duration::from_millis(v)
}

pub fn is_ready_line(line: &str) -> bool {
    line.to_lowercase().contains(&READY_LINE.to_lowercase())
}

/// A free local port (bind :0, read it, release it).
pub fn allocate_local_port() -> Result<u16, String> {
    TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0))
        .and_then(|l| l.local_addr())
        .map(|a| a.port())
        .map_err(|e| e.to_string())
}

pub fn can_connect_to_socks(port: u16) -> bool {
    let Ok(mut addrs) = (LOCAL_SOCKS_HOST, port).to_socket_addrs() else { return false };
    match addrs.next() {
        Some(a) => TcpStream::connect_timeout(&a, ms(TCP_CONNECT_TIMEOUT_MS)).is_ok(),
        None => false,
    }
}

pub fn native_check(native: &mut dyn RtcNative, p: &RtcParams) -> Option<i64> {
    for _ in 0..CONNECTION_CHECK_ATTEMPTS {
        let port = allocate_local_port().ok()?;
        if let Ok(latency) = native.check(p, port, OLC_READY_TIMEOUT_MS) {
            if latency > 0 {
                return Some(latency);
            }
        }
    }
    None
}

pub fn native_ping(native: &mut dyn RtcNative, p: &RtcParams, log: &dyn Fn(&str)) -> Option<i64> {
    for _ in 0..HTTP_PING_ATTEMPTS {
        let port = allocate_local_port().ok()?;
        match native.ping(p, port, HTTP_PING_TIMEOUT_MS, HTTP_PING_URL) {
            Ok(latency) if latency >= 0 => return Some(latency),
            Ok(_) => {}
            Err(e) => log(&format!("OlcRtcConnectionChecker: Native ping failed: {e}")),
        }
    }
    None
}

/// Tries each binary in order; the last one's error is returned.
pub fn start_with_fallback(launchers: &mut [Box<dyn RtcLauncher>], socks_port: u16, log: &dyn Fn(&str)) -> Result<Box<dyn RtcProcess>, String> {
    let n = launchers.len();
    let mut last: Option<String> = None;
    for (i, l) in launchers.iter_mut().enumerate() {
        match l.start(socks_port) {
            Ok(p) => return Ok(p),
            Err(e) => {
                if i + 1 == n {
                    last = Some(e);
                    break;
                }
                log(&format!("OlcRtcConnectionChecker: olcRTC start failed for {}: {}. Retrying with fallback binary.", l.name(), e));
                last = Some(e);
            }
        }
    }
    Err(last.unwrap_or_else(|| "olcRTC binary failed to start".to_string()))
}

pub fn wait_for_ready(process: &mut dyn RtcProcess, socks_port: u16, timeout_ms: u64) -> Result<(), String> {
    let deadline = Instant::now() + ms(timeout_ms);
    while Instant::now() < deadline {
        if process.ready_seen() || can_connect_to_socks(socks_port) {
            return Ok(());
        }
        if !process.is_alive() {
            return Err("olcRTC exited before SOCKS5 was ready".to_string());
        }
        thread::sleep(ms(READY_POLL_INTERVAL_MS));
    }
    Err("olcRTC start timed out".to_string())
}

pub fn http_ping_through_socks(http: &mut dyn SocksHttp, socks_port: u16) -> Result<i64, String> {
    let started = Instant::now();
    let code = http.get_status(LOCAL_SOCKS_HOST, socks_port, HTTP_PING_URL, HTTP_CONNECT_TIMEOUT_MS, HTTP_READ_TIMEOUT_MS)?;
    if !(200..=399).contains(&code) {
        return Err(format!("Unexpected HTTP status: {code}"));
    }
    Ok(started.elapsed().as_millis() as i64)
}

/// One process-based check: time from start to SOCKS ready. The process is always stopped.
pub fn check_once(launchers: &mut [Box<dyn RtcLauncher>], ready_timeout_ms: u64, log: &dyn Fn(&str)) -> Result<i64, String> {
    let port = allocate_local_port()?;
    let mut process = start_with_fallback(launchers, port, log)?;
    let started = Instant::now();
    let result = wait_for_ready(process.as_mut(), port, ready_timeout_ms).map(|_| started.elapsed().as_millis() as i64);
    process.stop();
    result
}

/// One process-based ping: wait for SOCKS, then one HTTP GET through it. The process is always stopped.
pub fn ping_once(launchers: &mut [Box<dyn RtcLauncher>], http: &mut dyn SocksHttp, ready_timeout_ms: u64, log: &dyn Fn(&str)) -> Result<i64, String> {
    let port = allocate_local_port()?;
    let mut process = start_with_fallback(launchers, port, log)?;
    let result = wait_for_ready(process.as_mut(), port, ready_timeout_ms).and_then(|_| http_ping_through_socks(http, port));
    process.stop();
    result
}

pub fn process_check(launchers: &mut [Box<dyn RtcLauncher>], log: &dyn Fn(&str)) -> Option<i64> {
    for _ in 0..CONNECTION_CHECK_ATTEMPTS {
        if let Ok(latency) = check_once(launchers, OLC_READY_TIMEOUT_MS, log) {
            if latency > 0 {
                return Some(latency);
            }
        }
    }
    None
}

pub fn process_ping(launchers: &mut [Box<dyn RtcLauncher>], http: &mut dyn SocksHttp, log: &dyn Fn(&str)) -> Option<i64> {
    for _ in 0..HTTP_PING_ATTEMPTS {
        match ping_once(launchers, http, OLC_READY_TIMEOUT_MS, log) {
            Ok(latency) if latency >= 0 => return Some(latency),
            Ok(_) => {}
            Err(e) => log(&format!("OlcRtcConnectionChecker: HTTP ping failed: {e}")),
        }
    }
    None
}

/// Native when the core is loaded, process fallback otherwise.
pub fn check(p: &RtcParams, native: Option<&mut dyn RtcNative>, launchers: &mut [Box<dyn RtcLauncher>], log: &dyn Fn(&str)) -> Option<i64> {
    match native {
        Some(n) => native_check(n, p),
        None => process_check(launchers, log),
    }
}

pub fn ping(
    p: &RtcParams,
    native: Option<&mut dyn RtcNative>,
    launchers: &mut [Box<dyn RtcLauncher>],
    http: &mut dyn SocksHttp,
    log: &dyn Fn(&str),
) -> Option<i64> {
    match native {
        Some(n) => native_ping(n, p, log),
        None => process_ping(launchers, http, log),
    }
}

/// A child process whose stdout and stderr are watched for READY_LINE.
pub struct ChildRtcProcess {
    child: Child,
    ready: Arc<AtomicBool>,
}

impl ChildRtcProcess {
    pub fn id(&self) -> u32 {
        self.child.id()
    }
}

fn watch<R: Read + Send + 'static>(r: R, ready: Arc<AtomicBool>) {
    thread::spawn(move || {
        let mut reader = BufReader::new(r);
        let mut buf = Vec::new();
        loop {
            buf.clear();
            match reader.read_until(b'\n', &mut buf) {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    if is_ready_line(&String::from_utf8_lossy(&buf)) {
                        ready.store(true, Ordering::SeqCst);
                    }
                }
            }
        }
    });
}

/// Spawns cmd with NO_PROXY=127.0.0.1,localhost (both spellings) and watches its output.
pub fn spawn_watched(mut cmd: Command) -> Result<ChildRtcProcess, String> {
    cmd.env("NO_PROXY", "127.0.0.1,localhost")
        .env("no_proxy", "127.0.0.1,localhost")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    let ready = Arc::new(AtomicBool::new(false));
    if let Some(out) = child.stdout.take() {
        watch(out, ready.clone());
    }
    if let Some(err) = child.stderr.take() {
        watch(err, ready.clone());
    }
    Ok(ChildRtcProcess { child, ready })
}

impl RtcProcess for ChildRtcProcess {
    fn ready_seen(&self) -> bool {
        self.ready.load(Ordering::SeqCst)
    }

    fn is_alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }

    /// std only has a hard kill (Kotlin: destroy, then destroyForcibly after 3 s; child processes of
    /// the binary are the platform's job). Waits up to PROCESS_STOP_TIMEOUT_MS for the exit.
    fn stop(&mut self) {
        if !self.is_alive() {
            return;
        }
        let _ = self.child.kill();
        let deadline = Instant::now() + ms(PROCESS_STOP_TIMEOUT_MS);
        while Instant::now() < deadline {
            if let Ok(Some(_)) = self.child.try_wait() {
                return;
            }
            thread::sleep(ms(50));
        }
    }
}
