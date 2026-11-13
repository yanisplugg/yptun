import SwiftUI

#if os(iOS)
public struct YPtunIOSRootView: View {
    @ObservedObject private var model: YPtunApplicationModel
    @State private var selection = YPtunAppSection.connection

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        TabView(selection: $selection) {
            NavigationStack {
                ConnectionHomeView(model: model)
            }
            .tabItem { Label("Подключение", systemImage: "bolt.fill") }
            .tag(YPtunAppSection.connection)

            NavigationStack {
                SettingsOverviewView(model: model)
            }
            .tabItem { Label("Настройки", systemImage: "gearshape") }
            .tag(YPtunAppSection.settings)
        }
        .tint(Color(red: 59.0 / 255, green: 142.0 / 255, blue: 247.0 / 255))
        .toolbarBackground(.ultraThinMaterial, for: .tabBar)
        .task { await model.bootstrap() }
        .onOpenURL { url in
            Task { _ = await model.importLocations(from: url.absoluteString) }
        }
    }
}
#endif

#if os(macOS)
public struct YPtunMacRootView: View {
    @ObservedObject private var model: YPtunApplicationModel
    @State private var selection: YPtunAppSection? = .connection

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        NavigationSplitView {
            List(YPtunAppSection.allCases, selection: $selection) { section in
                Label(section.title, systemImage: section.symbol)
                    .tag(section)
            }
            .navigationTitle("YPtun")
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } detail: {
            NavigationStack {
                switch selection ?? .connection {
                case .connection:
                    ConnectionHomeView(model: model)
                case .settings:
                    SettingsOverviewView(model: model)
                }
            }
        }
        .tint(Color(red: 59.0 / 255, green: 142.0 / 255, blue: 247.0 / 255))
        .task { await model.bootstrap() }
        .onOpenURL { url in
            Task { _ = await model.importLocations(from: url.absoluteString) }
        }
    }
}

public struct YPtunMenuBarView: View {
    @ObservedObject private var model: YPtunApplicationModel
    @Environment(\.openWindow) private var openWindow

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        Text(model.connectionState == .connected ? "VPN подключён" : "VPN не подключён")
        if let location = model.selectedLocation {
            Text(location.name)
                .foregroundStyle(.secondary)
        }
        Divider()
        Button("Открыть YPtun") {
            openWindow(id: "main")
        }
        Button(model.primaryActionTitle, action: model.requestPrimaryAction)
            .disabled(model.isBootstrapping || model.connectionState == .disconnecting)
        if let notice = model.presentedNotice {
            Divider()
            Text(notice.title)
                .font(.caption.weight(.semibold))
            Text(notice.message)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
#endif
