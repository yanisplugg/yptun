package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.xray.XrayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A routing profile's own DNS (the China preset) overrides the traffic settings; other profiles don't. */
class RoutingProfileDnsTest {

    private val proxy = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS, server = "vbn.azz.su", serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS, sni = "vbn.azz.su",
    )

    private fun singBoxServers(routing: RoutingProfile?): Map<String, String> =
        Json.parseToJsonElement(
            SingBoxConfig.build(
                profile = proxy, listenPort = 10808,
                traffic = TrafficSettings().normalized(), routingProfile = routing,
            )
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
            .associate { it["tag"]!!.jsonPrimitive.content to it["server"]!!.jsonPrimitive.content }

    @Test
    fun chinaPresetSetsRemoteViaVpnAndChineseDirect() {
        val servers = singBoxServers(RoutingProfile.chinaDirect())
        assertEquals("1.1.1.1", servers["remote"])
        assertEquals("223.5.5.5", servers["direct"])
    }

    @Test
    fun noProfileOrPlainProfileKeepsTheAppSettings() {
        val defaults = TrafficSettings().normalized()
        for (routing in listOf(null, RoutingProfile.russiaDirect())) {
            val servers = singBoxServers(routing)
            assertEquals(defaults.remoteDns, servers["remote"])
            assertEquals(defaults.directDns, servers["direct"])
        }
    }

    @Test
    fun xrayReadsTheSameFields() {
        val servers = Json.parseToJsonElement(
            XrayConfig.build(
                profile = proxy, listenPort = 10808,
                traffic = TrafficSettings().normalized(), routingProfile = RoutingProfile.chinaDirect(),
            )
        ).jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("https://1.1.1.1/dns-query", "223.5.5.5"), servers)
    }

    @Test
    fun happStyleDohAndEditorRoundTrip() {
        // Happ carries a DoH resolver as type=DoH + a domain; a bare host gets the standard path.
        val doh = RoutingProfile(remoteDnsType = "DoH", remoteDnsDomain = "dns.google")
        assertEquals("https://dns.google/dns-query", doh.remoteDnsAddress())
        assertEquals(null, RoutingProfile().remoteDnsAddress())
        // The editor writes a single address back; an unchanged one must not rewrite the stored fields.
        assertSame(doh, doh.withRemoteDns("https://dns.google/dns-query"))
        assertEquals("9.9.9.9", doh.withRemoteDns("9.9.9.9").remoteDnsAddress())
        assertEquals(null, doh.withRemoteDns("").remoteDnsAddress())
    }
}
