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
- MasterDNS and non-csqtt VK-TURN paths supported by the in-process core
- UAC only when TUN is requested
- Runtime logs, x64/ARM64 self-contained publishing, single portable EXE with local `Data`, and Inno Setup installer

## Still using the legacy desktop client

- OpenFlux, snolc, Trust Tunnel and csqtt subprocess hosts
- Full location editor and the complete routing/traffic/settings UI
- Tray menu, global hotkey, updater, session diagnostics and free-server search
- ARM64 live-device execution and release signing in CI

The release workflow must stay on the established desktop application until those items are ported
and live Windows VPN tests pass. Local native artifacts are development builds and must not be
presented as signed releases.
