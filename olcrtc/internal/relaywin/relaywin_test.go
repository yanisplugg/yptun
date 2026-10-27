package relaywin

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// ai-generated: the whole file (the window core's tests, olcrtc#49). The cases
// are Jitsi's relaywindow_test (olcrtc#15) without the bridge, on a fake
// clock, plus Arm, the epochs, Over and the per-window wake.

// t0 is the fake clock. Nothing here reads the real one for the window: every
// call is handed its now, and a test steps time where it needs to.
var t0 = time.Date(2026, 9, 22, 12, 0, 0, 0, time.UTC)

const (
	// frame is a full datachannel frame, a smux frame of 12 KiB.
	frame = 12 << 10
	// window is the default window, spelled as a number the tests do sums on.
	window = DefaultWindow
)

func at(d time.Duration) time.Time { return t0.Add(d) }

// snapshot is a copy of key's window without the side effects of Room.
func snapshot(t *testing.T, w *Windows, key string) state {
	t.Helper()
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.windows[key]
	if st == nil {
		t.Fatalf("no window for %q", key)
	}
	return *st
}

// held reports whether a send to key would wait now, without the side effects
// of Room.
func held(w *Windows, key string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.windows[key]
	return st != nil && st.active && st.sent-st.echoed >= w.timing.Window
}

// on turns key's window on the way a destination that speaks the window
// does: the first n bytes to it are marked at once, and it echoes that mark.
// Nothing is in flight afterwards.
func on(t *testing.T, w *Windows, key string, n int) {
	t.Helper()
	due, counter := w.Sent(key, n, t0)
	if !due {
		t.Fatal("the first bytes to a destination were not marked")
	}
	if moved, turnedOn := w.ApplyEcho(key, counter); !moved || !turnedOn {
		t.Fatalf("the echo of the first mark: moved %v, turned on %v; want both", moved, turnedOn)
	}
}

// marks records the counts a sender's marks carry, as a carrier puts them on
// the wire after the bytes they count.
type marks []uint64

func (m *marks) sent(w *Windows, key string, n int, now time.Time) {
	if due, counter := w.Sent(key, n, now); due {
		*m = append(*m, counter)
	}
}

func (m *marks) last(t *testing.T) uint64 {
	t.Helper()
	if len(*m) == 0 {
		t.Fatal("no mark went out")
	}
	return (*m)[len(*m)-1]
}

func closed(ch <-chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

func closedWithin(t *testing.T, ch <-chan struct{}, within time.Duration, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(within):
		t.Fatal(what)
	}
}

// The zero Timing is the one the SaluteJazz leg was sized for (olcrtc#49),
// and a field left zero takes its default alone: a MarkEvery left zero is an
// eighth of whatever Window is.
func TestAZeroTimingTakesTheDefaults(t *testing.T) {
	want := Timing{
		Window:     192 << 10,
		MarkEvery:  24 << 10,
		ProbeAfter: time.Second,
		DeadAfter:  120 * time.Second,
		Retry:      250 * time.Millisecond,
	}
	if got := New(Timing{}).Timing(); got != want {
		t.Fatalf("New(Timing{}).Timing() = %+v, want %+v", got, want)
	}
	want = Timing{
		Window:     64 << 10,
		MarkEvery:  8 << 10,
		ProbeAfter: time.Second,
		DeadAfter:  time.Minute,
		Retry:      250 * time.Millisecond,
	}
	if got := New(Timing{Window: 64 << 10, DeadAfter: time.Minute}).Timing(); got != want {
		t.Fatalf("a partial Timing resolves to %+v, want %+v", got, want)
	}
}

// An older build drops marks unread and never echoes, so its window never
// turns on and the sender is not held for it, however much is in flight and
// however long: it is sent to as before.
func TestADestinationThatNeverEchoesIsSentToAsBefore(t *testing.T) {
	w := New(Timing{})
	for i := range 4 * window / frame {
		w.Sent("peer", frame, at(time.Duration(i)*time.Millisecond))
	}
	for _, now := range []time.Time{t0, at(DefaultProbeAfter), at(2 * DefaultDeadAfter)} {
		if ok, probe, _ := w.Room("peer", now); !ok || probe {
			t.Fatalf("at +%v: room %v, probe %v; a destination that never echoed held the sender",
				now.Sub(t0), ok, probe)
		}
	}
}

// Once on, a window lets a sender through until a whole window is in flight
// and holds it from there, until an echo of a later mark makes room.
func TestAnEchoTurnsTheWindowOnAndItHoldsAtAWindow(t *testing.T) {
	w := New(Timing{})
	on(t, w, "peer", frame)
	var m marks
	m.sent(w, "peer", window-1, t0)
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("held one byte short of a window in flight")
	}
	m.sent(w, "peer", 1, t0)
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("not held with a whole window in flight")
	}
	moved, turnedOn := w.ApplyEcho("peer", m.last(t))
	if !moved || turnedOn {
		t.Fatalf("the echo of a later mark: moved %v, turned on %v; want it moved a window already on", moved, turnedOn)
	}
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("an echo of the last mark left no room")
	}
}

