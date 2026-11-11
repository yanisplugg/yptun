//! Ported from SubscriptionKeyRenameTest plus storage-compatibility checks.

use std::collections::BTreeSet;

use yptun_core::model::ProxyCore;
use yptun_core::settings::*;

fn set(v: &[&str]) -> BTreeSet<String> {
    v.iter().map(|s| s.to_string()).collect()
}

#[test]
fn pins_collapse_sort_and_folder_membership_follow_the_new_key() {
    let (old, new, other) = ("Panel|https://one.test/sub", "Panel|https://moved.test/sub", "Other|https://two.test/sub");
    let before = AppBehaviorSettings {
        collapsed_subscription_groups: set(&[old]),
        pinned_subscription_groups: vec![other.into(), old.into()],
        ping_sorted_subscription_groups: set(&[old]),
        ping_sort_descending_subscription_groups: set(&[old, other]),
        custom_groups: vec![CustomGroup { id: "f".into(), name: "Folder".into(), members: vec![CustomGroup::sub_member(old), CustomGroup::sub_member(other)], ..Default::default() }],
        ..Default::default()
    };
    let after = before.with_subscription_key_renamed(old, new);
    assert_eq!(after.collapsed_subscription_groups, set(&[new]));
    assert_eq!(after.pinned_subscription_groups, [other, new]);
    assert_eq!(after.ping_sorted_subscription_groups, set(&[new]));
    assert_eq!(after.ping_sort_descending_subscription_groups, set(&[new, other]));
    assert_eq!(after.custom_groups[0].members, [CustomGroup::sub_member(new), CustomGroup::sub_member(other)]);
    assert_eq!(before.with_subscription_key_renamed(old, old), before);
}

#[test]
fn loads_kotlin_settings_json() {
    let json = r#"{"showAutoButton":false,"pingUrl":"https://google.com","subscriptionUserAgent":"yptun",
        "lastPingResults":{"a":42,"b":null},"globalProxyCore":"xray","pingTimeoutSec":99,"customGroups":[{"id":"g","name":"G"}]}"#;
    let s: AppBehaviorSettings = serde_json::from_str(json).unwrap();
    assert!(!s.show_auto_button && s.confirm_before_delete && s.share_universal_link);
    assert_eq!(s.effective_ping_url(), DEFAULT_PING_URL);
    assert_eq!(s.effective_subscription_ua(), SUB_UA_BOTH);
    assert_eq!((s.last_ping_results["a"], s.last_ping_results["b"]), (Some(42), None));
    assert_eq!(s.global_proxy_core, ProxyCore::Xray);
    assert_eq!(s.effective_ping_timeout_ms(), 15_000);
    assert_eq!(s.custom_groups[0].members.len(), 0);
    let back: AppBehaviorSettings = serde_json::from_str(&serde_json::to_string(&s).unwrap()).unwrap();
    assert_eq!(back, s);
    assert!(serde_json::to_string(&s).unwrap().contains("\"lastPingResults\":{\"a\":42,\"b\":null}"));
}
