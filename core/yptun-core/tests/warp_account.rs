//! Ported from WarpConfigGenerator (androidMain/jvmMain): new WARP accounts and their INI.

use std::collections::HashMap;
use std::sync::Mutex;

use yptun_core::repository::{HttpRequest, HttpResponse};
use yptun_core::warp;
use yptun_core::warp_account::*;

const ACCOUNT: &str = r#"{"privKey":"cHJpdg==","peer_pub":"cGVlcg==","client_ipv4":"172.16.0.2","client_ipv6":"2606:4700:110:8a1e::1","extra":1}"#;

const REGISTRATION: &str = r#"{"id":"x","config":{"client_id":"yAf/","peers":[{"public_key":"bmV3cGVlcg==","endpoint":{"host":"engage.cloudflareclient.com:2408"}}],"interface":{"addresses":{"v4":"172.16.0.9","v6":"2606:4700::9"}}}}"#;

/// Answers by URL; an unknown URL is a transport error. Records (method, url, body, timeout).
#[derive(Default)]
struct FakeHttp {
    answers: HashMap<String, (u16, String)>,
    calls: Mutex<Vec<(String, HttpRequest, String, u64)>>,
}

impl FakeHttp {
    fn with(mut self, url: &str, status: u16, body: &str) -> Self {
        self.answers.insert(url.to_string(), (status, body.to_string()));
        self
    }
    fn answer(&self, method: &str, request: &HttpRequest, body: &str, timeout_ms: u64) -> Option<HttpResponse> {
        self.calls.lock().unwrap().push((method.to_string(), request.clone(), body.to_string(), timeout_ms));
        self.answers
            .get(&request.url)
            .map(|(status, body)| HttpResponse { status: *status, headers: vec![], body: body.clone() })
    }
    fn urls(&self) -> Vec<String> {
        self.calls.lock().unwrap().iter().map(|(m, r, _, _)| format!("{} {}", m, r.url)).collect()
    }
}

impl WarpHttp for FakeHttp {
    fn get(&self, request: &HttpRequest, timeout_ms: u64) -> Option<HttpResponse> {
        self.answer("GET", request, "", timeout_ms)
    }
    fn post_json(&self, request: &HttpRequest, body: &str, timeout_ms: u64) -> Option<HttpResponse> {
        self.answer("POST", request, body, timeout_ms)
    }
}

fn fixed_keys() -> Result<KeyPair, String> {
    Ok(KeyPair { private_key: "bXlwcml2".into(), public_key: "bXlwdWI=".into() })
}

fn run(http: &FakeHttp, official_first: bool) -> (Result<String, String>, Vec<String>) {
    let mut logs = Vec::new();
    let result = generate_with(
        http,
        official_first,
        &mut |line: &str| logs.push(line.to_string()),
        &mut |_| 0,
        &mut fixed_keys,
        "2026-11-08T14:25:01.000Z",
    );
    (result, logs)
}

fn header<'a>(request: &'a HttpRequest, name: &str) -> Option<&'a str> {
    request.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
}

#[test]
fn key_pair_matches_rfc7748_and_is_clamped() {
    let secret: [u8; 32] = [
        0x77, 0x07, 0x6d, 0x0a, 0x73, 0x18, 0xa5, 0x7d, 0x3c, 0x16, 0xc1, 0x72, 0x51, 0xb2, 0x66, 0x45, 0xdf, 0x4c, 0x2f,
        0x87, 0xeb, 0xc0, 0x99, 0x2a, 0xb1, 0x77, 0xfb, 0xa5, 0x1d, 0xb9, 0x2c, 0x2a,
    ];
    let keys = key_pair_from_secret(secret);
    assert_eq!(keys.public_key, "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=");
    use base64::Engine;
    let private = base64::engine::general_purpose::STANDARD.decode(&keys.private_key).unwrap();
    assert_eq!(private.len(), 32);
    assert_eq!((private[0] & 7, private[31] & 128, private[31] & 64), (0, 0, 64));
    // The clamped secret gives the same public key.
    let mut again = [0u8; 32];
    again.copy_from_slice(&private);
    assert_eq!(key_pair_from_secret(again), keys);
}

#[test]
fn fresh_key_pairs_are_random_and_ini_sized() {
    let a = generate_key_pair().unwrap();
    let b = generate_key_pair().unwrap();
    assert_ne!(a.private_key, b.private_key);
    assert_eq!((a.private_key.len(), a.public_key.len()), (44, 44));
}

