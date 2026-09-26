import AppIntents
import NetworkExtension
import SwiftUI
import WidgetKit

// Home-screen widget + Control Center toggle for the YPtun VPN — the iOS counterparts of Android's
// status/toggle widgets and Quick Settings tile. The VPN itself is the system profile the app saved
// (NETunnelProviderManager); starting it runs the packet-tunnel extension, which reads the last
// connect request the app left in the App Group container.

private let appGroup = "group.org.yptun.app"

// MARK: - Models

struct WidgetLocationItem {
    let id: String
    let name: String
    let requestJson: String
}

struct VpnWidgetData {
    let name: String
    let flag: String
    let ping: Int
    let bypassRussia: Bool
    let items: [WidgetLocationItem]
}

// MARK: - VPN Controller

private enum Vpn {
    static func manager() async -> NETunnelProviderManager? {
        try? await NETunnelProviderManager.loadAllFromPreferences().first
    }

    static func status() async -> (connected: Bool, connectedDate: Date?) {
        guard let manager = await manager() else { return (false, nil) }
        let s = manager.connection.status
        let isConn = (s == .connected || s == .connecting || s == .reasserting)
        return (isConn, isConn ? manager.connection.connectedDate : nil)
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

    static func restartIfConnected() async throws {
        guard let manager = await manager() else { return }
        let s = manager.connection.status
        if s == .connected || s == .connecting || s == .reasserting {
            manager.connection.stopVPNTunnel()
            try? await Task.sleep(nanoseconds: 600_000_000)
            try manager.connection.startVPNTunnel()
        }
    }

    static func loadWidgetData() -> VpnWidgetData {
        guard
            let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
                .appendingPathComponent("yptun/widget.json"),
            let data = try? Data(contentsOf: url),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return VpnWidgetData(name: "YPtun", flag: "🌐", ping: -1, bypassRussia: false, items: [])
        }

        let rawName = (object["name"] as? String) ?? "YPtun"
        let (flag, cleanName) = extractFlagAndName(from: rawName)
        let ping = (object["ping"] as? NSNumber)?.intValue ?? -1
        let bypassRu = (object["bypassRussia"] as? Bool) ?? false

        var items: [WidgetLocationItem] = []
        if let rawLocations = object["locations"] as? [[String: Any]] {
            for loc in rawLocations {
                if let id = loc["id"] as? String,
                   let name = loc["name"] as? String,
                   let req = loc["requestJson"] as? String {
                    items.append(WidgetLocationItem(id: id, name: name, requestJson: req))
                }
            }
        }

        return VpnWidgetData(name: cleanName, flag: flag, ping: ping, bypassRussia: bypassRu, items: items)
    }

    static func toggleBypassRussia() async throws {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return }
        let routingUrl = base.appendingPathComponent("yptun/routing.json")
        let widgetUrl = base.appendingPathComponent("yptun/widget.json")

        var routingObj: [String: Any] = [:]
        if let data = try? Data(contentsOf: routingUrl),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            routingObj = obj
        }
        let currentBypass = (routingObj["bypassRussia"] as? Bool) ?? false
        let newBypass = !currentBypass
        routingObj["bypassRussia"] = newBypass
        if let outData = try? JSONSerialization.data(withJSONObject: routingObj, options: [.prettyPrinted]) {
            try? outData.write(to: routingUrl)
        }

        if let data = try? Data(contentsOf: widgetUrl),
           var widgetObj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            widgetObj["bypassRussia"] = newBypass
            if let outData = try? JSONSerialization.data(withJSONObject: widgetObj) {
                try? outData.write(to: widgetUrl)
            }
        }

        try await restartIfConnected()
    }

    static func switchToNextLocation() async throws {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return }
        let widgetUrl = base.appendingPathComponent("yptun/widget.json")
        let requestUrl = base.appendingPathComponent("yptun/request.json")

        guard
            let data = try? Data(contentsOf: widgetUrl),
            var widgetObj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let rawLocations = widgetObj["locations"] as? [[String: Any]],
            !rawLocations.isEmpty
        else { return }

        let currentName = widgetObj["name"] as? String ?? ""
        let currentIndex = rawLocations.firstIndex(where: { ($0["name"] as? String) == currentName }) ?? 0
        let nextIndex = (currentIndex + 1) % rawLocations.count
        let nextLoc = rawLocations[nextIndex]

        if let nextReq = nextLoc["requestJson"] as? String,
           let reqData = nextReq.data(using: .utf8) {
            try? reqData.write(to: requestUrl)
        }

        widgetObj["name"] = nextLoc["name"]
        widgetObj["id"] = nextLoc["id"]
        if let p = nextLoc["ping"] as? NSNumber {
            widgetObj["ping"] = p.intValue
        }
        if let outData = try? JSONSerialization.data(withJSONObject: widgetObj) {
            try? outData.write(to: widgetUrl)
        }

        try await restartIfConnected()
    }
}

