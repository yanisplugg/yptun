import Foundation
import YPtunAppleDataMigration
import YPtunRustBridge

public enum RustMigrationValidationError: Error, Equatable, Sendable {
    case invalidUTF8(MigrationArtifact)
}

/// Uses the canonical Rust codecs to accept or reject Kotlin-era payloads before Swift copies them.
///
/// The migration package remains byte-preserving: Rust's normalized bundle is deliberately discarded
/// here. The native repository performs its normal save only after startup has successfully loaded the
/// copied bytes, so a migration never becomes an implicit destructive rewrite.
public struct RustMigrationValidator: MigrationPayloadValidating {
    public init() {}

    public func validate(_ payload: MigrationPayload) throws {
        guard let json = String(data: payload.bytes, encoding: .utf8) else {
            throw RustMigrationValidationError.invalidUTF8(payload.artifact)
        }

        switch payload.artifact {
        case .locationsV3, .locationsV4:
            _ = try appleMigrateLocationBundle(bundleJson: json)
        case .traffic:
            try CoreSession().setTraffic(trafficJson: json)
        case .routing:
            try CoreSession().setRouting(routingJson: json)
        case .routingProfiles:
            try CoreSession().setRoutingProfiles(profilesJson: json)
        case .appBehavior:
            // The Rust app-settings facade will own this model in the next migration slice. Preserve
            // the current payload byte-for-byte until that codec is exported.
            break
        case .deviceIdentity:
            guard !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw RustMigrationValidationError.invalidUTF8(.deviceIdentity)
            }
        }
    }
}
