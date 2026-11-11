//! The «Сессия» sheet, ported from the Kotlin `SessionInfo.kt`: what the world sees of the
//! connection (exit address, ASN, provider, place) and the Cloudflare speed test. Nothing here runs
//! in the background: both run only while the user has the sheet open.
//!
//! The platform supplies the wire: [`HttpFetcher`] for the lookup, [`SpeedTestTransport`] for the
//! streaming speed test. Both calls block (seconds): run them off the UI thread.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, LazyLock, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use regex_lite::Regex;
use serde_json::{Map, Value};

use crate::repository::{HttpFetcher, HttpRequest};

/// Answers with the caller's IPv4 address as plain text.
pub const IPV4_LOOKUP_URL: &str = "https://api.ipify.org";
/// Answers with the caller's IPv6 address; unreachable without IPv6.
pub const IPV6_LOOKUP_URL: &str = "https://api6.ipify.org";
/// `ipwho.is/<ip>` (or bare, for the address the request comes from): owner and place, as JSON.
pub const GEO_LOOKUP_URL: &str = "https://ipwho.is/";
/// How long the IPv6 probe may take before the sheet shows the result without it.
pub const IPV6_PROBE_TIMEOUT: Duration = Duration::from_millis(5_000);

static IPV4: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d{1,3}(\.\d{1,3}){3}$").unwrap());

/// What the world sees of this connection: the exit address and who owns it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SessionInfo {
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub asn: Option<String>,
    pub provider: Option<String>,
    /// `Finland (FI)`, or whichever of the two the lookup knew.
    pub country: Option<String>,
    pub city: Option<String>,
    pub region: Option<String>,
    pub latitude: Option<String>,
    pub longitude: Option<String>,
}

/// One look at the exit through `proxy` (the live tunnel's `socks5://…`; `None` = the default
/// route). The IPv6 probe runs alongside and is dropped after [`IPV6_PROBE_TIMEOUT`]. The error is
/// written for the user.
pub fn fetch_session_info(
    fetcher: Arc<dyn HttpFetcher>,
    proxy: Option<&str>,
) -> Result<SessionInfo, String> {
    let started = Instant::now();
    let (sender, receiver) = mpsc::channel();
    let probe = Arc::clone(&fetcher);
    let probe_proxy = proxy.map(str::to_string);
    // Detached on purpose: a probe stuck past the timeout must not hold the sheet.
    thread::spawn(move || {
        let _ = sender.send(plain_text(
            probe.as_ref(),
            IPV6_LOOKUP_URL,
            probe_proxy.as_deref(),
        ));
    });
    let ipv4 = plain_text(fetcher.as_ref(), IPV4_LOOKUP_URL, proxy).filter(|ip| IPV4.is_match(ip));
    // Without an IPv4 the lookup falls back to whatever address the request arrives from.
    let url = format!("{GEO_LOOKUP_URL}{}", ipv4.as_deref().unwrap_or(""));
    let body = fetcher
        .get(&request(&url, proxy))
        .ok_or_else(|| "ipwho.is did not answer".to_string())?
        .body;
    build_session_info(&body, ipv4, || {
        receiver
            .recv_timeout(IPV6_PROBE_TIMEOUT.saturating_sub(started.elapsed()))
            .ok()
            .flatten()
            .filter(|ip| ip.contains(':'))
    })
}

/// [`SessionInfo`] from an ipwho.is answer plus the separately probed addresses (an `ipv4` that is
/// not a dotted quad and an `ipv6` without a colon are ignored, like failed probes).
pub fn session_info_from_lookup(
    body: &str,
    ipv4: Option<&str>,
    ipv6: Option<&str>,
) -> Result<SessionInfo, String> {
    let ipv4 = ipv4.filter(|ip| IPV4.is_match(ip)).map(str::to_string);
    build_session_info(body, ipv4, || {
        ipv6.filter(|ip| ip.contains(':')).map(str::to_string)
    })
}

