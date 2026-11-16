import Foundation
import UserNotifications

/// The local notifications the shared code asks for — subscription expiry and panel announcements,
/// the same pushes Android shows. Kotlin signals through NotificationCenter by name, the way it already
/// drives the Live Activity, so the Kotlin ↔ Swift bridge stays as it is:
/// `org.yptun.notify.authorize` when the user turns a push on, `org.yptun.notify.post` with
/// `[identifier, title, body]` for each notification.
final class LocalNotifications: NSObject, UNUserNotificationCenterDelegate {
    /// UNUserNotificationCenter holds its delegate weakly.
    static let shared = LocalNotifications()

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
            guard let parts = note.object as? [String], parts.count == 3 else { return }
            Self.post(identifier: parts[0], title: parts[1], body: parts[2])
        }
    }

    private static func post(identifier: String, title: String, body: String) {
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                center.add(request)
            case .notDetermined:
                // The push was switched on before this prompt existed: ask now, the user opted in.
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
