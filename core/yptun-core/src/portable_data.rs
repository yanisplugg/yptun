//! PortableData (org.olcbox.app.desktop.PortableData): the portable build keeps its own data in ONE
//! encrypted file next to the .exe, `YPtun.data` = magic | 12-byte nonce | AES-256-GCM(zip), the magic
//! as associated data. The key is built in on purpose (the file has to open on any PC).
//!
//! AES-GCM and the zip reader/writer are done here on top of the existing `aes`, `sha2` and `flate2`
//! dependencies, so nothing new is pulled in. Files are byte-compatible with the Kotlin build.

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, UNIX_EPOCH};

use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockEncrypt, KeyInit};
use aes::Aes256;
use flate2::read::DeflateDecoder;
use flate2::write::DeflateEncoder;
use flate2::{Compression, Crc};
use sha2::{Digest, Sha256};

pub const PORTABLE_DATA_FILE_NAME: &str = "YPtun.data";
pub const PORTABLE_DATA_MAGIC: &[u8; 4] = b"YPD1";
pub const SAVE_INTERVAL_MS: u64 = 10_000;
const KEY_SEED: &[u8] = b"YPtun/portable-data/v1/9f3c1b7a";

/// What the bundle carries, relative to the data dir: every top-level *.json, device_identity, settings/*.
pub fn included(relative: &str) -> bool {
    let name = relative.replace('\\', "/");
    if name.contains("..") || name.starts_with('/') {
        return false;
    }
    if name == "device_identity" {
        return true;
    }
    if !name.contains('/') {
        return name.ends_with(".json");
    }
    if name.starts_with("settings/") {
        return name.matches('/').count() == 1;
    }
    false
}

// ---- AES-256-GCM ------------------------------------------------------------------------------

fn key() -> [u8; 32] {
    let d = Sha256::digest(KEY_SEED);
    let mut k = [0u8; 32];
    k.copy_from_slice(&d);
    k
}

fn encrypt_block(cipher: &Aes256, block: &[u8; 16]) -> [u8; 16] {
    let mut b = GenericArray::clone_from_slice(block);
    cipher.encrypt_block(&mut b);
    let mut out = [0u8; 16];
    out.copy_from_slice(&b);
    out
}

fn gf_mul(x: u128, y: u128) -> u128 {
    let r: u128 = 0xE1u128 << 120;
    let mut z = 0u128;
    let mut v = y;
    for i in 0..128 {
        if (x >> (127 - i)) & 1 == 1 {
            z ^= v;
        }
        v = if v & 1 == 1 { (v >> 1) ^ r } else { v >> 1 };
    }
    z
}

fn block_u128(chunk: &[u8]) -> u128 {
    let mut b = [0u8; 16];
    b[..chunk.len()].copy_from_slice(chunk);
    u128::from_be_bytes(b)
}

fn ghash(h: u128, aad: &[u8], ct: &[u8]) -> u128 {
    let mut y = 0u128;
    for c in aad.chunks(16) {
        y = gf_mul(y ^ block_u128(c), h);
    }
    for c in ct.chunks(16) {
        y = gf_mul(y ^ block_u128(c), h);
    }
    let lens = ((aad.len() as u128 * 8) << 64) | (ct.len() as u128 * 8);
    gf_mul(y ^ lens, h)
}

fn ctr_xor(cipher: &Aes256, j0: &[u8; 16], data: &mut [u8]) {
    let mut counter = u32::from_be_bytes([j0[12], j0[13], j0[14], j0[15]]);
    let mut block = *j0;
    for chunk in data.chunks_mut(16) {
        counter = counter.wrapping_add(1);
        block[12..].copy_from_slice(&counter.to_be_bytes());
        let ks = encrypt_block(cipher, &block);
        for (b, k) in chunk.iter_mut().zip(ks.iter()) {
            *b ^= k;
        }
    }
}

