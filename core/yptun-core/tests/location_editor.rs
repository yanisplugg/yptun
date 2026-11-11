//! Ported from VlessEditorTest and WireguardEngineTest, plus the rest of the add/edit form of
//! LocationViewModel: validation, the second proxy, service drafts, olcRTC installs, saving.

use std::sync::Mutex;

use yptun_core::awg::{self, Section};
use yptun_core::location::*;
use yptun_core::location_editor::*;
use yptun_core::location_list::LocationItem;
use yptun_core::model::*;
use yptun_core::panel::compose_inbound;
use yptun_core::repository::{
    ClientInfo, HttpFetcher, HttpRequest, HttpResponse, Repository, Store,
};
use yptun_core::share_link;
use yptun_core::transports::{
    AdvancedCoreConfig, ExtraRoom, MasterDnsConfig, WarpMode, WarpProtocol,
};

const UUID: &str = "11111111-2222-3333-4444-555555555555";
const VLESS: &str =
    "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#V";

const WG_CONFIG: &str = "[Interface]
PrivateKey = cHJpdmF0ZV9rZXlfZm9yX3Rlc3RzXzEyMzQ1Njc4OTA=
Address = 10.8.0.2/32
DNS = 1.1.1.1
Jc = 6
Jmin = 50
Jmax = 90
S1 = 11
S2 = 22
H1 = 111
H2 = 222
H3 = 333
H4 = 444

[Peer]
PublicKey = cHVibGljX2tleV9mb3JfdGVzdHNfMTIzNDU2Nzg5MDE=
Endpoint = 203.0.113.7:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25";

/// A form opened for a new location, as `startEditing(null)`.
fn editor() -> LocationEditor {
    let mut vm = LocationEditor::new();
    vm.start_editing(&[], None);
    vm
}

fn item(
    id: &str,
    config: &LocationConfig,
    subscription: Option<&str>,
    metadata: Option<LocationMetadata>,
) -> LocationItem {
    LocationItem::from_entry(&LocationEntry::from(
        id,
        config,
        subscription.map(str::to_string),
        metadata,
    ))
}

fn proxy(vm: &LocationEditor) -> &ProxyProfile {
    vm.editing_config.proxy.as_ref().expect("no main proxy")
}

fn knob(key: &str) -> awg::Knob {
    *awg::CONNECTION
        .iter()
        .chain(awg::KNOBS)
        .find(|k| k.key == key)
        .unwrap()
}

#[derive(Default)]
struct MemStore(Mutex<Option<LocationBundleV4>>);

impl Store for MemStore {
    fn load_bundle(&self) -> Option<LocationBundleV4> {
        self.0.lock().unwrap().clone()
    }
    fn save_bundle(&self, bundle: &LocationBundleV4) {
        *self.0.lock().unwrap() = Some(bundle.clone());
    }
}

struct Offline;

impl HttpFetcher for Offline {
    fn get(&self, _request: &HttpRequest) -> Option<HttpResponse> {
        None
    }
}

fn repository() -> Repository {
    Repository::new(
        Box::<MemStore>::default(),
        Box::new(Offline),
        ClientInfo::default(),
    )
}

// ---------------- VlessEditorTest ----------------

#[test]
fn fields_build_a_complete_profile() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.edit_vless(|p| ProxyProfile {
        server: "example.com".into(),
        server_port: 443,
        ..p
    });
    assert!(!proxy(&vm).is_complete()); // no uuid yet
    vm.edit_vless(|p| ProxyProfile {
        uuid: UUID.into(),
        security: SECURITY_REALITY.into(),
        reality_public_key: "k".into(),
        ..p
    });
    assert!(proxy(&vm).is_complete());
    assert!(vm
        .editing_proxy_link()
        .starts_with("vless://11111111-2222-3333-4444-555555555555@example.com:443"));
    assert!(vm.editing_proxy_link().contains("security=reality"));
    vm.edit_vless(|p| ProxyProfile {
        network: NETWORK_XHTTP.into(),
        ..p
    });
    assert!(vm.editing_config.requires_xray());
}

#[test]
fn vless_engine_is_a_standard_location_with_an_empty_vless_slot() {
    let mut vm = editor();
    assert!(!vm.is_vless_engine());
    vm.on_vless_engine_selected();
    assert!(vm.is_vless_engine());
    assert_eq!(vm.editing_config.engine, EngineType::Standard);
    vm.on_proxy_link_changed(&VLESS.replace("#V", "#V2"));
    assert!(proxy(&vm).is_complete());
    // Back to plain Standard keeps the proxy, drops the VLESS mode.
    vm.on_engine_changed(EngineType::Standard);
    assert!(!vm.is_vless_engine());
    assert!(proxy(&vm).is_complete());
}

