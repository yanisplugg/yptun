package org.olcbox.app.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.olcbox.app.data.LEGACY_LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class JvmLocationsDataSourceImpl(
    private val appDir: Path = DesktopPaths.appDataDir()
) : LocationsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val bundleFile: Path
        get() = appDir.resolve(LOCATIONS_BUNDLE_FILE_NAME)

    private val legacyBundleFile: Path
        get() = appDir.resolve(LEGACY_LOCATIONS_BUNDLE_FILE_NAME)

    private val deviceIdentityFile: Path
        get() = appDir.resolve("device_identity")

    override suspend fun loadLocationBundle(): LocationBundleV4? = withContext(Dispatchers.IO) {
        val file = bundleFile.takeIf { it.exists() } ?: legacyBundleFile.takeIf { it.exists() }
            ?: return@withContext null
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(LocationBundleV4.serializer(), file.readText()).normalized()
        }.getOrNull()
    }

    override suspend fun saveLocationBundle(bundle: LocationBundleV4): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(appDir)
        bundleFile.writeText(
            json.encodeToString(LocationBundleV4.serializer(), bundle.normalized())
        )
    }

    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()

    override suspend fun loadLegacyActiveLocationId(): String? = null

    override suspend fun loadDeviceIdentity(): String? = withContext(Dispatchers.IO) {
        deviceIdentityFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.ifBlank { null }
    }

    override suspend fun saveDeviceIdentity(value: String): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(appDir)
        deviceIdentityFile.writeText(value.trim())
    }
}
