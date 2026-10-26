package common

// ai-generated: the whole file (the window of messages a sender keeps in
// flight, the retransmit clock that no longer waits out a fixed ack budget,
// and the pace that keeps the stream inside what an SFU forwards).

import (
	"hash/crc32"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"time"
)

// Window pacing and retransmission constants. The rates are bytes per second.
const (
	// DefaultWindowMessages and DefaultWindowBytes bound what one sender
	// keeps unacknowledged. A relay carries a few hundred milliseconds of
	// round trip, so half a megabyte covers a megabyte a second there and
	// still bounds what a stalled peer can pin down in memory.
	DefaultWindowMessages = 64
	DefaultWindowBytes    = 512 << 10

	// DefaultInitialRate is what a fresh window writes before it has
	// learned anything: 2 Mbit/s, which every relay measured forwards and
	// no receiver's bandwidth estimate cuts off.
	DefaultInitialRate = 256 << 10
	// DefaultMinRate is the floor a window falls to when the path goes
	// dark. An SFU that stopped forwarding a stream for being over the
	// receiver's budget takes it back only once the stream is under it
	// again, so the floor has to be small.
	DefaultMinRate = 32 << 10
	// DefaultMaxRate caps the pace. seichannel is the slow, hard-to-block
	// transport, not the fast one, and 8 Mbit/s of SEI is already more
	// than any tunnel on it asks for.
	DefaultMaxRate = 1 << 20

	// DefaultGiveUp is how long the oldest unacknowledged message may go
	// before Send fails and the session above rebuilds. It matches the
	// write deadline muxconn uses, so the two agree on when a link is dead.
	DefaultGiveUp = 30 * time.Second

	// growEvery is how often a pace that held data back raises its rate by
	// an eighth while nothing is lost: from the initial rate to the cap in
	// about three seconds.
	growEvery = 250 * time.Millisecond
	// growFactor and cutFactor are the pace's multiplicative increase and
	// decrease.
	growFactor = 1.125
	cutFactor  = 0.75
	// paceBurst is how much credit a window may bank, in time at its rate,
	// so a lull does not turn into a burst the relay drops.
	paceBurst = 40 * time.Millisecond

	// minRTO and maxRTO bound the retransmission timer. The floor covers a
	// writer tick on each side plus the relay; the ceiling keeps a dark
	// path probed.
	minRTO = 250 * time.Millisecond
	maxRTO = 4 * time.Second
	// maxBackoff bounds the exponential backoff of repeated retransmissions
	// of one fragment.
	maxBackoff = 8
	// reorderFloor is the shortest reordering window loss detection allows
	// before it calls a fragment lost because later ones were acknowledged.
	reorderFloor = 20 * time.Millisecond
	// lostAfterAcks is how many fragments sent after one of ours may be
	// acknowledged before that one counts as lost. A tick writes its
	// fragments at one instant, so time alone cannot tell a hole inside a
	// tick from a fragment still on its way; the order they went out in
	// can. Two is enough on a path that keeps order, which every SFU here
	// does, and a spurious retransmission costs one fragment.
	lostAfterAcks = 2
	// blackout is how long a window with data outstanding may go without a
	// single acknowledgement before it drops to the floor rate: long enough
	// that a burst of loss on a slow relay does not trigger it.
	blackout = 3 * time.Second
)

// WindowConfig describes one sender's window.
type WindowConfig struct {
	// Role and Binding are stamped into every frame.
	Role    byte
	Binding uint32
	// FragmentSize is the payload one frame carries.
	FragmentSize int
	// Messages and Bytes bound what stays unacknowledged; zero takes the
	// defaults.
	Messages int
	Bytes    int
	// InitialRate, MinRate and MaxRate bound the pace, in bytes per second;
	// zero takes the defaults.
	InitialRate, MinRate, MaxRate float64
	// GiveUp is how long the oldest unacknowledged message may go before
	// Send reports ErrAckTimeout; zero takes the default.
	GiveUp time.Duration
	// Now is the clock; nil is time.Now. Tests drive it.
	Now func() time.Time
}

func (c WindowConfig) withDefaults() WindowConfig {
	if c.FragmentSize <= 0 {
		c.FragmentSize = 900
	}
	if c.Messages <= 0 {
		c.Messages = DefaultWindowMessages
	}
	if c.Bytes <= 0 {
		c.Bytes = DefaultWindowBytes
	}
	if c.InitialRate <= 0 {
		c.InitialRate = DefaultInitialRate
	}
	if c.MinRate <= 0 {
		c.MinRate = DefaultMinRate
	}
	if c.MaxRate <= 0 {
		c.MaxRate = DefaultMaxRate
	}
	if c.GiveUp <= 0 {
		c.GiveUp = DefaultGiveUp
	}
	if c.Now == nil {
		c.Now = time.Now
	}
	return c
}

