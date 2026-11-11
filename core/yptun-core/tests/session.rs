//! The «Сессия» sheet: exit lookup and speed test (no Kotlin test existed; behaviour follows
//! `SessionInfo.kt`).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::sleep;
use std::time::Duration;

use yptun_core::repository::{HttpFetcher, HttpRequest, HttpResponse};
use yptun_core::session::*;

const IPWHOIS: &str = r#"{
  "ip": "203.0.113.7",
  "success": true,
  "type": "IPv4",
  "country": "Finland",
  "country_code": "FI",
  "region": "Uusimaa",
  "city": "Helsinki",
  "latitude": 60.1699,
  "longitude": 24.9384,
  "connection": {"asn": 24940, "org": "Hetzner Online GmbH", "isp": "Hetzner", "domain": "hetzner.com"},
  "timezone": {"id": "Europe/Helsinki"}
}"#;

#[test]
fn lookup_answer_becomes_session_info() {
    let info = session_info_from_lookup(IPWHOIS, Some("203.0.113.7"), Some("2001:db8::7")).unwrap();

    assert_eq!(
        SessionInfo {
            ipv4: Some("203.0.113.7".into()),
            ipv6: Some("2001:db8::7".into()),
            asn: Some("24940".into()),
            provider: Some("Hetzner".into()),
            country: Some("Finland (FI)".into()),
            city: Some("Helsinki".into()),
            region: Some("Uusimaa".into()),
            latitude: Some("60.1699".into()),
            longitude: Some("24.9384".into()),
        },
        info
    );
}

