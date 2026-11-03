package crypto

import (
	"bytes"
	"encoding/binary"
	"errors"
	"math"
	"sync"
	"testing"
)

const (
	testPSK        = "01234567890123456789012345678901"
	testDataAAD    = "olcrtc/muxconn/v2/data"
	testControlAAD = "olcrtc/muxconn/v2/control"
)

func newKeyPair(tb testing.TB) (*KeySet, *KeySet) {
	tb.Helper()
	client, err := NewKeySet([]byte(testPSK), Client)
	if err != nil {
		tb.Fatalf("NewKeySet(client) error = %v", err)
	}
	server, err := NewKeySet([]byte(testPSK), Server)
	if err != nil {
		tb.Fatalf("NewKeySet(server) error = %v", err)
	}
	return client, server
}

func TestNewKeySetRejectsInvalidInput(t *testing.T) {
	if _, err := NewKeySet([]byte("short"), Client); !errors.Is(err, ErrInvalidKeySize) {
		t.Fatalf("short PSK error = %v, want %v", err, ErrInvalidKeySize)
	}
	if _, err := NewKeySet([]byte(testPSK), Role(99)); !errors.Is(err, ErrInvalidRole) {
		t.Fatalf("invalid role error = %v, want %v", err, ErrInvalidRole)
	}
}

func TestDirectionalRoundTrip(t *testing.T) {
	client, server := newKeyPair(t)
	testDirection(t, client, server, "client to server")
	testDirection(t, server, client, "server to client")
}

