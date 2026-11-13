public enum YPtunIdentity: Sendable {
    public static let displayName = "YPtun"

    public enum IOS: Sendable {
        public static let appBundleIdentifier = "org.yptun.app"
        public static let tunnelBundleIdentifier = "org.yptun.app.tunnel"
        public static let widgetBundleIdentifier = "org.yptun.app.widget"
        public static let appGroupIdentifier = "group.org.yptun.app"
    }

    public enum MacOS: Sendable {
        public static let appBundleIdentifier = "org.yptun.app.macos"
        public static let tunnelBundleIdentifier = "org.yptun.app.macos.tunnel"
        public static let appGroupIdentifier = "group.org.yptun.app.macos"
    }
}

public enum VPNConnectionState: String, Codable, Sendable {
    case disconnected
    case connecting
    case connected
    case disconnecting
}

public struct LocationListItem: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let detail: String
    public let isComplete: Bool
    public let needsUserAction: Bool
    public let isFromSubscription: Bool
    public let subscriptionID: String?
    public let subscriptionName: String?
    public let icon: String?
    public let isPinned: Bool
    public let isLocked: Bool

    public init(
        id: String,
        name: String,
        detail: String,
        isComplete: Bool,
        needsUserAction: Bool,
        isFromSubscription: Bool,
        subscriptionID: String? = nil,
        subscriptionName: String? = nil,
        icon: String? = nil,
        isPinned: Bool = false,
        isLocked: Bool = false
    ) {
        self.id = id
        self.name = name
        self.detail = detail
        self.isComplete = isComplete
        self.needsUserAction = needsUserAction
        self.isFromSubscription = isFromSubscription
        self.subscriptionID = subscriptionID
        self.subscriptionName = subscriptionName
        self.icon = icon
        self.isPinned = isPinned
        self.isLocked = isLocked
    }
}