// MARK: - Flag & Name Helpers


// MARK: - Localization

private enum L {
    static let ru = Locale.preferredLanguages.first?.hasPrefix("ru") ?? true
    static var secured: String { ru ? "Защищено" : "Protected" }
    static var disconnected: String { ru ? "Отключено" : "Disconnected" }
    static var connect: String { ru ? "Подключить" : "Connect" }
    static var disconnect: String { ru ? "Отключить" : "Disconnect" }
    static var bypassRu: String { ru ? "Обход РФ" : "Bypass RU" }
    static var nextServer: String { ru ? "След. сервер" : "Next server" }
    static var noLocation: String { ru ? "Выберите сервер" : "Select server" }
}

// MARK: - App Intents


@available(iOS 16.0, *)
struct ToggleBypassRussiaIntent: AppIntent {
    static var title: LocalizedStringResource = "Обход РФ"
    static var description = IntentDescription("Переключает режим обхода РФ.")

    func perform() async throws -> some IntentResult {
        try await Vpn.toggleBypassRussia()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.0, *)
struct NextLocationIntent: AppIntent {
    static var title: LocalizedStringResource = "Следующий сервер"
    static var description = IntentDescription("Переключает на следующий доступный сервер.")

    func perform() async throws -> some IntentResult {
        try await Vpn.switchToNextLocation()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 18.0, *)
struct SetVpnIntent: SetValueIntent {
    static var title: LocalizedStringResource = "YPtun VPN"

    @Parameter(title: "Включен")
    var value: Bool

