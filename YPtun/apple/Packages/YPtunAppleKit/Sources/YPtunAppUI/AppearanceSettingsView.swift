import SwiftUI
import YPtunMigrationSupport
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

public struct AppearanceSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel
    @ObservedObject private var themeManager = YPtunThemeManager.shared

    @State private var showAutoButton = true
    @State private var showGroupAutoPick = true
    @State private var showSpeedOnHome = true
    @State private var twoColumnLayout = false
    @State private var hideEndpointWhenDescription = true
    @State private var pinSubscriptions = true
    @State private var showSubscriptionIcons = true
    @State private var showTrafficBar = true
    @State private var hideSessionButton = false
    @State private var customColor = Color(hex: 0xB388FF)
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // 1. Accent Color & Appearance (matches Android ThemeState)
                SettingsCaption("Оформление и акцентный цвет")
                SettingsCard {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text("Цвет элементов и кнопок")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                        }

                        // Presets row matching Android ThemeState.accentPresets
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                ForEach(YPtunThemeManager.accentPresets, id: \.hex) { preset in
                                    let isSelected = themeManager.accentHex == preset.hex
                                    Button {
                                        themeManager.accentHex = preset.hex
                                    } label: {
                                        ZStack {
                                            Circle()
                                                .fill(Color(hex: preset.hex))
                                                .frame(width: 38, height: 38)
                                                .overlay(
                                                    Circle()
                                                        .stroke(Color.white, lineWidth: isSelected ? 3 : 0)
                                                )
                                                .shadow(color: isSelected ? Color(hex: preset.hex).opacity(0.6) : Color.clear, radius: 6)

                                            if isSelected {
                                                Image(systemName: "checkmark")
                                                    .font(.system(size: 14, weight: .bold))
                                                    .foregroundStyle(.white)
                                            }
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.vertical, 4)
                        }

                        SettingsCardDivider()

                        // Custom color picker
                        ColorPicker("Выбрать произвольный цвет", selection: $customColor)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(YPtunColor.textPrimary)
                            .onChange(of: customColor) { newColor in
                                if let hex = hexFromColor(newColor) {
                                    themeManager.accentHex = hex
                                }
                            }
                    }
                    .padding(14)
                }

                // 2. Home Screen
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

                // 3. List View
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
                        subtitle: "Отображать прогресс-бар использованного трафика (например, 896.8 GB / ∞)",
                        icon: "chart.bar.fill",
                        iconColor: YPtunColor.accentPurple,
                        isOn: $showTrafficBar
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Иконки подписок",
                        subtitle: "Отображать логотип провайдера подписки слева от названия",
                        icon: "app.badge.fill",
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
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Оформление")
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
        customColor = Color(hex: themeManager.accentHex)
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
        dict["accentColor"] = String(format: "#%06X", themeManager.accentHex)

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }

    private func hexFromColor(_ color: Color) -> UInt? {
        #if canImport(UIKit)
        let uiColor = UIColor(color)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        if uiColor.getRed(&r, green: &g, blue: &b, alpha: &a) {
            let ir = UInt(r * 255.0) & 0xFF
            let ig = UInt(g * 255.0) & 0xFF
            let ib = UInt(b * 255.0) & 0xFF
            return (ir << 16) | (ig << 8) | ib
        }
        #elseif canImport(AppKit)
        let nsColor = NSColor(color).usingColorSpace(.sRGB) ?? NSColor(color)
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        nsColor.getRed(&r, green: &g, blue: &b, alpha: &a)
        let ir = UInt(r * 255.0) & 0xFF
        let ig = UInt(g * 255.0) & 0xFF
        let ib = UInt(b * 255.0) & 0xFF
        return (ir << 16) | (ig << 8) | ib
        #endif
        return nil
    }
}
