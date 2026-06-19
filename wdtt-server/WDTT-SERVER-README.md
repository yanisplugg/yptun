# wdtt-server

The VPS-side server for the WDTT VK-TURN transport core. The Android client (VK-TURN engine, WDTT
core) connects to it over VK TURN relays; this server terminates the WRAP+DTLS transport, hands the
client its WireGuard config (`GETCONF`), runs a userspace WireGuard tunnel and NATs traffic to the
internet.

## Provenance / license

`server.go` is vendored verbatim (module renamed to `wdtt-server`) from the root `server.go` of
**github.com/amurcanov/proxy-turn-vk-android** (GPLv3). Only the Go module name was changed so it
builds standalone next to the client module (`wg-turn-client`, in `../wdtt`). The matching client is
the same project's `go_client/`, already vendored at `../wdtt`.

## Build + bundle

`build-wdtt-server.ps1` cross-compiles linux/amd64 + linux/arm64, gzips each binary and writes them
to `../YPtun/androidApp/src/main/assets/wdtt/`. The in-app installer
(`WdttServerInstaller`) picks amd64/arm64 from the VPS `uname -m`, SFTPs the gzip in and runs it as a
systemd service. Rerun this script after updating `server.go`.

## Flags (set by the installer's systemd unit)

```
-listen 0.0.0.0:56000   DTLS listener (the client's "wdtt-server port")
-wg-port 56001          internal userspace WireGuard UDP port (default)
-password <pass>        WDTT connection password; the WRAP key is HKDF-derived from it
-dns 1.1.1.1            DNS handed to clients in their WireGuard config
-config-dir /etc/wdtt   persistent DB / keys
-admin / -bot-token     optional Telegram admin bot (unused by the app installer)
```

The server configures IP forwarding, iptables/nft MASQUERADE + FORWARD and BBR by itself at startup,
so it must run as root (the systemd unit does).
