//! The location list of LocationViewModel / HomeScreen: rows, the kept selection, the cold-start
//! seed, the clean-up picks and the auto-connect order.

use yptun_core::location::*;
use yptun_core::location_list::*;
use yptun_core::model::*;
use yptun_core::pings::Pings;
use yptun_core::share_link;
use yptun_core::transports::MasterDnsConfig;

const SUB: &str = "https://panel.example/sub";

fn vless(host: &str) -> ProxyProfile {
    share_link::parse(&format!(
        "vless://11111111-2222-3333-4444-555555555555@{host}:443?security=tls&type=tcp#{host}"
    ))
    .unwrap()
}

fn standard(name: &str, host: &str) -> LocationConfig {
    LocationConfig {
        name: name.into(),
        engine: EngineType::Standard,
        proxy: Some(vless(host)),
        ..LocationConfig::default()
    }
}

fn entry(id: &str, config: &LocationConfig, subscription: Option<&str>) -> LocationEntry {
    LocationEntry::from(id, config, subscription.map(str::to_string), None)
}

fn row(id: &str, config: &LocationConfig, subscription: Option<&str>) -> LocationItem {
    LocationItem::from_entry(&entry(id, config, subscription))
}

fn bundle(active: Option<&str>, entries: Vec<LocationEntry>) -> LocationBundleV4 {
    LocationBundleV4 {
        active_location_id: active.map(str::to_string),
        locations: entries,
        ..LocationBundleV4::default()
    }
}

fn pings(values: &[(&str, Option<i32>)]) -> Pings {
    values
        .iter()
        .map(|(id, ms)| (id.to_string(), *ms))
        .collect()
}

fn ids(items: &[LocationItem]) -> Vec<&str> {
    items.iter().map(|it| it.storage_id.as_str()).collect()
}

#[test]
fn rows_carry_the_full_config_and_its_name() {
    let item = row("a", &standard("", "a.example"), Some(SUB));
    assert_eq!(item.full_name, "");
    assert!(item.is_complete());
    assert!(item.is_subscription());
    let named = row("b", &standard("B", "b.example"), Some("  "));
    assert_eq!(named.full_name, "B");
    assert!(!named.is_subscription());
}

#[test]
fn the_active_location_stays_selected() {
    let b = bundle(
        Some("b"),
        vec![
            entry("a", &standard("A", "a.example"), None),
            entry("b", &standard("B", "b.example"), None),
        ],
    );
    let list = load_list(&b);
    assert_eq!(ids(&list.items), ["a", "b"]);
    assert_eq!(list.selected_id.as_deref(), Some("b"));
    assert!(!list.persist_selection);
}

#[test]
fn a_missing_or_blank_active_id_moves_to_the_first_row_and_is_saved() {
    let entries = vec![
        entry("a", &standard("A", "a.example"), None),
        entry("b", &standard("B", "b.example"), None),
    ];
    for active in [None, Some(""), Some("  "), Some("gone")] {
        let list = load_list(&bundle(active, entries.clone()));
        assert_eq!(list.selected_id.as_deref(), Some("a"), "{active:?}");
        assert!(list.persist_selection, "{active:?}");
    }
    // An empty list keeps whatever was stored and saves nothing.
    let empty = load_list(&bundle(Some("gone"), vec![]));
    assert_eq!(empty.selected_id.as_deref(), Some("gone"));
    assert!(!empty.persist_selection);
    assert_eq!(resolve_selection(&[], None), (None, false));
}

#[test]
fn the_cold_start_seed_paints_display_only_rows() {
    let b = bundle(
        None,
        vec![
            entry("a", &standard("A", "a.example"), Some(SUB)),
            entry(
                "o",
                &LocationConfig {
                    name: "Room".into(),
                    id: "room".into(),
                    key: "a".repeat(64),
                    bypass_provider: PROVIDER_TELEMOST.into(),
                    ..LocationConfig::default()
                },
                None,
            ),
        ],
    );
    let seed = seed_list(&LocationViewIndex::from_bundle(&b));
    assert_eq!(ids(&seed.items), ["a", "o"]);
    assert_eq!(seed.selected_id.as_deref(), Some("a"));
    assert!(!seed.persist_selection);
    let a = &seed.items[0];
    assert_eq!(a.full_name, "A");
    assert!(a.is_subscription());
    let stub = a.config.as_ref().unwrap().proxy.as_ref().unwrap();
    assert_eq!(
        (stub.kind.as_str(), stub.server.as_str()),
        (TYPE_VLESS, "a.example")
    );
    // Not complete: neither pinged nor connected until the full load replaces it.
    assert!(!a.is_complete());
    let o = &seed.items[1];
    assert!(o.config.as_ref().unwrap().proxy.is_none());
    assert_eq!(
        o.config.as_ref().unwrap().bypass_provider,
        PROVIDER_TELEMOST
    );
    assert!(!o.is_complete());

    let stored = seed_list(&LocationViewIndex {
        active_location_id: Some("o".into()),
        ..LocationViewIndex::from_bundle(&b)
    });
    assert_eq!(stored.selected_id.as_deref(), Some("o"));
}

#[test]
fn a_view_item_without_a_type_reads_as_vless() {
    let item = LocationItem::from_view_item(&LocationViewItem {
        storage_id: "x".into(),
        name: "X".into(),
        engine: EngineType::Standard,
        proxy_server: "host".into(),
        ..LocationViewItem::default()
    });
    let proxy = item.config.unwrap().proxy.unwrap();
    assert_eq!(
        (proxy.kind.as_str(), proxy.server.as_str()),
        (TYPE_VLESS, "host")
    );
}

