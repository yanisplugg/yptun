package org.olcbox.app.update

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-release check: applies the delta assets PUBLISHED on GitHub to the previous release's
 * binaries and demands the result be byte-identical to the next release. Skipped unless
 * `YPTUN_E2E_DIR` points at a directory laid out by the release-delta E2E script:
 *   apk-old.apk, apk-new.apk, apk.patch.gz          (Android; patch name = <sha256(old)[:16]>)
 *   img-old/, img-new/, win.patch                    (Windows app images; patch name = <sha256(app/YPtun.cfg)[:16]>)
 */
class ReleaseDeltaE2ETest {
    private val dir: Path? = System.getenv("YPTUN_E2E_DIR")?.let { Path.of(it) }

    private fun sha(p: Path) = DesktopAppImage.sha256(p)

    @Test
    fun androidPublishedPatchRebuildsTheNextApk() {
        val d = dir ?: return
        val out = d.resolve("apk-rebuilt.apk")
        val temp = Files.createTempDirectory("fbf").toFile()
        GZIPInputStream(Files.newInputStream(d.resolve("apk.patch.gz"))).use { patch ->
            Files.newOutputStream(out).use { FileByFileV1DeltaApplier(temp).applyDelta(d.resolve("apk-old.apk").toFile(), patch, it) }
        }
        assertEquals(sha(d.resolve("apk-new.apk")), sha(out), "rebuilt APK differs from the published one")
    }

    @Test
    fun windowsPublishedBundleRebuildsTheNextImage() {
        val d = dir ?: return
        val work = d.resolve("img-work")
        work.toFile().deleteRecursively()
        d.resolve("img-old").toFile().copyRecursively(work.toFile())

        val plan = DesktopDeltaPatch.stage(work, d.resolve("win.patch"), work.resolve(".yptun-update"), Files.createTempDirectory("tmp"))
        // The swapper's job: moves (cfg last), then deletions.
        plan.moves.forEach { (from, to) -> Files.createDirectories(to.parent); Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        plan.deletions.forEach { Files.deleteIfExists(it) }
        plan.stagingDir.toFile().deleteRecursively()

        fun tree(root: Path) = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.toList().associate { it.relativeTo(root).toString() to sha(it) }
        }
        val want = tree(d.resolve("img-new"))
        val got = tree(work)
        assertEquals(want.keys, got.keys, "file set differs")
        assertTrue(want.all { (k, v) -> got[k] == v }, "content differs: " + want.filter { got[it.key] != it.value }.keys)
        assertTrue(plan.moves.size < 20, "bundle should touch only a few files, touched ${plan.moves.size}")
    }
}
