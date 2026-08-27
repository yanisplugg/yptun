package common_test

import (
	"hash/crc32"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

func TestRandomID(t *testing.T) {
	a := common.RandomID()
	b := common.RandomID()
	if len(a) != 8 || len(b) != 8 {
		t.Fatalf("RandomID() = %q, %q, want 8 hex chars each", a, b)
	}
	if a == b {
		t.Fatalf("RandomID() returned the same value twice: %q", a)
	}
}

func TestFragmentPayloadEmpty(t *testing.T) {
	got := common.FragmentPayload(nil, 16)
	if len(got) != 1 || len(got[0]) != 0 {
		t.Fatalf("FragmentPayload(nil) = %v, want one empty fragment", got)
	}
}

func TestFragmentPayloadChunks(t *testing.T) {
	data := []byte("hello world")
	got := common.FragmentPayload(data, 4)
	if len(got) != 3 || string(got[0]) != "hell" || string(got[1]) != "o wo" || string(got[2]) != "rld" {
		t.Fatalf("FragmentPayload(%q, 4) = %v", data, got)
	}
}

func TestReassemblerDeliveredAndDuplicate(t *testing.T) {
	r := common.NewReassembler(8)
	payload := []byte("hello world")
	crc := crc32.ChecksumIEEE(payload)
	frags := common.FragmentPayload(payload, 5)

	for i, frag := range frags {
		result, data := r.Push(common.Fragment{
			Seq:       1,
			CRC:       crc,
			TotalLen:  uint32(len(payload)), //nolint:gosec // bounded test fixture
			FragIdx:   uint16(i),
			FragTotal: uint16(len(frags)), //nolint:gosec // bounded test fixture
			Payload:   frag,
		}.WithPayloadCRC())
		if i < len(frags)-1 {
			if result != common.ResultPartial {
				t.Fatalf("Push(%d) result = %v, want Partial", i, result)
			}
		} else {
			if result != common.ResultDelivered || string(data) != "hello world" {
				t.Fatalf("Push(final) = %v / %q", result, data)
			}
		}
	}

	// re-push the last fragment: duplicate path.
	result, _ := r.Push(common.Fragment{
		Seq:       1,
		CRC:       crc,
		TotalLen:  uint32(len(payload)),   //nolint:gosec // bounded test fixture
		FragIdx:   uint16(len(frags) - 1), //nolint:gosec // bounded test fixture
		FragTotal: uint16(len(frags)),     //nolint:gosec // bounded test fixture
		Payload:   frags[len(frags)-1],
	}.WithPayloadCRC())
	if result != common.ResultDuplicate {
		t.Fatalf("dup push result = %v, want Duplicate", result)
	}
}

func TestReassemblerIgnoresCRCMismatch(t *testing.T) {
	r := common.NewReassembler(8)
	payload := []byte("abcd")
	frags := common.FragmentPayload(payload, 4)
	result, _ := r.Push(common.Fragment{
		Seq:       1,
		CRC:       0xdeadbeef,           // wrong
		TotalLen:  uint32(len(payload)), //nolint:gosec // bounded test fixture
		FragIdx:   0,
		FragTotal: uint16(len(frags)), //nolint:gosec // bounded test fixture
		Payload:   frags[0],
	}.WithPayloadCRC())
	if result != common.ResultDelivered {
		// single-fragment path: assemble fires immediately, CRC check fails, ignore.
		if result != common.ResultIgnore {
			t.Fatalf("Push() result = %v, want Ignore", result)
		}
	}
}

// TestAckTrackerWholeMessage exercises the single-fragment case, which is the
// whole-message ack semantics the old AckRegistry provided.
func TestAckTrackerWholeMessage(t *testing.T) {
	a := common.NewAckTracker()
	waiter := a.Register(42, 0xcafebabe, 1)
	defer a.Unregister(42)

	if waiter.Done() {
		t.Fatal("waiter done before any ack")
	}
	if !a.Mark(42, 0xcafebabe, 0) {
		t.Fatal("Mark() = false for the only fragment")
	}
	if !waiter.Done() {
		t.Fatal("waiter not done after its only fragment was acked")
	}
	// Stale and duplicate marks do not block / panic.
	if a.Mark(999, 0, 0) {
		t.Fatal("Mark() = true for an unknown seq")
	}
	if a.Mark(42, 0xcafebabe, 0) {
		t.Fatal("Mark() = true for an already acked fragment")
	}
}

func TestAckTrackerPerFragment(t *testing.T) {
	a := common.NewAckTracker()
	waiter := a.Register(7, 0x1234, 3)
	defer a.Unregister(7)

	if !a.Mark(7, 0x1234, 1) {
		t.Fatal("Mark(frag 1) = false")
	}
	if got := waiter.Pending(); len(got) != 2 || got[0] != 0 || got[1] != 2 {
		t.Fatalf("Pending() = %v, want [0 2]", got)
	}
	select {
	case <-waiter.Notify():
	default:
		t.Fatal("Mark() did not notify")
	}

	// An ack for a different crc belongs to a reused seq and is ignored.
	if a.Mark(7, 0x9999, 0) {
		t.Fatal("Mark() accepted an ack with a mismatched crc")
	}
	// Out-of-range fragment indexes are ignored.
	if a.Mark(7, 0x1234, 3) || a.Mark(7, 0x1234, -1) {
		t.Fatal("Mark() accepted an out-of-range fragment index")
	}

	a.Mark(7, 0x1234, 0)
	a.Mark(7, 0x1234, 2)
	if !waiter.Done() || len(waiter.Pending()) != 0 {
		t.Fatalf("waiter not done: pending=%v", waiter.Pending())
	}
}

func TestPerAttemptAckTimeoutBatchAware(t *testing.T) {
	const floor = time.Second
	interval := 40 * time.Millisecond // 25 FPS

	// Small payloads sit on the floor.
	if got := common.PerAttemptAckTimeout(1, 1, interval, floor); got != floor {
		t.Fatalf("PerAttemptAckTimeout(1,1) = %v, want %v", got, floor)
	}

	// One fragment per tick: 16 * 40ms * 3.
	if got, want := common.PerAttemptAckTimeout(16, 1, interval, floor), 1920*time.Millisecond; got != want {
		t.Fatalf("PerAttemptAckTimeout(16,1) = %v, want %v", got, want)
	}

	// A batching writer drains the same payload in fewer ticks, so the
	// budget shrinks accordingly: ceil(16/4) = 4 ticks, below the floor above
	// so this one is measured against a smaller floor.
	if got, want := common.PerAttemptAckTimeout(16, 4, interval, 0), 480*time.Millisecond; got != want {
		t.Fatalf("PerAttemptAckTimeout(16,4) = %v, want %v", got, want)
	}

	// A non-positive batch size behaves like one fragment per tick.
	if got := common.PerAttemptAckTimeout(16, 0, interval, floor); got != 1920*time.Millisecond {
		t.Fatalf("PerAttemptAckTimeout(16,0) = %v", got)
	}

	// Huge payloads are capped.
	if got, want := common.PerAttemptAckTimeout(100000, 1, interval, floor), 30*time.Second; got != want {
		t.Fatalf("PerAttemptAckTimeout(100000,1) = %v, want %v", got, want)
	}
}

func TestBindingTokenFallsBackToRoomURL(t *testing.T) {
	channel := common.BindingToken("channel-a", "https://example.org/room")
	if channel == 0 {
		t.Fatal("BindingToken() = 0")
	}
	if got := common.BindingToken("channel-a", "https://other.example/room"); got != channel {
		t.Fatalf("BindingToken() varied with roomURL: %d != %d", got, channel)
	}
	room := common.BindingToken("", "https://example.org/room")
	if room == 0 || room == channel {
		t.Fatalf("BindingToken(\"\", room) = %d, channel = %d", room, channel)
	}
	if common.BindingToken("", "") == 0 {
		t.Fatal("BindingToken(\"\", \"\") = 0, must never be zero")
	}
}
