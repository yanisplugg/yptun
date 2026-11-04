//! Ported from XrayDnsFormatTest, XrayFakeDnsSpecTest, PrepareRawPanelRoutingTest,
//! XhttpExtraSanitizeTest, PrepareRawXhttpTest and the Xray half of ThreeXuiLinkTest.

use serde_json::{json, Value};
use yptun_core::model::*;
use yptun_core::routing::*;
use yptun_core::transports::FakeDnsSpec;
use yptun_core::xray::config::*;

fn vless() -> ProxyProfile {
    ProxyProfile {
        kind: TYPE_VLESS.into(), server: "vbn.azz.su".into(), server_port: 443, uuid: "11111111-1111-1111-1111-111111111111".into(),
        network: NETWORK_TCP.into(), security: SECURITY_TLS.into(), sni: "vbn.azz.su".into(), ..Default::default()
    }
}
fn parse(s: &str) -> Value {
    serde_json::from_str(s).unwrap()
}
fn built(p: &ProxyProfile, o: XrayOptions) -> Value {
    parse(&build(p, &o))
}
fn list<'a>(v: &'a Value, k: &str) -> Vec<&'a Value> {
    v[k].as_array().map(|a| a.iter().collect()).unwrap_or_default()
}
fn strs(v: &Value) -> Vec<String> {
    v.as_array().unwrap().iter().map(|x| x.as_str().unwrap().to_string()).collect()
}
fn by_tag<'a>(v: &'a Value, k: &str, tag: &str) -> &'a Value {
    list(v, k).into_iter().find(|o| o["tag"] == tag).unwrap_or_else(|| panic!("no {tag} in {k}"))
}
fn traffic(remote: &str, remote2: &str, direct: &str) -> TrafficSettings {
    TrafficSettings { remote_dns: remote.into(), remote_dns2: remote2.into(), direct_dns: direct.into(), ..Default::default() }
}

// ---------------- DNS ----------------

#[test]
fn doh_kept_and_dot_becomes_doh() {
    let root = built(&vless(), XrayOptions { traffic: traffic("https://dns.google/dns-query", "tls://one.one.one.one:853", "tls://dns.quad9.net"), ..Default::default() });
    assert_eq!(strs(&root["dns"]["servers"]), ["https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query", "https://9.9.9.9/dns-query"]);
}

#[test]
fn remote_resolvers_ride_the_proxy() {
    let root = built(&vless(), XrayOptions { traffic: traffic("https://cloudflare-dns.com/dns-query", "https://dns.example.org/dns-query", "77.88.8.8"), ..Default::default() });
    let rules = list(&root["routing"], "rules");
    let d = rules.iter().find(|r| r.get("domain").is_some()).unwrap();
    assert_eq!((strs(&d["domain"]), d["outboundTag"].as_str()), (vec!["full:dns.example.org".to_string()], Some("proxy")));
    let i = rules.iter().find(|r| r.get("ip").is_some()).unwrap();
    assert_eq!(strs(&i["ip"]), ["1.1.1.1"]);

    let (d, i) = remote_dns_endpoints(&traffic("1.1.1.1", "https+local://dns.google/dns-query", "77.88.8.8"));
    assert!(d.is_empty());
    assert_eq!(i, ["1.1.1.1"]);
    let (d, i) = remote_dns_endpoints(&traffic("192.168.1.1", "dns.example.org", "77.88.8.8"));
    assert_eq!(d, ["full:dns.example.org"]);
    assert!(i.is_empty());
}

#[test]
fn known_doh_hostnames_become_ips_and_remote_ips_become_doh_or_tcp() {
    let first = |r: &str| strs(&built(&vless(), XrayOptions { traffic: traffic(r, "", "77.88.8.8"), ..Default::default() })["dns"]["servers"])[0].clone();
    assert_eq!(first("https://cloudflare-dns.com/dns-query"), "https://1.1.1.1/dns-query");
    assert_eq!(first("tls://cloudflare-dns.com"), "https://1.1.1.1/dns-query");
    assert_eq!(first("https://DNS.GOOGLE"), "https://8.8.8.8/dns-query");
    assert_eq!(first("https://cloudflare-dns.com/family"), "https://cloudflare-dns.com/family");
    assert_eq!(first("https://dns.example.org/dns-query"), "https://dns.example.org/dns-query");
    assert_eq!(first("45.90.28.0"), "tcp://45.90.28.0");
    let s = strs(&built(&vless(), XrayOptions { traffic: traffic("8.8.8.8", "1.1.1.1", "223.5.5.5"), ..Default::default() })["dns"]["servers"]);
    assert_eq!(s, ["https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query", "223.5.5.5"]);
}

