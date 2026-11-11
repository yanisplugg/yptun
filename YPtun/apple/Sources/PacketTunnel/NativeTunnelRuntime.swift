import Foundation
@preconcurrency import NetworkExtension
import YPtunRustBridge
import YPtunTunnelSupport

#if canImport(Coreapi) && canImport(Tun2SocksKit)
import Coreapi
import Darwin
import Tun2SocksKit

final class NativeTunnelRuntime: @unchecked Sendable {
    private let lock = NSLock()
    private var running = false

    func start(
        provider: NEPacketTunnelProvider,
        completion: @escaping (Error?) -> Void
    ) {
        do {
            let request = try AppleTunnelStore.loadRequest()
            let logURL = try AppleTunnelStore.url(for: AppleTunnelStore.logFileName)
            let cacheURL = try AppleTunnelStore.url(for: AppleTunnelStore.cacheFileName)
            let plan = try appleNativeRuntimePlan(
                locationJson: request.locationJSON,
                trafficJson: request.trafficJSON,
                routingJson: request.routingJSON,
                routingProfilesJson: request.routingProfilesJSON,
                appBehaviorJson: request.appBehaviorJSON,
                socksPort: 10_808,
                deviceId: request.deviceID,
                logFilePath: logURL.path,
                cacheFilePath: cacheURL.path
            )

            try startEngines(plan)
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
        Socks5Tunnel.quit()
        CoreapiSbStop()
        CoreapiXrayStop()
        CoreapiAwgStop()
        CoreapiTurnableStop()
        CoreapiMieruStop()
        CoreapiRtcStopPool()
        var ignored: NSError?
        _ = CoreapiRtcStop(&ignored)
        setRunning(false)
    }

    func handle(message: String) -> String {
        switch message {
        case "status":
            return isRunning ? "connected" : "disconnected"
        default:
            return ""
        }
    }

    private func startEngines(_ plan: AppleNativeRuntimePlan) throws {
        if let olcrtc = plan.olcrtc {
            try startOlcRtc(olcrtc)
        }
        if let amneziaWg = plan.amneziaWg {
            try callCore {
                CoreapiAwgStart(amneziaWg.iniConfig, amneziaWg.listenAddress, $0)
            }
        }
        if let turnable = plan.turnable {
            try callCore {
                CoreapiTurnableStart(turnable.config, turnable.listenAddress, $0)
            }
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
}

#else

final class NativeTunnelRuntime {
    func start(
        provider: NEPacketTunnelProvider,
        completion: @escaping (Error?) -> Void
    ) {
        let error = NativeTunnelRuntimeError.coreFrameworkUnavailable
        try? AppleTunnelStore.writeText(error.localizedDescription, to: AppleTunnelStore.errorFileName)
        completion(error)
    }

    func stop() {}

    func handle(message: String) -> String { "unavailable" }
}

#endif

enum NativeTunnelRuntimeError: LocalizedError {
    case coreFrameworkUnavailable

    var errorDescription: String? {
        switch self {
        case .coreFrameworkUnavailable:
            "The in-process Apple core framework is not linked."
        }
    }
}
