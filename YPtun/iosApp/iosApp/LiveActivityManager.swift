import ActivityKit
import Foundation
import NetworkExtension

@available(iOS 16.1, *)
enum LiveActivityManager {
    private static let appGroup = "group.org.yptun.app"

    static func isEnabled() -> Bool {
        guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("yptun/live_activity.txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return true // Enabled by default
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines) != "false"
    }

    static func update(connected: Bool, connectedDate: Date?) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        guard isEnabled() else {
            endAll()
            return
        }

        if connected {
            let (flag, name) = loadServerInfo()
            let state = VpnActivityAttributes.ContentState(
                isConnected: true,
                serverName: name,
                flag: flag,
                connectedDate: connectedDate ?? Date()
            )

            if let activity = Activity<VpnActivityAttributes>.activities.first {
                Task {
                    await activity.update(ActivityContent(state: state, staleDate: nil))
                }
            } else {
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
        } else {
            endAll()
        }
    }

    static func endAll() {
        Task {
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

        let trimmed = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return ("🌐", "YPtun") }

        // Extract leading emoji flag if present
        var emojiPrefix = ""
        for char in trimmed {
            if char.unicodeScalars.first?.properties.isEmoji == true {
                emojiPrefix.append(char)
            } else {
                break
            }
        }
        let flagCandidate = emojiPrefix.trimmingCharacters(in: .whitespaces)
        if !flagCandidate.isEmpty {
            let rest = trimmed.dropFirst(emojiPrefix.count).trimmingCharacters(in: .whitespaces)
            return (flagCandidate, rest.isEmpty ? trimmed : rest)
        }
        return ("🌐", trimmed)
    }
}
