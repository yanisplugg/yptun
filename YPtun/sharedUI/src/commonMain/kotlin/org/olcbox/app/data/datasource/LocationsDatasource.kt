package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.importer.AmneziaWgParser
import org.olcbox.app.data.importer.FreeturnUriParser
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.importer.SubscriptionDecoder
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.identity.DeviceInfo
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.SubscriptionUserAgentHolder
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.FakeDnsSpec
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.LocationViewIndex
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.data.share.YptunInboundCodec
import org.olcbox.app.util.IsoTime

interface LocationsDataSource {
    suspend fun loadLocationBundle(): LocationBundleV4?
    suspend fun saveLocationBundle(bundle: LocationBundleV4)

    /**
     * Loads the lightweight [LocationViewIndex] persisted alongside the bundle (names + metadata only,
     * no heavy connection payloads), used to paint the location list INSTANTLY on a cold start before
     * the full bundle decode finishes. Default `null` = no fast index on this platform (falls back to
     * the full decode); only Android, where the cold-start lag is felt, persists and returns it.
     */
    suspend fun loadLocationViewIndex(): LocationViewIndex? = null
    suspend fun loadLegacyLocations(): List<Pair<String, String>>
    suspend fun loadLegacyActiveLocationId(): String?
    suspend fun loadDeviceIdentity(): String? = null
    suspend fun saveDeviceIdentity(value: String) = Unit

    /**
     * Persisted state for the Happ-style provider-usage report — an opaque JSON string mapping each
     * `providerid` to the epoch-day it was last reported, so the daily report fires at most once per
     * calendar day per id across restarts (and is shared with the background worker). Default no-op
     * (no persistence) on platforms without a store; only Android needs real persistence here.
     */
    suspend fun loadProviderReportState(): String? = null
    suspend fun saveProviderReportState(value: String) = Unit

    /**
     * A platform-stable device id (e.g. Android ANDROID_ID) used to seed the HWID so it
     * survives reinstalls / data-clears. Null when the platform offers no stable id.
     */
    suspend fun platformStableId(): String? = null

    /**
     * A cheap change token for the persisted bundle (e.g. file mtime + size), used by the repository
     * to cache the decoded bundle and skip the re-read/decode when nothing changed. The token MUST
     * change whenever the bundle is rewritten by ANY repository instance (the VPN service holds its
     * own instance), so a stale cache can never be served. Default `null` = no token available, which
     * disables the cache (always reload) — only Android, where the lag matters, overrides this.
     */
    suspend fun bundleVersionToken(): Long? = null
}

internal expect fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy? = null,
    connectTimeoutMs: Long = 3_000,
    requestTimeoutMs: Long = 8_000,
    socketTimeoutMs: Long = 8_000
): HttpClient

internal expect suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T