fn gcm_parts(key: &[u8; 32], nonce: &[u8; 12]) -> (Aes256, u128, [u8; 16]) {
    let cipher = Aes256::new(GenericArray::from_slice(key));
    let h = u128::from_be_bytes(encrypt_block(&cipher, &[0u8; 16]));
    let mut j0 = [0u8; 16];
    j0[..12].copy_from_slice(nonce);
    j0[15] = 1;
    (cipher, h, j0)
}

/// AES-256-GCM encrypt: ciphertext followed by the 16-byte tag.
pub fn aes256_gcm_encrypt(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let (cipher, h, j0) = gcm_parts(key, nonce);
    let mut out = plaintext.to_vec();
    ctr_xor(&cipher, &j0, &mut out);
    let s = ghash(h, aad, &out);
    let tag = u128::from_be_bytes(encrypt_block(&cipher, &j0)) ^ s;
    out.extend_from_slice(&tag.to_be_bytes());
    out
}

/// AES-256-GCM decrypt of ciphertext||tag; Err on a wrong tag.
pub fn aes256_gcm_decrypt(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], data: &[u8]) -> Result<Vec<u8>, String> {
    if data.len() < 16 {
        return Err(String::from("ciphertext too short"));
    }
    let (ct, tag) = data.split_at(data.len() - 16);
    let (cipher, h, j0) = gcm_parts(key, nonce);
    let expected = (u128::from_be_bytes(encrypt_block(&cipher, &j0)) ^ ghash(h, aad, ct)).to_be_bytes();
    let diff = expected.iter().zip(tag.iter()).fold(0u8, |acc, (a, b)| acc | (a ^ b));
    if diff != 0 {
        return Err(String::from("Tag mismatch"));
    }
    let mut out = ct.to_vec();
    ctr_xor(&cipher, &j0, &mut out);
    Ok(out)
}

/// seal with a given nonce (tests); [seal] picks a random one.
pub fn seal_with_nonce(payload: &[u8], nonce: &[u8; 12]) -> Vec<u8> {
    let mut out = PORTABLE_DATA_MAGIC.to_vec();
    out.extend_from_slice(nonce);
    out.extend_from_slice(&aes256_gcm_encrypt(&key(), nonce, PORTABLE_DATA_MAGIC, payload));
    out
}

pub fn seal(payload: &[u8]) -> Result<Vec<u8>, String> {
    let mut nonce = [0u8; 12];
    getrandom::getrandom(&mut nonce).map_err(|e| e.to_string())?;
    Ok(seal_with_nonce(payload, &nonce))
}

/// Inverse of [seal]; Err on a foreign, damaged or tampered file.
pub fn open(file: &[u8]) -> Result<Vec<u8>, String> {
    let m = PORTABLE_DATA_MAGIC.len();
    if !(file.len() > m + 12 + 16 && &file[..m] == PORTABLE_DATA_MAGIC) {
        return Err(String::from("not a YPtun data file"));
    }
    let mut nonce = [0u8; 12];
    nonce.copy_from_slice(&file[m..m + 12]);
    aes256_gcm_decrypt(&key(), &nonce, PORTABLE_DATA_MAGIC, &file[m + 12..])
}

// ---- zip --------------------------------------------------------------------------------------

fn put16(v: &mut Vec<u8>, x: u16) {
    v.extend_from_slice(&x.to_le_bytes());
}

fn put32(v: &mut Vec<u8>, x: u32) {
    v.extend_from_slice(&x.to_le_bytes());
}

fn get16(b: &[u8], at: usize) -> Result<u16, String> {
    b.get(at..at + 2).map(|s| u16::from_le_bytes([s[0], s[1]])).ok_or_else(|| String::from("truncated zip"))
}

fn get32(b: &[u8], at: usize) -> Result<u32, String> {
    b.get(at..at + 4).map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]])).ok_or_else(|| String::from("truncated zip"))
}

const DOS_DATE_1980: u16 = 0x0021;

