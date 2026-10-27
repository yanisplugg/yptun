#![recursion_limit = "256"]
#![allow(linker_messages)]

#[cfg(target_os = "linux")]
#[global_allocator]
static GLOBAL_ALLOCATOR: snmalloc_rs::SnMalloc = snmalloc_rs::SnMalloc;

pub const fn allocator_name() -> &'static str {
    #[cfg(target_os = "linux")]
    {
        "snmalloc"
    }
    #[cfg(not(target_os = "linux"))]
    {
        "system"
    }
}

pub(crate) fn collect_allocator_thread_heap() -> bool {
    false
}

mod dataplane;
#[path = "../shared/dtls.rs"]
pub mod dtls;
mod model;
mod net_setup;
mod packet;
mod path_probe;
mod protocol;
mod proxy_route;
mod tokio_io;
mod tproxy;
mod tun_device;
mod web_panel;
#[path = "../shared/wire_protocol.rs"]
mod wire_protocol;

const WIRE_PROTOCOL_REVISION: &str = "CSQTT-WIRE-3";

use anyhow::{Context, Result, bail};
use clap::Parser;
use dashmap::DashMap;
use model::{
    DEFAULT_AUTO_RESTART_INTERVAL_HOURS, Database, DatabasePersistence, load_database, now,
    random_password, save_database,
};
use protocol::Session;
use std::{
    io::Read,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::RwLock,
};

#[derive(Parser, Debug)]
#[command(name = "csqtt", version, about = "Сервер и консоль управления CSQTT")]
struct Args {
    #[arg(long, hide = true)]
    protocol_revision: bool,

    #[arg(long, default_value = "0.0.0.0:46000", help = "UDP-адрес сервера")]
    listen: SocketAddr,

    #[arg(long, default_value_t = 46002, help = "HTTPS-порт веб-панели")]
    web_port: u16,

    #[arg(long, default_value = "/etc/csqtt", help = "Каталог конфигурации")]
    config_dir: std::path::PathBuf,

    #[arg(
        long,
        env = "CSQTT_MAIN_PASSWORD",
        default_value = "",
        help = "Основной пароль CSQTT"
    )]
    password: String,

    #[arg(
        long,
        env = "CSQTT_WEB_USER",
        default_value = "admin",
        help = "Логин веб-панели"
    )]
    web_user: String,

    #[arg(
        long,
        env = "CSQTT_WEB_PASS",
        default_value = "",
        help = "Пароль веб-панели"
    )]
    web_pass: String,

    #[arg(
        long,
        env = "CSQTT_PUBLIC_IP",
        default_value = "",
        help = "Внешний IPv4-адрес сервера для клиентских ссылок"
    )]
    public_ip: String,

    #[arg(
        long,
        env = "CSQTT_DNS",
        help = "Один или два DNS IPv4-адреса через запятую"
    )]
    dns: Option<String>,

    #[arg(
        long,
        env = "CSQTT_WEB_SECRET_PATH",
        default_value = "",
        help = "Секретный префикс пути к веб-панели"
    )]
    web_secret_path: String,

    #[arg(
        long,
        env = "CSQTT_SECURE_COOKIE",
        default_value_t = false,
        help = "Выдавать cookie только по HTTPS"
    )]
    secure_cookie: bool,

    #[arg(long, help = "Запустить службу CSQTT")]
    start: bool,

    #[arg(long, help = "Остановить службу CSQTT")]
    stop: bool,

    #[arg(long, help = "Перезапустить службу CSQTT")]
    restart: bool,

    #[arg(long, short = 'd', help = "Открыть DPI-монитор")]
    dpi: bool,

    #[arg(long, hide = true)]
    tproxy_child: bool,

    #[arg(long, hide = true)]
    tproxy_port: Option<u16>,

    #[arg(long, hide = true)]
    tproxy_status: Option<std::path::PathBuf>,

    #[arg(
        long,
        short = 's',
        default_value_t = 0,
        help = "Число последних DPI-записей"
    )]
    samples: usize,
}

#[derive(Debug, Default, serde::Deserialize)]
struct DeployOverrides {
    #[serde(default)]
    main_password: String,
}

fn normalize_dns(value: &str) -> Result<String> {
    let addresses: Vec<_> = value
        .split(',')
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .collect();
    if addresses.is_empty() || addresses.len() > 2 {
        bail!("DNS must contain one or two IPv4 addresses");
    }
    for address in &addresses {
        address
            .parse::<std::net::Ipv4Addr>()
            .with_context(|| format!("invalid DNS IPv4 address: {address}"))?;
    }
    Ok(addresses.join(","))
}

fn is_public_ipv4(ip: std::net::Ipv4Addr) -> bool {
    !ip.is_unspecified() && !ip.is_loopback() && !ip.is_private()
}

async fn detect_public_ipv4() -> Option<String> {
    if let Ok(output) = tokio::process::Command::new("ip")
        .args(["-4", "route", "get", "77.88.8.8"])
        .output()
        .await
        && output.status.success()
    {
        let text = String::from_utf8_lossy(&output.stdout);
        let words: Vec<&str> = text.split_whitespace().collect();
        for i in 0..words.len().saturating_sub(1) {
            if words[i] == "src"
                && let Ok(ip) = words[i + 1].parse::<std::net::Ipv4Addr>()
                && is_public_ipv4(ip)
            {
                return Some(ip.to_string());
            }
        }
    }
    if let Ok(output) = tokio::process::Command::new("ip")
        .args(["-4", "-o", "addr", "show", "scope", "global"])
        .output()
        .await
        && output.status.success()
    {
        let text = String::from_utf8_lossy(&output.stdout);
        for line in text.lines() {
            let words: Vec<&str> = line.split_whitespace().collect();
            if let Some(pos) = words.iter().position(|&w| w == "inet")
                && let Some(cidr) = words.get(pos + 1)
                && let Some(addr) = cidr.split('/').next()
                && let Ok(ip) = addr.parse::<std::net::Ipv4Addr>()
                && is_public_ipv4(ip)
            {
                return Some(ip.to_string());
            }
        }
    }
    if let Ok(output) = tokio::process::Command::new("curl")
        .args([
            "-4",
            "-fsS",
            "--connect-timeout",
            "3",
            "--max-time",
            "5",
            "https://api.ipify.org",
        ])
        .output()
        .await
        && output.status.success()
    {
        let text = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if let Ok(ip) = text.parse::<std::net::Ipv4Addr>()
            && is_public_ipv4(ip)
        {
            return Some(text);
        }
    }
    for host in ["api.ipify.org", "ipv4.icanhazip.com"] {
        if let Ok(Ok(mut stream)) = tokio::time::timeout(
            Duration::from_secs(3),
            tokio::net::TcpStream::connect((host, 80)),
        )
        .await
        {
            let req = format!(
                "GET / HTTP/1.1\r\nHost: {host}\r\nUser-Agent: curl/7.88.1\r\nConnection: close\r\n\r\n"
            );
            if stream.write_all(req.as_bytes()).await.is_ok() {
                let mut buf = Vec::new();
                if stream.read_to_end(&mut buf).await.is_ok() {
                    let text = String::from_utf8_lossy(&buf);
                    if let Some(body) = text.split("\r\n\r\n").nth(1) {
                        let ip_str = body.trim();
                        if let Ok(ip) = ip_str.parse::<std::net::Ipv4Addr>()
                            && is_public_ipv4(ip)
                        {
                            return Some(ip_str.to_string());
                        }
                    }
                }
            }
        }
    }
    None
}

