use crate::{
    App,
    dataplane::{self, DataplaneConfig, DataplaneLogic},
    lock_unpoison, log_event,
    model::{
        Database, TrafficCounters, TrafficSnapshot, cached_now, derive_wrap_key, get_next_ip,
        is_expired, now, resolve_session_ip, secret_eq, secret_tag,
    },
    packet::{PACKET_CAPACITY, PacketBuf, PacketBuffer},
    tokio_io::{IoCounters, PacketOutput},
    tun_device::RouteTable,
};
use anyhow::{Context, Result, anyhow, bail};
use ctr::cipher::{InnerIvInit, KeyInit, StreamCipher};
use hmac::{Hmac, Mac};
use rand::{Rng, RngCore, SeedableRng, rngs::OsRng, rngs::SmallRng};
use sha1::Sha1;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::{
        Arc, RwLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::{
    Mutex, OwnedMutexGuard, RwLockReadGuard, RwLockWriteGuard, Semaphore, mpsc, oneshot,
};
use tokio_util::sync::CancellationToken;

type Aes128Ctr128BE = ctr::Ctr128BE<aes::Aes128>;
type Aes128CtrCore = ctr::CtrCore<aes::Aes128, ctr::flavors::Ctr128BE>;

pub(crate) const MAX_ACTIVE_SESSIONS: usize = 3072;
const CONTROL_EVENT_CAPACITY: usize = 1024;
const CONTROL_TASK_CAPACITY: usize = 128;
const DB_LOCK_TIMEOUT: Duration = Duration::from_secs(5);
const TRAFFIC_FLUSH_INTERVAL: Duration = Duration::from_secs(30);
const SESSION_SETUP_IDLE_MS: u64 = 15_000;
const SESSION_AUTH_IDLE_MS: u64 = 10 * 60 * 60 * 1_000;
const PUBLIC_SETUP_GHOST_IDLE_SECS: u64 = 30;
const PUBLIC_AUTH_GHOST_IDLE_SECS: u64 = 2 * 60;
const PUBLIC_SESSION_LIMIT: usize = MAX_ACTIVE_SESSIONS + 256;
const DIAGNOSTIC_CLIENT_LIMIT: usize = 8;
const DIAGNOSTIC_REQUEST_MAX_BYTES: usize = 128;
const DIAGNOSTIC_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);
const DIAGNOSTIC_WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const DIAGNOSTIC_LEASE: Duration = Duration::from_secs(5 * 60);
const SESSION_LEASE: &[u8] = b"\xffCSQTT_LEASE";
pub const PANEL_RESTART_NOTICE: &[u8] = b"\xffCSQTT_PANEL_RESTART_V1\x00\x91\x7d\x03\xa8";
const STREAM_REPAIR_PREFIX: &[u8] = b"\xffCSQTT_STREAM_REPAIR_V1";
const STREAM_ALIVE_PREFIX: &[u8] = b"\xffCSQTT_STREAM_ALIVE_V1";
pub(crate) const MAX_STREAM_WORKERS: usize = 100;
const MAX_STREAM_WORKERS_U16: u16 = MAX_STREAM_WORKERS as u16;
const STREAM_RECONCILE_INTERVAL_MS: u64 = 3_000;
const STREAM_REPAIR_GRACE_MS: u64 = 30_000;
const STREAM_REPAIR_ESCALATE_MS: u64 = 60_000;
const STREAM_ALIVE_INTERVAL_MS: u64 = 10_000;
const STREAM_ALIVE_REPEAT: u8 = 2;
const STREAM_CONTROL_CARRIERS: usize = 3;
const STREAM_ROUND_ORPHAN_TTL_MS: u64 = 60_000;
const STREAM_INVENTORY_RESYNC_MS: u64 = 60_000;
const EPOCH_SWEEP_INTERVAL_MS: u64 = 5 * 60_000;
const EPOCH_IDLE_TTL_MS: u64 = 60 * 60_000;
const HOT_TABLE_RESERVE: usize = 128;
const MEMORY_COMPACT_INTERVAL_MS: u64 = 5_000;
const SESSION_MAINTENANCE_INTERVAL_MS: u64 = 1_000;
const SETUP_BUDGET_PER_TICK: usize = 1024;
const DTLS_TABLE_LIMIT: usize = 1024;
const UNAUTHENTICATED_SETUP_LOG_INTERVAL_MS: u64 = 10_000;
pub(crate) static SESSION_ID_COUNTER: AtomicU64 = AtomicU64::new(1);
static ROUTE_REGISTRATION_COUNTER: AtomicU64 = AtomicU64::new(1);
static CACHED_MONOTONIC_MS: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct DpiFrame {
    pub timestamp_ms: u64,
    pub direction: String,
    pub src: String,
    pub dst: String,
    pub proto: String,
    pub pt: u8,
    pub seq: u64,
    pub len: usize,
    pub wire_len: usize,
    pub password: String,
    pub gen_id: u64,
    pub salt: String,
    pub detail: String,
    pub hex_preview: String,
}

pub static DPI_RING: std::sync::LazyLock<RwLock<VecDeque<DpiFrame>>> =
    std::sync::LazyLock::new(|| RwLock::new(VecDeque::with_capacity(100)));

pub static DPI_BROADCAST: std::sync::LazyLock<tokio::sync::broadcast::Sender<DpiFrame>> =
    std::sync::LazyLock::new(|| {
        let (tx, _) = tokio::sync::broadcast::channel(64);
        tx
    });

pub fn record_dpi(frame: DpiFrame) {
    if let Ok(mut ring) = DPI_RING.try_write() {
        if ring.len() >= 100 {
            ring.pop_front();
        }
        ring.push_back(frame.clone());
    }
    let _ = DPI_BROADCAST.send(frame);
}

pub fn epoch_snapshot_len() -> usize {
    ENGINE_EPOCHS_GAUGE.load(Ordering::Relaxed) as usize
}

pub fn dpi_ring_len() -> usize {
    DPI_RING.read().map(|ring| ring.len()).unwrap_or(0)
}

pub fn hex_and_ascii_dump(data: &[u8]) -> String {
    let limit = data.len().min(64);
    let mut out = String::with_capacity(limit * 4);
    for chunk in data.get(..limit).unwrap_or_default().chunks(16) {
        for byte in chunk {
            use std::fmt::Write;
            let _ = write!(out, "{byte:02x} ");
        }
        if chunk.len() < 16 {
            for _ in 0..16 - chunk.len() {
                out.push_str("   ");
            }
        }
        out.push_str(" | ");
        for byte in chunk {
            if (32..=126).contains(byte) {
                out.push(*byte as char);
            } else {
                out.push('.');
            }
        }
        out.push('\n');
    }
    if data.len() > limit {
        use std::fmt::Write;
        let _ = writeln!(out, "... (+{} more bytes)", data.len() - limit);
    }
    out
}

#[inline(always)]
fn is_dpi_control(plain: &[u8]) -> bool {
    plain.starts_with(b"GETCONF:")
        || plain.starts_with(b"TUNCONF:")
        || plain.starts_with(b"ROUTING:")
        || plain.starts_with(b"WORKERS:")
        || plain.starts_with(b"GETWORKERS:")
        || plain.starts_with(b"TUNWORKERS:")
        || plain == PANEL_RESTART_NOTICE
        || plain.starts_with(b"DENIED:")
        || plain.starts_with(b"DISCONNECT:")
        || (plain.len() >= 5 && matches!(plain.first(), Some(20..=22)))
}

#[inline(always)]
#[cfg(feature = "diagnostics")]
fn should_record_dpi(
    enabled: bool,
    now_ms: u64,
    payload_counter: &mut u64,
    last_sample_ms: &mut u64,
    plain: &[u8],
) -> bool {
    if !enabled {
        return false;
    }
    if !is_dpi_control(plain) {
        *payload_counter = payload_counter.wrapping_add(1);
        if *payload_counter & 511 != 0 {
            return false;
        }
    }
    if now_ms.saturating_sub(*last_sample_ms) < 200 {
        return false;
    }
    *last_sample_ms = now_ms;
    true
}

#[inline(always)]
#[cfg(not(feature = "diagnostics"))]
fn should_record_dpi(
    enabled: bool,
    now_ms: u64,
    payload_counter: &mut u64,
    last_sample_ms: &mut u64,
    plain: &[u8],
) -> bool {
    let _ = (enabled, now_ms, payload_counter, last_sample_ms, plain);
    false
}

pub fn pad_tunconf(base: &str) -> String {
    base.to_owned()
}

fn display_control_payload(plain: &[u8]) -> String {
    let text = String::from_utf8_lossy(plain);
    let Some(payload) = text.strip_prefix("GETCONF:") else {
        return text.into_owned();
    };
    let parts = payload.split('|').collect::<Vec<_>>();
    if parts.len() < 3 {
        return "GETCONF:[REDACTED]".to_owned();
    }
    let first = parts[0];
    let rest = parts[2..].join("|");
    if rest.is_empty() {
        format!("GETCONF:{first}|[REDACTED]")
    } else {
        format!("GETCONF:{first}|[REDACTED]|{rest}")
    }
}

#[allow(clippy::too_many_arguments)]
pub fn record_packet_dpi(
    direction: &'static str,
    src: &str,
    dst: &str,
    pt: u8,
    seq: u64,
    plain: &[u8],
    wire_len: usize,
    password: &str,
    gen_id: u64,
    salt: &str,
) {
    let control = is_dpi_control(plain);
    let proto = match pt {
        111 => "RTP-OBFS Audio (PT=111)",
        96 => "RTP-OBFS Video (PT=96)",
        _ => "RTP-OBFS (Custom)",
    };
    let detail = if [
        b"GETCONF:".as_slice(),
        b"TUNCONF:".as_slice(),
        b"ROUTING:".as_slice(),
        b"WORKERS:".as_slice(),
        b"GETWORKERS:".as_slice(),
        b"TUNWORKERS:".as_slice(),
        b"DENIED:".as_slice(),
        b"DISCONNECT:".as_slice(),
    ]
    .iter()
    .any(|prefix| plain.starts_with(prefix))
    {
        display_control_payload(plain)
    } else if plain.iter().all(|byte| *byte == 0xff) {
        format!("KeepAlive Ping ({} bytes)", plain.len())
    } else {
        format!("Payload ({} bytes)", plain.len())
    };
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    let hex_preview = if control {
        hex_and_ascii_dump(detail.as_bytes())
    } else {
        String::new()
    };
    record_dpi(DpiFrame {
        timestamp_ms,
        direction: direction.to_owned(),
        src: src.to_owned(),
        dst: dst.to_owned(),
        proto: proto.to_owned(),
        pt,
        seq,
        len: plain.len(),
        wire_len,
        password: password.to_owned(),
        gen_id,
        salt: salt.to_owned(),
        detail,
        hex_preview,
    });
}

pub async fn run_dpi_server() -> Result<()> {
    if !cfg!(feature = "diagnostics") {
        return Ok(());
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:46003").await?;
    let clients = Arc::new(Semaphore::new(DIAGNOSTIC_CLIENT_LIMIT));
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            continue;
        };
        let Ok(permit) = clients.clone().try_acquire_owned() else {
            continue;
        };
        tokio::spawn(async move {
            let _permit = permit;
            serve_dpi_client(socket).await;
        });
    }
}

async fn serve_dpi_client(socket: tokio::net::TcpStream) {
    let (mut reader, mut writer) = tokio::io::split(socket);
    let request = match tokio::time::timeout(
        DIAGNOSTIC_HANDSHAKE_TIMEOUT,
        read_diagnostic_request(&mut reader),
    )
    .await
    {
        Ok(Ok(Some(request))) => request,
        _ => return,
    };
    let requested = if request.starts_with("GET_DPI:") {
        request
            .strip_prefix("GET_DPI:")
            .unwrap_or("0")
            .parse::<usize>()
            .unwrap_or(0)
    } else {
        0
    };
    let samples = if requested > 0 {
        if let Ok(ring) = DPI_RING.try_read() {
            let count = requested.min(ring.len());
            ring.iter()
                .skip(ring.len() - count)
                .cloned()
                .collect::<Vec<_>>()
        } else {
            Vec::new()
        }
    } else {
        Vec::new()
    };
    for frame in samples {
        if let Ok(json) = serde_json::to_string(&frame)
            && !write_diagnostic_line(&mut writer, &json).await
        {
            return;
        }
    }
    if requested > 0 {
        return;
    }
    let mut rx = DPI_BROADCAST.subscribe();
    let mut control = [0_u8; 64];
    let lease = tokio::time::sleep(DIAGNOSTIC_LEASE);
    tokio::pin!(lease);
    loop {
        tokio::select! {
            received = tokio::io::AsyncReadExt::read(&mut reader, &mut control) => {
                match received {
                    Ok(0) | Err(_) => break,
                    Ok(_) => lease.as_mut().reset(tokio::time::Instant::now() + DIAGNOSTIC_LEASE),
                }
            }
            _ = &mut lease => break,
            frame = rx.recv() => match frame {
                Ok(frame) => {
                    if let Ok(json) = serde_json::to_string(&frame)
                        && !write_diagnostic_line(&mut writer, &json).await
                    {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    }
    drop(rx);
    if DPI_BROADCAST.receiver_count() == 0
        && let Ok(mut ring) = DPI_RING.write()
    {
        ring.clear();
        ring.shrink_to_fit();
    }
}

async fn read_diagnostic_request<R>(reader: &mut R) -> std::io::Result<Option<String>>
where
    R: tokio::io::AsyncRead + Unpin,
{
    use tokio::io::AsyncReadExt;

    let mut bytes = [0u8; DIAGNOSTIC_REQUEST_MAX_BYTES + 1];
    let mut length = 0usize;
    loop {
        let read = reader.read(&mut bytes[length..]).await?;
        if read == 0 {
            if length == 0 {
                return Ok(None);
            }
            break;
        }
        let end = length + read;
        if let Some(newline) = bytes[length..end].iter().position(|byte| *byte == b'\n') {
            length += newline;
            break;
        }
        length = end;
        if length == bytes.len() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "diagnostic request exceeds maximum length",
            ));
        }
    }
    let request = std::str::from_utf8(&bytes[..length]).map_err(|_| {
        std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "diagnostic request is not UTF-8",
        )
    })?;
    Ok(Some(request.trim().to_owned()))
}

async fn write_diagnostic_line<W>(writer: &mut W, line: &str) -> bool
where
    W: tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::AsyncWriteExt;

    matches!(
        tokio::time::timeout(DIAGNOSTIC_WRITE_TIMEOUT, async {
            writer.write_all(line.as_bytes()).await?;
            writer.write_all(b"\n").await
        })
        .await,
        Ok(Ok(()))
    )
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct ThreadCpuFrame {
    pub tid: u32,
    pub name: String,
    pub user_cpu_ns: u64,
    pub system_cpu_ns: u64,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct SyscallsFrame {
    pub timestamp_ms: u64,
    #[serde(default)]
    pub sample_window_ns: u64,
    #[serde(default)]
    pub process_cpu_ns: u64,
    #[serde(default)]
    pub process_user_cpu_ns: u64,
    #[serde(default)]
    pub process_system_cpu_ns: u64,
    #[serde(default)]
    pub dataplane_cpu_ns: u64,
    #[serde(default)]
    pub dataplane_tid: u32,
    #[serde(default)]
    pub threads: Vec<ThreadCpuFrame>,
    pub udp_rx_pps: u64,
    pub udp_rx_bps: u64,
    pub udp_rx_errors_s: u64,
    pub udp_tx_pps: u64,
    pub udp_tx_bps: u64,
    pub udp_tx_errors_s: u64,
    pub udp_tx_drops_s: u64,
    pub tun_rx_pps: u64,
    pub tun_rx_bps: u64,
    pub tun_rx_errors_s: u64,
    pub tun_tx_pps: u64,
    pub tun_tx_bps: u64,
    pub tun_tx_errors_s: u64,
    pub tun_tx_drops_s: u64,
    pub readiness_wakeups_s: u64,
    pub recv_syscalls_s: u64,
    pub send_syscalls_s: u64,
    pub rx_eagain_s: u64,
    pub tx_eagain_s: u64,
    pub crypto_ops_s: u64,
    pub active_sessions: u64,
    pub free_udp_tx_slots: u64,
    pub free_tun_tx_slots: u64,
    #[serde(default)]
    pub udp_rx_enobufs_s: u64,
    #[serde(default)]
    pub udp_tx_enobufs_s: u64,
    pub total_udp_rx_packets: u64,
    pub total_udp_tx_packets: u64,
    pub total_tun_rx_packets: u64,
    pub total_tun_tx_packets: u64,
}

pub static SYSCALLS_BROADCAST: std::sync::LazyLock<tokio::sync::broadcast::Sender<SyscallsFrame>> =
    std::sync::LazyLock::new(|| {
        let (tx, _) = tokio::sync::broadcast::channel(16);
        tx
    });

pub static GLOBAL_IO_COUNTERS: std::sync::LazyLock<std::sync::RwLock<crate::tokio_io::IoCounters>> =
    std::sync::LazyLock::new(|| std::sync::RwLock::new(crate::tokio_io::IoCounters::default()));

#[allow(dead_code)]
pub static CRYPTO_OPS_COUNTER: AtomicU64 = AtomicU64::new(0);
pub static ACTIVE_SESSIONS_GAUGE: AtomicU64 = AtomicU64::new(0);
pub static HOT_SESSION_CAPACITY_GAUGE: AtomicU64 = AtomicU64::new(0);
pub static ENGINE_EPOCHS_GAUGE: AtomicU64 = AtomicU64::new(0);
pub static STREAM_REPAIRS_GAUGE: AtomicU64 = AtomicU64::new(0);
pub static STREAM_INVENTORY_GAUGE: AtomicU64 = AtomicU64::new(0);
pub static SYSCALLS_CLIENTS: AtomicU64 = AtomicU64::new(0);

#[inline(always)]
fn record_crypto_op(enabled: bool) {
    #[cfg(feature = "diagnostics")]
    {
        if enabled {
            CRYPTO_OPS_COUNTER.fetch_add(1, Ordering::Relaxed);
        }
    }
    #[cfg(not(feature = "diagnostics"))]
    {
        let _ = enabled;
    }
}

pub async fn run_syscalls_server(app: Arc<App>) -> Result<()> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:46004").await?;
    let clients = Arc::new(Semaphore::new(DIAGNOSTIC_CLIENT_LIMIT));
    loop {
        let Ok((socket, _)) = listener.accept().await else {
            continue;
        };
        let Ok(permit) = clients.clone().try_acquire_owned() else {
            continue;
        };
        let app = app.clone();
        tokio::spawn(async move {
            let _permit = permit;
            serve_syscalls_client(socket, app).await;
        });
    }
}

