use std::collections::HashSet;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::SystemTime;

const FAILURE_COOLDOWN_MS: u64 = 60_000;
const HEALTHY_STATE: u64 = 0;

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub struct TurnIpEntry {
    pub host: Arc<str>,
    pub port: Option<Arc<str>>,
    state: AtomicU64,
}

impl TurnIpEntry {
    fn is_healthy(&self) -> bool {
        self.state.load(Ordering::Acquire) == HEALTHY_STATE
    }

    pub fn mark_failed_at(&self, now: u64) {
        self.state.store(now, Ordering::Release);
    }

    pub fn mark_healthy(&self) {
        self.state.store(HEALTHY_STATE, Ordering::Release);
    }

    fn try_revive(&self, now: u64, failed_at: u64) -> bool {
        if now.saturating_sub(failed_at) < FAILURE_COOLDOWN_MS {
            return false;
        }
        self.state
            .compare_exchange(
                failed_at,
                HEALTHY_STATE,
                Ordering::AcqRel,
                Ordering::Acquire,
            )
            .is_ok()
    }
}

pub struct TurnIpPool {
    entries: Vec<TurnIpEntry>,
}

impl TurnIpPool {
    pub fn new(custom_endpoints: Vec<(Arc<str>, Option<Arc<str>>)>, worker_count: usize) -> Self {
        let mut distinct = Vec::new();
        let mut seen = HashSet::new();
        for (host, port) in custom_endpoints {
            if seen.insert(host.clone()) {
                distinct.push((host, port));
            }
        }
        if worker_count > 0 && worker_count < distinct.len() {
            distinct.truncate(worker_count);
        }
        let entries = distinct
            .into_iter()
            .map(|(host, port)| TurnIpEntry {
                host,
                port,
                state: AtomicU64::new(HEALTHY_STATE),
            })
            .collect();
        Self { entries }
    }

    pub fn is_custom(&self) -> bool {
        !self.entries.is_empty()
    }

    pub fn select_target(&self, worker_id: usize) -> Option<(Arc<str>, Option<Arc<str>>)> {
        if !self.is_custom() {
            return None;
        }

        let now = current_time_ms();
        let healthy_entries: Vec<&TurnIpEntry> = self
            .entries
            .iter()
            .filter(|e| {
                if e.is_healthy() {
                    true
                } else {
                    let failed_at = e.state.load(Ordering::Acquire);
                    failed_at != HEALTHY_STATE && e.try_revive(now, failed_at)
                }
            })
            .collect();

        if healthy_entries.is_empty() {
            return None;
        }

        let index = (worker_id.saturating_sub(1)) % healthy_entries.len();
        let chosen = healthy_entries[index];
        Some((chosen.host.clone(), chosen.port.clone()))
    }

    pub fn mark_failed(&self, host: &str) {
        let now = current_time_ms();
        for entry in &self.entries {
            if entry.host.as_ref() == host {
                entry.mark_failed_at(now);
            }
        }
    }

    pub fn mark_healthy(&self, host: &str) {
        for entry in &self.entries {
            if entry.host.as_ref() == host {
                entry.mark_healthy();
            }
        }
    }
}

fn split_host_port(token: &str) -> Option<(&str, Option<&str>)> {
    if let Some(bracketed) = token.strip_prefix('[') {
        let (host, tail) = bracketed.split_once(']')?;
        let port = tail.strip_prefix(':');
        return (tail.is_empty() || port.is_some()).then_some((host, port));
    }
    match token.split_once(':') {
        Some((host, port)) if !port.contains(':') => Some((host, Some(port))),
        _ => Some((token, None)),
    }
}

