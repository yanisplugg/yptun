//! Multi-room olcRTC pool (port of the desktop DesktopRoomPool, round-robin only).
//!
//! Raises up to N INDEPENDENT olcRTC rooms through the core's handle-based room API and fronts them
//! with a tiny TCP balancer on listen_host:listen_port. Each NEW connection goes to the next LIVE
//! room's SOCKS, so parallel flows spread over the rooms and the bandwidth adds up; one flow is never
//! split (no reordering). The balancer is a transparent byte pump: the SOCKS5 handshake (auth
//! included) reaches the room verbatim, so bridge, balancer and rooms share one credential pair.
//!
//! FAST CONNECT: start returns as soon as the FIRST room is up; the others keep connecting (and
//! retrying every RETRY_FAILED_MS) in the background and join the pool as they come up. The balancer
//! prefers rooms the core reports healthy and falls back to all known rooms when none is.
//! No Stage-2 bond yet (needs the server-side reassembler).

use std::collections::{BTreeMap, HashMap};
use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::Duration;

pub const COPY_BUFFER: usize = 64 * 1024;
/// A room whose initial connect failed is retried this often, in the background.
pub const RETRY_FAILED_MS: u64 = 60_000;
pub const DEFAULT_MAX_ROOMS: usize = 5;
pub const DEFAULT_READY_TIMEOUT_MS: i32 = 20_000;
/// Balancer → room connect timeout.
pub const ROOM_CONNECT_TIMEOUT_MS: u64 = 4_000;
const ACCEPT_POLL_MS: u64 = 50;

/// The room half of the native core (YpRtcStartRoom / StopRoom / StopAllRooms / RoomsRunning /
/// RoomHealthy / RoomPort). start_room listens on an OS-assigned loopback port; <= 0 means failure.
pub trait RoomCore: Send + Sync {
    #[allow(clippy::too_many_arguments)]
    fn start_room(
        &self,
        carrier: &str,
        transport: &str,
        room: &str,
        client_id: &str,
        key_hex: &str,
        user: &str,
        pass: &str,
        ready_timeout_ms: i32,
    ) -> i64;
    fn room_port(&self, handle: i64) -> i32;
    fn room_healthy(&self, handle: i64) -> bool;
    fn stop_room(&self, handle: i64);
    fn stop_all_rooms(&self);
    fn rooms_running(&self) -> i32;
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RoomSpec {
    pub carrier: String,
    pub transport: String,
    pub room: String,
    pub client_id: String,
    pub key_hex: String,
}

type Log = Arc<dyn Fn(&str) + Send + Sync>;

#[derive(Default)]
struct PoolState {
    handles: Vec<i64>,
    /// loopback port → room handle (sorted: the published backend list)
    slot_handles: BTreeMap<i32, i64>,
}

struct Inner {
    core: Arc<dyn RoomCore>,
    log: Log,
    retry_ms: AtomicU64,
    stopped: AtomicBool,
    pool: Mutex<PoolState>,
    rr: AtomicUsize,
    live: Mutex<HashMap<u64, TcpStream>>,
    next_id: AtomicU64,
    balancer: Mutex<Option<JoinHandle<()>>>,
    local_addr: Mutex<Option<SocketAddr>>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// Single-use: once stopped, create a new pool.
pub struct RoomPool {
    inner: Arc<Inner>,
}

impl RoomPool {
    pub fn new(core: Arc<dyn RoomCore>, log: impl Fn(&str) + Send + Sync + 'static) -> Self {
        RoomPool {
            inner: Arc::new(Inner {
                core,
                log: Arc::new(log),
                retry_ms: AtomicU64::new(RETRY_FAILED_MS),
                stopped: AtomicBool::new(false),
                pool: Mutex::new(PoolState::default()),
                rr: AtomicUsize::new(0),
                live: Mutex::new(HashMap::new()),
                next_id: AtomicU64::new(0),
                balancer: Mutex::new(None),
                local_addr: Mutex::new(None),
            }),
        }
    }

    /// Overrides RETRY_FAILED_MS (tests).
    pub fn with_retry_ms(self, ms: u64) -> Self {
        self.inner.retry_ms.store(ms, Ordering::SeqCst);
        self
    }

    /// Rooms currently registered in the core.
    pub fn rooms_up(&self) -> i32 {
        self.inner.core.rooms_running()
    }

    /// Loopback SOCKS ports of the rooms in the pool, sorted.
    pub fn backend_ports(&self) -> Vec<i32> {
        lock(&self.inner.pool).slot_handles.keys().copied().collect()
    }

