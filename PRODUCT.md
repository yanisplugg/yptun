# YPtun Apple Clients

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

People who need a dependable VPN and censorship-circumvention client on iPhone, iPad, and Mac. The common path is a fast, understandable one-tap connection; advanced users also manage subscriptions, custom proxy profiles, routing rules, stealth transports, logs, and server health.

## Product Purpose

YPtun provides one Apple-native client for importing, organizing, testing, and running standard and censorship-resistant tunnel configurations. Success means the selected connection starts reliably, survives network changes, explains failures clearly, and preserves the user's existing TestFlight data during the migration from Kotlin Multiplatform to Swift.

## Positioning

YPtun combines conventional sing-box/Xray proxy support with specialized transports such as WARP, AmneziaWG, olcRTC, VK-TURN, and MasterDNS behind one shared Rust decision engine. Swift owns Apple UI and system APIs; packet traffic stays in native tunnel engines and never crosses the Rust FFI boundary.

## Operating Context

- The main app manages locations, subscriptions, routing profiles, imports, settings, logs, and connection controls.
- A Network Extension packet-tunnel process owns the live VPN session.
- The app, widget, and extension exchange small atomic snapshots through the existing App Group.
- Connections may be started from the app, Settings, widgets, App Intents, or Control Center.
- Development may happen off macOS, so GitHub Actions is a required Apple build and TestFlight verification path.

## Capabilities and Constraints

- Apple work is isolated to `feat/testflight-deploy`; Android and PC clients retain their own stacks and are out of scope.
- Minimum versions are iOS 17 and macOS 14.
- Both iOS and macOS use `NEPacketTunnelProvider`.
- macOS ships through both Mac App Store/TestFlight and a Developer ID-signed, notarized DMG.
- Kotlin/Native, Compose Multiplatform, `SharedUI.framework`, and Gradle are removed from final Apple artifacts. Kotlin remains elsewhere in the repository for non-Apple clients.
- Shared product logic comes from `yptun-core` through generated UniFFI Swift bindings. Swift remains the executor for NetworkExtension, Network.framework, Keychain, App Intents, widgets, and other Apple APIs.
- Existing bundle identifiers, App Group identifiers, saved locations, subscriptions, routing profiles, active selection, and device identity must survive the update.
- Turnable and Mieru are part of the native Apple parity target. Because Network Extensions cannot
  launch subprocesses, both run in-process inside the packet-tunnel extension; their GPL notices and
  exact upstream revisions ship with the app.
- The iOS extension must remain within realistic Network Extension memory limits and cannot depend on subprocesses.

## Brand Commitments

- Preserve the YPtun name, recognizable cat asset, existing feature hierarchy, terminology, and core navigation structure.
- The Apple clients should feel authored for Apple platforms rather than like an Android interface embedded on iPhone or Mac.
- System Liquid Glass may enhance navigation, status surfaces, and compact controls on supported OS versions, but must remain restrained and fall back to ordinary system materials on earlier supported releases.

## Evidence on Hand

- Existing Apple implementation and assets: `YPtun/iosApp/`.
- Existing Apple platform behavior: `YPtun/sharedUI/src/iosMain/` and `YPtun/sharedUI/src/macosMain/`.
- Current shared Rust logic and compatibility tests: `core/yptun-core/`.
- Current UniFFI surface: `core/yptun-ffi/`.
- App Store and TestFlight pipeline: `.github/workflows/ios.yml` and `appstore_info/`.
- Architecture and prior iOS audit: `APP_STRUCTURE.md` and `IOS_AUDIT_BUGS_AND_RISKS.md`.

## Product Principles

1. Keep the common connection path calm and immediate; reveal advanced networking detail only when requested.
2. Put connection decisions in Rust and platform execution in Swift, with no packet-by-packet FFI traffic.
3. Preserve user data and identifiers across the migration; a normal update must never feel like a reinstall.
4. Prefer system behavior, accessibility, and legibility over custom chrome, especially inside operational settings.
5. Treat reliability, actionable errors, and network handoff recovery as user-facing product features.

## Accessibility & Inclusion

Use Dynamic Type, VoiceOver labels and values, sufficient contrast, Reduce Motion, Reduce Transparency, keyboard navigation on macOS, and minimum 44-point touch targets on iOS. The interface must remain usable in light and dark appearances and with longer localized strings.