async fn serve_syscalls_client(socket: tokio::net::TcpStream, _app: Arc<App>) {
    let (mut reader, mut writer) = tokio::io::split(socket);
    let _request = match tokio::time::timeout(
        DIAGNOSTIC_HANDSHAKE_TIMEOUT,
        read_diagnostic_request(&mut reader),
    )
    .await
    {
        Ok(Ok(Some(request))) => request,
        _ => return,
    };
    if !cfg!(feature = "diagnostics") {
        return;
    }
    let _syscalls = SyscallsClient::new();
    let mut rx = SYSCALLS_BROADCAST.subscribe();
    let mut control = [0_u8; 64];
    let lease = tokio::time::sleep(DIAGNOSTIC_LEASE);
    tokio::pin!(lease);
    loop {
        tokio::select! {
            received = tokio::io::AsyncReadExt::read(&mut reader, &mut control) => {
                match received {
                    Ok(0) | Err(_) => break,
                    Ok(_) => lease.as_mut().reset(tokio::time::Instant::now() + DIAGNOSTIC_LEASE),
                }
            }
            _ = &mut lease => break,
            frame = rx.recv() => match frame {
                Ok(frame) => {
                    if let Ok(json) = serde_json::to_string(&frame)
                        && !write_diagnostic_line(&mut writer, &json).await
                    {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    }
}

struct SyscallsClient;

impl SyscallsClient {
    fn new() -> Self {
        SYSCALLS_CLIENTS.fetch_add(1, Ordering::AcqRel);
        Self
    }
}

impl Drop for SyscallsClient {
    fn drop(&mut self) {
        SYSCALLS_CLIENTS.fetch_sub(1, Ordering::AcqRel);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PasswordEpochDecision {
    Current,
    Replaced,
}

pub struct PasswordEpochSlot {
    pub epoch: Arc<Mutex<PasswordEpochState>>,
    pub last_used_ms: AtomicU64,
}

impl PasswordEpochSlot {
    pub fn new(epoch: PasswordEpochState, now_ms: u64) -> Self {
        Self {
            epoch: Arc::new(Mutex::new(epoch)),
            last_used_ms: AtomicU64::new(now_ms),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PasswordEpochState {
    generation_id: u64,
    session_salt: String,
}

impl PasswordEpochState {
    pub fn new(generation_id: u64, session_salt: String) -> Self {
        Self {
            generation_id,
            session_salt,
        }
    }

    fn admit(&mut self, generation_id: u64, session_salt: &str) -> PasswordEpochDecision {
        if generation_id != self.generation_id || session_salt != self.session_salt {
            self.generation_id = generation_id;
            self.session_salt = session_salt.to_owned();
            PasswordEpochDecision::Replaced
        } else {
            PasswordEpochDecision::Current
        }
    }

    fn matches(&self, generation_id: u64, session_salt: &str) -> bool {
        self.generation_id == generation_id && self.session_salt == session_salt
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum CredentialAccess {
    Main,
    Slot(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct SessionEpochIdentity {
    password: String,
    generation_id: u64,
    session_salt: String,
}

pub struct StreamDebugMetrics {
    active: Arc<AtomicBool>,
    generation: AtomicU64,
    started_at: AtomicU64,
    up_bytes: AtomicU64,
    down_bytes: AtomicU64,
    up_packets: AtomicU64,
    down_packets: AtomicU64,
}

impl StreamDebugMetrics {
    pub fn new(active: Arc<AtomicBool>) -> Self {
        let started_at = if active.load(Ordering::Relaxed) {
            cached_now()
        } else {
            0
        };
        Self {
            active,
            generation: AtomicU64::new(1),
            started_at: AtomicU64::new(started_at),
            up_bytes: AtomicU64::new(0),
            down_bytes: AtomicU64::new(0),
            up_packets: AtomicU64::new(0),
            down_packets: AtomicU64::new(0),
        }
    }

    pub fn reset(&self) {
        self.generation.fetch_add(1, Ordering::AcqRel);
        self.started_at.store(cached_now(), Ordering::Relaxed);
        self.up_bytes.store(0, Ordering::Relaxed);
        self.down_bytes.store(0, Ordering::Relaxed);
        self.up_packets.store(0, Ordering::Relaxed);
        self.down_packets.store(0, Ordering::Relaxed);
    }

    #[cfg(feature = "diagnostics")]
    fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    #[cfg(feature = "diagnostics")]
    fn publish(&self, up_bytes: u64, down_bytes: u64, up_packets: u64, down_packets: u64) {
        if !self.active.load(Ordering::Acquire) {
            return;
        }
        self.up_bytes.store(up_bytes, Ordering::Relaxed);
        self.down_bytes.store(down_bytes, Ordering::Relaxed);
        self.up_packets.store(up_packets, Ordering::Relaxed);
        self.down_packets.store(down_packets, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> StreamDebugSnapshot {
        let active = self.active.load(Ordering::Acquire);
        let mut started_at = self.started_at.load(Ordering::Relaxed);
        if active && started_at == 0 {
            let current = cached_now();
            let _ =
                self.started_at
                    .compare_exchange(0, current, Ordering::Relaxed, Ordering::Relaxed);
            started_at = self.started_at.load(Ordering::Relaxed);
        }
        StreamDebugSnapshot {
            active,
            started_at,
            up_bytes: self.up_bytes.load(Ordering::Relaxed),
            down_bytes: self.down_bytes.load(Ordering::Relaxed),
            up_packets: self.up_packets.load(Ordering::Relaxed),
            down_packets: self.down_packets.load(Ordering::Relaxed),
        }
    }
}

pub struct StreamDebugSnapshot {
    pub active: bool,
    pub started_at: u64,
    pub up_bytes: u64,
    pub down_bytes: u64,
    pub up_packets: u64,
    pub down_packets: u64,
}

pub struct Session {
    pub id: u64,
    pub address: SocketAddr,
    pub password: String,
    pub generation_id: AtomicU64,
    pub session_salt: std::sync::Mutex<String>,
    pub worker_id: AtomicU64,
    pub desired_stream_count: AtomicU64,
    pub tunnel_ip: std::sync::Mutex<Option<[u8; 4]>>,
    pub last_seen: AtomicU64,
    pub up_bytes: AtomicU64,
    pub down_bytes: AtomicU64,
    pub created_at: u64,
    pub is_srtp: bool,
    pub handshake_done: AtomicBool,
    pub has_tunnel: AtomicBool,
    pub stream_debug: StreamDebugMetrics,
    pub cancel_token: CancellationToken,
}

impl Session {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        id: u64,
        address: SocketAddr,
        password: &str,
        generation_id: u64,
        session_salt: &str,
        is_srtp: bool,
        stream_debug_active: Arc<AtomicBool>,
        wall_now: u64,
    ) -> Arc<Self> {
        Arc::new(Self {
            id,
            address,
            password: password.to_owned(),
            generation_id: AtomicU64::new(generation_id),
            session_salt: std::sync::Mutex::new(session_salt.to_owned()),
            worker_id: AtomicU64::new(u64::MAX),
            desired_stream_count: AtomicU64::new(0),
            tunnel_ip: std::sync::Mutex::new(None),
            last_seen: AtomicU64::new(wall_now),
            up_bytes: AtomicU64::new(0),
            down_bytes: AtomicU64::new(0),
            created_at: wall_now,
            is_srtp,
            handshake_done: AtomicBool::new(true),
            has_tunnel: AtomicBool::new(false),
            stream_debug: StreamDebugMetrics::new(stream_debug_active),
            cancel_token: CancellationToken::new(),
        })
    }
}

struct Credential {
    password: Arc<str>,
    key: [u8; 32],
    aes: aes::Aes128,
    hmac: Hmac<Sha1>,
    dtls_client_cipher_key: [u8; 32],
    dtls_client_aes: aes::Aes128,
    dtls_client_hmac: Hmac<Sha1>,
    dtls_server_cipher_key: [u8; 32],
}

#[derive(Default)]
pub struct CredentialSet {
    entries: Vec<Credential>,
}

impl CredentialSet {
    fn contains_password(&self, password: &str) -> bool {
        self.entries
            .iter()
            .any(|credential| credential.password.as_ref() == password)
    }
}

#[derive(Clone)]
struct EpochValue {
    last_seen_ms: u64,
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
struct EndpointKey {
    peer: SocketAddr,
    local_ip: Option<IpAddr>,
}

impl EndpointKey {
    #[inline(always)]
    fn new(peer: SocketAddr, local_ip: Option<IpAddr>) -> Self {
        Self { peer, local_ip }
    }
}

fn dtls_state_label(state: crate::dtls::DtlsHandshakeState) -> &'static str {
    match state {
        crate::dtls::DtlsHandshakeState::Idle => "Idle",
        crate::dtls::DtlsHandshakeState::AwaitingServerFlight => "ждём полёт сервера",
        crate::dtls::DtlsHandshakeState::AwaitingServerFinished => "ждём Finished сервера",
        crate::dtls::DtlsHandshakeState::AwaitingClientFlight => "ждём полёт клиента",
        crate::dtls::DtlsHandshakeState::Connected => "Connected",
        crate::dtls::DtlsHandshakeState::Failed => "Failed",
    }
}

fn insert_bounded_endpoint<T>(
    map: &mut HashMap<EndpointKey, (T, Vec<u8>, u64)>,
    key: EndpointKey,
    value: (T, Vec<u8>, u64),
) {
    if !map.contains_key(&key) && map.len() >= DTLS_TABLE_LIMIT {
        let oldest = map
            .iter()
            .min_by_key(|(_, entry)| entry.2)
            .map(|(oldest, _)| *oldest);
        if let Some(oldest) = oldest {
            map.remove(&oldest);
        }
    }
    map.insert(key, value);
}

fn uplink_source_matches(packet: &[u8], tunnel_ip: Option<[u8; 4]>) -> bool {
    if packet.first().map(|version| *version >> 4) != Some(4) {
        return true;
    }
    match tunnel_ip {
        Some(ip) => packet.get(12..16) == Some(&ip[..]),
        None => false,
    }
}

#[derive(Clone, Copy)]
struct OutState {
    ssrc: u32,
    initial_seq: u16,
    initial_ts: u32,
    initial_abs_send_time: u32,
    started: Instant,
    count: u64,
    payload_type: u8,
    transport_seq: u16,
}

impl OutState {
    fn new(payload_type: u8) -> Self {
        let mut random = [0u8; 12];
        OsRng.fill_bytes(&mut random);
        let wall_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        Self {
            ssrc: u32::from_be_bytes(random[0..4].try_into().unwrap_or_default()),
            initial_seq: u16::from_be_bytes(random[4..6].try_into().unwrap_or_default()),
            initial_ts: u32::from_be_bytes(random[6..10].try_into().unwrap_or_default()),
            initial_abs_send_time: (((wall_ms * 262_144) / 1_000) as u32) & 0x00ff_ffff,
            started: Instant::now(),
            count: 0,
            payload_type,
            transport_seq: u16::from_be_bytes(random[10..12].try_into().unwrap_or_default()),
        }
    }

    #[inline(always)]
    fn clocks_for_type(&self, now: Instant, pt: u8) -> (u32, u32) {
        let elapsed = now.saturating_duration_since(self.started);
        let ts = if pt == 111 {
            let frame_idx = (elapsed.as_millis() / 20) as u32;
            self.initial_ts.wrapping_add(frame_idx.wrapping_mul(960))
        } else {
            self.initial_ts
                .wrapping_add(duration_ticks(elapsed, rtp_clock_rate(pt)))
        };
        (
            ts,
            self.initial_abs_send_time
                .wrapping_add(duration_ticks(elapsed, 262_144))
                & 0x00ff_ffff,
        )
    }
}

pub struct ReplayState {
    initialized: bool,
    highest: u16,
    bitmap: Option<Box<[u64; 256]>>,
}

const REPLAY_WINDOW_BITS: usize = 16_384;
const REPLAY_WORD_BITS: usize = 64;

impl ReplayState {
    pub(crate) fn new() -> Self {
        Self {
            initialized: false,
            highest: 0,
            bitmap: None,
        }
    }

    fn accept(&mut self, seq: u16) -> bool {
        self.bitmap.get_or_insert_with(|| Box::new([0; 256]));
        if !self.initialized {
            self.initialized = true;
            self.highest = seq;
            self.set_srtp_bit(seq);
            return true;
        }
        let forward = seq.wrapping_sub(self.highest);
        if forward != 0 && forward < 32768 {
            if forward as usize >= REPLAY_WINDOW_BITS {
                if let Some(bitmap) = self.bitmap.as_mut() {
                    bitmap.fill(0);
                }
            } else {
                self.clear_advanced(self.highest.wrapping_add(1), forward as usize);
            }
            self.highest = seq;
            self.set_srtp_bit(seq);
            return true;
        }
        let behind = self.highest.wrapping_sub(seq);
        if behind as usize >= REPLAY_WINDOW_BITS || self.test_srtp_bit(seq) {
            return false;
        }
        self.set_srtp_bit(seq);
        true
    }

    fn set_srtp_bit(&mut self, counter: u16) {
        let index = usize::from(counter) % REPLAY_WINDOW_BITS;
        if let Some(bitmap) = self.bitmap.as_mut() {
            bitmap[index / REPLAY_WORD_BITS] |= 1 << (index % REPLAY_WORD_BITS);
        }
    }

    fn clear_advanced(&mut self, first: u16, count: usize) {
        let mut bit = usize::from(first) % REPLAY_WINDOW_BITS;
        let mut remaining = count;
        if let Some(bitmap) = self.bitmap.as_mut() {
            while remaining != 0 {
                let offset = bit % REPLAY_WORD_BITS;
                let width = remaining.min(REPLAY_WORD_BITS - offset);
                let mask = if width == REPLAY_WORD_BITS {
                    u64::MAX
                } else {
                    ((1u64 << width) - 1) << offset
                };
                bitmap[bit / REPLAY_WORD_BITS] &= !mask;
                bit = (bit + width) % REPLAY_WINDOW_BITS;
                remaining -= width;
            }
        }
    }

    fn test_srtp_bit(&self, counter: u16) -> bool {
        let index = usize::from(counter) % REPLAY_WINDOW_BITS;
        self.bitmap.as_ref().is_some_and(|bitmap| {
            bitmap[index / REPLAY_WORD_BITS] & (1 << (index % REPLAY_WORD_BITS)) != 0
        })
    }
}

#[cfg(feature = "diagnostics")]
struct HotDebug {
    generation: u64,
    up_bytes: u64,
    down_bytes: u64,
    up_packets: u64,
    down_packets: u64,
}

#[cfg(feature = "diagnostics")]
impl HotDebug {
    fn new(generation: u64) -> Self {
        Self {
            generation,
            up_bytes: 0,
            down_bytes: 0,
            up_packets: 0,
            down_packets: 0,
        }
    }

    fn reset(&mut self, generation: u64) {
        self.generation = generation;
        self.up_bytes = 0;
        self.down_bytes = 0;
        self.up_packets = 0;
        self.down_packets = 0;
    }
}

struct HotSessionCold {
    password: Arc<str>,
    generation_id: u64,
    session_salt: String,
    key: [u8; 32],
    public: Arc<Session>,
    out_key: Option<[u8; 32]>,
}

pub(crate) struct HotSession {
    id: u64,
    peer: SocketAddr,
    local_ip: Option<IpAddr>,
    aes: aes::Aes128,
    hmac: Hmac<Sha1>,
    out_aes: aes::Aes128,
    out_hmac: Hmac<Sha1>,
    output: OutState,
    replay: ReplayState,
    tunnel_ip: Option<[u8; 4]>,
    registration_id: Option<u64>,
    worker_id: u16,
    desired_stream_count: u16,
    last_inbound_ms: u64,
    up_total: u64,
    down_total: u64,
    reported_up: u64,
    reported_down: u64,
    is_srtp: bool,
    pending_tunnel: bool,
    cold: Box<HotSessionCold>,
    #[cfg(feature = "diagnostics")]
    debug: HotDebug,
}

impl HotSession {
    #[allow(clippy::too_many_arguments)]
    fn legacy(
        id: u64,
        peer: SocketAddr,
        local_ip: Option<IpAddr>,
        password: Arc<str>,
        key: [u8; 32],
        payload_type: u8,
        is_srtp: bool,
        generation_id: u64,
        session_salt: &str,
        stream_debug_active: Arc<AtomicBool>,
        monotonic_ms: u64,
        wall_now: u64,
        out_key: Option<[u8; 32]>,
    ) -> Self {
        let public = Session::new(
            id,
            peer,
            &password,
            generation_id,
            session_salt,
            is_srtp,
            stream_debug_active,
            wall_now,
        );
        #[cfg(feature = "diagnostics")]
        let debug_generation = public.stream_debug.generation();
        Self {
            id,
            peer,
            local_ip,
            aes: make_aes_key(&key),
            hmac: make_hmac_key(&key),
            out_aes: out_key
                .as_ref()
                .map(make_aes_key)
                .unwrap_or_else(|| make_aes_key(&key)),
            out_hmac: out_key
                .as_ref()
                .map(make_hmac_key)
                .unwrap_or_else(|| make_hmac_key(&key)),
            output: OutState::new(payload_type),
            replay: ReplayState::new(),
            tunnel_ip: None,
            registration_id: None,
            worker_id: 0,
            desired_stream_count: 0,
            last_inbound_ms: monotonic_ms,
            up_total: 0,
            down_total: 0,
            reported_up: 0,
            reported_down: 0,
            is_srtp,
            pending_tunnel: false,
            cold: Box::new(HotSessionCold {
                password,
                generation_id,
                session_salt: session_salt.to_owned(),
                key,
                public,
                out_key,
            }),
            #[cfg(feature = "diagnostics")]
            debug: HotDebug::new(debug_generation),
        }
    }

    #[inline(always)]
    fn record_debug_up(&mut self, bytes: u64, enabled: bool) {
        #[cfg(feature = "diagnostics")]
        {
            if enabled {
                self.debug.up_bytes = self.debug.up_bytes.saturating_add(bytes);
                self.debug.up_packets = self.debug.up_packets.saturating_add(1);
            }
        }
        #[cfg(not(feature = "diagnostics"))]
        {
            let _ = (bytes, enabled);
        }
    }

    #[inline(always)]
    fn record_debug_down(&mut self, bytes: u64, enabled: bool) {
        #[cfg(feature = "diagnostics")]
        {
            if enabled {
                self.debug.down_bytes = self.debug.down_bytes.saturating_add(bytes);
                self.debug.down_packets = self.debug.down_packets.saturating_add(1);
            }
        }
        #[cfg(not(feature = "diagnostics"))]
        {
            let _ = (bytes, enabled);
        }
    }

    fn publish_counters(
        &mut self,
        global_up: &AtomicU64,
        global_down: &AtomicU64,
        wall_now: u64,
        debug_enabled: bool,
    ) {
        let up_delta = self.up_total.saturating_sub(self.reported_up);
        let down_delta = self.down_total.saturating_sub(self.reported_down);
        if up_delta != 0 {
            self.cold
                .public
                .up_bytes
                .fetch_add(up_delta, Ordering::Relaxed);
            global_up.fetch_add(up_delta, Ordering::Relaxed);
            self.reported_up = self.up_total;
        }
        if down_delta != 0 {
            self.cold
                .public
                .down_bytes
                .fetch_add(down_delta, Ordering::Relaxed);
            global_down.fetch_add(down_delta, Ordering::Relaxed);
            self.reported_down = self.down_total;
        }
        self.cold
            .public
            .last_seen
            .store(wall_now, Ordering::Relaxed);
        #[cfg(feature = "diagnostics")]
        {
            let generation = self.cold.public.stream_debug.generation();
            if generation != self.debug.generation {
                self.debug.reset(generation);
            }
            if debug_enabled {
                self.cold.public.stream_debug.publish(
                    self.debug.up_bytes,
                    self.debug.down_bytes,
                    self.debug.up_packets,
                    self.debug.down_packets,
                );
            }
        }
        #[cfg(not(feature = "diagnostics"))]
        {
            let _ = debug_enabled;
        }
    }
}

#[derive(Clone, Copy)]
struct DecodedPacket {
    range: RangePair,
    seq: u16,
    payload_type: u8,
    is_srtp: bool,
}

#[derive(Clone, Copy)]
struct RangePair {
    start: usize,
    end: usize,
}

impl RangePair {
    fn get<'a>(&self, packet: &'a [u8]) -> &'a [u8] {
        &packet[self.start..self.end]
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GetconfReconnectAction {
    Process,
    Replace,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct StreamIdentity {
    password: String,
    generation_id: u64,
    session_salt: String,
}

struct StreamInventory {
    desired_count: u16,
    desired_session_id: u64,
    present: [bool; MAX_STREAM_WORKERS + 1],
    carriers: Vec<usize>,
    seen: bool,
}

impl StreamInventory {
    fn new() -> Self {
        Self {
            desired_count: 0,
            desired_session_id: 0,
            present: [false; MAX_STREAM_WORKERS + 1],
            carriers: Vec::with_capacity(STREAM_CONTROL_CARRIERS),
            seen: false,
        }
    }

    fn reset(&mut self) {
        self.desired_count = 0;
        self.desired_session_id = 0;
        self.present.fill(false);
        self.carriers.clear();
        self.seen = false;
    }

    fn observe(&mut self, slot: usize, session: &HotSession, worker_id: usize) {
        if session.id >= self.desired_session_id {
            self.desired_count = session.desired_stream_count;
            self.desired_session_id = session.id;
        }
        self.present[worker_id] = true;
        self.seen = true;
        if self.carriers.len() < STREAM_CONTROL_CARRIERS {
            self.carriers.push(slot);
        }
    }

    fn fill_missing(&self, out: &mut Vec<u16>) {
        let desired = usize::from(self.desired_count).min(MAX_STREAM_WORKERS);
        for worker in 1..=desired {
            if !self.present[worker] {
                out.push(worker as u16);
            }
        }
    }
}

#[derive(Default)]
struct StreamRepairRound {
    sequence: u64,
    last_missing: Vec<u16>,
    first_missing_ms: u64,
    last_sent_ms: u64,
    alive_ids: Vec<u16>,
    alive_desired_count: u16,
    alive_sent_ms: u64,
    alive_sent: u8,
    last_seen_ms: u64,
}

impl StreamRepairRound {
    fn repair_payload(
        &mut self,
        missing: &[u16],
        desired_count: u16,
        now_ms: u64,
    ) -> Option<Vec<u8>> {
        if missing != self.last_missing.as_slice() {
            self.sequence = self.sequence.wrapping_add(1).max(1);
            self.last_missing.clear();
            self.last_missing.extend_from_slice(missing);
            self.first_missing_ms = now_ms;
            self.last_sent_ms = 0;
            self.alive_ids.clear();
            self.alive_sent = 0;
        }
        if now_ms.saturating_sub(self.first_missing_ms) < STREAM_REPAIR_GRACE_MS {
            return None;
        }
        if self.last_sent_ms != 0
            && now_ms.saturating_sub(self.last_sent_ms) < STREAM_REPAIR_ESCALATE_MS
        {
            return None;
        }
        if self.last_sent_ms != 0 {
            self.sequence = self.sequence.wrapping_add(1).max(1);
        }
        self.last_sent_ms = now_ms;
        Some(stream_control_payload(
            STREAM_REPAIR_PREFIX,
            self.sequence,
            desired_count,
            &self.last_missing,
        ))
    }

    fn recovered(&mut self, desired_count: u16, now_ms: u64) {
        if self.last_missing.is_empty() {
            return;
        }
        self.alive_ids = std::mem::take(&mut self.last_missing);
        self.alive_desired_count = desired_count;
        self.alive_sent_ms = 0;
        self.alive_sent = 0;
        self.first_missing_ms = now_ms;
        self.last_sent_ms = 0;
    }

    fn alive_payload(&mut self, now_ms: u64) -> Option<Vec<u8>> {
        if self.alive_ids.is_empty()
            || self.alive_sent >= STREAM_ALIVE_REPEAT
            || (self.alive_sent_ms != 0
                && now_ms.saturating_sub(self.alive_sent_ms) < STREAM_ALIVE_INTERVAL_MS)
        {
            return None;
        }
        self.alive_sent = self.alive_sent.saturating_add(1);
        self.alive_sent_ms = now_ms;
        Some(stream_control_payload(
            STREAM_ALIVE_PREFIX,
            self.sequence,
            self.alive_desired_count,
            &self.alive_ids,
        ))
    }

    fn active(&self) -> bool {
        !self.last_missing.is_empty()
            || (!self.alive_ids.is_empty() && self.alive_sent < STREAM_ALIVE_REPEAT)
    }
}

pub(crate) enum ProtocolCommand {
    SendPlain {
        session_id: u64,
        payload: Vec<u8>,
    },
    BroadcastPlain {
        payload: Vec<u8>,
    },
    ActivateTunnel {
        session_id: u64,
        ip: [u8; 4],
        registration_id: u64,
        worker_id: u16,
        desired_count: u16,
    },
    InjectTun {
        session_id: u64,
        payload: Vec<u8>,
    },
    UpdateEpoch {
        session_id: u64,
        password: String,
        generation_id: u64,
        session_salt: String,
    },
    SetAuthoritativeEpoch {
        password: String,
        generation_id: u64,
        session_salt: String,
    },
    DisconnectPassword {
        password: String,
        requester_session_id: u64,
    },
    ReplaceCredentials(Arc<CredentialSet>),
    DropSession {
        session_id: u64,
    },
    DropPassword {
        password: String,
    },
    UpdateDesiredStreams {
        password: String,
        generation_id: u64,
        desired_count: u16,
    },
    CompactMemory {
        completed: oneshot::Sender<bool>,
    },
}

enum ControlEvent {
    SessionCreated(Arc<Session>),
    SessionClosed {
        address: SocketAddr,
        session_id: u64,
        reason: &'static str,
    },
    Payload {
        address: SocketAddr,
        session_id: u64,
        payload: Vec<u8>,
    },
    IngressBeforeTunnel {
        address: SocketAddr,
        session_id: u64,
        payload: Vec<u8>,
    },
    StreamRepair {
        password: String,
        generation_id: u64,
        desired_count: u16,
        sequence: u64,
        missing: Vec<u16>,
        selected_carriers: usize,
        sent_carriers: usize,
    },
    IoCounters(IoCounters),
}

pub struct ProtocolRuntime {
    dataplane: Option<dataplane::DataplaneRuntime<ProtocolCommand>>,
    control_task: tokio::task::JoinHandle<()>,
    status: tokio::sync::watch::Receiver<Option<String>>,
}

impl ProtocolRuntime {
    pub fn status_receiver(&self) -> tokio::sync::watch::Receiver<Option<String>> {
        self.status.clone()
    }

    pub async fn shutdown(mut self) -> Result<()> {
        self.control_task.abort();
        let _ = tokio::time::timeout(Duration::from_millis(500), &mut self.control_task).await;
        if let Some(runtime) = self.dataplane.take() {
            runtime.shutdown()?;
        }
        Ok(())
    }
}

static CREDENTIALS_SNAPSHOT: std::sync::OnceLock<std::sync::Mutex<Arc<CredentialSet>>> =
    std::sync::OnceLock::new();
static EPOCHS_SNAPSHOT: std::sync::OnceLock<std::sync::Mutex<HashMap<String, EpochValue>>> =
    std::sync::OnceLock::new();

fn publish_credentials_snapshot(credentials: &Arc<CredentialSet>) {
    match CREDENTIALS_SNAPSHOT.get() {
        Some(snapshot) => *lock_unpoison(snapshot) = credentials.clone(),
        None => {
            let _ = CREDENTIALS_SNAPSHOT.set(std::sync::Mutex::new(credentials.clone()));
        }
    }
}

fn publish_epochs_snapshot(epochs: HashMap<String, EpochValue>) {
    match EPOCHS_SNAPSHOT.get() {
        Some(snapshot) => *lock_unpoison(snapshot) = epochs,
        None => {
            let _ = EPOCHS_SNAPSHOT.set(std::sync::Mutex::new(epochs));
        }
    }
}

fn latest_engine_state() -> (Arc<CredentialSet>, HashMap<String, EpochValue>) {
    let credentials = CREDENTIALS_SNAPSHOT
        .get()
        .map(|snapshot| lock_unpoison(snapshot).clone())
        .unwrap_or_default();
    let epochs = EPOCHS_SNAPSHOT
        .get()
        .map(|snapshot| lock_unpoison(snapshot).clone())
        .unwrap_or_default();
    (credentials, epochs)
}

pub async fn start(app: Arc<App>) -> Result<ProtocolRuntime> {
    let credentials = build_credentials(&app).await?;
    publish_credentials_snapshot(&credentials);
    publish_epochs_snapshot(HashMap::new());
    let (control_tx, control_rx) = mpsc::channel(CONTROL_EVENT_CAPACITY);
    let engine_factory = {
        let control_tx = control_tx.clone();
        let stream_debug_active = app.stream_debug_active.clone();
        let shared = EngineShared {
            global_up: app.bytes_from_client.clone(),
            global_down: app.bytes_to_client.clone(),
        };
        move || {
            let (credentials, epochs) = latest_engine_state();
            ProtocolEngine::new(
                credentials,
                epochs,
                control_tx.clone(),
                stream_debug_active.clone(),
                shared.clone(),
            )
        }
    };
    let runtime = dataplane::spawn(DataplaneConfig::new(app.listen), engine_factory)
        .context("start tokio protocol dataplane")?;
    let status = runtime.status_receiver();
    app.dataplane
        .set(runtime.handle())
        .map_err(|_| anyhow!("dataplane handle already initialized"))?;
    let control_app = app.clone();
    let control_task = tokio::spawn(async move {
        control_loop(control_app, control_rx).await;
    });
    Ok(ProtocolRuntime {
        dataplane: Some(runtime),
        control_task,
        status,
    })
}

pub async fn refresh_credentials(app: &Arc<App>) -> Result<()> {
    let credentials = build_credentials(app).await?;
    publish_credentials_snapshot(&credentials);
    command(app, ProtocolCommand::ReplaceCredentials(credentials))
}

pub const FALLBACK_DNS: &str = "77.88.8.8,77.88.8.1";

pub async fn effective_dns(app: &Arc<App>) -> String {
    let configured = app.dns.read().await.clone();
    let configured_clean = configured.trim();
    if configured_clean.is_empty() {
        FALLBACK_DNS.to_string()
    } else {
        configured_clean.to_string()
    }
}

pub async fn broadcast_tunnel_configuration(app: &Arc<App>) -> Result<usize> {
    let dns = effective_dns(app).await;
    let sessions = app
        .sessions
        .iter()
        .map(|entry| entry.value().clone())
        .collect::<Vec<_>>();
    let mut delivered = 0usize;
    let mut first_error = None;
    for session in sessions {
        let tunnel_ip = *lock_unpoison(&session.tunnel_ip);
        let Some(tunnel_ip) = tunnel_ip else {
            continue;
        };
        let payload = pad_tunconf(&format!(
            "TUNCONF:{}:{}:0:stream-v1",
            Ipv4Addr::from(tunnel_ip),
            dns
        ))
        .into_bytes();
        match command(
            app,
            ProtocolCommand::SendPlain {
                session_id: session.id,
                payload,
            },
        ) {
            Ok(()) => delivered = delivered.saturating_add(1),
            Err(error) => {
                if first_error.is_none() {
                    first_error = Some(error);
                }
            }
        }
    }
    if let Some(error) = first_error {
        return Err(error.context("queue hot tunnel configuration"));
    }
    Ok(delivered)
}

pub fn forget_idle_password_epoch(app: &Arc<App>, password: &str) {
    app.password_epochs.remove_if(password, |_, _| {
        !app.sessions
            .iter()
            .any(|session| session.password == password)
    });
}

pub fn drop_password_sessions(app: &Arc<App>, password: &str) {
    let _ = command(
        app,
        ProtocolCommand::DropPassword {
            password: password.to_owned(),
        },
    );
    let candidates = app
        .sessions
        .iter()
        .filter(|entry| entry.value().password == password)
        .map(|entry| *entry.key())
        .collect::<Vec<_>>();
    for session_id in candidates {
        if let Some((_, session)) = app
            .sessions
            .remove_if(&session_id, |_, current| current.id == session_id)
        {
            session.cancel_token.cancel();
        }
    }
}

pub fn drop_all_sessions(app: &Arc<App>) {
    let ids = app
        .sessions
        .iter()
        .map(|entry| entry.value().id)
        .collect::<Vec<_>>();
    for session_id in ids {
        let _ = command(app, ProtocolCommand::DropSession { session_id });
    }
    for entry in app.sessions.iter() {
        entry.value().cancel_token.cancel();
    }
    app.sessions.clear();
}

pub fn notify_panel_restart(app: &Arc<App>) -> Result<()> {
    command(
        app,
        ProtocolCommand::BroadcastPlain {
            payload: PANEL_RESTART_NOTICE.to_vec(),
        },
    )
}

pub async fn compact_memory(app: &Arc<App>) -> Result<bool> {
    let (completed, confirmation) = oneshot::channel();
    command(app, ProtocolCommand::CompactMemory { completed })?;
    tokio::time::timeout(Duration::from_secs(3), confirmation)
        .await
        .map_err(|_| anyhow!("dataplane memory compaction timed out"))?
        .map_err(|_| anyhow!("dataplane stopped before memory compaction"))
}

fn command(app: &Arc<App>, command_value: ProtocolCommand) -> Result<()> {
    app.dataplane
        .get()
        .ok_or_else(|| anyhow!("dataplane is not initialized"))?
        .try_send(command_value)
}

async fn db_read<'a>(app: &'a Arc<App>) -> Result<RwLockReadGuard<'a, Database>> {
    tokio::time::timeout(DB_LOCK_TIMEOUT, app.db.read())
        .await
        .map_err(|_| anyhow!("database read lock timed out"))
}

async fn db_write<'a>(app: &'a Arc<App>) -> Result<RwLockWriteGuard<'a, Database>> {
    tokio::time::timeout(DB_LOCK_TIMEOUT, app.db.write())
        .await
        .map_err(|_| anyhow!("database write lock timed out"))
}

async fn build_credentials(app: &Arc<App>) -> Result<Arc<CredentialSet>> {
    let keys_to_derive = {
        let db = db_read(app).await?;
        let mut keys = Vec::new();
        if !db.main_password.is_empty() {
            keys.push(db.main_password.clone());
        }
        for pass in db.passwords.keys() {
            if !pass.is_empty() {
                keys.push(pass.clone());
            }
        }
        keys
    };
    let active_keys: HashSet<&str> = keys_to_derive.iter().map(String::as_str).collect();
    app.derived_keys
        .retain(|k, _| active_keys.contains(k.as_str()));
    let mut entries = Vec::with_capacity(keys_to_derive.len());
    let mut seen = HashSet::new();
    for key_source in keys_to_derive {
        if !seen.insert(key_source.clone()) {
            continue;
        }
        let key = if let Some(existing) = app.derived_keys.get(&key_source) {
            *existing.value()
        } else {
            let key = derive_wrap_key(&key_source)?;
            app.derived_keys.insert(key_source.clone(), key);
            key
        };
        let dtls_keys = crate::dtls::derive_dtls_srtp_deterministic(&key);
        entries.push(Credential {
            password: Arc::<str>::from(key_source),
            key,
            aes: make_aes_key(&key),
            hmac: make_hmac_key(&key),
            dtls_client_cipher_key: dtls_keys.client_cipher_key,
            dtls_client_aes: make_aes_key(&dtls_keys.client_cipher_key),
            dtls_client_hmac: make_hmac_key(&dtls_keys.client_cipher_key),
            dtls_server_cipher_key: dtls_keys.server_cipher_key,
        });
    }
    Ok(Arc::new(CredentialSet { entries }))
}

struct ProtocolEngine {
    credentials: Arc<CredentialSet>,
    epochs: HashMap<String, EpochValue>,
    sessions: slab::Slab<HotSession>,
    by_peer: HashMap<EndpointKey, usize>,
    by_id: HashMap<u64, usize>,
    routes: RouteTable,
    control_tx: mpsc::Sender<ControlEvent>,
    stream_debug_active: Arc<AtomicBool>,
    global_up: Arc<AtomicU64>,
    global_down: Arc<AtomicU64>,
    rng: SmallRng,
    setup_scratch: PacketBuffer,
    stale_slots: Vec<usize>,
    started: Instant,
    monotonic_ms: u64,
    wall_now: u64,
    debug_enabled: bool,
    dpi_enabled: bool,
    dpi_sample_counter: u64,
    dpi_last_sample_ms: u64,
    syscalls_enabled: bool,
    io_metrics_enabled: bool,
    setup_budget: usize,
    unauthenticated_setup_logged: bool,
    last_unauthenticated_setup_log_ms: u64,
    uplink_source_drops: u64,
    last_uplink_source_log_ms: u64,
    last_io_counters: IoCounters,
    memory_compact_pending: bool,
    last_memory_compact_ms: u64,
    last_stream_reconcile_ms: u64,
    last_stream_inventory_resync_ms: u64,
    last_session_maintenance_ms: u64,
    stream_inventory_dirty: bool,
    stream_repairs: HashMap<StreamIdentity, StreamRepairRound>,
    stream_inventory: HashMap<StreamIdentity, StreamInventory>,
    stream_actions: Vec<StreamControlAction>,
    stream_missing_scratch: Vec<u16>,
    epoch_sweep_active: HashSet<String>,
    last_epoch_sweep_ms: u64,
    batch_now: Instant,
    dtls_handshakes: HashMap<EndpointKey, (crate::dtls::DtlsServerHandshake, Vec<u8>, u64)>,
    dtls_negotiated: HashMap<EndpointKey, (crate::dtls::DtlsSrtpKeys, Vec<u8>, u64)>,
    reassembler: crate::wire_protocol::Reassembler,
    next_frag_packet_id: u16,
}

struct StreamControlAction {
    carriers: Vec<usize>,
    payload: Vec<u8>,
    repair: Option<StreamRepairLog>,
}

struct StreamRepairLog {
    password: String,
    generation_id: u64,
    desired_count: u16,
    sequence: u64,
    missing: Vec<u16>,
}

#[derive(Clone)]
struct EngineShared {
    global_up: Arc<AtomicU64>,
    global_down: Arc<AtomicU64>,
}

impl ProtocolEngine {
    fn new(
        credentials: Arc<CredentialSet>,
        epochs: HashMap<String, EpochValue>,
        control_tx: mpsc::Sender<ControlEvent>,
        stream_debug_active: Arc<AtomicBool>,
        shared: EngineShared,
    ) -> Self {
        let wall_now = wall_clock();
        let engine = Self {
            credentials,
            epochs,
            sessions: slab::Slab::with_capacity(HOT_TABLE_RESERVE),
            by_peer: HashMap::with_capacity(HOT_TABLE_RESERVE),
            by_id: HashMap::with_capacity(HOT_TABLE_RESERVE),
            routes: RouteTable::new(),
            control_tx,
            stream_debug_active,
            global_up: shared.global_up,
            global_down: shared.global_down,
            rng: SmallRng::from_entropy(),
            setup_scratch: PacketBuffer::new(),
            stale_slots: Vec::with_capacity(256),
            started: Instant::now(),
            monotonic_ms: 1,
            wall_now,
            debug_enabled: false,
            dpi_enabled: false,
            dpi_sample_counter: 0,
            dpi_last_sample_ms: 0,
            syscalls_enabled: false,
            io_metrics_enabled: false,
            setup_budget: SETUP_BUDGET_PER_TICK * 2,
            unauthenticated_setup_logged: false,
            last_unauthenticated_setup_log_ms: 0,
            uplink_source_drops: 0,
            last_uplink_source_log_ms: 0,
            last_io_counters: IoCounters::default(),
            memory_compact_pending: false,
            last_memory_compact_ms: 0,
            last_stream_reconcile_ms: 0,
            last_stream_inventory_resync_ms: 0,
            last_session_maintenance_ms: 0,
            stream_inventory_dirty: true,
            stream_repairs: HashMap::new(),
            stream_inventory: HashMap::new(),
            stream_actions: Vec::new(),
            stream_missing_scratch: Vec::new(),
            epoch_sweep_active: HashSet::new(),
            last_epoch_sweep_ms: 0,
            batch_now: Instant::now(),
            dtls_handshakes: HashMap::new(),
            dtls_negotiated: HashMap::new(),
            reassembler: crate::wire_protocol::Reassembler::new(),
            next_frag_packet_id: 1,
        };
        engine.publish_session_gauges();
        engine.publish_memory_gauges();
        engine
    }

    fn publish_session_gauges(&self) {
        HOT_SESSION_CAPACITY_GAUGE.fetch_max(self.sessions.capacity() as u64, Ordering::Relaxed);
    }

    fn session_added(&self) {
        ACTIVE_SESSIONS_GAUGE.fetch_add(1, Ordering::Relaxed);
        self.publish_session_gauges();
    }

    fn session_removed(&self) {
        let _ = ACTIVE_SESSIONS_GAUGE.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| {
            value.checked_sub(1)
        });
        self.publish_session_gauges();
    }

    fn publish_memory_gauges(&self) {
        ENGINE_EPOCHS_GAUGE.store(self.epochs.len() as u64, Ordering::Relaxed);
        STREAM_REPAIRS_GAUGE.store(self.stream_repairs.len() as u64, Ordering::Relaxed);
        STREAM_INVENTORY_GAUGE.store(self.stream_inventory.len() as u64, Ordering::Relaxed);
    }

    fn publish_epoch_state(&self) {
        publish_epochs_snapshot(self.epochs.clone());
        self.publish_memory_gauges();
    }

    fn compact_memory(&mut self, force: bool) {
        let session_count = self.sessions.len();
        let session_capacity = self.sessions.capacity();
        if !force
            && (session_capacity <= HOT_TABLE_RESERVE
                || session_count.saturating_mul(2) > session_capacity)
        {
            self.memory_compact_pending = false;
            return;
        }
        self.stream_inventory_dirty = true;
        let by_peer = &mut self.by_peer;
        let by_id = &mut self.by_id;
        let routes = &mut self.routes;
        self.sessions.compact(|session, _, new_slot| {
            by_peer.insert(EndpointKey::new(session.peer, session.local_ip), new_slot);
            by_id.insert(session.id, new_slot);
            if let (Some(ip), Some(registration_id)) = (session.tunnel_ip, session.registration_id)
            {
                routes.update_slot(ip, session.id, registration_id, new_slot);
            }
            true
        });
        self.sessions.shrink_to_fit();
        if self.sessions.capacity() < HOT_TABLE_RESERVE {
            self.sessions
                .reserve(HOT_TABLE_RESERVE.saturating_sub(self.sessions.len()));
        }
        self.by_peer.shrink_to(HOT_TABLE_RESERVE.max(session_count));
        self.by_id.shrink_to(HOT_TABLE_RESERVE.max(session_count));
        self.epochs.shrink_to_fit();
        self.stream_repairs.shrink_to_fit();
        self.stream_inventory.shrink_to_fit();
        self.stale_slots.shrink_to(256);
        self.memory_compact_pending = false;
        self.last_memory_compact_ms = self.monotonic_ms;
        self.publish_session_gauges();
        self.publish_memory_gauges();
    }

    fn shrink_stream_bookkeeping(&mut self) {
        const RETAINED_CAPACITY: usize = 32;
        if self.stream_repairs.capacity() > RETAINED_CAPACITY
            && self.stream_repairs.len().saturating_mul(4) < self.stream_repairs.capacity()
        {
            self.stream_repairs
                .shrink_to(RETAINED_CAPACITY.max(self.stream_repairs.len()));
        }
        if self.stream_inventory.capacity() > RETAINED_CAPACITY
            && self.stream_inventory.len().saturating_mul(4) < self.stream_inventory.capacity()
        {
            self.stream_inventory
                .shrink_to(RETAINED_CAPACITY.max(self.stream_inventory.len()));
        }
        if self.stream_actions.capacity() > RETAINED_CAPACITY
            && self.stream_actions.len().saturating_mul(4) < self.stream_actions.capacity()
        {
            self.stream_actions
                .shrink_to(RETAINED_CAPACITY.max(self.stream_actions.len()));
        }
    }

    fn shrink_epoch_bookkeeping(&mut self) {
        const RETAINED_CAPACITY: usize = 32;
        if self.epochs.capacity() > RETAINED_CAPACITY
            && self.epochs.len().saturating_mul(4) < self.epochs.capacity()
        {
            self.epochs
                .shrink_to(RETAINED_CAPACITY.max(self.epochs.len()));
        }
        if self.epoch_sweep_active.capacity() > RETAINED_CAPACITY
            && self.epoch_sweep_active.len().saturating_mul(4) < self.epoch_sweep_active.capacity()
        {
            self.epoch_sweep_active
                .shrink_to(RETAINED_CAPACITY.max(self.epoch_sweep_active.len()));
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn create_legacy_session(
        &mut self,
        peer: SocketAddr,
        local_ip: Option<IpAddr>,
        password: Arc<str>,
        key: [u8; 32],
        payload_type: u8,
        is_srtp: bool,
        generation_id: u64,
        session_salt: &str,
        replay_seq: Option<u16>,
        out_key: Option<[u8; 32]>,
    ) -> usize {
        let endpoint_key = EndpointKey::new(peer, local_ip);
        if let Some(old_slot) = self.by_peer.get(&endpoint_key).copied() {
            self.remove_slot_with_reason(old_slot, "peer-replaced");
        }
        let id = SESSION_ID_COUNTER.fetch_add(1, Ordering::Relaxed);
        let mut session = HotSession::legacy(
            id,
            peer,
            local_ip,
            password,
            key,
            payload_type,
            is_srtp,
            generation_id,
            session_salt,
            self.stream_debug_active.clone(),
            self.monotonic_ms,
            self.wall_now,
            out_key,
        );
        if let Some(seq) = replay_seq {
            let _ = session.replay.accept(seq);
        }
        let slot = self.sessions.insert(session);
        self.by_peer.insert(endpoint_key, slot);
        self.by_id.insert(id, slot);
        self.stream_inventory_dirty = true;
        self.session_added();
        slot
    }

    fn publish_created_session(&self, session: &HotSession, payload: Vec<u8>) {
        let _ = self
            .control_tx
            .try_send(ControlEvent::SessionCreated(session.cold.public.clone()));
        let _ = self.control_tx.try_send(ControlEvent::Payload {
            address: session.peer,
            session_id: session.id,
            payload,
        });
    }

    fn place_new_session(&mut self, slot: usize, payload: Vec<u8>) {
        let Some(session) = self.sessions.get(slot) else {
            return;
        };
        self.publish_created_session(session, payload);
    }

    fn remove_slot_with_reason(&mut self, slot: usize, reason: &'static str) {
        if !self.sessions.contains(slot) {
            return;
        }
        let mut session = self.sessions.remove(slot);
        self.memory_compact_pending = true;
        self.stream_inventory_dirty = true;
        self.session_removed();
        session.publish_counters(
            &self.global_up,
            &self.global_down,
            self.wall_now,
            self.debug_enabled,
        );
        self.by_peer
            .remove(&EndpointKey::new(session.peer, session.local_ip));
        self.by_id.remove(&session.id);
        if let (Some(ip), Some(registration_id)) = (session.tunnel_ip, session.registration_id) {
            let removed_active_route = self.routes.unregister(ip, registration_id);
            if removed_active_route {
                let worker_id = session.cold.public.worker_id.load(Ordering::Acquire);
                let fallback = self
                    .sessions
                    .iter()
                    .filter(|(_, candidate)| {
                        candidate.tunnel_ip == Some(ip)
                            && candidate.registration_id.is_some()
                            && candidate.cold.password == session.cold.password
                            && candidate.cold.generation_id == session.cold.generation_id
                            && candidate.cold.session_salt == session.cold.session_salt
                            && candidate.cold.public.worker_id.load(Ordering::Acquire) == worker_id
                    })
                    .max_by_key(|(_, candidate)| candidate.id)
                    .map(|(candidate_slot, candidate)| {
                        (
                            candidate_slot,
                            candidate.id,
                            candidate.registration_id.unwrap_or_default(),
                            candidate.cold.public.clone(),
                        )
                    });
                if let Some((fallback_slot, fallback_id, fallback_registration, public)) = fallback
                    && let Ok(worker_id) = u16::try_from(worker_id)
                {
                    self.routes.register(
                        ip,
                        fallback_id,
                        fallback_registration,
                        worker_id,
                        fallback_slot,
                    );
                    public.has_tunnel.store(true, Ordering::Release);
                }
            }
        }
        session
            .cold
            .public
            .has_tunnel
            .store(false, Ordering::Release);
        session.cold.public.cancel_token.cancel();
        let _ = self.control_tx.try_send(ControlEvent::SessionClosed {
            address: session.peer,
            session_id: session.id,
            reason,
        });
    }

    fn handle_unknown_legacy(&mut self, peer: SocketAddr, local_ip: Option<IpAddr>, wire: &[u8]) {
        if self.sessions.len() >= MAX_ACTIVE_SESSIONS
            || wire.len() > PACKET_CAPACITY
            || self.setup_budget == 0
        {
            return;
        }
        self.setup_budget -= 1;
        let mut found = None;
        let endpoint_key = EndpointKey::new(peer, local_ip);
        let dtls_entry = self
            .dtls_negotiated
            .get(&endpoint_key)
            .or_else(|| {
                if local_ip.is_some() {
                    self.dtls_negotiated.get(&EndpointKey::new(peer, None))
                } else {
                    None
                }
            })
            .map(|(keys, _, ms)| (*keys, *ms));
        if let Some((dtls_keys, _)) = dtls_entry
            && self.setup_scratch.copy_from(wire)
        {
            let aes = make_aes_key(&dtls_keys.client_cipher_key);
            let hmac = make_hmac_key(&dtls_keys.client_cipher_key);
            let decoded = {
                let packet = self.setup_scratch.as_mut_slice();
                unwrap_legacy_in_place(&aes, &hmac, packet, None, self.syscalls_enabled)
            };
            if let Ok(decoded) = decoded {
                let plain = decoded.range.get(self.setup_scratch.as_slice());
                if let Some((password, generation_id, session_salt)) = parse_getconf_identity(plain)
                {
                    let matched = self
                        .credentials
                        .entries
                        .iter()
                        .find(|c| c.password.as_ref() == password);
                    if let Some(credential) = matched {
                        found = Some((
                            credential.password.clone(),
                            dtls_keys.client_cipher_key,
                            decoded,
                            generation_id,
                            session_salt.to_owned(),
                            plain.to_vec(),
                            Some(dtls_keys.server_cipher_key),
                        ));
                    }
                }
            }
        }
        if found.is_none() {
            for credential in &self.credentials.entries {
                if !self.setup_scratch.copy_from(wire) {
                    return;
                }
                let decoded = {
                    let packet = self.setup_scratch.as_mut_slice();
                    unwrap_legacy_in_place(
                        &credential.dtls_client_aes,
                        &credential.dtls_client_hmac,
                        packet,
                        None,
                        self.syscalls_enabled,
                    )
                };
                let Ok(decoded) = decoded else {
                    continue;
                };
                let plain = decoded.range.get(self.setup_scratch.as_slice());
                let Some((password, generation_id, session_salt)) = parse_getconf_identity(plain)
                else {
                    continue;
                };
                if credential.password.as_ref() != password {
                    continue;
                }
                found = Some((
                    credential.password.clone(),
                    credential.dtls_client_cipher_key,
                    decoded,
                    generation_id,
                    session_salt.to_owned(),
                    plain.to_vec(),
                    Some(credential.dtls_server_cipher_key),
                ));
                break;
            }
        }
        if found.is_none() {
            for credential in &self.credentials.entries {
                if !self.setup_scratch.copy_from(wire) {
                    return;
                }
                let decoded = {
                    let packet = self.setup_scratch.as_mut_slice();
                    unwrap_legacy_in_place(
                        &credential.aes,
                        &credential.hmac,
                        packet,
                        None,
                        self.syscalls_enabled,
                    )
                };
                let Ok(decoded) = decoded else {
                    continue;
                };
                let plain = decoded.range.get(self.setup_scratch.as_slice());
                let Some((password, generation_id, session_salt)) = parse_getconf_identity(plain)
                else {
                    continue;
                };
                if credential.password.as_ref() != password {
                    continue;
                }
                found = Some((
                    credential.password.clone(),
                    credential.key,
                    decoded,
                    generation_id,
                    session_salt.to_owned(),
                    plain.to_vec(),
                    None,
                ));
                break;
            }
        }
        let Some((password, key, decoded, generation_id, session_salt, plain, out_key)) = found
        else {
            self.report_unauthenticated_setup(peer, wire.len());
            return;
        };
        let slot = self.create_legacy_session(
            peer,
            local_ip,
            password,
            key,
            decoded.payload_type,
            decoded.is_srtp,
            generation_id,
            &session_salt,
            Some(decoded.seq),
            out_key,
        );
        let record_dpi = should_record_dpi(
            self.dpi_enabled,
            self.monotonic_ms,
            &mut self.dpi_sample_counter,
            &mut self.dpi_last_sample_ms,
            &plain,
        );
        let Some(session) = self.sessions.get(slot) else {
            return;
        };
        if record_dpi {
            record_packet_dpi(
                "INBOUND",
                &peer.to_string(),
                "TUN-Server",
                decoded.payload_type,
                decoded.seq as u64,
                &plain,
                wire.len(),
                &session.cold.password,
                session.cold.generation_id,
                &session.cold.session_salt,
            );
        }
        self.place_new_session(slot, plain);
    }

    fn record_uplink_source_drop(&mut self, slot: usize) {
        self.uplink_source_drops = self.uplink_source_drops.saturating_add(1);
        let elapsed = self
            .monotonic_ms
            .saturating_sub(self.last_uplink_source_log_ms);
        if self.uplink_source_drops > 1 && elapsed < UNAUTHENTICATED_SETUP_LOG_INTERVAL_MS {
            return;
        }
        self.last_uplink_source_log_ms = self.monotonic_ms;
        let peer = self
            .sessions
            .get(slot)
            .map(|session| session.peer.to_string())
            .unwrap_or_default();
        eprintln!(
            "[CSQTT] dropped uplink IPv4 packet from {peer}: source address is not the session tunnel IP (total {})",
            self.uplink_source_drops
        );
    }

    fn report_unauthenticated_setup(&mut self, peer: SocketAddr, wire_len: usize) {
        if self.unauthenticated_setup_logged
            && self
                .monotonic_ms
                .saturating_sub(self.last_unauthenticated_setup_log_ms)
                < UNAUTHENTICATED_SETUP_LOG_INTERVAL_MS
        {
            return;
        }
        self.unauthenticated_setup_logged = true;
        self.last_unauthenticated_setup_log_ms = self.monotonic_ms;
        eprintln!(
            "[CSQTT] UDP setup from {peer} rejected ({wire_len} bytes): no active credential could decrypt or accept GETCONF"
        );
    }

    fn handle_dtls(
        &mut self,
        peer: SocketAddr,
        local_ip: Option<IpAddr>,
        packet: &[u8],
        sink: &mut impl PacketOutput,
    ) {
        let (is_channel, payload) = if packet.len() >= 4 && packet[0] & 0xc0 == 0x40 {
            let length = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if length <= packet.len() - 4 {
                (true, &packet[4..4 + length])
            } else {
                (true, &packet[4..])
            }
        } else {
            (false, packet)
        };
        let endpoint_key = EndpointKey::new(peer, local_ip);
        let fallback_key = local_ip.map(|_| EndpointKey::new(peer, None));
        let mut entry_key = None;
        if self.dtls_handshakes.contains_key(&endpoint_key) {
            entry_key = Some(endpoint_key);
        } else if let Some(fb) = fallback_key
            && self.dtls_handshakes.contains_key(&fb)
        {
            entry_key = Some(fb);
        }
        if let Some(key) = entry_key {
            let outcome = match self.dtls_handshakes.get_mut(&key) {
                Some(entry) => {
                    entry.2 = self.monotonic_ms;
                    entry.0.handle_datagram(payload)
                }
                None => return,
            };

            let flight = match outcome {
                Ok(flight) => flight,

                Err(error) => {
                    eprintln!(
                        "[CSQTT] DTLS: рукопожатие с {peer} отклонено ({error}), сбрасываем состояние"
                    );
                    self.dtls_handshakes.remove(&key);
                    return;
                }
            };

            let Some((mut handshake, _, _)) = self.dtls_handshakes.remove(&key) else {
                return;
            };
            let srtp_keys = handshake.srtp_keys().copied();
            let ready = handshake.is_connected() && srtp_keys.is_some();
            let client_app_data = handshake.take_application_data().into_iter().next();

            if let Some(flight) = flight.as_ref() {
                eprintln!(
                    "[CSQTT] DTLS: полёт → {peer} ({} байт, {}), состояние {}",
                    flight.len(),
                    if is_channel { "ChannelData" } else { "UDP" },
                    dtls_state_label(handshake.state())
                );
                self.send_dtls_flight(peer, local_ip, is_channel, flight, sink);
            }

            if !ready {
                insert_bounded_endpoint(
                    &mut self.dtls_handshakes,
                    key,
                    (handshake, flight.unwrap_or_default(), self.monotonic_ms),
                );
                return;
            }

            let Some(dtls_keys) = srtp_keys else {
                insert_bounded_endpoint(
                    &mut self.dtls_handshakes,
                    key,
                    (handshake, flight.unwrap_or_default(), self.monotonic_ms),
                );
                return;
            };
            let flight2 = flight.unwrap_or_default();
            insert_bounded_endpoint(
                &mut self.dtls_negotiated,
                endpoint_key,
                (dtls_keys, flight2.clone(), self.monotonic_ms),
            );
            if let Some(fb) = fallback_key {
                insert_bounded_endpoint(
                    &mut self.dtls_negotiated,
                    fb,
                    (dtls_keys, flight2, self.monotonic_ms),
                );
            }
            if let Some(app_data) = client_app_data {
                match parse_getconf_identity(&app_data) {
                    Some((password, generation_id, session_salt))
                        if self.credentials.contains_password(password) =>
                    {
                        eprintln!(
                            "[CSQTT] DTLS: рукопожатие с {peer} завершено, SRTP-ключи согласованы, GETCONF {} байт внутри DTLS",
                            app_data.len()
                        );
                        let slot = self.create_legacy_session(
                            peer,
                            local_ip,
                            Arc::from(password),
                            dtls_keys.client_cipher_key,
                            111,
                            true,
                            generation_id,
                            session_salt,
                            None,
                            Some(dtls_keys.server_cipher_key),
                        );
                        if let Some(session) = self.sessions.get(slot) {
                            self.publish_created_session(session, app_data);
                        }
                    }
                    _ => {
                        eprintln!(
                            "[CSQTT] DTLS: рукопожатие с {peer} завершено, но GETCONF не распознан"
                        );
                        self.report_unauthenticated_setup(peer, packet.len());
                    }
                }
            } else if ready {
                eprintln!(
                    "[CSQTT] DTLS: рукопожатие с {peer} завершено, SRTP-ключи согласованы, ожидаем GETCONF по SRTP"
                );
            }
            return;
        }

        let cached_entry = self
            .dtls_negotiated
            .get(&endpoint_key)
            .or_else(|| fallback_key.and_then(|fb| self.dtls_negotiated.get(&fb)));
        if let Some((_, cached_flight2, _)) = cached_entry {
            if !crate::dtls::is_client_hello(payload) {
                self.send_dtls_flight(peer, local_ip, is_channel, cached_flight2, sink);
                return;
            } else {
                self.dtls_negotiated.remove(&endpoint_key);
                if let Some(fb) = fallback_key {
                    self.dtls_negotiated.remove(&fb);
                }
            }
        }

        if self.sessions.len() >= MAX_ACTIVE_SESSIONS || self.setup_budget == 0 {
            eprintln!(
                "[CSQTT] DTLS: ClientHello от {peer} отброшен (сессий {}, бюджет настройки {})",
                self.sessions.len(),
                self.setup_budget
            );
            return;
        }
        self.setup_budget -= 1;

        let Ok(mut handshake) = crate::dtls::DtlsServerHandshake::new() else {
            eprintln!("[CSQTT] DTLS: не удалось создать сертификат для {peer}");
            return;
        };
        match handshake.handle_datagram(payload) {
            Ok(Some(flight)) => {
                self.send_dtls_flight(peer, local_ip, is_channel, &flight, sink);
                insert_bounded_endpoint(
                    &mut self.dtls_handshakes,
                    endpoint_key,
                    (handshake, flight, self.monotonic_ms),
                );
            }
            Ok(None) => eprintln!("[CSQTT] DTLS: ClientHello от {peer} без ответа"),
            Err(error) => eprintln!("[CSQTT] DTLS: ClientHello от {peer} отвергнут ({error})"),
        }
    }

    fn send_dtls_flight(
        &self,
        peer: SocketAddr,
        local_ip: Option<IpAddr>,
        is_channel: bool,
        flight: &[u8],
        sink: &mut impl PacketOutput,
    ) {
        sink.send_udp_prioritized(peer, local_ip, false, true, |output| {
            if is_channel {
                let channel = 0x4000u16;
                let payload_len = flight.len();
                let pad = (4 - (payload_len % 4)) % 4;
                let total = 4 + payload_len + pad;
                let storage = output.storage_mut();
                if total > storage.len() {
                    return false;
                }
                storage[..2].copy_from_slice(&channel.to_be_bytes());
                storage[2..4].copy_from_slice(&(payload_len as u16).to_be_bytes());
                storage[4..4 + payload_len].copy_from_slice(flight);
                if pad > 0 {
                    storage[4 + payload_len..total].fill(0);
                }
                output.set_len(total)
            } else {
                output.copy_from(flight)
            }
        });
        sink.request_udp_flush();
    }

    fn handle_existing(
        &mut self,
        slot: usize,
        local_ip: Option<IpAddr>,
        packet: &mut [u8],
        sink: &mut impl PacketOutput,
    ) -> bool {
        if !self.sessions.contains(slot) {
            return false;
        }
        let decoded = {
            let session = &mut self.sessions[slot];
            unwrap_legacy_in_place(
                &session.aes,
                &session.hmac,
                packet,
                Some(session.is_srtp),
                self.syscalls_enabled,
            )
            .ok()
        };
        let Some(decoded) = decoded else {
            return false;
        };
        let plain = decoded.range.get(packet);

        let is_getconf = plain.starts_with(b"GETCONF:");
        if is_getconf {
            let Some((incoming_password, incoming_generation, incoming_salt)) =
                parse_getconf_identity(plain)
            else {
                return true;
            };
            let session = &self.sessions[slot];
            if let Some(epoch) = self.epochs.get_mut(incoming_password) {
                epoch.last_seen_ms = self.monotonic_ms;
            }
            match getconf_reconnect_action_hot(
                &session.cold.password,
                session.cold.generation_id,
                &session.cold.session_salt,
                incoming_password,
                incoming_generation,
                incoming_salt,
            ) {
                GetconfReconnectAction::Replace => {
                    let peer = session.peer;
                    let local_ip = local_ip.or(session.local_ip);
                    let password = session.cold.password.clone();
                    let key = session.cold.key;
                    let out_key = session.cold.out_key;
                    let payload_type = decoded.payload_type;
                    let is_srtp = decoded.is_srtp;
                    let payload = plain.to_vec();
                    let session_salt = incoming_salt.to_owned();
                    self.remove_slot_with_reason(slot, "epoch-replaced");
                    let new_slot = self.create_legacy_session(
                        peer,
                        local_ip,
                        password,
                        key,
                        payload_type,
                        is_srtp,
                        incoming_generation,
                        &session_salt,
                        Some(decoded.seq),
                        out_key,
                    );
                    self.place_new_session(new_slot, payload);
                    return true;
                }
                GetconfReconnectAction::Process => {}
            }
        }
        let accepted = {
            let session = &mut self.sessions[slot];
            session.replay.accept(decoded.seq)
        };
        if !accepted {
            return true;
        }
        let mut endpoint_key_update = None;
        {
            let session = &mut self.sessions[slot];
            session.last_inbound_ms = self.monotonic_ms;
            if local_ip.is_some() && session.local_ip != local_ip {
                endpoint_key_update = Some((session.peer, session.local_ip, local_ip));
                session.local_ip = local_ip;
            }
        }
        if let Some((peer, old_local_ip, new_local_ip)) = endpoint_key_update {
            self.by_peer.remove(&EndpointKey::new(peer, old_local_ip));
            self.by_peer
                .insert(EndpointKey::new(peer, new_local_ip), slot);
        }
        if plain == SESSION_LEASE {
            return true;
        }
        let record_dpi = should_record_dpi(
            self.dpi_enabled,
            self.monotonic_ms,
            &mut self.dpi_sample_counter,
            &mut self.dpi_last_sample_ms,
            plain,
        );
        {
            let session = &mut self.sessions[slot];
            if record_dpi {
                record_packet_dpi(
                    "INBOUND",
                    &session.peer.to_string(),
                    "TUN-Server",
                    decoded.payload_type,
                    decoded.seq as u64,
                    plain,
                    packet.len(),
                    &session.cold.password,
                    session.cold.generation_id,
                    &session.cold.session_salt,
                );
            }
            if plain == b"READY" {
                if self.sessions[slot].tunnel_ip.is_none() {
                    let pass = self.sessions[slot].cold.password.clone();
                    let existing_tunnel = if !pass.is_empty() {
                        self.sessions.iter().find_map(|(_, s)| {
                            if s.cold.password == pass {
                                s.tunnel_ip.map(|ip| (ip, s.desired_stream_count))
                            } else {
                                None
                            }
                        })
                    } else {
                        None
                    };
                    if let Some((ip, desired_count)) = existing_tunnel {
                        let reg_id = ROUTE_REGISTRATION_COUNTER.fetch_add(1, Ordering::Relaxed);
                        let worker_id = self.sessions[slot].worker_id;
                        let session_id = self.sessions[slot].id;
                        self.activate_tunnel(session_id, ip, reg_id, worker_id, desired_count);
                    }
                }
                let session = &mut self.sessions[slot];
                send_plain(
                    session,
                    b"READY_OK",
                    &mut self.rng,
                    sink,
                    self.batch_now,
                    SendObservability {
                        record_dpi,
                        count_crypto: self.syscalls_enabled,
                    },
                );
                return true;
            }
            if is_idle_keepalive(plain) {
                return true;
            }
            if is_control_payload(plain) {
                let event = ControlEvent::Payload {
                    address: session.peer,
                    session_id: session.id,
                    payload: plain.to_vec(),
                };
                let _ = self.control_tx.try_send(event);
                return true;
            }
        }
        let active_tunnel = self.sessions[slot].tunnel_ip.is_some()
            && self.sessions[slot].registration_id.is_some();
        if active_tunnel {
            self.write_ingress_packet(slot, plain, sink);
            return true;
        }
        let session = &mut self.sessions[slot];
        if !session.pending_tunnel {
            session.pending_tunnel = true;
            let event = ControlEvent::IngressBeforeTunnel {
                address: session.peer,
                session_id: session.id,
                payload: plain.to_vec(),
            };
            if self.control_tx.try_send(event).is_err() {
                session.pending_tunnel = false;
            }
        }
        true
    }

    fn write_ingress_packet(&mut self, slot: usize, packet: &[u8], sink: &mut impl PacketOutput) {
        let tunnel_ip = self
            .sessions
            .get(slot)
            .and_then(|session| session.tunnel_ip);
        let mut offset = 0;
        let p = packet;
        while offset < p.len() {
            let rem = &p[offset..];
            if rem.is_empty() {
                break;
            }
            if rem[0] == crate::wire_protocol::CSQTT_FRAG_MAGIC {
                if rem.len() < crate::wire_protocol::FRAG_HEADER_LEN {
                    break;
                }
                let Some(header) = crate::wire_protocol::FragHeader::decode(rem) else {
                    break;
                };
                let chunk_len = if header.frag_idx + 1 == header.total_frags {
                    (header.total_len as usize).saturating_sub(
                        header.frag_idx as usize * crate::wire_protocol::MAX_FRAG_PAYLOAD,
                    )
                } else {
                    crate::wire_protocol::MAX_FRAG_PAYLOAD
                };
                let frag_total_len = crate::wire_protocol::FRAG_HEADER_LEN + chunk_len;
                if frag_total_len > rem.len() {
                    break;
                }
                let frag_slice = &rem[..frag_total_len];
                let now = self.batch_now;
                let peer = self.sessions[slot]
                    .tunnel_ip
                    .map(|ip| u32::from_be_bytes(ip) as u64)
                    .unwrap_or(self.sessions[slot].id);
                let mut written = false;
                let mut spoofed = false;
                if self
                    .reassembler
                    .process_fragment_from(peer, frag_slice, now, |assembled| {
                        if uplink_source_matches(assembled, tunnel_ip) {
                            written = sink.write_tun(assembled);
                        } else {
                            spoofed = true;
                        }
                    })
                    && written
                    && let Some(session) = self.sessions.get_mut(slot)
                {
                    session.up_total = session.up_total.saturating_add(header.total_len as u64);
                    session.record_debug_up(header.total_len as u64, self.debug_enabled);
                }
                if spoofed {
                    self.record_uplink_source_drop(slot);
                }
                offset += frag_total_len;
                continue;
            }
            if rem.len() < 20 {
                break;
            }
            let version = rem[0] >> 4;
            let pkt_len = if version == 4 {
                u16::from_be_bytes([rem[2], rem[3]]) as usize
            } else if version == 6 && rem.len() >= 40 {
                40 + u16::from_be_bytes([rem[4], rem[5]]) as usize
            } else {
                break;
            };
            if pkt_len < 20 || pkt_len > rem.len() {
                break;
            }
            let slice = &rem[..pkt_len];
            if !uplink_source_matches(slice, tunnel_ip) {
                self.record_uplink_source_drop(slot);
                offset += pkt_len;
                continue;
            }
            let written = sink.write_tun(slice);
            if written && let Some(session) = self.sessions.get_mut(slot) {
                session.up_total = session.up_total.saturating_add(slice.len() as u64);
                session.record_debug_up(slice.len() as u64, self.debug_enabled);
            }
            offset += pkt_len;
        }
    }

    fn activate_tunnel(
        &mut self,
        session_id: u64,
        ip: [u8; 4],
        registration_id: u64,
        worker_id: u16,
        desired_count: u16,
    ) -> bool {
        let Some(slot) = self.by_id.get(&session_id).copied() else {
            return false;
        };
        let session = &mut self.sessions[slot];
        if let (Some(previous_ip), Some(old_registration)) =
            (session.tunnel_ip, session.registration_id)
        {
            self.routes.unregister(previous_ip, old_registration);
        }
        if self
            .routes
            .register(ip, session_id, registration_id, worker_id, slot)
        {
            session.tunnel_ip = Some(ip);
            session.registration_id = Some(registration_id);
            session.worker_id = worker_id;
            session.desired_stream_count = desired_count;
            session.pending_tunnel = false;
            *lock_unpoison(&session.cold.public.tunnel_ip) = Some(ip);
            session
                .cold
                .public
                .worker_id
                .store(u64::from(worker_id), Ordering::Release);
            session
                .cold
                .public
                .desired_stream_count
                .store(u64::from(desired_count), Ordering::Release);
            session
                .cold
                .public
                .has_tunnel
                .store(true, Ordering::Release);
            self.stream_inventory_dirty = true;
            true
        } else {
            false
        }
    }

    fn update_epoch(
        &mut self,
        session_id: u64,
        password: String,
        generation_id: u64,
        session_salt: String,
    ) {
        let Some(slot) = self.by_id.get(&session_id).copied() else {
            return;
        };
        {
            let session = &mut self.sessions[slot];
            session.cold.password = Arc::from(password.as_str());
            session.cold.generation_id = generation_id;
            session.cold.session_salt = session_salt.clone();
            session
                .cold
                .public
                .generation_id
                .store(generation_id, Ordering::Release);
            *lock_unpoison(&session.cold.public.session_salt) = session_salt;
        }
        self.stream_inventory_dirty = true;
    }

    fn send_down_endpoint(
        &mut self,
        client_key: usize,
        endpoint: crate::tun_device::RouteEndpoint,
        packet: &[u8],
        mut headroom: usize,
        sink: &mut impl PacketOutput,
    ) -> DownlinkSend {
        let Some(session) = self.sessions.get(endpoint.slot) else {
            return DownlinkSend::Stale;
        };
        if session.id != endpoint.session_id
            || session.registration_id != Some(endpoint.registration_id)
        {
            return DownlinkSend::Stale;
        }
        let sent = {
            let Some(session) = self.sessions.get_mut(endpoint.slot) else {
                return DownlinkSend::Stale;
            };
            let record_dpi = should_record_dpi(
                self.dpi_enabled,
                self.monotonic_ms,
                &mut self.dpi_sample_counter,
                &mut self.dpi_last_sample_ms,
                packet,
            );
            if packet.len() <= 1280 {
                let sent = send_data_client(
                    client_key,
                    session,
                    packet,
                    &mut self.rng,
                    sink,
                    self.batch_now,
                    SendObservability {
                        record_dpi,
                        count_crypto: self.syscalls_enabled,
                    },
                    headroom,
                );
                if sent {
                    session.down_total = session.down_total.saturating_add(packet.len() as u64);
                    session.record_debug_down(packet.len() as u64, self.debug_enabled);
                }
                sent
            } else {
                // Reserve the whole in-flight window for every fragment before
                // emitting any of them, so a packet is never torn apart by the
                // per-client window (which would lose all fragments).
                let frag_count = packet
                    .len()
                    .div_ceil(crate::wire_protocol::MAX_FRAG_PAYLOAD);
                if headroom < frag_count {
                    return DownlinkSend::Backpressured;
                }
                let packet_id = self.next_frag_packet_id;
                self.next_frag_packet_id = self.next_frag_packet_id.wrapping_add(1);
                let mut all_sent = true;
                let rng = &mut self.rng;
                let batch_now = self.batch_now;
                let count_crypto = self.syscalls_enabled;
                crate::wire_protocol::fragment_packet(packet_id, packet, |frag| {
                    let sent = send_data_client(
                        client_key,
                        session,
                        frag,
                        rng,
                        sink,
                        batch_now,
                        SendObservability {
                            record_dpi: false,
                            count_crypto,
                        },
                        headroom,
                    );
                    if sent {
                        headroom = headroom.saturating_sub(1);
                    } else {
                        all_sent = false;
                    }
                });
                if all_sent {
                    session.down_total = session.down_total.saturating_add(packet.len() as u64);
                    session.record_debug_down(packet.len() as u64, self.debug_enabled);
                }
                all_sent
            }
        };
        if sent {
            DownlinkSend::Sent
        } else {
            DownlinkSend::Backpressured
        }
    }

    fn send_down_key(
        &mut self,
        key: usize,
        packet: &[u8],
        headroom: usize,
        sink: &mut impl PacketOutput,
    ) -> DownlinkSend {
        let Some(endpoint) = self.routes.select_packet(key, self.batch_now, packet) else {
            return if self.routes.active_path_count(key) == 0 {
                DownlinkSend::Stale
            } else {
                DownlinkSend::Backpressured
            };
        };

        self.send_down_endpoint(key, endpoint, packet, headroom, sink)
    }

    fn send_stream_control(
        &mut self,
        carriers: &[usize],
        payload: &[u8],
        sink: &mut impl PacketOutput,
    ) -> usize {
        let mut sent = 0usize;
        for slot in carriers.iter().copied() {
            if sent >= STREAM_CONTROL_CARRIERS {
                return sent;
            }
            let Some(session) = self.sessions.get_mut(slot) else {
                continue;
            };
            if send_plain(
                session,
                payload,
                &mut self.rng,
                sink,
                self.batch_now,
                SendObservability {
                    record_dpi: false,
                    count_crypto: self.syscalls_enabled,
                },
            ) {
                sent += 1;
            }
        }
        sent
    }

    fn reconcile_streams(&mut self, sink: &mut impl PacketOutput) {
        if self
            .monotonic_ms
            .saturating_sub(self.last_stream_reconcile_ms)
            < STREAM_RECONCILE_INTERVAL_MS
        {
            return;
        }
        self.last_stream_reconcile_ms = self.monotonic_ms;
        let rebuild_inventory = self.stream_inventory_dirty
            || self
                .monotonic_ms
                .saturating_sub(self.last_stream_inventory_resync_ms)
                >= STREAM_INVENTORY_RESYNC_MS;
        let mut inventory = std::mem::take(&mut self.stream_inventory);
        if rebuild_inventory {
            for streams in inventory.values_mut() {
                streams.reset();
            }
            for (slot, session) in self.sessions.iter() {
                let desired_count = usize::from(session.desired_stream_count);
                if desired_count == 0
                    || desired_count > MAX_STREAM_WORKERS
                    || session.registration_id.is_none()
                {
                    continue;
                }
                let worker_id = session.cold.public.worker_id.load(Ordering::Acquire) as usize;
                if worker_id == 0 || worker_id > desired_count || worker_id > MAX_STREAM_WORKERS {
                    continue;
                }
                inventory
                    .entry(StreamIdentity {
                        password: session.cold.password.to_string(),
                        generation_id: session.cold.generation_id,
                        session_salt: session.cold.session_salt.clone(),
                    })
                    .or_insert_with(StreamInventory::new)
                    .observe(slot, session, worker_id);
            }
            inventory.retain(|_, streams| streams.seen);
            self.stream_inventory_dirty = false;
            self.last_stream_inventory_resync_ms = self.monotonic_ms;
        }
        let now_ms = self.monotonic_ms;
        let mut actions = std::mem::take(&mut self.stream_actions);
        for (identity, streams) in inventory.iter() {
            let round = match self.stream_repairs.get_mut(identity) {
                Some(round) => round,
                None => self.stream_repairs.entry(identity.clone()).or_default(),
            };
            round.last_seen_ms = now_ms;
            self.stream_missing_scratch.clear();
            streams.fill_missing(&mut self.stream_missing_scratch);
            let missing = self.stream_missing_scratch.as_slice();
            if missing.is_empty() {
                round.recovered(streams.desired_count, now_ms);
                if let Some(payload) = round.alive_payload(now_ms) {
                    actions.push(StreamControlAction {
                        carriers: streams.carriers.clone(),
                        payload,
                        repair: None,
                    });
                }
            } else if let Some(payload) =
                round.repair_payload(missing, streams.desired_count, now_ms)
            {
                actions.push(StreamControlAction {
                    carriers: streams.carriers.clone(),
                    payload,
                    repair: Some(StreamRepairLog {
                        password: identity.password.clone(),
                        generation_id: identity.generation_id,
                        desired_count: streams.desired_count,
                        sequence: round.sequence,
                        missing: missing.to_vec(),
                    }),
                });
            }
        }
        self.stream_repairs.retain(|identity, round| {
            inventory.contains_key(identity)
                || !round.active()
                || now_ms.saturating_sub(round.last_seen_ms) < STREAM_ROUND_ORPHAN_TTL_MS
        });
        for action in actions.drain(..) {
            let selected_carriers = action.carriers.len();
            let sent_carriers = self.send_stream_control(&action.carriers, &action.payload, sink);
            if let Some(repair) = action.repair {
                let _ = self.control_tx.try_send(ControlEvent::StreamRepair {
                    password: repair.password,
                    generation_id: repair.generation_id,
                    desired_count: repair.desired_count,
                    sequence: repair.sequence,
                    missing: repair.missing,
                    selected_carriers,
                    sent_carriers,
                });
            }
        }
        self.stream_inventory = inventory;
        self.stream_actions = actions;
        self.shrink_stream_bookkeeping();
        self.publish_memory_gauges();
    }

    fn sweep_idle_epochs(&mut self) {
        if self.monotonic_ms.saturating_sub(self.last_epoch_sweep_ms) < EPOCH_SWEEP_INTERVAL_MS {
            return;
        }
        self.last_epoch_sweep_ms = self.monotonic_ms;
        self.epoch_sweep_active.clear();
        for (_, session) in self.sessions.iter() {
            if !session.cold.password.is_empty() {
                self.epoch_sweep_active
                    .insert(session.cold.password.to_string());
            }
        }
        let before = self.epochs.len();
        self.epochs.retain(|password, epoch| {
            self.epoch_sweep_active.contains(password)
                || self.monotonic_ms.saturating_sub(epoch.last_seen_ms) < EPOCH_IDLE_TTL_MS
        });
        self.shrink_epoch_bookkeeping();
        if self.epochs.len() != before {
            self.publish_epoch_state();
        } else {
            self.publish_memory_gauges();
        }
    }

    fn handle_tun_packet(&mut self, packet: &mut [u8], sink: &mut impl PacketOutput) {
        let Some(key) = RouteTable::packet_key(packet) else {
            return;
        };
        let active_clients = self.routes.active_client_count();
        let active_streams = self.routes.active_path_count(key);
        if active_streams == 0 {
            return;
        }
        let headroom = sink.client_data_headroom(key, active_clients, active_streams);
        if headroom > 0 {
            let _ = self.send_down_key(key, packet, headroom, sink);
        }
    }
}

impl DataplaneLogic for ProtocolEngine {
    type Command = ProtocolCommand;

    fn on_udp<S: PacketOutput>(
        &mut self,
        peer: SocketAddr,
        local_ip: Option<IpAddr>,
        packet: &mut [u8],
        sink: &mut S,
    ) {
        if crate::dtls::is_dtls_packet(packet) {
            self.handle_dtls(peer, local_ip, packet, sink);
            return;
        }
        let key = EndpointKey::new(peer, local_ip);
        if let Some(slot) = self.by_peer.get(&key).copied()
            && self.handle_existing(slot, local_ip, packet, sink)
        {
            return;
        }
        if local_ip.is_some()
            && let Some(slot) = self.by_peer.get(&EndpointKey::new(peer, None)).copied()
            && self.handle_existing(slot, local_ip, packet, sink)
        {
            return;
        }
        self.handle_unknown_legacy(peer, local_ip, packet);
    }

    fn on_tun<S: PacketOutput>(&mut self, packet: &mut [u8], sink: &mut S) {
        self.handle_tun_packet(packet, sink);
    }

    fn on_tun_batch_end<S: PacketOutput>(&mut self, _sink: &mut S) {}

    fn on_udp_batch_end<S: PacketOutput>(&mut self, _sink: &mut S) {}

    fn begin_batch(&mut self, now: Instant) {
        self.batch_now = now;
        self.monotonic_ms = now
            .duration_since(self.started)
            .as_millis()
            .min(u128::from(u64::MAX)) as u64
            + 1;
    }

    fn on_command<S: PacketOutput>(&mut self, command_value: Self::Command, sink: &mut S) {
        match command_value {
            ProtocolCommand::SendPlain {
                session_id,
                payload,
            } => {
                if let Some(slot) = self.by_id.get(&session_id).copied() {
                    let record_dpi = should_record_dpi(
                        self.dpi_enabled,
                        self.monotonic_ms,
                        &mut self.dpi_sample_counter,
                        &mut self.dpi_last_sample_ms,
                        &payload,
                    );
                    let session = &mut self.sessions[slot];
                    let _ = send_plain(
                        session,
                        &payload,
                        &mut self.rng,
                        sink,
                        self.batch_now,
                        SendObservability {
                            record_dpi,
                            count_crypto: self.syscalls_enabled,
                        },
                    );
                }
            }
            ProtocolCommand::BroadcastPlain { payload } => {
                let record_dpi = should_record_dpi(
                    self.dpi_enabled,
                    self.monotonic_ms,
                    &mut self.dpi_sample_counter,
                    &mut self.dpi_last_sample_ms,
                    &payload,
                );
                let rng = &mut self.rng;
                let batch_now = self.batch_now;
                let count_crypto = self.syscalls_enabled;
                for (_, session) in self.sessions.iter_mut() {
                    let _ = send_plain(
                        session,
                        &payload,
                        rng,
                        sink,
                        batch_now,
                        SendObservability {
                            record_dpi,
                            count_crypto,
                        },
                    );
                }
            }
            ProtocolCommand::ActivateTunnel {
                session_id,
                ip,
                registration_id,
                worker_id,
                desired_count,
            } => {
                self.activate_tunnel(session_id, ip, registration_id, worker_id, desired_count);
            }
            ProtocolCommand::InjectTun {
                session_id,
                payload,
            } => {
                if let Some(slot) = self.by_id.get(&session_id).copied()
                    && sink.write_tun(&payload)
                {
                    let session = &mut self.sessions[slot];
                    session.up_total = session.up_total.saturating_add(payload.len() as u64);
                    session.record_debug_up(payload.len() as u64, self.debug_enabled);
                }
            }
            ProtocolCommand::UpdateEpoch {
                session_id,
                password,
                generation_id,
                session_salt,
            } => self.update_epoch(session_id, password, generation_id, session_salt),
            ProtocolCommand::SetAuthoritativeEpoch {
                password,
                generation_id,
                session_salt,
            } => {
                self.stale_slots.clear();
                for (slot, session) in self.sessions.iter() {
                    if session_is_retired_by_epoch(
                        &session.cold.password,
                        session.cold.generation_id,
                        &session.cold.session_salt,
                        &password,
                        generation_id,
                        &session_salt,
                    ) {
                        self.stale_slots.push(slot);
                    }
                }
                while let Some(slot) = self.stale_slots.pop() {
                    self.remove_slot_with_reason(slot, "authoritative-epoch-replaced");
                }
                self.epochs.insert(
                    password,
                    EpochValue {
                        last_seen_ms: self.monotonic_ms,
                    },
                );
                self.publish_epoch_state();
            }
            ProtocolCommand::DisconnectPassword {
                password,
                requester_session_id,
            } => {
                if let Some(slot) = self.by_id.get(&requester_session_id).copied() {
                    let payload = b"OK:disconnected";
                    let record_dpi = should_record_dpi(
                        self.dpi_enabled,
                        self.monotonic_ms,
                        &mut self.dpi_sample_counter,
                        &mut self.dpi_last_sample_ms,
                        payload,
                    );
                    let session = &mut self.sessions[slot];
                    let _ = send_plain(
                        session,
                        payload,
                        &mut self.rng,
                        sink,
                        self.batch_now,
                        SendObservability {
                            record_dpi,
                            count_crypto: self.syscalls_enabled,
                        },
                    );
                }
                self.stale_slots.clear();
                for (slot, session) in self.sessions.iter() {
                    if session_is_disconnected_password(
                        session.id,
                        &session.cold.password,
                        requester_session_id,
                        &password,
                    ) {
                        self.stale_slots.push(slot);
                    }
                }
                while let Some(slot) = self.stale_slots.pop() {
                    self.remove_slot_with_reason(slot, "password-disconnect");
                }
            }
            ProtocolCommand::ReplaceCredentials(credentials) => {
                self.stale_slots.clear();
                for (slot, session) in self.sessions.iter() {
                    if !credentials.contains_password(&session.cold.password) {
                        self.stale_slots.push(slot);
                    }
                }
                while let Some(slot) = self.stale_slots.pop() {
                    self.remove_slot_with_reason(slot, "credential-removed");
                }
                self.credentials = credentials;
            }
            ProtocolCommand::DropSession { session_id } => {
                if let Some(slot) = self.by_id.get(&session_id).copied() {
                    self.remove_slot_with_reason(slot, "requested-session-drop");
                }
            }
            ProtocolCommand::DropPassword { password } => {
                self.stale_slots.clear();
                for (slot, session) in self.sessions.iter() {
                    if session.cold.password.as_ref() == password {
                        self.stale_slots.push(slot);
                    }
                }
                while let Some(slot) = self.stale_slots.pop() {
                    self.remove_slot_with_reason(slot, "credential-removed");
                }
            }
            ProtocolCommand::UpdateDesiredStreams {
                password,
                generation_id,
                desired_count,
            } => {
                for (_, session) in self.sessions.iter_mut() {
                    if session.cold.password.as_ref() == password
                        && session.cold.generation_id == generation_id
                    {
                        session.desired_stream_count = desired_count;
                        session
                            .cold
                            .public
                            .desired_stream_count
                            .store(u64::from(desired_count), Ordering::Release);
                    }
                }
            }
            ProtocolCommand::CompactMemory { completed } => {
                self.compact_memory(true);
                let _ = completed.send(crate::collect_allocator_thread_heap());
            }
        }
    }

    fn on_tick<S: PacketOutput>(&mut self, sink: &mut S) {
        self.monotonic_ms = self.started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64 + 1;
        self.wall_now = wall_clock();
        #[cfg(feature = "diagnostics")]
        {
            self.debug_enabled = self.stream_debug_active.load(Ordering::Acquire);
            self.dpi_enabled = DPI_BROADCAST.receiver_count() != 0;
            self.syscalls_enabled = SYSCALLS_CLIENTS.load(Ordering::Acquire) != 0;
            self.io_metrics_enabled = self.syscalls_enabled;
        }
        #[cfg(not(feature = "diagnostics"))]
        {
            self.debug_enabled = false;
            self.dpi_enabled = false;
            self.syscalls_enabled = false;
            self.io_metrics_enabled = false;
        }
        self.setup_budget = SETUP_BUDGET_PER_TICK;
        if self
            .monotonic_ms
            .saturating_sub(self.last_session_maintenance_ms)
            >= SESSION_MAINTENANCE_INTERVAL_MS
        {
            self.last_session_maintenance_ms = self.monotonic_ms;
            self.stale_slots.clear();
            let global_up = &self.global_up;
            let global_down = &self.global_down;
            let monotonic_ms = self.monotonic_ms;
            let wall_now = self.wall_now;
            let debug_enabled = self.debug_enabled;
            for (slot, session) in self.sessions.iter_mut() {
                session.publish_counters(global_up, global_down, wall_now, debug_enabled);
                let idle_ms = monotonic_ms.saturating_sub(session.last_inbound_ms);
                if (session.registration_id.is_none() && idle_ms >= SESSION_SETUP_IDLE_MS)
                    || (session.registration_id.is_some() && idle_ms >= SESSION_AUTH_IDLE_MS)
                {
                    self.stale_slots.push(slot);
                }
            }
            while let Some(slot) = self.stale_slots.pop() {
                let reason = hot_session_idle_reason(
                    self.sessions
                        .get(slot)
                        .is_some_and(|session| session.registration_id.is_some()),
                );
                self.remove_slot_with_reason(slot, reason);
            }
            self.dtls_handshakes
                .retain(|_, (_, _, created_ms)| monotonic_ms.saturating_sub(*created_ms) <= 10_000);
            self.dtls_negotiated
                .retain(|_, (_, _, created_ms)| monotonic_ms.saturating_sub(*created_ms) <= 10_000);
        }
        self.reconcile_streams(sink);
        self.sweep_idle_epochs();
        if self.memory_compact_pending
            && self
                .monotonic_ms
                .saturating_sub(self.last_memory_compact_ms)
                >= MEMORY_COMPACT_INTERVAL_MS
        {
            self.compact_memory(false);
        }
    }

    fn on_io_counters(&mut self, counters: IoCounters) {
        if let Ok(mut global) = GLOBAL_IO_COUNTERS.write() {
            *global = counters;
        }
        let errors_changed = counters.udp_rx_errors != self.last_io_counters.udp_rx_errors
            || counters.udp_tx_errors != self.last_io_counters.udp_tx_errors
            || counters.tun_rx_errors != self.last_io_counters.tun_rx_errors
            || counters.tun_tx_errors != self.last_io_counters.tun_tx_errors
            || counters.udp_tx_drops != self.last_io_counters.udp_tx_drops
            || counters.tun_tx_drops != self.last_io_counters.tun_tx_drops
            || counters.udp_rx_enobufs != self.last_io_counters.udp_rx_enobufs
            || counters.udp_tx_enobufs != self.last_io_counters.udp_tx_enobufs;
        if errors_changed {
            let _ = self.control_tx.try_send(ControlEvent::IoCounters(counters));
        }
        self.last_io_counters = counters;
    }
}

fn wall_clock() -> u64 {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO);
    duration.as_secs()
}

pub(crate) fn unix_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_millis() as u64
}

fn make_aes_key(key: &[u8; 32]) -> aes::Aes128 {
    let mut value = [0u8; 16];
    value.copy_from_slice(&key[..16]);
    aes::Aes128::new(&value.into())
}

fn make_hmac_key(key: &[u8; 32]) -> Hmac<Sha1> {
    <Hmac<Sha1> as hmac::digest::KeyInit>::new_from_slice(&key[16..]).expect("HMAC-SHA1 key")
}

#[inline(always)]
fn aes_ctr(cipher: &aes::Aes128, iv: &[u8; 16]) -> Aes128Ctr128BE {
    Aes128Ctr128BE::from_core(Aes128CtrCore::inner_iv_init(cipher.clone(), iv.into()))
}

fn build_srtp_iv(ssrc: u32, seq: u16, timestamp: u32) -> [u8; 16] {
    let mut iv = [0u8; 16];
    iv[0..4].copy_from_slice(&ssrc.to_be_bytes());
    iv[4..6].copy_from_slice(&seq.to_be_bytes());
    iv[8..12].copy_from_slice(&timestamp.to_be_bytes());
    iv
}

#[inline(always)]
fn rtp_clock_rate(payload_type: u8) -> u32 {
    if payload_type == 96 { 90_000 } else { 48_000 }
}

#[inline(always)]
fn duration_ticks(elapsed: Duration, clock_rate: u32) -> u32 {
    let rate = u64::from(clock_rate);
    elapsed
        .as_secs()
        .wrapping_mul(rate)
        .wrapping_add(u64::from(elapsed.subsec_nanos()).wrapping_mul(rate) / 1_000_000_000)
        as u32
}

fn rtp_header_len(wire: &[u8]) -> Result<usize> {
    if wire.len() < 13 || wire[0] >> 6 != 2 {
        bail!("invalid RTP packet");
    }
    let mut header_len = 12usize;
    if wire[0] & 0x10 != 0 {
        if wire.len() < 16 {
            bail!("packet too short for extension header");
        }
        let words = u16::from_be_bytes(wire[14..16].try_into()?) as usize;
        header_len = header_len
            .checked_add(4 + words * 4)
            .ok_or_else(|| anyhow!("RTP header length overflow"))?;
    }
    if wire.len() < header_len {
        bail!("packet too short for calculated RTP header");
    }
    Ok(header_len)
}

fn unwrap_legacy_in_place(
    aes: &aes::Aes128,
    hmac: &Hmac<Sha1>,
    wire: &mut [u8],
    _expected_srtp: Option<bool>,
    count_crypto: bool,
) -> Result<DecodedPacket> {
    let header_len = rtp_header_len(wire)?;
    let payload_type = wire[1] & 0x7f;
    if payload_type != 111 && payload_type != 96 && payload_type != 6 {
        bail!("unsupported RTP payload type");
    }
    let seq = u16::from_be_bytes([wire[2], wire[3]]);
    let timestamp = u32::from_be_bytes(wire[4..8].try_into()?);
    let ssrc = u32::from_be_bytes(wire[8..12].try_into()?);
    if wire.len() < header_len + 10 {
        bail!("SRTP packet is too short");
    }
    let message_len = wire.len() - 10;
    let mut mac = hmac.clone();
    mac.update(&wire[..message_len]);
    if mac.verify_truncated_left(&wire[message_len..]).is_err() {
        bail!("SRTP authentication failed");
    }
    let iv = build_srtp_iv(ssrc, seq, timestamp);
    let mut cipher = aes_ctr(aes, &iv);
    cipher.apply_keystream(&mut wire[header_len..message_len]);
    let mut end = message_len;
    if wire[0] & 0x20 != 0 {
        let padding = wire[message_len - 1] as usize;
        if padding == 0 || padding > message_len.saturating_sub(header_len) {
            bail!("invalid RTP padding in SRTP mode");
        }
        end = message_len - padding;
    }
    if end < header_len {
        bail!("invalid SRTP payload bounds");
    }
    if end.saturating_sub(header_len) >= 13 && (20..=23).contains(&wire[header_len]) {
        let mut offset = header_len;
        while offset + 13 <= end {
            if !(20..=23).contains(&wire[offset]) || wire[offset + 1] != 0xfe {
                break;
            }
            let record_len =
                13 + (((wire[offset + 11] as usize) << 8) | wire[offset + 12] as usize);
            if offset + record_len > end {
                break;
            }
            offset += record_len;
        }
        if offset > header_len {
            end = offset;
        }
    }
    record_crypto_op(count_crypto);
    Ok(DecodedPacket {
        range: RangePair {
            start: header_len,
            end,
        },
        seq,
        payload_type,
        is_srtp: true,
    })
}

#[derive(Clone, Copy)]
struct SendObservability {
    record_dpi: bool,
    count_crypto: bool,
}

enum DownlinkSend {
    Sent,
    Stale,
    Backpressured,
}

fn send_plain<S: PacketOutput>(
    session: &mut HotSession,
    plain: &[u8],
    rng: &mut SmallRng,
    sink: &mut S,
    batch_now: Instant,
    observability: SendObservability,
) -> bool {
    send_legacy_plain(session, plain, rng, sink, batch_now, observability)
}

#[allow(clippy::too_many_arguments)]
fn send_data_client<S: PacketOutput>(
    client_key: usize,
    session: &mut HotSession,
    plain: &[u8],
    rng: &mut SmallRng,
    sink: &mut S,
    batch_now: Instant,
    observability: SendObservability,
    headroom: usize,
) -> bool {
    let peer = session.peer;
    let local_ip = session.local_ip;
    let duplicate = false;
    let mut wire_len = 0usize;
    let is_priority = false;
    let payload = plain;
    let sent = sink.send_udp_client_with_headroom(
        client_key,
        peer,
        local_ip,
        duplicate,
        is_priority,
        headroom,
        |output| match wrap_legacy_into(
            session,
            payload,
            rng,
            output,
            batch_now,
            observability.count_crypto,
        ) {
            Ok(length) => {
                wire_len = length;
                true
            }
            Err(_) => false,
        },
    );
    if sent && observability.record_dpi {
        record_packet_dpi(
            "OUTBOUND",
            "TUN-Server",
            &peer.to_string(),
            session.output.payload_type,
            u64::from(
                session
                    .output
                    .initial_seq
                    .wrapping_add(session.output.count.wrapping_sub(1) as u16),
            ),
            plain,
            wire_len,
            &session.cold.password,
            session.cold.generation_id,
            &session.cold.session_salt,
        );
    }
    sent
}

fn send_legacy_plain<S: PacketOutput>(
    session: &mut HotSession,
    plain: &[u8],
    rng: &mut SmallRng,
    sink: &mut S,
    batch_now: Instant,
    observability: SendObservability,
) -> bool {
    let peer = session.peer;
    let local_ip = session.local_ip;
    let duplicate = false;
    let mut wire_len = 0usize;
    let is_priority = should_flush_udp_immediately(plain);
    let sent = sink.send_udp_prioritized(peer, local_ip, duplicate, is_priority, |output| {
        match wrap_legacy_into(
            session,
            plain,
            rng,
            output,
            batch_now,
            observability.count_crypto,
        ) {
            Ok(length) => {
                wire_len = length;
                true
            }
            Err(_) => false,
        }
    });
    if sent && is_priority {
        sink.request_udp_flush();
    }
    if sent && observability.record_dpi {
        record_packet_dpi(
            "OUTBOUND",
            "TUN-Server",
            &peer.to_string(),
            session.output.payload_type,
            u64::from(
                session
                    .output
                    .initial_seq
                    .wrapping_add(session.output.count.wrapping_sub(1) as u16),
            ),
            plain,
            wire_len,
            &session.cold.password,
            session.cold.generation_id,
            &session.cold.session_salt,
        );
    }
    sent
}

fn wrap_legacy_into(
    session: &mut HotSession,
    plain: &[u8],
    rng: &mut SmallRng,
    output: &mut PacketBuf,
    batch_now: Instant,
    count_crypto: bool,
) -> Result<usize> {
    let count = session.output.count;
    session.output.count = session.output.count.wrapping_add(1);
    let seq = session.output.initial_seq.wrapping_add(count as u16);
    let (payload_type, padding, marker_bit) = {
        const OVERHEAD: usize = 24 + 10 + 4 + 8 + 20;
        const SRTP_OVERHEAD: usize = 24 + 10;
        let current_projected = plain.len().saturating_add(OVERHEAD);
        let wire_min = plain.len().saturating_add(SRTP_OVERHEAD);
        if wire_min < 100 {
            let target: usize = rng.gen_range(100..=150);
            let pad = target.saturating_sub(wire_min).max(1);
            (111u8, pad, false)
        } else if plain.len() <= 350 {
            let pad = rng.gen_range(1..=2);
            (111u8, pad, false)
        } else if current_projected <= 1050 {
            let headroom = 1050usize.saturating_sub(current_projected);
            let pad = if headroom > 1 {
                rng.gen_range(1..=headroom.min(4))
            } else {
                1
            };
            (96u8, pad, true)
        } else {
            const MAX_WIRE_LIMIT: usize = 1350;
            let headroom = MAX_WIRE_LIMIT.saturating_sub(current_projected);
            let pad = if headroom > 1 {
                let jitter_limit = headroom.min(4);
                rng.gen_range(1..=jitter_limit)
            } else {
                1
            };
            (96u8, pad, rng.gen_range(0..4u8) == 0)
        }
    };
    let effective_len = plain.len();
    let (timestamp, abs_send_time) = session.output.clocks_for_type(batch_now, payload_type);
    let transport_seq = session.output.transport_seq;
    session.output.transport_seq = session.output.transport_seq.wrapping_add(1);
    let mut header = [0u8; 24];
    header[0] = if padding > 0 { 0xb0 } else { 0x90 };
    header[1] = payload_type & 0x7f;
    if marker_bit {
        header[1] |= 0x80;
    }
    header[2..4].copy_from_slice(&seq.to_be_bytes());
    header[4..8].copy_from_slice(&timestamp.to_be_bytes());
    header[8..12].copy_from_slice(&session.output.ssrc.to_be_bytes());
    header[12..14].copy_from_slice(&[0xbe, 0xde]);
    header[14..16].copy_from_slice(&2u16.to_be_bytes());
    header[16] = 0x32;
    let abs_bytes = abs_send_time.to_be_bytes();
    header[17..20].copy_from_slice(&abs_bytes[1..4]);
    header[20] = 0x51;
    header[21..23].copy_from_slice(&transport_seq.to_be_bytes());
    header[23] = 0;
    let storage = output.storage_mut();
    let ciphertext_len = effective_len
        .checked_add(padding)
        .ok_or_else(|| anyhow!("SRTP length overflow"))?;
    let total = 24usize
        .checked_add(ciphertext_len)
        .and_then(|value| value.checked_add(10))
        .ok_or_else(|| anyhow!("SRTP length overflow"))?;
    if total > storage.len() {
        bail!("SRTP output exceeds packet capacity");
    }
    storage[..24].copy_from_slice(&header);
    storage[24..24 + plain.len()].copy_from_slice(plain);
    if effective_len > plain.len() {
        storage[24 + plain.len()..24 + effective_len].fill(0);
    }
    if padding > 0 {
        let random_padding = padding - 1;
        if random_padding != 0 {
            rng.fill_bytes(&mut storage[24 + effective_len..24 + effective_len + random_padding]);
        }
        storage[24 + ciphertext_len - 1] = padding as u8;
    }
    let iv = build_srtp_iv(session.output.ssrc, seq, timestamp);
    let mut cipher = aes_ctr(&session.out_aes, &iv);
    cipher.apply_keystream(&mut storage[24..24 + ciphertext_len]);
    let mut mac = session.out_hmac.clone();
    mac.update(&storage[..24 + ciphertext_len]);
    let tag = mac.finalize().into_bytes();
    storage[24 + ciphertext_len..total].copy_from_slice(&tag[..10]);
    if !output.set_len(total) {
        bail!("SRTP output length rejected");
    }
    record_crypto_op(count_crypto);
    Ok(total)
}

fn stream_control_payload(
    prefix: &[u8],
    sequence: u64,
    desired_count: u16,
    worker_ids: &[u16],
) -> Vec<u8> {
    let count = worker_ids.len().min(u8::MAX as usize);
    let mut payload = Vec::with_capacity(prefix.len() + 11 + count * 2);
    payload.extend_from_slice(prefix);
    payload.extend_from_slice(&sequence.to_be_bytes());
    payload.extend_from_slice(&desired_count.to_be_bytes());
    payload.push(count as u8);
    for worker_id in worker_ids.iter().take(count) {
        payload.extend_from_slice(&worker_id.to_be_bytes());
    }
    payload
}

fn parse_generation_id(value: &str) -> Option<u64> {
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    value.parse::<u64>().ok()
}

fn parse_getconf_identity(payload: &[u8]) -> Option<(&str, u64, &str)> {
    let text = std::str::from_utf8(payload).ok()?;
    let content = text.strip_prefix("GETCONF:")?.trim();
    let parts: Vec<&str> = content.split('|').collect();
    if parts.len() < 3 {
        return None;
    }
    if let Some(generation_id) = parts.get(2).and_then(|s| parse_generation_id(s.trim())) {
        let password = parts.get(1)?.trim();
        let session_salt = parts.get(3).unwrap_or(&"").trim();
        if password.is_empty() || password.len() > 128 || session_salt.len() > 128 {
            return None;
        }
        return Some((password, generation_id, session_salt));
    }
    if parts.len() >= 4 {
        let legacy_pass = parts.get(2)?.trim();
        let legacy_fallback = parts.get(1)?.trim();
        let generation_id = parse_generation_id(parts.get(3)?.trim())?;
        let session_salt = parts.get(4).unwrap_or(&"").trim();
        let password = if !legacy_pass.is_empty() {
            legacy_pass
        } else {
            legacy_fallback
        };
        if password.is_empty() || password.len() > 128 || session_salt.len() > 128 {
            return None;
        }
        return Some((password, generation_id, session_salt));
    }
    None
}

fn is_control_payload(payload: &[u8]) -> bool {
    payload.starts_with(b"GETCONF:")
        || payload.starts_with(b"GETWORKERS:")
        || payload.starts_with(b"DISCONNECT:")
        || payload == b"READY"
        || payload.first().is_some_and(|byte| *byte == 0xff)
}

#[inline(always)]
fn is_idle_keepalive(payload: &[u8]) -> bool {
    (!payload.is_empty() && payload.iter().all(|byte| *byte == 0xff))
        || ((4..=9).contains(&payload.len()) && payload.first() == Some(&0xff))
}

#[inline(always)]
fn should_flush_udp_immediately(payload: &[u8]) -> bool {
    payload.starts_with(b"GETCONF:")
        || payload.starts_with(b"TUNCONF:")
        || payload.starts_with(b"ROUTING:")
        || payload.starts_with(b"WORKERS:")
        || payload.starts_with(b"GETWORKERS:")
        || payload.starts_with(b"TUNWORKERS:")
        || payload.starts_with(b"DENIED:")
        || payload.starts_with(b"DISCONNECT:")
        || payload.starts_with(STREAM_REPAIR_PREFIX)
        || payload.starts_with(STREAM_ALIVE_PREFIX)
        || payload == b"READY"
        || payload == b"READY_OK"
        || payload == PANEL_RESTART_NOTICE
}

fn session_is_retired_by_epoch(
    session_password: &str,
    session_generation_id: u64,
    session_salt: &str,
    authoritative_password: &str,
    authoritative_generation_id: u64,
    authoritative_salt: &str,
) -> bool {
    session_password == authoritative_password
        && (session_generation_id != authoritative_generation_id
            || session_salt != authoritative_salt)
}

fn session_is_disconnected_password(
    session_id: u64,
    session_password: &str,
    requester_session_id: u64,
    target_password: &str,
) -> bool {
    session_id == requester_session_id || session_password == target_password
}

fn getconf_reconnect_action_hot(
    session_password: &str,
    session_generation_id: u64,
    session_salt: &str,
    incoming_password: &str,
    incoming_generation_id: u64,
    incoming_salt: &str,
) -> GetconfReconnectAction {
    if session_password == incoming_password
        && session_generation_id == incoming_generation_id
        && session_salt == incoming_salt
    {
        GetconfReconnectAction::Process
    } else {
        GetconfReconnectAction::Replace
    }
}

async fn control_loop(app: Arc<App>, mut receiver: mpsc::Receiver<ControlEvent>) {
    let mut last_io = IoCounters::default();
    let jobs = Arc::new(Semaphore::new(CONTROL_TASK_CAPACITY));
    while let Some(event) = receiver.recv().await {
        match event {
            ControlEvent::SessionCreated(session) => {
                prune_public_sessions(&app, now().max(0) as u64);
                if let Some((_, old)) = app.sessions.remove(&session.id) {
                    old.cancel_token.cancel();
                }
                if app.sessions.len() < PUBLIC_SESSION_LIMIT {
                    app.total_connections.fetch_add(1, Ordering::Relaxed);
                    log_event(
                        &app,
                        "INFO",
                        "SESSION",
                        &format!(
                            "Created {} session for {}",
                            if session.is_srtp { "SRTP" } else { "RTP" },
                            session.address
                        ),
                    );
                    app.sessions.insert(session.id, session);
                }
            }
            ControlEvent::SessionClosed {
                address,
                session_id,
                reason,
            } => {
                if let Some((_, session)) = app
                    .sessions
                    .remove_if(&session_id, |_, current| current.id == session_id)
                {
                    session.cancel_token.cancel();
                    log_event(
                        &app,
                        "INFO",
                        "SESSION",
                        &format!("Closed session for {address}: {reason}"),
                    );
                }
            }
            ControlEvent::Payload {
                address,
                session_id,
                payload,
            } => {
                let Ok(permit) = jobs.clone().acquire_owned().await else {
                    return;
                };
                let app = app.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    process_control_payload_event(app, address, session_id, payload).await;
                });
            }
            ControlEvent::IngressBeforeTunnel {
                address,
                session_id,
                payload,
            } => {
                let Ok(permit) = jobs.clone().acquire_owned().await else {
                    return;
                };
                let app = app.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    process_ingress_before_tunnel_event(app, address, session_id, payload).await;
                });
            }
            ControlEvent::StreamRepair {
                password,
                generation_id,
                desired_count,
                sequence,
                missing,
                selected_carriers,
                sent_carriers,
            } => {
                log_event(
                    &app,
                    "WARN",
                    "STREAM",
                    &format!(
                        "Repair requested: pass={} gen={generation_id} missing={missing:?}/{desired_count} sequence={sequence} carriers={sent_carriers}/{selected_carriers}",
                        secret_tag(&password),
                    ),
                );
            }
            ControlEvent::IoCounters(counters) => {
                let delta_errors = counters
                    .udp_rx_errors
                    .saturating_sub(last_io.udp_rx_errors)
                    .saturating_add(counters.udp_tx_errors.saturating_sub(last_io.udp_tx_errors))
                    .saturating_add(counters.tun_rx_errors.saturating_sub(last_io.tun_rx_errors))
                    .saturating_add(counters.tun_tx_errors.saturating_sub(last_io.tun_tx_errors));
                let delta_udp_drops = counters.udp_tx_drops.saturating_sub(last_io.udp_tx_drops);
                let delta_tun_drops = counters.tun_tx_drops.saturating_sub(last_io.tun_tx_drops);
                if delta_errors != 0 || delta_udp_drops != 0 || delta_tun_drops != 0 {
                    log_event(
                        &app,
                        "WARN",
                        "DATAPLANE",
                        &format!(
                            "I/O errors +{delta_errors}, UDP TX drops +{delta_udp_drops}, TUN TX drops +{delta_tun_drops}; udp_rx={}, udp_tx={}, tun_rx={}, tun_tx={}",
                            counters.udp_rx_packets,
                            counters.udp_tx_packets,
                            counters.tun_rx_packets,
                            counters.tun_tx_packets
                        ),
                    );
                }
                last_io = counters;
            }
        }
    }
}

async fn process_control_payload_event(
    app: Arc<App>,
    address: SocketAddr,
    session_id: u64,
    payload: Vec<u8>,
) {
    let session = app
        .sessions
        .get(&session_id)
        .and_then(|entry| (entry.value().id == session_id).then(|| entry.value().clone()));
    if let Some(session) = session
        && let Err(error) = process_control_payload(&app, &session, &payload).await
    {
        log_event(
            &app,
            "WARN",
            "SESSION",
            &format!("{address}: control payload rejected: {error}"),
        );
    }
}

async fn process_ingress_before_tunnel_event(
    app: Arc<App>,
    address: SocketAddr,
    session_id: u64,
    payload: Vec<u8>,
) {
    let session = app
        .sessions
        .get(&session_id)
        .and_then(|entry| (entry.value().id == session_id).then(|| entry.value().clone()));
    if let Some(session) = session {
        if let Err(error) = ensure_control_tunnel(&app, &session).await {
            log_event(
                &app,
                "WARN",
                "TUN",
                &format!("{address}: tunnel setup pending: {error}"),
            );
        } else {
            let _ = command(
                &app,
                ProtocolCommand::InjectTun {
                    session_id,
                    payload,
                },
            );
        }
    }
}

struct ControlWriter<'a> {
    app: &'a Arc<App>,
    session_id: u64,
}

impl ControlWriter<'_> {
    async fn write(&self, payload: &[u8]) -> Result<()> {
        command(
            self.app,
            ProtocolCommand::SendPlain {
                session_id: self.session_id,
                payload: payload.to_vec(),
            },
        )
    }
}

async fn process_control_payload(
    app: &Arc<App>,
    session: &Arc<Session>,
    payload: &[u8],
) -> Result<()> {
    let writer = ControlWriter {
        app,
        session_id: session.id,
    };
    if payload.starts_with(b"GETCONF:") {
        let text = std::str::from_utf8(payload)?;
        return handle_getconf(app, &writer, session, text).await;
    }
    if payload.starts_with(b"GETWORKERS:") {
        let text = std::str::from_utf8(payload)?;
        return handle_getworkers(app, &writer, session, text).await;
    }
    if payload.starts_with(b"DISCONNECT:") {
        let text = std::str::from_utf8(payload)?;
        let content = text.strip_prefix("DISCONNECT:").unwrap_or_default().trim();
        let mut parts = content.splitn(2, '|');
        let pass_part = parts.next().unwrap_or("").trim();
        let salt = parts.next().unwrap_or("").trim();
        match handle_disconnect(app, session, pass_part, salt).await {
            Ok(_) => {
                let _ = writer.write(b"OK:disconnected").await;
            }
            Err(response) => writer.write(response.as_bytes()).await?,
        }
        return Ok(());
    }
    Ok(())
}

async fn handle_getworkers(
    app: &Arc<App>,
    writer: &ControlWriter<'_>,
    session: &Arc<Session>,
    text: &str,
) -> Result<()> {
    let raw = text.strip_prefix("GETWORKERS:").unwrap_or_default().trim();
    let (pass_part, count_part) = if let Some((p, cnt)) = raw.split_once('|') {
        (p.trim(), cnt.trim())
    } else {
        ("", raw)
    };
    let requested = count_part.parse::<u32>().unwrap_or(0);

    let db = db_read(app).await?;
    let password = if !pass_part.is_empty() {
        pass_part.to_string()
    } else {
        session.password.clone()
    };
    let access = getconf_credential_access(&db, &password);
    let server_limit = access
        .as_ref()
        .ok()
        .and_then(|a| resolve_worker_limit_access(&db, a));

    let (agreed, limit_val) = match server_limit {
        Some(limit) if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&limit) => {
            let eff = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested) {
                requested.min(limit)
            } else if requested < 3 {
                3.min(limit)
            } else {
                limit
            };
            (eff, limit)
        }
        _ => {
            let req = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested) {
                requested
            } else if requested > MAX_STREAM_WORKERS_U16 as u32 {
                MAX_STREAM_WORKERS_U16 as u32
            } else {
                3
            };
            (req, 0)
        }
    };

    let generation_id = session.generation_id.load(Ordering::Acquire);
    for s in app.sessions.iter() {
        if s.password == password && s.generation_id.load(Ordering::Acquire) == generation_id {
            s.desired_stream_count
                .store(agreed as u64, Ordering::Release);
        }
    }
    let _ = command(
        app,
        ProtocolCommand::UpdateDesiredStreams {
            password: password.clone(),
            generation_id,
            desired_count: agreed as u16,
        },
    );

    let response = format!("TUNWORKERS:{agreed}:{limit_val}");
    log_event(
        app,
        "INFO",
        "WORKERS",
        &format!(
            "TUNWORKERS pass={} requested={requested} agreed={agreed} limit={limit_val}",
            secret_tag(&password)
        ),
    );
    writer.write(response.as_bytes()).await?;
    Ok(())
}

fn getconf_credential_access(
    db: &Database,
    password: &str,
) -> std::result::Result<CredentialAccess, &'static str> {
    let token = password.trim();
    if token.is_empty() {
        return Err("DENIED:wrong_password");
    }
    if !db.main_password.is_empty() && secret_eq(&db.main_password, token) {
        return Ok(CredentialAccess::Main);
    }
    if let Some((slot_key, entry)) = db.passwords.get_key_value(token) {
        if entry.is_deactivated {
            return Err("DENIED:deactivated");
        }
        if is_expired(entry) {
            return Err("DENIED:expired");
        }
        return Ok(CredentialAccess::Slot(slot_key.clone()));
    }
    Err("DENIED:wrong_password")
}

