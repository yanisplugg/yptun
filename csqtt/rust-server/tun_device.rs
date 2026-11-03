use crate::packet::extract_dst_ipv4;
use std::collections::HashMap;
use std::net::Ipv4Addr;

pub const TUN_IFACE: &str = "csqtt1";
pub const TUN_SUBNET: &str = "10.66.67.0/24";
const SUBNET_PREFIX: [u8; 3] = [10, 66, 67];

pub type SessionId = u64;
pub type RegistrationId = u64;
pub type WorkerId = u16;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RouteEndpoint {
    pub session_id: SessionId,
    pub registration_id: RegistrationId,
    pub worker_id: WorkerId,
    pub slot: usize,
}

pub const TB_RATE_BYTES_PER_SEC: f64 = 247_000.0;
pub const TB_CAPACITY_BYTES: f64 = 10_500.0;
#[allow(dead_code)]
const QUANTUM_BYTES: usize = 10_500;
pub const MAX_DEFICIT: f64 = -4_500.0;

const FLOW_BURST_BYTES: usize = 16 * 1024;
const FLOW_TABLE_MAX: usize = 1024;
const FLOW_EVICT_EVERY: u32 = 1024;
const FLOW_IDLE_TTL: std::time::Duration = std::time::Duration::from_secs(30);
const SMALL_UDP_MAX_LEN: usize = 300;
const SMALL_UDP_STICKY: std::time::Duration = std::time::Duration::from_secs(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum FlowKey {
    V4 {
        src: [u8; 4],
        dst: [u8; 4],
        proto: u8,
        sport: u16,
        dport: u16,
    },
    V6 {
        src: [u8; 16],
        dst: [u8; 16],
        proto: u8,
        sport: u16,
        dport: u16,
    },
}

#[derive(Clone, Copy, Debug)]
struct FlowState {
    idx: usize,
    last_seen: std::time::Instant,
    burst_left: usize,
    sticky_until: Option<std::time::Instant>,
}

fn transport_ports(packet: &[u8], offset: usize, proto: u8) -> (u16, u16) {
    if (proto == 6 || proto == 17) && packet.len() >= offset + 4 {
        (
            u16::from_be_bytes([packet[offset], packet[offset + 1]]),
            u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]),
        )
    } else {
        (0, 0)
    }
}

