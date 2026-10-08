package org.olcbox.app.desktop

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableDataTest {

    @Test
    fun onlyTheUsersDataIsIncluded() {
        for (ok in listOf("locations_v4.json", "update_settings.json", "device_identity", "settings/ui.json", "settings/telegram_warp.conf")) {
            assertTrue(PortableData.included(ok), ok)
        }
        for (no in listOf("yptun.log", "singbox-cache.db", "bin/x.exe", "geo/geoip.dat", "settings/a/b.json", "../x.json", "/etc/passwd", "olcrtc-data/names")) {
            assertFalse(PortableData.included(no), no)
        }
    }

    @Test
    fun packSealOpenUnpackRoundTrips() {
        val src = Files.createTempDirectory("yp-src")
        val dst = Files.createTempDirectory("yp-dst")
        Files.writeString(src.resolve("locations_v4.json"), """{"subscriptions":["https://sub.example/token"]}""")
        Files.write(src.resolve("device_identity"), byteArrayOf(1, 2, 3))
        Files.createDirectories(src.resolve("settings"))
        Files.writeString(src.resolve("settings/ui.json"), "{\"theme\":\"dark\"}")
        Files.writeString(src.resolve("yptun.log"), "not part of it")

        val sealed = PortableData.seal(PortableData.pack(src))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("sub.example"), "the file must not show its content")
        PortableData.unpackInto(dst, PortableData.open(sealed))

        assertEquals(Files.readString(src.resolve("locations_v4.json")), Files.readString(dst.resolve("locations_v4.json")))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(dst.resolve("device_identity")))
        assertEquals("{\"theme\":\"dark\"}", Files.readString(dst.resolve("settings/ui.json")))
        assertFalse(Files.exists(dst.resolve("yptun.log")))
    }

    @Test
    fun aDamagedOrForeignFileIsRefused() {
        val sealed = PortableData.seal("hello".toByteArray())
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { PortableData.open(tampered) }
        assertFailsWith<Exception> { PortableData.open("PK\u0003\u0004 just a zip".toByteArray()) }
        assertFailsWith<Exception> { PortableData.open(ByteArray(0)) }
    }

    @Test
    fun anArchiveCannotWriteOutsideTheDataDir() {
        val evil = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("../escaped.json")); z.write(1); z.closeEntry() }
        }.toByteArray()
        val dst = Files.createTempDirectory("yp-dst")
        assertFailsWith<IllegalArgumentException> { PortableData.unpackInto(dst, evil) }
        assertFalse(Files.exists(dst.resolveSibling("escaped.json")))
    }

    @Test
    fun theSaverWritesTheBundleOnlyWhenSomethingChanged() {
        val dir = Files.createTempDirectory("yp-live")
        val exeDir = Files.createTempDirectory("yp-exe")
        val bundle = exeDir.resolve(PortableData.FILE_NAME)
        val saver = PortableData.Saver(dir, bundle)

        saver.saveIfChanged()
        assertFalse(Files.exists(bundle), "nothing to save yet")

        Files.writeString(dir.resolve("locations_v4.json"), "{}")
        saver.saveIfChanged()
        assertTrue(Files.exists(bundle))
        val first = Files.getLastModifiedTime(bundle)

        Thread.sleep(30)
        saver.saveIfChanged()
        assertEquals(first, Files.getLastModifiedTime(bundle), "unchanged data is not rewritten")

        Files.writeString(dir.resolve("locations_v4.json"), "{\"a\":1}")
        Files.setLastModifiedTime(dir.resolve("locations_v4.json"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
        saver.saveIfChanged()
        val back = Files.createTempDirectory("yp-back")
        PortableData.unpackInto(back, PortableData.open(Files.readAllBytes(bundle)))
        assertEquals("{\"a\":1}", Files.readString(back.resolve("locations_v4.json")))
    }
}
