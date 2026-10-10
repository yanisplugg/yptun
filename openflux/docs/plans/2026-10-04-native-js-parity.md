# Native vs JS transports: parity audit (2026-10-04)

Every native transport was read next to its JS port, facet by facet (auth, wire
format, framing, reconnect, keep-alive, captcha/login, cookies, Stop). The JS
ports were made from the natives as they were around 27 September; the natives
moved on since. This is what was found, what was fixed, and what still differs.
Companion to [the audit](2026-10-04-native-to-js-audit.md) that says which
natives stay native.

**How it is checked now.** `transport/script/parity_test.go` runs the pure seams
through both implementations and compares bytes; `interop_cups_test.go` runs the
native cupsonline transport and its JS port against each other through a fake
cups.online, both ways; `shutdown_test.go` checks the engine's Stop. The natives
export the pure functions the tests need (`yandex.BuildSaveChanges`,
`yandex.ExtractBase64`, `mailru.BuildSaveChanges`, `mailru.CursorPayloads`,
`yandex.BoardsModifyObjects`, `yandex.ModifyObjectsPayloads`,
`cupsonline.SetBaseRoomURL`).

## Found and fixed

| transport | what differed | effect | gate |
|---|---|---|---|
| mailru, yandex | **saveChanges "editor activity"** stream (random 0.5-5 s) existed only in the natives | the JS side looked like an idle bot next to a native peer | byte-for-byte builder test |
| mailru | a batched server message was delivered as its **first** cursor entry only, and a whole message dropped when a peer's keep-alive was in it | lost packets | payload parser + `handleMessage` test |
| mailru, yandex | a failed keep-alive only set a state; the native one closes the socket | a half-open connection never reconnected | review |
| mailru, yandex | `cookiesApplied` closed the socket **and** scheduled a reconnect: two sessions | duplicate participant, duplicate packets | review (one reconnect at a time; stale sockets ignored) |
| boards | native moved to **modify-objects + drop-objects**; the JS port still sent `notify-position`, which the native now ignores | a JS boards client's packets never reached a current exit | byte-for-byte builder + object-parser tests |
| boards | client engine.io `2` ping every 20 s (the native dropped it: the server closes the socket on it) | board dropped every 20 s | review |
| boards | `ack` numbering was not reset per connection: after the first reconnect the wait for the subscribe answer (`431[`) could not match | stuck reconnect loop | review |
| boards | backoff never reset after a long session | slow reconnects for good | review |
| yandex, vyandex, boards | a login wall was raised as `loginRequired`, which never reaches the app's notifier; the native reports `captchaRequired` with reason `login` (the apps use the reason) | no sign-in prompt for JS transports | review |
| vyandex | a captcha/login solved before the first authorization **never started** the transport; a transient first-authorization failure left it dead | JS vyandex stayed down after a solved captcha | review |
| cupsonline | **batches were sent concurrently per room**: their pieces interleaved in the stream | packets above ~1 KB from a JS side never arrived at a native peer | interop test (this one was found by it) |
| cupsonline | one receive buffer per room; native keeps one **per sender** | two members sending at once corrupted each other's packets | unit test |
| cupsonline | a failed write did not close the socket; a hang-up mid-handshake was not a "refusal" (so no re-join) | dead channel until the read timeout | review |
| cupsonline | no exit-side room creation, no room-list report (an exit's share link had no rooms) | JS cupsonline could not be an exit started without rooms | interop test |
| oneme (both) | a **receiver** whose call socket closed went into `connectCallerLoop` and started placing calls to an empty callee every second | MAX API spam from a JS exit | review |
| engine | `Stop()` never called the script's `close()` and left its sockets open | a participant stayed in the document after Stop; goroutines leaked | `shutdown_test.go` |
| engine | `Stop()` waited for ever on JS that does not return | hung disconnect | `shutdown_test.go` |
| engine | `ws.send` had no write deadline (native: 15-20 s): a half-open socket blocked the whole event loop | frozen transport | review |
| engine | scripts could not know their role | cupsonline cannot tell exit from client | the core now passes `params.exit` to every script transport |

All seven bundled scripts are now version 1.1.0.

## Still different (known, deliberate or open)

- **oneme default.** The native transport moves bytes by *ICE injection* (the
  PeerConnection it builds is inert); its JS counterpart is
  `oneme-iceinject`, not `oneme-webrtc`. A profile that swaps native `oneme` for
  JS must pick `oneme-iceinject`. Native `IsConnected()` is always true; the JS
  ports report the real state.
- **Not interop-tested:** yandex, vyandex, boards, mailru, oneme talk to
  hard-coded hosts with no test hook; only the pure seams are byte-compared and
  the rest is reviewed. Next step: a base-URL override like cupsonline's and a
  fake for each.
- **vyandex:** the native relay is 2000 goroutines and a 1,000,000-packet queue;
  the JS port is 64 concurrent POSTs through `concurrency.pool` (documented in
  the script). The native `SlimVolgaConfig` (phones) has no JS counterpart.
  A failed *first* authorization makes native `Start()` return an error; the JS
  port retries (after a captcha/login wall it waits for the applied cookies).
- **yandex native `--yandex-cookies-file`** (a cookies.txt loader) has no JS
  equivalent; apps use the cookie hand-over instead.
- **JSON key order.** Go marshals maps with sorted keys, JS in insertion order;
  vyandex's relay body and boards' subscribe differ in key order from the native
  (semantically equal; the saveChanges and modify-objects builders are written
  in sorted order and byte-compared).
- **Suspected in both:** the saveChanges cursor payload starts with a fixed length
  prefix `0x06` followed by a user id of up to 10 characters (UTF-16). The recorded
  dump has a 3-character short id for 6 bytes, so the prefix looks like a byte
  length; with a longer id native and JS send a prefix that does not match what
  follows. Both are identical on purpose (parity); whether the server cares needs
  a live check.
- **Both natives and JS** drop a whole Yandex frame that contains a keep-alive
  marker (only mailru was fixed natively to deliver every entry of a batch).

## Signing

The scripts were signed with the official key after this work (`js/build.sh
<key>` rebuilds the bundles from `src/`, signs, and removes the
`PENDING_SIGNATURE` marker; `shipped_signatures_test.go` verifies every shipped
signature under `OfficialKeyHex`). The Android app keeps its own copies in
`androidApp/src/main/assets/scripts`; they have to be copied after each signing
(the app replaces an installed bundled script when the shipped one is newer).
