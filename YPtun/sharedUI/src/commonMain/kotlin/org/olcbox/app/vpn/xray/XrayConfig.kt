package org.olcbox.app.vpn.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings

/**
 * Builds an Xray-core JSON configuration from a [ProxyProfile]. Mirrors [org.olcbox.app.vpn.singbox.SingBoxConfig]
 * but in Xray's schema, and additionally supports the xhttp transport (which sing-box lacks).
 *
 * Produces a SOCKS inbound on [listenPort] (consumed by the TUN bridge) and a proxy outbound.
 * When [olcrtcChainPort] is set, the proxy outbound is chained through olcRTC's local SOCKS.
 */
object XrayConfig {

    private const val PROXY_TAG = "proxy"
    // Main (first-hop) proxy when a second/cascade proxy is present: the second exits as PROXY_TAG and
    // dials THROUGH this. With no second proxy the main itself is PROXY_TAG (the exit).
    private const val PROXY_BASE_TAG = "proxy-base"
    // Cascade exit added on top of a VERBATIM config: dials through the config's own main proxy tag
    // (which may itself be "proxy"), so it must be a distinct tag.
    private const val CASCADE_EXIT_TAG = "cascade-exit"
    // Loopback-SOCKS relay used to chain a 2nd proxy over an xhttp/splithttp MAIN (which can't be a
    // sub-dialer): the exit dials its server through CASCADE_LOOP_OUT_TAG (a local socks outbound),
    // whose CASCADE_LOOP_IN_TAG inbound is routed to the real xhttp main (now a normal outbound).
    private const val CASCADE_LOOP_IN_TAG = "cascade-loop-in"
    private const val CASCADE_LOOP_OUT_TAG = "cascade-loop-out"
    private const val OLCRTC_TAG = "olcrtc-out"
    private const val WG_BASE_TAG = "wireguard-base"

    // FakeDNS synthetic-IP pool (matches the v2rayNG/Happ default).
    private const val FAKEDNS_SERVER = "fakedns"
    private const val FAKEDNS_POOL = "198.18.0.0/15"
    private const val FAKEDNS_POOL_SIZE = 65535

    private val json = Json { prettyPrint = true }

    /**
     * `domainStrategy` for the `direct` (freedom) outbound. CRITICAL on Android: with the default
     * `AsIs`, freedom hands the bare hostname to Go's system resolver, which can't resolve on Android
     * (no usable /etc/resolv.conf, and the process is unbound from any network once connected) — so
     * `domain:ru → direct` "did nothing" on xray (RU sites never resolved) while sing-box worked.
     * Forcing `UseIP*` makes xray resolve direct destinations through its own `dns` block (queried over
     * protected sockets) before dialing, mirroring sing-box's direct outbound. Family-matched so it
     * stays consistent with the ipv4_only/ipv6_only enforcement.
     */
    private fun directDomainStrategy(traffic: TrafficSettings): String = when (traffic.domainStrategy) {
        // Force* (not Use*): also REFUSES a raw IP-literal of the wrong family. UseIPv4 only resolved
        // DOMAINS to IPv4 — a direct site reached by an IPv6 LITERAL (e.g. an app's own-DoH AAAA, or a
        // geoip:ru match on a v6 address) was still dialed over real IPv6, the leak the user reported on
        // `domain:ru/geosite:ru/geoip:ru → direct` under prefer_ipv4. ForceIPv4 drops that v6 entirely.
        "ipv6_only" -> "ForceIPv6"
        // ipv4_only AND the hybrid prefer_* strategies all force the DIRECT (freedom) outbound to IPv4.
        // Hybrid would otherwise resolve direct/bypass destinations dual-stack (UseIP) and dial the
        // user's REAL IPv6 for domain:ru → direct, leaking it. Pinning direct to IPv4 keeps bypass
        // traffic off real IPv6; proxied traffic is unaffected (it exits via the proxy's own IP).
        else -> "ForceIPv4"
    }

    /**
     * Major DNS providers that serve DNS-over-HTTPS on their well-known IP with a cert that carries the
     * IP as a SAN — so `https://<ip>/dns-query` needs NO bootstrap resolution and can't be MITM'd.
     */
    private val DOH_IP_PROVIDERS = setOf(
        "8.8.8.8", "8.8.4.4",                 // Google
        "1.1.1.1", "1.0.0.1",                 // Cloudflare
        "9.9.9.9", "149.112.112.112",         // Quad9
        "223.5.5.5", "223.6.6.6",             // AliDNS
    )

    /**
     * Rewrites an UPSTREAM (proxied) DNS resolver so it survives an exit that blocks plain DNS. The
     * remote resolvers ride the proxy/cascade, and a splithttp/cascade exit that drops UDP:53 *and*
     * TCP:53 to 8.8.8.8/1.1.1.1 made every lookup wait out a 4s serial timeout → "record not found" →
     * ERR_CONNECTION_ABORTED. So:
     *  - a known major provider IP → DNS-over-HTTPS on its own IP (`https://<ip>/dns-query`): rides
     *    443 like normal traffic, no bootstrap (IP-SAN cert), can't be port-53-blocked.
     *  - any other bare IP → DNS-over-TCP (`tcp://<ip>`): reliable stream, still better than UDP.
     *  - a server that already carries a scheme (udp/tcp/https/quic), a hostname, or `fakedns` is left
     *    EXACTLY as the user set it (a hostname DoH would need its own bootstrap; don't touch it).
     * Direct DNS is deliberately NOT routed through this — it's queried for direct/RU domains where
     * plain UDP is fine.
     */
    private fun proxiedDns(server: String): String {
        val s = server.trim()
        if (s.isEmpty() || s.contains("://") || s == FAKEDNS_SERVER) return s
        if (s in DOH_IP_PROVIDERS) return "https://$s/dns-query"
        val isIpv4 = s.all { it.isDigit() || it == '.' } && s.count { it == '.' } == 3
        val isIpv6 = s.contains(':') && s.all { it.isDigit() || (it in 'a'..'f') || (it in 'A'..'F') || it == ':' }
        return if (isIpv4 || isIpv6) "tcp://$s" else s
    }

    // --- FakeDNS building blocks (shared by build() and prepareRaw()) ---

    /** Routing rules that hijack DNS (UDP/53, TCP/853 DoT) to the `dns-out` outbound. */
    private fun dnsHijackRules(): List<JsonObject> = listOf(
        buildJsonObject { put("type", "field"); put("network", "udp"); put("port", 53); put("outboundTag", "dns-out") },
        buildJsonObject { put("type", "field"); put("network", "tcp"); put("port", 853); put("outboundTag", "dns-out") },
    )

    /** The `dns` protocol outbound that answers hijacked queries (tag `dns-out`). */
    private fun dnsOutOutbound(): JsonObject = buildJsonObject {
        put("tag", "dns-out")
        put("protocol", "dns")
        putJsonObject("settings") { put("nonIPQuery", "skip") }
    }

    /** A single synthetic-IP pool entry for the top-level `fakedns` array. */
    private fun fakeDnsPool(): JsonObject = buildJsonObject {
        put("ipPool", FAKEDNS_POOL)
        put("poolSize", FAKEDNS_POOL_SIZE)
    }

