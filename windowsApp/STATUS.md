# Native Windows migration status

The native client is being introduced alongside the existing Compose desktop application so a
Windows release never silently loses features during the migration.

## Implemented

- WinUI 3 navigation and native Windows window/chrome
- Shared `%APPDATA%\YPtun` profile storage; existing `locations_v4.json` is reused
- Import from clipboard/file/subscription URL, selection, deletion and subscription refresh
- C# ↔ Rust JSON/C ABI with no packet data crossing the UI boundary
- Shared sing-box/Xray/OpenVPN/AmneziaWG/Hysteria2 engines through the same `yptuncore` used by
  Android
- Native sing-box TUN with per-process rules; external Wintun/tun2socks fallback
- System proxy mode with backup/restore and crash cleanup
- OpenFlux, snolc, Trust Tunnel and csqtt subprocess hosts with Windows Job Object cleanup
- MasterDNS, multi-room, WARP generation and every VK-TURN path supported by the shared controller
- Location create/edit/duplicate/share, subscription group edit/refresh/delete, routing profile import,
  free-server search and latency checks
- Traffic, routing, split-tunnel, SOCKS, updater and app-behavior settings; advanced JSON preserves
  every shared-core field that does not yet have a dedicated native control
- Tray lifecycle, global connect hotkey, startup task, activation forwarding, session watchdog and
  Telegram proxy diagnostics
- Authenticode- and SHA-256-verified updater for installer and portable deployments
- UAC only when TUN is requested
- Runtime logs, x64/ARM64 self-contained publishing, single portable EXE with local `Data`, and Inno Setup installer

## Release blockers

- Live Windows VPN regression across every engine and both proxy/TUN modes
- ARM64 live-device execution
- Authenticode release signing and signed-update validation in CI

The release workflow must stay on the established desktop application until the live regression and
signing gates pass. Local native artifacts are development builds and must not be presented as
signed releases.
