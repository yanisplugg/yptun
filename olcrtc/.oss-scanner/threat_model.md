# Threat model

For automated and human security review of olcRTC. How to report is in
[SECURITY.md](../SECURITY.md).

## What this project does

olcRTC is a tunnel that travels inside a WebRTC call on a public meeting
service. A client (`olcrtc` in `cnc` mode, or the library under `mobile/` and
`pkg/` that Ghostlane embeds on Android, iOS, macOS, Windows and Linux) serves
SOCKS5 to local apps and carries their TCP streams and UDP datagrams through a
room on a meeting service (a carrier: Jitsi, Yandex Telemost, WB Stream,
SaluteJazz, VK Calls, MTS Link, MAX) to a server (`olcrtc` in `srv` mode) in the
same room, which dials the destinations.

```text
app -> SOCKS5 -> olcrtc cnc -> carrier's SFU (room) -> olcrtc srv -> internet
```

The layers, from the wire up:

- **Engines** (`internal/engine/*`) speak each carrier's signaling (WebSocket
  JSON, XMPP and Jingle, LiveKit protobuf, SDP over HTTP) and run the WebRTC
  peer connections (pion). **Auth providers** (`internal/auth/*`) get room
  credentials from the carriers' web APIs.
- **Transports** (`internal/transport/*`) turn a peer connection into a byte
  link: `datachannel` (SCTP data channels), `vp8channel` (KCP inside valid VP8
  keyframes), `seichannel` (fragments in H.264 SEI NAL units) and
  `videochannel` (fragments drawn into video frames).
  `internal/transport/common` holds the frame codec, fragmentation and
  reassembly, acknowledgement and retransmission that the video transports
  share; `internal/relaywin` bounds what a sender leaves on the relay for one
  destination.
- **Record layer** (`internal/crypto`, `internal/muxconn`): every record is
  XChaCha20-Poly1305 under directional keys derived from a pre-shared key
  (HKDF-SHA256), with an AAD per lane, a random nonce prefix, a counter and a
  replay window. A server may hold a key ring, one key per user; a session is
  pinned to the first key that authenticates one of its records.
- **Session**: smux over the record layer. Its first stream carries the
  handshake (`internal/handshake`, protocol version 3: a challenge, a device id
  and claims) and then the control protocol (`internal/control`), both
  length-prefixed JSON (`internal/framing`). Datagrams use `internal/udpwire`
  on a lossy lane.
- **Server** (`internal/server`): accepts streams and UDP flows, applies the
  egress policy (`egress.go`: no loopback, private, link-local, metadata or
  reserved target; a name is resolved once and the checked address is the one
  dialled), counts bytes per key (`meter.go`) and serves the counts to the
  host's own agent on a loopback HTTP listener (`GET /stats`, `stats.listen`).
- **Client** (`internal/client`): the SOCKS5 server (CONNECT, UDP ASSOCIATE,
  optional username and password), direct rules (`internal/route`) and
  server-name sniffing for tun2socks setups (`internal/sniff`).
- **Inputs from the embedding app**: `olcrtc://` links (`internal/link`,
  `internal/roomhint`), YAML configuration (`internal/config`), the gomobile
  API (`mobile/`) and the c-shared API (`cmd/olcrtc-cgo`).

## Who the attacker is, and where untrusted input enters

1. **Anyone in the room.** A room is joined by its link, with the carrier's own
   web client or any WebRTC stack. Every user of a server holds that room's
   link, so a participant may hold a valid key of its own (another user of the
   same server) or no key at all. It can send any RTP, RTCP and data channel
   message, and those reach every transport's frame decoder, reassembly,
   acknowledgement and window logic before any record is authenticated. It must
   not be able to read, inject into or redirect another participant's tunnel,
   end or wedge it cheaply, or make the server count bytes against another key.
2. **The carrier.** Its SFU and signaling servers see, drop, reorder and rewrite
   every frame and every signaling message. A hostile carrier can cut a tunnel
   and can see that a call took place and how much it carried. It must not be
   able to read or forge tunnel traffic, crash or exhaust a client or a server
   through its signaling or its media, or steer a client's traffic to anyone
   but the server that holds the key.
3. **A key holder, against the server and the other users.** An authenticated
   client picks its CONNECT targets, UDP flows, stream counts and timing. It
   must not reach the server host's own networks (loopback, private,
   link-local, cloud metadata) through the egress policy, see or disturb other
   key holders' sessions, exhaust what they share (the UDP flow table, memory,
   goroutines, file descriptors), or move its bytes onto another key's count.
4. **A server, against its clients.** A malicious server operator sees the
   traffic it lets out, as any VPN exit does. It must not be able to run code
   on a client, exhaust it, or reach the client's local network through it.
