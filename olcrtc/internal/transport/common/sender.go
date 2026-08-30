package common

import (
	"errors"
	"hash/crc32"
	"sync"
	"sync/atomic"
	"time"
)

// ErrAckTimeout is returned when the peer does not acknowledge every fragment
// of a message within the attempt budget. Transports wrap it in their own
// sentinel so callers keep seeing a transport-specific error.
var ErrAckTimeout = errors.New("ack timeout")

// maxAckTimeout caps the per-attempt wait however large the payload is.
const maxAckTimeout = 30 * time.Second

// SenderConfig describes how one transport paces and retries its fragments.
type SenderConfig struct {
	// Role and Binding are stamped into every outgoing frame.
	Role    byte
	Binding uint32
	// FragmentSize is the maximum payload carried by one frame.
	FragmentSize int
	// MaxAttempts bounds how many times the still-unacked fragments of one
	// message are retransmitted before Send gives up.
	MaxAttempts int
	// FrameInterval and BatchSize describe the writer's drain rate and are
	// what makes the ack budget cover a full round trip.
	FrameInterval time.Duration
	BatchSize     int
	// AckFloor is the minimum per-attempt wait, used for payloads too small
	// for the drain estimate to matter.
	AckFloor time.Duration
}

// Sender owns the outbound half of an ack-based video transport: it
// fragments a message, queues it, waits for per-fragment acks and
// retransmits only the fragments that were not acknowledged.
//
// Retransmitting just the missing fragments is what makes the retry loop
// safe. Whole-message semantics had to choose between never retrying (leaving
// a genuinely lost fragment unrecoverable) and re-queueing every fragment of
// the message on each attempt, which duplicates the whole payload behind the
// paced writer and clogs the outbound queue.
type Sender struct {
	cfg   SenderConfig
	queue *OutboundQueue
	acks  *AckTracker
	seq   atomic.Uint32
	mu    sync.Mutex
}

// NewSender binds a sender to the transport's outbound queue.
func NewSender(cfg SenderConfig, queue *OutboundQueue) *Sender {
	return &Sender{cfg: cfg, queue: queue, acks: NewAckTracker()}
}

// Send fragments data, queues it and blocks until every fragment is
// acknowledged, the attempt budget is exhausted (ErrAckTimeout) or the
// transport closes.
func (s *Sender) Send(data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	seq := s.seq.Add(1)
	crc := crc32.ChecksumIEEE(data)
	fragments := FragmentPayload(data, s.cfg.FragmentSize)

	waiter := s.acks.Register(seq, crc, len(fragments))
	defer s.acks.Unregister(seq)

	ackTimeout := PerAttemptAckTimeout(
		len(fragments), s.cfg.BatchSize, s.cfg.FrameInterval, s.cfg.AckFloor)

	// Initial attempt sends every fragment; later attempts send whatever is
	// still unacked.
	pending := make([]int, len(fragments))
	for i := range pending {
		pending[i] = i
	}

	for range s.cfg.MaxAttempts {
		if err := s.queueFragments(seq, crc, len(data), fragments, pending); err != nil {
			return err
		}

		done, err := s.await(waiter, ackTimeout)
		if err != nil {
			return err
		}
		if done {
			return nil
		}

		pending = waiter.Pending()
		if len(pending) == 0 {
			return nil
		}
	}

	return ErrAckTimeout
}

// Resolve records an inbound ack for one fragment.
func (s *Sender) Resolve(seq, crc uint32, fragIdx uint16) {
	s.acks.Mark(seq, crc, int(fragIdx))
}

// Ack queues the acknowledgement of one received fragment on the priority
// queue.
func (s *Sender) Ack(seq, crc uint32, fragIdx uint16) {
	_ = s.queue.Enqueue(EncodeAck(s.cfg.Role, s.cfg.Binding, seq, crc, fragIdx), true)
}

// Hello returns the presence beacon for this sender's role and binding.
func (s *Sender) Hello() []byte {
	return EncodeHello(s.cfg.Role, s.cfg.Binding)
}

func (s *Sender) queueFragments(seq, crc uint32, totalLen int, fragments [][]byte, pending []int) error {
	for _, idx := range pending {
		frame := EncodeData(
			s.cfg.Role, s.cfg.Binding, seq, crc,
			totalLen, idx, len(fragments), fragments[idx])
		if err := s.queue.Enqueue(frame, false); err != nil {
			return err
		}
	}
	return nil
}

// await blocks until every fragment is acked, the per-attempt timeout
// elapses, or the transport closes. Returns (done, err).
func (s *Sender) await(waiter *AckWaiter, timeout time.Duration) (bool, error) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		if waiter.Done() {
			return true, nil
		}
		select {
		case <-waiter.Notify():
			// Re-check Done() at the top of the loop.
		case <-timer.C:
			return waiter.Done(), nil
		case <-s.queue.closed:
			return false, s.queue.closedErr
		}
	}
}

// PerAttemptAckTimeout returns how long to wait for the acks of a
// fragments-sized message before retransmitting what is still missing.
//
// The budget has to cover the writer draining every fragment at its frame
// cadence plus reassembly and the ack trip back, so it scales with the number
// of writer ticks the message needs (batchSize fragments per tick) and gets a
// 3x margin for scheduling jitter. Anything below floor uses floor; the
// result is capped so a huge payload cannot block a sender indefinitely.
func PerAttemptAckTimeout(fragments, batchSize int, frameInterval, floor time.Duration) time.Duration {
	if batchSize <= 0 {
		batchSize = 1
	}
	drainTicks := (fragments + batchSize - 1) / batchSize
	estimated := time.Duration(drainTicks) * frameInterval * 3
	if estimated < floor {
		return floor
	}
	if estimated > maxAckTimeout {
		return maxAckTimeout
	}
	return estimated
}

// DeliverFragment pushes an inbound data fragment into r and acknowledges it.
// Every fragment that decodes and passes its own checksum is acked, duplicates
// included: under retransmission the sender may have lost the earlier ack and
// is waiting on this one. A malformed, out-of-range or corrupted fragment stays
// silent so the sender retransmits exactly that fragment.
func DeliverFragment(r *Reassembler, frame Frame, onData func([]byte), ack func(seq, crc uint32, fragIdx uint16)) {
	result, data := r.Push(Fragment{
		Seq:       frame.Seq,
		CRC:       frame.CRC,
		TotalLen:  frame.TotalLen,
		FragIdx:   frame.FragIdx,
		FragTotal: frame.FragTotal,
		FragCRC:   frame.FragCRC,
		Payload:   frame.Payload,
	})

	switch result {
	case ResultDelivered:
		if onData != nil {
			onData(data)
		}
		ack(frame.Seq, frame.CRC, frame.FragIdx)
	case ResultPartial, ResultDuplicate:
		ack(frame.Seq, frame.CRC, frame.FragIdx)
	case ResultIgnore:
		// Malformed, out of range or corrupted; acknowledging it here is
		// exactly what would lose the message, so stay silent and let the
		// sender retransmit.
	}
}