    /// Where the balancer listens (useful with listen_port 0).
    pub fn local_addr(&self) -> Option<SocketAddr> {
        *lock(&self.inner.local_addr)
    }

    /// True if at least one room came up and the balancer is listening.
    #[allow(clippy::too_many_arguments)]
    pub fn start(
        &self,
        rooms: &[RoomSpec],
        listen_host: &str,
        listen_port: u16,
        user: &str,
        pass: &str,
        max_rooms: usize,
        ready_timeout_ms: i32,
    ) -> bool {
        let specs: Vec<RoomSpec> = rooms.iter().take(max_rooms).cloned().collect();
        if specs.is_empty() {
            (self.inner.log)("multiroom: no room specs to start");
            return false;
        }
        let ready = Arc::new((Mutex::new(false), Condvar::new()));
        for (i, r) in specs.into_iter().enumerate() {
            let inner = self.inner.clone();
            let ready = ready.clone();
            let (user, pass) = (user.to_string(), pass.to_string());
            let _ = thread::Builder::new()
                .name(format!("olcrtc-room-{}", i + 1))
                .spawn(move || supervise_room(&inner, i, &r, &user, &pass, ready_timeout_ms, &ready));
        }
        // Every room's first attempt is bounded by ready_timeout_ms, so a timeout here means none came up.
        let wait = Duration::from_millis(ready_timeout_ms.max(0) as u64 + 2_000);
        let up = {
            let (m, c) = &*ready;
            let g = lock(m);
            let (g, _) = c.wait_timeout_while(g, wait, |up| !*up).unwrap_or_else(|e| e.into_inner());
            *g
        };
        if !up {
            (self.inner.log)(&format!("multiroom: no rooms came up within {ready_timeout_ms}ms — aborting"));
            self.stop();
            return false;
        }
        (self.inner.log)(&format!(
            "multiroom: first room up ({} so far) — balancer up; others connect/retry in background",
            self.backend_ports().len()
        ));
        self.start_balancer(listen_host, listen_port)
    }

    fn start_balancer(&self, host: &str, port: u16) -> bool {
        let fail = |e: String| {
            (self.inner.log)(&format!("multiroom: balancer failed to bind {host}:{port}: {e}"));
            false
        };
        let listener = match TcpListener::bind((host, port)) {
            Ok(l) => l,
            Err(e) => return fail(e.to_string()),
        };
        if let Err(e) = listener.set_nonblocking(true) {
            return fail(e.to_string());
        }
        *lock(&self.inner.local_addr) = listener.local_addr().ok();
        let inner = self.inner.clone();
        let label = format!("{host}:{port}");
        let spawned = thread::Builder::new().name("olcrtc-balancer".into()).spawn(move || {
            let n = lock(&inner.pool).slot_handles.len();
            (inner.log)(&format!("multiroom: round-robin balancer on {label} over {n} room(s)"));
            while !inner.stopped.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((s, _)) => {
                        let _ = s.set_nonblocking(false);
                        forward(&inner, s);
                    }
                    Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => {
                        thread::sleep(Duration::from_millis(ACCEPT_POLL_MS))
                    }
                    Err(_) => break,
                }
            }
        });
        match spawned {
            Ok(h) => {
                *lock(&self.inner.balancer) = Some(h);
                true
            }
            Err(e) => fail(e.to_string()),
        }
    }

    /// Stops the balancer, every in-flight connection and every room. Idempotent; releases the port.
    pub fn stop(&self) {
        let inner = &self.inner;
        inner.stopped.store(true, Ordering::SeqCst);
        let balancer = lock(&inner.balancer).take();
        if let Some(h) = balancer {
            let _ = h.join();
        }
        *lock(&inner.local_addr) = None;
        // Blocking copy loops only unblock when their socket shuts down.
        let live: Vec<TcpStream> = lock(&inner.live).drain().map(|(_, s)| s).collect();
        for s in live {
            let _ = s.shutdown(Shutdown::Both);
        }
        let had_rooms = {
            let mut p = lock(&inner.pool);
            p.slot_handles.clear();
            let had = !p.handles.is_empty();
            p.handles.clear();
            had
        };
        if had_rooms {
            inner.core.stop_all_rooms();
        }
    }
}

fn sleep_unless_stopped(inner: &Inner, ms: u64) -> bool {
    let mut left = ms;
    while left > 0 {
        if inner.stopped.load(Ordering::SeqCst) {
            return false;
        }
        let slice = left.min(500);
        thread::sleep(Duration::from_millis(slice));
        left -= slice;
    }
    !inner.stopped.load(Ordering::SeqCst)
}

