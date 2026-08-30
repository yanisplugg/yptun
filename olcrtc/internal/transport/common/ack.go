package common

import "sync"

// AckTracker tracks per-fragment acknowledgements for in-flight Send calls.
// Each Send registers a waiter keyed by sequence number with the total
// fragment count; the receive loop calls Mark(seq, crc, fragIdx) when an ack
// arrives, and Send retransmits whatever Pending() still reports.
//
// Per-fragment accounting is the general case, not an optimisation: the video
// transports are lossy at the fragment level (every fragment is a separate
// encoded video frame that can be corrupted past ECC recovery). Whole-message
// ack semantics forced a full retransmit of an entire message on any single
// fragment loss, which under load piled fragments into the outbound queue and
// eventually killed the encoder. A single-fragment message (total == 1) gives
// exactly the whole-message semantics back, so this one tracker serves both.
type AckTracker struct {
	mu      sync.Mutex
	pending map[uint32]*AckWaiter
}

// AckWaiter is the per-message acknowledgement state handed to a sender.
type AckWaiter struct {
	mu        sync.Mutex
	crc       uint32
	total     int
	acked     []bool
	remaining int
	notify    chan struct{}
}

// NewAckTracker creates an empty tracker.
func NewAckTracker() *AckTracker {
	return &AckTracker{pending: make(map[uint32]*AckWaiter)}
}

// Register installs a waiter for (seq, crc) covering total fragments and
// returns it. The caller must drop it via Unregister.
func (t *AckTracker) Register(seq, crc uint32, total int) *AckWaiter {
	w := &AckWaiter{
		crc:       crc,
		total:     total,
		acked:     make([]bool, total),
		remaining: total,
		notify:    make(chan struct{}, 1),
	}
	t.mu.Lock()
	t.pending[seq] = w
	t.mu.Unlock()
	return w
}

// Unregister drops the waiter for seq.
func (t *AckTracker) Unregister(seq uint32) {
	t.mu.Lock()
	delete(t.pending, seq)
	t.mu.Unlock()
}

// Mark records that fragIdx of seq is acknowledged. crc must match the
// waiter's crc, otherwise the ack is ignored (it is from an older message
// whose seq was reused). Returns true iff this call actually flipped a
// previously-unacked fragment.
func (t *AckTracker) Mark(seq, crc uint32, fragIdx int) bool {
	t.mu.Lock()
	w, ok := t.pending[seq]
	t.mu.Unlock()
	if !ok {
		return false
	}
	w.mu.Lock()
	if w.crc != crc || fragIdx < 0 || fragIdx >= w.total || w.acked[fragIdx] {
		w.mu.Unlock()
		return false
	}
	w.acked[fragIdx] = true
	w.remaining--
	w.mu.Unlock()
	select {
	case w.notify <- struct{}{}:
	default:
	}
	return true
}

// Pending returns the indexes of fragments still unacked.
func (w *AckWaiter) Pending() []int {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]int, 0, w.remaining)
	for i, ok := range w.acked {
		if !ok {
			out = append(out, i)
		}
	}
	return out
}

// Done reports whether every fragment has been acked.
func (w *AckWaiter) Done() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.remaining == 0
}

// Notify returns the channel that ticks on every Mark.
func (w *AckWaiter) Notify() <-chan struct{} {
	return w.notify
}
