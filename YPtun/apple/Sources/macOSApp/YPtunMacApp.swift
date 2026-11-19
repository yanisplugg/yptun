import SwiftUI
import YPtunAppUI

@main
struct YPtunMacApp: App {
    @StateObject private var model = YPtunApplicationModel()

    var body: some Scene {
        WindowGroup("YPtun", id: "main") {
            YPtunMacRootView(model: model)
                .frame(minWidth: 800, minHeight: 560)
        }
        .defaultSize(width: 920, height: 680)

        MenuBarExtra("YPtun", systemImage: model.connectionState == .connected ? "shield.fill" : "shield") {
            YPtunMenuBarView(model: model)
        }
    }
}