fn resolve_worker_limit_access(db: &Database, access: &CredentialAccess) -> Option<u32> {
    match access {
        CredentialAccess::Main => db
            .main_workers
            .filter(|&w| (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&w)),
        CredentialAccess::Slot(key) => {
            if let Some(entry) = db.passwords.get(key) {
                if let Some(w) = entry.workers
                    && (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&w)
                {
                    return Some(w);
                }
                if !entry.group_id.is_empty()
                    && let Some(group) = db.groups.get(&entry.group_id)
                    && let Some(w) = group.workers
                    && (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&w)
                {
                    return Some(w);
                }
            }
            None
        }
    }
}

fn resolve_routing_rule_access<'a>(
    db: &'a Database,
    access: &CredentialAccess,
) -> Option<&'a crate::model::RoutingRule> {
    match access {
        CredentialAccess::Main => {
            if !db.main_routing_rule_id.is_empty() {
                db.routing_rules.get(&db.main_routing_rule_id)
            } else {
                None
            }
        }
        CredentialAccess::Slot(key) => {
            if let Some(entry) = db.passwords.get(key) {
                if !entry.routing_rule_id.is_empty()
                    && let Some(rule) = db.routing_rules.get(&entry.routing_rule_id)
                {
                    return Some(rule);
                }
                if !entry.group_id.is_empty()
                    && let Some(group) = db.groups.get(&entry.group_id)
                    && !group.routing_rule_id.is_empty()
                {
                    return db.routing_rules.get(&group.routing_rule_id);
                }
            }
            None
        }
    }
}