// A window honours the size it was given.
func TestAWindowHoldsAtItsOwnSize(t *testing.T) {
	w := New(Timing{Window: 1000})
	on(t, w, "peer", 10)
	w.Sent("peer", 999, t0)
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("held at 999 of a 1000-byte window")
	}
	w.Sent("peer", 1, t0)
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("not held at 1000 of a 1000-byte window")
	}
}

// A window on is marked by bytes: every MarkEvery of them, with the count as
// of the byte that crossed.
func TestAWindowOnIsMarkedEveryMarkEveryBytes(t *testing.T) {
	w := New(Timing{Window: 64 << 10, MarkEvery: 8000, ProbeAfter: time.Hour})
	on(t, w, "peer", 100)
	var m marks
	for i := range 9 {
		m.sent(w, "peer", 3000, at(time.Duration(i)*time.Second))
	}
	want := marks{100 + 9000, 100 + 18000, 100 + 27000}
	if len(m) != len(want) {
		t.Fatalf("marks %v, want %v", m, want)
	}
	for i := range want {
		if m[i] != want[i] {
			t.Fatalf("marks %v, want %v", m, want)
		}
	}
}

// Until a destination echoes, its bytes are marked once a probe interval
// even when they are few: a peer that speaks the window turns it on within
// an interval of traffic. The first bytes to a destination are marked at
// once. A window on is marked by bytes alone.
func TestUntilItEchoesADestinationIsMarkedOnceAProbeInterval(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second})
	var m marks
	m.sent(w, "peer", 100, t0)
	m.sent(w, "peer", 100, at(999*time.Millisecond))
	m.sent(w, "peer", 100, at(time.Second))
	want := marks{100, 300}
	if len(m) != 2 || m[0] != want[0] || m[1] != want[1] {
		t.Fatalf("marks %v, want %v", m, want)
	}
	if moved, _ := w.ApplyEcho("peer", m.last(t)); !moved {
		t.Fatal("the echo of a mark moved nothing")
	}
	if due, _ := w.Sent("peer", 100, at(time.Hour)); due {
		t.Fatal("a window on was marked for time, not bytes")
	}
}

// A lost echo would hold the sender for good. A sender held for a probe
// interval since its last mark is asked to mark again, with what it has
// sent; the echo of that probe makes room.
func TestALostEchoIsRepairedByAProbe(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second, DeadAfter: time.Hour})
	on(t, w, "peer", frame)
	var lost marks
	for range window / frame {
		lost.sent(w, "peer", frame, t0)
	}
	sent := snapshot(t, w, "peer").sent
	if ok, probe, _ := w.Room("peer", at(999*time.Millisecond)); ok || probe {
		t.Fatalf("room %v, probe %v a moment after the last mark; want held without a probe", ok, probe)
	}
	ok, probe, counter := w.Room("peer", at(time.Second))
	if ok || !probe || counter != sent {
		t.Fatalf("a probe interval after the last mark: room %v, probe %v at %d; want held, a probe at %d",
			ok, probe, counter, sent)
	}
	if _, probe, _ := w.Room("peer", at(1500*time.Millisecond)); probe {
		t.Fatal("probed again within an interval of the probe")
	}
	if _, probe, again := w.Room("peer", at(2*time.Second)); !probe || again != sent {
		t.Fatalf("an interval after the probe: probe %v at %d; want another at %d", probe, again, sent)
	}
	if moved, _ := w.ApplyEcho("peer", counter); !moved {
		t.Fatal("the probe's echo moved nothing")
	}
	if ok, _, _ := w.Room("peer", at(2*time.Second)); !ok {
		t.Fatal("still held after the probe's echo")
	}
}

// A destination that holds the sender for DeadAfter without an echo is let
// go: its window is off, and the sender goes on as it would to an older
// peer. An echo turns the window on again.
func TestADestinationSilentForDeadAfterIsLetGo(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second, DeadAfter: time.Minute})
	on(t, w, "peer", frame)
	w.Sent("peer", window, t0)
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("the window is not on and full")
	}
	if ok, _, _ := w.Room("peer", at(time.Minute-time.Nanosecond)); ok {
		t.Fatal("let go before DeadAfter")
	}
	if ok, _, _ := w.Room("peer", at(time.Minute)); !ok {
		t.Fatal("still held after DeadAfter without an echo")
	}
	if ok, _, _ := w.Room("peer", at(time.Hour)); !ok {
		t.Fatal("a destination let go held the sender again")
	}
	moved, turnedOn := w.ApplyEcho("peer", snapshot(t, w, "peer").sent)
	if !moved || !turnedOn {
		t.Fatalf("an echo after the let-go: moved %v, turned on %v; want both", moved, turnedOn)
	}
	if ok, _, _ := w.Room("peer", at(time.Hour)); !ok {
		t.Fatal("held with nothing in flight")
	}
}

// A destination that echoes is alive even when its echo does not open the
// window: every echo starts the dead clock over.
func TestAnEchoStartsTheDeadClockOver(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Hour, DeadAfter: time.Minute})
	var m marks
	m.sent(w, "peer", frame, t0)
	m.sent(w, "peer", window/8, t0) // a MarkEvery left zero
	if len(m) != 2 {
		t.Fatalf("marks %v, want two", m)
	}
	w.Sent("peer", 2*window, t0)
	w.ApplyEcho("peer", m[0])

	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("the window is not on and full")
	}
	w.ApplyEcho("peer", m[1])
	if ok, _, _ := w.Room("peer", at(2*time.Minute)); ok {
		t.Fatal("a destination that echoed was let go as dead: its echo did not start the clock over")
	}
	if ok, _, _ := w.Room("peer", at(4*time.Minute)); !ok {
		t.Fatal("a destination silent for twice the dead period still holds the sender")
	}
}

