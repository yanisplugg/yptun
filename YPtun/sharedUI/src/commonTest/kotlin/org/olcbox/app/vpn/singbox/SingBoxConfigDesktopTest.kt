package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks in the desktop-only sing-box config that made Windows connectivity work: the Hiddify-style
 * in-core TUN (mixed stack + MTU 9000), xudp on vless, and the SOCKS+HTTP "mixed" inbound used by
 * desktop proxy mode. A regression here is exactly what broke browsing before.
 */
class SingBoxConfigDesktopTest {

    private val visionProfile = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS,
        server = "vbn.azz.su",
        serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        flow = "xtls-rprx-vision",
        network = ProxyProfile.NETWORK_TCP,
        security = ProxyProfile.SECURITY_TLS,
        sni = "vbn.azz.su",
    )

    private fun build(tunMode: Boolean, mixedInbound: Boolean): Map<String, Any?> {
        val json = SingBoxConfig.build(
            profile = visionProfile,
            listenPort = 10808,
            listenHost = "127.0.0.1",
            socksUsername = "",
            socksPassword = "",
            tunMode = tunMode,
            mixedInbound = mixedInbound,
        )
        return Json.parseToJsonElement(json).jsonObject.let { root ->
            val inbounds = root["inbounds"]!!.jsonArray.map { it.jsonObject }
            val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
            mapOf(
                "tun" to inbounds.firstOrNull { it["type"]?.jsonPrimitive?.content == "tun" },
                "proxyInbound" to inbounds.first { it["tag"]?.jsonPrimitive?.content == "socks-in" },
                "proxyOutbound" to outbounds.first { it["type"]?.jsonPrimitive?.content == "vless" },
            )
        }
    }

    @Test
    fun desktopTunUsesMixedStackAndLargeMtu() {
        @Suppress("UNCHECKED_CAST")
        val tun = build(tunMode = true, mixedInbound = true)["tun"] as kotlinx.serialization.json.JsonObject?
        requireNotNull(tun) { "in-core TUN inbound must be present in tun mode" }
        assertEquals("mixed", tun["stack"]!!.jsonPrimitive.content)
        assertEquals(9000, tun["mtu"]!!.jsonPrimitive.int)
        assertEquals(true, tun["auto_route"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun vlessAlwaysCarriesXudpEvenWithVisionFlow() {
        @Suppress("UNCHECKED_CAST")
        val out = build(tunMode = true, mixedInbound = true)["proxyOutbound"]
            as kotlinx.serialization.json.JsonObject
        assertEquals("xtls-rprx-vision", out["flow"]!!.jsonPrimitive.content)
        assertEquals("xudp", out["packet_encoding"]!!.jsonPrimitive.content)
    }

    @Test
    fun mixedInboundEnablesHttpProxyForProxyMode() {
        @Suppress("UNCHECKED_CAST")
        val inbound = build(tunMode = false, mixedInbound = true)["proxyInbound"]
            as kotlinx.serialization.json.JsonObject
        assertEquals("mixed", inbound["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun nonDesktopKeepsPlainSocksInbound() {
        @Suppress("UNCHECKED_CAST")
        val inbound = build(tunMode = false, mixedInbound = false)["proxyInbound"]
            as kotlinx.serialization.json.JsonObject
        assertEquals("socks", inbound["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun tunOmittedWhenNotInTunMode() {
        assertTrue(build(tunMode = false, mixedInbound = true)["tun"] == null)
    }
}
