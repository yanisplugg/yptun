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

    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
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
