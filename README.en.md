<div align="center">

# YPtun

### Fast censorship-resistant VPN · Android

*VLESS · Reality · XHTTP over **Xray** and **sing-box**, **Hysteria2** (QUIC), obfuscated **AmneziaWG**, a tunnel through **VK-TURN** calls, the **DNSTT** DNS tunnel, a standalone Telegram proxy over **WARP** — and **olcRTC**, which disguises traffic as a video call.*

<br>

[![Latest release](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=downloads)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Stars](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Cores](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)

<br>

[Русский](README.md) · **English** · [فارسی](README.fa.md) · [简体中文](README.zh.md)

</div>

---

## Why YPtun?

Most VPN clients give you one core and one way to connect. **YPtun gives you a toolbox.** Several censorship-bypass engines in a single app: when one method gets blocked, switch to another and keep going.

> **The point is versatility.** Xray and sing-box with every common protocol and transport, obfuscated WireGuard via AmneziaWG, tunnelling through real calls (VK-TURN and olcRTC), the DNSTT DNS tunnel, import of basically anything, and Happ-compatible routing profiles. Kill one path — there are several more next to it.

> Built for places where the internet fights back — Russia, Iran, and any country where sites vanish without warning.

> **Desktop is coming** — native Windows and Linux builds are in the works.

---

## What's new in 2.6.1

| | |
|---|---|
| **Auto-connect to the fastest** | An "Auto" button next to the connect button: it proxy-pings every ready server in parallel with a real handshake (not just TCP/ICMP), connects to the fastest, and advances on failure. The button stays available while connected, so a tap re-rolls onto the new fastest server. |
| **ASN-based routing** | A new `asn:62041` (Telegram), `asn:13335` (Cloudflare) selector in routing profiles — it catches **all** of an operator's networks, including bare-IP services that domain lists miss. Expanded to real ranges on the fly; works on both cores. One-tap presets in the editor. |
| **Speed on the home screen** | An optional `↓ / ↑` line under the selected configuration (a settings toggle, off by default). |
| **Telegram proxy over WARP** | A standalone background proxy: it raises an AmneziaWG Cloudflare WARP tunnel and exposes a local SOCKS5 for Telegram. Runs independently of the main VPN and rotates off dead WARP endpoints automatically. |
| **Two-proxy cascade** | A second (exit) proxy on top of the main one — including over an xhttp connection via local SOCKS, with proper `xmux` and XTLS Vision; DNS resolves over the cascade via TCP/DoH. |

---

## Features

