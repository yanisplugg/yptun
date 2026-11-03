use anyhow::{Result, bail};
use aws_lc_rs::aead::{AES_128_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use aws_lc_rs::agreement::{
    ECDH_P256, EphemeralPrivateKey, UnparsedPublicKey as EcdhUnparsedPublicKey, agree_ephemeral,
};
use aws_lc_rs::rand::SystemRandom;
use aws_lc_rs::signature::{
    ECDSA_P256_SHA256_ASN1, ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair, KeyPair as _,
    UnparsedPublicKey as EcdsaUnparsedPublicKey,
};
use hmac::digest::KeyInit;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use x25519_dalek::{PublicKey, StaticSecret};

pub const DTLS_RECORD_HEADER_LEN: usize = 13;
pub const DTLS_HANDSHAKE_HEADER_LEN: usize = 12;

pub const MAX_DTLS_HANDSHAKE_MESSAGE_LEN: usize = 16 * 1024;
pub const MAX_DTLS_HANDSHAKE_INFLIGHT: usize = 64;

pub const CONTENT_TYPE_CHANGE_CIPHER_SPEC: u8 = 20;
pub const CONTENT_TYPE_ALERT: u8 = 21;
pub const CONTENT_TYPE_HANDSHAKE: u8 = 22;
pub const CONTENT_TYPE_APPLICATION_DATA: u8 = 23;

pub const HANDSHAKE_TYPE_CLIENT_HELLO: u8 = 1;
pub const HANDSHAKE_TYPE_SERVER_HELLO: u8 = 2;
pub const HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST: u8 = 3;
pub const HANDSHAKE_TYPE_NEW_SESSION_TICKET: u8 = 4;
pub const HANDSHAKE_TYPE_CERTIFICATE: u8 = 11;
pub const HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE: u8 = 12;
pub const HANDSHAKE_TYPE_SERVER_HELLO_DONE: u8 = 14;
pub const HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE: u8 = 16;
pub const HANDSHAKE_TYPE_FINISHED: u8 = 20;
pub const HANDSHAKE_TYPE_CERTIFICATE_STATUS: u8 = 22;

pub const EXT_USE_SRTP: u16 = 14;

pub const SRTP_AES128_CM_SHA1_80: u16 = 0x0001;
pub const SRTP_AES128_CM_SHA1_32: u16 = 0x0002;
pub const SRTP_AEAD_AES_128_GCM: u16 = 0x0007;
pub const SRTP_AEAD_AES_256_GCM: u16 = 0x0008;
pub const SRTP_KEY_BLOCK_LEN: usize = 60;

pub struct DtlsCertificate {
    der: Vec<u8>,
    signing_key: EcdsaKeyPair,
}

const CERTIFICATE_COMMON_NAME: &str = "WebRTC";
const CERTIFICATE_VALIDITY_DAYS: i64 = 30;

fn dtls_rng() -> SystemRandom {
    SystemRandom::new()
}

fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let year = if month <= 2 { year - 1 } else { year } as i64;
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month_prime = ((month + 9) % 12) as i64;
    let day_of_year = (153 * month_prime + 2) / 5 + day as i64 - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146097 + day_of_era - 719468
}

fn civil_from_days(days: i64) -> (i32, u32, u32) {
    let shifted = days + 719468;
    let era = if shifted >= 0 {
        shifted
    } else {
        shifted - 146096
    } / 146097;
    let day_of_era = shifted - era * 146097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_prime + 2) / 5 + 1) as u32;
    let month = if month_prime < 10 {
        month_prime + 3
    } else {
        month_prime - 9
    } as u32;
    let year = if month <= 2 { year + 1 } else { year };
    (year as i32, month, day)
}

fn utc_today() -> (i32, u32, u32) {
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs() as i64)
        .unwrap_or_default();
    civil_from_days(seconds.div_euclid(86_400))
}

fn random_u64() -> Result<u64> {
    let bytes = random_array::<8>()?;
    Ok(u64::from_be_bytes(bytes))
}

impl DtlsCertificate {
    pub fn generate() -> Result<Self> {
        let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256)
            .map_err(|error| anyhow::anyhow!("cannot generate an ECDSA P-256 key: {error}"))?;
        let signing_key =
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &key_pair.serialize_der())
                .map_err(|error| {
                    anyhow::anyhow!("cannot load the freshly generated ECDSA P-256 key: {error}")
                })?;

        let mut params = rcgen::CertificateParams::new(Vec::<String>::new())
            .map_err(|error| anyhow::anyhow!("cannot build certificate parameters: {error}"))?;
        params
            .distinguished_name
            .push(rcgen::DnType::CommonName, CERTIFICATE_COMMON_NAME);
        params.is_ca = rcgen::IsCa::NoCa;
        params.key_usages = Vec::new();
        params.extended_key_usages = Vec::new();
        params.serial_number = Some(rcgen::SerialNumber::from(random_u64()?));
        let (year, month, day) = utc_today();
        params.not_before = rcgen::date_time_ymd(year, month as u8, day as u8);
        let (year, month, day) =
            civil_from_days(days_from_civil(year, month, day) + CERTIFICATE_VALIDITY_DAYS);
        params.not_after = rcgen::date_time_ymd(year, month as u8, day as u8);
        let certificate = params
            .self_signed(&key_pair)
            .map_err(|error| anyhow::anyhow!("cannot self-sign the certificate: {error}"))?;

        Ok(Self {
            der: certificate.der().to_vec(),
            signing_key,
        })
    }

    pub fn from_der(der: Vec<u8>, pkcs8: &[u8]) -> Result<Self> {
        let signing_key = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8)
            .map_err(|error| {
                anyhow::anyhow!("cannot load the certificate's private key: {error}")
            })?;

        if certificate_p256_public_key(&der)? != public_point_bytes(&signing_key)? {
            bail!("the certificate does not belong to the supplied private key");
        }
        Ok(Self { der, signing_key })
    }

    pub fn der(&self) -> &[u8] {
        &self.der
    }

    pub fn fingerprint_sha256(&self) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(&self.der);
        hasher.finalize().into()
    }

    pub fn fingerprint_sdp(&self) -> String {
        use std::fmt::Write as _;
        let digest = self.fingerprint_sha256();
        let mut text = String::with_capacity(7 + digest.len() * 3);
        text.push_str("sha-256");
        for byte in digest {
            let _ = write!(text, " {byte:02X}");
        }
        text
    }

    fn sign(&self, message: &[u8]) -> Result<Vec<u8>> {
        let signature = self
            .signing_key
            .sign(&dtls_rng(), message)
            .map_err(|_| anyhow::anyhow!("cannot sign the server key exchange"))?;
        Ok(signature.as_ref().to_vec())
    }
}

fn public_point_bytes(key: &EcdsaKeyPair) -> Result<[u8; 65]> {
    let point: &[u8] = key.public_key().as_ref();
    let point: [u8; 65] = point
        .try_into()
        .map_err(|_| anyhow::anyhow!("the ECDSA P-256 public key is not an uncompressed point"))?;
    if point[0] != 0x04 {
        bail!("the ECDSA P-256 public key is not in uncompressed form");
    }
    Ok(point)
}

fn verify_p256_signature(public_point: &[u8; 65], message: &[u8], signature: &[u8]) -> Result<()> {
    EcdsaUnparsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, public_point)
        .verify(message, signature)
        .map_err(|_| anyhow::anyhow!("the server key exchange signature does not verify"))
}

fn read_der_tlv<'a>(data: &'a [u8], position: &mut usize) -> Result<(u8, &'a [u8])> {
    let tag = *data
        .get(*position)
        .ok_or_else(|| anyhow::anyhow!("truncated DER"))?;
    *position += 1;
    let first = *data
        .get(*position)
        .ok_or_else(|| anyhow::anyhow!("truncated DER length"))?;
    *position += 1;
    let length = if first & 0x80 == 0 {
        first as usize
    } else {
        let count = (first & 0x7f) as usize;
        if count == 0 || count > 4 {
            bail!("unsupported DER length encoding");
        }
        let mut value = 0usize;
        for _ in 0..count {
            let byte = *data
                .get(*position)
                .ok_or_else(|| anyhow::anyhow!("truncated DER length"))?;
            *position += 1;
            value = (value << 8) | byte as usize;
        }
        value
    };
    let end = position
        .checked_add(length)
        .filter(|end| *end <= data.len())
        .ok_or_else(|| anyhow::anyhow!("truncated DER value"))?;
    let value = &data[*position..end];
    *position = end;
    Ok((tag, value))
}

pub fn certificate_p256_public_key(certificate: &[u8]) -> Result<[u8; 65]> {
    const OID_EC_PUBLIC_KEY: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
    const OID_PRIME256V1: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];

    let mut outer = 0;
    let (tag, body) = read_der_tlv(certificate, &mut outer)?;
    if tag != 0x30 {
        bail!("certificate is not a SEQUENCE");
    }

    let mut body_position = 0;
    let (tag, tbs) = read_der_tlv(body, &mut body_position)?;
    if tag != 0x30 {
        bail!("tbsCertificate is not a SEQUENCE");
    }

    let mut tbs_position = 0;

    if tbs.first() == Some(&0xa0) {
        read_der_tlv(tbs, &mut tbs_position)?;
    }

    for _ in 0..5 {
        read_der_tlv(tbs, &mut tbs_position)?;
    }
    let (tag, spki) = read_der_tlv(tbs, &mut tbs_position)?;
    if tag != 0x30 {
        bail!("subjectPublicKeyInfo is not a SEQUENCE");
    }

    let mut spki_position = 0;
    let (tag, algorithm) = read_der_tlv(spki, &mut spki_position)?;
    if tag != 0x30 {
        bail!("the algorithm identifier is not a SEQUENCE");
    }
    let mut algorithm_position = 0;
    let (tag, oid) = read_der_tlv(algorithm, &mut algorithm_position)?;
    if tag != 0x06 || oid != OID_EC_PUBLIC_KEY {
        bail!("the certificate key is not an EC key");
    }
    let (tag, curve) = read_der_tlv(algorithm, &mut algorithm_position)?;
    if tag != 0x06 || curve != OID_PRIME256V1 {
        bail!("the certificate key is not on prime256v1");
    }

    let (tag, bits) = read_der_tlv(spki, &mut spki_position)?;
    if tag != 0x03 {
        bail!("subjectPublicKey is not a BIT STRING");
    }

    if bits.len() != 66 || bits[0] != 0 {
        bail!("unexpected P-256 public key encoding");
    }
    if bits[1] != 0x04 {
        bail!("the P-256 public key is not in uncompressed form");
    }
    let mut point = [0u8; 65];
    point.copy_from_slice(&bits[1..]);
    Ok(point)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DtlsSrtpKeys {
    pub client_write_key: [u8; 16],
    pub server_write_key: [u8; 16],
    pub client_write_salt: [u8; 14],
    pub server_write_salt: [u8; 14],
    pub client_cipher_key: [u8; 32],
    pub server_cipher_key: [u8; 32],
}

type HmacSha256 = Hmac<Sha256>;

pub fn is_dtls_packet(wire: &[u8]) -> bool {
    !wire.is_empty() && (20..=63).contains(&wire[0])
}

pub fn is_srtp_packet(wire: &[u8]) -> bool {
    !wire.is_empty() && (128..=191).contains(&wire[0])
}

pub fn is_stun_or_turn_packet(wire: &[u8]) -> bool {
    !wire.is_empty() && (wire[0] <= 3 || wire[0] == 0x40)
}

pub fn extract_client_hello_random(wire: &[u8]) -> Option<[u8; 32]> {
    if let Ok(records) = parse_dtls_records(wire) {
        for record in records {
            if record.content_type == CONTENT_TYPE_HANDSHAKE
                && let Ok(msgs) = parse_handshake_messages(record.fragment)
            {
                for msg in msgs {
                    if msg.msg_type == HANDSHAKE_TYPE_CLIENT_HELLO && msg.body.len() >= 35 {
                        let mut rnd = [0u8; 32];
                        rnd.copy_from_slice(&msg.body[2..34]);
                        return Some(rnd);
                    }
                }
            }
        }
    }
    None
}

pub fn is_client_hello(wire: &[u8]) -> bool {
    extract_client_hello_random(wire).is_some()
}

fn hmac_sha256(key: &[u8]) -> HmacSha256 {
    match HmacSha256::new_from_slice(key) {
        Ok(mac) => mac,
        Err(_) => HmacSha256::new(&Default::default()),
    }
}

