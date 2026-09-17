package org.olcbox.app.vpn.singbox

import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.vpn.xray.XrayConfig
import java.io.File
import kotlin.test.Test

/**
 * Throwaway harness: dumps the exact shapes DesktopEngineController feeds to the core so
 * `sing-box check` can be run on each. Only runs when -Dyptun.dumpConfigs=<dir> is set.
 */
class DumpDesktopConfigsTest {

    private val vless = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS, server = "vbn.azz.su", serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_TCP, security = ProxyProfile.SECURITY_TLS, sni = "vbn.azz.su",
    )
    private val awgSocks = ProxyProfile(
        type = "socks", server = "127.0.0.1", serverPort = 10809,
        rawOutbound = """{"type":"socks","server":"127.0.0.1","server_port":10809,"version":"5"}""",
    )
    private val wgBase = ProxyProfile(
        type = "wireguard", server = "10.0.0.1", serverPort = 51820,
        rawOutbound = """{"type":"wireguard","server":"10.0.0.1","server_port":51820,
            "local_address":["10.7.0.2/32"],
            "private_key":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "peer_public_key":"ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="}""",
    )

    private val xhttp = ProxyProfile(
        type = ProxyProfile.TYPE_VLESS, server = "vbn.azz.su", serverPort = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        network = ProxyProfile.NETWORK_XHTTP, security = ProxyProfile.SECURITY_TLS, sni = "vbn.azz.su",
        path = "/xh", xhttpMode = "packet-up",
    )

    @Test
    fun dump() {
        val dir = System.getProperty("yptun.dumpConfigs")?.let(::File) ?: return
        dir.mkdirs()
        fun w(name: String, json: String) = File(dir, "$name.json").writeText(json)

        // 1. plain vless over the in-core TUN (the shape that was dying)
        w("01-plain-tun", SingBoxConfig.build(
            profile = vless, listenPort = 10808, autoDetectInterface = true,
            matchAppsByProcess = true, blockQuic = true, forceFamilyResolve = false,
            tunMode = true, mixedInbound = true, hijackDns = true,
        ))
        // 2. same, no TUN (proxy mode)
        w("02-plain-socks", SingBoxConfig.build(
            profile = vless, listenPort = 10808, autoDetectInterface = true,
            matchAppsByProcess = true, mixedInbound = true, hijackDns = true,
        ))
        // 3. sing-box fronting xray (startSingBoxFront)
        w("03-xray-front", SingBoxConfig.build(
            profile = ProxyProfile(
                tag = "xray", type = "socks", server = "127.0.0.1", serverPort = 10810,
                rawOutbound = """{"type":"socks","server":"127.0.0.1","server_port":10810,"version":"5"}""",
            ),
            listenPort = 10808, autoDetectInterface = true, matchAppsByProcess = true,
            traffic = TrafficSettings(fakeDnsEnabled = false, blockRuDomains = false),
            blockQuic = false, forceFamilyResolve = false, preferTcpRemoteDns = true,
            tunMode = true, mixedInbound = true, cacheFilePath = File(dir, "cache.db").absolutePath,
        ))
        // 4. VK-TURN over AmneziaWG-SOCKS, with and without a second proxy
        w("04-vkturn-awg", SingBoxConfig.build(
            profile = awgSocks, listenPort = 10808, autoDetectInterface = true,
            matchAppsByProcess = true, dnsStrategyOverride = "ipv4_only", blockQuic = false,
            tunMode = true, mixedInbound = true, cacheFilePath = File(dir, "cache.db").absolutePath,
        ))
        w("05-vkturn-awg-cascade", SingBoxConfig.build(
            profile = awgSocks, secondProfile = vless, listenPort = 10808,
            autoDetectInterface = true, matchAppsByProcess = true,
            dnsStrategyOverride = "ipv4_only", blockQuic = false, tunMode = true, mixedInbound = true,
        ))
        // 6. VK-TURN over WireGuard (+ chained proxy)
        w("06-vkturn-wg", SingBoxConfig.build(
            profile = vless, wireguardBase = wgBase, listenPort = 10808,
            autoDetectInterface = true, matchAppsByProcess = true,
            dnsStrategyOverride = "ipv4_only", blockQuic = false, tunMode = true, mixedInbound = true,
        ))
        // 7. AmneziaWG standalone (local UDP tunnel: quic allowed, sniff-override)
        w("07-awg-standalone", SingBoxConfig.build(
            profile = awgSocks, listenPort = 10808, autoDetectInterface = true,
            matchAppsByProcess = true, blockQuic = false, sniffOverrideDestination = true,
            forceFamilyResolve = false, tunMode = true, mixedInbound = true, hijackDns = true,
        ))
        // 8. olcRTC chain (directViaBase)
        w("08-olcrtc-chain", SingBoxConfig.build(
            profile = vless, listenPort = 10808, olcrtcChainPort = 10811,
            autoDetectInterface = true, matchAppsByProcess = true, directViaBase = true,
            mixedInbound = true, hijackDns = true, tunMode = true,
        ))
        // 9. MasterDNS (directViaBase, no local resolve)
        w("09-masterdns", SingBoxConfig.build(
            profile = vless, listenPort = 10808, olcrtcChainPort = 10812,
            autoDetectInterface = true, matchAppsByProcess = true, blockQuic = true,
            forceFamilyResolve = false, allowLocalResolve = false, directViaBase = true,
            mixedInbound = true, hijackDns = true, tunMode = true,
        ))
        // 10. fakeip forced (desktop default path)
        w("10-fakedns", SingBoxConfig.build(
            profile = vless, listenPort = 10808, autoDetectInterface = true,
            matchAppsByProcess = true, forceFakeDns = true, tunMode = true, mixedInbound = true,
            hijackDns = true, cacheFilePath = File(dir, "cache.db").absolutePath,
        ))
        // 11. DoH remote resolver
        w("11-doh", SingBoxConfig.build(
            profile = vless, listenPort = 10808, autoDetectInterface = true,
            remoteDnsOverHttps = true, tunMode = true, mixedInbound = true, hijackDns = true,
        ))
        // 12. split tunnel
        w("12-split", SingBoxConfig.build(
            profile = vless, listenPort = 10808, autoDetectInterface = true, tunMode = true,
            splitTunnelMode = SingBoxConfig.SPLIT_TUNNEL_BYPASS,
            splitTunnelProcesses = listOf("chrome.exe"),
            tunExcludeAddresses = listOf("1.2.3.4/32"), mixedInbound = true, hijackDns = true,
        ))

        // --- Xray side (same idea: `xray run -test -c <file>` over every shape after a re-vendor) ---
        // 21. plain vless+tls
        w("21-xray-plain", XrayConfig.build(profile = vless, listenPort = 10810))
        // 22. vless WITHOUT tls to a public address — upstream #6303 makes this a hard error, our
        //     local patch downgrades it to a warning (see cores/xraybridge/patches_test.go).
        w("22-xray-vless-notls", XrayConfig.build(
            profile = vless.copy(security = "", sni = ""), listenPort = 10810,
        ))
        // 23. xhttp main + second proxy (cascade loopback) with the legacy session* keys
        w("23-xray-xhttp-cascade", XrayConfig.build(
            profile = xhttp, secondProfile = vless, listenPort = 10810,
        ))
        // 24. VK-TURN: the proxy dials through an Xray wireguard base outbound
        w("24-xray-wg-base", XrayConfig.build(
            profile = vless, wireguardBase = wgBase, listenPort = 10810, blockQuic = false,
        ))
        // 25. MasterDNS: chain via sockopt.dialerProxy, direct bucket kept inside the tunnel
        w("25-xray-masterdns", XrayConfig.build(
            profile = vless, listenPort = 10810, olcrtcChainPort = 10812,
            chainViaDialerProxy = true, directViaBase = true, forceFamilyResolve = false,
            handshakeTimeoutSec = 30,
        ))
    }
}
