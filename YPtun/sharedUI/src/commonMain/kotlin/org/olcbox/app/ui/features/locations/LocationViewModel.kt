package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.importer.VkTurnComposer
import org.olcbox.app.data.importer.VkTurnDraft
import org.olcbox.app.data.model.AdvancedCoreConfig
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.ExtraRoom
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.DnsttConfig
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.share.ShareLinkComposer
import org.olcbox.app.data.share.YptunInboundCodec

/**
 * @Immutable: instances are never mutated in place (a new one is built on every change), so Compose
 * can treat it as a stable parameter and skip recomposing an unchanged [LocationRow] — important so a
 * ping update only recomposes the rows whose ping actually changed, not the whole list.
 */
@androidx.compose.runtime.Immutable
data class LocationItem(
    val storageId: String,
    val fullName: String,
    val config: LocationConfig? = null,
    val subscriptionUrl: String? = null,
    val metadata: LocationMetadata? = null
)

sealed class PingsState {
    object Idle : PingsState()

    data class Loading(
        val lastPings: Map<String, Int?>? = null,
        val currentPings: Map<String, Int?> = emptyMap(),
        val pendingLocationIds: Set<String> = emptySet(),
        val completed: Int = 0,
        val total: Int = 0
    ) : PingsState()

    data class Success(
        val pings: Map<String, Int?>
    ) : PingsState()

    data class Error(
        val message: String,
        val lastPings: Map<String, Int?>? = null
    ) : PingsState()
}

