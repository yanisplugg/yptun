use crate::{
    packet::{PACKET_CAPACITY, PacketBuf},
    wrap::WRAP_KEY_LEN,
};
use aes::cipher::{InnerIvInit, KeyInit, StreamCipher};
use anyhow::{Result, bail};
use hmac::{Hmac, Mac};
use rand::RngExt;
use rand::{Rng, SeedableRng, rngs::StdRng};
use sha1::Sha1;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

type Aes128Ctr128BE = ctr::Ctr128BE<aes::Aes128>;
type Aes128CtrCore = ctr::CtrCore<aes::Aes128, ctr::flavors::Ctr128BE>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObfsMode {
    Audio,
    Video,
    Unified,
}

impl ObfsMode {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "audio" => Ok(Self::Audio),
            "video" => Ok(Self::Video),
            "unified" => Ok(Self::Unified),
            _ => bail!("неподдерживаемый режим обфускации: {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct ObfsConfig {
    pub padding_max: usize,
    pub ssrc: u32,
    pub mode: ObfsMode,
}

impl ObfsConfig {
    pub fn new(mode: ObfsMode) -> Self {
        let padding_max = match mode {
            ObfsMode::Audio => 24,
            ObfsMode::Video => 60,
            ObfsMode::Unified => 48,
        };
        Self {
            padding_max,
            ssrc: seeded_rng().next_u32(),
            mode,
        }
    }
}

pub struct ObfsState {
    count: AtomicU64,
    initial_timestamp: u32,
    initial_abs_send_time: u32,
    initial_sequence: u16,
    transport_init: u16,
    started: Instant,
}

impl Default for ObfsState {
    fn default() -> Self {
        Self::new()
    }
}

impl ObfsState {
    pub fn new() -> Self {
        let mut rng = seeded_rng();
        let wall_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        Self {
            count: AtomicU64::new(0),
            initial_timestamp: rng.next_u32(),
            initial_abs_send_time: (((wall_ms * 262_144) / 1_000) as u32) & 0x00ff_ffff,
            initial_sequence: rng.next_u32() as u16,
            transport_init: rng.next_u32() as u16,
            started: Instant::now(),
        }
    }

    #[inline(always)]
    fn next(&self) -> (u64, u16) {
        let count = self.count.fetch_add(1, Ordering::Relaxed);
        let transport = self.transport_init.wrapping_add(count as u16);
        (count, transport)
    }
}

pub struct ObfsCipher {
    aes: aes::Aes128,
    hmac: Hmac<Sha1>,
}

#[derive(Clone)]
pub enum SessionCipher {
    Obfs(Arc<ObfsCipher>),
    Plain,
}

impl SessionCipher {
    pub fn wrap(
        &self,
        packet: &mut PacketBuf,
        config: &ObfsConfig,
        state: &ObfsState,
    ) -> Result<()> {
        match self {
            Self::Obfs(cipher) => cipher.wrap(packet, config, state),
            Self::Plain => Ok(()),
        }
    }

    pub fn unwrap(&self, packet: &mut PacketBuf, mode: ObfsMode) -> Result<u16> {
        match self {
            Self::Obfs(cipher) => cipher.unwrap(packet, mode),
            Self::Plain => Ok(0),
        }
    }

    pub fn is_plain(&self) -> bool {
        matches!(self, Self::Plain)
    }
}

impl ObfsCipher {
    pub fn new(key: [u8; WRAP_KEY_LEN]) -> Result<Self> {
        Ok(Self {
            aes: aes::Aes128::new_from_slice(&key[..16])
                .map_err(|_| anyhow::anyhow!("AES key rejected"))?,
            hmac: <Hmac<Sha1> as hmac::digest::KeyInit>::new_from_slice(&key[16..])
                .map_err(|_| anyhow::anyhow!("HMAC key rejected"))?,
        })
    }

    pub fn wrap(
        &self,
        packet: &mut PacketBuf,
        config: &ObfsConfig,
        state: &ObfsState,
    ) -> Result<()> {
        let (count, transport_sequence) = state.next();
        let sequence = state.initial_sequence.wrapping_add(count as u16);
        self.wrap_internal(packet, config, state, sequence, transport_sequence)
    }

