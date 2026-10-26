use crate::{
    auth::{CallUnavailable, TurnCredentials, VkAuth, VkRateLimitError},
    dispatcher::Dispatcher,
    events::Events,
    obfs::ObfsMode,
    packet::PacketPool,
    repair::RepairState,
    session::{
        ConfigDeliveryState, SessionConfig, SessionRuntime, ShutdownCoordinator, TurnAllocateError,
        run_session,
    },
    srtp_mode::SrtpMode,
    stats::Stats,
    turn::TurnRequestError,
    turn_endpoint::TurnTransportMode,
};
use std::{
    collections::HashSet,
    net::SocketAddr,
    ops::Range,
    sync::{
        Arc, LazyLock,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio::sync::{Mutex, Notify, mpsc, oneshot};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

pub const WORKERS_PER_GROUP: usize = 10;
const _: () = assert!(WORKERS_PER_GROUP == 10);
pub const GROUPS_PER_CREDENTIAL: usize = 1;
pub const WORKERS_PER_CREDENTIAL: usize = WORKERS_PER_GROUP * GROUPS_PER_CREDENTIAL;
const _: () = assert!(WORKERS_PER_CREDENTIAL == 10);
pub const WORKER_START_INTERVAL: Duration = Duration::from_millis(100);
const CREDENTIAL_POST_DELAY: Duration = Duration::from_millis(100);
const CREDENTIAL_FETCH_TIMEOUT: Duration = Duration::from_secs(240);
const FATAL_RETRY_BASE: Duration = Duration::from_secs(5);
const FATAL_RETRY_CAP: Duration = Duration::from_secs(300);
const RECOVERY_LOG_INTERVAL_MS: u64 = 10_000;
static RECOVERY_CLOCK: LazyLock<std::time::Instant> = LazyLock::new(std::time::Instant::now);
static NETWORK_RECOVERY_LOG: RecoveryLogGate = RecoveryLogGate::new();
static TURN_TIMEOUT_RECOVERY_LOG: RecoveryLogGate = RecoveryLogGate::new();
static WRAP_TIMEOUT_RECOVERY_LOG: RecoveryLogGate = RecoveryLogGate::new();
static GETCONF_TIMEOUT_RECOVERY_LOG: RecoveryLogGate = RecoveryLogGate::new();

struct RecoveryLogGate {
    pending: AtomicU64,
    next_report_ms: AtomicU64,
}

impl RecoveryLogGate {
    const fn new() -> Self {
        Self {
            pending: AtomicU64::new(0),
            next_report_ms: AtomicU64::new(0),
        }
    }

    fn observe(&self, now_ms: u64) -> Option<u64> {
        self.pending.fetch_add(1, Ordering::Relaxed);
        loop {
            let deadline = self.next_report_ms.load(Ordering::Acquire);
            if deadline != 0 && now_ms < deadline {
                return None;
            }
            let next = now_ms.saturating_add(RECOVERY_LOG_INTERVAL_MS).max(1);
            if self
                .next_report_ms
                .compare_exchange(deadline, next, Ordering::AcqRel, Ordering::Acquire)
                .is_ok()
            {
                return Some(self.pending.swap(0, Ordering::AcqRel));
            }
        }
    }
}

fn recovery_now_ms() -> u64 {
    RECOVERY_CLOCK
        .elapsed()
        .as_millis()
        .min(u128::from(u64::MAX)) as u64
}

pub fn credential_failure_delay(failures: usize) -> Duration {
    const LADDER: [Duration; 7] = [
        Duration::from_secs(2),
        Duration::from_secs(3),
        Duration::from_secs(5),
        Duration::from_secs(10),
        Duration::from_secs(15),
        Duration::from_secs(20),
        Duration::from_secs(30),
    ];
    let index = failures.min(LADDER.len() - 1);
    LADDER[index]
}

pub struct RuntimeParams {
    pub peer: SocketAddr,
    pub turn_host: Option<Arc<str>>,
    pub turn_port: Option<Arc<str>>,
    pub turn_ip_pool: Arc<crate::turn_ip_pool::TurnIpPool>,
    pub turn_transport: TurnTransportMode,
    pub srtp_mode: SrtpMode,
    pub hashes: Arc<[String]>,
    pub wrap_key: [u8; 32],
    pub client_wrap_key: [u8; 32],
    pub mode: ObfsMode,
    pub generation: u64,
    pub salt: Arc<str>,
    pub local_port: Arc<str>,
    pub password: Arc<str>,
    pub workers: Arc<AtomicUsize>,
    pub configured_workers: usize,
}

pub struct GroupContext {
    pub params: Arc<RuntimeParams>,
    pub auth: Arc<VkAuth>,
    pub dispatcher: Arc<Dispatcher>,
    pub pool: Arc<PacketPool>,
    pub stats: Arc<Stats>,
    pub events: Events,
    pub paused: Arc<PauseGate>,
    pub config_tx: mpsc::Sender<String>,
    pub start_pacer: Arc<WorkerStartPacer>,
    pub credential_pacer: Arc<tokio::sync::Mutex<()>>,
    pub ready_credential_tx: Option<mpsc::UnboundedSender<usize>>,
    pub config_sent: Arc<AtomicBool>,
    pub config_in_flight: Arc<AtomicBool>,
    pub server_stream_repair: Arc<AtomicBool>,
    pub repair: Arc<RepairState>,
    pub shutdown: Arc<ShutdownCoordinator>,
    pub cancel: CancellationToken,
    pub scale_notify: Arc<tokio::sync::Notify>,
}

pub struct PauseGate {
    paused: AtomicBool,
    changed: Notify,
}

impl PauseGate {
    pub fn new() -> Self {
        Self {
            paused: AtomicBool::new(false),
            changed: Notify::new(),
        }
    }

    pub fn set_paused(&self, paused: bool) {
        if self.paused.swap(paused, Ordering::AcqRel) != paused {
            self.changed.notify_waiters();
        }
    }

    fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Acquire)
    }

    async fn wait_until_resumed(&self, cancel: &CancellationToken) -> bool {
        while self.is_paused() {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if !self.is_paused() {
                break;
            }
            tokio::select! {
                _ = cancel.cancelled() => return false,
                _ = notified.as_mut() => {},
                _ = tokio::time::sleep(Duration::from_millis(250)) => {},
            }
        }
        true
    }
}

pub struct WorkerStartPacer {
    next: Mutex<Instant>,
    interval: Duration,
}

impl WorkerStartPacer {
    pub fn new(interval: Duration) -> Self {
        Self {
            next: Mutex::new(Instant::now()),
            interval,
        }
    }

    async fn wait(&self, cancel: &CancellationToken) -> bool {
        let mut next = tokio::select! {
            biased;
            _ = cancel.cancelled() => return false,
            next = self.next.lock() => next,
        };
        let scheduled = (*next).max(Instant::now());
        tokio::select! {
            biased;
            _ = cancel.cancelled() => false,
            _ = tokio::time::sleep_until(scheduled) => {
                *next = scheduled + self.interval;
                true
            },
        }
    }
}

struct ConfigFlightGuard {
    in_flight: Arc<AtomicBool>,
    acquired: bool,
}

impl ConfigFlightGuard {
    fn acquire(sent: &AtomicBool, in_flight: Arc<AtomicBool>) -> Self {
        let acquired = !sent.load(Ordering::Acquire)
            && in_flight
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_ok();
        Self {
            in_flight,
            acquired,
        }
    }
}

impl Drop for ConfigFlightGuard {
    fn drop(&mut self) {
        if self.acquired {
            self.in_flight.store(false, Ordering::Release);
        }
    }
}

