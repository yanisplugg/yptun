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
        .library(name: "YPtunVPNControl", targets: ["YPtunVPNControl"]),
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
                "YPtunTunnelSupport",
                .product(name: "YPtunRustBridge", package: "YPtunRustBridge"),
                .product(name: "YPtunAppleDataMigration", package: "YPtunAppleDataMigration"),
            ]
        ),
        .target(
            name: "YPtunVPNControl",
            dependencies: ["YPtunShared"]
        ),
        .target(
            name: "YPtunAppUI",
            dependencies: [
                "YPtunShared",
                "YPtunMigrationSupport",
                "YPtunTunnelSupport",
                "YPtunVPNControl",
            ]
        ),
        .testTarget(
            name: "YPtunAppUITests",
            dependencies: ["YPtunAppUI", "YPtunShared"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
