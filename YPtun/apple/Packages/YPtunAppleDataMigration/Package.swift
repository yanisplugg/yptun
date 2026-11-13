// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "YPtunAppleDataMigration",
    platforms: [
        .iOS(.v16),
        .macOS(.v14),
    ],
    products: [
        .library(
            name: "YPtunAppleDataMigration",
            targets: ["YPtunAppleDataMigration"]
        ),
    ],
    targets: [
        .target(
            name: "YPtunAppleDataMigration",
            path: "Sources/YPtunAppleDataMigration"
        ),
        .testTarget(
            name: "YPtunAppleDataMigrationTests",
            dependencies: ["YPtunAppleDataMigration"],
            path: "Tests/YPtunAppleDataMigrationTests"
        ),
    ],
    swiftLanguageModes: [.v6]
)
