//! Ported from MixedSubscriptionImportTest and SubscriptionCascadeTest: a subscription that mixes
//! link families imports every link (issue #39: the first matching parser used to win the whole
//! body), and a cascade set for a whole subscription lands in every server, survives a refresh and
//! can be removed.

use std::sync::{Arc, Mutex};

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use yptun_core::location::*;
use yptun_core::model::EngineType;
use yptun_core::repository::*;

#[derive(Clone, Default)]
struct FakeStore(Arc<Mutex<Option<LocationBundleV4>>>);

impl FakeStore {
    fn stored(&self) -> LocationBundleV4 {
        self.0.lock().unwrap().clone().expect("nothing imported")
    }
}

impl Store for FakeStore {
    fn load_bundle(&self) -> Option<LocationBundleV4> {
        self.0.lock().unwrap().clone()
    }
    fn save_bundle(&self, bundle: &LocationBundleV4) {
        *self.0.lock().unwrap() = Some(bundle.clone());
    }
}

/// Answers every request with the current body (the panel of the test).
#[derive(Clone, Default)]
struct Panel(Arc<Mutex<String>>);

impl Panel {
    fn new(body: &str) -> Self {
        Self(Arc::new(Mutex::new(body.into())))
    }
}

impl HttpFetcher for Panel {
    fn get(&self, _: &HttpRequest) -> Option<HttpResponse> {
        let body = self.0.lock().unwrap().clone();
        Some(HttpResponse {
            status: 200,
            headers: vec![],
            body,
        })
    }
}

fn repo(store: &FakeStore, panel: &Panel) -> Repository {
    let client = ClientInfo {
        hwid: "hwid-test".into(),
        app_id: "hwid-test".into(),
        ..Default::default()
    };
    Repository::new(Box::new(store.clone()), Box::new(panel.clone()), client)
}

/// Imports a pasted `text` into an empty store and returns what was saved.
fn import(text: &str) -> LocationBundleV4 {
    let store = FakeStore::default();
    repo(&store, &Panel::default()).import_text(text, None);
    store.stored()
}

fn names(b: &LocationBundleV4) -> Vec<String> {
    b.locations
        .iter()
        .map(|e| e.location().display_name())
        .collect()
}

// ---------------- mixed families ----------------

fn olcrtc_link() -> String {
    format!(
        "olcrtc://wbstream?seichannel@room-01#{}%android-01$OLC room",
        "a".repeat(64)
    )
}

const VLESS: &str =
    "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#Amsterdam";
const SECOND_VLESS: &str =
    "vless://22222222-3333-4444-5555-666666666666@example.org:8443?security=tls&type=tcp#Berlin";

const WG_CONF: &str = "[Interface]
PrivateKey = QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMjM0NTY3OD0=
Address = 10.7.3.2/32
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = cGVlcl9wdWJsaWNfa2V5X2Jhc2U2NF8zMl9ieXRlc19vaz0=
Endpoint = 127.0.0.1:9000
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25";

fn freeturn_link(ip: &str, name: &str) -> String {
    let wg = URL_SAFE_NO_PAD.encode(WG_CONF);
    format!("freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg={wg}>@{ip}:56000#deadbeef${name}")
}

#[test]
fn imports_both_olcrtc_and_vless_from_one_subscription() {
    let imported = import(&format!("{}\n{VLESS}\n{SECOND_VLESS}", olcrtc_link()));
    assert_eq!(
        imported.locations.len(),
        3,
        "every link must be imported, not just olcrtc://"
    );
    let names = names(&imported);
    for name in ["OLC room", "Amsterdam", "Berlin"] {
        assert!(names.contains(&name.to_string()), "{names:?}");
    }
    let standard = imported
        .locations
        .iter()
        .filter(|e| e.location().engine == EngineType::Standard)
        .count();
    assert_eq!(standard, 2, "both vless links become Standard locations");
}

#[test]
fn imports_both_families_from_a_base64_subscription() {
    // A base64 body hid olcrtc:// links from their parser entirely (it looks for the scheme in the
    // raw text), so the mirror image of the bug: only the vless:// servers landed.
    let imported = import(&STANDARD.encode(format!("{}\n{VLESS}", olcrtc_link())));
    assert_eq!(imported.locations.len(), 2);
    let names = names(&imported);
    assert!(names.contains(&"OLC room".to_string()), "{names:?}");
    assert!(names.contains(&"Amsterdam".to_string()), "{names:?}");
}

