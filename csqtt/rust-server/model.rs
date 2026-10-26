use anyhow::{Context, Result, bail};
use base64::Engine;
use hkdf::Hkdf;
use rand::{RngCore, rngs::OsRng};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Condvar, LazyLock, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;

pub const PASSWORD_LEN: usize = 16;
pub const PASS_CHARS: &[u8] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
pub const DEFAULT_AUTO_RESTART_INTERVAL_HOURS: u8 = 0;
static DATABASE_SAVE_LOCK: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ClientGroup {
    pub id: String,
    pub name: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub routing_rule_id: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub admin_uuid: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub sub_uuid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workers: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vk_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vk_hashes: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct RoutingRule {
    pub id: String,
    pub name: String,
    pub is_whitelist: bool,
    pub packages: Vec<String>,
    pub template_domains: bool,
    pub direct_domains: Vec<String>,
    pub domains_is_whitelist: bool,
    pub ip_is_whitelist: bool,
    pub ips: Vec<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct PasswordEntry {
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub ip: String,
    pub expires_at: i64,
    pub down_bytes: i64,
    pub up_bytes: i64,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub vk_hash: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub ports: String,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub is_deactivated: bool,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub name: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub vk_hashes: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub vk_token_enabled: bool,
    pub dtls_port: u16,
    pub wg_port: u16,
    pub local_port: u16,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub group_id: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub routing_rule_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub sub_uuid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workers: Option<u32>,
}

pub const DEFAULT_LOCAL_PROXY_PORT: u16 = 45000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalProxyProfile {
    pub id: String,
    pub name: String,
    pub port: u16,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub username: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub password: String,
}

impl LocalProxyProfile {
    pub fn new_id() -> String {
        use rand::RngCore;
        let mut bytes = [0u8; 6];
        rand::rngs::OsRng.fill_bytes(&mut bytes);
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
    }
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct LocalProxyState {
    pub active_profile_id: String,
    pub profiles: Vec<LocalProxyProfile>,
}

impl LocalProxyState {
    pub fn normalize(&mut self) {
        for profile in &mut self.profiles {
            if profile.port == 0 {
                profile.port = DEFAULT_LOCAL_PROXY_PORT;
            }
        }
        if !self.active_profile_id.is_empty()
            && !self.profiles.iter().any(|p| p.id == self.active_profile_id)
        {
            self.active_profile_id.clear();
        }
    }

    pub fn active_profile(&self) -> Option<&LocalProxyProfile> {
        if self.active_profile_id.is_empty() {
            return None;
        }
        self.profiles
            .iter()
            .find(|p| p.id == self.active_profile_id)
    }

    pub fn find_profile(&self, id: &str) -> Option<&LocalProxyProfile> {
        self.profiles.iter().find(|p| p.id == id)
    }

    pub fn find_profile_mut(&mut self, id: &str) -> Option<&mut LocalProxyProfile> {
        self.profiles.iter_mut().find(|p| p.id == id)
    }

    pub fn remove_profile(&mut self, id: &str) -> bool {
        let before = self.profiles.len();
        self.profiles.retain(|p| p.id != id);
        if self.active_profile_id == id {
            self.active_profile_id.clear();
        }
        self.profiles.len() < before
    }
}

impl<'de> serde::Deserialize<'de> for LocalProxyState {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = serde_json::Value::deserialize(deserializer)?;
        if let Some(profiles) = value.get("profiles") {
            let active_profile_id = value
                .get("active_profile_id")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_owned();
            let profiles: Vec<LocalProxyProfile> =
                serde_json::from_value(profiles.clone()).unwrap_or_default();
            Ok(Self {
                active_profile_id,
                profiles,
            })
        } else if value.is_object() && value.get("port").is_some() {
            let enabled = value
                .get("enabled")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let port = value
                .get("port")
                .and_then(|v| v.as_u64())
                .unwrap_or(DEFAULT_LOCAL_PROXY_PORT as u64) as u16;
            let username = value
                .get("username")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_owned();
            let password = value
                .get("password")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_owned();
            let id = LocalProxyProfile::new_id();
            let profile = LocalProxyProfile {
                id: id.clone(),
                name: format!("SOCKS5 :{port}"),
                port: if port == 0 {
                    DEFAULT_LOCAL_PROXY_PORT
                } else {
                    port
                },
                username,
                password,
            };
            Ok(Self {
                active_profile_id: if enabled { id } else { String::new() },
                profiles: vec![profile],
            })
        } else {
            Ok(Self::default())
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Database {
    pub main_password: String,
    pub dns: String,
    #[serde(default)]
    pub dns_provider: String,
    #[serde(default)]
    pub auto_restart_interval_hours: Option<u8>,
    pub main_up_bytes: i64,
    pub main_down_bytes: i64,
    pub admin_id: String,
    pub bot_token: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub vk_access_token: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub vk_hashes: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub main_sub_uuid: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub main_vk_hashes: String,
    #[serde(default)]
    pub main_vk_token_enabled: bool,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub main_routing_rule_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub main_workers: Option<u32>,
    #[serde(default, alias = "slots")]
    pub passwords: BTreeMap<String, PasswordEntry>,
    pub web_sessions: BTreeMap<String, i64>,
    pub logging_active: Option<bool>,
    #[serde(default)]
    pub local_proxy: LocalProxyState,
    #[serde(default)]
    pub groups: BTreeMap<String, ClientGroup>,
    #[serde(default)]
    pub routing_rules: BTreeMap<String, RoutingRule>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TrafficCounters {
    pub up_bytes: i64,
    pub down_bytes: i64,
}

#[derive(Debug, Default)]
pub struct TrafficSnapshot {
    pub main: Option<TrafficCounters>,
    pub passwords: BTreeMap<String, TrafficCounters>,
}

impl TrafficSnapshot {
    fn merge_from(&mut self, mut newer: Self) {
        if newer.main.is_some() {
            self.main = newer.main;
        }
        self.passwords.append(&mut newer.passwords);
    }

    fn is_empty(&self) -> bool {
        self.main.is_none() && self.passwords.is_empty()
    }
}

impl Database {
    pub fn auto_restart_interval_hours(&self) -> u8 {
        self.auto_restart_interval_hours
            .unwrap_or(DEFAULT_AUTO_RESTART_INTERVAL_HOURS)
    }

    pub fn set_auto_restart_interval_hours(&mut self, hours: u8) {
        self.auto_restart_interval_hours = Some(hours);
    }

    pub fn repair_stale_password_bindings(&mut self) -> Vec<String> {
        let mut repairs = Vec::new();
        if self.main_password.trim().is_empty() {
            self.main_password = random_password();
            repairs.push("main_password regenerated".to_owned());
        }
        if self.main_sub_uuid.trim().is_empty() {
            self.main_sub_uuid = random_uuid_v4();
            repairs.push("main_sub_uuid regenerated".to_owned());
        }
        let mut old_keys: Vec<String> = self.passwords.keys().cloned().collect();
        old_keys.sort_by(|a, b| b.len().cmp(&a.len()).then_with(|| a.cmp(b)));
        for key in old_keys {
            let tag = secret_tag(&key);
            let needs_new_key = key.trim().is_empty();
            let mut entry = self.passwords.remove(&key).unwrap();
            let new_key = if needs_new_key {
                let mut candidate = random_password();
                while secret_eq(&candidate, &self.main_password)
                    || self.passwords.contains_key(&candidate)
                {
                    candidate = random_password();
                }
                repairs.push(format!("password tag={tag} rekeyed"));
                candidate
            } else {
                key.clone()
            };
            if entry.ip.is_empty()
                && let Some(next_ip) = get_next_ip(self)
            {
                entry.ip = next_ip;
                repairs.push(format!("password tag={tag} ip assigned"));
            }
            if entry.sub_uuid.trim().is_empty() {
                entry.sub_uuid = random_uuid_v4();
                repairs.push(format!("password tag={tag} sub_uuid regenerated"));
            }
            self.passwords.insert(new_key, entry);
        }
        repairs
    }

    pub fn resolve_routing_rule<'a>(&'a self, password: &str) -> Option<&'a RoutingRule> {
        if secret_eq(password, &self.main_password)
            && !self.main_routing_rule_id.is_empty()
            && let Some(rule) = self.routing_rules.get(&self.main_routing_rule_id)
        {
            return Some(rule);
        }
        if let Some(entry) = self.passwords.get(password) {
            if !entry.routing_rule_id.is_empty()
                && let Some(rule) = self.routing_rules.get(&entry.routing_rule_id)
            {
                return Some(rule);
            }
            if !entry.group_id.is_empty()
                && let Some(group) = self.groups.get(&entry.group_id)
                && !group.routing_rule_id.is_empty()
                && let Some(rule) = self.routing_rules.get(&group.routing_rule_id)
            {
                return Some(rule);
            }
        }
        None
    }

    pub fn resolve_worker_limit(&self, password: &str) -> Option<u32> {
        if secret_eq(password, &self.main_password) {
            return self.main_workers.filter(|&w| (3..=100).contains(&w));
        }
        if let Some(entry) = self.passwords.get(password) {
            if let Some(w) = entry.workers
                && (3..=100).contains(&w)
            {
                return Some(w);
            }
            if !entry.group_id.is_empty()
                && let Some(group) = self.groups.get(&entry.group_id)
                && let Some(w) = group.workers
                && (3..=100).contains(&w)
            {
                return Some(w);
            }
        }
        None
    }
}

#[derive(Clone)]
pub struct DatabasePersistence {
    inner: Arc<DatabasePersistenceInner>,
}

struct DatabasePersistenceInner {
    config_dir: PathBuf,
    state: Mutex<DatabasePersistenceState>,
    queue_ready: Condvar,
    notify: tokio::sync::Notify,
}

#[derive(Default)]
struct DatabasePersistenceState {
    queue: PersistenceQueue,
    last_error: Option<String>,
}

#[derive(Debug)]
enum PersistenceUpdate {
    Snapshot(Box<Database>),
    Traffic(TrafficSnapshot),
}

#[derive(Default)]
struct PersistenceQueue {
    next_revision: u64,
    processed_revision: u64,
    successful_revision: u64,
    pending: VecDeque<(u64, PersistenceUpdate)>,
    worker_running: bool,
}

impl PersistenceQueue {
    fn next_revision(&mut self) -> u64 {
        self.next_revision = self.next_revision.saturating_add(1);
        self.next_revision
    }

    fn submit_snapshot(&mut self, snapshot: Database) -> (u64, bool) {
        let revision = self.next_revision();
        self.pending.clear();
        self.pending
            .push_back((revision, PersistenceUpdate::Snapshot(Box::new(snapshot))));
        let start_worker = !self.worker_running;
        self.worker_running = true;
        (revision, start_worker)
    }

    fn submit_traffic(&mut self, traffic: TrafficSnapshot) -> (u64, bool) {
        let revision = self.next_revision();
        if let Some((pending_revision, PersistenceUpdate::Traffic(pending))) =
            self.pending.back_mut()
        {
            pending.merge_from(traffic);
            *pending_revision = revision;
        } else {
            self.pending
                .push_back((revision, PersistenceUpdate::Traffic(traffic)));
        }
        let start_worker = !self.worker_running;
        self.worker_running = true;
        (revision, start_worker)
    }

    fn take_pending(&mut self) -> Option<(u64, PersistenceUpdate)> {
        self.pending.pop_front()
    }

    fn complete(&mut self, revision: u64, successful: bool) {
        if revision >= self.processed_revision {
            self.processed_revision = revision;
        }
        if successful && revision >= self.successful_revision {
            self.successful_revision = revision;
        }
    }
}

impl DatabasePersistence {
    pub fn new(config_dir: PathBuf) -> Result<Self> {
        let inner = Arc::new(DatabasePersistenceInner {
            config_dir,
            state: Mutex::new(DatabasePersistenceState::default()),
            queue_ready: Condvar::new(),
            notify: tokio::sync::Notify::new(),
        });
        let worker = inner.clone();
        std::thread::Builder::new()
            .name("csqtt-db-writer".to_owned())
            .spawn(move || database_persistence_worker(worker))
            .context("start database persistence worker")?;
        Ok(Self { inner })
    }

    pub fn submit(&self, snapshot: Database) -> u64 {
        let (revision, _) = {
            let mut state = self
                .inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            state.queue.submit_snapshot(snapshot)
        };
        self.inner.queue_ready.notify_one();
        revision
    }

    pub fn submit_traffic(&self, traffic: TrafficSnapshot) -> u64 {
        if traffic.is_empty() {
            return 0;
        }
        let (revision, _) = {
            let mut state = self
                .inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            state.queue.submit_traffic(traffic)
        };
        self.inner.queue_ready.notify_one();
        revision
    }

    pub async fn wait(&self, revision: u64) -> Result<()> {
        loop {
            let notified = self.inner.notify.notified();
            let outcome = {
                let state = self
                    .inner
                    .state
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if state.queue.successful_revision >= revision {
                    Some(Ok(()))
                } else if state.queue.processed_revision >= revision {
                    Some(Err(anyhow::anyhow!(
                        "{}",
                        state
                            .last_error
                            .as_deref()
                            .unwrap_or("database persistence failed")
                    )))
                } else {
                    None
                }
            };
            if let Some(outcome) = outcome {
                return outcome;
            }
            notified.await;
        }
    }
}

fn database_persistence_worker(inner: Arc<DatabasePersistenceInner>) {
    let mut connection = None;
    loop {
        let (revision, update) = {
            let mut state = inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            while state.queue.pending.is_empty() {
                state = inner
                    .queue_ready
                    .wait(state)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            state.queue.take_pending().expect("pending database update")
        };
        let result = persist_database_update(&inner.config_dir, &mut connection, update);
        let error = result.err().map(|error| format!("{error:#}"));
        if error.is_some() {
            connection = None;
        }
        {
            let mut state = inner
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            state.queue.complete(revision, error.is_none());
            if let Some(error) = error {
                state.last_error = Some(error);
            } else if state.queue.successful_revision >= revision {
                state.last_error = None;
            }
        }
        inner.notify.notify_waiters();
    }
}

pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

static CACHED_NOW: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[inline(always)]
pub fn cached_now() -> u64 {
    let cached = CACHED_NOW.load(std::sync::atomic::Ordering::Relaxed);
    if cached != 0 {
        return cached;
    }
    refresh_cached_now()
}

pub fn refresh_cached_now() -> u64 {
    let ts = now() as u64;
    CACHED_NOW.store(ts, std::sync::atomic::Ordering::Relaxed);
    ts
}

pub fn random_password() -> String {
    let mut out = String::with_capacity(PASSWORD_LEN);
    let mut byte = [0u8; 1];
    while out.len() < PASSWORD_LEN {
        OsRng.fill_bytes(&mut byte);
        let value = usize::from(byte[0]);
        if value < 256 - 256 % PASS_CHARS.len() {
            out.push(PASS_CHARS[value % PASS_CHARS.len()] as char);
        }
    }
    out
}

pub fn secret_eq(left: &str, right: &str) -> bool {
    bool::from(left.as_bytes().ct_eq(right.as_bytes()))
}

pub fn secret_tag(secret: &str) -> String {
    Sha256::digest(secret.as_bytes())[..4]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

pub fn random_token(size: usize) -> String {
    let mut data = vec![0u8; size];
    OsRng.fill_bytes(&mut data);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

pub fn random_uuid_v4() -> String {
    let mut bytes = [0u8; 16];
    OsRng.fill_bytes(&mut bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    )
}

pub fn generate_unique_client_name<I, S>(existing_names: I) -> String
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let set: std::collections::HashSet<String> = existing_names
        .into_iter()
        .map(|s| s.as_ref().trim().to_lowercase())
        .collect();
    for _ in 0..1000 {
        let mut b = [0u8; 2];
        OsRng.fill_bytes(&mut b);
        let num = 100 + (u16::from_le_bytes(b) % 900);
        let candidate = format!("Клиент{num}");
        if !set.contains(&candidate.to_lowercase()) {
            return candidate;
        }
    }
    let mut b = [0u8; 4];
    OsRng.fill_bytes(&mut b);
    let num = 1000 + (u32::from_le_bytes(b) % 900000);
    format!("Клиент{num}")
}

pub fn is_client_name_taken<I, S>(existing_names: I, new_name: &str) -> bool
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let norm = new_name.trim().to_lowercase();
    if norm.is_empty() {
        return false;
    }
    existing_names
        .into_iter()
        .any(|s| s.as_ref().trim().to_lowercase() == norm)
}

pub fn existing_client_names_in_scope(
    db: &Database,
    group_id: &str,
    exclude_password: Option<&str>,
) -> Vec<String> {
    let gid = group_id.trim();
    db.passwords
        .iter()
        .filter_map(|(pass, entry)| {
            if Some(pass.as_str()) == exclude_password {
                return None;
            }
            if entry.group_id.trim() == gid {
                Some(entry.name.clone())
            } else {
                None
            }
        })
        .collect()
}

pub fn derive_wrap_key(password: &str) -> Result<[u8; 32]> {
    if password.is_empty() {
        bail!("empty password");
    }
    let hk = Hkdf::<Sha256>::new(Some(b"CSQTT-WRAP-v1"), password.as_bytes());
    let mut key = [0u8; 32];
    hk.expand(b"rtp-obfs/chacha20poly1305", &mut key)
        .map_err(|_| anyhow::anyhow!("HKDF expansion failed"))?;
    Ok(key)
}

pub fn is_expired(entry: &PasswordEntry) -> bool {
    entry.expires_at != 0 && now() > entry.expires_at
}

pub const OWNER_TUNNEL_IP: &str = "10.66.67.2";

pub fn get_next_ip(db: &Database) -> Option<String> {
    let mut buf = String::with_capacity(16);
    for i in 3..=250u8 {
        buf.clear();
        use std::fmt::Write;
        let _ = write!(buf, "10.66.67.{i}");
        let is_used = db.passwords.values().any(|d| d.ip == buf);
        if !is_used {
            return Some(buf);
        }
    }
    None
}

pub fn resolve_session_ip(db: &Database, session_password: &str) -> Option<String> {
    if secret_eq(session_password, &db.main_password) {
        return Some(OWNER_TUNNEL_IP.to_owned());
    }
    db.passwords.get(session_password).and_then(|entry| {
        if !entry.ip.is_empty() {
            Some(entry.ip.clone())
        } else {
            None
        }
    })
}

const DATABASE_FILE: &str = "csqtt.db";
const LEGACY_DATABASE_FILE: &str = "passwords.json";
const LEGACY_DATABASE_IMPORTED_FILE: &str = "passwords.json.imported";
const LEGACY_DATABASE_FILES: [&str; 2] = [LEGACY_DATABASE_FILE, LEGACY_DATABASE_IMPORTED_FILE];

const DATABASE_SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS counters (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    main_up_bytes   INTEGER NOT NULL DEFAULT 0,
    main_down_bytes INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS passwords (
    password       TEXT PRIMARY KEY,
    ip             TEXT    NOT NULL DEFAULT '',
    expires_at     INTEGER NOT NULL DEFAULT 0,
    down_bytes     INTEGER NOT NULL DEFAULT 0,
    up_bytes       INTEGER NOT NULL DEFAULT 0,
    vk_hash        TEXT    NOT NULL DEFAULT '',
    ports          TEXT    NOT NULL DEFAULT '',
    is_deactivated INTEGER NOT NULL DEFAULT 0,
    name           TEXT    NOT NULL DEFAULT '',
    vk_hashes      TEXT    NOT NULL DEFAULT '',
    vk_token_enabled INTEGER NOT NULL DEFAULT 0,
    dtls_port      INTEGER NOT NULL DEFAULT 0,
    wg_port        INTEGER NOT NULL DEFAULT 0,
    local_port     INTEGER NOT NULL DEFAULT 0,
    group_id       TEXT    NOT NULL DEFAULT '',
    routing_rule_id TEXT   NOT NULL DEFAULT '',
    sub_uuid       TEXT    NOT NULL DEFAULT '',
    workers        INTEGER
);
CREATE TABLE IF NOT EXISTS local_proxy_profiles (
    id         TEXT PRIMARY KEY,
    sort_order INTEGER NOT NULL,
    name       TEXT    NOT NULL,
    port       INTEGER NOT NULL,
    username   TEXT    NOT NULL DEFAULT '',
    password   TEXT    NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS local_proxy_state (
    id                INTEGER PRIMARY KEY CHECK (id = 1),
    active_profile_id TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS client_groups (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    routing_rule_id TEXT NOT NULL DEFAULT '',
    admin_uuid      TEXT NOT NULL DEFAULT '',
    sub_uuid        TEXT NOT NULL DEFAULT '',
    workers         INTEGER,
    vk_token        TEXT,
    vk_hashes       TEXT
);
CREATE TABLE IF NOT EXISTS routing_rules (
    id                   TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    is_whitelist         INTEGER NOT NULL DEFAULT 0,
    packages_json        TEXT NOT NULL DEFAULT '[]',
    template_domains     INTEGER NOT NULL DEFAULT 0,
    direct_domains       TEXT NOT NULL DEFAULT '[]',
    domains_is_whitelist INTEGER NOT NULL DEFAULT 0,
    ip_is_whitelist      INTEGER NOT NULL DEFAULT 0,
    ips_json             TEXT NOT NULL DEFAULT '[]'
);
";

fn open_database_connection(config_dir: &Path) -> Result<Connection> {
    fs::create_dir_all(config_dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(config_dir, fs::Permissions::from_mode(0o700))?;
    }
    let path = config_dir.join(DATABASE_FILE);
    let connection = Connection::open(&path).with_context(|| format!("open {}", path.display()))?;
    connection
        .pragma_update(None, "journal_mode", "WAL")
        .context("enable WAL journal mode")?;
    connection
        .pragma_update(None, "synchronous", "FULL")
        .context("set synchronous FULL")?;
    connection
        .execute_batch(DATABASE_SCHEMA)
        .with_context(|| format!("schema {}", path.display()))?;
    ensure_password_columns(&connection)?;
    ensure_routing_rule_columns(&connection)?;
    ensure_client_group_columns(&connection)?;
    Ok(connection)
}

fn ensure_client_group_columns(connection: &Connection) -> Result<()> {
    let mut statement = connection
        .prepare("PRAGMA table_info(client_groups)")
        .context("prepare client_groups schema check")?;
    let columns = statement
        .query_map([], |row| row.get::<_, String>(1))
        .context("read client_groups schema")?
        .collect::<std::result::Result<Vec<_>, _>>()
        .context("collect client_groups schema")?;
    if !columns.iter().any(|column| column == "admin_uuid") {
        connection
            .execute(
                "ALTER TABLE client_groups ADD COLUMN admin_uuid TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add admin_uuid column")?;
    }
    if !columns.iter().any(|column| column == "sub_uuid") {
        connection
            .execute(
                "ALTER TABLE client_groups ADD COLUMN sub_uuid TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add sub_uuid column")?;
    }
    if !columns.iter().any(|column| column == "workers") {
        connection
            .execute("ALTER TABLE client_groups ADD COLUMN workers INTEGER", [])
            .context("add workers column")?;
    }
    if !columns.iter().any(|column| column == "vk_token") {
        connection
            .execute("ALTER TABLE client_groups ADD COLUMN vk_token TEXT", [])
            .context("add vk_token column")?;
    }
    if !columns.iter().any(|column| column == "vk_hashes") {
        connection
            .execute("ALTER TABLE client_groups ADD COLUMN vk_hashes TEXT", [])
            .context("add vk_hashes column")?;
    }
    Ok(())
}

fn ensure_routing_rule_columns(connection: &Connection) -> Result<()> {
    let mut statement = connection
        .prepare("PRAGMA table_info(routing_rules)")
        .context("prepare routing_rules schema check")?;
    let columns = statement
        .query_map([], |row| row.get::<_, String>(1))
        .context("read routing_rules schema")?
        .collect::<std::result::Result<Vec<_>, _>>()
        .context("collect routing_rules schema")?;
    if !columns
        .iter()
        .any(|column| column == "domains_is_whitelist")
    {
        connection
            .execute(
                "ALTER TABLE routing_rules ADD COLUMN domains_is_whitelist INTEGER NOT NULL DEFAULT 0",
                [],
            )
            .context("add domains_is_whitelist column")?;
    }
    if !columns.iter().any(|column| column == "ip_is_whitelist") {
        connection
            .execute(
                "ALTER TABLE routing_rules ADD COLUMN ip_is_whitelist INTEGER NOT NULL DEFAULT 0",
                [],
            )
            .context("add ip_is_whitelist column")?;
    }
    if !columns.iter().any(|column| column == "ips_json") {
        connection
            .execute(
                "ALTER TABLE routing_rules ADD COLUMN ips_json TEXT NOT NULL DEFAULT '[]'",
                [],
            )
            .context("add ips_json column")?;
    }
    Ok(())
}

fn ensure_password_columns(connection: &Connection) -> Result<()> {
    let mut statement = connection
        .prepare("PRAGMA table_info(passwords)")
        .context("prepare password schema check")?;
    let columns = statement
        .query_map([], |row| row.get::<_, String>(1))
        .context("read password schema")?
        .collect::<std::result::Result<Vec<_>, _>>()
        .context("collect password schema")?;
    if !columns.iter().any(|column| column == "ip") {
        connection
            .execute(
                "ALTER TABLE passwords ADD COLUMN ip TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add ip column")?;
    }
    if !columns.iter().any(|column| column == "vk_token_enabled") {
        connection
            .execute(
                "ALTER TABLE passwords ADD COLUMN vk_token_enabled INTEGER NOT NULL DEFAULT 0",
                [],
            )
            .context("add vk_token_enabled column")?;
    }
    if !columns.iter().any(|column| column == "group_id") {
        connection
            .execute(
                "ALTER TABLE passwords ADD COLUMN group_id TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add group_id column")?;
    }
    if !columns.iter().any(|column| column == "routing_rule_id") {
        connection
            .execute(
                "ALTER TABLE passwords ADD COLUMN routing_rule_id TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add routing_rule_id column")?;
    }
    if !columns.iter().any(|column| column == "sub_uuid") {
        connection
            .execute(
                "ALTER TABLE passwords ADD COLUMN sub_uuid TEXT NOT NULL DEFAULT ''",
                [],
            )
            .context("add sub_uuid column")?;
    }
    if !columns.iter().any(|column| column == "workers") {
        connection
            .execute("ALTER TABLE passwords ADD COLUMN workers INTEGER", [])
            .context("add workers column")?;
    }
    Ok(())
}

fn existing_row_keys(transaction: &rusqlite::Transaction<'_>, select: &str) -> Result<Vec<String>> {
    let mut statement = transaction
        .prepare(select)
        .with_context(|| format!("prepare {select}"))?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .with_context(|| format!("query {select}"))?;
    rows.collect::<std::result::Result<Vec<_>, _>>()
        .with_context(|| format!("read {select}"))
}

fn write_database_snapshot(connection: &mut Connection, db: &Database) -> Result<()> {
    let transaction = connection
        .transaction()
        .context("begin database transaction")?;
    let auto_restart_interval_hours = db.auto_restart_interval_hours().to_string();
    for (key, value) in [
        ("main_password", db.main_password.as_str()),
        ("main_sub_uuid", db.main_sub_uuid.as_str()),
        ("main_vk_hashes", db.main_vk_hashes.as_str()),
        (
            "main_vk_token_enabled",
            if db.main_vk_token_enabled { "1" } else { "0" },
        ),
        ("dns", db.dns.as_str()),
        ("dns_provider", db.dns_provider.as_str()),
        (
            "auto_restart_interval_hours",
            auto_restart_interval_hours.as_str(),
        ),
        ("admin_id", db.admin_id.as_str()),
        ("bot_token", db.bot_token.as_str()),
        ("vk_access_token", db.vk_access_token.as_str()),
        ("vk_hashes", db.vk_hashes.as_str()),
        ("main_routing_rule_id", db.main_routing_rule_id.as_str()),
    ] {
        transaction
            .execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                rusqlite::params![key, value],
            )
            .context("write meta")?;
    }
    if let Some(w) = db.main_workers {
        transaction
            .execute(
                "INSERT INTO meta (key, value) VALUES ('main_workers', ?1)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                rusqlite::params![w.to_string()],
            )
            .context("write main_workers")?;
    } else {
        transaction
            .execute("DELETE FROM meta WHERE key = 'main_workers'", [])
            .context("clear main_workers")?;
    }
    if let Some(active) = db.logging_active {
        transaction
            .execute(
                "INSERT INTO meta (key, value) VALUES ('logging_active', ?1)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                rusqlite::params![if active { "1" } else { "0" }],
            )
            .context("write logging_active")?;
    } else {
        transaction
            .execute("DELETE FROM meta WHERE key = 'logging_active'", [])
            .context("clear logging_active")?;
    }
    transaction
        .execute(
            "INSERT INTO counters (id, main_up_bytes, main_down_bytes) VALUES (1, ?1, ?2)
             ON CONFLICT(id) DO UPDATE SET
                main_up_bytes = excluded.main_up_bytes,
                main_down_bytes = excluded.main_down_bytes",
            rusqlite::params![db.main_up_bytes, db.main_down_bytes],
        )
        .context("write counters")?;
    for password in existing_row_keys(&transaction, "SELECT password FROM passwords")? {
        if !db.passwords.contains_key(&password) {
            transaction
                .execute(
                    "DELETE FROM passwords WHERE password = ?1",
                    rusqlite::params![password],
                )
                .with_context(|| format!("remove password {}", secret_tag(&password)))?;
        }
    }
    for group_id in existing_row_keys(&transaction, "SELECT id FROM client_groups")? {
        if !db.groups.contains_key(&group_id) {
            transaction
                .execute(
                    "DELETE FROM client_groups WHERE id = ?1",
                    rusqlite::params![group_id],
                )
                .with_context(|| format!("remove group {group_id}"))?;
        }
    }
    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO client_groups (id, name, routing_rule_id, admin_uuid, sub_uuid, workers, vk_token, vk_hashes)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    routing_rule_id = excluded.routing_rule_id,
                    admin_uuid = excluded.admin_uuid,
                    sub_uuid = excluded.sub_uuid,
                    workers = excluded.workers,
                    vk_token = excluded.vk_token,
                    vk_hashes = excluded.vk_hashes",
            )
            .context("prepare client_groups insert")?;
        for group in db.groups.values() {
            insert
                .execute(rusqlite::params![
                    group.id,
                    group.name,
                    group.routing_rule_id,
                    group.admin_uuid,
                    group.sub_uuid,
                    group.workers.map(|w| w as i64),
                    group.vk_token.as_deref(),
                    group.vk_hashes.as_deref(),
                ])
                .with_context(|| format!("write group {}", group.id))?;
        }
    }
    for rule_id in existing_row_keys(&transaction, "SELECT id FROM routing_rules")? {
        if !db.routing_rules.contains_key(&rule_id) {
            transaction
                .execute(
                    "DELETE FROM routing_rules WHERE id = ?1",
                    rusqlite::params![rule_id],
                )
                .with_context(|| format!("remove routing rule {rule_id}"))?;
        }
    }
    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO routing_rules (
                    id, name, is_whitelist, packages_json, template_domains, direct_domains,
                    domains_is_whitelist, ip_is_whitelist, ips_json
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
                 ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    is_whitelist = excluded.is_whitelist,
                    packages_json = excluded.packages_json,
                    template_domains = excluded.template_domains,
                    direct_domains = excluded.direct_domains,
                    domains_is_whitelist = excluded.domains_is_whitelist,
                    ip_is_whitelist = excluded.ip_is_whitelist,
                    ips_json = excluded.ips_json",
            )
            .context("prepare routing_rules insert")?;
        for rule in db.routing_rules.values() {
            let pkgs = serde_json::to_string(&rule.packages).unwrap_or_else(|_| "[]".to_string());
            let domains =
                serde_json::to_string(&rule.direct_domains).unwrap_or_else(|_| "[]".to_string());
            let ips = serde_json::to_string(&rule.ips).unwrap_or_else(|_| "[]".to_string());
            insert
                .execute(rusqlite::params![
                    rule.id,
                    rule.name,
                    i64::from(rule.is_whitelist),
                    pkgs,
                    i64::from(rule.template_domains),
                    domains,
                    i64::from(rule.domains_is_whitelist),
                    i64::from(rule.ip_is_whitelist),
                    ips,
                ])
                .with_context(|| format!("write routing rule {}", rule.id))?;
        }
    }
    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO passwords (
                    password, ip, expires_at, down_bytes, up_bytes,
                    vk_hash, ports, is_deactivated, name, vk_hashes, vk_token_enabled,
                    dtls_port, wg_port, local_port, group_id, routing_rule_id, sub_uuid, workers
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)
                ON CONFLICT(password) DO UPDATE SET
                    ip = excluded.ip,
                    expires_at = excluded.expires_at,
                    down_bytes = excluded.down_bytes,
                    up_bytes = excluded.up_bytes,
                    vk_hash = excluded.vk_hash,
                    ports = excluded.ports,
                    is_deactivated = excluded.is_deactivated,
                    name = excluded.name,
                    vk_hashes = excluded.vk_hashes,
                    vk_token_enabled = excluded.vk_token_enabled,
                    dtls_port = excluded.dtls_port,
                    wg_port = excluded.wg_port,
                    local_port = excluded.local_port,
                    group_id = excluded.group_id,
                    routing_rule_id = excluded.routing_rule_id,
                    sub_uuid = excluded.sub_uuid,
                    workers = excluded.workers",
            )
            .context("prepare passwords insert")?;
        for (password, entry) in &db.passwords {
            insert
                .execute(rusqlite::params![
                    password,
                    entry.ip,
                    entry.expires_at,
                    entry.down_bytes,
                    entry.up_bytes,
                    entry.vk_hash,
                    entry.ports,
                    i64::from(entry.is_deactivated),
                    entry.name,
                    entry.vk_hashes,
                    i64::from(entry.vk_token_enabled),
                    entry.dtls_port,
                    entry.wg_port,
                    entry.local_port,
                    entry.group_id,
                    entry.routing_rule_id,
                    entry.sub_uuid,
                    entry.workers.map(|w| w as i64),
                ])
                .with_context(|| format!("write password {}", secret_tag(password)))?;
        }
    }
    for profile_id in existing_row_keys(&transaction, "SELECT id FROM local_proxy_profiles")? {
        if !db
            .local_proxy
            .profiles
            .iter()
            .any(|profile| profile.id == profile_id)
        {
            transaction
                .execute(
                    "DELETE FROM local_proxy_profiles WHERE id = ?1",
                    rusqlite::params![profile_id],
                )
                .with_context(|| format!("remove local proxy profile {profile_id}"))?;
        }
    }
    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO local_proxy_profiles (id, sort_order, name, port, username, password)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(id) DO UPDATE SET
                    sort_order = excluded.sort_order,
                    name = excluded.name,
                    port = excluded.port,
                    username = excluded.username,
                    password = excluded.password",
            )
            .context("prepare local proxy profiles insert")?;
        for (sort_order, profile) in db.local_proxy.profiles.iter().enumerate() {
            insert
                .execute(rusqlite::params![
                    profile.id,
                    sort_order as i64,
                    profile.name,
                    profile.port,
                    profile.username,
                    profile.password,
                ])
                .context("write local proxy profile")?;
        }
    }
    transaction
        .execute(
            "INSERT INTO local_proxy_state (id, active_profile_id) VALUES (1, ?1)
             ON CONFLICT(id) DO UPDATE SET active_profile_id = excluded.active_profile_id",
            rusqlite::params![db.local_proxy.active_profile_id],
        )
        .context("write local proxy state")?;
    transaction
        .commit()
        .context("commit database transaction")?;
    Ok(())
}

