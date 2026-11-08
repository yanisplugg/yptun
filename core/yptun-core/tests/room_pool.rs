use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicI64, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use yptun_core::room_pool::{RoomCore, RoomPool, RoomSpec};

#[derive(Default)]
struct FakeCore {
    next: AtomicI64,
    rooms: Mutex<HashMap<i64, (String, u16)>>,
    attempts: Mutex<HashMap<String, u32>>,
    unhealthy: Mutex<HashSet<String>>,
    stopped_rooms: Mutex<Vec<i64>>,
    stop_all: AtomicU32,
    starts: Mutex<Vec<String>>,
}

fn echo(l: TcpListener, name: String) {
    thread::spawn(move || {
        for mut s in l.incoming().flatten() {
            let name = name.clone();
            thread::spawn(move || {
                let mut data = Vec::new();
                let _ = s.read_to_end(&mut data);
                let mut reply = format!("{name}:").into_bytes();
                reply.extend(data);
                let _ = s.write_all(&reply);
            });
        }
    });
}

impl RoomCore for FakeCore {
    fn start_room(&self, _c: &str, _t: &str, room: &str, _id: &str, _k: &str, user: &str, pass: &str, _ms: i32) -> i64 {
        assert_eq!((user, pass), ("u", "p"));
        self.starts.lock().unwrap().push(room.to_string());
        let n = {
            let mut a = self.attempts.lock().unwrap();
            let e = a.entry(room.to_string()).or_insert(0);
            *e += 1;
            *e
        };
        if room == "fail" || (room == "flaky" && n == 1) {
            return 0;
        }
        let h = self.next.fetch_add(1, Ordering::SeqCst) + 1;
        if room == "noport" {
            self.rooms.lock().unwrap().insert(h, (room.into(), 0));
            return h;
        }
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        echo(l, room.to_string());
        self.rooms.lock().unwrap().insert(h, (room.into(), port));
        h
    }
    fn room_port(&self, h: i64) -> i32 {
        self.rooms.lock().unwrap().get(&h).map(|r| r.1 as i32).unwrap_or(0)
    }
    fn room_healthy(&self, h: i64) -> bool {
        let rooms = self.rooms.lock().unwrap();
        rooms.get(&h).map_or(false, |r| !self.unhealthy.lock().unwrap().contains(&r.0))
    }
    fn stop_room(&self, h: i64) {
        self.stopped_rooms.lock().unwrap().push(h);
    }
    fn stop_all_rooms(&self) {
        self.stop_all.fetch_add(1, Ordering::SeqCst);
    }
    fn rooms_running(&self) -> i32 {
        self.rooms.lock().unwrap().len() as i32
    }
}

fn spec(room: &str) -> RoomSpec {
    RoomSpec { carrier: "telemost".into(), transport: "datachannel".into(), room: room.into(), client_id: "c".into(), key_hex: "00".into() }
}

fn pool(core: &Arc<FakeCore>) -> (RoomPool, Arc<Mutex<Vec<String>>>) {
    let logs = Arc::new(Mutex::new(Vec::new()));
    let l = logs.clone();
    let c: Arc<dyn RoomCore> = core.clone();
    (RoomPool::new(c, move |s: &str| l.lock().unwrap().push(s.to_string())), logs)
}

fn has(logs: &Arc<Mutex<Vec<String>>>, needle: &str) -> bool {
    logs.lock().unwrap().iter().any(|l| l.contains(needle))
}

fn wait_until(mut f: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + Duration::from_secs(5);
    while Instant::now() < end {
        if f() {
            return true;
        }
        thread::sleep(Duration::from_millis(20));
    }
    false
}

fn request(addr: SocketAddr) -> String {
    let mut s = TcpStream::connect(addr).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    s.write_all(b"x").unwrap();
    s.shutdown(Shutdown::Write).unwrap();
    let mut out = String::new();
    let _ = s.read_to_string(&mut out);
    out
}

#[test]
fn no_specs() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    assert!(!p.start(&[], "127.0.0.1", 0, "u", "p", 5, 1000));
    assert!(has(&logs, "multiroom: no room specs to start"));
}

