// Package common provides the building blocks shared by the video-track based
// transports: the engine-session adapter, the wire frame codec, fragment and
// reassembly handling, per-fragment ack tracking with the retransmit loop
// built on it, the outbound queue pair, session binding tokens and per-peer
// random IDs.
//
// seichannel and videochannel are built entirely out of these; vp8channel
// does its own KCP-based framing and consumes only the session adapter, the
// binding token and the ID helpers.
package common

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"hash/crc32"
	"sync"
	"time"
)

// RandomID returns 8 random hex characters for use as a per-peer suffix on
// track and stream IDs. Required for Jitsi: msid collisions between
// participants cause Jicofo to reject session-accept.
func RandomID() string {
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%08x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:])
}

// FragmentPayload splits data into chunks of at most maxSize bytes. An empty
// payload produces a single empty fragment so the caller can still ack a
// zero-byte message round-trip. Fragments alias data and are valid until the
// caller reuses it. Sender encodes each fragment into an owned wire frame
// before Send returns. A non-positive maxSize yields one chunk holding
// everything: misconfigured sizing must not spin here forever.
func FragmentPayload(data []byte, maxSize int) [][]byte {
	if len(data) == 0 {
		return [][]byte{{}}
	}
	if maxSize <= 0 {
		return [][]byte{data}
	}
	out := make([][]byte, 0, (len(data)+maxSize-1)/maxSize)
	for start := 0; start < len(data); start += maxSize {
		end := start + maxSize
		if end > len(data) {
			end = len(data)
		}
		out = append(out, data[start:end])
	}
	return out
}

// Reassembly limits. TotalLen and FragTotal arrive from the wire and are
// used as allocation sizes, so they are bounded before anything is reserved:
// a 27-byte frame claiming TotalLen 0xFFFFFFFF would otherwise reserve 4 GiB,
// and one claiming FragTotal 65535 would reserve a 65535-entry slice header
// per sequence number.
//
// MaxMessageLen sits far above what any transport can produce - smux frames
// are capped at 32 KiB and every video transport caps its payload well below
// that - so legitimate traffic never comes close.
const (
	// MaxMessageLen is the largest reassembled message accepted from the wire.
	MaxMessageLen = 256 * 1024
	// MaxFragments is the largest fragment count accepted from the wire.
	MaxFragments = 4096
)

// Fragment describes one piece of a fragmented message on the wire.
type Fragment struct {
	Seq       uint32
	CRC       uint32
	TotalLen  uint32
	FragIdx   uint16
	FragTotal uint16
	// FragCRC is the crc32 of Payload, verified before the fragment is stored.
	FragCRC uint32
	Payload []byte
}

// WithPayloadCRC returns f with FragCRC derived from its payload. Callers that
// build fragments outside the wire codec use it so the per-fragment checksum
// stays a property of the fragment instead of something each caller repeats.
func (f Fragment) WithPayloadCRC() Fragment {
	f.FragCRC = crc32.ChecksumIEEE(f.Payload)
	return f
}

// valid reports whether the fragment's self-describing fields are consistent
// and within the reassembly limits, and whether the payload matches its own
// checksum. Everything here comes straight off the wire from an unauthenticated
// room participant, so it is checked before a single byte is reserved.
func (f Fragment) valid() bool {
	if f.FragTotal == 0 || f.FragTotal > MaxFragments || f.FragIdx >= f.FragTotal {
		return false
	}
	if f.TotalLen > MaxMessageLen {
		return false
	}
	// A message of L bytes never splits into more than max(L, 1) fragments,
	// which keeps the frags slice proportional to the announced length.
	if uint32(f.FragTotal) > max(f.TotalLen, 1) {
		return false
	}
	if uint64(len(f.Payload)) > uint64(f.TotalLen) {
		return false
	}
	return crc32.ChecksumIEEE(f.Payload) == f.FragCRC
}

// InboundMessage tracks reassembly state for one inbound message.
type InboundMessage struct {
	TotalLen uint32
	CRC      uint32
	frags    [][]byte
	remain   int
	// added is the monotonic insertion counter used to evict the oldest
	// incomplete message when the pending set exceeds its cap.
	added uint64
}

// Reassembler holds inbound message state and a sliding window of recently
// delivered (seq, crc) pairs so duplicate fragments resolve to a fresh ack
// rather than a re-delivery.
type Reassembler struct {
	mu      sync.Mutex
	inbound map[uint32]*InboundMessage
	// delivered is the live half of the dedup window and previous the half
	// it replaced. Rotating instead of clearing keeps at least maxRecent
	// entries addressable at all times; clearing outright let a retransmit
	// arriving right after the flush be reassembled and delivered a second
	// time.
	delivered map[uint32]uint32
	previous  map[uint32]uint32
	maxRecent int
	// maxPending bounds the number of incomplete messages held at once.
	// Lost fragments (routine on video transports behind an SFU) would
	// otherwise leak these entries forever; once the cap is hit we evict
	// the oldest incomplete message to make room.
	maxPending int
	addCounter uint64
}