    fn wrap_internal(
        &self,
        packet: &mut PacketBuf,
        config: &ObfsConfig,
        state: &ObfsState,
        sequence: u16,
        transport_sequence: u16,
    ) -> Result<()> {
        if packet.is_empty() {
            bail!("obfs: empty payload");
        }
        let payload_len = packet.len();
        let elapsed = state.started.elapsed();
        let mut rng = rand::rng();
        let (payload_type, padding_total, marker_bit) = match config.mode {
            ObfsMode::Audio => {
                const SRTP_OVERHEAD: usize = 24 + 10;
                let wire_min = payload_len.saturating_add(SRTP_OVERHEAD);
                let pad = if config.padding_max == 0 {
                    1
                } else if wire_min < 100 {
                    let target: usize = rng.random_range(100..=150);
                    target.saturating_sub(wire_min).max(1)
                } else if payload_len < 1000 {
                    rng.random_range(0..config.padding_max.clamp(4, 24)) + 1
                } else {
                    1
                };
                (111u8, pad, false)
            }
            ObfsMode::Video => {
                let pad = if config.padding_max == 0 {
                    1
                } else if payload_len < 1000 {
                    rng.random_range(0..config.padding_max.clamp(8, 48)) + 1
                } else {
                    const MAX_WIRE_LIMIT: usize = 1280;
                    const OVERHEAD: usize = 24 + 10 + 4 + 8 + 20;
                    let current_projected = payload_len.saturating_add(OVERHEAD);
                    let headroom = MAX_WIRE_LIMIT.saturating_sub(current_projected);
                    if headroom > 1 {
                        let jitter_limit = headroom.min(32);
                        rng.random_range(0..jitter_limit) + 1
                    } else {
                        1
                    }
                };
                (96u8, pad, rng.random_range(0..5) == 0)
            }
            ObfsMode::Unified => {
                const MAX_WIRE_LIMIT: usize = 1350;
                const OVERHEAD: usize = 24 + 10 + 4 + 8 + 20;
                const SRTP_OVERHEAD: usize = 24 + 10;
                let current_projected = payload_len.saturating_add(OVERHEAD);
                let wire_min = payload_len.saturating_add(SRTP_OVERHEAD);
                if wire_min < 100 {
                    let target: usize = rng.random_range(100..=150);
                    let pad = target.saturating_sub(wire_min).max(1);
                    (111u8, pad, false)
                } else if payload_len <= 350 {
                    let pad = rng.random_range(1..=2);
                    (111u8, pad, false)
                } else if current_projected <= 1050 {
                    let headroom = 1050usize.saturating_sub(current_projected);
                    let pad = if headroom > 1 {
                        rng.random_range(1..=headroom.min(4))
                    } else {
                        1
                    };
                    (96u8, pad, true)
                } else {
                    let headroom = MAX_WIRE_LIMIT.saturating_sub(current_projected);
                    let pad = if headroom > 1 {
                        let jitter_limit = headroom.min(4);
                        rng.random_range(1..=jitter_limit)
                    } else {
                        1
                    };
                    (96u8, pad, rng.random_range(0..4) == 0)
                }
            }
        };
        let timestamp = if payload_type == 111 {
            let frame_idx = (elapsed.as_millis() / 20) as u32;
            state
                .initial_timestamp
                .wrapping_add(frame_idx.wrapping_mul(960))
        } else {
            state
                .initial_timestamp
                .wrapping_add(duration_ticks(elapsed, rtp_clock_rate(payload_type)))
        };
        let padding_random = padding_total.saturating_sub(1);
        let tail = padding_total + 10;
        let range = packet.range();
        if range.start < 24
            || range
                .end
                .checked_add(tail)
                .is_none_or(|end| end > PACKET_CAPACITY)
        {
            bail!("obfs: packet exceeds buffer capacity");
        }
        let absolute_send_time = state
            .initial_abs_send_time
            .wrapping_add(duration_ticks(elapsed, 262_144))
            & 0x00ff_ffff;
        let header = packet.prepend(24)?;
        header.fill(0);
        header[0] = if padding_total > 0 { 0xb0 } else { 0x90 };
        header[1] = payload_type & 0x7f;
        if marker_bit {
            header[1] |= 0x80;
        }
        header[2..4].copy_from_slice(&sequence.to_be_bytes());
        header[4..8].copy_from_slice(&timestamp.to_be_bytes());
        header[8..12].copy_from_slice(&config.ssrc.to_be_bytes());
        header[12..14].copy_from_slice(&0xbedeu16.to_be_bytes());
        header[14..16].copy_from_slice(&2u16.to_be_bytes());
        header[16] = 0x32;
        header[17] = (absolute_send_time >> 16) as u8;
        header[18] = (absolute_send_time >> 8) as u8;
        header[19] = absolute_send_time as u8;
        header[20] = 0x51;
        header[21..23].copy_from_slice(&transport_sequence.to_be_bytes());
        let range = packet.range();
        let payload_start = range.start + 24;
        let payload_end = payload_start + payload_len;
        let tag_start = payload_end + padding_total;

        packet.extend_tail(padding_total + 10)?;
        let storage = packet.storage_mut();
        if padding_total > 0 {
            if padding_random > 0 {
                rng.fill_bytes(&mut storage[payload_end..payload_end + padding_random]);
            }
            storage[tag_start - 1] = padding_total as u8;
        }
        let iv = srtp_iv(config.ssrc, sequence, timestamp);
        let mut cipher = aes_ctr(&self.aes, &iv);
        cipher.apply_keystream(&mut storage[payload_start..tag_start]);
        let mut mac = self.hmac.clone();
        mac.update(&storage[range.start..tag_start]);
        let tag = mac.finalize().into_bytes();
        storage[tag_start..tag_start + 10].copy_from_slice(&tag[..10]);
        Ok(())
    }

