#![allow(linker_messages)]

mod auth;
mod captcha;
mod captcha_slider;
mod cpu_task;
pub mod direct_relay;
mod dispatcher;
mod dns;
#[path = "../shared/dtls.rs"]
pub mod dtls;
mod events;
mod logging;
mod namegen;
mod obfs;
mod packet;
mod profiles;
mod protocol;
mod repair;
mod session;
mod sni;
#[path = "../shared/spsc_ring.rs"]
mod spsc_ring;
mod srtp_mode;
mod stats;
mod stun_codec;
mod tun;
mod turn;
mod turn_core;
mod turn_endpoint;
mod turn_ip_pool;
mod turn_stream;
mod vk_js_calls;
#[path = "../shared/wire_protocol.rs"]
mod wire_protocol;
mod worker;
mod wrap;

use anyhow::{Context, Result, bail};
use auth::{VkAuth, VkHashCheck};
use base64::{Engine, engine::general_purpose::STANDARD};
use captcha::CaptchaSolver;
use clap::Parser;
use dispatcher::{Dispatcher, Termination};
use events::Events;
use obfs::ObfsMode;
use packet::{PacketPool, packet_pool_size};
use repair::RepairState;
use session::ShutdownCoordinator;
use srtp_mode::SrtpMode;
use stats::Stats;
use std::{
    collections::HashSet,
    io::BufRead,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio_util::sync::CancellationToken;
use turn_endpoint::TurnTransportMode;
use turn_ip_pool::{TurnIpPool, parse_custom_turn_ips};
use worker::{
    GROUPS_PER_CREDENTIAL, GroupContext, PauseGate, RuntimeParams, WORKER_START_INTERVAL,
    WORKERS_PER_GROUP, WorkerStartPacer, parse_hashes, run_groups,
};
use wrap::derive_wrap_key;

const EXIT_NORMAL: i32 = 0;
const EXIT_FATAL: i32 = 1;
const EXIT_TUN_LOST: i32 = 3;
const MAX_VK_HASHES: usize = 6;
pub const MAX_WORKERS: usize = 100;
const STREAMS_PER_RUNTIME_WORKER: usize = 8;
const STDIN_QUEUE_CAPACITY: usize = 64;
const MAX_STDIN_LINE_BYTES: usize = 64 * 1024;
type StdinLine = std::io::Result<String>;

#[derive(Parser)]
#[command(disable_help_flag = true)]
struct Arguments {
    #[arg(long, default_value = "")]
    turn: String,
    #[arg(long = "custom-turn-ips", default_value = "")]
    custom_turn_ips: String,
    #[arg(long, default_value = "")]
    port: String,
    #[arg(long, default_value = "127.0.0.1:9000")]
    listen: String,
    #[arg(long, default_value = "", allow_hyphen_values = true)]
    vk: String,
    #[arg(long, default_value = "manual")]
    vk_hash_mode: String,
    #[arg(long, default_value = "")]
    peer: String,
    #[arg(short = 'n', long, default_value_t = 18)]
    workers: usize,
    #[arg(long, default_value_t = 0)]
    runtime_threads: usize,
    #[arg(long, default_value = "vkcalls")]
    vk_auth_mode: String,
    #[arg(long, default_value = "auto")]
    captcha_mode: String,
    #[arg(long, default_value = "chrome")]
    fingerprint: String,
    #[arg(long, default_value = "")]
    client_ids: String,
    #[arg(long, default_value = "unified")]
    obfs: String,
    #[arg(long, default_value = "udp")]
    turn_transport: String,
    #[arg(long, default_value = "auto")]
    srtp_mode: String,
    #[arg(long = "gen", default_value_t = 0)]
    generation: u64,
    #[arg(long, default_value = "")]
    salt: String,
    #[arg(long, default_value = "")]
    tun_uds: String,
    #[arg(long, default_value_t = false)]
    validate_vk_hashes: bool,
    #[arg(long = "domain-routing", default_value_t = false)]
    domain_routing: bool,
    #[arg(long = "exclude-ru-zones", default_value_t = false)]
    exclude_ru_zones: bool,
    #[arg(long = "template-domains", default_value_t = false)]
    template_domains: bool,
    #[arg(long = "domain-whitelist", default_value_t = false)]
    domain_whitelist: bool,
    #[arg(long = "direct-domains", default_value = "")]
    direct_domains: String,
    #[arg(long = "domains-file")]
    domains_file: Vec<String>,
    #[arg(long = "ip-routing", default_value_t = false)]
    ip_routing: bool,
    #[arg(long = "ip-whitelist", default_value_t = false)]
    ip_whitelist: bool,
    #[arg(long = "direct-ips", default_value = "")]
    direct_ips: String,
    #[arg(long = "exact-workers", default_value_t = false)]
    exact_workers: bool,
}

fn main() {
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("[PANIC] Rust client task failed: {info}\n");
        #[cfg(unix)]
        unsafe {
            let _ = libc::write(libc::STDERR_FILENO, msg.as_ptr().cast(), msg.len());
        }
        #[cfg(not(unix))]
        {
            use std::io::Write;
            let mut stderr = std::io::stderr().lock();
            let _ = stderr.write_all(msg.as_bytes());
            let _ = stderr.flush();
        }
    }));
    let arguments = Arguments::parse_from(normalized_arguments());
    let runtime = match build_runtime(runtime_worker_threads(
        arguments.workers,
        arguments.runtime_threads,
    )) {
        Ok(runtime) => runtime,
        Err(error) => {
            eprintln!("[ФАТАЛ] {error:#}");
            std::process::exit(EXIT_FATAL);
        }
    };
    let exit_code = runtime.block_on(async {
        match tokio::spawn(run(arguments)).await {
            Ok(Ok(code)) => code,
            Ok(Err(error)) => {
                crate::log_error!("[ФАТАЛ] {error:#}");
                EXIT_FATAL
            }
            Err(error) => {
                crate::log_error!("[ФАТАЛ] паника верхнего уровня изолирована: {error}");
                EXIT_FATAL
            }
        }
    });
    runtime.shutdown_timeout(Duration::from_secs(2));
    let _ = logging::shutdown(Duration::from_secs(1));
    std::process::exit(exit_code);
}

