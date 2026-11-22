package org.olcbox.app.data.importer

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.share.ShareLinkComposer
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.PingsState
import org.olcbox.app.util.parseEmojiAndName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun fullMieruLinkWithEmojiProfileNameAndActiveProfileIsCorrectlyImported() {
        // Link created with activeProfile="🇺🇸Нью-Йорк (Mieru)", profileName="🇺🇸Нью-Йорк (Mieru)",
        // host="us-ny.mieru.yptun.app", port=4443, user="alice", pass="secret123".
        val link = "mieru://ClUKH/Cfh7rwn4e40J3RjNGOLdCZ0L7RgNC6IChNaWVydSkSEgoFYWxpY2USCXNlY3JldDEyMxoeEhV1cy1ueS5taWVydS55cHR1bi5hcHAaBQjbIhACEh/wn4e68J+HuNCd0YzRji3QmdC+0YDQuiAoTWllcnUpILgI"
        val profile = assertNotNull(ShareLinkParser.parse(link))

        assertEquals(ProxyProfile.TYPE_MIERU, profile.type)
        assertEquals("🇺🇸Нью-Йорк (Mieru)", profile.tag)
        assertEquals("us-ny.mieru.yptun.app", profile.server)
        assertEquals(4443, profile.serverPort)
        assertEquals("alice", profile.username)
        assertEquals("secret123", profile.password)
        assertEquals(link, profile.mieruConfig)
        assertTrue(profile.isComplete())

        // Ensure LocationConfig displays the exact profile name including Unicode and flags
        val location = LocationConfig(name = profile.displayName(), proxy = profile)
        assertEquals("🇺🇸Нью-Йорк (Mieru)", location.displayName())

        // Ensure emoji split separates flag and location cleanly
        val (emoji, name) = parseEmojiAndName(profile.displayName())
        assertEquals("🇺🇸", emoji)
        assertEquals("Нью-Йорк (Mieru)", name)
    }

    @Test
    fun multiProfileMieruSelectsActiveProfile() {
        // Contains 2 profiles: 1) "🇯🇵Токио (Mieru)", 2) "🇺🇸Нью-Йорк (Mieru)".
        // activeProfile is set to "🇺🇸Нью-Йорк (Mieru)".
        val link = "mieru://ClMKGvCfh6/wn4e10KLQvtC60LjQviAoTWllcnUpEhgKCnRva3lvX3VzZXISCnRva3lvX3Bhc3MaGxISanAubWllcnUueXB0dW4uYXBwGgUIuwMQAgpVCh/wn4e68J+HuNCd0YzRji3QmdC+0YDQuiAoTWllcnUpEhIKBWFsaWNlEglzZWNyZXQxMjMaHhIVdXMtbnkubWllcnUueXB0dW4uYXBwGgUI2yIQAhIf8J+HuvCfh7jQndGM0Y4t0JnQvtGA0LogKE1pZXJ1KSC4CA=="
        val profile = assertNotNull(ShareLinkParser.parse(link))

        assertEquals("🇺🇸Нью-Йорк (Mieru)", profile.tag)
        assertEquals("us-ny.mieru.yptun.app", profile.server)
        assertEquals(4443, profile.serverPort)
        assertEquals("alice", profile.username)
    }

    @Test
    fun fullMieruDoesNotGetOverwrittenByGenericSystemRemark() {
        val baseLink = "mieru://ClUKH/Cfh7rwn4e40J3RjNGOLdCZ0L7RgNC6IChNaWVydSkSEgoFYWxpY2USCXNlY3JldDEyMxoeEhV1cy1ueS5taWVydS55cHR1bi5hcHAaBQjbIhACEh/wn4e68J+HuNCd0YzRji3QmdC+0YDQuiAoTWllcnUpILgI"
        val linkWithSystemRemark = "$baseLink#YPTun"
        val profile = assertNotNull(ShareLinkParser.parse(linkWithSystemRemark))
        assertEquals("🇺🇸Нью-Йорк (Mieru)", profile.tag)

        val linkWithMieruRemark = "$baseLink#Mieru"
        val profile2 = assertNotNull(ShareLinkParser.parse(linkWithMieruRemark))
        assertEquals("🇺🇸Нью-Йорк (Mieru)", profile2.tag)
    }

    @Test
    fun mieruIsNotMarkedOfflineByUniversalPingState() {
        val link = "mieru://ClUKH/Cfh7rwn4e40J3RjNGOLdCZ0L7RgNC6IChNaWVydSkSEgoFYWxpY2USCXNlY3JldDEyMxoeEhV1cy1ueS5taWVydS55cHR1bi5hcHAaBQjbIhACEh/wn4e68J+HuNCd0YzRji3QmdC+0YDQuiAoTWllcnUpILgI"
        val profile = assertNotNull(ShareLinkParser.parse(link))
        val loc = LocationItem(
            storageId = "loc_mieru_1",
            fullName = profile.displayName(),
            config = LocationConfig(name = profile.displayName(), proxy = profile)
        )

        // Simulate a ping pass that finished with a null result (as happens for unprobed off-tunnel tunnels)
        val pingsState = PingsState.Success(mapOf(loc.storageId to null))

        val isMieru = loc.config?.proxy?.type == ProxyProfile.TYPE_MIERU
        val isOffline = if (isMieru) false else (pingsState.pings.containsKey(loc.storageId) && pingsState.pings[loc.storageId] == null)
        assertFalse(isOffline, "Mieru location must never be marked offline from universal ping probes")
    }

    @Test
    fun opaqueStandardLinkFallbackIsKeptForUpstreamDecoder() {
        val link = "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3pp"
        val profile = assertNotNull(ShareLinkParser.parse(link))
        assertEquals(ProxyProfile.TYPE_MIERU, profile.type)
        assertEquals(link, profile.mieruConfig)
        assertTrue(profile.isComplete())
        assertEquals(link, ShareLinkComposer.compose(profile))
    }
}
