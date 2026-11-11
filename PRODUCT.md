# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Stack

Delegated by the user: one native Rust desktop client for Windows and Linux, built with Slint. Shared
UI, application state, profile/subscription management and VPN orchestration live in Rust. Thin
platform modules own only OS integration such as TUN, system proxy, tray, startup and hotkeys.

## Users

People using YPtun on Windows or Linux to establish and manage censorship-resistant VPN connections,
subscriptions, routes and split-tunnelling rules from a desktop application.

## Product Purpose

YPtun provides one desktop control surface for the shared Rust/Go VPN engines. A successful desktop
release preserves the legacy client's complete capabilities and data while avoiding separate Windows
and Linux UI implementations.

## Operating Context

The application runs persistently in the system tray, starts and stops privileged networking only
when requested, imports links/files/subscriptions, edits locations and routing, diagnoses sessions,
updates itself and supports installer and portable deployment where the OS permits it.

## Capabilities and Constraints

- Windows and Linux must use the same Rust UI and application-state implementation.
- Existing `%APPDATA%\YPtun` / Linux YPtun data and `locations_v4.json` remain compatible.
- Packet traffic stays in the existing Rust/Go cores; UI messages carry state and commands only.
- Android behavior and its Kotlin UI must remain unchanged.
- Windows x64/ARM64 and Linux amd64/arm64 are release targets.
- Legacy Compose remains the release fallback until the Rust desktop client passes live VPN tests.
- Unsigned or incomplete artifacts are development builds, never final releases.

## Brand Commitments

Keep the YPtun name and existing icon assets. The desktop visual target is the legacy Compose client
from before the WinUI migration: its structure, warm dark/light themes, connection control, location
list, sheets and settings hierarchy are incumbent authority, not material for a redesign.

## Evidence on Hand

- Legacy desktop implementation: `YPtun/desktopApp/src/main/kotlin/main.kt` and
  `YPtun/sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/`.
- Incumbent visual references: `YPtun/docs/screenshots/`.
- Shared Rust domain/runtime: `core/yptun-core`.
- Recovered Windows parity implementation: `core/yptun-windows` and branch
  `windows-full-parity`.

## Product Principles

1. One desktop feature is implemented once for Windows and Linux.
2. Preserve behavior, data and visual familiarity before changing technology.
3. Ask for privilege only at the operation that needs it.
4. Keep networking in tested Rust/Go cores and keep platform seams narrow.
5. Do not replace a working release path until the new path proves parity on real systems.
