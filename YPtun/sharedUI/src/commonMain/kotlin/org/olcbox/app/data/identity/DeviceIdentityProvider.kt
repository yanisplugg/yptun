package org.olcbox.app.data.identity

import kotlin.random.Random
import org.olcbox.app.data.datasource.LocationsDataSource

interface DeviceIdentityProvider {
    suspend fun hwid(): String
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

    private fun generateInstallId(): String {
        val random = Random.Default.nextBytes(16)
        return random.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
