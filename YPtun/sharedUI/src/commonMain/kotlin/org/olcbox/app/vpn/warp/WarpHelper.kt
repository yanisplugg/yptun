package org.olcbox.app.vpn.warp

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.model.ProxyProfile

object WarpHelper {

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
        val host: String
        val port: Int
        if (lastColon > 0) {
            host = ep.substring(0, lastColon).removePrefix("[").removeSuffix("]")
            port = ep.substring(lastColon + 1).toIntOrNull() ?: 2408
        } else {
            host = ep
            port = 2408
        }

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
