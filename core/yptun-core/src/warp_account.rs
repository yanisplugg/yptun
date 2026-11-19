//! Getting a NEW Cloudflare WARP account (port of `WarpConfigGenerator`, the androidMain and jvmMain
//! copies are identical): ready accounts from server-side generators first (the Cloudflare API is
//! blocked in some regions), direct Cloudflare registration as the fallback, and the AmneziaWG INI
//! built from the account. The INI then goes through [`crate::warp`] like any stored WARP config.
//!
//! The core does no networking: the platform runs the requests through [`WarpHttp`].

use std::time::{SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde_json::{json, Value};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::isotime::civil_from_days;
use crate::repository::{HttpRequest, HttpResponse};

/// Standard WARP data-plane endpoint.
pub const ENDPOINT: &str = "engage.cloudflareclient.com:2408";

/// Server-side WARP generators that register with Cloudflare for us and return a ready account.
/// Tried in order until one answers. The last one (GitHub Pages) answers with a JSON ARRAY of
/// accounts, one is picked at random.
pub const SERVICE_ENDPOINTS: [&str; 6] = [
    "https://www.warp-generator.workers.dev",
    "https://warp.sub-aggregator.workers.dev",
    "https://warp-vercel-chi.vercel.app/api/warp-data",
    "https://warp-vercel-murex.vercel.app/api/warp-data",
    "https://warp-gen.netlify.app/",
    "https://breead1337.github.io/ws-cache-9e41/w.json",
];

/// Direct Cloudflare registration (fallback only — blocked in some regions).
pub const REG_URL: &str = "https://api.cloudflareclient.com/v0a2158/reg";
/// Pass-through of the same API on our Cloudflare Worker, tried when the direct one fails.
pub const REG_MIRROR_URL: &str = "https://cache-mirror-7q.cdn-edge-7q.workers.dev/cf/v0a2158/reg";
pub const CF_CLIENT_VERSION: &str = "a-6.3-2158";
pub const CF_USER_AGENT: &str = "okhttp/3.12.1";
/// The generators answer 500 to non-browser User-Agents.
pub const BROWSER_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// Whole-request timeouts, as in Kotlin: a generator 6 s; the official API 5 s (give up fast, the
/// mirror is next); the mirror 12 s.
pub const SERVICE_TIMEOUT_MS: u64 = 6_000;
pub const REG_TIMEOUT_MS: u64 = 5_000;
pub const REG_MIRROR_TIMEOUT_MS: u64 = 12_000;

/// Full tunnel: everything the local SOCKS client (Telegram) opens rides WARP.
const ALLOWED_IPS: &str = "0.0.0.0/0, ::/0";

/// AmneziaWG I1 init packet (DPI evasion) — the Amnezia "WARP" preset. Sent BEFORE the WireGuard
/// handshake; carries no secret, so it is shared by every account. Without it DPI networks let the
/// handshake through and then drop all data ("connects but moves no data").
pub const WARP_I1: &str = concat!(
    "<b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986ae0b728",
    "0135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393f9f0fa43f11e510e",
    "cb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b043121c5710ec289f471c91ee41",
    "4fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4f01b472362b8056c3172b513051f8322d1",
    "062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1bf9b0607c32ec4b3203a4ee21ab64df336212b97582",
    "80803fcab14933b0e7ee1e04a7becce3e2633f4852585c567894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554",
    "549bef9256111b2d67afde0b496f16962d4957ff654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa",
    "8da5f73588e82e9470f3b21b27b28c649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad",
    "9b138ca07d4a4a1650a2c2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3",
    "a8d7a5291326e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb07",
    "3ce65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68ab633f",
    "f7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12ee3b1a93f30d8ab9",
    "537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4b8e93047371175b1d6d9cc8a",
    "b745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7c369e031d2041e152040920ac2c5ab534",
    "0bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee12af583383d994e22c0cf42383834bcd3ada1b3825",
    "a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2d",
    "cf9348579a57d8d3490b2fa99f278f1c37d87dad9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865",
    "f4e155e9fe020279f434f3bf1bd117b717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d13",
    "0347594f13e9e07514fa370754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c",
    "61f2989dd44f0cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f7",
    "16738dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d87e3a8",
    "b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a3af9927136b441",
    "9e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3befe745fb7d634f70386d87",
    "3b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080724e1ab5d5f43074ecb53a479af21ed0",
    "3d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfbad06f5f83f7ad0429fa9a2719c56ffe3783460e166",
    "de2d8>",
);

/// HTTP done by the platform (OkHttp / URLSession / reqwest). `None` = transport error or timeout.
pub trait WarpHttp: Send + Sync {
    fn get(&self, request: &HttpRequest, timeout_ms: u64) -> Option<HttpResponse>;
    /// POST with `body` as `application/json` (the header is also in `request.headers`).
    fn post_json(&self, request: &HttpRequest, body: &str, timeout_ms: u64) -> Option<HttpResponse>;
}

/// WireGuard keys, base64 as in an INI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeyPair {
    pub private_key: String,
    pub public_key: String,
}