// An echo moves a window only forward and only within what was sent: one
// past what was sent, one behind or at the last echo, and one for a
// destination with no window move nothing.
func TestAnEchoMovesAWindowOnlyForwardAndWithinWhatWasSent(t *testing.T) {
	w := New(Timing{})
	var m marks
	m.sent(w, "peer", frame, t0)
	m.sent(w, "peer", 2*window, t0)
	if len(m) != 2 {
		t.Fatalf("marks %v, want two", m)
	}
	first, second := m[0], m[1]
	for _, echo := range []struct {
		key     string
		counter uint64
	}{{"stranger", first}, {"peer", second + 1}, {"peer", 0}} {
		if moved, turnedOn := w.ApplyEcho(echo.key, echo.counter); moved || turnedOn {
			t.Fatalf("an echo of %d for %q moved the window", echo.counter, echo.key)
		}
	}
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("an echo past what was sent, or of nothing, turned the window on")
	}
	if moved, turnedOn := w.ApplyEcho("peer", first); !moved || !turnedOn {
		t.Fatal("the echo of the first mark did not turn the window on")
	}
	for _, stale := range []uint64{first, first - 1} {
		if moved, _ := w.ApplyEcho("peer", stale); moved {
			t.Fatalf("an echo of %d, not past the last echo of %d, moved the window", stale, first)
		}
	}
	if st := snapshot(t, w, "peer"); st.sent-st.echoed != 2*window {
		t.Fatalf("%d in flight, want %d", st.sent-st.echoed, 2*window)
	}
	if moved, turnedOn := w.ApplyEcho("peer", second); !moved || turnedOn {
		t.Fatalf("the echo of the second mark: moved %v, turned on %v; want moved only", moved, turnedOn)
	}
}

// Reset starts a window over. An echo of a mark from before it, late on its
// way, must not move the new window: counts go on across the reset, so its
// count is behind where the new window starts.
func TestAnEchoFromBeforeAResetMovesNoWindow(t *testing.T) {
	w := New(Timing{})
	var m marks
	m.sent(w, "peer", window/2, t0)
	stale := m.last(t)
	w.Reset("peer")

	m.sent(w, "peer", frame, t0)
	fresh := m.last(t)
	w.Sent("peer", 2*window, t0)
	if moved, _ := w.ApplyEcho("peer", stale); moved {
		t.Fatal("an echo from before the reset moved the new window")
	}
	if snapshot(t, w, "peer").active {
		t.Fatal("an echo from before the reset turned the new window on")
	}
	w.ApplyEcho("peer", fresh)
	st := snapshot(t, w, "peer")
	if !st.active || st.sent-st.echoed != 2*window {
		t.Fatalf("after the new window's own echo: on %v with %d in flight, want on with %d",
			st.active, st.sent-st.echoed, 2*window)
	}
}

// A window starts at the count of every byte counted to any destination, so
// after ResetAll no mark from before, of any window, is in range of a new
// one.
func TestANewWindowStartsPastEveryCountBeforeIt(t *testing.T) {
	w := New(Timing{})
	_, a := w.Sent("a", 1000, t0)
	_, b := w.Sent("b", 5000, t0)
	if a != 1000 || b != 6000 {
		t.Fatalf("first marks %d and %d; want 1000, and 6000 for a window opened after it", a, b)
	}
	w.ResetAll()
	_, freshA := w.Sent("a", 10, t0)
	_, freshB := w.Sent("b", 10, t0)
	for _, echo := range []struct {
		key     string
		counter uint64
	}{{"a", a}, {"a", b}, {"b", a}, {"b", b}} {
		if moved, _ := w.ApplyEcho(echo.key, echo.counter); moved {
			t.Fatalf("an echo of %d from before ResetAll moved %q's new window", echo.counter, echo.key)
		}
	}
	if moved, _ := w.ApplyEcho("a", freshA); !moved {
		t.Fatal("the new window's own echo moved nothing")
	}
	if moved, _ := w.ApplyEcho("b", freshB); !moved {
		t.Fatal("the other new window's own echo moved nothing")
	}
}

// One window per destination: a destination whose leg stalls holds the
// sender's bytes to it and nobody else's.
func TestAHeldDestinationDoesNotHoldAnother(t *testing.T) {
	w := New(Timing{})
	on(t, w, "slow", frame)
	on(t, w, "fast", frame)
	w.Sent("slow", window, t0)
	if ok, _, _ := w.Room("slow", t0); ok {
		t.Fatal("the stalled destination's window is not full")
	}
	for range window / frame / 2 {
		if ok, _, _ := w.Room("fast", t0); !ok {
			t.Fatal("the stalled destination held another")
		}
		w.Sent("fast", frame, t0)
	}
}

