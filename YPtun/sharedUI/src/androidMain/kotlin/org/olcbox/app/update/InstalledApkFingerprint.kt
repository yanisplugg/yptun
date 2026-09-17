package org.olcbox.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 of the APK this process is running from — the file a delta patch has to be applied to.
 *
 * Published patches name the base they were generated against (see
 * [AppUpdateService.deltaBaseHashToken]), so comparing it against this value is what decides
 * up front whether a patch can possibly apply. Before that check the app picked a patch by ABI
 * name alone and happily downloaded one built against a different APK — a universal install, or
 * a locally built version with the same versionName — which always failed and always ended in the
 * full ~112 MB download.
 *
 * Hashing 112 MB costs a few hundred ms, so the result is cached for as long as the file it was
 * taken from keeps its size and mtime (an update replaces the process anyway).
 */
object InstalledApkFingerprint {

    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedSha: String? = null

    suspend fun of(context: Context): String? = withContext(Dispatchers.IO) {
        val apk = runCatching { File(context.applicationInfo.sourceDir) }.getOrNull()
        if (apk == null || !apk.isFile || apk.length() <= 0L) return@withContext null
        val key = "${apk.absolutePath}:${apk.length()}:${apk.lastModified()}"
        mutex.withLock {
            cachedSha?.takeIf { cachedKey == key }?.let { return@withLock it }
            val sha = runCatching { sha256(apk) }
                .onFailure { Log.w(TAG, "cannot hash the installed APK: ${it.message}") }
                .getOrNull()
            cachedKey = key
            cachedSha = sha
            sha
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val TAG = "YPtunUpdate"
}
