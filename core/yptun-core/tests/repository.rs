//! Ported from LocationsRepositoryImplTest (import, refresh, fetch headers, privacy, edits),
//! EmbeddedSingBoxRouteTest (import half) and JsonSubscriptionRoutingTest.

use std::sync::{Arc, Mutex};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use yptun_core::location::*;
use yptun_core::model::*;
use yptun_core::repository::*;
use yptun_core::settings::*;

#[derive(Default)]
struct State {
    stored: Option<LocationBundleV4>,
    legacy: Vec<(String, String)>,
    legacy_active: Option<String>,
    behavior: AppBehaviorSettings,
    values: std::collections::BTreeMap<String, String>,
    saves: i64,
}

#[derive(Clone, Default)]
struct FakeStore(Arc<Mutex<State>>);

impl FakeStore {
    fn with(stored: LocationBundleV4) -> Self {
        let s = Self::default();
        s.0.lock().unwrap().stored = Some(stored);
        s
    }
    fn stored(&self) -> LocationBundleV4 {
        self.0.lock().unwrap().stored.clone().expect("nothing saved")
    }
    fn set_behavior(&self, b: AppBehaviorSettings) {
        self.0.lock().unwrap().behavior = b;
    }
}

impl Store for FakeStore {
    fn load_bundle(&self) -> Option<LocationBundleV4> {
        self.0.lock().unwrap().stored.clone()
    }
    fn save_bundle(&self, bundle: &LocationBundleV4) {
        let mut s = self.0.lock().unwrap();
        s.stored = Some(bundle.clone());
        s.saves += 1;
    }
    fn bundle_version_token(&self) -> Option<i64> {
        Some(self.0.lock().unwrap().saves)
    }
    fn load_legacy_locations(&self) -> Vec<(String, String)> {
        self.0.lock().unwrap().legacy.clone()
    }
    fn load_legacy_active_id(&self) -> Option<String> {
        self.0.lock().unwrap().legacy_active.clone()
    }
    fn load_value(&self, key: &str) -> Option<String> {
        self.0.lock().unwrap().values.get(key).cloned()
    }
    fn save_value(&self, key: &str, value: &str) {
        self.0.lock().unwrap().values.insert(key.into(), value.into());
    }
    fn app_behavior(&self) -> AppBehaviorSettings {
        self.0.lock().unwrap().behavior.clone()
    }
}

type Handler = dyn Fn(&HttpRequest) -> HttpResponse + Send + Sync;

#[derive(Clone)]
struct FakeHttp {
    handler: Arc<Mutex<Box<Handler>>>,
    seen: Arc<Mutex<Vec<HttpRequest>>>,
}

impl FakeHttp {
    fn new(f: impl Fn(&HttpRequest) -> HttpResponse + Send + Sync + 'static) -> Self {
        Self { handler: Arc::new(Mutex::new(Box::new(f))), seen: Arc::default() }
    }
    fn body(body: &str) -> Self {
        let b = body.to_string();
        Self::new(move |_| ok(&b, &[]))
    }
    fn set(&self, f: impl Fn(&HttpRequest) -> HttpResponse + Send + Sync + 'static) {
        *self.handler.lock().unwrap() = Box::new(f);
    }
    fn seen(&self) -> Vec<HttpRequest> {
        self.seen.lock().unwrap().clone()
    }
}

impl HttpFetcher for FakeHttp {
    fn get(&self, request: &HttpRequest) -> Option<HttpResponse> {
        self.seen.lock().unwrap().push(request.clone());
        Some((self.handler.lock().unwrap())(request))
    }
}

fn ok(body: &str, headers: &[(&str, &str)]) -> HttpResponse {
    HttpResponse { status: 200, headers: headers.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(), body: body.into() }
}

fn header<'a>(r: &'a HttpRequest, name: &str) -> Option<&'a str> {
    r.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
}

const UA: &str = "YPtun/3.7.3";

fn client(hwid: &str) -> ClientInfo {
    ClientInfo { user_agent: UA.into(), app_version: "3.7.3".into(), hwid: hwid.into(), app_id: hwid.into(), device_os: "iOS".into(), os_version: "18.0".into(), device_model: "iPhone".into() }
}

fn repo(store: &FakeStore, http: &FakeHttp) -> Repository {
    Repository::with_clock(Box::new(store.clone()), Box::new(http.clone()), client("hwid-test"), Box::new(|| 1_000_000))
}

fn offline(store: &FakeStore) -> Repository {
    repo(store, &FakeHttp::new(|_| HttpResponse { status: 404, ..Default::default() }))
}

fn olc(name: &str, id: &str, key: char, provider: &str) -> LocationConfig {
    LocationConfig { name: name.into(), id: id.into(), key: key.to_string().repeat(64), bypass_provider: provider.into(), ..Default::default() }
}

fn bundle(active: &str, entries: Vec<LocationEntry>) -> LocationBundleV4 {
    LocationBundleV4 { active_location_id: Some(active.into()), locations: entries, ..Default::default() }
}

fn ids(b: &LocationBundleV4) -> Vec<String> {
    b.locations.iter().map(|e| e.storage_id.clone()).collect()
}

// ---------------- bundle / legacy ----------------

