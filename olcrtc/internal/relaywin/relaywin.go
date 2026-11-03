// Package relaywin bounds what a sender has handed a relay for one
// destination and the destination has not yet taken off it: an end-to-end
// window per destination, over a relay that never pushes back.
//
// An SFU that relays data between participants reads a sender's channel as
// fast as frames arrive and queues them toward each receiver. LiveKit 1.5.3,
// which SaluteJazz runs, acknowledges a megabyte at once and queues toward a
// subscriber without a limit; the JVB queues about 2 MB and drops the rest
// (olcrtc#15). Either way the sender's own channel says nothing about the
// receiver's leg, and on a slow one the queue grows to whatever smux's
// windows allow: megabytes, tens of seconds of it, with the tunnel's control
// pings waiting at the back (olcrtc#49).
//
// So a sender counts the bytes it hands the relay for each destination and
// follows them, every MarkEvery bytes, with a mark that carries the count.
// The destination echoes a mark when its receive path reaches it. The relay
// delivers in order, so an echo means every byte before the mark has left
// the relay; what is in flight is what was sent less the last count echoed,
// and a sender holds while that is a whole Window. The count is the sender's
// own, so a frame lost anyway cannot skew it: the next echo covers it.
//
// The carrier owns the wire - how a mark and an echo are framed, and whose
// echo counts for which destination. This package counts and decides:
//
//   - A window holds a sender only once it is on: an echo came from the
//     destination (ApplyEcho), or the destination said some other way that it
//     speaks the window (Arm). An older build never echoes and is sent to as
//     before. Until it echoes, a destination is marked at least once a
//     ProbeAfter, so one that does speak the window turns it on within an
//     interval of traffic.
//   - An echo moves a window only past its last echo and within what was
//     sent. Counts never start over: a window opened after a Reset starts at
//     the count of every byte counted here, past every mark sent before it,
//     so a late echo of one of those is out of its range.
//   - A sender held for ProbeAfter since the last mark marks again (Room asks
//     for it), which repairs a lost mark or echo. One held for DeadAfter
//     without an echo lets the destination go: its window is off, and what
//     waits for it goes as it would without one. Liveness, not the window,
//     decides that a peer is gone.
//   - Each window has an epoch, which Reset, ResetAll and Bump replace. A
//     sender that parked under one epoch and wakes under another holds a
//     frame for a session that is over, and must not send it.
//   - A frame the carrier would rather drop than hold, a datagram, asks Over
//     instead of Room: it goes until the window is on and more than a Window
//     and the carrier's slack is in flight, or a sender has been held on the
//     window for ProbeAfter. The slack lets datagrams through beside a bulk
//     sender that keeps the window full, which is let go at every echo; the
//     yield keeps a datagram flow as fast as the destination's leg from
//     taking every byte an echo frees before a held sender looks again, which
//     would hold it until liveness closed the session. Over only looks, so
//     asking it on every datagram moves none of the clocks a held sender runs
//     on.
//
// A sender that has to wait takes its window's Wake before it asks Room, then
// waits on that channel and a Retry timer. An echo that moves the window, a
// Reset or a Bump of it, and ResetAll close the channel, so a wake between
// the question and the wait is not missed. One wake releases every sender
// parked on that window and none parked on another: a server with a sender
// parked per client wakes the one an echo made room for, not all of them on
// every echo from any of them.
//
// A carrier that sets Timing.Horizon has each window sized for its leg
// instead of held at Window: a fixed window is seconds of queue on a slow leg,
// and a pong waits behind all of it. Once a second while a sender is held on
// it, the window measures the rate its destination took bytes off the relay,
// and keeps the best of the last rateSamples of those. It starts at
// firstSizedWindow, and its size is then
//
//	best recent rate × Horizon
//
// no less than MinWindow and no more than Window: Horizon of the leg in
// flight, whatever the leg. A sender that is never held sends less than the
// leg takes, and its rate says nothing about the leg, so the size stays.
//
// The best rate, not the last one, and no round trip: through Sber's relay a
// receiver is handed its queue in bursts, seconds apart. A window sized by the
// last second's rate and the shortest echo it ever saw shrinks below what the
// leg needs in flight to stay busy, and the rate it measures then is its own,
// which shrinks it again, to MinWindow (a 60 kB/s leg moved 16 kB/s that way).
// Sized by the best rate a leg has shown, a window only shrinks when the leg
// does, and a burst or a lull costs nothing; Horizon has to be longer than
// the delay a relay adds of its own, or the window measures itself. Marks
// follow the size. Nothing on the wire changes: the size is the sender's own,
// so a peer on any build is sent to as before.
//
// Jitsi keeps its own copy of the mechanism, where it was proved
// (internal/engine/jitsi/relaywindow.go); this is that mechanism without a
// wire, for the carriers that came after it.
//
// ai-generated: the whole package (olcrtc#49).
package relaywin

