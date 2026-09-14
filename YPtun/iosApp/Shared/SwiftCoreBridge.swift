import Coreapi
import Foundation
import SharedUI

/// Kotlin's `IosCoreBridge`, forwarded 1:1 to the gomobile framework of `kazcores/coreapi`.
/// Compiled into both the app (server pings) and the packet-tunnel extension (the tunnel itself).
/// Error convention of the Kotlin side: "" = success, otherwise the error text.
final class SwiftCoreBridge: NSObject, IosCoreBridge {
    private func err(_ call: (NSErrorPointer) -> Bool) -> String {
        var error: NSError?
        _ = call(&error)
        return error?.localizedDescription ?? ""
    }

    func pollLog(timeoutMs: Int32) -> String { CoreapiPollLog(Int(timeoutMs)) }

    func sbVersion() -> String { CoreapiSbVersion() }
    func sbStart(configJson: String) -> String { err { CoreapiSbStart(configJson, $0) } }
    func sbStop() { CoreapiSbStop() }
    func sbRunning() -> Bool { CoreapiSbRunning() }

    func xraySetAssetPath(dir: String) { CoreapiXraySetAssetPath(dir) }
    func xrayVersion() -> String { CoreapiXrayVersion() }
    func xrayStart(configJson: String) -> String { err { CoreapiXrayStart(configJson, $0) } }
    func xrayStop() { CoreapiXrayStop() }
    func xrayRunning() -> Bool { CoreapiXrayRunning() }
    func xrayMeasureDelay(configJson: String, url: String, method: String, timeoutMs: Int32) -> Int64 {
        CoreapiXrayMeasureDelay(configJson, url, method, Int(timeoutMs))
    }

    func tcpPing(host: String, port: Int32, timeoutMs: Int32) -> Int64 {
        guard !host.isEmpty, port > 0, port <= 65535 else { return -1 }
        let h = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "0.0.0.0" {
            return -1
        }
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let info = res else {
            return -1
        }
        defer { freeaddrinfo(info) }

        let fd = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        let start = DispatchTime.now()
        let ret = connect(fd, info.pointee.ai_addr, info.pointee.ai_addrlen)
        if ret == 0 {
            let elapsed = DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds
            return max(1, Int64(elapsed / 1_000_000))
        }
        if errno != EINPROGRESS {
            return -1
        }

        var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
        let pollRet = poll(&pfd, 1, Int32(timeoutMs))
        if pollRet > 0 && (pfd.revents & Int16(POLLOUT)) != 0 && (pfd.revents & (Int16(POLLERR) | Int16(POLLHUP))) == 0 {
            var errVal: Int32 = 0
            var len = socklen_t(MemoryLayout<Int32>.size)
            if getsockopt(fd, SOL_SOCKET, SO_ERROR, &errVal, &len) == 0 && errVal == 0 {
                let elapsed = DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds
                return max(1, Int64(elapsed / 1_000_000))
            }
        }
        return -1
    }

    func awgVersion() -> String { CoreapiAwgVersion() }
    func awgStart(iniConfig: String, listenAddr: String) -> String { err { CoreapiAwgStart(iniConfig, listenAddr, $0) } }
    func awgStop() { CoreapiAwgStop() }
    func awgRunning() -> Bool { CoreapiAwgRunning() }
    func awgMeasureDelay(iniConfig: String, url: String, method: String, timeoutMs: Int32) -> Int64 {
        CoreapiAwgMeasureDelay(iniConfig, url, method, Int(timeoutMs))
    }
    func awgProbe(iniConfig: String) -> Int64 {
        CoreapiAwgProbe(iniConfig)
    }

    func ftVersion() -> String { CoreapiFtVersion() }
    func ftStart(uri: String, listenAddr: String, vkLink: String, nStreams: Int32) -> String {
        err { CoreapiFtStart(uri, listenAddr, vkLink, Int(nStreams), $0) }
    }
    func ftStop() { CoreapiFtStop() }
    func ftRunning() -> Bool { CoreapiFtRunning() }
    func ftConnectedStreams() -> Int32 { Int32(CoreapiFtConnectedStreams()) }
    func ftCaptchaUrl() -> String { CoreapiFtCaptchaURL() }
    func ftCaptchaActive() -> Bool { CoreapiFtCaptchaActive() }

