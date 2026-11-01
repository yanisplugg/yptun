package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomApiSmokeTest {
    @Test
    fun roomApiLinksAndRejectsBadArgs() {
        val dll = File("../desktopApp/build/generated/desktopNativeResources/native/yptuncore-windows-amd64.dll")
        if (!dll.exists() || !System.getProperty("os.name").startsWith("Windows")) return
        val lib = Native.load(dll.absolutePath, YpTunCoreLib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        assertEquals(0, lib.YpRtcRoomsRunning())
        // Unknown provider / empty room: rejected up front, no network, no handle.
        assertEquals(-1L, lib.YpRtcStartRoom("nope", "datachannel", "", "dev", "00", 0, "u", "p", 1000))
        assertEquals(0, lib.YpRtcRoomHealthy(12345L))
        assertEquals(0, lib.YpRtcRoomPort(12345L))
        lib.YpRtcStopAllRooms()
    }
}
