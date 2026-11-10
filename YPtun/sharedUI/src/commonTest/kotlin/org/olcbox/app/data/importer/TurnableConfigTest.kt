package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VkTurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnableConfigTest {
    @Test
    fun turnableConfigCarriesItsCallAndNeedsNoSeparateVkLink() {
        val draft = VkTurnDraft(
            core = VkTurnConfig.CORE_TURNABLE,
            turnableConfig = "turnable://user:call@vk.com/wireguard?type=relay&gateway=203.0.113.7:56000",
            vkLink = "",
            listenPort = "9000",
            outbound = VkTurnConfig.OUTBOUND_AMNEZIAWG, // composer must force the supported WG shape
            wgPrivateKey = "client-private",
            wgPeerPublicKey = "server-public",
            wgAddress = "10.9.1.2/32",
            wgMtu = "1200",
        )
        val (vk, proxy) = VkTurnComposer.compose(draft, "Turnable")
        assertTrue(vk.usesTurnable())
        assertTrue(vk.isStorable())
        assertTrue(vk.isComplete())
        assertEquals(VkTurnConfig.OUTBOUND_WIREGUARD, vk.outbound)
        assertEquals("", vk.uri)
        val outbound = Json.parseToJsonElement(proxy.rawOutbound!!).jsonObject
        assertEquals("127.0.0.1", outbound["server"]!!.jsonPrimitive.content)
        assertEquals("9000", outbound["server_port"]!!.jsonPrimitive.content)

        val location = LocationConfig(engine = EngineType.VkTurn, vkturn = vk, proxy = proxy, proxyEnabled = true)
        assertTrue(location.isComplete())
        assertFalse(location.needsVkLink())
        val restored = VkTurnComposer.decompose(vk, proxy)
        assertEquals(draft.turnableConfig, restored.turnableConfig)
        assertEquals(VkTurnConfig.CORE_TURNABLE, restored.core)
    }
}
