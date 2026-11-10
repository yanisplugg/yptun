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
                .product(name: "YPtunRustBridge", package: "YPtunRustBridge"),
                .product(name: "YPtunAppleDataMigration", package: "YPtunAppleDataMigration"),
            ]
        ),
    ],
    swiftLanguageModes: [.v6]
)
