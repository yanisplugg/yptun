package org.olcbox.app.vpn

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.importer.TrustTunnelParser

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
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
    private var connectGeneration = 0L
    private var inFlightLocationId: String? = null
    private var connectedLocationId: String? = null
    private var lastStartEpochMs: Long = 0L
    private var lastKnownActiveLocation: LocationConfig? = null

    init {
        scope.launch {
            runCatching { loadManager(createIfMissing = false) }
                .onFailure { addLog("VPN profile load failed: ${it.message}") }
            // The widget / Control Center toggle starts the tunnel without the app, so the extension
            // must already find a request for the current location.
            val initial = locationsRepository.getActiveLocation()?.location?.normalized()
            lastKnownActiveLocation = initial
            initial?.takeIf { it.isComplete() }
                ?.let { runCatching { publishRequest(it) } }
            startLogTail()
            locationsRepository.changes.collect {
                val updated = locationsRepository.getActiveLocation()?.location?.normalized()
                lastKnownActiveLocation = updated
                updated?.takeIf { it.isComplete() }
                    ?.let { runCatching { publishRequest(it) } }
            }
        }
    }

    private fun triggerImpactHaptic(style: UIImpactFeedbackStyle) {
        runCatching {
            val generator = UIImpactFeedbackGenerator(style)
            generator.prepare()
            generator.impactOccurred()
        }
    }

    private fun triggerNotificationHaptic(type: UINotificationFeedbackType) {
        runCatching {
            val generator = UINotificationFeedbackGenerator()
            generator.prepare()
            generator.notificationOccurred(type)
        }
    }

    override fun needsPermission(): Boolean = false

    /**
     * Subscription-expiry warnings on Android's rules: the user's toggle, the last
     * [AppBehaviorSettings.SUBSCRIPTION_EXPIRY_NOTIFY_DAYS] days, once per subscription per day left.
     * An iOS app process lives minutes, not days, so "already shown" is kept on disk instead of in
     * memory. Swift posts the notification (LocalNotifications.swift).
     */
    override fun notifyExpiringSubscriptions(subscriptions: List<ExpiringSubscriptionInfo>) {
        if (!IosSharedStore.loadAppBehavior().notifySubscriptionExpiry) {
            NSNotificationCenter.defaultCenter.postNotificationName("org.yptun.notify.clearExpiry", null)
            return
        }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val dayMs = 24L * 60L * 60L * 1_000L
        val strings = org.olcbox.app.ui.i18n.stringsFor(org.olcbox.app.ui.i18n.LocalizationState.effective)
        subscriptions.forEach { sub ->
            val remainingMs = sub.expiresAtEpochMs - now
            if (remainingMs <= 0L) return@forEach
            val until = org.olcbox.app.util.IsoTime.formatLocalDateTime(sub.expiresAtEpochMs)

            // 1. Immediate notification if already within the last N days (deduplicated per day left).
            if (remainingMs <= AppBehaviorSettings.SUBSCRIPTION_EXPIRY_NOTIFY_DAYS * dayMs) {
                val daysLeft = (remainingMs + dayMs - 1) / dayMs // ceil → 3, 2, 1
                if (markNotified("e|${sub.name.hashCode()}|$daysLeft")) {
                    postLocalNotification(
                        id = "yptun.expiry.${sub.name.hashCode()}.now.$daysLeft",
                        title = strings.subscriptionExpiringSoon,
                        body = "«${sub.name}»: ${strings.subscriptionExpiryFull(until, daysLeft)}",
                    )
                }
            }

            // 2. Schedule future UNCalendarNotificationTrigger alerts at -3d / -2d / -1d so iOS delivers
            // them even when the app stays completely closed.
            for (daysBefore in AppBehaviorSettings.SUBSCRIPTION_EXPIRY_NOTIFY_DAYS downTo 1) {
                val fireAtMs = sub.expiresAtEpochMs - daysBefore * dayMs
                if (fireAtMs > now + 60_000L) {
                    scheduleLocalNotification(
                        id = "yptun.expiry.${sub.name.hashCode()}.d$daysBefore",
                        title = strings.subscriptionExpiringSoon,
                        body = "«${sub.name}»: ${strings.subscriptionExpiryFull(until, daysBefore.toLong())}",
                        fireAtEpochSec = fireAtMs / 1_000L,
                    )
                }
            }
        }
    }

    /** Panel announcements (Remnawave `announce`), each text shown once — Android's rules. */
    override fun notifyPanelAnnouncements(announcements: List<PanelAnnouncementInfo>) {
        if (!IosSharedStore.loadAppBehavior().notifyPanelAnnouncements) return
        announcements.forEach { ann ->
            val hash = (ann.name + "|" + ann.announce).hashCode()
            if (!markNotified("a|$hash")) return@forEach
            postLocalNotification(id = "yptun.announce.$hash", title = ann.name, body = ann.announce)
        }
    }

    /** Records [key] as shown; false when it already was. Bounded, so the file never grows for good. */
    private fun markNotified(key: String): Boolean {
        val shown = IosSharedStore.readText(NOTIFIED_FILE)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (key in shown) return false
        IosSharedStore.writeText(NOTIFIED_FILE, (shown + key).takeLast(MAX_NOTIFIED_KEYS).joinToString("\n"))
        return true
    }

    /** Same channel as the Live Activity signals: a named NSNotification Swift listens for. */
    private fun postLocalNotification(id: String, title: String, body: String) {
        NSNotificationCenter.defaultCenter.postNotificationName("org.yptun.notify.post", listOf(id, title, body))
    }

    private fun scheduleLocalNotification(id: String, title: String, body: String, fireAtEpochSec: Long) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            "org.yptun.notify.schedule",
            listOf(id, title, body, fireAtEpochSec.toString())
        )
    }

    override fun startVpn() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastStartEpochMs < 400L) {
            return
        }
        lastStartEpochMs = now

        val targetId = lastKnownActiveLocation?.let { it.id.ifBlank { it.displayName() } }
        if (connectJob?.isActive == true && inFlightLocationId != null && inFlightLocationId == targetId) {
            return
        }

        val currentManager = manager
        val isActuallyConnected = currentManager?.connection?.status == NEVPNStatusConnected
        if (isActuallyConnected && connectedLocationId != null && connectedLocationId == targetId && _status.value == VpnStatus.Connected) {
            return
        }

        inFlightLocationId = targetId
        triggerImpactHaptic(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
        val requestedGeneration = ++connectGeneration
        connectJob?.cancel()
        connectWatchdogJob?.cancel()
        connectJob = scope.launch {
            vpnMutex.withLock {
                if (requestedGeneration != connectGeneration) return@withLock

                val active = locationsRepository.getActiveLocation()?.location?.normalized()
                lastKnownActiveLocation = active
                if (active == null || !active.isComplete()) {
                    setStatus(VpnStatus.Error("No active location"))
                    addLog("Add a valid location before connecting")
                    return@withLock
                }

                val currentTargetId = active.id.ifBlank { active.displayName() }
                val mgr = manager ?: loadManager(createIfMissing = false)
                val isConn = mgr?.connection?.status == NEVPNStatusConnected

                // Redundant start: already connected to this exact location
                if (isConn && connectedLocationId == currentTargetId && _status.value == VpnStatus.Connected) {
                    return@withLock
                }

                inFlightLocationId = currentTargetId

                // If tunnel is already running, attempt hot reload without tearing down the iOS VPN tunnel
                val currentSession = mgr?.connection as? NETunnelProviderSession
                if (currentSession != null && isConn) {
                    setStatus(VpnStatus.Reconnecting)
                    publishRequest(active)
                    IosSharedStore.writeText(IosTunnelSession.ERROR_FILE, "")
                    addLog("Fast switching to ${active.displayName()}...")
                    val reply = withTimeoutOrNull(2_500L) {
                        askExtension(currentSession, "reload")
                    }
                    if (requestedGeneration != connectGeneration) return@withLock
                    if (reply == "ok") {
                        addLog("Connected to ${active.displayName()}")
                        setStatus(VpnStatus.Connected)
                        connectedLocationId = targetId
                        inFlightLocationId = null
                        triggerNotificationHaptic(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                        return@withLock
                    } else {
                        val errMsg = reply?.removePrefix("error:") ?: "timeout"
                        addLog("Fast switch unavailable ($errMsg), restarting tunnel...")
                    }
                }

                if (requestedGeneration != connectGeneration) return@withLock
                setStatus(VpnStatus.Connecting)
                wasConnecting = true
                addLog("Connecting to ${active.displayName()}...")
                val result = runCatching {
                    publishRequest(active)
                    IosSharedStore.writeText(IosTunnelSession.ERROR_FILE, "")
                    // Always reload manager from system preferences before connecting.
                    // iOS invalidates the cached NETunnelProviderManager when the app is backgrounded,
                    // causing NEVPNErrorDomain error 2 on the next connect attempt.
                    manager = null
                    var m = loadManager(createIfMissing = true) ?: error("VPN profile unavailable")
                    var connection = m.connection
                    // A running tunnel keeps its old location; restart it on the new request.
                    if (connection.status != platform.NetworkExtension.NEVPNStatusDisconnected &&
                        connection.status != platform.NetworkExtension.NEVPNStatusInvalid
                    ) {
                        connection.stopVPNTunnel()
                        awaitDisconnected(m)
                        // Give PacketTunnelProvider's post-stopTunnel exit(0) (150ms) time to finish
                        // so iOS neagent spawns a clean extension process instead of racing teardown.
                        delay(350)
                        // Freshly reload manager after stopping to avoid NEVPNErrorDomain error 2
                        manager = null
                        m = loadManager(createIfMissing = true) ?: m
                    }
                    if (requestedGeneration != connectGeneration) return@withLock
                    var started = false
                    for (attempt in 0..3) {
                        if (requestedGeneration != connectGeneration) return@withLock
                        var waitCount = 0
                        while (m.connection.status == platform.NetworkExtension.NEVPNStatusDisconnecting && waitCount < 25) {
                            delay(200)
                            waitCount++
                        }
                        memScoped {
                            val err = alloc<ObjCObjectVar<NSError?>>()
                            if (m.connection.startVPNTunnelAndReturnError(err.ptr)) {
                                started = true
                            } else {
                                if (attempt < 3) {
                                    delay(400L * (attempt + 1))
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
                if (requestedGeneration != connectGeneration) return@withLock
                result.onSuccess {
                    connectWatchdogJob?.cancel()
                    val expectedGeneration = requestedGeneration
                    connectWatchdogJob = scope.launch {
                        delay(35_000)
                        if (expectedGeneration == connectGeneration && _status.value == VpnStatus.Connecting) {
                            val failure = IosSharedStore.readText(IosTunnelSession.ERROR_FILE)?.trim().orEmpty()
                            val msg = failure.ifEmpty { "Таймаут подключения: сервер не отвечает" }
                            addLog("Watchdog: connection timed out after 35s ($msg)")
                            stopVpn()
                            setStatus(VpnStatus.Error(msg))
                        }
                    }
                }
                result.onFailure {
                    if (requestedGeneration == connectGeneration) {
                        inFlightLocationId = null
                    }
                    if (requestedGeneration != connectGeneration) return@withLock
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
        triggerImpactHaptic(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
        inFlightLocationId = null
        connectedLocationId = null
        connectGeneration++
        connectJob?.cancel()
        connectWatchdogJob?.cancel()
        scope.launch {
            vpnMutex.withLock {
                setStatus(VpnStatus.Stopping)
                addLog("Disconnecting tunnel...")
                val m = manager ?: loadManager(createIfMissing = false)
                if (m != null &&
                    m.connection.status != platform.NetworkExtension.NEVPNStatusDisconnected &&
                    m.connection.status != platform.NetworkExtension.NEVPNStatusInvalid
                ) {
                    m.connection.stopVPNTunnel()
                    awaitDisconnected(m)
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

    override val pingTimeoutMs: Long
        get() = IosSharedStore.loadAppBehavior().effectivePingTimeoutMs().toLong()
    override val pingRounds: Int
        get() = IosSharedStore.loadAppBehavior().effectivePingPrecision()
    override val freeServersFromPages: Boolean
        get() = IosSharedStore.loadAppBehavior().freeServersFromPages

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        var best: Long? = null
        repeat(pingRounds) {
            val ms = pingOnce(locationConfig)
            if (ms == null) return best
            if (best == null || ms < best!!) best = ms
        }
        return best
    }

    private suspend fun isSameAsActiveLocation(config: LocationConfig): Boolean {
        val activeEntry = locationsRepository.getActiveLocation() ?: return false
        val active = activeEntry.location.normalized()
        if (config.id.isNotBlank() && active.id.isNotBlank()) {
            return config.id == active.id
        }
        if (config.engine != active.engine) return false
        if (config.displayName() == active.displayName()) {
            val p1 = config.proxy
            val p2 = active.proxy
            if (p1 == null && p2 == null) return true
            if (p1 != null && p2 != null) {
                return p1.type == p2.type && p1.server == p2.server && p1.serverPort == p2.serverPort &&
                    p1.ttConfig == p2.ttConfig
            }
        }
        return false
    }

    private val xrayPingSemaphore = Semaphore(4)
    private val pingPortMutex = Mutex()
    private var pingPortCursor = 25_000

    private suspend fun nextPingListenPort(): Int = pingPortMutex.withLock {
        val port = pingPortCursor
        pingPortCursor = if (pingPortCursor >= 55_000) 25_000 else pingPortCursor + 1
        port
    }

    /**
     * Resolves a hostname via PacketTunnelProvider when VPN is connected (bypassing FakeDNS 100.64.0.x),
     * or via native POSIX getaddrinfo when disconnected (avoiding Go's /etc/resolv.conf 5s timeout on iOS).
     */
    private suspend fun resolveServerIpv4ForPing(host: String, connected: Boolean): String? {
        val trimmed = host.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty() || isLoopbackHost(trimmed)) return null
        if (isIpv4Literal(trimmed)) return trimmed
        if (connected) {
            val currentManager = manager ?: loadManager(createIfMissing = false)
            val session = currentManager?.connection as? NETunnelProviderSession
            if (session != null && currentManager.connection.status == NEVPNStatusConnected) {
                val ip = withTimeoutOrNull(2_500L) {
                    askExtension(session, "resolve:$trimmed")
                }?.trim().orEmpty()
                if (isIpv4Literal(ip)) return ip
            }
        }
        val localIps = core.resolveHostIpv4(trimmed)
        return localIps.split(',').map { it.trim() }.firstOrNull { isIpv4Literal(it) && !isFakeDnsIp(it) }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun isFakeDnsIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return (a == 100 && b in 64..127) || (a == 198 && b in 18..19)
    }

    /**
     * Direct TCP ping executed inside PacketTunnelProvider when VPN is connected, so iOS bypasses
     * utun and FakeDNS (100.64.0.x) and measures real direct server latency with zero double-hop.
     */
    private suspend fun directTcpPingViaExtension(host: String, port: Int, timeoutMs: Int): Long? {
        if (host.isBlank() || isLoopbackHost(host) || port !in 1..65535) return null
        val currentManager = manager ?: loadManager(createIfMissing = false)
        val session = currentManager?.connection as? NETunnelProviderSession ?: return null
        if (currentManager.connection.status != NEVPNStatusConnected) return null
        val reply = withTimeoutOrNull((timeoutMs + 1_000).toLong()) {
            askExtension(session, "tcpping:$host|$port|$timeoutMs")
        }
        return reply?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * Real end-to-end proxy URL test executed inside PacketTunnelProvider when VPN is connected,
     * so the probe bypasses utun and measures true server->exit->target latency (e.g. Brazil vs Poland
     * even when both enter via the same RU relay!). Returns -2 if extension memory is too tight.
     */
    private suspend fun directXrayPingViaExtension(
        configJson: String,
        url: String,
        method: String,
        timeoutMs: Int
    ): Long? {
        val currentManager = manager ?: loadManager(createIfMissing = false)
        val session = currentManager?.connection as? NETunnelProviderSession ?: return null
        if (currentManager.connection.status != NEVPNStatusConnected) return null
        val payload = "xrayping:$timeoutMs|$method|$url|$configJson"
        val reply = withTimeoutOrNull((timeoutMs + 2_000).toLong()) {
            askExtension(session, payload)
        }
        return reply?.trim()?.toLongOrNull()
    }

    private suspend fun pingOnce(locationConfig: LocationConfig): Long? = withContext(Dispatchers.IO) {
        val config = locationConfig.normalized()
        val behavior = IosSharedStore.loadAppBehavior()
        val timeoutMs = pingTimeoutMs.toInt()
        val profile = config.proxy
        val method = if (behavior.pingMode == AppBehaviorSettings.PING_PROXY_GET) "GET" else "HEAD"
        val connected = status.value == VpnStatus.Connected
        val isActiveLocation = connected && isSameAsActiveLocation(config)
        val pingMs = when {
            config.engine == EngineType.Stealth -> {
                if (isActiveLocation) tunnelPing() else rtcPing(config)
            }
            config.engine == EngineType.VkTurn ||
                config.engine == EngineType.MasterDns ||
                config.engine == EngineType.OpenFlux ||
                config.engine == EngineType.Snolc -> {
                if (isActiveLocation) tunnelPing() else null
            }
            profile == null -> null
            isActiveLocation -> {
                tunnelPing()
            }
            profile.type == ProxyProfile.TYPE_TRUSTTUNNEL -> {
                val ep = TrustTunnelParser.endpoints(profile.ttConfig).firstOrNull()
                if (ep == null) null
                else if (connected) {
                    directTcpPingViaExtension(ep.first, ep.second, timeoutMs)
                } else {
                    core.tcpPing(ep.first, ep.second, timeoutMs).takeIf { it > 0 }
                }
            }
            profile.type == ProxyProfile.TYPE_AMNEZIAWG -> {
                val awgConfig = profile.awgConfig.orEmpty()
                if (awgConfig.isBlank()) null
                else {
                    val url = behavior.effectivePingUrl()
                    val ms = core.awgMeasureDelay(awgConfig, url, method, timeoutMs)
                    if (ms > 0) ms else {
                        val probeMs = core.awgProbe(awgConfig)
                        if (probeMs > 0) probeMs else null
                    }
                }
            }
            profile.type == ProxyProfile.TYPE_HYSTERIA2 -> {
                hysteria2ProbePing(profile, timeoutMs, connected)
            }
            behavior.pingMode == AppBehaviorSettings.PING_TCP ||
                profile.type == ProxyProfile.TYPE_MIERU -> {
                if (isLoopbackHost(profile.server)) null
                else {
                    val port = profile.serverPort.takeIf { it in 1..65535 } ?: 443
                    if (connected) {
                        directTcpPingViaExtension(profile.server, port, timeoutMs)
                    } else {
                        core.tcpPing(profile.server, port, timeoutMs).takeIf { it > 0 }
                    }
                }
            }
            behavior.pingMode == AppBehaviorSettings.PING_ICMP -> {
                // ICMP raw sockets are unavailable inside sandboxed iOS apps; fall back to direct TCP ping.
                if (isLoopbackHost(profile.server)) null
                else {
                    val port = profile.serverPort.takeIf { it in 1..65535 } ?: 443
                    if (connected) {
                        directTcpPingViaExtension(profile.server, port, timeoutMs)
                    } else {
                        core.tcpPing(profile.server, port, timeoutMs).takeIf { it > 0 }
                    }
                }
            }
            else -> {
                if (isLoopbackHost(profile.server)) null
                else {
                    proxyUrlTest(
                        profile = profile,
                        url = behavior.effectivePingUrl(),
                        method = method,
                        timeoutMs = timeoutMs,
                        connected = connected,
                    )?.takeIf { it > 0 }
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

    private suspend fun directUdpPingViaExtension(host: String, port: Int, timeoutMs: Int): Long? {
        val session = activeSession() ?: return null
        return runCatching {
            val response = withTimeoutOrNull(timeoutMs.toLong() + 1_000L) {
                askExtension(session, "udpping:$host|$port|$timeoutMs")
            } ?: return null
            response.trim().toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()
    }

    private suspend fun hysteria2ProbePing(
        profile: ProxyProfile,
        timeoutMs: Int,
        connected: Boolean
    ): Long? = withContext(Dispatchers.Default) {
        if (isLoopbackHost(profile.server)) return@withContext null
        val port = profile.serverPort.takeIf { it in 1..65535 } ?: 443

        // 1. Try UDP QUIC probe (direct or via extension)
        val udpMs = if (connected) {
            directUdpPingViaExtension(profile.server, port, timeoutMs)
        } else {
            core.udpPing(profile.server, port, timeoutMs).takeIf { it > 0 }
        }
        if (udpMs != null && udpMs > 0) return@withContext udpMs

        // 2. Direct TCP ping to specified port (in case port forwarding / reverse proxy answers)
        val tcpPortMs = if (connected) {
            directTcpPingViaExtension(profile.server, port, timeoutMs)
        } else {
            core.tcpPing(profile.server, port, timeoutMs).takeIf { it > 0 }
        }
        if (tcpPortMs != null && tcpPortMs > 0) return@withContext tcpPortMs

        // 3. Fallback to host TCP 443 and 80 (measure latency to host when Hysteria port is filtered/closed)
        if (port != 443) {
            val ms443 = if (connected) {
                directTcpPingViaExtension(profile.server, 443, timeoutMs)
            } else {
                core.tcpPing(profile.server, 443, timeoutMs).takeIf { it > 0 }
            }
            if (ms443 != null && ms443 > 0) return@withContext ms443
        }
        if (port != 80) {
            val ms80 = if (connected) {
                directTcpPingViaExtension(profile.server, 80, timeoutMs)
            } else {
                core.tcpPing(profile.server, 80, timeoutMs).takeIf { it > 0 }
            }
            if (ms80 != null && ms80 > 0) return@withContext ms80
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

    private suspend fun tunnelPing(): Long? = withContext(Dispatchers.IO) {
        if (status.value != VpnStatus.Connected) return@withContext null
        val behavior = IosSharedStore.loadAppBehavior()
        val urlString = behavior.effectivePingUrl()
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val ok = runCatching {
            val response: HttpResponse = if (behavior.pingMode == AppBehaviorSettings.PING_PROXY_GET) {
                pingClient.get(urlString) {
                    header(HttpHeaders.Connection, "close")
                    header(HttpHeaders.CacheControl, "no-cache")
                }
            } else {
                pingClient.head(urlString) {
                    header(HttpHeaders.Connection, "close")
                    header(HttpHeaders.CacheControl, "no-cache")
                }
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
     * Per-server URL test (à la v2rayNG / Happ) through a throwaway xray instance.
     * Pre-resolves the proxy server hostname to IPv4 via native iOS DNS (avoiding Go's /etc/resolv.conf
     * 5-second timeout on iOS!) and runs inside PacketTunnelProvider when VPN is connected so the probe
     * bypasses utun and measures true end-to-end exit latency without double-hopping.
     */
    private suspend fun proxyUrlTest(
        profile: ProxyProfile,
        url: String,
        method: String,
        timeoutMs: Int = PING_TIMEOUT_MS,
        connected: Boolean = false
    ): Long? {
        if (profile.server.isBlank() ||
            profile.type == ProxyProfile.TYPE_HYSTERIA2 ||
            profile.type == ProxyProfile.TYPE_MIERU ||
            profile.type == ProxyProfile.TYPE_TRUSTTUNNEL
        ) return null

        val listenPort = nextPingListenPort()
        val raw = profile.rawXrayConfig
        val baseConfigJson = if (!raw.isNullOrBlank()) {
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

        val resolvedIp = resolveServerIpv4ForPing(profile.server, connected = connected)
        val configJson = patchPingXrayConfigForIos(baseConfigJson, resolvedIp)

        if (connected) {
            val ms = directXrayPingViaExtension(configJson, url, method, timeoutMs)
            if (ms != null && ms > 0) return ms
            if (ms == -2L) {
                // PacketTunnel memory is tight: fall back to lightweight direct TCP ping.
                val port = profile.serverPort.takeIf { it in 1..65535 } ?: 443
                return directTcpPingViaExtension(profile.server, port, timeoutMs)
            }
            return null
        }

        return xrayPingSemaphore.withPermit {
            core.xrayMeasureDelay(configJson, url, method, timeoutMs).takeIf { it >= 0 }
        }
    }

    /**
     * Rewrites the proxy outbound's hostname to its pre-resolved IPv4 (while preserving TLS/Reality
     * SNI = original hostname) and injects compact iOS memory policy + explicit IPv4 DNS so Go's
     * xray-core never stalls for 5s on missing /etc/resolv.conf on iOS.
     */
    private fun patchPingXrayConfigForIos(configJson: String, resolvedIp: String?): String {
        val root = runCatching { Json.parseToJsonElement(configJson).jsonObject }.getOrNull() ?: return configJson
        val outbounds = root["outbounds"] as? JsonArray ?: return configJson
        val patchedOutbounds = buildJsonArray {
            outbounds.forEachIndexed { index, el ->
                val obj = el as? JsonObject
                if (index == 0 && obj != null && !resolvedIp.isNullOrBlank()) {
                    add(rewriteOutboundAddressPreservingSni(obj, resolvedIp))
                } else {
                    add(el)
                }
            }
        }
        val patched = buildJsonObject {
            root.forEach { (k, v) ->
                when (k) {
                    "outbounds" -> put("outbounds", patchedOutbounds)
                    "dns", "policy" -> Unit
                    else -> put(k, v)
                }
            }
            putJsonObject("policy") {
                putJsonObject("levels") {
                    putJsonObject("0") {
                        put("bufferSize", 8)
                        put("connIdle", 15)
                        put("handshake", 6)
                    }
                }
            }
        }
        return patched.toString()
    }

    private fun rewriteOutboundAddressPreservingSni(outbound: JsonObject, ip: String): JsonObject {
        val settings = outbound["settings"] as? JsonObject ?: return outbound
        var originalHost: String? = null
        val newSettings = buildJsonObject {
            settings.forEach { (k, v) ->
                if ((k == "vnext" || k == "servers") && v is JsonArray) {
                    put(k, buildJsonArray {
                        v.forEachIndexed { i, item ->
                            val entry = item as? JsonObject
                            if (i == 0 && entry != null) {
                                val addr = entry["address"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (addr.isNotEmpty() && !isIpv4Literal(addr)) {
                                    originalHost = addr
                                    putEntryAddress(entry, ip)
                                } else {
                                    add(item)
                                }
                            } else {
                                add(item)
                            }
                        }
                    })
                } else {
                    put(k, v)
                }
            }
        }
        val hostForSni = originalHost ?: return outbound
        val stream = outbound["streamSettings"] as? JsonObject
        val newStream = if (stream != null) {
            val sec = stream["security"]?.jsonPrimitive?.contentOrNull?.lowercase()
            buildJsonObject {
                stream.forEach { (k, v) ->
                    if (k == "tlsSettings" && sec == "tls" && v is JsonObject) {
                        val existingSni = v["serverName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (existingSni.isEmpty()) {
                            put(k, buildJsonObject {
                                v.forEach { (tk, tv) -> put(tk, tv) }
                                put("serverName", hostForSni)
                            })
                        } else {
                            put(k, v)
                        }
                    } else if (k == "realitySettings" && sec == "reality" && v is JsonObject) {
                        val existingSni = v["serverName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (existingSni.isEmpty()) {
                            put(k, buildJsonObject {
                                v.forEach { (rk, rv) -> put(rk, rv) }
                                put("serverName", hostForSni)
                            })
                        } else {
                            put(k, v)
                        }
                    } else {
                        put(k, v)
                    }
                }
            }
        } else null

        return buildJsonObject {
            outbound.forEach { (k, v) ->
                when {
                    k == "settings" -> put("settings", newSettings)
                    k == "streamSettings" && newStream != null -> put("streamSettings", newStream)
                    else -> put(k, v)
                }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.putEntryAddress(entry: JsonObject, ip: String) {
        add(buildJsonObject {
            entry.forEach { (k, v) ->
                if (k == "address") put("address", ip) else put(k, v)
            }
        })
    }

    /**
     * The connect request the extension reads (location + device id) and the name the widget shows —
     * both in the App Group container.
     */
    private suspend fun publishRequest(location: LocationConfig) {
        val deviceId = locationsRepository.getDeviceIdentity()
        val locJson = Json.encodeToString(LocationConfig.serializer(), location)
        val trafficJson = IosSharedStore.readText("traffic.json") ?: "{}"
        val routingJson = IosSharedStore.readText("routing.json") ?: "{}"
        val routingProfilesJson = IosSharedStore.readText("routing_profiles.json") ?: "{}"
        val appBehaviorJson = IosSharedStore.readText("app_behavior.json") ?: "{}"
        var warpConfig = org.olcbox.app.vpn.warp.WarpConfigStore.loadConfig().orEmpty()
        if (warpConfig.isNotBlank() && !warpConfig.contains("PrivateKey", ignoreCase = true)) {
            warpConfig = ""
        }
        if (warpConfig.isNotBlank()) {
            val upgraded = org.olcbox.app.vpn.warp.WarpConfigGenerator.upgradeIfNeeded(warpConfig)
            if (upgraded != warpConfig) {
                runCatching { org.olcbox.app.vpn.warp.WarpConfigStore.saveConfig(upgraded) }
                warpConfig = upgraded
            }
        }
        if (location.warpEnabled && warpConfig.isBlank()) {
            addLog("WARP: generating Cloudflare WARP config...")
            val generated = withContext(Dispatchers.Default) {
                runCatching {
                    org.olcbox.app.vpn.warp.WarpConfigGenerator.generate { msg -> addLog(msg) }
                }.onFailure {
                    addLog("WARP: generation failed: ${it.message}")
                }.getOrNull()
            }
            if (!generated.isNullOrBlank()) {
                runCatching { org.olcbox.app.vpn.warp.WarpConfigStore.saveConfig(generated) }
                warpConfig = generated
            }
        }
        val rawXray = location.proxy?.rawXrayConfig.orEmpty()
        val needsGeoAssets = rawXray.contains("geosite:", ignoreCase = true) ||
            rawXray.contains("geoip:", ignoreCase = true) ||
            location.routingProfileId.isNotBlank()
        if (needsGeoAssets && !org.olcbox.app.vpn.ios.IosGeoAssets.hasAssets()) {
            addLog("Geo assets missing — downloading geoip.dat / geosite.dat...")
            val profilesState = runCatching { IosSharedStore.loadRoutingProfiles() }.getOrNull()
            val geoipUrl = profilesState?.geoipUrl.orEmpty()
            val geositeUrl = profilesState?.geositeUrl.orEmpty()
            withContext(Dispatchers.Default) {
                runCatching {
                    org.olcbox.app.vpn.ios.IosGeoAssets.ensureAssets(geoipUrl, geositeUrl)
                }
            }
        }

        val requestPayload = buildJsonObject {
            put("locationJSON", JsonPrimitive(locJson))
            put("trafficJSON", JsonPrimitive(trafficJson))
            put("routingJSON", JsonPrimitive(routingJson))
            put("routingProfilesJSON", JsonPrimitive(routingProfilesJson))
            put("appBehaviorJSON", JsonPrimitive(appBehaviorJson))
            put("warpConfig", JsonPrimitive(warpConfig))
            put("deviceID", JsonPrimitive(deviceId))
            put("deviceId", JsonPrimitive(deviceId))
            put("location", Json.parseToJsonElement(locJson))
        }
        IosSharedStore.writeText(
            IosTunnelSession.REQUEST_FILE,
            requestPayload.toString()
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
                val wasNotConnected = _status.value != VpnStatus.Connected
                setStatus(VpnStatus.Connected)
                connectedLocationId = inFlightLocationId ?: connectedLocationId
                inFlightLocationId = null
                if (wasNotConnected) {
                    addLog("Tunnel connected")
                    triggerNotificationHaptic(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                    NSNotificationCenter.defaultCenter.postNotificationName("org.yptun.vpn.connected", null)
                    // Now that traffic goes through the tunnel, retry the geo databases if they are still
                    // missing: without them a verbatim Xray config loses every geosite:/geoip: rule.
                    settingsController?.ensureGeoAssets()
                    scope.launch {
                        delay(1500)
                        val ms = tunnelPing()
                        if (ms != null && ms > 0) {
                            addLog("Tunnel ping: ${ms}ms")
                            updateWidgetPing(ms)
                        }
                    }
                }
            }
            NEVPNStatusReasserting -> setStatus(VpnStatus.Reconnecting)
            NEVPNStatusDisconnecting -> setStatus(VpnStatus.Stopping)
            else -> {
                connectWatchdogJob?.cancel()
                val wasConnected = _status.value == VpnStatus.Connected
                _connectedSince.value = 0L
                inFlightLocationId = null
                connectedLocationId = null
                val failure = IosSharedStore.readText(IosTunnelSession.ERROR_FILE)?.trim().orEmpty()
                if (wasConnecting && failure.isNotEmpty()) {
                    setStatus(VpnStatus.Error(failure))
                } else {
                    setStatus(VpnStatus.Disconnected)
                }
                if (wasConnected) {
                    NSNotificationCenter.defaultCenter.postNotificationName("org.yptun.vpn.disconnected", null)
                }
                wasConnecting = false
            }
        }
    }

    private suspend fun awaitDisconnected(m: NETunnelProviderManager) {
        // TrustTunnel teardown (stopping DNS proxies + event loop + vpn_close) can take 2–4s;
        // wait up to 6s so startVPNTunnel never collides with a dying extension process.
        repeat(60) {
            val s = m.connection.status
            if (s == platform.NetworkExtension.NEVPNStatusDisconnected || s == platform.NetworkExtension.NEVPNStatusInvalid) return
            delay(100)
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
            var lastSize = -1L
            var last = ""
            while (isActive) {
                val currentSize = IosSharedStore.fileSize(IosTunnelSession.LOG_FILE)
                if (currentSize != lastSize) {
                    lastSize = currentSize
                    val text = withContext(Dispatchers.Default) {
                        IosSharedStore.readTextTail(IosTunnelSession.LOG_FILE, LOG_TAIL_BYTES)
                    }.orEmpty()
                    if (text != last) {
                        last = text
                        _logs.value = text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(MAX_LOG_LINES)
                    }
                }
                delay(if (_isConnected.value) 4_000L else 1_500L)
            }
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        IosSharedStore.appendLog(message)
        _logs.value = (_logs.value + message).takeLast(MAX_LOG_LINES)
    }

    override fun clearLogs() {
        val clearMsg = if (_isConnected.value) "[Logs cleared] Connected" else "[Logs cleared]"
        IosSharedStore.writeText(IosTunnelSession.LOG_FILE, "$clearMsg\n")
        _logs.value = listOf(clearMsg)
    }

    private companion object {
        const val NOTIFIED_FILE = "notified_keys.txt"
        const val MAX_NOTIFIED_KEYS = 200
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
