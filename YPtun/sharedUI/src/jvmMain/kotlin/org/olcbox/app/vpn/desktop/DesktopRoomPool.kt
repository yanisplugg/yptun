package org.olcbox.app.vpn.desktop

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop twin of Android's OlcrtcRoomManager (round-robin only): raises up to N INDEPENDENT olcRTC rooms
 * through yptuncore's handle-based `YpRtcStartRoom` and fronts them with a tiny TCP balancer on
 * `listenHost:listenPort`. Each NEW connection goes to the next LIVE room's SOCKS, so parallel flows
 * spread over the rooms and the bandwidth adds up; one flow is never split (no reordering).
 *
 * Every room listens on an OS-assigned loopback port and is password-protected; the balancer is a
 * transparent byte pump, so the SOCKS5 handshake (auth included) reaches the room verbatim and the
 * bridge, balancer and rooms all share one credential pair.
 *
 * FAST CONNECT: [start] returns as soon as the FIRST room is up; the others keep connecting (and
 * retrying every [RETRY_FAILED_MS]) in the background and join the pool as they come up. Rooms
 * self-heal inside the core once up. The balancer prefers rooms the core reports healthy.
 * ponytail: no Stage-2 bond on desktop (needs the server-side reassembler); add when it is deployed.
 */
internal class DesktopRoomPool(private val log: (String) -> Unit) {
    data class RoomSpec(
        val carrier: String,
        val transport: String,
        val room: String,
        val clientId: String,
        val keyHex: String,
    )

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "olcrtc-room-io").apply { isDaemon = true } }
    private val liveSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val poolLock = Any()
    private val handles = mutableListOf<Long>()
    private val slotHandles = mutableMapOf<Int, Long>() // loopback port -> room handle

    @Volatile private var backendPorts: List<Int> = emptyList()
    @Volatile private var stopped = false
    private var serverSocket: ServerSocket? = null
    private val rr = AtomicInteger(0)

    /** Rooms currently registered in the core. */
    val roomsUp: Int get() = YpTunCore.rtcRoomsRunning()

    /** True if at least one room came up and the balancer is listening. */
    fun start(
        rooms: List<RoomSpec>,
        listenHost: String,
        listenPort: Int,
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
        val firstReady = CountDownLatch(1)
        specs.forEachIndexed { i, r ->
            Thread { superviseRoom(i, r, user, pass, readyTimeoutMs, firstReady) }
                .apply { isDaemon = true; name = "olcrtc-room-${i + 1}" }
                .start()
        }
        // Every room's first attempt is bounded by readyTimeoutMs, so a timeout here means none came up.
        if (!firstReady.await((readyTimeoutMs + 2_000).toLong(), TimeUnit.MILLISECONDS)) {
            log("multiroom: no rooms came up within ${readyTimeoutMs}ms — aborting")
            stop()
            return false
        }
        log("multiroom: first room up (${backendPorts.size} so far) — balancer up; others connect/retry in background")
        return startBalancer(listenHost, listenPort)
    }

    private fun superviseRoom(index: Int, r: RoomSpec, user: String, pass: String, readyTimeoutMs: Int, firstReady: CountDownLatch) {
        while (!stopped) {
            val handle = YpTunCore.rtcStartRoom(r.carrier, r.transport, r.room, r.clientId, r.keyHex, user, pass, readyTimeoutMs)
            if (handle <= 0) {
                if (stopped) return
                log("multiroom: room ${index + 1} (${r.carrier}/${r.room}) connect failed, retry in ${RETRY_FAILED_MS / 1000}s")
                if (!sleepUnlessStopped(RETRY_FAILED_MS)) return
                continue
            }
            // Stopped while connecting: stop() may already have drained the core's registry, so a late
            // arrival must stop ITSELF or it leaks.
            if (stopped) {
                YpTunCore.rtcStopRoom(handle)
                return
            }
            val port = YpTunCore.rtcRoomPort(handle)
            if (port <= 0) {
                log("multiroom: room ${index + 1} (${r.carrier}/${r.room}) came up but reported no port — retry in ${RETRY_FAILED_MS / 1000}s")
                YpTunCore.rtcStopRoom(handle)
                if (!sleepUnlessStopped(RETRY_FAILED_MS)) return
                continue
            }
            synchronized(poolLock) {
                handles.add(handle)
                slotHandles[port] = handle
                backendPorts = slotHandles.keys.sorted() // publish for the hot path
            }
            log("multiroom: room ${index + 1} (${r.carrier}/${r.room}) up on 127.0.0.1:$port (self-healing)")
            firstReady.countDown()
            return
        }
    }

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

    private fun startBalancer(host: String, port: Int): Boolean = try {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(host, port))
        serverSocket = ss
        Thread {
            log("multiroom: round-robin balancer on $host:$port over ${backendPorts.size} room(s)")
            while (!stopped) {
                val downstream = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                forward(downstream)
            }
        }.apply { isDaemon = true; name = "olcrtc-balancer" }.start()
        true
    } catch (e: Exception) {
        log("multiroom: balancer failed to bind $host:$port: ${e.message}")
        false
    }

    /** Pipes one accepted connection to a LIVE room's SOCKS, both ways. */
    private fun forward(downstream: Socket) {
        liveSockets.add(downstream)
        io.execute {
            var upstream: Socket? = null
            try {
                upstream = connectToLiveRoom()
                if (upstream == null) {
                    log("multiroom: no live room to forward to")
                    return@execute
                }
                liveSockets.add(upstream)
                val up = upstream
                val back = io.submit {
                    runCatching { up.getInputStream().copyTo(downstream.getOutputStream(), COPY_BUFFER) }
                    runCatching { downstream.shutdownOutput() }
                }
                runCatching { downstream.getInputStream().copyTo(up.getOutputStream(), COPY_BUFFER) }
                runCatching { up.shutdownOutput() }
                runCatching { back.get() }
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
     * Round-robin over the rooms the core reports healthy (so a connection isn't pinned onto a room that
     * is mid-reconnect), falling back to all known rooms when none is — never fully offline.
     */
    private fun connectToLiveRoom(): Socket? {
        val all = backendPorts
        if (all.isEmpty()) return null
        val healthy = all.filter { p ->
            val h = synchronized(poolLock) { slotHandles[p] }
            h != null && YpTunCore.rtcRoomHealthy(h)
        }
        val candidates = healthy.ifEmpty { all }
        repeat(candidates.size) {
            val idx = (rr.getAndIncrement() % candidates.size + candidates.size) % candidates.size
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", candidates[idx]), 4_000)
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
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Blocking copy loops only unblock when their socket closes.
        synchronized(liveSockets) {
            liveSockets.toList().forEach { runCatching { it.close() } }
            liveSockets.clear()
        }
        val hadRooms = synchronized(poolLock) {
            backendPorts = emptyList()
            slotHandles.clear()
            handles.isNotEmpty().also { handles.clear() }
        }
        if (hadRooms) YpTunCore.rtcStopAllRooms()
        io.shutdownNow()
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024

        /** A room whose initial connect failed is retried this often, in the background. */
        const val RETRY_FAILED_MS = 60_000L
    }
}
