package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path

class JvmDesktopSocksProxySettingsStore(
    private val file: Path = DesktopPaths.appDataDir().resolve("desktop_socks_proxy_settings.json")
) {
    suspend fun load(): DesktopSocksProxySettings {
        return runCatching {
            if (!Files.exists(file)) return DesktopSocksProxySettings()
            json.decodeFromString(DesktopSocksProxySettings.serializer(), Files.readString(file)).normalized()
        }.getOrDefault(DesktopSocksProxySettings())
    }

    suspend fun save(settings: DesktopSocksProxySettings) {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            json.encodeToString(DesktopSocksProxySettings.serializer(), settings.normalized())
        )
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
