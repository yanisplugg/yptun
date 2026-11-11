//! The ping pass of the location list (port of `LocationViewModel.refreshPings` & co): the
//! locations are probed at most `parallelism` at a time; a pass can be paused (pings in flight
//! finish, no new one starts), resumed and cancelled, and shows an estimate of the time left.
//! Several targeted refreshes running together form one pass with one progress.

use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::location::LocationConfig;
use crate::location_list::LocationItem;
use crate::settings::MAX_PING_PARALLELISM;

/// Attempts per location: a probe that spins a throwaway core can lose a port race under a big
/// batch, so a failure is retried before the location shows as unavailable.
pub const LOCATION_PING_ATTEMPTS: u32 = 3;
/// Default budget of one location's whole ping, retries included.
pub const LOCATION_PING_TIMEOUT_MS: u64 = 12_000;
pub const LOCATION_PING_RETRY_DELAY_MS: u64 = 150;
pub const LOCATION_PING_PARALLELISM: i32 = 5;
/// At most one state emission per this many ms while a pass runs (300+ completions would
/// otherwise redraw the whole list 300 times).
pub const PING_EMIT_THROTTLE_MS: u64 = 150;
/// Completed pings needed before the pass shows an estimated time to the end.
pub const PING_ETA_MIN_SAMPLES: usize = 5;

/// Ping results by storage id: the round trip in ms, `None` = probed and unreachable.
pub type Pings = BTreeMap<String, Option<i32>>;

/// Probes one location: the round trip in ms or `None`. The second argument is what is left of the
/// location's budget; a probe should not wait longer.
pub type PingFn = Arc<dyn Fn(&LocationConfig, Duration) -> Option<i64> + Send + Sync>;

/// Called once every location of a refresh has its result: (answered, pinged).
pub type CompletionFn = Box<dyn FnOnce(usize, usize) + Send>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PingsState {
    Idle,
    Loading(PingProgress),
    Success {
        pings: Pings,
    },
    Error {
        message: String,
        last_pings: Option<Pings>,
    },
}

/// A running pass.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PingProgress {
    pub last_pings: Option<Pings>,
    pub current_pings: Pings,
    pub pending_location_ids: BTreeSet<String>,
    /// Locations of the whole pass that are done / the pass's size.
    pub completed: usize,
    pub total: usize,
    /// Held: pings in flight finish, no new one starts until resumed.
    pub paused: bool,
    /// Seconds to the end of the pass; `None` while paused or before there are enough samples.
    pub eta_seconds: Option<i32>,
}

impl PingsState {
    /// The results this state shows (`currentPingsSnapshot`).
    pub fn pings(&self) -> Pings {
        match self {
            PingsState::Idle => Pings::new(),
            PingsState::Loading(p) if p.current_pings.is_empty() => {
                p.last_pings.clone().unwrap_or_default()
            }
            PingsState::Loading(p) => p.current_pings.clone(),
            PingsState::Success { pings } => pings.clone(),
            PingsState::Error { last_pings, .. } => last_pings.clone().unwrap_or_default(),
        }
    }

    pub fn is_loading(&self) -> bool {
        matches!(self, PingsState::Loading(_))
    }
}

/// Seconds left at the pace so far: `active_ms` spent on `done` results, `remaining` to go.
/// `None` with fewer than [`PING_ETA_MIN_SAMPLES`] results or no time measured yet.
pub fn eta_seconds(active_ms: i64, done: usize, remaining: usize) -> Option<i32> {
    if done < PING_ETA_MIN_SAMPLES || active_ms <= 0 {
        return None;
    }
    Some((active_ms as f64 / done as f64 * remaining as f64 / 1000.0) as i32)
}

/// One location's ping: up to [`LOCATION_PING_ATTEMPTS`] probes [`LOCATION_PING_RETRY_DELAY_MS`]
/// apart, all within `budget`; `None` for an incomplete config, no answer or a blown budget.
pub fn check_location_ping(
    config: &LocationConfig,
    budget: Duration,
    ping: &dyn Fn(&LocationConfig, Duration) -> Option<i64>,
) -> Option<i64> {
    check_ping(config, budget, ping, &|| false)
}

