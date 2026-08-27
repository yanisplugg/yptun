package common

// Queue sizes for the outbound frame pair. Acks ride a separate, smaller
// queue so a full data queue can never delay the ack that would drain it.
const (
	outboundDataQueueSize = 256
	outboundAckQueueSize  = 64
)

// OutboundQueue is the two-channel send queue shared by the ack-based video
// transports: bulk frames on one channel, acks on a priority channel. The
// writer loop drains it one frame per call and always prefers a pending ack.
type OutboundQueue struct {
	data   chan []byte
	acks   chan []byte
	closed <-chan struct{}
	// closedErr is returned by Enqueue once the transport is shutting down.
	closedErr error
}

// NewOutboundQueue creates the queue pair. done is the transport's close
// channel and closedErr is the sentinel Enqueue returns after it fires.
func NewOutboundQueue(done <-chan struct{}, closedErr error) *OutboundQueue {
	return &OutboundQueue{
		data:      make(chan []byte, outboundDataQueueSize),
		acks:      make(chan []byte, outboundAckQueueSize),
		closed:    done,
		closedErr: closedErr,
	}
}

// Next returns the next frame to write. The second result is false when the
// transport is closing and the writer must stop; a nil frame with a true
// result means the queue is empty and the caller should emit an idle frame.
//
// The two-phase select is deliberate: the first, ack-only phase guarantees a
// queued ack always wins over a queued data frame, which the single fused
// select below cannot do (Go picks uniformly among ready cases).
func (q *OutboundQueue) Next() ([]byte, bool) {
	select {
	case <-q.closed:
		return nil, false
	case frame := <-q.acks:
		return frame, true
	default:
	}

	select {
	case <-q.closed:
		return nil, false
	case frame := <-q.acks:
		return frame, true
	case frame := <-q.data:
		return frame, true
	default:
		return nil, true
	}
}

// Enqueue queues frame, on the priority ack channel when priority is set.
// It blocks while the target channel is full and fails once the transport
// closes.
func (q *OutboundQueue) Enqueue(frame []byte, priority bool) error {
	// Check the close signal first: once closed, Enqueue must fail even
	// though the channel below may still have room, which a single fused
	// select would decide by coin flip.
	select {
	case <-q.closed:
		return q.closedErr
	default:
	}

	ch := q.data
	if priority {
		ch = q.acks
	}

	select {
	case <-q.closed:
		return q.closedErr
	case ch <- frame:
		return nil
	}
}

// Len reports how many bulk frames are queued. Used by tests.
func (q *OutboundQueue) Len() int { return len(q.data) }
