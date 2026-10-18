// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

#![allow(linker_messages)]

pub mod auth;
pub mod captcha;
pub mod captcha_slider;
pub mod client_perf;
pub mod cpu_task;
pub mod dispatcher;
pub mod dns;
pub mod events;
#[path = "../shared/flow_frame.rs"]
pub mod flow_frame;
pub mod logging;
pub mod namegen;
pub mod obfs;
pub mod packet;
pub mod profiles;
pub mod protocol;
pub mod repair;
#[path = "../shared/selective_fec.rs"]
pub mod selective_fec;
pub mod session;
pub mod stats;
#[path = "../shared/striped_scheduler.rs"]
pub mod striped_scheduler;
pub mod stun_codec;
pub mod tun;
pub mod turn;
pub mod turn_core;
pub mod turn_endpoint;
pub mod turn_stream;
pub mod udp_batch;
pub mod vk_js_calls;
#[path = "../shared/wire_protocol.rs"]
pub mod wire_protocol;
pub mod worker;
pub mod wrap;

use anyhow::{Context, Result, bail};
use std::{
    ffi::{CStr, CString},
    net::SocketAddr,
    os::raw::c_char,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};
use tokio_util::sync::CancellationToken;

const MAX_VK_HASHES: usize = 6;

pub type TunConfigCallback = extern "C" fn(*const c_char);
pub type LogCallback = extern "C" fn(*const c_char);

#[derive(serde::Deserialize, Default)]
struct FfiOptions {
    #[serde(default)]
    peer: String,
    #[serde(default = "default_listen")]
    listen: String,
    #[serde(default)]
    vk_hashes: String,
    #[serde(default)]
    password: String,
    #[serde(default = "default_workers")]
    workers: usize,
    #[serde(default = "default_device_id")]
    device_id: String,
    #[serde(default = "default_obfs")]
    obfs: String,
    #[serde(default)]
    turn_tcp: bool,
    #[serde(default = "default_fingerprint")]
    fingerprint: String,
    #[serde(default)]
    client_ids: String,
    #[serde(default = "default_vk_auth_mode")]
    vk_auth_mode: String,
    #[serde(default = "default_captcha_mode")]
    captcha_mode: String,
    #[serde(default)]
    turn_host: String,
    #[serde(default)]
    turn_port: String,
    #[serde(default)]
    redistribute: bool,
    #[serde(default)]
    generation: u64,
    #[serde(default)]
    salt: String,
}

fn default_listen() -> String { "127.0.0.1:0".to_string() }
fn default_workers() -> usize { 18 }
fn default_device_id() -> String { "ios".to_string() }
fn default_obfs() -> String { "audio".to_string() }
fn default_fingerprint() -> String { "safari".to_string() }
fn default_vk_auth_mode() -> String { "vkcalls".to_string() }
fn default_captcha_mode() -> String { "auto".to_string() }

struct CsqttState {
    cancel: CancellationToken,
    thread: Option<thread::JoinHandle<()>>,
    running: Arc<AtomicBool>,
}

static STATE: Mutex<Option<CsqttState>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "C" fn csqtt_ffi_version() -> *const c_char {
    static VERSION: &[u8] = b"2.1.9\0";
    VERSION.as_ptr().cast()
}

