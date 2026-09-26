import AppIntents
import Foundation
import NetworkExtension
import WidgetKit

@available(iOS 16.0, *)
public enum VpnControlBridge {
    private static let appGroup = "group.org.yptun.app"

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

    public static func restartOrConnect() async throws {
        guard let manager = await manager() else { return }
        let s = manager.connection.status
        if s == .connected || s == .connecting || s == .reasserting {
            manager.connection.stopVPNTunnel()
            for _ in 0..<25 {
                try? await Task.sleep(nanoseconds: 100_000_000)
                try? await manager.loadFromPreferences()
                let status = manager.connection.status
                if status == .disconnected || status == .invalid {
                    break
                }
            }
        }
        if !manager.isEnabled {
            manager.isEnabled = true
            try await manager.saveToPreferences()
            try await manager.loadFromPreferences()
        }
        try manager.connection.startVPNTunnel()
    }

    public static func switchToNextLocation() async throws {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return }
        let widgetUrl = base.appendingPathComponent("yptun/widget.json")
        let tunnelReqUrl = base.appendingPathComponent("yptun/tunnel_request.json")
        let legacyReqUrl = base.appendingPathComponent("yptun/request.json")

        guard
            let data = try? Data(contentsOf: widgetUrl),
            var widgetObj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let rawLocations = widgetObj["locations"] as? [[String: Any]],
            !rawLocations.isEmpty
        else {
            try await set(true)
            return
        }

        let currentName = widgetObj["name"] as? String ?? ""
        let currentIndex = rawLocations.firstIndex(where: { ($0["name"] as? String) == currentName }) ?? 0
        let nextIndex = (currentIndex + 1) % rawLocations.count
        let nextLoc = rawLocations[nextIndex]

        if let nextReq = nextLoc["requestJson"] as? String,
           let reqData = nextReq.data(using: .utf8) {
            try? reqData.write(to: tunnelReqUrl)
            try? reqData.write(to: legacyReqUrl)
        }

        widgetObj["name"] = nextLoc["name"]
        widgetObj["id"] = nextLoc["id"]
        if let p = nextLoc["ping"] as? NSNumber {
            widgetObj["ping"] = p.intValue
        }
        if let outData = try? JSONSerialization.data(withJSONObject: widgetObj, options: [.prettyPrinted]) {
            try? outData.write(to: widgetUrl)
        }

        try await restartOrConnect()
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
public struct NextLocationIntent: AppIntent {
    public static var title: LocalizedStringResource = "Следующий сервер"
    public static var description = IntentDescription("Переключает на следующий доступный сервер и подключается к нему.")

    public init() {}

    public func perform() async throws -> some IntentResult {
        try await VpnControlBridge.switchToNextLocation()
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
            intent: NextLocationIntent(),
            phrases: [
                "Следующий сервер в \(.applicationName)",
                "Сменить сервер в \(.applicationName)",
                "Next server in \(.applicationName)"
            ],
            shortTitle: "Следующий сервер",
            systemImageName: "forward.fill"
        )
    }
}
