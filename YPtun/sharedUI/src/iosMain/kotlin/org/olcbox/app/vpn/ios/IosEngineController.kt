package org.olcbox.app.vpn.ios

import kotlinx.coroutines.delay
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.importer.VkTurnComposer
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.MasterDnsConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.ios.IosCoreBridge
import org.olcbox.app.ios.orThrow
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.xray.XrayConfig
import kotlin.time.TimeSource

/**
 * iOS engine orchestration, run inside the packet-tunnel extension: starts the core(s) for a location
 * and leaves a SOCKS5 endpoint on 127.0.0.1:[start]'s listenPort, which hev-socks5-tunnel then feeds
 * the whole TUN into — the same topology as Android (every engine → local SOCKS → hev bridge).
 *
 * Ported from DesktopEngineController (same in-process core API) with the mobile parameters of
 * OlcboxVpnService: no TUN inside the cores, no process matching, and no interface detection — the
 * extension's own sockets never enter its tunnel on iOS, which is what VpnService.protect() buys
 * Android and interface pinning buys the desktop.
 */
internal class IosEngineController(
    private val core: IosCoreBridge,
    private val log: (String) -> Unit,
) {
    var activeProxyCore: ProxyCore = ProxyCore.SingBox
        private set

    private var masterDnsProxyActive = false

    /** olcRTC's local SOCKS port when chaining; the proxy core dials its outbound through it. */
    private fun chainOlcrtcPort(socksPort: Int) = socksPort + 1

    /** AmneziaWG's local SOCKS port (awgproxy). */
    private fun awgLocalPort(socksPort: Int) = socksPort + 2

    /**
     * Starts the engine(s) for [location]; on return the SOCKS5 endpoint accepts connections.
     * Throws with a user-facing message on failure (the caller then runs [stopAll]).
     */
    suspend fun start(
        location: LocationConfig,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        masterDnsProxyActive = false
        val config = location.normalized()
        when (config.engine) {
            EngineType.Stealth -> startStealth(config, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.Standard,
            EngineType.Chain -> startSingBoxOrXray(config, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.VkTurn -> startVkTurn(config, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.MasterDns -> startMasterDns(config, listenPort, socksUsername, socksPassword)
            // OpenFlux runs as a subprocess on Android/desktop (its core panics); iOS has no subprocesses.
            EngineType.OpenFlux -> throw IllegalStateException("OpenFlux на iOS пока не поддерживается")
        }
    }

    fun stopAll() {
        runCatching { core.sbStop() }
        runCatching { core.xrayStop() }
        runCatching { core.awgStop() }
        runCatching { core.ftStop() }
        runCatching { core.wdttStop() }
        runCatching { core.masterDnsStop() }
        runCatching { core.rtcStop() }
        masterDnsProxyActive = false
    }

    fun coreRunning(engine: EngineType): Boolean = when (engine) {
        EngineType.Stealth -> core.rtcRunning()
        EngineType.Standard -> proxyCoreRunning()
        EngineType.Chain -> core.rtcRunning() && proxyCoreRunning()
        EngineType.VkTurn -> (core.ftRunning() || core.wdttRunning()) && proxyCoreRunning()
        EngineType.MasterDns -> core.masterDnsRunning() && (!masterDnsProxyActive || proxyCoreRunning())
        EngineType.OpenFlux -> false
    }

    private fun proxyCoreRunning(): Boolean =
        if (activeProxyCore == ProxyCore.Xray) core.xrayRunning() else core.sbRunning()

    // ---------------------------------------------------------------------------------------
    // Stealth (olcRTC)

    private suspend fun startStealth(
        config: LocationConfig,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        require(!IosNet.isLocalPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }
        startOlcRtc(config, listenPort, socksUsername, socksPassword, deviceId)
        log("olcRTC ready on 127.0.0.1:$listenPort")
    }

    private fun startOlcRtc(config: LocationConfig, port: Int, user: String, pass: String, deviceId: String) {
        applyTelemostCookies(config)
        core.rtcSetDns(RTC_DNS).takeIf { it.isNotEmpty() }?.let { log("olcRTC: set dns failed: $it") }
        core.rtcSetVp8Options(config.vp8Fps, config.vp8Batch)
            .takeIf { it.isNotEmpty() }?.let { log("olcRTC: set vp8 options failed: $it") }
        log("Starting olcRTC provider=${config.bypassProvider}, transport=${config.transport}, room=${config.id}")
        core.rtcStart(
            carrier = config.bypassProvider,
            transport = config.transport,
            roomId = config.id,
            clientId = deviceId,
            keyHex = config.key,
            socksPort = port,
            socksUser = user,
            socksPass = pass,
        ).orThrow("olcRTC start failed")
        core.rtcWaitReady(MOBILE_READY_TIMEOUT_MS).orThrow("olcRTC not ready")
    }

    private fun applyTelemostCookies(config: LocationConfig) {
        val behavior = IosSharedStore.loadAppBehavior()
        val use = behavior.telemostCookiesEnabled &&
            behavior.telemostCookies.isNotBlank() &&
            LocationConfig.normalizeProvider(config.bypassProvider) == LocationConfig.PROVIDER_TELEMOST
        runCatching { core.rtcSetTelemostCookies(if (use) behavior.telemostCookies.trim() else "") }
        if (use) log("Applied Telemost cookies")
    }

    // ---------------------------------------------------------------------------------------
    // Standard / Chain (sing-box or xray, mirrors OlcboxVpnService.startSingBoxCore)

    private suspend fun startSingBoxOrXray(
        config: LocationConfig,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        val profile = config.proxy
        check(profile != null && profile.isComplete()) { "No proxy configured" }

        val chained = config.engine == EngineType.Chain
        val chainPort = chainOlcrtcPort(listenPort)
        // Optional SECOND/cascade proxy, dialed THROUGH the main. A 2nd proxy on the SAME node as the
        // main is dropped — a cascade into itself cannot work.
        val secondProfile = config.proxy2?.takeIf { it.isComplete() }?.let { second ->
            if (profile.isSameNodeAs(second)) {
                log("2nd (cascade) proxy is the same node as the main — ignoring it (exit via the main)")
                null
            } else second
        }

        require(!IosNet.isLocalPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        if (chained) {
            startOlcRtc(config, chainPort, socksUsername, socksPassword, deviceId)
            log("olcRTC chain ready on 127.0.0.1:$chainPort")
        }

        // AmneziaWG raises a local SOCKS (awgproxy) that sing-box routes through; Hysteria2 and Naive
        // are native sing-box outbounds that carry UDP/QUIC themselves.
        val isAwg = profile.type == ProxyProfile.TYPE_AMNEZIAWG
        val isHy2 = profile.type == ProxyProfile.TYPE_HYSTERIA2
        val isNaive = profile.type == ProxyProfile.TYPE_NAIVE
        if (profile.type == ProxyProfile.TYPE_TRUSTTUNNEL) {
            throw IllegalStateException("Trust Tunnel на iOS пока не поддерживается")
        }
        // The iOS cores are built without with_naive_outbound (no cronet for iOS in this tree).
        if (isNaive) throw IllegalStateException("NaïveProxy на iOS пока не поддерживается")
        val effectiveProfile = if (isAwg) prepareAmneziaWgProxy(profile, listenPort) else profile

        val traffic = IosSharedStore.loadTraffic()
        val routing = IosSharedStore.loadRouting()
        val profilesState = IosSharedStore.loadRoutingProfiles()
        val routingProfile = profilesState.resolve(config.routingProfileId)
        if (routingProfile == null) {
            log("Routing: NO profile applied — all traffic via proxy")
        } else {
            log("Routing: applying '${routingProfile.displayName()}'")
        }

        // The app-wide engine choice applies only when this server's own core is "Auto".
        val globalCore = IosSharedStore.loadAppBehavior().globalProxyCore
        activeProxyCore = if (isAwg || isHy2 || isNaive) ProxyCore.SingBox else config.resolvedCore(globalCore)
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
            ensureGeoAssetPath(routingProfile, profilesState).isEmpty()
        ) {
            activeProxyCore = ProxyCore.SingBox
            log("Geo databases unavailable for Xray → using sing-box for routing")
        }
        if (secondProfile?.network == ProxyProfile.NETWORK_XHTTP && activeProxyCore != ProxyCore.Xray) {
            activeProxyCore = ProxyCore.Xray
            log("Second (cascade) proxy uses xhttp → forcing Xray core")
        }

        val secondChainableOnRaw = secondProfile?.type in setOf(
            ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN, ProxyProfile.TYPE_SHADOWSOCKS,
        )
        when {
            secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() && !secondChainableOnRaw ->
                log("WARNING: the 2nd proxy is a '${secondProfile.type}' CLIENT TUNNEL, not an Xray exit outbound — it cannot cascade over a custom Xray config and is ignored.")
            secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() ->
                log("Cascade: exit via 2nd proxy '${secondProfile.displayName()}' over the custom Xray config")
            secondProfile != null ->
                log("Cascade: exit via 2nd proxy '${secondProfile.displayName()}' over main '${effectiveProfile.displayName()}'")
            config.proxy2 != null ->
                log("Cascade: a 2nd proxy IS set but its link is incomplete/unparsed — dropped, exit via the 1st proxy")
        }

        if (activeProxyCore == ProxyCore.Xray) {
            val rawXray = effectiveProfile.rawXrayConfig
            var assetPath = ""
            val json = if (!rawXray.isNullOrBlank()) {
                // A full user Xray config (JSON subscription) runs VERBATIM and its OWN routing wins; its
                // geosite:/geoip: selectors need the geo .dat, stripped only if that can't be fetched.
                val rawNeedsGeo = rawXray.contains("geosite:") || rawXray.contains("geoip:")
                assetPath = when {
                    rawNeedsGeo -> ensureRawConfigGeoAssetPath(routingProfile, profilesState)
                    routingProfile != null -> ensureGeoAssetPath(routingProfile, profilesState)
                    else -> ""
                }
                val stripGeo = rawNeedsGeo && assetPath.isEmpty()
                log(
                    "Starting Xray with custom config (embedded routing honored" +
                        (if (rawNeedsGeo && assetPath.isNotEmpty()) ", geo db loaded for its geosite:/geoip:" else "") +
                        (if (stripGeo) ", geo db unavailable -> geo selectors stripped" else "") +
                        (if (routingProfile != null) " + routing profile '${routingProfile.displayName()}'" else "") +
                        ")"
                )
                XrayConfig.prepareRaw(
                    rawConfigJson = rawXray,
                    listenPort = listenPort,
                    listenHost = LISTEN_HOST,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                    fakeDnsEnabled = traffic.fakeDnsEnabled,
                    stripGeoSelectors = stripGeo,
                    forceIpv4 = traffic.domainStrategy.let { it == "ipv4_only" || it == "prefer_ipv4" },
                    secondProfile = secondProfile,
                )
            } else {
                assetPath = ensureGeoAssetPath(routingProfile, profilesState)
                XrayConfig.build(
                    profile = effectiveProfile,
                    listenPort = listenPort,
                    listenHost = LISTEN_HOST,
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
                    bypassLan = routing.bypassLan,
                    fakeDnsSpec = config.fakeDns,
                )
            }
            log("Starting Xray engine=${config.engine}, server=${effectiveProfile.server}:${effectiveProfile.serverPort}")
            if (assetPath.isNotEmpty()) core.xraySetAssetPath(assetPath)
            core.xrayStart(json).orThrow("xray start failed")
        } else {
            if (isAwg) log("AmneziaWG outbound: QUIC allowed + sniff-override→IPv4")
            if (isHy2) log("Hysteria2 outbound: native sing-box (QUIC allowed)")
            if (isNaive) log("Naive outbound: native sing-box")
            val json = SingBoxConfig.build(
                profile = effectiveProfile,
                listenPort = listenPort,
                listenHost = LISTEN_HOST,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = if (chained) chainPort else null,
                olcrtcChainUser = if (chained) socksUsername else "",
                olcrtcChainPass = if (chained) socksPassword else "",
                routing = routing,
                traffic = traffic,
                advanced = config.advanced,
                routingProfile = routingProfile,
                singboxGeositeBase = profilesState.singboxGeositeBase,
                singboxGeoipBase = profilesState.singboxGeoipBase,
                blockQuic = !(isAwg || isHy2 || (isNaive && effectiveProfile.naiveQuic)),
                sniffOverrideDestination = isAwg,
                secondProfile = secondProfile,
                fakeDnsSpec = config.fakeDns,
                preferTcpRemoteDns = isAwg && secondProfile == null,
                cacheFilePath = IosSharedStore.path(SINGBOX_CACHE_FILE),
            )
            log("Starting sing-box engine=${config.engine} via ${effectiveProfile.server}:${effectiveProfile.serverPort}")
            core.sbStart(json).orThrow("sing-box start failed")
        }

        if (!IosNet.awaitLocalPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Proxy SOCKS port $listenPort did not open")
        }
        log("Proxy core ready on $LISTEN_HOST:$listenPort")
    }

    // ---------------------------------------------------------------------------------------
    // MasterDNS (+ optional proxy over it)

    private suspend fun startMasterDns(
        config: LocationConfig,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) {
        val masterDns = config.masterDns
        check(masterDns != null && masterDns.isComplete()) { "MasterDNS not configured" }

        val proxy = masterDns.proxyLink.takeIf { it.isNotBlank() }?.let { link ->
            (ShareLinkParser.parse(link)
                ?: org.olcbox.app.data.share.YptunInboundCodec.parse(link)?.let { it.proxy ?: it.proxy2 })
                ?.takeIf { it.isComplete() }
        }
        if (masterDns.proxyLink.isNotBlank() && proxy == null) {
            log("MasterDNS: proxy link present but could not be parsed — exiting via the MasterDNS SOCKS directly")
        }
        val useProxy = proxy != null
        masterDnsProxyActive = useProxy
        val masterDnsPort = if (useProxy) chainOlcrtcPort(listenPort) else listenPort

        require(!IosNet.isLocalPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        val masterDnsAddr = "$LISTEN_HOST:$masterDnsPort"
        log(
            "Starting MasterDNS on $masterDnsAddr (domains=${masterDns.domains}, " +
                "resolvers=${masterDns.resolverList().size}, " +
                "encryption=${MasterDnsConfig.ENCRYPTION_LABELS.getOrElse(masterDns.encryptionMethod) { "?" }})"
        )
        runCatching { core.masterDnsStop() }
        core.masterDnsStart(
            workDir = IosSharedStore.path("masterdns"),
            domains = masterDns.domains,
            key = masterDns.encryptionKey,
            encryptionMethod = masterDns.encryptionMethod,
            resolvers = masterDns.resolvers,
            listenAddr = masterDnsAddr,
            // The chain detour dials the internal port without credentials, so it must run no-auth.
            socksUser = if (useProxy) "" else socksUsername,
            socksPass = if (useProxy) "" else socksPassword,
            balancingStrategy = masterDns.balancingStrategy,
            packetDuplication = masterDns.packetDuplication,
            uploadCompression = 0,
            downloadCompression = 0,
        ).orThrow("MasterDNS start failed")
        if (!IosNet.awaitLocalPortOpen(masterDnsPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("MasterDNS SOCKS port $masterDnsPort did not open" +
                core.masterDnsLastError().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty())
        }
        log("MasterDNS ready on $masterDnsAddr")
        if (proxy == null) return

        startProxyOverTunnel("MasterDNS", config, proxy, masterDnsPort, listenPort, socksUsername, socksPassword) { p, g ->
            masterDns.resolvedProxyCore(p, g)
        }
    }

    /**
     * Fronts a tunnel's local SOCKS ([tunnelPort], no auth) with a proxy core on [listenPort]: apps →
     * core → [proxy] → tunnel SOCKS → tunnel exit → internet. Both tunnels using it are slow and
     * TCP-only, which is what the tuning below is about (see the Android path).
     */
    private suspend fun startProxyOverTunnel(
        label: String,
        config: LocationConfig,
        proxy: ProxyProfile,
        tunnelPort: Int,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        resolveCore: (ProxyProfile, ProxyCore) -> ProxyCore,
    ) {
        val traffic = IosSharedStore.loadTraffic()
        val routing = IosSharedStore.loadRouting()
        val profilesState = IosSharedStore.loadRoutingProfiles()
        val routingProfile = profilesState.resolve(config.routingProfileId)
        val globalCore = IosSharedStore.loadAppBehavior().globalProxyCore
        val profileWantsXray = routingProfile != null &&
            (routingProfile.needsGeoFiles() || routingProfile.dnsHosts.isNotEmpty()) &&
            proxy.type in XRAY_SUPPORTED_TYPES
        val useXray = resolveCore(proxy, globalCore) == ProxyCore.Xray || profileWantsXray
        log("$label chaining proxy ${proxy.displayName()} over the tunnel (${if (useXray) "Xray" else "sing-box"})")

        if (useXray) {
            val assetPath = ensureGeoAssetPath(routingProfile, profilesState)
            val xrayJson = XrayConfig.build(
                profile = proxy,
                listenPort = listenPort,
                listenHost = LISTEN_HOST,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = tunnelPort,
                traffic = traffic,
                routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                blockQuic = true,
                forceFamilyResolve = false,
                chainViaDialerProxy = true,
                handshakeTimeoutSec = 30,
                directViaBase = true,
            )
            activeProxyCore = ProxyCore.Xray
            if (assetPath.isNotEmpty()) core.xraySetAssetPath(assetPath)
            core.xrayStart(xrayJson).orThrow("xray start failed")
        } else {
            val json = SingBoxConfig.build(
                profile = proxy,
                listenPort = listenPort,
                listenHost = LISTEN_HOST,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = tunnelPort,
                routing = routing,
                traffic = traffic,
                routingProfile = routingProfile,
                singboxGeositeBase = profilesState.singboxGeositeBase,
                singboxGeoipBase = profilesState.singboxGeoipBase,
                blockQuic = true,
                forceFamilyResolve = false,
                allowLocalResolve = false,
                directViaBase = true,
                cacheFilePath = IosSharedStore.path(SINGBOX_CACHE_FILE),
            )
            activeProxyCore = ProxyCore.SingBox
            core.sbStart(json).orThrow("sing-box start failed")
        }

        if (!IosNet.awaitLocalPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("$label proxy SOCKS port $listenPort did not open")
        }
        log("$label proxy ready on $LISTEN_HOST:$listenPort")
    }

    // ---------------------------------------------------------------------------------------
    // VK-TURN (mirrors OlcboxVpnService.startVkTurnCore)

    /** Set while a VK captcha waits for the user; the app polls it and shows the page. */
    var pendingCaptchaUrl: String = ""
        private set

    private suspend fun startVkTurn(
        config: LocationConfig,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        val vk = config.vkturn
        // Clamp the exit's WireGuard/AmneziaWG MTU to what VK TURN + DTLS + RTP-obf can carry.
        var profile = VkTurnComposer.clampVkTurnMtu(config.proxy)
        val usesWdtt = vk?.usesWdtt() == true
        val outboundType = vk?.outbound?.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD }
            ?: VkTurnConfig.OUTBOUND_WIREGUARD
        val outboundConfigured = when {
            usesWdtt -> true // WDTT fetches its WireGuard config FROM the server (GETCONF)
            outboundType == VkTurnConfig.OUTBOUND_AMNEZIAWG -> !profile?.awgConfig.isNullOrBlank()
            outboundType == VkTurnConfig.OUTBOUND_PROXY -> profile != null &&
                profile.server.isNotBlank() && profile.serverPort in 1..65535
            else -> !profile?.rawOutbound.isNullOrBlank()
        }
        check(vk != null && vk.isComplete() && outboundConfigured) { "VK-TURN not configured" }

        require(!IosNet.isLocalPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        val listenAddr = "127.0.0.1:${vk.listenPort}"
        if (usesWdtt) {
            log(
                "Starting VK-TURN WDTT Plus core on $listenAddr (peer=${vk.wdttPeerAddr()}, " +
                    "workers=${vk.wdttWorkers.takeIf { it > 0 }?.toString() ?: "auto"}, " +
                    "rt=${vk.wdttPlus.rtNetworkMode}, masque=${vk.wdttPlus.masque})"
            )
            core.wdttStart(
                vk.wdttCoreOptionsJson(
                    listen = listenAddr,
                    deviceId = deviceId,
                    masqueConfigPath = IosSharedStore.path("wdtt-masque.json"),
                )
            ).orThrow("WDTT start failed")
        } else {
            log("Starting VK-TURN freeturn listener on $listenAddr")
            core.ftStart(vk.uri, listenAddr, vk.vkLink, vk.streams).orThrow("VK-TURN start failed")
        }

        // Prepared while the relay handshake (VK auth → DTLS → TURN) is in flight.
        val routing = IosSharedStore.loadRouting()
        // WG / freeturn TCP is IPv4-only → force A-only DNS so dual-stack sites don't dead-end.
        val traffic = IosSharedStore.loadTraffic().copy(domainStrategy = "ipv4_only")
        val profilesState = IosSharedStore.loadRoutingProfiles()

        if (usesWdtt) {
            // The config only arrives once the first worker has a TURN session, so waiting on it doubles
            // as the relay-ready gate.
            val wgConf = core.wdttWaitConfig(VKTURN_RELAY_READY_TIMEOUT_MS)
            when {
                wgConf.isNotEmpty() -> {
                    profile = buildWdttWgProfile(wgConf, vk.listenPort)
                    log("VK-TURN WDTT relay up; WireGuard config from server applied")
                }
                !profile?.rawOutbound.isNullOrBlank() ->
                    log("VK-TURN WDTT: no GETCONF — falling back to the stored WireGuard config")
                else -> throw IllegalStateException(
                    "WDTT: no WireGuard config from server (GETCONF) and none stored" +
                        core.wdttLastError().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                )
            }
        } else if (awaitVkTurnRelayReady(VKTURN_RELAY_READY_TIMEOUT_MS)) {
            log("VK-TURN relay up (${core.ftConnectedStreams()} stream(s)); starting WireGuard")
        } else {
            log("VK-TURN relay not ready yet; starting outbound anyway (will retry)")
        }

        activeProxyCore = ProxyCore.SingBox
        val exitProfile = requireNotNull(profile)

        // Chained exit proxy on top of the tunnel (WireGuard/WDTT and AmneziaWG exits).
        val chainProxy = if (outboundType != VkTurnConfig.OUTBOUND_PROXY) {
            vk.chainProxyLink.takeIf { it.isNotBlank() }
                ?.let { ShareLinkParser.parse(it) }?.takeIf { it.isComplete() }
        } else null

        val awgSocks = if (outboundType == VkTurnConfig.OUTBOUND_AMNEZIAWG) {
            prepareAmneziaWgProxy(exitProfile, listenPort)
        } else null

        val proxyForCore = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) exitProfile else chainProxy
        val useXray = proxyForCore != null && vk.resolvedProxyCore(proxyForCore) == ProxyCore.Xray

        if (useXray) {
            val xrayJson = when (outboundType) {
                VkTurnConfig.OUTBOUND_PROXY -> {
                    log("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp, Xray)")
                    XrayConfig.build(
                        profile = exitProfile,
                        listenPort = listenPort,
                        listenHost = LISTEN_HOST,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        traffic = traffic,
                        routingProfile = null,
                        blockQuic = false,
                    )
                }
                VkTurnConfig.OUTBOUND_AMNEZIAWG -> {
                    log("VK-TURN chaining proxy ${chainProxy!!.displayName()} over AmneziaWG (Xray)")
                    XrayConfig.build(
                        profile = chainProxy,
                        olcrtcChainPort = awgLocalPort(listenPort),
                        listenPort = listenPort,
                        listenHost = LISTEN_HOST,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        traffic = traffic,
                        routingProfile = null,
                        blockQuic = false,
                        chainViaDialerProxy = true,
                        directViaBase = true,
                    )
                }
                else -> {
                    log("VK-TURN chaining proxy ${chainProxy!!.displayName()} over WireGuard (Xray)")
                    XrayConfig.build(
                        profile = chainProxy,
                        wireguardBase = exitProfile,
                        listenPort = listenPort,
                        listenHost = LISTEN_HOST,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword,
                        traffic = traffic,
                        routingProfile = null,
                        blockQuic = false,
                    )
                }
            }
            activeProxyCore = ProxyCore.Xray
            log("Starting Xray (VK-TURN, $outboundType) via $listenAddr")
            core.xrayStart(xrayJson).orThrow("xray start failed")
        } else {
            val json = when (outboundType) {
                VkTurnConfig.OUTBOUND_AMNEZIAWG -> {
                    log(
                        if (chainProxy != null) "VK-TURN chaining proxy ${chainProxy.displayName()} over AmneziaWG"
                        else "VK-TURN exit: AmneziaWG over VK"
                    )
                    vkTurnSingBox(
                        // With a chain proxy the chain IS the exit and dials THROUGH the AWG SOCKS as a
                        // base detour — so `direct` traffic still exits through the tunnel.
                        profile = chainProxy ?: requireNotNull(awgSocks),
                        chainPort = if (chainProxy != null) awgLocalPort(listenPort) else null,
                        listenPort = listenPort, socksUsername = socksUsername, socksPassword = socksPassword,
                        routing = routing, traffic = traffic, profilesState = profilesState,
                        sniffOverrideDestination = true,
                        preferTcpRemoteDns = chainProxy == null,
                        directViaBase = chainProxy != null,
                    )
                }
                VkTurnConfig.OUTBOUND_PROXY -> {
                    log("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp)")
                    vkTurnSingBox(
                        profile = exitProfile, listenPort = listenPort,
                        socksUsername = socksUsername, socksPassword = socksPassword,
                        routing = routing, traffic = traffic, profilesState = profilesState,
                    )
                }
                else -> if (chainProxy != null) {
                    log("VK-TURN chaining proxy ${chainProxy.displayName()} over WireGuard")
                    vkTurnSingBox(
                        profile = chainProxy, wireguardBase = exitProfile, listenPort = listenPort,
                        socksUsername = socksUsername, socksPassword = socksPassword,
                        routing = routing, traffic = traffic, profilesState = profilesState,
                    )
                } else {
                    vkTurnSingBox(
                        profile = exitProfile, listenPort = listenPort,
                        socksUsername = socksUsername, socksPassword = socksPassword,
                        routing = routing, traffic = traffic, profilesState = profilesState,
                    )
                }
            }
            log("Starting sing-box (VK-TURN, $outboundType) via $listenAddr")
            core.sbStart(json).orThrow("sing-box start failed")
        }

        if (!IosNet.awaitLocalPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("VK-TURN SOCKS port $listenPort did not open")
        }
        log("VK-TURN ready on $LISTEN_HOST:$listenPort")
    }

    private fun vkTurnSingBox(
        profile: ProxyProfile,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        routing: RoutingRules,
        traffic: TrafficSettings,
        profilesState: RoutingProfilesState,
        wireguardBase: ProxyProfile? = null,
        chainPort: Int? = null,
        sniffOverrideDestination: Boolean = false,
        preferTcpRemoteDns: Boolean = false,
        directViaBase: Boolean = false,
    ): String = SingBoxConfig.build(
        profile = profile,
        wireguardBase = wireguardBase,
        olcrtcChainPort = chainPort,
        listenPort = listenPort,
        listenHost = LISTEN_HOST,
        socksUsername = socksUsername,
        socksPassword = socksPassword,
        routing = routing,
        traffic = traffic,
        routingProfile = null,
        singboxGeositeBase = profilesState.singboxGeositeBase,
        singboxGeoipBase = profilesState.singboxGeoipBase,
        dnsStrategyOverride = "ipv4_only",
        // VK-TURN carries UDP natively — never blackhole QUIC on this engine.
        blockQuic = false,
        sniffOverrideDestination = sniffOverrideDestination,
        preferTcpRemoteDns = preferTcpRemoteDns,
        directViaBase = directViaBase,
        cacheFilePath = IosSharedStore.path(SINGBOX_CACHE_FILE),
    )

    /**
     * WireGuard outbound over the local WDTT listener, from the config the server returns — MTU clamped
     * to 1200: through VK TURN + DTLS + RTP-obf the real path MTU is well under the advertised 1280.
     */
    private fun buildWdttWgProfile(wgConf: String, listenPort: Int): ProxyProfile {
        var priv = ""
        var pub = ""
        var addr = ""
        var mtu = 0
        for (raw in wgConf.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val v = line.substring(eq + 1).trim()
            when (line.substring(0, eq).trim().lowercase()) {
                "privatekey" -> priv = v
                "publickey" -> pub = v
                "address" -> if (addr.isEmpty()) addr = v.substringBefore(',').trim()
                "mtu" -> mtu = v.toIntOrNull() ?: 0
            }
        }
        val localAddr = if (addr.isNotEmpty()) "\"$addr\"" else ""
        val effMtu = (if (mtu > 0) mtu else 1200).coerceAtMost(1200)
        val json = "{\"type\":\"wireguard\",\"server\":\"127.0.0.1\",\"server_port\":$listenPort," +
            "\"local_address\":[$localAddr],\"private_key\":\"$priv\",\"peer_public_key\":\"$pub\",\"mtu\":$effMtu}"
        return ProxyProfile(tag = "WDTT", type = "wireguard", server = "127.0.0.1", serverPort = listenPort, rawOutbound = json)
    }

    /**
     * Waits for the first VK-TURN stream. A manual VK captcha stops the clock: the user needs far more
     * than [timeoutMs] to solve one. The page is served by freeturn on localhost inside this extension;
     * [pendingCaptchaUrl] tells the app to show it.
     */
    private suspend fun awaitVkTurnRelayReady(timeoutMs: Int): Boolean {
        var mark = TimeSource.Monotonic.markNow()
        try {
            while (mark.elapsedNow().inWholeMilliseconds < timeoutMs) {
                if (core.ftConnectedStreams() > 0) return true
                val captcha = core.ftCaptchaUrl()
                if (captcha.isNotBlank() && captcha != pendingCaptchaUrl) {
                    pendingCaptchaUrl = captcha
                    log("VK просит капчу — откройте приложение, чтобы её решить")
                }
                // freeturn gives up on a manual captcha by itself (3 min), so this cannot wait forever.
                if (core.ftCaptchaActive()) mark = TimeSource.Monotonic.markNow()
                delay(50)
            }
            return false
        } finally {
            pendingCaptchaUrl = ""
        }
    }

    // ---------------------------------------------------------------------------------------
    // AmneziaWG → local SOCKS

    private suspend fun prepareAmneziaWgProxy(profile: ProxyProfile, socksPort: Int): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_AMNEZIAWG) return profile
        runCatching { core.awgStop() }
        val port = awgLocalPort(socksPort)
        val listen = "127.0.0.1:$port"
        log("Starting AmneziaWG SOCKS on $listen")
        core.awgStart(profile.awgConfig, listen).orThrow("AmneziaWG start failed")
        if (!IosNet.awaitLocalPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("AmneziaWG SOCKS port $port did not open")
        }
        return ProxyProfile(
            tag = profile.tag.ifBlank { "AmneziaWG" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = port,
            rawOutbound = "{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":$port,\"version\":\"5\"}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Geo assets

    /** Geo .dat for a VERBATIM config's own geosite:/geoip: selectors — not gated on a profile. */
    private fun ensureRawConfigGeoAssetPath(profile: RoutingProfile?, state: RoutingProfilesState): String {
        val geoip = profile?.geoipUrl?.takeIf { it.isNotBlank() } ?: state.geoipUrl
        val geosite = profile?.geositeUrl?.takeIf { it.isNotBlank() } ?: state.geositeUrl
        return if (runCatching { IosGeoAssets.ensureAssets(geoip, geosite) }.getOrDefault(false)) {
            IosGeoAssets.assetDir
        } else ""
    }

    private fun ensureGeoAssetPath(profile: RoutingProfile?, state: RoutingProfilesState): String {
        if (profile == null || !profile.needsGeoFiles()) return ""
        val ok = runCatching {
            IosGeoAssets.ensureAssets(profile.geoipUrl.ifBlank { state.geoipUrl }, profile.geositeUrl.ifBlank { state.geositeUrl })
        }.getOrDefault(false)
        return if (ok) {
            log("Geo databases ready for routing profile '${profile.displayName()}'")
            IosGeoAssets.assetDir
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

    private companion object {
        const val LISTEN_HOST = "127.0.0.1"
        const val MOBILE_READY_TIMEOUT_MS = 25_000
        const val VKTURN_RELAY_READY_TIMEOUT_MS = 20_000
        const val RTC_DNS = "1.1.1.1:53"
        const val SINGBOX_CACHE_FILE = "singbox-cache.db"

        // Proxy types xray-core can serve from typed fields (same as Android).
        val XRAY_SUPPORTED_TYPES = setOf(
            ProxyProfile.TYPE_VLESS,
            ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN,
            ProxyProfile.TYPE_SHADOWSOCKS,
        )
    }
}
