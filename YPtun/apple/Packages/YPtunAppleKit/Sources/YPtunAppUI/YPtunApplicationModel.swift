import Foundation
import Combine
import YPtunMigrationSupport
import YPtunShared
import YPtunVPNControl

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

public struct LocationEditorRequest: Identifiable, Sendable {
    public let id = UUID()
    public let locationID: String?

    public init(locationID: String?) {
        self.locationID = locationID
    }
}

@MainActor
public final class YPtunApplicationModel: ObservableObject {
    @Published public private(set) var connectionState: VPNConnectionState = .disconnected
    @Published public private(set) var locations: [LocationListItem]
    @Published public var selectedLocationID: String?
    @Published public private(set) var isBootstrapping = false
    @Published public private(set) var startupMessage: String?
    @Published public var isAddLocationPresented = false
    @Published public var presentedNotice: YPtunNotice?
    @Published public var locationEditorRequest: LocationEditorRequest?
    @Published public var locationPendingDeletion: LocationListItem?

    private let bootstrapper: AppleApplicationBootstrapper
    private let vpnController: any AppleVPNControlling
    private let locationSelection: @MainActor @Sendable (String) async throws -> Void
    private var didBootstrap = false
    private var selectionTask: Task<Void, Never>?
    private var connectionTask: Task<Void, Never>?

    public init(
        locations: [LocationListItem] = [],
        selectedLocationID: String? = nil,
        bootstrapper: AppleApplicationBootstrapper = AppleApplicationBootstrapper(),
        vpnController: any AppleVPNControlling = AppleVPNController(),
        locationSelection: (@MainActor @Sendable (String) async throws -> Void)? = nil
    ) {
        self.locations = locations
        self.selectedLocationID = selectedLocationID
        self.bootstrapper = bootstrapper
        self.vpnController = vpnController
        self.locationSelection = locationSelection ?? { id in
            try await bootstrapper.selectLocation(id: id)
        }
        vpnController.onStateChange = { [weak self] state in
            self?.connectionState = state
        }
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
            connectionState = await vpnController.currentState()
        } catch {
            startupMessage = "Не удалось прочитать сохранённые локации. Исходные данные не изменены."
        }
    }

    public func select(_ location: LocationListItem) {
        guard selectedLocationID != location.id else { return }
        let previousLocationID = selectedLocationID
        let restartActiveTunnel = location.isComplete
            && (connectionState == .connected || connectionState == .connecting)
        selectedLocationID = location.id
        selectionTask?.cancel()
        connectionTask?.cancel()
        selectionTask = Task { [locationSelection, vpnController] in
            do {
                try await locationSelection(location.id)
            } catch is CancellationError {
                return
            } catch {
                guard selectedLocationID == location.id else { return }
                selectedLocationID = previousLocationID
                startupMessage = "Не удалось сохранить выбранную локацию. Исходные данные не изменены."
                return
            }

            do {
                try Task.checkCancellation()
                guard selectedLocationID == location.id else { return }
                if restartActiveTunnel {
                    connectionState = .connecting
                    try await vpnController.restart()
                } else if connectionState == .connected || connectionState == .connecting {
                    connectionState = .disconnecting
                    await vpnController.stop()
                }
            } catch is CancellationError {
                return
            } catch {
                guard selectedLocationID == location.id else { return }
                connectionState = await vpnController.currentState()
                presentedNotice = YPtunNotice(
                    title: "Не удалось переключить локацию",
                    message: "Выбор сохранён, но VPN не перезапустился: \(error.localizedDescription)"
                )
            }
        }
    }

    func waitForPendingSelection() async {
        await selectionTask?.value
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
        guard connectionState != .disconnecting else { return }
        connectionTask?.cancel()
        if connectionState == .connected || connectionState == .connecting {
            connectionState = .disconnecting
            connectionTask = Task { [vpnController] in
                await vpnController.stop()
            }
            return
        }

        connectionState = .connecting
        connectionTask = Task { [bootstrapper, vpnController] in
            do {
                try await bootstrapper.prepareTunnelRequest(locationID: selectedLocation.id)
                try Task.checkCancellation()
                try await vpnController.start()
            } catch is CancellationError {
                connectionState = .disconnected
            } catch {
                connectionState = .disconnected
                presentedNotice = YPtunNotice(
                    title: "Не удалось подключиться",
                    message: error.localizedDescription
                )
            }
        }
    }

    public func importLocations(from text: String) async -> Bool {
        let restartAfterImport = isTunnelActive
        do {
            let snapshot = try await bootstrapper.importLocations(from: text)
            locations = snapshot.locations
            selectedLocationID = snapshot.activeLocationID
                ?? snapshot.locations.first(where: \.isComplete)?.id
            if restartAfterImport {
                await restartTunnelAfterConfigurationChange()
            }
            return true
        } catch {
            presentedNotice = YPtunNotice(
                title: "Не удалось импортировать",
                message: error.localizedDescription
            )
            return false
        }
    }

    public func locationText(id: String?) async -> String {
        guard let id else {
            return "{\n  \"name\": \"\",\n  \"engine\": \"standard\",\n  \"proxy\": {\n    \"type\": \"vless\",\n    \"server\": \"\",\n    \"server_port\": 443,\n    \"uuid\": \"\"\n  }\n}"
        }
        do {
            return try await bootstrapper.locationText(id: id)
        } catch {
            presentedNotice = YPtunNotice(
                title: "Не удалось открыть локацию",
                message: error.localizedDescription
            )
            return "{}"
        }
    }

    public func saveLocation(text: String, id: String?) async -> Bool {
        let restartAfterSave = isTunnelActive && (id == nil || id == selectedLocationID)
        do {
            let snapshot = try await bootstrapper.saveLocationText(text, id: id)
            locations = snapshot.locations
            selectedLocationID = snapshot.activeLocationID
            if restartAfterSave {
                await restartTunnelAfterConfigurationChange()
            }
            return true
        } catch {
            presentedNotice = YPtunNotice(
                title: "Не удалось сохранить локацию",
                message: error.localizedDescription
            )
            return false
        }
    }

    public func deletePendingLocation() async {
        guard let location = locationPendingDeletion else { return }
        let restartAfterDelete = isTunnelActive && location.id == selectedLocationID
        do {
            let snapshot = try await bootstrapper.deleteLocation(id: location.id)
            locations = snapshot.locations
            selectedLocationID = snapshot.activeLocationID
            locationPendingDeletion = nil
            if restartAfterDelete {
                await restartTunnelAfterConfigurationChange()
            }
        } catch {
            locationPendingDeletion = nil
            presentedNotice = YPtunNotice(
                title: "Не удалось удалить локацию",
                message: error.localizedDescription
            )
        }
    }

    private var isTunnelActive: Bool {
        connectionState == .connected || connectionState == .connecting
    }

    private func restartTunnelAfterConfigurationChange() async {
        guard let selectedLocation, selectedLocation.isComplete else {
            connectionState = .disconnecting
            await vpnController.stop()
            return
        }
        connectionState = .connecting
        do {
            try await vpnController.restart()
        } catch is CancellationError {
            return
        } catch {
            connectionState = await vpnController.currentState()
            presentedNotice = YPtunNotice(
                title: "Не удалось применить изменения",
                message: "Данные сохранены, но VPN не перезапустился: \(error.localizedDescription)"
            )
        }
    }
}
