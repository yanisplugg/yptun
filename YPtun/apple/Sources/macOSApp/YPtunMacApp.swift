import SwiftUI
import YPtunAppUI

@main
struct YPtunMacApp: App {
    @State private var model = YPtunApplicationModel()

    var body: some Scene {
        WindowGroup("YPtun", id: "main") {
            YPtunMacRootView(model: model)
        }
        .defaultSize(width: 920, height: 680)

        MenuBarExtra("YPtun", systemImage: model.connectionState == .connected ? "shield.fill" : "shield") {
            YPtunMenuBarView(model: model)
        }
    }
}
