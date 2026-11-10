import SwiftUI
import YPtunRustBridge
import YPtunShared

@main
struct YPtunMacApp: App {
    var body: some Scene {
        WindowGroup {
            MacRootView()
        }
    }
}

private struct MacRootView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text(YPtunIdentity.displayName)
                .font(.headline)
            Text("Native macOS shell")
            Text("Rust bridge: macOS \(YPtunRustBridgeInfo.minimumMacOS)+")
                .font(.caption)
        }
        .padding()
        .frame(minWidth: 420, minHeight: 260)
    }
}
