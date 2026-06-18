package org.olcbox.app.data.importer

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Parses the panel's VK-TURN share link:
 *
 * ```
 * freeturn://vk?<transport><mode=..&obf-profile=..&bond=1&wg=<b64>>@<ip:port>#<obfKey>$<comment>
 * ```
 *
 * The angle-bracket block carries the freeturn transport params; the panel also embeds the
 * client WireGuard config (wg-quick INI, base64url, padding stripped) as `wg=`. We keep the
 * original link verbatim for the Go freeturn client (it re-parses everything and ignores `wg`)
 * and decode `wg=` here into a sing-box (legacy) WireGuard outbound whose Endpoint already
 * points at the local freeturn listener (127.0.0.1:<port>).
 */
object FreeturnUriParser {

    const val SCHEME = "freeturn://"

    data class FreeturnLink(
        /** The original freeturn:// link, passed verbatim to the Go client. */
        val uri: String,
        /** Peer (VPS) host carried after `@`, for display/dedup. */
        val serverIp: String,
        /** Peer (VPS) port carried after `@`, for display/dedup. */
        val serverPort: Int,
        /** Local port the freeturn client raises (= WireGuard Endpoint port in udp mode; 0 in tcp). */
        val listenPort: Int,
        /** Tunnel mode: "udp" (WireGuard) or "tcp" (Proxy/bonded). */
        val mode: String,
        /** sing-box wireguard outbound (verbatim ProxyProfile.rawOutbound) — udp mode only; "" otherwise. */
        val wgOutboundJson: String,
        /** Exit proxy share link (vless/vmess/trojan/ss) decoded from `vless=` — tcp mode only; "" otherwise. */
        val exitProxyLink: String,
        /** Optional human comment after `$`. */
        val comment: String,
    )

    fun parse(uri: String): FreeturnLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null

        var s = trimmed.substring(SCHEME.length)

        // 1. comment after the last '$'
        var comment = ""
        s.lastIndexOf('$').takeIf { it >= 0 }?.let { idx ->
            comment = s.substring(idx + 1)
            s = s.substring(0, idx)
        }
        // 2. obfKey after '#' (consumed by the Go client; we only strip it off here)
        s.lastIndexOf('#').takeIf { it >= 0 }?.let { idx ->
            s = s.substring(0, idx)
        }
        // 3. peer after '@'
        var peer = ""
        s.lastIndexOf('@').takeIf { it >= 0 }?.let { idx ->
            peer = s.substring(idx + 1)
            s = s.substring(0, idx)
        }
        if (peer.isBlank()) return null
        val (serverIp, serverPort) = UriCodec.splitHostPort(peer) ?: return null

        // 4. remaining: <provider>?<transport><k=v&...>. udp embeds the WireGuard outbound as `wg=`,
        //    tcp (Proxy/bonded) embeds the exit proxy share link as `vless=` (both base64url). The link
        //    type is decided by which payload is present (mode= is the tiebreaker), so a tcp/bonded
        //    link no longer fails to import just because it carries no WireGuard config.
        val params = extractAngleParams(s)
        val cm = comment.let(UriCodec::percentDecode)
        val wgB64 = params["wg"]
        val exitB64 = params["vless"] ?: params["proxy"]
        val mode = params["mode"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // tcp / Proxy-bonded: needs the embedded exit proxy link. No WireGuard outbound.
        if (exitB64 != null && (mode == "tcp" || wgB64 == null)) {
            val exit = SubscriptionDecoder.decodeBase64Chunk(exitB64)?.trim().orEmpty()
            if (exit.isBlank()) return null
            return FreeturnLink(
                uri = trimmed, serverIp = serverIp, serverPort = serverPort,
                listenPort = 0, mode = "tcp", wgOutboundJson = "", exitProxyLink = exit, comment = cm,
            )
        }

        // udp / WireGuard: decode the embedded wg-quick INI into a sing-box wireguard outbound.
        val wgConf = wgB64?.let { SubscriptionDecoder.decodeBase64Chunk(it) } ?: return null
        val wg = parseWgConf(wgConf) ?: return null
        return FreeturnLink(
            uri = trimmed, serverIp = serverIp, serverPort = serverPort,
            listenPort = wg.endpointPort, mode = "udp",
            wgOutboundJson = wg.outboundJson, exitProxyLink = "", comment = cm,
        )
    }

    /** Pulls the `key=val&...` map out of the first `<...>` block in `?<transport><...>`. */
    private fun extractAngleParams(s: String): Map<String, String> {
        val open = s.indexOf('<')
        val close = s.lastIndexOf('>')
        if (open < 0 || close <= open) return emptyMap()
        val inner = s.substring(open + 1, close)
        return inner.split('&')
            .mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
            }
            .toMap()
    }

    private data class WgConf(val endpointPort: Int, val outboundJson: String)

    /** Converts a wg-quick INI client config into a sing-box legacy wireguard outbound. */
    private fun parseWgConf(conf: String): WgConf? {
        var privateKey = ""
        var address = ""
        var mtu = 0
        var publicKey = ""
        var endpoint = ""
        for (rawLine in conf.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "privatekey" -> privateKey = value
                // Address may be a comma list; the WG tunnel address is the first entry.
                "address" -> if (address.isBlank()) address = value.substringBefore(',').trim()
                "mtu" -> mtu = value.toIntOrNull() ?: 0
                "publickey" -> publicKey = value
                "endpoint" -> endpoint = value
            }
        }
        if (privateKey.isBlank() || publicKey.isBlank() || address.isBlank()) return null
        val (epHost, epPort) = UriCodec.splitHostPort(endpoint) ?: return null

        val json = buildJsonObject {
            put("type", "wireguard")
            put("server", epHost)
            put("server_port", epPort)
            putJsonArray("local_address") { add(address) }
            put("private_key", privateKey)
            put("peer_public_key", publicKey)
            if (mtu > 0) put("mtu", mtu)
        }
        return WgConf(endpointPort = epPort, outboundJson = json.toString())
    }
}
