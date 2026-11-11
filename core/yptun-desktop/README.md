# YPtun Rust desktop client

This crate is the single native desktop UI for Windows and Linux. It uses Slint for rendering and
calls the shared Rust application/runtime layer directly. The legacy Compose client and WinUI client
remain available only as migration references until this crate passes parity and live VPN tests.

## Architecture

- `ui/`: one visual implementation for both operating systems. Its visual authority is the legacy
  Compose client, not the temporary WinUI layout.
- `src/backend.rs`: the narrow UI-to-application command boundary. Windows currently delegates to
  the recovered Rust Windows host; Linux is the next platform adapter.
- `yptun-core`: profile, subscription, routing, engine and persistence logic shared with Android.
- platform adapters: TUN, system proxy, tray, global hotkey, startup and update installation only.

The command boundary is temporary migration scaffolding. Models and commands will move into a new
platform-neutral `yptun-desktop-runtime` crate so Windows and Linux cannot drift.

## Development

Keep build output off the system drive:

```powershell
$env:CARGO_TARGET_DIR = "D:\yptun-cache\cargo-target"
cargo run --manifest-path core\Cargo.toml -p yptun-desktop
```
