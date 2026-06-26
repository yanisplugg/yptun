package org.olcbox.app.vpn.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import awg.Awg
import awg.Instance
import awg.LogWriter as AwgLogWriter
import awg.Protector as AwgProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import org.olcbox.app.vpn.data.KEY_ANDROID_APP_BEHAVIOR
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.net.Socket

/**
 * Lightweight always-on foreground service that runs a WARP AmneziaWG tunnel and exposes a local
 * SOCKS5 at [LISTEN_HOST]:[LISTEN_PORT]. The user points Telegram's SOCKS5 proxy at it. This is a
 * FULL-TUNNEL proxy: everything the SOCKS receives rides WARP, exactly like running the WARP config
 * in TUN/VPN mode — only without an Android VpnService/TUN (no "VPN key" icon). Runs INDEPENDENTLY
 * of the main VpnService (its own [Awg.newInstance] instance, own port), so it never collides with a
 * main-VPN AmneziaWG transport, and protects its WARP socket through the main VpnService when one is
 * active (see [VpnSocketProtectBridge]).
 */
class TelegramProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var instance: Instance? = null
    // Guards against the double-start race: two startForegroundService() calls deliver two
    // onStartCommand()s before [instance] is set, which used to spawn two endpoint sweeps fighting
    // over port 12080 ("address already in use"). Set synchronously on the main thread here.
    @Volatile private var starting = false
    private var healthJob: Job? = null
    // Set by the AmneziaWG log writer the moment the tunnel degrades — EPERM on the UDP socket
    // ("operation not permitted"), OR the endpoint going silent ("stopped hearing back" /
    // "Handshake did not complete"), which is how a throttled WARP endpoint dies after letting the
    // first handshake through. Lets the health loop react in seconds and rotate to another endpoint.
    @Volatile private var degraded = false
    // Set when the default network changes (Wi-Fi⇄cellular, or connectivity returning after Doze): the
    // WARP UDP socket is bound to the old handle and the health loop should re-verify/rotate AT ONCE
    // rather than wait out its slow heartbeat (the cause of the long reconnect after the phone sleeps).
    @Volatile private var networkKicked = false
    // The WARP endpoint the live tunnel is using, so the watchdog can rotate AWAY from it on failure.
    @Volatile private var activeEndpoint: String? = null

    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Watch the default network so we can recover the WARP tunnel the instant connectivity returns
        // after sleep (or flips Wi-Fi⇄cellular) instead of waiting for the next slow health heartbeat.
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                // A new default network (connectivity back after Doze, or Wi-Fi⇄cellular) or losing one
                // both mean the WARP socket's underlying handle changed — kick an immediate re-verify.
                // (Deliberately NOT onCapabilitiesChanged: it fires on every signal/validation blip.)
                override fun onAvailable(network: android.net.Network) { networkKicked = true }
                override fun onLost(network: android.net.Network) { networkKicked = true }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                // API 23: registerDefaultNetworkCallback doesn't exist — watch the INTERNET-capable network.
                val req = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
            connectivityManager = cm
            networkCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Stop" tapped in the notification: turn the toggle OFF (persisted → the manager's live
            // datastore collector reflects it in the UI and it won't auto-restart) and tear down.
            stopFromNotification()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (instance == null && !starting) {
            starting = true
            startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        scope.launch {
            try {
                val ds = LocationsDataSourceImpl(applicationContext)
                val baseConfig = runCatching { ds.loadTelegramWarpConfig() }.getOrNull()
                if (baseConfig.isNullOrBlank()) {
                    Log.w(TAG, "No cached WARP config — stopping Telegram proxy")
                    OlcboxVpnState.addLog("Telegram proxy: no config to start — stopping")
                    stopSelfSafely()
                    return@launch
                }
                val creds = runCatching { TelegramProxyCreds.getOrCreate(applicationContext) }.getOrNull()
                if (bringUp(ds, baseConfig, creds)) {
                    startHealthLoop(ds, baseConfig, creds)
                } else {
                    stopSelfSafely()
                }
            } finally {
                starting = false
            }
        }
    }

    /**
     * Sweeps the WARP endpoints and keeps the first that actually reaches a Telegram DC. WARP is
     * anycast so the SAME generated account works on every endpoint IP/port — we only vary IP+port to
     * dodge throttling/DPI on the default engage.cloudflareclient.com:2408 (the usual reason WARP
     * "connects" but moves no data). On success [instance] is left running and the working config is
     * cached so next start is instant. Returns whether a working endpoint was found.
     */
    private suspend fun bringUp(
        ds: LocationsDataSourceImpl,
        baseConfig: String,
        creds: TelegramProxyCreds.Credentials?,
        avoid: Set<String> = emptySet()
    ): Boolean {
        val cachedEp = extractEndpoint(baseConfig)
        val endpoints = (listOfNotNull(cachedEp) + WARP_FALLBACK_ENDPOINTS).distinct().filterNot { it in avoid }
        if (endpoints.isEmpty()) return false
        OlcboxVpnState.addLog(
            "Telegram proxy: trying ${endpoints.size} relay(s)" +
                (if (avoid.isNotEmpty()) " (rotating past ${avoid.size} dead)" else "") +
                " — verifying via Telegram DC"
        )

        for ((i, ep) in endpoints.withIndex()) {
            // Force a SHORT keepalive: WARP works in TUN mode (constant traffic) but here the SOCKS
            // tunnel is mostly idle, and WARP drops an idle session in ~15s — faster than the config's
            // PersistentKeepalive=25. A 10s keepalive keeps the session warm so it doesn't die between
            // Telegram bursts. (No effect on the working TUN path, which is never idle.)
            val cfg = forceKeepalive(rewriteEndpoint(baseConfig, ep), 10)
            OlcboxVpnState.addLog("Telegram: [${i + 1}/${endpoints.size}] connecting via $ep…")
            // A previous instance's SOCKS listener on 12080 is freed ASYNCHRONOUSLY by the Go side, so
            // starting the next one too soon failed every rotation with "bind: address already in use".
            // Wait for the port to actually free first.
            awaitPortFree(LISTEN_PORT)
            val inst = buildInstance(creds)
            val started = runCatching { inst.start(cfg, "$LISTEN_HOST:$LISTEN_PORT") }
                .onFailure { OlcboxVpnState.addLog("Telegram: start failed on $ep — ${it.message ?: it}") }
                .isSuccess
            if (!started) { runCatching { inst.stop() }; continue }
            instance = inst

            if (telegramReachableThroughWarp(creds)) {
                activeEndpoint = ep
                degraded = false
                OlcboxVpnState.addLog("Telegram: ✅ working via $ep — SOCKS5 $LISTEN_HOST:$LISTEN_PORT, point Telegram here")
                return true
            }

            OlcboxVpnState.addLog("Telegram: $ep handshake up but NO traffic to Telegram — trying next")
            runCatching { inst.stop() }
            instance = null
        }

        OlcboxVpnState.addLog("Telegram: ❌ all ${endpoints.size} relays unreachable for Telegram on this network")
        return false
    }

    /**
     * Periodically re-verifies the tunnel and auto-restarts it when sends start failing. The AmneziaWG
     * UDP socket gets EPERM ("sendmsg: operation not permitted") when the underlying network handle is
     * swapped out from under it — frequent when the main proxy holds a process-wide bindProcessToNetwork
     * and the device flaps Wi-Fi. A fresh [bringUp] re-opens the socket on the now-current network and
     * recovers. Capped restarts so a genuinely-blocked network doesn't spin forever.
     */
    private fun startHealthLoop(
        ds: LocationsDataSourceImpl,
        baseConfig: String,
        creds: TelegramProxyCreds.Credentials?
    ) {
        healthJob?.cancel()
        degraded = false
        networkKicked = false
        healthJob = scope.launch {
            var consecutiveFails = 0
            var restarts = 0
            var sinceVerifyMs = 0L
            var persistedEp = extractEndpoint(baseConfig)
            val dead = linkedSetOf<String>() // endpoints that died under us — rotate away from them
            var lastWallMs = System.currentTimeMillis()
            while (isActive && instance != null) {
                delay(HEALTH_TICK_MS)
                if (instance == null) break
                // Wall-clock gap >> the tick means the device was frozen in Doze / deep sleep: the WARP
                // handshake is now stale (and the underlying network may have swapped). That's the slow
                // post-sleep reconnect — the loop used to wait out the full HEALTH_VERIFY_MS heartbeat AND
                // need two failures before rotating. On wake (or a network change) re-verify AT ONCE and
                // treat a single failure as decisive, so recovery is seconds not a minute+.
                val now = System.currentTimeMillis()
                val wokeFromSleep = (now - lastWallMs) > HEALTH_TICK_MS * 3
                lastWallMs = now
                val kicked = networkKicked.also { networkKicked = false }
                val wake = wokeFromSleep || kicked
                val sawDegrade = degraded.also { degraded = false }
                sinceVerifyMs += HEALTH_TICK_MS
                // Only spend a probe when something looks wrong, on wake/network-change, or on the slow heartbeat.
                if (!sawDegrade && !wake && sinceVerifyMs < HEALTH_VERIFY_MS) continue
                sinceVerifyMs = 0
                if (wake) OlcboxVpnState.addLog(
                    "Telegram: ${if (wokeFromSleep) "woke from sleep" else "network changed"} — re-verifying tunnel now"
                )

                if (telegramReachableThroughWarp(creds)) {
                    consecutiveFails = 0
                    // The current endpoint is genuinely carrying traffic — remember it so next launch
                    // starts here instead of the throttled default. (A 15s-then-dead endpoint never
                    // reaches this point, so it never gets cached.)
                    val ep = activeEndpoint
                    if (ep != null && ep != persistedEp) {
                        runCatching { ds.saveTelegramWarpConfig(rewriteEndpoint(baseConfig, ep)) }
                        persistedEp = ep
                        OlcboxVpnState.addLog("Telegram: cached working endpoint $ep")
                    }
                    continue
                }
                // A degrade signal (EPERM / silent handshake), a wake, or a network change is decisive —
                // rotate at once; an ordinary mid-session blip still waits for 2 fails to avoid churn.
                if (!sawDegrade && !wake && ++consecutiveFails < 2) continue
                if (restarts >= MAX_HEALTH_RESTARTS) {
                    OlcboxVpnState.addLog("Telegram: relay keeps dying after $restarts tries — likely throttled on this network. Re-toggle to retry.")
                    break
                }
                restarts++; consecutiveFails = 0
                activeEndpoint?.let { dead += it }
                OlcboxVpnState.addLog("Telegram: tunnel died — rotating to another relay [$restarts/$MAX_HEALTH_RESTARTS]")
                runCatching { instance?.stop() }; instance = null
                var ok = bringUp(ds, baseConfig, creds, avoid = dead)
                if (!ok && dead.isNotEmpty()) {
                    // Tried them all — reset and start over (the network may have recovered since).
                    dead.clear()
                    ok = bringUp(ds, baseConfig, creds)
                }
                if (!ok) break
            }
        }
    }

    private fun buildInstance(creds: TelegramProxyCreds.Credentials?): Instance =
        Awg.newInstance().apply {
            setDebug(false)
            // FULL-TUNNEL SOCKS: route EVERYTHING the proxy receives through WARP — identical to
            // running this WARP config in TUN/VPN mode, just exposed as a local SOCKS5 with no
            // VpnService/TUN (no "VPN key" icon). Deliberately NOT a Telegram-only split: the split
            // dialed non-Telegram destinations DIRECT, so the parts of Telegram that hit non-DC IPs
            // (and DNS) leaked onto the blocked network and timed out, which is why the same WARP
            // config "worked in TUN but not as a proxy". (No setSplitCIDRs → Go defaults to all-WARP.)
            // Require auto-generated username/password (RFC 1929) so no other local app can quietly
            // use the WARP proxy. The user types these into Telegram's SOCKS5 settings (shown in UI).
            if (creds != null) setAuth(creds.user, creds.pass)
            setLogWriter(object : AwgLogWriter {
                override fun writeLog(line: String) {
                    val t = line.trimEnd()
                    Log.v(TAG, t)
                    if (t.isEmpty()) return
                    if (t.contains("operation not permitted")) {
                        // amneziawg spams EPERM dozens of times/sec — surface only the first, drop repeats.
                        val first = !degraded
                        degraded = true
                        if (first) OlcboxVpnState.addLog("Telegram: UDP send blocked (EPERM) — will rotate/restart")
                        return
                    }
                    if (t.contains("stopped hearing back") || t.contains("Handshake did not complete")) {
                        degraded = true
                        OlcboxVpnState.addLog("Telegram: $t")
                        return
                    }
                    // Drop amneziawg's internal per-routine churn — it floods the journal (1800+ lines).
                    // Keep only the meaningful lines (handshake result, endpoint, working/failed, errors).
                    if (TG_LOG_NOISE.any { t.contains(it) }) return
                    OlcboxVpnState.addLog("Telegram: $t")
                }
            })
            // Protect the WARP UDP socket through the main VpnService if its TUN is up (else no-op).
            setProtector(object : AwgProtector {
                override fun protect(fd: Long): Boolean {
                    return VpnSocketProtectBridge.protect?.invoke(fd.toInt()) ?: true
                }
            })
        }

    /**
     * Decisive verdict that the WARP tunnel actually CARRIES traffic: open the local SOCKS5, authenticate,
     * and CONNECT to a real Telegram DC (149.154.167.51:443 — routed via WARP like everything in this
     * full-tunnel SOCKS). A success reply means the SYN/ACK round-tripped through WARP. Times out fast so a blocked
     * endpoint doesn't stall the endpoint sweep.
     */
    private fun telegramReachableThroughWarp(creds: TelegramProxyCreds.Credentials?): Boolean = runCatching {
        Socket().use { s ->
            s.connect(java.net.InetSocketAddress(LISTEN_HOST, LISTEN_PORT), 3_000)
            s.soTimeout = SELF_CHECK_TIMEOUT_MS
            val out = s.getOutputStream(); val inp = s.getInputStream()
            // Greeting: offer no-auth + user/pass.
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)); out.flush()
            val m = ByteArray(2); if (!readFully(inp, m) || m[0] != 0x05.toByte()) return false
            if (m[1] == 0x02.toByte()) {
                val u = (creds?.user ?: "").toByteArray(); val p = (creds?.pass ?: "").toByteArray()
                val auth = ByteArray(3 + u.size + p.size)
                auth[0] = 0x01; auth[1] = u.size.toByte(); System.arraycopy(u, 0, auth, 2, u.size)
                auth[2 + u.size] = p.size.toByte(); System.arraycopy(p, 0, auth, 3 + u.size, p.size)
                out.write(auth); out.flush()
                val ar = ByteArray(2); if (!readFully(inp, ar) || ar[1] != 0x00.toByte()) return false
            } else if (m[1] != 0x00.toByte()) {
                return false
            }
            // CONNECT 149.154.167.51:443 (ATYP=IPv4).
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 149.toByte(), 154.toByte(), 167.toByte(), 51.toByte(), 0x01, 0xBB.toByte()))
            out.flush()
            val rep = ByteArray(4)
            readFully(inp, rep) && rep[1] == 0x00.toByte()
        }
    }.getOrDefault(false)

    private fun readFully(inp: java.io.InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = inp.read(b, n, b.size - n); if (r < 0) return false; n += r }
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
        // No keepalive line — add one right after the [Peer] section header, else append.
        return if (config.contains("[Peer]")) {
            config.replace("[Peer]", "[Peer]\nPersistentKeepalive = $seconds")
        } else {
            "$config\nPersistentKeepalive = $seconds"
        }
    }

    /** Blocks until [port] on loopback is bindable again (the prior awg listener has been torn down). */
    private suspend fun awaitPortFree(port: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val free = runCatching {
                java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(LISTEN_HOST, port)) }
                true
            }.getOrDefault(false)
            if (free) return
            delay(120)
        }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** Persists telegramProxyEnabled=false (so it won't auto-restart + UI toggle follows), then stops. */
    private fun stopFromNotification() {
        OlcboxVpnState.addLog("Telegram proxy: stopped from notification")
        scope.launch {
            runCatching {
                applicationContext.vpnPrefDataStore.edit { prefs ->
                    val current = prefs[KEY_ANDROID_APP_BEHAVIOR]
                        ?.let { runCatching { Json.decodeFromString(AppBehaviorSettings.serializer(), it) }.getOrNull() }
                        ?: AppBehaviorSettings()
                    prefs[KEY_ANDROID_APP_BEHAVIOR] = Json.encodeToString(
                        AppBehaviorSettings.serializer(),
                        current.copy(telegramProxyEnabled = false)
                    )
                }
            }
            stopSelfSafely()
        }
    }

    override fun onDestroy() {
        healthJob?.cancel()
        healthJob = null
        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        connectivityManager = null
        runCatching { instance?.stop() }
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val s = stringsFor(LocalizationState.effective)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, s.telegramProxyTitle, NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val icon = resources.getIdentifier("ic_stat_yptun", "drawable", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock
        // One-tap "Stop" in the notification: stops the proxy and turns the toggle off.
        val stopIntent = Intent(this, TelegramProxyService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(s.telegramProxyNotifActive)
            .setContentText("$LISTEN_HOST:$LISTEN_PORT")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, s.notifStop, stopPending)
            .build()
    }

    companion object {
        const val LISTEN_HOST = "127.0.0.1"
        // Dedicated port, deliberately clear of the main VPN's local ports (socksListenPort 10808 and
        // its +1/+2/+3 helpers) so the two never collide.
        const val LISTEN_PORT = 12080
        private const val TAG = "TgWarpProxy"
        private const val CHANNEL_ID = "olcbox_tg_proxy"
        private const val NOTIFICATION_ID = 49_001
        private const val ACTION_STOP = "org.olcbox.app.telegram.STOP"

        // How long the Telegram-DC verification waits for the SOCKS CONNECT reply. Long enough for the
        // WARP handshake + a real round-trip, short enough that a dead endpoint doesn't stall the sweep.
        private const val SELF_CHECK_TIMEOUT_MS = 6_000

        // Health watchdog: poll on a short tick so an EPERM (flagged by the log writer) triggers a
        // restart within seconds; otherwise actively probe only every HEALTH_VERIFY_MS to avoid traffic.
        // Auto-restarts are capped so a genuinely-blocked network doesn't spin forever.
        private const val HEALTH_TICK_MS = 4_000L
        private const val HEALTH_VERIFY_MS = 30_000L
        private const val MAX_HEALTH_RESTARTS = 5

        // amneziawg-go internal per-goroutine churn — pure noise in the in-app journal. Meaningful lines
        // (handshake result, endpoint sweep, working/failed, errors) don't match any of these.
        private val TG_LOG_NOISE = listOf(
            "Routine:", "UAPI:", "UDP bind has been updated", "Interface up requested",
            "Interface state was", "transport packet lined up", "Device closing", "Device closed",
            "- Starting", "- Stopping", "Sending keepalive packet", "Adding allowedip",
        )

        /**
         * WARP endpoints to try, in order, until one carries traffic to Telegram. WARP is anycast so the
         * SAME generated account works on every one — we only vary IP+port to dodge the throttling/DPI on
         * the default engage.cloudflareclient.com:2408 (the usual reason WARP "connects" but moves no
         * data in RU). Mix of the canonical 162.159.192/193 + 188.114.96/97 ranges on alternate ports
         * that are commonly open. The cached/working endpoint is tried first (see startTunnel).
         */
        private val WARP_FALLBACK_ENDPOINTS = listOf(
            // Proven-working endpoint+port from a real DPI network (port 894, NOT 2408 which DPI often
            // throttles) — tried early so a fresh config lands on a port that actually carries data.
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

        fun start(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramProxyService::class.java))
        }
    }
}
