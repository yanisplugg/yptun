import ActivityKit
import SwiftUI
import WidgetKit

@available(iOS 16.2, *)
struct VpnLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VpnActivityAttributes.self) { context in
            // Lock screen / Banner UI
            LockScreenLiveActivityView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                // Expanded UI (when long-pressed)
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        Text(context.state.flag)
                            .font(.title2)
                        Text(context.state.serverName)
                            .font(.system(size: 16, weight: .bold))
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    .padding(.leading, 8)
                }

                DynamicIslandExpandedRegion(.trailing) {
                    if let start = context.state.connectedDate, context.state.isConnected {
                        HStack(spacing: 5) {
                            Circle()
                                .fill(Color.green)
                                .frame(width: 6, height: 6)
                            Text(start, style: .timer)
                                .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                .monospacedDigit()
                                .foregroundColor(.green)
                        }
                        .padding(.trailing, 8)
                    }
                }

                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        HStack(spacing: 6) {
                            Image("CatTile")
                                .renderingMode(.template)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 15, height: 15)
                                .foregroundColor(context.state.isConnected ? .green : .secondary)
                            Text(context.state.isConnected ? "VPN активен" : "Отключен")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(context.state.isConnected ? .green : .secondary)
                        }

                        Spacer()

                        if #available(iOS 17.0, *) {
                            Button(intent: ToggleVpnIntent()) {
                                Text(context.state.isConnected ? "Отключить" : "Подключить")
                                    .font(.system(size: 13, weight: .semibold))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 7)
                                    .background(context.state.isConnected ? Color.red.opacity(0.85) : Color.green.opacity(0.85))
                                    .foregroundColor(.white)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.top, 4)
                    .padding(.bottom, 6)
                }
            } compactLeading: {
                HStack(spacing: 3) {
                    Image("CatTile")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 12, height: 12)
                        .foregroundColor(context.state.isConnected ? .green : .gray)
                    Text(context.state.flag)
                        .font(.system(size: 12))
                }
                .padding(.leading, 4)
            } compactTrailing: {
                if let start = context.state.connectedDate, context.state.isConnected {
                    Text(start, style: .timer)
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .monospacedDigit()
                        .foregroundColor(.green)
                        .frame(width: 34, alignment: .trailing)
                        .padding(.trailing, 4)
                } else {
                    Circle()
                        .fill(context.state.isConnected ? Color.green : Color.gray)
                        .frame(width: 7, height: 7)
                        .padding(.trailing, 4)
                }
            } minimal: {
                HStack(spacing: 2) {
                    Image("CatTile")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 11, height: 11)
                        .foregroundColor(context.state.isConnected ? .green : .gray)
                    Text(context.state.flag)
                        .font(.system(size: 10))
                }
            }
        }
    }
}

// MARK: - Lock Screen Banner View

@available(iOS 16.2, *)
struct LockScreenLiveActivityView: View {
    let context: ActivityViewContext<VpnActivityAttributes>

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(context.state.isConnected ? Color.green.opacity(0.15) : Color.gray.opacity(0.15))
                    .frame(width: 44, height: 44)
                Text(context.state.flag)
                    .font(.title2)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(context.state.serverName)
                        .font(.system(size: 16, weight: .semibold))
                        .lineLimit(1)

                    Spacer()

                    if let start = context.state.connectedDate, context.state.isConnected {
                        Text(start, style: .timer)
                            .font(.system(size: 14, weight: .medium, design: .monospaced))
                            .monospacedDigit()
                            .foregroundColor(.green)
                    }
                }

                Text(context.state.isConnected ? "VPN подключен · Защищено" : "VPN отключен")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }

            if #available(iOS 17.0, *) {
                Button(intent: ToggleVpnIntent()) {
                    Image(systemName: context.state.isConnected ? "stop.fill" : "play.fill")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 36, height: 36)
                        .background(context.state.isConnected ? Color.red.opacity(0.9) : Color.green.opacity(0.9))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(UIColor.secondarySystemBackground))
    }
}