#[test]
fn exports_and_imports_bundle_v5_with_active_location() {
    let a = LocationEntry::from("amsterdam", &LocationConfig { key: "key-a".into(), ..olc("Amsterdam", "room-a", 'a', PROVIDER_JAZZ) }, None, None);
    let b = LocationEntry::from("berlin", &LocationConfig { key: "key-b".into(), ..olc("Berlin", "room-b", 'b', PROVIDER_TELEMOST) }, None, None);
    let exported = offline(&FakeStore::with(bundle("berlin", vec![a, b]))).export_bundle();
    assert!(exported.contains("\"version\": 5") && exported.contains("\"endpoint\"") && exported.contains("\"auth_provider\""), "{exported}");
    assert!(!exported.contains("\"client_id\"") && !exported.contains("\"bypass_provider\""));

    let target = FakeStore::default();
    assert!(offline(&target).import_text(&exported, None));
    let imported = target.stored();
    assert_eq!((imported.version, imported.active_location_id.as_deref()), (5, Some("berlin")));
    assert_eq!(ids(&imported), ["amsterdam", "berlin"]);
    assert_eq!(imported.locations[1].location().bypass_provider, PROVIDER_TELEMOST);
}

#[test]
fn migrates_legacy_locations_and_preserves_active_selection() {
    let store = FakeStore::default();
    {
        let mut s = store.0.lock().unwrap();
        s.legacy = vec![
            ("legacy_a".into(), r#"{"name":"A","server":"room-a","password":"key-a","provider":"jazz"}"#.into()),
            ("legacy_b".into(), r#"{"name":"B","server":"room-b","password":"key-b","turn":{"type":"wbstream"}}"#.into()),
        ];
        s.legacy_active = Some("legacy_b".into());
    }
    let b = offline(&store).bundle();
    assert_eq!(b.active_location_id.as_deref(), Some("legacy_b"));
    assert_eq!(ids(&b), ["legacy_a", "legacy_b"]);
    assert_eq!(b.locations[1].location().bypass_provider, PROVIDER_WB_STREAM);
    assert_eq!(store.stored(), b);
}

#[test]
fn wbstream_aliases_become_the_canonical_provider() {
    let raw = r#"{"active_location_id":"wb","locations":[{"storage_id":"wb","name":"WB","id":"room-wb","key":"key-wb","bypass_provider":"wbstream"}]}"#;
    let stored: LocationBundleV4 = serde_json::from_str(raw).unwrap();
    let active = offline(&FakeStore::with(stored)).active_location().unwrap();
    assert_eq!(active.location().bypass_provider, PROVIDER_WB_STREAM);

    let store = FakeStore::default();
    offline(&store).import_text(r#"{"version":3,"active_location_id":"wb","locations":[{"storage_id":"wb","name":"WB","id":"room-wb","key":"key-wb","bypass_provider":"wbstream"}]}"#, None);
    assert_eq!(store.stored().locations[0].location().bypass_provider, PROVIDER_WB_STREAM);
}

#[test]
fn imports_single_legacy_location_with_turn_provider() {
    let store = FakeStore::default();
    offline(&store).import_text(r#"{"hysteria":{"name":"Paris","server":"room-paris","password":"key-paris"},"turn":{"type":"telemost"}}"#, None);
    let b = store.stored();
    assert_eq!(b.active_location_id.as_deref(), Some("imported_paris"));
    assert_eq!(b.locations.len(), 1);
    assert_eq!((b.locations[0].location().id.as_str(), b.locations[0].location().bypass_provider.as_str()), ("room-paris", PROVIDER_TELEMOST));
}

// ---------------- olcRTC ----------------

#[test]
fn imports_olcrtc_uris() {
    let store = FakeStore::default();
    offline(&store).import_text(&format!("olcrtc://wbstream?seichannel@room-01#{}%android-01$RU / olc free sub / IPv6", "a".repeat(64)), None);
    let e = &store.stored().locations[0];
    let l = e.location();
    assert_eq!((l.bypass_provider.as_str(), l.transport.as_str(), l.id.as_str(), l.name.as_str()), (PROVIDER_WB_STREAM, TRANSPORT_SEICHANNEL, "room-01", "RU / olc free sub / IPv6"));
    assert_eq!(e.metadata.as_ref().and_then(|m| m.mimo.as_deref()), Some("RU / olc free sub / IPv6"));
    assert!(e.metadata.as_ref().unwrap().subscription.is_none());

    let store = FakeStore::default();
    offline(&store).import_text(&format!("olcrtc://jitsi?datachannel@https://meet.cryptopro.ru/myroom#{}$Jitsi room", "b".repeat(64)), None);
    let l = store.stored().locations[0].location();
    assert_eq!((l.bypass_provider.as_str(), l.transport.as_str(), l.id.as_str(), l.name.as_str()), (PROVIDER_JITSI, TRANSPORT_DATACHANNEL, "https://meet.cryptopro.ru/myroom", "Jitsi room"));
}

#[test]
fn imports_olcrtc_subscription_and_applies_local_names() {
    let input = format!(
        "#name: Test subscription\n#update: 1778011200\n#refresh: 10m\n#color: #4A90E2\n#icon: flag-ru\n#used: 10mb/10gb\n#available: 9.99gb\n\n\
         olcrtc://wbstream?seichannel@room-01#{}%android-01$RU / default name\n##name: RU-1\n##color: #4A90E2\n##icon: node-ru\n##used: 500mb/10gb\n##available: 9.5gb\n##ip: 203.0.113.10\n##comment: primary\n\n\
         olcrtc://jazz?datachannel@room-02#{}%android-02$DE / backup\n##name: DE-Backup",
        "a".repeat(64),
        "b".repeat(64)
    );
    let store = FakeStore::default();
    offline(&store).import_text(&input, None);
    let b = store.stored();
    assert_eq!(b.locations.iter().map(|e| e.location().name).collect::<Vec<_>>(), ["RU-1", "DE-Backup"]);
    assert_eq!(b.locations[1].location().bypass_provider, PROVIDER_JAZZ);
    assert_eq!(b.active_location_id.as_deref(), Some("imported_ru-1"));
    let m = b.locations[0].metadata.clone().unwrap();
    assert_eq!((m.name.as_deref(), m.color.as_deref(), m.icon.as_deref(), m.used.as_deref()), (Some("RU-1"), Some("#4A90E2"), Some("node-ru"), Some("500mb/10gb")));
    assert_eq!((m.available.as_deref(), m.ip.as_deref(), m.comment.as_deref(), m.mimo.as_deref()), (Some("9.5gb"), Some("203.0.113.10"), Some("primary"), Some("RU / default name")));
    let sub = m.subscription.clone().unwrap();
    assert_eq!((sub.name.as_deref(), sub.update.as_deref(), sub.refresh.as_deref(), sub.icon.as_deref()), (Some("Test subscription"), Some("1778011200"), Some("10m"), Some("flag-ru")));
    assert_eq!((sub.used.as_deref(), sub.available.as_deref(), sub.update_interval_hours), (Some("10mb/10gb"), Some("9.99gb"), Some(1)));
    assert_eq!(b.locations[1].metadata.as_ref().unwrap().subscription.as_ref().map(|s| &s.name), Some(&sub.name));
}

#[test]
fn imports_olcrtc_subscription_format_with_transport_parameters() {
    let key = "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799";
    let input = format!(
        "#name: Zarazaex Free RU\n#refresh: 10m\n\nolcrtc://wbstream?seichannel<fps=60&batch=64&frag=900&ack-ms=2000>@room-01#{key}$RU / olcng free sub / IPv6\n##name: RU-1\n\n\
         olcrtc://jitsi?videochannel<video-w=1080&video-h=1080&video-codec=tile&video-tile-rs=20>@https://meet.jit.si/room-02#{}$DE / backup\n##name: DE-Backup",
        "a".repeat(64)
    );
    let store = FakeStore::default();
    offline(&store).import_text(&input, None);
    let b = store.stored();
    let (sei, video) = (b.locations[0].location(), b.locations[1].location());
    assert_eq!((sei.name.as_str(), video.name.as_str()), ("RU-1", "DE-Backup"));
    let so = sei.sei_options();
    assert_eq!((so.fps, so.batch, so.fragment_size, so.ack_timeout_ms), (60, 64, 900, 2000));
    let vo = video.video_options();
    assert_eq!((vo.codec.as_str(), vo.width, vo.height, vo.tile_rs), ("tile", 1080, 1080, 20));
    assert_eq!(b.locations[0].metadata.as_ref().unwrap().subscription.as_ref().unwrap().update_interval_hours, Some(1));
    let again = FakeStore::default();
    offline(&again).import_text(&yptun_core::share::olcrtc_uri(&video), None);
    assert_eq!(again.stored().locations[0].location().video_options(), vo);
}

#[test]
fn imported_transport_survives_and_videochannel() {
    let store = FakeStore::default();
    offline(&store).import_text(&format!(r#"{{"version":3,"locations":[
        {{"storage_id":"telemost","name":"Telemost","id":"75047680642749","key":"{a}","bypass_provider":"telemost","transport":"datachannel"}},
        {{"storage_id":"wb","name":"WB","id":"room-wb","key":"{b}","bypass_provider":"wbstream","transport":"datachannel"}}]}}"#, a = "a".repeat(64), b = "b".repeat(64)), None);
    let b = store.stored();
    assert!(b.locations.iter().all(|e| e.location().transport == TRANSPORT_DATACHANNEL));

    let store = FakeStore::default();
    offline(&store).import_text(&format!(r#"{{"version":4,"active_location_id":"telemost-video","locations":[{{"storage_id":"telemost-video","name":"Telemost Video",
        "endpoint":{{"room_id":"75047680642749","key":"{}"}},"carrier":"telemost","transport":{{"type":"videochannel"}}}}]}}"#, "c".repeat(64)), None);
    let b = store.stored();
    assert_eq!(b.version, 5);
    assert_eq!((b.locations[0].location().bypass_provider.as_str(), b.locations[0].location().transport.as_str()), (PROVIDER_TELEMOST, TRANSPORT_VIDEOCHANNEL));
}