fn check_ping(
    config: &LocationConfig,
    budget: Duration,
    ping: &dyn Fn(&LocationConfig, Duration) -> Option<i64>,
    stopped: &dyn Fn() -> bool,
) -> Option<i64> {
    if !config.is_complete() {
        return None;
    }
    let deadline = Instant::now() + budget;
    let retry = Duration::from_millis(LOCATION_PING_RETRY_DELAY_MS);
    for attempt in 0..LOCATION_PING_ATTEMPTS {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() || stopped() {
            return None;
        }
        // A probe that panics counts as no answer, like one that throws.
        let result = catch_unwind(AssertUnwindSafe(|| ping(config, left)))
            .ok()
            .flatten();
        if Instant::now() > deadline {
            return None;
        }
        if result.is_some() {
            return result;
        }
        if attempt + 1 < LOCATION_PING_ATTEMPTS {
            if deadline.saturating_duration_since(Instant::now()) <= retry {
                return None;
            }
            thread::sleep(retry);
        }
    }
    None
}

/// What one refresh pings with.
pub struct PingRequest {
    /// Only these storage ids (`None` = every complete location).
    pub targets: Option<Vec<String>>,
    /// Locations probed at once, clamped to `1..=MAX_PING_PARALLELISM`.
    pub parallelism: i32,
    /// Budget of one location's whole ping.
    pub budget: Duration,
    pub ping: PingFn,
    pub on_complete: Option<CompletionFn>,
}

impl PingRequest {
    pub fn new(ping: PingFn) -> Self {
        Self {
            targets: None,
            parallelism: LOCATION_PING_PARALLELISM,
            budget: Duration::from_millis(LOCATION_PING_TIMEOUT_MS),
            ping,
            on_complete: None,
        }
    }

    pub fn targets(mut self, ids: Vec<String>) -> Self {
        self.targets = Some(ids);
        self
    }

    pub fn parallelism(mut self, parallelism: i32) -> Self {
        self.parallelism = parallelism;
        self
    }

    pub fn budget(mut self, budget: Duration) -> Self {
        self.budget = budget;
        self
    }

    pub fn on_complete(mut self, f: impl FnOnce(usize, usize) + Send + 'static) -> Self {
        self.on_complete = Some(Box::new(f));
        self
    }
}

/// The worker threads of one refresh; [`PingHandle::wait`] blocks until they are done (every
/// location answered, or the pass was cancelled and the pings in flight returned).
pub struct PingHandle {
    workers: Vec<JoinHandle<()>>,
}

impl PingHandle {
    pub fn wait(self) {
        for worker in self.workers {
            let _ = worker.join();
        }
    }
}

type StateListener = Arc<dyn Fn(&PingsState) + Send + Sync>;
type CompletedListener = Arc<dyn Fn(&Pings) + Send + Sync>;

/// The list's ping passes. Cheap to clone; every clone drives the same passes.
#[derive(Clone)]
pub struct PingSession {
    inner: Arc<Inner>,
}

struct Inner {
    pass: Mutex<Pass>,
    wake: Condvar,
    clock: Box<dyn Fn() -> i64 + Send + Sync>,
    listener: Mutex<Option<StateListener>>,
    on_completed: Mutex<Option<CompletedListener>>,
}

struct Pass {
    /// The source of truth during a pass, updated in place by every result.
    live: Pings,
    /// Storage id → the refresh that pings it.
    active: BTreeMap<String, u64>,
    /// The last emitted state: what shows while nothing is pinged.
    emitted: PingsState,
    paused: bool,
    total: usize,
    done: usize,
    start_ms: i64,
    paused_accum_ms: i64,
    paused_since_ms: i64,
    next_request: u64,
    /// Bumped by a cancel: workers of an older epoch stop.
    epoch: u64,
    emit_generation: u64,
    emit_pending: bool,
}

