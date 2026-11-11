import Foundation
@preconcurrency import NetworkExtension
import YPtunRustBridge
import YPtunTunnelSupport

#if canImport(Coreapi) && canImport(Tun2SocksKit)
import Coreapi
import Darwin
import Tun2SocksKit
#if canImport(SnolcCore)
import SnolcCore
#endif
#if canImport(CsqttCore)
import CsqttCore
#endif

final class NativeTunnelRuntime: @unchecked Sendable {
    private let lock = NSLock()
    private var running = false
    private var pumpingLogs = false
    private var watchdogFailures = 0
    private weak var provider: NEPacketTunnelProvider?
    private var watchdog: DispatchSourceTimer?

    func start(
        provider: NEPacketTunnelProvider,
        completion: @escaping (Error?) -> Void
    ) {
        do {
            self.provider = provider
            let request = try AppleTunnelStore.loadRequest()
            let logURL = try AppleTunnelStore.url(for: AppleTunnelStore.logFileName)
            let cacheURL = try AppleTunnelStore.url(for: AppleTunnelStore.cacheFileName)
            let runtimeDirectory = try AppleTunnelStore.directory()
            let plan = try appleNativeRuntimePlan(
                locationJson: request.locationJSON,
                trafficJson: request.trafficJSON,
                routingJson: request.routingJSON,
                routingProfilesJson: request.routingProfilesJSON,
                appBehaviorJson: request.appBehaviorJSON,
                warpConfig: request.warpConfig,
                socksPort: 10_808,
                deviceId: request.deviceID,
                runtimeDirectory: runtimeDirectory.path,
                logFilePath: logURL.path,
                cacheFilePath: cacheURL.path
            )

            startLogPump()
            try startEngines(
                plan,
                request: request,
                logFilePath: logURL.path,
                cacheFilePath: cacheURL.path
            )
            provider.setTunnelNetworkSettings(networkSettings(from: plan.tunnel)) { [weak self] error in
                guard error == nil else {
                    self?.stop()
                    self?.record(error: error!)
                    completion(error)
                    return
                }
                do {
                    try self?.startBridge(yaml: plan.bridge.yaml)
                    self?.setRunning(true)
                    try? AppleTunnelStore.writeText(
                        VPNConnectionState.connected.rawValue,
                        to: AppleTunnelStore.statusFileName
                    )
                    self?.startWatchdog(plan)
                    completion(nil)
                } catch {
                    self?.stop()
                    self?.record(error: error)
                    completion(error)
                }
            }
        } catch {
            stop()
            record(error: error)
            completion(error)
        }
    }

    func stop() {
        watchdog?.cancel()
        watchdog = nil
        setPumpingLogs(false)
        Socks5Tunnel.quit()
        CoreapiSbStop()
        CoreapiXrayStop()
        CoreapiAwgStop()
        CoreapiTurnableStop()
        CoreapiMieruStop()
        CoreapiAppleEngineStopAll()
#if canImport(SnolcCore)
        snolc_ffi_stop()
#endif
#if canImport(CsqttCore)
        csqtt_ffi_stop()
#endif
        CoreapiCsqttBridgeStop()
        CoreapiRtcStopPool()
        var ignored: NSError?
        _ = CoreapiRtcStop(&ignored)
        setRunning(false)
        provider = nil
        try? AppleTunnelStore.writeText(
            VPNConnectionState.disconnected.rawValue,
            to: AppleTunnelStore.statusFileName
        )
    }

    func handle(message: String) -> String {
        switch message {
        case "status":
            return isRunning ? "connected" : "disconnected"
        default:
            return ""
        }
    }

