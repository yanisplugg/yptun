//! The TOML files the static `snolc` executable runs from (`packages = "builtin"`: every module is
//! linked in, see olcvpn-client/snolc/YPTUN.md). Relative paths resolve against the file that holds
//! them. Ported from `SnolcFiles.kt`; the output is byte-identical to the Kotlin generator.
//!
//! Limits are raised from upstream's 256 MiB "low-memory" template (2 sessions, 16 flows per client),
//! which would choke a phone browser: 128 flows per client, 4 clients (a phone easily holds 64+ at
//! once - every UDP/DNS flow and keep-alive connection takes one - and the old 64 reset the whole
//! tunnel when crossed). Yamux's window must be at least streams × 256 KiB, hence 129 × 262144;
//! `max_flows` must cover `max_sessions` × (streams - 1).

use crate::util::{is_blank, trim_indent};

const MODULE_VERSION: &str = "0.0.4";

/// One generated file: its path relative to the directory that holds `snolc.toml`, and its text.
pub type SnolcFile = (String, String);

fn toml_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn engine(role: &str, adapter: &str, state_dir: &str, debug: bool) -> String {
    let levels = if debug {
        r#""warning", "error", "debug""#
    } else {
        r#""warning", "error""#
    };
    trim_indent(&format!(
        r#"
        wire_version = 1

        [paths]
        packages = "builtin"
        state = "{state_dir}"

        [engine]
        max_sessions = 4
        max_flows = 512
        max_pending_sessions = 4
        max_pending_opens = 64
        max_managed_bytes = 268435456
        max_commands = 64
        max_events = 256
        max_io_chunk = 16384
        max_ingress_packets_per_tick = 32
        connect_timeout_ms = 15000
        handshake_timeout_ms = 15000
        shutdown_timeout_ms = 5000

        [stack]
        ipv4 = true
        ipv6 = true
        mtu = 1280
        tcp_socket_rx_bytes = 65536
        tcp_socket_tx_bytes = 65536
        udp_socket_rx_bytes = 131072
        udp_socket_tx_bytes = 131072
        udp_metadata_slots = 8
        packet_queue_bytes = 262144
        max_udp_payload_bytes = 65507
        reassembly_slots = 4
        reassembly_timeout_ms = 15000

        [yamux]
        max_streams_per_session = 129
        receive_window_bytes = 33816576
        split_send_size = 16384
        read_after_close = true

        [logging]
        mode = "file"
        source = "toml"
        levels = [{levels}]
        file = "{state_dir}/snolc.log"
        limit = "8mb"
        queue_bytes = 65536
        max_record_bytes = 2048
        flush_interval_ms = 1000
        on_io_error = "event"

        [control]
        mode = "off"

        [[tunnels]]
        name = "main"
        role = "{role}"
        adapters = ["modules/{adapter}.toml"]
        protection = "modules/noise.toml"
        carrier = "modules/tcp.toml"
        policy = "modules/policy.toml"
    "#
    )) + "\n"
}

fn module(instance: &str, pkg: &str, role: &str, options: &str) -> String {
    format!(
        "wire_version = 1\ninstance = \"{instance}\"\npackage = \"owenewans/{pkg}@{MODULE_VERSION}\"\nrole = \"{role}\"\n\n[options]\n{}\n",
        trim_indent(options)
    )
}

fn policy(role: &str) -> String {
    module(
        "policy-main",
        "policy-dummy",
        role,
        "pump_buffer_bytes = 16384",
    )
}

/// Exit node: listens on `port`, private key at `key_file`, forwards straight to the internet.
pub fn server(port: i32, key_file: &str, state_dir: &str) -> Vec<SnolcFile> {
    vec![
        ("snolc.toml".into(), engine("server", "direct", state_dir, false)),
        (
            "modules/direct.toml".into(),
            module(
                "direct-main",
                "adapter-direct",
                "server",
                r#"
            dns_mode = "system"
            max_pending_opens = 64
            max_resolved_addresses = 16
            resolve_timeout_ms = 15000
            connect_timeout_ms = 15000
            "#,
            ),
        ),
        (
            "modules/noise.toml".into(),
            module("noise-main", "protection-noise", "server", &format!("mode = \"server\"\nprivate_key_file = \"{key_file}\"")),
        ),
        (
            "modules/tcp.toml".into(),
            module(
                "tcp-main",
                "carrier-tcp",
                "server",
                &format!("mode = \"listen\"\nendpoint_ip = \"0.0.0.0:{port}\"\nmax_connections = 4\nnodelay = true"),
            ),
        ),
        ("modules/policy.toml".into(), policy("server")),
    ]
}

/// Client: SOCKS5 on `listen_host:socks_port` (session login when `username` is not blank, else
/// open), node public key in `pub.hex` (written by the caller).
pub fn client(
    listen_host: &str,
    socks_port: i32,
    host: &str,
    port: i32,
    debug: bool,
    username: &str,
    password: &str,
) -> Vec<SnolcFile> {
    let endpoint = if host.contains(':') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    };
    let login = if !is_blank(username) {
        format!(
            "\nusername = \"{}\"\npassword = \"{}\"",
            toml_escape(username),
            toml_escape(password)
        )
    } else {
        String::new()
    };
    let socks = format!(
        r#"
                listen = "{listen_host}:{socks_port}"
                max_connections = 128
                max_udp_associations = 8
                max_request_bytes = 1024
                reject_fragments = true
                "#
    ) + &login;
    vec![
        ("snolc.toml".into(), engine("client", "socks5", "state", debug)),
        ("modules/socks5.toml".into(), module("socks5-main", "adapter-socks5", "client", &socks)),
        (
            "modules/noise.toml".into(),
            module("noise-main", "protection-noise", "client", "mode = \"client\"\nserver_public_key_file = \"../pub.hex\""),
        ),
        (
            "modules/tcp.toml".into(),
            module(
                "tcp-main",
                "carrier-tcp",
                "client",
                &format!("mode = \"connect\"\nendpoint_ip = \"{endpoint}\"\nmax_connections = 2\nnodelay = true"),
            ),
        ),
        ("modules/policy.toml".into(), policy("client")),
    ]
}
