package common_test

// ai-generated: the whole file (the window's admission, retransmission clock
// and pace, on a clock the test drives).

import (
	"errors"
	"hash/crc32"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// testClock is the clock a window reads, moved by the test alone.
type testClock struct{ at atomic.Int64 }

func newTestClock() *testClock {
	c := &testClock{}
	c.at.Store(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC).UnixNano())
	return c
}

func (c *testClock) now() time.Time { return time.Unix(0, c.at.Load()).UTC() }

func (c *testClock) advance(d time.Duration) { c.at.Add(int64(d)) }

// collector gathers what a Drain writes.
type collector struct{ frames []common.Frame }

func (c *collector) emit(raw []byte) bool {
	frame, err := common.DecodeFrame(raw)
	if err != nil {
		panic(err)
	}
	c.frames = append(c.frames, frame)
	return true
}

func newTestWindow(t *testing.T, clock *testClock, cfg common.WindowConfig) *common.Window {
	t.Helper()
	cfg.Now = clock.now
	if cfg.FragmentSize == 0 {
		cfg.FragmentSize = 4
	}
	if cfg.Role == 0 {
		cfg.Role = common.RoleServer
	}
	var seq atomic.Uint32
	return common.NewWindow(cfg, &seq)
}

func drainAll(w *common.Window) []common.Frame {
	out := &collector{}
	w.Drain(1024, out.emit)
	return out.frames
}

// TestWindowKeepsSeveralMessagesInFlight is the point of the window: Send
// returns before the peer has answered, so the next message goes out in the
// same tick instead of a round trip later.
func TestWindowKeepsSeveralMessagesInFlight(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 4})

	for i := range 3 {
		if err := w.Send([]byte{byte(i), byte(i), byte(i), byte(i)}); err != nil {
			t.Fatalf("Send(%d) = %v", i, err)
		}
	}
	if got := w.InFlight(); got != 3 {
		t.Fatalf("InFlight() = %d, want 3", got)
	}
	frames := drainAll(w)
	if len(frames) != 3 {
		t.Fatalf("Drain wrote %d frames, want 3", len(frames))
	}
	for i, frame := range frames {
		if frame.Type != common.FrameTypeStream {
			t.Fatalf("frame %d type = %d", i, frame.Type)
		}
		if frame.Floor != frames[0].Seq {
			t.Fatalf("frame %d floor = %d, want %d", i, frame.Floor, frames[0].Seq)
		}
	}
	if extra := drainAll(w); len(extra) != 0 {
		t.Fatalf("Drain wrote %d frames again, want none", len(extra))
	}
}

// TestWindowBlocksWhenFullAndWakesOnAck keeps the window a back-pressure
// point: a full one parks the caller until the peer acknowledges.
func TestWindowBlocksWhenFullAndWakesOnAck(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 1})

	payload := []byte("abcd")
	if err := w.Send(payload); err != nil {
		t.Fatalf("Send = %v", err)
	}
	frames := drainAll(w)
	if len(frames) != 1 {
		t.Fatalf("Drain wrote %d frames, want 1", len(frames))
	}

	done := make(chan error, 1)
	go func() { done <- w.Send([]byte("efgh")) }()
	select {
	case err := <-done:
		t.Fatalf("Send returned %v with the window full", err)
	case <-time.After(50 * time.Millisecond):
	}

	w.Ack(frames[0].Seq, frames[0].CRC, frames[0].FragIdx)
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Send after the ack = %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Send stayed blocked after the message was acknowledged")
	}
	if got := w.InFlight(); got != 1 {
		t.Fatalf("InFlight() = %d, want the second message alone", got)
	}
}