// windowMsg is one message of the window: its fragments, what of it the peer
// has acknowledged and when each fragment last went out.
type windowMsg struct {
	seq, crc  uint32
	total     int
	data      []byte
	fragSize  int
	acked     []bool
	lost      []bool
	sentAt    []time.Time
	sentOrd   []uint64
	tries     []uint8
	remaining int
	queued    time.Time
}

func (m *windowMsg) frag(i int) []byte {
	start := i * m.fragSize
	end := min(start+m.fragSize, len(m.data))
	return m.data[start:end]
}

// Window is the sending half of an ordered stream: it keeps several messages
// in flight, retransmits the fragments the peer has not acknowledged on a
// clock learned from the round trip, and paces what it writes.
//
// The transport it serves used to send one message at a time and wait for
// every fragment of it to be acknowledged, so its throughput was one smux
// frame per round trip - 7 KB per 400 ms on a relay, a tenth of what the
// relay forwards (issues #9 and #16). A window turns the round trip into
// latency instead of a divisor.
type Window struct {
	cfg  WindowConfig
	seq  *atomic.Uint32
	cond *sync.Cond

	mu     sync.Mutex
	id     uint32
	msgs   []*windowMsg
	bytes  int
	floor  uint32
	closed bool
	failed error

	srtt, rttvar time.Duration
	rack         time.Time
	lastAck      time.Time
	// sendOrd counts transmissions and ackedOrd is the highest one the
	// peer has acknowledged, which is how a hole inside one tick's burst
	// is found.
	sendOrd  uint64
	ackedOrd uint64

	rate     float64
	credit   float64
	creditAt time.Time
	lossAt   time.Time
	growAt   time.Time
	held     bool
}

// NewWindow creates a window that draws sequence numbers from seq, which it
// shares with the transport's one-at-a-time sender so an acknowledgement
// belongs to exactly one of them.
func NewWindow(cfg WindowConfig, seq *atomic.Uint32) *Window {
	cfg = cfg.withDefaults()
	w := &Window{cfg: cfg, seq: seq, id: randomStreamID(), rate: cfg.InitialRate}
	w.cond = sync.NewCond(&w.mu)
	w.floor = seq.Load() + 1
	return w
}

func randomStreamID() uint32 {
	id := rand.Uint32() //nolint:gosec // a stream id only has to differ from the last one
	if id == 0 {
		return 1
	}
	return id
}

// ID is the current stream id, which changes on every Reset.
func (w *Window) ID() uint32 {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.id
}

// InFlight reports how many messages are unacknowledged.
func (w *Window) InFlight() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.msgs)
}

// Rate is the current pace in bytes per second.
func (w *Window) Rate() float64 {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.rate
}

// SRTT is the smoothed round trip, zero until the first acknowledgement.
func (w *Window) SRTT() time.Duration {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.srtt
}

// Send copies data into the window and returns once there is room for it. It
// does not wait for the peer: the writer sends the fragments and the window
// retransmits what is not acknowledged. It fails when the transport closes,
// or with ErrAckTimeout when the oldest message has gone unacknowledged for
// the give-up window.
func (w *Window) Send(data []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	for {
		switch {
		case w.closed:
			return ErrWindowClosed
		case w.failed != nil:
			return w.failed
		case len(w.msgs) < w.cfg.Messages && (w.bytes < w.cfg.Bytes || len(w.msgs) == 0):
			w.admit(data)
			return nil
		}
		w.cond.Wait()
	}
}

// admit puts one message in the window. The caller holds the lock.
func (w *Window) admit(data []byte) {
	owned := make([]byte, len(data))
	copy(owned, data)
	frags := max((len(owned)+w.cfg.FragmentSize-1)/w.cfg.FragmentSize, 1)
	msg := &windowMsg{
		seq:       w.seq.Add(1),
		crc:       crc32.ChecksumIEEE(owned),
		total:     len(owned),
		data:      owned,
		fragSize:  w.cfg.FragmentSize,
		acked:     make([]bool, frags),
		lost:      make([]bool, frags),
		sentAt:    make([]time.Time, frags),
		sentOrd:   make([]uint64, frags),
		tries:     make([]uint8, frags),
		remaining: frags,
		queued:    w.cfg.Now(),
	}
	if len(w.msgs) == 0 {
		w.floor = msg.seq
	}
	w.msgs = append(w.msgs, msg)
	w.bytes += len(owned)
}

