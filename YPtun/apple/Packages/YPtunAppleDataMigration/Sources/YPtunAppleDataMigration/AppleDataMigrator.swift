import Foundation

/// Serializes discovery, planning, backup, validation, and file replacement.
///
/// Each file replacement is atomic. The collection is intentionally not advertised as one
/// transaction: a process termination can leave a prefix completed, and the next call safely resumes
/// because planning and content-addressed backups are idempotent.
public actor AppleDataMigrator {
    public let paths: AppleDataPaths
    private let validator: any MigrationPayloadValidating

    public init(
        paths: AppleDataPaths,
        validator: any MigrationPayloadValidating = BytePreservingMigrationValidator()
    ) {
        self.paths = paths
        self.validator = validator
    }

    public func discover() -> MigrationDiscoveryReport {
        var files: [DiscoveredMigrationFile] = []
        var issues: [DiscoveryIssue] = []

        for artifact in MigrationArtifact.allCases {
            var visitedPaths: Set<String> = []
            for candidate in candidates(for: artifact) {
                let url = candidate.url.standardizedFileURL
                guard visitedPaths.insert(url.path).inserted else { continue }
                guard FileManager.default.fileExists(atPath: url.path) else { continue }

                do {
                    guard let snapshot = try AtomicFileIO.snapshot(at: url) else { continue }
                    files.append(
                        DiscoveredMigrationFile(
                            artifact: artifact,
                            origin: candidate.origin,
                            snapshot: snapshot
                        )
                    )
                } catch {
                    issues.append(
                        DiscoveryIssue(
                            artifact: artifact,
                            origin: candidate.origin,
                            url: url,
                            message: String(describing: error)
                        )
                    )
                }
            }
        }

        return MigrationDiscoveryReport(files: files, issues: issues)
    }

    public func plan(
        options: MigrationOptions = MigrationOptions()
    ) -> MigrationPlan {
        makePlan(discovery: discover(), options: options)
    }

    public func migrate(
        options: MigrationOptions = MigrationOptions()
    ) -> MigrationReport {
        let startedAt = Date()
        let plan = makePlan(discovery: discover(), options: options)
        let records = plan.items.map { execute($0, options: options) }
        return MigrationReport(
            startedAt: startedAt,
            completedAt: Date(),
            plan: plan,
            records: records
        )
    }

    private func makePlan(
        discovery: MigrationDiscoveryReport,
        options: MigrationOptions
    ) -> MigrationPlan {
        let items = MigrationArtifact.allCases.map { artifact in
            planItem(for: artifact, discovery: discovery, options: options)
        }
        return MigrationPlan(options: options, discovery: discovery, items: items)
    }

    private func planItem(
        for artifact: MigrationArtifact,
        discovery: MigrationDiscoveryReport,
        options: MigrationOptions
    ) -> MigrationPlanItem {
        let destination = paths.destination(for: artifact).standardizedFileURL
        let relevantIssues = discovery.issues.filter { $0.artifact == artifact }
        let sources = discovery.files.filter { $0.artifact == artifact }
        let selectedSource = sources.min { lhs, rhs in
            sourceRank(lhs.origin, for: artifact) < sourceRank(rhs.origin, for: artifact)
        }
        let destinationSnapshot = sources
            .first { samePath($0.snapshot.url, destination) }
            ?.snapshot

        guard relevantIssues.isEmpty else {
            return MigrationPlanItem(
                artifact: artifact,
                selectedSource: selectedSource,
                destination: destination,
                destinationSnapshot: destinationSnapshot,
                disposition: .blocked,
                diagnostic: relevantIssues.map(\.message).joined(separator: "; ")
            )
        }
        guard let selectedSource else {
            return MigrationPlanItem(
                artifact: artifact,
                selectedSource: nil,
                destination: destination,
                destinationSnapshot: destinationSnapshot,
                disposition: .sourceMissing
            )
        }
        if samePath(selectedSource.snapshot.url, destination) {
            return MigrationPlanItem(
                artifact: artifact,
                selectedSource: selectedSource,
                destination: destination,
                destinationSnapshot: selectedSource.snapshot,
                disposition: .alreadyInPlace
            )
        }
        guard let destinationSnapshot else {
            return MigrationPlanItem(
                artifact: artifact,
                selectedSource: selectedSource,
                destination: destination,
                destinationSnapshot: nil,
                disposition: .copy
            )
        }
        if snapshotsMatch(selectedSource.snapshot, destinationSnapshot) {
            return MigrationPlanItem(
                artifact: artifact,
                selectedSource: selectedSource,
                destination: destination,
                destinationSnapshot: destinationSnapshot,
                disposition: .destinationMatches
            )
        }

        let disposition: MigrationDisposition = switch options.conflictResolution {
        case .preserveExisting: .conflictPreserved
        case .replaceExistingAfterBackup: .replaceAfterBackup
        }
        return MigrationPlanItem(
            artifact: artifact,
            selectedSource: selectedSource,
            destination: destination,
            destinationSnapshot: destinationSnapshot,
            disposition: disposition
        )
    }

    private func execute(
        _ item: MigrationPlanItem,
        options: MigrationOptions
    ) -> MigrationRecord {
        guard item.disposition != .sourceMissing else {
            return record(for: item, result: .sourceMissing)
        }
        guard item.disposition != .blocked else {
            return record(
                for: item,
                result: .blocked(item.diagnostic ?? "Discovery failed")
            )
        }
        guard let source = item.selectedSource else {
            return record(for: item, result: .sourceMissing)
        }

        var sourceBackup: URL?
        var destinationBackup: URL?
        do {
            let sourceBytes = try AtomicFileIO.readBytes(at: source.snapshot.url)
            let sourceDigest = AtomicFileIO.sha256(of: sourceBytes)
            guard sourceBytes.count == source.snapshot.byteCount,
                  sourceDigest == source.snapshot.sha256 else {
                return record(for: item, result: .sourceChanged)
            }

            if samePath(source.snapshot.url, item.destination) {
                return record(
                    for: item,
                    sourceBackup: sourceBackup,
                    result: .unchanged(.alreadyInPlace)
                )
            }

            let currentDestination = try currentBytes(at: item.destination)
            if let currentDestination, currentDestination == sourceBytes {
                return record(
                    for: item,
                    sourceBackup: sourceBackup,
                    result: .unchanged(.destinationMatches)
                )
            }

            if currentDestination != nil,
               options.conflictResolution == .preserveExisting {
                return record(for: item, result: .conflictPreserved)
            }

            do {
                try validator.validate(
                    MigrationPayload(
                        artifact: item.artifact,
                        source: source.snapshot.url,
                        destination: item.destination,
                        bytes: sourceBytes,
                        sha256: sourceDigest
                    )
                )
            } catch {
                return record(
                    for: item,
                    result: .validationRejected(String(describing: error))
                )
            }

            if let currentDestination {
                if options.backsUpSource {
                    sourceBackup = try writeBackup(
                        sourceBytes,
                        artifact: item.artifact,
                        digest: sourceDigest,
                        category: "sources",
                        origin: source.origin.rawValue
                    )
                }
                let destinationDigest = AtomicFileIO.sha256(of: currentDestination)
                destinationBackup = try writeBackup(
                    currentDestination,
                    artifact: item.artifact,
                    digest: destinationDigest,
                    category: "destinations",
                    origin: "native"
                )
                guard let latestDestination = try currentBytes(at: item.destination),
                      latestDestination == currentDestination else {
                    return record(
                        for: item,
                        sourceBackup: sourceBackup,
                        destinationBackup: destinationBackup,
                        result: .destinationChanged
                    )
                }
                try AtomicFileIO.writeBytesAtomically(sourceBytes, to: item.destination)
                return record(
                    for: item,
                    sourceBackup: sourceBackup,
                    destinationBackup: destinationBackup,
                    result: .replaced
                )
            }

            if options.backsUpSource {
                sourceBackup = try writeBackup(
                    sourceBytes,
                    artifact: item.artifact,
                    digest: sourceDigest,
                    category: "sources",
                    origin: source.origin.rawValue
                )
            }
            if let unexpectedDestination = try currentBytes(at: item.destination) {
                let result: MigrationRecordResult = unexpectedDestination == sourceBytes
                    ? .unchanged(.destinationMatches)
                    : .destinationChanged
                return record(
                    for: item,
                    sourceBackup: sourceBackup,
                    result: result
                )
            }
            try AtomicFileIO.writeBytesAtomically(
                sourceBytes,
                to: item.destination,
                replacingExisting: false
            )
            return record(
                for: item,
                sourceBackup: sourceBackup,
                result: .copied
            )
        } catch {
            return record(
                for: item,
                sourceBackup: sourceBackup,
                destinationBackup: destinationBackup,
                result: .failed(String(describing: error))
            )
        }
    }

    private func writeBackup(
        _ bytes: Data,
        artifact: MigrationArtifact,
        digest: String,
        category: String,
        origin: String
    ) throws -> URL {
        let url = paths.migrationBackupDirectory
            .appendingPathComponent(category, isDirectory: true)
            .appendingPathComponent(origin, isDirectory: true)
            .appendingPathComponent(digest, isDirectory: true)
            .appendingPathComponent(artifact.fileName, isDirectory: false)

        if let existing = try currentBytes(at: url) {
            guard existing == bytes else {
                throw AtomicFileError.backupCollision(url)
            }
            return url
        }
        do {
            try AtomicFileIO.writeBytesAtomically(
                bytes,
                to: url,
                replacingExisting: false
            )
            return url
        } catch {
            // A second process may have installed the same content-addressed backup after our check.
            if let existing = try currentBytes(at: url), existing == bytes {
                return url
            }
            throw error
        }
    }

    private func currentBytes(at url: URL) throws -> Data? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try AtomicFileIO.readBytes(at: url)
    }

    private func candidates(for artifact: MigrationArtifact) -> [Candidate] {
        if artifact.isLocationBundle {
            return [
                Candidate(
                    origin: .applicationSupportOlcbox,
                    url: paths.legacyOlcboxDirectory.appendingPathComponent(artifact.fileName)
                ),
                Candidate(
                    origin: .applicationSupportYPtun,
                    url: paths.nativeYPtunDirectory.appendingPathComponent(artifact.fileName)
                ),
            ]
        }

        var values: [Candidate] = []
        if let appGroupDirectory = paths.appGroupYPtunDirectory {
            values.append(
                Candidate(
                    origin: .appGroupYPtun,
                    url: appGroupDirectory.appendingPathComponent(artifact.fileName)
                )
            )
        }
        values.append(
            Candidate(
                origin: .documentsYPtun,
                url: paths.documentsYPtunDirectory.appendingPathComponent(artifact.fileName)
            )
        )
        values.append(
            Candidate(
                origin: .applicationSupportOlcbox,
                url: paths.legacyOlcboxDirectory.appendingPathComponent(artifact.fileName)
            )
        )
        values.append(
            Candidate(
                origin: .applicationSupportYPtun,
                url: paths.nativeYPtunDirectory.appendingPathComponent(artifact.fileName)
            )
        )
        return values
    }

    private func sourceRank(
        _ origin: MigrationOrigin,
        for artifact: MigrationArtifact
    ) -> Int {
        if artifact.isLocationBundle {
            return switch origin {
            case .applicationSupportOlcbox: 0
            case .applicationSupportYPtun: 1
            case .appGroupYPtun: 2
            case .documentsYPtun: 3
            }
        }
        return switch origin {
        case .appGroupYPtun: 0
        case .documentsYPtun: 1
        case .applicationSupportOlcbox: 2
        case .applicationSupportYPtun: 3
        }
    }

    private func snapshotsMatch(_ lhs: FileSnapshot, _ rhs: FileSnapshot) -> Bool {
        lhs.byteCount == rhs.byteCount && lhs.sha256 == rhs.sha256
    }

    private func samePath(_ lhs: URL, _ rhs: URL) -> Bool {
        lhs.standardizedFileURL.path == rhs.standardizedFileURL.path
    }

    private func record(
        for item: MigrationPlanItem,
        sourceBackup: URL? = nil,
        destinationBackup: URL? = nil,
        result: MigrationRecordResult
    ) -> MigrationRecord {
        MigrationRecord(
            artifact: item.artifact,
            source: item.selectedSource?.snapshot.url,
            destination: item.destination,
            sourceBackup: sourceBackup,
            replacedDestinationBackup: destinationBackup,
            result: result
        )
    }
}

private struct Candidate: Sendable {
    let origin: MigrationOrigin
    let url: URL
}