struct Refresh {
    id: u64,
    epoch: u64,
    queue: Mutex<VecDeque<(String, LocationConfig)>>,
    total: usize,
    completed: AtomicUsize,
    online: AtomicUsize,
    budget: Duration,
    ping: PingFn,
    on_complete: Mutex<Option<CompletionFn>>,
}

impl Default for PingSession {
    fn default() -> Self {
        Self::new()
    }
}

impl PingSession {
    pub fn new() -> Self {
        Self::with_clock(Box::new(|| {
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_or(0, |d| d.as_millis() as i64)
        }))
    }

    /// A session whose estimate reads time (epoch ms) from `clock`.
    pub fn with_clock(clock: Box<dyn Fn() -> i64 + Send + Sync>) -> Self {
        Self {
            inner: Arc::new(Inner {
                pass: Mutex::new(Pass {
                    live: Pings::new(),
                    active: BTreeMap::new(),
                    emitted: PingsState::Idle,
                    paused: false,
                    total: 0,
                    done: 0,
                    start_ms: 0,
                    paused_accum_ms: 0,
                    paused_since_ms: 0,
                    next_request: 0,
                    epoch: 0,
                    emit_generation: 0,
                    emit_pending: false,
                }),
                wake: Condvar::new(),
                clock,
                listener: Mutex::new(None),
                on_completed: Mutex::new(None),
            }),
        }
    }

    /// Receives every state change; while a pass runs, at most one per [`PING_EMIT_THROTTLE_MS`].
    pub fn set_listener(&self, listener: impl Fn(&PingsState) + Send + Sync + 'static) {
        *lock(&self.inner.listener) = Some(Arc::new(listener));
    }

    /// Receives all results (failed ones as `None`) whenever a refresh completes, to persist them.
    pub fn set_on_completed(&self, listener: impl Fn(&Pings) + Send + Sync + 'static) {
        *lock(&self.inner.on_completed) = Some(Arc::new(listener));
    }

    /// The state right now.
    pub fn state(&self) -> PingsState {
        let pass = self.inner.lock_pass();
        self.inner.current(&pass)
    }

    /// The results shown right now (`currentPingsSnapshot`).
    pub fn pings(&self) -> Pings {
        self.state().pings()
    }

    pub fn is_running(&self) -> bool {
        !self.inner.lock_pass().active.is_empty()
    }

    /// Pings the complete locations of `items` (those of `request.targets` when given) that no
    /// running refresh pings already. Results of the other locations stay.
    pub fn refresh(&self, items: &[LocationItem], request: PingRequest) -> PingHandle {
        let inner = &self.inner;
        let permits = request.parallelism.clamp(1, MAX_PING_PARALLELISM) as usize;
        let mut pass = inner.lock_pass();
        let previous = inner.current(&pass).pings();
        pass.live = previous.clone();

        if items.is_empty() {
            let state = pass.active.is_empty().then(|| {
                pass.emitted = PingsState::Success {
                    pings: Pings::new(),
                };
                pass.emitted.clone()
            });
            drop(pass);
            if let Some(state) = state {
                inner.notify(&state);
            }
            if let Some(done) = request.on_complete {
                done(0, 0);
            }
            return PingHandle {
                workers: Vec::new(),
            };
        }

        let mut queue = VecDeque::new();
        let mut queued = BTreeSet::new();
        for item in items {
            let Some(config) = item.config.as_ref().filter(|c| c.is_complete()) else {
                continue;
            };
            let targeted = request
                .targets
                .as_ref()
                .is_none_or(|t| t.contains(&item.storage_id));
            if targeted
                && !pass.active.contains_key(&item.storage_id)
                && queued.insert(item.storage_id.clone())
            {
                queue.push_back((item.storage_id.clone(), config.clone()));
            }
        }

        if queue.is_empty() {
            let state = inner.emit(&mut pass, Some(previous));
            drop(pass);
            inner.notify(&state);
            if let Some(done) = request.on_complete {
                done(0, 0);
            }
            return PingHandle {
                workers: Vec::new(),
            };
        }

        if pass.active.is_empty() {
            pass.total = 0;
            pass.done = 0;
            pass.start_ms = (inner.clock)();
            pass.paused_accum_ms = 0;
            pass.paused_since_ms = 0;
        }
        pass.total += queue.len();
        pass.next_request += 1;
        let id = pass.next_request;
        for (storage_id, _) in &queue {
            pass.active.insert(storage_id.clone(), id);
        }
        let workers = permits.min(queue.len());
        let refresh = Arc::new(Refresh {
            id,
            epoch: pass.epoch,
            total: queue.len(),
            queue: Mutex::new(queue),
            completed: AtomicUsize::new(0),
            online: AtomicUsize::new(0),
            budget: request.budget,
            ping: request.ping,
            on_complete: Mutex::new(request.on_complete),
        });
        let state = inner.emit(&mut pass, Some(previous));
        drop(pass);
        inner.notify(&state);

        let workers = (0..workers)
            .map(|_| {
                let inner = Arc::clone(&self.inner);
                let refresh = Arc::clone(&refresh);
                thread::spawn(move || inner.work(&refresh))
            })
            .collect();
        PingHandle { workers }
    }

