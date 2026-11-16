import SwiftUI
import YPtunMigrationSupport

public struct TrafficSettingsView: View {
    @ObservedObject var model: YPtunApplicationModel

    @State private var remoteDns = "8.8.8.8"
    @State private var remoteDns2 = "1.1.1.1"
    @State private var directDns = "77.88.8.8"
    @State private var domainStrategy = "ipv4_only"
    @State private var mtu = 1500
    @State private var blockRuDomains = false
    @State private var fakeDnsEnabled = false
    @State private var muxEnabled = false
    @State private var muxProtocol = "h2mux"
    @State private var muxMaxConnections = 8
    @State private var fragmentEnabled = false
    @State private var fragmentPackets = "tlshello"
    @State private var fragmentLength = "50-100"
    @State private var fragmentInterval = "10-20"
    @State private var isLoading = true
    @State private var isSaving = false

    public init(model: YPtunApplicationModel) {
        self.model = model
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // DNS
                SettingsCaption("DNS-серверы")
                SettingsCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Удалённый DNS (Основной)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Text("Используется для резолва доменов внутри туннеля")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                        Spacer()
                        TextField("8.8.8.8", text: $remoteDns)
                            .font(.system(size: 14, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 130)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)

                    SettingsCardDivider()

                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Резервный удалённый DNS")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Text("Запасной сервер резолва при сбоях основного")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                        Spacer()
                        TextField("1.1.1.1", text: $remoteDns2)
                            .font(.system(size: 14, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 130)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)

                    SettingsCardDivider()

                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Прямой DNS (Direct)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Text("Резолв локальных и исключённых сайтов")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                        Spacer()
                        TextField("77.88.8.8", text: $directDns)
                            .font(.system(size: 14, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 130)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }

                // Domain Strategy
                SettingsCaption("Стратегия разрешения IP-адресов")
                SettingsChoiceChips(
                    options: [
                        ("ipv4_only", "Только IPv4"),
                        ("prefer_ipv4", "Предпочитать IPv4"),
                        ("prefer_ipv6", "Предпочитать IPv6"),
                        ("ipv6_only", "Только IPv6")
                    ],
                    selected: $domainStrategy
                )

                // Network & MTU
                SettingsCaption("Сетевые параметры и MTU")
                SettingsCard {
                    SettingsStepperRow(
                        title: "MTU интерфейса",
                        subtitle: "Стандартный размер пакета (дефолт 1500, диапазон 1280–9000)",
                        value: mtu,
                        range: 1280...9000,
                        unit: "",
                        onChange: { mtu = $0 }
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Блокировать RU-домены",
                        subtitle: "Принудительно сбрасывать соединения к зоне .ru мимо туннеля",
                        icon: "xmark.shield.fill",
                        iconColor: .red,
                        isOn: $blockRuDomains
                    )
                    SettingsCardDivider()
                    SettingsCardToggle(
                        title: "Включить Fake DNS",
                        subtitle: "Выделение виртуальных IP из пула 198.18.0.0/15 для ускорения резолва",
                        icon: "bolt.horizontal.fill",
                        iconColor: .yellow,
                        isOn: $fakeDnsEnabled
                    )
                }

                // TCP Mux
                SettingsCaption("Мультиплексирование TCP (MUX)")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Использовать TCP MUX",
                        subtitle: "Объединяет несколько сетевых потоков в одно TCP-соединение",
                        icon: "arrow.triangle.merge",
                        iconColor: YPtunColor.accent,
                        isOn: $muxEnabled
                    )