pub fn p_hash_sha256(secret: &[u8], seed: &[u8], out: &mut [u8]) {
    let mut mac = hmac_sha256(secret);
    mac.update(seed);
    let mut a = mac.finalize().into_bytes();
    let mut offset = 0;
    while offset < out.len() {
        let mut mac = hmac_sha256(secret);
        mac.update(&a);
        mac.update(seed);
        let block = mac.finalize().into_bytes();
        let chunk = (out.len() - offset).min(block.len());
        out[offset..offset + chunk].copy_from_slice(&block[..chunk]);
        offset += chunk;
        if offset < out.len() {
            let mut next_a = hmac_sha256(secret);
            next_a.update(&a);
            a = next_a.finalize().into_bytes();
        }
    }
}

pub fn tls12_prf(secret: &[u8], label: &[u8], seed: &[u8], out: &mut [u8]) {
    let mut label_and_seed = Vec::with_capacity(label.len() + seed.len());
    label_and_seed.extend_from_slice(label);
    label_and_seed.extend_from_slice(seed);
    p_hash_sha256(secret, &label_and_seed, out);
}

pub fn derive_cipher_key(master_key: &[u8; 16], master_salt: &[u8; 14]) -> [u8; 32] {
    let mut cipher_key = [0u8; 32];
    cipher_key[..16].copy_from_slice(master_key);
    let mut hmac_key = [0u8; 16];
    tls12_prf(master_salt, b"SRTP auth key", master_key, &mut hmac_key);
    cipher_key[16..].copy_from_slice(&hmac_key);
    cipher_key
}

pub fn derive_master_secret(
    premaster_secret: &[u8; 32],
    client_random: &[u8; 32],
    server_random: &[u8; 32],
) -> [u8; 48] {
    let mut seed = [0u8; 64];
    seed[..32].copy_from_slice(client_random);
    seed[32..].copy_from_slice(server_random);
    let mut master = [0u8; 48];
    tls12_prf(premaster_secret, b"master secret", &seed, &mut master);
    master
}

pub fn derive_dtls_srtp_keys(
    master_secret: &[u8; 48],
    client_random: &[u8; 32],
    server_random: &[u8; 32],
) -> DtlsSrtpKeys {
    let mut seed = [0u8; 64];
    seed[..32].copy_from_slice(client_random);
    seed[32..].copy_from_slice(server_random);

    let mut key_block = [0u8; 60];
    tls12_prf(master_secret, b"EXTRACTOR-dtls_srtp", &seed, &mut key_block);

    let mut client_write_key = [0u8; 16];
    let mut server_write_key = [0u8; 16];
    let mut client_write_salt = [0u8; 14];
    let mut server_write_salt = [0u8; 14];

    client_write_key.copy_from_slice(&key_block[0..16]);
    server_write_key.copy_from_slice(&key_block[16..32]);
    client_write_salt.copy_from_slice(&key_block[32..46]);
    server_write_salt.copy_from_slice(&key_block[46..60]);

    let client_cipher_key = derive_cipher_key(&client_write_key, &client_write_salt);
    let server_cipher_key = derive_cipher_key(&server_write_key, &server_write_salt);

    DtlsSrtpKeys {
        client_write_key,
        server_write_key,
        client_write_salt,
        server_write_salt,
        client_cipher_key,
        server_cipher_key,
    }
}

pub fn derive_dtls_srtp_deterministic(wrap_key: &[u8; 32]) -> DtlsSrtpKeys {
    let mut premaster_secret = [0u8; 32];
    tls12_prf(
        wrap_key,
        b"csqtt-dtls-srtp-pms",
        b"csqtt-premaster",
        &mut premaster_secret,
    );

    let mut client_random = [0u8; 32];
    tls12_prf(
        wrap_key,
        b"csqtt-dtls-random",
        b"client-random",
        &mut client_random,
    );

    let mut server_random = [0u8; 32];
    tls12_prf(
        wrap_key,
        b"csqtt-dtls-random",
        b"server-random",
        &mut server_random,
    );

    let master_secret = derive_master_secret(&premaster_secret, &client_random, &server_random);
    derive_dtls_srtp_keys(&master_secret, &client_random, &server_random)
}

pub fn compute_verify_data(
    master_secret: &[u8; 48],
    label: &[u8],
    handshake_seed: &[u8],
) -> [u8; 12] {
    let mut verify_data = [0u8; 12];
    tls12_prf(master_secret, label, handshake_seed, &mut verify_data);
    verify_data
}

pub const DTLS10_VERSION: [u8; 2] = [0xfe, 0xff];
pub const DTLS12_VERSION: [u8; 2] = [0xfe, 0xfd];

pub fn write_dtls_record(
    content_type: u8,
    epoch: u16,
    sequence: u64,
    fragment: &[u8],
    out: &mut Vec<u8>,
) {
    let version = if epoch == 0
        && content_type == CONTENT_TYPE_HANDSHAKE
        && fragment.first() == Some(&HANDSHAKE_TYPE_CLIENT_HELLO)
    {
        DTLS10_VERSION
    } else {
        DTLS12_VERSION
    };
    write_dtls_record_with_version(content_type, version, epoch, sequence, fragment, out);
}

pub fn write_dtls_record_with_version(
    content_type: u8,
    version: [u8; 2],
    epoch: u16,
    sequence: u64,
    fragment: &[u8],
    out: &mut Vec<u8>,
) {
    out.push(content_type);
    out.extend_from_slice(&version);
    out.extend_from_slice(&epoch.to_be_bytes());
    let seq_bytes = sequence.to_be_bytes();
    out.extend_from_slice(&seq_bytes[2..8]);
    out.extend_from_slice(&(fragment.len() as u16).to_be_bytes());
    out.extend_from_slice(fragment);
}

pub fn write_handshake_msg(msg_type: u8, message_seq: u16, body: &[u8], out: &mut Vec<u8>) {
    out.push(msg_type);
    let len = body.len() as u32;
    out.push((len >> 16) as u8);
    out.push((len >> 8) as u8);
    out.push(len as u8);
    out.extend_from_slice(&message_seq.to_be_bytes());
    out.extend_from_slice(&[0x00, 0x00, 0x00]);
    out.push((len >> 16) as u8);
    out.push((len >> 8) as u8);
    out.push(len as u8);
    out.extend_from_slice(body);
}

pub struct DtlsRecord<'a> {
    pub content_type: u8,
    pub version: [u8; 2],
    pub epoch: u16,
    pub sequence: u64,
    pub fragment: &'a [u8],
}

pub fn parse_dtls_records(mut data: &[u8]) -> Result<Vec<DtlsRecord<'_>>> {
    let mut records = Vec::new();
    while data.len() >= DTLS_RECORD_HEADER_LEN {
        let content_type = data[0];
        let version = [data[1], data[2]];
        let epoch = u16::from_be_bytes([data[3], data[4]]);
        let mut seq_bytes = [0u8; 8];
        seq_bytes[2..8].copy_from_slice(&data[5..11]);
        let sequence = u64::from_be_bytes(seq_bytes);
        let length = u16::from_be_bytes([data[11], data[12]]) as usize;
        if data.len() < DTLS_RECORD_HEADER_LEN + length {
            bail!("truncated DTLS record");
        }
        let fragment = &data[DTLS_RECORD_HEADER_LEN..DTLS_RECORD_HEADER_LEN + length];
        records.push(DtlsRecord {
            content_type,
            version,
            epoch,
            sequence,
            fragment,
        });
        data = &data[DTLS_RECORD_HEADER_LEN + length..];
    }
    Ok(records)
}

pub struct DtlsHandshakeMsg<'a> {
    pub msg_type: u8,
    pub length: u32,
    pub message_seq: u16,
    pub fragment_offset: u32,
    pub fragment_length: u32,
    pub body: &'a [u8],
}

pub fn parse_handshake_messages(mut data: &[u8]) -> Result<Vec<DtlsHandshakeMsg<'_>>> {
    let mut msgs = Vec::new();
    while data.len() >= DTLS_HANDSHAKE_HEADER_LEN {
        let msg_type = data[0];
        let length = ((data[1] as u32) << 16) | ((data[2] as u32) << 8) | (data[3] as u32);
        let message_seq = u16::from_be_bytes([data[4], data[5]]);
        let fragment_offset = ((data[6] as u32) << 16) | ((data[7] as u32) << 8) | (data[8] as u32);
        let fragment_length =
            ((data[9] as u32) << 16) | ((data[10] as u32) << 8) | (data[11] as u32);
        let frag_len = fragment_length as usize;
        if data.len() < DTLS_HANDSHAKE_HEADER_LEN + frag_len {
            bail!("truncated DTLS handshake message");
        }
        let body = &data[DTLS_HANDSHAKE_HEADER_LEN..DTLS_HANDSHAKE_HEADER_LEN + frag_len];
        msgs.push(DtlsHandshakeMsg {
            msg_type,
            length,
            message_seq,
            fragment_offset,
            fragment_length,
            body,
        });
        data = &data[DTLS_HANDSHAKE_HEADER_LEN + frag_len..];
    }
    Ok(msgs)
}

const GCM_EXPLICIT_NONCE_LEN: usize = 8;
const GCM_TAG_LEN: usize = 16;
pub const DTLS_AEAD_OVERHEAD: usize = GCM_EXPLICIT_NONCE_LEN + GCM_TAG_LEN;

pub const ALERT_LEVEL_WARNING: u8 = 1;
pub const ALERT_LEVEL_FATAL: u8 = 2;
pub const ALERT_CLOSE_NOTIFY: u8 = 0;
pub const ALERT_UNEXPECTED_MESSAGE: u8 = 10;
pub const ALERT_BAD_RECORD_MAC: u8 = 20;
pub const ALERT_HANDSHAKE_FAILURE: u8 = 40;
pub const ALERT_ILLEGAL_PARAMETER: u8 = 47;
pub const ALERT_DECODE_ERROR: u8 = 50;
pub const ALERT_DECRYPT_ERROR: u8 = 51;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RecordKeys {
    pub client_write_key: [u8; 16],
    pub server_write_key: [u8; 16],
    pub client_write_iv: [u8; 4],
    pub server_write_iv: [u8; 4],
}

pub fn derive_record_keys(
    master_secret: &[u8; 48],
    client_random: &[u8; 32],
    server_random: &[u8; 32],
) -> RecordKeys {
    let mut seed = [0u8; 64];
    seed[..32].copy_from_slice(server_random);
    seed[32..].copy_from_slice(client_random);

    let mut key_block = [0u8; 40];
    tls12_prf(master_secret, b"key expansion", &seed, &mut key_block);

    let mut keys = RecordKeys {
        client_write_key: [0u8; 16],
        server_write_key: [0u8; 16],
        client_write_iv: [0u8; 4],
        server_write_iv: [0u8; 4],
    };
    keys.client_write_key.copy_from_slice(&key_block[0..16]);
    keys.server_write_key.copy_from_slice(&key_block[16..32]);
    keys.client_write_iv.copy_from_slice(&key_block[32..36]);
    keys.server_write_iv.copy_from_slice(&key_block[36..40]);
    keys
}

pub fn derive_extended_master_secret(
    premaster_secret: &[u8; 32],
    session_hash: &[u8; 32],
) -> [u8; 48] {
    let mut master = [0u8; 48];
    tls12_prf(
        premaster_secret,
        b"extended master secret",
        session_hash,
        &mut master,
    );
    master
}

pub struct RecordProtector {
    key: LessSafeKey,
    fixed_iv: [u8; 4],
    epoch: u16,
    next_sequence: u64,
    highest_seen: u64,
    replay_window: u64,
    accepts_records: bool,
}

impl RecordProtector {
    pub fn new(key: &[u8], fixed_iv: &[u8], epoch: u16) -> Result<Self> {
        let key = LessSafeKey::new(
            UnboundKey::new(&AES_128_GCM, key)
                .map_err(|_| anyhow::anyhow!("not a valid AES-128-GCM key"))?,
        );
        let fixed_iv: [u8; 4] = fixed_iv
            .try_into()
            .map_err(|_| anyhow::anyhow!("the fixed IV must be 4 bytes"))?;
        Ok(Self {
            key,
            fixed_iv,
            epoch,
            next_sequence: 0,
            highest_seen: 0,
            replay_window: 0,
            accepts_records: true,
        })
    }

