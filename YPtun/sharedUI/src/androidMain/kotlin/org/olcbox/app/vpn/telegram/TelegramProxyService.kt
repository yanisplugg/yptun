package org.olcbox.app.vpn.telegram

import android.app.AlarmManager
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

    /**
     * The user swiped the app out of Recents. A started foreground service should survive that, but
     * several OEMs (and the AOSP "task removed" path) kill the whole process anyway — which dropped the
     * SOCKS proxy. The toggle is still ON (we only stop on an explicit ACTION_STOP / notification Stop),
     * so schedule a prompt self-restart. START_STICKY is the fallback if this alarm is throttled.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            val restart = Intent(applicationContext, TelegramProxyService::class.java)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(applicationContext, 7, restart, flags)
            } else {
                PendingIntent.getService(applicationContext, 7, restart, flags)
            }
            val am = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // setAndAllowWhileIdle needs no SCHEDULE_EXACT_ALARM permission, fires within ~seconds even in
            // Doze, and grants a brief foreground-service-start allowance from its callback.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_000, pi)
        }
        super.onTaskRemoved(rootIntent)
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
                // Try once now, but run the health loop EITHER WAY — if the radio isn't ready yet or the
                // network is momentarily blocked at start, the loop keeps retrying instead of the service
                // giving up and stopping (which left the proxy permanently dead until re-toggled).
                if (!bringUp(ds, baseConfig, creds)) {
                    OlcboxVpnState.addLog("Telegram: first connect didn't land — watchdog will keep retrying")
                }
                startHealthLoop(ds, baseConfig, creds)
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
     * Keeps the WARP tunnel alive for the WHOLE lifetime of the service — it NEVER permanently gives up.
     * The AmneziaWG UDP socket gets EPERM ("sendmsg: operation not permitted") when the underlying network
     * handle is swapped (Wi-Fi⇄cellular, connectivity returning after Doze); a fresh [bringUp] re-opens it
     * on the now-current network. The old loop exited on the first failed bring-up and after a restart cap
     * ("отказывается реконнектиться") — so after sleep, if the network wasn't ready on the first try, the
     * proxy stayed dead until re-toggled. Now: while DOWN we keep retrying with a capped backoff, and a
     * wake/network-change resets everything and retries AT ONCE (fast reconnect).
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
            var sinceVerifyMs = 0L
            var persistedEp = extractEndpoint(baseConfig)
            val dead = linkedSetOf<String>() // endpoints that died under us — rotate away from them
            var lastWallMs = System.currentTimeMillis()
            var downWaitMs = 0L // backoff countdown between bring-up attempts while the tunnel is down

            // Brings the tunnel up, trying the dead-avoiding sweep first then a clean retry. Returns success.
            suspend fun reviveTunnel(): Boolean {
                var ok = bringUp(ds, baseConfig, creds, avoid = dead)
                if (!ok && dead.isNotEmpty()) { dead.clear(); ok = bringUp(ds, baseConfig, creds) }
                if (ok) { dead.clear(); downWaitMs = 0; consecutiveFails = 0; sinceVerifyMs = 0 }
                return ok
            }

            while (isActive) {
                delay(HEALTH_TICK_MS)
                // Wall-clock gap >> the tick means the device was frozen in Doze/deep sleep: the WARP
                // handshake is stale and the network may have swapped. Treat wake & network-change as a
                // fresh chance — forget dead relays and the backoff so we retry everything immediately.
                val now = System.currentTimeMillis()
                val wokeFromSleep = (now - lastWallMs) > HEALTH_TICK_MS * 3
                lastWallMs = now
                val kicked = networkKicked.also { networkKicked = false }
                val wake = wokeFromSleep || kicked
                if (wake) {
                    dead.clear(); downWaitMs = 0; consecutiveFails = 0
                    OlcboxVpnState.addLog(
                        "Telegram: ${if (wokeFromSleep) "woke from sleep" else "network changed"} — re-checking tunnel now"
                    )
                }

                // TUNNEL DOWN → keep trying to bring it back (never stay dead), with a capped backoff.
                if (instance == null) {
                    if (!wake && downWaitMs > 0) { downWaitMs -= HEALTH_TICK_MS; continue }
                    if (!reviveTunnel()) {
                        downWaitMs = (if (downWaitMs <= 0) HEALTH_TICK_MS else downWaitMs * 2)
                            .coerceAtMost(DOWN_RETRY_MAX_MS)
                        OlcboxVpnState.addLog("Telegram: no working relay yet — retrying in ${downWaitMs / 1000}s")
                    }
                    continue
                }

                // TUNNEL UP → verify on a degrade signal, a wake/network change, or the slow heartbeat.
                val sawDegrade = degraded.also { degraded = false }
                sinceVerifyMs += HEALTH_TICK_MS
                if (!sawDegrade && !wake && sinceVerifyMs < HEALTH_VERIFY_MS) continue
                sinceVerifyMs = 0

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
                consecutiveFails = 0
                activeEndpoint?.let { dead += it }
                OlcboxVpnState.addLog("Telegram: tunnel died — rotating relay")
                runCatching { instance?.stop() }; instance = null
                // Revive immediately (don't wait a tick) for a fast reconnect; the down-branch keeps
                // retrying on later ticks if this attempt didn't land.
                if (!reviveTunnel()) downWaitMs = HEALTH_TICK_MS
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
        private const val HEALTH_TICK_MS = 4_000L
        private const val HEALTH_VERIFY_MS = 30_000L
        // While the tunnel is DOWN the watchdog retries bring-up with an exponential backoff capped here —
        // it never permanently gives up (the proxy must keep trying to recover on its own), but on a truly
        // blocked network it backs off to one full relay sweep per minute instead of spinning.
        private const val DOWN_RETRY_MAX_MS = 60_000L

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
