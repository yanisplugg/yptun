import Foundation
import YPtunAppleDataMigration
import YPtunRustBridge
import YPtunShared

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
        let paths = try AppleDataPaths.system(appGroupIdentifier: appGroupIdentifier)
        let migration = await AppleDataMigrator(
            paths: paths,
            validator: RustMigrationValidator()
        ).migrate()
        guard let bundleURL = [.locationsV4, .locationsV3]
            .map({ paths.destination(for: $0) })
            .first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
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

    private var appGroupIdentifier: String {
        #if os(macOS)
        YPtunIdentity.MacOS.appGroupIdentifier
        #else
        YPtunIdentity.IOS.appGroupIdentifier
        #endif
    }
}
