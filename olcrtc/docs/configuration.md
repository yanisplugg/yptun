<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

[RU](configuration.ru.md) / **EN**

</div>


# YAML configuration

`olcrtc` reads runtime settings from a single YAML file. The CLI takes exactly one argument - the path to the config; there are no separate CLI flags for mode, transport and provider anymore.

```bash
olcrtc /etc/olcrtc/server.yaml
olcrtc /etc/olcrtc/client.yaml
```

Ready-made examples:

- [`server.jitsi.datachannel.yaml`](./examples/server/server.jitsi.datachannel.yaml) - jitsi + datachannel srv
- [`client.jitsi.datachannel.yaml`](./examples/client/client.jitsi.datachannel.yaml) - jitsi + datachannel cnc
- [`server.jitsi.videochannel.yaml`](./examples/server/server.jitsi.videochannel.yaml) - jitsi + videochannel srv
- [`client.jitsi.videochannel.yaml`](./examples/client/client.jitsi.videochannel.yaml) - jitsi + videochannel cnc
- [`server.jitsi.seichannel.yaml`](./examples/server/server.jitsi.seichannel.yaml) - jitsi + seichannel srv
- [`client.jitsi.seichannel.yaml`](./examples/client/client.jitsi.seichannel.yaml) - jitsi + seichannel cnc
- [`server.jitsi.vp8channel.yaml`](./examples/server/server.jitsi.vp8channel.yaml) - jitsi + vp8channel srv
- [`client.jitsi.vp8channel.yaml`](./examples/client/client.jitsi.vp8channel.yaml) - jitsi + vp8channel cnc
- [`server.telemost.datachannel.yaml`](./examples/server/server.telemost.datachannel.yaml) - telemost + datachannel srv
- [`client.telemost.datachannel.yaml`](./examples/client/client.telemost.datachannel.yaml) - telemost + datachannel cnc
- [`server.telemost.videochannel.yaml`](./examples/server/server.telemost.videochannel.yaml) - telemost + videochannel srv
- [`client.telemost.videochannel.yaml`](./examples/client/client.telemost.videochannel.yaml) - telemost + videochannel cnc
- [`server.telemost.seichannel.yaml`](./examples/server/server.telemost.seichannel.yaml) - telemost + seichannel srv
- [`client.telemost.seichannel.yaml`](./examples/client/client.telemost.seichannel.yaml) - telemost + seichannel
- [`server.telemost.vp8channel.yaml`](./examples/server/server.telemost.vp8channel.yaml) - telemost + vp8channel srv
- [`client.telemost.vp8channel.yaml`](./examples/client/client.telemost.vp8channel.yaml) - telemost + vp8channel cnc
- [`server.wbstream.datachannel.yaml`](./examples/server/server.wbstream.datachannel.yaml) - wbstream + datachannel srv
- [`client.wbstream.datachannel.yaml`](./examples/client/client.wbstream.datachannel.yaml) - wbstream + datachannel cnc
- [`server.wbstream.videochannel.yaml`](./examples/server/server.wbstream.videochannel.yaml) - wbstream + videochannel srv
- [`client.wbstream.videochannel.yaml`](./examples/client/client.wbstream.videochannel.yaml) - wbstream + videochannel cnc
- [`server.wbstream.seichannel.yaml`](./examples/server/server.wbstream.seichannel.yaml) - wbstream + seichannel srv
- [`client.wbstream.seichannel.yaml`](./examples/client/client.wbstream.seichannel.yaml) - wbstream + seichannel cnc
- [`server.wbstream.vp8channel.yaml`](./examples/server/server.wbstream.vp8channel.yaml) - wbstream + vp8channel srv
- [`client.wbstream.vp8channel.yaml`](./examples/client/client.wbstream.vp8channel.yaml) - wbstream + vp8channel cnc
- [`failover.yaml`](./examples/failover.yaml) - failover

## Schema

