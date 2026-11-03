#[allow(dead_code)]
pub const PATH_ECHO_PREFIX: &[u8] = b"\xffCSQTT_PATH_ECHO_V1";

#[cfg(test)]
pub const CLIENT_GETCONF_AUDIO_FIXTURE: &str = "b06f77881122334410203040bede0002325566775199aa005c468ea9f0329817714cb0d49b032855ae1b18c3ee81bf342bdd58cd166bdf23a16575b0ca29cb53618501b45b3ae4bb1fda630bb39ce45dbf5dde3073f99f87f2da0010b786331f3d88ba24fe8cf3be";

pub const CSQTT_FRAG_MAGIC: u8 = 0xCF;
pub const MAX_FRAG_PAYLOAD: usize = 1272;
pub const FRAG_HEADER_LEN: usize = 8;
pub const MAX_PACKET_LEN: usize = 2048;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FragHeader {
    pub frag_idx: u8,
    pub total_frags: u8,
    pub packet_id: u16,
    pub total_len: u16,
}

impl FragHeader {
    pub fn encode(&self, out: &mut [u8]) -> bool {
        if out.len() < FRAG_HEADER_LEN {
            return false;
        }
        out[0] = CSQTT_FRAG_MAGIC;
        out[1] = self.frag_idx;
        out[2] = self.total_frags;
        out[3] = 0;
        out[4..6].copy_from_slice(&self.packet_id.to_be_bytes());
        out[6..8].copy_from_slice(&self.total_len.to_be_bytes());
        true
    }

    pub fn decode(src: &[u8]) -> Option<Self> {
        if src.len() < FRAG_HEADER_LEN || src[0] != CSQTT_FRAG_MAGIC {
            return None;
        }
        let frag_idx = src[1];
        let total_frags = src[2];
        if total_frags == 0 || total_frags > 32 || frag_idx >= total_frags {
            return None;
        }
        let packet_id = u16::from_be_bytes([src[4], src[5]]);
        let total_len = u16::from_be_bytes([src[6], src[7]]);
        let total_len_usize = usize::from(total_len);
        let frags_max_len = usize::from(total_frags) * MAX_FRAG_PAYLOAD;
        let frags_min_len = frags_max_len - MAX_FRAG_PAYLOAD;
        if total_len_usize == 0
            || total_len_usize > MAX_PACKET_LEN
            || total_len_usize <= frags_min_len
            || total_len_usize > frags_max_len
        {
            return None;
        }
        Some(Self {
            frag_idx,
            total_frags,
            packet_id,
            total_len,
        })
    }
}

pub fn fragment_packet<F: FnMut(&[u8])>(packet_id: u16, packet: &[u8], mut emit: F) {
    let total_len = packet.len();
    if total_len == 0 || total_len > MAX_PACKET_LEN {
        return;
    }
    let Ok(total_frags) = u8::try_from(total_len.div_ceil(MAX_FRAG_PAYLOAD)) else {
        return;
    };
    if total_frags == 0 || total_frags > 32 {
        return;
    }
    let mut scratch = [0u8; FRAG_HEADER_LEN + MAX_FRAG_PAYLOAD];
    for frag_idx in 0..total_frags {
        let offset = frag_idx as usize * MAX_FRAG_PAYLOAD;
        let chunk_len = (total_len - offset).min(MAX_FRAG_PAYLOAD);
        let header = FragHeader {
            frag_idx,
            total_frags,
            packet_id,
            total_len: total_len as u16,
        };
        header.encode(&mut scratch[..FRAG_HEADER_LEN]);
        scratch[FRAG_HEADER_LEN..FRAG_HEADER_LEN + chunk_len]
            .copy_from_slice(&packet[offset..offset + chunk_len]);
        emit(&scratch[..FRAG_HEADER_LEN + chunk_len]);
    }
}

