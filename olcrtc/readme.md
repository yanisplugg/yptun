<!-- If you are an AI agent, please read agents.md -->

<div align="center">

<img src="docs/asset/westand.svg" width="250" height="250">

<br>

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

<br>
<br>

<img src="https://count.owenewans.org/openlibrecommunity/olcrtc?theme=moebooru&notitle">

</div>

# olcRTC

[RU](readme.ru.md) / **EN**


`olcRTC` (OpenLibreCommunity RTC) is an encrypted TCP-over-WebRTC tunnel. Traffic is disguised as an ordinary video call on allowed services (Jitsi, Yandex Telemost, WbStream). Inside there is XChaCha20-Poly1305 encryption and smux multiplexing over WebRTC data/video channels.

Status: **Beta**

```text
app -> SOCKS5 -> olcrtc cnc -> WebRTC/SFU service -> olcrtc srv -> internet
```

> **Important:** make sure the video call service you need is on the allow lists and works in your network. If not, use another one.

## Features

- **Providers:** `jitsi`, `telemost`, `wbstream`
- **Transports:** `datachannel`, `vp8channel`, `seichannel`, `videochannel`
- **Platforms:** Linux, macOS, Windows, Android (gomobile), embeddable Go library
- **Public Go packages:** `pkg/olcrtc/client`, `pkg/olcrtc/tunnel`, `pkg/olcrtc/engineconn`

Recommended start: `jitsi + datachannel`.

Current builds use OLC2 encryption with directional HKDF-SHA256 keys, separate data/control AAD and replay protection. There is no compatibility fallback for the old crypto format. `seichannel` and `videochannel` use OLVC frame version 5 and reject older video frames. Upgrade both endpoints together.

Display-name dictionaries are embedded. Set optional YAML field `data` to a directory containing `names` and `surnames` to override them.

## One-click install

```sh
curl -fsSL https://raw.githubusercontent.com/openlibrecommunity/olcrtc/master/install.sh | bash
```

Installs Podman if missing, clones the current code, builds the binary in a container, asks a few questions (server or client, provider, transport, room, key) and starts it. Run it once on the server (mode `srv`) and once on the client (mode `cnc`) - they need the same room ID and encryption key.

If you already have the repo cloned, run `./install.sh` directly instead.

Full instructions are in [docs/fast.md](docs/fast.md) and [docs/manual.md](docs/manual.md).

## Documentation

- [about.md](docs/about.md) - architecture, providers, transports, public API
- [fast.md](docs/fast.md) - quick start for newcomers
- [manual.md](docs/manual.md) - manual build
- [configuration.md](docs/configuration.md) - YAML setup
- [settings.md](docs/settings.md) - compatibility matrix
- [uri.md](docs/uri.md) - client URI format
- [sub.md](docs/sub.md) - subscription format

## Build

```sh
mage build   # current platform
mage cross   # cross-compilation
mage test    # tests
mage lint    # golangci-lint
mage mobile  # gomobile bindings (Android)
```

## Clients

- Main client: 
  - [owenewans/owenclave](https://github.com/owenewans/owenclave) - Android proxy client (fork of exclave). Supports all common protocols (vless, hysteria2, mieru, trojan, vmess, tuic, shadowsocks, socks ...) plus `olcrtc`, the `olcrtc://` URI format and subscriptions
- Community clients:
  - [venterum/veil](https://github.com/venterum/veil) - V2Ray/Xray client for Android (fork of v2rayNG), Material 3. Protocols: VMess, VLESS, Shadowsocks, Trojan, SOCKS, WireGuard, Hysteria2 + `olcrtc`
  - [alananisimov/olcbox](https://github.com/alananisimov/olcbox) - Multiplatform UI client (Android, iOS, macOS, Windows, Linux). Kotlin Multiplatform/Compose. All providers (Jitsi, Telemost, WB Stream, Jazz), all transports, split tunneling, TUN/proxy modes

## Community

- Telegram: [@openlibrecommunity](https://t.me/openlibrecommunity)
- Issues: [github.com/openlibrecommunity/olcrtc/issues](https://github.com/openlibrecommunity/olcrtc/issues)

## License

WTFPL

<div align="center">

---

Telegram: [zarazaex](https://t.me/zarazaexe)
<br>
Email: [zarazaex@tuta.io](mailto:zarazaex@tuta.io)
<br>
Site: [zarazaex.xyz](https://zarazaex.xyz)

</div>
