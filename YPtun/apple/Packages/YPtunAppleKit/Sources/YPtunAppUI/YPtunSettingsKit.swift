import SwiftUI

public struct SettingsCaption: View {
    let title: String

    public init(_ title: String) {
        self.title = title
    }

    public var body: some View {
        Text(title.uppercased())
            .font(.system(size: 12, weight: .bold))
            .tracking(0.8)
            .foregroundStyle(YPtunColor.textSecondary)
            .padding(.horizontal, 6)
            .padding(.top, 14)
            .padding(.bottom, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

public struct SettingsCard<Content: View>: View {
    let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 0) {
            content
        }
        .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.white.opacity(0.04), lineWidth: 1)
        )
    }
}

public struct SettingsCardDivider: View {
    public init() {}

    public var body: some View {
        Divider()
            .background(YPtunColor.cardSurfaceHigh.opacity(0.8))
            .padding(.leading, 56)
    }
}

public struct SettingsCardRow: View {
    let title: String
    let subtitle: String?
    let value: String?
    let icon: String?
    let iconColor: Color
    let showChevron: Bool
    let action: (() -> Void)?

    public init(
        title: String,
        subtitle: String? = nil,
        value: String? = nil,
        icon: String? = nil,
        iconColor: Color = YPtunColor.accent,
        showChevron: Bool = true,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.icon = icon
        self.iconColor = iconColor
        self.showChevron = showChevron
        self.action = action
    }

    public var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: 14) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 32, height: 32)
                        .background(iconColor.opacity(0.14), in: Circle())
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(YPtunColor.textPrimary)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 12.5))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if let value, !value.isEmpty {
                    Text(value)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(YPtunColor.textSecondary)
                        .lineLimit(1)
                }

                if showChevron && action != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(YPtunColor.textMuted)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

public struct SettingsCardToggle: View {
    let title: String
    let subtitle: String?
    let icon: String?
    let iconColor: Color
    @Binding var isOn: Bool

    public init(
        title: String,
        subtitle: String? = nil,
        icon: String? = nil,
        iconColor: Color = YPtunColor.accent,
        isOn: Binding<Bool>
    ) {
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
        self.iconColor = iconColor
        self._isOn = isOn
    }

    public var body: some View {
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: 14) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 32, height: 32)
                        .background(iconColor.opacity(0.14), in: Circle())
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(YPtunColor.textPrimary)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 12.5))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .tint(YPtunColor.accent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

public struct SettingsChoiceChips<T: Hashable>: View {
    let options: [(T, String)]
    @Binding var selected: T

    public init(options: [(T, String)], selected: Binding<T>) {
        self.options = options
        self._selected = selected
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { item in
                    let isChosen = item.0 == selected
                    Button {
                        selected = item.0
                    } label: {
                        Text(item.1)
                            .font(.system(size: 13, weight: isChosen ? .bold : .medium))
                            .foregroundStyle(isChosen ? Color.black : YPtunColor.textPrimary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(
                                isChosen ? YPtunColor.accent : YPtunColor.cardSurfaceHigh,
                                in: Capsule()
                            )
                            .overlay(
                                Capsule()
                                    .stroke(isChosen ? Color.clear : Color.white.opacity(0.08), lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 6)
        }
    }
}

public struct SettingsNote: View {
    let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(.system(size: 12.5))
            .foregroundStyle(YPtunColor.textMuted)
            .padding(.horizontal, 6)
            .padding(.top, 4)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

public struct SettingsStepperRow: View {
    let title: String
    let subtitle: String?
    let value: Int
    let range: ClosedRange<Int>
    let unit: String
    let onChange: (Int) -> Void

    public init(
        title: String,
        subtitle: String? = nil,
        value: Int,
        range: ClosedRange<Int>,
        unit: String = "",
        onChange: @escaping (Int) -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.range = range
        self.unit = unit
        self.onChange = onChange
    }

    public var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(YPtunColor.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 12.5))
                        .foregroundStyle(YPtunColor.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                Button {
                    if value > range.lowerBound { onChange(value - 1) }
                } label: {
                    Text("−")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(value > range.lowerBound ? YPtunColor.textPrimary : YPtunColor.textMuted)
                        .frame(width: 32, height: 32)
                        .background(YPtunColor.cardSurfaceHigh, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(value <= range.lowerBound)

                Text("\(value)\(unit.isEmpty ? "" : " \(unit)")")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .foregroundStyle(YPtunColor.accent)
                    .frame(minWidth: 38, alignment: .center)

                Button {
                    if value < range.upperBound { onChange(value + 1) }
                } label: {
                    Text("+")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(value < range.upperBound ? YPtunColor.textPrimary : YPtunColor.textMuted)
                        .frame(width: 32, height: 32)
                        .background(YPtunColor.cardSurfaceHigh, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(value >= range.upperBound)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}