5. **The network between a peer and the carrier** (a censor's DPI). It sees the
   carrier's TLS, DTLS and SRTP. What the engine adds on top of an ordinary
   client of that carrier should not be a cheap and reliable distinguisher.
6. **Local processes and local inputs.** On a client, other local processes can
   reach the SOCKS5 port; with credentials configured they must not use it
   without them. Links, configuration and route rules can come from a
   third-party server list and must be parsed without crashes or escapes. On a
   server, `/stats` is meant for the host's own agent, on loopback.

## Components that matter most and least

Most: `internal/crypto`, `internal/muxconn`, `internal/handshake`,
`internal/control`, `internal/framing`, `internal/udpwire`,
`internal/transport/*`, `internal/relaywin`, `internal/server` (egress, UDP
flows, metering), `internal/client` (SOCKS5), `internal/engine/*` and
`internal/auth/*` (a hostile carrier), `internal/link` and `mobile/`.

Less: `internal/names`, `internal/logger`, `internal/supervisor` (failover
between profiles), and `install.sh` (an installer an operator runs; worth a
look for injection through its own inputs).

Out of scope: `internal/gate` and `cmd/gate-report` (the release gate, which
drives real relays), `internal/e2e` (tests), `internal/fakedns`,
`internal/testhooks` (compiled only with the `olcrtc_testhooks` tag), `docs/`
and `magefile.go`. A bug inside a third-party module (pion, smux, kcp-go, the
LiveKit SDK, gorilla/websocket, the DTLS fork) belongs upstream; report it here
when olcRTC's own code makes it reachable, and say how.

## How to exercise it

- The tree is at `/src`. Binaries are in `/src/build`: `olcrtc` (the command
  line program, `olcrtc <config.yaml>`, modes `srv` and `cnc`, examples in
  `docs/examples/`), `olcrtc-lean` (the build the mobile apps use) and
  `libolcrtc.so` (`cmd/olcrtc-cgo`). No real carrier can be joined from a scan;
  everything below runs offline.
- `go test ./...` runs the suite. CI also runs it with `-race`, with
  `-tags olcrtc_lean` and with `-tags olcrtc_testhooks`; the build cache holds
  all four.
- The tests that connect two pion peers in one process (`internal/e2e`,
  `internal/engine` and its `jitsi`, `maxcalls`, `mtslink` and `salutejazz`
  packages) need an up, non-loopback IPv4 interface, which a sandbox with only
  `lo` lacks: there they fail after their connect timeouts, and the SaluteJazz
  ones skip. As root, before `go test`, one dummy interface is enough for the
  whole suite to pass offline:

  ```sh
  ip link add pk0 type dummy && ip addr add 10.99.0.1/24 dev pk0 && ip link set pk0 up
  ```
- `internal/e2e` runs whole tunnels in one process: a client and a server in an
  in-memory room (`tunnel_test.go`; `multikey_test.go` and
  `multikey_metering_test.go` for a key ring and its counts; `udp_test.go`;
  `egress_test.go`). Reproduce anything above the transports there.
- The transport and codec tests (`internal/transport/*/*_test.go`) drive one
  side with crafted frames: a hostile participant's reproducer belongs there.
- Go fuzzing works offline, for example
  `go test ./internal/sniff -run '^$' -fuzz FuzzHost -fuzztime 60s`. New fuzz
  targets for the frame decoders, `framing`, `udpwire`, `handshake` and `link`
  are welcome in a report.

## How we rate severity

- **Critical**: reading or forging tunnel payload without the key; a room
  participant or the carrier taking over or redirecting another user's tunnel;
  code execution or memory corruption from the network (cgo, `unsafe`, a
  parser); a key holder reaching the server host's loopback, private or
  link-local networks or cloud metadata.
- **High**: a party without a key ending, wedging or starving another user's
  tunnel with a few frames or messages (a censor's cheap kill switch); one key
  holder disturbing another's sessions or exhausting what they share; bytes
  counted against the wrong key, or traffic that escapes the count; a key, a
  room credential or a carrier token reaching a log, the network or another
  party; memory growth on a client without bound, driven by the room or the
  carrier (a mobile host kills the tunnel past a few tens of megabytes).
- **Medium**: exhaustion that needs sustained traffic of the size of what it
  disrupts, or a hostile carrier's signaling; a local unprivileged process using
  the SOCKS5 port or `/stats` beyond what the configuration allows; log
  injection from strings the network controls; a concrete, code-level
  distinguisher of the engine's traffic from the carrier's ordinary clients.
- **Low**: a crash from a malformed local configuration file; anything that
  needs root or control of the device, which the design does not defend against
  (see SECURITY.md).
- What the design does not promise is not a finding by itself: the carrier sees
  that a call took place and its volume, and the tunnel makes nobody anonymous.

## How reports and patches should look

Name the attacker's position from the list above, the carrier and the transport
when one matters, and what the attacker gets. The most useful reproducer is a Go
test in the package concerned, with in-memory links and no network. A patch
should keep `go test -race ./...` and golangci-lint (v2, with the repository's
`.golangci.yml`) clean, and keep the wire protocol compatible with older peers
unless the report says why it cannot.