#[test]
fn import_updates_matching_storage_ids_and_appends_new_locations() {
    let store = FakeStore::with(bundle(
        "custom_paris",
        vec![
            LocationEntry::from("custom_paris", &olc("Paris", "room-old", 'a', PROVIDER_WB_STREAM), None, None),
            LocationEntry::from("custom_berlin", &olc("Berlin", "room-berlin", 'b', PROVIDER_TELEMOST), None, None),
        ],
    ));
    offline(&store).import_text(&format!(r#"{{"version":4,"active_location_id":"sub_wb","locations":[
        {{"storage_id":"custom_paris","name":"Paris updated","endpoint":{{"room_id":"room-new","key":"{c}","client_id":"phone-1"}},"carrier":"wbstream","transport":{{"type":"datachannel"}}}},
        {{"storage_id":"sub_wb","name":"WB sub","subscription_url":"https://example.com/sub.md","endpoint":{{"room_id":"room-sub","key":"{d}","client_id":"phone-2"}},"carrier":"wbstream","transport":{{"type":"vp8channel"}}}}]}}"#,
        c = "c".repeat(64), d = "d".repeat(64)), None);
    let b = store.stored();
    assert_eq!(ids(&b), ["custom_paris", "custom_berlin", "sub_wb"]);
    assert_eq!((b.locations[0].location().id.as_str(), b.locations[1].location().id.as_str()), ("room-new", "room-berlin"));
    assert_eq!(b.locations[2].subscription_url.as_deref(), Some("https://example.com/sub.md"));
    assert_eq!(b.active_location_id.as_deref(), Some("sub_wb"));
}

#[test]
fn invalid_location_cannot_become_active() {
    let store = FakeStore::default();
    offline(&store).save_location("broken", &LocationConfig { name: "Broken".into(), id: "room".into(), ..Default::default() });
    let b = store.stored();
    assert!(b.active_location_id.is_none() && b.locations.is_empty());
}

