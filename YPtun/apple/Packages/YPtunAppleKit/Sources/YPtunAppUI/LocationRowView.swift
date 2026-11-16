import SwiftUI
import YPtunShared

public struct LocationRowView: View {
    let location: LocationListItem
    let isSelected: Bool
    let savedPing: AppleSavedPing?
    let onSelect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void

    public init(
        location: LocationListItem,
        isSelected: Bool,
        savedPing: AppleSavedPing?,
        onSelect: @escaping () -> Void,
        onEdit: @escaping () -> Void,
        onDelete: @escaping () -> Void
    ) {
        self.location = location
        self.isSelected = isSelected
        self.savedPing = savedPing
        self.onSelect = onSelect
        self.onEdit = onEdit
        self.onDelete = onDelete
    }

    public var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                // Flag / Emoji / Icon
                if !location.flagEmoji.isEmpty {
                    Text(location.flagEmoji)
                        .font(.system(size: 22))
                        .frame(width: 32, alignment: .center)
                } else if let icon = location.icon, !icon.isEmpty {
                    Text(icon)
                        .font(.system(size: 20))
                        .frame(width: 32, alignment: .center)
                } else {
                    Image(systemName: location.isFromSubscription ? "network" : "bolt.shield")
                        .font(.system(size: 18))
                        .foregroundStyle(isSelected ? YPtunColor.accent : YPtunColor.textSecondary)
                        .frame(width: 32, alignment: .center)
                }

                // Name and Protocol / Endpoint subtitle
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(location.cleanName)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)

                        if location.isPinned {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(.orange)
                        }
                        if location.isLocked {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                    }

                    HStack(spacing: 6) {
                        Text(location.protocolTag)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .lineLimit(1)

                        if location.needsUserAction {
                            Text("· Требуется действие")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // Ping Result (matches Android LocationRow 1:1)
                if let savedPing {
                    switch savedPing {
                    case let .latency(ms):
                        HStack(spacing: 4) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.pingGreen)
                            Text("\(ms) ms")
                                .font(.system(size: 12, weight: .semibold, design: .rounded))
                                .foregroundStyle(YPtunColor.pingGreen)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(YPtunColor.pingGreenBg, in: Capsule())

                    case .offline:
                        HStack(spacing: 4) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.offlineRed)
                            Text("offline")
                                .font(.system(size: 12, weight: .semibold, design: .rounded))
                                .foregroundStyle(YPtunColor.offlineRed)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(YPtunColor.offlineRedBg, in: Capsule())
                    }
                } else {
                    Text("—")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(YPtunColor.textSecondary)
                        .padding(.horizontal, 4)
                }

                // Three dots context menu
                Menu {
                    Button(action: onEdit) {
                        Label("Редактировать", systemImage: "pencil")
                    }
                    Button(role: .destructive, action: onDelete) {
                        Label("Удалить", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(YPtunColor.textSecondary)
                        .frame(width: 28, height: 36)
                }
                .buttonStyle(.plain)

                // Radio selection indicator on the far right (matches Screenshot 7)
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(YPtunColor.accent)
                } else {
                    Circle()
                        .strokeBorder(Color.white.opacity(0.35), lineWidth: 2)
                        .frame(width: 22, height: 22)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .frame(minHeight: 64)
            .background(isSelected ? YPtunColor.cardSurfaceSelected : YPtunColor.cardSurfaceHigh)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? YPtunColor.accent : Color.clear, lineWidth: 1.5)
            )
        }
        .buttonStyle(.plain)
        .opacity(location.isComplete ? 1.0 : 0.6)
    }
}
