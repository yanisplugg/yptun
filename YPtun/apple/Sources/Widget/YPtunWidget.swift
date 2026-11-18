import ActivityKit
import AppIntents
import Foundation
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
            if #available(iOS 17.0, *) {
                Button(intent: ToggleVPNIntent()) {
                    Label(
                        entry.state == .connected ? "Отключить" : "Подключить",
                        systemImage: entry.state == .connected ? "stop.fill" : "power"
                    )
                    .font(.caption.weight(.semibold))
                }
                .buttonStyle(.borderedProminent)
            } else {
                Label("Открыть YPtun", systemImage: "arrow.up.forward.app")
                    .font(.caption.weight(.semibold))
            }
        }
        .ypWidgetBackground()
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

private extension View {
    @ViewBuilder
    func ypWidgetBackground() -> some View {
        if #available(iOS 17.0, *) {
            self.containerBackground(.fill.tertiary, for: .widget)
        } else {
            self.padding().background(Color.secondary.opacity(0.12))
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

@available(iOS 16.2, *)
private struct StopVPNLiveActivityIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Отключить YPtun"
    static let description = IntentDescription("Отключает активное VPN-подключение YPtun.")

    @MainActor
    func perform() async throws -> some IntentResult {
        let controller = AppleVPNController()
        await controller.stop()
        for activity in Activity<VpnActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.2, *)
private struct VpnLockScreenBanner: View {
    let state: VpnActivityAttributes.ContentState

    private var hasCountryFlag: Bool {
        !state.flag.isEmpty && state.flag != "🌐"
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.primary.opacity(0.08))
                    .frame(width: 38, height: 38)
                if hasCountryFlag {
                    Text(state.flag)
                        .font(.title3)
                } else {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.green)
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(state.serverName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                HStack(spacing: 5) {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 6, height: 6)
                    Text("Защищено")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let connectedDate = state.connectedDate {
                        Text("·")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                        Text(connectedDate, style: .timer)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }

            Spacer(minLength: 8)

            if #available(iOS 17.0, *) {
                Button(intent: StopVPNLiveActivityIntent()) {
                    Image(systemName: "power")
                        .font(.system(size: 14, weight: .semibold))
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)
                .tint(.secondary)
                .accessibilityLabel("Отключить VPN")
            } else {
                Text(YPtunIdentity.displayName)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }
}

@available(iOS 16.2, *)
private struct VpnLiveActivity: Widget {
    private func hasCountryFlag(_ flag: String) -> Bool {
        !flag.isEmpty && flag != "🌐"
    }

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VpnActivityAttributes.self) { context in
            VpnLockScreenBanner(state: context.state)
                .activitySystemActionForegroundColor(.primary)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 6) {
                        if hasCountryFlag(context.state.flag) {
                            Text(context.state.flag)
                                .font(.title3)
                        } else {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(.green)
                        }
                    }
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 6, height: 6)
                        if let connectedDate = context.state.connectedDate {
                            Text(connectedDate, style: .timer)
                                .font(.subheadline.weight(.medium).monospacedDigit())
                                .foregroundStyle(.primary)
                                .multilineTextAlignment(.trailing)
                                .frame(minWidth: 44, alignment: .trailing)
                        } else {
                            Text("VPN")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(alignment: .center, spacing: 10) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(context.state.serverName)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text("\(YPtunIdentity.displayName) · Защищено")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        if #available(iOS 17.0, *) {
                            Button(intent: StopVPNLiveActivityIntent()) {
                                Text("Отключить")
                                    .font(.caption.weight(.semibold))
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .tint(.secondary)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 2)
                }
            } compactLeading: {
                if hasCountryFlag(context.state.flag) {
                    Text(context.state.flag)
                        .font(.system(size: 14))
                        .padding(.leading, 2)
                } else {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.green)
                        .padding(.leading, 2)
                }
            } compactTrailing: {
                if let connectedDate = context.state.connectedDate {
                    Text(connectedDate, style: .timer)
                        .font(.caption2.weight(.medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: 48, alignment: .trailing)
                        .padding(.trailing, 2)
                } else {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 7, height: 7)
                        .padding(.trailing, 4)
                }
            } minimal: {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.green)
            }
            .keylineTint(.green.opacity(0.65))
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
        if #available(iOS 16.2, *) {
            VpnLiveActivity()
        }
    }
}
