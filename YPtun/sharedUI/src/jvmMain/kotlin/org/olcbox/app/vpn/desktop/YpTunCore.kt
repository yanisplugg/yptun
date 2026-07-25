package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

/**
 * JNA mapping of `yptuncore` — the desktop c-shared build of every YPtun proxy core
 * (sing-box, xray, AmneziaWG, Hysteria2, VK-TURN/freeturn, olcrtc) in one shared Go runtime,
 * mirroring the Android gomobile AAR. Built from `cores/cmd/yptuncore`.
 *
 * Functions returning [Pointer] return a C string allocated by the Go side (NULL = success /
 * no data) that MUST be released with [YpFree] — use [YpTunCore.takeString].
 */
internal interface YpTunCoreLib : Library {
    fun YpFree(p: Pointer?)
    fun YpPollLog(timeoutMs: Int): Pointer?

    fun YpSbVersion(): Pointer?
    fun YpSbStart(configJson: String): Pointer?
    fun YpSbStop()
    fun YpSbRunning(): Int

    fun YpXraySetAssetPath(dir: String)
    fun YpXrayVersion(): Pointer?
    fun YpXrayStart(configJson: String): Pointer?
    fun YpXrayStop()
    fun YpXrayRunning(): Int
    fun YpXrayMeasureDelay(configJson: String, url: String, method: String, timeoutMs: Int): Long

    fun YpAwgStart(iniConfig: String, listenAddr: String): Pointer?
    fun YpAwgStop()
    fun YpAwgRunning(): Int
    fun YpAwgProbe(iniConfig: String): Long
    fun YpAwgMeasureDelay(iniConfig: String, url: String, method: String, timeoutMs: Int): Long
    fun YpAwgGenerateKeyPair(): Pointer?

    fun YpTgAwgStart(iniConfig: String, listenAddr: String, user: String, pass: String): Pointer?
    fun YpTgAwgStop()
    fun YpTgAwgRunning(): Int

    fun YpFtVersion(): Pointer?
    fun YpFtStart(uri: String, listenAddr: String, vkLink: String, nStreams: Int): Pointer?
    fun YpFtStop()
    fun YpFtRunning(): Int
    fun YpFtConnectedStreams(): Int

    fun YpWdttStart(
        peer: String,
        vkHashes: String,
        password: String,
        listen: String,
        numWorkers: Int,
        deviceId: String,
        fingerprint: String,
        clientIds: String,
    ): Pointer?

    fun YpWdttWaitConfig(timeoutMs: Int): Pointer?
    fun YpWdttStop()
    fun YpWdttRunning(): Int
    fun YpWdttPushCaptcha(token: String)

    fun YpDnsttStart(resolver: String, domain: String, pubKeyHex: String, listenAddr: String): Pointer?
    fun YpDnsttStop()
    fun YpDnsttRunning(): Int

    fun YpRtcVersion(): Pointer?
    fun YpRtcSetTransport(transport: String)
    fun YpRtcSetTelemostCookies(cookies: String)
    fun YpRtcSetDNS(dnsServer: String)
    fun YpRtcSetSocksListenHost(host: String)
    fun YpRtcSetVP8Options(fps: Int, batchSize: Int)
    fun YpRtcSetLivenessOptions(intervalMs: Int, timeoutMs: Int, failures: Int)
    fun YpRtcStart(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String,
    ): Pointer?

    fun YpRtcWaitReady(timeoutMs: Int): Pointer?
    fun YpRtcStop()
    fun YpRtcRunning(): Int
    fun YpRtcCheck(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        timeoutMs: Int,
        vp8Fps: Int,
        vp8Batch: Int,
    ): Long

    fun YpRtcPing(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        timeoutMs: Int,
        pingUrl: String,
        vp8Fps: Int,
        vp8Batch: Int,
    ): Long
}

/**
 * Idiomatic façade over [YpTunCoreLib]: extracts the bundled DLL, converts error C-strings into
 * thrown [IllegalStateException]s, and pumps the Go log bus into [logSinks].
 */
internal object YpTunCore {

    /** External log consumers (the VPN manager); each line is already "tag: message". */
    val logSinks = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    val isAvailable: Boolean get() = libOrNull != null