import (
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	// DefaultWindow is 192 KiB a destination. A control ping waits behind
	// up to a window one way and its pong behind up to a window the other,
	// so on the slowest SFU-to-receiver leg the gate has measured, 34 kB/s,
	// two windows and a round trip have to fit in the tunnel's 15 s
	// liveness timeout with a margin: 256 KiB does not. At a 0.35 s round
	// trip it still carries about 3.9 Mbit/s (7/8 of a window a round
	// trip), over the gate's 1.4 and 1.6 Mbit/s floors.
	DefaultWindow = 192 << 10
	// DefaultProbeAfter: a sender held this long since its last mark marks
	// again, and a destination that has not echoed yet is marked at least
	// this often.
	DefaultProbeAfter = time.Second
	// DefaultDeadAfter: a destination that has held a sender this long
	// without an echo is let go. A relay that never drops reliable data
	// (LiveKit) makes a silent destination a slow leg or a hole being
	// repaired far more often than a dead one, and liveness closes a dead
	// one long before this: it is a backstop, not a verdict.
	DefaultDeadAfter = 120 * time.Second
	// DefaultRetry is how often a held sender looks again, for the probe and
	// the dead check; an echo, a Reset or a Bump wakes it at once anyway.
	DefaultRetry = DefaultProbeAfter / 4

	// markDivisor: a MarkEvery left zero is this part of the window, which
	// keeps a sender's view within an eighth of the truth for one small frame
	// a mark.
	markDivisor = 8

	// DefaultMinWindow is the least a window sized for its leg shrinks to
	// when the Timing names no MinWindow: at the 26 kB/s the gate has
	// measured through Sber's relay it is well under a second of queue, and
	// it still keeps a slower leg moving.
	DefaultMinWindow = 16 << 10
	// minMarkEvery: a window sized down still marks at least this far apart,
	// so a small window does not spend its leg on marks.
	minMarkEvery = 2 << 10
	// rateInterval is how long a sized window measures the rate its
	// destination takes bytes at before it sizes itself again, and
	// rateSamples how many of those rates it keeps the best of: a lull of a
	// few seconds does not shrink a window, a leg slower for ten does.
	rateInterval = time.Second
	rateSamples  = 10
	// firstSizedWindow is where a sized window starts, before it has
	// measured its leg: what a new window hands the relay at once is queued
	// there however slow the leg, 7.6 s of it at the cap on a 26 kB/s leg,
	// and a pong behind it. A leg that carries it in less than Horizon grows
	// it within a rateInterval or two.
	firstSizedWindow = 64 << 10
)

// Timing sizes a Windows. A field left zero takes its default: the Default
// constants, and for MarkEvery an eighth of Window.
type Timing struct {
	// Window is how many bytes may be in flight to one destination.
	Window uint64
	// MarkEvery is how many bytes to a destination go between two marks.
	MarkEvery uint64
	// ProbeAfter, DeadAfter and Retry: see DefaultProbeAfter,
	// DefaultDeadAfter and DefaultRetry.
	ProbeAfter time.Duration
	DeadAfter  time.Duration
	Retry      time.Duration
	// Horizon, when set, sizes each destination's window for its leg (see
	// the package comment): how much of the leg's time a window lets be in
	// flight, at the best rate the leg has shown lately. Zero keeps every
	// window at Window.
	Horizon time.Duration
	// MinWindow is the least a sized window shrinks to: DefaultMinWindow
	// when Horizon is set and this is zero, and never more than Window.
	MinWindow uint64
}

