use std::collections::HashSet;

use yptun_core::conflicting_vpn::*;

#[test]
fn product_lookup() {
    assert_eq!(product_for("happ.exe"), Some("Happ"));
    assert_eq!(product_for("HAPP.EXE"), Some("Happ"));
    assert_eq!(product_for("happ"), Some("Happ"));
    assert_eq!(product_for("nekobox_core"), Some("Nekoray"));
    assert_eq!(product_for("Cloudflare WARP.exe"), Some("Cloudflare WARP"));
    assert_eq!(product_for("tor.exe"), None);
    assert_eq!(product_for("explorer.exe"), None);
}

#[test]
fn tasklist_parsing() {
    let out = "\"System Idle Process\",\"0\",\"Services\",\"0\",\"8 K\"\r\n\"Hiddify.exe\",\"4242\",\"Console\",\"1\",\"120 000 K\"\r\n\r\nINFO: garbage\r\n";
    assert_eq!(
        parse_tasklist_csv(out),
        vec![(0, "system idle process".to_string()), (4242, "hiddify.exe".to_string())]
    );
}

#[test]
fn command_names() {
    assert_eq!(exe_name_from_command("C:\\Program Files\\Happ\\Happ.exe"), "happ.exe");
    assert_eq!(exe_name_from_command("/usr/bin/sing-box"), "sing-box");
    assert_eq!(exe_name_from_command("xray"), "xray");
}

#[test]
fn detection_groups_and_skips_ours() {
    let procs = vec![
        (1, "v2rayn.exe".to_string()),
        (2, "xray.exe".to_string()),
        (3, "v2rayn-core.exe".to_string()),
        (4, "olcrtc.exe".to_string()),
        (5, "sing-box".to_string()),
    ];
    let ours: HashSet<i64> = [2].into_iter().collect();
    assert_eq!(
        detect(&procs, &ours),
        vec![
            RunningVpnApp { display_name: "v2rayN".to_string(), pids: vec![1, 3] },
            RunningVpnApp { display_name: "sing-box".to_string(), pids: vec![5] },
        ]
    );
    assert!(detect(&[], &ours).is_empty());
}
