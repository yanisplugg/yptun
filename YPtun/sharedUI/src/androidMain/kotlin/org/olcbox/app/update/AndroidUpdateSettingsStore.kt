package org.olcbox.app.update

import android.content.Context

class AndroidUpdateSettingsStore(context: Context) : AppUpdateSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "olcbox_update_settings",
        Context.MODE_PRIVATE
    )

    override suspend fun load(): AppUpdateSettings {
        val channel = preferences.getString(KEY_CHANNEL, null)
            ?.let { value -> runCatching { ReleaseChannel.valueOf(value) }.getOrNull() }
            ?: ReleaseChannel.Stable

        val lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L)
            .takeIf { it > 0L }

        return AppUpdateSettings(
            channel = channel,
            intervalHours = preferences.getInt(
                KEY_INTERVAL_HOURS,
                AppUpdateSettings.DEFAULT_INTERVAL_HOURS
            ),
            lastCheckAtEpochMs = lastCheck,
            lastSeenUpdateVersion = preferences.getString(KEY_LAST_SEEN, null),
            lastDownloadedUpdateVersion = preferences.getString(KEY_LAST_DOWNLOADED, null)
        ).normalized()
    }

    override suspend fun save(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putString(KEY_CHANNEL, normalized.channel.name)
            .putInt(KEY_INTERVAL_HOURS, normalized.intervalHours)
            .putLong(KEY_LAST_CHECK, normalized.lastCheckAtEpochMs ?: 0L)
            .putString(KEY_LAST_SEEN, normalized.lastSeenUpdateVersion)
            .putString(KEY_LAST_DOWNLOADED, normalized.lastDownloadedUpdateVersion)
            .apply()
    }

    private companion object {
        const val KEY_CHANNEL = "update_channel"
        const val KEY_INTERVAL_HOURS = "update_interval_hours"
        const val KEY_LAST_CHECK = "last_update_check_at_epoch_ms"
        const val KEY_LAST_SEEN = "last_seen_update_version"
        const val KEY_LAST_DOWNLOADED = "last_downloaded_update_version"
    }
}
