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
            val config = runCatching { LocationsDataSourceImpl(applicationContext).loadTelegramWarpConfig() }
                .getOrNull()
            if (config.isNullOrBlank()) {
                Log.w(TAG, "No cached WARP config — stopping Telegram proxy")
                OlcboxVpnState.addLog("Telegram proxy: no WARP config to start — stopping")
                stopSelfSafely()
                return@launch
            }
            val creds = runCatching { TelegramProxyCreds.getOrCreate(applicationContext) }.getOrNull()
            val inst = Awg.newInstance().apply {
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
                        // Surface handshake/transport lines in the visible journal so WARP failures
                        // (e.g. handshake never completes) are diagnosable without logcat.
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
            instance = inst
            runCatching { inst.start(config, "$LISTEN_HOST:$LISTEN_PORT") }
                .onFailure {
                    Log.e(TAG, "Telegram WARP tunnel failed to start: ${it.message}")
                    OlcboxVpnState.addLog("TG-WARP: tunnel FAILED to start — ${it.message ?: it}")
                    instance = null
                    stopSelfSafely()
                }
                .onSuccess {
                    Log.i(TAG, "Telegram WARP SOCKS up on $LISTEN_HOST:$LISTEN_PORT")
                    OlcboxVpnState.addLog("TG-WARP: SOCKS up on $LISTEN_HOST:$LISTEN_PORT — point Telegram here")
                }
        }
    }

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