fn supervise_room(
    inner: &Arc<Inner>,
    index: usize,
    r: &RoomSpec,
    user: &str,
    pass: &str,
    ready_timeout_ms: i32,
    ready: &Arc<(Mutex<bool>, Condvar)>,
) {
    let retry = inner.retry_ms.load(Ordering::SeqCst);
    while !inner.stopped.load(Ordering::SeqCst) {
        let handle = inner.core.start_room(&r.carrier, &r.transport, &r.room, &r.client_id, &r.key_hex, user, pass, ready_timeout_ms);
        if handle <= 0 {
            if inner.stopped.load(Ordering::SeqCst) {
                return;
            }
            (inner.log)(&format!(
                "multiroom: room {} ({}/{}) connect failed, retry in {}s",
                index + 1,
                r.carrier,
                r.room,
                retry / 1000
            ));
            if !sleep_unless_stopped(inner, retry) {
                return;
            }
            continue;
        }
        // Stopped while connecting: stop() may already have drained the core's registry, so a late
        // arrival must stop ITSELF or it leaks.
        if inner.stopped.load(Ordering::SeqCst) {
            inner.core.stop_room(handle);
            return;
        }
        let port = inner.core.room_port(handle);
        if port <= 0 {
            (inner.log)(&format!(
                "multiroom: room {} ({}/{}) came up but reported no port — retry in {}s",
                index + 1,
                r.carrier,
                r.room,
                retry / 1000
            ));
            inner.core.stop_room(handle);
            if !sleep_unless_stopped(inner, retry) {
                return;
            }
            continue;
        }
        {
            let mut p = lock(&inner.pool);
            p.handles.push(handle);
            p.slot_handles.insert(port, handle);
        }
        (inner.log)(&format!("multiroom: room {} ({}/{}) up on 127.0.0.1:{} (self-healing)", index + 1, r.carrier, r.room, port));
        let (m, c) = &**ready;
        *lock(m) = true;
        c.notify_all();
        return;
    }
}

fn register(inner: &Inner, s: &TcpStream) -> Option<u64> {
    let clone = s.try_clone().ok()?;
    let id = inner.next_id.fetch_add(1, Ordering::SeqCst);
    lock(&inner.live).insert(id, clone);
    Some(id)
}

/// Pipes one accepted connection to a LIVE room's SOCKS, both ways.
fn forward(inner: &Arc<Inner>, downstream: TcpStream) {
    let down_id = register(inner, &downstream);
    let inner = inner.clone();
    let _ = thread::Builder::new().name("olcrtc-room-io".into()).spawn(move || {
        let mut up_id = None;
        match connect_to_live_room(&inner) {
            Some(up) => {
                up_id = register(&inner, &up);
                pipe(&downstream, &up);
            }
            None => (inner.log)("multiroom: no live room to forward to"),
        }
        let mut live = lock(&inner.live);
        for id in [up_id, down_id].into_iter().flatten() {
            live.remove(&id);
        }
    });
}

fn pipe(down: &TcpStream, up: &TcpStream) {
    let (Ok(up_r), Ok(down_w)) = (up.try_clone(), down.try_clone()) else { return };
    let back = thread::spawn(move || {
        pump(&up_r, &down_w);
        let _ = down_w.shutdown(Shutdown::Write);
    });
    pump(down, up);
    let _ = up.shutdown(Shutdown::Write);
    let _ = back.join();
}

fn pump(mut from: &TcpStream, mut to: &TcpStream) {
    let mut buf = vec![0u8; COPY_BUFFER];
    loop {
        match from.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if to.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
}

/// Round-robin over the rooms the core reports healthy (so a connection isn't pinned onto a room
/// that is mid-reconnect), falling back to all known rooms when none is — never fully offline.
fn connect_to_live_room(inner: &Inner) -> Option<TcpStream> {
    let slots = lock(&inner.pool).slot_handles.clone();
    let all: Vec<i32> = slots.keys().copied().collect();
    if all.is_empty() {
        return None;
    }
    let healthy: Vec<i32> = all
        .iter()
        .copied()
        .filter(|p| slots.get(p).map_or(false, |&h| inner.core.room_healthy(h)))
        .collect();
    let candidates = if healthy.is_empty() { all } else { healthy };
    for _ in 0..candidates.len() {
        let idx = inner.rr.fetch_add(1, Ordering::SeqCst) % candidates.len();
        let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, candidates[idx] as u16));
        if let Ok(s) = TcpStream::connect_timeout(&addr, Duration::from_millis(ROOM_CONNECT_TIMEOUT_MS)) {
            return Some(s);
        }
    }
    None
}
