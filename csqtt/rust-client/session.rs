use crate::{
    auth::TurnCredentials,
    dispatcher::{Dispatcher, WorkerChannels},
    events::Events,
    obfs::{ObfsCipher, ObfsConfig, ObfsMode, ObfsState, SessionCipher, is_rtp_packet},
    packet::{PacketBuf, PacketPool},
    protocol::{
        ConfigResponse, config_request, disconnect_request, is_config_response,
        is_control_response, is_panel_restart_notice, parse_config_response, parse_stream_alive,
        parse_stream_repair,
    },
    repair::RepairState,
    srtp_mode::SrtpMode,
    stats::Stats,
    turn::{TurnAllocation, TurnConnectTarget, TurnReceiver, TurnRequestError},
    turn_endpoint::{TurnTransportMode, resolve_turn_endpoints},
};
use anyhow::{Context, Result, anyhow, bail};
use arc_swap::ArcSwap;
use bytes::Bytes;
use std::{
    net::SocketAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio::{
    sync::{mpsc, oneshot},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;

const CONFIG_RESPONSE_TIMEOUT_MS: [u64; 4] = [400, 800, 1600, 3000];
const DEALLOCATE_TIMEOUT: Duration = Duration::from_millis(700);
const CONNECT_CANCEL_GRACE: Duration = Duration::from_secs(1);
const SESSION_SHUTDOWN_GRACE: Duration = Duration::from_secs(2);
const DISCONNECT_SEND_TIMEOUT: Duration = Duration::from_millis(300);

fn configuration_timeout_message() -> String {
    format!(
        "GETCONF чтение ответа конфига: timeout после {} попыток",
        CONFIG_RESPONSE_TIMEOUT_MS.len()
    )
}
const DISCONNECT_ACK_TIMEOUT: Duration = Duration::from_millis(600);
const DISCONNECT_CONTROL_ATTEMPTS: usize = 3;
const WRITER_COMMAND_CAPACITY: usize = 256;
static NEXT_INCARNATION_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub struct TurnAllocateError(anyhow::Error);

impl TurnAllocateError {
    pub fn stun_code(&self) -> Option<i32> {
        self.0.chain().find_map(|cause| {
            cause
                .downcast_ref::<TurnRequestError>()
                .map(TurnRequestError::stun_code)
        })
    }
}

impl std::fmt::Display for TurnAllocateError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "TURN Allocate: {:#}", self.0)
    }
}

impl std::error::Error for TurnAllocateError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(self.0.as_ref())
    }
}

pub struct SessionConfig {
    pub id: usize,
    pub peer: SocketAddr,
    pub turn_host: Option<Arc<str>>,
    pub turn_port: Option<Arc<str>>,
    pub srtp_mode: SrtpMode,
    pub turn_transport: TurnTransportMode,
    pub local_port: Arc<str>,
    pub password: Arc<str>,
    pub generation: u64,
    pub turn_endpoint_cursor: usize,
    pub salt: Arc<str>,
    pub mode: ObfsMode,
    #[allow(dead_code)]
    pub wrap_key: [u8; 32],
    pub client_wrap_key: [u8; 32],
    pub get_config: bool,
    pub server_stream_repair: Arc<AtomicBool>,
    pub repair: Arc<RepairState>,
    pub configured_workers: usize,
}

pub struct SessionRuntime {
    pub dispatcher: Arc<Dispatcher>,
    pub pool: Arc<PacketPool>,
    pub stats: Arc<Stats>,
    pub events: Events,
    pub config_tx: mpsc::Sender<String>,
    pub config_delivery: Option<ConfigDeliveryState>,
    pub cancel: CancellationToken,
    pub shutdown: Arc<ShutdownCoordinator>,
    pub ready_tx: Option<oneshot::Sender<()>>,
    pub allocation_started: Option<oneshot::Sender<()>>,
    pub allocation_ready: Option<oneshot::Sender<()>>,
    pub scale_notify: Option<Arc<tokio::sync::Notify>>,
    pub target_workers: Option<Arc<AtomicUsize>>,
    pub configured_workers: usize,
}

fn build_registration_payload(config: &SessionConfig) -> Bytes {
    Bytes::from(config_request(
        &config.local_port,
        &config.password,
        config.generation,
        &config.salt,
        config.id,
        Some(config.configured_workers.max(1)),
    ))
}

fn apply_workers_limit(
    agreed: usize,
    target_workers: Option<&Arc<AtomicUsize>>,
    scale_notify: Option<&Arc<tokio::sync::Notify>>,
) {
    let effective = agreed.max(1);
    if let Some(target) = target_workers {
        let prev = target.swap(effective, Ordering::AcqRel);
        if prev != effective
            && let Some(notify) = scale_notify
        {
            notify.notify_waiters();
        }
    }
}

pub fn parse_workers_from_tunconf(text: &str) -> Option<usize> {
    for part in text.split(':') {
        if let Some(count) = part
            .strip_prefix("workers=")
            .and_then(|s| s.trim().parse::<usize>().ok())
        {
            return Some(count);
        }
    }
    None
}

pub struct ConfigDeliveryState {
    pub sent: Arc<AtomicBool>,
    pub in_flight: Arc<AtomicBool>,
}

impl ConfigDeliveryState {
    fn complete(&self, delivered: bool) {
        if delivered {
            self.sent.store(true, Ordering::Release);
        }
        self.in_flight.store(false, Ordering::Release);
    }
}

struct TransportShared {
    allocation: Arc<TurnAllocation>,
    cipher: ArcSwap<SessionCipher>,
    recv_cipher: ArcSwap<SessionCipher>,
    config: ObfsConfig,
    pool: Arc<PacketPool>,
}

struct TransportWriter {
    shared: Arc<TransportShared>,
    write_state: Arc<ObfsState>,
}

struct TransportReader {
    shared: Arc<TransportShared>,
    receiver: TurnReceiver,
    replay: ReplayProtection,
}

enum WriterCommand {
    SendBytes {
        data: Bytes,
        completion: oneshot::Sender<Result<()>>,
    },
}

struct WriterRuntime {
    commands: mpsc::Receiver<WriterCommand>,
    shutdown: Arc<ShutdownCoordinator>,
    activity: Arc<AtomicU64>,
}

pub const MAX_ROUTING_CHUNKS: usize = 8192;

#[derive(Default)]
pub struct RoutingReassembler {
    total_chunks: usize,
    chunks: Vec<Option<String>>,
    received_count: usize,
    finished: bool,
}

impl RoutingReassembler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn feed(&mut self, payload: &[u8]) -> Option<String> {
        let text = std::str::from_utf8(payload).ok()?;
        let rest = text.strip_prefix("ROUTING:")?;
        if let Some(total_str) = rest.strip_prefix("START:") {
            let total: usize = total_str.trim().parse().ok()?;
            if total == 0 || total > MAX_ROUTING_CHUNKS {
                return None;
            }
            if self.total_chunks != total {
                self.total_chunks = total;
                self.chunks = vec![None; total];
                self.received_count = 0;
                self.finished = false;
            }
            return None;
        }
        if let Some(total_str) = rest.strip_prefix("FINISH:") {
            let total: usize = total_str.trim().parse().ok()?;
            if total == 0 || total > MAX_ROUTING_CHUNKS {
                return None;
            }
            if self.total_chunks != total {
                self.total_chunks = total;
                self.chunks = vec![None; total];
                self.received_count = 0;
            }
            self.finished = true;
            if self.received_count == self.total_chunks && self.total_chunks > 0 {
                return self.assemble();
            }
            return None;
        }
        let mut parts = rest.splitn(3, ':');
        let seq_str = parts.next()?;
        let total_str = parts.next()?;
        let chunk_data = parts.next()?;
        let seq: usize = seq_str.trim().parse().ok()?;
        let total: usize = total_str.trim().parse().ok()?;
        if total == 0 || total > MAX_ROUTING_CHUNKS || seq == 0 || seq > total {
            return None;
        }
        if self.total_chunks != total {
            self.total_chunks = total;
            self.chunks = vec![None; total];
            self.received_count = 0;
            self.finished = false;
        }
        if self.chunks[seq - 1].is_none() {
            self.chunks[seq - 1] = Some(chunk_data.to_string());
            self.received_count += 1;
        }
        if self.finished && self.received_count == self.total_chunks && self.total_chunks > 0 {
            return self.assemble();
        }
        None
    }

    fn assemble(&mut self) -> Option<String> {
        use base64::Engine;
        let mut b64_combined = String::new();
        for chunk in &self.chunks {
            b64_combined.push_str(chunk.as_deref().unwrap_or_default());
        }
        self.chunks.clear();
        self.received_count = 0;
        self.total_chunks = 0;
        self.finished = false;

        let decoded = base64::engine::general_purpose::STANDARD
            .decode(b64_combined.trim())
            .ok()?;
        if decoded.len() >= 2 && decoded[0] == 0x1f && decoded[1] == 0x8b {
            use flate2::read::GzDecoder;
            use std::io::Read;
            let mut gz = GzDecoder::new(&decoded[..]);
            let mut decompressed = String::new();
            if gz.read_to_string(&mut decompressed).is_ok() {
                return Some(decompressed);
            }
        }
        String::from_utf8(decoded).ok()
    }
}

struct ReaderRuntime {
    dispatcher: Arc<Dispatcher>,
    events: Events,
    repair: Arc<RepairState>,
    shutdown: Arc<ShutdownCoordinator>,
    config_tx: mpsc::Sender<String>,
    routing_reassembler: RoutingReassembler,
    scale_notify: Option<Arc<tokio::sync::Notify>>,
    target_workers: Option<Arc<AtomicUsize>>,
    configured_workers: usize,
}