pub async fn run_groups(context: Arc<GroupContext>) {
    let hash_count = context.params.hashes.len();
    if hash_count == 0 {
        return;
    }
    let availability: Vec<_> = (0..hash_count)
        .map(|_| Arc::new(HashAvailability::new(&context.cancel)))
        .collect();
    let mut credentials_by_hash: Vec<Vec<Arc<GroupCredentials>>> =
        (0..hash_count).map(|_| Vec::new()).collect();
    let mut next_credential_id = 1usize;

    let mut get_group_credentials = |group_index: usize| -> Arc<GroupCredentials> {
        let hash_index = group_hash_index(group_index, hash_count);
        let group_index_for_hash = group_index / hash_count;
        let cohort_index = credential_cohort_index(group_index_for_hash);
        while credentials_by_hash[hash_index].len() <= cohort_index {
            credentials_by_hash[hash_index].push(Arc::new(GroupCredentials {
                credential_id: next_credential_id,
                hash: context.params.hashes[hash_index].clone(),
                context: context.clone(),
                state: tokio::sync::Mutex::new(CredentialCache::default()),
                fetch_gate: tokio::sync::Mutex::new(()),
                failures: AtomicUsize::new(0),
                availability: availability[hash_index].clone(),
                exhausted: AtomicBool::new(false),
            }));
            next_credential_id = next_credential_id.saturating_add(1);
        }
        credentials_by_hash[hash_index][cohort_index].clone()
    };

    let mut tasks = tokio::task::JoinSet::new();
    let mut spawned_groups = 0usize;

    let spawn_groups_up_to =
        |target_total: usize,
         tasks: &mut tokio::task::JoinSet<()>,
         spawned: &mut usize,
         get_creds: &mut dyn FnMut(usize) -> Arc<GroupCredentials>| {
            let needed_groups = target_total.div_ceil(WORKERS_PER_GROUP);
            while *spawned < needed_groups {
                let group_index = *spawned;
                let group_id = group_index + 1;
                let group_creds = get_creds(group_index);
                let group_context = context.clone();
                let group_cancel = context.cancel.child_token();
                tasks.spawn(async move {
                    run_group(group_id, group_context, group_creds, group_cancel).await;
                });
                *spawned += 1;
            }
        };

    let initial_workers = context.params.workers.load(Ordering::Acquire);
    spawn_groups_up_to(
        initial_workers,
        &mut tasks,
        &mut spawned_groups,
        &mut get_group_credentials,
    );

    loop {
        tokio::select! {
            biased;
            _ = context.cancel.cancelled() => break,
            _ = context.scale_notify.notified() => {
                let current_workers = context.params.workers.load(Ordering::Acquire);
                spawn_groups_up_to(current_workers, &mut tasks, &mut spawned_groups, &mut get_group_credentials);
            }
            result = tasks.join_next(), if !tasks.is_empty() => {
                if let Some(Err(error)) = result {
                    crate::log_error!("[СУПЕРВИЗОР] Задача группы завершилась аварийно: {error}");
                }
                if !context.cancel.is_cancelled() && tasks.is_empty() {
                    break;
                }
            }
        }
    }
}

async fn run_group(
    group_id: usize,
    context: Arc<GroupContext>,
    group_creds: Arc<GroupCredentials>,
    group_cancel: CancellationToken,
) {
    if !context.paused.wait_until_resumed(&group_cancel).await {
        return;
    }
    let mut workers = tokio::task::JoinSet::<usize>::new();
    let mut spawned_worker_ids = std::collections::HashSet::new();

    let spawn_workers = |workers: &mut tokio::task::JoinSet<usize>,
                         spawned: &mut std::collections::HashSet<usize>| {
        let total_workers = context.params.workers.load(Ordering::Acquire);
        for id in group_worker_ids(group_id, total_workers) {
            if spawned.insert(id) {
                let worker_context = context.clone();
                let worker_creds = group_creds.clone();
                let worker_cancel = group_cancel.clone();
                workers.spawn(async move {
                    let result = tokio::spawn(supervise_worker(
                        id,
                        worker_context,
                        worker_creds,
                        worker_cancel,
                    ))
                    .await;
                    if let Err(error) = result {
                        crate::log_error!(
                            "[ГРУППА #{group_id}][ВОРКЕР #{id}] Супервизор завершился аварийно: {error}"
                        );
                    }
                    id
                });
            }
        }
    };

    spawn_workers(&mut workers, &mut spawned_worker_ids);

    loop {
        tokio::select! {
            biased;
            _ = group_cancel.cancelled() => break,
            _ = context.cancel.cancelled() => break,
            _ = context.scale_notify.notified() => {
                spawn_workers(&mut workers, &mut spawned_worker_ids);
            }
            result = workers.join_next(), if !workers.is_empty() => {
                match result {
                    Some(Ok(id)) => {
                        spawned_worker_ids.remove(&id);
                        let total_workers = context.params.workers.load(Ordering::Acquire);
                        if !group_cancel.is_cancelled()
                            && !context.cancel.is_cancelled()
                            && id <= total_workers
                            && !group_creds.is_unavailable()
                            && !group_creds.is_exhausted()
                        {
                            spawn_workers(&mut workers, &mut spawned_worker_ids);
                        }
                    }
                    Some(Err(error)) => {
                        crate::log_error!("[ГРУППА #{group_id}] Задача воркера завершилась аварийно: {error}");
                    }
                    None => {}
                }
                if !group_cancel.is_cancelled()
                    && !context.cancel.is_cancelled()
                    && workers.is_empty()
                    && (group_creds.is_unavailable() || group_creds.is_exhausted())
                {
                    crate::log_error!("[ГРУППА #{group_id}] Credentials недоступны или исчерпаны, группа остановлена");
                    break;
                }
            }
        }
    }
}

struct HashAvailability {
    unavailable: AtomicBool,
    cancel: CancellationToken,
}

impl HashAvailability {
    fn new(parent_cancel: &CancellationToken) -> Self {
        Self {
            unavailable: AtomicBool::new(false),
            cancel: parent_cancel.child_token(),
        }
    }

    fn mark_unavailable(&self) {
        self.unavailable.store(true, Ordering::Release);
        self.cancel.cancel();
    }
}

pub struct GroupCredentials {
    pub credential_id: usize,
    pub hash: String,
    pub context: Arc<GroupContext>,
    state: tokio::sync::Mutex<CredentialCache>,
    fetch_gate: tokio::sync::Mutex<()>,
    failures: AtomicUsize,
    availability: Arc<HashAvailability>,
    exhausted: AtomicBool,
}

struct CachedCredentials {
    value: TurnCredentials,
    generation: u64,
    allocation_attempts: usize,
    allocation_mismatches: usize,
}

#[derive(Clone)]
struct CredentialLease {
    value: TurnCredentials,
    generation: u64,
}

#[derive(Default)]
struct CredentialCache {
    current: Option<CachedCredentials>,
    quota_blocked: Option<TurnCredentials>,
    next_generation: u64,
    quota_retries: usize,
}

enum QuotaRotation {
    Rotated(Duration),
    Replaced,
}

enum AllocationMismatch {
    RetryCurrent,
    Rotated,
    Replaced,
}

impl CredentialCache {
    fn reserve(&mut self) -> Option<CredentialLease> {
        if self
            .current
            .as_ref()
            .is_some_and(|cached| cached.allocation_attempts >= WORKERS_PER_CREDENTIAL)
        {
            self.current = None;
        }
        let cached = self.current.as_mut()?;
        cached.allocation_attempts = cached.allocation_attempts.saturating_add(1);
        Some(CredentialLease {
            value: cached.value.clone(),
            generation: cached.generation,
        })
    }

    fn store(&mut self, credentials: TurnCredentials) -> bool {
        if self
            .quota_blocked
            .as_ref()
            .is_some_and(|blocked| same_turn_credentials(blocked, &credentials))
            && self.quota_retries < 2
        {
            return false;
        }
        self.quota_blocked = None;
        self.next_generation = self.next_generation.saturating_add(1).max(1);
        self.current = Some(CachedCredentials {
            value: credentials,
            generation: self.next_generation,
            allocation_attempts: 0,
            allocation_mismatches: 0,
        });
        true
    }

    fn record_allocation(&mut self, lease: &CredentialLease) {
        if let Some(cached) = self.current.as_mut()
            && cached.generation == lease.generation
            && same_turn_credentials(&cached.value, &lease.value)
        {
            cached.allocation_mismatches = 0;
            self.quota_retries = 0;
        }
    }

    fn release(&mut self, lease: &CredentialLease) {
        if let Some(cached) = self.current.as_mut()
            && cached.generation == lease.generation
            && same_turn_credentials(&cached.value, &lease.value)
        {
            cached.allocation_attempts = cached.allocation_attempts.saturating_sub(1);
        }
    }

