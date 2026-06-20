package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.delay
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.xray.XrayConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Desktop port of OlcboxVpnService's engine orchestration (Android): starts the right core(s)
 * for a location via [YpTunCore] (the in-process Go runtime) and leaves a SOCKS5 endpoint on
 * `listenHost:listenPort` for the TUN bridge / system proxy that DesktopVpnManager owns.
 *
 * Differences from Android: no VpnService binding/protection (the TUN controller routes the
 * proxy server around the tunnel; sing-box uses native auto_detect_interface), no notifications,
 * and settings come from [JvmVpnSettings] JSON files instead of DataStore.
 */
internal class DesktopEngineController(
    private val log: (String) -> Unit,
) {
    var activeProxyCore: ProxyCore = ProxyCore.SingBox
        private set

    private val logSink: (String) -> Unit = { line -> log(line) }

    /** olcRTC's local SOCKS port when chaining; sing-box dials its outbound through it. */
    private fun chainOlcrtcPort(socksPort: Int) = socksPort + 1

    /** AmneziaWG's local SOCKS port (awgproxy). */
    private fun awgLocalPort(socksPort: Int) = socksPort + 2

    /** Hysteria2's local SOCKS port (hysteria2proxy). */
    private fun hy2LocalPort(socksPort: Int) = socksPort + 3

    val isSupported: Boolean get() = YpTunCore.isAvailable

    init {
        YpTunCore.logSinks.add(logSink)
    }

    fun close() {
        YpTunCore.logSinks.remove(logSink)
    }

    /**
     * When set (desktop TUN mode with split tunneling), the sing-box config gets its own tun
     * inbound + per-process rules; [tunHandledInCore] then tells the manager to skip tun2socks.
     */
    private var tunRequest: TunRequest? = null

    private data class TunRequest(val splitMode: String, val processes: List<String>)

    /** True after [start] when sing-box owns the TUN itself (no external tun2socks needed). */
    var tunHandledInCore: Boolean = false
        private set

    private val requestedTun: Boolean get() = tunRequest != null

    /**
     * Starts the engine(s) for [location]; on return the SOCKS5 endpoint is accepting connections.
     * Throws with a user-facing message on failure (caller stops everything via [stopAll]).
     *
     * [tunViaSingBox]: desktop TUN mode wants sing-box to own the TUN (per-process split
     * tunneling). Honored only when the location ends up on the sing-box core; check
     * [tunHandledInCore] afterwards.
     */
    suspend fun start(
        location: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
        tunViaSingBox: Boolean = false,
        splitTunnelMode: String = SingBoxConfig.SPLIT_TUNNEL_ALL,
        splitTunnelProcesses: List<String> = emptyList(),
    ) {
        tunRequest = if (tunViaSingBox) TunRequest(splitTunnelMode, splitTunnelProcesses) else null
        tunHandledInCore = false
        val config = location.normalized()
        when (config.engine) {
            EngineType.Stealth -> startStealth(config, listenHost, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.Standard,
            EngineType.Chain -> startSingBoxOrXray(config, listenHost, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.VkTurn -> startVkTurn(config, listenHost, listenPort, socksUsername, socksPassword)
        }
        if (requestedTun && !tunHandledInCore) {
            log("Per-process split tunneling unavailable (core is ${activeProxyCore}); falling back to tun2socks for all apps")
        }
    }

    fun stopAll() {
        YpTunCore.stopAll()
    }

    fun coreRunning(engine: EngineType): Boolean = when (engine) {
        EngineType.Stealth -> YpTunCore.rtcRunning()
        EngineType.Standard -> proxyCoreRunning()
        EngineType.Chain -> YpTunCore.rtcRunning() && proxyCoreRunning()
        EngineType.VkTurn -> YpTunCore.ftRunning() && proxyCoreRunning()
    }

    private fun proxyCoreRunning(): Boolean =
        if (activeProxyCore == ProxyCore.Xray) YpTunCore.xrayRunning() else YpTunCore.sbRunning()

    // ---------------------------------------------------------------------------------------
    // Stealth (olcrtc in-process)

    private suspend fun startStealth(
        config: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        require(!isLocalSocksPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }
        YpTunCore.rtcSetSocksListenHost(listenHost)
        applyTelemostCookies(config)
        log("Starting olcRTC provider=${config.bypassProvider}, transport=${config.transport}, room=${config.id}")
        YpTunCore.rtcStart(
            carrier = config.bypassProvider,
            transport = config.transport,
            roomId = config.id,
            clientId = deviceId,
            keyHex = config.key,
            socksPort = listenPort,
            socksUser = socksUsername,
            socksPass = socksPassword,
        )
        YpTunCore.rtcWaitReady(MOBILE_READY_TIMEOUT_MS)
        log("olcRTC ready on $listenHost:$listenPort")
    }

    private fun applyTelemostCookies(config: LocationConfig) {
        val behavior = JvmVpnSettings.loadAppBehavior()
        val use = behavior.telemostCookiesEnabled &&
            behavior.telemostCookies.isNotBlank() &&
            LocationConfig.normalizeProvider(config.bypassProvider) == LocationConfig.PROVIDER_TELEMOST
        runCatching { YpTunCore.rtcSetTelemostCookies(if (use) behavior.telemostCookies.trim() else "") }
        if (use) log("Applied Telemost cookies")
    }

    // ---------------------------------------------------------------------------------------
    // Standard / Chain (sing-box or xray, mirrors OlcboxVpnService.startSingBoxCore)

    private suspend fun startSingBoxOrXray(
        config: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        val profile = config.proxy
        check(profile != null && profile.isComplete()) { "No proxy configured" }

        val chained = config.engine == EngineType.Chain
        val chainPort = chainOlcrtcPort(listenPort)
        val secondProfile = config.proxy2?.takeIf { it.isComplete() }

        require(!isLocalSocksPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        if (chained) {
            applyTelemostCookies(config)
            log("Starting olcRTC (chain) provider=${config.bypassProvider}, room=${config.id}")
            YpTunCore.rtcStart(
                carrier = config.bypassProvider,
                transport = config.transport,
                roomId = config.id,
                clientId = deviceId,
                keyHex = config.key,
                socksPort = chainPort,
                socksUser = socksUsername,
                socksPass = socksPassword,
            )
            YpTunCore.rtcWaitReady(MOBILE_READY_TIMEOUT_MS)
            log("olcRTC chain ready on 127.0.0.1:$chainPort")
        }

        val isAwg = profile.type == ProxyProfile.TYPE_AMNEZIAWG
        val isHy2 = profile.type == ProxyProfile.TYPE_HYSTERIA2
        val effectiveProfile = when {
            isAwg -> prepareAmneziaWgProxy(profile, listenPort)
            isHy2 -> prepareHysteria2Proxy(profile, listenPort)
            else -> profile
        }
        val isLocalUdpTunnel = isAwg || isHy2

        val traffic = JvmVpnSettings.loadTraffic()
        val routing = JvmVpnSettings.loadRouting()
        val profilesState = JvmVpnSettings.loadRoutingProfiles()
        val routingProfile = profilesState.resolve(config.routingProfileId)
        if (routingProfile == null) {
            log("Routing: NO profile applied — all traffic via proxy")
        } else {
            log("Routing: applying '${routingProfile.displayName()}'")
        }

        activeProxyCore = if (isLocalUdpTunnel) ProxyCore.SingBox else config.resolvedCore()
        val profileWantsXray = routingProfile != null && routingProfile.dnsHosts.isNotEmpty()
        if (!effectiveProfile.rawXrayConfig.isNullOrBlank()) {
            if (activeProxyCore != ProxyCore.Xray) log("Raw Xray config present → forcing Xray core")
            activeProxyCore = ProxyCore.Xray
        }
        if (activeProxyCore == ProxyCore.SingBox &&
            (traffic.blockRuDomains || profileWantsXray) &&
            effectiveProfile.rawOutbound.isNullOrBlank() &&
            effectiveProfile.type in XRAY_SUPPORTED_TYPES
        ) {
            activeProxyCore = ProxyCore.Xray
            log(
                if (profileWantsXray) "Switching to Xray core for routing profile (native domain:/geoip: matching)"
                else "Switching to Xray core for RU-domain blocklist"
            )
        }
        if (activeProxyCore == ProxyCore.Xray &&
            routingProfile?.needsGeoFiles() == true &&
            effectiveProfile.rawXrayConfig.isNullOrBlank() &&
            effectiveProfile.network != ProxyProfile.NETWORK_XHTTP &&
            effectiveProfile.rawOutbound.isNullOrBlank() &&
            ensureGeoAssetPath(routingProfile).isEmpty()
        ) {
            activeProxyCore = ProxyCore.SingBox
            log("Geo databases unavailable for Xray → using sing-box for routing")
        }
        if (secondProfile?.network == ProxyProfile.NETWORK_XHTTP && activeProxyCore != ProxyCore.Xray) {
            activeProxyCore = ProxyCore.Xray
            log("Second (cascade) proxy uses xhttp → forcing Xray core")
        }

        if (activeProxyCore == ProxyCore.Xray) {
            val rawXray = effectiveProfile.rawXrayConfig
            var assetPath = ""
            val json = if (!rawXray.isNullOrBlank()) {
                if (routingProfile != null) assetPath = ensureGeoAssetPath(routingProfile)
                log("Starting Xray with custom config")
                XrayConfig.prepareRaw(
                    rawConfigJson = rawXray,
                    listenPort = listenPort,
                    listenHost = listenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                    fakeDnsEnabled = traffic.fakeDnsEnabled,
                )
            } else {
                assetPath = ensureGeoAssetPath(routingProfile)
                XrayConfig.build(
                    profile = effectiveProfile,
                    listenPort = listenPort,
                    listenHost = listenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    olcrtcChainPort = if (chained) chainPort else null,
                    olcrtcChainUser = if (chained) socksUsername else "",
                    olcrtcChainPass = if (chained) socksPassword else "",
                    traffic = traffic.let { t ->
                        config.advanced?.let {
                            t.copy(
                                muxEnabled = it.muxEnabled,
                                muxProtocol = it.muxProtocol,
                                muxMaxConnections = it.muxMaxStreams,
                                fragmentEnabled = it.tlsFragment,
                            )
                        } ?: t
                    },
                    routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                    secondProfile = secondProfile,
                )
            }
            log("Starting Xray engine=${config.engine}, server=${effectiveProfile.server}:${effectiveProfile.serverPort}")
            if (assetPath.isNotEmpty()) YpTunCore.xraySetAssetPath(assetPath)
            YpTunCore.xrayStart(json)
        } else {
            if (isAwg) log("AmneziaWG outbound: QUIC allowed + sniff-override→IPv4")
            if (isHy2) log("Hysteria2 outbound: QUIC allowed + sniff-override→IPv4")
            val json = SingBoxConfig.build(
                profile = effectiveProfile,
                listenPort = listenPort,
                listenHost = listenHost,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = if (chained) chainPort else null,
                olcrtcChainUser = if (chained) socksUsername else "",
                olcrtcChainPass = if (chained) socksPassword else "",
                autoDetectInterface = true,
                routing = routing,
                traffic = traffic,
                advanced = config.advanced,
                routingProfile = routingProfile,
                singboxGeositeBase = profilesState.singboxGeositeBase,
                singboxGeoipBase = profilesState.singboxGeoipBase,
                blockQuic = !isLocalUdpTunnel,
                sniffOverrideDestination = isLocalUdpTunnel,
                secondProfile = secondProfile,
                fakeDnsSpec = config.fakeDns,
                tunMode = requestedTun,
                splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
            )
            log("Starting sing-box engine=${config.engine} via ${effectiveProfile.server}:${effectiveProfile.serverPort}" +
                if (requestedTun) " (in-core TUN + per-process rules)" else "")
            YpTunCore.sbStart(json)
            tunHandledInCore = requestedTun
        }

        if (!awaitSocksPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Proxy SOCKS port $listenPort did not open")
        }
        log("Proxy core ready on $listenHost:$listenPort")
    }

    // ---------------------------------------------------------------------------------------
    // VK-TURN (mirrors OlcboxVpnService.startVkTurnCore)

    private suspend fun startVkTurn(
        config: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) {
        val vk = config.vkturn
        val profile = config.proxy
        val outboundType = vk?.outbound?.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD }
            ?: VkTurnConfig.OUTBOUND_WIREGUARD
        val outboundConfigured = when (outboundType) {
            VkTurnConfig.OUTBOUND_AMNEZIAWG -> !profile?.awgConfig.isNullOrBlank()
            VkTurnConfig.OUTBOUND_PROXY -> profile != null &&
                profile.server.isNotBlank() && profile.serverPort in 1..65535
            else -> !profile?.rawOutbound.isNullOrBlank()
        }
        check(vk != null && vk.isComplete() && outboundConfigured) { "VK-TURN not configured" }

        require(!isLocalSocksPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        val listenAddr = "127.0.0.1:${vk.listenPort}"
        val freeturnUri = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) vk.uri
        else vk.uri.replace("&bond=1", "").replace("bond=1&", "").replace("bond=1", "")
        log("Starting VK-TURN freeturn listener on $listenAddr")
        YpTunCore.ftStart(freeturnUri, listenAddr, vk.vkLink, vk.streams)

        if (awaitVkTurnRelayReady(VKTURN_RELAY_READY_TIMEOUT_MS)) {
            log("VK-TURN relay up (${YpTunCore.ftConnectedStreams()} stream(s)); starting WireGuard")
        } else {
            log("VK-TURN relay not ready yet; starting outbound anyway (will retry)")
        }

        activeProxyCore = ProxyCore.SingBox
        val exitProfile = requireNotNull(profile)
        val routing = JvmVpnSettings.loadRouting()
        // WG / freeturn TCP is IPv4-only → force A-only DNS so dual-stack sites don't dead-end.
        val traffic = JvmVpnSettings.loadTraffic().copy(domainStrategy = "ipv4_only")
        val profilesState = JvmVpnSettings.loadRoutingProfiles()
        val routingProfile: RoutingProfile? = null

        val chainProxy = if (outboundType == VkTurnConfig.OUTBOUND_WIREGUARD) {
            vk.chainProxyLink.takeIf { it.isNotBlank() }
                ?.let { ShareLinkParser.parse(it) }?.takeIf { it.isComplete() }
        } else null

        val proxyForCore = when (outboundType) {
            VkTurnConfig.OUTBOUND_PROXY -> exitProfile
            else -> chainProxy
        }
        val useXray = proxyForCore != null &&
            outboundType != VkTurnConfig.OUTBOUND_AMNEZIAWG &&
            vk.resolvedProxyCore(proxyForCore) == ProxyCore.Xray

        if (useXray) {
            val xrayJson = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) {
                log("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp, Xray)")
                XrayConfig.build(
                    profile = exitProfile,
                    listenPort = listenPort,
                    listenHost = listenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    logLevel = "debug",
                    traffic = traffic,
                    routingProfile = null,
                    blockQuic = false,
                )
            } else {
                log("VK-TURN chaining proxy ${chainProxy!!.displayName()} over WireGuard (Xray)")
                XrayConfig.build(
                    profile = chainProxy,
                    wireguardBase = exitProfile,
                    listenPort = listenPort,
                    listenHost = listenHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    logLevel = "debug",
                    traffic = traffic,
                    routingProfile = null,
                    blockQuic = false,
                )
            }
            activeProxyCore = ProxyCore.Xray
            log("Starting Xray (VK-TURN, $outboundType) via $listenAddr")
            YpTunCore.xrayStart(xrayJson)
        } else {
            val json = when (outboundType) {
                VkTurnConfig.OUTBOUND_AMNEZIAWG -> {
                    log("VK-TURN exit: AmneziaWG over VK")
                    val awgSocks = prepareAmneziaWgProxy(exitProfile, listenPort)
                    SingBoxConfig.build(
                        profile = awgSocks,
                        listenPort = listenPort,
                        listenHost = listenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        autoDetectInterface = true,
                        routing = routing,
                        traffic = traffic,
                        routingProfile = routingProfile,
                        singboxGeositeBase = profilesState.singboxGeositeBase,
                        singboxGeoipBase = profilesState.singboxGeoipBase,
                        logLevel = "debug",
                        dnsStrategyOverride = "ipv4_only",
                        blockQuic = false,
                        tunMode = requestedTun,
                        splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                        splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
                    )
                }

                VkTurnConfig.OUTBOUND_PROXY -> {
                    log("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp)")
                    SingBoxConfig.build(
                        profile = exitProfile,
                        listenPort = listenPort,
                        listenHost = listenHost,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        autoDetectInterface = true,
                        routing = routing,
                        traffic = traffic,
                        routingProfile = routingProfile,
                        singboxGeositeBase = profilesState.singboxGeositeBase,
                        singboxGeoipBase = profilesState.singboxGeoipBase,
                        logLevel = "debug",
                        dnsStrategyOverride = "ipv4_only",
                        blockQuic = false,
                        tunMode = requestedTun,
                        splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                        splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
                    )
                }

                else -> {
                    if (chainProxy != null) {
                        log("VK-TURN chaining proxy ${chainProxy.displayName()} over WireGuard")
                        SingBoxConfig.build(
                            profile = chainProxy,
                            wireguardBase = exitProfile,
                            listenPort = listenPort,
                            listenHost = listenHost,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            autoDetectInterface = true,
                            routing = routing,
                            traffic = traffic,
                            routingProfile = routingProfile,
                            singboxGeositeBase = profilesState.singboxGeositeBase,
                            singboxGeoipBase = profilesState.singboxGeoipBase,
                            logLevel = "debug",
                            dnsStrategyOverride = "ipv4_only",
                            blockQuic = false,
                            tunMode = requestedTun,
                            splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                            splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
                        )
                    } else {
                        SingBoxConfig.build(
                            profile = exitProfile,
                            listenPort = listenPort,
                            listenHost = listenHost,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            autoDetectInterface = true,
                            routing = routing,
                            traffic = traffic,
                            routingProfile = routingProfile,
                            singboxGeositeBase = profilesState.singboxGeositeBase,
                            singboxGeoipBase = profilesState.singboxGeoipBase,
                            logLevel = "debug",
                            dnsStrategyOverride = "ipv4_only",
                            blockQuic = false,
                            tunMode = requestedTun,
                            splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                            splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
                        )
                    }
                }
            }
            log("Starting sing-box (VK-TURN, $outboundType) via $listenAddr")
            YpTunCore.sbStart(json)
        }

        if (!awaitSocksPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("VK-TURN SOCKS port $listenPort did not open")
        }
        log("VK-TURN ready on $listenHost:$listenPort")
    }

    private suspend fun awaitVkTurnRelayReady(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (YpTunCore.ftConnectedStreams() > 0) return true
            delay(200)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Local UDP tunnels (AmneziaWG / Hysteria2 → local SOCKS), mirrors prepare*Proxy on Android

    private suspend fun prepareAmneziaWgProxy(profile: ProxyProfile, socksPort: Int): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_AMNEZIAWG) return profile
        runCatching { YpTunCore.awgStop() }
        val port = awgLocalPort(socksPort)
        val listen = "127.0.0.1:$port"
        log("Starting AmneziaWG SOCKS on $listen")
        YpTunCore.awgStart(profile.awgConfig, listen)
        if (!awaitSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("AmneziaWG SOCKS port $port did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$port,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "AmneziaWG" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = port,
            rawOutbound = raw,
        )
    }

    private suspend fun prepareHysteria2Proxy(profile: ProxyProfile, socksPort: Int): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_HYSTERIA2) return profile
        runCatching { YpTunCore.hy2Stop() }
        val cfg = buildJsonObject {
            put("server", profile.server)
            put("port", profile.serverPort)
            if (profile.hy2Ports.isNotBlank()) put("ports", profile.hy2Ports)
            put("auth", profile.password)
            put("sni", profile.sni.ifBlank { profile.server })
            put("insecure", profile.allowInsecure)
            if (profile.hy2Obfs.isNotBlank()) {
                put("obfs", profile.hy2Obfs)
                put("obfsPassword", profile.hy2ObfsPassword)
            }
            if (profile.hy2UpMbps > 0) put("upMbps", profile.hy2UpMbps)
            if (profile.hy2DownMbps > 0) put("downMbps", profile.hy2DownMbps)
        }.toString()
        val port = hy2LocalPort(socksPort)
        val listen = "127.0.0.1:$port"
        log("Starting Hysteria2 SOCKS on $listen")
        YpTunCore.hy2Start(cfg, listen)
        if (!awaitSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Hysteria2 SOCKS port $port did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$port,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "Hysteria2" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = port,
            rawOutbound = raw,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Geo assets / routing-profile degradation (mirrors Android helpers)

    private fun ensureGeoAssetPath(profile: RoutingProfile?): String {
        if (profile == null || !profile.needsGeoFiles()) return ""
        val state = JvmVpnSettings.loadRoutingProfiles()
        val geoip = profile.geoipUrl.ifBlank { state.geoipUrl }
        val geosite = profile.geositeUrl.ifBlank { state.geositeUrl }
        val ok = JvmGeoAssets.ensureAssets(geoip, geosite)
        return if (ok) {
            log("Geo databases ready for routing profile '${profile.displayName()}'")
            JvmGeoAssets.assetDir().absolutePath
        } else {
            log("Geo databases unavailable; profile geo rules will be skipped on Xray")
            ""
        }
    }

    private fun xrayRoutingProfile(profile: RoutingProfile?, assetPath: String): RoutingProfile? {
        if (profile == null) return null
        if (profile.needsGeoFiles() && assetPath.isEmpty()) {
            log("Geo databases unavailable — applying '${profile.displayName()}' without geo selectors")
            return profile.withoutGeoSelectors()
        }
        return profile
    }

    // ---------------------------------------------------------------------------------------
    // Port helpers

    private fun isLocalSocksPortOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
    }.isSuccess

    private suspend fun awaitSocksPortOpen(port: Int, timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksPortOpen(port)) return true
            delay(200)
        }
        return false
    }

    private companion object {
        const val MOBILE_READY_TIMEOUT_MS = 25_000
        const val VKTURN_RELAY_READY_TIMEOUT_MS = 20_000

        // Proxy types xray-core can serve from typed fields (same as Android).
        val XRAY_SUPPORTED_TYPES = setOf(
            ProxyProfile.TYPE_VLESS,
            ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN,
            ProxyProfile.TYPE_SHADOWSOCKS,
        )
    }
}
