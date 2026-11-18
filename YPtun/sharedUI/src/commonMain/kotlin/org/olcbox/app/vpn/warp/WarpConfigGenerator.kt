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
        "https://www.warp-generator.workers.dev",
        "https://warp.sub-aggregator.workers.dev",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
        "https://warp-gen.netlify.app/",
    )

    /**
     * AmneziaWG I1 init packet (DPI evasion). Sent before the WireGuard handshake so ISP DPI
     * classifies the UDP flow as allowed instead of dropping all post-handshake transport packets.
     */
    const val WARP_I1 =
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

    /**
     * Upgrades previously persisted warp.conf files that lack the DPI-evasion I1 init packet,
     * MTU = 1280, or have an IPv6 DNS server that stalls UDP lookups inside userspace netstack.
     */
    fun upgradeIfNeeded(ini: String): String {
        if (ini.isBlank()) return ini
        val lines = ini.lines().toMutableList()
        var hasI1 = false
        var hasMtu = false
        var hasDns = false
        var peerIdx = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals("[Peer]", ignoreCase = true) && peerIdx < 0) {
                peerIdx = i
            }
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim().lowercase()
                when (key) {
                    "i1" -> hasI1 = true
                    "mtu" -> hasMtu = true
                    "dns" -> {
                        hasDns = true
                        if (trimmed.contains(':')) {
                            lines[i] = "DNS = 1.1.1.1"
                        }
                    }
                }
            }
        }
        val insertAt = if (peerIdx >= 0) peerIdx else lines.size
        val toInsert = mutableListOf<String>()
        if (!hasDns) toInsert += "DNS = 1.1.1.1"
        if (!hasMtu) toInsert += "MTU = 1280"
        if (!hasI1) toInsert += "I1 = $WARP_I1"
        if (toInsert.isNotEmpty()) {
            lines.addAll(insertAt, toInsert)
        }
        return lines.joinToString("\n")
    }

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
        appendLine("I1 = $WARP_I1")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKey")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = 162.159.192.1:2408")
    }
}
