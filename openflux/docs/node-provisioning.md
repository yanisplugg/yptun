# Node provisioning (the SSH wizard)

`--node-wizard` runs a JSON-over-stdin/stdout protocol (one object per
line) for a desktop app to provision a new exit node over SSH
non-interactively, without the app ever shelling out to `ssh` itself:

```
Request:  {"id": 1, "method": "connect", "params": {...}}
Response: {"id": 1, "ok": true, ...} or {"id": 1, "ok": false, "error": "..."}
```

Secrets (SSH and sudo passwords, the private key, the channel key) arrive
only on stdin and never reach the log or the command line.

## What it does

- `provision/` drives the SSH connection: it connects, has the VDS
  download a pinned, hash-checked copy of `deploy/node-install.sh` (the
  commit and SHA-256 are pinned together in `provision/pin.go`, so a
  changed file on GitHub is never run), and runs it.
- Each channel is an independent node (its own document/carrier mix, key,
  port and systemd instance) - installing one never touches another.
- A channel's carrier can be any mix of a Yandex document, a Mail.ru public
  document and cups.online rooms besides `direct`
  (`provision.ChannelTransport`); the rooms are created by the app
  (`cupsonline.CreateRoomList`) so the node keeps them, and its link,
  across restarts.
- `provision.ShareLink` builds the finished node's `openflux://` link from
  the same priorities and encryption context `node-install.sh` writes to
  the node's config - see [links.md](links.md).

## Protocol methods

`newChannel`, `connect`, `disconnect`, `plan`, `apply`, `remove`,
`checkDocument`, `createRooms`, `shareLink` (see `node_wizard.go`). A new
server's host key comes back as `{"hostKey": "...", "trust": true}` so the
user can compare the fingerprint before trusting it; a changed key comes
back with `"mismatch": true`.

The same dispatcher also serves `php.*` methods (`php.probe`, `php.deploy`,
`php.check`, `php.start`, `php.stop`, `php.node`, `php.newRoom`, `php.link`,
`php.remove`) for the no-VPS PHP-hosting path - see
[phpbox.md](phpbox.md#putting-the-node-on-a-hosting).

## Keeping the node current

`node-install.sh update` and the optional
`openflux-node-update.timer` move the node to the newest `node-v*` release
every 6 hours: it downloads the release, verifies the core against that
release's own `node-install.sh` and `SHA256SUMS`, restarts the channels,
and rolls back (and skips that release) if a channel does not stay up. An
app with an older pinned script no longer downgrades a server the updater
has moved on. `repo=owner/name` in `/etc/openflux-node/update.conf` points
it at another repository's releases.

The `node-v*` releases this points at are built by
[building-and-releases.md](building-and-releases.md) (`node-release.yml`),
and `deploy/node-install.sh` itself is exercised across ten Linux
distributions by `.github/workflows/node-install.yml` on every change
under `deploy/` or `provision/`.

## Other entry points

The same JSON protocol backs the Android bridge (`mobile.Node*`,
`PhpCall`/`PhpProgress`/`PhpCancel`) and the iOS C API
(`OpenFluxPhpCall`), so a client linking the core in-process (gomobile, the
iOS static library) gets identical behavior to the desktop app shelling
out to `--node-wizard`.