#[test]
fn imports_every_freeturn_link_not_just_the_first() {
    let imported = import(&format!(
        "{}\n{}",
        freeturn_link("203.0.113.7", "VK one"),
        freeturn_link("203.0.113.8", "VK two")
    ));
    assert_eq!(imported.locations.len(), 2);
    assert!(imported
        .locations
        .iter()
        .all(|e| e.location().engine == EngineType::VkTurn));
    let ids: std::collections::HashSet<&str> = imported
        .locations
        .iter()
        .map(|e| e.storage_id.as_str())
        .collect();
    assert_eq!(ids.len(), 2, "storage ids must stay unique across entries");
}

#[test]
fn single_family_subscription_is_unchanged() {
    let imported = import(&format!("{VLESS}\n{SECOND_VLESS}"));
    assert_eq!(imported.locations.len(), 2);
    assert_eq!(
        imported.active_location_id.as_deref(),
        Some(imported.locations[0].storage_id.as_str())
    );
}

// ---------------- subscription cascade ----------------

const URL: &str = "https://example.test/sub";
const CASCADE: &str =
    "vless://99999999-2222-3333-4444-555555555555@exit.test:443?type=tcp&security=tls#Exit";

fn link(host: &str, name: &str) -> String {
    format!("vless://11111111-2222-3333-4444-555555555555@{host}:443?type=tcp&security=tls#{name}")
}

fn exit_of(e: &LocationEntry) -> Option<&str> {
    e.proxy2.as_ref().map(|p| p.server.as_str())
}

fn cascade_link(e: &LocationEntry) -> Option<String> {
    e.metadata
        .as_ref()?
        .subscription
        .as_ref()?
        .cascade_link
        .clone()
}

#[test]
fn cascade_is_written_to_every_server_survives_refresh_and_can_be_removed() {
    let panel = Panel::new(&format!("{}\n{}", link("a.test", "A"), link("b.test", "B")));
    let store = FakeStore::default();
    let r = repo(&store, &panel);
    r.import_text(URL, None);

    assert!(r.set_subscription_cascade(URL, CASCADE));
    assert!(store
        .stored()
        .locations
        .iter()
        .all(|e| exit_of(e) == Some("exit.test")));

    // The panel hands out a new server next time; the refresh knows nothing about the cascade.
    panel
        .0
        .lock()
        .unwrap()
        .push_str(&format!("\n{}", link("c.test", "C")));
    r.refresh_subscription(URL, None);
    let refreshed = store.stored().locations;
    assert_eq!(refreshed.len(), 3);
    assert!(refreshed.iter().all(|e| exit_of(e) == Some("exit.test")));
    assert_eq!(cascade_link(&refreshed[0]).as_deref(), Some(CASCADE));

    assert!(r.set_subscription_cascade(URL, ""));
    let removed = store.stored().locations;
    assert!(removed.iter().all(|e| e.proxy2.is_none()));
    assert_eq!(cascade_link(&removed[0]), None);
}

#[test]
fn unusable_link_and_unknown_subscription_are_refused() {
    let store = FakeStore::default();
    let r = repo(&store, &Panel::new(&link("a.test", "A")));
    r.import_text(URL, None);

    assert!(!r.set_subscription_cascade(URL, "not a proxy link"));
    assert!(!r.set_subscription_cascade("https://example.test/other", CASCADE));
    assert!(store.stored().locations.iter().all(|e| e.proxy2.is_none()));
}

#[test]
fn a_server_is_never_cascaded_through_itself() {
    let store = FakeStore::default();
    let r = repo(
        &store,
        &Panel::new(&format!(
            "{}\n{}",
            link("a.test", "A"),
            link("exit.test", "Same")
        )),
    );
    r.import_text(URL, None);

    assert!(r.set_subscription_cascade(
        URL,
        "vless://11111111-2222-3333-4444-555555555555@exit.test:443?type=tcp&security=tls#X"
    ));
    let stored = store.stored();
    let by_name = |n: &str| {
        stored
            .locations
            .iter()
            .find(|e| e.name == n)
            .unwrap()
            .clone()
    };
    assert_eq!(exit_of(&by_name("A")), Some("exit.test"));
    assert_eq!(exit_of(&by_name("Same")), None);
}