    /// Holds the running pass: pings in flight finish, no new one starts until [`Self::resume`].
    pub fn pause(&self) {
        let mut pass = self.inner.lock_pass();
        if pass.active.is_empty() || pass.paused {
            return;
        }
        pass.paused_since_ms = (self.inner.clock)();
        pass.paused = true;
        let state = self.inner.emit(&mut pass, None);
        drop(pass);
        self.inner.notify(&state);
    }

    pub fn resume(&self) {
        let mut pass = self.inner.lock_pass();
        if !pass.paused {
            return;
        }
        pass.paused_accum_ms += (self.inner.clock)() - pass.paused_since_ms;
        pass.paused_since_ms = 0;
        pass.paused = false;
        let state = self.inner.emit(&mut pass, None);
        drop(pass);
        self.inner.wake.notify_all();
        self.inner.notify(&state);
    }

    /// Drops every ping that has not finished (results already in stay). The locations leave the
    /// pass at once, so a refresh right after (the "restart" button) is not skipped as running.
    pub fn cancel(&self) {
        let mut pass = self.inner.lock_pass();
        pass.paused = false;
        pass.paused_since_ms = 0;
        pass.active.clear();
        pass.epoch += 1;
        let state = self.inner.emit(&mut pass, None);
        drop(pass);
        self.inner.wake.notify_all();
        self.inner.notify(&state);
    }