pub struct App {
    pub db: RwLock<Database>,
    pub db_persistence: DatabasePersistence,
    pub dns: RwLock<String>,
    pub startup_main_password: String,
    pub config_dir: std::path::PathBuf,
    pub listen: SocketAddr,
    pub web_port: u16,
    pub web_user: String,
    pub web_pass: String,
    pub web_secret_path: String,
    pub public_ip: Arc<RwLock<String>>,
    pub secure_cookie: bool,
    pub sessions: DashMap<u64, Arc<Session>>,
    pub password_epochs: DashMap<String, Arc<protocol::PasswordEpochSlot>>,
    pub web_sessions: DashMap<String, i64>,
    pub login_limits: DashMap<String, (u32, i64)>,
    pub web_auth_admission: std::sync::Mutex<()>,
    pub bytes_from_client: Arc<AtomicU64>,
    pub bytes_to_client: Arc<AtomicU64>,
    pub total_connections: AtomicU64,
    pub cpu_percent: AtomicU64,
    pub cpu_cores: AtomicU64,
    pub started: i64,
    pub derived_keys: DashMap<String, [u8; 32]>,
    pub logs: std::sync::Mutex<std::collections::VecDeque<String>>,
    pub logging_active: std::sync::atomic::AtomicBool,
    pub stream_debug_active: Arc<AtomicBool>,
    pub log_file_path: std::path::PathBuf,
    pub proxy_route: RwLock<Option<Arc<proxy_route::ProxyRoute>>>,
    pub proxy_operation: tokio::sync::Mutex<()>,
    pub proxy_trigger: tokio::sync::Notify,
    pub proxy_port_listening: std::sync::atomic::AtomicBool,
    pub proxy_health_error: std::sync::RwLock<Option<String>>,
    pub memory_trim_gate: tokio::sync::Mutex<()>,
    pub memory_trim_count: AtomicU64,
    pub memory_trim_last_unix: AtomicU64,
    pub auto_restart_interval_tx: tokio::sync::watch::Sender<u8>,
    pub restart_pending: AtomicBool,
    pub(crate) dataplane:
        std::sync::OnceLock<dataplane::DataplaneHandle<protocol::ProtocolCommand>>,
}

#[inline]
pub fn lock_unpoison<T>(mutex: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[inline]
pub fn read_unpoison<T>(lock: &std::sync::RwLock<T>) -> std::sync::RwLockReadGuard<'_, T> {
    lock.read()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[inline]
pub fn write_unpoison<T>(lock: &std::sync::RwLock<T>) -> std::sync::RwLockWriteGuard<'_, T> {
    lock.write()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

pub async fn trim_memory(app: &Arc<App>) -> Result<bool> {
    let _trim_guard = app.memory_trim_gate.lock().await;
    let allocator_collected = protocol::compact_memory(app).await?;
    app.memory_trim_count.fetch_add(1, Ordering::Relaxed);
    app.memory_trim_last_unix
        .store(now().max(0) as u64, Ordering::Relaxed);
    Ok(allocator_collected)
}

fn schedule_proxy_shutdown_compaction(app: &Arc<App>) {
    let app = app.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(3)).await;
        if app.proxy_route.read().await.is_none() {
            let _ = trim_memory(&app).await;
        }
    });
}

pub(crate) const MAX_LOG_RECORD_BYTES: usize = 2_048;
pub(crate) const LOG_RING_CAPACITY: usize = 600;
const LOG_TRUNCATION_SUFFIX: &str = "...[truncated]";

fn utf8_prefix(value: &str, max_bytes: usize) -> &str {
    let mut end = value.len().min(max_bytes);
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    &value[..end]
}

fn append_utf8_prefix(target: &mut String, value: &str) {
    let remaining = MAX_LOG_RECORD_BYTES.saturating_sub(target.len());
    target.push_str(utf8_prefix(value, remaining));
}

fn format_log_record(time_str: &str, level: &str, msg: &str) -> String {
    let mut formatted = String::with_capacity(MAX_LOG_RECORD_BYTES);
    append_utf8_prefix(&mut formatted, "[");
    append_utf8_prefix(&mut formatted, time_str);
    append_utf8_prefix(&mut formatted, "] [");
    append_utf8_prefix(&mut formatted, level);
    append_utf8_prefix(&mut formatted, "] ");

    let remaining = MAX_LOG_RECORD_BYTES.saturating_sub(formatted.len());
    if msg.len() <= remaining {
        formatted.push_str(msg);
    } else if remaining > LOG_TRUNCATION_SUFFIX.len() {
        formatted.push_str(utf8_prefix(msg, remaining - LOG_TRUNCATION_SUFFIX.len()));
        formatted.push_str(LOG_TRUNCATION_SUFFIX);
    } else {
        formatted.push_str(utf8_prefix(msg, remaining));
    }

    debug_assert!(formatted.len() <= MAX_LOG_RECORD_BYTES);
    formatted
}

fn enqueue_log_write(path: std::path::PathBuf, line: String) {
    static SENDER: std::sync::OnceLock<std::sync::mpsc::SyncSender<(std::path::PathBuf, String)>> =
        std::sync::OnceLock::new();
    let sender = SENDER.get_or_init(|| {
        let (sender, receiver) = std::sync::mpsc::sync_channel(512);
        let _ = std::thread::Builder::new()
            .name("csqtt-log-writer".to_owned())
            .spawn(move || {
                while let Ok((path, line)) = receiver.recv() {
                    use std::io::Write;
                    if let Ok(metadata) = std::fs::metadata(&path)
                        && metadata.len() > 8 * 1024 * 1024
                    {
                        let _ = std::fs::remove_file(&path);
                    }
                    if let Ok(mut file) = std::fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(&path)
                    {
                        let _ = writeln!(file, "{line}");
                    }
                }
            });
        sender
    });
    let _ = sender.try_send((path, line));
}

