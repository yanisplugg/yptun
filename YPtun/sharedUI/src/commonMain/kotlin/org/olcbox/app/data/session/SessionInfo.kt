package org.olcbox.app.data.session

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import kotlin.time.TimeSource

/** What the world sees of this connection: the exit address and who owns it. */
data class SessionInfo(
    val ipv4: String?,
    val ipv6: String?,
    val asn: String?,
    val provider: String?,
    val country: String?,
    val city: String?,
    val region: String?,
    val latitude: String?,
    val longitude: String?
)

/**
 * One look at the exit through [proxy] (the live tunnel; null = the default route). Runs only when the
 * user opens the «Сессия» sheet — nothing is requested in the background.
 */
suspend fun fetchSessionInfo(proxy: SubscriptionFetchProxy?): SessionInfo {
    if (proxy != null) {
        val result = runCatching {
            withProxyAuthentication(proxy) {
                val client = createProxyHttpClient(proxy, 6_000, 10_000, 10_000)
                try {
                    fetchSessionInfoInternal(client)
                } finally {
                    client.close()
                }
            }
        }
        if (result.isSuccess) {
            return result.getOrThrow()
        }
        val ex = result.exceptionOrNull()
        if (ex is CancellationException) throw ex
    }

    // Direct fallback (routes through the active VPN TUN interface if connected)
    val directClient = createProxyHttpClient(null, 6_000, 10_000, 10_000)
    try {
        return fetchSessionInfoInternal(directClient)
    } finally {
        directClient.close()
    }
}

