package org.olcbox.app.vpn.telegram

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
import org.olcbox.app.vpn.desktop.YpTunCore

/**
 * Desktop copy of the androidMain generator of the same name (separate source sets, so no shared
 * file): produces a Cloudflare WARP AmneziaWG config for the Telegram-over-WARP proxy.
 *
 * PRIMARY path mirrors warp-generator.github.io ("for AmneziaWG"): instead of registering directly
 * with `api.cloudflareclient.com` (which is BLOCKED in some regions), it GETs a ready WARP account
 * from one of several SERVER-SIDE generators. Those servers do the Cloudflare registration on their
 * side and return {privKey, peer_pub, client_ipv4, client_ipv6}, so the machine never touches the
 * blocked endpoint. If every generator is unreachable we FALL BACK to direct registration.
 *
 * IMPORTANT: Cloudflare WARP is PLAIN WireGuard — the obfuscation params keep the handshake
 * byte-identical to vanilla WireGuard (S1=S2=0, H1..H4 = 1..4) and add junk COVER packets
 * (Jc/Jmin/Jmax) plus the [WARP_I1] DPI-evasion init packet. The I1 is the critical bit on DPI
 * networks: without it the handshake completes but the ISP kills the transport stream, so WARP
 * "connects but moves no data".
 */
internal object WarpConfigGenerator {

    /** Standard WARP data-plane endpoint. */
    private const val ENDPOINT = "engage.cloudflareclient.com:2408"

    /** Direct Cloudflare registration (fallback only — blocked in some regions). */
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CF_CLIENT_VERSION = "a-6.3-2158"
    private const val CF_USER_AGENT = "okhttp/3.12.1"

    /**
     * Server-side WARP generators that register with Cloudflare for us and return a ready account.
     * Tried in order until one answers; several means the feature survives any single one being down.
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

    /**
     * AmneziaWG I1 init packet (DPI evasion) — the Amnezia "WARP" preset's obfuscation packet. Sent
     * BEFORE the WireGuard handshake so the ISP's DPI classifies the flow as allowed. It is
     * independent of the account keys and carries no secret, so it is safe to bundle and reuse for
     * every generated account. WITHOUT it the handshake completes but DPI networks silently drop all
     * transport data (handshake OK -> "stopped hearing back" -> every DC connect times out).
     */
    private const val WARP_I1 =
        "<b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986" +
            "ae0b7280135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393" +
            "f9f0fa43f11e510ecb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b04" +
            "3121c5710ec289f471c91ee414fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4" +
            "f01b472362b8056c3172b513051f8322d1062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1" +
            "bf9b0607c32ec4b3203a4ee21ab64df336212b9758280803fcab14933b0e7ee1e04a7becce3e2633f4852585c56" +
            "7894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554549bef9256111b2d67afde0b496f16962d4957f" +
            "f654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa8da5f73588e82e9470f3b21b27b28c" +
            "649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad9b138ca07d4a4a1650a2c" +
            "2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3a8d7a5291326" +
            "e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb073ce" +
            "65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68" +
            "ab633ff7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12e" +
            "e3b1a93f30d8ab9537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4" +
            "b8e93047371175b1d6d9cc8ab745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7" +
            "c369e031d2041e152040920ac2c5ab5340bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee" +
            "12af583383d994e22c0cf42383834bcd3ada1b3825a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99" +
            "c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2dcf9348579a57d8d3490b2fa99f278f1c37d87dad" +
            "9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865f4e155e9fe020279f434f3bf1bd117b" +
            "717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d130347594f13e9e07514fa37" +
            "0754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c61f2989dd44f0" +
            "cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f71673" +
            "8dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d8" +
            "7e3a8b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a" +
            "3af9927136b4419e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3" +
            "befe745fb7d634f70386d873b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080" +
            "724e1ab5d5f43074ecb53a479af21ed03d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfba" +
            "d06f5f83f7ad0429fa9a2719c56ffe3783460e166de2d8>"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns a ready-to-use AmneziaWG INI config. Tries the server-side generators first (bypasses a
     * blocked Cloudflare API), then falls back to direct registration. Requires internet (first time
     * only — the caller caches the result). Throws only if BOTH paths fail.
     */
    suspend fun generate(log: (String) -> Unit = {}): String {
        generateViaService(log)?.let { return it }
        log("WARP: all generators failed — falling back to direct Cloudflare registration")
        return generateViaCloudflare()
    }

    /** Fetches a pre-registered WARP account from the server-side generators. Null if all fail. */
    private suspend fun generateViaService(log: (String) -> Unit): String? {
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
                    // These generators 500 on non-browser User-Agents, so present a normal browser UA.
                    val response = client.get(url) {
                        headers { append(HttpHeaders.UserAgent, BROWSER_USER_AGENT) }
                    }
                    if (response.status.value !in 200..299) return@runCatching null
                    json.decodeFromString(WarpServiceAccount.serializer(), response.bodyAsText())
                }.onFailure { log("WARP generator $url failed: ${it.message}") }.getOrNull()

                val priv = account?.privKey?.takeIf { it.isNotBlank() }
                val peer = account?.peerPub?.takeIf { it.isNotBlank() }
                val v4 = account?.clientIpv4?.takeIf { it.isNotBlank() }
                if (priv != null && peer != null && v4 != null) {
                    log("WARP: config obtained from $url")
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
        val keyPair = YpTunCore.awgGenerateKeyPair()
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
            val bytes = java.util.Base64.getDecoder().decode(id)
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
        // AmneziaWG knobs for a PLAIN-WireGuard server (WARP): only junk cover packets; the handshake
        // stays vanilla (S1=S2=0, H1..H4 = 1..4) so Cloudflare accepts it.
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
            // DPI-evasion init packet — see WARP_I1.
            appendLine("I1 = $WARP_I1")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublic")
            appendLine("AllowedIPs = $ALLOWED_IPS")
            appendLine("Endpoint = $ENDPOINT")
            appendLine("PersistentKeepalive = 25")
        }
    }

    /** Minimal ISO-8601 UTC timestamp for the `tos` field. */
    private fun isoNow(): String = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.000'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.now())

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