| | |
|---|---|
| **Multiple engines** | Xray, sing-box, AmneziaWG, VK-TURN, DNSTT — the core is picked per protocol automatically or by hand. |
| **Protocols** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **Transports** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · uTLS fingerprints |
| **DNSTT (DNS tunnel)** | A tunnel over DNS queries (KCP + Noise) — works where all other traffic is blocked but DNS still flows. One-tap server install on a VPS over SSH. |
| **Telegram proxy over WARP** | A lightweight background service: a WARP tunnel + a local SOCKS5 for Telegram, independent of the main connection. |
| **olcRTC** | The [olcRTC](https://github.com/openlibrecommunity/olcrtc) transport — traffic rides real video-call services (Jazz, Telemost, WB Stream, Jitsi); to DPI it looks like a live call, not a proxy. |
| **Smart import** | vless/vmess/trojan/ss links, base64, JSON panels, **full raw Xray / sing-box configs** (applied as-is), AmneziaWG `.conf`/QR, olcRTC URIs, Happ profiles, bulk link-list import. |
| **DNS & routing** | Happ-compatible routing profiles (block/direct/proxy by `geoip:`/`geosite:`/`asn:`/domains/CIDR), v2rayNG-style per-rule routing, a "block RU domains" toggle, custom DNS and fakedns. |
| **Auto server pick** | One-tap connect to the fastest reachable node, with failover. |
| **HTTP proxy** | A Happ-compatible local HTTP proxy on top of the active engine. |
| **DPI evasion** | TLS fragmentation, multiplexing, AmneziaWG obfuscation, QUIC blocking where it would leak. |
| **No leaks** | Captures both IPv4 and IPv6 — nothing slips past the tunnel. |
| **Split tunneling** | Choose which apps go through the VPN. |
| **Subscriptions** | Auto-update (toggle per subscription), reachable-server counter, server descriptions, traffic/quota, groups with collapse/pin/ping-sort, folders. |

---

## Download

Grab the latest signed APK from the **[releases page](https://github.com/yanisplugg/olcvpn-client/releases/latest)**.

| Build | For |
|-------|-----|
| **`arm64-v8a`** | Modern phones — pick this if unsure |
| `armeabi-v7a` | Older 32-bit devices |
| `x86_64` | Emulators / x86 tablets |
| `universal` | One file for everything (largest) |

Minimum is **Android 6.0** (API 23).

---

## How it works

```
┌──────────────┐  packets   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│     Apps     │ ─────────▶ │  Android TUN  │ ─────────▶ │     Engine (1 process)     │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  DNSTT / olcRTC      │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                 open internet
```

Every native core is built into **one** `gomobile` library (a single Go runtime), so Xray, sing-box, AmneziaWG, VK-TURN, DNSTT and olcRTC coexist in one process without conflicts. The app raises a `VpnService`, feeds packets into the TUN, and wraps them in the chosen engine through a local SOCKS5.

---

## Engines in plain words

- **Xray / sing-box** — classic proxy cores: VLESS+Reality, XHTTP, WS+TLS, etc. The core is chosen per transport automatically.
- **AmneziaWG** — WireGuard with obfuscation: the handshake and packets don't look like "plain" WireGuard, which is often cut by signature.
- **Hysteria2** — a fast QUIC-based protocol with Salamander obfuscation and port hopping; holds speed well on lossy links.
- **VK-TURN** — raises a local WireGuard and pushes it through VK's call TURN servers; several "calls" are bonded for throughput.
- **DNSTT** — a tunnel over DNS queries; works where only DNS is open.
- **olcRTC** — video-call disguise: traffic rides real conferencing services and looks like a live call to DPI.
- **Telegram proxy over WARP** — a standalone background proxy for Telegram on top of Cloudflare WARP.

---

## Build from source

Everything needed is already vendored (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `hysteria2proxy`, `free-turn-proxy`, `dnstt`, `wdtt`, `amneziawg-go`). You'll need:

- **JDK 17** (the one bundled with Android Studio works)
- **Android SDK** (set `sdk.dir` in `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) on `PATH`

> `gomobile` invokes `javac`, so put the JDK's `bin/` on `PATH` — not just `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.6.1 -Polcbox.versionCode=286
```

APKs land in `YPtun/androidApp/build/outputs/apk/release/`.
Want a faster, phone-only build? Add `-Polcbox.android.abiFilters=arm64-v8a`.

<details>
<summary>Signing your own release builds (optional, for maintainers)</summary>

<br>

By default Gradle produces debug-signed APKs. To publish your own signed releases, create a keystore and point `YPtun/keystore.properties` at it:

```properties
storeFile=release.keystore
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

That file (and the `.keystore`) are git-ignored and never committed — they live only on your machine. Keep the keystore safe: updates are signed with the same key so they install over previous versions.

</details>

---

## Development

YPtun is **Kotlin Multiplatform**: all the logic (import, config building, engines, UI state) lives in `commonMain`, with platform bits in `androidMain`. The same code runs on the JVM desktop.

- **UI** — Jetpack Compose, one design across platforms.
- **Localization** — Russian, English, فارسی and 简体中文 in a single strings file.
- **Native cores** — Go, built into one gomobile AAR by the `buildCoresAndroidAar` task; core inputs are tracked, so the AAR rebuilds only when Go code changes.
- **Tests** — unit tests for the routing parsers/converters (`./gradlew :sharedUI:jvmTest`).
- **Branches** — stable on `main`, active development on `Beta`; releases are tagged `vX.Y.Z`.

Found a bug or want a feature? Open an issue or PR — see **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## Project structure

```
YPtun/            Kotlin Multiplatform app — Compose UI, Android VpnService, engines
cores/            Go glue: one gomobile AAR from sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + DNSTT
olcrtc/           olcRTC — video-call disguise transport          (third-party, vendored)
sing-box/         sing-box / libbox                                (vendored)
awgproxy/         AmneziaWG wrapper → local SOCKS5                 (Go module)
hysteria2proxy/   Hysteria2 (apernet) wrapper → local SOCKS5       (Go module)
free-turn-proxy/  VK-TURN — tunnel through VK calls                (Go module)
dnstt/            DNSTT — tunnel over DNS                           (client + server)
wdtt/             WDTT — tunnel variant                            (client + server)
amneziawg-go/     AmneziaWG implementation                         (vendored)
```

---

## Roadmap

- [x] Android release
- [x] AmneziaWG, VK-TURN and DNSTT engines
- [x] Routing profiles (Happ-compatible) + ASN
- [ ] **Windows** build — *soon*
- [ ] **Linux** build — *soon*

> The shared engine already runs on the JVM (`desktopApp`), so desktop is next.

---

## Contributing

PRs and issues welcome. Before you start, see:
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — how to build, format and submit changes
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — community rules
- **[SECURITY.md](SECURITY.md)** — how to report a vulnerability

---

## Credits

Standing on the shoulders of giants:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## License

[GPL-3.0](LICENSE) — the app ships under the GNU GPL v3.0 because it bundles **sing-box** (also GPL-3.0): copyleft applies to the whole product. Vendored components keep their own licenses (`sing-box` — GPL-3.0, Xray — MPL-2.0, `amneziawg-go` — MIT, `olcrtc` — WTFPL).

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="No censorship" width="150">

<br><br>

> *"A nation that is afraid to let its people judge the truth and falsehood in an open market is a nation that is afraid of its people."*
>
> — **John F. Kennedy**

<br>

<sub>For a free internet</sub>

</div>