    fn invalidate(&mut self, lease: &CredentialLease) -> bool {
        if self.current.as_ref().is_some_and(|cached| {
            cached.generation == lease.generation
                && same_turn_credentials(&cached.value, &lease.value)
        }) {
            self.current = None;
            return true;
        }
        false
    }

    fn rotate_after_quota(&mut self, lease: &CredentialLease) -> QuotaRotation {
        let Some(cached) = self.current.as_ref() else {
            return QuotaRotation::Replaced;
        };
        if cached.generation != lease.generation
            || !same_turn_credentials(&cached.value, &lease.value)
        {
            return QuotaRotation::Replaced;
        }
        let rejected = cached.value.clone();
        self.current = None;
        self.quota_blocked = Some(rejected);
        let delay = quota_rotation_delay(self.quota_retries);
        self.quota_retries = self.quota_retries.saturating_add(1);
        QuotaRotation::Rotated(delay)
    }

    fn next_quota_delay(&mut self) -> Duration {
        let delay = quota_rotation_delay(self.quota_retries);
        self.quota_retries = self.quota_retries.saturating_add(1);
        delay
    }

    fn retry_after_mismatch(&mut self, lease: &CredentialLease) -> AllocationMismatch {
        let Some(cached) = self.current.as_mut() else {
            return AllocationMismatch::Replaced;
        };
        if cached.generation != lease.generation
            || !same_turn_credentials(&cached.value, &lease.value)
        {
            return AllocationMismatch::Replaced;
        }
        if cached.allocation_mismatches == 0 {
            cached.allocation_mismatches = 1;
            return AllocationMismatch::RetryCurrent;
        }
        self.current = None;
        AllocationMismatch::Rotated
    }
}

impl GroupCredentials {
    fn is_unavailable(&self) -> bool {
        self.availability.unavailable.load(Ordering::Acquire)
    }

    fn is_exhausted(&self) -> bool {
        self.exhausted.load(Ordering::Acquire)
    }

    fn cancellation_token(&self) -> CancellationToken {
        self.availability.cancel.clone()
    }

    async fn get(&self) -> Option<CredentialLease> {
        if self.is_unavailable() || self.is_exhausted() {
            return None;
        }
        if let Some(lease) = self.state.lock().await.reserve() {
            return Some(lease);
        }
        let credential_stream_id = credential_stream_id(self.credential_id);
        let _fetch = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            guard = self.fetch_gate.lock() => guard,
        };
        if let Some(lease) = self.state.lock().await.reserve() {
            return Some(lease);
        }
        let _guard = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            guard = self.context.credential_pacer.lock() => guard,
        };
        if self.is_unavailable() || self.is_exhausted() {
            return None;
        }
        let short_hash: String = self.hash.chars().take(8).collect();
        crate::log_error!(
            "[КРЕД #{}] Запрос (хеш: {short_hash}...)",
            self.credential_id
        );
        let fetched = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            result = tokio::time::timeout(
                CREDENTIAL_FETCH_TIMEOUT,
                self.context.auth.get_credentials(&self.hash, credential_stream_id),
            ) => result.unwrap_or_else(|_| Err(anyhow::anyhow!(
                "VK не вернул credential за {CREDENTIAL_FETCH_TIMEOUT:?}"
            ))),
        };
        match fetched {
            Ok(creds) => {
                let server_addresses = creds.server_addresses.clone();
                let mut state = self.state.lock().await;
                if !state.store(creds) {
                    let delay = state.next_quota_delay();
                    drop(state);
                    crate::log_error!(
                        "[TURN][RETRY] VK вернул прежний credential после 486; новый запрос через {:?}",
                        delay
                    );
                    tokio::select! {
                        _ = self.context.cancel.cancelled() => return None,
                        _ = self.availability.cancel.cancelled() => return None,
                        _ = tokio::time::sleep(delay) => {}
                    }
                    return None;
                }
                drop(state);
                self.failures.store(0, Ordering::Relaxed);
                tokio::select! {
                    _ = self.context.cancel.cancelled() => return None,
                    _ = self.availability.cancel.cancelled() => return None,
                    _ = tokio::time::sleep(CREDENTIAL_POST_DELAY) => {}
                }
                crate::log_error!(
                    "[КРЕД #{}] OK, TURN: {:?}, до {} воркеров",
                    self.credential_id,
                    server_addresses,
                    WORKERS_PER_CREDENTIAL
                );
                self.context.events.progress("credentials");
                self.state.lock().await.reserve()
            }
            Err(error) => {
                if let Some(call) = error.downcast_ref::<CallUnavailable>() {
                    self.availability.mark_unavailable();
                    self.context.events.call_unavailable(&self.hash, call.code);
                    return None;
                }
                let failures = self.failures.fetch_add(1, Ordering::Relaxed);
                let delay = if error.downcast_ref::<VkRateLimitError>().is_some() || failures >= 5 {
                    self.failures.store(0, Ordering::Relaxed);
                    Duration::from_secs(30)
                } else {
                    credential_failure_delay(failures)
                };
                crate::log_error!(
                    "[КРЕД #{}] Ошибка: {error:#}. Повторяем через {delay:?}...",
                    self.credential_id
                );
                tokio::select! {
                    _ = self.context.cancel.cancelled() => return None,
                    _ = self.availability.cancel.cancelled() => return None,
                    _ = tokio::time::sleep(delay) => {}
                }
                None
            }
        }
    }

    async fn invalidate(&self, lease: &CredentialLease) {
        let mut state = self.state.lock().await;
        if state.invalidate(lease) {
            crate::log_error!(
                "[КРЕД #{}] Невалиден, запрашиваем новый",
                self.credential_id
            );
        }
    }

    async fn record_allocation(&self, lease: &CredentialLease) {
        self.state.lock().await.record_allocation(lease);
    }

    async fn release_allocation(&self, lease: &CredentialLease) {
        self.state.lock().await.release(lease);
    }

    async fn rotate_after_quota(&self, lease: &CredentialLease) -> QuotaRotation {
        self.state.lock().await.rotate_after_quota(lease)
    }

    async fn retry_after_mismatch(&self, lease: &CredentialLease) -> AllocationMismatch {
        self.state.lock().await.retry_after_mismatch(lease)
    }
}

async fn supervise_worker(
    id: usize,
    context: Arc<GroupContext>,
    group_creds: Arc<GroupCredentials>,
    worker_cancel: CancellationToken,
) {
    let endpoint_cursor = Arc::new(AtomicUsize::new(0));
    loop {
        if worker_cancel.is_cancelled()
            || context.cancel.is_cancelled()
            || (id > context.params.workers.load(Ordering::Acquire)
                && id > context.params.configured_workers)
        {
            return;
        }
        let task = tokio::spawn(worker_loop(
            id,
            context.clone(),
            group_creds.clone(),
            endpoint_cursor.clone(),
            worker_cancel.clone(),
        ));
        match task.await {
            Ok(()) => {
                if worker_cancel.is_cancelled()
                    || context.cancel.is_cancelled()
                    || (id > context.params.workers.load(Ordering::Acquire)
                        && id > context.params.configured_workers)
                {
                    return;
                }
                tokio::select! {
                    _ = context.cancel.cancelled() => return,
                    _ = worker_cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_millis(500)) => {}
                }
            }
            Err(error)
                if error.is_panic()
                    && !worker_cancel.is_cancelled()
                    && !context.cancel.is_cancelled() =>
            {
                crate::log_error!(
                    "[ВОРКЕР #{id}] Паника изолирована, перезапуск через 1 секунду: {error}"
                );
                tokio::select! {
                    _ = context.cancel.cancelled() => return,
                    _ = worker_cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {}
                }
            }
            Err(error) => {
                if !worker_cancel.is_cancelled() && !context.cancel.is_cancelled() {
                    crate::log_error!("[ВОРКЕР #{id}] Задача отменена: {error}");
                }
                return;
            }
        }
    }
}

