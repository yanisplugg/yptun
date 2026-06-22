package org.olcbox.app.vpn.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private val RAW_OUTBOUND_NO_MUX = setOf("wireguard", "hysteria2", "hysteria", "tuic", "endpoint", "socks")

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
    ): String {
        // Effective DNS/resolve strategy (per-tunnel override → global traffic setting). Hoisted so
        // both the inbound sniff-override and the route resolve/family rules use the same value.
        val effectiveStrategy = dnsStrategyOverride ?: traffic.domainStrategy
        // FakeDNS is on when either the (legacy global) traffic toggle is set OR this location carries a
        // translated spec. The pool ranges come from the spec when present, else the defaults.
        val fakeEnabled = traffic.fakeDnsEnabled || fakeDnsSpec != null
        val fake4Range = fakeDnsSpec?.inet4Range?.takeIf { it.isNotBlank() } ?: "198.18.0.0/15"
        val fake6Range = fakeDnsSpec?.inet6Range?.takeIf { it.isNotBlank() } ?: "fc00::/18"
        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    // App traffic resolves through the proxy (no DNS leak).
                    addJsonObject {
                        put("tag", "remote")
                        put("address", traffic.remoteDns)
                        put("detour", PROXY_TAG)
                    }
                    // Bootstrap: resolve the proxy server's own domain directly.
                    addJsonObject {
                        put("tag", "direct")
                        put("address", traffic.directDns)
                        put("detour", "direct")
                    }
                    // FakeDNS equivalent: hand out synthetic IPs so apps never see the real address;
                    // the sniffed domain is resolved behind the proxy.
                    if (fakeEnabled) {
                        addJsonObject {
                            put("tag", "fake")
                            put("address", "fakeip")
                        }
                    }
                }
                putJsonArray("rules") {
                    addJsonObject {
                        put("outbound", "any")
                        put("server", "direct")
                    }
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
                if (fakeEnabled) {
                    putJsonObject("fakeip") {
                        put("enabled", true)
                        put("inet4_range", fake4Range)
                        put("inet6_range", fake6Range)
                    }
                    // Separate fake/real caches so a fakeip answer never poisons a direct lookup.
                    put("independent_cache", true)
                }
                put("final", "remote")
                // ipv4_only override (VK-TURN): the WireGuard tunnel is IPv4-only, so resolving
                // AAAA would make dual-stack sites attempt IPv6 → "no route to host". Forcing A-only
                // keeps all traffic on IPv4 through the tunnel.
                put("strategy", dnsStrategyOverride ?: traffic.domainStrategy)
            }

            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "socks")
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
                    // Force the chosen IP family on a full UDP tunnel without rejecting: sniff the
                    // domain, replace an IP-literal destination with it, and resolve to the family.
                    if (sniffOverrideDestination) {
                        put("sniff", true)
                        put("sniff_override_destination", true)
                        put("domain_strategy", effectiveStrategy)
                    }
                }
            }

            putJsonArray("outbounds") {
                val wgBaseOutbound = wireguardBase?.let { buildWireguardBaseOutbound(it) }
                // The main proxy dials through the WG base (VK-TURN) when present, else olcRTC (Chain).
                val baseDetour = if (wgBaseOutbound != null) WG_BASE_TAG else null
                // Base tunnel exit tag (WG for VK-TURN, dnstt SOCKS for dnstt) — keeps `direct` traffic on
                // the tunnel when [directViaBase].
                val baseExitTag = when {
                    wgBaseOutbound != null -> WG_BASE_TAG
                    olcrtcChainPort != null -> OLCRTC_TAG
                    else -> null
                }
                val directDialsBase = directViaBase && baseExitTag != null
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
                } else {
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
                if (wgBaseOutbound != null) {
                    add(wgBaseOutbound)
                }
                // olcRTC chain detour: the main proxy dials through this local SOCKS.
                if (olcrtcChainPort != null) {
                    add(olcrtcSocksOutbound(OLCRTC_TAG, olcrtcChainPort, olcrtcChainUser, olcrtcChainPass))
                }
                addJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                    // VK-TURN / dnstt: route `direct` traffic THROUGH the base tunnel (dnstt-server / VK
                    // exit) instead of the real interface, so routing never bypasses the tunnel.
                    if (directDialsBase) put("detour", baseExitTag)
                    // IPv6-leak guard for HYBRID modes (prefer_ipv4/prefer_ipv6): the direct/bypass path
                    // (domain:ru → direct) would otherwise dial the user's REAL IPv6 for dual-stack sites,
                    // exposing it on a leak check ("domain:ru goes direct only over IPv4, IPv6 leaks").
                    // Force the direct outbound to IPv4 so bypass traffic NEVER egresses over real IPv6;
                    // sing-box re-resolves the sniffed domain to A here, so even an IPv6-literal direct
                    // connection is dialed as IPv4. Proxied traffic is untouched (still dual-stack via the
                    // proxy's own IP — no user-IP leak there). ipv4_only/ipv6_only already pin a family
                    // globally (route reject), so only the prefer_* hybrids need this.
                    if (effectiveStrategy == "prefer_ipv4" || effectiveStrategy == "prefer_ipv6") {
                        put("domain_strategy", "ipv4_only")
                    }
                }
            }

            putJsonObject("route") {
                put("final", if (routingProfile != null) SingBoxRouting.finalOutbound(routingProfile) else PROXY_TAG)
                put("auto_detect_interface", autoDetectInterface)

                putJsonArray("rules") {
                    // Expert per-core overrides (sing-box): explicit sniff/resolve/strategy control.
                    val sbExpert = routingProfile?.expertEnabled == true
                    val sbExpertStrategy = routingProfile
                        ?.takeIf { it.expertEnabled }?.singboxDomainStrategy?.takeIf { it.isNotBlank() }
                    // Sniff destination domain so domain rules match (advanced or expert can disable it).
                    if (advanced?.sniff != false && (!sbExpert || routingProfile!!.singboxSniff)) {
                        addJsonObject { put("action", "sniff") }
                    }
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
                    // Private/LAN always direct (Happ profiles assume it; bypassLan toggle wants it).
                    if (routingProfile != null || routing.bypassLan) {
                        addJsonObject {
                            put("ip_is_private", true)
                            put("outbound", "direct")
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
                            manualRulesUseIp || (sbExpert && routingProfile!!.singboxResolve))
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
                    // Advanced verbatim user rules (highest precedence).
                    parseJsonArray(routing.customRulesJson).forEach { add(it) }
                    // Structured v2rayNG-style rules (in user-defined order, after verbatim JSON).
                    SingBoxRouting.manualRules(routing.rules).forEach { add(it) }
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
                    // The selected routing profile's own buckets (ordered by its routeOrder).
                    if (routingProfile != null) {
                        SingBoxRouting.rules(routingProfile).forEach { add(it) }
                    }
                    // Direct conveniences last (a profile proxy rule above still wins on first match).
                    if (routing.directDomains.isNotEmpty()) {
                        addJsonObject {
                            putJsonArray("domain_suffix") { routing.directDomains.forEach { add(it) } }
                            put("outbound", "direct")
                        }
                    }
                    if (routing.bypassRussia) {
                        addJsonObject {
                            putJsonArray("rule_set") { add("geoip-ru"); add("geosite-ru") }
                            put("outbound", "direct")
                        }
                    }
                }

                // rule_set definitions, merged from the profile + the toggles, de-duplicated by tag
                // (a profile `geoip:ru` and the bypassRussia toggle both want a `geoip-ru` set, and a
                // duplicate tag is a hard config error in sing-box).
                val mergedRuleSets = buildList {
                    if (routingProfile != null) {
                        SingBoxRouting.ruleSets(routingProfile, singboxGeositeBase, singboxGeoipBase)
                            .forEach { (it as? JsonObject)?.let(::add) }
                    }
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
                            put("download_detour", "direct")
                        })
                    }
                    if (routing.bypassRussia) {
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geoip-ru")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs")
                            put("download_detour", "direct")
                        })
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geosite-ru")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs")
                            put("download_detour", "direct")
                        })
                    }
                }.associateBy { it["tag"]?.jsonPrimitive?.contentOrNull ?: it.toString() }.values
                if (mergedRuleSets.isNotEmpty()) {
                    putJsonArray("rule_set") { mergedRuleSets.forEach { add(it) } }
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
                    } else {
                        put("packet_encoding", "xudp")
                    }
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
            }

            // TLS/transport apply to vless/vmess/trojan; shadowsocks ignores them.
            if (profile.type != ProxyProfile.TYPE_SHADOWSOCKS) {
                buildTls(profile)?.let { put("tls", it) }
                buildTransport(profile)?.let { put("transport", it) }
            }

            if (detourTag != null) put("detour", detourTag)
            if (tfo) put("tcp_fast_open", true)
            buildMultiplex(traffic, advanced)?.let { put("multiplex", it) }
        }
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

    /** A raw WireGuard outbound used as a chain base (tagged [WG_BASE_TAG], no mux/detour). */
    private fun buildWireguardBaseOutbound(profile: ProxyProfile): JsonObject? {
        val raw = profile.rawOutbound?.takeIf { it.isNotBlank() } ?: return null
        val rawObj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return buildJsonObject {
            rawObj.forEach { (k, v) -> if (k != "tag" && k != "detour") put(k, v) }
            put("tag", WG_BASE_TAG)
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
