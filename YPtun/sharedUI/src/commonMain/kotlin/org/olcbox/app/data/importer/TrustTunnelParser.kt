package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.olcbox.app.data.model.ProxyProfile

/**
 * Recognises an AdGuard Trust Tunnel `tt://` deep-link and wraps it in a [ProxyProfile] of
 * [ProxyProfile.TYPE_TRUSTTUNNEL]. The payload is an opaque base64url TLV blob (hostname, creds,
 * addresses, embedded certificate, …) that only the native `DeepLink.decode` (in the vendored
 * trusttunnel AAR) can turn into a `[endpoint]` TOML — so here we keep the raw link verbatim in
 * [ProxyProfile.ttConfig] and decode it lazily at connect time (androidMain). The optional `#name`
 * fragment becomes the display tag; server/port stay placeholders (the real endpoint lives inside
 * the encoded blob and is resolved by the native client).
 */
/**
 * Server to show in the UI: a Trust Tunnel profile keeps the placeholder "trusttunnel" as its server (the real
 * endpoint lives inside the tt:// blob), so read it from the link; every other protocol shows [ProxyProfile.server].
 */
val ProxyProfile.displayServer: String
    get() = if (type == ProxyProfile.TYPE_TRUSTTUNNEL) {
        TrustTunnelParser.endpoints(ttConfig).firstOrNull()?.first ?: server
    } else server

object TrustTunnelParser {

    const val SCHEME = "tt://"

    fun looksLikeTrustTunnel(text: String): Boolean =
        text.trim().startsWith(SCHEME, ignoreCase = true)

    fun parse(uri: String): ProxyProfile? {
        val trimmed = uri.trim()
        if (!looksLikeTrustTunnel(trimmed)) return null
        // Payload sits between the scheme and an optional `#name` fragment; a `tt://?<payload>`
        // (query-style) form is accepted too by dropping a single leading '?'.
        val afterScheme = trimmed.substring(SCHEME.length)
        val payload = afterScheme.substringBefore('#').removePrefix("?").trim()
        if (payload.isEmpty()) return null
        val name = afterScheme.substringAfter('#', "").let(UriCodec::percentDecode)
            .ifBlank { name(trimmed) ?: "Trust Tunnel" }
        return ProxyProfile(
            tag = name,
            type = ProxyProfile.TYPE_TRUSTTUNNEL,
            server = "trusttunnel",
            serverPort = 0,
            ttConfig = trimmed,
        )
    }

    private const val TAG_HOSTNAME = 1
    private const val TAG_ADDRESS = 2
    private const val TAG_NAME = 12

    /**
     * TLV list of a `tt://` blob (tag/length are QUIC-style varints: the top 2 bits of the first byte give the
     * byte count), empty when the link cannot be read.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun items(link: String): List<Pair<Int, String>> {
        val payload = link.trim().removePrefix(SCHEME).substringBefore('#').removePrefix("?").trim()
        val bytes = runCatching {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload)
        }.getOrNull() ?: return emptyList()
        var i = 0
        fun varint(): Int? {
            if (i >= bytes.size) return null
            val len = 1 shl ((bytes[i].toInt() and 0xFF) ushr 6)
            if (i + len > bytes.size) return null
            var v = (bytes[i].toInt() and 0x3F)
            for (k in 1 until len) v = (v shl 8) or (bytes[i + k].toInt() and 0xFF)
            i += len
            return v
        }
        val out = mutableListOf<Pair<Int, String>>()
        while (i < bytes.size) {
            val tag = varint() ?: break
            val len = varint() ?: break
            if (len < 0 || i + len > bytes.size) break
            // Only the text fields are read; binary ones (certificate) are skipped untouched.
            if (tag == TAG_HOSTNAME || tag == TAG_ADDRESS || tag == TAG_NAME) out += tag to bytes.decodeToString(i, i + len)
            i += len
        }
        return out
    }

    /** Display name the panel puts into the blob (tag 12), or null when the link carries none. */
    fun name(link: String): String? =
        items(link).firstOrNull { it.first == TAG_NAME }?.second?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Real `host`/`port` of the endpoint(s) inside a `tt://` blob, for pings (the profile itself carries a
     * placeholder). Falls back to `hostname:443` when no address is present; empty when the link cannot be read.
     */
    fun endpoints(link: String): List<Pair<String, Int>> {
        val all = items(link)
        val hostname = all.firstOrNull { it.first == TAG_HOSTNAME }?.second.orEmpty()
        val raw = all.filter { it.first == TAG_ADDRESS }.map { it.second }
            .ifEmpty { listOfNotNull(hostname.takeIf { it.isNotBlank() }?.let { "$it:443" }) }
        return raw.mapNotNull { addr ->
            val host = addr.substringBeforeLast(':', "").removeSurrounding("[", "]")
            val port = addr.substringAfterLast(':', "").toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) null else host to port
        }
    }
}