    /** Returns [sniffing] with `fakedns` ensured in destOverride (building a default block if null). */
    private fun sniffingWithFakeDns(sniffing: JsonObject?): JsonObject = buildJsonObject {
        val existing = (sniffing?.get("destOverride") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOf("http", "tls", "quic")
        sniffing?.forEach { (k, v) -> if (k != "destOverride" && k != "enabled") put(k, v) }
        put("enabled", true)
        putJsonArray("destOverride") {
            existing.forEach { add(it) }
            if ("fakedns" !in existing) add("fakedns")
        }
    }

    fun build(
        profile: ProxyProfile,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        olcrtcChainPort: Int? = null,
        olcrtcChainUser: String = "",
        olcrtcChainPass: String = "",
        logLevel: String = "debug",
        traffic: TrafficSettings = TrafficSettings(),
        // VK-TURN chain: when set, [profile] dials its server THROUGH this WireGuard outbound (the
        // WG-over-VK base). Mirrors SingBoxConfig.wireguardBase. The ProxyProfile carries the
        // sing-box-format WG outbound in [ProxyProfile.rawOutbound]; we convert it to Xray schema.
        wireguardBase: ProxyProfile? = null,
        // When set, the `routing` block is generated from this profile (direct/block/proxy buckets
        // with geosite:/geoip:/domain: selectors) and its dns.hosts are merged. The referenced
        // geoip.dat/geosite.dat must be present in XRAY_LOCATION_ASSET.
        routingProfile: org.olcbox.app.data.model.RoutingProfile? = null,
        // Block QUIC (UDP/443) so clients fall back to TCP. MUST be false for UDP-capable tunnels
        // (VK-TURN / WireGuard) which carry QUIC natively — blocking it there breaks those engines.
        blockQuic: Boolean = true,
        // Family enforcement (the opposite-family blackhole + IPIfNonMatch routing-resolve that ipv4_only/
        // prefer_ipv4/ipv6_only turn on). IPIfNonMatch makes Xray resolve EVERY domain for routing, and
        // those DNS lookups egress via the proxy — i.e. through the tunnel. On a very slow tunnel (dnstt:
        // DNS TXT) that adds a tunnel round-trip to every connection and stalls all traffic. Pass false
        // there: the bridge already drops client IPv6 for ipv4 modes, so the family stays pinned without
        // forcing per-connection resolution over the tunnel.
        forceFamilyResolve: Boolean = true,
        // Chain the main proxy through its detour with sockopt.dialerProxy (socket-level) instead of
        // proxySettings (proxy-level). proxySettings re-wraps the outbound and DROPS its own transport —
        // a vless+reality / xtls-vision exit then sends a malformed handshake and the server resets the
        // connection ("vless → connection reset" over dnstt). dialerProxy keeps the full vless/TLS/flow
        // intact and only routes the underlying socket through the detour. Set for the dnstt chain.
        chainViaDialerProxy: Boolean = false,
        // For VK-TURN / dnstt: the base tunnel (WireGuard / dnstt SOCKS) is the MANDATORY transport, so a
        // routing rule's `direct` bucket must NOT leak to the real network — it should still exit through
        // the base tunnel (at the VK / dnstt-server). When true, the `direct` outbound dials through the
        // base detour instead of dialing the destination on the real interface. So routing only chooses
        // base-tunnel-exit (direct) vs second-proxy-exit (proxy); nothing ever bypasses the tunnel.
        directViaBase: Boolean = false,
        // Send LAN/private ranges straight out (not through the proxy) so local-network devices stay
        // reachable — the global "Обход LAN" toggle. Only applied when the `direct` outbound is the
        // REAL network ([directViaBase] == false); for VK-TURN/dnstt base-detour exits LAN must keep
        // tunnelling, so this is ignored there (the toggle still governs the sing-box path the same way).
        bypassLan: Boolean = true,
        // Optional SECOND proxy chained ON TOP of [profile] (the main). When present, traffic exits via
        // this proxy (tag [PROXY_TAG], emitted first so it's Xray's default outbound) which dials THROUGH
        // the main (tag [PROXY_BASE_TAG]); the main dials through olcRTC/WG. So: client → [olcRTC/WG] →
        // main → second → internet. Null = the main proxy is the single exit (PROXY_TAG), as before.
        secondProfile: ProxyProfile? = null,
        // Outbound handshake budget (seconds). Xray's default is only 4s — far too short when the proxy
        // is chained over an ultra-slow tunnel (dnstt: SOCKS5 greeting+CONNECT to the VPS, then the VPS's
        // dial to the proxy server, then the vless/TLS handshake, all round-tripping over DNS TXT). Xray
        // then aborts mid-handshake → "connection reset" for EVERY transport. null = leave Xray's default.
        handshakeTimeoutSec: Int? = null,
    ): String {
        // Cascade over an xhttp/splithttp MAIN: Xray's splithttp transport can be NEITHER a
        // sockopt.dialerProxy sub-dialer NOR a proxySettings target, so the 2nd-proxy exit can't reach
        // its server "through" the main (→ "failed to find an available destination > EOF" = the user's
        // "xhttp + 2nd tcp proxy = нет соединения"). Route the exit through a local SOCKS loopback
        // instead; the main ([PROXY_BASE_TAG]) then runs as an ORDINARY outbound that just proxies the
        // TCP to the 2nd server. Same fix as prepareRaw(). Non-xhttp mains keep the direct dialerProxy.
        val cascadeMainIsXhttp = profile.network == ProxyProfile.NETWORK_XHTTP
        val cascadeLoopActive = secondProfile?.isComplete() == true && cascadeMainIsXhttp
        // Internal loopback SOCKS port: one above the app's listen port (127.0.0.1 only, not exposed).
        val cascadeLoopPort = listenPort + 1
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", logLevel) }

            // Stretch the handshake (and idle) budget for slow chained tunnels so Xray doesn't kill a
            // still-completing handshake. Only emitted when a caller asks for it (e.g. dnstt).
            if (handshakeTimeoutSec != null) {
                putJsonObject("policy") {
                    putJsonObject("levels") {
                        putJsonObject("0") {
                            put("handshake", handshakeTimeoutSec)
                            put("connIdle", 600)
                            // Generous half-close drain (defaults are 2s/5s) so a peer that finished one
                            // direction isn't cut while the other still trickles over the slow tunnel.
                            put("uplinkOnly", 30)
                            put("downlinkOnly", 30)
                        }
                    }
                }
            }

            putJsonObject("dns") {
                val profileHosts = routingProfile?.dnsHosts ?: emptyMap()
                if (traffic.blockRuDomains || profileHosts.isNotEmpty()) {
                    putJsonObject("hosts") {
                        if (traffic.blockRuDomains) RuBlocklist.hostRegexps.forEach { put(it, "0.0.0.0") }
                        profileHosts.forEach { (k, v) -> put(k, v) }
                    }
                }
                putJsonArray("servers") {
                    // FakeDNS first so sniffed domains get a synthetic IP before the real resolvers.
                    if (traffic.fakeDnsEnabled) add(FAKEDNS_SERVER)
                    // Remote resolvers ride the proxy/cascade → DoH-over-443 (major IPs) or DNS-over-TCP
                    // so they aren't dropped by an exit that blocks port 53 (the 4s-serial-timeout →
                    // ERR_CONNECTION_ABORTED bug).
                    add(proxiedDns(traffic.remoteDns))
                    if (traffic.remoteDns2.isNotBlank()) add(proxiedDns(traffic.remoteDns2))
                    add(traffic.directDns)
                }
                put("queryStrategy", traffic.xrayQueryStrategy())
            }

            // Synthetic-IP pool for FakeDNS (apps see 198.18.x.x; the domain is recovered via the
            // socks inbound's `fakedns` sniffing and resolved behind the proxy).
            if (traffic.fakeDnsEnabled) {
                putJsonArray("fakedns") { add(fakeDnsPool()) }
            }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "socks-in")
                    put("listen", listenHost)
                    put("port", listenPort)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("udp", true)
                        if (socksUsername.isNotBlank()) {
                            put("auth", "password")
                            putJsonArray("accounts") {
                                addJsonObject {
                                    put("user", socksUsername)
                                    put("pass", socksPassword)
                                }
                            }
                        } else {
                            put("auth", "noauth")
                        }
                    }
                    putJsonObject("sniffing") {
                        // Expert mode can disable sniffing or switch it to route-only (sniff for routing
                        // but keep dialing the original destination). Sniffing ON is required for
                        // `domain:` rules to match by SNI/Host — the usual reason domain:ru "does nothing".
                        val expert = routingProfile?.expertEnabled == true
                        put("enabled", if (expert) routingProfile!!.xraySniffing else true)
                        putJsonArray("destOverride") {
                            add("http"); add("tls"); add("quic")
                            // Recover the real domain from a FakeDNS synthetic-IP connection.
                            if (traffic.fakeDnsEnabled) add("fakedns")
                        }
                        if (expert && routingProfile!!.xrayRouteOnly) put("routeOnly", true)
                    }
                }
                // Loopback SOCKS relay for the xhttp-main cascade (127.0.0.1 only, not exposed). Its
                // traffic is routed straight to the xhttp main ([PROXY_BASE_TAG]) by the rule below.
                if (cascadeLoopActive) addJsonObject {
                    put("tag", CASCADE_LOOP_IN_TAG)
                    put("listen", "127.0.0.1")
                    put("port", cascadeLoopPort)
                    put("protocol", "socks")
                    putJsonObject("settings") { put("udp", true); put("auth", "noauth") }
                }
            }

            val wgBaseOutbound = wireguardBase?.let { buildWireguardBaseOutbound(it) }
            // The main proxy dials through the WG base (VK-TURN) when present, else olcRTC (Chain).
            val baseDetour = if (wgBaseOutbound != null) WG_BASE_TAG else null
            // The base tunnel's exit tag (WG for VK-TURN, dnstt SOCKS for dnstt). Used to keep `direct`
            // traffic on the tunnel when [directViaBase].
            val baseExitTag = when {
                wgBaseOutbound != null -> WG_BASE_TAG
                olcrtcChainPort != null -> OLCRTC_TAG
                else -> null
            }
            val directDialsBase = directViaBase && baseExitTag != null
            val second = secondProfile?.takeIf { it.isComplete() }
            putJsonArray("outbounds") {
                if (second != null) {
                    // Cascade: traffic exits via the SECOND proxy (tag PROXY_TAG, emitted FIRST so it is
                    // Xray's default outbound), which dials THROUGH the main (PROXY_BASE_TAG); the main
                    // dials through olcRTC/WG. Keeping the second as PROXY_TAG means the default route and
                    // routing rules transparently use the real exit.
                    add(
                        buildProxyOutbound(
                            second,
                            chained = false,
                            traffic = traffic,
                            // xhttp main can't sub-dial → the exit dials through the SOCKS loopback
                            // (which routes to the main). Otherwise it dials directly through the main.
                            detourTagOverride = if (cascadeLoopActive) CASCADE_LOOP_OUT_TAG else PROXY_BASE_TAG,
                            tag = PROXY_TAG,
                            // Force dialerProxy over the loopback so the exit's OWN transport is preserved.
                            chainViaDialerProxy = cascadeLoopActive,
                            // Over the clean loopback tunnel, KEEP the exit's XTLS Vision flow — the 2nd
                            // server requires it (a vless-reality-vision tcp 2nd proxy reset with it dropped
                            // while an xhttp 2nd, which has no Vision flow, worked).
                            preserveFlow = cascadeLoopActive,
                        )
                    )
                    add(
                        buildProxyOutbound(
                            profile,
                            chained = olcrtcChainPort != null,
                            traffic = traffic,
                            detourTagOverride = baseDetour,
                            tag = PROXY_BASE_TAG,
                            // Pack the loopback's many per-flow connections onto few main H2 tunnels.
                            xhttpHighConcurrency = cascadeLoopActive,
                        )
                    )
                    // The relay's socks outbound (loops back to the xhttp main, which dials the 2nd server).
                    if (cascadeLoopActive) addJsonObject {
                        put("tag", CASCADE_LOOP_OUT_TAG)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            putJsonArray("servers") {
                                addJsonObject { put("address", "127.0.0.1"); put("port", cascadeLoopPort) }
                            }
                        }
                    }
                } else {
                    // Single hop: the main proxy IS the exit (tag PROXY_TAG), dialing through olcRTC/WG.
                    add(
                        buildProxyOutbound(
                            profile,
                            chained = olcrtcChainPort != null,
                            traffic = traffic,
                            detourTagOverride = baseDetour,
                            tag = PROXY_TAG,
                            chainViaDialerProxy = chainViaDialerProxy,
                        )
                    )
                }
                if (wgBaseOutbound != null) add(wgBaseOutbound)
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        // Resolve direct destinations via xray's own DNS, not Go's (broken on Android).
                        put("domainStrategy", directDomainStrategy(traffic))
                    }
                    // VK-TURN / dnstt: send `direct` traffic THROUGH the base tunnel (the dnstt-server /
                    // VK exit) instead of the real interface, so routing never bypasses the tunnel.
                    if (directDialsBase) {
                        putJsonObject("streamSettings") {
                            putJsonObject("sockopt") { put("dialerProxy", baseExitTag) }
                        }
                    }
                }
                // Always present: needed by the RU blocklist, profile block buckets AND the QUIC
                // blackhole rule below.
                addJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                }
                // FakeDNS: DNS queries (port 53/853) are hijacked to this dns outbound so the core
                // answers them from the fake pool / upstream instead of leaking them out.
                if (traffic.fakeDnsEnabled) add(dnsOutOutbound())
                // TLS fragmentation outbound (DPI evasion); proxy dials through it via dialerProxy.
                if (traffic.fragmentEnabled && olcrtcChainPort == null) {
                    addJsonObject {
                        put("tag", "fragment")
                        put("protocol", "freedom")
                        putJsonObject("settings") {
                            putJsonObject("fragment") {
                                put("packets", traffic.fragmentPackets)
                                put("length", traffic.fragmentLength)
                                put("interval", traffic.fragmentInterval)
                            }
                        }
                    }
                }
                // olcRTC chain detour: the main proxy (PROXY_TAG or PROXY_BASE_TAG) dials through this.
                if (olcrtcChainPort != null) {
                    add(olcrtcSocksOutbound(OLCRTC_TAG, olcrtcChainPort, olcrtcChainUser, olcrtcChainPass))
                }
            }

            // QUIC (HTTP/3) over a TCP-only transport (xhttp/reality/ws) can't pass → ERR_QUIC_PROTOCOL
            // on Telemost/Wildberries/Google. Blackhole UDP/443 so clients fall back to TCP/HTTP2.
            val quicBlockRule = buildJsonObject {
                put("type", "field")
                put("network", "udp")
                put("port", 443)
                put("outboundTag", "block")
            }
            // FakeDNS: route DNS queries to the dns outbound so they're answered from the fake pool.
            val dnsOutRules = if (traffic.fakeDnsEnabled) dnsHijackRules() else emptyList()
            // Blocked RU hosts resolve to 0.0.0.0 (dns.hosts above); blackhole anything aimed there.
            val blockZeroRule = buildJsonObject {
                put("type", "field")
                putJsonArray("ip") { add("0.0.0.0") }
                put("outboundTag", "block")
            }
            // Domain-strategy enforcement: blackhole the opposite IP family so ipv4_only / ipv6_only
            // forces ALL traffic onto the chosen family (even DoH apps that resolve the other one).
            // prefer_ipv4 is folded into the ipv4_only path here: on an IPv4-only ISP the user wants NO
            // tunnel IPv6, and without this the EXIT proxy's dual-stack server picks AAAA for proxied
            // domains → 2ip shows the tunnel's IPv6 even though the bridge already drops client-side v6.
            // This is the SAME recipe as ipv4_only (which works), so it adds no new failure mode.
            val familyBlockRule = when {
                !forceFamilyResolve -> null
                traffic.domainStrategy == "ipv4_only" || traffic.domainStrategy == "prefer_ipv4" -> buildJsonObject {
                    put("type", "field"); putJsonArray("ip") { add("::/0") }; put("outboundTag", "block")
                }
                traffic.domainStrategy == "ipv6_only" -> buildJsonObject {
                    put("type", "field"); putJsonArray("ip") { add("0.0.0.0/0") }; put("outboundTag", "block")
                }
                else -> null
            }
            // When forcing a family (ipv4_only/ipv6_only) we must resolve domains for routing so the
            // opposite-family blackhole below actually matches domain destinations (with AsIs it only
            // catches raw IP literals, and the remote proxy is then free to pick AAAA → IPv6 leak on
            // 2ip.io). IPIfNonMatch + queryStrategy=UseIPv4 makes Xray resolve to the chosen family.
            val forceFamily = familyBlockRule != null
            // LAN/private → real direct (only when `direct` is the real network, never on a base-detour
            // tunnel exit). Matched before the proxy buckets so local addresses never enter the tunnel.
            // Loopback chaining: the relay inbound the exit dials through MUST exit via the real xhttp
            // main ([PROXY_BASE_TAG]) — never direct/geo/quic-block/family-block (which would drop it).
            // Emitted FIRST so it wins over every other rule.
            val cascadeLoopRule = if (cascadeLoopActive) buildJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(CASCADE_LOOP_IN_TAG) }
                put("outboundTag", PROXY_BASE_TAG)
            } else null
            val lanBypassRule = if (bypassLan && !directViaBase) buildJsonObject {
                put("type", "field")
                putJsonArray("ip") {
                    add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16")
                    add("127.0.0.0/8"); add("169.254.0.0/16")
                }
                put("outboundTag", "direct")
            } else null
            putJsonObject("routing") {
                if (routingProfile != null) {
                    // Profile routing (direct/block/proxy buckets) COMBINED with QUIC block + RU
                    // blocklist (item 5): the toggles run alongside the profile, not instead of it.
                    val base = XrayRouting.routingObject(routingProfile)
                    val baseStrategy = base["domainStrategy"] ?: JsonPrimitive("AsIs")
                    put("domainStrategy", if (forceFamily) JsonPrimitive("IPIfNonMatch") else baseStrategy)
                    putJsonArray("rules") {
                        // Loopback relay → xhttp main, before anything that could drop/redirect it.
                        cascadeLoopRule?.let { add(it) }
                        // DNS hijack first so port-53/853 queries reach dns-out before any other rule.
                        dnsOutRules.forEach { add(it) }
                        lanBypassRule?.let { add(it) }
                        if (blockQuic) add(quicBlockRule)
                        familyBlockRule?.let { add(it) }
                        if (traffic.blockRuDomains) add(blockZeroRule)
                        (base["rules"] as? JsonArray)?.forEach { add(it) }
                    }
                } else {
                    put("domainStrategy", if (forceFamily) "IPIfNonMatch" else "AsIs")
                    putJsonArray("rules") {
                        cascadeLoopRule?.let { add(it) }
                        dnsOutRules.forEach { add(it) }
                        lanBypassRule?.let { add(it) }
                        if (blockQuic) add(quicBlockRule)
                        familyBlockRule?.let { add(it) }
                        if (traffic.blockRuDomains) add(blockZeroRule)
                    }
                }
            }
        }
        return json.encodeToString(config)
    }

    /**
     * Prepares a user-supplied full Xray JSON config to run verbatim through xray-core: the only
     * thing we rewrite is the SOCKS inbound, so it listens on the bridge's [listenHost]:[listenPort]
     * with the expected auth. Everything else (dns.hosts, routing.rules, fakedns, outbounds…) is
     * preserved exactly as the user authored it. Returns the original text unchanged if it can't be
     * parsed (xray-core will then surface the error on start).
     */
    fun prepareRaw(
        rawConfigJson: String,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        // When set, the profile's routing buckets (e.g. domain:ru → direct) are MERGED into the user's
        // verbatim config. Without this the routing profile was silently ignored for full custom Xray
        // configs (common with server-side cascade setups), so domain:ru never went direct on vless.
        routingProfile: org.olcbox.app.data.model.RoutingProfile? = null,
        // Global FakeDNS toggle. When on AND the raw config doesn't already define `fakedns`, we inject
        // the fakedns plumbing (pool + dns server + sniffing + dns-out). A config that ALREADY carries
        // its own `fakedns` (e.g. a Remnawave/Happ subscription) is honored verbatim — never overwritten.
        fakeDnsEnabled: Boolean = false,
        // Strip geosite:/geoip: selectors from the config's OWN routing rules & dns servers. xray-core
        // FAILS TO LOAD ("open .../geosite.dat: no such file", or "failed to parse domain rule:
        // geosite:<cat>") when the matching geoip.dat/geosite.dat isn't present (blocked network can't
        // download it) or lacks a category the config used. Stripping keeps every regexp/domain/CIDR
        // rule (which do the real RU routing) so the config ALWAYS loads; only the geo-DB-dependent
        // selectors (geosite:private, geosite:telegram, geoip:private…) are dropped.
        stripGeoSelectors: Boolean = false,
        // Enforce IPv4 on a verbatim config under ipv4_only/prefer_ipv4: force every `freedom` outbound
        // to ForceIPv4 (so a DIRECT site — e.g. 2ip.ru — never dials real IPv6, even via a raw v6
        // literal; the embedded `direct` is plain freedom=AsIs and leaked) and override dns.queryStrategy
        // to UseIPv4 (A-only resolution, so dual-stack sites never learn an AAAA to attempt). The hev
        // bridge's IPv6 drop only gates tun→bridge, NOT the core's own outbound sockets — hence this.
        forceIpv4: Boolean = false,
        // Optional SECOND/cascade proxy chained on top of a verbatim config: traffic exits via this
        // proxy, which dials THROUGH the config's main proxy outbound (client → main-server →
        // second-server → internet). Only standard Xray protocols (vless/vmess/trojan/ss) can be chained
        // here — AmneziaWG/WireGuard/Hysteria2 aren't Xray exit outbounds and are ignored (logged by the
        // caller). Null = single hop.
        secondProfile: ProxyProfile? = null,
    ): String {
        val root = runCatching { Json.parseToJsonElement(rawConfigJson).jsonObject }.getOrNull()
            ?: return rawConfigJson

        // Only inject when the user's config doesn't already opt into FakeDNS (honor wins).
        val injectFake = fakeDnsEnabled && root["fakedns"] == null

        // Reuse the user's existing socks inbound (to keep its sniffing/fakedns) when present.
        val existingInbounds = (root["inbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val templateSocks = existingInbounds.firstOrNull {
            it["protocol"]?.jsonPrimitive?.contentOrNull == "socks"
        }
        val nonSocks = existingInbounds.filter {
            it["protocol"]?.jsonPrimitive?.contentOrNull != "socks"
        }

        val socksInbound = buildJsonObject {
            put("tag", "socks-in")
            put("listen", listenHost)
            put("port", listenPort)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("udp", true)
                if (socksUsername.isNotBlank()) {
                    put("auth", "password")
                    putJsonArray("accounts") {
                        addJsonObject {
                            put("user", socksUsername)
                            put("pass", socksPassword)
                        }
                    }
                } else {
                    put("auth", "noauth")
                }
            }
            // Preserve the user's sniffing block (carries fakedns/destOverride needed for hosts blocking).
            val sniffing = templateSocks?.get("sniffing") as? JsonObject
            when {
                // Injecting FakeDNS: ensure the sniffer recovers the domain from a synthetic IP.
                injectFake -> put("sniffing", sniffingWithFakeDns(sniffing))
                sniffing != null -> put("sniffing", sniffing)
                else -> putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                }
            }
        }

        // Merge profile routing (direct/block buckets) into the user's config, if requested.
        val userOutbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val userOutboundTags = userOutbounds.mapNotNull { it["tag"]?.jsonPrimitive?.contentOrNull }.toSet()
        val userRouting = root["routing"] as? JsonObject

        // CASCADE: a standard second proxy chained on top of the verbatim config. Its exit outbound
        // reaches its server THROUGH the config's main proxy outbound and becomes the default exit, so
        // traffic flows client → main-server → second-server → internet.
        val cascadeTypes = setOf(
            ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS,
            ProxyProfile.TYPE_TROJAN, ProxyProfile.TYPE_SHADOWSOCKS
        )
        val cascadeSecond = secondProfile?.takeIf { it.isComplete() && it.type in cascadeTypes }
        // The config's main proxy outbound (the protocol outbound it exits through today).
        val mainProxyOutbound = userOutbounds.firstOrNull {
            it["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() in cascadeTypes
        }
        val mainProxyTag = mainProxyOutbound?.get("tag")?.jsonPrimitive?.contentOrNull
        // An xhttp/splithttp MAIN can't serve as an intermediate hop: Xray's splithttp transport can be
        // NEITHER a proxySettings target NOR a sockopt.dialerProxy sub-dialer, so a 2nd proxy chained
        // "through" it (either mechanism) gets NO connection — the long-standing "xhttp + 2nd tcp proxy
        // doesn't connect". Fix: chain via a local SOCKS loopback (CASCADE_LOOP_*) so the xhttp main runs
        // as an ORDINARY outbound that just proxies the TCP to the 2nd server. Non-xhttp mains keep the
        // direct chain (proxySettings for tcp/reality, dialerProxy only when the EXIT itself is xhttp).
        val mainNetwork = (mainProxyOutbound?.get("streamSettings") as? JsonObject)
            ?.get("network")?.jsonPrimitive?.contentOrNull?.lowercase()
        val mainIsXhttp = mainNetwork == "xhttp" || mainNetwork == "splithttp"
        // Internal loopback SOCKS port: one above the app's listen port (127.0.0.1 only, not exposed).
        val cascadeLoopPort = listenPort + 1
        val cascadeOutbound = if (cascadeSecond != null && mainProxyTag != null) {
            if (mainIsXhttp) {
                buildProxyOutbound(
                    cascadeSecond,
                    chained = false,
                    // Dial the exit's socket through the loopback SOCKS (which routes to the xhttp main).
                    // Force dialerProxy so the exit's OWN transport (tcp/reality/ws/xhttp) is preserved.
                    detourTagOverride = CASCADE_LOOP_OUT_TAG,
                    tag = CASCADE_EXIT_TAG,
                    chainViaDialerProxy = true,
                    // Keep XTLS Vision flow over the clean loopback — the 2nd server requires it.
                    preserveFlow = true,
                )
            } else {
                buildProxyOutbound(
                    cascadeSecond,
                    chained = false,
                    detourTagOverride = mainProxyTag,
                    tag = CASCADE_EXIT_TAG,
                    // Non-xhttp base: proxy-level (proxySettings) is the standard multi-hop. dialerProxy
                    // is only needed to preserve the EXIT's OWN transport when the EXIT itself is xhttp.
                    chainViaDialerProxy = cascadeSecond.network == ProxyProfile.NETWORK_XHTTP,
                )
            }
        } else null
        val cascadeLoopActive = cascadeOutbound != null && mainProxyTag != null && mainIsXhttp
        // The local SOCKS outbound the exit dials through (→ the loopback inbound below).
        val cascadeLoopOutbound = if (cascadeLoopActive) buildJsonObject {
            put("tag", CASCADE_LOOP_OUT_TAG)
            put("protocol", "socks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject { put("address", "127.0.0.1"); put("port", cascadeLoopPort) }
                }
            }
        } else null
        // The loopback SOCKS inbound; its traffic is routed straight to the xhttp main (rule added below).
        val cascadeLoopInbound = if (cascadeLoopActive) buildJsonObject {
            put("tag", CASCADE_LOOP_IN_TAG)
            put("listen", "127.0.0.1")
            put("port", cascadeLoopPort)
            put("protocol", "socks")
            putJsonObject("settings") { put("udp", true); put("auth", "noauth") }
        } else null
        // HONOR THE CONFIG'S OWN ROUTING: when the raw config already ships routing rules, the embedded
        // routing takes precedence and the app's routing profile is NOT overlaid. Overlaying it injected
        // extra selectors (e.g. geosite:category-ads) that need a geosite.dat the config never asked for,
        // failing the whole config ("open .../geosite.dat: no such file"). The profile is still merged
        // into configs that carry NO routing of their own (bare/cascade custom configs), keeping that path.
        val userHasRouting = (userRouting?.get("rules") as? JsonArray)?.isNotEmpty() == true
        val mergedRouting: JsonObject? = if (userHasRouting) null else routingProfile?.let { rp ->
            buildJsonObject {
                // Keep the user's domainStrategy if they set one; else the profile's.
                val ds = userRouting?.get("domainStrategy")?.jsonPrimitive?.contentOrNull
                    ?: XrayRouting.domainStrategy(rp)
                put("domainStrategy", ds)
                putJsonArray("rules") {
                    // Profile buckets FIRST so domain:ru → direct wins over the user's catch-all proxy.
                    XrayRouting.bucketRules(rp).forEach { add(it) }
                    // Then the user's own rules (their proxy default / custom splits).
                    (userRouting?.get("rules") as? JsonArray)?.forEach { add(it) }
                    // Selective-proxy fall-through (only when not a global-proxy profile).
                    XrayRouting.catchAllDirectRule(rp)?.let { add(it) }
                }
            }
        }

        // FakeDNS injection (only when the raw config doesn't already define it): augment `dns` with a
        // `fakedns` server, add the synthetic-IP pool, and hijack DNS ports to a `dns-out` outbound.
        val rawDns = root["dns"] as? JsonObject
        val augmentedDns = if (injectFake) buildJsonObject {
            rawDns?.forEach { (k, v) -> if (k != "servers") put(k, v) }
            putJsonArray("servers") {
                add("fakedns")
                val servers = rawDns?.get("servers") as? JsonArray
                if (servers != null) servers.forEach { add(it) } else add("1.1.1.1")
            }
            if (rawDns?.get("queryStrategy") == null) put("queryStrategy", "UseIP")
        } else null

        // Build the final routing object, prepending the DNS-hijack rules when injecting FakeDNS.
        val baseRouting = mergedRouting ?: userRouting
        val finalRouting: JsonObject? = when {
            injectFake -> buildJsonObject {
                baseRouting?.forEach { (k, v) -> if (k != "rules") put(k, v) }
                if (baseRouting?.get("domainStrategy") == null) put("domainStrategy", "AsIs")
                putJsonArray("rules") {
                    dnsHijackRules().forEach { add(it) }
                    (baseRouting?.get("rules") as? JsonArray)?.forEach { add(it) }
                }
            }
            mergedRouting != null -> mergedRouting
            userRouting != null -> userRouting
            else -> null
        }

        // The dns to write: injected-fakedns version if any, else the config's own — geo-stripped when
        // requested so unresolvable geosite: DNS categories can't fail the load.
        val baseDns = augmentedDns ?: (root["dns"] as? JsonObject)
        val strippedDns = if (stripGeoSelectors) stripGeoFromDns(baseDns) else baseDns
        // Force A-only resolution under ipv4_only/prefer_ipv4 so dual-stack sites never learn an AAAA.
        val outDns = if (forceIpv4 && strippedDns != null) buildJsonObject {
            strippedDns.forEach { (k, v) -> if (k != "queryStrategy") put(k, v) }
            put("queryStrategy", "UseIPv4")
        } else strippedDns
        // The routing to write: strip geo ONLY from the config's OWN embedded routing (userHasRouting) —
        // a merged routing PROFILE keeps its geo selectors (handled separately by the caller's asset path).
        val geoStrippedRouting =
            if (stripGeoSelectors && userHasRouting) stripGeoFromRouting(finalRouting) else finalRouting
        // Cascade: send everything that exited via the main proxy through the cascade exit instead, so
        // the exit (which dials through the main) becomes the real exit. Default fall-through is also
        // covered by emitting CASCADE_EXIT_TAG as the FIRST outbound below.
        val routingAfterCascade = if (cascadeOutbound != null && mainProxyTag != null) {
            rewriteOutboundTag(geoStrippedRouting, mainProxyTag, CASCADE_EXIT_TAG)
        } else geoStrippedRouting
        // Loopback chaining: the relay inbound the exit dials through MUST exit via the real xhttp main —
        // NOT direct/geo, and NOT the rewritten cascade-exit (that would loop forever). Prepend a
        // high-priority inboundTag rule so it wins over every other rule. mainProxyTag is non-null here.
        val routingToWrite = if (cascadeLoopActive) {
            val loopRule = buildJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add(CASCADE_LOOP_IN_TAG) }
                put("outboundTag", mainProxyTag)
            }
            val existingRules = (routingAfterCascade?.get("rules") as? JsonArray)?.toList() ?: emptyList()
            buildJsonObject {
                routingAfterCascade?.forEach { (k, v) -> if (k != "rules") put(k, v) }
                putJsonArray("rules") { add(loopRule); existingRules.forEach { add(it) } }
            }
        } else routingAfterCascade

        val newRoot = buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "inbounds" && key != "outbounds" && key != "routing" && key != "dns" &&
                    !(injectFake && key == "fakedns")
                ) {
                    put(key, value)
                }
            }
            if (outDns != null) put("dns", outDns)
            if (injectFake) putJsonArray("fakedns") { add(fakeDnsPool()) }
            putJsonArray("inbounds") {
                add(socksInbound)
                // Loopback SOCKS relay (only present for the xhttp-main cascade), 127.0.0.1 only.
                cascadeLoopInbound?.let { add(it) }
                nonSocks.forEach { add(it) }
            }
            putJsonArray("outbounds") {
                // Cascade exit FIRST so it's xray's default outbound (fall-through traffic exits via it).
                cascadeOutbound?.let { add(it) }
                // The relay's socks outbound (loops back to the xhttp main, which dials the 2nd server).
                cascadeLoopOutbound?.let { add(it) }
                userOutbounds.forEach { ob ->
                    val lifted = liftXhttpExtraHostPath(ob)
                    // Cascade base: pack the loopback's per-flow connections onto few main H2 tunnels
                    // (only the xhttp main, only when it has no xmux of its own — honor the user's).
                    val tuned = if (cascadeLoopActive &&
                        ob["tag"]?.jsonPrimitive?.contentOrNull == mainProxyTag
                    ) withXhttpHighConcurrency(lifted) else lifted
                    add(if (forceIpv4) forceIpv4Freedom(tuned) else tuned)
                }
                // The profile rules reference direct/block tags — make sure they exist.
                if (mergedRouting != null && "direct" !in userOutboundTags) {
                    addJsonObject {
                        put("tag", "direct")
                        put("protocol", "freedom")
                        // Resolve via xray's DNS (Go's resolver can't on Android) so direct rules work.
                        // ForceIPv4 (not UseIPv4) so the merged profile's domain:ru → direct never dials the
                        // user's real IPv6 — including refusing a raw IPv6 literal — matching the typed-config
                        // direct outbound above.
                        putJsonObject("settings") { put("domainStrategy", "ForceIPv4") }
                    }
                }
                if (mergedRouting != null && "block" !in userOutboundTags) {
                    addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
                }
                // FakeDNS dns outbound for the hijacked queries.
                if (injectFake && "dns-out" !in userOutboundTags) add(dnsOutOutbound())
            }
            if (routingToWrite != null) put("routing", routingToWrite)
        }
        return json.encodeToString(newRoot)
    }

    /**
     * Builds a THROWAWAY Xray config for the per-server URL-test ("ping") of a VERBATIM raw config —
     * an xhttp/splithttp/reality node a bare [ProxyProfile] can't represent, so the connect path runs
     * it from [ProxyProfile.rawXrayConfig]. The ping path used to ignore that and rebuild a plain
     * vless from type/server/port (no streamSettings) — which can NEVER reach an xhttp/reality server,
     * so every such location falsely showed "недоступен" even while it connected fine.
     *
     * This instead lifts the config's REAL proxy outbound (transport verbatim) and routes ALL traffic
     * through it — no `routing`/`dns` block, so the probe measures the actual transport à la Happ
     * instead of accidentally hitting a `direct`/`block` rule, and needs no geosite.dat. Returns null
     * when the config has no recognizable proxy outbound.
     */
    fun buildRawProxyPingConfig(
        rawConfigJson: String,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
    ): String? {
        val root = runCatching { Json.parseToJsonElement(rawConfigJson).jsonObject }.getOrNull() ?: return null
        val outbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")
        val proxyOutbound = outbounds.firstOrNull {
            it["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() in proxyProtocols
        } ?: return null
        // Re-tag to "proxy" so it's unambiguously the sole exit; everything else is kept verbatim
        // (streamSettings carry the xhttp/splithttp/reality fronting the probe must reproduce).
        val pingOutbound = buildJsonObject {
            var taggedProxy = false
            proxyOutbound.forEach { (k, v) ->
                if (k == "tag") { put("tag", PROXY_TAG); taggedProxy = true } else put(k, v)
            }
            if (!taggedProxy) put("tag", PROXY_TAG)
        }
        val socksInbound = buildJsonObject {
            put("tag", "socks-in")
            put("listen", listenHost)
            put("port", listenPort)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("udp", true)
                put("auth", "noauth")
            }
        }
        val newRoot = buildJsonObject {
            putJsonArray("inbounds") { add(socksInbound) }
            putJsonArray("outbounds") { add(pingOutbound) }
            // No routing/dns: a single outbound means every dialed destination exits via the proxy,
            // and the server address resolves through the system resolver (no chicken-and-egg DNS).
        }
        return json.encodeToString(newRoot)
    }

    /** Rewrites every routing rule whose `outboundTag` is [from] to [to] (used to redirect the main
     *  proxy's traffic to the cascade exit). Leaves other rules untouched. */
    private fun rewriteOutboundTag(routing: JsonObject?, from: String, to: String): JsonObject? {
        if (routing == null) return null
        val rules = routing["rules"] as? JsonArray ?: return routing
        val rewritten = rules.map { el ->
            val rule = el as? JsonObject ?: return@map el
            if (rule["outboundTag"]?.jsonPrimitive?.contentOrNull != from) return@map el
            buildJsonObject {
                rule.forEach { (k, v) -> if (k == "outboundTag") put(k, to) else put(k, v) }
            }
        }
        return buildJsonObject {
            routing.forEach { (k, v) -> if (k != "rules") put(k, v) }
            put("rules", JsonArray(rewritten))
        }
    }

    /**
     * Lifts `xhttpSettings.extra.host`/`.path` to the TOP-LEVEL `xhttpSettings.host`/`.path` when the
     * top level is empty. xray-core merges `extra` then OVERRIDES extra.Host/Path with the top-level
     * values (infra/conf transport_internet: `extra.Host = c.Host`), so a domain-fronted config that
     * only carries the real host in `extra.host` (top-level host="") sends an EMPTY host → xray falls
     * back to the reality SNI as the Host header → the fronted backend returns HTTP 400. Copying the
     * value up makes the override a no-op and the correct Host header is sent. No-op for configs that
     * already put host at the top level (the working ones) or have no xhttp `extra`.
     */
    private fun liftXhttpExtraHostPath(outbound: JsonObject): JsonObject {
        val stream = outbound["streamSettings"] as? JsonObject ?: return outbound
        val xhttp = stream["xhttpSettings"] as? JsonObject ?: return outbound
        val extra = xhttp["extra"] as? JsonObject ?: return outbound
        val topHost = xhttp["host"]?.jsonPrimitive?.contentOrNull
        val topPath = xhttp["path"]?.jsonPrimitive?.contentOrNull
        val extraHost = extra["host"]?.jsonPrimitive?.contentOrNull
        val extraPath = extra["path"]?.jsonPrimitive?.contentOrNull
        val newHost = if (topHost.isNullOrBlank() && !extraHost.isNullOrBlank()) extraHost else topHost
        val newPath = if (topPath.isNullOrBlank() && !extraPath.isNullOrBlank()) extraPath else topPath
        if (newHost == topHost && newPath == topPath) return outbound
        val newXhttp = buildJsonObject {
            xhttp.forEach { (k, v) -> if (k != "host" && k != "path") put(k, v) }
            if (!newHost.isNullOrBlank()) put("host", newHost)
            if (!newPath.isNullOrBlank()) put("path", newPath)
        }
        val newStream = buildJsonObject {
            stream.forEach { (k, v) -> if (k != "xhttpSettings") put(k, v) }
            put("xhttpSettings", newXhttp)
        }
        return buildJsonObject {
            outbound.forEach { (k, v) -> if (k != "streamSettings") put(k, v) }
            put("streamSettings", newStream)
        }
    }

    /**
     * Gives a verbatim xhttp outbound a high-concurrency `xmux` so the cascade SOCKS loopback's many
     * per-app-flow connections collapse onto a couple of HTTP/2 tunnels (else the main spawned a fresh
     * H2 connection per flow → 18+ → server dropped them = stalls). No-op if the outbound isn't xhttp or
     * already defines its own `xmux` (honor the user's tuning).
     */
    private fun withXhttpHighConcurrency(outbound: JsonObject): JsonObject {
        val stream = outbound["streamSettings"] as? JsonObject ?: return outbound
        val xhttp = stream["xhttpSettings"] as? JsonObject ?: return outbound
        if (xhttp["xmux"] != null) return outbound
        val newXhttp = buildJsonObject {
            xhttp.forEach { (k, v) -> put(k, v) }
            putJsonObject("xmux") {
                put("maxConnections", "4-8")
                put("cMaxReuseTimes", "64-128")
                put("cMaxLifetimeMs", 0)
            }
        }
        val newStream = buildJsonObject {
            stream.forEach { (k, v) -> if (k != "xhttpSettings") put(k, v) }
            put("xhttpSettings", newXhttp)
        }
        return buildJsonObject {
            outbound.forEach { (k, v) -> if (k != "streamSettings") put(k, v) }
            put("streamSettings", newStream)
        }
    }

    /** Forces a `freedom` outbound to ForceIPv4 (so it never dials IPv6, incl. raw v6 literals). */
    private fun forceIpv4Freedom(outbound: JsonObject): JsonObject {
        if (outbound["protocol"]?.jsonPrimitive?.contentOrNull != "freedom") return outbound
        val settings = outbound["settings"] as? JsonObject
        return buildJsonObject {
            outbound.forEach { (k, v) -> if (k != "settings") put(k, v) }
            putJsonObject("settings") {
                settings?.forEach { (k, v) -> if (k != "domainStrategy") put(k, v) }
                put("domainStrategy", "ForceIPv4")
            }
        }
    }

    /** Removes geosite:/geoip: entries from a string array (keeps regexp:/domain:/CIDR/plain entries). */
    private fun stripGeoArray(arr: JsonArray?): JsonArray = JsonArray(
        (arr ?: JsonArray(emptyList())).filter {
            val s = (it as? JsonPrimitive)?.contentOrNull?.lowercase()
            s == null || !(s.startsWith("geosite:") || s.startsWith("geoip:"))
        }
    )

    /**
     * Strips geosite:/geoip: from a routing object's rules: cleans each rule's `domain`/`ip` arrays and
     * DROPS a rule whose only matchers were geo selectors (now empty) so it can't accidentally match-all.
     * Rules matched by other fields (network/port/protocol/inboundTag) are preserved untouched.
     */
    private fun stripGeoFromRouting(routing: JsonObject?): JsonObject? {
        if (routing == null) return null
        val rules = routing["rules"] as? JsonArray ?: return routing
        val cleaned = rules.mapNotNull { el ->
            val rule = el as? JsonObject ?: return@mapNotNull el
            val hadDomain = rule["domain"] is JsonArray
            val hadIp = rule["ip"] is JsonArray
            val newDomain = if (hadDomain) stripGeoArray(rule["domain"] as? JsonArray) else null
            val newIp = if (hadIp) stripGeoArray(rule["ip"] as? JsonArray) else null
            val domainEmptied = hadDomain && newDomain!!.isEmpty()
            val ipEmptied = hadIp && newIp!!.isEmpty()
            // Drop only when EVERY matcher this rule had became empty (purely-geo rule).
            when {
                hadDomain && hadIp && domainEmptied && ipEmptied -> return@mapNotNull null
                hadDomain && !hadIp && domainEmptied -> return@mapNotNull null
                hadIp && !hadDomain && ipEmptied -> return@mapNotNull null
            }
            buildJsonObject {
                rule.forEach { (k, v) ->
                    when (k) {
                        "domain" -> if (!newDomain!!.isEmpty()) put(k, newDomain)
                        "ip" -> if (!newIp!!.isEmpty()) put(k, newIp)
                        else -> put(k, v)
                    }
                }
            }
        }
        return buildJsonObject {
            routing.forEach { (k, v) -> if (k != "rules") put(k, v) }
            put("rules", JsonArray(cleaned))
        }
    }

    /** Strips geosite: entries from each dns server's `domains`; drops a server left with no domains. */
    private fun stripGeoFromDns(dns: JsonObject?): JsonObject? {
        if (dns == null) return null
        val servers = dns["servers"] as? JsonArray ?: return dns
        val cleaned = servers.mapNotNull { el ->
            val server = el as? JsonObject ?: return@mapNotNull el // plain "1.1.1.1" string server
            val domains = server["domains"] as? JsonArray ?: return@mapNotNull el
            val newDomains = stripGeoArray(domains)
            if (newDomains.isEmpty()) return@mapNotNull null // server only routed geo categories
            buildJsonObject {
                server.forEach { (k, v) -> if (k == "domains") put(k, newDomains) else put(k, v) }
            }
        }
        return buildJsonObject {
            dns.forEach { (k, v) -> if (k != "servers") put(k, v) }
            put("servers", JsonArray(cleaned))
        }
    }

    private const val WG_BAL_PREFIX = "wg-bal-"
    private const val WG_BALANCER_TAG = "vk-bal"

    /**
     * Builds an Xray config whose exit is a LOAD-BALANCED set of WireGuard outbounds — one per VK-TURN
     * freeturn server, each dialing its own local freeturn listener ([wgProfiles]; rawOutbound carries
     * server=127.0.0.1:localPort + keys). Xray's `balancers` spreads connections across them (strategy
     * `random` → per-connection, so several servers' bandwidth aggregates). There is NO proxy hop here:
     * the WireGuard tunnels ARE the exits, so this is a separate builder from [build] (which is
     * proxy-centric) and leaves the Standard cascade path completely untouched. The WG tunnels are
     * IPv4-only, so traffic resolves A-only and QUIC is carried natively (never blocked).
     */
    fun buildWireguardBalancer(
        wgProfiles: List<ProxyProfile>,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        logLevel: String = "debug",
        traffic: TrafficSettings = TrafficSettings(),
        bypassLan: Boolean = true,
    ): String {
        val bases = wgProfiles.mapIndexedNotNull { i, p -> buildWireguardBaseOutbound(p, "$WG_BAL_PREFIX$i") }
        val serverCount = bases.size
        val config = buildJsonObject {
            putJsonObject("log") { put("loglevel", logLevel) }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    if (traffic.fakeDnsEnabled) add(FAKEDNS_SERVER)
                    add(traffic.remoteDns)
                    if (traffic.remoteDns2.isNotBlank()) add(traffic.remoteDns2)
                    add(traffic.directDns)
                }
                put("queryStrategy", traffic.xrayQueryStrategy())
            }
            if (traffic.fakeDnsEnabled) putJsonArray("fakedns") { add(fakeDnsPool()) }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("tag", "socks-in")
                    put("listen", listenHost)
                    put("port", listenPort)
                    put("protocol", "socks")
                    putJsonObject("settings") {
                        put("udp", true)
                        if (socksUsername.isNotBlank()) {
                            put("auth", "password")
                            putJsonArray("accounts") {
                                addJsonObject { put("user", socksUsername); put("pass", socksPassword) }
                            }
                        } else {
                            put("auth", "noauth")
                        }
                    }
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") {
                            add("http"); add("tls"); add("quic")
                            if (traffic.fakeDnsEnabled) add("fakedns")
                        }
                    }
                }
            }
            putJsonArray("outbounds") {
                bases.forEach { add(it) }
                addJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    putJsonObject("settings") { put("domainStrategy", directDomainStrategy(traffic)) }
                }
                addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
                if (traffic.fakeDnsEnabled) add(dnsOutOutbound())
            }
            // Health-probe each WG-over-VK outbound so a DEAD freeturn server (VPS down, relay never
            // came up) is detected and dropped from the balancer instead of black-holing the
            // connections round-robined onto it — i.e. "even if the 2nd server doesn't connect, the
            // tunnel still works off the live one(s)". The probe egresses through each wg-bal-* outbound
            // (→ its local freeturn relay → VK → VPS → internet), so it measures the real end-to-end path.
            if (serverCount > 1) {
                putJsonObject("burstObservatory") {
                    putJsonArray("subjectSelector") { add(WG_BAL_PREFIX) }
                    putJsonObject("pingConfig") {
                        put("destination", "http://connectivitycheck.gstatic.com/generate_204")
                        put("interval", "10s")
                        put("sampling", 2)
                        put("timeout", "8s")
                    }
                }
            }
            putJsonObject("routing") {
                put("domainStrategy", "AsIs")
                putJsonArray("balancers") {
                    addJsonObject {
                        put("tag", WG_BALANCER_TAG)
                        putJsonArray("selector") { add(WG_BAL_PREFIX) }
                        // leastLoad with expected=N keeps ALL healthy servers in rotation (so their
                        // bandwidth still aggregates like round-robin) while the burstObservatory health
                        // data lets it EXCLUDE servers that don't answer — a dead 2nd server is skipped,
                        // not black-holed. fallbackTag guarantees an exit during the cold-start window
                        // before the first probe lands (and if every server is briefly unhealthy), so
                        // traffic is never dropped. Single server → no balancer (caller gate), so this
                        // only ever runs with ≥2 outbounds.
                        put("fallbackTag", "$WG_BAL_PREFIX${'0'}")
                        putJsonObject("strategy") {
                            put("type", "leastLoad")
                            putJsonObject("settings") {
                                put("expected", serverCount)
                                // Generous RTT ceiling: a server under this through VK qualifies; it only
                                // filters out the truly dead/unreachable ones.
                                putJsonArray("baselines") { add("3000ms") }
                            }
                        }
                    }
                }
                putJsonArray("rules") {
                    if (traffic.fakeDnsEnabled) dnsHijackRules().forEach { add(it) }
                    // Bypass LAN/private ranges straight out (not into the VK tunnel) so local-network
                    // devices stay reachable while connected. Explicit RFC1918 + loopback + link-local
                    // CIDRs (no geoip.dat dependency). Honors the global "Обход LAN" toggle.
                    if (bypassLan) {
                        addJsonObject {
                            put("type", "field")
                            putJsonArray("ip") {
                                add("10.0.0.0/8"); add("172.16.0.0/12"); add("192.168.0.0/16")
                                add("127.0.0.0/8"); add("169.254.0.0/16")
                            }
                            put("outboundTag", "direct")
                        }
                    }
                    // Hard IPv4-only: blackhole any IPv6 destination so the VK tunnel never carries v6
                    // (no tunnel-IPv6 leak on RU/direct sites). The WG tunnel is v4-only anyway.
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { add("::/0") }
                        put("outboundTag", "block")
                    }
                    // Everything else exits via the balanced WireGuard set.
                    addJsonObject {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("balancerTag", WG_BALANCER_TAG)
                    }
                }
            }
        }
        return json.encodeToString(config)
    }

    /**
     * Converts the sing-box WireGuard outbound stored in [profile].rawOutbound into an Xray
     * `wireguard` outbound (tag [WG_BASE_TAG]) so a chained proxy can dial through the VK tunnel.
     */
    /**
     * A SOCKS outbound pointing at olcRTC's local listener on [port], in Xray schema. Used as the
     * chain detour ([OLCRTC_TAG]) and, when the proxy is disabled, as the default exit ([PROXY_TAG])
     * so Chain traffic keeps riding the stealth tunnel instead of leaking out directly.
     */
    private fun olcrtcSocksOutbound(tag: String, port: Int, user: String, pass: String): JsonObject =
        buildJsonObject {
            put("tag", tag)
            put("protocol", "socks")
            putJsonObject("settings") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("address", "127.0.0.1")
                        put("port", port)
                        if (user.isNotBlank()) {
                            putJsonArray("users") {
                                addJsonObject {
                                    put("user", user)
                                    put("pass", pass)
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun buildWireguardBaseOutbound(profile: ProxyProfile, tag: String = WG_BASE_TAG): JsonObject? {
        val raw = profile.rawOutbound?.takeIf { it.isNotBlank() } ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val secret = o["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val peerPub = o["peer_public_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val server = o["server"]?.jsonPrimitive?.contentOrNull ?: "127.0.0.1"
        val port = o["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        val addresses = (o["local_address"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val mtu = o["mtu"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val keepalive = o["persistent_keepalive_interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: o["persistent_keepalive"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 25
        return buildJsonObject {
            put("tag", tag)
            put("protocol", "wireguard")
            putJsonObject("settings") {
                put("secretKey", secret)
                putJsonArray("address") { addresses.forEach { add(it) } }
                // The WG-over-VK tunnel is IPv4-only; resolve the chained proxy's server to IPv4 so it
                // doesn't dial an AAAA address with no route through the tunnel (→ connection reset).
                put("domainStrategy", "ForceIPv4")
                putJsonArray("peers") {
                    addJsonObject {
                        put("publicKey", peerPub)
                        put("endpoint", "$server:$port")
                        // IPv4 ONLY: the WG-over-VK tunnel is v4-only (client address is v4, the VPS
                        // NATs v4 only). Advertising ::/0 here let Xray route IPv6 INTO the tunnel, so a
                        // RU/"direct" site reached through VK-TURN egressed via the VPS's IPv6 — the
                        // "tunnel IPv6 leaks on RU sites under prefer_ipv4" report. Drop ::/0 so v6 is
                        // never carried; ForceIPv4 above already resolves destinations to A.
                        putJsonArray("allowedIPs") { add("0.0.0.0/0") }
                        // Keep the tunnel alive through the TURN relay / NAT (matches wg-quick clients).
                        put("keepAlive", keepalive)
                    }
                }
                if (mtu != null) put("mtu", mtu)
            }
        }
    }

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings(),
        // When set, the proxy dials through this outbound tag (e.g. the VK-TURN WireGuard base, or the
        // main proxy when this is the cascade exit); takes precedence over the olcRTC chain detour.
        detourTagOverride: String? = null,
        // Outbound tag to emit. [PROXY_TAG] for the exit (default), [PROXY_BASE_TAG] for the main hop
        // when a second/cascade proxy exits in front of it.
        tag: String = PROXY_TAG,
        // Force socket-level (dialerProxy) chaining over the detour even for non-xhttp transports — keeps
        // a vless reality/vision exit intact over the dnstt chain (see [build]'s chainViaDialerProxy).
        chainViaDialerProxy: Boolean = false,
        // Keep XTLS Vision `flow` even though this is a chained hop. Set for the cascade SOCKS loopback,
        // which hands the exit a CLEAN transparent TCP stream Vision can traverse — and the 2nd server's
        // vless inbound REQUIRES the flow it was configured with (drop it → "EOF"/reset). The generic
        // olcRTC/WG/dnstt detours still drop flow (they can't carry Vision's raw-TLS splice reliably).
        preserveFlow: Boolean = false,
        // Cascade base (xhttp main): give its xhttp transport a high-concurrency xmux so the loopback's
        // per-app-flow connections collapse onto a couple of H2 tunnels (see buildStreamSettings).
        xhttpHighConcurrency: Boolean = false,
    ) = buildJsonObject {
        val detourTag = detourTagOverride ?: if (chained) OLCRTC_TAG else null
        put("tag", tag)
        put("protocol", profile.type)

        putJsonObject("settings") {
            when (profile.type) {
                ProxyProfile.TYPE_VLESS, ProxyProfile.TYPE_VMESS -> {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", profile.uuid)
                                    if (profile.type == ProxyProfile.TYPE_VLESS) {
                                        put("encryption", "none")
                                        // XTLS Vision (xtls-rprx-vision) splices the RAW TLS connection to
                                        // ITS OWN server — it can't ride a chain. When this vless dials
                                        // through a detour (cascade exit over the main, an olcRTC/VK-TURN/
                                        // dnstt base hop), keeping the flow makes the exit hang = "no
                                        // connection" (e.g. a tcp vless-reality 2nd proxy over an xhttp
                                        // main). Drop it on chained hops; plain vless tunnels fine. The
                                        // direct (un-chained) hop keeps its flow so Vision still works there.
                                        // The cascade SOCKS loopback also keeps it (preserveFlow): the
                                        // 2nd server's vless inbound requires the Vision flow it expects.
                                        if (profile.flow.isNotBlank() && (detourTag == null || preserveFlow)) put("flow", profile.flow)
                                    } else {
                                        put("alterId", profile.alterId)
                                        put("security", profile.cipher.ifBlank { "auto" })
                                    }
                                }
                            }
                        }
                    }
                }

                ProxyProfile.TYPE_TROJAN -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            put("password", profile.password)
                        }
                    }
                }

                ProxyProfile.TYPE_SHADOWSOCKS -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            put("method", profile.method)
                            put("password", profile.password)
                        }
                    }
                }

                // Local SOCKS5 hop — currently the AmneziaWG outbound (prepareAmneziaWgProxy raises an
                // awgproxy SOCKS on 127.0.0.1 and hands it over as a type="socks" profile). Without this
                // branch the outbound had an empty `settings` (no server) on Xray, so a cascade whose
                // second proxy is xhttp (which forces the Xray core) had a dead AmneziaWG base hop.
                "socks" -> {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", profile.server)
                            put("port", profile.serverPort)
                            if (profile.password.isNotBlank()) {
                                putJsonArray("users") {
                                    addJsonObject { put("user", ""); put("pass", profile.password) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // A local SOCKS hop (AmneziaWG base) is plaintext loopback: it must NOT get TLS/stream
        // settings, TLS-fragment dialing or mux — those are for real remote proxy servers and would
        // corrupt the loopback SOCKS handshake (e.g. fragment splits the SOCKS bytes → connection
        // reset). Emit it bare, like olcrtcSocksOutbound. Only the detour (proxySettings) still applies.
        val isLocalSocks = profile.type == "socks"
        // Cascade exit chaining: the second/exit proxy reaches its server THROUGH the base proxy
        // ([PROXY_BASE_TAG]). Use transport-level chaining (sockopt.dialerProxy) instead of
        // proxy-level chaining (proxySettings). proxySettings drops the exit's OWN transport, so an
        // xhttp exit was sending raw VLESS to the server, which replied with its HTTP web-fallback
        // ("unexpected response version. Expecting 0 but actually 72" = 'H'). dialerProxy keeps the
        // exit's xhttp/ws/TLS intact and only routes its underlying socket through the base.
        //
        // The same proxySettings/xhttp breakage hits ANY detour, not just the cascade exit: a VK-TURN
        // WireGuard chain proxy ([WG_BASE_TAG]) or an olcRTC Chain proxy ([OLCRTC_TAG]) that is xhttp
        // also lost its transport under proxySettings ("vkturn cascade xhttp doesn't work"). So use
        // dialerProxy whenever the profile carries an xhttp transport over a detour, in addition to the
        // cascade-exit case. Non-xhttp profiles over WG/olcRTC keep proxySettings (unchanged, works).
        val needsDialerProxy = detourTag != null &&
            (detourTag == PROXY_BASE_TAG || profile.network == ProxyProfile.NETWORK_XHTTP || chainViaDialerProxy)
        val dialerProxyTag = if (needsDialerProxy) detourTag else null
        if (!isLocalSocks) {
            put("streamSettings", buildStreamSettings(
                profile,
                fragmentDialer = traffic.fragmentEnabled && detourTag == null,
                dialerProxyTag = dialerProxyTag,
                xhttpHighConcurrency = xhttpHighConcurrency,
            ))
        }

        if (detourTag != null && dialerProxyTag == null) {
            putJsonObject("proxySettings") { put("tag", detourTag) }
        }

        if (traffic.muxEnabled && !isLocalSocks) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", traffic.muxMaxConnections)
            }
        }
    }

    private fun buildStreamSettings(
        profile: ProxyProfile,
        fragmentDialer: Boolean = false,
        // When set, the transport's underlying socket is dialed through this outbound tag
        // (transport-level chaining that preserves THIS profile's transport, e.g. xhttp). Mutually
        // exclusive with [fragmentDialer] in practice (fragment only runs when there is no detour).
        dialerProxyTag: String? = null,
        // Cascade base over xhttp: pack MANY tunnel streams onto FEW HTTP/2 connections. The SOCKS
        // loopback opens a fresh connection to this xhttp main per app-flow; without a high xmux
        // concurrency the main spawned a new H2 connection EACH time (xmuxClients 1→18) and the server
        // dropped them past ~14 (broken pipe / stalls). An xhttp 2nd proxy never hit this because its own
        // mux collapsed everything to ~1-2 connections — this makes a non-muxable (Vision) 2nd match that.
        xhttpHighConcurrency: Boolean = false,
    ) = buildJsonObject {
        if (dialerProxyTag != null) {
            putJsonObject("sockopt") { put("dialerProxy", dialerProxyTag) }
        } else if (fragmentDialer) {
            putJsonObject("sockopt") { put("dialerProxy", "fragment") }
        }
        val network = when (profile.network) {
            ProxyProfile.NETWORK_WS -> "ws"
            ProxyProfile.NETWORK_GRPC -> "grpc"
            ProxyProfile.NETWORK_HTTP -> "http"
            ProxyProfile.NETWORK_HTTPUPGRADE -> "httpupgrade"
            ProxyProfile.NETWORK_XHTTP -> "xhttp"
            else -> "tcp"
        }
        put("network", network)

        when (profile.security) {
            ProxyProfile.SECURITY_TLS -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    put("serverName", profile.sni.ifBlank { profile.server })
                    put("allowInsecure", profile.allowInsecure)
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    if (profile.alpn.isNotEmpty()) {
                        putJsonArray("alpn") { profile.alpn.forEach { add(it) } }
                    }
                }
            }

            ProxyProfile.SECURITY_REALITY -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", profile.sni.ifBlank { profile.server })
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    put("publicKey", profile.realityPublicKey)
                    put("shortId", profile.realityShortId)
                }
            }

            else -> put("security", "none")
        }

        when (network) {
            "ws" -> putJsonObject("wsSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) putJsonObject("headers") { put("Host", profile.host) }
            }

            "grpc" -> putJsonObject("grpcSettings") {
                if (profile.path.isNotBlank()) put("serviceName", profile.path)
            }

            "xhttp" -> putJsonObject("xhttpSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) put("host", profile.host)
                put("mode", "auto")
                // Cascade base: spread the loopback's per-app-flow tunnels across a SMALL POOL of reused
                // H2 connections (4-8). Funnelling everything onto 1 connection (high maxConcurrency)
                // chokes on H2 head-of-line blocking; opening one-per-flow (no xmux) overran the server
                // past ~14 (the 18-connection broken-pipe). A 4-8 pool is under that cap yet parallel.
                if (xhttpHighConcurrency) putJsonObject("xmux") {
                    put("maxConnections", "4-8")
                    put("cMaxReuseTimes", "64-128")
                    put("cMaxLifetimeMs", 0)
                }
            }

            "httpupgrade" -> putJsonObject("httpupgradeSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) put("host", profile.host)
            }

            "http" -> putJsonObject("httpSettings") {
                if (profile.path.isNotBlank()) put("path", profile.path)
                if (profile.host.isNotBlank()) putJsonArray("host") { add(profile.host) }
            }
        }
    }
}
