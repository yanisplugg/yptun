# YPtun Rust desktop client

This crate is the single native desktop UI for Windows and Linux. It uses Slint for rendering and
calls the shared Rust application/runtime layer directly. The legacy Compose client and WinUI client
remain available only as migration references until this crate passes parity and live VPN tests.

## Architecture

- `ui/`: one visual implementation for both operating systems. Its visual authority is the legacy
  Compose client, not the temporary WinUI layout.
- `src/backend.rs`: the narrow UI-to-application command boundary. Windows delegates to the
  recovered Rust Windows host; Linux uses the shared engine controller through its native adapter.
- `yptun-core`: profile, subscription, routing, engine and persistence logic shared with Android.
- platform adapters: TUN, system proxy, tray, global hotkey, startup and update installation only.

Linux currently runs Standard, Stealth, Chain, MasterDNS, OpenFlux and snolc in proxy or TUN mode.
VK-TURN/Trust Tunnel/Mieru, Telegram proxy, probing and automatic reconnect remain explicit Linux
gaps. The command boundary is temporary migration scaffolding: models and commands will move into a
platform-neutral runtime layer so Windows and Linux cannot drift.

## Development

Keep build output off the system drive:

```powershell
$env:CARGO_TARGET_DIR = "D:\yptun-cache\cargo-target"
cargo run --manifest-path core\Cargo.toml -p yptun-desktop
```