#[test]
fn the_vless_entry_drops_a_proxy_of_another_kind() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.on_proxy_link_changed("trojan://secret@t.example:443?security=tls#T");
    assert_eq!(proxy(&vm).kind, TYPE_TROJAN);
    vm.on_vless_engine_selected();
    assert!(vm.editing_config.proxy.is_none());
    assert_eq!(vm.editing_proxy_link(), "");
    // A VLESS proxy stays.
    vm.on_engine_changed(EngineType::Standard);
    vm.on_proxy_link_changed(VLESS);
    vm.on_vless_engine_selected();
    assert_eq!(proxy(&vm).uuid, UUID);
}

// ---------------- WireguardEngineTest ----------------

#[test]
fn picking_the_engine_gives_an_empty_amneziawg_template_that_is_not_saveable_yet() {
    let mut vm = editor();
    vm.on_name_changed("tunnel");
    vm.on_wireguard_engine_selected();

    assert!(vm.is_wireguard_engine());
    assert_eq!(vm.editing_config.engine, EngineType::Standard);
    assert_eq!(proxy(&vm).kind, TYPE_AMNEZIAWG);
    assert!(awg::is_awg(&proxy(&vm).awg_config));
    assert_eq!(
        vm.wireguard_missing(),
        vec!["PrivateKey", "PublicKey", "Endpoint"]
    );
    assert!(!vm.is_form_valid());
}

#[test]
fn a_pasted_config_makes_the_form_valid_and_feeds_the_list_entry() {
    let mut vm = editor();
    vm.on_name_changed("tunnel");
    vm.on_wireguard_engine_selected();
    vm.on_wireguard_ini_changed(WG_CONFIG);

    assert!(vm.wireguard_missing().is_empty());
    assert!(vm.is_form_valid());
    let p = proxy(&vm);
    assert_eq!(p.server, "203.0.113.7");
    assert_eq!(p.server_port, 51820);
    assert!(p.is_complete());
}

#[test]
fn keys_and_every_parameter_can_be_edited_and_only_their_own_line_changes() {
    let mut vm = editor();
    vm.on_wireguard_engine_selected();
    vm.on_wireguard_ini_changed(WG_CONFIG);

    vm.on_awg_knob_changed(&knob("PrivateKey"), "TmV3UHJpdmF0ZUtleQ==");
    vm.on_awg_knob_changed(&knob("Endpoint"), "198.51.100.9:443");
    vm.on_awg_knob_changed(&knob("Jc"), "9");

    let ini = proxy(&vm).awg_config.clone();
    assert_eq!(
        awg::get(&ini, "PrivateKey", Section::Interface),
        "TmV3UHJpdmF0ZUtleQ=="
    );
    assert_eq!(awg::get(&ini, "Jc", Section::Interface), "9");
    assert_eq!(awg::get(&ini, "H4", Section::Interface), "444");
    assert_eq!(
        ini,
        WG_CONFIG
            .replace(
                "cHJpdmF0ZV9rZXlfZm9yX3Rlc3RzXzEyMzQ1Njc4OTA=",
                "TmV3UHJpdmF0ZUtleQ=="
            )
            .replace("203.0.113.7:51820", "198.51.100.9:443")
            .replace("Jc = 6", "Jc = 9")
    );
    // The list entry follows the Endpoint.
    assert_eq!(proxy(&vm).server, "198.51.100.9");
    assert_eq!(proxy(&vm).server_port, 443);
}

#[test]
fn switching_to_plain_wireguard_drops_obfuscation_and_switching_back_restores_it() {
    let mut vm = editor();
    vm.on_wireguard_engine_selected();
    vm.on_wireguard_ini_changed(WG_CONFIG);

    vm.on_wireguard_protocol_changed(false);
    let plain = proxy(&vm).awg_config.clone();
    assert!(!awg::is_awg(&plain));
    assert!(vm.is_wireguard_engine());
    assert_eq!(
        awg::get(&plain, "Endpoint", Section::Peer),
        "203.0.113.7:51820"
    );

    vm.on_wireguard_protocol_changed(true);
    let back = proxy(&vm).awg_config.clone();
    assert_eq!(awg::get(&back, "Jc", Section::Interface), "6");
    assert_eq!(awg::get(&back, "H4", Section::Interface), "444");
}

