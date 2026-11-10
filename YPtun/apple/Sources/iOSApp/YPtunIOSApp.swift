import SwiftUI
import YPtunAppUI

@main
struct YPtunIOSApp: App {
    @State private var model = YPtunApplicationModel()

    var body: some Scene {
        WindowGroup {
            YPtunIOSRootView(model: model)
        }
    }
}
