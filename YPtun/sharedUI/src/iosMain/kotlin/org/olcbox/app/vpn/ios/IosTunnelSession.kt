package org.olcbox.app.vpn.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ios.IosCoreBridge
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData
import platform.NetworkExtension.NEDNSSettings
import platform.NetworkExtension.NEIPv4Route
import platform.NetworkExtension.NEIPv4Settings
import platform.NetworkExtension.NEIPv6Route
import platform.NetworkExtension.NEIPv6Settings
import platform.NetworkExtension.NEPacketTunnelNetworkSettings
import platform.NetworkExtension.NEPacketTunnelProvider
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * What the app hands the extension for one connect, written to the App Group container right before
 * `startVPNTunnel` — so a start from iOS Settings / Control Center reuses the last choice.
 */
@Serializable
data class IosTunnelRequest(
    val location: LocationConfig,
    val deviceId: String,
)

/**
 * The packet-tunnel extension's Kotlin half. Swift's PacketTunnelProvider owns only what Kotlin can't
 * reach from a non-Mac build: the gomobile cores ([IosCoreBridge]) and hev-socks5-tunnel. Everything
 * else — picking and starting the cores, the tunnel's addresses/routes/DNS, the bridge config — is here.
 *
 * Topology = Android's: every engine leaves a SOCKS5 on 127.0.0.1:[SOCKS_PORT]; hev reads the utun and
 * feeds it into that SOCKS, answering DNS itself from a mapped pool (mapdns) so names reach the core.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTunnelSession(
    private val core: IosCoreBridge,
    private val provider: NEPacketTunnelProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = IosEngineController(core) { log(it) }
    private var watchdog: Job? = null
    private var engineType: EngineType? = null

    /**
     * Starts the cores and applies the tunnel settings; [completion] gets the hev-socks5-tunnel YAML to
     * run, or an error text (then nothing is left running).
     */
    fun start(completion: (hevConfig: String?, error: String?) -> Unit) {
        IosSharedStore.writeText(LOG_FILE, "")
        startLogPump()
        scope.launch {
            val result = runCatching {
                val request = IosSharedStore.readText(REQUEST_FILE)
                    ?.let { json.decodeFromString(IosTunnelRequest.serializer(), it) }
                    ?: error("Нет активной локации — выберите её в приложении")
                val location = request.location.normalized()
                check(location.isComplete()) { "Локация настроена не полностью" }
                log("Connecting ${location.displayName()} (engine=${location.engine})")

                val user = credential(12)
                val pass = credential(24)
                engine.start(location, SOCKS_PORT, user, pass, request.deviceId)
                engineType = location.engine

                val traffic = IosSharedStore.loadTraffic()
                val bypassLan = IosSharedStore.loadRouting().bypassLan
                applyNetworkSettings(traffic.mtu, bypassLan)
                log("Tunnel settings applied (mtu=${traffic.mtu}, bypassLan=$bypassLan)")
                hevConfig(
                    mtu = traffic.mtu,
                    user = user,
                    pass = pass,
                    tcpOnlyUdp = location.engine in TCP_ONLY_ENGINES,
                    dropIpv6 = traffic.domainStrategy.let { it == "ipv4_only" || it == "prefer_ipv4" },
                    slowTunnel = location.engine in SLOW_ENGINES,
                )
            }
            result.onSuccess {
                startWatchdog()
                completion(it, null)
            }.onFailure {
                val message = it.message ?: "Не удалось подключиться"
                log("Connect failed: $message")
                IosSharedStore.writeText(ERROR_FILE, message)
                engine.stopAll()
                completion(null, message)
            }
        }
    }

    fun stop() {
        log("Stopping")
        watchdog?.cancel()
        engine.stopAll()
        scope.cancel()
    }

    /**
     * App → extension request/response (NETunnelProviderSession.sendProviderMessage): "status" returns
     * the pending VK captcha URL (empty when none); "wdtt-captcha:<token>" hands a solved WDTT captcha
     * to the core.
     */
    fun handleAppMessage(message: String): String = when {
        message == "status" -> engine.pendingCaptchaUrl
        message.startsWith("wdtt-captcha:") -> {
            core.wdttPushCaptcha(message.removePrefix("wdtt-captcha:"))
            ""
        }
        else -> ""
    }

    private suspend fun applyNetworkSettings(mtu: Int, bypassLan: Boolean) {
        val settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress = "127.0.0.1")
        settings.setMTU(NSNumber(int = mtu))
        settings.setIPv4Settings(
            NEIPv4Settings(addresses = listOf(TUN_IPV4_ADDRESS), subnetMasks = listOf("255.255.255.0")).apply {
                setIncludedRoutes(listOf(NEIPv4Route.defaultRoute()))
                // "Обход LAN": private ranges, multicast and broadcast stay on the real network, so local
                // discovery works — the mapped-DNS pool 100.64/10 is deliberately not among them.
                if (bypassLan) {
                    setExcludedRoutes(LAN_ROUTES.map { (addr, mask) -> NEIPv4Route(destinationAddress = addr, subnetMask = mask) })
                }
            }
        )
        // Always capture IPv6 so raw IPv6 can't leak past the tunnel; hev refuses it when IPv4 is
        // forced, which blackholes it instead.
        settings.setIPv6Settings(
            NEIPv6Settings(addresses = listOf(TUN_IPV6_ADDRESS), networkPrefixLengths = listOf(NSNumber(int = 126))).apply {
                setIncludedRoutes(listOf(NEIPv6Route.defaultRoute()))
            }
        )
        settings.setDNSSettings(
            NEDNSSettings(servers = listOf(MAPDNS_ADDRESS)).apply { setMatchDomains(listOf("")) }
        )
        val error = suspendCancellableCoroutine { cont ->
            provider.setTunnelNetworkSettings(settings) { err -> cont.resume(err?.localizedDescription) }
        }
        if (error != null) throw IllegalStateException("Не удалось применить настройки туннеля: $error")
    }

    private fun hevConfig(
        mtu: Int,
        user: String,
        pass: String,
        tcpOnlyUdp: Boolean,
        dropIpv6: Boolean,
        slowTunnel: Boolean,
    ): String = """
        tunnel:
          mtu: $mtu
          ipv4: $TUN_IPV4_ADDRESS
          ${if (dropIpv6) "# ipv6 disabled (IPv4 only/preferred)" else "ipv6: '$TUN_IPV6_ADDRESS'"}

        socks5:
          address: 127.0.0.1
          port: $SOCKS_PORT
          udp: '${if (tcpOnlyUdp) "tcp" else "udp"}'
          pipeline: false
          username: '$user'
          password: '$pass'

        mapdns:
          address: $MAPDNS_ADDRESS
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000

        misc:
          task-stack-size: 24576
          tcp-buffer-size: 4096
          max-session-count: 1200
          connect-timeout: ${if (slowTunnel) 30000 else 10000}
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-level: warn
    """.trimIndent()

    /** A dead core means a tunnel that carries nothing — drop it so iOS shows "disconnected". */
    private fun startWatchdog() {
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val type = engineType ?: continue
                if (!engine.coreRunning(type)) {
                    log("Core stopped unexpectedly — closing the tunnel")
                    engine.stopAll()
                    provider.cancelTunnelWithError(null)
                    return@launch
                }
            }
        }
    }

    private fun startLogPump() {
        scope.launch {
            while (isActive) {
                val line = core.pollLog(1_000)
                if (line.isNotEmpty()) log(line)
            }
        }
    }

    private fun log(line: String) {
        val path = IosSharedStore.path(LOG_FILE)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) IosSharedStore.writeText(LOG_FILE, "")
        val data: NSData = NSString.create(string = "$line\n").dataUsingEncoding(NSUTF8StringEncoding) ?: return
        NSFileHandle.fileHandleForWritingAtPath(path)?.let { handle ->
            handle.seekToEndOfFile()
            handle.writeData(data)
            handle.closeFile()
        }
    }

    private fun credential(length: Int): String =
        buildString(length) { repeat(length) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } }

    companion object {
        const val REQUEST_FILE = "tunnel_request.json"
        const val LOG_FILE = "tunnel.log"
        /** Why the last connect failed ("" after a good one) — the app shows it as the error. */
        const val ERROR_FILE = "tunnel_error.txt"

        private const val SOCKS_PORT = 10808
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1"
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        /** Engines whose SOCKS carries TCP only: hev tunnels UDP over TCP for them. */
        private val TCP_ONLY_ENGINES = setOf(EngineType.Stealth, EngineType.MasterDns, EngineType.OpenFlux)
        private val SLOW_ENGINES = setOf(EngineType.MasterDns, EngineType.OpenFlux)

        private val LAN_ROUTES = listOf(
            "10.0.0.0" to "255.0.0.0",
            "127.0.0.0" to "255.0.0.0",
            "169.254.0.0" to "255.255.0.0",
            "172.16.0.0" to "255.240.0.0",
            "192.168.0.0" to "255.255.0.0",
            "224.0.0.0" to "240.0.0.0",
            "255.255.255.255" to "255.255.255.255",
        )

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}
