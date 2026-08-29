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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import awg.Awg
import awg.LogWriter as AwgLogWriter
import awg.Protector as AwgProtector
import xraybridge.Xraybridge
import xraybridge.Protector as XrayProtector
import freeturn.CaptchaPresenter as FreeturnCaptchaPresenter
import freeturn.Freeturn
import freeturn.LogWriter as FreeturnLogWriter
import wdttmobile.Wdttmobile
import wdttmobile.ConfigSink as WdttConfigSink
import dnsttmobile.Dnsttmobile
import dnsttmobile.DnsttClient
import dnsttmobile.SocketProtector as DnsttSocketProtector
import com.adguard.trusttunnel.DeepLink as TrustTunnelDeepLink
import com.adguard.trusttunnel.VpnClient as TrustTunnelVpnClient
import com.adguard.trusttunnel.VpnClientListener as TrustTunnelListener
import mobile.LogWriter
import mobile.Mobile
import mobile.Runtime as OlcrtcRuntime
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
import org.olcbox.app.data.importer.VkTurnComposer
import org.olcbox.app.data.share.YptunInboundCodec
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.singbox.SingBoxEngine
import org.olcbox.app.vpn.xray.XrayConfig
import org.olcbox.app.vpn.xray.XrayEngine
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.proxy.HttpProxyBridge
import org.olcbox.app.vpn.telegram.VpnSocketProtectBridge
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
import org.olcbox.app.vpn.geo.AsnResolver
import org.olcbox.app.vpn.geo.GeoAssetManager
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.data.model.VkTurnConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
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

    /** In-flight widget "Auto = fastest" search (ping pass + connect); null/inactive when idle. */
    private var autoConnectJob: Job? = null

    private var lastNotificationStatus = ""
    /** Display name of the currently-connecting/connected location, shown in the notification. */
    @Volatile private var connectedLocationName = ""
    @Volatile private var showSpeedInNotif = false
    // Publish live throughput to OlcboxVpnState for the optional Home-screen speed line (independent
    // of the notification speed toggle). Drives whether the 2s speed loop runs.
    @Volatile private var showSpeedOnHome = false
    // "connected/total rooms" in the notification (olcRTC multi-room only). [activeMultiRoomTotal] is
    // the number of rooms configured for the current connection (0 = not multi-room).
    @Volatile private var showRoomsInNotif = false
    @Volatile private var activeMultiRoomTotal = 0
    // Number of freeturn servers in the current VK-TURN connection when multiple are load-balanced
    // (0 = single server / not freeturn). Drives the "connected/total servers" notification, gated on
    // the same [showRoomsInNotif] toggle as olcRTC rooms.
    @Volatile private var activeFreeturnServers = 0

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
    /** Active dnstt (DNS tunnel) client for [EngineType.Dnstt]; null when another engine is running. */
    private var dnsttClient: DnsttClient? = null
    /** True when the active dnstt engine also fronts a proxy core (proxy-over-dnstt). */
    private var dnsttProxyActive: Boolean = false
    /** Active Trust Tunnel client (SOCKS-only) for a [ProxyProfile.TYPE_TRUSTTUNNEL] proxy; null otherwise. */
    private var trustTunnelClient: TrustTunnelVpnClient? = null
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
    private var httpProxyBridge: HttpProxyBridge? = null
    private var singBox: SingBoxEngine? = null
    private var xray: XrayEngine? = null
    // Multi-room (Stealth/Chain): when a location uses it, the single mobileRuntime tunnel is replaced by
    // N independent rooms fronted by a round-robin balancer (see OlcrtcRoomManager). Null = single-room.
    private var olcrtcRoomManager: OlcrtcRoomManager? = null
    // One owned olcRTC client lifecycle for the single-room path (Runtime replaced the old
    // package-level Mobile.start/stop/waitReady singleton upstream). Independent rooms (multi-room
    // path above) use their own package-level Mobile.startRoom/stopRoom handles, not this instance.
    private val mobileRuntime: OlcrtcRuntime = Mobile.new_()
    private var engineType: EngineType = EngineType.Stealth
    private var activeMtu: Int = TUN_MTU
    // Snapshotted from the (suspend) traffic settings in [startMobile] so the non-suspend
    // [writeTun2socksConfig] can decide whether to drop the bridge's IPv6. True for the IPv4-leaning
    // strategies — "IPv4 only" (ipv4_only) AND "IPv4 preferred" (prefer_ipv4): both want traffic on
    // IPv4, so the bridge refuses IPv6 (RST → Happy-Eyeballs falls back to IPv4). prefer_ipv6/
    // ipv6_only keep dual-stack. See [writeTun2socksConfig].
    private var activeDropBridgeIpv6: Boolean = false
    // Snapshotted from the (suspend) routing settings in [startMobile] so the non-suspend
    // [establishSystemVpnTunnel] can decide whether to carve the private/LAN ranges OUT of the TUN
    // routes. Carving them out makes "Обход LAN" work for EVERY engine — including olcRTC(Stealth),
    // VK-TURN and dnstt, whose cores tunnel everything and have no routing-engine "direct" bucket. LAN
    // packets then never enter the tunnel and reach the local network on the real interface directly.
    private var activeBypassLan: Boolean = false
    // Energy-saver mode snapshot: trims background work while connected (no logcat journal capture, a
    // much slower health watchdog). Read in [startMobile]; logcat is gated in [startLogcatCapture].
    @Volatile private var activeEnergySaver: Boolean = false
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
        observeStatusForWidgets()
    }

    /**
     * Repaints the home-screen widgets on every connection-status change (connect / disconnect /
     * reconnect / error). Live download/upload speed for the status widget is pushed separately by the
     * speed loop. The broadcast is explicit (targeted by class name) so it reaches the providers in
     * androidApp without the shared module referencing them.
     */
    private fun observeStatusForWidgets() {
        scope.launch {
            OlcboxVpnState.status.collect { refreshWidgets() }
        }
    }

    private fun refreshWidgets() {
        org.olcbox.app.widget.WidgetRefresh.ping(this)
    }

    /** True when at least one "full" status widget is placed — used to keep the speed loop alive. */
    private fun hasStatusWidgets(): Boolean = runCatching {
        android.appwidget.AppWidgetManager.getInstance(this)
            .getAppWidgetIds(
                android.content.ComponentName(packageName, "org.olcbox.app.widget.StatusWidgetProvider")
            ).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Full-logs capture: tails this process's logcat into the in-app journal, so EVERYTHING the
     * native cores emit (xray-core, sing-box/libbox, olcRTC, freeturn, WireGuard…) shows up in the
     * "Журнал" — not just the lines we explicitly addLog(). Reads only our own PID (no special
     * permission needed) and skips our own OlcboxVpnService tag to avoid echoing addLog() twice.
     */
    private fun startLogcatCapture() {
        if (logcatStarted.getAndSet(true)) return
        scope.launch(Dispatchers.IO) {
            // Energy-saver: skip the continuous logcat tail entirely (a persistent reader process +
            // per-line wakeups is one of the few always-on CPU costs we can drop). The journal stays
            // empty until the user turns energy-saver off and reconnects.
            if (runCatching { loadAppBehavior().energySaver }.getOrDefault(false)) {
                addLog("Energy saver on: in-app logcat journal disabled")
                return@launch
            }
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
                    val raw = prefs[KEY_ANDROID_APP_BEHAVIOR] ?: return@map Triple(false, false, false)
                    runCatching {
                        val s = Json.decodeFromString(AppBehaviorSettings.serializer(), raw)
                        Triple(s.showSpeedInNotification, s.showRoomsInNotification, s.showSpeedOnHome)
                    }.getOrDefault(Triple(false, false, false))
                }
                .distinctUntilChanged()
                .collect { (speed, rooms, speedHome) ->
                    showSpeedInNotif = speed
                    showRoomsInNotif = rooms
                    showSpeedOnHome = speedHome
                    if (OlcboxVpnState.status.value is VpnStatus.Connected) {
                        // Start/stop the 2s speed loop to match the new flags (it self-cancels when both
                        // are off), then repost the notification so the speed line appears/clears now.
                        startSpeedUpdater()
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

            OlcboxVpnActions.ACTION_AUTO_CONNECT -> {
                applyStartOptions(loadStartOptions(intent))
                startForeground(ns.autoConnectSearching)
                startAutoConnectSearch()
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
            when (connectionMode) {
                AndroidConnectionMode.Proxy -> "Starting proxy..."
                AndroidConnectionMode.Tproxy -> "Starting transparent proxy..."
                AndroidConnectionMode.Tun -> "Protecting your connection"
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        VpnSocketProtectBridge.protect = null
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        mobileRuntime.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode.isTunless) return true
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
                    if (connectionMode.isTunless) return true
                    return this@OlcboxVpnService.protect(fd.toInt())
                }
            })
        }.onFailure { Log.w(TAG, "xray setProtector failed", it) }
        runCatching {
            Awg.setProtector(object : AwgProtector {
                override fun protect(fd: Long): Boolean {
                    if (connectionMode.isTunless) return true
                    return this@OlcboxVpnService.protect(fd.toInt())
                }
            })
        }.onFailure { Log.w(TAG, "awg setProtector failed", it) }
        mobileRuntime.setLogWriter(object : LogWriter {
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
        // split tunneling (disclosed 2026 for xray/sing-box mobile clients).
        //
        // In Proxy mode the SOCKS is the user-facing product: force NO-AUTH so ANY local client
        // connects immediately. Requiring an (auto-generated) username/password is exactly what made
        // Proxy mode "connects but no traffic" — a client that doesn't send credentials is accepted at
        // the TCP layer but its CONNECT is rejected, so nothing flows. Loopback-only listener anyway.
        when (options.connectionMode) {
            AndroidConnectionMode.Tun -> {
                // Protect the internal loopback SOCKS5 (tun2socks <-> core) so no other app can use it.
                // Honor the user's configured login/password if BOTH are set (that "SOCKS5 proxy" field
                // is exactly for this); otherwise fall back to per-session random credentials so the
                // listener is never left open with no auth.
                if (options.socksUsername.isBlank() || options.socksPassword.isBlank()) {
                    socksUsername = randomSocksToken()
                    socksPassword = randomSocksToken()
                }
            }
            AndroidConnectionMode.Proxy, AndroidConnectionMode.Tproxy -> {
                socksUsername = ""
                socksPassword = ""
                // Bind the exposed SOCKS (and, in Tproxy mode, the tproxy inbound) on all interfaces so
                // it's reachable from loopback (on-device Chrome via the Wi-Fi proxy) AND the LAN (a PC or
                // a router redirecting to the phone's IP) — like Happ's local-proxy mode. The
                // HttpProxyBridge binds the same host. (TUN keeps its 127.0.0.1.)
                socksListenHost = AndroidSocksProxySettings.ALL_INTERFACES
            }
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

    /**
     * Widget "Auto = fastest" without opening the app: probes every complete location in parallel
     * (the same per-engine probe the in-app ping uses, via a process-shared [AndroidVpnManager]),
     * persists the fastest as the active location and connects it through the normal [startTunnel]
     * path — all inside the already-foregrounded service. When nothing answers the probe, falls back
     * to the current active location (the user explicitly tapped, so still connect SOMETHING).
     * Status goes Connecting immediately so both widgets repaint amber on the tap.
     */
    private fun startAutoConnectSearch() {
        if (autoConnectJob?.isActive == true) return
        addLog("Auto-connect: searching for the fastest server")
        setStatus(VpnStatus.Connecting)
        updateNotification(ns.autoConnectSearching)
        autoConnectJob = scope.launch {
            val bundle = runCatching { repository.getBundle() }.getOrNull()
            val candidates = bundle?.locations
                ?.map { it.storageId to it.location.normalized() }
                ?.filter { it.second.isComplete() }
                .orEmpty()
            if (candidates.isEmpty()) {
                addLog("Auto-connect: no ready locations")
                setStatus(VpnStatus.Disconnected)
                cleanup()
                return@launch
            }
            val manager = autoPingManager(applicationContext)
            // Same "ping parallelism" slider as the in-app ping/auto pass — one knob everywhere.
            val gate = Semaphore(loadAppBehavior().effectivePingParallelism())
            val results = coroutineScope {
                candidates.map { (id, config) ->
                    async {
                        val ms = gate.withPermit {
                            runCatching {
                                withTimeoutOrNull(AUTO_CONNECT_PING_TIMEOUT_MS) { manager.ping(config) }
                            }.getOrNull()
                        }
                        id to ms
                    }
                }.awaitAll()
            }
            val fastest = results.filter { it.second != null }.minByOrNull { it.second!! }
            val targetId = fastest?.first ?: bundle?.activeLocationId ?: candidates.first().first
            addLog(
                if (fastest != null) "Auto-connect: fastest server picked (${fastest.second} ms)"
                else "Auto-connect: no server answered the probe; connecting the active location"
            )
            if (targetId != bundle?.activeLocationId) {
                // Funnels through saveLocationBundle → widgets repaint with the new active name.
                runCatching { repository.setActiveLocationId(targetId) }
            }
            withContext(Dispatchers.Main) {
                startTunnel(isMigration = false, isRestart = shouldRestartForStartCommand())
            }
        }
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

        if (connectionMode.isTunless) {
            // No TUN in Proxy/Tproxy mode: the core's own listeners ARE the exposed proxy. startMobile has
            // kept the process bound to the upstream (see releaseProcessBindingUnlessProxy /
            // shouldKeepProcessBound) so the cores can actually reach the internet — without that the
            // SOCKS connected but carried no traffic.
            restoreOrStartConnectedClock()
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            startHttpProxyBridge()
            val shown = deviceLanIp() ?: "127.0.0.1"
            if (connectionMode == AndroidConnectionMode.Tproxy) {
                addLog("Transparent proxy ready (no VPN tunnel) — redirect traffic here:")
                addLog("  • TPROXY: $shown:$tproxyPort (TCP+UDP, needs root/iptables TPROXY)")
                addLog("  • SOCKS5: $shown:$socksListenPort")
                addLog("  • HTTP:   $shown:$httpProxyPort")
            } else {
                addLog("Proxy ready (no VPN tunnel) — point apps here:")
                addLog("  • SOCKS5: $shown:$socksListenPort")
                addLog("  • HTTP:   $shown:$httpProxyPort")
            }
            addLog("SOCKS/HTTP auth disabled — any client may connect (no username/password)")
            launchProxySelfCheck()
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
        // Publish protect() so the independent Telegram-over-WARP proxy can keep its WARP UDP socket
        // out of this tun while it's up (see VpnSocketProtectBridge). Cleared on teardown.
        VpnSocketProtectBridge.protect = { fd -> protect(fd) }
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
        activeBypassLan = runCatching { loadRouting(expandAsn = false).bypassLan }.getOrDefault(true)
        loadAppBehavior().let {
            showSpeedInNotif = it.showSpeedInNotification
            showRoomsInNotif = it.showRoomsInNotification
            showSpeedOnHome = it.showSpeedOnHome
            activeEnergySaver = it.energySaver
        }
        return when (location.engine) {
            EngineType.Stealth -> startStealthCore(location, upstream, requestedGeneration, setErrorOnFailure)
            EngineType.Standard,
            EngineType.Chain -> startSingBoxCore(location, upstream, requestedGeneration, setErrorOnFailure)
            EngineType.VkTurn -> startVkTurnCore(location, upstream, requestedGeneration, setErrorOnFailure)
            EngineType.Dnstt -> startDnsttCore(location, upstream, requestedGeneration, setErrorOnFailure)
        }
    }

    /**
     * Brings up the olcRTC SOCKS that fronts [port]: MULTI-ROOM (round-robin balancer over the main room
     * + each extra) when [config] asks for it, otherwise the proven single-room singleton. Shared by
     * Stealth (bridge port) and Chain (chain port). Blocks until ready; on a multi-room failure it cleans
     * up and falls back to single-room so the user is never left offline. The caller must have already
     * configured the transport/cookies/binding and is responsible for markRtcConnected + generation
     * checks afterwards. Rooms live on consecutive loopback ports above [port] and share the bridge
     * credentials (password-protected, loopback-only — no open proxy).
     */
    private suspend fun startOlcrtcSocks(config: LocationConfig, deviceId: String, port: Int, enableBond: Boolean = false) {
        if (config.usesMultiRoom()) {
            val specs = config.multiRoomSpecs().map {
                OlcrtcRoomManager.RoomSpec(
                    carrier = it.provider.ifBlank { config.bypassProvider },
                    transport = it.transport.ifBlank { config.transport },
                    room = it.room,
                    clientId = deviceId,
                    keyHex = it.key,
                )
            }
            // Stage-2 bond is meaningful only for the Chain→VLESS single flow ([enableBond] from the Chain
            // caller); Stealth keeps round-robin (parallel app flows already aggregate there).
            val bond = enableBond && config.usesMultiRoomBond()
            val bondPort = config.effectiveBondPort()
            val mode = if (bond) "bond (port $bondPort)" else "round-robin"
            addLog("Starting olcRTC MULTI-ROOM: ${specs.size} room(s) + $mode balancer on $port")
            val mgr = OlcrtcRoomManager(::addLog)
            olcrtcRoomManager = mgr
            activeMultiRoomTotal = specs.size
            val ok = withContext(Dispatchers.IO) {
                mgr.start(
                    rooms = specs,
                    listenHost = socksListenHost,
                    listenPort = port,
                    user = socksUsername,
                    pass = socksPassword,
                    bond = bond,
                    bondHost = "127.0.0.1",
                    bondPort = bondPort,
                )
            }
            if (ok) return
            // Don't leave the user offline if multi-room can't bring a room/balancer up — clean it up and
            // fall back to the proven single-room path (the main room via the singleton).
            addLog("Multi-room failed to start — falling back to single room")
            olcrtcRoomManager?.let { runCatching { it.stop() } }
            olcrtcRoomManager = null
            activeMultiRoomTotal = 0
        } else {
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}"
            )
        }
        mobileRuntime.setProvider(config.bypassProvider)
        mobileRuntime.setRoom(config.id)
        mobileRuntime.setKey(config.key)
        mobileRuntime.setDeviceID(deviceId)
        mobileRuntime.setSocksPort(port.toLong())
        mobileRuntime.setSocksCredentials(socksUsername, socksPassword)
        // A generation left in "stopping" (a teardown that outran its timeout) makes every
        // later start fail with ErrAlreadyRunning until the app is killed, so give the old
        // one a bounded teardown before starting. A no-op on the normal path.
        if (mobileRuntime.isRunning()) {
            addLog("olcRTC still active before start - tearing the previous run down first")
            runCatching { mobileRuntime.stop(PREVIOUS_STOP_WAIT_MS) }
        }
        mobileRuntime.start()
        mobileRuntime.waitReady(MOBILE_READY_TIMEOUT_MS)
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
            lastMobileProvider = config.bypassProvider
            startOlcrtcSocks(config, deviceId, targetSocksPort)
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
            if (!keepProcessBound || !mobileRuntime.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    /**
     * dnstt (DNS tunnel): the dnstt client raises a transparent TCP forwarder on [socksListenPort];
     * the dnstt-server relays each connection to its upstream SOCKS5, so the local port behaves as
     * that SOCKS5 and the TUN bridge consumes it directly. dnstt carries only TCP (KCP + Noise over
     * DNS TXT), and the forwarder can't terminate SOCKS auth, so the bridge runs no-auth (creds
     * cleared). The dnstt UDP socket is protected so DNS queries egress the real network rather than
     * looping back into the TUN.
     */
    private suspend fun startDnsttCore(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val config = location.normalized()
        val dnstt = config.dnstt
        if (dnstt == null || !dnstt.isComplete()) {
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error("DNSTT not configured"))
                updateNotification(ns.notifConnectionFailed)
            }
            return false
        }
        // Optional proxy chained ON TOP of the dnstt tunnel: the proxy server is dialled THROUGH the
        // dnstt local SOCKS, so the public exit is the proxy, not the dnstt-server. When present, dnstt
        // moves to an internal port and a proxy core (Xray/sing-box) fronts the bridge on socksListenPort.
        // Accept a normal share link (vless/vmess/trojan/ss) OR a yptun://inbound link (a whole shared
        // LocationConfig) — pulling its main/second proxy out — so the same paste that works for the
        // Standard "additional proxy" field works here too.
        val proxy = dnstt.proxyLink.takeIf { it.isNotBlank() }?.let { link ->
            (ShareLinkParser.parse(link)
                ?: YptunInboundCodec.parse(link)?.let { it.proxy ?: it.proxy2 })
                ?.takeIf { it.isComplete() }
        }
        if (dnstt.proxyLink.isNotBlank() && proxy == null) {
            addLog("DNSTT: proxy link present but could not be parsed — exiting via dnstt SOCKS directly (no proxy)")
        }
        val useProxy = proxy != null
        dnsttProxyActive = useProxy
        // With a proxy, dnstt listens on the internal chain port and the proxy dials through it.
        val dnsttPort = if (useProxy) chainOlcrtcPort else socksListenPort
        return try {
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            waitForSocksPortReleased(socksListenPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(socksListenPort)) {
                throw IllegalStateException("SOCKS port $socksListenPort is still in use")
            }
            if (useProxy) {
                waitForSocksPortReleased(dnsttPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
                if (isLocalSocksPortOpen(dnsttPort)) {
                    throw IllegalStateException("DNSTT internal port $dnsttPort is still in use")
                }
            }
            val dnsttAddr = "$socksListenHost:$dnsttPort"
            addLog("Starting DNSTT on $dnsttAddr (domain=${dnstt.domain}, resolver=${dnstt.resolver})")
            val client = Dnsttmobile.newClient(dnstt.resolver, dnstt.domain, dnstt.pubKey, dnsttAddr)
            client.setProtectSocket(object : DnsttSocketProtector {
                override fun protect(fd: Long): Boolean = this@OlcboxVpnService.protect(fd.toInt())
            })
            client.setShareProxy(false)
            client.start()
            dnsttClient = client
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) {
                addLog("DNSTT start superseded")
                return false
            }
            if (!awaitSocksPortOpen(dnsttPort, MOBILE_READY_TIMEOUT_MS)) {
                throw IllegalStateException("DNSTT SOCKS port $dnsttPort did not open")
            }
            addLog("DNSTT ready on $dnsttAddr")

            if (!useProxy) {
                // No proxy core in front: the hev bridge talks straight to the dnstt forwarder, which
                // pipes to the dnstt-server's own SOCKS5 end-to-end. That transparent path can't terminate
                // a SOCKS auth handshake, so the bridge must run no-auth.
                socksUsername = ""
                socksPassword = ""
                publishActiveSocks()
                return true
            }
            // Proxy case: the bridge now talks to the Xray/sing-box SOCKS inbound (not the forwarder), so
            // KEEP the per-session credentials — both the bridge and that inbound use them and must match,
            // else the core rejects every connection ("proxy/socks: no matching auth method"). The
            // core→dnstt detour is no-auth (olcrtcChainUser left blank), matching the transparent forwarder.

            // Front the dnstt tunnel with the proxy core: TUN → core (socksListenPort) → proxy →
            // dnstt SOCKS (dnsttPort) → dnstt-server → internet. Reuses the olcRTC-chain dialer wiring
            // (the proxy dials its server through the local dnstt SOCKS). dnstt is TCP-only → block QUIC.
            val traffic = loadTrafficSettings()
            val profilesState = loadRoutingProfilesState()
            val routingProfile = resolveProfileExpandingAsn(profilesState, config.routingProfileId)
            val globalCore = loadAppBehavior().globalProxyCore
            val profileWantsXray = routingProfile != null &&
                (routingProfile.needsGeoFiles() || routingProfile.dnsHosts.isNotEmpty()) &&
                proxy!!.type in XRAY_SUPPORTED_TYPES
            val useXray = dnstt.resolvedProxyCore(proxy, globalCore) == ProxyCore.Xray || profileWantsXray
            addLog("DNSTT chaining proxy ${proxy!!.displayName()} over the tunnel (${if (useXray) "Xray" else "sing-box"})")
            if (useXray) {
                val assetPath = ensureGeoAssetPath(routingProfile)
                val xrayJson = XrayConfig.build(
                    profile = proxy,
                    listenPort = socksListenPort,
                    listenHost = socksListenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    olcrtcChainPort = dnsttPort,
                    logLevel = "debug",
                    traffic = traffic,
                    routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                    blockQuic = true,
                    // Don't force per-connection domain resolution (IPIfNonMatch) over the slow dnstt
                    // tunnel — it stalls all traffic. The bridge's v6 drop keeps ipv4 pinned. See the
                    // matching forceFamilyResolve/allowLocalResolve opt-out on the sing-box path below.
                    forceFamilyResolve = false,
                    // Chain the vless/trojan exit through the dnstt SOCKS at the SOCKET level (dialerProxy),
                    // not proxySettings — otherwise a vless reality/xtls-vision exit loses its transport and
                    // the server resets it ("если vless то connection reset").
                    chainViaDialerProxy = true,
                    // THE reset cause: Xray's default 4s handshake budget is far too short for the multi-hop
                    // handshake over the DNS tunnel (SOCKS5→VPS, VPS→proxy server, then vless/TLS), so Xray
                    // killed every connection mid-handshake. The no-proxy path survives because the hev
                    // bridge waits 10s. Give the chained handshake 30s.
                    handshakeTimeoutSec = 30,
                    // Routing must NOT bypass the dnstt tunnel: a `direct` rule (e.g. Россия напрямую)
                    // exits via the dnstt-server, not the real network. Routing only picks base-exit
                    // (direct) vs second-proxy-exit (proxy); the tunnel itself is never routed around.
                    directViaBase = true,
                )
                activeProxyCore = ProxyCore.Xray
                addLog("Starting Xray (DNSTT proxy) via $socksListenHost:$socksListenPort")
                xrayEngine().start(xrayJson, assetPath)
            } else {
                val json = SingBoxConfig.build(
                    profile = proxy,
                    listenPort = socksListenPort,
                    listenHost = socksListenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    olcrtcChainPort = dnsttPort,
                    autoDetectInterface = true,
                    routing = loadRouting(),
                    traffic = traffic,
                    routingProfile = routingProfile,
                    singboxGeositeBase = profilesState.singboxGeositeBase,
                    singboxGeoipBase = profilesState.singboxGeoipBase,
                    logLevel = "debug",
                    blockQuic = true,
                    // dnstt is the slowest tunnel we have (DNS TXT, tiny MTU). With forceFamilyResolve on
                    // (the default), a strict ipv4_only/ipv6_only strategy makes sing-box add a per-connection
                    // `resolve` action that resolves EVERY destination via the `remote` DNS server — whose
                    // detour is PROXY_TAG, i.e. a DNS query THROUGH the vless proxy THROUGH the dnstt tunnel.
                    // Every connection then blocks on a DNS round-trip over the DNS tunnel and stalls out
                    // ("traffic doesn't flow"). Opt out exactly like the AmneziaWG/VK-TURN constrained
                    // tunnels: domains pass straight to the proxy (resolved server-side on the VPS), and the
                    // ipv4 family is still enforced by the bridge's IPv6 drop — so no per-hop DNS over dnstt
                    // and no v6 leak.
                    forceFamilyResolve = false,
                    // Same reason for the geo/bypass-RU `resolve` action: resolving destinations through
                    // the proxy over the DNS tunnel adds a fatal round-trip per connection. Skip it — over
                    // dnstt all traffic rides the tunnel anyway (direct is censored), so IP-based RU-direct
                    // is moot; domain/geosite rules still work.
                    allowLocalResolve = false,
                    // `direct` traffic exits via the dnstt-server (base tunnel), never the real network —
                    // routing only governs the second proxy, the tunnel itself is never bypassed.
                    directViaBase = true,
                    cacheFilePath = singBoxCachePath(),
                )
                activeProxyCore = ProxyCore.SingBox
                addLog("Starting sing-box (DNSTT proxy) via $socksListenHost:$socksListenPort")
                singBoxEngine().start(json)
            }
            if (!awaitSocksPortOpen(socksListenPort, MOBILE_READY_TIMEOUT_MS)) {
                throw IllegalStateException("DNSTT proxy SOCKS port $socksListenPort did not open")
            }
            coroutineContext.ensureActive()
            if (requestedGeneration != generation) {
                addLog("DNSTT proxy start superseded")
                return false
            }
            addLog("DNSTT proxy ready on $socksListenHost:$socksListenPort")
            publishActiveSocks()
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("DNSTT start canceled")
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            addLog(if (staleRequest) "DNSTT start canceled: $message" else "DNSTT start failed: $message")
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification(ns.notifConnectionFailed)
            }
            false
        } finally {
            releaseProcessBindingUnlessProxy()
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
        // Foolproofing/defense-in-depth: drop a 2nd proxy that points at the SAME node as the main
        // (a proxy-into-itself can't work) — covers a bad import/subscription, not just manual entry.
        val secondProfile = config.proxy2?.takeIf { it.isComplete() }?.let { second ->
            val main = config.proxy
            if (main != null && main.isComplete() && main.isSameNodeAs(second)) {
                addLog("ВНИМАНИЕ: 2-й (каскадный) прокси совпадает с основным — каскад сам в себя невозможен, игнорирую (выход через основной)")
                null
            } else second
        }
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
                // Same multi-room fan-out as Stealth, but fronting the CHAIN port that sing-box/Xray dials
                // its VLESS outbound through — so the proxy is wrapped in an aggregated WebRTC tunnel.
                // enableBond: Stage-2 — the single Chain→VLESS flow is striped across rooms and reassembled
                // server-side (needs the bond reassembler on the olcRTC host) instead of round-robined.
                startOlcrtcSocks(config, deviceIdentityProvider.hwid(), chainPort, enableBond = true)
                markRtcConnected()
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) return false
                addLog("olcRTC chain ready on 127.0.0.1:$chainPort")
            }

            // AmneziaWG: raise a local SOCKS (awgproxy) and route the proxy through it — a full UDP
            // tunnel modeled as a socks outbound → sing-box core. Hysteria2 is a NATIVE sing-box
            // outbound since the 1.13 upgrade (with_quic builds alongside xray now), so it no longer
            // needs the old hysteria2proxy SOCKS bridge — but like AWG it's a full UDP/QUIC tunnel,
            // so keep QUIC unblocked and force the sing-box core (xray has no hysteria2).
            val isAwg = profile.type == ProxyProfile.TYPE_AMNEZIAWG
            val isHy2 = profile.type == ProxyProfile.TYPE_HYSTERIA2
            // Naive (NaïveProxy) is a native sing-box outbound (cronet) — xray has no equivalent.
            val isNaive = profile.type == ProxyProfile.TYPE_NAIVE
            // Trust Tunnel (AdGuard) — like AmneziaWG, raises a local SOCKS5 (its own native client in
            // SOCKS-only mode) that the proxy routes through; a full TCP/UDP tunnel over HTTP2/QUIC.
            val isTrustTunnel = profile.type == ProxyProfile.TYPE_TRUSTTUNNEL
            val effectiveProfile = when {
                isAwg -> prepareAmneziaWgProxy(profile)
                isTrustTunnel -> prepareTrustTunnelProxy(profile)
                else -> profile
            }
            val isLocalUdpTunnel = isAwg || isTrustTunnel

            // Happ-style routing profile (per-location override → global default), if any.
            val profilesState = loadRoutingProfilesState()
            val routingProfile = resolveProfileExpandingAsn(profilesState, config.routingProfileId)
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
            activeProxyCore =
                if (isLocalUdpTunnel || isHy2 || isNaive) ProxyCore.SingBox else config.resolvedCore(globalCore)
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
            // Cascade diagnostics — make the "second proxy is ignored / exits via the 1st" symptom
            // visible instead of silent: say whether the cascade is active, dropped as incomplete, or
            // unusable because the main is a verbatim Xray config (prepareRaw can't chain a 2nd hop).
            // Standard Xray protocols can be chained over a verbatim config (prepareRaw dialerProxy);
            // AmneziaWG/WireGuard/Hysteria2 can't (they're client tunnels, not Xray exit outbounds).
            val secondChainableOnRaw = secondProfile?.type in setOf(
                ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS,
                ProxyProfile.TYPE_TROJAN, ProxyProfile.TYPE_SHADOWSOCKS
            )
            when {
                secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() && !secondChainableOnRaw ->
                    addLog("ВНИМАНИЕ: 2-й прокси типа '${secondProfile.type}' (например AmneziaWG/WireGuard/Hysteria2) — это клиентский ТУННЕЛЬ, а не выходной Xray-outbound, поэтому НЕ может быть каскадом поверх кастомного Xray-конфига и игнорируется. Для каскада поверх xhttp используйте vless/vmess/trojan/ss.")
                secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() ->
                    addLog("Каскад: выход через 2-й прокси '${secondProfile.displayName()}' поверх кастомного Xray-конфига")
                secondProfile != null ->
                    addLog("Каскад: выход через 2-й прокси '${secondProfile.displayName()}' поверх основного '${effectiveProfile.displayName()}'")
                config.proxy2 != null ->
                    addLog("Каскад: 2-й прокси ЗАДАН, но ссылка неполная/не распозналась — он отброшен, выход через 1-й прокси")
            }
            if (activeProxyCore == ProxyCore.Xray) {
                val rawXray = effectiveProfile.rawXrayConfig
                var assetPath = ""
                val json = if (!rawXray.isNullOrBlank()) {
                    // User-supplied full Xray config (e.g. a Happ/Remnawave JSON subscription): run it
                    // VERBATIM and HONOR ITS OWN routing — the config's geosite:/geoip:/domain: rules take
                    // PRECEDENCE over the app's routing profile (prepareRaw only merges the profile into
                    // configs that ship NO routing of their own). To honor geosite:/geoip: selectors xray
                    // needs the geo .dat, so when the config references them we download geoip.dat/
                    // geosite.dat (from the profile's sources or the global runetfreedom defaults, which DO
                    // carry ru-available-only-inside / category-ads-all) and hand xray the asset dir. Only
                    // if that download genuinely fails (e.g. a blocked network with no db cached yet) do we
                    // fall back to stripping the geo selectors so the config still LOADS (degraded routing)
                    // instead of failing with "open .../geosite.dat: no such file". Non-geo rules always stay.
                    val rawNeedsGeo = rawXray.contains("geosite:") || rawXray.contains("geoip:")
                    assetPath = when {
                        rawNeedsGeo -> ensureRawConfigGeoAssetPath(routingProfile)
                        routingProfile != null -> ensureGeoAssetPath(routingProfile)
                        else -> ""
                    }
                    // Honor the config's own geo routing when the db is available; strip ONLY as a
                    // last-resort fallback when it couldn't be fetched (so the config still loads).
                    val stripGeo = rawNeedsGeo && assetPath.isEmpty()
                    addLog(
                        "Starting Xray with custom config (embedded routing honored" +
                            (if (rawNeedsGeo && assetPath.isNotEmpty()) ", geo db loaded for its geosite:/geoip:" else "") +
                            (if (stripGeo) ", geo db unavailable → geo selectors stripped" else "") +
                            (if (routingProfile != null) " + routing profile '${routingProfile.displayName()}'" else "") +
                            ")"
                    )
                    XrayConfig.prepareRaw(
                        rawConfigJson = rawXray,
                        listenPort = socksListenPort,
                        listenHost = socksListenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                        fakeDnsEnabled = loadTrafficSettings().fakeDnsEnabled,
                        stripGeoSelectors = stripGeo,
                        // ipv4_only/prefer_ipv4 → force the verbatim config's direct freedom + DNS to IPv4
                        // so direct .ru sites (e.g. 2ip.ru) can't leak real IPv6 past the bridge.
                        forceIpv4 = loadTrafficSettings().domainStrategy.let {
                            it == "ipv4_only" || it == "prefer_ipv4"
                        },
                        // Chain a standard second proxy over the verbatim config (xhttp main + vless/etc.
                        // second). Non-chainable seconds (AmneziaWG…) are ignored by prepareRaw.
                        secondProfile = secondProfile,
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
                        bypassLan = loadRouting(expandAsn = false).bypassLan,
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
                if (isHy2) addLog("Hysteria2 outbound: native sing-box (QUIC allowed)")
                if (isNaive) addLog("Naive outbound: native sing-box (cronet${if (effectiveProfile.naiveQuic) ", QUIC" else ""})")
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
                    // A naive+quic server needs its own QUIC uplink unblocked too.
                    blockQuic = !(isLocalUdpTunnel || isHy2 || (isNaive && effectiveProfile.naiveQuic)),
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
                    // Transparent-proxy inbound (root-only) — only emitted in Tproxy mode.
                    tproxyPort = tproxyPortOrNull,
                    // Standalone AmneziaWG (full UDP tunnel behind a local SOCKS): resolve the tunnel's
                    // DNS over TCP (SOCKS CONNECT) instead of the flaky SOCKS UDP-ASSOCIATE, so name
                    // resolution works WITHOUT needing a 2nd proxy to carry DNS. No-op with a 2nd proxy
                    // (that proxy's own protocol carries DNS) or for DoH/DoT resolvers.
                    preferTcpRemoteDns = isLocalUdpTunnel && secondProfile == null,
                    // Persist the fakeip table so a reconnect can't remap a synthetic IP onto another
                    // domain (stale app DNS caches → "untrusted SSL certificate" in OkHttp-based apps).
                    cacheFilePath = singBoxCachePath(),
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
            releaseProcessBindingUnlessProxy()
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
        // [profile] is the exit outbound. For WDTT it is SYNTHESIZED at runtime from the wdtt-server's
        // WireGuard config (GETCONF/OnConfig) — the user enters only the server IP[:port], no WG keys.
        var profile = config.proxy
        val usesWdtt = vk?.usesWdtt() == true
        // WDTT always exits via WireGuard (server-provided); the freeturn outbound choice is irrelevant.
        val outboundType = if (usesWdtt) VkTurnConfig.OUTBOUND_WIREGUARD
            else vk?.outbound?.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD } ?: VkTurnConfig.OUTBOUND_WIREGUARD
        val outboundConfigured = when {
            usesWdtt -> vk?.wdttPeer?.isNotBlank() == true // WG config comes from the server
            outboundType == VkTurnConfig.OUTBOUND_AMNEZIAWG -> !profile?.awgConfig.isNullOrBlank()
            outboundType == VkTurnConfig.OUTBOUND_PROXY -> profile != null &&
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

            // 1. The transport core that raises the local listener the WG/proxy outbound dials.
            val listenAddr = "127.0.0.1:${vk.listenPort}"

            // Multi-server freeturn: front several freeturn servers AT ONCE and load-balance traffic
            // across them per connection (Xray balancer over N WireGuard outbounds) so their bandwidth
            // aggregates. Only for the freeturn core with a plain WireGuard exit and no chain proxy;
            // a single server (or any other shape) keeps the exact original single-server path.
            val freeturnServers = if (!vk.usesWdtt() &&
                outboundType == VkTurnConfig.OUTBOUND_WIREGUARD &&
                vk.chainProxyLink.isBlank()
            ) vk.allFreeturnUris().take(1 + VKTURN_MAX_EXTRA_FREETURN) else emptyList()
            // One WireGuard ProxyProfile per server, each dialing its OWN local freeturn listener:
            // the primary uses [profile] (listener vk.listenPort); extras get vk.listenPort+i and their
            // WG keys are pulled from each link's embedded wg=. Empty unless we actually go multi.
            val freeturnWgProfiles: List<ProxyProfile> = if (freeturnServers.size > 1 && profile != null) buildList {
                add(profile!!)
                freeturnServers.drop(1).forEachIndexed { idx, u ->
                    VkTurnComposer.freeturnUriToWgProfile(u, vk.listenPort + idx + 1, "VK-TURN ${idx + 2}")
                        ?.let { add(it) }
                }
            } else emptyList()
            // Need at least 2 working WG outbounds (else there's nothing to balance) to engage multi.
            val multiFreeturn = freeturnWgProfiles.count { !it.rawOutbound.isNullOrBlank() } > 1

            if (vk.usesWdtt()) {
                // WDTT core (wg-turn-client): connects purely by the wdtt-server IP[:port] and FETCHES its
                // WireGuard config from the server (GETCONF/OnConfig) — the user enters no WG keys. We bring
                // WireGuard up from that returned config (Endpoint overridden to the local WDTT listener).
                val peerAddr = vk.wdttPeerAddr()
                val configSignal = CompletableDeferred<String>()
                addLog(
                    "Starting VK-TURN WDTT core on $listenAddr (peer=$peerAddr, " +
                        "workers=${vk.wdttWorkers.takeIf { it > 0 }?.toString() ?: "auto"})"
                )
                Wdttmobile.start(
                    peerAddr,
                    vk.vkLink,
                    vk.wdttPassword,
                    listenAddr,
                    vk.wdttWorkers.toLong(),
                    deviceIdentityProvider.hwid(),
                    vk.wdttFingerprint.ifBlank { "chrome" },
                    "",
                    object : WdttConfigSink {
                        override fun onConfig(wgConf: String) {
                            addLog("vkturn(wdtt): server WG config received (${wgConf.length} chars)")
                            if (!configSignal.isCompleted) configSignal.complete(wgConf)
                        }
                    },
                )
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) return false
                // The WireGuard config arrives via OnConfig once the first worker has a VK TURN session up,
                // so waiting on it doubles as the relay-ready gate. Build the WG outbound from it.
                val wgConf = withTimeoutOrNull(VKTURN_RELAY_READY_TIMEOUT_MS) { configSignal.await() }
                when {
                    wgConf != null && wgConf.isNotBlank() -> {
                        profile = buildWdttWgProfile(wgConf, vk.listenPort)
                        addLog("VK-TURN WDTT relay up; WireGuard config from server applied")
                    }
                    profile?.rawOutbound?.isNotBlank() == true ->
                        addLog("VK-TURN WDTT: no GETCONF — falling back to the stored WireGuard config")
                    else -> throw IllegalStateException(
                        "WDTT: no WireGuard config from server (GETCONF) and none stored"
                    )
                }
            } else {
                // freeturn client: local WireGuard entry listener tunnelling through VK.
                Freeturn.setDebug(false)
                Freeturn.setLogWriter(object : FreeturnLogWriter {
                    override fun writeLog(line: String) {
                        val trimmed = line.trimEnd()
                        addLog("vkturn: $trimmed")
                        Log.v("vkturn", trimmed)
                    }
                })
                // VK auth may hit a Smart Captcha the auto-solver can't pass (status=BOT). Without a
                // presenter freeturn falls back to "open localhost:8765 in a browser", which nothing on
                // Android ever does — the relay then never allocates and the tunnel black-holes. Route
                // the captcha page into the app instead: a WebView dialog + a heads-up notification.
                Freeturn.setCaptchaPresenter(object : FreeturnCaptchaPresenter {
                    override fun show(url: String) {
                        addLog("VK-TURN: manual captcha required — opening $url in-app")
                        OlcboxVpnState.setVkCaptchaUrl(url)
                        notifyVkCaptcha(url)
                    }

                    override fun hide() {
                        OlcboxVpnState.setVkCaptchaUrl(null)
                        cancelVkCaptchaNotification()
                    }
                })
                // bond=1 в старых ссылках ядро 3.2.0 просто игнорирует (раньше оно падало на нём в
                // udp-режиме, поэтому поле вырезалось здесь), так что URI уходит как есть.
                val freeturnUri = vk.uri
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
                if (multiFreeturn) {
                    // One relay per server, each on its own local listener (vk.listenPort + index). The
                    // VK call links are PARTITIONED across the servers (round-robin) — NOT shared in full
                    // to every relay — so each VPS handles a distinct share of the calls instead of all
                    // N relays hammering the same calls (which only multiplied phone CPU/obf and split
                    // each call N ways → no speed gain). Per-server streams scale with that server's own
                    // share of links. freeturn's StartMulti runs them together; Stop() cancels all.
                    val allLinks = vk.vkLink.split('\n', '\r', '\t', ' ', ',').filter { it.isNotBlank() }
                    val n = freeturnServers.size
                    val specsJson = Json.encodeToString(buildJsonArray {
                        freeturnServers.forEachIndexed { idx, u ->
                            // This server's share of the call links; if fewer links than servers, wrap
                            // so every relay still gets at least one call.
                            val myLinks = allLinks.filterIndexed { li, _ -> li % n == idx }
                                .ifEmpty { listOfNotNull(allLinks.getOrNull(if (allLinks.isEmpty()) -1 else idx % allLinks.size)) }
                            val myCount = myLinks.size.coerceAtLeast(1)
                            val myStreams = (VKTURN_STREAMS_BASE + (myCount - 1) * VKTURN_STREAMS_PER_EXTRA_CALL)
                                .coerceAtMost(VKTURN_STREAMS_AUTO_MAX)
                                .let { maxOf(vk.streams.takeIf { s -> s > 0 } ?: 0, it) }
                                .coerceAtMost(VKTURN_STREAMS_HARD_MAX)
                            addJsonObject {
                                put("uri", u)
                                put("listenAddr", "127.0.0.1:${vk.listenPort + idx}")
                                put("vkLink", myLinks.joinToString("\n"))
                                put("streams", myStreams)
                            }
                        }
                    })
                    addLog("Starting VK-TURN freeturn ×$n servers (balanced, ${allLinks.size} VK link(s) split across them, base ${vk.listenPort})")
                    if (allLinks.size < n) addLog("VK-TURN: fewer VK links (${allLinks.size}) than servers ($n) — add more VK call links for real speed scaling")
                    Freeturn.startMulti(specsJson)
                } else {
                    addLog("Starting VK-TURN freeturn listener on $listenAddr (links=$linkCount, streams=$effectiveStreams)")
                    Freeturn.start(freeturnUri, listenAddr, vk.vkLink, effectiveStreams.toLong())
                }
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
            val profilesState = loadRoutingProfilesState()

            // Multi-server freeturn: build ONE Xray config that load-balances across the per-server
            // WireGuard outbounds (each dialing its own local relay listener). Per-connection `random`
            // balancing spreads traffic so the servers' bandwidth aggregates. No proxy/chain here, so
            // the Standard cascade path is untouched.
            if (multiFreeturn) {
                val balancedProfiles = freeturnWgProfiles.filter { !it.rawOutbound.isNullOrBlank() }
                val xrayJson = XrayConfig.buildWireguardBalancer(
                    wgProfiles = balancedProfiles,
                    listenPort = socksListenPort,
                    listenHost = socksListenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    logLevel = "debug",
                    traffic = ipv4Traffic,
                    bypassLan = routing.bypassLan,
                )
                activeProxyCore = ProxyCore.Xray
                activeFreeturnServers = balancedProfiles.size
                addLog("Starting Xray (VK-TURN freeturn ×${balancedProfiles.size}, load-balanced) via $listenAddr")
                xrayEngine().start(xrayJson, "")
                if (!awaitSocksPortOpen(socksListenPort, MOBILE_READY_TIMEOUT_MS)) {
                    throw IllegalStateException("Xray SOCKS port $socksListenPort did not open")
                }
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) {
                    addLog("VK-TURN multi-freeturn Xray start superseded")
                    return false
                }
                addLog("Xray ready on $socksListenHost:$socksListenPort (${balancedProfiles.size} servers)")
                publishActiveSocks()
                return true
            }

            // Chained proxy on top of WireGuard (parsed once; reused by both cores). Applies to plain
            // WireGuard AND to WDTT (which also exits via WireGuard) — a vless/trojan/ss link dialled
            // THROUGH the WG-over-VK tunnel so the public exit is the proxy, not the VK/WDTT server.
            val chainProxy = if (outboundType == VkTurnConfig.OUTBOUND_WIREGUARD) {
                val raw = vk.chainProxyLink.takeIf { it.isNotBlank() }
                // Accept a normal share link (vless/vmess/trojan/ss) OR a yptun://inbound link (a whole
                // shared LocationConfig) — pulling its EXIT proxy (proxy2 ?: proxy). Previously only
                // ShareLinkParser was tried, so a yptun:// chain proxy returned null and we silently
                // exited via plain WireGuard ("2 прокси у VK-TURN мимо летит"). Mirrors the dnstt path
                // and the Standard "additional proxy" field (proxyFromAnyLink).
                val parsed = raw?.let { link ->
                    (ShareLinkParser.parse(link)
                        ?: YptunInboundCodec.parse(link)?.let { it.proxy2 ?: it.proxy })
                        ?.takeIf { it.isComplete() }
                }
                // A link is present but didn't parse → we'd silently exit via plain WG (looks like the
                // proxy is "ignored"). Make that loud so it's diagnosable instead of a silent bypass.
                if (raw != null && parsed == null) {
                    addLog("VK-TURN: ссылка прокси задана, но не распозналась — выход через чистый WireGuard (без прокси). Нужна vless/vmess/trojan/ss или yptun://inbound.")
                }
                parsed
            } else null

            // Routing profiles apply ONLY where there's a real proxy exit to split traffic on: the
            // proxy EXIT (outbound=Proxy) OR a chain/second proxy over WireGuard. Then the core listens
            // on a local SOCKS and the profile can split (proxy bucket over VK, direct bucket straight
            // out). Plain WireGuard / AmneziaWG / WDTT (no proxy) stay EXCLUDED (like olcRTC): they
            // tunnel everything through the WG-over-VK path with nothing to route against.
            val routingProfile: RoutingProfile? =
                if (outboundType == VkTurnConfig.OUTBOUND_PROXY || chainProxy != null)
                    resolveProfileExpandingAsn(profilesState, config.routingProfileId)
                else null

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
                        bypassLan = routing.bypassLan,
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
                        // Routing must NOT bypass the VK tunnel: a `direct` rule exits via the WG-over-VK
                        // base, not the real network. Routing only picks VK-exit (direct) vs chain-proxy.
                        directViaBase = true,
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
                        cacheFilePath = singBoxCachePath(),
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
                        cacheFilePath = singBoxCachePath(),
                    )
                }

                else -> { // WireGuard (incl. WDTT), optionally with a proxy chained on top
                    // Reuse the proxy parsed above (single source of truth for both cores).
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
                            // `direct` traffic exits via the WG-over-VK base, never the real network —
                            // routing only governs the chain proxy; the VK tunnel is never bypassed.
                            directViaBase = true,
                            cacheFilePath = singBoxCachePath(),
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
                            cacheFilePath = singBoxCachePath(),
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
            releaseProcessBindingUnlessProxy()
        }
    }

    /**
     * Builds a sing-box WireGuard outbound [ProxyProfile] from a wg-quick config string the wdtt-server
     * returns (GETCONF). The peer Endpoint is overridden to the local WDTT listener (127.0.0.1:listenPort)
     * — WG dials the WDTT core, which tunnels to the real WG server over VK.
     */
    private fun buildWdttWgProfile(wgConf: String, listenPort: Int): ProxyProfile {
        var priv = ""
        var pub = ""
        var addr = ""
        var mtu = 0
        for (raw in wgConf.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim()
            when (k) {
                "privatekey" -> priv = v
                "publickey" -> pub = v
                "address" -> if (addr.isEmpty()) addr = v.substringBefore(',').trim()
                "mtu" -> mtu = v.toIntOrNull() ?: 0
            }
        }
        val localAddr = if (addr.isNotEmpty()) "\"$addr\"" else ""
        // Clamp the WireGuard MTU to a value that survives the VK TURN + DTLS + RTP-obf path. The
        // wdtt-server hands out MTU=1280, but through that wrapping the real path MTU is well under
        // 1500 and 1280 black-holes large packets (uploads / TLS handshakes stall) — the same lesson
        // the freeturn WG default (1200) encodes. With a proxy chained ON TOP, its header eats more
        // headroom still, so cap at 1200 (honour a smaller server value if it sends one).
        val effMtu = (if (mtu > 0) mtu else 1200).coerceAtMost(1200)
        val json = buildString {
            append("{")
            append("\"type\":\"wireguard\",")
            append("\"server\":\"127.0.0.1\",")
            append("\"server_port\":$listenPort,")
            append("\"local_address\":[$localAddr],")
            append("\"private_key\":\"$priv\",")
            append("\"peer_public_key\":\"$pub\",")
            append("\"mtu\":$effMtu")
            append("}")
        }
        return ProxyProfile(
            tag = "WDTT",
            type = "wireguard",
            server = "127.0.0.1",
            serverPort = listenPort,
            rawOutbound = json,
        )
    }

    /** sing-box's work dir (libbox basePath); also holds its fakeip cache db. */
    private fun singBoxWorkDir(): File = File(filesDir, "singbox")

    /**
     * Path of the sing-box fakeip cache (see [SingBoxConfig.build]'s `cacheFilePath`). Keeping the
     * domain↔synthetic-IP table across restarts is what stops an app's stale DNS cache from dialing
     * a fake IP that now belongs to another domain ("untrusted SSL certificate").
     */
    private fun singBoxCachePath(): String = File(singBoxWorkDir(), "cache.db").absolutePath

    private fun singBoxEngine(): SingBoxEngine {
        return singBox ?: SingBoxEngine(
            context = applicationContext,
            workDir = singBoxWorkDir(),
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

    /** olcRTC is alive in single-room (singleton) OR multi-room (>=1 independent room up) mode. */
    private fun olcrtcRunning(): Boolean =
        mobileRuntime.isRunning() || (olcrtcRoomManager != null && Mobile.roomsRunning() > 0)

    /** True when the active engine's core(s) are alive. */
    private fun coreRunning(): Boolean = when (engineType) {
        EngineType.Stealth -> olcrtcRunning()
        EngineType.Standard -> proxyCoreRunning()
        EngineType.Chain -> olcrtcRunning() && proxyCoreRunning()
        // VK-TURN runs either the freeturn OR the WDTT transport core (mutually exclusive per location).
        EngineType.VkTurn -> (Freeturn.isRunning() || Wdttmobile.isRunning()) && proxyCoreRunning()
        // dnstt raises its own local SOCKS listener; with a proxy-over-dnstt a proxy core fronts it.
        EngineType.Dnstt -> dnsttClient?.isRunning == true && (!dnsttProxyActive || proxyCoreRunning())
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
        var deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!Freeturn.isRunning()) return false
            if (Freeturn.connectedStreams() > 0) return true
            // A manual captcha is on screen: the relay CANNOT come up until the user solves it, so
            // a fixed timeout would elapse mid-solve and start WireGuard against a dead listener.
            // Keep pushing the deadline while the captcha is pending (freeturn itself gives up after
            // 3 minutes, dropping captchaActive, so this can't wait forever).
            if (Freeturn.captchaActive()) deadline = System.currentTimeMillis() + timeoutMs
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
        mobileRuntime.setTransport(config.transport)
        mobileRuntime.setDNS(resolveOlcRtcDnsServer())
        mobileRuntime.setSocksListenHost(socksListenHost)
        mobileRuntime.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    /**
     * Picks the DNS server olcRTC uses to resolve provider hostnames (Jitsi/Telemost) before the tunnel
     * is up: the ACTIVE upstream network's own configured resolver first (fast, LAN-local, and correctly
     * reflects whatever the user's router/AdGuard Home hands out over DHCP) — falling back to our
     * preference-ordered public list (Runtime.setDNS probes it and sticks to the first that answers) only
     * when the network doesn't advertise one. That fallback exists for RU IPv4-only mobile networks where
     * Cloudflare/Google UDP/53 are frequently blocked; it would previously leave olcRTC unable to resolve.
     */
    private fun resolveOlcRtcDnsServer(): String {
        val upstreamDnsServer = currentNetwork
            ?.let(connectivityManager::getLinkProperties)
            ?.dnsServers
            ?.asSequence()
            ?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            ?.sortedBy { it.address.size }
            ?.mapNotNull { it.hostAddress }
            ?.map(::dnsEndpoint)
            ?.firstOrNull()

        // Keep the fallbacks appended instead of replaced: olcrtc takes a LIST and walks it
        // in order (protect.splitDNSServers/pickReachableDNS, which really resolves a probe
        // name through each candidate). A carrier resolver that answers but answers wrong
        // then fails the probe and signaling moves on, instead of being stranded on it.
        val selectedDnsServer = upstreamDnsServer
            ?.let { "$it,$FALLBACK_OLCRTC_DNS_SERVERS" }
            ?: FALLBACK_OLCRTC_DNS_SERVERS
        val source = if (upstreamDnsServer != null) "upstream+fallback" else "fallback"
        addLog("Using $source DNS server $selectedDnsServer for olcRTC signaling")
        return selectedDnsServer
    }

    private fun dnsEndpoint(address: String): String {
        return if (':' in address) "[$address]:53" else "$address:53"
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
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)
            // IPv4 capture. With "Обход LAN" on, route everything EXCEPT the private/LAN ranges into
            // the tunnel (so local-network traffic exits on the real interface — works for every engine,
            // including the ones whose core can't route "direct": olcRTC/VK-TURN/dnstt). The mapped-DNS
            // pool 100.64.0.0/10 is deliberately NOT excluded so synthetic DNS IPs still enter the tun.
            // Off → capture all of 0.0.0.0/0 exactly as before.
            if (activeBypassLan) {
                ipv4RoutesExcludingLan().forEach { (addr, prefix) -> builder.addRoute(addr, prefix) }
            } else {
                builder.addRoute("0.0.0.0", 0)
            }
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

    /**
     * The minimal set of IPv4 routes that cover all of 0.0.0.0/0 EXCEPT the private/LAN ranges, so
     * those addresses are left on the real network (the "Обход LAN" behaviour) for every engine. Built
     * by subtracting the excluded CIDRs from the full space and re-packing each remaining gap into
     * aligned CIDR blocks. Loopback (127/8) and link-local (169.254/16) are excluded alongside the
     * RFC-1918 ranges, matching the per-core direct rules. Works on all API levels (no excludeRoute).
     */
    private fun ipv4RoutesExcludingLan(): List<Pair<String, Int>> {
        val excluded = listOf(
            cidrRange("10.0.0.0", 8),
            cidrRange("127.0.0.0", 8),
            cidrRange("169.254.0.0", 16),
            cidrRange("172.16.0.0", 12),
            cidrRange("192.168.0.0", 16)
        ).sortedBy { it.first }

        val routes = mutableListOf<Pair<String, Int>>()
        var cursor = 0L
        val max = 0xFFFFFFFFL
        for ((start, end) in excluded) {
            if (start > cursor) appendCidrs(cursor, start - 1, routes)
            if (end + 1 > cursor) cursor = end + 1
        }
        if (cursor <= max) appendCidrs(cursor, max, routes)
        return routes
    }

    /** [base, base+2^(32-prefix)-1] as an inclusive unsigned-32-bit range. */
    private fun cidrRange(addr: String, prefix: Int): Pair<Long, Long> {
        val base = ipv4ToLong(addr)
        val size = 1L shl (32 - prefix)
        return base to (base + size - 1)
    }

    private fun ipv4ToLong(addr: String): Long {
        val p = addr.split(".")
        return ((p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()) and 0xFFFFFFFFL
    }

    private fun longToIpv4(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    /** Packs the inclusive range [start, end] into the fewest aligned CIDR blocks. */
    private fun appendCidrs(start: Long, end: Long, out: MutableList<Pair<String, Int>>) {
        var cur = start
        while (cur <= end) {
            // Largest block aligned at `cur` (its lowest set bit) that still fits the remaining span.
            var size = if (cur == 0L) (1L shl 32) else cur.takeLowestOneBit()
            val remaining = end - cur + 1
            while (size > remaining) size = size shr 1
            out.add(longToIpv4(cur) to (32 - size.countTrailingZeroBits()))
            cur += size
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
              udp: '${if (engineType == EngineType.Stealth || engineType == EngineType.Dnstt) "tcp" else "udp"}'
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
              connect-timeout: ${if (engineType == EngineType.Dnstt) 30000 else 10000}
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
        // Nothing to display → don't spin a 2s wake-up loop reading byte counters for no reason. Both
        // flags default OFF, so by default this avoids a permanent every-2s wake-up while connected.
        // The settings observer restarts this updater the moment either flag is toggled on.
        if (!showSpeedInNotif && !showRoomsInNotif && !showSpeedOnHome && !hasStatusWidgets()) return
        // Energy-saver: tick less often (fewer wake-ups + JNI reads + notification reposts) at the cost
        // of a slightly laggier speed/rooms readout.
        val interval = if (activeEnergySaver) ENERGY_SAVER_SPEED_INTERVAL_MS else SPEED_INTERVAL_MS
        speedJob = scope.launch {
            var prev: Tun2SocksStats? = null
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                val cur = readTun2SocksStats()
                // Re-evaluate the base text each tick when showing the rooms count so "up/total" updates
                // live as rooms (re)connect; otherwise reuse the last status (cheap).
                val base = if (showRoomsInNotif) connectedNotificationText()
                else lastNotificationStatus.ifBlank { ns.notifConnected }
                if (cur != null && prev != null) {
                    val secs = (interval / 1000.0).coerceAtLeast(0.5)
                    val down = ((cur.rxBytes - prev.rxBytes).coerceAtLeast(0L) / secs).toLong()
                    val up = ((cur.txBytes - prev.txBytes).coerceAtLeast(0L) / secs).toLong()
                    // Publish to state for the optional Home speed line / status widget regardless of
                    // the notif toggle.
                    val statusWidgets = hasStatusWidgets()
                    if (showSpeedOnHome || statusWidgets) OlcboxVpnState.setSpeed(down, up)
                    if (showSpeedInNotif) {
                        updateNotification(base, speedLine(down, up))
                    } else if (showRoomsInNotif) {
                        updateNotification(base)
                    }
                    // The status-change observer doesn't fire while merely the speed changes, so push
                    // the live rate to the status widget on every tick it's present.
                    if (statusWidgets) refreshWidgets()
                } else if (showRoomsInNotif) {
                    updateNotification(base)
                }
                prev = cur
                delay(interval)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        startSpeedUpdater()
        val watchdogInterval = if (activeEnergySaver) {
            AppBehaviorSettings.ENERGY_SAVER_WATCHDOG_INTERVAL_MS
        } else {
            WATCHDOG_INTERVAL_MS
        }
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(watchdogInterval)

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

                    mode.isTunless && !isLocalSocksPortOpen(socksListenPort) -> {
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
                    // Proxy (SOCKS) mode has no TUN, so the cores reach the internet only through the
                    // process→network binding. Re-pin it to the refreshed upstream; otherwise a benign
                    // Wi-Fi handle swap leaves the cores bound to a dead Network and traffic stalls.
                    if (connectionMode.isTunless && coreRunning()) {
                        bindProcessToNetwork(upstream)
                    }
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
        stopHttpProxyBridge()
        autoConnectJob?.cancel()
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
            // connectHost maps a 0.0.0.0 listen (Proxy mode) back to 127.0.0.1 so the in-process ping dials a real address.
            host = socksConnectHost(),
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
        // Drop any pending manual-captcha UI: the session it belonged to is gone.
        OlcboxVpnState.setVkCaptchaUrl(null)
        cancelVkCaptchaNotification()
        runCatching { Wdttmobile.stop() }
        runCatching { dnsttClient?.stop() }
        dnsttClient = null
        dnsttProxyActive = false
        runCatching { Awg.stop() }
        runCatching { trustTunnelClient?.stop() }
        runCatching { trustTunnelClient?.close() }
        trustTunnelClient = null
        val provider = lastMobileProvider
        val wasRunning = mobileRuntime.isRunning()
        // stop(0) means Runtime's own 5 s default; on timeout it stays "stopping" and every
        // later start returns ErrAlreadyRunning. Wait as long as a reconnect already does.
        runCatching { mobileRuntime.stop(PREVIOUS_STOP_WAIT_MS) }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
    }

    /** olcRTC's local SOCKS port when chaining; sing-box dials its outbound through it. */
    private val chainOlcrtcPort: Int get() = socksListenPort + 1

    /** AmneziaWG's local SOCKS port (awgproxy) when a proxy uses the AmneziaWG transport. */
    private val awgLocalPort: Int get() = socksListenPort + 2

    /** Trust Tunnel's local SOCKS port (native VpnClient, SOCKS-only mode) for TYPE_TRUSTTUNNEL. */
    private val trustTunnelLocalPort: Int get() = socksListenPort + 5

    /** Transparent-proxy (tproxy) listen port; offset from the SOCKS port to avoid collisions. */
    private val tproxyPort: Int get() = socksListenPort + 4

    /** The tproxy inbound port to emit in the sing-box config — only in Tproxy mode, else null. */
    private val tproxyPortOrNull: Int?
        get() = if (connectionMode == AndroidConnectionMode.Tproxy) tproxyPort else null

    /**
     * If [profile] is AmneziaWG, raise the awgproxy SOCKS5 from its config and return a SOCKS proxy
     * pointing at it, so sing-box (standalone or chained) routes through the AmneziaWG tunnel.
     * Otherwise returns [profile] unchanged.
     */
    private suspend fun prepareAmneziaWgProxy(profile: ProxyProfile): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_AMNEZIAWG) return profile
        runCatching { Awg.stop() }
        // Verbose AmneziaWG logging: routes the amneziawg-go device journal (handshake init/response,
        // "handshake did not complete", peer errors) into the in-app log sheet via setLogWriter below.
        // Standalone AWG "cannot connect" is otherwise invisible — with setDebug(false) the device only
        // emits errors, so a stalled handshake (tunnel up, no data) logs nothing. AWG is a low-volume
        // control path (handshakes aren't per-packet), so this doesn't spam the journal during transfer.
        Awg.setDebug(true)
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

    /**
     * If [profile] is Trust Tunnel, decode its `tt://` deep-link into a `[endpoint]` TOML (native
     * DeepLink.decode), start the AdGuard client in SOCKS-only mode (VpnClient.start(null) — no TUN),
     * and return a SOCKS proxy pointing at its local listener, so sing-box routes through the Trust
     * Tunnel like it does through AmneziaWG. Otherwise returns [profile] unchanged.
     */
    private suspend fun prepareTrustTunnelProxy(profile: ProxyProfile): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_TRUSTTUNNEL) return profile
        runCatching { trustTunnelClient?.stop(); trustTunnelClient?.close() }
        trustTunnelClient = null

        val endpointToml = runCatching { TrustTunnelDeepLink.decode(profile.ttConfig) }
            .getOrElse { throw IllegalStateException("Trust Tunnel: invalid tt:// link: ${it.message}") }
        val port = trustTunnelLocalPort
        // Top-level keys must precede any [table]; then the decoded [endpoint], then our SOCKS listener.
        // No [listener.tun] → SOCKS-only. Kill switch off: our VpnService TUN owns routing, not the client.
        val configToml = buildString {
            append("loglevel = \"info\"\n")
            append("killswitch_enabled = false\n\n")
            append(endpointToml.trim()).append("\n\n")
            append("[listener.socks]\n")
            append("address = \"127.0.0.1:").append(port).append("\"\n")
        }

        val listener = object : TrustTunnelListener {
            override fun protectSocket(socket: Int): Boolean {
                if (connectionMode.isTunless) return true
                return this@OlcboxVpnService.protect(socket)
            }
            // The endpoint certificate is embedded in the tt:// blob and pinned by the native client;
            // this callback is the app-side hook — accept (the user configured this exact endpoint).
            override fun verifyCertificate(certificate: ByteArray?, rawChain: List<ByteArray?>?): Boolean = true
            override fun onStateChanged(state: Int) { addLog("trusttunnel: state=$state") }
            override fun onConnectionInfo(info: String) { addLog("trusttunnel: $info") }
        }

        addLog("Starting Trust Tunnel SOCKS on 127.0.0.1:$port")
        val client = TrustTunnelVpnClient(configToml, listener)
        trustTunnelClient = client
        if (!client.start(null)) {
            trustTunnelClient = null
            throw IllegalStateException("Trust Tunnel client failed to start")
        }
        if (!awaitSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Trust Tunnel SOCKS port $port did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$port,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "Trust Tunnel" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = port,
            rawOutbound = raw,
        )
    }

    private suspend fun loadRouting(expandAsn: Boolean = true): RoutingRules {
        val raw = runCatching {
            applicationContext.vpnPrefDataStore.data.first()[KEY_ANDROID_ROUTING]
        }.getOrNull() ?: return RoutingRules()
        val routing = runCatching { Json.decodeFromString(RoutingRules.serializer(), raw) }
            .getOrDefault(RoutingRules())
        // sing-box has no native package-regex matcher: expand each rule's regex against the
        // device's installed packages into concrete `package_name` entries before building.
        val withPackages = if (routing.rules.any { it.packageRegex.isNotEmpty() }) {
            val installed = runCatching {
                packageManager.getInstalledPackages(0).map { it.packageName }
            }.getOrDefault(emptyList())
            routing.copy(rules = SingBoxRule.expandPackageRegex(routing.rules, installed))
        } else {
            routing
        }
        // Likewise neither core matches ASN natively: resolve each rule's `asn:` selectors to CIDRs
        // (cached per ASN) before building. Skipped for the bypassLan-only callers (expandAsn=false)
        // so reading one flag never triggers a network fetch. Graceful: an unresolved ASN is dropped.
        if (!expandAsn) return withPackages
        val asns = SingBoxRule.collectAsns(withPackages.rules)
        if (asns.isEmpty()) return withPackages
        val cidrs = runCatching { AsnResolver.ensure(applicationContext, asns) }.getOrDefault(emptyMap())
        return withPackages.copy(rules = SingBoxRule.expandAsn(withPackages.rules, cidrs))
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
        expandProfileAsn(loadRoutingProfilesState().resolve(config.routingProfileId))

    /**
     * Resolves the profile for [locationProfileId] from [state] AND expands any `asn:` selectors to
     * CIDRs (cached per ASN). Use this on every connect path instead of a bare `state.resolve(...)` so
     * ASN-based rules work on both cores. Null profile → null (no-op); unresolved ASNs are dropped.
     */
    private suspend fun resolveProfileExpandingAsn(
        state: RoutingProfilesState,
        locationProfileId: String?,
    ): RoutingProfile? = expandProfileAsn(state.resolve(locationProfileId))

    /** Replaces the profile's `asn:` selectors with their CIDR lists (no-op when it has none). */
    private suspend fun expandProfileAsn(profile: RoutingProfile?): RoutingProfile? {
        if (profile == null) return null
        val asns = profile.referencedAsns()
        if (asns.isEmpty()) return profile
        val cidrs = runCatching { AsnResolver.ensure(applicationContext, asns) }.getOrDefault(emptyMap())
        return profile.expandAsn(cidrs)
    }

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
     * Ensures the geo `.dat` files are present for a VERBATIM Xray config that ITSELF references
     * geosite:/geoip: selectors (e.g. a Happ/Remnawave JSON subscription whose own routing sends RU
     * sites direct and blocks ads). Unlike [ensureGeoAssetPath] this does NOT require an app routing
     * profile — the config's OWN routing is honored, so the db must be available regardless of whether
     * the user configured a profile. Uses the profile's geo sources when one is set, else the global
     * defaults. Returns the asset dir, or "" if the db couldn't be fetched (caller then strips geo).
     */
    private suspend fun ensureRawConfigGeoAssetPath(profile: RoutingProfile?): String {
        val state = loadRoutingProfilesState()
        val geoip = profile?.geoipUrl?.takeIf { it.isNotBlank() } ?: state.geoipUrl
        val geosite = profile?.geositeUrl?.takeIf { it.isNotBlank() } ?: state.geositeUrl
        val ok = runCatching { GeoAssetManager.ensureAssets(applicationContext, geoip, geosite) }
            .getOrDefault(false)
        return if (ok) {
            GeoAssetManager.assetDir(applicationContext).absolutePath
        } else {
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
        runCatching { mobileRuntime.setTelemostCookies(if (use) behavior.telemostCookies.trim() else "") }
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
        // Multi-room: tear down the balancer + every independent room before the singleton stop.
        olcrtcRoomManager?.let {
            runCatching { it.stop() }
            olcrtcRoomManager = null
        }
        activeMultiRoomTotal = 0
        activeFreeturnServers = 0
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

    // HTTP-proxy port for Proxy mode (Happ-style "HTTP Port" next to the "SOCKS5 Port"). Offset by +4 to
    // clear the internal helper ports (+1 chain, +2 awg base, +3 hy2). Browsers point their HTTP proxy here.
    private val httpProxyPort: Int get() = socksListenPort + 4

    /**
     * Proxy mode only: raise the engine-agnostic HTTP→SOCKS bridge so browsers (which speak an HTTP proxy,
     * not SOCKS) can use the local proxy. Forwards to the core's own SOCKS5. Never started in TUN mode.
     */
    private fun startHttpProxyBridge() {
        stopHttpProxyBridge()
        val bridge = HttpProxyBridge(
            listenHost = socksListenHost,
            listenPort = httpProxyPort,
            socksHost = socksConnectHost(),
            socksPort = socksListenPort,
            log = { addLog(it) },
        )
        httpProxyBridge = if (bridge.start()) {
            addLog("HTTP proxy ready on $socksListenHost:$httpProxyPort (forwards to SOCKS5 $socksListenPort)")
            bridge
        } else {
            null
        }
    }

    private fun stopHttpProxyBridge() {
        httpProxyBridge?.let { runCatching { it.stop() } }
        httpProxyBridge = null
    }

    /** The phone's current LAN IPv4 (e.g. 192.168.x.x) for display, or null if none found. */
    private fun deviceLanIp(): String? {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { addr ->
                    addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress
                }?.hostAddress
        }.getOrNull()
    }

    /**
     * After Proxy mode reports Connected, actually drive traffic through the local SOCKS5 once and
     * write the verdict to the in-app log: whether the path carries data and, on success, the exit IP
     * the user is browsing from. This is the difference between "the listener is up" (which the
     * watchdog already checks) and "a request genuinely reaches the internet and comes back". Best
     * effort, fire-and-forget — never blocks the connect path or flips the VPN status.
     */
    private fun launchProxySelfCheck() {
        scope.launch(Dispatchers.IO) {
            // Give the core's SOCKS listener a moment to finish binding before the first dial.
            delay(PROXY_SELF_CHECK_DELAY_MS)
            // 1) Loopback path (127.0.0.1) — proves the core itself carries traffic.
            val verdict = runCatching { probeProxyExitIp(socksConnectHost()) }
                .getOrElse { ProxySelfCheckResult.Failure(it.message ?: it.javaClass.simpleName) }
            when (verdict) {
                is ProxySelfCheckResult.Success ->
                    addLog("Proxy self-check: traffic OK — exit IP ${verdict.ip}")
                is ProxySelfCheckResult.Failure ->
                    addLog("Proxy self-check: NO traffic through SOCKS (${verdict.reason})")
            }

            // 2) LAN path (the phone's own 192.168.x.x) — this is EXACTLY what a PC/other device on
            //    the same Wi-Fi does. It's the decisive test for "expose SOCKS5/HTTP to the LAN":
            //    if loopback works but this fails, the listener isn't reachable externally (binding,
            //    Wi-Fi AP isolation, or a firewall) — not a "no traffic" problem. If both pass, the
            //    proxy is good and the issue is on the client side (wrong address/port).
            val lanIp = deviceLanIp()
            if (lanIp == null) {
                addLog("Proxy LAN check: no Wi-Fi/LAN IPv4 on this device — connect Wi-Fi to expose the proxy to a PC")
            } else {
                val lanVerdict = runCatching { probeProxyExitIp(lanIp) }
                    .getOrElse { ProxySelfCheckResult.Failure(it.message ?: it.javaClass.simpleName) }
                when (lanVerdict) {
                    is ProxySelfCheckResult.Success ->
                        addLog("Proxy LAN check (SOCKS5): reachable on $lanIp:$socksListenPort — exit IP ${lanVerdict.ip}")
                    is ProxySelfCheckResult.Failure ->
                        addLog("Proxy LAN check (SOCKS5): NOT reachable on $lanIp:$socksListenPort (${lanVerdict.reason}) — check Wi-Fi AP isolation / firewall")
                }

                // 3) HTTP path on the LAN IP (port +4). Browsers / the Windows system proxy speak an
                //    HTTP proxy, NOT SOCKS — so this is the port a PC actually points at, and the one
                //    that was never verified before. Drives a real absolute-form GET through the bridge.
                val httpVerdict = runCatching { probeHttpProxyExitIp(lanIp, httpProxyPort) }
                    .getOrElse { ProxySelfCheckResult.Failure(it.message ?: it.javaClass.simpleName) }
                when (httpVerdict) {
                    is ProxySelfCheckResult.Success ->
                        addLog("Proxy LAN check (HTTP): reachable on $lanIp:$httpProxyPort — exit IP ${httpVerdict.ip}. Point your PC's HTTP proxy here.")
                    is ProxySelfCheckResult.Failure ->
                        addLog("Proxy LAN check (HTTP): FAILED on $lanIp:$httpProxyPort (${httpVerdict.reason}) — HTTP bridge not serving")
                }
            }
        }
    }

    /**
     * Opens the local SOCKS5, performs a no-auth handshake, asks it to CONNECT (by domain, so the
     * exit node resolves it) to a plain-HTTP IP-echo endpoint, and parses the returned address. Throws
     * or returns Failure if any step doesn't complete — i.e. the proxy "connected" but carries nothing.
     */
    private fun probeProxyExitIp(host: String): ProxySelfCheckResult {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(host, socksListenPort),
                SOCKET_CONNECT_TIMEOUT_MS
            )
            socket.soTimeout = PROXY_SELF_CHECK_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // Greeting: offer no-auth only (Proxy mode forces no-auth, see applyStartOptions).
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val method = ByteArray(2)
            readFullyOrThrow(input, method)
            if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) {
                return ProxySelfCheckResult.Failure("SOCKS no-auth not accepted")
            }

            // CONNECT api.ipify.org:80 via ATYP=domain so DNS is resolved at the exit, not locally.
            val host = PROXY_SELF_CHECK_HOST.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(7 + host.size)
            request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
            request[4] = host.size.toByte()
            System.arraycopy(host, 0, request, 5, host.size)
            request[5 + host.size] = 0x00            // port 80 high byte
            request[6 + host.size] = 0x50.toByte()   // port 80 low byte
            out.write(request)
            out.flush()

            val reply = ByteArray(4)
            readFullyOrThrow(input, reply)
            if (reply[1] != 0x00.toByte()) {
                return ProxySelfCheckResult.Failure("SOCKS CONNECT rejected (0x%02x)".format(reply[1]))
            }
            // Consume the bound address the proxy echoes back (length depends on ATYP).
            val boundLen = when (reply[3]) {
                0x01.toByte() -> 4 + 2
                0x04.toByte() -> 16 + 2
                0x03.toByte() -> {
                    val l = ByteArray(1); readFullyOrThrow(input, l); (l[0].toInt() and 0xFF) + 2
                }
                else -> return ProxySelfCheckResult.Failure("bad SOCKS bound ATYP")
            }
            readFullyOrThrow(input, ByteArray(boundLen))

            out.write(
                ("GET / HTTP/1.0\r\nHost: $PROXY_SELF_CHECK_HOST\r\n" +
                    "User-Agent: olcbox\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()

            val response = input.readBytes().toString(Charsets.US_ASCII)
            val body = response.substringAfter("\r\n\r\n", "").trim()
            val ip = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return if (ip != null && ip.length in 7..45 && ip.any { it == '.' || it == ':' }) {
                ProxySelfCheckResult.Success(ip)
            } else {
                ProxySelfCheckResult.Failure("empty/invalid IP response")
            }
        }
    }

    private fun readFullyOrThrow(input: java.io.InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw java.io.EOFException("SOCKS stream closed early")
            read += n
        }
    }

    /**
     * Drives a real HTTP-proxy request through the HttpProxyBridge at [host]:[port] exactly like a
     * browser / the Windows system proxy does: open TCP, send an ABSOLUTE-form `GET http://…` request
     * line, read the IP-echo body. Proves the HTTP port (the one PCs actually point at) genuinely
     * forwards through the bridge → core SOCKS → exit. Failure ⇒ the bridge isn't serving.
     */
    private fun probeHttpProxyExitIp(host: String, port: Int): ProxySelfCheckResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            socket.soTimeout = PROXY_SELF_CHECK_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(
                ("GET http://$PROXY_SELF_CHECK_HOST/ HTTP/1.0\r\nHost: $PROXY_SELF_CHECK_HOST\r\n" +
                    "User-Agent: olcbox\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()
            val response = input.readBytes().toString(Charsets.US_ASCII)
            if (response.isEmpty()) return ProxySelfCheckResult.Failure("no response from HTTP bridge")
            val statusLine = response.substringBefore("\r\n")
            val body = response.substringAfter("\r\n\r\n", "").trim()
            val ip = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return if (ip != null && ip.length in 7..45 && ip.any { it == '.' || it == ':' }) {
                ProxySelfCheckResult.Success(ip)
            } else {
                ProxySelfCheckResult.Failure("bad HTTP reply: ${statusLine.take(40)}")
            }
        }
    }

    private sealed class ProxySelfCheckResult {
        data class Success(val ip: String) : ProxySelfCheckResult()
        data class Failure(val reason: String) : ProxySelfCheckResult()
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
        VpnSocketProtectBridge.protect = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy, AndroidConnectionMode.Tproxy -> coreRunning()
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
        // Proxy (SOCKS) mode has no TUN, so setUnderlyingNetworks above is a no-op and the
        // process→network binding is the ONLY route out for the cores. Whenever the upstream handle
        // changes (a benign Wi-Fi refresh swaps the Network object, a fallback to another network, …)
        // the old binding goes STALE and traffic silently dies — and because currentNetwork now equals
        // the new upstream, the watchdog's rebind check never fires. Re-pin here so every handle swap
        // immediately re-routes the cores. null = lost network → unbinds (matches the wait-for-network
        // path). Only while a core is live; the initial connect binds explicitly in startXxxCore.
        if (connectionMode.isTunless && coreRunning()) {
            bindProcessToNetwork(network)
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

    /**
     * Releases the process→network binding after a transport's setup completes, EXCEPT when a live
     * Proxy-mode session needs to keep it: in Proxy (SOCKS) mode there is no TUN, so protect()/
     * setUnderlyingNetworks don't route the cores — only the process binding does. Keeping it bound for
     * the session is what makes the exposed SOCKS actually carry traffic. TUN mode is unaffected (it
     * always unbinds here, relying on protect()/underlying networks). On a failed start the core is
     * already stopped (coreRunning() == false), so the binding is released as before.
     */
    private fun releaseProcessBindingUnlessProxy() {
        if (connectionMode.isTunless && coreRunning()) return
        unbindProcessFromNetwork()
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
        // Proxy (SOCKS) mode has no TUN: protect() is a no-op and setUnderlyingNetworks does not apply
        // (see updateUnderlyingNetwork), so the process→network binding is the ONLY thing routing the
        // cores' outbound sockets to the chosen upstream. Unbinding it after setup leaves the cores on
        // the system default network, which is why the local SOCKS connected but carried no traffic.
        // Keep the bind for the whole session on every transport in Proxy mode.
        if (connectionMode.isTunless) return true
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

    /**
     * Heads-up notification for a pending manual VK captcha (VK-TURN): if the app is backgrounded
     * mid-connect the user has no other way to learn the tunnel is waiting on them. Tapping opens
     * the app, where the captcha WebView dialog is already up (driven by [OlcboxVpnState.vkCaptchaUrl]).
     * Separate high-importance channel — the foreground channel is LOW and would not pop.
     */
    private fun notifyVkCaptcha(url: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    VK_CAPTCHA_CHANNEL_ID,
                    "VK captcha",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val statIcon = resources.getIdentifier("ic_stat_yptun", "drawable", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock
        val notification = NotificationCompat.Builder(this, VK_CAPTCHA_CHANNEL_ID)
            .setSmallIcon(statIcon)
            .setContentTitle(ns.vkCaptchaTitle)
            .setContentText(ns.notifVkCaptcha)
            .setContentIntent(getAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(VK_CAPTCHA_NOTIFICATION_ID, notification)
    }

    private fun cancelVkCaptchaNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(VK_CAPTCHA_NOTIFICATION_ID)
    }

    private fun setStatus(status: VpnStatus) {
        OlcboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
            AndroidConnectionMode.Tproxy -> "Transparent proxy"
        }
    }

    /** Localized strings for notifications, resolved against the user's current language choice. */
    private val ns get() = stringsFor(LocalizationState.effective)

    /**
     * Connected-state notification body: the active server's display name.
     * Falls back to the generic "Connected · VPN/Proxy" line if the name is empty.
     */
    private fun connectedNotificationText(): String {
        val base = connectedLocationName.ifBlank { ns.notifConnectedMode(activeModeLabel()) }
        if (showRoomsInNotif && olcrtcRoomManager != null && activeMultiRoomTotal > 0 &&
            (engineType == EngineType.Stealth || engineType == EngineType.Chain)
        ) {
            val up = runCatching { Mobile.roomsRunning() }.getOrDefault(0)
            return "$base · $up/$activeMultiRoomTotal комнат"
        }
        // VK-TURN multi-server freeturn: show connected/total servers (same toggle as rooms). The
        // count of relays with a live TURN stream is the "connected" number.
        if (showRoomsInNotif && engineType == EngineType.VkTurn && activeFreeturnServers > 1) {
            val up = runCatching { Freeturn.activeRelays() }.getOrDefault(0)
            return "$base · $up/$activeFreeturnServers серверов"
        }
        return base
    }

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

        // Widget "Auto = fastest" probe pass (see startAutoConnectSearch).
        private const val AUTO_CONNECT_PING_TIMEOUT_MS = 10_000L

        /**
         * Process-shared [AndroidVpnManager] used ONLY for the widget auto-connect probe pass.
         * Created once and kept (its init launches never-ending preference collectors, so churning
         * instances would leak coroutines). The Activity keeps its own instance as before.
         */
        @Volatile
        private var autoPingManager: AndroidVpnManager? = null

        private fun autoPingManager(context: Context): AndroidVpnManager =
            autoPingManager ?: synchronized(OlcboxVpnService::class.java) {
                autoPingManager
                    ?: AndroidVpnManager(context.applicationContext).also { autoPingManager = it }
            }

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        // Preference-ordered fallback when the active network advertises no usable DNS (see
        // resolveOlcRtcDnsServer): Yandex first — it stays reachable on RU IPv4-only mobile where
        // Cloudflare/Google UDP/53 are frequently blocked.
        private const val FALLBACK_OLCRTC_DNS_SERVERS = "77.88.8.8:53,8.8.8.8:53,1.1.1.1:53"
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val SPEED_INTERVAL_MS = 2_000L
        // Energy-saver: a much slower speed/rooms notification refresh (vs. SPEED_INTERVAL_MS). Network
        // switches are event-driven (the NetworkCallback), so the watchdog is only a backstop for a
        // silently-dead core / stalled traffic — safe to poll far less often when saving power.
        private const val ENERGY_SAVER_SPEED_INTERVAL_MS = 6_000L
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
        // Max EXTRA freeturn servers run alongside the primary for load-balancing (6 total).
        private const val VKTURN_MAX_EXTRA_FREETURN = 5
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
        // Proxy-mode post-connect self-check: drive one real request through the local SOCKS to confirm
        // the path actually carries traffic and learn the exit IP (logged for the user). Plain-HTTP
        // endpoint so no TLS is needed and the body is the bare IP.
        private const val PROXY_SELF_CHECK_DELAY_MS = 700L
        private const val PROXY_SELF_CHECK_TIMEOUT_MS = 8_000
        private const val PROXY_SELF_CHECK_HOST = "api.ipify.org"
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
        private const val VK_CAPTCHA_CHANNEL_ID = "olcbox_vk_captcha"
        private const val VK_CAPTCHA_NOTIFICATION_ID = 101
        private const val TAG = "OlcboxVpnService"

        // High-frequency Android UI/render/system spam that the whole-process logcat capture (`*:V`)
        // would otherwise pour into the in-app journal, drowning the VPN/core lines that actually
        // matter. Tags are anchored with a leading "/" so they match the logcat tag column
        // ("I/View   ( 1234): …") and never a substring of a core's message. Also drops the
        // "/sing-box" logcat copy — sing-box output is already piped into the journal as "sb: …",
        // so without this every sing-box line appeared TWICE. Matched case-insensitively.
        private val LOGCAT_NOISE = listOf(
            // sing-box is double-logged (Go LogWriter → "sb: …" AND android Log tag "sing-box").
            "/sing-box",
            // Android view / render / window / input framework — pure UI churn.
            "/View", "/VRI[", "/HWUI", "/ViewRootImpl", "/DecorView", "/SurfaceView",
            "/InputTransport", "/InputMethodManager", "/ImeFocusController", "/ImeTracker",
            "/InsetsSourceConsumer", "/InsetsController", "/WindowOnBackDispatcher", "/WindowManager",
            "/BufferQueueProducer", "/BLASTBufferQueue", "/SurfaceComposerClient", "/Choreographer",
            "/OpenGLRenderer", "/AdrenoVK", "/Dialog", "/Looper", "/CustomFrequencyManager",
            "/NativeCustomFrequencyManager", "/perf_hint", "/Compiler", "/NotificationManager", "/BBA2",
            // Message-substring offenders (no stable tag).
            "frameRateCategory", "setRequestedFrameRate", "setFrameRate", "ViewPostIme",
            "CacheManager::trimMemory", "beginning of main", "beginning of system",
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
