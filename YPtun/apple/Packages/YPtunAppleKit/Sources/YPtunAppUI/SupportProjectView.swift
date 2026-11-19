import SwiftUI

public struct SupportProjectView: View {
    public init() {}

    private let tonAddress = "UQAPC9J9UY8oaYV4AwjEAYIIJMswo7qVzJDkf4pzY8kVtzJ-"
    private let donationAlertsUrl = "https://www.donationalerts.com/r/yanisplugg"
    private let githubUrl = "https://github.com/yanisplugg/yptun"
    private let vpnBotUrl = "https://t.me/quofortpostbot"

    @State private var copiedNotice = false

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Header Banner
                SettingsCard {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 12) {
                            Image(systemName: "heart.circle.fill")
                                .font(.system(size: 32))
                                .foregroundStyle(.pink)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Поддержать проект YPtun")
                                    .font(.system(size: 17, weight: .bold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text("Проект разрабатывается открыто и бесплатно. Ваша поддержка помогает содержать серверы и развивать приложение.")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YPtunColor.textSecondary)
                            }
                        }
                    }
                    .padding(16)
                }

                // Crypto
                SettingsCaption("Криптовалюта (TON / USDT / GRAM)")
                SettingsCard {
                    Button {
                        YPtunPasteboard.setString(tonAddress)
                        copiedNotice = true
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "bitcoinsign.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color.cyan)
                                .frame(width: 36, height: 36)
                                .background(Color.cyan.opacity(0.14), in: Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text("TON Кошелек (USDT · TON · GRAM)")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text(tonAddress)
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundStyle(YPtunColor.textSecondary)
                                    .lineLimit(1)
                            }
                            Spacer()

                            Image(systemName: "doc.on.doc.fill")
                                .font(.system(size: 14))
                                .foregroundStyle(YPtunColor.accent)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                // DonationAlerts
                SettingsCaption("Карты РФ и СБП")
                SettingsCard {
                    Link(destination: URL(string: donationAlertsUrl)!) {
                        HStack(spacing: 14) {
                            Image(systemName: "creditcard.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color.orange)
                                .frame(width: 36, height: 36)
                                .background(Color.orange.opacity(0.14), in: Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text("DonationAlerts")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text("Оплата картами любых банков РФ, СБП и кошельками")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YPtunColor.textSecondary)
                            }
                            Spacer()

                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(YPtunColor.textMuted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                // Community & Star
                SettingsCaption("Бесплатная поддержка и сообщество")
                SettingsCard {
                    Link(destination: URL(string: githubUrl)!) {
                        HStack(spacing: 14) {
                            Image(systemName: "star.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color.yellow)
                                .frame(width: 36, height: 36)
                                .background(Color.yellow.opacity(0.14), in: Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text("Поставить звезду на GitHub")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text("Бесплатный способ поддержать наш открытый репозиторий")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YPtunColor.textSecondary)
                            }
                            Spacer()

                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(YPtunColor.textMuted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    SettingsCardDivider()

                    Link(destination: URL(string: vpnBotUrl)!) {
                        HStack(spacing: 14) {
                            Image(systemName: "paperplane.circle.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color.blue)
                                .frame(width: 36, height: 36)
                                .background(Color.blue.opacity(0.14), in: Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text("Telegram-бот автора")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                                Text("@quofortpostbot · Подписки и новости")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YPtunColor.textSecondary)
                            }
                            Spacer()

                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(YPtunColor.textMuted)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(YPtunColor.canvas.ignoresSafeArea())
        .navigationTitle("Поддержать проект")
        .alert("Адрес скопирован", isPresented: $copiedNotice) {
            Button("OK", role: .cancel) { copiedNotice = false }
        } message: {
            Text("Адрес TON-кошелька скопирован в буфер обмена.")
        }
    }
}
