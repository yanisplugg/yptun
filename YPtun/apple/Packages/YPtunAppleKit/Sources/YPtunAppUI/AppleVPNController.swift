import Foundation
import NetworkExtension
import YPtunShared

@MainActor
public protocol AppleVPNControlling: AnyObject {
    var onStateChange: ((VPNConnectionState) -> Void)? { get set }
    func currentState() async -> VPNConnectionState
    func start() async throws
    func stop() async
}

@MainActor
public final class AppleVPNController: AppleVPNControlling {
    public var onStateChange: ((VPNConnectionState) -> Void)?

    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    public init() {
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let connection = notification.object as? NEVPNConnection else { return }
            Task { @MainActor [weak self] in
                self?.onStateChange?(Self.state(for: connection.status))
            }
        }
    }

    deinit {
        if let statusObserver {
            NotificationCenter.default.removeObserver(statusObserver)
        }
    }

    public func currentState() async -> VPNConnectionState {
        do {
            let manager = try await loadManager(createIfMissing: false)
            return Self.state(for: manager?.connection.status ?? .disconnected)
        } catch {
            return .disconnected
        }
    }

    public func start() async throws {
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
        onStateChange?(.connecting)
    }

    public func stop() async {
        guard let manager = try? await loadManager(createIfMissing: false) else {
            onStateChange?(.disconnected)
            return
        }
        manager.connection.stopVPNTunnel()
        onStateChange?(.disconnecting)
    }

    private var providerBundleIdentifier: String {
        #if os(macOS)
        YPtunIdentity.MacOS.tunnelBundleIdentifier
        #else
        YPtunIdentity.IOS.tunnelBundleIdentifier
        #endif
    }

    private func loadManager(createIfMissing: Bool) async throws -> NETunnelProviderManager? {
        let managers = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<[NETunnelProviderManager], Error>) in
            NETunnelProviderManager.loadAllFromPreferences { managers, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: managers ?? [])
                }
            }
        }
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

    private static func state(for status: NEVPNStatus) -> VPNConnectionState {
        switch status {
        case .connected: .connected
        case .connecting, .reasserting: .connecting
        case .disconnecting: .disconnecting
        case .invalid, .disconnected: .disconnected
        @unknown default: .disconnected
        }
    }
}

public enum AppleVPNControllerError: LocalizedError, Sendable {
    case profileUnavailable

    public var errorDescription: String? {
        "The VPN profile is unavailable."
    }
}
