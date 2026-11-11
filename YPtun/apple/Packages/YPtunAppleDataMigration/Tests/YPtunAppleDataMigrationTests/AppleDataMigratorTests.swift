import Foundation
import Testing
@testable import YPtunAppleDataMigration

@Test func discoveryFindsKnownFilesWithoutParsingThem() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let malformedJSON = Data([0x7b, 0xff, 0x00, 0x7d])
    let locationURL = fixture.paths.legacyOlcboxDirectory
        .appendingPathComponent(MigrationArtifact.locationsV4.fileName)
    let trafficURL = fixture.paths.documentsYPtunDirectory
        .appendingPathComponent(MigrationArtifact.traffic.fileName)
    try fixture.write(malformedJSON, to: locationURL)
    try fixture.write(Data("not-json".utf8), to: trafficURL)

    let report = await AppleDataMigrator(paths: fixture.paths).discover()

    #expect(report.issues.isEmpty)
    #expect(report.files.count == 2)
    let location = try #require(report.files.first { $0.artifact == .locationsV4 })
    #expect(location.origin == .applicationSupportOlcbox)
    #expect(location.snapshot.byteCount == malformedJSON.count)
    #expect(location.snapshot.sha256 == AtomicFileIO.sha256(of: malformedJSON))
}

@Test func migrationCopiesOpaqueBytesAndIsIdempotent() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let locationBytes = Data([0x00, 0xff, 0x10, 0x7b, 0x7d])
    let routingBytes = Data("{\"unknown_future_field\":true}\n".utf8)
    let locationSource = fixture.paths.legacyOlcboxDirectory
        .appendingPathComponent(MigrationArtifact.locationsV4.fileName)
    let routingSource = fixture.paths.documentsYPtunDirectory
        .appendingPathComponent(MigrationArtifact.routing.fileName)
    try fixture.write(locationBytes, to: locationSource)
    try fixture.write(routingBytes, to: routingSource)
    let migrator = AppleDataMigrator(paths: fixture.paths)

    let first = await migrator.migrate()

    #expect(first.madeChanges)
    #expect(!first.hasFailures)
    #expect(try Data(contentsOf: fixture.paths.destination(for: .locationsV4)) == locationBytes)
    #expect(try Data(contentsOf: fixture.paths.destination(for: .routing)) == routingBytes)
    let changedRecords = first.records.filter {
        $0.result == .copied
    }
    #expect(changedRecords.count == 2)
    for record in changedRecords {
        let backup = try #require(record.sourceBackup)
        let expected = record.artifact == .locationsV4 ? locationBytes : routingBytes
        #expect(try Data(contentsOf: backup) == expected)
    }

    let backupFilesBefore = try fixture.regularFiles(below: fixture.paths.migrationBackupDirectory)
    let second = await migrator.migrate()
    let backupFilesAfter = try fixture.regularFiles(below: fixture.paths.migrationBackupDirectory)

    #expect(!second.madeChanges)
    #expect(!second.hasFailures)
    #expect(backupFilesAfter == backupFilesBefore)
}

@Test(
    arguments: [
        MigrationArtifact.traffic,
        MigrationArtifact.routing,
        MigrationArtifact.appBehavior,
    ]
)
func everySharedSettingsFileMigratesFromTheHistoricalFallback(
    artifact: MigrationArtifact
) async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let bytes = Data("opaque-\(artifact.rawValue)-settings".utf8)
    try fixture.write(
        bytes,
        to: fixture.paths.documentsYPtunDirectory.appendingPathComponent(artifact.fileName)
    )

    let report = await AppleDataMigrator(paths: fixture.paths).migrate()

    let record = try #require(report.records.first { $0.artifact == artifact })
    #expect(record.result == .copied)
    #expect(try Data(contentsOf: fixture.paths.destination(for: artifact)) == bytes)
}

