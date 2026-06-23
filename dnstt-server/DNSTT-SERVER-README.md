# dnstt-server (olcvpn build)

The VPS end of the DNS tunnel used by `EngineType.Dnstt`, packaged for the in-app one-tap installer
(`DnsttServerInstaller`). It is David Fifield's **dnstt-server**
(<https://www.bamsoftware.com/software/dnstt/>, source mirrored in `_dnstt_src/go_src/dnstt-server`)
with two small olcvpn additions:

- **`-domain DOMAIN`** flag — lets the installer pass the tunnel domain without a positional arg.
- **`-socks-port N`** flag + `socks.go` — a built-in no-auth, CONNECT-only **SOCKS5** listener on
  `127.0.0.1:N`, used as the tunnel upstream. The mobile dnstt client pipes a raw SOCKS5 byte-stream
  through each tunnel stream, so the upstream must terminate SOCKS5. With this flag the VPS is a
  self-contained internet exit — no separate proxy (microsocks/dante) to install.

The dnstt protocol packages (`dns`, `noise`, `turbotunnel`) are reused from the vendored client
module `../dnstt` via a `replace` directive, so there is a single copy of the protocol code.

## Direct mode (what the installer uses)

The installer does **not** require NS delegation. It runs the server on a custom UDP port and the
client points its resolver straight at `VPS_IP:PORT` (direct mode). The domain is then just an
arbitrary label that must match on both ends.

```
# one-time, persistent keypair
dnstt-server -gen-key -privkey-file /etc/dnstt/server.key -pubkey-file /etc/dnstt/server.pub

# run (systemd): listen on UDP 5300, built-in SOCKS5 exit on 127.0.0.1:8000
dnstt-server -udp 0.0.0.0:5300 -privkey-file /etc/dnstt/server.key -domain t.example.com -socks-port 8000
```

Client side (auto-filled by the installer on success): resolver `VPS_IP:5300`, domain
`t.example.com`, public key = contents of `server.pub`.

## Building

```
pwsh -File build-dnstt-server.ps1
```

Cross-compiles `linux/amd64` + `linux/arm64`, gzips each and writes them to
`../YPtun/androidApp/src/main/assets/dnstt/`, where the installer reads them.

## License

dnstt is released into the public domain / CC0 by its author. See the upstream project for details.
