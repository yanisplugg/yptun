//! Batched logs: lines collect on the Rust side and cross the FFI in batches, not one by one.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

/// Implemented by the app (Kotlin/Swift) to receive log batches.
#[uniffi::export(with_foreign)]
pub trait LogSink: Send + Sync {
    fn on_logs(&self, lines: Vec<String>);
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LogBatch {
    pub lines: Vec<String>,
    /// Oldest lines dropped because the buffer was full, since the previous drain.
    pub dropped: u64,
}

struct Inner {
    lines: VecDeque<String>,
    capacity: usize,
    dropped: u64,
}

impl Inner {
    fn push(&mut self, line: String) {
        if self.lines.len() >= self.capacity {
            self.lines.pop_front();
            self.dropped += 1;
        }
        self.lines.push_back(line);
    }

    fn take(&mut self, max: usize) -> LogBatch {
        let n = if max == 0 { self.lines.len() } else { max.min(self.lines.len()) };
        let lines: Vec<String> = self.lines.drain(..n).collect();
        LogBatch { lines, dropped: std::mem::take(&mut self.dropped) }
    }
}

/// Bounded ring of log lines (oldest dropped first).
#[derive(uniffi::Object)]
pub struct LogBuffer {
    inner: Mutex<Inner>,
}

impl LogBuffer {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Rust-side push without an owned String.
    pub fn push_str(&self, line: &str) {
        self.lock().push(line.to_string());
    }
}

#[uniffi::export]
impl LogBuffer {
    #[uniffi::constructor]
    pub fn new(capacity: u32) -> Arc<Self> {
        let capacity = (capacity as usize).max(1);
        let inner = Inner { lines: VecDeque::with_capacity(capacity.min(4096)), capacity, dropped: 0 };
        Arc::new(Self { inner: Mutex::new(inner) })
    }

    pub fn push(&self, line: String) {
        self.lock().push(line);
    }

    pub fn push_all(&self, lines: Vec<String>) {
        let mut g = self.lock();
        for line in lines {
            g.push(line);
        }
    }

    pub fn len(&self) -> u32 {
        self.lock().lines.len() as u32
    }

    pub fn is_empty(&self) -> bool {
        self.lock().lines.is_empty()
    }

    /// Takes up to max oldest lines (0 = all).
    pub fn drain(&self, max: u32) -> LogBatch {
        self.lock().take(max as usize)
    }

    /// Drains up to max lines (0 = all) into the sink in one call; returns how many were sent.
    /// The lock is released before the sink runs.
    pub fn flush_to(&self, sink: Arc<dyn LogSink>, max: u32) -> u32 {
        let batch = self.lock().take(max as usize);
        let n = batch.lines.len() as u32;
        if n > 0 {
            sink.on_logs(batch.lines);
        }
        n
    }
}
