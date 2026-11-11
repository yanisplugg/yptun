package org.olcbox.app.vpn.service

import org.olcbox.app.core.AndroidBridgeEngine
import org.olcbox.app.core.AndroidMode
import org.olcbox.app.core.AndroidRuntimeSnapshot
import org.olcbox.app.core.AndroidSplitMode
import org.olcbox.app.core.AndroidStartConfig
import org.olcbox.app.core.AndroidStatusKind
import org.olcbox.app.core.AndroidTun2SocksConfig
import org.olcbox.app.core.AndroidTunStats
import org.olcbox.app.core.AndroidVpnRuntime
import org.olcbox.app.core.androidCanReconnectInPlace
import org.olcbox.app.core.androidOlcrtcDns
import org.olcbox.app.core.androidPartitionVkLinks
import org.olcbox.app.core.androidPortPlan
import org.olcbox.app.core.androidPrepareStart
import org.olcbox.app.core.androidShouldRestart
import org.olcbox.app.core.androidSplitTunnelPlan
import org.olcbox.app.core.androidStatusEffects
import org.olcbox.app.core.androidTproxyApplyScript
import org.olcbox.app.core.androidTproxyFailure
import org.olcbox.app.core.androidTproxyTeardownScript
import org.olcbox.app.core.androidTun2socksYaml
import org.olcbox.app.core.androidTunnelPlan
import org.olcbox.app.core.androidVkturnStreams
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.VpnStatus

/**
 * Stable Kotlin-facing adapter over the generated UniFFI API.
 *
 * Generated types stay inside this file, so regenerating bindings cannot spread unsigned integers
 * and Rust naming through the Android service. The service still owns every Android framework call;
 * this object only delegates deterministic decisions and the small health state to Rust.
 */
internal object RustAndroidVpn {
    data class PreparedStart(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>,
    )

    data class SplitPlan(
        val allowedPackages: List<String>,
        val disallowedPackages: List<String>,
        val requireOneAllowed: Boolean,
    )

    data class IpPrefix(val address: String, val prefix: Int)

    data class TunnelPlan(
        val sessionName: String,
        val blocking: Boolean,
        val mtu: Int,
        val addresses: List<IpPrefix>,
        val dnsServers: List<String>,
        val routes: List<IpPrefix>,
    )

    data class Ports(
        val socks: Int,
        val chainOlcrtc: Int,
        val amneziawg: Int,
        val warpRelay: Int,
        val tproxy: Int,
        val httpProxy: Int,
        val trustTunnel: Int,
        val singBoxFront: Int,
    )

