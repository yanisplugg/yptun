import Foundation

/// Canonical Apple storage paths used during the Kotlin-to-native migration.
///
/// The old Kotlin Apple app split its data across two containers:
/// - location bundles in `Library/Application Support/Olcbox`;
/// - tunnel-facing settings in `group.org.yptun.app/yptun`.
///
/// Native location data is rooted at `Library/Application Support/YPtun`. Shared settings remain in
/// the App Group so the packet-tunnel extension can continue to read them. When an App Group is not
/// available (for example, an unsigned development build), the historical `Documents/yptun`
/// fallback remains the shared destination.
public struct AppleDataPaths: Hashable, Sendable {
    public static let appGroupIdentifier = "group.org.yptun.app"

    public let applicationSupportRoot: URL
    public let appGroupContainer: URL?
    public let documentsRoot: URL

    public init(
        applicationSupportRoot: URL,
        appGroupContainer: URL?,
        documentsRoot: URL
    ) {
        self.applicationSupportRoot = applicationSupportRoot.standardizedFileURL
        self.appGroupContainer = appGroupContainer?.standardizedFileURL
        self.documentsRoot = documentsRoot.standardizedFileURL
    }

    /// Resolves sandbox paths without creating directories or files.
    public static func system(
        appGroupIdentifier: String = AppleDataPaths.appGroupIdentifier
    ) throws -> AppleDataPaths {
        let fileManager = FileManager.default
        guard let applicationSupportRoot = fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw AppleDataPathError.applicationSupportUnavailable
        }
        guard let documentsRoot = fileManager.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else {
            throw AppleDataPathError.documentsUnavailable
        }

        return AppleDataPaths(
            applicationSupportRoot: applicationSupportRoot,
            appGroupContainer: fileManager.containerURL(
                forSecurityApplicationGroupIdentifier: appGroupIdentifier
            ),
            documentsRoot: documentsRoot
        )
    }

    public var legacyOlcboxDirectory: URL {
        applicationSupportRoot.appendingPathComponent("Olcbox", isDirectory: true)
    }

    public var nativeYPtunDirectory: URL {
        applicationSupportRoot.appendingPathComponent("YPtun", isDirectory: true)
    }

    public var appGroupYPtunDirectory: URL? {
        appGroupContainer?.appendingPathComponent("yptun", isDirectory: true)
    }

    public var documentsYPtunDirectory: URL {
        documentsRoot.appendingPathComponent("yptun", isDirectory: true)
    }

    public var sharedSettingsDirectory: URL {
        appGroupYPtunDirectory ?? documentsYPtunDirectory
    }

    public var migrationBackupDirectory: URL {
        nativeYPtunDirectory
            .appendingPathComponent("MigrationBackups", isDirectory: true)
            .appendingPathComponent("KotlinApple", isDirectory: true)
    }

    public func destination(for artifact: MigrationArtifact) -> URL {
        let directory = artifact.isNativeApplicationSupport
            ? nativeYPtunDirectory
            : sharedSettingsDirectory
        return directory.appendingPathComponent(artifact.fileName, isDirectory: false)
    }
}

public enum AppleDataPathError: Error, Equatable, Sendable {
    case applicationSupportUnavailable
    case documentsUnavailable
}