// ---------------- FakeDNS ----------------

#[test]
fn fake_dns_spec_turns_on_the_plumbing() {
    let spec = FakeDnsSpec { inet4_range: "198.19.0.0/16".into(), inet6_range: "fc00::/18".into(), block_regex: vec!["^ads[.]example$".into()], ..Default::default() };
    let root = built(&vless(), XrayOptions { fake_dns_spec: Some(spec), ..Default::default() });
    assert_eq!(root["fakedns"][0]["ipPool"], "198.19.0.0/16");
    assert_eq!(root["dns"]["servers"][0], "fakedns");
    assert_eq!(root["dns"]["hosts"]["regexp:^ads[.]example$"], "0.0.0.0");
    assert!(strs(&root["inbounds"][0]["sniffing"]["destOverride"]).contains(&"fakedns".to_string()));
    by_tag(&root, "outbounds", "dns-out");
    let rules = list(&root["routing"], "rules");
    let hijack: Vec<&&Value> = rules.iter().filter(|r| r["outboundTag"] == "dns-out").collect();
    assert!(!hijack.is_empty() && hijack.iter().all(|r| r["inboundTag"][0] == "socks-in"));
    assert!(hijack.iter().any(|r| r["port"] == 53 && r.get("network").is_none()));
    assert!(rules.iter().any(|r| r["outboundTag"] == "proxy" && r["ip"][0] == "198.19.0.0/16"));
    assert!(rules.iter().any(|r| r["outboundTag"] == "block" && r["ip"][0] == "0.0.0.0"));

    let plain = built(&vless(), XrayOptions::default());
    assert!(plain.get("fakedns").is_none());
    assert!(list(&plain["routing"], "rules").iter().all(|r| r["outboundTag"] != "dns-out"));
}

// ---------------- prepare_raw: panel routing ----------------

const PANEL: &str = r#"{
  "dns": {
    "hosts": { "regexp:(^|\\.)gov\\.ru$": "198.18.0.1", "regexp:(^|\\.)yandex\\.[a-z0-9.-]+$": "198.18.0.85", "plain.example": "198.18.0.200", "elsewhere.example": "203.0.113.9" },
    "servers": [ { "port": 53, "address": "1.1.1.1", "domains": ["geosite:telegram", "domain:t.me"], "skipFallback": true }, "fakedns", "1.1.1.1" ],
    "queryStrategy": "UseIP"
  },
  "fakedns": [ { "ipPool": "198.18.0.0/15", "poolSize": 65535 } ],
  "routing": {
    "rules": [
      { "ip": ["0.0.0.0"], "type": "field", "outboundTag": "direct" },
      { "ip": ["198.18.0.0/15"], "type": "field", "outboundTag": "direct" },
      { "port": 53, "type": "field", "network": "udp", "outboundTag": "dns-out" },
      { "port": 853, "type": "field", "network": "tcp", "outboundTag": "dns-out" },
      { "type": "field", "protocol": ["bittorrent"], "outboundTag": "block" },
      { "ip": ["geoip:private"], "type": "field", "outboundTag": "direct" },
      { "type": "field", "domain": ["geosite:private"], "outboundTag": "direct" }
    ],
    "domainStrategy": "IPOnDemand"
  },
  "inbounds": [ { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks", "settings": { "udp": true, "auth": "noauth" },
    "sniffing": { "enabled": true, "destOverride": ["quic", "http", "tls", "fakedns"], "metadataOnly": false } } ],
  "outbounds": [
    { "tag": "proxy", "protocol": "vless", "settings": { "vnext": [ { "address": "spb.quofortpost.com", "port": 443,
      "users": [ { "id": "732c8764-e31d-49ab-852b-54cb0f7cc3de", "encryption": "none", "flow": "xtls-rprx-vision" } ] } ] },
      "streamSettings": { "network": "tcp", "security": "tls", "tlsSettings": { "serverName": "spb.quofortpost.com" } } },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" },
    { "tag": "dns-out", "protocol": "dns", "settings": { "nonIPQuery": "skip" } }
  ],
  "remarks": "🇷🇺СПБ (без РКН)"
}"#;

