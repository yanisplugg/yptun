// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crossbeam_queue::ArrayQueue;
use std::{
    fmt,
    io::{self, Write},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc, Condvar, LazyLock, Mutex,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

const PROTOCOL_CAPACITY: usize = 128;
const DIAGNOSTIC_CAPACITY: usize = 512;
const MAX_LINE_BYTES: usize = 4096;
const PROTOCOL_BURST: usize = 32;
const COALESCE_BATCH: usize = 64;

struct BoundedLine {
    value: String,
    full: bool,
}

impl BoundedLine {
    fn new() -> Self {
        Self {
            value: String::with_capacity(256),
            full: false,
        }
    }
}

impl fmt::Write for BoundedLine {
    fn write_str(&mut self, value: &str) -> fmt::Result {
        if self.full {
            return Ok(());
        }
        let remaining = MAX_LINE_BYTES.saturating_sub(self.value.len());
        if value.len() <= remaining {
            self.value.push_str(value);
            return Ok(());
        }
        let mut end = remaining.min(value.len());
        while !value.is_char_boundary(end) {
            end -= 1;
        }
        self.value.push_str(&value[..end]);
        self.full = true;
        Ok(())
    }
}

struct LogLine {
    value: Box<str>,
}

struct Shared {
    protocol: ArrayQueue<LogLine>,
    diagnostic: ArrayQueue<LogLine>,
    protocol_outstanding: AtomicUsize,
    diagnostic_outstanding: AtomicUsize,
    producers: AtomicUsize,
    dropped_protocol: AtomicU64,
    dropped_diagnostic: AtomicU64,
    accepting: AtomicBool,
    shutdown: AtomicBool,
    stopped: AtomicBool,
    flush_requested: AtomicU64,
    flush_completed: AtomicU64,
    wake_generation: AtomicU64,
    wake_lock: Mutex<()>,
    wake: Condvar,
    completion_lock: Mutex<()>,
    completion: Condvar,
}

impl Shared {
    fn new(protocol_capacity: usize, diagnostic_capacity: usize) -> Self {
        Self {
            protocol: ArrayQueue::new(protocol_capacity.max(1)),
            diagnostic: ArrayQueue::new(diagnostic_capacity.max(1)),
            protocol_outstanding: AtomicUsize::new(0),
            diagnostic_outstanding: AtomicUsize::new(0),
            producers: AtomicUsize::new(0),
            dropped_protocol: AtomicU64::new(0),
            dropped_diagnostic: AtomicU64::new(0),
            accepting: AtomicBool::new(true),
            shutdown: AtomicBool::new(false),
            stopped: AtomicBool::new(false),
            flush_requested: AtomicU64::new(0),
            flush_completed: AtomicU64::new(0),
            wake_generation: AtomicU64::new(0),
            wake_lock: Mutex::new(()),
            wake: Condvar::new(),
            completion_lock: Mutex::new(()),
            completion: Condvar::new(),
        }
    }

    fn begin_producer(&self) -> bool {
        if !self.accepting.load(Ordering::Acquire) {
            return false;
        }
        self.producers.fetch_add(1, Ordering::AcqRel);
        if self.accepting.load(Ordering::Acquire) {
            true
        } else {
            self.finish_producer();
            false
        }
    }

    fn finish_producer(&self) {
        self.producers.fetch_sub(1, Ordering::AcqRel);
        self.notify_writer();
    }

    fn push_protocol(&self, line: LogLine) {
        Self::replace_oldest(
            &self.protocol,
            &self.protocol_outstanding,
            &self.dropped_protocol,
            line,
        );
        self.notify_writer();
    }

    fn push_diagnostic(&self, line: LogLine) {
        Self::replace_oldest(
            &self.diagnostic,
            &self.diagnostic_outstanding,
            &self.dropped_diagnostic,
            line,
        );
        self.notify_writer();
    }

    fn replace_oldest(
        queue: &ArrayQueue<LogLine>,
        outstanding: &AtomicUsize,
        dropped: &AtomicU64,
        line: LogLine,
    ) {
        outstanding.fetch_add(1, Ordering::AcqRel);
        let mut pending = line;
        for _ in 0..4 {
            match queue.push(pending) {
                Ok(()) => return,
                Err(returned) => pending = returned,
            }
            if queue.pop().is_some() {
                outstanding.fetch_sub(1, Ordering::AcqRel);
                dropped.fetch_add(1, Ordering::Relaxed);
            }
        }
        outstanding.fetch_sub(1, Ordering::AcqRel);
        dropped.fetch_add(1, Ordering::Relaxed);
    }

    fn notify_writer(&self) {
        self.wake_generation.fetch_add(1, Ordering::Release);
        self.wake.notify_one();
    }

    fn idle(&self) -> bool {
        self.protocol_outstanding.load(Ordering::Acquire) == 0
            && self.diagnostic_outstanding.load(Ordering::Acquire) == 0
            && self.producers.load(Ordering::Acquire) == 0
    }

    fn complete_flush(&self, generation: u64) {
        let _guard = self
            .completion_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        self.flush_completed.store(generation, Ordering::Release);
        self.completion.notify_all();
    }

    fn mark_stopped(&self) {
        let _guard = self
            .completion_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        self.stopped.store(true, Ordering::Release);
        self.completion.notify_all();
    }
}

struct Sink<W> {
    writer: W,
    disabled: bool,
}

impl<W: Write> Sink<W> {
    fn new(writer: W) -> Self {
        Self {
            writer,
            disabled: false,
        }
    }

    fn write_line(&mut self, value: &str, repetitions: usize) {
        if self.disabled {
            return;
        }
        let result = catch_unwind(AssertUnwindSafe(|| {
            self.writer.write_all(value.as_bytes())?;
            if repetitions > 1 {
                write!(self.writer, " (x{repetitions})")?;
            }
            self.writer.write_all(b"\n")
        }));
        if !matches!(result, Ok(Ok(()))) {
            self.disabled = true;
        }
    }

    fn flush(&mut self) {
        if self.disabled {
            return;
        }
        let result = catch_unwind(AssertUnwindSafe(|| self.writer.flush()));
        if !matches!(result, Ok(Ok(()))) {
            self.disabled = true;
        }
    }
}

pub struct Logger {
    shared: Arc<Shared>,
    writer: Mutex<Option<JoinHandle<()>>>,
}

impl Logger {
    fn with_writers<Out, Err>(
        stdout: Out,
        stderr: Err,
        protocol_capacity: usize,
        diagnostic_capacity: usize,
    ) -> Self
    where
        Out: Write + Send + 'static,
        Err: Write + Send + 'static,
    {
        let shared = Arc::new(Shared::new(protocol_capacity, diagnostic_capacity));
        let writer_shared = shared.clone();
        let handle = thread::Builder::new()
            .name("csqtt-log-writer".to_string())
            .spawn(move || {
                let result = catch_unwind(AssertUnwindSafe(|| {
                    writer_loop(writer_shared.clone(), Sink::new(stdout), Sink::new(stderr));
                }));
                if result.is_err() {
                    writer_shared.accepting.store(false, Ordering::Release);
                }
                writer_shared.mark_stopped();
            });
        let writer = match handle {
            Ok(handle) => Some(handle),
            Err(_) => {
                shared.accepting.store(false, Ordering::Release);
                shared.stopped.store(true, Ordering::Release);
                None
            }
        };
        Self {
            shared,
            writer: Mutex::new(writer),
        }
    }

    fn enqueue_protocol(&self, arguments: fmt::Arguments<'_>) {
        if !self.shared.begin_producer() {
            return;
        }
        if let Some(line) = format_line(arguments) {
            self.shared.push_protocol(line);
        }
        self.shared.finish_producer();
    }

    fn enqueue_diagnostic(&self, arguments: fmt::Arguments<'_>) {
        if !self.shared.begin_producer() {
            return;
        }
        if let Some(line) = format_line(arguments) {
            self.shared.push_diagnostic(line);
        }
        self.shared.finish_producer();
    }

    #[cfg(test)]
    fn flush(&self, timeout: Duration) -> bool {
        if self.shared.stopped.load(Ordering::Acquire) {
            return true;
        }
        let generation = self.shared.flush_requested.fetch_add(1, Ordering::AcqRel) + 1;
        self.shared.notify_writer();
        self.wait_until(timeout, || {
            self.shared.flush_completed.load(Ordering::Acquire) >= generation
                || self.shared.stopped.load(Ordering::Acquire)
        })
    }

    fn shutdown(&self, timeout: Duration) -> bool {
        self.shared.accepting.store(false, Ordering::Release);
        self.shared.shutdown.store(true, Ordering::Release);
        self.shared.notify_writer();
        let stopped = self.wait_until(timeout, || self.shared.stopped.load(Ordering::Acquire));
        if stopped {
            let mut writer = self
                .writer
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if let Some(handle) = writer.take() {
                let _ = handle.join();
            }
        }
        stopped
    }

    fn wait_until<F>(&self, timeout: Duration, predicate: F) -> bool
    where
        F: Fn() -> bool,
    {
        let deadline = Instant::now() + timeout;
        let mut guard = self
            .shared
            .completion_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        loop {
            if predicate() {
                return true;
            }
            let now = Instant::now();
            if now >= deadline {
                return false;
            }
            let remaining = deadline.saturating_duration_since(now);
            let waited = self.shared.completion.wait_timeout(guard, remaining);
            let (next_guard, timeout_result) =
                waited.unwrap_or_else(|poisoned| poisoned.into_inner());
            guard = next_guard;
            if timeout_result.timed_out() && !predicate() {
                return false;
            }
        }
    }

    #[cfg(test)]
    fn diagnostic_depth(&self) -> usize {
        self.shared.diagnostic_outstanding.load(Ordering::Acquire)
    }

    #[cfg(test)]
    fn dropped_diagnostics(&self) -> u64 {
        self.shared.dropped_diagnostic.load(Ordering::Acquire)
    }
}

impl Drop for Logger {
    fn drop(&mut self) {
        self.shared.accepting.store(false, Ordering::Release);
        self.shared.shutdown.store(true, Ordering::Release);
        self.shared.notify_writer();
    }
}

fn format_line(arguments: fmt::Arguments<'_>) -> Option<LogLine> {
    catch_unwind(AssertUnwindSafe(|| {
        let mut line = BoundedLine::new();
        fmt::write(&mut line, arguments).ok()?;
        Some(LogLine {
            value: line.value.into_boxed_str(),
        })
    }))
    .ok()
    .flatten()
}

fn writer_loop<Out: Write, Err: Write>(
    shared: Arc<Shared>,
    mut stdout: Sink<Out>,
    mut stderr: Sink<Err>,
) {
    let mut protocol_burst = 0usize;
    let mut pending_diagnostic = None;
    loop {
        if protocol_burst < PROTOCOL_BURST
            && let Some(line) = shared.protocol.pop()
        {
            stdout.write_line(&line.value, 1);
            shared.protocol_outstanding.fetch_sub(1, Ordering::AcqRel);
            protocol_burst += 1;
            continue;
        }

        let diagnostic = pending_diagnostic
            .take()
            .or_else(|| shared.diagnostic.pop());
        if let Some(line) = diagnostic {
            let mut repetitions = 1usize;
            while repetitions < COALESCE_BATCH {
                let Some(next) = shared.diagnostic.pop() else {
                    break;
                };
                if next.value == line.value {
                    repetitions += 1;
                    shared.diagnostic_outstanding.fetch_sub(1, Ordering::AcqRel);
                } else {
                    pending_diagnostic = Some(next);
                    break;
                }
            }
            stderr.write_line(&line.value, repetitions);
            shared.diagnostic_outstanding.fetch_sub(1, Ordering::AcqRel);
            protocol_burst = 0;
            continue;
        }

        if let Some(line) = shared.protocol.pop() {
            stdout.write_line(&line.value, 1);
            shared.protocol_outstanding.fetch_sub(1, Ordering::AcqRel);
            protocol_burst = 1;
            continue;
        }

        if shared.idle() {
            let requested = shared.flush_requested.load(Ordering::Acquire);
            if requested > shared.flush_completed.load(Ordering::Acquire) {
                stdout.flush();
                stderr.flush();
                shared.complete_flush(requested);
            }
            if shared.shutdown.load(Ordering::Acquire) {
                stdout.flush();
                stderr.flush();
                return;
            }
        }

        let observed = shared.wake_generation.load(Ordering::Acquire);
        let guard = shared
            .wake_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if observed == shared.wake_generation.load(Ordering::Acquire)
            && !shared.shutdown.load(Ordering::Acquire)
            && shared.protocol.is_empty()
            && shared.diagnostic.is_empty()
        {
            let _ = shared
                .wake
                .wait_timeout(guard, Duration::from_millis(100))
                .unwrap_or_else(|poisoned| poisoned.into_inner());
        }
    }
}

static LOGGER: LazyLock<Logger> = LazyLock::new(|| {
    Logger::with_writers(
        io::stdout(),
        io::stderr(),
        PROTOCOL_CAPACITY,
        DIAGNOSTIC_CAPACITY,
    )
});

pub fn stdout(arguments: fmt::Arguments<'_>) {
    LOGGER.enqueue_protocol(arguments);
}

pub fn stderr(arguments: fmt::Arguments<'_>) {
    LOGGER.enqueue_diagnostic(arguments);
}

pub fn shutdown(timeout: Duration) -> bool {
    LOGGER.shutdown(timeout)
}

#[macro_export]
macro_rules! log_output {
    ($($argument:tt)*) => {{
        $crate::logging::stdout(format_args!($($argument)*));
    }};
}

#[macro_export]
macro_rules! log_error {
    ($($argument:tt)*) => {{
        $crate::logging::stderr(format_args!($($argument)*));
    }};
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        atomic::AtomicUsize,
        mpsc::{self, Sender},
    };

    #[derive(Clone)]
    struct Records {
        lines: Arc<Mutex<Vec<(u8, String)>>>,
        flushes: Arc<AtomicUsize>,
    }

    impl Records {
        fn new() -> Self {
            Self {
                lines: Arc::new(Mutex::new(Vec::new())),
                flushes: Arc::new(AtomicUsize::new(0)),
            }
        }

        fn snapshot(&self) -> Vec<(u8, String)> {
            self.lines
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .clone()
        }
    }

    struct RecordingWriter {
        target: u8,
        records: Records,
        pending: Vec<u8>,
    }

    impl RecordingWriter {
        fn new(target: u8, records: Records) -> Self {
            Self {
                target,
                records,
                pending: Vec::new(),
            }
        }
    }

    impl Write for RecordingWriter {
        fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
            self.pending.extend_from_slice(buffer);
            while let Some(position) = self.pending.iter().position(|byte| *byte == b'\n') {
                let line = String::from_utf8_lossy(&self.pending[..position]).into_owned();
                self.records
                    .lines
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner())
                    .push((self.target, line));
                self.pending.drain(..=position);
            }
            Ok(buffer.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            self.records.flushes.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
    }

    struct BrokenWriter {
        writes: Arc<AtomicUsize>,
    }

    impl Write for BrokenWriter {
        fn write(&mut self, _buffer: &[u8]) -> io::Result<usize> {
            self.writes.fetch_add(1, Ordering::Relaxed);
            Err(io::Error::new(io::ErrorKind::BrokenPipe, "closed"))
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    struct GateWriter<W> {
        inner: W,
        gate: Arc<(Mutex<bool>, Condvar)>,
        started: Option<Sender<()>>,
    }

    impl<W: Write> Write for GateWriter<W> {
        fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
            if let Some(started) = self.started.take() {
                let _ = started.send(());
            }
            let (lock, wake) = &*self.gate;
            let mut open = lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            while !*open {
                open = wake
                    .wait(open)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            drop(open);
            self.inner.write(buffer)
        }

        fn flush(&mut self) -> io::Result<()> {
            self.inner.flush()
        }
    }

    fn open_gate(gate: &Arc<(Mutex<bool>, Condvar)>) {
        let (lock, wake) = &**gate;
        *lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = true;
        wake.notify_all();
    }

    #[test]
    fn saturated_open_pipe_never_blocks_producers_or_grows_unbounded() {
        let records = Records::new();
        let gate = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let logger = Arc::new(Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            GateWriter {
                inner: RecordingWriter::new(1, records),
                gate: gate.clone(),
                started: Some(started_tx),
            },
            8,
            32,
        ));
        logger.enqueue_diagnostic(format_args!("blocker"));
        started_rx.recv_timeout(Duration::from_secs(2)).unwrap();

        let (done_tx, done_rx) = mpsc::channel();
        for worker in 0..8 {
            let logger = logger.clone();
            let done_tx = done_tx.clone();
            thread::spawn(move || {
                for sequence in 0..5_000 {
                    logger.enqueue_diagnostic(format_args!("worker={worker} sequence={sequence}"));
                }
                let _ = done_tx.send(());
            });
        }
        drop(done_tx);
        for _ in 0..8 {
            done_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        }
        assert!(logger.diagnostic_depth() <= 33);
        assert!(logger.dropped_diagnostics() > 0);
        open_gate(&gate);
        assert!(logger.shutdown(Duration::from_secs(2)));
    }

    #[test]
    fn protocol_queue_has_reserved_capacity_and_writer_priority() {
        let records = Records::new();
        let gate = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let logger = Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            GateWriter {
                inner: RecordingWriter::new(1, records.clone()),
                gate: gate.clone(),
                started: Some(started_tx),
            },
            4,
            4,
        );
        logger.enqueue_diagnostic(format_args!("blocker"));
        started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        for sequence in 0..100 {
            logger.enqueue_diagnostic(format_args!("diagnostic-{sequence}"));
        }
        logger.enqueue_protocol(format_args!("event-1"));
        logger.enqueue_protocol(format_args!("event-2"));
        open_gate(&gate);
        assert!(logger.shutdown(Duration::from_secs(2)));

        let lines = records.snapshot();
        let event_1 = lines
            .iter()
            .position(|line| line == &(0, "event-1".into()))
            .unwrap();
        let event_2 = lines
            .iter()
            .position(|line| line == &(0, "event-2".into()))
            .unwrap();
        let next_diagnostic = lines
            .iter()
            .position(|(target, line)| *target == 1 && line.starts_with("diagnostic-"))
            .unwrap();
        assert!(event_1 < event_2);
        assert!(event_2 < next_diagnostic);
    }

    #[test]
    fn broken_stdout_is_disabled_without_affecting_stderr() {
        let writes = Arc::new(AtomicUsize::new(0));
        let records = Records::new();
        let logger = Logger::with_writers(
            BrokenWriter {
                writes: writes.clone(),
            },
            RecordingWriter::new(1, records.clone()),
            8,
            8,
        );
        logger.enqueue_protocol(format_args!("event-1"));
        logger.enqueue_protocol(format_args!("event-2"));
        logger.enqueue_diagnostic(format_args!("diagnostic"));
        assert!(logger.shutdown(Duration::from_secs(2)));
        assert_eq!(writes.load(Ordering::Relaxed), 1);
        assert!(records.snapshot().contains(&(1, "diagnostic".to_string())));
    }

    #[test]
    fn repeated_diagnostics_are_coalesced() {
        let records = Records::new();
        let gate = Arc::new((Mutex::new(false), Condvar::new()));
        let (started_tx, started_rx) = mpsc::channel();
        let logger = Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            GateWriter {
                inner: RecordingWriter::new(1, records.clone()),
                gate: gate.clone(),
                started: Some(started_tx),
            },
            8,
            128,
        );
        logger.enqueue_diagnostic(format_args!("blocker"));
        started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        for _ in 0..100 {
            logger.enqueue_diagnostic(format_args!("repeated"));
        }
        open_gate(&gate);
        assert!(logger.shutdown(Duration::from_secs(2)));
        let repeated = records
            .snapshot()
            .into_iter()
            .filter(|(_, line)| line.starts_with("repeated"))
            .collect::<Vec<_>>();
        assert_eq!(repeated.len(), 2);
        assert_eq!(repeated[0].1, "repeated (x64)");
        assert_eq!(repeated[1].1, "repeated (x36)");
    }

    #[test]
    fn flush_waits_for_written_data_and_flushes_both_sinks() {
        let records = Records::new();
        let logger = Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            RecordingWriter::new(1, records.clone()),
            8,
            8,
        );
        logger.enqueue_protocol(format_args!("event"));
        logger.enqueue_diagnostic(format_args!("diagnostic"));
        assert!(logger.flush(Duration::from_secs(2)));
        assert_eq!(records.snapshot().len(), 2);
        assert!(records.flushes.load(Ordering::Acquire) >= 2);
        assert!(logger.shutdown(Duration::from_secs(2)));
    }

    #[test]
    fn concurrent_producers_keep_per_producer_order_without_deadlock() {
        let records = Records::new();
        let logger = Arc::new(Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            RecordingWriter::new(1, records.clone()),
            8,
            10_000,
        ));
        let (done_tx, done_rx) = mpsc::channel();
        for worker in 0..8 {
            let logger = logger.clone();
            let done_tx = done_tx.clone();
            thread::spawn(move || {
                for sequence in 0..1_000 {
                    logger.enqueue_diagnostic(format_args!("worker={worker} sequence={sequence}"));
                }
                let _ = done_tx.send(());
            });
        }
        drop(done_tx);
        for _ in 0..8 {
            done_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        }
        assert!(logger.shutdown(Duration::from_secs(5)));
        assert_eq!(logger.dropped_diagnostics(), 0);

        let mut expected = [0usize; 8];
        let lines = records.snapshot();
        assert_eq!(lines.len(), 8_000);
        for (_, line) in lines {
            let mut fields = line.split_ascii_whitespace();
            let worker = fields
                .next()
                .unwrap()
                .trim_start_matches("worker=")
                .parse::<usize>()
                .unwrap();
            let sequence = fields
                .next()
                .unwrap()
                .trim_start_matches("sequence=")
                .parse::<usize>()
                .unwrap();
            assert_eq!(sequence, expected[worker]);
            expected[worker] += 1;
        }
        assert_eq!(expected, [1_000; 8]);
    }

    #[test]
    fn formatted_lines_have_a_strict_memory_bound() {
        let records = Records::new();
        let logger = Logger::with_writers(
            RecordingWriter::new(0, records.clone()),
            RecordingWriter::new(1, records.clone()),
            2,
            2,
        );
        let value = "я".repeat(MAX_LINE_BYTES);
        logger.enqueue_diagnostic(format_args!("{value}"));
        assert!(logger.shutdown(Duration::from_secs(2)));
        let lines = records.snapshot();
        assert_eq!(lines.len(), 1);
        assert!(lines[0].1.len() <= MAX_LINE_BYTES);
        assert!(std::str::from_utf8(lines[0].1.as_bytes()).is_ok());
    }
}