fn read_database_snapshot(config_dir: &Path) -> Result<Database> {
    let connection = open_database_connection(config_dir)?;
    let mut db = Database::default();
    {
        let mut meta = connection
            .prepare("SELECT key, value FROM meta")
            .context("prepare meta select")?;
        let mut rows = meta.query([]).context("read meta")?;
        while let Some(row) = rows.next()? {
            let key: String = row.get(0).context("read meta key")?;
            let value: String = row.get(1).context("read meta value")?;
            match key.as_str() {
                "main_password" => db.main_password = value,
                "main_sub_uuid" => db.main_sub_uuid = value,
                "main_vk_hashes" => db.main_vk_hashes = value,
                "main_vk_token_enabled" => db.main_vk_token_enabled = value == "1",
                "dns" => db.dns = value,
                "dns_provider" => db.dns_provider = value,
                "auto_restart_interval_hours" => {
                    if let Ok(hours) = value.parse::<u8>() {
                        db.auto_restart_interval_hours = Some(hours);
                    }
                }
                "admin_id" => db.admin_id = value,
                "bot_token" => db.bot_token = value,
                "vk_access_token" => db.vk_access_token = value,
                "vk_hashes" => db.vk_hashes = value,
                "main_routing_rule_id" => db.main_routing_rule_id = value,
                "main_workers" => {
                    db.main_workers = value
                        .parse::<u32>()
                        .ok()
                        .and_then(|w| (3..=100).contains(&w).then_some(w));
                }
                "logging_active" => db.logging_active = Some(value == "1"),
                _ => {}
            }
        }
    }
    if let Ok((up_bytes, down_bytes)) = connection.query_row(
        "SELECT main_up_bytes, main_down_bytes FROM counters WHERE id = 1",
        [],
        |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)),
    ) {
        db.main_up_bytes = up_bytes;
        db.main_down_bytes = down_bytes;
    }
    {
        let mut select = connection
            .prepare(
                "SELECT password, ip, expires_at, down_bytes, up_bytes,
                        vk_hash, ports, is_deactivated, name, vk_hashes, vk_token_enabled,
                        dtls_port, wg_port, local_port, group_id, routing_rule_id, sub_uuid, workers
                 FROM passwords",
            )
            .context("prepare passwords select")?;
        let mut rows = select.query([]).context("read passwords")?;
        while let Some(row) = rows.next()? {
            let password: String = row.get(0).context("read password key")?;
            let ip: String = row.get(1).unwrap_or_default();
            let sub_uuid: String = row.get(16).unwrap_or_default();
            let workers: Option<u32> = row
                .get::<_, Option<i64>>(17)
                .ok()
                .flatten()
                .and_then(|w| (3..=100).contains(&w).then_some(w as u32));
            let entry = PasswordEntry {
                ip,
                expires_at: row.get(2).context("read password expires_at")?,
                down_bytes: row.get(3).context("read password down_bytes")?,
                up_bytes: row.get(4).context("read password up_bytes")?,
                vk_hash: row.get(5).context("read password vk_hash")?,
                ports: row.get(6).context("read password ports")?,
                is_deactivated: row
                    .get::<_, i64>(7)
                    .context("read password is_deactivated")?
                    != 0,
                name: row.get(8).context("read password name")?,
                vk_hashes: row.get(9).context("read password vk_hashes")?,
                vk_token_enabled: row
                    .get::<_, i64>(10)
                    .context("read password vk_token_enabled")?
                    != 0,
                dtls_port: row.get(11).context("read password dtls_port")?,
                wg_port: row.get(12).context("read password wg_port")?,
                local_port: row.get(13).context("read password local_port")?,
                group_id: row.get(14).unwrap_or_default(),
                routing_rule_id: row.get(15).unwrap_or_default(),
                sub_uuid,
                workers,
            };
            db.passwords.insert(password, entry);
        }
        let mut names_by_group: std::collections::HashMap<
            String,
            std::collections::HashSet<String>,
        > = std::collections::HashMap::new();
        for (pass, entry) in db.passwords.iter_mut() {
            if pass == &db.main_password {
                if entry.name.trim().is_empty() || entry.name == "Главный пароль" {
                    entry.name = "Владелец".to_string();
                }
                continue;
            }
            let group_key = entry.group_id.trim().to_string();
            let group_names = names_by_group.entry(group_key).or_default();
            let trimmed = entry.name.trim();
            if trimmed.is_empty() || group_names.contains(&trimmed.to_lowercase()) {
                let new_name = generate_unique_client_name(group_names.iter().map(|s| s.as_str()));
                group_names.insert(new_name.to_lowercase());
                entry.name = new_name;
            } else {
                group_names.insert(trimmed.to_lowercase());
                entry.name = trimmed.to_string();
            }
        }
    }
    {
        let mut select = connection
            .prepare("SELECT id, name, routing_rule_id, admin_uuid, sub_uuid, workers, vk_token, vk_hashes FROM client_groups")
            .context("prepare client_groups select")?;
        let mut rows = select.query([]).context("read client_groups")?;
        while let Some(row) = rows.next()? {
            let id: String = row.get(0).context("read group id")?;
            let name: String = row.get(1).context("read group name")?;
            let routing_rule_id: String = row.get(2).unwrap_or_default();
            let admin_uuid: String = row.get(3).unwrap_or_default();
            let mut sub_uuid: String = row.get(4).unwrap_or_default();
            if sub_uuid.trim().is_empty() {
                sub_uuid = random_uuid_v4();
            }
            let workers: Option<u32> = row
                .get::<_, Option<i64>>(5)
                .ok()
                .flatten()
                .and_then(|w| (3..=100).contains(&w).then_some(w as u32));
            let vk_token: Option<String> = row.get(6).ok().flatten();
            let vk_hashes: Option<String> = row.get(7).ok().flatten();
            db.groups.insert(
                id.clone(),
                ClientGroup {
                    id,
                    name,
                    routing_rule_id,
                    admin_uuid,
                    sub_uuid,
                    workers,
                    vk_token,
                    vk_hashes,
                },
            );
        }
    }
    {
        let mut select = connection
            .prepare(
                "SELECT id, name, is_whitelist, packages_json, template_domains, direct_domains,
                        domains_is_whitelist, ip_is_whitelist, ips_json
                 FROM routing_rules",
            )
            .context("prepare routing_rules select")?;
        let mut rows = select.query([]).context("read routing_rules")?;
        while let Some(row) = rows.next()? {
            let id: String = row.get(0).context("read rule id")?;
            let name: String = row.get(1).context("read rule name")?;
            let is_whitelist: bool = row.get::<_, i64>(2).context("read rule is_whitelist")? != 0;
            let pkgs_str: String = row.get(3).unwrap_or_default();
            let template_domains: bool = row.get::<_, i64>(4).unwrap_or(0) != 0;
            let domains_str: String = row.get(5).unwrap_or_default();
            let domains_is_whitelist: bool = row.get::<_, i64>(6).unwrap_or(0) != 0;
            let ip_is_whitelist: bool = row.get::<_, i64>(7).unwrap_or(0) != 0;
            let ips_str: String = row.get(8).unwrap_or_default();
            let packages: Vec<String> = serde_json::from_str(&pkgs_str).unwrap_or_default();
            let direct_domains: Vec<String> =
                serde_json::from_str(&domains_str).unwrap_or_default();
            let ips: Vec<String> = serde_json::from_str(&ips_str).unwrap_or_default();
            db.routing_rules.insert(
                id.clone(),
                RoutingRule {
                    id,
                    name,
                    is_whitelist,
                    packages,
                    template_domains,
                    direct_domains,
                    domains_is_whitelist,
                    ip_is_whitelist,
                    ips,
                },
            );
        }
    }
    {
        let mut select = connection
            .prepare(
                "SELECT id, name, port, username, password
                 FROM local_proxy_profiles ORDER BY sort_order",
            )
            .context("prepare local proxy profiles select")?;
        let mut rows = select.query([]).context("read local proxy profiles")?;
        while let Some(row) = rows.next()? {
            db.local_proxy.profiles.push(LocalProxyProfile {
                id: row.get(0).context("read profile id")?,
                name: row.get(1).context("read profile name")?,
                port: row.get(2).context("read profile port")?,
                username: row.get(3).context("read profile username")?,
                password: row.get(4).context("read profile password")?,
            });
        }
    }
    if let Ok(active_profile_id) = connection.query_row(
        "SELECT active_profile_id FROM local_proxy_state WHERE id = 1",
        [],
        |row| row.get::<_, String>(0),
    ) {
        db.local_proxy.active_profile_id = active_profile_id;
    }
    db.local_proxy.normalize();
    db.bot_token.clear();
    db.admin_id.clear();
    Ok(db)
}