fn build_runtime(default_worker_threads: usize) -> Result<tokio::runtime::Runtime> {
    let mut builder = tokio::runtime::Builder::new_multi_thread();
    builder.worker_threads(default_worker_threads);
    builder
        .enable_all()
        .build()
        .context("создание Tokio runtime")
}

fn runtime_worker_threads(requested_workers: usize, configured_threads: usize) -> usize {
    let available = std::thread::available_parallelism()
        .map(|parallelism| parallelism.get())
        .unwrap_or(1);
    runtime_worker_threads_with_limit(requested_workers, configured_threads, available)
}

fn runtime_worker_threads_with_limit(
    requested_workers: usize,
    configured_threads: usize,
    available_threads: usize,
) -> usize {
    let available = available_threads.max(1);
    if configured_threads != 0 {
        return configured_threads.clamp(1, available);
    }
    normalize_worker_count(requested_workers)
        .div_ceil(STREAMS_PER_RUNTIME_WORKER)
        .clamp(1, available)
}

async fn run(arguments: Arguments) -> Result<i32> {
    if arguments.validate_vk_hashes {
        run_vk_hash_validation(&arguments).await?;
        return Ok(EXIT_NORMAL);
    }
    let js_hash_mode = arguments.vk_hash_mode == "auto_js";
    let mut vk_auth_mode = arguments.vk_auth_mode.clone();
    let js_auth_mode = if vk_auth_mode == "auto_js" && !js_hash_mode {
        crate::log_error!(
            "[КЛИЕНТ] Предупреждение: режим Auto JS неприменим без хешей Auto JS, переключаем на vkcalls"
        );
        vk_auth_mode = "vkcalls".to_owned();
        false
    } else {
        vk_auth_mode == "auto_js"
    };
    if arguments.peer.is_empty() || (!js_hash_mode && arguments.vk.is_empty()) {
        bail!("[КЛИЕНТ] Нужны -peer и хеши VK");
    }
    let password = std::env::var("CSQTT_PASSWORD").unwrap_or_default();
    if password.is_empty() {
        bail!("[КЛИЕНТ] Нужен пароль в переменной CSQTT_PASSWORD");
    }
    let mut stdin = spawn_stdin_reader();
    let peer = resolve_peer(&arguments.peer).await?;
    let mode = ObfsMode::parse(&arguments.obfs)?;
    let turn_transport = TurnTransportMode::parse(&arguments.turn_transport)?;
    let srtp_mode = SrtpMode::parse(&arguments.srtp_mode)?;
    let wrap_key = derive_wrap_key(&password)?;
    let client_wrap_key = wrap_key;
    let session_profile = profiles::random_profile(&arguments.fingerprint);
    let mut js_calls = None;
    let mut js_credential_broker = None;
    let hash_source = if js_hash_mode {
        let bootstrap = read_vk_js_bootstrap(&mut stdin).await?;
        let started = vk_js_calls::start(bootstrap, js_auth_mode, &session_profile).await?;
        let hashes = started.hashes.join(",");
        js_calls = Some(started.active);
        js_credential_broker = Some(started.credential_broker);
        hashes
    } else {
        arguments.vk.clone()
    };
    let hashes: Vec<_> = parse_hashes(&hash_source)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK");
    }
    let workers = if arguments.exact_workers {
        arguments.workers.clamp(3, MAX_WORKERS)
    } else {
        normalize_worker_count(arguments.workers)
    };
    let groups = workers.div_ceil(WORKERS_PER_GROUP);
    let cancel = CancellationToken::new();
    let captcha = CaptchaSolver::new(&arguments.captcha_mode, cancel.clone());
    let events = Events::from_env();
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    let auth = Arc::new(VkAuth::new(
        &vk_auth_mode,
        session_profile,
        &client_ids,
        captcha.clone(),
        js_credential_broker,
    ));
    let stats = Arc::new(Stats::default());
    let paused = Arc::new(PauseGate::new());
    let finish_js_calls = Arc::new(AtomicBool::new(false));
    let parent_task = start_parent_monitor(cancel.clone());
    events.process(std::process::id());
    let pool = PacketPool::new(packet_pool_size(MAX_WORKERS));
    let tun_uds = (!arguments.tun_uds.is_empty()).then_some(arguments.tun_uds.clone());
    let mut direct_domains = crate::sni::parse_direct_domains(&arguments.direct_domains);
    for file_path in &arguments.domains_file {
        if let Ok(content) = std::fs::read_to_string(file_path) {
            let file_domains = crate::sni::parse_direct_domains(&content);
            for domain in file_domains {
                if !direct_domains.contains(&domain) {
                    direct_domains.push(domain);
                }
            }
        }
    }
    direct_domains.truncate(1024);
    let ip_rules = crate::sni::parse_ip_rules(&arguments.direct_ips);
    let domain_routing = dispatcher::DomainRoutingConfig {
        enabled: arguments.domain_routing,
        exclude_ru_zones: arguments.exclude_ru_zones,
        template_domains: arguments.template_domains,
        direct_domains: Arc::new(direct_domains),
        is_whitelist: arguments.domain_whitelist,
        ip_routing_enabled: arguments.ip_routing,
        ip_is_whitelist: arguments.ip_whitelist,
        ip_rules: Arc::new(ip_rules),
    };
    let dispatcher_result = Dispatcher::start_with_domain_routing(
        &arguments.listen,
        tun_uds,
        pool.clone(),
        stats.clone(),
        cancel.clone(),
        domain_routing,
        workers,
    )
    .await;
    let (dispatcher, local_port) = match dispatcher_result {
        Ok(value) => value,
        Err(error) => {
            if let Some(active) = js_calls.take() {
                active.leave_creator().await;
            }
            return Err(error);
        }
    };
    let control_task = start_control_input(
        cancel.clone(),
        paused.clone(),
        captcha,
        events.clone(),
        finish_js_calls.clone(),
        dispatcher.clone(),
        stdin,
    );
    let local_port: Arc<str> = Arc::from(local_port);
    let raw_turn_ips = if !arguments.custom_turn_ips.is_empty() {
        &arguments.custom_turn_ips
    } else {
        &arguments.turn
    };
    let turn_ip_pool = Arc::new(TurnIpPool::new(
        parse_custom_turn_ips(raw_turn_ips),
        MAX_WORKERS,
    ));
    let initial_workers = workers;
    let scale_notify = Arc::new(tokio::sync::Notify::new());
    let params_workers = Arc::new(AtomicUsize::new(initial_workers));
    let params = Arc::new(RuntimeParams {
        peer,
        turn_host: (!arguments.turn.is_empty()).then(|| Arc::from(arguments.turn.as_str())),
        turn_port: (!arguments.port.is_empty()).then(|| Arc::from(arguments.port.as_str())),
        turn_ip_pool,
        turn_transport,
        srtp_mode,
        hashes: hashes.into(),
        wrap_key,
        client_wrap_key,
        mode,
        generation: arguments.generation,
        salt: Arc::from(arguments.salt.as_str()),
        local_port: local_port.clone(),
        password: Arc::from(password.as_str()),
        workers: params_workers,
        configured_workers: workers,
    });
    print_configuration(
        &arguments,
        auth.client_ids(),
        workers,
        groups,
        &params,
        &local_port,
    );
    let repair = RepairState::new(MAX_WORKERS);
    let stats_task = tokio::spawn(stats.clone().run(events.clone(), cancel.clone()));
    let (config_tx, mut config_rx) = tokio::sync::mpsc::channel::<String>(32);
    let config_events = events.clone();
    let config_task = tokio::spawn(async move {
        let mut last_config = None;
        while let Some(config) = config_rx.recv().await {
            if last_config.as_deref() == Some(config.as_str()) {
                continue;
            }
            if let Some(value) = config.strip_prefix("TUNCONF:") {
                dns::mark_tunnel_active();
                let mut fields = value.splitn(3, ':');
                let ip = fields.next().unwrap_or_default();
                let dns = fields.next().unwrap_or_default();
                crate::log_error!("[КЛИЕНТ] Tunnel IP: {ip}/32 | DNS: {dns}");
            }
            config_events.config(&config);
            last_config = Some(config);
        }
    });
    let (ready_credential_tx, ready_credential_rx) =
        if should_leave_js_creator(js_hash_mode, js_auth_mode) {
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            (Some(tx), Some(rx))
        } else {
            (None, None)
        };
    let context = Arc::new(GroupContext {
        params,
        auth,
        dispatcher: dispatcher.clone(),
        pool,
        stats,
        events: events.clone(),
        paused,
        config_tx,
        start_pacer: Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL)),
        credential_pacer: Arc::new(tokio::sync::Mutex::new(())),
        ready_credential_tx,
        config_sent: Arc::new(AtomicBool::new(false)),
        config_in_flight: Arc::new(AtomicBool::new(false)),
        server_stream_repair: Arc::new(AtomicBool::new(false)),
        repair,
        shutdown: Arc::new(ShutdownCoordinator::new()),
        cancel: cancel.clone(),
        scale_notify,
    });
    let required_ready_bots = required_js_ready_bots(groups);
    if js_auth_mode {
        crate::log_error!("[VK JS] Создатель удерживает звонок");
    }
    let creator_leave_task = match (js_calls.as_ref(), ready_credential_rx) {
        (Some(active), Some(receiver)) => Some(tokio::spawn(leave_js_creator_after_ready_workers(
            active.clone(),
            receiver,
            required_ready_bots,
            cancel.clone(),
        ))),
        _ => None,
    };
    let shutdown_events = events.clone();
    let tun_lost_events = events.clone();
    let tun_io_lost = dispatcher.tun_io_lost.clone();
    tokio::spawn(async move {
        loop {
            tun_io_lost.notified().await;
            tun_lost_events.tun_lost();
        }
    });
    let groups_future = run_groups(context);
    tokio::pin!(groups_future);
    let groups_completed = tokio::select! {
        _ = &mut groups_future => true,
        _ = tokio::signal::ctrl_c() => {
            crate::log_error!("[КЛИЕНТ] Получен сигнал завершения");
            cancel.cancel();
            false
        }
        _ = cancel.cancelled() => false,
    };
    cancel.cancel();
    if !groups_completed {
        groups_future.await;
    }
    dispatcher.shutdown().await;
    stats_task.abort();
    config_task.abort();
    control_task.abort();
    parent_task.abort();
    let _ = stats_task.await;
    let _ = config_task.await;
    let _ = control_task.await;
    let _ = parent_task.await;
    if let Some(mut task) = creator_leave_task
        && tokio::time::timeout(Duration::from_secs(9), &mut task)
            .await
            .is_err()
    {
        task.abort();
        let _ = task.await;
    }
    if let Some(active) = js_calls.take() {
        if finish_js_calls.load(Ordering::Acquire) {
            active.finish().await;
        } else {
            active.leave_creator().await;
        }
    }
    let termination = dispatcher.termination();
    shutdown_events.stopped();
    crate::log_error!("[КЛИЕНТ] Все воркеры завершены");
    match termination {
        None => Ok(EXIT_NORMAL),
        Some(Termination::TunLost) => Ok(EXIT_TUN_LOST),
        Some(Termination::Fatal) => bail!("аварийное завершение TUN или UDP задачи"),
    }
}

