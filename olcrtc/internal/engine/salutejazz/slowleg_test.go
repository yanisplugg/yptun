package salutejazz

import (
	"bytes"
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: the whole file (the slow leg of the fake SFU, olcrtc#49).

// The slow leg is the fake's model of what every failing SaluteJazz gate run
// had between the SFU and one receiver: a path of 34-47 kB/s behind a queue
// with no bound (see fakeLeg). The helpers below are what the tests on it
// share: a session that tallies what reaches it, and a writer that pushes a
// load through a session as fast as the session lets it.

const (
	// slowLegRecord is one full frame of the byte stream: the datachannel
	// transport's largest payload, which a busy smux session fills.
	slowLegRecord = 12 * 1024
	// slowLegSettle is how long a writer has to make no progress before a
	// test calls it held.
	slowLegSettle = 300 * time.Millisecond
)

// taggedPayload is one test payload of size bytes. It opens with its tag - a
// kind and a sequence number - and a '|', so whatever reaches a session can
// be told apart from anything no test sent.
func taggedPayload(kind string, seq, size int) []byte {
	tag := fmt.Sprintf("%s-%08d|", kind, seq)
	payload := bytes.Repeat([]byte{'x'}, max(size, len(tag)))
	copy(payload, tag)
	return payload
}

// tally is what one session's callbacks received.
type tally struct {
	mu        sync.Mutex
	stream    int
	datagrams int
	// arrived is when each tagged payload arrived, by tag.
	arrived map[string]time.Time
	// stray is the first payload that carried no tag: something reached the
	// callbacks that no test sent. Only its length is kept.
	stray int
}

func (c *tally) note(lane string, payload []byte) {
	tag, _, tagged := bytes.Cut(payload, []byte{'|'})
	c.mu.Lock()
	defer c.mu.Unlock()
	if !tagged {
		if c.stray == 0 {
			c.stray = len(payload) + 1
		}
		return
	}
	if lane == "datagram" {
		c.datagrams += len(payload)
	} else {
		c.stream += len(payload)
	}
	c.arrived[string(tag)] = time.Now()
}

func (c *tally) streamBytes() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.stream
}

func (c *tally) datagramBytes() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.datagrams
}

// arrival is when the payload tagged kind-seq arrived, and whether it has.
func (c *tally) arrival(kind string, seq int) (time.Time, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	at, ok := c.arrived[fmt.Sprintf("%s-%08d", kind, seq)]
	return at, ok
}

// strayLength is the length of the first untagged payload, or -1 for none.
func (c *tally) strayLength() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.stray - 1
}

// connectTally joins the fake with both per-peer callbacks tallying into one
// tally. The callbacks never block: the leg is the only brake a test on it
// wants. setup runs on the session before it connects.
func connectTally(t *testing.T, url, name string, setup ...func(*Session)) (*Session, *tally) {
	t.Helper()
	got := &tally{arrived: make(map[string]time.Time)}
	created, err := New(context.Background(), engine.Config{
		URL: url, Token: "passw0rd", Name: name,
		Extra:          map[string]string{"roomID": "abc123"},
		OnPeerData:     func(_ string, d []byte) { got.note("stream", d) },
		OnPeerDatagram: func(_ string, d []byte) { got.note("datagram", d) },
	})
	if err != nil {
		t.Fatal(err)
	}
	sess := created.(*Session)
	t.Cleanup(func() { _ = sess.Close() })
	for _, apply := range setup {
		apply(sess)
	}
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect %s: %v", name, err)
	}
	return sess, got
}

// waitForRoom waits until every session names every other one and has both
// lanes open.
func waitForRoom(t *testing.T, sessions ...*Session) {
	t.Helper()
	for _, s := range sessions {
		waitFor(t, 5*time.Second, "the roster to name everyone and both lanes to open", func() bool {
			return len(s.remoteIdentities()) == len(sessions)-1 && s.CanSend() && s.DatagramCanSend()
		})
	}
}

// bulkWriter pushes a load through one send function, one tagged frame at a
// time, as fast as the send returns.
type bulkWriter struct {
	sent atomic.Int64
	// longest is the longest a single send took, in nanoseconds: how long
	// the writer was held at most.
	longest atomic.Int64
	stop    chan struct{}
	done    chan error
}

