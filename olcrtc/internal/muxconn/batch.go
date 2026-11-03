package muxconn

import (
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// ai-generated: the whole file (write batching for olcrtc#11).

// batcher gathers the small smux frames written close together into one
// record, so that a burst of them reaches the relay as a few messages rather
// than hundreds. A link asks for it with transport.Features.WriteInterval.
//
// The Jitsi bridge queues at most 50 messages per sender and drops the
// oldest when its forwarding falls behind. A burst of new streams is a SYN,
// a CONNECT, window updates, an ack and FINs per stream on the two sides;
// sent one frame per message, it overflowed that queue and lost the acks of
// dozens of streams at once. smux reads records as one byte stream, so a
// record that carries several whole frames needs nothing new from the peer.
//
// A frame joins only once the link can send and the peer's key is known,
// the wait every Write makes (Conn.Write), so a batched Write returns no
// sooner than an unbatched one could have sent. A frame never waits in the
// batch when the last record left an interval ago. A batch that reaches half
// a record leaves at once, and the Write that made it so returns only once
// it has gone to the link, as every Write did before: bulk data is not held
// here in front of the link's own queue, and a stream behind it keeps its
// place in smux's round robin. Only small frames that follow each other
// closely share a record, which then leaves one interval after the last.
type batcher struct {
	interval time.Duration
	limit    int // the plaintext one record may carry

	mu      sync.Mutex
	pending []byte
	spare   []byte
	blocked bool   // a writer waits for room
	taken   uint64 // batches the flusher has taken
	sent    uint64 // batches the flusher has handed to the link
	err     error  // why the last record failed; every later write reports it

	kick   chan struct{} // buffered(1): pending grew or a writer waits
	moved  chan struct{} // buffered(1): a batch was taken or sent
	failed chan struct{} // closed once err is set
}

// newBatcher returns nil when the link wants every frame sent on its own.
func newBatcher(features transport.Features) *batcher {
	limit := features.MaxPayloadSize - crypto.WireOverhead
	if features.WriteInterval <= 0 || limit <= 0 {
		return nil
	}
	return &batcher{
		interval: features.WriteInterval,
		limit:    limit,
		kick:     make(chan struct{}, 1),
		moved:    make(chan struct{}, 1),
		failed:   make(chan struct{}),
	}
}

func signal(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

// add queues one frame, waiting while the batch has no room for it, and
// until its record has gone when the frame makes the batch due. A frame
// never shares a record it does not fit in.
func (b *batcher) add(p []byte, closed <-chan struct{}) (int, error) {
	for {
		select {
		case <-closed:
			return 0, ErrClosed
		default:
		}
		queued, batch, err := b.append(p)
		switch {
		case err != nil:
			return 0, err
		case queued && batch == 0:
			return len(p), nil
		case queued:
			return len(p), b.waitSent(batch, closed)
		}
		if err := b.waitMoved(closed); err != nil {
			return 0, err
		}
	}
}

// append puts p in the batch when it fits. It reports whether it did and,
// when the batch is now due, the number the batch will leave under.
func (b *batcher) append(p []byte) (bool, uint64, error) {
	b.mu.Lock()
	defer signal(b.kick)
	defer b.mu.Unlock()
	if b.err != nil {
		return false, 0, b.err
	}
	fits := len(b.pending) == 0 || len(b.pending)+len(p) <= b.limit
	b.blocked = !fits
	if !fits {
		return false, 0, nil
	}
	b.pending = append(b.pending, p...)
	if len(b.pending) < b.limit/2 {
		return true, 0, nil
	}
	return true, b.taken + 1, nil
}

// waitMoved waits for the flusher to take or send a batch.
func (b *batcher) waitMoved(closed <-chan struct{}) error {
	select {
	case <-b.moved:
		return nil
	case <-b.failed:
		return nil // the next append reports the failure
	case <-closed:
		return ErrClosed
	}
}

// waitSent waits until batch has gone to the link, and reports why it did
// not when it never will.
func (b *batcher) waitSent(batch uint64, closed <-chan struct{}) error {
	for {
		b.mu.Lock()
		sent, err := b.sent, b.err
		b.mu.Unlock()
		if sent >= batch {
			return nil
		}
		if err != nil {
			return err
		}
		if err := b.waitMoved(closed); err != nil {
			return err
		}
	}
}

// due reports whether the batch should leave without waiting out the
// interval: a writer waits for room, or the batch is half a record long.
func (b *batcher) due() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.blocked || len(b.pending) >= b.limit/2
}

// waitTurn holds the flusher until an interval has passed since the last
// record or the batch is due. It reports false once the conn closes.
func (b *batcher) waitTurn(last time.Time, closed <-chan struct{}) bool {
	for {
		wait := b.interval - time.Since(last)
		if wait <= 0 || b.due() {
			return true
		}
		timer := time.NewTimer(wait)
		select {
		case <-timer.C:
		case <-b.kick:
			timer.Stop()
		case <-closed:
			timer.Stop()
			return false
		}
	}
}

// take hands the batch to the flusher and gives writers an empty one. With
// nothing pending (the frames of a late kick left with the last record) it
// returns nil and keeps the buffers as they are.
func (b *batcher) take() []byte {
	b.mu.Lock()
	record := b.pending
	if len(record) == 0 {
		b.mu.Unlock()
		return nil
	}
	b.pending = b.spare[:0]
	b.spare = nil
	b.blocked = false
	b.taken++
	b.mu.Unlock()
	signal(b.moved)
	return record
}

// done records that the batch taken last has gone and keeps its buffer for
// the one after next.
func (b *batcher) done(record []byte) {
	b.mu.Lock()
	b.spare = record[:0]
	b.sent = b.taken
	b.mu.Unlock()
	signal(b.moved)
}

// fail makes every later write report err and wakes the writers that wait.
// The flusher calls it once, on its way out.
func (b *batcher) fail(err error) {
	b.mu.Lock()
	b.err = err
	b.mu.Unlock()
	close(b.failed)
}

// startBatching turns on write batching when the link asks for it.
func (c *Conn) startBatching(features transport.Features) {
	c.batch = newBatcher(features)
	if c.batch != nil {
		go c.flushLoop()
	}
}

// flushLoop seals and sends what the batcher gathers, one record at a time,
// until the conn closes or a send fails.
func (c *Conn) flushLoop() {
	b := c.batch
	var last time.Time
	for {
		select {
		case <-b.kick:
		case <-c.closeCh:
			return
		}
		if !b.waitTurn(last, c.closeCh) {
			return
		}
		record := b.take()
		if len(record) == 0 {
			continue
		}
		_, err := c.writeRecord(record)
		last = time.Now()
		if err != nil {
			b.fail(err)
			return
		}
		b.done(record)
	}
}
