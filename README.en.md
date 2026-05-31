<div align="center">

# 🛡️ YPtun

### Fast, censorship-resistant VPN for Android

*VLESS · Reality · XHTTP over **Xray** & **sing-box** — with a WebRTC stealth transport that makes your traffic look like a video call.*

<br>

[![Latest release](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Stars](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Cores](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)

<br>

**🌍 English** · [**Русский**](README.md)

</div>

---

## ✨ Why YPtun?

Most VPN clients give you one engine and one way to connect. **YPtun gives you a toolbox.**
It bundles **two proxy cores** and a **stealth transport** in one app, so when one method gets blocked, you switch and keep going.

> Built for places where the internet fights back. 🌐

> 🖥️ **Desktop coming soon** — native **Windows** and **Linux** builds are in the works.

---

## 🚀 Features

| | |
|---|---|
| 🔀 **Dual cores** | Runs on **Xray** *or* **sing-box** — auto-picked per protocol, or force one. |
| 🧬 **Protocols** | VLESS · VMess · Trojan · Shadowsocks |
| 🚇 **Transports** | TCP · WS · gRPC · HTTPUpgrade · **XHTTP** · TLS · **Reality** · uTLS fingerprints |
| 🎭 **Stealth mode** | Tunnel inside a **WebRTC** data channel ([olcRTC](https://github.com/openlibrecommunity/olcrtc)) — looks like a video call to DPI. |
| 📥 **Smart import** | vless/vmess/trojan/ss links, base64 blobs, panel JSON, **full raw Xray / sing-box configs**, and olcRTC URIs. |
| 🧭 **DNS & routing** | Import a full Xray config (honored *verbatim*), or flip the built-in **"Block RU domains"** switch. |
| 🧱 **DPI evasion** | TLS fragmentation + connection multiplexing. |
| 🔒 **Leak-safe** | Captures **both IPv4 and IPv6** — nothing escapes the tunnel. |
| 📱 **Split tunneling** | Pick exactly which apps go through the VPN. |
| 🗂️ **Subscriptions** | Auto-refresh, traffic/usage display, multi-location lists. |

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

---

## 🧠 How it works

```
┌─────────────┐   packets   ┌───────────────┐   SOCKS5   ┌────────────────────────┐
│  Your apps  │ ──────────▶ │  Android TUN  │ ─────────▶ │  Engine (one process)  │
└─────────────┘             │  (IPv4+IPv6)  │            │  ┌──────────────────┐  │
                            └───────────────┘            │  │ Xray / sing-box  │  │
                                                         │  │  + olcRTC stealth│  │
                                                         │  └──────────────────┘  │
                                                         └───────────┬────────────┘
                                                                     ▼
                                                              🌍 the open internet
```

All native cores are compiled into **one** `gomobile` library (a single Go runtime), so Xray, sing-box and olcRTC coexist without conflicts.

---

## 🛠️ Build from source

Everything you need is vendored here (`cores`, `olcrtc`, `sing-box`). You'll need:

- **JDK 17** (the one bundled with Android Studio is fine)
- **Android SDK** (set `sdk.dir` in `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) on your `PATH`

> ⚠️ `gomobile` shells out to `javac`, so make sure the JDK's `bin/` is on your `PATH` — not just `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=1.0.0 -Polcbox.versionCode=1
```

APKs land in `YPtun/androidApp/build/outputs/apk/release/`.

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

## 🗂️ Project layout

```
YPtun/      Kotlin Multiplatform app — Compose UI, Android VpnService, engine wiring
cores/      Go glue: one gomobile AAR bundling sing-box (libbox) + olcRTC + Xray bridge
olcrtc/     WebRTC stealth transport          (vendored)
sing-box/   sing-box / libbox, pinned v1.12.25 (vendored)
```

---

## 🗺️ Roadmap

- [x] Android release
- [ ] 🪟 **Windows** desktop build — *coming soon*
- [ ] 🐧 **Linux** desktop build — *coming soon*

> The shared engine already runs on the JVM (`desktopApp`), so desktop builds are next in line.

---

## 🙏 Credits

Standing on the shoulders of giants:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc).

## 📄 License

[MIT](LICENSE) for the app. Vendored components keep their own licenses
(`sing-box/LICENSE`, `olcrtc/LICENSE`).

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="No RKN" width="150">

<br><br>

> *"A nation that is afraid to let its people judge the truth and falsehood in an open market is a nation that is afraid of its people."*
> *«Нация, которая боится позволить своему народу судить о правде и лжи на открытом рынке, — это нация, которая боится своего народа.»*
>
> — **John F. Kennedy**

<br>

<sub>Made for a freer internet. ⭐ the repo if it helps you.</sub>

</div>