    private func startEngines(
        _ plan: AppleNativeRuntimePlan,
        request: AppleTunnelRequest,
        logFilePath: String,
        cacheFilePath: String
    ) throws {
        if let olcrtc = plan.olcrtc {
            try startOlcRtc(olcrtc)
        }
        if let amneziaWg = plan.amneziaWg {
            try callCore {
                CoreapiAwgStart(amneziaWg.iniConfig, amneziaWg.listenAddress, $0)
            }
        }
        if let snolc = plan.snolc {
            try startSnolc(snolc)
        }
        if let csqtt = plan.csqtt {
            try startCsqtt(csqtt)
        }
        if let turnable = plan.turnable {
            try callCore {
                CoreapiTurnableStart(turnable.config, turnable.listenAddress, $0)
            }
        }
        for auxiliary in plan.auxiliary {
            let kind: String = switch auxiliary.kind {
            case .freeTurn: "freeturn"
            case .wdtt: "wdtt"
            case .masterDns: "masterdns"
            case .openFlux: "openflux"
            }
            try callCore {
                CoreapiAppleEngineStart(kind, auxiliary.optionsJson, $0)
            }
        }
        if plan.deferredWireguardProxy {
            let negotiated = CoreapiWdttWaitConfig(45_000)
            guard !negotiated.isEmpty, !negotiated.hasPrefix("ERROR:") else {
                throw NativeTunnelRuntimeError.wdttConfigurationUnavailable
            }
            guard !negotiated.hasPrefix("RAWCONF:") else {
                throw NativeTunnelRuntimeError.wdttRawModeUnavailable
            }
            let proxy = try appleWireguardRuntimeProxy(
                wireguardIni: negotiated,
                trafficJson: request.trafficJSON,
                routingJson: request.routingJSON,
                routingProfilesJson: request.routingProfilesJSON,
                locationJson: request.locationJSON,
                socksPort: 10_808,
                logFilePath: logFilePath,
                cacheFilePath: cacheFilePath
            )
            try callCore { CoreapiSbStart(proxy.config, $0) }
        }
        if let proxy = plan.proxy {
            switch proxy.kind {
            case .singBox:
                try callCore { CoreapiSbStart(proxy.config, $0) }
            case .xray:
                try callCore { CoreapiXrayStart(proxy.config, $0) }
            case .mieru:
                try callCore {
                    CoreapiMieruStart(
                        proxy.config,
                        "127.0.0.1:\(proxy.listenPort)",
                        $0
                    )
                }
            }
        }
    }

    private func startOlcRtc(_ plan: AppleOlcRtcRuntimePlan) throws {
        try callCore { CoreapiRtcSetTransport(plan.transport, $0) }
        try callCore { CoreapiRtcSetDNS("77.88.8.8", $0) }
        try callCore { CoreapiRtcSetSocksListenHost("127.0.0.1", $0) }
        try callCore { CoreapiRtcSetVP8Options(Int(plan.vp8Fps), Int(plan.vp8Batch), $0) }
        try callCore {
            CoreapiRtcSetSEIOptions(
                Int(plan.seiFps),
                Int(plan.seiBatch),
                Int(plan.seiFragmentSize),
                Int(plan.seiAckTimeoutMs),
                $0
            )
        }
        if plan.transport == "videochannel" {
            try callCore {
                CoreapiRtcSetVideoOptions(
                    Int(plan.videoWidth),
                    Int(plan.videoHeight),
                    Int(plan.videoFps),
                    Int(plan.videoQrSize),
                    plan.videoQrRecovery,
                    plan.videoCodec,
                    Int(plan.videoTileModule),
                    Int(plan.videoTileRs),
                    $0
                )
            }
        }
        if !plan.roomsJson.isEmpty {
            try callCore {
                CoreapiRtcStartPool(
                    plan.roomsJson,
                    plan.clientId,
                    "127.0.0.1:\(plan.listenPort)",
                    "",
                    "",
                    20_000,
                    $0
                )
            }
        } else {
            try callCore {
                CoreapiRtcStart(
                    plan.provider,
                    plan.transport,
                    plan.roomId,
                    plan.clientId,
                    plan.keyHex,
                    Int(plan.listenPort),
                    "",
                    "",
                    $0
                )
            }
            try callCore { CoreapiRtcWaitReady(20_000, $0) }
        }
    }