async fn leave_js_creator_after_ready_workers(
    active: vk_js_calls::ActiveCalls,
    receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    required_ready_bots: usize,
    cancel: CancellationToken,
) {
    let all_ready = wait_for_js_credential_readiness(receiver, required_ready_bots, cancel).await;
    if all_ready {
        crate::log_error!("[VK JS] TURN-боты готовы, создатель выходит из звонка");
    }
    let _ = tokio::time::timeout(Duration::from_secs(8), active.leave_creator()).await;
}

async fn wait_for_js_credential_readiness(
    mut receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    expected_credentials: usize,
    cancel: CancellationToken,
) -> bool {
    let mut ready = HashSet::with_capacity(expected_credentials);
    while ready.len() < expected_credentials {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break,
            credential = receiver.recv() => match credential {
                Some(credential) => {
                    ready.insert(credential);
                }
                None => break,
            },
        }
    }
    ready.len() == expected_credentials
}

fn required_js_ready_bots(groups: usize) -> usize {
    groups.div_ceil(GROUPS_PER_CREDENTIAL).clamp(1, 2)
}

fn normalize_worker_count(requested: usize) -> usize {
    if requested <= 5 {
        return 5;
    }
    requested.clamp(WORKERS_PER_GROUP, MAX_WORKERS) / WORKERS_PER_GROUP * WORKERS_PER_GROUP
}

