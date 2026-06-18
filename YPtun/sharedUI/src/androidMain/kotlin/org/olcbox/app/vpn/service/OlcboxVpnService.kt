package org.olcbox.app.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import awg.Awg
import awg.LogWriter as AwgLogWriter
import awg.Protector as AwgProtector
import hy2.Hy2
import hy2.LogWriter as Hy2LogWriter
import hy2.Protector as Hy2Protector
import xraybridge.Xraybridge
import xraybridge.Protector as XrayProtector
import freeturn.Freeturn
import freeturn.LogWriter as FreeturnLogWriter
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.singbox.SingBoxEngine
import org.olcbox.app.vpn.xray.XrayConfig
import org.olcbox.app.vpn.xray.XrayEngine
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTED_SINCE
import org.olcbox.app.vpn.data.KEY_ANDROID_ROUTING
import org.olcbox.app.vpn.data.KEY_ANDROID_ROUTING_PROFILES
import org.olcbox.app.vpn.data.KEY_ANDROID_APP_BEHAVIOR
import org.olcbox.app.vpn.data.KEY_ANDROID_TRAFFIC
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.SingBoxRule
import org.olcbox.app.vpn.geo.GeoAssetManager
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.data.model.VkTurnConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

class OlcboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val logcatStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var lastNotificationStatus = ""
    /** Display name of the currently-connecting/connected location, shown in the notification. */
    @Volatile private var connectedLocationName = ""
    @Volatile private var showSpeedInNotif = false

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var speedJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var currentNetworkTransport: UpstreamTransport? = null
    private var isCallbackRegistered = false
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var socksProxy: AuthenticatedSocksProxy? = null
    private var singBox: SingBoxEngine? = null
    private var xray: XrayEngine? = null
    private var engineType: EngineType = EngineType.Stealth
    private var activeMtu: Int = TUN_MTU
    // Snapshotted from the (suspend) traffic settings in [startMobile] so the non-suspend
    // [writeTun2socksConfig] can decide whether to drop the bridge's IPv6. True for the IPv4-leaning
    // strategies — "IPv4 only" (ipv4_only) AND "IPv4 preferred" (prefer_ipv4): both want traffic on
    // IPv4, so the bridge refuses IPv6 (RST → Happy-Eyeballs falls back to IPv4). prefer_ipv6/
    // ipv6_only keep dual-stack. See [writeTun2socksConfig].
    private var activeDropBridgeIpv6: Boolean = false
    private var activeProxyCore: ProxyCore = ProxyCore.SingBox

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network != currentNetwork) return

            networkLossJob?.cancel()
            networkLossJob = scope.launch {
                delay(NETWORK_LOSS_GRACE_MS)
                if (network != currentNetwork) return@launch

                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                    return@launch
                }

                if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification(ns.notifWaitingNetwork)
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return
            networkLossJob?.cancel()

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    requestTransportRecovery(
                        reason = "Network available",
                        fullRestart = false,
                        delayMs = NETWORK_STABILITY_GRACE_MS
                    )
                }
                return
            }

            val previousTransport = currentNetworkTransport
            val nextTransport = upstream.transportOrNull()
            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (OlcboxVpnState.status.value) {
                is VpnStatus.Connected -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Keeping transport on refreshed Wi-Fi network")
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS,
                            setReconnectingImmediately = false
                        )
                    }
                }

                is VpnStatus.Reconnecting -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport) &&
                        coreRunning() &&
                        canReconnectTransportInPlace()
                    ) {
                        setStatus(VpnStatus.Connected)
                        updateNotification(connectedNotificationText())
                        startWatchdog()
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olcbox::VpnWakeLock")
            .apply { setReferenceCounted(false) }

        installMobileCallbacks()
        observeSpeedNotificationSetting()
        startLogcatCapture()
        seedConnectedClockFromDisk()
    }

    /**
     * Full-logs capture: tails this process's logcat into the in-app journal, so EVERYTHING the
     * native cores emit (xray-core, sing-box/libbox, olcRTC, freeturn, WireGuard…) shows up in the
     * "Журнал" — not just the lines we explicitly addLog(). Reads only our own PID (no special
     * permission needed) and skips our own OlcboxVpnService tag to avoid echoing addLog() twice.
     */
    private fun startLogcatCapture() {
        if (logcatStarted.getAndSet(true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val pid = android.os.Process.myPid()
                    // -T 1: start from the next line (skip backlog). Capture all levels (verbose).
                    val proc = ProcessBuilder(
                        "logcat", "-v", "time", "-T", "1", "--pid=$pid", "*:V"
                    ).redirectErrorStream(true).start()
                    proc.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            // Skip our own service tag (already added via addLog) and high-frequency
                            // Android UI/render spam that just floods the journal (the frameRateCategory
                            // VRR logging on Android 15/16 was the worst offender — see LOGCAT_NOISE).
                            if (!line.contains("OlcboxVpnService") &&
                                LOGCAT_NOISE.none { line.contains(it, ignoreCase = true) }
                            ) {
                                OlcboxVpnState.appendRaw(line)
                            }
                        }
                    }
                    proc.destroy()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    OlcboxVpnState.addLog("logcat capture restarting: ${e.message}")
                    delay(2_000)
                }
            }
        }
    }

    /**
     * Keeps [showSpeedInNotif] in sync with the live setting so toggling "speed in notification"
     * while already connected takes effect immediately (without reconnecting). Turning it off
     * reposts the plain status to drop the speed line.
     */
    private fun observeSpeedNotificationSetting() {
        scope.launch {
            applicationContext.vpnPrefDataStore.data
                .map { prefs ->
                    val raw = prefs[KEY_ANDROID_APP_BEHAVIOR] ?: return@map false
                    runCatching {
                        Json.decodeFromString(AppBehaviorSettings.serializer(), raw).showSpeedInNotification
                    }.getOrDefault(false)
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    showSpeedInNotif = enabled
                    if (!enabled && OlcboxVpnState.status.value is VpnStatus.Connected) {
                        updateNotification(connectedNotificationText())
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        applyStartOptions(loadStartOptions(intent))
        val isRestart = shouldRestartForStartCommand()
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@OlcboxVpnService.protect(fd.toInt())
            }
        })
        // Register the xray/awg socket protectors process-wide (not just when those engines start),
        // so the per-server ping probes (xraybridge/awg MeasureDelay) always leave via the underlying
        // network and bypass the active TUN — otherwise, while connected through ANY engine, the probe
        // would ride the tunnel and report a bogus tunnel-inflated latency. See item: ping via main iface.
        runCatching {
            Xraybridge.setProtector(object : XrayProtector {
                override fun protect(fd: Long): Boolean {
                    if (connectionMode == AndroidConnectionMode.Proxy) return true
                    return this@OlcboxVpnService.protect(fd.toInt())
                }
            })
        }.onFailure { Log.w(TAG, "xray setProtector failed", it) }
        runCatching {
            Awg.setProtector(object : AwgProtector {
                override fun protect(fd: Long): Boolean {
                    if (connectionMode == AndroidConnectionMode.Proxy) return true
                    return this@OlcboxVpnService.protect(fd.toInt())
                }
            })
        }.onFailure { Log.w(TAG, "awg setProtector failed", it) }
        runCatching {
            Hy2.setProtector(object : Hy2Protector {
                override fun protect(fd: Long): Boolean {
                    if (connectionMode == AndroidConnectionMode.Proxy) return true
                    return this@OlcboxVpnService.protect(fd.toInt())
                }
            })
        }.onFailure { Log.w(TAG, "hy2 setProtector failed", it) }
        Mobile.setProviders()
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                Log.v("olcrtc", line)
                handleRtcLine(line)
            }
        })
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelMode = AndroidSplitTunnelMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                    ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            ),
            splitTunnelProxyApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
            splitTunnelBypassApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        // Security: in TUN mode the local SOCKS5 is purely internal (tun2socks <-> core), so
        // force per-session random credentials. Otherwise any app on the device could connect to
        // the unauthenticated 127.0.0.1 SOCKS listener, learn the real exit IP and bypass
        // split tunneling (disclosed 2026 for xray/sing-box mobile clients). In Proxy mode the
        // SOCKS is intentionally exposed, so the user's configured credentials are kept.
        if (options.connectionMode == AndroidConnectionMode.Tun) {
            socksUsername = randomSocksToken()
            socksPassword = randomSocksToken()
        }
        splitTunnelMode = options.splitTunnelMode
        splitTunnelProxyApps = options.splitTunnelProxyApps
        splitTunnelBypassApps = options.splitTunnelBypassApps
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false
    ) {
        val previousStartupJob = startupJob
        val hadPendingStartup = previousStartupJob?.isActive == true
        previousStartupJob?.cancel()
        watchdogJob?.cancel()
        speedJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        if (hadPendingStartup) {
            addLog("Canceling pending olcRTC start")
            stopMobile()
            stopTun2socks()
        }
        if (!isMigration) {
            resetRecoveryState()
        }
        val requestedGeneration = ++generation
        refreshWakeLock(force = true)

        startupJob = scope.launch {
            try {
                cleanupJob?.takeIf { it.isActive }?.let {
                    addLog("Waiting for previous olcRTC cleanup")
                    val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                        it.join()
                        true
                    } ?: false

                    if (!completed) {
                        addLog("Previous olcRTC cleanup is still pending; forcing transport cleanup")
                        it.cancel()
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    }
                }

                if (!isMigration) {
                    registerNetworkMonitor()
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock

                    val active = repository.getActiveLocation()
                    val location = active?.location?.normalized()
                    if (location == null || !location.isComplete()) {
                        setStatus(VpnStatus.Error("No active location"))
                        updateNotification(ns.notifAddLocation)
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }

                    if (isMigration && !forceFullRestart && canReconnectTransportInPlace()) {
                        reconnectTransport(location, requestedGeneration)
                    } else {
                        startFullTunnel(location, requestedGeneration, isMigration, isRestart)
                    }
                }
            } finally {
                if (requestedGeneration == generation) {
                    releaseWakeLock()
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        connectedLocationName = location.displayName()
        setStatus(VpnStatus.Reconnecting)
        updateNotification(ns.notifReconnecting)
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification(ns.notifWaitingNetwork)
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (startMobile(location, upstream, requestedGeneration, setErrorOnFailure = false)) {
            restoreOrStartConnectedClock()
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification(ns.notifWaitingTransport)
            scheduleTransportRetry(requestedGeneration, "transport reconnect failed")
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean
    ) {
        connectedLocationName = location.displayName()
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification(ns.notifConnecting)
        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification(ns.notifWaitingNetwork)
            if (isMigration) {
                scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            }
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startMobile(location, upstream, requestedGeneration, setErrorOnFailure = !isMigration)) {
            if (isMigration) {
                updateUnderlyingNetwork(null)
                setStatus(VpnStatus.Reconnecting)
                updateNotification(ns.notifWaitingTransport)
                scheduleTransportRetry(requestedGeneration, "transport start failed")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            restoreOrStartConnectedClock()
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("Proxy mode connected on SOCKS $socksListenHost:$socksListenPort")
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        restoreOrStartConnectedClock()
        setStatus(VpnStatus.Connected)
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established")
        // Interface hiding is handled by the bundled Zygisk module (VpnHideModule), not by renaming
        // the tun here — renaming an UP VpnService tun is refused by the kernel (EBUSY) and, if forced
        // via down/up, detaches Android's name-keyed `oif tun0` rules and breaks per-app VPN routing.
        maybeShareVpnHotspot()
        startWatchdog()
    }

    /**
     * Brings up whatever core serves the local SOCKS5 listener, dispatching on the location's
     * engine. The TUN bridge ([startTun2socks]) always points at [socksListenPort] regardless.
     */
    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        engineType = location.engine
        val trafficSettings = loadTrafficSettings()
        activeMtu = trafficSettings.mtu
        activeDropBridgeIpv6 = trafficSettings.normalized().domainStrategy
            .let { it == "ipv4_only" || it == "prefer_ipv4" }
        showSpeedInNotif = loadAppBehavior().showSpeedInNotification
        return when (location.engine) {
            EngineType.Stealth -> startStealthCore(location, upstream, requestedGeneration, setErrorOnFailure)
            EngineType.Standard,
            EngineType.Chain -> startSingBoxCore(location, upstream, requestedGeneration, setErrorOnFailure)
            EngineType.VkTurn -> startVkTurnCore(location, upstream, requestedGeneration, setErrorOnFailure)
        }
    }

    private suspend fun startStealthCore(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            installMobileCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            waitForJitsiRoomCleanup(config.bypassProvider)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            configureMobileTransport(config)
            applyTelemostCookies(config)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
            lastMobileProvider = config.bypassProvider
            Mobile.startWithTransport(
                config.bypassProvider,
                config.transport,
                config.id,
                deviceId,
                config.key,
                targetSocksPort.toLong(),
                socksUsername,
                socksPassword
            )
            Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                return false
            }
            coroutineContext.ensureActive()
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            addLog("username: $socksUsername, password: $socksPassword")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification(ns.notifConnectionFailed)
            }
            false
        } finally {
            if (!keepProcessBound || !Mobile.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    /**
     * Standard: sing-box (VLESS) serves SOCKS on [socksListenPort].
     * Chain: olcRTC raises SOCKS on [chainOlcrtcPort] and sing-box dials its outbound through it,
     * so the VLESS connection is wrapped inside the WebRTC stealth tunnel.
     */
    private suspend fun startSingBoxCore(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val config = location.normalized()
        val profile = config.proxy
        if (profile == null || !profile.isComplete()) {
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error("No proxy configured"))
                updateNotification(ns.notifAddProxy)
            }
            return false
        }

        val chained = config.engine == EngineType.Chain
        val chainPort = chainOlcrtcPort
        // Optional SECOND/cascade proxy chained on top of the main: traffic exits via it, dialing
        // through the main (→ olcRTC for Chain). Null = single hop (main is the exit).
        val secondProfile = config.proxy2?.takeIf { it.isComplete() }
        return try {
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")

            waitForSocksPortReleased(socksListenPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(socksListenPort)) {
                throw IllegalStateException("SOCKS port $socksListenPort is still in use")
            }

            if (chained) {
                resetRtcHealthState()
                waitForSocksPortReleased(chainPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
                installMobileCallbacks()
                configureMobileTransport(config)
                applyTelemostCookies(config)
                addLog("Starting olcRTC (chain) provider=${config.bypassProvider}, room=${config.id}")
                lastMobileProvider = config.bypassProvider
                Mobile.startWithTransport(
                    config.bypassProvider,
                    config.transport,
                    config.id,
                    deviceIdentityProvider.hwid(),
                    config.key,
                    chainPort.toLong(),
                    socksUsername,
                    socksPassword
                )
                Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
                markRtcConnected()
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) return false
                addLog("olcRTC chain ready on 127.0.0.1:$chainPort")
            }

            // AmneziaWG / Hysteria2: raise a local SOCKS (awgproxy / hysteria2proxy) and route the
            // proxy through it. Both are full UDP tunnels (carry QUIC natively), modeled as a socks
            // outbound → sing-box core, exactly like the AmneziaWG path.
            val isAwg = profile.type == ProxyProfile.TYPE_AMNEZIAWG
            val isHy2 = profile.type == ProxyProfile.TYPE_HYSTERIA2
            val effectiveProfile = when {
                isAwg -> prepareAmneziaWgProxy(profile)
                isHy2 -> prepareHysteria2Proxy(profile)
                else -> profile
            }
            val isLocalUdpTunnel = isAwg || isHy2

            // Happ-style routing profile (per-location override → global default), if any.
            val profilesState = loadRoutingProfilesState()
            val routingProfile = profilesState.resolve(config.routingProfileId)
            // Diagnostic: surfaces which profile (if any) is actually applied, so a "routing ignored"
            // report can be traced to "no profile resolved" vs "profile applied but ineffective".
            if (routingProfile == null) {
                addLog(
                    "Routing: NO profile applied (location id='${config.routingProfileId}', " +
                        "global='${profilesState.globalProfileId}', ${profilesState.profiles.size} saved) — all traffic via proxy"
                )
            } else {
                addLog(
                    "Routing: applying '${routingProfile.displayName()}' " +
                        "(direct sites=${routingProfile.directSites}, direct ip=${routingProfile.directIp}, " +
                        "globalProxy=${routingProfile.globalProxy})"
                )
            }

            // App-wide engine default (Auto/sing-box/Xray); applied only when the location core is Auto
            // and the transport doesn't force a core — a per-location choice still wins (see resolvedCore).
            val globalCore = loadAppBehavior().globalProxyCore
            activeProxyCore = if (isLocalUdpTunnel) ProxyCore.SingBox else config.resolvedCore(globalCore)
            // Only the RU-domain blocklist (regexp DNS hosts) and a profile's dns.hosts are Xray-only;
            // geo selectors work on BOTH cores (sing-box resolves geoip:/geosite: via remote .srs it
            // downloads itself), so geo must NOT force Xray — otherwise a missing geoip.dat would drop
            // the whole profile (incl. domain:ru) and Russian sites would wrongly egress via the VPN.
            // Routing-rule matching (domain:/geoip:/geosite: → direct/block/proxy) runs on sing-box
            // too: the AmneziaWG path proves it (AWG forces sing-box yet `domain:ru → direct` egresses
            // via the real IP correctly). Earlier we force-switched EVERY routing profile to Xray, but
            // that left standard (vless/…) locations broken whenever Xray couldn't start (e.g. a
            // geoip.dat download blocked on a whitelist network), so `domain:ru` wrongly egressed via
            // the VPN — exactly the "routing only works for AmneziaWG" bug. Now only a profile that
            // needs the RU regexp DNS-hosts (`dnsHosts`, genuinely Xray-only) forces Xray; plain
            // routing rules stay on whichever core the location resolves to (sing-box by default),
            // matching the working AWG behaviour.
            val profileWantsXray = routingProfile != null && routingProfile.dnsHosts.isNotEmpty()
            // A user-supplied full Xray JSON (dns / routing / balancers / custom fields) can ONLY run
            // verbatim on xray-core. Force Xray so the whole template is honored instead of falling to
            // sing-box, which would rebuild from the parsed profile and drop everything but the outbound.
            if (!effectiveProfile.rawXrayConfig.isNullOrBlank()) {
                if (activeProxyCore != ProxyCore.Xray) addLog("Raw Xray config present → forcing Xray core")
                activeProxyCore = ProxyCore.Xray
            }
            if (activeProxyCore == ProxyCore.SingBox &&
                (loadTrafficSettings().blockRuDomains || profileWantsXray) &&
                effectiveProfile.rawOutbound.isNullOrBlank() &&
                effectiveProfile.type in XRAY_SUPPORTED_TYPES
            ) {
                activeProxyCore = ProxyCore.Xray
                addLog(
                    if (profileWantsXray) "Switching to Xray core for routing profile (native domain:/geoip: matching)"
                    else "Switching to Xray core for RU-domain blocklist"
                )
            }
            // If Xray would run but the profile's geo .dat files can't be fetched (e.g. the current
            // network is blocked and NEEDS the VPN to reach GitHub), xray silently drops geosite:/geoip:
            // rules → `geosite:ru → direct` never fires and RU sites wrongly egress via the proxy. sing-box
            // downloads its own .srs rule-sets through the tunnel, so it keeps working — which is exactly
            // why routing "only works on sing-box/AmneziaWG, not xray". Fall back to sing-box when the
            // transport allows it (plain vless/ws/reality; not xhttp or a raw Xray template).
            if (activeProxyCore == ProxyCore.Xray &&
                routingProfile?.needsGeoFiles() == true &&
                effectiveProfile.rawXrayConfig.isNullOrBlank() &&
                effectiveProfile.network != ProxyProfile.NETWORK_XHTTP &&
                effectiveProfile.rawOutbound.isNullOrBlank() &&
                ensureGeoAssetPath(routingProfile).isEmpty()
            ) {
                activeProxyCore = ProxyCore.SingBox
                addLog("Geo databases unavailable for Xray → using sing-box for routing (it downloads its own rule-sets)")
            }
            // The cascade runs both hops in one core. An xhttp second proxy can only run on Xray, so
            // force it (overriding the geo fallback above) — sing-box can't carry xhttp.
            if (secondProfile?.network == ProxyProfile.NETWORK_XHTTP && activeProxyCore != ProxyCore.Xray) {
                activeProxyCore = ProxyCore.Xray
                addLog("Second (cascade) proxy uses xhttp → forcing Xray core")
            }
            if (activeProxyCore == ProxyCore.Xray) {
                val rawXray = effectiveProfile.rawXrayConfig
                var assetPath = ""
                val json = if (!rawXray.isNullOrBlank()) {
                    // User-supplied full Xray config: run mostly verbatim, but MERGE the routing profile
                    // (e.g. domain:ru → direct) so it applies to cascade/custom configs too — previously
                    // the profile was ignored here, which is why domain:ru worked on AWG but not vless.
                    if (routingProfile != null) assetPath = ensureGeoAssetPath(routingProfile)
                    addLog("Starting Xray with custom config" +
                        if (routingProfile != null) " + routing profile '${routingProfile.displayName()}'" else " (verbatim)")
                    XrayConfig.prepareRaw(
                        rawConfigJson = rawXray,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                        fakeDnsEnabled = loadTrafficSettings().fakeDnsEnabled,
                    )
                } else {
                    // Download geoip.dat/geosite.dat if the profile needs them (no-op when present).
                    assetPath = ensureGeoAssetPath(routingProfile)
                    XrayConfig.build(
                        profile = effectiveProfile,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        olcrtcChainPort = if (chained) chainPort else null,
                        olcrtcChainUser = if (chained) socksUsername else "",
                        olcrtcChainPass = if (chained) socksPassword else "",
                        // Per-location advanced (mux / TLS fragment) override the global traffic knobs.
                        traffic = loadTrafficSettings().let { t ->
                            config.advanced?.let {
                                t.copy(
                                    muxEnabled = it.muxEnabled,
                                    muxProtocol = it.muxProtocol,
                                    muxMaxConnections = it.muxMaxStreams,
                                    fragmentEnabled = it.tlsFragment,
                                )
                            } ?: t
                        },
                        routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                        secondProfile = secondProfile,
                    )
                }
                addLog("Starting Xray engine=${config.engine}, server=${effectiveProfile.server}:${effectiveProfile.serverPort}")
                xrayEngine().start(json, assetPath)
            } else {
                // AmneziaWG is a full UDP tunnel: it carries QUIC natively, so don't block QUIC
                // (that broke it like the VK-TURN regression). For the IPv4-only requirement we use
                // sniff-override instead of a blanket IPv6 reject: the SNI replaces an app's own-DoH
                // IPv6 literal and is re-resolved to IPv4, so traffic rides the tunnel as IPv4 (no
                // IPv6 leak) AND Chrome's IPv6 attempts aren't rejected/flooded.
                if (isAwg) addLog("AmneziaWG outbound: QUIC allowed + sniff-override→IPv4 (no IPv6 leak)")
                if (isHy2) addLog("Hysteria2 outbound: QUIC allowed + sniff-override→IPv4 (no IPv6 leak)")
                val json = SingBoxConfig.build(
                    profile = effectiveProfile,
                    listenPort = socksListenPort,
                    listenHost = socksListenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    olcrtcChainPort = if (chained) chainPort else null,
                    olcrtcChainUser = if (chained) socksUsername else "",
                    olcrtcChainPass = if (chained) socksPassword else "",
                    autoDetectInterface = true,
                    routing = loadRouting(),
                    traffic = loadTrafficSettings(),
                    advanced = config.advanced,
                    routingProfile = routingProfile,
                    singboxGeositeBase = profilesState.singboxGeositeBase,
                    singboxGeoipBase = profilesState.singboxGeoipBase,
                    blockQuic = !isLocalUdpTunnel,
                    sniffOverrideDestination = isLocalUdpTunnel,
                    // STRICT "IPv4 only": forceFamilyResolve (default true) keeps the `::/0 reject` so a real
                    // IPv6 destination never leaves. This stays on EVEN with fakeip — but fakeip now also
                    // hands out a fake IPv6 (fc00::/18) for AAAA, which sing-box restores to the domain
                    // BEFORE the IP rules, so fake v6 is tunnelled as IPv4 while only REAL v6 hits the
                    // reject. Combined with the DoH-domain block, apps resolve everything via fakeip → no
                    // real IPv6 ever appears → google works under strict IPv4.
                    secondProfile = secondProfile,
                    // FakeDNS translated from an imported Xray config → reproduced natively on sing-box.
                    fakeDnsSpec = config.fakeDns,
                )
                if (config.fakeDns != null) addLog("FakeDNS spec present → enabling sing-box fakeip (pool ${config.fakeDns!!.inet4Range}, ${config.fakeDns!!.blockRegex.size} block rules)")
                addLog("Starting sing-box engine=${config.engine} via ${effectiveProfile.server}:${effectiveProfile.serverPort}")
                singBoxEngine().start(json)
            }

            if (!awaitSocksPortOpen(socksListenPort, MOBILE_READY_TIMEOUT_MS)) {
                throw IllegalStateException("sing-box SOCKS port $socksListenPort did not open")
            }
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) {
                addLog("sing-box start superseded")
                return false
            }
            addLog("sing-box ready on $socksListenHost:$socksListenPort")
            publishActiveSocks()
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("sing-box start canceled")
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            addLog(if (staleRequest) "sing-box start canceled: $message" else "sing-box start failed: $message")
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification(ns.notifConnectionFailed)
            }
            false
        } finally {
            unbindProcessFromNetwork()
        }
    }

    /**
     * VK-TURN: the free-turn-proxy client raises a local WireGuard entry listener
     * (127.0.0.1:listenPort) tunnelling through VK, and sing-box runs a WireGuard
     * outbound dialling that listener — the panel's VK-TURN inbound consumed on the
     * client. Both are started together; the WireGuard handshake retries until the
     * freeturn listener binds, so a strict ordering barrier is unnecessary.
     */
    private suspend fun startVkTurnCore(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val config = location.normalized()
        val vk = config.vkturn
        val profile = config.proxy
        val outboundType = vk?.outbound?.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD }
            ?: VkTurnConfig.OUTBOUND_WIREGUARD
        val outboundConfigured = when (outboundType) {
            VkTurnConfig.OUTBOUND_AMNEZIAWG -> !profile?.awgConfig.isNullOrBlank()
            VkTurnConfig.OUTBOUND_PROXY -> profile != null &&
                profile.server.isNotBlank() && profile.serverPort in 1..65535
            else -> !profile?.rawOutbound.isNullOrBlank()
        }
        if (vk == null || !vk.isComplete() || !outboundConfigured) {
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error("VK-TURN not configured"))
                updateNotification(ns.notifAddVkLink)
            }
            return false
        }
        return try {
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")

            waitForSocksPortReleased(socksListenPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(socksListenPort)) {
                throw IllegalStateException("SOCKS port $socksListenPort is still in use")
            }

            // 1. freeturn client: local WireGuard entry listener tunnelling through VK.
            Freeturn.setDebug(false)
            Freeturn.setLogWriter(object : FreeturnLogWriter {
                override fun writeLog(line: String) {
                    val trimmed = line.trimEnd()
                    addLog("vkturn: $trimmed")
                    Log.v("vkturn", trimmed)
                }
            })
            val listenAddr = "127.0.0.1:${vk.listenPort}"
            // freeturn rejects `bond=1` unless mode==tcp; an old/imported URI carrying it in a udp
            // (WireGuard/AmneziaWG) tunnel makes the client fail to start. Strip it defensively.
            val freeturnUri = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) vk.uri
                else vk.uri.replace("&bond=1", "").replace("bond=1&", "").replace("bond=1", "")
            // Parallel TURN stream count, scaled GENTLY with the number of VK call links. The earlier
            // aggressive scaling (links×10) made it SLOWER, not faster: a single WireGuard flow sprayed
            // across 20-64 TURN paths of differing latency reorders past WG's replay window → drops →
            // throughput collapse, plus the per-packet obf on dozens of streams saturates the phone's CPU.
            // So keep a proven base (~one call's worth) and add only a FEW streams per EXTRA call, capped
            // low, to draw from more calls without exploding the parallel-path count. splitLinks() in
            // freeturn splits on newline/CR/tab/space/comma — count the same way. An explicit vk.streams
            // still acts as a power-user floor (up to a hard safety ceiling).
            val linkCount = vk.vkLink
                .split('\n', '\r', '\t', ' ', ',')
                .count { it.isNotBlank() }
                .coerceAtLeast(1)
            val autoStreams = (VKTURN_STREAMS_BASE + (linkCount - 1) * VKTURN_STREAMS_PER_EXTRA_CALL)
                .coerceAtMost(VKTURN_STREAMS_AUTO_MAX)
            val effectiveStreams = maxOf(vk.streams, autoStreams)
                .coerceAtMost(VKTURN_STREAMS_HARD_MAX)
            addLog("Starting VK-TURN freeturn listener on $listenAddr (links=$linkCount, streams=$effectiveStreams)")
            Freeturn.start(freeturnUri, listenAddr, vk.vkLink, effectiveStreams.toLong())
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) return false

            // Order the WireGuard bring-up behind the VK TURN relay: wait for the freeturn
            // client to establish at least one TURN stream (DTLS handshake + TURN allocation)
            // so the tunnel uplink is live before WireGuard starts handshaking. Otherwise the
            // WireGuard outbound can exhaust its handshake attempts and report offline while the
            // relay is still coming up (only masked on a fast same-LAN path). Best-effort: if the
            // relay does not report ready in time we proceed anyway and let WireGuard retry.
            if (awaitVkTurnRelayReady(VKTURN_RELAY_READY_TIMEOUT_MS)) {
                addLog("VK-TURN relay up (${Freeturn.connectedStreams()} stream(s)); starting WireGuard")
            } else {
                addLog("VK-TURN relay not ready yet; starting WireGuard anyway (will retry)")
            }
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) return false

            // 2. The exit outbound that dials the local freeturn listener and rides the VK tunnel:
            //    - WireGuard / AmneziaWG: a UDP tunnel whose Endpoint is the freeturn UDP listener
            //      (mode=udp). AmneziaWG is raised by the awgproxy module (local SOCKS) and routed
            //      through. WireGuard may additionally chain a proxy ON TOP (dialled through WG).
            //    - proxy: a TCP proxy whose server was rewritten to the freeturn TCP listener
            //      (mode=tcp); sing-box dials it directly through the tunnel.
            activeProxyCore = ProxyCore.SingBox
            val exitProfile = requireNotNull(profile)
            val routing = loadRouting()
            val traffic = loadTrafficSettings()
            // WG / freeturn TCP is IPv4-only → force A-only DNS so dual-stack sites don't dead-end.
            val ipv4Traffic = traffic.copy(domainStrategy = "ipv4_only")
            // VK-TURN is intentionally EXCLUDED from routing profiles (like olcRTC): it tunnels
            // everything through the WG-over-VK path, so no per-app routing is applied here.
            val profilesState = loadRoutingProfilesState()
            val routingProfile: RoutingProfile? = null

            // Chained proxy on top of WireGuard (parsed once; reused by both cores).
            val chainProxy = if (outboundType == VkTurnConfig.OUTBOUND_WIREGUARD) {
                vk.chainProxyLink.takeIf { it.isNotBlank() }
                    ?.let { ShareLinkParser.parse(it) }?.takeIf { it.isComplete() }
            } else null

            // The proxy whose core choice matters: the PROXY exit, or the WG chain proxy. AmneziaWG /
            // plain WireGuard have no typed proxy → always sing-box.
            val proxyForCore = when (outboundType) {
                VkTurnConfig.OUTBOUND_PROXY -> exitProfile
                else -> chainProxy
            }
            // A geo-based routing profile also forces Xray (sing-box has no native geo selectors), as
            // long as the relevant proxy is a typed proxy Xray can serve.
            val profileWantsXray = routingProfile != null &&
                (routingProfile.needsGeoFiles() || routingProfile.dnsHosts.isNotEmpty()) &&
                proxyForCore != null && proxyForCore.type in XRAY_SUPPORTED_TYPES
            // App-wide engine default applies to the VK-TURN exit/chain proxy too (per-location wins).
            val globalCore = loadAppBehavior().globalProxyCore
            val useXray = proxyForCore != null &&
                outboundType != VkTurnConfig.OUTBOUND_AMNEZIAWG &&
                (vk.resolvedProxyCore(proxyForCore, globalCore) == ProxyCore.Xray || profileWantsXray)

            if (useXray) {
                val assetPath = ensureGeoAssetPath(routingProfile)
                val xrayProfile = xrayRoutingProfile(routingProfile, assetPath)
                val xrayJson = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) {
                    addLog("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp, Xray)")
                    XrayConfig.build(
                        profile = exitProfile,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        logLevel = "debug",
                        traffic = ipv4Traffic,
                        routingProfile = xrayProfile,
                        blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                    )
                } else {
                    addLog("VK-TURN chaining proxy ${chainProxy!!.displayName()} over WireGuard (Xray)")
                    XrayConfig.build(
                        profile = chainProxy,
                        wireguardBase = exitProfile,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        logLevel = "debug",
                        traffic = ipv4Traffic,
                        routingProfile = xrayProfile,
                        blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                    )
                }
                activeProxyCore = ProxyCore.Xray
                addLog("Starting Xray (VK-TURN, $outboundType) via $listenAddr")
                xrayEngine().start(xrayJson, assetPath)
                if (!awaitSocksPortOpen(socksListenPort, MOBILE_READY_TIMEOUT_MS)) {
                    throw IllegalStateException("Xray SOCKS port $socksListenPort did not open")
                }
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) {
                    addLog("VK-TURN Xray start superseded")
                    return false
                }
                addLog("Xray ready on $socksListenHost:$socksListenPort")
                publishActiveSocks()
                return true
            }

            val json = when (outboundType) {
                VkTurnConfig.OUTBOUND_AMNEZIAWG -> {
                    addLog("VK-TURN exit: AmneziaWG over VK")
                    val awgSocks = prepareAmneziaWgProxy(exitProfile)
                    SingBoxConfig.build(
                        profile = awgSocks,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        autoDetectInterface = true,
                        routing = routing,
                        traffic = traffic,
                        routingProfile = routingProfile,
                        singboxGeositeBase = profilesState.singboxGeositeBase,
                        singboxGeoipBase = profilesState.singboxGeoipBase,
                        logLevel = "debug",
                        dnsStrategyOverride = "ipv4_only",
                        blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                    )
                }

                VkTurnConfig.OUTBOUND_PROXY -> {
                    addLog("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp)")
                    SingBoxConfig.build(
                        profile = exitProfile,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        autoDetectInterface = true,
                        routing = routing,
                        traffic = traffic,
                        routingProfile = routingProfile,
                        singboxGeositeBase = profilesState.singboxGeositeBase,
                        singboxGeoipBase = profilesState.singboxGeoipBase,
                        logLevel = "debug",
                        // The exit is reached through the IPv4 freeturn TCP listener; force A-only
                        // resolution so dual-stack sites don't attempt IPv6 (no v6 path) and the
                        // TUN's captured ::/0 stays a harmless blackhole instead of a dead route.
                        dnsStrategyOverride = "ipv4_only",
                        blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                    )
                }

                else -> { // WireGuard, optionally with a proxy chained on top
                    val chainProxy = vk.chainProxyLink.takeIf { it.isNotBlank() }
                        ?.let { ShareLinkParser.parse(it) }
                        ?.takeIf { it.isComplete() }
                    if (chainProxy != null) {
                        addLog("VK-TURN chaining proxy ${chainProxy.displayName()} over WireGuard")
                        SingBoxConfig.build(
                            profile = chainProxy,
                            wireguardBase = exitProfile,
                            listenPort = socksListenPort,
                            listenHost = socksListenHost,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            autoDetectInterface = true,
                            routing = routing,
                            traffic = traffic,
                            routingProfile = routingProfile,
                            singboxGeositeBase = profilesState.singboxGeositeBase,
                            singboxGeoipBase = profilesState.singboxGeoipBase,
                            logLevel = "debug",
                            dnsStrategyOverride = "ipv4_only",
                            blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                        )
                    } else {
                        SingBoxConfig.build(
                            profile = exitProfile,
                            listenPort = socksListenPort,
                            listenHost = socksListenHost,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            autoDetectInterface = true,
                            routing = routing,
                            traffic = traffic,
                            routingProfile = routingProfile,
                            singboxGeositeBase = profilesState.singboxGeositeBase,
                            singboxGeoipBase = profilesState.singboxGeoipBase,
                            // info level surfaces the WireGuard handshake so a dead server→client
                            // relay path (handshake never completes) is visible.
                            logLevel = "debug",
                            // WG tunnel is IPv4-only; force A-only resolution so dual-stack sites
                            // don't attempt IPv6 (no route through the tunnel → "no route to host").
                            dnsStrategyOverride = "ipv4_only",
                            blockQuic = false, // VK-TURN tunnels UDP; never block QUIC here
                        )
                    }
                }
            }
            addLog("Starting sing-box (VK-TURN, $outboundType) via $listenAddr")
            singBoxEngine().start(json)

            if (!awaitSocksPortOpen(socksListenPort, MOBILE_READY_TIMEOUT_MS)) {
                throw IllegalStateException("sing-box SOCKS port $socksListenPort did not open")
            }
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) {
                addLog("VK-TURN start superseded")
                return false
            }
            addLog("VK-TURN ready on $socksListenHost:$socksListenPort")
            publishActiveSocks()
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("VK-TURN start canceled")
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            addLog(if (staleRequest) "VK-TURN start canceled: $message" else "VK-TURN start failed: $message")
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification(ns.notifConnectionFailed)
            }
            false
        } finally {
            unbindProcessFromNetwork()
        }
    }

    private fun singBoxEngine(): SingBoxEngine {
        return singBox ?: SingBoxEngine(
            context = applicationContext,
            workDir = File(filesDir, "singbox"),
            tempDir = File(cacheDir, "singbox"),
            protect = { fd -> protect(fd) },
            log = { addLog(it) },
            underlyingNetwork = { currentNetwork }
        ).also { singBox = it }
    }

    private fun xrayEngine(): XrayEngine {
        return xray ?: XrayEngine(
            protect = { fd -> protect(fd) },
            log = { addLog(it) }
        ).also { xray = it }
    }

    private fun proxyCoreRunning(): Boolean =
        if (activeProxyCore == ProxyCore.Xray) xray?.isRunning == true else singBox?.isRunning == true

    /** True when the active engine's core(s) are alive. */
    private fun coreRunning(): Boolean = when (engineType) {
        EngineType.Stealth -> Mobile.isRunning()
        EngineType.Standard -> proxyCoreRunning()
        EngineType.Chain -> Mobile.isRunning() && proxyCoreRunning()
        EngineType.VkTurn -> Freeturn.isRunning() && proxyCoreRunning()
    }

    private suspend fun awaitSocksPortOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        return false
    }

    /** Waits for the freeturn client to bring up at least one TURN relay stream. */
    private suspend fun awaitVkTurnRelayReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!Freeturn.isRunning()) return false
            if (Freeturn.connectedStreams() > 0) return true
            delay(VKTURN_RELAY_POLL_MS)
        }
        return Freeturn.connectedStreams() > 0
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileTransport(location: LocationConfig) {
        val config = location.normalized()
        Mobile.setProviders()
        Mobile.setTransport(config.transport)
        // Preference-ordered DNS list (olcRTC probes & sticks to the first that actually answers):
        // Yandex first — it stays reachable on RU IPv4-only mobile where Cloudflare/Google UDP/53 are
        // blocked, which previously left OLCRTC unable to resolve ("doesn't work on IPv4-only").
        Mobile.setDNS("77.88.8.8:53,8.8.8.8:53,1.1.1.1:53")
        Mobile.setSocksListenHost(socksListenHost)
        Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification(ns.notifTunnelFailed)
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification(ns.notifTunnelFailed)
            false
        }
    }


    /**
     * ip-rule priority for the hotspot-share rules. MUST be a value Android's netd does NOT use for
     * its own rules (it uses 0/10000/11000/15998-22999/25000/29998/31000/32000), and teardown ALWAYS
     * matches on `iif <tether>` too — never a blind "del priority", which would wipe Android's rules.
     */
    private val hotspotRulePref = 26000

    /** Tether/hotspot interface name candidates (softAP, USB-RNDIS, BT-PAN). */
    private val hotspotIfaces = listOf("ap0", "ap1", "wlan1", "wlan2", "softap0", "swlan0", "rndis0", "usb0", "bt-pan")

    /** True once [maybeShareVpnHotspot] installed its rules — gates [teardownVpnHotspotShare] so we
     *  never spawn `su` (root prompt) on disconnect for users who never enabled the feature. */
    @Volatile private var hotspotShareApplied = false

    /**
     * EXPERIMENTAL (root): when [AppBehaviorSettings.shareVpnHotspot] is on, route hotspot/tethered
     * clients through the VPN. Android sends tethering straight to the upstream (bypassing the VPN);
     * we add `ip rule iif <tether> lookup <vpn-table>` (so client ingress uses the VPN routing table)
     * plus iptables forwarding + NAT (MASQUERADE) on the tun and TCP-MSS clamping. The iif rules are
     * added for every candidate tether name — a name that doesn't exist yet stays "detached" and
     * attaches automatically when the user turns the hotspot on. Best-effort; silently no-ops without
     * root. Does NOT touch the phone's own traffic (only FORWARDed packets), so browsing/routing are
     * unaffected. Torn down on disconnect by [teardownVpnHotspotShare].
     */
    private suspend fun maybeShareVpnHotspot() {
        if (!loadAppBehavior().shareVpnHotspot) return
        withContext(Dispatchers.IO) {
            val pref = hotspotRulePref
            val aps = hotspotIfaces.joinToString(" ")
            val script = buildString {
                append("export PATH=/system/bin:/system/xbin:/vendor/bin:\$PATH; ")
                append("IP=\$(command -v ip || echo /system/bin/ip); ")
                append("IPT=\$(command -v iptables || echo /system/bin/iptables); ")
                append("IP6T=\$(command -v ip6tables || echo /system/bin/ip6tables); ")
                // Detect the VPN tun and its routing table from its default route.
                append("L=\$(\$IP route show table all | grep -m1 '^default dev tun'); ")
                append("TUN=\$(echo \"\$L\" | sed -n 's/^default dev \\([^ ]*\\) table \\([^ ]*\\).*/\\1/p'); ")
                append("TBL=\$(echo \"\$L\" | sed -n 's/^default dev \\([^ ]*\\) table \\([^ ]*\\).*/\\2/p'); ")
                append("if [ -z \"\$TUN\" ] || [ -z \"\$TBL\" ]; then echo 'no VPN tun/table found'; exit 0; fi; ")
                append("echo 1 > /proc/sys/net/ipv4/ip_forward; ")
                append("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null; ")
                // Route each candidate tether iface's ingress into the VPN table (v4 + v6).
                append("for ap in $aps; do ")
                append("\$IP rule del iif \"\$ap\" lookup \"\$TBL\" priority $pref 2>/dev/null; ")
                append("\$IP rule add iif \"\$ap\" lookup \"\$TBL\" priority $pref 2>/dev/null; ")
                append("\$IP -6 rule del iif \"\$ap\" lookup \"\$TBL\" priority $pref 2>/dev/null; ")
                append("\$IP -6 rule add iif \"\$ap\" lookup \"\$TBL\" priority $pref 2>/dev/null; ")
                append("done; ")
                // Forwarding + NAT on the tun (insert at top so Android's FORWARD policy can't drop it).
                append("\$IPT -C FORWARD -o \"\$TUN\" -j ACCEPT 2>/dev/null || \$IPT -I FORWARD -o \"\$TUN\" -j ACCEPT; ")
                append("\$IPT -C FORWARD -i \"\$TUN\" -j ACCEPT 2>/dev/null || \$IPT -I FORWARD -i \"\$TUN\" -j ACCEPT; ")
                append("\$IPT -t nat -C POSTROUTING -o \"\$TUN\" -j MASQUERADE 2>/dev/null || \$IPT -t nat -I POSTROUTING -o \"\$TUN\" -j MASQUERADE; ")
                append("\$IPT -t mangle -C FORWARD -p tcp --tcp-flags SYN,RST SYN -o \"\$TUN\" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \$IPT -t mangle -I FORWARD -p tcp --tcp-flags SYN,RST SYN -o \"\$TUN\" -j TCPMSS --clamp-mss-to-pmtu; ")
                append("\$IP6T -C FORWARD -o \"\$TUN\" -j ACCEPT 2>/dev/null || \$IP6T -I FORWARD -o \"\$TUN\" -j ACCEPT; ")
                append("\$IP6T -C FORWARD -i \"\$TUN\" -j ACCEPT 2>/dev/null || \$IP6T -I FORWARD -i \"\$TUN\" -j ACCEPT; ")
                append("\$IP6T -t nat -C POSTROUTING -o \"\$TUN\" -j MASQUERADE 2>/dev/null || \$IP6T -t nat -I POSTROUTING -o \"\$TUN\" -j MASQUERADE 2>/dev/null; ")
                append("echo \"hotspot share on via \$TUN table \$TBL (tether: $aps)\"")
            }
            runCatching {
                val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
                val code = p.waitFor()
                hotspotShareApplied = true
                addLog("Hotspot share (root): exit=$code${if (out.isNotBlank()) " — $out" else ""}")
            }.onFailure {
                addLog("Hotspot share (root) failed (no su / denied): ${it.message}")
            }
        }
    }

    /**
     * Removes everything [maybeShareVpnHotspot] installed (our priority-[hotspotRulePref] ip-rules and
     * the per-tun iptables forward/NAT/MSS rules). Idempotent and safe to call even when the feature
     * was never enabled. Runs on every disconnect so stale rules never linger.
     */
    private suspend fun teardownVpnHotspotShare() {
        if (!hotspotShareApplied) return
        hotspotShareApplied = false
        withContext(Dispatchers.IO) {
            val pref = hotspotRulePref
            val aps = hotspotIfaces.joinToString(" ")
            val script = buildString {
                append("export PATH=/system/bin:/system/xbin:/vendor/bin:\$PATH; ")
                append("IP=\$(command -v ip || echo /system/bin/ip); ")
                append("IPT=\$(command -v iptables || echo /system/bin/iptables); ")
                append("IP6T=\$(command -v ip6tables || echo /system/bin/ip6tables); ")
                // Drop ONLY our iif rules — matched by iif+priority so Android's own priority-$pref
                // rules (oif <iface>) are never touched. Loop in case of duplicates.
                append("for ap in $aps; do ")
                append("while \$IP rule del iif \"\$ap\" priority $pref 2>/dev/null; do :; done; ")
                append("while \$IP -6 rule del iif \"\$ap\" priority $pref 2>/dev/null; do :; done; ")
                append("done; ")
                // Remove the forward/NAT/MSS rules for any tun the VPN may have used.
                append("for TUN in tun0 tun1 tun2 tun3; do ")
                append("\$IPT -D FORWARD -o \"\$TUN\" -j ACCEPT 2>/dev/null; ")
                append("\$IPT -D FORWARD -i \"\$TUN\" -j ACCEPT 2>/dev/null; ")
                append("\$IPT -t nat -D POSTROUTING -o \"\$TUN\" -j MASQUERADE 2>/dev/null; ")
                append("\$IPT -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -o \"\$TUN\" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null; ")
                append("\$IP6T -D FORWARD -o \"\$TUN\" -j ACCEPT 2>/dev/null; ")
                append("\$IP6T -D FORWARD -i \"\$TUN\" -j ACCEPT 2>/dev/null; ")
                append("\$IP6T -t nat -D POSTROUTING -o \"\$TUN\" -j MASQUERADE 2>/dev/null; ")
                append("done")
            }
            runCatching {
                ProcessBuilder("su", "-c", script).redirectErrorStream(true).start().waitFor()
            }
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("YPtun")
                .setMtu(activeMtu)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)
            // Always capture IPv6 (addAddress + addRoute ::/0) so raw IPv6 traffic can NOT leak past
            // the tunnel to the real interface (was leaking the real IPv6 for VK-TURN). VK-TURN's
            // WireGuard/AmneziaWG/proxy paths all force ipv4_only DNS, so dual-stack apps never get
            // AAAA records and won't attempt IPv6 — the captured ::/0 only blackholes rare literal
            // IPv6, avoiding both the leak AND the old "no route to host" dead-sites problem.
            builder.addAddress(TUN_IPV6_ADDRESS, IPV6_PREFIX_LENGTH).addRoute("::", 0)

            if (!applySplitTunneling(builder)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification(ns.notifVpnTunnelError)
            null
        }
    }

    private fun applySplitTunneling(builder: Builder): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                addLog("Split tunneling: all apps use TUN")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification(ns.notifSplitTunnelError)
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification(ns.notifSplitTunnelError)
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                val applied = splitTunnelBypassApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)

        // IPv4-leaning strategy ("IPv4 only" OR "IPv4 preferred"): drop the tun's IPv6 address from the
        // bridge config so hev-socks5-tunnel REFUSES every IPv6 session (no `tunnel.ipv6` ⇒
        // ipv6_enabled=0 in the native bridge). This DROPS IPv6 for EVERY engine — including
        // olcRTC(Stealth) and VK-TURN, which run no sing-box/Xray family enforcement and would otherwise
        // carry IPv6 to an IPv4-only upstream → "google.com closed" (prefer_ipv4) or a server-side IPv6
        // on a leak-check. The system TUN still routes ::/0 into the tunnel (see establishSystemVpnTunnel),
        // so the refused IPv6 is blackholed — never leaked to the iface, in full OR split tunneling.
        // Why prefer_ipv4 belongs here: the bridge's mapped-DNS only ever answers A (never AAAA), so the
        // ONLY IPv6 reaching the bridge is from apps' own DoH/DoT (Chrome Secure DNS) or IPv6 literals —
        // which an IPv4 tunnel can't serve. RST-ing it makes the app fall back to IPv4 (= "prefer IPv4").
        // sing-box can still reach v6-only sites THROUGH the proxy (that path is app→fake-v4→domain→proxy,
        // it never hits the bridge as raw v6). prefer_ipv6/ipv6_only keep dual-stack (cores handle family).
        // [activeDropBridgeIpv6] is snapshotted in startMobile (loadTrafficSettings is suspend; this isn't).
        val ipv6Line = if (activeDropBridgeIpv6) "# ipv6 disabled (IPv4 only/preferred)" else "ipv6: '$TUN_IPV6_ADDRESS'"

        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $activeMtu
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS
              $ipv6Line

            socks5:
              address: ${socksConnectHost()}
              port: $socksListenPort
              udp: '${if (engineType == EngineType.Stealth) "tcp" else "udp"}'
              pipeline: false
              username: '$socksUsername'
              password: '$socksPassword'

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    /**
     * Refreshes the download/upload speed line in the notification on a short cadence (independent of
     * the slower health watchdog) so the rates feel live. Reads [showSpeedInNotif] each tick, so the
     * setting can be toggled on/off mid-connection. Only Tun mode exposes tun2socks byte counters.
     */
    private fun startSpeedUpdater() {
        speedJob?.cancel()
        if (connectionMode != AndroidConnectionMode.Tun) return
        speedJob = scope.launch {
            var prev: Tun2SocksStats? = null
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                val cur = readTun2SocksStats()
                if (showSpeedInNotif && cur != null && prev != null) {
                    val secs = (SPEED_INTERVAL_MS / 1000.0).coerceAtLeast(0.5)
                    val down = ((cur.rxBytes - prev.rxBytes).coerceAtLeast(0L) / secs).toLong()
                    val up = ((cur.txBytes - prev.txBytes).coerceAtLeast(0L) / secs).toLong()
                    updateNotification(lastNotificationStatus.ifBlank { ns.notifConnected }, speedLine(down, up))
                }
                prev = cur
                delay(SPEED_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        startSpeedUpdater()
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)

                when {
                    !coreRunning() -> {
                        addLog("Watchdog: transport core stopped")
                        requestTransportRecovery("olcRTC stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                if (currentNetwork != upstream) {
                    val previousTransport = currentNetworkTransport
                    val nextTransport = upstream.transportOrNull()
                    updateUnderlyingNetwork(upstream)
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Watchdog: refreshed Wi-Fi upstream")
                        continue
                    }
                    addLog("Watchdog: upstream changed to ${getNetName(upstream)}")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                if (mode == AndroidConnectionMode.Tun && isTunTrafficStalled()) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    requestTransportRecovery("TUN traffic stalled", fullRestart = false)
                    return@launch
                }
            }
        }
    }

    private fun cleanup(stopService: Boolean = true) {
        // Explicit user stop (ACTION_STOP_VPN / revoke / manager.stopVpn) resets the timer; a bare
        // onDestroy (stopService=false, e.g. process teardown on app-swipe) must NOT, so the
        // auto-restarted service can restore the running clock.
        if (stopService) clearPersistedConnectedClock()
        if (cleanupJob?.isActive == true) return

        val status = OlcboxVpnState.status.value
        if (status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            cleanupJob?.isActive != true
        ) {
            if (stopService) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        speedJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        releaseWakeLock()

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        stopAuthenticatedSocksProxy()
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        cleanupJob = scope.launch {
            try {
                teardownVpnHotspotShare()
                stopVisibleVpnProcesses()
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Disconnected)
                    addLog("${activeModeLabel()} stopped")
                }

                stopMobileAndWait()
                resetRecoveryState()
            } finally {
                if (stopService && generation == cleanupGeneration) stopSelf()
            }
        }
    }

    private suspend fun stopVisibleVpnProcesses() {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        cleanupVpnInterface()
        tunThread?.interrupt()
        waitForTun2socksStopped(tunThread)
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        unbindProcessFromNetwork()
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?) {
        if (thread == null) return
        val stopped = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (thread.isAlive) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("tun2socks cleanup is still pending")
        }
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true,
        stopMobileBeforeTun: Boolean = false
    ) {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        if (stopMobileBeforeTun) {
            stopMobile()
        }
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tunThread?.interrupt()
        if (closeTun) {
            waitForTun2socksStopped(tunThread)
        }
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        if (waitForSocksPort) {
            if (stopMobileBeforeTun) {
                waitForSocksPortReleased()
            } else {
                stopMobileAndWait()
            }
        } else if (!stopMobileBeforeTun) {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
    }

    /** Publishes the live SOCKS endpoint + per-session creds so the in-process ping can use them. */
    private fun publishActiveSocks() {
        OlcboxVpnState.activeSocks = OlcboxVpnState.SocksEndpoint(
            host = socksListenHost,
            port = socksListenPort,
            username = socksUsername,
            password = socksPassword,
        )
    }

    private fun stopMobile() {
        OlcboxVpnState.activeSocks = null
        runCatching { singBox?.stop() }
        runCatching { xray?.stop() }
        runCatching { Freeturn.stop() }
        runCatching { Awg.stop() }
        runCatching { Hy2.stop() }
        val provider = lastMobileProvider
        val wasRunning = Mobile.isRunning()
        runCatching { Mobile.stop() }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
    }

    /** olcRTC's local SOCKS port when chaining; sing-box dials its outbound through it. */
    private val chainOlcrtcPort: Int get() = socksListenPort + 1

    /** AmneziaWG's local SOCKS port (awgproxy) when a proxy uses the AmneziaWG transport. */
    private val awgLocalPort: Int get() = socksListenPort + 2

    /**
     * If [profile] is AmneziaWG, raise the awgproxy SOCKS5 from its config and return a SOCKS proxy
     * pointing at it, so sing-box (standalone or chained) routes through the AmneziaWG tunnel.
     * Otherwise returns [profile] unchanged.
     */
    private suspend fun prepareAmneziaWgProxy(profile: ProxyProfile): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_AMNEZIAWG) return profile
        runCatching { Awg.stop() }
        Awg.setDebug(false)
        Awg.setLogWriter(object : AwgLogWriter {
            override fun writeLog(line: String) {
                val trimmed = line.trimEnd()
                addLog("awg: $trimmed")
                Log.v("awg", trimmed)
            }
        })
        val listen = "127.0.0.1:$awgLocalPort"
        addLog("Starting AmneziaWG SOCKS on $listen")
        Awg.start(profile.awgConfig, listen)
        if (!awaitSocksPortOpen(awgLocalPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("AmneziaWG SOCKS port $awgLocalPort did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$awgLocalPort,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "AmneziaWG" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = awgLocalPort,
            rawOutbound = raw,
        )
    }

    private val hy2LocalPort: Int get() = socksListenPort + 3

    /**
     * If [profile] is Hysteria2, raise the hysteria2proxy SOCKS5 from its config and return a SOCKS
     * proxy pointing at it, so sing-box (standalone or chained) routes through the Hysteria2 tunnel.
     * Otherwise returns [profile] unchanged. Mirrors [prepareAmneziaWgProxy].
     */
    private suspend fun prepareHysteria2Proxy(profile: ProxyProfile): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_HYSTERIA2) return profile
        runCatching { Hy2.stop() }
        Hy2.setDebug(false)
        Hy2.setLogWriter(object : Hy2LogWriter {
            override fun writeLog(line: String) {
                val trimmed = line.trimEnd()
                addLog("hy2: $trimmed")
                Log.v("hy2", trimmed)
            }
        })
        val cfg = buildJsonObject {
            put("server", profile.server)
            put("port", profile.serverPort)
            if (profile.hy2Ports.isNotBlank()) put("ports", profile.hy2Ports)
            put("auth", profile.password)
            put("sni", profile.sni.ifBlank { profile.server })
            put("insecure", profile.allowInsecure)
            if (profile.hy2Obfs.isNotBlank()) {
                put("obfs", profile.hy2Obfs)
                put("obfsPassword", profile.hy2ObfsPassword)
            }
            if (profile.hy2UpMbps > 0) put("upMbps", profile.hy2UpMbps)
            if (profile.hy2DownMbps > 0) put("downMbps", profile.hy2DownMbps)
        }.toString()
        val listen = "127.0.0.1:$hy2LocalPort"
        addLog("Starting Hysteria2 SOCKS on $listen")
        Hy2.start(cfg, listen)
        if (!awaitSocksPortOpen(hy2LocalPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Hysteria2 SOCKS port $hy2LocalPort did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$hy2LocalPort,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "Hysteria2" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = hy2LocalPort,
            rawOutbound = raw,
        )
    }

    private suspend fun loadRouting(): RoutingRules {
        val raw = runCatching {
            applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_ROUTING]
        }.getOrNull() ?: return RoutingRules()
        val routing = runCatching { Json.decodeFromString(RoutingRules.serializer(), raw) }
            .getOrDefault(RoutingRules())
        // sing-box has no native package-regex matcher: expand each rule's regex against the
        // device's installed packages into concrete `package_name` entries before building.
        if (routing.rules.none { it.packageRegex.isNotEmpty() }) return routing
        val installed = runCatching {
            packageManager.getInstalledPackages(0).map { it.packageName }
        }.getOrDefault(emptyList())
        return routing.copy(rules = SingBoxRule.expandPackageRegex(routing.rules, installed))
    }

    private suspend fun loadRoutingProfilesState(): RoutingProfilesState {
        val raw = runCatching {
            applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_ROUTING_PROFILES]
        }.getOrNull() ?: return RoutingProfilesState()
        return runCatching { Json.decodeFromString(RoutingProfilesState.serializer(), raw) }
            .getOrDefault(RoutingProfilesState())
    }

    /** The Happ-style routing profile in effect for [config]: per-location override → global default. */
    private suspend fun resolveRoutingProfile(config: LocationConfig): RoutingProfile? =
        loadRoutingProfilesState().resolve(config.routingProfileId)

    /**
     * When [profile] references geosite:/geoip: selectors, makes sure the geo `.dat` files are present
     * (downloading from the configured sources if missing) and returns the asset directory for
     * xray-core. Returns "" when no geo files are needed or the download failed — xray then runs
     * without geo data (non-geo rules still apply) instead of failing to start.
     */
    private suspend fun ensureGeoAssetPath(profile: RoutingProfile?): String {
        if (profile == null || !profile.needsGeoFiles()) return ""
        val state = loadRoutingProfilesState()
        val geoip = profile.geoipUrl.ifBlank { state.geoipUrl }
        val geosite = profile.geositeUrl.ifBlank { state.geositeUrl }
        val ok = runCatching { GeoAssetManager.ensureAssets(applicationContext, geoip, geosite) }
            .getOrDefault(false)
        return if (ok) {
            addLog("Geo databases ready for routing profile '${profile.displayName()}'")
            GeoAssetManager.assetDir(applicationContext).absolutePath
        } else {
            addLog("Geo databases unavailable; profile geo rules will be skipped on Xray")
            ""
        }
    }

    /**
     * The routing profile to hand to xray-core: dropped when it needs `geoip.dat`/`geosite.dat` that
     * couldn't be downloaded ([assetPath] blank), since an Xray config referencing geosite:/geoip:
     * without the data fails to load. Degrading to no profile keeps the connection working.
     */
    private fun xrayRoutingProfile(profile: RoutingProfile?, assetPath: String): RoutingProfile? {
        if (profile == null) return null
        if (profile.needsGeoFiles() && assetPath.isEmpty()) {
            // Keep the non-geo rules (e.g. domain:ru → direct) rather than dropping everything; an
            // Xray config that referenced geosite:/geoip: without the .dat would fail to load.
            addLog("Geo databases unavailable — applying '${profile.displayName()}' without geo selectors")
            return profile.withoutGeoSelectors()
        }
        return profile
    }

    private suspend fun loadTrafficSettings(): TrafficSettings {
        val raw = runCatching {
            applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_TRAFFIC]
        }.getOrNull() ?: return TrafficSettings()
        return runCatching { Json.decodeFromString(TrafficSettings.serializer(), raw) }
            .getOrDefault(TrafficSettings())
            .normalized()
    }

    private suspend fun loadAppBehavior(): AppBehaviorSettings {
        val raw = runCatching {
            applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_APP_BEHAVIOR]
        }.getOrNull() ?: return AppBehaviorSettings()
        return runCatching { Json.decodeFromString(AppBehaviorSettings.serializer(), raw) }
            .getOrDefault(AppBehaviorSettings())
    }

    /**
     * Resolves the connection-timer start time as the tunnel goes Connected: RESTORE the persisted
     * value when present (the process was killed by an app-swipe and auto-restarted — keep counting
     * from the real start), otherwise stamp now and persist it (a genuine fresh connect, where the
     * value was cleared by the user's connect/stop action). Seeds [OlcboxVpnState] so the restored
     * time survives the transient Connecting reset on the auto-restart path.
     */
    private suspend fun restoreOrStartConnectedClock() {
        val store = applicationContext.vpnPrefDataStore
        val persisted = runCatching { store.data.first()[KEY_ANDROID_CONNECTED_SINCE] }.getOrNull() ?: 0L
        val value = if (persisted > 0L) persisted else System.currentTimeMillis()
        if (persisted <= 0L) {
            runCatching { store.edit { it[KEY_ANDROID_CONNECTED_SINCE] = value } }
        }
        OlcboxVpnState.setConnectedSince(value)
    }

    /** On (re)start, show the persisted elapsed time immediately, before the reconnect completes. */
    private fun seedConnectedClockFromDisk() {
        scope.launch {
            val persisted = runCatching {
                applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_CONNECTED_SINCE]
            }.getOrNull() ?: 0L
            if (persisted > 0L) OlcboxVpnState.setConnectedSince(persisted)
        }
    }

    /**
     * Clears the persisted timer. Called ONLY on an explicit user stop/connect — never on the
     * process-teardown (onDestroy) path — so an app-swipe kill leaves the value intact to be
     * restored by the auto-restarted service.
     */
    private fun clearPersistedConnectedClock() {
        scope.launch {
            // NonCancellable so the clear still lands if the scope is torn down mid-edit.
            withContext(NonCancellable) {
                runCatching { applicationContext.vpnPrefDataStore.edit { it.remove(KEY_ANDROID_CONNECTED_SINCE) } }
            }
        }
    }

    /** Applies the stored Yandex Telemost cookies to olcRTC when enabled and the carrier is Telemost. */
    private suspend fun applyTelemostCookies(config: LocationConfig) {
        val behavior = loadAppBehavior()
        val use = behavior.telemostCookiesEnabled &&
            behavior.telemostCookies.isNotBlank() &&
            LocationConfig.normalizeProvider(config.bypassProvider) == LocationConfig.PROVIDER_TELEMOST
        runCatching { Mobile.setTelemostCookies(if (use) behavior.telemostCookies.trim() else "") }
        if (use) addLog("Applied Telemost cookies")
    }

    /** Cryptographically random token for per-session local SOCKS5 credentials (hex, 18 bytes). */
    private fun randomSocksToken(): String {
        val bytes = ByteArray(18)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF or 0x100).toString(16).substring(1) }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait() {
        val socksPort = socksListenPort
        stopMobile()
        waitForSocksPortReleased(socksPort)
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(socksConnectHost(), port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && coreRunning()) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification(ns.notifReconnecting)
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification(ns.notifReconnecting)
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(isMigration = true, forceFullRestart = fullRestart)
        }
    }

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock", it)
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(isMigration = true)
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy -> coreRunning()
        }
    }

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            tun2socksThread != null ||
            socksProxy != null ||
            coreRunning()
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        currentNetworkTransport = network?.transportOrNull()
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
    }

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun isBenignWifiRefresh(
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?
    ): Boolean {
        return previousTransport == UpstreamTransport.Wifi &&
            nextTransport == UpstreamTransport.Wifi
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "YPtun VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String, speed: CharSequence? = null) {
        lastNotificationStatus = status
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status, speed))
    }

    private fun buildNotification(status: String, speed: CharSequence? = null): Notification {
        val title = "YPtun"
        // Body is the status line (the active server name when connected). When the
        // live speed is shown, append it right next to the name.
        val body: CharSequence = if (speed != null) {
            android.text.SpannableStringBuilder(status).append("   ").append(speed)
        } else {
            status
        }
        // Status-bar icon: our cat-head silhouette (system tints it monochrome).
        // Resolved by name because this lives in androidApp's resources, not sharedUI's R.
        val statIcon = resources.getIdentifier("ic_stat_yptun", "drawable", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(statIcon)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                ns.notifStop,
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, OlcboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Custom content so the COLORED app logo sits right next to the "YPtun" title.
        val pkg = packageName
        val layoutId = resources.getIdentifier("notif_olcbox", "layout", pkg)
        if (layoutId != 0) {
            val rv = android.widget.RemoteViews(pkg, layoutId)
            rv.setTextViewText(resources.getIdentifier("notif_title", "id", pkg), title)
            rv.setTextViewText(resources.getIdentifier("notif_text", "id", pkg), body)
            val logo = resources.getIdentifier("ic_notification_logo", "drawable", pkg)
            if (logo != 0) rv.setImageViewResource(resources.getIdentifier("notif_icon", "id", pkg), logo)
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
        } else {
            builder.setContentTitle(title).setContentText(body)
        }
        return builder.build()
    }

    private fun appIconBitmap(): android.graphics.Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(packageName)
        (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: run {
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp
        }
    }.getOrNull()

    /** "↓ 1.2 MB/s  ↑ 300 KB/s" with a green download arrow and a blue upload arrow. */
    private fun speedLine(downBytesPerSec: Long, upBytesPerSec: Long): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        val downStart = sb.length
        sb.append("↓ ")
        sb.setSpan(
            android.text.style.ForegroundColorSpan(0xFF2E7D32.toInt()), // green
            downStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append("${formatRate(downBytesPerSec)}   ")
        val upStart = sb.length
        sb.append("↑ ")
        sb.setSpan(
            android.text.style.ForegroundColorSpan(0xFF1565C0.toInt()), // blue
            upStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append(formatRate(upBytesPerSec))
        return sb
    }

    private fun formatRate(bytesPerSec: Long): String {
        val b = bytesPerSec.coerceAtLeast(0).toDouble()
        return when {
            b >= 1024 * 1024 -> String.format("%.1f MB/s", b / (1024 * 1024))
            b >= 1024 -> String.format("%.0f KB/s", b / 1024)
            else -> "${b.toLong()} B/s"
        }
    }

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        OlcboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    /** Localized strings for notifications, resolved against the user's current language choice. */
    private val ns get() = stringsFor(LocalizationState.effective)

    /**
     * Connected-state notification body: the active server's display name.
     * Falls back to the generic "Connected · VPN/Proxy" line if the name is empty.
     */
    private fun connectedNotificationText(): String =
        connectedLocationName.ifBlank { ns.notifConnectedMode(activeModeLabel()) }

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("YPtun_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val SPEED_INTERVAL_MS = 2_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 3
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 4_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val VKTURN_RELAY_READY_TIMEOUT_MS = 20_000L
        private const val VKTURN_RELAY_POLL_MS = 200L
        // VK-TURN parallel TURN streams. freeturn fans these across the call links (multiProvider), so
        // more streams = more parallel relay paths. But a single WireGuard flow degrades when sprayed
        // across too many paths of differing latency (reorder past WG's replay window → drops) and each
        // stream's per-packet obf burns phone CPU — so "more streams" backfires past a point. Keep a
        // proven BASE (≈ one call's worth) and add only a few streams per EXTRA call, capped LOW.
        private const val VKTURN_STREAMS_BASE = 10
        private const val VKTURN_STREAMS_PER_EXTRA_CALL = 3
        // Low auto cap on the gently-scaled total: 1 call→10, 2+→12. Upstream docs (free-turn-proxy
        // docs/modes.md) say 6-12 streams is the comfortable range and >15-20 RISKS A VK BAN, so 12 is
        // the safe ceiling — letting this run to 20-64 was the regression (slower + ban risk).
        private const val VKTURN_STREAMS_AUTO_MAX = 12
        // Hard ceiling that even an explicit power-user vk.streams can't exceed, so a huge pasted link
        // list / manual value can't spawn a runaway number of DTLS handshakes / TURN allocations.
        private const val VKTURN_STREAMS_HARD_MAX = 64
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        // ULA IPv6 address for the TUN. We claim ::/0 too so IPv6 traffic is captured by the
        // tunnel instead of leaking out the underlying network's native IPv6 (see issue #3).
        private const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1"
        private const val IPV6_PREFIX_LENGTH = 126
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        // Proxy types xray-core can serve from typed fields (used to prefer Xray for the RU blocklist).
        private val XRAY_SUPPORTED_TYPES = setOf(
            ProxyProfile.TYPE_VLESS,
            ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN,
            ProxyProfile.TYPE_SHADOWSOCKS
        )
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "OlcboxVpnService"

        // High-frequency Android UI/render/system spam that the whole-process logcat capture would
        // otherwise pour into the in-app journal, drowning the VPN/core lines that actually matter.
        // Matched case-insensitively as a substring of the raw logcat line (tag OR message).
        private val LOGCAT_NOISE = listOf(
            "frameRateCategory",   // Android 15/16 variable-refresh-rate: "frameRateCategory Request!"
            "setFrameRate",
            "BLASTBufferQueue",
            "Choreographer",
            "OpenGLRenderer",
            "ViewRootImpl",
            "ImeTracker",
            "InsetsController",
        )

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
            // Mirror to logcat at INFO so the full connect/recovery flow is visible via `adb logcat
            // OlcboxVpnService:I` (the in-app journal lives in-process only). The logcat-capture loop
            // skips the OlcboxVpnService tag, so this never echoes back into the journal.
            android.util.Log.i(TAG, msg)
        }
    }
}