// Reset forgets one window and leaves the rest. The window it forgot starts
// over off: the destination may come back as an older build.
func TestResetStartsOneWindowOver(t *testing.T) {
	w := New(Timing{})
	for _, key := range []string{"a", "b"} {
		on(t, w, key, frame)
		w.Sent(key, window, t0)
	}
	w.Reset("a")
	if ok, _, _ := w.Room("a", t0); !ok {
		t.Fatal("a window Reset forgot holds the sender")
	}
	w.Sent("a", 4*window, t0)
	if ok, _, _ := w.Room("a", t0); !ok {
		t.Fatal("a window started over is on before an echo")
	}
	if !held(w, "b") {
		t.Fatal("Reset of one window let another go")
	}
}

// ResetAll forgets every window: a sender that starts over is not held by a
// window toward anyone.
func TestResetAllStartsEveryWindowOver(t *testing.T) {
	w := New(Timing{})
	for _, key := range []string{"a", "b"} {
		on(t, w, key, frame)
		w.Sent(key, window, t0)
	}
	w.ResetAll()
	for _, key := range []string{"a", "b"} {
		if ok, _, _ := w.Room(key, t0); !ok {
			t.Fatalf("%q's window outlived ResetAll", key)
		}
	}
}

// Len counts the windows open: what a sender counts or waits on opens one,
// and so does arming it; looking, echoing and bumping open none; a Reset or
// ResetAll closes them. A carrier that opens a window for a destination gone
// for good holds it until the whole Windows goes, and Len is how it sees that.
func TestLenCountsTheWindowsOpen(t *testing.T) {
	w := New(Timing{})
	if n := w.Len(); n != 0 {
		t.Fatalf("a new Windows has %d windows open, want 0", n)
	}
	w.Epoch("a")
	w.Wake("b")
	w.Sent("c", frame, t0)
	w.Arm("d")
	if n := w.Len(); n != 4 {
		t.Fatalf("Len = %d after Epoch, Wake, Sent and Arm on four keys, want 4", n)
	}
	w.Room("e", t0)
	w.Over("e", slack, t0)
	w.ApplyEcho("e", 1)
	w.Bump("e")
	if n := w.Len(); n != 4 {
		t.Fatalf("Len = %d after Room, Over, ApplyEcho and Bump on a fifth key, want 4", n)
	}
	w.Reset("a")
	if n := w.Len(); n != 3 {
		t.Fatalf("Len = %d after a Reset, want 3", n)
	}
	w.ResetAll()
	if n := w.Len(); n != 0 {
		t.Fatalf("Len = %d after ResetAll, want 0", n)
	}
}

// Arm turns a window on before any echo, for a destination that has said it
// speaks the window some other way: a sender can be held from its first
// byte. An armed window is marked by bytes, like any window on.
func TestArmTurnsTheWindowOnWithoutAnEcho(t *testing.T) {
	w := New(Timing{})
	w.Arm("peer")
	if !snapshot(t, w, "peer").active {
		t.Fatal("Arm left the window off")
	}
	if due, _ := w.Sent("peer", 100, t0); due {
		t.Fatal("an armed window's first bytes were marked for time, not bytes")
	}
	if due, _ := w.Sent("peer", 100, at(time.Hour)); due {
		t.Fatal("an armed window was marked for time, not bytes")
	}
	w.Sent("peer", window-201, t0)
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("held one byte short of a window")
	}
	w.Sent("peer", 1, t0)
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("an armed window with a window in flight did not hold the sender")
	}
}

// Arm on a window that has been counting keeps what is in flight, and the
// echo that comes later moves it without turning it on again. A second Arm
// changes nothing.
func TestArmKeepsWhatIsInFlight(t *testing.T) {
	w := New(Timing{})
	var m marks
	m.sent(w, "peer", frame, t0)
	w.Sent("peer", window, t0)
	if ok, _, _ := w.Room("peer", t0); !ok {
		t.Fatal("held before any echo or Arm")
	}
	w.Arm("peer")
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("Arm forgot what was in flight")
	}
	before := snapshot(t, w, "peer")
	w.Arm("peer")
	if after := snapshot(t, w, "peer"); after != before {
		t.Fatalf("a second Arm changed the window: %+v, then %+v", before, after)
	}
	if moved, turnedOn := w.ApplyEcho("peer", m.last(t)); !moved || turnedOn {
		t.Fatalf("the echo after Arm: moved %v, turned on %v; want moved only", moved, turnedOn)
	}
}

// A window Arm opens starts at the session's count, as one Sent opens does:
// no echo of a mark from before it, to this destination or another, moves it.
func TestArmOpensAWindowAtTheSessionsCount(t *testing.T) {
	w := New(Timing{})
	_, a := w.Sent("a", 1000, t0)
	w.Arm("b")
	if moved, _ := w.ApplyEcho("b", a); moved {
		t.Fatal("an echo of another window's mark moved a window Arm opened after it")
	}
	w.Reset("a")
	w.Arm("a")
	if moved, _ := w.ApplyEcho("a", a); moved {
		t.Fatal("an echo from before the reset moved the window Arm opened")
	}
	_, fresh := w.Sent("a", window/2, t0)
	if fresh != 1000+window/2 {
		t.Fatalf("the armed window's mark counts %d, want %d", fresh, 1000+window/2)
	}
	if moved, turnedOn := w.ApplyEcho("a", fresh); !moved || turnedOn {
		t.Fatalf("the armed window's own echo: moved %v, turned on %v; want moved only", moved, turnedOn)
	}
}

