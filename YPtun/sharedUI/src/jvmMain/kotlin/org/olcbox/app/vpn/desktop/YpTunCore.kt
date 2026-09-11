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

    fun YpBindOutboundInterface(index: Int, pinUdp: Int)
    fun YpAddNativeSearchPath(dir: String)

    fun YpXraySetAssetPath(dir: String)
    fun YpXrayVersion(): Pointer?
    fun YpXrayStart(configJson: String): Pointer?
    fun YpXrayStop()
    fun YpXrayRunning(): Int
    fun YpXrayMeasureDelay(configJson: String, url: String, method: String, timeoutMs: Int): Long

    fun YpAwgVersion(): Pointer?
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

    fun YpFtCaptchaURL(): Pointer?

    fun YpFtCaptchaActive(): Int

    fun YpWdttStart(optionsJson: String): Pointer?
    fun YpWdttLastError(): Pointer?
    fun YpWdttWaitConfig(timeoutMs: Int): Pointer?
    fun YpWdttStop()
    fun YpWdttRunning(): Int
    fun YpWdttPushCaptcha(token: String)
    fun YpWdttVersion(): Pointer?

    fun YpMasterDnsStart(
        workDir: String,
        domains: String,
        encryptionKey: String,
        encryptionMethod: Int,
        resolvers: String,
        listenAddr: String,
        socksUser: String,
        socksPass: String,
        balancingStrategy: Int,
        packetDuplication: Int,
        uploadCompression: Int,
        downloadCompression: Int,
    ): Pointer?
    fun YpMasterDnsStop()
    fun YpMasterDnsRunning(): Int
    fun YpMasterDnsLastError(): Pointer?
    fun YpMasterDnsVersion(): Pointer?

    fun YpRtcVersion(): Pointer?
    fun YpRtcSetTransport(transport: String)
    fun YpRtcSetTelemostCookies(cookies: String)
    fun YpRtcSetDNS(dnsServer: String)
    fun YpRtcSetSocksListenHost(host: String)
    fun YpRtcSetVP8Options(fps: Int, batchSize: Int)
    fun YpRtcSetSEIOptions(fps: Int, batchSize: Int, fragmentSize: Int, ackTimeoutMs: Int): Pointer?
    fun YpRtcSetVideoOptions(
        width: Int, height: Int, fps: Int, qrSize: Int,
        qrRecovery: String, codec: String, tileModule: Int, tileRS: Int,
    ): Pointer?
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
            // NaïveProxy's cronet library is loaded lazily, by name, from PATH — point the core at
            // the directory we unpack the bundled natives into (see DesktopNativeAssets).
            runCatching {
                lib.YpAddNativeSearchPath(DesktopNativeAssets.resolveCronetLibraryDir().toString())
            }
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
            "win" in os -> "yptuncore-windows-$arch.dll"
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

    /**
     * Pins the sockets xray opens to the physical adapter [index] (0 = off). The Windows stand-in
     * for VpnService.protect(): without it xray's `direct` outbound is swallowed by our own TUN and
     * loops, which is what made routing profiles and cascades look dead in TUN mode.
     *
     * [pinUdp] must be false for a config whose UDP goes to a local hop (the VK-TURN
     * WireGuard-over-Xray exit) — see the Go doc on YpBindOutboundInterface.
     */
    fun bindOutboundInterface(index: Int, pinUdp: Boolean = true) {
        runCatching { libOrNull?.YpBindOutboundInterface(index, if (pinUdp) 1 else 0) }
    }

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

    fun awgVersion(): String = takeString(lib().YpAwgVersion()).orEmpty()
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

    /** URL of a pending manual VK captcha (freeturn serves it on localhost); empty when there is none. */
    fun ftCaptchaUrl(): String = takeString(libOrNull?.YpFtCaptchaURL()).orEmpty()

    /** True while the user is solving a VK captcha — the relay cannot come up until they are done. */
    fun ftCaptchaActive(): Boolean = (libOrNull?.YpFtCaptchaActive() ?: 0) == 1

    // VK-TURN / qWDTT core (wg-turn-client) ---------------------------------------------
    /** Starts qWDTT from [org.olcbox.app.data.model.VkTurnConfig.wdttCoreOptionsJson]. */
    fun wdttStart(optionsJson: String) = check(lib().YpWdttStart(optionsJson), "WDTT start failed")

    /** Why the core stopped on its own ("" while fine). */
    fun wdttLastError(): String = takeString(libOrNull?.YpWdttLastError()).orEmpty()

    /** The wdtt-server's WireGuard config (GETCONF), or null if it didn't arrive within [timeoutMs]. */
    fun wdttWaitConfig(timeoutMs: Int): String? =
        takeString(lib().YpWdttWaitConfig(timeoutMs))?.takeIf { it.isNotBlank() }

    fun wdttStop() = libOrNull?.YpWdttStop() ?: Unit
    fun wdttRunning(): Boolean = libOrNull?.YpWdttRunning() == 1
    fun wdttPushCaptcha(token: String) = lib().YpWdttPushCaptcha(token)

    fun wdttVersion(): String = takeString(lib().YpWdttVersion()).orEmpty()

    // MasterDNS ---------------------------------------------------------------------------------
    /** Zero for any of the tuning knobs keeps the MasterDnsVPN default. */
    fun masterDnsStart(
        workDir: String,
        domains: String,
        encryptionKey: String,
        encryptionMethod: Int,
        resolvers: String,
        listenAddr: String,
        socksUser: String,
        socksPass: String,
        balancingStrategy: Int = 0,
        packetDuplication: Int = 0,
        uploadCompression: Int = 0,
        downloadCompression: Int = 0,
    ) = check(
        lib().YpMasterDnsStart(
            workDir, domains, encryptionKey, encryptionMethod, resolvers, listenAddr,
            socksUser, socksPass, balancingStrategy, packetDuplication,
            uploadCompression, downloadCompression,
        ),
        "MasterDNS start failed"
    )

    fun masterDnsStop() = libOrNull?.YpMasterDnsStop() ?: Unit
    fun masterDnsRunning(): Boolean = libOrNull?.YpMasterDnsRunning() == 1

    /** Why the tunnel's run loop exited, when it did; empty while it is alive. */
    fun masterDnsLastError(): String = takeString(libOrNull?.YpMasterDnsLastError()).orEmpty()

    fun masterDnsVersion(): String = takeString(lib().YpMasterDnsVersion()).orEmpty()

    // olcrtc --------------------------------------------------------------------------------
    fun rtcVersion(): String = takeString(lib().YpRtcVersion()).orEmpty()
    fun rtcSetTransport(transport: String) = lib().YpRtcSetTransport(transport)
    fun rtcSetTelemostCookies(cookies: String) = lib().YpRtcSetTelemostCookies(cookies)
    fun rtcSetDns(dnsServer: String) = lib().YpRtcSetDNS(dnsServer)
    fun rtcSetSocksListenHost(host: String) = lib().YpRtcSetSocksListenHost(host)

    /** Per-transport options of a location (vp8 / sei / video), before [rtcStart]. */
    fun rtcApplyTransportOptions(config: org.olcbox.app.data.model.LocationConfig) {
        when (config.transport) {
            org.olcbox.app.data.model.LocationConfig.TRANSPORT_VP8CHANNEL ->
                lib().YpRtcSetVP8Options(config.vp8Fps, config.vp8Batch)
            org.olcbox.app.data.model.LocationConfig.TRANSPORT_SEICHANNEL -> config.seiOptions().let {
                check(lib().YpRtcSetSEIOptions(it.fps, it.batch, it.fragmentSize, it.ackTimeoutMs), "olcRTC SEI options")
            }
            org.olcbox.app.data.model.LocationConfig.TRANSPORT_VIDEOCHANNEL -> config.videoOptions().let {
                check(
                    lib().YpRtcSetVideoOptions(
                        it.width, it.height, it.fps, it.qrSize, it.qrRecovery, it.codec, it.tileModule, it.tileRs,
                    ),
                    "olcRTC video options",
                )
            }
        }
    }

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
        runCatching { masterDnsStop() }
        runCatching { awgStop() }
        runCatching { rtcStop() }
    }
}
