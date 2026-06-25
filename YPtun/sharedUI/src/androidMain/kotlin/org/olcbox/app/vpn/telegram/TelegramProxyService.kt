package org.olcbox.app.vpn.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import awg.Awg
import awg.Instance
import awg.LogWriter as AwgLogWriter
import awg.Protector as AwgProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.net.Socket

/**
 * Lightweight always-on foreground service that runs the Telegram-over-WARP AmneziaWG tunnel and
 * exposes a local SOCKS5 at [LISTEN_HOST]:[LISTEN_PORT]. The user points Telegram's SOCKS5 proxy at
 * it. Runs INDEPENDENTLY of the main VpnService (its own [Awg.newInstance] instance, own port), so it
 * never collides with a main-VPN AmneziaWG transport, and protects its WARP socket through the main
 * VpnService when one is active (see [VpnSocketProtectBridge]). Deliberately minimal: a low-importance
 * notification, no watchdog and no log journal — AmneziaWG idles at near-zero CPU.
 */
class TelegramProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var instance: Instance? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (instance == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        scope.launch {
            val ds = LocationsDataSourceImpl(applicationContext)
            val baseConfig = runCatching { ds.loadTelegramWarpConfig() }.getOrNull()
            if (baseConfig.isNullOrBlank()) {
                Log.w(TAG, "No cached WARP config — stopping Telegram proxy")
                OlcboxVpnState.addLog("Telegram proxy: no WARP config to start — stopping")
                stopSelfSafely()
                return@launch
            }
            val creds = runCatching { TelegramProxyCreds.getOrCreate(applicationContext) }.getOrNull()

            // WARP is anycast: the SAME account works on every WARP endpoint IP/port. The default
            // engage.cloudflareclient.com:2408 is the one most heavily throttled/blocked (RU especially),
            // so the handshake comes up but carries no traffic. Try the cached/working endpoint first,
            // then a spread of alternate WARP IPs+ports, KEEPING the first that actually reaches a
            // Telegram DC through the tunnel — and cache that working config so next time is instant.
            val cachedEp = extractEndpoint(baseConfig)
            val endpoints = (listOfNotNull(cachedEp) + WARP_FALLBACK_ENDPOINTS).distinct()
            OlcboxVpnState.addLog("Telegram proxy: trying ${endpoints.size} WARP endpoint(s) — verifying via Telegram DC")

            for ((i, ep) in endpoints.withIndex()) {
                val cfg = rewriteEndpoint(baseConfig, ep)
                OlcboxVpnState.addLog("TG-WARP: [${i + 1}/${endpoints.size}] handshake via $ep…")
                val inst = buildInstance(creds)
                val started = runCatching { inst.start(cfg, "$LISTEN_HOST:$LISTEN_PORT") }
                    .onFailure { OlcboxVpnState.addLog("TG-WARP: start failed on $ep — ${it.message ?: it}") }
                    .isSuccess
                if (!started) { runCatching { inst.stop() }; continue }
                instance = inst

                if (telegramReachableThroughWarp(creds)) {
                    OlcboxVpnState.addLog("TG-WARP: ✅ working via $ep — SOCKS5 $LISTEN_HOST:$LISTEN_PORT, point Telegram here")
                    if (ep != cachedEp) runCatching { ds.saveTelegramWarpConfig(cfg) } // remember the working endpoint
                    return@launch
                }

                OlcboxVpnState.addLog("TG-WARP: $ep handshake up but NO traffic to Telegram — trying next")
                runCatching { inst.stop() }
                instance = null
            }

            OlcboxVpnState.addLog("TG-WARP: ❌ all ${endpoints.size} WARP endpoints blocked here — Cloudflare WARP appears unreachable on this network")
            stopSelfSafely()
        }
    }

    private fun buildInstance(creds: TelegramProxyCreds.Credentials?): Instance =
        Awg.newInstance().apply {
            setDebug(false)
            // Split SOCKS: ONLY Telegram IPs ride WARP; everything else dials direct. This is what
            // makes it "Telegram-only via WARP, rest direct" — a pure SOCKS, no VPN/TUN.
            setSplitCIDRs(TELEGRAM_CIDRS)
            // Require auto-generated username/password (RFC 1929) so no other local app can quietly
            // use the WARP proxy. The user types these into Telegram's SOCKS5 settings (shown in UI).
            if (creds != null) setAuth(creds.user, creds.pass)
            setLogWriter(object : AwgLogWriter {
                override fun writeLog(line: String) {
                    val trimmed = line.trimEnd()
                    Log.v(TAG, trimmed)
                    if (trimmed.isNotEmpty()) OlcboxVpnState.addLog("TG-WARP: $trimmed")
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
     * and CONNECT to a real Telegram DC (149.154.167.51:443 — inside [TELEGRAM_CIDRS] so it's routed via
     * WARP). A success reply means the SYN/ACK round-tripped through WARP. Times out fast so a blocked
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

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { instance?.stop() }
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram прокси",
                NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val icon = resources.getIdentifier("ic_stat_yptun", "drawable", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("Telegram прокси")
            .setContentText("SOCKS5 $LISTEN_HOST:$LISTEN_PORT")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
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

        // How long the Telegram-DC verification waits for the SOCKS CONNECT reply. Long enough for the
        // WARP handshake + a real round-trip, short enough that a dead endpoint doesn't stall the sweep.
        private const val SELF_CHECK_TIMEOUT_MS = 6_000

        /**
         * WARP endpoints to try, in order, until one carries traffic to Telegram. WARP is anycast so the
         * SAME generated account works on every one — we only vary IP+port to dodge the throttling/DPI on
         * the default engage.cloudflareclient.com:2408 (the usual reason WARP "connects" but moves no
         * data in RU). Mix of the canonical 162.159.192/193 + 188.114.96/97 ranges on alternate ports
         * that are commonly open. The cached/working endpoint is tried first (see startTunnel).
         */
        private val WARP_FALLBACK_ENDPOINTS = listOf(
            "engage.cloudflareclient.com:2408",
            "162.159.192.1:2408",
            "162.159.193.10:2408",
            "188.114.96.1:2408",
            "188.114.97.1:2408",
            "162.159.192.1:894",
            "162.159.192.1:945",
            "162.159.193.10:4500",
            "188.114.96.1:1701",
            "188.114.97.1:955",
        )

        /**
         * Telegram DC / media IP ranges — only these ride WARP through the split SOCKS; everything else
         * the client sends is dialed direct. Update if Telegram publishes new ranges.
         */
        const val TELEGRAM_CIDRS =
            "91.108.4.0/22,91.108.8.0/22,91.108.12.0/22,91.108.16.0/22,91.108.20.0/22," +
                "91.108.56.0/22,91.105.192.0/23,91.108.58.0/23,149.154.160.0/20,149.154.164.0/22," +
                "149.154.168.0/22,149.154.172.0/22,2001:b28:f23d::/48,2001:b28:f23f::/48," +
                "2001:67c:4e8::/48,2001:b28:f23c::/48"

        fun start(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramProxyService::class.java))
        }
    }
}