// An epoch names one window of a destination. Counting, echoes, Arm and Room
// keep it; Reset, Bump and ResetAll replace it, for that destination alone
// (ResetAll: for all). No epoch is zero.
func TestAnEpochLastsUntilAResetOrABump(t *testing.T) {
	w := New(Timing{})
	e1 := w.Epoch("a")
	other := w.Epoch("b")
	if e1 == 0 || other == 0 {
		t.Fatalf("epochs %d and %d; zero is no epoch", e1, other)
	}
	on(t, w, "a", frame)
	w.Arm("a")
	w.Sent("a", window, t0)
	w.Room("a", at(time.Hour))
	if got := w.Epoch("a"); got != e1 {
		t.Fatalf("the epoch went from %d to %d with the window in use", e1, got)
	}

	w.Reset("a")
	e2 := w.Epoch("a")
	w.Bump("a")
	e3 := w.Epoch("a")
	if e2 == e1 || e3 == e2 || e3 == e1 {
		t.Fatalf("epochs %d, then %d after Reset, then %d after Bump; want each new", e1, e2, e3)
	}
	if got := w.Epoch("b"); got != other {
		t.Fatalf("Reset and Bump of one window changed another's epoch: %d, then %d", other, got)
	}
	w.ResetAll()
	e4, after := w.Epoch("a"), w.Epoch("b")
	if e4 == e1 || e4 == e2 || e4 == e3 || after == other {
		t.Fatalf("ResetAll left an epoch: a %d (had %d, %d, %d), b %d (had %d)", e4, e1, e2, e3, after, other)
	}
}

// An epoch never comes back, whatever replaced it: a held sender compares
// the epoch it started under with the one it wakes to, and a repeat would
// send a frame for a session that is gone.
func TestAnEpochNeverComesBack(t *testing.T) {
	w := New(Timing{})
	seen := make(map[uint64]bool)
	replace := []func(){func() { w.Reset("peer") }, func() { w.Bump("peer") }, w.ResetAll}
	for i := range 300 {
		e := w.Epoch("peer")
		if seen[e] {
			t.Fatalf("epoch %d came back after %d replacements", e, i)
		}
		seen[e] = true
		replace[i%len(replace)]()
	}
}

// Bump gives a window a new epoch and keeps its counts: the frames a held
// sender waits with belong to a session that is gone, but the bytes it
// handed the relay before are still queued there, and their echoes still
// count.
func TestABumpKeepsTheCounts(t *testing.T) {
	w := New(Timing{})
	on(t, w, "peer", frame)
	var m marks
	m.sent(w, "peer", window, t0)
	before := snapshot(t, w, "peer")
	w.Bump("peer")
	after := snapshot(t, w, "peer")
	// The epoch and the wake channel are Bump's to replace; the counts are not.
	after.epoch, after.wake = before.epoch, before.wake
	if after != before {
		t.Fatalf("Bump changed the counts: %+v, then %+v", before, after)
	}
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("Bump let go of what is in flight")
	}
	if moved, turnedOn := w.ApplyEcho("peer", m.last(t)); !moved || turnedOn {
		t.Fatalf("the echo of a mark from before the bump: moved %v, turned on %v; want moved only", moved, turnedOn)
	}
}

// slack is the room past a window the SaluteJazz datagram rule leaves (D in
// the spec): a datagram is dropped only past W + D in flight.
const slack = 64 << 10

// A window that is off is never over, however much is in flight: an older
// build never echoes, and its datagrams go as they did before the window. A
// destination with no window is not over either, and the question opens
// none.
func TestAWindowOffIsNeverOver(t *testing.T) {
	w := New(Timing{})
	if w.Over("peer", slack, t0) {
		t.Fatal("a destination with no window is over")
	}
	if _, open := w.windows["peer"]; open {
		t.Fatal("Over opened a window")
	}
	w.Sent("peer", 16*window, t0)
	if w.Over("peer", slack, t0) {
		t.Fatalf("a destination that never echoed is over with %d in flight", 16*window)
	}
}

// Once on, a window is over past a window and the slack, not at a window: a
// sender is held at W, a datagram only dropped past W + D.
func TestAWindowOnIsOverPastAWindowAndItsSlack(t *testing.T) {
	w := New(Timing{})
	on(t, w, "peer", frame)
	w.Sent("peer", window, t0)
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("the window is not on and full")
	}
	if w.Over("peer", slack, t0) {
		t.Fatal("over at a window in flight: a datagram is dropped only past the slack")
	}
	w.Sent("peer", slack, t0)
	if w.Over("peer", slack, t0) {
		t.Fatalf("over at exactly a window and the slack (%d) in flight", window+slack)
	}
	w.Sent("peer", 1, t0)
	if !w.Over("peer", slack, t0) {
		t.Fatalf("not over at %d in flight, a byte past a window and the slack", window+slack+1)
	}
	if moved, _ := w.ApplyEcho("peer", snapshot(t, w, "peer").sent); !moved || w.Over("peer", slack, t0) {
		t.Fatal("still over after the echo of everything sent")
	}
}