#[test]
fn service_answer_is_one_account_or_a_pool() {
    let one = parse_service_account(ACCOUNT, |_| 0).unwrap();
    assert_eq!(
        one,
        WarpAccount {
            private_key: "cHJpdg==".into(),
            peer_public_key: "cGVlcg==".into(),
            ipv4: "172.16.0.2".into(),
            ipv6: Some("2606:4700:110:8a1e::1".into()),
            reserved: None,
        }
    );
    let pool = r#" [{"privKey":"a","peer_pub":"b","client_ipv4":"1.1.1.1"},{"privKey":"c","peer_pub":"d","client_ipv4":"2.2.2.2","client_ipv6":" "}]"#;
    let mut seen = 0;
    let picked = parse_service_account(pool, |n| {
        seen = n;
        1
    })
    .unwrap();
    assert_eq!((seen, picked.private_key.as_str(), picked.ipv6), (2, "c", None));

    assert!(parse_service_account(r#"{"privKey":"a","peer_pub":"b","client_ipv4":""}"#, |_| 0).is_none());
    assert!(parse_service_account(r#"{"privKey":"a","client_ipv4":"1.1.1.1"}"#, |_| 0).is_none());
    assert!(parse_service_account("[]", |_| 0).is_none());
    assert!(parse_service_account("<html>500</html>", |_| 0).is_none());
}

#[test]
fn client_id_becomes_the_reserved_bytes() {
    assert_eq!(reserved_from_client_id(Some("yAf/")), Some([200, 7, 255]));
    assert_eq!(reserved_from_client_id(Some("AQID")), Some([1, 2, 3]));
    assert_eq!(reserved_from_client_id(Some("AQ==")), None);
    assert_eq!(reserved_from_client_id(Some("not base64!")), None);
    assert_eq!(reserved_from_client_id(Some("")), None);
    assert_eq!(reserved_from_client_id(None), None);
}

#[test]
fn registration_request_and_answer() {
    let body: serde_json::Value = serde_json::from_str(&registration_body("bXlwdWI=", "2026-11-08T14:25:01.000Z")).unwrap();
    assert_eq!(body["key"], "bXlwdWI=");
    assert_eq!(body["tos"], "2026-11-08T14:25:01.000Z");
    assert_eq!((body["model"].as_str(), body["type"].as_str(), body["locale"].as_str()), (Some("PC"), Some("Android"), Some("en_US")));
    assert_eq!((body["install_id"].as_str(), body["fcm_token"].as_str()), (Some(""), Some("")));

    let account = parse_registration(REGISTRATION, "bXlwcml2").unwrap();
    assert_eq!(
        account,
        WarpAccount {
            private_key: "bXlwcml2".into(),
            peer_public_key: "bmV3cGVlcg==".into(),
            ipv4: "172.16.0.9".into(),
            ipv6: Some("2606:4700::9".into()),
            reserved: Some([200, 7, 255]),
        }
    );
    assert_eq!(parse_registration(r#"{"config":{"peers":[]}}"#, "k").unwrap_err(), "Cloudflare WARP response missing peer");
    assert_eq!(
        parse_registration(r#"{"config":{"peers":[{"public_key":"p"}],"interface":{"addresses":{"v6":"::1"}}}}"#, "k").unwrap_err(),
        "Cloudflare WARP response missing IPv4 address"
    );
    assert!(parse_registration("oops", "k").is_err());
}

#[test]
fn tos_timestamp_format() {
    assert_eq!(iso_utc(1794147901000), "2026-11-08T14:25:01.000Z");
    assert_eq!(iso_utc(0), "1970-01-01T00:00:00.000Z");
    assert_eq!(tos_now().len(), 24);
}

#[test]
fn ini_matches_the_kotlin_generator() {
    let account = parse_service_account(ACCOUNT, |_| 0).unwrap();
    let expected = format!(
        "[Interface]\nPrivateKey = cHJpdg==\nAddress = 172.16.0.2/32, 2606:4700:110:8a1e::1/128\nDNS = 1.1.1.1\nMTU = 1280\nJc = 4\nJmin = 40\nJmax = 70\nS1 = 0\nS2 = 0\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\nI1 = {}\n\n[Peer]\nPublicKey = cGVlcg==\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = engage.cloudflareclient.com:2408\nPersistentKeepalive = 25\n",
        WARP_I1
    );
    assert_eq!(build_ini(&account), expected);

    let registered = parse_registration(REGISTRATION, "bXlwcml2").unwrap();
    let ini = build_ini(&WarpAccount { ipv6: None, ..registered });
    assert!(ini.contains("\nAddress = 172.16.0.9/32\n"));
    assert!(ini.contains("\nMTU = 1280\nReserved = 200, 7, 255\nJc = 4\n"));
}

#[test]
fn i1_is_the_amnezia_warp_preset() {
    assert_eq!(WARP_I1.len(), 2506);
    assert!(WARP_I1.starts_with("<b 0xce00000"));
    assert!(WARP_I1.ends_with("e2d8>"));
}

#[test]
fn generated_ini_runs_through_the_existing_warp_helpers() {
    let ini = build_ini(&parse_registration(REGISTRATION, "bXlwcml2").unwrap());
    let profile = warp::parse_warp_ini(&ini, "WARP").unwrap();
    assert_eq!((profile.kind.as_str(), profile.server.as_str(), profile.server_port), ("wireguard", "162.159.192.1", 2408));
    let engine = warp::engine_ini(&ini, true, false);
    assert!(!engine.contains("Reserved") && !engine.contains("I1 =") && engine.contains("Jc = 4"));
    assert!(engine.contains("Endpoint = 162.159.192.1:2408"));
}

#[test]
fn generators_are_tried_in_order_with_a_browser_agent() {
    let http = FakeHttp::default()
        .with(SERVICE_ENDPOINTS[0], 500, "error")
        .with(SERVICE_ENDPOINTS[2], 200, ACCOUNT);
    let (result, logs) = run(&http, false);
    assert!(result.unwrap().contains("PrivateKey = cHJpdg=="));
    assert_eq!(
        http.urls(),
        vec![format!("GET {}", SERVICE_ENDPOINTS[0]), format!("GET {}", SERVICE_ENDPOINTS[1]), format!("GET {}", SERVICE_ENDPOINTS[2])]
    );
    let calls = http.calls.lock().unwrap();
    assert!(calls.iter().all(|(_, r, _, t)| header(r, "User-Agent") == Some(BROWSER_USER_AGENT) && *t == SERVICE_TIMEOUT_MS));
    assert!(logs.iter().any(|l| l == &format!("WARP: config obtained from {}", SERVICE_ENDPOINTS[2])));
}

#[test]
fn without_generators_it_registers_through_the_mirror() {
    let http = FakeHttp::default().with(REG_URL, 403, "").with(REG_MIRROR_URL, 200, REGISTRATION);
    let (result, logs) = run(&http, false);
    let ini = result.unwrap();
    assert!(ini.contains("PrivateKey = bXlwcml2\n") && ini.contains("Reserved = 200, 7, 255") && ini.contains("PublicKey = bmV3cGVlcg=="));
    let urls = http.urls();
    assert_eq!(urls.len(), SERVICE_ENDPOINTS.len() + 2);
    assert_eq!(&urls[SERVICE_ENDPOINTS.len()..], &[format!("POST {}", REG_URL), format!("POST {}", REG_MIRROR_URL)]);
    assert!(logs.iter().any(|l| l.starts_with("WARP: all generators failed")));

    let calls = http.calls.lock().unwrap();
    let (_, official, body, timeout) = &calls[SERVICE_ENDPOINTS.len()];
    assert_eq!(*timeout, REG_TIMEOUT_MS);
    assert_eq!(calls[SERVICE_ENDPOINTS.len() + 1].3, REG_MIRROR_TIMEOUT_MS);
    assert_eq!(header(official, "User-Agent"), Some(CF_USER_AGENT));
    assert_eq!(header(official, "CF-Client-Version"), Some(CF_CLIENT_VERSION));
    assert_eq!(header(official, "Content-Type"), Some("application/json"));
    assert!(body.contains(r#""key":"bXlwdWI=""#));
}

#[test]
fn official_first_skips_the_generators_when_cloudflare_answers() {
    let http = FakeHttp::default().with(REG_URL, 200, REGISTRATION).with(SERVICE_ENDPOINTS[0], 200, ACCOUNT);
    let (result, _) = run(&http, true);
    assert!(result.unwrap().contains("Reserved = 200, 7, 255"));
    assert_eq!(http.urls(), vec![format!("POST {}", REG_URL)]);
}

#[test]
fn official_first_falls_back_to_the_generators() {
    let http = FakeHttp::default().with(SERVICE_ENDPOINTS[0], 200, ACCOUNT);
    let (result, logs) = run(&http, true);
    assert!(result.unwrap().contains("PrivateKey = cHJpdg=="));
    assert!(logs[0].starts_with("WARP: official Cloudflare registration failed"));
    assert_eq!(http.urls()[2], format!("GET {}", SERVICE_ENDPOINTS[0]));
}

#[test]
fn everything_down_is_an_error() {
    let http = FakeHttp::default().with(REG_MIRROR_URL, 403, "");
    let (result, _) = run(&http, false);
    assert_eq!(result.unwrap_err(), "Cloudflare WARP registration failed (HTTP 403)");

    let (result, _) = run(&FakeHttp::default(), false);
    assert!(result.unwrap_err().contains("no answer from"));
}

#[test]
fn a_broken_2xx_registration_is_not_retried_on_the_mirror() {
    let http = FakeHttp::default().with(REG_URL, 200, "{}").with(REG_MIRROR_URL, 200, REGISTRATION);
    let (result, _) = run(&http, true);
    // official_first failed with a parse error -> generators (all down) -> registration again.
    assert!(result.is_err());
    assert!(!http.urls().iter().any(|u| u.contains(REG_MIRROR_URL)));
}