func (t Timing) withDefaults() Timing {
	if t.Window == 0 {
		t.Window = DefaultWindow
	}
	if t.MarkEvery == 0 {
		t.MarkEvery = max(t.Window/markDivisor, 1)
	}
	if t.ProbeAfter <= 0 {
		t.ProbeAfter = DefaultProbeAfter
	}
	if t.DeadAfter <= 0 {
		t.DeadAfter = DefaultDeadAfter
	}
	if t.Retry <= 0 {
		t.Retry = DefaultRetry
	}
	if t.Horizon > 0 {
		if t.MinWindow == 0 {
			t.MinWindow = DefaultMinWindow
		}
		t.MinWindow = min(t.MinWindow, t.Window)
	}
	return t
}

// state is the window toward one destination.
type state struct {
	sent      uint64    // bytes handed to the relay for the destination
	marked    uint64    // sent, as of the last mark
	echoed    uint64    // the highest count the destination has echoed
	active    bool      // the window holds: an echo came, or Arm
	markedAt  time.Time // when the last mark went out
	heldSince time.Time // when the window filled, cleared by every echo
	// waitingSince is when a sender was first held since Room last let one
	// go. Unlike heldSince an echo leaves it: an echo whose room a datagram
	// takes lets nobody go. Room letting a sender go clears it, and so does
	// Bump, which sends every parked sender away.
	waitingSince time.Time
	epoch        uint64 // replaced by Reset, ResetAll and Bump; never zero
	// wake is closed when a sender held on the window may go on: replaced
	// by an echo that moves it and by Bump, left closed by Reset and
	// ResetAll, which drop the window with it.
	wake chan struct{}

	// size is the window now: Window, or what its leg sized it to
	// (Timing.Horizon). markEvery is how many bytes go between two marks at
	// that size.
	size      uint64
	markEvery uint64
	// leg is what a sized window measures of its leg, nil when the window
	// is not sized.
	leg *legMeasure
}

// legMeasure is what a sized window measures of its leg.
type legMeasure struct {
	// rateSince and rateFrom start the interval the destination's rate is
	// measured over: when, and the count echoed then. held says a sender was
	// held on the window during it.
	rateSince time.Time
	rateFrom  uint64
	held      bool
	// rates are the last rateSamples rates measured with a sender held, in
	// bytes a second, next the slot the next one takes.
	rates [rateSamples]float64
	next  int
}

// Windows is the window toward every destination of one sender, keyed by
// whatever the carrier addresses a destination by. It is safe for concurrent
// use. Make one with New.
type Windows struct {
	timing Timing

	mu      sync.Mutex
	windows map[string]*state
	// count is every byte counted against a window since New, where a new
	// window's count starts. epochs is the last epoch handed out.
	count  uint64
	epochs uint64
}

// New returns a Windows with no window open, sized by t.
func New(t Timing) *Windows {
	return &Windows{
		timing:  t.withDefaults(),
		windows: make(map[string]*state),
	}
}

// Timing is the timing in force, its zero fields filled in: a held sender
// waits Retry between looks.
func (w *Windows) Timing() Timing { return w.timing }

// Room reports whether a send to key may go now. It is false only while the
// window is on and a whole Window is in flight.
//
// A window that holds the sender and has not marked for ProbeAfter asks for
// a probe: probe is true and counter is the count the mark carries, which the
// caller sends whether or not ok. A window that has held the sender for
// DeadAfter without an echo is turned off, and the send goes.
func (w *Windows) Room(key string, now time.Time) (bool, bool, uint64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.windows[key]
	if st == nil || !st.active || st.sent-st.echoed < st.size {
		if st != nil {
			st.heldSince, st.waitingSince = time.Time{}, time.Time{}
		}
		return true, false, 0
	}
	if st.leg != nil {
		st.leg.held = true
	}
	if st.heldSince.IsZero() {
		st.heldSince = now
	}
	if st.waitingSince.IsZero() {
		st.waitingSince = now
	}
	if held := now.Sub(st.heldSince); held >= w.timing.DeadAfter {
		// The destination is not named: the key is the carrier's address
		// for it, which does not go into a log.
		logger.Infof("relaywin: no echo for %s with %d bytes in flight - window off",
			held.Round(time.Second), st.sent-st.echoed)
		st.active = false
		st.heldSince, st.waitingSince = time.Time{}, time.Time{}
		return true, false, 0
	}
	if now.Sub(st.markedAt) >= w.timing.ProbeAfter {
		st.marked, st.markedAt = st.sent, now
		return false, true, st.sent
	}
	return false, false, 0
}

