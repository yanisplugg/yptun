import ActivityKit
import Foundation

@available(iOS 16.2, *)
public struct VpnActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var isConnected: Bool
        public var serverName: String
        public var flag: String
        public var connectedDate: Date?

        public init(isConnected: Bool, serverName: String, flag: String, connectedDate: Date?) {
            self.isConnected = isConnected
            self.serverName = serverName
            self.flag = flag
            self.connectedDate = connectedDate
        }
    }

    public var appName: String

    public init(appName: String = "YPtun") {
        self.appName = appName
    }
}