#[test]
fn locked_rows_hide_their_settings() {
    let locked = LocationEntry::from(
        "enc",
        &standard("E", "e.example"),
        Some(SUB.into()),
        Some(LocationMetadata {
            locked: true,
            ..LocationMetadata::default()
        }),
    );
    let items = vec![
        LocationItem::from_entry(&locked),
        row("own", &standard("O", "o.example"), None),
    ];
    assert!(is_locked(&items, Some("enc")));
    assert!(!is_locked(&items, Some("own")));
    assert!(!is_locked(&items, Some("missing")));
    assert!(!is_locked(&items, None));
}

#[test]
fn tunnel_only_engines() {
    for engine in [
        EngineType::VkTurn,
        EngineType::MasterDns,
        EngineType::OpenFlux,
        EngineType::Snolc,
    ] {
        assert!(is_tunnel_only(engine), "{engine:?}");
    }
    for engine in [EngineType::Stealth, EngineType::Standard, EngineType::Chain] {
        assert!(!is_tunnel_only(engine), "{engine:?}");
    }
}

#[test]
fn clean_up_picks_own_unreachable_rows_only() {
    let dns = LocationConfig {
        name: "D".into(),
        engine: EngineType::MasterDns,
        master_dns: Some(MasterDnsConfig {
            domains: "t.example.com".into(),
            ..MasterDnsConfig::default()
        }),
        ..LocationConfig::default()
    };
    let items = vec![
        row("sub", &standard("S", "s.example"), Some(SUB)),
        row("dead", &standard("Dead", "dead.example"), None),
        row("alive", &standard("Alive", "alive.example"), None),
        row("unpinged", &standard("U", "u.example"), None),
        row("dns", &dns, None),
    ];
    let p = pings(&[
        ("sub", None),
        ("dead", None),
        ("alive", Some(80)),
        ("dns", None),
    ]);
    assert_eq!(unreachable_custom_location_ids(&items, &p), ["dead"]);
    assert_eq!(subscription_location_ids(&items), ["sub"]);
}

#[test]
fn an_own_copy_of_a_subscription_server_is_the_duplicate() {
    let items = vec![
        row("own1", &standard("Mine", "s.example"), None),
        row("sub", &standard("Panel name", "s.example"), Some(SUB)),
        row("own2", &standard("Other", "o.example"), None),
        row("own3", &standard("Other again", "o.example"), None),
        row("sub2", &standard("Twice", "o.example"), Some(SUB)),
    ];
    // The subscription rows are seen first; among own rows the first one stays.
    let dups = duplicate_custom_location_ids(&items);
    assert_eq!(dups, ["own1", "own2", "own3"]);

    let own_only = vec![
        row("a", &standard("A", "x.example"), None),
        row("b", &standard("B", "x.example"), None),
        row("c", &standard("C", "y.example"), None),
    ];
    assert_eq!(duplicate_custom_location_ids(&own_only), ["b"]);
}

#[test]
fn auto_connect_tries_the_fastest_complete_rows() {
    let incomplete = LocationConfig {
        name: "I".into(),
        engine: EngineType::Standard,
        ..LocationConfig::default()
    };
    let items = vec![
        row("a", &standard("A", "a.example"), None),
        row("b", &standard("B", "b.example"), None),
        row("c", &standard("C", "c.example"), None),
        row("i", &incomplete, None),
    ];
    let candidates = auto_connect_candidates(&items, None);
    assert_eq!(
        candidates
            .iter()
            .map(|it| it.storage_id.as_str())
            .collect::<Vec<_>>(),
        ["a", "b", "c"]
    );
    let only = ["c".to_string(), "i".to_string()];
    let limited = auto_connect_candidates(&items, Some(&only));
    assert_eq!(limited.len(), 1);

    let p = pings(&[("a", Some(300)), ("b", None), ("c", Some(40))]);
    assert_eq!(auto_connect_order(&candidates, &p), ["c", "a"]);
    // Nobody answered: all of them, in list order.
    let silent = pings(&[("a", None)]);
    assert_eq!(auto_connect_order(&candidates, &silent), ["a", "b", "c"]);

    let many: Vec<String> = (0..10).map(|i| i.to_string()).collect();
    assert_eq!(
        auto_connect_attempts(&many).len(),
        AUTO_CONNECT_MAX_ATTEMPTS
    );
    assert_eq!(auto_connect_attempts(&many[..2]).len(), 2);
}

#[test]
fn equal_pings_keep_the_list_order() {
    let items = vec![
        row("a", &standard("A", "a.example"), None),
        row("b", &standard("B", "b.example"), None),
        row("c", &standard("C", "c.example"), None),
    ];
    let candidates = auto_connect_candidates(&items, None);
    let p = pings(&[("a", Some(50)), ("b", Some(20)), ("c", Some(50))]);
    assert_eq!(auto_connect_order(&candidates, &p), ["b", "a", "c"]);
}

#[test]
fn tunnel_only_rows_are_pinged_only_when_connected() {
    let dns = LocationConfig {
        name: "D".into(),
        engine: EngineType::MasterDns,
        master_dns: Some(MasterDnsConfig {
            domains: "t.example.com".into(),
            ..MasterDnsConfig::default()
        }),
        ..LocationConfig::default()
    };
    let other = standard("A", "a.example");
    assert_eq!(ping_plan(&dns, &other.normalized()), PingPlan::Skip);
    assert_eq!(
        ping_plan(&dns, &dns.normalized()),
        PingPlan::Probe { is_active: true }
    );
    assert_eq!(
        ping_plan(&other, &dns.normalized()),
        PingPlan::Probe { is_active: false }
    );
    assert_eq!(
        ping_plan(&other, &other.normalized()),
        PingPlan::Probe { is_active: true }
    );
}
