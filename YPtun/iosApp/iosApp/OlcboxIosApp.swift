import SwiftUI
import SharedUI

@main
struct OlcboxIosApp: App {
    private let platformBridge: SwiftPlatformBridge
    private let coreBridge: SwiftCoreBridge
    private let appSession: IosAppSession

    init() {
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
