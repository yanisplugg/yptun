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
suspend fun fetchSessionInfo(proxy: SubscriptionFetchProxy?): SessionInfo = withProxyAuthentication(proxy) {
    val client = createProxyHttpClient(proxy, 6_000, 10_000, 10_000)
    try {
        coroutineScope {
            val v6 = async { withTimeoutOrNull(5_000) { client.plainText("https://api6.ipify.org") }?.takeIf { ":" in it } }
            val v4 = client.plainText("https://api.ipify.org")?.takeIf { IPV4.matches(it) }
            // Without an IPv4 the lookup falls back to whatever address the request arrives from.
            val root = Json.parseToJsonElement(client.get("https://ipwho.is/${v4.orEmpty()}").bodyAsText()).jsonObject
            check(root["success"]?.jsonPrimitive?.boolean != false) { root["message"]?.jsonPrimitive?.contentOrNull ?: "lookup failed" }
            val conn = root["connection"] as? JsonObject
            fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val country = root.text("country")
            val code = root.text("country_code")
            SessionInfo(
                ipv4 = v4 ?: root.text("ip")?.takeIf { ":" !in it },
                ipv6 = v6.await() ?: root.text("ip")?.takeIf { ":" in it },
                asn = conn?.text("asn"),
                provider = conn?.text("isp") ?: conn?.text("org"),
                country = if (country != null && code != null) "$country ($code)" else country ?: code,
                city = root.text("city"),
                region = root.text("region"),
                latitude = root.text("latitude"),
                longitude = root.text("longitude")
            )
        }
    } finally {
        client.close()
    }
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
    withProxyAuthentication(proxy) {
        val client = createProxyHttpClient(proxy, 8_000, 30_000, 30_000)
        try {
            val rtts = (1..4).map {
                val t = TimeSource.Monotonic.markNow()
                client.get("$SPEED_HOST/__down?bytes=0").bodyAsText()
                t.elapsedNow().inWholeMilliseconds.toInt()
            }
            push(p.copy(phase = SpeedPhase.Download, pingMs = rtts.min()))

            // Several streams, like the web speed tests: one TCP stream through a tunnel never fills the link.
            // Cloudflare refuses a single download above ~25 MB (403), so each stream asks for 25 MB again and again.
            val down = measureRate({ push(p.copy(downMbps = it)) }) { add, over ->
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
            push(p.copy(phase = SpeedPhase.Upload, downMbps = down))

            val chunk = ByteArray(UP_CHUNK)
            val up = measureRate({ push(p.copy(upMbps = it)) }) { add, _ ->
                check(client.post("$SPEED_HOST/__up") { setBody(chunk) }.status.value in 200..299) { "speed test: upload rejected" }
                add(chunk.size)
            }
            push(p.copy(upMbps = up))
            push(p.copy(phase = SpeedPhase.Done))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            push(p.copy(phase = SpeedPhase.Failed))
        } finally {
            client.close()
        }
    }
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