    pub fn unwrap(&self, packet: &mut PacketBuf, _mode: ObfsMode) -> Result<u16> {
        let range = packet.range();
        let wire = packet.as_slice();
        if wire.len() < 13 || wire[0] >> 6 != 2 {
            bail!("obfs: not RTP v2");
        }
        let payload_type = wire[1] & 0x7f;
        if !matches!(payload_type, 111 | 96 | 6) {
            bail!("obfs: unsupported payload type {payload_type}");
        }
        let sequence = u16::from_be_bytes([wire[2], wire[3]]);
        let timestamp = u32::from_be_bytes(wire[4..8].try_into()?);
        let ssrc = u32::from_be_bytes(wire[8..12].try_into()?);
        let mut header_len = 12usize;
        if wire[0] & 0x10 != 0 {
            if wire.len() < 16 {
                bail!("obfs: packet too short for extension header");
            }
            header_len += 4 + u16::from_be_bytes([wire[14], wire[15]]) as usize * 4;
        }
        if wire.len() < header_len {
            bail!("obfs: packet too short for calculated RTP header");
        }
        let storage = packet.storage_mut();
        if range.len() < header_len + 10 {
            bail!("obfs srtp: missing authentication tag");
        }
        let tag_start = range.end - 10;
        let mut mac = self.hmac.clone();
        mac.update(&storage[range.start..tag_start]);
        if mac
            .verify_truncated_left(&storage[tag_start..range.end])
            .is_err()
        {
            bail!("obfs srtp: authentication failed");
        }
        let payload_start = range.start + header_len;
        let iv = srtp_iv(ssrc, sequence, timestamp);
        let mut cipher = aes_ctr(&self.aes, &iv);
        cipher.apply_keystream(&mut storage[payload_start..tag_start]);
        let mut payload_end = tag_start;
        if storage[range.start] & 0x20 != 0 {
            let padding = storage[tag_start - 1] as usize;
            if padding == 0 || padding > tag_start - payload_start {
                bail!("obfs srtp: invalid padding length {padding}");
            }
            payload_end = tag_start - padding;
        }
        packet.set_range(payload_start..payload_end)?;
        Ok(sequence)
    }
}

#[inline(always)]
fn aes_ctr(cipher: &aes::Aes128, iv: &[u8; 16]) -> Aes128Ctr128BE {
    Aes128Ctr128BE::from_core(Aes128CtrCore::inner_iv_init(cipher.clone(), iv.into()))
}

fn seeded_rng() -> StdRng {
    static FALLBACK_SEQUENCE: AtomicU64 = AtomicU64::new(0);

    let mut seed = [0u8; 32];
    if getrandom::fill(&mut seed).is_err() {
        let sequence = FALLBACK_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let time = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;
        let base = time ^ sequence.rotate_left(17) ^ u64::from(std::process::id());
        for (index, chunk) in seed.chunks_exact_mut(8).enumerate() {
            let value = base
                .wrapping_add((index as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15))
                .rotate_left((index * 13) as u32);
            chunk.copy_from_slice(&value.to_le_bytes());
        }
    }
    StdRng::from_seed(seed)
}

#[inline(always)]
fn rtp_clock_rate(payload_type: u8) -> u32 {
    if payload_type == 96 { 90_000 } else { 48_000 }
}

#[inline(always)]
fn duration_ticks(elapsed: Duration, clock_rate: u32) -> u32 {
    let rate = u64::from(clock_rate);
    elapsed
        .as_secs()
        .wrapping_mul(rate)
        .wrapping_add(u64::from(elapsed.subsec_nanos()).wrapping_mul(rate) / 1_000_000_000)
        as u32
}

fn srtp_iv(ssrc: u32, sequence: u16, timestamp: u32) -> [u8; 16] {
    let mut value = [0u8; 16];
    value[..4].copy_from_slice(&ssrc.to_be_bytes());
    value[4..6].copy_from_slice(&sequence.to_be_bytes());
    value[8..12].copy_from_slice(&timestamp.to_be_bytes());
    value
}

pub fn is_rtp_packet(wire: &[u8]) -> bool {
    wire.len() >= 13 && wire[0] >> 6 == 2 && matches!(wire[1] & 0x7f, 111 | 96 | 6)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        packet::{PACKET_HEADROOM, PacketPool},
        wrap::derive_wrap_key,
    };
    use proptest::prelude::*;

