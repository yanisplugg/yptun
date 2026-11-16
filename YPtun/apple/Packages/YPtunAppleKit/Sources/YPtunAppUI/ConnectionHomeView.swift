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
        ZStack(alignment: .bottom) {
            ScrollView {
                LazyVStack(spacing: 14) {
                    // Connection Timer (Android Compose ConnectionTimer style)
                    ConnectionTimer(state: model.connectionState)
                        .padding(.top, 10)

                    // Main Connect Button with Satellite "Авто" button (Android StartButton style)
                    ZStack {
                        AndroidStyleStartButton(
                            state: model.connectionState,
                            requiresSetup: model.selectedLocation == nil,
                            isLoading: model.isBootstrapping,
                            action: model.requestPrimaryAction
                        )
                        .disabled(model.isBootstrapping || model.isAutoConnecting)

                        if model.uiBehavior.showAutoButton && model.locations.contains(where: \.isComplete) {
                            Button {
                                model.autoConnectFastest()
                            } label: {
                                Group {
                                    if model.isAutoConnecting {
                                        ProgressView()
                                            .tint(YPtunColor.accent)
                                    } else {
                                        Text("Авто")
                                            .font(.system(size: 13, weight: .bold))
                                            .foregroundStyle(YPtunColor.accent)
                                    }
                                }
                                .frame(width: 56, height: 56)
                                .background(YPtunColor.cardSurfaceHigh, in: Circle())
                            }
                            .buttonStyle(.plain)
                            .disabled(model.isAutoConnecting || model.isBootstrapping)
                            .offset(x: 100)
                            .accessibilityLabel("Авто — выбор быстрейшего")
                        }
                    }
                    .padding(.vertical, 6)

                    // Session Overview Pill (if connected, matching Android SessionSheet link)
                    if model.connectionState == .connected && !model.uiBehavior.hideSessionButton {
                        NavigationLink {
                            SessionOverviewView(model: model)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "globe")
                                    .font(.system(size: 14))
                                    .foregroundStyle(YPtunColor.accent)
                                Text("Сессия")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                            }
                            .padding(.horizontal, 18)
                            .frame(minHeight: 36)
                            .background(YPtunColor.cardSurfaceHigh, in: Capsule())
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

                    // "Обновить подписки" Full-Width Button (Android SubscriptionsRefreshRow)
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
                            .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: YPtunRadius.card))
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
                    .padding(.top, 4)
                    .padding(.horizontal, 4)

                    // Locations & Subscriptions Content
                    if model.locations.isEmpty && !model.isBootstrapping {
                        emptyState
                    } else {
                        ForEach(locationSections) { section in
                            if section.isSubscription {
                                SubscriptionCardView(
                                    title: section.title,
                                    subscriptionID: section.subscriptionURL,
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
                                        model.autoConnectFastest(onlyIDs: ids)
                                    },
                                    onDeleteSubscription: {
                                        let ids = section.locations.map(\.id)
                                        Task { await model.deleteSubscriptionGroup(ids: ids) }
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
                .padding(.bottom, 96) // Space so floating nav bar does not cover items
            }

            // Floating Navigation Bar matching Screenshot 7 & Android Compose
            floatingNavigationBar
                .padding(.bottom, 12)
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
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
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

    // Material 3 Floating Navigation Bar matching Screenshot 7
    private var floatingNavigationBar: some View {
        HStack(spacing: 20) {
            // Tab 1: Подключение (Active)
            Button {} label: {
                VStack(spacing: 4) {
                    ZStack {
                        Capsule()
                            .fill(YPtunColor.accent.opacity(0.22))
                            .frame(width: 64, height: 32)
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(YPtunColor.accent)
                    }
                    Text("Подключение")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(YPtunColor.accent)
                }
            }
            .buttonStyle(.plain)

            // Tab 2: Настройки (Tapping presents Settings Sheet)
            Button {
                isSettingsPresented = true
            } label: {
                VStack(spacing: 4) {
                    ZStack {
                        Capsule()
                            .fill(Color.clear)
                            .frame(width: 64, height: 32)
                        Image(systemName: "gearshape.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(YPtunColor.textSecondary)
                    }
                    Text("Настройки")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(YPtunColor.textSecondary)
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 8)
        .background(
            Color(hex: 0x161719).opacity(0.96),
            in: Capsule()
        )
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.6), radius: 16, y: 6)
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
            let isSub = location.isFromSubscription || location.subscriptionID != nil
            let key: String
            let subURL: String?
            if isSub {
                let subName = location.subscriptionName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let urlStr = location.subscriptionID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let combined = [subName, urlStr].filter { !$0.isEmpty }.joined(separator: "|")
                key = combined.isEmpty ? (location.customGroup ?? "Подписка") : combined
                subURL = urlStr.isEmpty ? nil : urlStr
            } else {
                key = "__custom__"
                subURL = nil
            }

            if let index = sections.firstIndex(where: { $0.id == key }) {
                if !sections[index].locations.contains(where: { $0.id == location.id }) {
                    sections[index].locations.append(location)
                }
            } else {
                sections.append(
                    LocationSection(
                        id: key,
                        title: sectionTitle(for: location),
                        isSubscription: isSub,
                        subscriptionURL: subURL,
                        locations: [location]
                    )
                )
            }
        }
        return sections
    }

    private func sectionTitle(for location: LocationListItem) -> String {
        guard location.isFromSubscription || location.subscriptionID != nil else {
            return "Мои конфигурации"
        }
        if let name = location.subscriptionName, !name.isEmpty { return name }
        if let subID = location.subscriptionID, let host = URL(string: subID)?.host, !host.isEmpty {
            return host
        }
        return "Подписка"
    }

    private var homeMenu: some View {
        Menu {
            NavigationLink {
                AppearanceSettingsView(model: model)
            } label: {
                Label("Вид списка", systemImage: "square.grid.2x2")
            }

            NavigationLink {
                PingSettingsView(model: model)
            } label: {
                Label("Настройки пинга", systemImage: "bolt.horizontal")
            }

            NavigationLink {
                TunnelLogView()
            } label: {
                Label("Логи подключения", systemImage: "text.alignleft")
            }

            Divider()

            Text("ОЧИСТКА")
                .font(.caption2)
                .foregroundStyle(YPtunColor.textMuted)

            Button {
                Task { await model.deleteUnreachableLocations() }
            } label: {
                Label("Удалить недоступные", systemImage: "wifi.slash")
            }

            Button {
                Task { await model.deleteDuplicateLocations() }
            } label: {
                Label("Удалить дубликаты", systemImage: "doc.on.doc")
            }

            if model.locations.contains(where: \.isFromSubscription) {
                Button(role: .destructive) {
                    model.requestBulkDeletion(.subscriptions)
                } label: {
                    Label("Удалить все подписки", systemImage: "folder.badge.minus")
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
    let subscriptionURL: String?
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
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
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
                // Outer Ring matching Android StartButton size 132dp
                Circle()
                    .fill(ringColor)
                    .frame(width: 132, height: 132)

                // Inner Main Button matching Android StartButton size 88dp
                Circle()
                    .fill(buttonColor)
                    .frame(width: 88, height: 88)
                    .shadow(color: isActive ? YPtunColor.accentGlow : Color.clear, radius: 14)

                if isLoading || state == .connecting || state == .disconnecting {
                    ProgressView()
                        .controlSize(.large)
                        .tint(contentColor)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: 42, weight: .semibold))
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
    private var buttonColor: Color { isActive ? YPtunColor.accent : YPtunColor.cardSurfaceHighest }
    private var contentColor: Color { isActive ? Color.white : YPtunColor.textSecondary }
    private var symbol: String { isActive ? "pause.fill" : requiresSetup ? "plus" : "power" }

    private var accessibilityTitle: String {
        if requiresSetup { return "Добавить локацию" }
        return isActive ? "Отключить" : "Подключить"
    }
}
