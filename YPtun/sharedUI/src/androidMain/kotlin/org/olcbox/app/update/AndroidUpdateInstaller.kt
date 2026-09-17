package org.olcbox.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class AndroidUpdateInstaller(
    context: Context,
    private val proxyProvider: () -> SubscriptionFetchProxy? = { null }
) {
    private val appContext = context.applicationContext

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                appContext.packageManager.canRequestPackageInstalls()
    }

    fun unknownSourcesSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openUnknownSourcesSettings(): Result<Unit> = runCatching {
        appContext.startActivity(unknownSourcesSettingsIntent())
    }

    suspend fun downloadAndOpen(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        return runCatching {
            if (!canRequestPackageInstalls()) {
                openUnknownSourcesSettings().getOrThrow()
                return@runCatching "Allow YPtun to install updates, then tap Download again"
            }

            val file = download(asset, onProgress).getOrThrow()
            // Update-channel hardening: never hand the OS an APK that isn't signed with the official
            // YPtun key (a MITM on the download could otherwise swap in a malicious build). The OS
            // install would reject a mismatched signature anyway, but we abort early + delete it.
            requireOfficialApk(file)
            val installIntent = installIntent(file)
            try {
                appContext.startActivity(installIntent)
            } catch (error: ActivityNotFoundException) {
                val uri = uriFor(file)
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType(file.name))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            "Installing ${asset.name}"
        }
    }

    fun installIntent(file: File): Intent {
        return installIntent(uriFor(file), file.name)
    }

    fun relaunchIntent(): Intent? {
        return appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun installIntent(uri: Uri, name: String): Intent {
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            setDataAndType(uri, mimeType(name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun uriFor(file: File): Uri {
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
    }

    /**
     * Produces the verified, ready-to-install new APK. Prefers a binary delta when one is published
     * (downloads a few-MB patch + reconstructs the APK locally from the installed one); falls back to
     * a full download on any delta failure (no patch, base APK mismatch, apply error). In BOTH paths
     * the resulting APK's signature is checked against the official key before it is returned, so a
     * MITM-swapped download or a bad reconstruction can never reach the installer.
     */
    suspend fun resolveUpdateApk(
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = runCatching {
        info.deltaAsset?.let { delta ->
            val patched = runCatching { applyDeltaUpdate(delta, onProgress) }
                // Why a full APK is being downloaded instead of a 1.5 MB patch used to be
                // invisible: the failure was swallowed and the user just saw a 112 MB download.
                .onFailure { Log.w(TAG, "delta ${delta.name} not applied: ${it.message}") }
                .getOrNull()
            if (patched != null) return@runCatching patched
            // Any delta failure → fall through to the full download below.
        }
        if (info.deltaAsset == null) {
            Log.i(TAG, "no delta patch for this install — downloading ${info.asset.name} in full")
        }
        val full = download(info.asset, onProgress).getOrThrow()
        requireOfficialApk(full)
        full
    }

    /** Unreadable signature = damaged file (a bad download), not a forged one — say so separately. */
    private fun requireOfficialApk(apk: File) {
        val sha = org.olcbox.app.security.IntegrityGuard.apkSigningSha256(appContext, apk)
        if (sha.equals(org.olcbox.app.security.IntegrityGuard.OFFICIAL_SIGNING_SHA256, ignoreCase = true)) return
        apk.delete()
        if (sha == null) error("Downloaded APK is damaged — try again or download from GitHub")
        error("Update signature mismatch — download rejected for safety")
    }

    private suspend fun applyDeltaUpdate(
        delta: AppUpdateAsset,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val baseApk = File(appContext.applicationInfo.sourceDir)
        require(baseApk.exists() && baseApk.length() > 0) { "installed base APK not found" }
        val patchGz = download(delta, onProgress).getOrThrow()
        val outApk = File(File(appContext.cacheDir, "updates").apply { mkdirs() }, "yptun-delta-update.apk")
        try {
            DeltaApkPatcher.apply(appContext, baseApk, patchGz, outApk)
        } finally {
            patchGz.delete()
        }
        if (!org.olcbox.app.security.IntegrityGuard.isOfficialApk(appContext, outApk)) {
            outApk.delete()
            error("Reconstructed APK failed signature check")
        }
        outApk
    }

    suspend fun download(asset: AppUpdateAsset, onProgress: (Float) -> Unit): Result<File> = runCatching {
        val proxy = proxyProvider()
        withProxyAuthentication(proxy) {
            downloadFile(asset, onProgress, proxy)
        }
    }

    private suspend fun downloadFile(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit,
        proxy: SubscriptionFetchProxy?
    ): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, "updates").apply {
            mkdirs()
        }
        val fileName = asset.name.substringAfterLast('/').ifBlank { "olcbox-update.apk" }
        val target = File(directory, fileName)
        val connection = if (proxy == null) {
            URL(asset.downloadUrl).openConnection()
        } else {
            URL(asset.downloadUrl).openConnection(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port))
            )
        } as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP $code while downloading ${asset.name}")
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
        }
        // A connection closed mid-stream on mobile data ends as a clean EOF, not an exception —
        // without this the truncated APK reached the signature check and was reported as a mismatch.
        if (total > 0L && copied != total) {
            target.delete()
            error("Download interrupted (${copied / 1_048_576} of ${total / 1_048_576} MB) — try again")
        }
        reportProgress(1f, onProgress)
        target
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }

    private companion object {
        const val TAG = "YPtunUpdate"
    }

    private fun mimeType(name: String): String {
        return when {
            name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
