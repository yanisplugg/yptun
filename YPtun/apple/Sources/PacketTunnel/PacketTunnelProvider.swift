import Foundation
import NetworkExtension
import YPtunRustBridge
import YPtunShared
import YPtunTunnelSupport

final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        _ = TunnelBootstrap()
#if os(macOS)
        _ = YPtunRustBridgeInfo.minimumMacOS
#else
        _ = YPtunRustBridgeInfo.minimumIOS
#endif
        completionHandler(TunnelPlaceholderError.implementationPending)
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        completionHandler()
    }
}