    private func startSnolc(_ plan: AppleSnolcRuntimePlan) throws {
#if canImport(SnolcCore)
        let directory = URL(fileURLWithPath: plan.directory, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for file in plan.files {
            let url = directory.appendingPathComponent(file.relativePath)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(file.contents.utf8).write(to: url, options: .atomic)
        }
        if let pointer = snolc_ffi_start(plan.configPath) {
            let message = String(cString: pointer)
            snolc_ffi_free_string(pointer)
            throw NativeTunnelRuntimeError.specializedCore(message)
        }
#else
        throw NativeTunnelRuntimeError.specializedCore("SnolcCore is not linked")
#endif
    }

    private func startCsqtt(_ plan: AppleCsqttRuntimePlan) throws {
#if canImport(CsqttCore)
        try callCore {
            CoreapiCsqttBridgeStart(
                plan.bridgeSocksAddress,
                plan.clientUdpAddress,
                Int(plan.mtu),
                $0
            )
        }
        if let pointer = csqtt_ffi_start(plan.optionsJson, { configuration in
            guard let configuration else { return }
            var error: NSError?
            _ = CoreapiCsqttBridgeSetTunnel(String(cString: configuration), &error)
        }, { line in
            guard let line else { return }
            CoreapiPushLog("csqtt", String(cString: line))
        }) {
            let message = String(cString: pointer)
            csqtt_ffi_free_string(pointer)
            throw NativeTunnelRuntimeError.specializedCore(message)
        }
        guard CoreapiCsqttBridgeWaitTunnel(35_000) else {
            throw NativeTunnelRuntimeError.specializedCore(
                "CSQTT tunnel handshake timed out"
            )
        }
#else
        throw NativeTunnelRuntimeError.specializedCore("CsqttCore is not linked")
#endif
    }

    private func startBridge(yaml: String) throws {
        let configURL = try AppleTunnelStore.url(for: "hev_config.yml")
        try Data(yaml.utf8).write(to: configURL, options: .atomic)
        Socks5Tunnel.run(withConfig: .file(path: configURL)) { code in
            if code != 0 {
                try? AppleTunnelStore.writeText(
                    "hev-socks5-tunnel exited with code \(code)",
                    to: AppleTunnelStore.errorFileName
                )
            }
        }
    }

    private func networkSettings(from plan: AppleTunnelPlan) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(
            tunnelRemoteAddress: plan.tunnelRemoteAddress
        )
        settings.mtu = NSNumber(value: plan.mtu)

        let ipv4 = NEIPv4Settings(
            addresses: [plan.ipv4Address],
            subnetMasks: [plan.ipv4SubnetMask]
        )
        if plan.includeDefaultIpv4Route {
            ipv4.includedRoutes = [.default()]
        }
        ipv4.excludedRoutes = plan.excludedIpv4Routes.map {
            NEIPv4Route(destinationAddress: $0.address, subnetMask: $0.subnetMask)
        }
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(
            addresses: [plan.ipv6Address],
            networkPrefixLengths: [NSNumber(value: plan.ipv6PrefixLength)]
        )
        if plan.includeDefaultIpv6Route {
            ipv6.includedRoutes = [.default()]
        }
        settings.ipv6Settings = ipv6

        let dns = NEDNSSettings(servers: plan.dnsServers)
        dns.matchDomains = plan.dnsMatchDomains
        settings.dnsSettings = dns
        return settings
    }

    private func callCore(_ body: (NSErrorPointer) -> Bool) throws {
        var error: NSError?
        _ = body(&error)
        if let error { throw error }
    }

    private func record(error: Error) {
        try? AppleTunnelStore.writeText(error.localizedDescription, to: AppleTunnelStore.errorFileName)
        try? AppleTunnelStore.writeText(
            VPNConnectionState.disconnected.rawValue,
            to: AppleTunnelStore.statusFileName
        )
    }

    private func startLogPump() {
        setPumpingLogs(true)
        DispatchQueue.global(qos: .utility).async { [weak self] in
            while self?.shouldPumpLogs == true {
                let line = CoreapiPollLog(1_000)
                guard !line.isEmpty,
                      !line.hasPrefix("openflux: <-"),
                      !line.hasPrefix("openflux: ->") else { continue }
                try? AppleTunnelStore.appendLog(line)
            }
        }
    }