// A window started over is empty: what was in flight before a Reset or a
// ResetAll does not count against the window opened after it, even once that
// one is on.
func TestAWindowStartedOverIsNotOver(t *testing.T) {
	for _, tc := range []struct {
		name string
		do   func(*Windows)
	}{
		{"Reset", func(w *Windows) { w.Reset("peer") }},
		{"ResetAll", func(w *Windows) { w.ResetAll() }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := New(Timing{})
			w.Arm("peer")
			w.Sent("peer", window+slack+1, t0)
			if !w.Over("peer", slack, t0) {
				t.Fatal("the window is not over to start with")
			}
			tc.do(w)
			if w.Over("peer", slack, t0) {
				t.Fatalf("over after %s", tc.name)
			}
			w.Arm("peer")
			w.Sent("peer", window+slack, t0)
			if w.Over("peer", slack, t0) {
				t.Fatalf("the window opened after %s counts what was in flight before it", tc.name)
			}
		})
	}
}

// Bump keeps the counts, so a window over stays over: the bytes are still
// queued at the relay.
func TestABumpKeepsAWindowOver(t *testing.T) {
	w := New(Timing{})
	w.Arm("peer")
	w.Sent("peer", window+slack+1, t0)
	w.Bump("peer")
	if !w.Over("peer", slack, t0) {
		t.Fatal("Bump forgot what is in flight")
	}
}

// A destination let go after DeadAfter is off, and is never over again until
// it echoes: liveness, not the window, decides it is gone.
func TestAWindowLetGoIsNeverOver(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second, DeadAfter: time.Minute})
	on(t, w, "peer", frame)
	w.Sent("peer", window+slack+1, t0)
	w.Room("peer", t0)
	if ok, _, _ := w.Room("peer", at(time.Minute)); !ok {
		t.Fatal("not let go after DeadAfter")
	}
	w.Sent("peer", 4*window, t0)
	if w.Over("peer", slack, t0) {
		t.Fatal("a destination let go is over")
	}
}

// A datagram yields to a sender held on its window for a probe interval. A
// datagram flow as fast as the destination's leg keeps between a Window and
// a Window and the slack in flight, takes the room each echo frees before the
// parked sender looks again, and starts the dead clock over with every one of
// those echoes: without the yield the byte stream - the tunnel's control
// pings with it - waits until liveness closes the session. The wait is the
// sender's own clock: an echo does not start it over, only a Room that lets a
// sender go or a Bump that sends every parked sender away ends it, and while
// it runs past a probe interval a datagram yields even to a window an echo
// has just opened, which the woken sender has not taken yet.
func TestADatagramYieldsToASenderHeldAProbeInterval(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second, DeadAfter: time.Minute})
	var m marks
	on(t, w, "peer", frame)
	for range (window + slack/2) / frame {
		m.sent(w, "peer", frame, t0)
	}
	if ok, _, _ := w.Room("peer", t0); ok {
		t.Fatal("the window is not on and full")
	}
	if w.Over("peer", slack, at(time.Second-time.Nanosecond)) {
		t.Fatal("a datagram yielded to a sender held for less than a probe interval")
	}
	if !w.Over("peer", slack, at(time.Second)) {
		t.Fatal("a datagram did not yield to a sender held for a probe interval")
	}

	if moved, _ := w.ApplyEcho("peer", m[0]); !moved || !held(w, "peer") {
		t.Fatal("the first echo did not move the window and leave it full")
	}
	if !w.Over("peer", slack, at(time.Second)) {
		t.Fatal("an echo that left the window full started the sender's wait over")
	}
	if moved, _ := w.ApplyEcho("peer", m.last(t)); !moved || held(w, "peer") {
		t.Fatal("the echo of everything sent did not open the window")
	}
	if !w.Over("peer", slack, at(time.Second)) {
		t.Fatal("a datagram took the room an echo opened for the sender held on it")
	}
	if ok, _, _ := w.Room("peer", at(time.Second)); !ok {
		t.Fatal("the sender was not let go on an open window")
	}
	if w.Over("peer", slack, at(time.Hour)) {
		t.Fatal("a datagram yielded after the sender was let go")
	}

	w.Sent("peer", window, at(time.Second))
	if ok, _, _ := w.Room("peer", at(time.Second)); ok {
		t.Fatal("the window is not full again")
	}
	w.Bump("peer")
	if w.Over("peer", slack, at(time.Hour)) {
		t.Fatal("a datagram yielded to senders a Bump sent away")
	}
	w.Room("peer", at(2*time.Second))
	if w.Over("peer", slack, at(3*time.Second-time.Nanosecond)) || !w.Over("peer", slack, at(3*time.Second)) {
		t.Fatal("a sender held after a Bump did not start a wait of its own")
	}
}

// Over only looks. It does not start the hold, take the probe a held sender
// is due, let a silent destination go or wake anyone: a datagram asks it on
// every send, and must not move a clock the reliable sender runs on.
func TestOverOnlyLooks(t *testing.T) {
	w := New(Timing{ProbeAfter: time.Second, DeadAfter: time.Minute})
	on(t, w, "peer", frame)
	w.Sent("peer", window+slack+1, t0)
	wake := w.Wake("peer")
	before := snapshot(t, w, "peer")
	for range 3 {
		w.Over("peer", slack, t0)
	}
	if after := snapshot(t, w, "peer"); after != before {
		t.Fatalf("Over changed the window: %+v, then %+v", before, after)
	}
	if closed(wake) {
		t.Fatal("Over woke the senders")
	}
	w.Room("peer", t0)
	w.Over("peer", slack, t0)
	if _, probe, _ := w.Room("peer", at(time.Second)); !probe {
		t.Fatal("Over took the probe a held sender was due")
	}
	if ok, _, _ := w.Room("peer", at(time.Minute-time.Nanosecond)); ok {
		t.Fatal("Over moved the dead clock")
	}
}

