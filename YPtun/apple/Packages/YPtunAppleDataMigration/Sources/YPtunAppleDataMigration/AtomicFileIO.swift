import CryptoKit
import Foundation

/// Byte-oriented file operations used by migration code.
///
/// This type intentionally has no JSON or model APIs. Writes use Foundation's same-directory atomic
/// replacement and verify the committed bytes before returning.
public enum AtomicFileIO: Sendable {
    public static func readBytes(at url: URL) throws -> Data {
        let standardizedURL = url.standardizedFileURL
        let attributes = try FileManager.default.attributesOfItem(atPath: standardizedURL.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            throw AtomicFileError.notARegularFile(standardizedURL)
        }
        // Own the bytes instead of memory-mapping them. The source may be replaced by the retiring
        // process while migration is running; an owned snapshot cannot change underneath hashing,
        // validation, backup, or commit.
        return try Data(contentsOf: standardizedURL)
    }

    public static func snapshot(at url: URL) throws -> FileSnapshot? {
        let standardizedURL = url.standardizedFileURL
        guard FileManager.default.fileExists(atPath: standardizedURL.path) else {
            return nil
        }
        let data = try readBytes(at: standardizedURL)
        let attributes = try FileManager.default.attributesOfItem(atPath: standardizedURL.path)
        return FileSnapshot(
            url: standardizedURL,
            byteCount: data.count,
            sha256: sha256(of: data),
            modificationDate: attributes[.modificationDate] as? Date
        )
    }

    public static func writeBytesAtomically(
        _ data: Data,
        to url: URL,
        replacingExisting: Bool = true
    ) throws {
        let destination = url.standardizedFileURL
        let parent = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: parent,
            withIntermediateDirectories: true
        )

        if FileManager.default.fileExists(atPath: destination.path) {
            let attributes = try FileManager.default.attributesOfItem(atPath: destination.path)
            guard attributes[.type] as? FileAttributeType == .typeRegular else {
                throw AtomicFileError.notARegularFile(destination)
            }
        }

        do {
            var options: Data.WritingOptions = [.atomic]
            if !replacingExisting {
                options.insert(.withoutOverwriting)
            }
            try data.write(to: destination, options: options)
        } catch {
            throw AtomicFileError.writeFailed(destination, String(describing: error))
        }

        let committed = try readBytes(at: destination)
        guard committed == data else {
            throw AtomicFileError.verificationFailed(destination)
        }
    }

    public static func sha256(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

public enum AtomicFileError: Error, Hashable, Sendable {
    case notARegularFile(URL)
    case writeFailed(URL, String)
    case verificationFailed(URL)
    case backupCollision(URL)
}
