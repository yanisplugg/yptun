use std::cell::RefCell;
use std::net::TcpListener;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use yptun_core::olcrtc_check::{self as oc, RtcLauncher, RtcNative, RtcParams, RtcProcess, SocksHttp};

fn params() -> RtcParams {
    RtcParams {
        carrier: "telemost".into(),
        transport: "datachannel".into(),
        room_id: "room".into(),
        device_id: oc::DEFAULT_DEVICE_ID.into(),
        key_hex: "00".into(),
        vp8_fps: 30,
        vp8_batch: 4,
    }
}

#[derive(Default)]
struct SeqNative {
    checks: Vec<Result<i64, String>>,
    pings: Vec<Result<i64, String>>,
    ports: Vec<u16>,
}

impl RtcNative for SeqNative {
    fn check(&mut self, p: &RtcParams, port: u16, timeout: u64) -> Result<i64, String> {
        assert_eq!(p.device_id, "olcbox");
        assert_eq!(timeout, 8_000);
        self.ports.push(port);
        if self.checks.is_empty() { Err("empty".into()) } else { self.checks.remove(0) }
    }
    fn ping(&mut self, _p: &RtcParams, port: u16, timeout: u64, url: &str) -> Result<i64, String> {
        assert_eq!((timeout, url), (8_000, "https://www.google.com/generate_204"));
        self.ports.push(port);
        if self.pings.is_empty() { Err("empty".into()) } else { self.pings.remove(0) }
    }
}

struct FakeProc {
    created: Instant,
    ready_after: Option<Duration>,
    alive: bool,
    stopped: Arc<AtomicBool>,
}

impl RtcProcess for FakeProc {
    fn ready_seen(&self) -> bool {
        self.ready_after.map_or(false, |d| self.created.elapsed() >= d)
    }
    fn is_alive(&mut self) -> bool {
        self.alive
    }
    fn stop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
    }
}

struct FakeLauncher {
    name: String,
    fail: Option<String>,
    ready_after: Option<Duration>,
    alive: bool,
    stopped: Arc<AtomicBool>,
}

fn launcher(name: &str, fail: Option<&str>, ready_after: Option<u64>) -> FakeLauncher {
    FakeLauncher {
        name: name.into(),
        fail: fail.map(|s| s.to_string()),
        ready_after: ready_after.map(Duration::from_millis),
        alive: true,
        stopped: Arc::new(AtomicBool::new(false)),
    }
}

impl RtcLauncher for FakeLauncher {
    fn name(&self) -> String {
        self.name.clone()
    }
    fn start(&mut self, port: u16) -> Result<Box<dyn RtcProcess>, String> {
        assert!(port > 0);
        if let Some(e) = &self.fail {
            return Err(e.clone());
        }
        Ok(Box::new(FakeProc { created: Instant::now(), ready_after: self.ready_after, alive: self.alive, stopped: self.stopped.clone() }))
    }
}

struct FakeHttp(u16);

impl SocksHttp for FakeHttp {
    fn get_status(&mut self, host: &str, _port: u16, url: &str, c: u64, r: u64) -> Result<u16, String> {
        assert_eq!((host, url, c, r), ("127.0.0.1", "https://www.google.com/generate_204", 8_000, 8_000));
        Ok(self.0)
    }
}

#[test]
fn native_check_attempts() {
    let mut n = SeqNative { checks: vec![Err("x".into()), Ok(42)], ..Default::default() };
    assert_eq!(oc::native_check(&mut n, &params()), Some(42));
    assert_eq!(n.ports.len(), 2);
    assert!(n.ports.iter().all(|p| *p > 0));
    let mut n = SeqNative { checks: vec![Ok(-1), Ok(0), Ok(5)], ..Default::default() };
    assert_eq!(oc::native_check(&mut n, &params()), None);
    assert_eq!(n.ports.len(), 2);
    let mut n = SeqNative { checks: vec![Ok(7), Ok(9)], ..Default::default() };
    assert_eq!(oc::check(&params(), Some(&mut n), &mut [], &|_: &str| {}), Some(7));
    assert_eq!(n.ports.len(), 1);
}

#[test]
fn native_ping_attempts() {
    let logs = RefCell::new(Vec::<String>::new());
    let log = |s: &str| logs.borrow_mut().push(s.to_string());
    let mut n = SeqNative { pings: vec![Ok(0)], ..Default::default() };
    assert_eq!(oc::native_ping(&mut n, &params(), &log), Some(0));
    let mut n = SeqNative { pings: vec![Err("boom".into()), Ok(5)], ..Default::default() };
    assert_eq!(oc::native_ping(&mut n, &params(), &log), None);
    assert_eq!(n.ports.len(), 1);
    assert_eq!(logs.borrow().as_slice(), ["OlcRtcConnectionChecker: Native ping failed: boom"]);
}

#[test]
fn ready_line() {
    assert!(oc::is_ready_line("2026/11/08 INFO socks5 SERVER listening on 127.0.0.1:1080"));
    assert!(!oc::is_ready_line("SOCKS5 server starting"));
}