pub fn log_event(app: &Arc<App>, level: &str, _module: &str, msg: &str) {
    if !app.logging_active.load(Ordering::Relaxed) {
        return;
    }
    let time_str = chrono::Local::now().format("%d %b %y %H:%M").to_string();
    let formatted = format_log_record(&time_str, level, msg);

    eprintln!("{}", formatted);

    let mut logs = lock_unpoison(&app.logs);
    logs.push_back(formatted.clone());
    if logs.len() > LOG_RING_CAPACITY {
        logs.pop_front();
    }
    drop(logs);

    let path = app.log_file_path.clone();
    enqueue_log_write(path, formatted);
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct CpuSnapshot {
    total: u64,
    process: u64,
    cores: u64,
}

fn parse_cpu_total(line: &str) -> Option<u64> {
    let mut values = line.split_whitespace().skip(1);
    let mut total = 0_u64;
    for _ in 0..4 {
        total = total.checked_add(values.next()?.parse().ok()?)?;
    }
    for value in values {
        total = total.checked_add(value.parse().ok()?)?;
    }
    Some(total)
}

fn parse_host_cpu(stat: &str) -> Option<(u64, u64)> {
    let aggregate = stat.lines().next()?;
    if !aggregate.starts_with("cpu ") {
        return None;
    }
    let total = parse_cpu_total(aggregate)?;
    let cores = stat
        .lines()
        .skip(1)
        .filter(|line| {
            line.strip_prefix("cpu")
                .and_then(|suffix| suffix.split_whitespace().next())
                .is_some_and(|cpu| !cpu.is_empty() && cpu.bytes().all(|byte| byte.is_ascii_digit()))
        })
        .count()
        .max(1) as u64;
    Some((total, cores))
}

fn parse_process_cpu(stat: &str) -> Option<u64> {
    let mut fields = stat.get(stat.rfind(')')? + 1..)?.split_whitespace();
    let user: u64 = fields.nth(11)?.parse().ok()?;
    let system: u64 = fields.next()?.parse().ok()?;
    Some(user.saturating_add(system))
}

fn read_proc_text<'a>(path: &str, buffer: &'a mut [u8]) -> Option<&'a str> {
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.read(buffer).ok()?;
    std::str::from_utf8(&buffer[..len]).ok()
}

fn cpu_percentage(previous: CpuSnapshot, current: CpuSnapshot) -> Option<u64> {
    let total_delta = current.total.checked_sub(previous.total)?;
    if total_delta == 0 {
        return None;
    }
    let process_delta = current.process.saturating_sub(previous.process);
    let process =
        (process_delta as f64 * current.cores as f64 * 100.0 / total_delta as f64).round() as u64;
    Some(process.min(current.cores.saturating_mul(100)))
}

async fn cpu_loop(app: Arc<App>) {
    let mut timer = tokio::time::interval(Duration::from_secs(1));
    let mut previous = None;
    let mut host_stat_buffer = [0_u8; 8192];
    let mut process_stat_buffer = [0_u8; 1024];
    loop {
        timer.tick().await;
        model::refresh_cached_now();
        protocol::refresh_monotonic_millis();
        if let (Some(host_stat), Some(process_stat)) = (
            read_proc_text("/proc/stat", &mut host_stat_buffer),
            read_proc_text("/proc/self/stat", &mut process_stat_buffer),
        ) && let Some((total, cores)) = parse_host_cpu(host_stat)
            && let Some(process) = parse_process_cpu(process_stat)
        {
            let current = CpuSnapshot {
                total,
                process,
                cores,
            };
            if let Some(previous) = previous
                && let Some(process_percent) = cpu_percentage(previous, current)
            {
                app.cpu_percent.store(process_percent, Ordering::Relaxed);
            }
            app.cpu_cores.store(cores, Ordering::Relaxed);
            previous = Some(current);
        }
    }
}

const PASSWORD_EPOCH_IDLE_TTL_MS: u64 = 60 * 60 * 1_000;
const PASSWORD_EPOCH_SWEEP_INTERVAL: Duration = Duration::from_secs(5 * 60);

