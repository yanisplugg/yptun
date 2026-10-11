package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.DesktopRuntimeMode
import org.olcbox.app.desktop.appendToYptunLog
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

/** What [JvmUpdateInstaller.install] actually did, so the caller knows whether to restart. */
sealed interface DesktopUpdateOutcome {
    /** The full installer was downloaded and handed to the OS; the user drives it from here. */
    data class InstallerOpened(val message: String) : DesktopUpdateOutcome

    /** A delta was applied and staged. The app must shut down cleanly and exit for it to land. */
    data class RestartRequired(val message: String) : DesktopUpdateOutcome
}

class JvmUpdateInstaller(
    private val directory: Path = DesktopPaths.appDataDir().resolve("updates")
) {
    /**
     * Installs [info], preferring a binary delta.
     *
     * The delta path downloads a few-MB bundle and rebuilds the changed files of the installed app
     * image locally instead of pulling the ~160 MB installer. It is refused unless the installation
     * is exactly the one the bundle was built against AND every rebuilt file matches the published
     * one byte for byte (see [DesktopDeltaPatch]), so any mismatch, any I/O failure and any
     * non-app-image run simply falls through to the full download below.
     */
    suspend fun install(
        info: AppUpdateInfo,
        onStage: (UpdateStage) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): Result<DesktopUpdateOutcome> = runCatching {
        // A portable has no installed app image to patch and no installer to run: the new single-file
        // portable .exe replaces the one the user started, then the app restarts from it.
        if (DesktopRuntimeMode.isPortable && info.asset.isPortableExe()) {
            return@runCatching replacePortable(info.asset, onStage, onProgress)
        }
        info.deltaAsset?.let { delta ->
            val staged = runCatching { applyDelta(delta, onStage, onProgress) }
                // Why a ~230 MB installer is being pulled instead of a few-MB bundle used to be
                // invisible — the failure was swallowed. yptun.log is the first place anyone looks.
                .onFailure { appendToYptunLog("update: delta ${delta.name} not applied: ${it.message}") }
                .getOrNull()
            if (staged != null) return@runCatching staged
        }
        if (info.deltaAsset == null) {
            appendToYptunLog("update: no delta bundle for this install — downloading ${info.asset.name} in full")
        }
        installFull(info.asset, onStage, onProgress)
    }

    /**
     * The reserve path when no delta applies (or it failed). On Windows the installer is run silently
     * and YPtun restarts by itself; elsewhere (and for anything that isn't a plain installer) it is
     * handed to the OS as before.
     */
    private suspend fun installFull(
        asset: AppUpdateAsset,
        onStage: (UpdateStage) -> Unit,
        onProgress: (Float) -> Unit
    ): DesktopUpdateOutcome {
        val silent = DesktopPaths.os == DesktopOs.Windows && !asset.isPortableExe() &&
            asset.name.lowercase().let { it.endsWith(".exe") || it.endsWith(".msi") }
        if (!silent) return DesktopUpdateOutcome.InstallerOpened(openInstaller(asset, onProgress))
        return withContext(Dispatchers.IO) {
            reportStage(UpdateStage(UpdateStageKind.DownloadInstaller, 1, 2), onStage)
            val file = download(asset, onProgress)
            reportStage(UpdateStage(UpdateStageKind.Install, 2, 2), onStage)
            DesktopSelfUpdate.scheduleInstaller(file)
            DesktopUpdateOutcome.RestartRequired("Update ready - installing and restarting YPtun")
        }
    }

    private suspend fun replacePortable(
        asset: AppUpdateAsset,
        onStage: (UpdateStage) -> Unit,
        onProgress: (Float) -> Unit
    ): DesktopUpdateOutcome = withContext(Dispatchers.IO) {
        reportStage(UpdateStage(UpdateStageKind.DownloadInstaller, 1, 2), onStage)
        val file = download(asset, onProgress)
        reportStage(UpdateStage(UpdateStageKind.Install, 2, 2), onStage)
        val target = DesktopRuntimeMode.portableExecutable()
        if (target == null) {
            appendToYptunLog("update: portable .exe path unknown - running the downloaded ${file.fileName} instead")
        }
        DesktopSelfUpdate.scheduleReplacePortable(file, target)
        DesktopUpdateOutcome.RestartRequired("Update ready - restarting YPtun")
    }

    private fun AppUpdateAsset.isPortableExe(): Boolean =
        name.lowercase().let { "portable" in it && it.endsWith(".exe") }

    /** Downloads [asset] and hands it to the OS (the pre-delta behaviour, kept for the full path). */
    suspend fun downloadAndOpen(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = runCatching { openInstaller(asset, onProgress) }

    private suspend fun openInstaller(asset: AppUpdateAsset, onProgress: (Float) -> Unit): String {
        val file = download(asset, onProgress)
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file.toFile())
            desktop?.isSupported(Desktop.Action.BROWSE) == true -> desktop.browse(URI(asset.downloadUrl))
            else -> error("No system file handler available for ${asset.name}")
        }
        return "Opening ${asset.name}"
    }

    private suspend fun applyDelta(
        delta: AppUpdateAsset,
        onStage: (UpdateStage) -> Unit,
        onProgress: (Float) -> Unit
    ): DesktopUpdateOutcome.RestartRequired? = withContext(Dispatchers.IO) {
        val rootDir = DesktopAppImage.installDir()
            ?: error("not running from an installed app image (portable or development run)")
        reportStage(UpdateStage(UpdateStageKind.DownloadPatch, 1, 3), onStage)
        val bundle = download(delta, onProgress)
        reportStage(UpdateStage(UpdateStageKind.ApplyPatch, 2, 3), onStage)
        reportProgress(0f, onProgress)
        // Staged INSIDE the app directory when we may write there, so every commit is a rename on
        // the same volume. When we may NOT — a deb install under root-owned /opt/yptun, or Program
        // Files without elevation — creating that directory threw and the delta died here, before
        // the swapper (which knows how to elevate) was ever reached. Stage in our own data
        // directory instead and let the elevated swapper move the files in; a cross-volume `mv` is
        // a copy, but the commit order already tolerates a half-finished swap: new jars carry new
        // names, the classpath file moves last, and old files are removed only after that.
        val stagingDir = if (Files.isWritable(rootDir)) {
            rootDir.resolve(".yptun-update")
        } else {
            directory.resolve("staging")
        }
        val plan = try {
            DesktopDeltaPatch.stage(
                rootDir = rootDir,
                bundle = bundle,
                stagingDir = stagingDir,
                tempDir = directory.resolve("patch-tmp"),
                onStep = { done, total -> reportProgressBlocking(done.toFloat() / total, onProgress) }
            )
        } catch (e: Exception) {
            runCatching { stagingDir.toFile().deleteRecursively() }
            throw e
        } finally {
            bundle.deleteIfExists()
            runCatching { directory.resolve("patch-tmp").toFile().deleteRecursively() }
        }
        reportProgress(1f, onProgress)
        reportStage(UpdateStage(UpdateStageKind.Restart, 3, 3), onStage)
        DesktopSelfUpdate.scheduleSwap(plan)
        DesktopUpdateOutcome.RestartRequired("Update ready — restarting YPtun")
    }

    private suspend fun download(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = directory.resolve(asset.name.substringAfterLast('/').ifBlank { "olcbox-update" })
        val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes ?: -1L
        var copied = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0L) {
                        reportProgress(
                            (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                            onProgress
                        )
                    }
                }
            }
            // A connection dropped mid-stream ends as a clean EOF, not an exception. Without this the
            // truncated file was handed to the OS as an installer, or to the patcher as a bundle.
            if (total > 0L && copied != total) {
                target.deleteIfExists()
                error("Download interrupted (${copied / 1_048_576} of ${total / 1_048_576} MB) — try again")
            }
        }
        // GitHub publishes a SHA-256 for every release asset. Nothing downloaded here is run, applied or
        // swapped in unless it matches - a corrupted or tampered file is deleted instead.
        asset.sha256?.let { expected ->
            val actual = DesktopAppImage.sha256(target)
            if (!actual.equals(expected, ignoreCase = true)) {
                target.deleteIfExists()
                error("Checksum mismatch for ${asset.name} - the download is corrupt, try again")
            }
        } ?: appendToYptunLog("update: no SHA-256 published for ${asset.name} - integrity not verified")
        reportProgress(1f, onProgress)
        target
    }

    private suspend fun reportStage(stage: UpdateStage, onStage: (UpdateStage) -> Unit) {
        withContext(Dispatchers.Main.immediate) { onStage(stage) }
    }

    /** For the patcher's non-suspending callback (it already runs on an IO thread). */
    private fun reportProgressBlocking(progress: Float, onProgress: (Float) -> Unit) {
        runBlocking(Dispatchers.Main.immediate) { onProgress(progress) }
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
