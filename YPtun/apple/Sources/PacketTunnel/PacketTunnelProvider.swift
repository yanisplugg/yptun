import Foundation
@preconcurrency import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let runtime = NativeTunnelRuntime()

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        runtime.start(provider: self, completion: completionHandler)
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        runtime.stop()
        completionHandler()
    }

    override func handleAppMessage(
        _ messageData: Data,
        completionHandler: ((Data?) -> Void)?
    ) {
        let message = String(data: messageData, encoding: .utf8) ?? ""
        let response = runtime.handle(message: message)
        completionHandler?(Data(response.utf8))
    }
}
