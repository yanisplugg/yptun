package org.olcbox.app.vpn.singbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import libbox.BoxService
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.LocalDNSTransport
import libbox.NetworkInterface as LibboxNetworkInterface
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.SetupOptions
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState
import java.io.File
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper around sing-box's gomobile `libbox` binding (pinned to v1.12.25).
 *
 * sing-box runs as a userspace proxy: the config exposes a SOCKS5 inbound (consumed by the
 * TUN bridge) and the proxy outbound. `route.auto_detect_interface = true`, so sing-box binds
 * and protects each outbound socket via [PlatformInterface.autoDetectInterfaceControl] →
 * [protect] (VpnService.protect), keeping its traffic off the TUN (no routing loop). The TUN
 * device itself stays owned by OlcboxVpnService (hev-socks5-tunnel), so [openTun] is never used.
 *
 * @param protect protects a socket fd from the VPN (VpnService.protect).
 * @param log forwards sing-box log lines into the app log.
 * @param underlyingNetwork returns the current upstream (non-VPN) network used to resolve the
 *        default interface for sing-box.
 */
class SingBoxEngine(
    private val context: Context,
    private val workDir: File,
    private val tempDir: File,
    private val protect: (Int) -> Boolean,
    private val log: (String) -> Unit,
    private val underlyingNetwork: () -> Network?,
) {
    private val running = AtomicBoolean(false)
    private var service: BoxService? = null
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(configJson: String) {
        if (running.get()) stop()
        ensureSetup()
        val boxService = Libbox.newService(configJson, PlatformBridge())
        boxService.start()
        service = boxService
        running.set(true)
        Log.i(TAG, "sing-box service started")
    }

    @Synchronized
    fun stop() {
        val boxService = service ?: run { running.set(false); return }
        runCatching { boxService.close() }
            .onFailure { Log.w(TAG, "sing-box close failed", it) }
        service = null
        running.set(false)
        Log.i(TAG, "sing-box service stopped")
    }

    private fun ensureSetup() {
        if (setupDone) return
        synchronized(SingBoxEngine::class.java) {
            if (setupDone) return
            workDir.mkdirs()
            tempDir.mkdirs()
            val options = SetupOptions().apply {
                basePath = workDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = tempDir.absolutePath
                username = ""
                isTVOS = false
                fixAndroidStack = true
            }
            Libbox.setup(options)
            setupDone = true
        }
    }

    private inner class PlatformBridge : PlatformInterface {

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            protect(fd)
        }

        override fun openTun(options: TunOptions): Int {
            throw UnsupportedOperationException("sing-box TUN is not used; hev-socks5-tunnel owns the TUN")
        }

        override fun writeLog(message: String) {
            log("sb: ${message.trimEnd()}")
            Log.v("sing-box", message)
        }

        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): Int = throw UnsupportedOperationException("connection owner lookup not supported")

        override fun packageNameByUid(uid: Int): String =
            throw UnsupportedOperationException("packageNameByUid not supported")

        override fun uidByPackageName(packageName: String): Int =
            throw UnsupportedOperationException("uidByPackageName not supported")

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            pushDefaultInterface(listener)
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // No continuous monitoring; the service restarts the engine on upstream changes.
        }

        override fun getInterfaces(): NetworkInterfaceIterator =
            InterfaceArrayIterator(enumerateInterfaces())

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun readWIFIState(): WIFIState? = null

        override fun systemCertificates(): StringIterator? = null

        override fun clearDNSCache() {}

        override fun sendNotification(notification: Notification) {}
    }

    /** Reports the current upstream interface so sing-box has a default to bind sockets to. */
    private fun pushDefaultInterface(listener: InterfaceUpdateListener) {
        val net = underlyingNetwork() ?: connectivity.activeNetwork
        val name = net?.let { connectivity.getLinkProperties(it)?.interfaceName }
        if (name == null) {
            log("sb: no default interface found")
            return
        }
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
        log("sb: default interface $name (#$index)")
        runCatching { listener.updateDefaultInterface(name, index, false, false) }
            .onFailure { log("sb: updateDefaultInterface failed: ${it.message}") }
    }

    /** Enumerates usable network interfaces (excluding loopback, down, and the VPN tun). */
    private fun enumerateInterfaces(): List<LibboxNetworkInterface> {
        val result = mutableListOf<LibboxNetworkInterface>()
        val ifaces = runCatching { JavaNetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        for (iface in ifaces) {
            val name = iface.name ?: continue
            if (name.startsWith("tun")) continue
            val isUp = runCatching { iface.isUp }.getOrDefault(false)
            if (!isUp) continue
            if (runCatching { iface.isLoopback }.getOrDefault(true)) continue

            var flags = 0
            flags = flags or FLAG_UP or FLAG_RUNNING
            if (runCatching { iface.isPointToPoint }.getOrDefault(false)) flags = flags or FLAG_POINTOPOINT
            if (runCatching { iface.supportsMulticast() }.getOrDefault(false)) flags = flags or FLAG_MULTICAST

            val ni = LibboxNetworkInterface().apply {
                setIndex(iface.index)
                setMTU(runCatching { iface.mtu }.getOrDefault(1500))
                setName(name)
                // Addresses left empty on purpose: sing-box parses them with MustParsePrefix and
                // only needs index/name for auto-detect binding. Empty avoids parse panics.
                setAddresses(StringArrayIterator(emptyList()))
                setFlags(flags)
                setType(INTERFACE_TYPE_OTHER)
                setDNSServer(StringArrayIterator(emptyList()))
                setMetered(false)
            }
            result.add(ni)
        }
        return result
    }

    private class StringArrayIterator(private val items: List<String>) : StringIterator {
        private var i = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = i < items.size
        override fun next(): String = items[i++]
    }

    private class InterfaceArrayIterator(
        private val items: List<LibboxNetworkInterface>
    ) : NetworkInterfaceIterator {
        private var i = 0
        override fun hasNext(): Boolean = i < items.size
        override fun next(): LibboxNetworkInterface = items[i++]
    }

    companion object {
        private const val TAG = "SingBoxEngine"

        // net.Flags bit values expected by sing-box.
        private const val FLAG_UP = 1
        private const val FLAG_POINTOPOINT = 8
        private const val FLAG_MULTICAST = 16
        private const val FLAG_RUNNING = 32
        private const val INTERFACE_TYPE_OTHER = 3

        @Volatile
        private var setupDone = false
    }
}
