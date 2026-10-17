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
    fun clearLogs() {}

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

    /**
     * [ping] in the user's ping mode, but a server only counts as alive when a request actually goes
     * THROUGH its proxy: TCP/ICMP/Auto prove just an open port, and free servers are full of hosts that
     * accept TCP and pass nothing. Platforms without a via-proxy probe fall back to plain [ping].
     */
    suspend fun pingVerified(locationConfig: LocationConfig): Long? = ping(locationConfig)

    /** One probe's timeout and how many times [ping] measures (settings «Пинг»); platforms without those settings keep the old numbers. */
    val pingTimeoutMs: Long get() = 6_000L
    val pingRounds: Int get() = 1

    /** Settings «Пинг»: take the free-servers list from the aggregated GitHub Pages file instead of the built-in sources. */
    val freeServersFromPages: Boolean get() = false

    /** The whole-location budget callers wrap [ping] in (it used to be a fixed 12 s). */
    val pingBudgetMs: Long get() = maxOf(12_000L, pingTimeoutMs * pingRounds + 3_000L)

    /** Same for [pingVerified], which adds one more via-proxy request (it used to be a fixed 15 s). */
    val pingVerifiedBudgetMs: Long get() = maxOf(15_000L, pingTimeoutMs * (pingRounds + 1) + 1_000L)
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null

    /**
     * Imports a `happ://routing/add/...` link as a routing profile. Returns true when [link] was a
     * routing link (and was handled), so callers can short-circuit normal config import. Platforms
     * without routing-profile support leave the default no-op.
     */
    fun importRoutingProfileLink(link: String): Boolean = false

    /** Current routing profiles, for the per-location selector. Empty on platforms without support. */
    fun routingProfileChoices(): List<RoutingProfile> = emptyList()

    /**
     * Notify the user about subscriptions nearing expiry (driven by the panel's expiry header, like
     * Happ). Called after each subscription refresh with EVERY subscription's expiry; the platform
     * decides whether to post (gated on [org.olcbox.app.data.model.AppBehaviorSettings.notifySubscriptionExpiry],
     * a day threshold and de-duplication). Default no-op for platforms without local notifications.
     */
    fun notifyExpiringSubscriptions(subscriptions: List<ExpiringSubscriptionInfo>) {}

    /**
     * Notify the user about panel announcements (Remnawave `announce` header). Called after each
     * subscription refresh with EVERY subscription's current announcement; the platform decides whether
     * to post (gated on [org.olcbox.app.data.model.AppBehaviorSettings.notifyPanelAnnouncements] and
     * de-duplicated by content so the same announcement is shown only once). Default no-op for platforms
     * without local notifications.
     */
    fun notifyPanelAnnouncements(announcements: List<PanelAnnouncementInfo>) {}
}

/** A subscription's display name + its expiry (wall-clock epoch-ms), for expiry notifications. */
data class ExpiringSubscriptionInfo(
    val name: String,
    val expiresAtEpochMs: Long
)

/** A subscription's display name + the panel announcement text it carries, for announcement pushes. */
data class PanelAnnouncementInfo(
    val name: String,
    val announce: String
)