#[test]
fn additive_import_keeps_selection_restore_carries_its_own() {
    let start = || bundle("keep_me", vec![LocationEntry::from("keep_me", &olc("Running", "room-live", 'a', PROVIDER_WB_STREAM), None, None)]);
    let store = FakeStore::with(start());
    offline(&store).import_text(&format!("olcrtc://wbstream?seichannel@room-99#{}$Pasted", "b".repeat(64)), None);
    let b = store.stored();
    assert_eq!((b.locations.len(), b.active_location_id.as_deref()), (2, Some("keep_me")));

    let store = FakeStore::with(start());
    offline(&store).import_text(&format!(r#"{{"version":4,"active_location_id":"keep_me","locations":[{{"storage_id":"keep_me","name":"Restored",
        "endpoint":{{"room_id":"room-restored","key":"{}","client_id":"desktop"}},"carrier":"wbstream","transport":{{"type":"datachannel"}}}}]}}"#, "b".repeat(64)), None);
    let b = store.stored();
    assert_eq!((b.active_location_id.as_deref(), b.locations.len()), (Some("keep_me"), 1));
    assert_eq!(b.locations[0].location().id, "room-restored");
}

#[test]
fn single_profile_import_does_not_overwrite_an_existing_id() {
    let store = FakeStore::with(bundle("imported_room-01", vec![LocationEntry::from("imported_room-01", &olc("Old", "room-old", 'a', PROVIDER_WB_STREAM), None, None)]));
    offline(&store).import_text(&format!("olcrtc://wbstream?seichannel@room-01#{}$New", "b".repeat(64)), None);
    let b = store.stored();
    assert_eq!(ids(&b), ["imported_room-01", "imported_new"]);
    assert_eq!(b.active_location_id.as_deref(), Some("imported_room-01"));
}

#[test]
fn imports_a_wrapped_yptun_inbound_link() {
    let link = yptun_core::panel::compose_inbound(&olc("Wrapped", "room-wrapped", 'd', PROVIDER_WB_STREAM));
    let wrapped: Vec<String> = link.as_bytes().chunks(40).map(|c| String::from_utf8(c.to_vec()).unwrap()).collect();
    let store = FakeStore::default();
    assert!(offline(&store).import_text(&wrapped.join("\n"), None));
    assert_eq!(store.stored().locations[0].location().id, "room-wrapped");
}

// ---------------- fetching ----------------

#[test]
fn subscription_header_sets_interval_and_identity_headers() {
    let http = FakeHttp::new(|_| ok(&format!("olcrtc://wbstream?vp8channel@room#{}$Sub", "c".repeat(64)), &[("profile-update-interval", "6")]));
    let store = FakeStore::default();
    assert!(repo(&store, &http).import_text("https://example.test/sub.txt", None));
    let first = &http.seen()[0];
    assert_eq!((header(first, "User-Agent"), header(first, "x-hwid")), (Some(UA), Some("hwid-test")));
    assert_eq!(header(first, "x-device-model"), Some("iPhone"));
    let e = &store.stored().locations[0];
    assert_eq!(e.metadata.as_ref().unwrap().subscription.as_ref().unwrap().update_interval_hours, Some(6));
    assert_eq!(e.subscription_url.as_deref(), Some("https://example.test/sub.txt"));
    let sub = e.metadata.as_ref().unwrap().subscription.as_ref().unwrap();
    assert_eq!((sub.last_refresh_at_epoch_ms, sub.last_attempt_at_epoch_ms), (Some(1_000_000), Some(1_000_000)));
}

#[test]
fn import_falls_back_when_identity_response_is_not_config() {
    let http = FakeHttp::new(|r| {
        if header(r, "x-hwid").is_some() {
            ok("<html>blocked</html>", &[])
        } else {
            ok(&format!("\u{FEFF}olcrtc://wbstream?vp8channel@room#{}$Fallback", "d".repeat(64)), &[("profile-update-interval", "12")])
        }
    });
    let store = FakeStore::default();
    assert!(repo(&store, &http).import_text("http://example.test/sub.txt", None));
    let seen = http.seen();
    assert_eq!(header(&seen[0], "x-hwid"), Some("hwid-test"));
    let retry = seen.iter().position(|r| header(r, "x-hwid").is_none()).unwrap();
    assert!(retry > 0);
    assert_eq!(header(&seen[retry], "User-Agent"), Some(UA));
    let e = &store.stored().locations[0];
    assert_eq!(e.location().id, "room");
    assert_eq!(e.metadata.as_ref().unwrap().subscription.as_ref().unwrap().update_interval_hours, Some(12));
}

#[test]
fn panel_headers_and_info_endpoint_fill_subscription_metadata() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let title = format!("base64:{}", base64::engine::general_purpose::STANDARD.encode("Моя панель"));
    let http = FakeHttp::new(move |r| {
        if r.url.ends_with("/info") {
            ok(r#"{"response":{"user":{"expiresAt":"2099-05-03T20:59:00.000Z","trafficUsed":"1.5 GiB","trafficLimit":"0"}}}"#, &[])
        } else {
            ok(link, &[("profile-title", &title), ("subscription-userinfo", "upload=0; download=1073741824; total=0; expire=0"), ("support-url", "https://t.me/support"), ("announce", "Привет"), ("profile-icon", "https://x.test/i.png")])
        }
    });
    let store = FakeStore::default();
    repo(&store, &http).import_text("https://panel.test/sub/abc", None);
    let sub = store.stored().locations[0].metadata.clone().unwrap().subscription.unwrap();
    assert_eq!((sub.name.as_deref(), sub.used.as_deref(), sub.available.as_deref()), (Some("Моя панель"), Some("1.5 GiB"), Some("∞")));
    assert_eq!(sub.expires_at_epoch_ms, yptun_core::isotime::parse_iso_to_epoch_ms(Some("2099-05-03T20:59:00Z")));
    assert_eq!((sub.support_url.as_deref(), sub.announce.as_deref(), sub.icon_url.as_deref()), (Some("https://t.me/support"), Some("Привет"), Some("https://x.test/i.png")));
}

