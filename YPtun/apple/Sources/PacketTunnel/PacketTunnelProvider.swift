import Darwin
import Foundation
@preconcurrency import NetworkExtension

private final class SendableCallbackBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let runtime = NativeTunnelRuntime()

    override func startTunnel(
        options: [String: NSObject]? = nil,
        completionHandler: @escaping (Error?) -> Void
    ) {
        let done = SendableCallbackBox(completionHandler)
        runtime.start(provider: self) { error in
            done.value(error)
            if error != nil {
                // Exit cleanly after reporting start failure so iOS spawns a fresh
                // extension process on the next connect with zero lingering sockets
                // or dirty C/Rust/Go global state.
                DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.15) {
                    exit(0)
                }
            }
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        runtime.stop()
        completionHandler()
        // Always terminate the extension process after stopping so Tun2SocksKit
        // (hev-socks5-tunnel), Go Coreapi, and Rust snolc/csqtt start clean next time.
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.15) {
            exit(0)
        }
    }

    override func sleep(completionHandler: @escaping () -> Void) {
        runtime.handleSleep()
        completionHandler()
    }

    override func wake() {
        runtime.handleWake()
    }

    override func handleAppMessage(
        _ messageData: Data,
        completionHandler: ((Data?) -> Void)?
    ) {
        let message = String(data: messageData, encoding: .utf8) ?? ""
        let reply = SendableCallbackBox(completionHandler)
        runtime.handle(message: message) { response in
            reply.value?(Data(response.utf8))
        }
    }
}