                    if muxEnabled {
                        SettingsCardDivider()
                        HStack {
                            Text("Протокол Mux")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                            Picker("Протокол", selection: $muxProtocol) {
                                Text("h2mux").tag("h2mux")
                                Text("smux").tag("smux")
                                Text("yamux").tag("yamux")
                            }
                            .pickerStyle(.menu)
                            .tint(YPtunColor.accent)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)

                        SettingsCardDivider()
                        SettingsStepperRow(
                            title: "Максимум Mux соединений",
                            subtitle: "Лимит параллельных сессий внутри одного мультиплекс-потока",
                            value: muxMaxConnections,
                            range: 1...64,
                            onChange: { muxMaxConnections = $0 }
                        )
                    }
                }

                // TLS Fragmentation
                SettingsCaption("Фрагментация пакетов (TLS Fragment)")
                SettingsCard {
                    SettingsCardToggle(
                        title: "Включить фрагментацию TLS",
                        subtitle: "Разбивает пакет TLS ClientHello на части для обхода систем DPI",
                        icon: "scissors",
                        iconColor: .pink,
                        isOn: $fragmentEnabled
                    )

                    if fragmentEnabled {
                        SettingsCardDivider()
                        HStack {
                            Text("Тип пакетов")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                            TextField("tlshello", text: $fragmentPackets)
                                .font(.system(size: 14, design: .monospaced))
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 130)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)

                        SettingsCardDivider()
                        HStack {
                            Text("Размер фрагментов")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                            TextField("50-100", text: $fragmentLength)
                                .font(.system(size: 14, design: .monospaced))
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 130)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)

                        SettingsCardDivider()
                        HStack {
                            Text("Интервал (мс)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YPtunColor.textPrimary)
                            Spacer()
                            TextField("10-20", text: $fragmentInterval)
                                .font(.system(size: 14, design: .monospaced))
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 130)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }
                }
                SettingsNote("Настройки трафика валидируются Rust-ядром. При изменении MTU или DNS туннель будет автоматически перезапущен.")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Настройки трафика")
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
        guard let text = await model.settingsText(.traffic),
              let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        remoteDns = obj["remote_dns"] as? String ?? obj["remoteDns"] as? String ?? "8.8.8.8"
        remoteDns2 = obj["remote_dns2"] as? String ?? obj["remoteDns2"] as? String ?? "1.1.1.1"
        directDns = obj["direct_dns"] as? String ?? obj["directDns"] as? String ?? "77.88.8.8"
        domainStrategy = obj["domain_strategy"] as? String ?? obj["domainStrategy"] as? String ?? "ipv4_only"
        mtu = obj["mtu"] as? Int ?? 1500
        blockRuDomains = obj["block_ru_domains"] as? Bool ?? obj["blockRuDomains"] as? Bool ?? false
        fakeDnsEnabled = obj["fake_dns_enabled"] as? Bool ?? obj["fakeDnsEnabled"] as? Bool ?? false
        muxEnabled = obj["mux_enabled"] as? Bool ?? obj["muxEnabled"] as? Bool ?? false
        muxProtocol = obj["mux_protocol"] as? String ?? obj["muxProtocol"] as? String ?? "h2mux"
        muxMaxConnections = obj["mux_max_connections"] as? Int ?? obj["muxMaxConnections"] as? Int ?? 8
        fragmentEnabled = obj["fragment_enabled"] as? Bool ?? obj["fragmentEnabled"] as? Bool ?? false
        fragmentPackets = obj["fragment_packets"] as? String ?? obj["fragmentPackets"] as? String ?? "tlshello"
        fragmentLength = obj["fragment_length"] as? String ?? obj["fragmentLength"] as? String ?? "50-100"
        fragmentInterval = obj["fragment_interval"] as? String ?? obj["fragmentInterval"] as? String ?? "10-20"
    }

    @MainActor
    private func save() async {
        isSaving = true
        defer { isSaving = false }

        let dict: [String: Any] = [
            "remote_dns": remoteDns,
            "remote_dns2": remoteDns2,
            "direct_dns": directDns,
            "domain_strategy": domainStrategy,
            "mtu": mtu,
            "block_ru_domains": blockRuDomains,
            "fake_dns_enabled": fakeDnsEnabled,
            "mux_enabled": muxEnabled,
            "mux_protocol": muxProtocol,
            "mux_max_connections": muxMaxConnections,
            "fragment_enabled": fragmentEnabled,
            "fragment_packets": fragmentPackets,
            "fragment_length": fragmentLength,
            "fragment_interval": fragmentInterval
        ]

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            _ = await model.saveSettingsText(text, file: .traffic)
        }
    }
}