#[test]
fn unsupported_client_stub_falls_back_to_happ() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let http = FakeHttp::new(move |r| {
        if header(r, "User-Agent") == Some(HAPP_USER_AGENT) {
            ok(link, &[])
        } else {
            ok("vless://x@stub.test:1#Подписка не поддерживается для этого клиента", &[])
        }
    });
    let store = FakeStore::default();
    assert!(repo(&store, &http).import_text("https://panel.test/sub", None));
    assert_eq!(store.stored().locations[0].location().proxy.unwrap().server, "a.test");
}

fn subscription_requests(behavior: AppBehaviorSettings) -> Vec<(String, Option<String>)> {
    let http = FakeHttp::body("vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A");
    let store = FakeStore::default();
    store.set_behavior(behavior);
    Repository::with_clock(Box::new(store.clone()), Box::new(http.clone()), client("real-hwid"), Box::new(|| 1)).import_text("https://example.test/sub", None);
    // The cosmetic branding-logo lookup presents as a browser; it is not a subscription request.
    http.seen().iter().filter(|r| !header(r, "User-Agent").unwrap_or("").starts_with("Mozilla")).map(|r| (header(r, "User-Agent").unwrap_or("").to_string(), header(r, "x-hwid").map(str::to_string))).collect()
}

#[test]
fn user_agent_modes_and_hwid_privacy() {
    let uas: Vec<String> = subscription_requests(AppBehaviorSettings::default()).into_iter().map(|r| r.0).collect();
    assert!(uas.iter().any(|u| u.starts_with("Happ")) && uas.iter().any(|u| !u.starts_with("Happ")), "{uas:?}");
    let legacy: Vec<String> = subscription_requests(AppBehaviorSettings { subscription_user_agent: SUB_UA_YPTUN.into(), ..Default::default() }).into_iter().map(|r| r.0).collect();
    assert!(legacy.iter().any(|u| u.starts_with("Happ")));
    let only: Vec<String> = subscription_requests(AppBehaviorSettings { subscription_user_agent: SUB_UA_YPTUN_ONLY.into(), ..Default::default() }).into_iter().map(|r| r.0).collect();
    assert!(!only.is_empty() && only.iter().all(|u| !u.starts_with("Happ")));
    let tun: Vec<String> = subscription_requests(AppBehaviorSettings { subscription_user_agent: SUB_UA_V2RAYTUN.into(), ..Default::default() }).into_iter().map(|r| r.0).collect();
    assert!(!tun.is_empty() && tun.iter().all(|u| u == "v2raytun/ios"), "{tun:?}");
    let happ: Vec<String> = subscription_requests(AppBehaviorSettings { subscription_user_agent: SUB_UA_HAPP.into(), ..Default::default() }).into_iter().map(|r| r.0).collect();
    assert!(!happ.is_empty() && happ.iter().all(|u| u == "Happ/1.0"));

    let real: Vec<String> = subscription_requests(AppBehaviorSettings::default()).into_iter().filter_map(|r| r.1).collect();
    assert!(!real.is_empty() && real.iter().all(|h| h == "real-hwid"));
    let none = subscription_requests(AppBehaviorSettings { send_device_info_to_subscriptions: false, ..Default::default() });
    assert!(!none.is_empty() && none.iter().all(|r| r.1.is_none()));
}

#[test]
fn provider_report_only_while_the_switch_is_on() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let http = FakeHttp::new(move |r| if r.url.contains("check.happ-proxy.com") { ok("ok", &[]) } else { ok(link, &[("providerid", "prov-1")]) });
    let store = FakeStore::default();
    let repo = repo(&store, &http);
    repo.import_text("https://example.test/sub", None);
    let checks = || http.seen().iter().filter(|r| r.url.contains("check.happ-proxy.com")).count();
    store.set_behavior(AppBehaviorSettings { provider_report_enabled: false, ..Default::default() });
    repo.report_provider_usage();
    assert_eq!(checks(), 0);
    store.set_behavior(AppBehaviorSettings::default());
    repo.report_provider_usage();
    assert_eq!(checks(), 1);
    repo.report_provider_usage();
    assert_eq!(checks(), 1, "once per day");
    assert!(http.seen().iter().any(|r| r.url == "https://check.happ-proxy.com/provider?id=prov-1"));
}

// ---------------- refresh ----------------

fn sub_entry(id: &str, cfg: LocationConfig, url: &str) -> LocationEntry {
    LocationEntry::from(id, &cfg, Some(url.into()), None)
}

#[test]
fn refresh_single_subscription_preserves_others() {
    let store = FakeStore::with(bundle(
        "beta",
        vec![
            sub_entry("alpha", olc("Alpha", "room-alpha", 'a', PROVIDER_WB_STREAM), "https://example.test/alpha"),
            sub_entry("beta", olc("Beta", "room-beta", 'b', PROVIDER_WB_STREAM), "https://example.test/beta"),
        ],
    ));
    let http = FakeHttp::body(&format!("olcrtc://wbstream?vp8channel@room-alpha-new#{}$Alpha", "c".repeat(64)));
    assert_eq!(repo(&store, &http).refresh_subscription("https://example.test/alpha", None), 1);
    let b = store.stored();
    assert_eq!(ids(&b), ["beta", "imported_alpha"]);
    assert_eq!(b.locations[1].location().id, "room-alpha-new");
    assert_eq!(b.active_location_id.as_deref(), Some("beta"));
    assert!(http.seen().iter().all(|r| !r.url.contains("/beta")));
}

