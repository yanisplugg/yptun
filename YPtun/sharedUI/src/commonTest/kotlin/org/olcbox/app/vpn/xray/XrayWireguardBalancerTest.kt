package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XrayWireguardBalancerTest {
    private fun wireguard(port: Int, suffix: Int) = ProxyProfile(
        tag = "wg-$suffix",
        type = "wireguard",
        rawOutbound = """
            {
              "type":"wireguard",
              "server":"127.0.0.1",
              "server_port":$port,
              "local_address":["10.7.$suffix.2/32"],
              "private_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
              "peer_public_key":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
              "mtu":1200
            }
        """.trimIndent(),
    )

    @Test
    fun multiServerUsesDeterministicRoundRobinWithoutProbeGate() {
        val root = Json.parseToJsonElement(
            XrayConfig.buildWireguardBalancer(
                wgProfiles = listOf(wireguard(9101, 1), wireguard(9102, 2)),
                listenPort = 10808,
                traffic = TrafficSettings(),
            )
        ).jsonObject

        val tags = root["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("wg-bal-0", "wg-bal-1", "direct", "block"), tags)
        val balancer = root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals("vk-bal", balancer["tag"]!!.jsonPrimitive.content)
        assertEquals("wg-bal-0", balancer["fallbackTag"]!!.jsonPrimitive.content)
        assertEquals("roundRobin", balancer["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(root["burstObservatory"], "HTTP probe failures must not pin all traffic to the fallback")
        val catchAll = root["routing"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject
        assertEquals("vk-bal", catchAll["balancerTag"]!!.jsonPrimitive.content)
    }
}