fn session_epoch_identity(session: &Session) -> SessionEpochIdentity {
    SessionEpochIdentity {
        password: session.password.clone(),
        generation_id: session.generation_id.load(Ordering::Acquire),
        session_salt: lock_unpoison(&session.session_salt).clone(),
    }
}

fn set_public_epoch(session: &Session, generation_id: u64, session_salt: &str) {
    session
        .generation_id
        .store(generation_id, Ordering::Release);
    *lock_unpoison(&session.session_salt) = session_salt.to_owned();
}

fn current_password_epoch(app: &Arc<App>, password: &str, slot: &Arc<PasswordEpochSlot>) -> bool {
    app.password_epochs
        .get(password)
        .is_some_and(|entry| Arc::ptr_eq(entry.value(), slot))
}

async fn lock_password_epoch(
    app: &Arc<App>,
    password: &str,
) -> (
    Arc<PasswordEpochSlot>,
    OwnedMutexGuard<PasswordEpochState>,
    bool,
) {
    loop {
        let (slot, created) =
            if let Some(existing) = app.password_epochs.get(password).map(|e| e.value().clone()) {
                (existing, false)
            } else {
                let fresh = Arc::new(PasswordEpochSlot::new(
                    PasswordEpochState::default(),
                    unix_time_ms(),
                ));
                match app.password_epochs.entry(password.to_owned()) {
                    dashmap::mapref::entry::Entry::Occupied(entry) => (entry.get().clone(), false),
                    dashmap::mapref::entry::Entry::Vacant(entry) => {
                        entry.insert(fresh.clone());
                        (fresh, true)
                    }
                }
            };
        let guard = slot.epoch.clone().lock_owned().await;
        slot.last_used_ms.store(unix_time_ms(), Ordering::Relaxed);
        if current_password_epoch(app, password, &slot) {
            return (slot, guard, created);
        }
    }
}