pub const REASSEMBLER_SLOTS: usize = 512;
pub const REASSEMBLER_MASK: usize = 511;
pub const REASSEMBLER_PROBE_MAX: usize = 8;
pub const REASSEMBLER_TIMEOUT_MS: u128 = 300;

pub struct ReassemblerSlot {
    pub peer: u64,
    pub packet_id: u16,
    pub total_len: usize,
    pub total_frags: u8,
    pub frags_received: u8,
    pub received_mask: u32,
    pub updated_at: std::time::Instant,
    pub active: bool,
    pub buffer: Box<[u8; MAX_PACKET_LEN]>,
}

pub struct Reassembler {
    slots: Vec<Option<ReassemblerSlot>>,
}

impl Default for Reassembler {
    fn default() -> Self {
        Self::new()
    }
}

impl Reassembler {
    pub fn new() -> Self {
        let mut slots = Vec::with_capacity(REASSEMBLER_SLOTS);
        for _ in 0..REASSEMBLER_SLOTS {
            slots.push(None);
        }
        Self { slots }
    }

    pub fn process_fragment_from<F: FnOnce(&[u8])>(
        &mut self,
        peer: u64,
        payload: &[u8],
        now: std::time::Instant,
        on_complete: F,
    ) -> bool {
        let Some(header) = FragHeader::decode(payload) else {
            return false;
        };
        let chunk = &payload[FRAG_HEADER_LEN..];
        let total_len = header.total_len as usize;
        let offset = header.frag_idx as usize * MAX_FRAG_PAYLOAD;
        if chunk.len() != (total_len - offset).min(MAX_FRAG_PAYLOAD) {
            return false;
        }
        let mixed = u64::from(header.packet_id) ^ peer.wrapping_mul(0x9E37_79B9_7F4A_7C15);
        let base_idx = (mixed as usize) & REASSEMBLER_MASK;

        let mut target_idx = None;
        let mut first_available_idx = None;

        for probe in 0..REASSEMBLER_PROBE_MAX {
            let idx = (base_idx + probe) & REASSEMBLER_MASK;
            match &self.slots[idx] {
                Some(existing) => {
                    if existing.active
                        && existing.peer == peer
                        && existing.packet_id == header.packet_id
                        && existing.total_len == total_len
                        && existing.total_frags == header.total_frags
                        && now
                            .saturating_duration_since(existing.updated_at)
                            .as_millis()
                            < REASSEMBLER_TIMEOUT_MS
                    {
                        target_idx = Some(idx);
                        break;
                    }
                    if (!existing.active
                        || now
                            .saturating_duration_since(existing.updated_at)
                            .as_millis()
                            >= REASSEMBLER_TIMEOUT_MS)
                        && first_available_idx.is_none()
                    {
                        first_available_idx = Some(idx);
                    }
                }
                None => {
                    if first_available_idx.is_none() {
                        first_available_idx = Some(idx);
                    }
                }
            }
        }

        let slot_idx = match target_idx {
            Some(idx) => idx,
            None => match first_available_idx {
                Some(idx) => {
                    match &mut self.slots[idx] {
                        Some(slot) => {
                            slot.peer = peer;
                            slot.packet_id = header.packet_id;
                            slot.total_len = total_len;
                            slot.total_frags = header.total_frags;
                            slot.frags_received = 0;
                            slot.received_mask = 0;
                            slot.updated_at = now;
                            slot.active = true;
                        }
                        None => {
                            let new_slot = ReassemblerSlot {
                                peer,
                                packet_id: header.packet_id,
                                total_len,
                                total_frags: header.total_frags,
                                frags_received: 0,
                                received_mask: 0,
                                updated_at: now,
                                active: true,
                                buffer: Box::new([0u8; MAX_PACKET_LEN]),
                            };
                            self.slots[idx] = Some(new_slot);
                        }
                    }
                    idx
                }
                None => {
                    return false;
                }
            },
        };

        let Some(slot) = self.slots[slot_idx].as_mut() else {
            return false;
        };
        slot.updated_at = now;
        let bit = 1u32 << (header.frag_idx as u32);
        if (slot.received_mask & bit) == 0 {
            slot.received_mask |= bit;
            slot.frags_received += 1;
            slot.buffer[offset..offset + chunk.len()].copy_from_slice(chunk);
        }

        if slot.frags_received == slot.total_frags {
            on_complete(&slot.buffer[..total_len]);
            slot.active = false;
            true
        } else {
            false
        }
    }
}

