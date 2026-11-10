import Foundation
import Observation
import YPtunMigrationSupport
import YPtunShared

public enum YPtunAppSection: String, CaseIterable, Hashable, Identifiable, Sendable {
    case connection
    case settings

    public var id: Self { self }

    public var title: String {
        switch self {
        case .connection: "Подключение"
        case .settings: "Настройки"
        }
    }

    public var symbol: String {
        switch self {
        case .connection: "bolt.fill"
        case .settings: "gearshape"
        }
    }
}

public struct YPtunNotice: Identifiable, Equatable, Sendable {
    public let id = UUID()
    public let title: String
    public let message: String

    public init(title: String, message: String) {
        self.title = title
        self.message = message
    }
}

@MainActor
@Observable
public final class YPtunApplicationModel {
    public private(set) var connectionState: VPNConnectionState = .disconnected
    public private(set) var locations: [LocationListItem]
    public var selectedLocationID: String?
    public private(set) var isBootstrapping = false
    public private(set) var startupMessage: String?
    public var isAddLocationPresented = false
    public var presentedNotice: YPtunNotice?

    private let bootstrapper: AppleApplicationBootstrapper
    private var didBootstrap = false

    public init(
        locations: [LocationListItem] = [],
        selectedLocationID: String? = nil,
        bootstrapper: AppleApplicationBootstrapper = AppleApplicationBootstrapper()
    ) {
        self.locations = locations
        self.selectedLocationID = selectedLocationID
        self.bootstrapper = bootstrapper
    }

    public var selectedLocation: LocationListItem? {
        locations.first { $0.id == selectedLocationID }
    }

    public var primaryActionTitle: String {
        guard selectedLocation != nil else { return "Добавить локацию" }
        return switch connectionState {
        case .disconnected: "Подключить"
        case .connecting: "Подключение…"
        case .connected: "Отключить"
        case .disconnecting: "Отключение…"
        }
    }

    public var primaryActionSymbol: String {
        selectedLocation == nil ? "plus" : connectionState == .connected ? "stop.fill" : "power"
    }

    public func bootstrap() async {
        guard !didBootstrap else { return }
        didBootstrap = true
        isBootstrapping = true
        defer { isBootstrapping = false }

        do {
            let snapshot = try await bootstrapper.load()
            locations = snapshot.locations
            selectedLocationID = snapshot.activeLocationID
                ?? snapshot.locations.first(where: \.isComplete)?.id
            if snapshot.migrationHasFailures {
                startupMessage = "Часть старых данных не удалось перенести. Исходные файлы сохранены."
            } else if snapshot.migrationMadeChanges {
                startupMessage = "Данные предыдущей версии перенесены без изменения исходных файлов."
            }
        } catch {
            startupMessage = "Не удалось прочитать сохранённые локации. Исходные данные не изменены."
        }
    }

    public func select(_ location: LocationListItem) {
        selectedLocationID = location.id
    }

    public func requestPrimaryAction() {
        guard let selectedLocation else {
            isAddLocationPresented = true
            return
        }
        guard selectedLocation.isComplete else {
            presentedNotice = YPtunNotice(
                title: selectedLocation.needsUserAction ? "Требуется действие" : "Локация не готова",
                message: selectedLocation.needsUserAction
                    ? "Завершите привязку аккаунта в настройках этой локации."
                    : "Проверьте обязательные параметры конфигурации перед подключением."
            )
            return
        }
        presentedNotice = YPtunNotice(
            title: "Подключение пока недоступно",
            message: "Нативный Packet Tunnel ещё подключается к движкам. Сохранённая локация уже перенесена и не будет потеряна."
        )
    }

    public func showPendingFeature(_ title: String) {
        presentedNotice = YPtunNotice(
            title: title,
            message: "Раздел уже заложен в нативную структуру и будет подключён к Rust-ядру следующим этапом."
        )
    }
}
