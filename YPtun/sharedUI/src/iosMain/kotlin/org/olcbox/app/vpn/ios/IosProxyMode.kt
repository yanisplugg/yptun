package org.olcbox.app.vpn.ios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Proxy mode on iOS: instead of capturing the device's packets, the tunnel only advertises an HTTP
 * proxy (`NEProxySettings`) that every app honouring the system proxy — which is anything built on
 * URLSession/CFNetwork — goes through. Apps that ignore it keep talking to the network directly,
 * which is the whole point of the mode: nothing is forced through the core.
 *
 * NEProxySettings speaks HTTP and PAC only, never SOCKS, and the cores leave a SOCKS5 listener. So
 * the config gets an HTTP listener as well:
 *  - sing-box has `mixed`, which serves SOCKS and HTTP on the SAME port — one field to flip;
 *  - xray's socks inbound is SOCKS-only, so it needs a separate `http` inbound.
 *
 * Both are done here, on the finished JSON, rather than as parameters threaded through every core
 * builder: the shape is the same for a typed profile, a verbatim user config and every engine, and
 * this way TUN mode — the default — is byte-for-byte what it was.
 */
internal object IosProxyMode {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The xray HTTP inbound's tag; SOCKS stays on its own port for the hev bridge and for pings. */
    private const val XRAY_HTTP_TAG = "http-in"

    /**
     * Turns the sing-box inbound listening on [listenPort] from `socks` into `mixed`. Returns the
     * config unchanged when there is no such inbound (then proxy mode falls back to TUN, see
     * IosTunnelSession).
     */
    fun singBoxMixedInbound(configJson: String, listenPort: Int): String {
        val root = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return configJson
        val inbounds = root["inbounds"] as? JsonArray ?: return configJson
        var changed = false
        val rewritten = buildJsonArray {
            inbounds.forEach { element ->
                val inbound = element as? JsonObject
                val isSocksHere = inbound != null &&
                    (inbound["type"] as? JsonPrimitive)?.contentOrNull == "socks" &&
                    (inbound["listen_port"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } == listenPort
                if (!isSocksHere) {
                    add(element)
                    return@forEach
                }
                changed = true
                add(
                    buildJsonObject {
                        inbound!!.forEach { (key, value) -> if (key != "type") put(key, value) }
                        put("type", "mixed")
                    }
                )
            }
        }
        if (!changed) return configJson
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (key, value) -> if (key != "inbounds") put(key, value) }
                put("inbounds", rewritten)
            }
        )
    }

    /**
     * Puts an `http` inbound on [listenPort] and moves xray's own SOCKS inbound aside to
     * [altSocksPort]. Two listeners cannot share a port and xray's socks inbound does not speak HTTP,
     * yet the system proxy should land on the same port whichever core is running — that is the port
     * the settings screen shows the user. Returns the config unchanged if it declares no inbounds.
     */
    fun xrayHttpInbound(configJson: String, listenHost: String, listenPort: Int, altSocksPort: Int): String {
        val root = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return configJson
        val inbounds = root["inbounds"] as? JsonArray ?: return configJson
        val moved = buildJsonArray {
            inbounds.forEach { element ->
                val inbound = element as? JsonObject
                val onListenPort = inbound != null &&
                    (inbound["port"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() } == listenPort
                if (!onListenPort) {
                    add(element)
                    return@forEach
                }
                add(
                    buildJsonObject {
                        inbound!!.forEach { (key, value) -> if (key != "port") put(key, value) }
                        put("port", altSocksPort)
                    }
                )
            }
            add(
                buildJsonObject {
                    put("tag", XRAY_HTTP_TAG)
                    put("listen", listenHost)
                    put("port", listenPort)
                    put("protocol", "http")
                    putJsonObject("sniffing") {
                        // Without sniffing the routing rules only ever see an IP, so `domain:` buckets
                        // stop matching and everything falls through to the default outbound.
                        put("enabled", true)
                        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                    }
                }
            )
        }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (key, value) -> if (key != "inbounds") put(key, value) }
                put("inbounds", moved)
            }
        )
    }
}