fn should_leave_js_creator(js_hash_mode: bool, js_auth_mode: bool) -> bool {
    js_hash_mode && js_auth_mode
}

fn normalized_arguments() -> Vec<String> {
    std::env::args().map(normalize_cli_argument).collect()
}

fn normalize_cli_argument(argument: String) -> String {
    const LONG_FLAGS: &[&str] = &[
        "turn",
        "custom-turn-ips",
        "port",
        "listen",
        "vk",
        "vk-hash-mode",
        "peer",
        "client-ids",
        "vk-auth-mode",
        "captcha-mode",
        "fingerprint",
        "obfs",
        "turn-transport",
        "srtp-mode",
        "gen",
        "salt",
        "tun-uds",
        "validate-vk-hashes",
        "domain-routing",
        "exclude-ru-zones",
        "template-domains",
        "domain-whitelist",
        "direct-domains",
        "domains-file",
        "ip-routing",
        "ip-whitelist",
        "direct-ips",
        "runtime-threads",
        "workers",
        "exact-workers",
    ];
    let Some(rest) = argument.strip_prefix('-') else {
        return argument;
    };
    let name = rest.split_once('=').map_or(rest, |(name, _)| name);
    if !rest.starts_with('-') && LONG_FLAGS.contains(&name) {
        format!("-{argument}")
    } else {
        argument
    }
}

