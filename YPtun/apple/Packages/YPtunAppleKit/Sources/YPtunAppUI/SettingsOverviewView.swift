import SwiftUI
import YPtunMigrationSupport

public struct SettingsOverviewView: View {
    @ObservedObject private var model: YPtunApplicationModel
    private let bootstrapper = AppleApplicationBootstrapper()
    @State private var searchText = ""

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Header (1:1 with Android Compose Settings Header)
                HStack(spacing: 14) {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(YPtunColor.accent)
                        .frame(width: 48, height: 48)
                        .background(YPtunColor.cardSurfaceSelected, in: Circle())
                        .overlay(
                            Circle()
                                .stroke(YPtunColor.accent.opacity(0.2), lineWidth: 1)
                        )

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Настройки")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundStyle(YPtunColor.textPrimary)
                        Text("Параметры подключения и системы")
                            .font(.system(size: 13))
                            .foregroundStyle(YPtunColor.textSecondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 4)
                .padding(.top, 8)

                // Search Bar
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 15))
                        .foregroundStyle(YPtunColor.textMuted)
                    TextField("Поиск настроек…", text: $searchText)
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
                .padding(.bottom, 4)

                // 1. Connection
                if matchesSearch("подключение", "режим", "socks", "прокси", "авто", "туннель") {
                    SettingsCaption("Подключение")
                    SettingsCard {
                        NavigationLink {
                            ConnectionSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Параметры подключения",
                                subtitle: "Режим VPN/Прокси, SOCKS5-порт, автоподключение",
                                icon: "bolt.shield.fill",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            RoutingSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Маршрутизация",
                                subtitle: "Обход РФ (.ru), блокировка рекламы, пресеты",
                                icon: "arrow.triangle.branch",
                                iconColor: .blue
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            TrafficSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Настройки трафика",
                                subtitle: "DNS, MTU, TCP Mux, TLS-фрагментация",
                                icon: "point.3.connected.trianglepath.dotted",
                                iconColor: YPtunColor.accent
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 2. Appearance & List View
                if matchesSearch("вид", "оформление", "тема", "пинг", "скорость", "авто", "список") {
                    SettingsCaption("Отображение и интерфейс")
                    SettingsCard {
                        NavigationLink {
                            AppearanceSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Вид списка и главный экран",
                                subtitle: "Кнопка «Авто», автовыбор ⚡, двухколоночный режим",
                                icon: "rectangle.grid.1x2.fill",
                                iconColor: .yellow
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            PingSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Тест задержки и пинг",
                                subtitle: "Формат результата, потоки, таймаут проверки",
                                icon: "timer",
                                iconColor: YPtunColor.pingGreen
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 3. Subscriptions
                if matchesSearch("подписки", "сервера", "конфигурации", "ссылки", "yptun") {
                    SettingsCaption("Подписки и конфигурации")
                    SettingsCard {
                        NavigationLink {
                            SubscriptionsSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Управление подписками",
                                subtitle: "Период автообновления, ссылки, защита от удаления",
                                icon: "dot.radiowaves.left.and.right",
                                iconColor: .cyan
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            SubscriptionLocationsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Все локации из подписок",
                                subtitle: "\(model.locations.filter(\.isFromSubscription).count) серверов добавлено",
                                icon: "globe",
                                iconColor: .indigo
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 4. System & Diagnostics
                if matchesSearch("логи", "журнал", "сессия", "эксперимент", "ядра", "json") {
                    SettingsCaption("Система и диагностика")
                    SettingsCard {
                        NavigationLink {
                            TunnelLogView()
                        } label: {
                            SettingsCardRow(
                                title: "Журнал логов",
                                subtitle: "Логи сетевого туннеля и внутренних ядер",
                                icon: "text.alignleft",
                                iconColor: .gray
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            SessionOverviewView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Информация о сессии",
                                subtitle: "Текущий IP, статус соединения и протокол",
                                icon: "info.circle.fill",
                                iconColor: .blue
                            )
                        }
                        .buttonStyle(.plain)

                        SettingsCardDivider()

                        NavigationLink {
                            ExperimentalSettingsView(model: model)
                        } label: {
                            SettingsCardRow(
                                title: "Экспериментальные функции",
                                subtitle: "Выбор ядра (sing-box/Xray), VK Turn, сырой JSON",
                                icon: "flask.fill",
                                iconColor: .orange
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // 5. Support Project
                if matchesSearch("поддержать", "донат", "звезда", "ton", "usdt", "разработка") {
                    SettingsCaption("Поддержать проект")
                    SettingsCard {
                        NavigationLink {
                            SupportProjectView()
                        } label: {
                            SettingsCardRow(
                                title: "Поддержать развитие YPtun",
                                subtitle: "Криптовалюта (TON/USDT), карты РФ, звезда на GitHub",
                                icon: "heart.fill",
                                iconColor: .pink
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                // About Footer
                VStack(spacing: 6) {
                    Text("YPtun для iOS · Версия 4.0.0 (507)")
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
        .navigationBarTitleDisplayMode(.inline)
        .tint(YPtunColor.accent)
        .preferredColorScheme(.dark)
    }

    private func matchesSearch(_ terms: String...) -> Bool {
        guard !searchText.isEmpty else { return true }
        let query = searchText.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        return terms.contains { $0.lowercased().contains(query) }
    }
}
