package org.olcbox.app.update

import android.content.Context
import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Applies a gzip-compressed File-by-File v1 binary patch (Google archive-patcher, vendored in
 * `:archivepatcher`) to the user's currently-installed APK to reconstruct the new release APK —
 * byte-identical to the published one, so it keeps the original signature. This is the on-device
 * half of the delta-update feature; the patch is generated host-side at release time.
 */
object DeltaApkPatcher {

    fun apply(context: Context, baseApk: File, patchGz: File, outApk: File): File {
        val tempDir = File(context.cacheDir, "updates/patch-tmp").apply { mkdirs() }
        try {
            GZIPInputStream(BufferedInputStream(patchGz.inputStream())).use { patchIn ->
                FileOutputStream(outApk).use { out ->
                    // applyDelta(oldBlob, rawDeltaIn, newBlobOut) — the gz is the transport wrapper.
                    FileByFileV1DeltaApplier(tempDir).applyDelta(baseApk, patchIn, out)
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
        return outApk
    }
}
