package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * sing-box 1.14 REMOVED the legacy `{"address": "tls://1.1.1.1"}` DNS server form (hard config
 * error, not a warning) and refuses a `detour` on the `direct` outbound. Both shapes were what the
 * app emitted on 1.13, so a regression here means the core refuses to start at all — which is
 * invisible until someone presses connect.
 */
class SingBoxDnsFormatTest {

    private val profile = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS,
        server = "vbn.azz.su",
        serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP,
        security = ProxyProfile.SECURITY_TLS,
        sni = "vbn.azz.su",
    )

    private fun build(traffic: TrafficSettings = TrafficSettings(), olcrtcPort: Int? = null) =
        Json.parseToJsonElement(
            SingBoxConfig.build(
                profile = profile,
                listenPort = 10808,
                traffic = traffic,
                olcrtcChainPort = olcrtcPort,
                directViaBase = olcrtcPort != null,
            )
        ).jsonObject

    @Test
    fun everyDnsServerIsTyped() {
        val servers = build(
            TrafficSettings(
                remoteDns = "https://dns.google/dns-query",
                remoteDns2 = "tls://1.1.1.1",
                directDns = "8.8.8.8:5353",
            )
        )["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

        assertTrue(servers.none { it.containsKey("address") }, "legacy `address` form is a fatal error on 1.14")
        assertTrue(servers.all { it.containsKey("type") })

        val byTag = servers.associateBy { it["tag"]!!.jsonPrimitive.content }
        assertEquals("https", byTag["remote"]!!["type"]!!.jsonPrimitive.content)
        assertEquals("dns.google", byTag["remote"]!!["server"]!!.jsonPrimitive.content)
        assertEquals("tls", byTag["remote2"]!!["type"]!!.jsonPrimitive.content)
        assertEquals("udp", byTag["direct"]!!["type"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", byTag["direct"]!!["server"]!!.jsonPrimitive.content)
        assertEquals(5353, byTag["direct"]!!["server_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun directOutboundNeverCarriesADetour() {
        val root = build(olcrtcPort = 10811)
        val direct = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "direct" }
        assertNull(direct["detour"], "`detour` is not supported in direct context (sing-box 1.14)")
        // …and the never-bypass tunnel still keeps its "direct" bucket inside the tunnel, by tag.
        val privateRule = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
            .first { it["ip_is_private"] != null }
        assertEquals("olcrtc-out", privateRule["outbound"]!!.jsonPrimitive.content)
    }

    /**
     * The plain shape (no base tunnel) pointed the bootstrap resolver at the bare `direct` outbound.
     * 1.14 refuses to start on that — "start dns/udp[direct]: detour to an empty direct outbound
     * makes no sense" — which took down every desktop engine at once, since Xray is fronted by a
     * sing-box TUN there.
     */
    @Test
    fun noDnsServerDetoursToTheBareDirectOutbound() {
        for (root in listOf(build(), build(olcrtcPort = 10811))) {
            val servers = root["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            assertTrue(
                servers.none { it["detour"]?.jsonPrimitive?.content == "direct" },
                "a DNS detour to the empty direct outbound is a fatal start error on 1.14",
            )
        }
        // The never-bypass shape must still keep its bootstrap lookups inside the tunnel.
        val chained = build(olcrtcPort = 10811)["dns"]!!.jsonObject["servers"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertEquals("olcrtc-out", chained["detour"]!!.jsonPrimitive.content)
    }
}
