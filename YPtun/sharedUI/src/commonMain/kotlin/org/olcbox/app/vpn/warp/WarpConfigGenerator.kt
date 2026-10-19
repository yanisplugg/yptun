package org.olcbox.app.vpn.warp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
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
        "https://breead1337.github.io/ws-cache-9e41/w.json",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
    )

    @Serializable
    private data class WarpServiceAccount(
        @SerialName("privKey") val privKey: String? = null,
        @SerialName("peer_pub") val peerPubSnake: String? = null,
        @SerialName("peerPub") val peerPubCamel: String? = null,
        @SerialName("peer_public_key") val peerPubLong: String? = null,
        @SerialName("client_ipv4") val clientIpv4Snake: String? = null,
        @SerialName("clientIpv4") val clientIpv4Camel: String? = null,
        @SerialName("client_ipv6") val clientIpv6Snake: String? = null,
        @SerialName("clientIpv6") val clientIpv6Camel: String? = null,
    ) {
        val peerPub: String? get() = peerPubSnake ?: peerPubCamel ?: peerPubLong
        val clientIpv4: String? get() = clientIpv4Snake ?: clientIpv4Camel
        val clientIpv6: String? get() = clientIpv6Snake ?: clientIpv6Camel
    }

    private val EMBEDDED_ACCOUNTS = listOf(
        WarpServiceAccount(
            privKey = "wQZxT1GGSses/f3A6Gi5ScD+p2OJdLNMi5HXXPXw1rE=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:8a14:aea8:32bc:7bfe:d44c"
        ),
        WarpServiceAccount(
            privKey = "TqyqWhFeOp6j6FvbvQALnwwpOYU6ZUDW8UJgIIvpHXQ=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:898b:f716:b6ef:327:9c25"
        ),
        WarpServiceAccount(
            privKey = "cUt60Njwbf22FaoiBL3lSUKzyUBS5WzbVm8k87ENmrc=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:876b:ca30:2409:6bda:24e5"
        ),
        WarpServiceAccount(
            privKey = "N2CiyBgHUA5JXClrfv3YfAEWChIGMv5NK82vnKNEr0o=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:850f:728c:71ff:b52a:de50"
        ),
        WarpServiceAccount(
            privKey = "jhe7v2O1gc2PyNHtf3l/+4xNZoYza2vEjAB2EH4jCx8=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:80e2:c8b8:7359:4bf6:933d"
        ),
        WarpServiceAccount(
            privKey = "DOSETgGERmbsUmgmfwZDeLlX/XKqLvkNAENuEkA+fyA=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:8e08:d29f:d79b:6699:84d8"
        ),
        WarpServiceAccount(
            privKey = "9JK9tn8jhfwRUA/6dIzF1NLfjBWvlE1dNzkhug4CaQg=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:86df:d4de:b297:6609:8523"
        ),
        WarpServiceAccount(
            privKey = "WgNE90ZMBj1PllQY1A9XKTJMDNt4tp4S+KupIuAij1A=",
            peerPubSnake = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
            clientIpv4Snake = "172.16.0.2",
            clientIpv6Snake = "2606:4700:110:86b9:f5db:cc97:46cc:459"
        ),
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
            connectTimeoutMs = 3_000,
            requestTimeoutMs = 4_000,
            socketTimeoutMs = 4_000,
        )
        try {
            for (url in SERVICE_ENDPOINTS) {
                val account = runCatching {
                    val response = client.get(url) {
                        headers { append(HttpHeaders.UserAgent, BROWSER_USER_AGENT) }
                    }
                    if (response.status.value !in 200..299) {
                        log("WARP generator $url returned HTTP ${response.status.value}")
                        return@runCatching null
                    }
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

        log("WARP: network generators unreachable, using embedded WARP profile")
        val fallback = EMBEDDED_ACCOUNTS.random()
        return buildIni(
            fallback.privKey!!,
            fallback.peerPub!!,
            fallback.clientIpv4!!,
            fallback.clientIpv6
        )
    }

    private fun buildIni(
        privateKey: String,
        peerPublicKey: String,
        clientIpv4: String,
        clientIpv6: String?,
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        val v4 = if (clientIpv4.contains('/')) clientIpv4 else "$clientIpv4/32"
        val v6 = clientIpv6?.takeIf { it.isNotBlank() }?.let { if (it.contains('/')) it else "$it/128" }
        val addresses = if (v6 != null) "$v4, $v6" else v4
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
