use std::net::IpAddr;

use yptun_core::desktop_net::*;

fn nic(name: &str, display: &str, index: i32, up: bool, addrs: &[&str]) -> Nic {
    Nic {
        name: name.to_string(),
        display_name: display.to_string(),
        index,
        is_up: up,
        is_loopback: false,
        is_virtual: false,
        addresses: addrs.iter().map(|a| a.parse::<IpAddr>().unwrap()).collect(),
    }
}

#[test]
fn tunnel_addresses() {
    assert!(is_tunnel_address("10.0.88.88"));
    assert!(is_tunnel_address("172.19.0.1"));
    assert!(!is_tunnel_address("192.168.1.5"));
}

#[test]
fn linux_counters() {
    let dir = std::env::temp_dir().join(format!("yptun-net-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("rx_bytes"), "12345\n").unwrap();
    assert_eq!(read_counters_from(&dir), None);
    std::fs::write(dir.join("tx_bytes"), "678\n").unwrap();
    assert_eq!(read_counters_from(&dir), Some(Counters { rx_bytes: 12345, tx_bytes: 678 }));
    std::fs::write(dir.join("tx_bytes"), "garbage").unwrap();
    assert_eq!(read_counters_from(&dir), None);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn uplink_fingerprint() {
    let mut lo = nic("lo", "Loopback", 1, true, &["127.0.0.1"]);
    lo.is_loopback = true;
    let nics = vec![
        nic("wlan0", "Intel(R) Wi-Fi", 3, true, &["10.0.0.7"]),
        nic("eth0", "Realtek PCIe", 2, true, &["192.168.1.5", "fe80::1", "169.254.1.1"]),
        nic("YPtun", "YPtun", 9, true, &["10.0.88.88"]),
        nic("docker0", "docker0", 4, true, &["172.17.0.1"]),
        nic("eth1", "Realtek PCIe", 5, false, &["192.168.2.5"]),
        nic("Ethernet 2", "Hyper-V Virtual Ethernet Adapter", 6, true, &["172.20.0.1"]),
        nic("enp3s0", "Realtek PCIe", 7, true, &["169.254.9.9"]),
        lo,
    ];
    assert_eq!(fingerprint(&nics), "eth0=192.168.1.5|wlan0=10.0.0.7");
    assert_eq!(fingerprint(&[]), "");
}

#[test]
fn physical_adapter() {
    assert_eq!(physical_index(&nic("YPtun", "Wintun", 9, true, &[])), 0);
    assert_eq!(physical_index(&nic("olcbox0", "", 9, true, &[])), 0);
    assert_eq!(physical_index(&nic("eth0", "Realtek", 7, true, &[])), 7);
    assert_eq!(physical_index(&nic("eth0", "Realtek", 0, true, &[])), 0);
    assert!(is_virtual_name("vEthernet (WSL)", ""));
    assert!(!is_virtual_name("eth0", "Realtek PCIe"));
}
