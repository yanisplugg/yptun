use crate::model::secret_eq;
use crate::{
    App, lock_unpoison, log_event,
    model::{
        self, ClientGroup, PasswordEntry, RoutingRule, is_expired, now, random_password,
        random_token,
    },
    protocol, read_unpoison,
};
use axum::{
    Json, Router,
    body::Body,
    extract::{ConnectInfo, DefaultBodyLimit, Extension, Path, Query, State},
    http::{HeaderMap, Request, StatusCode, header},
    middleware::{self, Next},
    response::{Html, IntoResponse, Redirect, Response},
    routing::{get, post, put},
};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    io::Read,
    net::{Ipv4Addr, SocketAddr},
    sync::{
        Arc, LazyLock, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Clone)]
struct WebState {
    app: Arc<App>,
}

#[derive(Deserialize)]
struct LoginRequest {
    user: String,
    pass: String,
}

#[derive(Deserialize)]
struct CreateClientRequest {
    name: String,
    days: i64,
    #[serde(default)]
    workers: Option<u32>,
    #[serde(default)]
    password: Option<String>,
    #[serde(default)]
    hash: String,
    #[serde(default)]
    vk_token: bool,
    #[serde(default, alias = "dtls_port")]
    peer_port: Option<u16>,
    #[serde(default)]
    local_port: Option<u16>,
    #[serde(default)]
    group_id: Option<String>,
}

fn validate_workers(workers: Option<u32>) -> Result<Option<u32>, &'static str> {
    match workers {
        None | Some(0) => Ok(None),
        Some(w) if (3..=100).contains(&w) => Ok(Some(w)),
        _ => Err("workers must be 0 (default) or between 3 and 100"),
    }
}

#[derive(Deserialize)]
struct ExtendClientRequest {
    days: i64,
}

#[derive(Deserialize)]
struct SettingsRequest {
    #[serde(default)]
    main_password: Option<String>,
    #[serde(default)]
    dns_provider: Option<String>,
    #[serde(default)]
    dns_primary: Option<String>,
    #[serde(default)]
    dns_secondary: Option<String>,
    #[serde(default)]
    auto_restart_interval_hours: Option<u8>,
    #[serde(default)]
    vk_token: Option<String>,
    #[serde(default)]
    vk_hashes: Option<String>,
}

#[derive(Clone, Copy)]
struct DnsProvider {
    id: &'static str,
    primary: &'static str,
    secondary: &'static str,
}

const DNS_PROVIDERS: [DnsProvider; 13] = [
    DnsProvider {
        id: "yandex",
        primary: "77.88.8.8",
        secondary: "77.88.8.1",
    },
    DnsProvider {
        id: "cloudflare",
        primary: "1.1.1.1",
        secondary: "1.0.0.1",
    },
    DnsProvider {
        id: "google",
        primary: "8.8.8.8",
        secondary: "8.8.4.4",
    },
    DnsProvider {
        id: "adguard",
        primary: "94.140.14.14",
        secondary: "94.140.15.14",
    },
    DnsProvider {
        id: "xbox",
        primary: "111.88.96.54",
        secondary: "111.88.96.55",
    },
    DnsProvider {
        id: "comms",
        primary: "83.220.169.155",
        secondary: "212.109.195.93",
    },
    DnsProvider {
        id: "geohide",
        primary: "193.233.112.67",
        secondary: "193.233.112.88",
    },
    DnsProvider {
        id: "quad9",
        primary: "9.9.9.9",
        secondary: "149.112.112.112",
    },
    DnsProvider {
        id: "opendns",
        primary: "208.67.222.222",
        secondary: "208.67.220.220",
    },
    DnsProvider {
        id: "rostelecom",
        primary: "95.189.32.74",
        secondary: "195.208.5.1",
    },
    DnsProvider {
        id: "nextdns",
        primary: "45.90.28.181",
        secondary: "45.90.30.181",
    },
    DnsProvider {
        id: "bizone",
        primary: "195.208.6.1",
        secondary: "195.208.7.1",
    },
    DnsProvider {
        id: "nsdi",
        primary: "195.208.4.1",
        secondary: "195.208.5.1",
    },
];

fn dns_provider_by_id(id: &str) -> Option<DnsProvider> {
    DNS_PROVIDERS
        .iter()
        .copied()
        .find(|provider| provider.id == id)
}

pub(crate) fn dns_provider_is_known(id: &str) -> bool {
    dns_provider_by_id(id).is_some()
}

pub(crate) fn dns_provider_id(dns: &str) -> Option<&'static str> {
    DNS_PROVIDERS
        .iter()
        .find(|provider| dns == format!("{},{}", provider.primary, provider.secondary))
        .map(|provider| provider.id)
}

fn provider_dns(provider: DnsProvider) -> String {
    format!("{},{}", provider.primary, provider.secondary)
}

pub(crate) fn provider_dns_by_id(id: &str) -> Option<String> {
    dns_provider_by_id(id).map(provider_dns)
}

fn manual_dns(primary: Option<&str>, secondary: Option<&str>) -> Result<String, &'static str> {
    let Some(primary) = primary else {
        return Err("primary DNS is required");
    };
    let primary = primary.trim();
    let secondary = secondary.unwrap_or_default().trim();
    if primary.is_empty() {
        return Err("primary DNS is required");
    }
    if primary.parse::<Ipv4Addr>().is_err()
        || (!secondary.is_empty() && secondary.parse::<Ipv4Addr>().is_err())
    {
        return Err("DNS must be a valid IPv4 address");
    }
    Ok(if secondary.is_empty() {
        primary.to_owned()
    } else {
        format!("{primary},{secondary}")
    })
}

pub(crate) fn deploy_dns_profile_or_yandex(existing_dns: &str) -> String {
    let addresses: Vec<_> = existing_dns
        .split(',')
        .map(str::trim)
        .filter(|address| !address.is_empty())
        .collect();
    if addresses
        .iter()
        .any(|&a| a == "95.182.120.241" || a == "37.230.192.51")
    {
        return "193.233.112.67,193.233.112.88".to_owned();
    }
    if addresses
        .iter()
        .any(|&a| a == "111.88.96.50" || a == "111.88.96.51")
    {
        return "111.88.96.54,111.88.96.55".to_owned();
    }
    let first = addresses.first().copied();
    let selected = DNS_PROVIDERS
        .iter()
        .filter_map(|provider| {
            let matches = addresses
                .iter()
                .filter(|address| **address == provider.primary || **address == provider.secondary)
                .count();
            (matches > 0).then_some((
                provider,
                (
                    matches,
                    usize::from(first == Some(provider.primary)),
                    usize::from(first == Some(provider.secondary)),
                ),
            ))
        })
        .max_by_key(|(_, score)| *score)
        .map(|(provider, _)| *provider)
        .unwrap_or(DNS_PROVIDERS[0]);
    provider_dns(selected)
}

const WEB_BODY_LIMIT_BYTES: usize = 16 * 1024;
const MAX_LOGIN_FIELD_BYTES: usize = 256;
const MAX_CLIENT_NAME_BYTES: usize = 128;
const MAX_PROXY_PROFILE_NAME_BYTES: usize = 64;

fn validate_text_field(
    value: &str,
    max_bytes: usize,
    message: &'static str,
) -> Result<(), &'static str> {
    if value.len() > max_bytes || value.chars().any(char::is_control) {
        return Err(message);
    }
    Ok(())
}

fn validate_login_request(request: &LoginRequest) -> Result<(), &'static str> {
    validate_text_field(
        &request.user,
        MAX_LOGIN_FIELD_BYTES,
        "login fields are too long or invalid",
    )?;
    validate_text_field(
        &request.pass,
        MAX_LOGIN_FIELD_BYTES,
        "login fields are too long or invalid",
    )
}

fn validate_client_name(name: &str) -> Result<(), &'static str> {
    validate_text_field(
        name,
        MAX_CLIENT_NAME_BYTES,
        "client name is too long or invalid",
    )
}

fn validate_proxy_profile_name(name: &str) -> Result<(), &'static str> {
    validate_text_field(
        name,
        MAX_PROXY_PROFILE_NAME_BYTES,
        "proxy profile name is too long or invalid",
    )
}

#[derive(Serialize)]
struct ClientInfo {
    password: String,
    down: i64,
    up: i64,
    expires: i64,
    active: bool,
    active_sessions: usize,
    vk_hash: String,
    ports: String,
    ip: String,
    name: String,
    vk_hashes: String,
    vk_token: bool,
    peer_port: u16,
    local_port: u16,
    #[serde(default)]
    group_id: String,
    #[serde(default)]
    routing_rule_id: String,
    #[serde(default)]
    link: String,
    #[serde(default)]
    sub_uuid: String,
    #[serde(default)]
    workers: Option<u32>,
}

fn url_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for b in input.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            _ => {
                use std::fmt::Write;
                let _ = write!(out, "%{:02X}", b);
            }
        }
    }
    out
}

pub const CSQTT_CIPHER_KEY_SEED: &str = "csqtt-chiper-key";

pub fn encrypt_csqtt_link(payload: &str) -> String {
    use aws_lc_rs::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use base64::Engine;
    use hmac::{Hmac, KeyInit, Mac};
    use sha2::{Digest, Sha256};

    let key_hash = Sha256::digest(CSQTT_CIPHER_KEY_SEED.as_bytes());
    let unbound = match UnboundKey::new(&AES_256_GCM, &key_hash) {
        Ok(k) => k,
        Err(_) => {
            let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
            return format!("csqtt://{b64}");
        }
    };
    let key = LessSafeKey::new(unbound);

    let mut iv = [0u8; 12];
    type HmacSha256 = Hmac<Sha256>;
    if let Ok(mut mac) = HmacSha256::new_from_slice(&key_hash) {
        mac.update(payload.as_bytes());
        let result = mac.finalize().into_bytes();
        iv.copy_from_slice(&result[..12]);
    } else {
        use rand::RngCore;
        rand::rngs::OsRng.fill_bytes(&mut iv);
    }
    let nonce = match Nonce::try_assume_unique_for_key(&iv) {
        Ok(n) => n,
        Err(_) => {
            let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
            return format!("csqtt://{b64}");
        }
    };

    let mut in_out = payload.as_bytes().to_vec();
    if key
        .seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
        .is_err()
    {
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
        return format!("csqtt://{b64}");
    }

    let mut combined = Vec::with_capacity(12 + in_out.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&in_out);

    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&combined);
    format!("csqtt://{b64}")
}

#[allow(dead_code)]
pub fn decrypt_csqtt_link(raw_link: &str) -> Option<String> {
    use aws_lc_rs::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use base64::Engine;
    use sha2::{Digest, Sha256};

    let payload = raw_link.trim().strip_prefix("csqtt://")?.trim();
    let clean = payload.replace(['\n', '\r'], "");
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(&clean)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(&clean))
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(&clean))
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(&clean))
        .ok()?;

    if bytes.len() >= 28 {
        let key_hash = Sha256::digest(CSQTT_CIPHER_KEY_SEED.as_bytes());
        if let Ok(unbound) = UnboundKey::new(&AES_256_GCM, &key_hash) {
            let key = LessSafeKey::new(unbound);
            let mut iv = [0u8; 12];
            iv.copy_from_slice(&bytes[..12]);
            if let Ok(nonce) = Nonce::try_assume_unique_for_key(&iv) {
                let mut ciphertext = bytes[12..].to_vec();
                if let Ok(decrypted) = key.open_in_place(nonce, Aad::empty(), &mut ciphertext)
                    && let Ok(s) = String::from_utf8(decrypted.to_vec())
                {
                    return Some(s);
                }
            }
        }
    }

    None
}

fn make_client_link(
    host: &str,
    port: u16,
    password: &str,
    vk_hashes: &str,
    vk_token: bool,
    shared_token: &str,
) -> String {
    let mut payload = format!(
        "connect?v=2&host={}&peer={}",
        url_encode(host),
        if port == 0 { 46000 } else { port },
    );
    if !password.is_empty() {
        payload.push_str("&password=");
        payload.push_str(&url_encode(password));
    }
    let hashes: Vec<&str> = vk_hashes
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    if !hashes.is_empty() {
        payload.push_str("&hashes=");
        payload.push_str(
            &hashes
                .iter()
                .map(|h| url_encode(h))
                .collect::<Vec<_>>()
                .join("+"),
        );
    } else if vk_token && !shared_token.is_empty() {
        payload.push_str("&token=");
        payload.push_str(&url_encode(shared_token));
    }
    encrypt_csqtt_link(&payload)
}

#[inline]
fn make_main_link(host: &str, port: u16, db: &model::Database) -> String {
    let vk_hashes = if db.main_vk_hashes.is_empty() {
        &db.vk_hashes
    } else {
        &db.main_vk_hashes
    };
    make_client_link(
        host,
        port,
        &db.main_password,
        vk_hashes,
        db.main_vk_token_enabled,
        &db.vk_access_token,
    )
}

#[inline]
fn make_entry_link(
    host: &str,
    port: u16,
    password: &str,
    entry: &model::PasswordEntry,
    shared_token: &str,
) -> String {
    make_client_link(
        host,
        port,
        password,
        &entry.vk_hashes,
        entry.vk_token_enabled,
        shared_token,
    )
}

const CAESAR_SHIFT: u8 = 47;
pub(crate) const MAX_WEB_SESSIONS: usize = 256;
pub(crate) const MAX_LOGIN_LIMITS: usize = 4_096;
const MAX_WEB_CONNECTIONS: usize = 64;

fn caesar_decode(value: &str) -> String {
    if let Some(rest) = value.strip_prefix("c1:")
        && let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(rest)
    {
        let decoded: Vec<u8> = bytes.iter().map(|b| b.wrapping_sub(CAESAR_SHIFT)).collect();
        if let Ok(s) = String::from_utf8(decoded) {
            return s;
        }
    }
    value.to_owned()
}

fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

fn cookie_tokens(headers: &HeaderMap) -> Vec<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|value| value.split(';'))
        .map(str::trim)
        .filter_map(|part| part.strip_prefix("csqtt_session=").map(str::to_owned))
        .collect()
}

fn authorized(app: &App, headers: &HeaderMap) -> bool {
    if cookie_tokens(headers).iter().any(|token| {
        app.web_sessions
            .get(&hash_token(token))
            .map(|expiry| *expiry > now())
            .unwrap_or(false)
    }) {
        return true;
    }

    if let Some(key) = headers
        .get("x-api-key")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        && !key.is_empty()
    {
        if secret_eq(key, &app.web_pass) || secret_eq(key, &app.startup_main_password) {
            return true;
        }
        if let Ok(db) = app.db.try_read()
            && secret_eq(key, &db.main_password)
        {
            return true;
        }
        if app
            .web_sessions
            .get(&hash_token(key))
            .is_some_and(|expiry| *expiry > now())
        {
            return true;
        }
    }

    if let Some(auth) = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        && let Some(token) = auth
            .strip_prefix("Bearer ")
            .or_else(|| auth.strip_prefix("bearer "))
    {
        let token = token.trim();
        if !token.is_empty() {
            if secret_eq(token, &app.web_pass) || secret_eq(token, &app.startup_main_password) {
                return true;
            }
            if let Ok(db) = app.db.try_read()
                && secret_eq(token, &db.main_password)
            {
                return true;
            }
            if app
                .web_sessions
                .get(&hash_token(token))
                .is_some_and(|expiry| *expiry > now())
            {
                return true;
            }
        }
    }

    false
}

async fn auth_middleware(
    State(state): State<WebState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    if authorized(&state.app, request.headers()) {
        return next.run(request).await;
    }
    let has_auth_header = request.headers().contains_key("x-api-key")
        || request
            .headers()
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.starts_with("Bearer ") || s.starts_with("bearer "))
            .unwrap_or(false)
        || !cookie_tokens(request.headers()).is_empty();

    if !has_auth_header {
        if let Some(token) = request.extensions().get::<Arc<StealthAbortController>>() {
            token.abort();
        }
        return stealth_abort_response();
    }

    let mut response = StatusCode::UNAUTHORIZED.into_response();
    if !cookie_tokens(request.headers()).is_empty() {
        response.headers_mut().insert(
            header::SET_COOKIE,
            expired_session_cookie().parse().unwrap(),
        );
    }
    response
}

fn expired_session_cookie() -> &'static str {
    "csqtt_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure"
}

const STEALTH_ABORT_HEADER: &str = "x-csqtt-stealth-rst";

fn stealth_abort_response() -> Response {
    (
        StatusCode::OK,
        [
            (header::HeaderName::from_static(STEALTH_ABORT_HEADER), "1"),
            (header::CONNECTION, "close"),
        ],
        "",
    )
        .into_response()
}

fn render_root(state: &WebState, headers: &HeaderMap) -> Response {
    if authorized(&state.app, headers) {
        let mut response = Html(PANEL_HTML).into_response();
        response.headers_mut().insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
        );
        return response;
    }
    let mut response = Html(LOGIN_HTML).into_response();
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
    );
    if !cookie_tokens(headers).is_empty() {
        response.headers_mut().insert(
            header::SET_COOKIE,
            expired_session_cookie().parse().unwrap(),
        );
    }
    response
}

async fn panel_root(State(state): State<WebState>, headers: HeaderMap) -> Response {
    render_root(&state, &headers)
}

async fn favicon() -> Response {
    pwa_response(PWA_ICON_SVG, "image/svg+xml", "public, max-age=604800")
}

async fn fallback_reject(token: Option<Extension<Arc<StealthAbortController>>>) -> Response {
    if let Some(Extension(controller)) = token {
        controller.abort();
    }
    stealth_abort_response()
}

fn pwa_response(
    body: impl IntoResponse,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response {
    let mut response = body.into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static(content_type),
    );
    headers.insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static(cache_control),
    );
    response
}

fn pwa_binary_response(
    body: Vec<u8>,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response {
    let mut response = body.into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static(content_type),
    );
    headers.insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static(cache_control),
    );
    response
}

async fn root_with_secret(
    State(state): State<WebState>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    if authorized(&state.app, &headers) {
        let secret = state.app.web_secret_path.trim().trim_matches('/');
        let target = if secret.is_empty() {
            "/".to_string()
        } else {
            format!("/{}/", secret)
        };
        return Redirect::temporary(&target).into_response();
    }
    fallback_reject(token).await
}

async fn pwa_manifest(State(state): State<WebState>) -> Response {
    let secret = state.app.web_secret_path.trim().trim_matches('/');
    if secret.is_empty() {
        pwa_response(
            PWA_MANIFEST,
            "application/manifest+json; charset=utf-8",
            "no-cache",
        )
    } else {
        let prefix = format!("/{}", secret);
        let base = format!("{}/", prefix);
        let manifest = PWA_MANIFEST
            .replace("\"id\": \"/\"", &format!("\"id\": \"{}\"", base))
            .replace(
                "\"start_url\": \"/\"",
                &format!("\"start_url\": \"{}\"", base),
            )
            .replace("\"scope\": \"/\"", &format!("\"scope\": \"{}\"", base))
            .replace(
                "\"/pwa-icon-192.png\"",
                &format!("\"{}/pwa-icon-192.png\"", prefix),
            )
            .replace(
                "\"/pwa-icon-512.png\"",
                &format!("\"{}/pwa-icon-512.png\"", prefix),
            )
            .replace("\"/pwa-icon.svg\"", &format!("\"{}/pwa-icon.svg\"", prefix));
        pwa_response(
            manifest,
            "application/manifest+json; charset=utf-8",
            "no-cache",
        )
    }
}

async fn pwa_service_worker() -> Response {
    let mut response = pwa_response(
        PWA_SERVICE_WORKER,
        "application/javascript; charset=utf-8",
        "no-cache",
    );
    response.headers_mut().insert(
        header::HeaderName::from_static("service-worker-allowed"),
        header::HeaderValue::from_static("/"),
    );
    response
}

async fn pwa_icon() -> Response {
    pwa_response(PWA_ICON_SVG, "image/svg+xml", "public, max-age=604800")
}

async fn pwa_icon_192() -> Response {
    pwa_binary_response(
        PWA_ICON_192_PNG.clone(),
        "image/png",
        "public, max-age=604800",
    )
}

async fn pwa_icon_512() -> Response {
    pwa_binary_response(
        PWA_ICON_512_PNG.clone(),
        "image/png",
        "public, max-age=604800",
    )
}

async fn qrcode_js() -> Response {
    pwa_response(
        QRCODE_JS,
        "application/javascript; charset=utf-8",
        "public, max-age=604800, immutable",
    )
}

async fn login(
    State(state): State<WebState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<LoginRequest>,
) -> Response {
    if let Err(message) = validate_login_request(&request) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let source = addr.ip().to_string();
    if let Ok(forwarded) = std::env::var("CSQTT_TRUST_XFF")
        && (forwarded == "1" || forwarded.eq_ignore_ascii_case("true"))
        && let Some(forwarded) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.split(',').next())
            .map(str::trim)
            .filter(|s| !s.is_empty())
    {
        log_event(
            &state.app,
            "INFO",
            "AUTH",
            &format!("Login attempt from {source} (XFF: {forwarded})"),
        );
    }

    let current = now();
    if let Some(value) = state.app.login_limits.get(&source)
        && value.1 > current
        && value.0 >= 3
    {
        return (StatusCode::TOO_MANY_REQUESTS, "too many attempts").into_response();
    }

    let user = caesar_decode(&request.user);
    let pass = caesar_decode(&request.pass);
    let valid = secret_eq(&user, &state.app.web_user) & secret_eq(&pass, &state.app.web_pass);

    if !valid {
        if !state.record_failed_login(source, current) {
            return (StatusCode::TOO_MANY_REQUESTS, "too many login sources").into_response();
        }
        return (StatusCode::UNAUTHORIZED, "invalid credentials").into_response();
    }

    state.app.login_limits.remove(&source);
    let token = random_token(32);
    if !state.web_session_insert(token.clone()) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            "too many active web sessions",
        )
            .into_response();
    }
    let cookie =
        format!("csqtt_session={token}; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax; Secure");
    let mut response = (StatusCode::OK, [(header::SET_COOKIE, cookie)], "ok").into_response();
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
    );
    response
}

impl WebState {
    fn record_failed_login(&self, source: String, current: i64) -> bool {
        let _admission = lock_unpoison(&self.app.web_auth_admission);
        self.app.login_limits.retain(|_, value| value.1 > current);
        if !self.app.login_limits.contains_key(&source)
            && self.app.login_limits.len() >= MAX_LOGIN_LIMITS
        {
            return false;
        }
        self.app
            .login_limits
            .entry(source)
            .and_modify(|value| {
                if value.1 <= current {
                    *value = (1, current + 180);
                } else {
                    value.0 = value.0.saturating_add(1);
                }
            })
            .or_insert((1, current + 180));
        true
    }

    fn web_session_insert(&self, token: String) -> bool {
        let _admission = lock_unpoison(&self.app.web_auth_admission);
        let current = now();
        self.app.web_sessions.retain(|_, expiry| *expiry > current);
        if self.app.web_sessions.len() >= MAX_WEB_SESSIONS {
            return false;
        }
        let key = hash_token(&token);
        self.app.web_sessions.insert(key, current + 86400);
        true
    }
}

async fn logout(State(state): State<WebState>, headers: HeaderMap) -> Response {
    let tokens = cookie_tokens(&headers);
    if !tokens.is_empty() {
        for token in &tokens {
            state.app.web_sessions.remove(&hash_token(token));
        }
    }
    (
        StatusCode::OK,
        [(header::SET_COOKIE, expired_session_cookie())],
        "ok",
    )
        .into_response()
}

async fn logout_all(State(state): State<WebState>) -> Response {
    state.app.web_sessions.clear();
    (
        StatusCode::OK,
        [(header::SET_COOKIE, expired_session_cookie())],
        "ok",
    )
        .into_response()
}

async fn refresh_credentials_report(state: &WebState) -> Result<(), String> {
    match crate::protocol::refresh_credentials(&state.app).await {
        Ok(()) => Ok(()),
        Err(error) => {
            let message = format!("{error:#}");
            log_event(
                &state.app,
                "ERROR",
                "AUTH",
                &format!("Change saved but credential reload failed: {message}"),
            );
            Err(message)
        }
    }
}

async fn wait_for_persistence(state: &WebState, revision: u64) -> Result<(), Response> {
    match tokio::time::timeout(
        Duration::from_secs(15),
        state.app.db_persistence.wait(revision),
    )
    .await
    {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => {
            eprintln!("[WEB] Failed to persist database mutation: {error:#}");
            Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                "failed to persist change",
            )
                .into_response())
        }
        Err(_) => Err((
            StatusCode::GATEWAY_TIMEOUT,
            "database persistence is taking too long; refresh before retrying",
        )
            .into_response()),
    }
}

fn proc_kib(text: &str, field: &str) -> Option<u64> {
    text.lines()
        .find(|line| line.starts_with(field))?
        .split_whitespace()
        .nth(1)?
        .parse()
        .ok()
}

fn read_proc_text<'a>(path: &str, buffer: &'a mut [u8]) -> Option<&'a str> {
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.read(buffer).ok()?;
    std::str::from_utf8(&buffer[..len]).ok()
}

#[derive(Clone, Copy, Default)]
struct ProcessMemory {
    rss: u64,
    peak: u64,
    anonymous: u64,
    file: u64,
    shared: u64,
    swap: u64,
}

fn process_memory(text: &str) -> ProcessMemory {
    ProcessMemory {
        rss: proc_kib(text, "VmRSS:").unwrap_or(0),
        peak: proc_kib(text, "VmHWM:").unwrap_or(0),
        anonymous: proc_kib(text, "RssAnon:").unwrap_or(0),
        file: proc_kib(text, "RssFile:").unwrap_or(0),
        shared: proc_kib(text, "RssShmem:").unwrap_or(0),
        swap: proc_kib(text, "VmSwap:").unwrap_or(0),
    }
}

#[derive(Clone, Copy, Default)]
struct ProcessMemoryRollup {
    pss: u64,
    pss_anon: u64,
    anonymous: u64,
    file: u64,
    shmem: u64,
    private: u64,
    shared: u64,
    swap_pss: u64,
}

fn process_memory_rollup(text: &str) -> ProcessMemoryRollup {
    let private_clean = proc_kib(text, "Private_Clean:").unwrap_or(0);
    let private_dirty = proc_kib(text, "Private_Dirty:").unwrap_or(0);
    let shared_clean = proc_kib(text, "Shared_Clean:").unwrap_or(0);
    let shared_dirty = proc_kib(text, "Shared_Dirty:").unwrap_or(0);
    ProcessMemoryRollup {
        pss: proc_kib(text, "Pss:").unwrap_or(0),
        pss_anon: proc_kib(text, "Pss_Anon:").unwrap_or(0),
        anonymous: proc_kib(text, "Anonymous:").unwrap_or(0),
        file: proc_kib(text, "Pss_File:").unwrap_or(0),
        shmem: proc_kib(text, "Pss_Shmem:").unwrap_or(0),
        private: private_clean.saturating_add(private_dirty),
        shared: shared_clean.saturating_add(shared_dirty),
        swap_pss: proc_kib(text, "SwapPss:").unwrap_or(0),
    }
}

#[derive(Default)]
struct SystemStatsCache {
    sampled_at: Option<Instant>,
    process_memory: ProcessMemory,
    process_rollup: ProcessMemoryRollup,
    total_ram: u64,
}

static SYSTEM_STATS_CACHE: LazyLock<Mutex<SystemStatsCache>> =
    LazyLock::new(|| Mutex::new(SystemStatsCache::default()));
const SYSTEM_STATS_CACHE_TTL: Duration = Duration::from_secs(60);

fn cached_system_stats() -> (ProcessMemory, ProcessMemoryRollup, u64) {
    let mut cache = lock_unpoison(&SYSTEM_STATS_CACHE);
    let refresh = cache
        .sampled_at
        .is_none_or(|sampled_at| sampled_at.elapsed() >= SYSTEM_STATS_CACHE_TTL);
    if refresh {
        let mut buffer = [0_u8; 4096];
        cache.process_memory = read_proc_text("/proc/self/status", &mut buffer)
            .map(process_memory)
            .unwrap_or_default();
        cache.process_rollup = read_proc_text("/proc/self/smaps_rollup", &mut buffer)
            .map(process_memory_rollup)
            .unwrap_or_default();
        cache.total_ram = read_proc_text("/proc/meminfo", &mut buffer)
            .and_then(|text| proc_kib(text, "MemTotal:"))
            .unwrap_or(0);
        cache.sampled_at = Some(Instant::now());
    }
    (cache.process_memory, cache.process_rollup, cache.total_ram)
}

fn format_memory_kib(kib: u64) -> String {
    let (value, unit) = if kib >= 1024 * 1024 {
        (kib as f64 / (1024.0 * 1024.0), "GB")
    } else {
        (kib as f64 / 1024.0, "MB")
    };
    let mut value = format!("{value:.2}");
    while value.ends_with('0') {
        value.pop();
    }
    if value.ends_with('.') {
        value.pop();
    }
    format!("{value} {unit}")
}

fn format_total_memory_kib(kib: u64) -> String {
    if kib >= 1024 * 1024 {
        let mut value = format!("{:.1}", kib as f64 / (1024.0 * 1024.0));
        if value.ends_with(".0") {
            value.truncate(value.len() - 2);
        }
        format!("{value} GB")
    } else {
        format!("{} MB", (kib + 512) / 1024)
    }
}

async fn stats(State(state): State<WebState>, _headers: HeaderMap) -> impl IntoResponse {
    let (process_memory, process_rollup, total_ram) = cached_system_stats();
    let io = *read_unpoison(&protocol::GLOBAL_IO_COUNTERS);

    let cpu_total = state.app.cpu_percent.load(Ordering::Relaxed);
    let cpu_cores = state.app.cpu_cores.load(Ordering::Relaxed).max(1);
    let cpu_capacity = cpu_cores.saturating_mul(100);
    let cpu_normalized = cpu_total
        .saturating_mul(100)
        .checked_div(cpu_capacity)
        .unwrap_or(0)
        .min(100);
    let log_ring_bytes = crate::lock_unpoison(&state.app.logs)
        .iter()
        .map(String::len)
        .sum::<usize>();
    let db = state.app.db.read().await;
    let (
        local_proxy_active,
        local_proxy_tcp_sessions,
        local_proxy_udp_flows,
        local_proxy_tcp_peak,
        local_proxy_udp_peak,
        local_proxy_tcp_total,
        local_proxy_udp_total,
    ) = {
        let route = state.app.proxy_route.read().await;
        let active = route.as_ref().is_some_and(|route| route.is_alive());
        let proxy_stats = route
            .as_ref()
            .map(|route| route.diagnostic_snapshot())
            .unwrap_or_default();
        (
            active,
            proxy_stats.tcp_active,
            proxy_stats.udp_active,
            proxy_stats.tcp_peak,
            proxy_stats.udp_peak,
            proxy_stats.tcp_total,
            proxy_stats.udp_total,
        )
    };
    Json(serde_json::json!({
        "ram": format!("{} / {}", format_memory_kib(process_memory.rss), format_total_memory_kib(total_ram)),
        "ram_used": format_memory_kib(process_memory.rss),
        "ram_total": format_total_memory_kib(total_ram),
        "ram_peak": format_memory_kib(process_memory.peak),
        "ram_anonymous": format_memory_kib(process_memory.anonymous),
        "ram_file": format_memory_kib(process_memory.file),
        "ram_shared": format_memory_kib(process_memory.shared),
        "ram_swap": format_memory_kib(process_memory.swap),
        "ram_pss": format_memory_kib(process_rollup.pss),
        "ram_private": format_memory_kib(process_rollup.private),
        "ram_shared_rollup": format_memory_kib(process_rollup.shared),
        "ram_anon_rollup": format_memory_kib(process_rollup.pss_anon),
        "ram_anonymous_rollup": format_memory_kib(process_rollup.anonymous),
        "ram_file_rollup": format_memory_kib(process_rollup.file),
        "ram_shmem_rollup": format_memory_kib(process_rollup.shmem),
        "ram_swap_pss": format_memory_kib(process_rollup.swap_pss),
        "ram_used_kib": process_memory.rss,
        "ram_peak_kib": process_memory.peak,
        "ram_anonymous_kib": process_memory.anonymous,
        "ram_file_kib": process_memory.file,
        "ram_shared_kib": process_memory.shared,
        "ram_swap_kib": process_memory.swap,
        "ram_pss_kib": process_rollup.pss,
        "ram_private_kib": process_rollup.private,
        "ram_shared_rollup_kib": process_rollup.shared,
        "ram_anon_rollup_kib": process_rollup.pss_anon,
        "ram_anonymous_rollup_kib": process_rollup.anonymous,
        "ram_file_rollup_kib": process_rollup.file,
        "ram_shmem_rollup_kib": process_rollup.shmem,
        "ram_swap_pss_kib": process_rollup.swap_pss,
        "cpu_total": cpu_total,
        "cpu_capacity": cpu_capacity,
        "cpu_normalized": cpu_normalized,
        "status": "Active",
        "peer_port": state.app.listen.port(),
        "public_ip": state.app.public_ip.read().await.clone(),
        "uptime": now().saturating_sub(state.app.started),
        "active": state.app.sessions.len(),
        "total": state.app.total_connections.load(Ordering::Relaxed),
        "up": state.app.bytes_from_client.load(Ordering::Relaxed),
        "down": state.app.bytes_to_client.load(Ordering::Relaxed),
        "udp_rx_packets": io.udp_rx_packets,
        "udp_rx_bytes": io.udp_rx_bytes,
        "udp_rx_errors": io.udp_rx_errors,
        "udp_tx_packets": io.udp_tx_packets,
        "udp_tx_bytes": io.udp_tx_bytes,
        "udp_tx_errors": io.udp_tx_errors,
        "passwords": db.passwords.len(),
        "transport": "RTP/SRTP",
        "tunnel": "userspace-tun",
        "allocator": crate::allocator_name(),
        "local_proxy_enabled": !db.local_proxy.active_profile_id.is_empty(),
        "local_proxy_port": db.local_proxy.active_profile().map(|p| p.port).unwrap_or(0),
        "local_proxy_active": local_proxy_active,
        "local_proxy_port_listening": state.app.proxy_port_listening.load(Ordering::Acquire),
        "local_proxy_health_error": crate::read_unpoison(&state.app.proxy_health_error).clone(),
        "local_proxy_tcp_sessions": local_proxy_tcp_sessions,
        "local_proxy_udp_flows": local_proxy_udp_flows,
        "local_proxy_tcp_peak": local_proxy_tcp_peak,
        "local_proxy_udp_peak": local_proxy_udp_peak,
        "local_proxy_tcp_total": local_proxy_tcp_total,
        "local_proxy_udp_total": local_proxy_udp_total,
        "hot_sessions": protocol::ACTIVE_SESSIONS_GAUGE.load(Ordering::Relaxed),
        "hot_session_capacity": protocol::HOT_SESSION_CAPACITY_GAUGE.load(Ordering::Relaxed),
        "public_session_capacity": state.app.sessions.capacity(),
        "mem_engine_epochs": protocol::epoch_snapshot_len(),
        "mem_password_epochs": state.app.password_epochs.len(),
        "mem_password_epoch_capacity": state.app.password_epochs.capacity(),
        "mem_derived_keys": state.app.derived_keys.len(),
        "mem_web_sessions": state.app.web_sessions.len(),
        "mem_web_session_capacity": MAX_WEB_SESSIONS,
        "mem_login_limits": state.app.login_limits.len(),
        "mem_login_limit_capacity": MAX_LOGIN_LIMITS,
        "mem_log_ring": crate::lock_unpoison(&state.app.logs).len(),
        "mem_log_bytes": log_ring_bytes,
        "mem_dpi_ring": protocol::dpi_ring_len(),
        "mem_stream_repairs": protocol::STREAM_REPAIRS_GAUGE.load(Ordering::Relaxed),
        "mem_stream_inventory": protocol::STREAM_INVENTORY_GAUGE.load(Ordering::Relaxed),
        "memory_trim_count": state.app.memory_trim_count.load(Ordering::Relaxed),
        "memory_trim_last_unix": state.app.memory_trim_last_unix.load(Ordering::Relaxed)
    }))
}

async fn memory_trim(State(state): State<WebState>) -> Response {
    match crate::trim_memory(&state.app).await {
        Ok(allocator_collected) => Json(serde_json::json!({
            "compacted": true,
            "allocator_collected": allocator_collected,
            "allocator_collection_scope": "not-supported-by-system-allocator",
            "trim_count": state.app.memory_trim_count.load(Ordering::Relaxed),
            "trimmed_at": state.app.memory_trim_last_unix.load(Ordering::Relaxed),
        }))
        .into_response(),
        Err(error) => {
            log_event(
                &state.app,
                "WARNING",
                "MEMORY",
                &format!("Memory trim skipped: {error:#}"),
            );
            (
                StatusCode::SERVICE_UNAVAILABLE,
                "memory trim is temporarily unavailable",
            )
                .into_response()
        }
    }
}

async fn settings_get(State(state): State<WebState>) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let dns = db.dns.clone();
    let mut parts = dns.splitn(2, ',');
    let primary = parts.next().unwrap_or("77.88.8.8").trim().to_owned();
    let secondary = parts.next().unwrap_or("77.88.8.1").trim().to_owned();
    let provider = if db.dns_provider == "custom" || dns_provider_by_id(&db.dns_provider).is_some()
    {
        db.dns_provider.as_str()
    } else {
        dns_provider_id(&dns).unwrap_or("custom")
    };
    let vk_hashes = if !db.vk_hashes.is_empty() {
        db.vk_hashes.clone()
    } else {
        db.passwords
            .values()
            .find(|p| !p.vk_hashes.is_empty())
            .map(|p| p.vk_hashes.clone())
            .unwrap_or_default()
    };
    Json(serde_json::json!({
        "main_password": db.main_password,
        "dns_provider": provider,
        "dns_primary": primary,
        "dns_secondary": secondary,
        "auto_restart_interval_hours": db.auto_restart_interval_hours(),
        "vk_token": db.vk_access_token,
        "vk_hashes": vk_hashes,
        "peer_port": state.app.listen.port(),
        "restart_required": db.main_password != state.app.startup_main_password
    }))
}

async fn settings_post(
    State(state): State<WebState>,
    Json(request): Json<SettingsRequest>,
) -> Response {
    if let Some(password) = request.main_password.as_ref()
        && !password.is_empty()
        && password.len() > 128
    {
        return (
            StatusCode::BAD_REQUEST,
            "password length must be 1..128 or empty",
        )
            .into_response();
    }
    let dns = match request.dns_provider.as_deref() {
        Some("custom") | None
            if request.dns_primary.is_some() || request.dns_secondary.is_some() =>
        {
            match manual_dns(
                request.dns_primary.as_deref(),
                request.dns_secondary.as_deref(),
            ) {
                Ok(dns) => Some((dns, "custom".to_owned())),
                Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
            }
        }
        Some("custom") => {
            return (StatusCode::BAD_REQUEST, "primary DNS is required").into_response();
        }
        Some(provider_id) => {
            let Some(provider) = dns_provider_by_id(provider_id) else {
                return (StatusCode::BAD_REQUEST, "unknown DNS provider").into_response();
            };
            Some((provider_dns(provider), provider_id.to_owned()))
        }
        None => None,
    };
    if let Some(hours) = request.auto_restart_interval_hours
        && !matches!(hours, 0 | 12 | 24 | 48 | 72)
    {
        return (
            StatusCode::BAD_REQUEST,
            "auto restart interval must be 0, 12, 24, 48, or 72 hours",
        )
            .into_response();
    }
    let requested_vk_access_token = match request.vk_token {
        Some(token) => match normalize_vk_access_token(&token) {
            Ok(token) => Some(token),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let requested_vk_hashes = match request.vk_hashes {
        Some(raw) => match normalize_client_vk_hashes(&raw) {
            Ok(hashes) => Some(hashes),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let requested_auto_restart_interval = request.auto_restart_interval_hours;
    let (revision, retired_main_password, applied_dns) = {
        let mut db = state.app.db.write().await;
        let retired_main_password = if let Some(password) = request.main_password {
            if db.passwords.contains_key(&password) {
                return (
                    StatusCode::BAD_REQUEST,
                    "Пароль совпадает с паролем клиента",
                )
                    .into_response();
            }
            (db.main_password != password)
                .then(|| std::mem::replace(&mut db.main_password, password))
        } else {
            None
        };
        let applied_dns = dns
            .as_ref()
            .and_then(|(dns, _)| (db.dns != *dns).then(|| dns.clone()));
        if let Some((dns, provider)) = dns.as_ref() {
            db.dns.clone_from(dns);
            db.dns_provider.clone_from(provider);
        }
        if let Some(hours) = requested_auto_restart_interval {
            db.set_auto_restart_interval_hours(hours);
        }
        if let Some(token) = requested_vk_access_token {
            db.vk_access_token = token;
        }
        if let Some(hashes) = requested_vk_hashes {
            db.vk_hashes = hashes;
        }
        (
            state.app.db_persistence.submit(db.clone()),
            retired_main_password,
            applied_dns,
        )
    };
    match state.app.db_persistence.wait(revision).await {
        Ok(()) => {}
        Err(error) => {
            eprintln!("[SETTINGS] Failed to persist settings: {error}");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                "failed to persist settings",
            )
                .into_response();
        }
    }
    if let Some(hours) = requested_auto_restart_interval {
        state.app.auto_restart_interval_tx.send_replace(hours);
    }
    let main_password_changed = retired_main_password.is_some();
    if let Some(password) = retired_main_password
        && !password.is_empty()
    {
        state.app.derived_keys.remove(&password);
    }
    if main_password_changed && let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }
    if let Some(dns) = applied_dns {
        *state.app.dns.write().await = dns;
        match crate::protocol::broadcast_tunnel_configuration(&state.app).await {
            Ok(sent) => log_event(
                &state.app,
                "INFO",
                "DNS",
                &format!("DNS configuration applied to {sent} active paths"),
            ),
            Err(error) => {
                let warning = format!("saved but DNS apply failed: {error:#}");
                return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved")
                    .into_response();
            }
        }
    }
    let restart_required = {
        let db = state.app.db.read().await;
        db.main_password != state.app.startup_main_password
    };
    Json(serde_json::json!({ "restart_required": restart_required })).into_response()
}

fn is_valid_hostname(name: &str) -> bool {
    if name.is_empty() || name.len() > 253 {
        return false;
    }
    name.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
            && !label.starts_with('-')
            && !label.ends_with('-')
    })
}

fn parse_host_header(host: &str) -> Option<(String, Option<String>)> {
    let host = host.trim();
    if host.is_empty() || host.len() > 255 {
        return None;
    }
    let (name, port) = if let Some(rest) = host.strip_prefix('[') {
        let (name, rest) = rest.split_once(']')?;
        let port = match rest.strip_prefix(':') {
            Some(p) => Some(p.to_string()),
            None if rest.is_empty() => None,
            None => return None,
        };
        (name.to_string(), port)
    } else if let Some((name, port)) = host.rsplit_once(':') {
        (name.to_string(), Some(port.to_string()))
    } else {
        (host.to_string(), None)
    };

    if let Some(port) = port.as_deref()
        && (port.is_empty() || port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()))
    {
        return None;
    }

    if name.parse::<std::net::IpAddr>().is_err() && !is_valid_hostname(&name) {
        return None;
    }
    Some((name, port))
}

async fn resolve_client_host(headers: &HeaderMap, state: &WebState) -> String {
    let host_header = headers
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .and_then(parse_host_header)
        .map(|(name, _)| name)
        .unwrap_or_default();

    let is_local_or_unspecified = host_header.is_empty()
        || host_header == "0.0.0.0"
        || host_header == "127.0.0.1"
        || host_header == "localhost"
        || host_header == "::1";

    if !is_local_or_unspecified {
        return host_header.to_string();
    }

    let public_ip = state.app.public_ip.read().await.clone();
    if !public_ip.is_empty() && public_ip != "0.0.0.0" && public_ip != "127.0.0.1" {
        return public_ip;
    }

    let listen_ip = state.app.listen.ip().to_string();
    if listen_ip != "0.0.0.0" && listen_ip != "127.0.0.1" {
        return listen_ip;
    }

    if !host_header.is_empty() {
        return host_header.to_string();
    }

    "0.0.0.0".to_string()
}

#[derive(Deserialize, Default)]
struct ClientListQuery {
    #[serde(default)]
    group_id: Option<String>,
    #[serde(default)]
    active: Option<bool>,
    #[serde(default)]
    search: Option<String>,
}

fn count_active_sessions(
    sessions: &dashmap::DashMap<u64, Arc<crate::protocol::Session>>,
    password: &str,
) -> usize {
    if password.is_empty() {
        return 0;
    }
    let mut count = 0;
    for entry in sessions.iter() {
        if entry.value().password == password {
            count += 1;
        }
    }
    count
}

async fn clients_get(
    State(state): State<WebState>,
    headers: HeaderMap,
    Query(query): Query<ClientListQuery>,
) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    let mut list: Vec<ClientInfo> = db
        .passwords
        .iter()
        .map(|(password, entry)| {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            ClientInfo {
                password: password.clone(),
                down: entry.down_bytes,
                up: entry.up_bytes,
                expires: entry.expires_at,
                active: !entry.is_deactivated && !is_expired(entry),
                active_sessions: count_active_sessions(&state.app.sessions, password),
                vk_hash: entry.vk_hash.clone(),
                ports: entry.ports.clone(),
                ip: if entry.ip.is_empty() {
                    "10.66.67.3".to_string()
                } else {
                    entry.ip.clone()
                },
                name: entry.name.clone(),
                vk_hashes: entry.vk_hashes.clone(),
                vk_token: entry.vk_token_enabled,
                peer_port: client_peer_port,
                local_port: entry.local_port,
                group_id: entry.group_id.clone(),
                routing_rule_id: entry.routing_rule_id.clone(),
                link: make_entry_link(
                    &host,
                    client_peer_port,
                    password,
                    entry,
                    &db.vk_access_token,
                ),
                sub_uuid: entry.sub_uuid.clone(),
                workers: entry.workers,
            }
        })
        .collect();
    if !db.main_password.is_empty() {
        let mut session_up = 0;
        let mut session_down = 0;
        for s in state.app.sessions.iter() {
            let sess = s.value();
            if secret_eq(&sess.password, &db.main_password) {
                session_up += sess.up_bytes.load(Ordering::Relaxed) as i64;
                session_down += sess.down_bytes.load(Ordering::Relaxed) as i64;
            }
        }

        let main_peer_port = state.app.listen.port();
        list.push(ClientInfo {
            password: db.main_password.clone(),
            down: db.main_down_bytes + session_down,
            up: db.main_up_bytes + session_up,
            expires: 0,
            active: true,
            active_sessions: count_active_sessions(&state.app.sessions, &db.main_password),
            vk_hash: String::new(),
            ports: String::new(),
            ip: "10.66.67.2".to_string(),
            name: "Владелец".to_string(),
            vk_hashes: if db.main_vk_hashes.is_empty() {
                db.vk_hashes.clone()
            } else {
                db.main_vk_hashes.clone()
            },
            vk_token: db.main_vk_token_enabled,
            peer_port: main_peer_port,
            local_port: 0,
            group_id: String::new(),
            routing_rule_id: db.main_routing_rule_id.clone(),
            link: make_main_link(&host, main_peer_port, &db),
            sub_uuid: db.main_sub_uuid.clone(),
            workers: db.main_workers,
        });
    }

    if let Some(ref gid) = query.group_id {
        let g = gid.trim();
        if g == "root" || g == "ungrouped" {
            list.retain(|c| c.group_id.is_empty());
        } else {
            list.retain(|c| c.group_id == g);
        }
    }
    if let Some(act) = query.active {
        list.retain(|c| c.active == act);
    }
    if let Some(ref s) = query.search {
        let q = s.trim().to_lowercase();
        if !q.is_empty() {
            list.retain(|c| {
                c.name.to_lowercase().contains(&q) || c.password.to_lowercase().contains(&q)
            });
        }
    }

    Json(list)
}

async fn group_clients_get(
    State(state): State<WebState>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    let db = state.app.db.read().await;
    if !db.groups.contains_key(&id) {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "group not found" })),
        )
            .into_response();
    }
    let host = resolve_client_host(&headers, &state).await;
    let effective_vk_token = db
        .groups
        .get(&id)
        .and_then(|g| g.vk_token.as_deref())
        .unwrap_or(&db.vk_access_token);
    let list: Vec<ClientInfo> = db
        .passwords
        .iter()
        .filter(|(_, entry)| entry.group_id == id)
        .map(|(password, entry)| {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            ClientInfo {
                password: password.clone(),
                down: entry.down_bytes,
                up: entry.up_bytes,
                expires: entry.expires_at,
                active: !entry.is_deactivated && !is_expired(entry),
                active_sessions: count_active_sessions(&state.app.sessions, password),
                vk_hash: entry.vk_hash.clone(),
                ports: entry.ports.clone(),
                ip: if entry.ip.is_empty() {
                    "10.66.67.3".to_string()
                } else {
                    entry.ip.clone()
                },
                name: entry.name.clone(),
                vk_hashes: entry.vk_hashes.clone(),
                vk_token: entry.vk_token_enabled,
                peer_port: client_peer_port,
                local_port: entry.local_port,
                group_id: entry.group_id.clone(),
                routing_rule_id: entry.routing_rule_id.clone(),
                link: make_entry_link(&host, client_peer_port, password, entry, effective_vk_token),
                sub_uuid: entry.sub_uuid.clone(),
                workers: entry.workers,
            }
        })
        .collect();
    Json(list).into_response()
}

fn normalize_client_vk_hashes(raw: &str) -> Result<String, &'static str> {
    let value = raw.trim();
    if value.is_empty() {
        return Ok(String::new());
    }
    if value.len() > 1024 {
        return Err("VK hashes are too long");
    }
    if value.chars().any(char::is_whitespace) {
        return Err("VK hashes must be comma-separated without spaces");
    }
    let hashes: Vec<_> = value.split(',').collect();
    if hashes.len() > 6 || hashes.iter().any(|hash| hash.len() < 16) {
        return Err("provide 1 to 6 valid VK hashes");
    }
    Ok(hashes.join(","))
}

fn normalize_vk_access_token(raw: &str) -> Result<String, &'static str> {
    let token = raw.trim();
    if token.is_empty() {
        return Ok(String::new());
    }
    if token.len() > 4096 || token.chars().any(char::is_whitespace) {
        return Err("VK token is too long or contains whitespace");
    }
    Ok(token.to_owned())
}

async fn clients_create(
    State(state): State<WebState>,
    headers: HeaderMap,
    Json(request): Json<CreateClientRequest>,
) -> Response {
    if let Err(message) = validate_client_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    if request.days < 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 0..3650").into_response();
    }
    let vk_hashes = match normalize_client_vk_hashes(&request.hash) {
        Ok(value) => value,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if request.vk_token && !vk_hashes.is_empty() {
        return (StatusCode::BAD_REQUEST, "choose VK hashes or VK token").into_response();
    }

    let default_peer_port = state.app.listen.port();
    let peer_port = request.peer_port.unwrap_or(default_peer_port);
    let local_port = request.local_port.unwrap_or(0);
    let ports = format!("{},0,{}", peer_port, local_port);
    let group_id = request.group_id.unwrap_or_default();
    let validated_workers = match validate_workers(request.workers) {
        Ok(w) => w,
        Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
    };

    let host = resolve_client_host(&headers, &state).await;

    let (password, expires, revision, link, sub_uuid) = {
        let mut db = state.app.db.write().await;
        if request.vk_token && db.vk_access_token.is_empty() {
            return (StatusCode::BAD_REQUEST, "configure a VK token first").into_response();
        }
        db.passwords.retain(|_, entry| !is_expired(entry));

        let client_name = if request.name.trim().is_empty() {
            model::generate_unique_client_name(model::existing_client_names_in_scope(
                &db, &group_id, None,
            ))
        } else {
            let trimmed = request.name.trim().to_string();
            if model::is_client_name_taken(
                model::existing_client_names_in_scope(&db, &group_id, None),
                &trimmed,
            ) {
                return (
                    StatusCode::BAD_REQUEST,
                    "Участник с таким именем уже существует",
                )
                    .into_response();
            }
            trimmed
        };

        let password = if let Some(ref custom_pw) = request.password {
            let pw = custom_pw.trim();
            if pw.is_empty() || pw.len() > 128 {
                return (
                    StatusCode::BAD_REQUEST,
                    "password must be 1..128 characters",
                )
                    .into_response();
            }
            if db.passwords.contains_key(pw) || pw == db.main_password {
                return (StatusCode::BAD_REQUEST, "password already exists").into_response();
            }
            pw.to_string()
        } else {
            let candidate = (0..64)
                .map(|_| random_password())
                .find(|c| !db.passwords.contains_key(c) && c != &db.main_password);
            let Some(p) = candidate else {
                return StatusCode::INTERNAL_SERVER_ERROR.into_response();
            };
            p
        };

        let expires = if request.days == 0 {
            0
        } else {
            now().saturating_add(request.days.saturating_mul(86400))
        };
        let sub_uuid = model::random_uuid_v4();
        let ip = model::get_next_ip(&db).unwrap_or_default();
        db.passwords.insert(
            password.clone(),
            PasswordEntry {
                ip,
                expires_at: expires,
                down_bytes: 0,
                up_bytes: 0,
                vk_hash: vk_hashes.clone(),
                ports,
                is_deactivated: false,
                name: client_name,
                vk_hashes: vk_hashes.clone(),
                vk_token_enabled: request.vk_token,
                dtls_port: peer_port,
                wg_port: 0,
                local_port,
                group_id: group_id.clone(),
                routing_rule_id: String::new(),
                sub_uuid: sub_uuid.clone(),
                workers: validated_workers,
            },
        );
        let link = make_client_link(
            &host,
            peer_port,
            &password,
            &vk_hashes,
            request.vk_token,
            &db.vk_access_token,
        );
        let revision = state.app.db_persistence.submit(db.clone());
        (password, expires, revision, link, sub_uuid)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let reload_error = refresh_credentials_report(&state).await.err();

    Json(serde_json::json!({
        "password": password,
        "expires": expires,
        "peer_port": peer_port,
        "local_port": local_port,
        "vk_hashes": vk_hashes,
        "vk_token": request.vk_token,
        "group_id": group_id,
        "workers": validated_workers.unwrap_or(0),
        "reload_error": reload_error,
        "link": link,
        "sub_uuid": sub_uuid,
    }))
    .into_response()
}

async fn client_update(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<CreateClientRequest>,
) -> Response {
    if let Err(message) = validate_client_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    if request.days < 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 0..3650").into_response();
    }
    let vk_hashes = match normalize_client_vk_hashes(&request.hash) {
        Ok(value) => value,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if request.vk_token && !vk_hashes.is_empty() {
        return (StatusCode::BAD_REQUEST, "choose VK hashes or VK token").into_response();
    }
    let validated_workers = match validate_workers(request.workers) {
        Ok(w) => w,
        Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
    };

    let revision = {
        let mut db = state.app.db.write().await;
        if request.vk_token && db.vk_access_token.is_empty() {
            return (StatusCode::BAD_REQUEST, "configure a VK token first").into_response();
        }
        if secret_eq(&password, &db.main_password) {
            db.main_vk_hashes = vk_hashes;
            db.main_vk_token_enabled = request.vk_token;
            db.main_workers = validated_workers;
            state.app.db_persistence.submit(db.clone())
        } else {
            let current_group_id = match db.passwords.get(&password) {
                Some(e) => e.group_id.clone(),
                None => return StatusCode::NOT_FOUND.into_response(),
            };
            let target_group_id = request.group_id.as_deref().unwrap_or(&current_group_id);
            let scope_names =
                model::existing_client_names_in_scope(&db, target_group_id, Some(&password));
            let client_name = if request.name.trim().is_empty() {
                model::generate_unique_client_name(&scope_names)
            } else {
                let trimmed = request.name.trim().to_string();
                if model::is_client_name_taken(&scope_names, &trimmed) {
                    return (
                        StatusCode::BAD_REQUEST,
                        "Участник с таким именем уже существует",
                    )
                        .into_response();
                }
                trimmed
            };

            let entry = db.passwords.get_mut(&password).unwrap();

            let default_peer_port = state.app.listen.port();
            let fallback_port = if entry.dtls_port == 0 {
                default_peer_port
            } else {
                entry.dtls_port
            };
            let peer_port = request.peer_port.unwrap_or(fallback_port);
            let local_port = request.local_port.unwrap_or(0);

            entry.name = client_name;
            entry.expires_at = if request.days == 0 {
                0
            } else {
                now().saturating_add(request.days.saturating_mul(86400))
            };
            entry.vk_hash = vk_hashes.clone();
            entry.vk_hashes = vk_hashes;
            entry.vk_token_enabled = request.vk_token;
            entry.dtls_port = peer_port;
            entry.wg_port = 0;
            entry.local_port = local_port;
            entry.ports = format!("{},0,{}", peer_port, local_port);
            if let Some(ref g_id) = request.group_id {
                entry.group_id = g_id.clone();
            }
            entry.workers = validated_workers;
            state.app.db_persistence.submit(db.clone())
        }
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }
    StatusCode::OK.into_response()
}

async fn client_toggle(State(state): State<WebState>, Path(password): Path<String>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        let Some(entry) = db.passwords.get_mut(&password) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        entry.is_deactivated = !entry.is_deactivated;
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }

    crate::protocol::drop_password_sessions(&state.app, &password);
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }

    StatusCode::OK.into_response()
}

async fn client_unbind(State(state): State<WebState>, Path(password): Path<String>) -> Response {
    crate::protocol::drop_password_sessions(&state.app, &password);
    crate::protocol::forget_idle_password_epoch(&state.app, &password);
    StatusCode::OK.into_response()
}

async fn client_delete(State(state): State<WebState>, Path(password): Path<String>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        if db.passwords.remove(&password).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        };
        state.app.derived_keys.remove(&password);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }

    crate::protocol::drop_password_sessions(&state.app, &password);
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }

    StatusCode::OK.into_response()
}

async fn client_get(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(password): Path<String>,
) -> Response {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    if secret_eq(&password, &db.main_password) {
        let mut session_up = 0;
        let mut session_down = 0;
        let mut active_sessions = 0;
        for entry in state.app.sessions.iter() {
            let sess = entry.value();
            if secret_eq(&sess.password, &db.main_password) {
                active_sessions += 1;
                session_up += sess.up_bytes.load(Ordering::Relaxed) as i64;
                session_down += sess.down_bytes.load(Ordering::Relaxed) as i64;
            }
        }

        let main_peer_port = state.app.listen.port();
        let info = ClientInfo {
            password: db.main_password.clone(),
            down: db.main_down_bytes + session_down,
            up: db.main_up_bytes + session_up,
            expires: 0,
            active: true,
            active_sessions,
            vk_hash: String::new(),
            ports: String::new(),
            ip: "10.66.67.2".to_string(),
            name: "Главный пароль".to_string(),
            vk_hashes: if db.main_vk_hashes.is_empty() {
                db.vk_hashes.clone()
            } else {
                db.main_vk_hashes.clone()
            },
            vk_token: db.main_vk_token_enabled,
            peer_port: main_peer_port,
            local_port: 0,
            group_id: String::new(),
            routing_rule_id: db.main_routing_rule_id.clone(),
            link: make_main_link(&host, main_peer_port, &db),
            sub_uuid: db.main_sub_uuid.clone(),
            workers: db.main_workers,
        };
        return Json(info).into_response();
    }

    if let Some(entry) = db.passwords.get(&password) {
        let active_sessions = count_active_sessions(&state.app.sessions, &password);
        let client_peer_port = if entry.dtls_port == 0 {
            state.app.listen.port()
        } else {
            entry.dtls_port
        };
        let info = ClientInfo {
            password: password.clone(),
            down: entry.down_bytes,
            up: entry.up_bytes,
            expires: entry.expires_at,
            active: !entry.is_deactivated && !is_expired(entry),
            active_sessions,
            vk_hash: entry.vk_hash.clone(),
            ports: entry.ports.clone(),
            ip: if entry.ip.is_empty() {
                "10.66.67.3".to_string()
            } else {
                entry.ip.clone()
            },
            name: entry.name.clone(),
            vk_hashes: entry.vk_hashes.clone(),
            vk_token: entry.vk_token_enabled,
            peer_port: client_peer_port,
            local_port: entry.local_port,
            group_id: entry.group_id.clone(),
            routing_rule_id: entry.routing_rule_id.clone(),
            link: make_entry_link(
                &host,
                client_peer_port,
                &password,
                entry,
                &db.vk_access_token,
            ),
            sub_uuid: entry.sub_uuid.clone(),
            workers: entry.workers,
        };
        return Json(info).into_response();
    }

    (StatusCode::NOT_FOUND, "client not found").into_response()
}

async fn client_reset_traffic(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let result = {
        let mut db = state.app.db.write().await;
        if secret_eq(&password, &db.main_password) {
            db.main_up_bytes = 0;
            db.main_down_bytes = 0;
            for s in state.app.sessions.iter() {
                if s.value().password == password {
                    s.value().up_bytes.store(0, Ordering::Relaxed);
                    s.value().down_bytes.store(0, Ordering::Relaxed);
                }
            }
            let revision = state.app.db_persistence.submit(db.clone());
            Some(revision)
        } else if let Some(entry) = db.passwords.get_mut(&password) {
            entry.up_bytes = 0;
            entry.down_bytes = 0;
            for s in state.app.sessions.iter() {
                if s.value().password == password {
                    s.value().up_bytes.store(0, Ordering::Relaxed);
                    s.value().down_bytes.store(0, Ordering::Relaxed);
                }
            }
            let revision = state.app.db_persistence.submit(db.clone());
            Some(revision)
        } else {
            None
        }
    };

    let Some(revision) = result else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    Json(serde_json::json!({ "ok": true })).into_response()
}

async fn client_extend(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<ExtendClientRequest>,
) -> Response {
    if request.days <= 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 1..3650").into_response();
    }
    let current = now();
    let result = {
        let mut db = state.app.db.write().await;
        if let Some(entry) = db.passwords.get_mut(&password) {
            let base = if entry.expires_at > current {
                entry.expires_at
            } else {
                current
            };
            let new_expires = base.saturating_add(request.days.saturating_mul(86400));
            entry.expires_at = new_expires;
            let revision = state.app.db_persistence.submit(db.clone());
            Some((new_expires, revision))
        } else {
            None
        }
    };

    let Some((expires, revision)) = result else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({ "ok": true, "expires": expires })).into_response()
}

#[derive(Serialize)]
struct ClientLinkItem {
    name: String,
    password: String,
    link: String,
    expires: i64,
    peer_port: u16,
    group_id: String,
    #[serde(default)]
    sub_uuid: String,
}

async fn api_links_get(State(state): State<WebState>, headers: HeaderMap) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;
    let mut list: Vec<ClientLinkItem> = db
        .passwords
        .iter()
        .map(|(password, entry)| {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            ClientLinkItem {
                name: entry.name.clone(),
                password: password.clone(),
                link: make_entry_link(
                    &host,
                    client_peer_port,
                    password,
                    entry,
                    &db.vk_access_token,
                ),
                expires: entry.expires_at,
                peer_port: client_peer_port,
                group_id: entry.group_id.clone(),
                sub_uuid: entry.sub_uuid.clone(),
            }
        })
        .collect();
    if !db.main_password.is_empty() {
        let main_peer_port = state.app.listen.port();
        list.push(ClientLinkItem {
            name: "Владелец".to_owned(),
            password: db.main_password.clone(),
            link: make_main_link(&host, main_peer_port, &db),
            expires: 0,
            peer_port: main_peer_port,
            group_id: String::new(),
            sub_uuid: db.main_sub_uuid.clone(),
        });
    }
    Json(list)
}

#[derive(Deserialize)]
struct BatchVkRequest {
    #[serde(default)]
    vk_token: Option<String>,
    #[serde(default)]
    vk_hashes: Option<String>,
    #[serde(default)]
    apply_to_all_clients: Option<bool>,
}

async fn api_links_batch_vk(
    State(state): State<WebState>,
    Json(request): Json<BatchVkRequest>,
) -> Response {
    let token = match request.vk_token {
        Some(t) => match normalize_vk_access_token(&t) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let hashes = match request.vk_hashes {
        Some(h) => match normalize_client_vk_hashes(&h) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let apply_all = request.apply_to_all_clients.unwrap_or(true);
    let mut updated_count = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        if let Some(ref t) = token {
            db.vk_access_token = t.clone();
        }
        if let Some(ref h) = hashes {
            db.vk_hashes = h.clone();
        }
        if apply_all {
            if let Some(ref h) = hashes {
                db.main_vk_hashes = h.clone();
            }
            if let Some(ref t) = token {
                db.main_vk_token_enabled = !t.is_empty();
            }
            for entry in db.passwords.values_mut() {
                if let Some(ref h) = hashes {
                    entry.vk_hashes = h.clone();
                }
                if let Some(ref t) = token {
                    entry.vk_token_enabled = !t.is_empty();
                }
                updated_count += 1;
            }
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({
        "ok": true,
        "updated_clients": updated_count
    }))
    .into_response()
}

#[derive(Deserialize)]
struct ClientVkUpdateRequest {
    #[serde(default)]
    vk_token: Option<bool>,
    #[serde(default)]
    vk_hashes: Option<String>,
}

async fn api_client_vk_update(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<ClientVkUpdateRequest>,
) -> Response {
    let hashes = match request.vk_hashes {
        Some(h) => match normalize_client_vk_hashes(&h) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let revision = {
        let mut db = state.app.db.write().await;
        if secret_eq(&password, &db.main_password) {
            if let Some(h) = hashes {
                db.main_vk_hashes = h;
            }
            if let Some(token_enabled) = request.vk_token {
                db.main_vk_token_enabled = token_enabled;
            }
            state.app.db_persistence.submit(db.clone())
        } else {
            let Some(entry) = db.passwords.get_mut(&password) else {
                return (StatusCode::NOT_FOUND, "client not found").into_response();
            };
            if let Some(h) = hashes {
                entry.vk_hashes = h;
            }
            if let Some(token_enabled) = request.vk_token {
                entry.vk_token_enabled = token_enabled;
            }
            state.app.db_persistence.submit(db.clone())
        }
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({ "ok": true })).into_response()
}

#[derive(Deserialize)]
struct ApiDnsRequest {
    #[serde(default)]
    dns_provider: Option<String>,
    #[serde(default)]
    dns_primary: Option<String>,
    #[serde(default)]
    dns_secondary: Option<String>,
}

async fn api_settings_dns(
    State(state): State<WebState>,
    Json(request): Json<ApiDnsRequest>,
) -> Response {
    let dns = match request.dns_provider.as_deref() {
        Some("custom") | None
            if request.dns_primary.is_some() || request.dns_secondary.is_some() =>
        {
            match manual_dns(
                request.dns_primary.as_deref(),
                request.dns_secondary.as_deref(),
            ) {
                Ok(dns) => (dns, "custom".to_owned()),
                Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
            }
        }
        Some("custom") => {
            return (StatusCode::BAD_REQUEST, "primary DNS is required").into_response();
        }
        Some(provider_id) => {
            let Some(provider) = dns_provider_by_id(provider_id) else {
                return (StatusCode::BAD_REQUEST, "unknown DNS provider").into_response();
            };
            (provider_dns(provider), provider_id.to_owned())
        }
        None => return (StatusCode::BAD_REQUEST, "missing DNS parameters").into_response(),
    };
    let (revision, applied_dns) = {
        let mut db = state.app.db.write().await;
        let applied_dns = (db.dns != dns.0).then(|| dns.0.clone());
        db.dns = dns.0;
        db.dns_provider = dns.1;
        (state.app.db_persistence.submit(db.clone()), applied_dns)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(new_dns) = applied_dns {
        *state.app.dns.write().await = new_dns;
        let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    }
    Json(serde_json::json!({ "ok": true })).into_response()
}

#[derive(Deserialize)]
struct RestartIntervalRequest {
    interval_hours: u8,
}

async fn api_settings_restart_interval(
    State(state): State<WebState>,
    Json(request): Json<RestartIntervalRequest>,
) -> Response {
    if !matches!(request.interval_hours, 0 | 12 | 24 | 48 | 72) {
        return (
            StatusCode::BAD_REQUEST,
            "auto restart interval must be 0, 12, 24, 48, or 72 hours",
        )
            .into_response();
    }
    let revision = {
        let mut db = state.app.db.write().await;
        db.set_auto_restart_interval_hours(request.interval_hours);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    state
        .app
        .auto_restart_interval_tx
        .send_replace(request.interval_hours);
    Json(serde_json::json!({ "ok": true, "interval_hours": request.interval_hours }))
        .into_response()
}

#[derive(Deserialize)]
struct MasterPasswordRequest {
    password: String,
}

async fn api_settings_master_password(
    State(state): State<WebState>,
    Json(request): Json<MasterPasswordRequest>,
) -> Response {
    if !request.password.is_empty() && request.password.len() > 128 {
        return (
            StatusCode::BAD_REQUEST,
            "password length must be 1..128 or empty",
        )
            .into_response();
    }
    let (revision, retired_password) = {
        let mut db = state.app.db.write().await;
        if !request.password.is_empty() && db.passwords.contains_key(&request.password) {
            return (
                StatusCode::BAD_REQUEST,
                "Пароль совпадает с паролем клиента",
            )
                .into_response();
        }
        let retired = (db.main_password != request.password)
            .then(|| std::mem::replace(&mut db.main_password, request.password));
        (state.app.db_persistence.submit(db.clone()), retired)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(p) = retired_password
        && !p.is_empty()
    {
        state.app.derived_keys.remove(&p);
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({ "ok": true })).into_response()
}

#[derive(Serialize, Deserialize, Clone)]
struct ServerExportData {
    version: u32,
    exported_at: i64,
    main_password: String,
    dns: String,
    dns_provider: String,
    auto_restart_interval_hours: Option<u8>,
    vk_access_token: String,
    vk_hashes: String,
    groups: std::collections::BTreeMap<String, ClientGroup>,
    routing_rules: std::collections::BTreeMap<String, RoutingRule>,
    clients: Vec<ExportClientEntry>,
}

#[derive(Serialize, Deserialize, Clone)]
struct ExportClientEntry {
    password: String,
    name: String,
    expires_at: i64,
    down_bytes: i64,
    up_bytes: i64,
    #[serde(default)]
    vk_hash: String,
    #[serde(default)]
    vk_hashes: String,
    #[serde(default)]
    vk_token_enabled: bool,
    #[serde(default)]
    ports: String,
    #[serde(default)]
    dtls_port: u16,
    #[serde(default)]
    local_port: u16,
    #[serde(default)]
    group_id: String,
    #[serde(default)]
    routing_rule_id: String,
    #[serde(default)]
    is_deactivated: bool,
    #[serde(default)]
    sub_uuid: String,
    #[serde(default)]
    workers: Option<u32>,
    #[serde(default)]
    ip: String,
}

async fn api_export_get(State(state): State<WebState>) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let clients: Vec<ExportClientEntry> = db
        .passwords
        .iter()
        .map(|(pwd, entry)| ExportClientEntry {
            password: pwd.clone(),
            name: entry.name.clone(),
            expires_at: entry.expires_at,
            down_bytes: entry.down_bytes,
            up_bytes: entry.up_bytes,
            vk_hash: entry.vk_hash.clone(),
            vk_hashes: entry.vk_hashes.clone(),
            vk_token_enabled: entry.vk_token_enabled,
            ports: entry.ports.clone(),
            dtls_port: entry.dtls_port,
            local_port: entry.local_port,
            group_id: entry.group_id.clone(),
            routing_rule_id: entry.routing_rule_id.clone(),
            is_deactivated: entry.is_deactivated,
            sub_uuid: entry.sub_uuid.clone(),
            workers: entry.workers,
            ip: entry.ip.clone(),
        })
        .collect();
    let export = ServerExportData {
        version: 1,
        exported_at: now(),
        main_password: db.main_password.clone(),
        dns: db.dns.clone(),
        dns_provider: db.dns_provider.clone(),
        auto_restart_interval_hours: db.auto_restart_interval_hours,
        vk_access_token: db.vk_access_token.clone(),
        vk_hashes: db.vk_hashes.clone(),
        groups: db.groups.clone(),
        routing_rules: db.routing_rules.clone(),
        clients,
    };
    Json(export)
}

#[derive(Deserialize)]
struct ServerImportPayload {
    #[serde(default)]
    import_clients: Option<bool>,
    #[serde(default)]
    import_settings: Option<bool>,
    #[serde(default)]
    config: Option<ServerExportData>,
    #[serde(flatten)]
    direct_config: Option<ServerExportData>,
}

async fn api_import_post(
    State(state): State<WebState>,
    Json(payload): Json<ServerImportPayload>,
) -> Response {
    let export_opt = payload.config.or(payload.direct_config);
    let Some(export) = export_opt else {
        return (StatusCode::BAD_REQUEST, "invalid or empty config").into_response();
    };
    let do_clients = payload.import_clients.unwrap_or(true);
    let do_settings = payload.import_settings.unwrap_or(true);
    let mut added_clients = 0usize;
    let mut applied_dns = None;
    let mut applied_interval = None;

    let revision = {
        let mut db = state.app.db.write().await;
        if do_clients {
            for c in export.clients {
                if !db.passwords.contains_key(&c.password) {
                    let sub_uuid = if c.sub_uuid.trim().is_empty() {
                        model::random_uuid_v4()
                    } else {
                        c.sub_uuid
                    };
                    let ip = if c.ip.is_empty() {
                        model::get_next_ip(&db).unwrap_or_default()
                    } else {
                        c.ip
                    };
                    let client_name = if c.name.trim().is_empty()
                        || model::is_client_name_taken(
                            model::existing_client_names_in_scope(&db, &c.group_id, None),
                            &c.name,
                        ) {
                        model::generate_unique_client_name(model::existing_client_names_in_scope(
                            &db,
                            &c.group_id,
                            None,
                        ))
                    } else {
                        c.name
                    };
                    db.passwords.insert(
                        c.password.clone(),
                        PasswordEntry {
                            ip,
                            expires_at: c.expires_at,
                            down_bytes: c.down_bytes,
                            up_bytes: c.up_bytes,
                            vk_hash: c.vk_hash,
                            ports: c.ports,
                            is_deactivated: c.is_deactivated,
                            name: client_name,
                            vk_hashes: c.vk_hashes,
                            vk_token_enabled: c.vk_token_enabled,
                            dtls_port: c.dtls_port,
                            wg_port: 0,
                            local_port: c.local_port,
                            group_id: c.group_id,
                            routing_rule_id: c.routing_rule_id,
                            sub_uuid,
                            workers: c.workers.filter(|&w| (3..=100).contains(&w)),
                        },
                    );
                    added_clients += 1;
                }
            }
            for (gid, g) in export.groups {
                db.groups.entry(gid).or_insert(g);
            }
            for (rid, r) in export.routing_rules {
                db.routing_rules.entry(rid).or_insert(r);
            }
        }
        if do_settings {
            if !export.main_password.is_empty() && !db.passwords.contains_key(&export.main_password)
            {
                db.main_password = export.main_password;
            }
            if !export.dns.is_empty() {
                applied_dns = (db.dns != export.dns).then(|| export.dns.clone());
                db.dns = export.dns;
                db.dns_provider = export.dns_provider;
            }
            if let Some(hours) = export.auto_restart_interval_hours {
                db.set_auto_restart_interval_hours(hours);
                applied_interval = Some(hours);
            }
            if !export.vk_access_token.is_empty() {
                db.vk_access_token = export.vk_access_token;
            }
            if !export.vk_hashes.is_empty() {
                db.vk_hashes = export.vk_hashes;
            }
        }
        state.app.db_persistence.submit(db.clone())
    };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(dns) = applied_dns {
        *state.app.dns.write().await = dns;
        let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    }
    if let Some(hours) = applied_interval {
        state.app.auto_restart_interval_tx.send_replace(hours);
    }
    let _ = refresh_credentials_report(&state).await;

    Json(serde_json::json!({
        "ok": true,
        "added_clients": added_clients,
        "settings_imported": do_settings
    }))
    .into_response()
}

#[derive(Serialize)]
struct GroupInfo {
    id: String,
    name: String,
    routing_rule_id: String,
    admin_uuid: String,
    sub_uuid: String,
    member_count: usize,
    up: i64,
    down: i64,
    active_sessions: usize,
}

async fn groups_get(State(state): State<WebState>) -> Response {
    let db = state.app.db.read().await;
    let mut list = Vec::new();
    for group in db.groups.values() {
        let mut member_count = 0;
        let mut up = 0i64;
        let mut down = 0i64;
        let mut active_sessions = 0;
        for (_pass, p_entry) in &db.passwords {
            if p_entry.group_id == group.id {
                member_count += 1;
                up += p_entry.up_bytes;
                down += p_entry.down_bytes;
                active_sessions += count_active_sessions(&state.app.sessions, _pass);
            }
        }
        list.push(GroupInfo {
            id: group.id.clone(),
            name: group.name.clone(),
            routing_rule_id: group.routing_rule_id.clone(),
            admin_uuid: group.admin_uuid.clone(),
            sub_uuid: group.sub_uuid.clone(),
            member_count,
            up,
            down,
            active_sessions,
        });
    }
    Json(list).into_response()
}

#[derive(Deserialize)]
struct CreateGroupRequest {
    name: String,
}

async fn groups_create(
    State(state): State<WebState>,
    Json(request): Json<CreateGroupRequest>,
) -> Response {
    let name = request.name.trim();
    if name.is_empty() || name.len() > 64 {
        return (StatusCode::BAD_REQUEST, "invalid group name").into_response();
    }
    let id = format!("grp_{}", random_token(8));
    let group = ClientGroup {
        id: id.clone(),
        name: name.to_string(),
        routing_rule_id: String::new(),
        admin_uuid: String::new(),
        sub_uuid: model::random_uuid_v4(),
        workers: None,
        vk_token: None,
        vk_hashes: None,
    };
    let revision = {
        let mut db = state.app.db.write().await;
        db.groups.insert(id, group.clone());
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    Json(group).into_response()
}

#[derive(Deserialize)]
struct UpdateGroupRequest {
    name: Option<String>,
    routing_rule_id: Option<String>,
}

async fn groups_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<UpdateGroupRequest>,
) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        let Some(group) = db.groups.get_mut(&id) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        if let Some(name) = request.name {
            let n = name.trim();
            if n.is_empty() || n.len() > 64 {
                return (StatusCode::BAD_REQUEST, "invalid group name").into_response();
            }
            group.name = n.to_string();
        }
        if let Some(r_id) = request.routing_rule_id {
            group.routing_rule_id = r_id;
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    StatusCode::OK.into_response()
}

async fn groups_delete(State(state): State<WebState>, Path(id): Path<String>) -> Response {
    let (revision, deleted_passwords) = {
        let mut db = state.app.db.write().await;
        if db.groups.remove(&id).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        }
        let mut deleted = Vec::new();
        db.passwords.retain(|pwd, entry| {
            if entry.group_id == id {
                deleted.push(pwd.clone());
                false
            } else {
                true
            }
        });
        for pwd in &deleted {
            state.app.derived_keys.remove(pwd);
        }
        let rev = state.app.db_persistence.submit(db.clone());
        (rev, deleted)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    for pwd in &deleted_passwords {
        crate::protocol::drop_password_sessions(&state.app, pwd);
    }
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }
    StatusCode::OK.into_response()
}

async fn group_admin_link_post(State(state): State<WebState>, Path(id): Path<String>) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db.groups.get_mut(&id) else {
        return (StatusCode::NOT_FOUND, "group not found").into_response();
    };
    if group.admin_uuid.is_empty() {
        group.admin_uuid = model::random_uuid_v4();
    }
    let admin_uuid = group.admin_uuid.clone();
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "admin_uuid": admin_uuid
        })),
    )
        .into_response()
}

async fn group_admin_link_regenerate(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db.groups.get_mut(&id) else {
        return (StatusCode::NOT_FOUND, "group not found").into_response();
    };
    group.admin_uuid = model::random_uuid_v4();
    let admin_uuid = group.admin_uuid.clone();
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "admin_uuid": admin_uuid
        })),
    )
        .into_response()
}

async fn group_admin_link_delete(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db.groups.get_mut(&id) else {
        return (StatusCode::NOT_FOUND, "group not found").into_response();
    };
    group.admin_uuid.clear();
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true
        })),
    )
        .into_response()
}

async fn group_admin_page(
    State(state): State<WebState>,
    Path(admin_uuid): Path<String>,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let db = state.app.db.read().await;
    let exists = db
        .groups
        .values()
        .any(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid);
    if !exists {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    Html(GROUP_ADMIN_HTML).into_response()
}

fn html_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn render_client_sub_html(client_name: &str, csqtt_link: &str) -> String {
    let name_esc = html_escape(client_name);
    let link_esc = html_escape(csqtt_link);
    CLIENT_SUB_HTML
        .replace("__CLIENT_NAME__", &name_esc)
        .replace("__CSQTT_LINK__", &link_esc)
}

fn render_group_sub_html(group_name: &str, clients_json: &str, count: usize) -> String {
    let name_esc = html_escape(group_name);
    let json_esc = js_json_embed(clients_json);
    GROUP_SUB_HTML
        .replace("__GROUP_NAME__", &name_esc)
        .replace("__CLIENTS_COUNT__", &count.to_string())
        .replace("__CLIENTS_JSON__", &json_esc)
}

fn js_json_embed(json: &str) -> String {
    json.replace('<', "\\u003C")
        .replace('>', "\\u003E")
        .replace('&', "\\u0026")
        .replace('\u{2028}', "\\u2028")
        .replace('\u{2029}', "\\u2029")
}

async fn client_sub_page(
    State(state): State<WebState>,
    Path(sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let sub_uuid = sub_uuid.trim();
    if sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    if !db.main_sub_uuid.is_empty() && db.main_sub_uuid == sub_uuid && !db.main_password.is_empty()
    {
        let main_peer_port = state.app.listen.port();
        let link = make_main_link(&host, main_peer_port, &db);
        return Html(render_client_sub_html("Владелец", &link)).into_response();
    }

    let client_entry = db
        .passwords
        .iter()
        .find(|(_, entry)| !entry.sub_uuid.is_empty() && entry.sub_uuid == sub_uuid);
    let Some((password, entry)) = client_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };
    let client_peer_port = if entry.dtls_port == 0 {
        state.app.listen.port()
    } else {
        entry.dtls_port
    };
    let effective_vk_token = if !entry.group_id.is_empty() {
        db.groups
            .get(&entry.group_id)
            .and_then(|g| g.vk_token.as_deref())
            .unwrap_or(&db.vk_access_token)
    } else {
        &db.vk_access_token
    };
    let link = make_entry_link(&host, client_peer_port, password, entry, effective_vk_token);
    let client_name = if entry.name.is_empty() {
        "Клиент".to_string()
    } else {
        entry.name.clone()
    };
    Html(render_client_sub_html(&client_name, &link)).into_response()
}

async fn client_sub_json(
    State(state): State<WebState>,
    Path(sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let sub_uuid = sub_uuid.trim();
    if sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    if !db.main_sub_uuid.is_empty() && db.main_sub_uuid == sub_uuid && !db.main_password.is_empty()
    {
        let main_peer_port = state.app.listen.port();
        let link = make_main_link(&host, main_peer_port, &db);
        return (
            StatusCode::OK,
            Json(serde_json::json!({
                "ok": true,
                "name": "Владелец",
                "link": link,
                "sub_uuid": db.main_sub_uuid,
            })),
        )
            .into_response();
    }

    let client_entry = db
        .passwords
        .iter()
        .find(|(_, entry)| !entry.sub_uuid.is_empty() && entry.sub_uuid == sub_uuid);
    let Some((password, entry)) = client_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };
    let client_peer_port = if entry.dtls_port == 0 {
        state.app.listen.port()
    } else {
        entry.dtls_port
    };
    let effective_vk_token = if !entry.group_id.is_empty() {
        db.groups
            .get(&entry.group_id)
            .and_then(|g| g.vk_token.as_deref())
            .unwrap_or(&db.vk_access_token)
    } else {
        &db.vk_access_token
    };
    let link = make_entry_link(&host, client_peer_port, password, entry, effective_vk_token);
    let client_name = if entry.name.is_empty() {
        "Клиент".to_string()
    } else {
        entry.name.clone()
    };
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "name": client_name,
            "link": link,
            "sub_uuid": entry.sub_uuid,
        })),
    )
        .into_response()
}

#[derive(Serialize)]
struct GroupSubClientItem {
    name: String,
    link: String,
    sub_uuid: String,
}

async fn group_sub_page(
    State(state): State<WebState>,
    Path(group_sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let group_sub_uuid = group_sub_uuid.trim();
    if group_sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let group_entry = db
        .groups
        .values()
        .find(|g| !g.sub_uuid.is_empty() && g.sub_uuid == group_sub_uuid);
    let Some(group) = group_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };

    let host = resolve_client_host(&headers, &state).await;
    let effective_vk_token = group.vk_token.as_deref().unwrap_or(&db.vk_access_token);
    let mut items = Vec::new();
    for (password, entry) in &db.passwords {
        if entry.group_id == group.id {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            let link =
                make_entry_link(&host, client_peer_port, password, entry, effective_vk_token);
            let name = if entry.name.is_empty() {
                "Клиент".to_string()
            } else {
                entry.name.clone()
            };
            items.push(GroupSubClientItem {
                name,
                link,
                sub_uuid: entry.sub_uuid.clone(),
            });
        }
    }
    items.sort_by(|a, b| a.name.cmp(&b.name));
    let count = items.len();
    let clients_json = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    Html(render_group_sub_html(&group.name, &clients_json, count)).into_response()
}

async fn group_sub_json(
    State(state): State<WebState>,
    Path(group_sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let group_sub_uuid = group_sub_uuid.trim();
    if group_sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let group_entry = db
        .groups
        .values()
        .find(|g| !g.sub_uuid.is_empty() && g.sub_uuid == group_sub_uuid);
    let Some(group) = group_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };

    let host = resolve_client_host(&headers, &state).await;
    let effective_vk_token = group.vk_token.as_deref().unwrap_or(&db.vk_access_token);
    let mut items = Vec::new();
    for (password, entry) in &db.passwords {
        if entry.group_id == group.id {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            let link =
                make_entry_link(&host, client_peer_port, password, entry, effective_vk_token);
            let name = if entry.name.is_empty() {
                "Клиент".to_string()
            } else {
                entry.name.clone()
            };
            items.push(GroupSubClientItem {
                name,
                link,
                sub_uuid: entry.sub_uuid.clone(),
            });
        }
    }
    items.sort_by(|a, b| a.name.cmp(&b.name));

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "group": {
                "id": group.id,
                "name": group.name,
                "sub_uuid": group.sub_uuid,
            },
            "clients": items,
        })),
    )
        .into_response()
}

async fn universal_sub_json(
    State(state): State<WebState>,
    Path(uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let trimmed = uuid.trim();
    if trimmed.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let is_client = {
        let db = state.app.db.read().await;
        (!db.main_sub_uuid.is_empty() && db.main_sub_uuid == trimmed)
            || db
                .passwords
                .values()
                .any(|p| !p.sub_uuid.is_empty() && p.sub_uuid == trimmed)
    };
    if is_client {
        return client_sub_json(State(state), Path(uuid), headers, token).await;
    }
    let is_group = {
        let db = state.app.db.read().await;
        db.groups
            .values()
            .any(|g| !g.sub_uuid.is_empty() && g.sub_uuid == trimmed)
    };
    if is_group {
        return group_sub_json(State(state), Path(uuid), headers, token).await;
    }
    if let Some(Extension(ctrl)) = token {
        ctrl.abort();
    }
    stealth_abort_response()
}

async fn client_sub_link_regenerate(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let (revision, new_sub) = {
        let mut db = state.app.db.write().await;
        if secret_eq(&password, &db.main_password) {
            db.main_sub_uuid = model::random_uuid_v4();
            let new_sub = db.main_sub_uuid.clone();
            let rev = state.app.db_persistence.submit(db.clone());
            (rev, new_sub)
        } else if let Some(entry) = db.passwords.get_mut(&password) {
            entry.sub_uuid = model::random_uuid_v4();
            let new_sub = entry.sub_uuid.clone();
            let rev = state.app.db_persistence.submit(db.clone());
            (rev, new_sub)
        } else {
            return StatusCode::NOT_FOUND.into_response();
        }
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "sub_uuid": new_sub
        })),
    )
        .into_response()
}

async fn group_sub_link_regenerate(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let (revision, new_sub) = {
        let mut db = state.app.db.write().await;
        let Some(group) = db.groups.get_mut(&id) else {
            return (StatusCode::NOT_FOUND, "group not found").into_response();
        };
        group.sub_uuid = model::random_uuid_v4();
        let new_sub = group.sub_uuid.clone();
        let rev = state.app.db_persistence.submit(db.clone());
        (rev, new_sub)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "sub_uuid": new_sub
        })),
    )
        .into_response()
}

#[derive(Deserialize)]
struct BatchToggleRequest {
    passwords: Vec<String>,
    active: bool,
}

async fn api_clients_batch_toggle(
    State(state): State<WebState>,
    Json(req): Json<BatchToggleRequest>,
) -> Response {
    let mut updated = 0usize;
    let mut passwords_to_disconnect = Vec::new();
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.is_deactivated = !req.active;
                updated += 1;
                if !req.active {
                    passwords_to_disconnect.push(pwd.clone());
                }
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &passwords_to_disconnect {
        crate::protocol::drop_password_sessions(&state.app, pwd);
    }
    if updated > 0 {
        let _ = refresh_credentials_report(&state).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

#[derive(Deserialize)]
struct BatchExtendRequest {
    passwords: Vec<String>,
    days: i64,
}

async fn api_clients_batch_extend(
    State(state): State<WebState>,
    Json(req): Json<BatchExtendRequest>,
) -> Response {
    if req.days <= 0 || req.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be between 1 and 3650").into_response();
    }
    let add_secs = req.days.saturating_mul(86400);
    let current_time = now();
    let mut updated = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                let base = if entry.expires_at > current_time {
                    entry.expires_at
                } else {
                    current_time
                };
                entry.expires_at = base.saturating_add(add_secs);
                updated += 1;
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

#[derive(Deserialize)]
struct BatchPasswordsRequest {
    passwords: Vec<String>,
}

async fn api_clients_batch_reset_traffic(
    State(state): State<WebState>,
    Json(req): Json<BatchPasswordsRequest>,
) -> Response {
    let mut updated = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if pwd == &db.main_password {
                db.main_down_bytes = 0;
                db.main_up_bytes = 0;
                updated += 1;
            } else if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.down_bytes = 0;
                entry.up_bytes = 0;
                updated += 1;
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &req.passwords {
        for s in state.app.sessions.iter() {
            if &s.password == pwd {
                s.down_bytes.store(0, Ordering::Relaxed);
                s.up_bytes.store(0, Ordering::Relaxed);
            }
        }
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

async fn api_clients_batch_delete(
    State(state): State<WebState>,
    Json(req): Json<BatchPasswordsRequest>,
) -> Response {
    let mut deleted = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if db.passwords.remove(pwd).is_some() {
                state.app.derived_keys.remove(pwd);
                deleted += 1;
            }
        }
        if deleted > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &req.passwords {
        crate::protocol::drop_password_sessions(&state.app, pwd);
    }
    if deleted > 0 {
        let _ = refresh_credentials_report(&state).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "deleted": deleted })),
    )
        .into_response()
}

#[derive(Deserialize)]
struct BatchGroupAssignRequest {
    passwords: Vec<String>,
    group_id: String,
}

async fn api_clients_batch_group(
    State(state): State<WebState>,
    Json(req): Json<BatchGroupAssignRequest>,
) -> Response {
    let mut moved = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        let gid = req.group_id.trim();
        if !gid.is_empty() && !db.groups.contains_key(gid) {
            return (StatusCode::BAD_REQUEST, "target group not found").into_response();
        }
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.group_id = gid.to_string();
                moved += 1;
            }
        }
        if moved > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "moved": moved })),
    )
        .into_response()
}

#[derive(Deserialize, Default)]
struct DpiDiagnosticsQuery {
    #[serde(default)]
    limit: Option<usize>,
}

async fn api_diagnostics_dpi(Query(query): Query<DpiDiagnosticsQuery>) -> impl IntoResponse {
    let limit = query.limit.unwrap_or(20).clamp(1, 100);
    let frames: Vec<protocol::DpiFrame> = protocol::DPI_RING
        .read()
        .map(|ring| {
            let count = limit.min(ring.len());
            ring.iter().skip(ring.len() - count).cloned().collect()
        })
        .unwrap_or_default();
    Json(serde_json::json!({
        "ok": true,
        "ring_len": protocol::dpi_ring_len(),
        "frames": frames,
    }))
}

async fn group_admin_data_get(
    State(state): State<WebState>,
    Path(admin_uuid): Path<String>,
) -> Response {
    let db = state.app.db.read().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let server_ip = state.app.public_ip.read().await.clone();
    let host = if server_ip.is_empty() {
        "0.0.0.0".to_string()
    } else {
        server_ip.clone()
    };
    let peer_port = state.app.listen.port();
    let effective_vk_token = group.vk_token.as_deref().unwrap_or(&db.vk_access_token);
    let effective_vk_hashes = group.vk_hashes.as_deref().unwrap_or(&db.vk_hashes);
    let mut clients = Vec::new();
    for (_pass, p_entry) in &db.passwords {
        if p_entry.group_id == group.id {
            let link = make_entry_link(&host, peer_port, _pass, p_entry, effective_vk_token);
            clients.push(serde_json::json!({
                "password": _pass,
                "name": p_entry.name,
                "expires": p_entry.expires_at,
                "up": p_entry.up_bytes,
                "down": p_entry.down_bytes,
                "active": !p_entry.is_deactivated && !model::is_expired(p_entry),
                "is_deactivated": p_entry.is_deactivated,
                "active_sessions": count_active_sessions(&state.app.sessions, _pass),
                "ip": if p_entry.ip.is_empty() { "10.66.67.3" } else { &p_entry.ip },
                "device_ip": if p_entry.ip.is_empty() { "10.66.67.3" } else { &p_entry.ip },
                "group_id": p_entry.group_id,
                "peer_port": p_entry.wg_port,
                "vk_hashes": p_entry.vk_hashes,
                "vk_token": p_entry.vk_token_enabled,
                "sub_uuid": p_entry.sub_uuid,
                "workers": p_entry.workers.unwrap_or(0),
                "link": link,
            }));
        }
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "group": {
                "id": group.id,
                "name": group.name,
                "workers": group.workers.unwrap_or(0),
                "vk_token": effective_vk_token,
                "vk_hashes": effective_vk_hashes,
            },
            "clients": clients,
            "server_ip": server_ip,
            "peer_port": peer_port
        })),
    )
        .into_response()
}

#[derive(Deserialize)]
struct GroupAdminSettingsRequest {
    #[serde(default)]
    vk_token: Option<String>,
    #[serde(default)]
    vk_hashes: Option<String>,
    #[serde(default)]
    workers: Option<u32>,
}

async fn group_admin_settings_post(
    State(state): State<WebState>,
    Path(admin_uuid): Path<String>,
    Json(payload): Json<GroupAdminSettingsRequest>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values_mut()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    if let Some(token) = payload.vk_token {
        let normalized = match normalize_vk_access_token(&token) {
            Ok(t) => t,
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        };
        group.vk_token = Some(normalized);
    }
    if let Some(hashes) = payload.vk_hashes {
        let normalized = match normalize_client_vk_hashes(&hashes) {
            Ok(h) => h,
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        };
        group.vk_hashes = Some(normalized);
    }
    if let Some(w) = payload.workers {
        match validate_workers(Some(w)) {
            Ok(validated) => group.workers = validated,
            Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
        }
    }
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    let _ = refresh_credentials_report(&state).await;
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

#[derive(Deserialize)]
struct GroupAdminCreateClientRequest {
    #[serde(default)]
    name: String,
    #[serde(default)]
    days: i64,
    #[serde(default)]
    workers: Option<u32>,
}

async fn group_admin_client_create(
    State(state): State<WebState>,
    Path(admin_uuid): Path<String>,
    Json(payload): Json<GroupAdminCreateClientRequest>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group_id) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
        .map(|g| g.id.clone())
    else {
        return stealth_abort_response();
    };
    let client_name = if payload.name.trim().is_empty() {
        model::generate_unique_client_name(model::existing_client_names_in_scope(
            &db, &group_id, None,
        ))
    } else {
        let trimmed = payload.name.trim().to_string();
        if let Err(msg) = validate_client_name(&trimmed) {
            return (StatusCode::BAD_REQUEST, msg).into_response();
        }
        if model::is_client_name_taken(
            model::existing_client_names_in_scope(&db, &group_id, None),
            &trimmed,
        ) {
            return (
                StatusCode::BAD_REQUEST,
                "Участник с таким именем уже существует в этой группе",
            )
                .into_response();
        }
        trimmed
    };
    let validated_workers = match validate_workers(payload.workers) {
        Ok(w) => w,
        Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
    };
    let mut pass = model::random_password();
    while db.passwords.contains_key(&pass) {
        pass = model::random_password();
    }
    let now_ts = model::now();
    let expires_at = if payload.days > 0 {
        now_ts.saturating_add(payload.days.saturating_mul(86400))
    } else {
        0
    };
    let sub_uuid = model::random_uuid_v4();
    let ip = model::get_next_ip(&db).unwrap_or_default();
    let entry = model::PasswordEntry {
        ip,
        expires_at,
        down_bytes: 0,
        up_bytes: 0,
        vk_hash: String::new(),
        ports: String::new(),
        is_deactivated: false,
        name: client_name,
        vk_hashes: String::new(),
        vk_token_enabled: false,
        dtls_port: 0,
        wg_port: 0,
        local_port: 0,
        group_id,
        routing_rule_id: String::new(),
        sub_uuid: sub_uuid.clone(),
        workers: validated_workers,
    };
    db.passwords.insert(pass.clone(), entry);
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (
            StatusCode::OK,
            [("x-csqtt-reload-error", warning)],
            Json(serde_json::json!({
                "ok": true,
                "password": pass,
                "sub_uuid": sub_uuid
            })),
        )
            .into_response();
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "password": pass,
            "sub_uuid": sub_uuid
        })),
    )
        .into_response()
}

async fn group_admin_client_toggle(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let Some(entry) = db.passwords.get_mut(&password) else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };
    if entry.group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    entry.is_deactivated = !entry.is_deactivated;
    let is_deactivated = entry.is_deactivated;
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    if is_deactivated {
        crate::protocol::drop_password_sessions(&state.app, &password);
    }
    let _ = refresh_credentials_report(&state).await;
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "is_deactivated": is_deactivated
        })),
    )
        .into_response()
}

async fn group_admin_client_reset_traffic(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let Some(entry) = db.passwords.get_mut(&password) else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };
    if entry.group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    entry.up_bytes = 0;
    entry.down_bytes = 0;
    for s in state.app.sessions.iter() {
        if s.value().password == password {
            s.value().up_bytes.store(0, Ordering::Relaxed);
            s.value().down_bytes.store(0, Ordering::Relaxed);
        }
    }
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

async fn group_admin_client_extend(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let Some(entry) = db.passwords.get_mut(&password) else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };
    if entry.group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    let now_ts = model::now();
    let base = if entry.expires_at > now_ts {
        entry.expires_at
    } else {
        now_ts
    };
    entry.expires_at = base.saturating_add(30 * 86400);
    let expires = entry.expires_at;
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    let _ = refresh_credentials_report(&state).await;
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "expires": expires })),
    )
        .into_response()
}

async fn group_admin_client_unbind(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
) -> Response {
    let db = state.app.db.read().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let Some(entry) = db.passwords.get(&password) else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };
    if entry.group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    drop(db);
    crate::protocol::drop_password_sessions(&state.app, &password);
    crate::protocol::forget_idle_password_epoch(&state.app, &password);
    let _ = refresh_credentials_report(&state).await;
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

#[derive(Deserialize)]
struct GroupAdminUpdateClientRequest {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    days: Option<i64>,
    #[serde(default)]
    workers: Option<u32>,
    #[serde(default)]
    hash: Option<String>,
    #[serde(default)]
    vk_token: Option<bool>,
}

async fn group_admin_client_update(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
    Json(payload): Json<GroupAdminUpdateClientRequest>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let current_group_id = match db.passwords.get(&password) {
        Some(e) => e.group_id.clone(),
        None => return (StatusCode::NOT_FOUND, "client not found").into_response(),
    };
    if current_group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    let validated_name = if let Some(name) = payload.name {
        let trimmed = name.trim().to_string();
        let scope_names = model::existing_client_names_in_scope(&db, &group_id, Some(&password));
        let client_name = if trimmed.is_empty() {
            model::generate_unique_client_name(&scope_names)
        } else {
            if let Err(msg) = validate_client_name(&trimmed) {
                return (StatusCode::BAD_REQUEST, msg).into_response();
            }
            if model::is_client_name_taken(&scope_names, &trimmed) {
                return (
                    StatusCode::BAD_REQUEST,
                    "Участник с таким именем уже существует в этой группе",
                )
                    .into_response();
            }
            trimmed
        };
        Some(client_name)
    } else {
        None
    };

    let entry = db.passwords.get_mut(&password).unwrap();
    if let Some(name) = validated_name {
        entry.name = name;
    }
    if let Some(days) = payload.days {
        if days > 0 {
            entry.expires_at = model::now().saturating_add(days.saturating_mul(86400));
        } else {
            entry.expires_at = 0;
        }
    }
    if let Some(w) = payload.workers {
        match validate_workers(Some(w)) {
            Ok(validated) => entry.workers = validated,
            Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
        }
    }
    if let Some(ref h) = payload.hash {
        let vk_hashes = match normalize_client_vk_hashes(h) {
            Ok(v) => v,
            Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
        };
        entry.vk_hash = vk_hashes.clone();
        entry.vk_hashes = vk_hashes;
    }
    if let Some(vt) = payload.vk_token {
        entry.vk_token_enabled = vt;
        if vt {
            entry.vk_hash.clear();
            entry.vk_hashes.clear();
        }
    }
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    let _ = refresh_credentials_report(&state).await;
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

async fn group_admin_client_delete(
    State(state): State<WebState>,
    Path((admin_uuid, password)): Path<(String, String)>,
) -> Response {
    let mut db = state.app.db.write().await;
    let Some(group) = db
        .groups
        .values()
        .find(|g| !g.admin_uuid.is_empty() && g.admin_uuid == admin_uuid)
    else {
        return stealth_abort_response();
    };
    let group_id = group.id.clone();
    let Some(entry) = db.passwords.get(&password) else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };
    if entry.group_id != group_id {
        return (StatusCode::FORBIDDEN, "forbidden").into_response();
    }
    db.passwords.remove(&password);
    let snapshot = db.clone();
    drop(db);
    state.app.db_persistence.submit(snapshot);
    crate::protocol::drop_password_sessions(&state.app, &password);
    let _ = refresh_credentials_report(&state).await;
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

#[derive(Serialize)]
struct RoutingRuleInfo {
    id: String,
    name: String,
    is_whitelist: bool,
    mode: String,
    packages: Vec<String>,
    template_domains: bool,
    direct_domains: Vec<String>,
    domains: Vec<String>,
    domains_is_whitelist: bool,
    domains_mode: String,
    ip_is_whitelist: bool,
    ip_mode: String,
    ips: Vec<String>,
    assigned_groups: Vec<String>,
    target_group_ids: Vec<String>,
    assigned_clients: Vec<String>,
    target_client_passwords: Vec<String>,
}

async fn routing_rules_get(State(state): State<WebState>) -> Response {
    let db = state.app.db.read().await;
    let mut list = Vec::new();
    for rule in db.routing_rules.values() {
        let mut assigned_groups = Vec::new();
        for group in db.groups.values() {
            if group.routing_rule_id == rule.id {
                assigned_groups.push(group.id.clone());
            }
        }
        let mut assigned_clients = Vec::new();
        if !db.main_password.is_empty() && db.main_routing_rule_id == rule.id {
            assigned_clients.push(db.main_password.clone());
        }
        for (pass, entry) in &db.passwords {
            if entry.routing_rule_id == rule.id {
                assigned_clients.push(pass.clone());
            }
        }
        list.push(RoutingRuleInfo {
            id: rule.id.clone(),
            name: rule.name.clone(),
            is_whitelist: rule.is_whitelist,
            mode: if rule.is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            packages: rule.packages.clone(),
            template_domains: rule.template_domains,
            direct_domains: rule.direct_domains.clone(),
            domains: rule.direct_domains.clone(),
            domains_is_whitelist: rule.domains_is_whitelist,
            domains_mode: if rule.domains_is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            ip_is_whitelist: rule.ip_is_whitelist,
            ip_mode: if rule.ip_is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            ips: rule.ips.clone(),
            assigned_groups: assigned_groups.clone(),
            target_group_ids: assigned_groups,
            assigned_clients: assigned_clients.clone(),
            target_client_passwords: assigned_clients,
        });
    }
    Json(list).into_response()
}

const TEMPLATE_DOMAINS_TXT: &str = include_str!("../app/src/main/assets/domains.txt");

async fn template_domains_get() -> impl IntoResponse {
    (
        [(
            axum::http::header::CONTENT_TYPE,
            "text/plain; charset=utf-8",
        )],
        TEMPLATE_DOMAINS_TXT,
    )
}

#[derive(Deserialize)]
struct CreateRoutingRuleRequest {
    #[serde(default)]
    name: String,
    #[serde(default)]
    is_whitelist: bool,
    #[serde(default)]
    mode: Option<String>,
    #[serde(default)]
    packages: Vec<String>,
    #[serde(default)]
    template_domains: bool,
    #[serde(default)]
    direct_domains: Vec<String>,
    #[serde(default)]
    domains: Option<Vec<String>>,
    #[serde(default)]
    domains_mode: Option<String>,
    #[serde(default)]
    domains_is_whitelist: bool,
    #[serde(default)]
    ip_mode: Option<String>,
    #[serde(default)]
    ip_is_whitelist: bool,
    #[serde(default)]
    ips: Vec<String>,
    #[serde(default)]
    assigned_groups: Vec<String>,
    #[serde(default)]
    target_group_ids: Option<Vec<String>>,
    #[serde(default)]
    assigned_clients: Vec<String>,
    #[serde(default)]
    target_client_passwords: Option<Vec<String>>,
}

async fn routing_rules_create(
    State(state): State<WebState>,
    Json(request): Json<CreateRoutingRuleRequest>,
) -> Response {
    let name_str = request.name.trim();
    let name = if name_str.is_empty() {
        "Правило".to_string()
    } else {
        name_str.chars().take(64).collect::<String>()
    };
    let is_whitelist = if let Some(ref m) = request.mode {
        m == "whitelist"
    } else {
        request.is_whitelist
    };
    let direct_domains = if !request.direct_domains.is_empty() {
        request.direct_domains
    } else {
        request.domains.unwrap_or_default()
    };
    let domains_is_whitelist = if let Some(ref m) = request.domains_mode {
        m == "whitelist"
    } else {
        request.domains_is_whitelist
    };
    let ip_is_whitelist = if let Some(ref m) = request.ip_mode {
        m == "whitelist"
    } else {
        request.ip_is_whitelist
    };
    let assigned_groups = if !request.assigned_groups.is_empty() {
        request.assigned_groups
    } else {
        request.target_group_ids.unwrap_or_default()
    };
    let assigned_clients = if !request.assigned_clients.is_empty() {
        request.assigned_clients
    } else {
        request.target_client_passwords.unwrap_or_default()
    };
    let id = format!("rule_{}", random_token(8));
    let rule = RoutingRule {
        id: id.clone(),
        name,
        is_whitelist,
        packages: request.packages,
        template_domains: request.template_domains,
        direct_domains,
        domains_is_whitelist,
        ip_is_whitelist,
        ips: request.ips,
    };
    let revision = {
        let mut db = state.app.db.write().await;
        for g_id in &assigned_groups {
            if let Some(g) = db.groups.get_mut(g_id) {
                g.routing_rule_id = id.clone();
            }
        }
        for c_pass in &assigned_clients {
            if let Some(p) = db.passwords.get_mut(c_pass) {
                p.routing_rule_id = id.clone();
            }
        }
        if !db.main_password.is_empty() && assigned_clients.contains(&db.main_password) {
            db.main_routing_rule_id = id.clone();
        }
        db.routing_rules.insert(id, rule.clone());
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    Json(rule).into_response()
}

async fn routing_rules_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<CreateRoutingRuleRequest>,
) -> Response {
    let name_str = request.name.trim();
    let name = if name_str.is_empty() {
        "Правило".to_string()
    } else {
        name_str.chars().take(64).collect::<String>()
    };
    let is_whitelist = if let Some(ref m) = request.mode {
        m == "whitelist"
    } else {
        request.is_whitelist
    };
    let direct_domains = if !request.direct_domains.is_empty() {
        request.direct_domains
    } else {
        request.domains.unwrap_or_default()
    };
    let domains_is_whitelist = if let Some(ref m) = request.domains_mode {
        m == "whitelist"
    } else {
        request.domains_is_whitelist
    };
    let ip_is_whitelist = if let Some(ref m) = request.ip_mode {
        m == "whitelist"
    } else {
        request.ip_is_whitelist
    };
    let assigned_groups = if !request.assigned_groups.is_empty() {
        request.assigned_groups
    } else {
        request.target_group_ids.unwrap_or_default()
    };
    let assigned_clients = if !request.assigned_clients.is_empty() {
        request.assigned_clients
    } else {
        request.target_client_passwords.unwrap_or_default()
    };
    let revision = {
        let mut db = state.app.db.write().await;
        let Some(rule) = db.routing_rules.get_mut(&id) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        rule.name = name;
        rule.is_whitelist = is_whitelist;
        rule.packages = request.packages;
        rule.template_domains = request.template_domains;
        rule.direct_domains = direct_domains;
        rule.domains_is_whitelist = domains_is_whitelist;
        rule.ip_is_whitelist = ip_is_whitelist;
        rule.ips = request.ips;

        for group in db.groups.values_mut() {
            if assigned_groups.contains(&group.id) {
                group.routing_rule_id = id.clone();
            } else if group.routing_rule_id == id {
                group.routing_rule_id.clear();
            }
        }
        for (pass, entry) in db.passwords.iter_mut() {
            if assigned_clients.contains(pass) {
                entry.routing_rule_id = id.clone();
            } else if entry.routing_rule_id == id {
                entry.routing_rule_id.clear();
            }
        }
        if !db.main_password.is_empty() {
            if assigned_clients.contains(&db.main_password) {
                db.main_routing_rule_id = id.clone();
            } else if db.main_routing_rule_id == id {
                db.main_routing_rule_id.clear();
            }
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    StatusCode::OK.into_response()
}

async fn routing_rules_delete(State(state): State<WebState>, Path(id): Path<String>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        if db.routing_rules.remove(&id).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        }
        for group in db.groups.values_mut() {
            if group.routing_rule_id == id {
                group.routing_rule_id.clear();
            }
        }
        for entry in db.passwords.values_mut() {
            if entry.routing_rule_id == id {
                entry.routing_rule_id.clear();
            }
        }
        if db.main_routing_rule_id == id {
            db.main_routing_rule_id.clear();
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    StatusCode::OK.into_response()
}

async fn reboot(State(state): State<WebState>) -> Response {
    if state.app.restart_pending.swap(true, Ordering::AcqRel) {
        return (
            StatusCode::ACCEPTED,
            [
                (header::RETRY_AFTER, "3"),
                (header::CACHE_CONTROL, "no-store"),
            ],
        )
            .into_response();
    }

    match crate::request_managed_restart(&state.app, "panel") {
        Ok(()) => (
            StatusCode::ACCEPTED,
            [
                (header::RETRY_AFTER, "3"),
                (header::CACHE_CONTROL, "no-store"),
            ],
        )
            .into_response(),
        Err(error) => {
            state.app.restart_pending.store(false, Ordering::Release);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("restart failed: {error:#}"),
            )
                .into_response()
        }
    }
}

async fn web_session_janitor(app: Arc<App>) {
    let mut timer = tokio::time::interval(Duration::from_secs(300));
    loop {
        timer.tick().await;
        let current = now();
        app.web_sessions.retain(|_, expiry| *expiry > current);
        app.login_limits.retain(|_, value| value.1 > current);
        app.web_sessions.shrink_to_fit();
        app.login_limits.shrink_to_fit();
    }
}

#[derive(Debug)]
pub struct StealthAbortController {
    aborted: AtomicBool,
    last_activity_ms: AtomicU64,
    birth_ms: AtomicU64,
    #[cfg(unix)]
    raw_fd: std::sync::Mutex<Option<std::os::fd::RawFd>>,
}

static WEB_CONNECTION_CONTROLLERS: Mutex<Vec<std::sync::Weak<StealthAbortController>>> =
    Mutex::new(Vec::new());

const WEB_IDLE_ABORT_MS: u64 = 120_000;
const WEB_MAX_CONNECTION_LIFETIME_MS: u64 = 30 * 60_000;

fn web_watchdog_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

async fn web_slowloris_watchdog() {
    let mut timer = tokio::time::interval(Duration::from_millis(5_000));
    loop {
        timer.tick().await;
        let now = web_watchdog_now_ms();
        let stale = {
            let mut registry = WEB_CONNECTION_CONTROLLERS
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            registry.shrink_to_fit();
            let mut stale = Vec::new();
            registry.retain(|weak| match weak.upgrade() {
                Some(controller) => {
                    stale.push(controller);
                    true
                }
                None => false,
            });
            stale
        };
        if stale.is_empty() {
            continue;
        }
        for controller in stale {
            if controller.aborted.load(Ordering::Relaxed) {
                continue;
            }
            let idle_for = now.saturating_sub(controller.last_activity_ms.load(Ordering::Acquire));
            if idle_for > WEB_IDLE_ABORT_MS {
                controller.abort();
                continue;
            }
            let registered_at = controller.birth_ms.load(Ordering::Acquire);
            if registered_at != 0
                && now.saturating_sub(registered_at) > WEB_MAX_CONNECTION_LIFETIME_MS
            {
                controller.abort();
            }
        }
    }
}

fn register_web_controller(controller: &Arc<StealthAbortController>) {
    let time = web_watchdog_now_ms();
    controller.last_activity_ms.store(time, Ordering::Release);
    controller.birth_ms.store(time, Ordering::Release);
    let mut registry = WEB_CONNECTION_CONTROLLERS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if registry.len() > 4 * MAX_WEB_CONNECTIONS {
        registry.retain(|weak| weak.strong_count() > 0);
    }
    registry.push(Arc::downgrade(controller));
}

impl StealthAbortController {
    pub fn new(#[allow(unused_variables)] stream: &tokio::net::TcpStream) -> Self {
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;
            Self {
                aborted: AtomicBool::new(false),
                last_activity_ms: AtomicU64::new(web_watchdog_now_ms()),
                birth_ms: AtomicU64::new(0),
                raw_fd: std::sync::Mutex::new(Some(stream.as_raw_fd())),
            }
        }
        #[cfg(not(unix))]
        {
            Self {
                aborted: AtomicBool::new(false),
                last_activity_ms: AtomicU64::new(web_watchdog_now_ms()),
                birth_ms: AtomicU64::new(0),
            }
        }
    }

    pub fn touch(&self) {
        self.last_activity_ms
            .store(web_watchdog_now_ms(), Ordering::Release);
    }

    pub fn abort(&self) {
        if self.aborted.swap(true, Ordering::SeqCst) {
            return;
        }
        #[cfg(unix)]
        {
            let guard = self
                .raw_fd
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if let Some(fd) = *guard {
                let linger = libc::linger {
                    l_onoff: 1,
                    l_linger: 0,
                };
                unsafe {
                    libc::setsockopt(
                        fd,
                        libc::SOL_SOCKET,
                        libc::SO_LINGER,
                        (&linger as *const libc::linger).cast(),
                        std::mem::size_of::<libc::linger>() as libc::socklen_t,
                    );
                    libc::shutdown(fd, libc::SHUT_RDWR);
                }
            }
        }
    }

    fn detach(&self) {
        #[cfg(unix)]
        {
            *self
                .raw_fd
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        }
    }

    pub fn is_aborted(&self) -> bool {
        self.aborted.load(Ordering::Relaxed)
    }
}

pub enum DualProtocolStream {
    #[allow(dead_code)]
    Plain {
        stream: tokio::net::TcpStream,
        controller: Arc<StealthAbortController>,
        _permit: tokio::sync::OwnedSemaphorePermit,
    },
    Tls {
        stream: Box<tokio_rustls::server::TlsStream<tokio::net::TcpStream>>,
        controller: Arc<StealthAbortController>,
        _permit: tokio::sync::OwnedSemaphorePermit,
    },
}

impl DualProtocolStream {
    pub fn controller(&self) -> &Arc<StealthAbortController> {
        match self {
            DualProtocolStream::Plain { controller, .. } => controller,
            DualProtocolStream::Tls { controller, .. } => controller,
        }
    }

    #[allow(deprecated)]
    fn abort_tcp(&self) {
        self.controller().abort();
        match self {
            DualProtocolStream::Plain { stream, .. } => {
                let _ = stream.set_linger(Some(Duration::ZERO));
            }
            DualProtocolStream::Tls { stream, .. } => {
                let _ = stream.get_ref().0.set_linger(Some(Duration::ZERO));
            }
        }
    }
}

impl Drop for DualProtocolStream {
    fn drop(&mut self) {
        self.controller().detach();
    }
}

impl tokio::io::AsyncRead for DualProtocolStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                let poll = std::pin::Pin::new(stream).poll_read(cx, buf);
                if matches!(poll, std::task::Poll::Ready(Ok(())) if !buf.filled().is_empty()) {
                    self.controller().touch();
                }
                poll
            }
            DualProtocolStream::Tls { stream, .. } => {
                let stream = stream.as_mut();
                let poll = std::pin::Pin::new(stream).poll_read(cx, buf);
                if matches!(poll, std::task::Poll::Ready(Ok(())) if !buf.filled().is_empty()) {
                    self.controller().touch();
                }
                poll
            }
        }
    }
}

impl tokio::io::AsyncWrite for DualProtocolStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.controller().is_aborted()
            || buf
                .windows(STEALTH_ABORT_HEADER.len())
                .any(|w| w == STEALTH_ABORT_HEADER.as_bytes())
        {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                let poll = std::pin::Pin::new(stream).poll_write(cx, buf);
                if poll.is_ready() {
                    self.controller().touch();
                }
                poll
            }
            DualProtocolStream::Tls { stream, .. } => {
                let stream = stream.as_mut();
                let poll = std::pin::Pin::new(stream).poll_write(cx, buf);
                if poll.is_ready() {
                    self.controller().touch();
                }
                poll
            }
        }
    }

    fn poll_write_vectored(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        bufs: &[std::io::IoSlice<'_>],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                std::pin::Pin::new(stream).poll_write_vectored(cx, bufs)
            }
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_write_vectored(cx, bufs)
            }
        }
    }

    fn is_write_vectored(&self) -> bool {
        match self {
            DualProtocolStream::Plain { stream, .. } => stream.is_write_vectored(),
            DualProtocolStream::Tls { stream, .. } => stream.is_write_vectored(),
        }
    }

    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => std::pin::Pin::new(stream).poll_flush(cx),
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_flush(cx)
            }
        }
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                std::pin::Pin::new(stream).poll_shutdown(cx)
            }
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_shutdown(cx)
            }
        }
    }
}

fn collapse_consecutive_slashes(path: &str) -> String {
    if !path.contains("//") {
        return path.to_string();
    }
    let mut result = String::with_capacity(path.len());
    let mut prev_slash = false;
    for c in path.chars() {
        if c == '/' {
            if !prev_slash {
                result.push(c);
                prev_slash = true;
            }
        } else {
            result.push(c);
            prev_slash = false;
        }
    }
    result
}

#[derive(Clone)]
pub struct StealthConnectionService<S> {
    inner: S,
    controller: Arc<StealthAbortController>,
}

impl<S, ReqBody> tower_service::Service<axum::http::Request<ReqBody>>
    for StealthConnectionService<S>
where
    S: tower_service::Service<axum::http::Request<ReqBody>>,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = S::Future;

    fn poll_ready(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut req: axum::http::Request<ReqBody>) -> Self::Future {
        req.extensions_mut().insert(self.controller.clone());
        let uri = req.uri().clone();
        let path = uri.path();
        if path.contains("//") {
            let new_path = collapse_consecutive_slashes(path);
            let new_uri_str = if let Some(query) = uri.query() {
                format!("{}?{}", new_path, query)
            } else {
                new_path
            };
            if let Ok(new_uri) = new_uri_str.parse() {
                *req.uri_mut() = new_uri;
            }
        }
        self.inner.call(req)
    }
}

#[derive(Clone)]
pub struct DualAcceptor {
    tls_acceptor: axum_server::tls_rustls::RustlsAcceptor,
    connections: Arc<tokio::sync::Semaphore>,
    app: Arc<App>,
}

impl DualAcceptor {
    pub fn new(config: axum_server::tls_rustls::RustlsConfig, app: Arc<App>) -> Self {
        Self {
            tls_acceptor: axum_server::tls_rustls::RustlsAcceptor::new(config),
            connections: Arc::new(tokio::sync::Semaphore::new(MAX_WEB_CONNECTIONS)),
            app,
        }
    }
}

async fn is_valid_secret_path(raw_path: &str, app: &Arc<App>) -> bool {
    let path_no_query = raw_path.split('?').next().unwrap_or("").trim();
    let normalized = collapse_consecutive_slashes(path_no_query);
    let secret = app.web_secret_path.trim().trim_matches('/');
    if !secret.is_empty() {
        let prefix = format!("/{}", secret);
        if normalized == prefix || normalized.starts_with(&format!("{}/", prefix)) {
            return true;
        }
    } else {
        return true;
    }

    let trimmed = normalized.trim_matches('/');
    let first_segment = trimmed.split('/').next().unwrap_or("");
    if !first_segment.is_empty() {
        let db = app.db.read().await;
        if !db.main_sub_uuid.is_empty() && db.main_sub_uuid == first_segment {
            return true;
        }
        if db
            .passwords
            .values()
            .any(|p| !p.sub_uuid.is_empty() && p.sub_uuid == first_segment)
        {
            return true;
        }
        if db.groups.values().any(|g| {
            (!g.sub_uuid.is_empty() && g.sub_uuid == first_segment)
                || (!g.admin_uuid.is_empty() && g.admin_uuid == first_segment)
        }) {
            return true;
        }
    }

    false
}

impl<S> axum_server::accept::Accept<tokio::net::TcpStream, S> for DualAcceptor
where
    S: Send + Clone + 'static,
{
    type Stream = DualProtocolStream;
    type Service = StealthConnectionService<S>;
    type Future = std::pin::Pin<
        Box<
            dyn std::future::Future<Output = std::io::Result<(Self::Stream, Self::Service)>> + Send,
        >,
    >;

    #[allow(deprecated)]
    fn accept(&self, mut stream: tokio::net::TcpStream, service: S) -> Self::Future {
        let tls_acceptor = self.tls_acceptor.clone();
        let connections = self.connections.clone();
        let app = self.app.clone();
        Box::pin(async move {
            let permit = connections.try_acquire_owned().map_err(|_| {
                std::io::Error::new(
                    std::io::ErrorKind::ConnectionAborted,
                    "web connection limit",
                )
            })?;
            let mut buf = [0u8; 1];
            let _ = tokio::time::timeout(Duration::from_secs(10), stream.peek(&mut buf))
                .await
                .map_err(|_| {
                    let _ = stream.set_linger(Some(Duration::ZERO));
                    std::io::Error::new(std::io::ErrorKind::TimedOut, "web connection timed out")
                })??;
            if buf[0] == 0x16 {
                let controller = Arc::new(StealthAbortController::new(&stream));
                register_web_controller(&controller);
                let (tls_stream, service) = tokio::time::timeout(
                    Duration::from_secs(10),
                    tls_acceptor.accept(stream, service),
                )
                .await
                .map_err(|_| {
                    std::io::Error::new(std::io::ErrorKind::TimedOut, "TLS handshake timed out")
                })??;
                Ok((
                    DualProtocolStream::Tls {
                        stream: Box::new(tls_stream),
                        controller: controller.clone(),
                        _permit: permit,
                    },
                    StealthConnectionService {
                        inner: service,
                        controller,
                    },
                ))
            } else {
                let mut http_buf = [0u8; 2048];
                let n =
                    match tokio::time::timeout(Duration::from_secs(5), stream.read(&mut http_buf))
                        .await
                    {
                        Ok(Ok(n)) if n > 0 => n,
                        _ => {
                            let _ = stream.set_linger(Some(Duration::ZERO));
                            drop(stream);
                            return Err(std::io::Error::new(
                                std::io::ErrorKind::ConnectionReset,
                                "plain HTTP rejected",
                            ));
                        }
                    };
                let req_str = String::from_utf8_lossy(&http_buf[..n]);
                let first_line = req_str.lines().next().unwrap_or("");
                let mut parts = first_line.split_whitespace();
                let _method = parts.next().unwrap_or("");
                let raw_path = parts.next().unwrap_or("");

                let host_header = req_str
                    .lines()
                    .find(|l| l.to_ascii_lowercase().starts_with("host:"))
                    .and_then(|l| l.split_once(':'))
                    .map(|(_, h)| h.trim())
                    .unwrap_or("");

                if is_valid_secret_path(raw_path, &app).await {
                    let public_ip = app.public_ip.read().await.clone();
                    let target_host = match parse_host_header(host_header) {
                        Some((name, _))
                            if name.parse::<std::net::IpAddr>().is_ok() || name == public_ip =>
                        {
                            if host_header.contains(':')
                                || app.web_port == 443
                                || app.web_port == 80
                            {
                                host_header.to_string()
                            } else {
                                format!("{}:{}", host_header, app.web_port)
                            }
                        }
                        _ => {
                            let ip_str = if !public_ip.is_empty() && public_ip != "0.0.0.0" {
                                public_ip
                            } else {
                                "127.0.0.1".to_string()
                            };
                            if app.web_port == 443 {
                                ip_str
                            } else {
                                format!("{}:{}", ip_str, app.web_port)
                            }
                        }
                    };

                    let redirect_url = format!("https://{}{}", target_host, raw_path);
                    let resp = format!(
                        "HTTP/1.1 301 Moved Permanently\r\nLocation: {redirect_url}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    );
                    let _ = tokio::time::timeout(
                        Duration::from_secs(2),
                        stream.write_all(resp.as_bytes()),
                    )
                    .await;
                    let _ = stream.flush().await;
                    let _ = stream.shutdown().await;
                    drop(stream);
                    Err(std::io::Error::other("redirected HTTP to HTTPS"))
                } else {
                    let _ = stream.set_linger(Some(Duration::ZERO));
                    drop(stream);
                    Err(std::io::Error::new(
                        std::io::ErrorKind::ConnectionReset,
                        "plain HTTP rejected (dead RST)",
                    ))
                }
            }
        })
    }
}

#[derive(Deserialize)]
struct LogsToggleRequest {
    active: bool,
}

async fn logs_get(State(state): State<WebState>) -> impl IntoResponse {
    let lines = lock_unpoison(&state.app.logs)
        .iter()
        .cloned()
        .collect::<Vec<String>>();
    Json(serde_json::json!({
        "path": state.app.log_file_path.to_string_lossy(),
        "active": state.app.logging_active.load(Ordering::Relaxed),
        "lines": lines
    }))
}

async fn logs_toggle(
    State(state): State<WebState>,
    Json(request): Json<LogsToggleRequest>,
) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        db.logging_active = Some(request.active);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    state
        .app
        .logging_active
        .store(request.active, Ordering::Relaxed);

    StatusCode::OK.into_response()
}

async fn logs_clear(State(state): State<WebState>) -> Response {
    lock_unpoison(&state.app.logs).clear();

    let path = state.app.log_file_path.clone();
    tokio::spawn(async move {
        let _ = tokio::fs::remove_file(&path).await;
    });

    StatusCode::OK.into_response()
}

#[derive(Deserialize)]
struct ProfileRequest {
    #[serde(default)]
    name: String,
    port: u16,
    #[serde(default)]
    username: String,
    #[serde(default)]
    password: String,
}

async fn local_proxy_get(State(state): State<WebState>) -> impl IntoResponse {
    let (proxy_state, active, tcp_sessions, udp_flows) = {
        let db = state.app.db.read().await;
        let route = state.app.proxy_route.read().await;
        let active = route.as_ref().is_some_and(|r| r.is_alive());
        let (tcp_sessions, udp_flows) =
            route.as_ref().map(|r| r.stats_snapshot()).unwrap_or((0, 0));
        (db.local_proxy.clone(), active, tcp_sessions, udp_flows)
    };
    let profiles: Vec<serde_json::Value> = proxy_state
        .profiles
        .iter()
        .map(|p| {
            serde_json::json!({
                "id": p.id,
                "name": p.name,
                "port": p.port,
                "username": p.username,
                "password": p.password,
            })
        })
        .collect();
    Json(serde_json::json!({
        "active_profile_id": proxy_state.active_profile_id,
        "profiles": profiles,
        "route_active": active,
        "port_listening": state.app.proxy_port_listening.load(std::sync::atomic::Ordering::Acquire),
        "health_error": crate::read_unpoison(&state.app.proxy_health_error).clone(),
        "tcp_sessions": tcp_sessions,
        "udp_flows": udp_flows,
    }))
}

async fn local_proxy_create(
    State(state): State<WebState>,
    Json(request): Json<ProfileRequest>,
) -> Response {
    if let Err(message) = validate_proxy_profile_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let port = if request.port == 0 {
        crate::model::DEFAULT_LOCAL_PROXY_PORT
    } else {
        request.port
    };
    let profile = crate::model::LocalProxyProfile {
        id: crate::model::LocalProxyProfile::new_id(),
        name: if request.name.is_empty() {
            format!("SOCKS5 :{port}")
        } else {
            request.name
        },
        port,
        username: request.username,
        password: request.password,
    };
    if let Err(error) = crate::proxy_route::validate_config(&profile) {
        return (StatusCode::BAD_REQUEST, error.to_string()).into_response();
    }
    let id = profile.id.clone();
    let revision = {
        let mut db = state.app.db.write().await;
        if db.local_proxy.profiles.len() >= 20 {
            return (StatusCode::BAD_REQUEST, "Too many profiles (max 20)").into_response();
        }
        db.local_proxy.profiles.push(profile);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile created: {id}"),
    );
    let port_listening = crate::proxy_route::port_is_listening(port).await;
    Json(serde_json::json!({ "id": id, "port_listening": port_listening })).into_response()
}

async fn local_proxy_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<ProfileRequest>,
) -> Response {
    if let Err(message) = validate_proxy_profile_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let port = if request.port == 0 {
        crate::model::DEFAULT_LOCAL_PROXY_PORT
    } else {
        request.port
    };
    let temp_profile = crate::model::LocalProxyProfile {
        id: id.clone(),
        name: request.name.clone(),
        port,
        username: request.username.clone(),
        password: request.password.clone(),
    };
    if let Err(error) = crate::proxy_route::validate_config(&temp_profile) {
        return (StatusCode::BAD_REQUEST, error.to_string()).into_response();
    }
    let (was_active, revision) = {
        let mut db = state.app.db.write().await;
        let was_active = db.local_proxy.active_profile_id == id;
        let Some(profile) = db.local_proxy.find_profile_mut(&id) else {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        };
        if !request.name.is_empty() {
            profile.name = request.name;
        }
        profile.port = port;
        profile.username = request.username;
        profile.password = request.password;
        (was_active, state.app.db_persistence.submit(db.clone()))
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if was_active {
        state.app.proxy_trigger.notify_one();
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile updated: {id}"),
    );
    let port_listening = crate::proxy_route::port_is_listening(port).await;
    Json(serde_json::json!({ "updated": true, "port_listening": port_listening })).into_response()
}

async fn local_proxy_delete(State(state): State<WebState>, Path(id): Path<String>) -> Response {
    let (was_active, revision) = {
        let mut db = state.app.db.write().await;
        let was_active = db.local_proxy.active_profile_id == id;
        if !db.local_proxy.remove_profile(&id) {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        }
        (was_active, state.app.db_persistence.submit(db.clone()))
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if was_active {
        state.app.proxy_trigger.notify_one();
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile deleted: {id}"),
    );
    Json(serde_json::json!({ "deleted": true })).into_response()
}

async fn wait_proxy_route_applied(state: &WebState, want_active: bool) -> Result<(), String> {
    tokio::time::sleep(Duration::from_millis(250)).await;
    let deadline = std::time::Instant::now() + Duration::from_secs(8);
    loop {
        let route_active = state.app.proxy_route.read().await.is_some();
        let health_error = read_unpoison(&state.app.proxy_health_error).clone();
        if want_active {
            if route_active && health_error.is_none() {
                return Ok(());
            }
            if let Some(error) = health_error {
                return Err(error);
            }
        } else if !route_active {
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            return Err("route is still applying; check status in a few seconds".to_owned());
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
}

async fn local_proxy_activate(State(state): State<WebState>, Path(id): Path<String>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        if db.local_proxy.find_profile(&id).is_none() {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        }
        db.local_proxy.active_profile_id = id.clone();
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        "Local SOCKS5 settings saved; applying TPROXY route",
    );
    state.app.proxy_trigger.notify_one();
    match wait_proxy_route_applied(&state, true).await {
        Ok(()) => Json(serde_json::json!({ "activated": true })).into_response(),
        Err(error) => (StatusCode::BAD_GATEWAY, error).into_response(),
    }
}

async fn local_proxy_deactivate(State(state): State<WebState>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        db.local_proxy.active_profile_id.clear();
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        "Local SOCKS5 routing disabled; restoring direct route",
    );
    state.app.proxy_trigger.notify_one();
    match wait_proxy_route_applied(&state, false).await {
        Ok(()) => Json(serde_json::json!({ "deactivated": true })).into_response(),
        Err(error) => (StatusCode::BAD_GATEWAY, error).into_response(),
    }
}

async fn logs_download(State(state): State<WebState>) -> impl IntoResponse {
    let path = state.app.log_file_path.clone();
    if let Ok(file) = tokio::fs::File::open(&path).await {
        let body = axum::body::Body::from_stream(tokio_util::io::ReaderStream::new(file));
        let headers = [
            (
                axum::http::header::CONTENT_TYPE,
                "text/plain; charset=utf-8",
            ),
            (
                axum::http::header::CONTENT_DISPOSITION,
                "attachment; filename=\"csqtt.log\"",
            ),
        ];
        (headers, body).into_response()
    } else {
        (axum::http::StatusCode::NOT_FOUND, "Log file not found").into_response()
    }
}

async fn security_headers(mut request: Request<Body>, next: Next) -> Response {
    let controller = request
        .extensions()
        .get::<Arc<StealthAbortController>>()
        .cloned();
    let uri = request.uri().clone();
    let path = uri.path();
    if path.contains("//") {
        let new_path = collapse_consecutive_slashes(path);
        let new_uri_str = if let Some(query) = uri.query() {
            format!("{}?{}", new_path, query)
        } else {
            new_path
        };
        if let Ok(new_uri) = new_uri_str.parse() {
            *request.uri_mut() = new_uri;
        }
    }
    let mut response = next.run(request).await;
    if response.headers().contains_key(STEALTH_ABORT_HEADER) {
        if let Some(controller) = controller {
            controller.abort();
        }
        return response;
    }
    let h = response.headers_mut();
    if !h.contains_key(header::CACHE_CONTROL) {
        h.insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store"),
        );
    }
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        header::HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::X_FRAME_OPTIONS,
        header::HeaderValue::from_static("DENY"),
    );
    h.insert(
        header::REFERRER_POLICY,
        header::HeaderValue::from_static("no-referrer"),
    );
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        header::HeaderValue::from_static(
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'",
        ),
    );
    h.insert(
        header::HeaderName::from_static("strict-transport-security"),
        header::HeaderValue::from_static("max-age=15552000"),
    );
    response
}

pub async fn run(
    app: Arc<App>,
    tls_config: axum_server::tls_rustls::RustlsConfig,
) -> anyhow::Result<()> {
    let state = WebState { app: app.clone() };
    let protected = Router::new()
        .route("/api/stats", get(stats))
        .route("/api/memory/trim", post(memory_trim))
        .route("/api/settings", get(settings_get).post(settings_post))
        .route("/api/clients", get(clients_get).post(clients_create))
        .route(
            "/api/clients/{password}",
            get(client_get).post(client_update).delete(client_delete),
        )
        .route("/api/clients/{password}/toggle", post(client_toggle))
        .route("/api/clients/{password}/unbind", post(client_unbind))
        .route(
            "/api/clients/{password}/reset-traffic",
            post(client_reset_traffic),
        )
        .route("/api/clients/{password}/extend", post(client_extend))
        .route(
            "/api/clients/{password}/sub-link/regenerate",
            post(client_sub_link_regenerate),
        )
        .route("/api/groups", get(groups_get).post(groups_create))
        .route("/api/groups/{id}", put(groups_update).delete(groups_delete))
        .route(
            "/api/groups/{id}/admin-link",
            post(group_admin_link_post).delete(group_admin_link_delete),
        )
        .route(
            "/api/groups/{id}/admin-link/regenerate",
            post(group_admin_link_regenerate),
        )
        .route(
            "/api/groups/{id}/sub-link/regenerate",
            post(group_sub_link_regenerate),
        )
        .route(
            "/api/routing-rules",
            get(routing_rules_get).post(routing_rules_create),
        )
        .route(
            "/api/routing-rules/{id}",
            put(routing_rules_update).delete(routing_rules_delete),
        )
        .route("/api/template-domains", get(template_domains_get))
        .route("/api/logs", get(logs_get))
        .route("/api/logs/toggle", post(logs_toggle))
        .route("/api/logs/clear", post(logs_clear))
        .route("/api/logs/download", get(logs_download))
        .route(
            "/api/local-proxy",
            get(local_proxy_get).post(local_proxy_create),
        )
        .route(
            "/api/local-proxy/profiles/{id}",
            put(local_proxy_update).delete(local_proxy_delete),
        )
        .route("/api/local-proxy/activate/{id}", post(local_proxy_activate))
        .route("/api/local-proxy/deactivate", post(local_proxy_deactivate))
        .route("/api/logout", post(logout))
        .route("/api/logout_all", post(logout_all))
        .route("/api/links", get(api_links_get))
        .route("/api/links/batch-vk", post(api_links_batch_vk))
        .route("/api/clients/{password}/vk", put(api_client_vk_update))
        .route("/api/settings/dns", post(api_settings_dns))
        .route(
            "/api/settings/restart-interval",
            post(api_settings_restart_interval),
        )
        .route(
            "/api/settings/master-password",
            post(api_settings_master_password),
        )
        .route("/api/clients/batch-toggle", post(api_clients_batch_toggle))
        .route("/api/clients/batch-extend", post(api_clients_batch_extend))
        .route(
            "/api/clients/batch-reset-traffic",
            post(api_clients_batch_reset_traffic),
        )
        .route("/api/clients/batch-delete", post(api_clients_batch_delete))
        .route("/api/clients/batch-group", post(api_clients_batch_group))
        .route("/api/groups/{id}/clients", get(group_clients_get))
        .route("/api/diagnostics/dpi", get(api_diagnostics_dpi))
        .route("/api/export", get(api_export_get))
        .route("/api/import", post(api_import_post))
        .route("/api/reboot", post(reboot))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ));

    let panel_routes = Router::new()
        .route("/", get(panel_root))
        .route("/manifest.webmanifest", get(pwa_manifest))
        .route("/sw.js", get(pwa_service_worker))
        .route("/pwa-icon.svg", get(pwa_icon))
        .route("/pwa-icon-192.png", get(pwa_icon_192))
        .route("/pwa-icon-512.png", get(pwa_icon_512))
        .route("/qrcode.min.js", get(qrcode_js))
        .route("/favicon.ico", get(favicon))
        .route("/api/login", post(login))
        .merge(protected);

    let mut router = Router::new();
    let secret = app.web_secret_path.trim().trim_matches('/');
    if secret.is_empty() {
        router = router.merge(panel_routes);
    } else {
        let prefix = format!("/{}", secret);
        let trailing_prefix = format!("{}/", prefix);
        router = router
            .route("/", get(root_with_secret))
            .route(&trailing_prefix, get(panel_root))
            .nest(&prefix, panel_routes);
        router = router
            .route("/manifest.webmanifest", get(pwa_manifest))
            .route("/sw.js", get(pwa_service_worker))
            .route("/pwa-icon.svg", get(pwa_icon))
            .route("/pwa-icon-192.png", get(pwa_icon_192))
            .route("/pwa-icon-512.png", get(pwa_icon_512))
            .route("/qrcode.min.js", get(qrcode_js))
            .route("/favicon.ico", get(favicon));
    }

    let router = router
        .route("/{uuid}/admin", get(group_admin_page))
        .route("/{uuid}/admin/", get(group_admin_page))
        .route("/{uuid}/api/data", get(group_admin_data_get))
        .route("/api/group-admin/{uuid}/data", get(group_admin_data_get))
        .route("/{uuid}/api/settings", post(group_admin_settings_post))
        .route(
            "/api/group-admin/{uuid}/settings",
            post(group_admin_settings_post),
        )
        .route("/{uuid}/api/clients", post(group_admin_client_create))
        .route(
            "/api/group-admin/{uuid}/clients",
            post(group_admin_client_create),
        )
        .route(
            "/{uuid}/api/clients/{password}",
            put(group_admin_client_update).delete(group_admin_client_delete),
        )
        .route(
            "/api/group-admin/{uuid}/clients/{password}",
            put(group_admin_client_update).delete(group_admin_client_delete),
        )
        .route(
            "/{uuid}/api/clients/{password}/toggle",
            post(group_admin_client_toggle),
        )
        .route(
            "/api/group-admin/{uuid}/clients/{password}/toggle",
            post(group_admin_client_toggle),
        )
        .route(
            "/{uuid}/api/clients/{password}/reset-traffic",
            post(group_admin_client_reset_traffic),
        )
        .route(
            "/api/group-admin/{uuid}/clients/{password}/reset-traffic",
            post(group_admin_client_reset_traffic),
        )
        .route(
            "/{uuid}/api/clients/{password}/extend",
            post(group_admin_client_extend),
        )
        .route(
            "/api/group-admin/{uuid}/clients/{password}/extend",
            post(group_admin_client_extend),
        )
        .route(
            "/{uuid}/api/clients/{password}/unbind",
            post(group_admin_client_unbind),
        )
        .route(
            "/api/group-admin/{uuid}/clients/{password}/unbind",
            post(group_admin_client_unbind),
        )
        .route("/{uuid}/sub", get(client_sub_page))
        .route("/{uuid}/sub/", get(client_sub_page))
        .route("/{uuid}/json", get(universal_sub_json))
        .route("/{uuid}/json/", get(universal_sub_json))
        .route("/{uuid}/sub.json", get(client_sub_json))
        .route("/api/sub/{uuid}", get(client_sub_json))
        .route("/api/sub/{uuid}/", get(client_sub_json))
        .route("/{uuid}/sub-group", get(group_sub_page))
        .route("/{uuid}/sub-group/", get(group_sub_page))
        .route("/{uuid}/sub-group.json", get(group_sub_json))
        .route("/api/sub-group/{uuid}", get(group_sub_json))
        .route("/api/sub-group/{uuid}/", get(group_sub_json))
        .fallback(fallback_reject)
        .layer(DefaultBodyLimit::max(WEB_BODY_LIMIT_BYTES))
        .layer(middleware::from_fn(security_headers))
        .with_state(state);

    tokio::spawn(web_session_janitor(app.clone()));
    tokio::spawn(web_slowloris_watchdog());
    println!("[WEB] HTTP/HTTPS listening on 0.0.0.0:{}", app.web_port);
    let acceptor = DualAcceptor::new(tls_config, app.clone());
    axum_server::Server::bind(SocketAddr::from(([0, 0, 0, 0], app.web_port)))
        .acceptor(acceptor)
        .serve(router.into_make_service_with_connect_info::<SocketAddr>())
        .await?;
    Ok(())
}

const PWA_MANIFEST: &str = r##"{
  "id": "/",
  "name": "CSQTT",
  "short_name": "CSQTT",
  "description": "Панель управления сервером CSQTT",
  "lang": "ru",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "display_override": ["window-controls-overlay", "standalone"],
  "prefer_related_applications": false,
  "background_color": "#0a0a0c",
  "theme_color": "#0a0a0c",
  "icons": [
    {
      "src": "/pwa-icon-192.png",
      "sizes": "192x192",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/pwa-icon-512.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/pwa-icon.svg",
      "sizes": "any",
      "type": "image/svg+xml",
      "purpose": "any maskable"
    }
  ]
}"##;

const PWA_ICON_SVG: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="122" fill="#0a0a0c"/><defs><linearGradient id="c" x1="128" y1="152" x2="390" y2="360" gradientUnits="userSpaceOnUse"><stop stop-color="#55adff"/><stop offset="1" stop-color="#0875f5"/></linearGradient></defs><path fill="url(#c)" d="M374 151a148 148 0 1 0 0 210l-44-37a91 91 0 1 1 0-136z"/></svg>"##;

static PWA_ICON_192_PNG: LazyLock<Vec<u8>> = LazyLock::new(|| {
    base64::engine::general_purpose::STANDARD
        .decode("iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAAHzUlEQVR42u2d13MTRxyA9RjAyE0YMJaMjXCjkwqpkxDsCS3tKcNMekKfkEmAdCyUgGlppCd/Cbbgn8JYxjOb/Z3uZE0gILD2tHv7PXzvtu777vakLanFi1sUgK+k+BCAAAAIAIAAAAgAgAAACACAAAAIAIAAAAgAgAAACACAAAAIAIAAAAgAgAAACACAAAAIAIAAAAgAgAAACACAAAAIAIAAAAgAgAAACACAAAAIwA2WbRjjcyCAZNG1cUz1v1qssu33W+qJGh7/bZ7HhF9vqUdreOSXCiMfX1O5fcUqfLYEYLXwm05cV9v/nFPb/7gVsE14QPmFh6/Ms/XKrNr686zaosntPRPAZ08ATWONFn7zyevqSS18RBzyR2z+aZ4eHUPneoZSBGCY5fpOL9I/9ddcgA3ybxJ+nFUbQ3r28GQggAaTf62ong6lt1n+jT/Mqg0h+WMl1bGOpwIBLFT8v+eck19Y/30FQiCABxL/GRHfcfmFdcLlWdV/VEIYRWwCuPsYX8RPmvwjl8tV+o6UkJsA/iP+pjG15dT1xMs/fGmelbsKSE4AerjzerEqvi/yD12ssFo/DdpHGBZ5G8DWmru+b/ILg8KFMhH4FoAMeZ79Zw75tfwDIb2HeTfwIgDkv11+Ye35ssodIoJEByBDHuS/s/wReU0bQ6LkBYD89cmfnyirNRNEkKgAkP/+5A84N+N9BCnk91f+/hCfI0ghv9/yC31ndQTDowSA/H7KH+FjBCnkR36h+8AUTwC+50d+AkB+5CcAe0F+xvzeBmDzxLaBj0oqqxeuZ+vYyUFWb63afSYA+Qmg7qGPTfIPHi8F25ZkGrRTQ/fuQgDyE8AdsUX+Rkp/txhkHn+j5V+lx/vI72AANqzkyu2LfyuSDv0LrYTQKPkR3sEAmr2G14btCmUxi8zlR34PA4ju/nHLP6z37MxYthPbipcKyO9TANHdvxny2/qZyNOgXvkZ7zseQDPkzziy/2b2YAn5kxxAM3Zsyzi2+WxWD2+QP6EBbNZjf+S/Nz1hBMifoABk7I/89SMvurzsJiiA2u3JkR+8CwD5wdsA5FQW37/qBI8DiOsXXi4+WBeAHEQXh/wMfcDKAGT4Y1p+hj5gbQBxzOrk7g/WB5CkKc1AAHXRHw5/TC5m4e4PVgdgUv6h42wLDhYHYHoNL3d/sDsAw7s3cLHB+gBMyZ/by8svWByAjP9N7ttDAGB/AAY3reJCg90BvFI0ul0hFxqsDmD9J9eMyS9bFnKhweoATG5Um2X8Dy4EYGqX5h4CANsDMLlFOQGAMwGY2J+fAMCJAEwdTkEAYH0AJk9m6dlDAGB7AAaPJSIAsD4Ak2dyEQA4E4CJA+kIAJwIwNRpjHIQHRcarA5gRO/UYOoo0vwxpkKA5QH0vlw0eg4vFxqsD8DkIdRcaLA6ADmIzuQJ7HLsKBcbrF4SaUp+OXmdAMD6AEzJH8HFBrsDMCj/8KWy6ljH0UFgcQCycN2U/ELfEb4OBQcCMCG/MHRRPwVGeAqAxZvjmpRfWLmLl2FwIAAT8guDmnaeAmBrALKA3aT8gxfKqvcw7wJgaQCdegNbk/IPhPAUAGsPyTMtv7D2PL8LgKUByBpe0/ILuUMMhcDSg7JNyy/kNW0MhQjAxj9K5vCblj8/UVZrJoiAACz8o+RlOA75A87NOB9B65uTKq1B6IQEED0F4pC/P8TVCET+ti+mqywZ2InYSQhA1vLGJb/Qd1ZHMDzqtPytn1cgggQEIMQpf4QrEfyf/EKaCJIRgExhjlP+1cJ3M6r7wylrP5MWLXZ7jfh3kj/9WQUicDwAof9oKVb5e0MkglbLngbpF8brln+pcGpatezn5djpAOQpELf8Qu7bCpmxghV3/da3Ju9f/oj9V5Hd1QAEWczSDPmFbLFCZrTQFPHbRPwvpx9c/lM31JKTGiJwNwChmfILPcKZGdWxs6DSQ6PGhzoifoeI3wj5QxZLBGt5L3AyAFnN1Wz5VwXcDOh6b7KhMaR3jAeI9BENlV84cUMt0hCBgwEIq/Vcfhvk7y7Ms1IYv6ky7+qhig6i/cV7D5VaBkdV645CQOdX01XikH8REbgbgGCj/CsiTt9Uy2vo+maeZcLXOpQamiX/Q59WIAIHA5DFLMi/cPmrvHGVAFyNAPkXKD8RuBlAsI2KXtCC/A2Q3/MIUi7/8dmDJeRvhPwevxOkXP8HsgemkB/5/Q0gWEccRoD8yO9lAMH6AR0B8iO/twFEUSA/8nsbgCDTmJEf+b0NIFgtpefxIz9fd3obQBQB8iO/twFErPhgCvkZ8vgbgCCLWbz+hRf5/Q4gmG+v5+0vf3/KK/kXPXcayQng9hASLz9jfQK45wozvXglkbM6Ge4QwH1NrdYhOC8/4hPAQmnTSxmdXMyC+ATQSJbq9bqd70xaLT8vtwQQz1NBL1rveHvSCvkD6bnbE0DTdmcb3BlsW9Ie7tdjfOsSEZ47PQHYvEmtbGCVfn5cLdUsaMe2SHaEJ4Akwi7NBABAAAAEAEAAAAQAQAAABABAAAAEAEAAAAQAQAAABABAAAAEAEAAAAQAQAAABABAAAAEAEAAAAQAQAAABABAAAAEAEAAAAQAQAAABAAEwIcABABAAAAEAEAAAAQAQAAABACQWP4FTDjEgh+CERkAAAAASUVORK5CYII=")
        .unwrap_or_default()
});

static PWA_ICON_512_PNG: LazyLock<Vec<u8>> = LazyLock::new(|| {
    base64::engine::general_purpose::STANDARD
        .decode("iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAYAAAD0eNT6AAAW3UlEQVR42u3d6Xdc9X3H8Tws4EWyhbBlWbYsY1my8dYlbbqSEEsBg7P0UUOTNE0CYTvgpJBuKZbUJBBCNtLS7S+JPOafCtjY50zvb2auNJJGQsss997v68HrSc7JE1n2+3N/9zfDpx58cF8dAIjlU34IAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAABgAPghAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAAAYAAGAAAD00+aWljs5//3b90/95r6M/avcfG/1hy+z3btdPfnFpjRMtfvaAAQA9dOrLSysey6L+J+/fa/jjLXz6/Xt7jv8Gv7lX/4MtzNy4XZ+4vrTCnx0YAMA2jF6YXwn9Z/4ri/x6798rbPx/P3mvsyuZieuLDSPn5/1ZgwEAYt+M/f1G8NtVKf5X3vt4rV83TTyz2GAUgAEAlQ/+xdc/qH/mv++vChr/5PI606/VDAIwAKAa7+5T8P80C31O/DvHf8WvPq5fajnzaq1+PBsEfpfAAIBSPOW3B1/8dxf/TsazMXDY6QAYAFCU6E9l0f+zTaIv/t2J/8V2vzQGwACAQUb/f+43iX9f49/ugjEABgD02proi38h4r/e+NPuDIABAF1wOov+pTc+2Bh+8S9c/C/8YtXpV2rGABgAsLvw/3mn6It/4eP/2DqGABgAsKVHsnf76Wk/hV/8qxH/dulU4NA5dwXAAID28P9gNfziX734n09+3mQIgAFA9GP+r2TH/P97v0n8Q8R/vbFrXg+AAUDM8It/2Pifa2MIgAFApPCLv/gn764yBMAAoOrhF3/xXxf/prv12czYtQV/d8AAoMyX+y6ny33iL/47iH/u1MvpsuCcv0tgAFC28P9Fp/CLv/hvI/5rhsBLhgAYABReHn7xF/9uxH/2Z3frMy2T2RDwdwwMAAr4nj8Pv/iLf6/j3879ADAAKNhxv/iLf6/jnzuZXgvMei0ABgCFeOoXf/HvR/zPJu80HX3KaQAYAPT1qX99+MVf/Psd/3ZOAzAAoI+X/MRf/IsQ/2T6neZrAX9HMQCg20/9Fzs/9Yu/+Bch/u2GnQZgAECX3vX/9ZL4i38p4t/w07v1I0+6G4ABAHt66t/syF/8xb+o8c+deLHmNAADAHZ15P9/98Vf/EsZ/zNtjAAMANimK+mpX/zFvwLxz6XTAH+3MQBgi6d+8Rf/qsW/4e279YkXvBLAAICO8f/LFH7xF/8Kxv/RNkYABgC03fIXf/GPEP+cTwlgAOB9f3bkL/7iHyn+ufRKwL8BGACIv/iLf6D4n24xAjAACPm+X/zFP3L8G95qGnIvAAMA8Rd/8Y8VfyMAAwDxF3/xDxr/qRYjAAMA8Rd/8Q8WfyMAA4DKfsxP/MVf/LeO/9Rbd+pTP7lTH/2CjwliAFCRm/7iL/7iv73458afv+XfEAwAxF/8xT9S/E+1GAEYAIi/+It/sPgbARgAiL/4i3/Q+BsBGACIv/iLf9D4J5OZY0YABgDiL/7iHyv+kz9uMgIwAPBRP/EX/2Dxz43O+4ggBgC+5Ef8xT9U/HNDM74sCAMA8Rd/8Q8V/5MtRgAGAOIv/uIfLP4NPzICMAAQf/EX/3DxNwAwABgI8Rd/8Rd/MACCftxP/MVf/Psf/7Hnbok/BgDiL/7iHy3+/h3CAGBgn/UXf/EXf/EHAyDYpT/xF3/xF38wAMRf/MVf/Hsc/4d98x8GAIN87y/+4i/+PucPBoD4i7/4i7/4gwFQ5aN/8Rd/8Rd/MADEX/zFX/x7GH+X/TAAGLjL6ehf/EsX/zOv1urjzyyucfj8/I7//NP/Z/zpxYZjybXF+ulXauIv/mAAVP3z/uJf7Pin0B/P4p4M6vdkLBsFyVQ2DMRf/DEAqMLRv/gXLv6Djv3ORsGC+O8g/j7mhwFAIYh/MeI//VqtPpEFf2QXx/dFcejcXGMMnHq5Jv6bxN9lPwwACv3eX/z7E/88+lX9/UpjYPKlmviLPwYAhTr6vzAv/gOIf9Wjv50xIP5gAFCwo3/x7138y368383XBEefWggT/7HnXfbDAKBIt/6/siT+fYp/xKf97WofAuIPBgADOPoX/+7G/+yNmvDvwJFsCJzMXg+IPxgA9PHin/h3L/4p/I75d294dq45BEoefx/zwwCg8Ef/4t+9+E9c98TftROBJxdKG3+X/TAAKPzFP/HvTvyFv7dDQPzBAKCLR//iv/f4p+N+v1f9ceLFmviDAcBeL/6J/97j7z3/YO4HFDH+LvthAFCKp3/x31v8J64v+X0qwGsB8QcDgB08/Yv/3uLvqb+YpwHiDwYAWzz9i//u4z9z47bfo4KaeKE2kPj7mB8GgB9CKT72J/67j7+n/uIbyk4D+hl/l/3AACgF8d9d/NNTv/iXawRMfLcm/mAAsOnTv/hvK/5+f8rpeDYCxB8MAE//4r/j+LvlX36j2acEuhl/l/3AACj307/4f2L8HflX65WA+IMB4Olf/MU/8AgQfzAAYj79i7/4Bx8B4g8GQLynf/EXfzqOgK3i77IfGACl/dY/8Rd/Nh8B4g8GQCVdSt/6J/7iT8cRIP5gAFT36V/8xZ/NR0AW+fXhP5a97xd/MADK/fT/xgfiL/7sYAQcc9kPDIBKXP4Tf/Fn2yNA/MEAqMZH/768JP7iD2AARHz6F39H/wAGQLWNZt/8J/4b+d0AMADiHP+Lv6N/AAMg2PG/+Pu8P4ABEOz4X/wbrjj6BzAAwhz/i/9K/B39AxgAMY7/xX8l/mdv1PxOABgAMY7/xb8Z/yvvfex3AsAAiOFUdvwv/s34T1xf9DsBYADEIP7N+Hv6BzAAwg+AiPH39A9gAIQ+/o8Yfxf/AAyAUC6+/kH4+Cc+9gdgAIQ9/o8af0//AAZA2I//RY1/493/M979AxgAAd//R47/lV+7+Q9gAAR8/x89/p7+AQyAcKLH39M/gAEQ8v1/9Ph7+gcwAEK+/48c/8RH/wAMgJgDIHD8p1/z0T8AAyDi+//A8b/s+B/AAIg7AOLG/7LLfwAGQNgLgIHj7/gfwAAI/P4/Zvwd/wMYAOEHQMT4O/4HMABCv/+PGn/H/wAGQFhR4+/4H8AAMAACxv+yL/8BMAAiv/+PGv/Lv/L+H8AAiD4AAsb/uON/AAMg9AAIGP9LBgCAARDZY9+/HTL+lxz/AxgAoS8ABo2/AQBgAIQfABHjf+ZVn/8HMAACixh/7/8BDAADIGD8DQAAAyC0yS8thYy/9/8ABoABEDD+BgCAAWAABIz/RQMAwAAwAOLF3ycAAAwAAyBY/JNxFwABDIDoAyBa/A0AAAMgvPPZ1wBHi//FXxoAAAZAcBHjbwAAGAAGQMD4J4fPz/vzBzAADIBI8U/82QMYAAZAsPhfMAAADAADIF78DQAAA8AACBh/AwDAAAgvYvwNAAADwAAIGH8DAMAAMAACxv/CLwwAAAMg+gAIGH8DAMAAMAACxt8AADAADICA8X/MAAAwAAyAePE3AAAMAAMgYPwNAAADILyI8U/8x4AADAADIFj8k/Gn/eeAAQyAwGa/dztc/A0AAAMgvJNfXAoX/+SYAQBgABgAseJ/Pg2AawYAgAEQfQAEi//5n39cP/1KzZ8/gAEQewBEi3/Onz+AARDWiS0GQJXjbwAAGAAGQMD4GwAABkB4EeOfjLkICGAAGACx4n/OAAAwAAyAePFPpnwSAMAAiD4AosW/4V33AAAMgMBmbtwOGX8DAMAACG3i+lLI+CfuAQAYAAZAsPg3B8CC3wEAA8AAiBT/c+/erc9m/A4AGABhRY1/cujcnN8BAAMg7gCIGP/EawAAAyCsqPFPTr3s+wAADICw9wAWQ8Z/1j0AAAPAAIgZ/9mfeQ0AYAAENXJ+Pmz8ZzKTL3kNAGAARL0HEDT+Ob8DAAZA6AEQMf4zXgMAGABh7wE8sxg2/l4DABgABkDA+Od8KRCAARDyImDk+J/NHH3KawAAAyCgyPE/+06T3wMAAyCc6ddqoeOfOAUAMABi3gMIHH+nAAAGQNh7ANHjnxxxCgBgAEQTPf7TmZM+EghgAERz5tVa6Pjnhmd9JBDAAAjkeHYPIHr8nQIAGAChXwNEjX/DT7O7AE+6CwBgAAQbANHjn/P7AGAAhDGevQYQ/7v1M04BAAyASA5nHwcU/1V+JwAMgDDEf9WJF10IBDAAIr0GEP8VPhYIYADEeQ0g/k1v360/+rZXAQAGQBDivxr/xIVAAAMgzGsA8V/LqwAAAyDEawDx38jvBoABEOM1gPivMfGCTwUAGABVfw3w9KL4tzndMuRVAIABUPlTAPFfE/+Gt4wAAAOg4k6/UhP/dfFPJr7rVQCAAVDx1wDivzb+ueNGAIABUGXivzH+Uy2jvh8AwACIcAog/qvxz7kPAGAAVPoUQPw3xn/qrTv1qZ/cMQJYse/M1frBry/7WYABUJ3LgOLfOf45I4AU/+F//rA+lDlgBIABUAWHzs2L/xbxP9ViBIj/UJs0AtL/7ucDBkClTgHEf238jQDxH9qEEQAGQGVOAcS/c/yNAPHf4J8+rB/MPGQEgAFQZuL/yfFPJtMImDECxL8Z/4NGABgAZTd2bVH8txH/yR83GQHiv96Br7kcCAZASYn/9uJvBIj/ZvYbAWAAlPYUQPy3FX8jQPw3nAC0GAFgAJTzFED8tx3/ky0Pz/va4LI78NmbXYl/wz9mI+BvjQAwAEp5CiD+241/w4/u1Meeu+X3p6TSt/t1M/7tXA4EA6BUxH9n8c+lEeCVQPm+2rdX8W+cBBgBYACU6xRgQfx3GP/cicxBI6A07/t7Hf/9RgAYAGVz6uWa+O8i/jmvBIp/5N+v+Df8IPvWwMdv+vljALCvBN8OOCf+u4x/O6cBxXzq73f8c/uedTkQA4AynAK8VBP/PcT/xL/fqU9kRnxKYPC3/D93c/DxzxkBGACU4RRA/PcW/3ZOAwb01P8vHxYn/kYABgBlMdnhFED8dx7/3NHvuBvQt3f931guZvwbftfwoMuBGAAUmfh3J/7J8WQpey0w57VAT4/7U/gLHv+HkjeyEfCoEYABQIE/Fij+3Yt/O0OgR+EvSfxzRgAGAIV1MnsVIP7djX/uSPZa4MBZ9wP29IU++XF/CeO/MgL+6k1/nhgAFPBC4Oyc+Pcg/uNt0hA47ERgR0/8G8Jf0vg3BkDy7G/92WIAUDxHn1oQ/x7Ff8Vi0+GrhsBW4T+0PvpViH/uq0YABgAFJP69j39yrOVQNgS8Hmge8+fhr3T8X28xAjAAKOKrAPHvT/yPLX604pFvLzfGQMSn/aHsmD8Pf4j4t3M5EAOAol0IFP/+xb9h4aP6WMvot6o9Bg48sTH6EeP/QIsRgAFAoYj/YOK/Xj4GyvyaYN/01Ub0h1P0//XDjuGPGn8jAAOAwhlOrwLEf6DxT462u/lRYwyU4XQgBT85nAU/J/6d478yAnxMEAOAojjy5IL4Fyj+7Y60jPx99jG5bBAMD3AUHHxiIdN8wm8PvvhvP/4P/MPv6r+X/I3LgRgAFMSJF2viX9D4r/HmqkcyI99crg99fmGN/bt4jbB/eq4+lAV+qBH5pkN/t1wf+eFHqzaJvvjvMP5GAAYARXsVIP7liv9mRpN/6+zhdj/caGQz4t/d+BsBGAAUdgSIv/iLf2/j38blQAwAivEqQPzFX/z7Fn8jAAOAwph4oSb+4i/+fYy/EYABQGFeBYi/+It/f+Ofe8DHBDEAKNIIEH/xF//ex9/lQAwACvP9AOIv/uLf5/gbARgAFOU+gPiLv/j3Of5GAAYAhRkB4i/+4t/f+LePAJcDMQAYFPEXf/EfQPx9QgADgEEbyi4Fir/4i/9g4m8EYAAw8BEg/uIv/oOJvwGAAUBBRoD4i7/4iz8GAPFGgPiLv/iLPwYA8Yx+YUH8xV/8+8A3A2IAUDjjz98Sf/EX/15/BNC/NRgAlGUEiL/4i7/4YwAQbASIv/iLv/hjABBsBIi/+Iu/+GMAENCxNALEX/zFX/wxADACxF/8xV/8MQCI8hHB+QXxF3/x91E/DABCflnQzJz4i7/4+5IfDADCjgDxF3/xF38MAIwA8Rd/8Rd/DACCjQDxF3/xF38MAAIae+6W+It/7Pi76Y8BgBEg/uIv/mAAEMpI9jFB8Rf/SPH3MT8MAGg5mN0LEH/xjxB/7/sxAKDDCDj6nVviL/7VjH868hd/DADYXD4CxF/8KxV/f7cxAGB7pwHiL/5ViL+nfgwA2OkIODtXP5KdBoi/+Jcy/o78MQBgj58SmFsQf/EvVfzd8scAgC45kJ0GiL/4lyH+nvoxAKAH0isB8Rf/QsbfRT8MAOjDaYD4i3+B4u+pHwMA+ujQ1QXxF/+Bxt+7fjAAGOBpwCPfXhZ/8e9v/N3wBwOAAp0GiL/49yH+nvrBAKCARr+1LP7i75IfGABEfS3QPgTEX/wd94MBQLAh8HA2BMRf/IUfDACC3g8Qf/H3nh8MAIIPAfEXf+EHA4CAhrMhIP7iL/xgABB5CIh/6PgLPxgABL8sOPLNZfGPEn+X+8AAgHb7OwwB8a9Q/IUfDAD4JEOfXxD/isTfMT8YALDzIfDEQv1wdiog/iWLf/a0L/xgAEDXxoD4Fzv+og8GAPTursD0XHMMiH8h4t+Ivnf7YADAIMaA+Pc3/qIPBgAUxr5sDBxsHwPi39X4iz4YAFCO7xd44mZ9+BvL4r/b+LvIBwYAlP904OrKIBD/TeL/1exz+p7ywQCASg+CM9kg+NzNhqjxb8Re8MEAAKNgdRRULv5iDwYAsMNPGnz25ooDX18ubvyf/e1q6L27BwMA6PHJQTYMOtn/teW9x//Z5fpDj9/MvNnw4OMCDxgAAGAA+CEAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAIABAAAYAACAAQAAGAAAgAEAABgAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAgAHgBwEABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAIAB4IcAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAYAAAAAYAAGAAAAAGAABgAAAABgAAGAAAQAz/Dxw3WBbFbYk0AAAAAElFTkSuQmCC")
        .unwrap_or_default()
});

const PWA_SERVICE_WORKER: &str = r##"const CACHE_NAME = 'csqtt-panel-shell-2.5.0-pwa4';
const base = self.location.pathname.replace(/\/sw\.js$/, '');
const SHELL_ASSETS = ['/manifest.webmanifest', '/pwa-icon.svg', '/pwa-icon-192.png', '/pwa-icon-512.png', '/qrcode.min.js'].map(p => (base ? base : '') + p);

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(CACHE_NAME);
      await cache.addAll(SHELL_ASSETS);
    } catch (_) {}
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(name => name !== CACHE_NAME).map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname.includes('/api/')) return;

  if (SHELL_ASSETS.includes(url.pathname)) {
    event.respondWith(caches.match(request).then(cached => cached || fetch(request)));
  }
});
"##;

const QRCODE_JS: &str = r##"var qrcode=function(){function i(t,r){function a(t,r){g=function(t){for(var r=new Array(t),e=0;e<t;e+=1){r[e]=new Array(t);for(var n=0;n<t;n+=1)r[e][n]=null}return r}(l=4*u+17),e(0,0),e(l-7,0),e(0,l-7),i(),o(),v(t,r),7<=u&&h(t),null==n&&(n=w(u,f,c)),d(n,r)}var u=t,f=y[r],g=null,l=0,n=null,c=[],s={},e=function(t,r){for(var e=-1;e<=7;e+=1)if(!(t+e<=-1||l<=t+e))for(var n=-1;n<=7;n+=1)r+n<=-1||l<=r+n||(g[t+e][r+n]=0<=e&&e<=6&&(0==n||6==n)||0<=n&&n<=6&&(0==e||6==e)||2<=e&&e<=4&&2<=n&&n<=4)},o=function(){for(var t=8;t<l-8;t+=1)null==g[t][6]&&(g[t][6]=t%2==0);for(var r=8;r<l-8;r+=1)null==g[6][r]&&(g[6][r]=r%2==0)},i=function(){for(var t=B.getPatternPosition(u),r=0;r<t.length;r+=1)for(var e=0;e<t.length;e+=1){var n=t[r],o=t[e];if(null==g[n][o])for(var i=-2;i<=2;i+=1)for(var a=-2;a<=2;a+=1)g[n+i][o+a]=-2==i||2==i||-2==a||2==a||0==i&&0==a}},h=function(t){for(var r=B.getBCHTypeNumber(u),e=0;e<18;e+=1){var n=!t&&1==(r>>e&1);g[Math.floor(e/3)][e%3+l-8-3]=n}for(e=0;e<18;e+=1){n=!t&&1==(r>>e&1);g[e%3+l-8-3][Math.floor(e/3)]=n}},v=function(t,r){for(var e=f<<3|r,n=B.getBCHTypeInfo(e),o=0;o<15;o+=1){var i=!t&&1==(n>>o&1);o<6?g[o][8]=i:o<8?g[o+1][8]=i:g[l-15+o][8]=i}for(o=0;o<15;o+=1){i=!t&&1==(n>>o&1);o<8?g[8][l-o-1]=i:o<9?g[8][15-o-1+1]=i:g[8][15-o-1]=i}g[l-8][8]=!t},d=function(t,r){for(var e=-1,n=l-1,o=7,i=0,a=B.getMaskFunction(r),u=l-1;0<u;u-=2)for(6==u&&(u-=1);;){for(var f=0;f<2;f+=1)if(null==g[n][u-f]){var c=!1;i<t.length&&(c=1==(t[i]>>>o&1)),a(n,u-f)&&(c=!c),g[n][u-f]=c,-1==(o-=1)&&(i+=1,o=7)}if((n+=e)<0||l<=n){n-=e,e=-e;break}}},w=function(t,r,e){for(var n=b.getRSBlocks(t,r),o=M(),i=0;i<e.length;i+=1){var a=e[i];o.put(a.getMode(),4),o.put(a.getLength(),B.getLengthInBits(a.getMode(),t)),a.write(o)}var u=0;for(i=0;i<n.length;i+=1)u+=n[i].dataCount;if(o.getLengthInBits()>8*u)throw"code length overflow. ("+o.getLengthInBits()+">"+8*u+")";for(o.getLengthInBits()+4<=8*u&&o.put(0,4);o.getLengthInBits()%8!=0;)o.putBit(!1);for(;!(o.getLengthInBits()>=8*u||(o.put(236,8),o.getLengthInBits()>=8*u));)o.put(17,8);return function(t,r){for(var e=0,n=0,o=0,i=new Array(r.length),a=new Array(r.length),u=0;u<r.length;u+=1){var f=r[u].dataCount,c=r[u].totalCount-f;n=Math.max(n,f),o=Math.max(o,c),i[u]=new Array(f);for(var g=0;g<i[u].length;g+=1)i[u][g]=255&t.getBuffer()[g+e];e+=f;var l=B.getErrorCorrectPolynomial(c),h=C(i[u],l.getLength()-1).mod(l);a[u]=new Array(l.getLength()-1);for(g=0;g<a[u].length;g+=1){var s=g+h.getLength()-a[u].length;a[u][g]=0<=s?h.getAt(s):0}}var v=0;for(g=0;g<r.length;g+=1)v+=r[g].totalCount;var d=new Array(v),w=0;for(g=0;g<n;g+=1)for(u=0;u<r.length;u+=1)g<i[u].length&&(d[w]=i[u][g],w+=1);for(g=0;g<o;g+=1)for(u=0;u<r.length;u+=1)g<a[u].length&&(d[w]=a[u][g],w+=1);return d}(o,n)};s.addData=function(t,r){var e=null;switch(r=r||"Byte"){case"Numeric":e=x(t);break;case"Alphanumeric":e=m(t);break;case"Byte":e=L(t);break;case"Kanji":e=D(t);break;default:throw"mode:"+r}c.push(e),n=null},s.isDark=function(t,r){if(t<0||l<=t||r<0||l<=r)throw t+","+r;return g[t][r]},s.getModuleCount=function(){return l},s.make=function(){if(u<1){for(var t=1;t<40;t++){for(var r=b.getRSBlocks(t,f),e=M(),n=0;n<c.length;n++){var o=c[n];e.put(o.getMode(),4),e.put(o.getLength(),B.getLengthInBits(o.getMode(),t)),o.write(e)}var i=0;for(n=0;n<r.length;n++)i+=r[n].dataCount;if(e.getLengthInBits()<=8*i)break}u=t}a(!1,function(){for(var t=0,r=0,e=0;e<8;e+=1){a(!0,e);var n=B.getLostPoint(s);(0==e||n<t)&&(t=n,r=e)}return r}())},s.createTableTag=function(t,r){t=t||2;var e="";e+='<table style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: "+(r=void 0===r?4*t:r)+"px;",e+='">',e+="<tbody>";for(var n=0;n<s.getModuleCount();n+=1){e+="<tr>";for(var o=0;o<s.getModuleCount();o+=1)e+='<td style="',e+=" border-width: 0px; border-style: none;",e+=" border-collapse: collapse;",e+=" padding: 0px; margin: 0px;",e+=" width: "+t+"px;",e+=" height: "+t+"px;",e+=" background-color: ",e+=s.isDark(n,o)?"#000000":"#ffffff",e+=";",e+='"/>';e+="</tr>"}return e+="</tbody>",e+="</table>"},s.createSvgTag=function(t,r,e,n){var o={};"object"==typeof t&&(t=(o=t).cellSize,r=o.margin,e=o.alt,n=o.title),t=t||2,r=void 0===r?4*t:r,(e="string"==typeof e?{text:e}:e||{}).text=e.text||null,e.id=e.text?e.id||"qrcode-description":null,(n="string"==typeof n?{text:n}:n||{}).text=n.text||null,n.id=n.text?n.id||"qrcode-title":null;var i,a,u,f,c=s.getModuleCount()*t+2*r,g="";for(f="l"+t+",0 0,"+t+" -"+t+",0 0,-"+t+"z ",g+='<svg version="1.1" xmlns="http://www.w3.org/2000/svg"',g+=o.scalable?"":' width="'+c+'px" height="'+c+'px"',g+=' viewBox="0 0 '+c+" "+c+'" ',g+=' preserveAspectRatio="xMinYMin meet"',g+=n.text||e.text?' role="img" aria-labelledby="'+p([n.id,e.id].join(" ").trim())+'"':"",g+=">",g+=n.text?'<title id="'+p(n.id)+'">'+p(n.text)+"</title>":"",g+=e.text?'<description id="'+p(e.id)+'">'+p(e.text)+"</description>":"",g+='<rect width="100%" height="100%" fill="white" cx="0" cy="0"/>',g+='<path d="',a=0;a<s.getModuleCount();a+=1)for(u=a*t+r,i=0;i<s.getModuleCount();i+=1)s.isDark(a,i)&&(g+="M"+(i*t+r)+","+u+f);return g+='" stroke="transparent" fill="black"/>',g+="</svg>"},s.createDataURL=function(o,t){o=o||2,t=void 0===t?4*o:t;var r=s.getModuleCount()*o+2*t,i=t,a=r-t;return I(r,r,function(t,r){if(i<=t&&t<a&&i<=r&&r<a){var e=Math.floor((t-i)/o),n=Math.floor((r-i)/o);return s.isDark(n,e)?0:1}return 1})},s.createImgTag=function(t,r,e){t=t||2,r=void 0===r?4*t:r;var n=s.getModuleCount()*t+2*r,o="";return o+="<img",o+=' src="',o+=s.createDataURL(t,r),o+='"',o+=' width="',o+=n,o+='"',o+=' height="',o+=n,o+='"',e&&(o+=' alt="',o+=p(e),o+='"'),o+="/>"};var p=function(t){for(var r="",e=0;e<t.length;e+=1){var n=t.charAt(e);switch(n){case"<":r+="&lt;";break;case">":r+="&gt;";break;case"&":r+="&amp;";break;case'"':r+="&quot;";break;default:r+=n}}return r};return s.createASCII=function(t,r){if((t=t||1)<2)return function(t){t=void 0===t?2:t;var r,e,n,o,i,a=1*s.getModuleCount()+2*t,u=t,f=a-t,c={"██":"█","█ ":"▀"," █":"▄","  ":" "},g={"██":"▀","█ ":"▀"," █":" ","  ":" "},l="";for(r=0;r<a;r+=2){for(n=Math.floor((r-u)/1),o=Math.floor((r+1-u)/1),e=0;e<a;e+=1)i="█",u<=e&&e<f&&u<=r&&r<f&&s.isDark(n,Math.floor((e-u)/1))&&(i=" "),u<=e&&e<f&&u<=r+1&&r+1<f&&s.isDark(o,Math.floor((e-u)/1))?i+=" ":i+="█",l+=t<1&&f<=r+1?g[i]:c[i];l+="\n"}return a%2&&0<t?l.substring(0,l.length-a-1)+Array(1+a).join("▀"):l.substring(0,l.length-1)}(r);t-=1,r=void 0===r?2*t:r;var e,n,o,i,a=s.getModuleCount()*t+2*r,u=r,f=a-r,c=Array(t+1).join("██"),g=Array(t+1).join("  "),l="",h="";for(e=0;e<a;e+=1){for(o=Math.floor((e-u)/t),h="",n=0;n<a;n+=1)i=1,u<=n&&n<f&&u<=e&&e<f&&s.isDark(o,Math.floor((n-u)/t))&&(i=0),h+=i?c:g;for(o=0;o<t;o+=1)l+=h+"\n"}return l.substring(0,l.length-1)},s.renderTo2dContext=function(t,r){r=r||2;for(var e=s.getModuleCount(),n=0;n<e;n++)for(var o=0;o<e;o++)t.fillStyle=s.isDark(n,o)?"black":"white",t.fillRect(n*r,o*r,r,r)},s}i.stringToBytes=(i.stringToBytesFuncs={default:function(t){for(var r=[],e=0;e<t.length;e+=1){var n=t.charCodeAt(e);r.push(255&n)}return r}}).default,i.createStringToBytes=function(u,f){var i=function(){function t(){var t=r.read();if(-1==t)throw"eof";return t}for(var r=S(u),e=0,n={};;){var o=r.read();if(-1==o)break;var i=t(),a=t()<<8|t();n[String.fromCharCode(o<<8|i)]=a,e+=1}if(e!=f)throw e+" != "+f;return n}(),a="?".charCodeAt(0);return function(t){for(var r=[],e=0;e<t.length;e+=1){var n=t.charCodeAt(e);if(n<128)r.push(n);else{var o=i[t.charAt(e)];"number"==typeof o?(255&o)==o?r.push(o):(r.push(o>>>8),r.push(255&o)):r.push(a)}}return r}};var r,t,a=1,u=2,o=4,f=8,y={L:1,M:0,Q:3,H:2},e=0,n=1,c=2,g=3,l=4,h=5,s=6,v=7,B=(r=[[],[6,18],[6,22],[6,26],[6,30],[6,34],[6,22,38],[6,24,42],[6,26,46],[6,28,50],[6,30,54],[6,32,58],[6,34,62],[6,26,46,66],[6,26,48,70],[6,26,50,74],[6,30,54,78],[6,30,56,82],[6,30,58,86],[6,34,62,90],[6,28,50,72,94],[6,26,50,74,98],[6,30,54,78,102],[6,28,54,80,106],[6,32,58,84,110],[6,30,58,86,114],[6,34,62,90,118],[6,26,50,74,98,122],[6,30,54,78,102,126],[6,26,52,78,104,130],[6,30,56,82,108,134],[6,34,60,86,112,138],[6,30,58,86,114,142],[6,34,62,90,118,146],[6,30,54,78,102,126,150],[6,24,50,76,102,128,154],[6,28,54,80,106,132,158],[6,32,58,84,110,136,162],[6,26,54,82,110,138,166],[6,30,58,86,114,142,170]],(t={}).getBCHTypeInfo=function(t){for(var r=t<<10;0<=d(r)-d(1335);)r^=1335<<d(r)-d(1335);return 21522^(t<<10|r)},t.getBCHTypeNumber=function(t){for(var r=t<<12;0<=d(r)-d(7973);)r^=7973<<d(r)-d(7973);return t<<12|r},t.getPatternPosition=function(t){return r[t-1]},t.getMaskFunction=function(t){switch(t){case e:return function(t,r){return(t+r)%2==0};case n:return function(t,r){return t%2==0};case c:return function(t,r){return r%3==0};case g:return function(t,r){return(t+r)%3==0};case l:return function(t,r){return(Math.floor(t/2)+Math.floor(r/3))%2==0};case h:return function(t,r){return t*r%2+t*r%3==0};case s:return function(t,r){return(t*r%2+t*r%3)%2==0};case v:return function(t,r){return(t*r%3+(t+r)%2)%2==0};default:throw"bad maskPattern:"+t}},t.getErrorCorrectPolynomial=function(t){for(var r=C([1],0),e=0;e<t;e+=1)r=r.multiply(C([1,w.gexp(e)],0));return r},t.getLengthInBits=function(t,r){if(1<=r&&r<10)switch(t){case a:return 10;case u:return 9;case o:case f:return 8;default:throw"mode:"+t}else if(r<27)switch(t){case a:return 12;case u:return 11;case o:return 16;case f:return 10;default:throw"mode:"+t}else{if(!(r<41))throw"type:"+r;switch(t){case a:return 14;case u:return 13;case o:return 16;case f:return 12;default:throw"mode:"+t}}},t.getLostPoint=function(t){for(var r=t.getModuleCount(),e=0,n=0;n<r;n+=1)for(var o=0;o<r;o+=1){for(var i=0,a=t.isDark(n,o),u=-1;u<=1;u+=1)if(!(n+u<0||r<=n+u))for(var f=-1;f<=1;f+=1)o+f<0||r<=o+f||0==u&&0==f||a==t.isDark(n+u,o+f)&&(i+=1);5<i&&(e+=3+i-5)}for(n=0;n<r-1;n+=1)for(o=0;o<r-1;o+=1){var c=0;t.isDark(n,o)&&(c+=1),t.isDark(n+1,o)&&(c+=1),t.isDark(n,o+1)&&(c+=1),t.isDark(n+1,o+1)&&(c+=1),0!=c&&4!=c||(e+=3)}for(n=0;n<r;n+=1)for(o=0;o<r-6;o+=1)t.isDark(n,o)&&!t.isDark(n,o+1)&&t.isDark(n,o+2)&&t.isDark(n,o+3)&&t.isDark(n,o+4)&&!t.isDark(n,o+5)&&t.isDark(n,o+6)&&(e+=40);for(o=0;o<r;o+=1)for(n=0;n<r-6;n+=1)t.isDark(n,o)&&!t.isDark(n+1,o)&&t.isDark(n+2,o)&&t.isDark(n+3,o)&&t.isDark(n+4,o)&&!t.isDark(n+5,o)&&t.isDark(n+6,o)&&(e+=40);var g=0;for(o=0;o<r;o+=1)for(n=0;n<r;n+=1)t.isDark(n,o)&&(g+=1);return e+=Math.abs(100*g/r/r-50)/5*10},t);function d(t){for(var r=0;0!=t;)r+=1,t>>>=1;return r}var w=function(){for(var r=new Array(256),e=new Array(256),t=0;t<8;t+=1)r[t]=1<<t;for(t=8;t<256;t+=1)r[t]=r[t-4]^r[t-5]^r[t-6]^r[t-8];for(t=0;t<255;t+=1)e[r[t]]=t;var n={glog:function(t){if(t<1)throw"glog("+t+")";return e[t]},gexp:function(t){for(;t<0;)t+=255;for(;256<=t;)t-=255;return r[t]}};return n}();function C(n,o){if(void 0===n.length)throw n.length+"/"+o;var r=function(){for(var t=0;t<n.length&&0==n[t];)t+=1;for(var r=new Array(n.length-t+o),e=0;e<n.length-t;e+=1)r[e]=n[e+t];return r}(),i={getAt:function(t){return r[t]},getLength:function(){return r.length},multiply:function(t){for(var r=new Array(i.getLength()+t.getLength()-1),e=0;e<i.getLength();e+=1)for(var n=0;n<t.getLength();n+=1)r[e+n]^=w.gexp(w.glog(i.getAt(e))+w.glog(t.getAt(n)));return C(r,0)},mod:function(t){if(i.getLength()-t.getLength()<0)return i;for(var r=w.glog(i.getAt(0))-w.glog(t.getAt(0)),e=new Array(i.getLength()),n=0;n<i.getLength();n+=1)e[n]=i.getAt(n);for(n=0;n<t.getLength();n+=1)e[n]^=w.gexp(w.glog(t.getAt(n))+r);return C(e,0).mod(t)}};return i}function p(){var e=[],o={writeByte:function(t){e.push(255&t)},writeShort:function(t){o.writeByte(t),o.writeByte(t>>>8)},writeBytes:function(t,r,e){r=r||0,e=e||t.length;for(var n=0;n<e;n+=1)o.writeByte(t[n+r])},writeString:function(t){for(var r=0;r<t.length;r+=1)o.writeByte(t.charCodeAt(r))},toByteArray:function(){return e},toString:function(){var t="";t+="[";for(var r=0;r<e.length;r+=1)0<r&&(t+=","),t+=e[r];return t+="]"}};return o}var k,A,b=(k=[[1,26,19],[1,26,16],[1,26,13],[1,26,9],[1,44,34],[1,44,28],[1,44,22],[1,44,16],[1,70,55],[1,70,44],[2,35,17],[2,35,13],[1,100,80],[2,50,32],[2,50,24],[4,25,9],[1,134,108],[2,67,43],[2,33,15,2,34,16],[2,33,11,2,34,12],[2,86,68],[4,43,27],[4,43,19],[4,43,15],[2,98,78],[4,49,31],[2,32,14,4,33,15],[4,39,13,1,40,14],[2,121,97],[2,60,38,2,61,39],[4,40,18,2,41,19],[4,40,14,2,41,15],[2,146,116],[3,58,36,2,59,37],[4,36,16,4,37,17],[4,36,12,4,37,13],[2,86,68,2,87,69],[4,69,43,1,70,44],[6,43,19,2,44,20],[6,43,15,2,44,16],[4,101,81],[1,80,50,4,81,51],[4,50,22,4,51,23],[3,36,12,8,37,13],[2,116,92,2,117,93],[6,58,36,2,59,37],[4,46,20,6,47,21],[7,42,14,4,43,15],[4,133,107],[8,59,37,1,60,38],[8,44,20,4,45,21],[12,33,11,4,34,12],[3,145,115,1,146,116],[4,64,40,5,65,41],[11,36,16,5,37,17],[11,36,12,5,37,13],[5,109,87,1,110,88],[5,65,41,5,66,42],[5,54,24,7,55,25],[11,36,12,7,37,13],[5,122,98,1,123,99],[7,73,45,3,74,46],[15,43,19,2,44,20],[3,45,15,13,46,16],[1,135,107,5,136,108],[10,74,46,1,75,47],[1,50,22,15,51,23],[2,42,14,17,43,15],[5,150,120,1,151,121],[9,69,43,4,70,44],[17,50,22,1,51,23],[2,42,14,19,43,15],[3,141,113,4,142,114],[3,70,44,11,71,45],[17,47,21,4,48,22],[9,39,13,16,40,14],[3,135,107,5,136,108],[3,67,41,13,68,42],[15,54,24,5,55,25],[15,43,15,10,44,16],[4,144,116,4,145,117],[17,68,42],[17,50,22,6,51,23],[19,46,16,6,47,17],[2,139,111,7,140,112],[17,74,46],[7,54,24,16,55,25],[34,37,13],[4,151,121,5,152,122],[4,75,47,14,76,48],[11,54,24,14,55,25],[16,45,15,14,46,16],[6,147,117,4,148,118],[6,73,45,14,74,46],[11,54,24,16,55,25],[30,46,16,2,47,17],[8,132,106,4,133,107],[8,75,47,13,76,48],[7,54,24,22,55,25],[22,45,15,13,46,16],[10,142,114,2,143,115],[19,74,46,4,75,47],[28,50,22,6,51,23],[33,46,16,4,47,17],[8,152,122,4,153,123],[22,73,45,3,74,46],[8,53,23,26,54,24],[12,45,15,28,46,16],[3,147,117,10,148,118],[3,73,45,23,74,46],[4,54,24,31,55,25],[11,45,15,31,46,16],[7,146,116,7,147,117],[21,73,45,7,74,46],[1,53,23,37,54,24],[19,45,15,26,46,16],[5,145,115,10,146,116],[19,75,47,10,76,48],[15,54,24,25,55,25],[23,45,15,25,46,16],[13,145,115,3,146,116],[2,74,46,29,75,47],[42,54,24,1,55,25],[23,45,15,28,46,16],[17,145,115],[10,74,46,23,75,47],[10,54,24,35,55,25],[19,45,15,35,46,16],[17,145,115,1,146,116],[14,74,46,21,75,47],[29,54,24,19,55,25],[11,45,15,46,46,16],[13,145,115,6,146,116],[14,74,46,23,75,47],[44,54,24,7,55,25],[59,46,16,1,47,17],[12,151,121,7,152,122],[12,75,47,26,76,48],[39,54,24,14,55,25],[22,45,15,41,46,16],[6,151,121,14,152,122],[6,75,47,34,76,48],[46,54,24,10,55,25],[2,45,15,64,46,16],[17,152,122,4,153,123],[29,74,46,14,75,47],[49,54,24,10,55,25],[24,45,15,46,46,16],[4,152,122,18,153,123],[13,74,46,32,75,47],[48,54,24,14,55,25],[42,45,15,32,46,16],[20,147,117,4,148,118],[40,75,47,7,76,48],[43,54,24,22,55,25],[10,45,15,67,46,16],[19,148,118,6,149,119],[18,75,47,31,76,48],[34,54,24,34,55,25],[20,45,15,61,46,16]],(A={}).getRSBlocks=function(t,r){var e=function(t,r){switch(r){case y.L:return k[4*(t-1)+0];case y.M:return k[4*(t-1)+1];case y.Q:return k[4*(t-1)+2];case y.H:return k[4*(t-1)+3];default:return}}(t,r);if(void 0===e)throw"bad rs block @ typeNumber:"+t+"/errorCorrectionLevel:"+r;for(var n,o,i=e.length/3,a=[],u=0;u<i;u+=1)for(var f=e[3*u+0],c=e[3*u+1],g=e[3*u+2],l=0;l<f;l+=1)a.push((n=g,o=void 0,(o={}).totalCount=c,o.dataCount=n,o));return a},A),M=function(){var e=[],n=0,o={getBuffer:function(){return e},getAt:function(t){var r=Math.floor(t/8);return 1==(e[r]>>>7-t%8&1)},put:function(t,r){for(var e=0;e<r;e+=1)o.putBit(1==(t>>>r-e-1&1))},getLengthInBits:function(){return n},putBit:function(t){var r=Math.floor(n/8);e.length<=r&&e.push(0),t&&(e[r]|=128>>>n%8),n+=1}};return o},x=function(t){var r=a,n=t,e={getMode:function(){return r},getLength:function(t){return n.length},write:function(t){for(var r=n,e=0;e+2<r.length;)t.put(o(r.substring(e,e+3)),10),e+=3;e<r.length&&(r.length-e==1?t.put(o(r.substring(e,e+1)),4):r.length-e==2&&t.put(o(r.substring(e,e+2)),7))}},o=function(t){for(var r=0,e=0;e<t.length;e+=1)r=10*r+i(t.charAt(e));return r},i=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);throw"illegal char :"+t};return e},m=function(t){var r=u,n=t,e={getMode:function(){return r},getLength:function(t){return n.length},write:function(t){for(var r=n,e=0;e+1<r.length;)t.put(45*o(r.charAt(e))+o(r.charAt(e+1)),11),e+=2;e<r.length&&t.put(o(r.charAt(e)),6)}},o=function(t){if("0"<=t&&t<="9")return t.charCodeAt(0)-"0".charCodeAt(0);if("A"<=t&&t<="Z")return t.charCodeAt(0)-"A".charCodeAt(0)+10;switch(t){case" ":return 36;case"$":return 37;case"%":return 38;case"*":return 39;case"+":return 40;case"-":return 41;case".":return 42;case"/":return 43;case":":return 44;default:throw"illegal char :"+t}};return e},L=function(t){var r=o,e=i.stringToBytes(t),n={getMode:function(){return r},getLength:function(t){return e.length},write:function(t){for(var r=0;r<e.length;r+=1)t.put(e[r],8)}};return n},D=function(t){var r=f,e=i.stringToBytesFuncs.SJIS;if(!e)throw"sjis not supported.";!function(){var t=e("友");if(2!=t.length||38726!=(t[0]<<8|t[1]))throw"sjis not supported."}();var o=e(t),n={getMode:function(){return r},getLength:function(t){return~~(o.length/2)},write:function(t){for(var r=o,e=0;e+1<r.length;){var n=(255&r[e])<<8|255&r[e+1];if(33088<=n&&n<=40956)n-=33088;else{if(!(57408<=n&&n<=60351))throw"illegal char at "+(e+1)+"/"+n;n-=49472}n=192*(n>>>8&255)+(255&n),t.put(n,13),e+=2}if(e<r.length)throw"illegal char at "+(e+1)}};return n},S=function(t){var e=t,n=0,o=0,i=0,r={read:function(){for(;i<8;){if(n>=e.length){if(0==i)return-1;throw"unexpected end of file./"+i}var t=e.charAt(n);if(n+=1,"="==t)return i=0,-1;t.match(/^\s$/)||(o=o<<6|a(t.charCodeAt(0)),i+=6)}var r=o>>>i-8&255;return i-=8,r}},a=function(t){if(65<=t&&t<=90)return t-65;if(97<=t&&t<=122)return t-97+26;if(48<=t&&t<=57)return t-48+52;if(43==t)return 62;if(47==t)return 63;throw"c:"+t};return r},I=function(t,r,e){for(var n=function(t,r){var n=t,o=r,l=new Array(t*r),e={setPixel:function(t,r,e){l[r*n+t]=e},write:function(t){t.writeString("GIF87a"),t.writeShort(n),t.writeShort(o),t.writeByte(128),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(0),t.writeByte(255),t.writeByte(255),t.writeByte(255),t.writeString(","),t.writeShort(0),t.writeShort(0),t.writeShort(n),t.writeShort(o),t.writeByte(0);var r=i(2);t.writeByte(2);for(var e=0;255<r.length-e;)t.writeByte(255),t.writeBytes(r,e,255),e+=255;t.writeByte(r.length-e),t.writeBytes(r,e,r.length-e),t.writeByte(0),t.writeString(";")}},i=function(t){for(var r=1<<t,e=1+(1<<t),n=t+1,o=h(),i=0;i<r;i+=1)o.add(String.fromCharCode(i));o.add(String.fromCharCode(r)),o.add(String.fromCharCode(e));var a=p(),u=function(t){var e=t,n=0,o=0,r={write:function(t,r){if(t>>>r!=0)throw"length over";for(;8<=n+r;)e.writeByte(255&(t<<n|o)),r-=8-n,t>>>=8-n,n=o=0;o|=t<<n,n+=r},flush:function(){0<n&&e.writeByte(o)}};return r}(a);u.write(r,n);var f=0,c=String.fromCharCode(l[f]);for(f+=1;f<l.length;){var g=String.fromCharCode(l[f]);f+=1,o.contains(c+g)?c+=g:(u.write(o.indexOf(c),n),o.size()<4095&&(o.size()==1<<n&&(n+=1),o.add(c+g)),c=g)}return u.write(o.indexOf(c),n),u.write(e,n),u.flush(),a.toByteArray()},h=function(){var r={},e=0,n={add:function(t){if(n.contains(t))throw"dup key:"+t;r[t]=e,e+=1},size:function(){return e},indexOf:function(t){return r[t]},contains:function(t){return void 0!==r[t]}};return n};return e}(t,r),o=0;o<r;o+=1)for(var i=0;i<t;i+=1)n.setPixel(i,o,e(i,o));var a=p();n.write(a);for(var u=function(){function e(t){a+=String.fromCharCode(r(63&t))}var n=0,o=0,i=0,a="",t={},r=function(t){if(t<0);else{if(t<26)return 65+t;if(t<52)return t-26+97;if(t<62)return t-52+48;if(62==t)return 43;if(63==t)return 47}throw"n:"+t};return t.writeByte=function(t){for(n=n<<8|255&t,o+=8,i+=1;6<=o;)e(n>>>o-6),o-=6},t.flush=function(){if(0<o&&(e(n<<6-o),o=n=0),i%3!=0)for(var t=3-i%3,r=0;r<t;r+=1)a+="="},t.toString=function(){return a},t}(),f=a.toByteArray(),c=0;c<f.length;c+=1)u.writeByte(f[c]);return u.flush(),"data:image/gif;base64,"+u};return i}();qrcode.stringToBytesFuncs["UTF-8"]=function(t){return function(t){for(var r=[],e=0;e<t.length;e++){var n=t.charCodeAt(e);n<128?r.push(n):n<2048?r.push(192|n>>6,128|63&n):n<55296||57344<=n?r.push(224|n>>12,128|n>>6&63,128|63&n):(e++,n=65536+((1023&n)<<10|1023&t.charCodeAt(e)),r.push(240|n>>18,128|n>>12&63,128|n>>6&63,128|63&n))}return r}(t)},function(t){"function"==typeof define&&define.amd?define([],t):"object"==typeof exports&&(module.exports=t())}(function(){return qrcode});"##;

const LOGIN_HTML: &str = r##"
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0a0a0c">
    <meta name="mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <link rel="manifest" href="/manifest.webmanifest">
    <link rel="icon" type="image/svg+xml" href="/pwa-icon.svg">
    <link rel="apple-touch-icon" href="/pwa-icon.svg">
    <link rel="icon" type="image/png" sizes="192x192" href="/pwa-icon-192.png">
    <meta property="og:title" content="CSQTT">
    <meta property="og:type" content="website">
    <meta property="og:image" content="/pwa-icon.svg">
    <title>CSQTT — Вход</title>
    <style>
        :root {
            --bg-color: #f8fafc;
            --surface: #ffffff;
            --primary: #0077ff;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --error: #ef4444;
            --border: #e2e8f0;
            --glass: rgba(255, 255, 255, 0.9);
        }

        html { -webkit-text-size-adjust: none; text-size-adjust: none; }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, 'Segoe UI', Roboto, Arial, sans-serif; }
        body { background-color: var(--bg-color); color: var(--text-main); min-height: 100vh; display: flex; align-items: center; justify-content: center; overflow: hidden; }

        main {
            z-index: 1; width: min(420px, calc(100% - 40px)); background: var(--glass);
            backdrop-filter: blur(20px); border: 1px solid var(--border); border-radius: 24px;
            padding: 48px 40px; box-shadow: 0 20px 40px rgba(0, 0, 0, 0.05);
            animation: slideUp 0.6s cubic-bezier(0.16, 1, 0.3, 1);
        }
        @keyframes slideUp { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }

        h1 { text-align: center; font-size: 28px; font-weight: 700; margin-bottom: 36px; color: var(--text-main); }

        .input-group { margin-bottom: 20px; }
        .input-group label { display: block; font-size: 13px; font-weight: 600; color: var(--text-muted); margin-bottom: 8px; text-transform: uppercase; letter-spacing: 0.05em; }
        .input-group input, .input-group select { width: 100%; height: 52px; background: #f8fafc; border: 1px solid var(--border); border-radius: 14px; padding: 0 16px; font-size: 15px; color: var(--text-main); outline: none; transition: all 0.2s; }
        .input-group input:focus, .input-group select:focus { border-color: var(--primary); box-shadow: 0 0 0 4px rgba(0, 119, 255, 0.1); }

        button { width: 100%; height: 52px; background: var(--primary); color: white; border: none; border-radius: 14px; font-size: 16px; font-weight: 600; cursor: pointer; transition: all 0.2s; margin-top: 12px; box-shadow: 0 8px 16px rgba(0, 119, 255, 0.25); }
        button:hover { transform: translateY(-2px); box-shadow: 0 12px 20px rgba(0, 119, 255, 0.35); }
        button:active { transform: translateY(0); }

        #e { min-height: 20px; color: var(--error); font-size: 14px; font-weight: 500; text-align: center; margin-top: 16px; opacity: 0; }
    </style>
</head>
<body>
    <main>
        <h1>Вход</h1>
        <form id="f">
            <div class="input-group">
                <label for="u">Пользователь</label>
                <input id="u" required>
            </div>
            <div class="input-group">
                <label for="p">Пароль</label>
                <input id="p" type="password" required>
            </div>
            <button id="submitBtn" type="submit">Войти</button>
            <div id="e"></div>
        </form>
    </main>
    <script>
        const basePath = window.location.pathname.replace(/\/$/, '');
        if (basePath) {
            document.querySelectorAll('link[rel="manifest"], link[rel="icon"], link[rel="apple-touch-icon"]').forEach(el => {
                const href = el.getAttribute('href');
                if (href && href.startsWith('/')) {
                    el.setAttribute('href', basePath + href);
                }
            });
        }
        function caesarEncode(s) {
            const bytes = new TextEncoder().encode(s);
            let bin = '';
            for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode((bytes[i] + 47) & 0xff);
            return 'c1:' + btoa(bin);
        }
        f.onsubmit = async x => {
            x.preventDefault();
            const btn = document.getElementById('submitBtn');
            const err = document.getElementById('e');
            err.style.opacity = '0';
            try {
                let r = await fetch((basePath || '') + "/api/login", {
                    method: "POST",
                    headers: {"content-type": "application/json"},
                    credentials: "include",
                    body: JSON.stringify({user: caesarEncode(u.value), pass: caesarEncode(p.value)})
                });
                if (r.ok) {
                    btn.innerHTML = 'Успешно!'; btn.style.background = '#0077ff';
                    setTimeout(() => {
                        window.location.href = (basePath || '') + '/';
                    }, 150);
                } else {
                    err.textContent = r.status === 429 ? "Подождите 3 минуты." : "Неверный логин или пароль"; err.style.opacity = '1';
                }
            } catch (error) { err.textContent = "Ошибка сети"; err.style.opacity = '1'; }
        };
    </script>
</body>
</html>

"##;

const CLIENT_SUB_HTML: &str = r##"<!DOCTYPE html>
<html lang="ru" data-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0a0a0c">
    <link rel="icon" type="image/svg+xml" href="/pwa-icon.svg">
    <link rel="apple-touch-icon" href="/pwa-icon.svg">
    <meta property="og:title" content="CSQTT">
    <meta property="og:type" content="website">
    <meta property="og:image" content="/pwa-icon.svg">
    <title>Подписка: __CLIENT_NAME__</title>
    <script src="/qrcode.min.js"></script>
    <style>
        :root {
            --bg: #0a0a0c;
            --surface: #141418;
            --surface-hover: #1c1c22;
            --border: #27272a;
            --text-main: #f4f4f5;
            --text-muted: #a1a1aa;
            --primary: #3b82f6;
            --primary-hover: #2563eb;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 16px;
        }
        dialog#qrDlg {
            display: block;
            position: static;
            background: var(--surface);
            color: var(--text-main);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 24px;
            box-shadow: 0 16px 32px rgba(0, 0, 0, 0.4);
            box-sizing: border-box;
            max-width: 440px;
            width: min(440px, calc(100% - 24px));
            text-align: center;
        }
        .dlg-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
        }
        .close-btn {
            background: none;
            border: none;
            color: var(--text-muted);
            cursor: pointer;
            padding: 4px;
            border-radius: 6px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .close-btn:hover {
            color: var(--text-main);
            background: var(--surface-hover);
        }
        .qr-box {
            background: #ffffff;
            padding: 12px;
            border-radius: 12px;
            display: inline-flex;
            justify-content: center;
            align-items: center;
            width: 260px;
            height: 260px;
            margin: 0 auto;
        }
        .qr-box svg {
            width: 100%;
            height: 100%;
            display: block;
        }
        .btn {
            font-family: inherit;
            cursor: pointer;
            transition: all 0.15s ease;
            outline: none;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            text-decoration: none;
        }
        .btn-primary {
            background: var(--primary);
            color: #ffffff;
            border: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 500;
        }
        .btn-primary:hover {
            background: var(--primary-hover);
        }
        .btn-ghost {
            background: transparent;
            color: var(--text-main);
            border: 1px solid var(--border);
            border-radius: 8px;
        }
        .btn-ghost:hover {
            background: var(--surface-hover);
        }
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%) translateY(100px);
            background: var(--surface);
            color: var(--text-main);
            padding: 10px 18px;
            border-radius: 8px;
            border: 1px solid var(--border);
            font-size: 13px;
            font-weight: 500;
            box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
            opacity: 0;
            transition: transform 0.25s ease, opacity 0.25s ease;
            pointer-events: none;
            z-index: 1000;
        }
        .toast.show {
            transform: translateX(-50%) translateY(0);
            opacity: 1;
        }
    </style>
</head>
<body>
    <dialog id="qrDlg" style="max-width: 440px; width: min(440px, calc(100% - 24px)); text-align: center;" open="">
        <div class="dlg-header" style="justify-content: center;">
            <div style="display: flex; align-items: center; gap: 8px;">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="3" y="3" width="7" height="7"></rect>
                    <rect x="14" y="3" width="7" height="7"></rect>
                    <rect x="14" y="14" width="7" height="7"></rect>
                    <rect x="3" y="14" width="7" height="7"></rect>
                    <path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path>
                </svg>
                <h2 id="qrDlgTitle" style="font-size: 18px; margin: 0;">Подписка: __CLIENT_NAME__</h2>
            </div>
        </div>
        <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; margin: 16px 0;">
            <div id="qrCodeContainer" class="qr-box"></div>
            <div style="width: 100%; display: flex; gap: 8px; align-items: center;">
                <input id="qrDlgLink" readonly="" value="__CSQTT_LINK__" style="flex: 1; height: 42px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; padding: 0 10px; background: var(--surface-hover); color: var(--text-muted); border-radius: 8px; border: 1px solid var(--border);" onclick="this.select()">
                <button class="btn btn-ghost" id="qrDlgCopyBtn" onclick="copyQrDlgLink(this)" style="height: 42px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: #27272a; border: 1px solid #3f3f46; color: #ffffff; border-radius: 8px;" title="Копировать ссылку">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
            </div>
        </div>
        <div class="dlg-actions" style="justify-content: center; display: flex; margin-top: 16px;">
            <a id="qrDlgConnectBtn" href="__CSQTT_LINK__" class="btn btn-primary" style="width: 100%; height: 42px; text-decoration: none; display: flex; align-items: center; justify-content: center; font-weight: 500; font-size: 14px; border-radius: 8px;">Подключить в приложении CSQTT</a>
        </div>
    </dialog>

    <div id="toast" class="toast"></div>

    <script>
                function showToast(msg) {
            const t = document.getElementById('toast');
            if (!t) return;
            t.textContent = msg;
            t.classList.add('show');
            setTimeout(() => { t.classList.remove('show'); }, 2000);
        }
        async function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try {
                    await navigator.clipboard.writeText(text);
                    return true;
                } catch (e) {}
            }
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                ta.style.top = '0';
                ta.setAttribute('readonly', '');
                document.body.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, 99999);
                const success = document.execCommand('copy');
                document.body.removeChild(ta);
                return success;
            } catch (e) {
                return false;
            }
        }
        async function copyQrDlgLink(btn) {
            const link = document.getElementById('qrDlgLink')?.value || '';
            if (!link) return;
            if (await copyToClipboard(link)) {
                if (btn) {
                    btn.style.color = '#3b82f6';
                    btn.style.borderColor = '#3b82f6';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка скопирована");
            } else {
                showToast("Не удалось скопировать");
            }
        }
        (function initQr() {
            const link = document.getElementById('qrDlgLink')?.value || '';
            if (!link) return;
            try {
                const qr = qrcode(0, 'L');
                qr.addData(link);
                qr.make();
                const svg = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
                document.getElementById('qrCodeContainer').innerHTML = svg;
            } catch (e) {
                document.getElementById('qrCodeContainer').innerHTML = '<div style="color:#ef4444;padding:16px;">Ошибка генерации QR</div>';
            }
        })();
    </script>
</body>
</html>
"##;

const GROUP_SUB_HTML: &str = r##"<!DOCTYPE html>
<html lang="ru" data-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0a0a0c">
    <link rel="icon" type="image/svg+xml" href="/pwa-icon.svg">
    <link rel="apple-touch-icon" href="/pwa-icon.svg">
    <meta property="og:title" content="CSQTT">
    <meta property="og:type" content="website">
    <title>Подписка группы: __GROUP_NAME__</title>
    <script src="/qrcode.min.js"></script>
    <style>
        :root {
            --bg: #0a0a0c;
            --surface: #141418;
            --surface-hover: #1c1c22;
            --border: #27272a;
            --text-main: #f4f4f5;
            --text-muted: #a1a1aa;
            --primary: #3b82f6;
            --primary-hover: #2563eb;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 24px 16px 48px;
        }
        .header-bar {
            width: 100%;
            max-width: 1360px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 16px;
            margin-bottom: 24px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px 24px;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
        }
        .header-left {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .header-title {
            font-size: 19px;
            font-weight: 700;
            margin: 0;
            color: var(--text-main);
        }
        .badge {
            background: rgba(59, 130, 246, 0.15);
            color: var(--primary);
            border: 1px solid rgba(59, 130, 246, 0.3);
            border-radius: 9999px;
            padding: 4px 12px;
            font-size: 13px;
            font-weight: 600;
        }
        .group-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
            gap: 20px;
            width: 100%;
            max-width: 1360px;
        }
        @media (min-width: 1024px) {
            .group-grid {
                grid-template-columns: repeat(3, 1fr);
            }
        }
        @media (min-width: 1360px) {
            .group-grid {
                grid-template-columns: repeat(4, 1fr);
            }
        }
        @media (max-width: 640px) {
            .group-grid {
                grid-template-columns: 1fr;
                gap: 16px;
            }
            body { padding: 16px 12px 32px; }
            .header-bar { padding: 14px 16px; margin-bottom: 16px; }
        }
        .client-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 20px;
            display: flex;
            flex-direction: column;
            align-items: center;
            text-align: center;
            box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
            transition: transform 0.2s ease, border-color 0.2s ease;
        }
        .client-card:hover {
            transform: translateY(-2px);
            border-color: rgba(59, 130, 246, 0.4);
        }
        .client-card-header {
            width: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            margin-bottom: 12px;
        }
        .client-name {
            font-size: 16px;
            font-weight: 600;
            color: var(--text-main);
            word-break: break-word;
        }
        .divider {
            width: 100%;
            border: 0;
            border-top: 1px solid var(--border);
            margin: 0 0 16px;
        }
        .qr-box {
            background: #ffffff;
            padding: 10px;
            border-radius: 12px;
            display: inline-flex;
            justify-content: center;
            align-items: center;
            width: 220px;
            height: 220px;
            margin: 0 auto 16px;
        }
        .qr-box svg {
            width: 100%;
            height: 100%;
            display: block;
        }
        .input-row {
            width: 100%;
            display: flex;
            gap: 8px;
            align-items: center;
            margin-bottom: 12px;
        }
        .link-input {
            flex: 1;
            height: 40px;
            font-size: 12px;
            font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
            padding: 0 10px;
            background: var(--surface-hover);
            color: var(--text-muted);
            border-radius: 8px;
            border: 1px solid var(--border);
            outline: none;
        }
        .btn {
            font-family: inherit;
            cursor: pointer;
            transition: all 0.15s ease;
            outline: none;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            text-decoration: none;
        }
        .btn-copy {
            height: 40px;
            width: 44px;
            padding: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            flex-shrink: 0;
            background: #27272a;
            border: 1px solid #3f3f46;
            color: #ffffff;
            border-radius: 8px;
        }
        .btn-copy:hover {
            background: #3f3f46;
        }
        .btn-primary {
            background: var(--primary);
            color: #ffffff;
            border: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 500;
            width: 100%;
            height: 42px;
        }
        .btn-primary:hover {
            background: var(--primary-hover);
        }
        .empty-state {
            grid-column: 1 / -1;
            text-align: center;
            padding: 60px 20px;
            color: var(--text-muted);
            font-size: 15px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
        }
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%) translateY(100px);
            background: var(--surface);
            color: var(--text-main);
            padding: 10px 18px;
            border-radius: 8px;
            border: 1px solid var(--border);
            font-size: 13px;
            font-weight: 500;
            box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
            opacity: 0;
            transition: transform 0.25s ease, opacity 0.25s ease;
            pointer-events: none;
            z-index: 1000;
        }
        .toast.show {
            transform: translateX(-50%) translateY(0);
            opacity: 1;
        }
    </style>
</head>
<body>
    <header class="header-bar">
        <div class="header-left">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="7" r="3.5"></circle>
                <path d="M6 21v-2a4 4 0 0 1 8 0v2"></path>
                <path d="M5.5 10a3 3 0 0 1 0-6"></path>
                <path d="M2 21v-1.5a3.5 3.5 0 0 1 3-3.4"></path>
                <path d="M18.5 4a3 3 0 0 1 0 6"></path>
                <path d="M19 16.1a3.5 3.5 0 0 1 3 3.4V21"></path>
            </svg>
            <h1 class="header-title">Группа: __GROUP_NAME__</h1>
        </div>
        <div class="badge">__CLIENTS_COUNT__ клиентов</div>
    </header>

    <main class="group-grid" id="gridContainer"></main>

    <div id="toast" class="toast"></div>

    <script>

        const clients = __CLIENTS_JSON__;
        function esc(s) {
            return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        }
        function showToast(msg) {
            const t = document.getElementById('toast');
            if (!t) return;
            t.textContent = msg;
            t.classList.add('show');
            setTimeout(() => { t.classList.remove('show'); }, 2000);
        }
        async function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try {
                    await navigator.clipboard.writeText(text);
                    return true;
                } catch (e) {}
            }
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                ta.style.top = '0';
                ta.setAttribute('readonly', '');
                document.body.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, 99999);
                const success = document.execCommand('copy');
                document.body.removeChild(ta);
                return success;
            } catch (e) {
                return false;
            }
        }
        async function copyCardLink(btn, link) {
            if (!link) return;
            if (await copyToClipboard(link)) {
                if (btn) {
                    btn.style.color = '#3b82f6';
                    btn.style.borderColor = '#3b82f6';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка скопирована");
            } else {
                showToast("Не удалось скопировать");
            }
        }

        const grid = document.getElementById('gridContainer');
        if (!clients.length) {
            grid.innerHTML = '<div class="empty-state">В этой группе пока нет клиентов.</div>';
        } else {
            let html = '';
            clients.forEach((c, idx) => {
                html += `
                <div class="client-card">
                    <div class="client-card-header">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path>
                            <circle cx="12" cy="7" r="4"></circle>
                        </svg>
                        <h2 class="client-name">${esc(c.name || 'Клиент')}</h2>
                    </div>
                    <hr class="divider">
                    <div class="qr-box" id="qr-box-${idx}"></div>
                    <div class="input-row">
                        <input class="link-input" readonly value="${esc(c.link)}" onclick="this.select()">
                        <button class="btn btn-copy" onclick="copyCardLink(this, '${esc(c.link)}')" title="Копировать ссылку">
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        </button>
                    </div>
                    <a href="${esc(c.link)}" class="btn btn-primary">Подключить в приложении CSQTT</a>
                </div>
                `;
            });
            grid.innerHTML = html;

            clients.forEach((c, idx) => {
                const el = document.getElementById('qr-box-' + idx);
                if (!el || !c.link) return;
                try {
                    const qr = qrcode(0, 'L');
                    qr.addData(c.link);
                    qr.make();
                    el.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
                } catch (e) {
                    el.innerHTML = '<div style="color:#ef4444;font-size:12px;">Ошибка QR</div>';
                }
            });
        }
    </script>
</body>
</html>
"##;

const GROUP_ADMIN_HTML: &str = r##"<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0a0a0c">
    <link rel="icon" type="image/svg+xml" href="/pwa-icon.svg">
    <link rel="apple-touch-icon" href="/pwa-icon.svg">
    <meta property="og:title" content="CSQTT">
    <meta property="og:type" content="website">
    <meta property="og:image" content="/pwa-icon.svg">
    <title>CSQTT — Панель группы</title>
    <script src="/qrcode.min.js"></script>
    <style>
        :root {
            --bg-color: #f8fafc;
            --surface: #ffffff;
            --surface-hover: #f1f5f9;
            --primary: #0077ff;
            --primary-hover: #0060cc;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --border: #e2e8f0;
            --danger: #ef4444;
            --success: #10b981;
            --icon-bg: rgba(0, 119, 255, 0.1);
        }

        [data-theme="dark"] {
            --bg-color: #000000;
            --surface: #0a0a0c;
            --surface-hover: #161619;
            --text-main: #f4f4f5;
            --text-muted: #9ca3af;
            --border: #1f1f23;
            --danger: #ef4444;
            --success: #10b981;
            --icon-bg: rgba(0, 119, 255, 0.15);
        }

        * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, 'Segoe UI', Roboto, Arial, sans-serif; }
        body {
            background-color: var(--bg-color);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            padding: 24px 16px;
            transition: background-color 0.3s ease, color 0.3s ease;
        }
        .main-card {
            max-width: 760px;
            width: 100%;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 20px;
            padding: 24px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            box-shadow: 0 4px 20px rgba(0,0,0,0.06);
            align-self: flex-start;
        }
        .dlg-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 12px;
        }
        .btn {
            font-size: 13px;
            padding: 0 16px;
            height: 38px;
            white-space: nowrap;
            border-radius: 10px;
            border: 1px solid var(--border);
            cursor: pointer;
            font-weight: 600;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
            transition: all 0.2s;
            box-sizing: border-box;
        }
        .btn-primary { background: var(--primary); color: #fff; border-color: var(--primary); }
        .btn-primary:hover { background: var(--primary-hover); border-color: var(--primary-hover); }
        .btn-ghost { background: var(--surface); color: var(--text-main); }
        .btn-ghost:hover { background: var(--surface-hover); }
        .btn-danger { color: #ef4444; border-color: rgba(239, 68, 68, 0.35); background: transparent; }
        .btn-danger:hover { background: rgba(239, 68, 68, 0.1); }
        .badge {
            font-size: 11px;
            font-weight: 600;
            padding: 3px 8px;
            border-radius: 6px;
            display: inline-flex;
            align-items: center;
            gap: 4px;
        }
        .badge.active { background: rgba(16, 185, 129, 0.12); color: #10b981; border: 1px solid rgba(16, 185, 129, 0.25); }
        .badge.inactive { background: rgba(239, 68, 68, 0.12); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.25); }
        .badge.admin { background: var(--icon-bg); color: var(--primary); border: 1px solid rgba(0, 119, 255, 0.25); }
        .group-dlg-actions {
            display: flex;
            flex-wrap: wrap;
            align-items: center;
            gap: 8px;
        }
        .group-act-btn {
            height: 38px;
            font-size: 13px;
            border-radius: 10px;
            padding: 0 16px;
            box-sizing: border-box;
            border: 1px solid var(--border);
            white-space: nowrap;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-weight: 600;
        }
        .group-search-wrapper {
            position: relative;
            display: inline-flex;
            align-items: center;
            flex: 1;
            min-width: 170px;
        }
        input[type="text"].group-search-input, .group-search-input {
            width: 100%;
            height: 38px;
            background: var(--surface-hover);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 0 32px 0 46px !important;
            font-size: 13px;
            color: var(--text-main);
            outline: none;
            box-sizing: border-box;
            transition: all 0.2s;
        }
        .group-search-input:focus { border-color: var(--primary); }
        .group-search-icon {
            position: absolute;
            left: 13px;
            top: 50%;
            transform: translateY(-50%);
            color: var(--text-muted);
            pointer-events: none;
        }
        .stats-summary {
            font-size: 13px;
            color: var(--text-muted);
            padding: 10px 14px;
            background: var(--surface-hover);
            border-radius: 12px;
            border: 1px solid var(--border);
            font-weight: 500;
        }
        .client-cards-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 14px;
            margin-top: 4px;
        }
        .client-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px;
            cursor: pointer;
            box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
            display: flex;
            flex-direction: column;
            gap: 10px;
            transition: all 0.2s ease;
        }
        .client-card:hover {
            border-color: var(--primary);
            transform: translateY(-2px);
            box-shadow: 0 8px 16px rgba(0, 0, 0, 0.08);
        }
        .client-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--border);
            padding-bottom: 10px;
            min-height: 32px;
        }
        .client-card-title {
            display: flex;
            align-items: center;
            gap: 8px;
            font-weight: 700;
            font-size: 14px;
            color: var(--text-main);
            min-width: 0;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .client-card-body {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        .client-card-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 12px;
            color: var(--text-muted);
        }
        .client-card-label { color: var(--text-muted); }
        .client-card-value { color: var(--text-main); font-weight: 500; }
        .client-pw {
            font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
            color: var(--primary);
            font-weight: 600;
            background: var(--icon-bg);
            padding: 2px 6px;
            border-radius: 6px;
        }
        .client-sub-btn {
            width: 100%;
            margin-top: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
            font-size: 12px;
            padding: 7px 10px;
            border: 1px solid var(--border) !important;
            color: var(--text-main) !important;
            background: transparent !important;
            border-radius: 8px;
            font-weight: 500;
            transition: all 0.15s ease;
            cursor: pointer;
            box-sizing: border-box;
        }
        .client-sub-btn:hover {
            background: var(--surface-hover) !important;
            border-color: var(--primary) !important;
            color: var(--primary) !important;
        }
        [data-theme="dark"] .client-sub-btn {
            border-color: rgba(255, 255, 255, 0.25) !important;
            color: #ffffff !important;
        }
        [data-theme="dark"] .client-sub-btn:hover {
            border-color: var(--primary) !important;
            color: var(--primary) !important;
            background: rgba(255, 255, 255, 0.05) !important;
        }
        .hub-bulk-panel {
            background: var(--surface-hover);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 16px;
            margin-bottom: 20px;
        }
        .hub-field-block {
            display: flex;
            flex-direction: column;
            margin-bottom: 12px;
        }
        .hub-field-block:last-child {
            margin-bottom: 0;
        }
        .hub-field-label {
            display: block;
            font-size: 11px;
            font-weight: 700;
            color: var(--text-muted);
            margin-bottom: 6px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .hub-field-row {
            display: flex;
            gap: 8px;
            align-items: center;
        }
        .hub-field-input {
            flex: 1;
            min-width: 0;
            min-height: 38px;
            height: 38px;
            font-size: 13px;
            padding: 0 12px;
            border-radius: 8px;
            border: 1px solid var(--border);
            background: var(--surface);
            color: var(--text-main);
            box-sizing: border-box;
        }
        .hub-field-actions {
            display: flex;
            gap: 8px;
            align-items: center;
            flex-shrink: 0;
        }
        .hub-btn-apply {
            height: 38px;
            font-size: 13px;
            font-weight: 600;
            min-width: 175px;
            padding: 0 14px;
            white-space: nowrap;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
        }
        .hub-btn-clear {
            height: 38px;
            font-size: 13px;
            font-weight: 600;
            min-width: 135px;
            padding: 0 12px;
            white-space: nowrap;
            color: #ef4444 !important;
            border: 1px solid rgba(239, 68, 68, 0.35) !important;
            background: transparent;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
            transition: all 0.15s ease;
        }
        .hub-btn-clear:hover {
            background: rgba(239, 68, 68, 0.1) !important;
            border-color: #ef4444 !important;
            color: #dc2626 !important;
        }
        .hub-bulk-footer {
            display: flex;
            gap: 8px;
            flex-wrap: wrap;
            align-items: center;
            justify-content: space-between;
            border-top: 1px solid var(--border);
            padding-top: 12px;
            margin-top: 14px;
        }
        input.hub-search-input, .hub-search-input {
            width: 100%;
            height: 36px;
            padding: 0 32px 0 44px !important;
            border-radius: 8px;
            background: var(--surface);
            border: 1px solid var(--border);
            color: var(--text-main);
            font-size: 13px;
            outline: none;
            box-sizing: border-box;
            transition: all 0.2s;
        }
        .hub-search-input::placeholder {
            color: var(--text-muted);
            opacity: 0.8;
        }
        .hub-search-input:focus {
            border-color: var(--primary);
            background: var(--surface-hover);
            box-shadow: 0 0 0 2px rgba(0, 119, 255, 0.15);
        }
        dialog {
            background: var(--surface);
            color: var(--text-main);
            border: 1px solid var(--border);
            border-radius: 18px;
            padding: 20px;
            margin: auto;
            max-width: 500px;
            width: min(500px, calc(100% - 24px));
            outline: none;
            box-shadow: 0 20px 40px rgba(0,0,0,0.25);
        }
        dialog::backdrop { background: rgba(0, 0, 0, 0.6); backdrop-filter: blur(4px); }
        .input-group {
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-bottom: 12px;
        }
        label { font-size: 12px; color: var(--text-muted); font-weight: 600; }
        input[type="text"], input[type="number"], input:not([type]), input {
            background: var(--surface-hover);
            border: 1px solid var(--border);
            border-radius: 10px;
            height: 40px;
            padding: 0 12px;
            color: var(--text-main);
            font-size: 14px;
            outline: none;
            transition: all 0.2s;
            box-sizing: border-box;
        }
        input:focus { border-color: var(--primary); }
        .dlg-actions {
            display: flex;
            justify-content: flex-end;
            gap: 8px;
            margin-top: 16px;
        }
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%);
            background: var(--surface);
            color: var(--text-main);
            padding: 10px 18px;
            border-radius: 10px;
            font-size: 13px;
            border: 1px solid var(--border);
            box-shadow: 0 10px 30px rgba(0,0,0,0.3);
            z-index: 10000;
            display: none;
            font-weight: 500;
        }
        #qrCodeContainer {
            background: #ffffff;
            padding: 14px;
            border-radius: 16px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 16px rgba(0,0,0,0.12);
            border: 1px solid var(--border);
            width: 240px;
            height: 240px;
            max-width: 100%;
            aspect-ratio: 1;
            box-sizing: border-box;
            overflow: hidden;
            margin: 0 auto;
        }
        #qrCodeContainer svg {
            width: 100% !important;
            height: 100% !important;
            max-width: 100% !important;
            max-height: 100% !important;
            display: block !important;
        }
        .show-mobile { display: none; }
        .hide-mobile { display: inline-block; }
        @media (max-width: 600px) {
            body { padding: 12px 8px; }
            .main-card { padding: 16px; border-radius: 16px; gap: 12px; }
            .group-dlg-actions { flex-direction: column; align-items: stretch; gap: 8px; }
            .group-act-btn { width: 100%; justify-content: center; }
            .group-search-wrapper { width: 100%; min-width: 0; }
            .client-cards-grid { grid-template-columns: 1fr; }
            .show-mobile { display: inline-block !important; }
            .hide-mobile { display: none !important; }
            .hub-field-row {
                flex-direction: column;
                align-items: stretch;
                gap: 8px;
            }
            .hub-field-row .hub-field-input {
                width: 100% !important;
                height: 38px !important;
                min-height: 38px !important;
                flex: none !important;
                box-sizing: border-box !important;
            }
            .hub-field-actions {
                width: 100%;
                display: flex;
                gap: 8px;
            }
            .hub-btn-apply,
            .hub-btn-clear {
                flex: 1;
                width: auto;
                min-width: 0;
                font-size: 12px;
                padding: 0 8px;
                height: 38px;
                box-sizing: border-box;
            }
            .hub-field-row.hub-workers-row {
                flex-direction: row;
                align-items: center;
                gap: 8px;
            }
            .hub-field-row.hub-workers-row .hub-workers-input {
                width: 75px !important;
                flex: 0 0 75px !important;
                height: 38px !important;
                min-height: 38px !important;
                text-align: center;
                box-sizing: border-box !important;
            }
            .hub-field-row.hub-workers-row .hub-field-actions {
                flex: 1;
                min-width: 0;
            }
            .hub-bulk-footer {
                flex-direction: column;
                align-items: stretch;
                gap: 10px;
            }
            .hub-search-container {
                width: 100% !important;
            }
            .hub-search-input {
                height: 38px;
                font-size: 13px;
            }
        }
    </style>
</head>
<body>
    <div class="main-card">
        <div class="dlg-header">
            <div style="display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0;">
                <h2 id="groupDlgTitle" style="font-size: 22px; font-weight: 700; margin: 0; line-height: 1.2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">Группа</h2>
                <span class="badge admin">Админ</span>
            </div>
            <button class="btn btn-ghost" onclick="toggleTheme()" title="Сменить тему" style="width: 36px; height: 36px; padding: 0; border: 1px solid var(--border); border-radius: 10px;">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line></svg>
            </button>
        </div>
        <div id="statsSummary" class="stats-summary">Загрузка данных...</div>
        <div class="group-dlg-actions">
            <button class="btn btn-primary group-act-btn" onclick="openNewClientModal()">Создать пароль</button>
            <button class="btn btn-ghost group-act-btn" onclick="openLinksAndTokensDlg()">Настройка ссылок</button>
            <div class="group-search-wrapper">
                <input type="text" id="groupSearchInput" class="group-search-input" placeholder="Поиск в группе..." oninput="handleSearch(this.value)" style="padding-left: 46px !important;">
                <svg class="group-search-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
            </div>
        </div>
        <div id="groupClientsCards" class="client-cards-grid"></div>
    </div>

    <dialog id="newClientModal">
        <div class="dlg-header" style="margin-bottom: 14px;">
            <h3 style="font-size: 18px; margin: 0; font-weight: 700;">Создать пароль</h3>
            <button class="btn btn-ghost" style="width: 32px; height: 32px; padding: 0; border: none; font-size: 16px;" onclick="document.getElementById('newClientModal').close()">✕</button>
        </div>
        <div class="input-group">
            <label>Имя клиента</label>
            <input type="text" id="newClientName" placeholder="Например: Клиент123">
        </div>
        <div class="input-group">
            <label>Срок действия (0 = Бессрочно, 1-365 Ручной лимит)</label>
            <input type="number" id="newClientDays" value="30" min="0" max="365">
        </div>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('newClientModal').close()">Отмена</button>
            <button class="btn btn-primary" onclick="submitNewClient()">Создать</button>
        </div>
    </dialog>

    <dialog id="editClientModal">
        <div class="dlg-header" style="margin-bottom: 14px;">
            <h3 style="font-size: 18px; margin: 0; font-weight: 700;">Управление клиентом</h3>
            <button class="btn btn-ghost" style="width: 32px; height: 32px; padding: 0; border: none; font-size: 16px;" onclick="document.getElementById('editClientModal').close()">✕</button>
        </div>
        <div class="input-group">
            <label>Имя клиента</label>
            <input type="text" id="editName">
        </div>
        <div class="input-group">
            <label>Пароль (ключ)</label>
            <div style="display: flex; gap: 8px; align-items: center;">
                <input type="text" id="editPass" readonly style="flex: 1; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--text-muted);">
                <button class="btn btn-ghost" onclick="copyPasswordText()" style="width: 40px; height: 40px; padding: 0;" title="Копировать"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg></button>
                <button class="btn btn-ghost" onclick="openQrModalForActiveClient()" style="width: 40px; height: 40px; padding: 0;" title="QR-код"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg></button>
            </div>
        </div>
        <div class="input-group">
            <label>Срок действия (0 = Бессрочно, 1-365 Ручной лимит)</label>
            <input type="number" id="editDays" min="0" max="365">
        </div>
        <div style="border: 1px solid var(--border); border-radius: 12px; padding: 12px; margin: 12px 0; font-size: 13px; display: flex; flex-direction: column; gap: 8px; background: var(--surface-hover);">
            <div style="display: flex; justify-content: space-between;"><span style="color: var(--text-muted);">IP-адрес:</span><span id="editIp">—</span></div>
            <div style="display: flex; justify-content: space-between;"><span style="color: var(--text-muted);">Трафик:</span><span id="editTraffic" style="font-family: ui-monospace, SFMono-Regular, Consolas, monospace;">↑ 0 B / ↓ 0 B</span></div>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px;">
            <button class="btn btn-ghost" onclick="executeResetTraffic()" style="flex: 1; height: 38px;">Сбросить трафик</button>
            <button class="btn btn-ghost" onclick="executeExtend30()" style="flex: 1; height: 38px;">Продлить +30 дн.</button>
            <button class="btn btn-ghost" id="editToggleBtn" onclick="executeToggleClient()" style="flex: 1; height: 38px;">Отключить</button>
        </div>
        <div class="dlg-actions" style="justify-content: space-between;">
            <button class="btn btn-danger" onclick="executeDeleteClient()" style="height: 38px;">Удалить</button>
            <button class="btn btn-primary" onclick="saveClientChanges()" style="height: 38px;">Сохранить</button>
        </div>
    </dialog>

    <dialog id="qrDlg" style="max-width: 440px; width: min(440px, calc(100% - 24px)); text-align: center;">
        <div class="dlg-header" style="justify-content: space-between;">
            <h3 id="qrDlgTitle" style="font-size: 18px; margin: 0; font-weight: 700;">QR-код клиента</h3>
            <button class="btn btn-ghost" style="width: 32px; height: 32px; padding: 0; border: none; font-size: 16px;" onclick="document.getElementById('qrDlg').close()">✕</button>
        </div>
        <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; margin: 16px 0;">
            <div id="qrCodeContainer"></div>
            <div style="width: 100%; display: flex; gap: 8px; align-items: center;">
                <input type="text" id="qrDlgLink" readonly style="flex: 1; height: 40px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--text-muted);" onclick="this.select()">
                <button class="btn btn-ghost" onclick="copyQrLink()" style="height: 40px; width: 44px; padding: 0;" title="Копировать"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg></button>
            </div>
        </div>
        <button class="btn btn-primary" style="width: 100%; height: 40px;" onclick="document.getElementById('qrDlg').close()">Закрыть</button>
    </dialog>

    <dialog id="linksAndTokensDlg" style="max-width: 820px; width: min(820px, calc(100% - 24px)); max-height: 88vh; overflow-y: auto;">
        <div class="dlg-header">
            <h2 style="font-size: 20px; font-weight: 700; margin: 0;">Настройка ссылок</h2>
            <button class="btn btn-ghost" style="width: 32px; height: 32px; padding: 0; border: none; font-size: 16px;" onclick="document.getElementById('linksAndTokensDlg').close()">✕</button>
        </div>

        <!-- Top bulk settings panel -->
        <div class="hub-bulk-panel">
            <div class="hub-field-block">
                <label class="hub-field-label">VK токен группы</label>
                <div class="hub-field-row">
                    <input id="hubVkTokenInput" class="hub-field-input" placeholder="Введите VK токен..." onchange="autoSaveHubVkToken()" onblur="autoSaveHubVkToken()">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubVkTokenDirect()"><span class="hide-mobile">Применить VK токен</span><span class="show-mobile">Применить токен</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-token')"><span class="hide-mobile">Очистить токен</span><span class="show-mobile">Очистить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-field-block">
                <label class="hub-field-label">VK хеши группы (через запятую)</label>
                <div class="hub-field-row">
                    <input id="hubVkHashesInput" class="hub-field-input" placeholder="hash1,hash2,...,hash6" onchange="autoSaveHubVkHashes()" onblur="autoSaveHubVkHashes()">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubVkHashesDirect()"><span class="hide-mobile">Применить VK хеши</span><span class="show-mobile">Применить хеши</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-hashes')"><span class="hide-mobile">Очистить хеши</span><span class="show-mobile">Очистить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-field-block">
                <label class="hub-field-label">Установить число потоков (0 = Клиентские настройки, 3-99 Ручной лимит)</label>
                <div class="hub-field-row hub-workers-row">
                    <input type="number" id="hubWorkersInput" class="hub-field-input hub-workers-input" min="0" max="99" value="0" placeholder="0 (Клиентские)">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubWorkersDirect()"><span class="hide-mobile">Установить число потоков</span><span class="show-mobile">Установить</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-workers')"><span class="hide-mobile">Сбросить число потоков</span><span class="show-mobile">Сбросить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-bulk-footer">
                <div style="display: flex; align-items: center; gap: 8px; font-size: 13px;">
                    <input type="checkbox" id="hubSelectAll" onchange="toggleSelectAllHubClients(this.checked)" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--primary);">
                    <label for="hubSelectAll" style="cursor: pointer; font-weight: 600;">Выбрать всех</label>
                    <span id="hubSelectedCount" style="color: var(--text-muted); font-size: 12px;">(0 выбрано)</span>
                </div>
                <div class="hub-search-container" style="position: relative; width: 280px; max-width: 100%;">
                    <input type="text" id="hubSearchInput" class="hub-search-input" placeholder="Клиент (группа)..." oninput="handleHubSearch(this.value)" style="padding-left: 44px !important; padding-right: 32px !important;">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--text-muted); pointer-events: none;"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                    <button id="hubSearchClearBtn" onclick="clearHubSearch()" style="display: none; position: absolute; right: 8px; top: 50%; transform: translateY(-50%); background: none; border: none; color: var(--text-muted); cursor: pointer; padding: 4px; align-items: center; justify-content: center;" title="Очистить">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                    </button>
                </div>
            </div>
        </div>

        <!-- Clients links list -->
        <div id="hubClientsList" style="display: flex; flex-direction: column; gap: 10px;"></div>
    </dialog>

    <dialog id="batchConfirmDlg" style="max-width: 440px; width: min(440px, calc(100% - 24px));">
        <h3 id="batchConfirmTitle" style="font-size: 18px; margin: 0 0 12px 0; font-weight: 700;">Подтверждение</h3>
        <p id="batchConfirmMessage" style="font-size: 14px; color: var(--text-muted); line-height: 1.5; margin-bottom: 24px;"></p>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('batchConfirmDlg').close()">Отмена</button>
            <button class="btn btn-primary" id="batchConfirmSubmitBtn">Применить</button>
        </div>
    </dialog>

    <div id="toast" class="toast"></div>

    <script>
        function toggleTheme() {
            const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
            document.documentElement.setAttribute('data-theme', isDark ? 'light' : 'dark');
            localStorage.setItem('csqtt-theme', isDark ? 'light' : 'dark');
        }
        if (localStorage.getItem('csqtt-theme') === 'dark') {
            document.documentElement.setAttribute('data-theme', 'dark');
        }

        const adminUuid = window.location.pathname.split('/').filter(Boolean)[0];
        let currentData = null;
        let activeClient = null;

        function esc(s) {
            return String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
        }

        function size(b) {
            if (!b || b <= 0) return '0 B';
            const units = ['B', 'KB', 'MB', 'GB', 'TB'];
            const i = Math.floor(Math.log(b) / Math.log(1024));
            return (b / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
        }

        function calcMatchScore(text, query) {
            if (!text || !query) return 0;
            const t = String(text).trim().toLowerCase();
            const q = String(query).trim().toLowerCase();
            if (!t || !q) return 0;
            if (t === q) return 1000;
            if (t.startsWith(q)) return 500 + Math.max(0, 100 - t.length);
            const words = t.split(/[\s,._\-#№]+|(?<=[^\d\s])(?=\d)|(?<=\d)(?=[^\d\s])/);
            for (const w of words) {
                if (w === q) return 450 + Math.max(0, 100 - w.length);
                if (w.startsWith(q)) return 400 + Math.max(0, 100 - w.length);
            }
            const idx = t.indexOf(q);
            if (idx !== -1) return 200 + Math.max(0, 100 - idx);
            return 0;
        }

        function generateUniqueClientName(existingNames) {
            const set = new Set((existingNames || []).map(n => String(n || '').trim().toLowerCase()));
            for (let i = 0; i < 1000; i++) {
                const candidate = 'Клиент' + Math.floor(100 + Math.random() * 900);
                if (!set.has(candidate.toLowerCase())) {
                    return candidate;
                }
            }
            return 'Клиент' + Math.floor(100 + Math.random() * 900);
        }

        function showToast(msg, isError) {
            const t = document.getElementById('toast');
            t.textContent = msg;
            t.style.borderColor = isError ? 'var(--danger)' : 'var(--border)';
            t.style.display = 'block';
            setTimeout(() => { t.style.display = 'none'; }, 2500);
        }

        async function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try {
                    await navigator.clipboard.writeText(text);
                    return true;
                } catch (e) {}
            }
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                document.body.removeChild(ta);
                return true;
            } catch (e) {
                return false;
            }
        }

        async function loadData() {
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/data');
                if (!r.ok) {
                    document.getElementById('statsSummary').textContent = 'Ошибка загрузки данных группы';
                    return;
                }
                currentData = await r.json();
                renderView();
            } catch (e) {
                document.getElementById('statsSummary').textContent = 'Ошибка сети';
            }
        }

        function renderView() {
            if (!currentData) return;
            document.getElementById('groupDlgTitle').textContent = currentData.group.name || 'Группа';
            const clients = currentData.clients || [];
            let sumUp = 0, sumDown = 0, sumSessions = 0;
            for (const c of clients) {
                sumUp += (c.up || 0);
                sumDown += (c.down || 0);
                sumSessions += (c.active_sessions || 0);
            }
            document.getElementById('statsSummary').textContent = `Участники: ${clients.length} · Трафик: ↑ ${size(sumUp)} / ↓ ${size(sumDown)} · Потоки: ${sumSessions}`;
            renderCards(clients);
        }

        function renderCards(clients) {
            const query = (document.getElementById('groupSearchInput')?.value || '').trim().toLowerCase();
            const filtered = query ? clients.filter(c => (c.name || '').toLowerCase().includes(query) || c.password.toLowerCase().includes(query)) : clients;
            const container = document.getElementById('groupClientsCards');
            if (filtered.length === 0) {
                container.innerHTML = '<div style="grid-column: 1/-1; text-align: center; padding: 32px; color: var(--text-muted); font-size: 14px;">' + (query ? 'Ничего не найдено' : 'В группе пока нет клиентов. Нажмите «Создать пароль».') + '</div>';
                return;
            }
            container.innerHTML = filtered.map(c => {
                const expiryStr = c.expires ? new Date(c.expires * 1000).toLocaleDateString() : '∞ Бессрочно';
                return `
                <div class="client-card" onclick="openEditModal('${esc(c.password)}')">
                    <div class="client-card-header">
                        <div class="client-card-title">
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
                            <span>${esc(c.name || 'Клиент')}</span>
                        </div>
                        <span class="badge ${c.active ? 'active' : 'inactive'}">${c.active ? 'Участник' : 'Неактивно'}</span>
                    </div>
                    <div class="client-card-body">
                        <div class="client-card-row">
                            <span class="client-card-label">IP:</span>
                            <span class="client-card-value">${esc(c.ip || '—')}</span>
                        </div>
                        <div class="client-card-row">
                            <span class="client-card-label">Срок действия:</span>
                            <span class="client-card-value">${expiryStr}</span>
                        </div>
                        <div class="client-card-row">
                            <span class="client-card-label">Трафик:</span>
                            <span class="client-card-value" style="font-family: ui-monospace, SFMono-Regular, Consolas, monospace;">↑ ${size(c.up)} / ↓ ${size(c.down)}</span>
                        </div>
                        <div class="client-card-row">
                            <span class="client-card-label">Потоки:</span>
                            <span class="client-card-value">${c.active_sessions || 0}</span>
                        </div>
                        ${c.sub_uuid ? `
                        <button class="btn btn-ghost client-sub-btn" onclick="event.stopPropagation(); copyClientSubLink('${c.sub_uuid}', this)" title="Скопировать ссылку подписки">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                            <span>Скопировать ссылку подписки</span>
                        </button>
                        ` : ''}
                    </div>
                </div>`;
            }).join('');
        }

        function handleSearch(val) {
            if (currentData) renderCards(currentData.clients || []);
        }

        function openNewClientModal() {
            const existing = (currentData?.clients || []).map(c => c.name);
            document.getElementById('newClientName').value = generateUniqueClientName(existing);
            document.getElementById('newClientDays').value = '30';
            document.getElementById('newClientModal').showModal();
        }

        async function submitNewClient() {
            let name = document.getElementById('newClientName').value.trim();
            if (!name) {
                const existing = (currentData?.clients || []).map(c => c.name);
                name = generateUniqueClientName(existing);
            }
            const days = parseInt(document.getElementById('newClientDays').value, 10) || 0;
            const workers = 0;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients', {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ name, days, workers })
                });
                if (r.ok) {
                    showToast('Пароль создан');
                    document.getElementById('newClientModal').close();
                    await loadData();
                } else {
                    const msg = await r.text().catch(() => '');
                    showToast(msg || 'Ошибка создания пароля', true);
                }
            } catch (e) {
                showToast('Ошибка сети', true);
            }
        }

        function openEditModal(password) {
            const c = (currentData?.clients || []).find(x => x.password === password);
            if (!c) return;
            activeClient = c;
            document.getElementById('editName').value = c.name || '';
            document.getElementById('editPass').value = c.password;
            let remainingDays = 0;
            if (c.expires) {
                remainingDays = Math.max(0, Math.ceil((c.expires * 1000 - Date.now()) / 86400000));
            }
            document.getElementById('editDays').value = c.expires ? remainingDays : 0;
            document.getElementById('editIp').textContent = c.ip || '—';
            document.getElementById('editTraffic').textContent = `↑ ${size(c.up)} / ↓ ${size(c.down)}`;
            const toggleBtn = document.getElementById('editToggleBtn');
            toggleBtn.textContent = c.is_deactivated ? 'Включить' : 'Отключить';
            toggleBtn.style.color = c.is_deactivated ? '#10b981' : '#ef4444';
            document.getElementById('editClientModal').showModal();
        }

        async function copyPasswordText() {
            if (activeClient && await copyToClipboard(activeClient.password)) {
                showToast('Пароль скопирован');
            }
        }

        async function executeResetTraffic() {
            if (!activeClient) return;
            if (!confirm('Сбросить трафик для этого клиента?')) return;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password) + '/reset-traffic', { method: 'POST' });
                if (r.ok) {
                    showToast('Трафик сброшен');
                    document.getElementById('editClientModal').close();
                    await loadData();
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        async function executeExtend30() {
            if (!activeClient) return;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password) + '/extend', { method: 'POST' });
                if (r.ok) {
                    showToast('Срок продлён на 30 дней');
                    document.getElementById('editClientModal').close();
                    await loadData();
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        async function executeToggleClient() {
            if (!activeClient) return;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password) + '/toggle', { method: 'POST' });
                if (r.ok) {
                    showToast('Статус клиента изменён');
                    document.getElementById('editClientModal').close();
                    await loadData();
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        async function executeUnbind() {
            if (!activeClient) return;
            if (!confirm('Отвязать устройство?')) return;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password) + '/unbind', { method: 'POST' });
                if (r.ok) {
                    showToast('Устройство отвязано');
                    document.getElementById('editClientModal').close();
                    await loadData();
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        async function executeDeleteClient() {
            if (!activeClient) return;
            if (!confirm('Удалить клиента? Это действие необратимо.')) return;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password), { method: 'DELETE' });
                if (r.ok) {
                    showToast('Клиент удалён');
                    document.getElementById('editClientModal').close();
                    await loadData();
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        async function saveClientChanges() {
            if (!activeClient) return;
            let name = document.getElementById('editName').value.trim();
            if (!name) {
                const existing = (currentData?.clients || []).filter(c => c.password !== activeClient.password).map(c => c.name);
                name = generateUniqueClientName(existing);
            }
            const days = parseInt(document.getElementById('editDays').value, 10);
            const workers = activeClient.workers || 0;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(activeClient.password), {
                    method: 'PUT',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ name, days, workers })
                });
                if (r.ok) {
                    showToast('Изменения сохранены');
                    document.getElementById('editClientModal').close();
                    await loadData();
                } else {
                    const msg = await r.text().catch(() => '');
                    showToast(msg || 'Ошибка сохранения', true);
                }
            } catch (e) { showToast('Ошибка сети', true); }
        }

        function generateQrSvg(text) {
            try {
                const qr = qrcode(0, 'L');
                qr.addData(text);
                qr.make();
                return qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
            } catch (e) {
                return '<div style="color: #ef4444; padding: 16px;">Ошибка генерации QR</div>';
            }
        }

        function openQrModalForActiveClient() {
            if (!activeClient) return;
            const link = activeClient.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            document.getElementById('qrDlgTitle').textContent = 'QR-код: ' + (activeClient.name || 'Клиент');
            document.getElementById('qrDlgLink').value = link;
            const container = document.getElementById('qrCodeContainer');
            container.innerHTML = generateQrSvg(link);
            document.getElementById('qrDlg').showModal();
        }

        async function copyClientSubLink(subUuid, btn) {
            if (!subUuid) {
                showToast("У клиента нет ссылки подписки", true);
                return;
            }
            const url = window.location.origin + '/' + subUuid + '/sub';
            if (await copyToClipboard(url)) {
                if (btn) {
                    btn.style.color = '#10b981';
                    setTimeout(() => { btn.style.color = ''; }, 1500);
                }
                showToast("Ссылка подписки скопирована");
            } else {
                showToast("Не удалось скопировать", true);
            }
        }

        async function copyQrLink() {
            const link = document.getElementById('qrDlgLink')?.value || '';
            if (await copyToClipboard(link)) {
                showToast('Ссылка скопирована');
            }
        }

        let hubSelectedPasswords = new Set();
        let currentHubFilterQuery = '';
        let groupVkToken = '';
        let groupVkHashes = '';

        function openLinksAndTokensDlg() {
            hubSelectedPasswords.clear();
            currentHubFilterQuery = '';
            const searchInput = document.getElementById('hubSearchInput');
            if (searchInput) searchInput.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';

            groupVkToken = currentData?.group?.vk_token || '';
            groupVkHashes = currentData?.group?.vk_hashes || '';

            const tokenInput = document.getElementById('hubVkTokenInput');
            if (tokenInput) tokenInput.value = groupVkToken;
            const hashesInput = document.getElementById('hubVkHashesInput');
            if (hashesInput) hashesInput.value = groupVkHashes;
            const workersInput = document.getElementById('hubWorkersInput');
            if (workersInput) workersInput.value = currentData?.group?.workers || '0';
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;

            renderHubClientsList();
            updateHubSelectedCount();
            document.getElementById('linksAndTokensDlg').showModal();
        }

        async function autoSaveHubVkToken() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (token && (/\s/.test(token) || token.length > 4096)) return false;
            if (token === groupVkToken) return true;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/settings', {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ vk_token: token })
                });
                if (r.ok) {
                    groupVkToken = token;
                    if (currentData && currentData.group) {
                        currentData.group.vk_token = token;
                    }
                    await loadData();
                    renderHubClientsList();
                    return true;
                }
            } catch (e) {}
            return false;
        }

        async function autoSaveHubVkHashes() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            if (raw === groupVkHashes) return true;
            try {
                const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/settings', {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ vk_hashes: raw })
                });
                if (r.ok) {
                    groupVkHashes = raw;
                    if (currentData && currentData.group) {
                        currentData.group.vk_hashes = raw;
                    }
                    await loadData();
                    renderHubClientsList();
                    return true;
                }
            } catch (e) {}
            return false;
        }

        async function applyHubVkTokenDirect() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (!token) {
                showToast("Введите VK токен", true);
                return;
            }
            if (/\s/.test(token) || token.length > 4096) {
                showToast("VK токен не должен содержать пробелы и должен быть короче 4097 символов", true);
                return;
            }
            await autoSaveHubVkToken();
            promptBatchAction('token');
        }

        async function applyHubVkHashesDirect() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
            if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", true);
                return;
            }
            await autoSaveHubVkHashes();
            promptBatchAction('hashes');
        }

        async function applyHubWorkersDirect() {
            const input = document.getElementById('hubWorkersInput');
            const w = parseInt(input ? input.value : '0', 10);
            if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 99) {
                showToast("Число потоков должно быть 0 (клиентские) или от 3 до 99", true);
                return;
            }
            promptBatchAction('workers');
        }

        function handleHubSearch(val) {
            currentHubFilterQuery = val.trim();
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = currentHubFilterQuery ? 'flex' : 'none';
            renderHubClientsList();
        }

        function clearHubSearch() {
            const searchInput = document.getElementById('hubSearchInput');
            if (searchInput) searchInput.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';
            currentHubFilterQuery = '';
            renderHubClientsList();
        }

        function renderHubClientsList(preservedChecked = null) {
            const container = document.getElementById('hubClientsList');
            if (!container) return;
            if (preservedChecked !== null) {
                hubSelectedPasswords.clear();
                preservedChecked.forEach(p => hubSelectedPasswords.add(p));
            }
            const allClients = currentData?.clients || [];
            if (!allClients.length) {
                container.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 24px;">Нет созданных клиентов</div>';
                updateHubSelectedCount();
                return;
            }

            const groupName = currentData?.group?.name || 'Группа';
            let displayClients = [];
            const q = currentHubFilterQuery;

            if (!q) {
                displayClients = allClients.map(c => ({
                    client: c,
                    groupName: groupName,
                    score: 0
                }));
            } else {
                const bracketMatch = q.match(/^(.*?)\((.*?)\)(.*)$/);
                let mode = 'name_only';
                let namePart = '';
                let groupPart = '';

                if (bracketMatch) {
                    const outside = (bracketMatch[1] + ' ' + bracketMatch[3]).trim();
                    const inside = bracketMatch[2].trim();
                    if (!outside) {
                        mode = 'group_only';
                        groupPart = inside;
                    } else {
                        mode = 'name_and_group';
                        namePart = outside;
                        groupPart = inside;
                    }
                } else {
                    const openIdx = q.lastIndexOf('(');
                    if (openIdx !== -1) {
                        const outside = q.slice(0, openIdx).trim();
                        const inside = q.slice(openIdx + 1).trim();
                        if (!outside) {
                            mode = 'group_only';
                            groupPart = inside;
                        } else {
                            mode = 'name_and_group';
                            namePart = outside;
                            groupPart = inside;
                        }
                    } else {
                        mode = 'name_only';
                        namePart = q;
                    }
                }

                const scored = [];
                for (const c of allClients) {
                    const cName = c.name || 'Клиент';
                    let score = 0;

                    if (mode === 'name_only') {
                        score = calcMatchScore(cName, namePart);
                    } else if (mode === 'group_only') {
                        if (groupName) {
                            if (!groupPart) {
                                score = 100;
                            } else {
                                score = calcMatchScore(groupName, groupPart);
                            }
                        }
                    } else if (mode === 'name_and_group') {
                        const nScore = namePart ? calcMatchScore(cName, namePart) : 0;
                        const gScore = (groupPart && groupName) ? calcMatchScore(groupName, groupPart) : 0;
                        if (nScore > 0 && gScore > 0) {
                            score = 2000 + nScore + gScore;
                        } else if (nScore > 0) {
                            score = nScore;
                        } else if (gScore > 0) {
                            score = gScore;
                        }
                    }

                    if (score > 0) {
                        scored.push({ client: c, groupName: groupName, score });
                    }
                }

                scored.sort((a, b) => b.score - a.score);
                displayClients = scored;
            }

            if (!displayClients.length) {
                container.innerHTML = `<div style="color: var(--text-muted); text-align: center; padding: 24px;">По запросу «<b>${esc(q)}</b>» ничего не найдено</div>`;
                updateHubSelectedCount();
                return;
            }

            container.innerHTML = displayClients.map(item => {
                const c = item.client;
                const link = c.link || '';
                let badge = '';
                const hashes = String(c.vk_hashes || '').trim();
                if (c.vk_token) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Токен</span>';
                } else if (hashes) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Хеши</span>';
                } else {
                    badge = '<span class="badge inactive" style="font-size: 11px;">Прямой</span>';
                }
                if (c.workers && c.workers >= 3) {
                    badge += ` <span class="badge active" style="font-size: 11px;">Потоки: ${c.workers}</span>`;
                }
                const isChecked = hubSelectedPasswords.has(c.password);
                const displayName = `${c.name || 'Клиент'} (${groupName})`;

                return `
                <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 8px;">
                    <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap;">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <input type="checkbox" class="hub-client-cb" value="${esc(c.password)}" ${isChecked ? 'checked' : ''} onchange="handleHubClientCbChange(this)" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--primary);">
                            <span style="font-weight: 600; font-size: 14px; color: var(--text-main);">${esc(displayName)}</span>
                            ${badge}
                        </div>
                    </div>
                    <div style="display: flex; gap: 8px; align-items: center;">
                        <input readonly value="${esc(link)}" style="flex: 1; height: 40px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; padding: 0 10px; background: var(--surface-hover); color: var(--text-muted); border-radius: 8px;" onclick="this.select()">
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="copyHubClientLink('${esc(c.password)}', this)" title="Копировать ссылку">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        </button>
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="openQrModalForClient('${esc(c.password)}')" title="QR-код">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg>
                        </button>
                    </div>
                </div>
                `;
            }).join('');

            updateHubSelectedCount();
        }

        function handleHubClientCbChange(cb) {
            if (cb.checked) {
                hubSelectedPasswords.add(cb.value);
            } else {
                hubSelectedPasswords.delete(cb.value);
            }
            updateHubSelectedCount();
        }

        function toggleSelectAllHubClients(checked) {
            const cbs = document.querySelectorAll('.hub-client-cb');
            cbs.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    hubSelectedPasswords.add(cb.value);
                } else {
                    hubSelectedPasswords.delete(cb.value);
                }
            });
            updateHubSelectedCount();
        }

        function updateHubSelectedCount() {
            const visibleCbs = document.querySelectorAll('.hub-client-cb');
            const checkedCount = hubSelectedPasswords.size;
            const countEl = document.getElementById('hubSelectedCount');
            if (countEl) countEl.textContent = `(${checkedCount} выбрано)`;
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) {
                selectAll.checked = (visibleCbs.length > 0 && Array.from(visibleCbs).every(cb => cb.checked));
            }
        }

        async function copyHubClientLink(password, btn = null) {
            const client = (currentData?.clients || []).find(c => c.password === password);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            if (await copyToClipboard(link)) {
                showToast("Ссылка скопирована");
                if (btn) {
                    const orig = btn.innerHTML;
                    btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
                    setTimeout(() => { btn.innerHTML = orig; }, 1500);
                }
            } else {
                showToast("Не удалось скопировать", true);
            }
        }

        function openQrModalForClient(password) {
            const client = (currentData?.clients || []).find(c => c.password === password);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            document.getElementById('qrDlgTitle').textContent = 'QR-код: ' + (client.name || 'Клиент');
            document.getElementById('qrDlgLink').value = link;
            const container = document.getElementById('qrCodeContainer');
            container.innerHTML = generateQrSvg(link);
            document.getElementById('qrDlg').showModal();
        }

        let currentBatchAction = null;

        function promptBatchAction(action) {
            if (!hubSelectedPasswords.size) {
                const checkedCbs = Array.from(document.querySelectorAll('.hub-client-cb:checked'));
                checkedCbs.forEach(cb => hubSelectedPasswords.add(cb.value));
            }
            if (!hubSelectedPasswords.size) {
                showToast("Выберите хотя бы одного клиента", true);
                return;
            }
            const count = hubSelectedPasswords.size;
            const titleEl = document.getElementById('batchConfirmTitle');
            const msgEl = document.getElementById('batchConfirmMessage');
            const submitBtn = document.getElementById('batchConfirmSubmitBtn');

            if (action === 'token') {
                const tokenVal = (document.getElementById('hubVkTokenInput')?.value || groupVkToken || '').trim();
                if (!tokenVal) {
                    showToast("Укажите VK токен", true);
                    return;
                }
                titleEl.textContent = "Применить VK токен";
                msgEl.textContent = `Включить общий VK токен для выбранных клиентов (${count} шт.)? Индивидуальные VK хеши будут очищены.`;
                submitBtn.textContent = "Применить токен";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-token') {
                titleEl.textContent = "Отключить VK токен";
                msgEl.textContent = `Отключить VK токен для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Отключить токен";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || groupVkHashes || '').trim();
                const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
                if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                    showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", true);
                    return;
                }
                titleEl.textContent = "Применить VK хеши";
                msgEl.textContent = `Установить указанные VK хеши для выбранных клиентов (${count} шт.)? Флаг VK токена будет отключен.`;
                submitBtn.textContent = "Применить хеши";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-hashes') {
                titleEl.textContent = "Очистить VK хеши";
                msgEl.textContent = `Очистить VK хеши для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Очистить хеши";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'workers') {
                const w = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10);
                if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 100) {
                    showToast("Число потоков должно быть 0 или от 3 до 100", true);
                    return;
                }
                titleEl.textContent = "Установить число потоков";
                msgEl.textContent = `Установить число потоков (${w === 0 ? 'Клиентские настройки (0)' : w}) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Установить число потоков";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-workers') {
                titleEl.textContent = "Сбросить число потоков";
                msgEl.textContent = `Сбросить число потоков до клиентских настроек (0) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Сбросить число потоков";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'clear') {
                titleEl.textContent = "Очистить параметры VK";
                msgEl.textContent = `Очистить VK токен и VK хеши у выбранных клиентов (${count} шт.)? Они перейдут в обычный режим прямого подключения.`;
                submitBtn.textContent = "Очистить";
                submitBtn.className = "btn btn-danger";
            }
            currentBatchAction = action;
            submitBtn.onclick = executeBatchAction;
            document.getElementById('batchConfirmDlg').showModal();
        }

        async function executeBatchAction() {
            const action = currentBatchAction;
            if (!action) return;
            const checkedPasses = Array.from(hubSelectedPasswords);
            const clients = (currentData?.clients || []).filter(c => checkedPasses.includes(c.password));

            const submitBtn = document.getElementById('batchConfirmSubmitBtn');
            submitBtn.disabled = true;
            submitBtn.textContent = "Применение...";

            let hashesVal = null;
            let tokenVal = null;
            let workersVal = null;

            if (action === 'token') {
                tokenVal = true;
                hashesVal = '';
            } else if (action === 'clear-token') {
                tokenVal = false;
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || groupVkHashes || '').trim();
                hashesVal = raw.split(',').map(s => s.trim()).filter(Boolean).join(',');
                tokenVal = false;
            } else if (action === 'clear-hashes') {
                hashesVal = '';
            } else if (action === 'workers') {
                workersVal = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10) || 0;
            } else if (action === 'clear-workers') {
                workersVal = 0;
                const winp = document.getElementById('hubWorkersInput');
                if (winp) winp.value = '0';
            } else if (action === 'clear') {
                tokenVal = false;
                hashesVal = '';
            }

            try {
                let successCount = 0;
                for (const c of clients) {
                    const payload = {};
                    if (hashesVal !== null) payload.hash = hashesVal;
                    if (tokenVal !== null) payload.vk_token = tokenVal;
                    if (workersVal !== null) payload.workers = workersVal;

                    const r = await fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/clients/' + encodeURIComponent(c.password), {
                        method: 'PUT',
                        headers: { 'content-type': 'application/json' },
                        body: JSON.stringify(payload)
                    });
                    if (r.ok) {
                        successCount++;
                    }
                }
                if (workersVal !== null) {
                    fetch('/' + encodeURIComponent(adminUuid) + '/api' + '/settings', {
                        method: 'POST',
                        headers: { 'content-type': 'application/json' },
                        body: JSON.stringify({ workers: workersVal })
                    }).catch(() => {});
                }
                document.getElementById('batchConfirmDlg').close();
                showToast(`Обновлено клиентов: ${successCount} из ${clients.length}`);
                await loadData();
                renderHubClientsList();
                updateHubSelectedCount();
            } finally {
                submitBtn.disabled = false;
            }
        }

        document.getElementById('linksAndTokensDlg')?.addEventListener('close', () => {
            hubSelectedPasswords.clear();
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;
        });

        loadData();
    </script>
</body>
</html>
"##;

const PANEL_HTML: &str = r##"
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0a0a0c">
    <meta name="mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <link rel="manifest" href="/manifest.webmanifest">
    <link rel="icon" type="image/svg+xml" href="/pwa-icon.svg">
    <link rel="apple-touch-icon" href="/pwa-icon.svg">
    <link rel="icon" type="image/png" sizes="192x192" href="/pwa-icon-192.png">
    <meta property="og:title" content="CSQTT">
    <meta property="og:type" content="website">
    <meta property="og:image" content="/pwa-icon.svg">
    <title>CSQTT</title>
    <script src="/qrcode.min.js"></script>
    <style>
        :root {
            --bg-color: #f8fafc;
            --surface: #ffffff;
            --surface-hover: #f1f5f9;
            --primary: #0077ff;
            --primary-hover: #0060cc;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --border: #e2e8f0;
            --glass: rgba(255, 255, 255, 0.9);
            --icon-bg: rgba(0, 119, 255, 0.1);
            --header-glass: rgba(255, 255, 255, 0.8);
        }

        [data-theme="dark"] {
            --bg-color: #000000;
            --surface: #0a0a0c;
            --surface-hover: #161619;
            --text-main: #f4f4f5;
            --text-muted: #9ca3af;
            --border: #1f1f23;
            --glass: rgba(10, 10, 12, 0.85);
            --header-glass: rgba(0, 0, 0, 0.8);
            --icon-bg: rgba(0, 119, 255, 0.15);
        }

        html { -webkit-text-size-adjust: none; text-size-adjust: none; }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, 'Segoe UI', Roboto, Arial, sans-serif; }
        body { background-color: var(--bg-color); color: var(--text-main); min-height: 100vh; display: flex; flex-direction: column; overflow-x: hidden; width: 100%; }

        body, header, .header-container, .brand, .brand-logo, .version-logo, .support-card, .header-actions, .btn, main, .glass-panel,
        .stat-card, .stat-icon, .stat-value, .stat-sub, .progress-bar, .progress-fill,
        .section-header, .section-title, .table-wrapper, table, th, td, tr,
        .client-name, .client-pw, .client-hash, .traffic-flex, .badge, .actions,
        .settings-grid, .setting-card, .input-group, .input-group input, .input-group select,
        .toggle-row, .switch, .slider, dialog, .dlg-row, .dlg-actions,
        svg, path, rect, line, polyline, circle, .tab-btn {
            transition: background-color 0.3s ease, border-color 0.3s ease, color 0.3s ease, fill 0.3s ease, stroke 0.3s ease, box-shadow 0.3s ease;
        }

        .glass-panel { background: var(--surface); border: 1px solid var(--border); border-radius: 20px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }

        header { position: sticky; top: 0; z-index: 40; height: 72px; background: var(--header-glass); backdrop-filter: blur(16px); border-bottom: 1px solid var(--border); width: 100%; }
        .header-container { max-width: 1300px; width: 100%; margin: 0 auto; padding: 0 24px; height: 100%; display: flex; align-items: center; justify-content: space-between; }
        .brand { display: flex; align-items: center; flex: 0 1 auto; min-width: 0; }
        .brand-logo { display: block; width: 165px; height: auto; }
        .version-logo { display: block; width: 180px; height: auto; flex: 0 1 auto; }
        .crypto-button {
            display: inline-flex; align-items: center; justify-content: center; width: 100%;
            height: 48px; min-height: 48px; padding: 0 16px; border: 0; border-radius: 12px;
            background: #1e293b; box-shadow: 0 4px 12px rgba(30, 41, 59, 0.38); text-decoration: none; cursor: pointer;
        }
        .crypto-button:hover { background: #334155; transform: translateY(-1px); box-shadow: 0 6px 16px rgba(30, 41, 59, 0.48); }
        .crypto-button:active { transform: scale(0.96); }
        .crypto-button:focus-visible { outline: 2px solid #38bdf8; outline-offset: 3px; }
        .crypto-logo { display: block; width: 120px; height: auto; }
        .telegram-button {
            display: inline-flex; align-items: center; justify-content: center; gap: 10px; width: 100%;
            height: 48px; min-height: 48px; padding: 0 16px; border: 0; border-radius: 12px;
            background: #24a1de; box-shadow: 0 4px 12px rgba(36, 161, 222, 0.32); text-decoration: none;
            color: #ffffff; font-size: 15px; font-weight: 600; cursor: pointer; transition: all 0.2s;
        }
        .telegram-button:hover { background: #1f94cd; transform: translateY(-1px); box-shadow: 0 6px 16px rgba(36, 161, 222, 0.44); color: #ffffff; }
        .telegram-button:active { transform: scale(0.96); }
        .telegram-button:focus-visible { outline: 2px solid #24a1de; outline-offset: 3px; }
        .tg-plane-icon { display: block; width: 22px; height: 22px; flex-shrink: 0; }

        .header-actions { display: flex; gap: 16px; align-items: center; }

        .restart-required-banner { display: none; width: 100%; padding: 11px 20px; background: #b91c1c; color: #fff; text-align: center; font-size: 13px; font-weight: 700; box-shadow: 0 8px 20px rgba(185, 28, 28, 0.24); transform: translateY(-100%); opacity: 0; }
        .restart-required-banner.visible { display: block; animation: restartBannerIn 0.32s ease-out forwards; }
        @keyframes restartBannerIn { to { transform: translateY(0); opacity: 1; } }

        .btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 10px 20px; font-size: 14px; font-weight: 600; border-radius: 12px; border: none; cursor: pointer; transition: all 0.2s; outline: none; }
        .btn:active { transform: scale(0.96); }
        .btn-primary { background: var(--primary); color: #fff; box-shadow: 0 4px 12px rgba(0, 119, 255, 0.25); }
        .btn-primary:hover { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(0, 119, 255, 0.35); }
        .btn:disabled { cursor: not-allowed; opacity: 0.42; transform: none !important; box-shadow: none; }
        .btn.save-success { background: #10b981; opacity: 1; }
        .btn-ghost { background: transparent; color: var(--text-muted); }
        .btn-ghost:hover { background: var(--surface-hover); color: var(--text-main); }
        .btn-icon { padding: 8px; border-radius: 10px; }
        .btn-api {
            background: transparent;
            color: var(--text-muted);
            border: none;
            border-radius: 8px;
            padding: 6px 10px;
            height: 36px;
            font-size: 14px;
            font-weight: 500;
            gap: 6px;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            transition: all 0.2s ease;
        }
        .btn-api:hover {
            background: var(--surface-hover);
            color: var(--text-main);
            transform: translateY(-1px);
        }
        .btn-api:active {
            transform: scale(0.96);
        }
        .btn-api svg {
            color: var(--primary);
        }

        .api-card {
            background: var(--bg-color);
            border: 1px solid var(--border);
            border-radius: 12px;
            overflow: hidden;
            transition: border-color 0.2s;
        }
        .api-card:hover { border-color: var(--primary); }
        .api-card-header {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 10px 14px;
            background: var(--surface-hover);
            border-bottom: 1px solid var(--border);
            font-size: 13px;
            flex-wrap: wrap;
        }
        .api-method {
            font-size: 11px;
            font-weight: 700;
            padding: 3px 7px;
            border-radius: 6px;
            text-transform: uppercase;
            letter-spacing: 0.04em;
        }
        .api-method.get { background: rgba(16, 185, 129, 0.15); color: #10b981; border: 1px solid rgba(16, 185, 129, 0.3); }
        .api-method.post { background: rgba(0, 119, 255, 0.15); color: var(--primary); border: 1px solid rgba(0, 119, 255, 0.3); }
        .api-method.put { background: rgba(245, 158, 11, 0.15); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.3); }
        .api-method.delete { background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3); }
        .api-path { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-weight: 600; color: var(--text-main); }
        .api-desc { color: var(--text-muted); font-size: 12px; margin-left: auto; }
        .api-card-body { padding: 12px 14px; }
        .api-code-label { font-size: 11px; font-weight: 600; color: var(--text-muted); margin-bottom: 4px; }
        .api-code-block {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 10px;
            font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
            font-size: 12px;
            line-height: 1.5;
            color: var(--text-main);
            overflow-x: auto;
            margin: 0;
        }

        .api-tabs-nav {
            display: flex;
            gap: 6px;
            margin-bottom: 20px;
            overflow-x: auto;
            padding-bottom: 6px;
            border-bottom: 1px solid var(--border);
            scrollbar-width: none;
        }
        .api-tabs-nav::-webkit-scrollbar { display: none; }
        .api-tab-btn {
            padding: 8px 14px;
            font-size: 13px;
            font-weight: 600;
            white-space: nowrap;
            border-radius: 10px;
            border: 1px solid transparent;
            cursor: pointer;
            background: transparent;
            color: var(--text-muted);
            transition: all 0.2s ease;
            display: inline-flex;
            align-items: center;
            gap: 6px;
        }
        .api-tab-btn:hover {
            color: var(--text-main);
            background: var(--surface-hover);
        }
        .api-tab-btn.active {
            background: var(--primary);
            color: #ffffff;
            box-shadow: 0 4px 12px rgba(0, 119, 255, 0.25);
        }
        .api-curl-copy-btn {
            padding: 3px 8px;
            font-size: 11px;
            height: 24px;
            border-radius: 6px;
            background: var(--surface-hover);
            color: var(--text-muted);
            border: 1px solid var(--border);
            cursor: pointer;
            transition: all 0.2s;
        }
        .api-curl-copy-btn:hover {
            color: var(--text-main);
            border-color: var(--primary);
        }

        main { max-width: 1300px; width: 100%; margin: 0 auto; padding: 32px 24px 80px; display: flex; flex-direction: column; gap: 32px; }

        .dashboard { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 20px; }
        .stat-card { padding: 24px; display: flex; flex-direction: column; gap: 12px; position: relative; overflow: hidden; }
        .stat-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: var(--primary); opacity: 0.8; }
        .stat-header { display: flex; align-items: center; justify-content: space-between; }
        .stat-icon { width: 40px; height: 40px; border-radius: 12px; background: var(--icon-bg); color: var(--primary); display: grid; place-items: center; }
        .stat-icon svg { width: 20px; height: 20px; fill: currentColor; stroke: currentColor; }
        .stat-label { font-size: 13px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em; }
        .stat-value { font-size: 28px; font-weight: 700; color: var(--text-main); line-height: 1.1; }
        #ram_val { white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
        #ram_detail { white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
        #traffic_ingress { white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
        .stat-sub { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
        .progress-bar { width: 100%; height: 6px; background: var(--surface-hover); border-radius: 3px; margin-top: 8px; overflow: hidden; }
        .progress-fill { height: 100%; background: var(--primary); border-radius: 3px; transition: width 0.5s ease-out; }

        .tabs-container { display: flex; justify-content: center; margin-bottom: 8px; }
        .tabs { display: inline-flex; background: var(--surface); border: 1px solid var(--border); padding: 4px; border-radius: 14px; gap: 4px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }
        .tab-btn { background: transparent; border: none; color: var(--text-muted); padding: 8px 20px; font-size: 14px; font-weight: 600; border-radius: 10px; cursor: pointer; display: flex; align-items: center; gap: 8px; outline: none; white-space: nowrap; }
        .tab-btn:hover { color: var(--text-main); }
        .tab-btn.active { background: var(--primary); color: #ffffff; box-shadow: 0 4px 12px rgba(0, 119, 255, 0.2); }

        .section-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
        .clients-toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
        .clients-tb-links-btn { color: var(--primary); border: 1px solid rgba(0, 119, 255, 0.4); white-space: nowrap; flex-shrink: 0; order: 1; }
        .panel-search-wrapper { flex: 1 1 240px; max-width: 440px; min-width: 180px; position: relative; order: 2; }
        .panel-search-input { width: 100%; height: 38px; padding: 0 32px 0 34px; border-radius: 9px; background: var(--surface); border: 1px solid var(--border); color: var(--text-main); font-size: 13px; outline: none; transition: border-color 0.2s, background 0.2s, box-shadow 0.2s; }
        .panel-search-input::placeholder { color: var(--text-muted); opacity: 0.8; }
        .panel-search-input:focus { border-color: var(--primary); background: var(--surface-hover); box-shadow: 0 0 0 2px rgba(0, 119, 255, 0.15); }
        .clients-tb-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; order: 3; }
        .group-dlg-actions { display: flex; flex-direction: row; align-items: center; gap: 8px; flex-wrap: nowrap; margin-bottom: 12px; }
        .group-act-btn { height: 36px; font-size: 13px; border-radius: 8px; padding: 0 14px; box-sizing: border-box; white-space: nowrap; display: inline-flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
        .group-admin-row { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; }
        #groupAdminLinkBtn {
            background: transparent !important;
            color: var(--primary) !important;
            border: 1.5px solid var(--primary) !important;
            box-shadow: none !important;
        }
        #groupAdminLinkBtn:hover {
            background: var(--icon-bg) !important;
        }
        .group-delete-btn {
            background: transparent !important;
            color: #ef4444 !important;
            border: 1.5px solid #ef4444 !important;
            box-shadow: none !important;
        }
        .group-delete-btn:hover {
            background: rgba(239, 68, 68, 0.1) !important;
        }
        .group-regen-btn { width: 36px; min-width: 36px; height: 36px; padding: 0 !important; display: inline-flex; align-items: center; justify-content: center; border-radius: 8px; box-sizing: border-box; }
        .group-search-wrapper { position: relative; display: inline-flex; align-items: center; flex: 1 1 auto; min-width: 140px; }
        .group-search-input { width: 100%; height: 36px; padding: 0 28px 0 38px; border-radius: 8px; background: var(--surface); border: 1px solid var(--border); color: var(--text-main); font-size: 13px; outline: none; box-sizing: border-box; transition: all 0.2s; }
        .group-search-input::placeholder { color: var(--text-muted); opacity: 0.8; }
        .group-search-input:focus { border-color: var(--primary); background: var(--surface-hover); box-shadow: 0 0 0 2px rgba(0, 119, 255, 0.15); }
        .group-search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--text-muted); pointer-events: none; }
        .section-title { font-size: 22px; font-weight: 700; display: flex; align-items: center; gap: 10px; color: var(--text-main); }
        .section-title svg { stroke: var(--primary); }

        .table-wrapper { overflow-x: auto; border-radius: 16px; background: var(--surface); border: 1px solid var(--border); }
        table { width: 100%; border-collapse: collapse; min-width: 900px; }
        th { text-align: left; padding: 16px 20px; font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; border-bottom: 1px solid var(--border); background: var(--bg-color); transition: background 0.3s; }
        td { padding: 16px 20px; border-bottom: 1px solid var(--border); vertical-align: middle; transition: background 0.2s, border-color 0.3s; }
        tr:last-child td { border-bottom: none; }
        tr:hover td { background: var(--surface-hover); }

        .client-name { font-weight: 600; font-size: 15px; margin-bottom: 4px; display: flex; align-items: center; gap: 8px;}
        .client-pw { font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; font-size: 12px; color: var(--primary); background: var(--icon-bg); padding: 2px 6px; border-radius: 4px; }
        .client-hash { font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; font-size: 12px; color: var(--text-muted); max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 4px; }

        .traffic-flex { display: flex; flex-direction: column; gap: 4px; font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; font-size: 13px; }
        .t-up { color: var(--primary); } .t-down { color: var(--primary); }

        .badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 20px; font-size: 12px; font-weight: 600; }
        .badge::before { content: ''; display: block; width: 6px; height: 6px; border-radius: 50%; background: var(--primary); }
        .badge.active { background: var(--icon-bg); color: var(--primary); border: 1px solid var(--primary); }
        .badge.inactive { background: var(--surface-hover); color: var(--text-muted); border: 1px solid var(--border); }
        .badge.inactive::before { background: var(--text-muted); }

        .actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
        .empty-state { text-align: center; padding: 64px 20px; color: var(--text-muted); }

        .settings-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
        @media (max-width: 1024px) { .settings-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
        @media (max-width: 600px) { .settings-grid { grid-template-columns: minmax(0, 1fr); } }
        .setting-card { padding: 24px; }
        .dns-selection { display: grid; grid-template-columns: minmax(0, 1fr); gap: 8px; }
        .dns-selection.is-custom { grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr); }
        .dns-selection .dns-manual-input { display: none; }
        .dns-selection.is-custom .dns-manual-input { display: block; }
        .settings-actions-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
        .settings-action-btn {
            width: 100%; min-width: 0; padding: 10px 8px; border: 0; background: transparent;
            color: var(--text-main); font-size: 14px; font-weight: 600; cursor: pointer; border-radius: 8px;
        }
        .settings-action-btn:hover { color: var(--primary); background: transparent; }
        .settings-action-btn:active { transform: scale(0.97); }
        .settings-action-btn:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
        .settings-action-btn.reboot { color: var(--primary); }
        .settings-action-btn.reboot:hover { color: #005fcc; }
        .settings-action-btn.danger { color: #ef4444; }
        .settings-action-btn.danger:hover { color: #dc2626; }
        .support-card { padding: 24px; display: flex; flex-direction: column; gap: 18px; }
        .support-card-title { margin: 0; color: var(--text-main); font-size: 16px; font-weight: 600; }
        .proxy-info-btn {
            width: 24px; height: 24px; flex: 0 0 24px; display: inline-flex; align-items: center; justify-content: center;
            border: 1px solid var(--border); border-radius: 50%; background: var(--bg-color); color: var(--text-muted);
            font-size: 14px; font-weight: 700; line-height: 1; cursor: pointer; transition: color .2s, border-color .2s, background .2s;
        }
        .proxy-info-btn:hover { color: var(--primary); border-color: var(--primary); background: var(--icon-bg); }
        .proxy-info-btn:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
        .proxy-info-btn svg { width: 15px; height: 15px; display: block; }
        .proxy-info-list { margin: 0; padding-left: 20px; color: var(--text-muted); font-size: 14px; line-height: 1.6; }
        .proxy-info-list li + li { margin-top: 12px; }
        .proxy-info-list strong { color: var(--text-main); }
        .proxy-profiles { display: flex; flex-direction: column; gap: 10px; }
        .proxy-profile-card {
            display: flex; align-items: center; gap: 12px; padding: 14px 16px;
            background: var(--bg-color); border: 1px solid var(--border); border-radius: 14px;
            transition: border-color .2s, background .2s, box-shadow .2s;
        }
        .proxy-profile-card.is-active { border-color: #10b981; background: rgba(16, 185, 129, 0.06); }
        .proxy-profile-dot {
            width: 10px; height: 10px; flex: 0 0 10px; border-radius: 50%;
            background: var(--border); transition: background .2s;
        }
        .proxy-profile-card.is-active .proxy-profile-dot { background: #10b981; box-shadow: 0 0 8px rgba(16, 185, 129, 0.4); }
        .proxy-profile-body { flex: 1; min-width: 0; }
        .proxy-profile-name { font-size: 14px; font-weight: 600; color: var(--text-main); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .proxy-profile-meta { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
        .proxy-profile-actions { display: flex; gap: 4px; flex-shrink: 0; }
        .proxy-profile-actions button {
            width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center;
            border: 1px solid var(--border); border-radius: 8px; background: transparent;
            color: var(--text-muted); font-size: 15px; cursor: pointer; transition: color .2s, border-color .2s, background .2s;
        }
        .proxy-profile-actions button:hover { color: var(--primary); border-color: var(--primary); background: var(--icon-bg); }
        .proxy-profile-actions button.danger:hover { color: #ef4444; border-color: #ef4444; }
        .proxy-profile-actions button svg { width: 15px; height: 15px; display: block; }
        .proxy-empty { text-align: center; padding: 32px 16px; color: var(--text-muted); font-size: 14px; }
        .proxy-add-btn {
            display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px;
            border: 1px dashed var(--border); border-radius: 8px; background: transparent;
            color: var(--text-muted); font-size: 18px; font-weight: 400; cursor: pointer;
            transition: color .2s, border-color .2s, background .2s;
        }
        .proxy-add-btn:hover { color: var(--primary); border-color: var(--primary); background: var(--icon-bg); }
        .proxy-add-btn svg { width: 16px; height: 16px; display: block; }
        input { background: var(--bg-color); color: var(--text-main); border: 1px solid var(--border); border-radius: 12px; outline: none; }
        .input-group { margin-bottom: 16px; }
        .input-group label { display: block; font-size: 13px; font-weight: 600; color: var(--text-muted); margin-bottom: 8px; text-transform: uppercase; }
        /* Custom Modern Scrollbar */
        ::-webkit-scrollbar {
            width: 6px;
            height: 6px;
        }
        ::-webkit-scrollbar-track {
            background: transparent;
        }
        ::-webkit-scrollbar-thumb {
            background: rgba(100, 116, 139, 0.3);
            border-radius: 999px;
        }
        ::-webkit-scrollbar-thumb:hover {
            background: rgba(100, 116, 139, 0.55);
        }
        * {
            scrollbar-width: thin;
            scrollbar-color: rgba(100, 116, 139, 0.3) transparent;
        }

        .input-group input:not([type="checkbox"]), .input-group select { width: 100%; height: 48px; background: var(--bg-color); color: var(--text-main); border: 1px solid var(--border); border-radius: 12px; padding: 0 16px; font-size: 14px; outline: none; transition: background 0.3s, border-color 0.3s, color 0.3s; }
        .input-group input:not([type="checkbox"]):focus, .input-group select:focus { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(0, 119, 255, 0.15); }
        .input-group input.is-invalid { border-color: #ef4444 !important; box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.15) !important; }

        .toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 16px; padding: 12px; background: var(--bg-color); border-radius: 12px; border: 1px solid var(--border); transition: background 0.3s, border-color 0.3s; }
        .toggle-row label { font-size: 14px; font-weight: 600; color: var(--text-main); margin: 0; text-transform: none; white-space: nowrap; }
        .vk-hashes-toggle { margin-top: 10px; }
        .switch { position: relative; display: inline-block; width: 44px; height: 24px; }
        .switch input { opacity: 0; width: 0; height: 0; }
        .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: var(--border); transition: .3s; border-radius: 24px; }
        .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }
        input:checked + .slider { background-color: var(--primary); }
        input:checked + .slider:before { transform: translateX(20px); }

        dialog { margin: auto; width: min(500px, calc(100% - 32px)); max-height: min(90vh, 760px); overflow-y: auto; background: var(--surface); color: var(--text-main); border: 1px solid var(--border); border-radius: 24px; padding: 32px; box-shadow: 0 25px 50px -12px rgba(0,0,0,0.25); }
        dialog::backdrop { background: rgba(0,0,0,0.4); backdrop-filter: blur(4px); }
        dialog h2 { margin-bottom: 24px; font-size: 24px; }
        .dlg-row { display: flex; gap: 16px; }
        .dlg-row > div { flex: 1; }
        .dlg-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 12px; margin-top: 32px; }

        .client-cards-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 16px;
            margin-top: 16px;
        }
        .client-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px;
            cursor: pointer;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
            display: flex;
            flex-direction: column;
            gap: 10px;
            position: relative;
        }
        .client-card:hover {
            background: var(--surface-hover);
            transform: translateY(-2px);
            box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
        }
        .client-card.group-card {
            border: 1.5px solid var(--primary, #0077ff);
        }
        .client-card.group-card:hover {
            border-color: var(--primary-hover, #0060cc);
            box-shadow: 0 10px 20px -3px rgba(0, 119, 255, 0.2);
        }
        .group-card-tag {
            display: inline-flex;
            align-items: center;
            font-size: 11px;
            font-weight: 600;
            padding: 2px 8px;
            border-radius: 6px;
            background: var(--icon-bg);
            color: var(--primary);
        }
        .rule-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        .rule-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .rule-card-title {
            font-size: 15px;
            font-weight: 700;
            color: var(--text-main);
        }
        .rule-chip {
            display: inline-flex;
            align-items: center;
            font-size: 11px;
            padding: 3px 8px;
            border-radius: 6px;
            background: var(--surface-hover);
            color: var(--text-muted);
            border: 1px solid var(--border);
        }
        #qrCodeContainer {
            background: #ffffff;
            padding: 16px;
            border-radius: 16px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 16px rgba(0,0,0,0.12);
            border: 1px solid var(--border);
            width: 260px;
            height: 260px;
            max-width: 100%;
            aspect-ratio: 1;
            box-sizing: border-box;
            overflow: hidden;
            margin: 0 auto;
        }
        #qrCodeContainer svg {
            width: 100% !important;
            height: 100% !important;
            max-width: 100% !important;
            max-height: 100% !important;
            display: block !important;
        }

        /* Hub Links & Bulk Settings Panel */
        .hub-bulk-panel {
            background: var(--bg-color);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 16px;
            margin-bottom: 20px;
        }
        .hub-field-block {
            display: flex;
            flex-direction: column;
            margin-bottom: 12px;
        }
        .hub-field-block:last-child {
            margin-bottom: 0;
        }
        .hub-field-label {
            display: block;
            font-size: 11px;
            font-weight: 700;
            color: var(--text-muted);
            margin-bottom: 6px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .hub-field-row {
            display: flex;
            gap: 8px;
            align-items: center;
        }
        .hub-field-input {
            flex: 1;
            min-width: 0;
            min-height: 38px;
            height: 38px;
            font-size: 13px;
            padding: 0 12px;
            border-radius: 8px;
            border: 1px solid var(--border);
            background: var(--surface);
            color: var(--text-main);
            box-sizing: border-box;
        }
        .hub-field-actions {
            display: flex;
            gap: 8px;
            align-items: center;
            flex-shrink: 0;
        }
        .hub-btn-apply {
            height: 38px;
            font-size: 13px;
            font-weight: 600;
            min-width: 175px;
            padding: 0 14px;
            white-space: nowrap;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
        }
        .hub-btn-clear {
            height: 38px;
            font-size: 13px;
            font-weight: 600;
            min-width: 135px;
            padding: 0 12px;
            white-space: nowrap;
            color: #ef4444 !important;
            border: 1px solid rgba(239, 68, 68, 0.35) !important;
            background: transparent;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
            transition: all 0.15s ease;
        }
        .hub-btn-clear:hover {
            background: rgba(239, 68, 68, 0.1) !important;
            border-color: #ef4444 !important;
            color: #dc2626 !important;
        }
        .hub-bulk-footer {
            display: flex;
            gap: 8px;
            flex-wrap: wrap;
            align-items: center;
            justify-content: space-between;
            border-top: 1px solid var(--border);
            padding-top: 12px;
            margin-top: 14px;
        }
        input.hub-search-input, .hub-search-input {
            width: 100%;
            height: 36px;
            padding: 0 32px 0 44px !important;
            border-radius: 8px;
            background: var(--surface);
            border: 1px solid var(--border);
            color: var(--text-main);
            font-size: 13px;
            outline: none;
            box-sizing: border-box;
            transition: all 0.2s;
        }
        .hub-search-input::placeholder {
            color: var(--text-muted);
            opacity: 0.8;
        }
        .hub-search-input:focus {
            border-color: var(--primary);
            background: var(--surface-hover);
            box-shadow: 0 0 0 2px rgba(0, 119, 255, 0.15);
        }

        @media (max-width: 640px) {
            .hub-field-row {
                flex-direction: column;
                align-items: stretch;
                gap: 8px;
            }
            .hub-field-row .hub-field-input {
                width: 100% !important;
                height: 38px !important;
                min-height: 38px !important;
                flex: none !important;
                box-sizing: border-box !important;
            }
            .hub-field-actions {
                width: 100%;
                display: flex;
                gap: 8px;
            }
            .hub-btn-apply,
            .hub-btn-clear {
                flex: 1;
                width: auto;
                min-width: 0;
                font-size: 12px;
                padding: 0 8px;
                height: 38px;
                box-sizing: border-box;
            }
            .hub-field-row.hub-workers-row {
                flex-direction: row;
                align-items: center;
                gap: 8px;
            }
            .hub-field-row.hub-workers-row .hub-workers-input {
                width: 75px !important;
                flex: 0 0 75px !important;
                height: 38px !important;
                min-height: 38px !important;
                text-align: center;
                box-sizing: border-box !important;
            }
            .hub-field-row.hub-workers-row .hub-field-actions {
                flex: 1;
                min-width: 0;
            }
            .hub-bulk-footer {
                flex-direction: column;
                align-items: stretch;
                gap: 10px;
            }
            .hub-search-container {
                width: 100% !important;
            }
            .hub-search-input {
                height: 38px;
                font-size: 13px;
            }
        }

        .client-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--border);
            padding-bottom: 10px;
            margin-bottom: 2px;
            min-height: 40px;
        }
        .client-card-title {
            font-size: 15px;
            font-weight: 700;
            color: var(--text-main);
            display: flex;
            align-items: center;
            gap: 8px;
            min-width: 0;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .rule-targets-container {
            max-height: 200px;
            overflow-y: auto;
            border: 1px solid var(--border);
            border-radius: 12px;
            background: var(--bg-color);
            padding: 8px;
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        .rule-target-section-title {
            font-size: 11px;
            font-weight: 700;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.05em;
            padding: 4px 6px 2px;
        }
        .rule-target-item {
            display: flex !important;
            align-items: center !important;
            gap: 10px !important;
            padding: 8px 12px !important;
            border-radius: 10px !important;
            background: var(--surface) !important;
            border: 1px solid var(--border) !important;
            cursor: pointer !important;
            transition: background 0.15s, border-color 0.15s !important;
            user-select: none;
            width: 100%;
            box-sizing: border-box;
        }
        .rule-target-item:hover {
            background: var(--surface-hover) !important;
            border-color: rgba(0, 119, 255, 0.4) !important;
        }
        .rule-target-item input[type="checkbox"] {
            width: 18px !important;
            min-width: 18px !important;
            max-width: 18px !important;
            height: 18px !important;
            min-height: 18px !important;
            max-height: 18px !important;
            margin: 0 !important;
            padding: 0 !important;
            accent-color: var(--primary);
            cursor: pointer;
            flex-shrink: 0;
            border-radius: 4px;
        }
        .rule-target-info {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 8px;
            flex: 1;
            min-width: 0;
        }
        .rule-target-name {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-main);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .rule-target-meta {
            font-size: 11px;
            color: var(--text-muted);
            font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;
            white-space: nowrap;
            flex-shrink: 0;
        }
        .rule-target-badge {
            display: inline-flex;
            align-items: center;
            font-size: 10px;
            font-weight: 600;
            padding: 2px 6px;
            border-radius: 4px;
            background: var(--icon-bg);
            color: var(--primary);
            flex-shrink: 0;
        }
        .client-card-body {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        .client-card-row {
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 13px;
        }
        .client-card-label {
            color: var(--text-muted);
            font-weight: 600;
        }
        .client-card-value {
            color: var(--text-main);
            font-weight: 500;
        }
        .client-sub-btn {
            width: 100%;
            margin-top: 8px;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
            font-size: 12px;
            padding: 7px 10px;
            border: 1px solid var(--border) !important;
            color: var(--text-main) !important;
            background: transparent !important;
            border-radius: 8px;
            font-weight: 500;
            transition: all 0.15s ease;
            cursor: pointer;
            box-sizing: border-box;
        }
        .client-sub-btn:hover {
            background: var(--surface-hover) !important;
            border-color: var(--primary) !important;
            color: var(--primary) !important;
        }
        [data-theme="dark"] .client-sub-btn {
            border-color: rgba(255, 255, 255, 0.25) !important;
            color: #ffffff !important;
        }
        [data-theme="dark"] .client-sub-btn:hover {
            border-color: var(--primary) !important;
            color: var(--primary) !important;
            background: rgba(255, 255, 255, 0.05) !important;
        }

        .dlg-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
        }
        .dlg-header h2 {
            margin: 0;
            font-size: 22px;
        }
        .close-btn {
            background: transparent;
            border: none;
            color: var(--text-muted);
            cursor: pointer;
            padding: 4px;
            border-radius: 50%;
            display: grid;
            place-items: center;
            outline: none;
        }
        .close-btn:hover {
            background: var(--surface-hover);
            color: var(--text-main);
        }

        @keyframes spin { 100% { transform: rotate(360deg); } }
        @keyframes shrink { from { width: 100%; } to { width: 0%; } }

        .toast-container { position: fixed; bottom: 24px; right: 24px; z-index: 100000; display: flex; flex-direction: column; gap: 10px; pointer-events: none; }
        .toast {
            position: relative;
            background: var(--surface);
            border: 1px solid var(--border);
            color: var(--text-main);
            padding: 14px 20px;
            border-radius: 12px;
            font-size: 14px;
            font-weight: 500;
            display: flex;
            align-items: center;
            gap: 10px;
            box-shadow: 0 10px 25px rgba(0,0,0,0.1);
            animation: toastIn 0.3s forwards;
            overflow: hidden;
            transition: all 0.3s;
            pointer-events: auto;
        }
        .toast.hide { animation: toastOut 0.3s forwards; }
        @keyframes toastIn { from { opacity: 0; transform: translateX(50px); } to { opacity: 1; transform: translateX(0); } }
        @keyframes toastOut { from { opacity: 1; transform: translateX(0); } to { opacity: 0; transform: translateX(50px); } }
        .toast-icon { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; background: var(--icon-bg); color: var(--primary); }

        .toast-success { border-left: 3px solid #10b981; }
        .toast-error { border-left: 3px solid #ef4444; }

        .toast-progress {
            position: absolute; bottom: 0; left: 0; height: 3px; width: 100%;
            background: rgba(0,0,0,0.3);
            animation: shrink 1.5s linear forwards;
        }

        .show-mobile { display: none !important; }
        @media(max-width: 480px) {
            .hide-mobile { display: none !important; }
            .show-mobile { display: inline-block !important; }

            .header-container { padding: 0 10px !important; }
            .brand-logo { width: 92px !important; }
            .header-actions { gap: 6px !important; }
            .version-logo { width: 82px !important; }
            .header-actions .btn:not(.btn-icon) { padding: 6px 8px !important; font-size: 12px !important; white-space: nowrap !important; }
            .header-actions .btn-icon { padding: 6px !important; }

            .routing-panel { padding: 16px !important; }
            .routing-header { gap: 8px !important; margin-bottom: 16px !important; }
        }

        @media(max-width: 768px) {
            header { height: 64px; }
            .header-container { padding: 0 16px; }
            .brand-logo { width: 128px; }
            .version-logo { width: 132px; }
            main { padding: 20px 12px 80px; gap: 20px; }

            .tabs-container { width: 100%; margin-bottom: 4px; }
            .tabs { display: flex; width: 100%; }
            .tab-btn { flex: 1; justify-content: center; padding: 10px 0; font-size: 13px; }

            .dashboard { grid-template-columns: repeat(2, 1fr); gap: 12px; }
            .dashboard .stat-card {
                padding: 12px 14px;
                min-height: 106px;
                display: flex;
                flex-direction: column;
                justify-content: space-between;
                gap: 8px;
            }
            .dashboard .cpu-card,
            .dashboard .memory-card {
                grid-column: auto;
                min-width: 0;
                min-height: 106px;
            }
            .dashboard .traffic-card {
                grid-column: span 2;
                min-height: auto;
                flex-direction: row;
                align-items: center;
                justify-content: space-between;
                padding: 12px 16px;
            }
            .dashboard .traffic-card .stat-header {
                display: flex;
                align-items: center;
                gap: 8px;
            }
            .dashboard .traffic-card div[style*="display: flex"] {
                flex-direction: row !important;
                gap: 16px !important;
            }
            .dashboard .traffic-card .stat-value {
                font-size: 15px !important;
            }
            .dashboard .cpu-card .stat-value,
            .dashboard .memory-card .stat-value {
                font-size: 18px;
                letter-spacing: -0.04em;
            }

            .stat-icon { width: 32px; height: 32px; border-radius: 8px; }
            .stat-icon svg { width: 16px; height: 16px; }
            .stat-label { font-size: 11px; }
            .stat-value { font-size: 18px; }
            .stat-sub { font-size: 11px; }
            .progress-bar { margin-top: 4px; }

            .section-header { flex-direction: column; align-items: flex-start; gap: 12px; }
            .section-header .btn { width: 100%; }
            .clients-toolbar { display: flex !important; flex-direction: column !important; align-items: stretch !important; gap: 8px !important; margin-bottom: 16px !important; justify-content: flex-start !important; }
            .clients-tb-links-btn { order: 1 !important; width: 100% !important; }
            .clients-tb-actions { order: 2 !important; width: 100% !important; display: flex !important; flex-direction: column !important; gap: 8px !important; }
            .clients-tb-actions .btn { width: 100% !important; }
            .panel-search-wrapper { order: 3 !important; width: 100% !important; max-width: 100% !important; margin-top: 4px !important; flex: none !important; }
            .group-dlg-actions { display: flex !important; flex-direction: column !important; align-items: stretch !important; gap: 8px !important; margin-bottom: 12px !important; }
            .group-dlg-actions .group-act-btn, .group-dlg-actions .btn { width: 100% !important; height: 38px !important; justify-content: center !important; }
            .group-admin-row { width: 100% !important; display: flex !important; gap: 6px !important; align-items: center !important; }
            .group-admin-row #groupAdminLinkBtn { flex: 1 1 0 !important; width: auto !important; height: 38px !important; }
            .group-admin-row .group-regen-btn { width: 38px !important; min-width: 38px !important; height: 38px !important; flex-shrink: 0 !important; }
            .group-search-wrapper { width: 100% !important; min-width: 0 !important; margin-top: 4px !important; flex: none !important; }
            .group-search-input { width: 100% !important; height: 38px !important; padding: 0 28px 0 38px !important; }
            .group-search-input:focus { width: 100% !important; }
            .dlg-row { flex-direction: column; gap: 0; }
            dialog { padding: 20px 16px; width: calc(100% - 24px); }
            .dlg-actions { flex-direction: row; flex-wrap: wrap; gap: 8px !important; }
            .dlg-actions .btn { flex: 1 1 0; width: auto; font-size: 13px !important; padding: 12px 4px !important; font-weight: 600; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .toggle-row { gap: 8px; padding: 10px; }
            .toggle-row > label:first-child { font-size: 12px; flex-shrink: 0; }
            #edit_copyLinkBtn { width: 34px; min-width: 34px; height: 34px; padding: 0 !important; gap: 0 !important; font-size: 0 !important; }
            .vk-hashes-toggle > div > label:first-child { font-size: 12px; flex-shrink: 0; }
            .switch { width: 56px; height: 32px; }
            .slider:before { height: 26px; width: 26px; left: 3px; bottom: 3px; }
            input:checked + .slider:before { transform: translateX(24px); }
            .setting-card { padding: 16px; }

            .table-wrapper { background: transparent; border: none; }
            table, thead, tbody, th, td, tr { display: block; width: 100%; }
            thead { display: none; }
            tbody { display: flex; flex-direction: column; gap: 12px; }

            tr {
                background: var(--surface);
                border: 1px solid var(--border);
                border-radius: 16px;
                padding: 16px;
                margin-bottom: 0;
                box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
                display: flex;
                flex-direction: column;
                gap: 10px;
            }
            tr:hover td { background: transparent; }

            td {
                border: none;
                padding: 0;
                min-height: auto;
                display: flex;
                justify-content: space-between;
                align-items: center;
                text-align: left;
                width: 100%;
            }

            td::before {
                content: attr(data-label) ":";
                font-size: 11px;
                font-weight: 600;
                color: var(--text-muted);
                text-transform: uppercase;
                letter-spacing: 0.05em;
                display: inline-block;
            }

            td[data-label="Пользователь"] {
                flex-direction: column;
                align-items: flex-start;
                gap: 4px;
                border-bottom: 1px solid var(--border);
                padding-bottom: 8px;
                margin-bottom: 2px;
            }
            td[data-label="Пользователь"]::before {
                display: none;
            }
            td[data-label="Пользователь"] .client-name {
                font-size: 15px;
                font-weight: 700;
                width: 100%;
                justify-content: space-between;
            }
            td[data-label="Пользователь"] .client-hash {
                font-size: 11px;
                width: 100%;
                margin-top: 2px;
            }

            td[data-label="Действия"] {
                border-top: 1px solid var(--border);
                padding-top: 8px;
                margin-top: 2px;
                justify-content: flex-end;
            }
            td[data-label="Действия"]::before {
                display: none;
            }
            .actions {
                width: 100%;
                justify-content: flex-end;
                gap: 6px;
                margin-top: 0;
            }
        }
        @media(max-width: 480px) {
            dialog { padding: 20px 12px !important; width: calc(100% - 16px) !important; }
        }
        .log-line { margin-bottom: 4px; padding: 2px 4px; border-radius: 4px; color: #e2e8f0; }
        .log-line:hover { background: rgba(255, 255, 255, 0.05); }
        #logConsole ::selection { background: rgba(0, 119, 255, 0.3); color: #ffffff; }
        .log-time { color: var(--text-muted); margin-right: 8px; }
        .log-lvl { font-weight: 700; margin-right: 8px; padding: 1px 6px; border-radius: 4px; font-size: 11px; }
        .log-lvl.log-info { color: #38bdf8; background: rgba(56, 189, 248, 0.1); }
        .log-lvl.log-err { color: #f87171; background: rgba(248, 113, 113, 0.1); }
        .log-mod { color: #34d399; background: rgba(52, 211, 153, 0.1); padding: 1px 6px; border-radius: 4px; font-size: 11px; font-weight: 600; margin-right: 8px; }

        #logConsole::-webkit-scrollbar {
            width: 8px;
            height: 8px;
        }
        #logConsole::-webkit-scrollbar-track {
            background: rgba(255, 255, 255, 0.03);
            border-radius: 10px;
        }
        #logConsole::-webkit-scrollbar-thumb {
            background: rgba(255, 255, 255, 0.12);
            border-radius: 10px;
        }
        #logConsole::-webkit-scrollbar-thumb:hover {
            background: rgba(255, 255, 255, 0.22);
        }

        select {
            background: var(--surface);
            color: var(--text-main);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 6px 12px;
            font-size: 13px;
            font-weight: 500;
            outline: none;
            cursor: pointer;
            transition: all 0.2s ease;
        }
        select:focus {
            border-color: var(--primary);
            box-shadow: 0 0 0 3px rgba(0, 119, 255, 0.15);
        }

        input[type=number]::-webkit-outer-spin-button,
        input[type=number]::-webkit-inner-spin-button {
            -webkit-appearance: none;
            margin: 0;
        }
        input[type=number] {
            -moz-appearance: textfield;
        }

        .dlg-row {
            display: flex;
            gap: 8px !important;
            width: 100%;
        }
        .dlg-row .input-group {
            flex: 1 1 0% !important;
            min-width: 0;
            margin-bottom: 0;
        }
        .dlg-row input {
            text-align: center;
            font-size: 13px !important;
            padding: 0 4px !important;
            font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;
        }
        .night-switch {
            position: relative;
            display: inline-flex;
            align-items: center;
            background: var(--surface-hover);
            border: 1px solid var(--border);
            border-radius: 8px;
            cursor: pointer;
            user-select: none;
            font-size: 13px;
            font-weight: 500;
            height: 32px;
            padding: 2px;
            z-index: 1;
            box-sizing: border-box;
            width: 110px;
        }
        .night-switch::before {
            content: "";
            position: absolute;
            top: 2px;
            bottom: 2px;
            left: 2px;
            width: calc(50% - 2px);
            border-radius: 6px;
            transition: all 0.3s cubic-bezier(0.4, 0.0, 0.2, 1);
            z-index: -1;
        }
        .night-switch.is-on::before {
            transform: translateX(100%);
            background: #0077ff;
        }
        .night-switch.is-off::before {
            transform: translateX(0);
            background: #0077ff;
        }
        .night-switch .ns-side {
            flex: 1;
            height: 100%;
            display: flex;
            align-items: center;
            justify-content: center;
            color: var(--text-muted);
            transition: color 0.3s;
        }
        .night-switch.is-on .ns-on { color: #ffffff; }
        .night-switch.is-off .ns-off { color: #ffffff; }
    </style>
</head>
<body>
    <header>
        <div class="header-container">
            <div class="brand" aria-label="CSQTT">
                <svg class="brand-logo" viewBox="0 0 760 184" role="img" aria-label="CSQTT">
                    <defs>
                        <linearGradient id="csqttLogoGradient" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="68" y2="340">
                            <stop offset="0" stop-color="#3DBFFD"></stop><stop offset="0.32" stop-color="#299BFD"></stop><stop offset="0.66" stop-color="#1475FD"></stop><stop offset="1" stop-color="#0450FC"></stop>
                        </linearGradient>
                    </defs>
                    <path fill="url(#csqttLogoGradient)" fill-rule="evenodd" d="M79,11 L66,15 L48,25 L40,32 L27,49 L21,62 L17,78 L17,98 L19,108 L22,117 L33,136 L40,144 L56,156 L66,161 L84,166 L108,166 L118,164 L127,161 L143,152 L157,139 L160,135 L160,133 L137,116 L135,116 L123,128 L113,133 L106,135 L100,135 L99,136 L87,135 L73,130 L67,126 L57,116 L51,105 L48,93 L48,83 L51,71 L58,59 L67,50 L83,42 L105,41 L115,44 L129,53 L136,61 L160,44 L160,42 L155,35 L144,25 L135,19 L124,14 L108,10 Z M219,13 L208,18 L201,23 L191,35 L186,49 L186,66 L188,73 L193,82 L200,89 L208,94 L219,98 L248,103 L264,108 L270,113 L272,117 L272,125 L267,132 L260,136 L255,137 L234,137 L220,133 L212,129 L199,119 L181,141 L199,155 L212,161 L222,164 L232,165 L233,166 L257,166 L276,161 L285,156 L292,150 L298,142 L302,132 L303,112 L297,97 L287,87 L276,81 L258,76 L234,72 L224,68 L218,63 L216,58 L217,50 L222,44 L228,41 L237,39 L248,39 L258,41 L268,45 L279,53 L281,53 L299,32 L285,21 L270,14 L259,11 L233,10 Z M354,25 L344,34 L333,49 L328,60 L324,74 L324,79 L323,80 L324,103 L329,119 L336,132 L349,147 L360,155 L372,161 L391,166 L414,166 L430,162 L442,156 L460,174 L478,156 L462,140 L469,131 L474,122 L479,109 L481,99 L481,77 L479,68 L474,54 L466,41 L453,27 L441,19 L430,14 L414,10 L385,11 L372,15 Z M389,42 L411,41 L427,47 L442,61 L449,76 L450,96 L447,106 L444,112 L439,118 L421,100 L404,116 L405,119 L418,132 L416,134 L399,136 L385,133 L377,129 L363,116 L355,99 L354,83 L357,71 L361,63 L373,50 L379,46 Z M496,13 L496,44 L536,44 L537,45 L537,163 L568,163 L568,45 L569,44 L609,44 L610,43 L610,13 Z M628,13 L627,14 L627,37 L628,38 L627,43 L628,44 L666,44 L667,45 L667,163 L698,163 L699,162 L699,45 L700,44 L738,44 L738,13 Z"></path>
                </svg>
            </div>
            <div class="header-actions">
                <button class="btn btn-api" onclick="openApiDocsDlg()" title="API Документация">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="16 18 22 12 16 6"></polyline>
                        <polyline points="8 6 2 12 8 18"></polyline>
                    </svg>
                    <span>API</span>
                </button>
                <button class="btn btn-ghost btn-icon" onclick="openImportExportDlg()" title="Импорт / Экспорт конфигурации">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"/>
                    </svg>
                </button>
                <button class="btn btn-ghost btn-icon" onclick="toggleTheme()" title="Сменить тему">
                    <svg id="themeIcon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
                </button>
                <svg class="version-logo" viewBox="0 0 516 64" role="img" aria-label="v2.5.0 by amurcanov" fill="none" stroke="url(#versionLogoGradient)" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">
                    <defs><linearGradient id="versionLogoGradient" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="516" y2="64"><stop offset="0" stop-color="#45B7FF"></stop><stop offset="0.45" stop-color="#2087FF"></stop><stop offset="1" stop-color="#075BFF"></stop></linearGradient></defs>
                    <path d="M8,24 L18,48 L28,24 M34,20 Q38,14 46,14 H50 Q58,14 58,22 Q58,28 52,33 L36,48 H58 M67,47 L67.8,47 M102,14 H80 V28 H92 Q102,28 102,38 Q102,48 90,48 Q80,48 78,42 M110,47 L110.8,47 M122,22 Q122,14 134,14 Q146,14 146,22 V40 Q146,48 134,48 Q122,48 122,40 Z M158,12 V48 M158,31 Q163,24 170,24 Q180,24 180,36 Q180,48 170,48 Q163,48 158,41 M190,24 L200,47 M210,24 L200,47 L196,57 Q194,60 189,58 M250,30 Q246,24 239,24 Q228,24 228,36 Q228,48 239,48 Q246,48 250,42 M250,24 V48 M260,48 V24 M260,31 Q264,24 270,24 Q277,24 277,32 V48 M277,31 Q281,24 287,24 Q294,24 294,32 V48 M304,24 V39 Q304,48 314,48 Q324,48 324,39 V24 M334,48 V24 M334,32 Q338,24 348,24 M382,29 Q378,24 370,24 Q360,24 360,36 Q360,48 370,48 Q378,48 382,43 M414,30 Q410,24 403,24 Q392,24 392,36 Q392,48 403,48 Q410,48 414,42 M414,24 V48 M424,48 V24 M424,32 Q429,24 436,24 Q446,24 446,35 V48 M467,24 Q456,24 456,36 Q456,48 467,48 Q478,48 478,36 Q478,24 467,24 Z M488,24 L498,48 L508,24"></path>
                </svg>
            </div>
        </div>
    </header>
    <div id="restartRequiredBanner" class="restart-required-banner">Для применения изменений необходимо перезагрузить CSQTT на сервере (перейдите в настройки)</div>

    <main>
        <div class="tabs-container">
            <div class="tabs">
                <button class="tab-btn active" data-tab="monitoring" onclick="switchTab('monitoring')">Мониторинг</button>
                <button class="tab-btn" data-tab="clients" onclick="switchTab('clients')">Клиенты</button>
                <button class="tab-btn" data-tab="logs" onclick="switchTab('logs')">Логи</button>
                <button class="tab-btn" data-tab="settings" onclick="switchTab('settings')">Настройки</button>
            </div>
        </div>

        <div id="monitoring-section" style="display: none;">
            <div class="section-header" style="justify-content: flex-end; margin-bottom: 15px; display: flex; align-items: center; gap: 10px; flex-direction: row !important;">
                <label for="updateIntervalSelect" style="color: var(--text-secondary); font-size: 14px;" title="С какой периодичностью панель будет обновлять данные мониторинга, потоков и трафика">Обновление:</label>
                <select id="updateIntervalSelect" class="input" style="width: auto; padding: 4px 10px; min-height: 32px;" onchange="changeUpdateInterval()">
                    <option value="1000">1 сек</option>
                    <option value="3000" selected>3 сек</option>
                    <option value="5000">5 сек</option>
                    <option value="10000">10 сек</option>
                    <option value="15000">15 сек</option>
                    <option value="30000">30 сек</option>
                    <option value="60000">60 сек</option>
                    <option value="90000">90 сек</option>
                    <option value="120000">120 сек</option>
                </select>
            </div>
            <section class="dashboard">
                <div class="glass-panel stat-card">
                    <div class="stat-header">
                        <div class="stat-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect><rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect><line x1="6" y1="6" x2="6.01" y2="6"></line><line x1="6" y1="18" x2="6.01" y2="18"></line></svg></div>
                        <div class="stat-label">Сервер</div>
                    </div>
                    <div>
                        <div class="stat-value" id="status_val">...</div>
                        <div class="stat-sub" id="status_uptime">Загрузка...</div>
                    </div>
                </div>

                <div class="glass-panel stat-card">
                    <div class="stat-header">
                        <div class="stat-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg></div>
                        <div class="stat-label">Сессии</div>
                    </div>
                    <div>
                        <div class="stat-value" id="active_val">0</div>
                        <div class="stat-sub">Активные сессии</div>
                    </div>
                </div>

                <div class="glass-panel stat-card cpu-card">
                    <div class="stat-header">
                        <div class="stat-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg></div>
                        <div class="stat-label">CPU</div>
                    </div>
                    <div>
                        <div class="stat-value" id="cpu_val">0% / 100%</div>
                        <div class="progress-bar"><div class="progress-fill" id="cpu_bar" style="width: 0%"></div></div>
                    </div>
                </div>

                <div class="glass-panel stat-card memory-card">
                    <div class="stat-header">
                        <div class="stat-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 9h20v6H2z"></path><path d="M5 15v2M8 15v2M11 15v2M14 15v2M17 15v2M20 15v2"></path><rect x="4" y="10" width="3" height="4" rx="0.5"></rect><rect x="9" y="10" width="3" height="4" rx="0.5"></rect><rect x="14" y="10" width="3" height="4" rx="0.5"></rect><rect x="19" y="10" width="3" height="4" rx="0.5"></rect></svg></div>
                        <div class="stat-label">ОЗУ</div>
                    </div>
                    <div>
                        <div class="stat-value" id="ram_val">0 MB</div>
                        <div class="stat-sub" id="ram_detail">Использование памяти</div>
                    </div>
                </div>

                <div class="glass-panel stat-card traffic-card">
                    <div class="stat-header">
                        <div class="stat-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="20" x2="12" y2="10"></line><line x1="18" y1="20" x2="18" y2="4"></line><line x1="6" y1="20" x2="6" y2="16"></line></svg></div>
                        <div class="stat-label">Трафик</div>
                    </div>
                    <div style="display: flex; flex-direction: column; gap: 4px;">
                        <div class="stat-value" style="font-size: 20px; color: var(--primary);" id="traffic_up">↑ 0 B</div>
                        <div class="stat-value" style="font-size: 20px; color: var(--primary);" id="traffic_down">↓ 0 B</div>
                        <div class="stat-sub" id="traffic_ingress">RX 0 · TX 0</div>
                    </div>
                </div>
            </section>
        </div>

        <div id="clients-section" style="display: none;">
            <section>
                <div class="clients-toolbar">
                    <button class="btn btn-ghost clients-tb-links-btn" onclick="openLinksAndTokensDlg()">Настройка ссылок</button>
                    <div class="panel-search-wrapper">
                        <input type="text" id="mainSearchInput" class="panel-search-input" placeholder="Поиск групп и пользователей..." oninput="handleMainSearch(this.value)">
                        <svg style="position: absolute; left: 10px; top: 11px; color: var(--text-muted); pointer-events: none;" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                        <button id="mainSearchClearBtn" onclick="clearMainSearch()" style="display: none; position: absolute; right: 8px; top: 10px; background: none; border: none; color: var(--text-muted); cursor: pointer; padding: 0;" title="Очистить">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                        </button>
                    </div>
                    <div class="clients-tb-actions">
                        <button class="btn btn-ghost" style="color: var(--primary); border: 1px solid rgba(0, 119, 255, 0.4); white-space: nowrap;" onclick="openRoutingRulesDlg()">Управление маршрутизацией</button>
                        <button class="btn btn-primary" onclick="openNewGroupDlg()">Создать группу</button>
                        <button class="btn btn-primary" onclick="openNewClientDlg()">Создать доступ</button>
                    </div>
                </div>
                <div id="clientsCards" class="client-cards-grid"></div>
            </section>
        </div>

        <div id="logs-section" style="display: none;">
            <section style="display: flex; flex-direction: column; gap: 16px;">
                <div style="display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: nowrap; width: 100%;">
                    <div style="display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; overflow: hidden;">
                        <span style="font-size: 12px; color: var(--text-muted); font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 1; min-width: 0;" id="logFilePath" title="Путь: /etc/csqtt/csqtt.log">Путь: /etc/csqtt/csqtt.log</span>
                        <a class="btn btn-primary" id="downloadLogBtn" href="/api/logs/download" style="padding: 4px 10px; font-size: 11px; text-decoration: none; border-radius: 8px; height: 28px; min-height: auto; display: inline-flex; align-items: center; flex-shrink: 0; white-space: nowrap;" download>Скачать</a>
                    </div>
                    <div style="display: flex; align-items: center; gap: 8px; user-select: none; white-space: nowrap; flex-shrink: 0;">
                        <label for="loggingActiveToggle" style="font-size: 13px; font-weight: 600; color: var(--text-muted);">Логирование</label>
                        <label class="switch">
                            <input type="checkbox" id="loggingActiveToggle" onchange="toggleLoggingActive()">
                            <span class="slider"></span>
                        </label>
                    </div>
                </div>
                <div class="glass-panel" style="padding: 16px; background: #0a0b0d; border: 1px solid var(--border); border-radius: 16px; position: relative; width: 100%;">
                    <button onclick="clearLogs()" title="Очистить логи" style="position: absolute; top: 12px; right: 12px; background: #1c1215; border: 1px solid rgba(239, 68, 68, 0.25); color: #ef4444; width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: all 0.2s; outline: none; z-index: 10;" onmouseover="this.style.background='#2a1519';this.style.borderColor='rgba(239,68,68,0.4)'" onmouseout="this.style.background='#1c1215';this.style.borderColor='rgba(239,68,68,0.25)'">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>
                    </button>
                    <div id="logConsole" style="height: 450px; overflow-y: auto; font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; font-size: 12px; line-height: 1.6; color: #e2e8f0; white-space: pre-wrap; word-break: break-all; scroll-behavior: smooth; padding-right: 32px;"></div>
                </div>
            </section>
        </div>

        <div id="settings-section" style="display: none;">
            <section>
                <div class="settings-actions-grid">
                    <button class="settings-action-btn reboot" onclick="document.getElementById('rebootDlg').showModal()">Перезагрузить</button>
                    <button class="settings-action-btn danger" onclick="logout()">Выйти</button>
                </div>
                <div class="settings-grid">
                    <div class="glass-panel setting-card" style="display: flex; flex-direction: column; justify-content: space-between;">
                        <div class="input-group" style="margin-bottom: 0;"><label for="mainpass">Главный пароль</label><input id="mainpass"></div>
                        <button id="saveMainPasswordBtn" class="btn btn-primary" style="width: 100%; margin-top: 22px;" onclick="saveMainPassword()" disabled>Сохранить главный пароль</button>
                    </div>
                    <div class="glass-panel setting-card" style="display: flex; flex-direction: column; justify-content: space-between;">
                        <div class="input-group" style="margin-bottom: 0;">
                            <label for="dns_provider">DNS Адреса</label>
                            <div id="dns_selection" class="dns-selection">
                            <select id="dns_provider">
                                <option value="custom">Ручной DNS</option>
                                <option value="yandex" selected>Yandex DNS</option>
                                <option value="cloudflare">Cloudflare DNS</option>
                                <option value="google">Google DNS</option>
                                <option value="adguard">AdGuard DNS</option>
                                <option value="xbox">Xbox DNS</option>
                                <option value="comms">Comms DNS</option>
                                <option value="geohide">Geohide DNS</option>
                                <option value="quad9">Quad9 DNS</option>
                                <option value="opendns">OpenDNS</option>
                                <option value="rostelecom">Rostelecom DNS</option>
                                <option value="nextdns">NextDNS</option>
                                <option value="bizone">BI.ZONE DNS</option>
                                <option value="nsdi">НСДИ DNS</option>
                            </select>
                            <input id="dns_manual" class="dns-manual-input" type="text" inputmode="decimal" autocomplete="off" spellcheck="false" placeholder="ipv4,ipv4" aria-label="Ручной DNS">
                            </div>
                        </div>
                        <button id="saveDnsBtn" class="btn btn-primary" style="width: 100%; margin-top: 22px;" onclick="saveDnsSettings()" disabled>Применить DNS</button>
                    </div>
                    <div class="glass-panel setting-card" style="display: flex; flex-direction: column; justify-content: space-between;">
                        <div class="input-group" style="margin-bottom: 0;">
                            <label for="auto_restart_interval">Интервал перезагрузок</label>
                            <select id="auto_restart_interval">
                                <option value="0" selected>Отключено (по умолчанию)</option>
                                <option value="12">12 часов</option>
                                <option value="24">24 часа</option>
                                <option value="48">48 часов</option>
                                <option value="72">72 часа</option>
                            </select>
                        </div>
                        <button id="saveAutoRestartIntervalBtn" class="btn btn-primary" style="width: 100%; margin-top: 22px;" onclick="saveAutoRestartInterval()" disabled>Сохранить интервал</button>
                    </div>
                </div>
            </section>

            <section style="margin-top: 20px;">
                <div class="glass-panel routing-panel local-proxy-panel" style="padding: 24px;">
                    <div class="routing-header" style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; gap: 12px;">
                        <div style="display: flex; align-items: center; gap: 8px; min-width: 0;">
                            <h3 style="margin: 0; font-size: 16px; font-weight: 600; white-space: nowrap;">
                                <span class="hide-mobile">Локальный прокси SOCKS5 UDP</span><span class="show-mobile">SOCKS5 UDP</span>
                            </h3>
                            <button class="proxy-info-btn" type="button" aria-label="Что такое локальный SOCKS5 UDP" title="Что такое локальный SOCKS5 UDP" onclick="document.getElementById('localProxyInfoDlg').showModal()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"></circle><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path><line x1="12" y1="17" x2="12.01" y2="17"></line></svg></button>
                        </div>
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <button class="proxy-add-btn" type="button" aria-label="Добавить профиль" title="Добавить профиль" onclick="openProfileDlg()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"></path></svg></button>
                        </div>
                    </div>
                    <div id="proxyProfilesList" class="proxy-profiles"></div>
                </div>
            </section>

            <section style="margin-top: 20px;">
                <div class="glass-panel support-card">
                    <h3 class="support-card-title">Поддержать разработчика Amurcanov</h3>
                    <a class="telegram-button" href="https://t.me/amurcanov_telegram" target="_blank" rel="noopener noreferrer" aria-label="Telegram: amurcanov_telegram" title="Telegram: amurcanov_telegram">
                        <svg class="tg-plane-icon" viewBox="0 0 24 24" width="22" height="22" fill="#ffffff" aria-hidden="true">
                            <path d="M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"/>
                        </svg>
                        <span>t.me/amurcanov_telegram</span>
                    </a>
                    <button class="crypto-button" type="button" onclick="openCryptoDonateDlg()" aria-label="Поддержать криптовалютой" title="CRYPTO">
                        <svg class="crypto-logo" viewBox="0 0 320 60" aria-hidden="true">
                            <g transform="translate(160, 30) scale(0.9) translate(-160, -30)">
                                <path fill="#FFFFFF" d="M35,30 C35,17 45,8 60,8 C70,8 78,13 82,21 L70,28 C68,23 64,20 60,20 C52,20 47,24 47,30 C47,36 52,40 60,40 C64,40 68,37 70,32 L82,39 C78,47 70,52 60,52 C45,52 35,43 35,30 Z M92,10 L115,10 C126,10 133,16 133,24 C133,30 129,35 122,37 L135,50 L120,50 L109,38 L104,38 L104,50 L92,50 L92,10 Z M104,20 L104,29 L114,29 C118,29 121,27 121,24 C121,21 118,20 114,20 L104,20 Z M148,10 L160,29 L172,10 L186,10 L167,36 L167,50 L153,50 L153,36 L134,10 L148,10 Z M196,10 L219,10 C231,10 238,17 238,26 C238,35 231,42 219,42 L208,42 L208,50 L196,50 L196,10 Z M208,20 L208,32 L218,32 C223,32 226,30 226,26 C226,22 223,20 218,20 L208,20 Z M243,10 L279,10 L279,21 L267,21 L267,50 L255,50 L255,21 L243,21 L243,10 Z M285,30 C285,17 296,8 310,8 C324,8 335,17 335,30 C335,43 324,52 310,52 C296,52 285,43 285,30 Z M297,30 C297,37 302,41 310,41 C318,41 323,37 323,30 C323,23 318,19 310,19 C302,19 297,23 297,30 Z"></path>
                            </g>
                        </svg>
                    </button>
                </div>
            </section>
        </div>
    </main>

    <dialog id="confirmDlg">
        <h2 id="confirmTitle" style="font-size: 18px; margin-bottom: 12px;">Подтверждение</h2>
        <p id="confirmMessage" style="font-size: 14px; color: var(--text-muted); margin-bottom: 24px; line-height: 1.5;"></p>
        <div class="dlg-actions" style="margin-top: 0;">
            <button class="btn btn-ghost" onclick="document.getElementById('confirmDlg').close()">Отмена</button>
            <button class="btn btn-primary" id="confirmActionBtn">Подтвердить</button>
        </div>
    </dialog>

    <dialog id="cryptoDonateDlg">
        <h2 style="font-size: 20px; margin-bottom: 8px;">Поддержать криптовалютой</h2>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 20px; line-height: 1.5;">Переведите криптовалюту на один из указанных адресов кошельков.</p>

        <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 14px; margin-bottom: 14px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                <span style="font-size: 13px; font-weight: 700; color: #31A1F5;">GRAM (Сеть TON)</span>
            </div>
            <div style="font-family: monospace; font-size: 13px; word-break: break-all; color: var(--text-main); line-height: 1.4; user-select: all;" id="gramWalletAddr">UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7</div>
            <div style="margin-top: 8px; text-align: right;">
                <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px;" onclick="copyText('UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7', this)">Скопировать</button>
            </div>
        </div>

        <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 14px; margin-bottom: 14px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                <span style="font-size: 13px; font-weight: 700; color: #50AF95;">USDT (Сеть TON)</span>
            </div>
            <div style="font-family: monospace; font-size: 13px; word-break: break-all; color: var(--text-main); line-height: 1.4; user-select: all;" id="usdtTonWalletAddr">UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7</div>
            <div style="margin-top: 8px; text-align: right;">
                <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px;" onclick="copyText('UQCsHSj_Bev5AG3vCz-84TQC7BSWjNdNdOjP9M2gWUEmbyD7', this)">Скопировать</button>
            </div>
        </div>

        <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 14px; margin-bottom: 20px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                <span style="font-size: 13px; font-weight: 700; color: #50AF95;">USDT (Сеть TRC20)</span>
            </div>
            <div style="font-family: monospace; font-size: 13px; word-break: break-all; color: var(--text-main); line-height: 1.4; user-select: all;" id="usdtTrcWalletAddr">TD1oiQiHmjqsRDPxfUjUbSWxEmcr4k7Lob</div>
            <div style="margin-top: 8px; text-align: right;">
                <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px;" onclick="copyText('TD1oiQiHmjqsRDPxfUjUbSWxEmcr4k7Lob', this)">Скопировать</button>
            </div>
        </div>

        <div class="dlg-actions" style="margin-top: 0;">
            <button class="btn btn-primary" onclick="document.getElementById('cryptoDonateDlg').close()">Хорошо</button>
        </div>
    </dialog>

    <dialog id="localProxyInfoDlg">
        <h2 style="font-size: 20px; margin-bottom: 20px;">SOCKS5 UDP</h2>
        <ul class="proxy-info-list">
            <li><strong>Зачем нужно?</strong> Маршрутизировать трафик из CSQTT в Xray, 3x-ui и другие панели. Это нужно, чтобы маршрутизировать трафик через geoip/geodat и работать с любыми возможностями ваших панелей.</li>
            <li><strong>Как работает?</strong> CSQTT передаёт TCP и UDP в локальный SOCKS5. Если прокси недоступен, используется прямой выход через основной VPS.</li>
            <li><strong>Как настроить?</strong> В соответствующем ядре или веб-панели заранее создайте инбаунд SOCKS5-прокси (в некоторых панелях он может называться mixed) с предпочитаемым портом и включите UDP в настройках прокси. Затем укажите в панели CSQTT тот же самый порт.</li>
        </ul>
        <div class="dlg-actions" style="margin-top: 24px;">
            <button class="btn btn-primary" onclick="document.getElementById('localProxyInfoDlg').close()">Понятно</button>
        </div>
    </dialog>

    <dialog id="vkHashesInfoDlg">
        <h2 style="font-size: 20px; margin-bottom: 16px;">VK хеши в ссылке</h2>
        <p style="color: var(--text-muted); font-size: 14px; line-height: 1.6; margin: 0;">Укажите от 1 до 6 VK-хешей через запятую без пробелов. Вставляйте только сами значения хешей. Они будут добавлены в ссылку CSQTT v2, и пользователю не придётся вводить их вручную.</p>
        <div class="dlg-actions" style="margin-top: 24px;">
            <button class="btn btn-primary" onclick="document.getElementById('vkHashesInfoDlg').close()">Понятно</button>
        </div>
    </dialog>

    <dialog id="vkTokenSettingsDlg">
        <h2 style="font-size: 20px; margin-bottom: 16px;">Настройка VK токенов</h2>
        <p style="color: var(--text-muted); font-size: 14px; line-height: 1.6; margin: 0 0 18px;">Если сохранить VK токен здесь, все CSQTT-ссылки с включённой функцией VK токена будут автоматически использовать этот общий токен.</p>
        <div class="input-group" style="margin-bottom: 0;">
            <label for="vkTokenSettingsInput">Бессрочный VK токен</label>
            <input id="vkTokenSettingsInput" type="password" maxlength="4096" autocomplete="off" spellcheck="false">
        </div>
        <div class="dlg-actions" style="margin-top: 20px;">
            <button class="btn btn-ghost" style="color: #ef4444;" onclick="clearVkTokenSettings()">Очистить</button>
            <button class="btn btn-primary" onclick="saveVkTokenSettings()">Сохранить</button>
        </div>
    </dialog>

    <dialog id="profileDlg">
        <h2 id="profileDlgTitle" style="font-size: 18px; margin-bottom: 18px;">Новый профиль</h2>
        <div class="input-group"><label for="profileDlgName">Имя</label><input id="profileDlgName" maxlength="64" placeholder="3x-ui VLESS"></div>
        <div class="input-group"><label for="profileDlgPort">Порт SOCKS5</label><input type="number" id="profileDlgPort" min="1" max="65535" value="45000" inputmode="numeric"></div>
        <div class="input-group"><label for="profileDlgUser">Логин (необязательно)</label><input id="profileDlgUser" maxlength="255" autocomplete="off"></div>
        <div class="input-group" style="margin-bottom: 0;"><label for="profileDlgPass">Пароль (необязательно)</label><input type="password" id="profileDlgPass" maxlength="255" autocomplete="new-password"></div>
        <div class="dlg-actions" style="margin-top: 20px;">
            <button class="btn btn-ghost" onclick="document.getElementById('profileDlg').close()">Отмена</button>
            <button class="btn btn-primary" id="profileDlgSave" onclick="saveProfileDlg()">Создать</button>
        </div>
    </dialog>

    <dialog id="newDlg">
        <h2>Создание доступа</h2>
        <div id="newDlgGroupNotice" style="display: none; font-size: 13px; color: var(--text-muted); margin-bottom: 12px;">
            Группа: <span id="newDlgGroupName" style="font-weight: 600; color: var(--text-muted);"></span>
        </div>
        <input type="hidden" id="newDlgGroupId" value="">
        <div class="input-group">
            <label for="cname">Имя клиента</label>
            <input id="cname" maxlength="128">
        </div>
        <div class="input-group">
            <label for="days">Срок действия (0 = Бессрочно, 1-365 Ручной лимит)</label>
            <input type="number" id="days" value="30" min="0" max="365">
        </div>
        <div class="input-group">
            <label for="new_workers">Число потоков (0 = Клиентские настройки, 3-100 Ручной лимит)</label>
            <input type="number" id="new_workers" min="0" max="100" value="0" placeholder="0 (Клиентские)">
        </div>

        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('newDlg').close()">Отмена</button>
            <button class="btn btn-primary" onclick="createClient()" id="createBtn">Создать</button>
        </div>
    </dialog>

    <dialog id="editDlg">
        <div class="dlg-header">
            <h2>Управление доступом</h2>
            <button class="close-btn" onclick="document.getElementById('editDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <div class="input-group">
            <label for="edit_name">Имя клиента</label>
            <input id="edit_name" maxlength="128">
        </div>

        <div class="input-group">
            <label for="edit_group">Группа</label>
            <select id="edit_group">
                <option value="">Без группы</option>
            </select>
        </div>

        <div class="input-group">
            <label for="edit_pass">Пароль (ключ)</label>
            <div style="display: flex; gap: 8px; align-items: center;">
                <input id="edit_pass" readonly style="background: var(--surface-hover); color: var(--text-muted); cursor: not-allowed; flex: 1; font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace; height: 48px;">
                <button class="btn btn-ghost" onclick="copyPasswordText()" style="width: 48px; height: 48px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0;" title="Копировать"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg></button>
                <button class="btn btn-ghost" onclick="openQrModalForActiveClient()" style="width: 48px; height: 48px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0;" title="QR-код"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg></button>
            </div>
        </div>

        <div class="input-group">
            <label for="edit_days">Срок действия (0 = Бессрочно, 1-365 Ручной лимит)</label>
            <input type="number" id="edit_days" min="0" max="365">
        </div>

        <div class="input-group">
            <label for="edit_workers">Число потоков (0 = Клиентские настройки, 3-100 Ручной лимит)</label>
            <input type="number" id="edit_workers" min="0" max="100" value="0" placeholder="0 (Клиентские)">
        </div>

        <!-- Info Section -->
        <div style="border: 1px solid var(--border); border-radius: 12px; padding: 12px; margin-top: 16px; font-size: 13px; display: flex; flex-direction: column; gap: 8px;">
            <div style="display: flex; justify-content: space-between;"><span style="color: var(--text-muted);">IP-адрес:</span><span id="edit_ip_info" style="font-weight: 500;">—</span></div>
            <div style="display: flex; justify-content: space-between;"><span style="color: var(--text-muted);">Трафик:</span><span id="edit_traffic_info" style="font-weight: 500; font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;">↑ 0 B / ↓ 0 B</span></div>
        </div>

        <div class="dlg-actions" style="margin-top: 16px;">
            <button class="btn btn-ghost" id="edit_deleteBtn" style="color: #ef4444;" onclick="executeDelete()"><span class="hide-mobile">Удалить</span><span class="show-mobile">Удал.</span></button>
            <button class="btn btn-ghost" id="edit_statusBtn" style="color: var(--primary);" onclick="executeToggle()">Выкл</button>
            <button class="btn btn-primary" onclick="saveClientChanges()" id="edit_saveBtn"><span class="hide-mobile">Сохранить</span><span class="show-mobile">Сохр.</span></button>
        </div>
    </dialog>

    <dialog id="newGroupDlg">
        <div class="dlg-header">
            <h2>Создание группы</h2>
            <button class="close-btn" onclick="document.getElementById('newGroupDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>
        <div class="input-group">
            <label for="groupNameInput">Имя группы</label>
            <input id="groupNameInput" maxlength="128" placeholder="Введите название группы">
        </div>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('newGroupDlg').close()">Отмена</button>
            <button class="btn btn-primary" onclick="createGroup()" id="createGroupBtn">Создать</button>
        </div>
    </dialog>

    <dialog id="groupDlg" style="max-width: 720px; width: min(720px, calc(100% - 24px)); max-height: 88vh; overflow-y: auto;">
        <div class="dlg-header" style="align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px;">
            <div style="display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1;">
                <h2 id="groupDlgTitle" style="font-size: 22px; margin: 0; line-height: 1.2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">Группа</h2>
                <input id="groupDlgTitleInput" style="display: none; font-size: 18px; font-weight: 700; height: 32px; padding: 0 8px; border-radius: 8px; width: 200px; max-width: 100%;" maxlength="128">
                <button id="groupDlgEditBtn" onclick="toggleGroupInlineEdit()" style="background: none; border: 1px solid var(--border); cursor: pointer; color: var(--text-muted); display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0; transition: all 0.2s;" title="Переименовать">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>
                </button>
            </div>
            <button class="close-btn" onclick="document.getElementById('groupDlg').close()" style="margin: 0; flex-shrink: 0;">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <div class="group-dlg-actions">
            <button class="btn btn-primary group-act-btn" onclick="createClientInCurrentGroup()">Создать пароль</button>
            <div class="group-admin-row">
                <button id="groupAdminLinkBtn" class="btn group-act-btn" onclick="createOrCopyGroupAdminLink()">Создать админа</button>
                <button id="groupAdminDeleteBtn" class="btn btn-ghost group-regen-btn" style="display: none; color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.4);" onclick="deleteGroupAdminLink()" title="Удалить ссылку админа"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6"/></svg></button>
            </div>
            <button id="groupDeleteBtn" class="btn group-act-btn group-delete-btn" onclick="deleteCurrentGroup()" title="Удалить группу">Удалить группу</button>
            <div class="group-search-wrapper">
                <input type="text" id="groupSearchInput" class="group-search-input" placeholder="Поиск в группе..." oninput="handleGroupSearch(this.value)">
                <svg class="group-search-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                <button id="groupSearchClearBtn" onclick="clearGroupSearch()" style="display: none; position: absolute; right: 10px; top: 50%; transform: translateY(-50%); background: none; border: none; color: var(--text-muted); cursor: pointer; padding: 4px;" title="Очистить">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                </button>
            </div>
        </div>

        <div id="groupClientsCards" class="client-cards-grid" style="margin-top: 12px;"></div>
    </dialog>

    <dialog id="routingRulesDlg" style="max-width: 680px; width: min(680px, calc(100% - 24px)); max-height: 85vh; overflow-y: auto;">
        <div class="dlg-header" style="align-items: center; justify-content: space-between; gap: 10px;">
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <h2 style="margin: 0;">Управление маршрутизацией</h2>
                <button class="btn btn-primary" style="font-size: 13px; padding: 4px 12px; height: 32px; white-space: nowrap;" onclick="openEditRoutingRuleDlg(null)">+ Создать правило</button>
            </div>
            <button class="close-btn" onclick="document.getElementById('routingRulesDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin: 8px 0 16px; line-height: 1.5;">
            Правила маршрутизации автоматически передаются клиенту Android при подключении (через TUNCONF). При назначении правила клиенту вкладка «Исключения» в приложении скрывается, а правила применяются сервером.
        </p>
        <div id="routingRulesList" style="display: flex; flex-direction: column; gap: 12px;"></div>
    </dialog>

    <dialog id="editRoutingRuleDlg" style="max-width: 620px; width: min(620px, calc(100% - 24px)); max-height: 85vh; overflow-y: auto;">
        <div class="dlg-header">
            <h2 id="editRoutingRuleTitle">Правило маршрутизации</h2>
            <button class="close-btn" onclick="document.getElementById('editRoutingRuleDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>
        <input type="hidden" id="ruleId">
        <div class="input-group">
            <label for="ruleName">Имя правила</label>
            <input id="ruleName" maxlength="128" placeholder="Например: Обход блокировок">
        </div>
        <div class="input-group">
            <label for="ruleMode">Режим исключения приложений</label>
            <select id="ruleMode">
                <option value="blacklist">Чёрный список</option>
                <option value="whitelist">Белый список</option>
            </select>
        </div>
        <div class="input-group">
            <label for="rulePackages">Список пакетов приложений</label>
            <textarea id="rulePackages" rows="3" style="width: 100%; background: var(--surface); color: var(--text-main); border: 1px solid var(--border); border-radius: 12px; padding: 10px; font-family: monospace; font-size: 12px;" placeholder="com.instagram.android&#10;org.telegram.messenger"></textarea>
        </div>
        <div class="input-group">
            <label for="ruleDomainsMode">Режим исключения доменов</label>
            <select id="ruleDomainsMode" onchange="onRuleDomainsModeChange()">
                <option value="blacklist">Чёрный список</option>
                <option value="whitelist">Белый список</option>
            </select>
        </div>
        <div class="input-group">
            <label for="ruleDomains">Список доменов</label>
            <textarea id="ruleDomains" rows="3" style="width: 100%; background: var(--surface); color: var(--text-main); border: 1px solid var(--border); border-radius: 12px; padding: 10px; font-family: monospace; font-size: 12px;" placeholder="gosuslugi.ru&#10;*.ru&#10;vk.com"></textarea>
        </div>
        <div class="toggle-row" id="ruleTemplateDomainsRow" style="margin-bottom: 16px;">
            <div style="display: flex; align-items: center; gap: 8px;">
                <label for="ruleTemplateDomains" style="cursor: pointer;">Готовый список RU доменов</label>
                <button type="button" class="btn-icon" style="padding: 2px; border: none; background: transparent; cursor: pointer; color: var(--primary); display: flex; align-items: center;" onclick="openTemplateDomainsDlg()" title="Посмотреть список RU доменов">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                </button>
            </div>
            <label class="switch">
                <input type="checkbox" id="ruleTemplateDomains">
                <span class="slider"></span>
            </label>
        </div>
        <div class="input-group">
            <label for="ruleIpMode">Режим исключения адресов</label>
            <select id="ruleIpMode">
                <option value="blacklist">Чёрный список</option>
                <option value="whitelist">Белый список</option>
            </select>
        </div>
        <div class="input-group">
            <label for="ruleIps">Список адресов</label>
            <textarea id="ruleIps" rows="3" style="width: 100%; background: var(--surface); color: var(--text-main); border: 1px solid var(--border); border-radius: 12px; padding: 10px; font-family: monospace; font-size: 12px;" placeholder="1.2.3.4/32&#10;1.2.3.4&#10;1.2.3.4-1.2.3.255"></textarea>
        </div>
        <div class="input-group">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; flex-wrap: wrap; gap: 6px;">
                <label style="margin: 0;">Применить правило к:</label>
                <div style="display: flex; gap: 6px;">
                    <button type="button" class="btn btn-ghost" style="font-size: 11px; height: 26px; padding: 0 8px; border: 1px solid var(--border);" onclick="selectAllRuleTargets(true)">Выбрать все</button>
                    <button type="button" class="btn btn-ghost" style="font-size: 11px; height: 26px; padding: 0 8px; border: 1px solid var(--border);" onclick="selectAllRuleTargets(false)">Снять все</button>
                </div>
            </div>
            <div id="ruleTargetsContainer" class="rule-targets-container" style="user-select: none; -webkit-user-select: none; touch-action: pan-y;"></div>
        </div>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('editRoutingRuleDlg').close()">Отмена</button>
            <button class="btn btn-ghost" id="ruleDeleteBtn" style="color: #ef4444; display: none;" onclick="deleteCurrentRoutingRule()">Удалить</button>
            <button class="btn btn-primary" onclick="saveRoutingRule()" id="ruleSaveBtn">Сохранить</button>
        </div>
    </dialog>

    <dialog id="templateDomainsDlg" style="max-width: 540px; width: min(540px, calc(100% - 24px)); max-height: 80vh; overflow-y: auto;">
        <div class="dlg-header">
            <h2>Список RU доменов</h2>
            <button class="close-btn" onclick="document.getElementById('templateDomainsDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>
        <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 10px; line-height: 1.5;">
            Эти <span id="templateDomainsCount">503</span> домена направляются напрямую без VPN при включении функции:
        </p>
        <div style="margin-bottom: 10px;">
            <input id="templateDomainsFilter" placeholder="Быстрый поиск по доменам..." style="width: 100%; height: 38px; font-size: 13px; padding: 0 12px;" oninput="filterTemplateDomains(this.value)">
        </div>
        <div id="templateDomainsList" style="max-height: 320px; overflow-y: auto; background: var(--bg-color); border: 1px solid var(--border); border-radius: 12px; padding: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; line-height: 1.6; color: var(--text-main);"></div>
        <div class="dlg-actions" style="margin-top: 16px;">
            <button class="btn btn-primary" onclick="document.getElementById('templateDomainsDlg').close()">Понятно</button>
        </div>
    </dialog>

    <dialog id="linksAndTokensDlg" style="max-width: 820px; width: min(820px, calc(100% - 24px)); max-height: 88vh; overflow-y: auto;">
        <div class="dlg-header">
            <h2>Настройка ссылок</h2>
            <button class="close-btn" onclick="document.getElementById('linksAndTokensDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <!-- Top bulk settings panel -->
        <div class="hub-bulk-panel">
            <div class="hub-field-block">
                <label class="hub-field-label">Общий VK токен</label>
                <div class="hub-field-row">
                    <input id="hubVkTokenInput" class="hub-field-input" placeholder="Введите VK токен..." onchange="autoSaveHubVkToken()" onblur="autoSaveHubVkToken()">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubVkTokenDirect()"><span class="hide-mobile">Применить VK токен</span><span class="show-mobile">Применить токен</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-token')"><span class="hide-mobile">Очистить токен</span><span class="show-mobile">Очистить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-field-block">
                <label class="hub-field-label">VK хеши (через запятую)</label>
                <div class="hub-field-row">
                    <input id="hubVkHashesInput" class="hub-field-input" placeholder="hash1,hash2,...,hash6" onchange="autoSaveHubVkHashes()" onblur="autoSaveHubVkHashes()">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubVkHashesDirect()"><span class="hide-mobile">Применить VK хеши</span><span class="show-mobile">Применить хеши</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-hashes')"><span class="hide-mobile">Очистить хеши</span><span class="show-mobile">Очистить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-field-block">
                <label class="hub-field-label">Установить число потоков (0 = Клиентские настройки, 3-100 Ручной лимит)</label>
                <div class="hub-field-row hub-workers-row">
                    <input type="number" id="hubWorkersInput" class="hub-field-input hub-workers-input" min="0" max="100" value="0" placeholder="0 (Клиентские)">
                    <div class="hub-field-actions">
                        <button class="btn btn-primary hub-btn-apply" onclick="applyHubWorkersDirect()"><span class="hide-mobile">Установить число потоков</span><span class="show-mobile">Установить</span></button>
                        <button class="btn btn-ghost hub-btn-clear" onclick="promptBatchAction('clear-workers')"><span class="hide-mobile">Сбросить число потоков</span><span class="show-mobile">Сбросить</span></button>
                    </div>
                </div>
            </div>

            <div class="hub-bulk-footer">
                <div style="display: flex; align-items: center; gap: 8px; font-size: 13px;">
                    <input type="checkbox" id="hubSelectAll" onchange="toggleSelectAllHubClients(this.checked)" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--primary);">
                    <label for="hubSelectAll" style="cursor: pointer; font-weight: 600;">Выбрать всех</label>
                    <span id="hubSelectedCount" style="color: var(--text-muted); font-size: 12px;">(0 выбрано)</span>
                </div>
                <div class="hub-search-container" style="position: relative; width: 280px; max-width: 100%;">
                    <input type="text" id="hubSearchInput" class="hub-search-input" placeholder="Клиент (группа)..." oninput="handleHubSearch(this.value)" style="padding-left: 44px !important; padding-right: 32px !important;">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--text-muted); pointer-events: none;"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                    <button id="hubSearchClearBtn" onclick="clearHubSearch()" style="display: none; position: absolute; right: 8px; top: 50%; transform: translateY(-50%); background: none; border: none; color: var(--text-muted); cursor: pointer; padding: 4px; align-items: center; justify-content: center;" title="Очистить">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                    </button>
                </div>
            </div>
        </div>

        <!-- Clients links list -->
        <div id="hubClientsList" style="display: flex; flex-direction: column; gap: 10px;"></div>
    </dialog>

    <dialog id="qrDlg" style="max-width: 440px; width: min(440px, calc(100% - 24px)); text-align: center;">
        <div class="dlg-header" style="justify-content: space-between;">
            <div style="display: flex; align-items: center; gap: 8px;">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg>
                <h2 id="qrDlgTitle" style="font-size: 18px; margin: 0;">QR-код клиента</h2>
            </div>
            <button class="close-btn" onclick="document.getElementById('qrDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>
        <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; margin: 16px 0;">
            <div id="qrCodeContainer"></div>
            <div style="width: 100%; display: flex; gap: 8px; align-items: center;">
                <input id="qrDlgLink" readonly style="flex: 1; height: 42px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; padding: 0 10px; background: var(--surface-hover); color: var(--text-muted); border-radius: 8px;" onclick="this.select()">
                <button class="btn btn-ghost" id="qrDlgCopyBtn" onclick="copyQrDlgLink(this)" style="height: 42px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" title="Копировать ссылку">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
            </div>
        </div>
        <div class="dlg-actions" style="justify-content: center;">
            <button class="btn btn-primary" style="width: 100%;" onclick="document.getElementById('qrDlg').close()">Закрыть</button>
        </div>
    </dialog>

    <!-- Confirmation Dialog for Batch Operations -->
    <dialog id="batchConfirmDlg" style="max-width: 440px; width: min(440px, calc(100% - 24px));">
        <h2 id="batchConfirmTitle" style="font-size: 20px; margin-bottom: 12px;">Подтверждение</h2>
        <p id="batchConfirmMessage" style="font-size: 14px; color: var(--text-muted); line-height: 1.5; margin-bottom: 24px;"></p>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('batchConfirmDlg').close()">Отмена</button>
            <button class="btn btn-primary" id="batchConfirmSubmitBtn">Применить</button>
        </div>
    </dialog>

    <dialog id="importExportDlg" style="max-width: 640px; width: min(640px, calc(100% - 24px)); max-height: 88vh; overflow-y: auto;">
        <div class="dlg-header">
            <div style="display: flex; align-items: center; gap: 10px;">
                <div style="width: 36px; height: 36px; border-radius: 10px; background: var(--icon-bg); color: var(--primary); display: grid; place-items: center;">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"/>
                    </svg>
                </div>
                <div>
                    <h2 style="font-size: 20px; font-weight: 700; margin: 0;">Импорт и Экспорт</h2>
                    <p style="font-size: 12px; color: var(--text-muted); margin: 0;">Резервная копия, перенос клиентов и настроек</p>
                </div>
            </div>
            <button class="close-btn" onclick="document.getElementById('importExportDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <div style="display: flex; gap: 8px; margin-bottom: 16px;">
            <button id="btnTabExport" class="btn btn-primary" style="flex: 1;" onclick="switchImportExportTab('export')">Экспорт</button>
            <button id="btnTabImport" class="btn btn-ghost" style="flex: 1; border: 1px solid var(--border);" onclick="switchImportExportTab('import')">Импорт</button>
        </div>

        <div style="display: flex; gap: 8px; margin-bottom: 16px; align-items: center;">
            <label style="flex: 1; display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; font-weight: 500;">
                <input type="checkbox" id="ieOptClients" checked style="width: 18px; height: 18px; cursor: pointer; accent-color: var(--primary);">
                <span>Клиенты</span>
            </label>
            <label style="flex: 1; display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; font-weight: 500;">
                <input type="checkbox" id="ieOptSettings" checked style="width: 18px; height: 18px; cursor: pointer; accent-color: var(--primary);">
                <span>Настройки</span>
            </label>
        </div>

        <div id="iePaneExport" style="display: flex; flex-direction: column; gap: 12px;">
            <div style="display: flex; gap: 10px;">
                <button class="btn btn-ghost" style="flex: 1; border: 1px solid var(--border); gap: 8px; justify-content: center;" onclick="executeExportDownload()">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                    <span>Скачать</span>
                </button>
                <button class="btn btn-ghost" style="flex: 1; border: 1px solid var(--border); gap: 8px; justify-content: center;" onclick="executeExportCopy()">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                    <span>Копировать</span>
                </button>
            </div>
        </div>

        <div id="iePaneImport" style="display: none; flex-direction: column; gap: 12px;">
            <div style="display: flex; gap: 10px;">
                <label class="btn btn-ghost" style="flex: 1; border: 1px solid var(--border); cursor: pointer; gap: 8px; justify-content: center; display: inline-flex; align-items: center;">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="12" y1="18" x2="12" y2="12"></line><line x1="9" y1="15" x2="15" y2="15"></line></svg>
                    <span>Загрузить файл</span>
                    <input type="file" id="ieFileInput" accept=".json" style="display: none;" onchange="handleImportFile(event)">
                </label>
                <button class="btn btn-ghost" style="flex: 1; border: 1px solid var(--border); gap: 8px; justify-content: center;" onclick="executeImportFromClipboard()">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path><rect x="8" y="2" width="8" height="4" rx="1" ry="1"></rect></svg>
                    <span>Вставить из буфера</span>
                </button>
            </div>
            <div id="ieImportStatus" style="font-size: 13px; font-weight: 600; display: none; text-align: center;"></div>
        </div>
    </dialog>

    <!-- API Documentation Modal -->
    <dialog id="apiDocsDlg" style="max-width: 940px; width: min(940px, calc(100% - 24px)); max-height: 88vh; overflow-y: auto;">
        <div class="dlg-header">
            <div style="display: flex; align-items: center; gap: 10px;">
                <div style="width: 36px; height: 36px; border-radius: 10px; background: var(--icon-bg); color: var(--primary); display: grid; place-items: center;">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="16 18 22 12 16 6"></polyline>
                        <polyline points="8 6 2 12 8 18"></polyline>
                    </svg>
                </div>
                <div>
                    <h2 style="font-size: 20px; font-weight: 700; margin: 0;">Документация REST API</h2>
                    <p style="font-size: 12px; color: var(--text-muted); margin: 0;">Интеграция с Telegram-ботами, панелями управления и биллингом</p>
                </div>
            </div>
            <button class="close-btn" onclick="document.getElementById('apiDocsDlg').close()">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        </div>

        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px; margin-bottom: 16px;">
            <div style="background: var(--surface-hover); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px;">
                <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px;">
                    <div style="display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 13px; color: var(--text-main);">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>
                        Базовый адрес API (Base URL)
                    </div>
                    <button class="btn btn-ghost" onclick="copyText(document.getElementById('apiBaseUrlDisplay').textContent, this)" style="padding: 2px 8px; font-size: 11px; height: 22px; min-height: auto;">Копировать</button>
                </div>
                <div style="font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; color: var(--primary); background: var(--bg-color); padding: 6px 10px; border-radius: 6px; border: 1px solid var(--border); word-break: break-all;" id="apiBaseUrlDisplay"></div>
            </div>
            <div style="background: var(--surface-hover); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px;">
                <div style="display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 13px; margin-bottom: 6px; color: var(--text-main);">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>
                    Авторизация запросов (Headers)
                </div>
                <div style="display: flex; flex-direction: column; gap: 4px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px;">
                    <div style="background: var(--bg-color); padding: 5px 8px; border-radius: 6px; border: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center;">
                        <code>X-API-Key: &lt;пароль_панели&gt;</code>
                        <span style="color: var(--text-muted); font-size: 10px;">Рекомендуемый</span>
                    </div>
                    <div style="background: var(--bg-color); padding: 5px 8px; border-radius: 6px; border: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center;">
                        <code>Authorization: Bearer &lt;пароль&gt;</code>
                        <span style="color: var(--text-muted); font-size: 10px;">Стандарт Bearer</span>
                    </div>
                </div>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="api-tabs-nav">
            <button id="btnApiTabClients" class="api-tab-btn active" onclick="switchApiDocsTab('clients')">
                👥 Клиенты
            </button>
            <button id="btnApiTabGroups" class="api-tab-btn" onclick="switchApiDocsTab('groups')">
                📁 Группы
            </button>
            <button id="btnApiTabSubs" class="api-tab-btn" onclick="switchApiDocsTab('subs')">
                🔗 Подписки
            </button>
            <button id="btnApiTabRouting" class="api-tab-btn" onclick="switchApiDocsTab('routing')">
                🔀 Маршрутизация & Прокси
            </button>
            <button id="btnApiTabSystem" class="api-tab-btn" onclick="switchApiDocsTab('system')">
                ⚙️ Система & Бэкап
            </button>
        </div>

        <!-- TAB 1: Clients -->
        <div id="apiPaneClients" style="display: flex; flex-direction: column; gap: 12px; margin-bottom: 20px;">
            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients</span>
                    <span class="api-desc">Создание нового клиента (ключа)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Создает ключ доступа. Если <code>password</code> не указан, сервер сгенерирует надежный криптостойкий пароль. В ответе возвращаются готовая ссылка <code>link</code> (<code>csqtt://...</code>) и срок <code>expires</code>.
                    </p>
                    <div class="api-code-label">Тело запроса (JSON):</div>
                    <pre class="api-code-block"><code>{
  "name": "Пользователь @username",
  "days": 30,                     // 0 = бессрочно, 1..3650 = дней
  "password": "custom_pass",       // опционально (мин. 4 символа)
  "peer_port": 46000,             // опционально (UDP порт)
  "group_id": "group_id_here",     // опционально (привязка к группе)
  "vk_token": false,               // опционально (общий VK токен)
  "hash": "hash1,hash2"            // опционально (VK хеши)
}</code></pre>
                    <div class="api-code-label" style="margin-top: 8px;">Пример curl:</div>
                    <pre class="api-code-block"><code>curl -X POST https://example.com/api/clients \
  -H "X-API-Key: YOUR_PASSWORD" \
  -H "Content-Type: application/json" \
  -d '{"name":"TelegramUser","days":30}'</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/clients</span>
                    <span class="api-desc">Список всех клиентов (с фильтрацией)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 6px;">
                        Возвращает массив клиентов: расход трафика, статус активности, срок, <code>ip</code>, <code>sub_uuid</code> и <code>link</code>.
                    </p>
                    <p style="font-size: 11px; color: var(--primary);">
                        Фильтры (Query): <code>?group_id={id|root}</code>, <code>?active=true|false</code>, <code>?search=текст</code>.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/clients/{password}</span>
                    <span class="api-desc">Информация по одному клиенту</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает объект клиента по его паролю (трафик, активные сессии, IP-адреса, скорость, срок, ссылка подписки и <code>link</code>).
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}</span>
                    <span class="api-desc">Обновление параметров клиента</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Изменение имени, срока, пароля, UDP-порта, группы или VK параметров:
                    </p>
                    <pre class="api-code-block"><code>{ "name": "Новое Имя", "days": 60, "peer_port": 46000, "group_id": "" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}/extend</span>
                    <span class="api-desc">Продление срока действия ключа</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Продлевает ключ на указанное число дней. Если ключ истёк — отсчет от текущего момента; если активен — дни прибавляются к дате окончания:
                    </p>
                    <pre class="api-code-block"><code>{ "days": 30 }</code></pre>
                    <div class="api-code-label" style="margin-top: 8px;">Ответ: <code>{ "ok": true, "expires": 1791600000 }</code></div>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}/toggle</span>
                    <span class="api-desc">Блокировка / разблокировка</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Переключает статус клиента. При деактивации активные туннели ключа мгновенно сбрасываются. Ответ: <code>{ "ok": true, "active": false }</code>.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}/unbind</span>
                    <span class="api-desc">Сброс привязки клиента</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Сбрасывает эпоху пароля и активные сессии клиента, позволяя подключиться заново.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}/reset-traffic</span>
                    <span class="api-desc">Сброс расхода трафика в 0</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Сбрасывает счетчики отправленного и принятого трафика в базе данных и у активных сессий в ноль.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/{password}/sub-link/regenerate</span>
                    <span class="api-desc">Перегенерация ссылки подписки</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Генерирует новый постоянный <code>sub_uuid</code> для клиента или Главного пароля, мгновенно аннулируя старый адрес подписки.
                    </p>
                    <pre class="api-code-block"><code>Ответ: { "ok": true, "sub_uuid": "e2a3b4c5-..." }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method put">PUT</span>
                    <span class="api-path">/api/clients/{password}/vk</span>
                    <span class="api-desc">Персональная настройка VK токена и хешей</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "vk_token": true, "vk_hashes": "hash1,hash2" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method delete">DELETE</span>
                    <span class="api-path">/api/clients/{password}</span>
                    <span class="api-desc">Удаление клиента</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Полностью удаляет ключ с сервера и немедленно разрывает активные соединения.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/links</span>
                    <span class="api-desc">Массив всех готовых ссылок csqtt://</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает компактный массив объектов всех клиентов и мастер-пароля с готовыми ссылками <code>csqtt://...</code> и <code>sub_link</code> для ботов.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/links/batch-vk</span>
                    <span class="api-desc">Пакетная настройка VK токенов и хешей</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "vk_token": "vk1.a...", "vk_hashes": "hash1,hash2", "apply_to_all_clients": true }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/batch-toggle</span>
                    <span class="api-desc">Массовое переключение активности</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "passwords": ["p1", "p2"], "active": true }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/batch-extend</span>
                    <span class="api-desc">Массовое продление срока ключей</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "passwords": ["p1", "p2"], "days": 30 }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/batch-reset-traffic</span>
                    <span class="api-desc">Массовый сброс расхода трафика</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "passwords": ["p1", "p2"] }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/batch-delete</span>
                    <span class="api-desc">Массовое удаление клиентов</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "passwords": ["p1", "p2"] }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/clients/batch-group</span>
                    <span class="api-desc">Массовое перемещение между группами</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "passwords": ["p1", "p2"], "group_id": "target_group_id_or_empty" }</code></pre>
                </div>
            </div>
        </div>

        <!-- TAB 2: Groups -->
        <div id="apiPaneGroups" style="display: none; flex-direction: column; gap: 12px; margin-bottom: 20px;">
            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/groups</span>
                    <span class="api-desc">Список групп и лимитов скорости</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает все группы: <code>id</code>, <code>name</code>, ограничение скорости <code>rate_limit_mbps</code>, привязанное правило <code>routing_rule_id</code>, постоянный <code>sub_uuid</code> групповой подписки, статус админ-панели и <code>admin_uuid</code>.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/groups</span>
                    <span class="api-desc">Создание группы</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "name": "VIP Клиенты", "rate_limit_mbps": 100, "routing_rule_id": "" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method put">PUT</span>
                    <span class="api-path">/api/groups/{id}</span>
                    <span class="api-desc">Редактирование группы</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "name": "Новое Имя Группы", "routing_rule_id": "rule_id_here" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method delete">DELETE</span>
                    <span class="api-path">/api/groups/{id}</span>
                    <span class="api-desc">Каскадное удаление группы</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: #ef4444; font-weight: 500;">
                        Внимание: удаляет группу и безвозвратно удаляет всех входящих в неё клиентов.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-method delete" style="margin-left: -4px;">DELETE</span>
                    <span class="api-path">/api/groups/{id}/admin-link</span>
                    <span class="api-desc">Управление админ-панелью группы</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        <code>POST</code> генерирует секретную ссылку <code>/{admin_uuid}/admin</code> для делегированного управления клиентами группы без доступа к настройкам сервера. <code>DELETE</code> отключает ссылку.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/groups/{id}/admin-link/regenerate</span>
                    <span class="api-desc">Перегенерация секрета админ-панели</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Выпускает новый <code>admin_uuid</code> группы. Предыдущая ссылка мгновенно аннулируется.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/groups/{id}/sub-link/regenerate</span>
                    <span class="api-desc">Перегенерация ссылки групповой подписки</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Выпускает новый уникальный <code>sub_uuid</code> для групповой подписки <code>/{group_sub_uuid}/sub-group</code>. Ответ: <code>{ "ok": true, "sub_uuid": "..." }</code>.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/groups/{id}/clients</span>
                    <span class="api-desc">Список клиентов выбранной группы</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает JSON-массив всех клиентов, входящих в группу с указанным <code>id</code>.
                    </p>
                </div>
            </div>
        </div>

        <!-- TAB 3: Subscriptions -->
        <div id="apiPaneSubs" style="display: none; flex-direction: column; gap: 12px; margin-bottom: 20px;">
            <div style="background: rgba(59, 130, 246, 0.08); border: 1px solid rgba(59, 130, 246, 0.2); border-radius: 10px; padding: 10px 14px; font-size: 12px; color: var(--text-muted); line-height: 1.5;">
                <strong style="color: #60a5fa;">Изоляция доступа:</strong> эндпоинты подписок и управления группами открываются напрямую от корня со своим персональным секретным UUID (без префикса главной админки). Это предотвращает утечку пути главной панели администратора.
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/{sub_uuid}/sub</span>
                    <span class="api-desc">Веб-страница персональной подписки (HTML)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Публичная веб-страница подписки клиента или Главного пароля (без необходимости авторизации). Содержит живой векторный QR-код, прямую ссылку <code>csqtt://connect?...</code>, кнопку «Подключить в приложении CSQTT» (с нативным уведомлением в приложении), контрастную кнопку копирования и инструкции.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/{sub_uuid}/json</span>
                    <span class="api-desc">Конфигурация подписки клиента (JSON)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Чистый эндпоинт для автоматического обновления конфигурации клиентом или ботом (также доступны алиасы <code>/{sub_uuid}/sub.json</code> и <code>/api/sub/{sub_uuid}</code>):
                    </p>
                    <pre class="api-code-block"><code>{
  "v": 2,
  "name": "Пользователь",
  "host": "vpn.example.com",
  "peer": 46000,
  "password": "p_abc123...",
  "vk_token": "...",
  "hash": "...",
  "link": "csqtt://connect?v=2&host=vpn.example.com&peer=46000&password=..."
}</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/{group_sub_uuid}/sub-group</span>
                    <span class="api-desc">Групповая страница подписок (HTML)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Публичная адаптивная страница для группы. Выводит всех активных клиентов группы в виде аккуратной сетки с QR-кодами, ссылками, кнопками копирования и быстрого подключения в CSQTT.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/{group_sub_uuid}/json</span>
                    <span class="api-desc">Конфигурации клиентов группы (JSON)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает JSON-массив с параметрами подключения всех активных клиентов группы (также доступны алиасы <code>/{group_sub_uuid}/sub-group.json</code> и <code>/api/sub-group/{group_sub_uuid}</code>): <code>[{ "name": "...", "link": "csqtt://...", ... }]</code>.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/{admin_uuid}/admin</span>
                    <span class="api-desc">Панель управления группы (HTML & API)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Автономная панель для модератора группы. Управление клиентами и настройками группы изолировано под префиксом <code>/{admin_uuid}/api/...</code> (получение данных группы: <code>GET /{admin_uuid}/api/data</code>, создание клиента: <code>POST /{admin_uuid}/api/clients</code>).
                    </p>
                </div>
            </div>
        </div>

        <!-- TAB 4: Routing & Proxy -->
        <div id="apiPaneRouting" style="display: none; flex-direction: column; gap: 12px; margin-bottom: 20px;">
            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-method post" style="margin-left: -4px;">POST</span>
                    <span class="api-path">/api/routing-rules</span>
                    <span class="api-desc">Правила выборочной маршрутизации</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        <code>GET</code> возвращает список настроенных правил обхода. <code>POST</code> создает новое правило с доменами, IP-диапазонами и outbound-действием (прямой выход или прокси):
                    </p>
                    <pre class="api-code-block"><code>{
  "name": "YouTube & Discord",
  "domains": ["youtube.com", "googlevideo.com", "discord.com"],
  "ip_ranges": ["162.158.0.0/15"],
  "action": "proxy", // "direct" или "proxy"
  "proxy_id": "proxy_uuid_here"
}</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method put">PUT</span>
                    <span class="api-method delete" style="margin-left: -4px;">DELETE</span>
                    <span class="api-path">/api/routing-rules/{id}</span>
                    <span class="api-desc">Редактирование и удаление правила</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        <code>PUT</code> обновляет список доменов и IP правила, <code>DELETE</code> удаляет правило.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/template-domains</span>
                    <span class="api-desc">Шаблоны доменов популярных сервисов</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает готовые списки доменов для YouTube, Discord, Instagram, Twitter/X, ChatGPT, Spotify и др.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-method post" style="margin-left: -4px;">POST</span>
                    <span class="api-path">/api/local-proxy</span>
                    <span class="api-desc">Outbound прокси (SOCKS5 / HTTP)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted); margin-bottom: 8px;">
                        Добавление внешних upstream прокси-серверов для маршрутизации трафика:
                    </p>
                    <pre class="api-code-block"><code>{
  "name": "Германия SOCKS5",
  "protocol": "socks5", // "socks5" или "http"
  "host": "198.51.100.1",
  "port": 1080,
  "username": "user",
  "password": "pass"
}</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method put">PUT</span>
                    <span class="api-method delete" style="margin-left: -4px;">DELETE</span>
                    <span class="api-path">/api/local-proxy/profiles/{id}</span>
                    <span class="api-desc">Управление профилем прокси</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Редактирование хоста/порта/пароля или удаление профиля прокси.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/local-proxy/activate/{id}</span>
                    <span class="api-desc">Активация системного прокси</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Назначает выбранный прокси глобальным шлюзом по умолчанию (или <code>POST /api/local-proxy/deactivate</code> для отключения).
                    </p>
                </div>
            </div>
        </div>

        <!-- TAB 5: System & Settings -->
        <div id="apiPaneSystem" style="display: none; flex-direction: column; gap: 12px; margin-bottom: 20px;">
            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/stats</span>
                    <span class="api-desc">Мониторинг нагрузки и ресурсов</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает нагрузку CPU (%), использование RAM (байт), число активных туннелей и скорость сети.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-path">/api/diagnostics/dpi</span>
                    <span class="api-desc">DPI диагностика и телеметрия пакетов</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Возвращает статус кольцевого буфера DPI, количество зафиксированных аномальных пакетов и последние семплы фреймов для отладки цензуры.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/memory/trim</span>
                    <span class="api-desc">Принудительное освобождение RAM (Trim)</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Вызывает сброс неиспользуемой памяти в ядро операционной системы для минимизации RSS.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-method post" style="margin-left: -4px;">POST</span>
                    <span class="api-path">/api/settings</span>
                    <span class="api-desc">Глобальные настройки сервера</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Получение и сохранение настроек: DNS, MTU, stealth-домены маскировки, интервал автоперезапуска, лимиты.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/settings/dns</span>
                    <span class="api-desc">Быстрая смена DNS</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "dns_provider": "cloudflare", "dns_primary": "1.1.1.1", "dns_secondary": "1.0.0.1" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/settings/restart-interval</span>
                    <span class="api-desc">Интервал автоперезапуска (в часах)</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "interval_hours": 12 }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/settings/master-password</span>
                    <span class="api-desc">Смена главного пароля сервера</span>
                </div>
                <div class="api-card-body">
                    <pre class="api-code-block"><code>{ "password": "новый_пароль" }</code></pre>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-method post" style="margin-left: -4px;">POST</span>
                    <span class="api-path">/api/export и /api/import</span>
                    <span class="api-desc">Резервная копия и неразрушающее слияние</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        <code>GET /api/export</code> выгружает полный JSON базы. <code>POST /api/import</code> производит умное слияние: клиенты и настройки добавляются к существующим без перезаписи и потери трафика.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method get">GET</span>
                    <span class="api-method post" style="margin-left: -4px;">POST</span>
                    <span class="api-path">/api/logs</span>
                    <span class="api-desc">Журнал логов сервера</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        <code>GET /api/logs</code> возвращает строки логов. <code>POST /api/logs/toggle</code> включает/выключает запись, <code>POST /api/logs/clear</code> очищает буфер, а <code>GET /api/logs/download</code> отдает текстовый файл.
                    </p>
                </div>
            </div>

            <div class="api-card">
                <div class="api-card-header">
                    <span class="api-method post">POST</span>
                    <span class="api-path">/api/reboot</span>
                    <span class="api-desc">Перезапуск сервера CSQTT</span>
                </div>
                <div class="api-card-body">
                    <p style="font-size: 12px; color: var(--text-muted);">
                        Инициирует контролируемый мягкий перезапуск процесса CSQTT на сервере.
                    </p>
                </div>
            </div>
        </div>

        <div class="dlg-actions">
            <button class="btn btn-primary" onclick="document.getElementById('apiDocsDlg').close()">Закрыть</button>
        </div>
    </dialog>

    <dialog id="rebootDlg">
        <h2>Перезапуск сервера</h2>
        <p style="color: var(--text-muted); font-size: 15px; margin-bottom: 24px;">Вы уверены, что хотите перезапустить CSQTT? Активные соединения будут прерваны на несколько секунд.</p>
        <div class="dlg-actions">
            <button class="btn btn-ghost" onclick="document.getElementById('rebootDlg').close()">Отмена</button>
            <button class="btn btn-primary" onclick="executeReboot()">Перезапустить</button>
        </div>
    </dialog>

    <div class="toast-container" id="toastContainer"></div>

    <script>
        const basePath = window.location.pathname.replace(/\/$/, '');
        if (basePath) {
            document.querySelectorAll('link[rel="manifest"], link[rel="icon"], link[rel="apple-touch-icon"]').forEach(el => {
                const href = el.getAttribute('href');
                if (href && href.startsWith('/')) {
                    el.setAttribute('href', basePath + href);
                }
            });
        }
        if ('serviceWorker' in navigator && window.isSecureContext) {
            window.addEventListener('load', () => {
                navigator.serviceWorker.register((basePath || '') + '/sw.js', { scope: (basePath || '') + '/' }).catch(() => {});
            });
        }
        function openCryptoDonateDlg() {
            document.getElementById('cryptoDonateDlg').showModal();
        }
        async function copyText(text, btn) {
            if (await copyToClipboard(text)) {
                if (btn) {
                    const orig = btn.textContent;
                    btn.textContent = "Скопировано!";
                    setTimeout(() => { btn.textContent = orig; }, 2000);
                }
                showToast("Адрес скопирован в буфер");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }
        function toggleTheme() {
            const isDark = document.body.parentElement.getAttribute('data-theme') === 'dark';
            document.body.parentElement.setAttribute('data-theme', isDark ? 'light' : 'dark');
            localStorage.setItem('csqtt-theme', isDark ? 'light' : 'dark');
            document.getElementById('themeIcon').innerHTML = isDark
                ? '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>'
                : '<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>';
        }
        if (localStorage.getItem('csqtt-theme') === 'dark') { toggleTheme(); }

        const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

        async function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try {
                    await navigator.clipboard.writeText(text);
                    return true;
                } catch (e) {}
            }
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                ta.style.top = '0';
                ta.setAttribute('readonly', '');
                document.body.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, 99999);
                const success = document.execCommand('copy');
                document.body.removeChild(ta);
                return success;
            } catch (e) {
                return false;
            }
        }

        let apiNetworkToastShown = false;
        async function api(u, o = {}) {
            try {
                let url = (basePath || '') + u;
                const opts = { credentials: "include", ...o, headers: { "content-type": "application/json", ...(o.headers || {}) } };
                if (!opts.signal && typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
                    opts.signal = AbortSignal.timeout(8000);
                }
                let res = await fetch(url, opts);
                if (res.status === 401) { location.reload(); }
                apiNetworkToastShown = false;
                return res;
            } catch (e) {
                if (e && (e.name === 'AbortError' || e.name === 'TimeoutError')) {
                    throw e;
                }
                if (!apiNetworkToastShown) {
                    apiNetworkToastShown = true;
                    showToast("Ошибка сети", "error");
                }
                throw e;
            }
        }

        const size = n => {
            if (n < 1024) return n + " B";
            if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
            if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
            return (n / 1073741824).toFixed(2) + " GB";
        };

        const compactCounter = input => {
            const count = Number(input);
            if (!Number.isFinite(count) || count <= 0) return "0";
            const units = ["", "K", "M", "G", "T", "P", "E"];
            let value = count;
            let unit = 0;
            while (value >= 1000 && unit < units.length - 1) {
                value /= 1000;
                unit += 1;
            }
            if (value >= 999.5 && unit < units.length - 1) {
                value /= 1000;
                unit += 1;
            }
            const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
            return value.toFixed(digits).replace(/\.0+$/, "") + units[unit];
        };

        const uptime = s => {
            const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
            return d > 0 ? d + "д " + h + "ч" : h > 0 ? h + "ч " + m + "м" : m + "м";
        };

        function showToast(text, type = 'success') {
            const container = document.getElementById('toastContainer');
            if (container) {
                const openDialog = Array.from(document.querySelectorAll('dialog[open]')).pop();
                if (openDialog) {
                    openDialog.appendChild(container);
                } else if (container.parentElement !== document.body) {
                    document.body.appendChild(container);
                }
            }
            const toast = document.createElement('div');
            toast.className = 'toast ' + (type === 'error' ? 'toast-error' : 'toast-success');
            const icon = document.createElement('div');
            icon.className = 'toast-icon';
            if (type === 'error') {
                icon.style.color = '#ef4444';
                icon.style.background = 'rgba(239, 68, 68, 0.15)';
                icon.textContent = '✗';
            } else {
                icon.style.color = 'var(--primary)';
                icon.style.background = 'var(--icon-bg)';
                icon.textContent = '✓';
            }
            const message = document.createElement('span');
            message.textContent = String(text);
            toast.append(icon, message);
            if (container) container.appendChild(toast);
            setTimeout(() => { toast.classList.add('hide'); setTimeout(() => toast.remove(), 300); }, 3000);
        }

        function showConfirm(title, message, btnText, btnClass, callback, cancelCallback) {
            const dlg = document.getElementById('confirmDlg');
            const titleEl = document.getElementById('confirmTitle');
            const msgEl = document.getElementById('confirmMessage');
            const btn = document.getElementById('confirmActionBtn');
            const cancelBtn = dlg.querySelector('button.btn-ghost');
            let actionTriggered = false;

            if (titleEl) titleEl.textContent = title || 'Подтверждение';
            if (msgEl) msgEl.textContent = message || '';
            if (btn) {
                btn.textContent = btnText || 'Подтвердить';
                btn.className = `btn ${btnClass || 'btn-primary'}`;
                btn.onclick = () => {
                    actionTriggered = true;
                    dlg.close();
                    if (callback) callback();
                };
            }
            if (cancelBtn) {
                cancelBtn.onclick = () => {
                    dlg.close();
                };
            }
            if (dlg) {
                dlg.onclose = () => {
                    if (!actionTriggered && cancelCallback) {
                        cancelCallback();
                    }
                };
                dlg.showModal();
            }
        }

        let statsInFlight = false;
        async function loadStats() {
            if (statsInFlight) return;
            statsInFlight = true;
            try {
                let r = await api("/api/stats"); if(!r.ok) return; let x = await r.json();
                if (x.peer_port) window.serverDefaultPeerPort = Number(x.peer_port);
                if (x.public_ip) window.serverPublicIp = x.public_ip;
                document.getElementById('status_val').textContent = x.status === "Active" ? "Активно" : x.status;
                document.getElementById('status_uptime').textContent = "Аптайм: " + uptime(x.uptime);
                document.getElementById('active_val').textContent = x.active;
                const cpuTotal = Number(x.cpu_total) || 0;
                const cpuCapacity = Math.max(100, Number(x.cpu_capacity) || 100);
                const cpuNormalized = Math.min(100, Math.max(0, Number(x.cpu_normalized) || 0));
                document.getElementById('cpu_val').textContent = cpuTotal + '% / ' + cpuCapacity + '%';
                document.getElementById('cpu_bar').style.width = cpuNormalized + '%';
                const ramValue = document.getElementById('ram_val');
                const ramDetail = document.getElementById('ram_detail');
                ramValue.textContent = x.ram_used || String(x.ram || '').split(' / ')[0] || '0 MB';
                ramDetail.textContent = `из ${x.ram_total || '0 MB'} · пик ${x.ram_peak || ramValue.textContent}`;
                ramValue.title = [
                    `RSS: ${x.ram_used || '0 MB'}`,
                    `Анонимная: ${x.ram_anonymous || '0 MB'}`,
                    `Файлы: ${x.ram_file || '0 MB'}`,
                    `Shared: ${x.ram_shared || '0 MB'}`,
                    `Swap: ${x.ram_swap || '0 MB'}`,
                    `Пик: ${x.ram_peak || '0 MB'}`,
                    `PSS: ${x.ram_pss || 'н/д'} · private: ${x.ram_private || 'н/д'} · shared: ${x.ram_shared_rollup || 'н/д'}`,
                    `smaps PSS anon/file/shmem: ${x.ram_anon_rollup || 'н/д'} / ${x.ram_file_rollup || 'н/д'} / ${x.ram_shmem_rollup || 'н/д'} · Anonymous RSS-like: ${x.ram_anonymous_rollup || 'н/д'}`,
                    `Аллокатор: ${x.allocator || 'system'}`,
                    `Сессии: ${Number(x.hot_sessions) || 0}/${Number(x.hot_session_capacity) || 0}`,
                    `TPROXY TCP/UDP: ${Number(x.local_proxy_tcp_sessions) || 0}/${Number(x.local_proxy_udp_flows) || 0}`,
                    `Эпохи движка: ${Number(x.mem_engine_epochs) || 0}`,
                    `Ключи: ${Number(x.mem_derived_keys) || 0} · web-сессии: ${Number(x.mem_web_sessions) || 0}/${Number(x.mem_web_session_capacity) || 0} · login-limit: ${Number(x.mem_login_limits) || 0}`,
                    `stream repair/inventory: ${Number(x.mem_stream_repairs) || 0}/${Number(x.mem_stream_inventory) || 0} · DPI: ${Number(x.mem_dpi_ring) || 0} · логи: ${Number(x.mem_log_ring) || 0} (${Number(x.mem_log_bytes) || 0} B)`,
                    `Циклов memory trim: ${Number(x.memory_trim_count) || 0}${Number(x.memory_trim_last_unix) ? ` · последнее: ${new Date(Number(x.memory_trim_last_unix) * 1000).toLocaleTimeString()}` : ''}`
                ].join('\n');
                document.getElementById('traffic_up').textContent = "↑ " + size(x.up);
                document.getElementById('traffic_down').textContent = "↓ " + size(x.down);
                document.getElementById('traffic_ingress').textContent = `RX ${compactCounter(x.udp_rx_packets)} · TX ${compactCounter(x.udp_tx_packets)}`;

                updateLocalProxyRuntime(
                    Boolean(x.local_proxy_enabled),
                    Boolean(x.local_proxy_active),
                    x.local_proxy_port_listening,
                    Number(x.local_proxy_tcp_sessions) || 0,
                    Number(x.local_proxy_udp_flows) || 0,
                    x.local_proxy_health_error || null
                );
            } catch (e) {
            } finally {
                statsInFlight = false;
            }
        }

        let savedMainPassword = '';
        let savedDnsProvider = 'yandex';
        let savedManualDns = '';
        let savedAutoRestartInterval = 0;
        let sharedVkToken = '';
        let sharedVkHashes = '';

        function setRestartRequired(required) {
            document.getElementById('restartRequiredBanner')?.classList.toggle('visible', Boolean(required));
        }

        function syncDnsControls() {
            const provider = document.getElementById('dns_provider')?.value;
            document.getElementById('dns_selection')?.classList.toggle('is-custom', provider === 'custom');
        }

        function updateSettingsDirtyState() {
            const mainPassword = document.getElementById('mainpass')?.value ?? '';
            const dnsProvider = document.getElementById('dns_provider')?.value ?? '';
            const manualDns = document.getElementById('dns_manual')?.value ?? '';
            const autoRestartInterval = Number(document.getElementById('auto_restart_interval')?.value ?? 0);
            const mainButton = document.getElementById('saveMainPasswordBtn');
            const dnsButton = document.getElementById('saveDnsBtn');
            const autoRestartButton = document.getElementById('saveAutoRestartIntervalBtn');
            if (mainButton && !mainButton.classList.contains('saving')) {
                mainButton.disabled = mainPassword === savedMainPassword;
            }
            if (dnsButton && !dnsButton.classList.contains('saving')) {
                dnsButton.disabled = dnsProvider === savedDnsProvider
                    && (dnsProvider !== 'custom' || manualDns === savedManualDns);
            }
            if (autoRestartButton && !autoRestartButton.classList.contains('saving')) {
                autoRestartButton.disabled = autoRestartInterval === savedAutoRestartInterval;
            }
        }

        async function loadSettings() {
            let r = await api("/api/settings"); if(!r.ok) return; let x = await r.json();
            savedMainPassword = x.main_password || '';
            savedDnsProvider = x.dns_provider || 'custom';
            savedManualDns = [x.dns_primary, x.dns_secondary].filter(Boolean).join(',');
            savedAutoRestartInterval = [0, 12, 24, 48, 72].includes(Number(x.auto_restart_interval_hours))
                ? Number(x.auto_restart_interval_hours)
                : 0;
            sharedVkToken = x.vk_token || '';
            sharedVkHashes = x.vk_hashes || '';
            document.getElementById('mainpass').value = savedMainPassword;
            document.getElementById('dns_provider').value = savedDnsProvider;
            document.getElementById('dns_manual').value = savedManualDns;
            document.getElementById('auto_restart_interval').value = String(savedAutoRestartInterval);
            syncDnsControls();
            setRestartRequired(x.restart_required);
            updateSettingsDirtyState();
            loadLocalProxy();
        }

        function openVkTokenSettingsDlg() {
            document.getElementById('vkTokenSettingsInput').value = sharedVkToken;
            document.getElementById('vkTokenSettingsDlg').showModal();
        }

        async function saveVkTokenSettings(clear = false) {
            const input = document.getElementById('vkTokenSettingsInput');
            const token = clear ? '' : input.value.trim();
            if (token && (/\s/.test(token) || token.length > 4096)) {
                showToast('VK токен не должен содержать пробелы и должен быть короче 4097 символов', 'error');
                return;
            }
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify({ vk_token: token })
            });
            if (!response.ok) {
                showToast(await response.text(), 'error');
                return;
            }
            sharedVkToken = token;
            input.value = token;
            document.getElementById('vkTokenSettingsDlg').close();
            showToast(token ? 'Общий VK токен сохранён' : 'Общий VK токен очищен');
        }

        function clearVkTokenSettings() {
            saveVkTokenSettings(true);
        }

        let localProxyData = { active_profile_id: '', profiles: [], route_active: false, port_listening: true, health_error: null, tcp_sessions: 0, udp_flows: 0 };
        let profileDlgEditId = null;
        let profileDlgSaving = false;

        function renderProxyProfiles() {
            const container = document.getElementById('proxyProfilesList');
            if (!container) return;
            const { profiles, active_profile_id, route_active, port_listening, health_error, tcp_sessions, udp_flows } = localProxyData;
            if (!profiles.length) {
                container.innerHTML = '<div class="proxy-empty">Нет профилей. Нажмите кнопку <strong>+</strong> чтобы добавить.</div>';
                return;
            }
            const playIcon = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7.5 4.9v14.2a1 1 0 0 0 1.53.85l11.3-7.1a1 1 0 0 0 0-1.7L9.03 4.05a1 1 0 0 0-1.53.85z"/></svg>';
            const stopIcon = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="5.5" y="5.5" width="13" height="13" rx="2"/></svg>';
            const editIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>';
            const trashIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>';
            container.innerHTML = profiles.map(p => {
                const isActive = p.id === active_profile_id;
                let badge = '';
                if (isActive) {
                    if (route_active) {
                        badge = `<span style="font-size:11px;font-weight:600;color:#10b981;margin-left:6px;">Активен</span>`;
                    } else if (port_listening === false) {
                        badge = `<span style="font-size:11px;font-weight:600;color:#ef4444;margin-left:6px;">Порт не слушается</span>`;
                    } else if (health_error) {
                        badge = `<span style="font-size:11px;font-weight:600;color:#ef4444;margin-left:6px;" title="${esc(health_error)}">Проба не прошла (Direct)</span>`;
                    } else {
                        badge = `<span style="font-size:11px;font-weight:600;color:#f59e0b;margin-left:6px;">Подключение...</span>`;
                    }
                }
                let meta = p.username ? `${p.port} · ${p.username}` : `${p.port}`;
                if (isActive) {
                    if (route_active) {
                        meta += ` · ${tcp_sessions} TCP · ${udp_flows} UDP`;
                    } else if (health_error) {
                        meta += ` · Ошибка: ${esc(health_error)}`;
                    }
                }
                const toggleBtn = isActive
                    ? `<button title="Деактивировать" onclick="deactivateProxy()">${stopIcon}</button>`
                    : `<button title="Активировать" onclick="activateProxy('${esc(p.id)}')">${playIcon}</button>`;
                return `<div class="proxy-profile-card${isActive ? ' is-active' : ''}" data-id="${esc(p.id)}">
                    <div class="proxy-profile-dot"></div>
                    <div class="proxy-profile-body">
                        <div class="proxy-profile-name">${esc(p.name)}${badge}</div>
                        <div class="proxy-profile-meta">${meta}</div>
                    </div>
                    <div class="proxy-profile-actions">
                        ${toggleBtn}
                        <button title="Редактировать" onclick="openProfileDlg('${esc(p.id)}')">${editIcon}</button>
                        <button class="danger" title="Удалить" onclick="deleteProxy('${esc(p.id)}','${esc(p.name)}')">${trashIcon}</button>
                    </div>
                </div>`;
            }).join('');
        }

        function updateLocalProxyRuntime(enabled, active, portListening, tcpSessions, udpFlows, healthError) {
            const routeActive = Boolean(enabled) && Boolean(active);
            const listening = portListening !== false;
            const tcp = Number(tcpSessions) || 0;
            const udp = Number(udpFlows) || 0;
            const err = healthError || null;
            if (
                localProxyData.route_active === routeActive &&
                localProxyData.port_listening === listening &&
                localProxyData.health_error === err &&
                localProxyData.tcp_sessions === tcp &&
                localProxyData.udp_flows === udp
            ) return;
            localProxyData.route_active = routeActive;
            localProxyData.port_listening = listening;
            localProxyData.health_error = err;
            localProxyData.tcp_sessions = tcp;
            localProxyData.udp_flows = udp;
            if (document.getElementById('settings-section').style.display !== 'none') {
                renderProxyProfiles();
            }
        }

        async function loadLocalProxy() {
            const r = await api('/api/local-proxy');
            if (!r.ok) return;
            localProxyData = await r.json();
            renderProxyProfiles();
        }

        function openProfileDlg(editId) {
            profileDlgEditId = editId || null;
            const dlg = document.getElementById('profileDlg');
            const title = document.getElementById('profileDlgTitle');
            const saveBtn = document.getElementById('profileDlgSave');
            if (editId) {
                const p = localProxyData.profiles.find(x => x.id === editId);
                if (!p) return;
                title.textContent = 'Редактировать профиль';
                saveBtn.textContent = 'Сохранить';
                document.getElementById('profileDlgName').value = p.name;
                document.getElementById('profileDlgPort').value = p.port;
                document.getElementById('profileDlgUser').value = p.username || '';
                document.getElementById('profileDlgPass').value = p.password || '';
            } else {
                title.textContent = 'Новый профиль';
                saveBtn.textContent = 'Создать';
                document.getElementById('profileDlgName').value = '';
                document.getElementById('profileDlgPort').value = '45000';
                document.getElementById('profileDlgUser').value = '';
                document.getElementById('profileDlgPass').value = '';
            }
            dlg.showModal();
        }

        async function saveProfileDlg() {
            if (profileDlgSaving) return;
            const name = document.getElementById('profileDlgName').value.trim();
            const port = parseInt(document.getElementById('profileDlgPort').value, 10);
            const username = document.getElementById('profileDlgUser').value;
            const password = document.getElementById('profileDlgPass').value;
            if (!port || port < 1 || port > 65535) {
                showToast('Укажите порт от 1 до 65535', 'error'); return;
            }
            if (password && !username) {
                showToast('Для пароля SOCKS5 укажите логин', 'error'); return;
            }
            const body = JSON.stringify({ name, port, username, password });
            const btn = document.getElementById('profileDlgSave');
            profileDlgSaving = true;
            btn.disabled = true;
            btn.textContent = 'Сохранение...';
            try {
                let r;
                if (profileDlgEditId) {
                    r = await api(`/api/local-proxy/profiles/${profileDlgEditId}`, { method: 'PUT', body });
                } else {
                    r = await api('/api/local-proxy', { method: 'POST', body });
                }
                if (!r.ok) {
                    showToast((await r.text()) || 'Ошибка', 'error'); return;
                }
                const data = await r.json().catch(() => ({}));
                document.getElementById('profileDlg').close();
                showToast(profileDlgEditId ? 'Профиль обновлён' : 'Профиль создан', 'success');
                if (data.port_listening === false) {
                    showToast('Указанный вами порт никем не слушается', 'error');
                }
                await loadLocalProxy();
            } finally {
                profileDlgSaving = false;
                btn.disabled = false;
                btn.textContent = profileDlgEditId ? 'Сохранить' : 'Создать';
            }
        }

        async function activateProxy(id) {
            const r = await api(`/api/local-proxy/activate/${id}`, { method: 'POST', body: '{}' });
            if (!r.ok) { showToast((await r.text()) || 'Ошибка активации', 'error'); return; }
            showToast('Профиль активирован', 'success');
            await loadLocalProxy();
        }

        async function deactivateProxy() {
            const r = await api('/api/local-proxy/deactivate', { method: 'POST', body: '{}' });
            if (!r.ok) { showToast((await r.text()) || 'Ошибка', 'error'); return; }
            showToast('Маршрут SOCKS5 отключён', 'success');
            await loadLocalProxy();
        }

        async function deleteProxy(id, name) {
            showConfirm(`Удалить профиль «${name}»?`, `Удаление профиля необратимо.`, 'Удалить', 'btn-danger', async () => {
                const r = await api(`/api/local-proxy/profiles/${id}`, { method: 'DELETE', body: '{}' });
                if (!r.ok) { showToast((await r.text()) || 'Ошибка', 'error'); return; }
                showToast('Профиль удалён', 'success');
                await loadLocalProxy();
            });
        }

        window.clientsData = [];
        window.groupsData = [];
        window.routingRulesData = [];
        window.currentViewingGroupId = null;

        function generateUniqueClientName(existingNames) {
            const set = new Set((existingNames || []).map(n => String(n || '').trim().toLowerCase()));
            for (let i = 0; i < 1000; i++) {
                const candidate = 'Клиент' + Math.floor(100 + Math.random() * 900);
                if (!set.has(candidate.toLowerCase())) {
                    return candidate;
                }
            }
            return 'Клиент' + Math.floor(100 + Math.random() * 900);
        }

        function renderClientCard(x) {
            const isMain = x.name === "Главный пароль";
            const expiryStr = isMain ? "—" : (x.expires ? new Date(x.expires * 1000).toLocaleDateString() : "∞ Бессрочно");
            return `
            <div class="client-card" onclick="openEditClientDlgByPassword('${esc(x.password)}')">
                <div class="client-card-header">
                    <div class="client-card-title">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
                        <span>${esc(x.name || 'Клиент')}</span>
                    </div>
                    <span class="badge ${x.active ? "active" : "inactive"}">${x.active ? "Участник" : "Неактивно"}</span>
                </div>
                <div class="client-card-body">
                    <div class="client-card-row">
                        <span class="client-card-label">IP:</span>
                        <span class="client-card-value">${esc(x.ip || '—')}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Срок действия:</span>
                        <span class="client-card-value">${expiryStr}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Трафик:</span>
                        <span class="client-card-value" style="font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;">↑ ${size(x.up)} / ↓ ${size(x.down)}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Потоки:</span>
                        <span class="client-card-value">${x.active_sessions}</span>
                    </div>
                    ${x.sub_uuid ? `
                    <button class="btn btn-ghost client-sub-btn" onclick="event.stopPropagation(); copyClientSubLink('${x.sub_uuid}', this)" title="Скопировать ссылку подписки">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        <span>Скопировать ссылку подписки</span>
                    </button>
                    ` : ''}
                </div>
            </div>
            `;
        }

        function renderGroupCard(g, st) {
            const stats = st || { count: 0, up: 0, down: 0, active_sessions: 0 };
            return `
            <div class="client-card group-card" onclick="openGroupDlg('${esc(g.id)}')">
                <div class="client-card-header">
                    <div class="client-card-title">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary); flex-shrink: 0;"><circle cx="12" cy="7" r="3.5"></circle><path d="M6 21v-2a4 4 0 0 1 8 0v2"></path><path d="M5.5 10a3 3 0 0 1 0-6"></path><path d="M2 21v-1.5a3.5 3.5 0 0 1 3-3.4"></path><path d="M18.5 4a3 3 0 0 1 0 6"></path><path d="M19 16.1a3.5 3.5 0 0 1 3 3.4V21"></path></svg>
                        <span>${esc(g.name || 'Без названия')}</span>
                    </div>
                    <span class="badge active">Группа</span>
                </div>
                <div class="client-card-body">
                    <div class="client-card-row">
                        <span class="client-card-label">Участники:</span>
                        <span class="client-card-value">${stats.count}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Срок действия:</span>
                        <span class="client-card-value">—</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Трафик:</span>
                        <span class="client-card-value" style="font-family: ui-monospace, SFMono-Regular, 'Cascadia Code', Consolas, monospace;">↑ ${size(stats.up)} / ↓ ${size(stats.down)}</span>
                    </div>
                    <div class="client-card-row">
                        <span class="client-card-label">Потоки:</span>
                        <span class="client-card-value">${stats.active_sessions}</span>
                    </div>
                    ${g.sub_uuid ? `
                    <button class="btn btn-ghost" style="width: 100%; margin-top: 8px; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 12px; padding: 7px 10px; border: 1px solid var(--primary); color: var(--primary); border-radius: 8px; font-weight: 500;" onclick="event.stopPropagation(); copyGroupSubLink('${g.sub_uuid}', this)" title="Скопировать ссылку подписки">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        <span>Скопировать ссылку подписки</span>
                    </button>
                    ` : ''}
                </div>
            </div>
            `;
        }

        function calcMatchScore(text, query) {
            if (!text || !query) return 0;
            const t = String(text).trim().toLowerCase();
            const q = String(query).trim().toLowerCase();
            if (!t || !q) return 0;
            if (t === q) return 1000;
            if (t.startsWith(q)) return 500 + Math.max(0, 100 - t.length);
            const words = t.split(/[\s,._\-#№]+|(?<=[^\d\s])(?=\d)|(?<=\d)(?=[^\d\s])/);
            for (const w of words) {
                if (w === q) return 450 + Math.max(0, 100 - w.length);
                if (w.startsWith(q)) return 400 + Math.max(0, 100 - w.length);
            }
            const idx = t.indexOf(q);
            if (idx !== -1) return 200 + Math.max(0, 100 - idx);
            return 0;
        }

        function handleMainSearch(val) {
            const clearBtn = document.getElementById('mainSearchClearBtn');
            if (clearBtn) clearBtn.style.display = val.trim() ? 'block' : 'none';
            renderMainClientsCards();
        }

        function clearMainSearch() {
            const input = document.getElementById('mainSearchInput');
            if (input) {
                input.value = '';
                const clearBtn = document.getElementById('mainSearchClearBtn');
                if (clearBtn) clearBtn.style.display = 'none';
                renderMainClientsCards();
                input.focus();
            }
        }

        function renderMainClientsCards() {
            const container = document.getElementById('clientsCards');
            if (!container) return;

            const groupStats = {};
            for (const g of (window.groupsData || [])) {
                groupStats[g.id] = { count: 0, up: 0, down: 0, active_sessions: 0, clients: [] };
            }
            const standaloneClients = [];
            for (const c of (window.clientsData || [])) {
                if (c.group_id && groupStats[c.group_id]) {
                    groupStats[c.group_id].count++;
                    groupStats[c.group_id].up += (c.up || 0);
                    groupStats[c.group_id].down += (c.down || 0);
                    groupStats[c.group_id].active_sessions += (c.active_sessions || 0);
                    groupStats[c.group_id].clients.push(c);
                } else {
                    standaloneClients.push(c);
                }
            }

            const query = (document.getElementById('mainSearchInput')?.value || '').trim();

            if (!query) {
                const groupsHtml = (window.groupsData || []).map(g => renderGroupCard(g, groupStats[g.id])).join('');
                const standaloneHtml = standaloneClients.map(x => renderClientCard(x)).join('');
                container.innerHTML = groupsHtml + standaloneHtml;
                if (!groupsHtml && !standaloneHtml) {
                    container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1;">Нет созданных доступов.</div>`;
                }
                return;
            }

            const results = [];

            for (const g of (window.groupsData || [])) {
                let bestScore = calcMatchScore(g.name, query);
                const st = groupStats[g.id] || { count: 0, clients: [] };
                for (const c of st.clients) {
                    const sc = calcMatchScore(c.name, query);
                    if (sc > bestScore) bestScore = sc;
                }
                if (bestScore > 0) {
                    results.push({
                        type: 'group',
                        score: bestScore,
                        html: renderGroupCard(g, st)
                    });
                }
            }

            for (const c of standaloneClients) {
                const sc = calcMatchScore(c.name, query);
                if (sc > 0) {
                    results.push({
                        type: 'client',
                        score: sc,
                        html: renderClientCard(c)
                    });
                }
            }

            if (results.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 32px 16px;">По запросу «<b>${esc(query)}</b>» ничего не найдено.</div>`;
                return;
            }

            results.sort((a, b) => b.score - a.score);
            container.innerHTML = results.map(r => r.html).join('');
        }

        let clientsInFlight = false;
        async function loadClients() {
            if (clientsInFlight) return;
            clientsInFlight = true;
            try {
                const [rC, rG] = await Promise.all([api("/api/clients"), api("/api/groups")]);
                if (!rC.ok) return;
                const a = await rC.json() || [];
                window.clientsData = a;
                if (a.length > 0 && a[0].peer_port) {
                    window.serverDefaultPeerPort = a[0].peer_port;
                }
                if (rG && rG.ok) {
                    window.groupsData = await rG.json() || [];
                }

                renderMainClientsCards();

                if (window.currentViewingGroupId) {
                    refreshCurrentGroupDlgView();
                }
            } catch (e) {
            } finally {
                clientsInFlight = false;
            }
        }

        function openEditClientDlgByIndex(idx) {
            if (window.clientsData && window.clientsData[idx]) {
                openEditClientDlg(window.clientsData[idx]);
            }
        }

        function openEditClientDlgByPassword(password) {
            const c = (window.clientsData || []).find(x => x.password === password);
            if (c) openEditClientDlg(c);
        }

        function openNewGroupDlg() {
            document.getElementById('groupNameInput').value = 'Группа' + Math.floor(100 + Math.random() * 900);
            document.getElementById('newGroupDlg').showModal();
        }

        async function createGroup() {
            const name = document.getElementById('groupNameInput').value.trim();
            if (!name) { showToast("Укажите имя группы", "error"); return; }
            const btn = document.getElementById('createGroupBtn');
            btn.disabled = true;
            try {
                let r = await api("/api/groups", {
                    method: "POST",
                    body: JSON.stringify({ name: name })
                });
                if (r.ok) {
                    showToast("Группа создана");
                    document.getElementById('newGroupDlg').close();
                    loadClients();
                } else {
                    showToast(await r.text(), "error");
                }
            } finally {
                btn.disabled = false;
            }
        }

        let isGroupInlineEditing = false;
        function exitGroupInlineEdit() {
            isGroupInlineEditing = false;
            const titleEl = document.getElementById('groupDlgTitle');
            const inputEl = document.getElementById('groupDlgTitleInput');
            const btnEl = document.getElementById('groupDlgEditBtn');
            if (titleEl) titleEl.style.display = '';
            if (inputEl) inputEl.style.display = 'none';
            if (btnEl) {
                btnEl.title = "Переименовать";
                btnEl.style.color = "var(--text-muted)";
                btnEl.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>`;
            }
        }

        async function toggleGroupInlineEdit() {
            const titleEl = document.getElementById('groupDlgTitle');
            const inputEl = document.getElementById('groupDlgTitleInput');
            const btnEl = document.getElementById('groupDlgEditBtn');
            if (!titleEl || !inputEl || !btnEl) return;

            if (!isGroupInlineEditing) {
                isGroupInlineEditing = true;
                inputEl.value = titleEl.textContent.trim();
                titleEl.style.display = 'none';
                inputEl.style.display = 'inline-block';
                inputEl.focus();
                inputEl.select();
                btnEl.title = "Сохранить";
                btnEl.style.color = "var(--primary)";
                btnEl.innerHTML = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
            } else {
                const newName = inputEl.value.trim();
                if (!newName) {
                    showToast("Имя группы не может быть пустым", "error");
                    return;
                }
                const groupId = window.currentViewingGroupId;
                if (!groupId) return;
                btnEl.disabled = true;
                try {
                    let r = await api("/api/groups/" + encodeURIComponent(groupId), {
                        method: "PUT",
                        body: JSON.stringify({ name: newName })
                    });
                    if (r.ok) {
                        showToast("Группа переименована");
                        titleEl.textContent = newName;
                        exitGroupInlineEdit();
                        await loadClients();
                        if (window.currentViewingGroupId) refreshCurrentGroupDlgView();
                    } else {
                        showToast(await r.text(), "error");
                    }
                } finally {
                    btnEl.disabled = false;
                }
            }
        }

        function handleGroupSearch(val) {
            const clearBtn = document.getElementById('groupSearchClearBtn');
            if (clearBtn) clearBtn.style.display = val.trim() ? 'block' : 'none';
            refreshCurrentGroupDlgView();
        }

        function clearGroupSearch() {
            const input = document.getElementById('groupSearchInput');
            if (input) {
                input.value = '';
                const clearBtn = document.getElementById('groupSearchClearBtn');
                if (clearBtn) clearBtn.style.display = 'none';
                refreshCurrentGroupDlgView();
                input.focus();
            }
        }

        function refreshCurrentGroupDlgView() {
            if (!window.currentViewingGroupId) return;
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            if (!group) return;
            if (!isGroupInlineEditing) {
                document.getElementById('groupDlgTitle').textContent = group.name || 'Группа';
            }
            const adminBtn = document.getElementById('groupAdminLinkBtn');
            const deleteAdminBtn = document.getElementById('groupAdminDeleteBtn');
            if (adminBtn) {
                if (group.admin_uuid) {
                    adminBtn.textContent = 'Скопировать ссылку админа';
                    if (deleteAdminBtn) deleteAdminBtn.style.display = 'inline-flex';
                } else {
                    adminBtn.textContent = 'Создать админа';
                    if (deleteAdminBtn) deleteAdminBtn.style.display = 'none';
                }
            }

            const groupClients = (window.clientsData || []).filter(c => c.group_id === window.currentViewingGroupId);
            let sumUp = 0, sumDown = 0, sumSessions = 0;
            for (const c of groupClients) {
                sumUp += (c.up || 0);
                sumDown += (c.down || 0);
                sumSessions += (c.active_sessions || 0);
            }
            const statsEl = document.getElementById('groupDlgStatsSummary');
            if (statsEl) {
                statsEl.textContent = `Участники: ${groupClients.length} · Трафик: ↑ ${size(sumUp)} / ↓ ${size(sumDown)} · Потоки: ${sumSessions}`;
            }

            const container = document.getElementById('groupClientsCards');
            if (!container) return;

            if (groupClients.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 24px;">В группе пока нет клиентов. Нажмите «Создать пароль», чтобы добавить.</div>`;
                return;
            }

            const query = (document.getElementById('groupSearchInput')?.value || '').trim();
            if (!query) {
                container.innerHTML = groupClients.map(c => renderClientCard(c)).join('');
                return;
            }

            const matched = [];
            for (const c of groupClients) {
                const sc = calcMatchScore(c.name, query);
                if (sc > 0) {
                    matched.push({ score: sc, html: renderClientCard(c) });
                }
            }

            if (matched.length === 0) {
                container.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; padding: 24px;">По запросу «<b>${esc(query)}</b>» в группе ничего не найдено.</div>`;
                return;
            }

            matched.sort((a, b) => b.score - a.score);
            container.innerHTML = matched.map(m => m.html).join('');
        }

        function openGroupDlg(groupId) {
            window.currentViewingGroupId = groupId;
            exitGroupInlineEdit();
            const gSearch = document.getElementById('groupSearchInput');
            if (gSearch) gSearch.value = '';
            const gClear = document.getElementById('groupSearchClearBtn');
            if (gClear) gClear.style.display = 'none';
            refreshCurrentGroupDlgView();
            const inputEl = document.getElementById('groupDlgTitleInput');
            if (inputEl) {
                inputEl.onkeydown = (e) => {
                    if (e.key === 'Enter') toggleGroupInlineEdit();
                    else if (e.key === 'Escape') exitGroupInlineEdit();
                };
            }
            document.getElementById('groupDlg').showModal();
        }

        function createClientInCurrentGroup() {
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            openNewClientDlg(window.currentViewingGroupId, group ? group.name : '');
        }

        async function copyClientSubLink(subUuid, btn) {
            if (!subUuid) {
                showToast("У клиента нет ссылки подписки", "error");
                return;
            }
            const url = window.location.origin + '/' + subUuid + '/sub';
            if (await copyToClipboard(url)) {
                if (btn) {
                    btn.style.color = '#3b82f6';
                    btn.style.borderColor = '#3b82f6';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка подписки скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function copyGroupSubLink(subUuid, btn) {
            if (!subUuid) {
                showToast("У группы нет ссылки подписки", "error");
                return;
            }
            const url = window.location.origin + '/' + subUuid + '/sub-group';
            if (await copyToClipboard(url)) {
                if (btn) {
                    btn.style.color = '#3b82f6';
                    btn.style.borderColor = '#3b82f6';
                    setTimeout(() => { btn.style.color = ''; btn.style.borderColor = ''; }, 1500);
                }
                showToast("Ссылка подписки группы скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function createOrCopyGroupAdminLink() {
            if (!window.currentViewingGroupId) return;
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            if (!group) return;
            if (group.admin_uuid) {
                const url = window.location.origin + '/' + group.admin_uuid + '/admin';
                await copyToClipboard(url);
                showToast("Ссылка админа скопирована");
                return;
            }
            try {
                let r = await api("/api/groups/" + encodeURIComponent(window.currentViewingGroupId) + "/admin-link", {
                    method: "POST"
                });
                if (r.ok) {
                    let res = await r.json();
                    group.admin_uuid = res.admin_uuid;
                    refreshCurrentGroupDlgView();
                    const url = window.location.origin + '/' + res.admin_uuid + '/admin';
                    await copyToClipboard(url);
                    showToast("Админ создан! Ссылка скопирована");
                } else {
                    showToast("Ошибка создания ссылки админа", "error");
                }
            } catch (e) {
                showToast("Ошибка сети", "error");
            }
        }

        async function regenerateGroupAdminLink() {
            if (!window.currentViewingGroupId) return;
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            if (!group) return;
            if (!confirm("Пересоздать ссылку админа группы? Старая ссылка перестанет работать.")) return;
            try {
                let r = await api("/api/groups/" + encodeURIComponent(window.currentViewingGroupId) + "/admin-link/regenerate", {
                    method: "POST"
                });
                if (r.ok) {
                    let res = await r.json();
                    group.admin_uuid = res.admin_uuid;
                    refreshCurrentGroupDlgView();
                    const url = window.location.origin + '/' + res.admin_uuid + '/admin';
                    await copyToClipboard(url);
                    showToast("Новая ссылка админа создана и скопирована");
                } else {
                    showToast("Ошибка пересоздания ссылки", "error");
                }
            } catch (e) {
                showToast("Ошибка сети", "error");
            }
        }

        async function deleteGroupAdminLink() {
            if (!window.currentViewingGroupId) return;
            const group = (window.groupsData || []).find(g => g.id === window.currentViewingGroupId);
            if (!group) return;
            if (!confirm("Удалить ссылку админа группы? Ссылка перестанет работать.")) return;
            try {
                let r = await api("/api/groups/" + encodeURIComponent(window.currentViewingGroupId) + "/admin-link", {
                    method: "DELETE"
                });
                if (r.ok) {
                    group.admin_uuid = "";
                    refreshCurrentGroupDlgView();
                    showToast("Ссылка админа группы удалена");
                } else {
                    showToast("Ошибка удаления ссылки", "error");
                }
            } catch (e) {
                showToast("Ошибка сети", "error");
            }
        }

        async function deleteCurrentGroup() {
            if (!window.currentViewingGroupId) return;
            if (!confirm("Удалить группу? Все ключи внутри этой группы также будут безвозвратно удалены.")) return;
            let r = await api("/api/groups/" + encodeURIComponent(window.currentViewingGroupId), {
                method: "DELETE"
            });
            if (r.ok) {
                showToast("Группа и все её ключи удалены");
                document.getElementById('groupDlg').close();
                window.currentViewingGroupId = null;
                loadClients();
            } else {
                showToast(await r.text(), "error");
            }
        }

        async function openRoutingRulesDlg() {
            await loadRoutingRules();
            document.getElementById('routingRulesDlg').showModal();
        }

        async function loadRoutingRules() {
            let r = await api("/api/routing-rules");
            if (!r.ok) { showToast("Не удалось загрузить правила", "error"); return; }
            window.routingRulesData = await r.json() || [];
            renderRoutingRulesList();
        }

        function renderRoutingRulesList() {
            const container = document.getElementById('routingRulesList');
            if (!window.routingRulesData || window.routingRulesData.length === 0) {
                container.innerHTML = `<div class="empty-state" style="padding: 24px;">Нет созданных правил маршрутизации.</div>`;
                return;
            }
            container.innerHTML = window.routingRulesData.map(rule => {
                const isWhite = rule.mode === 'whitelist';
                const modeText = isWhite ? 'Белый список' : 'Чёрный список';
                const modeBadge = `<span class="badge ${isWhite ? 'active' : 'inactive'}" style="font-size: 11px;">${modeText}</span>`;
                const appsCount = (rule.packages || []).length;
                const domainsCount = (rule.domains || []).length;
                const targetGroups = (rule.target_group_ids || []).length;
                const targetClients = (rule.target_client_passwords || []).length;
                return `
                <div class="rule-card">
                    <div class="rule-card-header">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <div class="rule-card-title">${esc(rule.name || 'Без имени')}</div>
                            ${modeBadge}
                        </div>
                        <div style="display: flex; gap: 6px;">
                            <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px; height: 30px;" onclick="openEditRoutingRuleDlg('${esc(rule.id)}')">Изменить</button>
                            <button class="btn btn-ghost" style="padding: 4px 10px; font-size: 12px; height: 30px; color: #ef4444;" onclick="deleteRoutingRule('${esc(rule.id)}')">Удалить</button>
                        </div>
                    </div>
                    <div style="display: flex; flex-wrap: wrap; gap: 6px; font-size: 12px;">
                        <span class="rule-chip">Приложений: ${appsCount}</span>
                        <span class="rule-chip">Доменов: ${domainsCount}</span>
                        <span class="rule-chip">Адресов: ${(rule.ips || []).length}</span>
                        <span class="rule-chip">Шаблоны: ${rule.template_domains ? 'Вкл' : 'Выкл'}</span>
                        <span class="rule-chip">Целей: ${targetGroups} групп, ${targetClients} клиентов</span>
                    </div>
                </div>
                `;
            }).join('');
        }

        function onRuleDomainsModeChange() {
            const mode = document.getElementById('ruleDomainsMode').value;
            const row = document.getElementById('ruleTemplateDomainsRow');
            if (row) {
                row.style.display = (mode === 'whitelist') ? 'none' : 'flex';
            }
        }

        function selectAllRuleTargets(selectAll) {
            const cbs = document.querySelectorAll('#ruleTargetsContainer input[type="checkbox"]');
            cbs.forEach(cb => cb.checked = selectAll);
        }

        function toggleRuleTargetItem(e, item) {
            if (e.target.tagName === 'INPUT') return;
            const cb = item.querySelector('input[type="checkbox"]');
            if (cb) {
                cb.checked = !cb.checked;
            }
        }

        let isDragSelecting = false;
        let dragSelectState = false;
        let dragStartItem = null;

        function bindTargetDragSelection(container) {
            if (container._dragBound) return;
            container._dragBound = true;

            container.addEventListener('mousedown', (e) => {
                const item = e.target.closest('.rule-target-item');
                if (!item) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (!cb) return;
                isDragSelecting = true;
                dragStartItem = item;
                dragSelectState = !cb.checked;
            });

            container.addEventListener('mouseover', (e) => {
                if (!isDragSelecting) return;
                const item = e.target.closest('.rule-target-item');
                if (!item || item === dragStartItem) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (cb && cb.checked !== dragSelectState) {
                    cb.checked = dragSelectState;
                }
            });

            container.addEventListener('touchstart', (e) => {
                const item = e.target.closest('.rule-target-item');
                if (!item) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (!cb) return;
                isDragSelecting = true;
                dragStartItem = item;
                dragSelectState = !cb.checked;
            }, { passive: true });

            container.addEventListener('touchmove', (e) => {
                if (!isDragSelecting) return;
                const touch = e.touches[0];
                if (!touch) return;
                const targetEl = document.elementFromPoint(touch.clientX, touch.clientY);
                const item = targetEl ? targetEl.closest('.rule-target-item') : null;
                if (!item || item === dragStartItem) return;
                const cb = item.querySelector('input[type="checkbox"]');
                if (cb && cb.checked !== dragSelectState) {
                    cb.checked = dragSelectState;
                }
            }, { passive: true });
        }

        window.addEventListener('mouseup', () => { isDragSelecting = false; dragStartItem = null; });
        window.addEventListener('touchend', () => { isDragSelecting = false; dragStartItem = null; });

        function openEditRoutingRuleDlg(ruleId) {
            const rule = ruleId ? (window.routingRulesData || []).find(r => r.id === ruleId) : null;
            document.getElementById('ruleId').value = rule ? rule.id : '';
            document.getElementById('editRoutingRuleTitle').textContent = rule ? 'Редактирование правила' : 'Создание правила';

            const randomNum = Math.floor(100 + Math.random() * 900);
            const defaultName = rule ? (rule.name || '') : `Правило${randomNum}`;
            document.getElementById('ruleName').value = defaultName;
            document.getElementById('ruleMode').value = rule ? (rule.mode || 'blacklist') : 'blacklist';
            document.getElementById('rulePackages').value = rule && rule.packages ? rule.packages.join('\n') : '';
            document.getElementById('ruleDomainsMode').value = rule ? (rule.domains_mode || (rule.domains_is_whitelist ? 'whitelist' : 'blacklist')) : 'blacklist';
            onRuleDomainsModeChange();
            document.getElementById('ruleDomains').value = rule && (rule.domains || rule.direct_domains) ? (rule.domains || rule.direct_domains).join('\n') : '';
            document.getElementById('ruleTemplateDomains').checked = rule ? Boolean(rule.template_domains) : false;
            document.getElementById('ruleIpMode').value = rule ? (rule.ip_mode || (rule.ip_is_whitelist ? 'whitelist' : 'blacklist')) : 'blacklist';
            document.getElementById('ruleIps').value = rule && rule.ips ? rule.ips.join('\n') : '';
            document.getElementById('ruleDeleteBtn').style.display = rule ? 'inline-block' : 'none';

            const targetsCont = document.getElementById('ruleTargetsContainer');
            let targetsHtml = '';

            const groups = window.groupsData || [];
            if (groups.length > 0) {
                targetsHtml += '<div class="rule-target-section-title">Группы:</div>';
                targetsHtml += groups.map(g => {
                    const checked = rule && rule.target_group_ids && rule.target_group_ids.includes(g.id);
                    return `
                    <div class="rule-target-item" onclick="toggleRuleTargetItem(event, this)">
                        <input type="checkbox" class="rule-target-group-cb" value="${esc(g.id)}" ${checked ? 'checked' : ''}>
                        <div class="rule-target-info">
                            <span class="rule-target-name">${esc(g.name || 'Без названия')}</span>
                            <span class="rule-target-badge">Группа</span>
                        </div>
                    </div>
                    `;
                }).join('');
            }

            const clients = (window.clientsData || []).filter(c => !c.group_id);
            if (clients.length > 0) {
                targetsHtml += '<div class="rule-target-section-title" style="margin-top: 8px;">Отдельные клиенты:</div>';
                targetsHtml += clients.map(c => {
                    const checked = rule && rule.target_client_passwords && rule.target_client_passwords.includes(c.password);
                    return `
                    <div class="rule-target-item" onclick="toggleRuleTargetItem(event, this)">
                        <input type="checkbox" class="rule-target-client-cb" value="${esc(c.password)}" ${checked ? 'checked' : ''}>
                        <div class="rule-target-info">
                            <span class="rule-target-name">${esc(c.name || 'Клиент')}</span>
                            <span class="rule-target-meta">${esc(c.password)}</span>
                        </div>
                    </div>
                    `;
                }).join('');
            }

            targetsCont.innerHTML = targetsHtml || '<div style="color: var(--text-muted); font-size: 12px; padding: 12px; text-align: center;">Нет доступных групп или клиентов</div>';
            bindTargetDragSelection(targetsCont);
            document.getElementById('editRoutingRuleDlg').showModal();
        }

        async function saveRoutingRule() {
            const id = document.getElementById('ruleId').value;
            let name = document.getElementById('ruleName').value.trim();
            if (!name) name = "Правило";
            const mode = document.getElementById('ruleMode').value;

            const packages = document.getElementById('rulePackages').value
                .split(/[\n,]/)
                .map(s => s.trim())
                .filter(Boolean);

            const rawDomains = document.getElementById('ruleDomains').value
                .split(/[\n,]/);
            const domains = [];
            const seen = new Set();
            let hadGarbage = false;
            for (let raw of rawDomains) {
                let s = raw.trim();
                if (!s) continue;
                s = s.replace(/^[a-zA-Z]+:\/\//, '');
                s = s.split(/[/?#]/)[0];
                s = s.replace(/:\d+$/, '');
                s = s.replace(/^["'\[\(]+|["'\]\)]+$/g, '');
                s = s.replace(/\.+$/, '').trim();
                if (!s) { hadGarbage = true; continue; }
                const isWildcardDomain = /^(\*\.)?([a-zA-Z0-9\u0400-\u04FF]([a-zA-Z0-9\u0400-\u04FF\-]{0,61}[a-zA-Z0-9\u0400-\u04FF])?\.)+[a-zA-Z\u0400-\u04FF]{2,}$/i.test(s);
                const isIpv4 = /^(\d{1,3}\.){3}\d{1,3}$/.test(s);
                const isIpv6 = /^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$/.test(s);
                const isSimple = /^[a-zA-Z0-9\u0400-\u04FF\-_\.]+$/.test(s) && (s.includes('.') || s === 'localhost');
                if (isWildcardDomain || isIpv4 || isIpv6 || isSimple) {
                    const norm = s.toLowerCase();
                    if (!seen.has(norm)) {
                        seen.add(norm);
                        domains.push(norm);
                    }
                } else {
                    hadGarbage = true;
                }
            }
            document.getElementById('ruleDomains').value = domains.join('\n');
            if (hadGarbage) {
                const domainsEl = document.getElementById('ruleDomains');
                if (domainsEl) {
                    domainsEl.style.borderColor = '#ef4444';
                    setTimeout(() => { if (domainsEl) domainsEl.style.borderColor = ''; }, 2000);
                }
            }

            const domains_mode = document.getElementById('ruleDomainsMode').value;
            const template_domains = (domains_mode === 'whitelist') ? false : document.getElementById('ruleTemplateDomains').checked;
            const ip_mode = document.getElementById('ruleIpMode').value;
            const rawIps = document.getElementById('ruleIps').value.split(/[\n,]/);
            const ips = [];
            const seenIps = new Set();
            for (let raw of rawIps) {
                let s = raw.trim();
                if (!s) continue;
                const isCidr = /^(\d{1,3}\.){3}\d{1,3}\/(3[0-2]|[12]?\d)$/.test(s);
                const isSingle = /^(\d{1,3}\.){3}\d{1,3}$/.test(s);
                const isRange = /^(\d{1,3}\.){3}\d{1,3}\s*-\s*(\d{1,3}\.){3}\d{1,3}$/.test(s);
                if (isCidr || isSingle || isRange) {
                    const norm = s.replace(/\s+/g, '');
                    if (!seenIps.has(norm)) {
                        seenIps.add(norm);
                        ips.push(norm);
                    }
                }
            }
            document.getElementById('ruleIps').value = ips.join('\n');

            const target_group_ids = Array.from(document.querySelectorAll('.rule-target-group-cb:checked')).map(cb => cb.value);
            const target_client_passwords = Array.from(document.querySelectorAll('.rule-target-client-cb:checked')).map(cb => cb.value);

            const btn = document.getElementById('ruleSaveBtn');
            btn.disabled = true;
            try {
                const payload = {
                    name,
                    mode,
                    is_whitelist: mode === 'whitelist',
                    packages,
                    domains_mode,
                    domains_is_whitelist: domains_mode === 'whitelist',
                    direct_domains: domains,
                    template_domains,
                    ip_mode,
                    ip_is_whitelist: ip_mode === 'whitelist',
                    ips,
                    assigned_groups: target_group_ids,
                    assigned_clients: target_client_passwords
                };
                let r;
                if (id) {
                    r = await api("/api/routing-rules/" + encodeURIComponent(id), {
                        method: "PUT",
                        body: JSON.stringify(payload)
                    });
                } else {
                    r = await api("/api/routing-rules", {
                        method: "POST",
                        body: JSON.stringify(payload)
                    });
                }
                if (r.ok) {
                    showToast("Правило сохранено");
                    document.getElementById('editRoutingRuleDlg').close();
                    await loadRoutingRules();
                    loadClients();
                } else {
                    showToast(await r.text(), "error");
                }
            } finally {
                btn.disabled = false;
            }
        }

        function updateApiDocsPaths() {
            const basePath = window.location.pathname.replace(/\/$/, '');
            const baseUrl = window.location.origin + (basePath || '');
            const baseEl = document.getElementById('apiBaseUrlDisplay');
            if (baseEl) baseEl.textContent = baseUrl;
            document.querySelectorAll('#apiDocsDlg .api-path').forEach(el => {
                if (!el.dataset.orig) el.dataset.orig = el.textContent.trim();
                const orig = el.dataset.orig;
                el.textContent = orig.split(' и ').map(p => {
                    const cleanP = p.trim();
                    if (cleanP.startsWith('/{') || cleanP.startsWith('/api/sub') || cleanP.startsWith('/api/group-admin')) {
                        return cleanP;
                    }
                    return (basePath || '') + cleanP;
                }).join(' и ');
            });
            document.querySelectorAll('#apiDocsDlg code').forEach(el => {
                if (!el.dataset.orig) el.dataset.orig = el.textContent;
                let text = el.dataset.orig;
                if (text.includes('/api/') && !text.includes('/api/sub') && !text.includes('/api/group-admin')) {
                    text = text.replaceAll('/api/', (basePath || '') + '/api/');
                }
                el.textContent = text;
            });
        }

        function openApiDocsDlg() {
            switchApiDocsTab('clients');
            updateApiDocsPaths();
            document.getElementById('apiDocsDlg').showModal();
        }

        function switchApiDocsTab(tab) {
            const tabs = ['clients', 'groups', 'subs', 'routing', 'system'];
            tabs.forEach(t => {
                const btn = document.getElementById('btnApiTab' + t.charAt(0).toUpperCase() + t.slice(1));
                const pane = document.getElementById('apiPane' + t.charAt(0).toUpperCase() + t.slice(1));
                if (btn) {
                    if (t === tab) {
                        btn.classList.add('active');
                    } else {
                        btn.classList.remove('active');
                    }
                }
                if (pane) {
                    pane.style.display = (t === tab) ? 'flex' : 'none';
                }
            });
        }

        function openImportExportDlg() {
            switchImportExportTab('export');
            const st = document.getElementById('ieImportStatus');
            if (st) { st.style.display = 'none'; st.textContent = ''; }
            document.getElementById('importExportDlg').showModal();
        }

        function switchImportExportTab(tab) {
            const btnExp = document.getElementById('btnTabExport');
            const btnImp = document.getElementById('btnTabImport');
            const paneExp = document.getElementById('iePaneExport');
            const paneImp = document.getElementById('iePaneImport');
            if (tab === 'export') {
                btnExp.className = 'btn btn-primary';
                btnImp.className = 'btn btn-ghost';
                btnImp.style.border = '1px solid var(--border)';
                paneExp.style.display = 'flex';
                paneImp.style.display = 'none';
            } else {
                btnImp.className = 'btn btn-primary';
                btnExp.className = 'btn btn-ghost';
                btnExp.style.border = '1px solid var(--border)';
                paneExp.style.display = 'none';
                paneImp.style.display = 'flex';
            }
        }

        async function fetchExportData() {
            const r = await api('/api/export');
            if (!r.ok) throw new Error('Ошибка экспорта: ' + r.statusText);
            const data = await r.json();
            const incClients = document.getElementById('ieOptClients').checked;
            const incSettings = document.getElementById('ieOptSettings').checked;
            const filtered = {
                version: data.version,
                exported_at: data.exported_at
            };
            if (incClients) {
                filtered.clients = data.clients;
                filtered.groups = data.groups;
                filtered.routing_rules = data.routing_rules;
            }
            if (incSettings) {
                filtered.main_password = data.main_password;
                filtered.dns = data.dns;
                filtered.dns_provider = data.dns_provider;
                filtered.auto_restart_interval_hours = data.auto_restart_interval_hours;
                filtered.vk_access_token = data.vk_access_token;
                filtered.vk_hashes = data.vk_hashes;
            }
            return filtered;
        }

        async function executeExportDownload() {
            try {
                const data = await fetchExportData();
                const jsonStr = JSON.stringify(data, null, 2);
                const hostIp = window.location.hostname || 'server';
                const safeIp = hostIp.replace(/[^a-zA-Z0-9]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
                const filename = (safeIp || 'server') + '-config.json';
                const blob = new Blob([jsonStr], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            } catch (e) {
                alert(e.message || 'Ошибка скачивания конфигурации');
            }
        }

        async function executeExportCopy() {
            try {
                const data = await fetchExportData();
                const jsonStr = JSON.stringify(data, null, 2);
                await navigator.clipboard.writeText(jsonStr);
                alert('Конфигурация скопирована в буфер обмена ✓');
            } catch (e) {
                alert(e.message || 'Ошибка копирования');
            }
        }

        function handleImportFile(event) {
            const file = event.target.files && event.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = async function(e) {
                await processImportJson(e.target.result);
            };
            reader.readAsText(file);
            event.target.value = '';
        }

        async function executeImportFromClipboard() {
            const statusEl = document.getElementById('ieImportStatus');
            statusEl.style.display = 'block';
            statusEl.style.color = 'var(--text-muted)';
            statusEl.textContent = 'Чтение буфера...';
            try {
                const text = await navigator.clipboard.readText();
                if (!text || !text.trim()) {
                    statusEl.style.color = '#ef4444';
                    statusEl.textContent = 'Буфер обмена пуст';
                    return;
                }
                await processImportJson(text.trim());
            } catch (e) {
                statusEl.style.color = '#ef4444';
                statusEl.textContent = 'Ошибка чтения буфера: ' + (e.message || e);
            }
        }

        async function processImportJson(text) {
            const statusEl = document.getElementById('ieImportStatus');
            statusEl.style.display = 'block';
            let parsed;
            try {
                parsed = JSON.parse(text);
            } catch (e) {
                statusEl.style.color = '#ef4444';
                statusEl.textContent = 'Ошибка: невалидный JSON';
                return;
            }
            const incClients = document.getElementById('ieOptClients').checked;
            const incSettings = document.getElementById('ieOptSettings').checked;
            statusEl.style.color = 'var(--text-muted)';
            statusEl.textContent = 'Импортирование...';
            try {
                const r = await api('/api/import', {
                    method: 'POST',
                    body: JSON.stringify({
                        import_clients: incClients,
                        import_settings: incSettings,
                        config: parsed
                    })
                });
                if (!r.ok) {
                    const err = await r.text();
                    throw new Error(err || r.statusText);
                }
                const res = await r.json();
                statusEl.style.color = '#10b981';
                statusEl.textContent = 'Успешно! Добавлено новых клиентов: ' + (res.added_clients || 0) + ' ✓';
                if (typeof loadClients === 'function') loadClients();
                if (typeof loadSettings === 'function') loadSettings();
                setTimeout(() => {
                    document.getElementById('importExportDlg').close();
                }, 1500);
            } catch (e) {
                statusEl.style.color = '#ef4444';
                statusEl.textContent = 'Ошибка: ' + (e.message || 'Не удалось импортировать');
            }
        }

        let templateDomainsCache = null;
        async function openTemplateDomainsDlg() {
            const listEl = document.getElementById('templateDomainsList');
            const countEl = document.getElementById('templateDomainsCount');
            const filterEl = document.getElementById('templateDomainsFilter');
            if (filterEl) filterEl.value = '';
            document.getElementById('templateDomainsDlg').showModal();
            if (!templateDomainsCache) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 20px;">Загрузка...</div>';
                try {
                    const r = await api('/api/template-domains');
                    if (r.ok) {
                        const txt = await r.text();
                        templateDomainsCache = txt.split('\n')
                            .map(s => s.trim())
                            .filter(s => s && !s.startsWith('#'));
                    } else {
                        templateDomainsCache = [];
                    }
                } catch(e) {
                    templateDomainsCache = [];
                }
            }
            if (countEl) countEl.textContent = templateDomainsCache.length;
            renderTemplateDomainsList(templateDomainsCache);
        }

        function renderTemplateDomainsList(list) {
            const listEl = document.getElementById('templateDomainsList');
            if (!listEl) return;
            if (!list || list.length === 0) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 10px;">Ничего не найдено</div>';
                return;
            }
            listEl.innerHTML = list.map(d => `<div style="padding: 2px 0;">${esc(d)}</div>`).join('');
        }

        function filterTemplateDomains(query) {
            if (!templateDomainsCache) return;
            const q = query.trim().toLowerCase();
            if (!q) {
                renderTemplateDomainsList(templateDomainsCache);
                return;
            }
            const filtered = templateDomainsCache.filter(d => d.toLowerCase().includes(q));
            renderTemplateDomainsList(filtered);
        }

        async function deleteRoutingRule(ruleId) {
            if (!confirm("Удалить правило маршрутизации?")) return;
            let r = await api("/api/routing-rules/" + encodeURIComponent(ruleId), { method: "DELETE" });
            if (r.ok) {
                showToast("Правило удалено");
                await loadRoutingRules();
                loadClients();
            } else {
                showToast(await r.text(), "error");
            }
        }

        async function deleteCurrentRoutingRule() {
            const id = document.getElementById('ruleId').value;
            if (id) {
                document.getElementById('editRoutingRuleDlg').close();
                await deleteRoutingRule(id);
            }
        }

        function openNewClientDlg(groupId = null, groupName = null) {
            const targetGid = groupId || '';
            const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid);
            document.getElementById('cname').value = generateUniqueClientName(scopeClients.map(c => c.name));
            document.getElementById('days').value = '30';
            const workersInput = document.getElementById('new_workers');
            if (workersInput) workersInput.value = '0';
            const groupNotice = document.getElementById('newDlgGroupNotice');
            const groupNameSpan = document.getElementById('newDlgGroupName');
            const groupIdInput = document.getElementById('newDlgGroupId');
            if (groupId) {
                groupNotice.style.display = 'block';
                groupNameSpan.textContent = groupName || groupId;
                groupIdInput.value = groupId;
            } else {
                groupNotice.style.display = 'none';
                groupNameSpan.textContent = '';
                groupIdInput.value = '';
            }
            document.getElementById('newDlg').showModal();
        }

        async function createClient() {
            const btn = document.getElementById('createBtn');
            btn.textContent = 'Создание...'; btn.disabled = true;

            try {
                const targetGid = document.getElementById('newDlgGroupId').value || "";
                let name = document.getElementById('cname').value.trim();
                if (!name) {
                    const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid);
                    name = generateUniqueClientName(scopeClients.map(c => c.name));
                }
                const workersVal = parseInt(document.getElementById('new_workers')?.value || '0', 10) || 0;
                let r = await api("/api/clients", {
                    method: "POST",
                    body: JSON.stringify({
                        name: name,
                        group_id: targetGid,
                        days: +document.getElementById('days').value,
                        hash: "",
                        workers: workersVal
                    })
                });

                if(!r.ok) { showToast(await r.text(), "error"); } else {
                    document.getElementById('newDlg').close();
                    showToast("Ключ успешно создан");
                    loadClients();
                }
            } finally { btn.textContent = 'Создать'; btn.disabled = false; }
        }

        let activeClient = null;

        function openEditClientDlg(c) {
            activeClient = c;
            const isMain = c.name === "Главный пароль";

            document.getElementById('edit_name').value = c.name || '';
            document.getElementById('edit_name').disabled = isMain;
            const groupSelect = document.getElementById('edit_group');
            if (groupSelect) {
                groupSelect.innerHTML = '<option value="">Без группы</option>' + (window.groupsData || []).map(g =>
                    `<option value="${esc(g.id)}">${esc(g.name || 'Без названия')}</option>`
                ).join('');
                groupSelect.value = c.group_id || "";
                groupSelect.disabled = isMain;
            }
            document.getElementById('edit_pass').value = c.password || '';

            let remainingDays = 0;
            if (c.expires) {
                remainingDays = Math.max(0, Math.ceil((c.expires * 1000 - Date.now()) / 86400000));
            }
            document.getElementById('edit_days').value = c.expires ? remainingDays : 0;
            document.getElementById('edit_days').disabled = isMain;

            const editWorkers = document.getElementById('edit_workers');
            if (editWorkers) {
                editWorkers.value = c.workers || 0;
                editWorkers.disabled = isMain;
            }

            document.getElementById('edit_ip_info').textContent = c.ip || '—';
            document.getElementById('edit_traffic_info').textContent = `↑ ${size(c.up)} / ↓ ${size(c.down)}`;

            document.getElementById('edit_statusBtn').innerHTML = c.active ? 'Выкл' : 'Вкл';
            document.getElementById('edit_statusBtn').style.display = isMain ? 'none' : 'inline-block';

            document.getElementById('edit_deleteBtn').style.display = isMain ? 'none' : 'inline-block';
            document.getElementById('edit_saveBtn').style.display = isMain ? 'none' : 'inline-block';

            document.getElementById('editDlg').showModal();
        }

        async function copyPasswordText() {
            let val = document.getElementById('edit_pass').value;
            if (await copyToClipboard(val)) {
                showToast("Пароль скопирован");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        async function executeToggle() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password) + "/toggle", {method: "POST"});
            if (r.ok) {
                showToast("Статус изменен");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function executeUnbind() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password) + "/unbind", {method: "POST"});
            if (r.ok) {
                showToast("Привязка сброшена");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function executeDelete() {
            if (!activeClient) return;
            let r = await api("/api/clients/" + encodeURIComponent(activeClient.password), {method: "DELETE"});
            if (r.ok) {
                showToast("Доступ удален");
                document.getElementById('editDlg').close();
                loadClients();
            }
        }

        async function saveClientChanges() {
            if (!activeClient) return;
            const btn = document.getElementById('edit_saveBtn');
            btn.textContent = 'Сохранение...'; btn.disabled = true;

            try {
                const targetGid = document.getElementById('edit_group') ? document.getElementById('edit_group').value : "";
                let name = document.getElementById('edit_name').value.trim();
                if (!name) {
                    const scopeClients = (window.clientsData || []).filter(c => (c.group_id || '') === targetGid && c.password !== activeClient.password);
                    name = generateUniqueClientName(scopeClients.map(c => c.name));
                }
                let r = await api("/api/clients/" + encodeURIComponent(activeClient.password), {
                    method: "POST",
                    body: JSON.stringify({
                        name: name,
                        group_id: targetGid,
                        days: +document.getElementById('edit_days').value,
                        hash: activeClient.vk_hashes || activeClient.vk_hash || "",
                        vk_token: Boolean(activeClient.vk_token),
                        peer_port: activeClient.peer_port || window.serverDefaultPeerPort || 46000,
                        local_port: activeClient.local_port || 0,
                        workers: parseInt(document.getElementById('edit_workers')?.value || '0', 10) || 0
                    })
                });

                if(!r.ok) { showToast(await r.text(), "error"); } else {
                    showToast("Изменения сохранены");
                    document.getElementById('editDlg').close();
                    loadClients();
                }
            } finally { btn.textContent = 'Сохранить'; btn.disabled = false; }
        }

        let currentHubFilterQuery = '';
        const hubSelectedPasswords = new Set();

        function handleHubSearch(val) {
            currentHubFilterQuery = (val || '').trim();
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = currentHubFilterQuery ? 'inline-flex' : 'none';
            renderHubClientsList();
        }

        function clearHubSearch() {
            const input = document.getElementById('hubSearchInput');
            if (input) input.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';
            currentHubFilterQuery = '';
            renderHubClientsList();
        }

        function openLinksAndTokensDlg() {
            hubSelectedPasswords.clear();
            currentHubFilterQuery = '';
            const searchInput = document.getElementById('hubSearchInput');
            if (searchInput) searchInput.value = '';
            const clearBtn = document.getElementById('hubSearchClearBtn');
            if (clearBtn) clearBtn.style.display = 'none';

            const tokenInput = document.getElementById('hubVkTokenInput');
            if (tokenInput) tokenInput.value = sharedVkToken || '';
            const hashesInput = document.getElementById('hubVkHashesInput');
            if (hashesInput) hashesInput.value = sharedVkHashes || '';
            const workersInput = document.getElementById('hubWorkersInput');
            if (workersInput) workersInput.value = '0';
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;
            renderHubClientsList();
            updateHubSelectedCount();
            document.getElementById('linksAndTokensDlg').showModal();
        }

        async function autoSaveHubVkToken() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (token && (/\s/.test(token) || token.length > 4096)) return false;
            if (token === sharedVkToken) return true;
            const r = await api('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ vk_token: token })
            });
            if (r.ok) {
                sharedVkToken = token;
                renderHubClientsList();
                return true;
            }
            return false;
        }

        async function autoSaveHubVkHashes() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            if (raw === sharedVkHashes) return true;
            const r = await api('/api/settings', {
                method: 'POST',
                body: JSON.stringify({ vk_hashes: raw })
            });
            if (r.ok) {
                sharedVkHashes = raw;
                renderHubClientsList();
                return true;
            }
            return false;
        }

        async function applyHubVkTokenDirect() {
            const input = document.getElementById('hubVkTokenInput');
            const token = input ? input.value.trim() : '';
            if (!token) {
                showToast("Введите VK токен", "error");
                return;
            }
            if (/\s/.test(token) || token.length > 4096) {
                showToast("VK токен не должен содержать пробелы и должен быть короче 4097 символов", "error");
                return;
            }
            await autoSaveHubVkToken();
            promptBatchAction('token');
        }

        async function applyHubVkHashesDirect() {
            const input = document.getElementById('hubVkHashesInput');
            const raw = input ? input.value.trim() : '';
            const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
            if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", "error");
                return;
            }
            await autoSaveHubVkHashes();
            promptBatchAction('hashes');
        }

        async function applyHubWorkersDirect() {
            const input = document.getElementById('hubWorkersInput');
            const w = parseInt(input ? input.value : '0', 10);
            if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 100) {
                showToast("Число потоков должно быть 0 (клиентские) или от 3 до 100", "error");
                return;
            }
            promptBatchAction('workers');
        }

        function renderHubClientsList(preservedChecked = null) {
            const container = document.getElementById('hubClientsList');
            if (!container) return;
            if (preservedChecked !== null) {
                hubSelectedPasswords.clear();
                preservedChecked.forEach(p => hubSelectedPasswords.add(p));
            }
            const allClients = window.clientsData || [];
            if (!allClients.length) {
                container.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 24px;">Нет созданных клиентов</div>';
                updateHubSelectedCount();
                return;
            }

            const groupMap = new Map();
            (window.groupsData || []).forEach(g => {
                if (g && g.id) groupMap.set(g.id, g.name || '');
            });

            let displayClients = [];
            const q = currentHubFilterQuery;

            if (!q) {
                displayClients = allClients.map(c => ({
                    client: c,
                    groupName: c.group_id ? (groupMap.get(c.group_id) || '') : '',
                    score: 0
                }));
            } else {
                const bracketMatch = q.match(/^(.*?)\((.*?)\)(.*)$/);
                let mode = 'name_only';
                let namePart = '';
                let groupPart = '';

                if (bracketMatch) {
                    const outside = (bracketMatch[1] + ' ' + bracketMatch[3]).trim();
                    const inside = bracketMatch[2].trim();
                    if (!outside) {
                        mode = 'group_only';
                        groupPart = inside;
                    } else {
                        mode = 'name_and_group';
                        namePart = outside;
                        groupPart = inside;
                    }
                } else {
                    const openIdx = q.lastIndexOf('(');
                    if (openIdx !== -1) {
                        const outside = q.slice(0, openIdx).trim();
                        const inside = q.slice(openIdx + 1).trim();
                        if (!outside) {
                            mode = 'group_only';
                            groupPart = inside;
                        } else {
                            mode = 'name_and_group';
                            namePart = outside;
                            groupPart = inside;
                        }
                    } else {
                        mode = 'name_only';
                        namePart = q;
                    }
                }

                const scored = [];
                for (const c of allClients) {
                    const gName = c.group_id ? (groupMap.get(c.group_id) || '') : '';
                    const cName = c.name || 'Клиент';
                    let score = 0;

                    if (mode === 'name_only') {
                        score = calcMatchScore(cName, namePart);
                    } else if (mode === 'group_only') {
                        if (c.group_id && gName) {
                            if (!groupPart) {
                                score = 100;
                            } else {
                                score = calcMatchScore(gName, groupPart);
                            }
                        }
                    } else if (mode === 'name_and_group') {
                        const nScore = namePart ? calcMatchScore(cName, namePart) : 0;
                        const gScore = (groupPart && gName) ? calcMatchScore(gName, groupPart) : 0;
                        if (nScore > 0 && gScore > 0) {
                            score = 2000 + nScore + gScore;
                        } else if (nScore > 0) {
                            score = nScore;
                        } else if (gScore > 0) {
                            score = gScore;
                        }
                    }

                    if (score > 0) {
                        scored.push({ client: c, groupName: gName, score });
                    }
                }

                scored.sort((a, b) => b.score - a.score);
                displayClients = scored;
            }

            if (!displayClients.length) {
                container.innerHTML = `<div style="color: var(--text-muted); text-align: center; padding: 24px;">По запросу «<b>${esc(q)}</b>» ничего не найдено</div>`;
                updateHubSelectedCount();
                return;
            }

            container.innerHTML = displayClients.map(item => {
                const c = item.client;
                const groupName = item.groupName;
                const link = c.link || '';
                let badge = '';
                const hashes = String(c.vk_hashes || c.vk_hash || '').trim();
                if (c.vk_token) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Токен</span>';
                } else if (hashes) {
                    badge = '<span class="badge active" style="font-size: 11px;">VK Хеши</span>';
                } else {
                    badge = '<span class="badge inactive" style="font-size: 11px;">Прямой</span>';
                }
                if (c.workers && c.workers >= 3) {
                    badge += ` <span class="badge active" style="font-size: 11px;">Потоки: ${c.workers}</span>`;
                }
                const isChecked = hubSelectedPasswords.has(c.password);
                const displayName = groupName ? `${c.name || 'Клиент'} (${groupName})` : (c.name || 'Клиент');

                return `
                <div style="background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 8px;">
                    <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap;">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <input type="checkbox" class="hub-client-cb" value="${esc(c.password)}" ${isChecked ? 'checked' : ''} onchange="handleHubClientCbChange(this)" style="width: 16px; height: 16px; cursor: pointer; accent-color: var(--primary);">
                            <span style="font-weight: 600; font-size: 14px; color: var(--text-main);">${esc(displayName)}</span>
                            ${badge}
                        </div>
                    </div>
                    <div style="display: flex; gap: 8px; align-items: center;">
                        <input readonly value="${esc(link)}" style="flex: 1; height: 40px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; padding: 0 10px; background: var(--surface-hover); color: var(--text-muted); border-radius: 8px;" onclick="this.select()">
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="copyHubClientLink('${esc(c.password)}', this)" title="Копировать ссылку">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                        </button>
                        <button class="btn btn-ghost" style="height: 40px; width: 44px; padding: 0; display: flex; align-items: center; justify-content: center; flex-shrink: 0; border: 1px solid var(--border); border-radius: 8px;" onclick="openQrModalForClient('${esc(c.password)}')" title="QR-код">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="color: var(--primary);"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M7 7h.01M17 7h.01M7 17h.01M17 17h.01"></path></svg>
                        </button>
                    </div>
                </div>
                `;
            }).join('');

            updateHubSelectedCount();
        }

        function handleHubClientCbChange(cb) {
            if (cb.checked) {
                hubSelectedPasswords.add(cb.value);
            } else {
                hubSelectedPasswords.delete(cb.value);
            }
            updateHubSelectedCount();
        }

        function toggleSelectAllHubClients(checked) {
            const cbs = document.querySelectorAll('.hub-client-cb');
            cbs.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    hubSelectedPasswords.add(cb.value);
                } else {
                    hubSelectedPasswords.delete(cb.value);
                }
            });
            updateHubSelectedCount();
        }

        function updateHubSelectedCount() {
            const visibleCbs = document.querySelectorAll('.hub-client-cb');
            const checkedCount = hubSelectedPasswords.size;
            const countEl = document.getElementById('hubSelectedCount');
            if (countEl) countEl.textContent = `(${checkedCount} выбрано)`;
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) {
                selectAll.checked = (visibleCbs.length > 0 && Array.from(visibleCbs).every(cb => cb.checked));
            }
        }

        async function copyHubClientLink(password, btn = null) {
            const client = (window.clientsData || []).find(c => c.password === password);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            if (await copyToClipboard(link)) {
                showToast("Ссылка скопирована");
                if (btn) {
                    const orig = btn.innerHTML;
                    btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
                    setTimeout(() => { btn.innerHTML = orig; }, 1500);
                }
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }

        let currentBatchAction = null;

        function promptBatchAction(action) {
            if (!hubSelectedPasswords.size) {
                const checkedCbs = Array.from(document.querySelectorAll('.hub-client-cb:checked'));
                checkedCbs.forEach(cb => hubSelectedPasswords.add(cb.value));
            }
            if (!hubSelectedPasswords.size) {
                showToast("Выберите хотя бы одного клиента", "error");
                return;
            }
            const count = hubSelectedPasswords.size;
            const titleEl = document.getElementById('batchConfirmTitle');
            const msgEl = document.getElementById('batchConfirmMessage');
            const submitBtn = document.getElementById('batchConfirmSubmitBtn');

            if (action === 'token') {
                const tokenVal = (document.getElementById('hubVkTokenInput')?.value || sharedVkToken || '').trim();
                if (!tokenVal) {
                    showToast("Укажите VK токен", "error");
                    return;
                }
                titleEl.textContent = "Применить VK токен";
                msgEl.textContent = `Включить общий VK токен для выбранных клиентов (${count} шт.)? Индивидуальные VK хеши будут очищены.`;
                submitBtn.textContent = "Применить токен";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-token') {
                titleEl.textContent = "Отключить VK токен";
                msgEl.textContent = `Отключить VK токен для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Отключить токен";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || sharedVkHashes || '').trim();
                const hashes = raw.split(',').map(s => s.trim()).filter(Boolean);
                if (!hashes.length || hashes.length > 6 || hashes.some(h => h.length < 16 || /\s/.test(h))) {
                    showToast("Укажите от 1 до 6 корректных VK-хешей через запятую (каждый от 16 символов без пробелов)", "error");
                    return;
                }
                titleEl.textContent = "Применить VK хеши";
                msgEl.textContent = `Установить указанные VK хеши для выбранных клиентов (${count} шт.)? Флаг VK токена будет отключен.`;
                submitBtn.textContent = "Применить хеши";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-hashes') {
                titleEl.textContent = "Очистить VK хеши";
                msgEl.textContent = `Очистить VK хеши для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Очистить хеши";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'workers') {
                const w = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10);
                if (isNaN(w) || w < 0 || (w > 0 && w < 3) || w > 100) {
                    showToast("Число потоков должно быть 0 или от 3 до 100", "error");
                    return;
                }
                titleEl.textContent = "Установить число потоков";
                msgEl.textContent = `Установить число потоков (${w === 0 ? 'Клиентские настройки (0)' : w}) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Установить число потоков";
                submitBtn.className = "btn btn-primary";
            } else if (action === 'clear-workers') {
                titleEl.textContent = "Сбросить число потоков";
                msgEl.textContent = `Сбросить число потоков до клиентских настроек (0) для выбранных клиентов (${count} шт.)?`;
                submitBtn.textContent = "Сбросить число потоков";
                submitBtn.className = "btn btn-danger";
            } else if (action === 'clear') {
                titleEl.textContent = "Очистить параметры VK";
                msgEl.textContent = `Очистить VK токен и VK хеши у выбранных клиентов (${count} шт.)? Они перейдут в обычный режим прямого подключения.`;
                submitBtn.textContent = "Очистить";
                submitBtn.className = "btn btn-danger";
            }
            currentBatchAction = action;
            submitBtn.onclick = executeBatchAction;
            document.getElementById('batchConfirmDlg').showModal();
        }

        async function executeBatchAction() {
            const action = currentBatchAction;
            if (!action) return;
            const checkedPasses = Array.from(hubSelectedPasswords);
            const clients = (window.clientsData || []).filter(c => checkedPasses.includes(c.password));

            const submitBtn = document.getElementById('batchConfirmSubmitBtn');
            submitBtn.disabled = true;
            submitBtn.textContent = "Применение...";

            let hashesVal = null;
            let tokenVal = null;
            let workersVal = null;

            if (action === 'token') {
                tokenVal = true;
                hashesVal = '';
            } else if (action === 'clear-token') {
                tokenVal = false;
            } else if (action === 'hashes') {
                const raw = (document.getElementById('hubVkHashesInput')?.value || sharedVkHashes || '').trim();
                hashesVal = raw.split(',').map(s => s.trim()).filter(Boolean).join(',');
                tokenVal = false;
            } else if (action === 'clear-hashes') {
                hashesVal = '';
            } else if (action === 'workers') {
                workersVal = parseInt(document.getElementById('hubWorkersInput')?.value || '0', 10) || 0;
            } else if (action === 'clear-workers') {
                workersVal = 0;
                const winp = document.getElementById('hubWorkersInput');
                if (winp) winp.value = '0';
            } else if (action === 'clear') {
                tokenVal = false;
                hashesVal = '';
            }

            try {
                let successCount = 0;
                for (const c of clients) {
                    let remainingDays = 0;
                    if (c.expires) {
                        remainingDays = Math.max(1, Math.ceil((c.expires * 1000 - Date.now()) / 86400000));
                    }
                    const payload = {
                        name: c.name || '',
                        group_id: c.group_id || '',
                        days: c.expires ? remainingDays : 0,
                        hash: (hashesVal !== null) ? hashesVal : (c.vk_hashes || c.vk_hash || ''),
                        vk_token: (tokenVal !== null) ? tokenVal : Boolean(c.vk_token),
                        peer_port: c.peer_port || window.serverDefaultPeerPort || 46000,
                        local_port: c.local_port || 0,
                        workers: (workersVal !== null) ? workersVal : (c.workers || 0)
                    };
                    const r = await api("/api/clients/" + encodeURIComponent(c.password), {
                        method: "POST",
                        body: JSON.stringify(payload)
                    });
                    if (r.ok) {
                        successCount++;
                    }
                }
                document.getElementById('batchConfirmDlg').close();
                showToast(`Обновлено клиентов: ${successCount} из ${clients.length}`);
                await loadClients();
                renderHubClientsList();
                updateHubSelectedCount();
            } finally {
                submitBtn.disabled = false;
                submitBtn.className = "btn btn-primary";
                submitBtn.textContent = "Подтвердить";
                currentBatchAction = null;
            }
        }
        let logsLoadedOnce = false;
        let logsInFlight = false;
        async function loadLogs() {
            if (logsInFlight) return;
            const consoleElem = document.getElementById('logConsole');

            const selection = window.getSelection();
            const isSelecting = selection && selection.toString().length > 0 && consoleElem.contains(selection.anchorNode);

            if (isSelecting) {
                return;
            }

            logsInFlight = true;
            try {
                let r = await api("/api/logs"); if(!r.ok) return; let x = await r.json();
                document.getElementById('logFilePath').textContent = "Путь: " + x.path;
                document.getElementById('loggingActiveToggle').checked = x.active;

                const threshold = 30;
                const scrollBottom = consoleElem.scrollHeight - consoleElem.clientHeight;
                const isScrolledToBottom = (scrollBottom - consoleElem.scrollTop) <= threshold;

                const linesHtml = x.lines.map(line => {
                    let escLine = esc(line);
                    const match = escLine.match(/^\[([^\]]+)\]\s+\[([^\]]+)\]\s+(.*)$/);
                    if (match) {
                        const [, timestamp, level, message] = match;
                        const lvlClass = level.trim() === 'ERROR' ? 'log-err' : 'log-info';
                        return `<div class="log-line"><span class="log-time">[${timestamp}]</span><span class="log-lvl ${lvlClass}">${level}</span><span>${message}</span></div>`;
                    }
                    return `<div class="log-line"><span>${escLine}</span></div>`;
                }).join('');

                consoleElem.innerHTML = linesHtml;

                if (isScrolledToBottom || !logsLoadedOnce) {
                    consoleElem.scrollTop = consoleElem.scrollHeight;
                    logsLoadedOnce = true;
                }
            } catch (e) {
            } finally {
                logsInFlight = false;
            }
        }

        async function toggleLoggingActive() {
            const active = document.getElementById('loggingActiveToggle').checked;
            let r = await api("/api/logs/toggle", {
                method: "POST",
                body: JSON.stringify({ active })
            });
            if (r.ok) {
                showToast(active ? "Логирование включено" : "Логирование выключено");
            }
        }

        async function clearLogs() {
            if (!confirm("Вы действительно хотите очистить всю историю логов?")) return;
            let r = await api("/api/logs/clear", {method: "POST"});
            if (r.ok) {
                document.getElementById('logConsole').textContent = '';
                showToast("История логов очищена");
            }
        }

        async function animateSavedButton(button, normalText) {
            button.classList.add('save-success');
            button.textContent = 'Успешно!';
            await new Promise(resolve => setTimeout(resolve, 700));
            button.classList.remove('save-success', 'saving');
            button.textContent = normalText;
            updateSettingsDirtyState();
        }

        async function saveMainPassword() {
            const button = document.getElementById('saveMainPasswordBtn');
            if (!button || button.disabled) return;
            button.classList.add('saving'); button.disabled = true;
            const value = document.getElementById('mainpass').value;
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify({ main_password: value })
            });
            if (!response.ok) {
                button.classList.remove('saving');
                showToast(await response.text(), 'error');
                updateSettingsDirtyState();
                return;
            }
            const result = await response.json();
            savedMainPassword = value;
            setRestartRequired(result.restart_required);
            await animateSavedButton(button, 'Сохранить главный пароль');
        }

        async function saveDnsSettings() {
            const button = document.getElementById('saveDnsBtn');
            const provider = document.getElementById('dns_provider')?.value || '';
            if (!button || button.disabled) return;
            let payload;
            if (provider === 'custom') {
                const value = document.getElementById('dns_manual')?.value || '';
                const addresses = value.split(',');
                const valid = value === value.trim()
                    && !value.includes(' ')
                    && (addresses.length === 1 || addresses.length === 2)
                    && addresses.every(address => {
                        const octets = address.split('.');
                        return octets.length === 4
                            && octets.every(octet => /^[0-9]{1,3}$/.test(octet) && Number(octet) <= 255);
                    });
                if (!valid) {
                    showToast('Введите один или два IPv4-адреса через запятую без пробелов', 'error');
                    return;
                }
                payload = {
                    dns_provider: 'custom',
                    dns_primary: addresses[0],
                    dns_secondary: addresses[1] || ''
                };
            } else {
                payload = { dns_provider: provider };
            }
            button.classList.add('saving'); button.disabled = true;
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify(payload)
            });
            if (!response.ok) {
                button.classList.remove('saving');
                showToast(await response.text(), 'error');
                updateSettingsDirtyState();
                return;
            }
            const result = await response.json();
            savedDnsProvider = provider;
            savedManualDns = provider === 'custom'
                ? [payload.dns_primary, payload.dns_secondary].filter(Boolean).join(',')
                : savedManualDns;
            setRestartRequired(result.restart_required);
            await animateSavedButton(button, 'Применить DNS');
        }
        async function saveAutoRestartInterval() {
            const button = document.getElementById('saveAutoRestartIntervalBtn');
            if (!button || button.disabled) return;
            const hours = Number(document.getElementById('auto_restart_interval').value);
            if (![0, 12, 24, 48, 72].includes(hours)) {
                showToast('Выберите допустимый интервал перезагрузки', 'error');
                return;
            }
            button.classList.add('saving'); button.disabled = true;
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify({ auto_restart_interval_hours: hours })
            });
            if (!response.ok) {
                button.classList.remove('saving');
                showToast(await response.text(), 'error');
                updateSettingsDirtyState();
                return;
            }
            savedAutoRestartInterval = hours;
            await animateSavedButton(button, 'Сохранить интервал');
        }
        async function executeReboot() {
            document.getElementById('rebootDlg').close();
            const response = await api("/api/reboot", {method: "POST"});
            if (!response.ok) {
                showToast("Не удалось подготовить перезагрузку", "error");
                return;
            }
            showToast("Сервер перезапускается...");
            setTimeout(() => location.reload(), 3000);
        }
        async function logout() { await api("/api/logout", {method: "POST"}); sessionStorage.clear(); location.reload(); }

        function switchTab(tabId) {
            document.querySelectorAll('.tab-btn').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-tab') === tabId);
            });
            document.getElementById('monitoring-section').style.display = tabId === 'monitoring' ? 'block' : 'none';
            document.getElementById('clients-section').style.display = tabId === 'clients' ? 'block' : 'none';
            document.getElementById('logs-section').style.display = tabId === 'logs' ? 'block' : 'none';
            document.getElementById('settings-section').style.display = tabId === 'settings' ? 'block' : 'none';
            localStorage.setItem('csqtt-active-tab', tabId);
            if (tabId === 'clients') {
                loadClients();
            }
            if (tabId === 'logs') {
                loadLogs();
            }
        }

        const savedTab = localStorage.getItem('csqtt-active-tab') || 'monitoring';
        switchTab(savedTab);

        let updateIntervalId = null;
        let logsIntervalId = null;
        let nextClientsRefreshAt = 0;
        function startUpdateInterval(ms) {
            if (updateIntervalId) clearInterval(updateIntervalId);
            updateIntervalId = setInterval(() => {
                loadStats();
                if (document.getElementById('clients-section').style.display !== 'none' && Date.now() >= nextClientsRefreshAt) {
                    loadClients();
                    nextClientsRefreshAt = Date.now() + 30000;
                }
            }, ms);
            if (!logsIntervalId) {
                logsIntervalId = setInterval(() => {
                    if (document.getElementById('logs-section').style.display !== 'none') {
                        loadLogs();
                    }
                }, 5000);
            }
        }

        function changeUpdateInterval() {
            const ms = parseInt(document.getElementById('updateIntervalSelect').value, 10);
            localStorage.setItem('csqtt-update-interval', ms);
            startUpdateInterval(ms);
        }

        document.getElementById('mainpass')?.addEventListener('input', updateSettingsDirtyState);
        const dnsProviderSelect = document.getElementById('dns_provider');
        dnsProviderSelect?.addEventListener('change', () => {
            syncDnsControls();
            updateSettingsDirtyState();
        });
        document.getElementById('dns_manual')?.addEventListener('input', updateSettingsDirtyState);
        document.getElementById('auto_restart_interval')?.addEventListener('change', updateSettingsDirtyState);
        const dlBtn = document.getElementById('downloadLogBtn');
        if (dlBtn) dlBtn.href = (basePath || '') + '/api/logs/download';
        updateApiDocsPaths();
        loadStats(); loadSettings(); if (savedTab === 'clients') { loadClients(); } if (savedTab === 'logs') { loadLogs(); }

        const savedInterval = parseInt(localStorage.getItem('csqtt-update-interval'), 10) || 3000;
        const selectElem = document.getElementById('updateIntervalSelect');
        if (selectElem) {
            selectElem.value = savedInterval;
        }
        startUpdateInterval(savedInterval);

        document.querySelectorAll('dialog').forEach(dlg => {
            dlg.addEventListener('mousedown', (e) => {
                dlg._mouseDownOnBackdrop = (e.target === dlg);
            });
            dlg.addEventListener('click', (e) => {
                if (e.target === dlg && dlg._mouseDownOnBackdrop) {
                    const rect = dlg.getBoundingClientRect();
                    const isInDialog = (
                        rect.top <= e.clientY && e.clientY <= rect.top + rect.height &&
                        rect.left <= e.clientX && e.clientX <= rect.left + rect.width
                    );
                    if (!isInDialog) {
                        dlg.close();
                    }
                }
                dlg._mouseDownOnBackdrop = false;
            });
            dlg.addEventListener('close', () => {
                const toastBox = document.getElementById('toastContainer');
                if (toastBox && !document.querySelector('dialog[open]')) {
                    document.body.appendChild(toastBox);
                }
            });
        });

        document.getElementById('linksAndTokensDlg')?.addEventListener('close', () => {
            const cbs = document.querySelectorAll('.hub-client-cb');
            cbs.forEach(cb => cb.checked = false);
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;
            updateHubSelectedCount();
        });

        document.getElementById('groupDlg')?.addEventListener('close', () => {
            if (typeof exitGroupInlineEdit === 'function') exitGroupInlineEdit();
        });

        window.qrSvgCache = {};
        function generateQrSvg(text) {
            if (window.qrSvgCache[text]) {
                return window.qrSvgCache[text];
            }
            try {
                const qr = qrcode(0, 'L');
                qr.addData(text);
                qr.make();
                const svg = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
                window.qrSvgCache[text] = svg;
                return svg;
            } catch (e) {
                return '<div style="color: #ef4444; padding: 16px;">Ошибка генерации QR</div>';
            }
        }

        function openQrModalForClient(password) {
            const client = (window.clientsData || []).find(c => c.password === password) || (typeof activeClient !== 'undefined' && activeClient && activeClient.password === password ? activeClient : null);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            const titleEl = document.getElementById('qrDlgTitle');
            if (titleEl) titleEl.textContent = 'QR-код: ' + (client.name || 'Клиент');
            const linkInput = document.getElementById('qrDlgLink');
            if (linkInput) linkInput.value = link;
            const container = document.getElementById('qrCodeContainer');
            if (container) container.innerHTML = generateQrSvg(link);
            document.getElementById('qrDlg').showModal();
        }

        function openQrModalForActiveClient() {
            if (typeof activeClient !== 'undefined' && activeClient) {
                openQrModalForClient(activeClient.password);
            }
        }

        async function copyQrDlgLink(btn) {
            const link = document.getElementById('qrDlgLink')?.value || '';
            if (await copyToClipboard(link)) {
                if (btn) {
                    btn.style.color = '#10b981';
                    setTimeout(() => { btn.style.color = ''; }, 1500);
                }
                showToast("Ссылка скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }
    </script>
</body>
</html>

"##;

#[cfg(test)]
mod tests {
    use super::{
        DNS_PROVIDERS, LOGIN_HTML, MAX_CLIENT_NAME_BYTES, PANEL_HTML, PWA_ICON_192_PNG,
        PWA_ICON_512_PNG, PWA_MANIFEST, PWA_SERVICE_WORKER, count_active_sessions,
        decrypt_csqtt_link, deploy_dns_profile_or_yandex, dns_provider_by_id, dns_provider_id,
        encrypt_csqtt_link, make_client_link, manual_dns, normalize_client_vk_hashes,
        normalize_vk_access_token, process_memory, process_memory_rollup, provider_dns,
        validate_client_name, validate_proxy_profile_name, validate_workers,
    };

    #[test]
    fn dns_provider_catalog_is_complete_and_has_exact_yandex_default() {
        assert_eq!(DNS_PROVIDERS.len(), 13);
        let yandex = dns_provider_by_id("yandex").unwrap();
        assert_eq!(provider_dns(yandex), "77.88.8.8,77.88.8.1");
        assert_eq!(dns_provider_id("1.1.1.1,1.0.0.1"), Some("cloudflare"));
        assert_eq!(
            dns_provider_id("45.90.28.181,45.90.30.181"),
            Some("nextdns")
        );
        assert_eq!(dns_provider_id("195.208.6.1,195.208.7.1"), Some("bizone"));
        assert_eq!(dns_provider_id("195.208.4.1,195.208.5.1"), Some("nsdi"));
        assert_eq!(dns_provider_id("10.0.0.1"), None);
    }

    #[test]
    fn legacy_deploy_dns_migration_normalizes_known_partial_profiles_or_uses_yandex() {
        assert_eq!(
            deploy_dns_profile_or_yandex("111.88.96.55,203.0.113.7"),
            "111.88.96.54,111.88.96.55"
        );
        assert_eq!(
            deploy_dns_profile_or_yandex("111.88.96.51,203.0.113.7"),
            "111.88.96.54,111.88.96.55"
        );
        assert_eq!(
            deploy_dns_profile_or_yandex("95.182.120.241,203.0.113.7"),
            "193.233.112.67,193.233.112.88"
        );
        assert_eq!(
            deploy_dns_profile_or_yandex("8.8.8.8,1.1.1.1"),
            "8.8.8.8,8.8.4.4"
        );
        assert_eq!(
            deploy_dns_profile_or_yandex("203.0.113.7,198.51.100.9"),
            "77.88.8.8,77.88.8.1"
        );
    }

    #[test]
    fn manual_dns_accepts_one_or_two_ipv4_addresses() {
        assert_eq!(manual_dns(Some("1.1.1.1"), None).unwrap(), "1.1.1.1");
        assert_eq!(
            manual_dns(Some(" 1.1.1.1 "), Some(" 1.0.0.1 ")).unwrap(),
            "1.1.1.1,1.0.0.1"
        );
    }

    #[test]
    fn manual_dns_rejects_empty_primary_and_invalid_ipv4() {
        assert!(manual_dns(None, None).is_err());
        assert!(manual_dns(Some(""), None).is_err());
        assert!(manual_dns(Some("1.1.1.256"), None).is_err());
        assert!(manual_dns(Some("1.1.1.1"), Some("not-an-ip")).is_err());
    }

    #[test]
    fn panel_uses_the_complete_dns_provider_list_without_network_checks() {
        assert!(PANEL_HTML.contains("DNS Адреса"));
        assert!(PANEL_HTML.contains("id=\"dns_provider\""));
        assert!(PANEL_HTML.contains("<option value=\"custom\">Ручной DNS</option>"));
        assert!(PANEL_HTML.contains("id=\"dns_manual\""));
        assert!(PANEL_HTML.contains("placeholder=\"ipv4,ipv4\""));
        assert!(PANEL_HTML.contains("Yandex DNS"));
        assert!(PANEL_HTML.contains("Rostelecom DNS"));
        assert!(PANEL_HTML.contains("NextDNS"));
        assert!(PANEL_HTML.contains("BI.ZONE DNS"));
        assert!(PANEL_HTML.contains("НСДИ DNS"));
        assert!(PANEL_HTML.contains("dns_provider: provider"));
        assert!(!PANEL_HTML.contains("/api/dns-providers"));
        assert!(!PANEL_HTML.contains("dns_provider_health"));
        assert!(!PANEL_HTML.contains("refreshDnsProviderPings"));
        assert!(PANEL_HTML.contains("dns_provider: 'custom'"));
        assert!(PANEL_HTML.contains("syncDnsControls"));
    }

    #[test]
    fn persisted_text_fields_have_byte_and_control_character_limits() {
        assert!(validate_client_name("normal client").is_ok());
        assert!(validate_client_name(&"x".repeat(MAX_CLIENT_NAME_BYTES)).is_ok());
        assert!(validate_client_name(&"x".repeat(MAX_CLIENT_NAME_BYTES + 1)).is_err());
        assert!(validate_client_name("bad\nname").is_err());
        assert!(validate_proxy_profile_name(&"x".repeat(64)).is_ok());
        assert!(validate_proxy_profile_name(&"x".repeat(65)).is_err());
    }

    #[test]
    fn client_vk_hashes_accept_empty_or_one_to_six_values() {
        assert_eq!(normalize_client_vk_hashes("").unwrap(), "");
        for count in 1..=6 {
            let hashes = (1..=count)
                .map(|index| format!("abcdefghijklmnop{index}"))
                .collect::<Vec<_>>()
                .join(",");
            assert_eq!(normalize_client_vk_hashes(&hashes).unwrap(), hashes);
        }
    }

    #[test]
    fn client_vk_hashes_reject_spaces_short_values_and_more_than_six() {
        assert!(normalize_client_vk_hashes("abcdefghijklmnop, abcdefghijklmnop").is_err());
        assert!(normalize_client_vk_hashes("short").is_err());
        let seven = (1..=7)
            .map(|index| format!("abcdefghijklmnop{index}"))
            .collect::<Vec<_>>()
            .join(",");
        assert!(normalize_client_vk_hashes(&seven).is_err());
    }

    #[test]
    fn vk_token_accepts_one_compact_value_or_empty() {
        assert_eq!(
            normalize_vk_access_token("  vk1.token-_%2B  ").unwrap(),
            "vk1.token-_%2B"
        );
        assert_eq!(normalize_vk_access_token(" ").unwrap(), "");
        assert!(normalize_vk_access_token("token with space").is_err());
        assert!(normalize_vk_access_token(&"x".repeat(4097)).is_err());
    }

    #[test]
    fn mobile_panel_keeps_compact_labels_and_consistent_memory_value() {
        assert!(PANEL_HTML.contains("Использование памяти"));
        assert!(PANEL_HTML.contains("#ram_val { white-space: nowrap;"));
        assert!(PANEL_HTML.contains("id=\"ram_detail\""));
        assert!(PANEL_HTML.contains("x.ram_used"));
        assert!(PANEL_HTML.contains("text-size-adjust: none"));
        assert!(PANEL_HTML.contains(".toggle-row label"));
        assert!(PANEL_HTML.contains("white-space: nowrap"));
    }

    #[test]
    fn panel_displays_compact_udp_traffic_counters() {
        assert!(PANEL_HTML.contains("id=\"traffic_ingress\""));
        assert!(PANEL_HTML.contains("x.udp_rx_packets"));
        assert!(PANEL_HTML.contains("x.udp_tx_packets"));
        assert!(PANEL_HTML.contains("const compactCounter = input =>"));
        assert!(
            PANEL_HTML.contains("const units = [\"\", \"K\", \"M\", \"G\", \"T\", \"P\", \"E\"]")
        );
        assert!(PANEL_HTML.contains("#traffic_ingress { white-space: nowrap;"));
        assert!(PANEL_HTML.contains(
            "RX ${compactCounter(x.udp_rx_packets)} · TX ${compactCounter(x.udp_tx_packets)}"
        ));
        assert!(!PANEL_HTML.contains("UDP RX ${Number(x.udp_rx_packets) || 0}"));
    }

    #[test]
    fn pwa_uses_standalone_shell_without_caching_authenticated_panel_data() {
        assert!(PWA_MANIFEST.contains("\"display\": \"standalone\""));
        assert!(PWA_MANIFEST.contains("\"start_url\": \"/\""));
        assert!(PWA_MANIFEST.contains("\"sizes\": \"192x192\""));
        assert!(PWA_MANIFEST.contains("pwa-icon-192.png"));
        assert!(PWA_MANIFEST.contains("pwa-icon-512.png"));
        assert!(LOGIN_HTML.contains("manifest.webmanifest"));
        assert!(PANEL_HTML.contains("manifest.webmanifest"));
        assert!(LOGIN_HTML.contains("pwa-icon-192.png"));
        assert!(PANEL_HTML.contains("pwa-icon-192.png"));
        assert!(PWA_SERVICE_WORKER.contains("url.pathname.startsWith('/api/')"));
        assert!(PWA_SERVICE_WORKER.contains("SHELL_ASSETS"));
        assert!(!PWA_SERVICE_WORKER.contains("OFFLINE_PAGE"));
        assert!(!PWA_SERVICE_WORKER.contains("request.mode === 'navigate'"));
    }

    #[test]
    fn participant_cards_contain_ip_info() {
        assert!(PANEL_HTML.contains("IP:"));
    }

    #[test]
    fn pwa_raster_icon_fallbacks_are_valid_pngs() {
        const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
        assert!(PWA_ICON_192_PNG.starts_with(PNG_SIGNATURE));
        assert!(PWA_ICON_512_PNG.starts_with(PNG_SIGNATURE));
        assert!(PWA_ICON_512_PNG.len() > PWA_ICON_192_PNG.len());
    }

    #[test]
    fn process_memory_reads_linux_status_fields() {
        let memory = process_memory(
            "VmHWM:\t850000 kB\nVmRSS:\t400000 kB\nRssAnon:\t350000 kB\nRssFile:\t45000 kB\nRssShmem:\t5000 kB\nVmSwap:\t12000 kB\n",
        );
        assert_eq!(memory.rss, 400000);
        assert_eq!(memory.peak, 850000);
        assert_eq!(memory.anonymous, 350000);
        assert_eq!(memory.file, 45000);
        assert_eq!(memory.shared, 5000);
        assert_eq!(memory.swap, 12000);
    }

    #[test]
    fn process_memory_rollup_reads_attribution_fields() {
        let memory = process_memory_rollup(
            "Pss:\t700 kB\nPss_Anon:\t650 kB\nPss_File:\t40 kB\nPss_Shmem:\t10 kB\nAnonymous:\t680 kB\nShared_Clean:\t12 kB\nShared_Dirty:\t3 kB\nPrivate_Clean:\t20 kB\nPrivate_Dirty:\t640 kB\nSwapPss:\t7 kB\n",
        );
        assert_eq!(memory.pss, 700);
        assert_eq!(memory.pss_anon, 650);
        assert_eq!(memory.anonymous, 680);
        assert_eq!(memory.file, 40);
        assert_eq!(memory.shmem, 10);
        assert_eq!(memory.private, 660);
        assert_eq!(memory.shared, 15);
        assert_eq!(memory.swap_pss, 7);
    }

    #[test]
    fn csqtt_link_encryption_and_decryption() {
        let payload = "connect?v=2&host=1.2.3.4&peer=46000&password=testpass";
        let encrypted1 = encrypt_csqtt_link(payload);
        let encrypted2 = encrypt_csqtt_link(payload);
        assert!(encrypted1.starts_with("csqtt://"));
        assert_eq!(
            encrypted1, encrypted2,
            "link encryption must be deterministic for identical payload"
        );
        let decrypted = decrypt_csqtt_link(&encrypted1).expect("decryption should succeed");
        assert_eq!(decrypted, payload);
    }

    #[test]
    fn csqtt_link_legacy_plaintext_rejected() {
        use base64::Engine;
        let payload = "connect?v=2&host=1.2.3.4&peer=46000&password=legacy";
        let legacy_b64 =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
        let legacy_link = format!("csqtt://{legacy_b64}");
        assert!(decrypt_csqtt_link(&legacy_link).is_none());
    }

    #[test]
    fn make_client_link_contains_password_and_aes_roundtrip() {
        let link = make_client_link("1.2.3.4", 46000, "abc1234567890123", "", false, "");
        assert!(link.starts_with("csqtt://"));
        let decrypted = decrypt_csqtt_link(&link).expect("must decrypt");
        assert!(decrypted.contains("password=abc1234567890123"));
        assert!(!decrypted.contains("client="));
        assert!(!decrypted.contains("&workers="));
    }

    #[test]
    fn owner_link_contains_owner_password_without_workers() {
        let link = make_client_link("1.2.3.4", 46000, "ownerpass1234567", "", false, "");
        let decrypted = decrypt_csqtt_link(&link).expect("must decrypt");
        assert!(decrypted.contains("password=ownerpass1234567"));
        assert!(!decrypted.contains("client="));
        assert!(!decrypted.contains("&workers="));
    }

    #[test]
    fn count_active_sessions_isolates_passwords() {
        use crate::protocol::Session;
        use dashmap::DashMap;
        use std::net::SocketAddr;
        use std::sync::Arc;
        use std::sync::atomic::AtomicBool;

        let sessions = DashMap::new();
        let debug_flag = Arc::new(AtomicBool::new(false));
        let addr: SocketAddr = "127.0.0.1:46001".parse().unwrap();

        let s1 = Session::new(
            1,
            addr,
            "pass_a",
            1,
            "salt1",
            false,
            debug_flag.clone(),
            100,
        );
        sessions.insert(1, s1);

        assert_eq!(count_active_sessions(&sessions, "pass_a"), 1);
        assert_eq!(count_active_sessions(&sessions, "pass_b"), 0);

        let s_owner = Session::new(2, addr, "owner_pass", 2, "salt2", false, debug_flag, 100);
        sessions.insert(2, s_owner);

        assert_eq!(count_active_sessions(&sessions, "owner_pass"), 1);
        assert_eq!(count_active_sessions(&sessions, "pass_a"), 1);
        assert_eq!(count_active_sessions(&sessions, "pass_b"), 0);
    }

    #[test]
    fn workers_validation() {
        assert_eq!(validate_workers(None), Ok(None));
        assert_eq!(validate_workers(Some(0)), Ok(None));
        assert_eq!(validate_workers(Some(3)), Ok(Some(3)));
        assert_eq!(validate_workers(Some(36)), Ok(Some(36)));
        assert_eq!(validate_workers(Some(99)), Ok(Some(99)));
        assert_eq!(validate_workers(Some(100)), Ok(Some(100)));
        assert!(validate_workers(Some(1)).is_err());
        assert!(validate_workers(Some(2)).is_err());
        assert!(validate_workers(Some(101)).is_err());
    }
}
