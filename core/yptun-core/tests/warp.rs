//! Ported from WarpHelperTest.

use yptun_core::warp::*;

#[test]
fn parse_warp_ini_to_profile() {
    let ini = "[Interface]\nPrivateKey = aaaaaa=\nAddress = 172.16.0.2/32, 2606:4700:110:8735:e228:a54:4c32:ae20/128\nDNS = 1.1.1.1\nMTU = 1280\nJc = 4\nI1 = <b 0xce00...>\n\n[Peer]\nPublicKey = bbbbbb=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = engage.cloudflareclient.com:2408\nPersistentKeepalive = 25";
    let p = parse_warp_ini(ini, "WARP").unwrap();
    assert_eq!((p.tag.as_str(), p.kind.as_str(), p.server.as_str(), p.server_port), ("WARP", "wireguard", "162.159.192.1", 2408));
    let j: serde_json::Value = serde_json::from_str(p.raw_outbound.as_deref().unwrap()).unwrap();
    assert_eq!((j["server"].as_str(), j["private_key"].as_str(), j["peer_public_key"].as_str()), (Some("162.159.192.1"), Some("aaaaaa="), Some("bbbbbb=")));
    assert_eq!(j["local_address"].as_array().unwrap().len(), 2);
    assert_eq!((j["mtu"].as_i64(), j["persistent_keepalive"].as_i64()), (Some(1280), Some(25)));
    assert!(parse_warp_ini("[Interface]\nPrivateKey = a=", "W").is_none());
}

#[test]
fn engine_ini_drops_reserved_pins_endpoint_and_only_awg_keeps_obfuscation() {
    let ini = "[Interface]\nPrivateKey = a=\nReserved = 1, 2, 3\nJc = 4\nS1 = 0\nI1 = <b 0xce>\n[Peer]\nPublicKey = b=\nEndpoint = engage.cloudflareclient.com:2408";
    let awg = engine_ini(ini, true, true);
    assert!(!awg.contains("Reserved"));
    assert!(awg.contains("I1 = <b 0xce>") && awg.contains("Jc = 4") && awg.contains("Endpoint = 162.159.192.1:2408"));
    let server = engine_ini(ini, true, false);
    assert!(!server.contains("I1") && server.contains("Jc = 4"));
    let wg = engine_ini(ini, false, true);
    assert!(!wg.contains("Jc") && !wg.contains("S1") && !wg.contains("Reserved"));
    assert_eq!(pinned_host("1.2.3.4"), "1.2.3.4");
    assert_eq!(endpoint_of("[Peer]\nEndpoint = [2606:4700::1]:500"), ("2606:4700::1".to_string(), 500));
    assert_eq!(endpoint_of("[Peer]"), ("engage.cloudflareclient.com".to_string(), 2408));
}
