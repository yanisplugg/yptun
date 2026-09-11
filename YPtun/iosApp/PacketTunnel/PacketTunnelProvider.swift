import Foundation
import NetworkExtension
import SharedUI
import Tun2SocksKit
import WidgetKit

/// The system VPN. Everything that decides HOW to connect lives in Kotlin (`IosTunnelSession`); this
/// class only hands it the cores and runs hev-socks5-tunnel, which moves the utun's packets into the
/// local SOCKS5 the cores leave behind.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var session: IosTunnelSession?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let session = IosTunnelSession(core: SwiftCoreBridge(), provider: self)
        self.session = session
        session.start { hevConfig, error in
            guard let hevConfig, error == nil else {
                completionHandler(NSError(
                    domain: "org.yptun.tunnel",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: error ?? "Tunnel start failed"]
                ))
                return
            }
            // Blocks its own thread until quit(); the tunnel settings are applied already.
            Socks5Tunnel.run(withConfig: .string(content: hevConfig)) { code in
                NSLog("YPtun: hev-socks5-tunnel exited with \(code)")
            }
            completionHandler(nil)
            PacketTunnelProvider.refreshWidgets()
        }
    }

    /// A start/stop from Settings or the widget happens without the app — refresh the widget here too.
    private static func refreshWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
        if #available(iOS 18.0, *) {
            ControlCenter.shared.reloadAllControls()
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        Socks5Tunnel.quit()
        session?.stop()
        session = nil
        completionHandler()
        PacketTunnelProvider.refreshWidgets()
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        let message = String(data: messageData, encoding: .utf8) ?? ""
        let reply = session?.handleAppMessage(message: message) ?? ""
        completionHandler?(reply.data(using: .utf8))
    }
}
