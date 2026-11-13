import SwiftUI
import YPtunMigrationSupport

public struct SettingsOverviewView: View {
    @ObservedObject private var model: YPtunApplicationModel
    private let bootstrapper = AppleApplicationBootstrapper()

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        List {
            Section("Подключение") {
                NavigationLink {
                    SubscriptionLocationsView(model: model)
                } label: {
                    Label("Подписки", systemImage: "dot.radiowaves.left.and.right")
                }
                NavigationLink {
                    RawSettingsEditorView(
                        title: "Профили маршрутизации",
                        file: .routingProfiles,
                        bootstrapper: bootstrapper
                    )
                } label: {
                    Label("Профили маршрутизации", systemImage: "arrow.triangle.branch")
                }
                NavigationLink {
                    RawSettingsEditorView(
                        title: "Настройки трафика",
                        file: .traffic,
                        bootstrapper: bootstrapper
                    )
                } label: {
                    Label("Настройки трафика", systemImage: "point.3.connected.trianglepath.dotted")
                }
            }
            Section("Приложение") {
                NavigationLink {
                    TunnelLogView()
                } label: {
                    Label("Логи", systemImage: "text.alignleft")
                }
                NavigationLink {
                    SessionOverviewView(model: model)
                } label: {
                    Label("Информация о сессии", systemImage: "info.circle")
                }
                NavigationLink {
                    RawSettingsEditorView(
                        title: "Основные настройки",
                        file: .appBehavior,
                        bootstrapper: bootstrapper
                    )
                } label: {
                    Label("Основные настройки", systemImage: "gearshape")
                }
            }
            Section {
                LabeledContent("Нативный клиент", value: "iOS 16+ · macOS 14+")
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
        VStack(spacing: 12) {
            Label(title, systemImage: symbol)
                .font(.title3.weight(.semibold))
            Text("Раздел переносится на Swift и будет подключён к общему Rust-ядру.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .navigationTitle(title)
    }
}