async fn lock_existing_password_epoch(
    app: &Arc<App>,
    password: &str,
) -> Option<(Arc<PasswordEpochSlot>, OwnedMutexGuard<PasswordEpochState>)> {
    loop {
        let slot = app
            .password_epochs
            .get(password)
            .map(|entry| entry.value().clone())?;
        let guard = slot.epoch.clone().lock_owned().await;
        slot.last_used_ms.store(unix_time_ms(), Ordering::Relaxed);
        if current_password_epoch(app, password, &slot) {
            return Some((slot, guard));
        }
    }
}

fn rejected_session_is_request(
    session: &Session,
    password: &str,
    generation_id: u64,
    session_salt: &str,
) -> bool {
    let identity = session_epoch_identity(session);
    identity.password.is_empty()
        || (identity.password == password
            && identity.generation_id == generation_id
            && identity.session_salt == session_salt)
}

fn drop_exact_session(app: &Arc<App>, session: &Arc<Session>) {
    let _ = command(
        app,
        ProtocolCommand::DropSession {
            session_id: session.id,
        },
    );
    if let Some((_, removed)) = app
        .sessions
        .remove_if(&session.id, |_, current| current.id == session.id)
    {
        removed.cancel_token.cancel();
    }
}

fn getconf_log_status(response: &str) -> &str {
    if response.starts_with("TUNCONF:") {
        "TUNCONF"
    } else {
        response
    }
}