// Ack records one acknowledged fragment and reports whether it belonged to
// this window. An acknowledgement of a fragment that is already in moves the
// round trip estimate on nothing.
func (w *Window) Ack(seq, crc uint32, fragIdx uint16) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	msg := w.find(seq, crc)
	if msg == nil || int(fragIdx) >= len(msg.acked) {
		return false
	}
	now := w.cfg.Now()
	w.lastAck = now
	idx := int(fragIdx)
	if msg.acked[idx] {
		return true
	}
	msg.acked[idx] = true
	msg.lost[idx] = false
	msg.remaining--
	if msg.tries[idx] == 1 {
		w.sample(now.Sub(msg.sentAt[idx]))
	}
	if msg.sentAt[idx].After(w.rack) {
		w.rack = msg.sentAt[idx]
	}
	if msg.sentOrd[idx] > w.ackedOrd {
		w.ackedOrd = msg.sentOrd[idx]
	}
	if msg.remaining == 0 {
		w.retire()
	}
	return true
}

// find returns the in-flight message for (seq, crc), or nil.
func (w *Window) find(seq, crc uint32) *windowMsg {
	if len(w.msgs) == 0 {
		return nil
	}
	idx := seq - w.msgs[0].seq
	if idx >= uint32(len(w.msgs)) { //nolint:gosec // the window is far below 2^32
		return nil
	}
	msg := w.msgs[idx]
	if msg.crc != crc {
		return nil
	}
	return msg
}

// retire drops the acknowledged messages at the front of the window and
// wakes whoever waits for room. The caller holds the lock.
func (w *Window) retire() {
	n := 0
	for n < len(w.msgs) && w.msgs[n].remaining == 0 {
		w.bytes -= len(w.msgs[n].data)
		n++
	}
	if n == 0 {
		return
	}
	w.msgs = append(w.msgs[:0], w.msgs[n:]...)
	if len(w.msgs) > 0 {
		w.floor = w.msgs[0].seq
	} else {
		w.floor = w.seq.Load() + 1
	}
	w.cond.Broadcast()
}

// sample folds one round trip measurement into the estimate, as RFC 6298.
func (w *Window) sample(rtt time.Duration) {
	if rtt <= 0 {
		return
	}
	if w.srtt == 0 {
		w.srtt, w.rttvar = rtt, rtt/2
		return
	}
	diff := w.srtt - rtt
	if diff < 0 {
		diff = -diff
	}
	w.rttvar = (3*w.rttvar + diff) / 4
	w.srtt = (7*w.srtt + rtt) / 8
}

func (w *Window) rto() time.Duration {
	if w.srtt == 0 {
		return time.Second
	}
	return min(max(w.srtt+4*w.rttvar, minRTO), maxRTO)
}

// Drain writes what may go now: first the fragments loss detection marked,
// then the ones that have never gone out, both in the order they were
// queued. It stops at frames written, at the pace's credit or when emit
// refuses. It returns how many frames it wrote.
func (w *Window) Drain(frames int, emit func([]byte) bool) int {
	w.mu.Lock()
	defer w.mu.Unlock()
	now := w.cfg.Now()
	w.detectLoss(now)
	w.expire(now)
	w.refill(now)

	written := 0
	for _, retransmit := range [2]bool{true, false} {
		for _, msg := range w.msgs {
			for i := range msg.acked {
				if written >= frames || w.credit <= 0 {
					w.held = w.held || w.pending()
					return written
				}
				if !w.wants(msg, i, retransmit) {
					continue
				}
				if !w.emitFrag(msg, i, now, emit) {
					return written
				}
				written++
			}
		}
	}
	return written
}

// wants reports whether fragment i of msg is due on this pass.
func (w *Window) wants(msg *windowMsg, i int, retransmit bool) bool {
	if msg.acked[i] {
		return false
	}
	if retransmit {
		return msg.lost[i]
	}
	return msg.sentAt[i].IsZero()
}

// emitFrag hands one fragment to the writer and books it. The caller holds
// the lock.
func (w *Window) emitFrag(msg *windowMsg, i int, now time.Time, emit func([]byte) bool) bool {
	frame := EncodeStreamData(
		w.cfg.Role, w.cfg.Binding, w.id, w.floor, msg.seq, msg.crc,
		msg.total, i, len(msg.acked), msg.frag(i))
	if !emit(frame) {
		return false
	}
	w.sendOrd++
	msg.sentAt[i], msg.sentOrd[i] = now, w.sendOrd
	msg.lost[i] = false
	if msg.tries[i] < maxBackoff {
		msg.tries[i]++
	}
	w.credit -= float64(len(frame))
	return true
}

