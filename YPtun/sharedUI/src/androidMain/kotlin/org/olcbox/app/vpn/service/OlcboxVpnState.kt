package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.ui.features.locations.components.SpeedSample
import org.olcbox.app.vpn.VpnStatus

object OlcboxVpnState {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    /**
     * Wall-clock epoch-ms when the current session became connected (0 = not connected). Process-global
     * so the connection timer keeps counting from the real start even after the Activity is closed and
     * reopened while the foreground VPN service keeps running — it's NOT reset by a brief Reconnecting.
     */
    private val _connectedSinceMs = MutableStateFlow(0L)
    val connectedSinceMs = _connectedSinceMs.asStateFlow()

    /**
     * Live down/up throughput (bytes per second) of the current connection, published by the
     * service's speed loop. Drives the optional Home-screen speed line. Reset to zero on disconnect.
     */
    private val _speed = MutableStateFlow(SpeedSample(0L, 0L))
    val speed = _speed.asStateFlow()

    fun setSpeed(downBytesPerSec: Long, upBytesPerSec: Long) {
        _speed.value = SpeedSample(downBytesPerSec, upBytesPerSec)
    }

    /**
     * Manual VK captcha page for a VK-TURN (freeturn) connect, served by the freeturn client on a
     * localhost HTTP proxy. Non-null while the user has to solve it — the UI opens it in an in-app
     * WebView; solving lets the TURN relay come up. Published by the service's CaptchaPresenter,
     * cleared when the captcha is solved/cancelled or the session stops.
     */
    private val _vkCaptchaUrl = MutableStateFlow<String?>(null)
    val vkCaptchaUrl = _vkCaptchaUrl.asStateFlow()

    fun setVkCaptchaUrl(url: String?) {
        _vkCaptchaUrl.value = url
    }

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        when (status) {
            // Stamp lazily on the first Connected of a session; a value restored via
            // setConnectedSince already wins (it's > 0) so this never overwrites it.
            is VpnStatus.Connected -> if (_connectedSinceMs.value == 0L) _connectedSinceMs.value = System.currentTimeMillis()
            // ONLY an explicit stop clears the running clock. Connecting / Reconnecting / Error are all
            // TRANSIENT (recovery, network change, a settings re-apply, a probe-induced blip during a
            // ping pass) — keep counting so the on-screen timer never resets mid-session. A genuine
            // fresh connect always passes through Disconnected/Stopping first (or the service clears the
            // persisted value), so the next Connected re-stamps from 0.
            is VpnStatus.Disconnected, is VpnStatus.Stopping -> {
                _connectedSinceMs.value = 0L
                _speed.value = SpeedSample(0L, 0L)
            }
            else -> { /* Connecting / Reconnecting / Error: keep the running clock */ }
        }
    }

    /**
     * Seeds the connection clock with a specific epoch-ms — used by the service to RESTORE the
     * persisted start time after the process was killed and auto-restarted (app swiped from recents),
     * so the on-screen timer keeps counting from the real start instead of resetting. A value of 0
     * is ignored here (a reset goes through [setStatus]); a positive value wins over the lazy
     * "set now on Connected" so the restored time is preserved even across the Connecting transition.
     */
    fun setConnectedSince(epochMs: Long) {
        if (epochMs > 0L) _connectedSinceMs.value = epochMs
    }

    fun addLog(msg: String) {
        Log.d(TAG, msg)
        _logs.update { (it + stripAnsi(msg)).takeLast(MAX_LOG_ENTRIES) }
    }

    /**
     * Appends a line to the journal WITHOUT echoing it back to logcat. Used by the logcat tailer
     * ([OlcboxVpnService] full-logs capture) so reading our own process log doesn't feed itself.
     */
    fun appendRaw(line: String) {
        _logs.update { (it + stripAnsi(line)).takeLast(MAX_LOG_ENTRIES) }
    }

    /**
     * Removes ANSI/VT100 colour & cursor escape sequences. sing-box/xray emit coloured levels like
     * `[36mINFO[0m` and 256-colour tags `[38;5;181m…` — left in, they render as
     * "[36m…[0m" garbage in the in-app journal. Cheap fast-path when there's no escape byte at all.
     */
    private fun stripAnsi(s: String): String =
        if (s.indexOf('') < 0) s else ANSI_ESCAPE.replace(s, "")

    private val ANSI_ESCAPE = Regex("\\[[0-9;]*[A-Za-z]")

    /**
     * The live local SOCKS5 endpoint of the running core (host/port + the per-session credentials,
     * which are randomized in TUN mode). Published by the service so the in-process latency probe
     * can authenticate to it; null when no core is up. Same-process singleton.
     */
    @Volatile
    var activeSocks: SocksEndpoint? = null

    data class SocksEndpoint(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
    )

    private const val MAX_LOG_ENTRIES = 5_000
    // MUST stay "OlcboxVpnService": the full-logs logcat tailer skips lines whose tag contains this
    // string so our own addLog() output isn't re-captured from logcat and duplicated in the journal.
    private const val TAG = "OlcboxVpnService"
}
