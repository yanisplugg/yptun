package org.olcbox.app.desktop

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Keeps ONE YPtun running per machine.
 *
 * Launching the .exe again used to start a second, complete copy of the app: two trays, two engine
 * controllers, and both fighting over the same local SOCKS port and the same settings files — which
 * is what the user saw as "дальше открываются дубли если много раз .exe подрубать". The installed
 * build and the portable share those resources too, so this is not just about double-clicking one
 * shortcut.
 *
 * A loopback listener is the guard: whoever binds [PORT] first owns the app, and every later launch
 * finds the port taken, tells the owner to show its window (handing over a deep link it was opened
 * with, if any), and exits. A lock FILE would not do — a killed process leaves a stale one behind,
 * while a socket is released by the OS the instant the owner dies.
 */
object DesktopSingleInstance {

    /**
     * Fixed loopback port, in the IANA dynamic range and clear of the app's own ports (10808 SOCKS,
     * 10809 PAC, 10812 HTTP bridge, +6/+7 the Xray front).
     */
    private const val PORT = 47_638

    private const val SHOW_COMMAND = "show"

    @Volatile private var listener: ServerSocket? = null

    /**
     * Claims ownership. Returns true when this process is the one instance and may continue; false
     * when another copy is already running (it has been told to show itself — and to import the
     * link in [args], if there is one — and this process must exit immediately, without a window).
     *
     * [onShowRequested] is called — off the UI thread — whenever a later launch asks for the window,
     * with the link it was opened with or null.
     */
    fun claim(args: Array<String>, onShowRequested: (link: String?) -> Unit): Boolean {
        val server = tryBind() ?: run {
            notifyOwner(linkArgument(args)?.let { "$SHOW_COMMAND $it" } ?: SHOW_COMMAND)
            return false
        }
        startListening(server, onShowRequested)
        return true
    }

    /**
     * Waits for the port to come free, then claims it. Used by the copy that is relaunching itself
     * elevated: the old process is still alive for a moment, and it must NOT be mistaken for a
     * duplicate — it is the very process being replaced.
     */
    fun claimAfterPredecessorExits(onShowRequested: (link: String?) -> Unit, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val server = tryBind() ?: run {
                Thread.sleep(200)
                null
            } ?: continue
            startListening(server, onShowRequested)
            return true
        }
        // The predecessor never let go. Run anyway: refusing to start would be worse than two copies.
        return true
    }

    /** The share link the app was launched with (a registered URL scheme handler passes it as an argument). */
    fun linkArgument(args: Array<String>): String? = args.firstOrNull { "://" in it }

    /** Releases the port so a successor (the elevated relaunch) can take over straight away. */
    fun release() {
        runCatching { listener?.close() }
        listener = null
    }

    private fun tryBind(): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = false // MUST fail while another instance holds the port
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))
        }
    } catch (e: IOException) {
        null
    }

    private fun startListening(server: ServerSocket, onShowRequested: (String?) -> Unit) {
        listener = server
        Thread({ acceptLoop(server, onShowRequested) }, "YPtunSingleInstance").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket, onShowRequested: (String?) -> Unit) {
        while (true) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                return // released
            }
            runCatching {
                client.use {
                    it.soTimeout = 2_000
                    val line = it.getInputStream().bufferedReader(Charsets.UTF_8).readLine()?.trim()
                    // Strictly "show" or "show <link>" with ONE space-free link: anything can connect
                    // to a loopback port — a web page's fetch() to 127.0.0.1 included, whose request
                    // line always carries " HTTP/1.1" — and must not get a server imported this way.
                    val link = line?.removePrefix("$SHOW_COMMAND ")?.takeIf { it != line }
                    when {
                        line == SHOW_COMMAND -> onShowRequested(null)
                        link != null && "://" in link && ' ' !in link -> onShowRequested(link)
                    }
                }
            }
        }
    }

    /** Best-effort "you are already running, come to the front". */
    private fun notifyOwner(command: String) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 2_000)
                socket.getOutputStream().write("$command\n".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        }
    }
}
