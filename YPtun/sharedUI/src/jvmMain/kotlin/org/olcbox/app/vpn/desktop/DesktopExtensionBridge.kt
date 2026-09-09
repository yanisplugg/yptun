package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.VpnStatus
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Loopback control API for the Chrome extension.
 *
 * **Why this exists at all.** MV3 gives an extension no raw sockets and no way to listen on a port,
 * so an extension can never speak VLESS itself, and REALITY is doubly impossible there — it needs a
 * custom TLS ClientHello and Chrome's TLS stack is not scriptable. The only shape in which a browser
 * gets tcp / xhttp / tls / REALITY is the one it already understands: `chrome.proxy` pointed at a
 * proxy on this machine, with a real core behind it. That core is YPtun itself — the extension pastes
 * the link here, the app raises the tunnel with whatever engine the link needs, and the browser talks
 * to a [DesktopHttpProxyBridge] in front of the core's local SOCKS.
 *
 * The previous design put the proxy ON THE SERVER (an extra xray `http` inbound over TLS), which is
 * what the owner asked to get rid of: it needed a second inbound, a real Let's Encrypt certificate,
 * and it could never carry REALITY.
 *
 * **Why HTTP and not the SOCKS port directly.** Chrome cannot answer a SOCKS5 auth challenge, and a
 * SOCKS-only listener is what every engine except sing-box publishes; the bridge terminates both
 * problems — the same reason system-proxy mode uses it (see [DesktopHttpProxyBridge]).
 *
 * **Who may talk to it.** Bound to loopback, and every request must carry [GUARD_HEADER]. A custom
 * header forces a CORS preflight, and the preflight is only answered for a `chrome-extension://`
 * origin — so a web page cannot reach these endpoints even though they sit on localhost.
 */
