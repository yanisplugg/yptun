package org.olcbox.app.update

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.deleteIfExists

/**
 * Desktop delta updates: a File-by-File v1 patch that turns the INSTALLED application jar into the
 * new release's jar, so an update downloads a few MB instead of the ~160 MB installer.
 *
 * ## Container format (`YPtun-delta-<from>-<to>-<os>-<arch>.patch`)
 *
 * ```
 * YPTUNDLT1\n          magic + format version
 * <sha256 of the old jar>\n
 * <sha256 of the new jar>\n
 * <gzip-compressed raw File-by-File v1 patch>
 * ```
 *
 * The two hashes are what make this safe to apply blind: the patch is refused unless the installed
 * jar is EXACTLY the one it was generated against, and the reconstructed jar is refused unless it is
 * byte-identical to the published one. Either check failing simply falls back to the full installer.
 */
internal object DesktopDeltaPatch {

    const val MAGIC = "YPTUNDLT1"

    data class Header(val fromSha256: String, val toSha256: String)

    /**
     * Rebuilds the new jar from [baseJar] + [patchFile] into [target].
     *
     * @throws IllegalStateException when the container is malformed, the installed jar is not the
     * patch's base, or the result doesn't match the expected hash.
     */
    fun apply(baseJar: Path, patchFile: Path, target: Path, tempDir: Path) {
        Files.createDirectories(tempDir)
        BufferedInputStream(Files.newInputStream(patchFile)).use { input ->
            val header = readHeader(input)
            val baseHash = DesktopAppImage.sha256(baseJar)
            check(baseHash.equals(header.fromSha256, ignoreCase = true)) {
                "installed jar is not the patch base (have $baseHash, want ${header.fromSha256})"
            }
            Files.newOutputStream(target).use { output ->
                FileByFileV1DeltaApplier(tempDir.toFile())
                    .applyDelta(baseJar.toFile(), GZIPInputStream(input), output)
            }
            val resultHash = DesktopAppImage.sha256(target)
            if (!resultHash.equals(header.toSha256, ignoreCase = true)) {
                target.deleteIfExists()
                error("patched jar does not match the published one ($resultHash != ${header.toSha256})")
            }
        }
    }

    /**
     * Reads the three header lines, leaving [input] positioned at the first byte of the gzip stream.
     * Read one byte at a time on purpose — a buffered reader would swallow part of the payload.
     */
    fun readHeader(input: InputStream): Header {
        val magic = readLine(input)
        check(magic == MAGIC) { "not a YPtun delta patch (magic='$magic')" }
        val from = readLine(input)
        val to = readLine(input)
        check(from.length == 64 && to.length == 64) { "malformed delta patch header" }
        return Header(from, to)
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
            check(line.length <= 128) { "malformed delta patch header" }
        }
        return line.toString()
    }
}