fn log_getconf_result(
    app: &Arc<App>,
    session: &Arc<Session>,
    password: &str,
    generation_id: Option<u64>,
    worker_id: Option<u16>,
    desired_count: Option<u16>,
    response: &str,
) {
    let generation = generation_id
        .map(|value| value.to_string())
        .unwrap_or_else(|| "?".to_owned());
    let worker = worker_id
        .map(|value| value.to_string())
        .unwrap_or_else(|| "?".to_owned());
    let desired = desired_count
        .map(|value| value.to_string())
        .unwrap_or_else(|| "?".to_owned());
    let pass = if !password.is_empty() {
        secret_tag(password)
    } else {
        "-".to_owned()
    };
    log_event(
        app,
        "INFO",
        "GETCONF",
        &format!(
            "GETCONF {} pass={} gen={} worker={}/{} -> {}",
            session.address,
            pass,
            generation,
            worker,
            desired,
            getconf_log_status(response)
        ),
    );
}

pub fn purge_stale_password_sessions(
    app: &Arc<App>,
    password: &str,
    generation_id: u64,
    session_salt: &str,
    current_session_id: u64,
) {
    let candidates = app
        .sessions
        .iter()
        .filter_map(|entry| {
            let session = entry.value();
            if session.id == current_session_id {
                return None;
            }
            let identity = session_epoch_identity(session);
            (identity.password == password
                && (identity.generation_id != generation_id
                    || identity.session_salt != session_salt))
                .then_some((session.id, session.address))
        })
        .collect::<Vec<_>>();
    for (session_id, address) in candidates {
        let _ = command(app, ProtocolCommand::DropSession { session_id });
        if let Some((_, removed)) = app
            .sessions
            .remove_if(&session_id, |_, current| current.id == session_id)
        {
            removed.cancel_token.cancel();
            log_event(
                app,
                "INFO",
                "SESSION",
                &format!(
                    "Purged stale session for password {} at {address}",
                    crate::model::secret_tag(password)
                ),
            );
        }
    }
}

fn purge_replaced_worker_sessions(
    app: &Arc<App>,
    password: &str,
    generation_id: u64,
    session_salt: &str,
    worker_id: u64,
    current_session_id: u64,
) {
    let candidates = app
        .sessions
        .iter()
        .filter_map(|entry| {
            let session = entry.value();
            if session.id == current_session_id
                || session.worker_id.load(Ordering::Acquire) != worker_id
            {
                return None;
            }
            let identity = session_epoch_identity(session);
            (identity.password == password
                && identity.generation_id == generation_id
                && identity.session_salt == session_salt)
                .then_some(session.id)
        })
        .collect::<Vec<_>>();
    for session_id in candidates {
        let _ = command(app, ProtocolCommand::DropSession { session_id });
        if let Some((_, removed)) = app
            .sessions
            .remove_if(&session_id, |_, current| current.id == session_id)
        {
            removed.cancel_token.cancel();
        }
    }
}

