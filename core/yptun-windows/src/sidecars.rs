use std::io::{BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use yptun_core::desktop_engine::CsqttUp;
use yptun_core::desktop_vpn_runtime::LogJournal;

type LineHandler = Arc<dyn Fn(String) + Send + Sync>;

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|error| error.into_inner())
}

fn journal_line(journal: &LogJournal, prefix: &str, line: &str) {
    let line = line.trim_end();
    if line.trim().is_empty() {
        return;
    }
    let stamp = yptun_core::desktop_app::format_timestamp(yptun_core::desktop_app::now_ms());
    journal.add(&format!("{stamp} {prefix}: {line}"));
}

fn pump<R: Read + Send + 'static>(name: String, stream: Option<R>, handler: LineHandler) {
    let Some(stream) = stream else { return };
    let _ = thread::Builder::new().name(name).spawn(move || {
        for line in BufReader::new(stream).lines().map_while(Result::ok) {
            handler(line);
        }
    });
}

struct RunningChild {
    child: Child,
    stdin: Option<ChildStdin>,
    _job: Option<ChildJob>,
}

#[derive(Default)]
struct ProcessState {
    running: Option<RunningChild>,
    last_exit: Option<i32>,
}

/// A subprocess slot with a retained stdin pipe. Sidecars that opt into "exit on stdin EOF" are
/// therefore also cleaned up when the WinUI process crashes and Windows closes its pipe handles.
#[derive(Clone, Default)]
pub(crate) struct ProcessSlot {
    state: Arc<Mutex<ProcessState>>,
}

impl ProcessSlot {
    pub(crate) fn start(
        &self,
        command: &[String],
        working_dir: Option<&Path>,
        environment: &[(String, String)],
        prefix: &str,
        journal: Arc<LogJournal>,
        stdout_handler: Option<LineHandler>,
    ) -> Result<(), String> {
        if command.is_empty() {
            return Err("empty sidecar command".into());
        }
        self.stop(None, Duration::from_secs(2), Duration::from_secs(2));

        let mut builder = Command::new(&command[0]);
        builder
            .args(&command[1..])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(directory) = working_dir {
            builder.current_dir(directory);
        }
        for (name, value) in environment {
            builder.env(name, value);
        }
        hide_console(&mut builder);
        let mut child = builder
            .spawn()
            .map_err(|error| format!("failed to start {}: {error}", command[0]))?;
        let job = match ChildJob::assign(&child) {
            Ok(job) => Some(job),
            Err(error) => {
                journal_line(
                    &journal,
                    prefix,
                    &format!("warning: child crash cleanup is unavailable: {error}"),
                );
                None
            }
        };
        let stdin = child.stdin.take();
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();

        let default_handler: LineHandler = {
            let journal = journal.clone();
            let prefix = prefix.to_string();
            Arc::new(move |line| journal_line(&journal, &prefix, &line))
        };
        pump(
            format!("{prefix}-stdout"),
            stdout,
            stdout_handler.unwrap_or_else(|| default_handler.clone()),
        );
        pump(format!("{prefix}-stderr"), stderr, default_handler);

        let mut state = lock(&self.state);
        state.last_exit = None;
        state.running = Some(RunningChild {
            child,
            stdin,
            _job: job,
        });
        Ok(())
    }

    pub(crate) fn send_line(&self, line: &str) -> Result<(), String> {
        let mut state = lock(&self.state);
        let Some(running) = state.running.as_mut() else {
            return Err("sidecar is not running".into());
        };
        let Some(stdin) = running.stdin.as_mut() else {
            return Err("sidecar stdin is unavailable".into());
        };
        stdin
            .write_all(format!("{line}\n").as_bytes())
            .and_then(|_| stdin.flush())
            .map_err(|error| error.to_string())
    }

    pub(crate) fn is_running(&self) -> bool {
        let mut state = lock(&self.state);
        refresh(&mut state);
        state.running.is_some()
    }

    pub(crate) fn exit_description(&self) -> String {
        let mut state = lock(&self.state);
        refresh(&mut state);
        if state.running.is_some() {
            "still starting".into()
        } else if let Some(code) = state.last_exit {
            format!("exited with {code}")
        } else {
            "not started".into()
        }
    }

