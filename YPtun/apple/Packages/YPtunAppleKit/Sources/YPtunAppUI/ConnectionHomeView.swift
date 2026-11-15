import Foundation
import SwiftUI
import YPtunShared

public struct ConnectionHomeView: View {
    @ObservedObject private var model: YPtunApplicationModel
    @State private var isSettingsPresented = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                // Connection Timer
                ConnectionTimer(state: model.connectionState)
                    .padding(.top, 14)

                // Circular Power Button with "Авто" next to it
                ZStack {
                    AndroidStyleStartButton(
                        state: model.connectionState,
                        requiresSetup: model.selectedLocation == nil,
                        isLoading: model.isBootstrapping,
                        action: model.requestPrimaryAction
                    )
                    .disabled(model.isBootstrapping || model.isAutoConnecting)

                    if model.uiBehavior.showAutoButton && model.locations.contains(where: \.isComplete) {
                        Button(action: model.autoConnectFastest) {
                            Group {
                                if model.isAutoConnecting {
                                    ProgressView()
                                        .tint(YPtunColor.accent)
                                } else {
                                    Text("Авто")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(YPtunColor.accent)
                                }
                            }
                            .frame(width: 54, height: 54)
                            .background(YPtunColor.cardSurfaceHigh, in: Circle())
                            .overlay(
                                Circle()
                                    .stroke(YPtunColor.accent.opacity(0.3), lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(model.isAutoConnecting || model.isBootstrapping)
                        .offset(x: 95)
                        .accessibilityLabel("Подключиться к лучшей локации")
                    }
                }
                .padding(.vertical, 8)

                // Subtitle / Session Overview Link
                if model.connectionState == .connected && !model.uiBehavior.hideSessionButton {
                    NavigationLink {
                        SessionOverviewView(model: model)
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "globe")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.accent)
                            Text("Информация о сессии")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: 36)
                        .background(YPtunColor.cardSurface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }

                if let message = model.startupMessage {
                    Label(message, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(YPtunColor.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: 16))
                }

                // "Обновить подписки" Full-Width Button
                if locationSections.contains(where: \.isSubscription) {
                    Button {
                        Task { await model.refreshSubscriptions() }
                    } label: {
                        HStack(spacing: 10) {
                            if model.isRefreshingSubscriptions {
                                ProgressView()
                                    .tint(YPtunColor.accent)
                            } else {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundStyle(YPtunColor.accent)
                            }
                            Text("Обновить подписки")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                        }
                        .padding(.horizontal, 18)
                        .frame(minHeight: 48)
                        .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: 18))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.isRefreshingSubscriptions)
                }