// Sent counts n bytes handed to the relay for key, opening its window if
// there is none, and reports whether a mark is due and the count it carries:
// every MarkEvery bytes, and while the destination has not echoed, once a
// ProbeAfter as well, starting with the first bytes. The caller sends the
// mark after the bytes it counts, on the same ordered lane. counter is zero
// when no mark is due.
func (w *Windows) Sent(key string, n int, now time.Time) (bool, uint64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.open(key)
	add := uint64(max(n, 0))
	st.sent += add
	w.count += add
	due := st.sent-st.marked >= st.markEvery ||
		(!st.active && now.Sub(st.markedAt) >= w.timing.ProbeAfter)
	if !due {
		return false, 0
	}
	st.marked, st.markedAt = st.sent, now
	return true, st.sent
}

// ApplyEcho takes the destination's echo of counter without a clock: it moves
// the window as ApplyEchoAt does and gives a sized window no measurement.
func (w *Windows) ApplyEcho(key string, counter uint64) (bool, bool) {
	return w.ApplyEchoAt(key, counter, time.Time{})
}

// ApplyEchoAt takes the destination's echo of counter, come back at now. It
// moves key's window only past the last echo and within what was sent, and
// reports whether it moved and whether that turned the window on. A window
// that moved wakes every sender parked on it. A sized window also counts the
// echo into its rate and, once a rateInterval has passed, sizes itself again;
// a zero now gives it no measurement. The caller has checked that the
// echo came from the destination key names: anyone who can see a count can
// send it back.
func (w *Windows) ApplyEchoAt(key string, counter uint64, now time.Time) (bool, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.windows[key]
	if st == nil || counter <= st.echoed || counter > st.sent {
		return false, false
	}
	turnedOn := !st.active
	st.active = true
	st.echoed = counter
	st.heldSince = time.Time{}
	if st.leg != nil && !now.IsZero() {
		w.measure(st, counter, now)
	}
	st.wakeSenders()
	return true, turnedOn
}

// Size is the window toward key now: Window, or for a sized window its first
// size until its leg has sized it; Window for a key with no window open. It
// only looks.
func (w *Windows) Size(key string) uint64 {
	w.mu.Lock()
	defer w.mu.Unlock()
	if st := w.windows[key]; st != nil {
		return st.size
	}
	return w.timing.Window
}

// Arm turns key's window on without an echo, opening it if there is none:
// the destination has said some other way that it speaks the window, so a
// sender may be held before its first echo comes. What is in flight stays.
func (w *Windows) Arm(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.open(key)
	if !st.active {
		st.active = true
		st.heldSince = time.Time{}
	}
}

// Reset forgets key's window: the destination starts over, and what was
// counted toward it means nothing to what comes after. A window opened for
// key later starts off, at the count of every byte counted so far, under a
// new epoch. A sender parked on the window is woken.
func (w *Windows) Reset(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if st := w.windows[key]; st != nil {
		close(st.wake)
		delete(w.windows, key)
	}
}

// ResetAll forgets every window, as Reset does one, and wakes every parked
// sender. The count of bytes and the epochs go on.
func (w *Windows) ResetAll() {
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, st := range w.windows {
		close(st.wake)
	}
	clear(w.windows)
}

// Bump gives key's window a new epoch and keeps its counts: the session a
// parked sender's frame belongs to is over, but the bytes it handed the relay
// before are still queued there and their echoes still count. A sender
// parked on the window is woken, to find its epoch gone, so nobody is waiting
// on the window any more.
func (w *Windows) Bump(key string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if st := w.windows[key]; st != nil {
		st.epoch = w.nextEpoch()
		st.waitingSince = time.Time{}
		st.wakeSenders()
	}
}

// Epoch is the epoch of key's window, opening the window if there is none.
// It is never zero, and it is never one another window has had: a sender
// takes it before it waits and compares it with the one it wakes to.
func (w *Windows) Epoch(key string) uint64 {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.open(key).epoch
}

