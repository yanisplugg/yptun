package org.olcbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val locationsRepository: LocationsRepository,
    private val configImporter: ConfigImporter,
    private val logExporter: LogExporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeScreenState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = LocationConfig(),
            shouldShowConfigInvalidReminder = false,
            canStartVpn = false,
            startBlockedReason = "Add a location first"
        )
    )
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs

    init {
        loadCurrentConfig()
        startSubscriptionAutoRefresh()

        viewModelScope.launch {
            locationsRepository.changes
                .drop(1)
                .collect {
                    loadCurrentConfigNow()
                }
        }

        viewModelScope.launch {
            vpnManager.status.collect { status ->
                _state.update {
                    when (status) {
                        VpnStatus.Connected -> it.copy(isVpnConnected = true, isVpnLoading = false)
                        VpnStatus.Connecting -> it.copy(isVpnConnected = false, isVpnLoading = true)
                        VpnStatus.Reconnecting -> it.copy(isVpnConnected = true, isVpnLoading = true)
                        VpnStatus.Stopping -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        VpnStatus.Disconnected -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        is VpnStatus.Error -> it.copy(isVpnConnected = false, isVpnLoading = false)
                    }
                }
            }
        }

        viewModelScope.launch {
            vpnManager.connectedSinceEpochMs.collect { since ->
                _state.update { it.copy(connectedSinceEpochMs = since) }
            }
        }
    }

    fun loadCurrentConfig(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            loadCurrentConfigNow()
            onComplete()
        }
    }

    private suspend fun loadCurrentConfigNow() {
        val active = locationsRepository.getActiveLocation()
        if (active == null) {
            _state.update {
                it.copy(
                    selectedLocation = null,
                    configData = LocationConfig(),
                    canStartVpn = false,
                    startBlockedReason = "Add a location first"
                )
            }
            return
        }

        val normalized = active.location
        val locationItem = LocationItem(
            storageId = active.storageId,
            fullName = normalized.displayName(),
            config = normalized,
            subscriptionUrl = active.subscriptionUrl,
            metadata = active.metadata
        )

        _state.update {
            it.copy(
                configData = normalized,
                selectedLocation = locationItem,
                canStartVpn = normalized.isComplete(),
                startBlockedReason = if (normalized.isComplete()) null else "Complete active location first"
            )
        }
    }

    suspend fun performPing(): Long? {
        return vpnManager.ping(_state.value.configData)
    }

    suspend fun performPingFor(config: LocationConfig): Long? {
        return vpnManager.ping(config)
    }

    suspend fun checkConnectionFor(config: LocationConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        val status = vpnManager.status.value
        if (_state.value.isVpnLoading ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting
        ) {
            viewModelScope.launch {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected || vpnManager.status.value is VpnStatus.Connected) {
                    vpnManager.stopVpn()
                } else {
                    val active = locationsRepository.getActiveLocation()
                    if (active == null || !active.location.isComplete()) {
                        _state.update {
                            it.copy(
                                isVpnLoading = false,
                                canStartVpn = false,
                                startBlockedReason = "Add a valid location first"
                            )
                        }
                        return@launch
                    }
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun restartVpnIfRunning() {
        when (vpnManager.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting -> viewModelScope.launch {
                _state.update { it.copy(isVpnLoading = true) }
                vpnManager.startVpn()
            }

            VpnStatus.Disconnected,
            VpnStatus.Stopping,
            is VpnStatus.Error -> Unit
        }
    }
    private fun updateLocationConfig(block: (LocationConfig) -> LocationConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }
    fun onCopyFullConfigClicked() {
        viewModelScope.launch {
            configImporter.copyToClipboard(locationsRepository.exportBundle())
        }
    }

    fun suggestedLogsFileName(): String = "olcbox-logs.txt"

    fun onSaveLogsToFile(
        target: Any,
        onSaved: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.writeLogs(target, content)
                .onSuccess { savedPath ->
                    onSaved(
                        if (savedPath.isBlank() || savedPath == "Logs saved") {
                            "Logs saved"
                        } else {
                            "Logs saved to $savedPath"
                        }
                    )
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to save logs")
                }
        }
    }

    fun onShareLogs(
        onShared: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.shareLogs(content)
                .onSuccess { message -> onShared(message) }
                .onFailure { error -> onError(error.message ?: "Failed to share logs") }
        }
    }

    fun onPasteFromClipboard(
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text, onComplete, onError)
        } ?: onError("No clipboard data found")
    }

    fun onFileSelected(
        fileSource: Any,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val text = configImporter.readTextFromSource(fileSource)
            if (text == null) {
                onError("Could not read config file")
            } else {
                onImportFullConfig(text, onComplete, onError)
            }
        }
    }

    /** Routing profiles for the per-location selector (empty on platforms without support). */
    fun routingProfileChoices(): List<org.olcbox.app.data.model.RoutingProfile> =
        vpnManager.routingProfileChoices()

    fun onImportFullConfig(
        rawText: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (rawText.isBlank()) {
            onError("No config text found")
            return
        }
        // Bulk import: several subscription/share URLs pasted at once (one per line). We only treat
        // it as a batch when EVERY non-empty line is an http(s) URL (≥2 of them) — that's the only
        // unambiguous case. Multi-line sing-box/xray JSON, base64 blobs and vless:// lists are left
        // to the single-import path below (which already handles a multi-link block itself).
        run {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val httpUrls = lines.filter {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
            // Everything else that looks like a protocol share link (vless://, vmess://, ss://,
            // trojan://, freeturn://, …) — grouped into ONE blob so a 1000-line paste is parsed in a
            // single pass (parseProxyText splits it). Mixed pastes (subscription URLs + links) work.
            val protoLines = lines.filter { it !in httpUrls && it.contains("://") }
            val isBatch = httpUrls.size >= 2 || (httpUrls.isNotEmpty() && protoLines.isNotEmpty())
            if (isBatch) {
                val items = buildList {
                    addAll(httpUrls)
                    if (protoLines.isNotEmpty()) add(protoLines.joinToString("\n"))
                }
                importManySubscriptions(items, onComplete, onError)
                return
            }
        }
        // A happ://routing/add/... link OR raw Happ routing JSON is a routing profile, not a
        // location — hand it off instead of trying to parse it as a config.
        if (org.olcbox.app.data.importer.HappRoutingParser.looksLikeRoutingProfile(rawText)) {
            if (vpnManager.importRoutingProfileLink(rawText.trim())) {
                onComplete()
            } else {
                onError("Invalid routing profile")
            }
            return
        }
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    locationsRepository.importText(
                        text = rawText,
                        subscriptionProxy = vpnManager.subscriptionFetchProxy()
                    )
                }
                if (!imported) {
                    onError("No valid YPtun config found")
                    return@launch
                }
                loadCurrentConfigNow()
                maybePromptForVkTurnLink()
                onComplete()
            } catch (e: Exception) {
                val message = e.message ?: "Import failed"
                _state.update {
                    it.copy(
                        canStartVpn = false,
                        startBlockedReason = message
                    )
                }
                onError(message)
            }
        }
    }

    /**
     * Imports a batch of subscription/share URLs, one at a time, reusing the single-import path so
     * dedup/merge behave exactly as for one paste. Succeeds if at least one URL imported; reports how
     * many failed otherwise. Runs network I/O off the main thread.
     */
    private fun importManySubscriptions(
        urls: List<String>,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            var added = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (url in urls) {
                    val ok = runCatching {
                        locationsRepository.importText(
                            text = url,
                            subscriptionProxy = vpnManager.subscriptionFetchProxy()
                        )
                    }.getOrDefault(false)
                    if (ok) added++ else failed++
                }
            }
            if (added == 0) {
                onError("No valid subscriptions found ($failed failed)")
                return@launch
            }
            loadCurrentConfigNow()
            maybePromptForVkTurnLink()
            onComplete()
            if (failed > 0) onError("Imported $added, $failed failed")
        }
    }

    /**
     * After importing a freeturn:// link, the VK-TURN location has everything but the per-client
     * VK Calls link. Surface a prompt so the user can paste it right away (or defer with "Later").
     */
    private suspend fun maybePromptForVkTurnLink() {
        val active = locationsRepository.getActiveLocation() ?: return
        val config = active.location
        val needsLink = config.engine == EngineType.VkTurn &&
            config.vkturn?.vkLink.isNullOrBlank()
        if (needsLink) {
            _state.update {
                it.copy(
                    vkTurnLinkPrompt = VkTurnLinkPrompt(
                        storageId = active.storageId,
                        locationName = config.displayName()
                    )
                )
            }
        }
    }

    /** Saves the VK Calls link into the prompted VK-TURN location and dismisses the prompt. */
    fun submitVkTurnLink(storageId: String, vkLink: String) {
        viewModelScope.launch {
            val location = locationsRepository.loadLocation(storageId)
            val vkturn = location?.vkturn
            if (location != null && vkturn != null) {
                locationsRepository.saveLocation(
                    storageId,
                    location.copy(vkturn = vkturn.copy(vkLink = vkLink.trim()))
                )
                loadCurrentConfigNow()
            }
            _state.update { it.copy(vkTurnLinkPrompt = null) }
        }
    }

    /** Dismisses the VK Calls link prompt; the user can fill it in later from location settings. */
    fun dismissVkTurnLinkPrompt() {
        _state.update { it.copy(vkTurnLinkPrompt = null) }
    }

    fun refreshSubscriptions(
        onComplete: (updatedCount: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedCount = locationsRepository.refreshSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(updatedCount)
        }
    }

    fun refreshSubscription(
        subscriptionUrl: String,
        onComplete: (updatedCount: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedCount = locationsRepository.refreshSubscription(
                subscriptionUrl = subscriptionUrl,
                subscriptionProxy = vpnManager.subscriptionFetchProxy()
            )
            loadCurrentConfigNow()
            onComplete(updatedCount)
        }
    }

    private fun startSubscriptionAutoRefresh() {
        viewModelScope.launch {
            backfillMissingSubscriptionExpiry()
            // Once per launch: retry overdue subscriptions even if they failed last time (keyed off the
            // last successful refresh). The periodic poll keeps the failure backoff to avoid hammering.
            refreshDueSubscriptionsIfNeeded(retryFailed = true)
            while (true) {
                delay(SUBSCRIPTION_AUTO_REFRESH_POLL_MS)
                refreshDueSubscriptionsIfNeeded()
            }
        }
    }

    /**
     * One-shot (per launch) backfill so the "show subscription expiry" toggle works on subscriptions
     * imported before the expiry was captured — they have no stored expiry until refreshed. Targets
     * only those URLs and ignores the auto-refresh interval. Best-effort; failures are ignored.
     */
    private suspend fun backfillMissingSubscriptionExpiry() {
        val updatedCount = withContext(Dispatchers.IO) {
            runCatching {
                locationsRepository.refreshSubscriptionsMissingExpiry(
                    subscriptionProxy = vpnManager.subscriptionFetchProxy()
                )
            }.getOrDefault(0)
        }
        if (updatedCount > 0) {
            loadCurrentConfigNow()
        }
    }

    /**
     * Called when the user turns the "show subscription expiry" toggle ON: force-refresh every
     * subscription now (network is ready, unlike a cold-launch backfill) so the "до …" date is
     * fetched and shown right away. Best-effort; the location list reloads via the repository's
     * change flow.
     */
    fun refreshSubscriptionExpiryNow(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    locationsRepository.refreshSubscriptions(
                        subscriptionProxy = vpnManager.subscriptionFetchProxy()
                    )
                }
            }
            loadCurrentConfigNow()
            onComplete()
        }
    }

    private suspend fun refreshDueSubscriptionsIfNeeded(retryFailed: Boolean = false) {
        val updatedCount = withContext(Dispatchers.IO) {
            locationsRepository.refreshDueSubscriptions(
                subscriptionProxy = vpnManager.subscriptionFetchProxy(),
                retryFailed = retryFailed
            )
        }
        if (updatedCount > 0) {
            loadCurrentConfigNow()
        }
    }

    private fun buildLogsExport(logs: List<String>): String {
        return buildString {
            appendLine("YPtun application logs")
            appendLine("Entries: ${logs.size}")
            appendLine()
            logs.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }
    }
}

data class HomeScreenState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: LocationConfig,
    val shouldShowConfigInvalidReminder: Boolean,
    val canStartVpn: Boolean,
    val startBlockedReason: String?,
    /** Wall-clock epoch-ms when the connection started (0 = not connected); drives the on-screen timer. */
    val connectedSinceEpochMs: Long = 0L,
    /** Set after importing a VK-TURN link that still needs a per-client VK Calls link. */
    val vkTurnLinkPrompt: VkTurnLinkPrompt? = null
)

/** Prompt to collect the per-client VK Calls link for a freshly imported VK-TURN location. */
data class VkTurnLinkPrompt(
    val storageId: String,
    val locationName: String
)

private const val SUBSCRIPTION_AUTO_REFRESH_POLL_MS = 60L * 60L * 1_000L