pub struct ShutdownCoordinator {
    state: Mutex<ShutdownState>,
    changed: tokio::sync::Notify,
    next_activity: AtomicU64,
}

struct ShutdownState {
    active: bool,
    completed: bool,
    streams: Vec<ShutdownControlStream>,
}

struct ShutdownControlStream {
    incarnation_id: u64,
    writer: mpsc::Sender<WriterCommand>,
    activity: Arc<AtomicU64>,
}

struct ShutdownRegistration {
    coordinator: Arc<ShutdownCoordinator>,
    incarnation_id: u64,
    activity: Arc<AtomicU64>,
}

impl ShutdownCoordinator {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(ShutdownState {
                active: false,
                completed: false,
                streams: Vec::new(),
            }),
            changed: tokio::sync::Notify::new(),
            next_activity: AtomicU64::new(1),
        }
    }

    fn register(
        self: &Arc<Self>,
        incarnation_id: u64,
        writer: mpsc::Sender<WriterCommand>,
    ) -> ShutdownRegistration {
        let activity = Arc::new(AtomicU64::new(self.next_activity()));
        let mut state = self.lock_state();
        if !state.active {
            state.streams.push(ShutdownControlStream {
                incarnation_id,
                writer,
                activity: activity.clone(),
            });
        }
        ShutdownRegistration {
            coordinator: self.clone(),
            incarnation_id,
            activity,
        }
    }

    fn mark_activity(&self, activity: &AtomicU64) {
        activity.store(self.next_activity(), Ordering::Release);
    }

    async fn request_disconnect(&self, password: &str, salt: &str) {
        let leader = {
            let mut state = self.lock_state();
            if state.active {
                false
            } else {
                state.active = true;
                state.completed = false;
                true
            }
        };
        if !leader {
            self.wait_until_completed(Duration::from_secs(2)).await;
            return;
        }

        let request = disconnect_request(password, salt);
        for writer in self
            .control_streams()
            .into_iter()
            .take(DISCONNECT_CONTROL_ATTEMPTS)
        {
            if self.is_completed() {
                break;
            }
            let delivered = tokio::time::timeout(
                DISCONNECT_SEND_TIMEOUT,
                send_writer_bytes(&writer, request.as_bytes()),
            )
            .await
            .is_ok_and(|result| result.is_ok());
            if delivered && self.wait_until_completed(DISCONNECT_ACK_TIMEOUT).await {
                break;
            }
        }
        self.complete();
    }

    fn observe_control_response(&self, response: &[u8]) -> bool {
        if response != b"OK:disconnected" && !response.starts_with(b"DENIED:") {
            return false;
        }
        let mut state = self.lock_state();
        if !state.active || state.completed {
            return false;
        }
        state.completed = true;
        drop(state);
        self.changed.notify_waiters();
        true
    }

    fn control_streams(&self) -> Vec<mpsc::Sender<WriterCommand>> {
        let state = self.lock_state();
        let mut streams: Vec<_> = state
            .streams
            .iter()
            .map(|stream| {
                (
                    stream.activity.load(Ordering::Acquire),
                    stream.incarnation_id,
                    stream.writer.clone(),
                )
            })
            .collect();
        streams.sort_unstable_by(|left, right| {
            right.0.cmp(&left.0).then_with(|| right.1.cmp(&left.1))
        });
        streams.into_iter().map(|(_, _, writer)| writer).collect()
    }

    fn complete(&self) {
        let mut state = self.lock_state();
        if state.completed {
            return;
        }
        state.completed = true;
        drop(state);
        self.changed.notify_waiters();
    }

    fn is_completed(&self) -> bool {
        self.lock_state().completed
    }

    async fn wait_until_completed(&self, timeout: Duration) -> bool {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            let notified = self.changed.notified();
            if self.is_completed() {
                return true;
            }
            if tokio::time::timeout_at(deadline, notified).await.is_err() {
                return self.is_completed();
            }
        }
    }

    fn unregister(&self, incarnation_id: u64) {
        let mut state = self.lock_state();
        state
            .streams
            .retain(|stream| stream.incarnation_id != incarnation_id);
    }

    fn next_activity(&self) -> u64 {
        self.next_activity.fetch_add(1, Ordering::Relaxed).max(1)
    }

    fn lock_state(&self) -> std::sync::MutexGuard<'_, ShutdownState> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Drop for ShutdownRegistration {
    fn drop(&mut self) {
        self.coordinator.unregister(self.incarnation_id);
    }
}

struct ReplayWindow {
    highest: Option<u64>,
    seen: Box<[u64; 256]>,
}

const REPLAY_WINDOW_BITS: usize = 16_384;
const REPLAY_WORD_BITS: usize = 64;

#[derive(Default)]
struct ReplayProtection {
    rtp: ReplayWindow,
}

impl Default for ReplayWindow {
    fn default() -> Self {
        Self {
            highest: None,
            seen: Box::new([0; 256]),
        }
    }
}

impl ReplayWindow {
    fn accept(&mut self, counter: u64) -> bool {
        let Some(highest) = self.highest else {
            self.highest = Some(counter);
            self.set_bit(counter);
            return true;
        };
        if counter > highest {
            let shift = counter - highest;
            if shift >= REPLAY_WINDOW_BITS as u64 {
                self.seen.fill(0);
            } else {
                self.clear_advanced(highest.wrapping_add(1), shift as usize);
            }
            self.highest = Some(counter);
            self.set_bit(counter);
            return true;
        }
        let age = highest - counter;
        if age >= REPLAY_WINDOW_BITS as u64 {
            return false;
        }
        if self.test_bit(counter) {
            return false;
        }
        self.set_bit(counter);
        true
    }

    fn set_bit(&mut self, counter: u64) {
        let idx = (counter as usize) % REPLAY_WINDOW_BITS;
        self.seen[idx / REPLAY_WORD_BITS] |= 1 << (idx % REPLAY_WORD_BITS);
    }

    fn clear_advanced(&mut self, first: u64, count: usize) {
        let mut bit = (first as usize) % REPLAY_WINDOW_BITS;
        let mut remaining = count;
        while remaining != 0 {
            let offset = bit % REPLAY_WORD_BITS;
            let width = remaining.min(REPLAY_WORD_BITS - offset);
            let mask = if width == REPLAY_WORD_BITS {
                u64::MAX
            } else {
                ((1u64 << width) - 1) << offset
            };
            self.seen[bit / REPLAY_WORD_BITS] &= !mask;
            bit = (bit + width) % REPLAY_WINDOW_BITS;
            remaining -= width;
        }
    }

    fn test_bit(&self, counter: u64) -> bool {
        let idx = (counter as usize) % REPLAY_WINDOW_BITS;
        (self.seen[idx / REPLAY_WORD_BITS] & (1 << (idx % REPLAY_WORD_BITS))) != 0
    }

    fn accept_rtp(&mut self, sequence: u16) -> bool {
        let Some(highest) = self.highest else {
            return self.accept(sequence as u64);
        };
        let base = highest & !(u16::MAX as u64);
        let mut extended = base | sequence as u64;
        if extended.saturating_add(1 << 15) < highest {
            extended = extended.saturating_add(1 << 16);
        } else if extended > highest.saturating_add(1 << 15) && extended >= 1 << 16 {
            extended -= 1 << 16;
        }
        self.accept(extended)
    }
}

const DTLS_HANDSHAKE_TIMEOUT: Duration = Duration::from_millis(8000);
const DTLS_RETRANSMIT_TIMEOUT: Duration = Duration::from_millis(500);

fn wrap_key_material(config: &SessionConfig) -> crate::dtls::DtlsSrtpKeys {
    let key_to_use = if config.client_wrap_key != [0; 32] {
        &config.client_wrap_key
    } else {
        &config.wrap_key
    };
    crate::dtls::derive_dtls_srtp_deterministic(key_to_use)
}

fn cipher_pair(
    keys: crate::dtls::DtlsSrtpKeys,
) -> Result<(Arc<SessionCipher>, Arc<SessionCipher>)> {
    Ok((
        Arc::new(SessionCipher::Obfs(Arc::new(ObfsCipher::new(
            keys.client_cipher_key,
        )?))),
        Arc::new(SessionCipher::Obfs(Arc::new(ObfsCipher::new(
            keys.server_cipher_key,
        )?))),
    ))
}

fn install_cipher_pair(shared: &TransportShared, pair: (Arc<SessionCipher>, Arc<SessionCipher>)) {
    shared.cipher.store(pair.0);
    shared.recv_cipher.store(pair.1);
}

async fn install_session_ciphers(
    shared: &Arc<TransportShared>,
    writer: &mut TransportWriter,
    reader: &mut TransportReader,
    config: &SessionConfig,
    events: &crate::events::Events,
) -> Result<()> {
    if !config.srtp_mode.uses_dtls() {
        install_cipher_pair(shared, cipher_pair(wrap_key_material(config))?);
        crate::log_error!(
            "[СЕССИЯ #{}] SRTP: {} (legacy WRAP-производные ключи)",
            config.id,
            config.srtp_mode.as_str()
        );
        return Ok(());
    }
    match run_dtls_handshake(writer, reader, config).await {
        Ok(keys) => {
            install_cipher_pair(shared, cipher_pair(keys)?);
            if config.id == 1 {
                crate::log_error!("[СЕССИЯ #{}] SRTP: DTLS-SRTP ✓", config.id);
            }
            events.dtls(config.id, "DTLS-хендшейк успешен (ECDSA P-256)");
            Ok(())
        }
        Err(error) => {
            events.dtls(config.id, &format!("рукопожатие не состоялось ({error:#})"));
            crate::log_error!("[СЕССИЯ #{}] DTLS-SRTP не состоялся ({error:#})", config.id);
            if config.srtp_mode.falls_back_to_wrap() {
                crate::log_error!(
                    "[СЕССИЯ #{}] SRTP: AUTO fallback -> WRAP-SRTP (legacy ключи)",
                    config.id
                );
                events.dtls(config.id, "AUTO fallback -> WRAP-SRTP");
                install_cipher_pair(shared, cipher_pair(wrap_key_material(config))?);
                Ok(())
            } else {
                Err(error)
            }
        }
    }
}

