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
                // Flag / Emoji
                if !location.flagEmoji.isEmpty {
                    Text(location.flagEmoji)
                        .font(.system(size: 24))
                        .frame(width: 32, alignment: .center)
                } else if let icon = location.icon, !icon.isEmpty {
                    Text(icon)
                        .font(.system(size: 22))
                        .frame(width: 32, alignment: .center)
                } else {
                    Image(systemName: location.isFromSubscription ? "network" : "bolt.shield")
                        .font(.system(size: 20))
                        .foregroundStyle(isSelected ? YPtunColor.accent : YPtunColor.textSecondary)
                        .frame(width: 32, alignment: .center)
                }

                // Name and Protocol
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(location.cleanName)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(YPtunColor.textPrimary)
                            .lineLimit(1)

                        if location.isPinned {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 11))
                                .foregroundStyle(.orange)
                        }
                        if location.isLocked {
                            Image(systemName: "lock.fill")
                                .font(.system(size: 11))
                                .foregroundStyle(YPtunColor.textSecondary)
                        }
                    }

                    HStack(spacing: 6) {
                        Text(location.protocolTag)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .lineLimit(1)

                        if location.needsUserAction {
                            Text("· Требуется действие")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.orange)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                // Ping Badge
                if let savedPing {
                    switch savedPing {
                    case let .latency(ms):
                        HStack(spacing: 4) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.pingGreen)
                            Text("\(ms) ms")
                                .font(.system(size: 13, weight: .semibold, design: .rounded))
                                .foregroundStyle(YPtunColor.pingGreen)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(YPtunColor.pingGreenBg, in: Capsule())

                    case .offline:
                        HStack(spacing: 4) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(YPtunColor.offlineRed)
                            Text("offline")
                                .font(.system(size: 13, weight: .semibold, design: .rounded))
                                .foregroundStyle(YPtunColor.offlineRed)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(YPtunColor.offlineRedBg, in: Capsule())
                    }
                }

                // Context Menu
                Menu {
                    Button(action: onEdit) {
                        Label("Редактировать", systemImage: "pencil")
                    }
                    Button(role: .destructive, action: onDelete) {
                        Label("Удалить", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18))
                        .foregroundStyle(YPtunColor.textSecondary)
                        .frame(width: 32, height: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(isSelected ? YPtunColor.cardSurfaceSelected : YPtunColor.cardSurface)
            .clipShape(RoundedRectangle(cornerRadius: YPtunRadius.card))
            .overlay(
                RoundedRectangle(cornerRadius: YPtunRadius.card)
                    .stroke(isSelected ? YPtunColor.accent : Color.clear, lineWidth: 1.5)
            )
        }
        .buttonStyle(.plain)
        .opacity(location.isComplete ? 1.0 : 0.6)
    }
}
