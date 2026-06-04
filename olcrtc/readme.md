

<div align="center">

![Westand](docs/asset/westand.svg)


<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

</div>

## About
olcRTC - across the sea

Project that bypass blocking by parasitizing on whitelisted services in Russia, use legal meet services

## Status

Beta
<br>
See all info in [docs](docs/)
<br>
Issues? contact us at [@openlibrecommunity](https://t.me/openlibrecommunity) or make an [issue](https://github.com/openlibrecommunity/olcrtc/issues)
<br>
Community ui client: [alananisimov/olcbox](https://github.com/alananisimov/olcbox)

## Read docs for start 

[Configuration](docs/configuration.md)

[For noobs](docs/fast.md)

[Manual](docs/manual.md)

[Setting matrix](docs/settings.md)

[More info](docs/about.md)

[Docker setup](docs/docker.md)

[Client URI format](docs/uri.md)

[Client subscription format](docs/sub.md)

# More

Encrypted TCP-over-WebRTC tunnel. Traffic is disguised as a regular video call on whitelisted services (Jitsi, Yandex Telemost, WbStream, More). Inside - XChaCha20-Poly1305 encryption + smux multiplexing over WebRTC data/video channels.

**Supported providers:** `jitsi` - `telemost` - `wbstream`

**Transports:** `datachannel` - `vp8channel` - `seichannel` - `videochannel`

**Platforms:** Linux, macOS, Windows, Android (gomobile), Docker, embeddable Go library

```
app -> SOCKS5 -> olcrtc cnc -> WebRTC/SFU service -> olcrtc srv -> internet
```

<div align="center">

---

Telegram: [zarazaex](https://t.me/zarazaexe)
<br>
Email: [zarazaex@tuta.io](mailto:zarazaex@tuta.io)
<br>
Site: [zarazaex.xyz](https://zarazaex.xyz)

</div>
