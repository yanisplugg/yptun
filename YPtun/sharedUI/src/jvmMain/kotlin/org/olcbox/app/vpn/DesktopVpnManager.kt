package org.olcbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.DesktopEngineController
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    init {
        // If a previous run crashed while system-proxy mode was active, the OS proxy may still point
        // at our now-dead local port (no internet). Clear that leftover on launch.
        scope.launch { runCatching { proxyController.clearStaleProxy() } }
    }

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedSinceEpochMs = MutableStateFlow(0L)
    override val connectedSinceEpochMs: StateFlow<Long> = _connectedSinceEpochMs.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var watchdogJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var generation = 0L
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)

    // In-process engines (sing-box / xray / AmneziaWG / Hysteria2 / VK-TURN / chained olcRTC),
    // the desktop port of the Android engine orchestration. Stealth keeps the olcrtc subprocess.
    private val engineController = DesktopEngineController(::addLog)
    private var engineLocation: LocationConfig? = null

    /**
     * The user's connection mode (TUN vs system proxy), supplied by the UI layer
     * (DesktopSettingsController). Defaults to TUN, the historical desktop behavior.
     */
    var connectionModeProvider: () -> AndroidConnectionMode = { AndroidConnectionMode.Tun }

    /** The mode actually used by the current/last connection — drives the matching cleanup. */
    private var activeDesktopMode: DesktopMode? = null

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? = pingInternal(locationConfig)

    /**
     * Desktop port of AndroidVpnManager.pingInternal: the user-selected ping method (Settings →
     * «Пинг») overrides the per-engine default probe. TCP/ICMP probe the location's own server;
     * the URL is used only by the via-proxy GET/HEAD probes (throwaway xray/awg instances inside
     * yptuncore — works while disconnected, like v2rayNG/Happ).
     */
    private suspend fun pingInternal(locationConfig: LocationConfig): Long? {
        val config = locationConfig.normalized()
        val behavior = org.olcbox.app.vpn.desktop.JvmVpnSettings.loadAppBehavior()
        val profile = config.proxy
        val hasProxy = profile != null
        when (behavior.pingMode) {
            org.olcbox.app.data.model.AppBehaviorSettings.PING_TCP ->
                if (hasProxy) return tcpPing(profile?.server, profile?.serverPort)
            org.olcbox.app.data.model.AppBehaviorSettings.PING_ICMP ->
                if (hasProxy) return icmpPing(profile?.server)
            org.olcbox.app.data.model.AppBehaviorSettings.PING_PROXY_GET ->
                if (hasProxy) return proxyUrlTest(config, behavior.effectivePingUrl(), "GET")
            org.olcbox.app.data.model.AppBehaviorSettings.PING_PROXY_HEAD ->
                if (hasProxy) return proxyUrlTest(config, behavior.effectivePingUrl(), "HEAD")
            else -> { /* PING_AUTO → engine-specific default below */ }
        }
        val proxyType = profile?.type
        return when {
            // Obfuscated transports whose real endpoint is blocked/hidden: probe through the live
            // tunnel when connected; otherwise the best standalone probe available.
            config.engine == EngineType.VkTurn -> tunnelPing()
            proxyType == ProxyProfile.TYPE_AMNEZIAWG ->
                if (isConnected.value) tunnelPing()
                else awgProbePing(profile?.awgConfig.orEmpty())
            proxyType == ProxyProfile.TYPE_HYSTERIA2 ->
                if (isConnected.value) tunnelPing()
                else icmpPing(profile?.server.orEmpty())
            config.engine == EngineType.Standard || config.engine == EngineType.Chain ->
                tcpPing(profile?.server, profile?.serverPort)
            else -> OlcRtcConnectionChecker.ping(
                locationConfig = locationConfig,
                deviceId = locationsRepository.getDeviceIdentity()
            )
        }
    }

    /** TCP-connect latency to host:port in ms, best of a few attempts, or null if unreachable. */
    private suspend fun tcpPing(host: String?, port: Int?): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isNullOrBlank() || port == null || port !in 1..65535) return@withContext null
        var best: Long? = null
        repeat(TCP_PING_ATTEMPTS) {
            val elapsed = runCatching {
                Socket().use { socket ->
                    val start = System.nanoTime()
                    socket.connect(InetSocketAddress(host, port), TCP_PING_TIMEOUT_MS)
                    (System.nanoTime() - start) / 1_000_000L
                }
            }.getOrNull()
            if (elapsed != null && (best == null || elapsed < best!!)) best = elapsed
        }
        best
    }

    /**
     * ICMP latency to [host]. Java's isReachable needs raw-socket privileges on Windows, so when it
     * fails we shell out to the system `ping` and parse the reported time.
     */
    private suspend fun icmpPing(host: String?): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isNullOrBlank()) return@withContext null
        var best: Long? = null
        repeat(TCP_PING_ATTEMPTS) {
            val ms = runCatching {
                val addr = java.net.InetAddress.getByName(host)
                val start = System.nanoTime()
                if (addr.isReachable(TCP_PING_TIMEOUT_MS)) (System.nanoTime() - start) / 1_000_000L else null
            }.getOrNull() ?: systemPing(host)
            if (ms != null && (best == null || ms < best!!)) best = ms
        }
        best
    }

    /** One `ping` invocation via the OS binary; returns the echoed time in ms or null. */
    private fun systemPing(host: String): Long? = runCatching {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val command = if (isWindows) {
            listOf("ping", "-n", "1", "-w", TCP_PING_TIMEOUT_MS.toString(), host)
        } else {
            listOf("ping", "-c", "1", "-W", (TCP_PING_TIMEOUT_MS / 1000).coerceAtLeast(1).toString(), host)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TCP_PING_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        // "time=23ms" / "время=23мс" / "time<1ms" — grab the first number before ms/мс.
        Regex("[=<]\\s*(\\d+)\\s*(?:ms|мс)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()

    /** Pre-connection AmneziaWG latency: a throwaway WG handshake probe inside yptuncore. */
    private suspend fun awgProbePing(awgConfig: String): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (awgConfig.isBlank()) return@withContext null
        val ms = runCatching { org.olcbox.app.vpn.desktop.YpTunCore.awgProbe(awgConfig) }.getOrDefault(-1L)
        if (ms >= 0) ms else null
    }

    /**
     * End-to-end latency through the live tunnel: a SOCKS5 CONNECT to 1.1.1.1:443 via the running
     * core's local SOCKS. Returns null when no core is up.
     */
    private suspend fun tunnelPing(): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!isConnected.value) return@withContext null
        val socks = _socksProxySettings.value.normalized()
        var best: Long? = null
        repeat(TCP_PING_ATTEMPTS) {
            val ms = runCatching {
                socks5ConnectRtt(
                    socks.host, socks.port, socks.username, socks.password,
                    TUNNEL_PROBE_HOST, TUNNEL_PROBE_PORT, TUNNEL_PING_TIMEOUT_MS
                )
            }.getOrNull()
            if (ms != null && (best == null || ms < best!!)) best = ms
        }
        best
    }

    /** Hand-rolled SOCKS5 CONNECT to an IPv4 target through proxyHost:proxyPort; returns RTT ms. */
    private fun socks5ConnectRtt(
        proxyHost: String, proxyPort: Int, username: String, password: String,
        targetHost: String, targetPort: Int, timeoutMs: Int
    ): Long? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val start = System.nanoTime()

            val useAuth = username.isNotBlank()
            out.write(if (useAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val sel = readExactly(inp, 2)
            if (sel[0].toInt() and 0xFF != 0x05) return null
            when (sel[1].toInt() and 0xFF) {
                0x00 -> {}
                0x02 -> {
                    val u = username.encodeToByteArray(); val p = password.encodeToByteArray()
                    val a = ByteArray(3 + u.size + p.size)
                    a[0] = 0x01; a[1] = u.size.toByte(); u.copyInto(a, 2)
                    a[2 + u.size] = p.size.toByte(); p.copyInto(a, 3 + u.size)
                    out.write(a); out.flush()
                    if (readExactly(inp, 2)[1].toInt() and 0xFF != 0x00) return null
                }
                else -> return null
            }

            val ip = targetHost.split('.').map { it.toInt() }
            val req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                ip[0].toByte(), ip[1].toByte(), ip[2].toByte(), ip[3].toByte(),
                ((targetPort shr 8) and 0xFF).toByte(), (targetPort and 0xFF).toByte()
            )
            out.write(req); out.flush()

            val head = readExactly(inp, 4)
            if (head[1].toInt() and 0xFF != 0x00) return null
            val addrLen = when (head[3].toInt() and 0xFF) {
                0x01 -> 4; 0x04 -> 16; 0x03 -> readExactly(inp, 1)[0].toInt() and 0xFF
                else -> return null
            }
            readExactly(inp, addrLen + 2)
            return (System.nanoTime() - start) / 1_000_000L
        }
    }

    private fun readExactly(inp: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("short read")
            off += r
        }
        return buf
    }

    /**
     * Per-server proxy URL test (à la v2rayNG / Happ): a throwaway xray instance inside yptuncore
     * fetches [url] through the location's proxy. AmneziaWG goes through a throwaway awg tunnel;
     * Hysteria2 isn't xray-serviceable (use Auto/ICMP). Works while disconnected.
     */
    private suspend fun proxyUrlTest(location: LocationConfig, url: String, method: String): Long? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val profile = location.proxy ?: run {
                addLog("Proxy $method ping: location has no proxy")
                return@withContext null
            }
            if (profile.type == ProxyProfile.TYPE_AMNEZIAWG) {
                val awgConfig = profile.awgConfig.orEmpty()
                if (awgConfig.isBlank()) return@withContext null
                val ms = runCatching {
                    org.olcbox.app.vpn.desktop.YpTunCore.awgMeasureDelay(awgConfig, url, method, TUNNEL_PING_TIMEOUT_MS)
                }.getOrDefault(-1L)
                return@withContext if (ms >= 0) ms else null
            }
            if (profile.type == ProxyProfile.TYPE_HYSTERIA2) {
                addLog("Proxy $method ping: Hysteria2 not supported (use Auto/ICMP)")
                return@withContext null
            }
            if (profile.server.isBlank()) return@withContext null
            val listenPort = (20_000..60_000).random()
            val configJson = runCatching {
                org.olcbox.app.vpn.xray.XrayConfig.build(
                    profile = profile,
                    listenPort = listenPort,
                    listenHost = "127.0.0.1",
                    logLevel = "none",
                    blockQuic = false,
                )
            }.getOrElse {
                addLog("Proxy $method ping: failed to build test config: ${it.message}")
                return@withContext null
            }
            val ms = runCatching {
                org.olcbox.app.vpn.desktop.YpTunCore.xrayMeasureDelay(configJson, url, method, TUNNEL_PING_TIMEOUT_MS)
            }.getOrElse {
                addLog("Proxy $method ping error for ${profile.server}: ${it.message}")
                -1L
            }
            if (ms >= 0) ms else null
        }

    /**
     * IPv4 addresses of the engines' upstream servers for [location] — routed around the Windows
     * TUN so the engines' own traffic doesn't loop through the tunnel they provide.
     */
    private fun resolveBypassServerIps(location: LocationConfig): List<String> {
        val config = location.normalized()
        val hosts = buildList {
            config.proxy?.let { profile ->
                if (profile.server.isNotBlank() && profile.server != "127.0.0.1") add(profile.server)
                awgEndpointHost(profile)?.let { add(it) }
            }
            config.proxy2?.let { if (it.server.isNotBlank()) add(it.server) }
            config.vkturn?.let { vk ->
                runCatching { java.net.URI(vk.uri).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        return hosts.distinct().flatMap { host ->
            runCatching {
                java.net.InetAddress.getAllByName(host)
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress }
            }.getOrElse {
                addLog("Could not resolve $host for TUN bypass route: ${it.message}")
                emptyList()
            }
        }.distinct().filter { it != "127.0.0.1" }
    }

    /** The `Endpoint = host:port` of an AmneziaWG INI config, if any. */
    private fun awgEndpointHost(profile: ProxyProfile): String? {
        if (profile.awgConfig.isBlank()) return null
        val line = profile.awgConfig.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && "=" in it }
            ?: return null
        val value = line.substringAfter("=").trim()
        return value.substringBeforeLast(":").trim('[', ']').takeIf { it.isNotBlank() }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val socks = _socksProxySettings.value.normalized()
        return SubscriptionFetchProxy(
            host = socks.host,
            port = socks.port,
            username = socks.username,
            password = socks.password
        )
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = DesktopSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            engineController.close()
            scope.cancel()
        }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        try {
            val ready = CompletableDeferred<Unit>()
            val startupFailure = CompletableDeferred<String>()
            val desktopMode = if (connectionModeProvider() == AndroidConnectionMode.Proxy) {
                DesktopMode.SystemProxy
            } else {
                DesktopMode.current()
            }
            activeDesktopMode = desktopMode
            val socksSettings = _socksProxySettings.value.normalized()

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }

            // Non-Stealth engines (sing-box/xray/AmneziaWG/Hysteria2/VK-TURN/Chain) run in-process
            // via the yptuncore library — the desktop port of the Android engine stack. Stealth
            // keeps the proven olcrtc subprocess (incl. the privileged Linux TUN path).
            val useEngineController = location.engine != EngineType.Stealth && engineController.isSupported

            if (useEngineController) {
                engineLocation = location
                // Windows: let sing-box own the wintun adapter itself (auto_route + auto_detect_interface
                // + native hijack-dns), the Hiddify/sing-box-official architecture — instead of the
                // external xjasonlyu/tun2socks bridge, whose SOCKS-UDP path stalled DNS through this
                // server. sing-box reads wintun directly and excludes its own upstream from the tunnel,
                // so there's no proxy-UDP dependency and no manual bypass route. Per-process split
                // tunneling rides the same in-core TUN for free (only the TUN owner can attribute apps).
                val split = org.olcbox.app.vpn.desktop.JvmVpnSettings.loadSplitTunnel()
                val wantsInCoreTun = desktopMode == DesktopMode.WindowsTun
                engineController.start(
                    location = location,
                    listenHost = socksSettings.host,
                    listenPort = socksSettings.port,
                    socksUsername = socksSettings.username,
                    socksPassword = socksSettings.password,
                    deviceId = locationsRepository.getDeviceIdentity(),
                    tunViaSingBox = wantsInCoreTun,
                    splitTunnelMode = split.mode,
                    splitTunnelProcesses = when (split.mode) {
                        "proxy_selected" -> split.proxyProcesses.toList()
                        "bypass_selected" -> split.bypassProcesses.toList()
                        else -> emptyList()
                    },
                )
            } else {
                process = startOlcRtcProcessWithFallback(
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = true,
                    privileged = desktopMode == DesktopMode.LinuxTun
                )

                waitForOlcRtcReady(
                    process = process ?: error("olcRTC process is missing"),
                    ready = ready,
                    startupFailure = startupFailure,
                    socksPort = socksSettings.port,
                    requestGeneration = requestGeneration
                )
            }

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(socksSettings.port, requestGeneration)
                DesktopMode.WindowsTun -> if (engineController.tunHandledInCore) {
                    // sing-box raised the wintun adapter itself (per-process split tunneling);
                    // no external tun2socks needed.
                    addLog("Windows TUN owned by sing-box (per-process split tunneling active)")
                } else {
                    startWindowsTun(
                        socksPort = socksSettings.port,
                        requestGeneration = requestGeneration,
                        bypassServerIps = if (useEngineController) resolveBypassServerIps(location) else emptyList()
                    )
                }
                DesktopMode.SystemProxy -> startSystemProxy(socksSettings, requestGeneration)
            }

            setStatus(VpnStatus.Connected)
            startWatchdog(requestGeneration)
            addLog(
                when (desktopMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN connected"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN connected"
                    DesktopMode.SystemProxy -> "Desktop proxy connected"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            when (activeDesktopMode ?: DesktopMode.current()) {
                DesktopMode.LinuxTun -> {
                    runCatching {
                        linuxTunController.stop(tunProcess)
                    }.onFailure {
                        addLog("Linux TUN cleanup failed: ${it.message}")
                    }
                    tunProcess = null
                }
                DesktopMode.WindowsTun -> {
                    runCatching {
                        windowsTunController.stop(tunProcess)
                    }.onFailure {
                        addLog("Windows TUN cleanup failed: ${it.message}")
                    }
                    tunProcess = null
                }
                DesktopMode.SystemProxy -> {
                    runCatching {
                        proxyController.restore()
                    }.onFailure {
                        addLog("Proxy restore failed: ${it.message}")
                    }
                }
            }

            pacServer.stop()
            runCatching { engineController.stopAll() }
            engineLocation = null
            stopProcess(process)
            process = null
            deleteOlcRtcConfig()

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(hevBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(
        socksPort: Int,
        requestGeneration: Long,
        bypassServerIps: List<String> = emptyList()
    ) {
        val tun2SocksBinary = DesktopNativeAssets.resolveWindowsTun2SocksBinary()
        tunProcess = windowsTunController.start(tun2SocksBinary, socksPort, bypassServerIps)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("tun2socks process is missing"))
    }

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        // PAC is only used by macOS; Windows points its system HTTP proxy straight at the sing-box
        // "mixed" inbound (host:port), which WinINET honours reliably (PAC+SOCKS5 is flaky there).
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        proxyController.enable(
            httpProxyHostPort = "${socksSettings.host}:${socksSettings.port}",
            pacUrl = pacServer.url
        )

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    private enum class DesktopMode {
        LinuxTun,
        WindowsTun,
        SystemProxy;

        companion object {
            fun current(): DesktopMode {
                return when (DesktopPaths.os) {
                    DesktopOs.Linux -> LinuxTun
                    DesktopOs.Windows -> WindowsTun
                    DesktopOs.MacOS,
                    DesktopOs.Other -> SystemProxy
                }
            }
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        var lastException: Exception? = null

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null) {
            return
        }

        setStatus(VpnStatus.Stopping)

        when (activeDesktopMode ?: DesktopMode.current()) {
            DesktopMode.LinuxTun -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.WindowsTun -> {
                runCatching {
                    windowsTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.SystemProxy -> {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
        }

        pacServer.stop()

        // Stop the in-process engines (no-op when the olcrtc subprocess path was used).
        runCatching { engineController.stopAll() }
        engineLocation = null

        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(
                when (DesktopPaths.os) {
                    DesktopOs.Linux -> "Desktop Linux TUN stopped"
                    DesktopOs.Windows -> "Desktop Windows TUN stopped"
                    DesktopOs.MacOS,
                    DesktopOs.Other -> "Desktop proxy stopped"
                }
            )
        }
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val dataDir = DesktopNativeAssets.resolveOlcRtcDataDir()
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dataDir = dataDir
        )
        val configPath = writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, room=${config.id}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            startedProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach

                    if (logOutput) {
                        addLog("rtc: $line")
                    }

                    if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                        ready.complete(Unit)
                    }

                    if (isFatalOlcRtcStartupLine(line)) {
                        startupFailure.complete(line)
                    }
                }
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-client-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            target.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach

                    addLog("tun: $line")
                }
            }
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                waitForOlcRtcStartupStability(process, startupFailure, requestGeneration)
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Fetch the current exit IP + country as seen from the internet (through the tunnel). In TUN mode
     * our own HTTP request is captured by the system TUN; in proxy mode we dial through the local
     * SOCKS. Returns e.g. "203.0.113.7 · Netherlands", or null if not connected / lookup failed.
     */
    suspend fun checkExitIp(): String? = withContext(Dispatchers.IO) {
        if (!isConnected.value) return@withContext null
        runCatching {
            val url = java.net.URL("http://ip-api.com/json/?fields=query,country")
            val conn = if (connectionModeProvider() == AndroidConnectionMode.Proxy) {
                val s = _socksProxySettings.value.normalized()
                url.openConnection(
                    java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress(s.host, s.port))
                )
            } else {
                url.openConnection()
            }
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.getInputStream().bufferedReader().use { it.readText() }
            val ip = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val country = Regex("\"country\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            when {
                ip == null -> null
                !country.isNullOrBlank() -> "$ip · $country"
                else -> ip
            }
        }.getOrNull()
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        val connected = status is VpnStatus.Connected
        _isConnected.value = connected
        // Drive the home-screen uptime timer: stamp the real connection start once, clear on any
        // non-connected state. Keep an existing stamp across redundant Connected updates so the timer
        // doesn't reset mid-session.
        _connectedSinceEpochMs.value = when {
            connected && _connectedSinceEpochMs.value == 0L -> System.currentTimeMillis()
            connected -> _connectedSinceEpochMs.value
            else -> 0L
        }
    }

    /**
     * Auto-reconnect watchdog: while connected, polls that the active engine is still alive and, if it
     * died unexpectedly (server dropped, core crashed), restarts the connection. A manual stop bumps
     * [generation] and cancels this job, so it never fights an intentional disconnect.
     */
    private fun startWatchdog(generationAtConnect: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(WATCHDOG_GRACE_MS)
            while (isActive && generationAtConnect == generation) {
                delay(WATCHDOG_INTERVAL_MS)
                if (generationAtConnect != generation) break
                if (_status.value !is VpnStatus.Connected) continue
                val alive = runCatching { isActiveEngineAlive() }.getOrDefault(true)
                if (!alive) {
                    addLog("Watchdog: tunnel stopped unexpectedly — reconnecting…")
                    startVpn() // bumps generation → ends this watchdog; reconnect starts a fresh one
                    break
                }
            }
        }
    }

    private fun isActiveEngineAlive(): Boolean {
        val loc = engineLocation
        return if (loc != null) engineController.coreRunning(loc.engine)
        else process?.isAlive == true
    }

    private fun addLog(message: String) {
        _logs.update {
            (it + message).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 5_000
        const val WATCHDOG_GRACE_MS = 8_000L
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4

        // Ping probes (same values as AndroidVpnManager).
        const val TCP_PING_ATTEMPTS = 2
        const val TCP_PING_TIMEOUT_MS = 3_000
        const val TUNNEL_PROBE_HOST = "1.1.1.1"
        const val TUNNEL_PROBE_PORT = 443
        const val TUNNEL_PING_TIMEOUT_MS = 6_000

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                    "join room failed" in text ||
                    "get room token" in text && "failed" in text ||
                    "transport connect" in text && "failed" in text
        }
    }
}
