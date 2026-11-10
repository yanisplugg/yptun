# Native Apple shell

This directory contains the additive pure-Swift Apple application skeleton. It does not replace or
modify the existing Kotlin Multiplatform iOS project in `YPtun/iosApp`.

## Targets

| Target | Platform | Bundle identifier |
| --- | --- | --- |
| `YPtunIOS` | iOS 17+ app | `org.yptun.app` |
| `YPtunPacketTunnelIOS` | iOS 17+ packet tunnel | `org.yptun.app.tunnel` |
| `YPtunWidget` | iOS 17+ widget | `org.yptun.app.widget` |
| `YPtunMac` | macOS 14+ app | `org.yptun.app.macos` |
| `YPtunPacketTunnelMac` | macOS 14+ packet tunnel | `org.yptun.app.macos.tunnel` |

The existing iOS App Group remains `group.org.yptun.app`. The macOS skeleton uses
`group.org.yptun.app.macos` so its signing and stored state can evolve independently.

The macOS tunnel is initially packaged as an app extension (`.appex`), which is appropriate for
development signing and Mac App Store distribution. A direct Developer ID release would instead
need a separate system-extension target and the `packet-tunnel-provider-systemextension`
entitlement; that distribution-specific target is intentionally outside this initial skeleton.

`Packages/YPtunAppleKit` establishes two initial shared boundaries:

- `YPtunShared` contains cross-target identity and state values.
- `YPtunTunnelSupport` contains tunnel bootstrap/error surfaces and depends on the local
  `Packages/YPtunRustBridge` package.
- `YPtunMigrationSupport` validates Kotlin-era location/settings payloads with Rust before the
  byte-preserving migration package copies them into native storage.

The views and packet tunnel provider are intentionally structural placeholders. In particular,
`startTunnel` returns an implementation-pending error until the Rust session and packet flow are
wired together.

## Prerequisites

- macOS with Xcode 16 or newer selected by `xcode-select`
- XcodeGen (`brew install xcodegen`)
- Rust through `rustup`, including the targets listed in `core/scripts/build-apple.sh`
- An Apple Developer team/profiles with Network Extension and App Group capabilities

## Generate and build

From the repository root, generate the local UniFFI bindings and XCFramework first. These build
outputs are intentionally ignored by Git.

```bash
bash core/scripts/build-apple.sh
cd YPtun/apple
xcodegen generate
open YPtunApple.xcodeproj
```

Select a development team for all five targets. Ensure the identifiers and App Groups above exist
in the developer account, then build the `YPtun iOS` or `YPtun macOS` scheme.

Command-line smoke builds can be run after package resolution and signing setup:

```bash
xcodebuild -project YPtunApple.xcodeproj \
  -scheme "YPtun iOS" \
  -destination "generic/platform=iOS Simulator" \
  CODE_SIGNING_ALLOWED=NO build

xcodebuild -project YPtunApple.xcodeproj \
  -scheme "YPtun macOS" \
  CODE_SIGNING_ALLOWED=NO build
```

## macOS verification still required

Windows can validate the YAML and XML structure, but the following require macOS/Xcode:

1. Run `core/scripts/build-apple.sh` and resolve both local Swift packages.
2. Run `xcodegen generate` and inspect extension embedding in both host apps.
3. Compile every target in Swift 6 mode with complete concurrency checking.
4. Confirm provisioning accepts the Network Extension and App Group entitlements for each bundle.
5. Install signed builds on a physical iOS 17+ device and a macOS 14+ machine; packet tunnel
   extensions cannot be meaningfully activated in the unsigned simulator smoke build.
6. Confirm the intended macOS distribution channel before adding release signing; Developer ID
   distribution requires the separate system-extension shape described above.