@Test func defaultConflictPolicyPreservesNativeDestination() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let source = Data("legacy".utf8)
    let destination = Data("native".utf8)
    try fixture.write(
        source,
        to: fixture.paths.legacyOlcboxDirectory
            .appendingPathComponent(MigrationArtifact.locationsV4.fileName)
    )
    let destinationURL = fixture.paths.destination(for: .locationsV4)
    try fixture.write(destination, to: destinationURL)
    let migrator = AppleDataMigrator(paths: fixture.paths)

    let plan = await migrator.plan()
    let item = try #require(plan.items.first { $0.artifact == .locationsV4 })
    #expect(item.disposition == .conflictPreserved)

    let report = await migrator.migrate()
    let record = try #require(report.records.first { $0.artifact == .locationsV4 })
    #expect(record.result == .conflictPreserved)
    #expect(record.sourceBackup == nil)
    #expect(record.replacedDestinationBackup == nil)
    #expect(try Data(contentsOf: destinationURL) == destination)
}

@Test func replacementBacksUpBothSidesBeforeWriting() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let source = Data("legacy-byte-for-byte\n".utf8)
    let destination = Data("native-before-replacement\n".utf8)
    let sourceURL = fixture.paths.legacyOlcboxDirectory
        .appendingPathComponent(MigrationArtifact.locationsV3.fileName)
    let destinationURL = fixture.paths.destination(for: .locationsV3)
    try fixture.write(source, to: sourceURL)
    try fixture.write(destination, to: destinationURL)

    let report = await AppleDataMigrator(paths: fixture.paths).migrate(
        options: MigrationOptions(conflictResolution: .replaceExistingAfterBackup)
    )

    let record = try #require(report.records.first { $0.artifact == .locationsV3 })
    #expect(record.result == .replaced)
    #expect(try Data(contentsOf: destinationURL) == source)
    #expect(try Data(contentsOf: #require(record.sourceBackup)) == source)
    #expect(try Data(contentsOf: #require(record.replacedDestinationBackup)) == destination)
}

@Test func validatorCanRejectWithoutChangingTheDestination() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let sourceURL = fixture.paths.legacyOlcboxDirectory
        .appendingPathComponent(MigrationArtifact.locationsV4.fileName)
    try fixture.write(Data("opaque".utf8), to: sourceURL)
    let migrator = AppleDataMigrator(
        paths: fixture.paths,
        validator: RejectingValidator()
    )

    let report = await migrator.migrate()

    let record = try #require(report.records.first { $0.artifact == .locationsV4 })
    guard case .validationRejected = record.result else {
        Issue.record("Expected validation rejection, got \(record.result)")
        return
    }
    #expect(!FileManager.default.fileExists(atPath: record.destination.path))
    #expect(record.sourceBackup == nil)
}

@Test func migrationPreservesRoutingProfilesAndDeviceIdentityBytes() async throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let routingProfiles = Data(#"{"globalProfileId":"main","profiles":[]}"#.utf8)
    let identity = Data("legacy-device-id\n".utf8)
    let warp = Data("[Interface]\nPrivateKey = key\n[Peer]\nPublicKey = peer\n".utf8)
    let appGroupDirectory = try #require(fixture.paths.appGroupYPtunDirectory)
    try fixture.write(
        routingProfiles,
        to: appGroupDirectory
            .appendingPathComponent(MigrationArtifact.routingProfiles.fileName)
    )
    try fixture.write(
        identity,
        to: fixture.paths.legacyOlcboxDirectory
            .appendingPathComponent(MigrationArtifact.deviceIdentity.fileName)
    )
    try fixture.write(
        warp,
        to: appGroupDirectory.appendingPathComponent(MigrationArtifact.warpConfig.fileName)
    )

    let report = await AppleDataMigrator(paths: fixture.paths).migrate()

    #expect(!report.hasFailures)
    #expect(
        try AtomicFileIO.readBytes(at: fixture.paths.destination(for: .routingProfiles))
            == routingProfiles
    )
    #expect(
        try AtomicFileIO.readBytes(at: fixture.paths.destination(for: .deviceIdentity))
            == identity
    )
    #expect(
        try AtomicFileIO.readBytes(at: fixture.paths.destination(for: .warpConfig)) == warp
    )
}

private struct RejectingValidator: MigrationPayloadValidating {
    struct Rejected: Error {}

    func validate(_ payload: MigrationPayload) throws {
        throw Rejected()
    }
}
