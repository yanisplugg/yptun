package org.olcbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.ProxyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.share.YptunInboundCodec
import org.olcbox.app.ui.features.home.components.FreeServerItem
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.ExpiringSubscriptionInfo
import org.olcbox.app.vpn.PanelAnnouncementInfo
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
        migrateFreeServersToXray()

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
                        is VpnStatus.Error -> it.copy(
                            isVpnConnected = false,
                            isVpnLoading = false,
                            connectError = status.message
                        )
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

    /** Budget for one location's whole ping (see [VpnManager.pingBudgetMs]). */
    val pingBudgetMs: Long get() = vpnManager.pingBudgetMs

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
            _state.update { it.copy(isVpnLoading = true, connectError = null) }
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

    /**
     * "Auto = fastest": connects to the first location in [orderedStorageIds] (already sorted
     * fastest-first by the caller) that comes up, advancing to the next on failure/timeout. Pure
     * app-level orchestration over the public [VpnManager] API ([startVpn]/[stopVpn]/[status]) — it
     * never touches the running service's watchdog/recovery, so it can't destabilise a live tunnel.
     * [onResult] gets the connected location's display name, or null if none of the candidates came up.
     */
    fun autoConnectInOrder(
        orderedStorageIds: List<String>,
        onResult: (connectedName: String?) -> Unit = {}
    ) {
        if (orderedStorageIds.isEmpty()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            // Start from a clean state so each attempt's status transition is unambiguous.
            val s0 = vpnManager.status.value
            if (s0 is VpnStatus.Connected || s0 is VpnStatus.Connecting || s0 is VpnStatus.Reconnecting) {
                vpnManager.stopVpn()
                withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) {
                    vpnManager.status.first { it is VpnStatus.Disconnected || it is VpnStatus.Error }
                }
            }

            var connectedName: String? = null
            for (id in orderedStorageIds.take(AUTO_CONNECT_MAX_ATTEMPTS)) {
                val config = locationsRepository.loadLocation(id) ?: continue
                if (!config.isComplete()) continue
                locationsRepository.setActiveLocationId(id)
                loadCurrentConfigNow()
                vpnManager.startVpn()
                // Wait for THIS attempt's terminal status: drop the stale pre-connect states, then take
                // the first Connected (success) or a fresh Error (failure). Timeout = treat as failure.
                val outcome = withTimeoutOrNull(AUTO_CONNECT_ATTEMPT_TIMEOUT_MS) {
                    vpnManager.status
                        .dropWhile { it is VpnStatus.Disconnected || it is VpnStatus.Error || it is VpnStatus.Stopping }
                        .first { it is VpnStatus.Connected || it is VpnStatus.Error }
                }
                if (outcome is VpnStatus.Connected) {
                    connectedName = config.displayName()
                    break
                }
                // Failed/timeout → tear down before trying the next candidate.
                vpnManager.stopVpn()
                withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) {
                    vpnManager.status.first { it is VpnStatus.Disconnected || it is VpnStatus.Error }
                }
            }

            if (connectedName == null) _state.update { it.copy(isVpnLoading = false) }
            onResult(connectedName)
        }
    }

    /** The Home snackbar showed [HomeScreenState.connectError]; don't show it again on the next visit. */
    fun clearConnectError() {
        _state.update { it.copy(connectError = null) }
    }

    fun restartVpnIfRunning() {
        when (vpnManager.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting -> viewModelScope.launch {
                _state.update { it.copy(isVpnLoading = true, connectError = null) }
                vpnManager.startVpn()
            }

            // Stopping = the user pressed Stop: a settings change must not bring the VPN back.
            VpnStatus.Disconnected,
            VpnStatus.Stopping -> Unit
            is VpnStatus.Error -> {
                _state.update { it.copy(isVpnLoading = false) }
            }
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
                    onError(importFailureReason(rawText))
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
     * Free servers are checked through xray-core (see pingVerified), but with the core on «Авто» they connect
     * through sing-box, and plenty of public configs only work in xray — they showed a good ping and then
     * Chrome got ERR_CONNECTION_CLOSED. Servers added before this fix get the same core they were checked with.
     * Only «Авто» is touched: a core the user picked by hand stays.
     */
    private fun migrateFreeServersToXray() {
        viewModelScope.launch {
            runCatching {
                val bundle = locationsRepository.getBundle()
                val fixed = bundle.locations.map {
                    val unset = it.core == null || it.core == org.olcbox.app.data.model.ProxyCore.Auto
                    if (unset && it.subscriptionUrl?.trim() == FREE_SERVERS_URL) {
                        it.copy(core = org.olcbox.app.data.model.ProxyCore.Xray)
                    } else it
                }
                if (fixed != bundle.locations) locationsRepository.saveBundle(bundle.copy(locations = fixed))
            }
        }
    }

    private var freeServersJob: Job? = null

    /**
     * Number of the current free-servers run. A cancelled run does not stop at once — its checks sit in
     * blocking native calls for up to the ping timeout — and when it finally unwinds, its cleanup used to
     * switch the loading flag off for the run the user had started in the meantime, so a search started
     * right after «Отмена» seemed to do nothing. Every state write of a run is tagged with its number and
     * dropped when it is no longer the current one.
     */
    private var freeServersRun = 0

    private fun updateFreeServers(run: Int, transform: (HomeScreenState) -> HomeScreenState) {
        _state.update { if (run == freeServersRun) transform(it) else it }
    }

    /** The working servers the current run has found so far (replaced, never mutated, so a stop can read it at once). */
    @kotlin.concurrent.Volatile
    private var freeServersFound: List<FreeServerItem> = emptyList()

    // The list the last search drew up, and which of its servers are checked: what lets a stopped search carry on.
    @kotlin.concurrent.Volatile
    private var freeParsed: List<Pair<String, ProxyProfile>> = emptyList()
    private val freeCheckedIdx = HashSet<Int>()

    // Pause of the search: servers in flight finish, no new one starts. The pace is measured without the pause.
    private val freeGate = MutableStateFlow(false)
    private var freeRunStartMs = 0L
    private var freePausedAccumMs = 0L
    private var freePausedSinceMs = 0L
    private var freeRunChecks = 0
    private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** Seconds to the end of the search at the pace so far; null while paused or with too few samples. */
    private fun freeEta(total: Int, checked: Int): Int? {
        if (freeGate.value || freeRunChecks < 5) return null
        val activeMs = nowMs() - freeRunStartMs - freePausedAccumMs
        if (activeMs <= 0L) return null
        return (activeMs.toDouble() / freeRunChecks * (total - checked).coerceAtLeast(0) / 1000.0).toInt()
    }

    /** Servers of the last search that are not checked yet (what «Продолжить поиск» has to go through). */
    private fun freeRemaining(): Int = (freeParsed.size - freeCheckedIdx.size).coerceAtLeast(0)

    /**
     * Ends the search NOW but keeps what it found: the servers checked so far go to the picker sheet instead of
     * being thrown away (a full pass over a long list takes hours). The unchecked rest stays in the model so the
     * search can be continued. [onNothingFound] when nothing worked yet.
     */
    fun stopFreeServersSearch(onNothingFound: () -> Unit = {}) {
        val found = freeServersFound.sortedBy { it.pingMs }
        freeServersRun++
        freeServersJob?.cancel()
        freeServersJob = null
        freeGate.value = false
        _state.update {
            it.copy(
                isFreeServersLoading = false,
                freeServersBackground = false,
                freeServersPaused = false,
                freeServersProgress = null,
                freeServersResumable = freeRemaining(),
                availableFreeServers = found.takeIf { list -> list.isNotEmpty() },
            )
        }
        if (found.isEmpty()) onNothingFound()
    }

    /** Holds the free-servers search: servers in flight finish, no new one starts until [resumeFreeServersPaused]. */
    fun pauseFreeServersSearch() {
        if (freeServersJob?.isActive != true || freeGate.value) return
        freePausedSinceMs = nowMs()
        freeGate.value = true
        _state.update { it.copy(freeServersPaused = true) }
    }

    fun resumeFreeServersPaused() {
        if (!freeGate.value) return
        freePausedAccumMs += nowMs() - freePausedSinceMs
        freePausedSinceMs = 0L
        freeGate.value = false
        _state.update { it.copy(freeServersPaused = false) }
    }

    // True while the running search is the silent one (auto-update, or a manual search sent to the background):
    // its result is saved straight away instead of opening the picker. Read at the END of the run.
    @kotlin.concurrent.Volatile
    private var freeRunInBackground = false

    /**
     * Sends the running manual search to the background: the dialog closes, the strip under the toolbar shows the
     * progress (same as the auto-update), and the found servers are saved without the picker when it ends.
     */
    fun sendFreeServersToBackground() {
        if (freeServersJob?.isActive != true) return
        freeRunInBackground = true
        _state.update { it.copy(isFreeServersLoading = false, freeServersBackground = true) }
    }

    private var freeAutoAttemptMs = 0L

    /**
     * Auto-update of the free servers (the switch in the group's menu): a silent search every few hours whose
     * result replaces the unpinned servers without the picker. The Home screen calls it periodically; it does
     * nothing unless the switch is on, the group exists and the interval has passed — and never while a search
     * runs, the picker is open, a stopped search waits to be continued, or the tunnel runs over a free server
     * that the save would replace.
     */
    fun autoUpdateFreeServersIfDue(parallelism: Int, limit: Int) {
        val st = _state.value
        if (freeServersJob?.isActive == true || st.availableFreeServers != null || st.freeServersResumable > 0) return
        val sel = st.selectedLocation
        if (st.isVpnConnected && sel?.subscriptionUrl?.trim() == FREE_SERVERS_URL && sel.metadata?.pinned != true) return
        viewModelScope.launch {
            val subs = runCatching {
                locationsRepository.getBundle().locations.filter { it.subscriptionUrl?.trim() == FREE_SERVERS_URL }
            }.getOrNull().orEmpty().mapNotNull { it.metadata?.subscription }
            if (subs.isEmpty() || subs.any { !it.autoUpdateEnabled }) return@launch
            val hours = subs.firstNotNullOfOrNull { it.updateIntervalHours?.takeIf { h -> h > 0 } } ?: FREE_AUTO_UPDATE_HOURS
            val last = maxOf(
                freeAutoAttemptMs,
                subs.maxOf { maxOf(it.lastRefreshAtEpochMs ?: 0L, it.lastAttemptAtEpochMs ?: 0L) }
            )
            val now = nowMs()
            if (now - last < hours * 3_600_000L) return@launch
            if (freeServersJob?.isActive == true || _state.value.availableFreeServers != null) return@launch
            freeAutoAttemptMs = now
            loadFreeServers(parallelism = parallelism, limit = limit, background = true)
        }
    }

    /** Throws the search away for good: found servers and the unchecked rest. */
    fun cancelFreeServersLoad() {
        freeServersRun++
        freeServersJob?.cancel()
        freeServersJob = null
        freeGate.value = false
        freeParsed = emptyList()
        freeCheckedIdx.clear()
        freeServersFound = emptyList()
        _state.update {
            it.copy(
                isFreeServersLoading = false,
                freeServersBackground = false,
                freeServersPaused = false,
                freeServersProgress = null,
                freeServersResumable = 0
            )
        }
    }

    fun dismissFreeServersSheet() {
        _state.update { it.copy(availableFreeServers = null, freeServersProgress = null) }
    }

    fun loadFreeServers(
        parallelism: Int = org.olcbox.app.data.model.AppBehaviorSettings.DEFAULT_PING_PARALLELISM,
        limit: Int = org.olcbox.app.data.model.AppBehaviorSettings.DEFAULT_FREE_SERVERS_LIMIT,
        onError: (String) -> Unit = {},
        // Auto-update: no dialog, and the result is saved straight away instead of opening the picker.
        background: Boolean = false,
        // Carry on a stopped search over the servers it has not checked yet: no new download, what it found stays.
        resume: Boolean = false,
    ) {
        if (resume && freeRemaining() == 0) return
        freeServersJob?.cancel()
        val run = ++freeServersRun
        freeGate.value = false
        freeRunStartMs = nowMs()
        freePausedAccumMs = 0L
        freePausedSinceMs = 0L
        freeRunChecks = 0
        freeRunInBackground = background
        if (!resume) freeServersFound = emptyList()
        _state.update {
            it.copy(
                isFreeServersLoading = !background,
                freeServersBackground = background,
                freeServersPaused = false,
                freeServersResumable = 0,
                availableFreeServers = null,
                freeServersProgress = null
            )
        }
        freeServersJob = viewModelScope.launch {
            try {
                if (!resume) {
                    val sourceTexts = withContext(Dispatchers.IO) {
                        // With the VPN up this is the tunnel's local SOCKS, which wants the session login —
                        // without withProxyAuthentication the fetch died with a SOCKS auth error.
                        val proxy = vpnManager.subscriptionFetchProxy()
                        val client = createProxyHttpClient(proxy)
                        try {
                            // Все источники сразу; недоступный не мешает остальным.
                            // One shuffled slice of the Pages file as big as the limit asks (0 = all of it).
                            val sources = if (vpnManager.freeServersFromPages) {
                                listOf(FreeSource(FREE_SERVERS_PAGES_URL, maxCount = limit))
                            } else {
                                freeSourcesFor(org.olcbox.app.ui.i18n.LocalizationState.effective.id)
                            }
                            sources.map { source ->
                                async {
                                    runCatching {
                                        org.olcbox.app.data.datasource.withProxyAuthentication(proxy) { client.get(source.url).bodyAsText() }
                                    }.getOrDefault("") to source.maxCount
                                }
                            }.awaitAll()
                        } finally {
                            client.close()
                        }
                    }

                    if (sourceTexts.all { (text, _) -> text.isBlank() }) {
                        updateFreeServers(run) { it.copy(isFreeServersLoading = false, freeServersBackground = false, freeServersProgress = null) }
                        onError("Не удалось загрузить список серверов")
                        return@launch
                    }

                    val picked = withContext(Dispatchers.Default) { pickFreeServers(sourceTexts, limit) }
                    if (picked.isEmpty()) {
                        updateFreeServers(run) { it.copy(isFreeServersLoading = false, freeServersBackground = false, freeServersProgress = null) }
                        onError("Не удалось распознать конфигурации серверов")
                        return@launch
                    }
                    freeParsed = picked
                    freeCheckedIdx.clear()
                }

                val parsedItems = freeParsed
                val total = parsedItems.size
                val mutex = Mutex()
                var checkedCount = freeCheckedIdx.size
                // Empty for a fresh search, the earlier finds when the search is continued.
                val workingServers = freeServersFound.toMutableList()
                updateFreeServers(run) {
                    it.copy(freeServersProgress = FreeServersProgress(checked = checkedCount, total = total, found = workingServers.size))
                }
                val todo = parsedItems.withIndex().filter { it.index !in freeCheckedIdx }

                withContext(Dispatchers.IO) {
                    // Тот же ползунок «Потоки пинга» из настроек, что и у обычного пинга.
                    val sem = Semaphore(
                        parallelism.coerceIn(
                            org.olcbox.app.data.model.AppBehaviorSettings.MIN_PING_PARALLELISM,
                            org.olcbox.app.data.model.AppBehaviorSettings.MAX_PING_PARALLELISM,
                        )
                    )
                    todo.map { (index, entry) ->
                        val (line, profile) = entry
                        async {
                            sem.withPermit {
                                // Paused: wait here; servers already being pinged finish on their own.
                                freeGate.first { !it }
                                val config = LocationConfig(
                                    name = profile.displayName(),
                                    engine = EngineType.Standard,
                                    proxy = profile
                                ).normalized()
                                // pingVerified берёт режим пинга из настроек (TCP / ICMP / GET / HEAD) и в режимах без прокси
                                // ещё пропускает запрос через сервер: открытый порт у бесплатных ничего не значит, коннект
                                // потом не шёл. Таймаут вмещает обе проверки, иначе живые серверы обрезались бы.
                                val ping = withTimeoutOrNull(vpnManager.pingVerifiedBudgetMs) {
                                    try {
                                        vpnManager.pingVerified(config)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                val item = if (ping != null && ping > 0) {
                                    FreeServerItem(
                                        id = "free_${profile.server}_${profile.serverPort}_$index",
                                        line = line,
                                        profile = profile,
                                        displayName = profile.displayName(),
                                        pingMs = ping
                                    )
                                } else null

                                val snapshot = mutex.withLock {
                                    checkedCount++
                                    // A superseded run must not mark servers of a list it no longer owns.
                                    if (run == freeServersRun) {
                                        freeCheckedIdx.add(index)
                                        freeRunChecks++
                                    }
                                    if (item != null) {
                                        workingServers.add(item)
                                        if (run == freeServersRun) freeServersFound = workingServers.toList()
                                    }
                                    FreeServersProgress(checkedCount, total, workingServers.size, freeEta(total, checkedCount))
                                }
                                updateFreeServers(run) { it.copy(freeServersProgress = snapshot) }
                            }
                        }
                    }.awaitAll()
                }

                val sortedWorking = workingServers.sortedBy { it.pingMs }
                updateFreeServers(run) {
                    it.copy(
                        isFreeServersLoading = false,
                        freeServersBackground = false,
                        freeServersPaused = false,
                        freeServersProgress = null,
                        freeServersResumable = 0
                    )
                }

                if (sortedWorking.isEmpty()) {
                    onError("Не удалось найти работающие бесплатные серверы. Попробуйте позже.")
                } else if (freeRunInBackground) {
                    if (run == freeServersRun) {
                        // The cloud (server-side WARP) choice made when the list was first saved carries over.
                        val hadWarp = runCatching {
                            locationsRepository.getBundle().locations.any {
                                it.subscriptionUrl?.trim() == FREE_SERVERS_URL && it.metadata?.pinned != true && it.warpEnabled
                            }
                        }.getOrDefault(false)
                        saveSelectedFreeServers(
                            selectedServers = sortedWorking,
                            warpIds = if (hadWarp) sortedWorking.map { it.id }.toSet() else emptySet()
                        )
                    }
                } else {
                    updateFreeServers(run) { it.copy(availableFreeServers = sortedWorking) }
                }
            } catch (e: CancellationException) {
                updateFreeServers(run) { it.copy(isFreeServersLoading = false, freeServersBackground = false, freeServersProgress = null) }
            } catch (e: Exception) {
                updateFreeServers(run) { it.copy(isFreeServersLoading = false, freeServersBackground = false, freeServersProgress = null) }
                onError(e.message ?: "Ошибка загрузки бесплатных серверов")
            }
        }
    }

    /**
     * Turns the fetched lists into the servers to check, source by source in [FREE_SERVERS_SOURCES]
     * order: every protocol the importer understands and xray can probe (VLESS, VMess, Trojan, Shadowsocks —
     * VLESS alone threw away ~90% of the Freedom-V2Ray list), each server once (lists repeat the same
     * node under different names — ~10% of ebrasha — and overlap each other), and a source's limit
     * spent only on servers no earlier source already gave. Big lists are shuffled and parsed only
     * until the limit is reached, not all 8k lines. [limit] caps the whole result (0 = no cap): the
     * first [limit] servers in source order, so the sources of the app's language win.
     */
    private fun pickFreeServers(sourceTexts: List<Pair<String, Int>>, limit: Int): List<Pair<String, ProxyProfile>> {
        val seen = HashSet<ProxyProfile>()
        val picked = mutableListOf<Pair<String, ProxyProfile>>()
        for ((body, maxCount) in sourceTexts) {
            if (limit > 0 && picked.size >= limit) break
            // Some lists (Epodonios, chengaopan) are one base64 blob, not a line per link.
            val lines = org.olcbox.app.data.importer.SubscriptionDecoder.toLinkText(body).lines()
                .map { it.trim() }
                .filter { line -> FREE_SERVER_SCHEMES.any { line.startsWith(it, ignoreCase = true) } }
                .let { if (maxCount > 0) it.shuffled() else it }
            var taken = 0
            for (line in lines) {
                if (maxCount > 0 && taken >= maxCount) break
                if (limit > 0 && picked.size >= limit) break
                val profile = runCatching { ShareLinkParser.parse(line) }.getOrNull()
                    ?.takeIf { it.isComplete() } ?: continue
                if (!seen.add(profile.dedupNormalized())) continue
                picked += line to profile
                taken++
            }
        }
        return picked
    }

    fun saveSelectedFreeServers(
        selectedServers: List<FreeServerItem>,
        warpIds: Set<String> = emptySet(),
        onComplete: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _state.update { it.copy(availableFreeServers = null) }
        if (selectedServers.isEmpty()) return

        viewModelScope.launch {
            try {
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val previousSub = locationsRepository.getBundle().locations
                    .filter { it.subscriptionUrl?.trim() == FREE_SERVERS_URL }
                    .firstNotNullOfOrNull { it.metadata?.subscription }
                val newEntries = selectedServers.mapIndexed { index, item ->
                    val profile = item.profile
                    val location = LocationConfig(
                        name = item.displayName,
                        engine = EngineType.Standard,
                        proxy = profile,
                        // The same core that verified the server (pingVerified runs xray), not the sing-box default.
                        core = org.olcbox.app.data.model.ProxyCore.Xray,
                        // Cloud in the picker: WARP at the exit of this server.
                        warpEnabled = item.id in warpIds,
                        warpMode = org.olcbox.app.data.model.WarpMode.Server,
                        // Russian servers (RU flag): AmneziaWG, plain WireGuard is cut there; the rest: WireGuard.
                        warpProtocol = if (item.displayName.contains("\uD83C\uDDF7\uD83C\uDDFA")) {
                            org.olcbox.app.data.model.WarpProtocol.Awg
                        } else org.olcbox.app.data.model.WarpProtocol.Wg
                    ).normalized()
                    val base = "${profile.server}_${profile.serverPort}"
                        .lowercase()
                        .map { if (it.isLetterOrDigit()) it else '_' }
                        .joinToString("")
                    val storageId = "free_${base}_${now}_$index"
                    LocationEntry.from(
                        storageId = storageId,
                        location = location,
                        subscriptionUrl = FREE_SERVERS_URL,
                        metadata = LocationMetadata(
                            subscription = SubscriptionMetadata(
                                name = "Бесплатные серверы",
                                updateIntervalHours = previousSub?.updateIntervalHours ?: 0,
                                autoUpdateEnabled = previousSub?.autoUpdateEnabled ?: false,
                                lastRefreshAtEpochMs = now,
                                lastAttemptAtEpochMs = now
                            )
                        )
                    )
                }

                val currentBundle = locationsRepository.getBundle()
                // Убираем ТОЛЬКО старые бесплатные серверы, никогда не затрагивая остальные локации пользователя.
                // Закреплённые остаются, а такой же сервер из нового поиска не дублируется.
                val oldFree = currentBundle.locations.filter { it.subscriptionUrl?.trim() == FREE_SERVERS_URL }
                val keptPinned = oldFree.filter { it.metadata?.pinned == true }
                val pinnedProfiles = keptPinned.mapNotNull { it.proxy?.dedupNormalized() }.toSet()
                val freshEntries = newEntries.filterNot { it.proxy?.dedupNormalized() in pinnedProfiles }
                val nonFreeLocations = currentBundle.locations.filterNot {
                    it.subscriptionUrl?.trim() == FREE_SERVERS_URL
                }
                val updatedLocations = nonFreeLocations + keptPinned + freshEntries

                val droppedFreeIds = (oldFree - keptPinned.toSet()).map { it.storageId }.toSet()

                val updatedActiveId = if (currentBundle.activeLocationId in droppedFreeIds) {
                    freshEntries.firstOrNull()?.storageId ?: keptPinned.firstOrNull()?.storageId ?: currentBundle.activeLocationId
                } else {
                    currentBundle.activeLocationId
                }

                locationsRepository.saveBundle(
                    currentBundle.copy(
                        locations = updatedLocations,
                        activeLocationId = updatedActiveId
                    )
                )

                loadCurrentConfigNow()
                onComplete(freshEntries.size)
            } catch (e: Exception) {
                onError(e.message ?: "Ошибка сохранения серверов")
            }
        }
    }

    /**
     * "No valid config" is a lie when the link IS ours but arrived damaged. A `yptun://inbound`
     * payload is base64url, so it can contain runs of underscores, and every Markdown-rendering chat
     * eats them (`___` -> `_`). The lost characters cannot be recovered — the payload simply stops
     * inflating — while the link still looks perfectly intact to the eye, so the generic message
     * sends the owner hunting for a bug in the importer instead of asking for the link again.
     */
    private fun importFailureReason(rawText: String): String {
        val text = rawText.trim()
        val isSingleInboundLink = text.startsWith(YptunInboundCodec.PREFIX) &&
            text.indexOf(YptunInboundCodec.PREFIX, startIndex = 1) < 0
        if (text.startsWith("openflux://", ignoreCase = true)) {
            return "This OpenFlux link can't be imported: only Yandex / Mail.ru / cups.online / Avito / direct " +
                "transports are supported (a session needs a secret), or the link is damaged."
        }
        return if (isSingleInboundLink && YptunInboundCodec.parse(text) == null) {
            "This YPtun link is damaged — characters were lost in transit. " +
                "Scan the QR instead, or send the link as a file."
        } else {
            "No valid YPtun config found"
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
            // Initial delay so cold startup renders cached locations and subscriptions instantly without
            // locking the mutation mutex behind slow/blocked subscription network calls.
            delay(15_000L)
            backfillMissingSubscriptionExpiry()
            // Once per launch: retry overdue subscriptions even if they failed last time (keyed off the
            // last successful refresh). The periodic poll keeps the failure backoff to avoid hammering.
            refreshDueSubscriptionsIfNeeded(retryFailed = true)
            notifyExpiringSubscriptions()
            notifyPanelAnnouncements()
            reportProviderUsage()
            while (true) {
                delay(SUBSCRIPTION_AUTO_REFRESH_POLL_MS)
                refreshDueSubscriptionsIfNeeded()
                notifyExpiringSubscriptions()
                notifyPanelAnnouncements()
                reportProviderUsage()
            }
        }
    }

    /** Daily Happ-style provider-usage report for subscriptions carrying a `providerid` (best-effort). */
    private suspend fun reportProviderUsage() {
        runCatching {
            withContext(Dispatchers.IO) { locationsRepository.reportProviderUsage() }
        }
    }

    /**
     * Hand every subscription's expiry to the platform so it can warn the user before it runs out
     * (Happ-style, driven by the panel's expiry header). The platform applies the user's toggle, the
     * day threshold and de-duplication — here we only collect one (name → expiry) per subscription.
     */
    private suspend fun notifyExpiringSubscriptions() {
        val subs = runCatching {
            withContext(Dispatchers.IO) {
                locationsRepository.getBundle().locations
                    .filter { !it.subscriptionUrl.isNullOrBlank() }
                    .groupBy { it.subscriptionUrl!!.trim() }
                    .mapNotNull { (url, entries) ->
                        val meta = entries.firstNotNullOfOrNull { it.metadata?.subscription }
                        val expiresAt = meta?.expiresAtEpochMs ?: return@mapNotNull null
                        val name = meta.name?.takeIf { it.isNotBlank() }
                            ?: entries.firstOrNull { it.name.isNotBlank() }?.name
                            ?: url
                        ExpiringSubscriptionInfo(name = name, expiresAtEpochMs = expiresAt)
                    }
            }
        }.getOrDefault(emptyList())
        if (subs.isNotEmpty()) vpnManager.notifyExpiringSubscriptions(subs)
    }

    /**
     * Hand every subscription's panel announcement (Remnawave `announce` header) to the platform so it
     * can push it to the user. The platform applies the user's toggle and de-duplicates by content —
     * here we only collect one (name → announce) per subscription.
     */
    private suspend fun notifyPanelAnnouncements() {
        val announcements = runCatching {
            withContext(Dispatchers.IO) {
                locationsRepository.getBundle().locations
                    .filter { !it.subscriptionUrl.isNullOrBlank() }
                    .groupBy { it.subscriptionUrl!!.trim() }
                    .mapNotNull { (url, entries) ->
                        val meta = entries.firstNotNullOfOrNull { it.metadata?.subscription }
                        val announce = meta?.announce?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val name = meta.name?.takeIf { it.isNotBlank() }
                            ?: entries.firstOrNull { it.name.isNotBlank() }?.name
                            ?: url
                        PanelAnnouncementInfo(name = name, announce = announce)
                    }
            }
        }.getOrDefault(emptyList())
        if (announcements.isNotEmpty()) vpnManager.notifyPanelAnnouncements(announcements)
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
    /**
     * Why the last connect failed ([VpnStatus.Error]), for a one-off snackbar on Home. Separate from
     * [startBlockedReason], which holds standing states ("Add a location first") that must not pop up.
     */
    val connectError: String? = null,
    /** Wall-clock epoch-ms when the connection started (0 = not connected); drives the on-screen timer. */
    val connectedSinceEpochMs: Long = 0L,
    /** Set after importing a VK-TURN link that still needs a per-client VK Calls link. */
    val vkTurnLinkPrompt: VkTurnLinkPrompt? = null,
    val isFreeServersLoading: Boolean = false,
    /** A silent auto-update search of the free servers runs (no dialog; the strip near the toolbar shows it). */
    val freeServersBackground: Boolean = false,
    /** The free-servers search is held (servers in flight finish, no new one starts). */
    val freeServersPaused: Boolean = false,
    /** Servers a stopped search has not checked yet; > 0 offers «Продолжить поиск». */
    val freeServersResumable: Int = 0,
    val freeServersProgress: FreeServersProgress? = null,
    val availableFreeServers: List<FreeServerItem>? = null
)

data class FreeServersProgress(
    val checked: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    /** Estimated seconds to the end of the search; null while paused or before there are enough samples. */
    val etaSeconds: Int? = null
)

/** How often the free servers auto-update when the group's switch is on. */
const val FREE_AUTO_UPDATE_HOURS = 6

const val FREE_SERVERS_URL = "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt"

/**
 * Hidden GitHub Page with one file of vless links (≤ 20000), rebuilt hourly by a workflow. Used instead of
 * [FREE_SERVERS_SOURCES] when the «Пинг» setting is on; [FREE_SERVERS_URL] stays the group id either way.
 */
const val FREE_SERVERS_PAGES_URL = "https://breead1337.github.io/ws-cache-9e41/v.txt"

/**
 * Источник бесплатных серверов: [maxCount] — сколько уникальных серверов взять (0 = все, без
 * перемешивания), [region] — для какого языка приложения он в приоритете ("" = для всех).
 */
class FreeSource(val url: String, val maxCount: Int = 0, val region: String = "")

/**
 * Источники бесплатных серверов в порядке приоритета; [FREE_SERVERS_URL] — ещё и идентификатор
 * группы. Сколько всего взять, решает настройка «Лимит бесплатных серверов» (500 / 1000 / без
 * лимита): берутся первые N по этому порядку, а источники языка приложения ([freeSourcesFor])
 * идут вперёд. Проверка до 15 с на сервер — большие списки заметно удлиняют ожидание.
 *  - zieng2 — маленький (~60) список под российские белые списки, целиком;
 *  - igareck/vpn-configs-for-russia — обновляется каждые пару часов, конфиги заранее проверяет сервер
 *    в России (в т.ч. под белые списки мобильного интернета); три VLESS-файла целиком (~100 уникальных);
 *  - Freedom-V2Ray — агрегатор шести списков (~1600 серверов всех протоколов), раз в 2 часа
 *    выкидывает хосты, не принимающие TCP (проверка из США, не через прокси — сами всё равно проверяем);
 *  - ebrasha — огромный (~7 тыс. уникальных, только VLESS) и живой процентов на 7, только выборка;
 *  - Иран: barry-far, hamedcode, Epodonios — сборщики Telegram-каналов, по 6–8 тыс. VLESS, выборка
 *    (Epodonios отдаёт base64 — [SubscriptionDecoder.toLinkText] разворачивает);
 *  - Китай: peasoft/NoMoreWalls и ermaozi/get_subscribe (раздача под китайские сети), chengaopan
 *    (base64) — по 40–250 VLESS, берём целиком. Рабочие всё равно определяет пинг на устройстве.
 */
val FREE_SERVERS_SOURCES = listOf(
    FreeSource(FREE_SERVERS_URL, region = "ru"),
    FreeSource("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt", region = "ru"),
    FreeSource("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt", region = "ru"),
    FreeSource("https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-checked.txt", region = "ru"),
    FreeSource("https://raw.githubusercontent.com/MahanKenway/Freedom-V2Ray/main/configs/mix.txt", 250),
    FreeSource("https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/vless_configs.txt", 250),
    FreeSource("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt", 250, "fa"),
    FreeSource("https://raw.githubusercontent.com/hamedcode/port-based-v2ray-configs/main/sub/vless.txt", 250, "fa"),
    FreeSource("https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt", 250, "fa"),
    FreeSource("https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list_raw.txt", region = "zh"),
    FreeSource("https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt", region = "zh"),
    FreeSource("https://raw.githubusercontent.com/chengaopan/AutoMergePublicNodes/master/list.txt", region = "zh"),
)

/** [FREE_SERVERS_SOURCES] с источниками языка приложения впереди, затем общими, затем чужими; порядок внутри групп прежний. */
fun freeSourcesFor(language: String): List<FreeSource> =
    FREE_SERVERS_SOURCES.sortedBy { if (it.region == language) 0 else if (it.region.isEmpty()) 1 else 2 }


/**
 * Протоколы, которые берём из бесплатных списков: их разбирает [ShareLinkParser] и проверяет xray через
 * прокси. Hysteria2 сюда не входит: проверка через прокси (режим пинга по умолчанию) её не умеет, и
 * такой сервер всегда выглядел бы нерабочим.
 */
private val FREE_SERVER_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://")

/** Prompt to collect the per-client VK Calls link for a freshly imported VK-TURN location. */
data class VkTurnLinkPrompt(
    val storageId: String,
    val locationName: String
)

private const val SUBSCRIPTION_AUTO_REFRESH_POLL_MS = 60L * 60L * 1_000L

/** Auto-connect: how many candidates to try before giving up, and the per-attempt connect budget. */
private const val AUTO_CONNECT_MAX_ATTEMPTS = 6
private const val AUTO_CONNECT_ATTEMPT_TIMEOUT_MS = 15_000L
private const val STOP_SETTLE_TIMEOUT_MS = 6_000L