#[test]
fn failed_refresh_keeps_the_subscription_and_stamps_the_attempt() {
    let store = FakeStore::with(bundle("alpha", vec![sub_entry("alpha", olc("Alpha", "room-alpha", 'a', PROVIDER_WB_STREAM), "https://example.test/alpha")]));
    let http = FakeHttp::body("<html>not a config</html>");
    assert_eq!(repo(&store, &http).refresh_subscription("https://example.test/alpha", None), 0);
    let b = store.stored();
    assert_eq!(ids(&b), ["alpha"]);
    assert_eq!(b.locations[0].location().id, "room-alpha");
    assert_eq!(b.active_location_id.as_deref(), Some("alpha"));
    assert_eq!(b.locations[0].metadata.as_ref().unwrap().subscription.as_ref().unwrap().last_attempt_at_epoch_ms, Some(1_000_000));
}

#[test]
fn refresh_keeps_selected_server_when_it_still_exists() {
    let url = "https://example.test/multi";
    let vp8 = |n: &str, r: &str, k: char| LocationConfig { transport: TRANSPORT_VP8CHANNEL.into(), ..olc(n, r, k, PROVIDER_WB_STREAM) };
    let store = FakeStore::with(bundle("srv-b", vec![sub_entry("srv-a", vp8("A", "room-a", 'a'), url), sub_entry("srv-b", vp8("B", "room-b", 'b'), url)]));
    let http = FakeHttp::body(&format!("olcrtc://wbstream?vp8channel@room-a#{}$A\nolcrtc://wbstream?vp8channel@room-b#{}$B", "a".repeat(64), "b".repeat(64)));
    assert_eq!(repo(&store, &http).refresh_subscription(url, None), 1);
    let b = store.stored();
    let mut got = ids(&b);
    got.sort();
    assert_eq!(got, ["srv-a", "srv-b"]);
    assert_eq!(b.active_location_id.as_deref(), Some("srv-b"));
}

#[test]
fn growing_subscription_keeps_every_server_and_its_id() {
    let link = |h: &str, n: &str| format!("vless://11111111-2222-3333-4444-555555555555@{h}:443?type=tcp&security=tls#{n}");
    let http = FakeHttp::body(&[link("a.test", "Рига"), link("b.test", "Хельсинки")].join("\n"));
    let store = FakeStore::default();
    let r = repo(&store, &http);
    let url = "https://example.test/grow";
    r.import_text(url, None);
    let riga = store.stored().locations.iter().find(|e| e.name == "Рига").unwrap().storage_id.clone();
    let body2 = [link("a.test", "Рига"), link("b.test", "Хельсинки"), link("c.test", "Стокгольм")].join("\n");
    http.set(move |_| ok(&body2, &[]));
    r.refresh_subscription(url, None);
    let body3 = [link("d.test", "Алматы"), link("a.test", "Рига"), link("b.test", "Хельсинки"), link("c.test", "Стокгольм"), link("e.test", "Нюрнберг")].join("\n");
    http.set(move |_| ok(&body3, &[]));
    r.refresh_subscription(url, None);
    let b = store.stored();
    assert_eq!(b.locations.iter().map(|e| e.name.clone()).collect::<Vec<_>>(), ["Алматы", "Рига", "Хельсинки", "Стокгольм", "Нюрнберг"]);
    let mut uniq = ids(&b);
    uniq.dedup();
    assert_eq!(uniq.len(), 5);
    assert_eq!(b.locations.iter().find(|e| e.name == "Рига").unwrap().storage_id, riga);
}

#[test]
fn freeturn_subscription_keeps_every_server_and_its_id() {
    let conf = "[Interface]\nPrivateKey = QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMjM0NTY3OD0=\nAddress = 10.7.3.2/32\n\n[Peer]\nPublicKey = cGVlcl9wdWJsaWNfa2V5X2Jhc2U2NF8zMl9ieXRlc19vaz0=\nEndpoint = 127.0.0.1:9000\nAllowedIPs = 0.0.0.0/0";
    let wg = URL_SAFE_NO_PAD.encode(conf);
    let link = move |ip: &str, n: &str| format!("freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg={wg}>@{ip}:56000#deadbeef${n}");
    let http = FakeHttp::body(&[link("198.51.100.1", "Нода 1"), link("198.51.100.2", "Нода 2")].join("\n"));
    let store = FakeStore::default();
    let r = repo(&store, &http);
    let url = "https://example.test/freeturn-grow";
    r.import_text(url, None);
    let node1 = store.stored().locations.iter().find(|e| e.name == "Нода 1").unwrap().storage_id.clone();
    let next = [link("198.51.100.3", "Нода 0"), link("198.51.100.1", "Нода 1 (NL)"), link("198.51.100.2", "Нода 2")].join("\n");
    http.set(move |_| ok(&next, &[]));
    r.refresh_subscription(url, None);
    let b = store.stored();
    assert_eq!(b.locations.iter().map(|e| e.name.clone()).collect::<Vec<_>>(), ["Нода 0", "Нода 1 (NL)", "Нода 2"]);
    assert_eq!(b.locations.iter().find(|e| e.name == "Нода 1 (NL)").unwrap().storage_id, node1);
}