fn normalize_stdin_line(mut bytes: Vec<u8>) -> String {
    while bytes.last() == Some(&b'\n') || bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn spawn_stdin_reader() -> tokio::sync::mpsc::Receiver<StdinLine> {
    let (sender, receiver) = tokio::sync::mpsc::channel(STDIN_QUEUE_CAPACITY);
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        let mut reader = stdin.lock();
        let mut line = Vec::new();
        loop {
            let available = match reader.fill_buf() {
                Ok(available) => available,
                Err(error) => {
                    let _ = sender.blocking_send(Err(error));
                    break;
                }
            };
            if available.is_empty() {
                if !line.is_empty() {
                    let _ = sender.blocking_send(Ok(normalize_stdin_line(line)));
                }
                break;
            }
            let mut chunk_end = available.len();
            let mut complete = false;
            if let Some(position) = available.iter().position(|&b| b == b'\n') {
                chunk_end = position;
                complete = true;
            }
            line.extend_from_slice(&available[..chunk_end]);
            let consumed = if complete {
                chunk_end + 1
            } else {
                available.len()
            };
            reader.consume(consumed);

            if line.len() > MAX_STDIN_LINE_BYTES {
                let _ = sender.blocking_send(Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "stdin line too large",
                )));
                break;
            }
            if complete
                && sender
                    .blocking_send(Ok(normalize_stdin_line(std::mem::take(&mut line))))
                    .is_err()
            {
                break;
            }
        }
    });
    receiver
}

async fn read_vk_js_bootstrap(
    stdin: &mut tokio::sync::mpsc::Receiver<StdinLine>,
) -> Result<vk_js_calls::Bootstrap> {
    let line = tokio::time::timeout(Duration::from_secs(15), stdin.recv())
        .await
        .context("тайм-аут передачи данных Auto JS")?
        .context("канал stdin закрыт до передачи данных Auto JS")?
        .context("чтение данных Auto JS")?;
    let encoded = line
        .trim()
        .strip_prefix("VK_JS_BOOTSTRAP:")
        .context("неверный формат данных Auto JS")?;
    if encoded.len() > 32 * 1024 {
        bail!("слишком большие данные Auto JS");
    }
    let decoded = STANDARD
        .decode(encoded)
        .context("повреждены данные Auto JS")?;
    let bootstrap: vk_js_calls::Bootstrap =
        serde_json::from_slice(&decoded).context("некорректные данные Auto JS")?;
    Ok(bootstrap)
}

