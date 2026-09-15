package org.olcbox.app.data.importer

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.VkTurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QwdttUriParserTest {

    // Exactly what wdtt-server/database_bot.go emits (name/pass url-escaped).
    private val link =
        "qwdtt://config?name=qWDTT+-+Main+%2851.15.1.2%29&peer=51.15.1.2&hashes=ab12%2Ccd34&workers=9&port=9000&pass=p%40ss+word"

    @Test
    fun parsesServerQuickLink() {
        val l = QwdttUriParser.parse(link)!!
        assertEquals("qWDTT - Main (51.15.1.2)", l.name)
        assertEquals("51.15.1.2", l.peer)
        // The server packs the hashes comma-separated; we store them ONE PER LINE, which is the
        // format the location settings edit (one «Ссылка на звонок VK» field per line). Keeping the
        // commas put every hash into the first field and painted it red.
        assertEquals("ab12\ncd34", l.hashes)     // VK hashes → stored as vkLink
        assertEquals("p@ss word", l.password)
        assertEquals(9000, l.listenPort)
        assertEquals(9, l.workers)
    }

    @Test
    fun rejectsWrongSchemeOrMissingFields() {
        assertNull(QwdttUriParser.parse("freeturn://vk?<mode=udp>@1.2.3.4:5"))
        assertNull(QwdttUriParser.parse("qwdtt://config?name=x&port=9000"))  // no peer/pass
    }

    @Test
    fun composeRoundTrips() {
        val l = QwdttUriParser.parse(link)!!
        val vk = VkTurnConfig(
            core = VkTurnConfig.CORE_WDTT,
            wdttPeer = l.peer,
            wdttPassword = l.password,
            wdttWorkers = l.workers,
            vkLink = l.hashes,
            listenPort = l.listenPort,
        )
        // A WDTT VK-TURN location built from the link is immediately connectable.
        val cfg = LocationConfig(name = l.name, engine = org.olcbox.app.data.model.EngineType.VkTurn, vkturn = vk)
        assertTrue(cfg.isStorable() && vk.isComplete())

        val again = QwdttUriParser.parse(QwdttUriParser.compose(l.name, vk))!!
        assertEquals(l, again)
    }
}
