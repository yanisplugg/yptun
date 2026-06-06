package org.olcbox.app.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the bundled Zygisk VPN-hide Magisk module (id [MODULE_ID]). The module hides VPN
 * interfaces (tun/ppp/tap/wg/awg) from other apps by hooking getifaddrs in their processes.
 *
 * The "Hide tun0" experimental toggle drives this: enabling installs the module from assets (or
 * re-enables it if already present), disabling flags it off. Zygisk loads modules at boot, so a
 * reboot is required after an install/enable for hiding to take effect ([Result.needsReboot]).
 */
object VpnHideModule {
    const val MODULE_ID = "olcvpnhide"
    private const val ASSET = "olcvpnhide.zip"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    enum class Result(val needsReboot: Boolean) {
        INSTALLED(true),       // freshly installed → reboot to load
        ENABLED(true),         // was present-but-disabled → reboot to load
        ALREADY_ACTIVE(false), // already installed & enabled
        NO_ROOT(false),        // su unavailable / denied
        FAILED(false),         // extraction or magisk install failed
    }

    /** Install (from assets) or re-enable the module. Safe to call repeatedly. */
    suspend fun enable(context: Context): Result = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, ASSET)
        val extracted = runCatching {
            context.assets.open(ASSET).use { input -> zip.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        if (!extracted) return@withContext Result.FAILED
        // World-readable so the root shell / magisk can read it from our cache dir.
        runCatching { zip.setReadable(true, false) }

        val script = buildString {
            append("MOD=$MODULE_DIR; ")
            append("if [ -d \"\$MOD\" ]; then ")
            append("if [ -f \"\$MOD/disable\" ] || [ -f \"\$MOD/remove\" ]; then ")
            append("rm -f \"\$MOD/disable\" \"\$MOD/remove\" && echo ENABLED || echo FAILED; ")
            append("else echo ALREADY; fi; ")
            append("else ")
            append("magisk --install-module \"${zip.absolutePath}\" >/dev/null 2>&1 && echo INSTALLED || echo FAILED; ")
            append("fi")
        }
        val out = runSu(script) ?: return@withContext Result.NO_ROOT
        when {
            out.contains("INSTALLED") -> Result.INSTALLED
            out.contains("ENABLED") -> Result.ENABLED
            out.contains("ALREADY") -> Result.ALREADY_ACTIVE
            else -> Result.FAILED
        }
    }

    /** Flag the module disabled (takes effect after the next reboot). */
    suspend fun disable(context: Context): Boolean = withContext(Dispatchers.IO) {
        runSu("MOD=$MODULE_DIR; [ -d \"\$MOD\" ] && touch \"\$MOD/disable\"; echo OK") != null
    }

    /** Reboot the device (requires root). */
    suspend fun reboot(): Unit = withContext(Dispatchers.IO) {
        runSu("/system/bin/svc power reboot || /system/bin/reboot")
        Unit
    }

    private fun runSu(script: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    }.getOrNull()
}
