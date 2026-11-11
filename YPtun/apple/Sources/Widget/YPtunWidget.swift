import Foundation
import AppIntents
import SwiftUI
import WidgetKit
import YPtunShared
import YPtunVPNControl

private struct StatusEntry: TimelineEntry {
    let date: Date
    let state: VPNConnectionState
}

private struct StatusProvider: TimelineProvider {
    private func entry() -> StatusEntry {
        let raw = try? AppleTunnelStore.readText(AppleTunnelStore.statusFileName)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return StatusEntry(
            date: .now,
            state: raw.flatMap(VPNConnectionState.init(rawValue:)) ?? .disconnected
        )
    }

    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, state: .disconnected)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        completion(entry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        completion(Timeline(entries: [entry()], policy: .after(.now.addingTimeInterval(60))))
    }
}

private struct StatusWidgetView: View {
    let entry: StatusEntry

    var body: some View {
        VStack(alignment: .leading) {
            Text(YPtunIdentity.displayName)
                .font(.headline)
            Text(statusTitle)
                .font(.caption)
            Button(intent: ToggleVPNIntent()) {
                Label(
                    entry.state == .connected ? "Отключить" : "Подключить",
                    systemImage: entry.state == .connected ? "stop.fill" : "power"
                )
                .font(.caption.weight(.semibold))
            }
            .buttonStyle(.borderedProminent)
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private var statusTitle: String {
        switch entry.state {
        case .disconnected: "Не подключено"
        case .connecting: "Подключение"
        case .connected: "Защищено"
        case .disconnecting: "Отключение"
        }
    }
}

private struct ToggleVPNIntent: AppIntent {
    static let title: LocalizedStringResource = "Переключить YPtun"
    static let description = IntentDescription("Подключает или отключает последнюю выбранную локацию.")

    @MainActor
    func perform() async throws -> some IntentResult {
        let controller = AppleVPNController()
        let state = await controller.currentState()
        if state == .connected || state == .connecting {
            await controller.stop()
        } else {
            try await controller.start()
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

private struct StatusWidget: Widget {
    let kind = "org.yptun.app.vpn-widget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
        }
        .configurationDisplayName("Статус YPtun")
        .description("Показывает состояние VPN-подключения.")
        .supportedFamilies([.systemSmall])
    }
}

@main
struct YPtunWidgetBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
    }
}