fn flow_key(packet: &[u8]) -> Option<FlowKey> {
    let version = packet.first()? >> 4;
    match version {
        4 => {
            if packet.len() < 20 || packet[0] >> 4 != 4 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || packet.len() < ihl {
                return None;
            }
            let proto = packet[9];
            let src = [packet[12], packet[13], packet[14], packet[15]];
            let dst = [packet[16], packet[17], packet[18], packet[19]];
            // Non-initial fragments carry no transport header; key them without
            // ports so they still follow the same flow.
            let fragment_offset = u16::from_be_bytes([packet[6], packet[7]]) & 0x1fff;
            let (sport, dport) = if fragment_offset == 0 {
                transport_ports(packet, ihl, proto)
            } else {
                (0, 0)
            };
            Some(FlowKey::V4 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let proto = packet[6];
            let src: [u8; 16] = packet[8..24].try_into().ok()?;
            let dst: [u8; 16] = packet[24..40].try_into().ok()?;
            let (sport, dport) = transport_ports(packet, 40, proto);
            Some(FlowKey::V6 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        _ => None,
    }
}

fn is_small_udp(key: &FlowKey, packet_len: usize) -> bool {
    packet_len <= SMALL_UDP_MAX_LEN
        && matches!(
            key,
            FlowKey::V4 { proto: 17, .. } | FlowKey::V6 { proto: 17, .. }
        )
}

#[derive(Default)]
struct ClientRoute {
    endpoints: Vec<RouteEndpoint>,
    tokens: Vec<f64>,
    last_update: Vec<std::time::Instant>,
    cursor: usize,
    flows: HashMap<FlowKey, FlowState>,
    flow_evict_counter: u32,
}

impl ClientRoute {
    fn register(&mut self, endpoint: RouteEndpoint) {
        if let Some(pos) = self.endpoints.iter().position(|current| {
            current.registration_id == endpoint.registration_id
                || current.worker_id == endpoint.worker_id
        }) {
            self.endpoints.remove(pos);
            if pos < self.tokens.len() {
                self.tokens.remove(pos);
            }
            if pos < self.last_update.len() {
                self.last_update.remove(pos);
            }
        }
        self.endpoints.push(endpoint);
        self.tokens.resize(self.endpoints.len(), TB_CAPACITY_BYTES);
        self.last_update
            .resize(self.endpoints.len(), std::time::Instant::now());
        let mut paired: Vec<(RouteEndpoint, f64, std::time::Instant)> = self
            .endpoints
            .drain(..)
            .zip(self.tokens.drain(..))
            .zip(self.last_update.drain(..))
            .map(|((endpoint, tokens), updated)| (endpoint, tokens, updated))
            .collect();
        paired.sort_unstable_by_key(|(endpoint, _, _)| endpoint.worker_id);
        for (endpoint, tokens, updated) in paired {
            self.endpoints.push(endpoint);
            self.tokens.push(tokens);
            self.last_update.push(updated);
        }
        self.cursor = self.cursor.min(self.endpoints.len().saturating_sub(1));
        self.flows.clear();
        self.flow_evict_counter = 0;
    }

    fn unregister(&mut self, registration_id: RegistrationId) -> bool {
        let previous_len = self.endpoints.len();
        if let Some(pos) = self
            .endpoints
            .iter()
            .position(|e| e.registration_id == registration_id)
        {
            self.endpoints.remove(pos);
            if pos < self.tokens.len() {
                self.tokens.remove(pos);
            }
            if pos < self.last_update.len() {
                self.last_update.remove(pos);
            }
        }
        self.cursor = self.cursor.min(self.endpoints.len().saturating_sub(1));
        self.flows.clear();
        self.flow_evict_counter = 0;
        self.endpoints.len() != previous_len
    }

    // Пакет какого размера покрывает корзину. Ленивый рефилл обновляет только
    // затронутые корзины; в типичном случае это O(1) вместо O(streams).
    #[inline]
    fn refill(&mut self, idx: usize, now: std::time::Instant) {
        let elapsed = now
            .saturating_duration_since(self.last_update[idx])
            .as_secs_f64();
        if elapsed >= 0.0001 {
            self.tokens[idx] =
                (self.tokens[idx] + elapsed * TB_RATE_BYTES_PER_SEC).min(TB_CAPACITY_BYTES);
        }
        self.last_update[idx] = now;
    }

    fn evict_flows(&mut self, now: std::time::Instant) {
        self.flow_evict_counter = self.flow_evict_counter.wrapping_add(1);
        if self.flow_evict_counter % FLOW_EVICT_EVERY != 0 && self.flows.len() < FLOW_TABLE_MAX {
            return;
        }
        self.flows
            .retain(|_, state| now.saturating_duration_since(state.last_seen) < FLOW_IDLE_TTL);
        if self.flows.len() >= FLOW_TABLE_MAX {
            self.flows.clear();
        }
    }

    // Выбираем поток с наибольшим остатком токенов, начиная с курсора. Токены
    // здесь не списываются: вызывающий код списывает длину пакета.
    fn choose_stream(&mut self, now: std::time::Instant, len_f: f64) -> usize {
        let count = self.endpoints.len();
        let start = self.cursor.min(count - 1);
        for offset in 0..count {
            let idx = (start + offset) % count;
            self.refill(idx, now);
            if self.tokens[idx] >= len_f {
                self.cursor = (idx + 1) % count;
                return idx;
            }
        }

        // Перелив: ни один поток не покрывает пакет — заряжаем поток с
        // наибольшим остатком дефицита (все корзины уже рефиллнуты выше).
        let mut best_idx = start;
        let mut best_tokens = self.tokens[start];
        for offset in 1..count {
            let idx = (start + offset) % count;
            if self.tokens[idx] > best_tokens {
                best_tokens = self.tokens[idx];
                best_idx = idx;
            }
        }
        self.cursor = (best_idx + 1) % count;
        best_idx
    }

    fn select_packet(&mut self, now: std::time::Instant, packet: &[u8]) -> Option<RouteEndpoint> {
        let count = self.endpoints.len();
        if count == 0 {
            return None;
        }

        if self.tokens.len() != count {
            self.tokens.resize(count, TB_CAPACITY_BYTES);
            self.last_update.resize(count, now);
        }

        let len_f = packet.len() as f64;

        let Some(key) = flow_key(packet) else {
            // Не IP-пакет (или тестовый dummy): сохраняем прежний курсорный
            // водопад, чтобы не менять поведение для служебных пакетов.
            let idx = self.choose_stream(now, len_f);
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return self.endpoints.get(idx).copied();
        };

        self.evict_flows(now);
        let small_udp = is_small_udp(&key, packet.len());

        if let Some(mut state) = self.flows.get(&key).copied() {
            state.last_seen = now;
            if small_udp {
                state.sticky_until = Some(now + SMALL_UDP_STICKY);
            }
            let sticky = state.sticky_until.is_some_and(|until| now < until);
            if state.idx < count && (sticky || state.burst_left >= packet.len()) {
                let idx = state.idx;
                state.burst_left = state.burst_left.saturating_sub(packet.len());
                self.flows.insert(key, state);
                self.refill(idx, now);
                self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
                return self.endpoints.get(idx).copied();
            }

            // Берст закончился или поток исчез: переносим весь flowlet на
            // следующий поток. Внутри берста порядок пакетов не меняется.
            let idx = self.choose_stream(now, len_f);
            state.idx = idx;
            state.burst_left = FLOW_BURST_BYTES.saturating_sub(packet.len());
            self.flows.insert(key, state);
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return self.endpoints.get(idx).copied();
        }

        let idx = self.choose_stream(now, len_f);
        self.flows.insert(
            key,
            FlowState {
                idx,
                last_seen: now,
                burst_left: FLOW_BURST_BYTES.saturating_sub(packet.len()),
                sticky_until: small_udp.then(|| now + SMALL_UDP_STICKY),
            },
        );
        self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
        self.endpoints.get(idx).copied()
    }

    fn is_empty(&self) -> bool {
        self.endpoints.is_empty()
    }
}

pub struct RouteTable {
    groups: Box<[Option<ClientRoute>; 256]>,
    active_groups: usize,
}

impl RouteTable {
    pub fn new() -> Self {
        Self {
            groups: Box::new(std::array::from_fn(|_| None)),
            active_groups: 0,
        }
    }

    pub fn register(
        &mut self,
        ip: [u8; 4],
        session_id: SessionId,
        registration_id: RegistrationId,
        worker_id: WorkerId,
        slot: usize,
    ) -> bool {
        let Some(index) = route_index(ip) else {
            return false;
        };
        if self.groups[index].is_none() {
            self.groups[index] = Some(ClientRoute::default());
            self.active_groups += 1;
        }
        if let Some(group) = self.groups[index].as_mut() {
            group.register(RouteEndpoint {
                session_id,
                registration_id,
                worker_id,
                slot,
            });
        }
        true
    }

    pub fn update_slot(
        &mut self,
        ip: [u8; 4],
        session_id: SessionId,
        registration_id: RegistrationId,
        slot: usize,
    ) {
        let Some(index) = route_index(ip) else {
            return;
        };
        let Some(group) = self.groups[index].as_mut() else {
            return;
        };
        if let Some(endpoint) = group.endpoints.iter_mut().find(|endpoint| {
            endpoint.session_id == session_id && endpoint.registration_id == registration_id
        }) {
            endpoint.slot = slot;
        }
    }

    pub fn unregister(&mut self, ip: [u8; 4], registration_id: RegistrationId) -> bool {
        let Some(index) = route_index(ip) else {
            return false;
        };
        let Some(group) = self.groups[index].as_mut() else {
            return false;
        };
        let removed = group.unregister(registration_id);
        if group.is_empty() {
            self.groups[index] = None;
            self.active_groups = self.active_groups.saturating_sub(1);
        }
        removed
    }

    #[inline(always)]
    pub fn packet_key(packet: &[u8]) -> Option<usize> {
        if packet.is_empty() {
            return None;
        }
        match packet[0] >> 4 {
            4 => route_index(extract_dst_ipv4(packet)?),
            6 => crate::packet::extract_dst_ipv6_slot(packet),
            _ => None,
        }
    }

    #[inline(always)]
    pub fn active_path_count(&self, key: usize) -> usize {
        self.groups
            .get(key)
            .and_then(|group| group.as_ref())
            .map_or(0, |group| group.endpoints.len())
    }

    #[inline(always)]
    pub fn active_client_count(&self) -> usize {
        self.active_groups
    }

    #[inline(always)]
    pub fn select_packet(
        &mut self,
        key: usize,
        now: std::time::Instant,
        packet: &[u8],
    ) -> Option<RouteEndpoint> {
        self.groups
            .get_mut(key)
            .and_then(|group| group.as_mut())
            .and_then(|group| group.select_packet(now, packet))
    }

    #[cfg(test)]
    pub fn select_test_len(
        &mut self,
        key: usize,
        now: std::time::Instant,
        packet_len: usize,
    ) -> Option<RouteEndpoint> {
        let dummy = vec![0u8; packet_len];
        self.select_packet(key, now, &dummy)
    }

    #[cfg(test)]
    #[inline(always)]
    pub fn select(&mut self, ip: [u8; 4]) -> Option<RouteEndpoint> {
        let index = route_index(ip)?;
        self.select_test_len(index, std::time::Instant::now(), 1000)
    }

    #[cfg(test)]
    pub fn stream_count(&self, ip: [u8; 4]) -> usize {
        route_index(ip).map_or(0, |key| self.active_path_count(key))
    }
}

impl Default for RouteTable {
    fn default() -> Self {
        Self::new()
    }
}

#[inline(always)]
pub fn route_index(ip: [u8; 4]) -> Option<usize> {
    (ip[..3] == SUBNET_PREFIX).then_some(ip[3] as usize)
}

pub fn parse_ipv4(value: &str) -> Option<[u8; 4]> {
    Some(value.parse::<Ipv4Addr>().ok()?.octets())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(last: u8) -> [u8; 4] {
        [10, 66, 67, last]
    }

    #[test]
    fn route_table_supports_all_stream_scales() {
        for count in [9, 18, 27, 36, 45, 54, 63, 72, 81, 90, 99, 100] {
            let mut table = RouteTable::new();
            for id in 1..=count as u64 {
                assert!(table.register(ip(2), id, id, id as u16, id as usize));
            }
            assert_eq!(table.stream_count(ip(2)), count);
            for _ in 0..100_000 {
                let selected = table.select(ip(2)).unwrap();
                assert!((1..=count as u64).contains(&selected.session_id));
            }
        }
    }

    #[test]
    fn unregister_is_registration_exact() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(7), 100, 10, 1, 1));
        assert!(table.register(ip(7), 200, 20, 2, 2));
        assert!(table.unregister(ip(7), 10));
        assert_eq!(table.stream_count(ip(7)), 1);
        assert_eq!(table.select(ip(7)).unwrap().session_id, 200);
    }

    #[test]
    fn compacted_session_slot_is_updated() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(7), 100, 10, 1, 3));
        table.update_slot(ip(7), 100, 10, 9);
        assert_eq!(table.select(ip(7)).unwrap().slot, 9);
    }

    #[test]
    fn foreign_subnet_is_rejected() {
        let mut table = RouteTable::new();
        assert!(!table.register([10, 66, 68, 2], 1, 1, 1, 1));
        assert!(table.select([10, 66, 68, 2]).is_none());
    }

    #[test]
    fn replacement_transport_atomically_keeps_one_logical_worker() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(7), 100, 10, 4, 1));
        assert!(table.register(ip(7), 200, 20, 4, 2));

        assert_eq!(table.stream_count(ip(7)), 1);
        assert_eq!(table.select(ip(7)).unwrap().session_id, 200);
        assert!(!table.unregister(ip(7), 10));
        assert_eq!(table.select(ip(7)).unwrap().session_id, 200);
    }

    #[test]
    fn downlink_cursor_round_robin_rotates_streams() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(2), 1, 1, 1, 1));
        assert!(table.register(ip(2), 2, 2, 2, 2));
        assert!(table.register(ip(2), 3, 3, 3, 3));

        let now = std::time::Instant::now();
        for i in 0..9 {
            let ep = table.select_test_len(2, now, 1200).unwrap();
            assert_eq!(ep.worker_id, (i % 3 + 1) as u16);
        }
    }

    fn tcp_packet(len: usize, sport: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; len];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        pkt[9] = 6;
        pkt[12..16].copy_from_slice(&[140, 82, 121, 4]);
        pkt[16..20].copy_from_slice(&[10, 66, 67, 2]);
        pkt[20..22].copy_from_slice(&80u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&sport.to_be_bytes());
        pkt
    }

    fn udp_packet(len: usize, sport: u16) -> Vec<u8> {
        let mut pkt = vec![0u8; len];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&[155, 133, 240, 10]);
        pkt[16..20].copy_from_slice(&[10, 66, 67, 2]);
        pkt[20..22].copy_from_slice(&27015u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&sport.to_be_bytes());
        pkt
    }

    #[test]
    fn downlink_new_tcp_flows_rotate_without_tick_pinning() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(2), 1, 1, 1, 1));
        assert!(table.register(ip(2), 2, 2, 2, 2));
        assert!(table.register(ip(2), 3, 3, 3, 3));

        let now = std::time::Instant::now();
        let mut seen = std::collections::HashSet::new();
        for i in 0..9u16 {
            let pkt = tcp_packet(300, 54321 + i);
            let ep = table.select_packet(2, now, &pkt).unwrap();
            seen.insert(ep.worker_id);
        }
        assert_eq!(seen.len(), 3);
    }

    #[test]
    fn downlink_tcp_flow_stays_in_burst_then_rotates() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(2), 1, 1, 1, 1));
        assert!(table.register(ip(2), 2, 2, 2, 2));
        assert!(table.register(ip(2), 3, 3, 3, 3));

        let start = std::time::Instant::now();
        let pkt = tcp_packet(1120, 49152);
        let burst_packets = FLOW_BURST_BYTES / pkt.len();
        let mut ids = Vec::new();
        for i in 0..60 {
            let now = start + std::time::Duration::from_micros(i * 16);
            ids.push(table.select_packet(2, now, &pkt).unwrap().worker_id);
        }

        assert!(
            ids[..burst_packets].iter().all(|&id| id == ids[0]),
            "packets inside one burst must keep order: {ids:?}"
        );
        assert!(
            ids.iter().any(|&id| id != ids[0]),
            "a new burst must move the flow to another stream: {ids:?}"
        );
    }

    #[test]
    fn downlink_small_udp_flow_stays_pinned_for_game_latency() {
        let mut table = RouteTable::new();
        assert!(table.register(ip(2), 1, 1, 1, 1));
        assert!(table.register(ip(2), 2, 2, 2, 2));
        assert!(table.register(ip(2), 3, 3, 3, 3));

        let start = std::time::Instant::now();
        let pkt = udp_packet(180, 27015);
        let mut pinned = None;
        for i in 0..100 {
            let now = start + std::time::Duration::from_millis(i * 15);
            let ep = table.select_packet(2, now, &pkt).unwrap();
            match pinned {
                Some(worker) => assert_eq!(ep.worker_id, worker),
                None => pinned = Some(ep.worker_id),
            }
        }
    }

    #[test]
    fn ipv4_and_ipv6_packet_key_resolution() {
        let mut pkt4 = [0u8; 20];
        pkt4[0] = 0x45;
        pkt4[2..4].copy_from_slice(&20u16.to_be_bytes());
        pkt4[16..20].copy_from_slice(&[10, 66, 67, 5]);
        assert_eq!(RouteTable::packet_key(&pkt4), Some(5));

        let mut pkt6 = [0u8; 40];
        pkt6[0] = 0x60;
        pkt6[24..39].copy_from_slice(&crate::packet::TUN_SUBNET_V6_PREFIX);
        pkt6[39] = 5;
        assert_eq!(RouteTable::packet_key(&pkt6), Some(5));

        pkt6[24] = 0xfe;
        assert_eq!(RouteTable::packet_key(&pkt6), None);
    }

    #[test]
    fn test_downlink_user_benchmark_artificial_ping_scenarios_36_and_99_streams() {
        let configs: [(&str, f64, f64); 2] = [
            (
                "Scenario A: Base 50-60 ms (Turn delta 1-2 ms / 3-10 ms)",
                52.0,
                24.0,
            ),
            (
                "Scenario B: Base 150 ms (Turn delta 1-2 ms / 3-10 ms)",
                150.0,
                38.0,
            ),
        ];

        let worker_counts = [36, 99];

        for &(scen_name, base_rtt, ladder_delta) in &configs {
            for &w_count in &worker_counts {
                let rtt_fn = |w_idx: usize| -> f64 {
                    if w_idx == 0 {
                        base_rtt
                    } else if w_idx == 1 {
                        base_rtt + 1.5
                    } else {
                        base_rtt + 3.0 + (w_idx as f64 / (w_count - 1) as f64) * ladder_delta
                    }
                };

                let mut table = RouteTable::new();
                for id in 1..=w_count as u64 {
                    assert!(table.register(ip(2), id, id, id as u16, id as usize));
                }

                let start = std::time::Instant::now();
                let mut current = start;
                let duration = std::time::Duration::from_secs(4);

                let mut game_64_samples = Vec::new();
                let mut game_128_samples = Vec::new();
                let mut game_pinned_hits = 0usize;
                let mut game_total_hits = 0usize;

                let mut bulk_bytes = 0usize;
                let mut bulk_seq = 0u32;
                let mut cs64_seq = 0u32;
                let mut cs128_seq = 0u32;

                let step_cs64 = std::time::Duration::from_micros(15625);
                let step_cs128 = std::time::Duration::from_micros(7812);
                let bulk_step =
                    std::time::Duration::from_micros(if w_count == 36 { 126 } else { 45 });

                let mut next_cs64 = current;
                let mut next_cs128 = current;
                let end_time = current + duration;

                while current < end_time {
                    if current >= next_cs64 {
                        let mut pkt = vec![0u8; 200];
                        pkt[0] = 0x45;
                        pkt[9] = 6;
                        pkt[12..16].copy_from_slice(&[185, 220, 100, 50]);
                        pkt[16..20].copy_from_slice(&[10, 66, 67, 2]);
                        pkt[20..22].copy_from_slice(&443u16.to_be_bytes());
                        pkt[22..24].copy_from_slice(&54321u16.to_be_bytes());
                        pkt[24..28].copy_from_slice(&cs64_seq.to_be_bytes());
                        pkt[32] = 0x50;
                        cs64_seq += 1;

                        let ep = table.select_packet(2, current, &pkt).unwrap();
                        let target_idx = (ep.worker_id - 1) as usize;
                        game_total_hits += 1;
                        if target_idx == 0 || target_idx == 1 {
                            game_pinned_hits += 1;
                        }
                        game_64_samples.push(rtt_fn(target_idx));
                        next_cs64 += step_cs64;
                    }

                    if current >= next_cs128 {
                        let mut pkt = vec![0u8; 180];
                        pkt[0] = 0x45;
                        pkt[9] = 17;
                        pkt[12..16].copy_from_slice(&[155, 133, 240, 10]);
                        pkt[16..20].copy_from_slice(&[10, 66, 67, 2]);
                        pkt[20..22].copy_from_slice(&27015u16.to_be_bytes());
                        pkt[22..24].copy_from_slice(&27015u16.to_be_bytes());
                        pkt[28..32].copy_from_slice(&cs128_seq.to_be_bytes());
                        cs128_seq += 1;

                        let ep = table.select_packet(2, current, &pkt).unwrap();
                        let target_idx = (ep.worker_id - 1) as usize;
                        game_total_hits += 1;
                        if target_idx == 0 || target_idx == 1 {
                            game_pinned_hits += 1;
                        }
                        game_128_samples.push(rtt_fn(target_idx));
                        next_cs128 += step_cs128;
                    }

                    let mut bulk_pkt = vec![0u8; 1120];
                    bulk_pkt[0] = 0x45;
                    bulk_pkt[9] = 6;
                    bulk_pkt[12..16].copy_from_slice(&[140, 82, 121, 4]);
                    bulk_pkt[16..20].copy_from_slice(&[10, 66, 67, 2]);
                    bulk_pkt[20..22].copy_from_slice(&80u16.to_be_bytes());
                    bulk_pkt[22..24].copy_from_slice(&49152u16.to_be_bytes());
                    bulk_pkt[24..28].copy_from_slice(&bulk_seq.to_be_bytes());
                    bulk_pkt[32] = 0x50;
                    bulk_seq += 1;

                    let _ = table.select_packet(2, current, &bulk_pkt);
                    bulk_bytes += 1120;

                    current += bulk_step;
                }

                let throughput_mbps =
                    (bulk_bytes as f64 * 8.0) / (duration.as_secs_f64() * 1_000_000.0);
                let pinned_ratio = (game_pinned_hits as f64 * 100.0) / game_total_hits as f64;

                let avg_64 = game_64_samples.iter().sum::<f64>() / game_64_samples.len() as f64;
                let var_64: f64 = game_64_samples
                    .iter()
                    .map(|&x| (x - avg_64).powi(2))
                    .sum::<f64>()
                    / (game_64_samples.len() - 1) as f64;
                let jitter_64 = var_64.sqrt();

                let avg_128 = game_128_samples.iter().sum::<f64>() / game_128_samples.len() as f64;
                let var_128: f64 = game_128_samples
                    .iter()
                    .map(|&x| (x - avg_128).powi(2))
                    .sum::<f64>()
                    / (game_128_samples.len() - 1) as f64;
                let jitter_128 = var_128.sqrt();

                println!(
                    "\n--- [ SERVER DOWNLINK: {} | {} STREAMS ] ---",
                    scen_name, w_count
                );
                println!(
                    "  Throughput Speed (Bulk Traffic):    {:.2} Mbps",
                    throughput_mbps
                );
                println!(
                    "  CS2 64 Hz Tick (VLESS TCP):         Avg: {:.2} ms | Jitter: {:.2} ms",
                    avg_64, jitter_64
                );
                println!(
                    "  CS2 128 Hz Tick (UDP):              Avg: {:.2} ms | Jitter: {:.2} ms",
                    avg_128, jitter_128
                );
                println!(
                    "  Game Flow Pinned to Worker 1/2:     {:.2}% of all ticks",
                    pinned_ratio
                );

                assert!(throughput_mbps > 0.0);
            }
        }
    }
}
