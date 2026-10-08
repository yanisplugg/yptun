package org.olcbox.app.data.importer

import org.olcbox.app.data.model.OpenFluxConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFluxUriParserTest {

    @Test
    fun parsesQueryUriYandex() {
        val uri = "openflux://config?transport=yandex&doc_url=https%3A%2F%2Fdocs.yandex.ru%2Fdocs%2Fview%3Fid%3D123#MyServer"
        val result = OpenFluxUriParser.parse(uri)
        assertNotNull(result)
        val (config, name) = result
        assertEquals("MyServer", name)
        assertEquals(OpenFluxConfig.TRANSPORT_YANDEX, config.transport)
        assertEquals("https://docs.yandex.ru/docs/view?id=123", config.docUrl)
    }

    @Test
    fun parsesQueryUriMax() {
        val uri = "openflux://config?transport=oneme&max_token=secret_tok_123&max_uid=99887766#MaxNode"
        val result = OpenFluxUriParser.parse(uri)
        assertNotNull(result)
        val (config, name) = result
        assertEquals("MaxNode", name)
        assertEquals(OpenFluxConfig.TRANSPORT_MAX, config.transport)
        assertEquals("secret_tok_123", config.maxToken)
        assertEquals("99887766", config.maxUid)
    }

    @Test
    fun parsesBareDocUrl() {
        val uri = "openflux://https://docs.yandex.ru/docs/view?id=abc123#QuickYandex"
        val result = OpenFluxUriParser.parse(uri)
        assertNotNull(result)
        val (config, name) = result
        assertEquals("QuickYandex", name)
        assertEquals("https://docs.yandex.ru/docs/view?id=abc123", config.docUrl)
        assertEquals(OpenFluxConfig.TRANSPORT_YANDEX, config.transport)
    }

    @Test
    fun parsesBase64Json() {
        val json = """{"transport":"vyandex","doc_url":"https://docs.yandex.ru/volga","name":"VolgaEditor"}"""
        val b64 = kotlin.io.encoding.Base64.UrlSafe.encode(json.encodeToByteArray()).trimEnd('=')
        val uri = "openflux://$b64"
        val result = OpenFluxUriParser.parse(uri)
        assertNotNull(result)
        val (config, name) = result
        assertEquals("VolgaEditor", name)
        assertEquals(OpenFluxConfig.TRANSPORT_VYANDEX, config.transport)
        assertEquals("https://docs.yandex.ru/volga", config.docUrl)
    }

    @Test
    fun roundtripToUri() {
        val config = OpenFluxConfig(
            transport = OpenFluxConfig.TRANSPORT_YANDEX,
            docUrl = "https://docs.yandex.ru/docs/view?id=456"
        )
        val uri = OpenFluxUriParser.toUri(config, "YandexTest")
        val parsed = OpenFluxUriParser.parse(uri)
        assertNotNull(parsed)
        assertEquals("YandexTest", parsed.second)
        assertEquals(config.docUrl, parsed.first.docUrl)
    }

    @Test
    fun rejectsNonOpenFlux() {
        assertNull(OpenFluxUriParser.parse("vless://whatever"))
        assertNull(OpenFluxUriParser.parse("qwdtt://config?peer=1.2.3.4"))
    }
}