pub fn load_database(config_dir: &Path) -> Result<Database> {
    let _guard = DATABASE_SAVE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let database_exists = config_dir.join(DATABASE_FILE).exists();
    let legacy_paths: Vec<PathBuf> = LEGACY_DATABASE_FILES
        .iter()
        .map(|name| config_dir.join(name))
        .filter(|path| path.exists())
        .collect();
    if database_exists {
        let mut db = read_database_snapshot(config_dir)?;
        repair_loaded_database(config_dir, &mut db)?;
        if !legacy_paths.is_empty() {
            eprintln!(
                "[DB] Ignoring {} retired JSON database file(s); {DATABASE_FILE} is authoritative",
                legacy_paths.len()
            );
        }
        return Ok(db);
    }
    if legacy_paths.is_empty() {
        return Ok(Database::default());
    }

    let mut legacy_databases = Vec::with_capacity(legacy_paths.len());
    for legacy_path in &legacy_paths {
        let data =
            fs::read(legacy_path).with_context(|| format!("read {}", legacy_path.display()))?;
        let mut legacy: Database = serde_json::from_slice(&data)
            .with_context(|| format!("parse {}", legacy_path.display()))?;
        legacy.bot_token.clear();
        legacy.admin_id.clear();
        legacy.web_sessions.clear();
        legacy.local_proxy.normalize();
        legacy_databases.push(legacy);
    }

    let mut connection = open_database_connection(config_dir)?;
    for legacy in &legacy_databases {
        import_legacy_database_rows(&mut connection, legacy)
            .context("commit legacy JSON rows into SQLite")?;
    }
    drop(connection);

    let mut db = read_database_snapshot(config_dir)?;
    for legacy in &legacy_databases {
        verify_legacy_database_rows(&db, legacy)
            .context("verify legacy JSON import into SQLite")?;
    }
    repair_loaded_database(config_dir, &mut db)?;

    for legacy_path in &legacy_paths {
        fs::remove_file(legacy_path)
            .with_context(|| format!("remove retired legacy database {}", legacy_path.display()))?;
    }
    eprintln!(
        "[DB] Imported {} retired JSON database file(s) into {DATABASE_FILE} and removed them",
        legacy_paths.len()
    );
    Ok(db)
}

