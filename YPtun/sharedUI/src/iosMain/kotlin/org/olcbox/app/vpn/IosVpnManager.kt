package org.olcbox.app.vpn

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import org.olcbox.app.data.datasource.createProxyHttpClient

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.importer.FreeturnUriParser
import org.olcbox.app.data.importer.VkTurnComposer
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ios.IosCoreBridge
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.vpn.ios.IosSettingsController
import org.olcbox.app.vpn.ios.IosSharedStore
import org.olcbox.app.vpn.ios.IosTunnelRequest
import org.olcbox.app.vpn.ios.IosTunnelSession
import org.olcbox.app.vpn.xray.XrayConfig
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.timeIntervalSince1970
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.NetworkExtension.NETunnelProviderSession
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDidChangeNotification
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

/**
 * iOS VPN: a system VPN profile (NETunnelProviderManager) whose packet-tunnel extension runs the cores
 * (see IosTunnelSession). The app only writes the connect request to the App Group container, starts or
 * stops the profile, mirrors the system status, tails the extension's log and pings servers with the
 * cores linked into the app itself.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosVpnManager(
    private val locationsRepository: LocationsRepository,
    private val core: IosCoreBridge,
) : VpnManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedSince = MutableStateFlow(0L)
    override val connectedSinceEpochMs: StateFlow<Long> = _connectedSince.asStateFlow()

    // Kept for the settings sheet; on iOS the cores' SOCKS lives inside the extension and is not
    // exposed to other apps, so these only survive as preferences for now.
    private val _socksProxySettings = MutableStateFlow(ApplicationSocksProxySettings())
    val socksProxySettings: StateFlow<ApplicationSocksProxySettings> = _socksProxySettings.asStateFlow()

    /**
     * Set once by the app's dependency container, after both objects exist (the controller is not a
     * constructor argument so the tunnel-side manager stays usable without the UI layer). While it is
     * null the routing-profile hooks below fall back to reading the shared store directly.
     */
    var settingsController: IosSettingsController? = null

    /**
     * Happ-style routing profiles. iOS persisted them all along (IosSharedStore.loadRoutingProfiles)
     * but left the VpnManager defaults in place, so the per-location selector was always empty and
     * `happ://routing/add/...` links silently did nothing.
     */
    override fun routingProfileChoices(): List<RoutingProfile> =
        settingsController?.routingProfiles?.value?.profiles
            ?: IosSharedStore.loadRoutingProfiles().profiles

    override fun importRoutingProfileLink(link: String): Boolean =
        settingsController?.importRoutingProfileLink(link) ?: false

    private var manager: NETunnelProviderManager? = null
    private var statusObserver: Any? = null
    private var logJob: Job? = null
    private var captchaJob: Job? = null
    private var wasConnecting = false
    private var lastCaptchaUrl = ""
    private val vpnMutex = Mutex()
    private var connectJob: Job? = null
    private var connectWatchdogJob: Job? = null

    init {
        scope.launch {
            runCatching { loadManager(createIfMissing = false) }
                .onFailure { addLog("VPN profile load failed: ${it.message}") }
            // The widget / Control Center toggle starts the tunnel without the app, so the extension
            // must already find a request for the current location.
            locationsRepository.getActiveLocation()?.location?.normalized()
                ?.takeIf { it.isComplete() }
                ?.let { runCatching { publishRequest(it) } }
            startLogTail()
            locationsRepository.changes.collect {
                locationsRepository.getActiveLocation()?.location?.normalized()
                    ?.takeIf { it.isComplete() }
                    ?.let { runCatching { publishRequest(it) } }
            }
        }
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        connectJob?.cancel()
        connectJob = scope.launch {
            vpnMutex.withLock {
                val active = locationsRepository.getActiveLocation()?.location?.normalized()
                if (active == null || !active.isComplete()) {
                    setStatus(VpnStatus.Error("No active location"))
                    addLog("Add a valid location before connecting")
                    return@withLock
                }
                setStatus(VpnStatus.Connecting)
                wasConnecting = true
                val result = runCatching {
                    publishRequest(active)
                    IosSharedStore.writeText(IosTunnelSession.ERROR_FILE, "")
                    // Always reload manager from system preferences before connecting.
                    // iOS invalidates the cached NETunnelProviderManager when the app is backgrounded,
                    // causing NEVPNErrorDomain error 2 on the next connect attempt.
                    manager = null
                    val m = loadManager(createIfMissing = true) ?: error("VPN profile unavailable")
                    var connection = m.connection
                    // A running tunnel keeps its old location; restart it on the new request.
                    if (connection.status != platform.NetworkExtension.NEVPNStatusDisconnected &&
                        connection.status != platform.NetworkExtension.NEVPNStatusInvalid
                    ) {
                        connection.stopVPNTunnel()
                        awaitDisconnected(m)
                        connection = m.connection
                    }
                    var started = false
                    for (attempt in 0..2) {
                        memScoped {
                            val err = alloc<ObjCObjectVar<NSError?>>()
                            if (m.connection.startVPNTunnelAndReturnError(err.ptr)) {
                                started = true
                            } else {
                                if (attempt < 2) {
                                    delay(250L * (attempt + 1))
                                    runCatching {
                                        suspendCancellableCoroutine<Unit> { cont ->
                                            m.loadFromPreferencesWithCompletionHandler { cont.resume(Unit) }
                                        }
                                    }
                                } else {
                                    error(err.value?.localizedDescription ?: "VPN start failed")
                                }
                            }
                        }
                        if (started) break
                    }
                }
                result.onSuccess {
                    connectWatchdogJob?.cancel()
                    connectWatchdogJob = scope.launch {
                        delay(35_000)
                        if (_status.value == VpnStatus.Connecting) {
                            val failure = IosSharedStore.readText(IosTunnelSession.ERROR_FILE)?.trim().orEmpty()
                            val msg = failure.ifEmpty { "Таймаут подключения: сервер не отвечает" }
                            addLog("Watchdog: connection timed out after 35s ($msg)")
                            stopVpn()
                            setStatus(VpnStatus.Error(msg))
                        }
                    }
                }
                result.onFailure {
                    if (it is kotlinx.coroutines.CancellationException || it.message?.contains("cancelled", ignoreCase = true) == true) {
                        return@launch
                    }
                    val message = it.message ?: "VPN start failed"
                    addLog("VPN start failed: $message")
                    setStatus(VpnStatus.Error(message))
                    wasConnecting = false
                }
            }
        }
    }

    override fun stopVpn() {
        connectJob?.cancel()
        connectWatchdogJob?.cancel()
        scope.launch {
            vpnMutex.withLock {
                setStatus(VpnStatus.Stopping)
                val m = manager ?: loadManager(createIfMissing = false)
                if (m != null &&
                    m.connection.status != platform.NetworkExtension.NEVPNStatusDisconnected &&
                    m.connection.status != platform.NetworkExtension.NEVPNStatusInvalid
                ) {
                    m.connection.stopVPNTunnel()
                    awaitDisconnected(m.connection)
                }
                setStatus(VpnStatus.Disconnected)
                wasConnecting = false
            }
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().lowercase()
        return h.isEmpty() || h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "0.0.0.0"
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? = withContext(Dispatchers.Default) {
        val config = locationConfig.normalized()
        val behavior = IosSharedStore.loadAppBehavior()
        val profile = config.proxy
        val method = if (behavior.pingMode == AppBehaviorSettings.PING_PROXY_GET) "GET" else "HEAD"
        val isActiveLocation = locationsRepository.getActiveLocationId() == locationConfig.id
        val pingMs = when {
            config.engine == EngineType.Stealth -> rtcPing(config)
            config.engine == EngineType.VkTurn -> {
                if (status.value == VpnStatus.Connected && isActiveLocation) {
                    tunnelPing()
                } else {
                    null
                }
            }
            config.engine == EngineType.MasterDns -> {
                if (status.value == VpnStatus.Connected && isActiveLocation) {
                    tunnelPing()
                } else {
                    null
                }
            }
            config.engine == EngineType.OpenFlux -> {
                if (status.value == VpnStatus.Connected && isActiveLocation) {
                    tunnelPing()
                } else {
                    null
                }
            }
            profile == null -> null
            profile.type == ProxyProfile.TYPE_AMNEZIAWG -> {
                val awgConfig = profile.awgConfig.orEmpty()
                if (awgConfig.isBlank()) null
                else if (status.value == VpnStatus.Connected && isActiveLocation) {
                    tunnelPing() ?: run {
                        val url = behavior.effectivePingUrl()
                        val ms = core.awgMeasureDelay(awgConfig, url, method, PING_TIMEOUT_MS)
                        if (ms > 0) ms else {
                            val probeMs = core.awgProbe(awgConfig)
                            if (probeMs > 0) probeMs else null
                        }
                    }
                } else {
                    val url = behavior.effectivePingUrl()
                    val ms = core.awgMeasureDelay(awgConfig, url, method, PING_TIMEOUT_MS)
                    if (ms > 0) ms else {
                        val probeMs = core.awgProbe(awgConfig)
                        if (probeMs > 0) probeMs else null
                    }
                }
            }
            status.value == VpnStatus.Connected && isActiveLocation -> {
                tunnelPing() ?: if (isLoopbackHost(profile.server)) null else core.tcpPing(profile.server, profile.serverPort, PING_TIMEOUT_MS).takeIf { it > 0 }
            }
            behavior.pingMode == AppBehaviorSettings.PING_TCP -> {
                if (isLoopbackHost(profile.server)) null
                else {
                    val ms = core.tcpPing(profile.server, profile.serverPort, PING_TIMEOUT_MS)
                    if (ms > 0) ms else null
                }
            }
            else -> {
                if (isLoopbackHost(profile.server)) null
                else if (behavior.pingMode == AppBehaviorSettings.PING_TCP) {
                    core.tcpPing(profile.server, profile.serverPort, PING_TIMEOUT_MS).takeIf { it > 0 }
                } else {
                    proxyUrlTest(profile, behavior.effectivePingUrl(), method)?.takeIf { it > 0 }
                        ?: core.tcpPing(profile.server, profile.serverPort, PING_TIMEOUT_MS).takeIf { it > 0 }
                }
            }
        }
        if (pingMs != null && pingMs > 0 && isActiveLocation) {
            updateWidgetPing(pingMs)
        }
        pingMs
    }

    private suspend fun vkTurnProbePing(config: LocationConfig): Long? = withContext(Dispatchers.Default) {
        val vk = config.vkturn
        val draft = VkTurnComposer.decompose(config.vkturn, config.proxy)
        var host = draft.peerHost
        if (isLoopbackHost(host)) host = ""
        if (host.isBlank()) {
            val srv = config.proxy?.server.orEmpty()
            if (!isLoopbackHost(srv)) host = srv
        }
        if (host.isBlank()) {
            val peer = vk?.wdttPeer.orEmpty()
            if (!isLoopbackHost(peer)) host = peer
        }
        if (host.isBlank() && vk?.uri?.isNotBlank() == true) {
            host = FreeturnUriParser.parse(vk.uri)?.serverIp.orEmpty()
            if (isLoopbackHost(host)) host = ""
        }
        val port = draft.peerPort.toIntOrNull() ?: config.proxy?.serverPort ?: vk?.wdttPort?.takeIf { it > 0 } ?: 0

        val awgConf = config.proxy?.awgConfig.orEmpty()
        if (awgConf.isNotBlank()) {
            val probeMs = runCatching { core.awgProbe(awgConf) }.getOrDefault(-1L)
            if (probeMs > 0) return@withContext probeMs
        }

        if (host.isNotBlank() && !isLoopbackHost(host) && port > 0) {
            val ms = core.tcpPing(host, port, PING_TIMEOUT_MS)
            if (ms > 0) return@withContext ms
        }
        if (host.isNotBlank() && !isLoopbackHost(host)) {
            val ms443 = core.tcpPing(host, 443, PING_TIMEOUT_MS)
            if (ms443 > 0) return@withContext ms443
            val ms80 = core.tcpPing(host, 80, PING_TIMEOUT_MS)
            if (ms80 > 0) return@withContext ms80
        }
        null
    }


    private val pingClient: HttpClient by lazy {
        createProxyHttpClient(
            connectTimeoutMs = PING_TIMEOUT_MS.toLong(),
            requestTimeoutMs = PING_TIMEOUT_MS.toLong(),
            socketTimeoutMs = PING_TIMEOUT_MS.toLong(),
        )
    }

    private suspend fun tunnelPing(): Long? = withContext(Dispatchers.Default) {
        if (status.value != VpnStatus.Connected) return@withContext null
        val behavior = IosSharedStore.loadAppBehavior()
        val urlString = behavior.effectivePingUrl()
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val ok = runCatching {
            val response: HttpResponse = if (behavior.pingMode == AppBehaviorSettings.PING_PROXY_GET) {
                pingClient.get(urlString)
            } else {
                pingClient.head(urlString)
            }
            response.status.value in 200..399 || response.status.value == 204
        }.getOrDefault(false)

        if (ok) {
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds
            maxOf(1L, elapsedMs)
        } else {
            null
        }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = ping(locationConfig)

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        _socksProxySettings.value = ApplicationSocksProxySettings(
            port = if (ApplicationSocksProxySettings.isValidPort(port)) port else ApplicationSocksProxySettings.DEFAULT_PORT,
            username = username.trim(),
            password = password.trim(),
        )
    }

    fun regenerateSocksProxyPassword() = Unit

    fun close() {
        statusObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        connectJob?.cancel()
        scope.cancel()
    }

    /**
     * Per-server URL test (à la v2rayNG / Happ) through a throwaway xray instance in this process —
     * works while disconnected. A verbatim config is probed through its own proxy outbound.
     */
    private fun proxyUrlTest(profile: ProxyProfile, url: String, method: String): Long? {
        if (profile.server.isBlank() || profile.type == ProxyProfile.TYPE_HYSTERIA2) return null
        val listenPort = (20_000..60_000).random()
        val raw = profile.rawXrayConfig
        val configJson = if (!raw.isNullOrBlank()) {
            XrayConfig.buildRawProxyPingConfig(rawConfigJson = raw, listenPort = listenPort, listenHost = "127.0.0.1")
        } else {
            runCatching {
                XrayConfig.build(
                    profile = profile,
                    listenPort = listenPort,
                    listenHost = "127.0.0.1",
                    logLevel = "none",
                    blockQuic = false,
                )
            }.getOrNull()
        } ?: return null
        return core.xrayMeasureDelay(configJson, url, method, PING_TIMEOUT_MS).takeIf { it >= 0 }
    }

    /**
     * The connect request the extension reads (location + device id) and the name the widget shows —
     * both in the App Group container.
     */
    private suspend fun publishRequest(location: LocationConfig) {
        val deviceId = locationsRepository.getDeviceIdentity()
        val request = IosTunnelRequest(location, deviceId)
        IosSharedStore.writeText(
            IosTunnelSession.REQUEST_FILE,
            IosTunnelSession.json.encodeToString(IosTunnelRequest.serializer(), request)
        )
        val routing = IosSharedStore.loadRouting()
        val bundle = runCatching { locationsRepository.getBundle() }.getOrNull()
        val items = bundle?.locations?.mapNotNull { entry ->
            val loc = entry.location.normalized()
            if (!loc.isComplete()) return@mapNotNull null
            val req = IosTunnelRequest(loc, deviceId)
            val reqJson = IosTunnelSession.json.encodeToString(IosTunnelRequest.serializer(), req)
            buildJsonObject {
                put("id", JsonPrimitive(entry.storageId))
                put("name", JsonPrimitive(loc.displayName()))
                put("requestJson", JsonPrimitive(reqJson))
            }
        } ?: emptyList()

        val existingPing = runCatching {
            IosSharedStore.readText(WIDGET_FILE)?.let {
                val element = Json.parseToJsonElement(it).jsonObject["ping"] as? JsonPrimitive
                element?.content?.toLongOrNull()
            }
        }.getOrNull() ?: -1L

        IosSharedStore.writeText(
            WIDGET_FILE,
            buildJsonObject {
                put("id", JsonPrimitive(location.id))
                put("name", JsonPrimitive(location.displayName()))
                put("ping", JsonPrimitive(existingPing))
                put("bypassRussia", JsonPrimitive(routing.bypassRussia))
                put("locations", JsonArray(items))
            }.toString()
        )
    }

    private fun updateWidgetPing(pingMs: Long) {
        val current = IosSharedStore.readText(WIDGET_FILE) ?: return
        val root = runCatching { Json.parseToJsonElement(current).jsonObject }.getOrNull() ?: return
        val updated = buildJsonObject {
            root.forEach { (k, v) ->
                if (k == "ping") put(k, JsonPrimitive(pingMs))
                else put(k, v)
            }
            if (!root.containsKey("ping")) put("ping", JsonPrimitive(pingMs))
        }
        IosSharedStore.writeText(WIDGET_FILE, updated.toString())
    }

    private suspend fun rtcPing(config: LocationConfig): Long? =
        core.rtcPing(
            carrier = config.bypassProvider,
            transport = config.transport,
            roomId = config.id,
            clientId = locationsRepository.getDeviceIdentity(),
            keyHex = config.key,
            socksPort = 0,
            timeoutMs = PING_TIMEOUT_MS,
            pingUrl = HTTP_PING_URL,
            vp8Fps = config.vp8Fps,
            vp8Batch = config.vp8Batch,
        ).takeIf { it >= 0 }

    // ---------------------------------------------------------------------------------------
    // VPN profile

    private suspend fun loadManager(createIfMissing: Boolean): NETunnelProviderManager? {
        manager?.let { return it }
        val existing = suspendCancellableCoroutine<NETunnelProviderManager?> { cont ->
            NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
                cont.resume(managers?.filterIsInstance<NETunnelProviderManager>()?.firstOrNull())
            }
        }
        val m = existing ?: if (createIfMissing) NETunnelProviderManager() else return null
        var needsSave = false
        if (m.onDemandEnabled || (m.onDemandRules?.isNotEmpty() == true)) {
            m.setOnDemandEnabled(false)
            m.setOnDemandRules(emptyList<Any?>())
            needsSave = true
        }
        if (existing == null || !m.enabled) {
            val tunnelId = platform.Foundation.NSBundle.mainBundle.bundleIdentifier
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it.tunnel" }
                ?: TUNNEL_BUNDLE_ID
            m.setProtocolConfiguration(NETunnelProviderProtocol().apply {
                setProviderBundleIdentifier(tunnelId)
                setServerAddress("YPtun")
            })
            m.setLocalizedDescription("YPtun")
            m.setEnabled(true)
            // The first save shows iOS's "Allow VPN configuration" prompt.
            val saveError = suspendCancellableCoroutine<String?> { cont ->
                m.saveToPreferencesWithCompletionHandler { err -> cont.resume(err?.localizedDescription) }
            }
            if (saveError != null) error("VPN profile not saved: $saveError")
            // A freshly saved manager must be reloaded before its connection can start.
            suspendCancellableCoroutine<Unit> { cont ->
                m.loadFromPreferencesWithCompletionHandler { cont.resume(Unit) }
            }
        } else if (needsSave) {
            suspendCancellableCoroutine<Unit> { cont ->
                m.saveToPreferencesWithCompletionHandler { cont.resume(Unit) }
            }
        }
        manager = m
        observeStatus(m)
        return m
    }

    private fun observeStatus(m: NETunnelProviderManager) {
        statusObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        statusObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NEVPNStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notif ->
            val conn = notif?.`object` as? platform.NetworkExtension.NEVPNConnection ?: manager?.connection
            onSystemStatus(conn)
        }
        onSystemStatus(m.connection)
    }

    private fun onSystemStatus(connection: platform.NetworkExtension.NEVPNConnection?) {
        val conn = connection ?: manager?.connection ?: return
        val s: NEVPNStatus = conn.status
        when (s) {
            NEVPNStatusConnecting -> {
                wasConnecting = true
                setStatus(VpnStatus.Connecting)
                manager?.let { watchCaptcha(it) }
            }
            NEVPNStatusConnected -> {
                connectWatchdogJob?.cancel()
                wasConnecting = false
                _connectedSince.value = conn.connectedDate
                    ?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L
                setStatus(VpnStatus.Connected)
                // Now that traffic goes through the tunnel, retry the geo databases if they are still
                // missing: without them a verbatim Xray config loses every geosite:/geoip: rule, and the
                // one moment they are needed (the connect path, inside the extension) is the one moment
                // the device has no working route to fetch them. No-op once they are on disk.
                settingsController?.ensureGeoAssets()
                scope.launch {
                    delay(1500)
                    val ms = tunnelPing()
                    if (ms != null && ms > 0) {
                        updateWidgetPing(ms)
                    }
                }
            }
            NEVPNStatusReasserting -> setStatus(VpnStatus.Reconnecting)
            NEVPNStatusDisconnecting -> setStatus(VpnStatus.Stopping)
            else -> {
                connectWatchdogJob?.cancel()
                _connectedSince.value = 0L
                val failure = IosSharedStore.readText(IosTunnelSession.ERROR_FILE)?.trim().orEmpty()
                if (wasConnecting && failure.isNotEmpty()) {
                    setStatus(VpnStatus.Error(failure))
                } else {
                    setStatus(VpnStatus.Disconnected)
                }
                wasConnecting = false
            }
        }
    }

    private suspend fun awaitDisconnected(m: NETunnelProviderManager) {
        repeat(20) {
            val s = m.connection.status
            if (s == platform.NetworkExtension.NEVPNStatusDisconnected || s == platform.NetworkExtension.NEVPNStatusInvalid) return
            delay(100)
            runCatching {
                suspendCancellableCoroutine<Unit> { cont ->
                    m.loadFromPreferencesWithCompletionHandler { cont.resume(Unit) }
                }
            }
        }
    }

    /**
     * While connecting, ask the extension whether VK wants a captcha; its page is served by the core
     * on localhost, so it opens in Safari. Stops once the tunnel is up or gone.
     */
    private fun watchCaptcha(m: NETunnelProviderManager) {
        if (captchaJob?.isActive == true) return
        captchaJob = scope.launch {
            val session = m.connection as? NETunnelProviderSession ?: return@launch
            while (isActive && m.connection.status == NEVPNStatusConnecting) {
                val url = askExtension(session, "status")
                if (url.isNotBlank() && url != lastCaptchaUrl) {
                    lastCaptchaUrl = url
                    addLog("VK просит капчу — открываю её")
                    NSURL.URLWithString(url)?.let {
                        UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
                    }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun askExtension(session: NETunnelProviderSession, message: String): String {
        val data = NSString.create(string = message).dataUsingEncoding(NSUTF8StringEncoding) ?: return ""
        return suspendCancellableCoroutine { cont ->
            val sent = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                session.sendProviderMessage(data, err.ptr) { response ->
                    val text = response?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }.orEmpty()
                    if (cont.isActive) cont.resume(text)
                }
            }
            if (!sent && cont.isActive) cont.resume("")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Log: the extension appends to tunnel.log in the App Group container.

    private fun startLogTail() {
        logJob?.cancel()
        logJob = scope.launch {
            var last = ""
            while (isActive) {
                // Tail only: the extension keeps the log across sessions now, so re-reading the whole
                // file once a second would cost more the longer the tunnel had been up.
                val text = withContext(Dispatchers.Default) {
                    IosSharedStore.readTextTail(IosTunnelSession.LOG_FILE, LOG_TAIL_BYTES)
                }.orEmpty()
                if (text != last) {
                    last = text
                    _logs.value = text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(MAX_LOG_LINES)
                }
                delay(1_000)
            }
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.value = (_logs.value + message).takeLast(MAX_LOG_LINES)
    }

    private companion object {
        const val TUNNEL_BUNDLE_ID = "org.yptun.app.tunnel"
        /** Read by the WidgetKit extension (YPtunWidget/VpnWidget.swift). */
        const val WIDGET_FILE = "widget.json"
        const val MAX_LOG_LINES = 2000
        /** Enough for [MAX_LOG_LINES] of tunnel log; the rest of the file is history we don't show. */
        const val LOG_TAIL_BYTES = 512L * 1024
        const val PING_TIMEOUT_MS = 8_000
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
    }
}
