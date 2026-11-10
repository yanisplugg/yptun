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
    ],
    dependencies: [
        .package(path: "../YPtunRustBridge"),
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
    ],
    swiftLanguageModes: [.v6]
)
