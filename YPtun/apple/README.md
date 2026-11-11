# Native Apple shell

This directory contains the pure-Swift Apple application and packet-tunnel targets that replace the
Kotlin Multiplatform Apple client. The previous project remains in `YPtun/iosApp` only as migration
evidence until the native release has completed its TestFlight soak.

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

`Packages/YPtunAppleKit` establishes the shared boundaries:

- `YPtunShared` contains cross-target identity and state values.
- `YPtunTunnelSupport` contains tunnel bootstrap/error surfaces and depends on the local
  `Packages/YPtunRustBridge` package.
- `YPtunMigrationSupport` validates Kotlin-era location/settings payloads with Rust before the
  byte-preserving migration package copies them into native storage.
- `YPtunAppUI` contains the adaptive SwiftUI app, import flow, VPN control, logs and settings.

The packet tunnel executes Rust-generated plans through in-process Apple cores. Standard sing-box
and Xray profiles, olcRTC, AmneziaWG, WARP chaining, Mieru, Turnable, free-turn, qWDTT, CSQTT,
MasterDNS, OpenFlux and snolc share the same `NEPacketTunnelProvider` topology and
hev-socks5-tunnel bridge. Unsupported or incomplete combinations fail before network settings are
installed instead of presenting a false connected state.

## Prerequisites

- macOS with Xcode 16 or newer selected by `xcode-select`
- XcodeGen (`brew install xcodegen`)
- Rust through `rustup`, including the targets listed in `core/scripts/build-apple.sh`
- An Apple Developer team/profiles with Network Extension and App Group capabilities

## Generate and build

From the repository root, generate the UniFFI, Go and specialized core XCFrameworks first. These
build outputs are intentionally ignored by Git.

```bash
bash core/scripts/build-apple.sh
bash core/scripts/build-go-apple.sh
bash core/scripts/build-specialized-apple.sh
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

## Release candidate

Run the **Native Apple release candidate** workflow with a fresh build number. It produces an
unsigned IPA on every run and a signed `YPtun-ios-signed` artifact when the existing distribution
certificate and three provisioning-profile secrets are configured. It never uploads to TestFlight.
The signed artifact can be uploaded explicitly with the existing iOS workflow's
`testflight_from_run` input after reviewing the build and device tests.

## Physical-device verification still required

Windows can validate the YAML and XML structure, but the following require macOS/Xcode:

1. Confirm provisioning accepts the Network Extension and App Group entitlements for each bundle.
2. Install signed builds on a physical iOS 17+ device and a macOS 14+ machine; packet tunnel
   extensions cannot be meaningfully activated in the unsigned simulator smoke build.
3. Exercise every in-process engine used by the release set and inspect `tunnel.log` for watchdog,
   memory-pressure and handoff failures.
4. Confirm the intended macOS distribution channel before adding release signing; Developer ID
   distribution requires the separate system-extension shape described above.
