import ActivityKit
import Foundation
import NetworkExtension

@available(iOS 16.2, *)
enum LiveActivityManager {
    private static let appGroup = "group.org.yptun.app"
    private static var isStarting = false

    static func isEnabled() -> Bool {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("yptun/live_activity.txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return true // Enabled by default
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines) != "false"
    }

    static func onConnected(date: Date? = nil) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard isEnabled() else {
            endAll()
            return
        }

        Task { @MainActor in
            var startDate = date
            if startDate == nil {
                if let manager = try? await NETunnelProviderManager.loadAllFromPreferences().first,
                   let connDate = manager.connection.connectedDate {
                    startDate = connDate
                }
            }
            if startDate == nil {
                startDate = Date()
            }

            let (flag, name) = loadServerInfo()
            let state = VpnActivityAttributes.ContentState(
                isConnected: true,
                serverName: name,
                flag: flag,
                connectedDate: startDate
            )

            if let activity = Activity<VpnActivityAttributes>.activities.first {
                await activity.update(ActivityContent(state: state, staleDate: nil))
            } else if !isStarting {
                isStarting = true
                defer { isStarting = false }
                let attributes = VpnActivityAttributes()
                do {
                    _ = try Activity.request(
                        attributes: attributes,
                        content: ActivityContent(state: state, staleDate: nil)
                    )
                } catch {
                    NSLog("YPtun: Failed to start Live Activity: \(error)")
                }
            }
        }
    }

    static func onDisconnected() {
        endAll()
    }

    static func endAll() {
        Task { @MainActor in
            for activity in Activity<VpnActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    private static func loadServerInfo() -> (flag: String, name: String) {
        guard
            let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
                .appendingPathComponent("yptun/widget.json"),
            let data = try? Data(contentsOf: url),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let rawName = object["name"] as? String
        else {
            return ("🌐", "YPtun")
        }

        let res = extractFlagAndName(from: rawName)
        return (flag: res.flag, name: res.name)
    }
}