fn build_session_info(
    body: &str,
    ipv4: Option<String>,
    ipv6: impl FnOnce() -> Option<String>,
) -> Result<SessionInfo, String> {
    let root: Value = serde_json::from_str(body).map_err(|e| e.to_string())?;
    let root = root
        .as_object()
        .ok_or_else(|| "lookup failed".to_string())?;
    if let Some(success) = root.get("success") {
        let ok = match success {
            Value::Bool(ok) => *ok,
            Value::String(s) if s == "true" || s == "false" => s == "true",
            Value::Array(_) | Value::Object(_) => return Err("lookup failed".into()),
            other => return Err(format!("{other} does not represent a Boolean")),
        };
        if !ok {
            return Err(match root.get("message") {
                Some(Value::String(message)) => message.clone(),
                Some(Value::Number(n)) => n.to_string(),
                Some(Value::Bool(b)) => b.to_string(),
                _ => "lookup failed".into(),
            });
        }
    }
    let connection = root.get("connection").and_then(Value::as_object);
    let conn_text = |key: &str| match connection {
        Some(connection) => text(connection, key),
        None => Ok(None),
    };
    let ip = text(root, "ip")?;
    let country = text(root, "country")?;
    let code = text(root, "country_code")?;
    Ok(SessionInfo {
        ipv4: ipv4.or_else(|| ip.clone().filter(|ip| !ip.contains(':'))),
        ipv6: ipv6().or_else(|| ip.filter(|ip| ip.contains(':'))),
        asn: conn_text("asn")?,
        provider: match conn_text("isp")? {
            Some(isp) => Some(isp),
            None => conn_text("org")?,
        },
        country: match (country, code) {
            (Some(country), Some(code)) => Some(format!("{country} ({code})")),
            (country, code) => country.or(code),
        },
        city: text(root, "city")?,
        region: text(root, "region")?,
        latitude: text(root, "latitude")?,
        longitude: text(root, "longitude")?,
    })
}

/// A non-blank primitive as text (numbers re-printed in their shortest form); a nested object or
/// array is a malformed answer.
fn text(object: &Map<String, Value>, key: &str) -> Result<Option<String>, String> {
    let content = match object.get(key) {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s.clone()),
        Some(Value::Number(n)) => Some(n.to_string()),
        Some(Value::Bool(b)) => Some(b.to_string()),
        Some(Value::Array(_) | Value::Object(_)) => {
            return Err(format!("lookup failed: unexpected \"{key}\""))
        }
    };
    Ok(content.filter(|s| !s.trim().is_empty()))
}

fn request(url: &str, proxy: Option<&str>) -> HttpRequest {
    HttpRequest {
        url: url.to_string(),
        headers: vec![],
        proxy: proxy.map(str::to_string),
    }
}

/// The trimmed body, whatever the status; `None` without a response.
fn plain_text(fetcher: &dyn HttpFetcher, url: &str, proxy: Option<&str>) -> Option<String> {
    fetcher
        .get(&request(url, proxy))
        .map(|response| response.body.trim().to_string())
}

// ---------------- speed test ----------------

pub const SPEED_HOST: &str = "https://speed.cloudflare.com";
/// An empty download: its round trip is the ping.
pub const SPEED_PING_URL: &str = "https://speed.cloudflare.com/__down?bytes=0";
/// Cloudflare refuses a single download above ~25 MB (403), so each stream asks for 25 MB again
/// and again.
pub const SPEED_DOWNLOAD_URL: &str = "https://speed.cloudflare.com/__down?bytes=25000000";
pub const SPEED_UPLOAD_URL: &str = "https://speed.cloudflare.com/__up";
/// Bytes per upload request.
pub const SPEED_UPLOAD_CHUNK: usize = 1024 * 1024;
/// Round trips measured for the ping; the fastest one counts.
pub const SPEED_PING_SAMPLES: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum SpeedPhase {
    #[default]
    Idle,
    Ping,
    Download,
    Upload,
    Done,
    Failed,
}

/// What the sheet shows; every number appears as soon as it is known and stays.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct SpeedProgress {
    pub phase: SpeedPhase,
    pub ping_ms: Option<i32>,
    pub down_mbps: Option<f64>,
    pub up_mbps: Option<f64>,
}

