package org.olcbox.app.update

import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path

class JvmUpdateSettingsStore(
    private val file: Path = DesktopPaths.appDataDir().resolve("update_settings.json")
) : AppUpdateSettingsStore {
    override suspend fun load(): AppUpdateSettings {
        return runCatching {
            if (!Files.exists(file)) return AppUpdateSettings()
            json.decodeFromString(AppUpdateSettings.serializer(), Files.readString(file)).normalized()
        }.getOrDefault(AppUpdateSettings())
    }

    override suspend fun save(settings: AppUpdateSettings) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(AppUpdateSettings.serializer(), settings.normalized()))
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
