import SwiftUI
import YPtunMigrationSupport

public struct RoutingSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var bypassRussia = false
    @State private var blockAds = false
    @State private var bypassLan = true
    @State private var directDomainsText = ""
    @State private var blockDomainsText = ""
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Caption: Основные правила
                SettingsCaption("Основные правила маршрутизации")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Обход сайтов РФ (.ru / .рф)",
                        subtitle: "Направлять российские ресурсы напрямую мимо VPN",
                        icon: "flag.fill",
                        iconColor: .blue,
                        isOn: $bypassRussia
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Блокировка рекламы и трекеров",
                        subtitle: "Фильтрация известных рекламных сетей на уровне ядра",
                        icon: "shield.fill",
                        iconColor: .purple,
                        isOn: $blockAds
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Обход локальной сети (LAN)",
                        subtitle: "Прямой доступ к роутеру, принтерам и домашним устройствам",
                        icon: "network",
                        iconColor: .green,
                        isOn: $bypassLan
                    )
                }

                // Presets
                SettingsCaption("Быстрые пресеты")
                SettingsCard {
                    SettingsCardRow(
                        title: "РФ напрямую + Блокировка рекламы",
                        subtitle: "Оптимальный режим для повседневного использования",
                        icon: "wand.and.stars",
                        showChevron: false
                    ) {
                        bypassRussia = true
                        blockAds = true
                        bypassLan = true
                    }
                    SettingsCardDivider()
                    SettingsCardRow(
                        title: "Только РФ напрямую",
                        subtitle: "Реклама не фильтруется, открываются Госуслуги и банки",
                        icon: "globe.europe.africa.fill",
                        showChevron: false
                    ) {
                        bypassRussia = true
                        blockAds = false
                        bypassLan = true
                    }
                    SettingsCardDivider()
                    SettingsCardRow(
                        title: "Весь трафик в VPN",
                        subtitle: "Полное шифрование всех запросов без исключений",
                        icon: "lock.shield.fill",
                        showChevron: false
                    ) {
                        bypassRussia = false
                        blockAds = false
                        bypassLan = true
                    }
                }

                // Custom Domains
                SettingsCaption("Пользовательские домены")
                SettingsCard {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Прямые домены (Direct)")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                        Text("Сайты, которые всегда открываются напрямую (через пробел или запятую)")
                            .font(.system(size: 12))
                            .foregroundStyle(YPtunColor.textSecondary)

                        TextField("example.ru, mybank.com", text: $directDomainsText)
                            .font(.system(size: 14, design: .monospaced))
                            .padding(10)
                            .background(YPtunColor.cardSurfaceHigh, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(16)

                    SettingsCardDivider()

                    VStack(alignment: .leading, spacing: 6) {
                        Text("Заблокированные домены (Block)")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                        Text("Домены, обращения к которым будут отклонены ядром")
                            .font(.system(size: 12))
                            .foregroundStyle(YPtunColor.textSecondary)

                        TextField("adservice.google.com, tracker.com", text: $blockDomainsText)
                            .font(.system(size: 14, design: .monospaced))
                            .padding(10)
                            .background(YPtunColor.cardSurfaceHigh, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(16)
                }
                SettingsNote("Правила применяются в реальном времени Rust-ядром sing-box и Xray. При сохранении активный VPN-туннель мягко перезапускается.")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Маршрутизация")
        .toolbar {
            Button("Сохранить") {
                Task { await save() }
            }
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
        guard let text = await model.settingsText(.routing),
              let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        bypassRussia = obj["bypass_russia"] as? Bool ?? false
        blockAds = obj["block_ads"] as? Bool ?? false
        bypassLan = obj["bypass_lan"] as? Bool ?? true

        if let direct = obj["direct_domains"] as? [String] {
            directDomainsText = direct.joined(separator: ", ")
        }
        if let blocked = obj["block_domains"] as? [String] {
            blockDomainsText = blocked.joined(separator: ", ")
        }
    }

    @MainActor
    private func save() async {
        isSaving = true
        defer { isSaving = false }

        let direct = directDomainsText
            .split(whereSeparator: { $0 == "," || $0 == " " || $0 == "\n" })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let blocked = blockDomainsText
            .split(whereSeparator: { $0 == "," || $0 == " " || $0 == "\n" })
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let dict: [String: Any] = [
            "bypass_russia": bypassRussia,
            "block_ads": blockAds,
            "bypass_lan": bypassLan,
            "direct_domains": direct,
            "block_domains": blocked,
            "custom_rules_json": "",
            "custom_rule_sets_json": ""
        ]

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .routing)
        }
    }
}