class DesktopExtensionBridge(
    private val repository: LocationsRepository,
    private val vpnManager: DesktopVpnManager,
    private val log: (String) -> Unit,
) {
    private var server: HttpServer? = null
    private var bridge: DesktopHttpProxyBridge? = null
    private var bridgeSocks: Pair<String, Int>? = null

    fun start() {
        if (server != null) return
        val srv = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), CONTROL_PORT), 0)
        }.getOrElse {
            log("Extension bridge: port $CONTROL_PORT busy — ${it.message}")
            return
        }
        srv.createContext("/") { ex -> handle(ex) }
        // A cold /connect blocks for as long as the core takes to come up. On the default (single,
        // dispatcher-thread) executor that would also stall the popup's /status polls, so give it a
        // few threads: the extension never has more than a couple of requests in flight.
        srv.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
            Thread(r, "YPtunExtensionBridge").apply { isDaemon = true }
        }
        srv.start()
        server = srv
        log("Extension bridge listening on $LOOPBACK:$CONTROL_PORT")
    }

    fun stop() {
        bridge?.stop()
        bridge = null
        bridgeSocks = null
        server?.stop(0)
        server = null
    }

    private fun handle(ex: HttpExchange) {
        try {
            val origin = ex.requestHeaders.getFirst("Origin").orEmpty()
            if (origin.isNotEmpty() && !origin.startsWith("chrome-extension://")) {
                reply(ex, 403, errorBody("forbidden"), "")
                return
            }
            if (ex.requestMethod == "OPTIONS") {
                reply(ex, 204, "", origin)
                return
            }
            if (ex.requestHeaders.getFirst(GUARD_HEADER) == null) {
                reply(ex, 403, errorBody("forbidden"), origin)
                return
            }
            when (ex.requestURI.path) {
                "/status" -> reply(ex, 200, status(), origin)
                "/connect" -> reply(ex, 200, connect(ex.requestBody.readBytes().decodeToString().trim()), origin)
                "/disconnect" -> reply(ex, 200, disconnect(), origin)
                else -> reply(ex, 404, errorBody("not-found"), origin)
            }
        } catch (e: Exception) {
            runCatching { reply(ex, 500, errorBody(e.message ?: "error"), "") }
        } finally {
            ex.close()
        }
    }

    // ── operations ─────────────────────────────────────────────────────────────

    /**
     * Imports [link] (any share link the app understands — vless/vmess/trojan/ss/yptun://inbound),
     * makes it the active location and raises the tunnel. A blank link connects whatever is already
     * active, which is how the extension reconnects to a location it stored earlier.
     */
    private fun connect(link: String): String = runBlocking {
        if (link.isNotBlank()) {
            val before = repository.getAllLocations().map { it.storageId }.toSet()
            val imported = runCatching { repository.importText(link) }.getOrDefault(false)
            if (!imported) return@runBlocking errorBody("bad-link")
            // importText says whether it worked, not WHICH entry it made. A fresh link leaves exactly
            // one new id; a link already in the list is deduped by the merge and leaves none, so fall
            // back to matching the server it points at — otherwise a re-connect would silently raise
            // whatever location happened to be active instead of the one the extension asked for.
            val entries = repository.getAllLocations()
            val target = entries.firstOrNull { it.storageId !in before }?.storageId
                ?: ShareLinkParser.parse(link)?.let { p ->
                    entries.firstOrNull { it.proxy?.server == p.server && it.proxy?.serverPort == p.serverPort }
                }?.storageId
            if (target != null) repository.setActiveLocationId(target)
        }
        val active = repository.getActiveLocation()
        if (active == null || !active.location.isComplete()) return@runBlocking errorBody("no-location")

        if (vpnManager.status.value !is VpnStatus.Connected) {
            vpnManager.startVpn()
            val settled = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                vpnManager.status.first {
                    it is VpnStatus.Connected || it is VpnStatus.Error || it is VpnStatus.Disconnected
                }
            }
            when (settled) {
                is VpnStatus.Connected -> Unit
                is VpnStatus.Error -> return@runBlocking errorBody(settled.message)
                else -> return@runBlocking errorBody("timeout")
            }
        }
        if (!startProxyBridge()) return@runBlocking errorBody("proxy-port-busy")
        status()
    }

    private fun disconnect(): String = runBlocking {
        bridge?.stop()
        bridge = null
        bridgeSocks = null
        vpnManager.stopVpn()
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            vpnManager.status.first { it is VpnStatus.Disconnected || it is VpnStatus.Error }
        }
        status()
    }

    /**
     * One HTTP proxy in front of the core's local SOCKS, on a port of our own so it never collides
     * with the one system-proxy mode raises. Rebuilt when the SOCKS endpoint moved (the "secured
     * SOCKS" toggle changes both the port and the credentials).
     */
    private fun startProxyBridge(): Boolean {
        val socks = vpnManager.socksProxySettings.value.normalized()
        val endpoint = socks.host to socks.port
        if (bridge != null && bridgeSocks == endpoint) return true
        bridge?.stop()
        val b = DesktopHttpProxyBridge(
            listenHost = LOOPBACK,
            listenPort = PROXY_PORT,
            socksHost = socks.host,
            socksPort = socks.port,
            socksUsername = socks.username,
            socksPassword = socks.password,
            log = log,
        )
        if (!b.start()) {
            bridge = null
            bridgeSocks = null
            return false
        }
        bridge = b
        bridgeSocks = endpoint
        log("Extension proxy on $LOOPBACK:$PROXY_PORT → SOCKS ${socks.host}:${socks.port}")
        return true
    }

    // ── payloads ───────────────────────────────────────────────────────────────

    private fun status(): String {
        val connected = vpnManager.status.value is VpnStatus.Connected
        val name = runCatching {
            runBlocking { repository.getActiveLocation()?.name }
        }.getOrNull().orEmpty()
        val proxy = if (connected && bridge != null) "$LOOPBACK:$PROXY_PORT" else ""
        return "{\"app\":\"YPtun\",\"version\":${jsonString(CurrentAppInfo.value.version)}," +
            "\"connected\":$connected,\"location\":${jsonString(name)},\"proxy\":${jsonString(proxy)}}"
    }

    private fun errorBody(code: String) = "{\"error\":${jsonString(code)}}"

    private fun reply(ex: HttpExchange, code: Int, body: String, origin: String) {
        val headers = ex.responseHeaders
        if (origin.isNotEmpty()) {
            headers.set("Access-Control-Allow-Origin", origin)
            headers.set("Access-Control-Allow-Headers", GUARD_HEADER)
            headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            headers.set("Access-Control-Max-Age", "600")
        }
        headers.set("Content-Type", "application/json; charset=utf-8")
        val bytes = body.encodeToByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /** Next to DesktopSingleInstance's 47638, clear of the app's own ports. */
        const val CONTROL_PORT = 47_639

        /** The HTTP proxy the extension points `chrome.proxy` at. */
        const val PROXY_PORT = 47_640

        /** Any request without it is refused — and asking for it is what forces the CORS preflight. */
        const val GUARD_HEADER = "X-YPtun-Extension"

        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 10_000L
    }
}