#[allow(dead_code)]
pub const SHARDED_REASSEMBLER_SHARDS: usize = 16;

#[allow(dead_code)]
pub struct ShardedReassembler {
    shards: Vec<std::sync::Mutex<Reassembler>>,
}

impl Default for ShardedReassembler {
    fn default() -> Self {
        Self::new()
    }
}

#[allow(dead_code)]
impl ShardedReassembler {
    pub fn new() -> Self {
        let mut shards = Vec::with_capacity(SHARDED_REASSEMBLER_SHARDS);
        for _ in 0..SHARDED_REASSEMBLER_SHARDS {
            shards.push(std::sync::Mutex::new(Reassembler::new()));
        }
        Self { shards }
    }

    pub fn process_fragment_from<F: FnOnce(&[u8])>(
        &self,
        peer: u64,
        payload: &[u8],
        now: std::time::Instant,
        on_complete: F,
    ) -> bool {
        let Some(header) = FragHeader::decode(payload) else {
            return false;
        };
        let shard_idx =
            ((u64::from(header.packet_id) ^ peer) as usize) % SHARDED_REASSEMBLER_SHARDS;
        let mut shard = match self.shards[shard_idx].lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        shard.process_fragment_from(peer, payload, now, on_complete)
    }
}

#[cfg(test)]
mod frag_tests {
    use super::*;

    #[test]
    fn test_frag_roundtrip_1500_mtu() {
        let original: Vec<u8> = (0..1500).map(|i| (i & 0xff) as u8).collect();
        let mut frags = Vec::new();
        fragment_packet(101, &original, |frag| {
            frags.push(frag.to_vec());
        });
        assert_eq!(frags.len(), 2);
        assert_eq!(frags[0].len(), FRAG_HEADER_LEN + 1272);
        assert_eq!(frags[1].len(), FRAG_HEADER_LEN + 228);

        let mut reassembler = Reassembler::new();
        let now = std::time::Instant::now();
        let mut assembled = Vec::new();

        assert!(!reassembler.process_fragment_from(0, &frags[0], now, |p| assembled = p.to_vec()));
        assert!(reassembler.process_fragment_from(0, &frags[1], now, |p| assembled = p.to_vec()));
        assert_eq!(assembled, original);
    }

    #[test]
    fn test_frag_roundtrip_max_packet_and_oversize_rejected() {
        let original: Vec<u8> = (0..MAX_PACKET_LEN)
            .map(|i| ((i * 7) & 0xff) as u8)
            .collect();
        let mut frags = Vec::new();
        fragment_packet(202, &original, |frag| {
            frags.push(frag.to_vec());
        });
        assert_eq!(frags.len(), MAX_PACKET_LEN.div_ceil(MAX_FRAG_PAYLOAD));

        let mut reassembler = Reassembler::new();
        let now = std::time::Instant::now();
        let mut assembled = Vec::new();

        let order: Vec<usize> = (0..frags.len()).rev().collect();
        for (i, &idx) in order.iter().enumerate() {
            let is_last = i == order.len() - 1;
            let finished =
                reassembler.process_fragment_from(0, &frags[idx], now, |p| assembled = p.to_vec());
            assert_eq!(finished, is_last);
        }
        assert_eq!(assembled, original);

        let mut too_big = Vec::new();
        fragment_packet(203, &vec![0u8; MAX_PACKET_LEN + 1], |frag| {
            too_big.push(frag.to_vec());
        });
        assert!(too_big.is_empty());
    }