#[test]
fn routing_hosts_become_domain_rules_before_their_ip_rule() {
    let out = parse(&prepare_raw(PANEL, &RawOptions { force_ipv4: true, ..RawOptions::new(10808) }));
    let rules = list(&out["routing"], "rules");
    assert_eq!(rules.len(), 8);
    assert_eq!(rules[1]["outboundTag"], "direct");
    assert_eq!(strs(&rules[1]["domain"]), [r"regexp:(^|\.)gov\.ru$", r"regexp:(^|\.)yandex\.[a-z0-9.-]+$", "full:plain.example"]);
    assert_eq!(rules[2]["ip"], json!(["198.18.0.0/15"]));
    let tags: Vec<&str> = rules.iter().map(|r| r["outboundTag"].as_str().unwrap()).collect();
    assert_eq!(tags, ["direct", "direct", "direct", "dns-out", "dns-out", "block", "direct", "direct"]);
    assert_eq!(out["routing"]["domainStrategy"], "IPOnDemand");
    let hosts: Vec<&String> = out["dns"]["hosts"].as_object().unwrap().keys().collect();
    assert_eq!(hosts, ["elsewhere.example"]);
    assert_eq!(out["dns"]["servers"].as_array().unwrap().len(), 3);
    assert!(out["dns"]["servers"][0]["domains"].to_string().contains("geosite:telegram"));
    assert_eq!(out["fakedns"][0]["ipPool"], "198.18.0.0/15");
    assert!(out["inbounds"][0]["sniffing"]["destOverride"].to_string().contains("fakedns"));
    assert_eq!(out["inbounds"][0]["tag"], "socks-in");
    assert_eq!(by_tag(&out, "outbounds", "direct")["settings"]["domainStrategy"], "ForceIPv4");
    assert_eq!(out["dns"]["queryStrategy"], "UseIPv4");
    assert_eq!(out["remarks"], "🇷🇺СПБ (без РКН)");

    let mut bare = parse(PANEL);
    bare.as_object_mut().unwrap().remove("routing");
    let out = parse(&prepare_raw(&bare.to_string(), &RawOptions::new(10808)));
    assert_eq!(out["dns"]["hosts"].as_object().unwrap().len(), 4);
    assert!(out.get("routing").is_none());
}

#[test]
fn unparseable_raw_config_is_returned_unchanged() {
    assert_eq!(prepare_raw("not json", &RawOptions::new(1)), "not json");
}

// ---------------- xhttp extra sanitizer ----------------

fn xhttp(extra: &str) -> ProxyProfile {
    ProxyProfile {
        tag: "n".into(), kind: TYPE_VLESS.into(), server: "1.2.3.4".into(), server_port: 443, uuid: "u".into(),
        network: NETWORK_XHTTP.into(), security: SECURITY_TLS.into(), sni: "example.com".into(), xhttp_mode: "auto".into(), xhttp_extra: extra.into(), ..Default::default()
    }
}
fn built_extra(p: &ProxyProfile) -> Value {
    let root = built(p, XrayOptions::default());
    list(&root, "outbounds").into_iter().find(|o| o["protocol"] == "vless").unwrap()["streamSettings"]["xhttpSettings"]["extra"].clone()
}

