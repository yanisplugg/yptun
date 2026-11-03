<!-- If you are an AI agent, please read agents.md -->

<p align="center">
  <img src="docs/asset/olcrtc-icon.svg" alt="olcRTC" width="112" height="112">
</p>

<h1 align="center">olcRTC</h1>

<p align="center">
  <b>An encrypted TCP-over-WebRTC tunnel.</b><br>
  The transport engine of <a href="https://github.com/ghostlane-project/ghostlane">Ghostlane</a>, as a Go library and a command-line program.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ghostlane-project/olcrtc?color=b5f23d&labelColor=07080d" alt="License: Apache-2.0"></a>
  <a href="https://github.com/ghostlane-project/olcrtc/releases"><img src="https://img.shields.io/github/v/release/ghostlane-project/olcrtc?color=b5f23d&labelColor=07080d&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/github/go-mod/go-version/ghostlane-project/olcrtc/proofkit?label=go&color=b5f23d&labelColor=07080d" alt="Go version">
  <a href="https://github.com/ghostlane-project/olcrtc/actions/workflows/ci.yml"><img src="https://github.com/ghostlane-project/olcrtc/actions/workflows/ci.yml/badge.svg?branch=proofkit" alt="CI"></a>
  <a href="https://opencollective.com/ghostlane"><img src="https://img.shields.io/badge/funding-Open_Collective-b5f23d?labelColor=07080d&logo=opencollective&logoColor=white" alt="Funding: Open Collective"></a>
</p>

<p align="center"><a href="readme.ru.md">RU</a> / <b>EN</b></p>


`olcRTC` (OpenLibreCommunity RTC) is an encrypted TCP-over-WebRTC tunnel. Traffic is disguised as an ordinary video call on allowed services (Jitsi, Yandex Telemost, WbStream, Sber SaluteJazz, VK Calls, MTS Link). Inside there is XChaCha20-Poly1305 encryption and smux multiplexing over WebRTC data/video channels.

Status: **Beta**

```text
app -> SOCKS5 -> olcrtc cnc -> WebRTC/SFU service -> olcrtc srv -> internet
```

> **Important:** make sure the video call service you need is on the allow lists and works in your network. If not, use another one.

## The project