async fn run_vk_hash_validation(arguments: &Arguments) -> Result<()> {
    let hashes: Vec<_> = parse_hashes(&arguments.vk)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK для проверки");
    }
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    for (hash, result) in auth::check_vk_hashes(&arguments.fingerprint, &client_ids, &hashes).await
    {
        let payload = match result {
            VkHashCheck::Valid => serde_json::json!({
                "hash": hash,
                "status": "valid"
            }),
            VkHashCheck::Invalid { code, message } => serde_json::json!({
                "hash": hash,
                "status": "invalid",
                "code": code,
                "message": message
            }),
            VkHashCheck::Unavailable { message } => serde_json::json!({
                "hash": hash,
                "status": "unavailable",
                "message": message
            }),
        };
        println!("HASH_CHECK:{payload}");
    }
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
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("пустой DNS-ответ для пира")))
        .context("ошибка разбора пира")
}

fn start_control_input(
    cancel: CancellationToken,
    paused: Arc<PauseGate>,
    captcha: Arc<CaptchaSolver>,
    events: Events,
    finish_js_calls: Arc<AtomicBool>,
    dispatcher: Arc<Dispatcher>,
    mut stdin: tokio::sync::mpsc::Receiver<StdinLine>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let control_required = events.enabled();
        loop {
            let next = tokio::select! {
                _ = cancel.cancelled() => return,
                next = stdin.recv() => next,
            };
            let line = match next {
                Some(Ok(line)) => line,
                Some(Err(error)) => {
                    crate::log_error!("[КЛИЕНТ] Ошибка канала управления: {error}");
                    if control_required {
                        cancel.cancel();
                    }
                    return;
                }
                None => {
                    if control_required {
                        crate::log_error!("[КЛИЕНТ] Канал управления закрыт");
                        cancel.cancel();
                    }
                    return;
                }
            };
            let line = line.trim();
            if !line.contains("error:tunnel stopped") && line != "FINISH_VK_CALLS" {
                crate::log_error!("[STDIN] {}", loggable_stdin_line(line));
            }
            match line {
                "PAUSE" => paused.set_paused(true),
                "RESUME" => paused.set_paused(false),
                "FINISH_VK_CALLS" => finish_js_calls.store(true, Ordering::Release),
                "STOP" => {
                    crate::log_error!("[КЛИЕНТ] Получена команда STOP");
                    cancel.cancel();
                    return;
                }
                _ => {
                    if let Some(result) = line.strip_prefix("CAPTCHA_RESULT|") {
                        if captcha.submit_result(result.to_owned()) {
                            crate::log_error!("[КАПЧА] Результат от Kotlin записан в канал");
                        } else {
                            crate::log_error!(
                                "[КАПЧА] Канал результата уже заполнен, устаревший ответ отклонён"
                            );
                        }
                    } else if let Some(payload) = line.strip_prefix("UPDATE_DOMAINS|") {
                        dispatcher.update_domain_routing(payload);
                    }
                }
            }
        }
    })
}

fn loggable_stdin_line(line: &str) -> &str {
    if line.starts_with("CAPTCHA_RESULT|") {
        "CAPTCHA_RESULT|<скрыто>"
    } else if line.starts_with("VK_JS_BOOTSTRAP:") {
        "VK_JS_BOOTSTRAP:<скрыто>"
    } else {
        line
    }
}

fn start_parent_monitor(cancel: CancellationToken) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        #[cfg(unix)]
        {
            let parent = unsafe { libc::getppid() };
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_secs(2)) => {}
                }
                if unsafe { libc::getppid() } != parent {
                    cancel.cancel();
                    return;
                }
            }
        }
        #[cfg(not(unix))]
        cancel.cancelled().await;
    })
}

