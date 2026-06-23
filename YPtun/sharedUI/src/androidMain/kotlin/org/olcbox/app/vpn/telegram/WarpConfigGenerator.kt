package org.olcbox.app.vpn.telegram

import android.util.Log
import awg.Awg
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates a Cloudflare WARP AmneziaWG config for the Telegram-over-WARP proxy.
 *
 * PRIMARY path mirrors warp-generator.github.io ("for AmneziaWG"): instead of registering directly
 * with `api.cloudflareclient.com` (which is BLOCKED in some regions — the user's case), it GETs a
 * ready WARP account from one of several SERVER-SIDE generators (Cloudflare Workers / Netlify /
 * Vercel). Those servers do the Cloudflare registration on their side and return JSON
 * {privKey, peer_pub, client_ipv4, client_ipv6}, so the device never touches the blocked endpoint.
 * If every generator is unreachable we FALL BACK to direct Cloudflare registration (works where it
 * isn't blocked), so the feature still functions for everyone.
 *
 * IMPORTANT: Cloudflare WARP is PLAIN WireGuard — the obfuscation params keep the handshake
 * byte-identical to vanilla WireGuard (S1=S2=0, H1..H4 = the default message types 1..4) and only add
 * junk COVER packets (Jc/Jmin/Jmax) for DPI resistance. The generator's accounts carry NO reserved
 * bytes, so we don't emit `Reserved` for them (plain WG, exactly as the generator outputs). DNS is
 * 1.1.1.1 and AllowedIPs is the full tunnel; the "Telegram only" split is done in the SOCKS layer
 * (awg.Instance.SetSplitCIDRs).
 */
object WarpConfigGenerator {

    /** Standard WARP data-plane endpoint. */
    private const val ENDPOINT = "engage.cloudflareclient.com:2408"

    /** Direct Cloudflare registration (fallback only — blocked in some regions). */
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CF_CLIENT_VERSION = "a-6.3-2158"
    private const val CF_USER_AGENT = "okhttp/3.12.1"

    /**
     * Server-side WARP generators that register with Cloudflare for us and return a ready account.
     * Same list warp-generator.github.io uses; tried in order until one answers. Keeping several
     * means the feature survives any single generator being down or blocked.
     */
    private val SERVICE_ENDPOINTS = listOf(
        "https://www.warp-generator.workers.dev",
        "https://warp.sub-aggregator.workers.dev",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
        "https://warp-gen.netlify.app/",
    )

    /** Full tunnel: every connection the local SOCKS client (Telegram) makes rides WARP. */
    private const val ALLOWED_IPS = "0.0.0.0/0, ::/0"

    private const val TAG = "WarpConfigGen"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns a ready-to-use AmneziaWG INI config. Tries the server-side generators first (bypasses a
     * blocked Cloudflare API), then falls back to direct registration. Requires internet (first-time
     * only — the caller caches the result). Throws only if BOTH paths fail.
     */
    suspend fun generate(): String {
        generateViaService()?.let { return it }
        Log.w(TAG, "All WARP generators failed — falling back to direct Cloudflare registration")
        return generateViaCloudflare()
    }

    /** Fetches a pre-registered WARP account from the server-side generators. Null if all fail. */
    private suspend fun generateViaService(): String? {
        val client = HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
        }
        try {
            for (url in SERVICE_ENDPOINTS) {
                val account = runCatching {
                    // These generators 500 on non-browser User-Agents (e.g. Ktor's default), so present
                    // a normal browser UA — the same kind warp-generator.github.io sends.
                    val response = client.get(url) {
                        headers { append(HttpHeaders.UserAgent, BROWSER_USER_AGENT) }
                    }
                    if (response.status.value !in 200..299) return@runCatching null
                    json.decodeFromString(WarpServiceAccount.serializer(), response.bodyAsText())
                }.onFailure { Log.w(TAG, "WARP generator $url failed: ${it.message}") }.getOrNull()

                val priv = account?.privKey?.takeIf { it.isNotBlank() }
                val peer = account?.peerPub?.takeIf { it.isNotBlank() }
                val v4 = account?.clientIpv4?.takeIf { it.isNotBlank() }
                if (priv != null && peer != null && v4 != null) {
                    Log.i(TAG, "WARP config obtained from $url")
                    // Service accounts are plain WG (no client_id/reserved).
                    return buildIni(priv, peer, v4, account.clientIpv6?.takeIf { it.isNotBlank() }, reserved = null)
                }
            }
        } finally {
            client.close()
        }
        return null
    }

    /** Direct Cloudflare registration — fallback for regions where the API isn't blocked. */
    private suspend fun generateViaCloudflare(): String {
        val keyPair = Awg.generateKeyPair()
        val parts = keyPair.split("|")
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "Failed to generate WireGuard keypair"
        }
        val privateKey = parts[0]
        val publicKey = parts[1]

        val client = HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }
        val body: JsonObject = buildJsonObject {
            put("key", publicKey)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", isoNow())
            put("model", "PC")
            put("type", "Android")
            put("locale", "en_US")
        }

        val responseText = try {
            val response = client.post(REG_URL) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.UserAgent, CF_USER_AGENT)
                    append("CF-Client-Version", CF_CLIENT_VERSION)
                }
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            if (response.status.value !in 200..299) {
                throw IllegalStateException("Cloudflare WARP registration failed (HTTP ${response.status.value})")
            }
            response.bodyAsText()
        } finally {
            client.close()
        }

        val reg = json.decodeFromString(WarpRegistration.serializer(), responseText)
        val peer = reg.config?.peers?.firstOrNull()
            ?: throw IllegalStateException("Cloudflare WARP response missing peer")
        val peerPublic = peer.publicKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Cloudflare WARP response missing peer public key")
        val addresses = reg.config.networkInterface?.addresses
            ?: throw IllegalStateException("Cloudflare WARP response missing interface addresses")
        val v4 = addresses.v4?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Cloudflare WARP response missing IPv4 address")
        val v6 = addresses.v6?.takeIf { it.isNotBlank() }
        val reserved = reservedFromClientId(reg.config.clientId)

        return buildIni(privateKey, peerPublic, v4, v6, reserved)
    }

    /** Decodes the WARP client_id (base64, 3 bytes) into the "b0, b1, b2" reserved-bytes string. */
    private fun reservedFromClientId(clientId: String?): String? {
        val id = clientId?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val bytes = android.util.Base64.decode(id, android.util.Base64.DEFAULT)
            if (bytes.size < 3) return null
            "${bytes[0].toInt() and 0xFF}, ${bytes[1].toInt() and 0xFF}, ${bytes[2].toInt() and 0xFF}"
        }.getOrNull()
    }

    private fun buildIni(
        privateKey: String,
        peerPublic: String,
        v4: String,
        v6: String?,
        reserved: String?
    ): String {
        val address = if (v6 != null) "$v4/32, $v6/128" else "$v4/32"
        // AmneziaWG knobs for a PLAIN-WireGuard server (WARP): only junk cover packets; handshake
        // stays vanilla (S1=S2=0, H1..H4 = 1..4) so Cloudflare accepts it. Matches the warp-generator
        // "AmneziaWG" preset exactly.
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            appendLine("DNS = 1.1.1.1")
            appendLine("MTU = 1280")
            if (reserved != null) appendLine("Reserved = $reserved")
            appendLine("Jc = 4")
            appendLine("Jmin = 40")
            appendLine("Jmax = 70")
            appendLine("S1 = 0")
            appendLine("S2 = 0")
            appendLine("H1 = 1")
            appendLine("H2 = 2")
            appendLine("H3 = 3")
            appendLine("H4 = 4")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublic")
            appendLine("AllowedIPs = $ALLOWED_IPS")
            appendLine("Endpoint = $ENDPOINT")
            appendLine("PersistentKeepalive = 25")
        }
    }

    /** Minimal ISO-8601 UTC timestamp for the `tos` field, without pulling in extra deps. */
    private fun isoNow(): String {
        val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d.000Z",
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            now.get(java.util.Calendar.SECOND),
        )
    }

    /** Server-side generator response: a ready WARP account (the device's own keys + addresses). */
    @Serializable
    private data class WarpServiceAccount(
        @SerialName("privKey") val privKey: String? = null,
        @SerialName("peer_pub") val peerPub: String? = null,
        @SerialName("client_ipv4") val clientIpv4: String? = null,
        @SerialName("client_ipv6") val clientIpv6: String? = null,
    )

    @Serializable
    private data class WarpRegistration(val config: Config? = null) {
        @Serializable
        data class Config(
            val peers: List<Peer>? = null,
            @SerialName("interface") val networkInterface: Interface? = null,
            @SerialName("client_id") val clientId: String? = null,
        )

        @Serializable
        data class Peer(@SerialName("public_key") val publicKey: String? = null)

        @Serializable
        data class Interface(val addresses: Addresses? = null)

        @Serializable
        data class Addresses(val v4: String? = null, val v6: String? = null)
    }
}
