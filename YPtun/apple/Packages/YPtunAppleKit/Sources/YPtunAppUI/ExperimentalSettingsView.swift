import SwiftUI
import YPtunMigrationSupport

public struct ExperimentalSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel
    private let bootstrapper = AppleApplicationBootstrapper()

    @State private var globalProxyCore = "auto"
    @State private var vkTurnEnabled = true
    @State private var amneziaWgEnabled = true
    @State private var mieruEnabled = true
    @State private var snolcEnabled = true
    @State private var csqttEnabled = true
    @State private var openFluxEnabled = true
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Default Core
                SettingsCaption("Сетевое ядро по умолчанию")
                SettingsChoiceChips(
                    options: [
                        ("auto", "Автоматически"),
                        ("singbox", "sing-box"),
                        ("xray", "Xray-core")
                    ],
                    selected: $globalProxyCore
                )
                SettingsNote("Применяется к серверам, где тип ядра не задан принудительно.")

                // Experimental Protocols
                SettingsCaption("Экспериментальные протоколы")
                SettingsCard {
                    SettingsCardToggle(
                        title: "VK Turn / WebRTC Relay",
                        subtitle: "Обход через защищенные медиа-шлюзы VK/Mail",
                        icon: "video.fill",
                        iconColor: .blue,
                        isOn: $vkTurnEnabled
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "AmneziaWG (AWG)",
                        subtitle: "Модифицированный WireGuard с обфускацией заголовков Jc/Jmin/H1..H4",
                        icon: "shield.checkered",
                        iconColor: YPtunColor.accent,
                        isOn: $amneziaWgEnabled
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Mieru Proxy",
                        subtitle: "Высокоскоростной зашифрованный протокол на базе TCP/UDP",
                        icon: "bolt.horizontal.fill",
                        iconColor: .green,
                        isOn: $mieruEnabled
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Snolc & CSQTT",
                        subtitle: "Специализированные протоколы с переменными длинами фреймов",
                        icon: "wave.3.forward",
                        iconColor: .cyan,
                        isOn: $snolcEnabled
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "OpenFlux Proxy",
                        subtitle: "Устойчивый протокол распределения пакетов через публичные CDN",
                        icon: "arrow.triangle.swap",
                        iconColor: .orange,
                        isOn: $openFluxEnabled
                    )
                }

                // Advanced JSON Configuration
                SettingsCaption("Прямое редактирование конфигураций (JSON)")
                SettingsCard {
                    NavigationLink {
                        RawSettingsEditorView(
                            title: "Маршрутизация — JSON",
                            file: .routing,
                            bootstrapper: bootstrapper
                        )
                    } label: {
                        SettingsCardRow(
                            title: "routing.json",
                            subtitle: "Сырые правила маршрутизации и списки исключений",
                            icon: "curlybraces",
                            iconColor: YPtunColor.accent
                        )
                    }
                    .buttonStyle(.plain)

                    SettingsCardDivider()

                    NavigationLink {
                        RawSettingsEditorView(
                            title: "Настройки трафика — JSON",
                            file: .traffic,
                            bootstrapper: bootstrapper
                        )
                    } label: {
                        SettingsCardRow(
                            title: "traffic.json",
                            subtitle: "DNS, MTU, мультиплексирование и TLS-фрагментация",
                            icon: "curlybraces",
                            iconColor: YPtunColor.accentPurple
                        )
                    }
                    .buttonStyle(.plain)

                    SettingsCardDivider()

                    NavigationLink {
                        RawSettingsEditorView(
                            title: "Основные настройки — JSON",
                            file: .appBehavior,
                            bootstrapper: bootstrapper
                        )
                    } label: {
                        SettingsCardRow(
                            title: "app_behavior.json",
                            subtitle: "Поведение приложения, UI-флаги и сохраненные пинги",
                            icon: "curlybraces",
                            iconColor: .orange
                        )
                    }
                    .buttonStyle(.plain)

                    SettingsCardDivider()

                    NavigationLink {
                        RawSettingsEditorView(
                            title: "Профили маршрутизации — JSON",
                            file: .routingProfiles,
                            bootstrapper: bootstrapper
                        )
                    } label: {
                        SettingsCardRow(
                            title: "routing_profiles.json",
                            subtitle: "Комплексные наборы правил для сложных сценариев",
                            icon: "curlybraces",
                            iconColor: .green
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Экспериментальные функции")
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

        globalProxyCore = obj["globalProxyCore"] as? String ?? "auto"
        vkTurnEnabled = obj["vkTurnEnabled"] as? Bool ?? true
        amneziaWgEnabled = obj["amneziaWgEnabled"] as? Bool ?? true
        mieruEnabled = obj["mieruEnabled"] as? Bool ?? true
        snolcEnabled = obj["snolcEnabled"] as? Bool ?? true
        csqttEnabled = obj["csqttEnabled"] as? Bool ?? true
        openFluxEnabled = obj["openFluxEnabled"] as? Bool ?? true
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

        dict["globalProxyCore"] = globalProxyCore
        dict["vkTurnEnabled"] = vkTurnEnabled
        dict["amneziaWgEnabled"] = amneziaWgEnabled
        dict["mieruEnabled"] = mieruEnabled
        dict["snolcEnabled"] = snolcEnabled
        dict["csqttEnabled"] = csqttEnabled
        dict["openFluxEnabled"] = openFluxEnabled

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }
}