private suspend fun fetchSessionInfoInternal(client: HttpClient): SessionInfo = coroutineScope {
    val v6Deferred = async {
        withTimeoutOrNull(4_000) {
            client.plainText("https://api6.ipify.org")?.takeIf { ":" in it }
        }
    }

    // 1. Discover IPv4 through multiple fast providers
    var v4: String? = withTimeoutOrNull(4_000) {
        client.plainText("https://api.ipify.org")?.takeIf { IPV4.matches(it) }
    }
    if (v4 == null) {
        v4 = withTimeoutOrNull(4_000) {
            client.plainText("https://icanhazip.com")?.takeIf { IPV4.matches(it) }
        }
    }
    if (v4 == null) {
        v4 = withTimeoutOrNull(4_000) {
            client.plainText("https://1.1.1.1/cdn-cgi/trace")?.let { trace ->
                trace.lineSequence()
                    .firstOrNull { it.startsWith("ip=") }
                    ?.removePrefix("ip=")
                    ?.trim()
                    ?.takeIf { IPV4.matches(it) }
            }
        }
    }

    // 2. Query geo / ASN / provider info
    var info: SessionInfo? = null

    // Provider A: ipwho.is
    try {
        val url = if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"
        val resp = withTimeoutOrNull(5_000) { client.get(url).bodyAsText() }
        if (resp != null && resp.trimStart().startsWith("{")) {
            val root = Json.parseToJsonElement(resp).jsonObject
            if (root["success"]?.jsonPrimitive?.boolean != false) {
                val conn = root["connection"] as? JsonObject
                fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val country = root.text("country")
                val code = root.text("country_code")
                val ip = root.text("ip")
                info = SessionInfo(
                    ipv4 = v4 ?: ip?.takeIf { ":" !in it },
                    ipv6 = v6Deferred.await() ?: ip?.takeIf { ":" in it },
                    asn = conn?.text("asn"),
                    provider = conn?.text("isp") ?: conn?.text("org"),
                    country = if (country != null && code != null) "$country ($code)" else country ?: code,
                    city = root.text("city"),
                    region = root.text("region"),
                    latitude = root.text("latitude"),
                    longitude = root.text("longitude")
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Fall through to next provider
    }

    // Provider B: ip-api.com (handles rate-limits, censored domains, or unauthenticated IPs)
    if (info == null || (info.provider == null && info.country == null)) {
        try {
            val url = if (v4 != null) "http://ip-api.com/json/$v4" else "http://ip-api.com/json/"
            val resp = withTimeoutOrNull(5_000) { client.get(url).bodyAsText() }
            if (resp != null && resp.trimStart().startsWith("{")) {
                val root = Json.parseToJsonElement(resp).jsonObject
                if (root["status"]?.jsonPrimitive?.contentOrNull == "success") {
                    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val country = root.text("country")
                    val code = root.text("countryCode")
                    val query = root.text("query")
                    val asString = root.text("as")
                    val asn = asString?.substringBefore(' ')?.takeIf { it.startsWith("AS", ignoreCase = true) }
                    val isp = root.text("isp")
                    val org = root.text("org")
                    val lat = root["lat"]?.jsonPrimitive?.contentOrNull
                    val lon = root["lon"]?.jsonPrimitive?.contentOrNull
                    info = SessionInfo(
                        ipv4 = v4 ?: query?.takeIf { ":" !in it },
                        ipv6 = v6Deferred.await() ?: query?.takeIf { ":" in it },
                        asn = asn,
                        provider = isp ?: org,
                        country = if (country != null && code != null) "$country ($code)" else country ?: code,
                        city = root.text("city"),
                        region = root.text("regionName"),
                        latitude = lat,
                        longitude = lon
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to minimal info
        }
    }

    // Provider C: Cloudflare trace fallback
    if (info == null) {
        try {
            val trace = withTimeoutOrNull(4_000) { client.plainText("https://1.1.1.1/cdn-cgi/trace") }
            if (trace != null) {
                val map = trace.lineSequence()
                    .mapNotNull {
                        val eq = it.indexOf('=')
                        if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
                    }.toMap()
                val traceIp = map["ip"]
                val loc = map["loc"]
                if (traceIp != null) {
                    info = SessionInfo(
                        ipv4 = v4 ?: traceIp.takeIf { ":" !in it },
                        ipv6 = v6Deferred.await() ?: traceIp.takeIf { ":" in it },
                        asn = null,
                        provider = if (map["warp"] == "on") "Cloudflare WARP" else null,
                        country = loc,
                        city = null,
                        region = null,
                        latitude = null,
                        longitude = null
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val finalV6 = v6Deferred.await()
    info ?: if (v4 != null || finalV6 != null) {
        SessionInfo(
            ipv4 = v4,
            ipv6 = finalV6,
            asn = null,
            provider = null,
            country = null,
            city = null,
            region = null,
            latitude = null,
            longitude = null
        )
    } else {
        throw IllegalStateException("Failed to determine session IP info")
    }
}

suspend fun fetchHostGeoInfo(host: String, proxy: SubscriptionFetchProxy?): SessionInfo? {
    val cleanHost = host.trim().trim('[', ']')
    if (cleanHost.isBlank() || cleanHost.equals("localhost", ignoreCase = true) || cleanHost == "127.0.0.1") {
        return null
    }
    return runCatching {
        if (proxy != null) {
            withProxyAuthentication(proxy) {
                val client = createProxyHttpClient(proxy, 6_000, 10_000, 10_000)
                try {
                    queryHostGeo(client, cleanHost)
                } finally {
                    client.close()
                }
            }
        } else {
            val client = createProxyHttpClient(null, 6_000, 10_000, 10_000)
            try {
                queryHostGeo(client, cleanHost)
            } finally {
                client.close()
            }
        }
    }.getOrNull()
}

private suspend fun queryHostGeo(client: HttpClient, host: String): SessionInfo? {
    try {
        val resp = withTimeoutOrNull(5_000) { client.get("https://ipwho.is/$host").bodyAsText() }
        if (resp != null && resp.trimStart().startsWith("{")) {
            val root = Json.parseToJsonElement(resp).jsonObject
            if (root["success"]?.jsonPrimitive?.boolean != false) {
                val conn = root["connection"] as? JsonObject
                fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val country = root.text("country")
                val code = root.text("country_code")
                val ip = root.text("ip")
                return SessionInfo(
                    ipv4 = ip?.takeIf { ":" !in it },
                    ipv6 = ip?.takeIf { ":" in it },
                    asn = conn?.text("asn"),
                    provider = conn?.text("isp") ?: conn?.text("org"),
                    country = if (country != null && code != null) "$country ($code)" else country ?: code,
                    city = root.text("city"),
                    region = root.text("region"),
                    latitude = root.text("latitude"),
                    longitude = root.text("longitude")
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {}

    try {
        val resp = withTimeoutOrNull(5_000) { client.get("http://ip-api.com/json/$host").bodyAsText() }
        if (resp != null && resp.trimStart().startsWith("{")) {
            val root = Json.parseToJsonElement(resp).jsonObject
            if (root["status"]?.jsonPrimitive?.contentOrNull == "success") {
                fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val country = root.text("country")
                val code = root.text("countryCode")
                val query = root.text("query")
                val asString = root.text("as")
                val asn = asString?.substringBefore(' ')?.takeIf { it.startsWith("AS", ignoreCase = true) }
                val isp = root.text("isp")
                val org = root.text("org")
                val lat = root["lat"]?.jsonPrimitive?.contentOrNull
                val lon = root["lon"]?.jsonPrimitive?.contentOrNull
                return SessionInfo(
                    ipv4 = query?.takeIf { ":" !in it },
                    ipv6 = query?.takeIf { ":" in it },
                    asn = asn,
                    provider = isp ?: org,
                    country = if (country != null && code != null) "$country ($code)" else country ?: code,
                    city = root.text("city"),
                    region = root.text("regionName"),
                    latitude = lat,
                    longitude = lon
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {}

    return null
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private suspend fun HttpClient.plainText(url: String): String? =
    runCatching { get(url).bodyAsText().trim() }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

enum class SpeedPhase { Idle, Ping, Download, Upload, Done, Failed }

data class SpeedProgress(
    val phase: SpeedPhase = SpeedPhase.Idle,
    val pingMs: Int? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null
)

private const val SPEED_HOST = "https://speed.cloudflare.com"
private const val PHASE_MS = 8_000L
private const val WARMUP_MS = 1_500L
private const val STREAMS = 4
private const val UP_CHUNK = 1024 * 1024

/** Ping, then download, then upload against Cloudflare through [proxy]; [onUpdate] fires as numbers move. */
suspend fun runSpeedTest(proxy: SubscriptionFetchProxy?, onUpdate: (SpeedProgress) -> Unit) {
    var p = SpeedProgress(SpeedPhase.Ping)
    fun push(next: SpeedProgress) { p = next; onUpdate(next) }
    push(p)
    if (proxy != null) {
        val result = runCatching {
            withProxyAuthentication(proxy) {
                val client = createProxyHttpClient(proxy, 8_000, 30_000, 30_000)
                try {
                    executeSpeedTest(client, p, ::push)
                } finally {
                    client.close()
                }
            }
        }
        if (result.isSuccess) return
        val ex = result.exceptionOrNull()
        if (ex is CancellationException) throw ex
    }
    // Direct fallback (via TUN interface)
    try {
        val directClient = createProxyHttpClient(null, 8_000, 30_000, 30_000)
        try {
            executeSpeedTest(directClient, p, ::push)
        } finally {
            directClient.close()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        push(p.copy(phase = SpeedPhase.Failed))
    }
}

private suspend fun executeSpeedTest(
    client: HttpClient,
    initial: SpeedProgress,
    push: (SpeedProgress) -> Unit
) {
    var p = initial
    val rtts = (1..4).map {
        val t = TimeSource.Monotonic.markNow()
        client.get("$SPEED_HOST/__down?bytes=0").bodyAsText()
        t.elapsedNow().inWholeMilliseconds.toInt()
    }
    p = p.copy(phase = SpeedPhase.Download, pingMs = rtts.min())
    push(p)

    // Several streams, like the web speed tests: one TCP stream through a tunnel never fills the link.
    // Cloudflare refuses a single download above ~25 MB (403), so each stream asks for 25 MB again and again.
    val down = measureRate({ p = p.copy(downMbps = it); push(p) }) { add, over ->
        val buf = ByteArray(64 * 1024)
        client.prepareGet("$SPEED_HOST/__down?bytes=25000000").execute { resp ->
            check(resp.status.value in 200..299) { "speed test: HTTP ${resp.status.value}" }
            val ch = resp.bodyAsChannel()
            while (!over()) {
                val n = ch.readAvailable(buf)
                if (n < 0) break
                add(n)
            }
        }
    }
    p = p.copy(phase = SpeedPhase.Upload, downMbps = down)
    push(p)

    val chunk = ByteArray(UP_CHUNK)
    val up = measureRate({ p = p.copy(upMbps = it); push(p) }) { add, _ ->
        check(client.post("$SPEED_HOST/__up") { setBody(chunk) }.status.value in 200..299) { "speed test: upload rejected" }
        add(chunk.size)
    }
    p = p.copy(phase = SpeedPhase.Done, upMbps = up)
    push(p)
}

/**
 * Runs [STREAMS] copies of [worker] for [PHASE_MS], each looping until the window ends and reporting bytes
 * through `add`; [onRate] gets Mbit/s as it moves. The first [WARMUP_MS] (TCP slow start, TLS) don't count.
 * All counting happens on one thread, so the shared totals need no locks.
 */
private suspend fun measureRate(
    onRate: (Double) -> Unit,
    worker: suspend (add: (Int) -> Unit, over: () -> Boolean) -> Unit
): Double = withContext(Dispatchers.Default.limitedParallelism(1)) {
    val t = TimeSource.Monotonic.markNow()
    var total = 0L
    var base = -1L
    var baseMs = 0L
    var lastPush = 0L
    fun rate(ms: Long) = if (base >= 0 && ms > baseMs) (total - base) * 8.0 / 1000.0 / (ms - baseMs)
        else total * 8.0 / 1000.0 / ms.coerceAtLeast(1)
    val over = { t.elapsedNow().inWholeMilliseconds >= PHASE_MS }
    val add = { n: Int ->
        total += n
        val ms = t.elapsedNow().inWholeMilliseconds
        if (base < 0 && ms >= WARMUP_MS) { base = total; baseMs = ms }
        if (base >= 0 && ms - lastPush >= 250) { lastPush = ms; onRate(rate(ms)) }
    }
    coroutineScope {
        repeat(STREAMS) { launch { while (!over()) worker(add, over) } }
    }
    rate(t.elapsedNow().inWholeMilliseconds)
}