    fn roundtrip(mode: ObfsMode) {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let payload = b"GETCONF:9000|device|password|7|salt";
        packet.read_area()[..payload.len()].copy_from_slice(payload);
        packet.set_read_len(payload.len()).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let config = ObfsConfig::new(mode);
        let state = ObfsState::new();
        cipher.wrap(&mut packet, &config, &state).unwrap();
        assert!(is_rtp_packet(packet.as_slice()));
        cipher.unwrap(&mut packet, mode).unwrap();
        assert_eq!(packet.as_slice(), payload);
    }

    fn roundtrip_sized_payload(mode: ObfsMode, length: usize, marker: u8) {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let payload = vec![marker; length];
        packet.read_area()[..payload.len()].copy_from_slice(&payload);
        packet.set_read_len(payload.len()).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("sized-roundtrip-password").unwrap()).unwrap();
        let state = ObfsState::new();
        cipher
            .wrap(&mut packet, &ObfsConfig::new(mode), &state)
            .unwrap();
        assert!(is_rtp_packet(packet.as_slice()));
        cipher.unwrap(&mut packet, mode).unwrap();
        assert_eq!(packet.as_slice(), payload);
    }

    macro_rules! sized_roundtrip_case {
        ($name:ident, $mode:expr, $length:expr, $marker:expr) => {
            #[test]
            fn $name() {
                roundtrip_sized_payload($mode, $length, $marker);
            }
        };
    }

    sized_roundtrip_case!(audio_roundtrip_one_byte, ObfsMode::Audio, 1, 0x01);
    sized_roundtrip_case!(audio_roundtrip_fifteen_bytes, ObfsMode::Audio, 15, 0x0f);
    sized_roundtrip_case!(audio_roundtrip_sixty_four_bytes, ObfsMode::Audio, 64, 0x40);
    sized_roundtrip_case!(audio_roundtrip_255_bytes, ObfsMode::Audio, 255, 0xff);
    sized_roundtrip_case!(audio_roundtrip_512_bytes, ObfsMode::Audio, 512, 0x5a);
    sized_roundtrip_case!(audio_roundtrip_1024_bytes, ObfsMode::Audio, 1024, 0xa5);
    sized_roundtrip_case!(audio_roundtrip_1536_bytes, ObfsMode::Audio, 1536, 0x33);
    sized_roundtrip_case!(audio_roundtrip_1900_bytes, ObfsMode::Audio, 1900, 0xcc);
    sized_roundtrip_case!(video_roundtrip_one_byte, ObfsMode::Video, 1, 0x02);
    sized_roundtrip_case!(video_roundtrip_fifteen_bytes, ObfsMode::Video, 15, 0x10);
    sized_roundtrip_case!(video_roundtrip_sixty_four_bytes, ObfsMode::Video, 64, 0x41);
    sized_roundtrip_case!(video_roundtrip_255_bytes, ObfsMode::Video, 255, 0xfe);
    sized_roundtrip_case!(video_roundtrip_512_bytes, ObfsMode::Video, 512, 0x6b);
    sized_roundtrip_case!(video_roundtrip_1024_bytes, ObfsMode::Video, 1024, 0xb6);
    sized_roundtrip_case!(video_roundtrip_1536_bytes, ObfsMode::Video, 1536, 0x44);
    sized_roundtrip_case!(video_roundtrip_1900_bytes, ObfsMode::Video, 1900, 0xdd);
    sized_roundtrip_case!(unified_roundtrip_one_byte, ObfsMode::Unified, 1, 0x03);
    sized_roundtrip_case!(unified_roundtrip_fifteen_bytes, ObfsMode::Unified, 15, 0x11);
    sized_roundtrip_case!(
        unified_roundtrip_sixty_four_bytes,
        ObfsMode::Unified,
        64,
        0x42
    );
    sized_roundtrip_case!(unified_roundtrip_255_bytes, ObfsMode::Unified, 255, 0xfd);
    sized_roundtrip_case!(unified_roundtrip_512_bytes, ObfsMode::Unified, 512, 0x6c);
    sized_roundtrip_case!(unified_roundtrip_1024_bytes, ObfsMode::Unified, 1024, 0xb7);

    #[test]
    fn stable_getconf_audio_fixture_matches_server_contract() {
        let payload = b"GETCONF:46000|wire-device|wire-password|7|wire-salt|1|27|CSQTT-WIRE-2";
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        packet.read_area()[..payload.len()].copy_from_slice(payload);
        packet.set_read_len(payload.len()).unwrap();
        let config = ObfsConfig {
            padding_max: 0,
            ssrc: 0x1020_3040,
            mode: ObfsMode::Audio,
        };
        let state = ObfsState {
            count: AtomicU64::new(0),
            initial_timestamp: 0x1122_3344,
            initial_abs_send_time: 0x0055_6677,
            initial_sequence: 0x7788,
            transport_init: 0x99aa,
            started: Instant::now() + Duration::from_secs(1),
        };
        ObfsCipher::new(derive_wrap_key("wire-password").unwrap())
            .unwrap()
            .wrap(&mut packet, &config, &state)
            .unwrap();
        assert_eq!(
            hex::encode(packet.as_slice()),
            crate::wire_protocol::CLIENT_GETCONF_AUDIO_FIXTURE,
        );
    }

    #[test]
    fn audio_roundtrip() {
        roundtrip(ObfsMode::Audio);
    }

    #[test]
    fn video_roundtrip() {
        roundtrip(ObfsMode::Video);
    }

    #[test]
    fn wire_profiles_expose_only_expected_rtp_metadata() {
        let plaintext = b"GETCONF:46000|device-id|connection-password|9|session-salt";
        let cipher = ObfsCipher::new(derive_wrap_key("wire-profile-password").unwrap()).unwrap();
        for (mode, payload_type) in [(ObfsMode::Audio, 111u8), (ObfsMode::Video, 96u8)] {
            let pool = PacketPool::new(1);
            let mut packet = pool.acquire();
            packet.read_area()[..plaintext.len()].copy_from_slice(plaintext);
            packet.set_read_len(plaintext.len()).unwrap();
            cipher
                .wrap(&mut packet, &ObfsConfig::new(mode), &ObfsState::new())
                .unwrap();
            let wire = packet.as_slice();
            assert!(is_rtp_packet(wire));
            assert_eq!(wire[0] >> 6, 2);
            assert_eq!(wire[1] & 0x7f, payload_type);
            assert_eq!(&wire[12..14], &0xbedeu16.to_be_bytes());
            assert_eq!(&wire[14..16], &2u16.to_be_bytes());
            assert_eq!(wire[16], 0x32);
            assert_eq!(wire[20], 0x51);
            assert!(
                !wire
                    .windows(plaintext.len())
                    .any(|slice| slice == plaintext)
            );
            assert!(!wire.windows(b"CSQTT".len()).any(|slice| slice == b"CSQTT"));
            assert!(
                !wire
                    .windows(b"connection-password".len())
                    .any(|slice| slice == b"connection-password")
            );
        }
    }

    #[test]
    fn rtp_clocks_follow_elapsed_media_time() {
        let frame = Duration::from_millis(20);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(111)), 960);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(96)), 1_800);
        assert_eq!(duration_ticks(Duration::from_secs(1), 48_000), 48_000);
        assert_eq!(duration_ticks(Duration::from_secs(1), 90_000), 90_000);
    }

    #[test]
    fn supported_modes_parse() {
        assert_eq!(ObfsMode::parse("audio").unwrap(), ObfsMode::Audio);
        assert_eq!(ObfsMode::parse("video").unwrap(), ObfsMode::Video);
        assert_eq!(ObfsMode::parse("unified").unwrap(), ObfsMode::Unified);
    }

    #[test]
    fn unknown_mode_returns_error() {
        assert!(ObfsMode::parse("invalid").is_err());
    }

    #[test]
    fn empty_payload_returns_error() {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let state = ObfsState::new();
        assert!(
            cipher
                .wrap(&mut packet, &ObfsConfig::new(ObfsMode::Audio), &state)
                .is_err()
        );
    }

    #[test]
    fn unwraps_go_audio_and_video_vectors() {
        let cipher = ObfsCipher::new(derive_wrap_key("cross-language-password").unwrap()).unwrap();
        let pool = PacketPool::new(4);
        let mut packet = pool.acquire();
        let payload = b"cross-language-payload";
        packet.read_area()[..payload.len()].copy_from_slice(payload);
        packet.set_read_len(payload.len()).unwrap();
        let config = ObfsConfig {
            padding_max: 0,
            ssrc: 0x709a_0949,
            mode: ObfsMode::Audio,
        };
        let state = ObfsState {
            count: AtomicU64::new(0),
            initial_timestamp: 0x7b25_bb36,
            initial_abs_send_time: 0x0011_eb85,
            initial_sequence: 0xcb1b,
            transport_init: 0x2bb6,
            started: Instant::now() + Duration::from_secs(1),
        };
        cipher.wrap(&mut packet, &config, &state).unwrap();
        let audio_wire = packet.as_slice().to_vec();

        let video_wire = {
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(payload);
            packet.set_read_len(payload.len()).unwrap();
            let config_video = ObfsConfig {
                padding_max: 0,
                ssrc: 0x709a_0949,
                mode: ObfsMode::Video,
            };
            let state_video = ObfsState {
                count: AtomicU64::new(0),
                initial_timestamp: 0x7b25_bb36,
                initial_abs_send_time: 0x0011_eb85,
                initial_sequence: 0xcb1b,
                transport_init: 0x2bb6,
                started: Instant::now() + Duration::from_secs(1),
            };
            cipher
                .wrap(&mut packet, &config_video, &state_video)
                .unwrap();
            packet.as_slice().to_vec()
        };

        for (wire, mode) in [(audio_wire, ObfsMode::Audio), (video_wire, ObfsMode::Video)] {
            let mut packet = pool.acquire();
            packet.read_area()[..wire.len()].copy_from_slice(&wire);
            packet.set_read_len(wire.len()).unwrap();
            cipher.unwrap(&mut packet, mode).unwrap();
            assert_eq!(packet.as_slice(), b"cross-language-payload");
        }
    }

    #[test]
    fn small_packet_adaptive_padding_reaches_opus_range() {
        let cipher = ObfsCipher::new(derive_wrap_key("adaptive-pad-password").unwrap()).unwrap();
        let pool = PacketPool::new(1);
        let config = ObfsConfig::new(ObfsMode::Audio);
        for size in [40, 52, 64, 80] {
            let payload = vec![0xaa; size];
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(&payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &ObfsState::new())
                .unwrap();
            let wire = packet.as_slice();
            assert!(is_rtp_packet(wire));
            assert!(
                wire.len() >= 100,
                "wire len {} should be at least 100 bytes for input size {}",
                wire.len(),
                size
            );
            assert!(
                wire.len() <= 150,
                "wire len {} should be at most 150 bytes for input size {}",
                wire.len(),
                size
            );
            cipher.unwrap(&mut packet, ObfsMode::Audio).unwrap();
            assert_eq!(packet.as_slice(), payload.as_slice());
        }
    }

    #[test]
    fn large_packet_padding_respects_mtu_boundary() {
        let cipher = ObfsCipher::new(derive_wrap_key("mtu-boundary-password").unwrap()).unwrap();
        let pool = PacketPool::new(1);
        let config = ObfsConfig::new(ObfsMode::Video);
        for size in [1000, 1100, 1120, 1160] {
            let payload = vec![0xbb; size];
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(&payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &ObfsState::new())
                .unwrap();
            let wire = packet.as_slice();
            assert!(is_rtp_packet(wire));
            const OVERHEAD_OUTSIDE_RTP: usize = 4 + 8 + 20;
            let total_projected_wire = wire.len() + OVERHEAD_OUTSIDE_RTP;
            assert!(
                total_projected_wire <= 1280,
                "total wire {} exceeds 1280 for input size {}",
                total_projected_wire,
                size
            );
            cipher.unwrap(&mut packet, ObfsMode::Video).unwrap();
            assert_eq!(packet.as_slice(), payload.as_slice());
        }
    }

    #[test]
    fn oversized_payload_returns_error() {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let length = packet.read_area().len();
        packet.set_read_len(length).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let state = ObfsState::new();
        assert!(
            cipher
                .wrap(&mut packet, &ObfsConfig::new(ObfsMode::Video), &state)
                .is_err()
        );
    }

    #[test]
    fn authenticated_audio_region_rejects_every_single_byte_tamper() {
        let key = derive_wrap_key("tamper-password").unwrap();
        let cipher = ObfsCipher::new(key).unwrap();
        let config = ObfsConfig::new(ObfsMode::Audio);
        let pool = PacketPool::new(1);
        let payload = b"authenticated-audio-payload";
        let wire = {
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &ObfsState::new())
                .unwrap();
            packet.as_slice().to_vec()
        };
        let authenticated_end = 24 + payload.len() + 16;
        for index in 0..authenticated_end {
            let mut tampered = wire.clone();
            tampered[index] ^= 1;
            let mut packet = pool.acquire();
            packet.read_area()[..tampered.len()].copy_from_slice(&tampered);
            packet.set_read_len(tampered.len()).unwrap();
            assert!(
                cipher.unwrap(&mut packet, config.mode).is_err(),
                "tamper index {index}"
            );
        }
    }

    #[test]
    fn video_wire_rejects_every_single_byte_tamper() {
        let key = derive_wrap_key("tamper-password").unwrap();
        let cipher = ObfsCipher::new(key).unwrap();
        let config = ObfsConfig::new(ObfsMode::Video);
        let pool = PacketPool::new(1);
        let payload = b"authenticated-video-payload";
        let wire = {
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &ObfsState::new())
                .unwrap();
            packet.as_slice().to_vec()
        };
        for index in 0..wire.len() {
            let mut tampered = wire.clone();
            tampered[index] ^= 1;
            let mut packet = pool.acquire();
            packet.read_area()[..tampered.len()].copy_from_slice(&tampered);
            packet.set_read_len(tampered.len()).unwrap();
            assert!(
                cipher.unwrap(&mut packet, config.mode).is_err(),
                "tamper index {index}"
            );
        }
    }

    #[test]
    fn sixty_three_parallel_streams_survive_sustained_roundtrips() {
        let key = derive_wrap_key("parallel-stream-password").unwrap();
        std::thread::scope(|scope| {
            for stream in 0..63u8 {
                scope.spawn(move || {
                    let cipher = ObfsCipher::new(key).unwrap();
                    let config = ObfsConfig::new(if stream % 2 == 0 {
                        ObfsMode::Audio
                    } else {
                        ObfsMode::Video
                    });
                    let pool = PacketPool::new(1);
                    let state = ObfsState::new();
                    for sequence in 0..1_000u32 {
                        let payload = [stream, sequence as u8, (sequence >> 8) as u8, 0x5a];
                        let mut packet = pool.acquire();
                        packet.read_area()[..payload.len()].copy_from_slice(&payload);
                        packet.set_read_len(payload.len()).unwrap();
                        cipher.wrap(&mut packet, &config, &state).unwrap();
                        cipher.unwrap(&mut packet, config.mode).unwrap();
                        assert_eq!(packet.as_slice(), payload);
                    }
                });
            }
        });
    }

    #[test]
    fn unified_wire_strictly_bimodal_across_all_payload_sizes() {
        let key = derive_wrap_key("unified-bimodal-test-password").unwrap();
        let cipher = ObfsCipher::new(key).unwrap();
        let pool = PacketPool::new(1);
        let config = ObfsConfig::new(ObfsMode::Unified);
        let state = ObfsState::new();

        for size in (20..=1150).step_by(7) {
            let mut payload = vec![0u8; size];
            payload[0] = 0x45;
            payload[2..4].copy_from_slice(&(size as u16).to_be_bytes());
            for (i, b) in payload[20..].iter_mut().enumerate() {
                *b = ((i * 37 + size) % 256) as u8;
            }

            let mut packet = pool.acquire();
            packet.read_area()[..size].copy_from_slice(&payload);
            packet.set_read_len(size).unwrap();

            cipher.wrap(&mut packet, &config, &state).unwrap();
            let wire = packet.as_slice();

            assert!(is_rtp_packet(wire));
            let pt = wire[1] & 0x7f;
            const OVERHEAD_OUTSIDE_RTP: usize = 4 + 8 + 20;
            let total_wire = wire.len() + OVERHEAD_OUTSIDE_RTP;

            assert!(
                total_wire <= 1242,
                "total_wire {total_wire} exceeded 1242 for size {size}"
            );

            if size <= 350 {
                assert_eq!(pt, 111);
                assert!(
                    total_wire >= size + 67,
                    "audio total_wire {} < expected for size {}",
                    total_wire,
                    size
                );
            } else if total_wire <= 1050 {
                assert_eq!(pt, 96);
                assert!(
                    wire[1] & 0x80 != 0,
                    "expected marker bit 1 on P-frame wire size {total_wire}"
                );
            } else {
                assert_eq!(pt, 96);
            }

            cipher.unwrap(&mut packet, ObfsMode::Unified).unwrap();
            assert_eq!(packet.as_slice(), payload.as_slice());
        }
    }

    #[test]
    fn thirty_six_streams_in_unified_mode_parallel_integrity() {
        let key = derive_wrap_key("thirty-six-streams-password").unwrap();
        std::thread::scope(|scope| {
            for stream in 0..36u8 {
                scope.spawn(move || {
                    let cipher = ObfsCipher::new(key).unwrap();
                    let config = ObfsConfig::new(ObfsMode::Unified);
                    let pool = PacketPool::new(1);
                    let state = ObfsState::new();
                    let test_sizes = [40, 80, 133, 134, 250, 600, 1000, 1120, 1160];

                    for seq in 0..500u32 {
                        let size = test_sizes[(seq as usize + stream as usize) % test_sizes.len()];
                        let mut payload = vec![0u8; size];
                        payload[0] = 0x45;
                        payload[2..4].copy_from_slice(&(size as u16).to_be_bytes());
                        payload[4] = stream;
                        payload[5] = seq as u8;
                        payload[6] = (seq >> 8) as u8;

                        let mut packet = pool.acquire();
                        packet.read_area()[..size].copy_from_slice(&payload);
                        packet.set_read_len(size).unwrap();

                        cipher.wrap(&mut packet, &config, &state).unwrap();
                        let total_wire = packet.len() + 32;
                        assert!(total_wire <= 1242);

                        cipher.unwrap(&mut packet, ObfsMode::Unified).unwrap();
                        assert_eq!(packet.as_slice(), payload.as_slice());
                    }
                });
            }
        });
    }

    proptest! {
        #[test]
        fn arbitrary_wire_input_never_panics(wire in proptest::collection::vec(any::<u8>(), 0..=(PACKET_CAPACITY - PACKET_HEADROOM))) {
            let cipher = ObfsCipher::new([7u8; WRAP_KEY_LEN]).unwrap();
            for mode in [ObfsMode::Audio, ObfsMode::Video] {
                let pool = PacketPool::new(1);
                let mut packet = pool.acquire();
                packet.read_area()[..wire.len()].copy_from_slice(&wire);
                packet.set_read_len(wire.len()).unwrap();
                let _ = cipher.unwrap(&mut packet, mode);
            }
        }
    }
}
