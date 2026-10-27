# Contributing

Patches, bug reports and reviews are welcome, and none of them needs permission
first. This is the engine behind [Ghostlane](https://github.com/ghostlane-project/ghostlane);
`docs/` is the manual and `AGENTS.md` describes how the repository is laid out.

## Before a large change

Open an issue first. The transports are subtle, and a short exchange saves
rewriting work that collided with something in flight. Small fixes need no
ceremony.

## The rules of the house

- **English in code, comments and commit messages.** Issues and pull requests
  may be in English or Russian; the docs exist in both (`docs/*.md` and
  `docs/*.ru.md`) and a change to one should update the other.
- **Explain the why.** A comment repeating the code is noise. A comment saying
  why the obvious approach failed is why this codebase is readable.
- **Tests where behaviour changes**, and a bug fix comes with a test that fails
  without it.
- **`golangci-lint run ./...` clean**, and `gofmt` clean, before you send it.
  The configuration is in `.golangci.yml`.
- **Never commit credentials**: no room ids, tokens, keys or subscription URLs.
  The history is public and permanent.

## Building and testing

```bash
go build ./...
go test ./...                    # and with -tags olcrtc_lean, the phone build
go test -race ./internal/...     # before sending anything touching concurrency
golangci-lint run ./...
```

The release gate (`internal/gate`, documented in `docs/gate.md`) runs real
transfers through real relays. It needs rooms and credentials of your own, and
it is not expected of an outside contributor: say in the pull request that you
could not run it, and a maintainer will.

## Sending a pull request

- One subject per pull request.
- Fill in the template: what changes, why, how it was tested, what you could not
  test.
- Wire-format changes need a word on compatibility: an old peer and a new one
  meet in the field, and the project keeps them working.

## Licence

Contributions are accepted under the repository's licence, the Apache License
2.0 (`LICENSE`); `NOTICE` records how this fork relates to its upstream. No
copyright assignment is asked for and there is no contributor licence agreement
to sign: section 5 of the licence already states that a contribution you submit
is offered under it, and section 3 carries the patent grant that goes with it.

## Being paid

Contributors may be compensated from project funds for substantial work; see
[FUNDING.md](https://github.com/ghostlane-project/ghostlane/blob/main/FUNDING.md)
in the application repository, which carries the funding policy for both
projects. Nothing about contributing depends on it.