// Over reports whether key's window is on with more than a Window and slack
// in flight, or has held a sender for ProbeAfter by now. It is for a frame the
// carrier drops rather than holds, a datagram: one that finds the window over
// is dropped, and one that does not goes and is counted with Sent like any
// other. A window that is off is never over - an older build never echoes, so
// what is in flight to it only grows, and its datagrams go as they did before
// the window - and neither is a destination with no window.
//
// The slack is for a bulk sender that keeps the window full: it is let go at
// every echo, so its waits are short, and datagrams still go beside it. A
// sender held for ProbeAfter is one they are starving: a flow as fast as the
// leg takes the room each echo frees before the sender looks again. From then
// until Room lets a sender go, every datagram to key is dropped, even one
// that finds room an echo has just made: that room is the held sender's.
//
// Over only looks. It opens no window, starts no hold, takes no probe, lets
// nothing go and wakes nobody: those are Room's, for the sender that waits.
func (w *Windows) Over(key string, slack uint64, now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	st := w.windows[key]
	if st == nil || !st.active {
		return false
	}
	if !st.waitingSince.IsZero() && now.Sub(st.waitingSince) >= w.timing.ProbeAfter {
		return true
	}
	// Compared past the window, so no slack can wrap the sum.
	inFlight := st.sent - st.echoed
	return inFlight > st.size && inFlight-st.size > slack
}

// Wake is the channel closed the next time a sender held on key's window may
// go on: an echo moved the window, a Reset or a Bump changed it, or ResetAll
// changed every window. A sender takes it before it asks Room, so a wake
// between the two is not missed. It opens the window if there is none, as
// Epoch does, so the channel is the one of the window Room will look at.
func (w *Windows) Wake(key string) <-chan struct{} {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.open(key).wake
}

// Len is how many destinations have a window open. It only looks. A window
// stays open until Reset or ResetAll: a carrier that sends to a destination
// gone for good opens one it keeps, and Len is how that shows.
func (w *Windows) Len() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.windows)
}

// open returns key's window, opening one at the session's count if there is
// none: no mark sent before it is past that count, so no echo of one can move
// the new window. Called with mu held.
func (w *Windows) open(key string) *state {
	st := w.windows[key]
	if st == nil {
		st = &state{
			sent: w.count, marked: w.count, echoed: w.count,
			epoch: w.nextEpoch(),
			wake:  make(chan struct{}),
			size:  w.timing.Window, markEvery: w.timing.MarkEvery,
		}
		if w.timing.Horizon > 0 {
			st.leg = &legMeasure{}
			w.resize(st, min(max(firstSizedWindow, w.timing.MinWindow), w.timing.Window))
		}
		w.windows[key] = st
	}
	return st
}

// measure takes an echo of counter, come back at now, into a sized window's
// rate and, once a rateInterval has passed, sizes the window again from the
// best rate its leg has shown in the last rateSamples intervals. An interval
// in which no sender was held says nothing about the leg and is not kept.
// Called with mu held.
func (w *Windows) measure(st *state, counter uint64, now time.Time) {
	leg := st.leg
	if leg.rateSince.IsZero() {
		leg.rateSince, leg.rateFrom, leg.held = now, counter, false
		return
	}
	elapsed := now.Sub(leg.rateSince)
	if elapsed < rateInterval {
		return
	}
	if leg.held {
		leg.rates[leg.next] = float64(counter-leg.rateFrom) / elapsed.Seconds()
		leg.next = (leg.next + 1) % rateSamples
		best := 0.0
		for _, rate := range leg.rates {
			best = max(best, rate)
		}
		want := uint64(best * w.timing.Horizon.Seconds())
		w.resize(st, min(max(want, w.timing.MinWindow), w.timing.Window))
	}
	leg.rateSince, leg.rateFrom, leg.held = now, counter, false
}

// resize gives a window a new size and marks it in proportion: at Window it
// marks every MarkEvery, as an unsized window does, and a smaller one no
// closer than minMarkEvery. Called with mu held.
func (w *Windows) resize(st *state, size uint64) {
	st.size = size
	st.markEvery = max(size*w.timing.MarkEvery/w.timing.Window, min(minMarkEvery, w.timing.MarkEvery))
}

// nextEpoch hands out an epoch no window has had. Called with mu held.
func (w *Windows) nextEpoch() uint64 {
	w.epochs++
	return w.epochs
}

// wakeSenders releases every sender parked on the window and arms the next
// wait. Called with mu held.
func (st *state) wakeSenders() {
	close(st.wake)
	st.wake = make(chan struct{})
}
