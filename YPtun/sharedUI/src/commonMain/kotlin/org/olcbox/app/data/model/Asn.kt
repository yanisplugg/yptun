package org.olcbox.app.data.model

/**
 * Helpers for ASN-based routing selectors (`asn:13335`, `asn:AS13335`). An ASN (Autonomous System
 * Number) names a whole network operator (e.g. `13335` Cloudflare, `62041` Telegram); matching by ASN
 * catches all of that operator's IP ranges — including bare-IP services that have no domain, which
 * domain lists miss.
 *
 * Neither sing-box nor xray match ASN natively, so an `asn:N` selector is EXPANDED to the operator's
 * CIDR list at config-build time (the platform resolves N → prefixes via [referencedAsns] +
 * [expand]); the cores then see ordinary `ip_cidr` / `ip` entries. An ASN that can't be resolved
 * contributes nothing, so the rest of the rule still applies (graceful degrade, like missing geo).
 */
object Asn {
    /** True when [selector] is an `asn:` routing selector. */
    fun isSelector(selector: String): Boolean = selector.trim().startsWith("asn:", ignoreCase = true)

    /**
     * Normalizes any ASN spelling — `asn:13335`, `AS13335`, `13335` — to the bare number string, or
     * null when it isn't a plain numeric ASN.
     */
    fun normalize(raw: String): String? {
        var v = raw.trim()
        if (v.startsWith("asn:", ignoreCase = true)) v = v.substring(4).trim()
        if (v.startsWith("as", ignoreCase = true)) v = v.substring(2).trim()
        return v.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** The distinct, normalized ASN numbers referenced by an `asn:` selector list. */
    fun collect(selectors: List<String>): Set<String> =
        selectors.asSequence().filter { isSelector(it) }.mapNotNull { normalize(it) }.toSet()

    /**
     * Replaces every `asn:N` entry in [selectors] with the CIDRs from [cidrsByAsn] (an unresolved ASN
     * contributes nothing). Non-asn entries pass through unchanged and order is preserved.
     */
    fun expand(selectors: List<String>, cidrsByAsn: Map<String, List<String>>): List<String> {
        if (selectors.none { isSelector(it) }) return selectors
        return selectors.flatMap { sel ->
            if (!isSelector(sel)) listOf(sel)
            else normalize(sel)?.let { cidrsByAsn[it] } ?: emptyList()
        }
    }

    /**
     * A few well-known ASNs offered as one-tap presets in the editor, so users don't have to look up
     * numbers. (label → bare ASN). Purely a convenience; any ASN can still be typed by hand.
     */
    val COMMON_PRESETS: List<Pair<String, String>> = listOf(
        "Telegram" to "62041",
        "Cloudflare" to "13335",
        "Google" to "15169",
        "Meta (FB/IG/WA)" to "32934",
        "Discord" to "49544",
        "Twitter / X" to "13414",
        "Amazon AWS" to "16509",
        "Microsoft" to "8075",
        "Netflix" to "2906",
        "Akamai" to "20940",
    )
}