#[test]
fn due_subscriptions_respect_interval_and_auto_update() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let http = FakeHttp::body(link);
    let store = FakeStore::default();
    let now = Arc::new(Mutex::new(1_000_000i64));
    let clock = now.clone();
    let r = Repository::with_clock(Box::new(store.clone()), Box::new(http.clone()), client("h"), Box::new(move || *clock.lock().unwrap()));
    r.import_text("https://example.test/sub", None);
    assert_eq!(r.refresh_due_subscriptions(false, None), 0, "just imported");
    *now.lock().unwrap() += 25 * 3_600_000;
    assert_eq!(r.refresh_due_subscriptions(false, None), 1);
    r.set_subscription_auto_update("https://example.test/sub", false);
    *now.lock().unwrap() += 25 * 3_600_000;
    assert_eq!(r.refresh_due_subscriptions(false, None), 0);
}

// ---------------- JSON subscriptions ----------------

#[test]
fn json_subscription_routing_is_kept_for_every_server() {
    let link = "vless://732c8764-e31d-49ab-852b-54cb0f7cc3de@spb.example.test:443?type=tcp&security=tls&sni=spb.example.test#SPB";
    let json = r#"[{ "remarks": "SPB",
      "dns": { "hosts": { "regexp:(^|\\.)ru$": "198.18.0.2" }, "servers": ["1.1.1.1"] },
      "routing": { "domainStrategy": "IPOnDemand", "rules": [
        { "type": "field", "ip": ["198.18.0.0/15"], "outboundTag": "direct" },
        { "type": "field", "protocol": ["bittorrent"], "outboundTag": "block" } ] },
      "outbounds": [
        { "tag": "proxy", "protocol": "vless", "settings": { "vnext": [ { "address": "spb.example.test", "port": 443,
          "users": [ { "id": "732c8764-e31d-49ab-852b-54cb0f7cc3de", "encryption": "none" } ] } ] },
          "streamSettings": { "network": "tcp", "security": "tls" } },
        { "tag": "direct", "protocol": "freedom" }, { "tag": "block", "protocol": "blackhole" } ] }]"#;
    let http = FakeHttp::new(move |r| if header(r, "User-Agent").is_some_and(|u| u.starts_with("Happ")) { ok(json, &[]) } else { ok(link, &[]) });
    let store = FakeStore::default();
    repo(&store, &http).import_text("https://example.test/panel", None);
    let e = &store.stored().locations[0];
    assert_eq!(e.name, "SPB");
    assert_eq!(e.location().core, ProxyCore::Xray);
    let raw = e.proxy.as_ref().unwrap().raw_xray_config.clone().unwrap();
    assert!(raw.contains("bittorrent") && raw.contains("198.18.0.0/15"));
}

const SINGBOX_CONFIG: &str = r#"{
  "dns": { "servers": [ { "tag": "google", "address": "tls://8.8.8.8" } ] },
  "inbounds": [ { "type": "tun", "tag": "tun-in" } ],
  "outbounds": [
    { "type": "vless", "tag": "vless-out", "server": "1.2.3.4", "server_port": 443, "uuid": "11111111-2222-3333-4444-555555555555" },
    { "type": "direct", "tag": "direct-out" }, { "type": "block", "tag": "block-out" }, { "type": "dns", "tag": "dns-out" } ],
  "route": { "final": "vless-out", "rules": [
      { "protocol": "dns", "outbound": "dns-out" }, { "action": "sniff" }, { "inbound": ["tun-in"], "outbound": "vless-out" },
      { "rule_set": "geosite-ru", "outbound": "direct-out" }, { "domain_suffix": ["ads.example"], "outbound": "block-out" },
      { "domain": ["mail.ru"], "outbound": "vless-out" } ],
    "rule_set": [ { "type": "remote", "tag": "geosite-ru", "format": "binary", "url": "https://example.invalid/geosite-ru.srs", "download_detour": "direct-out" } ] }
}"#;

#[test]
fn singbox_import_carries_its_route_and_remaps_tags() {
    let store = FakeStore::default();
    assert!(offline(&store).import_text(SINGBOX_CONFIG, None));
    let p = store.stored().locations[0].proxy.clone().unwrap();
    let route: serde_json::Value = serde_json::from_str(&p.raw_singbox_route.unwrap()).unwrap();
    let rules = route["rules"].as_array().unwrap();
    assert_eq!(rules.len(), 3);
    assert_eq!((rules[0]["outbound"].as_str(), rules[0]["rule_set"].as_str()), (Some("direct"), Some("geosite-ru")));
    assert_eq!(rules[1]["action"], "reject");
    assert!(rules[1].get("outbound").is_none());
    assert_eq!(rules[2]["outbound"], "proxy");
    assert_eq!(route["final"], "proxy");
    assert_eq!(route["rule_set"][0]["download_detour"], "direct");
}

