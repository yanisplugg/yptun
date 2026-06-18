package org.olcbox.app.vpn.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.Mobile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-room olcRTC manager: raises up to N INDEPENDENT olcRTC room instances (different room/provider
 * each) via the core's handle-based [Mobile.startRoom], then fronts them with a tiny round-robin TCP
 * balancer on [start]'s listenPort (the port the TUN bridge dials). Each NEW connection is forwarded to
 * the next LIVE room's SOCKS listener, so parallel flows (speed-test / torrent / browser) spread across
 * rooms and the bandwidth aggregates — without splitting a single flow (no reordering).
 *
 * Security: every room SOCKS listener AND the balancer live on 127.0.0.1 ONLY and are reached with the
 * same user/pass the bridge already uses, so no other local app can ride the tunnel. The balancer is a
 * transparent byte pump — the SOCKS5 handshake (incl. auth) passes through to the room verbatim, which
 * is why the bridge, balancer and rooms all share one credential pair.
 *
 * Lifecycle: ALL coroutines run on a private [managerScope] and ALL live sockets are tracked, so [stop]
 * deterministically closes the listener, every in-flight forwarded connection AND every room — releasing
 * the bridge port immediately (otherwise the next connect, even a different vless engine, hits "SOCKS
 * port still in use").
 */
class OlcrtcRoomManager(
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

    private companion object {
        // Larger than the 8 KiB default so the byte pump does fewer syscalls at high throughput.
        const val COPY_BUFFER = 64 * 1024
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = mutableListOf<Long>()
    private var serverSocket: ServerSocket? = null
    // Every socket the manager owns (the accepted downstream + the upstream to a room) — closed on stop
    // so blocking copy loops unblock and the OS releases everything at once.
    private val liveSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var backendPorts: List<Int> = emptyList()

    @Volatile
    private var stopped = false
    private val rr = AtomicInteger(0)

    val roomsUp: Int get() = handles.size

    /**
     * Starts [rooms] (capped at [maxRooms]) on consecutive loopback ports from [basePort], then the
     * balancer on [listenHost]:[listenPort]. Returns true if at least one room is up and the balancer
     * is listening. Rooms that fail to come up are skipped (logged), not fatal.
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
        // Start every room IN PARALLEL: a room that can't connect must NOT block or break the others —
        // each blocks up to readyTimeoutMs, so sequential would stall the whole tunnel on one bad room.
        // We then run with whatever came up (failures are skipped, logged).
        val ports = Collections.synchronizedList(mutableListOf<Int>())
        val threads = rooms.take(maxRooms).mapIndexed { i, r ->
            val port = basePort + i
            Thread {
                try {
                    val h = Mobile.startRoom(
                        r.carrier, r.transport, r.room, r.clientId, r.keyHex,
                        port.toLong(), user, pass, readyTimeoutMs.toLong(),
                    )
                    synchronized(handles) { handles.add(h) }
                    ports.add(port)
                    log("multiroom: room ${i + 1} (${r.carrier}/${r.room}) ready on 127.0.0.1:$port")
                } catch (e: Exception) {
                    log("multiroom: room ${i + 1} (${r.carrier}/${r.room}) failed (skipped): ${e.message}")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        if (ports.isEmpty()) {
            log("multiroom: no rooms came up")
            return false
        }
        backendPorts = ports.toList().sorted()
        log("multiroom: ${ports.size}/${rooms.take(maxRooms).size} room(s) up")
        return startBalancer(listenHost, listenPort)
    }

    private fun startBalancer(host: String, port: Int): Boolean {
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(host, port))
            serverSocket = ss
            managerScope.launch {
                log("multiroom: round-robin balancer on $host:$port over ${backendPorts.size} room(s)")
                while (isActive && !stopped) {
                    val downstream = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    forward(downstream, host)
                }
            }
            true
        } catch (e: Exception) {
            log("multiroom: balancer failed to bind $host:$port: ${e.message}")
            false
        }
    }

    /** Pipes one accepted connection to a LIVE room's SOCKS (trying each room once), both ways. */
    private fun forward(downstream: Socket, host: String) {
        liveSockets.add(downstream)
        managerScope.launch {
            var upstream: Socket? = null
            try {
                upstream = connectToLiveRoom(host)
                if (upstream == null) {
                    log("multiroom: no live room to forward to")
                    return@launch
                }
                liveSockets.add(upstream)
                val u = upstream
                val a = launch {
                    runCatching { downstream.getInputStream().copyTo(u.getOutputStream(), COPY_BUFFER) }
                    runCatching { u.shutdownOutput() }
                }
                val b = launch {
                    runCatching { u.getInputStream().copyTo(downstream.getOutputStream(), COPY_BUFFER) }
                    runCatching { downstream.shutdownOutput() }
                }
                a.join(); b.join()
            } catch (_: Exception) {
                // drop the connection; the app retries
            } finally {
                upstream?.let { liveSockets.remove(it); runCatching { it.close() } }
                liveSockets.remove(downstream)
                runCatching { downstream.close() }
            }
        }
    }

    /** Round-robins across [backendPorts], returning the first room we can actually connect to. */
    private fun connectToLiveRoom(host: String): Socket? {
        val ports = backendPorts
        if (ports.isEmpty()) return null
        repeat(ports.size) {
            val idx = (rr.getAndIncrement() % ports.size + ports.size) % ports.size
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, ports[idx]), 4_000)
                return s
            } catch (_: Exception) {
                runCatching { s.close() }
            }
        }
        return null
    }

    /** Stops the balancer, every in-flight connection and every room. Idempotent; releases the port. */
    fun stop() {
        stopped = true
        backendPorts = emptyList()
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Close every tracked socket so blocking copy loops unblock immediately (coroutine cancel does
        // NOT interrupt blocking java.net IO).
        synchronized(liveSockets) {
            liveSockets.toList().forEach { runCatching { it.close() } }
            liveSockets.clear()
        }
        runCatching { managerScope.cancel() }
        if (handles.isNotEmpty()) {
            runCatching { Mobile.stopAllRooms() }
            handles.clear()
        }
    }
}