fn repair_loaded_database(config_dir: &Path, db: &mut Database) -> Result<()> {
    let repairs = db.repair_stale_password_bindings();
    if repairs.is_empty() {
        return Ok(());
    }
    let mut connection = open_database_connection(config_dir)?;
    write_database_snapshot(&mut connection, db)?;
    const LOG_LIMIT: usize = 16;
    let shown = repairs
        .iter()
        .take(LOG_LIMIT)
        .map(String::as_str)
        .collect::<Vec<_>>()
        .join("; ");
    let hidden = repairs.len().saturating_sub(LOG_LIMIT);
    let suffix = if hidden > 0 {
        format!(" (+{hidden} more)")
    } else {
        String::new()
    };
    eprintln!(
        "[DB] WARN repaired {} stale password binding change(s) in {DATABASE_FILE}: {shown}{suffix}",
        repairs.len()
    );
    Ok(())
}

fn import_legacy_database_rows(connection: &mut Connection, legacy: &Database) -> Result<()> {
    let transaction = connection
        .transaction()
        .context("begin legacy JSON import transaction")?;

    for (key, value) in [
        ("main_password", legacy.main_password.as_str()),
        ("dns", legacy.dns.as_str()),
        ("vk_access_token", legacy.vk_access_token.as_str()),
        ("vk_hashes", legacy.vk_hashes.as_str()),
        ("main_routing_rule_id", legacy.main_routing_rule_id.as_str()),
    ] {
        transaction
            .execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value =
                    CASE WHEN meta.value = '' THEN excluded.value ELSE meta.value END",
                rusqlite::params![key, value],
            )
            .with_context(|| format!("merge legacy meta {key}"))?;
    }
    if let Some(active) = legacy.logging_active {
        transaction
            .execute(
                "INSERT INTO meta (key, value) VALUES ('logging_active', ?1)
                 ON CONFLICT(key) DO NOTHING",
                rusqlite::params![if active { "1" } else { "0" }],
            )
            .context("merge legacy logging_active")?;
    }
    transaction
        .execute(
            "INSERT INTO counters (id, main_up_bytes, main_down_bytes) VALUES (1, ?1, ?2)
             ON CONFLICT(id) DO UPDATE SET
                main_up_bytes = MAX(counters.main_up_bytes, excluded.main_up_bytes),
                main_down_bytes = MAX(counters.main_down_bytes, excluded.main_down_bytes)",
            rusqlite::params![legacy.main_up_bytes, legacy.main_down_bytes],
        )
        .context("merge legacy main traffic counters")?;

    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO passwords (
                    password, ip, expires_at, down_bytes, up_bytes,
                    vk_hash, ports, is_deactivated, name, vk_hashes, vk_token_enabled,
                    dtls_port, wg_port, local_port
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
                ON CONFLICT(password) DO NOTHING",
            )
            .context("prepare legacy passwords import")?;
        for (password, entry) in &legacy.passwords {
            insert
                .execute(rusqlite::params![
                    password,
                    entry.ip,
                    entry.expires_at,
                    entry.down_bytes,
                    entry.up_bytes,
                    entry.vk_hash,
                    entry.ports,
                    i64::from(entry.is_deactivated),
                    entry.name,
                    entry.vk_hashes,
                    i64::from(entry.vk_token_enabled),
                    entry.dtls_port,
                    entry.wg_port,
                    entry.local_port,
                ])
                .with_context(|| format!("merge legacy password {}", secret_tag(password)))?;
        }
    }
    {
        let mut insert = transaction
            .prepare(
                "INSERT INTO local_proxy_profiles (id, sort_order, name, port, username, password)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(id) DO NOTHING",
            )
            .context("prepare legacy proxy profiles import")?;
        for (sort_order, profile) in legacy.local_proxy.profiles.iter().enumerate() {
            insert
                .execute(rusqlite::params![
                    profile.id,
                    sort_order as i64,
                    profile.name,
                    profile.port,
                    profile.username,
                    profile.password,
                ])
                .with_context(|| format!("merge legacy proxy profile {}", profile.id))?;
        }
    }
    if !legacy.local_proxy.active_profile_id.is_empty() {
        transaction
            .execute(
                "INSERT INTO local_proxy_state (id, active_profile_id) VALUES (1, ?1)
                 ON CONFLICT(id) DO UPDATE SET active_profile_id =
                    CASE WHEN local_proxy_state.active_profile_id = ''
                         THEN excluded.active_profile_id
                         ELSE local_proxy_state.active_profile_id END",
                rusqlite::params![legacy.local_proxy.active_profile_id],
            )
            .context("merge legacy active proxy profile")?;
    }
    transaction
        .commit()
        .context("commit legacy JSON import transaction")?;
    Ok(())
}

