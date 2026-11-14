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
    @Published public var bulkDeletionPending: BulkLocationDeletion?
    @Published public private(set) var isRefreshingSubscriptions = false

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

    public func settingsText(_ file: AppleSettingsFile) async -> String? {
        do {
            return try await bootstrapper.settingsText(file)
        } catch {
            presentedNotice = YPtunNotice(
                title: "Не удалось открыть настройки",
                message: error.localizedDescription
            )
            return nil
        }
    }

    public func saveSettingsText(_ text: String, file: AppleSettingsFile) async -> Bool {
        let restartAfterSave = isTunnelActive
        do {
            try await bootstrapper.saveSettingsText(text, file: file)
            if restartAfterSave {
                await restartTunnelAfterConfigurationChange()
            }
            return true
        } catch {
            presentedNotice = YPtunNotice(
                title: "Не удалось сохранить настройки",
                message: error.localizedDescription
            )
            return false
        }
    }

    public func performPendingBulkDeletion() async {
        guard let deletion = bulkDeletionPending else { return }
        bulkDeletionPending = nil
        let ids = locations.filter {
            switch deletion {
            case .subscriptions: $0.isFromSubscription
            case .customLocations: !$0.isFromSubscription
            }
        }.map(\.id)
        guard !ids.isEmpty else { return }

        let restartAfterDelete = isTunnelActive && ids.contains(selectedLocationID ?? "")
        var latestSnapshot: AppleApplicationSnapshot?
        do {
            for id in ids {
                latestSnapshot = try await bootstrapper.deleteLocation(id: id)
            }
            if let latestSnapshot {
                locations = latestSnapshot.locations
                selectedLocationID = latestSnapshot.activeLocationID
            }
            if restartAfterDelete {
                await restartTunnelAfterConfigurationChange()
            }
        } catch {
            if let snapshot = try? await bootstrapper.load() {
                locations = snapshot.locations
                selectedLocationID = snapshot.activeLocationID
            }
            presentedNotice = YPtunNotice(
                title: "Удалено не всё",
                message: "Часть конфигураций не удалось удалить: \(error.localizedDescription)"
            )
        }
    }

    public func refreshSubscriptions() async {
        guard !isRefreshingSubscriptions else { return }
        let urls = locations.compactMap(\.subscriptionID).reduce(into: [String]()) { result, url in
            if !result.contains(url) { result.append(url) }
        }
        guard !urls.isEmpty else { return }

        isRefreshingSubscriptions = true
        defer { isRefreshingSubscriptions = false }
        let restartAfterRefresh = isTunnelActive
        var refreshed = 0
        var latestSnapshot: AppleApplicationSnapshot?
        for url in urls {
            do {
                latestSnapshot = try await bootstrapper.importLocations(from: url)
                refreshed += 1
            } catch {
                continue
            }
        }
        if let latestSnapshot {
            locations = latestSnapshot.locations
            selectedLocationID = latestSnapshot.activeLocationID
                ?? latestSnapshot.locations.first(where: \.isComplete)?.id
            if restartAfterRefresh {
                await restartTunnelAfterConfigurationChange()
            }
        }
        if refreshed != urls.count {
            presentedNotice = YPtunNotice(
                title: refreshed == 0 ? "Не удалось обновить подписки" : "Обновлено не всё",
                message: "Обновлено \(refreshed) из \(urls.count) подписок. Проверьте сеть и ссылки."
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

public enum BulkLocationDeletion: String, Identifiable, Sendable {
    case subscriptions
    case customLocations

    public var id: Self { self }
}