#[test]
fn nothing_comes_up() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    assert!(!p.start(&[spec("fail")], "127.0.0.1", 0, "u", "p", 5, 100));
    assert!(has(&logs, "multiroom: room 1 (telemost/fail) connect failed, retry in 60s"));
    assert!(has(&logs, "multiroom: no rooms came up within 100ms — aborting"));
    assert_eq!(core.stop_all.load(Ordering::SeqCst), 0);
    assert!(p.local_addr().is_none());
}

#[test]
fn round_robin_and_health() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    assert!(p.start(&[spec("a"), spec("b")], "127.0.0.1", 0, "u", "p", 5, 2000));
    assert!(wait_until(|| p.backend_ports().len() == 2));
    assert!(has(&logs, "multiroom: room 1 (telemost/a) up on 127.0.0.1:"));
    let addr = p.local_addr().unwrap();
    let r: Vec<String> = (0..4).map(|_| request(addr)).collect();
    assert_ne!(r[0], r[1]);
    assert_eq!(r[0], r[2]);
    assert_eq!(r[1], r[3]);
    let set: HashSet<String> = r.into_iter().collect();
    assert_eq!(set, ["a:x".to_string(), "b:x".to_string()].into_iter().collect());

    core.unhealthy.lock().unwrap().insert("a".into());
    for _ in 0..3 {
        assert_eq!(request(addr), "b:x");
    }
    core.unhealthy.lock().unwrap().insert("b".into());
    let r: HashSet<String> = (0..2).map(|_| request(addr)).collect();
    assert_eq!(r.len(), 2, "falls back to all rooms when none is healthy");
    assert_eq!(p.rooms_up(), 2);
    p.stop();
}

#[test]
fn max_rooms_limits_specs() {
    let core = Arc::new(FakeCore::default());
    let (p, _) = pool(&core);
    assert!(p.start(&[spec("a"), spec("b"), spec("c")], "127.0.0.1", 0, "u", "p", 2, 2000));
    assert!(wait_until(|| p.backend_ports().len() == 2));
    thread::sleep(Duration::from_millis(200));
    let mut s = core.starts.lock().unwrap().clone();
    s.sort();
    assert_eq!(s, vec!["a", "b"]);
    p.stop();
}

#[test]
fn failed_room_retries_in_background() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    let p = p.with_retry_ms(100);
    assert!(p.start(&[spec("flaky")], "127.0.0.1", 0, "u", "p", 5, 2000));
    assert!(has(&logs, "multiroom: room 1 (telemost/flaky) connect failed, retry in 0s"));
    assert!(has(&logs, "multiroom: room 1 (telemost/flaky) up on 127.0.0.1:"));
    assert_eq!(request(p.local_addr().unwrap()), "flaky:x");
    p.stop();
}

#[test]
fn room_without_port_is_stopped_and_retried() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    let p = p.with_retry_ms(100);
    assert!(p.start(&[spec("noport"), spec("a")], "127.0.0.1", 0, "u", "p", 5, 2000));
    assert!(wait_until(|| core.stopped_rooms.lock().unwrap().len() >= 2));
    assert!(has(&logs, "multiroom: room 1 (telemost/noport) came up but reported no port — retry in 0s"));
    assert_eq!(p.backend_ports().len(), 1);
    p.stop();
}

#[test]
fn stop_releases_everything_once() {
    let core = Arc::new(FakeCore::default());
    let (p, logs) = pool(&core);
    assert!(p.start(&[spec("a")], "127.0.0.1", 0, "u", "p", 5, 2000));
    let addr = p.local_addr().unwrap();
    assert_eq!(request(addr), "a:x");
    assert!(has(&logs, "multiroom: round-robin balancer on 127.0.0.1:0 over 1 room(s)"));
    p.stop();
    assert_eq!(core.stop_all.load(Ordering::SeqCst), 1);
    assert!(p.backend_ports().is_empty());
    assert!(p.local_addr().is_none());
    assert!(TcpStream::connect_timeout(&addr, Duration::from_secs(1)).is_err());
    p.stop();
    assert_eq!(core.stop_all.load(Ordering::SeqCst), 1);
}
