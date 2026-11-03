<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

[RU](providers.ru.md) / **EN**

</div>

# Carriers

A carrier is the service a tunnel meets in: a video-conference room that
forwards what its participants publish. This fork carries five of them beyond
the upstream set - Telemost, WB Stream, SaluteJazz, VK Calls and MTS Link -
and this page is about both halves of living with a carrier: pointing an
instance at a room you already have, and teaching the engine a service it
does not know yet.

## The three parts of a carrier

A carrier is not one component. Three of them meet, and the configuration
names each:

- **The auth provider** (`internal/auth/<name>`) turns what a user holds -
  a room link, an account token - into engine credentials
  (`engine.Credentials`: a join URL, a token, whatever the service needs in
  `Extra`). It reports the engine it feeds by name
  (`auth.Provider.Engine()`).
- **The engine** (`internal/engine/<name>`) joins the room and carries
  bytes: a reliable lane, maybe a lossy datagram lane, maybe addressing for
  individual peers. What an engine can do is what its capability interfaces
  say (`engine.PeerSession`, `engine.DatagramSession`, …), and a transport
  discovers them at runtime, not in the config.
- **The transport** (`internal/transport/<name>`) is how the tunnel itself
  rides the engine: `datachannel` over a provider's data channel,
  `vp8channel`/`videochannel` dressed as a published video track, or
  `seichannel`. The transport is a separate choice: whatever the engine
  supports, the yaml asks for by name.

| provider | engine | notes |
|---|---|---|
| `jitsi` | `jitsi` | upstream carrier; instance pools in [`jitsi.instances.yaml`](./jitsi.instances.yaml) |
| `telemost` | `goolom` | the relay currently forwards a guest's track only as a trickle ([#58](https://github.com/ghostlane-project/olcrtc/issues/58)) |
| `wbstream` | `livekit` | needs a WB account token |
| `salutejazz` | `salutejazz` | LiveKit-protocol SFU behind Sber's TURN; relay window per destination ([#49](https://github.com/ghostlane-project/olcrtc/issues/49)) |
| `vkcalls` | `vkcalls` | VK Calls rooms |
| `mtslink` | `mtslink` | MTS Link; the SFU forwards VP9/H264, so the line's transport is `vp8channel` with the engine dressing frames as VP9; a client reaches the SFU over ICE-TCP unless its room link asks otherwise (`?ice=udp`, `?ice=both`, [#60](https://github.com/ghostlane-project/olcrtc/issues/60)); a server joins over UDP |
| `max` | `max` | calls of the MAX messenger (VK): a DIRECT room (created without a camera), the tunnel on the pair's data channel; `auth.token` is a calls session key or the web client's calls token, the room a join link (https://max.ru/joincall/<hash>) |
| `none` | any by name | direct engine access: engine/URL/token from the config, `livekit` by default |

## Setting up an instance

For every carrier the config is one yaml: `provider` for the auth provider,
`transport` for the tunnel, and the provider's own fields - the room link,
an account token, a nickname. Ready-made pairs sit in
[`examples/`](./examples/), one server and one client per carrier per
transport, and [`configuration.md`](./configuration.md) walks the fields.
For Jitsi there is one more step: a pool of instances in
[`jitsi.instances.yaml`](./jitsi.instances.yaml), so a server is a room
picked from a list rather than one room forever.

A word inherited from measuring these services: one server in a room means
one shared lane for every client in it. An SFU hands each participant the
others' streams, and a room full of downloaders eats the lane together -
run heavy users in separate rooms.

## Adding a carrier

The freshest worked example is MTS Link (PR #57), and the shape to copy is
its file map:

- `internal/auth/mtslink/` - the provider. `Issue` parses the join link,
  talks the service's HTTP the way its own web client does, and returns
  `Credentials`; `Engine()` says `mtslink`. Tests run against a fake
  gateway that answers the same paths.
- `internal/engine/mtslink/` - the session. It implements
  `engine.Session` plus the capabilities it has, each asserted in one
  `var _ engine.… = (*Session)(nil)` block, and its doc comment is where
  the service's odd behaviour is written down (which candidates the SFU
  offers twice, what it refuses to forward, how receive slots fill). Read
  those of the existing engines first: they are the findings the engines
  are built around.
- `internal/engine/builtin/builtin.go` - one `register` for the provider,
  one `engine.Register` for the engine.
- `docs/examples/` - a server/client pair for each transport the carrier
  runs with, and a line in `readme.md`.
- `internal/gate/` - cells for the release gate (see [`gate.md`](./gate.md)):
  a room pool for the carrier, the provider in the default walk, thresholds
  for its lanes, and an entry in `known.go` only while an open issue owns
  a cell that fails every run.

Two rules of the road from the carriers already here. First, the service's
behaviour is the spec: probe it before writing the engine, and put what it
actually does - not what its docs say - in the engine's doc comment.
Second, every mechanism that peers negotiate (the SaluteJazz relay window,
the ICE hint of a room link) ships off until the peer shows it speaks it,
so an older app keeps its session.
