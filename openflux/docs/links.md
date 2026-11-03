# Links and QR codes (`openflux://`)

An exit can print a link and QR code that sets a client up by scanning
instead of copying keys and document URLs by hand:

```bash
./openflux --role=exit --mode=l3 --negotiate \
    --transports=direct:100,yandex:50 --direct-listen=0.0.0.0:8445 \
    --encryption-key-file=secret.txt --url="YOUR_YANDEX_DOC_URL" \
    --share --share-host=EXIT_PUBLIC_IP
```

- The link contains the encryption key: treat it and the QR code like the
  key file.
- `--share-host` is the address clients dial for `direct`; by default the
  first public IPv4 of the host.
- MAX is left out (a token belongs to one account), and so is Cups.online
  when its rooms are created at startup.

## Format

`openflux://v1/<base64url(DEFLATE(JSON))>`, implemented in the `share`
package (`share.go`). The JSON payload (`share.Config`):

```json
{
  "name": "profile name (optional)",
  "negotiate": true,
  "codec": "batched",
  "secret": "shared encryption secret",
  "context": "encryption context (the exit's --url)",
  "mode": "",
  "transports": [{"type": "yandex", "url": "..."}, {"type": "direct", "dial": "host:port"}]
}
```

`mode` is `""` for the classic packet tunnel, or `"stream"` for a link to
the no-VPS PHP exit - see [phpbox.md](phpbox.md). A reader that predates
the `mode` field ignores it and would run the classic tunnel against a
stream-mode exit, which cannot work, so only links actually made for
stream mode carry it.

`share.Encode` / `Decode` / `PNG` / `Bitmap` / `Terminal` render and parse
the format and its QR code, for every client (CLI, Android, iOS, Desktop)
to use identically. **Links are read and made by the core only** - every
entry point (CLI flags, the mobile bridges, the iOS C API) calls into the
same `share.Read` / `share.Make`, so one configuration gives one link on
every client, and an error is always one of a fixed set of `code`s the
apps word for their own users, never free text from the core.

## From the CLI

```bash
# Parse a link (or "-" to read it from stdin) into {"config","context"} or {"error","code","param"}
./openflux --parse-link "openflux://v1/..."
./openflux --parse-link -

# Build a link for a share configuration (or "-" to read the JSON from stdin)
./openflux --make-link '{"transports":[{"type":"yandex","url":"..."}],"secret":"..."}'
```

Error codes (`share.Code*`): `not_link`, `unsupported_version`,
`case_changed`, `damaged` (not base64url/DEFLATE: mangled or cut in
transit), `too_large`, `bad_payload`, `bad_config`, `no_transports`,
`several_need_session`, `session_secret`, `short_secret`, `unknown_codec`,
`not_shareable` (MAX), `unknown_transport`, `direct_no_dial`,
`direct_needs_session`, `unknown_mode`, `stream_transport`,
`stream_one_transport`, `stream_plain_only`.

`openflux://` links tolerate base64 padding, the standard alphabet,
whitespace and line breaks (links get mangled by chat clients and
terminals), and count a secret's minimum length in characters the way
Kotlin counts them, not bytes - so the same rule rejects a too-short secret
identically on every client. `share/compat_test.go` freezes links written
by earlier builds as literals that must keep reading, so a format change
can't silently break an old link.

## On the no-VPS PHP exit

The phpbox status page parses and draws a pasted link's QR entirely in the
browser, using the core's own `share` package compiled to WebAssembly
(`cmd/sharewasm`) - one link parser for every client, and the secret never
leaves the browser. See [phpbox.md](phpbox.md).
