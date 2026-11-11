import Foundation
import YPtunAppleDataMigration
import YPtunRustBridge
import YPtunShared
import YPtunTunnelSupport

public struct AppleApplicationSnapshot: Sendable {
    public let locations: [LocationListItem]
    public let activeLocationID: String?
    public let migrationMadeChanges: Bool
    public let migrationHasFailures: Bool

    public init(
        locations: [LocationListItem],
        activeLocationID: String?,
        migrationMadeChanges: Bool,
        migrationHasFailures: Bool
    ) {
        self.locations = locations
        self.activeLocationID = activeLocationID
        self.migrationMadeChanges = migrationMadeChanges
        self.migrationHasFailures = migrationHasFailures
    }
}

public actor AppleApplicationBootstrapper {
    public init() {}

    public func load() async throws -> AppleApplicationSnapshot {
        let paths = try systemPaths()
        let migration = await AppleDataMigrator(
            paths: paths,
            validator: RustMigrationValidator()
        ).migrate()
        guard let bundleURL = locationBundleURL(in: paths) else {
            return AppleApplicationSnapshot(
                locations: [],
                activeLocationID: nil,
                migrationMadeChanges: migration.madeChanges,
                migrationHasFailures: migration.hasFailures
            )
        }

        let data = try Data(contentsOf: bundleURL)
        guard let json = String(data: data, encoding: .utf8) else {
            throw RustMigrationValidationError.invalidUTF8(.locationsV4)
        }
        let catalog = try appleLocationCatalog(bundleJson: json)
        return AppleApplicationSnapshot(
            locations: catalog.locations.map {
                LocationListItem(
                    id: $0.id,
                    name: $0.displayName,
                    detail: $0.detail,
                    isComplete: $0.complete,
                    needsUserAction: $0.needsUserAction,
                    isFromSubscription: $0.fromSubscription
                )
            },
            activeLocationID: catalog.activeLocationId,
            migrationMadeChanges: migration.madeChanges,
            migrationHasFailures: migration.hasFailures
        )
    }

    public func selectLocation(id: String) throws {
        let paths = try systemPaths()
        guard let bundleURL = locationBundleURL(in: paths) else {
            throw AppleApplicationBootstrapError.locationBundleMissing
        }
        let data = try Data(contentsOf: bundleURL)
        guard let json = String(data: data, encoding: .utf8) else {
            throw RustMigrationValidationError.invalidUTF8(.locationsV4)
        }
        let selection = try appleSelectLocation(bundleJson: json, locationId: id)
        try AtomicFileIO.writeBytesAtomically(
            Data(selection.normalizedBundleJson.utf8),
            to: bundleURL
        )
    }

    @discardableResult
    public func prepareTunnelRequest(locationID: String) throws -> AppleTunnelRequest {
        let paths = try systemPaths()
        guard let bundleURL = locationBundleURL(in: paths) else {
            throw AppleApplicationBootstrapError.locationBundleMissing
        }
        let bundleData = try Data(contentsOf: bundleURL)
        guard let bundleJSON = String(data: bundleData, encoding: .utf8) else {
            throw RustMigrationValidationError.invalidUTF8(.locationsV4)
        }
        guard let locationJSON = try appleLocationJson(
            bundleJson: bundleJSON,
            locationId: locationID
        ) else {
            throw AppleApplicationBootstrapError.locationMissing(locationID)
        }

        let deviceID = try deviceIdentity(paths: paths)

        let request = AppleTunnelRequest(
            locationJSON: locationJSON,
            trafficJSON: try artifactText(.traffic, paths: paths),
            routingJSON: try artifactText(.routing, paths: paths),
            routingProfilesJSON: try artifactText(.routingProfiles, paths: paths),
            appBehaviorJSON: try artifactText(.appBehavior, paths: paths),
            warpConfig: try artifactText(.warpConfig, paths: paths),
            deviceID: deviceID
        )
        try AppleTunnelStore.saveRequest(request)
        try AppleTunnelStore.writeText("", to: AppleTunnelStore.errorFileName)
        return request
    }

    public func importLocations(from input: String) async throws -> AppleApplicationSnapshot {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw AppleApplicationBootstrapError.importTextEmpty }
        let paths = try systemPaths()
        let parsedURL = URL(string: trimmed)
        let subscriptionURL: URL?
        if let parsedURL,
           trimmed.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
           parsedURL.scheme == "https" || parsedURL.scheme == "http" {
            subscriptionURL = parsedURL
        } else {
            subscriptionURL = nil
        }
        let text: String
        if let subscriptionURL {
            var request = URLRequest(url: subscriptionURL)
            request.timeoutInterval = 30
            request.setValue("YPtun/4.0.0", forHTTPHeaderField: "User-Agent")
            request.setValue(try deviceIdentity(paths: paths), forHTTPHeaderField: "x-hwid")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let response = response as? HTTPURLResponse,
               !(200..<300).contains(response.statusCode) {
                throw AppleApplicationBootstrapError.subscriptionHTTPStatus(response.statusCode)
            }
            guard let decoded = String(data: data, encoding: .utf8) else {
                throw AppleApplicationBootstrapError.invalidUTF8(subscriptionURL.lastPathComponent)
            }
            text = decoded
        } else {
            text = trimmed
        }

        let existing: String
        if let existingURL = locationBundleURL(in: paths) {
            existing = try readTextIfPresent(at: existingURL) ?? ""
        } else {
            existing = ""
        }
        let imported = try appleImportLocations(
            bundleJson: existing,
            text: text,
            subscriptionUrl: subscriptionURL?.absoluteString
        )
        try AtomicFileIO.writeBytesAtomically(
            Data(imported.normalizedBundleJson.utf8),
            to: paths.destination(for: .locationsV4)
        )
        return try await load()
    }

    public func locationText(id: String) throws -> String {
        let paths = try systemPaths()
        guard let bundleURL = locationBundleURL(in: paths) else {
            throw AppleApplicationBootstrapError.locationBundleMissing
        }
        let bundle = try readTextIfPresent(at: bundleURL) ?? ""
        guard let location = try appleLocationJson(bundleJson: bundle, locationId: id) else {
            throw AppleApplicationBootstrapError.locationMissing(id)
        }
        return location
    }

    public func saveLocationText(
        _ locationJSON: String,
        id: String?
    ) async throws -> AppleApplicationSnapshot {
        let paths = try systemPaths()
        let bundleURL = locationBundleURL(in: paths)
        let existing: String
        if let bundleURL {
            existing = try readTextIfPresent(at: bundleURL) ?? ""
        } else {
            existing = ""
        }
        let saved = try appleSaveLocation(
            bundleJson: existing,
            locationId: id,
            locationJson: locationJSON
        )
        try AtomicFileIO.writeBytesAtomically(
            Data(saved.normalizedBundleJson.utf8),
            to: paths.destination(for: .locationsV4)
        )
        return try await load()
    }

    private func systemPaths() throws -> AppleDataPaths {
        try AppleDataPaths.system(appGroupIdentifier: appGroupIdentifier)
    }

    private func locationBundleURL(in paths: AppleDataPaths) -> URL? {
        [.locationsV4, .locationsV3]
            .map { paths.destination(for: $0) }
            .first { FileManager.default.fileExists(atPath: $0.path) }
    }

    private func artifactText(
        _ artifact: MigrationArtifact,
        paths: AppleDataPaths
    ) throws -> String {
        try readTextIfPresent(at: paths.destination(for: artifact)) ?? ""
    }

    private func deviceIdentity(paths: AppleDataPaths) throws -> String {
        let deviceURL = paths.destination(for: .deviceIdentity)
        if let rawIdentity = try readTextIfPresent(at: deviceURL) {
            let stored = rawIdentity.trimmingCharacters(in: .whitespacesAndNewlines)
            if !stored.isEmpty { return stored }
        }
        let created = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        try AtomicFileIO.writeBytesAtomically(Data(created.utf8), to: deviceURL)
        return created
    }

    private func readTextIfPresent(at url: URL) throws -> String? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        guard let value = String(data: data, encoding: .utf8) else {
            throw AppleApplicationBootstrapError.invalidUTF8(url.lastPathComponent)
        }
        return value
    }

    private var appGroupIdentifier: String {
        #if os(macOS)
        YPtunIdentity.MacOS.appGroupIdentifier
        #else
        YPtunIdentity.IOS.appGroupIdentifier
        #endif
    }
}