    pub(crate) fn stop(&self, control_line: Option<&str>, graceful: Duration, force: Duration) {
        let (mut running, previous_exit) = {
            let mut state = lock(&self.state);
            (state.running.take(), state.last_exit)
        };
        let Some(mut running) = running.take() else {
            return;
        };

        if let (Some(line), Some(stdin)) = (control_line, running.stdin.as_mut()) {
            let _ = stdin.write_all(format!("{line}\n").as_bytes());
            let _ = stdin.flush();
        }
        // Closing stdin is the graceful stop signal used by OpenFlux/snolc and guarantees that a
        // child cannot inherit a live pipe after the host is gone.
        drop(running.stdin.take());

        let status = wait_for_exit(&mut running.child, graceful).or_else(|| {
            kill_process_tree(running.child.id());
            let _ = running.child.kill();
            wait_for_exit(&mut running.child, force)
        });
        let mut state = lock(&self.state);
        state.last_exit = status.and_then(|value| value.code()).or(previous_exit);
    }
}

fn refresh(state: &mut ProcessState) {
    let status = state
        .running
        .as_mut()
        .and_then(|running| running.child.try_wait().ok().flatten());
    if let Some(status) = status {
        state.last_exit = status.code();
        state.running = None;
    }
}

fn wait_for_exit(child: &mut Child, timeout: Duration) -> Option<ExitStatus> {
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(40)),
            _ => return None,
        }
    }
}

/// Every sidecar is assigned to a kill-on-close Job Object. If WinUI is terminated or crashes,
/// Windows closes the host's job handle and tears down the sidecar plus any children it spawned.
#[cfg(windows)]
pub(crate) struct ChildJob(*mut std::ffi::c_void);

#[cfg(windows)]
unsafe impl Send for ChildJob {}

#[cfg(windows)]
impl ChildJob {
    pub(crate) fn assign(child: &Child) -> Result<Self, String> {
        use std::mem::{size_of, zeroed};
        use std::os::windows::io::AsRawHandle;

        let handle = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if handle.is_null() {
            return Err(std::io::Error::last_os_error().to_string());
        }
        let mut information: JobObjectExtendedLimitInformation = unsafe { zeroed() };
        information.basic_limit_information.limit_flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let configured = unsafe {
            SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                &information as *const _ as *const std::ffi::c_void,
                size_of::<JobObjectExtendedLimitInformation>() as u32,
            )
        };
        if configured == 0 {
            let error = std::io::Error::last_os_error().to_string();
            unsafe { CloseHandle(handle) };
            return Err(error);
        }
        let assigned = unsafe {
            AssignProcessToJobObject(handle, child.as_raw_handle() as *mut std::ffi::c_void)
        };
        if assigned == 0 {
            let error = std::io::Error::last_os_error().to_string();
            unsafe { CloseHandle(handle) };
            return Err(error);
        }
        Ok(Self(handle))
    }
}

#[cfg(windows)]
impl Drop for ChildJob {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}

#[cfg(windows)]
const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: i32 = 9;
#[cfg(windows)]
const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x0000_2000;

#[cfg(windows)]
#[repr(C)]
struct JobObjectBasicLimitInformation {
    per_process_user_time_limit: i64,
    per_job_user_time_limit: i64,
    limit_flags: u32,
    minimum_working_set_size: usize,
    maximum_working_set_size: usize,
    active_process_limit: u32,
    affinity: usize,
    priority_class: u32,
    scheduling_class: u32,
}

#[cfg(windows)]
#[repr(C)]
struct IoCounters {
    read_operation_count: u64,
    write_operation_count: u64,
    other_operation_count: u64,
    read_transfer_count: u64,
    write_transfer_count: u64,
    other_transfer_count: u64,
}

