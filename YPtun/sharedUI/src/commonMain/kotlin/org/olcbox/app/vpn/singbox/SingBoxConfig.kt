package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.olcbox.app.data.model.AdvancedCoreConfig
import org.olcbox.app.data.model.FakeDnsSpec
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings

/**
 * Builds a sing-box (1.11+) JSON configuration from a [ProxyProfile].
 *
 * The generated config exposes a single SOCKS5 inbound on [listenPort] (consumed by the
 * existing TUN→SOCKS bridge) and a proxy outbound for the profile. When [olcrtcChainPort]
 * is provided the proxy outbound dials through olcRTC's local SOCKS (the "chain" engine):
 * a normal VLESS connection wrapped inside the WebRTC stealth tunnel.
 */
object SingBoxConfig {

    private const val PROXY_TAG = "proxy"
    // The main (first-hop) proxy when a second/cascade proxy is present: the second exits as PROXY_TAG
    // and dials THROUGH this. With no second proxy the main itself is PROXY_TAG (the exit).
    private const val PROXY_BASE_TAG = "proxy-base"
    private const val OLCRTC_TAG = "olcrtc-out"
    private const val WG_BASE_TAG = "wireguard-base"
    private const val SOCKS_IN_TAG = "socks-in"
    private const val TUN_IN_TAG = "tun-in"
    private const val TPROXY_IN_TAG = "tproxy-in"

    // Desktop per-process split tunneling modes (mirror AndroidSplitTunnelMode values).
    const val SPLIT_TUNNEL_ALL = "all_apps"
    const val SPLIT_TUNNEL_PROXY = "proxy_selected"
    const val SPLIT_TUNNEL_BYPASS = "bypass_selected"

    /**
     * Well-known DNS-over-HTTPS/TLS endpoints that browsers & apps dial directly (often over IPv6),
     * bypassing the tunnel's resolver. Rejected by domain under strict-family / fakeip so the app falls
     * back to the system resolver (→ tunnel → fake IPv4). Matched as domain_suffix (covers subdomains
     * like chrome.cloudflare-dns.com, mozilla.cloudflare-dns.com).
     */
    private val DOH_BLOCK_SUFFIXES = listOf(
        "cloudflare-dns.com",
        "dns.google",
        "dns.google.com",
        "one.one.one.one",
        "dns.quad9.net",
        "doh.opendns.com",
        "dns.nextdns.io",
        "doh.cleanbrowsing.org",
        "dns.adguard.com",
        "dns.adguard-dns.com",
    )

    /** Raw-outbound types that do not support sing-box smux and must not get a multiplex block. */
    private val RAW_OUTBOUND_NO_MUX = setOf("wireguard", "hysteria2", "hysteria", "tuic", "endpoint", "socks", "naive")

    private val json = Json { prettyPrint = true }

