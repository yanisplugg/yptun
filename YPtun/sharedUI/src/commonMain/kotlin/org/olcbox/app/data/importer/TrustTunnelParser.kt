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
        val name = afterScheme.substringAfter('#', "").let(UriCodec::percentDecode).ifBlank { "Trust Tunnel" }
        return ProxyProfile(
            tag = name,
            type = ProxyProfile.TYPE_TRUSTTUNNEL,
            server = "trusttunnel",
            serverPort = 0,
            ttConfig = trimmed,
        )
    }

    /**
     * Real `host`/`port` of the endpoint(s) inside a `tt://` blob, for pings (the profile itself carries
     * a placeholder). The blob is a TLV list — tag/length are QUIC-style varints: tag 1 = hostname,
     * tag 2 = endpoint address `host:port` (repeated). Falls back to `hostname:443` when no address is
     * present; empty when the link cannot be read.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun endpoints(link: String): List<Pair<String, Int>> {
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
        var hostname = ""
        val addresses = mutableListOf<String>()
        while (i < bytes.size) {
            val tag = varint() ?: break
            val len = varint() ?: break
            if (len < 0 || i + len > bytes.size) break
            val value = bytes.decodeToString(i, i + len)
            i += len
            when (tag) {
                1 -> hostname = value
                2 -> addresses += value
            }
        }
        val raw = addresses.ifEmpty { listOfNotNull(hostname.takeIf { it.isNotBlank() }?.let { "$it:443" }) }
        return raw.mapNotNull { addr ->
            val host = addr.substringBeforeLast(':', "").removeSurrounding("[", "]")
            val port = addr.substringAfterLast(':', "").toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) null else host to port
        }
    }
}
