# Logging and debugging

| Flag | Shows |
|------|-------|
| `-d` (`--debug=1`) | One line per IPv4 packet: `-> 52 bytes - UDP 10.10.10.2:53000 -> 8.8.8.8:53 ...` |
| `-dd` (`--debug=2`) | Plus operational logs: sessions, carriers, handshakes, crypto, control, errors. |
| `-ddd` (`--debug=3`) | Plus hexdumps of packets and ciphertext. |
| `--sensitive` (alias `--sensetive`) | Also logs key material and, with `-ddd`, plaintext frames - cookie jars, tokens. Off by default. |

`-d`/`-dd`/`-ddd` are shorthand for `--debug=1|2|3`; `-dddd` and beyond
still clamp to 3. A bare `--debug` (no value) means `1`. These are expanded
before flag parsing (`expandShortFlags` in `main.go`), so `-d --role=exit`
and `--debug=1 --role=exit` are equivalent - but a single-dash *long* flag
that happens to start with `d`, like `-direct-listen=:9000`, is left alone.

The same levels apply to the no-VPS PHP exit's `--mode=stream` path, just
framed around mux frames instead of IP packets: `-d` prints one line per
mux frame (`[STREAM] -> 526 bytes - stream 7 DATA`, `OPEN host:443`, `<-
... OPEN_OK`), `-dd` adds the operational lines (streams opening/closing, a
busy carrier), `-ddd` hexdumps `DATA` payloads. See
[phpbox.md](phpbox.md). On the PHP side itself the exit's own status page
keeps an `info`/`debug` log on the host; destination hosts are hidden
(`*:443`) there too unless `&sensitive=1` is set on that page.

## Why `--sensitive` exists

Logs at `-dd`/`-ddd` already cover sessions, handshakes and control
traffic in enough detail to debug most problems. `--sensitive` is a
separate, explicit opt-in on top of that, because the things it unlocks -
encryption key material, and at `-ddd` the plaintext of frames that carry
cookie jars and auth tokens - are secrets, not just verbose diagnostics.
Leave it off unless you are specifically debugging a captcha/cookie
exchange or a key-derivation mismatch, and never paste `--sensitive`
output somewhere public.

## Cookie persistence

Cookies obtained during a captcha/login solve (see
[transports.md](transports.md#captchas)) persist in `--cookie-store`
(default `./cookies-<transport>.json`) and are reused after restarts.
Under systemd with `ProtectSystem=strict`, point `--cookie-store` at a
writable directory, or the exit will solve the same captcha on every
restart.
