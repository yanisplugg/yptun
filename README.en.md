<div align="center">

# 🛡️ YPtun

### Fast, censorship-resistant VPN for Android

*VLESS · Reality · XHTTP over **Xray** & **sing-box**, **Hysteria2** (QUIC), obfuscated **AmneziaWG**, a tunnel through **VK-TURN** calls — and, above all, sheer versatility: even **olcRTC** support that makes your traffic look like a video call.*

<br>

[![Latest release](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Stars](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Cores](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)

<br>

[**Русский**](README.md) · **🌍 English** · [**فارسی**](README.fa.md)

</div>

---

## ✨ Why YPtun?

Most VPN clients give you one engine and one way to connect. **YPtun gives you a toolbox.**
It bundles **several circumvention engines** in one app, so when one method gets blocked, you switch and keep going.

> ⭐ **The standout is sheer versatility.** Xray and sing-box with every common protocol and transport, obfuscated WireGuard via **AmneziaWG**, tunneling through real calls (**VK-TURN** and **olcRTC**), import almost anything, and Happ-compatible routing profiles. Block one path and there are several more right next to it.

> Built for places where the internet fights back — for 🇷🇺 Russia, 🇮🇷 Iran and any country where sites vanish without notice. 🌐

> 🖥️ **Desktop coming soon** — native **Windows** and **Linux** builds are in the works.

---

## 🆕 What's new in 2.3

| | |
|---|---|
| 🚀 **Hysteria2 protocol** | Full **Hysteria2** (QUIC) support: import via `hysteria2://`/`hy2://` links and QR, **Salamander** obfuscation, port-hopping, bandwidth tuning. Share a config just like the other protocols. |
| 🔗 **Second (cascade) proxy** | Chain two proxies: traffic exits via the second, dialing through the first. Works over **AmneziaWG** too (including an xhttp exit). |
| 🔔 **Server name in the notification** | When connected, the shade shows the active server (and optional speed). |
| 🗂️ **Sharper subscriptions** | **Per-subscription** auto-update toggle and a **reachable-server** counter ("live/total") from the last ping pass. |
| 🎯 **Fewer false blocks** | Ad-blocking no longer takes down `google.com` and its infrastructure. |
| 🐱 **Polish** | Black launch background, silhouette logo on the Quick Settings tile, correct tile long-press. |

---

## 🆕 What's new in 2.0

| | |
|---|---|
| 🌀 **AmneziaWG engine** | Obfuscated WireGuard (AmneziaWG) — import `.conf`/QR, fine-tune obfuscation (Jc/Jmin/Jmax/S1/S2/H1–H4). Works as a standalone exit or as a link in a chain. |
| 📞 **VK-TURN engine** | A tunnel over the TURN infrastructure of VK calls. Bonds several parallel "calls" for speed; pick the exit: WireGuard / AmneziaWG / proxy. |
| 🧭 **Routing profiles** | Happ-compatible profiles (`happ://routing/add/…`): block/direct/proxy by `geoip:`/`geosite:`/domains/CIDR, a "route everything through proxy" switch, custom DNS and fakedns. Converted to both cores. |
| 🗂️ **Better subscriptions** | Subscription groups can be **collapsed**, **pinned to the top**, and **sorted by ping** — state is remembered. Bulk-import a list of links in one paste. |
| 🛡️ **Fewer leaks** | Unconditional QUIC blocking on transports that can't carry it, plus domain resolution for geoip rules — nothing slips past the tunnel anymore. |
| 🔔 **Notification** | Colored logo and optional live up/down speed right in the shade. |

---

## 🚀 Features

| | |
|---|---|
| 🔀 **Multiple engines** | **Xray**, **sing-box**, **AmneziaWG** and **VK-TURN** — the core is auto-picked per protocol, or forced. |
| 🧬 **Protocols** | VLESS · VMess · Trojan · Shadowsocks · **Hysteria2** · WireGuard / AmneziaWG |
| 🚇 **Transports** | TCP · WS · gRPC · HTTPUpgrade · **XHTTP** · TLS · **Reality** · uTLS fingerprints |
| 🎭 **olcRTC support** | The [olcRTC](https://github.com/openlibrecommunity/olcrtc) transport (by openlibrecommunity) — traffic rides real video-call services (Jazz, Telemost, WB Stream, Jitsi), so to DPI it's an ordinary call, not a proxy. |
| 📥 **Smart import** | vless/vmess/trojan/ss links, base64 blobs, panel JSON, **full raw Xray / sing-box configs**, AmneziaWG `.conf`/QR, olcRTC URIs, and Happ profiles. |
| 🧭 **DNS & routing** | Routing profiles, import a full Xray config (honored *verbatim*), or flip the built-in **"Block RU domains"** switch. |
| 🧱 **DPI evasion** | TLS fragmentation, multiplexing, AmneziaWG obfuscation, QUIC blocking. |
| 🔒 **Leak-safe** | Captures **both IPv4 and IPv6** — nothing escapes the tunnel. |
| 📱 **Split tunneling** | Pick exactly which apps go through the VPN. |
| 🗂️ **Subscriptions** | Auto-refresh (toggle it **per subscription**), reachable-server counter, traffic/usage display, groups with collapse/pin/sort-by-ping. |

---

## 📦 Download

Grab the latest signed APK from the **[Releases page](https://github.com/yanisplugg/olcvpn-client/releases/latest)**.

| Build | Best for |
|-------|----------|
| 🟢 **`arm64-v8a`** | Modern phones — **pick this if unsure** |
| 🟡 `armeabi-v7a` | Older 32-bit devices |
| 🔵 `x86_64` | Emulators / x86 tablets |
| ⚪ `universal` | One file that runs on anything (largest) |

> 💡 Not sure? Download **arm64-v8a** or **universal**.

Minimum is **Android 6.0** (API 23).

---

## 🧠 How it works

```
┌─────────────┐   packets   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│  Your apps  │ ──────────▶ │  Android TUN  │ ─────────▶ │     Engine (one process)   │
└─────────────┘             │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  + olcRTC stealth    │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                🌍 the open internet
```

All native cores are compiled into **one** `gomobile` library (a single Go runtime), so Xray, sing-box, AmneziaWG, VK-TURN and olcRTC coexist in one process without conflicts. The app just spins up a `VpnService`, feeds packets into the TUN, and wraps them into the chosen engine via a local SOCKS5.

---

## 🧩 Engines in plain words

- **Xray / sing-box** — classic proxy cores. VLESS+Reality, XHTTP, WS+TLS, etc. The core is auto-picked per transport.
- **AmneziaWG** — WireGuard with obfuscation: the handshake and packets don't look like "plain" WireGuard, which is often fingerprinted and dropped.
- **Hysteria2** — a fast QUIC-based protocol with Salamander obfuscation and port-hopping; holds up well on lossy / unstable links.
- **VK-TURN** — spins up a local WireGuard and routes it through the TURN servers of VK calls; several "calls" are bonded for throughput.
- **olcRTC** — disguise as a video call: traffic rides genuine conferencing services, so to DPI it looks like a live call.

---

## 🛠️ Build from source

Everything you need is vendored here (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `free-turn-proxy`, `amneziawg-go`). You'll need:

- **JDK 17** (the one bundled with Android Studio is fine)
- **Android SDK** (set `sdk.dir` in `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) on your `PATH`

> ⚠️ `gomobile` shells out to `javac`, so make sure the JDK's `bin/` is on your `PATH` — not just `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.0.0 -Polcbox.versionCode=2
```

APKs land in `YPtun/androidApp/build/outputs/apk/release/`.
Want a single-ABI build for your phone (faster)? Add `-Polcbox.android.abiFilters=arm64-v8a`.

<details>
<summary>🔑 Signing your own release builds (optional, for maintainers)</summary>

<br>

By default Gradle builds debug-signed APKs. If you want to publish your **own** signed
releases, create a keystore and point Gradle at it with `YPtun/keystore.properties`:

```properties
storeFile=release.keystore
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

This file (and the `.keystore` itself) is **gitignored and never committed** — it only lives
on your machine. Keep your keystore safe: you need the same one to ship updates that install
over previous versions.

</details>

---

## 🧪 Development

YPtun is **Kotlin Multiplatform**: all the logic (import, config building, engines, UI state)
lives in `commonMain`, with platform glue in `androidMain`. That means the same code already
runs on the JVM desktop target.

- **UI** — Jetpack Compose, one design across platforms.
- **Localization** — three languages (🇷🇺 Russian, 🇬🇧 English, 🇮🇷 فارسی) in a single strings file.
- **Native cores** — Go, compiled into one gomobile AAR by the `buildCoresAndroidAar` task; core
  inputs are tracked, so the AAR only rebuilds when Go code changes (Go's cache keeps it fast).
- **Tests** — unit tests for the routing parsers/converters (`./gradlew :sharedUI:jvmTest`).
- **Branches** — stable on `main`, active work on `Beta`; releases are tagged `vX.Y.Z`.

Found a bug or want a feature? Open an issue or PR — see **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## 🗂️ Project layout

```
YPtun/            Kotlin Multiplatform app — Compose UI, Android VpnService, engine wiring
cores/            Go glue: one gomobile AAR bundling sing-box + olcRTC + Xray + AmneziaWG + VK-TURN
olcrtc/           olcRTC — video-call disguise transport          (third-party, vendored)
sing-box/         sing-box / libbox                               (vendored)
awgproxy/         AmneziaWG wrapper → local SOCKS5                 (Go module)
hysteria2proxy/   Hysteria2 (apernet) wrapper → local SOCKS5        (Go module)
free-turn-proxy/  VK-TURN — tunnel over VK calls                  (Go module)
amneziawg-go/     AmneziaWG implementation                        (vendored)
```

---

## 🗺️ Roadmap

- [x] Android release
- [x] AmneziaWG and VK-TURN engines
- [x] Routing profiles (Happ-compatible)
- [ ] 🪟 **Windows** desktop build — *coming soon*
- [ ] 🐧 **Linux** desktop build — *coming soon*

> The shared engine already runs on the JVM (`desktopApp`), so desktop builds are next in line.

---

## 🤝 Contributing

PRs and issues are welcome. Before you start, have a look at:
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — how to build, format and submit changes
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — house rules
- **[SECURITY.md](SECURITY.md)** — how to report a vulnerability

---

## 🙏 Credits

Standing on the shoulders of giants:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## 📄 License

[MIT](LICENSE) for the app. Vendored components keep their own licenses
(`sing-box/LICENSE`, `olcrtc/LICENSE`, `amneziawg-go/LICENSE`).

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="No censorship" width="150">

<br><br>

> *"A nation that is afraid to let its people judge the truth and falsehood in an open market is a nation that is afraid of its people."*
>
> — **John F. Kennedy**

<br>

<sub>Made for a freer internet. ⭐ the repo if it helps you.</sub>

</div>
