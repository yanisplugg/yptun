package org.olcbox.app.update

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.io.path.exists

/**
 * Desktop delta updates: a bundle that rebuilds the changed files of an installed app image, so an
 * update downloads a few MB instead of the ~160 MB installer.
 *
 * ## Why a bundle and not a single patch
 *
 * jpackage flattens every dependency into `<install>/app/` as its own jar, and each name carries a
 * content hash (`desktopApp-8ae7e33d….jar`) that changes with the contents. A release therefore
 * REPLACES a handful of files rather than modifying one, and `YPtun.cfg` — which enumerates the
 * classpath by exact filename — has to change with them. Anything under `runtime/` is out of scope:
 * a JRE change means a full installer, and the generator refuses to produce a bundle for it.
 *
 * ## Format (`YPtun-delta-<from>-<to>-<os>-<arch>.patch`)
 *
 * A ZIP holding `manifest.json` plus one payload entry per operation:
 * - `patch` — a gzip File-by-File v1 patch turning `from` into `to` (both jars).
 * - `add` — the new file verbatim (new dependency, `YPtun.cfg`, anything not worth patching).
 * - `delete` — no payload; the old file goes away.
 *
 * Every operation carries SHA-256 of what it expects and what it produces. Nothing is applied unless
 * the installation is EXACTLY the one the bundle was generated against, and nothing is handed to the
 * swapper unless it came out byte-identical to the published build.
 */
internal object DesktopDeltaPatch {

    const val FORMAT = 2

    @Serializable
    data class Manifest(
        val format: Int = FORMAT,
        val from: String = "",
        val to: String = "",
        val target: String = "",
        val ops: List<Op> = emptyList(),
    )

    @Serializable
    data class Op(
        val op: String,
        val from: String = "",
        @SerialName("to") val to: String = "",
        val fromSha: String = "",
        val toSha: String = "",
        val payload: String = "",
    ) {
        companion object {
            const val PATCH = "patch"
            const val ADD = "add"
            const val DELETE = "delete"
        }
    }

    /** What the swapper has to do once this process exits. */
    data class Plan(
        /** staged file → its final place in the app directory. */
        val moves: List<Pair<Path, Path>>,
        /** files in the app directory that the new build no longer has. */
        val deletions: List<Path>,
        val stagingDir: Path,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Rebuilds every changed file of [appDir] into a staging directory and returns the [Plan] to
     * commit it. Throws when the bundle doesn't fit this installation — the caller then falls back
     * to the full installer.
     */
    fun stage(appDir: Path, bundle: Path, stagingDir: Path, tempDir: Path): Plan {
        Files.createDirectories(tempDir)
        if (Files.exists(stagingDir)) stagingDir.toFile().deleteRecursively()
        Files.createDirectories(stagingDir)

        ZipFile(bundle.toFile()).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST) ?: error("delta bundle has no $MANIFEST")
            val manifest = json.decodeFromString(
                Manifest.serializer(),
                // removePrefix("﻿"): tolerate a BOM, which is what most Windows tooling writes.
                zip.getInputStream(manifestEntry).use { it.readBytes().decodeToString() }
                    .removePrefix("﻿")
            )
            check(manifest.format == FORMAT) { "unsupported delta format ${manifest.format}" }

            val moves = mutableListOf<Pair<Path, Path>>()
            val deletions = mutableListOf<Path>()

            // Sorted so the classpath file lands last: if a move fails halfway, the installation is
            // still the old, working one (old jars are only removed afterwards).
            for (op in manifest.ops.sortedBy { it.op == Op.DELETE || it.to.endsWith(".cfg") }) {
                when (op.op) {
                    Op.PATCH -> {
                        val base = appDir.resolve(op.from).requireSafe(appDir)
                        check(base.exists()) { "installed file missing: ${op.from}" }
                        val baseSha = DesktopAppImage.sha256(base)
                        check(baseSha.equals(op.fromSha, ignoreCase = true)) {
                            "installed ${op.from} is not this patch's base"
                        }
                        val staged = stagingDir.resolve(op.to).requireSafe(stagingDir)
                        val entry = zip.getEntry(op.payload) ?: error("missing payload ${op.payload}")
                        Files.newOutputStream(staged).use { output ->
                            GZIPInputStream(BufferedInputStream(zip.getInputStream(entry))).use { patch ->
                                FileByFileV1DeltaApplier(tempDir.toFile())
                                    .applyDelta(base.toFile(), patch, output)
                            }
                        }
                        verify(staged, op.toSha, op.to)
                        moves += staged to appDir.resolve(op.to).requireSafe(appDir)
                        if (op.from != op.to) deletions.add(base)
                    }

                    Op.ADD -> {
                        val staged = stagingDir.resolve(op.to).requireSafe(stagingDir)
                        Files.createDirectories(staged.parent)
                        val entry = zip.getEntry(op.payload) ?: error("missing payload ${op.payload}")
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING)
                        }
                        verify(staged, op.toSha, op.to)
                        moves += staged to appDir.resolve(op.to).requireSafe(appDir)
                    }

                    Op.DELETE -> deletions.add(appDir.resolve(op.from).requireSafe(appDir))

                    else -> error("unknown delta operation '${op.op}'")
                }
            }
            return Plan(moves = moves, deletions = deletions, stagingDir = stagingDir)
        }
    }

    private fun verify(file: Path, expectedSha: String, name: String) {
        val sha = DesktopAppImage.sha256(file)
        check(sha.equals(expectedSha, ignoreCase = true)) {
            "rebuilt $name does not match the published file"
        }
    }

    /** Guards against a manifest path escaping the directory it is meant to write into. */
    private fun Path.requireSafe(root: Path): Path {
        val normalized = normalize()
        check(normalized.startsWith(root.normalize())) { "delta bundle path escapes the app directory" }
        return normalized
    }

    private const val MANIFEST = "manifest.json"
}
