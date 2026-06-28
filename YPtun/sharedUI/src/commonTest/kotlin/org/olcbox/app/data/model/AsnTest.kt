package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsnTest {

    @Test
    fun normalizesEverySpelling() {
        assertEquals("13335", Asn.normalize("asn:13335"))
        assertEquals("13335", Asn.normalize("AS13335"))
        assertEquals("13335", Asn.normalize("as13335"))
        assertEquals("13335", Asn.normalize("asn:AS13335"))
        assertEquals("13335", Asn.normalize("  13335 "))
        assertNull(Asn.normalize("telegram"))
        assertNull(Asn.normalize("asn:"))
    }

    @Test
    fun detectsSelectors() {
        assertTrue(Asn.isSelector("asn:62041"))
        assertTrue(Asn.isSelector("ASN:62041"))
        assertFalse(Asn.isSelector("geoip:ru"))
        assertFalse(Asn.isSelector("10.0.0.0/8"))
    }

    @Test
    fun collectsDistinctNumbers() {
        val got = Asn.collect(listOf("asn:62041", "geoip:ru", "asn:AS13335", "asn:62041", "1.2.3.4/32"))
        assertEquals(setOf("62041", "13335"), got)
    }

    @Test
    fun expandsAsnKeepingOtherSelectorsAndOrder() {
        val map = mapOf("62041" to listOf("91.108.4.0/22", "149.154.160.0/20"))
        val got = Asn.expand(listOf("geoip:ru", "asn:62041", "10.0.0.0/8"), map)
        assertEquals(listOf("geoip:ru", "91.108.4.0/22", "149.154.160.0/20", "10.0.0.0/8"), got)
    }

    @Test
    fun unresolvedAsnContributesNothing() {
        // 62041 missing from the map → dropped; the rest of the rule survives.
        val got = Asn.expand(listOf("asn:62041", "1.2.3.4/32"), emptyMap())
        assertEquals(listOf("1.2.3.4/32"), got)
    }

    @Test
    fun profileExpandsIpBucketsOnly() {
        val map = mapOf("13335" to listOf("104.16.0.0/13"))
        val profile = RoutingProfile(
            directIp = listOf("asn:13335", "geoip:ru"),
            proxySites = listOf("domain:youtube.com"),
        )
        assertEquals(setOf("13335"), profile.referencedAsns())
        val expanded = profile.expandAsn(map)
        assertEquals(listOf("104.16.0.0/13", "geoip:ru"), expanded.directIp)
        // Non-IP buckets are untouched.
        assertEquals(listOf("domain:youtube.com"), expanded.proxySites)
    }

    @Test
    fun manualRuleExpansion() {
        val map = mapOf("62041" to listOf("91.108.4.0/22"))
        val rules = listOf(
            SingBoxRule(name = "tg", ip = listOf("asn:62041"), outbound = SingBoxRule.OUT_PROXY),
            SingBoxRule(name = "plain", ip = listOf("8.8.8.8")),
        )
        assertEquals(setOf("62041"), SingBoxRule.collectAsns(rules))
        val expanded = SingBoxRule.expandAsn(rules, map)
        assertEquals(listOf("91.108.4.0/22"), expanded[0].ip)
        assertEquals(listOf("8.8.8.8"), expanded[1].ip)
    }
}
