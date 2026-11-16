import SwiftUI
import YPtunShared

public struct SubscriptionCardView: View {
    let title: String
    let subscriptionID: String?
    let locations: [LocationListItem]
    let selectedLocationID: String?
    let savedPings: [String: AppleSavedPing]
    let onSelect: (LocationListItem) -> Void
    let onEdit: (LocationListItem) -> Void
    let onDelete: (LocationListItem) -> Void
    let onRefreshSubscription: (String) -> Void
    let onPingAll: ([String]) -> Void

    @State private var isExpanded = true

    public init(
        title: String,
        subscriptionID: String?,
        locations: [LocationListItem],
        selectedLocationID: String?,
        savedPings: [String: AppleSavedPing],
        onSelect: @escaping (LocationListItem) -> Void,
        onEdit: @escaping (LocationListItem) -> Void,
        onDelete: @escaping (LocationListItem) -> Void,
        onRefreshSubscription: @escaping (String) -> Void,
        onPingAll: @escaping ([String]) -> Void
    ) {
        self.title = title
        self.subscriptionID = subscriptionID
        self.locations = locations
        self.selectedLocationID = selectedLocationID
        self.savedPings = savedPings
        self.onSelect = onSelect
        self.onEdit = onEdit
        self.onDelete = onDelete
        self.onRefreshSubscription = onRefreshSubscription
        self.onPingAll = onPingAll
    }

    private var sampleItem: LocationListItem? {
        locations.first
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Subscription Header
            HStack(spacing: 8) {
                if let isPinned = sampleItem?.isPinned, isPinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 13))
                        .foregroundStyle(.orange)
                }

                if let icon = sampleItem?.icon, !icon.isEmpty {
                    Text(icon)
                        .font(.system(size: 16))
                } else {
                    Image(systemName: "bolt.horizontal.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(YPtunColor.accent)
                }

                Text(title)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(YPtunColor.textPrimary)
                    .lineLimit(1)

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                } label: {
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(YPtunColor.textSecondary)
                }
                .buttonStyle(.plain)

                Spacer()

                // Actions: ⚡ Auto-pick / Ping all, ⏱️ Latency, ⋮ Menu
                Button {
                    onPingAll(locations.map(\.id))
                } label: {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(YPtunColor.accent)
                        .frame(width: 32, height: 32)
                        .background(YPtunColor.cardSurfaceHigh, in: Circle())
                }
                .buttonStyle(.plain)

                if let subID = subscriptionID {
                    Menu {
                        Button {
                            onRefreshSubscription(subID)
                        } label: {
                            Label("Обновить подписку", systemImage: "arrow.clockwise")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 14))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .frame(width: 32, height: 32)
                            .background(YPtunColor.cardSurfaceHigh, in: Circle())
                    }
                    .buttonStyle(.plain)
                }
            }

            // Metadata Lines (Email, Refresh Date, Interval)
            if let email = sampleItem?.supportEmail, !email.isEmpty {
                Text("Почта: \(email)")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(YPtunColor.textSecondary)
            }

            if let refreshMs = sampleItem?.lastRefreshEpochMs, refreshMs > 0 {
                Text("Обновлена \(formattedRefreshDate(refreshMs))")
                    .font(.system(size: 12))
                    .foregroundStyle(YPtunColor.textMuted)
            }

            if let interval = sampleItem?.updateIntervalHours, interval > 0 {
                Text("Обновление каждые \(interval) ч")
                    .font(.system(size: 12))
                    .foregroundStyle(YPtunColor.textMuted)
            }

            // Traffic Usage Progress Bar
            let used = sampleItem?.trafficUsed ?? ""
            let total = sampleItem?.trafficAvailable ?? "∞"
            if !used.isEmpty || !total.isEmpty {
                HStack(spacing: 8) {
                    Spacer()
                    Text("\(used.isEmpty ? "0 GB" : used) / \(total)")
                        .font(.system(size: 13, weight: .semibold, design: .rounded))
                        .foregroundStyle(YPtunColor.textPrimary)
                    Image(systemName: "globe")
                        .font(.system(size: 13))
                        .foregroundStyle(YPtunColor.accent)
                    Spacer()
                }
                .padding(.vertical, 8)
                .background(
                    LinearGradient(
                        colors: [YPtunColor.accentDeep.opacity(0.8), YPtunColor.accentPurple.opacity(0.8)],
                        startPoint: .leading,
                        endPoint: .trailing
                    ),
                    in: Capsule()
                )
            }

            // Locations and Custom Groups
            if isExpanded {
                let groups = groupedLocations
                ForEach(groups.keys.sorted(), id: \.self) { groupKey in
                    if groupKey != "__default__" {
                        // Nested custom group (e.g. "Белые списки")
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 6) {
                                Image(systemName: "folder.fill")
                                    .font(.system(size: 13))
                                    .foregroundStyle(YPtunColor.accent)
                                Text(groupKey)
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(YPtunColor.textPrimary)
                            }
                            .padding(.horizontal, 4)
                            .padding(.top, 4)

                            ForEach(groups[groupKey] ?? []) { location in
                                LocationRowView(
                                    location: location,
                                    isSelected: location.id == selectedLocationID,
                                    savedPing: savedPings[location.id],
                                    onSelect: { onSelect(location) },
                                    onEdit: { onEdit(location) },
                                    onDelete: { onDelete(location) }
                                )
                            }
                        }
                        .padding(10)
                        .background(YPtunColor.groupCard, in: RoundedRectangle(cornerRadius: YPtunRadius.card))
                    } else {
                        // Regular ungrouped locations
                        ForEach(groups["__default__"] ?? []) { location in
                            LocationRowView(
                                location: location,
                                isSelected: location.id == selectedLocationID,
                                savedPing: savedPings[location.id],
                                onSelect: { onSelect(location) },
                                onEdit: { onEdit(location) },
                                onDelete: { onDelete(location) }
                            )
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: YPtunRadius.card))
    }

    private var groupedLocations: [String: [LocationListItem]] {
        var result: [String: [LocationListItem]] = [:]
        for item in locations {
            let key = item.customGroup ?? "__default__"
            result[key, default: []].append(item)
        }
        return result
    }

    private func formattedRefreshDate(_ refreshMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(refreshMs) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM.yyyy HH:mm:ss"
        return formatter.string(from: date)
    }
}