fn verify_legacy_database_rows(sqlite: &Database, legacy: &Database) -> Result<()> {
    if !legacy.main_password.is_empty()
        && !sqlite.main_password.is_empty()
        && sqlite.main_password != legacy.main_password
    {
        bail!("legacy main password conflicts with existing SQLite state");
    }
    for (password, legacy_entry) in &legacy.passwords {
        let Some(sqlite_entry) = sqlite.passwords.get(password) else {
            bail!(
                "legacy password {} is missing after import",
                secret_tag(password)
            );
        };
        if !legacy_password_matches(sqlite_entry, legacy_entry) {
            bail!(
                "legacy password {} conflicts with existing SQLite state",
                secret_tag(password)
            );
        }
    }
    Ok(())
}

fn legacy_password_matches(sqlite: &PasswordEntry, legacy: &PasswordEntry) -> bool {
    sqlite.ip == legacy.ip
        && sqlite.expires_at == legacy.expires_at
        && sqlite.vk_hash == legacy.vk_hash
        && sqlite.ports == legacy.ports
        && sqlite.is_deactivated == legacy.is_deactivated
        && (sqlite.name == legacy.name || legacy.name.trim().is_empty())
        && sqlite.vk_hashes == legacy.vk_hashes
        && sqlite.vk_token_enabled == legacy.vk_token_enabled
        && sqlite.dtls_port == legacy.dtls_port
        && sqlite.wg_port == legacy.wg_port
        && sqlite.local_port == legacy.local_port
}

