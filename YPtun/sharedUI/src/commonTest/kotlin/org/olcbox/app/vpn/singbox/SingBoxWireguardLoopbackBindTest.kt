package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * VK-TURN / WDTT tunnel WireGuard to a LOCAL relay listener. On desktop the config runs with
 * `route.auto_detect_interface`, and sing-box appends its bind-to-interface control to every dialer —
 * including WireGuard's, whose socket is a ListenPacket the control only ever sees as `0.0.0.0`. The
 * socket then gets pinned to the physical NIC and cannot reach 127.0.0.1 at all ("wsasendmsg: The
 * requested address is not valid in its context"), so the tunnel came up and carried nothing.
 *
 * `inet4_bind_address` is what turns that bind off for one endpoint. It must be emitted ONLY for a
 * loopback peer with auto-detect on — Android relies on the bind/protect path and must not change.
 */
class SingBoxWireguardLoopbackBindTest {

    private fun wireguard(server: String) = ProxyProfile(
        tag = "WDTT",
        type = "wireguard",
        server = server,
        serverPort = 9000,
        rawOutbound = """
            {"type":"wireguard","server":"$server","server_port":9000,
             "local_address":["10.0.0.2/32"],
             "private_key":"aGVsbG8gd29ybGQgaGVsbG8gd29ybGQgaGVsbG8gd28=",
             "peer_public_key":"d29ybGQgaGVsbG8gd29ybGQgaGVsbG8gd29ybGQgaGU=","mtu":1200}
        """.trimIndent(),
    )

    private fun endpointOf(server: String, autoDetectInterface: Boolean) = Json
        .parseToJsonElement(
            SingBoxConfig.build(
                profile = wireguard(server),
                listenPort = 10808,
                listenHost = "127.0.0.1",
                socksUsername = "",
                socksPassword = "",
                autoDetectInterface = autoDetectInterface,
            )
        )
        .jsonObject["endpoints"]!!.jsonArray.single().jsonObject

    @Test
    fun loopbackPeerOnDesktopSkipsTheInterfaceBind() {
        val endpoint = endpointOf("127.0.0.1", autoDetectInterface = true)
        assertEquals("0.0.0.0", endpoint["inet4_bind_address"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun remotePeerKeepsTheInterfaceBind() {
        val endpoint = endpointOf("203.0.113.7", autoDetectInterface = true)
        assertNull(endpoint["inet4_bind_address"])
    }

    @Test
    fun androidPathIsUnchanged() {
        val endpoint = endpointOf("127.0.0.1", autoDetectInterface = false)
        assertNull(endpoint["inet4_bind_address"])
    }
}
