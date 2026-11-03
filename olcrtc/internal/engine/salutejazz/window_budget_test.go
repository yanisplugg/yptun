package salutejazz_test

import (
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/engine/salutejazz"
	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: the whole file (the relay window's budget, olcrtc#49).

// The relay window's size answers to numbers that were chosen in other places
// for other reasons: the tunnel's pong timeout, the lane's high-water mark,
// the gate's connect budget and its SaluteJazz throughput floors. Each test
// below writes one of those relations down and reads those numbers where they
// live, so a change on either side that breaks it fails here rather than on a
// slow Sber leg. They are tests of the external package because the gate
// reaches this engine through the client, so this package cannot import it.
//
// Two kinds of input are not read where they live. The record is
// SlowLegRecord, this package's copy of the datachannel transport's largest
// payload (defaultMaxPayloadSize, which that package does not export): a
// change there has to be carried here by hand. The leg rates and round trips
// are measurements, written down with where each comes from.

// budgetProvider is the name the gate judges a SaluteJazz pair under. A name
// the gate does not know gets the target's own floors, which the window does
// not carry twice of, so a stale name fails the floor test rather than passing
// it.
const budgetProvider = "salutejazz"

// legTime is how long a leg that carries rate bytes a second takes over n
// bytes.
func legTime(n, rate int) time.Duration {
	return time.Duration(n) * time.Second / time.Duration(rate)
}

// TestTwoWindowsAndARoundTripFitThePongTimeout is what sizes the window. A
// control ping waits behind up to a window on its way and its pong behind up
// to a window on the way back, and the slowest SFU-to-receiver leg a failing
// gate run showed carried 34 kB/s. Two windows at that rate and a round trip
// have to fit in the pong timeout with a second to spare: this is what rules
// out 256 KiB.
func TestTwoWindowsAndARoundTripFitThePongTimeout(t *testing.T) {
	const (
		slowestLeg = 34_000 // bytes a second
		roundTrip  = 500 * time.Millisecond
		spare      = time.Second
	)
	late := legTime(2*salutejazz.RelayWindow, slowestLeg) + roundTrip
	if limit := control.DefaultTimeout - spare; late > limit {
		t.Fatalf("a pong behind two %d KiB windows on a %d kB/s leg comes back after %s, "+
			"over the %s a %s pong timeout leaves", salutejazz.RelayWindow>>10, slowestLeg/1000,
			late.Round(time.Millisecond), limit, control.DefaultTimeout)
	}
}

// TestASizedWindowKeepsAPongInsideTheTimeoutOnTheSlowestLeg is the guarantee
// the sizing gives on a leg slower than the one relayWindow was weighed
// against: the engine gate on 7b78fd4a measured 26 kB/s from the SFU to the
// receiver (run 35862192631), where two whole windows are 15 s. A sized
// window lets relayHorizon of the leg's best recent rate be in flight, and a
// record past it, never less than its floor; two of those and a round trip
// have to fit the pong timeout with a second to spare.
func TestASizedWindowKeepsAPongInsideTheTimeoutOnTheSlowestLeg(t *testing.T) {
	const (
		slowestLeg = 26_000 // bytes a second
		roundTrip  = 500 * time.Millisecond
		spare      = time.Second
	)
	queue := max(salutejazz.RelayHorizon+legTime(salutejazz.SlowLegRecord, slowestLeg),
		legTime(salutejazz.RelayMinWindow, slowestLeg))
	late := 2*queue + roundTrip
	if limit := control.DefaultTimeout - spare; late > limit {
		t.Fatalf("a pong behind two sized windows on a %d kB/s leg comes back after %s, "+
			"over the %s a %s pong timeout leaves", slowestLeg/1000,
			late.Round(time.Millisecond), limit, control.DefaultTimeout)
	}
	// And it is what the sizing is for: the fixed window on this leg does not
	// fit.
	if fixed := legTime(2*salutejazz.RelayWindow, slowestLeg) + roundTrip; fixed <= control.DefaultTimeout-spare {
		t.Fatalf("two fixed %d KiB windows on a %d kB/s leg come back after %s, inside the timeout: "+
			"the leg is not the one the sizing is weighed on", salutejazz.RelayWindow>>10, slowestLeg/1000,
			fixed.Round(time.Millisecond))
	}
}

// TestTheWindowFitsUnderTheLanesMark: the lane's high-water mark is what the
// publisher channel may hold for every destination at once, and the window
// what one destination may have in flight. A window over the mark would let
// one destination fill the shared lane on a slow first hop, and every other
// destination's sends would be held at the lane before its own window ever
// held it.
func TestTheWindowFitsUnderTheLanesMark(t *testing.T) {
	if salutejazz.RelayWindow > salutejazz.LaneHighWaterMark {
		t.Fatalf("the relay window is %d KiB, over the %d KiB high-water mark of the lane it shares",
			salutejazz.RelayWindow>>10, salutejazz.LaneHighWaterMark>>10)
	}
}

// TestAConnectOnTopOfSixPullsFitsTheConnectBudget is S2's connect on top: six
// pulls in flight, and the connects made beside them judged at the gate's
// connect p95. A new stream's reply waits behind the window toward its client
// and one record from each pull that smux's round robin puts ahead of it. On
// a leg at the ~3.2 Mbit/s the phase-0 spike measured through Sber's relay,
// with 0.8 s for the connect's own round trips and the server's dial, that
// has to stay under the budget. Without a window it was the whole queue,
// about 3 MiB in mobile S2, and the gate measured the connects' 95th
// percentile at 8.7 and 10.6 s.
func TestAConnectOnTopOfSixPullsFitsTheConnectBudget(t *testing.T) {
	const (
		pulls      = 6
		leg        = 400_000 // bytes a second
		connectRTT = 800 * time.Millisecond
	)
	budget := gate.Local.For(budgetProvider).ConnectP95
	took := legTime(salutejazz.RelayWindow+pulls*salutejazz.SlowLegRecord, leg) + connectRTT
	if took >= budget {
		t.Fatalf("a connect behind a %d KiB window and %d records on a %d kB/s leg takes %s, "+
			"not under the gate's %s", salutejazz.RelayWindow>>10, pulls, leg/1000,
			took.Round(time.Millisecond), budget)
	}
}

// TestTheWindowCarriesTwiceTheThroughputFloor: a sender holds at most a
// window toward one destination, and an echo answers only a mark, one every
// relayMarkEvery, so up to that much of the window is always waiting for the
// next mark: it carries about 7/8 of a window a round trip. At the 0.35 s an
// end-to-end round trip through Sber's relay takes, that has to be at least
// twice the higher of the gate's two SaluteJazz floors, as the window carries
// each direction on its own: the window is then never what holds a healthy
// leg under a floor.
func TestTheWindowCarriesTwiceTheThroughputFloor(t *testing.T) {
	const roundTrip = 350 * time.Millisecond
	judged := gate.Local.For(budgetProvider)
	floor := max(judged.ThroughputDownBps, judged.ThroughputUpBps) // bits a second
	carries := float64(salutejazz.RelayWindow-salutejazz.RelayMarkEvery) * 8 / roundTrip.Seconds()
	if carries < 2*floor {
		t.Fatalf("a %d KiB window carries %.2f Mbit/s at a %s round trip, under twice the %.1f Mbit/s floor",
			salutejazz.RelayWindow>>10, carries/1e6, roundTrip, floor/1e6)
	}
}
