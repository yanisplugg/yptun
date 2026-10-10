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
}
