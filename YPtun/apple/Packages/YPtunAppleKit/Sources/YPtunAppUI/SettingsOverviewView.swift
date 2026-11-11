import SwiftUI

public struct SettingsOverviewView: View {
    @Bindable private var model: YPtunApplicationModel

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        List {
            Section("Подключение") {
                destination("Подписки", symbol: "dot.radiowaves.left.and.right")
                destination("Профили маршрутизации", symbol: "arrow.triangle.branch")
                destination("Настройки трафика", symbol: "point.3.connected.trianglepath.dotted")
            }
            Section("Приложение") {
                NavigationLink {
                    TunnelLogView()
                } label: {
                    Label("Логи", systemImage: "text.alignleft")
                }
                destination("Информация о сессии", symbol: "info.circle")
                destination("Основные настройки", symbol: "gearshape")
            }
            Section {
                LabeledContent("Нативный клиент", value: "iOS 17+ · macOS 14+")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Настройки")
    }

    private func destination(_ title: String, symbol: String) -> some View {
        NavigationLink {
            FeaturePlaceholderView(title: title, symbol: symbol)
        } label: {
            Label(title, systemImage: symbol)
        }
    }
}

private struct FeaturePlaceholderView: View {
    let title: String
    let symbol: String

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            Text("Раздел переносится на Swift и будет подключён к общему Rust-ядру.")
        }
        .navigationTitle(title)
    }
}
