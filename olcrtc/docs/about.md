<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

[RU](about.ru.md) / **EN**

</div>



# olcRTC - overview

`olcRTC` (OpenLibreCommunity RTC) is an encrypted TCP-over-WebRTC tunnel. It disguises traffic as ordinary participation in a WebRTC/SFU service: Jitsi Meet, Yandex Telemost or WbStream.

Project: [github.com/openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)  
License: WTFPL  
Status: **Beta**

## Why it is needed

In scenarios where direct access to an arbitrary VPS / IP is blocked, traffic has to be carried through services that are already reachable for the user. To an outside observer the connection looks like an ordinary WebRTC call to an allowed service IP, and the payload inside is additionally encrypted with the shared `crypto.key`.

> **Important:** always check that the video call service you need is on the allow lists. If it is not there, use another one. A list of all allow-listed services will be published soon.

Basic scheme:

```text
app
  -> SOCKS5 127.0.0.1:8808
   -> olcrtc cnc
    -> WebRTC/SFU service
     -> olcrtc srv
       -> internet
```

## How it works

Client mode `cnc` starts a local SOCKS5. A browser, curl, sing-box, olcbox or another app connects to it as to an ordinary proxy.

Server mode `srv` connects to the same room/session, accepts the encrypted smux stream and opens TCP connections to the target addresses on its own behalf.

Inside the tunnel:

```text
SOCKS CONNECT
  -> smux stream
   -> XChaCha20-Poly1305
    -> transport
     -> engine
      -> WebRTC/SFU
```

## Modes

| Mode | Purpose |
|---|---|
| `srv` | server side, accepts tunnel streams and does TCP dial to targets |
| `cnc` | client side, listens on a local SOCKS5 |
| `gen` | creates Room IDs for providers that can create rooms |

The CLI takes a single YAML file:

```bash
olcrtc server.yaml
olcrtc client.yaml
```

## Providers

`auth.provider` selects the service and the way credentials are obtained.

| Provider | Engine | Comment |
|---|---|---|
| `jitsi` | `jitsi` | Jitsi room URL, instances in docs/jitsi.instances.yaml, no separate registration |
| `telemost` | `goolom` | credentials via Yandex Telemost API, separate registration |
| `wbstream` | `livekit` | credentials via WbBStream API, separate registration |
| `none` | set in `engine.name` | direct engine mode with `engine.url` and `engine.token`, separate registration |

The same name is used in Go configs, logs, flags and tests: `Provider` in Go and `auth.provider` in YAML.

## Engines

`engine` is the low-level protocol of a concrete SFU/signaling:

| Engine | Package | Capabilities |
|---|---|---|
| `livekit` | `internal/engine/livekit` | data packets/video tracks/LiveKit SDK |
| `goolom` | `internal/engine/goolom` | Telemost/Goolom signaling, publisher/subscriber PeerConnection |
| `jitsi` | `internal/engine/jitsi` | Jitsi MUC/Jingle/colibri-ws, datachannel/best-effort video |

`internal/engine/builtin` binds `auth.provider` to the proper engine. There is no separate `internal/provider` package in the current project.

## Transports

`net.transport` defines how tunnel bytes are placed into a WebRTC primitive.

| Transport | How it carries data | Main scenario |
|---|---|---|
| `datachannel` | native byte/data path of the engine | simplest and fastest path, stable with Jitsi |
| `vp8channel` | KCP over VP8-like video frames | main video path for WB Stream and Telemost |
| `seichannel` | payload in H264 SEI NAL units, ACK/retry | fallback for WB Stream / Jitsi |
| `videochannel` | QR/tile frames encoded as VP8 in pure Go, ACK/retry | experimental visual transport |

Recommended start: `jitsi + datachannel`. Alternative: `wbstream + vp8channel`.

## Encryption and handshake

`internal/crypto` implements the OLC2 record layer on XChaCha20-Poly1305. The shared PSK is set as 64 hex characters:

```bash
openssl rand -hex 32
```

HKDF-SHA256 derives independent `olcrtc/v2/client-to-server` and `olcrtc/v2/server-to-client` keys from the PSK. Client and server use opposite send and receive keys, so reflected records fail authentication.

An OLC2 record contains the `OLC2` magic, a big-endian 64-bit counter, a 16-byte random sender prefix, ciphertext and a Poly1305 tag. Data and control records use different AEAD associated data: `olcrtc/muxconn/v2/data` and `olcrtc/muxconn/v2/control`.

