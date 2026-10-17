package org.olcbox.app

import android.app.Application
import android.content.Context
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.olcbox.app.migration.LegacyMigration
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider {
    companion object {
        lateinit var appContext: Context
        private const val PROVIDER_REPORT_WORK = "provider-usage-report"
    }

    // On-demand WorkManager configuration (its automatic startup initializer is removed in the
    // manifest). Used the first time WorkManager.getInstance() is called, from onCreate below.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // The Go cores (xray/sing-box/awg/...) share one runtime that is loaded lazily AFTER this point and
        // reads these at start-up. Default GOGC=100 with no limit let its heap grow to several hundred MB
        // (ping/free-server searches spin up many core instances) and keep it. GOGC=50 collects sooner; the
        // memory limit is SOFT (the GC just works harder near it), so it never kills a core.
        runCatching {
            android.system.Os.setenv("GOGC", "50", false)
            android.system.Os.setenv("GOMEMLIMIT", "400MiB", false)
        }
        appContext = applicationContext
        keepOffPrimeCores()
        // Переезд со старого applicationId: копируем состояние из прошлой установки ДО того, как
        // что-либо тронет DataStore (иначе он закеширует пустые настройки, и подложенный файл
        // подхватится только со следующего запуска). Синхронно и один раз за установку; когда
        // переезжать не с чего - выходит сразу, ещё до IPC.
        runCatching { LegacyMigration.runIfNeeded(this) }
        // Use the bundled emoji font so country-flag emojis render everywhere
        // (Compose Text picks up EmojiCompat automatically once initialized).
        EmojiCompat.init(BundledEmojiCompatConfig(this))
        scheduleProviderReport()
        org.olcbox.app.ui.activities.AppIcons.ensureLauncherEntry(this)
    }

    /**
     * Keeps the NON-UI threads of the app (the VPN service work and the Go cores) off the fastest cores:
     * the prime cores (e.g. 2 x 4.7 GHz on a Snapdragon 8 Elite) burn far more power per cycle and a tunnel
     * does not need them. The main thread and the render threads stay unpinned so animations stay smooth.
     * Mask = every CPU slower than the fastest one; nothing happens on chips with identical cores or when
     * sysfs is unreadable. Threads are pinned one by one (`taskset -p <mask> <tid>`) and the sweep repeats,
     * because the Go runtime and Kotlin pools keep creating threads after start-up.
     */
    private fun keepOffPrimeCores() {
        Thread {
            runCatching {
                val freqs = (0 until Runtime.getRuntime().availableProcessors()).map {
                    java.io.File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }
                val fastest = freqs.max()
                val mask = freqs.indices.filter { freqs[it] < fastest }.fold(0L) { m, i -> m or (1L shl i) }
                if (mask == 0L) return@runCatching
                val pid = android.os.Process.myPid()
                var waited = 0L
                for (at in listOf(3L, 15L, 45L, 120L, 300L, 600L, 900L)) {
                    Thread.sleep((at - waited) * 1_000L)
                    waited = at
                    val tids = java.io.File("/proc/self/task").listFiles().orEmpty().mapNotNull { dir ->
                        val tid = dir.name.toIntOrNull() ?: return@mapNotNull null
                        val name = runCatching { java.io.File(dir, "comm").readText().trim() }.getOrDefault("")
                        // main = the UI thread; RenderThread / hwuiTask = hardware-accelerated drawing.
                        tid.takeUnless { it == pid || name == "RenderThread" || name.startsWith("hwuiTask") }
                    }
                    if (tids.isEmpty()) continue
                    val script = tids.joinToString("; ") { "taskset -p ${mask.toString(16)} $it" }
                    ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
                        .also { it.inputStream.readBytes(); it.waitFor() }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Daily background provider-usage report (Happ `providerid`). KEEP so an already-scheduled job
     * isn't reset on every launch; requires network. No-op at runtime when no subscription carries a
     * providerid. Best-effort — never let a scheduling failure crash app startup.
     */
    private fun scheduleProviderReport() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<ProviderReportWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                PROVIDER_REPORT_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