fn print_configuration(
    arguments: &Arguments,
    client_ids: String,
    workers: usize,
    groups: usize,
    params: &RuntimeParams,
    local_port: &str,
) {
    let hashes = params.hashes.len();
    let turn_transport = params.turn_transport;
    let srtp_mode = params.srtp_mode;
    let captcha = match arguments.captcha_mode.as_str() {
        "wv" => "WBV selected in Android",
        "rjs" => "RJS Rust v2 with WBV Auto fallback",
        _ => "AUTO: Rust v2 x2 -> WBV Auto x2 -> Rust v2 x1 -> Manual WBV",
    };
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
    crate::log_error!("[КЛИЕНТ] VK Creds: Client IDs: {client_ids}");
    crate::log_error!("[КЛИЕНТ] VK Auth: {}", arguments.vk_auth_mode);
    crate::log_error!("[КЛИЕНТ] TLS: {} fingerprint", arguments.fingerprint);
    crate::log_error!("[КЛИЕНТ] Воркеров: {workers} (групп: {groups}, по {WORKERS_PER_GROUP})");
    crate::log_error!("[КЛИЕНТ] Хешей: {hashes}");
    crate::log_error!(
        "[КЛИЕНТ] Слушаю: {} (порт {local_port}) | Пир: {}",
        arguments.listen,
        arguments.peer
    );
    crate::log_error!(
        "[КЛИЕНТ] TURN: {} | SRTP: {} | DTLS: 1.2 (ECDSA P-256) | WRAP: ON | obfs={}",
        turn_transport.as_str(),
        srtp_mode.as_str(),
        arguments.obfs,
    );
    crate::log_error!("[WRAP] WRAP Ключ вычислен ✓");
    crate::log_error!("[КЛИЕНТ] Captcha: {captcha}");
    if arguments.domain_routing {
        crate::log_error!(
            "[КЛИЕНТ] Доменная маршрутизация: ВКЛ (RU/SU/РФ: {}, кастомных доменов: {})",
            if arguments.exclude_ru_zones {
                "ДА"
            } else {
                "НЕТ"
            },
            arguments
                .direct_domains
                .split(',')
                .filter(|s| !s.trim().is_empty())
                .count()
        );
    }
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
}

#[cfg(test)]
mod worker_count_tests {
    use super::*;

    #[test]
    fn every_supported_total_maps_to_complete_allocation_groups() {
        for groups in 1..=MAX_WORKERS / WORKERS_PER_GROUP {
            let workers = groups * WORKERS_PER_GROUP;
            assert_eq!(normalize_worker_count(workers), workers);
            assert_eq!(workers / WORKERS_PER_GROUP, groups);
        }
        assert_eq!(normalize_worker_count(MAX_WORKERS), 100);
    }

    #[test]
    fn invalid_totals_never_create_partial_or_excess_group() {
        for requested in 0..=1_000 {
            let workers = normalize_worker_count(requested);
            if requested <= 5 {
                assert_eq!(workers, 5);
            } else {
                assert!((WORKERS_PER_GROUP..=MAX_WORKERS).contains(&workers));
                assert_eq!(workers % WORKERS_PER_GROUP, 0);
            }
        }
    }

    #[test]
    fn runtime_thread_budget_follows_stream_budget_and_cpu_limit() {
        assert_eq!(runtime_worker_threads_with_limit(5, 0, 16), 1);
        assert_eq!(runtime_worker_threads_with_limit(10, 0, 16), 2);
        assert_eq!(runtime_worker_threads_with_limit(15, 0, 16), 2);
        assert_eq!(runtime_worker_threads_with_limit(20, 0, 16), 3);
        assert_eq!(runtime_worker_threads_with_limit(30, 0, 16), 4);
        assert_eq!(runtime_worker_threads_with_limit(50, 0, 16), 7);
        assert_eq!(runtime_worker_threads_with_limit(100, 0, 16), 13);
        assert_eq!(runtime_worker_threads_with_limit(100, 3, 16), 3);
        assert_eq!(runtime_worker_threads_with_limit(100, 32, 16), 16);
    }

    #[test]
    fn native_worker_normalization_is_bounded_and_group_aligned() {
        assert_eq!(normalize_worker_count(usize::MAX), MAX_WORKERS);
        assert_eq!(normalize_worker_count(usize::MAX - 1), MAX_WORKERS);
        assert_eq!(normalize_worker_count(48), 40);
        assert_eq!(normalize_worker_count(5), 5);
        assert_eq!(normalize_worker_count(4), 5);
        assert!(
            MAX_WORKERS.div_ceil(worker::WORKERS_PER_CREDENTIAL)
                <= vk_js_calls::MAX_ACCOUNT_CREDENTIALS
        );
    }

    #[test]
    fn auto_js_leaves_creator_only_when_both_modes_are_active() {
        assert!(!should_leave_js_creator(true, false));
        assert!(should_leave_js_creator(true, true));
        assert!(!should_leave_js_creator(false, false));
        assert!(!should_leave_js_creator(false, true));
    }

