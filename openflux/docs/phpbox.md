# Mode without a server (a PHP node on any web hosting)

Instead of a VDS running the Go binary, an exit can be a small PHP program
on **any** ordinary web hosting - free or paid, anything with PHP and
FTP/FTPS, no VPS and no `node-install.sh`. The client and the node meet in
a cups.online room or a Mail.ru document, so the hosting never has to
accept an inbound connection, and on a network where only that channel
opens, only that channel has to be reachable. TCP only (ports 80 and 443
at the exit), no key - the content stays protected by the apps' own TLS,
and the hosting sees where you go. This is meant for reconnect-tolerant,
mostly-`:443` traffic (Telegram/MTProto, short HTTPS requests), not as a
general full-tunnel VPN exit.

This page is the short version; the authoritative, deeply detailed one -
frame format, flow control, chain-mode handover, free-host quirks measured
on InfinityFree - is [`deploy/phpbox/README.md`](../deploy/phpbox/README.md).

```bash
# the client, as SOCKS5 and HTTP proxies, or as the system's full tunnel (utun/Wintun)
./openflux --role=client --mode=stream --transport=cupsonline \
    --url "https://interview.cups.online/live-coding/?room=<uuid>" --socks5 127.0.0.1:1080 --http-proxy 127.0.0.1:1081
sudo ./openflux --role=client --mode=stream --inbound=tun --transport=mailru --url "https://cloud.mail.ru/public/..."
```

- **Full tunnel** (`tunnel.StreamNet`): packets go into a local stack that
  opens a stream per TCP connection; DNS is answered locally with fake
  addresses and the name is opened at the exit. QUIC, other UDP and IPv6
  are dropped, so browsers fall back to TCP.
- **Clients in the apps**: `mobile.StartStreamProxy` / `StartStreamPacket`,
  `OpenFluxStartStreamClient` / `OpenFluxStartStreamPacketTunnel`. A link or
  QR for this mode has `"mode":"stream"` (`share.ModeStream`) and one
  carrier - see [links.md](links.md).
- `-d` / `-dd` / `-ddd` work here like in the packet modes: `[STREAM] ->
  526 bytes - stream 7 DATA` - see [logging.md](logging.md).

## Putting the node on a hosting

Both the apps' "Без сервера" ("without a server") wizard and
`--node-wizard`'s `php.*` methods go through `provision/phphost`:

- `probe` finds the web folder (also a level or two down:
  `domains/<site>/public_html`, `www/<site>`) and whether it is writable.
- `deploy` uploads the bundle embedded in the core (`deploy/phpbox`, with
  the link parser compiled to WebAssembly) over FTP/FTPS, keeps the token
  of an earlier install, and retries a file up to four times (reconnecting
  between tries, and falling back from TLS to plain FTP after repeated TLS
  data-channel failures) so a host that cuts transfers short still gets a
  working node.
- `check` asks the site, passing the iFastNet-style AES browser check in
  plain Go so no real browser is needed.
- `start`, `stop`, `node`, `newRoom`, `link`, `remove` round out the
  lifecycle; the node itself answers `a=ping`.
- Every answer is a code (`ftp_login`, `ftp_no_webroot`, `site_antibot`,
  `php_missing`, ...), never free text - the app words each one. One
  dispatcher, `phphost.Call`, serves the desktop wizard (`--node-wizard`,
  methods `php.*`, with progress lines), the Android bridge (`PhpCall`,
  `PhpProgress`, `PhpCancel`) and the iOS C API (`OpenFluxPhpCall`). The
  FTP password travels only through the pipe, never a flag or a log line.

See [node-provisioning.md](node-provisioning.md) for the sibling SSH
wizard that provisions a real VDS instead.

## The exit's own status page

Opening an exit's URL in a browser shows a status page instead of silently
running: live state (running / run number / time left / streams / bytes),
a debug log, Start/Stop, and a link box that parses a pasted
`openflux://` link client-side and draws its QR. A heartbeat and lock
mean a second open, or a pinger, attaches to the already-running node
instead of starting a second one. With `&chain=1` (on by default) a node
starts its successor before its own host-imposed time limit ends, so the
tunnel survives indefinitely across hosts that only grant a node a few
minutes per request. Full detail:
[`deploy/phpbox/README.md`](../deploy/phpbox/README.md#the-page-v03) and
[...#keeping-the-tunnel-up-chain-mode-v04](../deploy/phpbox/README.md#keeping-the-tunnel-up-chain-mode-v04).

## v0 caveat

Frames between the client and the PHP exit carry no encryption of their
own in v0: the hosting and anything on-path see destinations and traffic
shape (the app's own TLS/MTProto still protects content). Wrap the carrier
with the core's encryption (see [sessions.md](sessions.md)) before relying
on this for anything sensitive.
