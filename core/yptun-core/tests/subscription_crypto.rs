//! Ported from SubscriptionCryptoTest and ZlibWrapTest: the encrypted panel subscription
//! (`yptun-enc://`) and the zlib stream of share links, checked against what the panel and the
//! released apps produce and read.

use std::io::{Read, Write};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use yptun_core::crypto::*;

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

/// The string comes from the panel's own subcrypt.encrypt (tests/test_panel.py checks the same value).
const PANEL_LINE: &str = "yptun-enc://v1/iQNHVid_1bM4ILvOVmPtCjZCobO8WBANIqvem3QJJKmnRw";

/// RFC 8439 section 2.4.2 (counter 1): the cipher behind `yptun-enc://` is the IETF ChaCha20.
#[test]
fn chacha20_matches_rfc8439() {
    use chacha20::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
    let key: Vec<u8> = (0u8..32).collect();
    let nonce = unhex("000000000000004a00000000");
    let mut data = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".to_vec();
    let mut cipher = chacha20::ChaCha20::new(key.as_slice().into(), nonce.as_slice().into());
    cipher.seek(64u32); // block counter 1
    cipher.apply_keystream(&mut data);
    assert_eq!(
        hex(&data),
        "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab\
         8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d"
    );
}

#[test]
fn decrypts_what_the_panel_produces() {
    assert_eq!(
        decrypt_subscription_line(PANEL_LINE).as_deref(),
        Some("openflux://v1/test")
    );
    assert_eq!(decrypt_subscription_line("yptun-enc://v1/AAAA"), None);
    assert_eq!(decrypt_subscription_line("vless://x"), None);
    // With the panel's nonce the core writes the very same line.
    let blob = URL_SAFE_NO_PAD
        .decode(PANEL_LINE.strip_prefix(SUB_ENC_PREFIX).unwrap())
        .unwrap();
    let nonce: [u8; 12] = blob[..12].try_into().unwrap();
    assert_eq!(
        encrypt_subscription_line("openflux://v1/test", nonce),
        PANEL_LINE
    );
}

#[test]
fn decrypt_lines_keeps_comments() {
    let d = decrypt_subscription_lines(&format!("{PANEL_LINE}\n#order:A\n"));
    assert!(d.encrypted);
    assert_eq!(d.text, "openflux://v1/test\n#order:A\n");
    let plain = decrypt_subscription_lines("vless://x\n");
    assert!(!plain.encrypted);
    assert_eq!(plain.text, "vless://x\n");
}

// ---------------- zlib ----------------

fn payload() -> Vec<u8> {
    "[Interface]\nPrivateKey = abc\nJc = 4\n"
        .repeat(40)
        .into_bytes()
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &x in data {
        a = (a + x as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// True when `data` starts with a zlib header (CM=8, FCHECK valid) and has room for the trailer.
fn has_zlib_header(data: &[u8]) -> bool {
    data.len() >= 6
        && data[0] & 0x0F == 8
        && (data[0] as u32 * 256 + data[1] as u32).is_multiple_of(31)
}

/// Raw DEFLATE (RFC 1951), what Apple's "zlib" codec emits.
fn raw_deflate(data: &[u8]) -> Vec<u8> {
    let mut enc = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    enc.write_all(data).unwrap();
    enc.finish().unwrap()
}

/// Raw DEFLATE wrapped into zlib (2-byte header + stream + Adler-32), as the iOS app sends it.
fn zlib_wrap(raw: &[u8], original: &[u8]) -> Vec<u8> {
    [&[0x78, 0x9C], raw, &adler32(original).to_be_bytes()].concat()
}

/// The zlib-only reader of Android/desktop 3.5.2 and older (rejects a bad Adler-32 trailer).
fn classic_inflate(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    flate2::read::ZlibDecoder::new(data)
        .read_to_end(&mut out)
        .expect("zlib stream rejected");
    out
}

#[test]
fn deflate_writes_zlib_that_a_classic_inflater_opens() {
    let payload = payload();
    let packed = deflate(&payload).unwrap();
    assert!(has_zlib_header(&packed));
    assert_eq!(packed[packed.len() - 4..], adler32(&payload).to_be_bytes());
    assert_eq!(classic_inflate(&packed), payload);
    assert_eq!(inflate(&packed).unwrap(), payload);
}

#[test]
fn wrapped_raw_deflate_opens_with_classic_inflater() {
    let payload = payload();
    let raw = raw_deflate(&payload);
    let wrapped = zlib_wrap(&raw, &payload);
    assert!(has_zlib_header(&wrapped));
    assert_eq!(classic_inflate(&wrapped), payload);
    assert_eq!(inflate(&wrapped).unwrap(), payload);
    // A bare raw stream (no wrapper) still opens.
    assert_eq!(inflate(&raw).unwrap(), payload);
    // The classic reader really checks the trailer: a wrong Adler-32 is refused.
    let mut broken = wrapped.clone();
    *broken.last_mut().unwrap() ^= 1;
    assert!(flate2::read::ZlibDecoder::new(broken.as_slice())
        .read_to_end(&mut Vec::new())
        .is_err());
}