    func wdttVersion() -> String { CoreapiWdttVersion() }
    func wdttStart(optionsJson: String) -> String { err { CoreapiWdttStart(optionsJson, $0) } }
    func wdttLastError() -> String { CoreapiWdttLastError() }
    func wdttWaitConfig(timeoutMs: Int32) -> String { CoreapiWdttWaitConfig(Int(timeoutMs)) }
    func wdttStop() { CoreapiWdttStop() }
    func wdttRunning() -> Bool { CoreapiWdttRunning() }
    func wdttPushCaptcha(token: String) { CoreapiWdttPushCaptcha(token) }

    func masterDnsVersion() -> String { CoreapiMasterDnsVersion() }
    func masterDnsStart(
        workDir: String, domains: String, key: String, encryptionMethod: Int32,
        resolvers: String, listenAddr: String, socksUser: String, socksPass: String,
        balancingStrategy: Int32, packetDuplication: Int32, uploadCompression: Int32, downloadCompression: Int32
    ) -> String {
        err {
            CoreapiMasterDnsStart(
                workDir, domains, key, Int(encryptionMethod), resolvers, listenAddr, socksUser, socksPass,
                Int(balancingStrategy), Int(packetDuplication), Int(uploadCompression), Int(downloadCompression), $0
            )
        }
    }
    func masterDnsStop() { CoreapiMasterDnsStop() }
    func masterDnsRunning() -> Bool { CoreapiMasterDnsRunning() }
    func masterDnsLastError() -> String { CoreapiMasterDnsLastError() }

    func rtcVersion() -> String { CoreapiRtcVersion() }
    func rtcSetTransport(transport: String) -> String { err { CoreapiRtcSetTransport(transport, $0) } }
    func rtcSetTelemostCookies(cookies: String) { CoreapiRtcSetTelemostCookies(cookies) }
    func rtcSetDns(dnsServer: String) -> String { err { CoreapiRtcSetDNS(dnsServer, $0) } }
    func rtcSetSocksListenHost(host: String) -> String { err { CoreapiRtcSetSocksListenHost(host, $0) } }
    func rtcSetVp8Options(fps: Int32, batchSize: Int32) -> String {
        err { CoreapiRtcSetVP8Options(Int(fps), Int(batchSize), $0) }
    }
    func rtcSetSeiOptions(fps: Int32, batchSize: Int32, fragmentSize: Int32, ackTimeoutMs: Int32) -> String {
        err { CoreapiRtcSetSEIOptions(Int(fps), Int(batchSize), Int(fragmentSize), Int(ackTimeoutMs), $0) }
    }
    func rtcSetVideoOptions(
        width: Int32, height: Int32, fps: Int32, qrSize: Int32,
        qrRecovery: String, codec: String, tileModule: Int32, tileRs: Int32
    ) -> String {
        err {
            CoreapiRtcSetVideoOptions(
                Int(width), Int(height), Int(fps), Int(qrSize), qrRecovery, codec, Int(tileModule), Int(tileRs), $0
            )
        }
    }
    func rtcStart(
        carrier: String, transport: String, roomId: String, clientId: String, keyHex: String,
        socksPort: Int32, socksUser: String, socksPass: String
    ) -> String {
        err { CoreapiRtcStart(carrier, transport, roomId, clientId, keyHex, Int(socksPort), socksUser, socksPass, $0) }
    }
    func rtcWaitReady(timeoutMs: Int32) -> String { err { CoreapiRtcWaitReady(Int(timeoutMs), $0) } }
    func rtcStop() -> String { err { CoreapiRtcStop($0) } }
    func rtcRunning() -> Bool { CoreapiRtcRunning() }
    func rtcPing(
        carrier: String, transport: String, roomId: String, clientId: String, keyHex: String,
        socksPort: Int32, timeoutMs: Int32, pingUrl: String, vp8Fps: Int32, vp8Batch: Int32
    ) -> Int64 {
        CoreapiRtcPing(carrier, transport, roomId, clientId, keyHex, Int(socksPort), Int(timeoutMs), pingUrl, Int(vp8Fps), Int(vp8Batch))
    }
}