    fun build(
        profile: ProxyProfile,
        listenPort: Int,
        listenHost: String = "127.0.0.1",
        socksUsername: String = "",
        socksPassword: String = "",
        dns: String = "1.1.1.1",
        olcrtcChainPort: Int? = null,
        olcrtcChainUser: String = "",
        olcrtcChainPass: String = "",
        logLevel: String = "debug",
        // On Android we bind the whole process to the upstream network (like olcRTC), so
        // sing-box must not try to detect/bind an interface itself. Desktop can enable it.
        autoDetectInterface: Boolean = false,
        routing: RoutingRules = RoutingRules(),
        traffic: TrafficSettings = TrafficSettings(),
        // VK-TURN chain: when set, [profile] is the chained proxy and this WireGuard profile is
        // added as the base outbound; the proxy dials its server THROUGH WireGuard (detour).
        wireguardBase: ProxyProfile? = null,
        // Overrides the DNS resolution strategy (e.g. "ipv4_only" for an IPv4-only WG tunnel).
        dnsStrategyOverride: String? = null,
        // Per-location advanced core options (mux / tcp_fast_open / sniff). Null = defaults.
        advanced: AdvancedCoreConfig? = null,
        // Happ-style routing profile. When set, it fully drives route.rules/rule_set (replacing the
        // toggle-based [routing] rules); geo selectors become remote `.srs` rule-sets.
        routingProfile: RoutingProfile? = null,
        singboxGeositeBase: String = "",
        singboxGeoipBase: String = "",
        // Block QUIC (UDP/443 + sniffed quic) so clients fall back to TCP. MUST be false for
        // UDP-capable tunnels (VK-TURN / WireGuard / AmneziaWG) which carry QUIC natively — blocking
        // it there breaks those engines and is never wanted.
        blockQuic: Boolean = true,
        // Enforce the DNS family by force-resolving every sniffed domain locally and rejecting the
        // opposite family. Prevents a remote TCP proxy from picking AAAA (IPv6 leak). MUST be false
        // for a full UDP tunnel (AmneziaWG/WireGuard): it carries every family itself, and the forced
        // local resolve there routes DNS back through the tunnel proxy and breaks browsing
        // ("err connection closed"). Domains then pass through to the tunnel to resolve, as they did
        // before the routing rework. IP-based rules (geoip/ip_cidr) still trigger a resolve regardless.
        forceFamilyResolve: Boolean = true,
        // Whether the sniffed-domain `resolve` action may run. It resolves app destinations via the
        // `remote` DNS server, whose detour is the proxy — i.e. a DNS lookup THROUGH the tunnel. On a
        // very slow tunnel (dnstt: DNS TXT, tiny MTU) that adds a tunnel round-trip to EVERY connection
        // and stalls browsing, so the dnstt-proxy path passes false: domains then go straight to the
        // proxy (resolved server-side). Costs only IP-based geo rules (geoip:ru → direct); domain/geosite
        // rules still match without a local IP, and the family is still pinned by the bridge's v6 drop.
        allowLocalResolve: Boolean = true,
        // VK-TURN / dnstt: the base tunnel (WireGuard / dnstt SOCKS) is the MANDATORY transport, so a
        // routing rule's `direct` bucket must NOT leak to the real network — it should still exit through
        // the base tunnel. When true, the `direct` outbound dials through the base detour (WG / olcRTC),
        // so routing only chooses base-tunnel-exit (direct) vs second-proxy-exit (proxy); the tunnel is
        // never bypassed.
        directViaBase: Boolean = false,
        // Sniff the SNI/Host and OVERRIDE the connection destination with it before routing, then
        // resolve it to [dnsStrategyOverride]/[TrafficSettings.domainStrategy]. This is what lets a
        // v4-only full tunnel (AmneziaWG) stay IPv4-only WITHOUT rejecting traffic: an app's own-DoH
        // IPv6 literal (e.g. Chrome → `[2a00:…]:443`) is replaced by its domain and re-resolved to
        // IPv4, so it rides the tunnel as IPv4 instead of leaking IPv6 or being killed by the
        // `::/0 reject` backstop (which then only catches rare un-sniffable raw IPv6). Implemented via
        // sing-box's (1.12, deprecated-but-functional) inbound `sniff_override_destination`.
        sniffOverrideDestination: Boolean = false,
        // Optional SECOND proxy chained ON TOP of [profile] (the main). When present, traffic exits via
        // this proxy (tag [PROXY_TAG]) which dials THROUGH the main (tag [PROXY_BASE_TAG]); the main in
        // turn dials through olcRTC/WG. So: client → [olcRTC/WG] → main → second → internet. Null = the
        // main proxy is the single exit (PROXY_TAG), exactly as before.
        secondProfile: ProxyProfile? = null,
        // Per-location FakeDNS translated from an imported Xray config (fakeip pool + dns.hosts
        // blackholes). When non-null, FakeDNS is enabled on sing-box natively with these ranges and the
        // blackhole domains become `domain_regex → reject` route rules — so FakeDNS works on sing-box
        // too, not only xray-core. Overrides the (now per-config) [TrafficSettings.fakeDnsEnabled].
        fakeDnsSpec: FakeDnsSpec? = null,
        // Desktop only: sing-box owns the system TUN itself (wintun on Windows) with auto_route,
        // instead of an external tun2socks. Required for per-process split tunneling — only the
        // TUN owner can attribute connections to processes. Needs admin/root.
        tunMode: Boolean = false,
        // Per-process split tunneling (desktop analog of Android's per-app VPN): exe names matched
        // with sing-box `process_name` rules. Only effective with [tunMode].
        // "proxy_selected" → ONLY listed processes go through the proxy (everything else direct);
        // "bypass_selected" → listed processes go direct (everything else through the proxy).
        splitTunnelMode: String = SPLIT_TUNNEL_ALL,
        splitTunnelProcesses: List<String> = emptyList(),
        // Desktop only, [tunMode] only: upstream server IPs carved OUT of the TUN's auto_route, so an
        // engine's own traffic never loops through the tunnel it provides. Android does this with
        // VpnService.protect(); desktop has no protect, and auto_detect_interface only binds sing-box's
        // OWN dials — a sibling core inside the same process (awgproxy's WireGuard endpoint, freeturn's
        // relay) is not covered, so its UDP went into the TUN and the tunnel deadlocked. The external
        // tun2socks path solves the same problem with host routes (WindowsTunController).
        tunExcludeAddresses: List<String> = emptyList(),
        // Desktop tun2socks path: sing-box runs as a plain SOCKS server (no [tunMode]) behind an
        // external tun2socks. The OS routes ALL DNS into the TUN, so the app's UDP DNS queries arrive
        // at the SOCKS inbound. Without hijack-dns they'd be relayed as raw UDP/53 to the proxy and
        // die on TCP-only transports → total DNS outage. Hijacking makes sing-box answer them with its
        // own DNS servers (resolved over the proxy), exactly like the in-core TUN does. Harmless when
        // [tunMode] already enables hijack.
        hijackDns: Boolean = false,
        // Optional file for sing-box's own debug logs. The core is a c-shared lib inside the JVM, so
        // its default stderr output is lost; pointing log.output at a file lets the desktop surface
        // real route/DNS diagnostics. Null keeps the default (stderr).
        logFilePath: String? = null,
        // Resolve the remote DNS over DoH (DNS-over-HTTPS) instead of plain UDP. Many proxy servers
        // (vless with vision/reality or HTTP-based transports) carry TCP fine but drop or stall UDP,
        // so a UDP DNS query through the proxy times out → "strategy rejected" → nothing resolves.
        // DoH rides the proven TCP path AND multiplexes over HTTP/2, so the dozens of concurrent
        // lookups a page triggers don't head-of-line-block behind one another (plain DoT serialized
        // them → 5-16s stalls). The app's own UDP (QUIC) is already blocked, so DNS is the only thing
        // that needed UDP here.
        remoteDnsOverHttps: Boolean = false,
        // Force FakeDNS on (desktop): hand the app an instant synthetic IP and let the exit server
        // resolve the real domain from the sniffed SNI. Eliminates the per-domain DNS round-trip
        // through the proxy entirely — the UDP-over-vless DNS path stalled 5-6s on desktop, which is
        // why only Telegram (connects by IP, no DNS) worked.
        forceFakeDns: Boolean = false,
        // Desktop: use a "mixed" inbound (SOCKS + HTTP) so the OS system-proxy (HTTP) can use it.
        mixedInbound: Boolean = false,
        // Desktop: a routing rule's app list holds EXE names, matched with sing-box `process_name`.
        // On Android the same field holds package names and matches with `package_name`, which
        // resolves a UID and therefore can never match anything on a PC.
        matchAppsByProcess: Boolean = false,
        // Transparent-proxy mode: when set, a `tproxy` inbound (TCP+UDP) is added on [listenHost]:this,
        // so a rooted device / router can redirect traffic through the core with no per-app proxy. The
        // SOCKS inbound is still emitted for coexistence. Requires root (IP_TRANSPARENT) to bind.
        tproxyPort: Int? = null,
        // Resolve the `remote` DNS over TCP instead of UDP when it's a plain UDP resolver (bare IP /
        // udp://). For a full UDP tunnel exposed as a local SOCKS (AmneziaWG via awgproxy), UDP DNS
        // rides the SOCKS UDP-ASSOCIATE path, which is far less reliable than a plain TCP CONNECT; a
        // flaky associate silently kills name resolution so the tunnel "only works with a 2nd proxy"
        // (whose own protocol carries DNS). Forcing DNS over TCP makes it ride the proven CONNECT path.
        // No-op for DoH/DoT/DoQ resolvers (already TCP/own transport) — only bare-IP/udp is rewritten.
        preferTcpRemoteDns: Boolean = false,
        // Absolute path of sing-box's `experimental.cache_file` (bbolt db). Only used when FakeDNS is
        // active, and ONLY to persist the fakeip domain↔synthetic-IP table across restarts.
        //
        // WHY THIS MATTERS: without a cache file sing-box keeps the fakeip table in memory only, so
        // every reconnect restarts the pool at 198.18.0.1 and hands the SAME synthetic IPs out to
        // WHATEVER domain happens to be looked up first this session. Apps that cache DNS answers for
        // a long time (anything on OkHttp/JVM — ChatGPT, banking apps — unlike browsers, which
        // re-resolve) then dial a fake IP they learned BEFORE the reconnect, sing-box maps it to a
        // DIFFERENT domain, and the TLS handshake completes against the wrong host: the app reports
        // "this network uses an untrusted SSL certificate". Persisting the table keeps a fake IP
        // bound to its domain forever, so a stale app-side cache entry still resolves correctly.
        // Null (or FakeDNS off) → no `experimental` block at all, i.e. unchanged behaviour.
        cacheFilePath: String? = null,
    ): String {
        // Effective DNS/resolve strategy (per-tunnel override → global traffic setting). Hoisted so
        // both the inbound sniff-override and the route resolve/family rules use the same value.
        val effectiveStrategy = dnsStrategyOverride ?: traffic.domainStrategy
        // Base tunnel exit tag (WG for VK-TURN, olcRTC SOCKS for Chain/dnstt) — the transport that
        // [directViaBase] traffic must ride instead of the real network.
        val baseExitTag = when {
            wireguardBase != null -> WG_BASE_TAG
            olcrtcChainPort != null -> OLCRTC_TAG
            else -> null
        }
        val directDialsBase = directViaBase && baseExitTag != null
        // sing-box 1.14 refuses a `detour` on the `direct` outbound ("`detour` is not supported in
        // direct context"), which is how the never-bypass tunnels used to keep their `direct` bucket
        // inside the tunnel. Same effect, legal shape: point that bucket at the base tunnel BY TAG.
        val directTag = if (directDialsBase) baseExitTag!! else "direct"
        // FakeDNS is on when either the (legacy global) traffic toggle is set OR this location carries a
        // translated spec. The pool ranges come from the spec when present, else the defaults.
        val fakeEnabled = traffic.fakeDnsEnabled || fakeDnsSpec != null || forceFakeDns
        val fake4Range = fakeDnsSpec?.inet4Range?.takeIf { it.isNotBlank() } ?: "198.18.0.0/15"
        val fake6Range = fakeDnsSpec?.inet6Range?.takeIf { it.isNotBlank() } ?: "fc00::/18"
        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", true)
                if (!logFilePath.isNullOrBlank()) put("output", logFilePath)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    // App traffic resolves through the proxy (no DNS leak). On desktop, ride DoH (TCP +
                    // HTTP/2 multiplexing) so resolution survives proxy servers that don't carry UDP and
                    // doesn't serialize concurrent lookups (see [remoteDnsOverHttps]).
                    val remoteDnsAddress =
                        if (remoteDnsOverHttps && !traffic.remoteDns.contains("://")) "https://${traffic.remoteDns}/dns-query"
                        else traffic.remoteDns
                    // Compose both: DoH-ify (desktop) THEN rewrite a bare-IP/udp resolver to TCP
                    // (Beta). maybeTcpDns is a no-op for a `https://…` DoH address, so order is safe.
                    // Under a strict family the per-server `strategy` pin used to stop the wasted
                    // AAAA round-trip; 1.14 dropped that field from the typed servers, and the global
                    // `dns.strategy` below (same value) is what pins it now.
                    addDnsServer(
                        tag = "remote",
                        address = maybeTcpDns(remoteDnsAddress, preferTcpRemoteDns),
                        detour = PROXY_TAG,
                    )
                    // Optional second remote resolver (also via the proxy) — a fallback for "remote".
                    if (traffic.remoteDns2.isNotBlank()) {
                        addDnsServer(
                            tag = "remote2",
                            address = maybeTcpDns(traffic.remoteDns2, preferTcpRemoteDns),
                            detour = PROXY_TAG,
                        )
                    }
                    // Bootstrap: resolve the proxy server's own domain directly.
                    addDnsServer(tag = "direct", address = traffic.directDns, detour = directTag)
                    // FakeDNS equivalent: hand out synthetic IPs so apps never see the real address;
                    // the sniffed domain is resolved behind the proxy. In 1.14 the pool ranges live on
                    // the server itself — the top-level `dns.fakeip` block is gone with the legacy format.
                    if (fakeEnabled) {
                        addDnsServer(
                            tag = "fake",
                            address = "fakeip",
                            fake4Range = fake4Range,
                            fake6Range = fake6Range,
                        )
                    }
                }
                putJsonArray("rules") {
                    // Route BOTH A and AAAA to the fake server. Faking AAAA even under ipv4_only is
                    // deliberate: it hands the app a fake IPv6 (fc00::/18) instead of letting it learn the
                    // server's REAL IPv6, which the strict `::/0 reject` would then kill ("ERR_CONNECTION"
                    // on Google over v6). sing-box restores the fake v6 to the domain before the IP rules,
                    // so it's tunnelled as IPv4 — only genuine real IPv6 hits the reject. (When fakeip is
                    // off this block doesn't run, so non-fakeip ipv4_only behaviour is unchanged.)
                    if (fakeEnabled) {
                        addJsonObject {
                            putJsonArray("query_type") {
                                add("A")
                                add("AAAA")
                            }
                            put("server", "fake")
                        }
                    }
                }
                put("final", "remote")
                // ipv4_only override (VK-TURN): the WireGuard tunnel is IPv4-only, so resolving
                // AAAA would make dual-stack sites attempt IPv6 → "no route to host". Forcing A-only
                // keeps all traffic on IPv4 through the tunnel.
                put("strategy", dnsStrategyOverride ?: traffic.domainStrategy)
            }

            putJsonArray("inbounds") {
                if (tunMode) {
                    // sing-box-owned TUN: creates the adapter (wintun) and installs the default
                    // routes itself; auto_detect_interface keeps its own upstream off the tunnel.
                    addJsonObject {
                        put("type", "tun")
                        put("tag", TUN_IN_TAG)
                        putJsonArray("address") {
                            add("172.19.0.1/28")
                            add("fdfe:dcba:9876::1/126")
                        }
                        // Match Hiddify's proven Windows defaults. The "mixed" stack uses the OS native
                        // TCP stack (gVisor only for UDP) — pure gVisor TCP in userspace was dropping
                        // part of the download direction (TLS ServerHello never arrived → Firefox
                        // PR_END_OF_FILE_ERROR on many sites). MTU 9000 cuts per-packet overhead app↔tun.
                        put("mtu", 9000)
                        put("auto_route", true)
                        put("strict_route", false)
                        put("stack", "mixed")
                        // Host routes around the tunnel for the engines' own upstreams (see the param).
                        val excluded = tunExcludeAddresses
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
                            .distinct()
                        if (excluded.isNotEmpty()) {
                            putJsonArray("route_exclude_address") { excluded.forEach { add(it) } }
                        }
                    }
                }
                addJsonObject {
                    // Desktop: "mixed" speaks BOTH SOCKS and HTTP on one port (Windows system HTTP-proxy
                    // points here; SOCKS5 clients like tun2socks/hev still work). Android passes
                    // mixedInbound=false → plain "socks" (HTTP is provided by the HttpProxyBridge in
                    // Proxy mode), so TUN mode keeps a purely internal SOCKS bridge with no HTTP listener.
                    put("type", if (mixedInbound) "mixed" else "socks")
                    put("tag", SOCKS_IN_TAG)
                    put("listen", listenHost)
                    put("listen_port", listenPort)
                    if (socksUsername.isNotBlank()) {
                        putJsonArray("users") {
                            addJsonObject {
                                put("username", socksUsername)
                                put("password", socksPassword)
                            }
                        }
                    }
                    // NOTE: legacy inbound fields sniff/sniff_override_destination/domain_strategy were
                    // REMOVED in sing-box 1.13.0 (they crashed decode: "legacy inbound fields … removed").
                    // Sniffing + the family override are now done via route-rule actions below
                    // ({"action":"sniff"} + {"action":"resolve"}), keyed off [sniffOverrideDestination].
                }
                // Transparent-proxy inbound (root-only): TCP+UDP redirected traffic enters here. Sniff
                // is enabled so routing/DNS rules still match on domain, exactly like the socks inbound.
                if (tproxyPort != null) {
                    addJsonObject {
                        put("type", "tproxy")
                        put("tag", TPROXY_IN_TAG)
                        put("listen", listenHost)
                        put("listen_port", tproxyPort)
                        // network omitted → both TCP and UDP redirected traffic is accepted.
                        // Sniff + family override handled by the route-rule actions below (the legacy
                        // inbound sniff/sniff_override/domain_strategy fields were removed in 1.13.0).
                    }
                }
            }

            // WireGuard moved from an `outbound` (removed in sing-box 1.13.0) to a top-level
            // `endpoints` entry, referenced by tag. Build them here so the outbounds below can detour
            // to them and the endpoints array is emitted as a sibling of `outbounds`.
            val wgBaseEndpoint = wireguardBase?.let {
                buildWireguardEndpoint(it, WG_BASE_TAG, autoDetectInterface)
            }
            val mainIsWireguard = profile.type == "wireguard"
            val wgExitEndpoint =
                if (mainIsWireguard) buildWireguardEndpoint(profile, PROXY_TAG, autoDetectInterface) else null

            putJsonArray("outbounds") {
                // The main proxy dials through the WG base (VK-TURN) when present, else olcRTC (Chain).
                val baseDetour = if (wgBaseEndpoint != null) WG_BASE_TAG else null
                val second = secondProfile?.takeIf { it.isComplete() }
                if (second != null) {
                    // Cascade: traffic exits via the SECOND proxy (tag PROXY_TAG), which dials THROUGH the
                    // main (PROXY_BASE_TAG); the main dials through olcRTC/WG. Keeping the second as
                    // PROXY_TAG means routing / DNS / route.final (all → PROXY_TAG) transparently use the
                    // real exit, so nothing else needs to know about the extra hop.
                    add(
                        buildProxyOutbound(
                            profile,
                            chained = olcrtcChainPort != null,
                            traffic = traffic,
                            detourTagOverride = baseDetour,
                            tag = PROXY_BASE_TAG
                        )
                    )
                    add(
                        buildProxyOutbound(
                            second,
                            chained = false,
                            traffic = traffic,
                            detourTagOverride = PROXY_BASE_TAG,
                            advanced = advanced,
                            tag = PROXY_TAG
                        )
                    )
                } else if (!mainIsWireguard) {
                    // Single hop: the main proxy IS the exit (tag PROXY_TAG), dialing through olcRTC/WG.
                    add(
                        buildProxyOutbound(
                            profile,
                            chained = olcrtcChainPort != null,
                            traffic = traffic,
                            detourTagOverride = baseDetour,
                            advanced = advanced,
                            tag = PROXY_TAG
                        )
                    )
                }
                // else: the main IS WireGuard — its exit is the `endpoints` entry tagged PROXY_TAG.
                // olcRTC chain detour: the main proxy dials through this local SOCKS.
                if (olcrtcChainPort != null) {
                    add(olcrtcSocksOutbound(OLCRTC_TAG, olcrtcChainPort, olcrtcChainUser, olcrtcChainPass))
                }
                addJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                    // (VK-TURN / dnstt keep `direct` traffic inside the tunnel by routing it to
                    // [directTag] — this outbound stays a plain, detour-free direct.)
                    // IPv6-leak guard for HYBRID modes (prefer_ipv4/prefer_ipv6): the direct/bypass path
                    // (domain:ru → direct) would otherwise dial the user's REAL IPv6 for dual-stack sites,
                    // exposing it on a leak check ("domain:ru goes direct only over IPv4, IPv6 leaks").
                    // Force the direct outbound to IPv4 so bypass traffic NEVER egresses over real IPv6;
                    // sing-box re-resolves the sniffed domain to A here, so even an IPv6-literal direct
                    // connection is dialed as IPv4. Proxied traffic is untouched (still dual-stack via the
                    // proxy's own IP — no user-IP leak there). ipv4_only/ipv6_only already pin a family
                    // globally (route reject), so only the prefer_* hybrids need this.
                    // A bare dial-field `domain_strategy` is the legacy form (deprecated since 1.12);
                    // 1.14 wants it inside `domain_resolver`.
                    if (effectiveStrategy == "prefer_ipv4" || effectiveStrategy == "prefer_ipv6") {
                        putJsonObject("domain_resolver") {
                            put("server", "direct")
                            put("strategy", "ipv4_only")
                        }
                    }
                }
            }

            // WireGuard endpoints (sing-box 1.13 replacement for the removed wireguard outbound).
            val wgEndpoints = listOfNotNull(wgExitEndpoint, wgBaseEndpoint)
            if (wgEndpoints.isNotEmpty()) {
                putJsonArray("endpoints") { wgEndpoints.forEach { add(it) } }
            }

            // A sing-box JSON subscription carries its OWN `route` (normalized onto our tags at import
            // time, see LocationsDatasource.normalizeSingBoxRoute). It takes PRECEDENCE over the app's
            // routing profile and toggles — the same rule the Xray core applies to a verbatim
            // rawXrayConfig. The safety rules around it (sniff, DNS hijack, QUIC/DoH blocks, fakeip,
            // family enforcement) still run: they are what makes the tunnel itself work.
            val embeddedRoute = profile.rawSingBoxRoute
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val embeddedRules = embeddedRoute?.get("rules")
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val hasEmbeddedRoute = embeddedRules.isNotEmpty()
            // Its geo/IP rules only match a connection that already carries an IP, so they need the
            // sniffed domain resolved first — exactly like the app's own profile rules.
            val embeddedUsesIpRules = embeddedRules.any {
                it["ip_cidr"] != null || it["rule_set"] != null || it["ip_is_private"] != null
            }

            putJsonObject("route") {
                put(
                    "final",
                    when {
                        hasEmbeddedRoute ->
                            embeddedRoute?.get("final")?.jsonPrimitive?.contentOrNull ?: PROXY_TAG
                        routingProfile != null -> SingBoxRouting.finalOutbound(routingProfile, directTag)
                        else -> PROXY_TAG
                    }
                )
                put("auto_detect_interface", autoDetectInterface)
                // Bootstrap for outbounds dialling a DOMAIN (the proxy server's own hostname): resolve
                // it with the direct resolver, never through the tunnel we haven't built yet. Replaces
                // the legacy `{"outbound":"any","server":"direct"}` DNS rule, deprecated since 1.12.
                putJsonObject("default_domain_resolver") { put("server", "direct") }

                putJsonArray("rules") {
                    // Expert per-core overrides (sing-box): explicit sniff/resolve/strategy control.
                    val sbExpert = routingProfile?.expertEnabled == true
                    val sbExpertStrategy = routingProfile
                        ?.takeIf { it.expertEnabled }?.singboxDomainStrategy?.takeIf { it.isNotBlank() }
                    // Sniff destination domain so domain rules match (advanced or expert can disable it).
                    // A full UDP tunnel ([sniffOverrideDestination]) ALWAYS sniffs — the old code forced
                    // sniff on the inbound there regardless of the advanced/expert toggle, and the resolve
                    // action below needs the sniffed domain to override an IP-literal destination.
                    if (sniffOverrideDestination ||
                        (advanced?.sniff != false && (!sbExpert || routingProfile!!.singboxSniff))
                    ) {
                        addJsonObject { put("action", "sniff") }
                    }
                    if (tunMode || hijackDns) {
                        // System DNS queries arriving via the TUN are answered by sing-box itself.
                        addJsonObject {
                            putJsonArray("protocol") { add("dns") }
                            put("action", "hijack-dns")
                        }
                    }
                    // Per-process split tunneling (desktop): FIRST so a per-app decision wins over
                    // every later domain/geo rule.
                    val splitProcesses = splitTunnelProcesses.filter { it.isNotBlank() }
                    if (tunMode && splitProcesses.isNotEmpty()) {
                        when (splitTunnelMode) {
                            SPLIT_TUNNEL_BYPASS -> addJsonObject {
                                putJsonArray("process_name") { splitProcesses.forEach { add(it) } }
                                put("outbound", directTag)
                            }
                            SPLIT_TUNNEL_PROXY -> addJsonObject {
                                putJsonArray("process_name") { splitProcesses.forEach { add(it) } }
                                put("invert", true)
                                put("outbound", directTag)
                            }
                        }
                    }
                    // NOTE: the family override for full UDP tunnels (the old inbound
                    // sniff_override_destination + domain_strategy) is already covered by the `resolve`
                    // route action further down (gated on forceFamily / usesIpRules / …), so no extra
                    // resolve is emitted here — that would double-resolve and reorder the rules.
                    // Block QUIC (HTTP/3) so clients fall back to TCP/HTTP2 through the proxy. A
                    // TCP-only transport (xhttp / reality / ws) can't carry UDP, so QUIC just dies
                    // with ERR_QUIC_PROTOCOL (Telemost, Wildberries, Google, …). Rejecting it forces
                    // the working TCP path. Matches both the sniffed protocol and raw UDP/443.
                    if (blockQuic) {
                        addJsonObject {
                            putJsonArray("protocol") { add("quic") }
                            put("action", "reject")
                        }
                        addJsonObject {
                            put("network", "udp")
                            putJsonArray("port") { add(443) }
                            put("action", "reject")
                        }
                    }
                    // App-level DoH/DoT bypass: browsers (Chrome, Firefox…) ship hardcoded DNS-over-HTTPS
                    // endpoints, often reached over IPv6. Under strict "IPv4 only" the `::/0 reject` below
                    // would kill those connections and leave the app with NO DNS (google.com → connection
                    // closed). Reject the DoH endpoints BY DOMAIN instead, so the app cleanly falls back to
                    // the system resolver — which rides the tunnel and (with fakeip) returns a fake IPv4.
                    // Net effect: DNS works, stays on IPv4, and there is no v6 leak. Enabled whenever a
                    // strict family is enforced or fakeip is active (both want all DNS via the system path).
                    val blockAppDoh = fakeEnabled ||
                        effectiveStrategy == "ipv4_only" || effectiveStrategy == "ipv6_only"
                    if (blockAppDoh) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { DOH_BLOCK_SUFFIXES.forEach { add(it) } }
                            put("action", "reject")
                        }
                        // NOTE: we deliberately do NOT reject the DoH resolver IPs (1.1.1.1, 8.8.8.8…) by
                        // ip_cidr here. Many users set Android "Private DNS" to one of those IPs (DoT on
                        // :853), so the SYSTEM resolver itself dials them — an IP reject would kill ALL DNS
                        // (No address associated with hostname) and break geoip routing (Russia-direct).
                        // The domain block above is enough for browsers that resolve the DoH host by name.
                        // Firefox canary domain: returning NXDOMAIN/reject here disables its auto-DoH.
                        addJsonObject {
                            putJsonArray("domain") { add("use-application-dns.net") }
                            put("action", "reject")
                        }
                    }
                    // FakeDNS blackholes: the imported Xray config's dns.hosts entries that mapped a
                    // domain to 0.0.0.0 (e.g. regexp:(^|\.)gov\.ru$ → block). Reproduced as a sniffed
                    // domain_regex reject so the same domains die on sing-box too. Placed early (after
                    // QUIC) so a blocked domain dies before any proxy/direct rule can carry it.
                    if (fakeDnsSpec != null && fakeDnsSpec.blockRegex.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_regex") { fakeDnsSpec.blockRegex.forEach { add(it) } }
                            put("action", "reject")
                        }
                    }
                    // Routing profile and the advanced toggles are COMBINED (not either/or): the
                    // profile's buckets run alongside the user's verbatim rules and the
                    // bypassRussia/blockAds/block-direct toggles.
                    // A FakeDNS address is SYNTHETIC — it means "the domain I handed this app" and can
                    // never be dialled for real, so it must reach the proxy no matter what follows.
                    // This has to come BEFORE the private/LAN rule: the fake IPv6 pool (fc00::/18) sits
                    // inside fc00::/7, which `ip_is_private` matches, so every fake-v6 connection was
                    // classified as LAN and sent DIRECT. It then left the tunnel unprotected, and what
                    // the user saw was the ISP's interception of the real destination — "эта сеть
                    // использует недоверенный SSL-сертификат" in apps that check. (Normally the sniffed
                    // SNI replaces the fake address before routing; when sniffing can't see it — ECH,
                    // or anything that isn't TLS/HTTP — the fake address is all the router has.)
                    if (fakeEnabled) {
                        addJsonObject {
                            putJsonArray("ip_cidr") {
                                add(fake4Range)
                                add(fake6Range)
                            }
                            put("outbound", PROXY_TAG)
                        }
                    }
                    // Private/LAN always direct (Happ profiles assume it; bypassLan toggle wants it).
                    if (routingProfile != null || routing.bypassLan) {
                        addJsonObject {
                            put("ip_is_private", true)
                            put("outbound", directTag)
                        }
                    }
                    // sing-box 1.11+: ip_cidr/geoip rules only match a connection that already carries
                    // an IP. A sniffed domain connection has none, so `geoip:ru → direct` (profile or
                    // the bypassRussia toggle) is silently skipped and RU sites wrongly use the proxy
                    // IP. Resolve the sniffed domain to an IP first so IP rules can match. ALSO resolve
                    // unconditionally for ipv4_only/ipv6_only: otherwise a plain proxy never resolves
                    // locally and the REMOTE side picks the family (AAAA) → IPv6 leaks past the chosen
                    // strategy (2ip.io shows IPv6). Resolving here with the strategy forces the family.
                    // Expert mode can also force resolve (e.g. for geoip rules) and override the strategy.
                    val expertStrategy = sbExpertStrategy ?: effectiveStrategy
                    // Family enforcement is opt-out for full UDP tunnels (see [forceFamilyResolve]).
                    val forceFamily = forceFamilyResolve &&
                        (expertStrategy == "ipv4_only" || expertStrategy == "ipv6_only")
                    // v2rayNG-style manual rules that use IP/geoip selectors also need the sniffed
                    // domain resolved first, or `geoip:ru → direct` silently skips domain connections.
                    val manualRulesUseIp = routing.rules.any { it.enabled && it.ip.isNotEmpty() }
                    if (allowLocalResolve &&
                        (routingProfile?.usesIpRules() == true || routing.bypassRussia || forceFamily ||
                            manualRulesUseIp || embeddedUsesIpRules ||
                            (sbExpert && routingProfile!!.singboxResolve))
                    ) {
                        addJsonObject {
                            put("action", "resolve")
                            put("strategy", expertStrategy)
                        }
                    }
                    // Domain-strategy enforcement: AFTER resolve, reject the opposite IP family so
                    // ipv4_only / ipv6_only truly forces ALL traffic (incl. apps/browsers using their
                    // own DoH DNS that returns the other family, and raw IP-literal connections) onto
                    // the chosen one. Placed after `resolve` so freshly-resolved domains are caught
                    // too. Skipped for full UDP tunnels (forceFamily=false) and prefer_* (both families).
                    //
                    // method=drop (NOT the default RST): under strict IPv4 a browser that opens google
                    // (or its own DoH endpoint) over real IPv6 must fail SOFTLY so Happy-Eyeballs falls
                    // back to IPv4. A hard RST ("reject" default) instead surfaced as ERR_CONNECTION_RESET
                    // on google.com (the app "jumped to DoH over IPv6" and got reset). Dropping silently
                    // makes the v6 attempt time out and the app retries on IPv4 — no leak, no reset.
                    if (forceFamily) {
                        when (expertStrategy) {
                            "ipv4_only" -> addJsonObject {
                                putJsonArray("ip_cidr") { add("::/0") }
                                put("action", "reject")
                                put("method", "drop")
                            }
                            "ipv6_only" -> addJsonObject {
                                putJsonArray("ip_cidr") { add("0.0.0.0/0") }
                                put("action", "reject")
                                put("method", "drop")
                            }
                        }
                    }
                    // The JSON subscription's own routing REPLACES the app's rules (profile, toggles,
                    // manual and verbatim) — that's what "the config's routing comes first" means.
                    if (hasEmbeddedRoute) {
                        // Its `direct` bucket must ride the base tunnel too when the tunnel is
                        // mandatory — the detoured `direct` outbound that used to do this is illegal
                        // on 1.14, so retag here exactly like the app's own rules above.
                        embeddedRules.forEach { rule ->
                            add(
                                if (directTag != "direct" &&
                                    rule["outbound"]?.jsonPrimitive?.contentOrNull == "direct"
                                ) {
                                    JsonObject(rule + ("outbound" to JsonPrimitive(directTag)))
                                } else rule
                            )
                        }
                    } else {
                    // Advanced verbatim user rules (highest precedence).
                    parseJsonArray(routing.customRulesJson).forEach { add(it) }
                    // Structured v2rayNG-style rules (in user-defined order, after verbatim JSON).
                    SingBoxRouting.manualRules(routing.rules, matchAppsByProcess, directTag).forEach { add(it) }
                    // Blocking toggles first so ads/blocked domains die even if a profile bucket would proxy them.
                    if (routing.blockDomains.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { routing.blockDomains.forEach { add(it) } }
                            put("action", "reject")
                        }
                    }
                    if (routing.blockAds) {
                        // Exempt core Google infrastructure BEFORE the ad reject: the SagerNet
                        // category-ads-all rule-set is aggressive and otherwise takes down google.com
                        // (and its essential subresources). These are NOT ad-serving domains — the real
                        // ad domains (googlesyndication/doubleclick/googleadservices/…) live elsewhere and
                        // stay blocked. First-match-wins, so this must precede the reject.
                        addJsonObject {
                            putJsonArray("domain_suffix") {
                                add("google.com"); add("gstatic.com"); add("googleapis.com")
                            }
                            put("outbound", PROXY_TAG)
                        }
                        addJsonObject {
                            put("rule_set", "geosite-ads")
                            put("action", "reject")
                        }
                    }
                    // The selected routing profile's own buckets (ordered by its routeOrder). In the
                    // hybrid IPv6 modes, also reject IPv6 to the direct bucket so geosite:ru/domain:ru
                    // sites never egress over the user's real IPv6 (proxied traffic keeps dual-stack).
                    if (routingProfile != null) {
                        val hideDirectV6 = effectiveStrategy == "prefer_ipv4" || effectiveStrategy == "prefer_ipv6"
                        SingBoxRouting.rules(routingProfile, hideDirectIpv6 = hideDirectV6, directTag = directTag)
                            .forEach { add(it) }
                    }
                    // Direct conveniences last (a profile proxy rule above still wins on first match).
                    if (routing.directDomains.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { routing.directDomains.forEach { add(it) } }
                            put("outbound", directTag)
                        }
                    }
                    if (routing.bypassRussia) {
                        addJsonObject {
                            putJsonArray("rule_set") { add("geoip-ru"); add("geosite-ru") }
                            put("outbound", directTag)
                        }
                    }
                    }
                }

                // rule_set definitions, merged from the profile + the toggles, de-duplicated by tag
                // (a profile `geoip:ru` and the bypassRussia toggle both want a `geoip-ru` set, and a
                // duplicate tag is a hard config error in sing-box).
                val mergedRuleSets = buildList {
                    // Rule-sets the embedded routing references (remote .srs) must be declared too.
                    embeddedRoute?.get("rule_set")
                        ?.let { runCatching { it.jsonArray }.getOrNull() }
                        ?.forEach { (it as? JsonObject)?.let(::add) }
                    // With embedded routing in charge, none of the app's rules are emitted — declaring
                    // their rule-sets would be dead weight, and a same-tag set (geosite-ru!) would
                    // override the one the config actually asked for.
                    if (routingProfile != null && !hasEmbeddedRoute) {
                        SingBoxRouting.ruleSets(routingProfile, singboxGeositeBase, singboxGeoipBase)
                            .forEach { (it as? JsonObject)?.let(::add) }
                    }
                    if (!hasEmbeddedRoute) {
                    parseJsonArray(routing.customRuleSetsJson).forEach { (it as? JsonObject)?.let(::add) }
                    // Geo rule-sets referenced by the structured v2rayNG rules.
                    SingBoxRouting.manualRuleSets(routing.rules, singboxGeositeBase, singboxGeoipBase)
                        .forEach { (it as? JsonObject)?.let(::add) }
                    if (routing.blockAds) {
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geosite-ads")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs")
                            put("download_detour", SingBoxRouting.RULE_SET_DOWNLOAD_TAG)
                        })
                    }
                    if (routing.bypassRussia) {
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geoip-ru")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs")
                            put("download_detour", SingBoxRouting.RULE_SET_DOWNLOAD_TAG)
                        })
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geosite-ru")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs")
                            put("download_detour", SingBoxRouting.RULE_SET_DOWNLOAD_TAG)
                        })
                    }
                    }
                }.associateBy { it["tag"]?.jsonPrimitive?.contentOrNull ?: it.toString() }.values
                if (mergedRuleSets.isNotEmpty()) {
                    putJsonArray("rule_set") { mergedRuleSets.forEach { add(it) } }
                }
            }

            // The cache file earns its place twice over:
            //  - it persists FETCHED RULE-SETS. sing-box's RemoteRuleSet.StartContext only skips the
            //    initial download when the cache has the set, and a failed initial download aborts
            //    THE WHOLE CORE ("initial rule-set: <tag>"). With the cache, routing survives a start
            //    with no working network instead of taking the connection down with it.
            //  - with FakeDNS on it persists the fakeip table, so a synthetic IP keeps meaning the
            //    same domain across reconnects (see [cacheFilePath]).
            // Nothing else is cached (no selector/mode/RDRC state), so behaviour is otherwise identical.
            if (!cacheFilePath.isNullOrBlank()) {
                putJsonObject("experimental") {
                    putJsonObject("cache_file") {
                        put("enabled", true)
                        put("path", cacheFilePath)
                        if (fakeEnabled) put("store_fakeip", true)
                    }
                }
            }
        }
        return json.encodeToString(config)
    }

    /** Parses a verbatim JSON array string into its elements; returns empty on blank/invalid input. */
    private fun parseJsonArray(raw: String): List<kotlinx.serialization.json.JsonElement> {
        if (raw.isBlank()) return emptyList()
        return runCatching { Json.parseToJsonElement(raw).jsonArray.toList() }.getOrDefault(emptyList())
    }

    /**
     * Rewrites a plain UDP DNS resolver address to its TCP form when [preferTcp] is set, so the query
     * rides a SOCKS CONNECT instead of the flakier UDP-ASSOCIATE. DoH/DoT/DoQ and already-scheme'd
     * addresses are left untouched; special keywords ("fakeip"/"local") too.
     *   "8.8.8.8" → "tcp://8.8.8.8"  ·  "udp://1.1.1.1" → "tcp://1.1.1.1"  ·  "https://…" → unchanged
     */
    /**
     * Emits ONE `dns.servers` entry in the sing-box 1.14 form.
     *
     * 1.14.0 hard-rejects the legacy `{"address": "tls://1.1.1.1"}` shape ("legacy DNS server formats
     * … removed in sing-box 1.14.0"), so the scheme that used to live in the address string is now the
     * `type` field and the rest is split into `server` / `server_port` / `path`. Everything the app
     * can put in a resolver box goes through here: bare IP/host (udp), udp/tcp/tls/quic/h3/https URLs,
     * `local`, `fakeip`, and `dhcp://<iface>`.
     */
    private fun JsonArrayBuilder.addDnsServer(
        tag: String,
        address: String,
        detour: String? = null,
        fake4Range: String? = null,
        fake6Range: String? = null,
    ) {
        val raw = address.trim()
        val scheme = raw.substringBefore("://", "").lowercase()
        val rest = if (scheme.isEmpty()) raw else raw.substringAfter("://")
        val hostPart = rest.substringBefore("/")
        val path = rest.substringAfter("/", "").let { if (it.isBlank()) null else "/$it" }
        // Split host:port. A bare IPv6 literal has many colons and no port; a bracketed one has its
        // port after the closing bracket.
        val bracketEnd = hostPart.lastIndexOf(']')
        val portSep = when {
            bracketEnd >= 0 -> hostPart.indexOf(':', bracketEnd)
            hostPart.count { it == ':' } == 1 -> hostPart.indexOf(':')
            else -> -1
        }
        val host = if (portSep > 0) hostPart.substring(0, portSep) else hostPart
        val port = if (portSep > 0) hostPart.substring(portSep + 1).toIntOrNull() else null

        val type = when {
            scheme.isNotEmpty() -> scheme
            raw.equals("local", true) || raw.equals("localhost", true) -> "local"
            raw.equals("fakeip", true) -> "fakeip"
            raw.isBlank() -> "local"
            else -> "udp"
        }
        addJsonObject {
            put("tag", tag)
            put("type", type)
            when (type) {
                "local", "fakeip" -> Unit // no server address at all
                "dhcp" -> if (host.isNotBlank() && !host.equals("auto", true)) put("interface", host)
                else -> {
                    put("server", host.removePrefix("[").removeSuffix("]"))
                    if (port != null) put("server_port", port)
                    // Only DoH/DoH3 carry a URL path; the default is /dns-query, so emit it only when
                    // the user's address actually names a different one.
                    if (path != null && path != "/dns-query" && (type == "https" || type == "h3")) {
                        put("path", path)
                    }
                }
            }
            if (type == "fakeip") {
                put("inet4_range", fake4Range ?: "198.18.0.0/15")
                put("inet6_range", fake6Range ?: "fc00::/18")
            }
            // `local` and `fakeip` answer without dialling anything — a detour there is meaningless.
            // So is a detour to the bare `direct` outbound: 1.14 REFUSES to start on it ("detour to an
            // empty direct outbound makes no sense"), and it was always a no-op anyway. Dropped here,
            // at the one choke point every caller goes through, so no call site can reintroduce it.
            if (detour != null && detour != "direct" && type != "local" && type != "fakeip") {
                put("detour", detour)
            }
        }
    }

    private fun maybeTcpDns(address: String, preferTcp: Boolean): String {
        if (!preferTcp) return address
        val a = address.trim()
        if (a.isEmpty()) return address
        if (a.lowercase().startsWith("udp://")) return "tcp://" + a.substring("udp://".length)
        if (a.contains("://")) return address // explicit scheme (https/tls/quic/tcp/…) → leave as-is
        // Bare resolver = UDP in sing-box; only rewrite something host-like, not keywords like "local".
        if (!a.contains('.') && !a.contains(':')) return address
        return "tcp://$a"
    }

    private fun buildProxyOutbound(
        profile: ProxyProfile,
        chained: Boolean,
        traffic: TrafficSettings = TrafficSettings(),
        // When set, the proxy is chained over this outbound tag (e.g. the WireGuard base for
        // VK-TURN, or the main proxy when this is the cascade exit). Takes precedence over the
        // olcRTC [chained] detour.
        detourTagOverride: String? = null,
        advanced: AdvancedCoreConfig? = null,
        // Outbound tag to emit. [PROXY_TAG] for the exit (default), [PROXY_BASE_TAG] for the main
        // hop when a second/cascade proxy exits in front of it.
        tag: String = PROXY_TAG
    ): JsonObject {
        val detourTag = detourTagOverride ?: if (chained) OLCRTC_TAG else null
        val tfo = advanced?.tcpFastOpen == true
        // Catch-all: a raw sing-box outbound is used verbatim (tag/detour injected).
        profile.rawOutbound?.takeIf { it.isNotBlank() }?.let { raw ->
            val rawObj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (rawObj != null) {
                // Transports without sing-box smux support (wireguard, hysteria2, tuic…) must
                // not get a multiplex block injected — it would fail config parsing.
                val rawType = rawObj["type"]?.jsonPrimitive?.contentOrNull
                val muxUnsupported = rawType in RAW_OUTBOUND_NO_MUX
                return buildJsonObject {
                    rawObj.forEach { (k, v) -> if (k != "tag" && k != "detour") put(k, v) }
                    put("tag", tag)
                    if (detourTag != null) put("detour", detourTag)
                    if (tfo && !muxUnsupported && raw.indexOf("tcp_fast_open") < 0) put("tcp_fast_open", true)
                    if (!muxUnsupported && raw.indexOf("multiplex") < 0) {
                        buildMultiplex(traffic, advanced)?.let { put("multiplex", it) }
                    }
                }
            }
        }

        return buildJsonObject {
            put("type", profile.type)
            put("tag", tag)
            put("server", profile.server)
            put("server_port", profile.serverPort)

            when (profile.type) {
                ProxyProfile.TYPE_VLESS -> {
                    put("uuid", profile.uuid)
                    if (profile.flow.isNotBlank()) {
                        put("flow", profile.flow)
                    }
                    // xudp coexists with vision flow and is what makes UDP (DNS/QUIC) actually ride the
                    // vless tunnel. Omitting it (the old "flow XOR xudp") left UDP DNS over the proxy
                    // stalling on desktop. xray-based vision servers speak xudp, so set it always.
                    put("packet_encoding", "xudp")
                }

                ProxyProfile.TYPE_VMESS -> {
                    put("uuid", profile.uuid)
                    put("alter_id", profile.alterId)
                    put("security", profile.cipher.ifBlank { "auto" })
                }

                ProxyProfile.TYPE_TROJAN -> {
                    put("password", profile.password)
                }

                ProxyProfile.TYPE_SHADOWSOCKS -> {
                    put("method", profile.method)
                    put("password", profile.password)
                }

                // Native since sing-box 1.13 in this build (with_quic no longer clashes with
                // xray's quic fork) — replaces the old hysteria2proxy SOCKS bridge.
                ProxyProfile.TYPE_HYSTERIA2 -> {
                    put("password", profile.password)
                    if (profile.hy2UpMbps > 0) put("up_mbps", profile.hy2UpMbps)
                    if (profile.hy2DownMbps > 0) put("down_mbps", profile.hy2DownMbps)
                    if (profile.hy2Obfs == "salamander" && profile.hy2ObfsPassword.isNotBlank()) {
                        putJsonObject("obfs") {
                            put("type", "salamander")
                            put("password", profile.hy2ObfsPassword)
                        }
                    }
                    // Port hopping: link `mport=443-2000,5000` → sing-box `server_ports` ranges
                    // ("start:end"); a bare port becomes a one-port range.
                    hy2ServerPorts(profile.hy2Ports).takeIf { it.isNotEmpty() }?.let { ranges ->
                        putJsonArray("server_ports") { ranges.forEach { add(it) } }
                    }
                }

                // NaïveProxy — native sing-box outbound (with_naive_outbound, cronet-based). TLS is
                // mandatory and comes from buildTls below; QUIC flag from a naive+quic:// link.
                ProxyProfile.TYPE_NAIVE -> {
                    if (profile.username.isNotBlank()) put("username", profile.username)
                    if (profile.password.isNotBlank()) put("password", profile.password)
                    if (profile.naiveQuic) put("quic", true)
                }
            }

            // TLS/transport apply to vless/vmess/trojan; shadowsocks ignores them. hysteria2 and
            // naive carry their own protocol: TLS yes (mandatory), but no v2ray transport layer.
            if (profile.type != ProxyProfile.TYPE_SHADOWSOCKS) {
                buildTls(profile)?.let { put("tls", it) }
                if (profile.type != ProxyProfile.TYPE_HYSTERIA2 && profile.type != ProxyProfile.TYPE_NAIVE) {
                    buildTransport(profile)?.let { put("transport", it) }
                }
            }

            if (detourTag != null) put("detour", detourTag)
            // hysteria2/naive have no plain TCP leg and no smux support: skip tcp_fast_open + multiplex.
            if (profile.type != ProxyProfile.TYPE_HYSTERIA2 && profile.type != ProxyProfile.TYPE_NAIVE) {
                if (tfo) put("tcp_fast_open", true)
                buildMultiplex(traffic, advanced)?.let { put("multiplex", it) }
            }
        }
    }

    /**
     * Converts the link-style hysteria2 port-hopping spec ("443,2000-3000" / "2000-3000") into
     * sing-box `server_ports` entries ("start:end"). Invalid chunks are dropped.
     */
    private fun hy2ServerPorts(spec: String): List<String> =
        spec.split(',').mapNotNull { chunk ->
            val part = chunk.trim()
            if (part.isEmpty()) return@mapNotNull null
            val bits = part.split('-', ':').map { it.trim() }
            val start = bits.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val end = if (bits.size > 1) bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null else start
            if (start !in 1..65535 || end !in 1..65535 || end < start) return@mapNotNull null
            "$start:$end"
        }

    /**
     * A SOCKS5 outbound pointing at olcRTC's local listener on [port]. Used as the chain detour
     * ([OLCRTC_TAG]) and, when the proxy is disabled, as the exit itself ([PROXY_TAG]) so Chain
     * traffic keeps riding the stealth tunnel instead of leaking out directly.
     */
    private fun olcrtcSocksOutbound(tag: String, port: Int, user: String, pass: String): JsonObject =
        buildJsonObject {
            put("type", "socks")
            put("tag", tag)
            put("server", "127.0.0.1")
            put("server_port", port)
            put("version", "5")
            if (user.isNotBlank()) {
                put("username", user)
                put("password", pass)
            }
        }

    /**
     * Converts a legacy WireGuard *outbound* JSON (as stored in [ProxyProfile.rawOutbound]:
     * `server`/`server_port`/`local_address`/`private_key`/`peer_public_key`/`mtu`) into a sing-box
     * 1.13 WireGuard *endpoint* object (`address`/`private_key`/`peers[]`). The wireguard outbound was
     * removed in sing-box 1.13.0, so VK-TURN / WDTT (which tunnel through a local WireGuard listener)
     * must use an endpoint instead. Returns null if the raw JSON isn't a wireguard outbound.
     */
    /** Whether [host] is this machine — the shape every local relay listener uses. */
    private fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }

    private fun buildWireguardEndpoint(
        profile: ProxyProfile,
        tag: String,
        autoDetectInterface: Boolean = false,
    ): JsonObject? {
        val raw = profile.rawOutbound?.takeIf { it.isNotBlank() } ?: return null
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "wireguard") return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: "127.0.0.1"
        val serverPort = obj["server_port"]?.jsonPrimitive?.intOrNull ?: return null
        val privateKey = obj["private_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val peerPublicKey = obj["peer_public_key"]?.jsonPrimitive?.contentOrNull ?: return null
        val localAddrs = obj["local_address"]?.jsonArray ?: buildJsonArray { }
        val mtu = obj["mtu"]?.jsonPrimitive?.intOrNull
        return buildJsonObject {
            put("type", "wireguard")
            put("tag", tag)
            // Userspace gVisor stack (the process is already bound to the upstream network).
            put("system", false)
            // VK-TURN / WDTT put the WireGuard peer on 127.0.0.1 (the local relay listener). With
            // `route.auto_detect_interface` on — desktop, where there is no VpnService.protect — sing-box
            // appends its bind-to-interface control to EVERY dialer, and WireGuard's socket is a
            // ListenPacket: the control sees the BIND address (0.0.0.0), never the destination, so it
            // pins the socket to the physical NIC. Sending to 127.0.0.1 from there fails outright:
            //   "failed to send handshake initiation: write udp4 0.0.0.0:x->127.0.0.1:9000:
            //    wsasendmsg: The requested address is not valid in its context"
            // (WSAEADDRNOTAVAIL, straight out of the user's singbox.log) — the tunnel comes up, the
            // handshake never leaves the machine, and VK-TURN carries nothing.
            //
            // `inet4_bind_address` sets sing-box's `disableDefaultBind`, which is the only supported way
            // to keep that control off ONE endpoint. Only for a loopback peer, and only when the bind
            // would otherwise happen — Android (auto_detect_interface = false, protect() instead) is
            // untouched.
            if (autoDetectInterface && isLoopbackHost(server)) {
                put("inet4_bind_address", "0.0.0.0")
            }
            if (mtu != null && mtu > 0) put("mtu", mtu)
            putJsonArray("address") { localAddrs.forEach { add(it) } }
            put("private_key", privateKey)
            putJsonArray("peers") {
                addJsonObject {
                    put("address", server)
                    put("port", serverPort)
                    put("public_key", peerPublicKey)
                    // The local WG listener carries everything (VK-TURN tunnel is IPv4-only).
                    putJsonArray("allowed_ips") { add("0.0.0.0/0") }
                }
            }
        }
    }

    private fun buildMultiplex(traffic: TrafficSettings, advanced: AdvancedCoreConfig?): JsonObject? {
        // Per-location advanced mux overrides the global traffic setting when present.
        if (advanced != null) {
            if (!advanced.muxEnabled) return null
            return buildJsonObject {
                put("enabled", true)
                put("protocol", advanced.muxProtocol)
                put("max_streams", advanced.muxMaxStreams)
            }
        }
        if (!traffic.muxEnabled) return null
        return buildJsonObject {
            put("enabled", true)
            put("protocol", traffic.muxProtocol)
            put("max_connections", traffic.muxMaxConnections)
        }
    }

    private fun buildTls(profile: ProxyProfile) = when (profile.security) {
        ProxyProfile.SECURITY_TLS, ProxyProfile.SECURITY_REALITY -> buildJsonObject {
            put("enabled", true)
            put("server_name", profile.sni.ifBlank { profile.server })
            put("insecure", profile.allowInsecure)
            if (profile.alpn.isNotEmpty()) {
                putJsonArray("alpn") { profile.alpn.forEach { add(it) } }
            }
            if (profile.fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", profile.fingerprint)
                }
            }
            if (profile.security == ProxyProfile.SECURITY_REALITY &&
                profile.realityPublicKey.isNotBlank()
            ) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", profile.realityPublicKey)
                    put("short_id", profile.realityShortId)
                }
            }
        }

        else -> null
    }

    private fun buildTransport(profile: ProxyProfile) = when (profile.network) {
        ProxyProfile.NETWORK_WS -> buildJsonObject {
            put("type", "ws")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) {
                putJsonObject("headers") { put("Host", profile.host) }
            }
        }

        ProxyProfile.NETWORK_HTTPUPGRADE -> buildJsonObject {
            put("type", "httpupgrade")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) put("host", profile.host)
        }

        ProxyProfile.NETWORK_GRPC -> buildJsonObject {
            put("type", "grpc")
            if (profile.path.isNotBlank()) put("service_name", profile.path)
        }

        ProxyProfile.NETWORK_HTTP -> buildJsonObject {
            put("type", "http")
            if (profile.path.isNotBlank()) put("path", profile.path)
            if (profile.host.isNotBlank()) {
                putJsonArray("host") { add(profile.host) }
            }
        }

        ProxyProfile.NETWORK_XHTTP -> throw IllegalArgumentException(
            "xhttp/splithttp transport requires the Xray core, which is not supported yet"
        )

        else -> null // tcp: no transport block
    }
}
