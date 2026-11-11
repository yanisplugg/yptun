use std::collections::BTreeSet;
use std::path::PathBuf;

use yptun_core::desktop_settings::*;
use yptun_core::i18n::AppLanguage;
use yptun_core::routing::{RoutingProfile, RoutingProfilesState};
use yptun_core::vpn_settings::SplitTunnelState;

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("yptun-ds-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn set(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|s| s.to_string()).collect()
}

fn profile(id: &str, name: &str) -> RoutingProfile {
    RoutingProfile { id: id.into(), name: name.into(), ..Default::default() }
}

#[test]
fn ui_defaults_encode_nulls_and_ignore_unknown_keys() {
    let json = serde_json::to_string(&DesktopUiSettings::default()).unwrap();
    assert!(json.contains("\"language\":\"system\""));
    assert!(json.contains("\"connectionMode\":\"tun\""));
    assert!(json.contains("\"accentArgb\":null"));
    let parsed: DesktopUiSettings = serde_json::from_str(r#"{"lightTheme":true,"accentArgb":-16776961,"x":1}"#).unwrap();
    assert!(parsed.light_theme);
    assert_eq!(parsed.accent_argb, Some(-16776961));
    assert_eq!(parsed.language, "system");
}

#[test]
fn system_language_from_locale() {
    assert_eq!(system_language("ru"), AppLanguage::Russian);
    assert_eq!(system_language("fa"), AppLanguage::Persian);
    assert_eq!(system_language("zh"), AppLanguage::Chinese);
    assert_eq!(system_language("sk"), AppLanguage::Slovak);
    assert_eq!(system_language("cs"), AppLanguage::Czech);
    assert_eq!(system_language("be"), AppLanguage::Belarusian);
    assert_eq!(system_language("de"), AppLanguage::English);
}

#[test]
fn portable_without_elevation_forces_proxy() {
    assert_eq!(initial_connection_mode("tun", true, false), ("proxy", true));
    assert_eq!(initial_connection_mode("proxy", true, false), ("proxy", false));
    assert_eq!(initial_connection_mode("tun", true, true), ("tun", false));
    assert_eq!(initial_connection_mode("proxy", false, false), ("proxy", false));
    assert_eq!(initial_connection_mode("weird", false, false), ("tun", false));
}

#[test]
fn toggle_adds_and_removes() {
    assert_eq!(toggled(&set(&["a.exe"]), "a.exe"), set(&[]));
    assert_eq!(toggled(&set(&["a.exe"]), "b.exe"), set(&["a.exe", "b.exe"]));
    let s = toggle_split_tunnel_app(&SplitTunnelState::default(), SplitTunnelList::Bypass, "x.exe");
    assert_eq!(s.bypass_processes, set(&["x.exe"]));
    assert!(s.proxy_processes.is_empty());
    let s = set_split_tunnel_apps(&s, SplitTunnelList::Proxy, set(&["p"]));
    assert_eq!(s.proxy_processes, set(&["p"]));
}

#[test]
fn installed_apps_lists_exe_names_and_selected() {
    assert_eq!(process_exe_name("C:\\Program Files\\App\\App.exe"), "App.exe");
    assert_eq!(process_exe_name("/usr/bin/firefox"), "firefox");
    let commands: Vec<String> = ["C:\\a\\zed.EXE", "/usr/bin/bash", "/usr/lib/libx.so", "  ", "C:\\b\\zed.EXE", "C:\\Alpha.exe"]
        .iter()
        .map(|s| s.to_string())
        .collect();
    let state = SplitTunnelState { proxy_processes: set(&["beta.exe"]), bypass_processes: set(&["bash"]), ..Default::default() };
    assert_eq!(installed_apps(&commands, &state), vec!["Alpha.exe", "bash", "beta.exe", "zed.EXE"]);
}

#[test]
fn routing_profile_ids_and_crud() {
    let id = new_routing_profile_id();
    assert!(id.starts_with("rp-") && id.len() == 11);
    assert!(id[3..].chars().all(|c| c.is_ascii_hexdigit()));
    let (s, a) = save_routing_profile(&RoutingProfilesState::default(), profile("", "A"));
    assert!(a.starts_with("rp-"));
    let (s, b) = save_routing_profile(&s, profile("b", "B"));
    let (s, _) = save_routing_profile(&s, profile(&a, "A2"));
    assert_eq!(s.profiles.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), vec!["B", "A2"]);
    let s = with_global_routing_profile(&s, &b);
    assert_eq!(s.global_profile_id, "b");
    let s = delete_routing_profile(&s, "b");
    assert_eq!(s.global_profile_id, "");
    assert_eq!(s.profiles.len(), 1);
}

#[test]
fn routing_link_import_replaces_state() {
    let mut exported = RoutingProfilesState::default();
    exported.profiles.push(profile("", "Imported"));
    exported.global_profile_id = "g".into();
    let link = exported.to_routing_link();
    let base = with_global_routing_profile(&RoutingProfilesState::default(), "old");
    let out = import_routing_profile_link(&base, &link).unwrap();
    assert_eq!(out.global_profile_id, "g");
    assert_eq!(out.profiles.len(), 1);
    assert!(out.profiles[0].id.starts_with("rp-"));
    assert!(import_routing_profile_link(&base, "not a link").is_none());
}

#[test]
fn geo_sources_and_update_result() {
    let s = with_geo_sources(&RoutingProfilesState::default(), "  https://a/geoip.dat ", " https://a/geosite.dat");
    assert_eq!(s.geoip_url, "https://a/geoip.dat");
    assert_eq!(s.geosite_url, "https://a/geosite.dat");
    let (ok, status) = finish_geo_update(&s, true, 1234, 99);
    assert_eq!(ok.geo_last_updated, 1234);
    assert_eq!(status, GeoUpdateStatus::Success { updated_at: 1234, bytes: 99 });
    let (failed, status) = finish_geo_update(&s, false, 1234, 0);
    assert_eq!(failed.geo_last_updated, 0);
    assert_eq!(status, GeoUpdateStatus::Failed("download failed".into()));
}

#[test]
fn controller_persists_ui_and_profiles() {
    let dir = temp_dir("controller");
    let mut s = DesktopSettings::open(&dir, "cs", true, false);
    assert_eq!(s.connection_mode(), "proxy");
    assert_eq!(s.effective_language(), AppLanguage::Czech);
    let ui = std::fs::read_to_string(dir.join("settings").join("ui.json")).unwrap();
    assert!(ui.contains("\"connectionMode\": \"proxy\""));
    assert!(ui.contains("\n    \"language\""));
    s.set_language(AppLanguage::Slovak);
    s.set_accent_color(Some(42));
    let id = s.save_routing_profile(profile("", "P"));
    s.set_global_routing_profile(&id);
    assert!(s.begin_geo_update().is_some());
    assert!(s.begin_geo_update().is_none());
    s.finish_geo_update(true, 77, 1);
    s.toggle_split_tunnel_app(SplitTunnelList::Proxy, "a.exe");

    let r = DesktopSettings::open(&dir, "en", false, false);
    assert_eq!(r.language(), AppLanguage::Slovak);
    assert_eq!(r.connection_mode(), "proxy");
    assert_eq!(r.ui().accent_argb, Some(42));
    assert_eq!(r.routing_profiles().global_profile_id, id);
    assert_eq!(r.routing_profiles().geo_last_updated, 77);
    assert_eq!(r.split_tunnel().proxy_processes, set(&["a.exe"]));
    let _ = std::fs::remove_dir_all(&dir);
}
