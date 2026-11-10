import SwiftUI
import YPtunRustBridge
import YPtunShared

@main
struct YPtunIOSApp: App {
    var body: some Scene {
        WindowGroup {
            IOSRootView()
        }
    }
}

private struct IOSRootView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text(YPtunIdentity.displayName)
                .font(.headline)
            Text("Native iOS shell")
            Text("Rust bridge: iOS \(YPtunRustBridgeInfo.minimumIOS)+")
                .font(.caption)
        }
        .padding()
    }
}
