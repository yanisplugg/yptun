package org.olcbox.app.data.importer

import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The VK TURN path (DTLS + RTP/Opus obfuscation over the carrier's UDP) has a far smaller effective
 * MTU than a wg-quick config assumes, and an oversized MTU fails SILENTLY — small exchanges fit,
 * anything that fills a segment is dropped. So every VK-TURN WireGuard/AmneziaWG exit is clamped
 * before it is dialled.
 */
class VkTurnMtuClampTest {

    private fun wgOutbound(mtu: Int?): String = buildString {
        append("{\"type\":\"wireguard\",\"server\":\"127.0.0.1\",\"server_port\":9000,")
        append("\"local_address\":[\"10.7.1.2/32\"],")
        append("\"private_key\":\"priv\",\"peer_public_key\":\"pub\"")
        if (mtu != null) append(",\"mtu\":$mtu")
        append("}")
    }

    private fun mtuOf(raw: String?): Int? =
        raw?.let { Regex("\"mtu\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }

    @Test
    fun oversizedWireguardMtuIsClamped() {
        val clamped = VkTurnComposer.clampVkTurnMtu(
            ProxyProfile(type = "wireguard", rawOutbound = wgOutbound(1420))
        )
        assertEquals(VkTurnComposer.VKTURN_MAX_WG_MTU, mtuOf(clamped?.rawOutbound))
    }

    @Test
    fun smallerServerMtuIsKept() {
        val clamped = VkTurnComposer.clampVkTurnMtu(
            ProxyProfile(type = "wireguard", rawOutbound = wgOutbound(1000))
        )
        assertEquals(1000, mtuOf(clamped?.rawOutbound))
    }

    @Test
    fun missingWireguardMtuIsFilledIn() {
        val clamped = VkTurnComposer.clampVkTurnMtu(
            ProxyProfile(type = "wireguard", rawOutbound = wgOutbound(null))
        )
        assertEquals(VkTurnComposer.VKTURN_MAX_WG_MTU, mtuOf(clamped?.rawOutbound))
        // The rest of the outbound must survive the rewrite untouched.
        assertTrue(clamped!!.rawOutbound!!.contains("\"peer_public_key\":\"pub\""))
        assertTrue(clamped.rawOutbound!!.contains("\"local_address\":[\"10.7.1.2/32\"]"))
    }

    @Test
    fun amneziaWgIniIsClamped() {
        val ini = """
            [Interface]
            PrivateKey = priv
            Address = 10.7.1.2/32
            DNS = 1.1.1.1
            MTU = 1280
            Jc = 4

            [Peer]
            PublicKey = pub
            Endpoint = 127.0.0.1:9000
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val clamped = VkTurnComposer.clampVkTurnMtu(
            ProxyProfile(type = ProxyProfile.TYPE_AMNEZIAWG, awgConfig = ini)
        )
        assertTrue(clamped!!.awgConfig.contains("MTU = ${VkTurnComposer.VKTURN_MAX_WG_MTU}"))
        // Obfuscation knobs and the peer block must be preserved verbatim.
        assertTrue(clamped.awgConfig.contains("Jc = 4"))
        assertTrue(clamped.awgConfig.contains("Endpoint = 127.0.0.1:9000"))
    }

    @Test
    fun amneziaWgIniWithoutMtuGetsOne() {
        val ini = "[Interface]\nPrivateKey = priv\nAddress = 10.7.1.2/32\n\n[Peer]\nPublicKey = pub\n"
        val clamped = VkTurnComposer.clampVkTurnMtu(
            ProxyProfile(type = ProxyProfile.TYPE_AMNEZIAWG, awgConfig = ini)
        )
        // awgproxy would otherwise fall back to its own 1280 default, which is over the cap.
        assertTrue(clamped!!.awgConfig.contains("MTU = ${VkTurnComposer.VKTURN_MAX_WG_MTU}"))
        assertTrue(clamped.awgConfig.lineSequence().first().trim() == "[Interface]")
    }

    @Test
    fun proxyExitIsUntouched() {
        val proxy = ProxyProfile(type = ProxyProfile.TYPE_VLESS, server = "example.com", serverPort = 443, uuid = "u")
        assertEquals(proxy, VkTurnComposer.clampVkTurnMtu(proxy))
        assertNull(VkTurnComposer.clampVkTurnMtu(null))
    }
}
