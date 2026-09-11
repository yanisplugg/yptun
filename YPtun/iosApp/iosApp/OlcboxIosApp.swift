import NetworkExtension
import SharedUI
import SwiftUI
import UIKit
import WidgetKit

final class AppDelegate: NSObject, UIApplicationDelegate {
    static var onShortcut: ((UIApplicationShortcutItem) -> Void)?

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let shortcutItem = options.shortcutItem {
            Self.onShortcut?(shortcutItem)
        }
        return UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
    }

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        Self.onShortcut?(shortcutItem)
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
                appSession.stopVpn()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.appSession.startVpn()
                }
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
