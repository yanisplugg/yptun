import Foundation

public enum MigrationArtifact: String, CaseIterable, Hashable, Sendable {
    case locationsV4
    case locationsV3
    case traffic
    case routing
    case routingProfiles
    case appBehavior
    case deviceIdentity

    public var fileName: String {
        switch self {
        case .locationsV4: "locations_v4.json"
        case .locationsV3: "locations_v3.json"
        case .traffic: "traffic.json"
        case .routing: "routing.json"
        case .routingProfiles: "routing_profiles.json"
        case .appBehavior: "app_behavior.json"
        case .deviceIdentity: "device_identity"
        }
    }

    public var isLocationBundle: Bool {
        switch self {
        case .locationsV4, .locationsV3: true
        case .traffic, .routing, .routingProfiles, .appBehavior, .deviceIdentity: false
        }
    }

    public var isNativeApplicationSupport: Bool {
        isLocationBundle || self == .deviceIdentity
    }
}

public enum MigrationOrigin: String, Hashable, Sendable {
    case applicationSupportOlcbox
    case applicationSupportYPtun
    case appGroupYPtun
    case documentsYPtun
}

public struct FileSnapshot: Hashable, Sendable {
    public let url: URL
    public let byteCount: Int
    public let sha256: String
    public let modificationDate: Date?

    public init(
        url: URL,
        byteCount: Int,
        sha256: String,
        modificationDate: Date?
    ) {
        self.url = url
        self.byteCount = byteCount
        self.sha256 = sha256
        self.modificationDate = modificationDate
    }
}

public struct DiscoveredMigrationFile: Hashable, Sendable {
    public let artifact: MigrationArtifact
    public let origin: MigrationOrigin
    public let snapshot: FileSnapshot

    public init(
        artifact: MigrationArtifact,
        origin: MigrationOrigin,
        snapshot: FileSnapshot
    ) {
        self.artifact = artifact
        self.origin = origin
        self.snapshot = snapshot
    }
}

public struct DiscoveryIssue: Hashable, Sendable {
    public let artifact: MigrationArtifact
    public let origin: MigrationOrigin
    public let url: URL
    public let message: String

    public init(
        artifact: MigrationArtifact,
        origin: MigrationOrigin,
        url: URL,
        message: String
    ) {
        self.artifact = artifact
        self.origin = origin
        self.url = url
        self.message = message
    }
}

public struct MigrationDiscoveryReport: Hashable, Sendable {
    public let files: [DiscoveredMigrationFile]
    public let issues: [DiscoveryIssue]

    public init(files: [DiscoveredMigrationFile], issues: [DiscoveryIssue]) {
        self.files = files
        self.issues = issues
    }
}

public enum MigrationConflictResolution: Hashable, Sendable {
    /// Never overwrite bytes already present at a native destination.
    case preserveExisting
    /// Back up existing destination bytes, then atomically replace them with the selected source.
    case replaceExistingAfterBackup
}

public struct MigrationOptions: Hashable, Sendable {
    public var conflictResolution: MigrationConflictResolution
    public var backsUpSource: Bool

    public init(
        conflictResolution: MigrationConflictResolution = .preserveExisting,
        backsUpSource: Bool = true
    ) {
        self.conflictResolution = conflictResolution
        self.backsUpSource = backsUpSource
    }
}

public enum MigrationDisposition: Hashable, Sendable {
    case sourceMissing
    case blocked
    case alreadyInPlace
    case copy
    case destinationMatches
    case conflictPreserved
    case replaceAfterBackup
}

public struct MigrationPlanItem: Hashable, Sendable {
    public let artifact: MigrationArtifact
    public let selectedSource: DiscoveredMigrationFile?
    public let destination: URL
    public let destinationSnapshot: FileSnapshot?
    public let disposition: MigrationDisposition
    public let diagnostic: String?

    public init(
        artifact: MigrationArtifact,
        selectedSource: DiscoveredMigrationFile?,
        destination: URL,
        destinationSnapshot: FileSnapshot?,
        disposition: MigrationDisposition,
        diagnostic: String? = nil
    ) {
        self.artifact = artifact
        self.selectedSource = selectedSource
        self.destination = destination
        self.destinationSnapshot = destinationSnapshot
        self.disposition = disposition
        self.diagnostic = diagnostic
    }
}

public struct MigrationPlan: Hashable, Sendable {
    public let options: MigrationOptions
    public let discovery: MigrationDiscoveryReport
    public let items: [MigrationPlanItem]

    public init(
        options: MigrationOptions,
        discovery: MigrationDiscoveryReport,
        items: [MigrationPlanItem]
    ) {
        self.options = options
        self.discovery = discovery
        self.items = items
    }
}

public enum MigrationUnchangedReason: Hashable, Sendable {
    case alreadyInPlace
    case destinationMatches
}

public enum MigrationRecordResult: Hashable, Sendable {
    case sourceMissing
    case unchanged(MigrationUnchangedReason)
    case copied
    case replaced
    case conflictPreserved
    case blocked(String)
    case sourceChanged
    case destinationChanged
    case validationRejected(String)
    case failed(String)
}

public struct MigrationRecord: Hashable, Sendable {
    public let artifact: MigrationArtifact
    public let source: URL?
    public let destination: URL
    public let sourceBackup: URL?
    public let replacedDestinationBackup: URL?
    public let result: MigrationRecordResult

    public init(
        artifact: MigrationArtifact,
        source: URL?,
        destination: URL,
        sourceBackup: URL?,
        replacedDestinationBackup: URL?,
        result: MigrationRecordResult
    ) {
        self.artifact = artifact
        self.source = source
        self.destination = destination
        self.sourceBackup = sourceBackup
        self.replacedDestinationBackup = replacedDestinationBackup
        self.result = result
    }
}

public struct MigrationReport: Hashable, Sendable {
    public let startedAt: Date
    public let completedAt: Date
    public let plan: MigrationPlan
    public let records: [MigrationRecord]

    public init(
        startedAt: Date,
        completedAt: Date,
        plan: MigrationPlan,
        records: [MigrationRecord]
    ) {
        self.startedAt = startedAt
        self.completedAt = completedAt
        self.plan = plan
        self.records = records
    }

    public var madeChanges: Bool {
        records.contains { record in
            switch record.result {
            case .copied, .replaced: true
            default: false
            }
        }
    }

    public var hasFailures: Bool {
        records.contains { record in
            switch record.result {
            case .blocked, .sourceChanged, .destinationChanged, .validationRejected, .failed: true
            default: false
            }
        }
    }
}
