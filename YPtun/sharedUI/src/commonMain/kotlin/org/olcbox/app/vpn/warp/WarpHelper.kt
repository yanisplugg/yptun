package org.olcbox.app.vpn.warp

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.model.ProxyProfile

/**
 * AmneziaWG-on-the-server relay. awgproxy (a userspace engine outside the proxy core) dials UDP
 * itself, so to make its WireGuard packets travel INSIDE the main proxy the core listens on
 * `127.0.0.1:[listenPort]` (UDP) and forwards whatever arrives to the real WARP endpoint
 * ([targetHost]:[targetPort]) through the main proxy. AWG's Endpoint is rewritten to that loopback.
 */
data class WarpRelay(val listenPort: Int, val targetHost: String, val targetPort: Int)

object WarpHelper {

    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
    private const val WARP_ENDPOINT_IP = "162.159.192.1"

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)

    /** `host` / `port` of the INI's `Endpoint =` line (WARP default when absent). */
    fun endpointOf(ini: String): Pair<String, Int> {
        val line = ini.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
        val ep = line?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
        val colon = ep.lastIndexOf(':')
        return if (colon > 0) {
            ep.substring(0, colon).removePrefix("[").removeSuffix("]") to (ep.substring(colon + 1).toIntOrNull() ?: 2408)
        } else ep to 2408
    }

    private val OBFUSCATION_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime",
    )

    private fun dropKeys(ini: String, keys: Set<String>): String =
        ini.lineSequence().filterNot { line ->
            val eq = line.indexOf('=')
            eq > 0 && line.substring(0, eq).trim().lowercase() in keys
        }.joinToString("\n")

    /** The INI as PLAIN WireGuard: every AmneziaWG obfuscation knob (and `Reserved`) dropped. */
    fun plainWireGuard(ini: String): String = dropKeys(ini, OBFUSCATION_KEYS + "reserved")

    /**
     * The INI as the awgproxy engine should run it (measured live against Cloudflare):
     *  - `Reserved` is always dropped: awgproxy stamps those bytes AFTER the handshake MAC, so Cloudflare
     *    discards every handshake of an officially-registered account (never connects). Free WARP needs none.
     *  - AmneziaWG keeps every obfuscation knob INCLUDING the I1..I5 init packets. Do not drop them: on a DPI
     *    network (mobile/home RU operators) the handshake completes and then ALL data is cut after ~10 s —
     *    seen live on the phone ("nothing opens", rx frozen, handshake retries unanswered). A clean PC
     *    network measured I1 slower (~60 s to first data) than Jc-only, but that network has no DPI to beat.
     *  - the endpoint is pinned to WARP's anycast IP: a domain is resolved at start-up by Go's resolver,
     *    which on Android has no resolv.conf (and, with the VPN already up, would loop into our own tunnel).
     */
    fun engineIni(ini: String, awg: Boolean): String {
        val base = if (awg) dropKeys(ini, setOf("reserved")) else plainWireGuard(ini)
        val (host, port) = endpointOf(ini)
        return withEndpoint(base, "${pinnedHost(host)}:$port")
    }

    /** [host] unchanged when it is an IP literal, else WARP's anycast IP. */
    fun pinnedHost(host: String): String = if (isIpLiteral(host)) host else WARP_ENDPOINT_IP

    /** The same INI with its `Endpoint =` line pointed at [hostPort]. */
    fun withEndpoint(ini: String, hostPort: String): String =
        ini.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith("Endpoint", ignoreCase = true) && line.contains('=')) "Endpoint = $hostPort" else line
        }

    /**
     * Parses a Cloudflare WARP WireGuard / AmneziaWG INI configuration into a [ProxyProfile]
     * with type="wireguard" and sing-box format rawOutbound.
     */
    fun parseWarpIniToProxyProfile(ini: String, tag: String = "WARP"): ProxyProfile? {
        if (ini.isBlank()) return null

        var privateKey: String? = null
        var addresses = mutableListOf<String>()
        var mtu: Int? = null

        var peerPublicKey: String? = null
        var endpoint: String? = null
        var keepalive: Int? = 25

        for (rawLine in ini.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "privatekey" -> privateKey = value
                "publickey" -> peerPublicKey = value
                "address" -> {
                    // May be comma-separated list of IPv4 and IPv6 CIDRs
                    value.split(',').forEach { addr ->
                        val trimmed = addr.trim()
                        if (trimmed.isNotEmpty()) addresses.add(trimmed)
                    }
                }
                "endpoint" -> endpoint = value
                "mtu" -> mtu = value.toIntOrNull()
                "persistentkeepalive" -> keepalive = value.toIntOrNull() ?: keepalive
            }
        }

        val priv = privateKey?.takeIf { it.isNotBlank() } ?: return null
        val peerPub = peerPublicKey?.takeIf { it.isNotBlank() } ?: return null

        // Default endpoint if missing or unparsed
        val ep = endpoint?.takeIf { it.isNotBlank() } ?: "engage.cloudflareclient.com:2408"
        val lastColon = ep.lastIndexOf(':')
        var host: String
        val port: Int
        if (lastColon > 0) {
            host = ep.substring(0, lastColon).removePrefix("[").removeSuffix("]")
            port = ep.substring(lastColon + 1).toIntOrNull() ?: 2408
        } else {
            host = ep
            port = 2408
        }
        // Never hand the cores a DOMAIN endpoint: xray resolves it with its own DNS, whose queries are routed
        // to the proxy — which here is the very WireGuard outbound that is waiting for this lookup (deadlock:
        // "failed to set endpoint engage.cloudflareclient.com … record not found"). WARP's anycast IP is stable.
        host = pinnedHost(host)

        val rawOutbound = buildJsonObject {
            put("type", "wireguard")
            put("server", host)
            put("server_port", port)
            putJsonArray("local_address") {
                addresses.forEach { add(it) }
            }
            put("private_key", priv)
            put("peer_public_key", peerPub)
            if (mtu != null && mtu > 0) put("mtu", mtu)
            if (keepalive != null && keepalive > 0) put("persistent_keepalive", keepalive)
        }.toString()

        return ProxyProfile(
            tag = tag,
            type = "wireguard",
            server = host,
            serverPort = port,
            rawOutbound = rawOutbound
        )
    }
}