/// A deflated zip of the given (name, bytes) entries, readable by java.util.zip.
pub fn zip_entries(entries: &[(String, Vec<u8>)]) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (name, data) in entries {
        let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data)?;
        let compressed = enc.finish()?;
        let mut crc = Crc::new();
        crc.update(data);
        let offset = out.len() as u32;
        let n = name.as_bytes();
        put32(&mut out, 0x0403_4b50);
        put16(&mut out, 20);
        put16(&mut out, 0x0800);
        put16(&mut out, 8);
        put16(&mut out, 0);
        put16(&mut out, DOS_DATE_1980);
        put32(&mut out, crc.sum());
        put32(&mut out, compressed.len() as u32);
        put32(&mut out, data.len() as u32);
        put16(&mut out, n.len() as u16);
        put16(&mut out, 0);
        out.extend_from_slice(n);
        out.extend_from_slice(&compressed);

        put32(&mut central, 0x0201_4b50);
        put16(&mut central, 20);
        put16(&mut central, 20);
        put16(&mut central, 0x0800);
        put16(&mut central, 8);
        put16(&mut central, 0);
        put16(&mut central, DOS_DATE_1980);
        put32(&mut central, crc.sum());
        put32(&mut central, compressed.len() as u32);
        put32(&mut central, data.len() as u32);
        put16(&mut central, n.len() as u16);
        put16(&mut central, 0);
        put16(&mut central, 0);
        put16(&mut central, 0);
        put16(&mut central, 0);
        put32(&mut central, 0);
        put32(&mut central, offset);
        central.extend_from_slice(n);
    }
    let cd_offset = out.len() as u32;
    let cd_size = central.len() as u32;
    out.extend_from_slice(&central);
    put32(&mut out, 0x0605_4b50);
    put16(&mut out, 0);
    put16(&mut out, 0);
    put16(&mut out, entries.len() as u16);
    put16(&mut out, entries.len() as u16);
    put32(&mut out, cd_size);
    put32(&mut out, cd_offset);
    put16(&mut out, 0);
    Ok(out)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZipItem {
    pub name: String,
    pub is_directory: bool,
    pub data: Vec<u8>,
}

/// Reads a (non-zip64) zip through its central directory; stored and deflated entries, CRC checked.
pub fn unzip_entries(bytes: &[u8]) -> Result<Vec<ZipItem>, String> {
    if bytes.len() < 22 {
        return Err(String::from("not a zip"));
    }
    let mut eocd = None;
    let mut i = bytes.len() - 22;
    loop {
        if get32(bytes, i)? == 0x0605_4b50 {
            eocd = Some(i);
            break;
        }
        if i == 0 || bytes.len() - i > 22 + 0xFFFF {
            break;
        }
        i -= 1;
    }
    let eocd = eocd.ok_or_else(|| String::from("not a zip"))?;
    let count = get16(bytes, eocd + 10)? as usize;
    let mut p = get32(bytes, eocd + 16)? as usize;
    let mut items = Vec::with_capacity(count);
    for _ in 0..count {
        if get32(bytes, p)? != 0x0201_4b50 {
            return Err(String::from("bad central directory"));
        }
        let method = get16(bytes, p + 10)?;
        let crc = get32(bytes, p + 16)?;
        let csize = get32(bytes, p + 20)? as usize;
        let usize_ = get32(bytes, p + 24)? as usize;
        let n = get16(bytes, p + 28)? as usize;
        let e = get16(bytes, p + 30)? as usize;
        let c = get16(bytes, p + 32)? as usize;
        let local = get32(bytes, p + 42)? as usize;
        let name_bytes = bytes.get(p + 46..p + 46 + n).ok_or_else(|| String::from("truncated zip"))?;
        let name = String::from_utf8_lossy(name_bytes).to_string();
        p += 46 + n + e + c;
        if get32(bytes, local)? != 0x0403_4b50 {
            return Err(String::from("bad local header"));
        }
        let ln = get16(bytes, local + 26)? as usize;
        let le = get16(bytes, local + 28)? as usize;
        let start = local + 30 + ln + le;
        let raw = bytes.get(start..start + csize).ok_or_else(|| String::from("truncated zip"))?;
        let data = match method {
            0 => raw.to_vec(),
            8 => {
                let mut out = Vec::with_capacity(usize_);
                DeflateDecoder::new(raw).read_to_end(&mut out).map_err(|e| e.to_string())?;
                out
            }
            m => return Err(format!("unsupported zip method {}", m)),
        };
        let mut check = Crc::new();
        check.update(&data);
        if check.sum() != crc {
            return Err(format!("invalid entry CRC: {}", name));
        }
        items.push(ZipItem { is_directory: name.ends_with('/'), name, data });
    }
    Ok(items)
}

