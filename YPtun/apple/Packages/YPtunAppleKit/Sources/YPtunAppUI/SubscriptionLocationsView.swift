import SwiftUI
import YPtunShared

struct SubscriptionLocationsView: View {
    @ObservedObject var model: YPtunApplicationModel

    private var locations: [LocationListItem] {
        model.locations.filter(\.isFromSubscription)
    }

    var body: some View {
        List(locations) { location in
            Button {
                model.select(location)
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(location.name)
                            .foregroundStyle(.primary)
                        if !location.detail.isEmpty {
                            Text(location.detail)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if location.id == model.selectedLocationID {
                        Image(systemName: "checkmark")
                            .foregroundStyle(.tint)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .overlay {
            if locations.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("Подписок пока нет")
                        .font(.headline)
                    Text("Добавьте URL подписки через экран импорта.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("Подписки")
        .toolbar {
            Button {
                model.isAddLocationPresented = true
            } label: {
                Label("Добавить", systemImage: "plus")
            }
        }
        .sheet(isPresented: $model.isAddLocationPresented) {
            AddLocationView(model: model)
        }
        .tint(YPtunColor.accent)
        .preferredColorScheme(.dark)
    }
}

struct SessionOverviewView: View {
    @ObservedObject var model: YPtunApplicationModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SettingsCaption("Текущая сессия")
                SettingsCard {
                    HStack(spacing: 14) {
                        Image(systemName: statusIcon)
                            .font(.system(size: 22))
                            .foregroundStyle(statusColor)
                            .frame(width: 40, height: 40)
                            .background(statusColor.opacity(0.14), in: Circle())

                        VStack(alignment: .leading, spacing: 2) {
                            Text(statusTitle)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Text(model.connectionState == .connected ? "Трафик зашифрован и защищен" : "Защита не активна")
                                .font(.system(size: 12.5))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                        Spacer()
                    }
                    .padding(16)
                }

                SettingsCaption("Параметры соединения")
                SettingsCard {
                    SettingsCardRow(
                        title: "Выбранная локация",
                        subtitle: model.selectedLocation?.name ?? "Локация не выбрана",
                        icon: "mappin.circle.fill",
                        iconColor: YPtunColor.accent,
                        showChevron: false
                    )

                    if let loc = model.selectedLocation {
                        SettingsCardDivider()
                        SettingsCardRow(
                            title: "Протокол и транспорт",
                            subtitle: loc.protocolTag.isEmpty ? (loc.detail.isEmpty ? "VLESS / VMess" : loc.detail) : loc.protocolTag,
                            icon: "network.badge.shield.half.filled",
                            iconColor: YPtunColor.accent,
                            showChevron: false
                        )

                        if !loc.flagEmoji.isEmpty {
                            SettingsCardDivider()
                            SettingsCardRow(
                                title: "Регион",
                                subtitle: loc.flagEmoji,
                                icon: "globe.europe.africa.fill",
                                iconColor: .blue,
                                showChevron: false
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Информация о сессии")
    }

    private var statusTitle: String {
        switch model.connectionState {
        case .disconnected: "Не подключено"
        case .connecting: "Подключение…"
        case .connected: "Защищено"
        case .disconnecting: "Отключение…"
        }
    }

    private var statusIcon: String {
        switch model.connectionState {
        case .connected: "checkmark.shield.fill"
        case .connecting: "arrow.triangle.2.circlepath"
        case .disconnected, .disconnecting: "xmark.shield.fill"
        }
    }

    private var statusColor: Color {
        switch model.connectionState {
        case .connected: YPtunColor.pingGreen
        case .connecting: YPtunColor.accent
        case .disconnected, .disconnecting: YPtunColor.offlineRed
        }
    }
}
