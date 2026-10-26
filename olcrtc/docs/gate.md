<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

[RU](gate.ru.md) / **EN**

</div>


# Release gate

`internal/gate` runs the engine's client against a real relay and a real server with the load shapes that broke the tunnel, judges each cell against thresholds and writes `gate-report.json`. It is a `go test` suite: `TestGate`, switched on by `-olcrtc.gate`. Without the flag `go test ./internal/gate` runs only the unit tests.

Every planned cell ends `pass` or `fail`. A cell that did not run (its server or client never came up, a `-run` filter left it out, the deadline came first) fails with the reason. Nothing is skipped. A failure of a cell on the known list is reported and does not fail the gate: see [Known failures](#known-failures).

## Run it locally

The `cli` flavour, default build:

```bash
go test -count=1 -timeout 25m ./internal/gate -run '^TestGate$' -v \
  -olcrtc.gate -olcrtc.gate-providers=jitsi -olcrtc.gate-dir=/tmp/gate-cli
```

The `mobile` flavour, the lean build the phones ship:

```bash
go test -count=1 -tags olcrtc_lean -timeout 45m ./internal/gate -run '^TestGate$' -v \
  -olcrtc.gate -olcrtc.gate-providers=jitsi -olcrtc.gate-dir=/tmp/gate-mobile
```

- `-olcrtc.gate-dry` prints the plan, one cell id per line, and runs nothing. On the local target it needs no room and no token; the link target still needs the link, whose pair it plans.
- Jitsi and SaluteJazz need no secret. Telemost, VK Calls and MTS Link need pre-made rooms; WB Stream, walked only when named, needs rooms and an account token: see [Rooms and secrets](#rooms-and-secrets). Put them in the environment, for example from a file outside the repository (`set -a; . ~/gate.env; set +a`), not on the command line.
- The local target builds `cmd/olcrtc` with `-tags olcrtc_testhooks`, so `go` must be on `PATH`.
- The run ends 90 s before the `go test` deadline, so the report is written even when time runs out; cells it did not reach fail as not run. Give `-timeout` well above the run: the default 10 m cuts most runs short. A `-timeout` that leaves no more than 90 s is refused.
- Against a fleet node: `-olcrtc.gate-target=link` with the `olcrtc://` link in `OLCRTC_GATE_LINK`. The server is the node's; the load goes to public URLs (`-olcrtc.gate-link-small`, `-olcrtc.gate-link-big`, `-olcrtc.gate-link-sink`), and the run writes no logs.

## Flags

| Flag | Default | Meaning |
|---|---|---|
| `-olcrtc.gate` | off | run the gate |
| `-olcrtc.gate-target` | `local` | `local`: a child server per pair; `link`: the server behind an `olcrtc://` link |
| `-olcrtc.gate-dir` | `gate-artifacts` | where the report and the scrubbed logs go; a relative path is taken from the module root |
| `-olcrtc.gate-providers` | `jitsi,mtslink,salutejazz,vkcalls` | providers of the local target, run one after another. `wbstream`, `telemost` and `max` are carried and left out of the default, and each runs only when named: WB Stream closed its beta to its own staff on 2026-10-10 and answers every join 401, Telemost's relay cuts every published track to a trickle (#58), and the MAX account the pair signs in with is locked (2026-10-19) |
| `-olcrtc.gate-transports` | `datachannel,seichannel,vp8channel` | transports of the local target, see [Pairs](#pairs); `videochannel` runs only when named |
| `-olcrtc.gate-clients` | the build's own | `cli` in a default build, `mobile` in an `olcrtc_lean` one, one flavour per process |
| `-olcrtc.gate-telemost-rooms` | empty | Telemost pool; else `OLCRTC_GATE_TELEMOST_ROOMS` |
| `-olcrtc.gate-vkcalls-rooms` | empty | VK Calls pool, join links; else `OLCRTC_GATE_VKCALLS_ROOMS` |
| `-olcrtc.gate-mtslink-rooms` | empty | MTS Link pool, event join links; else `OLCRTC_GATE_MTSLINK_ROOMS` |
| `-olcrtc.gate-max-rooms` | empty | max pool, join links (max.ru/joincall/&#x200b;*); else `OLCRTC_GATE_MAX_ROOMS` |
| `-olcrtc.gate-wbstream-rooms` | empty | WB Stream pool; else `OLCRTC_GATE_WBSTREAM_ROOMS` |
| `-olcrtc.gate-jitsi-hosts` | empty | Jitsi hosts used instead of the instance list; else `OLCRTC_GATE_JITSI_HOSTS` |
| `-olcrtc.gate-jitsi-instances` | `docs/jitsi.instances.yaml` | the Jitsi instance list; a relative path is taken from the module root |
| `-olcrtc.gate-run-number` | `GITHUB_RUN_NUMBER`, else 0 | the number that picks a pool room |
| `-olcrtc.gate-big-mb` | `10` | size of the big pull, MiB |
| `-olcrtc.gate-link` | empty | the link of the link target; else `OLCRTC_GATE_LINK` |
| `-olcrtc.gate-link-small` | `https://proofkit.org/gate/kb` | 1 KB resource the node reaches |
| `-olcrtc.gate-link-big` | `https://proofkit.org/gate/10mb.bin` | resource of `-olcrtc.gate-big-mb` MiB the node reaches |
| `-olcrtc.gate-link-sink` | `https://speed.cloudflare.com/__up` | upload sink the node reaches |
| `-olcrtc.gate-dry` | off | print the plan and run nothing |
| `-olcrtc.gate-dtls-profile` | empty | the engine's `dtls.profile` every local server and the client handshake with (the link target: the client alone, the node keeps its own); empty or `off` is the stock Pion handshake, an unknown name fails the run before anything starts. The report names a profile in `dtls_profile` and the summary heading, and cell ids stay as they are, so a profile run goes to a report of its own |

The environment:

| Variable | Meaning |
|---|---|
| `OLCRTC_GATE_TELEMOST_ROOMS` | Telemost pool, when its flag is empty |
| `OLCRTC_GATE_VKCALLS_ROOMS` | VK Calls pool, when its flag is empty |
| `OLCRTC_GATE_MTSLINK_ROOMS` | MTS Link pool, when its flag is empty |
| `OLCRTC_GATE_MAX_ROOMS` | max pool, when its flag is empty |
| `OLCRTC_GATE_MAX_KEYS` | the max pair's two calls session keys, comma-separated, the server's first; the two engine sides are two identities, or the SFU's same-uid dedup tears the pair apart |
| `OLCRTC_GATE_WBSTREAM_ROOMS` | WB Stream pool, when its flag is empty |
| `OLCRTC_GATE_WBSTREAM_TOKEN` | WB Stream account token of the local server; it has no flag |
| `OLCRTC_GATE_JITSI_HOSTS` | Jitsi hosts, when their flag is empty |
| `OLCRTC_GATE_LINK` | the link, when its flag is empty |
| `OLCRTC_GATE_ENGINE_COMMIT`, `OLCRTC_GATE_ENGINE_REF`, `OLCRTC_GATE_APP_VERSION` | what the report says it tested; the commit defaults to the repository's `HEAD` |
| `GITHUB_RUN_NUMBER` | the run number, when its flag is not given |
| `RUNNER_OS`, `ImageOS` | the report's `runner`: `RUNNER_OS/ImageOS` on a GitHub runner, else this platform's `GOOS/GOARCH` |

A flag shows in the process list and the shell history, so rooms, the token and the link go in the environment.

## Rooms and secrets

- **Jitsi.** Each pair gets a fresh room, `https://<host>/gate-<12 hex>`, on the first host that answers one HTTPS request within 5 s. The hosts come from the instance list, or from the override when it names any. An override entry is a bare host or a URL; only its host is used.
- **SaluteJazz.** Each pair gets a fresh room, `<code>:<password>`, from Sber's anonymous create-meeting call, the one the engine's own `salutejazz` auth provider makes. It takes no account and no token, and nothing gives a room back: Sber has no delete, so a run leaves its rooms behind. A create that fails is a server that did not come up.
- **Telemost, VK Calls, MTS Link and WB Stream.** The gate creates none of these providers' rooms, so each has a pool of pre-made ones. An MTS Link entry is the join link of an endless meeting («постоянная встреча»), which a guest enters with no organizer inside. Entries are separated by commas or line breaks, trimmed, and blank ones dropped. A run takes entry `run_number mod len(pool)`, so runs in sequence take turns.
- **WB Stream token.** WB refuses a guest as the first participant of an idle room (`403 guests cannot create rooms`), so the local server signs in with the account token from `OLCRTC_GATE_WBSTREAM_TOKEN`. The client stays a guest, as the app is. Without the token every wbstream cell fails with a reason that names the variable.
- **Per pair.** A fresh 64-hex key and a fresh channel id `gate-<12 hex>`, so two runs in one pool room never read each other's frames.
- **Kept private.** The server's YAML (room, key, token) and its raw log stay in a temporary directory. `-olcrtc.gate-dir` gets scrubbed copies only.

Pool entries:

| Pool | Entry | The server joins |
|---|---|---|
| Telemost | `<id>` | `https://telemost.yandex.ru/j/<id>` |
| Telemost | `https://telemost.yandex.ru/j/<id>` | the URL as given |
| Telemost | an `http://` URL, or a URL without a scheme | the same URL with `https://` |
| VK Calls | `https://vk.ru/call/join/<id>` | the link as given |
| MTS Link | `https://my.mts-link.ru/j/<owner>/<event>` | the link as given |
| WB Stream | `<id>` | `<id>` |
| WB Stream | `https://stream.wb.ru/room/<id>`, with or without the scheme | `<id>`, the last path segment; a query or fragment is dropped |

The WB provider escapes whatever it gets into one path segment, so a whole URL would join a room that does not exist: the gate cuts it to the id.

Secrets of the engine repository (Settings > Secrets and variables > Actions) for the `gate-local` job:

| Secret | Required | Reaches `go test` as | Value |
|---|---|---|---|
| `GATE_TELEMOST_ROOMS` | yes | `OLCRTC_GATE_TELEMOST_ROOMS` | Telemost pool: ids or room URLs |
| `GATE_MTSLINK_ROOMS` | yes | `OLCRTC_GATE_MTSLINK_ROOMS` | MTS Link pool: event join links (https://my.mts-link.ru/j/<owner>/<event>) of endless meetings. In the default walk since 2026-10-11, after the engine's live validation |
| `GATE_WBSTREAM_ROOMS` | no | `OLCRTC_GATE_WBSTREAM_ROOMS` | WB Stream pool: ids or room URLs. Read when `wbstream` is named in `-olcrtc.gate-providers` |
| `GATE_WBSTREAM_TOKEN` | no | `OLCRTC_GATE_WBSTREAM_TOKEN` | WB Stream account access token, read with the pool |
| `GATE_JITSI_HOSTS` | no | `OLCRTC_GATE_JITSI_HOSTS` | Jitsi hosts; empty means the instance list |
| `GATE_VKCALLS_ROOMS` | no | `OLCRTC_GATE_VKCALLS_ROOMS` | VK Calls pool: room URLs (https://vk.ru/call/join/<id>). Read when the `vkcalls` provider is named in `-olcrtc.gate-providers`; it is in the default walk since 2026-09-28, after the engine's live validation (19 Mbit/s both ways, S0-S7) |
| `GATE_MAX_ROOMS` | yes | `OLCRTC_GATE_MAX_ROOMS` | max pool: join links (max.ru/joincall/*), created by the account browser. Read when the `max` provider is named in `-olcrtc.gate-providers`; in the default walk since 2026-10-18, after the live carrier measurement |
| `GATE_MAX_KEYS` | yes | `OLCRTC_GATE_MAX_KEYS` | the max pair's two calls session keys (the server's first). Live 20h+; refreshed the way the other account secrets are |

The job's first step masks every entry, the room id an entry's URL ends in, each Jitsi host as given and bare, and the token; then it names each required secret that is not set. The cells that need a missing secret fail in the report with the same reason, so the job fails, while the other providers still run. The secrets reach `go test` through step `env` only, never argv or script text. Set a secret from standard input (`gh secret set GATE_WBSTREAM_TOKEN`), never on the command line, and never commit a room, a link, a key or a token.

## Pairs

The local target crosses the providers with the transports in the order given and keeps what each provider carries:

| Provider | Transports |
|---|---|
| `jitsi` | `datachannel`, `videochannel`, `seichannel`, `vp8channel` |
| `telemost` | `vp8channel`, `videochannel` (Telemost drops SCTP, and seichannel fails there by design) |
| `wbstream` | `vp8channel`, `videochannel`, `seichannel` (WB guests cannot publish data) |
| `vkcalls` | `vp8channel`, `videochannel` (the SFU bridges no data between guests) |
| `mtslink` | `vp8channel` (the SFU relays no data between guests and forwards VP9 and H264, never VP8: `vp8channel` dresses its frames as VP9 for it, the other video transports publish what it does not take) |
| `max` | `datachannel` (the P2P pair's datagram channel is the measured lane: 2.7-5.7 Mbit/s up, 777 kbit/s down through the room's SFU relay; the counterparty answers no sendable media track, so no video transport) |
| `salutejazz` | `datachannel` (Sber admits a guest to the room's data channels only, never to a media track) |

Left at its default, `-olcrtc.gate-transports` runs `datachannel`, `seichannel` and `vp8channel`, those of them some provider carries. `videochannel` runs only when named: it sends one 256-byte fragment a frame at 30 fps, about 7.5 KiB/s, so S0's 10 MiB pull alone would outlast the cell's 5 min. A list given on the command line is taken as it is: an unknown name, a transport this build does not link (the lean build has no `videochannel`) or no provider carries, and a provider left with none are plan errors. The link target has one pair, the link's.

## What a cell is

`platform/provider/transport/client/scenario`, for example `engine-linux/jitsi/datachannel/mobile/S2`. A cell id never names a room.

The client flavours, one per process:

- `cli`: `pkg/olcrtc/client`, what `cmd/olcrtc` runs in mode `cnc`. Default build.
- `mobile`: `mobile.Runtime` the way the apps run it, VP8 at the app's 60 fps and batch 64, the process under `mobile.SetMemoryLimit` of 40 MiB and GC percent 10. Build with `-tags olcrtc_lean`.

`-olcrtc.gate-clients` naming the other build's flavour is a plan error, so the phone's memory settings never weigh a cli cell.

| ID | Name | What it does | Pass | Runs on |
|---|---|---|---|---|
| S0 | connect | start the client, one big pull, one 5 MiB push | `handshake_ms` within the connect budget (25 s), both transfers complete | every pair, both flavours |
| S1 | idle burst | 24 concurrent connects fetching 1 KB, then 24 one after another | all succeed, p95 within `connect_p95_ms` | load pairs, `mobile` |
| S2 | download saturation | 6 parallel big pulls, a 1 KB connect on top every 5 s (olcbox#23) | every pull, `throughput_down_bps`, every connect on top, their p95 within `connect_p95_ms` | load pairs, `mobile` |
| S3 | upload saturation | 4 parallel 5 MiB pushes, connects on top as in S2 (olcbox#15) | as S2, with `throughput_up_bps` | load pairs, `mobile` |
| S4 | quiet after load | 60 s idle after S3 (olcbox#25) | no missed pong, no reconnect, the conference alive, then a 1 KB pull | load pairs, `mobile` |
| S5 | resolver burst | 64 concurrent DNS queries to 8.8.8.8 through a SOCKS UDP associate, twice | `resolver_answered` of the 64 within 5 s, both times | load pairs, `mobile` |
| S6 | late server bridge | a fresh server whose Jitsi bridge opens 3 s late, then one 8 s late (olcbox#22) | the client ready no sooner than the delay, the proof the bridge was late, and within the delay plus the handshake budget | local target, `jitsi/datachannel`, both flavours |
| S7 | phone memory | peak heap and RSS from S2's start to S4's end over the baseline read before the client started; goroutines before S1 and at S4's end | `heap_growth_bytes`, `rss_growth_bytes`, `goroutine_growth` | load pairs, `mobile` |

Load pairs are `jitsi/datachannel`, `mtslink/vp8channel`, `salutejazz/datachannel` and `vkcalls/vp8channel` (and `telemost/vp8channel`, `max/datachannel` and `wbstream/vp8channel` when they are named) on the local target, the link's pair on the link target. The big pull is `-olcrtc.gate-big-mb` MiB. The scenarios of a client run in order on one tunnel. S6 delays the bridge through `OLCRTC_TEST_BRIDGE_DELAY`, which only a server built with `olcrtc_testhooks` reads; a release build has no such hook. Such a server also reads `OLCRTC_TEST_PROVIDER_DROP_AFTER`, a Go duration: that long after it joins, it drops its provider once and rebuilds it, as a relay that cuts its connection makes it do (olcrtc#19). No scenario sets it; the server takes it from the environment of the run. The local target starts every server with `OLCRTC_TEST_ALLOW_PRIVATE_TARGETS=1` as well: the origin the client pulls from is on loopback, which a server's egress policy refuses, and only such a server lifts the policy for it.

A cell has 5 min, S2 and S3 have 10. A server that did not come up gets one more try 30 s later (a missing token or pool room gets none), then its cells fail with the reason and the server's last log line, usually the provider's answer; a client that did not come up fails its cells too. Cells are not retried: a flake under load is a finding.

A failed cell of the local target also names every session the relay ended during it, where the server's engine logs such an end (WB Stream's LiveKit engine does): the reason the relay gave, how far into the cell and how long after the server joined, for example `the relay ended the server's session (PARTICIPANT_REMOVED) 26s into the cell, 39s after it joined`. It judges nothing, and a cell that passed gets no such line. The runner notes where the server's log stands as each cell begins and reads only what the log gained after that, a line at a time; a line longer than 64 KiB is read by its first 64 KiB. So a cell that fails late in a pair does not copy a 20 MB log into the process S7 weighs.

## Thresholds

The bounds a verdict judges by are in `internal/gate/thresholds.go`: `Local` for the local target, `Link` for a fleet node, the connect budget of S0 and the handshake budget of S6. S0's `handshake_ms` runs from the client's start to a working tunnel, so its bound is the tightest app ready wait, Android's 25 s, not the engine's 15 s reply deadline, which starts only at the first hello. S6 adds the handshake budget (15 s) to how late its server's bridge opens, 3 s and then 8 s, a delay the scenario sets, and fails a client ready sooner than that delay: no handshake completes before the bridge opens, so that bridge was not late and the cell tested nothing. A cell of a report carries the thresholds its pair was judged by, all of them, whatever its scenario: its target's, with its provider's own throughput floors where those are lower (`Thresholds.For`). SaluteJazz has such floors, 1.4 Mbit/s down and 1.6 Mbit/s up, by the same rule as a target's: half of what was measured, and every byte of it crosses Sber's TURN relay, which carried 2.79 Mbit/s down and 3.23 up from a datacenter. A provider's floor never raises a target's own:

| Threshold | Bounds |
|---|---|
| `connect_p95_ms` | p95 of a connect: S1's burst, the connects on top in S2 and S3 |
| `throughput_down_bps` | S2's aggregate download rate, a floor |
| `throughput_up_bps` | S3's aggregate upload rate, a floor |
| `heap_growth_bytes` | S7: how far the live heap rose at its peak over the baseline |
| `rss_growth_bytes` | S7: how far the RSS rose at its peak over the baseline |
| `goroutine_growth` | S7: how many more goroutines run at S4's end than before S1 |
| `resolver_answered` | S5: how many of the 64 queries each burst must get answered |

The connect and handshake budgets are not among them, so an S0 or S6 cell does not show the bound it was judged by. The other rules take no number: every transfer and connect must succeed, S4 allows no missed pong and no reconnect. A failure names the metric and what it measured, for example `connect_ok 23 of connect_total 24`.

S7 weighs the test process, which holds the harness too (the test binary, the origin, the load, what earlier pairs left), so it judges what the client adds. Before each client starts, the runner collects the garbage, hands the freed memory back to the OS and reads a baseline; an S7 cell records it as `heap_baseline_bytes` and `rss_baseline_bytes` next to `heap_peak_bytes` and `rss_peak_bytes`, and the verdict bounds the difference. The two bounds are the spec's for a process that runs the client alone (16 MiB of live heap, 45 MiB RSS) less what such a process holds before its client starts.

The sampler also watches for jumps. When the heap or the RSS rises by more than 6 MiB from one sample to the next, it writes a heap profile of the test process into the pair's directory, `<client>-heap-<t_ms>ms.pb.gz` with `t_ms` on the clock of the samples, and logs the file. Go's heap profile is also its allocs profile: `go tool pprof` shows what was in use at the last GC, `-sample_index=alloc_space` what was allocated since the process began, and `-base` with an earlier profile what was allocated in between. Every jump gets a profile, however many came before, because the one behind S7's peak comes late in a client's run.

A profile is written inside the process S7 weighs, and its garbage stays in the heap in use until the next GC, so a later sample can carry it. The line that names a profile gives what the process allocated while it was written and how far the heap in use moved, and an S7 cell records `heap_profiles_in_window`, the profiles written between S2's start and S4's end. A peak near the bound with a profile beside it may be partly the tool's.

## Known failures

`internal/gate/known.go` lists the cells an open engine issue fails on every run, one entry per line: a cell id pattern, in which `*` stands for exactly one whole segment (`engine-linux/jitsi/seichannel/*/S0` is that cell of both flavours), the issue's URL and a few words on what fails. Without the list a red gate says nothing about new regressions, because those cells fail every run.

- A known cell still runs and is reported. A failed one stays `fail` with its reasons; the report adds `known`, the issue's URL, to the cell and counts it in `failed` and in `failed_known`. `render` shows it as `fail (known: #9)` and says under the table how many known failures there are.
- A known failure fails neither its subtest nor `TestGate`, which logs it with its issue; any other failed cell fails the gate. The app's verdict does the same: it fails on `failed` minus `failed_known`.
- A cell that did not run is never known, whatever its id: one still planned when the report is built, or one whose server or client never came up. What failed there is the gate's world (a relay, a secret, a start), not the bug the issue tracks.
- Every entry needs an open issue. Once the issue is closed and its cells pass, drop the entry: `TestGate` logs `known failure passed: <cell> (<issue>)` for each known cell that passed, and `render` shows it as `pass (known: #9)`.
- A WB Stream cell may be on the list for an engine bug. Without the WB token, or with a room that will not open, the server never comes up and its cells never run, so configuration stays a blocking failure however the list reads.

The unit tests hold the list to these rules: each pattern matches a cell of the plan and names its provider, each entry has an issue URL and a reason, and no cell is matched by two entries.

## Artifacts

```text
<gate-dir>/
  gate-report.json
  jitsi-datachannel/           a directory per pair
    srv.log                    the pair's server
    mobile-start.log           the client's start
    mobile-S2.log              a log per cell
    mobile-samples.csv         after S7: t_ms,heap_inuse,rss,goroutines
    mobile-heap-41000ms.pb.gz  a heap profile where memory jumped
    delay-3s/srv.log           S6's late servers
    delay-8s/srv.log
```

Every log is scrubbed before it is written: the run's rooms, room ids, each half of a SaluteJazz room (the code and the password), channel ids, override hosts (as given, without their port and lowercased, the forms a resolver's error and an XMPP JID carry) and WB token read `<room>`, keys read `<key>`. A secret is caught base64-encoded too: a server's debug log quotes XMPP stanza ids, base64 of a JID with the Jitsi host in it. The failures in the report are scrubbed the same way. The link target writes no logs: the fleet's rooms are not the run's to show.

The report is written after the plan, again after every cell and once more by `TestMain` when the test binary exits; each write replaces the file whole (a temporary file renamed over it). A cell not finished yet reads as failed, not run, so a binary that dies mid-run, of a panic outside a scenario or killed by `-timeout`, leaves every cell it finished, and the cell in flight and the ones after it fail as not run.

## Reading a report

```bash
go run ./cmd/gate-report render /tmp/gate-cli/gate-report.json
go run ./cmd/gate-report compare -severity fail previous.json current.json
```

`render` prints the Markdown table: a row per cell with its verdict, key metrics and time. `compare` pairs the cells of two reports by id and prints the deltas: throughput down or peak memory up by more than 25 %, or a pass that became a failure, is a regression. It exits 2 on a regression with `-severity fail`, 0 with `warn` (the default), 1 on a report it cannot read and 64 on a wrong command line. A cell in only one of the reports is not compared.

## CI

The `Test` job runs the unit tests of three builds: the default one, `olcrtc_lean` and `olcrtc_testhooks`, the one the local target's server is built with, so the hook S6 relies on is tested on every event, a fork's pull request included. Two more jobs in `.github/workflows/ci.yml` run the gate:

- `gate-plan` runs the dry run of both builds, needs no secret and so runs for a pull request from a fork too, and puts both plans in the job summary. It fails when a build plans no cell or a cell of the other flavour.
- `gate-local` needs `gate-plan` and runs the gate on the local target, one job per provider: inside it the `cli` flavour, then `mobile` under `-tags olcrtc_lean`, then the `cli` cells once more with `-olcrtc.gate-dtls-profile=chrome-linux-138-compat-v1` (the profile ships off, so that pass shows its rows without failing). The jobs run in parallel and a failed one is re-run on its own (Re-run failed jobs), so one carrier's bad day no longer holds the whole gate. Each job renders its reports into its summary and uploads its `gate-<provider>` artifact: the reports, the scrubbed logs, the samples and the heap profiles. One job at a time per provider (`concurrency: gate-rooms-<provider>`, queued, never cancelled), because two runs of one provider share its pool rooms; jobs of different providers share nothing. A pull request from a fork gets no secrets, so the job does not run for it; the push that merges it runs the gate.
