use std::collections::BTreeMap;

use yptun_core::location::LocationConfig;
use yptun_core::olcrtc_command::{desktop_provider_arg, yaml_value, OlcRtcCommand, DNS_SERVERS};

fn loc(provider: &str, transport: &str) -> LocationConfig {
    LocationConfig {
        id: "room-1".into(),
        key: "k'ey".into(),
        bypass_provider: provider.into(),
        transport: transport.into(),
        ..Default::default()
    }
}

fn head(provider: &str, transport: &str) -> String {
    format!(
        "mode: cnc\nlink: direct\nauth:\n  provider: '{provider}'\nroom:\n  id: 'room-1'\ncrypto:\n  key: 'k''ey'\nnet:\n  transport: '{transport}'\n  dns: '77.88.8.8:53,8.8.8.8:53,1.1.1.1:53'\nsocks:\n"
    )
}

#[test]
fn quoting_and_provider() {
    assert_eq!(yaml_value("a'b"), "'a''b'");
    assert_eq!(yaml_value(""), "''");
    assert_eq!(desktop_provider_arg("wb-stream"), "wbstream");
    assert_eq!(desktop_provider_arg("vk"), "vkcalls");
    assert_eq!(desktop_provider_arg(""), "mtslink");
    assert_eq!(DNS_SERVERS, "77.88.8.8:53,8.8.8.8:53,1.1.1.1:53");
}

#[test]
fn args_are_binary_and_config() {
    let c = OlcRtcCommand::new("/opt/olcrtc", loc("telemost", "vp8channel"));
    assert_eq!(c.args("/tmp/c.yaml"), vec!["/opt/olcrtc".to_string(), "/tmp/c.yaml".to_string()]);
}

#[test]
fn vp8_defaults() {
    let l = loc("telemost", "vp8channel");
    let n = l.normalized();
    let c = OlcRtcCommand::new("olcrtc", l);
    let expected = head("telemost", "vp8channel")
        + &format!("  host: '127.0.0.1'\n  port: 10808\nvp8:\n  fps: {}\n  batch_size: {}\ndata: 'data'\n", n.vp8_fps, n.vp8_batch);
    assert_eq!(c.yaml(), expected);
}

#[test]
fn auth_and_data_dir() {
    let mut c = OlcRtcCommand::new("olcrtc", loc("telemost", "datachannel"));
    c.socks_host = "127.0.0.2".into();
    c.socks_port = 1080;
    c.socks_user = "u".into();
    c.socks_pass = "p'w".into();
    c.data_dir = Some("C:\\data dir".into());
    let expected = head("telemost", "datachannel") + "  host: '127.0.0.2'\n  port: 1080\n  user: 'u'\n  pass: 'p''w'\ndata: 'C:\\data dir'\n";
    assert_eq!(c.yaml(), expected);

    c.socks_user = "  ".into();
    assert!(!c.yaml().contains("user:"));
}

#[test]
fn sei_uses_location_options() {
    let mut l = loc("telemost", "seichannel");
    l.transport_options = BTreeMap::from([("fps".to_string(), "30".to_string()), ("frag".to_string(), "1200".to_string())]);
    let s = l.normalized().sei_options();
    let y = OlcRtcCommand::new("olcrtc", l).yaml();
    let block = format!(
        "sei:\n  fps: {}\n  batch_size: {}\n  fragment_size: {}\n  ack_timeout_ms: {}\ndata: 'data'\n",
        s.fps, s.batch, s.fragment_size, s.ack_timeout_ms
    );
    assert!(y.ends_with(&block), "{y}");
    assert!(!y.contains("vp8:"));
}

#[test]
fn video_uses_location_options() {
    let l = loc("telemost", "videochannel");
    let v = l.normalized().video_options();
    let y = OlcRtcCommand::new("olcrtc", l).yaml();
    let block = format!(
        "video:\n  codec: {}\n  width: {}\n  height: {}\n  fps: {}\n  qr_size: {}\n  qr_recovery: {}\n  tile_module: {}\n  tile_rs: {}\ndata: 'data'\n",
        v.codec, v.width, v.height, v.fps, v.qr_size, v.qr_recovery, v.tile_module, v.tile_rs
    );
    assert!(y.ends_with(&block), "{y}");
    assert!(y.starts_with(&head("telemost", "videochannel")));
}
