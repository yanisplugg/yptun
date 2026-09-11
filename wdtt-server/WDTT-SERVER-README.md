# wdtt-server (WDTT Plus)

The VPS-side server for the WDTT VK-TURN transport core. The client (VK-TURN engine, WDTT core, on
Android and desktop) connects to it over VK TURN relays; this server terminates the WRAP+DTLS
transport, hands the client its WireGuard config (`GETCONF`), runs WireGuard (kernel or userspace) and
NATs traffic to the internet.

## Provenance / license

The `*.go` files are the root package of **github.com/Ivan4537/WDTT-Plus** (GPLv3), release v17
(commit abf0a0f), vendored verbatim; only the Go module name was changed (`wg-turn-client` →
`wdtt-server`) so it builds standalone next to the client module in `../wdtt` (WDTT Plus `go_client/`).
To update: copy the root `*.go`, `go.mod`, `go.sum` over, rename the module again, rerun the build.

## Build + bundle

`build-wdtt-server.ps1` cross-compiles linux/amd64 + linux/arm64, gzips each binary and writes them
to `../YPtun/androidApp/src/main/assets/wdtt/` (the desktop build picks them up from there too). The
in-app installer (`SshWdttServerInstaller`) picks amd64/arm64 from the VPS `uname -m`, uploads the
gzip and installs it following the WDTT Plus deploy contract: binary `/usr/local/bin/wdtt-server`,
unit `/etc/systemd/system/wdtt.service`, data in `/etc/wdtt`. A pre-Plus install (unit
`wdtt-server.service`) is stopped and its `/etc/wdtt` moved aside, since the Plus server refuses to
start on a database it does not recognise.

## Flags (set by the installer's systemd unit)

```
-listen 0.0.0.0:56000      DTLS listener (the client's "wdtt-server port")
-password <pass>           owner (main) password; the WRAP key is HKDF-derived from it
-dns 1.1.1.1               DNS handed to clients in their WireGuard config
-config-dir /etc/wdtt      persistent DB (passwords.json) / keys (wg-keys.dat)
-wg-backend auto           WireGuard backend: auto, kernel or userspace
-max-workers-per-access N  concurrent DTLS workers per access (0 = no limit)
-max-client-mbps N         bandwidth cap per access (0 = none)
-admin / -bot-token        optional Telegram admin bot
```

The server configures IP forwarding, iptables/nft MASQUERADE + FORWARD and BBR by itself at startup,
so it must run as root (the systemd unit does). `wdtt-server --version` prints the server version.
