package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Foolproofing: a 2nd/cascade proxy must not be the user's OWN main proxy (a proxy-into-itself). */
class ProxyProfileSameNodeTest {

    private val main = ProxyProfile(
        tag = "Finland", type = ProxyProfile.TYPE_VLESS, server = "fin.example.com", serverPort = 8443,
        uuid = "uuid-main", network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_REALITY,
    )

    @Test
    fun identicalLinkIsSameNode() {
        assertTrue(main.isSameNodeAs(main.copy(tag = "pasted again")))
    }

    @Test
    fun sameServerPortDifferentTransportIsSameNode() {
        // Same endpoint reached via a differently-formatted link still counts as the same node.
        val asTcp = main.copy(tag = "x", network = ProxyProfile.NETWORK_TCP, flow = "xtls-rprx-vision")
        assertTrue(main.isSameNodeAs(asTcp))
    }

    @Test
    fun differentServerIsNotSameNode() {
        val other = main.copy(server = "nbg.example.com", serverPort = 9444, tag = "Nuremberg")
        assertFalse(main.isSameNodeAs(other))
    }

    @Test
    fun sameServerDifferentPortIsNotSameNode() {
        assertFalse(main.isSameNodeAs(main.copy(serverPort = 9444)))
    }

    @Test
    fun identicalRawXrayConfigIsSameNode() {
        val a = ProxyProfile(tag = "a", rawXrayConfig = """{"outbounds":[]}""")
        val b = ProxyProfile(tag = "b", rawXrayConfig = """{"outbounds":[]}""")
        assertTrue(a.isSameNodeAs(b))
    }
}
