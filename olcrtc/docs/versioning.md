# Versions

[RU](versioning.ru.md) / **EN**

The engine's versions are git tags of the form `vMAJOR.MINOR.PATCH` on commits
of the `proofkit` branch. Every tag has a
[GitHub release](https://github.com/ghostlane-project/olcrtc/releases) that says
what changed since the previous one and where that engine ships.

## The rules

- **MINOR** grows for a new carrier, new behaviour, or anything a peer sees on
  the wire: the handshake, the frames, a server's YAML. **PATCH** grows for a
  change that touches none of that: a fix inside one side, the gate, tests,
  docs. MAJOR stays 0 while the Go API may still change.
- **A tag never moves and is never deleted.** The Go checksum database keeps
  the commit behind every version a proxy has served, so a tag that moved would
  fail every later build that already knew it. A mistake is fixed by the next
  version.
- **Every engine that ships gets its tag first**, a client's pin and a server
  build alike. The release names what ships it.
- **Consumers name the version.** The module keeps its upstream path, so a Go
  consumer writes `require github.com/openlibrecommunity/olcrtc vX.Y.Z` and
  `replace github.com/openlibrecommunity/olcrtc => github.com/ghostlane-project/olcrtc vX.Y.Z`.
- **A binary says its version.** `olcrtc -version` prints it, and the first line
  of every run logs it. A build from a clean checkout of a tag says the tag
  (`v0.12.0`). Any other build says a pseudo-version past the last tag, which
  names its commit and ends in `+dirty` when the tree had changes.

## Releasing a version

1. The change is merged into `proofkit`, and CI, the gate included, is green on
   the commit.
2. Tag it and push the tag:

   ```sh
   git tag -a vX.Y.Z <commit> -m "olcRTC vX.Y.Z"
   git push origin vX.Y.Z
   ```

3. Publish a release for the tag: what changed since the previous tag, by its
   merges, and where it ships. Add its line to the table below.

## Versions

The [releases page](https://github.com/ghostlane-project/olcrtc/releases) is the
full list.

| Version | Commit | Date | Ships in |
|---|---|---|---|
| v0.12.1 | `d15b37ec` | 2026-10-23 | the servers of the ProofKit network, build `metering-v12` |
| v0.12.0 | `b6cb4c41` | 2026-10-19 | Ghostlane 1.0.467 |
| v0.11.1 | `bac09891` | 2026-10-12 | Ghostlane 1.0.466 |
| v0.11.0 | `443c1854` | 2026-10-11 | the servers of the ProofKit network, build `metering-v11` |

Engines before v0.11.0 were never tagged. v0.11.0 takes its minor number from
the server build it is, so that the eleventh server build and v0.11 name the
same engine. v0.11.1 is v0.11.0 with changes to the gate and the tests only.
