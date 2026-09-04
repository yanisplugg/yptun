package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.delay
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.VkTurnConfig
import org.olcbox.app.desktop.DesktopPaths
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

    /** Trust Tunnel's local SOCKS port (AdGuard client, SOCKS-only mode). Matches Android's offset. */
    private fun trustTunnelLocalPort(socksPort: Int) = socksPort + 5

    private val trustTunnel = DesktopTrustTunnel(log)

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

    private data class TunRequest(
        val splitMode: String,
        val processes: List<String>,
        val excludeAddresses: List<String>,
    )

    /** True after [start] when sing-box owns the TUN itself (no external tun2socks needed). */
    var tunHandledInCore: Boolean = false
        private set

    /**
     * True when the endpoint left on `listenHost:listenPort` accepts NO SOCKS credentials. Only the
     * bare dnstt path sets it: that local port is a transparent forwarder to the dnstt-server's own
     * SOCKS5, and a pipe cannot terminate an auth handshake. The TUN bridge / system proxy must then
     * connect anonymously.
     */
    var localSocksNoAuth: Boolean = false
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
        // Upstream server IPs to carve out of the in-core TUN (see SingBoxConfig.tunExcludeAddresses).
        tunExcludeAddresses: List<String> = emptyList(),
    ) {
        tunRequest = if (tunViaSingBox) {
            TunRequest(splitTunnelMode, splitTunnelProcesses, tunExcludeAddresses)
        } else {
            null
        }
        tunHandledInCore = false
        localSocksNoAuth = false
        dnsttProxyActive = false
        singBoxFrontActive = false
        val config = location.normalized()
        when (config.engine) {
            EngineType.Stealth -> startStealth(config, listenHost, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.Standard,
            EngineType.Chain -> startSingBoxOrXray(config, listenHost, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.VkTurn ->
                startVkTurn(config, listenHost, listenPort, socksUsername, socksPassword, deviceId)
            EngineType.Dnstt -> startDnstt(config, listenHost, listenPort, socksUsername, socksPassword)
        }
        if (requestedTun && !tunHandledInCore) {
            log("Per-process split tunneling unavailable (core is ${activeProxyCore}); falling back to tun2socks for all apps")
        }
    }

    /**
     * Path of sing-box's fakeip cache (see [SingBoxConfig.build]'s `cacheFilePath`). Only one sing-box
     * ever runs in-process (YpTunCore is a single global), so every tunnel shares the one db — which is
     * what keeps a synthetic IP bound to its domain when the user switches locations or reconnects.
     */
    private fun singBoxCachePath(): String =
        DesktopPaths.appDataDir().resolve("singbox-cache.db").toString()

    /**
     * sing-box's log file. sing-box opens it O_APPEND and never rotates it, and the desktop configs
     * run at `debug` — left alone the file grows without bound (hundreds of MB after a few sessions,
     * with the disk writes showing up as UI stutter). Drop it at start once it is past
     * [MAX_SINGBOX_LOG_BYTES]; the current session is what diagnostics actually need.
     */
    private fun singBoxLogPath(): String {
        val path = DesktopPaths.appDataDir().resolve("singbox.log")
        runCatching {
            if (java.nio.file.Files.exists(path) &&
                java.nio.file.Files.size(path) > MAX_SINGBOX_LOG_BYTES
            ) {
                java.nio.file.Files.delete(path)
            }
        }
        return path.toString()
    }

    fun stopAll() {
        trustTunnel.stop()
        YpTunCore.stopAll()
        // [start] is the only other place these are reset, and the olcRTC (Stealth) path never calls
        // it — it runs the olcrtc subprocess instead. So a stale tunHandledInCore=true, left by the
        // previous sing-box session, made the manager skip tun2socks on the NEXT olcRTC connect: the
        // log said "Windows TUN owned by sing-box" while nothing owned it and no TUN existed at all.
        tunHandledInCore = false
        localSocksNoAuth = false
        dnsttProxyActive = false
        singBoxFrontActive = false
    }

    fun coreRunning(engine: EngineType): Boolean = when (engine) {
        EngineType.Stealth -> YpTunCore.rtcRunning()
        EngineType.Standard -> proxyCoreRunning()
        EngineType.Chain -> YpTunCore.rtcRunning() && proxyCoreRunning()
        EngineType.VkTurn -> (YpTunCore.ftRunning() || YpTunCore.wdttRunning()) && proxyCoreRunning()
        // dnstt raises its own local forwarder; with a proxy-over-dnstt a proxy core fronts it.
        EngineType.Dnstt -> YpTunCore.dnsttRunning() && (!dnsttProxyActive || proxyCoreRunning())
    }

    /** True when the active dnstt engine also fronts a proxy core (proxy-over-dnstt). */
    private var dnsttProxyActive: Boolean = false

    /** True while a sing-box front owns the TUN in front of the Xray core (see [startSingBoxFront]). */
    private var singBoxFrontActive: Boolean = false

    private fun proxyCoreRunning(): Boolean = if (activeProxyCore == ProxyCore.Xray) {
        // With a front, the tunnel is only alive while BOTH halves are — a dead front means the TUN
        // has no reader at all, which the watchdog must see as "disconnected".
        YpTunCore.xrayRunning() && (!singBoxFrontActive || YpTunCore.sbRunning())
    } else {
        YpTunCore.sbRunning()
    }

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

    /**
     * A cascade only exists if the MAIN can open a TCP connection to the SECOND's server. Two hops
     * that resolve to the SAME machine cannot: a node's own routing normally refuses connections
     * back to itself (loop protection), which presents as "cascade connects and nothing loads" with
     * nothing wrong on our side at all.
     *
     * [ProxyProfile.isSameNodeAs] only compares the configured host, so two different hostnames on one
     * box slip past it — exactly the case in the user's subscription, where both hops resolved to
     * 185.230.141.216. Say so out loud instead of leaving the user to guess; the cascade is still
     * attempted, because a CDN-fronted pair can legitimately share an address.
     */
    private fun warnIfCascadeHopsShareAnAddress(main: ProxyProfile, second: ProxyProfile) {
        val mainIps = resolveAll(main.server)
        if (mainIps.isEmpty()) return
        val secondIps = resolveAll(second.server)
        if (secondIps.isEmpty()) return
        val shared = mainIps intersect secondIps
        if (shared.isEmpty()) return
        log(
            "WARNING: the cascade's two hops resolve to the SAME address (${shared.joinToString()}) — " +
                "'${main.displayName()}' would have to dial '${second.displayName()}' on its own machine, " +
                "which most servers refuse. If nothing loads, pick a 2nd proxy on a different server."
        )
    }

    private fun resolveAll(host: String): Set<String> = runCatching {
        java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Replace the proxy server domain with a host-resolved IPv4 (keeping the original host as TLS
     * SNI), so the core dials by IP and never re-resolves the server name per-connection through the
     * in-config bootstrap DNS (which stalled on desktop). No-op if the server is blank, already an IP,
     * a local address, or resolution fails (then the core resolves it as before).
     */
    private fun dialByServerIp(profile: ProxyProfile): ProxyProfile {
        val host = profile.server
        if (host.isBlank() || host == "127.0.0.1") return profile
        val looksLikeIp = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' } ||
            host.contains(':')
        if (looksLikeIp) return profile
        val ip = runCatching {
            java.net.InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()?.hostAddress
        }.getOrNull()
        if (ip.isNullOrBlank()) {
            log("Could not host-resolve $host; letting the core resolve it")
            return profile
        }
        log("Dialing proxy server by IP $ip (SNI=${profile.sni.ifBlank { host }})")
        return profile.copy(server = ip, sni = profile.sni.ifBlank { host })
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
        // Optional SECOND/cascade proxy: traffic exits via it, dialing THROUGH the main.
        //
        // Deliberately NOT run through [dialByServerIp]. That fix exists for the MAIN hop, whose
        // socket really is opened here — but the second hop is dialed BY THE MAIN PROXY, from the exit
        // server's vantage point, so pinning it to an address resolved on this machine is wrong twice
        // over: a censored/poisoned local resolver hands the exit a blackhole IP (TCP connects, TLS
        // completes against nothing, no traffic ever flows — the reported symptom), and even a healthy
        // local answer can be the wrong endpoint for geo-routed DNS. Leaving the domain in place lets
        // the main proxy resolve it, which is what Android does and what the cascade expects.
        //
        // Foolproofing (ported from Android): drop a 2nd proxy that points at the SAME node as the
        // main — a proxy into itself cannot work, and a bad import/subscription can produce one.
        val secondProfile = config.proxy2?.takeIf { it.isComplete() }?.let { second ->
            if (profile.isComplete() && profile.isSameNodeAs(second)) {
                log("2nd (cascade) proxy is the same node as the main — a cascade into itself is impossible; ignoring it (exit via the main)")
                null
            } else {
                warnIfCascadeHopsShareAnAddress(profile, second)
                second
            }
        }

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

        // AmneziaWG raises a local SOCKS (awgproxy) that sing-box routes through — a full UDP tunnel
        // modeled as a socks outbound. Hysteria2 is a NATIVE sing-box outbound since the 1.13 upgrade
        // (the old hysteria2proxy bridge and its YpHy2* exports are gone), so it is passed straight
        // through; like AWG it carries UDP/QUIC itself, so QUIC stays unblocked.
        val isAwg = profile.type == ProxyProfile.TYPE_AMNEZIAWG
        val isHy2 = profile.type == ProxyProfile.TYPE_HYSTERIA2
        // Trust Tunnel (AdGuard) — like AmneziaWG, raises a local SOCKS5 (its own client, SOCKS-only)
        // that the proxy routes through; a full TCP/UDP tunnel over HTTP2/QUIC.
        val isTrustTunnel = profile.type == ProxyProfile.TYPE_TRUSTTUNNEL
        val effectiveProfile = when {
            isAwg -> prepareAmneziaWgProxy(profile, listenPort)
            isTrustTunnel -> prepareTrustTunnelProxy(profile, listenPort)
            isHy2 -> profile
            // Dial the proxy server by its host-resolved IP (keeping the original host as TLS SNI) so
            // sing-box never re-resolves the server domain per-connection through the bootstrap DNS.
            // That bootstrap (AliDNS 223.5.5.5) is slow/unreachable from RU on desktop → "lookup
            // <server>: context deadline exceeded" killed every connection while only IP-based apps
            // (Telegram) survived. The host OS resolver does this once, reliably.
            else -> dialByServerIp(profile)
        }
        val isLocalUdpTunnel = isAwg || isHy2 || isTrustTunnel

        val traffic = JvmVpnSettings.loadTraffic()
        val routing = loadRoutingExpandingAsn()
        val profilesState = JvmVpnSettings.loadRoutingProfiles()
        val routingProfile = resolveProfileExpandingAsn(profilesState, config.routingProfileId)
        if (routingProfile == null) {
            log("Routing: NO profile applied — all traffic via proxy")
        } else {
            log("Routing: applying '${routingProfile.displayName()}'")
        }

        // The app-wide engine choice applies only when this server's own core is "Auto"; an explicit
        // per-server choice still wins (resolvedCore decides).
        val globalCore = JvmVpnSettings.loadAppBehavior().globalProxyCore
        activeProxyCore = if (isLocalUdpTunnel) ProxyCore.SingBox else config.resolvedCore(globalCore)
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

        // Say out loud what the cascade ended up doing — "the 2nd proxy does nothing" is otherwise
        // indistinguishable from "the 2nd proxy was silently dropped". Mirrors the Android messages.
        // A tunnel-type 2nd hop (AmneziaWG/WireGuard/Hysteria2) is a CLIENT tunnel, not an Xray exit
        // outbound, so it cannot be chained over a verbatim Xray config at all.
        val secondChainableOnRaw = secondProfile?.type in setOf(
            ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN, ProxyProfile.TYPE_SHADOWSOCKS,
        )
        when {
            secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() && !secondChainableOnRaw ->
                log("WARNING: the 2nd proxy is a '${secondProfile.type}' CLIENT TUNNEL, not an Xray exit outbound — it cannot cascade over a custom Xray config and is ignored. Use vless/vmess/trojan/ss for that.")
            secondProfile != null && !effectiveProfile.rawXrayConfig.isNullOrBlank() ->
                log("Cascade: exit via 2nd proxy '${secondProfile.displayName()}' over the custom Xray config")
            secondProfile != null ->
                log("Cascade: exit via 2nd proxy '${secondProfile.displayName()}' over main '${effectiveProfile.displayName()}'")
            config.proxy2 != null ->
                log("Cascade: a 2nd proxy IS set but its link is incomplete/unparsed — dropped, exit via the 1st proxy")
        }

        if (activeProxyCore == ProxyCore.Xray) {
            // xray-core has no TUN of its own, so on Windows this used to be handed to the external
            // tun2socks bridge — see [singBoxFrontPort] for why that never worked. Put xray on an
            // internal port and let a thin sing-box own the adapter instead.
            val frontXray = requestedTun
            val xrayPort = if (frontXray) singBoxFrontPort(listenPort) else listenPort
            val xrayHost = if (frontXray) "127.0.0.1" else listenHost
            val rawXray = effectiveProfile.rawXrayConfig
            var assetPath = ""
            val json = if (!rawXray.isNullOrBlank()) {
                // A full user Xray config (JSON subscription) runs VERBATIM and its OWN routing wins —
                // prepareRaw overlays the app's routing profile only into configs that ship none. To
                // actually honor geosite:/geoip: selectors xray needs the geo .dat, so download it when
                // the config references them (regardless of whether an app routing profile is active —
                // that was the desktop bug: with no profile the asset path stayed empty and the config
                // either failed to load or silently lost its geo rules). Stripping the selectors is the
                // last-resort fallback for when the db genuinely can't be fetched, so it still loads.
                val rawNeedsGeo = rawXray.contains("geosite:") || rawXray.contains("geoip:")
                assetPath = when {
                    rawNeedsGeo -> ensureRawConfigGeoAssetPath(routingProfile)
                    routingProfile != null -> ensureGeoAssetPath(routingProfile)
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
                    listenPort = xrayPort,
                    listenHost = xrayHost,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                    fakeDnsEnabled = traffic.fakeDnsEnabled,
                    stripGeoSelectors = stripGeo,
                    // ipv4_only/prefer_ipv4 -> force the verbatim config's freedom outbounds + DNS to
                    // IPv4, or a DIRECT-routed site still dials real IPv6 past the tunnel.
                    forceIpv4 = traffic.domainStrategy.let { it == "ipv4_only" || it == "prefer_ipv4" },
                    // Cascade over a verbatim config: desktop LOGGED it but never passed it, so the 2nd
                    // proxy was silently dropped for every raw config.
                    secondProfile = secondProfile,
                )
            } else {
                assetPath = ensureGeoAssetPath(routingProfile)
                XrayConfig.build(
                    profile = effectiveProfile,
                    listenPort = xrayPort,
                    listenHost = xrayHost,
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
                    // The "Обход LAN" toggle. Android passes it; desktop did not, so on the Xray core
                    // LAN bypass silently ran on the default no matter what the user set.
                    bypassLan = routing.bypassLan,
                )
            }
            log("Starting Xray engine=${config.engine}, server=${effectiveProfile.server}:${effectiveProfile.serverPort}")
            if (assetPath.isNotEmpty()) YpTunCore.xraySetAssetPath(assetPath)
            YpTunCore.xrayStart(json)
            if (frontXray) {
                startSingBoxFront(
                    xrayPort = xrayPort,
                    listenHost = listenHost,
                    listenPort = listenPort,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routing = routing,
                    traffic = traffic,
                    // xray already blackholes UDP/443 for TCP-only transports; leave the policy there.
                    blockQuic = false,
                )
            }
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
                matchAppsByProcess = true,
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
                tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                // Desktop always fronts sing-box with a TUN (in-core or external tun2socks); the OS
                // funnels all DNS into it, so sing-box must hijack & resolve DNS itself (see param doc).
                hijackDns = true,
                // Plain UDP remote DNS (like the working Android path): with packet_encoding=xudp the
                // vless tunnel carries UDP, so DNS is fast stateless packets instead of one long-lived
                // DoH/TCP connection that periodically froze for 5-6s and blocked all resolution.
                remoteDnsOverHttps = false,
                // Let the exit server resolve destination domains instead of forcing a client-side
                // re-resolve per connection (which made every web connection wait on the single, flaky
                // DoH-over-proxy channel → 30s stalls). No routing profile here = no geoip rules need a
                // local IP, so domains pass straight through the vless tunnel. The server (IPv4) picks
                // the address, so there's no user IPv6 leak. Keep family enforcement only for the local
                // UDP tunnels that genuinely carry every family themselves.
                forceFamilyResolve = false,
                // SOCKS+HTTP inbound so desktop proxy mode can point the Windows system HTTP-proxy at it.
                mixedInbound = true,
                logFilePath = singBoxLogPath(),
                cacheFilePath = singBoxCachePath(),
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
    // sing-box front for the Xray core (desktop TUN mode)

    /**
     * Internal loopback port an Xray core listens on while a sing-box front owns the TUN.
     *
     * Clear of every other offset in use: +1 olcRTC chain / Xray's cascade loopback, +2 AmneziaWG,
     * +5 Trust Tunnel. XrayConfig derives its own cascade loopback from the port we hand it, so this
     * reserves +6 AND +7.
     */
    private fun singBoxFrontPort(socksPort: Int) = socksPort + 6

    /**
     * Puts a thin sing-box in front of an already-started Xray core: sing-box owns the system TUN
     * (wintun, auto_route) and relays everything into Xray's loopback SOCKS.
     *
     * WHY: xray-core has no TUN of its own, so every location that forces it — xhttp/splithttp, a
     * routing profile, an xhttp cascade — used to fall back to the external xjasonlyu/tun2socks
     * bridge. That bridge carries the OS's DNS as a SOCKS5 UDP ASSOCIATE, which stalls; it is the
     * very reason sing-box was moved to an in-core TUN in the first place, and nothing was ever done
     * for the Xray path. The tunnel came up and the servers pinged, but nothing resolved and no
     * connection completed. This is the same shape AmneziaWG and Trust Tunnel already use (a local
     * SOCKS fronted by sing-box), so it needs no new machinery.
     *
     * The front is deliberately DUMB: routing profile, QUIC policy, DNS hosts, FakeDNS and the RU
     * blocklist all live in the Xray config and must not be applied twice. It does not resolve
     * sniffed domains either, so Xray receives the DOMAIN its `domain:`/`geosite:` rules (and the
     * REALITY SNI) depend on.
     */
    private suspend fun startSingBoxFront(
        xrayPort: Int,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        routing: org.olcbox.app.data.model.RoutingRules,
        traffic: org.olcbox.app.data.model.TrafficSettings,
        blockQuic: Boolean,
    ) {
        if (!awaitSocksPortOpen(xrayPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("Xray SOCKS port $xrayPort did not open")
        }
        val credentials = if (socksUsername.isNotBlank()) {
            ",\"username\":\"$socksUsername\",\"password\":\"$socksPassword\""
        } else {
            ""
        }
        val frontProfile = ProxyProfile(
            tag = "xray",
            type = "socks",
            server = "127.0.0.1",
            serverPort = xrayPort,
            rawOutbound = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
                "\"server_port\":$xrayPort,\"version\":\"5\"$credentials}",
        )
        val json = SingBoxConfig.build(
            profile = frontProfile,
            listenPort = listenPort,
            listenHost = listenHost,
            socksUsername = socksUsername,
            socksPassword = socksPassword,
            autoDetectInterface = true,
            routing = routing,
            matchAppsByProcess = true,
            // Everything policy-shaped is Xray's job here — see the doc above.
            traffic = traffic.copy(fakeDnsEnabled = false, blockRuDomains = false),
            routingProfile = null,
            blockQuic = blockQuic,
            forceFamilyResolve = false,
            // DNS goes to Xray as a plain SOCKS5 CONNECT instead of a UDP ASSOCIATE — the associate
            // path is exactly what died on the tun2socks bridge.
            preferTcpRemoteDns = true,
            tunMode = true,
            splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
            splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
            tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
            mixedInbound = true,
            logFilePath = singBoxLogPath(),
            cacheFilePath = singBoxCachePath(),
        )
        log("Fronting Xray with sing-box: in-core TUN on $listenHost:$listenPort → 127.0.0.1:$xrayPort")
        YpTunCore.sbStart(json)
        tunHandledInCore = true
        singBoxFrontActive = true
    }

    // ---------------------------------------------------------------------------------------
    // dnstt (mirrors OlcboxVpnService.startDnsttCore)

    /**
     * dnstt (DNS tunnel): the client raises a transparent TCP forwarder on the local port; the
     * dnstt-server relays each connection to its own upstream SOCKS5, so that port behaves as that
     * SOCKS5 and the TUN bridge can consume it directly. The forwarder cannot terminate a SOCKS auth
     * handshake, so without a proxy core in front the local endpoint must run no-auth — see
     * [localSocksNoAuth]. With a proxy link, dnstt moves to the internal chain port and an Xray/
     * sing-box core fronts it on [listenPort], keeping the credentials.
     */
    private suspend fun startDnstt(
        config: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
    ) {
        val dnstt = config.dnstt
        check(dnstt != null && dnstt.isComplete()) { "DNSTT not configured" }

        val proxy = dnstt.proxyLink.takeIf { it.isNotBlank() }?.let { link ->
            (ShareLinkParser.parse(link)
                ?: org.olcbox.app.data.share.YptunInboundCodec.parse(link)?.let { it.proxy ?: it.proxy2 })
                ?.takeIf { it.isComplete() }
        }
        if (dnstt.proxyLink.isNotBlank() && proxy == null) {
            log("DNSTT: proxy link present but could not be parsed — exiting via dnstt SOCKS directly (no proxy)")
        }
        val useProxy = proxy != null
        dnsttProxyActive = useProxy
        localSocksNoAuth = !useProxy
        val dnsttPort = if (useProxy) chainOlcrtcPort(listenPort) else listenPort

        require(!isLocalSocksPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }
        if (useProxy) {
            require(!isLocalSocksPortOpen(dnsttPort)) { "DNSTT internal port $dnsttPort is still in use" }
        }

        val dnsttAddr = "$listenHost:$dnsttPort"
        log("Starting DNSTT on $dnsttAddr (domain=${dnstt.domain}, resolver=${dnstt.resolver})")
        runCatching { YpTunCore.dnsttStop() }
        YpTunCore.dnsttStart(dnstt.resolver, dnstt.domain, dnstt.pubKey, dnsttAddr)
        if (!awaitSocksPortOpen(dnsttPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("DNSTT SOCKS port $dnsttPort did not open")
        }
        log("DNSTT ready on $dnsttAddr")
        if (!useProxy) return

        val traffic = JvmVpnSettings.loadTraffic()
        val routing = loadRoutingExpandingAsn()
        val profilesState = JvmVpnSettings.loadRoutingProfiles()
        val routingProfile = resolveProfileExpandingAsn(profilesState, config.routingProfileId)
        val globalCore = JvmVpnSettings.loadAppBehavior().globalProxyCore
        val profileWantsXray = routingProfile != null &&
            (routingProfile.needsGeoFiles() || routingProfile.dnsHosts.isNotEmpty()) &&
            proxy.type in XRAY_SUPPORTED_TYPES
        val useXray = dnstt.resolvedProxyCore(proxy, globalCore) == ProxyCore.Xray || profileWantsXray
        log("DNSTT chaining proxy ${proxy.displayName()} over the tunnel (${if (useXray) "Xray" else "sing-box"})")

        if (useXray) {
            // xray owns no TUN — a sing-box front does, so the external tun2socks bridge (and its
            // stalling SOCKS-UDP DNS) is skipped here too. See [startSingBoxFront].
            val frontXray = requestedTun
            val xrayPort = if (frontXray) singBoxFrontPort(listenPort) else listenPort
            val xrayHost = if (frontXray) "127.0.0.1" else listenHost
            val assetPath = ensureGeoAssetPath(routingProfile)
            val xrayJson = XrayConfig.build(
                profile = proxy,
                listenPort = xrayPort,
                listenHost = xrayHost,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = dnsttPort,
                traffic = traffic,
                routingProfile = xrayRoutingProfile(routingProfile, assetPath),
                blockQuic = true,
                // Resolving every destination over the DNS tunnel stalls all traffic; the family stays
                // pinned by the bridge's v6 drop.
                forceFamilyResolve = false,
                // Chain at the SOCKET level, or a vless reality/xtls-vision exit loses its transport
                // and the server resets it.
                chainViaDialerProxy = true,
                // Xray's default 4s handshake budget is far too short for SOCKS5→VPS→proxy→TLS over a
                // DNS tunnel, and it killed every connection mid-handshake.
                handshakeTimeoutSec = 30,
                // A `direct` rule must still exit via the dnstt-server, never the real network.
                directViaBase = true,
            )
            activeProxyCore = ProxyCore.Xray
            if (assetPath.isNotEmpty()) YpTunCore.xraySetAssetPath(assetPath)
            YpTunCore.xrayStart(xrayJson)
            if (frontXray) {
                startSingBoxFront(
                    xrayPort = xrayPort,
                    listenHost = listenHost,
                    listenPort = listenPort,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routing = routing,
                    traffic = traffic,
                    blockQuic = false,
                )
            }
        } else {
            val json = SingBoxConfig.build(
                profile = proxy,
                listenPort = listenPort,
                listenHost = listenHost,
                socksUsername = socksUsername,
                socksPassword = socksPassword,
                olcrtcChainPort = dnsttPort,
                autoDetectInterface = true,
                routing = routing,
                matchAppsByProcess = true,
                traffic = traffic,
                routingProfile = routingProfile,
                singboxGeositeBase = profilesState.singboxGeositeBase,
                singboxGeoipBase = profilesState.singboxGeoipBase,
                blockQuic = true,
                // Both resolve opt-outs: a per-connection DNS round-trip through the proxy through the
                // DNS tunnel is fatal for throughput. See the Android path for the full reasoning.
                forceFamilyResolve = false,
                allowLocalResolve = false,
                directViaBase = true,
                mixedInbound = true,
                hijackDns = true,
                tunMode = requestedTun,
                splitTunnelMode = tunRequest?.splitMode ?: SingBoxConfig.SPLIT_TUNNEL_ALL,
                splitTunnelProcesses = tunRequest?.processes ?: emptyList(),
                tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                logFilePath = singBoxLogPath(),
                cacheFilePath = singBoxCachePath(),
            )
            activeProxyCore = ProxyCore.SingBox
            YpTunCore.sbStart(json)
            tunHandledInCore = requestedTun
        }

        if (!awaitSocksPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("DNSTT proxy SOCKS port $listenPort did not open")
        }
        log("DNSTT proxy ready on $listenHost:$listenPort")
    }

    // ---------------------------------------------------------------------------------------
    // VK-TURN (mirrors OlcboxVpnService.startVkTurnCore)

    private suspend fun startVkTurn(
        config: LocationConfig,
        listenHost: String,
        listenPort: Int,
        socksUsername: String,
        socksPassword: String,
        deviceId: String,
    ) {
        val vk = config.vkturn
        var profile = config.proxy
        val usesWdtt = vk?.usesWdtt() == true
        val outboundType = vk?.outbound?.ifBlank { VkTurnConfig.OUTBOUND_WIREGUARD }
            ?: VkTurnConfig.OUTBOUND_WIREGUARD
        val outboundConfigured = when {
            // WDTT fetches its WireGuard config FROM the server (GETCONF), so the user stores no WG
            // keys and there is nothing to validate up front — see LocationViewModel's matching gate.
            usesWdtt -> true
            outboundType == VkTurnConfig.OUTBOUND_AMNEZIAWG -> !profile?.awgConfig.isNullOrBlank()
            outboundType == VkTurnConfig.OUTBOUND_PROXY -> profile != null &&
                profile.server.isNotBlank() && profile.serverPort in 1..65535
            else -> !profile?.rawOutbound.isNullOrBlank()
        }
        check(vk != null && vk.isComplete() && outboundConfigured) { "VK-TURN not configured" }

        require(!isLocalSocksPortOpen(listenPort)) { "SOCKS port $listenPort is still in use" }

        val listenAddr = "127.0.0.1:${vk.listenPort}"
        if (usesWdtt) {
            // WDTT core (wg-turn-client): dials the wdtt-server purely by IP[:port] over VK call links
            // and hands back the WireGuard config we build the outbound from.
            val peerAddr = vk.wdttPeerAddr()
            log(
                "Starting VK-TURN WDTT core on $listenAddr (peer=$peerAddr, " +
                    "workers=${vk.wdttWorkers.takeIf { it > 0 }?.toString() ?: "auto"})"
            )
            YpTunCore.wdttStart(
                peer = peerAddr,
                vkHashes = vk.vkLink,
                password = vk.wdttPassword,
                listen = listenAddr,
                numWorkers = vk.wdttWorkers,
                deviceId = deviceId,
                fingerprint = vk.wdttFingerprint.ifBlank { "chrome" },
            )
            // The config only arrives once the first worker has a VK TURN session up, so waiting on it
            // doubles as the relay-ready gate (same as the Android OnConfig path).
            val wgConf = YpTunCore.wdttWaitConfig(VKTURN_RELAY_READY_TIMEOUT_MS)
            when {
                wgConf != null -> {
                    profile = buildWdttWgProfile(wgConf, vk.listenPort)
                    log("VK-TURN WDTT relay up; WireGuard config from server applied")
                }
                !profile?.rawOutbound.isNullOrBlank() ->
                    log("VK-TURN WDTT: no GETCONF — falling back to the stored WireGuard config")
                else -> throw IllegalStateException(
                    "WDTT: no WireGuard config from server (GETCONF) and none stored"
                )
            }
        } else {
            // bond=1 в старых ссылках ядро 3.2.0 игнорирует — вырезать его больше не нужно.
            val freeturnUri = vk.uri
            log("Starting VK-TURN freeturn listener on $listenAddr")
            YpTunCore.ftStart(freeturnUri, listenAddr, vk.vkLink, vk.streams)

            if (awaitVkTurnRelayReady(VKTURN_RELAY_READY_TIMEOUT_MS)) {
                log("VK-TURN relay up (${YpTunCore.ftConnectedStreams()} stream(s)); starting WireGuard")
            } else {
                log("VK-TURN relay not ready yet; starting outbound anyway (will retry)")
            }
        }

        activeProxyCore = ProxyCore.SingBox
        val exitProfile = requireNotNull(profile)
        val routing = loadRoutingExpandingAsn()
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
            // Same as the Standard/Chain path: xray owns no TUN, so a sing-box front does (and the
            // external tun2socks bridge — with its stalling SOCKS-UDP DNS — is skipped entirely).
            val frontXray = requestedTun
            val xrayPort = if (frontXray) singBoxFrontPort(listenPort) else listenPort
            val xrayHost = if (frontXray) "127.0.0.1" else listenHost
            val xrayJson = if (outboundType == VkTurnConfig.OUTBOUND_PROXY) {
                log("VK-TURN exit: proxy ${exitProfile.displayName()} over VK (tcp, Xray)")
                XrayConfig.build(
                    profile = exitProfile,
                    listenPort = xrayPort,
                    listenHost = xrayHost,
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
                    listenPort = xrayPort,
                    listenHost = xrayHost,
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
            if (frontXray) {
                startSingBoxFront(
                    xrayPort = xrayPort,
                    listenHost = listenHost,
                    listenPort = listenPort,
                    socksUsername = socksUsername,
                    socksPassword = socksPassword,
                    routing = routing,
                    traffic = traffic,
                    // VK-TURN carries UDP natively — never blackhole QUIC on this engine.
                    blockQuic = false,
                )
            }
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
                        matchAppsByProcess = true,
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
                        tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                        // SOCKS+HTTP so desktop proxy mode can point the Windows system proxy here.
                        mixedInbound = true,
                        logFilePath = singBoxLogPath(),
                        cacheFilePath = singBoxCachePath(),
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
                        matchAppsByProcess = true,
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
                        tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                        // SOCKS+HTTP so desktop proxy mode can point the Windows system proxy here.
                        mixedInbound = true,
                        logFilePath = singBoxLogPath(),
                        cacheFilePath = singBoxCachePath(),
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
                            matchAppsByProcess = true,
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
                            tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                            // SOCKS+HTTP so desktop proxy mode can point the Windows system proxy here.
                            mixedInbound = true,
                            logFilePath = singBoxLogPath(),
                            cacheFilePath = singBoxCachePath(),
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
                            matchAppsByProcess = true,
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
                            tunExcludeAddresses = tunRequest?.excludeAddresses ?: emptyList(),
                            // SOCKS+HTTP so desktop proxy mode can point the Windows system proxy here.
                            mixedInbound = true,
                            logFilePath = singBoxLogPath(),
                            cacheFilePath = singBoxCachePath(),
                        )
                    }
                }
            }
            log("Starting sing-box (VK-TURN, $outboundType) via $listenAddr" +
                if (requestedTun) " (in-core TUN)" else "")
            YpTunCore.sbStart(json)
            // This was MISSING: the configs above are built with `tunMode = requestedTun`, so sing-box
            // raises the wintun adapter with auto_route — but the manager was never told, so it ALSO
            // started tun2socks on its own adapter with 0.0.0.0/1 + 128.0.0.0/1 at metric 1. Two TUNs
            // fought over the default route and VK-TURN carried nothing.
            tunHandledInCore = requestedTun
        }

        if (!awaitSocksPortOpen(listenPort, MOBILE_READY_TIMEOUT_MS)) {
            throw IllegalStateException("VK-TURN SOCKS port $listenPort did not open")
        }
        log("VK-TURN ready on $listenHost:$listenPort")
    }

    /**
     * WireGuard outbound over the local WDTT listener, built from the config the wdtt-server returns.
     * Mirrors OlcboxVpnService.buildWdttWgProfile, including the MTU clamp: the server advertises 1280,
     * but through VK TURN + DTLS + RTP-obf the real path MTU is well under that and 1280 black-holes
     * large packets (uploads / TLS handshakes stall).
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
            val k = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim()
            when (k) {
                "privatekey" -> priv = v
                "publickey" -> pub = v
                "address" -> if (addr.isEmpty()) addr = v.substringBefore(',').trim()
                "mtu" -> mtu = v.toIntOrNull() ?: 0
            }
        }
        val localAddr = if (addr.isNotEmpty()) "\"$addr\"" else ""
        val effMtu = (if (mtu > 0) mtu else 1200).coerceAtMost(1200)
        val json = buildString {
            append("{")
            append("\"type\":\"wireguard\",")
            append("\"server\":\"127.0.0.1\",")
            append("\"server_port\":$listenPort,")
            append("\"local_address\":[$localAddr],")
            append("\"private_key\":\"$priv\",")
            append("\"peer_public_key\":\"$pub\",")
            append("\"mtu\":$effMtu")
            append("}")
        }
        return ProxyProfile(
            tag = "WDTT",
            type = "wireguard",
            server = "127.0.0.1",
            serverPort = listenPort,
            rawOutbound = json,
        )
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

    /**
     * Starts the AdGuard Trust Tunnel client from the profile's `tt://` deep link and returns a SOCKS
     * proxy pointing at its local listener, so the proxy core routes through it exactly like it does
     * through AmneziaWG. Mirrors prepareTrustTunnelProxy on Android (which uses the in-process AAR).
     */
    private suspend fun prepareTrustTunnelProxy(profile: ProxyProfile, socksPort: Int): ProxyProfile {
        if (profile.type != ProxyProfile.TYPE_TRUSTTUNNEL) return profile
        val port = trustTunnelLocalPort(socksPort)
        trustTunnel.start(profile.ttConfig, port)
        if (!awaitSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS)) {
            trustTunnel.stop()
            throw IllegalStateException("Trust Tunnel SOCKS port $port did not open")
        }
        val raw = "{\"type\":\"socks\",\"server\":\"127.0.0.1\"," +
            "\"server_port\":$port,\"version\":\"5\"}"
        return ProxyProfile(
            tag = profile.tag.ifBlank { "Trust Tunnel" },
            type = "socks",
            server = "127.0.0.1",
            serverPort = port,
            rawOutbound = raw,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Geo assets / routing-profile degradation (mirrors Android helpers)

    /**
     * Geo .dat for a VERBATIM user config's own geosite:/geoip: selectors. Unlike [ensureGeoAssetPath]
     * this is NOT gated on an app routing profile — the config asked for geo matching itself, so the db
     * is fetched from the profile's sources or the global defaults. Empty string = download failed
     * (blocked network, nothing cached), and the caller then strips the selectors so the config loads.
     */
    private fun ensureRawConfigGeoAssetPath(profile: RoutingProfile?): String {
        val state = JvmVpnSettings.loadRoutingProfiles()
        val geoip = profile?.geoipUrl?.takeIf { it.isNotBlank() } ?: state.geoipUrl
        val geosite = profile?.geositeUrl?.takeIf { it.isNotBlank() } ?: state.geositeUrl
        val ok = runCatching { JvmGeoAssets.ensureAssets(geoip, geosite) }.getOrDefault(false)
        return if (ok) JvmGeoAssets.assetDir().absolutePath else ""
    }

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

    /**
     * The routing profile for [locationProfileId] with its `asn:N` selectors replaced by the
     * operator's CIDRs — the desktop twin of OlcboxVpnService.resolveProfileExpandingAsn.
     *
     * Both cores DROP selectors they can't parse, so without this every `asn:` rule silently did
     * nothing on desktop and the profile looked half-applied. Unresolvable ASNs are still dropped,
     * but the rest of the profile is unaffected.
     */
    private suspend fun resolveProfileExpandingAsn(
        state: org.olcbox.app.data.model.RoutingProfilesState,
        locationProfileId: String?,
    ): RoutingProfile? {
        val profile = state.resolve(locationProfileId) ?: return null
        val asns = profile.referencedAsns()
        if (asns.isEmpty()) return profile
        val cidrs = runCatching { JvmAsnResolver.ensure(asns) }.getOrDefault(emptyMap())
        log("Routing: expanded ${cidrs.size}/${asns.size} ASN selector(s) to CIDRs")
        return profile.expandAsn(cidrs)
    }

    /**
     * [JvmVpnSettings.loadRouting] with the manual rules' `asn:` selectors and app regexes expanded,
     * as on Android. The app list is EXE names here (matched with sing-box `process_name`), so the
     * regex is resolved against running processes instead of installed packages.
     */
    private suspend fun loadRoutingExpandingAsn(): org.olcbox.app.data.model.RoutingRules {
        val routing = JvmVpnSettings.loadRouting()
        val asns = org.olcbox.app.data.model.Asn.collect(routing.rules.flatMap { it.ip })
        var rules = routing.rules
        if (asns.isNotEmpty()) {
            val cidrs = runCatching { JvmAsnResolver.ensure(asns) }.getOrDefault(emptyMap())
            rules = org.olcbox.app.data.model.SingBoxRule.expandAsn(rules, cidrs)
        }
        if (rules.any { it.packageRegex.isNotEmpty() }) {
            rules = org.olcbox.app.data.model.SingBoxRule.expandPackageRegex(rules, runningProcessNames())
        }
        return if (rules === routing.rules) routing else routing.copy(rules = rules)
    }

    /** Distinct executable names of every process we can see — the desktop "installed apps" list. */
    private fun runningProcessNames(): List<String> = runCatching {
        ProcessHandle.allProcesses()
            .map { it.info().command().orElse("") }
            .filter { it.isNotBlank() }
            .map { it.substringAfterLast('\\').substringAfterLast('/') }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

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
            // A core is usually listening within a few dozen ms; the old 200 ms grid added a quarter
            // of a second of dead time to every connect for nothing.
            delay(PORT_POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val MOBILE_READY_TIMEOUT_MS = 25_000
        const val VKTURN_RELAY_READY_TIMEOUT_MS = 20_000

        /** How often [awaitSocksPortOpen] probes the core's local port. */
        const val PORT_POLL_INTERVAL_MS = 30L

        /** Size past which singbox.log is dropped at start instead of appended to (see singBoxLogPath). */
        const val MAX_SINGBOX_LOG_BYTES = 32L * 1024 * 1024

        // Proxy types xray-core can serve from typed fields (same as Android).
        val XRAY_SUPPORTED_TYPES = setOf(
            ProxyProfile.TYPE_VLESS,
            ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN,
            ProxyProfile.TYPE_SHADOWSOCKS,
        )
    }
}
