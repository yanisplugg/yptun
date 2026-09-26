import AppIntents
import Foundation
import NetworkExtension
import WidgetKit

@available(iOS 16.0, *)
public enum VpnControlBridge {
    public static func manager() async -> NETunnelProviderManager? {
        try? await NETunnelProviderManager.loadAllFromPreferences().first
    }

    public static func status() async -> (connected: Bool, connectedDate: Date?) {
        guard let manager = await manager() else { return (false, nil) }
        let s = manager.connection.status
        let isConn = (s == .connected || s == .connecting || s == .reasserting)
        return (isConn, isConn ? manager.connection.connectedDate : nil)
    }

    public static func set(_ on: Bool) async throws {
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

    public static func autoSelect() async throws {
        // Trigger auto-select signal
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.org.yptun.app") else {
            try await set(true)
            return
        }
        let signalUrl = base.appendingPathComponent("yptun/widget_auto_signal.txt")
        try? "request".data(using: .utf8)?.write(to: signalUrl)
        try await set(true)
    }
}

// MARK: - App Intents

@available(iOS 16.0, *)
public struct ToggleVpnIntent: AppIntent {
    public static var title: LocalizedStringResource = "Переключить VPN"
    public static var description = IntentDescription("Включает или выключает VPN.")

    public init() {}

    public func perform() async throws -> some IntentResult {
        let isConnected = await VpnControlBridge.status().connected
        try await VpnControlBridge.set(!isConnected)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.0, *)
public struct ConnectVpnIntent: AppIntent {
    public static var title: LocalizedStringResource = "Подключить VPN"
    public static var description = IntentDescription("Включает VPN соединение.")

    public init() {}

    public func perform() async throws -> some IntentResult {
        try await VpnControlBridge.set(true)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.0, *)
public struct DisconnectVpnIntent: AppIntent {
    public static var title: LocalizedStringResource = "Отключить VPN"
    public static var description = IntentDescription("Отключает VPN соединение.")

    public init() {}

    public func perform() async throws -> some IntentResult {
        try await VpnControlBridge.set(false)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.0, *)
public struct AutoSelectVpnIntent: AppIntent {
    public static var title: LocalizedStringResource = "Быстрый выбор сервера"
    public static var description = IntentDescription("Находит самый быстрый сервер и подключается к нему.")

    public init() {}

    public func perform() async throws -> some IntentResult {
        try await VpnControlBridge.autoSelect()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

@available(iOS 16.0, *)
public struct GetVpnStatusIntent: AppIntent {
    public static var title: LocalizedStringResource = "Статус VPN"
    public static var description = IntentDescription("Возвращает 'true', если VPN сейчас подключен.")

    public init() {}

    public func perform() async throws -> some IntentResult & ReturnsValue<Bool> {
        let isConnected = await VpnControlBridge.status().connected
        return .result(value: isConnected)
    }
}

// MARK: - App Shortcuts Provider

@available(iOS 16.0, *)
public struct YPtunShortcuts: AppShortcutsProvider {
    public static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ToggleVpnIntent(),
            phrases: [
                "Переключить \(.applicationName)",
                "Toggle \(.applicationName)",
                "Включить или выключить \(.applicationName)"
            ],
            shortTitle: "Переключить VPN",
            systemImageName: "power"
        )
        AppShortcut(
            intent: ConnectVpnIntent(),
            phrases: [
                "Подключить \(.applicationName)",
                "Connect \(.applicationName)",
                "Включить \(.applicationName)"
            ],
            shortTitle: "Подключить VPN",
            systemImageName: "play.fill"
        )
        AppShortcut(
            intent: DisconnectVpnIntent(),
            phrases: [
                "Отключить \(.applicationName)",
                "Disconnect \(.applicationName)",
                "Выключить \(.applicationName)"
            ],
            shortTitle: "Отключить VPN",
            systemImageName: "stop.fill"
        )
        AppShortcut(
            intent: AutoSelectVpnIntent(),
            phrases: [
                "Быстрый сервер в \(.applicationName)",
                "Автовыбор в \(.applicationName)",
                "Fastest server in \(.applicationName)"
            ],
            shortTitle: "Автовыбор сервера",
            systemImageName: "bolt.fill"
        )
    }
}
