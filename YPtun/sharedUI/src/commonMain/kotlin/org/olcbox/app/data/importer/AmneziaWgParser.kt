package org.olcbox.app.data.importer

import org.olcbox.app.data.model.ProxyProfile

/**
 * Recognises an AmneziaWG configuration: a wg-quick INI whose `[Interface]` carries the Amnezia
 * obfuscation knobs (Jc/Jmin/Jmax/S1/S2/H1..H4). The whole INI is preserved in
 * [ProxyProfile.awgConfig] (the awgproxy module re-parses it); server/port come from the peer
 * Endpoint for display/dedup.
 */
object AmneziaWgParser {

    private val AWG_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

    /** True when [text] looks like an AmneziaWG wg-quick config (has [Interface] + an awg knob). */
    fun looksLikeAmneziaWg(text: String): Boolean {
        if (!text.contains("[Interface]", ignoreCase = true)) return false
        return text.lineSequence().any { line ->
            val key = line.substringBefore('=', "").trim().lowercase()
            key in AWG_KEYS
        }
    }

    fun parse(text: String): ProxyProfile? {
        if (!looksLikeAmneziaWg(text)) return null
        var endpoint = ""
        var name = ""
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("#")) {
                name = line.removePrefix("#").trim().ifBlank { name }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (key == "endpoint") endpoint = value
        }
        val (host, port) = endpoint.takeIf { it.isNotBlank() }
            ?.let { UriCodec.splitHostPort(it) }
            ?: ("amneziawg" to 0)

        return ProxyProfile(
            tag = name.ifBlank { if (host.isNotBlank()) "AmneziaWG $host" else "AmneziaWG" },
            type = ProxyProfile.TYPE_AMNEZIAWG,
            server = host,
            serverPort = port,
            awgConfig = text.trim(),
        )
    }
}