// An echo that moves a window closes the wake channel a sender held on that
// window took before it looked; one that moves nothing does not, and neither
// does one that moves another window: a server's senders to other clients
// stay parked. The next channel is a new one.
func TestAnEchoThatMovesAWindowWakesThatWindow(t *testing.T) {
	w := New(Timing{})
	_, counter := w.Sent("peer", frame, t0)
	_, otherCounter := w.Sent("other", frame, t0)
	wake, other := w.Wake("peer"), w.Wake("other")
	w.ApplyEcho("peer", counter+1)
	w.ApplyEcho("stranger", counter)
	if closed(wake) || closed(other) {
		t.Fatal("an echo that moved nothing woke the senders")
	}
	w.ApplyEcho("peer", counter)
	if !closed(wake) {
		t.Fatal("an echo that moved the window woke nobody")
	}
	if closed(other) {
		t.Fatal("an echo that moved one window woke the senders held on another")
	}
	if closed(w.Wake("peer")) {
		t.Fatal("the next wake channel came closed")
	}
	w.ApplyEcho("other", otherCounter)
	if !closed(other) {
		t.Fatal("an echo that moved the other window woke nobody")
	}
}

// Reset, ResetAll and Bump each wake a sender held on the window they
// change, which then finds its window gone or its epoch replaced. A Reset or
// a Bump of another window leaves it parked.
func TestResetsAndBumpsWake(t *testing.T) {
	for _, tc := range []struct {
		name string
		do   func(*Windows)
	}{
		{"Reset", func(w *Windows) { w.Reset("peer") }},
		{"ResetAll", func(w *Windows) { w.ResetAll() }},
		{"Bump", func(w *Windows) { w.Bump("peer") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := New(Timing{})
			for _, key := range []string{"peer", "other"} {
				on(t, w, key, frame)
				w.Sent(key, window, t0)
			}
			wake := w.Wake("peer")
			w.Reset("other")
			w.Bump("other")
			if closed(wake) {
				t.Fatal("a Reset or a Bump of another window woke the senders held on this one")
			}
			tc.do(w)
			if !closed(wake) {
				t.Fatalf("%s woke nobody", tc.name)
			}
		})
	}
}

// What only makes a window stricter, or looks at it, wakes nobody: a woken
// sender would find itself held again.
func TestCountingArmingAndLookingDoNotWake(t *testing.T) {
	w := New(Timing{})
	wake := w.Wake("peer")
	w.Sent("peer", window, t0)
	w.Arm("peer")
	w.Room("peer", at(time.Hour))
	w.Over("peer", slack, t0)
	w.Epoch("other")
	w.Wake("other")
	w.Timing()
	if closed(wake) {
		t.Fatal("counting, Arm or a look woke the senders")
	}
}

// parkOn parks n senders on key's wake channel and returns a channel closed
// once every one of them is released.
func parkOn(w *Windows, key string, n int) <-chan struct{} {
	var ready, released sync.WaitGroup
	ready.Add(n)
	released.Add(n)
	for range n {
		go func() {
			wake := w.Wake(key)
			ready.Done()
			<-wake
			released.Done()
		}()
	}
	ready.Wait()
	done := make(chan struct{})
	go func() {
		released.Wait()
		close(done)
	}()
	return done
}

// Several senders held on one window are all released by the one wake, so
// the one that can go is not left to its retry timer behind another that
// took the wake. Senders held on another window stay parked.
func TestOneWakeReleasesEverySenderParkedOnItsWindow(t *testing.T) {
	w := New(Timing{})
	_, counter := w.Sent("peer", frame, t0)
	otherWake := w.Wake("other")
	done := parkOn(w, "peer", 3)
	w.ApplyEcho("peer", counter)
	closedWithin(t, done, 5*time.Second, "one wake did not release every sender parked on the window")
	if closed(otherWake) {
		t.Fatal("the wake of one window released a sender parked on another")
	}
}

// ResetAll releases every parked sender, whatever window it waits on.
func TestResetAllReleasesEveryParkedSender(t *testing.T) {
	w := New(Timing{})
	keys := []string{"a", "b", "c"}
	done := make([]<-chan struct{}, 0, len(keys))
	for _, key := range keys {
		done = append(done, parkOn(w, key, 2))
	}
	w.ResetAll()
	for _, d := range done {
		closedWithin(t, d, 5*time.Second, "ResetAll did not release every parked sender")
	}
}

var errStaleEpoch = errors.New("the window's epoch changed while the sender was held")

// sender is a carrier's send path in miniature: it takes the wake channel
// before it looks, marks when Room asks for a probe, and parks until a wake
// or the retry timer. A window whose epoch changed meanwhile refuses the
// frame it waits with. parked, when set, is told that it parks, without
// waiting for anyone to listen.
type sender struct {
	w      *Windows
	key    string
	now    time.Time
	mark   func(counter uint64)
	parked chan<- struct{}
}

