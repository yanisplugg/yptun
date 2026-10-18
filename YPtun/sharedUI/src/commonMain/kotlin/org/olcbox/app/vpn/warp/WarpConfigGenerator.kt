package org.olcbox.app.vpn.warp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.olcbox.app.data.datasource.createProxyHttpClient

internal object WarpConfigGenerator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private val SERVICE_ENDPOINTS = listOf(
        "https://www.warp-generator.workers.dev",
        "https://warp.sub-aggregator.workers.dev",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
        "https://warp-gen.netlify.app/",
        "https://breead1337.github.io/ws-cache-9e41/w.json",
    )

    @Serializable
    private data class WarpServiceAccount(
        val privKey: String? = null,
        val peerPub: String? = null,
        val clientIpv4: String? = null,
        val clientIpv6: String? = null,
    )

    private fun parseAccount(text: String): WarpServiceAccount =
        if (text.trimStart().startsWith("[")) {
            json.decodeFromString(ListSerializer(WarpServiceAccount.serializer()), text).random()
        } else {
            json.decodeFromString(WarpServiceAccount.serializer(), text)
        }

    /**
     * Generates or fetches a Cloudflare WARP account INI config.
     */
    suspend fun generate(log: (String) -> Unit = {}): String {
        val client = createProxyHttpClient(
            subscriptionProxy = null,
            connectTimeoutMs = 6_000,
            requestTimeoutMs = 8_000,
            socketTimeoutMs = 8_000,
        )
        try {
            for (url in SERVICE_ENDPOINTS) {
                val account = runCatching {
                    val response = client.get(url) {
                        headers { append(HttpHeaders.UserAgent, BROWSER_USER_AGENT) }
                    }
                    if (response.status.value !in 200..299) return@runCatching null
                    parseAccount(response.bodyAsText())
                }.onFailure { log("WARP generator $url failed: ${it.message}") }.getOrNull()

                val priv = account?.privKey?.takeIf { it.isNotBlank() }
                val peer = account?.peerPub?.takeIf { it.isNotBlank() }
                val v4 = account?.clientIpv4?.takeIf { it.isNotBlank() }
                if (priv != null && peer != null && v4 != null) {
                    log("WARP: config obtained from $url")
                    return buildIni(priv, peer, v4, account.clientIpv6?.takeIf { it.isNotBlank() })
                }
            }
        } finally {
            runCatching { client.close() }
        }
        throw IllegalStateException("Failed to obtain Cloudflare WARP configuration from all endpoints")
    }

    private fun buildIni(
        privateKey: String,
        peerPublicKey: String,
        clientIpv4: String,
        clientIpv6: String?,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        val addresses = if (clientIpv6.isNullOrBlank()) clientIpv4 else "$clientIpv4, $clientIpv6"
        appendLine("Address = $addresses")
        appendLine("DNS = 1.1.1.1, 2606:4700:4700::1111")
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
        appendLine("PublicKey = $peerPublicKey")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = 162.159.192.1:2408")
    }
}
