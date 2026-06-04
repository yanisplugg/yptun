package org.olcbox.app.vpn.xray

import android.util.Log
import xraybridge.Protector
import xraybridge.Xraybridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper around the gomobile-bound `xraybridge` package (xray-core).
 *
 * Xray runs as a userspace proxy: the config exposes a SOCKS inbound (consumed by the TUN bridge)
 * and the proxy outbound. Socket protection is done in xray-core via RegisterDialerController →
 * [protect] (VpnService.protect), so xray traffic stays off the TUN. No interface monitor needed.
 *
 * @param protect protects a socket fd from the VPN (VpnService.protect).
 * @param log forwards a couple of lifecycle lines into the app log (xray-core logs go to logcat).
 */
class XrayEngine(
    private val protect: (Int) -> Boolean,
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get() && runCatching { Xraybridge.isRunning() }.getOrDefault(false)

    /**
     * @param assetPath directory holding geoip.dat/geosite.dat for geosite:/geoip: routing selectors.
     *   Blank leaves xray-core's default (no geo data) — passed straight through to the bridge.
     */
    @Synchronized
    fun start(configJson: String, assetPath: String = "") {
        if (running.get()) stop()
        runCatching { Xraybridge.setAssetPath(assetPath) }
            .onFailure { Log.w(TAG, "xray setAssetPath failed", it) }
        Xraybridge.setProtector(object : Protector {
            override fun protect(fd: Long): Boolean = this@XrayEngine.protect(fd.toInt())
        })
        Xraybridge.start(configJson)
        running.set(true)
        log("xray: service started")
        Log.i(TAG, "xray service started")
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { Xraybridge.stop() }.onFailure { Log.w(TAG, "xray stop failed", it) }
        log("xray: service stopped")
        Log.i(TAG, "xray service stopped")
    }

    private companion object {
        const val TAG = "XrayEngine"
    }
}
