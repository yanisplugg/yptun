package org.olcbox.app.vpn.snolc

/**
 * The TOML files the static `snolc` executable runs from (`packages = "builtin"`: every module is linked in,
 * see olcvpn-client/snolc/YPTUN.md). Relative paths resolve against the file that holds them.
 *
 * Limits are raised from upstream's 256 MiB "low-memory" template (2 sessions, 16 flows per client), which
 * would choke a phone browser: 64 flows per client, 4 clients. Yamux's window must be at least
 * streams × 256 KiB, hence 65 × 262144.
 */
object SnolcFiles {
    private const val MODULE_VERSION = "0.0.4"

    private fun String.toml() = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun engine(
        role: String,
        adapter: String,
        stateDir: String,
        debug: Boolean,
        lowMemory: Boolean = false,
    ) = """
        wire_version = 1

        [paths]
        packages = "builtin"
        state = "$stateDir"

        [engine]
        max_sessions = ${if (lowMemory) 2 else 4}
        max_flows = ${if (lowMemory) 32 else 256}
        max_pending_sessions = ${if (lowMemory) 2 else 4}
        max_pending_opens = ${if (lowMemory) 16 else 64}
        max_managed_bytes = ${if (lowMemory) 33554432 else 268435456}
        max_commands = ${if (lowMemory) 16 else 64}
        max_events = ${if (lowMemory) 64 else 256}
        max_io_chunk = 16384
        max_ingress_packets_per_tick = 32
        connect_timeout_ms = 15000
        handshake_timeout_ms = 15000
        shutdown_timeout_ms = 5000

        [stack]
        ipv4 = true
        ipv6 = true
        mtu = 1280
        tcp_socket_rx_bytes = ${if (lowMemory) 32768 else 65536}
        tcp_socket_tx_bytes = ${if (lowMemory) 32768 else 65536}
        udp_socket_rx_bytes = ${if (lowMemory) 65536 else 131072}
        udp_socket_tx_bytes = ${if (lowMemory) 65536 else 131072}
        udp_metadata_slots = 8
        packet_queue_bytes = 262144
        max_udp_payload_bytes = 65507
        reassembly_slots = 4
        reassembly_timeout_ms = 15000

        [yamux]
        max_streams_per_session = ${if (lowMemory) 16 else 65}
        receive_window_bytes = ${if (lowMemory) 4194304 else 17039360}
        split_send_size = 16384
        read_after_close = true

        [logging]
        mode = "file"
        source = "toml"
        levels = [${if (debug) "\"warning\", \"error\", \"debug\"" else "\"warning\", \"error\""}]
        file = "$stateDir/snolc.log"
        limit = "8mb"
        queue_bytes = 65536
        max_record_bytes = 2048
        flush_interval_ms = 1000
        on_io_error = "event"

        [control]
        mode = "off"

        [[tunnels]]
        name = "main"
        role = "$role"
        adapters = ["modules/$adapter.toml"]
        protection = "modules/noise.toml"
        carrier = "modules/tcp.toml"
        policy = "modules/policy.toml"
    """.trimIndent() + "\n"

    private fun module(instance: String, pkg: String, role: String, options: String) =
        "wire_version = 1\ninstance = \"$instance\"\npackage = \"owenewans/$pkg@$MODULE_VERSION\"\nrole = \"$role\"\n\n[options]\n" +
            options.trimIndent() + "\n"

    private val policy = { role: String -> module("policy-main", "policy-dummy", role, "pump_buffer_bytes = 16384") }

    /** Exit node: listens on [port], private key at [keyFile], forwards straight to the internet. */
    fun server(port: Int, keyFile: String, stateDir: String): Map<String, String> = mapOf(
        "snolc.toml" to engine("server", "direct", stateDir, debug = false),
        "modules/direct.toml" to module(
            "direct-main", "adapter-direct", "server",
            """
            dns_mode = "system"
            max_pending_opens = 64
            max_resolved_addresses = 16
            resolve_timeout_ms = 15000
            connect_timeout_ms = 15000
            """,
        ),
        "modules/noise.toml" to module("noise-main", "protection-noise", "server", "mode = \"server\"\nprivate_key_file = \"$keyFile\""),
        "modules/tcp.toml" to module(
            "tcp-main", "carrier-tcp", "server",
            "mode = \"listen\"\nendpoint_ip = \"0.0.0.0:$port\"\nmax_connections = 4\nnodelay = true",
        ),
        "modules/policy.toml" to policy("server"),
    )

    /**
     * Client: SOCKS5 on [listenHost]:[socksPort] (session login when [username] is not blank, else open),
     * node public key in `pub.hex` (written by the caller).
     */
    fun client(
        listenHost: String,
        socksPort: Int,
        host: String,
        port: Int,
        debug: Boolean,
        username: String = "",
        password: String = "",
        lowMemory: Boolean = false,
    ): Map<String, String> {
        val endpoint = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return mapOf(
            "snolc.toml" to engine("client", "socks5", "state", debug, lowMemory),
            "modules/socks5.toml" to module(
                "socks5-main", "adapter-socks5", "client",
                """
                listen = "$listenHost:$socksPort"
                max_connections = 128
                max_udp_associations = 8
                max_request_bytes = 1024
                reject_fragments = true
                """ + if (username.isNotBlank()) "\nusername = \"${username.toml()}\"\npassword = \"${password.toml()}\"" else "",
            ),
            "modules/noise.toml" to module("noise-main", "protection-noise", "client", "mode = \"client\"\nserver_public_key_file = \"../pub.hex\""),
            "modules/tcp.toml" to module(
                "tcp-main", "carrier-tcp", "client",
                "mode = \"connect\"\nendpoint_ip = \"$endpoint\"\nmax_connections = 2\nnodelay = true",
            ),
            "modules/policy.toml" to policy("client"),
        )
    }
}
