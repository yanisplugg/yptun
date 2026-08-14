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
 * finds the port taken, tells the owner to show its window, and exits. A lock FILE would not do —
 * a killed process leaves a stale one behind, while a socket is released by the OS the instant the
 * owner dies.
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
     * when another copy is already running (it has been told to show itself and this process must
     * exit immediately, without a window).
     *
     * [onShowRequested] is called — off the UI thread — whenever a later launch asks for the window.
     */
    fun claim(onShowRequested: () -> Unit): Boolean {
        val loopback = InetAddress.getLoopbackAddress()
        val server = try {
            ServerSocket().apply {
                reuseAddress = false // MUST fail while another instance holds the port
                bind(InetSocketAddress(loopback, PORT))
            }
        } catch (e: IOException) {
            notifyOwner()
            return false
        }
        listener = server
        Thread({ acceptLoop(server, onShowRequested) }, "YPtunSingleInstance").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Waits for the port to come free, then claims it. Used by the copy that is relaunching itself
     * elevated: the old process is still alive for a moment, and it must NOT be mistaken for a
     * duplicate — it is the very process being replaced.
     */
    fun claimAfterPredecessorExits(onShowRequested: () -> Unit, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val loopback = InetAddress.getLoopbackAddress()
            val server = try {
                ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(loopback, PORT))
                }
            } catch (e: IOException) {
                Thread.sleep(200)
                continue
            }
            listener = server
            Thread({ acceptLoop(server, onShowRequested) }, "YPtunSingleInstance").apply {
                isDaemon = true
                start()
            }
            return true
        }
        // The predecessor never let go. Run anyway: refusing to start would be worse than two copies.
        return true
    }

    /** Releases the port so a successor (the elevated relaunch) can take over straight away. */
    fun release() {
        runCatching { listener?.close() }
        listener = null
    }

    private fun acceptLoop(server: ServerSocket, onShowRequested: () -> Unit) {
        while (true) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                return // released
            }
            runCatching {
                client.use {
                    it.soTimeout = 2_000
                    val line = it.getInputStream().bufferedReader().readLine()
                    if (line?.trim() == SHOW_COMMAND) onShowRequested()
                }
            }
        }
    }

    /** Best-effort "you are already running, come to the front". */
    private fun notifyOwner() {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 2_000)
                socket.getOutputStream().write("$SHOW_COMMAND\n".toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
            }
        }
    }
}
