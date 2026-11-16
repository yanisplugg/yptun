import SwiftUI
import YPtunShared
#if canImport(UIKit)
import UIKit
#endif

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
    let onDeleteSubscription: () -> Void

    @State private var isExpanded = true
    @State private var isRefreshing = false

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
        onPingAll: @escaping ([String]) -> Void,
        onDeleteSubscription: @escaping () -> Void = {}
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
        self.onDeleteSubscription = onDeleteSubscription
    }

    // Deduplicate servers so no server repeats within this card
    private var uniqueLocations: [LocationListItem] {
        var seenIDs = Set<String>()
        var seenConfigs = Set<String>()
        var result: [LocationListItem] = []
        for item in locations {
            let configKey = "\(item.cleanName)|\(item.protocolTag)"
            if !seenIDs.contains(item.id) && !seenConfigs.contains(configKey) {
                seenIDs.insert(item.id)
                seenConfigs.insert(configKey)
                result.append(item)
            }
        }
        return result
    }

    private var sampleItem: LocationListItem? {
        uniqueLocations.first
    }

    private var aliveCount: Int {
        uniqueLocations.filter { loc in
            guard let ping = savedPings[loc.id] else { return false }
            if case .latency = ping { return true }
            return false
        }.count
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Subscription Header Container (Material 3 surfaceContainer #161719)
            VStack(alignment: .leading, spacing: 8) {
                // Row 1: Chevron, Favicon, Title, Alive Badge, Auto-pick, Refresh, Menu
                HStack(spacing: 8) {
                    // Tap chevron or title collapses/expands
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            isExpanded.toggle()
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(YPtunColor.textSecondary)

                            if let isPinned = sampleItem?.isPinned, isPinned {
                                Image(systemName: "pin.fill")
                                    .font(.system(size: 12))
                                    .foregroundStyle(YPtunColor.offlineRed)
                            }

                            if let icon = sampleItem?.icon, !icon.isEmpty {
                                Text(icon)
                                    .font(.system(size: 16))
                            } else {
                                Image(systemName: "folder.fill")
                                    .font(.system(size: 14))
                                    .foregroundStyle(YPtunColor.accent)
                            }

                            Text(title)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(YPtunColor.textPrimary)
                                .lineLimit(1)
                        }
                    }
                    .buttonStyle(.plain)

                    // Alive Counter Badge (e.g. "9/9")
                    if !uniqueLocations.isEmpty {
                        Text("\(aliveCount)/\(uniqueLocations.count)")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .foregroundStyle(aliveCount > 0 ? YPtunColor.accent : YPtunColor.textSecondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(YPtunColor.cardSurfaceHigh, in: Capsule())
                    }

                    Spacer()

                    // Action: ⚡ Auto-pick fastest in this subscription
                    Button {
                        onPingAll(uniqueLocations.map(\.id))
                    } label: {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(YPtunColor.accent)
                            .frame(width: 30, height: 30)
                            .background(YPtunColor.cardSurfaceHigh, in: Circle())
                    }
                    .buttonStyle(.plain)

                    // Action: 🔄 Refresh subscription URL
                    if let subURL = subscriptionID, !subURL.isEmpty {
                        Button {
                            isRefreshing = true
                            onRefreshSubscription(subURL)
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                isRefreshing = false
                            }
                        } label: {
                            if isRefreshing {
                                ProgressView()
                                    .scaleEffect(0.7)
                                    .frame(width: 30, height: 30)
                            } else {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(YPtunColor.accent)
                                    .frame(width: 30, height: 30)
                                    .background(YPtunColor.cardSurfaceHigh, in: Circle())
                            }
                        }
                        .buttonStyle(.plain)
                        .disabled(isRefreshing)

                        // Action: ⋮ Overflow Menu
                        Menu {
                            Button {
                                onRefreshSubscription(subURL)
                            } label: {
                                Label("Обновить подписку", systemImage: "arrow.clockwise")
                            }

                            Button {
                                #if canImport(UIKit)
                                UIPasteboard.general.string = subURL
                                #endif
                            } label: {
                                Label("Скопировать ссылку", systemImage: "doc.on.doc")
                            }

                            Divider()

                            Button(role: .destructive) {
                                onDeleteSubscription()
                            } label: {
                                Label("Удалить подписку", systemImage: "trash")
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(YPtunColor.textSecondary)
                                .frame(width: 30, height: 30)
                                .background(YPtunColor.cardSurfaceHigh, in: Circle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Row 2: Metadata (Last refresh on left, update interval on right)
                HStack {
                    if let refreshMs = sampleItem?.lastRefreshEpochMs, refreshMs > 0 {
                        Text("Обновлена \(formattedRefreshDate(refreshMs))")
                            .font(.system(size: 11))
                            .foregroundStyle(YPtunColor.textMuted)
                    }
                    Spacer()
                    if let interval = sampleItem?.updateIntervalHours, interval > 0 {
                        Text("каждые \(interval) ч")
                            .font(.system(size: 11))
                            .foregroundStyle(YPtunColor.textMuted)
                    }
                }

                // Row 3: Traffic Usage Progress Bar (Android Compose TrafficProgressBar style)
                let used = sampleItem?.trafficUsed ?? ""
                let total = sampleItem?.trafficAvailable ?? ""
                if !used.isEmpty || !total.isEmpty {
                    trafficProgressBar(used: used.isEmpty ? "0 GB" : used, total: total.isEmpty ? "∞" : total)
                }
            }
            .padding(14)
            .background(YPtunColor.cardSurface, in: RoundedRectangle(cornerRadius: YPtunRadius.card))

            // Expanded Servers List (No inner duplicate groups)
            if isExpanded {
                VStack(spacing: 6) {
                    ForEach(uniqueLocations) { location in
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
                .padding(.horizontal, 2)
            }
        }
    }

    @ViewBuilder
    private func trafficProgressBar(used: String, total: String) -> some View {
        let fraction = calculateFraction(used: used, total: total)
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                // Background Track
                RoundedRectangle(cornerRadius: 12)
                    .fill(YPtunColor.cardSurfaceHigh)

                // Filled portion
                RoundedRectangle(cornerRadius: 12)
                    .fill(YPtunColor.accent)
                    .frame(width: proxy.size.width * max(0.05, min(1.0, fraction)))

                // Centered Label
                HStack(spacing: 6) {
                    Spacer()
                    Text("\(used) / \(total)")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(YPtunColor.textPrimary)
                    Image(systemName: "globe")
                        .font(.system(size: 11))
                        .foregroundStyle(YPtunColor.textPrimary)
                    Spacer()
                }
            }
        }
        .frame(height: 24)
        .padding(.vertical, 2)
    }

    private func calculateFraction(used: String, total: String) -> CGFloat {
        func parseBytes(_ str: String) -> Double? {
            let clean = str.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if clean.isEmpty || clean.contains("∞") { return nil }
            let scanner = Scanner(string: clean)
            guard let val = scanner.scanDouble() else { return nil }
            if clean.contains("TB") { return val * 1024 * 1024 * 1024 * 1024 }
            if clean.contains("GB") { return val * 1024 * 1024 * 1024 }
            if clean.contains("MB") { return val * 1024 * 1024 }
            if clean.contains("KB") { return val * 1024 }
            return val
        }
        guard let u = parseBytes(used), let t = parseBytes(total), t > 0 else {
            return 0.2
        }
        return CGFloat(min(1.0, u / t))
    }

    private func formattedRefreshDate(_ refreshMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(refreshMs) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM.yyyy HH:mm"
        return formatter.string(from: date)
    }
}
