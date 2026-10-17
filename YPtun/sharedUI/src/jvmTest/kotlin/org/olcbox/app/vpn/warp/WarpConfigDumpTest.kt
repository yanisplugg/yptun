package org.olcbox.app.vpn.warp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.xray.XrayConfig

/**
 * Builds the four WARP combinations (client/server × WG/AWG) for both cores and checks their shape.
 * When WARP_DUMP_DIR is set the configs are also written there, so they can be fed to a real
 * `sing-box check` / `xray -test` (that is how the loopback relay was verified).
 */
class WarpConfigDumpTest {

    private val ini = """
        [Interface]
        PrivateKey = YFPiOJCBPN8R4H2zXlGdfIcHq8Z0y1vC2m5qL7sZ2G4=
        Address = 172.16.0.2/32, 2606:4700:110:8c45:519f:f2ca:bcc5:227c/128
        MTU = 1280
        [Peer]
        PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = engage.cloudflareclient.com:2408
    """.trimIndent()

    private val vless = ProxyProfile(
        tag = "main", type = ProxyProfile.TYPE_VLESS, server = "127.0.0.1", serverPort = 18991,
        uuid = "11111111-2222-3333-4444-555555555555", flow = "xtls-rprx-vision",
    )

    private fun localSocks(port: Int) = ProxyProfile(
        tag = "WARP", type = "socks", server = "127.0.0.1", serverPort = port,
        rawOutbound = "{\"type\":\"socks\",\"server\":\"127.0.0.1\",\"server_port\":$port,\"version\":\"5\"}",
    )

    private val dir: File? = System.getenv("WARP_DUMP_DIR")?.let { File(it).apply { mkdirs() } }

    private fun dump(name: String, text: String) {
        dir?.resolve("$name.json")?.writeText(text)
        Json.parseToJsonElement(text) // must be valid JSON
    }

    @Test
    fun wgPlainEndpointHelpers() {
        val (host, port) = WarpHelper.endpointOf(ini)
        assertTrue(host == "engage.cloudflareclient.com" && port == 2408)
        assertTrue(WarpHelper.withEndpoint(ini, "127.0.0.1:18993").contains("Endpoint = 127.0.0.1:18993"))
    }

    @Test
    fun singBoxCombinations() {
        val wg = WarpHelper.parseWarpIniToProxyProfile(ini)!!
        // client + WG
        val cWg = SingBoxConfig.build(vless, listenPort = 18990, wireguardBase = wg)
        dump("sb-client-wg", cWg)
        assertTrue(cWg.contains("\"wireguard-base\""))
        // server + WG
        val sWg = SingBoxConfig.build(vless, listenPort = 18990, secondProfile = wg)
        dump("sb-server-wg", sWg)
        // client + AWG (main dials through awgproxy's SOCKS)
        val cAwg = SingBoxConfig.build(vless, listenPort = 18990, olcrtcChainPort = 18995)
        dump("sb-client-awg", cAwg)
        // server + AWG (exit = awgproxy SOCKS, relay inbound → main)
        val relay = WarpRelay(18993, "engage.cloudflareclient.com", 2408)
        val sAwg = SingBoxConfig.build(vless, listenPort = 18990, secondProfile = localSocks(18995), warpRelay = relay)
        dump("sb-server-awg", sAwg)
        val o = Json.parseToJsonElement(sAwg).jsonObject
        val inbound = o["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "warp-relay-in" }
        assertTrue(inbound["listen_port"]!!.jsonPrimitive.content == "18993")
        val firstRule = o["route"]!!.jsonObject["rules"]!!.jsonArray.first().jsonObject
        assertTrue(firstRule["outbound"]!!.jsonPrimitive.content == "proxy-base")
        val tags = o["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]?.jsonPrimitive?.content }
        assertTrue("proxy" in tags && "proxy-base" in tags)
    }

    @Test
    fun xrayCombinations() {
        val wg = WarpHelper.parseWarpIniToProxyProfile(ini)!!
        val cWg = XrayConfig.build(vless, listenPort = 18990, wireguardBase = wg, chainViaDialerProxy = true, keepFlowOnChain = true)
        dump("xr-client-wg", cWg)
        assertTrue(cWg.contains("xtls-rprx-vision"), "Vision flow must survive the WARP chain")
        val sWg = XrayConfig.build(vless, listenPort = 18990, secondProfile = wg)
        dump("xr-server-wg", sWg)
        val cAwg = XrayConfig.build(
            vless, listenPort = 18990, olcrtcChainPort = 18995, chainViaDialerProxy = true, keepFlowOnChain = true,
        )
        dump("xr-client-awg", cAwg)
        assertTrue(cAwg.contains("xtls-rprx-vision"))
        val relay = WarpRelay(18993, "engage.cloudflareclient.com", 2408)
        val sAwg = XrayConfig.build(vless, listenPort = 18990, secondProfile = localSocks(18995), warpRelay = relay)
        dump("xr-server-awg", sAwg)
        val o = Json.parseToJsonElement(sAwg).jsonObject
        val inbound = o["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "warp-relay-in" }
        assertTrue(inbound["protocol"]!!.jsonPrimitive.content == "dokodemo-door")
        val rules = o["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["inboundTag"]?.jsonArray?.any { t -> t.jsonPrimitive.content == "warp-relay-in" } == true })
    }
}
