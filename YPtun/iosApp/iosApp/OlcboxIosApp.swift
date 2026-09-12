import NetworkExtension
import SharedUI
import SwiftUI
import UIKit
import WidgetKit

/// Home-screen quick actions («Подключить / Отключить VPN»). A scene-based app gets them in two places:
/// a cold launch brings the item in the scene's connection options, a running app through the WINDOW
/// SCENE delegate — `application(_:performActionFor:)` is never called once scenes are on.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var onShortcut: ((UIApplicationShortcutItem) -> Void)? {
        didSet {
            // A cold-launch item may arrive before the app has wired the handler.
            if let item = pendingShortcut, let handler = onShortcut {
                pendingShortcut = nil
                handler(item)
            }
        }
    }
    private static var pendingShortcut: UIApplicationShortcutItem?

    static func deliver(_ item: UIApplicationShortcutItem) {
        if let handler = onShortcut {
            handler(item)
        } else {
            pendingShortcut = item
        }
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let shortcutItem = options.shortcutItem {
            Self.deliver(shortcutItem)
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }
}

/// Receives quick actions while the app is running; SwiftUI keeps managing the window itself.
final class QuickActionSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        AppDelegate.deliver(shortcutItem)
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
        // The widget and the Control Center toggle show the VPN state: refresh them on every change.
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { _ in
            WidgetCenter.shared.reloadAllTimelines()
            if #available(iOS 18.0, *) {
                ControlCenter.shared.reloadAllControls()
            }
        }
        let platformBridge = SwiftPlatformBridge()
        let coreBridge = SwiftCoreBridge()
        let session = IosAppFactory().createSession(
            platformBridge: platformBridge,
            coreBridge: coreBridge
        )
        self.platformBridge = platformBridge
        self.coreBridge = coreBridge
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
                handleUrl(url)
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

    private func handleUrl(_ url: URL) {
        guard url.scheme?.lowercased() == "yptun" else { return }
        if url.host == "control" {
            let path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if path == "start" {
                appSession.startVpn()
            } else if path == "stop" {
                appSession.stopVpn()
            } else if path == "restart" {
                appSession.restartVpn()
            }
        }
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
