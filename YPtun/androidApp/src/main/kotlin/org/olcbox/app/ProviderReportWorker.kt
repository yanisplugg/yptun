package org.olcbox.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl

/**
 * Background daily provider-usage report (Happ `providerid`). Runs even when the app UI is closed, so
 * a subscription with a providerid is reported once per day regardless of whether the user opens the
 * app. The repository de-dupes per calendar day via persisted state, so an extra run is harmless. Any
 * failure is retried by WorkManager. No-op when no subscription carries a providerid.
 */
class ProviderReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext)).reportProviderUsage()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
