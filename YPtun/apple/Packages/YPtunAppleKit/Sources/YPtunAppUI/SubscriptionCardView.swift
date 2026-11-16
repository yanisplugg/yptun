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

    // Keep all locations, unique by storage ID only
    private var uniqueLocations: [LocationListItem] {
        var seenIDs = Set<String>()
        var result: [LocationListItem] = []
        for loc in locations {
            if !seenIDs.contains(loc.id) {
                seenIDs.insert(loc.id)
                result.append(loc)
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
        // Outer Container: Single unified card matching Screenshot 7
        VStack(alignment: .leading, spacing: 10) {
            // Header Row: Chevron, Pin, Icon, Title, Alive Badge, ⚡, ⏱️, ⋮
            HStack(spacing: 8) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 13, weight: .bold))
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

                // Alive Counter Badge (e.g. "8/9" in accent color)
                if !uniqueLocations.isEmpty {
                    Text("\(aliveCount)/\(uniqueLocations.count)")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(YPtunColor.accent)
                }

                Spacer()

                // Action: ⚡ Auto-pick fastest in this subscription
                Button {
                    onPingAll(uniqueLocations.map(\.id))
                } label: {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(YPtunColor.accent)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)

                // Action: ⏱️ Ping all servers in this subscription
                Button {
                    onPingAll(uniqueLocations.map(\.id))
                } label: {
                    Image(systemName: "gauge.with.dots.needle.bottom.50percent")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(YPtunColor.textSecondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)

                // Action: ⋮ Overflow Menu
                if let subURL = subscriptionID, !subURL.isEmpty {
                    Menu {
                        Button {
                            isRefreshing = true
                            onRefreshSubscription(subURL)
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                isRefreshing = false
                            }
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
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(YPtunColor.textSecondary)
                            .frame(width: 28, height: 32)
                    }
                    .buttonStyle(.plain)
                }
            }

            // Metadata Lines (Screenshot 7: Почта, Обновлена, Каждые X ч)
            VStack(alignment: .leading, spacing: 3) {
                if let email = sampleItem?.supportEmail, !email.isEmpty {
                    HStack(spacing: 4) {
                        Text("Почта:")
                            .font(.system(size: 12))
                            .foregroundStyle(YPtunColor.textSecondary)
                        Text(email)
                            .font(.system(size: 12, weight: .medium))
                            .underline()
                            .foregroundStyle(YPtunColor.accent)
                    }
                }

                if let refreshMs = sampleItem?.lastRefreshEpochMs, refreshMs > 0 {
                    Text("Обновлена \(formattedRefreshDate(refreshMs))")
                        .font(.system(size: 11))
                        .foregroundStyle(YPtunColor.textMuted)
                }

                if let interval = sampleItem?.updateIntervalHours, interval > 0 {
                    Text("Обновление каждые \(interval) ч")
                        .font(.system(size: 11))
                        .foregroundStyle(YPtunColor.textMuted)
                }
            }

            // Traffic Usage Progress Bar (Capsule with accent purple fill, "896.8 GB / ∞ 🌐")
            let used = sampleItem?.trafficUsed ?? ""
            let total = sampleItem?.trafficAvailable ?? ""
            if !used.isEmpty || !total.isEmpty {
                trafficProgressBar(used: used.isEmpty ? "0 GB" : used, total: total.isEmpty ? "∞" : total)
            }

            // Expanded Server Sub-Cards (nested inside this same container)
            if isExpanded {
                VStack(spacing: 7) {
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
                .padding(.top, 4)
            }
        }
        .padding(14)
        .background(YPtunColor.groupCard, in: RoundedRectangle(cornerRadius: 18))
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .stroke(YPtunColor.groupBorder, lineWidth: 1.5)
        )
    }

    @ViewBuilder
    private func trafficProgressBar(used: String, total: String) -> some View {
        let fraction = calculateFraction(used: used, total: total)
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                // Background Track
                RoundedRectangle(cornerRadius: 12)
                    .fill(YPtunColor.cardSurfaceHigh)

                // Filled portion with accent color
                RoundedRectangle(cornerRadius: 12)
                    .fill(YPtunColor.accent)
                    .frame(width: proxy.size.width * max(0.05, min(1.0, fraction)))

                // Centered Label (matching Screenshot 7: "896.8 GB / ∞ 🌐")
                HStack(spacing: 6) {
                    Spacer()
                    Text("\(used) / \(total)")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)
                    Image(systemName: "globe")
                        .font(.system(size: 11))
                        .foregroundStyle(.white)
                    Spacer()
                }
            }
        }
        .frame(height: 26)
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
            return 1.0 // If unlimited or unknown, visually full bar in purple
        }
        return CGFloat(min(1.0, max(0.05, u / t)))
    }

    private func formattedRefreshDate(_ refreshMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(refreshMs) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM.yyyy HH:mm:ss"
        return formatter.string(from: date)
    }
}