                // Section Title: "КОНФИГУРАЦИИ"
                HStack {
                    Text("КОНФИГУРАЦИИ")
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1.5)
                        .foregroundStyle(YPtunColor.textSecondary)
                    Spacer()
                    Text("\(model.locations.count)")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(YPtunColor.textMuted)
                }
                .padding(.top, 8)
                .padding(.horizontal, 4)

                // Locations & Subscriptions Content
                if model.locations.isEmpty && !model.isBootstrapping {
                    emptyState
                } else {
                    ForEach(locationSections) { section in
                        if section.isSubscription {
                            SubscriptionCardView(
                                title: section.title,
                                subscriptionID: section.locations.first?.subscriptionID,
                                locations: section.locations,
                                selectedLocationID: model.selectedLocationID,
                                savedPings: model.uiBehavior.savedPings,
                                onSelect: { model.select($0) },
                                onEdit: {
                                    model.locationEditorRequest = LocationEditorRequest(locationID: $0.id)
                                },
                                onDelete: { model.requestLocationDeletion($0) },
                                onRefreshSubscription: { url in
                                    Task { _ = try? await model.importLocations(from: url) }
                                },
                                onPingAll: { ids in
                                    // Pings all locations in subscription
                                }
                            )
                        } else {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    Text("Мои конфигурации")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(YPtunColor.textPrimary)
                                    Spacer()
                                }
                                .padding(.horizontal, 4)

                                ForEach(section.locations) { location in
                                    LocationRowView(
                                        location: location,
                                        isSelected: location.id == model.selectedLocationID,
                                        savedPing: model.uiBehavior.savedPings[location.id],
                                        onSelect: { model.select(location) },
                                        onEdit: {
                                            model.locationEditorRequest = LocationEditorRequest(locationID: location.id)
                                        },
                                        onDelete: { model.requestLocationDeletion(location) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigation) {
                Button {
                    isSettingsPresented = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(YPtunColor.textPrimary)
                }
            }
            ToolbarItem(placement: .principal) {
                Text("YPtun")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(YPtunColor.textPrimary)
            }
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    model.isAddLocationPresented = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(YPtunColor.textPrimary)
                }
                homeMenu
            }
        }
        .sheet(isPresented: $isSettingsPresented) {
            NavigationStack {
                SettingsOverviewView(model: model)
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

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image("CatTile")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: 54, height: 54)
                .foregroundStyle(YPtunColor.textSecondary)
            Text("Локаций пока нет")
                .font(.headline)
                .foregroundStyle(YPtunColor.textPrimary)
            Text("Импортируйте подписку, ссылку или конфигурацию, чтобы начать.")
                .font(.subheadline)
                .foregroundStyle(YPtunColor.textSecondary)
                .multilineTextAlignment(.center)
            Button("Добавить") { model.isAddLocationPresented = true }
                .tint(YPtunColor.accent)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
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
            NavigationLink {
                TunnelLogView()
            } label: {
                Label("Логи подключения", systemImage: "text.alignleft")
            }
            Divider()
            if let location = model.selectedLocation {
                Button {
                    model.locationEditorRequest = LocationEditorRequest(locationID: location.id)
                } label: {
                    Label("Редактировать выбранную", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    model.requestLocationDeletion(location)
                } label: {
                    Label("Удалить выбранную", systemImage: "trash")
                }
                Divider()
            }
            if model.locations.contains(where: \.isFromSubscription) {
                Button(role: .destructive) {
                    model.requestBulkDeletion(.subscriptions)
                } label: {
                    Label("Удалить все подписки", systemImage: "trash")
                }
            }
            if model.locations.contains(where: { !$0.isFromSubscription }) {
                Button(role: .destructive) {
                    model.requestBulkDeletion(.customLocations)
                } label: {
                    Label("Удалить свои конфигурации", systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(YPtunColor.textPrimary)
                .frame(width: 32, height: 32)
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

private struct ConnectionTimer: View {
    let state: VPNConnectionState
    @State private var connectedAt: Date?

    var body: some View {
        VStack(spacing: 4) {
            Text("Время подключения")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(YPtunColor.textSecondary)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(formattedElapsed(at: context.date))
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .tracking(2)
                    .foregroundStyle(YPtunColor.textPrimary)
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
                // Outer Pulse Ring
                Circle()
                    .fill(ringColor)
                    .frame(width: 140, height: 140)

                // Main Circular Button
                Circle()
                    .fill(buttonColor)
                    .frame(width: 96, height: 96)
                    .shadow(color: isActive ? YPtunColor.accentGlow : Color.clear, radius: 16)

                if isLoading || state == .connecting || state == .disconnecting {
                    ProgressView()
                        .controlSize(.large)
                        .tint(contentColor)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: 38, weight: .bold))
                        .foregroundStyle(contentColor)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityTitle)
    }

    private var isActive: Bool { state == .connected }
    private var ringColor: Color { isActive ? YPtunColor.ringActive : YPtunColor.ringIdle }
    private var buttonColor: Color { isActive ? YPtunColor.accent : YPtunColor.cardSurfaceHigh }
    private var contentColor: Color { isActive ? Color.black : YPtunColor.textPrimary }
    private var symbol: String { isActive ? "pause.fill" : requiresSetup ? "plus" : "power" }

    private var accessibilityTitle: String {
        if requiresSetup { return "Добавить локацию" }
        return isActive ? "Отключить" : "Подключить"
    }
}
