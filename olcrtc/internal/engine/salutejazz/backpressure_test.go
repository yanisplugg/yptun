package salutejazz

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

// The back-pressure tests. pion's DataChannel.Send never blocks - it appends
// to an SCTP pending queue with no bound - so the only thing between a writer
// and unbounded memory is what this engine does at the high-water mark. The
// fake holds its relay, which stops the peer reading, closes the receive
// window and leaves the client's own queue to grow: that is the state every
// assertion below is about.

const (
	// backPressurePayload is one fill write. It is the size of a message the
	// byte stream really carries, well under the SCTP message limit.
	backPressurePayload = 12 * 1024
	// backPressureFillLimit bounds a fill loop, so a lane that never reaches
	// its mark fails the test instead of writing until the machine gives up.
	// It covers the peer's receive window and the mark on top of it several
	// times over.
	backPressureFillLimit = 1024
	// backPressureSettle is how long a writer has to make progress before a
	// test calls it parked.
	backPressureSettle = 200 * time.Millisecond
)

// TestBackPressureClosesTheLanesWhenThePeerStopsReading covers both lanes at
// the mark: the predicates the layer above asks before it writes say no, a
// datagram is dropped rather than queued behind a peer that is not reading,
// and a Send parks until there is room and then goes through.
func TestBackPressureClosesTheLanesWhenThePeerStopsReading(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "backpressure")
	waitFor(t, 5*time.Second, "both lanes open", func() bool {
		return sess.CanSend() && sess.DatagramCanSend()
	})
	release := fake.holdForward()
	t.Cleanup(release)

	payload := make([]byte, backPressurePayload)
	writer := fillReliableLane(t, sess, payload)
	if sess.CanSend() {
		t.Fatal("the reliable lane reports it can send while a writer is parked over its budget")
	}

	// The lossy lane is the other half: it never parks, it drops.
	fillLossyLane(t, sess, payload)
	if sess.DatagramCanSend() {
		t.Fatal("the lossy lane reports it can send while it is over its budget")
	}
	dropped(t, sess, payload)

	// One more write ends the writer, so the assertion below is about the
	// Send that is parked right now and not about the ones after it.
	parked := writer.writes.Load()
	close(writer.stop)
	release()
	select {
	case err := <-writer.done:
		if err != nil {
			t.Fatalf("the parked Send = %v, want it to go through once the lane drained", err)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("the parked Send was never woken after the peer started reading again")
	}
	if resumed := writer.writes.Load(); resumed <= parked {
		t.Fatalf("the writer returned without sending: %d writes before the park, %d after", parked, resumed)
	}
	waitFor(t, 30*time.Second, "both lanes to come back under their budget", func() bool {
		return sess.CanSend() && sess.DatagramCanSend()
	})
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// TestAParkedSendIsReleasedWhenTheAttemptEnds is the other way out of the
// park: the queue never drains, and the connection attempt the sender is
// parked on ends. pion runs the low-water callback only while the channel is
// open, so for a channel it is closing the wake never comes, and a sender
// left waiting there is a tunnel goroutine that never returns.
func TestAParkedSendIsReleasedWhenTheAttemptEnds(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "parked")
	waitFor(t, 5*time.Second, "both lanes open", func() bool {
		return sess.CanSend() && sess.DatagramCanSend()
	})
	release := fake.holdForward()
	t.Cleanup(release)

	writer := fillReliableLane(t, sess, make([]byte, backPressurePayload))

	go sess.teardown(sess.current())
	select {
	case err := <-writer.done:
		if !errors.Is(err, ErrSessionClosed) {
			t.Fatalf("the parked Send = %v, want %v", err, ErrSessionClosed)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("a Send parked on an attempt that ended never returned")
	}
}

// laneWriter is a Send loop a test starts on the reliable lane. It fills the
// lane until the lane parks it, and it ends on the first write after stop is
// closed - so what done carries is the verdict of the Send that was parked.
type laneWriter struct {
	writes atomic.Int64
	stop   chan struct{}
	done   chan error
}

// fillReliableLane writes until the lane holds more than one high-water mark
// and parks the writer. It hands back the writer, still parked.
func fillReliableLane(t *testing.T, sess *Session, payload []byte) *laneWriter {
	t.Helper()
	writer := &laneWriter{stop: make(chan struct{}), done: make(chan error, 1)}
	go func() {
		for range backPressureFillLimit {
			if err := sess.Send(payload); err != nil {
				writer.done <- err
				return
			}
			writer.writes.Add(1)
			select {
			case <-writer.stop:
				writer.done <- nil
				return
			default:
			}
		}
		writer.done <- nil
	}()
	waitFor(t, 30*time.Second, "the reliable lane to park its writer over the mark", func() bool {
		before := writer.writes.Load()
		time.Sleep(backPressureSettle)
		return !sess.CanSend() && writer.writes.Load() == before
	})
	select {
	case err := <-writer.done:
		t.Fatalf("the writer ran to its limit instead of parking over the mark: %v", err)
	default:
	}
	return writer
}

// fillLossyLane writes datagrams until the lane's own predicate says it is
// over its budget. Every write is issued while the predicate still says yes,
// so none of them is dropped: what the loop leaves behind is a lane exactly
// over its mark.
func fillLossyLane(t *testing.T, sess *Session, payload []byte) {
	t.Helper()
	for range backPressureFillLimit {
		if !sess.DatagramCanSend() {
			return
		}
		if err := sess.SendDatagram(payload); err != nil {
			t.Fatalf("filling the lossy lane: %v", err)
		}
	}
	t.Fatal("the lossy lane never reached its high-water mark")
}

// dropped writes on a lossy lane that is already over its budget and checks
// that every one of those writes was thrown away. A datagram that cannot go
// now is worth nothing later, so none of them is an error and none of them
// waits.
func dropped(t *testing.T, sess *Session, payload []byte) {
	t.Helper()
	const writes = 32
	gen := sess.current()
	before := gen.lossyDrops.Load()
	for range writes {
		if err := sess.SendDatagram(payload); err != nil {
			t.Fatalf("a datagram on a lane over its budget = %v, want it dropped", err)
		}
	}
	if got := gen.lossyDrops.Load() - before; got != writes {
		t.Fatalf("the lossy lane dropped %d of the %d datagrams written over its mark", got, writes)
	}
}