// pending reports whether anything is waiting to go out.
func (w *Window) pending() bool {
	for _, msg := range w.msgs {
		for i := range msg.acked {
			if !msg.acked[i] && (msg.lost[i] || msg.sentAt[i].IsZero()) {
				return true
			}
		}
	}
	return false
}

// detectLoss marks the fragments to retransmit: those whose retransmission
// timer expired, and those the peer skipped over while acknowledging later
// ones. A marked fragment cuts the pace, at most once per round trip.
func (w *Window) detectLoss(now time.Time) {
	rto := w.rto()
	reorder := max(w.srtt/4, reorderFloor)
	lost := false
	for _, msg := range w.msgs {
		for i := range msg.acked {
			if msg.acked[i] || msg.lost[i] || msg.sentAt[i].IsZero() {
				continue
			}
			if now.Sub(msg.sentAt[i]) < backoff(rto, msg.tries[i]) &&
				w.rack.Sub(msg.sentAt[i]) <= reorder &&
				w.ackedOrd < msg.sentOrd[i]+lostAfterAcks {
				continue
			}
			msg.lost[i] = true
			lost = true
		}
	}
	if lost {
		w.cut(now)
	}
	w.dark(now)
}

// backoff is the retransmission timer of a fragment sent tries times.
func backoff(rto time.Duration, tries uint8) time.Duration {
	if tries > 1 {
		rto <<= min(tries-1, maxBackoff)
	}
	return min(rto, maxRTO*maxBackoff)
}

// cut slows the pace after a loss, at most once per round trip.
func (w *Window) cut(now time.Time) {
	if !w.lossAt.IsZero() && now.Sub(w.lossAt) < max(w.srtt, growEvery) {
		return
	}
	w.rate = max(w.rate*cutFactor, w.cfg.MinRate)
	w.lossAt, w.growAt, w.held = now, now, false
}

// dark drops the pace to the floor when nothing at all has been
// acknowledged while data was outstanding: an SFU that stopped forwarding
// the stream takes it back only once the stream is small again.
func (w *Window) dark(now time.Time) {
	if len(w.msgs) == 0 || w.lastAck.IsZero() || now.Sub(w.lastAck) < blackout {
		return
	}
	if w.rate <= w.cfg.MinRate {
		return
	}
	w.rate = w.cfg.MinRate
	w.lossAt, w.growAt, w.held = now, now, false
}

// expire fails the window once its oldest message has been outstanding for
// the give-up window.
func (w *Window) expire(now time.Time) {
	if w.failed != nil || len(w.msgs) == 0 {
		return
	}
	if now.Sub(w.msgs[0].queued) < w.cfg.GiveUp {
		return
	}
	w.failed = ErrAckTimeout
	w.cond.Broadcast()
}

// refill tops the pace's credit up and raises the rate when the cap held
// data back for a whole grow interval without a loss.
func (w *Window) refill(now time.Time) {
	if w.creditAt.IsZero() {
		// A fresh window may write one burst at once: without it the
		// first tick after a message is queued writes nothing at all.
		w.creditAt, w.growAt = now, now
		w.credit = w.burst()
	}
	if elapsed := now.Sub(w.creditAt); elapsed > 0 {
		w.credit += w.rate * elapsed.Seconds()
		w.creditAt = now
	}
	w.credit = min(w.credit, w.burst())
	if !w.held || now.Sub(w.growAt) < growEvery {
		return
	}
	if !w.lossAt.IsZero() && now.Sub(w.lossAt) < growEvery {
		return
	}
	w.rate = min(w.rate*growFactor, w.cfg.MaxRate)
	w.growAt, w.held = now, false
}

// burst is the most credit the pace may bank.
func (w *Window) burst() float64 {
	return max(w.rate*paceBurst.Seconds(), float64(w.cfg.FragmentSize))
}

// Reset drops everything in flight and starts a new stream, which is what a
// reconnect leaves behind: the peer has forgotten the old sequence numbers,
// so a new stream id tells it to take this window's floor as its next
// message instead of waiting for messages that will never come.
func (w *Window) Reset() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.msgs, w.bytes = nil, 0
	w.id = randomStreamID()
	w.floor = w.seq.Load() + 1
	w.failed = nil
	w.srtt, w.rttvar, w.rack, w.lastAck = 0, 0, time.Time{}, time.Time{}
	w.sendOrd, w.ackedOrd = 0, 0
	w.rate, w.credit, w.creditAt = w.cfg.InitialRate, 0, time.Time{}
	w.lossAt, w.growAt, w.held = time.Time{}, time.Time{}, false
	w.cond.Broadcast()
}

// Close releases every Send waiting for room.
func (w *Window) Close() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.closed = true
	w.cond.Broadcast()
}
