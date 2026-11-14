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
                    TrafficSettingsView(model: model)
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
            Section("Расширенные настройки") {
                NavigationLink {
                    RawSettingsEditorView(
                        title: "Настройки трафика — JSON",
                        file: .traffic,
                        bootstrapper: bootstrapper
                    )
                } label: {
                    Label("JSON настроек трафика", systemImage: "curlybraces")
                }
            }
        }
        .navigationTitle("Настройки")
    }
}