#[test]
fn leaving_the_engine_gives_back_the_proxy_it_replaced() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.on_proxy_link_changed(&VLESS.replace("#V", "#Plain"));
    let vless = vm.editing_config.proxy.clone();
    assert_eq!(vless.as_ref().map(|p| p.kind.as_str()), Some(TYPE_VLESS));

    vm.on_wireguard_engine_selected();
    assert!(vm.is_wireguard_engine());
    vm.on_engine_changed(EngineType::Standard);

    assert!(!vm.is_wireguard_engine());
    assert_eq!(vm.editing_config.proxy, vless);
    assert_eq!(vm.editing_proxy_link(), VLESS.replace("#V", "#Plain"));

    // A template that replaced nothing leaves nothing behind.
    vm.start_editing(&[], None);
    vm.on_wireguard_engine_selected();
    vm.on_engine_changed(EngineType::Standard);
    assert!(vm.editing_config.proxy.is_none());
}

#[test]
fn crlf_pastes_are_unified_and_knobs_are_filtered() {
    let mut vm = editor();
    vm.on_wireguard_engine_selected();
    vm.on_wireguard_ini_changed(&WG_CONFIG.replace('\n', "\r\n"));
    assert_eq!(proxy(&vm).awg_config, WG_CONFIG);
    vm.on_awg_knob_changed(&knob("Jc"), "1x2");
    assert_eq!(
        awg::get(&proxy(&vm).awg_config, "Jc", Section::Interface),
        "12"
    );
    // Blank removes the line.
    vm.on_awg_knob_changed(&knob("DNS"), "");
    assert!(!proxy(&vm).awg_config.contains("DNS"));
    // Knobs do nothing without a WireGuard proxy.
    let mut other = editor();
    other.on_awg_knob_changed(&knob("Jc"), "9");
    assert!(other.editing_config.proxy.is_none());
}

// ---------------- validation ----------------

#[test]
fn the_name_is_required_and_counted_in_utf16_units() {
    let mut vm = editor();
    vm.on_name_changed("   ");
    assert_eq!(vm.name_error(), Some(FieldError::NameEmpty));
    vm.on_name_changed(&"a".repeat(MAX_NAME_LENGTH));
    assert_eq!(vm.name_error(), None);
    vm.on_name_changed(&"a".repeat(MAX_NAME_LENGTH + 1));
    assert_eq!(vm.name_error(), Some(FieldError::NameTooLong));
    // A flag is four UTF-16 units, as the Kotlin app counted.
    vm.on_name_changed(&format!("{}🇷🇺", "a".repeat(41)));
    assert_eq!(vm.name_error(), None);
    vm.on_name_changed(&format!("{}🇷🇺", "a".repeat(42)));
    assert_eq!(vm.name_error(), Some(FieldError::NameTooLong));
    assert_eq!(
        FieldError::NameTooLong.message(),
        "Name is too long (max 45 chars)"
    );
    assert_eq!(FieldError::NameEmpty.message(), "Name cannot be empty");
}

#[test]
fn olcrtc_needs_a_room_and_a_64_hex_key() {
    let mut vm = editor();
    assert_eq!(vm.editing_config.engine, EngineType::Stealth);
    vm.on_name_changed("room");
    vm.on_server_changed(" ");
    assert_eq!(
        vm.server_error(),
        Some(FieldError::RoomEmpty { jitsi: false })
    );
    assert_eq!(
        vm.server_error().unwrap().message(),
        "Room ID cannot be empty"
    );
    vm.on_server_changed(&"r".repeat(MAX_ROOM_LENGTH + 1));
    assert_eq!(
        vm.server_error(),
        Some(FieldError::RoomTooLong { jitsi: false })
    );
    vm.on_password_changed("abc");
    assert_eq!(vm.key_error(), Some(FieldError::KeyNotHex));
    assert_eq!(
        FieldError::KeyNotHex.message(),
        "Key must be 64 hex characters"
    );
    vm.on_password_changed("");
    assert_eq!(vm.key_error(), Some(FieldError::KeyEmpty));
    assert!(!vm.is_form_valid());

    vm.on_server_changed("room-1");
    vm.on_password_changed(&"aB".repeat(32));
    assert_eq!((vm.server_error(), vm.key_error()), (None, None));
    assert!(vm.is_form_valid());

    // Jitsi rooms are URLs.
    vm.on_bypass_provider_changed(PROVIDER_JITSI);
    vm.on_server_changed("");
    assert_eq!(
        vm.server_error().unwrap().message(),
        "Room URL cannot be empty"
    );
}

