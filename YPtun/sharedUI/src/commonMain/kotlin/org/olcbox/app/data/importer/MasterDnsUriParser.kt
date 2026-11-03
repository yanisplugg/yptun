package org.olcbox.app.data.importer

import org.olcbox.app.data.model.MasterDnsConfig

/**
 * Subscription link for a MasterDNS tunnel (the panel's personal-subscription list):
 *
 * ```
 * masterdns://v1?domains=<a,b>&key=<secret>&enc=<0..5>&resolvers=<ip[:port],…>[&bal=<0..8>][&dup=<0..10>][&proxy=<base64url(share link)>]#<name>
 * ```
 *
 * `domains` and `resolvers` are comma separated, `enc` is MasterDnsVPN's DATA_ENCRYPTION_METHOD
 * (default 1 = XOR), `proxy` optionally chains a vless/vmess/trojan/ss link on top of the tunnel.
 * Values are percent-encoded; the version segment (`v1`) lets the format grow without breaking old
 * clients — an unknown version yields null instead of a half-parsed location.
 */
object MasterDnsUriParser {

    const val SCHEME = "masterdns://"
    private const val PREFIX = "masterdns://v1"

    data class MasterDnsLink(val name: String, val config: MasterDnsConfig)

    fun parse(link: String): MasterDnsLink? {
        val t = link.trim()
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = t.substring(PREFIX.length)
        val name = rest.substringAfter('#', "").let(UriCodec::percentDecode)
        val q = UriCodec.parseQuery(rest.substringBefore('#').substringAfter('?', ""))
        val config = MasterDnsConfig(
            domains = q["domains"].orEmpty(),
            encryptionKey = q["key"].orEmpty(),
            encryptionMethod = q["enc"]?.toIntOrNull() ?: MasterDnsConfig.DEFAULT_ENCRYPTION_METHOD,
            resolvers = q["resolvers"].orEmpty(),
            balancingStrategy = q["bal"]?.toIntOrNull() ?: 0,
            packetDuplication = q["dup"]?.toIntOrNull() ?: 0,
            proxyLink = q["proxy"]?.let(SubscriptionDecoder::decodeBase64Chunk).orEmpty(),
        ).normalized()
        return if (config.isComplete()) MasterDnsLink(name, config) else null
    }
}
