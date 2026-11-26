import Foundation
import Network
@preconcurrency import NetworkExtension
import Coreapi
import CsqttCore
import Darwin
import SnolcCore
import Tun2SocksKit
@preconcurrency import VpnClientFramework
import YPtunRustBridge
import YPtunShared

final class NativeTunnelRuntime: @unchecked Sendable {
    private let lock = NSLock()
    private var running = false
    private var stopping = false
    private var pumpingLogs = false
    private var watchdogFailures = 0
    private var wdttRawSocksPort: Int? = nil
    private var activePlan: AppleNativeRuntimePlan?
    private var runtimeDirectoryPath: String = ""
    private var snolcLogURL: URL?
    private var snolcLogOffset: UInt64 = 0
    private var trustTunnelClient: VpnClient?
    private var trustTunnelStartHandled = false
    private var trustTunnelStartTimer: DispatchSourceTimer?
    private let trustTunnelQueue = DispatchQueue(label: "org.yptun.tunnel.trusttunnel", qos: .userInitiated)
    private let xrayPingSemaphore = DispatchSemaphore(value: 2)
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
        completion: @escaping @Sendable (Error?) -> Void
    ) {
        do {
            setStopping(false)
            lock.lock()
            wdttRawSocksPort = nil
            lock.unlock()
            self.provider = provider
            let request = try AppleTunnelStore.loadRequest()
            let logURL = try AppleTunnelStore.url(for: AppleTunnelStore.logFileName)
            let cacheURL = try AppleTunnelStore.url(for: AppleTunnelStore.cacheFileName)
            let runtimeDirectory = try AppleTunnelStore.directory()
            setRuntimeDirectoryPath(runtimeDirectory.path)

            if let ttSpec = extractTrustTunnelSpec(from: request) {
                startTrustTunnel(
                    provider: provider,
                    spec: ttSpec,
                    completion: completion
                )
                return
            }

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
            var bridgeYaml = plan.bridge.yaml
            if plan.snolc != nil {
                bridgeYaml = bridgeYaml.replacingOccurrences(of: "udp: 'udp'", with: "udp: 'tcp'")
            }
            lock.lock()
            let rawSocksPort = wdttRawSocksPort
            lock.unlock()
            if let rawSocksPort {
                bridgeYaml = rewriteBridgeSocksPort(bridgeYaml, port: rawSocksPort)
            }
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
        // Mark stopping & non-running FIRST so asynchronous callbacks (such as TrustTunnel's
        // VPN_SS_DISCONNECTED during vpn_stop) never re-enter stop() or call cancelTunnelWithError.
        lock.lock()
        stopping = true
        running = false
        wdttRawSocksPort = nil
        trustTunnelStartTimer?.cancel()
        trustTunnelStartTimer = nil
        let ttClient = trustTunnelClient
        trustTunnelClient = nil
        lock.unlock()

        memoryPressureSource?.cancel()
        memoryPressureSource = nil
        watchdog?.cancel()
        watchdog = nil
        pathMonitor?.cancel()
        pathMonitor = nil
        setActivePlan(nil)
        setPumpingLogs(false)
        flushSnolcLog()
        lock.lock()
        snolcLogURL = nil
        snolcLogOffset = 0
        lock.unlock()
        if let ttClient {
            let ttStopGroup = DispatchGroup()
            ttStopGroup.enter()
            trustTunnelQueue.async {
                _ = ttClient.stop()
                NativeLogger.setCallback(nil)
                ttStopGroup.leave()
            }
            _ = ttStopGroup.wait(timeout: .now() + 0.2)
        }
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
        let ttClient = trustTunnelClient
        lock.unlock()
        if let ttClient {
            log("Device entering sleep; notifying TrustTunnel")
            trustTunnelQueue.async {
                ttClient.notify_sleep()
            }
            return
        }
        log("Device entering sleep; freeing Go memory")
        CoreapiFreeOSMemory()
    }

    func handleWake() {
        lock.lock()
        let sleepDate = lastSleepAt
        lastSleepAt = nil
        let ttClient = trustTunnelClient
        lock.unlock()
        if let ttClient {
            log("Device woke up; notifying TrustTunnel")
            trustTunnelQueue.async {
                ttClient.notify_wake()
            }
            return
        }
        CoreapiFreeOSMemory()
        let sleptSeconds = sleepDate.map { Int(Date().timeIntervalSince($0)) } ?? -1
        log("Device woke up (slept: \(sleptSeconds >= 0 ? "\(sleptSeconds)s" : "unknown"))")
        if CoreapiWdttRunning() {
            log("Device woke up; resetting WDTT sessions...")
            CoreapiWdttResetSessions("device wake")
        }
        refreshProxyAfterNetworkChange(reason: "device wake")
    }

    func handle(message: String, completion: @escaping @Sendable (String) -> Void) {
        if message.hasPrefix("xrayping:") {
            let payload = String(message.dropFirst("xrayping:".count))
            DispatchQueue.global(qos: .utility).async { [weak self] in
                guard let self else {
                    completion("-1")
                    return
                }
                let ms = self.directXrayPing(payload: payload)
                completion(String(ms))
            }
            return
        }
        if message.hasPrefix("tcpping:") {
            let payload = String(message.dropFirst("tcpping:".count))
            DispatchQueue.global(qos: .utility).async { [weak self] in
                guard let self else {
                    completion("-1")
                    return
                }
                let ms = self.directTcpPing(payload: payload)
                completion(String(ms))
            }
            return
        }
        if message.hasPrefix("udpping:") {
            let payload = String(message.dropFirst("udpping:".count))
            DispatchQueue.global(qos: .utility).async { [weak self] in
                guard let self else {
                    completion("-1")
                    return
                }
                let ms = self.directUdpPing(payload: payload)
                completion(String(ms))
            }
            return
        }
        if message.hasPrefix("resolve:") {
            let host = String(message.dropFirst("resolve:".count)).trimmingCharacters(in: .whitespacesAndNewlines)
            DispatchQueue.global(qos: .utility).async { [weak self] in
                guard let self, !host.isEmpty else {
                    completion("")
                    return
                }
                let ip = self.resolveAllIPv4(host).first ?? ""
                completion(ip)
            }
            return
        }
        switch message {
        case "status":
            // IosVpnManager.watchCaptcha polls "status" expecting "" or an http:// captcha URL.
            completion("")
        case "reload":
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self else {
                    completion("error:runtime-gone")
                    return
                }
                self.performFastReload(completion: completion)
            }
        default:
            completion("")
        }
    }

    private func performFastReload(completion: @escaping @Sendable (String) -> Void) {
        lock.lock()
        guard running, !stopping, !isRefreshing, trustTunnelClient == nil else {
            lock.unlock()
            completion("error:restart-required")
            return
        }
        guard let currentPlan = activePlan, let currentProxy = currentPlan.proxy else {
            lock.unlock()
            completion("error:restart-required")
            return
        }
        // Auxiliary or specialized engines (wdtt, snolc, csqtt, olcrtc) require clean tunnel restart
        if !currentPlan.auxiliary.isEmpty || currentPlan.snolc != nil || currentPlan.csqtt != nil || currentPlan.olcrtc != nil || currentPlan.deferredWireguardProxy {
            lock.unlock()
            completion("error:restart-required")
            return
        }
        isRefreshing = true
        lock.unlock()

        defer {
            lock.lock()
            isRefreshing = false
            lock.unlock()
        }

        do {
            let request = try AppleTunnelStore.loadRequest()
            if extractTrustTunnelSpec(from: request) != nil {
                completion("error:restart-required")
                return
            }
            let logURL = try AppleTunnelStore.url(for: AppleTunnelStore.logFileName)
            let cacheURL = try AppleTunnelStore.url(for: AppleTunnelStore.cacheFileName)
            let runtimeDirectory = try AppleTunnelStore.directory()

            let newPlan = try appleNativeRuntimePlan(
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

            guard let newProxy = newPlan.proxy,
                  newPlan.auxiliary.isEmpty,
                  newPlan.snolc == nil,
                  newPlan.csqtt == nil,
                  newPlan.olcrtc == nil,
                  !newPlan.deferredWireguardProxy else {
                completion("error:restart-required")
                return
            }

            log("Fast-reloading proxy core: \(currentPlan.displayName) -> \(newPlan.displayName)")

            // Stop existing proxy and immediately free OS memory
            switch currentProxy.kind {
            case .singBox:
                CoreapiSbStop()
            case .xray:
                CoreapiXrayStop()
            case .mieru:
                CoreapiMieruStop()
            }
            CoreapiFreeOSMemory()

            // If available memory is critically low (< 4 MiB) even after stopping the old proxy, fall back to clean restart
            if os_proc_available_memory() < 4 * 1024 * 1024 {
                log("Available memory critically low (\(os_proc_available_memory() / 1024 / 1024) MiB) after stopping proxy; requiring restart")
                completion("error:restart-required")
                return
            }

            // Start new proxy
            switch newProxy.kind {
            case .singBox:
                try callCore { CoreapiSbStart(newProxy.config, $0) }
            case .xray:
                try startXrayWithIOSLimits(config: newProxy.config)
            case .mieru:
                try callCore {
                    CoreapiMieruStart(
                        newProxy.config,
                        "127.0.0.1:\(newProxy.listenPort)",
                        $0
                    )
                }
            }

            setActivePlan(newPlan)
            resetWatchdogFailures()
            CoreapiFreeOSMemory()
            log("Fast switch to \(newPlan.displayName) succeeded cleanly")
            completion("ok")
        } catch {
            log("Fast reload failed: \(error.localizedDescription); requiring restart")
            completion("error:restart-required")
        }
    }

    /// Real end-to-end proxy URL test executed inside PacketTunnelProvider (bypassing utun),
    /// throttled to 2 concurrent probes and guarded by available-memory check so it stays safely
    /// within the 50 MiB PacketTunnel Jetsam budget.
    /// Payload format: `<timeoutMs>|<method>|<url>|<configJson>`
    private func directXrayPing(payload: String) -> Int64 {
        let parts = payload.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count == 4,
              let rawTimeout = Int(parts[0]) else { return -1 }
        let method = String(parts[1])
        let url = String(parts[2])
        let configJson = String(parts[3])
        guard !url.isEmpty, !configJson.isEmpty else { return -1 }
        let timeoutMs = min(max(rawTimeout, 1_500), 12_000)

        guard xrayPingSemaphore.wait(timeout: .now() + .milliseconds(timeoutMs)) == .success else {
            return -1
        }
        defer {
            xrayPingSemaphore.signal()
            CoreapiFreeOSMemory()
        }
        guard !isStopping else { return -1 }
        // Guard against Jetsam OOM: require at least 14 MiB headroom before spawning a throwaway Xray.
        if os_proc_available_memory() < 14 * 1024 * 1024 {
            CoreapiFreeOSMemory()
            if os_proc_available_memory() < 12 * 1024 * 1024 {
                return -2 // Caller may fall back to direct TCP ping
            }
        }
        let tunedConfig = tuneXrayConfigForIOS(configJson, stripGeoSelectors: false, stripOnlyTag: nil)
        return CoreapiXrayMeasureDelay(tunedConfig, url, method, timeoutMs)
    }

    /// Direct non-blocking TCP ping executed inside PacketTunnelProvider, where iOS guarantees
    /// both DNS and sockets bypass the VPN utun interface and use the physical Wi-Fi/LTE path.
    /// Payload format: `<host>|<port>|<timeoutMs>` (or legacy `<host>:<port>:<timeoutMs>`).
    private func directTcpPing(payload: String) -> Int64 {
        let parts = payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2 else { return -1 }
        let host = parts[0].trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty,
              let port = Int(parts[1]), port > 0, port <= 65535 else { return -1 }
        let lower = host.lowercased()
        if lower == "127.0.0.1" || lower == "localhost" || lower == "::1" || lower == "0.0.0.0" {
            return -1
        }
        let timeoutMs = min(max((parts.count >= 3 ? Int(parts[2]) : nil) ?? 4_000, 500), 10_000)

        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let first = res else {
            return -1
        }
        defer { freeaddrinfo(first) }

        // Prefer IPv4 address if available, falling back to first returned address.
        var chosen: UnsafeMutablePointer<addrinfo> = first
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let cur = cursor {
            if cur.pointee.ai_family == AF_INET {
                chosen = cur
                break
            }
            cursor = cur.pointee.ai_next
        }

        let fd = socket(chosen.pointee.ai_family, chosen.pointee.ai_socktype, chosen.pointee.ai_protocol)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        let start = DispatchTime.now()
        let ret = connect(fd, chosen.pointee.ai_addr, chosen.pointee.ai_addrlen)
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

    private func directUdpPing(payload: String) -> Int64 {
        let parts = payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2 else { return -1 }
        let host = parts[0].trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty,
              let port = Int(parts[1]), port > 0, port <= 65535 else { return -1 }
        let lower = host.lowercased()
        if lower == "127.0.0.1" || lower == "localhost" || lower == "::1" || lower == "0.0.0.0" {
            return -1
        }
        let timeoutMs = min(max((parts.count >= 3 ? Int(parts[2]) : nil) ?? 4_000, 500), 10_000)

        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_DGRAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let first = res else {
            return -1
        }
        defer { freeaddrinfo(first) }

        var chosen: UnsafeMutablePointer<addrinfo> = first
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let cur = cursor {
            if cur.pointee.ai_family == AF_INET {
                chosen = cur
                break
            }
            cursor = cur.pointee.ai_next
        }

        let fd = socket(chosen.pointee.ai_family, chosen.pointee.ai_socktype, chosen.pointee.ai_protocol)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        var packet = [UInt8](repeating: 0, count: 1200)
        packet[0] = 0xC3
        packet[1] = 0x5A
        packet[2] = 0x5A
        packet[3] = 0x5A
        packet[4] = 0x5A
        packet[5] = 0x08
        for i in 6..<14 { packet[i] = UInt8(truncatingIfNeeded: i * 37) }
        packet[14] = 0x08
        for i in 15..<23 { packet[i] = UInt8(truncatingIfNeeded: i * 53) }

        let start = DispatchTime.now()
        let sent = sendto(fd, packet, packet.count, 0, chosen.pointee.ai_addr, chosen.pointee.ai_addrlen)
        guard sent > 0 else { return -1 }

        var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        let pollRet = poll(&pfd, 1, Int32(timeoutMs))
        if pollRet > 0 && (pfd.revents & Int16(POLLIN)) != 0 {
            var buf = [UInt8](repeating: 0, count: 1500)
            let recvd = recv(fd, &buf, buf.count, 0)
            if recvd > 0 {
                let elapsed = DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds
                return max(1, Int64(elapsed / 1_000_000))
            }
        }
        return -1
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

    /// Soft-restarts active network engines (AmneziaWG / sing-box / xray / mieru / wdtt) in-place while
    /// keeping `utun` and `hev-socks5-tunnel` alive. Fixes the iOS "10-minute sleep" freeze
    /// and Wi-Fi <-> LTE handoff drops by discarding stale NAT / UDP / HTTP2 sockets.
    private func refreshProxyAfterNetworkChange(reason: String) {
        if CoreapiWdttRunning() {
            log("Resetting WDTT sessions on network change: \(reason)")
            CoreapiWdttResetSessions(reason)
        }
        let proxyToRefresh: AppleProxyRuntimePlan?
        let awgToRefresh: AppleAmneziaWgRuntimePlan?
        lock.lock()
        guard running, !isRefreshing else {
            lock.unlock()
            return
        }
        proxyToRefresh = activePlan?.proxy
        awgToRefresh = activePlan?.amneziaWg
        if proxyToRefresh == nil && (awgToRefresh == nil || !CoreapiAwgRunning()) {
            lock.unlock()
            return
        }
        if let lastRefresh = lastRefreshAt, Date().timeIntervalSince(lastRefresh) < 5 {
            lock.unlock()
            return
        }
        isRefreshing = true
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
            if let awg = awgToRefresh, CoreapiAwgRunning() {
                self.log("Refreshing AmneziaWG core (\(reason))...")
                CoreapiAwgStop()
                do {
                    try self.callCore {
                        CoreapiAwgStart(awg.iniConfig, awg.listenAddress, $0)
                    }
                    self.resetWatchdogFailures()
                    self.log("AmneziaWG core refreshed cleanly (\(reason))")
                } catch {
                    self.log("AmneziaWG core refresh failed (\(reason)): \(error.localizedDescription)")
                }
            }
            if let proxyToRefresh {
                self.log("Soft-refreshing proxy core (\(reason))...")
                do {
                    switch proxyToRefresh.kind {
                    case .singBox:
                        CoreapiSbStop()
                        try self.callCore { CoreapiSbStart(proxyToRefresh.config, $0) }
                    case .xray:
                        CoreapiXrayStop()
                        try self.startXrayWithIOSLimits(config: proxyToRefresh.config)
                    case .mieru:
                        CoreapiMieruStop()
                        try self.callCore {
                            CoreapiMieruStart(proxyToRefresh.config, "127.0.0.1:\(proxyToRefresh.listenPort)", $0)
                        }
                    }
                    self.resetWatchdogFailures()
                    CoreapiFreeOSMemory()
                    self.log("Proxy core refreshed cleanly (\(reason))")
                } catch {
                    self.log("Proxy core refresh failed (\(reason)): \(error.localizedDescription)")
                }
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
            let endpointsStr = CoreapiTurnableEndpoints(turnable.config)
            for ep in endpointsStr.split(separator: ",").map(String.init) where !ep.isEmpty {
                extraExcludedIPv4.append(contentsOf: resolveAllIPv4(ep))
            }
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
            let tunedOptions = tuneAuxiliaryOptionsForIOS(kind: kind, optionsJson: auxiliary.optionsJson)
            try callCore {
                CoreapiAppleEngineStart(kind, tunedOptions, $0)
            }
        }
        if plan.deferredWireguardProxy {
            let negotiated = CoreapiWdttWaitConfig(45_000)
            guard !negotiated.isEmpty, !negotiated.hasPrefix("ERROR:") else {
                throw NativeTunnelRuntimeError.wdttConfigurationUnavailable
            }
            if negotiated.hasPrefix("RAWCONF:") {
                let socksPort = extractWdttListenPort(from: plan.auxiliary) ?? 10_808
                lock.lock()
                wdttRawSocksPort = socksPort
                lock.unlock()
                log("VK-TURN WDTT Raw up (\(negotiated.dropFirst("RAWCONF:".count))); SOCKS on 127.0.0.1:\(socksPort)")
            } else {
                lock.lock()
                wdttRawSocksPort = nil
                lock.unlock()
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
        }
        if let proxy = plan.proxy {
            switch proxy.kind {
            case .singBox:
                try callCore { CoreapiSbStart(proxy.config, $0) }
            case .xray:
                try startXrayWithIOSLimits(config: proxy.config)
            case .mieru:
                let endpointsStr = CoreapiMieruEndpoints(proxy.config)
                for ep in endpointsStr.split(separator: ",").map(String.init) where !ep.isEmpty {
                    extraExcludedIPv4.append(contentsOf: resolveAllIPv4(ep))
                }
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
            excluded.append(contentsOf: resolveAllIPv4(host))
        }
        try callCore {
            CoreapiAwgStart(plan.iniConfig, plan.listenAddress, $0)
        }
        return Array(Set(excluded))
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
            let cappedRooms = capOlcRtcRoomsForIOS(plan.roomsJson)
            try callCore {
                CoreapiRtcStartPool(
                    cappedRooms,
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
        lock.lock()
        self.snolcLogURL = snolcLogURL
        self.snolcLogOffset = 0
        lock.unlock()

        var listenPort: UInt16 = 10_808
        var excludedIPv4: [String] = []

        for file in plan.files {
            var contents = file.contents
            if file.relativePath == "snolc.toml" {
                contents = tuneSnolcEngineTomlForIOS(contents)
            } else if file.relativePath == "modules/socks5.toml" {
                contents = contents.replacingOccurrences(of: "max_connections = 128", with: "max_connections = 119")
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
        // Single client session on iOS with 119 concurrent user streams (max_streams_per_session = 120)
        // so iPhone background connections/DNS never hit "yamux user stream limit is exhausted",
        // while keeping lazy socket buffers (32 KiB TCP / 64 KiB UDP) and 32 MiB ceiling inside Jetsam.
        // Invariants: 120 * 262144 = 31,457,280 <= max_managed_bytes (33,554,432), max_flows (128) >= 1 * 119.
        let replacements: [(String, String)] = [
            ("max_sessions = 4", "max_sessions = 1"),
            ("max_flows = 256", "max_flows = 128"),
            ("max_flows = 512", "max_flows = 128"),
            ("max_pending_sessions = 4", "max_pending_sessions = 1"),
            ("max_pending_opens = 64", "max_pending_opens = 32"),
            ("max_managed_bytes = 268435456", "max_managed_bytes = 33554432"),
            ("max_commands = 64", "max_commands = 32"),
            ("max_events = 256", "max_events = 128"),
            ("tcp_socket_rx_bytes = 65536", "tcp_socket_rx_bytes = 32768"),
            ("tcp_socket_tx_bytes = 65536", "tcp_socket_tx_bytes = 32768"),
            ("udp_socket_rx_bytes = 131072", "udp_socket_rx_bytes = 65536"),
            ("udp_socket_tx_bytes = 131072", "udp_socket_tx_bytes = 65536"),
            ("max_streams_per_session = 65", "max_streams_per_session = 120"),
            ("max_streams_per_session = 129", "max_streams_per_session = 120"),
            ("receive_window_bytes = 17039360", "receive_window_bytes = 31457280"),
            ("receive_window_bytes = 33816576", "receive_window_bytes = 31457280"),
            ("levels = [\"warning\", \"error\"]", "levels = [\"warning\", \"error\", \"debug\"]"),
            ("limit = \"8mb\"", "limit = \"1mb\""),
        ]
        for (old, new) in replacements {
            out = out.replacingOccurrences(of: old, with: new)
        }
        return out
    }

    private func flushSnolcLog() {
        lock.lock()
        guard let url = snolcLogURL else {
            lock.unlock()
            return
        }
        let offset = snolcLogOffset
        lock.unlock()

        guard let handle = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? handle.close() }
        let fileSize = handle.seekToEndOfFile()
        guard fileSize > offset else { return }
        handle.seek(toFileOffset: offset)
        let chunk = handle.readData(ofLength: Int(min(fileSize - offset, 16_384)))
        let newOffset = offset + UInt64(chunk.count)

        lock.lock()
        if snolcLogURL == url {
            snolcLogOffset = newOffset
        }
        lock.unlock()

        guard let text = String(data: chunk, encoding: .utf8) else { return }
        for raw in text.split(separator: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !line.isEmpty {
                try? AppleTunnelStore.appendLog("snolc: \(line)")
            }
        }
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
                self?.flushSnolcLog()
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
        resetWatchdogFailures()
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if os_proc_available_memory() < 16 * 1024 * 1024 {
                CoreapiFreeOSMemory()
            }
            guard let currentPlan = self.activePlan else { return }
            if currentPlan.snolc != nil {
                self.flushSnolcLog()
            }
            if self.isCurrentlyRefreshing {
                return
            }
            if self.coresRunning(currentPlan) {
                self.resetWatchdogFailures()
                return
            }
            let isSlow = !currentPlan.auxiliary.isEmpty || currentPlan.turnable != nil || currentPlan.snolc != nil || currentPlan.csqtt != nil || currentPlan.proxy?.kind == .mieru
            let limit = isSlow ? 6 : 3
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
        } else if plan.deferredWireguardProxy {
            lock.lock()
            let isRaw = wdttRawSocksPort != nil
            lock.unlock()
            if !isRaw && !CoreapiSbRunning() {
                return false
            }
        }
        return true
    }

    private var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }

    private var isStopping: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopping
    }

    private func setStopping(_ value: Bool) {
        lock.lock()
        stopping = value
        lock.unlock()
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

    private func setRuntimeDirectoryPath(_ path: String) {
        lock.lock()
        runtimeDirectoryPath = path
        lock.unlock()
    }

    private func getRuntimeDirectoryPath() -> String {
        lock.lock()
        defer { lock.unlock() }
        return runtimeDirectoryPath
    }

    // MARK: - Xray Geo Assets & iOS Memory Limits

    private func startXrayWithIOSLimits(config: String) throws {
        let dir = getRuntimeDirectoryPath()
        let geoDir = URL(fileURLWithPath: dir, isDirectory: true).appendingPathComponent("geo", isDirectory: true)
        let geoipPath = geoDir.appendingPathComponent("geoip.dat").path
        let geositePath = geoDir.appendingPathComponent("geosite.dat").path
        let fm = FileManager.default
        let geoipSize = (try? fm.attributesOfItem(atPath: geoipPath)[.size] as? NSNumber)?.intValue ?? 0
        let geositeSize = (try? fm.attributesOfItem(atPath: geositePath)[.size] as? NSNumber)?.intValue ?? 0
        let hasGeoAssets = geoipSize >= 65_536 && geositeSize >= 65_536
        if hasGeoAssets {
            CoreapiXraySetAssetPath(geoDir.path)
            log("Xray geo assets active: \(geoDir.path)")
        } else {
            CoreapiXraySetAssetPath("")
            log("Xray geo assets missing (geoip=\(geoipSize)B, geosite=\(geositeSize)B); stripping geosite/geoip selectors")
        }

        var currentConfig = tuneXrayConfigForIOS(config, stripGeoSelectors: !hasGeoAssets, stripOnlyTag: nil)
        var strippedTags: Set<String> = []
        for attempt in 0..<6 {
            do {
                try callCore { CoreapiXrayStart(currentConfig, $0) }
                return
            } catch {
                let rawMsg = error.localizedDescription
                let msg = rawMsg.lowercased()
                let isGeoError = hasGeoAssets && (
                    msg.contains("geosite") || msg.contains("geoip") ||
                    msg.contains("list not found") || msg.contains("failed to load") ||
                    msg.contains("failed to check code")
                )
                guard isGeoError else { throw error }

                // Prefer stripping ONLY the single missing selector (e.g. `geosite:ru` absent from
                // russia-v2ray-rules-dat) so all remaining selectors (`geosite:category-ru`,
                // `geosite:yandex`, `geoip:ru`, `geoip:private`) stay active!
                if let badTag = extractFailingGeoSelector(from: rawMsg), !strippedTags.contains(badTag.lowercased()) {
                    strippedTags.insert(badTag.lowercased())
                    log("Xray missing geo selector '\(badTag)'; stripping only '\(badTag)' (attempt \(attempt + 1))")
                    currentConfig = tuneXrayConfigForIOS(currentConfig, stripGeoSelectors: false, stripOnlyTag: badTag)
                    continue
                }

                log("Xray geo selector error (\(rawMsg)); retrying with all geo selectors stripped")
                let fallback = tuneXrayConfigForIOS(config, stripGeoSelectors: true, stripOnlyTag: nil)
                try callCore { CoreapiXrayStart(fallback, $0) }
                return
            }
        }
        let fallback = tuneXrayConfigForIOS(config, stripGeoSelectors: true, stripOnlyTag: nil)
        try callCore { CoreapiXrayStart(fallback, $0) }
    }

    private func extractFailingGeoSelector(from message: String) -> String? {
        for marker in ["illegal domain rule: ", "illegal ip rule: "] {
            if let range = message.range(of: marker, options: .caseInsensitive) {
                let tail = message[range.upperBound...]
                let token = tail.prefix { !$0.isWhitespace && $0 != ">" && $0 != "," && $0 != ";" }
                let tag = String(token).trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
                let lower = tag.lowercased()
                if lower.hasPrefix("geosite:") || lower.hasPrefix("geoip:") {
                    return tag
                }
            }
        }
        return nil
    }

    /// Injects iOS-safe per-connection buffer/timeout policy into Xray JSON (8 KiB bufferSize
    /// instead of Xray's 512 KiB desktop default) and optionally strips geosite:/geoip: selectors
    /// while preserving all domain:/regexp:/ip rules.
    private func tuneXrayConfigForIOS(
        _ configJson: String,
        stripGeoSelectors: Bool,
        stripOnlyTag: String?
    ) -> String {
        guard let data = configJson.data(using: .utf8),
              var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return configJson
        }

        // Enforce iOS memory-safe Xray policy (prevents 50 MiB Jetsam OOM under parallel connections).
        var policy = (root["policy"] as? [String: Any]) ?? [:]
        var levels = (policy["levels"] as? [String: Any]) ?? [:]
        var level0 = (levels["0"] as? [String: Any]) ?? [:]
        let currentBuf = (level0["bufferSize"] as? Int) ?? 512
        if currentBuf <= 0 || currentBuf > 8 {
            level0["bufferSize"] = 8
        }
        let currentIdle = (level0["connIdle"] as? Int) ?? 300
        if currentIdle <= 0 || currentIdle > 120 {
            level0["connIdle"] = 120
        }
        level0["uplinkOnly"] = 2
        level0["downlinkOnly"] = 5
        levels["0"] = level0
        policy["levels"] = levels
        root["policy"] = policy

        if stripGeoSelectors || stripOnlyTag != nil {
            let shouldDrop: (String) -> Bool = { entry in
                let lower = entry.lowercased()
                if let tag = stripOnlyTag?.lowercased() {
                    return lower == tag
                }
                return lower.hasPrefix("geosite:") || lower.hasPrefix("geoip:")
            }

            if var routing = root["routing"] as? [String: Any],
               let rules = routing["rules"] as? [[String: Any]] {
                var filteredRules: [[String: Any]] = []
                for var rule in rules {
                    var droppedDomain = false
                    var droppedIp = false
                    if let domains = rule["domain"] as? [String] {
                        let kept = domains.filter { !shouldDrop($0) }
                        if kept.isEmpty {
                            rule.removeValue(forKey: "domain")
                            droppedDomain = true
                        } else {
                            rule["domain"] = kept
                        }
                    }
                    if let ips = rule["ip"] as? [String] {
                        let kept = ips.filter { !shouldDrop($0) }
                        if kept.isEmpty {
                            rule.removeValue(forKey: "ip")
                            droppedIp = true
                        } else {
                            rule["ip"] = kept
                        }
                    }
                    let hasOtherMatcher = rule["domain"] != nil || rule["ip"] != nil ||
                        rule["protocol"] != nil || rule["port"] != nil ||
                        rule["network"] != nil || rule["inboundTag"] != nil ||
                        rule["source"] != nil
                    if (droppedDomain || droppedIp) && !hasOtherMatcher {
                        continue
                    }
                    filteredRules.append(rule)
                }
                routing["rules"] = filteredRules
                root["routing"] = routing
            }

            if var dns = root["dns"] as? [String: Any],
               let servers = dns["servers"] as? [Any] {
                var filteredServers: [Any] = []
                for item in servers {
                    if var serverObj = item as? [String: Any],
                       let domains = serverObj["domains"] as? [String] {
                        let kept = domains.filter { !shouldDrop($0) }
                        if kept.isEmpty {
                            continue
                        }
                        serverObj["domains"] = kept
                        filteredServers.append(serverObj)
                    } else {
                        filteredServers.append(item)
                    }
                }
                dns["servers"] = filteredServers
                root["dns"] = dns
            }
        }

        // Enforce iOS memory caps on outbounds (xhttp/splithttp chunk sizes, buffers, and xmux connections).
        if var outbounds = root["outbounds"] as? [[String: Any]] {
            for i in 0..<outbounds.count {
                outbounds[i] = sanitizeOutboundForIOS(outbounds[i])
            }
            root["outbounds"] = outbounds
        }
        if var singleOutbound = root["outbound"] as? [String: Any] {
            root["outbound"] = sanitizeOutboundForIOS(singleOutbound)
        }

        guard let outData = try? JSONSerialization.data(withJSONObject: root),
              let outString = String(data: outData, encoding: .utf8) else {
            return configJson
        }
        return outString
    }

    // MARK: - XHTTP & XMUX Tuning for iOS Jetsam limits

    private func clampRangeOrInt(_ value: Any?, minVal: Int, maxVal: Int, defaultVal: String) -> Any {
        guard let value else {
            return defaultVal
        }
        if let num = value as? NSNumber {
            let intVal = num.intValue
            if intVal < minVal { return minVal }
            if intVal > maxVal { return maxVal }
            return intVal
        }
        if let str = value as? String {
            let trimmed = str.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { return defaultVal }
            if let singleInt = Int(trimmed) {
                if singleInt < minVal { return minVal }
                if singleInt > maxVal { return maxVal }
                return singleInt
            }
            let parts = trimmed.split(separator: "-").map { Int($0.trimmingCharacters(in: .whitespaces)) }
            if parts.count == 2, let low = parts[0], let high = parts[1] {
                let clampedLow = min(max(minVal, low), maxVal)
                let clampedHigh = max(clampedLow, min(maxVal, high))
                if clampedLow == clampedHigh && minVal < maxVal {
                    return "\(minVal)-\(maxVal)"
                }
                return "\(clampedLow)-\(clampedHigh)"
            }
            return defaultVal
        }
        if var dict = value as? [String: Any] {
            let fromVal = (dict["from"] as? NSNumber)?.intValue ?? minVal
            let toVal = (dict["to"] as? NSNumber)?.intValue ?? maxVal
            let clampedFrom = min(max(minVal, fromVal), maxVal)
            let clampedTo = max(clampedFrom, min(maxVal, toVal))
            dict["from"] = clampedFrom
            dict["to"] = clampedTo
            return dict
        }
        return defaultVal
    }

    private func sanitizeXhttpSettingsForIOS(_ original: [String: Any]) -> [String: Any] {
        var s = original

        // 1. scMaxBufferedPosts: default in Xray is 30! Clamp to 2 for iOS to prevent buffering tens of MBs.
        let bufPosts = (s["scMaxBufferedPosts"] as? NSNumber)?.intValue ?? 0
        if bufPosts <= 0 || bufPosts > 2 {
            s["scMaxBufferedPosts"] = 2
        }

        // 2. scMaxEachPostBytes: clamp to 250 KB max ("150000-250000" or int 250000).
        // Xray default is 1,000,000 (1 MB) if omitted!
        s["scMaxEachPostBytes"] = clampRangeOrInt(
            s["scMaxEachPostBytes"],
            minVal: 100_000,
            maxVal: 250_000,
            defaultVal: "150000-250000"
        )

        // 3. xmux settings
        var xmux = (s["xmux"] as? [String: Any]) ?? [:]
        // maxConnections: on iOS 15MB Jetsam ceiling, clamp to max 2 parallel connections
        xmux["maxConnections"] = clampRangeOrInt(
            xmux["maxConnections"],
            minVal: 1,
            maxVal: 2,
            defaultVal: "1-2"
        )
        // maxConcurrency: clamp to 4
        if xmux["maxConcurrency"] != nil {
            xmux["maxConcurrency"] = clampRangeOrInt(
                xmux["maxConcurrency"],
                minVal: 1,
                maxVal: 4,
                defaultVal: "1-4"
            )
        }
        // hMaxReusableSecs: retire idle H2 connections after 180-300s (3-5 min) so Go can collect memory
        // Instead of desktop 1800-3000s (30-50 min)
        xmux["hMaxReusableSecs"] = clampRangeOrInt(
            xmux["hMaxReusableSecs"],
            minVal: 60,
            maxVal: 300,
            defaultVal: "180-300"
        )
        s["xmux"] = xmux

        // 4. Handle nested extra if present
        if let extraStr = s["extra"] as? String,
           let extraData = extraStr.data(using: .utf8),
           var extraDict = (try? JSONSerialization.jsonObject(with: extraData)) as? [String: Any] {
            extraDict = sanitizeXhttpSettingsForIOS(extraDict)
            if let newExtraData = try? JSONSerialization.data(withJSONObject: extraDict),
               let newExtraStr = String(data: newExtraData, encoding: .utf8) {
                s["extra"] = newExtraStr
            }
        } else if let extraDict = s["extra"] as? [String: Any] {
            s["extra"] = sanitizeXhttpSettingsForIOS(extraDict)
        }

        // 5. Handle downloadSettings if present
        if var dl = s["downloadSettings"] as? [String: Any] {
            if let dlXhttp = dl["xhttpSettings"] as? [String: Any] {
                dl["xhttpSettings"] = sanitizeXhttpSettingsForIOS(dlXhttp)
            }
            if let dlSplit = dl["splithttpSettings"] as? [String: Any] {
                dl["splithttpSettings"] = sanitizeXhttpSettingsForIOS(dlSplit)
            }
            s["downloadSettings"] = dl
        }

        return s
    }

    private func sanitizeOutboundForIOS(_ outbound: [String: Any]) -> [String: Any] {
        var out = outbound
        if var mux = out["mux"] as? [String: Any], (mux["enabled"] as? Bool) == true {
            let concurrency = (mux["concurrency"] as? NSNumber)?.intValue ?? 0
            if concurrency <= 0 || concurrency > 4 {
                mux["concurrency"] = 4
            }
            out["mux"] = mux
        }

        if var stream = out["streamSettings"] as? [String: Any] {
            let net = (stream["network"] as? String)?.lowercased() ?? ""
            var modified = false
            if let xhttp = stream["xhttpSettings"] as? [String: Any] {
                stream["xhttpSettings"] = sanitizeXhttpSettingsForIOS(xhttp)
                modified = true
            }
            if let split = stream["splithttpSettings"] as? [String: Any] {
                stream["splithttpSettings"] = sanitizeXhttpSettingsForIOS(split)
                modified = true
            }
            if !modified && (net == "xhttp" || net == "splithttp") {
                let key = (net == "splithttp") ? "splithttpSettings" : "xhttpSettings"
                stream[key] = sanitizeXhttpSettingsForIOS([:])
            }
            out["streamSettings"] = stream
        }
        return out
    }

    // MARK: - iOS Memory Caps for Auxiliary Cores (qWDTT, MasterDNS, olcRTC)

    private func tuneAuxiliaryOptionsForIOS(kind: String, optionsJson: String) -> String {
        guard let data = optionsJson.data(using: .utf8),
              var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return optionsJson
        }
        switch kind {
        case "wdtt":
            // qWDTT subscriptions often ship desktop worker counts (27–108); clamp to 1..18 on iOS
            // so WebRTC/TURN buffers stay safely inside the 50 MiB PacketTunnel Jetsam budget.
            if let innerStr = root["options_json"] as? String,
               let innerData = innerStr.data(using: .utf8),
               var inner = (try? JSONSerialization.jsonObject(with: innerData)) as? [String: Any] {
                let rawWorkers = (inner["workers"] as? Int) ?? 0
                let clamped = rawWorkers <= 0 ? 9 : min(max(rawWorkers, 1), 18)
                if rawWorkers != clamped {
                    log("qWDTT: clamping workers \(rawWorkers) -> \(clamped) for iOS memory limit")
                    inner["workers"] = clamped
                    if let patchedData = try? JSONSerialization.data(withJSONObject: inner),
                       let patchedStr = String(data: patchedData, encoding: .utf8) {
                        root["options_json"] = patchedStr
                    }
                }
            }
        case "masterdns":
            let dup = (root["packet_duplication"] as? Int) ?? 1
            if dup > 2 {
                log("MasterDNS: clamping packet_duplication \(dup) -> 2 for iOS memory limit")
                root["packet_duplication"] = 2
            }
            root["upload_compression"] = 0
            root["download_compression"] = 0
            if let resolvers = root["resolvers"] as? String {
                let parts = resolvers
                    .split(whereSeparator: { $0 == "," || $0 == "\n" || $0 == " " || $0 == "\t" })
                    .map(String.init)
                    .filter { !$0.isEmpty }
                if parts.count > 16 {
                    log("MasterDNS: capping resolvers \(parts.count) -> 16 for iOS memory limit")
                    root["resolvers"] = parts.prefix(16).joined(separator: ",")
                }
            }
        default:
            return optionsJson
        }
        guard let outData = try? JSONSerialization.data(withJSONObject: root),
              let outStr = String(data: outData, encoding: .utf8) else {
            return optionsJson
        }
        return outStr
    }

    private func capOlcRtcRoomsForIOS(_ roomsJson: String) -> String {
        guard let data = roomsJson.data(using: .utf8),
              let rooms = (try? JSONSerialization.jsonObject(with: data)) as? [Any],
              rooms.count > 4 else {
            return roomsJson
        }
        log("olcRTC: capping room pool \(rooms.count) -> 4 for iOS memory limit")
        let capped = Array(rooms.prefix(4))
        guard let outData = try? JSONSerialization.data(withJSONObject: capped),
              let outStr = String(data: outData, encoding: .utf8) else {
            return roomsJson
        }
        return outStr
    }

    // MARK: - TrustTunnel (AdGuard VpnClientFramework)

    private struct TrustTunnelSpec {
        let displayName: String
        let ttConfig: String
        let mtu: Int
        let bypassLan: Bool
    }

    private func extractTrustTunnelSpec(from request: AppleTunnelRequest) -> TrustTunnelSpec? {
        guard let locData = request.locationJSON.data(using: .utf8),
              let loc = (try? JSONSerialization.jsonObject(with: locData)) as? [String: Any] else {
            return nil
        }
        if let engine = loc["engine"] as? String,
           !engine.isEmpty,
           engine.caseInsensitiveCompare("Standard") != .orderedSame {
            return nil
        }
        guard let proxy = loc["proxy"] as? [String: Any],
              let proxyType = proxy["type"] as? String,
              proxyType.caseInsensitiveCompare("trusttunnel") == .orderedSame,
              let ttConfig = proxy["tt_config"] as? String,
              !ttConfig.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        let name = (loc["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let tag = (proxy["tag"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayName = (name?.isEmpty == false ? name : nil)
            ?? (tag?.isEmpty == false ? tag : nil)
            ?? "Trust Tunnel"

        var mtu = 1280
        if let tData = request.trafficJSON.data(using: .utf8),
           let traffic = (try? JSONSerialization.jsonObject(with: tData)) as? [String: Any],
           let parsedMtu = traffic["mtu"] as? Int, parsedMtu >= 1200 && parsedMtu <= 9000 {
            mtu = parsedMtu
        }

        var bypassLan = true
        if let rData = request.routingJSON.data(using: .utf8),
           let routing = (try? JSONSerialization.jsonObject(with: rData)) as? [String: Any],
           let parsedBypass = routing["bypass_lan"] as? Bool {
            bypassLan = parsedBypass
        }

        return TrustTunnelSpec(
            displayName: displayName,
            ttConfig: ttConfig.trimmingCharacters(in: .whitespacesAndNewlines),
            mtu: mtu,
            bypassLan: bypassLan
        )
    }

    private func startTrustTunnel(
        provider: NEPacketTunnelProvider,
        spec: TrustTunnelSpec,
        completion: @escaping @Sendable (Error?) -> Void
    ) {
        log("Starting TrustTunnel: \(spec.displayName)")
        startMemoryPressureMonitoring()

        NativeLogger.setLogLevel(.info)
        NativeLogger.setCallback { _, message in
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                try? AppleTunnelStore.appendLog("trusttunnel: \(trimmed)")
            }
        }

        let endpointToml: String
        do {
            if spec.ttConfig.lowercased().hasPrefix("tt://") {
                endpointToml = try TrustTunnelDeepLink.decodeDeeplink(spec.ttConfig)
            } else {
                endpointToml = spec.ttConfig
            }
        } catch {
            let err = NativeTunnelRuntimeError.specializedCore("TrustTunnel invalid config: \(error.localizedDescription)")
            record(error: err)
            stop()
            completion(err)
            return
        }

        var effectiveEndpointToml = endpointToml.trimmingCharacters(in: .whitespacesAndNewlines)
        var dnsUpstreams = extractTomlStringArray(key: "dns_upstreams", from: effectiveEndpointToml)
        if dnsUpstreams.isEmpty {
            // Remove any existing empty dns_upstreams lines (single or multiline) to avoid duplicate key errors in TOML
            var cleanedLines: [String] = []
            var skippingDnsUpstreams = false
            for rawLine in effectiveEndpointToml.components(separatedBy: "\n") {
                let trimmed = rawLine.trimmingCharacters(in: .whitespaces)
                if !skippingDnsUpstreams {
                    if trimmed.lowercased().hasPrefix("dns_upstreams") {
                        if !trimmed.contains("]") {
                            skippingDnsUpstreams = true
                        }
                        continue
                    }
                    cleanedLines.append(rawLine)
                } else {
                    if trimmed.contains("]") {
                        skippingDnsUpstreams = false
                    }
                }
            }
            effectiveEndpointToml = cleanedLines.joined(separator: "\n")

            // Now inject our clean dns_upstreams array once under [endpoint]
            if effectiveEndpointToml.contains("[endpoint]") {
                effectiveEndpointToml = effectiveEndpointToml.replacingOccurrences(
                    of: "[endpoint]",
                    with: "[endpoint]\ndns_upstreams = [\"1.1.1.1\", \"8.8.8.8\"]"
                )
            } else if effectiveEndpointToml.contains("[[endpoint]]") {
                effectiveEndpointToml = effectiveEndpointToml.replacingOccurrences(
                    of: "[[endpoint]]",
                    with: "[[endpoint]]\ndns_upstreams = [\"1.1.1.1\", \"8.8.8.8\"]"
                )
            } else {
                effectiveEndpointToml += "\ndns_upstreams = [\"1.1.1.1\", \"8.8.8.8\"]\n"
            }
            dnsUpstreams = ["1.1.1.1", "8.8.8.8"]
            log("Injected fallback dns_upstreams into TrustTunnel config: [1.1.1.1, 8.8.8.8]")
        }

        let endpointAddresses = extractTomlStringArray(key: "addresses", from: effectiveEndpointToml)
        log("TrustTunnel endpoint decoded (addresses=\(endpointAddresses.count), dns_upstreams=\(dnsUpstreams.count), mtu=\(spec.mtu))")

        var remoteAddr = "127.0.0.1"
        var excludedEndpointCidrs: [String] = []
        for addr in endpointAddresses {
            let (host, _) = splitHostPort(addr)
            if isIPv4(host) {
                if remoteAddr == "127.0.0.1" { remoteAddr = host }
                excludedEndpointCidrs.append("\(host)/32")
            } else if isIPv6(host) {
                if remoteAddr == "127.0.0.1" { remoteAddr = host }
                excludedEndpointCidrs.append("\(host)/128")
            } else if !host.isEmpty {
                let resolved = resolveAllIPv4(host)
                if remoteAddr == "127.0.0.1", let first = resolved.first { remoteAddr = first }
                for ip in resolved {
                    excludedEndpointCidrs.append("\(ip)/32")
                }
            }
        }

        var excludedCidrs: [String] = ["0.0.0.0/8", "127.0.0.0/8", "224.0.0.0/3", "255.255.255.255/32"]
        if spec.bypassLan {
            excludedCidrs.append(contentsOf: ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"])
        }
        for cidr in excludedEndpointCidrs where !excludedCidrs.contains(cidr) {
            excludedCidrs.append(cidr)
        }
        let excludedTomlList = excludedCidrs.map { "\"\($0)\"" }.joined(separator: ", ")

        // TrustTunnelConfig::build_config (v1.1.12) requires top-level `vpn_mode = "general"`.
        let fullToml = """
        loglevel = "info"
        vpn_mode = "general"
        killswitch_enabled = false
        exclusions = []

        \(effectiveEndpointToml)

        [listener.tun]
        mtu_size = \(spec.mtu)
        included_routes = ["0.0.0.0/0", "::/0"]
        excluded_routes = [\(excludedTomlList)]
        """

        // Use RFC 2544 TUN IPv4 (198.18.0.2/24) so RFC1918 LAN exclusions never overlap the utun address,
        // and compute exact split includedRoutes via VpnClient.excludeCidr (same as official TrustTunnelClient).
        let computedIncluded = VpnClient.excludeCidr(["0.0.0.0/0", "::/0"], excludeRoutes: excludedCidrs)
        var v4Included: [NEIPv4Route] = []
        var v6Included: [NEIPv6Route] = []
        for route in computedIncluded {
            let parts = route.split(separator: "/")
            guard parts.count == 2, let prefix = Int(parts[1]) else { continue }
            let ip = String(parts[0])
            if ip.contains(":") {
                v6Included.append(NEIPv6Route(destinationAddress: ip, networkPrefixLength: NSNumber(value: prefix)))
            } else {
                v4Included.append(NEIPv4Route(destinationAddress: ip, subnetMask: ipv4Mask(forPrefixLength: prefix)))
            }
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: remoteAddr)
        settings.mtu = NSNumber(value: spec.mtu)

        let ipv4 = NEIPv4Settings(addresses: ["198.18.0.2"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = v4Included.isEmpty ? [.default()] : v4Included
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(addresses: ["fd00::1"], networkPrefixLengths: [64])
        ipv6.includedRoutes = v6Included.isEmpty ? [.default()] : v6Included
        settings.ipv6Settings = ipv6

        // Official TrustTunnelClient proxies DNS queries via virtual DNS proxy 198.18.53.53
        let dns = NEDNSSettings(servers: ["198.18.53.53"])
        dns.matchDomains = [""]
        settings.dnsSettings = dns

        lock.lock()
        trustTunnelStartHandled = false
        trustTunnelStartTimer?.cancel()
        trustTunnelStartTimer = nil
        lock.unlock()

        provider.setTunnelNetworkSettings(settings) { [weak self, weak provider] error in
            guard let self, let provider else { return }
            if let error {
                self.record(error: error)
                self.stop()
                completion(error)
                return
            }

            self.trustTunnelQueue.async { [weak self, weak provider] in
                guard let self, let provider else { return }

                let finishStart: @Sendable (Error?) -> Void = { [weak self] startError in
                    guard let self else { return }
                    self.lock.lock()
                    let alreadyHandled = self.trustTunnelStartHandled
                    self.trustTunnelStartHandled = true
                    self.trustTunnelStartTimer?.cancel()
                    self.trustTunnelStartTimer = nil
                    self.lock.unlock()
                    guard !alreadyHandled else { return }
                    if let startError {
                        self.record(error: startError)
                        self.stop()
                        completion(startError)
                    } else {
                        self.setRunning(true)
                        try? AppleTunnelStore.writeText(
                            VPNConnectionState.connected.rawValue,
                            to: AppleTunnelStore.statusFileName
                        )
                        try? AppleTunnelStore.writeText(
                            String(Date().timeIntervalSince1970),
                            to: AppleTunnelStore.connectedAtFileName
                        )
                        self.log("Tunnel connected: \(spec.displayName)")
                        completion(nil)
                    }
                }

                let client: VpnClient? = VpnClient(
                    config: fullToml,
                    connectionInfoHandler: { _ in },
                    stateChangeHandler: { [weak self, weak provider] rawState in
                        guard let self else { return }
                        self.log("TrustTunnel state -> \(rawState)")
                        switch rawState {
                        case 2: // connected
                            self.trustTunnelQueue.async {
                                provider?.reasserting = false
                                finishStart(nil)
                            }
                        case 3, 4, 5: // waiting_for_recovery, recovering, waiting_for_network
                            self.trustTunnelQueue.async {
                                provider?.reasserting = true
                            }
                        case 0: // disconnected
                            self.trustTunnelQueue.async { [weak self, weak provider] in
                                guard let self else { return }
                                // Ignore expected disconnect callback fired while stop() is already tearing down.
                                guard !self.isStopping else { return }
                                self.lock.lock()
                                let alreadyHandled = self.trustTunnelStartHandled
                                self.lock.unlock()
                                if !alreadyHandled {
                                    finishStart(NativeTunnelRuntimeError.specializedCore("TrustTunnel failed to connect"))
                                } else if self.isRunning {
                                    self.log("TrustTunnel disconnected unexpectedly")
                                    self.stop()
                                    provider?.cancelTunnelWithError(nil)
                                    DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.15) {
                                        exit(0)
                                    }
                                }
                            }
                        default:
                            break
                        }
                    }
                )

                guard let client else {
                    finishStart(NativeTunnelRuntimeError.specializedCore("TrustTunnel failed to initialize client (invalid TOML or network monitor)"))
                    return
                }

                self.lock.lock()
                self.trustTunnelClient = client
                let timer = DispatchSource.makeTimerSource(queue: self.trustTunnelQueue)
                timer.schedule(deadline: .now() + 20)
                timer.setEventHandler {
                    finishStart(NativeTunnelRuntimeError.specializedCore("TrustTunnel connection timed out (20s)"))
                }
                self.trustTunnelStartTimer = timer
                self.lock.unlock()
                timer.resume()

                if !client.start(provider.packetFlow) {
                    finishStart(NativeTunnelRuntimeError.specializedCore("TrustTunnel client start returned false"))
                }
            }
        }
    }

    private func extractTomlStringArray(key: String, from toml: String) -> [String] {
        var values: [String] = []
        let keyLower = key.lowercased()
        let lines = toml.components(separatedBy: "\n")
        var collecting = false
        var buffer = ""

        for rawLine in lines {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if !collecting {
                guard line.lowercased().hasPrefix(keyLower) else { continue }
                let afterKey = line.dropFirst(keyLower.count).trimmingCharacters(in: .whitespaces)
                guard afterKey.hasPrefix("="), let openBracket = afterKey.firstIndex(of: "[") else { continue }
                buffer = String(afterKey[afterKey.index(after: openBracket)...])
                collecting = true
            } else {
                buffer.append(" ")
                buffer.append(line)
            }

            if collecting, let closeBracket = buffer.firstIndex(of: "]") {
                let inside = buffer[..<closeBracket]
                for item in inside.split(separator: ",") {
                    let cleaned = item.trimmingCharacters(
                        in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\"'"))
                    )
                    if !cleaned.isEmpty {
                        values.append(cleaned)
                    }
                }
                collecting = false
                buffer = ""
            }
        }
        return values
    }

    private func extractWdttListenPort(from auxiliary: [AppleAuxiliaryRuntimePlan]) -> Int? {
        guard let wdtt = auxiliary.first(where: { $0.kind == .wdtt }),
              let outerData = wdtt.optionsJson.data(using: .utf8),
              let outer = try? JSONSerialization.jsonObject(with: outerData) as? [String: Any],
              let innerJson = outer["options_json"] as? String,
              let innerData = innerJson.data(using: .utf8),
              let inner = try? JSONSerialization.jsonObject(with: innerData) as? [String: Any],
              let listen = inner["listen"] as? String,
              let portStr = listen.split(separator: ":").last,
              let port = Int(portStr), (1...65_535).contains(port)
        else {
            return nil
        }
        return port
    }

    private func rewriteBridgeSocksPort(_ yaml: String, port: Int) -> String {
        var inSocks5 = false
        return yaml.split(separator: "\n", omittingEmptySubsequences: false).map { rawLine in
            let line = String(rawLine)
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if !line.hasPrefix(" ") && !trimmed.isEmpty {
                inSocks5 = trimmed == "socks5:"
            } else if inSocks5 && trimmed.hasPrefix("port:") {
                return "  port: \(port)"
            }
            return line
        }.joined(separator: "\n")
    }

    private func ipv4Mask(forPrefixLength length: Int) -> String {
        let clamped = max(0, min(32, length))
        let mask: UInt32 = clamped == 0 ? 0 : ~UInt32(0) << (32 - clamped)
        return "\(mask >> 24 & 0xff).\(mask >> 16 & 0xff).\(mask >> 8 & 0xff).\(mask & 0xff)"
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
