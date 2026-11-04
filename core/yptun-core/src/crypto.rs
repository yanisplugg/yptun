//! Link obfuscation and compression shared with the panels (port of `CsqttLinkCipher`,
//! `SubscriptionCrypto` and `Compression`). The keys are public by design: they keep links out of
//! the UI and screenshots, they do not protect against someone who reads the app.

use std::io::{Read, Write};

use aes::cipher::{KeyIvInit, StreamCipher};
use base64::alphabet;
use base64::engine::general_purpose::{GeneralPurpose, GeneralPurposeConfig};
use base64::engine::DecodePaddingMode;
use base64::Engine;

fn hex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

/// base64url, padding optional, `+`/`/` accepted as `-`/`_`, whitespace ignored.
pub(crate) fn b64url_lenient(body: &str) -> Option<Vec<u8>> {
    let clean: String = body
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| match c {
            '+' => '-',
            '/' => '_',
            c => c,
        })
        .collect();
    let clean = clean.trim_end_matches('=');
    GeneralPurpose::new(&alphabet::URL_SAFE, GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent))
        .decode(clean)
        .ok()
}

// ---------------- csqtt 2.5.0 link cipher ----------------

/// SHA-256("csqtt-chiper-key") (rust-server/web_panel.rs `encrypt_csqtt_link`).
const CSQTT_KEY: &str = "e0570f20d1d8806d842bd3839967ff7f316168ce787d6149781808a39b56c0a8";

type Aes256Ctr = ctr::Ctr32BE<aes::Aes256>;

/// `csqtt://<base64url(iv12 + AES-256-GCM(connect?v=2&…) + tag16)>` → the `connect?…` payload.
/// The GCM tag is not checked: a wrong blob decrypts to garbage that the parser rejects.
pub fn csqtt_decrypt(body: &str) -> Option<String> {
    let blob = b64url_lenient(body)?;
    if blob.len() < 12 + 16 {
        return None;
    }
    let mut iv = [0u8; 16];
    iv[..12].copy_from_slice(&blob[..12]);
    iv[15] = 2; // GCM: counter block 1 masks the tag, data starts at 2
    let mut data = blob[12..blob.len() - 16].to_vec();
    let key = hex(CSQTT_KEY);
    Aes256Ctr::new(key.as_slice().into(), (&iv).into()).apply_keystream(&mut data);
    let text = String::from_utf8_lossy(&data).into_owned();
    text.starts_with("connect?").then_some(text)
}

// ---------------- encrypted panel subscriptions ----------------

pub const SUB_ENC_PREFIX: &str = "yptun-enc://v1/";
const SUB_ENC_MAGIC: &[u8] = b"YPE1";
const SUB_ENC_KEY: &str = "3ada5b1420a9a6ba2f7524a69eb8bc5fd05d58fd4a66b61229182463fb327a19";

/// Text with every `yptun-enc://` line replaced by its link; `encrypted` = at least one was there.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decrypted {
    pub text: String,
    pub encrypted: bool,
}

/// Decrypts every encrypted line; unreadable lines are dropped rather than shown as garbage.
pub fn decrypt_subscription_lines(text: &str) -> Decrypted {
    let mut found = false;
    let lines: Vec<String> = crate::share_link::lines(text)
        .filter_map(|raw| {
            let line = raw.trim();
            if !line.starts_with(SUB_ENC_PREFIX) {
                return Some(raw.to_string());
            }
            found = true;
            decrypt_subscription_line(line)
        })
        .collect();
    Decrypted { text: if found { lines.join("\n") } else { text.to_string() }, encrypted: found }
}

/// `yptun-enc://v1/<base64url(nonce12 + ChaCha20("YPE1" + link))>` → the link.
pub fn decrypt_subscription_line(line: &str) -> Option<String> {
    let body = line.strip_prefix(SUB_ENC_PREFIX)?;
    let blob = b64url_lenient(body)?;
    if blob.len() < 12 + SUB_ENC_MAGIC.len() {
        return None;
    }
    let mut plain = blob[12..].to_vec();
    chacha20_xor(&hex(SUB_ENC_KEY), &blob[..12], &mut plain);
    let link = plain.strip_prefix(SUB_ENC_MAGIC)?;
    Some(String::from_utf8_lossy(link).into_owned())
}

/// The panel's encryption (for tests and for re-sharing a locked subscription is never needed).
pub fn encrypt_subscription_line(link: &str, nonce: [u8; 12]) -> String {
    let mut data = [SUB_ENC_MAGIC, link.as_bytes()].concat();
    chacha20_xor(&hex(SUB_ENC_KEY), &nonce, &mut data);
    let blob = [nonce.as_slice(), &data].concat();
    format!("{SUB_ENC_PREFIX}{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(blob))
}

fn chacha20_xor(key: &[u8], nonce: &[u8], data: &mut [u8]) {
    use chacha20::cipher::{KeyIvInit as _, StreamCipher as _};
    chacha20::ChaCha20::new(key.into(), nonce.into()).apply_keystream(data);
}

// ---------------- DEFLATE ----------------

/// zlib (RFC 1950) at the best level — what java.util.zip.Deflater writes.
pub fn deflate(data: &[u8]) -> Option<Vec<u8>> {
    let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::best());
    enc.write_all(data).ok()?;
    enc.finish().ok()
}

/// zlib first, then raw DEFLATE (the share formats use both).
pub fn inflate(data: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    if flate2::read::ZlibDecoder::new(data).read_to_end(&mut out).is_ok() && !out.is_empty() {
        return Some(out);
    }
    out.clear();
    flate2::read::DeflateDecoder::new(data).read_to_end(&mut out).ok()?;
    (!out.is_empty()).then_some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subscription_line_round_trip() {
        let enc = encrypt_subscription_line("vless://u@h:1#Locked", [7; 12]);
        assert!(enc.starts_with(SUB_ENC_PREFIX));
        assert_eq!(decrypt_subscription_line(&enc).as_deref(), Some("vless://u@h:1#Locked"));
        let d = decrypt_subscription_lines(&format!("#name: x\n{enc}\nyptun-enc://v1/!!!"));
        assert!(d.encrypted);
        assert_eq!(d.text, "#name: x\nvless://u@h:1#Locked");
    }

    #[test]
    fn deflate_round_trip_and_raw_inflate() {
        let data = b"[Interface]\nPrivateKey = x\n".repeat(20);
        assert_eq!(inflate(&deflate(&data).unwrap()).unwrap(), data);
        let mut raw = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::fast());
        raw.write_all(&data).unwrap();
        assert_eq!(inflate(&raw.finish().unwrap()).unwrap(), data);
    }
}
