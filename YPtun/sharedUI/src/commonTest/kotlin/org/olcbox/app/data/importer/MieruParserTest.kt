package org.olcbox.app.data.importer

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.share.ShareLinkComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MieruParserTest {
    @Test
    fun simpleLinkIsACompleteRoundTrippableStandardProfile() {
        val link = "mierus://user:p%40ss@203.0.113.9?profile=fast&mtu=1400&" +
            "multiplexing=MULTIPLEXING_HIGH&port=4443-4450&protocol=TCP#Tokyo"
        val profile = assertNotNull(ShareLinkParser.parse(link))
        assertEquals(ProxyProfile.TYPE_MIERU, profile.type)
        assertEquals("203.0.113.9", profile.server)
        assertEquals(4443, profile.serverPort)
        assertEquals("user", profile.username)
        assertEquals("p@ss", profile.password)
        assertEquals("Tokyo", profile.tag)
        assertTrue(profile.isComplete())
        assertEquals(link, ShareLinkComposer.compose(profile))
        assertTrue(LocationConfig(engine = EngineType.Standard, proxy = profile).isComplete())
    }

    @Test
    fun opaqueStandardLinkIsKeptForUpstreamDecoder() {
        val link = "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3pp"
        val profile = assertNotNull(ShareLinkParser.parse(link))
        assertEquals(ProxyProfile.TYPE_MIERU, profile.type)
        assertEquals(link, profile.mieruConfig)
        assertTrue(profile.isComplete())
        assertEquals(link, ShareLinkComposer.compose(profile))
    }
}
