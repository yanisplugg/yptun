package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MasterDNS takes its domains and resolvers as free-typed lists, and two things downstream depend on
 * parsing them right: the Go client is handed the joined list, and the desktop TUN must route EVERY
 * resolver around the tunnel (missing one deadlocks the tunnel that carries it).
 */
class MasterDnsConfigTest {

    @Test
    fun `lists split on commas, whitespace and newlines`() {
        val config = MasterDnsConfig(
            domains = "v.example.com, v2.example.com\nv3.example.com",
            encryptionKey = "secret",
            resolvers = "1.1.1.1, 8.8.8.8:5300;9.9.9.9\n1.1.1.1",
        )
        assertEquals(listOf("v.example.com", "v2.example.com", "v3.example.com"), config.domainList())
        // Duplicates collapse — the client builds a domain×resolver catalog, so a repeat is dead weight.
        assertEquals(listOf("1.1.1.1", "8.8.8.8:5300", "9.9.9.9"), config.resolverList())
    }

    @Test
    fun `resolver hosts drop the port and survive bracketed IPv6`() {
        val config = MasterDnsConfig(resolvers = "1.1.1.1, 8.8.8.8:5300, [2001:4860:4860::8888]:53")
        assertEquals(listOf("1.1.1.1", "8.8.8.8", "2001:4860:4860::8888"), config.resolverHosts())
    }

    @Test
    fun `completeness needs a domain, a key and a resolver`() {
        val full = MasterDnsConfig(domains = "v.example.com", encryptionKey = "k", resolvers = "1.1.1.1")
        assertTrue(full.isComplete())
        assertFalse(full.copy(domains = "  ").isComplete())
        assertFalse(full.copy(encryptionKey = "").isComplete())
        assertFalse(full.copy(resolvers = " , ").isComplete())
    }

    @Test
    fun `normalized rewrites the lists and clamps the tuning values`() {
        val normalized = MasterDnsConfig(
            domains = " v.example.com \n v2.example.com ",
            encryptionKey = "  k  ",
            encryptionMethod = 9,
            resolvers = "1.1.1.1 8.8.8.8",
            balancingStrategy = 42,
            packetDuplication = -1,
            proxyLink = "  vless://x  ",
        ).normalized()

        assertEquals("v.example.com,v2.example.com", normalized.domains)
        assertEquals("k", normalized.encryptionKey)
        assertEquals("1.1.1.1,8.8.8.8", normalized.resolvers)
        // Out-of-range values must not reach the core: it rejects an unknown cipher outright.
        assertEquals(5, normalized.encryptionMethod)
        assertEquals(8, normalized.balancingStrategy)
        assertEquals(0, normalized.packetDuplication)
        assertEquals("vless://x", normalized.proxyLink)
    }

    @Test
    fun `a proxy over the tunnel defaults to Xray`() {
        val config = MasterDnsConfig(domains = "v.example.com", encryptionKey = "k", resolvers = "1.1.1.1")
        assertFalse(config.hasProxy())
        assertTrue(config.copy(proxyLink = "vless://x").hasProxy())
        // Chaining the exit over the tunnel's SOCKS needs Xray's socket-level dialerProxy to keep a
        // reality/vision transport intact.
        assertEquals(ProxyCore.Xray, config.resolvedProxyCore(profile = null))
        assertEquals(ProxyCore.SingBox, config.copy(proxyCore = ProxyCore.SingBox).resolvedProxyCore(null))
    }
}
