// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "YPtunRustBridge",
    platforms: [
        .iOS(.v16),
        .macOS(.v14),
    ],
    products: [
        .library(name: "YPtunRustBridge", targets: ["YPtunRustBridge"]),
    ],
    targets: [
        .binaryTarget(
            name: "yptun_ffiFFI",
            path: "Artifacts/yptun_ffiFFI.xcframework"
        ),
        .target(
            name: "YPtunRustBridge",
            dependencies: ["yptun_ffiFFI"],
            path: "Sources/YPtunRustBridge"
        ),
    ]
)
