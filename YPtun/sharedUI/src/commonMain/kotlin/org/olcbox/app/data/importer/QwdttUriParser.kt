package org.olcbox.app.data.importer

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VkTurnConfig

/**
 * Parses the qWDTT quick link the wdtt-server's Telegram bot emits (see wdtt-server/database_bot.go):
 *
 * ```
 * qwdtt://config?name=<esc>&peer=<esc>&hashes=<esc>&workers=9&port=9000&pass=<esc>
 * ```
 *
 * `peer` is the wdtt-server host only (its DTLS port is the client default 56000, so the quick link
 * omits it); `port` is the LOCAL WireGuard listen port; `hashes` are the VK hashes the core dials with;
 * `pass` is the server password. Everything else — the WireGuard keys — the WDTT core fetches from the
 * server at runtime (GETCONF), so the link carries no WG config. Maps onto a VK-TURN location whose
 * core is [org.olcbox.app.data.model.VkTurnConfig.CORE_WDTT].
 */
object QwdttUriParser {

    const val SCHEME = "qwdtt://"

    data class QwdttLink(
        val name: String,
        /** wdtt-server host (no port — the DTLS port defaults to 56000 on the client). */
        val peer: String,
        /**
         * VK hashes, ONE PER LINE — the storage format `VkTurnConfig.vkLink` uses everywhere else
         * (the location settings edit it as one field per line). The server link packs them
         * comma-separated; [parse] splits them so each hash lands in its own «Ссылка на звонок VK».
         */
        val hashes: String,
        val password: String,
        /** Local WireGuard listen port; 0 → the default. */
        val listenPort: Int,
        /** Workers per hash; 0 → the core default. */
        val workers: Int,
    )

    fun parse(uri: String): QwdttLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        // qwdtt://config?<query> — tolerate a missing/other authority, we only need the query.
        val query = trimmed.substringAfter('?', "")
        if (query.isBlank()) return null
        val p = UriCodec.parseQuery(query)

        val peer = p["peer"]?.trim().orEmpty()
        val password = p["pass"]?.trim().orEmpty()
        if (peer.isBlank() || password.isBlank()) return null

        return QwdttLink(
            name = p["name"]?.trim().orEmpty(),
            peer = peer,
            hashes = splitHashes(p["hashes"].orEmpty()).joinToString("\n"),
            password = password,
            listenPort = p["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0,
            workers = p["workers"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
        )
    }

    /** Re-emits the quick link for a stored WDTT VK-TURN location (round-trips [parse]). */
    fun compose(name: String, vk: VkTurnConfig): String {
        val port = vk.listenPort.takeIf { it in 1..65535 } ?: LocationConfig.DEFAULT_FREETURN_PORT
        val params = buildList {
            if (name.isNotBlank()) add("name" to name)
            add("peer" to vk.wdttPeer.trim())
            splitHashes(vk.vkLink).takeIf { it.isNotEmpty() }?.let { add("hashes" to it.joinToString(",")) }
            if (vk.wdttWorkers > 0) add("workers" to vk.wdttWorkers.toString())
            add("port" to port.toString())
            add("pass" to vk.wdttPassword.trim())
        }
        return SCHEME + "config?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /**
     * Splits a hash list the way the qWDTT core does (`wdtt/group.go` `ParseHashes`): commas,
     * semicolons or any whitespace, blanks dropped. Lets the server's comma-separated `hashes=` and
     * our newline-separated [VkTurnConfig.vkLink] round-trip through the same parser.
     */
    private fun splitHashes(raw: String): List<String> =
        raw.split(',', ';', '\n', '\r', '\t', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Minimal RFC 3986 percent-encoding for query values. */
    private fun encode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else {
                append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}