    #[test]
    fn test_frag_wire_ceiling_and_duplicates() {
        let original: Vec<u8> = (0..1500).map(|i| (i & 0xff) as u8).collect();
        let mut frags = Vec::new();
        fragment_packet(303, &original, |frag| {
            frags.push(frag.to_vec());
        });
        assert_eq!(frags.len(), 2);
        for frag in &frags {
            let wire_size = frag.len() + 24 + 10 + 4 + 8 + 20 + 4;
            assert!(wire_size <= 1350);
        }

        let mut reassembler = Reassembler::new();
        let now = std::time::Instant::now();
        let mut assembled = Vec::new();

        assert!(!reassembler.process_fragment_from(0, &frags[0], now, |p| assembled = p.to_vec()));
        assert!(!reassembler.process_fragment_from(0, &frags[0], now, |p| assembled = p.to_vec()));
        assert!(reassembler.process_fragment_from(0, &frags[1], now, |p| assembled = p.to_vec()));
        assert_eq!(assembled, original);
    }

    #[test]
    fn test_frag_slot_timeout_recovery() {
        let original: Vec<u8> = vec![0x42; 1500];
        let mut frags = Vec::new();
        fragment_packet(404, &original, |frag| {
            frags.push(frag.to_vec());
        });

        let mut reassembler = Reassembler::new();
        let t0 = std::time::Instant::now();
        let mut assembled = Vec::new();

        assert!(!reassembler.process_fragment_from(0, &frags[0], t0, |p| assembled = p.to_vec()));

        let t1 = t0 + std::time::Duration::from_millis(1500);
        let new_original: Vec<u8> = vec![0x99; 1500];
        let mut new_frags = Vec::new();
        fragment_packet(404, &new_original, |frag| {
            new_frags.push(frag.to_vec());
        });

        assert!(
            !reassembler.process_fragment_from(0, &new_frags[0], t1, |p| assembled = p.to_vec())
        );
        assert!(
            reassembler.process_fragment_from(0, &new_frags[1], t1, |p| assembled = p.to_vec())
        );
        assert_eq!(assembled, new_original);
    }

    #[test]
    fn test_frag_peers_with_same_packet_id_do_not_mix() {
        let a: Vec<u8> = vec![0x11; 1500];
        let b: Vec<u8> = vec![0x22; 1500];
        let mut frags_a = Vec::new();
        fragment_packet(505, &a, |frag| frags_a.push(frag.to_vec()));
        let mut frags_b = Vec::new();
        fragment_packet(505, &b, |frag| frags_b.push(frag.to_vec()));

        let mut reassembler = Reassembler::new();
        let now = std::time::Instant::now();
        let mut out_a = Vec::new();
        let mut out_b = Vec::new();

        assert!(!reassembler.process_fragment_from(1, &frags_a[0], now, |p| out_a = p.to_vec()));
        assert!(!reassembler.process_fragment_from(2, &frags_b[0], now, |p| out_b = p.to_vec()));
        assert!(reassembler.process_fragment_from(1, &frags_a[1], now, |p| out_a = p.to_vec()));
        assert!(reassembler.process_fragment_from(2, &frags_b[1], now, |p| out_b = p.to_vec()));
        assert_eq!(out_a, a);
        assert_eq!(out_b, b);
    }

    #[test]
    fn test_frag_rejects_inconsistent_lengths() {
        let original: Vec<u8> = vec![0x33; 1500];
        let mut frags = Vec::new();
        fragment_packet(606, &original, |frag| frags.push(frag.to_vec()));

        let mut oversized = frags[0].clone();
        oversized[6..8].copy_from_slice(&5000u16.to_be_bytes());
        assert!(FragHeader::decode(&oversized).is_none());

        let mut reassembler = Reassembler::new();
        let now = std::time::Instant::now();
        assert!(!reassembler.process_fragment_from(
            0,
            &frags[1][..FRAG_HEADER_LEN + 10],
            now,
            |_| {}
        ));
    }
}
