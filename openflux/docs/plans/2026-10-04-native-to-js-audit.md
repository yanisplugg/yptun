# Native transports -> JS: audit (2026-10-04)

Goal: every transport a user can pick is a versioned, updatable JS package; the natives are
retired **only where no functionality is lost**. Backward compatibility wins every tie
(old nodes, `openflux://` links, profiles, iOS).

## Where each native stands

| native | size | JS port | automated parity check | verdict |
|---|---|---|---|---|
| `yandex` (Docs) | 955 lines + captcha 365 | `js/yandex.js` | none (live only, `scripttest`) | port exists; needs a parity gate before retiring |
| `vyandex` (Volga) | 1478 | `js/vyandex.js` | none | same |
| `boards` | 1100 | `js/boards.js` | none | same |
| `mailru` | 652 | `js/mailru.js` ("line-for-line", header says so) | none | same |
| `cupsonline` | 1579 | `js/cupsonline.js` | native has `fakecups_test.go`; the JS port is not run against it | cheapest first: reuse the fake server |
| `oneme` (MAX) | ~1000 (6 files) | `oneme-webrtc.js`, `oneme-iceinject.js` | none | port exists, two variants; decide which is the default |
| `direct` (raw TCP) | 496 | **none** | - | keep native: needs a raw TCP/listen host API |
| `phpbox` (stream mux, PHP hosting) | ~900 | **none** | - | keep native: not a carrier, a stream protocol with its own panel |

## Hard constraints found

1. **iOS has no JS engine.** `mobile/ios` links `cupsonline`, `mailru`, `oneme`, `yandex` natively and does
   not import `transport/script`; the Network Extension has a 50 MB cap and `debug.SetMemoryLimit(24 MB)`
   (`mobile/ios/packet.go`). Retiring a native removes that transport from iOS. **Natives stay for iOS.**
   Whether goja fits in 24 MB is unmeasured; measure before ever promising it.
2. **Wire compatibility with deployed nodes.** A node installed by the wizard runs a native transport; a
   client on the JS port must speak the same wire. The ports are meant to, but nothing proves it.
3. **Links and profiles.** `openflux://` links and saved profiles say `type=vyandex` etc. They must keep
   resolving: `type=<native>` maps to the bundled JS package of the same id where the platform has the
   engine, and to the native elsewhere.

## Plan that follows from this

1. **Parity gate (blocking).** For each pair, run the native and the JS port against the same fake server and
   assert identical frames both ways (cupsonline first: the fake already exists), then a cross run
   native-client <-> JS-exit. A port is "equal" only when that passes.
2. **Bundled packages.** Ship the ports as signed `.flux` (id, version, wire=1, update URL) so they update like
   any third-party transport. Their wire generation is 1 = what the natives speak today.
3. **Selection.** Where the engine exists (desktop core, Android) the JS package is used; iOS and any build
   without the engine keep the native. One `transportFactory` decision, in the core.
4. **Retire** a native only after 1-3 hold for it, and never on iOS. `direct` and `phpbox` are not retired.
