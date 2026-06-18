package org.olcbox.app.vpn.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.Mobile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-room olcRTC manager: raises up to N INDEPENDENT olcRTC room instances (different room/provider
 * each) via the core's handle-based [Mobile.startRoom], then fronts them with a tiny round-robin TCP
 * balancer on [listenPort] (the port the TUN bridge dials). Each NEW connection is forwarded to the
 * next room's SOCKS listener, so parallel flows (speed-test / torrent / browser) spread across rooms
 * and the bandwidth aggregates — without splitting a single flow (no reordering).
 *
 * Security: every room SOCKS listener AND the balancer live on 127.0.0.1 ONLY and are reached with the
 * same [user]/[pass] the bridge already uses, so no other local app can ride the tunnel. The balancer
 * is a transparent byte pump — the SOCKS5 handshake (incl. auth) passes through to the room verbatim,
 * which is why the bridge, balancer and rooms all share one credential pair.
 */
class OlcrtcRoomManager(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    /** One olcRTC room to raise. */
    data class RoomSpec(
        val carrier: String,
        val transport: String,
        val room: String,
        val clientId: String,
        val keyHex: String,
    )

    private val handles = mutableListOf<Long>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var backendPorts: List<Int> = emptyList()
    private val rr = AtomicInteger(0)

    /** Number of rooms that actually came up. */
    val roomsUp: Int get() = handles.size

    /**
     * Starts [rooms] (capped at [maxRooms]) on consecutive loopback ports from [basePort], then the
     * balancer on [listenHost]:[listenPort]. Returns true if at least one room is up and the balancer
     * is listening. Best-effort: rooms that fail to come up are skipped (logged), not fatal.
     */
    fun start(
        rooms: List<RoomSpec>,
        listenHost: String,
        listenPort: Int,
        basePort: Int,
        user: String,
        pass: String,
        maxRooms: Int = 5,
        readyTimeoutMs: Int = 20_000,
    ): Boolean {
        val ports = mutableListOf<Int>()
        rooms.take(maxRooms).forEachIndexed { i, r ->
            val port = basePort + i
            try {
                val h = Mobile.startRoom(
                    r.carrier, r.transport, r.room, r.clientId, r.keyHex,
                    port.toLong(), user, pass, readyTimeoutMs.toLong(),
                )
                handles.add(h)
                ports.add(port)
                log("multiroom: room ${i + 1} (${r.carrier}/${r.room}) ready on 127.0.0.1:$port")
            } catch (e: Exception) {
                log("multiroom: room ${i + 1} (${r.carrier}/${r.room}) failed: ${e.message}")
            }
        }
        if (ports.isEmpty()) {
            log("multiroom: no rooms came up")
            return false
        }
        backendPorts = ports
        return startBalancer(listenHost, listenPort)
    }

    private fun startBalancer(host: String, port: Int): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(host, port))
            serverSocket = ss
            acceptJob = scope.launch(Dispatchers.IO) {
                log("multiroom: round-robin balancer on $host:$port over ${backendPorts.size} room(s)")
                while (isActive) {
                    val downstream = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val ports = backendPorts
                    if (ports.isEmpty()) {
                        runCatching { downstream.close() }
                        continue
                    }
                    val idx = (rr.getAndIncrement() % ports.size + ports.size) % ports.size
                    forward(downstream, host, ports[idx])
                }
            }
            true
        } catch (e: Exception) {
            log("multiroom: balancer failed to bind $host:$port: ${e.message}")
            false
        }
    }

    /** Pipes one accepted connection to a room's SOCKS, both directions, then closes both ends. */
    private fun forward(downstream: Socket, host: String, backendPort: Int) {
        scope.launch(Dispatchers.IO) {
            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(host, backendPort), 5_000)
                val a = launch(Dispatchers.IO) {
                    runCatching { downstream.getInputStream().copyTo(upstream.getOutputStream()) }
                    runCatching { upstream.shutdownOutput() }
                }
                val b = launch(Dispatchers.IO) {
                    runCatching { upstream.getInputStream().copyTo(downstream.getOutputStream()) }
                    runCatching { downstream.shutdownOutput() }
                }
                a.join(); b.join()
            } catch (_: Exception) {
                // connect/copy failure — drop the connection (the app retries).
            } finally {
                runCatching { upstream.close() }
                runCatching { downstream.close() }
            }
        }
    }

    /** Stops the balancer and every room instance. Safe to call multiple times. */
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        backendPorts = emptyList()
        if (handles.isNotEmpty()) {
            // StopAllRooms cancels every handle in one core call (also catches any we lost track of).
            runCatching { Mobile.stopAllRooms() }
            handles.clear()
        }
    }
}
