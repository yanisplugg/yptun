package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class PacServer(
    private val host: String = PAC_HOST,
    private val port: Int = PAC_PORT
) {
    private var server: HttpServer? = null
    @Volatile
    private var socksTarget = SocksTarget(LOCAL_SOCKS_HOST, LOCAL_SOCKS_PORT, "", "")
    private var executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
    }

    val url: String
        get() = "http://$host:$port/proxy.pac"

    fun start(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = ""
    ) {
        updateSocksTarget(socksHost, socksPort, socksUsername, socksPassword)
        if (server != null) return
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OlcboxPacServer").apply { isDaemon = true }
        }
        server = HttpServer.create(InetSocketAddress(host, port), 0).also { httpServer ->
            httpServer.createContext("/proxy.pac") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.createContext("/") { exchange ->
                exchange.respond(currentPacContent())
            }
            httpServer.executor = executor
            httpServer.start()
        }
    }

    fun updateSocksTarget(
        socksHost: String = LOCAL_SOCKS_HOST,
        socksPort: Int = LOCAL_SOCKS_PORT,
        socksUsername: String = "",
        socksPassword: String = ""
    ) {
        socksTarget = SocksTarget(
            host = socksHost.ifBlank { LOCAL_SOCKS_HOST },
            port = socksPort,
            username = socksUsername,
            password = socksPassword
        )
    }

    internal fun currentPacContent(): String {
        val target = socksTarget
        return generatePac(target.host, target.port, target.username, target.password)
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor.shutdownNow()
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/x-ns-proxy-autoconfig; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class SocksTarget(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    companion object {
        const val PAC_HOST = "127.0.0.1"
        const val PAC_PORT = 10809
        const val LOCAL_SOCKS_HOST = "127.0.0.1"
        const val LOCAL_SOCKS_PORT = 10808

        fun generatePac(
            socksHost: String = LOCAL_SOCKS_HOST,
            socksPort: Int = LOCAL_SOCKS_PORT,
            socksUsername: String = "",
            socksPassword: String = ""
        ): String {
            val target = socksProxyTarget(socksHost, socksPort, socksUsername, socksPassword)
            return """
                function FindProxyForURL(url, host) {
                  if (isPlainHostName(host)) return "DIRECT";
                  if (host == "localhost" || host == "127.0.0.1" || host == "::1") return "DIRECT";
                  if (shExpMatch(host, "127.*")) return "DIRECT";
                  return "SOCKS5 $target; SOCKS $target";
                }
            """.trimIndent()
        }

        private fun socksProxyTarget(
            socksHost: String,
            socksPort: Int,
            socksUsername: String,
            socksPassword: String
        ): String {
            val hostPort = "${socksHost.ifBlank { LOCAL_SOCKS_HOST }}:$socksPort"
            if (socksUsername.isBlank()) return hostPort

            val userInfo = buildString {
                append(socksUsername.percentEncodeUserInfo())
                if (socksPassword.isNotEmpty()) {
                    append(':')
                    append(socksPassword.percentEncodeUserInfo())
                }
            }
            return "$userInfo@$hostPort"
        }

        private fun String.percentEncodeUserInfo(): String {
            val bytes = toByteArray(StandardCharsets.UTF_8)
            return buildString(bytes.size) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    val char = value.toChar()
                    if (char.isUnreservedUserInfoChar()) {
                        append(char)
                    } else {
                        append('%')
                        append(value.toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        }

        private fun Char.isUnreservedUserInfoChar(): Boolean {
            return this in 'A'..'Z' ||
                    this in 'a'..'z' ||
                    this in '0'..'9' ||
                    this == '-' ||
                    this == '.' ||
                    this == '_' ||
                    this == '~'
        }
    }
}
