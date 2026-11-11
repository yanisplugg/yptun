import Foundation
import Testing
@testable import YPtunAppleDataMigration

@Test func pathContractMatchesExistingAppleStorage() throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }

    let paths = fixture.paths
    #expect(paths.legacyOlcboxDirectory.lastPathComponent == "Olcbox")
    #expect(paths.nativeYPtunDirectory.lastPathComponent == "YPtun")
    #expect(paths.appGroupYPtunDirectory?.lastPathComponent == "yptun")
    #expect(paths.destination(for: .locationsV4).path.hasSuffix("YPtun/locations_v4.json"))
    #expect(paths.destination(for: .deviceIdentity).path.hasSuffix("YPtun/device_identity"))
    #expect(paths.destination(for: .traffic).path.hasSuffix("yptun/traffic.json"))
    #expect(paths.destination(for: .routingProfiles).path.hasSuffix("yptun/routing_profiles.json"))
    #expect(paths.destination(for: .warpConfig).path.hasSuffix("yptun/warp.conf"))
    #expect(paths.migrationBackupDirectory.path.hasSuffix("YPtun/MigrationBackups/KotlinApple"))
}

@Test func documentsIsTheSharedFallbackWhenTheAppGroupIsUnavailable() throws {
    let fixture = try MigrationFixture(hasAppGroup: false)
    defer { fixture.remove() }

    #expect(fixture.paths.sharedSettingsDirectory == fixture.paths.documentsYPtunDirectory)
    #expect(
        fixture.paths.destination(for: .appBehavior)
            == fixture.paths.documentsYPtunDirectory.appendingPathComponent("app_behavior.json")
    )
}