#[test]
fn olcrtc_keys_are_exactly_64_hex_characters() {
    assert!(is_olcrtc_key(&"0123456789abcdefABCDEF".repeat(3)[..64]));
    assert!(!is_olcrtc_key(&"a".repeat(63)));
    assert!(!is_olcrtc_key(&"a".repeat(65)));
    assert!(!is_olcrtc_key(&format!("{}\n", "a".repeat(64))));
    assert!(!is_olcrtc_key(&format!("{}g", "a".repeat(63))));
    // Full-width or Arabic-Indic digits are not hex.
    assert!(!is_olcrtc_key(&format!("{}٣", "a".repeat(62))));
}

#[test]
fn saving_validates_the_olcrtc_fields_only_off_standard() {
    let mut vm = editor();
    vm.on_name_changed("x");
    assert_eq!(vm.save(&[]), SaveOutcome::Invalid);
    assert_eq!(
        vm.server_error(),
        Some(FieldError::RoomEmpty { jitsi: false })
    );
    assert_eq!(vm.key_error(), Some(FieldError::KeyEmpty));
    vm.on_engine_changed(EngineType::Standard);
    assert_eq!((vm.server_error(), vm.key_error()), (None, None));
    assert_eq!(vm.save(&[]), SaveOutcome::Invalid); // no proxy
    assert_eq!((vm.server_error(), vm.key_error()), (None, None));
}

// ---------------- proxies ----------------

#[test]
fn a_pasted_link_or_outbound_becomes_the_main_proxy() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.on_proxy_link_changed("garbage");
    assert_eq!(vm.proxy_error(), Some(FieldError::UnrecognizedProxy));
    assert_eq!(
        FieldError::UnrecognizedProxy.message(),
        "Unrecognized proxy link or sing-box config"
    );
    assert!(vm.editing_config.proxy.is_none());
    assert_eq!(vm.editing_proxy_link(), "garbage");

    // An incomplete link is refused too.
    vm.on_proxy_link_changed("vless://@example.com:443");
    assert_eq!(vm.proxy_error(), Some(FieldError::UnrecognizedProxy));

    let outbound = r#"{"type":"tuic","server":"t.example","server_port":"8443","uuid":"u"}"#;
    vm.on_proxy_link_changed(&format!("  {outbound}  "));
    assert_eq!(vm.proxy_error(), None);
    let p = proxy(&vm);
    assert_eq!(
        (
            p.kind.as_str(),
            p.server.as_str(),
            p.server_port,
            p.tag.as_str()
        ),
        ("tuic", "t.example", 8443, "t.example")
    );
    assert_eq!(p.raw_outbound.as_deref(), Some(outbound));

    vm.on_proxy_link_changed("   ");
    assert_eq!(vm.proxy_error(), None);
    assert!(vm.editing_config.proxy.is_none());
}

