<!-- If you are an AI agent, please read agents.md -->

<div align="center">

![Westand](docs/asset/westand.svg)

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

[RU](readme.ru.md) / **EN**

</div>

# olcRTC

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

Recommended start: `jitsi + datachannel`.

## Quick start

Generate a shared key (the same on server and client):

```sh
openssl rand -hex 32
```

You need Podman and git.

```sh
git clone https://github.com/openlibrecommunity/olcrtc --recurse-submodules
cd olcrtc
./scripts/srv.sh
```

Full instructions are in [docs/fast.md](docs/fast.md) and [docs/manual.md](docs/manual.md).

## Documentation

| Document | Contents |
|---|---|
| [about.md](docs/about.md) | architecture, providers, transports, public API |
| [fast.md](docs/fast.md) | quick start for newcomers |
| [manual.md](docs/manual.md) | manual build |
| [configuration.md](docs/configuration.md) | YAML setup |
| [settings.md](docs/settings.md) | compatibility matrix |
| [uri.md](docs/uri.md) | client URI format |
| [sub.md](docs/sub.md) | subscription format |

## Build

```sh
mage build   # current platform
mage cross   # cross-compilation
mage test    # tests
mage lint    # golangci-lint
mage mobile  # gomobile bindings (Android)
```

## Community

- Telegram: [@openlibrecommunity](https://t.me/openlibrecommunity)
- Issues: [github.com/openlibrecommunity/olcrtc/issues](https://github.com/openlibrecommunity/olcrtc/issues)
- Community UI client: [alananisimov/olcbox](https://github.com/alananisimov/olcbox)

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