olcRTC is maintained as part of **[Ghostlane](https://github.com/ghostlane-project/ghostlane)**,
an open-source VPN client for Android, iOS, macOS, Windows and Linux. This
repository is the transport engine; the application repository is where the
project's governance, funding policy and community documents live:

| | |
| --- | --- |
| What the application is | [ghostlane/README.MD](https://github.com/ghostlane-project/ghostlane/blob/main/README.MD) |
| How the project is run | [GOVERNANCE.md](GOVERNANCE.md) |
| How to contribute | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Reporting a vulnerability | [SECURITY.md](SECURITY.md) |
| Funding, and who holds the money | [ghostlane/FUNDING.md](https://github.com/ghostlane-project/ghostlane/blob/main/FUNDING.md) |
| Licence and upstream attribution | [LICENSE](LICENSE), [NOTICE](NOTICE) |

The engine is under active maintenance: changes are exercised by a release gate
that moves real traffic through real relays before anything ships, and the
report of that run is published with every application release.

The engine is funded together with the application, through
[Open Collective](https://opencollective.com/ghostlane): Open Source Collective holds the
money as fiscal host, and every contribution and every expense is public there.

## This fork

This is a fork of [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc),
which is now archived. The work is on the **`proofkit`** branch, the default
here; that name is left over from the project's former one and is kept because
builds and pins point at it, not because the project is still called that.
`master` is an untouched copy of upstream and carries none of it. This branch is
the engine [Ghostlane](https://github.com/ghostlane-project/ghostlane) links on
every platform, which is what most of the additions below are for: a relay has
to tell its users apart, and a phone is not a server.

| Added here | What it is |
| --- | --- |
| A ring of server keys | `crypto.keys` / `crypto.keys_file`. A server holds several keys and pins, per peer, the one that peer's first record authenticates under — so each client can carry its own key. A single `crypto.key` is a one-entry ring and behaves exactly as before; clients are unchanged. |
| Per-key metering | `stats.listen` serves `GET /stats` on loopback with per-key byte totals. Enough to bill a key or cut one off, with no control plane to run. The body's `link` field says what the carrier session is doing - `connecting`, `up` or `down` - because the listener is bound before the room is joined, so an answer on its own only proves the process is running. |
| A lossy datagram lane | Datagrams travel beside the byte stream on `vp8channel`, `datachannel` and livekit, so a UDP flow no longer has to pretend to be a stream: vp8channel tags them `OLUD`/`OLUB` and sends them after control frames and before KCP data, livekit publishes them unreliably on its own topic. |
| SOCKS5 UDP ASSOCIATE | `udp.enabled` puts calls, games and everything else that is datagrams through the relay over that lane, with `udp.max_flows` per side. Off unless asked for. |
| DNS off the lossy lane | Behind a tun2socks every packet a phone sends arrives as a UDP association, the resolver's queries included, and a query lost under load is a stall the page feels. A datagram for port 53 now goes over a smux stream as TCP DNS (RFC 7766), 64 in flight, five seconds each, the lane as fallback. |
| A resolver ring on mobile | Names resolve through the host's protected sockets and a per-session list of resolvers, demoting one that stays silent rather than waiting on it. Inside a tunnel the system resolver is the tunnel, and a carrier that blackholes a public one is not rare. |
| A memory ceiling the host sets | `mobile.SetMemoryLimit`, `MemoryLimit`, `MemoryStats`, `FreeOSMemory`, `GoroutineSummary` and a log writer. An iOS packet-tunnel extension gets about 50 MB for everything it runs; a Go runtime that decides its own ceiling from the device's RAM will step over that in one collection. |
| A lean mobile bind | `-tags olcrtc_lean` leaves the videochannel transport out of the gomobile build — QR charset tables built at init for a transport no phone selects. The livekit engine stays in every build: WB Stream's auth provider names it, and a lean bind without it failed the session right after the guest token. The default build and the CLI are unchanged. |
| Phone-sized buffers | KCP windows, packet queues, NACK history, track reads and per-association read buffers sized for a phone instead of a server, selected by a host profile rather than hardcoded. |
| Failover that follows a moving server | The supervisor re-reads its profile list at every advance (`Config.Reload`; the CLI re-reads the config file) and looks for the profile that just ran, so a client follows a rolling window of rooms: a room added while a session is live is used the moment that session ends, no restart, and a room dropped from the list carries the walk to the new head rather than ending the pass. A room nobody is in - the handshake fails and nothing there sent a frame while it ran - ends the run for a client that has other rooms to try (`EndOnEmptyRoom`); a peer that is there but silent, and a client whose only room this is, keep retrying. A control stream the peer closed on purpose ends that session at once instead of waiting out a liveness window, and leaves the room alone: the same notice arrives from a server whose own provider rebuilt underneath it. IPv6 literals are refused locally once the exit has refused several in a row, and the judgement lapses. |
| A failover room list on mobile | `AddFailoverRoom` / `ClearFailoverRooms` beside `SetRoom`: the runtime walks the primary and the extras under the supervisor, one pass, re-reading the list at every hop, so the host can append rooms to a live generation; the pass ends with an error and the host's own retry loop takes over. A room that turns out to be empty is given up on only while another is on offer, counted as that room's run starts, so a lone room is retried in place exactly as before failover and a room the host appends is in force from the next start. `SetSessionListener` names the room of each session as it opens, which is how a host that cannot read the log tells a handover from a reconnect. |
| salutejazz engine and auth provider | The Sber SaluteJazz JSON connector (LiveKit-as-JSON over pion). |
| vkcalls engine and auth provider | VK Calls, joined as a guest with the call's link, over `vp8channel`. |
| mtslink engine and auth provider | MTS Link, joined as a guest with an endless meeting's link. Its SFU forwards VP9 and no VP8, so `vp8channel` rides it dressed as VP9. |
| A DTLS ClientHello profile | `dtls.profile`, and `SetDTLSProfile` on mobile. `off`, the default, keeps the WebRTC library's own handshake; `chrome-linux-138-compat-v1` opens it the way Chrome 138 does. Experimental: Ghostlane offers it as a switch, off by default. |
| A relay window for SaluteJazz | LiveKit 1.5.3, which SaluteJazz runs, acknowledges at once and queues toward each subscriber without a limit, so on a slow Sber leg the tunnel's control frames waited behind a stream window of data and liveness closed sessions that were alive. Each destination now gets a window sized by the sender to the best rate its leg showed in the last ten seconds times three seconds, between 16 and 192 KiB. It is armed only when both ends mark it in the handshake, so an older peer is served as before. |
| Direct rules on the client | `route.direct` / `route.direct_file`, and `SetDirectRules` on mobile: the destinations a client dials itself instead of through the tunnel, over the same protected socket the provider gets. Rules name hosts (`domain:`, `full:`) or addresses and prefixes, in the syntax of Xray's lists. For an address the rules do not list, the client reads the first bytes for a TLS server name or an HTTP `Host`, so a tun2socks that hands over only addresses still routes by name. With `udp.enabled`, datagrams to a listed target go direct too, and a DNS query for a listed name goes to the network's own resolvers instead of the exit. This is how Ghostlane's regional bypass modes route an olcRTC room. See [configuration.md](docs/configuration.md). |

Beside those: a client that tells an old peer, a wrong key and an empty room
apart instead of timing out on all three; per-lane record numbering and replay
checks; back-pressure on the Jitsi bridge send queue instead of a failed send;
dual-stack dialing with a retry for a dial that found no route, which is what
an IPv6-only carrier and App Review's NAT64 both need.

Changes that belong upstream are prepared as pull requests against it — the
`pr/*` branches here are those, one change each.

## Features

- **Providers:** `jitsi`, `telemost`, `wbstream`, `salutejazz`, `vkcalls`, `mtslink`
- **Transports:** `datachannel`, `vp8channel`, `seichannel`, `videochannel`
- **Platforms:** Linux, macOS, Windows, Android (gomobile), embeddable Go library
- **Public Go packages:** `pkg/olcrtc/client`, `pkg/olcrtc/tunnel`, `pkg/olcrtc/engineconn`

Recommended start: `jitsi + datachannel`.

Current builds use OLC2 encryption with directional HKDF-SHA256 keys, separate data/control AAD and replay protection. There is no compatibility fallback for the old crypto format. `seichannel` and `videochannel` use OLVC frame version 5 and reject older video frames. Upgrade both endpoints together.

Display-name dictionaries are embedded. Set optional YAML field `data` to a directory containing `names` and `surnames` to override them.

## One-click install

```sh
curl -fsSL https://raw.githubusercontent.com/ghostlane-project/olcrtc/proofkit/install.sh | bash
```

Installs Podman if missing, clones this fork's `proofkit` branch, builds the binary in a container, asks a few questions (server or client, provider, transport, room, key) and starts it. Run it once on the server (mode `srv`) and once on the client (mode `cnc`) - they need the same room ID and encryption key.

If you already have the repo cloned, run `./install.sh` directly instead.

Full instructions are in [docs/fast.md](docs/fast.md) and [docs/manual.md](docs/manual.md).

## Documentation

- [about.md](docs/about.md) - architecture, providers, transports, public API
- [fast.md](docs/fast.md) - quick start for newcomers
- [manual.md](docs/manual.md) - manual build
- [configuration.md](docs/configuration.md) - YAML setup
- [providers.md](docs/providers.md) - carriers: setting up an instance, adding a new one
- [settings.md](docs/settings.md) - compatibility matrix
- [uri.md](docs/uri.md) - client URI format
- [sub.md](docs/sub.md) - subscription format
- [gate.md](docs/gate.md) - release gate: the tunnel under load on real relays, and its report
- [versioning.md](docs/versioning.md) - versions: tags, releases, and which version runs where

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
- This fork's client:
  - [ghostlane-project/ghostlane](https://github.com/ghostlane-project/ghostlane) - Ghostlane, a fork of olcbox below. Kotlin Multiplatform/Compose for Android, iOS, macOS, Windows and Linux; olcRTC beside VLESS (Reality or TLS), Hysteria2, XHTTP, Trojan, Shadowsocks and VMess, and the client the additions above are built for

    <a href="https://apps.apple.com/app/id6795355210"><img src="docs/badges/app-store-badge.svg" alt="Download on the App Store" width="168" align="middle"></a>
    &nbsp;
    <a href="https://play.google.com/store/apps/details?id=org.proofkit.app"><img src="docs/badges/google-play-badge.png" alt="Get it on Google Play" width="217" align="middle"></a>

    Desktop builds and Android APKs: [GitHub Releases](https://github.com/ghostlane-project/ghostlane/releases).

- Community clients:
  - [venterum/veil](https://github.com/venterum/veil) - V2Ray/Xray client for Android (fork of v2rayNG), Material 3. Protocols: VMess, VLESS, Shadowsocks, Trojan, SOCKS, WireGuard, Hysteria2 + `olcrtc`
  - [alananisimov/olcbox](https://github.com/alananisimov/olcbox) - Multiplatform UI client (Android, iOS, macOS, Windows, Linux). Kotlin Multiplatform/Compose. All providers (Jitsi, Telemost, WB Stream, Jazz), all transports, split tunneling, TUN/proxy modes

## Community

- Issues and questions: [github.com/ghostlane-project/olcrtc/issues](https://github.com/ghostlane-project/olcrtc/issues)
- The application built on this engine: [github.com/ghostlane-project/ghostlane](https://github.com/ghostlane-project/ghostlane)
- How to contribute: [CONTRIBUTING.md](CONTRIBUTING.md) · Reporting a vulnerability: [SECURITY.md](SECURITY.md)

Upstream's own channels belong to the archived project and are not where this
fork is maintained.

## License

Apache License 2.0; see [LICENSE](LICENSE).

This is a fork of [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc),
which was published under the WTFPL. That licence permits redistribution under
other terms, and this fork uses that permission so the project carries a licence
downstream users, packagers and auditors can rely on, with an explicit patent
grant. The original licence text and copyright are preserved in
[docs/upstream-license-wtfpl.txt](docs/upstream-license-wtfpl.txt), and [NOTICE](NOTICE) records the
provenance and the third-party licences.

App Store is a trademark of Apple Inc., registered in the U.S. and other
countries. Google Play and the Google Play logo are trademarks of Google LLC.
