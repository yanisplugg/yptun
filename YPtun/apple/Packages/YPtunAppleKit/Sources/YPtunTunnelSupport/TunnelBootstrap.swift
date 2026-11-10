import Foundation
import YPtunRustBridge
import YPtunShared

public struct TunnelBootstrap: Equatable, Sendable {
    public let remoteAddress: String

    public init(remoteAddress: String = "127.0.0.1") {
        self.remoteAddress = remoteAddress
    }

    public var bridgeMinimumIOS: Int {
        YPtunRustBridgeInfo.minimumIOS
    }

    public var bridgeMinimumMacOS: Int {
        YPtunRustBridgeInfo.minimumMacOS
    }
}

public enum TunnelPlaceholderError: LocalizedError, Sendable {
    case implementationPending

    public var errorDescription: String? {
        "The native packet tunnel runtime has not been connected yet."
    }
}
