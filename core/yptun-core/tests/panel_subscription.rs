//! Ported from PanelAllLinksImportTest, PanelSubscriptionImportTest and PanelSubscriptionGroupingTest:
//! every link family the QuofortpostProtocols panel puts into a personal subscription imports, alone
//! and in one mixed list. The strings come from the panel's own link builders
//! (panel/app/protocols.py), so a format drift on either side fails here.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use yptun_core::importer::with_panel_subscription;
use yptun_core::location::*;
use yptun_core::model::EngineType;
use yptun_core::panel::parse_masterdns;
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

/// A pasted body never goes to the network.
struct Offline;

impl HttpFetcher for Offline {
    fn get(&self, _: &HttpRequest) -> Option<HttpResponse> {
        None
    }
}

fn repo(store: &FakeStore) -> Repository {
    Repository::new(
        Box::new(store.clone()),
        Box::new(Offline),
        ClientInfo::default(),
    )
}

/// Imports `text` into an empty store and returns what was saved.
fn import(text: &str) -> LocationBundleV4 {
    let store = FakeStore::default();
    repo(&store).import_text(text, None);
    let stored = store.0.lock().unwrap().clone();
    stored.unwrap_or_else(|| panic!("nothing imported from {text}"))
}

fn single(b: LocationBundleV4) -> LocationEntry {
    assert_eq!(b.locations.len(), 1, "expected exactly one location");
    b.locations.into_iter().next().unwrap()
}

fn engines(b: &LocationBundleV4) -> Vec<EngineType> {
    b.locations.iter().map(|e| e.location().engine).collect()
}

fn count(b: &LocationBundleV4, engine: EngineType) -> usize {
    engines(b).into_iter().filter(|e| *e == engine).count()
}

fn locked(e: &LocationEntry) -> bool {
    e.metadata.as_ref().is_some_and(|m| m.locked)
}