public enum AppleApplicationBootstrapError: LocalizedError, Equatable, Sendable {
    case locationBundleMissing
    case locationMissing(String)
    case invalidUTF8(String)
    case importTextEmpty
    case subscriptionHTTPStatus(Int)

    public var errorDescription: String? {
        switch self {
        case .locationBundleMissing: "Файл сохранённых локаций не найден."
        case let .locationMissing(id): "Локация \(id) больше не существует."
        case let .invalidUTF8(file): "Файл \(file) имеет неподдерживаемую кодировку."
        case .importTextEmpty: "Вставьте ссылку или конфигурацию."
        case let .subscriptionHTTPStatus(status):
            "Сервер подписки ответил с кодом HTTP \(status)."
        }
    }
}

public enum AppleSettingsFile: String, CaseIterable, Sendable {
    case traffic
    case routing
    case routingProfiles
    case appBehavior

    fileprivate var artifact: MigrationArtifact {
        switch self {
        case .traffic: .traffic
        case .routing: .routing
        case .routingProfiles: .routingProfiles
        case .appBehavior: .appBehavior
        }
    }
}

public extension AppleApplicationBootstrapper {
    func settingsText(_ file: AppleSettingsFile) throws -> String {
        let paths = try systemPaths()
        return try readTextIfPresent(at: paths.destination(for: file.artifact)) ?? "{}"
    }

    func saveSettingsText(_ text: String, file: AppleSettingsFile) throws {
        guard let bytes = text.data(using: .utf8) else {
            throw AppleApplicationBootstrapError.invalidUTF8(file.rawValue)
        }
        let paths = try systemPaths()
        let destination = paths.destination(for: file.artifact)
        let digest = AtomicFileIO.sha256(of: bytes)
        try RustMigrationValidator().validate(
            MigrationPayload(
                artifact: file.artifact,
                source: destination,
                destination: destination,
                bytes: bytes,
                sha256: digest
            )
        )
        try AtomicFileIO.writeBytesAtomically(bytes, to: destination)
    }
}