async fn run_dtls_handshake(
    writer: &mut TransportWriter,
    reader: &mut TransportReader,
    config: &SessionConfig,
) -> Result<crate::dtls::DtlsSrtpKeys> {
    let mut handshake = crate::dtls::DtlsClientHandshake::new()?;
    let mut flight = handshake.start()?;
    if config.id == 1 {
        crate::log_error!(
            "[СЕССИЯ #{}] DTLS: старт рукопожатия WebRTC (ClientHello {} байт)",
            config.id,
            flight.len()
        );
    }
    writer.send_bytes(&flight).await?;
    let started = tokio::time::Instant::now();
    let deadline = started + DTLS_HANDSHAKE_TIMEOUT;
    let mut retransmit_at = started + DTLS_RETRANSMIT_TIMEOUT;
    let mut retransmits = 0u32;
    let mut inbound_datagrams = 0u32;
    while !handshake.is_connected() {
        if tokio::time::Instant::now() >= deadline {
            crate::log_error!(
                "[СЕССИЯ #{}] DTLS: таймаут рукопожатия за {} мс (состояние {}, входящих {}, повторов {})",
                config.id,
                DTLS_HANDSHAKE_TIMEOUT.as_millis(),
                handshake_message_label(&handshake),
                inbound_datagrams,
                retransmits
            );
            bail!("DTLS handshake did not finish in time");
        }
        tokio::select! {
            biased;
            result = reader.recv() => {
                let packet = result?;
                inbound_datagrams += 1;
                let reply = match handshake.handle_datagram(packet.as_slice()) {
                    Ok(reply) => reply,
                    Err(err) => {
                        if config.id == 1 {
                            crate::log_error!(
                                "[СЕССИЯ #{}] DTLS: отброшен некорректный датаграмм ({err:#}), продолжаем ожидание",
                                config.id
                            );
                        }
                        None
                    }
                };
                match reply {
                    Some(bytes) => {
                        if config.id == 1 {
                            crate::log_error!(
                                "[СЕССИЯ #{}] DTLS: полёт ← сервер ({} байт, #{}) → ответ {} байт, состояние {}",
                                config.id,
                                packet.len(),
                                inbound_datagrams,
                                bytes.len(),
                                handshake_message_label(&handshake)
                            );
                        }
                        writer.send_bytes(&bytes).await?;
                        flight = bytes;
                        retransmits = 0;
                        retransmit_at = tokio::time::Instant::now() + DTLS_RETRANSMIT_TIMEOUT;
                    }
                    None => {
                        if config.id == 1 && inbound_datagrams <= 2 {
                            crate::log_error!(
                                "[СЕССИЯ #{}] DTLS: полёт ← сервер ({} байт, #{}), ответа нет, состояние {}",
                                config.id,
                                packet.len(),
                                inbound_datagrams,
                                handshake_message_label(&handshake)
                            );
                        }
                    }
                }
            }
            _ = tokio::time::sleep_until(retransmit_at) => {
                retransmits += 1;
                if config.id == 1 || retransmits >= 3 {
                    crate::log_error!(
                        "[СЕССИЯ #{}] DTLS: повтор последнего полёта #{} ({} байт, состояние {})",
                        config.id,
                        retransmits,
                        flight.len(),
                        handshake_message_label(&handshake)
                    );
                }
                writer.send_bytes(&flight).await?;
                retransmit_at = tokio::time::Instant::now() + DTLS_RETRANSMIT_TIMEOUT;
            }
        }
    }
    let keys = handshake
        .srtp_keys()
        .copied()
        .ok_or_else(|| anyhow!("DTLS handshake finished without SRTP keying material"))?;
    if config.id == 1 {
        crate::log_error!(
            "[СЕССИЯ #{}] DTLS: рукопожатие завершено за {} мс (входящих {}, повторов {}), профиль SRTP согласован, ключи установлены",
            config.id,
            started.elapsed().as_millis(),
            inbound_datagrams,
            retransmits
        );
    }
    Ok(keys)
}

fn handshake_message_label(handshake: &crate::dtls::DtlsClientHandshake) -> &'static str {
    match handshake.state() {
        crate::dtls::DtlsHandshakeState::Idle => "Idle",
        crate::dtls::DtlsHandshakeState::AwaitingServerFlight => "ждём полёт сервера",
        crate::dtls::DtlsHandshakeState::AwaitingServerFinished => "ждём Finished сервера",
        crate::dtls::DtlsHandshakeState::AwaitingClientFlight => "ждём полёт клиента",
        crate::dtls::DtlsHandshakeState::Connected => "Connected",
        crate::dtls::DtlsHandshakeState::Failed => "Failed",
    }
}

fn authenticate_inbound(
    cipher: &SessionCipher,
    config: &ObfsConfig,
    replay: &mut ReplayProtection,
    packet: &mut PacketBuf,
) -> bool {
    if cipher.is_plain() {
        return true;
    }
    if !is_rtp_packet(packet.as_slice()) {
        return false;
    }
    let Ok(sequence) = cipher.unwrap(packet, config.mode) else {
        return false;
    };
    replay.rtp.accept_rtp(sequence)
}

struct ActiveConnection {
    stats: Arc<Stats>,
    events: Events,
}

struct WorkerRegistration {
    dispatcher: Arc<Dispatcher>,
    id: usize,
    incarnation_id: u64,
}

impl WorkerRegistration {
    fn new(dispatcher: Arc<Dispatcher>, id: usize, incarnation_id: u64) -> Self {
        Self {
            dispatcher,
            id,
            incarnation_id,
        }
    }
}

impl Drop for WorkerRegistration {
    fn drop(&mut self) {
        self.dispatcher.unregister(self.id, self.incarnation_id);
    }
}

impl ActiveConnection {
    fn new(stats: Arc<Stats>, events: Events) -> Self {
        stats.active_connections.fetch_add(1, Ordering::Relaxed);
        Self { stats, events }
    }
}

impl Drop for ActiveConnection {
    fn drop(&mut self) {
        if self.stats.active_connections.fetch_sub(1, Ordering::AcqRel) == 1 {
            self.events.active_zero();
        }
    }
}

impl TransportWriter {
    fn new(shared: Arc<TransportShared>) -> Self {
        Self {
            shared,
            write_state: Arc::new(ObfsState::new()),
        }
    }

    async fn send_packet_duplicate(
        &mut self,
        mut packet: PacketBuf,
        duplicate: bool,
    ) -> Result<()> {
        let echo_packet = if duplicate {
            self.duplicate_plain_packet(&packet)
        } else {
            None
        };
        self.shared
            .cipher
            .load()
            .wrap(&mut packet, &self.shared.config, &self.write_state)?;
        self.shared.allocation.send(packet).await?;
        if let Some(mut copy) = echo_packet
            && self
                .shared
                .cipher
                .load()
                .wrap(&mut copy, &self.shared.config, &self.write_state)
                .is_ok()
        {
            let _ = self.shared.allocation.send(copy).await;
        }
        Ok(())
    }

    fn duplicate_plain_packet(&self, packet: &PacketBuf) -> Option<PacketBuf> {
        let mut copy = self.shared.pool.try_acquire()?;
        let source = packet.as_slice();
        let area = copy.read_area();
        if source.len() > area.len() {
            return None;
        }
        area[..source.len()].copy_from_slice(source);
        copy.set_read_len(source.len()).ok()?;
        Some(copy)
    }

    async fn send_bytes(&mut self, data: &[u8]) -> Result<()> {
        self.send_bytes_duplicate(data, false).await
    }

    async fn send_bytes_duplicate(&mut self, data: &[u8], duplicate: bool) -> Result<()> {
        let mut packet = self
            .shared
            .pool
            .try_acquire()
            .context("packet budget exhausted")?;
        if data.len() > packet.read_area().len() {
            bail!("transport payload too large: {}", data.len());
        }
        packet.read_area()[..data.len()].copy_from_slice(data);
        packet.set_read_len(data.len())?;
        self.send_packet_duplicate(packet, duplicate).await
    }
}

impl TransportReader {
    fn new(shared: Arc<TransportShared>, receiver: TurnReceiver) -> Self {
        Self {
            shared,
            receiver,
            replay: ReplayProtection::default(),
        }
    }

    async fn recv(&mut self) -> Result<PacketBuf> {
        loop {
            let mut packet = self
                .receiver
                .recv()
                .await
                .context("TURN allocation receive")?;
            let recv_cipher = self.shared.recv_cipher.load();
            if authenticate_inbound(
                &recv_cipher,
                &self.shared.config,
                &mut self.replay,
                &mut packet,
            ) {
                return Ok(packet);
            }
        }
    }
}