// TestWindowRetransmitsOnTheRetransmissionTimer replaces what the old sender
// did with a fixed ack budget: the fragment goes again once the timer built
// from the round trip expires, not two seconds later.
func TestWindowRetransmitsOnTheRetransmissionTimer(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 4})

	if err := w.Send([]byte("abcd")); err != nil {
		t.Fatalf("Send = %v", err)
	}
	first := drainAll(w)
	if len(first) != 1 {
		t.Fatalf("Drain wrote %d frames, want 1", len(first))
	}

	clock.advance(500 * time.Millisecond)
	if again := drainAll(w); len(again) != 0 {
		t.Fatalf("Drain retransmitted after %v, before the timer", 500*time.Millisecond)
	}
	clock.advance(time.Second)
	again := drainAll(w)
	if len(again) != 1 || again[0].Seq != first[0].Seq || again[0].FragIdx != first[0].FragIdx {
		t.Fatalf("Drain after the timer wrote %+v, want the fragment again", again)
	}
}

// TestWindowRetransmitsWhatTheAcksSkipped is the fast path: the peer
// acknowledged a later fragment, so the one before it is lost and goes again
// without waiting out any timer.
func TestWindowRetransmitsWhatTheAcksSkipped(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 4, FragmentSize: 4})

	if err := w.Send([]byte("abcdefghijklmnop")); err != nil { // four fragments
		t.Fatalf("Send = %v", err)
	}
	frames := drainAll(w)
	if len(frames) != 4 {
		t.Fatalf("Drain wrote %d frames, want 4", len(frames))
	}
	clock.advance(60 * time.Millisecond)
	w.Ack(frames[2].Seq, frames[2].CRC, frames[2].FragIdx)
	w.Ack(frames[3].Seq, frames[3].CRC, frames[3].FragIdx)
	clock.advance(40 * time.Millisecond)

	again := drainAll(w)
	if len(again) != 2 {
		t.Fatalf("Drain wrote %d frames, want the two the ack skipped", len(again))
	}
	for i, frame := range again {
		if frame.FragIdx != uint16(i) {
			t.Fatalf("frame %d is fragment %d", i, frame.FragIdx)
		}
	}
}

// TestWindowAckOnlyTakesItsOwn keeps the two senders apart: an ack for a
// message the window does not hold belongs to the one-at-a-time sender.
func TestWindowAckOnlyTakesItsOwn(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 4})

	if err := w.Send([]byte("abcd")); err != nil {
		t.Fatalf("Send = %v", err)
	}
	frames := drainAll(w)
	if w.Ack(frames[0].Seq+7, frames[0].CRC, 0) {
		t.Fatal("the window took an ack for a sequence number it never sent")
	}
	if w.Ack(frames[0].Seq, frames[0].CRC^1, 0) {
		t.Fatal("the window took an ack whose checksum does not match")
	}
	if !w.Ack(frames[0].Seq, frames[0].CRC, 0) {
		t.Fatal("the window refused the ack of its own fragment")
	}
}

// TestWindowPaceLimitsWhatOneDrainWrites keeps the stream inside what an SFU
// forwards: a drain writes what the rate has earned, not the whole window,
// and a lull banks one burst rather than the whole lull.
func TestWindowPaceLimitsWhatOneDrainWrites(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{
		Messages: 64, FragmentSize: 900, InitialRate: 90000, MaxRate: 90000,
	})
	for range 32 {
		if err := w.Send(make([]byte, 900)); err != nil {
			t.Fatalf("Send = %v", err)
		}
	}
	// The pace banks at most one burst, 40 ms at 90 KB/s, whatever it is
	// given: four fragments, not the thirty-two queued.
	first := drainAll(w)
	if len(first) == 0 || len(first) > 5 {
		t.Fatalf("Drain wrote %d frames, want the few the burst allows", len(first))
	}
	clock.advance(200 * time.Millisecond)
	second := drainAll(w)
	if len(second) == 0 || len(second) > 5 {
		t.Fatalf("Drain wrote %d frames after 200 ms, want the few the burst allows", len(second))
	}
	if w.InFlight() != 32 {
		t.Fatalf("InFlight() = %d, want everything still unacknowledged", w.InFlight())
	}
}

