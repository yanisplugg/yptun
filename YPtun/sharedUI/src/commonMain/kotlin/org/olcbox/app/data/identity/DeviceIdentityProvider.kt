package org.olcbox.app.data.identity

import kotlin.random.Random
import org.olcbox.app.data.datasource.LocationsDataSource

interface DeviceIdentityProvider {
    suspend fun hwid(): String

    /**
     * Stable, app-specific install id sent as the `x-app-id` (goiID) header on subscription requests.
     * Distinct from [hwid] (which is platform-stable / ANDROID_ID-derived): this is a random per-install
     * identifier the panel owner can register/track for OUR app (e.g. in Remnawave) and use to target
     * announcements. Generated once on first use and reused thereafter.
     */
    suspend fun appId(): String
}

class PersistentDeviceIdentityProvider(
    private val dataSource: LocationsDataSource
) : DeviceIdentityProvider {
    override suspend fun hwid(): String {
        dataSource.loadDeviceIdentity()?.let { existing ->
            // Migrate legacy ids that had an "install-" prefix.
            val cleaned = existing.removePrefix("install-")
            if (cleaned != existing) dataSource.saveDeviceIdentity(cleaned)
            return cleaned
        }

        // Prefer a platform-stable id (e.g. Android ANDROID_ID) so the HWID survives
        // reinstalls/data-clears — closer to how v2rayTun/Hiddify report devices.
        val stable = dataSource.platformStableId()?.takeIf { it.isNotBlank() }
        val identity = stable ?: generateInstallId()
        dataSource.saveDeviceIdentity(identity)
        return identity
    }

    override suspend fun appId(): String {
        dataSource.loadAppInstallId()?.takeIf { it.isNotBlank() }?.let { return it }
        val identity = generateInstallId()
        dataSource.saveAppInstallId(identity)
        return identity
    }

    private fun generateInstallId(): String {
        val random = Random.Default.nextBytes(16)
        return random.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
