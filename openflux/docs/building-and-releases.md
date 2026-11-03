# Building and releases

## Build

```bash
go mod tidy
go build -o openflux .
```

Cross-build for the exit node (Linux amd64), stripped:

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build -ldflags="-s -w" -trimpath -o openflux-linux .
```

A slimmer exit-only binary (no desktop node-wizard, no pinned install
script) builds with `-tags exitnode`; this is what `node-release.yml`
publishes - see below.

## Requirements

1. **Go** - see `go.mod` for the exact version.
2. **Android NDK r27+** - to build the Android client binary (`build_android.sh`).
3. **Xcode 26.6+** - to build the iOS client binary (`build_ios.sh`, `build_ios_app.sh`).
4. **A Linux VPS / VDS** for a VPS-hosted exit node. The `l3` backend
   requires root; `l4` works without. (Not needed for the no-VPS PHP exit -
   see [phpbox.md](phpbox.md).)

## CI (`.github/workflows/ci.yml`)

Runs on every push and pull request: `go test ./...` (with and without
`-race`), `go vet`, a cross-build matrix (linux/windows/darwin x
amd64/arm64, plus linux/arm), and an isolated Linux raw-socket/UDP/ICMP
test inside a disposable network namespace (`tunnel/l3`, via `unshare
--net`).

`.github/workflows/node-install.yml` separately exercises
`deploy/node-install.sh` - the SSH wizard's whole install path (plan,
apply with a sudo password, update/rollback, remove) - against ten Linux
distributions (Ubuntu, Debian, Rocky, Alma, Fedora, Arch, openSUSE) in
Docker/systemd containers, on any push or PR touching `deploy/` or
`provision/`.

## Tagged releases (`release.yml`)

A tag `v1.2.3` cross-compiles the CLI (client/exit) for Linux
(amd64/arm/arm64), Windows (386/amd64/arm64) and macOS (amd64/arm64) and
publishes it as a GitHub release with `SHA256SUMS.txt`, with release notes
taken from the matching `CHANGELOG.md` section. "Run workflow" builds the
same binaries as artifacts without publishing, for a dry run, and can also
publish from a chosen commit by ticking "Publish" and giving a version.

## Exit-node releases (`node-release.yml`)

A tag `node-v1.1.0` builds just the Linux exit-node core (`-tags
exitnode`, amd64/arm64/arm) and publishes it separately. The build is
reproducible (`-trimpath`, no VCS stamp, empty build id), and the job
**fails instead of publishing** if the built binaries' SHA-256 hashes
don't match the ones pinned in `deploy/node-install.sh` - a mismatch there
would mean an app's pinned installer and the actual release have silently
diverged. This is the release channel [node-provisioning.md](node-provisioning.md)'s
SSH wizard and update timer install and track.

## Nightly prereleases (`nightly.yml`)

A third channel, next to tagged `v*` and `node-v*`: a nightly is a GitHub
*prerelease* tagged `nightly-<date>-<commit>`, cross-compiled the same way
as `release.yml`, plus the three script dev tools
(`scriptsign`/`scripttest`/`scriptbundle` - see
[scripted-transports.md](scripted-transports.md)) for every OS. It carries
no update mechanism of its own; it exists so the Android/Desktop nightlies
(and anyone testing by hand) have a current CLI build to point at or pin a
submodule to.

It runs on a push to the `nightly` branch (how a test build is cut), every
night via cron if that branch has a commit with no build yet, or manually
via "Run workflow". Only one build per commit is ever published (checked
against existing release tags), and only the newest 7 nightly releases (and
their tags) are kept - older ones are deleted automatically after each
publish.

## Script transport signing keys

Shipping a new or updated scripted transport (`transport/script/js/*.js`)
needs `cmd/scriptsign` to produce its `.sig` (or to pack a `.flux`) - see
[scripted-transports.md](scripted-transports.md#dev-tools-transportscriptcmd).
That tool, and `cmd/scripttest`/`cmd/scriptbundle`, ship prebuilt in every
nightly for exactly this workflow.