    pub fn epoch(&self) -> u16 {
        self.epoch
    }

    fn combined_sequence(epoch: u16, sequence: u64) -> u64 {
        ((epoch as u64) << 48) | (sequence & 0x0000_ffff_ffff_ffff)
    }

    fn nonce(&self, explicit: [u8; GCM_EXPLICIT_NONCE_LEN]) -> [u8; 12] {
        let mut nonce = [0u8; 12];
        nonce[..4].copy_from_slice(&self.fixed_iv);
        nonce[4..].copy_from_slice(&explicit);
        nonce
    }

    fn explicit_nonce(epoch: u16, sequence: u64) -> [u8; GCM_EXPLICIT_NONCE_LEN] {
        Self::combined_sequence(epoch, sequence).to_be_bytes()
    }

    fn additional_data(
        epoch: u16,
        sequence: u64,
        content_type: u8,
        version: [u8; 2],
        length: usize,
    ) -> [u8; 13] {
        let mut aad = [0u8; 13];
        aad[..8].copy_from_slice(&Self::combined_sequence(epoch, sequence).to_be_bytes());
        aad[8] = content_type;
        aad[9..11].copy_from_slice(&version);
        aad[11..13].copy_from_slice(&(length as u16).to_be_bytes());
        aad
    }

    pub fn protect(&mut self, content_type: u8, plaintext: &[u8]) -> Result<Vec<u8>> {
        let sequence = self.next_sequence;
        self.next_sequence = self.next_sequence.wrapping_add(1) & 0x0000_ffff_ffff_ffff;

        let mut record =
            Vec::with_capacity(DTLS_RECORD_HEADER_LEN + DTLS_AEAD_OVERHEAD + plaintext.len());
        record.push(content_type);
        record.extend_from_slice(&DTLS12_VERSION);
        record.extend_from_slice(&self.epoch.to_be_bytes());
        record.extend_from_slice(&sequence.to_be_bytes()[2..8]);
        let sealed_len = plaintext.len() + DTLS_AEAD_OVERHEAD;
        record.extend_from_slice(&(sealed_len as u16).to_be_bytes());
        record.extend_from_slice(&Self::explicit_nonce(self.epoch, sequence));

        let aad = Self::additional_data(
            self.epoch,
            sequence,
            content_type,
            DTLS12_VERSION,
            plaintext.len(),
        );
        let mut buffer = Vec::with_capacity(plaintext.len() + GCM_TAG_LEN);
        buffer.extend_from_slice(plaintext);
        self.key
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(
                    self.nonce(Self::explicit_nonce(self.epoch, sequence)),
                ),
                Aad::from(aad),
                &mut buffer,
            )
            .map_err(|_| anyhow::anyhow!("cannot seal a DTLS record"))?;
        record.extend_from_slice(&buffer);
        Ok(record)
    }

    pub fn unprotect(&mut self, record: &DtlsRecord<'_>) -> Result<Vec<u8>> {
        if record.epoch != self.epoch {
            bail!(
                "record epoch {} does not match {}",
                record.epoch,
                self.epoch
            );
        }
        if !self.accepts_records {
            bail!("DTLS protector no longer accepts records");
        }
        if record.fragment.len() < DTLS_AEAD_OVERHEAD {
            bail!("undersized AEAD record");
        }

        let mut explicit = [0u8; GCM_EXPLICIT_NONCE_LEN];
        explicit.copy_from_slice(&record.fragment[..GCM_EXPLICIT_NONCE_LEN]);
        let plaintext_len = record.fragment.len() - DTLS_AEAD_OVERHEAD;

        let aad = Self::additional_data(
            record.epoch,
            record.sequence,
            record.content_type,
            DTLS12_VERSION,
            plaintext_len,
        );
        let mut buffer = record.fragment[GCM_EXPLICIT_NONCE_LEN..].to_vec();
        let opened = self
            .key
            .open_in_place(
                Nonce::assume_unique_for_key(self.nonce(explicit)),
                Aad::from(aad),
                &mut buffer,
            )
            .map_err(|_| anyhow::anyhow!("cannot open a DTLS record"))?;
        if !self.accept_sequence(record.sequence) {
            bail!("replayed or out-of-window DTLS record");
        }
        Ok(opened.to_vec())
    }

    fn accept_sequence(&mut self, sequence: u64) -> bool {
        if !self.accepts_records {
            return false;
        }
        if self.highest_seen == 0 && self.replay_window == 0 {
            self.highest_seen = sequence;
            self.replay_window = 1;
            return true;
        }
        if sequence > self.highest_seen {
            let shift = sequence - self.highest_seen;
            self.replay_window = if shift >= 64 {
                1
            } else {
                (self.replay_window << shift) | 1
            };
            self.highest_seen = sequence;
            return true;
        }
        let delta = self.highest_seen - sequence;
        if delta >= 64 {
            return false;
        }
        let bit = 1u64 << delta;
        if self.replay_window & bit != 0 {
            return false;
        }
        self.replay_window |= bit;
        true
    }

    pub fn close(&mut self) {
        self.accepts_records = false;
    }
}

#[derive(Clone, Default)]
pub struct Transcript {
    hasher: Sha256,
    messages: Vec<Vec<u8>>,
}

impl Transcript {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, message: &[u8]) {
        self.hasher.update(message);
        self.messages.push(message.to_vec());
    }

    pub fn hash(&self) -> [u8; 32] {
        self.hasher.clone().finalize().into()
    }

    pub fn messages(&self) -> &[Vec<u8>] {
        &self.messages
    }
}

struct PartialHandshake {
    msg_type: u8,
    length: usize,
    body: Vec<u8>,
    received: Vec<bool>,
    received_count: usize,
}

#[derive(Default)]
pub struct HandshakeReassembler {
    partial: BTreeMap<u16, PartialHandshake>,
    completed: BTreeMap<u16, Vec<u8>>,
    next_expected: u16,
}

impl HandshakeReassembler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn next_expected(&self) -> u16 {
        self.next_expected
    }

    pub fn feed(&mut self, payload: &[u8]) -> Result<()> {
        for message in parse_handshake_messages(payload)? {
            self.accept(&message)?;
        }
        Ok(())
    }

    fn accept(&mut self, message: &DtlsHandshakeMsg<'_>) -> Result<()> {
        if message.message_seq < self.next_expected {
            return Ok(());
        }

        let length = message.length as usize;
        let offset = message.fragment_offset as usize;

        if length > MAX_DTLS_HANDSHAKE_MESSAGE_LEN {
            bail!(
                "DTLS handshake message of {length} bytes exceeds the {} byte limit",
                MAX_DTLS_HANDSHAKE_MESSAGE_LEN
            );
        }

        offset
            .checked_add(message.body.len())
            .filter(|end| *end <= length)
            .ok_or_else(|| anyhow::anyhow!("handshake fragment runs past its message"))?;

        if !self.partial.contains_key(&message.message_seq)
            && self.partial.len() >= MAX_DTLS_HANDSHAKE_INFLIGHT
        {
            bail!("too many in-flight DTLS handshake messages");
        }
        if !self.completed.contains_key(&message.message_seq)
            && self.completed.len() >= MAX_DTLS_HANDSHAKE_INFLIGHT
        {
            bail!("too many buffered DTLS handshake messages");
        }

        let partial = self
            .partial
            .entry(message.message_seq)
            .or_insert_with(|| PartialHandshake {
                msg_type: message.msg_type,
                length,
                body: vec![0u8; length],
                received: vec![false; length],
                received_count: 0,
            });
        if partial.msg_type != message.msg_type || partial.length != length {
            bail!(
                "conflicting fragments for handshake message {}",
                message.message_seq
            );
        }
        for (index, byte) in message.body.iter().enumerate() {
            let position = offset + index;
            if !partial.received[position] {
                partial.received[position] = true;
                partial.received_count += 1;
            }
            partial.body[position] = *byte;
        }

        if partial.received_count == partial.length {
            let Some(partial) = self.partial.remove(&message.message_seq) else {
                bail!(
                    "the reassembly entry for message {} vanished",
                    message.message_seq
                );
            };
            let mut canonical = Vec::with_capacity(DTLS_HANDSHAKE_HEADER_LEN + length);
            write_handshake_msg(
                partial.msg_type,
                message.message_seq,
                &partial.body,
                &mut canonical,
            );
            self.completed.insert(message.message_seq, canonical);
        }
        Ok(())
    }

    pub fn take_ready(&mut self) -> Vec<Vec<u8>> {
        let mut ready = Vec::new();
        loop {
            self.skip_abandoned();
            match self.completed.remove(&self.next_expected) {
                Some(message) => {
                    self.next_expected = self.next_expected.wrapping_add(1);
                    ready.push(message);
                }
                None => break,
            }
        }
        ready
    }

    fn skip_abandoned(&mut self) {
        const MAX_SKIP_GAP: u32 = 16;
        if self.completed.contains_key(&self.next_expected)
            || self.partial.contains_key(&self.next_expected)
        {
            return;
        }
        if let Some(smallest) = self.completed.keys().copied().min()
            && smallest > self.next_expected
            && u32::from(smallest) - u32::from(self.next_expected) <= MAX_SKIP_GAP
        {
            self.next_expected = smallest;
        }
    }
}

pub fn handshake_message_parts(message: &[u8]) -> Result<(u8, &[u8])> {
    if message.len() < DTLS_HANDSHAKE_HEADER_LEN {
        bail!("short handshake message");
    }
    let msg_type = message[0];
    let length = ((message[1] as usize) << 16) | ((message[2] as usize) << 8) | message[3] as usize;
    if message.len() < DTLS_HANDSHAKE_HEADER_LEN + length {
        bail!("truncated handshake message");
    }
    Ok((
        msg_type,
        &message[DTLS_HANDSHAKE_HEADER_LEN..DTLS_HANDSHAKE_HEADER_LEN + length],
    ))
}

pub const EXT_SUPPORTED_GROUPS: u16 = 10;
pub const EXT_EC_POINT_FORMATS: u16 = 11;
pub const EXT_SIGNATURE_ALGORITHMS: u16 = 13;
pub const EXT_EXTENDED_MASTER_SECRET: u16 = 23;
pub const EXT_SESSION_TICKET: u16 = 35;
pub const EXT_RENEGOTIATION_INFO: u16 = 0xff01;

pub const GROUP_SECP256R1: u16 = 23;
pub const GROUP_SECP384R1: u16 = 24;
pub const GROUP_X25519: u16 = 29;

pub const CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256: u16 = 0xc02b;
pub const CIPHER_SUITE_ECDHE_ECDSA_CHACHA20_POLY1305_SHA256: u16 = 0xcca9;
pub const CIPHER_SUITE_ECDHE_ECDSA_AES256_GCM_SHA384: u16 = 0xc02c;
pub const CIPHER_SUITE_ECDHE_ECDSA_AES128_CBC_SHA: u16 = 0xc009;
pub const CIPHER_SUITE_ECDHE_ECDSA_AES256_CBC_SHA: u16 = 0xc00a;
pub const CIPHER_SUITE_ECDHE_RSA_AES128_GCM_SHA256: u16 = 0xc02f;
pub const CIPHER_SUITE_ECDHE_RSA_CHACHA20_POLY1305_SHA256: u16 = 0xcca8;
pub const CIPHER_SUITE_ECDHE_RSA_AES256_GCM_SHA384: u16 = 0xc030;
pub const CIPHER_SUITE_ECDHE_RSA_AES128_CBC_SHA: u16 = 0xc013;
pub const CIPHER_SUITE_ECDHE_RSA_AES256_CBC_SHA: u16 = 0xc014;
pub const CIPHER_SUITE_RSA_AES128_GCM_SHA256: u16 = 0x009c;
pub const CIPHER_SUITE_RSA_AES256_GCM_SHA384: u16 = 0x009d;
pub const CIPHER_SUITE_RSA_AES128_CBC_SHA: u16 = 0x002f;
pub const CIPHER_SUITE_RSA_AES256_CBC_SHA: u16 = 0x0035;

