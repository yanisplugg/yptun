import Foundation
import Testing
@testable import YPtunAppleDataMigration

@Test func atomicFileIOPreservesArbitraryBytesAcrossReplacement() throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let url = fixture.root.appendingPathComponent("atomic/payload.bin")
    let first = Data([0x00, 0x01, 0xfe, 0xff])
    let second = Data((0..<8_192).map { UInt8($0 % 251) })

    try AtomicFileIO.writeBytesAtomically(first, to: url)
    #expect(try AtomicFileIO.readBytes(at: url) == first)

    try AtomicFileIO.writeBytesAtomically(second, to: url)
    #expect(try AtomicFileIO.readBytes(at: url) == second)
    let snapshot = try #require(AtomicFileIO.snapshot(at: url))
    #expect(snapshot.byteCount == second.count)
    #expect(snapshot.sha256 == AtomicFileIO.sha256(of: second))
}

@Test func atomicFileIORefusesToReplaceADirectory() throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let directory = fixture.root.appendingPathComponent("not-a-file")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

    #expect(throws: AtomicFileError.self) {
        try AtomicFileIO.writeBytesAtomically(Data("data".utf8), to: directory)
    }
}

@Test func atomicFileIOCanRequireAnAbsentDestination() throws {
    let fixture = try MigrationFixture()
    defer { fixture.remove() }
    let url = fixture.root.appendingPathComponent("create-only.bin")
    let original = Data("original".utf8)
    try AtomicFileIO.writeBytesAtomically(original, to: url)

    #expect(throws: AtomicFileError.self) {
        try AtomicFileIO.writeBytesAtomically(
            Data("replacement".utf8),
            to: url,
            replacingExisting: false
        )
    }
    #expect(try AtomicFileIO.readBytes(at: url) == original)
}