Authenticated records pass through a 64-record replay window per sender prefix. Replay state is shared by data, control and reconnect connections, limited to 256 sender prefixes, and is not changed by unauthenticated input. Counter wrap is rejected.

OLC2 has no v1 fallback. Builds that use the old record format cannot connect to current builds.

The shared OLVC video frame format used by `seichannel` and `videochannel` is version 5. It carries sender role, session binding, per-fragment ACK data, a per-fragment checksum and a whole-message CRC. A fragment that fails its own checksum is never acknowledged, so it is retransmitted instead of being lost with the message. Older frames are rejected by magic or version checks, so old video transport builds are incompatible.

`smux` runs on top of the encrypted `muxconn`. The first smux stream is occupied by the handshake and the control protocol:

```text
CLIENT_HELLO(challenge) -> SERVER_WELCOME(challenge, authenticated peer ID)
CONTROL_PING <-> CONTROL_PONG
```

If the control pong does not arrive several times in a row, the runtime rebuilds the smux session or hands control to the failover supervisor.

## YAML

Minimal server:

```yaml
mode: srv
auth:
  provider: jitsi
room:
  # Use the Jitsi server that works in your network:
  # Instances: see docs/jitsi.instances.yaml - https://HOST/ROOM
  id: "https://meet.example.org/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
```

Minimal client:

```yaml
mode: cnc
auth:
  provider: jitsi
room:
  # Use the Jitsi server that works in your network:
  # Instances: see docs/jitsi.instances.yaml - https://HOST/ROOM
  id: "https://meet.example.org/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
socks:
  host: "127.0.0.1"
  port: 8808
```

More: [configuration.md](configuration.md), [settings.md](settings.md).

## Failover

`profiles[]` lets you run several configurations in order. For example, first `wbstream + vp8channel`, then `jitsi + datachannel`. Top-level fields act as defaults, a profile overrides only the parts it needs.

Active smux streams do not migrate when the profile changes. New connections can come up on the next profile.

## Repository structure

| Path | What is inside |
|---|---|
| `cmd/olcrtc` | CLI entrypoint |
| `cmd/olcrtc-cgo` | c-shared entrypoint |
| `pkg/olcrtc/client` | complete embeddable SOCKS5 client tunnel |
| `pkg/olcrtc/tunnel` | complete embeddable server tunnel |
| `pkg/olcrtc/engineconn` | raw unencrypted engine byte stream |
| `mobile` | gomobile bindings for Android |
| `internal/config` | YAML parsing, `crypto.key_file` |
| `internal/app/session` | defaults, validation, routing into `srv`/`cnc`/`gen` |
| `internal/auth` | provider-specific credential flows |
| `internal/engine` | SFU/signaling implementations |
| `internal/transport` | datachannel/vp8/sei/video transports |
| `internal/server` | server-side smux, handshake, TCP dial |
| `internal/client` | SOCKS5 listener, client-side smux |
| `internal/control` | liveness ping/pong |
| `internal/supervisor` | failover profiles |
| `docs` | documentation and YAML examples |

## Build

```bash
go install github.com/magefile/mage@latest

mage build
mage cross
mage test
mage lint
mage mobile
```

Go version: `1.26+`. `videochannel` is pure Go; `codec: tile` requires a resolution of `1080x1080`.

## Public API

`pkg/olcrtc/client` runs the complete encrypted client stack and opens a SOCKS5 listener:

Public constructors automatically register all built-in providers, engines and transports. Call `RegisterDefaults` manually only after custom registry manipulation or extension.

```go
cli := client.New(client.Config{
    Transport: "datachannel",
    Provider: "jitsi",
    RoomURL: "https://meet.example.org/myroom",
    KeyHex: "<64-char hex>",
    LocalAddr: "127.0.0.1:8808",
    DNSServer: "8.8.8.8:53",
})
err := cli.Run(ctx)
```

`pkg/olcrtc/tunnel` embeds the server side and exposes hooks:

```go
srv := tunnel.New(tunnel.Config{
    Transport: "datachannel",
    Provider:   "jitsi",
    // Instances: see docs/jitsi.instances.yaml
    RoomURL:   "https://meet.example.org/myroom",
    KeyHex:    "<64-char hex>",
    DNSServer: "8.8.8.8:53",
})
err := srv.Run(ctx)
```

