# YPtun VPN — Chrome extension

VLESS and AmneziaWG inside the browser. Chrome has no raw sockets, so an extension can never speak
those protocols itself — it can only point `chrome.proxy` at a proxy. This extension therefore ships
in two halves:

| Part | What it does |
| --- | --- |
| `chrome-extension/` (this folder) | popup UI, server list, `chrome.proxy` control |
| `cores/cmd/yptunhost` (Go binary) | runs xray (VLESS) or AmneziaWG locally, exposes ONE loopback SOCKS5 port |

Only the browser goes through the tunnel — the rest of the system is untouched (that's the point;
for system-wide use, run the YPtun desktop app).

## Install

1. Build the native host (needs the Go toolchain):

   ```
   cd cores
   go build -o yptunhost.exe ./cmd/yptunhost      # Linux/macOS: -o yptunhost
   ```

2. Load the extension: `chrome://extensions` → *Developer mode* → *Load unpacked* →
   pick `chrome-extension/`.

3. Open the popup. It shows the exact registration command with its own extension ID:

   ```
   yptunhost.exe --install <extension-id>
   ```

   Run it once (writes `org.yptun.host.json` next to the binary and registers it for
   Chrome/Chromium/Edge/Yandex). Reopen the popup — the warning is gone.

`yptunhost --uninstall` removes the registration.

## Use

Add a server (`+`): paste a `vless://…` link, or an AmneziaWG `[Interface] … [Peer] …` config.
Pick it in the list, hit the power button. The badge turns green and the status line shows the
local SOCKS5 port the browser is using.

**Bypass list** (Settings) — hosts that skip the tunnel, one per line; `localhost`, `127.0.0.1`,
`[::1]` and `<local>` are always bypassed.

**Language** — Auto (browser UI language) / Русский / English.

## How it holds together

The tunnel lives in the native host process, which Chrome starts and owns. When the extension's
service worker is suspended the port closes and the host exits, so the extension pings it every 20 s
(plus a 30 s alarm) to stay resident, and clears the proxy setting the moment the port drops — the
browser is never left proxying into a dead port.
