<div align="center">

# YPtun

### Fast censorship-resistant VPN · Android, Windows and Linux · iOS in beta

*VLESS · Reality · XHTTP over **Xray** and **sing-box**, **Hysteria2** (QUIC), obfuscated **AmneziaWG**, a tunnel through **VK-TURN** calls, the **MasterDNS** DNS tunnel, a standalone Telegram proxy over **WARP** — and **olcRTC**, which disguises traffic as a video call.*

<br>

[![Latest release](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=downloads)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Stars](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Windows%2010%2B-0078d4?style=flat-square&logo=windows&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Linux%20.deb-fcc624?style=flat-square&logo=linux&logoColor=black)
![Platform](https://img.shields.io/badge/platform-iOS%20beta-8e8e93?style=flat-square&logo=apple&logoColor=white)
![Cores](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)

<br>

[Русский](README.md) · **English** · [فارسی](README.fa.md) · [简体中文](README.zh.md)

</div>

---

## Why YPtun?

Most VPN clients give you one core and one way to connect. **YPtun gives you a toolbox.** Several censorship-bypass engines in a single app: when one method gets blocked, switch to another and keep going.

> **The point is versatility.** Xray and sing-box with every common protocol and transport, obfuscated WireGuard via AmneziaWG, tunnelling through real calls (VK-TURN and olcRTC), the MasterDNS DNS tunnel, import of basically anything, and Happ-compatible routing profiles. Kill one path — there are several more next to it.

> Built for places where the internet fights back — Russia, Iran, and any country where sites vanish without warning.

> **Windows is here** — an installer and a portable build as a single `.exe`, x64 and native ARM64. The same app and the same engines as on the phone: subscriptions, routing profiles, cascade, VK-TURN, olcRTC, MasterDNS, Trust Tunnel.

---

## What's new in 3.5.0

| | |
|---|---|
| **WDTT Plus replaces WDTT** | Client and server on Android and desktop: an "RT network" mode (TURN/TLS and TCP, UDP as fallback), a Cloudflare WARP fallback, backup VK hashes, your own VK IDs and keys, a manual TURN address. ⚠️ An old WDTT server does not work with the new client — reinstall it with the "Auto-install" button in the location settings. freeturn locations are not affected. |
| **New OpenFlux engine** | A TCP tunnel to your own exit node through Yandex Docs or a MAX call — for when everything else is blocked. The node installs on a VPS in one tap, DNS goes through the tunnel itself, and "Proxy over OpenFlux" adds end-to-end encryption. Experimental and not fast. |
| **QR scanner rewritten** | zxing-cpp recognition reads blurry, tilted, dense and inverted codes; tap to focus, pinch to zoom, torch, automatic zoom on flagships with large sensors. It also accepts the QR codes the app itself draws (`yptun://`, `hysteria2://`, `naive+https://`, `tt://`, `happ://`). |
| **VK-TURN** | The second proxy on top of AmneziaWG can go through Xray (xhttp and raw config), the exit MTU is capped at 1200, freeturn connects faster. An AmneziaWG exit without a second proxy no longer loses DNS. |
| **Routing from JSON subscriptions** | Every server of a subscription now gets the full config with its rules, not only xhttp ones. Russian sites that a subscription routes through `dns.hosts` open directly again in "IPv4 only" mode. |
| **Subscriptions keep their servers** | When a subscription grows, the last server no longer disappears and the selected one no longer jumps to its neighbour. Thanks @Zamotashka (#41). |
| **Smaller fixes** | Half of the screen went black after pasting in the location editor (#40); on Windows the VK captcha opens in the browser instead of an Explorer window. |

---

## Features

| | |
|---|---|
| **Multiple engines** | Xray, sing-box, AmneziaWG, VK-TURN, MasterDNS — the core is picked per protocol automatically or by hand. |
| **Protocols** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **Transports** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · uTLS fingerprints |
| **MasterDNS (DNS tunnel)** | A tunnel over DNS queries (MasterDnsVPN: custom ARQ transport, several resolvers at once, packet duplication) — works where all other traffic is blocked but DNS still flows. One-tap server install on a VPS over SSH. |
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

## Permissions and why they are needed

YPtun asks only for what a specific feature cannot work without. Camera, notifications, unrestricted battery use and installing updates are requested when you use that feature, not at install time.

### Android

| Permission | Why |
|---|---|
| **VPN** (`BIND_VPN_SERVICE`) | The system "Allow VPN connection" prompt on the first connect. Without it the app cannot raise the tunnel and route traffic through it. |
| **Internet and network state** (`INTERNET`, `ACCESS_NETWORK_STATE`) | Connecting to servers, pings, fetching subscriptions; reconnecting when you switch between Wi-Fi and mobile data. |
| **Running in the background** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`) | The VPN and the Telegram proxy run as services with a notification so the system does not stop them while the screen is off. |
| **Notifications** (`POST_NOTIFICATIONS`, Android 13+) | The connection notification with speed and a disconnect button — Android requires it for a background service. |
| **Unrestricted battery** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Only from a button in the settings: a Doze exemption so the tunnel does not drop overnight. Never requested without your tap. |
| **Camera** (`CAMERA`) | The QR scanner for importing servers. Requested the first time you open the scanner; frames are processed on the phone and never sent anywhere. Without a camera you can import a QR from an image file. |
| **Installed apps** (`QUERY_ALL_PACKAGES`) | Split tunneling: listing every installed app, including system ones without an icon, so you can pick which go through the VPN. The list never leaves the phone. |
| **Install apps** (`REQUEST_INSTALL_PACKAGES`) | In-app updates: the downloaded APK is handed to the system installer, which still asks you to confirm. |
| **Tasks after reboot** (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_DATA_SYNC`) | Added by the WorkManager library so a periodic task survives a reboot. There is one such task — the daily check-in for subscriptions with a Happ `providerid` (see below). YPtun has no autostart of its own. |
| **Data migration** (`org.olcbox.app.permission.MIGRATION`) | The app's own signature-level permission: it moves settings from the old `org.olcbox.app` install to the new `org.yptun.app`. Only an APK with the same signature can read that data. |

### Windows

| Right | Why |
|---|---|
| **Administrator** (UAC) | Only for "Tunnel" mode: creating the network adapter (wintun) and adding routes. The prompt appears once at launch when that mode is selected. "Proxy" mode needs no administrator rights. |
| **System proxy** | In "Proxy" mode the app sets the Windows proxy for the current user and restores the previous settings on disconnect. If the app crashed, the leftover proxy is removed on the next launch. |
| **Process list** | Per-process split tunneling — and detecting another running VPN client that breaks the connection. The app offers to close it but never terminates anything without your consent. |

### What the app contacts besides your servers

- **GitHub** — update checks and, by default, the routing lists (geoip/geosite, ASN).
- **`check.happ-proxy.com`** — only if a subscription carries a Happ `providerid`: once a day, like Happ itself, the app sends a check-in with the same device data the subscription already receives (HWID, OS, model, app version). Without a `providerid` there is no such request.
- **Services of the connection method you chose** — VK (VK-TURN), Cloudflare (Telegram over WARP), call services (olcRTC), Yandex Docs or MAX (OpenFlux).

---

## How it works

```
┌──────────────┐  packets   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│     Apps     │ ─────────▶ │  Android TUN  │ ─────────▶ │     Engine (1 process)     │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  MasterDNS / olcRTC      │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                 open internet
```

Every native core is built into **one** `gomobile` library (a single Go runtime), so Xray, sing-box, AmneziaWG, VK-TURN, MasterDNS and olcRTC coexist in one process without conflicts. The app raises a `VpnService`, feeds packets into the TUN, and wraps them in the chosen engine through a local SOCKS5.

---

## Engines in plain words

- **Xray / sing-box** — classic proxy cores: VLESS+Reality, XHTTP, WS+TLS, etc. The core is chosen per transport automatically.
- **AmneziaWG** — WireGuard with obfuscation: the handshake and packets don't look like "plain" WireGuard, which is often cut by signature.
- **Hysteria2** — a fast QUIC-based protocol with Salamander obfuscation and port hopping; holds speed well on lossy links.
- **VK-TURN** — raises a local WireGuard and pushes it through VK's call TURN servers; several "calls" are bonded for throughput.
- **MasterDNS** — a tunnel over DNS queries; works where only DNS is open.
- **olcRTC** — video-call disguise: traffic rides real conferencing services and looks like a live call to DPI.
- **Telegram proxy over WARP** — a standalone background proxy for Telegram on top of Cloudflare WARP.

---

## Build from source

Everything needed is already vendored (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `hysteria2proxy`, `free-turn-proxy`, `masterdns`, `wdtt`, `amneziawg-go`). You'll need:

- **JDK 17** (the one bundled with Android Studio works)
- **Android SDK** (set `sdk.dir` in `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) on `PATH`

> `gomobile` invokes `javac`, so put the JDK's `bin/` on `PATH` — not just `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=3.5.0 -Polcbox.versionCode=352
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
cores/            Go glue: one gomobile AAR from sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + MasterDNS
olcrtc/           olcRTC — video-call disguise transport          (third-party, vendored)
sing-box/         sing-box / libbox                                (vendored)
awgproxy/         AmneziaWG wrapper → local SOCKS5                 (Go module)
hysteria2proxy/   Hysteria2 (apernet) wrapper → local SOCKS5       (Go module)
free-turn-proxy/  VK-TURN — tunnel through VK calls                (Go module)
masterdns/        MasterDNS — tunnel over DNS                       (client + server)
wdtt/             WDTT — tunnel variant                            (client + server)
amneziawg-go/     AmneziaWG implementation                         (vendored)
```

---

## Roadmap

- [x] Android release
- [x] AmneziaWG, VK-TURN and MasterDNS engines
- [x] Routing profiles (Happ-compatible) + ASN
- [x] **Windows** build (x64 and ARM64)
- [x] **Linux** build (`.deb`, x64 and ARM64)
- [ ] **iOS** build — *in beta*

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
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go) ·
[OpenFlux](https://github.com/p1neappleXpress/OpenFlux) ·
[qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android) ·
[MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN) ·
[free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy) ·
[TrustTunnel](https://github.com/TrustTunnel/TrustTunnelClient) ·
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) ·
[tun2socks](https://github.com/xjasonlyu/tun2socks).

## Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by [SignPath Foundation](https://signpath.org).
Signed are the Windows installer and portable built by [GitHub Actions](.github/workflows/windows-desktop.yml) from this repository.

- Committers and reviewers: [repository contributors](https://github.com/yanisplugg/olcvpn-client/graphs/contributors)
- Approvers: [repository owner](https://github.com/yanisplugg)

Privacy: this program will not transfer any information to other networked systems unless specifically requested by the user (their servers, subscriptions and the circumvention services they choose), apart from checking GitHub for updates. Details are in [Permissions and why they are needed](#permissions-and-why-they-are-needed).

## License

[GPL-3.0](LICENSE) — the app ships under the GNU GPL v3.0 because it bundles **sing-box** (also GPL-3.0): copyleft applies to the whole product. Vendored components keep their own licenses (`sing-box` — GPL-3.0, Xray — MPL-2.0, `amneziawg-go` — MIT, `olcrtc` — WTFPL, OpenFlux — GPL-3.0, qWDTT (`wdtt`) — GPL-3.0, MasterDnsVPN (`masterdns`) — MIT, `free-turn-proxy` — Happy Bunny License (MIT-style), Trust Tunnel — Apache-2.0, `hev-socks5-tunnel` — MIT, `tun2socks` — MIT).

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
