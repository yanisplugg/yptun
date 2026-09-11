package org.olcbox.app.vpn.wdtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WdttRawTunTest {
    @Test
    fun parsesTheServersRawConf() {
        assertEquals(WdttRawTun("10.70.0.2", listOf("1.1.1.1", "8.8.8.8"), 1380), WdttRawTun.parse("RAWCONF:10.70.0.2|1.1.1.1, 8.8.8.8|1380"))
        // Bad DNS / MTU fall back like the core does; the TUN still comes up.
        assertEquals(WdttRawTun("10.70.0.3", listOf("1.1.1.1"), 1280), WdttRawTun.parse("RAWCONF:10.70.0.3|x|0"))
        // An address the VpnService builder would reject means no Raw direct at all.
        assertNull(WdttRawTun.parse("RAWCONF:fd00::2|1.1.1.1|1280"))
        assertNull(WdttRawTun.parse("RAWCONF:10.70.0.4|1.1.1.1"))
    }
}
