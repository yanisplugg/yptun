package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.vpn.DesktopVpnManager
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension reaches the app over plain HTTP on loopback, so the guard that keeps web pages out
 * is the only thing between a random site and "connect me to this link". It is enforced twice — a
 * non-extension `Origin` is refused, and every real request must carry the custom header (which is
 * what forces a CORS preflight in the first place) — and both are checked here against the real
 * server.
 *
 * Spoken over a raw socket on purpose: `HttpURLConnection` silently DROPS `Origin` (it is one of the
 * JDK's restricted headers), so every origin assertion made through it passes for the wrong reason.
 *
 * Nothing here calls `/connect`: this runs against the machine's real location store, and a
 * link-less connect would raise the developer's actual tunnel mid-test.
 */
class DesktopExtensionBridgeTest {

    private val vpnManager = DesktopVpnManager(LocationsRepositoryImpl(JvmLocationsDataSourceImpl()))
    private val bridge = DesktopExtensionBridge(
        repository = LocationsRepositoryImpl(JvmLocationsDataSourceImpl()),
        vpnManager = vpnManager,
        log = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() {
        bridge.stop()
        vpnManager.close()
    }

    private val extensionOrigin = "chrome-extension://liobljilajeajepjcoppcjghalnlgcjf"

    /** Returns status code to body. */
    private fun request(
        path: String,
        method: String = "GET",
        origin: String? = extensionOrigin,
        guard: Boolean = true,
    ): Pair<Int, String> {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", DesktopExtensionBridge.CONTROL_PORT), 5_000)
            s.soTimeout = 5_000
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n")
                origin?.let { append("Origin: $it\r\n") }
                if (guard) append("${DesktopExtensionBridge.GUARD_HEADER}: 1\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(head.toByteArray())
            s.getOutputStream().flush()
            val raw = s.getInputStream().readBytes().decodeToString()
            val code = raw.substringAfter(' ').substringBefore(' ').toInt()
            return code to raw.substringAfter("\r\n\r\n", "")
        }
    }

    @Test
    fun statusAnswersTheExtension() {
        val (code, body) = request("/status")
        assertEquals(200, code)
        assertTrue(body.contains("\"app\":\"YPtun\""), body)
        assertTrue(body.contains("\"connected\":false"), body)
        // Nothing is running, so there is no proxy to point the browser at yet.
        assertTrue(body.contains("\"proxy\":\"\""), body)
    }

    @Test
    fun aWebPageIsRefused() {
        assertEquals(403, request("/status", origin = "https://evil.example.com").first)
    }

    @Test
    fun theGuardHeaderIsRequired() {
        assertEquals(403, request("/status", guard = false).first)
    }

    @Test
    fun preflightIsAnsweredForTheExtensionOnly() {
        assertEquals(204, request("/status", method = "OPTIONS", guard = false).first)
        assertEquals(
            403,
            request("/status", method = "OPTIONS", origin = "https://evil.example.com", guard = false).first,
        )
    }

    @Test
    fun anUnknownPathIs404() {
        assertEquals(404, request("/whatever").first)
    }
}
