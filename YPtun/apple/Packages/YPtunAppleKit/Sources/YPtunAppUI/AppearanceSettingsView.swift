import SwiftUI
import YPtunMigrationSupport

public struct AppearanceSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var showAutoButton = true
    @State private var showGroupAutoPick = true
    @State private var showSpeedOnHome = true
    @State private var twoColumnLayout = false
    @State private var hideEndpointWhenDescription = true
    @State private var pinSubscriptions = true
    @State private var showSubscriptionIcons = true
    @State private var showTrafficBar = true
    @State private var hideSessionButton = false
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Home Screen
                SettingsCaption("Главный экран")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Кнопка «Авто»",
                        subtitle: "Плавающая кнопка быстрого подключения рядом со Start",
                        icon: "bolt.circle.fill",
                        iconColor: YPtunColor.accent,
                        isOn: $showAutoButton
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Автовыбор в подписках (⚡)",
                        subtitle: "Кнопка выбора лучшего сервера в шапке каждой карточки",
                        icon: "bolt.fill",
                        iconColor: .yellow,
                        isOn: $showGroupAutoPick
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Задержка на главном экране",
                        subtitle: "Показывать время отклика и пинг прямо в карточках локаций",
                        icon: "timer",
                        iconColor: YPtunColor.pingGreen,
                        isOn: $showSpeedOnHome
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Скрыть кнопку сессии",
                        subtitle: "Убрать плашку с информацией о сессии под кнопкой подключения",
                        icon: "info.circle",
                        iconColor: .gray,
                        isOn: $hideSessionButton
                    )
                }

                // List View
                SettingsCaption("Список локаций и подписок")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Закреплять подписки наверху",
                        subtitle: "Размещать карточки подписок перед единичными серверами",
                        icon: "pin.fill",
                        iconColor: .orange,
                        isOn: $pinSubscriptions
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Полоса расхода трафика",
                        subtitle: "Отображать прогресс-бар использованного трафика (например, 809.9 GB / ∞)",
                        icon: "chart.bar.fill",
                        iconColor: YPtunColor.accentPurple,
                        isOn: $showTrafficBar
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Иконки подписок",
                        subtitle: "Отображать логотипы и фавиконы провайдеров",
                        icon: "photo.fill",
                        iconColor: .cyan,
                        isOn: $showSubscriptionIcons
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Скрывать адрес эндпоинта",
                        subtitle: "Скрывать IP-адрес сервера, если задано понятное имя",
                        icon: "eye.slash.fill",
                        iconColor: .indigo,
                        isOn: $hideEndpointWhenDescription
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Двухколоночный режим",
                        subtitle: "Компактная сетка серверов в две колонки на iPad и широких экранах",
                        icon: "square.grid.2x2.fill",
                        iconColor: .teal,
                        isOn: $twoColumnLayout
                    )
                }

                // Theme
                SettingsCaption("Оформление")
                SettingsCard {
                    SettingsCardRow(
                        title: "Цветовая схема",
                        subtitle: "Глубокая тёмная тема AMOLED с неоновыми фиолетовыми акцентами",
                        value: "AMOLED Dark",
                        icon: "moon.stars.fill",
                        iconColor: YPtunColor.accent,
                        showChevron: false
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Вид и оформление")
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

        showAutoButton = obj["showAutoButton"] as? Bool ?? true
        showGroupAutoPick = obj["showGroupAutoPick"] as? Bool ?? true
        showSpeedOnHome = obj["showSpeedOnHome"] as? Bool ?? true
        hideSessionButton = obj["hideSessionButton"] as? Bool ?? false
        twoColumnLayout = obj["twoColumnLayout"] as? Bool ?? false
        hideEndpointWhenDescription = obj["hideEndpointWhenDescription"] as? Bool ?? true
        pinSubscriptions = obj["pinSubscriptions"] as? Bool ?? true
        showSubscriptionIcons = obj["showSubscriptionIcons"] as? Bool ?? true
        showTrafficBar = obj["showTrafficBar"] as? Bool ?? true
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

        dict["showAutoButton"] = showAutoButton
        dict["showGroupAutoPick"] = showGroupAutoPick
        dict["showSpeedOnHome"] = showSpeedOnHome
        dict["hideSessionButton"] = hideSessionButton
        dict["twoColumnLayout"] = twoColumnLayout
        dict["hideEndpointWhenDescription"] = hideEndpointWhenDescription
        dict["pinSubscriptions"] = pinSubscriptions
        dict["showSubscriptionIcons"] = showSubscriptionIcons
        dict["showTrafficBar"] = showTrafficBar

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }
}
