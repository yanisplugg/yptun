import Foundation
import SwiftUI
import WidgetKit
import YPtunShared
import YPtunTunnelSupport

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
