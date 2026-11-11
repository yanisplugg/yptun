use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use yptun_core::routing::{RoutingProfilesState, RoutingRules, TrafficSettings};
use yptun_core::settings::AppBehaviorSettings;
use yptun_core::vpn_settings::*;

fn temp_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("yptun-set-{tag}-{}-{nanos}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

#[test]
fn missing_files_give_defaults() {
    let root = temp_dir("defaults");
    let s = VpnSettings::new(&root);
    assert_eq!(s.load_traffic(), TrafficSettings::default().normalized());
    assert_eq!(s.load_routing(), RoutingRules::default());
    assert_eq!(s.load_routing_profiles(), RoutingProfilesState::default());
    assert_eq!(s.load_app_behavior(), AppBehaviorSettings::default());
    let split = s.load_split_tunnel();
    assert_eq!(split, SplitTunnelState::default());
    assert_eq!(split.mode, "all_apps");
    assert!(s.dir().is_dir());
    assert_eq!(s.dir(), root.join("settings").as_path());
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn split_tunnel_round_trip_and_pretty_json() {
    let root = temp_dir("split");
    let s = VpnSettings::new(&root);
    let mut st = SplitTunnelState::default();
    st.mode = "proxy_selected".to_string();
    st.proxy_processes.insert("telegram.exe".to_string());
    st.bypass_processes.insert("steam.exe".to_string());
    assert!(s.save_split_tunnel(&st));
    assert_eq!(s.load_split_tunnel(), st);
    let text = fs::read_to_string(s.path(SPLIT_TUNNEL_FILE)).unwrap();
    assert!(text.starts_with("{\n    \"mode\": \"proxy_selected\",\n    \"proxyProcesses\": ["));

    assert_eq!(
        to_pretty_json(&SplitTunnelState::default()).unwrap(),
        "{\n    \"mode\": \"all_apps\",\n    \"proxyProcesses\": [],\n    \"bypassProcesses\": []\n}"
    );
    let _ = fs::remove_dir_all(&root);
}

#[test]
fn bad_or_partial_files() {
    let root = temp_dir("bad");
    let s = VpnSettings::new(&root);
    fs::create_dir_all(s.dir()).unwrap();

    fs::write(s.path(SPLIT_TUNNEL_FILE), "not json").unwrap();
    assert_eq!(s.load_split_tunnel(), SplitTunnelState::default());

    fs::write(s.path(SPLIT_TUNNEL_FILE), "{\"mode\": 5}").unwrap();
    assert_eq!(s.load_split_tunnel(), SplitTunnelState::default());

    fs::write(
        s.path(SPLIT_TUNNEL_FILE),
        "{\"mode\":\"proxy\",\"proxyProcesses\":[\"a.exe\"],\"future\":1}",
    )
    .unwrap();
    let st = s.load_split_tunnel();
    assert_eq!(st.mode, "proxy");
    assert!(st.proxy_processes.contains("a.exe"));
    assert!(st.bypass_processes.is_empty());

    fs::write(s.path(TRAFFIC_FILE), "[]").unwrap();
    assert_eq!(s.load_traffic(), TrafficSettings::default().normalized());
    let _ = fs::remove_dir_all(&root);
}
