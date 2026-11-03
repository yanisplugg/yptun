package common_test

// ai-generated: the whole file (the receiving half of an ordered stream).

import (
	"bytes"
	"hash/crc32"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// streamFrames splits a message into the frames a window would write for it.
func streamFrames(stream, floor, seq uint32, payload []byte, fragSize int) []common.Frame {
	crc := crc32.ChecksumIEEE(payload)
	chunks := common.FragmentPayload(payload, fragSize)
	out := make([]common.Frame, 0, len(chunks))
	for i, chunk := range chunks {
		raw := common.EncodeStreamData(common.RoleServer, 0, stream, floor, seq, crc,
			len(payload), i, len(chunks), chunk)
		frame, err := common.DecodeFrame(raw)
		if err != nil {
			panic(err)
		}
		out = append(out, frame)
	}
	return out
}

func pushAll(o *common.Ordered, frames []common.Frame) (int, [][]byte) {
	acks := 0
	var ready [][]byte
	for _, frame := range frames {
		ack, out := o.Push(frame)
		if ack {
			acks++
		}
		ready = append(ready, out...)
	}
	return acks, ready
}

// TestOrderedHoldsAMessageUntilItsTurn is the whole point: a later message
// whose fragments are all in waits for the one queued before it, so the byte
// stream above never sees a hole.
func TestOrderedHoldsAMessageUntilItsTurn(t *testing.T) {
	o := common.NewOrdered(16, 0)
	first := []byte("first message")
	second := []byte("second message")

	acks, ready := pushAll(o, streamFrames(7, 1, 2, second, 4))
	if acks == 0 {
		t.Fatal("the second message was not acknowledged")
	}
	if len(ready) != 0 {
		t.Fatalf("the second message was delivered before the first: %q", ready)
	}

	_, ready = pushAll(o, streamFrames(7, 1, 1, first, 4))
	if len(ready) != 2 || !bytes.Equal(ready[0], first) || !bytes.Equal(ready[1], second) {
		t.Fatalf("delivered %q, want the two messages in order", ready)
	}
}

// TestOrderedTakesTheFloorAsItsStart keeps a receiver that joins mid-stream
// from delivering out of order: the sender's floor says which message is
// next, whichever frame arrives first.
func TestOrderedTakesTheFloorAsItsStart(t *testing.T) {
	o := common.NewOrdered(16, 0)
	// The first frame to arrive is of message 5, while the sender still
	// holds 3 unacknowledged: 3 and 4 are on their way.
	_, ready := pushAll(o, streamFrames(9, 3, 5, []byte("five"), 8))
	if len(ready) != 0 {
		t.Fatalf("delivered %q before the messages queued earlier", ready)
	}
	_, ready = pushAll(o, streamFrames(9, 3, 4, []byte("four"), 8))
	if len(ready) != 0 {
		t.Fatalf("delivered %q with message 3 still missing", ready)
	}
	_, ready = pushAll(o, streamFrames(9, 3, 3, []byte("three"), 8))
	if len(ready) != 3 {
		t.Fatalf("delivered %d messages, want three in order", len(ready))
	}
	if string(ready[0]) != "three" || string(ready[1]) != "four" || string(ready[2]) != "five" {
		t.Fatalf("delivered %q, want three, four, five", ready)
	}
}

// TestOrderedReAcksADuplicate keeps a lost acknowledgement from stalling the
// sender: the fragment comes again and is acknowledged again, not delivered
// again.
func TestOrderedReAcksADuplicate(t *testing.T) {
	o := common.NewOrdered(16, 0)
	frames := streamFrames(3, 1, 1, []byte("payload"), 4)

	if _, ready := pushAll(o, frames); len(ready) != 1 {
		t.Fatalf("delivered %d messages, want one", len(ready))
	}
	acks, ready := pushAll(o, frames)
	if acks != len(frames) {
		t.Fatalf("acknowledged %d of %d duplicate fragments", acks, len(frames))
	}
	if len(ready) != 0 {
		t.Fatalf("a duplicate was delivered a second time: %q", ready)
	}
}

// TestOrderedRefusesWhatIsTooFarAhead bounds what a peer can make this side
// hold.
func TestOrderedRefusesWhatIsTooFarAhead(t *testing.T) {
	o := common.NewOrdered(4, 0)
	if _, ready := pushAll(o, streamFrames(3, 1, 1, []byte("one"), 8)); len(ready) != 1 {
		t.Fatal("the first message was not delivered")
	}
	acks, ready := pushAll(o, streamFrames(3, 1, 99, []byte("far"), 8))
	if acks != 0 || len(ready) != 0 {
		t.Fatalf("a message %d ahead was taken (acks=%d)", 99-2, acks)
	}
}

// TestOrderedIgnoresACorruptedFragment keeps the ack honest: a fragment that
// fails its own checksum is not acknowledged, so the sender sends it again.
func TestOrderedIgnoresACorruptedFragment(t *testing.T) {
	o := common.NewOrdered(16, 0)
	frames := streamFrames(3, 1, 1, []byte("payload"), 4)
	frames[0].FragCRC ^= 1

	if ack, ready := o.Push(frames[0]); ack || len(ready) != 0 {
		t.Fatalf("a corrupted fragment was acknowledged (ack=%v)", ack)
	}
}

// TestOrderedFollowsTheStreamThroughAReconnect covers the peer that comes
// back with a new stream: its floor is where this side picks up, and the
// late frames of the stream it left cannot pull it back.
func TestOrderedFollowsTheStreamThroughAReconnect(t *testing.T) {
	o := common.NewOrdered(16, 0)
	old := streamFrames(11, 1, 1, []byte("before"), 8)
	if _, ready := pushAll(o, old); len(ready) != 1 {
		t.Fatal("the message before the reconnect was not delivered")
	}

	_, ready := pushAll(o, streamFrames(12, 40, 40, []byte("after"), 8))
	if len(ready) != 1 || string(ready[0]) != "after" {
		t.Fatalf("after the reconnect delivered %q", ready)
	}

	acks, ready := pushAll(o, streamFrames(11, 1, 2, []byte("late"), 8))
	if acks != 0 || len(ready) != 0 {
		t.Fatalf("a frame of the stream that was left got through (acks=%d, ready=%q)", acks, ready)
	}
}

// TestOrderedSkipsWhatTheSenderHasAcknowledgedPast keeps a receiver that
// lost its state from waiting forever for messages the sender will never
// send again.
func TestOrderedSkipsWhatTheSenderHasAcknowledgedPast(t *testing.T) {
	o := common.NewOrdered(16, 0)
	if _, ready := pushAll(o, streamFrames(5, 1, 1, []byte("one"), 8)); len(ready) != 1 {
		t.Fatal("the first message was not delivered")
	}
	// Message 2 never arrives whole, and the sender moves its floor past
	// it, which only happens once this side has acknowledged it all.
	if _, ready := pushAll(o, streamFrames(5, 2, 3, []byte("three"), 8)); len(ready) != 0 {
		t.Fatal("message three was delivered while two was still expected")
	}
	_, ready := pushAll(o, streamFrames(5, 4, 4, []byte("four"), 8))
	if len(ready) != 2 || string(ready[0]) != "three" || string(ready[1]) != "four" {
		t.Fatalf("delivered %q, want three and four once the floor moved", ready)
	}
}

// TestOrderedRefusesMoreThanItsByteBound keeps a peer that never sends the
// message this side waits for from pinning down memory: the one it waits
// for is always taken, the rest are refused and come again.
func TestOrderedRefusesMoreThanItsByteBound(t *testing.T) {
	o := common.NewOrdered(64, 4096)
	big := bytes.Repeat([]byte{4}, 2000)
	for seq := uint32(2); seq < 8; seq++ {
		pushAll(o, streamFrames(3, 1, seq, big, 900))
	}
	acks, ready := pushAll(o, streamFrames(3, 1, 9, big, 900))
	if acks != 0 || len(ready) != 0 {
		t.Fatalf("a message past the byte bound was taken (acks=%d)", acks)
	}
	if _, ready = pushAll(o, streamFrames(3, 1, 1, []byte("first"), 900)); len(ready) == 0 {
		t.Fatal("the message everything waits for was refused")
	}
}

// TestOrderedResetWaitsToBeToldWhereToPickUp covers the local reconnect: the
// session is gone, and the next stream's floor starts the new one.
func TestOrderedResetWaitsToBeToldWhereToPickUp(t *testing.T) {
	o := common.NewOrdered(16, 0)
	if _, ready := pushAll(o, streamFrames(5, 1, 1, []byte("one"), 8)); len(ready) != 1 {
		t.Fatal("the first message was not delivered")
	}
	o.Reset()

	_, ready := pushAll(o, streamFrames(5, 9, 9, []byte("nine"), 8))
	if len(ready) != 1 || string(ready[0]) != "nine" {
		t.Fatalf("after Reset delivered %q, want the new floor's message", ready)
	}
}