async fn password_epoch_sweeper(app: Arc<App>) {
    let mut ticker = tokio::time::interval(PASSWORD_EPOCH_SWEEP_INTERVAL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    ticker.tick().await;
    loop {
        ticker.tick().await;
        let now_ms = protocol::unix_time_ms();
        let candidates: Vec<String> = app
            .password_epochs
            .iter()
            .filter(|entry| {
                now_ms.saturating_sub(entry.value().last_used_ms.load(Ordering::Relaxed))
                    >= PASSWORD_EPOCH_IDLE_TTL_MS
            })
            .map(|entry| entry.key().clone())
            .collect();
        for password in candidates {
            app.password_epochs.remove_if(&password, |_, slot| {
                if now_ms.saturating_sub(slot.last_used_ms.load(Ordering::Relaxed))
                    < PASSWORD_EPOCH_IDLE_TTL_MS
                {
                    return false;
                }
                !app.sessions
                    .iter()
                    .any(|session| session.password == password)
            });
        }
        if app.password_epochs.len().saturating_mul(4) < app.password_epochs.capacity() {
            app.password_epochs.shrink_to_fit();
        }
    }
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        match signal(SignalKind::terminate()) {
            Ok(mut terminate) => {
                tokio::select! {
                    _ = tokio::signal::ctrl_c() => {}
                    _ = terminate.recv() => {}
                }
            }
            Err(error) => {
                eprintln!("[SIGNAL] SIGTERM handler unavailable: {error}");
                let _ = tokio::signal::ctrl_c().await;
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

#[cfg(unix)]
async fn web_tls_reload_loop(
    tls_config: axum_server::tls_rustls::RustlsConfig,
    cert_path: std::path::PathBuf,
    key_path: std::path::PathBuf,
) {
    use tokio::signal::unix::{SignalKind, signal};

    let Ok(mut reload) = signal(SignalKind::user_defined1()) else {
        eprintln!("[WEB] SIGUSR1 TLS reload handler unavailable");
        return;
    };
    while reload.recv().await.is_some() {
        match tls_config.reload_from_pem_file(&cert_path, &key_path).await {
            Ok(()) => println!("[WEB] TLS certificate reloaded"),
            Err(error) => eprintln!("[WEB] TLS certificate reload failed: {error}"),
        }
    }
}

fn run_systemctl(action: &str) -> Result<()> {
    if std::env::var("CSQTT_SERVICE_MANAGER").is_ok_and(|value| value == "docker") {
        if action != "restart" {
            bail!("для управления контейнером используйте docker start/stop csqtt");
        }
        #[cfg(unix)]
        {
            let init_exe = std::fs::read_link("/proc/1/exe").ok();
            if init_exe.is_none() || init_exe != std::env::current_exe().ok() {
                bail!(
                    "PID 1 контейнера не является этим процессом; выполните docker restart csqtt"
                );
            }
            if unsafe { libc::kill(1, libc::SIGTERM) } == 0 {
                println!("[CLI] Docker-контейнер перезапускается");
                return Ok(());
            }
            return Err(std::io::Error::last_os_error().into());
        }
        #[cfg(not(unix))]
        bail!("Docker service manager поддерживается только на Unix");
    }
    #[cfg(unix)]
    {
        if unsafe { libc_geteuid() } != 0 {
            bail!("для управления службой csqtt нужны права root");
        }
    }
    println!("[CLI] Выполняется systemctl {action} csqtt...");
    let mut command = std::process::Command::new("systemctl");
    if action == "restart" {
        command.arg("--no-block");
    }
    let status = command.args([action, "csqtt"]).status();
    match status {
        Ok(s) if s.success() => {
            println!("[CLI] Готово");
            Ok(())
        }
        Ok(s) => {
            bail!("[CLI] systemctl завершился с ошибкой: {}", s);
        }
        Err(e) => {
            bail!("[CLI] не удалось запустить systemctl: {}", e);
        }
    }
}

const PANEL_RESTART_DELAY_MILLIS: u64 = 750;
static PANEL_RESTART_SEQUENCE: AtomicU64 = AtomicU64::new(1);

pub(crate) fn request_service_restart() -> Result<()> {
    if std::env::var("CSQTT_SERVICE_MANAGER").is_ok_and(|value| value == "systemd") {
        let sequence = PANEL_RESTART_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let unit = format!("csqtt-panel-restart-{}-{sequence}", std::process::id());
        let status = std::process::Command::new("systemd-run")
            .arg("--quiet")
            .arg(format!("--unit={unit}"))
            .arg(format!("--on-active={PANEL_RESTART_DELAY_MILLIS}ms"))
            .arg("--timer-property=AccuracySec=100ms")
            .arg("--collect")
            .arg("/usr/bin/systemctl")
            .args(["--no-block", "restart", "csqtt"])
            .status()
            .context("schedule delayed systemd restart with systemd-run")?;
        if status.success() {
            return Ok(());
        }
        bail!("systemd-run delayed restart failed: {status}");
    }
    #[cfg(unix)]
    {
        std::thread::Builder::new()
            .name("csqtt-panel-restart".to_owned())
            .spawn(|| {
                std::thread::sleep(Duration::from_millis(PANEL_RESTART_DELAY_MILLIS));
                let pid = unsafe { libc::getpid() };
                if unsafe { libc::kill(pid, libc::SIGTERM) } != 0 {
                    eprintln!(
                        "[SYSTEM] delayed self-restart signal failed: {}",
                        std::io::Error::last_os_error()
                    );
                }
            })
            .context("schedule delayed self restart")?;
        Ok(())
    }
    #[cfg(not(unix))]
    bail!("managed restart is supported only on Unix");
}

pub(crate) fn request_managed_restart(app: &Arc<App>, source: &str) -> Result<()> {
    if let Err(error) = protocol::notify_panel_restart(app) {
        log_event(
            app,
            "WARN",
            "SYSTEM",
            &format!("Restart notification was not queued: {error:#}"),
        );
    }

    match request_service_restart() {
        Ok(()) => {
            log_event(
                app,
                "INFO",
                "SYSTEM",
                &format!("Managed restart scheduled from {source}"),
            );
            Ok(())
        }
        Err(error) => {
            log_event(
                app,
                "ERROR",
                "SYSTEM",
                &format!("Managed restart request from {source} failed: {error:#}"),
            );
            Err(error)
        }
    }
}

async fn auto_restart_loop(app: Arc<App>, mut interval_rx: tokio::sync::watch::Receiver<u8>) {
    const RETRY_DELAY: Duration = Duration::from_secs(5 * 60);

    loop {
        let hours = *interval_rx.borrow_and_update();
        if hours == 0 {
            if interval_rx.changed().await.is_err() {
                return;
            }
            continue;
        }

        let interval = Duration::from_secs(u64::from(hours) * 60 * 60);
        let uptime_seconds = now().saturating_sub(app.started) as u64;
        let wait = interval.saturating_sub(Duration::from_secs(uptime_seconds));

        tokio::select! {
            _ = tokio::time::sleep(wait) => {
                if app.restart_pending.swap(true, Ordering::AcqRel) {
                    return;
                }

                log_event(
                    &app,
                    "INFO",
                    "SYSTEM",
                    &format!(
                        "Automatic restart interval reached: {hours}h uptime target"
                    ),
                );
                if request_managed_restart(&app, "automatic uptime interval").is_ok() {
                    return;
                }

                app.restart_pending.store(false, Ordering::Release);
                tokio::select! {
                    _ = tokio::time::sleep(RETRY_DELAY) => {}
                    changed = interval_rx.changed() => {
                        if changed.is_err() {
                            return;
                        }
                    }
                }
            }
            changed = interval_rx.changed() => {
                if changed.is_err() {
                    return;
                }
            }
        }
    }
}

fn acquire_instance_lock(config_dir: &std::path::Path) -> Result<std::fs::File> {
    let path = config_dir.join(".server.lock");
    let file = std::fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .truncate(false)
        .open(&path)
        .with_context(|| format!("open instance lock {}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::fd::AsRawFd;
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))?;
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            bail!("another CSQTT server instance owns {}", path.display());
        }
    }
    Ok(file)
}

fn diagnostic_heartbeat(
    mut writer: tokio::net::tcp::OwnedWriteHalf,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        use tokio::io::AsyncWriteExt;

        let mut timer = tokio::time::interval_at(
            tokio::time::Instant::now() + Duration::from_secs(60),
            Duration::from_secs(60),
        );
        timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            timer.tick().await;
            if writer.write_all(b"PING\n").await.is_err() {
                break;
            }
        }
    })
}

async fn run_dpi_client(samples: usize) -> Result<()> {
    if !cfg!(feature = "diagnostics") {
        bail!("csqtt was built without diagnostics; rebuild with --diagnostics");
    }
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    println!(
        "\x1b[1;36m════════════════════════════════════════════════════════════════════════════════════\x1b[0m"
    );
    println!(
        "\x1b[1;33m               CSQTT DEEP PACKET INSPECTION (DPI) LIVE TRAFFIC SNIFFER               \x1b[0m"
    );
    println!(
        "\x1b[1;36m════════════════════════════════════════════════════════════════════════════════════\x1b[0m"
    );

    let mut stream = match tokio::net::TcpStream::connect("127.0.0.1:46003").await {
        Ok(s) => s,
        Err(e) => {
            bail!(
                "Could not connect to running CSQTT server DPI socket (127.0.0.1:46003): {e}. Ensure csqtt is active!"
            );
        }
    };

    let req = format!("GET_DPI:{samples}\n");
    stream.write_all(req.as_bytes()).await?;

    let (reader, writer) = stream.into_split();
    let _heartbeat = diagnostic_heartbeat(writer);
    let mut buf_reader = BufReader::new(reader);
    let mut line = String::new();
    loop {
        line.clear();
        match buf_reader.read_line(&mut line).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                if line.trim().is_empty() {
                    continue;
                }
                if let Ok(frame) = serde_json::from_str::<protocol::DpiFrame>(&line) {
                    let dt =
                        chrono::DateTime::from_timestamp((frame.timestamp_ms / 1000) as i64, 0)
                            .map(|t| t.format("%H:%M:%S").to_string())
                            .unwrap_or_else(|| "00:00:00".to_string());

                    let dir_str = if frame.direction == "INBOUND" {
                        "\x1b[1;32m▲ INBOUND \x1b[0m"
                    } else {
                        "\x1b[1;34m▼ OUTBOUND\x1b[0m"
                    };

                    let pt_str = if frame.pt == 111 {
                        "\x1b[1;33mRTP-Audio (PT=111)\x1b[0m".to_string()
                    } else if frame.pt == 96 {
                        "\x1b[1;35mRTP-Video (PT=96)\x1b[0m".to_string()
                    } else {
                        format!("\x1b[1;36m{}\x1b[0m", frame.proto)
                    };

                    println!(
                        "[\x1b[2m{}\x1b[0m] {} | \x1b[1;33m{}\x1b[0m -> \x1b[1;36m{}\x1b[0m | Size: {} B (Wire: {} B)",
                        dt, dir_str, frame.src, frame.dst, frame.len, frame.wire_len
                    );
                    println!(
                        "  Proto: {} | Seq: #\x1b[1m{}\x1b[0m | Pass: \x1b[1;32m{}\x1b[0m | Gen: \x1b[1;33m{}\x1b[0m | Salt: \x1b[1;35m{}\x1b[0m",
                        pt_str, frame.seq, frame.password, frame.gen_id, frame.salt
                    );
                    println!("  Detail: \x1b[1;37m{}\x1b[0m", frame.detail);
                    if !frame.hex_preview.is_empty() {
                        println!(
                            "  Hex & ASCII Preview:\n\x1b[2m{}\x1b[0m",
                            frame.hex_preview
                        );
                    }
                    println!(
                        "\x1b[2m────────────────────────────────────────────────────────────────────────────────────\x1b[0m"
                    );
                }
            }
        }
    }

    Ok(())
}

