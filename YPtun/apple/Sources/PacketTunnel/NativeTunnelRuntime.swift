import Foundation
import Network
@preconcurrency import NetworkExtension
import Coreapi
import CsqttCore
import Darwin
import SnolcCore
import Tun2SocksKit
import YPtunRustBridge
import YPtunShared

final class NativeTunnelRuntime: @unchecked Sendable {
    private let lock = NSLock()
    private var running = false
    private var pumpingLogs = false
    private var watchdogFailures = 0
    private var activePlan: AppleNativeRuntimePlan?
    private var lastSleepAt: Date?
    private var lastRefreshAt: Date?
    private var isRefreshing = false
    private var lastPathInitialized = false
    private var lastPathSatisfied = true
    private var lastPrimaryInterface = ""
    private weak var provider: NEPacketTunnelProvider?
    private var watchdog: DispatchSourceTimer?
    private var memoryPressureSource: DispatchSourceMemoryPressure?
    private var pathMonitor: NWPathMonitor?
    private let pathQueue = DispatchQueue(label: "org.yptun.tunnel.pathMonitor", qos: .utility)

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

            log("Starting tunnel: \(plan.displayName)")
            startMemoryPressureMonitoring()
            startLogPump()
            let extraExcludedIPv4 = try startEngines(
                plan,
                request: request,
                logFilePath: logURL.path,
                cacheFilePath: cacheURL.path
            )
            let settings = networkSettings(from: plan.tunnel, extraExcludedIPv4: extraExcludedIPv4)
            let bridgeYaml = plan.bridge.yaml
            provider.setTunnelNetworkSettings(settings) { [weak self] error in
                guard error == nil else {
                    self?.record(error: error!)
                    self?.stop()
                    completion(error)
                    return
                }
                do {
                    try self?.startBridge(yaml: bridgeYaml)
                    self?.setActivePlan(plan)
                    self?.setRunning(true)
                    try? AppleTunnelStore.writeText(
                        VPNConnectionState.connected.rawValue,
                        to: AppleTunnelStore.statusFileName
                    )
                    try? AppleTunnelStore.writeText(
                        String(Date().timeIntervalSince1970),
                        to: AppleTunnelStore.connectedAtFileName
                    )
                    self?.log("Tunnel connected: \(plan.displayName)")
                    self?.startWatchdog(plan)
                    self?.startPathMonitoring()
                    CoreapiFreeOSMemory()
                    completion(nil)
                } catch {
                    self?.record(error: error)
                    self?.stop()
                    completion(error)
                }
            }
        } catch {
            record(error: error)
            stop()
            completion(error)
        }
    }

    func stop() {
        memoryPressureSource?.cancel()
        memoryPressureSource = nil
        watchdog?.cancel()
        watchdog = nil
        pathMonitor?.cancel()
        pathMonitor = nil
        setActivePlan(nil)
        setPumpingLogs(false)
        Socks5Tunnel.quit()
        CoreapiSbStop()
        CoreapiXrayStop()
        CoreapiAwgStop()
        CoreapiTurnableStop()
        CoreapiMieruStop()
        CoreapiAppleEngineStopAll()
        // Run Rust FFI stops off-thread with a hard timeout so a blocked thread.join()
        // in snolc/csqtt can never wedge PacketTunnelProvider.stopTunnel().
        let stopGroup = DispatchGroup()
        stopGroup.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            snolc_ffi_stop()
            csqtt_ffi_stop()
            stopGroup.leave()
        }
        _ = stopGroup.wait(timeout: .now() + 1.5)
        CoreapiCsqttBridgeStop()
        CoreapiRtcStopPool()
        var ignored: NSError?
        _ = CoreapiRtcStop(&ignored)
        setRunning(false)
        CoreapiFreeOSMemory()
        provider = nil
        try? AppleTunnelStore.writeText(
            VPNConnectionState.disconnected.rawValue,
            to: AppleTunnelStore.statusFileName
        )
        try? AppleTunnelStore.remove(AppleTunnelStore.connectedAtFileName)
    }

    func handleSleep() {
        lock.lock()
        lastSleepAt = Date()
        lock.unlock()
        log("Device entering sleep; freeing Go memory")
        CoreapiFreeOSMemory()
    }

    func handleWake() {
        lock.lock()
        let sleepDate = lastSleepAt
        lastSleepAt = nil
        lock.unlock()
        CoreapiFreeOSMemory()
        guard let sleepDate else {
            log("Device woke up")
            return
        }
        let sleptSeconds = Date().timeIntervalSince(sleepDate)
        log("Device woke up after \(Int(sleptSeconds))s sleep")
        // After ~45s+ of iOS screen-off sleep, carrier NAT / Wi-Fi routers often silently
        // drop idle TCP/H2/xhttp sockets. Soft-restarting only the proxy engine flushes
        // stale pooled connections without tearing down the iOS VPN tunnel.
        if sleptSeconds >= 45 {
            refreshProxyAfterNetworkChange(reason: "wake after \(Int(sleptSeconds))s sleep")
        }
    }

    func handle(message: String) -> String {
        switch message {
        case "status":
            // IosVpnManager.watchCaptcha polls "status" expecting "" or an http:// captcha URL.
            return ""
        case "reload":
            return "error:restart-required"
        default:
            return ""
        }
    }

    private func startPathMonitoring() {
        pathMonitor?.cancel()
        lock.lock()
        lastPathInitialized = false
        lastPathSatisfied = true
        lastPrimaryInterface = ""
        lock.unlock()

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let satisfied = path.status == .satisfied
            let primaryIface = path.availableInterfaces
                .first(where: { !$0.name.hasPrefix("utun") })?.name ?? ""

            self.lock.lock()
            let initialized = self.lastPathInitialized
            let prevSatisfied = self.lastPathSatisfied
            let prevIface = self.lastPrimaryInterface
            self.lastPathInitialized = true
            self.lastPathSatisfied = satisfied
            if !primaryIface.isEmpty {
                self.lastPrimaryInterface = primaryIface
            }
            self.lock.unlock()

            guard initialized else { return }
            let networkRestored = satisfied && !prevSatisfied
            let interfaceSwitched = satisfied && !primaryIface.isEmpty && !prevIface.isEmpty && primaryIface != prevIface
            guard networkRestored || interfaceSwitched else { return }

            let reason = networkRestored
                ? "network restored (\(primaryIface))"
                : "interface switched (\(prevIface) -> \(primaryIface))"
            self.log("Network path change: \(reason)")
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 1.0) { [weak self] in
                self?.refreshProxyAfterNetworkChange(reason: reason)
            }
        }
        monitor.start(queue: pathQueue)
        pathMonitor = monitor
    }

    /// Soft-restarts ONLY the active proxy engine (sing-box / xray / mieru) in-place while
    /// keeping `utun` and `hev-socks5-tunnel` alive. Fixes the iOS "10-minute sleep" freeze
    /// and Wi-Fi <-> LTE handoff drops by discarding stale NAT / HTTP2 / xmux sockets.
    private func refreshProxyAfterNetworkChange(reason: String) {
        let proxyToRefresh: AppleProxyRuntimePlan
        lock.lock()
        guard running, !isRefreshing, let proxy = activePlan?.proxy else {
            lock.unlock()
            return
        }
        if let lastRefresh = lastRefreshAt, Date().timeIntervalSince(lastRefresh) < 15 {
            lock.unlock()
            return
        }
        isRefreshing = true
        proxyToRefresh = proxy
        lock.unlock()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            defer {
                self.lock.lock()
                self.lastRefreshAt = Date()
                self.isRefreshing = false
                self.lock.unlock()
            }
            guard self.isRunning else { return }
            self.log("Soft-refreshing proxy core (\(reason))...")
            do {
                switch proxyToRefresh.kind {
                case .singBox:
                    CoreapiSbStop()
                    try self.callCore { CoreapiSbStart(proxyToRefresh.config, $0) }
                case .xray:
                    CoreapiXrayStop()
                    try self.callCore { CoreapiXrayStart(proxyToRefresh.config, $0) }
                case .mieru:
                    CoreapiMieruStop()
                    try self.callCore {
                        CoreapiMieruStart(proxyToRefresh.config, "127.0.0.1:\(proxyToRefresh.listenPort)", $0)
                    }
                }
                CoreapiFreeOSMemory()
                self.log("Proxy core refreshed cleanly (\(reason))")
            } catch {
                self.log("Proxy core refresh failed (\(reason)): \(error.localizedDescription)")
            }
        }
    }

    private func startEngines(
        _ plan: AppleNativeRuntimePlan,
        request: AppleTunnelRequest,
        logFilePath: String,
        cacheFilePath: String
    ) throws -> [String] {
        var extraExcludedIPv4: [String] = []

        if let olcrtc = plan.olcrtc {
            try startOlcRtc(olcrtc)
        }
        if !plan.startAmneziaWgAfterProxy {
            let awgIPs = try startAmneziaWg(plan.amneziaWg)
            extraExcludedIPv4.append(contentsOf: awgIPs)
        }
        if let snolc = plan.snolc {
            let ips = try startSnolc(snolc)
            extraExcludedIPv4.append(contentsOf: ips)
        }
        if let csqtt = plan.csqtt {
            let csqttIPs = try startCsqtt(csqtt)
            extraExcludedIPv4.append(contentsOf: csqttIPs)
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
        if plan.startAmneziaWgAfterProxy {
            _ = try startAmneziaWg(plan.amneziaWg)
        }
        return extraExcludedIPv4
    }

    private func startAmneziaWg(_ plan: AppleAmneziaWgRuntimePlan?) throws -> [String] {
        guard let plan else { return [] }
        var excluded: [String] = []
        if let host = extractWgEndpointHost(from: plan.iniConfig),
           host != "127.0.0.1", host != "::1", host.lowercased() != "localhost" {
            if isIPv4(host) {
                excluded.append(host)
            } else if !isIPv6(host), let ip = resolveHostname(host), isIPv4(ip) {
                excluded.append(ip)
            }
        }
        try callCore {
            CoreapiAwgStart(plan.iniConfig, plan.listenAddress, $0)
        }
        return excluded
    }

    private func extractWgEndpointHost(from ini: String) -> String? {
        for line in ini.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.lowercased().hasPrefix("endpoint"),
                  let eq = trimmed.firstIndex(of: "=") else { continue }
            let value = trimmed[trimmed.index(after: eq)...].trimmingCharacters(in: .whitespaces)
            let (host, _) = splitHostPort(value)
            if !host.isEmpty { return host }
        }
        return nil
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

    private func startSnolc(_ plan: AppleSnolcRuntimePlan) throws -> [String] {
        let directory = URL(fileURLWithPath: plan.directory, isDirectory: true)
        let stateDir = directory.appendingPathComponent("state", isDirectory: true)
        let modulesDir = directory.appendingPathComponent("modules", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: modulesDir, withIntermediateDirectories: true)

        let snolcLogURL = stateDir.appendingPathComponent("snolc.log")
        try? FileManager.default.removeItem(at: snolcLogURL)

        var listenPort: UInt16 = 10_808
        var excludedIPv4: [String] = []

        for file in plan.files {
            var contents = file.contents
            if file.relativePath == "snolc.toml" {
                contents = tuneSnolcEngineTomlForIOS(contents)
            } else if file.relativePath == "modules/socks5.toml" {
                contents = contents.replacingOccurrences(of: "max_connections = 128", with: "max_connections = 64")
                if let port = extractListenPort(from: contents) {
                    listenPort = port
                }
            } else if file.relativePath == "modules/tcp.toml" {
                let (updated, resolvedIPv4) = resolveSnolcTcpEndpoint(contents)
                contents = updated
                if let ip = resolvedIPv4 {
                    excludedIPv4.append(ip)
                }
            }
            let url = directory.appendingPathComponent(file.relativePath)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(contents.utf8).write(to: url, options: .atomic)
        }

        log("Starting snolc on 127.0.0.1:\(listenPort) (excluded=\(excludedIPv4.joined(separator: ",")))")
        if let pointer = snolc_ffi_start(plan.configPath) {
            let message = String(cString: pointer)
            snolc_ffi_free_string(pointer)
            throw NativeTunnelRuntimeError.specializedCore("snolc start failed: \(message)")
        }

        let deadline = Date().addingTimeInterval(10.0)
        while Date() < deadline {
            if !snolc_ffi_is_running() {
                let tail = readSnolcLogTail(at: snolcLogURL)
                let detail = tail.isEmpty ? "snolc worker exited during startup" : "snolc exited: \(tail)"
                throw NativeTunnelRuntimeError.specializedCore(detail)
            }
            if isLocalPortOpen(port: listenPort) {
                log("snolc SOCKS5 ready on 127.0.0.1:\(listenPort)")
                return excludedIPv4
            }
            Thread.sleep(forTimeInterval: 0.05)
        }

        let tail = readSnolcLogTail(at: snolcLogURL)
        let detail = tail.isEmpty
            ? "snolc SOCKS5 port \(listenPort) did not open within 10s"
            : "snolc timeout (\(listenPort)): \(tail)"
        throw NativeTunnelRuntimeError.specializedCore(detail)
    }

    private func tuneSnolcEngineTomlForIOS(_ toml: String) -> String {
        var out = toml
        let replacements: [(String, String)] = [
            ("max_sessions = 4", "max_sessions = 2"),
            ("max_flows = 512", "max_flows = 64"),
            ("max_pending_sessions = 4", "max_pending_sessions = 2"),
            ("max_pending_opens = 64", "max_pending_opens = 32"),
            ("max_managed_bytes = 268435456", "max_managed_bytes = 17301504"),
            ("max_streams_per_session = 129", "max_streams_per_session = 33"),
            ("receive_window_bytes = 33816576", "receive_window_bytes = 8650752"),
            ("limit = \"8mb\"", "limit = \"1mb\""),
        ]
        for (old, new) in replacements {
            out = out.replacingOccurrences(of: old, with: new)
        }
        return out
    }

    private func extractListenPort(from toml: String) -> UInt16? {
        for line in toml.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("listen =") else { continue }
            guard let firstQuote = trimmed.firstIndex(of: "\""),
                  let lastQuote = trimmed.lastIndex(of: "\""),
                  firstQuote < lastQuote else { continue }
            let value = String(trimmed[trimmed.index(after: firstQuote)..<lastQuote])
            if let colon = value.lastIndex(of: ":"),
               let port = UInt16(value[value.index(after: colon)...]) {
                return port
            }
        }
        return nil
    }

    private func resolveSnolcTcpEndpoint(_ toml: String) -> (String, String?) {
        var lines = toml.components(separatedBy: "\n")
        var resolvedIPv4: String?
        for i in lines.indices {
            let trimmed = lines[i].trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("endpoint_ip =") else { continue }
            guard let firstQuote = trimmed.firstIndex(of: "\""),
                  let lastQuote = trimmed.lastIndex(of: "\""),
                  firstQuote < lastQuote else { continue }
            let endpoint = String(trimmed[trimmed.index(after: firstQuote)..<lastQuote])
            let (host, portStr) = splitHostPort(endpoint)
            guard !host.isEmpty, !portStr.isEmpty else { continue }
            if isIPv4(host) {
                resolvedIPv4 = host
                continue
            }
            if isIPv6(host) {
                continue
            }
            if let ip = resolveHostname(host) {
                let formatted = ip.contains(":") ? "[\(ip)]:\(portStr)" : "\(ip):\(portStr)"
                lines[i] = "endpoint_ip = \"\(formatted)\""
                if isIPv4(ip) {
                    resolvedIPv4 = ip
                }
                log("Resolved snolc host \(host) -> \(ip)")
            }
        }
        return (lines.joined(separator: "\n"), resolvedIPv4)
    }

    private func splitHostPort(_ endpoint: String) -> (String, String) {
        if endpoint.hasPrefix("["), let close = endpoint.firstIndex(of: "]") {
            let host = String(endpoint[endpoint.index(after: endpoint.startIndex)..<close])
            let rest = endpoint[endpoint.index(after: close)...]
            let port = rest.hasPrefix(":") ? String(rest.dropFirst()) : ""
            return (host, port)
        }
        guard let colon = endpoint.lastIndex(of: ":") else { return (endpoint, "") }
        let host = String(endpoint[..<colon])
        let port = String(endpoint[endpoint.index(after: colon)...])
        return (host, port)
    }

    private func isIPv4(_ host: String) -> Bool {
        var addr = in_addr()
        return host.withCString { inet_pton(AF_INET, $0, &addr) == 1 }
    }

    private func isIPv6(_ host: String) -> Bool {
        var addr = in6_addr()
        return host.withCString { inet_pton(AF_INET6, $0, &addr) == 1 }
    }

    private func resolveHostname(_ host: String) -> String? {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else {
            return nil
        }
        defer { freeaddrinfo(first) }
        var ipv6Fallback: String?
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let current = cursor {
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(
                current.pointee.ai_addr,
                current.pointee.ai_addrlen,
                &buffer,
                socklen_t(buffer.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 {
                let ip = String(cString: buffer)
                if current.pointee.ai_family == AF_INET {
                    return ip
                } else if ipv6Fallback == nil {
                    ipv6Fallback = ip
                }
            }
            cursor = current.pointee.ai_next
        }
        return ipv6Fallback
    }

    private func resolveAllIPv4(_ host: String) -> [String] {
        if isIPv4(host) { return [host] }
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else {
            return []
        }
        defer { freeaddrinfo(first) }
        var ips: [String] = []
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let current = cursor {
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(
                current.pointee.ai_addr,
                current.pointee.ai_addrlen,
                &buffer,
                socklen_t(buffer.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 {
                let ip = String(cString: buffer)
                if !ips.contains(ip) { ips.append(ip) }
            }
            cursor = current.pointee.ai_next
        }
        return ips
    }

    private func isLocalPortOpen(port: UInt16) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                connect(fd, sockPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return result == 0
    }

    private func readSnolcLogTail(at url: URL) -> String {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        let lines = text.split(separator: "\n").suffix(4).map(String.init)
        return lines.joined(separator: " | ")
    }

    private func startCsqtt(_ plan: AppleCsqttRuntimePlan) throws -> [String] {
        var candidateHosts: [String] = ["calls.vk.com", "api.vk.com", "ok.ru"]
        if let data = plan.optionsJson.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let peer = json["peer"] as? String {
                let (host, _) = splitHostPort(peer)
                if !host.isEmpty { candidateHosts.append(host) }
            }
            if let turnHost = json["turn_host"] as? String, !turnHost.isEmpty {
                let (host, _) = splitHostPort(turnHost)
                candidateHosts.append(host.isEmpty ? turnHost : host)
            }
        }
        var excludedIPv4: [String] = []
        for host in Set(candidateHosts) {
            excludedIPv4.append(contentsOf: resolveAllIPv4(host))
        }

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
        return Array(Set(excludedIPv4))
    }

    private func startBridge(yaml: String) throws {
        let configURL = try AppleTunnelStore.url(for: "hev_config.yml")
        try Data(yaml.utf8).write(to: configURL, options: .atomic)
        Socks5Tunnel.run(withConfig: .file(path: configURL)) { code in
            if code != 0 {
                let msg = "hev-socks5-tunnel exited with code \(code)"
                try? AppleTunnelStore.appendLog(msg)
                try? AppleTunnelStore.writeText(msg, to: AppleTunnelStore.errorFileName)
            }
        }
    }

    private func networkSettings(
        from plan: AppleTunnelPlan,
        extraExcludedIPv4: [String] = []
    ) -> NEPacketTunnelNetworkSettings {
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
        var excluded = plan.excludedIpv4Routes.map {
            NEIPv4Route(destinationAddress: $0.address, subnetMask: $0.subnetMask)
        }
        for ip in Set(extraExcludedIPv4) where isIPv4(ip) {
            excluded.append(NEIPv4Route(destinationAddress: ip, subnetMask: "255.255.255.255"))
        }
        ipv4.excludedRoutes = excluded
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

    private func log(_ line: String) {
        NSLog("YPtunTunnel: %@", line)
        try? AppleTunnelStore.appendLog(line)
    }

    private func record(error: Error) {
        let message = error.localizedDescription
        log("Tunnel error: \(message)")
        try? AppleTunnelStore.writeText(message, to: AppleTunnelStore.errorFileName)
        try? AppleTunnelStore.writeText(
            VPNConnectionState.disconnected.rawValue,
            to: AppleTunnelStore.statusFileName
        )
    }

    private func startMemoryPressureMonitoring() {
        memoryPressureSource?.cancel()
        let source = DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical], queue: .global(qos: .utility))
        source.setEventHandler {
            let availableBytes = os_proc_available_memory()
            let availableMB = Double(availableBytes) / 1024.0 / 1024.0
            NSLog("YPtun: Memory pressure warning received. Available: %.1f MB. Freeing Go and system memory...", availableMB)
            CoreapiFreeOSMemory()
        }
        source.resume()
        self.memoryPressureSource = source
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
            if os_proc_available_memory() < 12 * 1024 * 1024 {
                CoreapiFreeOSMemory()
            }
            if self.isCurrentlyRefreshing {
                return
            }
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
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.15) {
                exit(0)
            }
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
        if plan.snolc != nil && !snolc_ffi_is_running() { return false }
        if plan.csqtt != nil && (!csqtt_ffi_is_running() || !CoreapiCsqttBridgeIsRunning()) {
            return false
        }
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

    private var isCurrentlyRefreshing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isRefreshing
    }

    private func setActivePlan(_ plan: AppleNativeRuntimePlan?) {
        lock.lock()
        activePlan = plan
        lock.unlock()
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

enum NativeTunnelRuntimeError: LocalizedError {
    case wdttConfigurationUnavailable
    case wdttRawModeUnavailable
    case specializedCore(String)
    case coreStopped

    var errorDescription: String? {
        switch self {
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
