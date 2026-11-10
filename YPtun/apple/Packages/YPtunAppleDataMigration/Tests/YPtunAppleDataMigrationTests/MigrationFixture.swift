import Foundation

struct MigrationFixture {
    let root: URL
    let paths: AppleDataPaths

    init(hasAppGroup: Bool = true) throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("YPtunAppleDataMigrationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        paths = AppleDataPaths(
            applicationSupportRoot: root.appendingPathComponent("ApplicationSupport", isDirectory: true),
            appGroupContainer: hasAppGroup
                ? root.appendingPathComponent("AppGroup", isDirectory: true)
                : nil,
            documentsRoot: root.appendingPathComponent("Documents", isDirectory: true)
        )
    }

    func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try data.write(to: url)
    }

    func regularFiles(below directory: URL) throws -> Set<String> {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        let keys: Set<URLResourceKey> = [.isRegularFileKey]
        let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: Array(keys)
        )
        var files: Set<String> = []
        while let url = enumerator?.nextObject() as? URL {
            let values = try url.resourceValues(forKeys: keys)
            if values.isRegularFile == true {
                files.insert(url.path)
            }
        }
        return files
    }

    func remove() {
        try? FileManager.default.removeItem(at: root)
    }
}
