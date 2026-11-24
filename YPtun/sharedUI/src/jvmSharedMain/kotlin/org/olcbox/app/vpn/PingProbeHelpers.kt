package org.olcbox.app.vpn

import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.vpn.xray.XrayConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared JVM/Android network ping helpers (IPv4-prioritized multi-address TCP, TLS SNI handshake,
 * and live-SOCKS Xray ping config builder).
 *
 * Dual-stack hostnames otherwise flip between IPv4 and IPv6 across [InetAddress.getByName] calls,
 * causing spurious "Offline" reports on networks without working IPv6.
 */
object PingProbeHelpers {

    private val insecureTlsFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }.socketFactory
    }

    internal fun resolveAddressesPreferIpv4(host: String): List<InetAddress> {
        val trimmed = host.trim().trim('[', ']')
        if (trimmed.isEmpty()) return emptyList()
        val all = InetAddress.getAllByName(trimmed).toList()
        val v4 = all.filterIsInstance<Inet4Address>()
        val other = all.filterNot { it is Inet4Address }
        return v4 + other
    }

    internal fun isIpLiteral(host: String): Boolean {
        val h = host.trim().trim('[', ']')
        if (h.isEmpty()) return false
        if (':' in h) return true
        val parts = h.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    fun tcpPing(host: String?, port: Int?, timeoutMs: Int, attempts: Int): Long? {
        if (host.isNullOrBlank() || port == null || port !in 1..65535) return null
        val addrs = runCatching { resolveAddressesPreferIpv4(host) }.getOrNull().orEmpty()
        if (addrs.isEmpty()) return null
        var best: Long? = null
        repeat(attempts) {
            for (addr in addrs) {
                val elapsed = runCatching {
                    Socket().use { socket ->
                        val start = System.nanoTime()
                        socket.connect(InetSocketAddress(addr, port), timeoutMs)
                        ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                    }
                }.getOrNull()
                if (elapsed != null) {
                    if (best == null || elapsed < best!!) best = elapsed
                    break
                }
            }
        }
        return best
    }

    fun tlsPing(
        host: String?,
        port: Int?,
        sni: String?,
        allowInsecure: Boolean,
        timeoutMs: Int,
        attempts: Int,
    ): Long? {
        if (host.isNullOrBlank() || port == null || port !in 1..65535) return null
        val addrs = runCatching { resolveAddressesPreferIpv4(host) }.getOrNull().orEmpty()
        if (addrs.isEmpty()) return null
        val effectiveSni = sni?.trim().orEmpty().ifBlank { host.trim() }
        val factory = if (allowInsecure) insecureTlsFactory else SSLSocketFactory.getDefault() as SSLSocketFactory
        var best: Long? = null
        repeat(attempts) {
            for (addr in addrs) {
                val elapsed = runCatching {
                    val start = System.nanoTime()
                    Socket().use { raw ->
                        raw.connect(InetSocketAddress(addr, port), timeoutMs)
                        raw.soTimeout = timeoutMs
                        val tls = factory.createSocket(raw, effectiveSni, port, true) as SSLSocket
                        tls.use { sock ->
                            sock.soTimeout = timeoutMs
                            if (!allowInsecure && !isIpLiteral(effectiveSni)) {
                                sock.sslParameters = sock.sslParameters.apply {
                                    serverNames = listOf(SNIHostName(effectiveSni))
                                    endpointIdentificationAlgorithm = "HTTPS"
                                }
                            } else if (!isIpLiteral(effectiveSni)) {
                                sock.sslParameters = sock.sslParameters.apply {
                                    serverNames = listOf(SNIHostName(effectiveSni))
                                }
                            }
                            sock.startHandshake()
                        }
                    }
                    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                }.getOrNull()
                if (elapsed != null) {
                    if (best == null || elapsed < best!!) best = elapsed
                    break
                }
            }
        }
        return best
    }

    fun buildLiveSocksPingConfig(
        tag: String,
        host: String,
        port: Int,
        username: String = "",
        password: String = "",
    ): String = XrayConfig.build(
        profile = ProxyProfile(
            tag = tag,
            type = "socks",
            server = host,
            serverPort = port,
            username = username,
            password = password,
        ),
        listenPort = (20_000..60_000).random(),
        listenHost = "127.0.0.1",
        logLevel = "none",
        blockQuic = false,
    )
}
