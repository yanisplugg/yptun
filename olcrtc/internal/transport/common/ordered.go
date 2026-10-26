package common

// ai-generated: the whole file (the receiving half of an ordered stream: it
// delivers messages in the order the sender queued them, which is what lets
// the sender keep several of them in flight).

import (
	"errors"
	"hash/crc32"
	"sync"
)

// ErrWindowClosed is returned by a window's Send once its transport closes.
var ErrWindowClosed = errors.New("window closed")

// DefaultOrderedAhead is how many messages past the next one an ordered
// receiver holds. It covers a sender's window with room to spare; a frame
// further ahead than this is not one of ours.
const DefaultOrderedAhead = 128

// DefaultOrderedBytes bounds what a receiver holds for a peer that is not
// sending the message it is waiting for. Everything here comes off the wire
// from whoever is in the room, so it is bounded in bytes as well as in
// messages.
const DefaultOrderedBytes = 1 << 20

// retiredStreams is how many stream ids an ordered receiver remembers having
// left, so the late frames of one cannot pull it back.
const retiredStreams = 4

// Ordered reassembles an ordered stream and hands messages up in the
// sender's order: a message whose fragments are all in waits for the ones
// queued before it. Without that the peer could only ever have one message
// in flight, because a lost fragment would let the next message overtake it
// and hand the byte stream above a hole.
type Ordered struct {
	mu       sync.Mutex
	stream   uint32
	next     uint32
	started  bool
	partial  map[uint32]*InboundMessage
	held     map[uint32][]byte
	retired  [retiredStreams]uint32
	retireAt int
	ahead    uint32
	maxBytes int
	bytes    int
}

// NewOrdered creates a receiver that holds at most ahead messages, and
// maxBytes of them, past the next one; zero takes the defaults.
func NewOrdered(ahead, maxBytes int) *Ordered {
	if ahead <= 0 {
		ahead = DefaultOrderedAhead
	}
	if maxBytes <= 0 {
		maxBytes = DefaultOrderedBytes
	}
	return &Ordered{
		partial:  make(map[uint32]*InboundMessage),
		held:     make(map[uint32][]byte),
		ahead:    uint32(ahead),
		maxBytes: maxBytes,
	}
}

// Push takes one stream fragment. The first result says whether to
// acknowledge it: a fragment that was stored, one of a message already
// delivered, or one of a message whose fragments are all in. The second
// holds the messages that are now deliverable, in order.
func (o *Ordered) Push(frame Frame) (bool, [][]byte) {
	fragment := Fragment{
		Seq: frame.Seq, CRC: frame.CRC, TotalLen: frame.TotalLen,
		FragIdx: frame.FragIdx, FragTotal: frame.FragTotal,
		FragCRC: frame.FragCRC, Payload: frame.Payload,
	}
	if !fragment.valid() {
		return false, nil
	}

	o.mu.Lock()
	defer o.mu.Unlock()

	if !o.adopt(frame) {
		return false, nil
	}
	if before(frame.Seq, o.next) {
		return true, nil // already delivered; the peer lost our acknowledgement
	}
	if !before(frame.Seq, o.next+o.ahead) {
		return false, nil // further ahead than any window of ours reaches
	}
	if frame.Seq != o.next && o.bytes+len(frame.Payload) > o.maxBytes {
		// Full of messages waiting on one that has not arrived. The one
		// they wait for is always taken; the rest come again.
		return false, nil
	}
	ready := o.skipTo(frame.Floor)
	return true, append(ready, o.store(fragment)...)
}

// adopt binds the receiver to the frame's stream, taking its floor as the
// next message to deliver. It reports whether the frame belongs to the
// stream this receiver follows: a frame of a stream it has already left
// does not.
func (o *Ordered) adopt(frame Frame) bool {
	if o.started && o.stream == frame.Stream {
		return true
	}
	if o.hasRetired(frame.Stream) {
		return false
	}
	if o.started {
		o.retired[o.retireAt] = o.stream
		o.retireAt = (o.retireAt + 1) % retiredStreams
	}
	clear(o.partial)
	clear(o.held)
	o.bytes = 0
	o.stream, o.next, o.started = frame.Stream, frame.Floor, true
	return true
}

func (o *Ordered) hasRetired(stream uint32) bool {
	for _, id := range o.retired {
		if id != 0 && id == stream {
			return true
		}
	}
	return false
}

// skipTo moves past the messages the sender reports acknowledged, which this
// receiver may still be holding: everything below floor is delivered or
// gone. Only a receiver that lost its state mid-stream ever skips a gap.
func (o *Ordered) skipTo(floor uint32) [][]byte {
	if !before(o.next, floor) {
		return nil
	}
	var ready [][]byte
	for ; before(o.next, floor); o.next++ {
		if data, ok := o.held[o.next]; ok {
			ready = append(ready, data)
			o.bytes -= len(data)
			delete(o.held, o.next)
		}
		o.drop(o.next)
	}
	return append(ready, o.drain()...)
}

// store adds one fragment and returns whatever became deliverable.
func (o *Ordered) store(fragment Fragment) [][]byte {
	msg, ok := o.partial[fragment.Seq]
	if !ok || msg.CRC != fragment.CRC || msg.TotalLen != fragment.TotalLen ||
		len(msg.frags) != int(fragment.FragTotal) {
		msg = &InboundMessage{
			TotalLen: fragment.TotalLen,
			CRC:      fragment.CRC,
			frags:    make([][]byte, fragment.FragTotal),
			remain:   int(fragment.FragTotal),
		}
		o.partial[fragment.Seq] = msg
	}
	if msg.frags[fragment.FragIdx] == nil {
		chunk := make([]byte, len(fragment.Payload))
		copy(chunk, fragment.Payload)
		msg.frags[fragment.FragIdx] = chunk
		msg.remain--
		o.bytes += len(chunk)
	}
	if msg.remain > 0 {
		return nil
	}
	o.drop(fragment.Seq)
	data := assemble(msg)
	if crc32.ChecksumIEEE(data) != msg.CRC {
		return nil
	}
	if fragment.Seq != o.next {
		o.held[fragment.Seq] = data
		o.bytes += len(data)
		return nil
	}
	o.next++
	return append([][]byte{data}, o.drain()...)
}

// drain takes the messages held behind the one just delivered.
func (o *Ordered) drain() [][]byte {
	var ready [][]byte
	for {
		data, ok := o.held[o.next]
		if !ok {
			return ready
		}
		delete(o.held, o.next)
		o.bytes -= len(data)
		o.next++
		ready = append(ready, data)
	}
}

// drop forgets the half-assembled message at seq and the bytes it held.
func (o *Ordered) drop(seq uint32) {
	msg, ok := o.partial[seq]
	if !ok {
		return
	}
	for _, chunk := range msg.frags {
		o.bytes -= len(chunk)
	}
	delete(o.partial, seq)
}

// Reset forgets the stream. The peer that reconnects starts a new one, and
// its first frame says where this receiver picks up.
func (o *Ordered) Reset() {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.started, o.stream, o.next, o.bytes = false, 0, 0, 0
	o.retired, o.retireAt = [retiredStreams]uint32{}, 0
	clear(o.partial)
	clear(o.held)
}

// before reports whether sequence number a comes before b, wrapping like a
// serial number.
func before(a, b uint32) bool {
	return int32(a-b) < 0 //nolint:gosec // serial number arithmetic, RFC 1982
}