#[test]
fn singbox_import_keeps_its_fakeip_pool() {
    let fake = |dns: &str| {
        let text = format!(r#"{{ "dns": {dns}, "outbounds": [ {{ "type": "vless", "tag": "v", "server": "1.2.3.4", "server_port": 443, "uuid": "u" }} ] }}"#);
        let store = FakeStore::default();
        assert!(offline(&store).import_text(&text, None), "{text}");
        store.stored().locations[0].location().fake_dns
    };
    let legacy = fake(r#"{ "fakeip": { "enabled": true, "inet4_range": "198.19.0.0/16" } }"#).unwrap();
    assert_eq!((legacy.inet4_range.as_str(), legacy.inet6_range.as_str()), ("198.19.0.0/16", "fc00::/18"));
    assert_eq!(fake(r#"{ "servers": [ { "type": "fakeip", "tag": "f", "inet4_range": "198.18.0.0/15" } ] }"#).unwrap().inet4_range, "198.18.0.0/15");
    assert!(fake(r#"{ "fakeip": { "enabled": false, "inet4_range": "198.19.0.0/16" } }"#).is_none());
    assert!(fake(r#"{ "servers": [ { "tag": "g", "address": "tls://8.8.8.8" } ] }"#).is_none());
}

#[test]
fn encrypted_subscription_lines_are_locked_and_left_out_of_the_backup() {
    let enc = yptun_core::crypto::encrypt_subscription_line("vless://11111111-2222-3333-4444-555555555555@hidden.test:443?type=tcp&security=tls#Hidden", [3; 12]);
    let store = FakeStore::default();
    let r = offline(&store);
    assert!(r.import_text(&enc, None));
    let e = &store.stored().locations[0];
    assert!(e.metadata.as_ref().unwrap().locked);
    assert!(!r.export_bundle().contains("hidden.test"));
}

#[test]
fn desc_and_order_lines_from_the_panel() {
    let link = |h: &str, n: &str| format!("vless://11111111-2222-3333-4444-555555555555@{h}:443?type=tcp&security=tls#{n}");
    let text = format!(
        "#order:B\n#order:A\n#order:Olc\n#desc:A=%D0%91%D1%8B%D1%81%D1%82%D1%80%D1%8B%D0%B9\n{}\nolcrtc://wbstream?vp8channel@room#{}$Olc\n{}",
        link("a.test", "A"),
        "a".repeat(64),
        link("b.test", "B")
    );
    let store = FakeStore::default();
    offline(&store).import_text(&text, None);
    let b = store.stored();
    assert_eq!(b.locations.iter().map(|e| e.name.clone()).collect::<Vec<_>>(), ["B", "A", "Olc"]);
    assert_eq!(b.locations[1].description, "Быстрый");
}

// ---------------- edits ----------------

#[test]
fn subscription_url_can_be_changed() {
    let http = FakeHttp::body("vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A");
    let store = FakeStore::default();
    let r = repo(&store, &http);
    r.import_text("https://one.test/sub", None);
    r.import_text("https://two.test/sub", None);
    let before = {
        let mut v = ids(&store.stored());
        v.sort();
        v
    };
    assert!(r.change_subscription_url("https://one.test/sub", "https://moved.test/sub"));
    let urls: Vec<String> = store.stored().locations.iter().filter_map(|e| e.subscription_url.clone()).collect();
    assert!(urls.contains(&"https://moved.test/sub".into()) && !urls.contains(&"https://one.test/sub".into()) && urls.contains(&"https://two.test/sub".into()));
    let mut after = ids(&store.stored());
    after.sort();
    assert_eq!(before, after);
    assert!(!r.change_subscription_url("https://moved.test/sub", "ftp://x.test/sub"));
    assert!(!r.change_subscription_url("https://moved.test/sub", "https://moved.test/sub"));
    assert!(!r.change_subscription_url("https://moved.test/sub", "https://two.test/sub"));
    assert!(!r.change_subscription_url("https://nowhere.test/sub", "https://new.test/sub"));
}

#[test]
fn location_pin_survives_and_clears() {
    let http = FakeHttp::body("vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A");
    let store = FakeStore::default();
    let r = repo(&store, &http);
    r.import_text("https://one.test/sub", None);
    let id = store.stored().locations[0].storage_id.clone();
    r.set_location_pinned(&id, true);
    assert!(store.stored().locations[0].metadata.as_ref().unwrap().pinned);
    r.set_location_pinned(&id, false);
    assert!(!store.stored().locations[0].metadata.as_ref().unwrap().pinned);
    r.set_location_pinned("nope", true);
    assert!(store.stored().locations.iter().all(|e| !e.metadata.as_ref().unwrap().pinned));
}

#[test]
fn subscription_cascade_and_vk_link_survive_a_refresh() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let http = FakeHttp::body(link);
    let store = FakeStore::default();
    let r = repo(&store, &http);
    let url = "https://one.test/sub";
    r.import_text(url, None);
    assert!(!r.set_subscription_cascade(url, "garbage"));
    assert!(r.set_subscription_cascade(url, "trojan://p@exit.test:443?security=tls#Exit"));
    r.set_subscription_custom_name(url, "Мой VPN");
    r.refresh_subscription(url, None);
    let e = &store.stored().locations[0];
    assert_eq!(e.proxy2.as_ref().unwrap().server, "exit.test");
    let sub = e.metadata.as_ref().unwrap().subscription.as_ref().unwrap();
    assert_eq!(sub.custom_name.as_deref(), Some("Мой VPN"));
    assert!(r.set_subscription_cascade(url, ""));
    assert!(store.stored().locations[0].proxy2.is_none());
}

#[test]
fn branding_logo_is_looked_up_once_with_the_page_cookie() {
    let link = "vless://11111111-2222-3333-4444-555555555555@a.test:443?type=tcp&security=tls#A";
    let http = FakeHttp::new(move |r| {
        if r.url.ends_with("/assets/.app-config-v2.json") {
            assert_eq!(header(r, "Cookie"), Some("sid=abc"));
            ok(r#"{"brandingSettings":{"logoUrl":"https://cdn.test/logo.png"}}"#, &[])
        } else if r.url == "https://panel.test/page" {
            ok("<html></html>", &[("Set-Cookie", "sid=abc; Path=/; HttpOnly")])
        } else {
            ok(link, &[("profile-web-page-url", "https://panel.test/page")])
        }
    });
    let store = FakeStore::default();
    let r = repo(&store, &http);
    r.import_text("https://panel.test/sub/1", None);
    let sub = store.stored().locations[0].metadata.clone().unwrap().subscription.unwrap();
    assert_eq!(sub.icon_url.as_deref(), Some("https://cdn.test/logo.png"));
    let lookups = || http.seen().iter().filter(|r| r.url.ends_with(".app-config-v2.json")).count();
    assert_eq!(lookups(), 1);
    r.refresh_subscription("https://panel.test/sub/1", None);
    assert_eq!(lookups(), 1, "remembered per page");
}
