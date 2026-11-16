import SwiftUI
import YPtunMigrationSupport

public struct PingSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var pingResultDisplay = "both" // "time", "icon", "both"
    @State private var showSubscriptionAliveCount = true
    @State private var savePingResults = true
    @State private var showPingControls = true
    @State private var pingParallelism = 8
    @State private var pingTimeoutSec = 3
    @State private var pingPrecision = 1
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Display format
                SettingsCaption("Отображение результата")
                SettingsChoiceChips(
                    options: [
                        ("both", "Время и статус (✔ 120 ms)"),
                        ("time", "Только время (120 ms)"),
                        ("icon", "Только иконка")
                    ],
                    selected: $pingResultDisplay
                )

                // List of options
                SettingsCaption("Параметры проверки")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Количество живых локаций",
                        subtitle: "Показывать счётчик работающих серверов в шапке подписки (например, 9/9)",
                        icon: "checklist.checked",
                        iconColor: .green,
                        isOn: $showSubscriptionAliveCount
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Сохранять результаты пинга",
                        subtitle: "Не сбрасывать результаты замера при перезапуске приложения",
                        icon: "tray.and.arrow.down.fill",
                        iconColor: .blue,
                        isOn: $savePingResults
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Кнопки пинга в карточках",
                        subtitle: "Показывать кнопку пинга всех серверов в группе",
                        icon: "bolt.fill",
                        iconColor: .orange,
                        isOn: $showPingControls
                    )
                }

                // Performance & Timeout
                SettingsCaption("Производительность и задержка")
                SettingsCard {
                    SettingsStepperRow(
                        title: "Потоков параллельного пинга",
                        subtitle: "Количество одновременных сетевых запросов к серверам",
                        value: pingParallelism,
                        range: 1...16,
                        onChange: { pingParallelism = $0 }
                    )
                    SettingsCardDivider()
                    SettingsStepperRow(
                        title: "Таймаут проверки",
                        subtitle: "Максимальное время ожидания ответа от сервера",
                        value: pingTimeoutSec,
                        range: 1...10,
                        unit: "сек",
                        onChange: { pingTimeoutSec = $0 }
                    )
                }

                // Precision
                SettingsCaption("Точность замера")
                SettingsChoiceChips(
                    options: [
                        (1, "Одиночный замер (быстро)"),
                        (2, "Двойной (усреднение)"),
                        (3, "Тройной (высокая точность)")
                    ],
                    selected: $pingPrecision
                )
                SettingsNote("Высокая точность делает несколько повторных замеров каждого сервера и вычисляет медианное значение задержки.")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Тест скорости и пинг")
        .toolbar {
            Button("Сохранить") { Task { await save() } }
                .disabled(isLoading || isSaving)
        }
        .task { await load() }
        .overlay {
            if isLoading || isSaving {
                ProgressView(isSaving ? "Сохранение…" : "Загрузка…")
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }

    @MainActor
    private func load() async {
        defer { isLoading = false }
        guard let text = await model.settingsText(.appBehavior),
              let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        pingResultDisplay = obj["pingResultDisplay"] as? String ?? "both"
        showSubscriptionAliveCount = obj["showSubscriptionAliveCount"] as? Bool ?? true
        savePingResults = obj["savePingResults"] as? Bool ?? true
        showPingControls = obj["showPingControls"] as? Bool ?? true
        pingParallelism = obj["pingParallelism"] as? Int ?? 8
        pingTimeoutSec = obj["pingTimeoutSec"] as? Int ?? 3
        pingPrecision = obj["pingPrecision"] as? Int ?? 1
    }

    @MainActor
    private func save() async {
        isSaving = true
        defer { isSaving = false }

        var dict: [String: Any] = [:]
        if let text = await model.settingsText(.appBehavior),
           let data = text.data(using: .utf8),
           let existing = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            dict = existing
        }

        dict["pingResultDisplay"] = pingResultDisplay
        dict["showSubscriptionAliveCount"] = showSubscriptionAliveCount
        dict["savePingResults"] = savePingResults
        dict["showPingControls"] = showPingControls
        dict["pingParallelism"] = pingParallelism
        dict["pingTimeoutSec"] = pingTimeoutSec
        dict["pingPrecision"] = pingPrecision

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }
}