    private val libOrNull: YpTunCoreLib? by lazy {
        try {
            val fileName = libraryFileName() ?: run {
                println("YpTunCore: unsupported platform")
                return@lazy null
            }
            val resourcePath = "native/$fileName"
            val input = YpTunCore::class.java.classLoader?.getResourceAsStream(resourcePath)
            if (input == null) {
                println("YpTunCore: resource not found: $resourcePath")
                return@lazy null
            }
            val tempFile = Files.createTempFile("yptuncore-", "-$fileName")
            tempFile.toFile().deleteOnExit()
            input.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
            val lib = Native.load(tempFile.toAbsolutePath().toString(), YpTunCoreLib::class.java)
            startLogPump(lib)
            lib
        } catch (e: Throwable) {
            println("YpTunCore: failed to load native library: ${e.message}")
            null
        }
    }

    private fun lib(): YpTunCoreLib =
        libOrNull ?: error("yptuncore native library is not available on this platform")

    private fun libraryFileName(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "amd64"
            else -> return null
        }
        return when {
            "win" in os && arch == "amd64" -> "yptuncore-windows-amd64.dll"
            "linux" in os -> "yptuncore-linux-$arch.so"
            "mac" in os || "darwin" in os -> "yptuncore-darwin-$arch.dylib"
            else -> null
        }
    }

    /** Reads + frees a Go-allocated C string. */
    private fun takeString(p: Pointer?): String? {
        if (p == null) return null
        return try {
            p.getString(0)
        } finally {
            lib().YpFree(p)
        }
    }

    /** Throws when the call returned an error string. */
    private fun check(p: Pointer?, what: String) {
        val err = takeString(p) ?: return
        if (err.isNotBlank()) throw IllegalStateException("$what: $err")
    }

    private fun startLogPump(lib: YpTunCoreLib) {
        thread(name = "yptuncore-logs", isDaemon = true) {
            while (true) {
                val p = try {
                    lib.YpPollLog(1_000)
                } catch (_: Throwable) {
                    return@thread
                }
                if (p != null) {
                    val line = try {
                        p.getString(0)
                    } finally {
                        lib.YpFree(p)
                    }
                    for (sink in logSinks) {
                        runCatching { sink(line) }
                    }
                }
            }
        }
    }

    // sing-box ------------------------------------------------------------------------------
    fun sbVersion(): String = takeString(lib().YpSbVersion()).orEmpty()
    fun sbStart(configJson: String) = check(lib().YpSbStart(configJson), "sing-box start failed")
    fun sbStop() = lib().YpSbStop()
    fun sbRunning(): Boolean = libOrNull?.YpSbRunning() == 1

    // xray ----------------------------------------------------------------------------------
    fun xraySetAssetPath(dir: String) = lib().YpXraySetAssetPath(dir)
    fun xrayVersion(): String = takeString(lib().YpXrayVersion()).orEmpty()
    fun xrayStart(configJson: String) = check(lib().YpXrayStart(configJson), "xray start failed")
    fun xrayStop() = lib().YpXrayStop()
    fun xrayRunning(): Boolean = libOrNull?.YpXrayRunning() == 1
    fun xrayMeasureDelay(configJson: String, url: String, method: String, timeoutMs: Int): Long =
        lib().YpXrayMeasureDelay(configJson, url, method, timeoutMs)

    // AmneziaWG -----------------------------------------------------------------------------
    fun awgStart(iniConfig: String, listenAddr: String) =
        check(lib().YpAwgStart(iniConfig, listenAddr), "AmneziaWG start failed")

    fun awgStop() = libOrNull?.YpAwgStop() ?: Unit
    fun awgRunning(): Boolean = libOrNull?.YpAwgRunning() == 1
    fun awgProbe(iniConfig: String): Long = lib().YpAwgProbe(iniConfig)
    fun awgMeasureDelay(iniConfig: String, url: String, method: String, timeoutMs: Int): Long =
        lib().YpAwgMeasureDelay(iniConfig, url, method, timeoutMs)

    /** "privateKey|publicKey", base64 — for the WARP generator's direct-registration fallback. */
    fun awgGenerateKeyPair(): String = takeString(lib().YpAwgGenerateKeyPair()).orEmpty()

    // Telegram-over-WARP proxy (its OWN AmneziaWG instance, never the main transport's) ----------
    fun tgAwgStart(iniConfig: String, listenAddr: String, user: String, pass: String) =
        check(lib().YpTgAwgStart(iniConfig, listenAddr, user, pass), "Telegram WARP start failed")

    fun tgAwgStop() = libOrNull?.YpTgAwgStop() ?: Unit
    fun tgAwgRunning(): Boolean = libOrNull?.YpTgAwgRunning() == 1

    // VK-TURN -------------------------------------------------------------------------------
    fun ftVersion(): String = takeString(lib().YpFtVersion()).orEmpty()
    fun ftStart(uri: String, listenAddr: String, vkLink: String, nStreams: Int) =
        check(lib().YpFtStart(uri, listenAddr, vkLink, nStreams), "VK-TURN start failed")

    fun ftStop() = libOrNull?.YpFtStop() ?: Unit
    fun ftRunning(): Boolean = libOrNull?.YpFtRunning() == 1
    fun ftConnectedStreams(): Int = libOrNull?.YpFtConnectedStreams() ?: 0

    // VK-TURN / WDTT core (wg-turn-client) --------------------------------------------------
    fun wdttStart(
        peer: String,
        vkHashes: String,
        password: String,
        listen: String,
        numWorkers: Int,
        deviceId: String,
        fingerprint: String,
        clientIds: String = "",
    ) = check(
        lib().YpWdttStart(peer, vkHashes, password, listen, numWorkers, deviceId, fingerprint, clientIds),
        "WDTT start failed"
    )

    /** The wdtt-server's WireGuard config (GETCONF), or null if it didn't arrive within [timeoutMs]. */
    fun wdttWaitConfig(timeoutMs: Int): String? =
        takeString(lib().YpWdttWaitConfig(timeoutMs))?.takeIf { it.isNotBlank() }

    fun wdttStop() = libOrNull?.YpWdttStop() ?: Unit
    fun wdttRunning(): Boolean = libOrNull?.YpWdttRunning() == 1
    fun wdttPushCaptcha(token: String) = lib().YpWdttPushCaptcha(token)

    // dnstt ---------------------------------------------------------------------------------
    fun dnsttStart(resolver: String, domain: String, pubKeyHex: String, listenAddr: String) =
        check(lib().YpDnsttStart(resolver, domain, pubKeyHex, listenAddr), "DNSTT start failed")

    fun dnsttStop() = libOrNull?.YpDnsttStop() ?: Unit
    fun dnsttRunning(): Boolean = libOrNull?.YpDnsttRunning() == 1

    // olcrtc --------------------------------------------------------------------------------
    fun rtcVersion(): String = takeString(lib().YpRtcVersion()).orEmpty()
    fun rtcSetTransport(transport: String) = lib().YpRtcSetTransport(transport)
    fun rtcSetTelemostCookies(cookies: String) = lib().YpRtcSetTelemostCookies(cookies)
    fun rtcSetDns(dnsServer: String) = lib().YpRtcSetDNS(dnsServer)
    fun rtcSetSocksListenHost(host: String) = lib().YpRtcSetSocksListenHost(host)

    fun rtcStart(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String,
    ) = check(
        lib().YpRtcStart(carrier, transport, roomId, clientId, keyHex, socksPort, socksUser, socksPass),
        "olcRTC start failed"
    )

    fun rtcWaitReady(timeoutMs: Int) = check(lib().YpRtcWaitReady(timeoutMs), "olcRTC not ready")
    fun rtcStop() = libOrNull?.YpRtcStop() ?: Unit
    fun rtcRunning(): Boolean = libOrNull?.YpRtcRunning() == 1

    fun rtcCheck(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        timeoutMs: Int,
    ): Long = lib().YpRtcCheck(carrier, transport, roomId, clientId, keyHex, socksPort, timeoutMs, 0, 0)

    /** Stops every core (used on disconnect; mirrors Android stopMobile). */
    fun stopAll() {
        runCatching { sbStop() }
        runCatching { xrayStop() }
        runCatching { ftStop() }
        runCatching { wdttStop() }
        runCatching { dnsttStop() }
        runCatching { awgStop() }
        runCatching { rtcStop() }
    }
}