    data class TunStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long,
    )

    data class Speed(val downBytesPerSec: Long, val upBytesPerSec: Long)

    data class Recovery(val reason: String, val fullRestart: Boolean)

    data class RuntimeSnapshot(
        val vpnInterface: Boolean = false,
        val tun2socksThread: Boolean = false,
        val tun2socksAlive: Boolean = false,
        val socksProxy: Boolean = false,
        val coreRunning: Boolean = false,
        val startupJobActive: Boolean = false,
        val cleanupJobActive: Boolean = false,
    )

    data class StatusEffects(
        val connected: Boolean,
        val connectedSinceMs: Long,
        val resetSpeed: Boolean,
    )

    private val runtime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidVpnRuntime(System.currentTimeMillis().coerceAtLeast(0L).toULong())
    }

    fun prepareStart(
        connectionMode: AndroidConnectionMode,
        socksListenHost: String,
        socksListenPort: Int,
        socksUsername: String,
        socksPassword: String,
        splitTunnelMode: AndroidSplitTunnelMode,
        splitTunnelProxyApps: Set<String>,
        splitTunnelBypassApps: Set<String>,
    ): PreparedStart {
        val result = androidPrepareStart(
            AndroidStartConfig(
                connectionMode = connectionMode.toRust(),
                socksListenHost = socksListenHost,
                socksListenPort = socksListenPort,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                splitTunnelMode = splitTunnelMode.toRust(),
                splitTunnelProxyApps = splitTunnelProxyApps.toList(),
                splitTunnelBypassApps = splitTunnelBypassApps.toList(),
            )
        )
        return PreparedStart(
            connectionMode = result.connectionMode.toPlatform(),
            socksListenHost = result.socksListenHost,
            socksListenPort = result.socksListenPort,
            socksUsername = result.socksUsername,
            socksPassword = result.socksPassword,
            splitTunnelMode = result.splitTunnelMode.toPlatform(),
            splitTunnelProxyApps = result.splitTunnelProxyApps.toSet(),
            splitTunnelBypassApps = result.splitTunnelBypassApps.toSet(),
        )
    }

    fun splitTunnelPlan(
        mode: AndroidSplitTunnelMode,
        ownPackage: String,
        proxyPackages: Set<String>,
        bypassPackages: Set<String>,
    ): SplitPlan {
        val result = androidSplitTunnelPlan(
            mode.toRust(), ownPackage, proxyPackages.toList(), bypassPackages.toList()
        )
        return SplitPlan(
            allowedPackages = result.allowedPackages,
            disallowedPackages = result.disallowedPackages,
            requireOneAllowed = result.requireOneAllowed,
        )
    }

    fun tunnelPlan(mtu: Int, bypassLan: Boolean): TunnelPlan {
        val result = androidTunnelPlan(mtu, bypassLan)
        return TunnelPlan(
            sessionName = result.sessionName,
            blocking = result.blocking,
            mtu = result.mtu,
            addresses = result.addresses.map { IpPrefix(it.address, it.prefix) },
            dnsServers = result.dnsServers,
            routes = result.routes.map { IpPrefix(it.address, it.prefix) },
        )
    }

    fun ports(socksPort: Int): Ports {
        val result = androidPortPlan(socksPort)
        return Ports(
            socks = result.socks,
            chainOlcrtc = result.chainOlcrtc,
            amneziawg = result.amneziawg,
            warpRelay = result.warpRelay,
            tproxy = result.tproxy,
            httpProxy = result.httpProxy,
            trustTunnel = result.trustTunnel,
            singBoxFront = result.singBoxFront,
        )
    }

    fun tun2socksYaml(
        mtu: Int,
        dropIpv6: Boolean,
        socksListenHost: String,
        socksListenPort: Int,
        socksUsername: String,
        socksPassword: String,
        engine: EngineType,
    ): String = androidTun2socksYaml(
        AndroidTun2SocksConfig(
            mtu = mtu,
            dropIpv6 = dropIpv6,
            socksListenHost = socksListenHost,
            socksListenPort = socksListenPort,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            engine = engine.toRustBridge(),
        )
    )

    fun olcrtcDns(upstreamAddress: String?): String = androidOlcrtcDns(upstreamAddress)

    fun vkturnStreams(vkLinks: String, requestedStreams: Int): Int =
        androidVkturnStreams(vkLinks, requestedStreams)

    fun partitionVkLinks(vkLinks: String, servers: Int): List<String> =
        androidPartitionVkLinks(vkLinks, servers.coerceAtLeast(0).toUInt())

    fun tproxyTeardownScript(): String = androidTproxyTeardownScript()

    fun tproxyApplyScript(uid: Int, port: Int): String =
        androidTproxyApplyScript(uid.coerceAtLeast(0).toUInt(), port)

    fun tproxyFailure(executionError: String?, output: String): String? =
        androidTproxyFailure(executionError, output)

    fun observeRtcLine(
        line: String,
        nowMs: Long,
        statusConnected: Boolean,
        mode: AndroidConnectionMode,
    ): Recovery? = runtime.observeRtcLine(
        line,
        nowMs.coerceAtLeast(0L).toULong(),
        statusConnected,
        mode.toRust(),
    )?.let { Recovery(it.reason, it.fullRestart) }

    fun observeTunStats(stats: TunStats?, coreRunning: Boolean): Boolean =
        runtime.observeTunStats(stats?.toRust(), coreRunning)

    fun speedTick(stats: TunStats?, intervalMs: Long): Speed? =
        runtime.speedTick(stats?.toRust(), intervalMs.coerceAtLeast(0L).toULong())
            ?.let { Speed(it.downBytesPerSec, it.upBytesPerSec) }

    fun nextRetryDelayMs(baseDelayMs: Long): Long =
        runtime.nextRetryDelayMs(baseDelayMs.coerceAtLeast(0L).toULong()).toLong()

    fun resetRtc(nowMs: Long = System.currentTimeMillis()) {
        runtime.resetRtc(nowMs.coerceAtLeast(0L).toULong())
    }

    fun resetTunHealth() {
        runtime.resetTunHealth()
    }

    fun resetSpeed() {
        runtime.resetSpeed()
    }

    fun resetRetry() {
        runtime.resetRetry()
    }

    fun resetRecovery(nowMs: Long = System.currentTimeMillis()) {
        runtime.resetRecovery(nowMs.coerceAtLeast(0L).toULong())
    }

    fun canReconnectInPlace(mode: AndroidConnectionMode, snapshot: RuntimeSnapshot): Boolean =
        androidCanReconnectInPlace(mode.toRust(), snapshot.toRust())

    fun shouldRestart(status: VpnStatus, snapshot: RuntimeSnapshot): Boolean =
        androidShouldRestart(status.toRust(), snapshot.toRust())

    fun statusEffects(status: VpnStatus, connectedSinceMs: Long, nowMs: Long): StatusEffects {
        val result = androidStatusEffects(
            status.toRust(),
            connectedSinceMs.coerceAtLeast(0L).toULong(),
            nowMs.coerceAtLeast(0L).toULong(),
        )
        return StatusEffects(
            connected = result.connected,
            connectedSinceMs = result.connectedSinceMs.toLong(),
            resetSpeed = result.resetSpeed,
        )
    }

    private fun TunStats.toRust() = AndroidTunStats(
        txPackets = txPackets,
        txBytes = txBytes,
        rxPackets = rxPackets,
        rxBytes = rxBytes,
    )

    private fun RuntimeSnapshot.toRust() = AndroidRuntimeSnapshot(
        vpnInterface = vpnInterface,
        tun2socksThread = tun2socksThread,
        tun2socksAlive = tun2socksAlive,
        socksProxy = socksProxy,
        coreRunning = coreRunning,
        startupJobActive = startupJobActive,
        cleanupJobActive = cleanupJobActive,
    )

    private fun AndroidConnectionMode.toRust(): AndroidMode = when (this) {
        AndroidConnectionMode.Tun -> AndroidMode.TUN
        AndroidConnectionMode.Proxy -> AndroidMode.PROXY
        AndroidConnectionMode.Tproxy -> AndroidMode.TPROXY
    }

    private fun AndroidMode.toPlatform(): AndroidConnectionMode = when (this) {
        AndroidMode.TUN -> AndroidConnectionMode.Tun
        AndroidMode.PROXY -> AndroidConnectionMode.Proxy
        AndroidMode.TPROXY -> AndroidConnectionMode.Tproxy
    }

    private fun AndroidSplitTunnelMode.toRust(): AndroidSplitMode = when (this) {
        AndroidSplitTunnelMode.AllApps -> AndroidSplitMode.ALL_APPS
        AndroidSplitTunnelMode.ProxySelected -> AndroidSplitMode.PROXY_SELECTED
        AndroidSplitTunnelMode.BypassSelected -> AndroidSplitMode.BYPASS_SELECTED
    }

    private fun AndroidSplitMode.toPlatform(): AndroidSplitTunnelMode = when (this) {
        AndroidSplitMode.ALL_APPS -> AndroidSplitTunnelMode.AllApps
        AndroidSplitMode.PROXY_SELECTED -> AndroidSplitTunnelMode.ProxySelected
        AndroidSplitMode.BYPASS_SELECTED -> AndroidSplitTunnelMode.BypassSelected
    }

    private fun EngineType.toRustBridge(): AndroidBridgeEngine = when (this) {
        EngineType.Stealth -> AndroidBridgeEngine.STEALTH
        EngineType.MasterDns -> AndroidBridgeEngine.MASTER_DNS
        EngineType.OpenFlux -> AndroidBridgeEngine.OPEN_FLUX
        else -> AndroidBridgeEngine.OTHER
    }

    private fun VpnStatus.toRust(): AndroidStatusKind = when (this) {
        VpnStatus.Connected -> AndroidStatusKind.CONNECTED
        VpnStatus.Connecting -> AndroidStatusKind.CONNECTING
        VpnStatus.Reconnecting -> AndroidStatusKind.RECONNECTING
        VpnStatus.Stopping -> AndroidStatusKind.STOPPING
        VpnStatus.Disconnected -> AndroidStatusKind.DISCONNECTED
        is VpnStatus.Error -> AndroidStatusKind.ERROR
    }
}