pub fn parse_custom_turn_ips(raw: &str) -> Vec<(Arc<str>, Option<Arc<str>>)> {
    let mut endpoints = Vec::new();
    let mut seen = HashSet::new();
    for token in raw.split([',', '\n', ';']) {
        let token = token.trim();
        if token.is_empty() {
            continue;
        }
        let Some((host, port)) = split_host_port(token) else {
            continue;
        };
        let host = host.trim();
        if host.is_empty() {
            continue;
        }
        let port = port.map(str::trim).filter(|port| !port.is_empty());
        if seen.insert(host.to_string()) {
            endpoints.push((Arc::from(host), port.map(Arc::from)));
        }
    }
    endpoints
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_custom_turn_ips_parses_all() {
        let mut raw = String::new();
        for i in 1..=20 {
            raw.push_str(&format!("10.0.0.{i}, "));
        }
        let parsed = parse_custom_turn_ips(&raw);
        assert_eq!(parsed.len(), 20);
        assert_eq!(parsed[0].0.as_ref(), "10.0.0.1");
        assert_eq!(parsed[19].0.as_ref(), "10.0.0.20");
    }

    #[test]
    fn parse_custom_turn_ips_leaves_missing_port_to_the_caller() {
        let parsed = parse_custom_turn_ips("1.1.1.1, 2.2.2.2:5349");
        assert_eq!(parsed[0].1, None);
        assert_eq!(parsed[1].1.as_deref(), Some("5349"));
    }

    #[test]
    fn parse_custom_turn_ips_handles_ipv6_literals() {
        let parsed = parse_custom_turn_ips("[2001:db8::1]:3478, [2001:db8::2], 2001:db8::3");
        assert_eq!(parsed[0].0.as_ref(), "2001:db8::1");
        assert_eq!(parsed[0].1.as_deref(), Some("3478"));
        assert_eq!(parsed[1].0.as_ref(), "2001:db8::2");
        assert_eq!(parsed[1].1, None);
        assert_eq!(parsed[2].0.as_ref(), "2001:db8::3");
        assert_eq!(parsed[2].1, None);
    }

    #[test]
    fn distributes_evenly_across_healthy_ips() {
        let pool = TurnIpPool::new(parse_custom_turn_ips("1.1.1.1, 2.2.2.2"), 0);
        let w1 = pool.select_target(1).unwrap();
        let w2 = pool.select_target(2).unwrap();
        let w3 = pool.select_target(3).unwrap();
        let w4 = pool.select_target(4).unwrap();
        assert_eq!(w1.0.as_ref(), "1.1.1.1");
        assert_eq!(w2.0.as_ref(), "2.2.2.2");
        assert_eq!(w3.0.as_ref(), "1.1.1.1");
        assert_eq!(w4.0.as_ref(), "2.2.2.2");
    }

    #[test]
    fn selects_unique_endpoints_up_to_worker_count() {
        let mut raw = String::new();
        for i in 1..=64 {
            raw.push_str(&format!("10.0.0.{i}, "));
        }
        let pool = TurnIpPool::new(parse_custom_turn_ips(&raw), 18);
        assert_eq!(pool.entries.len(), 18);
        let mut chosen = HashSet::new();
        for w in 1..=18 {
            let target = pool.select_target(w).unwrap();
            assert!(chosen.insert(target.0.to_string()));
        }
        assert_eq!(chosen.len(), 18);
    }

    #[test]
    fn redistributes_on_failure() {
        let pool = TurnIpPool::new(parse_custom_turn_ips("1.1.1.1, 2.2.2.2"), 0);
        pool.mark_failed("1.1.1.1");
        let w1 = pool.select_target(1).unwrap();
        let w2 = pool.select_target(2).unwrap();
        assert_eq!(w1.0.as_ref(), "2.2.2.2");
        assert_eq!(w2.0.as_ref(), "2.2.2.2");
    }

    #[test]
    fn fallback_to_none_when_all_fail() {
        let pool = TurnIpPool::new(parse_custom_turn_ips("1.1.1.1, 2.2.2.2"), 0);
        pool.mark_failed("1.1.1.1");
        pool.mark_failed("2.2.2.2");
        assert!(pool.select_target(1).is_none());
        assert!(pool.select_target(2).is_none());
    }

    #[test]
    fn auto_mode_returns_none_for_workers() {
        let pool = TurnIpPool::new(Vec::new(), 0);
        assert!(pool.select_target(1).is_none());
    }

    #[test]
    fn mark_healthy_restores_endpoint() {
        let pool = TurnIpPool::new(parse_custom_turn_ips("1.1.1.1, 2.2.2.2"), 0);
        pool.mark_failed("1.1.1.1");
        assert_eq!(pool.select_target(1).unwrap().0.as_ref(), "2.2.2.2");
        pool.mark_healthy("1.1.1.1");
        assert_eq!(pool.select_target(1).unwrap().0.as_ref(), "1.1.1.1");
    }
}