pub async fn run_session(
    config: SessionConfig,
    credentials: TurnCredentials,
    mut runtime: SessionRuntime,
) -> Result<bool> {
    if credentials.server_addresses.is_empty() {
        bail!("нет TURN URL в учётных данных");
    }
    let turn_address = select_turn_address(&credentials.server_addresses, &config)?;
    let turn_path = turn_path_key(turn_address, &config)?;
    let target_display = match (&config.turn_host, &config.turn_port) {
        (Some(host), Some(port)) => format!("{host}:{port}"),
        (Some(host), None) => format!("{host}:19302"),
        (None, _) => turn_address.to_string(),
    };
    crate::log_error!("[TURN] Подключение к {target_display}");
    let cancel = runtime.cancel.clone();
    let turn_host = config.turn_host.clone();
    let turn_port = config.turn_port.clone();
    let turn_transport = config.turn_transport;
    if let Some(started) = runtime.allocation_started.take() {
        let _ = started.send(());
    }
    let mut connect = Box::pin(TurnAllocation::connect(
        TurnConnectTarget {
            address: turn_address,
            override_host: turn_host.as_deref(),
            override_port: turn_port.as_deref(),
            transport_mode: turn_transport,
        },
        credentials.username,
        credentials.password,
        config.peer,
        runtime.pool.clone(),
    ));
    let allocation = tokio::select! {
        biased;
        result = &mut connect => result.map_err(TurnAllocateError)?,
        _ = cancel.cancelled() => {
            if let Ok(Ok(allocation)) = tokio::time::timeout(CONNECT_CANCEL_GRACE, &mut connect).await {
                let _ = tokio::time::timeout(DEALLOCATE_TIMEOUT, allocation.deallocate()).await;
            }
            return Ok(false);
        },
    };
    drop(connect);
    if let Some(ready) = runtime.allocation_ready.take() {
        let _ = ready.send(());
    }
    crate::log_error!("[СЕССИЯ #{}] Relay: {}", config.id, allocation.local_addr());
    let channel = tokio::select! {
        biased;
        result = allocation.prepare_channel() => result,
        _ = cancel.cancelled() => {
            let _ = tokio::time::timeout(DEALLOCATE_TIMEOUT, allocation.deallocate()).await;
            return Ok(false);
        },
    };
    if let Err(error) = channel {
        let _ = tokio::time::timeout(DEALLOCATE_TIMEOUT, allocation.deallocate()).await;
        return Err(error.context("TURN ChannelBind обязателен"));
    }
    let session = tokio::spawn(run_allocated_session(
        config,
        runtime,
        allocation.clone(),
        turn_path,
    ));
    let result = await_session_task(&cancel, session).await;
    let _ = tokio::time::timeout(DEALLOCATE_TIMEOUT, allocation.deallocate()).await;
    result
}

struct SessionTaskGuard(Option<JoinHandle<Result<bool>>>);

impl Drop for SessionTaskGuard {
    fn drop(&mut self) {
        if let Some(handle) = self.0.take() {
            handle.abort();
        }
    }
}

async fn await_session_task(
    cancel: &CancellationToken,
    session: JoinHandle<Result<bool>>,
) -> Result<bool> {
    let mut guard = SessionTaskGuard(Some(session));
    let Some(handle) = guard.0.as_mut() else {
        return Err(anyhow!("session handle missing"));
    };
    let result = tokio::select! {
        biased;
        res = &mut *handle => match res {
            Ok(res) => res,
            Err(error) if error.is_panic() => Err(anyhow!("паника сессии изолирована: {error}")),
            Err(error) => Err(anyhow!("задача сессии завершена аварийно: {error}")),
        },
        _ = cancel.cancelled() => {
            match tokio::time::timeout(SESSION_SHUTDOWN_GRACE, &mut *handle).await {
                Ok(Ok(res)) => res,
                Ok(Err(error)) if error.is_panic() => {
                    Err(anyhow!("session panicked during graceful shutdown: {error}"))
                }
                Ok(Err(error)) => {
                    Err(anyhow!("session stopped during graceful shutdown: {error}"))
                }
                Err(_) => {
                    if let Some(handle) = guard.0.take() {
                        handle.abort();
                        let _ = handle.await;
                    }
                    return Ok(false);
                }
            }
        }
    };
    guard.0 = None;
    result
}

async fn run_allocated_session(
    config: SessionConfig,
    mut runtime: SessionRuntime,
    allocation: Arc<TurnAllocation>,
    _turn_path: Arc<str>,
) -> Result<bool> {
    let session_cancel = CancellationToken::new();
    let turn_receiver = allocation.take_receiver()?;
    crate::log_error!(
        "[СЕССИЯ #{}] [DIRECT] Прямой режим обфускации ({:?})",
        config.id,
        config.mode
    );
    let shared = Arc::new(TransportShared {
        allocation: allocation.clone(),
        cipher: ArcSwap::new(Arc::new(SessionCipher::Plain)),
        recv_cipher: ArcSwap::new(Arc::new(SessionCipher::Plain)),
        config: ObfsConfig::new(config.mode),
        pool: runtime.pool.clone(),
    });
    let mut writer_transport = TransportWriter::new(shared.clone());
    let mut reader_transport = TransportReader::new(shared.clone(), turn_receiver);
    install_session_ciphers(
        &shared,
        &mut writer_transport,
        &mut reader_transport,
        &config,
        &runtime.events,
    )
    .await
    .context("инициализация SRTP")?;
    let incarnation_id = NEXT_INCARNATION_ID.fetch_add(1, Ordering::Relaxed).max(1);
    let config_tx = config.get_config.then(|| runtime.config_tx.clone());
    let mut routing_reassembler = RoutingReassembler::new();
    let config_delivered = match request_configuration(
        &mut writer_transport,
        &mut reader_transport,
        &config,
        &runtime.events,
        config_tx.clone(),
        &mut routing_reassembler,
        WorkerScale {
            notify: runtime.scale_notify.as_ref(),
            target: runtime.target_workers.as_ref(),
            configured: runtime.configured_workers,
        },
    )
    .await
    {
        Ok(delivered) => {
            let delivered = config.get_config && delivered;
            if let Some(state) = &runtime.config_delivery {
                state.complete(delivered);
            }
            delivered
        }
        Err(error) => {
            if let Some(state) = &runtime.config_delivery {
                state.complete(false);
            }
            return Err(error);
        }
    };
    if let Some(target) = &runtime.target_workers
        && config.id > target.load(Ordering::Acquire)
        && config.id > runtime.configured_workers
    {
        return Ok(false);
    }
    let worker_channels = WorkerChannels {
        id: config.id,
        incarnation_id,
        allocation: allocation.clone(),
        cipher: shared.cipher.load_full(),
        config: shared.config,
        write_state: writer_transport.write_state.clone(),
    };
    let (writer_command_tx, writer_command_rx) = mpsc::channel(WRITER_COMMAND_CAPACITY);
    let shutdown_registration = runtime
        .shutdown
        .register(incarnation_id, writer_command_tx.clone());
    let writer_activity = shutdown_registration.activity.clone();
    runtime.dispatcher.register(worker_channels.clone());
    let _registration =
        WorkerRegistration::new(runtime.dispatcher.clone(), config.id, incarnation_id);
    if let Some(ready_tx) = runtime.ready_tx.take() {
        let _ = ready_tx.send(());
    }
    crate::log_error!("[ВОРКЕР #{}] [READY] Поток готов ✓", config.id);
    runtime.events.ready(config.id);
    config.repair.mark_ready(config.id);
    let _active = ActiveConnection::new(runtime.stats.clone(), runtime.events.clone());
    let mut writer = tokio::spawn(writer_loop(
        writer_transport,
        WriterRuntime {
            commands: writer_command_rx,
            shutdown: runtime.shutdown.clone(),
            activity: writer_activity,
        },
        session_cancel.clone(),
    ));
    let mut reader = tokio::spawn(reader_loop(
        reader_transport,
        ReaderRuntime {
            dispatcher: runtime.dispatcher.clone(),
            events: runtime.events.clone(),
            repair: config.repair.clone(),
            shutdown: runtime.shutdown.clone(),
            config_tx: runtime.config_tx.clone(),
            routing_reassembler,
            scale_notify: runtime.scale_notify.clone(),
            target_workers: runtime.target_workers.clone(),
            configured_workers: runtime.configured_workers,
        },
        session_cancel.clone(),
    ));
    let repair_generation = config.repair.restart_generation(config.id);
    let (session_result, completed): (Result<()>, u8) = tokio::select! {
        biased;
        _ = runtime.cancel.cancelled() => {
            runtime
                .shutdown
                .request_disconnect(&config.password, &config.salt)
                .await;
            (Ok(()), 0)
        }
        _ = async {
            if let (Some(notify), Some(target)) = (&runtime.scale_notify, &runtime.target_workers) {
                loop {
                    let notified = notify.notified();
                    tokio::pin!(notified);
                    notified.as_mut().enable();
                    let current_target = target.load(Ordering::Acquire);
                    if config.id > current_target && config.id > runtime.configured_workers {
                        break;
                    }
                    notified.await;
                }
            } else {
                std::future::pending::<()>().await;
            }
        } => {
            (Ok(()), 0)
        }
        _ = config.repair.changed(config.id, repair_generation) => {
            (Err(anyhow!("TARGET_REPAIR")), 0)
        }
        result = &mut writer => {
            (result.map_err(anyhow::Error::from).and_then(|value| value), 1)
        }
        result = &mut reader => {
            (result.map_err(anyhow::Error::from).and_then(|value| value), 2)
        }
    };
    session_cancel.cancel();
    stop_session_tasks(completed, writer, reader).await;
    crate::log_error!("[СЕССИЯ #{}] Завершена", config.id);
    session_result?;
    Ok(config_delivered)
}

