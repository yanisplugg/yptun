package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.olcbox.app.data.model.OpenFluxConfig
import org.olcbox.app.data.share.deflateOrNull

@OptIn(ExperimentalEncodingApi::class)
class OpenFluxUriParserTest {
    private fun link(json: String): String =
        "openflux://v1/" + Base64.UrlSafe.encode(deflateOrNull(json.encodeToByteArray())!!).trimEnd('=')

    @Test
    fun singlePlainTransportImports() {
        val l = OpenFluxUriParser.parse(
            link("""{"name":"Home","transports":[{"type":"vyandex","url":"https://docs.yandex.ru/d/1"}]}""")
        )
        assertNotNull(l)
        assertEquals("Home", l.name)
        assertEquals(OpenFluxConfig.TRANSPORT_VYANDEX, l.config.transport)
        assertEquals("https://docs.yandex.ru/d/1", l.config.docUrl)
    }

    @Test
    fun encryptedLinkKeepsSecretContextAndNegotiate() {
        val t = """{"type":"mailru","url":"https://cloud.mail.ru/public/x"}"""
        val c = OpenFluxUriParser.parse(
            link("""{"negotiate":true,"secret":"abcdefghijklmnop","context":"ctx","transports":[$t]}""")
        )!!.config
        assertEquals("abcdefghijklmnop", c.secret)
        assertEquals("ctx", c.context)
        assertEquals(true, c.negotiate)
        assertEquals(
            listOf("--encryption-key-file", "k", "--session-context", "ctx", "--negotiate"),
            c.encryptionArgs("k"),
        )
        val classic = OpenFluxUriParser.parse(link("""{"secret":"abcdefghijklmnop","transports":[$t]}"""))!!.config
        assertEquals(listOf("--encryption-key-file", "k"), classic.encryptionArgs("k"))
    }

    @Test
    fun negotiateWithoutSecretStreamAndMultiAreRejected() {
        val t = """{"type":"yandex","url":"https://x"}"""
        assertNull(OpenFluxUriParser.parse(link("""{"negotiate":true,"transports":[$t]}""")))
        assertNull(OpenFluxUriParser.parse(link("""{"mode":"stream","secret":"abcdefghijklmnop","transports":[$t]}""")))
        assertNull(OpenFluxUriParser.parse(link("""{"transports":[$t,$t]}""")))
        assertNull(OpenFluxUriParser.parse(link("""{"transports":[{"type":"direct","dial":"1.2.3.4:5"}]}""")))
        assertNull(OpenFluxUriParser.parse("openflux://v1/!!!"))
    }

    @Test
    fun sessionLinkImportsAsMultiTransportAndBuildsCoreArgs() {
        val c = OpenFluxUriParser.parse(
            link(
                """{"secret":"abcdefghijklmnop","transports":[
                {"type":"direct","dial":"203.0.113.5:8445","priority":100},
                {"type":"yandex","url":"https://docs.yandex.ru/d/1","priority":50},
                {"type":"mailru","url":"https://cloud.mail.ru/public/x","priority":10}]}"""
            )
        )!!.config
        assertEquals(true, c.isSession())
        assertEquals(true, c.isComplete())
        assertEquals(
            listOf(
                "--transports", "direct:100,yandex:50,mailru:10",
                "--direct-dial", "203.0.113.5:8445", "--yandex-url", "https://docs.yandex.ru/d/1",
                "--mailru-url", "https://cloud.mail.ru/public/x",
            ),
            c.carrierArgs(),
        )
        // Same type twice, a session without a key, or a direct carrier without a port are refused.
        val y = """{"type":"yandex","url":"https://x"}"""
        assertNull(OpenFluxUriParser.parse(link("""{"secret":"abcdefghijklmnop","transports":[$y,$y]}""")))
        assertNull(OpenFluxUriParser.parse(link("""{"secret":"abcdefghijklmnop","transports":[$y,{"type":"direct","dial":"host"}]}""")))
    }

    @Test
    fun singleCarrierArgsAndAvitoNeedCookies() {
        val single = OpenFluxConfig(transport = OpenFluxConfig.TRANSPORT_VYANDEX, docUrl = "https://d").normalized()
        assertEquals(listOf("--transport", "vyandex", "--url", "https://d"), single.carrierArgs())
        val avito = OpenFluxConfig(transport = OpenFluxConfig.TRANSPORT_AVITO, docUrl = "eyJ2IjoyLCJyb29tcyI6WyJhIl19")
        assertEquals(false, avito.isComplete())
        assertEquals(true, avito.copy(avitoCookies = "a=b").isComplete())
        assertEquals(true, avito.copy(avitoCookies = "a=b").withSession(true).copy(secret = "k").isComplete())
        assertEquals(avito.transport, avito.withSession(true).withSession(false).transport)
    }

    @Test
    fun maxLinkCarriesExitUidAndClientToken() {
        val c = OpenFluxUriParser.parse(link("""{"transports":[{"type":"oneme","uid":"777","token":"cli-tok"}]}"""))!!.config
        assertEquals(OpenFluxConfig.TRANSPORT_MAX, c.transport)
        assertEquals("777", c.maxUid)
        assertEquals("cli-tok", c.maxToken)
        assertNull(OpenFluxUriParser.parse(link("""{"transports":[{"type":"oneme","uid":"x1","token":"t"}]}""")))
        assertNull(OpenFluxUriParser.parse(link("""{"transports":[{"type":"oneme","uid":"777"}]}""")))
    }

    @Test
    fun avitoLinkCarriesRoomListAndClientCookies() {
        val c = OpenFluxUriParser.parse(link("""{"transports":[{"type":"avito","url":"ROOMLISTROOMLIST1234567890","cookies":"sid=c1"}]}"""))!!.config
        assertEquals(OpenFluxConfig.TRANSPORT_AVITO, c.transport)
        assertEquals("ROOMLISTROOMLIST1234567890", c.docUrl)
        assertEquals("sid=c1", c.avitoCookies)
    }
}