async fn worker_loop(
    id: usize,
    context: Arc<GroupContext>,
    group_creds: Arc<GroupCredentials>,
    endpoint_cursor: Arc<AtomicUsize>,
    worker_cancel: CancellationToken,
) {
    let mut attempt = 0usize;
    let mut fatal_streak = 0usize;
    loop {
        if worker_cancel.is_cancelled()
            || context.cancel.is_cancelled()
            || (id > context.params.workers.load(Ordering::Acquire)
                && id > context.params.configured_workers)
        {
            return;
        }
        if !context.paused.wait_until_resumed(&worker_cancel).await {
            return;
        }
        let credential_lease = loop {
            if group_creds.is_unavailable() || group_creds.is_exhausted() {
                return;
            }
            if let Some(lease) = group_creds.get().await {
                break lease;
            }
            if worker_cancel.is_cancelled()
                || context.cancel.is_cancelled()
                || group_creds.is_unavailable()
                || group_creds.is_exhausted()
            {
                return;
            }
        };
        if !context.start_pacer.wait(&worker_cancel).await {
            group_creds.release_allocation(&credential_lease).await;
            return;
        }
        if !context.paused.wait_until_resumed(&worker_cancel).await {
            group_creds.release_allocation(&credential_lease).await;
            return;
        }
        let config_guard = if id > 1 && !context.config_sent.load(Ordering::Acquire) {
            tokio::task::yield_now().await;
            ConfigFlightGuard::acquire(&context.config_sent, context.config_in_flight.clone())
        } else {
            ConfigFlightGuard::acquire(&context.config_sent, context.config_in_flight.clone())
        };
        let get_config = config_guard.acquired;
        let turn_endpoint_cursor = endpoint_cursor.fetch_add(1, Ordering::Relaxed);
        let credential_generation = context.repair.credential_generation(id);
        let (turn_host, turn_port) = match context.params.turn_ip_pool.select_target(id) {
            Some((host, port)) => (
                Some(host),
                port.or_else(|| context.params.turn_port.clone()),
            ),
            None => (
                context.params.turn_host.clone(),
                context.params.turn_port.clone(),
            ),
        };
        let target_turn_host = turn_host.clone();
        let session_config = SessionConfig {
            id,
            peer: context.params.peer,
            turn_host,
            turn_port,
            turn_transport: context.params.turn_transport,
            srtp_mode: context.params.srtp_mode,
            local_port: context.params.local_port.clone(),
            password: context.params.password.clone(),
            generation: context.params.generation,
            salt: context.params.salt.clone(),
            mode: context.params.mode,
            wrap_key: context.params.wrap_key,
            client_wrap_key: context.params.client_wrap_key,
            get_config,
            server_stream_repair: context.server_stream_repair.clone(),
            repair: context.repair.clone(),
            turn_endpoint_cursor,
            configured_workers: context.params.configured_workers,
        };
        let (ready_tx, mut ready_rx) = oneshot::channel();
        let (allocation_started_tx, mut allocation_started_rx) = oneshot::channel();
        let (allocation_tx, mut allocation_rx) = oneshot::channel();
        let session_cancel = worker_cancel.child_token();
        let creds_cancel = group_creds.cancellation_token();
        let mut session = Box::pin(run_session(
            session_config,
            credential_lease.value.clone(),
            SessionRuntime {
                dispatcher: context.dispatcher.clone(),
                pool: context.pool.clone(),
                stats: context.stats.clone(),
                events: context.events.clone(),
                config_tx: context.config_tx.clone(),
                config_delivery: get_config.then(|| ConfigDeliveryState {
                    sent: context.config_sent.clone(),
                    in_flight: context.config_in_flight.clone(),
                }),
                cancel: session_cancel.clone(),
                shutdown: context.shutdown.clone(),
                ready_tx: Some(ready_tx),
                allocation_started: Some(allocation_started_tx),
                allocation_ready: Some(allocation_tx),
                scale_notify: Some(context.scale_notify.clone()),
                target_workers: Some(context.params.workers.clone()),
                configured_workers: context.params.configured_workers,
            },
        ));
        let mut allocation_started_observed = false;
        let mut allocation_observed = false;
        let mut ready_observed = false;
        let mut was_ready = false;
        let mut creds_cancel_observed = false;
        let result = loop {
            tokio::select! {
                result = &mut session => break result,
                _ = creds_cancel.cancelled(), if !creds_cancel_observed => {
                    creds_cancel_observed = true;
                    session_cancel.cancel();
                }
                ready = &mut ready_rx, if !ready_observed => {
                    ready_observed = true;
                    if ready.is_ok() {
                        was_ready = true;
                        if let Some(sender) = &context.ready_credential_tx {
                            let _ = sender.send(group_creds.credential_id);
                        }
                    }
                }
                _started = &mut allocation_started_rx, if !allocation_started_observed => {
                    allocation_started_observed = true;
                }
                allocated = &mut allocation_rx, if !allocation_observed => {
                    allocation_observed = true;
                    if allocated.is_ok() {
                        group_creds.record_allocation(&credential_lease).await;
                        context.events.network_recovered();
                        if let Some(host) = &target_turn_host {
                            context.params.turn_ip_pool.mark_healthy(host);
                        }
                    }
                }
            }
        };
        group_creds.release_allocation(&credential_lease).await;
        if was_ready {
            attempt = 0;
            fatal_streak = 0;
        }
        drop(config_guard);
        if worker_cancel.is_cancelled()
            || context.cancel.is_cancelled()
            || (id > context.params.workers.load(Ordering::Acquire)
                && id > context.params.configured_workers)
            || group_creds.is_unavailable()
            || group_creds.is_exhausted()
        {
            return;
        }
        if context.paused.is_paused() {
            continue;
        }
        let mut delay = worker_retry_delay(attempt.max(1));
        if let Err(error) = &result {
            let message = error.to_string();
            let lower = message.to_ascii_lowercase();
            if lower.contains("invalid_worker_count")
                || message.contains("превышен лимит потоков")
                || (id > context.params.workers.load(Ordering::Acquire)
                    && id > context.params.configured_workers)
            {
                return;
            }
            let is_repair = lower.contains("target_repair");
            let is_quota = is_turn_allocation_quota(error, &lower);
            if let (false, false, false, Some(host)) =
                (is_repair, is_quota, was_ready, &target_turn_host)
            {
                context.params.turn_ip_pool.mark_failed(host);
            }
            let tcp_stream_reset = context.params.turn_transport == TurnTransportMode::TcpTls
                && is_remote_tcp_stream_reset(error, &lower);
            if context.params.turn_transport == TurnTransportMode::TcpTls && !tcp_stream_reset {
                let phase = if was_ready {
                    "активная аллокация завершилась"
                } else {
                    "создание аллокации завершилось"
                };
                crate::log_error!("[TURN][TCP] {phase}: {error:#}");
            }
            if lower.contains("target_repair") {
                attempt = 0;
                delay = Duration::from_millis(50 + rand::random::<u64>() % 151);
                if context.repair.credential_generation(id) > credential_generation {
                    group_creds.invalidate(&credential_lease).await;
                }
                crate::log_error!("[ВОРКЕР #{id}] Repair: пересоздаём поток");
                tokio::select! {
                    _ = context.cancel.cancelled() => return,
                    _ = worker_cancel.cancelled() => return,
                    _ = tokio::time::sleep(delay) => {}
                }
                continue;
            }
            if lower.contains("turn channel rebind retry exhausted")
                || lower.contains("turn allocation entered destroying unexpectedly")
            {
                attempt = 0;
                delay = Duration::from_millis(50 + rand::random::<u64>() % 151);
                tokio::select! {
                    _ = context.cancel.cancelled() => return,
                    _ = worker_cancel.cancelled() => return,
                    _ = tokio::time::sleep(delay) => {}
                }
                continue;
            }
            attempt = attempt.saturating_add(1);
            delay = worker_retry_delay(attempt);
            let credentials_invalid = should_invalidate_turn_credentials(error, &lower);
            if credentials_invalid {
                group_creds.invalidate(&credential_lease).await;
                attempt = 0;
                delay = Duration::from_millis(100 + rand::random::<u64>() % 51);
            }
            if is_turn_allocation_quota(error, &lower) {
                match group_creds.rotate_after_quota(&credential_lease).await {
                    QuotaRotation::Rotated(quota_delay) => {
                        attempt = 0;
                        delay = quota_delay;
                        crate::log_error!(
                            "[TURN][RETRY] Квота Allocate у креда #{}; обновляем cohort до {} потоков через {:?}",
                            group_creds.credential_id,
                            WORKERS_PER_CREDENTIAL,
                            delay
                        );
                    }
                    QuotaRotation::Replaced => {
                        attempt = 0;
                        delay = Duration::from_millis(50 + rand::random::<u64>() % 151);
                    }
                }
            } else if is_turn_allocation_mismatch(error, &lower) {
                match group_creds.retry_after_mismatch(&credential_lease).await {
                    AllocationMismatch::RetryCurrent => {
                        attempt = 0;
                        delay = Duration::from_millis(50 + rand::random::<u64>() % 51);
                    }
                    AllocationMismatch::Rotated => {
                        attempt = 0;
                        delay = Duration::from_millis(100 + rand::random::<u64>() % 51);
                        crate::log_error!(
                            "[TURN][RETRY] Allocation mismatch у креда #{}; обновляем cohort",
                            group_creds.credential_id
                        );
                    }
                    AllocationMismatch::Replaced => {
                        attempt = 0;
                        delay = Duration::from_millis(50 + rand::random::<u64>() % 51);
                    }
                }
            } else if credentials_invalid {
                crate::log_error!(
                    "[TURN][RETRY] Аутентификация у креда #{} исчерпана; обновляем cohort",
                    group_creds.credential_id
                );
            } else if tcp_stream_reset {
                delay = tcp_stream_retry_delay(attempt);
                crate::log_error!(
                    "[TURN][TCP] Удалённая сторона закрыла TCP-поток; восстановление через {:?}",
                    delay
                );
            } else if is_local_network_down(error) {
                context.events.network_timeout();
                delay = Duration::from_millis(250 + rand::random::<u64>() % 751);
                if let Some(count) = NETWORK_RECOVERY_LOG.observe(recovery_now_ms()) {
                    crate::log_error!(
                        "[СЕТЬ][RETRY] Локальный маршрут недоступен, {count} попыток восстановления"
                    );
                }
            } else {
                if is_transport_timeout(&lower) {
                    context.events.network_timeout();
                }
                if message.contains("FATAL_AUTH")
                    || message.contains("FATAL_PROTOCOL")
                    || message.contains("хеш мёртв")
                {
                    fatal_streak = fatal_streak.saturating_add(1);
                    delay = fatal_retry_delay(fatal_streak);
                    let display_message = message
                        .replace("FATAL_AUTH", "AUTH_RETRY")
                        .replace("FATAL_PROTOCOL", "PROTOCOL_RETRY");
                    crate::log_error!(
                        "[ВОРКЕР #{id}] Ошибка подключения, изолированный повтор через {:?}: {display_message}",
                        delay
                    );
                } else if lower.contains("wrap_auth_timeout") {
                    if let Some(count) = WRAP_TIMEOUT_RECOVERY_LOG.observe(recovery_now_ms()) {
                        crate::log_error!(
                            "[HANDSHAKE][RETRY] WRAP_AUTH_TIMEOUT, {count} попыток восстановления"
                        );
                    }
                } else if lower.contains("getconf") && lower.contains("timeout") {
                    if let Some(count) = GETCONF_TIMEOUT_RECOVERY_LOG.observe(recovery_now_ms()) {
                        crate::log_error!(
                            "[GETCONF][RETRY] Нет подтверждения, {count} попыток восстановления"
                        );
                    }
                } else if lower.contains("turn ") && lower.contains("transaction timeout") {
                    if let Some(count) = TURN_TIMEOUT_RECOVERY_LOG.observe(recovery_now_ms()) {
                        crate::log_error!(
                            "[TURN][RETRY] Нет ответа на служебную транзакцию, {count} попыток восстановления"
                        );
                    }
                } else if lower.contains("turn ") {
                    crate::log_error!("[ВОРКЕР #{id}] [TURN][RETRY] Попытка {attempt}: {message}");
                } else {
                    crate::log_error!("[ВОРКЕР #{id}] Ошибка (попытка {attempt}): {message}");
                }
                if lower.contains("getconf") {
                    delay = worker_retry_delay(attempt).min(Duration::from_secs(1));
                }
                if lower.contains("error 29") || lower.contains("cannot create socket") {
                    delay = Duration::from_secs(2 + rand::random::<u64>() % 3);
                    crate::log_error!(
                        "[ВОРКЕР #{id}] [СЕТЬ][RETRY] Временная ошибка сокета, пересоздаём транспорт через {:?}: {message}",
                        delay
                    );
                }
            }
        }
        tokio::select! {
            _ = context.cancel.cancelled() => return,
            _ = worker_cancel.cancelled() => return,
            _ = tokio::time::sleep(delay) => {}
        }
    }
}