async fn stop_session_tasks(
    completed: u8,
    writer: JoinHandle<Result<()>>,
    reader: JoinHandle<Result<()>>,
) {
    match completed {
        0 => {
            writer.abort();
            reader.abort();
            let _ = writer.await;
            let _ = reader.await;
        }
        1 => {
            reader.abort();
            let _ = reader.await;
        }
        _ => {
            writer.abort();
            let _ = writer.await;
        }
    }
}

#[derive(Clone, Copy)]
struct WorkerScale<'a> {
    notify: Option<&'a Arc<tokio::sync::Notify>>,
    target: Option<&'a Arc<AtomicUsize>>,
    configured: usize,
}

async fn request_configuration(
    writer: &mut TransportWriter,
    reader: &mut TransportReader,
    config: &SessionConfig,
    events: &Events,
    config_tx: Option<mpsc::Sender<String>>,
    routing_reassembler: &mut RoutingReassembler,
    scale: WorkerScale<'_>,
) -> Result<bool> {
    let request = build_registration_payload(config);
    'attempts: for (attempt, timeout_ms) in CONFIG_RESPONSE_TIMEOUT_MS.into_iter().enumerate() {
        if !config.srtp_mode.uses_dtls()
            && config.client_wrap_key != [0; 32]
            && config.client_wrap_key != config.wrap_key
        {
            let key = if attempt % 2 == 0 {
                config.client_wrap_key
            } else {
                config.wrap_key
            };
            if let Ok(pair) = cipher_pair(crate::dtls::derive_dtls_srtp_deterministic(&key)) {
                install_cipher_pair(&writer.shared, pair);
            }
        }
        writer
            .send_bytes_duplicate(request.as_ref(), true)
            .await
            .context("отправка GETCONF")?;
        let deadline = tokio::time::Instant::now() + Duration::from_millis(timeout_ms);
        loop {
            match tokio::time::timeout_at(deadline, reader.recv()).await {
                Ok(result) => {
                    let packet = match result {
                        Ok(p) => p,
                        Err(e) => {
                            return Err(e.context("ошибка чтения ответа GETCONF"));
                        }
                    };
                    if is_panel_restart_notice(packet.as_slice()) {
                        events.panel_restart();
                        continue;
                    }
                    if packet.as_slice().starts_with(b"ROUTING:") {
                        if let Some(json) = routing_reassembler.feed(packet.as_slice()) {
                            events.routing(&json);
                        }
                        continue;
                    }
                    if packet.as_slice().starts_with(b"TUNWORKERS:")
                        || packet.as_slice().starts_with(b"WORKERS:")
                    {
                        if let Some((agreed, server_limit)) =
                            crate::protocol::parse_tunworkers_response(packet.as_slice())
                        {
                            crate::log_error!(
                                "[ВОРКЕР #{}] TUNWORKERS: согласовано {}, лимит сервера {}",
                                config.id,
                                agreed,
                                server_limit
                            );
                            events.workers(server_limit as u32);
                            apply_workers_limit(agreed, scale.target, scale.notify);
                        }
                        continue;
                    }
                    if !is_config_response(packet.as_slice()) {
                        continue;
                    }
                    match parse_config_response(packet.as_slice()) {
                        Ok(ConfigResponse::NoConfig) => {
                            if config.get_config {
                                events.error(
                                    "handshake_no_config",
                                    "сервер не выдал TUNCONF для слота; проверьте адресный пул",
                                    true,
                                );
                                bail!("сервер вернул NOCONF вместо TUNCONF");
                            }
                            return Ok(false);
                        }
                        Ok(ConfigResponse::Config(value)) => {
                            let _ = writer.send_bytes(b"READY").await;
                            if value.contains(":stream-v1") || value.contains(":stream-v2") {
                                config.server_stream_repair.store(true, Ordering::Release);
                            }
                            if let Some(agreed) = parse_workers_from_tunconf(&value) {
                                apply_workers_limit(agreed, scale.target, scale.notify);
                            }
                            if let Some(sender) = &config_tx {
                                let _ = sender.try_send(value);
                            }
                            crate::log_error!("[ВОРКЕР #{}] Конфиг получен", config.id);
                            if config.get_config {
                                let req = crate::protocol::workers_request(
                                    &config.password,
                                    scale.configured,
                                );
                                let _ = writer.send_bytes(req.as_bytes()).await;
                            }
                            return Ok(true);
                        }
                        Err(error) => {
                            let message = error.to_string();
                            if config.get_config && message.contains("FATAL_") {
                                events.error("handshake_rejected", &message, true);
                                bail!("{message}");
                            }
                            crate::log_error!(
                                "[ВОРКЕР #{}] Нефатальная ошибка разбора TUNCONF: {message}, продолжаем ожидание",
                                config.id
                            );
                            continue;
                        }
                    }
                }
                Err(_) if attempt + 1 < CONFIG_RESPONSE_TIMEOUT_MS.len() => continue 'attempts,
                Err(_) => {
                    bail!(configuration_timeout_message())
                }
            }
        }
    }
    bail!("GETCONF ответ не получен")
}

async fn writer_loop(
    mut transport: TransportWriter,
    runtime: WriterRuntime,
    cancel: CancellationToken,
) -> Result<()> {
    let WriterRuntime {
        mut commands,
        shutdown,
        activity,
    } = runtime;
    let keepalive_interval = Duration::from_secs(10);
    loop {
        let command = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(()),
            command = commands.recv() => {
                let Some(command) = command else {
                    return Ok(());
                };
                Some(command)
            }
            _ = tokio::time::sleep(keepalive_interval) => {
                None
            }
        };
        match command {
            Some(cmd) => {
                handle_writer_command(&mut transport, cmd).await?;
                shutdown.mark_activity(&activity);
            }
            None => {
                let _ = transport.send_bytes(&[0xff; 4]).await;
            }
        }
    }
}

async fn handle_writer_command(
    transport: &mut TransportWriter,
    command: WriterCommand,
) -> Result<()> {
    match command {
        WriterCommand::SendBytes { data, completion } => {
            let result = transport.send_bytes(&data).await;
            let _ = completion.send(result);
            Ok(())
        }
    }
}

async fn send_writer_bytes(sender: &mpsc::Sender<WriterCommand>, data: &[u8]) -> Result<()> {
    let (completion, result) = oneshot::channel();
    sender
        .try_send(WriterCommand::SendBytes {
            data: Bytes::copy_from_slice(data),
            completion,
        })
        .context("writer command queue closed")?;
    result.await.context("writer command response closed")?
}

async fn reader_loop(
    mut transport: TransportReader,
    runtime: ReaderRuntime,
    cancel: CancellationToken,
) -> Result<()> {
    let ReaderRuntime {
        dispatcher,
        events,
        repair,
        shutdown,
        config_tx,
        mut routing_reassembler,
        scale_notify,
        target_workers,
        configured_workers,
    } = runtime;
    let mut dropped_non_ip = 0u64;
    loop {
        let packet = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(()),
            result = transport.recv() => result?,
        };
        if is_panel_restart_notice(packet.as_slice()) {
            events.panel_restart();
            continue;
        }
        if let Some(command) = parse_stream_repair(packet.as_slice()) {
            let max_workers = target_workers
                .as_ref()
                .map(|t| t.load(Ordering::Acquire))
                .unwrap_or(configured_workers);
            let result = repair.apply_repair(&command, max_workers);
            if result.restarts != 0 {
                crate::log_error!(
                    "[REPAIR] Сервер запросил перезапуск потоков {:?}, sequence {}, resets {}",
                    command.worker_ids,
                    command.sequence,
                    result.credential_resets
                );
            }
            continue;
        }
        if let Some(command) = parse_stream_alive(packet.as_slice()) {
            let max_workers = target_workers
                .as_ref()
                .map(|t| t.load(Ordering::Acquire))
                .unwrap_or(configured_workers);
            repair.apply_alive(&command, max_workers);
            continue;
        }
        if shutdown.observe_control_response(packet.as_slice()) {
            continue;
        }
        if packet.as_slice().starts_with(b"TUNCONF:") {
            if let Ok(ConfigResponse::Config(config)) = parse_config_response(packet.as_slice()) {
                if let Some(agreed) = parse_workers_from_tunconf(&config) {
                    apply_workers_limit(agreed, target_workers.as_ref(), scale_notify.as_ref());
                }
                let _ = config_tx.try_send(config);
            }
            continue;
        }
        if packet.as_slice().starts_with(b"ROUTING:") {
            if let Some(json) = routing_reassembler.feed(packet.as_slice()) {
                events.routing(&json);
            }
            continue;
        }
        if packet.as_slice().starts_with(b"TUNWORKERS:")
            || packet.as_slice().starts_with(b"WORKERS:")
        {
            if let Some((agreed, server_limit)) =
                crate::protocol::parse_tunworkers_response(packet.as_slice())
            {
                crate::log_error!(
                    "[СЕССИЯ] TUNWORKERS: согласовано {}, лимит сервера {}",
                    agreed,
                    server_limit
                );
                events.workers(server_limit as u32);
                apply_workers_limit(agreed, target_workers.as_ref(), scale_notify.as_ref());
            }
            continue;
        }
        if is_control_response(packet.as_slice()) {
            continue;
        }
        if packet.as_slice().first().copied() == Some(crate::wire_protocol::CSQTT_FRAG_MAGIC) {
            dispatcher.handle_incoming_fragment(packet.as_slice());
            continue;
        }
        if !is_ip_packet(packet.as_slice()) {
            dropped_non_ip += 1;
            if dropped_non_ip <= 8 || dropped_non_ip.is_multiple_of(1000) {
                crate::log_error!(
                    "[СЕССИЯ] Отброшен не-IP пакет от пира #{}: {} байт, первый байт 0x{:02X}",
                    dropped_non_ip,
                    packet.len(),
                    packet.as_slice().first().copied().unwrap_or_default()
                );
            }
            continue;
        }
        dispatcher.return_packet(packet);
    }
}

