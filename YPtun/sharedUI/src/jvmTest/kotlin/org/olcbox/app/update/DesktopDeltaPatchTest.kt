package org.olcbox.app.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopDeltaPatchTest {

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bundle(file: Path, manifest: String, payloads: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
            payloads.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    /** The launcher changes on every version, so a bundle must be able to replace it and the runtime files. */
    @Test
    fun replacesLauncherAddsFilesInNewDirectoriesAndDeletes() {
        val root = Files.createTempDirectory("yptun-root")
        Files.createDirectories(root.resolve("app"))
        Files.createDirectories(root.resolve("runtime/bin/server"))
        Files.write(root.resolve("YPtun.exe"), "old-launcher".toByteArray())
        Files.write(root.resolve("runtime/bin/server/classes.jsa"), "cds".toByteArray())

        val newLauncher = "new-launcher".toByteArray()
        val newFile = "fresh".toByteArray()
        val manifest = """
            {"format":3,"from":"1","to":"2","target":"windows-amd64","ops":[
              {"op":"add","to":"YPtun.exe","toSha":"${sha(newLauncher)}","payload":"p0"},
              {"op":"add","to":"runtime/lib/new.dat","toSha":"${sha(newFile)}","payload":"p1"},
              {"op":"delete","from":"runtime/bin/server/classes.jsa"}
            ]}
        """.trimIndent()
        val file = Files.createTempFile("delta", ".patch")
        bundle(file, manifest, mapOf("p0" to newLauncher, "p1" to newFile))

        val plan = DesktopDeltaPatch.stage(
            rootDir = root,
            bundle = file,
            stagingDir = root.resolve(".yptun-update"),
            tempDir = Files.createTempDirectory("tmp")
        )

        assertEquals(root, plan.rootDir)
        assertEquals(
            setOf(root.resolve("YPtun.exe"), root.resolve("runtime/lib/new.dat")),
            plan.moves.map { it.second }.toSet()
        )
        assertEquals(listOf(root.resolve("runtime/bin/server/classes.jsa")), plan.deletions)
        assertTrue(plan.moves.all { Files.exists(it.first) })
        // Nothing is touched in the installation itself until the swapper runs.
        assertEquals("old-launcher", Files.readString(root.resolve("YPtun.exe")))
    }

    @Test
    fun refusesAFileThatDoesNotMatchItsPublishedHash() {
        val root = Files.createTempDirectory("yptun-root")
        val manifest = """{"format":3,"ops":[{"op":"add","to":"YPtun.exe","toSha":"${"0".repeat(64)}","payload":"p0"}]}"""
        val file = Files.createTempFile("delta", ".patch")
        bundle(file, manifest, mapOf("p0" to "tampered".toByteArray()))

        assertFailsWith<IllegalStateException> {
            DesktopDeltaPatch.stage(root, file, root.resolve(".yptun-update"), Files.createTempDirectory("tmp"))
        }
    }

    @Test
    fun refusesPathsEscapingTheInstallation() {
        val root = Files.createTempDirectory("yptun-root")
        val evil = "x".toByteArray()
        val manifest = """{"format":3,"ops":[{"op":"add","to":"../../evil.dll","toSha":"${sha(evil)}","payload":"p0"}]}"""
        val file = Files.createTempFile("delta", ".patch")
        bundle(file, manifest, mapOf("p0" to evil))

        assertFailsWith<IllegalStateException> {
            DesktopDeltaPatch.stage(root, file, root.resolve(".yptun-update"), Files.createTempDirectory("tmp"))
        }
    }

    @Test
    fun refusesAnOldFormatBundle() {
        val root = Files.createTempDirectory("yptun-root")
        val file = Files.createTempFile("delta", ".patch")
        bundle(file, """{"format":2,"ops":[]}""", emptyMap())

        assertFailsWith<IllegalStateException> {
            DesktopDeltaPatch.stage(root, file, root.resolve(".yptun-update"), Files.createTempDirectory("tmp"))
        }
    }
}