    /// Shows previously saved results (unreachable ones as `None`) until the next pass.
    pub fn seed(&self, pings: &Pings) {
        if pings.is_empty() {
            return;
        }
        let mut pass = self.inner.lock_pass();
        pass.live
            .extend(pings.iter().map(|(id, ms)| (id.clone(), *ms)));
        let state = pass.active.is_empty().then(|| {
            pass.emitted = PingsState::Success {
                pings: pass.live.clone(),
            };
            pass.emitted.clone()
        });
        drop(pass);
        if let Some(state) = state {
            self.inner.notify(&state);
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Inner {
    fn lock_pass(&self) -> MutexGuard<'_, Pass> {
        lock(&self.pass)
    }

    /// The state an emission would publish now.
    fn current(&self, pass: &Pass) -> PingsState {
        if pass.active.is_empty() {
            pass.emitted.clone()
        } else {
            self.loading(pass, pass.live.clone())
        }
    }

    fn loading(&self, pass: &Pass, pings: Pings) -> PingsState {
        let pending: BTreeSet<String> = pass.active.keys().cloned().collect();
        let eta = if pass.paused {
            None
        } else {
            eta_seconds(
                (self.clock)() - pass.start_ms - pass.paused_accum_ms,
                pass.done,
                pass.total.saturating_sub(pass.done),
            )
        };
        PingsState::Loading(PingProgress {
            last_pings: Some(pings.clone()),
            current_pings: pings,
            completed: pass.done.min(pass.total),
            total: pass.total.max(pending.len()),
            pending_location_ids: pending,
            paused: pass.paused,
            eta_seconds: eta,
        })
    }

    /// `emitPingState`: publishes `pings` (the live results by default) as the current state.
    fn emit(&self, pass: &mut Pass, pings: Option<Pings>) -> PingsState {
        let pings = pings.unwrap_or_else(|| pass.live.clone());
        let state = if pass.active.is_empty() {
            pass.paused = false;
            pass.paused_since_ms = 0;
            PingsState::Success { pings }
        } else {
            self.loading(pass, pings)
        };
        pass.emitted = state.clone();
        state
    }

    fn notify(&self, state: &PingsState) {
        let listener = lock(&self.listener).clone();
        if let Some(listener) = listener {
            listener(state);
        }
    }

    /// Coalesces the emissions of a running pass into one per [`PING_EMIT_THROTTLE_MS`].
    fn schedule_emit(self: &Arc<Self>, pass: &mut Pass) {
        if pass.emit_pending || lock(&self.listener).is_none() {
            return;
        }
        pass.emit_pending = true;
        let generation = pass.emit_generation;
        let inner = Arc::clone(self);
        thread::spawn(move || {
            thread::sleep(Duration::from_millis(PING_EMIT_THROTTLE_MS));
            let mut pass = inner.lock_pass();
            if pass.emit_generation != generation {
                return;
            }
            pass.emit_pending = false;
            let state = inner.emit(&mut pass, None);
            drop(pass);
            inner.notify(&state);
        });
    }

    /// One worker of a refresh: takes the next location, waiting out a pause, until none is left
    /// or the pass is cancelled.
    fn work(self: &Arc<Self>, refresh: &Refresh) {
        loop {
            let (storage_id, config) = {
                let mut pass = self.lock_pass();
                while pass.paused && pass.epoch == refresh.epoch {
                    pass = self.wake.wait(pass).unwrap_or_else(|e| e.into_inner());
                }
                if pass.epoch != refresh.epoch {
                    return;
                }
                let mut queue = lock(&refresh.queue);
                loop {
                    match queue.pop_front() {
                        None => return,
                        Some((id, config)) if pass.active.get(&id) == Some(&refresh.id) => {
                            break (id, config)
                        }
                        Some(_) => {}
                    }
                }
            };
            let stopped = || self.lock_pass().epoch != refresh.epoch;
            let ping =
                check_ping(&config, refresh.budget, &*refresh.ping, &stopped).map(|ms| ms as i32);
            self.finish(refresh, storage_id, ping);
        }
    }

    fn finish(self: &Arc<Self>, refresh: &Refresh, storage_id: String, ping: Option<i32>) {
        let mut pass = self.lock_pass();
        if pass.active.get(&storage_id) != Some(&refresh.id) {
            return; // cancelled while in flight
        }
        pass.active.remove(&storage_id);
        pass.live.insert(storage_id, ping);
        if ping.is_some() {
            refresh.online.fetch_add(1, Ordering::SeqCst);
        }
        let completed = refresh.completed.fetch_add(1, Ordering::SeqCst) + 1;
        pass.done += 1;
        if completed < refresh.total {
            self.schedule_emit(&mut pass);
            return;
        }
        // The refresh's last result: publish at once, dropping a pending throttled emission.
        pass.emit_generation += 1;
        pass.emit_pending = false;
        let state = self.emit(&mut pass, None);
        let results = pass.live.clone();
        drop(pass);
        self.notify(&state);
        let on_completed = lock(&self.on_completed).clone();
        if let Some(on_completed) = on_completed {
            on_completed(&results);
        }
        let done = lock(&refresh.on_complete).take();
        if let Some(done) = done {
            done(refresh.online.load(Ordering::SeqCst), refresh.total);
        }
    }
}
