//! Ported from AwgIniEditorTest.

use yptun_core::awg::{self, Kind, Section::*};

const INI: &str = "[Interface]\nPrivateKey = AAA=\nAddress = 10.0.0.2/32\njc = 4\n# note\nS1 = 0\n\n[Peer]\nPublicKey = BBB=\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0";

#[test]
fn reads_values_case_insensitively_per_section() {
    assert_eq!(awg::get(INI, "Jc", Interface), "4");
    assert_eq!(awg::get(INI, "S1", Interface), "0");
    assert_eq!(awg::get(INI, "Jmax", Interface), "");
    assert_eq!(awg::get(INI, "Endpoint", Interface), "");
    assert_eq!(awg::get(INI, "Endpoint", Peer), "1.2.3.4:51820");
}

#[test]
fn replacing_keeps_the_key_case_and_everything_else() {
    let out = awg::set(INI, "Jc", "9", Interface);
    assert_eq!(out, INI.replace("jc = 4", "jc = 9"));
}

#[test]
fn new_key_goes_to_the_end_of_its_section() {
    let out = awg::set(INI, "Jmax", "70", Interface);
    let lines: Vec<&str> = out.split('\n').collect();
    let at = lines.iter().position(|l| *l == "Jmax = 70").unwrap();
    assert_eq!((lines[at - 1], lines[at + 1], lines[at + 2]), ("S1 = 0", "", "[Peer]"));
    let peer = awg::set(INI, "PersistentKeepalive", "25", Peer);
    assert_eq!(awg::get(&peer, "PersistentKeepalive", Peer), "25");
    assert_eq!(awg::get(&peer, "PersistentKeepalive", Interface), "");
}

#[test]
fn blank_value_removes_the_line() {
    assert_eq!(awg::set(INI, "S1", "", Interface), INI.replace("S1 = 0\n", ""));
    assert_eq!(awg::set(INI, "H1", "", Interface), INI);
}

#[test]
fn adds_a_missing_section_and_never_breaks_the_line() {
    let out = awg::set("[Interface]\nPrivateKey = A=", "PersistentKeepalive", "25", Peer);
    assert_eq!(awg::get(&out, "PersistentKeepalive", Peer), "25");
    assert_eq!(awg::get(&awg::set(INI, "I1", "<b 0x01>\n<r 8>", Interface), "I1", Interface), "<b 0x01><r 8>");
}

#[test]
fn protocol_is_awg_only_when_an_obfuscation_parameter_is_set() {
    assert!(awg::is_awg(INI));
    let plain = awg::to_wireguard(INI);
    assert!(!awg::is_awg(&plain));
    assert_eq!(awg::get(&plain, "AllowedIPs", Peer), "0.0.0.0/0");
    assert_eq!(awg::get(&plain, "PrivateKey", Interface), "AAA=");
    assert!(awg::is_awg(&awg::to_amneziawg(&plain)));
    assert_eq!(awg::obfuscation(INI)[..2], [("Jc".to_string(), "4".to_string()), ("S1".to_string(), "0".to_string())]);
}

#[test]
fn template_needs_keys_and_endpoint() {
    assert_eq!(awg::missing(&awg::skeleton(false)), ["PrivateKey", "PublicKey", "Endpoint"]);
    assert!(!awg::is_awg(&awg::skeleton(false)));
    assert!(awg::is_awg(&awg::skeleton(true)));
    assert!(awg::missing(INI).is_empty());
}

#[test]
fn endpoint_splits_host_and_port() {
    assert_eq!(awg::endpoint(INI), ("1.2.3.4".to_string(), 51820));
    assert_eq!(awg::endpoint("[Peer]\nEndpoint = [2001:db8::1]:443"), ("2001:db8::1".to_string(), 443));
    assert_eq!(awg::endpoint("[Interface]\nPrivateKey = A="), (String::new(), 0));
}

#[test]
fn fields_filter_what_they_accept() {
    assert_eq!(awg::filter("1a2-0", Kind::Number), "120");
    assert_eq!(awg::filter("1a-2", Kind::Range), "1-2");
    assert_eq!(awg::filter("<b 0x1>\n", Kind::Text), "<b 0x1>");
}

#[test]
fn switches_go_with_the_obfuscation() {
    let on = awg::set(INI, "RandomTrailers", "on", Interface);
    assert_eq!(awg::get(&on, "RandomTrailers", Interface), "on");
    assert!(awg::is_awg(&on));
    assert_eq!(awg::get(&awg::set(&on, "RandomTrailers", "", Interface), "RandomTrailers", Interface), "");
    let both = awg::to_wireguard(&awg::set(&on, "DisableCookies", "on", Interface));
    assert_eq!(awg::get(&both, "DisableCookies", Interface), "");
}