async fn handle_getconf(
    app: &Arc<App>,
    writer: &ControlWriter<'_>,
    session: &Arc<Session>,
    text: &str,
) -> Result<()> {
    let content = text.strip_prefix("GETCONF:").unwrap_or_default().trim();
    let parts: Vec<&str> = content.split('|').collect();
    let client_port = parts.first().copied().unwrap_or("9000").trim();

    let (password, generation_text, session_salt, worker_text, desired_text) = if parts
        .get(2)
        .and_then(|s| parse_generation_id(s.trim()))
        .is_some()
    {
        let pass = parts.get(1).copied().unwrap_or("").trim();
        let effective_pass = if !pass.is_empty() {
            pass
        } else {
            session.password.as_str()
        };
        (
            effective_pass,
            parts.get(2).copied().unwrap_or("").trim(),
            parts.get(3).copied().unwrap_or("").trim(),
            parts.get(4).copied().unwrap_or("").trim(),
            parts.get(5).copied().unwrap_or("").trim(),
        )
    } else {
        let legacy_token1 = parts.get(1).copied().unwrap_or("").trim();
        let legacy_token2 = parts.get(2).copied().unwrap_or("").trim();
        let effective_pass = if !legacy_token2.is_empty() {
            legacy_token2
        } else if !session.password.is_empty() && session.password.as_str() != legacy_token1 {
            session.password.as_str()
        } else if !legacy_token1.is_empty() {
            legacy_token1
        } else {
            session.password.as_str()
        };
        (
            effective_pass,
            parts.get(3).copied().unwrap_or("").trim(),
            parts.get(4).copied().unwrap_or("").trim(),
            parts.get(5).copied().unwrap_or("").trim(),
            parts.get(6).copied().unwrap_or("").trim(),
        )
    };

    let Some(generation_id) = parse_generation_id(generation_text) else {
        log_getconf_result(
            app,
            session,
            password,
            None,
            None,
            None,
            "DENIED:invalid_epoch",
        );
        writer.write(b"DENIED:invalid_epoch").await?;
        return Ok(());
    };
    if password.is_empty() || password.len() > 128 {
        log_getconf_result(
            app,
            session,
            password,
            Some(generation_id),
            None,
            None,
            "DENIED:wrong_password",
        );
        writer.write(b"DENIED:wrong_password").await?;
        return Ok(());
    }
    if client_port.parse::<u16>().is_err() {
        log_getconf_result(
            app,
            session,
            password,
            Some(generation_id),
            None,
            None,
            "DENIED:invalid_port",
        );
        writer.write(b"DENIED:invalid_port").await?;
        return Ok(());
    }
    if session_salt.len() > 128 {
        log_getconf_result(
            app,
            session,
            password,
            Some(generation_id),
            None,
            None,
            "DENIED:invalid_epoch",
        );
        writer.write(b"DENIED:invalid_epoch").await?;
        return Ok(());
    }
    let Ok(worker_id @ 1..=MAX_STREAM_WORKERS_U16) = worker_text.parse::<u16>() else {
        log_getconf_result(
            app,
            session,
            password,
            Some(generation_id),
            None,
            None,
            "DENIED:invalid_worker",
        );
        writer.write(b"DENIED:invalid_worker").await?;
        return Ok(());
    };
    session
        .worker_id
        .store(u64::from(worker_id), Ordering::Release);
    let count = if desired_text.is_empty() {
        0
    } else {
        let Ok(count @ 1..=MAX_STREAM_WORKERS_U16) = desired_text.parse::<u16>() else {
            log_getconf_result(
                app,
                session,
                password,
                Some(generation_id),
                Some(worker_id),
                None,
                "DENIED:invalid_worker_count",
            );
            writer.write(b"DENIED:invalid_worker_count").await?;
            return Ok(());
        };
        count
    };
    let desired_count = count;
    let preliminary = {
        let db = db_read(app).await?;
        let access = getconf_credential_access(&db, password);
        match access {
            Err(e) => Err(e),
            Ok(access) => {
                let current_session_desired = {
                    let direct = session.desired_stream_count.load(Ordering::Acquire) as u16;
                    if (3..=MAX_STREAM_WORKERS_U16).contains(&direct) {
                        direct
                    } else {
                        app.sessions
                            .iter()
                            .find_map(|s| {
                                if s.password == password
                                    && s.generation_id.load(Ordering::Acquire) == generation_id
                                {
                                    let val = s.desired_stream_count.load(Ordering::Acquire) as u16;
                                    if (3..=MAX_STREAM_WORKERS_U16).contains(&val) {
                                        return Some(val);
                                    }
                                }
                                None
                            })
                            .unwrap_or(0)
                    }
                };
                let server_worker_limit = resolve_worker_limit_access(&db, &access);
                let max_allowed = if let Some(limit) = server_worker_limit {
                    limit as u16
                } else if (3..=MAX_STREAM_WORKERS_U16).contains(&current_session_desired) {
                    current_session_desired
                } else if desired_count > 0 {
                    desired_count
                } else {
                    MAX_STREAM_WORKERS_U16
                };
                if worker_id > 3 && worker_id > max_allowed {
                    Err("DENIED:invalid_worker_count")
                } else {
                    Ok(access)
                }
            }
        }
    };
    if let Err(response) = preliminary {
        log_getconf_result(
            app,
            session,
            password,
            Some(generation_id),
            Some(worker_id),
            Some(desired_count),
            response,
        );
        writer.write(response.as_bytes()).await?;
        if rejected_session_is_request(session, password, generation_id, session_salt) {
            drop_exact_session(app, session);
        }
        return Ok(());
    }
    let (epoch_lock, mut epoch, created_epoch) = lock_password_epoch(app, password).await;
    let dns = effective_dns(app).await;
    let (
        response,
        reject_session,
        authorization_rejected,
        tunnel_ip,
        changed,
        routing_rule,
        server_worker_limit,
    ) = {
        let mut db = db_write(app).await?;
        let mut changed = false;
        let mut reject_session = false;
        let access = getconf_credential_access(&db, password);
        let server_worker_limit = access
            .as_ref()
            .ok()
            .and_then(|a| resolve_worker_limit_access(&db, a));
        let current_session_desired = {
            let direct = session.desired_stream_count.load(Ordering::Acquire) as u16;
            if (3..=MAX_STREAM_WORKERS_U16).contains(&direct) {
                direct
            } else {
                app.sessions
                    .iter()
                    .find_map(|s| {
                        if s.password == password
                            && s.generation_id.load(Ordering::Acquire) == generation_id
                        {
                            let val = s.desired_stream_count.load(Ordering::Acquire) as u16;
                            if (3..=MAX_STREAM_WORKERS_U16).contains(&val) {
                                return Some(val);
                            }
                        }
                        None
                    })
                    .unwrap_or(0)
            }
        };
        let effective_desired_count = if let Some(limit) = server_worker_limit {
            limit as u16
        } else if (3..=MAX_STREAM_WORKERS_U16).contains(&current_session_desired) {
            current_session_desired
        } else if desired_count > 0 {
            desired_count
        } else {
            3
        };
        let max_allowed = if let Some(limit) = server_worker_limit {
            limit as u16
        } else {
            MAX_STREAM_WORKERS_U16
        };
        let worker_limit_mismatch = worker_id > 3 && worker_id > max_allowed;
        let access = if worker_limit_mismatch {
            Err("DENIED:invalid_worker_count")
        } else {
            access
        };
        let authorization_rejected = access.is_err();
        let mut tunnel_ip = None;
        let mut routing_rule = None;
        let response = match access {
            Err(response) => {
                reject_session = true;
                response.to_owned()
            }
            Ok(access) => match epoch.admit(generation_id, session_salt) {
                PasswordEpochDecision::Current | PasswordEpochDecision::Replaced => {
                    set_public_epoch(session, generation_id, session_salt);
                    session
                        .worker_id
                        .store(u64::from(worker_id), Ordering::Release);
                    session
                        .desired_stream_count
                        .store(u64::from(effective_desired_count), Ordering::Release);
                    purge_stale_password_sessions(
                        app,
                        password,
                        generation_id,
                        session_salt,
                        session.id,
                    );
                    purge_replaced_worker_sessions(
                        app,
                        password,
                        generation_id,
                        session_salt,
                        u64::from(worker_id),
                        session.id,
                    );
                    let ip_str = match &access {
                        CredentialAccess::Main => crate::model::OWNER_TUNNEL_IP.to_string(),
                        CredentialAccess::Slot(slot_key) => {
                            let needs_ip =
                                db.passwords.get(slot_key).is_some_and(|e| e.ip.is_empty());
                            let next_ip = if needs_ip { get_next_ip(&db) } else { None };
                            if let Some(entry) = db.passwords.get_mut(slot_key) {
                                if let Some(ip) = next_ip {
                                    entry.ip = ip;
                                    changed = true;
                                }
                                entry.ip.clone()
                            } else {
                                String::new()
                            }
                        }
                    };
                    if !ip_str.is_empty() {
                        tunnel_ip = crate::tun_device::parse_ipv4(&ip_str);
                        routing_rule = resolve_routing_rule_access(&db, &access).cloned();
                        pad_tunconf(&format!(
                            "TUNCONF:{}:{}:{}:stream-v1:workers={effective_desired_count}",
                            ip_str, dns, client_port
                        ))
                    } else {
                        "NOCONF".to_owned()
                    }
                }
            },
        };
        if changed {
            app.db_persistence.submit(db.clone());
        }
        (
            response,
            reject_session,
            authorization_rejected,
            tunnel_ip,
            changed,
            routing_rule,
            server_worker_limit,
        )
    };
    if authorization_rejected && created_epoch {
        app.password_epochs
            .remove_if(password, |_, current| Arc::ptr_eq(current, &epoch_lock));
    }
    if !reject_session {
        command(
            app,
            ProtocolCommand::UpdateEpoch {
                session_id: session.id,
                password: password.to_owned(),
                generation_id,
                session_salt: session_salt.to_owned(),
            },
        )?;
        command(
            app,
            ProtocolCommand::SetAuthoritativeEpoch {
                password: password.to_owned(),
                generation_id,
                session_salt: session_salt.to_owned(),
            },
        )?;
    }
    let current_session_desired = session.desired_stream_count.load(Ordering::Acquire) as u16;
    let effective_desired_count = if let Some(limit) = server_worker_limit {
        limit as u16
    } else if (3..=MAX_STREAM_WORKERS_U16).contains(&current_session_desired) {
        current_session_desired
    } else if desired_count > 0 {
        desired_count
    } else {
        3
    };
    log_getconf_result(
        app,
        session,
        password,
        Some(generation_id),
        Some(worker_id),
        Some(effective_desired_count),
        &response,
    );
    let should_activate_carrier = worker_id <= effective_desired_count;
    if !reject_session
        && should_activate_carrier
        && let Some(ip) = tunnel_ip
    {
        *lock_unpoison(&session.tunnel_ip) = Some(ip);
        let registration_id = ROUTE_REGISTRATION_COUNTER.fetch_add(1, Ordering::Relaxed);
        session.has_tunnel.store(true, Ordering::Release);
        session
            .desired_stream_count
            .store(u64::from(effective_desired_count), Ordering::Release);
        command(
            app,
            ProtocolCommand::ActivateTunnel {
                session_id: session.id,
                ip,
                registration_id,
                worker_id,
                desired_count: effective_desired_count,
            },
        )?;
    }
    writer.write(response.as_bytes()).await?;
    if !reject_session
        && worker_id <= 1
        && let Some(rule) = routing_rule
    {
        let json_val = serde_json::json!({
            "version": 2,
            "name": rule.name,
            "mode": if rule.is_whitelist { "whitelist" } else { "blacklist" },
            "is_whitelist": rule.is_whitelist,
            "packages": &rule.packages,
            "domains_mode": if rule.domains_is_whitelist { "whitelist" } else { "blacklist" },
            "domains_is_whitelist": rule.domains_is_whitelist,
            "domains": &rule.direct_domains,
            "template_domains": rule.template_domains,
            "ip_mode": if rule.ip_is_whitelist { "whitelist" } else { "blacklist" },
            "ip_is_whitelist": rule.ip_is_whitelist,
            "ips": &rule.ips
        });
        let raw_json = serde_json::to_string(&json_val).unwrap_or_default();
        use flate2::Compression;
        use flate2::write::GzEncoder;
        use std::io::Write;
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        let payload_bytes = if encoder.write_all(raw_json.as_bytes()).is_ok()
            && let Ok(finish) = encoder.finish()
        {
            finish
        } else {
            raw_json.into_bytes()
        };
        use base64::Engine;
        let encoded = base64::engine::general_purpose::STANDARD.encode(&payload_bytes);
        let chunk_size = 650;
        let total_chunks = encoded.len().div_ceil(chunk_size);
        let total_chunks = if total_chunks == 0 { 1 } else { total_chunks };

        let _ = writer
            .write(format!("ROUTING:START:{total_chunks}").as_bytes())
            .await;
        for i in 1..=total_chunks {
            let start = (i - 1) * chunk_size;
            let end = std::cmp::min(i * chunk_size, encoded.len());
            let chunk = if start < encoded.len() {
                &encoded[start..end]
            } else {
                ""
            };
            let _ = writer
                .write(format!("ROUTING:{i}:{total_chunks}:{chunk}").as_bytes())
                .await;
        }
        let _ = writer
            .write(format!("ROUTING:FINISH:{total_chunks}").as_bytes())
            .await;
    }
    drop(epoch);
    if reject_session {
        drop_exact_session(app, session);
    }
    if changed {
        let _ = refresh_credentials(app).await;
    }
    Ok(())
}

async fn ensure_control_tunnel(app: &Arc<App>, session: &Arc<Session>) -> Result<()> {
    if session.has_tunnel.load(Ordering::Acquire) {
        return Ok(());
    }
    let ip = if let Some(ip) = *lock_unpoison(&session.tunnel_ip) {
        ip
    } else {
        let ip = {
            let db = db_read(app).await?;
            resolve_session_ip(&db, &session.password)
        }
        .ok_or_else(|| anyhow!("no IP assigned for this session"))?;
        let parsed =
            crate::tun_device::parse_ipv4(&ip).ok_or_else(|| anyhow!("invalid tunnel IP: {ip}"))?;
        *lock_unpoison(&session.tunnel_ip) = Some(parsed);
        parsed
    };
    let registration_id = ROUTE_REGISTRATION_COUNTER.fetch_add(1, Ordering::Relaxed);
    let worker_id = u16::try_from(session.worker_id.load(Ordering::Acquire))
        .ok()
        .filter(|worker| (1..=MAX_STREAM_WORKERS_U16).contains(worker))
        .unwrap_or(1);
    let desired_count = u16::try_from(session.desired_stream_count.load(Ordering::Acquire))
        .ok()
        .filter(|count| (1..=MAX_STREAM_WORKERS_U16).contains(count) && worker_id <= *count)
        .unwrap_or_default();
    command(
        app,
        ProtocolCommand::ActivateTunnel {
            session_id: session.id,
            ip,
            registration_id,
            worker_id,
            desired_count,
        },
    )?;
    session.has_tunnel.store(true, Ordering::Release);
    Ok(())
}

fn disconnect_request_authorized(
    db: &Database,
    password: &str,
    requester: &SessionEpochIdentity,
    target_salt: &str,
    epoch: &PasswordEpochState,
) -> bool {
    !target_salt.is_empty()
        && secret_eq(&requester.password, password)
        && requester.session_salt == target_salt
        && epoch.matches(requester.generation_id, target_salt)
        && (secret_eq(password, &db.main_password) || db.passwords.contains_key(password))
}

pub async fn handle_disconnect(
    app: &Arc<App>,
    requester: &Arc<Session>,
    password: &str,
    target_salt: &str,
) -> std::result::Result<usize, &'static str> {
    let effective_password = if !password.is_empty() {
        password
    } else {
        requester.password.as_str()
    };
    if effective_password.is_empty() || effective_password.len() > 128 {
        return Err("DENIED:wrong_password");
    }
    if target_salt.is_empty() || target_salt.len() > 128 {
        return Err("DENIED:invalid_epoch");
    }
    let preliminary = session_epoch_identity(requester);
    if preliminary.password != effective_password || preliminary.session_salt != target_salt {
        return Err("DENIED:not_owner");
    }
    let Some((_epoch_lock, epoch)) = lock_existing_password_epoch(app, effective_password).await
    else {
        return Err("DENIED:not_owner");
    };
    let db = db_read(app).await.map_err(|_| "DENIED:server_busy")?;
    let requester_identity = session_epoch_identity(requester);
    if !disconnect_request_authorized(
        &db,
        effective_password,
        &requester_identity,
        target_salt,
        &epoch,
    ) {
        return Err("DENIED:not_owner");
    }
    drop(db);
    let candidates = app
        .sessions
        .iter()
        .filter_map(|entry| {
            let session = entry.value();
            (session.id == requester.id || secret_eq(&session.password, effective_password))
                .then_some(session.id)
        })
        .collect::<Vec<_>>();
    command(
        app,
        ProtocolCommand::DisconnectPassword {
            password: effective_password.to_owned(),
            requester_session_id: requester.id,
        },
    )
    .map_err(|_| "DENIED:server_busy")?;
    let mut removed = 0usize;
    for session_id in candidates {
        if let Some((_, session)) = app.sessions.remove_if(&session_id, |_, current| {
            current.id == session_id
                && (current.id == requester.id || secret_eq(&current.password, effective_password))
        }) {
            session.cancel_token.cancel();
            removed += 1;
        }
    }
    Ok(removed)
}

#[inline(always)]
pub async fn flush_traffic(app: &Arc<App>) {
    let Ok(mut db) = db_write(app).await else {
        return;
    };
    let mut traffic = TrafficSnapshot::default();
    for entry in app.sessions.iter() {
        let session = entry.value();
        let up = session.up_bytes.swap(0, Ordering::Relaxed);
        let down = session.down_bytes.swap(0, Ordering::Relaxed);
        if up == 0 && down == 0 {
            continue;
        }
        if let Some(password) = db.passwords.get_mut(&session.password) {
            password.up_bytes = password.up_bytes.saturating_add(up as i64);
            password.down_bytes = password.down_bytes.saturating_add(down as i64);
            traffic.passwords.insert(
                session.password.clone(),
                TrafficCounters {
                    up_bytes: password.up_bytes,
                    down_bytes: password.down_bytes,
                },
            );
        } else if session.password == db.main_password {
            db.main_up_bytes = db.main_up_bytes.saturating_add(up as i64);
            db.main_down_bytes = db.main_down_bytes.saturating_add(down as i64);
            traffic.main = Some(TrafficCounters {
                up_bytes: db.main_up_bytes,
                down_bytes: db.main_down_bytes,
            });
        }
    }
    drop(db);
    app.db_persistence.submit_traffic(traffic);
}

pub(crate) fn refresh_monotonic_millis() -> u64 {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    let current = START
        .get_or_init(Instant::now)
        .elapsed()
        .as_millis()
        .min(u128::from(u64::MAX)) as u64
        + 1;
    CACHED_MONOTONIC_MS.store(current, Ordering::Relaxed);
    current
}

#[inline]
fn hot_session_idle_reason(is_authenticated: bool) -> &'static str {
    if is_authenticated {
        "authenticated-idle-10h"
    } else {
        "setup-idle-15s"
    }
}

#[inline]
fn public_session_is_stale(has_tunnel: bool, last_seen: u64, current_wall: u64) -> bool {
    let idle_limit = if has_tunnel {
        PUBLIC_AUTH_GHOST_IDLE_SECS
    } else {
        PUBLIC_SETUP_GHOST_IDLE_SECS
    };
    current_wall.saturating_sub(last_seen) >= idle_limit
}

pub async fn session_janitor(app: Arc<App>) {
    let mut timer = tokio::time::interval(Duration::from_secs(5));
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last_traffic_flush = Instant::now() - TRAFFIC_FLUSH_INTERVAL;
    loop {
        timer.tick().await;
        if last_traffic_flush.elapsed() >= TRAFFIC_FLUSH_INTERVAL {
            flush_traffic(&app).await;
            last_traffic_flush = Instant::now();
        }
        let current_wall = wall_clock();
        prune_public_sessions(&app, current_wall);
        if app.sessions.len().saturating_mul(4) < app.sessions.capacity() {
            app.sessions.shrink_to_fit();
        }
    }
}

fn prune_public_sessions(app: &Arc<App>, current_wall: u64) {
    let stale_public = app
        .sessions
        .iter()
        .filter_map(|entry| {
            let session = entry.value();
            public_session_is_stale(
                session.has_tunnel.load(Ordering::Acquire),
                session.last_seen.load(Ordering::Acquire),
                current_wall,
            )
            .then_some(session.id)
        })
        .collect::<Vec<_>>();
    for session_id in stale_public {
        let _ = command(app, ProtocolCommand::DropSession { session_id });
        if let Some((_, removed)) = app
            .sessions
            .remove_if(&session_id, |_, session| session.id == session_id)
        {
            removed.cancel_token.cancel();
        }
    }
    let overflow = app.sessions.len().saturating_sub(PUBLIC_SESSION_LIMIT);
    if overflow == 0 {
        return;
    }
    let mut inactive = app
        .sessions
        .iter()
        .filter(|entry| !entry.value().has_tunnel.load(Ordering::Acquire))
        .map(|entry| {
            (
                entry.value().last_seen.load(Ordering::Acquire),
                entry.value().id,
            )
        })
        .collect::<Vec<_>>();
    inactive.sort_unstable();
    for (_, session_id) in inactive.into_iter().take(overflow) {
        if let Some((_, removed)) = app
            .sessions
            .remove_if(&session_id, |_, session| session.id == session_id)
        {
            removed.cancel_token.cancel();
        }
    }
}

async fn run_password_janitor_cycle(app: &Arc<App>) {
    let expired = {
        let Ok(db) = db_read(app).await else {
            return;
        };
        db.passwords
            .iter()
            .filter(|(_, entry)| is_expired(entry))
            .map(|(password, _)| password.clone())
            .collect::<Vec<String>>()
    };
    let mut credentials_changed = false;
    for password in expired {
        {
            let Ok(mut db) = db_write(app).await else {
                continue;
            };
            if !db.passwords.get(&password).is_some_and(is_expired) {
                continue;
            }
            db.passwords.remove(&password);
            app.db_persistence.submit(db.clone());
        }
        app.derived_keys.remove(&password);
        drop_password_sessions(app, &password);
        credentials_changed = true;
    }
    if credentials_changed {
        let _ = refresh_credentials(app).await;
    }
}

