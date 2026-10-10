# OpenFlux in YPtun

Vendored from github.com/p1neappleXpress/OpenFlux (GPL-3.0), tag v0.4.2 = 74cac6d (2026-10-07; before that v0.3.0 d245db7, d34dc8c = 0.0.3,
3249724, first a8a8937 = 0.0.1), without `.idea/`, `.github/`, `ios-app/`, docker files and the iOS/Android shell scripts.
Engine `EngineType.OpenFlux`.

Re-vendor: export the new tag (strip CRs from the tarball files), `git merge-file` our 2 patched upstream files (`main.go`, `transport/oneme/max_transport.go`)
against the old base, copy the YPtun-only files (`yptun_client*.go`, this file, `build-openflux-server.ps1`) over, then
`go build`/`go test`, the smoke below, `build-openflux-server.ps1`, and bump `CoreVersions.OPENFLUX`.
Carriers (`OpenFluxConfig.TRANSPORTS`): yandex, vyandex (new Volga editor), mailru, cupsonline, oneme (MAX).
Since 0.0.3 the module is `openflux`; since 0.3.0 its path is `github.com/p1neappleXpress/OpenFlux` (upstream's — our files import that).
Flags are `--role=client|exit` + `--inbound=socks5`, the default codec is batched+zstd — an old node does NOT talk to a new
client, reinstall it. The 3-way merge base for the next re-vendor is v0.4.2 (74cac6d). Re-vendor 0.4.2 also dropped `scripts/` and the Docker files;
the `main.go`/`max_transport.go` patches were applied as plain `patch` hunks (they are small, see below).
Mail.ru carriers need an exit built from this core (node-v1.2.2 equivalent): reinstall the node.

Smoke (no real carrier): `--role=exit --mode=l4 --transport direct --direct-listen 127.0.0.1:P --encryption-key-file k` + client
`--transport direct --direct-dial 127.0.0.1:P --encryption-key-file k --socks5 127.0.0.1:Q --dns 1.1.1.1` with
`OPENFLUX_SOCKS_USER/PASS` set and stdin held open (a closed stdin kills the client via `--exit-on-stdin-eof`!);
curl via `socks5h://u:p@127.0.0.1:Q` must return 204 for cp.cloudflare.com, and an unauthenticated client must be refused.

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
2. `main.go` (patch hooks: the two flags, `envOr`, `yptunClientSetup` instead of `socks5.NewSOCKS5Server`; the `socks5` import is gone): the two flags above, `maxToken = envOr(...)`, and the client's SOCKS server built by
   `yptunClientSetup` instead of `socks5.NewSOCKS5Server`.
3. (upstream since 0.3.0, no local patch) `socks5/socks5.go`: RFC 1929 `SetAuth` and `io.ReadFull` request parsing — both were our
   PR #41 / patch before. Note upstream now also does UDP ASSOCIATE for dialers that implement `DialUDP`;
   `tunnelResolvingDialer` deliberately does NOT, so UDP associate stays refused as before (add `DialUDP` to enable).
4. `transport/oneme/max_transport.go` (PR #41): `MaxClient` kept as a pointer (upstream copied the struct
   by value while its goroutines ran on the original) and `Connect`/`LoginByToken` errors are returned.

## Encryption in the client (issue #65)

`OpenFluxConfig.secret/context/negotiate` → `--encryption-key-file <tmp file, deleted 5 s after start>`,
`--session-context`, `--negotiate`. A key without `negotiate` = the core's Classic-compatible Session
(exit serves both); `negotiate` = Session-only. `openflux://v1` links carry `secret`/`context`/`negotiate` and import
for one carrier. Smoke (3 modes, `direct` transport + key file) passes on 0.4.2.
