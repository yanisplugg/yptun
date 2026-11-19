import SwiftUI
import YPtunShared
import YPtunMigrationSupport

public struct SubscriptionsSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var confirmBeforeDelete = true
    @State private var allowConfigEditing = true
    @State private var shareUniversalLink = true
    @State private var autoUpdateInterval = 1
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    private var subscriptions: [(id: String, name: String, count: Int)] {
        var dict: [String: (name: String, count: Int)] = [:]
        for loc in model.locations where loc.isFromSubscription {
            if let subID = loc.subscriptionID {
                let name = loc.subscriptionName ?? URL(string: subID)?.host ?? "Подписка"
                dict[subID] = (name: name, count: (dict[subID]?.count ?? 0) + 1)
            }
        }
        return dict.map { (id: $0.key, name: $0.value.name, count: $0.value.count) }
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Active Subscriptions
                SettingsCaption("Активные подписки")
                if subscriptions.isEmpty {
                    SettingsCard {
                        HStack(spacing: 12) {
                            Image(systemName: "tray.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(YPtunColor.textMuted)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Нет добавленных подписок")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text("Импортируйте URL-ссылку подписки через экран «+»")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YPtunColor.textSecondary)
                            }
                        }
                        .padding(16)
                    }
                } else {
                    SettingsCard {
                        ForEach(Array(subscriptions.enumerated()), id: \.element.id) { index, sub in
                            if index > 0 { SettingsCardDivider() }
                            HStack(spacing: 12) {
                                Image(systemName: "network")
                                    .font(.system(size: 18))
                                    .foregroundStyle(YPtunColor.accent)
                                    .frame(width: 32, height: 32)
                                    .background(YPtunColor.accent.opacity(0.12), in: Circle())

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(sub.name)
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(YPtunColor.textPrimary)
                                    Text("\(sub.count) локаций · \(sub.id)")
                                        .font(.system(size: 12))
                                        .foregroundStyle(YPtunColor.textSecondary)
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)

                                Button {
                                    YPtunPasteboard.setString(sub.id)
                                    model.presentedNotice = YPtunNotice(
                                        title: "Скопировано",
                                        message: "Ссылка на подписку скопирована в буфер обмена."
                                    )
                                } label: {
                                    Image(systemName: "doc.on.doc")
                                        .font(.system(size: 14))
                                        .foregroundStyle(YPtunColor.textSecondary)
                                        .frame(width: 30, height: 30)
                                        .background(YPtunColor.cardSurfaceHigh, in: Circle())
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                    }
                }

                // Auto-refresh interval
                SettingsCaption("Периодичность автообновления")
                SettingsChoiceChips(
                    options: [
                        (1, "Каждый 1 ч"),
                        (6, "Каждые 6 ч"),
                        (12, "Каждые 12 ч"),
                        (24, "Раз в сутки")
                    ],
                    selected: $autoUpdateInterval
                )

                // Actions & Safety
                SettingsCaption("Действия и безопасность")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Подтверждение удаления",
                        subtitle: "Запрашивать диалог подтверждения перед удалением конфигураций и подписок",
                        icon: "trash.circle.fill",
                        iconColor: .red,
                        isOn: $confirmBeforeDelete
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Редактирование конфигураций",
                        subtitle: "Разрешить ручное изменение параметров серверов и JSON-структур",
                        icon: "pencil.circle.fill",
                        iconColor: .blue,
                        isOn: $allowConfigEditing
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Универсальные ссылки (yptun://)",
                        subtitle: "Генерировать удобные ссылки yptun:// при шаринге конфигураций",
                        icon: "link.circle.fill",
                        iconColor: YPtunColor.accent,
                        isOn: $shareUniversalLink
                    )
                }

                // Supported URL Schemes
                SettingsCaption("Поддерживаемые форматы и протоколы")
                SettingsCard {
                    SettingsCardRow(
                        title: "Ассоциации ссылок",
                        subtitle: "yptun://, vless://, vmess://, ss://, trojan://, sub://",
                        icon: "link",
                        showChevron: false
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Подписки и конфигурации")
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

        confirmBeforeDelete = obj["confirmBeforeDelete"] as? Bool ?? true
        allowConfigEditing = obj["allowConfigEditing"] as? Bool ?? true
        shareUniversalLink = obj["shareUniversalLink"] as? Bool ?? true
        autoUpdateInterval = obj["autoUpdateInterval"] as? Int ?? 1
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

        dict["confirmBeforeDelete"] = confirmBeforeDelete
        dict["allowConfigEditing"] = allowConfigEditing
        dict["shareUniversalLink"] = shareUniversalLink
        dict["autoUpdateInterval"] = autoUpdateInterval

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }
}