class LocationViewModel(
    private val locationsRepository: LocationsRepository,
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    /**
     * False until the first [loadLocations] completes. Lets the UI avoid flashing the empty
     * "add your first config" card during the initial async load (locations start as an empty list).
     */
    var hasLoadedLocations by mutableStateOf(false)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    /**
     * Invoked when a ping pass completes, with the latest results — including failed/unreachable
     * locations (value `null`), so the host can persist the offline state too, not just the
     * successful ones (see [AppBehaviorSettings.savePingResults]).
     */
    var onPingsCompleted: ((Map<String, Int?>) -> Unit)? = null

    private val activePingJobs = mutableMapOf<String, Job>()
    // Source-of-truth ping map during a refresh pass. Each completion updates this in place (O(1))
    // instead of copying the whole map; the UI state is then refreshed on a throttled cadence (see
    // [schedulePingEmit]) so 300+ completions no longer trigger 300 full-list recompositions + 300
    // whole-map copies (the O(n²) "ping lag with many inbounds" bug). All mutations happen on the
    // main dispatcher (viewModelScope), so no locking is needed.
    private val livePings = mutableMapOf<String, Int?>()
    private var pingEmitJob: Job? = null
    private var loadLocationsJob: Job? = null
    private var loadLocationsRequest = 0
    private val providerDrafts = mutableMapOf<String, ProviderDraft>()

    var editingConfig by mutableStateOf(LocationConfig())
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
    var editingSubscriptionUrl by mutableStateOf<String?>(null)
        private set
    var editingSubscriptionIntervalHours by mutableStateOf(SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS.toString())
        private set
    var editingServiceProvider by mutableStateOf(LocationConfig.DEFAULT_BYPASS_PROVIDER)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set

    /** Pasted proxy share link / raw sing-box outbound for Standard/Chain engines. */
    var editingProxyLink by mutableStateOf("")
        private set

    /** Editable VK-TURN (freeturn + WireGuard) fields for the [EngineType.VkTurn] engine. */
    var editingVkTurn by mutableStateOf(VkTurnDraft())
        private set

    /** Editable dnstt (DNS tunnel) fields for the [EngineType.Dnstt] engine. */
    var editingDnstt by mutableStateOf(DnsttConfig())
        private set

    var proxyError by mutableStateOf<String?>(null)
        private set

    /** Pasted link for the optional SECOND (cascade) proxy, chained on top of the main proxy. */
    var editingProxy2Link by mutableStateOf("")
        private set

    var proxy2Error by mutableStateOf<String?>(null)
        private set

    private val olcrtcFieldsValid: Boolean
        get() = serverError == null && keyError == null &&
                editingConfig.id.isNotBlank() && editingConfig.key.isNotBlank()

    /**
     * The freeturn peer + WireGuard keys needed to reach the tunnel. The per-client VK call link
     * is filled in separately and is not required to save (see [LocationConfig.isStorable]).
     */
    private val vkTurnFieldsValid: Boolean
        get() = with(editingVkTurn) {
            // WDTT core connects PURELY by the wdtt-server IP[:port] (+ a local listener); there is no
            // freeturn peer and no user-entered WireGuard keys (the WG config is fetched from the server
            // at runtime), so it gates on the wdtt-server address instead.
            if (core.equals(VkTurnConfig.CORE_WDTT, ignoreCase = true)) {
                return@with wdttPeer.isNotBlank() &&
                    (listenPort.trim().toIntOrNull() ?: 0) in 1..65535
            }
            val peerOk = peerHost.isNotBlank() &&
                (peerPort.trim().toIntOrNull() ?: 0) in 1..65535 &&
                (listenPort.trim().toIntOrNull() ?: 0) in 1..65535
            val exitOk = when (outbound) {
                // A proxy exit only needs a parseable share link; WG keys are unused.
                VkTurnConfig.OUTBOUND_PROXY ->
                    ShareLinkParser.parse(outboundProxyLink.trim())?.isComplete() == true
                // WireGuard / AmneziaWG need the client keypair + tunnel address.
                else -> wgPrivateKey.isNotBlank() && wgPeerPublicKey.isNotBlank() && wgAddress.isNotBlank()
            }
            peerOk && exitOk
        }

    /** dnstt needs a tunnel domain, server public key and a DNS resolver. */
    private val dnsttFieldsValid: Boolean
        get() = editingDnstt.isComplete()

    val isFormValid: Boolean
        get() = nameError == null && editingName.isNotBlank() && when (editingConfig.engine) {
            EngineType.Stealth -> olcrtcFieldsValid
            // The main proxy is always the primary outbound, so it must be valid. The optional second
            // (cascade) proxy is either parsed-and-complete or null, so it never blocks saving.
            EngineType.Standard -> editingConfig.proxy?.isComplete() == true
            EngineType.Chain -> editingConfig.proxy?.isComplete() == true && olcrtcFieldsValid
            EngineType.VkTurn -> vkTurnFieldsValid
            EngineType.Dnstt -> dnsttFieldsValid
        }

    init {
        loadLocations()
        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadLocations()
                }
        }
    }

    fun loadLocations(onComplete: () -> Unit = {}) {
        val requestId = ++loadLocationsRequest
        loadLocationsJob?.cancel()
        loadLocationsJob = viewModelScope.launch {
            // Fetch + parse + map OFF the main thread: with 300+ saved configs the bundle decode and
            // per-entry displayName() parsing froze the UI for seconds on app open (the "configs load
            // slowly" bug). Only the snapshotStateList mutation below must run on the main thread.
            val (nextLocations, currentSelectedId) = withContext(Dispatchers.IO) {
                val bundle = locationsRepository.getBundle()
                val mapped = bundle.locations.map { entry ->
                    val normalized = entry.location
                    LocationItem(
                        storageId = entry.storageId,
                        fullName = normalized.displayName(),
                        config = normalized,
                        subscriptionUrl = entry.subscriptionUrl,
                        metadata = entry.metadata
                    )
                }
                mapped to bundle.activeLocationId
            }

            if (requestId != loadLocationsRequest) return@launch

            locations.clear()
            locations.addAll(nextLocations)
            hasLoadedLocations = true

            val nextSelectedId = if (
                nextLocations.isNotEmpty() &&
                (
                        currentSelectedId.isNullOrBlank() ||
                                nextLocations.none { it.storageId == currentSelectedId }
                        )
            ) {
                nextLocations.firstOrNull()?.storageId
            } else {
                currentSelectedId
            }
            if (
                nextSelectedId != currentSelectedId &&
                nextLocations.any { it.storageId == nextSelectedId }
            ) {
                locationsRepository.setActiveLocationId(nextSelectedId)
            }

            if (requestId != loadLocationsRequest) return@launch

            selectedLocationId = nextSelectedId
            onComplete()
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.setActiveLocationId(id)
            selectedLocationId = id
            onComplete()
        }
    }

    fun refreshPings(
        targetLocationIds: List<String>? = null,
        parallelism: Int = LOCATION_PING_PARALLELISM,
        performPing: suspend (LocationConfig) -> Long?,
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        // Probe up to [parallelism] locations at once (the ping-speed knob). A fresh semaphore per pass
        // so a changed setting takes effect immediately.
        val semaphore = Semaphore(parallelism.coerceIn(1, 20))
        val previousPings = currentPingsSnapshot()
        // Seed the live map with prior results so a targeted refresh doesn't drop other groups' pings.
        livePings.clear()
        livePings.putAll(previousPings)
        val locationsSnapshot = locations.toList()

        val pingableLocations = locationsSnapshot
            .filter { location ->
                location.config?.isComplete() == true &&
                        (targetLocationIds == null || targetLocationIds.contains(location.storageId))
            }
            .filterNot { location ->
                activePingJobs.containsKey(location.storageId)
            }

        if (locationsSnapshot.isEmpty()) {
            if (activePingJobs.isEmpty()) {
                pingsState = PingsState.Success(emptyMap())
            }
            onComplete(0, 0)
            return
        }

        if (pingableLocations.isEmpty()) {
            emitPingState(previousPings)
            onComplete(0, 0)
            return
        }

        var completedForThisRequest = 0
        var onlineForThisRequest = 0
        val totalForThisRequest = pingableLocations.size
        val jobsToStart = mutableListOf<Job>()

        pingableLocations.forEach { location ->
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val ping = try {
                        semaphore.withPermit {
                            checkLocationPing(location, performPing)?.toInt()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    livePings[location.storageId] = ping

                    activePingJobs.remove(location.storageId)

                    if (ping != null) {
                        onlineForThisRequest++
                    }

                    completedForThisRequest++

                    if (completedForThisRequest == totalForThisRequest) {
                        // Final result of this pass: emit immediately (and cancel any pending throttle).
                        flushPingState()
                        onComplete(onlineForThisRequest, totalForThisRequest)
                    } else {
                        schedulePingEmit()
                    }
                } catch (e: CancellationException) {
                    activePingJobs.remove(location.storageId)
                    emitPingState()
                    throw e
                } catch (e: Exception) {
                    activePingJobs.remove(location.storageId)

                    val message = e.message ?: "HTTP ping failed"
                    onError(message)

                    emitPingState()
                }
            }

            activePingJobs[location.storageId] = job
            jobsToStart.add(job)
        }

        emitPingState(previousPings)
        jobsToStart.forEach { it.start() }
    }

    private fun currentPingsSnapshot(): Map<String, Int?> {
        return when (val state = pingsState) {
            PingsState.Idle -> emptyMap()

            is PingsState.Loading -> {
                state.currentPings.ifEmpty {
                    state.lastPings.orEmpty()
                }
            }

            is PingsState.Success -> {
                state.pings
            }

            is PingsState.Error -> {
                state.lastPings.orEmpty()
            }
        }
    }

    private fun emitPingState(
        pings: Map<String, Int?> = livePings.toMap()
    ) {
        val pendingIds = activePingJobs.keys.toSet()

        pingsState = if (pendingIds.isEmpty()) {
            PingsState.Success(pings)
        } else {
            PingsState.Loading(
                lastPings = pings,
                currentPings = pings,
                pendingLocationIds = pendingIds,
                completed = 0,
                total = pendingIds.size
            )
        }
    }

    /**
     * Coalesces frequent ping updates into at most one UI emission per [PING_EMIT_THROTTLE_MS]. While
     * a throttle window is open, additional completions just mutate [livePings]; one emission then
     * publishes the accumulated batch — turning an O(n²) recomposition storm into a steady trickle.
     */
    private fun schedulePingEmit() {
        if (pingEmitJob?.isActive == true) return
        pingEmitJob = viewModelScope.launch {
            delay(PING_EMIT_THROTTLE_MS)
            emitPingState()
        }
    }

    /** Emits the current [livePings] immediately, cancelling any pending throttled emission. */
    private fun flushPingState() {
        pingEmitJob?.cancel()
        pingEmitJob = null
        emitPingState()
        // Pass complete — hand all results (successful AND failed/null) to the host for optional persistence.
        onPingsCompleted?.invoke(livePings.toMap())
    }

    /**
     * Restores previously-saved ping results so they show on launch (until the next ping pass).
     * Values may be `null` for locations that were saved as unreachable/failed.
     */
    fun seedPings(pings: Map<String, Int?>) {
        if (pings.isEmpty()) return
        pings.forEach { (id, ms) -> livePings[id] = ms }
        if (activePingJobs.isEmpty()) {
            pingsState = PingsState.Success(livePings.toMap())
        }
    }

    private suspend fun checkLocationPing(
        location: LocationItem,
        performPing: suspend (LocationConfig) -> Long?
    ): Long? {
        val config = location.config?.takeIf { it.isComplete() } ?: return null

        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPing(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }

                if (result != null) {
                    return@withTimeoutOrNull result
                }

                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }

            null
        }
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        proxyError = null
        proxy2Error = null
        editingProxyLink = ""
        editingProxy2Link = ""
        isSaving = false
        providerDrafts.clear()

        if (id == null) {
            editingId = null
            // New inbound: proxy (additional outbound) starts OFF — the proxy section stays hidden
            // until the user enables it. Existing/imported locations keep their own value (default
            // true for legacy configs saved before the field existed, so they aren't disabled).
            editingConfig = LocationConfig(proxyEnabled = false)
            editingName = ""
            editingSubscriptionUrl = null
            editingSubscriptionIntervalHours = SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS.toString()
        } else {
            val location = locations.find { it.storageId == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingName = editingConfig.displayName()
            // Repopulate the proxy link fields from the stored config so the second-proxy toggle and
            // its link survive reopening/editing (otherwise the field showed blank and the cascade
            // looked disabled). These links are display-only — saving keeps editingConfig.proxy/proxy2
            // unless the user actually edits the field.
            editingProxyLink = editingConfig.proxy?.let { ShareLinkComposer.compose(it) }.orEmpty()
            editingProxy2Link = editingConfig.proxy2?.let { ShareLinkComposer.compose(it) }.orEmpty()
            editingSubscriptionUrl = location?.subscriptionUrl
            editingSubscriptionIntervalHours = (
                location?.metadata?.subscription?.updateIntervalHours
                    ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                ).toString()
        }
        editingVkTurn = if (editingConfig.engine == EngineType.VkTurn) {
            VkTurnComposer.decompose(editingConfig.vkturn, editingConfig.proxy)
        } else {
            VkTurnDraft()
        }
        editingDnstt = editingConfig.dnstt ?: DnsttConfig()
        val provider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        editingServiceProvider = if (provider == LocationConfig.PROVIDER_JITSI) {
            LocationConfig.DEFAULT_BYPASS_PROVIDER
        } else {
            provider
        }
        providerDrafts[provider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )
    }

    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
        // Keep the derived VK-TURN proxy tag in step with the location name.
        if (editingConfig.engine == EngineType.VkTurn) {
            updateVkTurnDraft { it }
        }
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onSniChanged(value: String) = Unit

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    // --- Multi-room (Stealth/Chain aggregation) ---
    fun onMultiRoomToggle(enabled: Boolean) {
        editingConfig = editingConfig.copy(multiRoomEnabled = enabled)
    }

    /** Stage-2 (Chain): bond the rooms into ONE reassembled stream instead of round-robin. */
    fun onMultiRoomBondToggle(enabled: Boolean) {
        editingConfig = editingConfig.copy(multiRoomBond = enabled)
    }

    fun onBondPortChanged(value: String) {
        editingConfig = editingConfig.copy(bondPort = value.filter(Char::isDigit).toIntOrNull() ?: 0)
    }

    fun onExtraRoomAdd() {
        if (editingConfig.extraRooms.size >= LocationConfig.MAX_EXTRA_ROOMS) return
        editingConfig = editingConfig.copy(
            extraRooms = editingConfig.extraRooms +
                ExtraRoom(provider = editingConfig.bypassProvider, transport = editingConfig.transport),
        )
    }

    fun onExtraRoomChanged(index: Int, transform: (ExtraRoom) -> ExtraRoom) {
        val list = editingConfig.extraRooms.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            editingConfig = editingConfig.copy(extraRooms = list)
        }
    }

    fun onExtraRoomRemoved(index: Int) {
        editingConfig = editingConfig.copy(
            extraRooms = editingConfig.extraRooms.filterIndexed { i, _ -> i != index },
        )
    }

    fun onCoreChanged(core: ProxyCore) {
        // xhttp / FakeDNS configs can only run on xray-core: silently coerce a sing-box pick to Xray.
        val effective = if (core == ProxyCore.SingBox && editingConfig.requiresXray()) {
            ProxyCore.Xray
        } else {
            core
        }
        editingConfig = editingConfig.copy(core = effective)
    }

    /** Enables/disables the proxy (additional outbound). When off, the location exits directly. */
    fun onProxyEnabledChanged(enabled: Boolean) {
        editingConfig = editingConfig.copy(proxyEnabled = enabled)
    }

    /** Sets this location's routing profile id ("" = global default, NONE_ID = no profile). */
    fun onRoutingProfileChanged(id: String) {
        editingConfig = editingConfig.copy(routingProfileId = id)
    }

    /** Applies an edit to the per-location advanced core options (mux / tfo / sniff / fragment). */
    fun updateAdvanced(transform: (AdvancedCoreConfig) -> AdvancedCoreConfig) {
        val current = editingConfig.advanced ?: AdvancedCoreConfig()
        editingConfig = editingConfig.copy(advanced = transform(current))
    }

    /** Per-client VK Calls join link for a VK-TURN location (not carried in the share link). */
    fun onVkLinkChanged(value: String) {
        updateVkTurnDraft { it.copy(vkLink = value) }
    }

    /**
     * Applies an edit to the VK-TURN draft and rebuilds the derived freeturn:// URI + WireGuard
     * outbound so [editingConfig] stays in sync for saving and validation.
     */
    fun updateVkTurnDraft(transform: (VkTurnDraft) -> VkTurnDraft) {
        val updated = transform(editingVkTurn)
        editingVkTurn = updated
        val (vkturn, proxy) = VkTurnComposer.compose(updated, editingName.ifBlank { "VK-TURN" })
        editingConfig = editingConfig.copy(vkturn = vkturn, proxy = proxy)
    }

    /** Applies an edit to the dnstt (DNS tunnel) config and keeps [editingConfig] in sync. */
    fun updateDnstt(transform: (DnsttConfig) -> DnsttConfig) {
        val updated = transform(editingDnstt)
        editingDnstt = updated
        editingConfig = editingConfig.copy(dnstt = updated)
    }

    fun onEngineChanged(engine: EngineType) {
        val previous = editingConfig.engine
        editingConfig = editingConfig.copy(engine = engine)
        // Clear olcRTC field errors when they are no longer required.
        if (engine == EngineType.Standard) {
            serverError = null
            keyError = null
        }
        if (engine == EngineType.VkTurn) {
            // Materialize the freeturn URI + WireGuard outbound from the current draft so a freshly
            // picked VK-TURN location is immediately consistent.
            updateVkTurnDraft { it }
        } else if (previous == EngineType.VkTurn) {
            // Drop the derived WireGuard outbound so it does not leak into proxy-based engines.
            editingConfig = editingConfig.copy(vkturn = null, proxy = null, proxy2 = null)
            editingProxyLink = ""
            editingProxy2Link = ""
            proxyError = null
            proxy2Error = null
        }
        if (engine == EngineType.Dnstt) {
            // Materialize the dnstt config from the current draft so the picked engine is consistent.
            editingConfig = editingConfig.copy(dnstt = editingDnstt)
        } else if (previous == EngineType.Dnstt) {
            editingConfig = editingConfig.copy(dnstt = null)
        }
    }

    /** Parses a pasted proxy share link or raw sing-box outbound into [editingConfig].proxy. */
    fun onProxyLinkChanged(value: String) {
        editingProxyLink = value
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            editingConfig = editingConfig.copy(proxy = null)
            proxyError = null
            return
        }
        val profile = ShareLinkParser.parse(trimmed) ?: rawOutboundProfile(trimmed)
        if (profile != null && profile.isComplete()) {
            editingConfig = editingConfig.copy(proxy = profile)
            proxyError = null
        } else {
            editingConfig = editingConfig.copy(proxy = null)
            proxyError = "Unrecognized proxy link or sing-box config"
        }
    }

    /** Parses a pasted link for the optional SECOND (cascade) proxy into [editingConfig].proxy2. */
    fun onProxy2LinkChanged(value: String) {
        editingProxy2Link = value
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            editingConfig = editingConfig.copy(proxy2 = null)
            proxy2Error = null
            return
        }
        val profile = proxyFromAnyLink(trimmed)
        if (profile != null && profile.isComplete()) {
            // For Standard/Chain the MAIN proxy editor is hidden (it normally comes from an import), so
            // this "additional proxy" is the ONLY visible proxy field. If there's no valid main proxy
            // yet, the user clearly means THIS to be their proxy → assign it as the main (otherwise the
            // location can't save: Chain/Standard isFormValid requires a complete main proxy). When a
            // main proxy already exists, it's a genuine SECOND/cascade hop (proxy2).
            val isChainOrStd = editingConfig.engine == EngineType.Standard || editingConfig.engine == EngineType.Chain
            if (isChainOrStd && editingConfig.proxy?.isComplete() != true) {
                editingConfig = editingConfig.copy(proxy = profile)
                editingProxyLink = trimmed
            } else {
                editingConfig = editingConfig.copy(proxy2 = profile)
            }
            proxy2Error = null
        } else {
            editingConfig = editingConfig.copy(proxy2 = null)
            proxy2Error = "Unrecognized proxy link or sing-box config"
        }
    }

    /**
     * Resolves a pasted cascade-proxy link to a [ProxyProfile]: a normal share link / sing-box JSON,
     * OR a `yptun://inbound` link (carrying a whole [LocationConfig]) — in which case its primary proxy
     * (or second proxy) is used as the cascade hop.
     */
    private fun proxyFromAnyLink(trimmed: String): ProxyProfile? {
        ShareLinkParser.parse(trimmed)?.let { return it }
        rawOutboundProfile(trimmed)?.let { return it }
        YptunInboundCodec.parse(trimmed)?.let { config -> return config.proxy ?: config.proxy2 }
        return null
    }

    private fun rawOutboundProfile(text: String): ProxyProfile? {
        if (!text.startsWith("{")) return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 0
        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: server
        return ProxyProfile(
            tag = tag,
            type = type,
            server = server,
            serverPort = port,
            rawOutbound = text
        )
    }

    fun onBypassProviderChanged(value: String) {
        val provider = LocationConfig.normalizeProvider(value)
        val currentProvider = LocationConfig.normalizeProvider(editingConfig.bypassProvider)
        if (provider == currentProvider) return

        providerDrafts[currentProvider] = ProviderDraft(
            room = editingConfig.id,
            key = editingConfig.key
        )

        if (provider != LocationConfig.PROVIDER_JITSI) {
            editingServiceProvider = provider
        }

        val restored = providerDrafts[provider] ?: ProviderDraft()

        editingConfig = editingConfig.copy(
            bypassProvider = provider,
            transport = LocationConfig.normalizeTransport(editingConfig.transport, provider),
            id = restored.room,
            key = restored.key
        )
        serverError = null
        keyError = null
    }

    fun onTransportChanged(value: String) {
        editingConfig = editingConfig.copy(
            transport = LocationConfig.normalizeTransport(value, editingConfig.bypassProvider)
        )
    }

    fun onVp8FpsChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Fps = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onVp8BatchChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Batch = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onSubscriptionIntervalChanged(value: String) {
        editingSubscriptionIntervalHours = value.filter { it.isDigit() }.take(3)
    }

    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 45 -> "Name is too long (max 45 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        val roomLabel = if (editingConfig.bypassProvider == LocationConfig.PROVIDER_JITSI) {
            "Room URL"
        } else {
            "Room ID"
        }
        serverError = when {
            server.isBlank() -> "$roomLabel cannot be empty"
            server.length > 256 -> "$roomLabel is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !key.matches(Regex("^[a-fA-F0-9]{64}$")) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        validateName(editingName)
        // olcRTC fields are only required for Stealth/Chain engines.
        if (editingConfig.engine != EngineType.Standard) {
            validateServer(editingConfig.id)
            validateKey(editingConfig.key)
        } else {
            serverError = null
            keyError = null
        }

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true

            val finalConfig = editingConfig.copy(name = editingName).normalized()

            // Dedup on add: if this is a brand-new location identical to one already saved, just select
            // the existing one instead of creating a duplicate.
            if (editingId == null) {
                val key = finalConfig.dedupKey()
                val existing = locations.firstOrNull { it.config?.dedupKey() == key }
                if (existing != null) {
                    locationsRepository.setActiveLocationId(existing.storageId)
                    loadLocations()
                    delay(600)
                    onComplete()
                    isSaving = false
                    return@launch
                }
            }

            val id = editingId ?: "custom_${(100..999).random()}"

            locationsRepository.saveLocation(id, finalConfig)
            editingSubscriptionUrl?.let { url ->
                val interval = editingSubscriptionIntervalHours.toIntOrNull()
                    ?: SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS
                locationsRepository.setSubscriptionUpdateInterval(url, interval)
            }
            locationsRepository.setActiveLocationId(id)

            loadLocations()

            delay(600)

            onComplete()

            isSaving = false
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocation(id)
            loadLocations(onComplete)
        }
    }

    /** Turns automatic refresh on/off for a single subscription (by URL), then reloads the list. */
    fun setSubscriptionAutoUpdate(subscriptionUrl: String, enabled: Boolean) {
        viewModelScope.launch {
            locationsRepository.setSubscriptionAutoUpdate(subscriptionUrl, enabled)
            loadLocations()
        }
    }

    /** Deletes several locations at once (e.g. all configs of one subscription) in a single rewrite. */
    fun deleteLocations(ids: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocations(ids)
            loadLocations(onComplete)
        }
    }

    /** Deletes every location that belongs to any subscription, keeping custom ones. */
    fun deleteAllSubscriptions(onComplete: () -> Unit = {}) {
        val ids = locations.filter { !it.subscriptionUrl.isNullOrBlank() }.map { it.storageId }
        deleteLocations(ids, onComplete)
    }

    /** Deletes all locations (subscriptions and custom). */
    fun deleteAllLocations(onComplete: () -> Unit = {}) {
        val ids = locations.map { it.storageId }
        deleteLocations(ids, onComplete)
    }

    /**
     * Storage ids of CUSTOM (non-subscription) locations that were probed and found unreachable
     * (a present-but-null entry in the latest ping results). Subscription locations are never included.
     */
    private fun unreachableCustomLocationIds(): List<String> {
        val pings = currentPingsSnapshot()
        return locations
            .filter { it.subscriptionUrl.isNullOrBlank() }
            // VK-TURN / dnstt can't be pinged off-tunnel (their result is null until connected) —
            // NEVER treat them as unreachable / delete them.
            .filter { it.config?.engine != EngineType.VkTurn && it.config?.engine != EngineType.Dnstt }
            .map { it.storageId }
            .filter { pings.containsKey(it) && pings[it] == null }
    }

    /**
     * Deletes custom locations that the last ping pass marked unreachable. Subscriptions are left
     * untouched. [onComplete] receives how many were removed (0 = nothing matched).
     */
    fun deleteUnreachableCustomLocations(onComplete: (Int) -> Unit = {}) {
        val ids = unreachableCustomLocationIds()
        if (ids.isEmpty()) {
            onComplete(0)
            return
        }
        deleteLocations(ids) { onComplete(ids.size) }
    }

    /**
     * Storage ids of CUSTOM locations whose configuration duplicates another location's. Subscription
     * entries seed the "seen" set first (so a custom copy of a subscription server counts as the
     * duplicate), and within the custom list the first occurrence is kept. Subscriptions are never
     * returned for deletion.
     */
    private fun duplicateCustomLocationIds(): List<String> {
        val seen = mutableSetOf<LocationConfig>()
        // Seed with subscription configs first so a custom duplicate of a subscription server is removed.
        locations
            .filter { !it.subscriptionUrl.isNullOrBlank() }
            .forEach { it.config?.dedupKey()?.let(seen::add) }
        val duplicates = mutableListOf<String>()
        locations
            .filter { it.subscriptionUrl.isNullOrBlank() }
            .forEach { item ->
                val key = item.config?.dedupKey() ?: return@forEach
                if (!seen.add(key)) duplicates.add(item.storageId)
            }
        return duplicates
    }

    /**
     * Deletes duplicate custom locations (identical configuration), keeping one copy of each.
     * Subscriptions are left untouched. [onComplete] receives how many were removed.
     */
    fun deleteDuplicateLocations(onComplete: (Int) -> Unit = {}) {
        val ids = duplicateCustomLocationIds()
        if (ids.isEmpty()) {
            onComplete(0)
            return
        }
        deleteLocations(ids) { onComplete(ids.size) }
    }

    private companion object {
        // Retry a few times: the proxy-HEAD probe spins a throwaway xray that binds a random local
        // port, so under a big batch (300+ "own" locations pinged at once) a transient port collision
        // or resource hiccup would otherwise show a false "unavailable". Each attempt re-rolls the port.
        const val LOCATION_PING_ATTEMPTS = 3
        const val LOCATION_PING_TIMEOUT_MS = 12_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 150L
        const val LOCATION_PING_PARALLELISM = 5
        const val PING_EMIT_THROTTLE_MS = 150L
    }

    private data class ProviderDraft(
        val room: String = "",
        val key: String = ""
    )
}
