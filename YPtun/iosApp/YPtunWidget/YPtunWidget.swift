import AppIntents
import NetworkExtension
import SwiftUI
import WidgetKit

// Home-screen widget + Control Center toggle for the YPtun VPN — the iOS counterparts of Android's
// status/toggle widgets and Quick Settings tile. The VPN itself is the system profile the app saved
// (NETunnelProviderManager); starting it runs the packet-tunnel extension, which reads the last
// connect request the app left in the App Group container.

private let appGroup = "group.org.yptun.app"

private enum Vpn {
    static func manager() async -> NETunnelProviderManager? {
        try? await NETunnelProviderManager.loadAllFromPreferences().first
    }

    static func isOn(_ status: NEVPNStatus) -> Bool {
        status == .connected || status == .connecting || status == .reasserting
    }

    static func connected() async -> Bool {
        guard let manager = await manager() else { return false }
        return isOn(manager.connection.status)
    }

    static func set(_ on: Bool) async throws {
        guard let manager = await manager() else { return }
        if on {
            if !manager.isEnabled {
                manager.isEnabled = true
                try await manager.saveToPreferences()
                try await manager.loadFromPreferences()
            }
            try manager.connection.startVPNTunnel()
        } else {
            manager.connection.stopVPNTunnel()
        }
    }

    /// Location name the app published (widget.json in the App Group container).
    static func locationName() -> String {
        guard
            let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
                .appendingPathComponent("yptun/widget.json"),
            let data = try? Data(contentsOf: url),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let name = object["name"] as? String
        else { return "" }
        return name
    }
}

private enum L {
    static let ru = Locale.preferredLanguages.first?.hasPrefix("ru") ?? false
    static var connected: String { ru ? "Подключено" : "Connected" }
    static var disconnected: String { ru ? "Отключено" : "Disconnected" }
    static var connect: String { ru ? "Подключить" : "Connect" }
    static var disconnect: String { ru ? "Отключить" : "Disconnect" }
    static var noLocation: String { ru ? "Выберите локацию в приложении" : "Pick a location in the app" }
}

// MARK: - Intents

@available(iOS 16.0, *)
struct ToggleVpnIntent: AppIntent {
    static var title: LocalizedStringResource = "YPtun VPN"
    static var description = IntentDescription("Turns the YPtun VPN on or off.")

    func perform() async throws -> some IntentResult {
        try await Vpn.set(!(await Vpn.connected()))
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 18.0, *)
struct SetVpnIntent: SetValueIntent {
    static var title: LocalizedStringResource = "YPtun VPN"

    @Parameter(title: "On")
    var value: Bool

    func perform() async throws -> some IntentResult {
        try await Vpn.set(value)
        return .result()
    }
}

// MARK: - Home-screen widget

struct VpnEntry: TimelineEntry {
    let date: Date
    let connected: Bool
    let name: String
}

struct VpnProvider: TimelineProvider {
    func placeholder(in context: Context) -> VpnEntry {
        VpnEntry(date: Date(), connected: false, name: "YPtun")
    }

    func getSnapshot(in context: Context, completion: @escaping (VpnEntry) -> Void) {
        Task { completion(await entry()) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VpnEntry>) -> Void) {
        // The app and the tunnel reload the timeline on every status change; the 15-minute refresh is
        // only a fallback.
        Task {
            completion(Timeline(entries: [await entry()], policy: .after(Date().addingTimeInterval(15 * 60))))
        }
    }

    private func entry() async -> VpnEntry {
        VpnEntry(date: Date(), connected: await Vpn.connected(), name: Vpn.locationName())
    }
}

struct VpnWidgetView: View {
    let entry: VpnEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: entry.connected ? "lock.shield.fill" : "shield.slash")
                    .foregroundColor(entry.connected ? .green : .secondary)
                Text(entry.connected ? L.connected : L.disconnected)
                    .font(.caption.weight(.semibold))
            }
            Text(entry.name.isEmpty ? L.noLocation : entry.name)
                .font(.headline)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Spacer(minLength: 0)
            if #available(iOS 17.0, *) {
                Button(intent: ToggleVpnIntent()) {
                    Text(entry.connected ? L.disconnect : L.connect)
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .tint(entry.connected ? .red : .accentColor)
            }
        }
        .padding(2)
        .widgetBackground()
    }
}

private extension View {
    @ViewBuilder
    func widgetBackground() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(.fill.tertiary, for: .widget)
        } else {
            padding()
        }
    }
}

struct VpnWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "org.yptun.app.vpn-widget", provider: VpnProvider()) { entry in
            VpnWidgetView(entry: entry)
        }
        .configurationDisplayName("YPtun")
        .description(L.ru ? "Состояние VPN и кнопка подключения" : "VPN status and connect button")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// MARK: - Control Center (iOS 18)

@available(iOS 18.0, *)
struct VpnControlProvider: ControlValueProvider {
    var previewValue: Bool { false }

    func currentValue() async throws -> Bool { await Vpn.connected() }
}

@available(iOS 18.0, *)
struct VpnControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "org.yptun.app.vpn-control", provider: VpnControlProvider()) { isOn in
            ControlWidgetToggle("YPtun", isOn: isOn, action: SetVpnIntent()) { on in
                Label(on ? L.connected : L.disconnected, systemImage: on ? "lock.shield.fill" : "shield.slash")
            }
        }
        .displayName("YPtun VPN")
    }
}

@main
struct YPtunWidgets: WidgetBundle {
    var body: some Widget {
        VpnWidget()
        if #available(iOS 18.0, *) {
            VpnControl()
        }
    }
}