#[test]
fn raw_outbounds_need_a_type_and_a_server() {
    let p = raw_outbound_profile(r#"{"type":"socks","tag":"s","server":"h","server_port":1080}"#)
        .unwrap();
    assert_eq!(
        (p.tag.as_str(), p.kind.as_str(), p.server_port),
        ("s", "socks", 1080)
    );
    let p = raw_outbound_profile(r#"{"type":"socks","tag":"  ","server":"h"}"#).unwrap();
    assert_eq!((p.tag.as_str(), p.server_port), ("h", 0));
    assert!(raw_outbound_profile(r#"{"type":"socks"}"#).is_none());
    assert!(raw_outbound_profile(r#"{"server":"h"}"#).is_none());
    assert!(raw_outbound_profile(r#" {"type":"socks","server":"h"}"#).is_none());
    assert!(raw_outbound_profile("[1]").is_none());
    assert!(raw_outbound_profile("{broken").is_none());
}

#[test]
fn the_cascade_link_fills_the_main_slot_first_and_refuses_itself() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    // No main proxy yet: the link becomes the main one (that field is hidden there).
    vm.on_proxy2_link_changed(&format!(" {VLESS} "));
    assert_eq!(proxy(&vm).uuid, UUID);
    assert!(vm.editing_config.proxy2.is_none());
    assert_eq!(vm.editing_proxy_link(), VLESS);
    assert_eq!(vm.proxy2_error(), None);

    // The same node under another name.
    vm.on_proxy2_link_changed(&VLESS.replace("#V", "#Same"));
    assert_eq!(vm.proxy2_error(), Some(FieldError::SecondProxySameAsMain));
    assert!(vm.editing_config.proxy2.is_none());

    vm.on_proxy2_link_changed("trojan://secret@other.example:443?security=tls#T");
    assert_eq!(vm.proxy2_error(), None);
    assert_eq!(
        vm.editing_config.proxy2.as_ref().unwrap().server,
        "other.example"
    );

    vm.on_proxy2_link_changed("nope");
    assert_eq!(vm.proxy2_error(), Some(FieldError::UnrecognizedProxy));
    assert!(vm.editing_config.proxy2.is_none());
    vm.on_proxy2_link_changed(" ");
    assert_eq!(vm.proxy2_error(), None);
    assert_eq!(vm.editing_proxy2_link(), " ");
}

#[test]
fn off_standard_the_cascade_link_is_always_the_second_proxy() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Stealth);
    vm.on_proxy2_link_changed(VLESS);
    assert!(vm.editing_config.proxy.is_none());
    assert_eq!(vm.editing_config.proxy2.as_ref().unwrap().uuid, UUID);
}

#[test]
fn a_yptun_inbound_link_cascades_into_its_exit() {
    let main = share_link::parse("trojan://secret@entry.example:443?security=tls#Entry").unwrap();
    let exit = share_link::parse("trojan://secret@exit.example:443?security=tls#Exit").unwrap();
    let shared = LocationConfig {
        name: "Shared".into(),
        engine: EngineType::Standard,
        proxy: Some(main.clone()),
        proxy2: Some(exit),
        ..LocationConfig::default()
    };
    let link = compose_inbound(&shared);
    assert_eq!(proxy_from_any_link(&link).unwrap().server, "exit.example");
    let single = LocationConfig {
        proxy2: None,
        ..shared
    };
    assert_eq!(
        proxy_from_any_link(&compose_inbound(&single))
            .unwrap()
            .server,
        "entry.example"
    );
    assert!(proxy_from_any_link("hello").is_none());
}

#[test]
fn xray_only_configs_cannot_pick_sing_box() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.on_proxy_link_changed(VLESS);
    vm.edit_vless(|p| ProxyProfile {
        network: NETWORK_XHTTP.into(),
        ..p
    });
    vm.on_core_changed(ProxyCore::SingBox);
    assert_eq!(vm.editing_config.core, ProxyCore::Xray);
    vm.edit_vless(|p| ProxyProfile {
        network: NETWORK_TCP.into(),
        ..p
    });
    vm.on_core_changed(ProxyCore::SingBox);
    assert_eq!(vm.editing_config.core, ProxyCore::SingBox);
    vm.on_core_changed(ProxyCore::Auto);
    assert_eq!(vm.editing_config.core, ProxyCore::Auto);
}

#[test]
fn openvpn_cannot_pick_xray() {
    let mut vm = editor();
    vm.editing_config.engine = EngineType::Standard;
    vm.editing_config.proxy = Some(ProxyProfile {
        kind: TYPE_OPENVPN.into(),
        server: "vpn.example".into(),
        server_port: 1194,
        raw_outbound: Some(r#"{"type":"openvpn-client","server":"vpn.example","server_port":1194}"#.into()),
        ..Default::default()
    });
    vm.on_core_changed(ProxyCore::Xray);
    assert_eq!(vm.editing_config.core, ProxyCore::SingBox);
}

// ---------------- olcRTC services and rooms ----------------

#[test]
fn switching_the_service_keeps_each_services_room_and_key() {
    let mut vm = editor();
    let key = "a".repeat(64);
    assert_eq!(vm.editing_service_provider(), DEFAULT_BYPASS_PROVIDER);
    vm.on_server_changed("mts-room");
    vm.on_password_changed(&key);
    vm.on_bypass_provider_changed(PROVIDER_TELEMOST);
    assert_eq!(
        (
            vm.editing_config.id.as_str(),
            vm.editing_config.key.as_str()
        ),
        ("", "")
    );
    assert_eq!(vm.editing_service_provider(), PROVIDER_TELEMOST);
    assert_eq!(vm.editing_config.transport, TRANSPORT_VP8CHANNEL);
    vm.on_server_changed("tm-room");

    vm.on_bypass_provider_changed("mts");
    assert_eq!(vm.editing_config.id, "mts-room");
    assert_eq!(vm.editing_config.key, key);

    // Jitsi is never shown as the picked service.
    vm.on_bypass_provider_changed(PROVIDER_JITSI);
    assert_eq!(vm.editing_config.bypass_provider, PROVIDER_JITSI);
    assert_eq!(vm.editing_service_provider(), PROVIDER_MTS_LINK);

    vm.on_bypass_provider_changed(PROVIDER_TELEMOST);
    assert_eq!(vm.editing_config.id, "tm-room");
    assert_eq!(vm.server_error(), None);

    // Picking the current service again changes nothing.
    vm.on_bypass_provider_changed("yandex");
    assert_eq!(vm.editing_config.id, "tm-room");
}

#[test]
fn transports_follow_the_service() {
    let mut vm = editor();
    vm.on_bypass_provider_changed(PROVIDER_JAZZ);
    vm.on_transport_changed("vp8");
    assert_eq!(vm.editing_config.transport, TRANSPORT_DATACHANNEL);
    vm.on_bypass_provider_changed(PROVIDER_TELEMOST);
    vm.on_transport_changed("sei");
    assert_eq!(vm.editing_config.transport, TRANSPORT_SEICHANNEL);
}

#[test]
fn an_olcrtc_install_lays_out_the_main_and_extra_rooms() {
    let mut vm = editor();
    let key = "b".repeat(64);
    let rooms: Vec<String> = [" r1 ", "", "r2", "r3", "r4", "r5", "r6"]
        .map(String::from)
        .to_vec();
    vm.apply_olcrtc_server_install(PROVIDER_TELEMOST, "sei", &rooms, &key);
    let c = &vm.editing_config;
    assert_eq!((c.id.as_str(), c.key.as_str()), ("r1", key.as_str()));
    assert_eq!(c.bypass_provider, PROVIDER_TELEMOST);
    assert_eq!(c.transport, TRANSPORT_SEICHANNEL);
    assert!(c.multi_room_enabled);
    let extra: Vec<&str> = c.extra_rooms.iter().map(|r| r.room.as_str()).collect();
    assert_eq!(extra, ["r2", "r3", "r4", "r5"]);
    assert_eq!(MAX_EXTRA_ROOMS, 4);
    assert!(c
        .extra_rooms
        .iter()
        .all(|r| r.key == key && r.provider == PROVIDER_TELEMOST && r.transport == "sei"));
    assert_eq!((vm.server_error(), vm.key_error()), (None, None));

    // One room: multi-room off.
    vm.apply_olcrtc_server_install(PROVIDER_TELEMOST, "sei", &["solo".to_string()], &key);
    assert!(!vm.editing_config.multi_room_enabled);
    assert!(vm.editing_config.extra_rooms.is_empty());

    // No usable room: nothing changes.
    vm.apply_olcrtc_server_install(PROVIDER_JAZZ, "x", &[" ".to_string()], "k");
    assert_eq!(vm.editing_config.id, "solo");
    assert_eq!(vm.editing_config.bypass_provider, PROVIDER_TELEMOST);
}

#[test]
fn extra_rooms_are_capped_and_edited_by_index() {
    let mut vm = editor();
    vm.on_bypass_provider_changed(PROVIDER_WB_STREAM);
    for _ in 0..6 {
        vm.on_extra_room_add();
    }
    assert_eq!(vm.editing_config.extra_rooms.len(), MAX_EXTRA_ROOMS);
    assert!(vm
        .editing_config
        .extra_rooms
        .iter()
        .all(|r| r.provider == PROVIDER_WB_STREAM && r.room.is_empty()));
    vm.on_extra_room_changed(1, |r| ExtraRoom {
        room: "second".into(),
        ..r
    });
    vm.on_extra_room_changed(9, |r| ExtraRoom {
        room: "nope".into(),
        ..r
    });
    vm.on_extra_room_removed(0);
    vm.on_extra_room_removed(9);
    assert_eq!(vm.editing_config.extra_rooms.len(), 3);
    assert_eq!(vm.editing_config.extra_rooms[0].room, "second");
    vm.on_multi_room_toggle(true);
    vm.on_multi_room_bond_toggle(true);
    assert!(vm.editing_config.multi_room_enabled && vm.editing_config.multi_room_bond);
}

#[test]
fn number_fields_keep_digits_only() {
    let mut vm = editor();
    vm.on_bond_port_changed("12a3");
    assert_eq!(vm.editing_config.bond_port, 123);
    vm.on_bond_port_changed("");
    assert_eq!(vm.editing_config.bond_port, 0);
    // Beyond Int: 0, like toIntOrNull() ?: 0.
    vm.on_bond_port_changed("99999999999");
    assert_eq!(vm.editing_config.bond_port, 0);
    // Arabic-Indic digits are digits to Kotlin too.
    vm.on_vp8_fps_changed("٣٠ fps");
    assert_eq!(vm.editing_config.vp8_fps, 30);
    vm.on_vp8_batch_changed("x");
    assert_eq!(vm.editing_config.vp8_batch, 0);
    vm.on_subscription_interval_changed("4x8h12");
    assert_eq!(vm.editing_subscription_interval_hours(), "481");
}

// ---------------- engines ----------------

#[test]
fn engines_carry_only_their_own_settings() {
    let mut vm = editor();
    vm.on_name_changed("vk");
    vm.on_engine_changed(EngineType::VkTurn);
    assert!(vm.editing_config.vkturn.is_some());
    assert!(vm.editing_config.proxy.is_some()); // the derived exit
    vm.on_engine_changed(EngineType::Standard);
    assert!(vm.editing_config.vkturn.is_none());
    assert!(vm.editing_config.proxy.is_none());

    vm.update_master_dns(|d| MasterDnsConfig {
        domains: "t.example.com".into(),
        ..d
    });
    vm.on_engine_changed(EngineType::MasterDns);
    assert_eq!(
        vm.editing_config.master_dns.as_ref().unwrap().domains,
        "t.example.com"
    );
    vm.on_engine_changed(EngineType::OpenFlux);
    assert!(vm.editing_config.master_dns.is_none());
    assert!(vm.editing_config.open_flux.is_some());
    vm.on_engine_changed(EngineType::Snolc);
    assert!(vm.editing_config.open_flux.is_none());
    assert!(vm.editing_config.snolc.is_some());
    vm.on_engine_changed(EngineType::Chain);
    assert!(vm.editing_config.snolc.is_none());
    // The drafts survive the round trip.
    assert_eq!(vm.editing_master_dns().domains, "t.example.com");
}

#[test]
fn the_vk_turn_proxy_tag_follows_the_name() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::VkTurn);
    assert_eq!(proxy(&vm).tag, "VK-TURN");
    vm.on_name_changed("Home");
    assert_eq!(proxy(&vm).tag, "Home");
    vm.on_vk_link_changed("https://vk.com/call/join/abc");
    assert_eq!(vm.editing_vk_turn().vk_link, "https://vk.com/call/join/abc");
    assert_eq!(
        vm.editing_config.vkturn.as_ref().unwrap().vk_link,
        "https://vk.com/call/join/abc"
    );
}

#[test]
fn advanced_and_switches_edit_the_config() {
    let mut vm = editor();
    vm.update_advanced(|a| AdvancedCoreConfig {
        mux_enabled: true,
        ..a
    });
    assert!(vm.editing_config.advanced.as_ref().unwrap().mux_enabled);
    vm.on_proxy_enabled_changed(true);
    vm.on_warp_enabled_changed(true);
    vm.on_warp_mode_changed(WarpMode::Server);
    vm.on_warp_protocol_changed(WarpProtocol::Awg);
    vm.on_routing_profile_changed("none");
    let c = &vm.editing_config;
    assert!(c.proxy_enabled && c.warp_enabled);
    assert_eq!(
        (c.warp_mode, c.warp_protocol, c.routing_profile_id.as_str()),
        (WarpMode::Server, WarpProtocol::Awg, "none")
    );
}

// ---------------- opening and saving ----------------

#[test]
fn a_new_location_starts_blank_with_the_proxy_switch_off() {
    let vm = editor();
    assert_eq!(vm.editing_id, None);
    assert!(!vm.editing_config.proxy_enabled);
    assert_eq!(vm.editing_name, "");
    assert_eq!(vm.editing_subscription_url(), None);
    assert_eq!(vm.editing_subscription_interval_hours(), "24");
    assert!(!vm.is_saving());
}

#[test]
fn editing_a_subscription_location_keeps_its_id_and_saves_the_interval() {
    let config = LocationConfig {
        name: "Sub".into(),
        engine: EngineType::Standard,
        proxy: share_link::parse(VLESS),
        ..LocationConfig::default()
    };
    let metadata = LocationMetadata {
        subscription: Some(SubscriptionMetadata {
            update_interval_hours: Some(12),
            ..SubscriptionMetadata::default()
        }),
        ..LocationMetadata::default()
    };
    let url = "https://panel.example/sub";
    let items = vec![item("sub_1", &config, Some(url), Some(metadata))];
    let mut vm = LocationEditor::new();
    vm.start_editing(&items, Some("sub_1"));
    assert_eq!(vm.editing_name, "Sub");
    assert_eq!(vm.editing_id.as_deref(), Some("sub_1"));
    assert_eq!(vm.editing_subscription_url(), Some(url));
    assert_eq!(vm.editing_subscription_interval_hours(), "12");
    assert!(vm.editing_proxy_link().starts_with("vless://"));
    assert!(vm.is_vless_engine());
    assert!(!vm.editing_locked(&items));

    vm.on_subscription_interval_changed("6");
    match vm.save(&items) {
        SaveOutcome::Save {
            storage_id,
            subscription_interval,
            config,
        } => {
            assert_eq!(storage_id, "sub_1");
            assert_eq!(subscription_interval, Some((url.to_string(), 6)));
            assert_eq!(config.name, "Sub");
        }
        other => panic!("{other:?}"),
    }
    // A blank interval saves the default.
    vm.finish_saving();
    vm.on_subscription_interval_changed("");
    match vm.save(&items) {
        SaveOutcome::Save {
            subscription_interval,
            ..
        } => assert_eq!(subscription_interval, Some((url.to_string(), 24))),
        other => panic!("{other:?}"),
    }
}

#[test]
fn locked_locations_are_reported_locked() {
    let config = LocationConfig {
        name: "L".into(),
        engine: EngineType::Standard,
        proxy: share_link::parse(VLESS),
        ..LocationConfig::default()
    };
    let locked = LocationMetadata {
        locked: true,
        ..LocationMetadata::default()
    };
    let items = vec![item("enc", &config, Some("https://x/sub"), Some(locked))];
    let mut vm = LocationEditor::new();
    vm.start_editing(&items, Some("enc"));
    assert!(vm.editing_locked(&items));
}

#[test]
fn saving_a_new_location_gets_a_fresh_id_and_writes_through_the_repository() {
    let mut vm = editor();
    vm.on_engine_changed(EngineType::Standard);
    vm.on_name_changed("  Mine  ");
    vm.on_proxy_link_changed(VLESS);
    let outcome = vm.save(&[]);
    let SaveOutcome::Save {
        storage_id,
        config,
        subscription_interval,
    } = &outcome
    else {
        panic!("{outcome:?}")
    };
    let number: u32 = storage_id.strip_prefix("custom_").unwrap().parse().unwrap();
    assert!((100..=999).contains(&number));
    assert_eq!(config.name, "Mine");
    assert_eq!(*subscription_interval, None);

    // A second tap while saving does nothing.
    assert!(vm.is_saving());
    assert_eq!(vm.save(&[]), SaveOutcome::Invalid);

    let repo = repository();
    assert!(outcome.apply(&repo));
    assert_eq!(
        repo.active_location_id().as_deref(),
        Some(storage_id.as_str())
    );
    assert_eq!(repo.load_location(storage_id).unwrap().name, "Mine");
    assert!(!SaveOutcome::Invalid.apply(&repo));
    vm.finish_saving();
    assert!(!vm.is_saving());

    // The same server again selects the saved one instead of adding a copy.
    let items: Vec<LocationItem> = repo
        .all_locations()
        .iter()
        .map(LocationItem::from_entry)
        .collect();
    let mut again = editor();
    again.on_engine_changed(EngineType::Standard);
    again.on_name_changed("Another name");
    again.on_proxy_link_changed(&VLESS.replace("#V", "#Copy"));
    let select = again.save(&items);
    assert_eq!(
        select,
        SaveOutcome::SelectExisting {
            storage_id: storage_id.clone()
        }
    );
    repo.set_active_location_id(None);
    assert!(select.apply(&repo));
    assert_eq!(
        repo.active_location_id().as_deref(),
        Some(storage_id.as_str())
    );
}

#[test]
fn subscription_intervals_are_written_on_save() {
    let config = LocationConfig {
        name: "Sub".into(),
        engine: EngineType::Standard,
        proxy: share_link::parse(VLESS),
        ..LocationConfig::default()
    };
    let url = "https://panel.example/sub";
    let repo = repository();
    repo.save_bundle(LocationBundleV4 {
        active_location_id: Some("s".into()),
        locations: vec![LocationEntry::from("s", &config, Some(url.into()), None)],
        ..LocationBundleV4::default()
    });
    let items: Vec<LocationItem> = repo
        .all_locations()
        .iter()
        .map(LocationItem::from_entry)
        .collect();
    let mut vm = LocationEditor::new();
    vm.start_editing(&items, Some("s"));
    vm.on_subscription_interval_changed("48");
    assert!(vm.save(&items).apply(&repo));
    let stored = repo.all_locations();
    let sub = stored[0]
        .metadata
        .as_ref()
        .unwrap()
        .subscription
        .as_ref()
        .unwrap();
    assert_eq!(sub.update_interval_hours, Some(48));
}

#[test]
fn new_ids_never_collide() {
    let items: Vec<LocationItem> = (100..1000)
        .map(|n| LocationItem {
            storage_id: format!("custom_{n}"),
            ..LocationItem::default()
        })
        .collect();
    let id = new_custom_id(&items);
    assert!(id.starts_with("custom_"));
    assert!(!items.iter().any(|it| it.storage_id == id));
    assert_eq!(id.matches('_').count(), 2);
    let free = new_custom_id(&items[..899]);
    assert_eq!(free, "custom_999");
}
