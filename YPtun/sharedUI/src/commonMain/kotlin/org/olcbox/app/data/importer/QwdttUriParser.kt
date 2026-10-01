package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses qWDTT / WDTT connection profiles and subscriptions:
 *
 * 1. URI schemes:
 *    - `qwdtt://config?name=..&peer=..&hashes=..&workers=9&port=9000&pass=..` (or `qwdtt:config?..`)
 *    - `wdtt://<server_ip>:<dtls_port>:<wg_port>:<local_port>:<password>:<vk_hash>`
 * 2. JSON configurations (from clipboard, file, Base64, or remote subscriptions):
 *    - Single profile: `{ "peer": "...", "password": "...", "vkHashes": "...", ... }`
 *    - Profile array: `[ { ... }, { ... } ]`
 *    - Subscription envelope: `{ "subscriptionName": "...", "profiles": [ ... ] }` or `{ "servers": [ ... ] }`
 */
object QwdttUriParser {

    const val SCHEME_QWDTT = "qwdtt://"
    const val SCHEME_QWDTT_SHORT = "qwdtt:"
    const val SCHEME_WDTT = "wdtt://"

    data class QwdttProfile(
        val name: String,
        val peer: String,
        val dtlsPort: Int,
        val hashes: String,
        val workers: Int,
        val listenPort: Int,
        val password: String,
        val rawMode: Boolean = false,
    )