fn worker_retry_delay(attempt: usize) -> Duration {
    worker_retry_delay_with_jitter(attempt, rand::random::<u64>() % 251)
}

fn worker_retry_delay_with_jitter(attempt: usize, jitter_ms: u64) -> Duration {
    let shift = attempt.saturating_sub(1).min(4) as u32;
    let base_ms = 250u64.saturating_mul(1u64 << shift).min(4_000);
    Duration::from_millis(base_ms.saturating_add(jitter_ms.min(250)))
}

fn fatal_retry_delay(streak: usize) -> Duration {
    fatal_retry_delay_with_jitter(streak, rand::random::<u64>() % 1_000)
}

fn fatal_retry_delay_with_jitter(streak: usize, jitter_ms: u64) -> Duration {
    let shift = streak.saturating_sub(1).min(6) as u32;
    let base_ms = (FATAL_RETRY_BASE.as_millis() as u64).saturating_mul(1u64 << shift);
    let cap_ms = FATAL_RETRY_CAP.as_millis() as u64;
    Duration::from_millis(base_ms.saturating_add(jitter_ms.min(1_000)).min(cap_ms))
}

fn tcp_stream_retry_delay(attempt: usize) -> Duration {
    quota_rotation_delay(attempt.saturating_sub(1))
}

fn quota_rotation_delay(attempt: usize) -> Duration {
    Duration::from_millis(match attempt {
        0 => 100,
        1 => 300,
        2 => 500,
        3 => 800,
        _ => 1_500,
    })
}

fn turn_allocate_stun_code(error: &anyhow::Error) -> Option<i32> {
    error.chain().find_map(|cause| {
        cause
            .downcast_ref::<TurnAllocateError>()
            .and_then(TurnAllocateError::stun_code)
    })
}

fn turn_stun_code(error: &anyhow::Error) -> Option<i32> {
    error.chain().find_map(|cause| {
        cause
            .downcast_ref::<TurnRequestError>()
            .map(TurnRequestError::stun_code)
    })
}

fn is_turn_allocation_quota(error: &anyhow::Error, message: &str) -> bool {
    turn_allocate_stun_code(error) == Some(486)
        || (message.contains("turn allocate")
            && [
                "stun error 486",
                "allocation quota reached",
                "quota reached",
            ]
            .iter()
            .any(|marker| message.contains(marker)))
}

fn is_turn_allocation_mismatch(error: &anyhow::Error, message: &str) -> bool {
    match turn_stun_code(error) {
        Some(437) => true,
        Some(400) => message.contains("channelbind"),
        _ => {
            message.contains("stun error 437")
                || (message.contains("stun error 400") && message.contains("channelbind"))
        }
    }
}

fn should_invalidate_turn_credentials(error: &anyhow::Error, message: &str) -> bool {
    if let Some(stun_code) = turn_stun_code(error) {
        return matches!(stun_code, 401 | 438 | 441);
    }
    message.contains("turn ")
        && [
            "stun error 401",
            "stun error 438",
            "stun error 441",
            "unauthorized",
            "wrong credential",
            "authentication failed",
        ]
        .iter()
        .any(|marker| message.contains(marker))
}

fn same_turn_credentials(left: &TurnCredentials, right: &TurnCredentials) -> bool {
    left.username == right.username
        && left.password == right.password
        && left.server_addresses == right.server_addresses
}