/// `java.net.URLEncoder` (form encoding), as the panel encodes names.
fn form_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => {
                (b as char).to_string()
            }
            b' ' => "+".into(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Family, link and the engine it lands on (trusttunnel/amneziawg are Standard locations with a
/// special proxy).
const LINKS: &[(&str, &str, EngineType)] = &[
    ("trusttunnel", "tt://AQIDBAUGBwg#Stealth%20NL-tt", EngineType::Standard),
    ("amneziawg", "amneziawg://W0ludGVyZmFjZV0KUHJpdmF0ZUtleSA9IGlIUnRGN0poYXpYOExaSXd5dkV0TVJWU3pjM0xRbUFQMz\
        hqMGozaW5LWGs9CkFkZHJlc3MgPSAxMC44LjAuMi8zMgpETlMgPSAxLjEuMS4xCk1UVSA9IDEyODAKSmMgPSA0Ckpt\
        aW4gPSA0MApKbWF4ID0gNzAKUzEgPSAwClMyID0gMApIMSA9IDEKSDIgPSAyCkgzID0gMwpINCA9IDQKCltQZWVyXQ\
        pQdWJsaWNLZXkgPSA4UTZoUzlFMG0zYjBjV2wzbTBiNm8wdVl5MHI4YnE3eEo0Y1gzbTJrWjFvPQpFbmRwb2ludCA9\
        IDIwMy4wLjExMy43OjUxODIwCkFsbG93ZWRJUHMgPSAwLjAuMC4wLzAsIDo6LzAKUGVyc2lzdGVudEtlZXBhbGl2ZS\
        A9IDI1#Stealth%20FI-awg", EngineType::Standard),
    ("openflux", "openflux://v1/JYrNCsIwEAZfRRZ66w_orSAiqC_gUTxsk60NtklItrah9N3dKuxh55tZwOJAUMOdCXvudpdr4VrI\
        wdLLsUEW12IfKQflNCkpA07iI6lALBhhU5Zp3ih5Hq0sg8SC8nFAG70LHKF-LMDJbyKh1TSLHkMv2DH7WFeVdiqWf1\
        eG8YfVx9B0kuyYsNAmvgs_Nr1R2eGc7W9y2ChYn-sX", EngineType::OpenFlux),
    ("vkturn", "freeturn://vk?tcp<mode=tcp&obf-profile=rtpopus&bond=1&vless=dmxlc3M6Ly8xMTExMTExMS0yMjIyLT\
        MzMzMtNDQ0NC01NTU1NTU1NTU1NTVAZXhhbXBsZS5jb206NDQzP3NlY3VyaXR5PXRscyZ0eXBlPXRjcCNleGl0>@20\
        3.0.113.9:56000#abababababababababababababababab$Stealth DE-vk", EngineType::VkTurn),
    ("freeturn", "freeturn://vk?tcp<mode=udp&obf-profile=rtpopus&wg=W0ludGVyZmFjZV0KUHJpdmF0ZUtleSA9IGFhYWEK\
        QWRkcmVzcyA9IDEwLjcuMi4yLzMyCgpbUGVlcl0KUHVibGljS2V5ID0gYmJiYgpFbmRwb2ludCA9IDEyNy4wLjAuMT\
        o5MDAwCkFsbG93ZWRJUHMgPSAwLjAuMC4wLzAK>@203.0.113.9:56001#cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd\
        $Stealth DE-ft", EngineType::VkTurn),
    ("masterdns", "masterdns://v1?domains=v.example.com,w.example.com&key=s3cret%2B&enc=1&resolvers=203.0.113\
        .5%3A53&bal=2#Stealth%20NL-dns", EngineType::MasterDns),
    ("qwdtt", "qwdtt://config?name=Stealth%20NL-wd&peer=203.0.113.5&hashes=h1%2Ch2&workers=9&port=9000&pa\
        ss=pw", EngineType::VkTurn),
    ("csqtt", "csqtt://connect?v=2&host=203.0.113.5&peer=46000&password=pw&hashes=a+b", EngineType::VkTurn),
    ("olcrtc", "olcrtc://wbstream?seichannel@room123#efefefefefefefefefefefefefefefefefefefefefefefefefefe\
        fefefefefef%b7856bde$Stealth NL-olc", EngineType::Stealth),
    ("snolc", "snolc://203.0.113.5:8444?key=ababababababababababababababababababababababababababababababa\
        bab#Stealth%20NL-snolc", EngineType::Snolc),
];

fn link(family: &str) -> &'static str {
    LINKS.iter().find(|(f, _, _)| *f == family).unwrap().1
}

// ---------------- every family ----------------

#[test]
fn every_family_imports_on_its_own() {
    for (family, link, engine) in LINKS {
        let stored = import(link);
        assert_eq!(
            stored.locations.len(),
            1,
            "{family}: expected exactly one location"
        );
        assert_eq!(
            stored.locations[0].location().engine,
            *engine,
            "{family}: wrong engine"
        );
    }
}

#[test]
fn host_description_line_reaches_the_location() {
    let body = format!(
        "{}\n#desc:Stealth%20NL-snolc=%D0%91%D1%8B%D1%81%D1%82%D1%80%D1%8B%D0%B9%20UDP\n{}",
        link("snolc"),
        link("masterdns")
    );
    let stored = import(&body);
    let description = |engine| {
        stored
            .locations
            .iter()
            .find(|e| e.location().engine == engine)
            .unwrap()
            .description
            .clone()
    };
    assert_eq!(description(EngineType::Snolc), "Быстрый UDP");
    assert_eq!(description(EngineType::MasterDns), "");
}

/// The panel writes `#desc:<node name>=<text>` with the SAME name it puts in the link, for every family.
#[test]
fn host_description_reaches_every_family() {
    let mut lost = vec![];
    for (family, link, _) in LINKS {
        let name = single(import(link)).name;
        let enc = form_encode(&name).replace('+', "%20");
        let got = single(import(&format!("{link}\n#desc:{enc}=desc-{family}\n"))).description;
        if got != format!("desc-{family}") {
            lost.push(format!("{family}(name='{name}', description='{got}')"));
        }
    }
    assert!(lost.is_empty(), "description lost: {lost:?}");
}

/// The cold-start list is painted from the light index first: it must carry the description too.
#[test]
fn cold_start_index_keeps_description() {
    let stored = import(&format!(
        "{}\n#desc:Stealth%20NL-snolc=Fast\n",
        link("snolc")
    ));
    let index = LocationViewIndex::from_bundle(&stored);
    assert_eq!(index.items.len(), 1);
    assert_eq!(index.items[0].description, "Fast");
}

#[test]
fn order_lines_restore_panel_order_across_families() {
    let body = format!(
        "{}\n{}\n#order:Stealth%20NL-snolc\n#order:Stealth%20NL-dns\n",
        link("masterdns"),
        link("snolc")
    );
    assert_eq!(
        engines(&import(&body)),
        [EngineType::Snolc, EngineType::MasterDns]
    );
    let without = body.split("#order").next().unwrap();
    assert_eq!(
        engines(&import(without)),
        [EngineType::MasterDns, EngineType::Snolc]
    );
}

#[test]
fn cyrillic_names_survive_in_every_family() {
    let cyr = "Амстердам-1";
    let enc = form_encode(cyr);
    let mut bad = vec![];
    for (family, link, _) in LINKS {
        let renamed = link
            .replace("Stealth%20", &format!("{enc}%20"))
            .replace("Stealth ", &format!("{cyr} "));
        if renamed == *link {
            continue; // the family carries its name elsewhere (qwdtt/csqtt query)
        }
        let name = single(import(&renamed)).name;
        if !name.starts_with(cyr) {
            bad.push(format!("{family}='{name}'"));
        }
    }
    assert!(bad.is_empty(), "names mangled: {bad:?}");
}

#[test]
fn whole_panel_subscription_keeps_every_family() {
    // A panel list may carry comment lines (# note) next to the links; they must be ignored.
    let all: Vec<&str> = LINKS.iter().map(|(_, l, _)| *l).collect();
    let stored = import(&format!("# QuofortpostProtocols\n{}\n", all.join("\n")));
    assert_eq!(count(&stored, EngineType::MasterDns), 1);
    assert_eq!(count(&stored, EngineType::Snolc), 1);
    assert_eq!(count(&stored, EngineType::OpenFlux), 1);
    assert_eq!(count(&stored, EngineType::Stealth), 1);
    assert_eq!(count(&stored, EngineType::VkTurn), 4);
    assert!(
        count(&stored, EngineType::Standard) >= 1,
        "trusttunnel/amneziawg must survive: {:?}",
        engines(&stored)
    );
}

#[test]
fn trust_tunnel_name_inside_the_blob_becomes_the_location_name() {
    let tlv = |tag: u8, v: &str| [&[tag, v.len() as u8][..], v.as_bytes()].concat();
    let blob = [
        tlv(1, "h.example.com"),
        tlv(2, "203.0.113.7:443"),
        tlv(12, "🇷🇺Москва (TT)"),
    ]
    .concat();
    let tt = format!("tt://?{}", URL_SAFE_NO_PAD.encode(blob));
    let stored = import(&format!("{}\n{tt}\n", link("snolc")));
    let names: Vec<&str> = stored.locations.iter().map(|e| e.name.as_str()).collect();
    assert!(names.contains(&"🇷🇺Москва (TT)"), "names: {names:?}");
}

/// Encrypted subscription (panel "зашифрованная подписка"): lines are `yptun-enc://` + the same
/// links, produced by app/subcrypt.py.
#[test]
fn encrypted_subscription_imports_locked() {
    let enc = [
        "yptun-enc://v1/Do26vXS1jq3CxJ-6XGVBoB_4jbqnlre-nqdQuMD5T7aviLWfI7bNoZiPOuhAr-chTP5UP-B3Jok\
         CD1jKM-dQueHVwjHfACHsaTgui-gVVWyxDmPeuvnK_WftJUJ4MoPcVcAXg7dHBqgl5nHTtmvJhk321hM2IHAE7EIJl\
         pjOviH7ZmV_OBQSVoC2kmG238V413NwI1-RLht0rshtEE_pK3unOouIVyEP1A2pn3-jlBUevA_OhDfAtcWqbLYbUSd\
         Qvqprt2Qw8fmI0vQQ41bE2izr923TjxGHHLtGSkDviu6B3r2S22ccIdjaLb65kQ",
        "yptun-enc://v1/--RFO6lshrJ0RXSzEVdsGgyBexlKV-uMANoWEuemt0hUYRbTOtlhGy5TDczqWFNBr7wOFBpOTo-\
         MqkmZ0HXUMXDBKR4Ge4lcDnyEf1MtXIEltN3f64zn1f9IReo3sleNAbMxm18vO0FfECq16Z2jzdlJ62v1KAkAHs73r\
         mUPARQ",
    ];
    let store = FakeStore::default();
    repo(&store).import_text(&format!("{}\n", enc.join("\n")), None);
    let stored = store.stored();
    let kinds: HashSet<EngineType> = engines(&stored).into_iter().collect();
    assert_eq!(
        kinds,
        HashSet::from([EngineType::OpenFlux, EngineType::Snolc])
    );
    assert!(stored.locations.iter().all(locked));

    let open = import(&format!("{}\n{}\n", link("openflux"), link("snolc")));
    assert!(!open.locations.iter().any(locked));

    let backup = repo(&store).export_bundle();
    assert!(
        !backup.contains("cloud.mail.ru") && backup.replace(' ', "").contains("\"locations\":[]"),
        "locked locations must stay out of the backup"
    );
}

/// A locked VK-TURN location lets the user set their own VK call link; it is stored as an override.
#[test]
fn locked_vk_turn_location_accepts_own_call_link() {
    let store = FakeStore::default();
    let r = repo(&store);
    r.import_text(&format!("{}\n", "yptun-enc://v1/JVBUZ7U1iOvnfPq-QWNzPehb8T-2iuhOFoaaaChf9K3oITd7cVxM5XGBVXsnX2fqcTy4CIhfD1u\
        Wyb1robI-XUa7A4C7v-cHJ8sgOss0fEMfUKK4VG32GKuNKzQc6wVAPutWCRMzMIGrHYzr4W7YmV6sPLI1XNmAQrxQJ\
        0K8Z0Dmw5X-YY-nI1tMHcAEuHZBcC7YKYNwhEPJ1TuEBS6DLfB9Q0ev3Ipg6lfK9EsyCzhfDMgyy8_fGQ_Bke0qwQq\
        otm0zNm8PU51uBOCdmG9ABkXrj63ap2JPXmuPS6WcWDHAquxX2sOyqOOAvxyXdc7lnjokdi5BeUm57rsH3V-5HJfAF\
        lKObJawlIacSL6Ex69BcqLfb7HUfdc4Y2w00ctLWEecHX3zSRvC2UO2"), None);
    let entry = single(store.stored());
    assert!(locked(&entry));
    r.set_location_vk_link(&entry.storage_id, " https://vk.com/call/join/abc ");
    let after = single(store.stored());
    assert_eq!(
        after.location().vkturn.unwrap().vk_link,
        "https://vk.com/call/join/abc"
    );
    assert_eq!(
        after.metadata.as_ref().unwrap().vk_link_override.as_deref(),
        Some("https://vk.com/call/join/abc")
    );
    assert!(locked(&after));
}

/// The panel may hand out qWDTT/VK-TURN without a VK call link: the location is usable once the
/// user adds one (the app asks at connect).
#[test]
fn location_without_vk_link_needs_one_then_connects() {
    let store = FakeStore::default();
    let r = repo(&store);
    r.import_text(
        "qwdtt://config?name=NoVk&peer=203.0.113.5&workers=9&port=9000&pass=pw",
        None,
    );
    let entry = single(store.stored());
    assert!(entry.location().needs_vk_link());
    assert!(!entry.location().is_complete());
    r.set_location_vk_link(&entry.storage_id, "https://vk.com/call/join/abc");
    let after = single(store.stored()).location();
    assert!(!after.needs_vk_link());
    assert!(after.is_complete());
}

// ---------------- masterdns:// and snolc:// next to the others ----------------

const MASTER_DNS: &str =
    "masterdns://v1?domains=v.example.com,w.example.com&key=s3cret%2B&enc=1&resolvers=1.1.1.1,8.8.8.8:53&bal=2#YPtun%20MasterDNS";

#[test]
fn masterdns_link_parses_all_fields() {
    let (name, config) = parse_masterdns(MASTER_DNS).unwrap();
    assert_eq!(name, "YPtun MasterDNS");
    assert_eq!(config.domain_list(), ["v.example.com", "w.example.com"]);
    assert_eq!(config.encryption_key, "s3cret+");
    assert_eq!(config.resolver_list(), ["1.1.1.1", "8.8.8.8:53"]);
    assert_eq!(config.balancing_strategy, 2);
}

#[test]
fn unknown_masterdns_version_or_missing_fields_are_rejected() {
    assert!(parse_masterdns("masterdns://v2?domains=a&key=k&resolvers=1.1.1.1").is_none());
    assert!(parse_masterdns("masterdns://v1?domains=a&resolvers=1.1.1.1").is_none());
}

#[test]
fn mixed_panel_subscription_keeps_every_family() {
    let snolc = format!(
        "snolc://203.0.113.5:443?key={}#YPtun%20SNOLC",
        "ab".repeat(32)
    );
    let body = [
        "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#Plain",
        MASTER_DNS,
        &snolc,
        "qwdtt://config?name=Q&peer=203.0.113.9&hashes=h1,h2&workers=9&port=9000&pass=p",
        "csqtt://connect?v=2&host=203.0.113.10&peer=46000&password=p&hashes=a+b",
    ]
    .join("\n");
    let stored = import(&body);
    assert_eq!(count(&stored, EngineType::MasterDns), 1);
    assert_eq!(count(&stored, EngineType::Snolc), 1);
    assert_eq!(count(&stored, EngineType::Standard), 1);
    assert_eq!(count(&stored, EngineType::VkTurn), 2);
}

// ---------------- the panel's subscription header on every family ----------------

fn panel() -> LocationMetadata {
    let sub = SubscriptionMetadata {
        name: Some("YPtun".into()),
        used: Some("1 GB".into()),
        update_interval_hours: Some(6),
        ..Default::default()
    };
    LocationMetadata {
        subscription: Some(sub),
        ..Default::default()
    }
}

fn entry(own: Option<SubscriptionMetadata>) -> LocationEntry {
    LocationEntry {
        storage_id: "a".into(),
        metadata: own.map(|s| LocationMetadata {
            subscription: Some(s),
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn subscription(e: LocationEntry) -> Option<SubscriptionMetadata> {
    e.metadata.and_then(|m| m.subscription)
}

#[test]
fn olcrtc_entry_without_title_joins_the_panel_subscription() {
    let own = SubscriptionMetadata {
        refresh: Some("10m".into()),
        update_interval_hours: Some(1),
        ..Default::default()
    };
    let merged = subscription(with_panel_subscription(entry(Some(own)), Some(&panel()))).unwrap();
    assert_eq!(merged.name.as_deref(), Some("YPtun"));
    assert_eq!(merged.used.as_deref(), Some("1 GB"));
    assert_eq!(merged.refresh.as_deref(), Some("10m")); // its own header survives where the panel has nothing
}

#[test]
fn entry_without_metadata_gets_the_panel_one_and_a_titled_one_keeps_its_title() {
    let name = |e| subscription(e).and_then(|s| s.name);
    assert_eq!(
        name(with_panel_subscription(entry(None), Some(&panel()))).as_deref(),
        Some("YPtun")
    );
    let own = SubscriptionMetadata {
        name: Some("Own".into()),
        ..Default::default()
    };
    assert_eq!(
        name(with_panel_subscription(entry(Some(own)), Some(&panel()))).as_deref(),
        Some("Own")
    );
    assert_eq!(with_panel_subscription(entry(None), None).metadata, None);
}
