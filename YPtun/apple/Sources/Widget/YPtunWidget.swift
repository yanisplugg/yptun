import Foundation
import SwiftUI
import WidgetKit
import YPtunShared

private struct StatusEntry: TimelineEntry {
    let date: Date
    let state: VPNConnectionState
}

private struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, state: .disconnected)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        completion(StatusEntry(date: .now, state: .disconnected))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        let entry = StatusEntry(date: .now, state: .disconnected)
        completion(Timeline(entries: [entry], policy: .never))
    }
}

private struct StatusWidgetView: View {
    let entry: StatusEntry

    var body: some View {
        VStack(alignment: .leading) {
            Text(YPtunIdentity.displayName)
                .font(.headline)
            Text(entry.state.rawValue.capitalized)
                .font(.caption)
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}

private struct StatusWidget: Widget {
    let kind = "org.yptun.app.vpn-widget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
        }
        .configurationDisplayName("YPtun Status")
        .description("Shows the current VPN connection state.")
        .supportedFamilies([.systemSmall])
    }
}

@main
struct YPtunWidgetBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
    }
}
