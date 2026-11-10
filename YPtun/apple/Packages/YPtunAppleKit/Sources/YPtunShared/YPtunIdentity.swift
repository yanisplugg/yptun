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