/// The X25519 key pair for a 32-byte secret (clamped the WireGuard way).
pub fn key_pair_from_secret(mut secret: [u8; 32]) -> KeyPair {
    secret[0] &= 248;
    secret[31] &= 127;
    secret[31] |= 64;
    let public = PublicKey::from(&StaticSecret::from(secret));
    KeyPair { private_key: STANDARD.encode(secret), public_key: STANDARD.encode(public.as_bytes()) }
}

/// A fresh random key pair (Kotlin `YpTunCore.awgGenerateKeyPair`).
pub fn generate_key_pair() -> Result<KeyPair, String> {
    let mut secret = [0u8; 32];
    getrandom::getrandom(&mut secret).map_err(|e| format!("Failed to generate WireGuard keypair: {e}"))?;
    Ok(key_pair_from_secret(secret))
}

/// What the INI is built from.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WarpAccount {
    pub private_key: String,
    pub peer_public_key: String,
    pub ipv4: String,
    pub ipv6: Option<String>,
    /// From Cloudflare's `client_id`; generator accounts have none.
    pub reserved: Option<[u8; 3]>,
}

fn non_blank(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str).filter(|s| !s.trim().is_empty()).map(str::to_string)
}

/// A generator answer: one account object, or an array of them (`pick(len)` chooses the index).
/// `None` when it is not JSON or lacks the private key, the peer key or the IPv4 address.
pub fn parse_service_account(text: &str, pick: impl FnOnce(usize) -> usize) -> Option<WarpAccount> {
    let value: Value = serde_json::from_str(text.trim()).ok()?;
    let account = match &value {
        Value::Array(items) => {
            if items.is_empty() {
                return None;
            }
            &items[pick(items.len()) % items.len()]
        }
        other => other,
    };
    Some(WarpAccount {
        private_key: non_blank(account.get("privKey"))?,
        peer_public_key: non_blank(account.get("peer_pub"))?,
        ipv4: non_blank(account.get("client_ipv4"))?,
        ipv6: non_blank(account.get("client_ipv6")),
        reserved: None,
    })
}

/// Cloudflare `client_id` (base64, ≥ 3 bytes) → the reserved bytes.
pub fn reserved_from_client_id(client_id: Option<&str>) -> Option<[u8; 3]> {
    let id = client_id.filter(|s| !s.trim().is_empty())?;
    let bytes = STANDARD.decode(id.trim()).ok()?;
    if bytes.len() < 3 {
        return None;
    }
    Some([bytes[0], bytes[1], bytes[2]])
}

/// The registration request body (same fields and order as Kotlin).
pub fn registration_body(public_key: &str, tos: &str) -> String {
    json!({
        "key": public_key,
        "install_id": "",
        "fcm_token": "",
        "tos": tos,
        "model": "PC",
        "type": "Android",
        "locale": "en_US",
    })
    .to_string()
}

/// Cloudflare's registration answer → the account (our own `private_key` goes in).
pub fn parse_registration(text: &str, private_key: &str) -> Result<WarpAccount, String> {
    let v: Value = serde_json::from_str(text).map_err(|e| format!("Cloudflare WARP response is not JSON: {e}"))?;
    let config = v.get("config");
    let peer = config
        .and_then(|c| c.get("peers"))
        .and_then(Value::as_array)
        .and_then(|p| p.first())
        .ok_or_else(|| "Cloudflare WARP response missing peer".to_string())?;
    let peer_public_key =
        non_blank(peer.get("public_key")).ok_or_else(|| "Cloudflare WARP response missing peer public key".to_string())?;
    let addresses = config
        .and_then(|c| c.get("interface"))
        .and_then(|i| i.get("addresses"))
        .ok_or_else(|| "Cloudflare WARP response missing interface addresses".to_string())?;
    let ipv4 = non_blank(addresses.get("v4")).ok_or_else(|| "Cloudflare WARP response missing IPv4 address".to_string())?;
    Ok(WarpAccount {
        private_key: private_key.to_string(),
        peer_public_key,
        ipv4,
        ipv6: non_blank(addresses.get("v6")),
        reserved: reserved_from_client_id(config.and_then(|c| c.get("client_id")).and_then(Value::as_str)),
    })
}

/// The AmneziaWG INI for a PLAIN-WireGuard server (WARP): only junk cover packets and I1; the
/// handshake stays vanilla (S1 = S2 = 0, H1..H4 = 1..4) so Cloudflare accepts it.
pub fn build_ini(account: &WarpAccount) -> String {
    let address = match &account.ipv6 {
        Some(v6) => format!("{}/32, {}/128", account.ipv4, v6),
        None => format!("{}/32", account.ipv4),
    };
    let mut lines: Vec<String> = vec![
        "[Interface]".to_string(),
        format!("PrivateKey = {}", account.private_key),
        format!("Address = {}", address),
        "DNS = 1.1.1.1".to_string(),
        "MTU = 1280".to_string(),
    ];
    if let Some([b0, b1, b2]) = account.reserved {
        lines.push(format!("Reserved = {}, {}, {}", b0, b1, b2));
    }
    for knob in ["Jc = 4", "Jmin = 40", "Jmax = 70", "S1 = 0", "S2 = 0", "H1 = 1", "H2 = 2", "H3 = 3", "H4 = 4"] {
        lines.push(knob.to_string());
    }
    lines.push(format!("I1 = {}", WARP_I1));
    lines.push(String::new());
    lines.push("[Peer]".to_string());
    lines.push(format!("PublicKey = {}", account.peer_public_key));
    lines.push(format!("AllowedIPs = {}", ALLOWED_IPS));
    lines.push(format!("Endpoint = {}", ENDPOINT));
    lines.push("PersistentKeepalive = 25".to_string());
    let mut ini = lines.join("\n");
    ini.push('\n');
    ini
}