func testDirection(t *testing.T, sender, receiver *KeySet, payload string) {
	t.Helper()
	record, err := sender.Seal([]byte(payload), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal() error = %v", err)
	}
	got, err := receiver.Open(record, []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if string(got) != payload {
		t.Fatalf("Open() = %q, want %q", got, payload)
	}
}

func TestReflectedRecordFailsAuthentication(t *testing.T) {
	client, _ := newKeyPair(t)
	record, err := client.Seal([]byte("reflect"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal() error = %v", err)
	}
	if _, err := client.Open(record, []byte(testDataAAD)); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("Open(reflection) error = %v, want %v", err, ErrAuthentication)
	}
}

func TestPlaneAADIsolation(t *testing.T) {
	client, server := newKeyPair(t)
	data, err := client.Seal([]byte("data"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal(data) error = %v", err)
	}
	if _, openErr := server.Open(data, []byte(testControlAAD)); !errors.Is(openErr, ErrAuthentication) {
		t.Fatalf("Open(data as control) error = %v, want %v", openErr, ErrAuthentication)
	}
	if _, openErr := server.Open(data, []byte(testDataAAD)); openErr != nil {
		t.Fatalf("Open(data) after AAD failure error = %v", openErr)
	}

	control, err := client.Seal([]byte("control"), []byte(testControlAAD))
	if err != nil {
		t.Fatalf("Seal(control) error = %v", err)
	}
	if _, err := server.Open(control, []byte(testDataAAD)); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("Open(control as data) error = %v, want %v", err, ErrAuthentication)
	}
	if _, err := server.Open(control, []byte(testControlAAD)); err != nil {
		t.Fatalf("Open(control) after AAD failure error = %v", err)
	}
}

func TestRecordLayoutAndBufferAPI(t *testing.T) {
	client, server := newKeyPair(t)
	dst := make([]byte, 3, 3+WireOverhead+7)
	copy(dst, "pre")
	sealed, err := client.SealInto(dst, []byte("payload"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("SealInto() error = %v", err)
	}
	record := sealed[3:]
	if len(record) != WireOverhead+len("payload") {
		t.Fatalf("record size = %d, want %d", len(record), WireOverhead+len("payload"))
	}
	if string(record[:len(recordMagic)]) != recordMagic {
		t.Fatalf("magic = %q, want %q", record[:len(recordMagic)], recordMagic)
	}
	if counter := binary.BigEndian.Uint64(record[len(recordMagic):]); counter != 1 {
		t.Fatalf("counter = %d, want 1", counter)
	}
	opened, err := server.OpenInto([]byte("pre"), record, []byte(testDataAAD))
	if err != nil {
		t.Fatalf("OpenInto() error = %v", err)
	}
	if string(opened) != "prepayload" {
		t.Fatalf("OpenInto() = %q, want %q", opened, "prepayload")
	}
}

func TestReplayDuplicate(t *testing.T) {
	client, server := newKeyPair(t)
	record, err := client.Seal([]byte("once"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal() error = %v", err)
	}
	if _, err := server.Open(record, []byte(testDataAAD)); err != nil {
		t.Fatalf("first Open() error = %v", err)
	}
	if _, err := server.Open(record, []byte(testDataAAD)); !errors.Is(err, ErrReplayDuplicate) {
		t.Fatalf("second Open() error = %v, want %v", err, ErrReplayDuplicate)
	}
}

func TestReplayAcceptsOutOfOrderAcrossPlanes(t *testing.T) {
	client, server := newKeyPair(t)
	data, err := client.Seal([]byte("one"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal(data) error = %v", err)
	}
	control, err := client.Seal([]byte("two"), []byte(testControlAAD))
	if err != nil {
		t.Fatalf("Seal(control) error = %v", err)
	}
	if _, err := server.Open(control, []byte(testControlAAD)); err != nil {
		t.Fatalf("Open(counter 2) error = %v", err)
	}
	if _, err := server.Open(data, []byte(testDataAAD)); err != nil {
		t.Fatalf("Open(counter 1) error = %v", err)
	}
}

func TestReplayRejectsRecordOlderThanWindow(t *testing.T) {
	client, server := newKeyPair(t)
	records := make([][]byte, replayWindowSize+1)
	for i := range records {
		var err error
		records[i], err = client.Seal([]byte("record"), []byte(testDataAAD))
		if err != nil {
			t.Fatalf("Seal(%d) error = %v", i, err)
		}
	}
	if _, err := server.Open(records[len(records)-1], []byte(testDataAAD)); err != nil {
		t.Fatalf("Open(newest) error = %v", err)
	}
	if _, err := server.Open(records[0], []byte(testDataAAD)); !errors.Is(err, ErrReplayTooOld) {
		t.Fatalf("Open(oldest) error = %v, want %v", err, ErrReplayTooOld)
	}
}

func TestServerAcceptsIndependentClientPrefixes(t *testing.T) {
	clientA, server := newKeyPair(t)
	clientB, err := NewKeySet([]byte(testPSK), Client)
	if err != nil {
		t.Fatalf("NewKeySet(client B) error = %v", err)
	}
	for name, client := range map[string]*KeySet{"a": clientA, "b": clientB} {
		record, sealErr := client.Seal([]byte(name), []byte(testDataAAD))
		if sealErr != nil {
			t.Fatalf("Seal(client %s) error = %v", name, sealErr)
		}
		if _, openErr := server.Open(record, []byte(testDataAAD)); openErr != nil {
			t.Fatalf("Open(client %s) error = %v", name, openErr)
		}
	}
	if n := len(server.replay.lanes[testDataAAD]); n != 2 {
		t.Fatalf("sender states on the data lane = %d, want 2", n)
	}
}

func TestReplayStateUsesBoundedLRU(t *testing.T) {
	_, server := newKeyPair(t)
	for i := 0; i <= maxReplaySenders; i++ {
		client, err := NewKeySet([]byte(testPSK), Client)
		if err != nil {
			t.Fatalf("NewKeySet(client %d) error = %v", i, err)
		}
		state, err := client.laneState([]byte(testDataAAD))
		if err != nil {
			t.Fatalf("laneState(client %d) error = %v", i, err)
		}
		binary.BigEndian.PutUint64(state.prefix[noncePrefixSize-8:], uint64(i))
		record, err := client.Seal(nil, []byte(testDataAAD))
		if err != nil {
			t.Fatalf("Seal(client %d) error = %v", i, err)
		}
		if _, err := server.Open(record, []byte(testDataAAD)); err != nil {
			t.Fatalf("Open(client %d) error = %v", i, err)
		}
	}
	if n := server.replay.lru.Len(); n != maxReplaySenders {
		t.Fatalf("sender states = %d, want %d", n, maxReplaySenders)
	}
	var first [noncePrefixSize]byte
	if _, ok := server.replay.lanes[testDataAAD][first]; ok {
		t.Fatal("least-recently-used sender was not evicted")
	}
}

func TestOpenRejectsMalformedRecords(t *testing.T) {
	client, server := newKeyPair(t)
	for _, size := range []int{0, 1, WireOverhead - 1} {
		if _, err := server.Open(make([]byte, size), []byte(testDataAAD)); !errors.Is(err, ErrRecordTooShort) {
			t.Fatalf("Open(size %d) error = %v, want %v", size, err, ErrRecordTooShort)
		}
	}
	record, err := client.Seal([]byte("payload"), []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal() error = %v", err)
	}
	badMagic := bytes.Clone(record)
	badMagic[0] ^= 0xff
	if _, err := server.Open(badMagic, []byte(testDataAAD)); !errors.Is(err, ErrBadRecordMagic) {
		t.Fatalf("Open(bad magic) error = %v, want %v", err, ErrBadRecordMagic)
	}
	if _, err := server.Open(record[:len(record)-1], []byte(testDataAAD)); !errors.Is(err, ErrAuthentication) {
		t.Fatalf("Open(truncated tag) error = %v, want %v", err, ErrAuthentication)
	}
}

func TestConcurrentSealAndOpen(t *testing.T) {
	client, server := newKeyPair(t)
	const workers = replayWindowSize
	records := make([][]byte, workers)
	errs := make(chan error, workers)
	var wg sync.WaitGroup
	wg.Add(workers)
	for i := range workers {
		go func() {
			defer wg.Done()
			var err error
			records[i], err = client.Seal([]byte("record"), []byte(testDataAAD))
			if err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	wg.Add(workers)
	for i := range workers {
		go func() {
			defer wg.Done()
			if _, err := server.Open(records[i], []byte(testDataAAD)); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Errorf("concurrent record error = %v", err)
	}
}

func TestCounterExhaustionDoesNotWrap(t *testing.T) {
	client, _ := newKeyPair(t)
	state, err := client.laneState([]byte(testDataAAD))
	if err != nil {
		t.Fatalf("laneState() error = %v", err)
	}
	state.counter.Store(math.MaxUint64 - 1)
	record, err := client.Seal(nil, []byte(testDataAAD))
	if err != nil {
		t.Fatalf("Seal(max counter) error = %v", err)
	}
	if counter := binary.BigEndian.Uint64(record[len(recordMagic):]); counter != math.MaxUint64 {
		t.Fatalf("counter = %d, want %d", counter, uint64(math.MaxUint64))
	}
	if _, err := client.Seal(nil, []byte(testDataAAD)); !errors.Is(err, ErrCounterExhausted) {
		t.Fatalf("Seal(after max) error = %v, want %v", err, ErrCounterExhausted)
	}
}

func BenchmarkSealInto(b *testing.B) {
	client, _ := newKeyPair(b)
	payload := bytes.Repeat([]byte{0xab}, 12*1024)
	buf := make([]byte, 0, len(payload)+WireOverhead)
	aad := []byte(testDataAAD)
	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		if _, err := client.SealInto(buf[:0], payload, aad); err != nil {
			b.Fatalf("SealInto() error = %v", err)
		}
	}
}

func BenchmarkRecordRoundTrip(b *testing.B) {
	client, server := newKeyPair(b)
	payload := bytes.Repeat([]byte{0xab}, 12*1024)
	aad := []byte(testDataAAD)
	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		record, err := client.Seal(payload, aad)
		if err != nil {
			b.Fatalf("Seal() error = %v", err)
		}
		if _, err := server.Open(record, aad); err != nil {
			b.Fatalf("Open() error = %v", err)
		}
	}
}

func mustSeal(tb testing.TB, keys *KeySet, plaintext, aad string) []byte {
	tb.Helper()
	record, err := keys.Seal([]byte(plaintext), []byte(aad))
	if err != nil {
		tb.Fatalf("Seal(%q) error = %v", aad, err)
	}
	return record
}

func recordPrefix(record []byte) [noncePrefixSize]byte {
	var prefix [noncePrefixSize]byte
	copy(prefix[:], record[len(recordMagic)+8:recordHeaderSize])
	return prefix
}

func recordCounter(record []byte) uint64 {
	return binary.BigEndian.Uint64(record[len(recordMagic):])
}

func TestLanesNumberRecordsIndependently(t *testing.T) {
	_, server := newKeyPair(t)
	data := mustSeal(t, server, "data", testDataAAD)
	control := mustSeal(t, server, "control", testControlAAD)
	if recordPrefix(data) == recordPrefix(control) {
		t.Fatal("data and control lanes share a sender prefix")
	}
	if recordCounter(data) != 1 || recordCounter(control) != 1 {
		t.Fatalf("counters = %d/%d, want each lane to start at 1", recordCounter(data), recordCounter(control))
	}
}

// The data KCP still holds a backlog when the idle control KCP delivers a pong
// at once; that is the order a client sees under load, and with one window for
// both lanes it aged the whole backlog out (409 rejects in one 80 s run).
func TestControlRecordAheadDoesNotAgeOutDataBacklog(t *testing.T) {
	client, server := newKeyPair(t)
	backlog := make([][]byte, replayWindowSize*4)
	for i := range backlog {
		backlog[i] = mustSeal(t, server, "bulk", testDataAAD)
	}
	pong := mustSeal(t, server, "pong", testControlAAD)
	if _, err := client.Open(pong, []byte(testControlAAD)); err != nil {
		t.Fatalf("Open(control) error = %v", err)
	}
	for i, record := range backlog {
		if _, err := client.Open(record, []byte(testDataAAD)); err != nil {
			t.Fatalf("Open(data %d) error = %v; a record on another lane must not age this lane out", i, err)
		}
	}
}

// A peer from before lanes had their own prefixes numbers every lane from one
// counter. Its backlog must survive a control record too, and a replay within
// a lane must still be caught.
func TestReceiverIsolatesLanesOfASharedPrefixSender(t *testing.T) {
	client, server := newKeyPair(t)
	shared, err := server.laneState([]byte(testDataAAD))
	if err != nil {
		t.Fatalf("laneState() error = %v", err)
	}
	backlog := make([][]byte, replayWindowSize*4)
	for i := range backlog {
		backlog[i], err = sealWith(shared, nil, []byte("bulk"), []byte(testDataAAD))
		if err != nil {
			t.Fatalf("sealWith(data %d) error = %v", i, err)
		}
	}
	pong, err := sealWith(shared, nil, []byte("pong"), []byte(testControlAAD))
	if err != nil {
		t.Fatalf("sealWith(control) error = %v", err)
	}
	if recordPrefix(pong) != recordPrefix(backlog[0]) {
		t.Fatal("test setup: the legacy sender must share one prefix across lanes")
	}
	if _, err := client.Open(pong, []byte(testControlAAD)); err != nil {
		t.Fatalf("Open(control) error = %v", err)
	}
	for i, record := range backlog {
		if _, err := client.Open(record, []byte(testDataAAD)); err != nil {
			t.Fatalf("Open(data %d) error = %v", i, err)
		}
	}
	// Still caught inside the window; backlog[0] is legitimately too old by
	// now, so the newest record is what proves the lane still tracks replays.
	newest := len(backlog) - 1
	if _, err := client.Open(backlog[newest], []byte(testDataAAD)); !errors.Is(err, ErrReplayDuplicate) {
		t.Fatalf("replayed Open(data %d) error = %v, want %v", newest, err, ErrReplayDuplicate)
	}
}

// TestReorderStatsTellLossFromReordering pins what the counters mean: a record
// that jumps past its predecessor counts the predecessor as skipped, and the
// predecessor arriving afterwards counts as late. A relay that reorders fills
// its gaps; one that drops leaves them, which is the difference a session that
// died of "frame too large" needs explained (olcbox#23).
func TestReorderStatsTellLossFromReordering(t *testing.T) {
	client, server := newKeyPair(t)
	records := make([][]byte, 0, 5)
	for i := range 5 {
		rec, err := client.Seal([]byte{byte(i)}, []byte(testDataAAD))
		if err != nil {
			t.Fatalf("Seal(%d) error = %v", i, err)
		}
		records = append(records, rec)
	}
	for _, i := range []int{0, 1, 3, 4} {
		if _, err := server.Open(records[i], []byte(testDataAAD)); err != nil {
			t.Fatalf("Open(%d) error = %v", i, err)
		}
	}
	if got := server.ReorderStats([]byte(testDataAAD)); got != (ReorderStats{Skipped: 1}) {
		t.Fatalf("after a gap: ReorderStats = %+v, want Skipped:1 Late:0", got)
	}
	if _, err := server.Open(records[2], []byte(testDataAAD)); err != nil {
		t.Fatalf("Open(2) late error = %v", err)
	}
	if got := server.ReorderStats([]byte(testDataAAD)); got != (ReorderStats{Skipped: 1, Late: 1}) {
		t.Fatalf("after the gap was filled: ReorderStats = %+v, want Skipped:1 Late:1", got)
	}
	if got := server.ReorderStats([]byte(testControlAAD)); got != (ReorderStats{}) {
		t.Fatalf("control lane ReorderStats = %+v, want zero", got)
	}
}