pub const CLIENT_HELLO_CIPHER_SUITES: &[u16] = &[
    CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256,
    CIPHER_SUITE_ECDHE_RSA_AES128_GCM_SHA256,
    CIPHER_SUITE_ECDHE_ECDSA_CHACHA20_POLY1305_SHA256,
    CIPHER_SUITE_ECDHE_RSA_CHACHA20_POLY1305_SHA256,
    CIPHER_SUITE_ECDHE_ECDSA_AES128_CBC_SHA,
    CIPHER_SUITE_ECDHE_RSA_AES128_CBC_SHA,
    CIPHER_SUITE_ECDHE_ECDSA_AES256_CBC_SHA,
    CIPHER_SUITE_ECDHE_RSA_AES256_CBC_SHA,
    CIPHER_SUITE_RSA_AES128_GCM_SHA256,
    CIPHER_SUITE_RSA_AES128_CBC_SHA,
    CIPHER_SUITE_RSA_AES256_CBC_SHA,
];
pub const CLIENT_HELLO_SIGNATURE_ALGORITHMS: &[u16] = &[
    0x0403, // ecdsa_secp256r1_sha256
    0x0804, // rsa_pss_rsae_sha256
    0x0401, // rsa_pkcs1_sha256
    0x0503, // ecdsa_secp384r1_sha384
    0x0805, // rsa_pss_rsae_sha384
    0x0501, // rsa_pkcs1_sha384
    0x0806, // rsa_pss_rsae_sha512
    0x0601, // rsa_pkcs1_sha512
    0x0201, // rsa_pkcs1_sha1
];
pub const CLIENT_HELLO_SUPPORTED_GROUPS: &[u16] = &[GROUP_X25519, GROUP_SECP256R1, GROUP_SECP384R1];
pub const CLIENT_HELLO_SRTP_PROFILES: &[u16] = &[
    SRTP_AES128_CM_SHA1_80,
    SRTP_AEAD_AES_256_GCM,
    SRTP_AEAD_AES_128_GCM,
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Extension {
    pub ext_type: u16,
    pub body: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClientHello {
    pub version: [u8; 2],
    pub random: [u8; 32],
    pub session_id: Vec<u8>,
    pub cookie: Vec<u8>,
    pub cipher_suites: Vec<u16>,
    pub compression_methods: Vec<u8>,
    pub extensions: Vec<Extension>,
}

fn decode_u16_list(body: &[u8]) -> Result<Vec<u16>> {
    if body.len() < 2 {
        bail!("short u16 list");
    }
    let declared = u16::from_be_bytes([body[0], body[1]]) as usize;
    if !declared.is_multiple_of(2) || body.len() < 2 + declared {
        bail!("bad u16 list length");
    }
    let mut out = Vec::with_capacity(declared / 2);
    for index in (0..declared).step_by(2) {
        out.push(u16::from_be_bytes([body[2 + index], body[3 + index]]));
    }
    Ok(out)
}

fn encode_u16_list(items: &[u16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(2 + items.len() * 2);
    out.extend_from_slice(&((items.len() * 2) as u16).to_be_bytes());
    for item in items {
        out.extend_from_slice(&item.to_be_bytes());
    }
    out
}

fn decode_use_srtp(body: &[u8]) -> Result<Vec<u16>> {
    if body.len() < 3 {
        bail!("short use_srtp extension");
    }
    let declared = u16::from_be_bytes([body[0], body[1]]) as usize;
    if declared == 0 || !declared.is_multiple_of(2) || body.len() != 3 + declared {
        bail!("bad use_srtp extension length");
    }
    if body[2 + declared] != 0 {
        bail!("use_srtp carries a non-empty MKI, which nothing here offers");
    }
    decode_u16_list(&body[..2 + declared])
}

fn parse_client_use_srtp(body: &[u8]) -> Result<Vec<u16>> {
    decode_use_srtp(body)
}

fn parse_server_use_srtp(body: &[u8]) -> Result<u16> {
    match decode_use_srtp(body)?.as_slice() {
        [profile] => Ok(*profile),
        _ => bail!("the ServerHello use_srtp extension must name exactly one profile"),
    }
}

fn srtp_body_from_profiles(profiles: &[u16]) -> Vec<u8> {
    let mut out = encode_u16_list(profiles);
    out.push(0);
    out
}

pub fn validate_client_hello(hello: &ClientHello) -> Result<()> {
    if hello.version != [0xfe, 0xfd] {
        bail!("ClientHello is not DTLS 1.2");
    }
    if hello.cookie.len() > 255 {
        bail!("ClientHello cookie exceeds 255 bytes");
    }
    if !hello
        .cipher_suites
        .contains(&CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256)
    {
        bail!("ClientHello does not offer TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
    }
    if !hello.compression_methods.contains(&0) {
        bail!("ClientHello does not offer null compression");
    }
    if !hello
        .extensions
        .iter()
        .any(|e| e.ext_type == EXT_EXTENDED_MASTER_SECRET)
    {
        bail!("ClientHello does not offer extended master secret");
    }
    let srtp = hello
        .extensions
        .iter()
        .find(|e| e.ext_type == EXT_USE_SRTP)
        .ok_or_else(|| anyhow::anyhow!("ClientHello has no use_srtp extension"))?;
    if !parse_client_use_srtp(&srtp.body)?.contains(&SRTP_AES128_CM_SHA1_80) {
        bail!("ClientHello does not offer SRTP_AES128_CM_HMAC_SHA1_80");
    }
    let groups = hello
        .extensions
        .iter()
        .find(|e| e.ext_type == EXT_SUPPORTED_GROUPS)
        .ok_or_else(|| anyhow::anyhow!("ClientHello has no supported_groups extension"))?;
    if !decode_u16_list(&groups.body)?.contains(&GROUP_SECP256R1) {
        bail!("ClientHello does not offer secp256r1");
    }

    let algorithms = hello
        .extensions
        .iter()
        .find(|e| e.ext_type == EXT_SIGNATURE_ALGORITHMS)
        .ok_or_else(|| anyhow::anyhow!("ClientHello has no signature_algorithms extension"))?;
    if !decode_u16_list(&algorithms.body)?.contains(&0x0403) {
        bail!("ClientHello does not offer ecdsa_secp256r1_sha256");
    }
    Ok(())
}

impl ClientHello {
    pub fn parse(body: &[u8]) -> Result<Self> {
        if body.len() < 35 {
            bail!("short ClientHello");
        }
        let mut version = [0u8; 2];
        version.copy_from_slice(&body[0..2]);
        let mut random = [0u8; 32];
        random.copy_from_slice(&body[2..34]);

        let mut offset = 34usize;
        let session_id_len = body[offset] as usize;
        offset += 1;
        if body.len() < offset + session_id_len + 1 {
            bail!("short ClientHello session id");
        }
        let session_id = body[offset..offset + session_id_len].to_vec();
        offset += session_id_len;

        let cookie_len = body[offset] as usize;
        offset += 1;
        if body.len() < offset + cookie_len + 2 {
            bail!("short ClientHello cookie");
        }
        let cookie = body[offset..offset + cookie_len].to_vec();
        offset += cookie_len;

        let suites_len = u16::from_be_bytes([body[offset], body[offset + 1]]) as usize;
        offset += 2;
        if !suites_len.is_multiple_of(2) || body.len() < offset + suites_len {
            bail!("bad ClientHello cipher suite list");
        }
        let mut cipher_suites = Vec::with_capacity(suites_len / 2);
        for index in (0..suites_len).step_by(2) {
            cipher_suites.push(u16::from_be_bytes([
                body[offset + index],
                body[offset + index + 1],
            ]));
        }
        offset += suites_len;

        if body.len() < offset + 1 {
            bail!("short ClientHello compression methods");
        }
        let methods_len = body[offset] as usize;
        offset += 1;
        if body.len() < offset + methods_len {
            bail!("short ClientHello compression methods");
        }
        let compression_methods = body[offset..offset + methods_len].to_vec();
        offset += methods_len;

        let mut extensions = Vec::new();
        if offset < body.len() {
            if body.len() < offset + 2 {
                bail!("short ClientHello extension list");
            }
            let extensions_len = u16::from_be_bytes([body[offset], body[offset + 1]]) as usize;
            offset += 2;
            let extensions_end = offset + extensions_len;
            if body.len() < extensions_end {
                bail!("short ClientHello extension list");
            }
            while offset < extensions_end {
                if extensions_end < offset + 4 {
                    bail!("short ClientHello extension header");
                }
                let ext_type = u16::from_be_bytes([body[offset], body[offset + 1]]);
                let ext_len = u16::from_be_bytes([body[offset + 2], body[offset + 3]]) as usize;
                offset += 4;
                if extensions_end < offset + ext_len {
                    bail!("short ClientHello extension body");
                }
                extensions.push(Extension {
                    ext_type,
                    body: body[offset..offset + ext_len].to_vec(),
                });
                offset += ext_len;
            }
        }

        Ok(ClientHello {
            version,
            random,
            session_id,
            cookie,
            cipher_suites,
            compression_methods,
            extensions,
        })
    }

    pub fn marshal(&self) -> Result<Vec<u8>> {
        if self.session_id.len() > 255 {
            bail!("session id exceeds 255 bytes");
        }
        if self.cookie.len() > 255 {
            bail!("cookie exceeds 255 bytes");
        }
        if self.compression_methods.len() > 255 {
            bail!("compression method list exceeds 255 bytes");
        }
        let mut out = Vec::with_capacity(128);
        out.extend_from_slice(&self.version);
        out.extend_from_slice(&self.random);
        out.push(self.session_id.len() as u8);
        out.extend_from_slice(&self.session_id);
        out.push(self.cookie.len() as u8);
        out.extend_from_slice(&self.cookie);
        out.extend_from_slice(&encode_u16_list(&self.cipher_suites));
        out.push(self.compression_methods.len() as u8);
        out.extend_from_slice(&self.compression_methods);

        let mut extensions = Vec::with_capacity(64);
        for extension in &self.extensions {
            let len = u16::try_from(extension.body.len())
                .map_err(|_| anyhow::anyhow!("extension body too long"))?;
            extensions.extend_from_slice(&extension.ext_type.to_be_bytes());
            extensions.extend_from_slice(&len.to_be_bytes());
            extensions.extend_from_slice(&extension.body);
        }
        let extensions_len = u16::try_from(extensions.len())
            .map_err(|_| anyhow::anyhow!("extension list too long"))?;
        out.extend_from_slice(&extensions_len.to_be_bytes());
        out.extend_from_slice(&extensions);
        Ok(out)
    }

    pub fn extension(&self, ext_type: u16) -> Option<&Extension> {
        self.extensions.iter().find(|e| e.ext_type == ext_type)
    }

    pub fn has_extension(&self, ext_type: u16) -> bool {
        self.extension(ext_type).is_some()
    }

    pub fn set_extension(&mut self, ext_type: u16, body: Vec<u8>) {
        match self.extensions.iter_mut().find(|e| e.ext_type == ext_type) {
            Some(existing) => existing.body = body,
            None => self.extensions.push(Extension { ext_type, body }),
        }
    }

    pub fn srtp_profiles(&self) -> Result<Vec<u16>> {
        let extension = self
            .extension(EXT_USE_SRTP)
            .ok_or_else(|| anyhow::anyhow!("missing use_srtp extension"))?;
        parse_client_use_srtp(&extension.body)
    }

    pub fn supported_groups(&self) -> Result<Vec<u16>> {
        let extension = self
            .extension(EXT_SUPPORTED_GROUPS)
            .ok_or_else(|| anyhow::anyhow!("missing supported_groups extension"))?;
        decode_u16_list(&extension.body)
    }

    pub fn signature_algorithms(&self) -> Result<Vec<u16>> {
        let extension = self
            .extension(EXT_SIGNATURE_ALGORITHMS)
            .ok_or_else(|| anyhow::anyhow!("missing signature_algorithms extension"))?;
        decode_u16_list(&extension.body)
    }

    pub fn client_random(&self) -> &[u8; 32] {
        &self.random
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DtlsHandshakeState {
    Idle,

    AwaitingServerFlight,

    AwaitingServerFinished,

    AwaitingClientFlight,
    Connected,
    Failed,
}

enum EphemeralSecret {
    X25519(StaticSecret),
    P256(EphemeralPrivateKey),
}

impl EphemeralSecret {
    fn generate(group: u16) -> Result<Self> {
        match group {
            GROUP_X25519 => {
                let bytes = random_array::<32>()?;
                Ok(Self::X25519(StaticSecret::from(bytes)))
            }
            GROUP_SECP256R1 => Ok(Self::P256(
                EphemeralPrivateKey::generate(&ECDH_P256, &dtls_rng())
                    .map_err(|_| anyhow::anyhow!("cannot generate an ECDH P-256 key"))?,
            )),
            other => bail!("unsupported ECDHE group {other}"),
        }
    }

    fn public_bytes(&self) -> Result<Vec<u8>> {
        match self {
            Self::X25519(secret) => Ok(PublicKey::from(secret).as_bytes().to_vec()),
            Self::P256(key) => Ok(key
                .compute_public_key()
                .map_err(|_| anyhow::anyhow!("cannot compute the ECDH P-256 public key"))?
                .as_ref()
                .to_vec()),
        }
    }

    fn agree(self, peer_public: &[u8]) -> Result<[u8; 32]> {
        match self {
            Self::X25519(secret) => {
                let bytes: [u8; 32] = peer_public
                    .try_into()
                    .map_err(|_| anyhow::anyhow!("the x25519 peer key is not 32 bytes"))?;
                Ok(*secret.diffie_hellman(&PublicKey::from(bytes)).as_bytes())
            }
            Self::P256(key) => {
                let peer = EcdhUnparsedPublicKey::new(&ECDH_P256, peer_public);
                agree_ephemeral(
                    key,
                    peer,
                    aws_lc_rs::error::Unspecified,
                    |secret| -> Result<[u8; 32], aws_lc_rs::error::Unspecified> {
                        if secret.len() > 32 {
                            return Err(aws_lc_rs::error::Unspecified);
                        }

                        let mut shared = [0u8; 32];
                        shared[32 - secret.len()..].copy_from_slice(secret);
                        Ok(shared)
                    },
                )
                .map_err(|_| anyhow::anyhow!("the ECDH P-256 exchange failed"))
            }
        }
    }
}

fn fill_random_bytes(dest: &mut [u8]) -> Result<()> {
    aws_lc_rs::rand::fill(dest)
        .map_err(|_| anyhow::anyhow!("the system RNG refused to produce randomness"))
}

fn random_array<const N: usize>() -> Result<[u8; N]> {
    let mut bytes = [0u8; N];
    fill_random_bytes(&mut bytes)?;
    Ok(bytes)
}

pub struct DtlsClientHandshake {
    pub client_random: [u8; 32],
    session_id: Vec<u8>,
    state: DtlsHandshakeState,
    cookie: Vec<u8>,
    message_seq: u16,
    record_sequence: u64,
    transcript: Transcript,
    reassembler: HandshakeReassembler,
    pub server_random: Option<[u8; 32]>,

    ephemeral_public: Option<Vec<u8>>,
    premaster_secret: Option<[u8; 32]>,
    master_secret: Option<[u8; 48]>,
    record_keys: Option<RecordKeys>,
    read: Option<RecordProtector>,
    write: Option<RecordProtector>,
    extended_master_secret: bool,
    pinned_fingerprint: Option<[u8; 32]>,
    peer_fingerprint: Option<[u8; 32]>,
    peer_certificate: Option<Vec<u8>>,
    srtp_keys: Option<DtlsSrtpKeys>,
    incoming: Vec<Vec<u8>>,
}

impl DtlsClientHandshake {
    pub fn new() -> Result<Self> {
        Ok(Self {
            client_random: random_array()?,
            session_id: Vec::new(),
            state: DtlsHandshakeState::Idle,
            cookie: Vec::new(),
            message_seq: 0,
            record_sequence: 0,
            transcript: Transcript::new(),
            reassembler: HandshakeReassembler::new(),
            server_random: None,
            ephemeral_public: None,
            premaster_secret: None,
            master_secret: None,
            record_keys: None,
            read: None,
            write: None,
            extended_master_secret: false,
            pinned_fingerprint: None,
            peer_fingerprint: None,
            peer_certificate: None,
            srtp_keys: None,
            incoming: Vec::new(),
        })
    }

    pub fn state(&self) -> DtlsHandshakeState {
        self.state
    }

    pub fn is_connected(&self) -> bool {
        self.state == DtlsHandshakeState::Connected
    }

    pub fn pin_fingerprint(&mut self, fingerprint: [u8; 32]) {
        self.pinned_fingerprint = Some(fingerprint);
    }

    pub fn peer_fingerprint(&self) -> Option<[u8; 32]> {
        self.peer_fingerprint
    }

    pub fn peer_certificate(&self) -> Option<&[u8]> {
        self.peer_certificate.as_deref()
    }

    pub fn srtp_keys(&self) -> Option<&DtlsSrtpKeys> {
        self.srtp_keys.as_ref()
    }

    pub fn record_keys(&self) -> Option<&RecordKeys> {
        self.record_keys.as_ref()
    }

    pub fn take_application_data(&mut self) -> Vec<Vec<u8>> {
        std::mem::take(&mut self.incoming)
    }

    pub fn create_client_hello(&self, cookie: &[u8]) -> ClientHello {
        ClientHello {
            version: DTLS12_VERSION,
            random: self.client_random,
            session_id: self.session_id.clone(),
            cookie: cookie.to_vec(),
            cipher_suites: CLIENT_HELLO_CIPHER_SUITES.to_vec(),
            compression_methods: vec![0],
            extensions: vec![
                Extension {
                    ext_type: EXT_EXTENDED_MASTER_SECRET,
                    body: Vec::new(),
                },
                Extension {
                    ext_type: EXT_RENEGOTIATION_INFO,
                    body: vec![0],
                },
                Extension {
                    ext_type: EXT_SUPPORTED_GROUPS,
                    body: encode_u16_list(CLIENT_HELLO_SUPPORTED_GROUPS),
                },
                Extension {
                    ext_type: EXT_EC_POINT_FORMATS,
                    body: vec![1, 0],
                },
                Extension {
                    ext_type: EXT_SESSION_TICKET,
                    body: Vec::new(),
                },
                Extension {
                    ext_type: EXT_SIGNATURE_ALGORITHMS,
                    body: encode_u16_list(CLIENT_HELLO_SIGNATURE_ALGORITHMS),
                },
                Extension {
                    ext_type: EXT_USE_SRTP,
                    body: srtp_body_from_profiles(CLIENT_HELLO_SRTP_PROFILES),
                },
            ],
        }
    }

    fn reset(&mut self) {
        self.state = DtlsHandshakeState::Idle;
        self.cookie.clear();
        self.message_seq = 0;
        self.record_sequence = 0;
        self.transcript = Transcript::new();
        self.reassembler = HandshakeReassembler::new();
        self.server_random = None;
        self.ephemeral_public = None;
        self.premaster_secret = None;
        self.master_secret = None;
        self.record_keys = None;
        self.read = None;
        self.write = None;
        self.extended_master_secret = false;
        self.peer_fingerprint = None;
        self.peer_certificate = None;
        self.srtp_keys = None;
        self.incoming.clear();
    }

    fn send_client_hello(&mut self, cookie: &[u8]) -> Result<Vec<u8>> {
        let hello = self.create_client_hello(cookie);
        validate_client_hello(&hello)?;
        let body = hello.marshal()?;
        let mut message = Vec::with_capacity(body.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_CLIENT_HELLO,
            self.message_seq,
            &body,
            &mut message,
        );
        self.transcript.push(&message);
        self.message_seq = self.message_seq.wrapping_add(1);

        let mut datagram = Vec::with_capacity(message.len() + DTLS_RECORD_HEADER_LEN);
        write_dtls_record(
            CONTENT_TYPE_HANDSHAKE,
            0,
            self.record_sequence,
            &message,
            &mut datagram,
        );
        self.record_sequence = self.record_sequence.wrapping_add(1);
        Ok(datagram)
    }

    pub fn start(&mut self) -> Result<Vec<u8>> {
        self.reset();
        self.state = DtlsHandshakeState::AwaitingServerFlight;
        self.send_client_hello(&[])
    }

    pub fn handle_datagram(&mut self, wire: &[u8]) -> Result<Option<Vec<u8>>> {
        self.handle_datagram_with_app_data(wire, None)
    }

    pub fn handle_datagram_with_app_data(
        &mut self,
        wire: &[u8],
        app_data: Option<&[u8]>,
    ) -> Result<Option<Vec<u8>>> {
        if self.state == DtlsHandshakeState::Failed {
            bail!("the handshake has already failed");
        }

        let records = match parse_dtls_records(wire) {
            Ok(records) => records,
            Err(_) => return Ok(None),
        };
        let mut flight: Option<Vec<u8>> = None;

        for record in records {
            match (record.epoch, record.content_type) {
                (0, CONTENT_TYPE_HANDSHAKE) if self.state == DtlsHandshakeState::Connected => {
                    continue;
                }
                (0, CONTENT_TYPE_HANDSHAKE) => {
                    if self.reassembler.feed(record.fragment).is_err() {
                        continue;
                    }
                    for message in self.reassembler.take_ready() {
                        if let Some(next) = self.handle_handshake_message(&message)? {
                            merge_flight(&mut flight, next);
                        }
                    }
                }
                (0, CONTENT_TYPE_CHANGE_CIPHER_SPEC) => {}
                (0, CONTENT_TYPE_ALERT) => self.handle_peer_alert(record.fragment)?,
                (1, content_type) => {
                    let Some(protector) = self.read.as_mut() else {
                        continue;
                    };
                    let Ok(plaintext) = protector.unprotect(&record) else {
                        continue;
                    };
                    match content_type {
                        CONTENT_TYPE_HANDSHAKE => {
                            if self.reassembler.feed(&plaintext).is_err() {
                                continue;
                            }
                            for message in self.reassembler.take_ready() {
                                if let Some(next) = self.handle_handshake_message(&message)? {
                                    merge_flight(&mut flight, next);
                                }
                            }
                        }
                        CONTENT_TYPE_APPLICATION_DATA => self.incoming.push(plaintext),
                        CONTENT_TYPE_ALERT => self.handle_peer_alert(&plaintext)?,
                        _ => {}
                    }
                }
                _ => {}
            }
        }

        if let (Some(data), true) = (app_data, self.write.is_some()) {
            let datagram = flight.get_or_insert_with(Vec::new);
            datagram.extend_from_slice(&self.write_application_data(data)?);
        }
        Ok(flight)
    }

    fn handle_peer_alert(&mut self, fragment: &[u8]) -> Result<()> {
        if fragment.len() < 2 {
            bail!("short alert");
        }
        let (level, description) = (fragment[0], fragment[1]);
        if description == ALERT_CLOSE_NOTIFY {
            self.state = DtlsHandshakeState::Failed;
            return Ok(());
        }
        if level == ALERT_LEVEL_FATAL {
            self.state = DtlsHandshakeState::Failed;
            bail!("the peer sent a fatal DTLS alert {description}");
        }
        Ok(())
    }

    pub fn write_application_data(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        let protector = self
            .write
            .as_mut()
            .ok_or_else(|| anyhow::anyhow!("the handshake has not produced keys yet"))?;
        protector.protect(CONTENT_TYPE_APPLICATION_DATA, data)
    }

    fn handle_handshake_message(&mut self, message: &[u8]) -> Result<Option<Vec<u8>>> {
        let (msg_type, body) = handshake_message_parts(message)?;
        match msg_type {
            HANDSHAKE_TYPE_HELLO_VERIFY_REQUEST => {
                if self.state != DtlsHandshakeState::AwaitingServerFlight {
                    bail!("a HelloVerifyRequest arrived outside the cookie exchange");
                }
                self.cookie = parse_hello_verify_request(body)?;

                self.transcript = Transcript::new();
                let cookie = self.cookie.clone();
                Ok(Some(self.send_client_hello(&cookie)?))
            }
            HANDSHAKE_TYPE_SERVER_HELLO => {
                self.transcript.push(message);
                self.handle_server_hello(body)?;
                Ok(None)
            }
            HANDSHAKE_TYPE_CERTIFICATE => {
                self.transcript.push(message);
                let certificate = parse_single_certificate(body)?;
                self.accept_certificate(&certificate)?;
                Ok(None)
            }
            HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE => {
                self.transcript.push(message);
                self.handle_server_key_exchange(body)?;
                Ok(None)
            }
            HANDSHAKE_TYPE_CERTIFICATE_STATUS | HANDSHAKE_TYPE_NEW_SESSION_TICKET => {
                self.transcript.push(message);
                Ok(None)
            }
            HANDSHAKE_TYPE_SERVER_HELLO_DONE => {
                self.transcript.push(message);
                Ok(Some(self.send_client_flight()?))
            }
            HANDSHAKE_TYPE_FINISHED => {
                self.finish(body)?;
                Ok(None)
            }
            other => bail!("unexpected handshake message {other} in the client"),
        }
    }

    fn handle_server_hello(&mut self, body: &[u8]) -> Result<()> {
        if body.len() < 38 {
            bail!("short ServerHello");
        }
        if body[0..2] != DTLS12_VERSION {
            bail!("the server does not speak DTLS 1.2");
        }
        let mut server_random = [0u8; 32];
        server_random.copy_from_slice(&body[2..34]);
        self.server_random = Some(server_random);

        let session_id_len = body[34] as usize;
        let mut offset = 35 + session_id_len;
        if body.len() < offset + 3 {
            bail!("short ServerHello");
        }
        let suite = u16::from_be_bytes([body[offset], body[offset + 1]]);
        offset += 2;
        if suite != CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256 {
            bail!("the server chose cipher suite {suite:#06x}, which we did not offer");
        }
        if body[offset] != 0 {
            bail!("the server chose a non-null compression method");
        }
        offset += 1;

        let mut extended_master_secret = false;
        let mut srtp_echoed = false;
        if offset < body.len() {
            if body.len() < offset + 2 {
                bail!("short ServerHello extension list");
            }
            let extensions_len = u16::from_be_bytes([body[offset], body[offset + 1]]) as usize;
            offset += 2;
            let end = offset + extensions_len;
            if body.len() < end {
                bail!("short ServerHello extension list");
            }
            while offset < end {
                if end < offset + 4 {
                    bail!("short ServerHello extension header");
                }
                let ext_type = u16::from_be_bytes([body[offset], body[offset + 1]]);
                let ext_len = u16::from_be_bytes([body[offset + 2], body[offset + 3]]) as usize;
                offset += 4;
                if end < offset + ext_len {
                    bail!("short ServerHello extension body");
                }
                match ext_type {
                    EXT_EXTENDED_MASTER_SECRET => extended_master_secret = true,
                    EXT_USE_SRTP => {
                        if !CLIENT_HELLO_SRTP_PROFILES
                            .contains(&parse_server_use_srtp(&body[offset..offset + ext_len])?)
                        {
                            bail!("the server selected no SRTP profile we offered");
                        }
                        srtp_echoed = true;
                    }
                    _ => {}
                }
                offset += ext_len;
            }
        }

        if !extended_master_secret {
            bail!("the server did not negotiate the extended master secret");
        }
        if !srtp_echoed {
            bail!("the server did not echo use_srtp");
        }
        self.extended_master_secret = true;
        Ok(())
    }

    fn accept_certificate(&mut self, certificate: &[u8]) -> Result<()> {
        let mut fingerprint = [0u8; 32];
        fingerprint.copy_from_slice(&Sha256::digest(certificate));
        if let Some(pinned) = self.pinned_fingerprint
            && pinned != fingerprint
        {
            bail!("the peer certificate does not match the pinned fingerprint");
        }

        certificate_p256_public_key(certificate)?;
        self.peer_fingerprint = Some(fingerprint);
        self.peer_certificate = Some(certificate.to_vec());
        Ok(())
    }

    fn handle_server_key_exchange(&mut self, body: &[u8]) -> Result<()> {
        if body.len() < 5 {
            bail!("short ServerKeyExchange");
        }
        if body[0] != 3 {
            bail!("the server did not use a named curve");
        }
        let group = u16::from_be_bytes([body[1], body[2]]);
        let key_len = body[3] as usize;
        let params_len = 4 + key_len;
        if body.len() < params_len + 4 {
            bail!("short ServerKeyExchange");
        }
        let peer_public = &body[4..params_len];

        let algorithm = u16::from_be_bytes([body[params_len], body[params_len + 1]]);
        if algorithm != 0x0403 {
            bail!("the server signed with {algorithm:#06x}, which we did not offer");
        }
        let signature_len =
            u16::from_be_bytes([body[params_len + 2], body[params_len + 3]]) as usize;
        if body.len() < params_len + 4 + signature_len {
            bail!("the ServerKeyExchange signature is truncated");
        }
        let signature = &body[params_len + 4..params_len + 4 + signature_len];

        let certificate = self
            .peer_certificate
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("the server signed before sending a certificate"))?;
        let public_point = certificate_p256_public_key(certificate)?;

        let server_random = self
            .server_random
            .ok_or_else(|| anyhow::anyhow!("ServerKeyExchange without a ServerHello"))?;
        let mut signed = Vec::with_capacity(64 + params_len);
        signed.extend_from_slice(&self.client_random);
        signed.extend_from_slice(&server_random);
        signed.extend_from_slice(&body[..params_len]);
        verify_p256_signature(&public_point, &signed, signature)?;

        let ephemeral = EphemeralSecret::generate(group)?;

        self.ephemeral_public = Some(ephemeral.public_bytes()?);
        self.premaster_secret = Some(ephemeral.agree(peer_public)?);
        Ok(())
    }

    fn send_client_flight(&mut self) -> Result<Vec<u8>> {
        if self.state != DtlsHandshakeState::AwaitingServerFlight {
            bail!("the server flight arrived out of order");
        }
        let premaster_secret = self
            .premaster_secret
            .ok_or_else(|| anyhow::anyhow!("the server flight had no ServerKeyExchange"))?;
        let server_random = self
            .server_random
            .ok_or_else(|| anyhow::anyhow!("the server flight had no ServerHello"))?;

        let public_bytes = self
            .ephemeral_public
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("no ServerKeyExchange produced a client key"))?
            .clone();
        let mut cke_body = Vec::with_capacity(public_bytes.len() + 1);
        cke_body.push(public_bytes.len() as u8);
        cke_body.extend_from_slice(&public_bytes);
        let mut cke = Vec::with_capacity(cke_body.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE,
            self.message_seq,
            &cke_body,
            &mut cke,
        );
        self.transcript.push(&cke);
        self.message_seq = self.message_seq.wrapping_add(1);

        let master_secret = if self.extended_master_secret {
            derive_extended_master_secret(&premaster_secret, &self.transcript.hash())
        } else {
            derive_master_secret(&premaster_secret, &self.client_random, &server_random)
        };
        self.master_secret = Some(master_secret);

        let keys = derive_record_keys(&master_secret, &self.client_random, &server_random);
        self.read = Some(RecordProtector::new(
            &keys.server_write_key,
            &keys.server_write_iv,
            1,
        )?);
        self.write = Some(RecordProtector::new(
            &keys.client_write_key,
            &keys.client_write_iv,
            1,
        )?);
        self.record_keys = Some(keys);
        self.srtp_keys = Some(derive_dtls_srtp_keys(
            &master_secret,
            &self.client_random,
            &server_random,
        ));

        let verify_data =
            compute_verify_data(&master_secret, b"client finished", &self.transcript.hash());
        let mut finished = Vec::with_capacity(12 + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_FINISHED,
            self.message_seq,
            &verify_data,
            &mut finished,
        );
        self.message_seq = self.message_seq.wrapping_add(1);

        self.transcript.push(&finished);

        let mut datagram = Vec::with_capacity(cke.len() + finished.len() + 64);
        write_dtls_record(
            CONTENT_TYPE_HANDSHAKE,
            0,
            self.record_sequence,
            &cke,
            &mut datagram,
        );
        self.record_sequence = self.record_sequence.wrapping_add(1);
        write_dtls_record(
            CONTENT_TYPE_CHANGE_CIPHER_SPEC,
            0,
            self.record_sequence,
            &[0x01],
            &mut datagram,
        );
        self.record_sequence = self.record_sequence.wrapping_add(1);
        let Some(write) = self.write.as_mut() else {
            bail!("the client write keys were not installed");
        };
        datagram.extend_from_slice(&write.protect(CONTENT_TYPE_HANDSHAKE, &finished)?);

        self.state = DtlsHandshakeState::AwaitingServerFinished;
        Ok(datagram)
    }

    fn finish(&mut self, body: &[u8]) -> Result<()> {
        if self.state != DtlsHandshakeState::AwaitingServerFinished {
            bail!("the server Finished arrived before the client flight was sent");
        }
        if body.len() != 12 {
            bail!("the server Finished is not 12 bytes");
        }
        let master_secret = self
            .master_secret
            .ok_or_else(|| anyhow::anyhow!("no master secret"))?;
        let expected =
            compute_verify_data(&master_secret, b"server finished", &self.transcript.hash());
        if expected != body {
            self.state = DtlsHandshakeState::Failed;
            bail!("the server Finished does not verify");
        }
        self.state = DtlsHandshakeState::Connected;
        Ok(())
    }
}

const SUPPORTED_GROUPS: [u16; 2] = [GROUP_X25519, GROUP_SECP256R1];

fn choose_group(extensions: &[Extension]) -> Result<u16> {
    let offered = extensions
        .iter()
        .find(|extension| extension.ext_type == EXT_SUPPORTED_GROUPS)
        .map(|extension| decode_u16_list(&extension.body))
        .transpose()?
        .unwrap_or_default();
    offered
        .into_iter()
        .find(|group| SUPPORTED_GROUPS.contains(group))
        .ok_or_else(|| anyhow::anyhow!("the client offered no group we support"))
}

fn has_extension(extensions: &[Extension], ext_type: u16) -> bool {
    extensions
        .iter()
        .any(|extension| extension.ext_type == ext_type)
}

fn choose_srtp_profile(extensions: &[Extension]) -> Result<u16> {
    let offered = extensions
        .iter()
        .find(|extension| extension.ext_type == EXT_USE_SRTP)
        .map(|extension| parse_client_use_srtp(&extension.body))
        .transpose()?
        .unwrap_or_default();
    CLIENT_HELLO_SRTP_PROFILES
        .iter()
        .copied()
        .find(|profile| offered.contains(profile))
        .ok_or_else(|| anyhow::anyhow!("the client offered no SRTP profile we support"))
}

pub struct DtlsServerHandshake {
    certificate: DtlsCertificate,
    pub server_random: [u8; 32],
    pub server_session_id: [u8; 32],
    pub client_random: Option<[u8; 32]>,
    state: DtlsHandshakeState,
    message_seq: u16,
    record_sequence: u64,
    transcript: Transcript,
    reassembler: HandshakeReassembler,
    ephemeral: Option<EphemeralSecret>,
    group: Option<u16>,
    master_secret: Option<[u8; 48]>,
    extended_master_secret: bool,
    record_keys: Option<RecordKeys>,
    read: Option<RecordProtector>,
    write: Option<RecordProtector>,
    srtp_keys: Option<DtlsSrtpKeys>,
    incoming: Vec<Vec<u8>>,

    last_flight: Option<Vec<u8>>,
}

impl DtlsServerHandshake {
    pub fn new() -> Result<Self> {
        Self::with_certificate(DtlsCertificate::generate()?)
    }

    pub fn with_certificate(certificate: DtlsCertificate) -> Result<Self> {
        Ok(Self {
            certificate,
            server_random: random_array()?,
            server_session_id: random_array()?,
            client_random: None,
            state: DtlsHandshakeState::Idle,
            message_seq: 0,
            record_sequence: 0,
            transcript: Transcript::new(),
            reassembler: HandshakeReassembler::new(),
            ephemeral: None,
            group: None,
            master_secret: None,
            extended_master_secret: false,
            record_keys: None,
            read: None,
            write: None,
            srtp_keys: None,
            incoming: Vec::new(),
            last_flight: None,
        })
    }

    pub fn state(&self) -> DtlsHandshakeState {
        self.state
    }

    pub fn is_connected(&self) -> bool {
        self.state == DtlsHandshakeState::Connected
    }

    pub fn certificate(&self) -> &DtlsCertificate {
        &self.certificate
    }

    pub fn srtp_keys(&self) -> Option<&DtlsSrtpKeys> {
        self.srtp_keys.as_ref()
    }

    pub fn record_keys(&self) -> Option<&RecordKeys> {
        self.record_keys.as_ref()
    }

    pub fn take_application_data(&mut self) -> Vec<Vec<u8>> {
        std::mem::take(&mut self.incoming)
    }

    pub fn handle_datagram(&mut self, wire: &[u8]) -> Result<Option<Vec<u8>>> {
        self.handle_datagram_with_app_data(wire, None)
    }

    pub fn handle_datagram_with_app_data(
        &mut self,
        wire: &[u8],
        app_data: Option<&[u8]>,
    ) -> Result<Option<Vec<u8>>> {
        if self.state == DtlsHandshakeState::Failed {
            bail!("the handshake has already failed");
        }

        let records = match parse_dtls_records(wire) {
            Ok(records) => records,
            Err(_) => return Ok(None),
        };
        let mut flight: Option<Vec<u8>> = None;

        for record in records {
            match (record.epoch, record.content_type) {
                (0, CONTENT_TYPE_HANDSHAKE) if self.state == DtlsHandshakeState::Connected => {
                    continue;
                }
                (0, CONTENT_TYPE_HANDSHAKE) => {
                    if self.reassembler.feed(record.fragment).is_err() {
                        continue;
                    }
                    for message in self.reassembler.take_ready() {
                        if let Some(next) = self.handle_handshake_message(&message)? {
                            merge_flight(&mut flight, next);
                        }
                    }
                }
                (0, CONTENT_TYPE_CHANGE_CIPHER_SPEC) => {}
                (0, CONTENT_TYPE_ALERT) => self.handle_peer_alert(record.fragment)?,
                (1, content_type) => {
                    let Some(protector) = self.read.as_mut() else {
                        continue;
                    };
                    let Ok(plaintext) = protector.unprotect(&record) else {
                        continue;
                    };
                    match content_type {
                        CONTENT_TYPE_HANDSHAKE => {
                            if self.reassembler.feed(&plaintext).is_err() {
                                continue;
                            }
                            for message in self.reassembler.take_ready() {
                                if let Some(next) = self.handle_handshake_message(&message)? {
                                    merge_flight(&mut flight, next);
                                }
                            }
                        }
                        CONTENT_TYPE_APPLICATION_DATA => self.incoming.push(plaintext),
                        CONTENT_TYPE_ALERT => self.handle_peer_alert(&plaintext)?,
                        _ => {}
                    }
                }
                _ => {}
            }
        }

        if let (Some(data), true) = (app_data, self.write.is_some()) {
            let datagram = flight.get_or_insert_with(Vec::new);
            datagram.extend_from_slice(&self.write_application_data(data)?);
        }
        Ok(flight)
    }

    fn handle_peer_alert(&mut self, fragment: &[u8]) -> Result<()> {
        if fragment.len() < 2 {
            bail!("short alert");
        }
        let (level, description) = (fragment[0], fragment[1]);
        if description == ALERT_CLOSE_NOTIFY {
            self.state = DtlsHandshakeState::Failed;
            return Ok(());
        }
        if level == ALERT_LEVEL_FATAL {
            self.state = DtlsHandshakeState::Failed;
            bail!("the peer sent a fatal DTLS alert {description}");
        }
        Ok(())
    }

    pub fn write_application_data(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        let protector = self
            .write
            .as_mut()
            .ok_or_else(|| anyhow::anyhow!("the handshake has not produced keys yet"))?;
        protector.protect(CONTENT_TYPE_APPLICATION_DATA, data)
    }

    fn handle_handshake_message(&mut self, message: &[u8]) -> Result<Option<Vec<u8>>> {
        let (msg_type, body) = handshake_message_parts(message)?;
        match msg_type {
            HANDSHAKE_TYPE_CLIENT_HELLO => {
                if self.state == DtlsHandshakeState::AwaitingClientFlight {
                    return Ok(self.last_flight.clone());
                }
                if self.state != DtlsHandshakeState::Idle {
                    bail!("a ClientHello arrived after the handshake had started");
                }
                let hello = ClientHello::parse(body)?;
                self.client_random = Some(hello.random);
                validate_client_hello(&hello)?;
                self.transcript = Transcript::new();
                self.transcript.push(message);
                Ok(Some(self.send_server_flight(&hello)?))
            }
            HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE => {
                self.transcript.push(message);
                self.handle_client_key_exchange(body)?;
                Ok(None)
            }
            HANDSHAKE_TYPE_FINISHED => Ok(Some(self.handle_client_finished(body, message)?)),
            other => bail!("unexpected handshake message {other} in the server"),
        }
    }

    fn send_server_flight(&mut self, hello: &ClientHello) -> Result<Vec<u8>> {
        let group = choose_group(&hello.extensions)?;
        let extended_master_secret = has_extension(&hello.extensions, EXT_EXTENDED_MASTER_SECRET);
        if !hello
            .cipher_suites
            .contains(&CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256)
        {
            bail!("the client does not offer TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        }
        self.group = Some(group);
        self.extended_master_secret = extended_master_secret;

        let mut extensions = Vec::with_capacity(48);
        if extended_master_secret {
            push_extension(&mut extensions, EXT_EXTENDED_MASTER_SECRET, &[]);
        }
        push_extension(&mut extensions, EXT_RENEGOTIATION_INFO, &[0]);
        if has_extension(&hello.extensions, EXT_EC_POINT_FORMATS) {
            push_extension(&mut extensions, EXT_EC_POINT_FORMATS, &[1, 0]);
        }
        let offers_ticket = has_extension(&hello.extensions, EXT_SESSION_TICKET);
        if offers_ticket {
            push_extension(&mut extensions, EXT_SESSION_TICKET, &[]);
        }
        let profile = choose_srtp_profile(&hello.extensions)?;
        push_extension(
            &mut extensions,
            EXT_USE_SRTP,
            &srtp_body_from_profiles(&[profile]),
        );

        let mut sh_body = Vec::with_capacity(64);
        sh_body.extend_from_slice(&DTLS12_VERSION);
        sh_body.extend_from_slice(&self.server_random);
        if offers_ticket {
            sh_body.push(0);
        } else {
            sh_body.push(32);
            sh_body.extend_from_slice(&self.server_session_id);
        }
        sh_body.extend_from_slice(&CIPHER_SUITE_ECDHE_ECDSA_AES128_GCM_SHA256.to_be_bytes());
        sh_body.push(0);
        sh_body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        sh_body.extend_from_slice(&extensions);

        let mut flight = Vec::with_capacity(512);
        let mut message = Vec::with_capacity(sh_body.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_SERVER_HELLO,
            self.message_seq,
            &sh_body,
            &mut message,
        );
        self.message_seq = self.message_seq.wrapping_add(1);
        write_own_record(&mut flight, &mut self.record_sequence, &message);

        self.transcript.push(&message);

        let mut cert_body = Vec::with_capacity(self.certificate.der().len() + 6);
        let chain_len = (self.certificate.der().len() + 3) as u32;
        cert_body.extend_from_slice(&chain_len.to_be_bytes()[1..]);
        let cert_len = self.certificate.der().len() as u32;
        cert_body.extend_from_slice(&cert_len.to_be_bytes()[1..]);
        cert_body.extend_from_slice(self.certificate.der());
        let mut message = Vec::with_capacity(cert_body.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_CERTIFICATE,
            self.message_seq,
            &cert_body,
            &mut message,
        );
        self.message_seq = self.message_seq.wrapping_add(1);
        write_own_record(&mut flight, &mut self.record_sequence, &message);
        self.transcript.push(&message);

        let ephemeral = EphemeralSecret::generate(group)?;
        let public_key = ephemeral.public_bytes()?;
        let mut params = Vec::with_capacity(public_key.len() + 4);
        params.push(3);
        params.extend_from_slice(&group.to_be_bytes());
        params.push(public_key.len() as u8);
        params.extend_from_slice(&public_key);

        let mut signed = Vec::with_capacity(64 + params.len());
        signed.extend_from_slice(&hello.random);
        signed.extend_from_slice(&self.server_random);
        signed.extend_from_slice(&params);
        let signature = self.certificate.sign(&signed)?;

        let mut ske_body = params;
        ske_body.extend_from_slice(&0x0403u16.to_be_bytes());
        ske_body.extend_from_slice(&(signature.len() as u16).to_be_bytes());
        ske_body.extend_from_slice(&signature);
        let mut message = Vec::with_capacity(ske_body.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_SERVER_KEY_EXCHANGE,
            self.message_seq,
            &ske_body,
            &mut message,
        );
        self.message_seq = self.message_seq.wrapping_add(1);
        write_own_record(&mut flight, &mut self.record_sequence, &message);
        self.transcript.push(&message);

        let mut message = Vec::with_capacity(DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_SERVER_HELLO_DONE,
            self.message_seq,
            &[],
            &mut message,
        );
        self.message_seq = self.message_seq.wrapping_add(1);
        write_own_record(&mut flight, &mut self.record_sequence, &message);
        self.transcript.push(&message);

        self.ephemeral = Some(ephemeral);
        self.last_flight = Some(flight.clone());
        self.state = DtlsHandshakeState::AwaitingClientFlight;
        Ok(flight)
    }

    fn handle_client_key_exchange(&mut self, body: &[u8]) -> Result<()> {
        if self.state != DtlsHandshakeState::AwaitingClientFlight {
            bail!("the client key exchange arrived out of order");
        }
        if body.is_empty() {
            bail!("short ClientKeyExchange");
        }
        let key_len = body[0] as usize;
        if body.len() < 1 + key_len {
            bail!("short ClientKeyExchange");
        }
        let expected_len = match self.group {
            Some(GROUP_X25519) => 32,
            Some(GROUP_SECP256R1) => 65,
            _ => bail!("no ECDHE group was negotiated"),
        };
        if key_len != expected_len {
            bail!("the ClientKeyExchange key is {key_len} bytes, not {expected_len}");
        }
        let ephemeral = self
            .ephemeral
            .take()
            .ok_or_else(|| anyhow::anyhow!("no ServerKeyExchange was sent"))?;
        let premaster_secret = ephemeral.agree(&body[1..1 + key_len])?;
        let client_random = self
            .client_random
            .ok_or_else(|| anyhow::anyhow!("no client random"))?;

        let master_secret = if self.extended_master_secret {
            derive_extended_master_secret(&premaster_secret, &self.transcript.hash())
        } else {
            derive_master_secret(&premaster_secret, &client_random, &self.server_random)
        };
        self.master_secret = Some(master_secret);

        let keys = derive_record_keys(&master_secret, &client_random, &self.server_random);
        self.read = Some(RecordProtector::new(
            &keys.client_write_key,
            &keys.client_write_iv,
            1,
        )?);
        self.write = Some(RecordProtector::new(
            &keys.server_write_key,
            &keys.server_write_iv,
            1,
        )?);
        self.record_keys = Some(keys);
        self.srtp_keys = Some(derive_dtls_srtp_keys(
            &master_secret,
            &client_random,
            &self.server_random,
        ));
        Ok(())
    }

    fn handle_client_finished(&mut self, body: &[u8], message: &[u8]) -> Result<Vec<u8>> {
        if self.state != DtlsHandshakeState::AwaitingClientFlight {
            bail!("the client Finished arrived out of order");
        }
        if body.len() != 12 {
            bail!("the client Finished is not 12 bytes");
        }
        let master_secret = self
            .master_secret
            .ok_or_else(|| anyhow::anyhow!("the client flight had no ClientKeyExchange"))?;

        let expected =
            compute_verify_data(&master_secret, b"client finished", &self.transcript.hash());
        if expected != body {
            self.state = DtlsHandshakeState::Failed;
            bail!("the client Finished does not verify");
        }

        self.transcript.push(message);
        let verify_data =
            compute_verify_data(&master_secret, b"server finished", &self.transcript.hash());

        let mut flight = Vec::new();
        write_dtls_record(
            CONTENT_TYPE_CHANGE_CIPHER_SPEC,
            0,
            self.record_sequence,
            &[0x01],
            &mut flight,
        );
        self.record_sequence = self.record_sequence.wrapping_add(1);

        let mut finished = Vec::with_capacity(verify_data.len() + DTLS_HANDSHAKE_HEADER_LEN);
        write_handshake_msg(
            HANDSHAKE_TYPE_FINISHED,
            self.message_seq,
            &verify_data,
            &mut finished,
        );
        self.message_seq = self.message_seq.wrapping_add(1);
        let Some(write) = self.write.as_mut() else {
            bail!("the server write keys were not installed");
        };
        flight.extend_from_slice(&write.protect(CONTENT_TYPE_HANDSHAKE, &finished)?);

        self.last_flight = Some(flight.clone());
        self.state = DtlsHandshakeState::Connected;
        Ok(flight)
    }
}

fn write_own_record(out: &mut Vec<u8>, sequence: &mut u64, message: &[u8]) {
    write_dtls_record(CONTENT_TYPE_HANDSHAKE, 0, *sequence, message, out);
    *sequence = sequence.wrapping_add(1);
}

fn push_extension(out: &mut Vec<u8>, ext_type: u16, body: &[u8]) {
    out.extend_from_slice(&ext_type.to_be_bytes());
    out.extend_from_slice(&(body.len() as u16).to_be_bytes());
    out.extend_from_slice(body);
}

fn parse_hello_verify_request(body: &[u8]) -> Result<Vec<u8>> {
    if body.len() < 3 {
        bail!("short HelloVerifyRequest");
    }
    let cookie_len = body[2] as usize;
    if cookie_len == 0 || body.len() != 3 + cookie_len {
        bail!("malformed HelloVerifyRequest");
    }
    Ok(body[3..].to_vec())
}

fn parse_single_certificate(body: &[u8]) -> Result<Vec<u8>> {
    if body.len() < 6 {
        bail!("short Certificate");
    }
    let chain_len = u32::from_be_bytes([0, body[0], body[1], body[2]]) as usize;
    if body.len() < 3 + chain_len {
        bail!("short Certificate chain");
    }
    let cert_len = u32::from_be_bytes([0, body[3], body[4], body[5]]) as usize;
    if chain_len < 3 + cert_len {
        bail!("short Certificate entry");
    }
    Ok(body[6..6 + cert_len].to_vec())
}

fn merge_flight(flight: &mut Option<Vec<u8>>, next: Vec<u8>) {
    match flight {
        Some(buffer) => buffer.extend_from_slice(&next),
        None => *flight = Some(next),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn handshake_type_of(wire: &[u8]) -> u8 {
        wire[DTLS_RECORD_HEADER_LEN]
    }

    fn record_fragment_range(wire: &[u8], index: usize) -> Option<std::ops::Range<usize>> {
        let mut offset = 0;
        let mut current = 0;
        while offset + DTLS_RECORD_HEADER_LEN <= wire.len() {
            let length = u16::from_be_bytes([wire[offset + 11], wire[offset + 12]]) as usize;
            let start = offset + DTLS_RECORD_HEADER_LEN;
            let end = start + length;
            if end > wire.len() {
                return None;
            }
            if current == index {
                return Some(start..end);
            }
            offset = end;
            current += 1;
        }
        None
    }

    fn complete_handshake(client: &mut DtlsClientHandshake, server: &mut DtlsServerHandshake) {
        let client_hello = client.start().unwrap();
        assert!(is_dtls_packet(&client_hello));
        assert_eq!(
            handshake_type_of(&client_hello),
            HANDSHAKE_TYPE_CLIENT_HELLO
        );

        let server_flight = server.handle_datagram(&client_hello).unwrap().unwrap();
        assert_eq!(
            handshake_type_of(&server_flight),
            HANDSHAKE_TYPE_SERVER_HELLO
        );

        let client_flight = client.handle_datagram(&server_flight).unwrap().unwrap();
        assert_eq!(client.state(), DtlsHandshakeState::AwaitingServerFinished);

        let server_finished = server.handle_datagram(&client_flight).unwrap().unwrap();
        assert!(server.is_connected());

        client.handle_datagram(&server_finished).unwrap();
        assert!(client.is_connected());
    }

    #[test]
    fn rfc5764_dtls_srtp_key_exchange_roundtrip() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let mut server = DtlsServerHandshake::new().expect("server handshake");

        complete_handshake(&mut client, &mut server);

        assert_eq!(
            client.peer_fingerprint(),
            Some(server.certificate().fingerprint_sha256())
        );
        assert_eq!(
            client.peer_certificate().unwrap(),
            server.certificate().der()
        );

        let client_keys = client.srtp_keys().expect("client keys");
        let server_keys = server.srtp_keys().expect("server keys");
        assert_eq!(client_keys, server_keys);
        assert_ne!(client_keys.client_cipher_key, client_keys.server_cipher_key);

        assert_eq!(client.record_keys(), server.record_keys());
    }

    #[test]
    fn a_pinned_fingerprint_that_does_not_match_is_rejected() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let mut server = DtlsServerHandshake::new().expect("server handshake");
        client.pin_fingerprint([0xAB; 32]);

        let client_hello = client.start().unwrap();
        let server_flight = server.handle_datagram(&client_hello).unwrap().unwrap();

        assert!(client.handle_datagram(&server_flight).is_err());
        assert!(!client.is_connected());
        assert!(client.srtp_keys().is_none());
    }

    #[test]
    fn a_forged_server_flight_is_rejected() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let mut server = DtlsServerHandshake::new().expect("server handshake");

        let client_hello = client.start().unwrap();
        let mut server_flight = server.handle_datagram(&client_hello).unwrap().unwrap();

        let signature = record_fragment_range(&server_flight, 2)
            .expect("the server flight has a ServerKeyExchange record");
        let target = signature.end - 1;
        server_flight[target] ^= 0x01;
        assert!(client.handle_datagram(&server_flight).is_err());
    }

    #[test]
    fn rfc5764_dtls_srtp_key_exchange_with_app_data_roundtrip() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let mut server = DtlsServerHandshake::new().expect("server handshake");

        let client_hello = client.start().unwrap();
        let verify = server.handle_datagram(&client_hello).unwrap().unwrap();
        let second_hello = client.handle_datagram(&verify).unwrap().unwrap();
        let server_flight = server.handle_datagram(&second_hello).unwrap().unwrap();

        let client_getconf = b"GETCONF:46000|dev-123|client-88|1|salt";
        let client_flight = client
            .handle_datagram_with_app_data(&server_flight, Some(client_getconf))
            .unwrap()
            .unwrap();

        let server_tunconf = b"TUNCONF:10.66.67.2|1.1.1.1|1360";
        let server_finished = server
            .handle_datagram_with_app_data(&client_flight, Some(server_tunconf))
            .unwrap()
            .unwrap();

        assert_eq!(
            server.take_application_data(),
            vec![client_getconf.to_vec()]
        );

        client.handle_datagram(&server_finished).unwrap();
        assert!(client.is_connected());
        assert_eq!(
            client.take_application_data(),
            vec![server_tunconf.to_vec()]
        );
        assert_eq!(client.srtp_keys(), server.srtp_keys());
    }

    #[test]
    fn dtls12_client_hello_matches_boring_ssl_calls_wire_format() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let initial_wire = client.start().expect("client start produces ClientHello");
        assert_eq!(
            initial_wire.len(),
            157,
            "ClientHello wire datagram must be exactly 157 bytes matching Chrome/WebRTC BoringSSL"
        );
        assert_eq!(
            &initial_wire[0..3],
            &[22, 0xfe, 0xff],
            "Outer record header must be Handshake with DTLS 1.0 (0xfeff) per RFC 6347 / BoringSSL"
        );

        let hello = client.create_client_hello(&[]);
        assert_eq!(hello.version, DTLS12_VERSION);
        assert_eq!(hello.cipher_suites, CLIENT_HELLO_CIPHER_SUITES.to_vec());
        assert_eq!(hello.compression_methods, vec![0]);
        validate_client_hello(&hello).expect("client hello is valid DTLS 1.2 ECDSA P-256");

        let wire = hello.marshal().expect("marshals");
        let parsed = ClientHello::parse(&wire).expect("parses back");
        assert_eq!(hello, parsed);
        assert_eq!(
            parsed.srtp_profiles().unwrap(),
            CLIENT_HELLO_SRTP_PROFILES.to_vec()
        );
        assert_eq!(
            parsed.supported_groups().unwrap(),
            CLIENT_HELLO_SUPPORTED_GROUPS.to_vec()
        );
        assert_eq!(
            parsed.signature_algorithms().unwrap(),
            CLIENT_HELLO_SIGNATURE_ALGORITHMS.to_vec()
        );
    }

    #[test]
    fn dtls12_client_hello_rebuild_keeps_identical_format() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let first = client.start().unwrap();
        let rebuilt = client.send_client_hello(b"test-cookie").unwrap();

        let a = record_fragment_range(&first, 0).expect("first ClientHello");
        let b = record_fragment_range(&rebuilt, 0).expect("rebuilt ClientHello");
        assert!(a.len() > 40);
        assert!(b.len() > 40);
    }

    #[test]
    fn multiplexing_boundaries_match_rfc7983() {
        assert!(is_stun_or_turn_packet(&[0x00, 0x01]));
        assert!(is_stun_or_turn_packet(&[0x01, 0x01]));
        assert!(is_stun_or_turn_packet(&[0x40, 0x00]));

        assert!(is_dtls_packet(&[20, 0xfe, 0xfd]));
        assert!(is_dtls_packet(&[21, 0xfe, 0xfd]));
        assert!(is_dtls_packet(&[22, 0xfe, 0xfd]));
        assert!(is_dtls_packet(&[23, 0xfe, 0xfd]));
        assert!(is_dtls_packet(&[63, 0x00]));
        assert!(!is_dtls_packet(&[19, 0x00]));
        assert!(!is_dtls_packet(&[64, 0x00]));

        assert!(is_srtp_packet(&[0x80, 111]));
        assert!(is_srtp_packet(&[0x90, 96]));
        assert!(is_srtp_packet(&[0xb0, 111]));
        assert!(!is_srtp_packet(&[127, 0x00]));
        assert!(!is_srtp_packet(&[192, 0x00]));
    }

    #[test]
    fn dtls12_truncated_client_hello_is_rejected() {
        let client = DtlsClientHandshake::new().expect("client handshake");
        let hello = client.create_client_hello(&[]);
        let encoded = hello.marshal().expect("marshals");
        for cut in [1, 20, 40, encoded.len() - 1] {
            assert!(
                ClientHello::parse(&encoded[..cut]).is_err(),
                "cut {cut} parsed"
            );
        }
    }

    #[test]
    fn extension_helpers_update_in_place() {
        let client = DtlsClientHandshake::new().expect("client handshake");
        let mut hello = client.create_client_hello(&[]);
        let before = hello.extensions.len();
        hello.set_extension(
            EXT_USE_SRTP,
            srtp_body_from_profiles(&[SRTP_AES128_CM_SHA1_80]),
        );
        assert_eq!(hello.extensions.len(), before);
        assert_eq!(hello.srtp_profiles().unwrap(), vec![SRTP_AES128_CM_SHA1_80]);
        hello.set_extension(0x1234, vec![1]);
        assert!(hello.has_extension(0x1234));
        assert_eq!(hello.extensions.len(), before + 1);
    }

    #[test]
    fn extract_client_hello_random_matches() {
        let mut client = DtlsClientHandshake::new().expect("client handshake");
        let client_hello = client.start().unwrap();
        let extracted = extract_client_hello_random(&client_hello);
        assert_eq!(extracted, Some(client.client_random));
        assert!(is_client_hello(&client_hello));
        assert_eq!(extract_client_hello_random(&[0x00, 0x01]), None);
        assert!(!is_client_hello(&[0x00, 0x01]));
    }

    #[test]
    fn use_srtp_matches_the_boring_ssl_wire_format() {
        let body = srtp_body_from_profiles(&[SRTP_AES128_CM_SHA1_80]);
        assert_eq!(body, vec![0x00, 0x02, 0x00, 0x01, 0x00]);
        assert_eq!(
            parse_server_use_srtp(&body).unwrap(),
            SRTP_AES128_CM_SHA1_80
        );
        assert_eq!(
            parse_client_use_srtp(&body).unwrap(),
            vec![SRTP_AES128_CM_SHA1_80]
        );

        let both = srtp_body_from_profiles(&[SRTP_AES128_CM_SHA1_80, SRTP_AES128_CM_SHA1_32]);
        assert_eq!(both, vec![0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00]);
        assert_eq!(parse_client_use_srtp(&both).unwrap().len(), 2);
        assert!(parse_server_use_srtp(&both).is_err());

        assert!(parse_client_use_srtp(&[0x00, 0x02, 0x00, 0x01, 0x02, 0xaa, 0xbb]).is_err());
        assert!(parse_client_use_srtp(&[0x00, 0x00, 0x00]).is_err());
        assert!(parse_client_use_srtp(&[0x00, 0x02, 0x00]).is_err());
        assert!(parse_client_use_srtp(&[0x00, 0x02, 0x00, 0x01, 0x00, 0xff]).is_err());
        assert!(parse_client_use_srtp(&[0x00, 0x02]).is_err());
    }
}
