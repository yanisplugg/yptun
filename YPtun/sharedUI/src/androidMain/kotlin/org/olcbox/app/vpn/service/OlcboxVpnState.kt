package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        when (status) {
            is VpnStatus.Connected -> if (_connectedSinceMs.value == 0L) _connectedSinceMs.value = System.currentTimeMillis()
            is VpnStatus.Reconnecting -> { /* keep the running clock across a transient reconnect */ }
            else -> _connectedSinceMs.value = 0L // Disconnected / Connecting / Error → reset
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
        _logs.update { (it + msg).takeLast(MAX_LOG_ENTRIES) }
    }

    /**
     * Appends a line to the journal WITHOUT echoing it back to logcat. Used by the logcat tailer
     * ([OlcboxVpnService] full-logs capture) so reading our own process log doesn't feed itself.
     */
    fun appendRaw(line: String) {
        _logs.update { (it + line).takeLast(MAX_LOG_ENTRIES) }
    }

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
    private const val TAG = "OlcboxVpnService"
}
