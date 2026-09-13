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
        session.start { [weak self, weak session] hevConfig, error in
            guard let self = self, let session = session else {
                completionHandler(NSError(domain: "org.yptun.tunnel", code: 1, userInfo: [NSLocalizedDescriptionKey: "Tunnel deallocated"]))
                return
            }
            guard let hevConfig, error == nil else {
                completionHandler(NSError(
                    domain: "org.yptun.tunnel",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: error ?? "Tunnel start failed"]
                ))
                return
            }
            // Write config to file: Tun2SocksKit .string mode has known YAML parsing/length issues, .file mode is reliable.
            let appGroup = "group.org.yptun.app"
            let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
                ?? FileManager.default.temporaryDirectory
            let configURL = containerURL.appendingPathComponent("hev_config.yml")
            do {
                try hevConfig.write(to: configURL, atomically: true, encoding: .utf8)
                session.log("Saved hev-socks5-tunnel config to \(configURL.path)")
            } catch {
                session.log("Failed to write hev config file: \(error.localizedDescription)")
            }

            if let sockFd = self.packetFlow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
                session.log("utun packetFlow socket fd: \(sockFd)")
            }

            session.log("Launching hev-socks5-tunnel...")
            Socks5Tunnel.run(withConfig: .file(path: configURL)) { code in
                session.log("hev-socks5-tunnel exited with code \(code)")
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
        session?.log("Tunnel stopping with reason: \(reason.rawValue)")
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
