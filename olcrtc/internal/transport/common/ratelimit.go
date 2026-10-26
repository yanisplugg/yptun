package common

import (
	"math"
	"sync"
	"time"
)

// PublishLimiter is a byte token bucket in front of one video track. Every
// writer of a transport charges what it puts on the track, and the bulk
// writers ask first, so the sum of all of them stays under the rate the SFU
// on the other end tolerates from a publisher.
//
// A relay is a policer, not a fair queue: WB Stream removes a participant
// whose published video stays above its ceiling for about 40 s (olcrtc#26),
// and the frame ticker alone does not bound the rate - it bounds how often a
// sample goes out, and a sample carries up to a batch of KCP packets.
//
// ai-generated: the whole file.
type PublishLimiter struct {
	mu     sync.Mutex
	rate   float64 // bytes a second
	burst  float64 // bytes the bucket holds
	tokens float64
	last   time.Time
	// now reads the clock; only tests replace it.
	now func() time.Time
}

// NewPublishLimiter returns a limiter that passes bytesPerSec with a burst of
// burst bytes, or nil when bytesPerSec is not positive. A nil limiter is
// ready for anything and charges nothing, so callers need no nil check.
//
// burst is raised to a tenth of a second's worth of bytes when it is smaller:
// a bucket that cannot hold one writer tick of samples would stall the bulk
// path on every tick instead of pacing it.
func NewPublishLimiter(bytesPerSec, burst int) *PublishLimiter {
	if bytesPerSec <= 0 {
		return nil
	}
	if minBurst := bytesPerSec / 10; burst < minBurst {
		burst = minBurst
	}
	if burst <= 0 {
		burst = bytesPerSec
	}
	l := &PublishLimiter{
		rate:   float64(bytesPerSec),
		burst:  float64(burst),
		tokens: float64(burst),
		now:    time.Now,
	}
	l.last = l.now()
	return l
}

// Ready reports whether n bytes fit in the bucket now. It consumes nothing:
// what goes out is charged once, where the samples are written. A request
// larger than the whole bucket is judged against the bucket, so an outsized
// sample waits for a full bucket instead of never passing.
func (l *PublishLimiter) Ready(n int) bool {
	if l == nil {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.refill()
	want := float64(n)
	if want > l.burst {
		want = l.burst
	}
	return l.tokens >= want
}

// Charge takes n bytes out of the bucket whether they fit or not. Control
// frames and keepalives are never held back - they are small and the link
// dies without them - so they run the bucket into debt that the bulk path
// then waits out.
func (l *PublishLimiter) Charge(n int) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.refill()
	l.tokens -= float64(n)
}

func (l *PublishLimiter) refill() {
	now := l.now()
	elapsed := now.Sub(l.last)
	if elapsed <= 0 {
		return
	}
	l.last = now
	l.tokens = math.Min(l.tokens+l.rate*elapsed.Seconds(), l.burst)
}
