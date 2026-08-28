package org.olcbox.app.data.share

import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YptunInboundCodecTest {

    private val sample = LocationConfig(
        name = "My Server",
        engine = EngineType.Standard,
        core = ProxyCore.Xray,
        routingProfileId = "ru-direct",
        transport = LocationConfig.DEFAULT_TRANSPORT,
        proxy = ProxyProfile(
            type = ProxyProfile.TYPE_VLESS,
            server = "example.com",
            serverPort = 443,
            uuid = "abc-123-uuid",
        ),
    )

    @Test
    fun composeProducesYptunInboundLink() {
        val link = YptunInboundCodec.compose(sample)
        assertTrue(link.startsWith(YptunInboundCodec.PREFIX), "link should start with the scheme: $link")
        // compose() emits the DEFLATE form (?v=2&c=) whenever it is shorter than the plain
        // ?v=1&d= one, so accept either rather than pinning the test to one encoding.
        assertTrue(
            link.contains("?v=2&c=") || link.contains("?v=1&d="),
            "link should carry the base64 payload: $link"
        )
    }

    @Test
    fun roundTripsTheWholeConfig() {
        val link = YptunInboundCodec.compose(sample)
        val parsed = YptunInboundCodec.parse(link)
        assertEquals(sample.normalized(), parsed, "parsed config must equal the original (all fields)")
    }

    @Test
    fun parsesPaddedFragmentForm() {
        // Fragment form (#<payload>) and missing base64 padding must still decode.
        val link = YptunInboundCodec.compose(sample)
        val payload = link.substringAfter("d=")
        val fragmentForm = "${YptunInboundCodec.PREFIX}#$payload"
        assertEquals(sample.normalized(), YptunInboundCodec.parse(fragmentForm))
    }

    @Test
    fun rejectsNonYptunLinks() {
        assertNull(YptunInboundCodec.parse("vless://abc@example.com:443"))
        assertNull(YptunInboundCodec.parse("https://example.com/sub"))
        assertNull(YptunInboundCodec.parse("yptun://inbound?v=1&d=!!!notbase64!!!"))
    }
}
