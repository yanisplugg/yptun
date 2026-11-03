package relaywin

import (
	"testing"
	"time"
)

// ai-generated: the whole file (a window sized for its leg, olcrtc#49).

// sized is the Timing SaluteJazz sizes its windows with.
var sized = Timing{Horizon: 3 * time.Second}

// leg is a relay and the destination behind it, on the fake clock: what a
// sender hands the relay queues, the leg drains it at rate bytes a second, and
// the destination's echo of a mark comes back rtt after the leg delivered the
// mark's last byte. A sender keeps the window full, or sends only appLimit
// bytes a second when that is set.
type leg struct {
	t        *testing.T
	w        *Windows
	key      string
	rate     float64
	rtt      time.Duration
	appLimit float64
	// timed says whether echoes carry the clock (ApplyEchoAt) or not.
	timed bool

	// jitter, when set, delays each echo by up to this much more, in order:
	// a relay that hands a receiver its queue in bursts. seed drives it.
	jitter time.Duration
	seed   uint64

	now       time.Time
	lastEcho  time.Time
	drained   float64
	pending   []uint64 // marks sent, not yet delivered
	echoes    []echoAt // marks delivered, echo on its way
	appBudget float64
	// worstQueue is the longest a byte waited in the relay, in time: the
	// queue ahead of a pong on this leg.
	worstQueue time.Duration
}

type echoAt struct {
	counter uint64
	at      time.Time
}

func newLeg(t *testing.T, timing Timing, rate float64, rtt time.Duration) *leg {
	t.Helper()
	l := &leg{t: t, w: New(timing), key: "peer", rate: rate, rtt: rtt, timed: true, now: t0}
	on(t, l.w, l.key, 1)
	l.drained = float64(snapshot(t, l.w, l.key).sent)
	return l
}

// run steps the clock by tick for d, sending, draining and echoing.
func (l *leg) run(d, tick time.Duration) {
	l.t.Helper()
	for end := l.now.Add(d); l.now.Before(end); l.now = l.now.Add(tick) {
		l.send(tick)
		l.drain(tick)
		l.echo()
	}
}

func (l *leg) send(tick time.Duration) {
	if l.appLimit > 0 {
		l.appBudget += l.appLimit * tick.Seconds()
	}
	for {
		if l.appLimit > 0 && l.appBudget < frame {
			return
		}
		ok, probe, counter := l.w.Room(l.key, l.now)
		if probe {
			l.pending = append(l.pending, counter)
		}
		if !ok {
			return
		}
		if due, counter := l.w.Sent(l.key, frame, l.now); due {
			l.pending = append(l.pending, counter)
		}
		if l.appLimit > 0 {
			l.appBudget -= frame
		}
	}
}

func (l *leg) drain(tick time.Duration) {
	sent := float64(snapshot(l.t, l.w, l.key).sent)
	if queued := sent - l.drained; queued > 0 {
		if wait := time.Duration(queued / l.rate * float64(time.Second)); wait > l.worstQueue {
			l.worstQueue = wait
		}
	}
	l.drained = min(sent, l.drained+l.rate*tick.Seconds())
	for len(l.pending) > 0 && float64(l.pending[0]) <= l.drained {
		at := l.now.Add(l.rtt + l.nextJitter())
		if at.Before(l.lastEcho) {
			at = l.lastEcho // echoes come back in the order their marks went
		}
		l.lastEcho = at
		l.echoes = append(l.echoes, echoAt{counter: l.pending[0], at: at})
		l.pending = l.pending[1:]
	}
}

// nextJitter is the next echo's extra delay, from a fixed sequence so a run
// is the same every time.
func (l *leg) nextJitter() time.Duration {
	if l.jitter <= 0 {
		return 0
	}
	l.seed = l.seed*6364136223846793005 + 1442695040888963407
	return time.Duration((l.seed >> 33) % uint64(l.jitter)) //nolint:gosec // under jitter, which fits
}

func (l *leg) echo() {
	for len(l.echoes) > 0 && !l.echoes[0].at.After(l.now) {
		if l.timed {
			l.w.ApplyEchoAt(l.key, l.echoes[0].counter, l.now)
		} else {
			l.w.ApplyEcho(l.key, l.echoes[0].counter)
		}
		l.echoes = l.echoes[1:]
	}
}

// settle runs the leg long enough for a window to find its size, then starts
// the queue record over.
func (l *leg) settle() {
	l.run(30*time.Second, 10*time.Millisecond)
	l.worstQueue = 0
}