fn is_local_network_down(error: &anyhow::Error) -> bool {
    for cause in error.chain() {
        if let Some(io_error) = cause.downcast_ref::<std::io::Error>()
            && matches!(
                io_error.raw_os_error(),
                Some(100 | 101 | 113 | 10_050 | 10_051 | 10_065)
            )
        {
            return true;
        }
    }
    let message = format!("{error:#}").to_ascii_lowercase();
    [
        "network is unreachable",
        "network is down",
        "no route to host",
        "no route to network",
        "enetunreach",
        "enetdown",
        "ehostunreach",
    ]
    .iter()
    .any(|marker| message.contains(marker))
        || [100, 101, 113, 10_050, 10_051, 10_065]
            .into_iter()
            .any(|code| contains_os_error_code(&message, code))
}

fn is_remote_tcp_stream_reset(error: &anyhow::Error, message: &str) -> bool {
    error.chain().any(|cause| {
        cause
            .downcast_ref::<std::io::Error>()
            .is_some_and(|io_error| {
                matches!(
                    io_error.kind(),
                    std::io::ErrorKind::ConnectionReset
                        | std::io::ErrorKind::ConnectionAborted
                        | std::io::ErrorKind::BrokenPipe
                )
            })
    }) || [
        "connection reset by peer",
        "connection aborted",
        "broken pipe",
    ]
    .iter()
    .any(|marker| message.contains(marker))
}

fn is_transport_timeout(message: &str) -> bool {
    [
        "transaction timeout",
        "request timeout",
        "i/o timeout",
        "timed out",
        "deadline has elapsed",
    ]
    .iter()
    .any(|marker| message.contains(marker))
}

fn contains_os_error_code(message: &str, code: i32) -> bool {
    let needle = format!("os error {code}");
    message.match_indices(&needle).any(|(index, _)| {
        message[index + needle.len()..]
            .chars()
            .next()
            .is_none_or(|character| !character.is_ascii_digit())
    })
}

pub fn parse_hashes(raw: &str) -> Vec<String> {
    let mut seen = HashSet::new();
    raw.split([',', ';', '\n', '\r', '\t', ' '])
        .filter_map(normalize_hash)
        .filter(|hash| seen.insert(hash.clone()))
        .collect()
}

fn normalize_hash(input: &str) -> Option<String> {
    let mut value = input.trim().trim_matches(['<', '>', '"', '\'']).to_owned();
    if value.is_empty() {
        return None;
    }
    let lower = value.to_ascii_lowercase();
    if let Some(index) = lower.find("/call/join/") {
        value = value[index + "/call/join/".len()..].to_owned();
    } else if lower.starts_with("http://") || lower.starts_with("https://") {
        return None;
    }
    if let Some(index) = value.find(['?', '#', '/']) {
        value.truncate(index);
    }
    let value = value.trim().trim_matches('/');
    (!value.is_empty()).then(|| value.to_owned())
}

fn group_worker_ids(group_id: usize, total_workers: usize) -> Range<usize> {
    let first = (group_id - 1) * WORKERS_PER_GROUP + 1;
    if first > total_workers {
        return first..first;
    }
    let last = (first + WORKERS_PER_GROUP).min(total_workers + 1);
    first..last
}

fn credential_stream_id(credential_id: usize) -> usize {
    credential_id * 100
}

fn group_hash_index(group_index: usize, hash_count: usize) -> usize {
    group_index % hash_count
}

