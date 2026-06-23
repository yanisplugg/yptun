package org.olcbox.app.vpn.telegram

import awg.Awg
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
 * Generates a Cloudflare WARP AmneziaWG config for the Telegram-over-WARP proxy, mirroring
 * warp-generator.github.io / github.com/ImMALWARE/bash-warp-generator (both register a device with
 * `api.cloudflareclient.com`). The keypair is made in Go ([Awg.generateKeyPair]) for reliable X25519;
 * the registration + INI assembly are done here.
 *
 * IMPORTANT: Cloudflare WARP is PLAIN WireGuard, not AmneziaWG — so the obfuscation params keep the
 * handshake byte-identical to vanilla WireGuard (S1=S2=0, H1..H4 = the default message types 1..4) and
 * only add junk COVER packets (Jc/Jmin/Jmax) for DPI resistance. DNS is 1.1.1.1, the endpoint is the
 * standard one, and AllowedIPs is restricted to Telegram's ranges ("split tunnel Telegram"), so the
 * tunnel only ever carries Telegram traffic even though a dedicated local SOCKS already isolates it.
 */
object WarpConfigGenerator {

    /** Standard WARP endpoint ("Сервер стандартный"). */
    private const val ENDPOINT = "engage.cloudflareclient.com:2408"
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CF_CLIENT_VERSION = "a-6.3-2158"
    private const val CF_USER_AGENT = "okhttp/3.12.1"

    /**
     * Telegram DC IP ranges (AllowedIPs). Telegram's data-centres live entirely within these blocks,
     * so the WARP tunnel carries Telegram and nothing else. Update if Telegram publishes new ranges.
     */
    private val TELEGRAM_ALLOWED_IPS = listOf(
        "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22",
        "91.108.20.0/22", "91.108.56.0/22", "91.105.192.0/23", "91.108.58.0/23",
        "149.154.160.0/20", "149.154.164.0/22", "149.154.168.0/22", "149.154.172.0/22",
        "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48", "2001:b28:f23c::/48",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Registers a fresh WARP device with Cloudflare and returns a ready-to-use AmneziaWG INI config.
     * Requires internet (first-time only — the caller caches the result). Throws on any failure.
     */
    suspend fun generate(): String {
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

        return buildIni(privateKey, peerPublic, v4, v6)
    }

    private fun buildIni(privateKey: String, peerPublic: String, v4: String, v6: String?): String {
        val address = if (v6 != null) "$v4/32, $v6/128" else "$v4/32"
        // AmneziaWG knobs for a PLAIN-WireGuard server (WARP): only junk cover packets; handshake
        // stays vanilla (S1=S2=0, H1..H4 = 1..4) so Cloudflare accepts it.
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            appendLine("DNS = 1.1.1.1")
            appendLine("MTU = 1280")
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
            appendLine("AllowedIPs = ${TELEGRAM_ALLOWED_IPS.joinToString(", ")}")
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

    @Serializable
    private data class WarpRegistration(val config: Config? = null) {
        @Serializable
        data class Config(
            val peers: List<Peer>? = null,
            @SerialName("interface") val networkInterface: Interface? = null,
        )

        @Serializable
        data class Peer(@SerialName("public_key") val publicKey: String? = null)

        @Serializable
        data class Interface(val addresses: Addresses? = null)

        @Serializable
        data class Addresses(val v4: String? = null, val v6: String? = null)
    }
}
