import SwiftUI
import YPtunShared

public struct ConnectionHomeView: View {
    @Bindable private var model: YPtunApplicationModel

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        List {
            Section {
                ConnectionStatusView(state: model.connectionState, isLoading: model.isBootstrapping)
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)

                if let message = model.startupMessage {
                    Label(message, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel(message)
                }

                ActiveLocationRow(location: model.selectedLocation)

                Button(action: model.requestPrimaryAction) {
                    Label(model.primaryActionTitle, systemImage: model.primaryActionSymbol)
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isBootstrapping)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }

            Section("Локации") {
                if model.locations.isEmpty && !model.isBootstrapping {
                    ContentUnavailableView {
                        VStack(spacing: 10) {
                            Image("CatTile")
                                .renderingMode(.template)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 54, height: 54)
                                .foregroundStyle(.secondary)
                            Text("Локаций пока нет")
                                .font(.headline)
                        }
                    } description: {
                        Text("Импортируйте подписку, ссылку или конфигурацию, чтобы начать.")
                    } actions: {
                        Button("Добавить") { model.isAddLocationPresented = true }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    ForEach(model.locations) { location in
                        Button {
                            model.select(location)
                        } label: {
                            LocationRow(
                                location: location,
                                isSelected: location.id == model.selectedLocationID
                            )
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button {
                                model.locationEditorRequest = LocationEditorRequest(
                                    locationID: location.id
                                )
                            } label: {
                                Label("Редактировать", systemImage: "pencil")
                            }
                            Button(role: .destructive) {
                                model.locationPendingDeletion = location
                            } label: {
                                Label("Удалить", systemImage: "trash")
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.inset)
        .navigationTitle("YPtun")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                NavigationLink {
                    TunnelLogView()
                } label: {
                    Label("Логи", systemImage: "text.alignleft")
                }
                Button {
                    model.isAddLocationPresented = true
                } label: {
                    Label("Добавить", systemImage: "plus")
                }
            }
        }
        .sheet(isPresented: $model.isAddLocationPresented) {
            AddLocationView(model: model)
        }
        .sheet(item: $model.locationEditorRequest) { request in
            RawLocationEditorView(model: model, request: request)
        }
        .alert(item: $model.presentedNotice) { notice in
            Alert(title: Text(notice.title), message: Text(notice.message))
        }
        .alert(
            "Удалить локацию?",
            isPresented: Binding(
                get: { model.locationPendingDeletion != nil },
                set: { if !$0 { model.locationPendingDeletion = nil } }
            ),
            presenting: model.locationPendingDeletion
        ) { _ in
            Button("Удалить", role: .destructive) {
                Task { await model.deletePendingLocation() }
            }
            Button("Отмена", role: .cancel) {
                model.locationPendingDeletion = nil
            }
        } message: { location in
            Text("«\(location.name)» будет удалена с этого устройства.")
        }
    }
}

private struct ConnectionStatusView: View {
    let state: VPNConnectionState
    let isLoading: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .fill(.thinMaterial)
                Circle()
                    .strokeBorder(statusColor.opacity(0.22), lineWidth: 1)
                Image(systemName: statusSymbol)
                    .font(.system(.largeTitle, design: .rounded, weight: .semibold))
                    .foregroundStyle(statusColor)
                    .symbolEffect(
                        .pulse,
                        options: .repeating,
                        isActive: !reduceMotion && (isLoading || state == .connecting)
                    )
            }
            .frame(width: 92, height: 92)
            .accessibilityHidden(true)

            VStack(spacing: 4) {
                Text(statusTitle)
                    .font(.title2.weight(.semibold))
                Text(statusDetail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(statusTitle)
        .accessibilityValue(statusDetail)
    }

    private var statusTitle: String {
        if isLoading { return "Загрузка данных" }
        return switch state {
        case .disconnected: "Не подключено"
        case .connecting: "Подключение"
        case .connected: "Защищено"
        case .disconnecting: "Отключение"
        }
    }

    private var statusDetail: String {
        if isLoading { return "Проверяем сохранённые локации" }
        return switch state {
        case .disconnected: "Трафик идёт через обычное подключение"
        case .connecting: "Запускаем защищённый маршрут"
        case .connected: "VPN работает"
        case .disconnecting: "Завершаем текущую сессию"
        }
    }

    private var statusSymbol: String {
        if isLoading { return "arrow.trianglehead.2.clockwise.rotate.90" }
        return switch state {
        case .disconnected: "shield.slash"
        case .connecting: "shield.lefthalf.filled"
        case .connected: "shield.checkered"
        case .disconnecting: "shield.slash"
        }
    }

    private var statusColor: Color {
        switch state {
        case .disconnected, .disconnecting: .secondary
        case .connecting: .orange
        case .connected: .green
        }
    }
}

private struct ActiveLocationRow: View {
    let location: LocationListItem?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "location.fill")
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(location?.name ?? "Локация не выбрана")
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                Text(location?.detail.nonEmpty ?? "Выберите локацию ниже или добавьте новую")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Текущая локация")
        .accessibilityValue(location?.name ?? "Не выбрана")
    }
}

private struct LocationRow: View {
    let location: LocationListItem
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: location.isFromSubscription ? "dot.radiowaves.left.and.right" : "network")
                .foregroundStyle(location.isComplete ? Color.accentColor : .secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(location.name)
                    .foregroundStyle(.primary)
                if !location.detail.isEmpty {
                    Text(location.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            if location.needsUserAction {
                Image(systemName: "person.crop.circle.badge.questionmark")
                    .foregroundStyle(.orange)
                    .accessibilityLabel("Требуется действие")
            } else if isSelected {
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.tint)
                    .accessibilityLabel("Выбрано")
            }
        }
        .contentShape(.rect)
        .opacity(location.isComplete ? 1 : 0.58)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
