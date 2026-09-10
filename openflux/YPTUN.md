# OpenFlux in YPtun

Vendored from github.com/p1neappleXpress/OpenFlux (GPL-3.0), commit a8a8937 (2026-09-10, release 0.0.1),
without `.idea/` and the iOS/Android shell scripts. Engine `EngineType.OpenFlux`.

## How it runs

As a **subprocess**, not a gomobile library: its transports panic on unexpected answers, and a panic in a
bound library kills the whole app.

- Android: `androidApp` task `buildOpenFluxAndroid` builds `lib/<abi>/libopenflux.so` with the NDK clang
  (CGo is required — the pure-Go resolver has no /etc/resolv.conf on Android). Started from
  `nativeLibraryDir`; the app's own exclusion from the VPN keeps its sockets off the TUN.
- Desktop: `desktopApp` task `buildOpenFluxHost` → `native/openflux-<os>-<arch>[.exe]`. In TUN mode a
  sing-box front owns the adapter (DNS as TCP CONNECT); the carrier's networks are routed around the TUN.
- Exit node: `build-openflux-server.ps1` → `assets/openflux/openflux-server-linux-{amd64,arm64}.gz`,
  installed over SSH as `openflux.service` (secrets in `/etc/openflux/openflux.env`).

## Local patches (re-apply on every re-vendor)

1. `yptun_client.go` (new file): DNS through the tunnel (`--dns`, DNS-over-TCP via the exit node, 5 min
   cache), secrets from the environment (`OPENFLUX_MAX_TOKEN`, `OPENFLUX_SOCKS_USER/PASS`), and
   `--exit-on-stdin-eof` so the client dies with the app.
2. `main.go`: the two flags above, `maxToken = envOr(...)`, and the client's SOCKS server built by
   `yptunClientSetup` instead of `socks5.NewSOCKS5Server`.
3. `socks5/socks5.go`: RFC 1929 username/password (`SetAuth`); upstream always answered "no auth".
   Also (PR #41) the request is read with `io.ReadFull` and errors are answered per RFC 1928 —
   upstream indexed a `[256]byte` buffer, so a ≥250-byte domain panicked the core.
   Tests: `socks5/socks5_auth_test.go`.
4. `transport/oneme/max_transport.go` (PR #41): `MaxClient` kept as a pointer (upstream copied the struct
   by value while its goroutines ran on the original) and `Connect`/`LoginByToken` errors are returned.
