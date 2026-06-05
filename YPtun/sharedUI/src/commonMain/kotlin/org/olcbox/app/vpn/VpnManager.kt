package org.olcbox.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.repository.SubscriptionFetchProxy

private val ZERO_CONNECTED_SINCE: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

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

    /**
     * Wall-clock epoch-ms when the current connection started (0 = not connected). Backed by a
     * process-global value so the on-screen timer keeps counting from the real start across an
     * Activity close/reopen while the VPN service stays up. Defaults to a constant 0 flow.
     */
    val connectedSinceEpochMs: StateFlow<Long> get() = ZERO_CONNECTED_SINCE
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