#[cfg(windows)]
#[repr(C)]
struct JobObjectExtendedLimitInformation {
    basic_limit_information: JobObjectBasicLimitInformation,
    io_info: IoCounters,
    process_memory_limit: usize,
    job_memory_limit: usize,
    peak_process_memory_used: usize,
    peak_job_memory_used: usize,
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateJobObjectW(
        job_attributes: *const std::ffi::c_void,
        name: *const u16,
    ) -> *mut std::ffi::c_void;
    fn SetInformationJobObject(
        job: *mut std::ffi::c_void,
        information_class: i32,
        information: *const std::ffi::c_void,
        information_length: u32,
    ) -> i32;
    fn AssignProcessToJobObject(job: *mut std::ffi::c_void, process: *mut std::ffi::c_void) -> i32;
    fn CloseHandle(handle: *mut std::ffi::c_void) -> i32;
}

#[cfg(not(windows))]
pub(crate) struct ChildJob;

#[cfg(not(windows))]
impl ChildJob {
    pub(crate) fn assign(_child: &Child) -> Result<Self, String> {
        Ok(Self)
    }
}

#[cfg(windows)]
fn kill_process_tree(pid: u32) {
    let mut command = Command::new("taskkill.exe");
    command.args(["/PID", &pid.to_string(), "/T", "/F"]);
    hide_console(&mut command);
    let _ = command.output();
}

#[cfg(not(windows))]
fn kill_process_tree(_pid: u32) {}

#[cfg(windows)]
fn hide_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_console(_command: &mut Command) {}

#[derive(Default)]
struct CsqttProtocolState {
    ready: Option<CsqttUp>,
    done: bool,
    last_error: String,
    active_streams: i32,
    bytes_up: u64,
    bytes_down: u64,
}

#[derive(Default)]
struct CsqttProtocol {
    state: Mutex<CsqttProtocolState>,
    changed: Condvar,
}

/// Line-protocol driver for `csqtthost`.
#[derive(Clone, Default)]
pub(crate) struct CsqttSidecar {
    process: ProcessSlot,
    protocol: Arc<CsqttProtocol>,
}

impl CsqttSidecar {
    pub(crate) fn launch(
        &self,
        executable: &Path,
        options_json: &str,
        journal: Arc<LogJournal>,
    ) -> Result<(), String> {
        self.stop();
        {
            let mut state = lock(&self.protocol.state);
            *state = CsqttProtocolState::default();
        }
        let protocol = self.protocol.clone();
        let journal_for_lines = journal.clone();
        let handler: LineHandler =
            Arc::new(move |line| handle_csqtt_line(&protocol, &journal_for_lines, &line));
        let command = vec![executable.to_string_lossy().into_owned()];
        self.process.start(
            &command,
            executable.parent(),
            &[],
            "csqtt",
            journal,
            Some(handler),
        )?;
        if let Err(error) = self.process.send_line(&format!("OPTS {options_json}")) {
            self.process
                .stop(None, Duration::from_millis(200), Duration::from_secs(1));
            return Err(format!("csqtt options write failed: {error}"));
        }
        Ok(())
    }

