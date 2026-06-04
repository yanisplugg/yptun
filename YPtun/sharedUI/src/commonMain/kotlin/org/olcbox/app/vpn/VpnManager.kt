package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.repository.SubscriptionFetchProxy

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>
    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null

    /**
     * Imports a `happ://routing/add/...` link as a routing profile. Returns true when [link] was a
     * routing link (and was handled), so callers can short-circuit normal config import. Platforms
     * without routing-profile support leave the default no-op.
     */
    fun importRoutingProfileLink(link: String): Boolean = false

    /** Current routing profiles, for the per-location selector. Empty on platforms without support. */
    fun routingProfileChoices(): List<RoutingProfile> = emptyList()
}
