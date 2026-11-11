import Foundation
import YPtunShared

public struct AppleTunnelRequest: Codable, Equatable, Sendable {
    public let locationJSON: String
    public let trafficJSON: String
    public let routingJSON: String
    public let routingProfilesJSON: String
    public let appBehaviorJSON: String
    public let deviceID: String

    public init(
        locationJSON: String,
        trafficJSON: String,
        routingJSON: String,
        routingProfilesJSON: String,
        appBehaviorJSON: String,
        deviceID: String
    ) {
        self.locationJSON = locationJSON
        self.trafficJSON = trafficJSON
        self.routingJSON = routingJSON
        self.routingProfilesJSON = routingProfilesJSON
        self.appBehaviorJSON = appBehaviorJSON
        self.deviceID = deviceID
    }
}

public enum AppleTunnelStore {
    public static let requestFileName = "tunnel_request.json"
    public static let errorFileName = "tunnel_error.txt"
    public static let logFileName = "tunnel.log"
    public static let cacheFileName = "singbox_cache.db"

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
