import NetworkExtension
import SharedUI
import SwiftUI
import WidgetKit

@main
struct OlcboxIosApp: App {
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
        self.platformBridge = platformBridge
        self.coreBridge = coreBridge
        self.appSession = IosAppFactory().createSession(
            platformBridge: platformBridge,
            coreBridge: coreBridge
        )
    }

    var body: some Scene {
        WindowGroup {
            ComposeHostView(
                platformBridge: platformBridge,
                appSession: appSession
            )
            .ignoresSafeArea()
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
