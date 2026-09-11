package org.olcbox.app.vpn

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ios.IosCoreBridge
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
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

    private var manager: NETunnelProviderManager? = null
    private var statusObserver: Any? = null
    private var logJob: Job? = null
    private var captchaJob: Job? = null
    private var wasConnecting = false
    private var lastCaptchaUrl = ""

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
        }
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        scope.launch {
            val active = locationsRepository.getActiveLocation()?.location?.normalized()
            if (active == null || !active.isComplete()) {
                setStatus(VpnStatus.Error("No active location"))
                addLog("Add a valid location before connecting")
                return@launch
            }
            if (active.engine == EngineType.OpenFlux) {
                setStatus(VpnStatus.Error("OpenFlux на iOS пока не поддерживается"))
                return@launch
            }
            setStatus(VpnStatus.Connecting)
            val result = runCatching {
                publishRequest(active)
                IosSharedStore.writeText(IosTunnelSession.ERROR_FILE, "")
                val m = loadManager(createIfMissing = true) ?: error("VPN profile unavailable")
                val connection = m.connection
                // A running tunnel keeps its old location; restart it on the new request.
                if (connection.status != platform.NetworkExtension.NEVPNStatusDisconnected &&
                    connection.status != platform.NetworkExtension.NEVPNStatusInvalid
                ) {
                    connection.stopVPNTunnel()
                    awaitDisconnected(m)
                }
                memScoped {
                    val err = alloc<ObjCObjectVar<NSError?>>()
                    if (!connection.startVPNTunnelAndReturnError(err.ptr)) {
                        error(err.value?.localizedDescription ?: "VPN start failed")
                    }
                }
            }
            result.onFailure {
                val message = it.message ?: "VPN start failed"
                addLog("VPN start failed: $message")
                setStatus(VpnStatus.Error(message))
            }
        }
    }

    override fun stopVpn() {
        scope.launch {
            setStatus(VpnStatus.Stopping)
            manager?.connection?.stopVPNTunnel() ?: setStatus(VpnStatus.Disconnected)
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? = withContext(Dispatchers.Default) {
        val config = locationConfig.normalized()
        val behavior = IosSharedStore.loadAppBehavior()
        val profile = config.proxy
        val method = if (behavior.pingMode == AppBehaviorSettings.PING_PROXY_GET) "GET" else "HEAD"
        when {
            config.engine == EngineType.Stealth -> rtcPing(config)
            profile == null -> null
            profile.type == ProxyProfile.TYPE_AMNEZIAWG ->
                profile.awgConfig.takeIf { it.isNotBlank() }
                    ?.let { core.awgMeasureDelay(it, behavior.effectivePingUrl(), method, PING_TIMEOUT_MS) }
                    ?.takeIf { it >= 0 }
            else -> proxyUrlTest(profile, behavior.effectivePingUrl(), method)
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
        val request = IosTunnelRequest(location, locationsRepository.getDeviceIdentity())
        IosSharedStore.writeText(
            IosTunnelSession.REQUEST_FILE,
            IosTunnelSession.json.encodeToString(IosTunnelRequest.serializer(), request)
        )
        IosSharedStore.writeText(
            WIDGET_FILE,
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(location.displayName()))
            }.toString()
        )
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
        if (existing == null || !m.enabled) {
            m.setProtocolConfiguration(NETunnelProviderProtocol().apply {
                setProviderBundleIdentifier(TUNNEL_BUNDLE_ID)
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
        }
        manager = m
        observeStatus(m)
        return m
    }

    private fun observeStatus(m: NETunnelProviderManager) {
        statusObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        statusObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NEVPNStatusDidChangeNotification,
            `object` = m.connection,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onSystemStatus(m) }
        onSystemStatus(m)
    }

    private fun onSystemStatus(m: NETunnelProviderManager) {
        val s: NEVPNStatus = m.connection.status
        when (s) {
            NEVPNStatusConnecting -> {
                wasConnecting = true
                setStatus(VpnStatus.Connecting)
                watchCaptcha(m)
            }
            NEVPNStatusConnected -> {
                wasConnecting = false
                _connectedSince.value = m.connection.connectedDate
                    ?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L
                setStatus(VpnStatus.Connected)
            }
            NEVPNStatusReasserting -> setStatus(VpnStatus.Reconnecting)
            NEVPNStatusDisconnecting -> setStatus(VpnStatus.Stopping)
            else -> {
                _connectedSince.value = 0L
                val failure = IosSharedStore.readText(IosTunnelSession.ERROR_FILE)?.trim().orEmpty()
                setStatus(if (wasConnecting && failure.isNotEmpty()) VpnStatus.Error(failure) else VpnStatus.Disconnected)
                wasConnecting = false
            }
        }
    }

    private suspend fun awaitDisconnected(m: NETunnelProviderManager) {
        repeat(50) {
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
            var last = ""
            while (isActive) {
                val text = withContext(Dispatchers.Default) { IosSharedStore.readText(IosTunnelSession.LOG_FILE) }.orEmpty()
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
        const val MAX_LOG_LINES = 500
        const val PING_TIMEOUT_MS = 8_000
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
    }
}