fn print_cli_help() {
    println!(
        "CSQTT — сервер и консоль управления\n\n\
Использование:\n  \
  csqtt start                 Запустить службу\n  \
  csqtt stop                  Остановить службу\n  \
  csqtt restart               Перезапустить службу\n  \
  csqtt dpi                   Открыть DPI-монитор\n  \
  csqtt dpi s 50              Показать последние 50 DPI-записей и выйти\n  \
  csqtt help                  Показать эту справку\n\n\
Ручной запуск сервера:\n  \
  --listen АДРЕС              UDP-адрес, по умолчанию 0.0.0.0:46000\n  \
  --web-port ПОРТ             HTTPS-порт панели, по умолчанию 46002\n  \
  --config-dir ПУТЬ           Каталог конфигурации, по умолчанию /etc/csqtt\n  \
  --password ПАРОЛЬ           Основной пароль CSQTT\n  \
  --web-user ЛОГИН            Логин веб-панели\n  \
  --web-pass ПАРОЛЬ           Пароль веб-панели\n  \
  --dns IP[,IP]               Один или два DNS IPv4-адреса\n  \
  --secure-cookie             Выдавать cookie только по HTTPS\n"
    );
}

fn main() -> Result<()> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("create tokio runtime")?;
    let result = runtime.block_on(async_main());
    runtime.shutdown_timeout(Duration::from_secs(1));
    result
}

