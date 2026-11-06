//! The pure half of HomeScreenViewModel: imports, the VPN button, the VK link prompt, subscription
//! expiry and announcements, the free servers and the logs export.

use std::collections::BTreeSet;

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use yptun_core::home::*;
use yptun_core::location::*;
use yptun_core::location_editor::LocationEditor;
use yptun_core::location_list::LocationItem;
use yptun_core::model::*;
use yptun_core::panel::compose_inbound;
use yptun_core::share_link;
use yptun_core::transports::{WarpMode, WarpProtocol};

fn vless_link(host: &str, name: &str) -> String {
    format!("vless://11111111-2222-3333-4444-555555555555@{host}:443?security=tls&type=tcp#{name}")
}

fn vless(host: &str) -> ProxyProfile {
    share_link::parse(&vless_link(host, host)).unwrap()
}

fn standard(name: &str, host: &str) -> LocationConfig {
    LocationConfig {
        name: name.into(),
        engine: EngineType::Standard,
        proxy: Some(vless(host)),
        ..LocationConfig::default()
    }
}

fn entry(id: &str, config: &LocationConfig, url: Option<&str>) -> LocationEntry {
    LocationEntry::from(id, config, url.map(str::to_string), None)
}

fn with_subscription(entry: LocationEntry, sub: SubscriptionMetadata) -> LocationEntry {
    LocationEntry {
        metadata: Some(LocationMetadata {
            subscription: Some(sub),
            ..entry.metadata.clone().unwrap_or_default()
        }),
        ..entry
    }
}

fn pinned(entry: LocationEntry) -> LocationEntry {
    LocationEntry {
        metadata: Some(LocationMetadata {
            pinned: true,
            ..entry.metadata.clone().unwrap_or_default()
        }),
        ..entry
    }
}

fn ids(bundle: &LocationBundleV4) -> Vec<&str> {
    bundle
        .locations
        .iter()
        .map(|e| e.storage_id.as_str())
        .collect()
}

// ---------------- import ----------------

#[test]
fn a_paste_is_one_import_unless_it_is_clearly_a_batch() {
    assert_eq!(plan_import(" \n\t "), ImportPlan::Empty);
    assert_eq!(
        plan_import("https://a.example/sub\n\n  HTTP://b.example/sub  "),
        ImportPlan::Batch(vec![
            "https://a.example/sub".into(),
            "HTTP://b.example/sub".into()
        ])
    );
    // URLs with share links: the links go together as the last item.
    let links = format!(
        "{}\r\nhttps://a.example/sub\r\n{}",
        vless_link("x.example", "X"),
        vless_link("y.example", "Y")
    );
    assert_eq!(
        plan_import(&links),
        ImportPlan::Batch(vec![
            "https://a.example/sub".into(),
            format!(
                "{}\n{}",
                vless_link("x.example", "X"),
                vless_link("y.example", "Y")
            )
        ])
    );
    // One URL, a link list or a JSON is a single import, kept as it is.
    for text in [
        " https://a.example/sub ".to_string(),
        format!(
            "{}\n{}",
            vless_link("x.example", "X"),
            vless_link("y.example", "Y")
        ),
        "{\n  \"outbounds\": []\n}".to_string(),
    ] {
        assert_eq!(plan_import(&text), ImportPlan::Single(text.clone()));
    }
}