// startBulk writes total bytes in frames of size through send. It stops early
// on the first error, which done carries, or when stop is closed.
func startBulk(send func([]byte) error, kind string, total, size int) *bulkWriter {
	w := &bulkWriter{stop: make(chan struct{}), done: make(chan error, 1)}
	go func() {
		for seq := 0; seq*size < total; seq++ {
			select {
			case <-w.stop:
				w.done <- nil
				return
			default:
			}
			began := time.Now()
			if err := send(taggedPayload(kind, seq, size)); err != nil {
				w.done <- err
				return
			}
			if took := int64(time.Since(began)); took > w.longest.Load() {
				w.longest.Store(took)
			}
			w.sent.Add(1)
		}
		w.done <- nil
	}()
	return w
}

// halt ends the writer at its next frame, and is safe to call twice.
func (w *bulkWriter) halt() {
	select {
	case <-w.stop:
	default:
		close(w.stop)
	}
}

// waitHeld waits until the writer has made no progress for slowLegSettle
// while it still has frames to write, and returns how many it had sent.
func (w *bulkWriter) waitHeld(t *testing.T, what string) int64 {
	t.Helper()
	var held int64
	waitFor(t, 20*time.Second, what, func() bool {
		select {
		case err := <-w.done:
			t.Fatalf("%s: the writer finished instead (%v) after %d frames", what, err, w.sent.Load())
		default:
		}
		before := w.sent.Load()
		time.Sleep(slowLegSettle)
		held = w.sent.Load()
		return held == before
	})
	return held
}

// finish waits for the writer to end and returns what it ended on.
func (w *bulkWriter) finish(t *testing.T, timeout time.Duration, what string) error {
	t.Helper()
	select {
	case err := <-w.done:
		return err
	case <-time.After(timeout):
		t.Fatalf("%s: the writer was still going after %s, at %d frames", what, timeout, w.sent.Load())
		return nil
	}
}

// TestTheSlowLegQueuesWithoutALimitAndDrainsAtItsRate pins the fake's model
// of a LiveKit 1.5.3 leg, which every window test below stands on: the sender
// is never held however slow the leg (the SFU takes everything at once), what
// the leg cannot carry yet waits in a queue that grows to the whole load, the
// load arrives no faster than the leg's rate, and a held leg carries nothing
// until it is given a rate.
func TestTheSlowLegQueuesWithoutALimitAndDrainsAtItsRate(t *testing.T) {
	url, fake := newFakeConnector(t)
	sender, _ := connectTally(t, url, "sender")
	receiver, got := connectTally(t, url, "receiver")
	waitForRoom(t, sender, receiver)
	to := receiver.localIdentity()

	const (
		rate  = 100_000
		total = 25 * slowLegRecord
	)
	fake.slowLeg(to, rate)
	start := time.Now()
	writer := startBulk(func(p []byte) error { return sender.SendTo(to, p) }, "bulk", total, slowLegRecord)
	select {
	case err := <-writer.done:
		if err != nil {
			t.Fatalf("a send on a slow leg = %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("the sender was held after %d frames: the fake stopped reading", writer.sent.Load())
	}
	// Everything the sender wrote is queued at once, less what the leg has
	// carried in the meantime.
	waitFor(t, 5*time.Second, "the leg to hold the load", func() bool {
		return fake.peakQueuedTo(to) >= total-2*slowLegRecord
	})
	waitFor(t, 10*time.Second, "the load to arrive", func() bool { return got.streamBytes() == total })
	took := time.Since(start)
	want := time.Duration(total) * time.Second / rate
	if took < want*9/10 {
		t.Fatalf("%d bytes crossed a %d B/s leg in %s, want at least %s", total, rate, took, want*9/10)
	}
	if queued := fake.queuedTo(to); queued != 0 {
		t.Fatalf("the leg still holds %d bytes after delivering everything", queued)
	}

	// A held leg carries nothing, and gives it all up once it has a rate.
	fake.slowLeg(to, 0)
	if err := sender.SendTo(to, taggedPayload("held", 0, 64)); err != nil {
		t.Fatal(err)
	}
	time.Sleep(slowLegSettle)
	if _, ok := got.arrival("held", 0); ok {
		t.Fatal("a packet crossed a held leg")
	}
	if fake.queuedTo(to) == 0 {
		t.Fatal("a held leg does not hold the packet sent into it")
	}
	fake.slowLeg(to, rate)
	waitFor(t, 5*time.Second, "the held packet once the leg moves", func() bool {
		_, ok := got.arrival("held", 0)
		return ok
	})
	if stray := got.strayLength(); stray >= 0 {
		t.Fatalf("the receiver was handed a %d byte payload no test sent", stray)
	}
	if failure := fake.lastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}