fn is_ip_packet(packet: &[u8]) -> bool {
    is_plain_ip_packet(packet) || (packet.len() > 24 && is_plain_ip_packet(&packet[24..]))
}

fn is_plain_ip_packet(packet: &[u8]) -> bool {
    match packet.first().map(|byte| byte >> 4) {
        Some(4) => {
            if packet.len() < 20 {
                return false;
            }
            let header_len = usize::from(packet[0] & 0x0f) * 4;
            (20..=packet.len()).contains(&header_len)
        }
        Some(6) => packet.len() >= 40,
        _ => false,
    }
}

fn turn_endpoint_index(id: usize, cursor: usize, endpoint_count: usize) -> usize {
    debug_assert!(endpoint_count > 0);
    (id % endpoint_count + cursor % endpoint_count) % endpoint_count
}

fn select_turn_address<'a>(addresses: &'a [Arc<str>], config: &SessionConfig) -> Result<&'a str> {
    let count = addresses.len();
    let start = turn_endpoint_index(config.id, config.turn_endpoint_cursor, count);
    for offset in 0..count {
        let address = addresses[(start + offset) % count].as_ref();
        if resolve_turn_endpoints(
            address,
            config.turn_host.as_deref(),
            config.turn_port.as_deref(),
            config.turn_transport,
        )
        .is_ok()
        {
            return Ok(address);
        }
    }
    bail!("Нет TURN endpoint для выбранного транспорта")
}

