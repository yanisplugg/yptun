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
    }
}

struct SessionOverviewView: View {
    @ObservedObject var model: YPtunApplicationModel

    var body: some View {
        List {
            LabeledContent("Состояние", value: statusTitle)
            LabeledContent("Локация", value: model.selectedLocation?.name ?? "Не выбрана")
            if let detail = model.selectedLocation?.detail, !detail.isEmpty {
                LabeledContent("Транспорт", value: detail)
            }
        }
        .navigationTitle("Информация о сессии")
    }

    private var statusTitle: String {
        switch model.connectionState {
        case .disconnected: "Не подключено"
        case .connecting: "Подключение"
        case .connected: "Защищено"
        case .disconnecting: "Отключение"
        }
    }
}