    fun parseLine(rawLine: String): QwdttProfile? {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith(SCHEME_QWDTT, ignoreCase = true) ||
            trimmed.startsWith(SCHEME_QWDTT_SHORT, ignoreCase = true)
        ) {
            return parseQwdttUri(trimmed)
        }
        if (trimmed.startsWith(SCHEME_WDTT, ignoreCase = true)) {
            return parseWdttLegacyUri(trimmed)
        }
        return null
    }

    fun parseQwdttUri(uriString: String): QwdttProfile? {
        val trimmed = uriString.trim()
        val queryPart = when {
            trimmed.contains('?') -> trimmed.substringAfter('?')
            trimmed.contains("config/") -> trimmed.substringAfter("config/")
            else -> return null
        }
        val params = queryPart.split('&').mapNotNull { kv ->
            val eq = kv.indexOf('=')
            if (eq <= 0) null
            else UriCodec.percentDecode(kv.substring(0, eq).trim()).lowercase() to
                UriCodec.percentDecode(kv.substring(eq + 1).trim())
        }.toMap()

        var peerRaw = params["peer"] ?: return null
        if (peerRaw.startsWith("[") && peerRaw.contains("](")) {
            peerRaw = peerRaw.substringAfter('[').substringBefore(']')
        }
        peerRaw = peerRaw.trim().trimEnd('/')
        if (peerRaw.isBlank()) return null

        val dtlsPortParam = params["dtls_port"] ?: params["server_port"] ?: params["port_dtls"]
        val (peerHost, peerPort) = if (peerRaw.contains(':')) {
            UriCodec.splitHostPort(peerRaw) ?: (peerRaw.substringBefore(':') to (dtlsPortParam?.toIntOrNull() ?: 56000))
        } else {
            peerRaw to (dtlsPortParam?.toIntOrNull() ?: 56000)
        }

        val hashes = params["hashes"] ?: params["vk_hashes"] ?: params["vkhashes"] ?: params["hash"] ?: ""
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: "qWDTT $peerHost"
        val workers = params["workers"]?.toIntOrNull()
            ?: params["workers_per_hash"]?.toIntOrNull()
            ?: params["workersperhash"]?.toIntOrNull()
            ?: 9
        val listenPort = params["port"]?.toIntOrNull()
            ?: params["listen_port"]?.toIntOrNull()
            ?: params["listenport"]?.toIntOrNull()
            ?: 9000
        val pass = params["pass"] ?: params["password"] ?: ""
        val rawMode = params["raw"]?.equals("true", ignoreCase = true) == true ||
            params["mode"]?.equals("raw", ignoreCase = true) == true

        return QwdttProfile(
            name = name,
            peer = peerHost,
            dtlsPort = peerPort,
            hashes = hashes,
            workers = workers,
            listenPort = listenPort,
            password = pass,
            rawMode = rawMode,
        )
    }

    fun parseWdttLegacyUri(uriString: String): QwdttProfile? {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith(SCHEME_WDTT, ignoreCase = true)) return null
        val rest = trimmed.substring(SCHEME_WDTT.length)
        val parts = rest.split(':')
        if (parts.size < 6) return null
        val ip = parts[0].trim()
        if (ip.isBlank()) return null
        val dtlsPort = parts[1].trim().toIntOrNull() ?: 56000
        val localPort = parts[3].trim().toIntOrNull() ?: 9000
        val pass = parts[4].trim()
        val hash = parts.drop(5).joinToString(":").trim()

        return QwdttProfile(
            name = "WDTT $ip",
            peer = ip,
            dtlsPort = dtlsPort,
            hashes = hash,
            workers = 9,
            listenPort = localPort,
            password = pass,
            rawMode = false,
        )
    }

    fun parseJson(rawJson: String): List<QwdttProfile> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()

        val jsonElement = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()

        // 1. Array of profiles: [ {...}, {...} ]
        if (jsonElement is JsonArray) {
            return jsonElement.mapNotNull { el -> (el as? JsonObject)?.let { parseProfileObject(it) } }
        }

        // 2. Object: either a subscription envelope or a single profile
        if (jsonElement is JsonObject) {
            val profilesArray = jsonElement["profiles"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: jsonElement["servers"]?.let { runCatching { it.jsonArray }.getOrNull() }
            if (profilesArray != null) {
                val subName = jsonElement["subscriptionName"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?: jsonElement["name"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                return profilesArray.mapNotNull { el ->
                    (el as? JsonObject)?.let { parseProfileObject(it, fallbackName = subName) }
                }
            }

            // Single profile object
            parseProfileObject(jsonElement)?.let { return listOf(it) }
        }

        return emptyList()
    }

    private fun parseProfileObject(obj: JsonObject, fallbackName: String? = null): QwdttProfile? {
        val peerRaw = obj["peer"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: obj["server"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: return null
        if (peerRaw.isBlank()) return null

        val dtlsPortParam = obj["dtls_port"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["server_port"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["dtlsPort"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

        val (peerHost, peerPort) = if (peerRaw.contains(':')) {
            UriCodec.splitHostPort(peerRaw) ?: (peerRaw.substringBefore(':') to (dtlsPortParam ?: 56000))
        } else {
            peerRaw to (dtlsPortParam ?: 56000)
        }

        val name = obj["name"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: fallbackName
            ?: "qWDTT $peerHost"

        val hashes = obj["hashes"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: obj["vkHashes"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: obj["vk_hashes"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: obj["hash"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: ""

        val pass = obj["password"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: obj["pass"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?: ""

        val workers = obj["workers"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["workersPerHash"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["workers_per_hash"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: 9

        val listenPort = obj["listenPort"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["port"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: obj["listen_port"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            ?: 9000

        val rawMode = obj["raw"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            ?: obj["rawMode"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            ?: (obj["mode"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.equals("raw", ignoreCase = true) == true)

        // Sanity check: must look like a qWDTT config (has password, or hashes, or explicit qwdtt markers)
        val isQwdttLikely = pass.isNotBlank() || hashes.isNotBlank() ||
            obj.containsKey("workersPerHash") || obj.containsKey("vkHashes") || obj.containsKey("listenPort")
        if (!isQwdttLikely) return null

        return QwdttProfile(
            name = name,
            peer = peerHost,
            dtlsPort = peerPort,
            hashes = hashes,
            workers = workers,
            listenPort = listenPort,
            password = pass,
            rawMode = rawMode,
        )
    }
}