// The leg the gate measured through Sber's relay (olcrtc#49): 26 kB/s from the
// SFU to the receiver, 0.3 s of round trip under the queue.
const (
	slowLeg = 26_000.0
	slowRTT = 300 * time.Millisecond
)

func TestASizedWindowShrinksToWhatASlowLegCarries(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.settle()
	l.run(30*time.Second, 10*time.Millisecond)

	size := l.w.Size(l.key)
	if size >= DefaultWindow/2 {
		t.Fatalf("on a %.0f kB/s leg the window stayed at %d KiB", slowLeg/1000, size>>10)
	}
	if size < DefaultMinWindow {
		t.Fatalf("the window shrank to %d bytes, under its %d KiB floor", size, DefaultMinWindow>>10)
	}
	// What a pong waits behind is the queue the window lets build: Horizon
	// of the leg, and the frame that went just before the window filled.
	rate := slowLeg
	limit := sized.Horizon + time.Duration(frame/rate*float64(time.Second)) + 500*time.Millisecond
	if l.worstQueue > limit {
		t.Fatalf("a byte waited %s in the relay on a settled %.0f kB/s leg, over %s",
			l.worstQueue.Round(time.Millisecond), slowLeg/1000, limit)
	}
	t.Logf("%.0f kB/s leg: window %d KiB, worst queue %s", slowLeg/1000, size>>10, l.worstQueue.Round(time.Millisecond))
}

func TestAFixedWindowQueuesSecondsOnTheSameLeg(t *testing.T) {
	// The case the sizing exists for, kept as a control: the same leg with
	// the fixed window queues a whole window, several seconds of it.
	l := newLeg(t, Timing{}, slowLeg, slowRTT)
	l.settle()
	l.run(30*time.Second, 10*time.Millisecond)
	if size := l.w.Size(l.key); size != DefaultWindow {
		t.Fatalf("a fixed window changed size to %d", size)
	}
	if l.worstQueue < 6*time.Second {
		t.Fatalf("the fixed window queued only %s on a %.0f kB/s leg: the control proves nothing",
			l.worstQueue.Round(time.Millisecond), slowLeg/1000)
	}
}

func TestASizedWindowGrowsBackWhenTheLegSpeedsUp(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.settle()
	if l.w.Size(l.key) >= DefaultWindow {
		t.Fatal("the window did not shrink on the slow leg to begin with")
	}
	l.rate = 1_000_000
	l.run(20*time.Second, 10*time.Millisecond)
	if size := l.w.Size(l.key); size != DefaultWindow {
		t.Fatalf("on a 1 MB/s leg the window is %d KiB, not its %d KiB cap", size>>10, DefaultWindow>>10)
	}
}

func TestASenderThatIsNeverHeldLeavesTheSizeAlone(t *testing.T) {
	// A sender under what the leg carries says nothing about the leg.
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.appLimit = slowLeg / 4
	first := l.w.Size(l.key)
	l.run(60*time.Second, 10*time.Millisecond)
	if size := l.w.Size(l.key); size != first {
		t.Fatalf("a sender that never filled the window moved its size from %d to %d KiB", first>>10, size>>10)
	}
}

func TestMarksFollowTheSize(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.settle()
	st := snapshot(t, l.w, l.key)
	want := max(st.size*l.w.Timing().MarkEvery/DefaultWindow, minMarkEvery)
	if st.markEvery != want {
		t.Fatalf("a %d KiB window marks every %d bytes, want %d", st.size>>10, st.markEvery, want)
	}
	if st.markEvery >= l.w.Timing().MarkEvery {
		t.Fatalf("a %d KiB window still marks every %d bytes, as the cap does", st.size>>10, st.markEvery)
	}
}

func TestASizedWindowHoldsAndOverflowsAtItsOwnSize(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.settle()
	st := snapshot(t, l.w, l.key)
	inFlight := st.sent - st.echoed
	if inFlight < st.size {
		// The settled sender keeps the window full; a tick without an echo
		// leaves it held.
		t.Fatalf("a settled sender has %d bytes in flight under its %d byte window", inFlight, st.size)
	}
	if ok, _, _ := l.w.Room(l.key, l.now); ok {
		t.Fatalf("Room lets a send go with %d bytes in flight on a %d byte window", inFlight, st.size)
	}
	if !l.w.Over(l.key, 0, l.now) && inFlight > st.size {
		t.Fatalf("Over does not see %d bytes in flight past a %d byte window", inFlight, st.size)
	}
}