#[unsafe(no_mangle)]
pub extern "C" fn csqtt_ffi_start(
    options_json: *const c_char,
    on_tun_config: Option<TunConfigCallback>,
    on_log: Option<LogCallback>,
) -> *mut c_char {
    if options_json.is_null() {
        return to_c_string("csqtt_ffi_start: options_json is null");
    }

    let json_str = match unsafe { CStr::from_ptr(options_json) }.to_str() {
        Ok(s) => s,
        Err(e) => return to_c_string(&format!("invalid utf-8 options json: {e}")),
    };

    let opts: FfiOptions = match serde_json::from_str(json_str) {
        Ok(o) => o,
        Err(e) => return to_c_string(&format!("failed to parse options json: {e}")),
    };

    let mut state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref state) = *state_guard {
        if state.running.load(Ordering::Acquire) {
            return to_c_string("csqtt client is already running");
        }
    }

    let cancel = CancellationToken::new();
    let cancel_worker = cancel.clone();
    let running = Arc::new(AtomicBool::new(true));
    let running_worker = Arc::clone(&running);

    let thread_handle = match thread::Builder::new()
        .name("csqtt-inprocess".to_string())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(e) => {
                    eprintln!("[CSQTT-FFI] Failed to build Tokio runtime: {e}");
                    running_worker.store(false, Ordering::Release);
                    return;
                }
            };

            let res = runtime.block_on(run_inprocess(
                opts,
                cancel_worker,
                running_worker.clone(),
                on_tun_config,
                on_log,
            ));

            if let Err(e) = res {
                eprintln!("[CSQTT-FFI] In-process client error: {e:#}");
            }
            running_worker.store(false, Ordering::Release);
        }) {
        Ok(h) => h,
        Err(e) => return to_c_string(&format!("failed to spawn worker thread: {e}")),
    };

    *state_guard = Some(CsqttState {
        cancel,
        thread: Some(thread_handle),
        running,
    });

    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn csqtt_ffi_stop() {
    let mut state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(mut state) = state_guard.take() {
        state.cancel.cancel();
        state.running.store(false, Ordering::Release);
        if let Some(thread) = state.thread.take() {
            let _ = thread.join();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn csqtt_ffi_is_running() -> bool {
    let state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref state) = *state_guard {
        state.running.load(Ordering::Acquire)
    } else {
        false
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn csqtt_ffi_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

fn to_c_string(msg: &str) -> *mut c_char {
    CString::new(msg)
        .unwrap_or_else(|_| CString::new("internal string conversion error").unwrap())
        .into_raw()
}

async fn run_inprocess(
    opts: FfiOptions,
    cancel: CancellationToken,
    running: Arc<AtomicBool>,
    on_tun_config: Option<TunConfigCallback>,
    on_log: Option<LogCallback>,
) -> Result<()> {
    if opts.peer.is_empty() || opts.vk_hashes.is_empty() {
        bail!("csqtt requires peer and vk_hashes");
    }
    if opts.password.is_empty() {
        bail!("csqtt requires password");
    }

    let peer = resolve_peer(&opts.peer).await?;
    let mode = obfs::ObfsMode::parse(&opts.obfs)?;
    let turn_transport = if opts.turn_tcp {
        turn_endpoint::TurnTransportMode::TcpTls
    } else {
        turn_endpoint::TurnTransportMode::Udp
    };
    let wrap_key = wrap::derive_wrap_key(&opts.password)?;
    let session_profile = profiles::random_profile(&opts.fingerprint);

    let hashes: Vec<_> = worker::parse_hashes(&opts.vk_hashes)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("No valid VK hashes found in vk_hashes");
    }

    // Clamp workers on iOS Network Extension to 9..18 to guarantee Jetsam headroom
    let workers = opts.workers.clamp(worker::WORKERS_PER_GROUP, 18);
    let groups = workers / worker::WORKERS_PER_GROUP;

    let captcha = captcha::CaptchaSolver::new(&opts.captcha_mode, cancel.clone());
    let events = events::Events::new(false);

    let client_ids: Vec<_> = opts
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
        .collect();

    let auth = Arc::new(auth::VkAuth::new(
        &opts.vk_auth_mode,
        session_profile,
        &client_ids,
        captcha.clone(),
        None,
    ));

    let pool = packet::PacketPool::new(packet::packet_pool_size(workers));
    let stats = Arc::new(stats::Stats::default());

    let (dispatcher, local_port) = dispatcher::Dispatcher::start(
        &opts.listen,
        None,
        pool.clone(),
        stats.clone(),
        cancel.clone(),
    )
    .await?;

    let local_port: Arc<str> = Arc::from(local_port);
    let params = Arc::new(worker::RuntimeParams {
        peer,
        turn_host: (!opts.turn_host.is_empty()).then(|| Arc::from(opts.turn_host.as_str())),
        turn_port: (!opts.turn_port.is_empty()).then(|| Arc::from(opts.turn_port.as_str())),
        turn_transport,
        hashes: hashes.into(),
        wrap_key,
        mode,
        generation: opts.generation,
        salt: Arc::from(opts.salt.as_str()),
        local_port: local_port.clone(),
        device_id: Arc::from(opts.device_id.as_str()),
        password: Arc::from(opts.password.as_str()),
        workers,
    });

    let paused = Arc::new(worker::PauseGate::new());
    let repair = repair::RepairState::new(workers);

    let (config_tx, mut config_rx) = tokio::sync::mpsc::channel::<String>(32);
    let config_task = tokio::spawn(async move {
        let mut last_config = None;
        while let Some(config) = config_rx.recv().await {
            if last_config.as_deref() == Some(config.as_str()) {
                continue;
            }
            if config.starts_with("TUNCONF:") {
                dns::mark_tunnel_active();
                if let Some(cb) = on_tun_config {
                    if let Ok(c_str) = CString::new(config.clone()) {
                        cb(c_str.as_ptr());
                    }
                }
            }
            if let Some(cb) = on_log {
                if let Ok(c_str) = CString::new(format!("[csqtt] {config}")) {
                    cb(c_str.as_ptr());
                }
            }
            last_config = Some(config);
        }
    });

    let context = Arc::new(worker::GroupContext {
        params,
        auth,
        dispatcher: dispatcher.clone(),
        pool,
        stats,
        events: events.clone(),
        paused,
        config_tx,
        start_pacer: Arc::new(worker::WorkerStartPacer::new(worker::WORKER_START_INTERVAL)),
        credential_pacer: Arc::new(tokio::sync::Mutex::new(())),
        ready_credential_tx: None,
        config_sent: Arc::new(AtomicBool::new(false)),
        config_in_flight: Arc::new(AtomicBool::new(false)),
        server_stream_repair: Arc::new(AtomicBool::new(false)),
        repair,
        shutdown: Arc::new(session::ShutdownCoordinator::new()),
        cancel: cancel.clone(),
    });

    let groups_future = worker::run_groups(groups, context);
    tokio::pin!(groups_future);

    tokio::select! {
        _ = &mut groups_future => {},
        _ = cancel.cancelled() => {},
    }

    cancel.cancel();
    dispatcher.shutdown().await;
    config_task.abort();
    let _ = config_task.await;
    running.store(false, Ordering::Release);

    Ok(())
}

async fn resolve_peer(peer: &str) -> Result<SocketAddr> {
    let mut last_error = None;
    for _ in 0..15 {
        match dns::resolve_socket(peer).await {
            Ok(address) => return Ok(address),
            Err(error) => last_error = Some(error),
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("empty DNS resolution for peer")))
        .context("resolving peer address")
}
