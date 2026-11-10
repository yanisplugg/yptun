import Foundation

/// An immutable view of bytes selected for migration.
///
/// A future Rust bridge can conform to ``MigrationPayloadValidating`` and validate the payload. The
/// validator deliberately cannot return replacement bytes, so validation cannot accidentally become
/// Swift-side normalization or serialization.
public struct MigrationPayload: Sendable {
    public let artifact: MigrationArtifact
    public let source: URL
    public let destination: URL
    public let bytes: Data
    public let sha256: String

    public init(
        artifact: MigrationArtifact,
        source: URL,
        destination: URL,
        bytes: Data,
        sha256: String
    ) {
        self.artifact = artifact
        self.source = source
        self.destination = destination
        self.bytes = bytes
        self.sha256 = sha256
    }
}

public protocol MigrationPayloadValidating: Sendable {
    func validate(_ payload: MigrationPayload) throws
}

/// The default policy: accept opaque payloads and preserve every byte.
public struct BytePreservingMigrationValidator: MigrationPayloadValidating {
    public init() {}

    public func validate(_ payload: MigrationPayload) throws {
        // Model parsing and normalization belong to the Rust core, not this foundation package.
    }
}
