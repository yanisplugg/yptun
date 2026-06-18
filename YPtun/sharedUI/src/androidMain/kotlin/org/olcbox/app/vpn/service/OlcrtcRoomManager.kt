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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Resilience: each room is SUPERVISED — if its initial connect fails it keeps retrying in the background
 * every [RETRY_FAILED_MS] until it comes up (or we stop); once up, the core's own self-heal keeps it
 * alive. The balancer PREFERS rooms the core reports healthy ([Mobile.roomHealthy]) so a new connection
 * isn't pinned onto a room that's mid-reconnect (the "frequent drop" cause), falling back to all known
 * rooms only if none report healthy yet — so we never go fully offline.
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

        // A room whose initial connect failed is retried this often, in the background, until it comes up
        // or the manager stops (per user: failed rooms must keep retrying ~every 60s).
        const val RETRY_FAILED_MS = 60_000L
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

    // Guards [ports] and [slotHandles]; [backendPorts] is the published @Volatile snapshot read on the
    // hot path. slotHandles maps port → room handle so the balancer can ask the core if that room is healthy.
    private val poolLock = Any()
    private val ports = mutableListOf<Int>()
    private val slotHandles = mutableMapOf<Int, Long>()

    val roomsUp: Int get() = handles.size

    /**
     * Starts [rooms] (capped at [maxRooms]) on consecutive loopback ports from [basePort], then the
     * balancer on [listenHost]:[listenPort].
     *
     * FAST CONNECT: returns as soon as the FIRST room is ready — the main one OR any extra, whichever
     * connects first — and starts the balancer over just that room. The REMAINING rooms keep connecting
     * on their own threads in the background and append their port to [backendPorts] (which is @Volatile
     * and re-read per connection) as each comes up, so the pool grows without restarting the balancer.
     * This avoids the old "wait for ALL rooms (up to readyTimeoutMs each) before connecting" stall.
     *
     * Returns true if at least one room came up and the balancer is listening. Rooms that fail to come
     * up are skipped (logged), not fatal.
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
        val specs = rooms.take(maxRooms)
        if (specs.isEmpty()) {
            log("multiroom: no room specs to start")
            return false
        }
        // Fires the instant ANY room is ready, so we start the balancer without waiting for the rest.
        val firstReady = CountDownLatch(1)
        // One SUPERVISOR per room (parallel, daemon): brings the room up and, if the initial connect
        // FAILS, keeps retrying every RETRY_FAILED_MS in the background until it succeeds or we stop.
        // Once up, the core's self-heal keeps the room alive and the supervisor exits.
        specs.forEachIndexed { i, r ->
            Thread { superviseRoom(i, r, basePort + i, user, pass, readyTimeoutMs, firstReady) }
                .apply { isDaemon = true; name = "olcrtc-room-${i + 1}" }
                .start()
        }

        // Wait only for the FIRST room. Each startRoom is bounded by readyTimeoutMs, so a small scheduling
        // grace guarantees that, if this times out, every room's first attempt has resolved and none came
        // up — abort so the caller falls back to single-room (no point retrying with no session up at all).
        val firstUp = firstReady.await((readyTimeoutMs + 2_000).toLong(), TimeUnit.MILLISECONDS)
        if (!firstUp) {
            log("multiroom: no rooms came up within ${readyTimeoutMs}ms — aborting")
            stop()
            return false
        }
        log("multiroom: first room up (${backendPorts.size} so far) — balancer up; others connect/retry in background")
        return startBalancer(listenHost, listenPort)
    }

    /**
     * Supervises ONE room on [port]: tries [Mobile.startRoom]; on success records its handle, joins the
     * balancer pool and signals [firstReady] (the core self-heals it from here, so we return); on failure
     * waits [RETRY_FAILED_MS] and retries until the room comes up or the manager stops.
     */
    private fun superviseRoom(
        index: Int,
        r: RoomSpec,
        port: Int,
        user: String,
        pass: String,
        readyTimeoutMs: Int,
        firstReady: CountDownLatch,
    ) {
        while (!stopped) {
            val handle = try {
                Mobile.startRoom(
                    r.carrier, r.transport, r.room, r.clientId, r.keyHex,
                    port.toLong(), user, pass, readyTimeoutMs.toLong(),
                )
            } catch (e: Exception) {
                if (stopped) return
                log("multiroom: room ${index + 1} (${r.carrier}/${r.room}) connect failed, retry in ${RETRY_FAILED_MS / 1000}s: ${e.message}")
                if (!sleepUnlessStopped(RETRY_FAILED_MS)) return
                continue
            }
            // Stopped while connecting → don't leave it dangling (stop() may have already drained the
            // registry, so a late arrival must stop ITSELF or it leaks).
            if (stopped) {
                runCatching { Mobile.stopRoom(handle) }
                return
            }
            synchronized(handles) { handles.add(handle) }
            synchronized(poolLock) {
                slotHandles[port] = handle
                if (!ports.contains(port)) ports.add(port)
                backendPorts = ports.sorted() // publish for the hot path
            }
            log("multiroom: room ${index + 1} (${r.carrier}/${r.room}) up on 127.0.0.1:$port (self-healing)")
            firstReady.countDown()
            return // up — the core self-heals from here; nothing more for the supervisor to do
        }
    }

    /** Sleeps [ms] in short slices, returning false as soon as [stopped] flips (so retries stop promptly). */
    private fun sleepUnlessStopped(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (stopped) return false
            val slice = minOf(500L, left)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                return !stopped
            }
            left -= slice
        }
        return !stopped
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

    /**
     * Picks a room to forward to, round-robin, PREFERRING rooms the core reports healthy (transport up,
     * recent liveness pong) so a connection isn't pinned onto a room that's mid-reconnect. Falls back to
     * all known rooms if none report healthy yet, so we never go fully offline. Returns the first room we
     * can actually open a socket to.
     */
    private fun connectToLiveRoom(host: String): Socket? {
        val all = backendPorts
        if (all.isEmpty()) return null
        val healthy = all.filter { p ->
            val h = synchronized(poolLock) { slotHandles[p] }
            h != null && runCatching { Mobile.roomHealthy(h) }.getOrDefault(true)
        }
        val candidates = if (healthy.isNotEmpty()) healthy else all
        repeat(candidates.size) {
            val idx = (rr.getAndIncrement() % candidates.size + candidates.size) % candidates.size
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, candidates[idx]), 4_000)
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
        synchronized(poolLock) {
            backendPorts = emptyList()
            ports.clear()
            slotHandles.clear()
        }
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
