import Foundation
import SwiftUI
import YPtunShared

public struct ConnectionHomeView: View {
    @ObservedObject private var model: YPtunApplicationModel

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ConnectionTimer(state: model.connectionState)
                    .padding(.top, 12)

                AndroidStyleStartButton(
                    state: model.connectionState,
                    requiresSetup: model.selectedLocation == nil,
                    isLoading: model.isBootstrapping,
                    action: model.requestPrimaryAction
                )
                .disabled(model.isBootstrapping)

                Text(model.primaryActionTitle)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)

                if model.connectionState == .connected {
                    NavigationLink {
                        SessionOverviewView(model: model)
                    } label: {
                        Label("Информация о сессии", systemImage: "globe")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 18)
                            .frame(minHeight: 42)
                            .background(.thinMaterial, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                if let message = model.startupMessage {
                    Label(message, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
                        .accessibilityLabel(message)
                }

                locationsContent
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(Color.secondary.opacity(0.04))
        .navigationTitle("YPtun")
        .ypInlineNavigationTitle()
        .toolbar {
            ToolbarItem(placement: .navigation) {
                NavigationLink {
                    TunnelLogView()
                } label: {
                    Label("Логи", systemImage: "clock.arrow.circlepath")
                }
            }
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    model.isAddLocationPresented = true
                } label: {
                    Label("Добавить", systemImage: "plus")
                }
                if !model.locations.isEmpty {
                    homeMenu
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
        .alert(item: $model.bulkDeletionPending) { deletion in
            Alert(
                title: Text(bulkDeletionTitle(deletion)),
                message: Text("Это действие нельзя отменить."),
                primaryButton: .destructive(Text("Удалить")) {
                    Task { await model.performPendingBulkDeletion() }
                },
                secondaryButton: .cancel(Text("Отмена"))
            )
        }
    }

    @ViewBuilder
    private var locationsContent: some View {
        HStack {
            Text("Локации")
                .font(.title3.weight(.semibold))
            Spacer()
            Text("\(model.locations.count)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 12)

        if locationSections.contains(where: \.isSubscription) {
            Button {
                Task { await model.refreshSubscriptions() }
            } label: {
                HStack(spacing: 10) {
                    if model.isRefreshingSubscriptions {
                        ProgressView()
                    } else {
                        Image(systemName: "arrow.clockwise")
                            .foregroundStyle(.tint)
                    }
                    Text("Обновить подписки")
                        .font(.subheadline.weight(.medium))
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 48)
                .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 20))
            }
            .buttonStyle(.plain)
            .disabled(model.isRefreshingSubscriptions)
        }

        if model.locations.isEmpty && !model.isBootstrapping {
            VStack(spacing: 12) {
                Image("CatTile")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 54, height: 54)
                    .foregroundStyle(.secondary)
                Text("Локаций пока нет")
                    .font(.headline)
                Text("Импортируйте подписку, ссылку или конфигурацию, чтобы начать.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Добавить") { model.isAddLocationPresented = true }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        } else {
            ForEach(locationSections) { section in
                LocationSectionHeader(section: section)
                ForEach(section.locations) { location in
                    LocationCard(
                        location: location,
                        isSelected: location.id == model.selectedLocationID,
                        onSelect: { model.select(location) },
                        onEdit: {
                            model.locationEditorRequest = LocationEditorRequest(locationID: location.id)
                        },
                        onDelete: { model.locationPendingDeletion = location }
                    )
                }
            }
        }
    }

    private var locationSections: [LocationSection] {
        var sections: [LocationSection] = []
        for location in model.locations {
            let key = location.subscriptionID ?? "__custom__"
            if let index = sections.firstIndex(where: { $0.id == key }) {
                sections[index].locations.append(location)
            } else {
                sections.append(
                    LocationSection(
                        id: key,
                        title: sectionTitle(for: location),
                        isSubscription: location.subscriptionID != nil,
                        locations: [location]
                    )
                )
            }
        }
        return sections
    }

    private func sectionTitle(for location: LocationListItem) -> String {
        guard let subscriptionID = location.subscriptionID else { return "Мои конфигурации" }
        if let name = location.subscriptionName { return name }
        return URL(string: subscriptionID)?.host ?? "Подписка"
    }

    private var homeMenu: some View {
        Menu {
            if let location = model.selectedLocation {
                Button {
                    model.locationEditorRequest = LocationEditorRequest(locationID: location.id)
                } label: {
                    Label("Редактировать", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    model.locationPendingDeletion = location
                } label: {
                    Label("Удалить", systemImage: "trash")
                }
                Divider()
            }
            if model.locations.contains(where: \.isFromSubscription) {
                Button(role: .destructive) {
                    model.bulkDeletionPending = .subscriptions
                } label: {
                    Label("Удалить все подписки", systemImage: "trash")
                }
            }
            if model.locations.contains(where: { !$0.isFromSubscription }) {
                Button(role: .destructive) {
                    model.bulkDeletionPending = .customLocations
                } label: {
                    Label("Удалить свои конфигурации", systemImage: "trash")
                }
            }
        } label: {
            Label("Ещё", systemImage: "ellipsis")
        }
    }

    private func bulkDeletionTitle(_ deletion: BulkLocationDeletion) -> String {
        switch deletion {
        case .subscriptions: "Удалить все подписки?"
        case .customLocations: "Удалить свои конфигурации?"
        }
    }
}

private struct LocationSection: Identifiable {
    let id: String
    let title: String
    let isSubscription: Bool
    var locations: [LocationListItem]
}

private struct LocationSectionHeader: View {
    let section: LocationSection

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: section.isSubscription ? "dot.radiowaves.left.and.right" : "slider.horizontal.3")
                .foregroundStyle(.tint)
            Text(section.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
            Spacer()
            Text("\(section.locations.count)")
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .background(Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 20))
    }
}

private struct ConnectionTimer: View {
    let state: VPNConnectionState
    @State private var connectedAt: Date?

    var body: some View {
        VStack(spacing: 3) {
            Text("Время подключения")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(formattedElapsed(at: context.date))
                    .font(.system(size: 32, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .tracking(2)
            }
        }
        .task(id: state) {
            guard state == .connected else {
                connectedAt = nil
                return
            }
            let stored = try? AppleTunnelStore.readText(AppleTunnelStore.connectedAtFileName)
            connectedAt = stored
                .flatMap { TimeInterval($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .map { Date(timeIntervalSince1970: $0) }
                ?? Date()
        }
        .accessibilityElement(children: .combine)
    }

    private func formattedElapsed(at date: Date) -> String {
        guard state == .connected, let connectedAt else { return "00:00:00" }
        let elapsed = max(0, Int(date.timeIntervalSince(connectedAt)))
        return String(
            format: "%02d:%02d:%02d",
            elapsed / 3_600,
            (elapsed % 3_600) / 60,
            elapsed % 60
        )
    }
}

private struct AndroidStyleStartButton: View {
    let state: VPNConnectionState
    let requiresSetup: Bool
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(ringColor)
                    .frame(width: 132, height: 132)
                Circle()
                    .fill(buttonColor)
                    .frame(width: 88, height: 88)
                if isLoading || state == .connecting || state == .disconnecting {
                    ProgressView()
                        .controlSize(.large)
                        .tint(contentColor)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: 38, weight: .medium))
                        .foregroundStyle(contentColor)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityTitle)
    }

    private var isActive: Bool { state == .connected }
    private var ringColor: Color { isActive ? Color.accentColor.opacity(0.22) : Color.secondary.opacity(0.10) }
    private var buttonColor: Color { isActive ? .accentColor : Color.secondary.opacity(0.14) }
    private var contentColor: Color { isActive ? .white : .secondary }
    private var symbol: String { isActive ? "pause.fill" : requiresSetup ? "plus" : "power" }

    private var accessibilityTitle: String {
        if requiresSetup { return "Добавить локацию" }
        return isActive ? "Отключить" : "Подключить"
    }
}

private struct LocationCard: View {
    let location: LocationListItem
    let isSelected: Bool
    let onSelect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onSelect) {
                HStack(spacing: 12) {
                    if let icon = location.icon {
                        Text(icon)
                            .font(.title3)
                            .frame(width: 28)
                    } else {
                        Image(systemName: location.isFromSubscription ? "dot.radiowaves.left.and.right" : "network")
                            .font(.title3)
                            .foregroundStyle(location.isComplete ? Color.accentColor : .secondary)
                            .frame(width: 28)
                    }

                    VStack(alignment: .leading, spacing: 3) {
                        Text(location.name)
                            .font(.body.weight(.medium))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                        if !location.detail.isEmpty {
                            Text(location.detail)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    if location.needsUserAction {
                        Image(systemName: "person.crop.circle.badge.questionmark")
                            .foregroundStyle(.orange)
                            .accessibilityLabel("Требуется действие")
                    } else if location.isLocked {
                        Image(systemName: "lock.fill")
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("Защищённая конфигурация")
                    } else if location.isPinned {
                        Image(systemName: "pin.fill")
                            .foregroundStyle(.orange)
                            .accessibilityLabel("Закреплено")
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                Button(action: onEdit) {
                    Label("Редактировать", systemImage: "pencil")
                }
                Button(role: .destructive, action: onDelete) {
                    Label("Удалить", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: 40)
            }

            Button(action: onSelect) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : .secondary.opacity(0.45))
            }
            .buttonStyle(.plain)
        }
        .frame(minHeight: 60)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(cardBackground, in: RoundedRectangle(cornerRadius: 24))
        .overlay {
            RoundedRectangle(cornerRadius: 24)
                .stroke(isSelected ? Color.accentColor : .clear, lineWidth: 1.5)
        }
        .opacity(location.isComplete ? 1 : 0.58)
        .contextMenu {
            Button(action: onEdit) {
                Label("Редактировать", systemImage: "pencil")
            }
            Button(role: .destructive, action: onDelete) {
                Label("Удалить", systemImage: "trash")
            }
        }
    }

    private var cardBackground: Color {
        isSelected ? Color.accentColor.opacity(0.14) : Color.secondary.opacity(0.09)
    }
}

private extension View {
    @ViewBuilder
    func ypInlineNavigationTitle() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}
