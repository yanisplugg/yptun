import Foundation
@preconcurrency import NetworkExtension
import YPtunShared

private struct UncheckedTransfer<Value>: @unchecked Sendable {
    let value: Value
}

private final class NotificationToken: @unchecked Sendable {
    var value: NSObjectProtocol?

    deinit {
        if let value {
            NotificationCenter.default.removeObserver(value)
        }
    }
}

@MainActor
public protocol AppleVPNControlling: AnyObject {
    var onStateChange: ((VPNConnectionState) -> Void)? { get set }
    func currentState() async -> VPNConnectionState
    func start() async throws
    func stop() async
    func restart() async throws
}

@MainActor
public final class AppleVPNController: AppleVPNControlling {
    public var onStateChange: ((VPNConnectionState) -> Void)?

    private var manager: NETunnelProviderManager?
    private let statusObserver = NotificationToken()

    public init() {
        statusObserver.value = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let connection = notification.object as? NEVPNConnection else { return }
            let status = connection.status
            Task { @MainActor [weak self] in
                guard let self, self.manager?.connection === connection else { return }
                self.publish(Self.state(for: status))
            }
        }
    }

    public func currentState() async -> VPNConnectionState {
        do {
            let manager = try await loadManager(createIfMissing: false)
            let state = Self.state(for: manager?.connection.status ?? .disconnected)
            publish(state)
            return state
        } catch {
            publish(.disconnected)
            return .disconnected
        }
    }

    public func start() async throws {
        do {
            guard let manager = try await loadManager(createIfMissing: true) else {
                throw AppleVPNControllerError.profileUnavailable
            }
            let tunnelProtocol = (manager.protocolConfiguration as? NETunnelProviderProtocol)
                ?? NETunnelProviderProtocol()
            tunnelProtocol.providerBundleIdentifier = providerBundleIdentifier
            tunnelProtocol.serverAddress = "YPtun"
            manager.protocolConfiguration = tunnelProtocol
            manager.localizedDescription = YPtunIdentity.displayName
            manager.isEnabled = true
            try await save(manager)
            try await reload(manager)
            self.manager = manager
            try manager.connection.startVPNTunnel()
            publish(.connecting)
        } catch {
            publish(.disconnected)
            throw error
        }
    }

    public func stop() async {
        guard let manager = try? await loadManager(createIfMissing: false) else {
            publish(.disconnected)
            return
        }
        manager.connection.stopVPNTunnel()
        publish(.disconnecting)
    }

    public func restart() async throws {
        if let manager = try await loadManager(createIfMissing: false) {
            manager.connection.stopVPNTunnel()
            publish(.disconnecting)
            _ = try await waitForDisconnection(manager.connection, timeout: .seconds(3))
        }
        try Task.checkCancellation()
        try await start()
    }

    private var providerBundleIdentifier: String {
        #if os(macOS)
        YPtunIdentity.MacOS.tunnelBundleIdentifier
        #else
        YPtunIdentity.IOS.tunnelBundleIdentifier
        #endif
    }

    private func loadManager(createIfMissing: Bool) async throws -> NETunnelProviderManager? {
        let transfer = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<UncheckedTransfer<[NETunnelProviderManager]>, Error>) in
            NETunnelProviderManager.loadAllFromPreferences { managers, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: UncheckedTransfer(value: managers ?? []))
                }
            }
        }
        let managers = transfer.value
        if let current = managers.first(where: {
            ($0.protocolConfiguration as? NETunnelProviderProtocol)?
                .providerBundleIdentifier == providerBundleIdentifier
        }) {
            manager = current
            return current
        }
        guard createIfMissing else { return nil }
        let created = NETunnelProviderManager()
        manager = created
        return created
    }

    private func save(_ manager: NETunnelProviderManager) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            manager.saveToPreferences { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    private func reload(_ manager: NETunnelProviderManager) async throws {
        try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<Void, Error>) in
            manager.loadFromPreferences { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            }
        }
    }

    private func waitForDisconnection(
        _ connection: NEVPNConnection,
        timeout: Duration
    ) async throws -> Bool {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while Self.state(for: connection.status) != .disconnected {
            try Task.checkCancellation()
            guard clock.now < deadline else { return false }
            try await Task.sleep(for: .milliseconds(100))
        }
        return true
    }

    private static func state(for status: NEVPNStatus) -> VPNConnectionState {
        switch status {
        case .connected: .connected
        case .connecting, .reasserting: .connecting
        case .disconnecting: .disconnecting
        case .invalid, .disconnected: .disconnected
        @unknown default: .disconnected
        }
    }

    private func publish(_ state: VPNConnectionState) {
        try? AppleTunnelStore.writeText(state.rawValue, to: AppleTunnelStore.statusFileName)
        onStateChange?(state)
    }
}

public enum AppleVPNControllerError: LocalizedError, Sendable {
    case profileUnavailable

    public var errorDescription: String? {
        "The VPN profile is unavailable."
    }
}