#[test]
fn lookup_fills_the_gaps_from_the_answer() {
    // No probe results: the address the lookup saw lands in the matching family.
    let v6_answer = r#"{"ip": "2001:db8::1", "country_code": "DE", "connection": {"org": "Org only", "isp": " "}, "city": ""}"#;
    let info = session_info_from_lookup(v6_answer, None, None).unwrap();
    assert_eq!(None, info.ipv4);
    assert_eq!(Some("2001:db8::1".into()), info.ipv6);
    assert_eq!(Some("Org only".into()), info.provider);
    assert_eq!(Some("DE".into()), info.country);
    assert_eq!(None, info.city);
    assert_eq!(None, info.asn);

    let info =
        session_info_from_lookup(r#"{"ip": "198.51.100.2", "country": "Japan"}"#, None, None)
            .unwrap();
    assert_eq!(Some("198.51.100.2".into()), info.ipv4);
    assert_eq!(None, info.ipv6);
    assert_eq!(Some("Japan".into()), info.country);

    // Probe answers that are not addresses (an HTML error page) are ignored.
    let info = session_info_from_lookup(IPWHOIS, Some("<html>busy</html>"), Some("busy")).unwrap();
    assert_eq!(Some("203.0.113.7".into()), info.ipv4);
    assert_eq!(None, info.ipv6);
}

#[test]
fn failed_lookups_explain_themselves() {
    assert_eq!(
        Err("Reserved range".to_string()),
        session_info_from_lookup(
            r#"{"success": false, "message": "Reserved range"}"#,
            None,
            None
        )
    );
    assert_eq!(
        Err("lookup failed".to_string()),
        session_info_from_lookup(r#"{"success": false}"#, None, None)
    );
    assert_eq!(
        Err("lookup failed".to_string()),
        session_info_from_lookup(r#"["not", "an", "object"]"#, None, None)
    );
    assert!(session_info_from_lookup("<html>502</html>", None, None).is_err());
    assert!(session_info_from_lookup(r#"{"success": 1}"#, None, None).is_err());
    assert!(session_info_from_lookup(r#"{"city": {"name": "x"}}"#, None, None).is_err());
    assert!(
        session_info_from_lookup(r#"{"success": "true", "connection": 5}"#, None, None).is_ok()
    );
}

/// Canned bodies by URL; every request recorded. Unknown URL = no response.
#[derive(Default)]
struct FakeWeb {
    bodies: HashMap<String, String>,
    requests: Mutex<Vec<HttpRequest>>,
}

impl HttpFetcher for FakeWeb {
    fn get(&self, request: &HttpRequest) -> Option<HttpResponse> {
        self.requests.lock().unwrap().push(request.clone());
        self.bodies.get(&request.url).map(|body| HttpResponse {
            status: 200,
            headers: vec![],
            body: body.clone(),
        })
    }
}

fn web(pairs: &[(&str, &str)]) -> Arc<FakeWeb> {
    Arc::new(FakeWeb {
        bodies: pairs
            .iter()
            .map(|(url, body)| (url.to_string(), body.to_string()))
            .collect(),
        requests: Mutex::default(),
    })
}

#[test]
fn fetch_goes_through_the_tunnel_proxy() {
    let fake = web(&[
        (IPV4_LOOKUP_URL, " 203.0.113.7\n"),
        (IPV6_LOOKUP_URL, "2001:db8::7"),
        ("https://ipwho.is/203.0.113.7", IPWHOIS),
    ]);

    let info = fetch_session_info(fake.clone(), Some("socks5://127.0.0.1:10808")).unwrap();

    assert_eq!(Some("203.0.113.7".into()), info.ipv4);
    assert_eq!(Some("2001:db8::7".into()), info.ipv6);
    let requests = fake.requests.lock().unwrap().clone();
    let mut urls: Vec<&str> = requests.iter().map(|r| r.url.as_str()).collect();
    urls.sort();
    assert_eq!(
        vec![
            IPV4_LOOKUP_URL,
            IPV6_LOOKUP_URL,
            "https://ipwho.is/203.0.113.7"
        ],
        urls
    );
    assert!(requests
        .iter()
        .all(|r| r.proxy.as_deref() == Some("socks5://127.0.0.1:10808") && r.headers.is_empty()));
}

#[test]
fn fetch_without_ipv4_asks_about_the_arriving_address() {
    let fake = web(&[
        (IPV4_LOOKUP_URL, "rate limited"),
        (
            GEO_LOOKUP_URL,
            r#"{"ip": "2001:db8::9", "country": "Sweden", "country_code": "SE"}"#,
        ),
    ]);

    let info = fetch_session_info(fake.clone(), None).unwrap();

    assert_eq!(None, info.ipv4);
    assert_eq!(Some("2001:db8::9".into()), info.ipv6);
    assert_eq!(Some("Sweden (SE)".into()), info.country);
    assert!(fake
        .requests
        .lock()
        .unwrap()
        .iter()
        .any(|r| r.url == GEO_LOOKUP_URL && r.proxy.is_none()));

    let offline = web(&[]);
    assert_eq!(
        Err("ipwho.is did not answer".to_string()),
        fetch_session_info(offline, None)
    );
}

#[test]
fn rate_meter_skips_the_warm_up() {
    let timing = SpeedTestTiming::default();
    let mut meter = RateMeter::new(&timing);

    assert_eq!(None, meter.add(1_000, 100)); // still warming up: nothing reported
    assert_eq!(Some(0.01), meter.add(1_000, 1_600)); // warm-up ends here: the overall rate
    assert_eq!(None, meter.add(50_000, 1_700)); // < 250 ms since the last report
    assert_eq!(Some(1.0), meter.add(75_000, 2_600)); // 125 kB in the 1 s after the warm-up
    assert_eq!(127_000, meter.total_bytes());
    assert_eq!(0.5, meter.rate(3_600));
    assert_eq!(0.0, RateMeter::new(&timing).rate(0));
}

/// Cloudflare stand-in: tiny chunks with short pauses, uploads that take a moment.
struct FakeCloudflare {
    upload_status: u16,
    fail_ping: bool,
    downloads: AtomicUsize,
    uploads: AtomicUsize,
}

impl FakeCloudflare {
    fn new(upload_status: u16, fail_ping: bool) -> Self {
        Self {
            upload_status,
            fail_ping,
            downloads: AtomicUsize::new(0),
            uploads: AtomicUsize::new(0),
        }
    }
}

impl SpeedTestTransport for FakeCloudflare {
    fn download(
        &self,
        url: &str,
        proxy: Option<&str>,
        on_chunk: &mut dyn FnMut(usize) -> bool,
    ) -> Result<(), String> {
        assert_eq!(Some("socks5://127.0.0.1:1080"), proxy);
        if url == SPEED_PING_URL {
            sleep(Duration::from_millis(3));
            return if self.fail_ping {
                Err("timeout".into())
            } else {
                Ok(())
            };
        }
        assert_eq!(SPEED_DOWNLOAD_URL, url);
        self.downloads.fetch_add(1, Ordering::SeqCst);
        for _ in 0..40 {
            sleep(Duration::from_millis(1));
            if !on_chunk(16 * 1024) {
                break;
            }
        }
        Ok(())
    }

    fn upload(&self, url: &str, _proxy: Option<&str>, body: &[u8]) -> Result<u16, String> {
        assert_eq!(SPEED_UPLOAD_URL, url);
        assert_eq!(SPEED_UPLOAD_CHUNK, body.len());
        self.uploads.fetch_add(1, Ordering::SeqCst);
        sleep(Duration::from_millis(2));
        Ok(self.upload_status)
    }
}

const FAST: SpeedTestTiming = SpeedTestTiming {
    phase_ms: 150,
    warmup_ms: 20,
    streams: 3,
    report_interval_ms: 10,
};

fn run(transport: &FakeCloudflare, cancel_on: Option<SpeedPhase>) -> Vec<SpeedProgress> {
    let updates = Mutex::new(Vec::new());
    let cancel = AtomicBool::new(false);
    run_speed_test_with(
        FAST,
        transport,
        Some("socks5://127.0.0.1:1080"),
        &cancel,
        &|progress| {
            if Some(progress.phase) == cancel_on {
                cancel.store(true, Ordering::SeqCst);
            }
            updates.lock().unwrap().push(progress);
        },
    );
    updates.into_inner().unwrap()
}

#[test]
fn speed_test_runs_ping_download_upload() {
    let transport = FakeCloudflare::new(200, false);

    let updates = run(&transport, None);

    assert_eq!(
        SpeedProgress {
            phase: SpeedPhase::Ping,
            ..Default::default()
        },
        updates[0]
    );
    let phases: Vec<SpeedPhase> = updates.iter().map(|u| u.phase).collect();
    let mut order = phases.clone();
    order.dedup();
    assert_eq!(
        vec![
            SpeedPhase::Ping,
            SpeedPhase::Download,
            SpeedPhase::Upload,
            SpeedPhase::Done
        ],
        order
    );
    // Rates move during the phases, not only at their ends.
    assert!(
        updates
            .iter()
            .filter(|u| u.phase == SpeedPhase::Download && u.down_mbps.is_some())
            .count()
            >= 2
    );
    assert!(updates
        .iter()
        .any(|u| u.phase == SpeedPhase::Upload && u.up_mbps.is_some()));
    let done = updates.last().unwrap();
    assert!(done.ping_ms.unwrap() >= 3);
    assert!(done.down_mbps.unwrap() > 0.0);
    assert!(done.up_mbps.unwrap() > 0.0);
    // Several streams, and the 25 MB download is simply asked for again.
    assert!(transport.downloads.load(Ordering::SeqCst) >= 3);
    assert!(transport.uploads.load(Ordering::SeqCst) >= 3);
}

#[test]
fn speed_test_failures_keep_what_was_measured() {
    let rejected = FakeCloudflare::new(403, false);
    let updates = run(&rejected, None);
    let last = updates.last().unwrap();
    assert_eq!(SpeedPhase::Failed, last.phase);
    assert!(last.ping_ms.is_some());
    assert!(last.down_mbps.is_some());
    assert_eq!(None, last.up_mbps);

    let unreachable = FakeCloudflare::new(200, true);
    assert_eq!(
        vec![
            SpeedProgress {
                phase: SpeedPhase::Ping,
                ..Default::default()
            },
            SpeedProgress {
                phase: SpeedPhase::Failed,
                ..Default::default()
            },
        ],
        run(&unreachable, None)
    );
}

#[test]
fn cancelled_speed_test_stops_quietly() {
    let transport = FakeCloudflare::new(200, false);

    let updates = run(&transport, Some(SpeedPhase::Download));

    assert!(updates
        .iter()
        .all(|u| u.phase == SpeedPhase::Ping || u.phase == SpeedPhase::Download));
    assert_eq!(0, transport.uploads.load(Ordering::SeqCst));
}