// TestWindowSlowsDownWhenNothingComesBack is the SFU that stopped forwarding
// the stream: with no acknowledgement at all the pace falls to its floor, so
// the stream gets small enough to be forwarded again.
func TestWindowSlowsDownWhenNothingComesBack(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{
		Messages: 64, FragmentSize: 900, InitialRate: 500000, MinRate: 30000,
	})
	for range 8 {
		if err := w.Send(make([]byte, 900)); err != nil {
			t.Fatalf("Send = %v", err)
		}
	}
	frames := drainAll(w)
	if len(frames) == 0 {
		t.Fatal("Drain wrote nothing")
	}
	w.Ack(frames[0].Seq, frames[0].CRC, frames[0].FragIdx)
	if got := w.Rate(); got != 500000 {
		t.Fatalf("Rate() = %v before the path went quiet", got)
	}

	clock.advance(5 * time.Second)
	drainAll(w)
	if got := w.Rate(); got != 30000 {
		t.Fatalf("Rate() = %v after five seconds of silence, want the floor", got)
	}
}

// TestWindowGivesUpAndReportsAckTimeout keeps a dead path from parking the
// session above it forever.
func TestWindowGivesUpAndReportsAckTimeout(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 2, GiveUp: 5 * time.Second})

	if err := w.Send([]byte("abcd")); err != nil {
		t.Fatalf("Send = %v", err)
	}
	drainAll(w)
	clock.advance(6 * time.Second)
	drainAll(w)

	if err := w.Send([]byte("efgh")); !errors.Is(err, common.ErrAckTimeout) {
		t.Fatalf("Send after the give-up window = %v, want %v", err, common.ErrAckTimeout)
	}
}

// TestWindowResetStartsANewStream covers the reconnect: the peer has
// forgotten everything, so the window drops what was in flight, takes a new
// stream id and lets a blocked Send through.
func TestWindowResetStartsANewStream(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 1})

	if err := w.Send([]byte("abcd")); err != nil {
		t.Fatalf("Send = %v", err)
	}
	drainAll(w)
	before := w.ID()

	done := make(chan error, 1)
	go func() { done <- w.Send([]byte("efgh")) }()
	time.Sleep(20 * time.Millisecond)
	w.Reset()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Send after Reset = %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Reset left a Send blocked")
	}
	if w.ID() == before {
		t.Fatal("Reset kept the old stream id")
	}
	frames := drainAll(w)
	if len(frames) != 1 || frames[0].Stream != w.ID() || frames[0].Floor != frames[0].Seq {
		t.Fatalf("after Reset the window wrote %+v", frames)
	}
}

// TestWindowCloseReleasesSend keeps Close from leaving a caller parked.
func TestWindowCloseReleasesSend(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 1})
	if err := w.Send([]byte("abcd")); err != nil {
		t.Fatalf("Send = %v", err)
	}

	done := make(chan error, 1)
	go func() { done <- w.Send([]byte("efgh")) }()
	time.Sleep(20 * time.Millisecond)
	w.Close()

	select {
	case err := <-done:
		if !errors.Is(err, common.ErrWindowClosed) {
			t.Fatalf("Send after Close = %v, want %v", err, common.ErrWindowClosed)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Close left a Send blocked")
	}
}

// TestWindowFragmentsCarryTheirOwnChecksums keeps the frames a window writes
// readable by the receiver's reassembly.
func TestWindowFragmentsCarryTheirOwnChecksums(t *testing.T) {
	clock := newTestClock()
	w := newTestWindow(t, clock, common.WindowConfig{Messages: 2, FragmentSize: 4})

	payload := []byte("abcdefgh")
	if err := w.Send(payload); err != nil {
		t.Fatalf("Send = %v", err)
	}
	frames := drainAll(w)
	if len(frames) != 2 {
		t.Fatalf("Drain wrote %d frames, want 2", len(frames))
	}
	whole := crc32.ChecksumIEEE(payload)
	for i, frame := range frames {
		if frame.CRC != whole || int(frame.TotalLen) != len(payload) || frame.FragTotal != 2 {
			t.Fatalf("frame %d = %+v", i, frame)
		}
		if frame.FragCRC != crc32.ChecksumIEEE(payload[i*4:i*4+4]) {
			t.Fatalf("frame %d carries the wrong fragment checksum", i)
		}
	}
}