// ---- the data dir -----------------------------------------------------------------------------

/// Included files of `dir` (depth <= 2), sorted by relative path.
pub fn included_files(dir: &Path) -> Vec<(String, PathBuf)> {
    let mut out = Vec::new();
    let Ok(top) = fs::read_dir(dir) else {
        return out;
    };
    for e in top.flatten() {
        let path = e.path();
        let name = e.file_name().to_string_lossy().to_string();
        if path.is_file() {
            out.push((name, path));
        } else if path.is_dir() {
            if let Ok(sub) = fs::read_dir(&path) {
                for s in sub.flatten() {
                    let sp = s.path();
                    if sp.is_file() {
                        out.push((format!("{}/{}", name, s.file_name().to_string_lossy()), sp));
                    }
                }
            }
        }
    }
    out.retain(|(rel, _)| included(rel));
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// Cheap change detector: name + size + mtime of every included file.
pub fn signature(dir: &Path) -> String {
    included_files(dir)
        .iter()
        .map(|(rel, path)| match fs::metadata(path) {
            Ok(m) => {
                let mtime = m
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_millis().to_string())
                    .unwrap_or_else(|| String::from("null"));
                format!("{}:{}:{}", rel, m.len(), mtime)
            }
            Err(_) => format!("{}:null:null", rel),
        })
        .collect::<Vec<_>>()
        .join("|")
}

pub fn clear_included(dir: &Path) {
    for (_, path) in included_files(dir) {
        let _ = fs::remove_file(path);
    }
}

/// The included files of `dir` as a zip.
pub fn pack(dir: &Path) -> io::Result<Vec<u8>> {
    let mut entries = Vec::new();
    for (rel, path) in included_files(dir) {
        entries.push((rel, fs::read(path)?));
    }
    zip_entries(&entries)
}

fn escapes(rel: &Path) -> bool {
    rel.components().any(|c| !matches!(c, Component::Normal(_) | Component::CurDir))
}

