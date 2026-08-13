package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
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
     * The delta path downloads a few-MB patch and rebuilds the installed application jar locally
     * instead of pulling the ~160 MB installer. It is refused unless the installed jar is exactly
     * the patch's base AND the result matches the published jar byte for byte (see
     * [DesktopDeltaPatch]), so any mismatch, any I/O failure and any non-app-image installation
     * simply falls through to the full download below.
     */
    suspend fun install(
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<DesktopUpdateOutcome> = runCatching {
        info.deltaAsset?.let { delta ->
            val staged = runCatching { applyDelta(delta, onProgress) }.getOrNull()
            if (staged != null) return@runCatching staged
        }
        DesktopUpdateOutcome.InstallerOpened(openInstaller(info.asset, onProgress))
    }

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
        onProgress: (Float) -> Unit
    ): DesktopUpdateOutcome.RestartRequired? = withContext(Dispatchers.IO) {
        val targetJar = DesktopAppImage.installedAppJar() ?: return@withContext null
        val patch = download(delta, onProgress)
        // Staged NEXT TO the installed jar when possible so the final move is a rename on the same
        // volume; the swapper falls back to a cross-volume copy otherwise.
        val staged = targetJar.resolveSibling(targetJar.fileName.toString() + ".new")
        try {
            DesktopDeltaPatch.apply(
                baseJar = targetJar,
                patchFile = patch,
                target = staged,
                tempDir = directory.resolve("patch-tmp")
            )
        } catch (e: Exception) {
            staged.deleteIfExists()
            throw e
        } finally {
            patch.deleteIfExists()
            runCatching { directory.resolve("patch-tmp").toFile().deleteRecursively() }
        }
        DesktopSelfUpdate.scheduleSwap(stagedJar = staged, targetJar = targetJar)
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
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
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
        }
        reportProgress(1f, onProgress)
        target
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
