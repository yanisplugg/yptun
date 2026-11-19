import SwiftUI
import YPtunMigrationSupport

public struct SettingsOverviewView: View {
    @ObservedObject private var model: YPtunApplicationModel
    @Environment(\.dismiss) private var dismiss
    @State private var searchText = ""

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Sheet Header: "Настройки" title on left, "✕" close button on right (matching Screenshot 6)
                HStack {
                    Text("Настройки")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(YPtunColor.textPrimary)
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .frame(width: 32, height: 32)
                            .background(YPtunColor.cardSurfaceHigh, in: Circle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 4)
                .padding(.top, 4)

                // Search Bar: "🔍 Поиск по настройкам"
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 15))
                        .foregroundStyle(YPtunColor.textMuted)
                    TextField("Поиск по настройкам", text: $searchText)
                        .font(.system(size: 14))
                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 14))
                                .foregroundStyle(YPtunColor.textMuted)
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: 14))
                .padding(.bottom, 2)

                // 1. ПОДКЛЮЧЕНИЕ (Screenshot 6: Настройки подключения, Маршрутизация и правила, Настройки трафика)
                if matchesSearch("подключение", "режим", "socks", "прокси", "авто", "туннель", "маршрутизация", "трафик", "dns") {
                    SettingsCaption("ПОДКЛЮЧЕНИЕ")
                    SettingsCard {
                        NavigationLink {
                            ConnectionSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Настройки подключения",
                                subtitle: "TUN · Полный туннель",
                                icon: "shield",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            RoutingSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Маршрутизация и правила",
                                subtitle: "Нарр-совместимые правила — глобально или для отдельной локации",
                                icon: "arrow.triangle.branch",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            TrafficSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Настройки трафика",
                                subtitle: "DNS, мультиплексирование, стратегия доменов",
                                icon: "server.rack",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 2. ПРИЛОЖЕНИЕ (Screenshot 6: Язык, Оформление, Конфиденциальность)
                if matchesSearch("приложение", "язык", "оформление", "цвет", "тема", "конфиденциальность") {
                    SettingsCaption("ПРИЛОЖЕНИЕ")
                    SettingsCard {
                        Menu {
                            Button("Русский") {}
                            Button("English") {}
                        } label: {
                            SettingsCardRow(
                                title: "Язык",
                                subtitle: nil,
                                value: "Русский",
                                icon: "globe",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            AppearanceSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Оформление",
                                subtitle: "Цвета YPtun · Тёмный фон",
                                icon: "paintpalette",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            AppBehaviorSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Конфиденциальность",
                                subtitle: "Что приложение отправляет наружу",
                                icon: "lock",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 3. ЕЩЁ (Screenshot 6: Дополнительно, Поддержать, Логи)
                if matchesSearch("ещё", "дополнительно", "vless", "ядро", "лог", "поддержать") {
                    SettingsCaption("ЕЩЁ")
                    SettingsCard {
                        NavigationLink {
                            ExperimentalSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Дополнительно",
                                subtitle: "Движок VLESS, схемы URI, редактор...",
                                icon: "wrench.and.screwdriver",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            SupportProjectView()
                        } label: {
                            SettingsCardRow(
                                title: "Поддержать проект",
                                subtitle: "Помощь разработчикам и реквизиты",
                                icon: "heart.fill",
                                iconColor: .pink
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            TunnelLogView()
                        } label: {
                            SettingsCardRow(
                                title: "Логи приложения",
                                subtitle: "Журнал подключений и ядра",
                                icon: "text.alignleft",
                                iconColor: YPtunColor.textSecondary
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // About Footer
                VStack(spacing: 6) {
                    Text("YPtun для iOS · Версия 4.0.0 (509)")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(YPtunColor.textSecondary)
                    Text("Rust Core · sing-box · Xray-core · AmneziaWG · NetworkExtension")
                        .font(.system(size: 11))
                        .foregroundStyle(YPtunColor.textMuted)

                    HStack(spacing: 12) {
                        Link("Конфиденциальность", destination: URL(string: "https://yanisplugg.github.io/yptun/privacy.html")!)
                        Text("·").foregroundStyle(YPtunColor.textMuted)
                        Link("Условия", destination: URL(string: "https://yanisplugg.github.io/yptun/terms.html")!)
                        Text("·").foregroundStyle(YPtunColor.textMuted)
                        Link("Поддержка", destination: URL(string: "https://yanisplugg.github.io/yptun/support.html")!)
                    }
                    .font(.system(size: 12))
                    .foregroundStyle(YPtunColor.accent)
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 16)
                .padding(.bottom, 24)
            }
            .padding(.horizontal, 16)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .tint(YPtunColor.accent)
        .preferredColorScheme(.dark)
    }

    private func matchesSearch(_ terms: String...) -> Bool {
        guard !searchText.isEmpty else { return true }
        let query = searchText.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        return terms.contains { $0.lowercased().contains(query) }
    }
}