    private func startWatchdog(_ plan: AppleNativeRuntimePlan) {
        watchdog?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        let slow = !plan.auxiliary.isEmpty || plan.turnable != nil || plan.snolc != nil || plan.csqtt != nil
        resetWatchdogFailures()
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if self.coresRunning(plan) {
                self.resetWatchdogFailures()
                return
            }
            let limit = slow ? 6 : 3
            let failures = self.incrementWatchdogFailures()
            try? AppleTunnelStore.appendLog("Core health check failed (\(failures)/\(limit))")
            guard failures >= limit else { return }
            let error = NativeTunnelRuntimeError.coreStopped
            let provider = self.provider
            self.record(error: error)
            self.stop()
            provider?.cancelTunnelWithError(error)
        }
        watchdog = timer
        timer.resume()
    }

    private func coresRunning(_ plan: AppleNativeRuntimePlan) -> Bool {
        if let olcrtc = plan.olcrtc {
            let up = olcrtc.roomsJson.isEmpty ? CoreapiRtcRunning() : CoreapiRtcPoolRunning()
            if !up { return false }
        }
        if plan.amneziaWg != nil && !CoreapiAwgRunning() { return false }
        if plan.turnable != nil && !CoreapiTurnableRunning() { return false }
        if plan.deferredWireguardProxy && !CoreapiWdttRunning() { return false }
        for runtime in plan.auxiliary {
            let kind: String = switch runtime.kind {
            case .freeTurn: "freeturn"
            case .wdtt: "wdtt"
            case .masterDns: "masterdns"
            case .openFlux: "openflux"
            }
            if !CoreapiAppleEngineRunning(kind) { return false }
        }
#if canImport(SnolcCore)
        if plan.snolc != nil && !snolc_ffi_is_running() { return false }
#endif
#if canImport(CsqttCore)
        if plan.csqtt != nil && (!csqtt_ffi_is_running() || !CoreapiCsqttBridgeIsRunning()) {
            return false
        }
#endif
        if let proxy = plan.proxy {
            switch proxy.kind {
            case .singBox where !CoreapiSbRunning(): return false
            case .xray where !CoreapiXrayRunning(): return false
            case .mieru where !CoreapiMieruRunning(): return false
            default: break
            }
        } else if plan.deferredWireguardProxy && !CoreapiSbRunning() {
            return false
        }
        return true
    }

    private var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }

    private func setRunning(_ value: Bool) {
        lock.lock()
        running = value
        lock.unlock()
    }

    private var shouldPumpLogs: Bool {
        lock.lock()
        defer { lock.unlock() }
        return pumpingLogs
    }

    private func setPumpingLogs(_ value: Bool) {
        lock.lock()
        pumpingLogs = value
        lock.unlock()
    }

    private func resetWatchdogFailures() {
        lock.lock()
        watchdogFailures = 0
        lock.unlock()
    }

    private func incrementWatchdogFailures() -> Int {
        lock.lock()
        watchdogFailures += 1
        let value = watchdogFailures
        lock.unlock()
        return value
    }
}

#else

final class NativeTunnelRuntime {
    func start(
        provider: NEPacketTunnelProvider,
        completion: @escaping (Error?) -> Void
    ) {
        let error = NativeTunnelRuntimeError.coreFrameworkUnavailable
        try? AppleTunnelStore.writeText(error.localizedDescription, to: AppleTunnelStore.errorFileName)
        try? AppleTunnelStore.writeText(
            VPNConnectionState.disconnected.rawValue,
            to: AppleTunnelStore.statusFileName
        )
        completion(error)
    }

    func stop() {}

    func handle(message: String) -> String { "unavailable" }
}

#endif

enum NativeTunnelRuntimeError: LocalizedError {
    case coreFrameworkUnavailable
    case wdttConfigurationUnavailable
    case wdttRawModeUnavailable
    case specializedCore(String)
    case coreStopped

    var errorDescription: String? {
        switch self {
        case .coreFrameworkUnavailable:
            "The in-process Apple core framework is not linked."
        case .wdttConfigurationUnavailable:
            "qWDTT did not return its WireGuard configuration before the timeout."
        case .wdttRawModeUnavailable:
            "qWDTT Raw mode is not supported by the native Apple runtime."
        case let .specializedCore(message):
            message
        case .coreStopped:
            "The active network core stopped unexpectedly."
        }
    }
}