#[test]
fn xhttp_extra_coerces_floats_and_drops_garbage() {
    let e = built_extra(&xhttp(r#"{"scMaxEachPostBytes":1000000.0,"xPaddingObfsMode":true,"xPaddingBytes":"oops","noGRPCHeader":false,"xmux":{"maxConcurrency":16.0,"maxConnections":0,"cMaxReuseTimes":{},"hMaxRequestTimes":"600-900"}}"#));
    assert_eq!(e["scMaxEachPostBytes"], 1000000);
    assert!(e.get("xPaddingBytes").is_none());
    assert_eq!(e["xPaddingObfsMode"], true);
    assert_eq!((e["xmux"]["maxConcurrency"].clone(), e["xmux"]["maxConnections"].clone()), (json!(16), json!(0)));
    assert!(e["xmux"].get("cMaxReuseTimes").is_none());
    assert_eq!(e["xmux"]["hMaxRequestTimes"], "600-900");

    let good = r#"{"xPaddingBytes":"100-1000","scMaxEachPostBytes":1000000,"xmux":{"maxConcurrency":"16-32","maxConnections":0}}"#;
    assert_eq!(built_extra(&xhttp(good)), parse(good));
}

#[test]
fn cdn_config_with_float_plain_ints_still_builds() {
    let bad = r#"{"path":"/static/main/video/segment.ts/m37f08c983037","host":"","mode":"packet-up","xPaddingBytes":"100-1000","xPaddingObfsMode":true,"xPaddingKey":"hash","xPaddingHeader":"X-Client-Version","xPaddingPlacement":"queryInHeader","xPaddingMethod":"tokenish","sessionIDPlacement":"header","sessionIDKey":"X-Upload-Token","sessionIDTable":"","sessionIDLength":"","seqPlacement":"query","seqKey":"chunk_id","noSSEHeader":false,"scMaxBufferedPosts":30.0,"scStreamUpServerSecs":"20-80","serverMaxHeaderBytes":0.0,"uplinkHTTPMethod":"GET","headers":{},"uplinkChunkSize":0,"noGRPCHeader":false,"xmux":{"maxConcurrency":"16-32","maxConnections":0,"cMaxReuseTimes":1000,"hMaxRequestTimes":"600-900","hMaxReusableSecs":"100","hKeepAlivePeriod":20000.0}}"#;
    let e = built_extra(&xhttp(bad));
    assert_eq!((e["xmux"]["hKeepAlivePeriod"].clone(), e["scMaxBufferedPosts"].clone(), e["serverMaxHeaderBytes"].clone()), (json!(20000), json!(30), json!(0)));
    assert_eq!((e["mode"].as_str(), e["xPaddingBytes"].as_str(), e["sessionIDKey"].as_str(), e["sessionIDLength"].as_str()), (Some("packet-up"), Some("100-1000"), Some("X-Upload-Token"), Some("")));
    assert_eq!((e["xmux"]["maxConcurrency"].clone(), e["xmux"]["cMaxReuseTimes"].clone()), (json!("16-32"), json!(1000)));
    assert!(!e.to_string().contains(".0"));
}

#[test]
fn xhttp_drops_unsalvageable_and_coerces_bools_strings() {
    let e = built_extra(&xhttp(r#"{"scMaxBufferedPosts":true,"serverMaxHeaderBytes":"none","xmux":{"hKeepAlivePeriod":{}}}"#));
    assert!(e.get("scMaxBufferedPosts").is_none() && e.get("serverMaxHeaderBytes").is_none() && e["xmux"].get("hKeepAlivePeriod").is_none());
    assert_eq!(built_extra(&xhttp(r#"{"scMaxBufferedPosts":"30"}"#))["scMaxBufferedPosts"], 30);

    let e = built_extra(&xhttp(r#"{"xPaddingObfsMode":"true","noSSEHeader":0,"noGRPCHeader":"nope","seqKey":1,"xPaddingMethod":["a"],"headers":{"X-N":5,"X-Ok":"v","X-Bad":{}}}"#));
    assert_eq!((e["xPaddingObfsMode"].clone(), e["noSSEHeader"].clone(), e["seqKey"].clone()), (json!(true), json!(false), json!("1")));
    assert!(e.get("noGRPCHeader").is_none() && e.get("xPaddingMethod").is_none());
    assert_eq!(e["headers"], json!({ "X-N": "5", "X-Ok": "v" }));
}

#[test]
fn raw_config_top_level_xhttp_settings_sanitized() {
    let raw = r#"{ "outbounds": [
        { "protocol": "vless", "tag": "proxy", "settings": { "vnext": [ { "address": "1.2.3.4", "port": 443, "users": [ { "encryption": "none", "id": "u" } ] } ] },
          "streamSettings": { "network": "xhttp", "security": "tls",
            "xhttpSettings": { "mode": "packet-up", "path": "/seg.ts", "host": "cdn.example.com", "scMaxBufferedPosts": 30.0, "xmux": { "maxConcurrency": "16-32", "hKeepAlivePeriod": 20000.0 } } } },
        { "protocol": "freedom", "tag": "direct" } ] }"#;
    let out = parse(&prepare_raw(raw, &RawOptions::new(10808)));
    let stream = &by_tag(&out, "outbounds", "proxy")["streamSettings"];
    let x = &stream["xhttpSettings"];
    assert_eq!((x["xmux"]["hKeepAlivePeriod"].clone(), x["scMaxBufferedPosts"].clone()), (json!(20000), json!(30)));
    assert_eq!((x["mode"].as_str(), x["path"].as_str(), x["host"].as_str()), (Some("packet-up"), Some("/seg.ts"), Some("cdn.example.com")));
    assert_eq!((stream["security"].as_str(), stream["network"].as_str()), (Some("tls"), Some("xhttp")));
}

// ---------------- prepare_raw: xhttp + cascade ----------------

const XHTTP: &str = r#"{
  "dns": { "hosts": { "regexp:(^|\\.)2ip\\.ru+$": "198.18.0.186" }, "queryStrategy": "UseIP",
    "servers": [ { "address": "fakedns", "domains": ["regexp:(^|\\.)vk\\.[a-z0-9.-]+$"], "queryStrategy": "UseIP", "skipFallback": true },
      { "address": "1.1.1.1", "domains": ["geosite:telegram", "geosite:discord", "domain:t.me"], "port": 53, "skipFallback": true }, "fakedns", "1.1.1.1" ] },
  "fakedns": [ { "ipPool": "198.18.0.0/15", "poolSize": 65535 } ],
  "inbounds": [ { "listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": { "auth": "noauth", "udp": true },
      "sniffing": { "destOverride": ["quic","http","tls","fakedns"], "enabled": true, "metadataOnly": false }, "tag": "socks" } ],
  "outbounds": [
    { "protocol": "vless", "settings": { "vnext": [ { "address": "poetica.example.com", "port": 443, "users": [ { "encryption": "none", "id": "uuid" } ] } ] },
      "streamSettings": { "network": "xhttp", "security": "reality", "realitySettings": { "publicKey": "PK", "serverName": "5post-gate.x5.ru", "shortId": "9c2e" },
        "xhttpSettings": { "mode": "auto", "host": "", "extra": { "host": "poetica.example.com", "seqKey": "X-Request-Seq" } } }, "tag": "proxy" },
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" } ],
  "routing": { "domainStrategy": "IPOnDemand", "rules": [
      { "ip": ["0.0.0.0"], "outboundTag": "direct", "type": "field" },
      { "ip": ["198.18.0.0/15"], "outboundTag": "direct", "type": "field" },
      { "domain": ["regexp:(^|\\.)vk\\.[a-z0-9.-]+$"], "outboundTag": "direct", "type": "field" },
      { "network": "udp", "outboundTag": "dns-out", "port": 53, "type": "field" },
      { "ip": ["geoip:private"], "outboundTag": "direct", "type": "field" },
      { "domain": ["geosite:private"], "outboundTag": "direct", "type": "field" } ] }
}"#;

#[test]
fn xhttp_config_prepares_without_geo_selectors() {
    let out = prepare_raw(XHTTP, &RawOptions { strip_geo_selectors: true, force_ipv4: true, ..RawOptions::new(10808) });
    assert!(!out.contains("geosite:") && !out.contains("geoip:"));
    let root = parse(&out);
    let proxy = by_tag(&root, "outbounds", "proxy");
    assert_eq!((proxy["protocol"].as_str(), proxy["streamSettings"]["network"].as_str()), (Some("vless"), Some("xhttp")));
    assert_eq!(proxy["streamSettings"]["xhttpSettings"]["host"], "poetica.example.com", "extra.host lifted to the top level");
    assert!(proxy["streamSettings"]["xhttpSettings"]["extra"].get("seqKey").is_some());
    assert_eq!(by_tag(&root, "outbounds", "direct")["settings"]["domainStrategy"], "ForceIPv4");
    let rules = list(&root["routing"], "rules");
    assert!(rules.iter().any(|r| r["domain"][0].as_str().is_some_and(|d| d.starts_with("regexp:"))));
    assert_eq!(root["dns"]["queryStrategy"], "UseIPv4");
    assert!(root.get("fakedns").is_some());
    assert!(list(&root, "inbounds").iter().any(|i| i["protocol"] == "socks" && i["port"] == 10808));
    // 2ip.ru host is steered by `ip: 198.18.0.0/15 → direct`: lifted into a domain rule.
    assert!(rules.iter().any(|r| r["domain"][0] == r"regexp:(^|\.)2ip\.ru+$"));
}

#[test]
fn xhttp_config_keeps_its_own_geo_routing_when_not_stripped() {
    let out = prepare_raw(XHTTP, &RawOptions::new(10808));
    assert!(out.contains("geosite:") && out.contains("geoip:"));
    let root = parse(&out);
    let rules = list(&root["routing"], "rules");
    assert!(rules.iter().any(|r| r["domain"][0] == "geosite:private"));
    assert!(rules.iter().any(|r| r["ip"][0] == "geoip:private"));
}

fn second(network: &str, security: &str, flow: &str) -> ProxyProfile {
    ProxyProfile {
        tag: "exit".into(), kind: TYPE_VLESS.into(), server: "exit.example.com".into(), server_port: 443, uuid: "exit-uuid".into(),
        network: network.into(), security: security.into(), sni: "exit.example.com".into(), flow: flow.into(), ..Default::default()
    }
}

#[test]
fn cascade_second_proxy_chains_through_xhttp_main() {
    for s in [second(NETWORK_TCP, SECURITY_TLS, ""), second(NETWORK_XHTTP, SECURITY_TLS, ""), second(NETWORK_TCP, SECURITY_REALITY, "xtls-rprx-vision")] {
        let root = parse(&prepare_raw(XHTTP, &RawOptions { strip_geo_selectors: true, second_profile: Some(s.clone()), ..RawOptions::new(10808) }));
        let outs = list(&root, "outbounds");
        assert_eq!(outs[0]["tag"], "cascade-exit");
        let exit = outs[0];
        assert!(exit.get("proxySettings").is_none());
        assert_eq!(exit["streamSettings"]["sockopt"]["dialerProxy"], "cascade-loop-out");
        if !s.flow.is_empty() {
            assert_eq!(exit["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
        }
        let loop_out = by_tag(&root, "outbounds", "cascade-loop-out");
        assert_eq!((loop_out["settings"]["servers"][0]["address"].as_str(), loop_out["settings"]["servers"][0]["port"].as_i64()), (Some("127.0.0.1"), Some(10809)));
        assert_eq!(by_tag(&root, "inbounds", "cascade-loop-in")["port"], 10809);
        let rules = list(&root["routing"], "rules");
        assert_eq!((rules[0]["inboundTag"][0].as_str(), rules[0]["outboundTag"].as_str()), (Some("cascade-loop-in"), Some("proxy")));
        // The main's traffic now exits via the cascade.
        assert!(rules.iter().all(|r| r["outboundTag"] != "proxy" || r["inboundTag"][0] == "cascade-loop-in"));
        let main = by_tag(&root, "outbounds", "proxy");
        assert_eq!(main["streamSettings"]["xhttpSettings"]["xmux"]["maxConnections"], "4-8");
    }
    let awg = ProxyProfile { tag: "awg".into(), kind: TYPE_AMNEZIAWG.into(), server: "wg.example.com".into(), server_port: 51820, awg_config: "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y\nEndpoint = wg.example.com:51820".into(), ..Default::default() };
    let root = parse(&prepare_raw(XHTTP, &RawOptions { strip_geo_selectors: true, second_profile: Some(awg), ..RawOptions::new(10808) }));
    assert!(list(&root, "outbounds").iter().all(|o| o["tag"] != "cascade-exit"));
}

#[test]
fn raw_proxy_ping_config() {
    let out = build_raw_proxy_ping_config(XHTTP, 34567, "127.0.0.1").unwrap();
    assert!(!out.contains("geosite:") && !out.contains("geoip:"));
    let root = parse(&out);
    let outs = list(&root, "outbounds");
    assert_eq!(outs.len(), 1);
    assert_eq!((outs[0]["tag"].as_str(), outs[0]["protocol"].as_str()), (Some("proxy"), Some("vless")));
    let st = &outs[0]["streamSettings"];
    assert_eq!((st["network"].as_str(), st["security"].as_str()), (Some("xhttp"), Some("reality")));
    assert!(st.get("realitySettings").is_some());
    assert_eq!(st["xhttpSettings"]["host"], "poetica.example.com");
    assert!(root.get("routing").is_none());
    assert_eq!(root["inbounds"][0]["port"], 34567);
    assert!(build_raw_proxy_ping_config(r#"{ "outbounds": [ { "protocol": "freedom", "tag": "direct" } ] }"#, 1, "127.0.0.1").is_none());
}

// ---------------- build: cascade ----------------

fn xhttp_main() -> ProxyProfile {
    ProxyProfile {
        tag: "main".into(), kind: TYPE_VLESS.into(), server: "fin.example.com".into(), server_port: 8443, uuid: "uuid-main".into(),
        network: NETWORK_XHTTP.into(), security: SECURITY_REALITY.into(), sni: "fin.example.com".into(), ..Default::default()
    }
}

#[test]
fn build_cascade_over_xhttp_main_uses_socks_loopback() {
    let s = ProxyProfile { server: "nbg.example.com".into(), server_port: 9444, ..second(NETWORK_TCP, SECURITY_REALITY, "xtls-rprx-vision") };
    let root = built(&xhttp_main(), XrayOptions { second_profile: Some(s), traffic: TrafficSettings { mux_enabled: true, ..Default::default() }, ..Default::default() });
    let exit = by_tag(&root, "outbounds", "proxy");
    assert_eq!(list(&root, "outbounds")[0]["tag"], "proxy");
    assert_eq!(exit["streamSettings"]["sockopt"]["dialerProxy"], "cascade-loop-out");
    assert!(exit.get("proxySettings").is_none());
    assert_eq!(exit["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
    assert_eq!(by_tag(&root, "outbounds", "cascade-loop-out")["settings"]["servers"][0]["port"], 10809);
    let base = by_tag(&root, "outbounds", "proxy-base");
    assert_eq!(base["streamSettings"]["xhttpSettings"]["xmux"]["maxConnections"], "4-8");
    assert!(base.get("mux").is_none(), "Mux.Cool kills the xhttp cascade");
    assert_eq!(by_tag(&root, "inbounds", "cascade-loop-in")["port"], 10809);
    let first = &root["routing"]["rules"][0];
    assert_eq!((first["inboundTag"][0].as_str(), first["outboundTag"].as_str()), (Some("cascade-loop-in"), Some("proxy-base")));

    assert!(!build(&xhttp_main(), &XrayOptions::default()).contains("cascade-loop"));
}

#[test]
fn build_chain_drops_vision_flow_unless_kept() {
    let p = ProxyProfile { flow: "xtls-rprx-vision".into(), ..vless() };
    let chained = built(&p, XrayOptions { olcrtc_chain_port: Some(10811), ..Default::default() });
    let proxy = by_tag(&chained, "outbounds", "proxy");
    assert!(proxy["settings"]["vnext"][0]["users"][0].get("flow").is_none());
    assert_eq!(proxy["proxySettings"]["tag"], "olcrtc-out");
    let kept = built(&p, XrayOptions { olcrtc_chain_port: Some(10811), keep_flow_on_chain: true, chain_via_dialer_proxy: true, ..Default::default() });
    let proxy = by_tag(&kept, "outbounds", "proxy");
    assert_eq!(proxy["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
    assert_eq!(proxy["streamSettings"]["sockopt"]["dialerProxy"], "olcrtc-out");
    assert!(proxy.get("proxySettings").is_none());
}

#[test]
fn build_family_lan_quic_and_profile() {
    let root = built(&vless(), XrayOptions {
        routing_profile: Some(RoutingProfile { direct_sites: vec!["domain:ru".into()], global_proxy: false, ..Default::default() }),
        traffic: TrafficSettings { block_ru_domains: true, ..Default::default() },
        ..Default::default()
    });
    assert_eq!(root["routing"]["domainStrategy"], "IPIfNonMatch");
    let rules = list(&root["routing"], "rules");
    let tags: Vec<&str> = rules.iter().map(|r| r["outboundTag"].as_str().unwrap()).collect();
    // remote DNS (8.8.8.8 → proxy), LAN, QUIC, ::/0, 0.0.0.0, profile direct, catch-all direct.
    assert_eq!(tags, ["proxy", "direct", "block", "block", "block", "direct", "direct"]);
    assert!(root["dns"]["hosts"].as_object().unwrap().len() > 100);
    assert_eq!(by_tag(&root, "outbounds", "direct")["settings"]["domainStrategy"], "ForceIPv4");

    let udp = built(&vless(), XrayOptions { block_quic: false, force_family_resolve: false, bypass_lan: false, ..Default::default() });
    assert_eq!(udp["routing"]["domainStrategy"], "AsIs");
    assert_eq!(list(&udp["routing"], "rules").len(), 1, "only the remote-DNS pin remains");
}

#[test]
fn build_vkturn_base_and_direct_via_base() {
    let wg = ProxyProfile { kind: "wireguard".into(), raw_outbound: Some(r#"{"type":"wireguard","server":"127.0.0.1","server_port":9000,"local_address":["10.7.0.2/32"],"private_key":"K","peer_public_key":"P","mtu":1200}"#.into()), ..Default::default() };
    let root = built(&vless(), XrayOptions { wireguard_base: Some(wg), direct_via_base: true, block_quic: false, ..Default::default() });
    let base = by_tag(&root, "outbounds", "wireguard-base");
    assert_eq!((base["settings"]["peers"][0]["endpoint"].as_str(), base["settings"]["mtu"].as_i64(), base["settings"]["peers"][0]["keepAlive"].as_i64()), (Some("127.0.0.1:9000"), Some(1200), Some(25)));
    assert_eq!(base["settings"]["peers"][0]["allowedIPs"], json!(["0.0.0.0/0"]));
    assert_eq!(by_tag(&root, "outbounds", "proxy")["proxySettings"]["tag"], "wireguard-base");
    assert_eq!(by_tag(&root, "outbounds", "direct")["streamSettings"]["sockopt"]["dialerProxy"], "wireguard-base");
    assert!(list(&root["routing"], "rules").iter().all(|r| r["ip"][0] != "10.0.0.0/8"), "no LAN bypass on a base-detour exit");
}

#[test]
fn wireguard_balancer() {
    let wg = |port: i32| ProxyProfile { kind: "wireguard".into(), raw_outbound: Some(format!(r#"{{"type":"wireguard","server":"127.0.0.1","server_port":{port},"local_address":["10.7.0.2/32"],"private_key":"K","peer_public_key":"P"}}"#)), ..Default::default() };
    let root = parse(&build_wireguard_balancer(&[wg(9101), wg(9102)], 10808, "127.0.0.1", "", "", "warning", &TrafficSettings::default(), true));
    assert_eq!(list(&root, "outbounds")[1]["tag"], "wg-bal-1");
    assert_eq!(root["routing"]["balancers"][0]["strategy"]["settings"]["expected"], 2);
    assert_eq!(root["routing"]["balancers"][0]["fallbackTag"], "wg-bal-0");
    assert!(root.get("burstObservatory").is_some());
    let last = list(&root["routing"], "rules").last().unwrap().clone();
    assert_eq!(last["balancerTag"], "vk-bal");
    let one = parse(&build_wireguard_balancer(&[wg(9101)], 10808, "127.0.0.1", "", "", "warning", &TrafficSettings::default(), false));
    assert!(one.get("burstObservatory").is_none());
}

#[test]
fn three_xui_params_reach_the_outbound() {
    let p = ProxyProfile {
        security: SECURITY_REALITY.into(), reality_public_key: "PBK".into(), reality_short_id: "1".into(), reality_mldsa65_verify: "PQV".into(), reality_spider_x: "/abc".into(),
        vless_encryption: "mlkem768x25519plus.native.0rtt.KEY".into(), final_mask: r#"{"tcp":[{"type":"fragment"}]}"#.into(), ..vless()
    };
    let root = built(&p, XrayOptions::default());
    let out = by_tag(&root, "outbounds", "proxy");
    assert_eq!(out["settings"]["vnext"][0]["users"][0]["encryption"], "mlkem768x25519plus.native.0rtt.KEY");
    let reality = &out["streamSettings"]["realitySettings"];
    assert_eq!((reality["mldsa65Verify"].as_str(), reality["spiderX"].as_str(), reality["serverName"].as_str()), (Some("PQV"), Some("/abc"), Some("vbn.azz.su")));
    assert_eq!(out["streamSettings"]["finalmask"]["tcp"][0]["type"], "fragment");
    let plain = built(&vless(), XrayOptions::default());
    let o = by_tag(&plain, "outbounds", "proxy");
    assert_eq!(o["settings"]["vnext"][0]["users"][0]["encryption"], "none");
    assert!(o["streamSettings"].get("finalmask").is_none());
    let x = built(&ProxyProfile { network: NETWORK_XHTTP.into(), path: "/p".into(), ..vless() }, XrayOptions::default());
    assert_eq!(by_tag(&x, "outbounds", "proxy")["streamSettings"]["xhttpSettings"]["mode"], "auto");
}
