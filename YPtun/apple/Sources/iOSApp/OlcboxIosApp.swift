import NetworkExtension
import SharedUI
import SwiftUI
import UIKit
import WidgetKit

/// Home-screen quick actions («Подключить / Отключить VPN»).
final class AppDelegate: NSObject, UIApplicationDelegate {
    @MainActor static var onShortcut: ((UIApplicationShortcutItem) -> Void)? {
        didSet {
            if let item = pendingShortcut, let handler = onShortcut {
                pendingShortcut = nil
                handler(item)
            }
        }
    }
    @MainActor private static var pendingShortcut: UIApplicationShortcutItem?

    @MainActor static func deliver(_ item: UIApplicationShortcutItem) {
        if let handler = onShortcut {
            handler(item)
        } else {
            pendingShortcut = item
        }
    }

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        Self.deliver(shortcutItem)
        completionHandler(true)
    }
}

@main
struct OlcboxIosApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let platformBridge: SwiftPlatformBridge
    private let coreBridge: SwiftCoreBridge
    private let appSession: IosAppSession

    init() {
        LocalNotifications.shared.install()
        if #available(iOS 16.2, *) {
            NotificationCenter.default.addObserver(forName: NSNotification.Name("org.yptun.vpn.connected"), object: nil, queue: .main) { _ in
                LiveActivityManager.onConnected()
            }
            NotificationCenter.default.addObserver(forName: NSNotification.Name("org.yptun.vpn.disconnected"), object: nil, queue: .main) { _ in
                LiveActivityManager.onDisconnected()
            }
        }
        let pb = SwiftPlatformBridge()
        let cb = SwiftCoreBridge()
        let session = IosAppFactory().createSession(
            platformBridge: pb,
            coreBridge: cb
        )
        self.platformBridge = pb
        self.coreBridge = cb
        self.appSession = session

        AppDelegate.onShortcut = { item in
            DispatchQueue.main.async {
                Self.handleShortcut(item, session: session)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeHostView(
                platformBridge: platformBridge,
                appSession: appSession
            )
            .ignoresSafeArea()
            .onOpenURL { url in
                handleUrl(url, session: appSession)
            }
        }
    }

    private static func handleShortcut(_ item: UIApplicationShortcutItem, session: IosAppSession) {
        switch item.type {
        case "org.yptun.control.start":
            session.startVpn()
        case "org.yptun.control.stop":
            session.stopVpn()
        default:
            break
        }
    }

    private static let importSchemes: Set<String> = [
        "qwdtt", "openflux", "masterdns", "snolc", "csqtt", "freeturn", "olcrtc", "amneziawg",
        "mieru", "mierus", "turnable", "tt",
    ]

    private func handleUrl(_ url: URL, session: IosAppSession) {
        if let scheme = url.scheme?.lowercased(), Self.importSchemes.contains(scheme) {
            session.importLink(text: url.absoluteString)
            return
        }
        guard url.scheme?.lowercased() == "yptun" else { return }
        let host = url.host?.lowercased() ?? ""

        if host == "control" {
            switch url.lastPathComponent.lowercased() {
            case "start": session.startVpn()
            case "stop": session.stopVpn()
            case "restart": session.restartVpn()
            case "auto": session.requestAutoSelect()
            default: break
            }
            return
        }

        let text = url.absoluteString
        if host == "import" {
            let prefix = "yptun://import/"
            guard text.count > prefix.count else { return }
            let raw = String(text.dropFirst(prefix.count))
            session.importLink(text: raw.removingPercentEncoding ?? raw)
            return
        }

        session.importLink(text: text)
    }
}

private struct ComposeHostView: UIViewControllerRepresentable {
    let platformBridge: SwiftPlatformBridge
    let appSession: IosAppSession

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = appSession.createViewController()
        platformBridge.presenter = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        platformBridge.presenter = uiViewController
    }
}