/// `yyyy-MM-ddTHH:mm:ss.000Z` (UTC) — the `tos` field.
pub fn iso_utc(epoch_ms: i64) -> String {
    let (y, m, d) = civil_from_days(epoch_ms.div_euclid(86_400_000));
    let sec = epoch_ms.rem_euclid(86_400_000) / 1000;
    format!("{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.000Z", y, m, d, sec / 3600, (sec % 3600) / 60, sec % 60)
}

pub fn tos_now() -> String {
    let ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
    iso_utc(ms)
}

fn random_index(len: usize) -> usize {
    let mut b = [0u8; 8];
    if len == 0 || getrandom::getrandom(&mut b).is_err() {
        return 0;
    }
    (u64::from_le_bytes(b) % len as u64) as usize
}

/// The generators in order; the INI of the first usable account, `None` when none answered.
pub fn generate_via_service(
    http: &dyn WarpHttp,
    pick: &mut dyn FnMut(usize) -> usize,
    log: &mut dyn FnMut(&str),
) -> Option<String> {
    for url in SERVICE_ENDPOINTS {
        let request = HttpRequest {
            url: url.to_string(),
            headers: vec![("User-Agent".to_string(), BROWSER_USER_AGENT.to_string())],
            proxy: None,
        };
        let account = match http.get(&request, SERVICE_TIMEOUT_MS) {
            Some(response) if response.ok() => parse_service_account(&response.body, |n| pick(n)),
            Some(_) => None,
            None => {
                log(format!("WARP generator {} failed: no answer", url).as_str());
                None
            }
        };
        if let Some(account) = account {
            log(format!("WARP: config obtained from {}", url).as_str());
            return Some(build_ini(&account));
        }
    }
    None
}

/// Direct registration: the official API, then our mirror. A 2xx answer is final (parsed or error).
pub fn register_with_cloudflare(http: &dyn WarpHttp, keys: &KeyPair, tos: &str) -> Result<String, String> {
    let body = registration_body(&keys.public_key, tos);
    let mut error = String::new();
    for (url, timeout_ms) in [(REG_URL, REG_TIMEOUT_MS), (REG_MIRROR_URL, REG_MIRROR_TIMEOUT_MS)] {
        let request = HttpRequest {
            url: url.to_string(),
            headers: vec![
                ("Content-Type".to_string(), "application/json".to_string()),
                ("User-Agent".to_string(), CF_USER_AGENT.to_string()),
                ("CF-Client-Version".to_string(), CF_CLIENT_VERSION.to_string()),
            ],
            proxy: None,
        };
        match http.post_json(&request, &body, timeout_ms) {
            Some(response) if response.ok() => {
                return parse_registration(&response.body, &keys.private_key).map(|account| build_ini(&account));
            }
            Some(response) => error = format!("Cloudflare WARP registration failed (HTTP {})", response.status),
            None => error = format!("Cloudflare WARP registration failed: no answer from {}", url),
        }
    }
    Err(error)
}

/// A ready AmneziaWG INI (Kotlin `generate`): generators first, then direct registration;
/// `official_first` (plain-WireGuard WARP) tries direct registration before the generators.
/// Needs internet once — the caller caches the result. `Err` only when every path failed.
pub fn generate(http: &dyn WarpHttp, official_first: bool, log: &mut dyn FnMut(&str)) -> Result<String, String> {
    generate_with(http, official_first, log, &mut random_index, &mut generate_key_pair, &tos_now())
}

/// [`generate`] with the randomness and the clock passed in (tests).
pub fn generate_with(
    http: &dyn WarpHttp,
    official_first: bool,
    log: &mut dyn FnMut(&str),
    pick: &mut dyn FnMut(usize) -> usize,
    new_keys: &mut dyn FnMut() -> Result<KeyPair, String>,
    tos: &str,
) -> Result<String, String> {
    if official_first {
        match new_keys().and_then(|keys| register_with_cloudflare(http, &keys, tos)) {
            Ok(ini) => return Ok(ini),
            Err(e) => log(format!("WARP: official Cloudflare registration failed: {}", e).as_str()),
        }
    }
    if let Some(ini) = generate_via_service(http, pick, log) {
        return Ok(ini);
    }
    log("WARP: all generators failed — falling back to direct Cloudflare registration");
    let keys = new_keys()?;
    register_with_cloudflare(http, &keys, tos)
}