func (s sender) waitRoom(epoch uint64) error {
	for {
		wake := s.w.Wake(s.key)
		if s.w.Epoch(s.key) != epoch {
			return errStaleEpoch
		}
		ok, probe, counter := s.w.Room(s.key, s.now)
		if probe && s.mark != nil {
			s.mark(counter)
		}
		if ok {
			return nil
		}
		select {
		case s.parked <- struct{}{}:
		default:
		}
		select {
		case <-wake:
		case <-time.After(s.w.Timing().Retry):
		}
	}
}

// heldSender parks a sender on a full window toward "peer", with the retry
// an hour away: whatever releases it is a wake.
func heldSender(t *testing.T) (*Windows, uint64, <-chan error) {
	t.Helper()
	w := New(Timing{Retry: time.Hour})
	on(t, w, "peer", frame)
	w.Sent("peer", window, t0)
	epoch := w.Epoch("peer")
	parked := make(chan struct{}, 1)
	errc := make(chan error, 1)
	go func() {
		errc <- sender{w: w, key: "peer", now: t0, parked: parked}.waitRoom(epoch)
	}()
	closedWithin(t, parked, 5*time.Second, "the sender was never held")
	return w, epoch, errc
}

func released(t *testing.T, errc <-chan error, what string) error {
	t.Helper()
	select {
	case err := <-errc:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal(what)
		return nil
	}
}

// A held sender is woken by the echo that makes room and goes on, with the
// retry an hour away.
func TestAnEchoWakesAHeldSender(t *testing.T) {
	w, _, errc := heldSender(t)
	w.ApplyEcho("peer", snapshot(t, w, "peer").sent)
	if err := released(t, errc, "the held sender slept through the echo"); err != nil {
		t.Fatalf("released by an echo with %v", err)
	}
}

// A Reset or a Bump wakes a held sender at once, and it finds its epoch
// gone: the frame it waits with belongs to a session that is over, and it
// never goes out.
func TestAResetOrABumpReleasesAHeldSenderWithoutSending(t *testing.T) {
	for _, tc := range []struct {
		name string
		do   func(*Windows)
	}{
		{"Reset", func(w *Windows) { w.Reset("peer") }},
		{"ResetAll", func(w *Windows) { w.ResetAll() }},
		{"Bump", func(w *Windows) { w.Bump("peer") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w, _, errc := heldSender(t)
			tc.do(w)
			if err := released(t, errc, tc.name+" did not release the held sender"); !errors.Is(err, errStaleEpoch) {
				t.Fatalf("released by %s with %v, want %v", tc.name, err, errStaleEpoch)
			}
		})
	}
}

// The relay in miniature: a sender and a receiver that stalls for a while,
// and between them a queue that takes everything, as LiveKit's toward a
// subscriber does. With the window on from the first byte (Arm), what is
// queued never exceeds a window and a frame, and the load arrives whole and
// in order once the receiver reads. Time does not move, and the retry is an
// hour away: every step forward is a wake.
func TestASlowReceiverNeverHasMoreThanAWindowQueued(t *testing.T) {
	const frames = 6 * 512 << 10 / frame // six phone windows of smux
	w := New(Timing{Retry: time.Hour, ProbeAfter: time.Hour, DeadAfter: time.Hour})
	w.Arm("peer")

	type item struct {
		seq  int
		mark uint64
		size int
	}
	queue := make(chan item, 2*frames)
	var queued, peak atomic.Int64
	resume := make(chan struct{})
	got := make(chan int, frames)
	go func() {
		<-resume
		for it := range queue {
			if it.size == 0 {
				// The receive path reaches a mark only once every frame
				// before it has been handed up, and echoes it.
				w.ApplyEcho("peer", it.mark)
				continue
			}
			queued.Add(-int64(it.size))
			got <- it.seq
		}
	}()

	parked := make(chan struct{}, 1)
	s := sender{w: w, key: "peer", now: t0, parked: parked, mark: func(c uint64) { queue <- item{mark: c} }}
	sent := make(chan struct{})
	go func() {
		defer close(sent)
		epoch := w.Epoch("peer")
		for seq := range frames {
			if err := s.waitRoom(epoch); err != nil {
				t.Errorf("frame %d: %v", seq, err)
				return
			}
			if q := queued.Add(frame); q > peak.Load() {
				peak.Store(q)
			}
			queue <- item{seq: seq, size: frame}
			if due, counter := w.Sent("peer", frame, t0); due {
				queue <- item{mark: counter}
			}
		}
	}()

	closedWithin(t, parked, 5*time.Second, "the sender was never held by a stalled receiver")
	close(resume)
	closedWithin(t, sent, 10*time.Second, "the sender never finished: a wake was missed")
	for want := range frames {
		select {
		case seq := <-got:
			if seq != want {
				t.Fatalf("frame %d arrived where %d was due", seq, want)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("the receiver stopped at %d of %d frames", want, frames)
		}
	}
	close(queue)
	if p := peak.Load(); p > window+frame {
		t.Fatalf("%d bytes queued at the relay at the peak, over a window and a frame (%d)", p, window+frame)
	}
}