/// How the speed test paces itself; [`SpeedTestTiming::default`] is what the apps use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SpeedTestTiming {
    /// Length of the download and of the upload phase.
    pub phase_ms: u64,
    /// The start of a phase (TCP slow start, TLS) that does not count towards the rate.
    pub warmup_ms: u64,
    /// Parallel connections: one TCP stream through a tunnel never fills the link.
    pub streams: usize,
    /// How often a moving rate is reported.
    pub report_interval_ms: u64,
}

impl Default for SpeedTestTiming {
    fn default() -> Self {
        Self {
            phase_ms: 8_000,
            warmup_ms: 1_500,
            streams: 4,
            report_interval_ms: 250,
        }
    }
}

/// Byte accounting for one phase: the rate in Mbit/s, excluding the warm-up once it is over.
#[derive(Debug, Clone, PartialEq)]
pub struct RateMeter {
    warmup_ms: i64,
    report_interval_ms: i64,
    total: i64,
    base: i64,
    base_ms: i64,
    last_report_ms: i64,
}

impl RateMeter {
    pub fn new(timing: &SpeedTestTiming) -> Self {
        Self {
            warmup_ms: timing.warmup_ms as i64,
            report_interval_ms: timing.report_interval_ms as i64,
            total: 0,
            base: -1,
            base_ms: 0,
            last_report_ms: 0,
        }
    }

    /// Counts `bytes` that arrived `elapsed_ms` into the phase; returns the rate when a report is
    /// due (after the warm-up, at most once per report interval).
    pub fn add(&mut self, bytes: i64, elapsed_ms: i64) -> Option<f64> {
        self.total += bytes;
        if self.base < 0 && elapsed_ms >= self.warmup_ms {
            self.base = self.total;
            self.base_ms = elapsed_ms;
        }
        if self.base >= 0 && elapsed_ms - self.last_report_ms >= self.report_interval_ms {
            self.last_report_ms = elapsed_ms;
            return Some(self.rate(elapsed_ms));
        }
        None
    }

    /// Mbit/s since the warm-up ended, or overall while it hasn't.
    pub fn rate(&self, elapsed_ms: i64) -> f64 {
        if self.base >= 0 && elapsed_ms > self.base_ms {
            (self.total - self.base) as f64 * 8.0 / 1000.0 / (elapsed_ms - self.base_ms) as f64
        } else {
            self.total as f64 * 8.0 / 1000.0 / elapsed_ms.max(1) as f64
        }
    }

    pub fn total_bytes(&self) -> i64 {
        self.total
    }
}

/// The streaming HTTP the speed test needs from the platform (through the tunnel's proxy).
pub trait SpeedTestTransport: Send + Sync {
    /// GETs `url` and reports each received chunk's size to `on_chunk` until the body ends or
    /// `on_chunk` returns `false`. A non-2xx status is an `Err`.
    fn download(
        &self,
        url: &str,
        proxy: Option<&str>,
        on_chunk: &mut dyn FnMut(usize) -> bool,
    ) -> Result<(), String>;

    /// POSTs `body` to `url` and returns the HTTP status.
    fn upload(&self, url: &str, proxy: Option<&str>, body: &[u8]) -> Result<u16, String>;
}

/// Ping, then download, then upload against Cloudflare through `proxy`; `on_update` fires as the
/// numbers move and ends with [`SpeedPhase::Done`] or [`SpeedPhase::Failed`]. Setting `cancel`
/// stops the test without a final update. Takes about 17 s.
pub fn run_speed_test(
    transport: &dyn SpeedTestTransport,
    proxy: Option<&str>,
    cancel: &AtomicBool,
    on_update: &(dyn Fn(SpeedProgress) + Sync),
) {
    run_speed_test_with(
        SpeedTestTiming::default(),
        transport,
        proxy,
        cancel,
        on_update,
    )
}

/// [`run_speed_test`] with custom pacing.
pub fn run_speed_test_with(
    timing: SpeedTestTiming,
    transport: &dyn SpeedTestTransport,
    proxy: Option<&str>,
    cancel: &AtomicBool,
    on_update: &(dyn Fn(SpeedProgress) + Sync),
) {
    let progress = Mutex::new(SpeedProgress {
        phase: SpeedPhase::Ping,
        ..SpeedProgress::default()
    });
    let push = |change: &dyn Fn(&mut SpeedProgress)| {
        let mut current = progress.lock().unwrap_or_else(|e| e.into_inner());
        change(&mut current);
        on_update(*current);
    };
    push(&|_| {});
    match speed_phases(&timing, transport, proxy, cancel, &push) {
        Ok(()) => push(&|p| p.phase = SpeedPhase::Done),
        Err(Stop::Cancelled) => {}
        Err(Stop::Failed) => push(&|p| p.phase = SpeedPhase::Failed),
    }
}

