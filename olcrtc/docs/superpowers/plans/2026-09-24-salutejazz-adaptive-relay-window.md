# A relay window sized for the leg — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each SaluteJazz relay window sizes itself to its leg — `clamp(rate × (minRTT + TargetQueue), MinWindow, Window)` — so a pong never waits behind seconds of data on a slow Sber leg, while a fast leg keeps today's 192 KiB.

**Architecture:** The sizing lives in `internal/relaywin` behind two new `Timing` fields and is off when `TargetQueue` is zero; `salutejazz` turns it on. The sender alone decides, so nothing on the wire changes.

**Tech Stack:** Go 1.26 (`/usr/local/go/bin`), `go test -race`, golangci-lint.

**Spec:** `docs/superpowers/specs/2026-09-24-salutejazz-adaptive-relay-window-design.md`

## Global Constraints

- With `TargetQueue` zero, `relaywin` behaves exactly as before: every existing test in `internal/relaywin` and `internal/engine/salutejazz` passes unmodified.
- `ApplyEcho(key, counter)` keeps its signature and gives the sizing no sample; `ApplyEchoAt(key, counter, now)` is the timed form.
- SaluteJazz: `TargetQueue` 1.5 s, `MinWindow` 16 KiB, cap `Window` 192 KiB (unchanged); marks every eighth of the current window, never under 2 KiB.
- No wire change; Jitsi's own copy (`internal/engine/jitsi/relaywindow.go`) is not touched.
- DATA is a shared prod box: at most two `-race` test binaries at once, MemAvailable above 3 GiB.
- No push while an app release gate runs (both drive the same live rooms); one gate run at a time.

---

### Task 1: `relaywin` sizes a window to its leg

**Files:** `internal/relaywin/relaywin.go`, `internal/relaywin/relaywin_test.go` (new tests only), a new `internal/relaywin/size_test.go` if the tests read better apart.

**Produces:** `Timing.TargetQueue time.Duration`, `Timing.MinWindow uint64`, `(*Windows).ApplyEchoAt(key string, counter uint64, now time.Time) (moved, turnedOn bool)`, `(*Windows).Size(key string) uint64`.

- [ ] **Tests first** (fake clock throughout):
  - a window whose echoes arrive at 26 kB/s while a sender is held shrinks, within a few rate intervals, to `26 kB/s × (minRTT + TargetQueue)` ± 10 %, and never under `MinWindow`;
  - the same window grows back to `Window` when echoes speed up to 1 MB/s;
  - echoes while no sender is held leave the size alone (an application-limited sender says nothing about the leg);
  - the mark interval is an eighth of the current size, never under 2 KiB;
  - `Room`, `Over` and `Sent` hold, overflow and mark against the destination's own size;
  - `Reset` forgets the size, the shortest round trip and the rate interval;
  - `ApplyEcho` (no clock) moves the window and never resizes it;
  - with `TargetQueue` zero, 26 kB/s echoes leave every window at `Window`.
- [ ] Run `go test -race ./internal/relaywin/` — the new tests fail.
- [ ] Implement: per-state `size`, `markEvery`, `marks []mark` (counter and time, bounded like the engine's 64), `minRTT`, `rateSince`/`rateFrom`, `heldInInterval`; `withDefaults` fills `MinWindow` (16 KiB) only when `TargetQueue > 0`; the rate interval closes when at least a second has passed and at least one echo arrived; the new size is `clamp(rate × (minRTT + TargetQueue), MinWindow, Window)` only if a sender was held during the interval.
- [ ] Run `go test -race ./internal/relaywin/` — all pass, old and new.
- [ ] Commit `feat(relaywin): a window sized for its leg, when the carrier asks (#49)`.

### Task 2: SaluteJazz turns it on

**Files:** `internal/engine/salutejazz/window.go`, `internal/engine/salutejazz/window_budget_test.go`, `internal/engine/salutejazz/tunnel_test.go`, `export_test.go` as needed.

- [ ] **Tests first:**
  - budget: on the 26 kB/s leg the gate measured, two windows of `max(MinWindow, 26 kB/s × (0.5 s + TargetQueue))` and a round trip fit the 15 s pong timeout with a second to spare; the existing 34 kB/s test on the cap stays;
  - tunnel: the whole tunnel over the fake SFU on 26 kB/s legs with six parallel pulls: no pong missed at the tunnel's real liveness (10 s probes, 15 s timeout), and once the window has settled every pong within `2 × TargetQueue` + the leg's round trip + a second; the pulls keep over a quarter of the leg. The existing 128 kB/s slow-leg test passes unchanged.
- [ ] Run them — the tunnel test fails on the fixed window.
- [ ] Implement: `defaultRelayTiming` sets `TargetQueue` and `MinWindow`; `handleWindowFrame` calls `ApplyEchoAt(from, counter, now)`; the 5 s debug report adds the window's size.
- [ ] Run `go test -race ./internal/engine/salutejazz/ ./internal/relaywin/`, then the whole module without `-race` (`go test ./...`), `go vet ./...`, `golangci-lint run` (its cache in this worktree's own directory).
- [ ] Commit `feat(engine/salutejazz): the relay window follows the leg (#49)`.

### Task 3: Hand over

- [ ] Wait until no app release gate is running; push `fix/salutejazz-adaptive-window` to `proofkit` (the remote), one gate at a time.
- [ ] Open the PR against `proofkit` with the spec's test plan; engine CI and the gate's salutejazz S2/S3 read against 7b78fd4a's (compare cell durations before believing a red).
- [ ] Report; the server build, agent pin and Force Update are a separate step the user confirms.