`pkg/olcrtc/engineconn` is the raw engine-level API. It does not apply OLC2 encryption, handshake, smux, SOCKS or liveness. Its `Dial` returns `io.ReadWriteCloser`, not `net.Conn`, because engine sends cannot provide interruptible deadline semantics.

The optional top-level YAML field `data` points to a directory containing `names` and `surnames`. When omitted, the dictionaries embedded in the binary are used.

## Mobile / Android

The `mobile` package provides an instance-based gomobile API. Each `Runtime`
has an independent configuration and lifecycle:

```go
runtime := mobile.New()
_ = runtime.SetProvider("jitsi")
_ = runtime.SetTransport("datachannel")
_ = runtime.SetRoom("https://meet.example.org/myroom")
_ = runtime.SetKey("<64-char hex>")
_ = runtime.SetSocksPort(8808)

_ = runtime.Start()
_ = runtime.WaitReady(10_000)
_ = runtime.Stop(5_000)
```

`SetTransport` accepts `datachannel`, `vp8channel`, `seichannel` and
`videochannel`; unknown values return an error. `SetVP8Options`,
`SetSEIOptions` and `SetVideoOptions` configure their corresponding
transports. Provider, room/channel, key, DNS/resolver, SOCKS credentials,
provider token, device identity, liveness and traffic settings are also
Runtime methods. A running generation keeps its immutable configuration
snapshot, so setter calls affect the next start.

`WaitReady` stays bound to the generation active when it was called. `Stop`
cancels that exact generation and returns `ErrStopTimeout` when bounded
shutdown expires. `Check` and `Ping` are Runtime methods that use an isolated
temporary client; passing SOCKS port `0` selects an ephemeral loopback port.

`Runtime.SetProtector` configures Android VPN `protect(fd)`. This callback is
process-wide Android networking state, not Runtime-local state. It is stored
atomically and each socket operation uses one callback snapshot.
`Runtime.SetDebug` controls process-wide internal logger verbosity and does not
replace or reconfigure the standard library log output.

## Clients

Ready-made clients that speak `olcrtc`:

| Client | Role | Protocols |
|---|---|---|
| [owenewans/owenclave](https://github.com/owenewans/owenclave) ([src.owenewans.org/owenrtc](https://src.owenewans.org/owenrtc)) | **main client**, Android (fork of exclave) | all common protocols (vless, hysteria2, mieru, trojan, vmess, tuic, shadowsocks, socks ...) plus `olcrtc`, the `olcrtc://` URI format and subscriptions |
| [venterum/veil](https://github.com/venterum/veil) | community client, Android (fork of v2rayNG), Material 3 | VMess, VLESS, Shadowsocks, Trojan, SOCKS, WireGuard, Hysteria2 + `olcrtc` |
| [alananisimov/olcbox](https://github.com/alananisimov/olcbox) | community client, multiplatform (Android, iOS, macOS, Windows, Linux) | All providers (Jitsi, Telemost, WB Stream, Jazz), all transports, split tunneling, TUN/proxy modes |

`owenclave` is the reference client for the `olcrtc://` URI and the subscription format. The native `olcrtc` binary in `mode: cnc` is also a full client - it only exposes a SOCKS5 listener without a UI.

## Tests

```bash
go test -count=1 ./...
mage test
mage e2e
```

Real-provider E2E is enabled via variables:

```bash
E2E_PROVIDERS=wbstream E2E_TRANSPORTS=vp8channel mage e2e
```

## Common problems

| Symptom | What to check |
|---|---|
| `key required` or `invalid key` | the same 64-character hex key on both sides |
| SOCKS5 not listening | `mode: cnc`, `socks.host`, `socks.port`, client logs |
| Jitsi does not connect without a second participant | server and client must be in the same room |
| WB Stream + datachannel does not work | guest flow has no `canPublishData`; use `vp8channel`, `seichannel` or `videochannel` |
| `seichannel ack timeout` | the provider throttles/does not route the video path; change transport/provider |

## Links

- [Quick start](fast.md)
- [Manual build](manual.md)
- [YAML configuration](configuration.md)
- [Compatibility matrix](settings.md)
- [URI format](uri.md)
- [Subscription format](sub.md)