| YAML path | Meaning |
|---|---|
| `mode` | `srv`, `cnc` or `gen` |
| `auth.provider` | `jitsi`, `telemost`, `wbstream`, `salutejazz`, `vkcalls`, `mtslink`, `none` |
| `auth.token` | optional pre-issued provider account token |
| `room.id` | room ID/URL for the chosen provider |
| `room.channel` | optional channel ID for peer-routing scenarios |
| `crypto.key` / `crypto.key_file` | shared key: 64 hex chars, directly or from a file |
| `crypto.keys` / `crypto.keys_file` | server only: a list of keys, each 64 hex chars, inline or one per line in a file; the key a client's first record authenticates under is pinned for that client |
| `net.transport` | `datachannel`, `vp8channel`, `seichannel`, `videochannel` |
| `net.dns` | DNS resolver in `host:port` form |
| `dtls.profile` | fixed DTLS ClientHello profile of the engine handshake: empty or `off` keeps the stock handshake; the one shipped id is `chrome-linux-138-compat-v1` (Chrome 138's cipher list minus what Pion cannot speak); an unknown name is rejected before dialing; a failover profile may override it, and an explicit `off` overrides an inherited profile |
| `socks.host` / `socks.port` | local SOCKS5 listener in `mode: cnc` |
| `socks.user` / `socks.pass` | optional auth for incoming SOCKS5 connections |
| `socks.proxy_addr` / `socks.proxy_port` | outbound SOCKS5 proxy on the server side |
| `socks.proxy_user` / `socks.proxy_pass` | optional auth for the upstream proxy (RFC 1929) |
| `engine.name` / `engine.url` / `engine.token` | direct engine mode, only when `auth.provider: none` |
| `video.*` | `videochannel` settings |
| `vp8.*` | `vp8channel` settings |
| `sei.*` | `seichannel` settings |
| `liveness.interval` | ping interval over the control stream, default `10s` |
| `liveness.timeout` | pong timeout, default `15s` |
| `liveness.failures` | how many pongs may be missed before rebuild, default `4` |
| `lifecycle.max_session_duration` | planned session rebuild, e.g. `6h`; empty = disabled |
| `traffic.max_payload_size` | limit of the encrypted wire-message; `0` = transport limit |
| `traffic.min_delay` / `traffic.max_delay` | optional send pacing, e.g. `5ms` / `30ms` |
| `udp.enabled` | opt in to the lossy SOCKS5 UDP ASSOCIATE relay (calls, games); off unless `true` |
| `udp.disabled` | `true` wins over `enabled` |
| `udp.max_flows` | concurrent UDP flows per side, default 1024 |
| `route.direct` | client only: rules for destinations dialed directly instead of through the tunnel - `domain:<name>`, `full:<name>`, an address or a CIDR prefix, one per entry; empty = everything through the tunnel |
| `route.direct_file` | the same rules from a file, one per line, `#` comments allowed; read relative to the YAML file and appended to `route.direct` |
| `gen.amount` | `gen` mode: how many rooms to create |
| `profiles[]` | list of failover profiles for `srv`/`cnc` |
| `failover.retry_delay` | pause before the next profile, e.g. `2s` |
| `failover.max_cycles` | how many full passes over the profiles to do; `0` = infinite |
| `data` | optional: directory holding `names`/`surnames` files that override the built-in display-name dictionaries. Resolved relative to the YAML file |
| `debug` | verbose logging |
| `stats.listen` | loopback address serving `GET /stats` with per-key byte totals, e.g. `127.0.0.1:9464`; empty disables |

`crypto.key_file` is read relative to the YAML file. You cannot set `crypto.key` and `crypto.key_file` at the same time.

`crypto.keys` and `crypto.keys_file` (read relative to the YAML file, `#` comments and blank lines skipped) give a server a ring of keys; they cannot be combined with `crypto.key`, `crypto.key_file` or each other, and a client (`mode: cnc`) always uses a single `crypto.key`:

```yaml
crypto:
  keys:
    - "0011...eeff"   # the room key
    - "aabb...8899"   # a per-user key
```

`mode: cnc` forbids listening on a non-loopback address (`0.0.0.0`, LAN IP etc.) unless both `socks.user` and `socks.pass` are set.

The `data` directory must contain files named `names` and `surnames`, one display-name component per line. The embedded dictionaries remain active when `data` is omitted.

## Direct routes

`route.direct` and `route.direct_file` (client only) name the destinations that bypass the tunnel. A CONNECT to a listed name or address is dialed by the client itself, over the same protected socket the provider uses, so on a phone it leaves through the physical interface. A CONNECT for an address the rules do not list is answered first and the client's first bytes are read for a TLS server name or an HTTP `Host`: a listed name found there is dialed directly, by that name; anything else takes the tunnel with those bytes replayed. With `udp.enabled`, datagrams to a listed target are relayed by the client too, and a DNS query (port 53) for a listed name is answered by the client's own resolver (`net.dns` and the network's servers) instead of through the exit. `domain:` matches the name and everything under it, `full:` the name only; a line that is not a rule fails the start.

```yaml
route:
  direct:
    - domain:ru
    - full:api.example.com
    - 10.0.0.0/8
```

## Server egress

A server never opens a TCP connection or a UDP flow for a client into its own host or its networks: loopback, private, link-local (cloud metadata included), shared CGNAT space (`100.64.0.0/10`), multicast, reserved and the other ranges Xray's freedom outbound refuses by default. A name is resolved once and judged by every address it resolves to - one refused address refuses the target - and the checked address itself is dialed, so the name is never resolved a second time. An IPv4-mapped IPv6 address is judged as the IPv4 address it reaches. With `socks.proxy_addr` a name goes to the proxy unresolved, for the proxy to resolve under its own policy; only a literal address is judged.

Without `debug` the server's log names no destination: a failed connect says why (`blocked target`, `no such host`, `connection refused`, ...) but not where, and the `traffic:` line carries the session and the byte counts. `debug: true` adds the target to every connect.

## Config schema migration

For one migration cycle the strict loader accepts the deprecated fields `link`, `ffmpeg`, `video.bitrate` and `video.hw`. All four fields are ignored by the current runtime and will be removed in the next config schema. Delete them from persisted configs. Other unknown or misspelled fields still cause a load error.

## Wire compatibility

Current builds use the OLC2 encrypted record layer. Directional HKDF-SHA256 keys, distinct data/control AEAD associated data and a shared 64-record replay window make it incompatible with the old record format. There is no legacy decoder fallback.

`seichannel` and `videochannel` use OLVC frame version 5, which adds a per-fragment checksum so a damaged fragment is retransmitted instead of acknowledged. Older frames are rejected by magic or version checks. Upgrade both tunnel endpoints together. Within version 5, `seichannel` keeps several messages in flight towards a peer whose hello announces ordered delivery, and falls back to one at a time for any other peer.

## Required minimum

### Server

> **Jitsi provider:** take instances from [`jitsi.instances.yaml`](./jitsi.instances.yaml), not from bare text. Check the host in the browser and pick a working one.

```yaml
mode: srv
auth:
  provider: jitsi
room:
  # Take the host from docs/jitsi.instances.yaml:
  # https://HOST/ROOM
  id: "https://REPLACE_ME_WITH_HOST/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
```

### Client

```yaml
mode: cnc
auth:
  provider: jitsi
room:
  # Take the host from docs/jitsi.instances.yaml:
  # https://HOST/ROOM
  id: "https://REPLACE_ME_WITH_HOST/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
socks:
  host: "127.0.0.1"
  port: 8808
```

## Liveness

After `CLIENT_HELLO` / `SERVER_WELCOME` the first smux stream stays open as an encrypted control stream. Over it `olcrtc` sends `CONTROL_PING` / `CONTROL_PONG` to check the actually working tunnel path, not just the status of the WebRTC connection.

```yaml
liveness:
  interval: 10s
  timeout: 15s
  failures: 4
```

When the threshold of missed pongs is reached, the current smux session is rebuilt. In failover mode the profile that finished after a failed reconnect hands control to the supervisor, and the supervisor tries the next profile.

## Lifecycle Rotation

`lifecycle.max_session_duration` sets a planned upper bound on the duration of a single call/session at the provider. When the time runs out, the active `srv` or `cnc` session is closed and started again with the same config.

```yaml
lifecycle:
  max_session_duration: 6h
```

The field is optional. Format is Go duration: `30m`, `2h`, `6h`. Zero and negative values are not accepted.

## Traffic Shaping

`traffic` adds a common wrapper around the chosen transport. It can limit the size of the encrypted message and add a small delay before sending. Data is not truncated: if the payload does not fit the effective limit, the send fails with an explicit error.

```yaml
traffic:
  max_payload_size: 4096
  min_delay: 5ms
  max_delay: 30ms
```

The limit is clamped to the `MaxPayloadSize` declared by the chosen transport. Client and server also reduce the smux frame size accounting for crypto overhead. A value of `0` adds no limit beyond the transport limit. If only `min_delay` is set, the delay is fixed. Use the same `traffic` settings on both sides.

## Failover Profiles

`mode: srv` and `mode: cnc` can define `profiles`. Top-level fields become shared defaults, and each profile overrides only what is specified inside it.

```yaml
mode: srv
crypto:
  key_file: ./olcrtc.key
net:
  dns: "8.8.8.8:53"

profiles:
  - name: wb-vp8
    auth:
      provider: wbstream
    room:
      id: "WB_ROOM_ID"
    net:
      transport: vp8channel

  - name: jitsi-dc
    auth:
      provider: jitsi
    room:
      id: "https://meet.example.org/olcrtc-room"
    net:
      transport: datachannel

failover:
  retry_delay: 2s
  max_cycles: 0
```

The order of profiles and the room parameters must be compatible on the server and the client. Active smux streams do not migrate between profiles; new connections can recover on the next profile.

The config file is re-read at every advance, so profiles added or removed while a session was live take effect the moment it ends, without a restart. The supervisor looks in the new list for the profile that just ran and takes the one after it; a profile the list no longer holds means the window rolled past it, and the walk continues at the head without counting a completed pass. An invalid profile is skipped with a warning rather than failing the reload, and a reload that errors or comes back empty keeps the last good list.

A client running under the supervisor gives up on a room nobody is in - its handshake fails and nothing in the room sent a frame while it ran - so the supervisor can move to the next profile. A peer that is in the room and silent, and a single-profile config, are retried for as long as olcrtc runs.

## mode: gen

`gen` is kept for providers that implement room creation via an API.

`salutejazz` is the one built-in provider that does: `-mode gen` creates a room
with a single anonymous POST and prints it as `<code>:<password>`, which is what
`room.id` takes.

The others do not create rooms through `olcrtc`: for `telemost` and `wbstream`
create the room on the service site and paste it into `room.id`; for `jitsi`
specify the room URL; for `vkcalls` create the call in the VK app and paste
its join link (`https://vk.ru/call/join/<id>`) — `room.id` takes it verbatim;
for `mtslink` create an endless meeting («постоянная встреча») in the MTS Link
cabinet and paste its join link (`https://my.mts-link.ru/j/<owner>/<event>`),
which a guest enters with no organizer inside.

A fresh VK room keeps its first participants in DIRECT topology: two alone
never switch it, a third simultaneous participant does, and SERVER sticks
for the room after that. The engine handles this itself: while it waits it
recruits signaling-only guest joins (at most two, dropped the moment the
switch arrives), so a server alone in a fresh room proceeds as soon as its
recruits — or the client and one recruit — make the third participant.