#[test]
fn binary_fallback() {
    let logs = RefCell::new(Vec::<String>::new());
    let log = |s: &str| logs.borrow_mut().push(s.to_string());
    let mut ls: Vec<Box<dyn RtcLauncher>> = vec![Box::new(launcher("a", Some("nope"), None)), Box::new(launcher("b", None, Some(0)))];
    assert!(oc::start_with_fallback(&mut ls, 1, &log).is_ok());
    assert_eq!(logs.borrow().as_slice(), ["OlcRtcConnectionChecker: olcRTC start failed for a: nope. Retrying with fallback binary."]);
    let mut ls: Vec<Box<dyn RtcLauncher>> = vec![Box::new(launcher("a", Some("nope"), None)), Box::new(launcher("b", Some("nope2"), None))];
    assert_eq!(oc::start_with_fallback(&mut ls, 1, &log).err(), Some("nope2".to_string()));
    let mut none: Vec<Box<dyn RtcLauncher>> = Vec::new();
    assert_eq!(oc::start_with_fallback(&mut none, 1, &log).err(), Some("olcRTC binary failed to start".to_string()));
}

#[test]
fn wait_for_ready_cases() {
    let stopped = Arc::new(AtomicBool::new(false));
    let mk = |ready_after: Option<u64>, alive: bool| FakeProc { created: Instant::now(), ready_after: ready_after.map(Duration::from_millis), alive, stopped: stopped.clone() };
    // Port 0 never accepts a connection; a released "free" port could be taken by a parallel test.
    let free = 0;
    assert_eq!(oc::wait_for_ready(&mut mk(Some(0), true), free, 1000), Ok(()));
    assert_eq!(oc::wait_for_ready(&mut mk(None, false), free, 1000), Err("olcRTC exited before SOCKS5 was ready".to_string()));
    assert_eq!(oc::wait_for_ready(&mut mk(None, true), free, 300), Err("olcRTC start timed out".to_string()));
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    assert_eq!(oc::wait_for_ready(&mut mk(None, true), port, 1000), Ok(()));
}

#[test]
fn check_and_ping_once_always_stop() {
    let log = |_: &str| {};
    let l = launcher("b", None, Some(30));
    let stopped = l.stopped.clone();
    let mut ls: Vec<Box<dyn RtcLauncher>> = vec![Box::new(l)];
    let latency = oc::check_once(&mut ls, 2000, &log).unwrap();
    assert!(latency >= 20, "latency {latency}");
    assert!(stopped.load(Ordering::SeqCst));

    let l = launcher("b", None, Some(0));
    let stopped = l.stopped.clone();
    let mut ls: Vec<Box<dyn RtcLauncher>> = vec![Box::new(l)];
    assert!(oc::ping_once(&mut ls, &mut FakeHttp(204), 2000, &log).is_ok());
    assert!(oc::ping_once(&mut ls, &mut FakeHttp(399), 2000, &log).is_ok());
    assert_eq!(oc::ping_once(&mut ls, &mut FakeHttp(500), 2000, &log), Err("Unexpected HTTP status: 500".to_string()));
    assert_eq!(oc::ping_once(&mut ls, &mut FakeHttp(199), 2000, &log), Err("Unexpected HTTP status: 199".to_string()));
    assert!(stopped.load(Ordering::SeqCst));
}

#[test]
fn process_paths_give_up() {
    let logs = RefCell::new(Vec::<String>::new());
    let log = |s: &str| logs.borrow_mut().push(s.to_string());
    let mut ls: Vec<Box<dyn RtcLauncher>> = vec![Box::new(launcher("a", Some("no binary"), None))];
    assert_eq!(oc::check(&params(), None, &mut ls, &log), None);
    assert_eq!(oc::ping(&params(), None, &mut ls, &mut FakeHttp(204), &log), None);
    assert_eq!(logs.borrow().as_slice(), ["OlcRtcConnectionChecker: HTTP ping failed: no binary"]);
}

fn shell(script: &str) -> Command {
    if cfg!(windows) {
        let mut c = Command::new("cmd");
        c.args(["/C", script]);
        c
    } else {
        let mut c = Command::new("sh");
        c.args(["-c", script]);
        c
    }
}

#[test]
fn real_child_process() {
    let mut p = oc::spawn_watched(shell("echo SOCKS5 server listening")).unwrap();
    let end = Instant::now() + Duration::from_secs(5);
    while !p.ready_seen() && Instant::now() < end {
        thread::sleep(Duration::from_millis(20));
    }
    assert!(p.ready_seen());
    p.stop();

    let long = if cfg!(windows) { "ping -n 30 127.0.0.1" } else { "sleep 30" };
    let mut p = oc::spawn_watched(shell(long)).unwrap();
    assert!(p.is_alive());
    assert!(!p.ready_seen());
    p.stop();
    assert!(!p.is_alive());
}
