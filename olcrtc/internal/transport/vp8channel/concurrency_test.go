package vp8channel

import (
	"sync"
	"testing"
	"time"
)

// TestWriteSampleLockedSerializesConcurrentWriters is the regression guard for
// issue #95. The server writes bulk data through per-peer pumps while
// writerLoop writes control/keepalive frames, both calling writeSampleLocked
// concurrently. pion's TrackLocalStaticSample.WriteSample is not safe for
// concurrent use: a frame's packetize step and its RTP emit are not atomic, so
// unsynchronized callers interleave packets on the wire and the remote VP8
// reassembler drops the corrupted frames. This test installs a sampleWriter
// that fails if two calls are ever in flight at once, then hammers it from many
// goroutines.
func TestWriteSampleLockedSerializesConcurrentWriters(t *testing.T) {
	var (
		inFlight int32
		mu       sync.Mutex
		overlaps int
	)

	p := &streamTransport{frameInterval: time.Second / 30}
	p.sampleWriter = func([]byte) bool {
		mu.Lock()
		inFlight++
		if inFlight > 1 {
			overlaps++
		}
		mu.Unlock()

		// Hold the "wire" briefly so any missing serialization manifests as
		// an observed overlap rather than a lucky interleave.
		time.Sleep(50 * time.Microsecond)

		mu.Lock()
		inFlight--
		mu.Unlock()
		return true
	}

	const (
		writers     = 16
		perWriter   = 64
	)
	var wg sync.WaitGroup
	wg.Add(writers)
	for range writers {
		go func() {
			defer wg.Done()
			payload := []byte{1, 2, 3, 4}
			for range perWriter {
				p.writeSampleLocked(payload)
			}
		}()
	}
	wg.Wait()

	if overlaps != 0 {
		t.Fatalf("writeSampleLocked allowed %d concurrent track writes; "+
			"WriteSample must be fully serialized (issue #95 regression)", overlaps)
	}
}
