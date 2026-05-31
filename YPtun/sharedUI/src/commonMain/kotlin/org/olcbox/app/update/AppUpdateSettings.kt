package org.olcbox.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateSettings(
    @SerialName("update_channel")
    val channel: ReleaseChannel = ReleaseChannel.Nightly,
    @SerialName("update_interval_hours")
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    @SerialName("last_update_check_at_epoch_ms")
    val lastCheckAtEpochMs: Long? = null,
    @SerialName("last_seen_update_version")
    val lastSeenUpdateVersion: String? = null,
    @SerialName("last_downloaded_update_version")
    val lastDownloadedUpdateVersion: String? = null
) {
    fun normalized(): AppUpdateSettings {
        return copy(
            channel = ReleaseChannel.Nightly,
            intervalHours = intervalHours.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
        )
    }

    companion object {
        const val MIN_INTERVAL_HOURS = 1
        const val MAX_INTERVAL_HOURS = 24
        const val DEFAULT_INTERVAL_HOURS = 24
        val INTERVAL_PRESETS = listOf(1, 6, 12, 24)
    }
}

interface AppUpdateSettingsStore {
    suspend fun load(): AppUpdateSettings
    suspend fun save(settings: AppUpdateSettings)
}

class InMemoryAppUpdateSettingsStore(
    initialSettings: AppUpdateSettings = AppUpdateSettings()
) : AppUpdateSettingsStore {
    var value: AppUpdateSettings = initialSettings.normalized()
        private set

    override suspend fun load(): AppUpdateSettings = value

    override suspend fun save(settings: AppUpdateSettings) {
        value = settings.normalized()
    }
}

fun AppUpdateSettings.isUpdateCheckDue(nowEpochMs: Long): Boolean {
    val lastCheck = lastCheckAtEpochMs ?: return true
    val intervalMs = intervalHours.coerceIn(
        AppUpdateSettings.MIN_INTERVAL_HOURS,
        AppUpdateSettings.MAX_INTERVAL_HOURS
    ).toLong() * 60L * 60L * 1_000L
    return nowEpochMs - lastCheck >= intervalMs
}

fun AppUpdateInfo.identity(): String {
    return listOf(
        channel.name,
        version,
        publishedAt.orEmpty(),
        asset.name,
        asset.sizeBytes?.toString().orEmpty(),
        asset.updatedAt.orEmpty()
    ).joinToString("|")
}

fun AppUpdateInfo.isDownloaded(settings: AppUpdateSettings): Boolean {
    return settings.lastDownloadedUpdateVersion == identity()
}

fun AppUpdateInfo.shouldShowOffer(settings: AppUpdateSettings): Boolean {
    return isUpdateAvailable &&
            !isDownloaded(settings) &&
            settings.lastSeenUpdateVersion != identity()
}

fun AppUpdateInfo.shouldShowOffer(settings: AppUpdateSettings, nowEpochMs: Long): Boolean {
    return isUpdateAvailable &&
            !isDownloaded(settings) &&
            (settings.lastSeenUpdateVersion != identity() || settings.isUpdateCheckDue(nowEpochMs))
}
