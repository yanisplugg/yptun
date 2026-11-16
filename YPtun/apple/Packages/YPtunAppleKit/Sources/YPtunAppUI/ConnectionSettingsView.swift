import SwiftUI
import YPtunMigrationSupport

public struct ConnectionSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var autoConnectOnLaunch = false
    @State private var energySaver = false
    @State private var socksProxyPort = 10808
    @State private var socksProxyHost = "127.0.0.1"
    @State private var socksUsername = ""
    @State private var socksPassword = ""
    @State private var selectedMode = 0 // 0: VPN (TUN), 1: SOCKS Proxy
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Connection Mode
                SettingsCaption("Режим работы")
                SettingsCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Picker("Режим", selection: $selectedMode) {
                            Text("VPN (Туннель всего устройства)").tag(0)
                            Text("Только SOCKS-прокси").tag(1)
                        }
                        .pickerStyle(.segmented)
                        .padding(12)

                        Text(selectedMode == 0
                             ? "Весь трафик устройства направляется через системный сетевой туннель Network Extension."
                             : "Системный VPN не создается. Локальный SOCKS5-прокси слушает порт для Telegram, браузеров и других приложений.")
                            .font(.system(size: 12.5))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 12)
                    }
                }

                // Automation
                SettingsCaption("Автоматизация и питание")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Автоподключение при старте",
                        subtitle: "Автоматически подключаться к выбранному серверу при запуске YPtun",
                        icon: "bolt.badge.automatic.fill",
                        iconColor: .orange,
                        isOn: $autoConnectOnLaunch
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Энергосбережение",
                        subtitle: "Снижает частоту фоновых проверок соединения для экономии батареи",
                        icon: "battery.100.bolt",
                        iconColor: .green,
                        isOn: $energySaver
                    )
                }

                // SOCKS5 Proxy
                SettingsCaption("Встроенный SOCKS5-прокси")
                SettingsCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Хост")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Text("Локальный IP-адрес для прослушивания")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                        Spacer()
                        TextField("127.0.0.1", text: $socksProxyHost)
                            .font(.system(size: 14, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 120)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)

                    SettingsCardDivider()

                    SettingsStepperRow(
                        title: "Порт SOCKS5",
                        subtitle: "Стандартный порт: 10808 (диапазон 1024–65535)",
                        value: socksProxyPort,
                        range: 1024...65535,
                        onChange: { socksProxyPort = $0 }
                    )

                    SettingsCardDivider()

                    HStack {
                        Text("Логин (опционально)")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                        Spacer()
                        TextField("Без логина", text: $socksUsername)
                            .font(.system(size: 14))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 140)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)

                    SettingsCardDivider()

                    HStack {
                        Text("Пароль (опционально)")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                        Spacer()
                        SecureField("Без пароля", text: $socksPassword)
                            .font(.system(size: 14))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 140)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }

                SettingsNote("Порт 10808 открывается локально на устройстве и может использоваться в Telegram (Настройки → Данные и память → Прокси → Добавить SOCKS5).")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Параметры подключения")
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

        autoConnectOnLaunch = obj["autoConnectOnLaunch"] as? Bool ?? false
        energySaver = obj["energySaver"] as? Bool ?? false
        socksProxyPort = obj["socksProxyPort"] as? Int ?? 10808
        socksProxyHost = obj["socksProxyHost"] as? String ?? "127.0.0.1"
        socksUsername = obj["socksUsername"] as? String ?? ""
        socksPassword = obj["socksPassword"] as? String ?? ""
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

        dict["autoConnectOnLaunch"] = autoConnectOnLaunch
        dict["energySaver"] = energySaver
        dict["socksProxyPort"] = socksProxyPort
        dict["socksProxyHost"] = socksProxyHost
        dict["socksUsername"] = socksUsername
        dict["socksPassword"] = socksPassword

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .appBehavior)
        }
    }
}