async fn async_main() -> Result<()> {
    rustls::crypto::aws_lc_rs::default_provider()
        .install_default()
        .ok();

    let args_vec: Vec<String> = std::env::args().collect();
    if args_vec.len() == 1
        || (args_vec.len() == 2 && matches!(args_vec[1].as_str(), "help" | "--help" | "-h"))
    {
        print_cli_help();
        return Ok(());
    }

    if args_vec.len() >= 2 {
        let cmd = args_vec[1].as_str();
        match cmd {
            "start" | "stop" | "restart" => {
                return run_systemctl(cmd);
            }
            "dpi" => {
                let mut samples = 0;
                if args_vec.len() >= 4 && args_vec[2] == "s" {
                    samples = args_vec[3].parse::<usize>().unwrap_or(0);
                }
                return run_dpi_client(samples).await;
            }
            _ => {}
        }
    }

    let args = Args::parse();

    if args.protocol_revision {
        println!("{}", WIRE_PROTOCOL_REVISION);
        return Ok(());
    }

    if args.start {
        return run_systemctl("start");
    }
    if args.stop {
        return run_systemctl("stop");
    }
    if args.restart {
        return run_systemctl("restart");
    }

    if args.dpi || args.samples > 0 {
        return run_dpi_client(args.samples).await;
    }

    if unsafe { libc_geteuid() } != 0 {
        bail!("csqtt-server must run as root");
    }

    if args.tproxy_child {
        let tproxy_status = args.tproxy_status.clone();
        return match run_tproxy_child(args.tproxy_port, args.tproxy_status).await {
            Ok(()) => Ok(()),
            Err(error) => {
                if let Some(path) = tproxy_status.as_deref() {
                    proxy_route::write_tproxy_child_error(path, &format!("{error:#}"));
                }
                Err(error)
            }
        };
    }

    tokio::fs::create_dir_all(&args.config_dir).await?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        tokio::fs::set_permissions(&args.config_dir, std::fs::Permissions::from_mode(0o700))
            .await?;
    }

    let _instance_lock = acquire_instance_lock(&args.config_dir)?;
    match tokio::time::timeout(
        Duration::from_secs(1),
        proxy_route::ProxyRoute::cleanup_orphaned_policy(),
    )
    .await
    {
        Ok(Ok(())) => {}
        Ok(Err(error)) => eprintln!("[PROXY] startup policy cleanup: {error:#}"),
        Err(_) => eprintln!("[PROXY] startup policy cleanup timed out"),
    }

    let mut db = load_database(&args.config_dir)?;
    db.admin_id.clear();
    db.bot_token.clear();
    db.web_sessions.clear();

    let deploy_overrides_path = args.config_dir.join("deploy-overrides.json");
    let deploy_overrides_present = deploy_overrides_path.exists();
    let deploy_overrides = if deploy_overrides_present {
        let text = std::fs::read_to_string(&deploy_overrides_path)
            .with_context(|| format!("read {}", deploy_overrides_path.display()))?;
        serde_json::from_str::<DeployOverrides>(&text)
            .with_context(|| format!("parse {}", deploy_overrides_path.display()))?
    } else {
        DeployOverrides::default()
    };

    if !args.password.is_empty() {
        db.main_password = args.password.clone();
    } else if !deploy_overrides.main_password.is_empty() {
        db.main_password = deploy_overrides.main_password.clone();
    }
    if db.main_password.is_empty() {
        db.main_password = random_password();
        println!("[INIT] generated main password: {}", db.main_password);
    }

    let runtime_dns = if let Some(configured) =
        args.dns.as_deref().filter(|value| !value.trim().is_empty())
    {
        normalize_dns(configured)?
    } else if let Some(known_dns) = web_panel::provider_dns_by_id(&db.dns_provider) {
        known_dns
    } else if (deploy_overrides_present && db.dns_provider != "custom") || !db.dns.trim().is_empty()
    {
        web_panel::deploy_dns_profile_or_yandex(&db.dns)
    } else {
        "77.88.8.8,77.88.8.1".to_owned()
    };
    db.dns = runtime_dns.clone();
    db.dns_provider =
        if db.dns_provider == "custom" || web_panel::dns_provider_is_known(&db.dns_provider) {
            db.dns_provider.clone()
        } else {
            web_panel::dns_provider_id(&db.dns)
                .unwrap_or("custom")
                .to_owned()
        };
    if !matches!(db.auto_restart_interval_hours(), 0 | 12 | 24 | 48 | 72) {
        db.set_auto_restart_interval_hours(DEFAULT_AUTO_RESTART_INTERVAL_HOURS);
    }

    let web_pass = if args.web_pass.is_empty() {
        let value = random_password() + &random_password();
        println!("[INIT] generated web password: {value}");
        value
    } else {
        args.web_pass
    };

    save_database(&args.config_dir, &db)?;
    if deploy_overrides_path.exists() {
        std::fs::remove_file(&deploy_overrides_path).with_context(|| {
            format!(
                "remove consumed deploy overrides {}",
                deploy_overrides_path.display()
            )
        })?;
    }

    let web_sessions = DashMap::new();

    let logging_active_val = db.logging_active.unwrap_or(true);
    let (auto_restart_interval_tx, auto_restart_interval_rx) =
        tokio::sync::watch::channel(db.auto_restart_interval_hours());
    let startup_main_password = db.main_password.clone();
    let initial_public_ip = args.public_ip.trim().to_string();
    let public_ip_arc = Arc::new(RwLock::new(initial_public_ip));
    let app = Arc::new(App {
        db_persistence: DatabasePersistence::new(args.config_dir.clone())?,
        db: RwLock::new(db),
        dns: RwLock::new(runtime_dns.clone()),
        startup_main_password,
        config_dir: args.config_dir.clone(),
        listen: args.listen,
        web_port: args.web_port,
        web_user: args.web_user,
        web_pass,
        web_secret_path: args.web_secret_path.trim().trim_matches('/').to_string(),
        public_ip: public_ip_arc.clone(),
        secure_cookie: args.secure_cookie,
        sessions: DashMap::new(),
        password_epochs: DashMap::new(),
        web_sessions,
        login_limits: DashMap::new(),
        web_auth_admission: std::sync::Mutex::new(()),
        bytes_from_client: Arc::new(AtomicU64::new(0)),
        bytes_to_client: Arc::new(AtomicU64::new(0)),
        total_connections: AtomicU64::new(0),
        cpu_percent: AtomicU64::new(0),
        cpu_cores: AtomicU64::new(1),
        started: now(),
        derived_keys: DashMap::new(),
        logs: std::sync::Mutex::new(std::collections::VecDeque::with_capacity(LOG_RING_CAPACITY)),
        logging_active: std::sync::atomic::AtomicBool::new(logging_active_val),
        stream_debug_active: Arc::new(AtomicBool::new(false)),
        log_file_path: args.config_dir.join("csqtt.log"),
        proxy_route: RwLock::new(None),
        proxy_operation: tokio::sync::Mutex::new(()),
        proxy_trigger: tokio::sync::Notify::new(),
        proxy_port_listening: std::sync::atomic::AtomicBool::new(true),
        proxy_health_error: std::sync::RwLock::new(None),
        memory_trim_gate: tokio::sync::Mutex::new(()),
        memory_trim_count: AtomicU64::new(0),
        memory_trim_last_unix: AtomicU64::new(0),
        auto_restart_interval_tx,
        restart_pending: AtomicBool::new(false),
        dataplane: std::sync::OnceLock::new(),
    });

    {
        let app_ip = app.public_ip.clone();
        tokio::spawn(async move {
            if app_ip.read().await.is_empty()
                && let Some(detected) = detect_public_ipv4().await
            {
                *app_ip.write().await = detected;
            }
        });
    }

    {
        let app_dns = app.clone();
        tokio::spawn(async move {
            let configured = protocol::effective_dns(&app_dns).await;
            log_event(
                &app_dns,
                "INFO",
                "DNS",
                &format!("DNS active: [{configured}]"),
            );
        });
    }

    log_event(&app, "INFO", "SYSTEM", " CSQTT Server 2.5.0");
    log_event(
        &app,
        "INFO",
        "SYSTEM",
        &format!(" RTP AEAD: {}", app.listen),
    );
    log_event(&app, "INFO", "SYSTEM", " Tunnel: Userspace TUN (CSQTT)");
    log_event(
        &app,
        "INFO",
        "SYSTEM",
        &format!(" Web: 0.0.0.0:{}", app.web_port),
    );

    let web_app = app.clone();
    let diagnostic_app = app.clone();

    tokio::spawn(async move {
        if let Err(e) = protocol::run_dpi_server().await {
            eprintln!("[DPI] Server listener error: {e}");
        }
    });

    tokio::spawn(async move {
        if let Err(e) = protocol::run_syscalls_server(diagnostic_app).await {
            eprintln!("[SYSCALLS] Server listener error: {e}");
        }
    });

    tokio::spawn(auto_restart_loop(app.clone(), auto_restart_interval_rx));

    let cert_path = app.config_dir.join("web_cert.pem");
    let key_path = app.config_dir.join("web_key.pem");
    if !cert_path.exists() && !key_path.exists() {
        let mut subject_alt_names = vec!["localhost".to_string(), "127.0.0.1".to_string()];
        if let Ok(hostname) = std::fs::read_to_string("/etc/hostname") {
            let hostname = hostname.trim().to_string();
            if !hostname.is_empty() {
                subject_alt_names.push(hostname);
            }
        }
        if let Ok(probe) = std::net::UdpSocket::bind("0.0.0.0:0") {
            let _ = probe.set_nonblocking(true);
            if probe.connect("8.8.8.8:53").is_ok()
                && let Ok(addr) = probe.local_addr()
                && !addr.ip().is_loopback()
            {
                subject_alt_names.push(addr.ip().to_string());
            }
        }
        if let Ok(cert) = rcgen::generate_simple_self_signed(subject_alt_names) {
            let cert_pem = cert.cert.pem();
            let key_pem = cert.key_pair.serialize_pem();
            let _ = tokio::fs::write(&cert_path, cert_pem).await;
            let _ = tokio::fs::write(&key_path, key_pem).await;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ =
                    tokio::fs::set_permissions(&key_path, std::fs::Permissions::from_mode(0o600))
                        .await;
            }
        }
    } else if !cert_path.exists() || !key_path.exists() {
        anyhow::bail!(
            "incomplete WEB TLS certificate pair in {}; restore both web_cert.pem and web_key.pem",
            app.config_dir.display()
        );
    }

    let tls_config = axum_server::tls_rustls::RustlsConfig::from_pem_file(&cert_path, &key_path)
        .await
        .context("load web cert")?;

    #[cfg(unix)]
    tokio::spawn(web_tls_reload_loop(
        tls_config.clone(),
        cert_path.clone(),
        key_path.clone(),
    ));

    let protocol_runtime = protocol::start(app.clone()).await?;
    let mut protocol_status = protocol_runtime.status_receiver();
    let mut web_task = tokio::spawn(async move { web_panel::run(web_app, tls_config).await });
    tokio::spawn(protocol::session_janitor(app.clone()));
    tokio::spawn(protocol::password_janitor(app.clone()));
    tokio::spawn(password_epoch_sweeper(app.clone()));

    tokio::spawn(cpu_loop(app.clone()));

    let monitor_app = app.clone();
    tokio::spawn(local_proxy_monitor_loop(monitor_app));

    let mut web_completed = false;
    let mut terminal_error = None;
    tokio::select! {
        _ = shutdown_signal() => {}
        result = &mut web_task => {
            web_completed = true;
            terminal_error = Some(match result {
                Ok(Ok(())) => anyhow::anyhow!("web server stopped unexpectedly"),
                Ok(Err(error)) => anyhow::anyhow!("web server failed: {error:#}"),
                Err(error) => anyhow::anyhow!("web server task failed: {error}"),
            });
        }
        changed = protocol_status.changed() => {
            terminal_error = Some(match changed {
                Ok(()) => protocol_status
                    .borrow()
                    .clone()
                    .map(anyhow::Error::msg)
                    .unwrap_or_else(|| anyhow::anyhow!("tokio dataplane stopped unexpectedly")),
                Err(_) => anyhow::anyhow!("tokio dataplane status channel closed"),
            });
        }
    }

    if !web_completed {
        web_task.abort();
        let _ = web_task.await;
    }
    if let Err(error) = protocol_runtime.shutdown().await
        && terminal_error.is_none()
    {
        terminal_error = Some(error);
    }

    log_event(&app, "INFO", "SHUTDOWN", "Stopping server...");

    let proxy_cleanup = async {
        let _proxy_operation = app.proxy_operation.lock().await;
        let proxy_route = app.proxy_route.write().await.take();
        if let Some(route) = proxy_route {
            route.deactivate().await;
        }
    };
    if tokio::time::timeout(Duration::from_secs(6), proxy_cleanup)
        .await
        .is_err()
    {
        eprintln!("[PROXY] shutdown cleanup timed out");
    }

    protocol::flush_traffic(&app).await;
    let final_revision = {
        let db = app.db.read().await;
        app.db_persistence.submit(db.clone())
    };
    match tokio::time::timeout(
        Duration::from_secs(1),
        app.db_persistence.wait(final_revision),
    )
    .await
    {
        Ok(Err(error)) => eprintln!("[DB] final save: {error:#}"),
        Err(_) => eprintln!("[DB] final save timed out"),
        Ok(Ok(())) => {}
    }

    protocol::drop_all_sessions(&app);
    match terminal_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

