package org.olcbox.app.data.importer

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
}
