package org.olcbox.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

interface LocationsRepository {
    val changes: StateFlow<Long>
    suspend fun getBundle(): LocationBundleV4
    suspend fun saveBundle(bundle: LocationBundleV4)
    suspend fun exportBundle(): String
    suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy? = null): Boolean
    suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): Int
    /**
     * Refreshes subscriptions whose update interval has elapsed. By default the schedule is keyed off
     * the last ATTEMPT (success or failure) so a periodic poll won't hammer an unreachable panel.
     * With [retryFailed] = true (used once per app launch) it is keyed off the last SUCCESSFUL refresh
     * only, so a subscription that's overdue but failed last time is retried again. Always silent.
     */
    suspend fun refreshDueSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy? = null,
        retryFailed: Boolean = false
    ): Int

    /**
     * One-time backfill for subscriptions imported before the expiry field was captured: force-refreshes
     * only the URLs whose entries still have no stored expiry, ignoring the auto-refresh interval. Lets
     * the "show subscription expiry" toggle work on existing subscriptions without re-importing them.
     */
    suspend fun refreshSubscriptionsMissingExpiry(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int)
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)

    /**
     * Deletes many locations in a single bundle rewrite (one load + one save), instead of N
     * sequential rewrites. Essential for long subscriptions where per-item deletion is O(N²).
     */
    suspend fun deleteLocations(storageIds: Collection<String>)
    suspend fun getAllLocations(): List<LocationEntry>
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)
    suspend fun getActiveLocation(): LocationEntry?
    suspend fun getDeviceIdentity(): String
}

data class SubscriptionFetchProxy(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)