// NewReassembler creates a reassembler with the given recent-delivery cap.
// When the delivered map exceeds maxRecent entries it is reset; a value of
// 256 is a reasonable default for the video transports.
func NewReassembler(maxRecent int) *Reassembler {
	if maxRecent <= 0 {
		maxRecent = 256
	}
	return &Reassembler{
		inbound:    make(map[uint32]*InboundMessage),
		delivered:  make(map[uint32]uint32),
		previous:   make(map[uint32]uint32),
		maxRecent:  maxRecent,
		maxPending: maxRecent,
	}
}

// Reset drops all reassembly and dedup state. A provider reconnect replaces
// the peer, so its half-assembled messages and its sequence numbering are
// both meaningless afterwards - and a reused sequence number would otherwise
// resolve against the previous peer's dedup window.
func (r *Reassembler) Reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.inbound = make(map[uint32]*InboundMessage)
	r.delivered = make(map[uint32]uint32, r.maxRecent)
	r.previous = make(map[uint32]uint32)
	r.addCounter = 0
}

// Result classifies what Push computed for a fragment.
type Result int

const (
	// ResultIgnore means the fragment was malformed or out of range.
	ResultIgnore Result = iota
	// ResultPartial means the fragment was stored but the message is not
	// fully reassembled yet.
	ResultPartial
	// ResultDuplicate means the message identified by (Seq, CRC) was
	// already delivered. Caller should re-ack without invoking OnData.
	ResultDuplicate
	// ResultDelivered means the message is complete; Data carries the
	// reassembled payload.
	ResultDelivered
)

// Push integrates fragment into reassembly state and returns one of the
// Result values. When ResultDelivered, the second return holds the
// reassembled payload bytes; otherwise it is nil.
func (r *Reassembler) Push(fragment Fragment) (Result, []byte) {
	if !fragment.valid() {
		return ResultIgnore, nil
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	if r.alreadyDelivered(fragment) {
		return ResultDuplicate, nil
	}

	msg := r.upsert(fragment)
	r.storeChunk(msg, fragment)
	if msg.remain > 0 {
		return ResultPartial, nil
	}
	return r.deliver(fragment.Seq, msg)
}

func (r *Reassembler) alreadyDelivered(fragment Fragment) bool {
	if crc, ok := r.delivered[fragment.Seq]; ok {
		return crc == fragment.CRC
	}
	crc, ok := r.previous[fragment.Seq]
	return ok && crc == fragment.CRC
}

// upsert returns the inbound message tracking entry for fragment.Seq,
// creating a fresh entry if no compatible one is present.
func (r *Reassembler) upsert(fragment Fragment) *InboundMessage {
	msg, ok := r.inbound[fragment.Seq]
	if ok && msg.CRC == fragment.CRC && msg.TotalLen == fragment.TotalLen &&
		len(msg.frags) == int(fragment.FragTotal) {
		return msg
	}
	r.addCounter++
	msg = &InboundMessage{
		TotalLen: fragment.TotalLen,
		CRC:      fragment.CRC,
		frags:    make([][]byte, fragment.FragTotal),
		remain:   int(fragment.FragTotal),
		added:    r.addCounter,
	}
	r.inbound[fragment.Seq] = msg
	r.evictOldestIfFull(fragment.Seq)
	return msg
}

// evictOldestIfFull drops the oldest incomplete message when the pending set
// exceeds its cap, preventing unbounded memory growth from messages whose
// fragments are never fully received. keep is never evicted - it is the entry
// the current Push is about to populate.
func (r *Reassembler) evictOldestIfFull(keep uint32) {
	if r.maxPending <= 0 || len(r.inbound) <= r.maxPending {
		return
	}
	var (
		oldestSeq   uint32
		oldestAdded uint64
		found       bool
	)
	for seq, m := range r.inbound {
		if seq == keep {
			continue
		}
		if !found || m.added < oldestAdded {
			oldestSeq, oldestAdded, found = seq, m.added, true
		}
	}
	if found {
		delete(r.inbound, oldestSeq)
	}
}

func (r *Reassembler) storeChunk(msg *InboundMessage, fragment Fragment) {
	if msg.frags[fragment.FragIdx] != nil {
		return
	}
	chunk := make([]byte, len(fragment.Payload))
	copy(chunk, fragment.Payload)
	msg.frags[fragment.FragIdx] = chunk
	msg.remain--
}

func (r *Reassembler) deliver(seq uint32, msg *InboundMessage) (Result, []byte) {
	delete(r.inbound, seq)
	data := assemble(msg)
	if crc32.ChecksumIEEE(data) != msg.CRC {
		return ResultIgnore, nil
	}
	if len(r.delivered) >= r.maxRecent {
		r.previous = r.delivered
		r.delivered = make(map[uint32]uint32, r.maxRecent)
	}
	r.delivered[seq] = msg.CRC
	return ResultDelivered, data
}

// assemble concatenates the received fragments. The buffer is sized from the
// bytes actually held, not from the announced TotalLen, so a message never
// reserves more than it received.
func assemble(msg *InboundMessage) []byte {
	size := 0
	for _, frag := range msg.frags {
		size += len(frag)
	}
	if uint64(size) > uint64(msg.TotalLen) {
		size = int(msg.TotalLen)
	}
	out := make([]byte, 0, size)
	for _, frag := range msg.frags {
		if len(out)+len(frag) > size {
			return append(out, frag[:size-len(out)]...)
		}
		out = append(out, frag...)
	}
	return out
}