async fn run_tproxy_child(
    port: Option<u16>,
    status_path: Option<std::path::PathBuf>,
) -> Result<()> {
    let port = port.context("TPROXY child port is missing")?;
    let status_path = status_path.context("TPROXY child status path is missing")?;
    let mut payload = String::new();
    std::io::stdin()
        .read_to_string(&mut payload)
        .context("read TPROXY child bootstrap")?;
    let profile: model::LocalProxyProfile =
        serde_json::from_str(&payload).context("decode TPROXY child bootstrap")?;
    proxy_route::validate_config(&profile)?;
    arm_tproxy_child_parent_death()?;
    let sockets =
        tproxy::bind_sockets(port).with_context(|| format!("bind TPROXY child {port}"))?;
    let cancel = tokio_util::sync::CancellationToken::new();
    let child_log: proxy_route::LogFn = Arc::new(|level: &str, message: &str| {
        eprintln!("[TPROXY] [{level}] {message}");
    });
    let stats = Arc::new(tproxy::TproxyStats::default());
    let quotas = tproxy::DeviceQuotaRegistry::new();
    let mut status = tokio::spawn(serve_tproxy_status(
        status_path,
        cancel.clone(),
        stats.clone(),
    ));
    let engine = tproxy::run(
        sockets,
        Arc::new(profile),
        cancel.clone(),
        child_log,
        stats,
        quotas,
    );
    tokio::pin!(engine);
    let result = tokio::select! {
        result = &mut engine => {
            cancel.cancel();
            let _ = (&mut status).await;
            result
        },
        result = &mut status => {
            cancel.cancel();
            let _ = engine.await;
            result.context("TPROXY child status worker")??;
            bail!("TPROXY child status worker stopped")
        },
        _ = shutdown_signal() => {
            cancel.cancel();
            let result = engine.await;
            let _ = (&mut status).await;
            result
        }
    };
    let (tcp_sessions, udp_flows, udp_datagrams) = result;
    eprintln!(
        "[TPROXY] child exited ({tcp_sessions} TCP sessions, {udp_flows} UDP flows, {udp_datagrams} UDP datagrams served)"
    );
    Ok(())
}

#[cfg(unix)]
async fn serve_tproxy_status(
    path: std::path::PathBuf,
    cancel: tokio_util::sync::CancellationToken,
    stats: Arc<tproxy::TproxyStats>,
) -> Result<()> {
    let _ = std::fs::remove_file(&path);
    let listener = tokio::net::UnixListener::bind(&path).context("bind TPROXY status socket")?;
    loop {
        tokio::select! {
            _ = cancel.cancelled() => break,
            accepted = listener.accept() => {
                let Ok((mut stream, _)) = accepted else {
                    continue;
                };
                let mut request = [0u8; 1];
                let received = tokio::time::timeout(Duration::from_millis(100), stream.read_exact(&mut request)).await;
                if !matches!(received, Ok(Ok(_))) || request[0] != 0x53 {
                    continue;
                }
                let Ok(payload) = serde_json::to_vec(&stats.snapshot()) else {
                    continue;
                };
                let _ = tokio::time::timeout(Duration::from_millis(100), stream.write_all(&payload)).await;
            }
        }
    }
    let _ = std::fs::remove_file(path);
    Ok(())
}

#[cfg(not(unix))]
async fn serve_tproxy_status(
    _path: std::path::PathBuf,
    _cancel: tokio_util::sync::CancellationToken,
    _stats: Arc<tproxy::TproxyStats>,
) -> Result<()> {
    bail!("TPROXY status socket is supported only on Unix")
}