fn credential_cohort_index(group_index_for_hash: usize) -> usize {
    group_index_for_hash / GROUPS_PER_CREDENTIAL
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pause_gate_blocks_new_work_until_resume() {
        let gate = Arc::new(PauseGate::new());
        let cancel = CancellationToken::new();
        gate.set_paused(true);
        let waiting_gate = gate.clone();
        let waiting_cancel = cancel.clone();
        let wait =
            tokio::spawn(async move { waiting_gate.wait_until_resumed(&waiting_cancel).await });
        tokio::task::yield_now().await;
        assert!(!wait.is_finished());
        gate.set_paused(false);
        assert!(wait.await.unwrap());
    }

    #[test]
    fn parses_and_deduplicates_vk_hashes() {
        assert_eq!(
            parse_hashes("abc, https://vk.ru/call/join/def?x=1;abc"),
            vec!["abc", "def"]
        );
    }

    #[test]
    fn rejects_unrelated_http_urls() {
        assert!(parse_hashes("https://example.com/not-a-call").is_empty());
    }

    #[test]
    fn every_credential_cohort_is_dedicated_to_one_complete_group() {
        for group_id in 1..=18 {
            let workers: Vec<_> = group_worker_ids(group_id, 18 * WORKERS_PER_GROUP).collect();
            assert_eq!(workers.len(), WORKERS_PER_GROUP);
        }
        for groups_per_hash in 1usize..=18 {
            let cohorts = groups_per_hash.div_ceil(GROUPS_PER_CREDENTIAL);
            for cohort_index in 0..cohorts {
                let assigned = (0..groups_per_hash)
                    .filter(|group_index| credential_cohort_index(*group_index) == cohort_index)
                    .count();
                assert!((1..=GROUPS_PER_CREDENTIAL).contains(&assigned));
                assert!(assigned * WORKERS_PER_GROUP <= WORKERS_PER_CREDENTIAL);
                assert_eq!(
                    credential_stream_id(cohort_index + 1),
                    (cohort_index + 1) * 100
                );
            }
        }
    }

    #[test]
    fn group_worker_ids_supports_custom_totals() {
        let workers_4: Vec<_> = group_worker_ids(1, 4).collect();
        assert_eq!(workers_4, vec![1, 2, 3, 4]);
        let workers_5: Vec<_> = group_worker_ids(1, 5).collect();
        assert_eq!(workers_5, vec![1, 2, 3, 4, 5]);
        let workers_4_grp2: Vec<_> = group_worker_ids(2, 4).collect();
        assert!(workers_4_grp2.is_empty());
        let workers_30: Vec<_> = group_worker_ids(3, 30).collect();
        assert_eq!(workers_30, (21..=30).collect::<Vec<_>>());
    }

    #[test]
    fn worker_groups_are_evenly_distributed_for_every_supported_hash_count() {
        for hash_count in 1..=6 {
            for groups in 1usize..=18 {
                let mut counts = vec![0usize; hash_count];
                for group_index in 0..groups {
                    counts[group_hash_index(group_index, hash_count)] += 1;
                }
                assert_eq!(counts.iter().sum::<usize>(), groups);
                let minimum = counts.iter().copied().min().unwrap();
                let maximum = counts.iter().copied().max().unwrap();
                assert!(maximum - minimum <= 1);
            }
        }
    }

    #[test]
    fn twenty_and_forty_workers_are_evenly_split_across_two_hashes() {
        for total in [20, 40] {
            let groups = total / WORKERS_PER_GROUP;
            let mut groups_by_hash = [0usize; 2];
            let mut cohorts_by_hash = [0usize; 2];
            for group_index in 0..groups {
                let hash_index = group_hash_index(group_index, groups_by_hash.len());
                let group_index_for_hash = groups_by_hash[hash_index];
                groups_by_hash[hash_index] += 1;
                cohorts_by_hash[hash_index] = cohorts_by_hash[hash_index]
                    .max(credential_cohort_index(group_index_for_hash) + 1);
            }
            let half_groups = groups / 2;
            assert_eq!(groups_by_hash, [half_groups, half_groups]);
            assert_eq!(cohorts_by_hash, [half_groups, half_groups]);
            assert_eq!(
                groups_by_hash.map(|g| g * WORKERS_PER_GROUP),
                [total / 2, total / 2]
            );
        }
    }

    #[test]
    fn unavailable_hash_stops_only_its_own_worker_groups() {
        let root_cancel = CancellationToken::new();
        let unavailable = HashAvailability::new(&root_cancel);
        let healthy = HashAvailability::new(&root_cancel);
        unavailable.mark_unavailable();
        assert!(unavailable.unavailable.load(Ordering::Acquire));
        assert!(unavailable.cancel.is_cancelled());
        assert!(!healthy.unavailable.load(Ordering::Acquire));
        assert!(!healthy.cancel.is_cancelled());
        assert!(!root_cancel.is_cancelled());
    }

    #[test]
    fn config_flight_is_globally_exclusive_and_released_by_drop() {
        let sent = AtomicBool::new(false);
        let in_flight = Arc::new(AtomicBool::new(false));
        let first = ConfigFlightGuard::acquire(&sent, in_flight.clone());
        assert!(first.acquired);
        let second = ConfigFlightGuard::acquire(&sent, in_flight.clone());
        assert!(!second.acquired);
        drop(first);
        let third = ConfigFlightGuard::acquire(&sent, in_flight);
        assert!(third.acquired);
    }

    #[test]
    fn delivered_config_prevents_new_flight() {
        let sent = AtomicBool::new(true);
        let in_flight = Arc::new(AtomicBool::new(false));
        let guard = ConfigFlightGuard::acquire(&sent, in_flight.clone());
        assert!(!guard.acquired);
        assert!(!in_flight.load(Ordering::Acquire));
    }

    #[tokio::test(start_paused = true)]
    async fn global_worker_start_pacer_spaces_all_max_starts_by_configured_interval() {
        let pacer = Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL));
        let cancel = CancellationToken::new();
        let started = Instant::now();
        let mut tasks = tokio::task::JoinSet::new();
        for _ in 0..100 {
            let pacer = pacer.clone();
            let cancel = cancel.clone();
            tasks.spawn(async move {
                assert!(pacer.wait(&cancel).await);
                Instant::now()
            });
        }
        let mut starts = Vec::with_capacity(100);
        while let Some(result) = tasks.join_next().await {
            starts.push(result.unwrap());
        }
        starts.sort_unstable();
        assert_eq!(starts.len(), 100);
        assert_eq!(starts[0], started);
        assert_eq!(
            starts[99].duration_since(started),
            WORKER_START_INTERVAL * 99
        );
        assert!(
            starts
                .windows(2)
                .all(|pair| pair[1].duration_since(pair[0]) == WORKER_START_INTERVAL)
        );
    }

    #[tokio::test(start_paused = true)]
    async fn cancelled_worker_start_does_not_wait_for_a_reserved_slot() {
        let pacer = WorkerStartPacer::new(WORKER_START_INTERVAL);
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(!pacer.wait(&cancel).await);
    }

    #[tokio::test(start_paused = true)]
    async fn cancelled_queued_reconnect_does_not_consume_the_next_slot() {
        let pacer = Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL));
        let live = CancellationToken::new();
        assert!(pacer.wait(&live).await);
        let cancelled = CancellationToken::new();
        let queued = {
            let pacer = pacer.clone();
            let cancelled = cancelled.clone();
            tokio::spawn(async move { pacer.wait(&cancelled).await })
        };
        tokio::task::yield_now().await;
        cancelled.cancel();
        assert!(!queued.await.unwrap());
        let before = Instant::now();
        assert!(pacer.wait(&live).await);
        assert_eq!(Instant::now() - before, WORKER_START_INTERVAL);
    }

    #[tokio::test(start_paused = true)]
    async fn isolated_reconnect_after_idle_starts_immediately() {
        let pacer = WorkerStartPacer::new(WORKER_START_INTERVAL);
        let cancel = CancellationToken::new();
        assert!(pacer.wait(&cancel).await);
        tokio::time::advance(Duration::from_secs(60)).await;
        let before = Instant::now();
        assert!(pacer.wait(&cancel).await);
        assert_eq!(Instant::now(), before);
    }

    #[test]
    fn fatal_retry_delay_doubles_from_five_seconds_to_five_minute_cap() {
        let expected_secs = [5, 10, 20, 40, 80, 160, 300, 300, 300, 300];
        for (index, expected) in expected_secs.iter().enumerate() {
            let delay = fatal_retry_delay_with_jitter(index + 1, 0);
            assert_eq!(
                delay,
                Duration::from_secs(*expected),
                "streak {}",
                index + 1
            );
        }
        assert_eq!(
            fatal_retry_delay_with_jitter(usize::MAX, 999),
            Duration::from_secs(300)
        );
    }

    #[test]
    fn worker_retry_delay_is_bounded_and_caps_at_4250_milliseconds() {
        for (attempt, minimum_ms) in [
            (0, 250),
            (1, 250),
            (2, 500),
            (3, 1_000),
            (4, 2_000),
            (5, 4_000),
            (16, 4_000),
            (usize::MAX, 4_000),
        ] {
            assert_eq!(
                worker_retry_delay_with_jitter(attempt, 0),
                Duration::from_millis(minimum_ms)
            );
            assert_eq!(
                worker_retry_delay_with_jitter(attempt, u64::MAX),
                Duration::from_millis(minimum_ms + 250)
            );
        }
    }

    #[test]
    fn worker_retry_delay_is_monotonic_for_every_jitter_value() {
        for jitter_ms in 0..=250 {
            let delays: Vec<_> = (1..=32)
                .map(|attempt| worker_retry_delay_with_jitter(attempt, jitter_ms))
                .collect();
            assert!(delays.windows(2).all(|window| window[0] <= window[1]));
            assert_eq!(delays[0], Duration::from_millis(250 + jitter_ms));
            assert_eq!(delays[4], Duration::from_millis(4_000 + jitter_ms));
            assert_eq!(delays[31], Duration::from_millis(4_000 + jitter_ms));
        }
    }

    #[test]
    fn tcp_stream_reset_uses_the_short_capped_retry_ladder() {
        let delays: Vec<_> = (1..=8).map(tcp_stream_retry_delay).collect();
        assert_eq!(
            delays,
            vec![
                Duration::from_millis(100),
                Duration::from_millis(300),
                Duration::from_millis(500),
                Duration::from_millis(800),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
            ]
        );
    }

    #[test]
    fn tcp_stream_reset_is_distinguished_from_a_local_route_failure() {
        let reset = anyhow::Error::new(std::io::Error::from_raw_os_error(104));
        assert!(is_remote_tcp_stream_reset(
            &reset,
            "turn stream read failed: connection reset by peer (os error 104)"
        ));
        let route = anyhow::Error::new(std::io::Error::from_raw_os_error(101));
        assert!(!is_remote_tcp_stream_reset(
            &route,
            "turn stream read failed: network is unreachable (os error 101)"
        ));
    }

    #[test]
    fn recovery_log_gate_coalesces_storms_without_losing_counts() {
        let gate = RecoveryLogGate::new();
        assert_eq!(gate.observe(0), Some(1));
        for now in 1..10_000 {
            assert_eq!(gate.observe(now), None);
        }
        assert_eq!(gate.observe(10_000), Some(10_000));
        for _ in 0..100_000 {
            assert_eq!(gate.observe(10_001), None);
        }
        assert_eq!(gate.observe(20_000), Some(100_001));
    }

    #[test]
    fn turn_credentials_are_invalidated_after_exhausted_auth_but_not_quota_rejection() {
        let error = anyhow::anyhow!("TURN Allocate failed: unauthorized; STUN error 401");
        assert!(should_invalidate_turn_credentials(
            &error,
            "turn allocate failed: unauthorized; stun error 401"
        ));
        let error = anyhow::anyhow!("TURN Allocate failed: wrong credentials; STUN error 441");
        assert!(should_invalidate_turn_credentials(
            &error,
            "turn allocate failed: wrong credentials; stun error 441"
        ));
        let error = anyhow::Error::new(TurnRequestError::new(0, 0, 438));
        assert!(should_invalidate_turn_credentials(
            &error,
            "turn refresh failed: stun error 438"
        ));
        let error =
            anyhow::anyhow!("TURN Allocate failed: Allocation Quota Reached; STUN error 486");
        assert!(!should_invalidate_turn_credentials(
            &error,
            "turn allocate failed: allocation quota reached; stun error 486"
        ));
        let error = anyhow::anyhow!("TURN ChannelBind hard timeout");
        assert!(!should_invalidate_turn_credentials(
            &error,
            "turn channelbind hard timeout"
        ));
        let error = anyhow::anyhow!("TURN Allocate hard timeout");
        assert!(!should_invalidate_turn_credentials(
            &error,
            "turn allocate hard timeout"
        ));
        let error = anyhow::anyhow!("TURN Allocate failed: Unknown Attribute; STUN error 420");
        assert!(!should_invalidate_turn_credentials(
            &error,
            "turn allocate failed: unknown attribute; stun error 420"
        ));
        let error = anyhow::anyhow!("TURN Allocate failed: Insufficient Capacity; STUN error 508");
        assert!(!should_invalidate_turn_credentials(
            &error,
            "turn allocate failed: insufficient capacity; stun error 508"
        ));
    }

    #[test]
    fn allocation_quota_is_recognized_structurally() {
        let error = anyhow::anyhow!("TURN Allocate failed: STUN error 486");
        assert!(is_turn_allocation_quota(
            &error,
            "turn allocate failed: stun error 486"
        ));
    }

    #[test]
    fn credential_identity_includes_secret_and_not_only_turn_addresses() {
        let addresses: Arc<[Arc<str>]> = Arc::from([Arc::from("turn.example:3478")]);
        let first = TurnCredentials {
            username: Arc::from("first"),
            password: Arc::from("secret-a"),
            server_addresses: addresses.clone(),
        };
        let same = first.clone();
        let replacement = TurnCredentials {
            username: Arc::from("second"),
            password: Arc::from("secret-b"),
            server_addresses: addresses,
        };
        assert!(same_turn_credentials(&first, &same));
        assert!(!same_turn_credentials(&first, &replacement));
    }

    #[test]
    fn credential_cache_never_reuses_a_credential_after_nine_allocate_attempts() {
        let addresses: Arc<[Arc<str>]> = Arc::from([Arc::from("turn.example:3478")]);
        let credentials = TurnCredentials {
            username: Arc::from("first"),
            password: Arc::from("secret-a"),
            server_addresses: addresses,
        };
        let mut cache = CredentialCache::default();
        cache.store(credentials.clone());
        for _ in 0..WORKERS_PER_CREDENTIAL {
            let lease = cache.reserve().expect("allocation slot must be available");
            cache.record_allocation(&lease);
        }
        assert!(cache.current.is_some());
        assert!(cache.reserve().is_none());
        assert!(cache.current.is_none());
    }

    #[test]
    fn quota_rotation_is_bounded_and_resets_after_successful_allocate() {
        let addresses: Arc<[Arc<str>]> = Arc::from([Arc::from("turn.example:3478")]);
        let first = TurnCredentials {
            username: Arc::from("first"),
            password: Arc::from("secret-a"),
            server_addresses: addresses.clone(),
        };
        let second = TurnCredentials {
            username: Arc::from("second"),
            password: Arc::from("secret-b"),
            server_addresses: addresses.clone(),
        };
        let third = TurnCredentials {
            username: Arc::from("third"),
            password: Arc::from("secret-c"),
            server_addresses: addresses,
        };
        let mut cache = CredentialCache::default();
        cache.store(first.clone());
        let first_lease = cache.reserve().unwrap();
        assert!(matches!(
            cache.rotate_after_quota(&first_lease),
            QuotaRotation::Rotated(delay) if delay == Duration::from_millis(100)
        ));
        assert!(cache.store(second.clone()));
        let second_lease = cache.reserve().unwrap();
        assert!(matches!(
            cache.rotate_after_quota(&second_lease),
            QuotaRotation::Rotated(delay) if delay == Duration::from_millis(300)
        ));
        assert_eq!(
            (0..8).map(quota_rotation_delay).collect::<Vec<_>>(),
            vec![
                Duration::from_millis(100),
                Duration::from_millis(300),
                Duration::from_millis(500),
                Duration::from_millis(800),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
                Duration::from_millis(1_500),
            ]
        );
        assert!(cache.store(third));
        let successful_lease = cache.reserve().unwrap();
        cache.record_allocation(&successful_lease);
        let reset_lease = cache.reserve().unwrap();
        assert!(matches!(
            cache.rotate_after_quota(&reset_lease),
            QuotaRotation::Rotated(delay) if delay == Duration::from_millis(100)
        ));
    }

    #[test]
    fn quota_rejected_credentials_are_never_reused() {
        let addresses: Arc<[Arc<str>]> = Arc::from([Arc::from("turn.example:3478")]);
        let rejected = TurnCredentials {
            username: Arc::from("first"),
            password: Arc::from("secret-a"),
            server_addresses: addresses.clone(),
        };
        let fresh = TurnCredentials {
            username: Arc::from("second"),
            password: Arc::from("secret-b"),
            server_addresses: addresses,
        };
        let mut cache = CredentialCache::default();
        assert!(cache.store(rejected.clone()));
        let rejected_lease = cache.reserve().unwrap();
        assert!(matches!(
            cache.rotate_after_quota(&rejected_lease),
            QuotaRotation::Rotated(_)
        ));
        assert!(!cache.store(rejected));
        assert!(cache.store(fresh));
    }

    #[test]
    fn allocation_mismatch_retries_once_then_rotates_and_resets_after_allocate() {
        let addresses: Arc<[Arc<str>]> = Arc::from([Arc::from("turn.example:3478")]);
        let first = TurnCredentials {
            username: Arc::from("first"),
            password: Arc::from("secret-a"),
            server_addresses: addresses.clone(),
        };
        let second = TurnCredentials {
            username: Arc::from("second"),
            password: Arc::from("secret-b"),
            server_addresses: addresses,
        };
        let mut cache = CredentialCache::default();
        cache.store(first);
        let first_attempt = cache.reserve().unwrap();
        assert!(matches!(
            cache.retry_after_mismatch(&first_attempt),
            AllocationMismatch::RetryCurrent
        ));
        let retry_attempt = cache.reserve().unwrap();
        assert!(matches!(
            cache.retry_after_mismatch(&retry_attempt),
            AllocationMismatch::Rotated
        ));
        cache.store(second);
        let successful_attempt = cache.reserve().unwrap();
        cache.record_allocation(&successful_attempt);
        let next_attempt = cache.reserve().unwrap();
        assert!(matches!(
            cache.retry_after_mismatch(&next_attempt),
            AllocationMismatch::RetryCurrent
        ));
    }

    #[test]
    fn allocation_mismatch_classifier_accepts_437_and_channelbind_400_only() {
        let error = anyhow::Error::new(TurnRequestError::new(0, 0, 437));
        assert!(is_turn_allocation_mismatch(
            &error,
            "turn refresh failed: stun error 437"
        ));
        let error = anyhow::Error::new(TurnRequestError::new(0, 0, 400));
        assert!(is_turn_allocation_mismatch(
            &error,
            "turn channelbind failed: stun error 400"
        ));
        assert!(!is_turn_allocation_mismatch(
            &error,
            "turn allocate failed: stun error 400"
        ));
    }

    #[test]
    fn local_network_classifier_uses_io_codes_and_not_generic_timeouts() {
        assert!(is_local_network_down(&anyhow::Error::new(
            std::io::Error::from_raw_os_error(101)
        )));
        assert!(is_local_network_down(&anyhow::anyhow!(
            "writer: Network is unreachable (os error 101)"
        )));
        assert!(!is_local_network_down(&anyhow::anyhow!(
            "reader: read udp 127.0.0.1:3478: i/o timeout"
        )));
        assert!(!is_local_network_down(&anyhow::anyhow!(
            "PEER_LIVENESS_TIMEOUT"
        )));
        assert!(!is_local_network_down(&anyhow::anyhow!(
            "connect failed (os error 10061)"
        )));
        assert!(!is_local_network_down(&anyhow::anyhow!(
            "connect timed out (os error 10060)"
        )));
    }

    #[test]
    fn transport_timeout_classifier_ignores_non_timeout_turn_errors() {
        assert!(is_transport_timeout("turn refresh transaction timeout"));
        assert!(is_transport_timeout("reader: i/o timeout"));
        assert!(is_transport_timeout("operation timed out"));
        assert!(!is_transport_timeout("turn channelbind: stun error 438"));
        assert!(!is_transport_timeout("turn allocate: stun error 486"));
    }

    #[test]
    fn credential_failure_ladder_matches_anti_flood_spec() {
        assert_eq!(credential_failure_delay(0), Duration::from_secs(2));
        assert_eq!(credential_failure_delay(1), Duration::from_secs(3));
        assert_eq!(credential_failure_delay(2), Duration::from_secs(5));
        assert_eq!(credential_failure_delay(3), Duration::from_secs(10));
        assert_eq!(credential_failure_delay(4), Duration::from_secs(15));
        assert_eq!(credential_failure_delay(5), Duration::from_secs(20));
        assert_eq!(credential_failure_delay(6), Duration::from_secs(30));
        assert_eq!(credential_failure_delay(100), Duration::from_secs(30));
    }
}
