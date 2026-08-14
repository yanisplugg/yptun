package org.olcbox.app.vpn.telegram

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.vpn.desktop.YpTunCore
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Desktop port of Android's TelegramProxyService: runs a WARP AmneziaWG tunnel and exposes a local
 * SOCKS5 at [LISTEN_HOST]:[LISTEN_PORT] for Telegram to point at.
 *
 * This is a FULL-TUNNEL proxy — everything the SOCKS receives rides WARP, exactly like running the
 * WARP config in TUN mode, only without a TUN (no admin, no adapter). It runs on its OWN AmneziaWG
 * instance inside yptuncore (YpTgAwg*), independent of the main VPN, so the two never collide and the
 * Telegram proxy keeps working whether or not the main tunnel is connected.
 *
 * Differences from Android: no foreground service / notification / Doze handling, and no
 * VpnService.protect() — on desktop the main tunnel's TUN would otherwise swallow the WARP UDP, so
 * DesktopVpnManager adds a bypass route for the active WARP endpoint instead.
 */
class DesktopTelegramProxy(
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<TelegramProxyState>(TelegramProxyState.Stopped)
    val state: StateFlow<TelegramProxyState> = _state.asStateFlow()

    private var healthJob: Job? = null
    // The in-flight enable. A full endpoint sweep can run for a minute, so [stop] cancels this BEFORE
    // taking the mutex — otherwise switching the toggle off mid-sweep appeared to hang the UI.
    private var startJob: Job? = null
    private var running = false

    /** The WARP endpoint the live tunnel uses — DesktopVpnManager routes it around the main TUN. */
    @Volatile
    var activeEndpoint: String? = null
        private set

    /** Set by the core's log bus when the tunnel degrades, so the health loop reacts in seconds. */
    @Volatile
    private var degraded = false

    init {
        YpTunCore.logSinks.add(::onCoreLog)
    }

    private fun onCoreLog(line: String) {
        if (!line.startsWith("tgwarp:")) return
        val t = line.removePrefix("tgwarp:").trim()
        if (t.isEmpty()) return
        if (t.contains("operation not permitted")) {
            // amneziawg spams EPERM dozens of times/sec — surface only the first, drop repeats.
            val first = !degraded
            degraded = true
            if (first) log("Telegram: UDP send blocked (EPERM) — will rotate/restart")
            return
        }
        if (t.contains("stopped hearing back") || t.contains("Handshake did not complete")) {
            degraded = true
            log("Telegram: $t")
            return
        }
        if (LOG_NOISE.any { t.contains(it) }) return
        log("Telegram: $t")
    }

    /** Turns the proxy on. Generates a WARP account on first use (needs internet), then caches it. */
    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            mutex.withLock {
                if (running) return@withLock
                running = true
                try {
                    val cached = TelegramProxyStore.loadConfig()
                    val baseConfig = cached ?: run {
                        _state.value = TelegramProxyState.Generating
                        log("Telegram proxy: generating a WARP account…")
                        val generated = runCatching { WarpConfigGenerator.generate(log) }.getOrElse { e ->
                            running = false
                            val message = e.message ?: "WARP config generation failed"
                            log("Telegram proxy: $message")
                            _state.value = TelegramProxyState.Error(message)
                            return@withLock
                        }
                        TelegramProxyStore.saveConfig(generated)
                        generated
                    }
                    val creds = TelegramProxyStore.getOrCreateCredentials()
                    // Try once now, but run the health loop EITHER WAY: if the network isn't ready yet
                    // the loop keeps retrying instead of leaving the proxy dead until re-toggled.
                    if (!bringUp(baseConfig, creds)) {
                        log("Telegram: first connect didn't land — watchdog will keep retrying")
                        _state.value = TelegramProxyState.Error("no working WARP relay yet — retrying")
                    }
                    startHealthLoop(baseConfig, creds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Toggled off mid-sweep — leave nothing half-started behind.
                    running = false
                    runCatching { YpTunCore.tgAwgStop() }
                    throw e
                }
            }
        }
    }

    fun stop() {
        // Cancel the enable FIRST, outside the mutex: a full sweep holds the lock for up to a minute,
        // and waiting it out made the toggle look stuck.
        startJob?.cancel()
        startJob = null
        scope.launch {
            mutex.withLock {
                healthJob?.cancel()
                healthJob = null
                running = false
                activeEndpoint = null
                runCatching { YpTunCore.tgAwgStop() }
                _state.value = TelegramProxyState.Stopped
                log("Telegram proxy: stopped")
            }
        }
    }

    fun close() {
        YpTunCore.logSinks.remove(::onCoreLog)
        runCatching { YpTunCore.tgAwgStop() }
        scope.cancel()
    }

    /**
     * Sweeps the WARP endpoints and keeps the first that actually reaches a Telegram DC. WARP is
     * anycast, so the SAME generated account works on every endpoint — we only vary IP+port to dodge
     * the throttling/DPI on the default engage.cloudflareclient.com:2408, the usual reason WARP
     * "connects" but moves no data. The working endpoint is cached so the next start is instant.
     */
    private suspend fun bringUp(
        baseConfig: String,
        creds: TelegramProxyStore.Credentials,
        avoid: Set<String> = emptySet(),
    ): Boolean {
        val cachedEp = extractEndpoint(baseConfig)
        val endpoints = (listOfNotNull(cachedEp) + WARP_FALLBACK_ENDPOINTS).distinct().filterNot { it in avoid }
        if (endpoints.isEmpty()) return false
        log(
            "Telegram proxy: trying ${endpoints.size} relay(s)" +
                (if (avoid.isNotEmpty()) " (rotating past ${avoid.size} dead)" else "") +
                " — verifying via Telegram DC"
        )

        for ((i, ep) in endpoints.withIndex()) {
            // Force a SHORT keepalive: WARP drops an idle session in ~15s, faster than the config's
            // PersistentKeepalive=25, and this SOCKS tunnel is mostly idle between Telegram bursts.
            val cfg = forceKeepalive(rewriteEndpoint(baseConfig, ep), 10)
            log("Telegram: [${i + 1}/${endpoints.size}] connecting via $ep…")
            // The previous instance's listener is freed asynchronously by the Go side; starting the
            // next one too soon fails every rotation with "bind: address already in use".
            awaitPortFree(LISTEN_PORT)
            val started = runCatching {
                YpTunCore.tgAwgStart(cfg, "$LISTEN_HOST:$LISTEN_PORT", creds.user, creds.pass)
            }.onFailure { log("Telegram: start failed on $ep — ${it.message ?: it}") }.isSuccess
            if (!started) {
                runCatching { YpTunCore.tgAwgStop() }
                continue
            }

            if (telegramReachableThroughWarp(creds)) {
                activeEndpoint = ep
                degraded = false
                _state.value = TelegramProxyState.Running(LISTEN_HOST, LISTEN_PORT, creds.user, creds.pass)
                log("Telegram: ✅ working via $ep — SOCKS5 $LISTEN_HOST:$LISTEN_PORT, point Telegram here")
                // Remember the endpoint that genuinely carries traffic, so the next launch starts here
                // instead of the throttled default. (A 15s-then-dead endpoint never gets this far.)
                if (ep != cachedEp) {
                    TelegramProxyStore.saveConfig(rewriteEndpoint(baseConfig, ep))
                    log("Telegram: cached working endpoint $ep")
                }
                return true
            }

            log("Telegram: $ep handshake up but NO traffic to Telegram — trying next")
            runCatching { YpTunCore.tgAwgStop() }
        }

        activeEndpoint = null
        log("Telegram: ❌ all ${endpoints.size} relays unreachable for Telegram on this network")
        return false
    }

    /**
     * Keeps the tunnel alive for as long as the proxy is enabled — it NEVER permanently gives up.
     * The AmneziaWG UDP socket gets EPERM when the underlying network changes; a fresh bring-up
     * re-opens it. While DOWN we retry with a capped backoff instead of exiting.
     */
    private fun startHealthLoop(baseConfig: String, creds: TelegramProxyStore.Credentials) {
        healthJob?.cancel()
        degraded = false
        healthJob = scope.launch {
            var consecutiveFails = 0
            var sinceVerifyMs = 0L
            val dead = linkedSetOf<String>() // endpoints that died under us — rotate away from them
            var lastWallMs = System.currentTimeMillis()
            var downWaitMs = 0L

            suspend fun reviveTunnel(): Boolean {
                var ok = bringUp(baseConfig, creds, avoid = dead)
                if (!ok && dead.isNotEmpty()) {
                    dead.clear()
                    ok = bringUp(baseConfig, creds)
                }
                if (ok) {
                    dead.clear(); downWaitMs = 0; consecutiveFails = 0; sinceVerifyMs = 0
                }
                return ok
            }

            while (isActive) {
                delay(HEALTH_TICK_MS)
                // A wall-clock gap >> the tick means the machine was suspended: the handshake is stale
                // and the network may have changed. Treat it as a fresh chance — forget dead relays.
                val now = System.currentTimeMillis()
                val wake = (now - lastWallMs) > HEALTH_TICK_MS * 3
                lastWallMs = now
                if (wake) {
                    dead.clear(); downWaitMs = 0; consecutiveFails = 0
                    log("Telegram: woke from sleep — re-checking tunnel now")
                }

                // TUNNEL DOWN → keep trying to bring it back, with a capped backoff.
                if (!YpTunCore.tgAwgRunning()) {
                    if (!wake && downWaitMs > 0) {
                        downWaitMs -= HEALTH_TICK_MS
                        continue
                    }
                    if (!reviveTunnel()) {
                        downWaitMs = (if (downWaitMs <= 0) HEALTH_TICK_MS else downWaitMs * 2)
                            .coerceAtMost(DOWN_RETRY_MAX_MS)
                        log("Telegram: no working relay yet — retrying in ${downWaitMs / 1000}s")
                    }
                    continue
                }

                // TUNNEL UP → verify on a degrade signal, a wake, or the slow heartbeat.
                val sawDegrade = degraded.also { degraded = false }
                sinceVerifyMs += HEALTH_TICK_MS
                if (!sawDegrade && !wake && sinceVerifyMs < HEALTH_VERIFY_MS) continue
                sinceVerifyMs = 0

                if (telegramReachableThroughWarp(creds)) {
                    consecutiveFails = 0
                    continue
                }
                // A degrade signal or a wake is decisive — rotate at once; an ordinary mid-session blip
                // still waits for 2 fails to avoid churn.
                if (!sawDegrade && !wake && ++consecutiveFails < 2) continue
                consecutiveFails = 0
                activeEndpoint?.let { dead += it }
                log("Telegram: tunnel died — rotating relay")
                runCatching { YpTunCore.tgAwgStop() }
                _state.value = TelegramProxyState.Error("reconnecting…")
                if (!reviveTunnel()) downWaitMs = HEALTH_TICK_MS
            }
        }
    }

    /**
     * Decisive verdict that the tunnel actually CARRIES traffic: open the local SOCKS5, authenticate,
     * and CONNECT to a real Telegram DC (149.154.167.51:443). A success reply means the SYN/ACK
     * round-tripped through WARP. Times out fast so a dead endpoint doesn't stall the sweep.
     */
    private suspend fun telegramReachableThroughWarp(
        creds: TelegramProxyStore.Credentials,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(LISTEN_HOST, LISTEN_PORT), 3_000)
                s.soTimeout = SELF_CHECK_TIMEOUT_MS
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                // Greeting: offer no-auth + user/pass.
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)); out.flush()
                val m = ByteArray(2)
                if (!readFully(inp, m) || m[0] != 0x05.toByte()) return@runCatching false
                if (m[1] == 0x02.toByte()) {
                    val u = creds.user.toByteArray()
                    val p = creds.pass.toByteArray()
                    val auth = ByteArray(3 + u.size + p.size)
                    auth[0] = 0x01; auth[1] = u.size.toByte(); u.copyInto(auth, 2)
                    auth[2 + u.size] = p.size.toByte(); p.copyInto(auth, 3 + u.size)
                    out.write(auth); out.flush()
                    val ar = ByteArray(2)
                    if (!readFully(inp, ar) || ar[1] != 0x00.toByte()) return@runCatching false
                } else if (m[1] != 0x00.toByte()) {
                    return@runCatching false
                }
                // CONNECT 149.154.167.51:443 (ATYP=IPv4).
                out.write(
                    byteArrayOf(
                        0x05, 0x01, 0x00, 0x01,
                        149.toByte(), 154.toByte(), 167.toByte(), 51.toByte(),
                        0x01, 0xBB.toByte()
                    )
                )
                out.flush()
                val rep = ByteArray(4)
                readFully(inp, rep) && rep[1] == 0x00.toByte()
            }
        }.getOrDefault(false)
    }

    private fun readFully(inp: java.io.InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) {
            val r = inp.read(b, n, b.size - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    /** Replaces (or appends) the `Endpoint = …` line in a WARP INI so the same account uses [endpoint]. */
    private fun rewriteEndpoint(config: String, endpoint: String): String {
        if (!config.contains("Endpoint", ignoreCase = true)) return "$config\nEndpoint = $endpoint"
        return config.lineSequence().joinToString("\n") {
            if (it.trimStart().startsWith("Endpoint", ignoreCase = true)) "Endpoint = $endpoint" else it
        }
    }

    private fun extractEndpoint(config: String): String? =
        config.lineSequence().firstOrNull { it.trimStart().startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }

    /** Sets `PersistentKeepalive` to [seconds] in a WARP INI (replacing or inserting it under [Peer]). */
    private fun forceKeepalive(config: String, seconds: Int): String {
        if (config.contains("PersistentKeepalive", ignoreCase = true)) {
            return config.lineSequence().joinToString("\n") {
                if (it.trimStart().startsWith("PersistentKeepalive", ignoreCase = true)) {
                    "PersistentKeepalive = $seconds"
                } else it
            }
        }
        return if (config.contains("[Peer]")) {
            config.replace("[Peer]", "[Peer]\nPersistentKeepalive = $seconds")
        } else {
            "$config\nPersistentKeepalive = $seconds"
        }
    }

    /** Blocks until [port] on loopback is bindable again (the prior listener has been torn down). */
    private suspend fun awaitPortFree(port: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val free = runCatching {
                ServerSocket().use { it.bind(InetSocketAddress(LISTEN_HOST, port)) }
                true
            }.getOrDefault(false)
            if (free) return
            delay(120)
        }
    }

    companion object {
        const val LISTEN_HOST = "127.0.0.1"

        /**
         * Dedicated port, deliberately clear of the main VPN's local ports (10808 and its +1/+2
         * helpers) so the two never collide.
         */
        const val LISTEN_PORT = 12080

        private const val SELF_CHECK_TIMEOUT_MS = 6_000
        private const val HEALTH_TICK_MS = 4_000L
        private const val HEALTH_VERIFY_MS = 30_000L
        private const val DOWN_RETRY_MAX_MS = 60_000L

        /** amneziawg-go internal per-goroutine churn — pure noise in the in-app journal. */
        private val LOG_NOISE = listOf(
            "Routine:", "UAPI:", "UDP bind has been updated", "Interface up requested",
            "Interface state was", "transport packet lined up", "Device closing", "Device closed",
            "- Starting", "- Stopping", "Sending keepalive packet", "Adding allowedip",
        )

        /**
         * WARP endpoints to try, in order, until one carries traffic to Telegram. WARP is anycast so
         * the SAME account works on every one — we only vary IP+port to dodge the throttling/DPI on
         * the default engage.cloudflareclient.com:2408. The cached/working endpoint is tried first.
         */
        private val WARP_FALLBACK_ENDPOINTS = listOf(
            // Proven-working endpoint+port from a real DPI network (port 894, NOT 2408).
            "162.159.192.8:894",
            "engage.cloudflareclient.com:2408",
            "162.159.192.1:894",
            "162.159.192.8:2408",
            "162.159.192.1:2408",
            "162.159.193.10:2408",
            "188.114.96.1:2408",
            "188.114.97.1:2408",
            "162.159.192.1:945",
            "162.159.193.10:4500",
            "188.114.96.1:1701",
            "188.114.97.1:955",
        )

        /** Endpoint hosts that must never be routed into the main TUN (see DesktopVpnManager). */
        fun candidateEndpointHosts(): List<String> =
            WARP_FALLBACK_ENDPOINTS.map { it.substringBeforeLast(':') }
    }
}