/// Writes the zip's entries under `dir`; anything that is not an included relative path is refused.
pub fn unpack_into(dir: &Path, zip_bytes: &[u8]) -> Result<(), String> {
    for item in unzip_entries(zip_bytes)? {
        if item.is_directory {
            continue;
        }
        if !included(&item.name) {
            return Err(format!("unexpected entry in the portable data: {}", item.name));
        }
        let rel = PathBuf::from(item.name.replace('\\', "/"));
        if escapes(&rel) {
            return Err(format!("entry escapes the data dir: {}", item.name));
        }
        let target = dir.join(&rel);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        fs::write(&target, &item.data).map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn sibling(bundle: &Path, name: &str) -> PathBuf {
    match bundle.parent() {
        Some(p) => p.join(name),
        None => PathBuf::from(name),
    }
}

/// Writes the bundle back next to the exe whenever the included files changed.
pub struct Saver {
    dir: PathBuf,
    bundle: PathBuf,
    last_saved: Mutex<Option<String>>,
}

impl Saver {
    pub fn new(dir: PathBuf, bundle: PathBuf) -> Self {
        Saver { dir, bundle, last_saved: Mutex::new(None) }
    }

    /// True when a new bundle was written. Read-only media / file in use: try again next time.
    pub fn save_if_changed(&self) -> bool {
        let mut last = self.last_saved.lock().unwrap();
        let sig = signature(&self.dir);
        if sig.is_empty() || last.as_deref() == Some(sig.as_str()) {
            return false;
        }
        let Ok(zip) = pack(&self.dir) else {
            return false;
        };
        let Ok(sealed) = seal(&zip) else {
            return false;
        };
        let tmp = sibling(&self.bundle, &format!("{}.tmp", PORTABLE_DATA_FILE_NAME));
        if fs::write(&tmp, &sealed).is_err() {
            return false;
        }
        if fs::rename(&tmp, &self.bundle).is_err() {
            let _ = fs::remove_file(&tmp);
            return false;
        }
        *last = Some(sig);
        true
    }
}

/// `%LOCALAPPDATA%\YPtun\portable-data`.
pub fn private_copy_dir() -> io::Result<PathBuf> {
    let local = match std::env::var("LOCALAPPDATA").ok().filter(|s| !s.trim().is_empty()) {
        Some(l) => PathBuf::from(l),
        None => crate::desktop_app::home_dir().join("AppData").join("Local"),
    };
    let dir = local.join("YPtun").join("portable-data");
    fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// The decision of PortableData.decide: (data dir, bundle to save into). No bundle = not portable.
pub fn decide(
    standard: PathBuf,
    is_portable: bool,
    portable_exe: Option<&Path>,
    private_dir: &dyn Fn() -> io::Result<PathBuf>,
) -> (PathBuf, Option<PathBuf>) {
    if !is_portable {
        return (standard, None);
    }
    let Some(exe) = portable_exe else {
        return (standard, None);
    };
    let Some(parent) = exe.parent() else {
        return (standard, None);
    };
    let bundle = parent.join(PORTABLE_DATA_FILE_NAME);
    let mut dir = standard;
    if bundle.is_file() {
        let restored = (|| -> Result<PathBuf, String> {
            let work = private_dir().map_err(|e| e.to_string())?;
            clear_included(&work);
            let bytes = fs::read(&bundle).map_err(|e| e.to_string())?;
            unpack_into(&work, &open(&bytes)?)?;
            Ok(work)
        })();
        match restored {
            Ok(work) => dir = work,
            Err(_) => {
                let _ = fs::rename(&bundle, sibling(&bundle, &format!("{}.bad", PORTABLE_DATA_FILE_NAME)));
            }
        }
    }
    (dir, Some(bundle))
}

static ACTIVE: OnceLock<PathBuf> = OnceLock::new();
static SAVER: OnceLock<Arc<Saver>> = OnceLock::new();

fn start_saver(dir: PathBuf, bundle: PathBuf) {
    let saver = Arc::new(Saver::new(dir, bundle));
    let _ = SAVER.set(saver.clone());
    let _ = thread::Builder::new().name(String::from("portable-data-saver")).spawn(move || loop {
        thread::sleep(Duration::from_millis(SAVE_INTERVAL_MS));
        saver.save_if_changed();
    });
}

/// Saves now if anything changed. Rust has no shutdown hook: the app calls this on exit.
pub fn save_now() {
    if let Some(s) = SAVER.get() {
        s.save_if_changed();
    }
}

/// The directory the app must use for its data, decided once.
pub fn resolve(standard: fn() -> PathBuf) -> PathBuf {
    ACTIVE
        .get_or_init(|| {
            let portable = crate::desktop_app::is_portable();
            let exe = if portable { crate::desktop_app::portable_executable() } else { None };
            let (dir, bundle) = decide(standard(), portable, exe.as_deref(), &private_copy_dir);
            if let Some(b) = bundle {
                start_saver(dir.clone(), b);
            }
            dir
        })
        .clone()
}
