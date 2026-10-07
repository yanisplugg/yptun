# csqtt in YPtun

Third VK-TURN core next to freeturn and qWDTT (location editor → VK-TURN → «Ядро» → *csqtt*).
Upstream: <https://github.com/amurcanov/csqtt> @ `71712b07` (`main`, v2.1.9). Vendored here:

| dir | what |
|---|---|
| `rust-client/`, `shared/` | the csqtt client (Rust), unchanged |
| `rust-server/` | the csqtt server (Rust), unchanged — built into the VPS installer's assets |
| `scripts/deploy.sh` | upstream's server installer, uploaded and run by the in-app auto-install |
| `bridge/` | **ours**: Go bridge `csqtthost` (below) |
| `prebuilt/` | binaries built by `build-all.sh`, committed (like `snolc/prebuilt`) |

**License.** csqtt is PolyForm Noncommercial 1.0.0 (`LICENSE`): YPtun may only ship it while it stays non-commercial.

## How it runs

The csqtt client speaks raw IP packets (it was written around an Android TUN fd) over VK TURN/RTP. YPtun's hosts
own the TUN themselves and feed every engine through a local SOCKS5, so — the same shape as qWDTT *Raw* — the
packets go through a userspace gVisor netstack that is served as SOCKS5, and the host treats it like the
AmneziaWG exit (chain proxy, routing and DNS come for free):

```
host (Kotlin)  ──OPTS json / STOP──▶  csqtthost (Go bridge)  ──argv, stdin STOP──▶  csqtt client (Rust)
   ▲  READY ip|dns|mtu, LOG, STATS         │ netstack ◀─raw IP packets over loopback UDP─▶ │ ──VK TURN──▶ csqtt server
   └────────── SOCKS5 on awgLocalPort ◀────┘
```

* `bridge/main.go` documents the line protocol. The tunnel address comes from the server (`TUNCONF:` event);
  closing the bridge's stdin stops everything, so a crashed app never leaves the Rust client behind.
* Captcha: the client asks the host for a WebView (`CAPTCHA_SOLVE|…`); the bridge answers `error:no-webview`
  and the client falls back to its Rust solver. Manual WebView solving is not implemented.
* Android/desktop only (iOS cannot spawn processes; the engine refuses with a clear message).
* Kotlin side: `vpn/csqtt/CsqttBridge.kt` (process driver, shared by Android and desktop),
  `OlcboxVpnService.startVkTurnCore` / `DesktopEngineController.startVkTurn` (branch `usesCsqtt`),
  `VkTurnConfig.csqttCoreOptionsJson`, `CsqttUriParser` (`csqtt://connect?v=2&host=&peer=&password=&hashes=a+b`),
  `SshCsqttServerInstaller` (auto-install).

## Building

`./build-all.sh [client] [server] [bridge]` — needs Rust (the server pins 1.97.1; add the musl/android targets to
it), `cargo-ndk`, `cargo-zigbuild` + `zig` (`pip install ziglang`), the Android NDK and Go.
Outputs: `prebuilt/csqtt-<os>-<arch>`, `prebuilt/csqtthost-<os>-<arch>` and the server as
`YPtun/androidApp/src/main/assets/csqtt/csqtt-server-linux-{amd64,arm64,armv7}.gz` + `deploy.sh`.
Android packs the pair as `lib/<abi>/libcsqtt.so` + `libcsqtthost.so`; the desktop bundles the host's pair.

## Re-vendoring

Diff upstream `rust-client`, `rust-server`, `shared`, `app/src/main/assets/deploy.sh` against these; update
`UPSTREAM.txt`, `CoreVersions.CSQTT`, rebuild. A change of `CSQTT-WIRE-*` (see `deploy.sh`) needs the server
reinstalled — the client and server must come from the same revision.
