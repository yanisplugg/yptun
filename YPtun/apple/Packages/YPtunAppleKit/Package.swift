// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "YPtunAppleKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "YPtunShared", targets: ["YPtunShared"]),
        .library(name: "YPtunTunnelSupport", targets: ["YPtunTunnelSupport"]),
        .library(name: "YPtunMigrationSupport", targets: ["YPtunMigrationSupport"]),
        .library(name: "YPtunAppUI", targets: ["YPtunAppUI"]),
    ],
    dependencies: [
        .package(path: "../YPtunRustBridge"),
        .package(path: "../YPtunAppleDataMigration"),
    ],
    targets: [
        .target(name: "YPtunShared"),
        .target(
            name: "YPtunTunnelSupport",
            dependencies: [
                "YPtunShared",
                .product(name: "YPtunRustBridge", package: "YPtunRustBridge"),
            ]
        ),
        .target(
            name: "YPtunMigrationSupport",
            dependencies: [
                "YPtunShared",
                .product(name: "YPtunRustBridge", package: "YPtunRustBridge"),
                .product(name: "YPtunAppleDataMigration", package: "YPtunAppleDataMigration"),
            ]
        ),
        .target(
            name: "YPtunAppUI",
            dependencies: [
                "YPtunShared",
                "YPtunMigrationSupport",
            ]
        ),
        .testTarget(
            name: "YPtunAppUITests",
            dependencies: ["YPtunAppUI", "YPtunShared"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
