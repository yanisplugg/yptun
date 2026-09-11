package org.olcbox.app.ios

/**
 * Every YPtun core, as the Go package `kazcores/coreapi` exposes it (the same functions the desktop
 * DLL wraps for JNA). Implemented in Swift by forwarding each call to the gomobile framework
 * (`CoreapiSbStart(…)` etc.) — Kotlin cannot see gomobile's Objective-C headers when it is compiled
 * off a Mac, so this seam keeps all the logic on the Kotlin side where it type-checks everywhere.
 *
 * Convention: functions that can fail return the error text, "" on success.
 */
interface IosCoreBridge {
    fun pollLog(timeoutMs: Int): String

    fun sbVersion(): String
    fun sbStart(configJson: String): String
    fun sbStop()
    fun sbRunning(): Boolean

    fun xraySetAssetPath(dir: String)
    fun xrayVersion(): String
    fun xrayStart(configJson: String): String
    fun xrayStop()
    fun xrayRunning(): Boolean
    fun xrayMeasureDelay(configJson: String, url: String, method: String, timeoutMs: Int): Long

    fun awgVersion(): String
    fun awgStart(iniConfig: String, listenAddr: String): String
    fun awgStop()
    fun awgRunning(): Boolean
    fun awgMeasureDelay(iniConfig: String, url: String, method: String, timeoutMs: Int): Long

    fun ftVersion(): String
    fun ftStart(uri: String, listenAddr: String, vkLink: String, nStreams: Int): String
    fun ftStop()
    fun ftRunning(): Boolean
    fun ftConnectedStreams(): Int
    fun ftCaptchaUrl(): String
    fun ftCaptchaActive(): Boolean

    fun wdttVersion(): String
    fun wdttStart(optionsJson: String): String
    fun wdttLastError(): String
    fun wdttWaitConfig(timeoutMs: Int): String
    fun wdttStop()
    fun wdttRunning(): Boolean
    fun wdttPushCaptcha(token: String)

    fun masterDnsVersion(): String
    fun masterDnsStart(
        workDir: String,
        domains: String,
        key: String,
        encryptionMethod: Int,
        resolvers: String,
        listenAddr: String,
        socksUser: String,
        socksPass: String,
        balancingStrategy: Int,
        packetDuplication: Int,
        uploadCompression: Int,
        downloadCompression: Int,
    ): String
    fun masterDnsStop()
    fun masterDnsRunning(): Boolean
    fun masterDnsLastError(): String

    fun rtcVersion(): String
    fun rtcSetTransport(transport: String): String
    fun rtcSetTelemostCookies(cookies: String)
    fun rtcSetDns(dnsServer: String): String
    fun rtcSetSocksListenHost(host: String): String
    fun rtcSetVp8Options(fps: Int, batchSize: Int): String
    fun rtcSetSeiOptions(fps: Int, batchSize: Int, fragmentSize: Int, ackTimeoutMs: Int): String
    fun rtcSetVideoOptions(
        width: Int,
        height: Int,
        fps: Int,
        qrSize: Int,
        qrRecovery: String,
        codec: String,
        tileModule: Int,
        tileRs: Int,
    ): String
    fun rtcStart(
        carrier: String,
        transport: String,
        roomId: String,
        clientId: String,
        keyHex: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String,
    ): String
    fun rtcWaitReady(timeoutMs: Int): String
    fun rtcStop(): String
    fun rtcRunning(): Boolean
    fun rtcPing(
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

/** Throws with [what] + the core's message when a bridge call reported an error. */
internal fun String.orThrow(what: String) {
    if (isNotEmpty()) throw IllegalStateException("$what: $this")
}
