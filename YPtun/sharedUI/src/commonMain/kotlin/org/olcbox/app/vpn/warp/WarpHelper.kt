package org.olcbox.app.vpn.warp

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.olcbox.app.data.model.ProxyProfile

/**
 * AmneziaWG-on-the-server relay. awgproxy (a userspace engine outside the proxy core) dials UDP
 * itself, so to make its WireGuard packets travel INSIDE the main proxy the core listens on
 * `127.0.0.1:[listenPort]` (UDP) and forwards whatever arrives to the real WARP endpoint
 * ([targetHost]:[targetPort]) through the main proxy. AWG's Endpoint is rewritten to that loopback.
 */
data class WarpRelay(val listenPort: Int, val targetHost: String, val targetPort: Int)

const val WARP_I1 =
        "<b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986ae0b728" +
        "0135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393f9f0fa43f11e510e" +
        "cb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b043121c5710ec289f471c91ee41" +
        "4fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4f01b472362b8056c3172b513051f8322d1" +
        "062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1bf9b0607c32ec4b3203a4ee21ab64df336212b97582" +
        "80803fcab14933b0e7ee1e04a7becce3e2633f4852585c567894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554" +
        "549bef9256111b2d67afde0b496f16962d4957ff654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa" +
        "8da5f73588e82e9470f3b21b27b28c649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad" +
        "9b138ca07d4a4a1650a2c2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3" +
        "a8d7a5291326e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb07" +
        "3ce65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68ab633f" +
        "f7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12ee3b1a93f30d8ab9" +
        "537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4b8e93047371175b1d6d9cc8a" +
        "b745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7c369e031d2041e152040920ac2c5ab534" +
        "0bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee12af583383d994e22c0cf42383834bcd3ada1b3825" +
        "a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2d" +
        "cf9348579a57d8d3490b2fa99f278f1c37d87dad9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865" +
        "f4e155e9fe020279f434f3bf1bd117b717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d13" +
        "0347594f13e9e07514fa370754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c" +
        "61f2989dd44f0cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f7" +
        "16738dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d87e3a8" +
        "b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a3af9927136b441" +
        "9e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3befe745fb7d634f70386d87" +
        "3b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080724e1ab5d5f43074ecb53a479af21ed0" +
        "3d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfbad06f5f83f7ad0429fa9a2719c56ffe3783460e166" +
        "de2d8>"
object WarpHelper {