#[cfg(target_os = "linux")]
fn arm_tproxy_child_parent_death() -> Result<()> {
    let result = unsafe { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM) };
    if result != 0 {
        return Err(std::io::Error::last_os_error()).context("arm TPROXY parent-death signal");
    }
    let docker_mode = std::env::var("CSQTT_SERVICE_MANAGER").is_ok_and(|value| value == "docker");
    if tproxy_parent_exited_during_startup(unsafe { libc::getppid() }, docker_mode) {
        bail!("TPROXY parent exited before child startup");
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn tproxy_parent_exited_during_startup(parent_pid: libc::pid_t, docker_mode: bool) -> bool {
    parent_pid == 1 && !docker_mode
}

#[cfg(not(target_os = "linux"))]
fn arm_tproxy_child_parent_death() -> Result<()> {
    Ok(())
}

#[cfg(target_os = "linux")]
unsafe fn libc_geteuid() -> u32 {
    unsafe extern "C" {
        fn geteuid() -> u32;
    }
    unsafe { geteuid() }
}

#[cfg(not(target_os = "linux"))]
unsafe fn libc_geteuid() -> u32 {
    0
}

async fn local_proxy_monitor_loop(app: Arc<App>) {
    const PORT_CHECK: Duration = Duration::from_secs(3);
    const STRIKES_BEFORE_PAUSE: u32 = 5;
    const PAUSE_SCHEDULE: [u64; 4] = [30, 90, 120, 120];

    let mut port_failures: u32 = 0;
    let mut pause_round: u32 = 0;
    let mut wait = Some(Duration::from_millis(200));

    loop {
        if let Some(delay) = wait {
            tokio::select! {
                _ = tokio::time::sleep(delay) => {}
                _ = app.proxy_trigger.notified() => {
                    port_failures = 0;
                    pause_round = 0;
                }
            }
        } else {
            app.proxy_trigger.notified().await;
            port_failures = 0;
            pause_round = 0;
        }

        let _operation = app.proxy_operation.lock().await;
        let profile = {
            let db = app.db.read().await;
            db.local_proxy.active_profile().cloned()
        };

        let Some(profile) = profile else {
            *write_unpoison(&app.proxy_health_error) = None;
            let old = app.proxy_route.write().await.take();
            if let Some(route) = old {
                route.deactivate().await;
                log_event(
                    &app,
                    "INFO",
                    "PROXY",
                    "Local SOCKS5 routing disabled; direct VPS route restored",
                );
                schedule_proxy_shutdown_compaction(&app);
            }
            port_failures = 0;
            pause_round = 0;
            wait = None;
            continue;
        };

        let current = {
            let guard = app.proxy_route.read().await;
            guard.clone()
        };
        if let Some(route) = current {
            if route.is_alive() && route.matches(&profile) {
                if !proxy_route::port_is_listening(profile.port).await {
                    *write_unpoison(&app.proxy_health_error) =
                        Some(format!("Порт {} не отвечает", profile.port));
                    app.proxy_port_listening
                        .store(false, std::sync::atomic::Ordering::Release);
                    let failed = app.proxy_route.write().await.take();
                    if let Some(failed) = failed {
                        failed.deactivate().await;
                    }
                    log_event(
                        &app,
                        "WARNING",
                        "PROXY",
                        &format!(
                            "SOCKS5 port {} is no longer listening; clients switched to the direct route",
                            profile.port
                        ),
                    );
                    schedule_proxy_shutdown_compaction(&app);
                    port_failures = 1;
                    pause_round = 0;
                    wait = Some(PORT_CHECK);
                } else {
                    app.proxy_port_listening
                        .store(true, std::sync::atomic::Ordering::Release);
                    *write_unpoison(&app.proxy_health_error) = None;
                    port_failures = 0;
                    pause_round = 0;
                    wait = Some(PORT_CHECK);
                }
                continue;
            }

            let stale = app.proxy_route.write().await.take();
            if let Some(stale) = stale {
                stale.deactivate().await;
                schedule_proxy_shutdown_compaction(&app);
            }
            wait = Some(Duration::from_millis(100));
            continue;
        }

        let listening = proxy_route::port_is_listening(profile.port).await;
        app.proxy_port_listening
            .store(listening, std::sync::atomic::Ordering::Release);
        if !listening {
            *write_unpoison(&app.proxy_health_error) =
                Some(format!("Порт {} не отвечает", profile.port));
            if port_failures == 0 {
                log_event(
                    &app,
                    "WARNING",
                    "PROXY",
                    &format!(
                        "SOCKS5 port {} is not listening; traffic goes direct, port re-check every 3s",
                        profile.port
                    ),
                );
            }
            port_failures = port_failures.saturating_add(1);
            if port_failures >= STRIKES_BEFORE_PAUSE {
                port_failures = 0;
                let pause = PAUSE_SCHEDULE[(pause_round as usize).min(PAUSE_SCHEDULE.len() - 1)];
                pause_round = pause_round.saturating_add(1);
                wait = Some(Duration::from_secs(pause));
            } else {
                wait = Some(PORT_CHECK);
            }
            continue;
        }

        port_failures = 0;
        pause_round = 0;
        log_event(
            &app,
            "INFO",
            "PROXY",
            &format!(
                "Connecting SOCKS5 forwarder to local SOCKS5 127.0.0.1:{}...",
                profile.port
            ),
        );
        let proxy_log_app = app.clone();
        let proxy_log: proxy_route::LogFn = Arc::new(move |level: &str, msg: &str| {
            log_event(&proxy_log_app, level, "PROXY", msg);
        });
        match proxy_route::ProxyRoute::connect(&profile, proxy_log).await {
            Ok(route) => {
                *write_unpoison(&app.proxy_health_error) = None;
                app.proxy_route.write().await.replace(route);
                log_event(
                    &app,
                    "INFO",
                    "PROXY",
                    &format!(
                        "Local SOCKS5 route 127.0.0.1:{} is active (TCP and UDP)",
                        profile.port
                    ),
                );
                wait = Some(PORT_CHECK);
            }
            Err(error) => {
                *write_unpoison(&app.proxy_health_error) = Some(format!("{error:#}"));
                log_event(
                    &app,
                    "ERROR",
                    "PROXY",
                    &format!(
                        "Local SOCKS5 is not ready: {error:#}. Direct VPS route is active; retry in 3s"
                    ),
                );
                wait = Some(PORT_CHECK);
            }
        }
    }
}

#[cfg(test)]
mod lock_tests {
    use super::{
        CpuSnapshot, LOG_TRUNCATION_SUFFIX, MAX_LOG_RECORD_BYTES, cpu_percentage,
        format_log_record, normalize_dns, parse_host_cpu, parse_process_cpu,
    };

    #[cfg(target_os = "linux")]
    #[test]
    fn docker_tproxy_child_accepts_a_healthy_pid_one_parent() {
        assert!(super::tproxy_parent_exited_during_startup(1, false));
        assert!(!super::tproxy_parent_exited_during_startup(1, true));
        assert!(!super::tproxy_parent_exited_during_startup(42, false));
    }

    #[test]
    fn log_record_is_utf8_safe_and_byte_bounded() {
        let record =
            format_log_record("22 Aug 26 12:00", "INFO", &"\u{0451}\u{0436}".repeat(2_000));

        assert!(record.len() <= MAX_LOG_RECORD_BYTES);
        assert!(std::str::from_utf8(record.as_bytes()).is_ok());
        assert!(record.ends_with(LOG_TRUNCATION_SUFFIX));
    }

    #[test]
    fn log_record_caps_an_oversized_level() {
        let record = format_log_record("22 Aug 26 12:00", &"\u{041b}".repeat(2_000), "ok");

        assert!(record.len() <= MAX_LOG_RECORD_BYTES);
        assert!(std::str::from_utf8(record.as_bytes()).is_ok());
    }

    #[test]
    fn dns_override_accepts_one_or_two_ipv4_addresses() {
        assert_eq!(normalize_dns("8.8.8.8").unwrap(), "8.8.8.8");
        assert_eq!(
            normalize_dns(" 8.8.8.8, 8.8.4.4 ").unwrap(),
            "8.8.8.8,8.8.4.4"
        );
    }

    #[test]
    fn dns_override_rejects_invalid_or_excess_addresses() {
        assert!(normalize_dns("").is_err());
        assert!(normalize_dns("example.org").is_err());
        assert!(normalize_dns("1.1.1.1,1.0.0.1,8.8.8.8").is_err());
    }

    #[test]
    fn poisoned_mutex_recovers_without_process_failure() {
        let mutex = std::sync::Arc::new(std::sync::Mutex::new(1u64));
        let poisoned = mutex.clone();
        let _ = std::thread::spawn(move || {
            let _guard = poisoned
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            panic!("poison");
        })
        .join();
        *super::lock_unpoison(&mutex) = 2;
        assert_eq!(*super::lock_unpoison(&mutex), 2);
    }

    #[test]
    fn parses_linux_cpu_counters_and_process_name_with_spaces() {
        let host = "cpu  100 2 30 400 10 0 0 0 0 0\ncpu0 50 1 15 200\ncpu1 50 1 15 200\n";
        assert_eq!(parse_host_cpu(host), Some((542, 2)));
        let process = "77 (csqtt worker) R 1 2 3 4 5 6 7 8 9 10 11 12 13 14";
        assert_eq!(parse_process_cpu(process), Some(23));
    }

    #[test]
    fn calculates_process_cpu_in_one_core_percent() {
        let previous = CpuSnapshot {
            total: 2_000,
            process: 100,
            cores: 4,
        };
        let current = CpuSnapshot {
            total: 2_100,
            process: 120,
            cores: 4,
        };
        assert_eq!(cpu_percentage(previous, current), Some(80));
    }
}
