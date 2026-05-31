<h1 align="center">YPtun</h1>

<p align="center">
  <b>Fast, censorship-resistant VPN client for Android.</b><br>
  VLESS / Reality / XHTTP over <a href="https://github.com/XTLS/Xray-core">Xray</a> &amp;
  <a href="https://github.com/SagerNet/sing-box">sing-box</a>, with an optional WebRTC stealth transport.
</p>

---

## Download

Grab the latest signed APK from the [**Releases**](../../releases) page.

| Build | Who it's for |
|-------|--------------|
| `YPtun-vX.Y.Z-arm64-v8a.apk`   | Modern phones (recommended) |
| `YPtun-vX.Y.Z-armeabi-v7a.apk` | Older 32-bit devices |
| `YPtun-vX.Y.Z-x86_64.apk`      | Emulators / x86 tablets |
| `YPtun-vX.Y.Z-universal.apk`   | One file that runs anywhere (largest) |

If you're not sure, take **arm64-v8a** (or **universal**).

## Features

- **Protocols:** VLESS, VMess, Trojan, Shadowsocks — TCP / WS / gRPC / HTTPUpgrade / **XHTTP**, with TLS, **Reality** and uTLS fingerprints.
- **Two cores:** runs on **Xray** or **sing-box**, picked automatically per transport.
- **Stealth transport:** tunnel proxy traffic inside a WebRTC data channel ([olcRTC](https://github.com/openlibrecommunity/olcrtc)) to look like a video call.
- **Subscriptions:** vless/vmess/trojan/ss links, base64 blobs, panel JSON, full raw Xray / sing-box configs, and olcRTC URIs.
- **Custom DNS & routing:** import a full Xray config (honored verbatim) or flip the built-in **"Block RU domains"** switch.
- **DPI evasion:** TLS fragmentation and multiplexing.
- **Leak-safe:** captures both IPv4 and IPv6 so nothing escapes the tunnel.
- **Per-app split tunneling.**

## Build from source

Everything needed is vendored in this repo (`cores`, `olcrtc`, `sing-box`). You need:

- JDK 17 (the one bundled with Android Studio works)
- Android SDK (set `sdk.dir` in `YPtun/local.properties`) + NDK `28.2.13676358`
- Go + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) on your `PATH` (the cores are built via `gomobile bind`)

> ⚠️ `gomobile` shells out to `javac`, so make sure the JDK's `bin` is on your `PATH`, not just `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=1.0.0 -Polcbox.versionCode=1
```

APKs land in `YPtun/androidApp/build/outputs/apk/release/`.

To produce signed release builds, create `YPtun/keystore.properties`:

```properties
storeFile=release.keystore
storePassword=********
keyAlias=********
keyPassword=********
```

## Project layout

```
YPtun/      Kotlin Multiplatform app (Compose UI, Android VpnService, engines)
cores/      Go glue: one gomobile AAR bundling sing-box (libbox) + olcRTC + an Xray bridge
olcrtc/     WebRTC stealth transport (vendored)
sing-box/   sing-box / libbox (vendored, pinned v1.12.25)
```

## Credits

Built on the shoulders of [Xray-core](https://github.com/XTLS/Xray-core),
[sing-box](https://github.com/SagerNet/sing-box) and
[olcRTC](https://github.com/openlibrecommunity/olcrtc). Thanks to their authors.

## License

See [LICENSE](LICENSE). Vendored components retain their own licenses
(`sing-box/LICENSE`, `olcrtc/LICENSE`).
