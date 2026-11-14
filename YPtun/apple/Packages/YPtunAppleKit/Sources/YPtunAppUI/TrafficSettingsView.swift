import Foundation
import SwiftUI
import YPtunMigrationSupport

struct TrafficSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var values: [String: Any] = [:]
    @State private var isLoading = true
    @State private var isSaving = false

    var body: some View {
        Form {
            Section("DNS") {
                TextField("Удалённый DNS", text: stringBinding("remoteDns", default: "8.8.8.8"))
                TextField("Резервный удалённый DNS", text: stringBinding("remoteDns2"))
                TextField("Прямой DNS", text: stringBinding("directDns", default: "77.88.8.8"))
                Picker("Стратегия адресов", selection: stringBinding("domainStrategy", default: "ipv4_only")) {
                    Text("Предпочитать IPv4").tag("prefer_ipv4")
                    Text("Предпочитать IPv6").tag("prefer_ipv6")
                    Text("Только IPv4").tag("ipv4_only")
                    Text("Только IPv6").tag("ipv6_only")
                }
            }

            Section("Мультиплексирование") {
                Toggle("Включить MUX", isOn: boolBinding("muxEnabled"))
                if boolValue("muxEnabled") {
                    Picker("Протокол", selection: stringBinding("muxProtocol", default: "h2mux")) {
                        Text("h2mux").tag("h2mux")
                        Text("smux").tag("smux")
                        Text("yamux").tag("yamux")
                    }
                    Stepper(
                        "Максимум соединений: \(intValue("muxMaxConnections", default: 8))",
                        value: intBinding("muxMaxConnections", default: 8),
                        in: 1...64
                    )
                }
            }

            Section("Фрагментация TLS (Xray)") {
                Toggle("Включить фрагментацию", isOn: boolBinding("fragmentEnabled"))
                if boolValue("fragmentEnabled") {
                    TextField("Пакеты", text: stringBinding("fragmentPackets", default: "tlshello"))
                    TextField("Размер фрагмента", text: stringBinding("fragmentLength", default: "50-100"))
                    TextField("Интервал, мс", text: stringBinding("fragmentInterval", default: "10-20"))
                }
            }

            Section {
                Stepper(
                    "MTU: \(intValue("mtu", default: 1500))",
                    value: intBinding("mtu", default: 1500),
                    in: 1_280...9_000,
                    step: 10
                )
                Toggle("Блокировать RU-домены", isOn: boolBinding("blockRuDomains"))
            } header: {
                Text("Сеть")
            } footer: {
                Text("Изменения валидируются Rust-ядром. При активном VPN туннель автоматически перезапускается.")
            }
        }
        .disabled(isLoading || isSaving)
        .navigationTitle("Настройки трафика")
        .toolbar {
            Button("Сохранить") {
                Task { await save() }
            }
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
            get: { boolValue(key, default: defaultValue) },
            set: { values[key] = $0 }
        )
    }

    private func intBinding(_ key: String, default defaultValue: Int) -> Binding<Int> {
        Binding(
            get: { intValue(key, default: defaultValue) },
            set: { values[key] = $0 }
        )
    }

    private func boolValue(_ key: String, default defaultValue: Bool = false) -> Bool {
        if let value = values[key] as? Bool { return value }
        return (values[key] as? NSNumber)?.boolValue ?? defaultValue
    }

    private func intValue(_ key: String, default defaultValue: Int) -> Int {
        if let value = values[key] as? Int { return value }
        return (values[key] as? NSNumber)?.intValue ?? defaultValue
    }

    @MainActor
    private func load() async {
        defer { isLoading = false }
        guard let text = await model.settingsText(.traffic),
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
        _ = await model.saveSettingsText(text, file: .traffic)
        isSaving = false
    }
}
