import Foundation
import SwiftUI
import YPtunMigrationSupport

struct AppBehaviorSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var values: [String: Any] = [:]
    @State private var isLoading = true
    @State private var isSaving = false

    var body: some View {
        Form {
            Section("Подключение") {
                Toggle("Подключаться при запуске", isOn: boolBinding("autoConnectOnLaunch"))
                Toggle("Спрашивать перед удалением", isOn: boolBinding("confirmBeforeDelete", default: true))
                Toggle("Скрыть кнопку сессии", isOn: boolBinding("hideSessionButton"))
            }

            Section("Список локаций") {
                Toggle("Показывать иконки подписок", isOn: boolBinding("showSubscriptionIcons", default: true))
            }

            Section {
                Picker("Ядро по умолчанию", selection: stringBinding("globalProxyCore", default: "auto")) {
                    Text("Автоматически").tag("auto")
                    Text("sing-box").tag("singbox")
                    Text("Xray").tag("xray")
                }
            } header: {
                Text("Ядро прокси")
            } footer: {
                Text("Применяется только к локациям, где ядро не выбрано вручную.")
            }
        }
        .disabled(isLoading || isSaving)
        .navigationTitle("Основные настройки")
        .toolbar {
            Button("Сохранить") { Task { await save() } }
                .disabled(isLoading || isSaving)
        }
        .overlay {
            if isLoading || isSaving {
                ProgressView(isSaving ? "Сохранение…" : "Загрузка…")
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .task { await load() }
    }

    private func stringBinding(_ key: String, default defaultValue: String = "") -> Binding<String> {
        Binding(
            get: { values[key] as? String ?? defaultValue },
            set: { values[key] = $0 }
        )
    }

    private func boolBinding(_ key: String, default defaultValue: Bool = false) -> Binding<Bool> {
        Binding(
            get: {
                if let value = values[key] as? Bool { return value }
                return (values[key] as? NSNumber)?.boolValue ?? defaultValue
            },
            set: { values[key] = $0 }
        )
    }

    @MainActor
    private func load() async {
        defer { isLoading = false }
        guard let text = await model.settingsText(.appBehavior),
              let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any] else { return }
        values = dictionary
    }

    @MainActor
    private func save() async {
        guard JSONSerialization.isValidJSONObject(values),
              let data = try? JSONSerialization.data(
                withJSONObject: values,
                options: [.prettyPrinted, .sortedKeys]
              ),
              let text = String(data: data, encoding: .utf8) else {
            model.presentedNotice = YPtunNotice(
                title: "Не удалось сохранить настройки",
                message: "Настройки содержат неподдерживаемое значение."
            )
            return
        }
        isSaving = true
        _ = await model.saveSettingsText(text, file: .appBehavior)
        isSaving = false
    }
}