func TestResetForgetsTheSize(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.settle()
	l.w.Reset(l.key)
	l.w.Epoch(l.key) // the next send opens it again
	if size := l.w.Size(l.key); size != firstSizedWindow {
		t.Fatalf("a window reset starts at %d KiB, not its first %d KiB", size>>10, firstSizedWindow>>10)
	}
	st := snapshot(t, l.w, l.key)
	if st.leg == nil {
		t.Fatal("a sized window opened again has nothing to measure its leg with")
	}
	if !st.leg.rateSince.IsZero() || st.leg.rates != [rateSamples]float64{} {
		t.Fatalf("a reset window kept its measurements: rate from %s, rates %v", st.leg.rateSince, st.leg.rates)
	}
}

func TestAnEchoWithoutAClockNeverResizes(t *testing.T) {
	l := newLeg(t, sized, slowLeg, slowRTT)
	l.timed = false
	first := l.w.Size(l.key)
	l.run(60*time.Second, 10*time.Millisecond)
	if size := l.w.Size(l.key); size != first {
		t.Fatalf("echoes with no clock resized the window from %d to %d KiB", first>>10, size>>10)
	}
}

func TestWithoutAHorizonTheWindowIsFixed(t *testing.T) {
	l := newLeg(t, Timing{}, slowLeg, slowRTT)
	l.run(60*time.Second, 10*time.Millisecond)
	if size := l.w.Size(l.key); size != DefaultWindow {
		t.Fatalf("a window with no Horizon changed size to %d KiB", size>>10)
	}
	if got := l.w.Timing().MinWindow; got != 0 {
		t.Fatalf("a Timing with no Horizon took a MinWindow of %d", got)
	}
}

func TestSizeOfAWindowNotOpenIsTheCap(t *testing.T) {
	w := New(sized)
	if size := w.Size("nobody"); size != DefaultWindow {
		t.Fatalf("Size of a window never opened is %d, want the %d cap", size, DefaultWindow)
	}
	if w.Len() != 0 {
		t.Fatal("Size opened a window")
	}
}

func TestASizedWindowStartsSmallAndAFastLegGrowsItToTheCap(t *testing.T) {
	// What a new window hands the relay before it has measured anything is
	// queued however slow the leg; a fast leg pays for starting small with
	// about one rate interval.
	l := newLeg(t, sized, 1_000_000, 50*time.Millisecond)
	if size := l.w.Size(l.key); size != firstSizedWindow {
		t.Fatalf("a sized window starts at %d KiB, not %d", size>>10, firstSizedWindow>>10)
	}
	l.run(3*time.Second, 5*time.Millisecond)
	if size := l.w.Size(l.key); size != DefaultWindow {
		t.Fatalf("3 s on a 1 MB/s leg left the window at %d KiB, under its %d KiB cap", size>>10, DefaultWindow>>10)
	}
}

// The leg the gate measured on the #49 branch: some 60 kB/s from Sber's SFU to
// the receiver, 0.3 s under the queue at best, and echoes that come back
// anywhere up to 3 s later than that, in bursts. A window sized by the last
// second's rate and the shortest echo it ever saw shrank until the window
// itself was what slowed the leg, and the rate it then measured shrank it
// again: 16 KiB and 16 kB/s moved, on this leg. The window has to hold what
// the leg can carry.
func TestABurstyLegDoesNotCollapseASizedWindow(t *testing.T) {
	const burstyLeg = 60_000.0
	l := newLeg(t, sized, burstyLeg, 300*time.Millisecond)
	l.jitter, l.seed = 3*time.Second, 49
	l.settle()

	from, least := l.drained, l.w.Size(l.key)
	for range 600 {
		l.run(100*time.Millisecond, 10*time.Millisecond)
		least = min(least, l.w.Size(l.key))
	}
	rate := (l.drained - from) / 60
	t.Logf("%.0f kB/s leg with up to 3 s of echo jitter: the window's least %d KiB, %.1f kB/s moved",
		burstyLeg/1000, least>>10, rate/1000)
	if rate < burstyLeg*0.8 {
		t.Fatalf("a %.0f kB/s leg with bursty echoes moved %.1f kB/s under a sized window, under 80 %% of it",
			burstyLeg/1000, rate/1000)
	}
	if least < DefaultWindow/3 {
		t.Fatalf("the window fell to %d KiB on a %.0f kB/s leg: it measured its own size, not the leg",
			least>>10, burstyLeg/1000)
	}
}
