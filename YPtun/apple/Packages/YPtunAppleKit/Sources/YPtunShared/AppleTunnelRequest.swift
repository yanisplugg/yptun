import Foundation

public struct AppleTunnelRequest: Codable, Equatable, Sendable {
    public let locationJSON: String
    public let trafficJSON: String
    public let routingJSON: String
    public let routingProfilesJSON: String
    public let appBehaviorJSON: String
    public let warpConfig: String
    public let deviceID: String

    public init(
        locationJSON: String,
        trafficJSON: String,
        routingJSON: String,
        routingProfilesJSON: String,
        appBehaviorJSON: String,
        warpConfig: String,
        deviceID: String
    ) {
        self.locationJSON = locationJSON
        self.trafficJSON = trafficJSON
        self.routingJSON = routingJSON
        self.routingProfilesJSON = routingProfilesJSON
        self.appBehaviorJSON = appBehaviorJSON
        self.warpConfig = warpConfig
        self.deviceID = deviceID
    }

    private enum CodingKeys: String, CodingKey {
        case locationJSON
        case trafficJSON
        case routingJSON
        case routingProfilesJSON
        case appBehaviorJSON
        case warpConfig
        case deviceID
        case deviceId
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.locationJSON = try container.decode(String.self, forKey: .locationJSON)
        self.trafficJSON = (try? container.decode(String.self, forKey: .trafficJSON)) ?? "{}"
        self.routingJSON = (try? container.decode(String.self, forKey: .routingJSON)) ?? "{}"
        self.routingProfilesJSON = (try? container.decode(String.self, forKey: .routingProfilesJSON)) ?? "{}"
        self.appBehaviorJSON = (try? container.decode(String.self, forKey: .appBehaviorJSON)) ?? "{}"
        self.warpConfig = (try? container.decode(String.self, forKey: .warpConfig)) ?? ""
        self.deviceID = (try? container.decode(String.self, forKey: .deviceID))
            ?? (try? container.decode(String.self, forKey: .deviceId)) ?? ""
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(locationJSON, forKey: .locationJSON)
        try container.encode(trafficJSON, forKey: .trafficJSON)
        try container.encode(routingJSON, forKey: .routingJSON)
        try container.encode(routingProfilesJSON, forKey: .routingProfilesJSON)
        try container.encode(appBehaviorJSON, forKey: .appBehaviorJSON)
        try container.encode(warpConfig, forKey: .warpConfig)
        try container.encode(deviceID, forKey: .deviceID)
        try container.encode(deviceID, forKey: .deviceId)
    }
}

public enum AppleTunnelStore {
    public static let requestFileName = "tunnel_request.json"
    public static let errorFileName = "tunnel_error.txt"
    public static let logFileName = "tunnel.log"
    public static let cacheFileName = "singbox_cache.db"
    public static let statusFileName = "tunnel_status.txt"
    public static let connectedAtFileName = "tunnel_connected_at.txt"

    public static func directory(fileManager: FileManager = .default) throws -> URL {
        let appGroupIdentifier: String
        #if os(macOS)
        appGroupIdentifier = YPtunIdentity.MacOS.appGroupIdentifier
        #else
        appGroupIdentifier = YPtunIdentity.IOS.appGroupIdentifier
        #endif
        let base = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
        guard let base else { throw AppleTunnelStoreError.containerUnavailable }
        let directory = base.appendingPathComponent("yptun", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func url(
        for fileName: String,
        fileManager: FileManager = .default
    ) throws -> URL {
        try directory(fileManager: fileManager)
            .appendingPathComponent(fileName, isDirectory: false)
    }

    public static func read(_ fileName: String) throws -> Data? {
        let fileURL = try url(for: fileName)
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try Data(contentsOf: fileURL)
    }

    public static func readText(_ fileName: String) throws -> String? {
        guard let data = try read(fileName) else { return nil }
        guard let value = String(data: data, encoding: .utf8) else {
            throw AppleTunnelStoreError.invalidUTF8(fileName)
        }
        return value
    }

    public static func write(_ data: Data, to fileName: String) throws {
        try data.write(to: url(for: fileName), options: .atomic)
    }

    public static func writeText(_ value: String, to fileName: String) throws {
        try write(Data(value.utf8), to: fileName)
    }

    public static func remove(_ fileName: String) throws {
        let fileURL = try url(for: fileName)
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        try FileManager.default.removeItem(at: fileURL)
    }

    public static func appendLog(_ line: String) throws {
        let fileURL = try url(for: logFileName)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
               let size = attrs[.size] as? UInt64, size > 256 * 1024 {
                try? remove(logFileName)
                try? write(Data("[log rotated]\n".utf8), to: logFileName)
            }
        } else {
            try write(Data(), to: logFileName)
        }
        let handle = try FileHandle(forWritingTo: fileURL)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("\(line)\n".utf8))
    }

    public static func loadRequest() throws -> AppleTunnelRequest {
        guard let data = try read(requestFileName) else {
            throw AppleTunnelStoreError.requestMissing
        }
        return try JSONDecoder().decode(AppleTunnelRequest.self, from: data)
    }

    public static func saveRequest(_ request: AppleTunnelRequest) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try write(encoder.encode(request), to: requestFileName)
    }

    public static func clearRequest() throws {
        try remove(requestFileName)
    }
}

public enum AppleTunnelStoreError: LocalizedError, Equatable, Sendable {
    case containerUnavailable
    case invalidUTF8(String)
    case requestMissing

    public var errorDescription: String? {
        switch self {
        case .containerUnavailable: "App Group container is unavailable."
        case let .invalidUTF8(file): "The shared file \(file) is not valid UTF-8."
        case .requestMissing: "No active tunnel request was saved."
        }
    }
}