fn turn_path_key(address: &str, config: &SessionConfig) -> Result<Arc<str>> {
    let endpoint = resolve_turn_endpoints(
        address,
        config.turn_host.as_deref(),
        config.turn_port.as_deref(),
        config.turn_transport,
    )
    .context("TURN endpoint is invalid")?
    .into_iter()
    .next()
    .ok_or_else(|| anyhow!("TURN endpoint list is empty"))?;
    Ok(Arc::from(endpoint.socket_authority()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Result;
    use proptest::prelude::*;
    use std::collections::HashSet;
    use std::future::pending;
    use std::time::Instant;

    #[test]
    fn transport_selection_skips_incompatible_turn_endpoints() {
        let addresses: Arc<[Arc<str>]> = [
            Arc::from("turn:relay.example:3478?transport=udp"),
            Arc::from("turn:relay.example:3478?transport=tcp"),
        ]
        .into();
        let config = SessionConfig {
            id: 0,
            peer: "127.0.0.1:46000".parse().unwrap(),
            turn_host: None,
            turn_port: None,
            turn_transport: TurnTransportMode::TcpTls,
            srtp_mode: SrtpMode::Auto,
            local_port: Arc::from("9000"),
            password: Arc::from("pass1234"),
            generation: 0,
            turn_endpoint_cursor: 0,
            salt: Arc::from(""),
            mode: ObfsMode::Audio,
            wrap_key: [0; 32],
            client_wrap_key: [0; 32],
            get_config: false,
            server_stream_repair: Arc::new(AtomicBool::new(false)),
            repair: RepairState::new(18),
            configured_workers: 18,
        };
        assert_eq!(
            select_turn_address(&addresses, &config).unwrap(),
            "turn:relay.example:3478?transport=tcp"
        );
    }

    #[test]
    fn first_registration_announces_the_full_stream_set_before_the_feature_reply() {
        let config = SessionConfig {
            id: 1,
            peer: "127.0.0.1:46000".parse().unwrap(),
            turn_host: None,
            turn_port: None,
            turn_transport: TurnTransportMode::Udp,
            srtp_mode: SrtpMode::Auto,
            local_port: Arc::from("9000"),
            password: Arc::from("pass1234"),
            generation: 7,
            turn_endpoint_cursor: 0,
            salt: Arc::from("generation-id"),
            mode: ObfsMode::Audio,
            wrap_key: [0; 32],
            client_wrap_key: [0; 32],
            get_config: true,
            server_stream_repair: Arc::new(AtomicBool::new(false)),
            repair: RepairState::new(9),
            configured_workers: 18,
        };
        assert_eq!(
            build_registration_payload(&config).as_ref(),
            b"GETCONF:9000|pass1234|7|generation-id|1|18"
        );
    }

    #[tokio::test]
    async fn shutdown_uses_the_most_recently_active_control_stream_once() {
        let shutdown = Arc::new(ShutdownCoordinator::new());
        let (older_tx, mut older_rx) = mpsc::channel(1);
        let older = shutdown.register(1, older_tx);
        let (newer_tx, mut newer_rx) = mpsc::channel(1);
        let newer = shutdown.register(2, newer_tx);
        shutdown.mark_activity(&older.activity);

        let coordinator = shutdown.clone();
        let task = tokio::spawn(async move {
            coordinator.request_disconnect("device", "salt").await;
        });
        let command = tokio::time::timeout(Duration::from_millis(100), older_rx.recv())
            .await
            .unwrap_or(None);
        let completion = match command {
            Some(WriterCommand::SendBytes { data, completion }) => {
                assert_eq!(data.as_ref(), b"DISCONNECT:device|salt");
                completion
            }
            None => panic!("shutdown did not select the active control stream"),
        };
        assert!(completion.send(Ok(())).is_ok());
        assert!(shutdown.observe_control_response(b"OK:disconnected"));
        assert!(
            tokio::time::timeout(Duration::from_secs(1), task)
                .await
                .is_ok()
        );
        assert!(newer_rx.try_recv().is_err());
        drop(older);
        drop(newer);
    }

    #[tokio::test]
    async fn shutdown_never_uses_a_dropped_control_stream() {
        let shutdown = Arc::new(ShutdownCoordinator::new());
        let (stale_tx, mut stale_rx) = mpsc::channel(1);
        let stale = shutdown.register(1, stale_tx);
        drop(stale);
        let (active_tx, mut active_rx) = mpsc::channel(1);
        let active = shutdown.register(2, active_tx);

        let coordinator = shutdown.clone();
        let task = tokio::spawn(async move {
            coordinator.request_disconnect("device", "salt").await;
        });
        let command = tokio::time::timeout(Duration::from_millis(100), active_rx.recv())
            .await
            .unwrap_or(None);
        let completion = match command {
            Some(WriterCommand::SendBytes { completion, .. }) => completion,
            None => panic!("shutdown did not select a live control stream"),
        };
        assert!(completion.send(Ok(())).is_ok());
        assert!(shutdown.observe_control_response(b"OK:disconnected"));
        assert!(
            tokio::time::timeout(Duration::from_secs(1), task)
                .await
                .is_ok()
        );
        assert!(stale_rx.try_recv().is_err());
        drop(active);
    }

    #[derive(Clone, Copy, Default)]
    struct ReplayCoverage {
        duplicate: usize,
        age_16383: usize,
        age_16384: usize,
        age_16385: usize,
        forward_small: usize,
        forward_large: usize,
    }

    impl ReplayCoverage {
        fn complete(self) -> bool {
            self.duplicate > 0
                && self.age_16383 > 0
                && self.age_16384 > 0
                && self.age_16385 > 0
                && self.forward_small > 0
                && self.forward_large > 0
        }
    }

    struct DropFlag(Arc<AtomicBool>);

    fn model_accept(
        highest: &mut Option<u64>,
        seen: &mut HashSet<u64>,
        counter: u64,
        model_window: u64,
    ) -> bool {
        match *highest {
            None => {
                *highest = Some(counter);
                seen.insert(counter);
                true
            }
            Some(current) if counter > current => {
                *highest = Some(counter);
                seen.retain(|value| counter - *value < model_window);
                seen.insert(counter);
                true
            }
            Some(current) if current - counter >= model_window => false,
            Some(_) => seen.insert(counter),
        }
    }

    fn replay_trace_matches(counters: &[u64], model_window: u64) -> bool {
        let mut window = ReplayWindow::default();
        let mut highest = None;
        let mut seen = HashSet::new();
        for &counter in counters {
            let expected = model_accept(&mut highest, &mut seen, counter, model_window);
            if window.accept(counter) != expected {
                return false;
            }
        }
        true
    }

    fn replay_trace_coverage(counters: &[u64]) -> ReplayCoverage {
        let mut highest = None;
        let mut seen = HashSet::new();
        let mut coverage = ReplayCoverage::default();
        for &counter in counters {
            if seen.contains(&counter) {
                coverage.duplicate += 1;
            }
            if let Some(current) = highest {
                if counter > current {
                    let delta = counter - current;
                    if delta < 16384 {
                        coverage.forward_small += 1;
                    } else {
                        coverage.forward_large += 1;
                    }
                } else {
                    match current - counter {
                        16383 => coverage.age_16383 += 1,
                        16384 => coverage.age_16384 += 1,
                        16385 => coverage.age_16385 += 1,
                        _ => {}
                    }
                }
            }
            model_accept(&mut highest, &mut seen, counter, 16384);
        }
        coverage
    }

    fn extend_rtp_reference(highest: u64, sequence: u16) -> u64 {
        let base = highest & !(u16::MAX as u64);
        let current = base | u64::from(sequence);
        let previous = current.checked_sub(1 << 16);
        let next = current.saturating_add(1 << 16);
        let mut selected = current;
        let mut distance = current.abs_diff(highest);
        if let Some(previous) = previous {
            let candidate_distance = previous.abs_diff(highest);
            if candidate_distance < distance {
                selected = previous;
                distance = candidate_distance;
            }
        }
        if next.abs_diff(highest) < distance {
            selected = next;
        }
        selected
    }

    fn rtp_trace_matches(sequences: &[u16]) -> bool {
        let mut window = ReplayWindow::default();
        let mut highest = None;
        let mut seen = HashSet::new();
        for &sequence in sequences {
            let extended = highest
                .map(|value| extend_rtp_reference(value, sequence))
                .unwrap_or(u64::from(sequence));
            let expected = model_accept(&mut highest, &mut seen, extended, 16384);
            if window.accept_rtp(sequence) != expected {
                return false;
            }
        }
        true
    }

    fn mix64(mut value: u64) -> u64 {
        value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
        value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        value ^ (value >> 31)
    }

    fn deterministic_replay_trace(seed: u64, length: usize) -> Vec<u64> {
        let mut state = seed;
        let mut counters = Vec::with_capacity(length);
        let prefix = [1_000, 1_000, 1_001, 17_385, 1_002, 1_001, 1_000, 25_000];
        counters.extend(prefix.into_iter().take(length));
        let mut highest = (seed & 0xffff).max(counters.iter().copied().max().unwrap_or(0));
        for _ in counters.len()..length {
            state = mix64(state);
            let counter = match state % 8 {
                0 => highest,
                1 => highest.saturating_sub(16383),
                2 => highest.saturating_sub(16384),
                3 => highest.saturating_sub(16385),
                4 => highest.saturating_sub(state % 512),
                5 => {
                    highest = highest.saturating_add(1 + (state >> 8) % 4);
                    highest
                }
                6 => {
                    highest = highest.saturating_add(16384 + (state >> 8) % 4_096);
                    highest
                }
                _ => state & 0x0000_ffff_ffff_ffff,
            };
            highest = highest.max(counter);
            counters.push(counter);
        }
        counters
    }

    #[test]
    fn replay_window_accepts_reordering_once_and_rejects_duplicates() {
        let mut window = ReplayWindow::default();
        assert!(window.accept(100));
        assert!(window.accept(102));
        assert!(window.accept(101));
        assert!(!window.accept(101));
        assert!(!window.accept(100));
        assert!(window.accept(230));
        assert!(!window.accept(102));
    }

    #[test]
    fn replay_window_handles_rtp_wrap_and_late_packets() {
        let mut window = ReplayWindow::default();
        for sequence in [65_534, 65_535, 0, 2, 1, 3] {
            assert!(window.accept_rtp(sequence));
        }
        assert!(!window.accept_rtp(0));
        assert!(!window.accept_rtp(65_535));
        assert_eq!(window.highest, Some(65_539));
    }

    #[test]
    fn replay_window_is_bounded_under_million_packet_attack() {
        let mut window = ReplayWindow::default();
        for counter in 0..1_000_000 {
            assert!(window.accept(counter));
            assert!(!window.accept(counter));
        }
        assert_eq!(std::mem::size_of_val(&window), 24);
    }

    proptest! {
        #[test]
        fn replay_window_matches_independent_set_model(
            counters in proptest::collection::vec(any::<u32>(), 1..=5_000)
        ) {
            let counters = counters.into_iter().map(u64::from).collect::<Vec<_>>();
            prop_assert!(replay_trace_matches(&counters, 16384));
        }

        #[test]
        fn rtp_replay_window_matches_independent_extended_sequence_model(
            sequences in proptest::collection::vec(any::<u16>(), 1..=2_000)
        ) {
            prop_assert!(rtp_trace_matches(&sequences));
        }
    }

    #[test]
    fn replay_oracle_detects_window_off_by_one_mutation() {
        let counters = [100, 16484, 101];
        assert!(replay_trace_matches(&counters, 16384));
        assert!(!replay_trace_matches(&counters, 16383));
    }

    #[test]
    fn deterministic_replay_fault_generator_is_reproducible_and_hits_boundaries() {
        let first = deterministic_replay_trace(0x1234_5678_9abc_def0, 4_096);
        let second = deterministic_replay_trace(0x1234_5678_9abc_def0, 4_096);
        let different = deterministic_replay_trace(0x1234_5678_9abc_def1, 4_096);
        assert_eq!(first, second);
        assert_ne!(first, different);
        assert!(first.windows(2).any(|pair| pair[0] == pair[1]));
        assert!(replay_trace_coverage(&first).complete());
    }

    #[test]
    fn replay_coverage_oracle_rejects_each_missing_boundary() {
        let complete = ReplayCoverage {
            duplicate: 1,
            age_16383: 1,
            age_16384: 1,
            age_16385: 1,
            forward_small: 1,
            forward_large: 1,
        };
        assert!(complete.complete());
        for index in 0..6 {
            let mut mutated = complete;
            match index {
                0 => mutated.duplicate = 0,
                1 => mutated.age_16383 = 0,
                2 => mutated.age_16384 = 0,
                3 => mutated.age_16385 = 0,
                4 => mutated.forward_small = 0,
                _ => mutated.forward_large = 0,
            }
            assert!(!mutated.complete());
        }
    }

    #[test]
    fn rtp_replay_reference_survives_multiple_wraps_duplicates_and_late_packets() {
        let mut sequence = 65_000u16;
        let mut trace = Vec::with_capacity(20_000);
        for index in 0..10_000 {
            sequence = sequence.wrapping_add(97);
            trace.push(sequence);
            if index % 7 == 0 {
                trace.push(sequence);
            }
            if index % 11 == 0 {
                trace.push(sequence.wrapping_sub(16383));
                trace.push(sequence.wrapping_sub(16384));
                trace.push(sequence.wrapping_sub(16385));
            }
        }
        assert!(rtp_trace_matches(&trace));
    }

    #[test]
    #[ignore = "explicit deterministic stability soak"]
    fn deterministic_replay_chaos_soak() {
        let seconds = std::env::var("CSQTT_SOAK_SECONDS")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(120)
            .max(1);
        let first_seed = std::env::var("CSQTT_SOAK_SEED")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .unwrap_or(0);
        let counters_per_seed = std::env::var("CSQTT_REPLAY_SOAK_COUNTERS")
            .ok()
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or(4_096)
            .max(8);
        let started = Instant::now();
        let mut offset = 0u64;
        loop {
            let seed = first_seed.wrapping_add(offset);
            let counters = deterministic_replay_trace(seed, counters_per_seed);
            assert!(
                replay_trace_matches(&counters, 16384)
                    && replay_trace_coverage(&counters).complete(),
                "replay window diverged at reproducible seed {seed}"
            );
            offset = offset.wrapping_add(1);
            if started.elapsed() >= Duration::from_secs(seconds) {
                break;
            }
        }
    }

    impl Drop for DropFlag {
        fn drop(&mut self) {
            self.0.store(true, Ordering::Release);
        }
    }

    #[tokio::test]
    async fn completed_writer_is_not_polled_twice() {
        let mut writer = tokio::spawn(async { Ok(()) });
        let reader = tokio::spawn(async {
            pending::<()>().await;
            Ok(())
        });

        let result: Result<()> = (&mut writer).await.unwrap();
        result.unwrap();
        stop_session_tasks(1, writer, reader).await;
    }

    #[tokio::test]
    async fn completed_reader_is_not_polled_twice() {
        let writer = tokio::spawn(async {
            pending::<()>().await;
            Ok(())
        });
        let mut reader = tokio::spawn(async { Ok(()) });

        let result: Result<()> = (&mut reader).await.unwrap();
        result.unwrap();
        stop_session_tasks(2, writer, reader).await;
    }

    #[tokio::test]
    async fn cancelled_session_aborts_every_child_task() {
        let writer = tokio::spawn(async {
            pending::<()>().await;
            Ok(())
        });
        let reader = tokio::spawn(async {
            pending::<()>().await;
            Ok(())
        });

        tokio::time::timeout(
            Duration::from_secs(1),
            stop_session_tasks(0, writer, reader),
        )
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn panicked_writer_handle_is_consumed_only_once() {
        let mut writer = tokio::spawn(async { panic!("injected writer failure") });
        let reader = tokio::spawn(async {
            pending::<()>().await;
            Ok(())
        });

        assert!((&mut writer).await.unwrap_err().is_panic());
        stop_session_tasks(1, writer, reader).await;
    }

    #[tokio::test]
    async fn cancellation_aborts_and_awaits_pending_session_startup() {
        let dropped = Arc::new(AtomicBool::new(false));
        let task_dropped = dropped.clone();
        let session = tokio::spawn(async move {
            let _flag = DropFlag(task_dropped);
            pending::<()>().await;
            Ok(false)
        });
        tokio::task::yield_now().await;
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(!await_session_task(&cancel, session).await.unwrap());
        assert!(dropped.load(Ordering::Acquire));
    }

    #[test]
    fn plaintext_corruption_and_replay_never_authenticate() {
        let pool = PacketPool::new(5);
        let cipher = SessionCipher::Obfs(Arc::new(ObfsCipher::new([0x52; 32]).unwrap()));
        let config = ObfsConfig::new(ObfsMode::Video);
        let mut replay = ReplayProtection::default();
        let mut encoded = pool.acquire();
        encoded.read_area()[..7].copy_from_slice(b"payload");
        encoded.set_read_len(7).unwrap();
        cipher
            .wrap(&mut encoded, &config, &ObfsState::new())
            .unwrap();
        let wire = encoded.as_slice().to_vec();
        drop(encoded);

        let mut plaintext = pool.acquire();
        plaintext.read_area()[..6].copy_from_slice(b"DENIED");
        plaintext.set_read_len(6).unwrap();
        assert!(!authenticate_inbound(
            &cipher,
            &config,
            &mut replay,
            &mut plaintext
        ));

        let mut corrupt_wire = wire.clone();
        let last = corrupt_wire.len() - 1;
        corrupt_wire[last] ^= 0x80;
        let mut corrupt = pool.acquire();
        corrupt.read_area()[..corrupt_wire.len()].copy_from_slice(&corrupt_wire);
        corrupt.set_read_len(corrupt_wire.len()).unwrap();
        assert!(!authenticate_inbound(
            &cipher,
            &config,
            &mut replay,
            &mut corrupt
        ));

        let mut valid = pool.acquire();
        valid.read_area()[..wire.len()].copy_from_slice(&wire);
        valid.set_read_len(wire.len()).unwrap();
        assert!(authenticate_inbound(
            &cipher,
            &config,
            &mut replay,
            &mut valid
        ));
        assert_eq!(valid.as_slice(), b"payload");

        let mut replayed = pool.acquire();
        replayed.read_area()[..wire.len()].copy_from_slice(&wire);
        replayed.set_read_len(wire.len()).unwrap();
        assert!(!authenticate_inbound(
            &cipher,
            &config,
            &mut replay,
            &mut replayed
        ));
    }

    #[tokio::test]
    async fn cancellation_waits_for_graceful_session_exit() {
        let cancel = CancellationToken::new();
        let exited = Arc::new(AtomicBool::new(false));
        let task_cancel = cancel.clone();
        let task_exited = exited.clone();
        let session = tokio::spawn(async move {
            task_cancel.cancelled().await;
            tokio::time::sleep(Duration::from_millis(25)).await;
            task_exited.store(true, Ordering::Release);
            Ok(false)
        });
        cancel.cancel();
        assert!(!await_session_task(&cancel, session).await.unwrap());
        assert!(exited.load(Ordering::Acquire));
    }

    #[test]
    fn transport_shutdown_token_is_independent() {
        let global = CancellationToken::new();
        let transport = CancellationToken::new();
        global.cancel();
        assert!(!transport.is_cancelled());
        transport.cancel();
        assert!(transport.is_cancelled());
    }

    #[test]
    fn endpoint_rotation_is_local_cyclic_and_overflow_safe() {
        for endpoint_count in 1..=8 {
            for id in 1..=100 {
                let selected = (0..endpoint_count)
                    .map(|cursor| turn_endpoint_index(id, cursor, endpoint_count))
                    .collect::<std::collections::HashSet<_>>();
                assert_eq!(selected.len(), endpoint_count);
                assert!(selected.iter().all(|endpoint| *endpoint < endpoint_count));
                assert_eq!(
                    turn_endpoint_index(id, usize::MAX, endpoint_count),
                    (id % endpoint_count + usize::MAX % endpoint_count) % endpoint_count
                );
            }
        }
    }

    #[test]
    fn turn_allocate_error_exposes_nested_io_source() {
        let error = anyhow::Error::new(TurnAllocateError(anyhow::Error::new(
            std::io::Error::from_raw_os_error(101),
        )));
        assert!(error.chain().any(|cause| {
            cause
                .downcast_ref::<std::io::Error>()
                .is_some_and(|error| error.raw_os_error() == Some(101))
        }));
    }

    #[test]
    fn turn_allocate_error_exposes_structured_stun_code() {
        let error = TurnAllocateError(anyhow::Error::new(TurnRequestError::new(3, 0, 486)));
        assert_eq!(error.stun_code(), Some(486));
    }

    #[test]
    fn successful_config_delivery_is_sticky() {
        let sent = Arc::new(AtomicBool::new(false));
        let in_flight = Arc::new(AtomicBool::new(true));
        ConfigDeliveryState {
            sent: sent.clone(),
            in_flight: in_flight.clone(),
        }
        .complete(true);
        assert!(sent.load(Ordering::Acquire));
        assert!(!in_flight.load(Ordering::Acquire));
    }

    #[test]
    fn failed_config_delivery_can_be_retried() {
        let sent = Arc::new(AtomicBool::new(false));
        let in_flight = Arc::new(AtomicBool::new(true));
        ConfigDeliveryState {
            sent: sent.clone(),
            in_flight: in_flight.clone(),
        }
        .complete(false);
        assert!(!sent.load(Ordering::Acquire));
        assert!(!in_flight.load(Ordering::Acquire));
    }

    #[test]
    fn configuration_timeout_is_recoverable() {
        let message = configuration_timeout_message();
        assert!(message.contains("GETCONF"));
        assert!(message.contains("timeout"));
        assert!(!message.contains("FATAL_"));
    }

    #[test]
    fn ip_filter_accepts_valid_packets() {
        let mut ipv4 = vec![0u8; 60];
        ipv4[0] = 0x45;
        assert!(is_ip_packet(&ipv4));
        ipv4[0] = 0x4f;
        assert!(is_ip_packet(&ipv4));
        let mut ipv6 = vec![0u8; 40];
        ipv6[0] = 0x60;
        assert!(is_ip_packet(&ipv6));
    }

    #[test]
    fn ip_filter_accepts_plain_packet_whose_seq_byte_looks_like_a_version() {
        for seq_byte in [0x40u8, 0x4f, 0x50, 0x60, 0x6f] {
            let mut ipv4 = vec![0u8; 60];
            ipv4[0] = 0x45;
            ipv4[24] = seq_byte;
            assert!(
                is_ip_packet(&ipv4),
                "plain IPv4 with TCP seq byte 0x{seq_byte:02X} must not be dropped"
            );
        }
    }

    #[test]
    fn ip_filter_rejects_server_control_bytes() {
        assert!(!is_ip_packet(&[0xff]));
        assert!(!is_ip_packet(&[0xff, 0x00, 0x00, 0x00]));
        assert!(!is_ip_packet(&[]));
        assert!(!is_ip_packet(&[0x55; 60]));
        assert!(!is_ip_packet(&[0x00; 60]));
    }

    #[test]
    fn ip_filter_rejects_truncated_headers() {
        assert!(!is_ip_packet(&[0x45, 0x00, 0x00, 0x14]));
        let mut ipv4 = vec![0u8; 24];
        ipv4[0] = 0x4f;
        assert!(!is_ip_packet(&ipv4));
        let mut ipv4 = vec![0u8; 40];
        ipv4[0] = 0x40;
        assert!(!is_ip_packet(&ipv4));
        let mut ipv6 = vec![0u8; 39];
        ipv6[0] = 0x60;
        assert!(!is_ip_packet(&ipv6));
    }

    #[test]
    fn test_routing_reassembler_out_of_order_and_gzip() {
        use base64::Engine;
        use flate2::Compression;
        use flate2::write::GzEncoder;
        use std::io::Write;

        let original = r#"{"mode":"whitelist","domains":["test.com"],"ips":["1.2.3.4"]}"#;
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(original.as_bytes()).unwrap();
        let compressed = enc.finish().unwrap();
        let b64 = base64::engine::general_purpose::STANDARD.encode(&compressed);

        let part1 = &b64[..b64.len() / 3];
        let part2 = &b64[b64.len() / 3..2 * b64.len() / 3];
        let part3 = &b64[2 * b64.len() / 3..];

        let mut reassembler = RoutingReassembler::new();
        assert_eq!(
            reassembler.feed(format!("ROUTING:2:3:{}", part2).as_bytes()),
            None
        );
        assert_eq!(reassembler.feed(b"ROUTING:FINISH:3"), None);
        assert_eq!(reassembler.feed(b"ROUTING:START:3"), None);
        assert_eq!(
            reassembler.feed(format!("ROUTING:3:3:{}", part3).as_bytes()),
            None
        );
        let res = reassembler.feed(format!("ROUTING:1:3:{}", part1).as_bytes());
        assert_eq!(res, Some(original.to_string()));
    }

    #[test]
    fn test_routing_reassembler_corrupted_payload() {
        let mut reassembler = RoutingReassembler::new();
        assert_eq!(reassembler.feed(b"ROUTING:START:1"), None);
        assert_eq!(reassembler.feed(b"ROUTING:1:1:not-valid-base64-!!!"), None);
        assert_eq!(reassembler.feed(b"ROUTING:FINISH:1"), None);
    }

    #[test]
    fn test_wrap_key_derivation_isolation() {
        let key_device = crate::wrap::derive_wrap_key("device-unique-1234").unwrap();
        let key_client = crate::wrap::derive_wrap_key("client99").unwrap();
        assert_ne!(key_device, key_client);
        assert_ne!(key_device, [0u8; 32]);
        assert_ne!(key_client, [0u8; 32]);
        let key_client_again = crate::wrap::derive_wrap_key("client99").unwrap();
        assert_eq!(key_client, key_client_again);
    }

    #[test]
    fn test_early_tunconf_parsing() {
        let raw = b"TUNCONF:10.66.67.2:1.1.1.1:stream-v2";
        let parsed = crate::protocol::parse_config_response(raw).unwrap();
        match parsed {
            crate::protocol::ConfigResponse::Config(val) => {
                assert!(val.starts_with("TUNCONF:10.66.67.2"));
                assert!(val.ends_with(":stream-v2"));
            }
            _ => panic!("expected Config"),
        }
    }
}
