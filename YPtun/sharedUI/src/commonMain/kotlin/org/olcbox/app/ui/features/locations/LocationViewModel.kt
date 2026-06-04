package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.data.repository.LocationsRepository

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

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    private val activePingJobs = mutableMapOf<String, Job>()
    private val pingSemaphore = Semaphore(LOCATION_PING_PARALLELISM)
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

    var proxyError by mutableStateOf<String?>(null)
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

    val isFormValid: Boolean
        get() = nameError == null && editingName.isNotBlank() && when (editingConfig.engine) {
            EngineType.Stealth -> olcrtcFieldsValid
            EngineType.Standard -> editingConfig.proxy?.isComplete() == true
            EngineType.Chain -> editingConfig.proxy?.isComplete() == true && olcrtcFieldsValid
            EngineType.VkTurn -> vkTurnFieldsValid
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
            val bundle = locationsRepository.getBundle()
            val savedConfigs = bundle.locations
            val currentSelectedId = bundle.activeLocationId

            val nextLocations = savedConfigs.map { entry ->
                val normalized = entry.location
                LocationItem(
                    storageId = entry.storageId,
                    fullName = normalized.displayName(),
                    config = normalized,
                    subscriptionUrl = entry.subscriptionUrl,
                    metadata = entry.metadata
                )
            }

            if (requestId != loadLocationsRequest) return@launch

            locations.clear()
            locations.addAll(nextLocations)

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
        performPing: suspend (LocationConfig) -> Long?,
        onComplete: (onlineCount: Int, totalCount: Int) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    ) {
        val previousPings = currentPingsSnapshot()
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
                        pingSemaphore.withPermit {
                            checkLocationPing(location, performPing)?.toInt()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    val updatedPings = currentPingsSnapshot().toMutableMap()
                    updatedPings[location.storageId] = ping

                    activePingJobs.remove(location.storageId)

                    if (ping != null) {
                        onlineForThisRequest++
                    }

                    completedForThisRequest++

                    emitPingState(updatedPings.toMap())

                    if (completedForThisRequest == totalForThisRequest) {
                        onComplete(onlineForThisRequest, totalForThisRequest)
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
        pings: Map<String, Int?> = currentPingsSnapshot()
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
        editingProxyLink = ""
        isSaving = false
        providerDrafts.clear()

        if (id == null) {
            editingId = null
            editingConfig = LocationConfig()
            editingName = ""
            editingSubscriptionUrl = null
            editingSubscriptionIntervalHours = SubscriptionMetadata.DEFAULT_UPDATE_INTERVAL_HOURS.toString()
        } else {
            val location = locations.find { it.storageId == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingName = editingConfig.displayName()
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

    fun onCoreChanged(core: ProxyCore) {
        editingConfig = editingConfig.copy(core = core)
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
            editingConfig = editingConfig.copy(vkturn = null, proxy = null)
            editingProxyLink = ""
            proxyError = null
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
            name.length > 30 -> "Name is too long (max 30 chars)"
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

            val id = editingId ?: "custom_${(100..999).random()}"
            val finalConfig = editingConfig.copy(name = editingName).normalized()

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

    /** Deletes several locations at once (e.g. all configs of one subscription). */
    fun deleteLocations(ids: List<String>, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            ids.forEach { locationsRepository.deleteLocation(it) }
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

    private companion object {
        const val LOCATION_PING_ATTEMPTS = 1
        const val LOCATION_PING_TIMEOUT_MS = 12_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 0L
        const val LOCATION_PING_PARALLELISM = 4
    }

    private data class ProviderDraft(
        val room: String = "",
        val key: String = ""
    )
}