    func perform() async throws -> some IntentResult {
        try await Vpn.set(value)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

// MARK: - Timeline Entry & Provider

struct VpnEntry: TimelineEntry {
    let date: Date
    let connected: Bool
    let connectedDate: Date?
    let name: String
    let flag: String
    let ping: Int
    let bypassRussia: Bool
}

struct VpnProvider: TimelineProvider {
    func placeholder(in context: Context) -> VpnEntry {
        VpnEntry(
            date: Date(),
            connected: false,
            connectedDate: nil,
            name: "YPtun",
            flag: "🌐",
            ping: -1,
            bypassRussia: false
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (VpnEntry) -> Void) {
        Task { completion(await entry()) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VpnEntry>) -> Void) {
        Task {
            let currentEntry = await entry()
            let nextRefresh = Date().addingTimeInterval(15 * 60)
            completion(Timeline(entries: [currentEntry], policy: .after(nextRefresh)))
        }
    }

    private func entry() async -> VpnEntry {
        let vpnStatus = await Vpn.status()
        let widgetData = Vpn.loadWidgetData()
        return VpnEntry(
            date: Date(),
            connected: vpnStatus.connected,
            connectedDate: vpnStatus.connectedDate,
            name: widgetData.name,
            flag: widgetData.flag,
            ping: widgetData.ping,
            bypassRussia: widgetData.bypassRussia
        )
    }
}

// MARK: - Widget Views

/// Small 2x2 Widget: One-tap Power button, Protection status, Flag + Server name (NO ping)
struct SmallWidgetView: View {
    let entry: VpnEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Header status
            HStack(spacing: 5) {
                Circle()
                    .fill(entry.connected ? Color.green : Color.secondary.opacity(0.5))
                    .frame(width: 8, height: 8)
                Text(entry.connected ? L.secured : L.disconnected)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(entry.connected ? .green : .secondary)
                Spacer()
                Text(entry.flag)
                    .font(.system(size: 16))
            }

            Spacer(minLength: 2)

            // Server name
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name.isEmpty ? L.noLocation : entry.name)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(.primary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 2)

            // Interactive Power Button
            if #available(iOS 17.0, *) {
                Button(intent: ToggleVpnIntent()) {
                    HStack(spacing: 6) {
                        Image(systemName: "power")
                            .font(.system(size: 13, weight: .bold))
                        Text(entry.connected ? L.disconnect : L.connect)
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 36)
                    .background(
                        entry.connected
                            ? AnyShapeStyle(Color.red.opacity(0.18))
                            : AnyShapeStyle(Color.accentColor.opacity(0.2))
                    )
                    .foregroundColor(entry.connected ? .red : .accentColor)
                    .clipShape(Capsule())
                    .overlay(
                        Capsule()
                            .stroke(entry.connected ? Color.red.opacity(0.4) : Color.accentColor.opacity(0.4), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(12)
        .widgetBackground()
    }
}

/// Medium 2x4 Widget: Large Power button, Status, Live session timer, Server flag + name, Ping, Bypass Russia toggle, Next server button
struct MediumWidgetView: View {
    let entry: VpnEntry

    var body: some View {
        HStack(spacing: 14) {
            // Left Column: Power button + status + live timer
            VStack(spacing: 6) {
                if #available(iOS 17.0, *) {
                    Button(intent: ToggleVpnIntent()) {
                        ZStack {
                            Circle()
                                .fill(
                                    entry.connected
                                        ? AnyShapeStyle(LinearGradient(colors: [Color.green, Color.teal], startPoint: .topLeading, endPoint: .bottomTrailing))
                                        : AnyShapeStyle(Color.secondary.opacity(0.15))
                                )
                                .frame(width: 58, height: 58)
                                .shadow(color: entry.connected ? Color.green.opacity(0.4) : Color.clear, radius: 8, x: 0, y: 2)

                            Image(systemName: "power")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(entry.connected ? .white : .secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }

                Text(entry.connected ? L.secured : L.disconnected)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(entry.connected ? .green : .secondary)

                if entry.connected, let connDate = entry.connectedDate {
                    Text(connDate, style: .timer)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                } else {
                    Text("00:00")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundColor(.secondary.opacity(0.5))
                        .lineLimit(1)
                }
            }
            .frame(width: 82)

            Divider()
                .padding(.vertical, 4)

            // Right Column: Server info, ping, and quick action buttons
            VStack(alignment: .leading, spacing: 8) {
                // Top: Server & Ping
                HStack(spacing: 6) {
                    Text(entry.flag)
                        .font(.system(size: 20))
                    Text(entry.name.isEmpty ? L.noLocation : entry.name)
                        .font(.system(size: 14, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)

                    Spacer(minLength: 4)

                    // Ping pill
                    if entry.ping > 0 {
                        HStack(spacing: 4) {
                            Circle()
                                .fill(entry.ping < 120 ? Color.green : (entry.ping < 250 ? Color.orange : Color.red))
                                .frame(width: 5, height: 5)
                            Text("\(entry.ping) ms")
                                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Color.secondary.opacity(0.12))
                        .clipShape(Capsule())
                    } else {
                        Text("-- ms")
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundColor(.secondary.opacity(0.6))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.secondary.opacity(0.08))
                            .clipShape(Capsule())
                    }
                }

                Spacer(minLength: 0)

                // Bottom row: Quick action buttons (Обход РФ & След. сервер)
                if #available(iOS 17.0, *) {
                    HStack(spacing: 8) {
                        // Quick toggle: Обход РФ
                        Button(intent: ToggleBypassRussiaIntent()) {
                            HStack(spacing: 5) {
                                Image(systemName: entry.bypassRussia ? "checkmark.shield.fill" : "shield")
                                    .font(.system(size: 11, weight: .semibold))
                                Text(L.bypassRu)
                                    .font(.system(size: 11, weight: .semibold))
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 32)
                            .background(
                                entry.bypassRussia
                                    ? AnyShapeStyle(Color.blue.opacity(0.22))
                                    : AnyShapeStyle(Color.secondary.opacity(0.12))
                            )
                            .foregroundColor(entry.bypassRussia ? .blue : .primary)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .stroke(entry.bypassRussia ? Color.blue.opacity(0.4) : Color.secondary.opacity(0.2), lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)

                        // Button: Следующий сервер
                        Button(intent: NextLocationIntent()) {
                            HStack(spacing: 5) {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: 10, weight: .bold))
                                Text(L.nextServer)
                                    .font(.system(size: 11, weight: .semibold))
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 32)
                            .background(Color.secondary.opacity(0.12))
                            .foregroundColor(.primary)
                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(12)
        .widgetBackground()
    }
}

// MARK: - Lock Screen Views

@available(iOS 16.0, *)
struct LockScreenCircularView: View {
    let entry: VpnEntry

    var body: some View {
        if #available(iOS 17.0, *) {
            Button(intent: ToggleVpnIntent()) {
                ZStack {
                    AccessoryWidgetBackground()
                    Image(systemName: entry.connected ? "lock.shield.fill" : "shield.slash")
                        .font(.system(size: 22, weight: .semibold))
                }
            }
            .buttonStyle(.plain)
        } else {
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: entry.connected ? "lock.shield.fill" : "shield.slash")
                    .font(.system(size: 22, weight: .semibold))
            }
        }
    }
}

@available(iOS 16.0, *)
struct LockScreenRectangularView: View {
    let entry: VpnEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: entry.connected ? "lock.shield.fill" : "shield.slash")
                    .font(.caption2)
                Text(entry.connected ? L.secured : L.disconnected)
                    .font(.caption2.weight(.bold))
                Spacer()
                if entry.connected, let connDate = entry.connectedDate {
                    Text(connDate, style: .timer)
                        .font(.caption2.monospacedDigit())
                }
            }
            Text(entry.flag + " " + (entry.name.isEmpty ? "YPtun" : entry.name))
                .font(.headline)
                .lineLimit(1)
            if entry.bypassRussia {
                Text(L.bypassRu + " • Вкл")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
    }
}

// MARK: - Unified Widget View

struct VpnWidgetView: View {
    @Environment(\.widgetFamily) var family
    let entry: VpnEntry

    var body: some View {
        switch family {
        case .systemSmall:
            SmallWidgetView(entry: entry)
        case .systemMedium:
            MediumWidgetView(entry: entry)
        default:
            if #available(iOS 16.0, *) {
                lockScreenContent(for: family)
            } else {
                SmallWidgetView(entry: entry)
            }
        }
    }

    @available(iOS 16.0, *)
    @ViewBuilder
    private func lockScreenContent(for family: WidgetFamily) -> some View {
        switch family {
        case .accessoryCircular:
            LockScreenCircularView(entry: entry)
        case .accessoryRectangular:
            LockScreenRectangularView(entry: entry)
        case .accessoryInline:
            Text("\(entry.flag) \(entry.connected ? L.secured : L.disconnected)")
        default:
            SmallWidgetView(entry: entry)
        }
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

private var widgetSupportedFamilies: [WidgetFamily] {
    if #available(iOS 16.0, *) {
        return [
            .systemSmall,
            .systemMedium,
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline
        ]
    } else {
        return [.systemSmall, .systemMedium]
    }
}

struct VpnWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "org.yptun.app.vpn-widget", provider: VpnProvider()) { entry in
            VpnWidgetView(entry: entry)
        }
        .configurationDisplayName("YPtun")
        .description(L.ru ? "Состояние VPN и быстрое управление" : "VPN status and quick controls")
        .supportedFamilies(widgetSupportedFamilies)
    }
}

// MARK: - Control Center (iOS 18)

@available(iOS 18.0, *)
struct VpnControlProvider: ControlValueProvider {
    var previewValue: Bool { false }

    func currentValue() async throws -> Bool {
        await Vpn.status().connected
    }
}

@available(iOS 18.0, *)
struct VpnControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "org.yptun.app.vpn-control", provider: VpnControlProvider()) { isOn in
            ControlWidgetToggle(isOn: isOn, action: SetVpnIntent()) {
                Label("YPtun", image: "CatTile")
            } valueLabel: { on in
                Text(on ? L.secured : L.disconnected)
            }
        }
        .displayName("YPtun VPN")
    }
}

// MARK: - Bundle

@main
struct YPtunWidgets: WidgetBundle {
    var body: some Widget {
        VpnWidget()
        if #available(iOS 16.2, *) {
            VpnLiveActivityWidget()
        }
        if #available(iOS 18.0, *) {
            VpnControl()
        }
    }
}