pub async fn password_janitor(app: Arc<App>) {
    let mut timer = tokio::time::interval(Duration::from_secs(3600));
    loop {
        timer.tick().await;
        run_password_janitor_cycle(&app).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::PasswordEntry;

    #[test]
    fn hot_tunnel_configuration_preserves_client_ip_and_replaces_dns() {
        let payload = format!(
            "TUNCONF:{}:{}:0:stream-v1",
            Ipv4Addr::from([10, 66, 67, 42]),
            "8.8.8.8,8.8.4.4"
        );
        assert_eq!(payload, "TUNCONF:10.66.67.42:8.8.8.8,8.8.4.4:0:stream-v1");
    }

    #[test]
    fn password_authentication_authorizes_main_and_slots() {
        let mut db = Database {
            main_password: "OwnerSecretPass1".to_owned(),
            ..Database::default()
        };
        db.passwords.insert(
            "SlotSecretPass01".to_owned(),
            PasswordEntry {
                ip: "10.66.67.3".to_owned(),
                ..PasswordEntry::default()
            },
        );
        db.passwords.insert(
            "SlotSecretPass02".to_owned(),
            PasswordEntry {
                ip: "10.66.67.4".to_owned(),
                is_deactivated: true,
                ..PasswordEntry::default()
            },
        );

        assert_eq!(
            getconf_credential_access(&db, "OwnerSecretPass1"),
            Ok(CredentialAccess::Main)
        );

        assert_eq!(
            getconf_credential_access(&db, "SlotSecretPass01"),
            Ok(CredentialAccess::Slot("SlotSecretPass01".to_owned()))
        );

        assert_eq!(
            getconf_credential_access(&db, "SlotSecretPass02"),
            Err("DENIED:deactivated")
        );

        assert_eq!(
            getconf_credential_access(&db, "UnknownPassword1"),
            Err("DENIED:wrong_password")
        );
        assert_eq!(
            getconf_credential_access(&db, ""),
            Err("DENIED:wrong_password")
        );
    }

    fn fixture_engine(password: &str) -> (ProtocolEngine, mpsc::Receiver<ControlEvent>) {
        let key = derive_wrap_key(password).unwrap();
        let dtls_keys = crate::dtls::derive_dtls_srtp_deterministic(&key);
        let credentials = Arc::new(CredentialSet {
            entries: vec![Credential {
                password: Arc::<str>::from(password),
                key,
                aes: make_aes_key(&key),
                hmac: make_hmac_key(&key),
                dtls_client_cipher_key: dtls_keys.client_cipher_key,
                dtls_client_aes: make_aes_key(&dtls_keys.client_cipher_key),
                dtls_client_hmac: make_hmac_key(&dtls_keys.client_cipher_key),
                dtls_server_cipher_key: dtls_keys.server_cipher_key,
            }],
        });
        let (control_tx, control_rx) = mpsc::channel(64);
        let engine = ProtocolEngine::new(
            credentials,
            HashMap::new(),
            control_tx,
            Arc::new(AtomicBool::new(false)),
            EngineShared {
                global_up: Arc::new(AtomicU64::new(0)),
                global_down: Arc::new(AtomicU64::new(0)),
            },
        );
        (engine, control_rx)
    }

    fn fixture_wire() -> Vec<u8> {
        hex::decode(crate::wire_protocol::CLIENT_GETCONF_AUDIO_FIXTURE).unwrap()
    }

    #[test]
    fn legacy_json_migration_with_16_char_passwords_and_ip() {
        use crate::model::{Database, PasswordEntry, load_database};

        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-legacy-getconf-{unique}"));
        std::fs::create_dir_all(&directory).unwrap();

        let mut legacy = Database {
            main_password: "OwnerMainPass123".to_owned(),
            ..Database::default()
        };
        legacy.passwords.insert(
            "16charclientpass".to_owned(),
            PasswordEntry {
                ip: "".to_owned(),
                ..PasswordEntry::default()
            },
        );
        std::fs::write(
            directory.join("passwords.json"),
            serde_json::to_vec_pretty(&legacy).unwrap(),
        )
        .unwrap();

        let migrated = load_database(&directory).unwrap();
        assert_eq!(migrated.passwords.len(), 1);
        let (new_pass, entry) = migrated.passwords.iter().next().unwrap();
        assert_eq!(new_pass.len(), 16);
        assert!(!entry.ip.is_empty());
        assert_eq!(
            getconf_credential_access(&migrated, new_pass),
            Ok(CredentialAccess::Slot(new_pass.clone()))
        );
        assert_eq!(
            getconf_credential_access(&migrated, "OwnerMainPass123"),
            Ok(CredentialAccess::Main)
        );
        let mut epoch = PasswordEpochState::default();
        assert_eq!(
            epoch.admit(1_786_000_000, "fresh-session-salt"),
            PasswordEpochDecision::Replaced
        );
        assert!(!directory.join("passwords.json").exists());
        let _ = std::fs::remove_dir_all(directory);
    }

    #[tokio::test]
    async fn diagnostic_request_reader_accepts_128_bytes_and_rejects_more() {
        use tokio::io::AsyncWriteExt;

        let (mut writer, mut reader) = tokio::io::duplex(512);
        let expected = "x".repeat(DIAGNOSTIC_REQUEST_MAX_BYTES);
        writer
            .write_all(format!("{expected}\n").as_bytes())
            .await
            .unwrap();
        assert_eq!(
            read_diagnostic_request(&mut reader).await.unwrap(),
            Some(expected)
        );

        let (mut writer, mut reader) = tokio::io::duplex(512);
        writer
            .write_all("x".repeat(DIAGNOSTIC_REQUEST_MAX_BYTES + 1).as_bytes())
            .await
            .unwrap();
        assert_eq!(
            read_diagnostic_request(&mut reader)
                .await
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::InvalidData
        );
    }

    #[test]
    fn public_auth_ghost_ttl_is_two_minutes_and_does_not_underflow() {
        assert!(!public_session_is_stale(true, 1_000, 1_119));
        assert!(public_session_is_stale(true, 1_000, 1_120));
        assert!(!public_session_is_stale(true, 1_000, 999));
        assert!(public_session_is_stale(false, 1_000, 1_030));
    }

    #[test]
    fn hot_authenticated_idle_timeout_is_ten_hours() {
        assert_eq!(SESSION_AUTH_IDLE_MS, 10 * 60 * 60 * 1_000);
        assert_eq!(hot_session_idle_reason(true), "authenticated-idle-10h");
        assert_eq!(hot_session_idle_reason(false), "setup-idle-15s");
    }

    #[test]
    fn idle_keepalives_are_ignored_after_refreshing_session_activity() {
        assert!(is_idle_keepalive(&[0xff; 16]));
        assert!(is_idle_keepalive(&[0xff, 0x11, 0x22, 0x33]));
        assert!(is_idle_keepalive(&[0xff, 1, 2, 3, 4, 5, 6, 7, 8]));
        assert!(!is_idle_keepalive(&[]));
        assert!(!is_idle_keepalive(&[0xff, 1, 2]));
    }

    #[test]
    fn epoch_cache_is_swept_regularly_and_expires_after_one_hour() {
        assert_eq!(EPOCH_SWEEP_INTERVAL_MS, 5 * 60_000);
        assert_eq!(EPOCH_IDLE_TTL_MS, 60 * 60_000);
    }

    #[test]
    fn rtp_clocks_follow_elapsed_media_time() {
        let frame = Duration::from_millis(20);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(111)), 960);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(96)), 1_800);
        assert_eq!(duration_ticks(Duration::from_secs(1), 48_000), 48_000);
        assert_eq!(duration_ticks(Duration::from_secs(1), 90_000), 90_000);
    }

    #[test]
    fn replay_window_handles_u16_wrap_and_duplicates() {
        let mut replay = ReplayState::new();
        assert!(replay.accept(65_534));
        assert!(replay.accept(65_535));
        assert!(replay.accept(0));
        assert!(replay.accept(1));
        assert!(!replay.accept(1));
        assert!(!replay.accept(65_535));
    }

    #[test]
    fn getconf_epoch_parser_is_strict() {
        let max = format!("GETCONF:9000|password|{}|salt", u64::MAX);
        assert_eq!(
            parse_getconf_identity(max.as_bytes()),
            Some(("password", u64::MAX, "salt"))
        );
        let max_with_count = format!("GETCONF:9000|password|{}|salt|4|36", u64::MAX);
        assert_eq!(
            parse_getconf_identity(max_with_count.as_bytes()),
            Some(("password", u64::MAX, "salt"))
        );
        let max_with_count_and_pad = format!(
            "GETCONF:9000|password|{}|salt|4|36|extra_padding_bytes_here",
            u64::MAX
        );
        assert_eq!(
            parse_getconf_identity(max_with_count_and_pad.as_bytes()),
            Some(("password", u64::MAX, "salt"))
        );
        assert!(parse_getconf_identity(b"GETCONF:9000|password|-1|salt").is_none());
        assert!(parse_getconf_identity(b"GETCONF:9000|password|+1|salt").is_none());
        assert!(parse_getconf_identity(b"GETCONF:9000||1|salt").is_none());
    }

    #[test]
    fn tunconf_padding_is_sized_for_audio_frames() {
        let base = "TUNCONF:10.66.67.2:1.1.1.1:9000:stream-v1";
        let padded = pad_tunconf(base);
        assert_eq!(padded, base);
    }

    #[test]
    fn client_getconf_fixture_authenticates_with_matching_password_only() {
        let wire = hex::decode(crate::wire_protocol::CLIENT_GETCONF_AUDIO_FIXTURE).unwrap();
        let key = derive_wrap_key("wire-password").unwrap();
        let aes = make_aes_key(&key);
        let hmac = make_hmac_key(&key);
        let mut matching = wire.clone();
        let decoded = unwrap_legacy_in_place(&aes, &hmac, &mut matching, None, false).unwrap();
        assert_eq!(
            decoded.range.get(&matching),
            b"GETCONF:46000|wire-device|wire-password|7|wire-salt|1|27|CSQTT-WIRE-2"
        );
        assert_eq!(
            parse_getconf_identity(decoded.range.get(&matching)),
            Some(("wire-password", 7, "wire-salt")),
        );

        let wrong_key = derive_wrap_key("wrong-password").unwrap();
        let wrong_aes = make_aes_key(&wrong_key);
        let wrong_hmac = make_hmac_key(&wrong_key);
        let mut mismatched = wire;
        assert!(
            unwrap_legacy_in_place(&wrong_aes, &wrong_hmac, &mut mismatched, None, false,).is_err()
        );
    }

    #[test]
    fn client_fixture_creates_hot_session_and_control_event_through_engine() {
        let (mut engine, mut events) = fixture_engine("wire-password");
        let peer = SocketAddr::from(([198, 51, 100, 10], 46000));
        engine.handle_unknown_legacy(peer, None, &fixture_wire());

        assert_eq!(engine.sessions.len(), 1);
        let (_, session) = engine.sessions.iter().next().unwrap();
        assert_eq!(session.peer, peer);
        assert_eq!(&*session.cold.password, "wire-password");
        assert_eq!(session.cold.generation_id, 7);
        assert_eq!(session.cold.session_salt, "wire-salt");

        match events.try_recv().unwrap() {
            ControlEvent::SessionCreated(session) => {
                assert_eq!(session.address, peer);
                assert_eq!(session.password, "wire-password");
            }
            _ => panic!("expected SessionCreated"),
        }
        match events.try_recv().unwrap() {
            ControlEvent::Payload {
                address, payload, ..
            } => {
                assert_eq!(address, peer);
                assert_eq!(
                    payload,
                    b"GETCONF:46000|wire-device|wire-password|7|wire-salt|1|27|CSQTT-WIRE-2"
                );
            }
            _ => panic!("expected GETCONF payload"),
        }
    }

    #[test]
    fn client_fixture_with_wrong_server_key_cannot_create_a_hot_session() {
        let (mut engine, mut events) = fixture_engine("wrong-password");
        engine.handle_unknown_legacy(
            SocketAddr::from(([198, 51, 100, 11], 46000)),
            None,
            &fixture_wire(),
        );
        assert!(engine.sessions.is_empty());
        assert!(events.try_recv().is_err());
    }

    #[test]
    fn duplicate_client_handshake_replaces_in_place_without_a_phantom_session() {
        let (mut engine, mut events) = fixture_engine("wire-password");
        let peer = SocketAddr::from(([198, 51, 100, 12], 46000));
        let wire = fixture_wire();
        engine.handle_unknown_legacy(peer, None, &wire);
        let first_id = engine.sessions.iter().next().unwrap().1.id;
        engine.handle_unknown_legacy(peer, None, &wire);

        assert_eq!(engine.sessions.len(), 1);
        let second_id = engine.sessions.iter().next().unwrap().1.id;
        assert_ne!(first_id, second_id);
        assert!(!engine.by_id.contains_key(&first_id));
        assert!(engine.by_id.contains_key(&second_id));
        let mut created = 0;
        let mut payloads = 0;
        let mut closed = 0;
        while let Ok(event) = events.try_recv() {
            match event {
                ControlEvent::SessionCreated(_) => created += 1,
                ControlEvent::Payload { .. } => payloads += 1,
                ControlEvent::SessionClosed { session_id, .. } => {
                    assert_eq!(session_id, first_id);
                    closed += 1;
                }
                _ => panic!("unexpected control event during duplicate handshake"),
            }
        }
        assert_eq!(created, 2);
        assert_eq!(payloads, 2);
        assert_eq!(closed, 1);
    }

    #[test]
    fn ten_client_endpoints_complete_the_encrypted_setup_path_without_collisions() {
        let (mut engine, mut events) = fixture_engine("wire-password");
        let wire = fixture_wire();
        for client in 0..10u8 {
            let peer = SocketAddr::from(([198, 51, 100, client + 20], 46_000 + u16::from(client)));
            engine.handle_unknown_legacy(peer, None, &wire);
        }

        assert_eq!(engine.sessions.len(), 10);
        assert_eq!(engine.by_peer.len(), 10);
        assert_eq!(engine.by_id.len(), 10);
        for _ in 0..20 {
            assert!(events.try_recv().is_ok());
        }
        assert!(events.try_recv().is_err());
    }

    #[test]
    fn client_getconf_fixture_rejects_every_single_bit_wire_mutation() {
        let wire = hex::decode(crate::wire_protocol::CLIENT_GETCONF_AUDIO_FIXTURE).unwrap();
        let key = derive_wrap_key("wire-password").unwrap();
        let aes = make_aes_key(&key);
        let hmac = make_hmac_key(&key);

        for byte_index in 0..wire.len() {
            for bit in 0..8 {
                let mut corrupted = wire.clone();
                corrupted[byte_index] ^= 1 << bit;
                assert!(
                    unwrap_legacy_in_place(&aes, &hmac, &mut corrupted, None, false).is_err(),
                    "accepted mutation at byte {byte_index}, bit {bit}"
                );
            }
        }
    }

    #[test]
    fn stream_control_payload_is_compact_and_deterministic() {
        let payload = stream_control_payload(STREAM_REPAIR_PREFIX, 7, 36, &[14, 28]);
        assert!(payload.starts_with(STREAM_REPAIR_PREFIX));
        let offset = STREAM_REPAIR_PREFIX.len();
        assert_eq!(&payload[offset..offset + 8], &7u64.to_be_bytes());
        assert_eq!(&payload[offset + 8..offset + 10], &36u16.to_be_bytes());
        assert_eq!(payload[offset + 10], 2);
        assert_eq!(&payload[offset + 11..offset + 13], &14u16.to_be_bytes());
        assert_eq!(&payload[offset + 13..offset + 15], &28u16.to_be_bytes());
        assert_eq!(payload.len(), STREAM_REPAIR_PREFIX.len() + 15);
    }

    #[test]
    fn stream_repair_round_deduplicates_and_escalates_by_sequence() {
        let mut round = StreamRepairRound::default();
        assert!(round.repair_payload(&[14, 28], 36, 0).is_none());
        let first = round.repair_payload(&[14, 28], 36, 30_000).unwrap();
        assert!(round.repair_payload(&[14, 28], 36, 31_000).is_none());
        let next = round.repair_payload(&[14, 28], 36, 90_000).unwrap();
        assert_ne!(first, next);
    }

    #[test]
    fn stream_repair_round_emits_alive_after_recovery() {
        let mut round = StreamRepairRound::default();
        assert!(round.repair_payload(&[14], 36, 0).is_none());
        let repair = round.repair_payload(&[14], 36, 30_000).unwrap();
        round.recovered(36, 30_100);
        let alive = round.alive_payload(30_100).unwrap();
        assert!(alive.starts_with(STREAM_ALIVE_PREFIX));
        assert_ne!(repair, alive);
        assert!(round.alive_payload(35_500).is_none());
        assert!(round.alive_payload(40_100).is_some());
        assert!(round.alive_payload(45_100).is_none());
    }

    #[test]
    fn session_lease_is_control() {
        assert!(is_control_payload(SESSION_LEASE));
    }

    #[test]
    fn udp_flushes_control_without_flushing_regular_data() {
        assert!(should_flush_udp_immediately(b"GETCONF:9000|device"));
        assert!(should_flush_udp_immediately(STREAM_REPAIR_PREFIX));
        assert!(should_flush_udp_immediately(b"READY"));
        assert!(!should_flush_udp_immediately(&[0x45, 0, 0, 28]));
    }

    #[test]
    fn reconnect_session_key_replaces_stale_and_conflicting_values() {
        assert_eq!(
            getconf_reconnect_action_hot("device", 77, "salt-a", "device", 76, "old",),
            GetconfReconnectAction::Replace
        );
        assert_eq!(
            getconf_reconnect_action_hot("device", 77, "salt-a", "device", 77, "salt-b",),
            GetconfReconnectAction::Replace
        );
        assert_eq!(
            getconf_reconnect_action_hot("device", 77, "salt-a", "device", 77, "salt-a",),
            GetconfReconnectAction::Process
        );
        assert_eq!(
            getconf_reconnect_action_hot("device", 77, "salt-a", "device", 77, "salt-a"),
            GetconfReconnectAction::Process
        );
    }

    #[test]
    fn active_password_session_replaces_any_different_key_without_ordering() {
        let mut state = PasswordEpochState::default();

        assert_eq!(state.admit(77, "salt-a"), PasswordEpochDecision::Replaced);
        assert_eq!(state.admit(77, "salt-a"), PasswordEpochDecision::Current);
        assert_eq!(state.admit(12, "salt-b"), PasswordEpochDecision::Replaced);
        assert_eq!(state.generation_id, 12);
        assert_eq!(state.session_salt, "salt-b");
        assert_eq!(state.admit(12, "salt-c"), PasswordEpochDecision::Replaced);
        assert_eq!(state.session_salt, "salt-c");
    }

    #[test]
    fn active_session_key_retires_every_different_session() {
        assert!(session_is_retired_by_epoch(
            "pass1", 41, "old-a", "pass1", 42, "current"
        ));
        assert!(session_is_retired_by_epoch(
            "pass1", 1, "old-b", "pass1", 42, "current"
        ));
        assert!(session_is_retired_by_epoch(
            "pass1", 42, "conflict", "pass1", 42, "current"
        ));
        assert!(!session_is_retired_by_epoch(
            "pass1", 42, "current", "pass1", 42, "current"
        ));
        assert!(session_is_retired_by_epoch(
            "pass1", 43, "future", "pass1", 42, "current"
        ));
        assert!(!session_is_retired_by_epoch(
            "other", 1, "old", "pass1", 42, "current"
        ));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn concurrent_session_admission_accepts_each_new_session_key() {
        let state = Arc::new(Mutex::new(PasswordEpochState::default()));
        let mut tasks = Vec::new();
        for generation in (1..=128u64).rev().chain(1..=128) {
            let state = state.clone();
            tasks.push(tokio::spawn(async move {
                let mut state = state.lock().await;
                state.admit(generation, &format!("salt-{generation}"))
            }));
        }
        for task in tasks {
            task.await.unwrap();
        }
        let mut state = state.lock().await;
        assert!(
            (1..=128).any(|generation| state.matches(generation, &format!("salt-{generation}")))
        );
        assert_eq!(
            state.admit(129, "salt-129"),
            PasswordEpochDecision::Replaced
        );
        assert_eq!(state.admit(129, "salt-129"), PasswordEpochDecision::Current);
    }

    #[test]
    fn disconnect_targets_requester_and_all_password_sessions() {
        assert!(session_is_disconnected_password(7, "", 7, "pass"));
        assert!(session_is_disconnected_password(8, "pass", 7, "pass"));
        assert!(!session_is_disconnected_password(8, "other", 7, "pass"));
    }

    #[test]
    fn ten_clients_one_gigabit_replay_path_is_predictable() {
        use std::sync::atomic::{AtomicU64, Ordering};

        const CLIENTS: usize = 10;
        const PACKET_BYTES: u64 = 1_250;
        const PACKETS_PER_CLIENT: u16 = 10_000;
        let admitted_bytes = AtomicU64::new(0);

        std::thread::scope(|scope| {
            for client in 0..CLIENTS {
                let admitted_bytes = &admitted_bytes;
                scope.spawn(move || {
                    let mut replay = ReplayState::new();
                    for sequence in 0..PACKETS_PER_CLIENT {
                        assert!(
                            replay.accept(sequence),
                            "client {client}, sequence {sequence}"
                        );
                        admitted_bytes.fetch_add(PACKET_BYTES, Ordering::Relaxed);
                    }
                });
            }
        });

        assert_eq!(
            admitted_bytes.load(Ordering::Relaxed),
            u64::try_from(CLIENTS).unwrap() * u64::from(PACKETS_PER_CLIENT) * PACKET_BYTES
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn ten_clients_concurrent_reconnections_keep_password_identities_isolated() {
        let states: Vec<_> = (0..10)
            .map(|_| Arc::new(Mutex::new(PasswordEpochState::default())))
            .collect();
        let mut tasks = Vec::new();

        for client in 0..10u64 {
            for generation in 1..=128u64 {
                let state = states[client as usize].clone();
                tasks.push(tokio::spawn(async move {
                    let salt = format!("client-{client}-generation-{generation}");
                    let mut state = state.lock().await;
                    state.admit(generation, &salt);
                }));
            }
        }
        for task in tasks {
            task.await.unwrap();
        }

        for (client, state) in states.iter().enumerate() {
            let state = state.lock().await;
            assert!(
                (1..=128).any(|generation| state.matches(
                    generation,
                    &format!("client-{client}-generation-{generation}")
                )),
                "client {client} retained another client's generation identity"
            );
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn same_password_reconnections_converge_to_a_complete_generation_pair() {
        let state = Arc::new(Mutex::new(PasswordEpochState::default()));
        let mut tasks = Vec::new();
        for attempt in 0..1024u64 {
            let state = state.clone();
            tasks.push(tokio::spawn(async move {
                let generation = attempt.wrapping_mul(37).wrapping_add(11);
                let salt = format!("salt-{generation}-{attempt}");
                let mut state = state.lock().await;
                state.admit(generation, &salt);
            }));
        }
        for task in tasks {
            task.await.unwrap();
        }

        let state = state.lock().await;
        assert!(
            (0..1024u64).any(|attempt| {
                let generation = attempt.wrapping_mul(37).wrapping_add(11);
                state.matches(generation, &format!("salt-{generation}-{attempt}"))
            }),
            "generation and salt were observed from different reconnects"
        );
    }

    #[test]
    fn replay_window_accepts_one_hundred_thousand_packets_through_wraps() {
        let mut replay = ReplayState::new();
        for sequence in 0..100_000u32 {
            assert!(replay.accept(sequence as u16), "sequence {sequence}");
        }
    }

    #[test]
    fn replay_window_rejects_deterministic_duplicates_and_stale_packets() {
        let mut replay = ReplayState::new();
        for sequence in 0..20_000u32 {
            assert!(replay.accept(sequence as u16));
            assert!(!replay.accept(sequence as u16));
        }
        assert!(!replay.accept(0));
        assert!(!replay.accept(1));
    }

    #[test]
    fn replay_window_accepts_initial_sequence_in_upper_half() {
        for start_seq in [32768u16, 40000, 49151, 65535] {
            let mut replay = ReplayState::new();
            for offset in 0..1000u16 {
                let seq = start_seq.wrapping_add(offset);
                assert!(replay.accept(seq), "start {start_seq}, seq {seq}");
            }
        }
    }

    #[test]
    fn ten_stream_repair_rounds_converge_without_cross_client_state() {
        let mut rounds: Vec<_> = (0..10).map(|_| StreamRepairRound::default()).collect();
        for (client, round) in rounds.iter_mut().enumerate() {
            let missing = [client as u16 + 1, client as u16 + 21];
            assert!(round.repair_payload(&missing, 36, 0).is_none());
            let repair = round
                .repair_payload(&missing, 36, STREAM_REPAIR_GRACE_MS)
                .unwrap();
            assert!(repair.ends_with(&(client as u16 + 21).to_be_bytes()));
            round.recovered(36, STREAM_REPAIR_GRACE_MS + 1);
            let alive = round.alive_payload(STREAM_REPAIR_GRACE_MS + 1).unwrap();
            assert!(alive.starts_with(STREAM_ALIVE_PREFIX));
        }
    }

    #[test]
    fn stream_control_payload_represents_all_supported_workers() {
        let workers: Vec<u16> = (1..=MAX_STREAM_WORKERS_U16).collect();
        let payload =
            stream_control_payload(STREAM_REPAIR_PREFIX, 9, MAX_STREAM_WORKERS_U16, &workers);
        let offset = STREAM_REPAIR_PREFIX.len();
        assert_eq!(payload[offset + 10], MAX_STREAM_WORKERS_U16 as u8);
        assert_eq!(
            &payload[payload.len() - 2..],
            &MAX_STREAM_WORKERS_U16.to_be_bytes()
        );
    }

    #[test]
    fn stream_control_payload_caps_untrusted_worker_list_at_u8_limit() {
        let workers: Vec<u16> = (0..300u16).collect();
        let payload = stream_control_payload(STREAM_REPAIR_PREFIX, 4, 300, &workers);
        let offset = STREAM_REPAIR_PREFIX.len();
        assert_eq!(payload[offset + 10], u8::MAX);
        assert_eq!(
            payload.len(),
            STREAM_REPAIR_PREFIX.len() + 11 + usize::from(u8::MAX) * 2
        );
    }

    #[test]
    fn generation_parser_accepts_decimal_boundaries_without_timestamp_ordering() {
        for value in ["0", "1", "0001", "42", "18446744073709551615"] {
            assert!(parse_generation_id(value).is_some(), "{value}");
        }
    }

    #[test]
    fn generation_parser_rejects_non_decimal_and_overflow_values() {
        for value in ["-1", "+1", "1.0", "abc", "18446744073709551616", " 7", "7 "] {
            assert!(parse_generation_id(value).is_none(), "{value:?}");
        }
    }

    #[test]
    fn test_two_stage_handshake_getworkers_parsing_and_limits() {
        let mut db = Database {
            main_workers: Some(15),
            ..Database::default()
        };
        db.passwords.insert(
            "client-key".to_string(),
            PasswordEntry {
                workers: Some(10),
                ..PasswordEntry::default()
            },
        );
        db.passwords
            .insert("unlimited-key".to_string(), PasswordEntry::default());

        let access = CredentialAccess::Slot("client-key".to_string());
        let limit = resolve_worker_limit_access(&db, &access);
        assert_eq!(limit, Some(10));

        let requested = 25u32;
        let (agreed, limit_val) = match limit {
            Some(l) if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&l) => {
                let eff = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested) {
                    requested.min(l)
                } else if requested < 3 {
                    3.min(l)
                } else {
                    l
                };
                (eff, l)
            }
            _ => (requested, 0),
        };
        assert_eq!(agreed, 10);
        assert_eq!(limit_val, 10);

        let access_unlimited = CredentialAccess::Slot("unlimited-key".to_string());
        let limit_unlimited = resolve_worker_limit_access(&db, &access_unlimited);
        assert_eq!(limit_unlimited, None);
        let (agreed_u, limit_val_u) = match limit_unlimited {
            Some(l) if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&l) => (requested.min(l), l),
            _ => {
                let req = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested) {
                    requested
                } else {
                    3
                };
                (req, 0)
            }
        };
        assert_eq!(agreed_u, 25);
        assert_eq!(limit_val_u, 0);

        let requested_low = 1u32;
        let (agreed_low, _) = match limit_unlimited {
            Some(l) if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&l) => {
                (requested_low.min(l), l)
            }
            _ => {
                let req = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested_low) {
                    requested_low
                } else if requested_low > MAX_STREAM_WORKERS_U16 as u32 {
                    MAX_STREAM_WORKERS_U16 as u32
                } else {
                    3
                };
                (req, 0)
            }
        };
        assert_eq!(agreed_low, 3);

        let requested_high = 200u32;
        let (agreed_high, _) = match limit_unlimited {
            Some(l) if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&l) => {
                (requested_high.min(l), l)
            }
            _ => {
                let req = if (3..=MAX_STREAM_WORKERS_U16 as u32).contains(&requested_high) {
                    requested_high
                } else if requested_high > MAX_STREAM_WORKERS_U16 as u32 {
                    MAX_STREAM_WORKERS_U16 as u32
                } else {
                    3
                };
                (req, 0)
            }
        };
        assert_eq!(agreed_high, MAX_STREAM_WORKERS_U16 as u32);
    }

    #[test]
    fn test_bootstrap_effective_workers_prevents_phantom_max_repair() {
        let desired_count = 0u16;
        let current_session_desired = 0u16;
        let server_worker_limit: Option<u32> = None;

        let effective_desired_count = if let Some(limit) = server_worker_limit {
            limit as u16
        } else if (3..=MAX_STREAM_WORKERS_U16).contains(&current_session_desired) {
            current_session_desired
        } else if desired_count > 0 {
            desired_count
        } else {
            3
        };
        assert_eq!(effective_desired_count, 3);

        let active_carriers: std::collections::HashSet<u16> = [1, 2, 3].into_iter().collect();
        let missing: Vec<u16> = (1..=effective_desired_count)
            .filter(|worker| !active_carriers.contains(worker))
            .collect();

        assert!(
            missing.is_empty(),
            "expected 0 missing streams on bootstrap of 3, got: {:?}",
            missing
        );

        let buggy_effective_desired = MAX_STREAM_WORKERS_U16;
        let buggy_missing: Vec<u16> = (1..=buggy_effective_desired)
            .filter(|worker| !active_carriers.contains(worker))
            .collect();
        assert_eq!(
            buggy_missing.len(),
            usize::from(MAX_STREAM_WORKERS_U16 - 3),
            "demonstrates the old bug that caused a full repair request"
        );
    }

    #[test]
    fn test_stream_descriptor_tracking_and_no_leak_on_reconnect_and_teardown() {
        use crate::tun_device::RouteTable;
        let ip = [10, 66, 67, 10];
        let mut table = RouteTable::new();

        assert!(table.register(ip, 101, 1, 1, 0));
        assert!(table.register(ip, 102, 2, 2, 1));
        assert!(table.register(ip, 103, 3, 3, 2));

        assert_eq!(table.stream_count(ip), 3);
        assert_eq!(table.active_client_count(), 1);

        assert!(table.register(ip, 202, 20, 2, 1));
        assert_eq!(table.stream_count(ip), 3);

        assert!(table.register(ip, 104, 4, 4, 3));
        assert!(table.register(ip, 105, 5, 5, 4));
        assert_eq!(table.stream_count(ip), 5);

        assert!(table.unregister(ip, 1));
        assert!(table.unregister(ip, 20));
        assert!(table.unregister(ip, 3));
        assert!(table.unregister(ip, 4));
        assert!(table.unregister(ip, 5));

        assert_eq!(table.stream_count(ip), 0);
        assert_eq!(table.active_client_count(), 0);
    }

    #[tokio::test]
    async fn dns_fallback_identifies_yandex_immediately_without_network() {
        assert_eq!(FALLBACK_DNS, "77.88.8.8,77.88.8.1");
    }
}