    #[test]
    fn auto_js_waits_for_at_most_two_independent_turn_bots() {
        assert_eq!(required_js_ready_bots(1), 1);
        assert_eq!(required_js_ready_bots(2), 2);
        assert_eq!(required_js_ready_bots(3), 2);
        assert_eq!(required_js_ready_bots(4), 2);
        assert_eq!(required_js_ready_bots(13), 2);
    }

    #[test]
    fn vk_hash_may_start_with_a_hyphen() {
        let arguments = Arguments::try_parse_from([
            "csqtt-client",
            "--vk",
            "-Wabc",
            "--peer",
            "127.0.0.1:9000",
        ])
        .unwrap();
        assert_eq!(arguments.vk, "-Wabc");
    }

    #[test]
    fn android_single_dash_flags_do_not_rewrite_hyphenated_hash_values() {
        assert_eq!(normalize_cli_argument("-vk".to_owned()), "--vk");
        assert_eq!(
            normalize_cli_argument("-client-ids".to_owned()),
            "--client-ids"
        );
        assert_eq!(normalize_cli_argument("-Wabc".to_owned()), "-Wabc");
    }

    #[test]
    fn removed_identity_flags_are_not_normalized_or_accepted() {
        assert_eq!(
            normalize_cli_argument("-client-id".to_owned()),
            "-client-id"
        );
        assert_eq!(
            normalize_cli_argument("-device-id".to_owned()),
            "-device-id"
        );
        assert_eq!(normalize_cli_argument("-password".to_owned()), "-password");
        assert!(
            Arguments::try_parse_from([
                "csqtt-client",
                "--peer",
                "127.0.0.1:9000",
                "--password",
                "secret",
            ])
            .is_err()
        );
    }

    #[test]
    fn hyphenated_hash_values_parse_without_redistribution_flag() {
        let arguments = Arguments::try_parse_from([
            "csqtt-client",
            "--vk",
            "-Wabc,-Wdef",
            "--peer",
            "127.0.0.1:9000",
        ])
        .unwrap();
        assert_eq!(arguments.vk, "-Wabc,-Wdef");
    }

    #[tokio::test]
    async fn js_creator_waits_for_every_distinct_ready_credential() {
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        sender.send(1).unwrap();
        sender.send(1).unwrap();
        sender.send(2).unwrap();
        assert!(wait_for_js_credential_readiness(receiver, 2, CancellationToken::new()).await);
    }

    #[tokio::test]
    async fn js_creator_wait_is_cancel_safe() {
        let (_sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(!wait_for_js_credential_readiness(receiver, 1, cancel).await);
    }

    #[tokio::test]
    async fn bootstrap_keeps_control_lines_that_follow_it() {
        let (sender, mut receiver) = tokio::sync::mpsc::channel(4);
        let encoded = STANDARD.encode(r#"{"token":"t"}"#);
        sender
            .send(Ok(format!("VK_JS_BOOTSTRAP:{encoded}")))
            .await
            .unwrap();
        sender.send(Ok("PAUSE".to_owned())).await.unwrap();
        let bootstrap = read_vk_js_bootstrap(&mut receiver).await.unwrap();
        assert_eq!(bootstrap.token, "t");
        assert_eq!(receiver.recv().await.unwrap().unwrap(), "PAUSE");
    }

    #[test]
    fn captcha_and_bootstrap_payloads_are_redacted_in_stdin_logs() {
        assert_eq!(
            loggable_stdin_line("CAPTCHA_RESULT|secret-token"),
            "CAPTCHA_RESULT|<скрыто>"
        );
        assert_eq!(
            loggable_stdin_line("VK_JS_BOOTSTRAP:abc"),
            "VK_JS_BOOTSTRAP:<скрыто>"
        );
        assert_eq!(loggable_stdin_line("PAUSE"), "PAUSE");
    }

    #[test]
    fn single_dash_domain_flags_and_files_parse_correctly() {
        assert_eq!(
            normalize_cli_argument("-template-domains".to_owned()),
            "--template-domains"
        );
        assert_eq!(
            normalize_cli_argument("-domains-file".to_owned()),
            "--domains-file"
        );
        let raw_args = [
            "csqtt-client",
            "--vk",
            "abc",
            "--peer",
            "127.0.0.1:9000",
            "-template-domains",
            "-domains-file",
            "domains.txt",
        ];
        let normalized = raw_args
            .iter()
            .map(|s| normalize_cli_argument(s.to_string()));
        let arguments = Arguments::try_parse_from(normalized).unwrap();
        assert!(arguments.template_domains);
        assert_eq!(arguments.domains_file, vec!["domains.txt"]);
    }
}