fn persist_database_update(
    config_dir: &Path,
    connection: &mut Option<Connection>,
    update: PersistenceUpdate,
) -> Result<()> {
    let _guard = DATABASE_SAVE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if connection.is_none() {
        *connection = Some(open_database_connection(config_dir)?);
    }
    let connection = connection
        .as_mut()
        .expect("database connection initialized");
    match update {
        PersistenceUpdate::Snapshot(snapshot) => write_database_snapshot(connection, &snapshot),
        PersistenceUpdate::Traffic(traffic) => write_traffic(connection, &traffic),
    }
}

pub fn save_database(config_dir: &Path, db: &Database) -> Result<()> {
    let _guard = DATABASE_SAVE_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut connection = open_database_connection(config_dir)?;
    write_database_snapshot(&mut connection, db)
}

fn write_traffic(connection: &mut Connection, traffic: &TrafficSnapshot) -> Result<()> {
    if traffic.is_empty() {
        return Ok(());
    }
    let transaction = connection
        .transaction()
        .context("begin traffic persistence transaction")?;

    if let Some(main) = traffic.main {
        transaction
            .execute(
                "INSERT INTO counters (id, main_up_bytes, main_down_bytes) VALUES (1, ?1, ?2)
                 ON CONFLICT(id) DO UPDATE SET
                    main_up_bytes = excluded.main_up_bytes,
                    main_down_bytes = excluded.main_down_bytes",
                rusqlite::params![main.up_bytes, main.down_bytes],
            )
            .context("write main traffic counters")?;
    }
    for (password, counters) in &traffic.passwords {
        transaction
            .execute(
                "UPDATE passwords SET up_bytes = ?2, down_bytes = ?3 WHERE password = ?1",
                rusqlite::params![password, counters.up_bytes, counters.down_bytes],
            )
            .with_context(|| format!("write traffic for password {}", secret_tag(password)))?;
    }
    transaction
        .commit()
        .context("commit traffic persistence transaction")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        Database, DatabasePersistence, PasswordEntry, PersistenceQueue, PersistenceUpdate,
        TrafficCounters, TrafficSnapshot, load_database, save_database,
    };

    #[test]
    fn dns_survives_database_restart() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-dns-test-{unique}"));
        let database = Database {
            dns: "9.9.9.9,149.112.112.112".to_owned(),
            dns_provider: "custom".to_owned(),
            ..Database::default()
        };

        save_database(&directory, &database).expect("save database");
        let restored = load_database(&directory).expect("load database");

        assert_eq!(restored.dns, database.dns);
        assert_eq!(restored.dns_provider, database.dns_provider);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn vk_token_settings_and_access_flag_survive_database_restart() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-vk-token-test-{unique}"));
        let mut database = Database {
            vk_access_token: "vk1.shared-token".to_owned(),
            ..Database::default()
        };
        database.passwords.insert(
            "client-password1".to_owned(),
            PasswordEntry {
                vk_token_enabled: true,
                ..PasswordEntry::default()
            },
        );

        save_database(&directory, &database).expect("save database");
        let restored = load_database(&directory).expect("load database");

        assert_eq!(restored.vk_access_token, "vk1.shared-token");
        assert!(restored.passwords["client-password1"].vk_token_enabled);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn auto_restart_interval_defaults_to_disabled_and_persists_disabled() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-auto-restart-test-{unique}"));

        let database = Database::default();
        assert_eq!(database.auto_restart_interval_hours(), 0);
        save_database(&directory, &database).expect("save default interval");

        let connection = rusqlite::Connection::open(directory.join(super::DATABASE_FILE)).unwrap();
        connection
            .execute(
                "DELETE FROM meta WHERE key = 'auto_restart_interval_hours'",
                [],
            )
            .unwrap();
        drop(connection);
        let mut restored = load_database(&directory).expect("load old sqlite database");
        assert_eq!(restored.auto_restart_interval_hours(), 0);

        restored.set_auto_restart_interval_hours(0);
        save_database(&directory, &restored).expect("save disabled interval");
        let disabled = load_database(&directory).expect("load disabled interval");
        assert_eq!(disabled.auto_restart_interval_hours(), 0);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn legacy_json_is_imported_into_sqlite_and_removed() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-json-import-test-{unique}"));
        std::fs::create_dir_all(&directory).unwrap();
        let mut legacy = Database {
            main_password: "legacy-main-pass".to_owned(),
            dns: "9.9.9.9".to_owned(),
            main_up_bytes: 11,
            main_down_bytes: 12,
            ..Database::default()
        };
        legacy.passwords.insert(
            "legacy-client-pw".to_owned(),
            PasswordEntry {
                ip: "10.66.67.3".to_owned(),
                up_bytes: 13,
                down_bytes: 14,
                ..PasswordEntry::default()
            },
        );
        let legacy_path = directory.join(super::LEGACY_DATABASE_FILE);
        std::fs::write(&legacy_path, serde_json::to_vec(&legacy).unwrap()).unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_password, "legacy-main-pass");
        assert_eq!(restored.main_up_bytes, 11);
        assert!(restored.passwords.contains_key("legacy-client-pw"));
        assert_eq!(restored.passwords["legacy-client-pw"].ip, "10.66.67.3");
        assert!(directory.join(super::DATABASE_FILE).exists());
        assert!(!legacy_path.exists());
        assert!(
            !directory
                .join(super::LEGACY_DATABASE_IMPORTED_FILE)
                .exists()
        );
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn legacy_json_import_preserves_passwords_and_ip() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-json-full-import-{unique}"));
        std::fs::create_dir_all(&directory).unwrap();

        let mut legacy = Database {
            main_password: "old-main-pass-16".to_owned(),
            dns: "77.88.8.8,77.88.8.1".to_owned(),
            main_up_bytes: 900,
            main_down_bytes: 1_800,
            ..Database::default()
        };
        legacy.passwords.insert(
            "client-pass-16-1".to_owned(),
            PasswordEntry {
                ip: "10.66.67.3".to_owned(),
                expires_at: 1_900_000_000,
                name: "first legacy user".to_owned(),
                vk_hashes: "hash-a\nhash-b".to_owned(),
                up_bytes: 11,
                down_bytes: 22,
                dtls_port: 46000,
                wg_port: 51820,
                local_port: 1080,
                ..PasswordEntry::default()
            },
        );
        legacy.passwords.insert(
            "client-pass-16-2".to_owned(),
            PasswordEntry {
                ip: "10.66.67.4".to_owned(),
                is_deactivated: true,
                name: "disabled legacy user".to_owned(),
                up_bytes: 33,
                down_bytes: 44,
                ..PasswordEntry::default()
            },
        );

        let legacy_path = directory.join(super::LEGACY_DATABASE_FILE);
        std::fs::write(&legacy_path, serde_json::to_vec_pretty(&legacy).unwrap()).unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_password, "old-main-pass-16");
        assert_eq!(restored.dns, "77.88.8.8,77.88.8.1");
        assert_eq!(
            (restored.main_up_bytes, restored.main_down_bytes),
            (900, 1_800)
        );

        let first = restored.passwords.get("client-pass-16-1").unwrap();
        assert_eq!(first.ip, "10.66.67.3");
        assert_eq!(first.name, "first legacy user");
        assert_eq!(first.vk_hashes, "hash-a\nhash-b");
        assert_eq!(
            (first.dtls_port, first.wg_port, first.local_port),
            (46000, 51820, 1080)
        );

        let second = restored.passwords.get("client-pass-16-2").unwrap();
        assert!(second.is_deactivated);
        assert_eq!(second.ip, "10.66.67.4");

        assert!(directory.join(super::DATABASE_FILE).exists());
        assert!(!legacy_path.exists());

        let reloaded = load_database(&directory).unwrap();
        assert_eq!(reloaded.passwords.len(), 2);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn load_repairs_stale_passwords_and_assigns_ip() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-binding-repair-{unique}"));
        let mut database = Database {
            main_password: "short".to_owned(),
            ..Database::default()
        };
        database.passwords.insert(
            "old8pass".to_owned(),
            PasswordEntry {
                ip: "".to_owned(),
                ..PasswordEntry::default()
            },
        );
        save_database(&directory, &database).unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_password, "short");
        assert!(restored.passwords.contains_key("old8pass"));
        let entry = &restored.passwords["old8pass"];
        assert_eq!(entry.ip, "10.66.67.3");

        let reloaded = load_database(&directory).unwrap();
        assert_eq!(reloaded.main_password, restored.main_password);
        assert_eq!(reloaded.passwords["old8pass"].ip, "10.66.67.3");
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn existing_sqlite_ignores_legacy_json() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-json-merge-test-{unique}"));
        let mut sqlite = Database {
            main_password: "sqlite-main-16ch".to_owned(),
            main_up_bytes: 50,
            ..Database::default()
        };
        sqlite.passwords.insert(
            "sqlite-client-16".to_owned(),
            PasswordEntry {
                name: "current".to_owned(),
                ip: "10.66.67.10".to_owned(),
                ..PasswordEntry::default()
            },
        );
        save_database(&directory, &sqlite).unwrap();

        let mut legacy = Database {
            main_password: "sqlite-main-16ch".to_owned(),
            main_up_bytes: 10,
            ..Database::default()
        };
        legacy.passwords.insert(
            "legacy-client-16".to_owned(),
            PasswordEntry {
                name: "imported".to_owned(),
                ip: "10.66.67.11".to_owned(),
                ..PasswordEntry::default()
            },
        );
        legacy.passwords.insert(
            "sqlite-client-16".to_owned(),
            PasswordEntry {
                name: "current".to_owned(),
                ip: "10.66.67.10".to_owned(),
                up_bytes: 1,
                ..PasswordEntry::default()
            },
        );
        let legacy_path = directory.join(super::LEGACY_DATABASE_FILE);
        std::fs::write(&legacy_path, serde_json::to_vec(&legacy).unwrap()).unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_password, "sqlite-main-16ch");
        assert_eq!(restored.main_up_bytes, 50);
        assert_eq!(restored.passwords.len(), 1);
        assert_eq!(restored.passwords["sqlite-client-16"].name, "current");
        assert_eq!(restored.passwords["sqlite-client-16"].ip, "10.66.67.10");
        assert!(legacy_path.exists());
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn existing_sqlite_ignores_conflicting_legacy_password() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-json-password-conflict-{unique}"));
        let mut sqlite = Database::default();
        sqlite.passwords.insert(
            "shared-password-".to_owned(),
            PasswordEntry {
                ip: "10.66.67.3".to_owned(),
                ..PasswordEntry::default()
            },
        );
        save_database(&directory, &sqlite).unwrap();

        let mut legacy = Database::default();
        legacy.passwords.insert(
            "shared-password-".to_owned(),
            PasswordEntry {
                ip: "10.66.67.4".to_owned(),
                ..PasswordEntry::default()
            },
        );
        let legacy_path = directory.join(super::LEGACY_DATABASE_FILE);
        std::fs::write(&legacy_path, serde_json::to_vec(&legacy).unwrap()).unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.passwords["shared-password-"].ip, "10.66.67.3");
        assert!(legacy_path.exists());
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn persistence_queue_coalesces_traffic_and_ignores_late_completion() {
        let mut queue = PersistenceQueue::default();
        let mut first = TrafficSnapshot::default();
        first.passwords.insert(
            "first".to_owned(),
            TrafficCounters {
                up_bytes: 1,
                down_bytes: 2,
            },
        );
        let (first_revision, start_first) = queue.submit_traffic(first);
        assert!(start_first);
        let mut second = TrafficSnapshot::default();
        second.passwords.insert(
            "second".to_owned(),
            TrafficCounters {
                up_bytes: 3,
                down_bytes: 4,
            },
        );
        let (second_revision, start_second) = queue.submit_traffic(second);
        assert!(!start_second);
        assert!(second_revision > first_revision);
        let Some((pending_revision, PersistenceUpdate::Traffic(pending))) = queue.take_pending()
        else {
            panic!("expected a merged traffic operation");
        };
        assert_eq!(pending_revision, second_revision);
        assert_eq!(pending.passwords["first"].up_bytes, 1);
        assert_eq!(pending.passwords["second"].down_bytes, 4);

        queue.complete(second_revision, true);
        queue.complete(first_revision, false);
        assert_eq!(queue.processed_revision, second_revision);
        assert_eq!(queue.successful_revision, second_revision);
    }

    #[test]
    fn persistence_snapshot_supersedes_queued_traffic() {
        let mut queue = PersistenceQueue::default();
        let (traffic_revision, _) = queue.submit_traffic(TrafficSnapshot::default());
        let (snapshot_revision, _) = queue.submit_snapshot(Database {
            main_up_bytes: 42,
            ..Database::default()
        });
        assert!(snapshot_revision > traffic_revision);
        let Some((revision, PersistenceUpdate::Snapshot(snapshot))) = queue.take_pending() else {
            panic!("expected latest database snapshot");
        };
        assert_eq!(revision, snapshot_revision);
        assert_eq!(snapshot.main_up_bytes, 42);
    }

    #[tokio::test]
    async fn database_persistence_finishes_with_latest_coalesced_snapshot() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-persistence-test-{unique}"));
        let persistence = DatabasePersistence::new(directory.clone()).unwrap();
        let mut final_revision = 0;
        for mutation in 1..=512 {
            final_revision = persistence.submit(Database {
                main_up_bytes: mutation,
                ..Database::default()
            });
        }
        persistence.wait(final_revision).await.unwrap();
        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_up_bytes, 512);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[tokio::test]
    async fn traffic_persistence_updates_only_changed_counter_rows() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-traffic-test-{unique}"));
        let mut database = Database {
            main_up_bytes: 1,
            main_down_bytes: 2,
            ..Database::default()
        };
        database.passwords.insert(
            "client-password1".to_owned(),
            PasswordEntry {
                ip: "10.66.67.3".to_owned(),
                up_bytes: 3,
                down_bytes: 4,
                ..PasswordEntry::default()
            },
        );
        save_database(&directory, &database).unwrap();

        let persistence = DatabasePersistence::new(directory.clone()).unwrap();
        let mut traffic = TrafficSnapshot {
            main: Some(TrafficCounters {
                up_bytes: 101,
                down_bytes: 102,
            }),
            ..TrafficSnapshot::default()
        };
        traffic.passwords.insert(
            "client-password1".to_owned(),
            TrafficCounters {
                up_bytes: 103,
                down_bytes: 104,
            },
        );
        let revision = persistence.submit_traffic(traffic);
        persistence.wait(revision).await.unwrap();

        let restored = load_database(&directory).unwrap();
        assert_eq!(
            (restored.main_up_bytes, restored.main_down_bytes),
            (101, 102)
        );
        let password = restored.passwords.get("client-password1").unwrap();
        assert_eq!((password.up_bytes, password.down_bytes), (103, 104));
        let _ = std::fs::remove_dir_all(directory);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn concurrent_database_mutations_persist_the_highest_revision() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory =
            std::env::temp_dir().join(format!("csqtt-persistence-stress-test-{unique}"));
        let persistence = DatabasePersistence::new(directory.clone()).unwrap();
        let database = std::sync::Arc::new(tokio::sync::Mutex::new(Database::default()));
        let final_revision = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
        let mut tasks = tokio::task::JoinSet::new();
        for _ in 0..16 {
            let database = database.clone();
            let persistence = persistence.clone();
            let final_revision = final_revision.clone();
            tasks.spawn(async move {
                for _ in 0..256 {
                    let mut database = database.lock().await;
                    database.main_up_bytes = database.main_up_bytes.saturating_add(1);
                    let revision = persistence.submit(database.clone());
                    final_revision.fetch_max(revision, std::sync::atomic::Ordering::AcqRel);
                }
            });
        }
        while let Some(result) = tasks.join_next().await {
            result.unwrap();
        }
        let final_revision = final_revision.load(std::sync::atomic::Ordering::Acquire);
        persistence.wait(final_revision).await.unwrap();
        let restored = load_database(&directory).unwrap();
        assert_eq!(restored.main_up_bytes, 4096);
        let _ = std::fs::remove_dir_all(directory);
    }

    #[tokio::test]
    async fn client_group_vk_settings_persist_across_restarts() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-group-vk-test-{unique}"));
        let persistence = DatabasePersistence::new(directory.clone()).unwrap();
        let mut db = Database::default();
        let group_id = "group-123".to_string();
        db.groups.insert(
            group_id.clone(),
            super::ClientGroup {
                id: group_id.clone(),
                name: "Test Group".to_string(),
                admin_uuid: "admin-uuid-456".to_string(),
                sub_uuid: "sub-uuid-789".to_string(),
                workers: Some(99),
                vk_token: Some("vk1.a.group_custom_token".to_string()),
                vk_hashes: Some("group_hash_1,group_hash_2".to_string()),
                routing_rule_id: String::new(),
            },
        );
        let revision = persistence.submit(db);
        persistence.wait(revision).await.unwrap();

        let restored = load_database(&directory).unwrap();
        let restored_group = restored.groups.get(&group_id).expect("group should exist");
        assert_eq!(restored_group.name, "Test Group");
        assert_eq!(restored_group.admin_uuid, "admin-uuid-456");
        assert_eq!(restored_group.workers, Some(99));
        assert_eq!(
            restored_group.vk_token.as_deref(),
            Some("vk1.a.group_custom_token")
        );
        assert_eq!(
            restored_group.vk_hashes.as_deref(),
            Some("group_hash_1,group_hash_2")
        );
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn test_client_name_uniqueness_and_scope_isolation() {
        let mut db = Database::default();
        db.passwords.insert(
            "pass-root".to_string(),
            PasswordEntry {
                name: "Клиент100".to_string(),
                group_id: "".to_string(),
                ..PasswordEntry::default()
            },
        );
        db.passwords.insert(
            "pass-g1".to_string(),
            PasswordEntry {
                name: "Клиент100".to_string(),
                group_id: "group-1".to_string(),
                ..PasswordEntry::default()
            },
        );
        db.passwords.insert(
            "pass-g2".to_string(),
            PasswordEntry {
                name: "Клиент100".to_string(),
                group_id: "group-2".to_string(),
                ..PasswordEntry::default()
            },
        );

        let root_names = super::existing_client_names_in_scope(&db, "", None);
        assert!(super::is_client_name_taken(&root_names, "Клиент100"));
        assert!(super::is_client_name_taken(&root_names, "клиент100"));
        assert!(!super::is_client_name_taken(&root_names, "Клиент101"));

        let g1_names = super::existing_client_names_in_scope(&db, "group-1", None);
        assert!(super::is_client_name_taken(&g1_names, "Клиент100"));
        let g1_names_excl = super::existing_client_names_in_scope(&db, "group-1", Some("pass-g1"));
        assert!(!super::is_client_name_taken(&g1_names_excl, "Клиент100"));

        let gen_name = super::generate_unique_client_name(&root_names);
        assert!(gen_name.starts_with("Клиент"));
        assert_ne!(gen_name, "Клиент100");
    }

    #[test]
    fn test_random_password_format_and_entropy() {
        use std::collections::HashSet;

        let mut seen = HashSet::new();
        for _ in 0..100 {
            let pass = super::random_password();
            assert_eq!(pass.len(), super::PASSWORD_LEN);
            assert!(pass.chars().all(|c| super::PASS_CHARS.contains(&(c as u8))));
            seen.insert(pass);
        }
        assert_eq!(seen.len(), 100);
    }

    #[test]
    fn repair_migrates_passwords_and_assigns_ip() {
        let mut db = super::Database {
            main_password: "".to_string(),
            ..super::Database::default()
        };
        db.passwords
            .insert("legacy-slot-pw".to_owned(), super::PasswordEntry::default());
        db.passwords
            .insert("".to_owned(), super::PasswordEntry::default());

        assert!(!db.repair_stale_password_bindings().is_empty());
        assert_eq!(db.main_password.len(), super::PASSWORD_LEN);
        assert!(db.main_password.chars().all(|c| c.is_ascii_alphanumeric()));
        let main_sub_uuid = db.main_sub_uuid.clone();
        assert!(!main_sub_uuid.is_empty());

        let legacy_entry = db.passwords.get("legacy-slot-pw").unwrap();
        assert_eq!(legacy_entry.ip, "10.66.67.3");
        assert!(!legacy_entry.sub_uuid.is_empty());

        let (_, slot_entry) = db
            .passwords
            .iter()
            .find(|(k, _)| {
                k.len() == super::PASSWORD_LEN && *k != &db.main_password && *k != "legacy-slot-pw"
            })
            .unwrap();
        assert_eq!(slot_entry.ip, "10.66.67.4");
        assert!(!slot_entry.sub_uuid.is_empty());

        assert!(db.repair_stale_password_bindings().is_empty());
    }

    #[tokio::test]
    async fn test_password_and_ip_database_persistence() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-pass-ip-test-{unique}"));
        let persistence = super::DatabasePersistence::new(directory.clone()).unwrap();
        let mut db = super::Database {
            main_password: "owner12345678901".to_string(),
            ..super::Database::default()
        };
        db.passwords.insert(
            "slot123456789012".to_string(),
            super::PasswordEntry {
                name: "Клиент555".to_string(),
                ip: "10.66.67.55".to_string(),
                ..super::PasswordEntry::default()
            },
        );
        let revision = persistence.submit(db);
        persistence.wait(revision).await.unwrap();

        let restored = super::load_database(&directory).unwrap();
        assert_eq!(restored.main_password, "owner12345678901");
        let slot = restored
            .passwords
            .get("slot123456789012")
            .expect("slot must exist");
        assert_eq!(slot.ip, "10.66.67.55");
        let _ = std::fs::remove_dir_all(directory);
    }

    #[test]
    fn test_legacy_slot_migration_sets_vladilets() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!("csqtt-vladilets-test-{unique}"));
        std::fs::create_dir_all(&directory).unwrap();

        let conn = rusqlite::Connection::open(directory.join("csqtt.db")).unwrap();
        conn.execute_batch(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
             CREATE TABLE passwords (
                 password TEXT PRIMARY KEY,
                 ip TEXT NOT NULL DEFAULT '',
                 expires_at INTEGER NOT NULL DEFAULT 0,
                 down_bytes INTEGER NOT NULL DEFAULT 0,
                 up_bytes INTEGER NOT NULL DEFAULT 0,
                 vk_hash TEXT NOT NULL DEFAULT '',
                 ports TEXT NOT NULL DEFAULT '',
                 is_deactivated INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '',
                 vk_hashes TEXT NOT NULL DEFAULT '',
                 vk_token_enabled INTEGER NOT NULL DEFAULT 0,
                 dtls_port INTEGER NOT NULL DEFAULT 0,
                 wg_port INTEGER NOT NULL DEFAULT 0,
                 local_port INTEGER NOT NULL DEFAULT 0,
                 group_id TEXT NOT NULL DEFAULT '',
                 routing_rule_id TEXT NOT NULL DEFAULT '',
                 sub_uuid TEXT NOT NULL DEFAULT '',
                 workers INTEGER DEFAULT NULL
             );
             INSERT INTO meta (key, value) VALUES ('main_password', 'main123456789012');
             INSERT INTO passwords (password, name) VALUES ('main123456789012', 'Главный пароль');
             INSERT INTO passwords (password, name) VALUES ('client1234567890', 'Клиент');",
        )
        .unwrap();
        drop(conn);

        let db = super::load_database(&directory).unwrap();
        let owner = db
            .passwords
            .get("main123456789012")
            .expect("owner slot exists");
        assert_eq!(owner.name, "Владелец");

        let participant = db
            .passwords
            .get("client1234567890")
            .expect("participant slot exists");
        assert_eq!(participant.ip, "10.66.67.3");
        let _ = std::fs::remove_dir_all(directory);
    }
}