class LocationsRepositoryImpl(
    private val dataSource: LocationsDataSource,
    private val httpClient: HttpClient = createProxyHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider = PersistentDeviceIdentityProvider(dataSource),
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) : LocationsRepository {
    private data class ImportSource(
        val content: String,
        val subscriptionUrl: String? = null,
        val updateIntervalHours: Int? = null,
        val requestMode: SubscriptionRequestMode = SubscriptionRequestMode.Identity,
        val profileTitle: String? = null,
        val userInfo: String? = null,
        /** Best-effort JSON from the Remnawave `<url>/info` endpoint (carries user.expiresAt etc.). */
        val infoJson: String? = null,
        /** Best-effort rich Xray JSON (Happ-UA fetch) used only to extract the FakeDNS spec. */
        val fakednsJson: String? = null,
        /** Remnawave `support-url` header (panel support link). */
        val supportUrl: String? = null,
        /** Remnawave `profile-web-page-url` header (subscription management page). */
        val webPageUrl: String? = null,
        /** Remnawave `announce` header (panel announcement; may be base64). */
        val announce: String? = null,
        /** Happ/Remnawave `providerid` header (provider tracking id). */
        val providerId: String? = null
    )

    private data class DownloadedSubscription(
        val content: String,
        val updateIntervalHours: Int?,
        val profileTitle: String? = null,
        val userInfo: String? = null,
        val infoJson: String? = null,
        val fakednsJson: String? = null,
        val supportUrl: String? = null,
        val webPageUrl: String? = null,
        val announce: String? = null,
        val providerId: String? = null
    )

    private data class ParsedImport(
        val bundle: LocationBundleV4,
        val mode: ImportMode
    )

    private data class ResolvedImport(
        val source: ImportSource,
        val parsed: ParsedImport
    )

    private data class ParsedOlcRtcUri(
        val location: LocationConfig,
        val mimo: String? = null
    )

    private enum class ImportMode {
        Additive,
        Restore
    }

    private enum class SubscriptionRequestMode {
        Identity,
        Compatibility
    }

    private val mutationMutex = Mutex()
    // In-memory cache of the decoded bundle. Every getBundle() otherwise re-reads the file and
    // JSON-decodes + normalizes the whole bundle; app startup alone fires many reads (active config,
    // the full location list, subscription backfill, expiry-notify, provider-report), so with hundreds
    // of configs that repeated decode is what makes the list appear with a lag. The bundle is read ONLY
    // via getBundleUnlocked and written ONLY via saveBundleUnlocked, both always under [mutationMutex]
    // (which also gives the memory visibility), and the cache is keyed on [LocationsDataSource.bundleVersionToken]
    // so a write from ANY instance (the VPN service keeps its own repository) invalidates it — the
    // service can never connect with a stale config. A null token disables the cache (always reload).
    private var cachedBundle: LocationBundleV4? = null
    private var cachedToken: Long? = null
    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    override suspend fun getBundle(): LocationBundleV4 {
        return mutationMutex.withLock {
            getBundleUnlocked()
        }
    }

    override suspend fun getViewIndex(): LocationViewIndex? {
        // Lock-free on purpose: this reads a SEPARATE, tiny file (no touch to the cached bundle) and is
        // only ever used to paint the list fast on launch — it must not wait behind a long mutation.
        return runCatching { dataSource.loadLocationViewIndex() }.getOrNull()
    }

    private suspend fun getBundleUnlocked(): LocationBundleV4 {
        val token = dataSource.bundleVersionToken()
        cachedBundle?.let { cached ->
            if (token != null && token == cachedToken) return cached
        }

        // Normalization happens HERE (the repository is the single read funnel), so the platform
        // datasources do raw IO + decode only and we never pay a double normalize+filter+dedup pass over
        // the whole bundle. normalized() is idempotent and every write is already normalized, so this is
        // behaviour-safe; it also keeps the in-memory token cache keyed to the canonical form.
        val stored = dataSource.loadLocationBundle()?.normalized()
        if (stored != null && stored.locations.isNotEmpty()) {
            cacheBundle(stored, token)
            return stored
        }

        val legacy = migrateLegacyBundle()
        if (legacy.locations.isNotEmpty()) {
            dataSource.saveLocationBundle(legacy)
            // The write changed the file; re-read the token so the cache matches the on-disk state.
            cacheBundle(legacy, dataSource.bundleVersionToken())
        } else {
            cacheBundle(legacy, token)
        }
        return legacy
    }

    /** Stores [bundle] as the in-memory cache under its [token] (null token = effectively uncached). */
    private fun cacheBundle(bundle: LocationBundleV4, token: Long?) {
        cachedBundle = bundle
        cachedToken = token
    }

    override suspend fun saveBundle(bundle: LocationBundleV4) {
        mutationMutex.withLock {
            saveBundleUnlocked(bundle)
        }
    }

    private suspend fun saveBundleUnlocked(bundle: LocationBundleV4) {
        val normalized = bundle.normalized()
        dataSource.saveLocationBundle(normalized)
        // Cache the just-written bundle under the post-write token so the next read serves it directly.
        cacheBundle(normalized, dataSource.bundleVersionToken())
        _changes.value = _changes.value + 1
    }

    override suspend fun exportBundle(): String {
        return json.encodeToString(LocationBundleV4.serializer(), getBundle())
    }

    override suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy?): Boolean {
        val resolved = resolveParsedImport(
            text = text,
            subscriptionProxy = subscriptionProxy
        ) ?: return false

        // The data was just fetched, so stamp "now" as the last-refresh time on every imported
        // subscription entry — otherwise a freshly added subscription shows no "обновлена …" date
        // until its first scheduled/manual refresh.
        val now = nowEpochMs()
        val importedInterval = resolved.source.updateIntervalHours
        val imported = resolved.parsed.bundle.normalized().let { bundle ->
            bundle.copy(
                locations = bundle.locations.map { entry ->
                    val isSubscription = entry.metadata?.subscription != null ||
                        !entry.subscriptionUrl.isNullOrBlank()
                    if (!isSubscription) {
                        entry
                    } else {
                        val interval = entry.metadata?.subscription?.updateIntervalHours
                            ?: importedInterval
                            ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                        entry.copy(
                            metadata = entry.metadata.withSubscriptionRefreshState(
                                updateIntervalHours = interval,
                                lastRefreshAtEpochMs = now,
                                lastAttemptAtEpochMs = now
                            )
                        ).normalized()
                    }
                }
            )
        }

        mutationMutex.withLock {
            val current = getBundleUnlocked()
            // Re-importing the SAME subscription URL must behave like a refresh: drop the old entries
            // from that URL first so freshly-parsed fields (typed profile, transports, FakeDNS spec)
            // REPLACE the stale ones, instead of being dropped as duplicates by the additive merge.
            // Without this, an old import (e.g. before FakeDNS extraction existed) sticks around and
            // re-adding the subscription never picks up the new fields.
            val subUrl = resolved.source.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
            val basis = if (subUrl != null) {
                current.copy(
                    locations = current.locations.filterNot { it.subscriptionUrl?.trim() == subUrl }
                )
            } else {
                current
            }
            val merged = mergeImportedBundle(
                current = basis,
                imported = imported,
                replaceMatchingStorageIds = resolved.parsed.mode == ImportMode.Restore
            )
            saveBundleUnlocked(merged)
        }
        return true
    }

    override suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy?): Int {
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = null,
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    override suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy?
    ): Int {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return 0
        return mutationMutex.withLock {
            refreshSubscriptionsUnlocked(
                onlyUrls = setOf(normalizedUrl),
                subscriptionProxy = subscriptionProxy
            )
        }
    }

    private suspend fun refreshSubscriptionsUnlocked(
        onlyUrls: Set<String>?,
        subscriptionProxy: SubscriptionFetchProxy?
    ): Int {
        val bundle = getBundleUnlocked()
        if (bundle.locations.isEmpty()) return 0

        val groupedByUrl = bundle.locations
            .mapNotNull { entry -> entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { url -> onlyUrls == null || url in onlyUrls }
        if (groupedByUrl.isEmpty()) return 0

        val targetUrls = groupedByUrl.keys
        val refreshedLocations = bundle.locations
            .filter { entry ->
                val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() }
                url == null || (onlyUrls != null && url !in targetUrls)
            }
            .toMutableList()
        val usedStorageIds = refreshedLocations.mapTo(mutableSetOf()) { it.storageId }
        val activeBefore = bundle.activeLocationId
        var activeAfter = activeBefore
        var successfulRefreshes = 0
        // True when a failed URL had its lastAttempt time bumped: persist that even with 0 successes so
        // the due-check defers the next retry by the interval (instead of retrying every poll).
        var attemptStateChanged = false

        fun preservePreviousEntries(entries: List<LocationEntry>, attemptTimestamp: Long? = null) {
            entries.forEach { entry ->
                if (usedStorageIds.add(entry.storageId)) {
                    refreshedLocations += if (attemptTimestamp == null) {
                        entry
                    } else {
                        entry.copy(
                            metadata = entry.metadata.withSubscriptionAttemptState(attemptTimestamp)
                        ).normalized()
                    }
                }
            }
        }

        groupedByUrl.forEach { (url, previousEntries) ->
            val attemptTimestamp = nowEpochMs()
            val previousInterval = previousEntries.subscriptionUpdateIntervalHours()
            val previousAutoUpdate = previousEntries.subscriptionAutoUpdateEnabled()
            val resolved = resolveParsedImport(
                text = url,
                fallbackSubscriptionInterval = previousInterval,
                subscriptionProxy = subscriptionProxy
            ) ?: run {
                // Fetch/parse failed — keep the old links, just stamp the attempt so we retry later.
                preservePreviousEntries(previousEntries, attemptTimestamp)
                attemptStateChanged = true
                return@forEach
            }
            val source = resolved.source
            val updateInterval = source.updateIntervalHours
                ?: previousInterval
                ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
            val refreshed = resolved.parsed.bundle.locations
            if (refreshed.isEmpty()) {
                preservePreviousEntries(previousEntries, attemptTimestamp)
                attemptStateChanged = true
                return@forEach
            }

            val reusedBySignature = previousEntries
                .groupBy { subscriptionSignature(it.location) }
                .mapValues { (_, entries) -> entries.toMutableList() }

            // Was the previously-selected server one of THIS subscription's entries? If so we must
            // decide its fate after the refresh: keep it if it still exists (reused by signature),
            // otherwise fall back to this subscription's first server.
            val activeInThisGroup = activeBefore != null && previousEntries.any { it.storageId == activeBefore }
            var activeReusedHere = false

            val reassigned = refreshed.mapIndexed { index, entry ->
                val signature = subscriptionSignature(entry.location)
                val reusedPool = reusedBySignature[signature]
                val reusedEntry = if (reusedPool.isNullOrEmpty()) null else reusedPool.removeAt(0)
                val storageId = reusedEntry?.storageId ?: uniqueStorageId(
                    base = "imported_${entry.location.storageSlug().ifBlank { "location_${index + 1}" }}",
                    used = usedStorageIds
                )
                // The selected server survived the refresh (same signature → same storageId reused):
                // keep it selected. Guard against null==null matching when nothing was selected.
                if (activeBefore != null && reusedEntry?.storageId == activeBefore) {
                    activeAfter = storageId
                    activeReusedHere = true
                }
                entry.copy(
                    storageId = storageId,
                    subscriptionUrl = url,
                    metadata = entry.metadata.withSubscriptionRefreshState(
                        updateIntervalHours = updateInterval,
                        lastRefreshAtEpochMs = attemptTimestamp,
                        lastAttemptAtEpochMs = attemptTimestamp,
                        autoUpdateEnabled = previousAutoUpdate
                    )
                ).normalized()
            }

            // Only reset the selection when the selected server actually vanished from this
            // subscription (removed upstream / signature changed). If it was reused, leave it alone —
            // otherwise every on-launch refresh would snap the user back to the first server.
            if (activeInThisGroup && !activeReusedHere) {
                activeAfter = reassigned.firstOrNull()?.storageId
            }
            refreshedLocations += reassigned
            successfulRefreshes += 1
        }

        if (successfulRefreshes == 0 && !attemptStateChanged) return 0

        saveBundleUnlocked(
            bundle.copy(
                activeLocationId = activeAfter,
                locations = refreshedLocations
            )
        )
        return successfulRefreshes
    }

    override suspend fun refreshDueSubscriptions(
        subscriptionProxy: SubscriptionFetchProxy?,
        retryFailed: Boolean
    ): Int {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val now = nowEpochMs()
            val dueUrls = bundle.locations
                .mapNotNull { entry ->
                    val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val metadata = entry.metadata?.subscription
                    // Respect the per-subscription auto-update switch — skip when the user turned it off.
                    if (metadata?.autoUpdateEnabled == false) return@mapNotNull null
                    val interval = metadata?.updateIntervalHours
                        ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                    // [retryFailed] (once per app launch): key the schedule off the last SUCCESSFUL
                    // refresh only, so an overdue subscription that FAILED last time is retried again
                    // (a failed attempt no longer pushes the next try out by a full interval).
                    // Otherwise (periodic poll): key off the last ATTEMPT (success OR failure) so we
                    // don't hammer an unreachable panel on every poll.
                    val lastTouchAt = if (retryFailed) {
                        metadata?.lastRefreshAtEpochMs ?: 0L
                    } else {
                        maxOf(
                            metadata?.lastRefreshAtEpochMs ?: 0L,
                            metadata?.lastAttemptAtEpochMs ?: 0L
                        )
                    }
                    val intervalMs = interval.toLong() * 60L * 60L * 1_000L
                    url.takeIf { lastTouchAt <= 0L || now - lastTouchAt >= intervalMs }
                }
                .toSet()

            if (dueUrls.isEmpty()) {
                0
            } else {
                refreshSubscriptionsUnlocked(
                    onlyUrls = dueUrls,
                    subscriptionProxy = subscriptionProxy
                )
            }
        }
    }

    override suspend fun refreshSubscriptionsMissingExpiry(subscriptionProxy: SubscriptionFetchProxy?): Int {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            // Group by URL; a subscription needs backfill only if NONE of its entries has a stored
            // expiry yet (refreshed entries all share the same merged metadata).
            val urlsMissingExpiry = bundle.locations
                .mapNotNull { entry ->
                    val url = entry.subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    url to (entry.metadata?.subscription?.expiresAtEpochMs != null)
                }
                .groupBy({ it.first }, { it.second })
                .filterValues { hasExpiryFlags -> hasExpiryFlags.none { it } }
                .keys

            if (urlsMissingExpiry.isEmpty()) {
                0
            } else {
                refreshSubscriptionsUnlocked(
                    onlyUrls = urlsMissingExpiry,
                    subscriptionProxy = subscriptionProxy
                )
            }
        }
    }

    override suspend fun reportProviderUsage() {
        val ids = runCatching {
            getBundle().locations
                .mapNotNull { it.metadata?.subscription?.providerId?.trim()?.takeIf { id -> id.isNotBlank() } }
                .distinct()
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return

        val today = nowEpochMs() / 86_400_000L
        // Persisted providerId → last-reported epoch-day, so we fire at most once per calendar day per
        // id across restarts and shared with the background worker.
        val state = loadProviderReportDays().toMutableMap()
        // Identity + device descriptors, exactly like the subscription fetch sends to the panel.
        val hwid = runCatching { deviceIdentityProvider.hwid() }.getOrNull().orEmpty()
        val appVersion = CurrentAppInfo.value.version

        var changed = false
        ids.forEach { id ->
            if (state[id] == today) return@forEach // already reported today
            val ok = runCatching {
                val response = httpClient.get(PROVIDER_CHECK_URL + id.encodeURLParameter()) {
                    headers {
                        append(HttpHeaders.UserAgent, subscriptionUserAgent())
                        if (hwid.isNotBlank()) append("x-hwid", hwid)
                        append("x-device-os", DeviceInfo.os)
                        append("x-ver-os", DeviceInfo.osVersion)
                        append("x-device-model", DeviceInfo.model)
                        append("x-app-version", appVersion)
                    }
                }
                response.status.value in 200..299
            }.getOrDefault(false)
            if (ok) {
                state[id] = today
                changed = true
            }
        }
        // Drop entries for provider ids that no longer exist so the state can't grow unbounded.
        val pruned = state.filterKeys { it in ids }
        if (changed || pruned.size != state.size) saveProviderReportDays(pruned)
    }

    /** Reads the persisted providerId → epoch-day report map (tolerates missing/corrupt state). */
    private suspend fun loadProviderReportDays(): Map<String, Long> {
        val raw = runCatching { dataSource.loadProviderReportState() }.getOrNull() ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.toLongOrNull()?.let { k to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Persists the providerId → epoch-day report map. */
    private suspend fun saveProviderReportDays(map: Map<String, Long>) {
        runCatching {
            val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
            dataSource.saveProviderReportState(json.encodeToString(JsonObject.serializer(), obj))
        }
    }

    override suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int) {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return

        mutationMutex.withLock {
            val interval = hours.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
            val bundle = getBundleUnlocked()
            val updated = bundle.locations.map { entry ->
                if (entry.subscriptionUrl?.trim() != normalizedUrl) {
                    entry
                } else {
                    entry.copy(
                        metadata = entry.metadata.withSubscriptionInterval(interval)
                    ).normalized()
                }
            }

            saveBundleUnlocked(bundle.copy(locations = updated))
        }
    }

    override suspend fun setSubscriptionAutoUpdate(subscriptionUrl: String, enabled: Boolean) {
        val normalizedUrl = subscriptionUrl.trim()
        if (normalizedUrl.isBlank()) return

        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val updated = bundle.locations.map { entry ->
                if (entry.subscriptionUrl?.trim() != normalizedUrl) {
                    entry
                } else {
                    entry.copy(
                        metadata = entry.metadata.withSubscriptionAutoUpdate(enabled)
                    ).normalized()
                }
            }

            saveBundleUnlocked(bundle.copy(locations = updated))
        }
    }

    override suspend fun saveLocation(storageId: String, location: LocationConfig) {
        mutationMutex.withLock {
            val normalizedId = storageId.ifBlank { location.storageSlug() }
            val bundle = getBundleUnlocked()
            val current = bundle.locations.firstOrNull { it.storageId == normalizedId }
            val entry = LocationEntry.from(
                storageId = normalizedId,
                location = location,
                subscriptionUrl = current?.subscriptionUrl,
                metadata = current?.metadata
            )
            // Replace in place to preserve list order; append only when it's a new entry.
            // (Editing an existing location — e.g. switching its core — must not reorder it.)
            val locations = if (bundle.locations.any { it.storageId == entry.storageId }) {
                bundle.locations.map { if (it.storageId == entry.storageId) entry else it }
            } else {
                bundle.locations + entry
            }

            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = entry.storageId,
                    locations = locations
                )
            )
        }
    }

    override suspend fun loadLocation(storageId: String): LocationConfig? {
        return mutationMutex.withLock {
            getBundleUnlocked().locations.firstOrNull { it.storageId == storageId }?.location
        }
    }

    override suspend fun deleteLocation(storageId: String) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = bundle.activeLocationId?.takeUnless { it == storageId },
                    locations = bundle.locations.filterNot { it.storageId == storageId }
                )
            )
        }
    }

    override suspend fun deleteLocations(storageIds: Collection<String>) {
        if (storageIds.isEmpty()) return
        val ids = storageIds.toHashSet()
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            saveBundleUnlocked(
                bundle.copy(
                    activeLocationId = bundle.activeLocationId?.takeUnless { it in ids },
                    locations = bundle.locations.filterNot { it.storageId in ids }
                )
            )
        }
    }

    override suspend fun getAllLocations(): List<LocationEntry> {
        return mutationMutex.withLock {
            getBundleUnlocked().locations
        }
    }

    override suspend fun getActiveLocationId(): String? {
        return mutationMutex.withLock {
            getBundleUnlocked().activeLocationId
        }
    }

    override suspend fun setActiveLocationId(storageId: String?) {
        mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            val nextActive = storageId?.takeIf { id -> bundle.locations.any { it.storageId == id } }
            saveBundleUnlocked(bundle.copy(activeLocationId = nextActive))
        }
    }

    override suspend fun getActiveLocation(): LocationEntry? {
        return mutationMutex.withLock {
            val bundle = getBundleUnlocked()
            bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
        }
    }

    override suspend fun getDeviceIdentity(): String {
        return deviceIdentityProvider.hwid()
    }

    private suspend fun resolveParsedImport(
        text: String,
        fallbackSubscriptionInterval: Int? = null,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): ResolvedImport? {
        val input = text.normalizedImportText()
        if (input.isBlank()) return null

        var source = resolveImportSource(
            text = input,
            requestMode = SubscriptionRequestMode.Identity,
            subscriptionProxy = subscriptionProxy
        ) ?: run {
            if (input.isHttpUrl()) {
                resolveImportSource(
                    text = input,
                    requestMode = SubscriptionRequestMode.Compatibility,
                    subscriptionProxy = subscriptionProxy
                )
            } else {
                null
            }
        } ?: return null

        var parsed = parseImportSource(source, fallbackSubscriptionInterval)
        if (parsed == null && input.isHttpUrl() && source.requestMode != SubscriptionRequestMode.Compatibility) {
            val fallbackSource = resolveImportSource(
                text = input,
                requestMode = SubscriptionRequestMode.Compatibility,
                subscriptionProxy = subscriptionProxy
            )
            if (fallbackSource != null) {
                source = fallbackSource
                parsed = parseImportSource(fallbackSource, fallbackSubscriptionInterval)
            }
        }

        return parsed?.let { ResolvedImport(source, it) }
    }

    private fun parseImportSource(
        source: ImportSource,
        fallbackSubscriptionInterval: Int? = null
    ): ParsedImport? {
        val initialSubscriptionInterval = source.updateIntervalHours
            ?: fallbackSubscriptionInterval
            ?: source.subscriptionUrl?.let { SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS }

        val parsed = parseImport(
            source.content.normalizedImportText(),
            source.subscriptionUrl,
            initialSubscriptionInterval,
            mergeSubscriptionMetadata(
                // The panel JSON body (Remnawave `user{}`) carries the expiry + human traffic counters;
                // the response headers carry the profile title (name) and may also carry traffic/expiry.
                primary = subscriptionMetadataFromBody(source.infoJson ?: source.content),
                secondary = subscriptionMetadataFromHeaders(
                    profileTitle = source.profileTitle,
                    userInfo = source.userInfo,
                    supportUrl = source.supportUrl,
                    webPageUrl = source.webPageUrl,
                    announce = source.announce,
                    providerId = source.providerId
                )
            // Persist the auto-refresh interval (profile-update-interval header) onto the metadata at
            // import time too, so it's shown and used even before the first scheduled refresh.
            ).withSubscriptionInterval(initialSubscriptionInterval)
        ) ?: return null

        // Enrich with FakeDNS recovered from the Happ-UA body (the only variant that carries it). The
        // pool + dns.hosts are identical across all servers of a subscription, so a single spec is
        // attached to every parsed location that doesn't already have one (the main YPtun/base64 import
        // produces none). xhttp/raw-Xray locations run verbatim on Xray and are left untouched.
        val fakeDnsSpec = source.fakednsJson?.let { fakeDnsSpecFromSubscriptionBody(it) } ?: return parsed
        val enriched = parsed.bundle.copy(
            locations = parsed.bundle.locations.map { entry ->
                if (entry.fakeDns != null ||
                    !entry.proxy?.rawXrayConfig.isNullOrBlank() ||
                    entry.proxy?.network == ProxyProfile.NETWORK_XHTTP
                ) {
                    entry
                } else {
                    entry.copy(fakeDns = fakeDnsSpec).normalized()
                }
            }
        )
        return parsed.copy(bundle = enriched)
    }

    /** Parses a rich Xray subscription body (array or single object) and returns its FakeDNS spec, or null. */
    private fun fakeDnsSpecFromSubscriptionBody(body: String): FakeDnsSpec? {
        val element = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull() ?: return null
        val config = when {
            element is JsonObject -> element
            else -> runCatching { element.jsonArray }.getOrNull()
                ?.firstNotNullOfOrNull { it.jsonObjectOrNull()?.takeIf { o -> o["fakedns"] != null } }
        } ?: return null
        return fakeDnsSpecFromXray(config)
    }

    private suspend fun resolveImportSource(
        text: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?
    ): ImportSource? {
        if (text.isBlank()) return null

        if (!text.isHttpUrl()) {
            return ImportSource(content = text.normalizedImportText())
        }

        val downloaded = downloadTextFromUrl(
            url = text,
            requestMode = requestMode,
            subscriptionProxy = subscriptionProxy
        ) ?: return null
        return downloaded.content
            .normalizedImportText()
            .takeIf { it.isNotBlank() }
            ?.let {
                ImportSource(
                    content = it,
                    subscriptionUrl = text.trim(),
                    updateIntervalHours = downloaded.updateIntervalHours,
                    requestMode = requestMode,
                    profileTitle = downloaded.profileTitle,
                    userInfo = downloaded.userInfo,
                    infoJson = downloaded.infoJson,
                    fakednsJson = downloaded.fakednsJson,
                    supportUrl = downloaded.supportUrl,
                    webPageUrl = downloaded.webPageUrl,
                    announce = downloaded.announce,
                    providerId = downloaded.providerId
                )
            }
    }

    /** Resolves the user's subscription User-Agent choice ([SubscriptionUserAgentHolder]) to a string. */
    private fun subscriptionUserAgent(): String =
        if (SubscriptionUserAgentHolder.mode == AppBehaviorSettings.SUB_UA_YPTUN) {
            CurrentAppInfo.userAgent
        } else {
            AppBehaviorSettings.HAPP_USER_AGENT
        }

    private suspend fun downloadTextFromUrl(
        url: String,
        requestMode: SubscriptionRequestMode,
        subscriptionProxy: SubscriptionFetchProxy?
    ): DownloadedSubscription? {
        val hwid = if (requestMode == SubscriptionRequestMode.Identity) {
            deviceIdentityProvider.hwid()
        } else {
            null
        }
        val client = if (subscriptionProxy == null) {
            httpClient
        } else {
            createProxyHttpClient(subscriptionProxy)
        }

        return try {
            withProxyAuthentication(subscriptionProxy) {
                val response = runCatching {
                    client.get(url) {
                        headers {
                            append(
                                HttpHeaders.Accept,
                                // Prefer JSON so Remnawave panels return the rich body (user{} with
                                // expiresAt + traffic) instead of bare base64 links; our parser handles
                                // both the JSON `links[]` and base64/plain bodies.
                                "application/json, text/plain, text/markdown, application/octet-stream, */*"
                            )
                            // Panels do User-Agent content-negotiation: our own "YPtun/x" (and a browser
                            // UA) get only bare base64 vless links, while a recognised client UA gets the
                            // RICH per-server Xray JSON (with dns.hosts / routing / FAKEDNS). We support
                            // that JSON (parseRawXray), and it's the only way to receive the server's
                            // FakeDNS config — so always present as Happ, the de-facto "full config" UA.
                            append(HttpHeaders.UserAgent, subscriptionUserAgent())
                            if (requestMode == SubscriptionRequestMode.Identity) {
                                append("x-hwid", hwid.orEmpty())
                                // Remnawave HWID device-limit descriptors.
                                append("x-device-os", DeviceInfo.os)
                                append("x-ver-os", DeviceInfo.osVersion)
                                append("x-device-model", DeviceInfo.model)
                            }
                        }
                    }
                }.getOrNull() ?: return@withProxyAuthentication null

                if (response.status.value !in 200..299) {
                    return@withProxyAuthentication null
                }

                val content = runCatching {
                    response.bodyAsText()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@withProxyAuthentication null

                // Best-effort: Remnawave exposes the rich JSON (user.expiresAt + traffic) at <url>/info;
                // the plain subscription endpoint returns only base64 links and expire=0. Failures are
                // ignored (non-Remnawave panels simply 404 here).
                val infoJson = runCatching {
                    val infoResponse = client.get(url.trim().trimEnd('/') + "/info") {
                        headers {
                            append(HttpHeaders.Accept, "application/json")
                            append(HttpHeaders.UserAgent, subscriptionUserAgent())
                            if (requestMode == SubscriptionRequestMode.Identity) {
                                append("x-hwid", hwid.orEmpty())
                            }
                        }
                    }
                    if (infoResponse.status.value in 200..299) infoResponse.bodyAsText() else null
                }.getOrNull()?.takeIf { it.isNotBlank() }

                // FakeDNS enrichment: the server's fakeip pool + dns.hosts only ship in the rich Xray
                // JSON returned under the "Happ/1.0" UA. The MAIN fetch above keeps the user's chosen UA
                // (default YPtun → clean names/links), so do a SEPARATE Happ-UA fetch here purely to
                // recover the FakeDNS config and attach it later. Skipped (reuse main) when the main UA
                // already is Happ. Best-effort: any failure just means no FakeDNS.
                val fakednsJson = if (subscriptionUserAgent() == AppBehaviorSettings.HAPP_USER_AGENT) {
                    content
                } else {
                    runCatching {
                        val happResponse = client.get(url) {
                            headers {
                                append(HttpHeaders.Accept, "application/json, text/plain, */*")
                                append(HttpHeaders.UserAgent, AppBehaviorSettings.HAPP_USER_AGENT)
                                if (requestMode == SubscriptionRequestMode.Identity) {
                                    append("x-hwid", hwid.orEmpty())
                                }
                            }
                        }
                        if (happResponse.status.value in 200..299) happResponse.bodyAsText() else null
                    }.getOrNull()?.takeIf { it.trimStart().startsWith("[") || it.trimStart().startsWith("{") }
                }

                DownloadedSubscription(
                    content = content,
                    updateIntervalHours = response.profileUpdateIntervalHours(),
                    profileTitle = response.headers["profile-title"]?.let { decodeProfileTitle(it) },
                    userInfo = response.headers["subscription-userinfo"]?.trim(),
                    infoJson = infoJson,
                    fakednsJson = fakednsJson,
                    // Remnawave also advertises a support link, a subscription web page and an
                    // announcement via headers (the last is often base64-wrapped like profile-title).
                    supportUrl = response.headers["support-url"]?.let { decodeMaybeBase64Header(it) },
                    webPageUrl = response.headers["profile-web-page-url"]?.let { decodeMaybeBase64Header(it) },
                    announce = response.headers["announce"]?.let { decodeMaybeBase64Header(it) },
                    // Happ/Remnawave provider tracking id (lowercase `providerid`; lookup is
                    // case-insensitive). Plain string — not base64.
                    providerId = response.headers["providerid"]?.trim()?.takeIf { it.isNotBlank() }
                )
            }
        } finally {
            if (subscriptionProxy != null) {
                client.close()
            }
        }
    }

    private fun String.isHttpUrl(): Boolean {
        val value = trim().lowercase()
        return value.startsWith("http://") || value.startsWith("https://")
    }

    private fun String.normalizedImportText(): String {
        return trim().removePrefix(UTF8_BOM).trim()
    }

    private suspend fun migrateLegacyBundle(): LocationBundleV4 {
        val legacy = dataSource.loadLegacyLocations().mapNotNull { (storageId, text) ->
            parseSingleLocation(text, storageId)
        }

        val active = dataSource.loadLegacyActiveLocationId()?.takeIf { id ->
            legacy.any { it.storageId == id }
        }

        return LocationBundleV4(
            activeLocationId = active,
            locations = legacy
        ).normalized()
    }

    private fun parseImport(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null,
        subscriptionMetadata: SubscriptionMetadata? = null
    ): ParsedImport? {
        // Our own universal inbound link (yptun://inbound?…&d=<base64 LocationConfig JSON>): carries
        // the WHOLE location (engine, transport, proxy/AWG/VK outbound, every toggle). Checked first
        // since it has its own scheme and restores the location verbatim.
        parseYptunInboundText(text, subscriptionUrl)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        parseOlcRtcText(text, subscriptionUrl, updateIntervalHours)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        // VK-TURN share link (freeturn://): a WireGuard-over-VK location. Checked before the
        // generic proxy parser since it has its own scheme and engine.
        parseFreeturnText(text, subscriptionUrl)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        // AmneziaWG .conf (whole wg-quick INI with obf knobs) → a Standard location whose proxy is
        // the AmneziaWG transport. Checked before the proxy parser (which splits into per-line links
        // and would not see the multi-line config).
        parseAmneziaWgText(text, subscriptionUrl)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        // Full raw Xray config — a single object OR an array of them (Happ/Remnawave subscriptions
        // ship one complete Xray config per server, each with its own dns.hosts / routing / fakedns).
        // MUST run before the proxy/sing-box parsers, otherwise the config is downgraded to a bare
        // sing-box vless and its fakedns / RU-direct DNS hosts are lost.
        parseRawXray(text, subscriptionUrl)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        // Proxy share links / subscriptions (vless, vmess, trojan, ss, base64 blobs and
        // JSON panels with a "links" array) become sing-box (Standard) locations.
        parseProxyText(text, subscriptionUrl, subscriptionMetadata)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        // Raw sing-box config (full config with "outbounds", a single outbound object, or an
        // array of outbounds) → Standard locations carrying the outbound JSON verbatim.
        parseRawSingBox(text, subscriptionUrl)?.let {
            return ParsedImport(it, ImportMode.Additive)
        }

        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl, updateIntervalHours)?.let {
            return ParsedImport(it, ImportMode.Restore)
        }

        return parseSingleLocation(root, null, subscriptionUrl)?.let {
            ParsedImport(
                LocationBundleV4(
                    activeLocationId = it.storageId,
                    locations = listOf(
                        it.copy(
                            metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
                        ).normalized()
                    )
                ),
                ImportMode.Additive
            )
        }
    }

    private fun mergeImportedBundle(
        current: LocationBundleV4?,
        imported: LocationBundleV4,
        replaceMatchingStorageIds: Boolean
    ): LocationBundleV4 {
        val currentBundle = current?.normalized()
        if (currentBundle == null || currentBundle.locations.isEmpty()) {
            return imported
        }

        val currentStorageIds = currentBundle.locations.mapTo(mutableSetOf()) { it.storageId }
        val existingStorageIds = currentBundle.locations.mapTo(mutableSetOf()) { it.storageId }

        val importedByStorageId = if (replaceMatchingStorageIds) {
            imported.locations.associateBy { it.storageId }
        } else {
            emptyMap()
        }
        val replacedStorageIds = importedByStorageId.keys.intersect(currentStorageIds)

        val mergedLocations = currentBundle.locations
            .map { existing ->
                importedByStorageId[existing.storageId]?.also {
                    existingStorageIds.add(it.storageId)
                } ?: existing
            }
            .toMutableList()

        val importedIdMap = mutableMapOf<String, String>()

        imported.locations.forEach { entry ->
            if (replaceMatchingStorageIds && entry.storageId in replacedStorageIds) return@forEach

            val storageId = uniqueStorageId(entry.storageId, existingStorageIds)
            importedIdMap[entry.storageId] = storageId
            mergedLocations += entry.copy(storageId = storageId).normalized()
        }

        val importedActive = imported.activeLocationId
            ?.let { id -> importedIdMap[id] ?: id }
            ?.takeIf { id -> mergedLocations.any { it.storageId == id } }

        val active = importedActive
            ?: currentBundle.activeLocationId?.takeIf { id -> mergedLocations.any { it.storageId == id } }
            ?: mergedLocations.firstOrNull()?.storageId

        return currentBundle.copy(
            activeLocationId = active,
            locations = mergedLocations
        )
    }

    private fun parseBundle(
        root: JsonObject,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        val locationsElement = root["locations"] ?: return null

        val locations = runCatching {
            locationsElement.jsonArray
        }.getOrNull()?.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null

            decodeLocationEntry(item, subscriptionUrl)?.let {
                return@mapNotNull it.copy(
                    metadata = it.metadata.withSubscriptionInterval(updateIntervalHours)
                ).normalized()
            }

            val storageId = item.string("storage_id")
                ?: item.string("storageId")
                ?: item.string("id")?.let { "imported_${it.storageSlug()}" }

            parseSingleLocation(item, storageId, subscriptionUrl)?.let { entry ->
                entry.copy(
                    metadata = entry.metadata.withSubscriptionInterval(updateIntervalHours)
                ).normalized()
            }
        } ?: return null

        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 3
        if (version < 3 && locations.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = root.string("active_location_id")
                ?: root.string("activeLocationId"),
            locations = locations
        )
    }

    private fun parseSingleLocation(
        text: String,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        val root = runCatching {
            json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        parseBundle(root, subscriptionUrl)?.let { bundle ->
            return bundle.normalized().locations.firstOrNull()
        }

        return parseSingleLocation(root, fallbackStorageId, subscriptionUrl)
    }

    private fun parseSingleLocation(
        root: JsonObject,
        fallbackStorageId: String?,
        subscriptionUrl: String? = null
    ): LocationEntry? {
        decodeLocationEntry(root, subscriptionUrl)?.let { return it }

        val source = root["location"]?.jsonObjectOrNull()
            ?: root["hysteria"]?.jsonObjectOrNull()
            ?: root

        val provider = firstNotBlank(
            source.string("auth_provider"),
            source.string("authProvider"),
            source.string("bypass_provider"),
            source.string("bypassProvider"),
            source.string("provider"),
            root["turn"]?.jsonObjectOrNull()?.string("type"),
            root.string("auth_provider"),
            root.string("authProvider"),
            root.string("bypass_provider"),
            root.string("bypassProvider"),
            root.string("provider")
        )

        val transportArgs = firstNotBlank(
            source.string("transport_args"),
            source.string("transportArgs"),
            source.string("args"),
            root.string("transport_args"),
            root.string("transportArgs"),
            root.string("args")
        )

        val vp8Fps = firstInt(
            source.int("vp8_fps"),
            source.int("vp8Fps"),
            root.int("vp8_fps"),
            root.int("vp8Fps"),
            transportArgInt(transportArgs, "-vp8-fps")
        ) ?: LocationConfig.DEFAULT_VP8_FPS

        val vp8Batch = firstInt(
            source.int("vp8_batch"),
            source.int("vp8Batch"),
            root.int("vp8_batch"),
            root.int("vp8Batch"),
            transportArgInt(transportArgs, "-vp8-batch")
        ) ?: LocationConfig.DEFAULT_VP8_BATCH

        val location = LocationConfig(
            name = firstNotBlank(source.string("name"), root.string("name")),
            id = firstNotBlank(
                source.string("id"),
                source.string("room_id"),
                source.string("server"),
                root.string("id")
            ),
            key = firstNotBlank(
                source.string("key"),
                source.string("encryption_key"),
                source.string("password"),
                root.string("key")
            ),
            bypassProvider = provider,
            transport = firstNotBlank(
                source.string("transport"),
                root.string("transport"),
                if (transportArgs.isNotBlank()) LocationConfig.TRANSPORT_VP8CHANNEL else null
            ),
            vp8Fps = vp8Fps,
            vp8Batch = vp8Batch
        ).normalized()

        if (!location.isComplete()) return null

        val storageId = firstNotBlank(
            fallbackStorageId,
            root.string("storage_id"),
            root.string("storageId"),
            source.string("storage_id"),
            source.string("storageId"),
            "imported_${location.storageSlug()}"
        )

        return LocationEntry.from(storageId, location, subscriptionUrl = subscriptionUrl)
    }

    /** Parses one or more `yptun://inbound…` links (one per line) into Standard/custom locations. */
    private fun parseYptunInboundText(
        text: String,
        subscriptionUrl: String? = null
    ): LocationBundleV4? {
        if (!text.contains(YptunInboundCodec.PREFIX)) return null
        val usedStorageIds = mutableSetOf<String>()
        val entries = text.lineSequence()
            .map { it.normalizedImportText() }
            .filter { it.startsWith(YptunInboundCodec.PREFIX) }
            .mapNotNull { YptunInboundCodec.parse(it) }
            .mapIndexed { index, parsed ->
                val location = parsed.normalized()
                val base = location.storageSlug().ifBlank { "location_${index + 1}" }
                val storageId = uniqueStorageId("imported_$base", usedStorageIds)
                LocationEntry.from(
                    storageId = storageId,
                    location = location,
                    subscriptionUrl = subscriptionUrl,
                    metadata = null
                )
            }
            .toList()
        if (entries.isEmpty()) return null
        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    private fun parseOlcRtcText(
        text: String,
        subscriptionUrl: String? = null,
        updateIntervalHours: Int? = null
    ): LocationBundleV4? {
        if (!text.contains(OLCRTC_URI_PREFIX)) return null

        val subscriptionFields = linkedMapOf<String, String>()
        val locations = mutableListOf<Pair<ParsedOlcRtcUri, MutableMap<String, String>>>()
        var localFields: MutableMap<String, String>? = null

        text.lineSequence()
            .map { it.normalizedImportText() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith(OLCRTC_URI_PREFIX) -> {
                        parseOlcRtcUri(line)?.let { parsed ->
                            val fields = linkedMapOf<String, String>()
                            locations += parsed to fields
                            localFields = fields
                        }
                    }

                    line.startsWith("##") && locations.isNotEmpty() -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("##")
                        ) ?: return@forEach

                        localFields?.set(key, value)
                    }

                    line.startsWith("#") -> {
                        val (key, value) = parseSubscriptionField(
                            line.removePrefix("#")
                        ) ?: return@forEach

                        subscriptionFields[key] = value
                    }
                }
            }

        if (locations.isEmpty()) return null

        val subscriptionMetadata = buildSubscriptionMetadata(subscriptionFields)
            .withSubscriptionInterval(updateIntervalHours)
        val usedStorageIds = mutableSetOf<String>()

        val entries = locations.mapIndexed { index, (parsed, fields) ->
            val metadata = buildLocationMetadata(
                fields = fields,
                mimo = parsed.mimo,
                subscription = subscriptionMetadata
            )
            val location = parsed.location.copy(
                name = firstNotBlank(
                    metadata?.name,
                    parsed.mimo,
                    parsed.location.name
                )
            ).normalized()
            val base = location.storageSlug().ifBlank { "location_${index + 1}" }
            val storageId = uniqueStorageId("imported_$base", usedStorageIds)
            LocationEntry.from(
                storageId = storageId,
                location = location,
                subscriptionUrl = subscriptionUrl,
                metadata = metadata
            )
        }

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    /**
     * Parses proxy share links / subscription bodies (vless/vmess/trojan/ss, base64 blobs,
     * or JSON panels exposing a "links" array) into sing-box [EngineType.Standard] locations.
     * Storage ids are derived from server:port so subscription refresh replaces stable entries.
     */
    /**
     * Builds subscription metadata (name + traffic) from panel response headers used by
     * Remnawave / 3x-ui / Marzban: `profile-title` (name) and `subscription-userinfo`
     * (`upload=…; download=…; total=…; expire=…`).
     */
    private fun subscriptionMetadataFromHeaders(
        profileTitle: String?,
        userInfo: String?,
        supportUrl: String? = null,
        webPageUrl: String? = null,
        announce: String? = null,
        providerId: String? = null
    ): SubscriptionMetadata? {
        val name = profileTitle?.trim()?.takeIf { it.isNotBlank() }

        var used: String? = null
        var available: String? = null
        var expiresAtEpochMs: Long? = null
        if (!userInfo.isNullOrBlank()) {
            val fields = userInfo.split(';')
                .mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
                }
                .toMap()
            val upload = fields["upload"]?.toLongOrNull() ?: 0L
            val download = fields["download"]?.toLongOrNull() ?: 0L
            val total = fields["total"]?.toLongOrNull()
            if (fields.containsKey("upload") || fields.containsKey("download") || total != null) {
                used = formatTrafficBytes(upload + download)
                available = if (total == null || total <= 0L) "∞" else formatTrafficBytes(total)
            }
            // `expire=<unix seconds>` (0 / absent = no expiry) — the standard subscription-userinfo field.
            expiresAtEpochMs = fields["expire"]?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1_000L }
        }

        val support = supportUrl?.trim()?.takeIf { it.isNotBlank() }
        val webPage = webPageUrl?.trim()?.takeIf { it.isNotBlank() }
        val announcement = announce?.trim()?.takeIf { it.isNotBlank() }
        val provider = providerId?.trim()?.takeIf { it.isNotBlank() }

        if (name == null && used == null && available == null && expiresAtEpochMs == null &&
            support == null && webPage == null && announcement == null && provider == null
        ) {
            return null
        }
        return SubscriptionMetadata(
            name = name,
            used = used,
            available = available,
            expiresAtEpochMs = expiresAtEpochMs,
            supportUrl = support,
            webPageUrl = webPage,
            announce = announcement,
            providerId = provider
        ).normalized()
    }

    /**
     * Builds subscription metadata from a Remnawave-style JSON subscription BODY, e.g.
     * `{ "user": { "expiresAt": "2099-05-03T20:59:00.000Z", "trafficUsed": "113.14 GiB",
     * "trafficLimit": "0", ... }, "links": [...] }`. Pulls the expiry date and the human traffic
     * counters that the response headers don't carry. Returns null for non-JSON bodies (base64 / plain
     * link lists) or when no usable field is present.
     */
    private fun subscriptionMetadataFromBody(content: String): SubscriptionMetadata? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        // The `/info` endpoint wraps it as {response:{user:{…}}}; the bare body uses {user:{…}}.
        val user = root["response"]?.jsonObjectOrNull()?.get("user")?.jsonObjectOrNull()
            ?: root["user"]?.jsonObjectOrNull()
            ?: return null

        val expiresAtEpochMs = IsoTime.parseIsoToEpochMs(user.string("expiresAt"))
        val used = user.string("trafficUsed")?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        val limit = user.string("trafficLimit")?.trim()
        // trafficLimit "0" / blank = unlimited (NO_RESET plans report a 0 limit).
        val available = when {
            limit.isNullOrBlank() || limit == "0" || limit == "0 B" -> if (used != null) "∞" else null
            else -> limit
        }

        if (expiresAtEpochMs == null && used == null && available == null) return null
        return SubscriptionMetadata(
            used = used,
            available = available,
            expiresAtEpochMs = expiresAtEpochMs
        ).normalized().takeUnless { it.isEmpty() }
    }

    /** Field-wise merge: [primary] wins, [secondary] fills the gaps; the name prefers the header title. */
    private fun mergeSubscriptionMetadata(
        primary: SubscriptionMetadata?,
        secondary: SubscriptionMetadata?
    ): SubscriptionMetadata? {
        if (primary == null) return secondary
        if (secondary == null) return primary
        return SubscriptionMetadata(
            name = secondary.name ?: primary.name,
            update = primary.update ?: secondary.update,
            refresh = primary.refresh ?: secondary.refresh,
            color = primary.color ?: secondary.color,
            icon = primary.icon ?: secondary.icon,
            used = primary.used ?: secondary.used,
            available = primary.available ?: secondary.available,
            updateIntervalHours = primary.updateIntervalHours ?: secondary.updateIntervalHours,
            lastRefreshAtEpochMs = primary.lastRefreshAtEpochMs ?: secondary.lastRefreshAtEpochMs,
            expiresAtEpochMs = primary.expiresAtEpochMs ?: secondary.expiresAtEpochMs,
            lastAttemptAtEpochMs = primary.lastAttemptAtEpochMs ?: secondary.lastAttemptAtEpochMs,
            supportUrl = primary.supportUrl ?: secondary.supportUrl,
            webPageUrl = primary.webPageUrl ?: secondary.webPageUrl,
            announce = primary.announce ?: secondary.announce,
            providerId = primary.providerId ?: secondary.providerId
        ).normalized().takeUnless { it.isEmpty() }
    }

    /** Decodes a `profile-title` header, which Remnawave sends as `base64:<payload>`. */
    private fun decodeProfileTitle(raw: String): String? = decodeMaybeBase64Header(raw)

    /**
     * Decodes a header value that Remnawave may send either plain or `base64:`-prefixed (used for
     * `profile-title`, `announce`, and occasionally the URLs). Falls back to the raw value if it isn't
     * actually base64. Returns null for blank input.
     */
    private fun decodeMaybeBase64Header(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val decoded = if (value.startsWith("base64:")) {
            val payload = value.removePrefix("base64:").trim()
            SubscriptionDecoder.decodeBase64Chunk(payload) ?: payload
        } else {
            value
        }
        return decoded.trim().takeIf { it.isNotBlank() }
    }

    /** Formats a byte count into a compact human string, e.g. "230.4 GB". */
    private fun formatTrafficBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        val rounded = (value * 10).toLong() / 10.0
        val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        return "$text ${units[unitIndex]}"
    }

    /**
     * Parses a single VK-TURN [FreeturnUriParser.SCHEME] link into a [EngineType.VkTurn] location.
     * The decoded WireGuard config is stored as [ProxyProfile.rawOutbound]; the per-client VK call
     * link is left empty for the user to fill in via the location settings before connecting.
     */
    private fun parseFreeturnText(
        text: String,
        subscriptionUrl: String? = null
    ): LocationBundleV4? {
        val line = text.trim().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(FreeturnUriParser.SCHEME, ignoreCase = true) }
            ?: return null
        val link = FreeturnUriParser.parse(line) ?: return null

        val name = link.comment.ifBlank { "VK-TURN ${link.serverIp}" }
        val location = if (link.mode == "tcp") {
            // tcp / Proxy-bonded: the exit is a normal proxy dialled THROUGH the local freeturn tcp
            // listener. Mirror VkTurnComposer.compose's PROXY branch — rewrite server→127.0.0.1:<listen>
            // and pin the SNI to the real host (else TLS validates against 127.0.0.1 and resets).
            val listenPort = LocationConfig.DEFAULT_FREETURN_PORT
            val base = ShareLinkParser.parse(link.exitProxyLink) ?: ProxyProfile()
            LocationConfig(
                name = name,
                engine = EngineType.VkTurn,
                proxy = base.copy(
                    tag = name,
                    sni = base.sni.ifBlank { base.server },
                    server = "127.0.0.1",
                    serverPort = listenPort,
                ),
                vkturn = VkTurnConfig(
                    uri = link.uri,
                    vkLink = "",
                    listenPort = listenPort,
                    outbound = VkTurnConfig.OUTBOUND_PROXY,
                    outboundProxyLink = link.exitProxyLink,
                ),
            ).normalized()
        } else {
            LocationConfig(
                name = name,
                engine = EngineType.VkTurn,
                proxy = ProxyProfile(
                    tag = name,
                    type = "wireguard",
                    server = link.serverIp,
                    serverPort = link.serverPort,
                    rawOutbound = link.wgOutboundJson,
                ),
                vkturn = VkTurnConfig(
                    uri = link.uri,
                    vkLink = "",
                    listenPort = link.listenPort,
                ),
            ).normalized()
        }

        val base = "${link.serverIp}_${link.serverPort}"
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        val storageId = uniqueStorageId("imported_vkturn_$base", mutableSetOf())
        val entry = LocationEntry.from(
            storageId = storageId,
            location = location,
            subscriptionUrl = subscriptionUrl,
        )
        return LocationBundleV4(
            activeLocationId = entry.storageId,
            locations = listOf(entry)
        )
    }

    /** Parses a whole AmneziaWG wg-quick .conf into a [EngineType.Standard] location. */
    private fun parseAmneziaWgText(text: String, subscriptionUrl: String? = null): LocationBundleV4? {
        val trimmed = text.trim()
        if (!AmneziaWgParser.looksLikeAmneziaWg(trimmed)) return null
        val profile = AmneziaWgParser.parse(trimmed) ?: return null
        val name = profile.tag.ifBlank { "AmneziaWG" }
        val location = LocationConfig(
            name = name,
            engine = EngineType.Standard,
            proxy = profile,
        ).normalized()
        val base = "${profile.server}_${profile.serverPort}"
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        val storageId = uniqueStorageId("imported_awg_$base", mutableSetOf())
        val entry = LocationEntry.from(
            storageId = storageId,
            location = location,
            subscriptionUrl = subscriptionUrl,
        )
        return LocationBundleV4(activeLocationId = entry.storageId, locations = listOf(entry))
    }

    private fun parseProxyText(
        text: String,
        subscriptionUrl: String? = null,
        subscriptionMetadata: SubscriptionMetadata? = null
    ): LocationBundleV4? {
        val profiles = ShareLinkParser.parseSubscription(text)
        if (profiles.isEmpty()) return null

        val locationMetadata = subscriptionMetadata?.let { LocationMetadata(subscription = it) }

        val usedStorageIds = mutableSetOf<String>()
        val entries = profiles.map { profile ->
            val location = LocationConfig(
                name = profile.displayName(),
                engine = EngineType.Standard,
                proxy = profile
            ).normalized()
            val base = "${profile.server}_${profile.serverPort}"
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
            val storageId = uniqueStorageId("imported_$base", usedStorageIds)
            LocationEntry.from(
                storageId = storageId,
                location = location,
                subscriptionUrl = subscriptionUrl,
                metadata = locationMetadata
            )
        }

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    /**
     * Parses a raw sing-box config into Standard locations. Accepts a full config object (with
     * an "outbounds" array), a single outbound object, or an array of outbounds. Each server
     * outbound (has "type" + "server") is stored verbatim as [ProxyProfile.rawOutbound].
     */
    private fun parseRawSingBox(
        text: String,
        subscriptionUrl: String? = null
    ): LocationBundleV4? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null

        val outbounds: List<JsonObject> = when {
            element is JsonObject && element["outbounds"] != null ->
                runCatching { element["outbounds"]!!.jsonArray }.getOrNull()
                    ?.mapNotNull { it.jsonObjectOrNull() } ?: return null
            element is JsonObject -> listOf(element)
            else -> runCatching { element.jsonArray }.getOrNull()
                ?.mapNotNull { it.jsonObjectOrNull() } ?: return null
        }

        val servers = outbounds.filter { it["type"] != null && it["server"] != null }
        if (servers.isEmpty()) return null

        val usedStorageIds = mutableSetOf<String>()
        val entries = servers.mapIndexedNotNull { index, outbound ->
            val server = outbound.string("server") ?: return@mapIndexedNotNull null
            val port = outbound["server_port"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val type = outbound.string("type") ?: return@mapIndexedNotNull null
            val tag = outbound.string("tag")?.takeIf { it.isNotBlank() } ?: "$server:$port"

            val profile = ProxyProfile(
                tag = tag,
                type = type,
                server = server,
                serverPort = port,
                rawOutbound = outbound.toString()
            )
            val location = LocationConfig(
                name = tag,
                engine = EngineType.Standard,
                proxy = profile
            ).normalized()
            val base = "${server}_$port"
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
            val storageId = uniqueStorageId("imported_$base", usedStorageIds)
            LocationEntry.from(
                storageId = storageId,
                location = location,
                subscriptionUrl = subscriptionUrl,
                metadata = null
            )
        }
        if (entries.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    /**
     * Parses a full raw Xray-core JSON config (single proxy node) into one Standard/Xray location
     * that runs the config verbatim. Recognised by an "outbounds" array whose entries use Xray's
     * "protocol" key (vless/vmess/trojan/shadowsocks). server/port/tag are extracted only for the
     * display name & storage-id; the entire JSON is preserved in [ProxyProfile.rawXrayConfig].
     */
    private fun parseRawXray(
        text: String,
        subscriptionUrl: String? = null
    ): LocationBundleV4? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null

        // Accept a single Xray config object OR an ARRAY of full Xray configs (Happ/Remnawave-style
        // subscriptions ship one complete config per server). Each is kept verbatim so its dns.hosts /
        // routing / fakedns are honored — instead of being downgraded to a bare sing-box vless.
        val configs: List<JsonObject> = when {
            element is JsonObject -> listOf(element)
            else -> runCatching { element.jsonArray }.getOrNull()
                ?.mapNotNull { it.jsonObjectOrNull() } ?: return null
        }

        val usedStorageIds = mutableSetOf<String>()
        val entries = configs.mapNotNull { parseSingleRawXrayEntry(it, usedStorageIds, subscriptionUrl) }
        if (entries.isEmpty()) return null

        return LocationBundleV4(
            activeLocationId = entries.firstOrNull()?.storageId,
            locations = entries
        )
    }

    /** Builds one verbatim-Xray location from a single full Xray config object, or null if it isn't one. */
    private fun parseSingleRawXrayEntry(
        root: JsonObject,
        usedStorageIds: MutableSet<String>,
        subscriptionUrl: String?
    ): LocationEntry? {
        val outbounds = runCatching { root["outbounds"]?.jsonArray }.getOrNull()
            ?.mapNotNull { it.jsonObjectOrNull() } ?: return null

        // The proxy outbound: a known Xray proxy protocol. (Xray keys off "protocol", not "type".)
        val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
        val proxyOutbound = outbounds.firstOrNull {
            it.string("protocol")?.lowercase() in proxyProtocols
        } ?: return null

        val protocol = proxyOutbound.string("protocol")!!.lowercase()
        val settings = proxyOutbound["settings"]?.jsonObjectOrNull()
        // vless/vmess use settings.vnext[]; trojan/shadowsocks use settings.servers[].
        val endpoint = settings?.get("vnext")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: settings?.get("servers")?.let { runCatching { it.jsonArray }.getOrNull() }
        val firstEndpoint = endpoint?.firstOrNull()?.jsonObjectOrNull()
        val server = firstEndpoint?.string("address") ?: return null
        val port = firstEndpoint["port"]?.jsonPrimitive?.intOrNull ?: return null

        val name = firstNotBlank(
            root.string("remarks"),
            proxyOutbound.string("tag"),
            "$server:$port"
        )

        // Try to fully translate the Xray proxy outbound into typed sing-box-runnable fields PLUS a
        // FakeDNS spec (fakeip pool + dns.hosts blackholes). When that succeeds, the location runs on
        // EITHER core — sing-box reproduces FakeDNS natively (dns.fakeip), so FakeDNS no longer needs
        // xray-core. Only an xhttp/splithttp transport (sing-box can't serve it) keeps the verbatim
        // Xray template + forced Xray core.
        val typed = typedProfileFromXrayOutbound(proxyOutbound, protocol, server, port, name)
        val location = if (typed != null) {
            LocationConfig(
                name = name,
                engine = EngineType.Standard,
                proxy = typed,
                core = ProxyCore.Auto,
                fakeDns = fakeDnsSpecFromXray(root),
            ).normalized()
        } else {
            // Untranslatable (xhttp / unknown transport) → run the whole template verbatim on Xray.
            LocationConfig(
                name = name,
                engine = EngineType.Standard,
                proxy = ProxyProfile(
                    tag = name,
                    type = protocol,
                    server = server,
                    serverPort = port,
                    // Keep THIS config (the array element), not the whole array, as the verbatim payload.
                    rawXrayConfig = root.toString()
                ),
                core = ProxyCore.Xray
            ).normalized()
        }

        val base = "${server}_$port"
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        val storageId = uniqueStorageId("imported_$base", usedStorageIds)

        return LocationEntry.from(
            storageId = storageId,
            location = location,
            subscriptionUrl = subscriptionUrl,
            metadata = null
        )
    }

    /**
     * Maps an Xray proxy outbound (vless/vmess/trojan/shadowsocks) to a typed [ProxyProfile] that the
     * sing-box core can dial directly. Returns null when the transport is xhttp/splithttp or otherwise
     * not serviceable by sing-box (the caller then keeps the verbatim Xray template instead).
     */
    private fun typedProfileFromXrayOutbound(
        outbound: JsonObject,
        protocol: String,
        server: String,
        port: Int,
        name: String,
    ): ProxyProfile? {
        val settings = outbound["settings"]?.jsonObjectOrNull()
        val stream = outbound["streamSettings"]?.jsonObjectOrNull()

        // Xray network → ProxyProfile network. xhttp/splithttp are Xray-only → bail (verbatim Xray).
        val xrayNet = stream?.string("network")?.lowercase() ?: "tcp"
        val network = when (xrayNet) {
            "tcp", "raw" -> ProxyProfile.NETWORK_TCP
            "ws", "websocket" -> ProxyProfile.NETWORK_WS
            "grpc", "gun" -> ProxyProfile.NETWORK_GRPC
            "h2", "http" -> ProxyProfile.NETWORK_HTTP
            "httpupgrade" -> ProxyProfile.NETWORK_HTTPUPGRADE
            "xhttp", "splithttp" -> return null
            else -> return null
        }

        val security = when (stream?.string("security")?.lowercase()) {
            "reality" -> ProxyProfile.SECURITY_REALITY
            "tls", "xtls" -> ProxyProfile.SECURITY_TLS
            else -> ProxyProfile.SECURITY_NONE
        }

        // Per-protocol credentials (vless/vmess use vnext[].users[]; trojan/ss use servers[]).
        val vnextUser = settings?.get("vnext")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()?.jsonObjectOrNull()
            ?.get("users")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()?.jsonObjectOrNull()
        val ssServer = settings?.get("servers")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()?.jsonObjectOrNull()

        // TLS/REALITY parameters live in stream.{realitySettings|tlsSettings}.
        val tls = stream?.get("realitySettings")?.jsonObjectOrNull()
            ?: stream?.get("tlsSettings")?.jsonObjectOrNull()
        val sni = tls?.string("serverName") ?: ""
        val fingerprint = tls?.string("fingerprint") ?: ""
        val alpn = (tls?.get("alpn") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        val allowInsecure = (tls?.get("allowInsecure") as? JsonPrimitive)?.contentOrNull == "true"
        val realityPbk = tls?.string("publicKey") ?: ""
        val realityShortId = tls?.string("shortId") ?: ""

        // Transport-specific path/host/serviceName.
        val wsLike = stream?.get("wsSettings")?.jsonObjectOrNull()
            ?: stream?.get("httpupgradeSettings")?.jsonObjectOrNull()
        val grpc = stream?.get("grpcSettings")?.jsonObjectOrNull()
        val path = when (network) {
            ProxyProfile.NETWORK_GRPC -> grpc?.string("serviceName") ?: ""
            else -> wsLike?.string("path") ?: ""
        }
        val host = wsLike?.string("host")
            ?: (wsLike?.get("headers")?.jsonObjectOrNull()?.string("Host"))
            ?: ""

        return when (protocol) {
            "vless", "vmess" -> {
                val uuid = vnextUser?.string("id") ?: return null
                if (uuid.isBlank()) return null
                ProxyProfile(
                    tag = name,
                    type = protocol,
                    server = server,
                    serverPort = port,
                    uuid = uuid,
                    flow = vnextUser.string("flow") ?: "",
                    alterId = vnextUser.int("alterId") ?: 0,
                    cipher = vnextUser.string("security") ?: "auto",
                    network = network,
                    security = security,
                    sni = sni,
                    alpn = alpn,
                    fingerprint = fingerprint,
                    allowInsecure = allowInsecure,
                    realityPublicKey = realityPbk,
                    realityShortId = realityShortId,
                    path = path,
                    host = host,
                )
            }
            "trojan" -> {
                val pass = ssServer?.string("password") ?: return null
                ProxyProfile(
                    tag = name, type = ProxyProfile.TYPE_TROJAN, server = server, serverPort = port,
                    password = pass, network = network, security = security, sni = sni, alpn = alpn,
                    fingerprint = fingerprint, allowInsecure = allowInsecure,
                    realityPublicKey = realityPbk, realityShortId = realityShortId, path = path, host = host,
                )
            }
            "shadowsocks" -> {
                val pass = ssServer?.string("password") ?: return null
                ProxyProfile(
                    tag = name, type = ProxyProfile.TYPE_SHADOWSOCKS, server = server, serverPort = port,
                    password = pass, method = ssServer.string("method") ?: "",
                    network = network,
                )
            }
            else -> null
        }
    }

    /**
     * Extracts a [FakeDnsSpec] from a full Xray config: the `fakedns` pool range and the `dns.hosts`
     * entries that map a domain to `0.0.0.0` (blackhole). Returns null when the config has no fakedns.
     */
    private fun fakeDnsSpecFromXray(root: JsonObject): FakeDnsSpec? {
        val pool = (root["fakedns"] as? JsonArray)?.firstOrNull()?.jsonObjectOrNull()
        // Xray also accepts a bare object for `fakedns`.
        val poolObj = pool ?: root["fakedns"]?.jsonObjectOrNull()
        val hasFakeDns = poolObj != null || (root["fakedns"] != null)
        if (!hasFakeDns) return null

        val inet4 = poolObj?.string("ipPool")?.takeIf { it.contains('.') } ?: "198.18.0.0/15"
        val inet6 = poolObj?.string("ipPool")?.takeIf { it.contains(':') } ?: "fc00::/18"

        // dns.hosts: keys mapping to "0.0.0.0" are blackholes. Keys are "regexp:<re>", "domain:<d>",
        // "geosite:..." or a plain domain. Translate regexp/domain/plain into sing-box domain_regex.
        val hosts = (root["dns"] as? JsonObject)?.get("hosts") as? JsonObject
        val blockRegex = hosts?.mapNotNull { (key, value) ->
            val blocks = when (value) {
                is JsonPrimitive -> value.contentOrNull == "0.0.0.0"
                is JsonArray -> value.any { (it as? JsonPrimitive)?.contentOrNull == "0.0.0.0" }
                else -> false
            }
            if (!blocks) return@mapNotNull null
            when {
                key.startsWith("regexp:") -> key.removePrefix("regexp:")
                key.startsWith("domain:") -> {
                    val d = key.removePrefix("domain:").replace(".", "\\.")
                    "(^|\\.)$d$"
                }
                key.startsWith("geosite:") || key.startsWith("geoip:") -> null // not regex-translatable
                else -> {
                    val d = key.replace(".", "\\.")
                    "(^|\\.)$d$"
                }
            }
        }.orEmpty().filterNotNull()

        return FakeDnsSpec(inet4Range = inet4, inet6Range = inet6, blockRegex = blockRegex)
    }

    private fun parseOlcRtcUri(line: String): ParsedOlcRtcUri? {
        val payload = line.removePrefix(OLCRTC_URI_PREFIX)

        val transportMarker = payload.indexOf('?')
        val roomMarker = payload.indexOf('@', startIndex = transportMarker + 1)
        val keyMarker = payload.indexOf('#', startIndex = roomMarker + 1)

        if (transportMarker <= 0 || roomMarker <= transportMarker || keyMarker <= roomMarker) {
            return null
        }

        val clientMarker = payload
            .indexOf('%', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val mimoMarker = payload
            .indexOf('$', startIndex = keyMarker + 1)
            .takeIf { it >= 0 }

        val keyEnd = listOfNotNull(clientMarker, mimoMarker).minOrNull() ?: payload.length

        val provider = payload.substring(0, transportMarker).trim()
        val transportToken = payload.substring(transportMarker + 1, roomMarker).trim()
        val (transport, transportOptions) = parseTransportToken(transportToken)
        val roomId = payload.substring(roomMarker + 1, keyMarker).trim()
        val key = payload.substring(keyMarker + 1, keyEnd).trim()

        val mimo = mimoMarker
            ?.let { payload.substring(it + 1) }
            .orEmpty()
            .trim()

        val location = LocationConfig(
            name = mimo.ifBlank { roomId },
            id = roomId,
            key = key,
            bypassProvider = provider,
            transport = transport,
            vp8Fps = transportOptions["vp8-fps"]
                ?: transportOptions["fps"]
                ?: LocationConfig.DEFAULT_VP8_FPS,
            vp8Batch = transportOptions["vp8-batch"]
                ?: transportOptions["batch"]
                ?: LocationConfig.DEFAULT_VP8_BATCH
        ).normalized()

        return location
            .takeIf { it.isComplete() }
            ?.let { ParsedOlcRtcUri(it, mimo.takeIf { value -> value.isNotBlank() }) }
    }

    private fun buildSubscriptionMetadata(fields: Map<String, String>): SubscriptionMetadata? {
        return SubscriptionMetadata(
            name = fields["name"],
            update = fields["update"],
            refresh = fields["refresh"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"]
        ).normalized().takeUnless { it.isEmpty() }
    }

    private fun buildLocationMetadata(
        fields: Map<String, String>,
        mimo: String?,
        subscription: SubscriptionMetadata?
    ): LocationMetadata? {
        return LocationMetadata(
            name = fields["name"],
            color = fields["color"],
            icon = fields["icon"],
            used = fields["used"],
            available = fields["available"],
            ip = fields["ip"],
            comment = fields["comment"],
            mimo = mimo,
            subscription = subscription
        ).normalized().takeUnless { it.isEmpty() }
    }

    private fun parseTransportToken(token: String): Pair<String, Map<String, Int>> {
        val optionsStart = token.indexOf('<')
        val optionsEnd = token.lastIndexOf('>')
        if (optionsStart < 0 || optionsEnd <= optionsStart) {
            return token to emptyMap()
        }

        val transport = token.substring(0, optionsStart).trim()
        val options = token.substring(optionsStart + 1, optionsEnd)
            .split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = part.substring(0, separator).trim().lowercase()
                val value = part.substring(separator + 1).trim().toIntOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()

        return transport to options
    }

    private fun parseSubscriptionField(value: String): Pair<String, String>? {
        val separator = value.indexOf(':')
        if (separator <= 0) return null

        val key = value.substring(0, separator).trim().lowercase()
        val fieldValue = value.substring(separator + 1).trim()

        return key to fieldValue
    }

    private fun uniqueStorageId(base: String, used: MutableSet<String>): String {
        val normalizedBase = base.storageSlug()
        var candidate = normalizedBase
        var suffix = 2

        while (!used.add(candidate)) {
            candidate = "${normalizedBase}_$suffix"
            suffix += 1
        }

        return candidate
    }

    private fun decodeLocationEntry(root: JsonObject, subscriptionUrl: String? = null): LocationEntry? {
        return runCatching {
            json.decodeFromJsonElement(LocationEntry.serializer(), root)
                .let { entry ->
                    if (entry.subscriptionUrl.isNullOrBlank() && !subscriptionUrl.isNullOrBlank()) {
                        entry.copy(subscriptionUrl = subscriptionUrl)
                    } else {
                        entry
                    }
                }
                .normalized()
                .takeIf { it.location.isStorable() }
        }.getOrNull()
    }

    private fun subscriptionSignature(location: LocationConfig): String {
        val normalized = location.normalized()
        return listOf(
            normalized.bypassProvider,
            normalized.transport,
            normalized.id,
            normalized.key
        ).joinToString("|")
    }

    private fun LocationConfig.storageSlug(): String {
        return displayName().ifBlank { id }.storageSlug()
    }

    private fun String.storageSlug(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "location" }
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.int(name: String): Int? {
        return (this[name] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { jsonObject }.getOrNull()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    private fun firstInt(vararg values: Int?): Int? {
        return values.firstOrNull { it != null }
    }

    private fun transportArgInt(args: String, name: String): Int? {
        if (args.isBlank()) return null

        val parts = args.split(Regex("\\s+")).filter { it.isNotBlank() }
        val index = parts.indexOf(name)

        return parts.getOrNull(index + 1)?.toIntOrNull()
    }

    private fun HttpResponse.profileUpdateIntervalHours(): Int? {
        return headers["profile-update-interval"]
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(
                SubscriptionMetadata.MIN_UPDATE_INTERVAL_HOURS,
                SubscriptionMetadata.MAX_UPDATE_INTERVAL_HOURS
            )
    }

    private fun List<LocationEntry>.subscriptionUpdateIntervalHours(): Int? {
        return firstNotNullOfOrNull { entry ->
            entry.metadata?.subscription?.updateIntervalHours
        }
    }

    /** Auto-update is considered disabled for the group if ANY of its entries has it turned off. */
    private fun List<LocationEntry>.subscriptionAutoUpdateEnabled(): Boolean {
        return none { entry -> entry.metadata?.subscription?.autoUpdateEnabled == false }
    }

    private fun SubscriptionMetadata?.withSubscriptionInterval(hours: Int?): SubscriptionMetadata? {
        if (hours == null) return this
        return (this ?: SubscriptionMetadata()).copy(
            updateIntervalHours = hours
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionInterval(hours: Int?): LocationMetadata? {
        if (hours == null) return this
        return withSubscriptionRefreshState(
            updateIntervalHours = hours,
            lastRefreshAtEpochMs = this?.subscription?.lastRefreshAtEpochMs,
            lastAttemptAtEpochMs = this?.subscription?.lastAttemptAtEpochMs,
            autoUpdateEnabled = this?.subscription?.autoUpdateEnabled ?: true
        )
    }

    /** Flips the per-subscription auto-update switch, keeping the rest of the metadata intact. */
    private fun LocationMetadata?.withSubscriptionAutoUpdate(enabled: Boolean): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(autoUpdateEnabled = enabled)
        ).normalized()
    }

    private fun LocationMetadata?.withSubscriptionRefreshState(
        updateIntervalHours: Int,
        lastRefreshAtEpochMs: Long?,
        lastAttemptAtEpochMs: Long? = lastRefreshAtEpochMs,
        autoUpdateEnabled: Boolean = this?.subscription?.autoUpdateEnabled ?: true
    ): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(
                updateIntervalHours = updateIntervalHours,
                lastRefreshAtEpochMs = lastRefreshAtEpochMs,
                lastAttemptAtEpochMs = lastAttemptAtEpochMs,
                autoUpdateEnabled = autoUpdateEnabled
            )
        ).normalized()
    }

    /** Records only a refresh ATTEMPT time (failure path), keeping the existing links/metadata intact. */
    private fun LocationMetadata?.withSubscriptionAttemptState(lastAttemptAtEpochMs: Long): LocationMetadata {
        val subscription = this?.subscription ?: SubscriptionMetadata()
        return (this ?: LocationMetadata()).copy(
            subscription = subscription.copy(lastAttemptAtEpochMs = lastAttemptAtEpochMs)
        ).normalized()
    }

    private companion object {
        const val OLCRTC_URI_PREFIX = "olcrtc://"
        const val UTF8_BOM = "\uFEFF"
        /** Happ provider-tracking check endpoint; the provider id is appended as the `id` query param. */
        const val PROVIDER_CHECK_URL = "https://check.happ-proxy.com/provider?id="
    }
}