enum Stop {
    Cancelled,
    Failed,
}

type Push<'a> = dyn Fn(&dyn Fn(&mut SpeedProgress)) + Sync + 'a;

fn speed_phases(
    timing: &SpeedTestTiming,
    transport: &dyn SpeedTestTransport,
    proxy: Option<&str>,
    cancel: &AtomicBool,
    push: &Push<'_>,
) -> Result<(), Stop> {
    let mut ping_ms = i32::MAX;
    for _ in 0..SPEED_PING_SAMPLES {
        let started = Instant::now();
        transport
            .download(SPEED_PING_URL, proxy, &mut |_| true)
            .map_err(|_| Stop::Failed)?;
        ping_ms = ping_ms.min(started.elapsed().as_millis().min(i32::MAX as u128) as i32);
        if cancel.load(Ordering::SeqCst) {
            return Err(Stop::Cancelled);
        }
    }
    push(&|p| {
        p.phase = SpeedPhase::Download;
        p.ping_ms = Some(ping_ms);
    });

    let down = measure_rate(
        timing,
        cancel,
        &|rate| push(&|p| p.down_mbps = Some(rate)),
        &|add, over| {
            transport.download(SPEED_DOWNLOAD_URL, proxy, &mut |n| {
                add(n as i64);
                !over()
            })
        },
    )?;
    push(&|p| {
        p.phase = SpeedPhase::Upload;
        p.down_mbps = Some(down);
    });

    let chunk = vec![0u8; SPEED_UPLOAD_CHUNK];
    let up = measure_rate(
        timing,
        cancel,
        &|rate| push(&|p| p.up_mbps = Some(rate)),
        &|add, _over| {
            let status = transport.upload(SPEED_UPLOAD_URL, proxy, &chunk)?;
            if !(200..300).contains(&status) {
                return Err("speed test: upload rejected".into());
            }
            add(chunk.len() as i64);
            Ok(())
        },
    )?;
    push(&|p| p.up_mbps = Some(up));
    Ok(())
}

type Worker<'a> = dyn Fn(&dyn Fn(i64), &dyn Fn() -> bool) -> Result<(), String> + Sync + 'a;

/// Runs `timing.streams` copies of `worker` for one phase, each looping until the phase is over
/// and reporting bytes through `add`; `on_rate` gets the moving rate. The first failure stops
/// every stream.
fn measure_rate(
    timing: &SpeedTestTiming,
    cancel: &AtomicBool,
    on_rate: &(dyn Fn(f64) + Sync),
    worker: &Worker<'_>,
) -> Result<f64, Stop> {
    let started = Instant::now();
    let elapsed_ms = || started.elapsed().as_millis() as i64;
    let meter = Mutex::new(RateMeter::new(timing));
    let failed = AtomicBool::new(false);
    let over = || {
        elapsed_ms() >= timing.phase_ms as i64
            || cancel.load(Ordering::SeqCst)
            || failed.load(Ordering::SeqCst)
    };
    let add = |bytes: i64| {
        // Counting and reporting under one lock, so reports never go backwards.
        let mut meter = meter.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(rate) = meter.add(bytes, elapsed_ms()) {
            on_rate(rate);
        }
    };
    thread::scope(|scope| {
        for _ in 0..timing.streams.max(1) {
            scope.spawn(|| {
                while !over() {
                    if worker(&add, &over).is_err() {
                        failed.store(true, Ordering::SeqCst);
                    }
                }
            });
        }
    });
    if cancel.load(Ordering::SeqCst) {
        return Err(Stop::Cancelled);
    }
    if failed.load(Ordering::SeqCst) {
        return Err(Stop::Failed);
    }
    let rate = meter
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .rate(elapsed_ms());
    Ok(rate)
}