#[test]
fn routing_profiles_are_not_locations() {
    let link = format!(
        "happ://routing/add/{}",
        STANDARD.encode(r#"{"Name":"R","DirectSites":["geosite:ru"]}"#)
    );
    assert_eq!(
        plan_import(&format!("  {link}\n")),
        ImportPlan::RoutingProfile(link)
    );
    let json = r#"{"Name":"R","GlobalProxy":"true"}"#;
    assert_eq!(
        plan_import(&format!("\n{json}\n")),
        ImportPlan::RoutingProfile(json.into())
    );
}

#[test]
fn failure_reasons_point_at_damaged_or_unsupported_links() {
    assert_eq!(
        import_failure_reason("  OpenFlux://abc"),
        UNSUPPORTED_OPENFLUX_LINK
    );
    assert_eq!(
        import_failure_reason("yptun://inbound/eyJicm9rZW4"),
        DAMAGED_INBOUND_LINK
    );
    // Two links are not "one damaged link".
    assert_eq!(
        import_failure_reason("yptun://inbound/aaa\nyptun://inbound/bbb"),
        NO_VALID_CONFIG
    );
    let good = compose_inbound(&standard("Shared", "s.example"));
    assert!(good.starts_with("yptun://inbound"));
    assert_eq!(import_failure_reason(&good), NO_VALID_CONFIG);
    assert_eq!(import_failure_reason("hello"), NO_VALID_CONFIG);
}

#[test]
fn batch_outcomes() {
    assert_eq!(
        import_many_outcome(0, 3),
        Err("No valid subscriptions found (3 failed)".into())
    );
    assert_eq!(import_many_outcome(2, 0), Ok(None));
    assert_eq!(
        import_many_outcome(2, 1),
        Ok(Some("Imported 2, 1 failed".into()))
    );
}

// ---------------- the VPN button ----------------

#[test]
fn statuses_map_to_the_button() {
    let flags = |s: VpnStatus| {
        let f = s.flags();
        (f.connected, f.loading, f.connect_error)
    };
    assert_eq!(flags(VpnStatus::Connected), (true, false, None));
    assert_eq!(flags(VpnStatus::Connecting), (false, true, None));
    assert_eq!(flags(VpnStatus::Reconnecting), (true, true, None));
    assert_eq!(flags(VpnStatus::Stopping), (false, false, None));
    assert_eq!(flags(VpnStatus::Disconnected), (false, false, None));
    assert_eq!(
        flags(VpnStatus::Error("boom".into())),
        (false, false, Some("boom".into()))
    );
    assert!(VpnStatus::Connected.restarts_on_change());
    assert!(VpnStatus::Connecting.restarts_on_change());
    assert!(VpnStatus::Reconnecting.restarts_on_change());
    assert!(!VpnStatus::Stopping.restarts_on_change());
    assert!(!VpnStatus::Disconnected.restarts_on_change());
    assert!(!VpnStatus::Error(String::new()).restarts_on_change());
}

#[test]
fn the_toggle_cancels_stops_or_starts() {
    let complete = entry("a", &standard("A", "a.example"), None);
    let incomplete = entry(
        "b",
        &LocationConfig {
            name: "B".into(),
            engine: EngineType::Standard,
            ..LocationConfig::default()
        },
        None,
    );
    use VpnStatus::*;
    assert_eq!(
        toggle_action(&Connecting, false, false, Some(&complete)),
        ToggleAction::Cancel
    );
    assert_eq!(
        toggle_action(&Reconnecting, false, true, None),
        ToggleAction::Cancel
    );
    assert_eq!(
        toggle_action(&Disconnected, true, false, None),
        ToggleAction::Cancel
    );
    assert_eq!(
        toggle_action(&Connected, false, false, None),
        ToggleAction::Stop
    );
    assert_eq!(
        toggle_action(&Disconnected, false, true, None),
        ToggleAction::Stop
    );
    assert_eq!(
        toggle_action(&Disconnected, false, false, Some(&complete)),
        ToggleAction::Start
    );
    assert_eq!(
        toggle_action(&Error("x".into()), false, false, Some(&complete)),
        ToggleAction::Start
    );
    assert_eq!(
        toggle_action(&Disconnected, false, false, Some(&incomplete)),
        ToggleAction::Blocked(ADD_VALID_LOCATION_FIRST)
    );
    assert_eq!(
        toggle_action(&Stopping, false, false, None),
        ToggleAction::Blocked(ADD_VALID_LOCATION_FIRST)
    );
}

#[test]
fn the_active_location_says_why_it_cannot_start() {
    let none = active_location(None);
    assert_eq!(none.selected, None);
    assert!(!none.can_start_vpn);
    assert_eq!(none.start_blocked_reason, Some(ADD_LOCATION_FIRST));

    let ok = active_location(Some(&entry("a", &standard("A", "a.example"), None)));
    assert_eq!(ok.selected.as_ref().unwrap().storage_id, "a");
    assert_eq!(ok.config.name, "A");
    assert!(ok.can_start_vpn);
    assert_eq!(ok.start_blocked_reason, None);

    let half = LocationConfig {
        name: "Half".into(),
        id: "room".into(),
        ..LocationConfig::default()
    };
    let blocked = active_location(Some(&entry("h", &half, None)));
    assert!(!blocked.can_start_vpn);
    assert_eq!(
        blocked.start_blocked_reason,
        Some(COMPLETE_ACTIVE_LOCATION_FIRST)
    );
}

// ---------------- VK-TURN link prompt ----------------

fn vk_turn_location(name: &str) -> LocationConfig {
    let mut vm = LocationEditor::new();
    vm.start_editing(&[], None);
    vm.on_name_changed(name);
    vm.on_engine_changed(EngineType::VkTurn);
    LocationConfig {
        name: name.into(),
        ..vm.editing_config
    }
}

#[test]
fn a_vk_turn_location_without_its_call_link_asks_for_it() {
    let config = vk_turn_location("VK home");
    let e = entry("vk", &config, None);
    assert_eq!(
        vk_turn_link_prompt(Some(&e)),
        Some(VkTurnLinkPrompt {
            storage_id: "vk".into(),
            location_name: "VK home".into()
        })
    );
    let linked = with_vk_turn_link(&config, "  https://vk.com/call/join/abc ").unwrap();
    assert_eq!(
        linked.vkturn.as_ref().unwrap().vk_link,
        "https://vk.com/call/join/abc"
    );
    assert_eq!(vk_turn_link_prompt(Some(&entry("vk", &linked, None))), None);
    assert_eq!(
        vk_turn_link_prompt(Some(&entry("a", &standard("A", "a.example"), None))),
        None
    );
    assert_eq!(vk_turn_link_prompt(None), None);
    assert_eq!(
        with_vk_turn_link(&standard("A", "a.example"), "https://vk.com/call/join/abc"),
        None
    );
}

// ---------------- subscriptions handed to the platform ----------------

#[test]
fn each_subscription_reports_its_expiry_and_announcement_once() {
    let a1 = with_subscription(
        entry(
            "a1",
            &standard("Server A1", "a1.example"),
            Some(" https://a/sub "),
        ),
        SubscriptionMetadata {
            name: Some("Panel A".into()),
            expires_at_epoch_ms: Some(1_000),
            announce: Some("Hello".into()),
            ..SubscriptionMetadata::default()
        },
    );
    let a2 = entry(
        "a2",
        &standard("Server A2", "a2.example"),
        Some("https://a/sub"),
    );
    let b1 = entry("b1", &standard("", "b1.example"), Some("https://b/sub"));
    let b2 = with_subscription(
        entry("b2", &standard("B2", "b2.example"), Some("https://b/sub")),
        SubscriptionMetadata {
            name: Some(" ".into()),
            expires_at_epoch_ms: Some(2_000),
            announce: Some("  ".into()),
            ..SubscriptionMetadata::default()
        },
    );
    let own = entry("own", &standard("Own", "o.example"), None);
    let c = with_subscription(
        entry("c", &standard("", "c.example"), Some("https://c/sub")),
        SubscriptionMetadata {
            announce: Some("Maintenance".into()),
            ..SubscriptionMetadata::default()
        },
    );
    let entries = [a1, a2, own, b1, b2, c];
    assert_eq!(
        expiring_subscriptions(&entries),
        [
            ExpiringSubscriptionInfo {
                name: "Panel A".into(),
                expires_at_epoch_ms: 1_000
            },
            ExpiringSubscriptionInfo {
                name: "B2".into(),
                expires_at_epoch_ms: 2_000
            },
        ]
    );
    assert_eq!(
        panel_announcements(&entries),
        [
            PanelAnnouncementInfo {
                name: "Panel A".into(),
                announce: "Hello".into()
            },
            PanelAnnouncementInfo {
                name: "https://c/sub".into(),
                announce: "Maintenance".into()
            },
        ]
    );
    assert!(expiring_subscriptions(&[]).is_empty());
}

// ---------------- free servers ----------------

#[test]
fn free_sources_put_the_users_language_first() {
    let urls =
        |lang: &str| -> Vec<&str> { free_sources_for(lang).iter().map(|s| s.region).collect() };
    assert_eq!(
        urls("ru"),
        ["ru", "ru", "ru", "ru", "", "", "fa", "fa", "fa", "zh", "zh", "zh"]
    );
    assert_eq!(
        urls("fa"),
        ["fa", "fa", "fa", "", "", "ru", "ru", "ru", "ru", "zh", "zh", "zh"]
    );
    assert_eq!(
        urls("en"),
        ["", "", "ru", "ru", "ru", "ru", "fa", "fa", "fa", "zh", "zh", "zh"]
    );
    assert_eq!(free_sources_for("ru")[0].url, FREE_SERVERS_URL);
    // The order inside a group stays.
    let zh: Vec<&str> = free_sources_for("zh")
        .iter()
        .take(3)
        .map(|s| s.url)
        .collect();
    assert_eq!(
        zh,
        FREE_SERVERS_SOURCES[9..]
            .iter()
            .map(|s| s.url)
            .collect::<Vec<_>>()
    );
}

#[test]
fn the_pick_takes_each_server_once_within_the_quotas() {
    let ss = format!(
        "ss://{}@ss.example:8388#SS",
        STANDARD.encode("aes-256-gcm:pass").trim_end_matches('=')
    );
    let first = [
        vless_link("a.example", "A"),
        "hysteria2://pass@h.example:443#H".to_string(),
        format!("  {}  ", vless_link("b.example", "B")),
        vless_link("a.example", "A again"),
        "trojan://secret@t.example:443?security=tls#T".to_string(),
        ss.clone(),
        "vless://@broken.example:443#Broken".to_string(),
        "# comment".to_string(),
    ]
    .join("\n");
    // A base64 subscription body, sampled (max 2), reversed by the test shuffle.
    let second = STANDARD.encode(
        [
            vless_link("c.example", "C"),
            vless_link("b.example", "B dup"),
            vless_link("d.example", "D"),
            vless_link("e.example", "E"),
        ]
        .join("\n"),
    );
    let sources = vec![(first, 0), (second, 2)];
    let mut shuffled = Vec::new();
    let mut reverse = |lines: &mut [String]| {
        shuffled.push(lines.len());
        lines.reverse();
    };
    let picked = pick_free_servers_with(&sources, 0, &mut reverse);
    let hosts: Vec<&str> = picked.iter().map(|(_, p)| p.server.as_str()).collect();
    // The duplicate of b does not use up the second source's quota.
    assert_eq!(
        hosts,
        [
            "a.example",
            "b.example",
            "t.example",
            "ss.example",
            "e.example",
            "d.example"
        ]
    );
    assert_eq!(shuffled, [4]); // only the sampled list is shuffled
    assert_eq!(picked[1].0, vless_link("b.example", "B")); // trimmed line kept
    assert_eq!(picked[3].1.kind, TYPE_SHADOWSOCKS);

    // The overall limit.
    let limited = pick_free_servers_with(&sources, 3, &mut |_: &mut [String]| {});
    assert_eq!(limited.len(), 3);
    // The default shuffle still takes every unique server of an unsampled list.
    assert_eq!(pick_free_servers(&sources[..1], 0).len(), 4);
}

#[test]
fn a_checked_server_becomes_an_item_only_when_it_answered() {
    let p = vless("a.example");
    assert_eq!(free_server_item(3, "line", &p, None), None);
    assert_eq!(free_server_item(3, "line", &p, Some(0)), None);
    assert_eq!(free_server_item(3, "line", &p, Some(-5)), None);
    let item = free_server_item(3, "line", &p, Some(120)).unwrap();
    assert_eq!(item.id, "free_a.example_443_3");
    assert_eq!(item.display_name, p.display_name());
    assert_eq!((item.line.as_str(), item.ping_ms), ("line", 120));

    let config = free_server_config(&p);
    assert_eq!(config.engine, EngineType::Standard);
    assert_eq!(config.name, p.display_name());
    assert!(config.is_complete());
}

#[test]
fn the_search_eta_needs_samples_and_no_pause() {
    assert_eq!(free_eta(true, 10_000, 10, 100, 10), None);
    assert_eq!(free_eta(false, 10_000, 4, 100, 4), None);
    assert_eq!(free_eta(false, 0, 10, 100, 10), None);
    // 2 s per check, 10 left.
    assert_eq!(free_eta(false, 10_000, 5, 15, 5), Some(20));
    assert_eq!(free_eta(false, 10_000, 5, 3, 5), Some(0));
}

fn free_item(id: &str, host: &str, name: &str) -> FreeServerItem {
    FreeServerItem {
        id: id.into(),
        line: vless_link(host, name),
        profile: vless(host),
        display_name: name.into(),
        ping_ms: 100,
    }
}

#[test]
fn saved_free_servers_run_on_xray_with_warp_where_asked() {
    let selected = [
        free_item("free_a", "a.example", "🇷🇺 Moscow"),
        free_item("free_b", "Пример.РФ", "DE"),
    ];
    let warp: BTreeSet<String> = ["free_b".to_string()].into();
    let previous = SubscriptionMetadata {
        update_interval_hours: Some(12),
        auto_update_enabled: true,
        ..SubscriptionMetadata::default()
    };
    let now = 1_700_000_000_000;
    let entries = free_server_entries(&selected, &warp, Some(&previous), now);
    assert_eq!(entries[0].storage_id, "free_a_example_443_1700000000000_0");
    assert_eq!(entries[1].storage_id, "free_пример_рф_443_1700000000000_1");
    let a = entries[0].location();
    assert_eq!(a.core, ProxyCore::Xray);
    assert!(!a.warp_enabled);
    assert_eq!(a.warp_protocol, WarpProtocol::Awg); // plain WireGuard is cut in Russia
    let b = entries[1].location();
    assert!(b.warp_enabled);
    assert_eq!(
        (b.warp_mode, b.warp_protocol),
        (WarpMode::Server, WarpProtocol::Wg)
    );
    for e in &entries {
        assert_eq!(e.subscription_url.as_deref(), Some(FREE_SERVERS_URL));
        let sub = e.metadata.as_ref().unwrap().subscription.as_ref().unwrap();
        assert_eq!(sub.name.as_deref(), Some(FREE_SERVERS_GROUP_NAME));
        assert_eq!(sub.update_interval_hours, Some(12));
        assert!(sub.auto_update_enabled);
        assert_eq!(sub.last_refresh_at_epoch_ms, Some(now));
        assert_eq!(sub.last_attempt_at_epoch_ms, Some(now));
    }
    // Without a previous group: the switch off, and the 0 interval clamped to the 1 h minimum by
    // the entry's normalization (as in the Kotlin app).
    let fresh = free_server_entries(&selected[..1], &BTreeSet::new(), None, now);
    let sub = fresh[0]
        .metadata
        .as_ref()
        .unwrap()
        .subscription
        .as_ref()
        .unwrap();
    assert_eq!(sub.update_interval_hours, Some(MIN_UPDATE_INTERVAL_HOURS));
    assert!(!sub.auto_update_enabled);
}

#[test]
fn a_new_search_replaces_only_the_unpinned_free_servers() {
    let now = 1_000;
    let own = entry("own", &standard("Own", "o.example"), None);
    let old = with_subscription(
        entry(
            "f_old",
            &standard("Old", "old.example"),
            Some(FREE_SERVERS_URL),
        ),
        SubscriptionMetadata {
            update_interval_hours: Some(8),
            ..SubscriptionMetadata::default()
        },
    );
    let pin = pinned(entry(
        "f_pin",
        &standard("Pin", "p.example"),
        Some(FREE_SERVERS_URL),
    ));
    let bundle = LocationBundleV4 {
        active_location_id: Some("f_old".into()),
        locations: vec![own.clone(), old, pin],
        ..LocationBundleV4::default()
    };
    assert_eq!(
        previous_free_subscription(&bundle).and_then(|s| s.update_interval_hours),
        Some(8)
    );
    let new_entries = free_server_entries(
        &[
            free_item("x", "p.example", "Pin again"),
            free_item("y", "n.example", "New"),
        ],
        &BTreeSet::new(),
        None,
        now,
    );
    let (merged, added) = merge_free_servers(&bundle, new_entries.clone());
    assert_eq!(added, 1);
    assert_eq!(ids(&merged), ["own", "f_pin", "free_n_example_443_1000_1"]);
    assert_eq!(
        merged.active_location_id.as_deref(),
        Some("free_n_example_443_1000_1")
    );

    // An own active location stays active.
    let own_active = LocationBundleV4 {
        active_location_id: Some("own".into()),
        ..bundle.clone()
    };
    let (merged, _) = merge_free_servers(&own_active, new_entries.clone());
    assert_eq!(merged.active_location_id.as_deref(), Some("own"));

    // Nothing new: the pinned server takes over.
    let (merged, added) = merge_free_servers(&bundle, new_entries[..1].to_vec());
    assert_eq!(added, 0);
    assert_eq!(ids(&merged), ["own", "f_pin"]);
    assert_eq!(merged.active_location_id.as_deref(), Some("f_pin"));

    // Nothing at all: the id stays as it was.
    let lone = LocationBundleV4 {
        active_location_id: Some("f_old".into()),
        locations: vec![own, bundle.locations[1].clone()],
        ..LocationBundleV4::default()
    };
    let (merged, _) = merge_free_servers(&lone, vec![]);
    assert_eq!(ids(&merged), ["own"]);
    assert_eq!(merged.active_location_id.as_deref(), Some("f_old"));
}

#[test]
fn old_free_servers_move_to_xray_unless_a_core_was_picked() {
    let auto = entry("f1", &standard("F1", "f1.example"), Some(FREE_SERVERS_URL));
    let picked = entry(
        "f2",
        &LocationConfig {
            core: ProxyCore::SingBox,
            ..standard("F2", "f2.example")
        },
        Some(FREE_SERVERS_URL),
    );
    let unset = LocationEntry {
        core: None,
        ..entry("f3", &standard("F3", "f3.example"), Some(FREE_SERVERS_URL))
    };
    let own = entry("own", &standard("Own", "o.example"), None);
    let bundle = LocationBundleV4 {
        locations: vec![auto, picked, unset, own],
        ..LocationBundleV4::default()
    };
    let fixed = migrate_free_servers_to_xray(&bundle).unwrap();
    let cores: Vec<Option<ProxyCore>> = fixed.locations.iter().map(|e| e.core).collect();
    assert_eq!(
        cores,
        [
            Some(ProxyCore::Xray),
            Some(ProxyCore::SingBox),
            Some(ProxyCore::Xray),
            Some(ProxyCore::Auto)
        ]
    );
    assert_eq!(migrate_free_servers_to_xray(&fixed), None);
}

#[test]
fn the_auto_update_waits_for_searches_and_a_tunnel_over_a_free_server() {
    let free = LocationItem::from_entry(&entry(
        "f",
        &standard("F", "f.example"),
        Some(FREE_SERVERS_URL),
    ));
    let pin = LocationItem::from_entry(&pinned(entry(
        "p",
        &standard("P", "p.example"),
        Some(FREE_SERVERS_URL),
    )));
    let own = LocationItem::from_entry(&entry("o", &standard("O", "o.example"), None));
    assert!(free_auto_update_blocked(true, false, 0, false, None));
    assert!(free_auto_update_blocked(false, true, 0, false, None));
    assert!(free_auto_update_blocked(false, false, 2, false, None));
    assert!(free_auto_update_blocked(false, false, 0, true, Some(&free)));
    assert!(!free_auto_update_blocked(
        false,
        false,
        0,
        false,
        Some(&free)
    ));
    assert!(!free_auto_update_blocked(false, false, 0, true, Some(&pin)));
    assert!(!free_auto_update_blocked(false, false, 0, true, Some(&own)));
    assert!(!free_auto_update_blocked(false, false, 0, true, None));
}

#[test]
fn the_auto_update_is_due_after_its_interval() {
    const HOUR: i64 = 3_600_000;
    let free = |id: &str, sub: SubscriptionMetadata| {
        with_subscription(
            entry(
                id,
                &standard(id, &format!("{id}.example")),
                Some(FREE_SERVERS_URL),
            ),
            sub,
        )
    };
    let on = SubscriptionMetadata {
        auto_update_enabled: true,
        update_interval_hours: Some(0),
        last_refresh_at_epoch_ms: Some(10 * HOUR),
        last_attempt_at_epoch_ms: Some(9 * HOUR),
        ..SubscriptionMetadata::default()
    };
    let entries = [free("a", on.clone())];
    // No interval: every FREE_AUTO_UPDATE_HOURS (6) since the last refresh.
    assert!(!free_auto_update_due(&entries, 0, 16 * HOUR - 1));
    assert!(free_auto_update_due(&entries, 0, 16 * HOUR));
    // A recent automatic attempt counts too.
    assert!(!free_auto_update_due(&entries, 12 * HOUR, 16 * HOUR));
    // Its own interval.
    let twelve = [free(
        "a",
        SubscriptionMetadata {
            update_interval_hours: Some(12),
            ..on.clone()
        },
    )];
    assert!(!free_auto_update_due(&twelve, 0, 21 * HOUR));
    assert!(free_auto_update_due(&twelve, 0, 22 * HOUR));
    // The switch off on any server, or no free servers at all: never.
    let mixed = [
        free("a", on.clone()),
        free(
            "b",
            SubscriptionMetadata {
                auto_update_enabled: false,
                ..on.clone()
            },
        ),
    ];
    assert!(!free_auto_update_due(&mixed, 0, 100 * HOUR));
    assert!(!free_auto_update_due(
        &[entry("o", &standard("O", "o.example"), None)],
        0,
        100 * HOUR
    ));
}

// ---------------- logs ----------------

#[test]
fn the_logs_export_numbers_the_lines() {
    assert_eq!(LOGS_FILE_NAME, "olcbox-logs.txt");
    assert_eq!(
        build_logs_export(&["first".into(), "second".into()]),
        "YPtun application logs\nEntries: 2\n\n1. first\n2. second\n"
    );
    assert_eq!(
        build_logs_export(&[]),
        "YPtun application logs\nEntries: 0\n\n"
    );
}
