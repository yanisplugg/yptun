package org.olcbox.app.data.datasource

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.olcbox.app.data.LEGACY_LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.LOCATIONS_BUNDLE_FILE_NAME
import org.olcbox.app.data.model.LocationBundleV4
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLocationsDataSourceImpl(
    appDirectoryName: String = "Olcbox"
) : LocationsDataSource {

    private val appDir: String = applicationSupportDirectory(appDirectoryName)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val bundlePath: String
        get() = appDir.childPath(LOCATIONS_BUNDLE_FILE_NAME)

    private val legacyBundlePath: String
        get() = appDir.childPath(LEGACY_LOCATIONS_BUNDLE_FILE_NAME)

    private val deviceIdentityPath: String
        get() = appDir.childPath("device_identity")

    override suspend fun loadLocationBundle(): LocationBundleV4? = withContext(Dispatchers.Default) {
        val file = bundlePath.takeIf { fileExists(it) }
            ?: legacyBundlePath.takeIf { fileExists(it) }
            ?: return@withContext null

        runCatching {
            json.decodeFromString(LocationBundleV4.serializer(), readText(file).orEmpty()).normalized()
        }.getOrNull()
    }

    override suspend fun saveLocationBundle(bundle: LocationBundleV4): Unit = withContext(Dispatchers.Default) {
        ensureDirectory(appDir)
        writeText(
            bundlePath,
            json.encodeToString(LocationBundleV4.serializer(), bundle.normalized())
        )
    }

    override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()

    override suspend fun loadLegacyActiveLocationId(): String? = null

    override suspend fun loadDeviceIdentity(): String? = withContext(Dispatchers.Default) {
        readText(deviceIdentityPath)?.trim()?.ifBlank { null }
    }

    override suspend fun saveDeviceIdentity(value: String): Unit = withContext(Dispatchers.Default) {
        ensureDirectory(appDir)
        writeText(deviceIdentityPath, value.trim())
    }

    private fun readText(path: String): String? {
        if (!fileExists(path)) return null
        return NSString.create(
            contentsOfFile = path,
            encoding = NSUTF8StringEncoding,
            error = null
        )?.toString()
    }

    private fun writeText(path: String, value: String) {
        NSString.create(string = value).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null
        )
    }

    private fun fileExists(path: String): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    private fun applicationSupportDirectory(appDirectoryName: String): String {
        val base = NSFileManager.defaultManager
            .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull()
            ?.let { it as? NSURL }
            ?.path
            ?: error("Application Support directory is unavailable")
        val path = base.childPath(appDirectoryName)
        ensureDirectory(path)
        return path
    }

    private fun ensureDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }

    private fun String.childPath(child: String): String {
        return trimEnd('/') + "/" + child
    }
}
