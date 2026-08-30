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
        appContext = applicationContext
        // Переезд со старого applicationId: копируем состояние из прошлой установки ДО того, как
        // что-либо тронет DataStore (иначе он закеширует пустые настройки, и подложенный файл
        // подхватится только со следующего запуска). Синхронно и один раз за установку; когда
        // переезжать не с чего - выходит сразу, ещё до IPC.
        runCatching { LegacyMigration.runIfNeeded(this) }
        // Use the bundled emoji font so country-flag emojis render everywhere
        // (Compose Text picks up EmojiCompat automatically once initialized).
        EmojiCompat.init(BundledEmojiCompatConfig(this))
        scheduleProviderReport()
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