    pub(crate) fn await_ready(&self, timeout: Duration) -> Option<CsqttUp> {
        let deadline = Instant::now() + timeout;
        let mut state = lock(&self.protocol.state);
        loop {
            if let Some(ready) = state.ready.clone() {
                return Some(ready);
            }
            if state.done || !self.process.is_running() {
                state.done = true;
                if state.last_error.is_empty() {
                    state.last_error = self.process.exit_description();
                }
                return None;
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let wait = deadline
                .saturating_duration_since(now)
                .min(Duration::from_millis(250));
            let (next, _) = self
                .protocol
                .changed
                .wait_timeout(state, wait)
                .unwrap_or_else(|error| error.into_inner());
            state = next;
        }
    }

    pub(crate) fn is_running(&self) -> bool {
        self.process.is_running()
    }

    pub(crate) fn no_tunnel_message(&self) -> String {
        let last_error = lock(&self.protocol.state).last_error.clone();
        let base = "csqtt: нет адреса туннеля от сервера — проверь IP/порт/пароль сервера и ссылки на звонки VK";
        let hint = if last_error.contains("handshake_timeout") {
            ". Сервер не ответил вовсе (TURN при этом поднялся). Проверь: пароль этого профиля активен и не истёк в панели; пароль с рабочим звонком + этот хеш, и наоборот — так видно, виноват пароль или звонок; на сервере `journalctl -u csqtt | grep -i rejected` (\"no active credential\" = пароль не принят)"
        } else {
            ""
        };
        if last_error.trim().is_empty() {
            format!("{base}{hint}")
        } else {
            format!("{base}{hint} — {last_error}")
        }
    }

    pub(crate) fn stop(&self) {
        self.process
            .stop(Some("STOP"), Duration::from_secs(4), Duration::from_secs(2));
        let mut state = lock(&self.protocol.state);
        state.done = true;
        self.protocol.changed.notify_all();
    }
}

fn csqtt_log(journal: &LogJournal, text: &str) {
    journal_line(journal, "csqtt", text);
}

fn handle_csqtt_line(protocol: &CsqttProtocol, journal: &LogJournal, line: &str) {
    if let Some(text) = line.strip_prefix("LOG ") {
        csqtt_log(journal, text);
        return;
    }
    if let Some(text) = line.strip_prefix("READY ") {
        if let Some(ready) = parse_csqtt_ready(text) {
            let mut state = lock(&protocol.state);
            state.ready = Some(ready);
            protocol.changed.notify_all();
        }
        return;
    }
    if let Some(text) = line.strip_prefix("STATS ") {
        let mut parts = text.split_whitespace();
        let mut state = lock(&protocol.state);
        state.active_streams = parts
            .next()
            .and_then(|v| v.parse().ok())
            .unwrap_or(state.active_streams);
        state.bytes_up = parts
            .next()
            .and_then(|v| v.parse().ok())
            .unwrap_or(state.bytes_up);
        state.bytes_down = parts
            .next()
            .and_then(|v| v.parse().ok())
            .unwrap_or(state.bytes_down);
        return;
    }
    if let Some(text) = line.strip_prefix("ERROR ") {
        let mut parts = text.splitn(3, ' ');
        let fatal = parts.next() == Some("1");
        let code = parts.next().unwrap_or_default();
        let message = parts.next().unwrap_or_default();
        csqtt_log(
            journal,
            &format!(
                "ошибка{}: {}{}{}",
                if fatal {
                    " (критическая)"
                } else {
                    ""
                },
                code,
                if code.is_empty() || message.is_empty() {
                    ""
                } else {
                    ": "
                },
                message
            ),
        );
        if fatal {
            let mut state = lock(&protocol.state);
            state.last_error = if message.is_empty() {
                code.to_string()
            } else {
                format!("{code}: {message}")
            };
            state.done = true;
            protocol.changed.notify_all();
        }
        return;
    }
    if line.trim() == "STOPPED" {
        let mut state = lock(&protocol.state);
        state.done = true;
        protocol.changed.notify_all();
        return;
    }
    csqtt_log(journal, line);
}

fn parse_csqtt_ready(text: &str) -> Option<CsqttUp> {
    let parts: Vec<&str> = text.trim().split('|').collect();
    if parts.len() != 3 || parts[0].trim().is_empty() {
        return None;
    }
    Some(CsqttUp {
        ip: parts[0].trim().to_string(),
        dns: parts[1]
            .split(',')
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string)
            .collect(),
        mtu: parts[2].trim().parse().unwrap_or(1300),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_csqtt_ready_line() {
        assert_eq!(
            parse_csqtt_ready("10.0.0.2|1.1.1.1,8.8.8.8|1280"),
            Some(CsqttUp {
                ip: "10.0.0.2".into(),
                dns: vec!["1.1.1.1".into(), "8.8.8.8".into()],
                mtu: 1280,
            })
        );
    }

    #[test]
    fn fatal_csqtt_error_finishes_readiness_wait() {
        let protocol = CsqttProtocol::default();
        let journal = LogJournal::new();
        handle_csqtt_line(
            &protocol,
            &journal,
            "ERROR 1 handshake_timeout server did not answer",
        );
        let state = lock(&protocol.state);
        assert!(state.done);
        assert_eq!(state.last_error, "handshake_timeout: server did not answer");
    }
}