    private const val DEFAULT_ENDPOINT = "162.159.192.1:894"
    private const val WARP_ENDPOINT_IP = "162.159.192.1"

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)

    /** `host` / `port` of the INI's `Endpoint =` line (WARP default when absent). */
    fun endpointOf(ini: String): Pair<String, Int> {
        val line = ini.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
        val ep = line?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
        val colon = ep.lastIndexOf(':')
        return if (colon > 0) {
            ep.substring(0, colon).removePrefix("[").removeSuffix("]") to (ep.substring(colon + 1).toIntOrNull() ?: 894)
        } else ep to 894
    }

    private val OBFUSCATION_KEYS = setOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime",
    )

    private fun dropKeys(ini: String, keys: Set<String>): String =
        ini.lineSequence().filterNot { line ->
            val eq = line.indexOf('=')
            eq > 0 && line.substring(0, eq).trim().lowercase() in keys
        }.joinToString("\n")

    /** The INI as PLAIN WireGuard: every AmneziaWG obfuscation knob (and `Reserved`) dropped. */
    fun plainWireGuard(ini: String): String = dropKeys(ini, OBFUSCATION_KEYS + "reserved")

    /**
     * The INI as the awgproxy engine should run it (measured live against Cloudflare):
     *  - `Reserved` is always dropped: awgproxy stamps those bytes AFTER the handshake MAC, so Cloudflare
     *    discards every handshake of an officially-registered account (never connects). Free WARP needs none.
     *  - AmneziaWG keeps the obfuscation knobs (Jc/Jmin/Jmax, S1/S2, H1..H4) but drops I1..I5: Cloudflare
     *    WARP is a standard WireGuard endpoint; sending I1 fake init packets causes Cloudflare to drop the handshake.
     *  - the endpoint is pinned to WARP's anycast IP: a domain is resolved at start-up by Go's resolver,
     *    which on Android has no resolv.conf (and, with the VPN already up, would loop into our own tunnel).
     */
    fun engineIni(ini: String, awg: Boolean, initPackets: Boolean = true): String {
        val base = if (awg) {
            val stripped = dropKeys(ini, setOf("i1", "i2", "i3", "i4", "i5"))
            if (initPackets) injectI1(stripped) else stripped
        } else {
            plainWireGuard(ini)
        }
        val (host, rawPort) = endpointOf(ini)
        val port = if (rawPort == 2408) 894 else rawPort
        val withEp = withEndpoint(base, "${pinnedHost(host)}:$port")
        return forceKeepalive(withEp, 15)
    }

    fun injectI1(ini: String): String {
        val lines = ini.lines().toMutableList()
        val ifaceIdx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        val insertIdx = if (ifaceIdx >= 0) ifaceIdx + 1 else 0
        lines.add(insertIdx, "I1 = $WARP_I1")
        return lines.joinToString("\n")
    }

    private fun forceKeepalive(config: String, seconds: Int): String {
        if (config.contains("PersistentKeepalive", ignoreCase = true)) {
            return config.lineSequence().joinToString("\n") {
                if (it.trimStart().startsWith("PersistentKeepalive", ignoreCase = true)) {
                    "PersistentKeepalive = $seconds"
                } else it
            }
        }
        return if (config.contains("[Peer]")) {
            config.replace("[Peer]", "[Peer]\nPersistentKeepalive = $seconds")
        } else {
            "$config\nPersistentKeepalive = $seconds"
        }
    }

    /** [host] unchanged when it is an IP literal, else WARP's anycast IP. */
    fun pinnedHost(host: String): String = if (isIpLiteral(host)) host else WARP_ENDPOINT_IP

    /** The same INI with its `Endpoint =` line pointed at [hostPort]. */
    fun withEndpoint(ini: String, hostPort: String): String =
        ini.lineSequence().joinToString("\n") { line ->
            if (line.trim().startsWith("Endpoint", ignoreCase = true) && line.contains('=')) "Endpoint = $hostPort" else line
        }

    /**
     * Parses a Cloudflare WARP WireGuard / AmneziaWG INI configuration into a [ProxyProfile]
     * with type="wireguard" and sing-box format rawOutbound.
     */
    fun parseWarpIniToProxyProfile(ini: String, tag: String = "WARP"): ProxyProfile? {
        if (ini.isBlank()) return null

        var privateKey: String? = null
        var addresses = mutableListOf<String>()
        var mtu: Int? = null

        var peerPublicKey: String? = null
        var endpoint: String? = null
        var keepalive: Int? = 25

        for (rawLine in ini.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "privatekey" -> privateKey = value
                "publickey" -> peerPublicKey = value
                "address" -> {
                    // May be comma-separated list of IPv4 and IPv6 CIDRs
                    value.split(',').forEach { addr ->
                        val trimmed = addr.trim()
                        if (trimmed.isNotEmpty()) addresses.add(trimmed)
                    }
                }
                "endpoint" -> endpoint = value
                "mtu" -> mtu = value.toIntOrNull()
                "persistentkeepalive" -> keepalive = value.toIntOrNull() ?: keepalive
            }
        }

        val priv = privateKey?.takeIf { it.isNotBlank() } ?: return null
        val peerPub = peerPublicKey?.takeIf { it.isNotBlank() } ?: return null

        // Default endpoint if missing or unparsed
        val ep = endpoint?.takeIf { it.isNotBlank() } ?: "engage.cloudflareclient.com:2408"
        val lastColon = ep.lastIndexOf(':')
        var host: String
        val port: Int
        if (lastColon > 0) {
            host = ep.substring(0, lastColon).removePrefix("[").removeSuffix("]")
            port = ep.substring(lastColon + 1).toIntOrNull() ?: 2408
        } else {
            host = ep
            port = 2408
        }
        // Never hand the cores a DOMAIN endpoint: xray resolves it with its own DNS, whose queries are routed
        // to the proxy — which here is the very WireGuard outbound that is waiting for this lookup (deadlock:
        // "failed to set endpoint engage.cloudflareclient.com … record not found"). WARP's anycast IP is stable.
        host = pinnedHost(host)

        val rawOutbound = buildJsonObject {
            put("type", "wireguard")
            put("server", host)
            put("server_port", port)
            putJsonArray("local_address") {
                addresses.forEach { add(it) }
            }
            put("private_key", priv)
            put("peer_public_key", peerPub)
            if (mtu != null && mtu > 0) put("mtu", mtu)
            if (keepalive != null && keepalive > 0) put("persistent_keepalive", keepalive)
        }.toString()

        return ProxyProfile(
            tag = tag,
            type = "wireguard",
            server = host,
            serverPort = port,
            rawOutbound = rawOutbound
        )
    }
}
