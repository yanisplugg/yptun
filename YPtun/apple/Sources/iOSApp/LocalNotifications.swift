import Foundation
@preconcurrency import UserNotifications

/// Local notifications requested by shared Kotlin code — subscription expiry (both immediate
/// and future `UNCalendarNotificationTrigger` schedules so alerts fire even when the app is
/// closed) and Remnawave panel announcements.
///
/// Signals over `NotificationCenter.default`:
/// - `org.yptun.notify.authorize`: request notification permission from iOS
/// - `org.yptun.notify.post`: post immediately (`userInfo`: `id`, `title`, `body`; or legacy `[id, title, body]`)
/// - `org.yptun.notify.schedule`: schedule for a future date (`userInfo`: `id`, `title`, `body`, `fireAtEpochSec`)
/// - `org.yptun.notify.clearExpiry`: cancel all pending scheduled subscription-expiry notifications
final class LocalNotifications: NSObject, @unchecked Sendable, UNUserNotificationCenterDelegate {
    /// UNUserNotificationCenter holds its delegate weakly.
    static let shared = LocalNotifications()
    private static let expiryPrefix = "yptun.expiry."

    func install() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("org.yptun.notify.authorize"), object: nil, queue: .main
        ) { _ in
            center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }

        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("org.yptun.notify.post"), object: nil, queue: .main
        ) { note in
            guard let payload = Self.extractPayload(from: note) else { return }
            Self.addRequest(
                identifier: payload.id,
                title: payload.title,
                body: payload.body,
                trigger: nil
            )
        }

        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("org.yptun.notify.schedule"), object: nil, queue: .main
        ) { note in
            guard let payload = Self.extractPayload(from: note),
                  let fireAt = payload.fireAt else { return }
            guard fireAt.timeIntervalSinceNow > 5 else { return }
            let components = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute, .second],
                from: fireAt
            )
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            Self.addRequest(
                identifier: payload.id,
                title: payload.title,
                body: payload.body,
                trigger: trigger
            )
        }

        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("org.yptun.notify.clearExpiry"), object: nil, queue: .main
        ) { _ in
            center.getPendingNotificationRequests { requests in
                let ids = requests.map(\.identifier).filter { $0.hasPrefix(Self.expiryPrefix) }
                if !ids.isEmpty {
                    center.removePendingNotificationRequests(withIdentifiers: ids)
                }
            }
        }
    }

    private struct NotificationPayload {
        let id: String
        let title: String
        let body: String
        let fireAt: Date?
    }

    private static func extractPayload(from note: Notification) -> NotificationPayload? {
        if let info = note.userInfo,
           let id = info["id"] as? String,
           let title = info["title"] as? String,
           let body = info["body"] as? String {
            let fireAt: Date?
            if let sec = info["fireAtEpochSec"] as? NSNumber {
                fireAt = Date(timeIntervalSince1970: sec.doubleValue)
            } else if let sec = info["fireAtEpochSec"] as? Double {
                fireAt = Date(timeIntervalSince1970: sec)
            } else if let secStr = info["fireAtEpochSec"] as? String, let sec = Double(secStr) {
                fireAt = Date(timeIntervalSince1970: sec)
            } else {
                fireAt = nil
            }
            return NotificationPayload(id: id, title: title, body: body, fireAt: fireAt)
        }

        if let raw = note.object as? [Any] {
            let parts = raw.compactMap { $0 as? String }
            if parts.count >= 3 {
                let fireAt: Date? = parts.count >= 4 ? Double(parts[3]).map(Date.init(timeIntervalSince1970:)) : nil
                return NotificationPayload(id: parts[0], title: parts[1], body: parts[2], fireAt: fireAt)
            }
        }
        return nil
    }

    private static func addRequest(
        identifier: String,
        title: String,
        body: String,
        trigger: UNNotificationTrigger?
    ) {
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                center.add(request)
            case .notDetermined:
                center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
                    if granted { center.add(request) }
                }
            default:
                break
            }
        }
    }

    /// Subscriptions refresh while the app is open; without this iOS files the notification away
    /// silently instead of showing it.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
