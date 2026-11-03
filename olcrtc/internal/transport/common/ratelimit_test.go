package common

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: the whole file.

func newTestLimiter(bytesPerSec, burst int, clock *time.Time) *PublishLimiter {
	l := NewPublishLimiter(bytesPerSec, burst)
	if l == nil {
		return nil
	}
	l.now = func() time.Time { return *clock }
	l.last = *clock
	return l
}

func TestPublishLimiterNilPassesEverything(t *testing.T) {
	var l *PublishLimiter
	if !l.Ready(1 << 20) {
		t.Fatal("nil limiter refused a sample")
	}
	l.Charge(1 << 20)
	if !l.Ready(1 << 20) {
		t.Fatal("nil limiter refused a sample after a charge")
	}
	if got := NewPublishLimiter(0, 10); got != nil {
		t.Fatalf("NewPublishLimiter(0, 10) = %v, want nil", got)
	}
}

func TestPublishLimiterSpendsAndRefills(t *testing.T) {
	clock := time.Unix(0, 0)
	l := newTestLimiter(1000, 2000, &clock)

	if !l.Ready(2000) {
		t.Fatal("a full bucket refused its own burst")
	}
	l.Charge(2000)
	if l.Ready(1) {
		t.Fatal("an empty bucket passed a byte")
	}

	clock = clock.Add(500 * time.Millisecond)
	if !l.Ready(500) {
		t.Fatal("half a second did not refill half the rate")
	}
	if l.Ready(501) {
		t.Fatal("half a second refilled more than the rate")
	}

	clock = clock.Add(time.Hour)
	if !l.Ready(2000) {
		t.Fatal("an idle hour left the bucket short of its burst")
	}
	l.Charge(2001)
	if l.Ready(1) {
		t.Fatal("the bucket refilled above its burst")
	}
}

func TestPublishLimiterWaitsOutDebt(t *testing.T) {
	clock := time.Unix(0, 0)
	l := newTestLimiter(1000, 1000, &clock)

	l.Charge(3000) // a write that never asked: control frames are not held back
	clock = clock.Add(time.Second)
	if l.Ready(1) {
		t.Fatal("one second of refill cleared two seconds of debt")
	}
	clock = clock.Add(2 * time.Second)
	if !l.Ready(1000) {
		t.Fatal("the debt was never paid off")
	}
}

func TestPublishLimiterJudgesAnOutsizedSampleAgainstTheBucket(t *testing.T) {
	clock := time.Unix(0, 0)
	l := newTestLimiter(1000, 1000, &clock)

	if !l.Ready(10_000) {
		t.Fatal("a sample larger than the bucket can never pass")
	}
	l.Charge(1)
	if l.Ready(10_000) {
		t.Fatal("an outsized sample passed a bucket that is not full")
	}
}

func TestPublishLimiterRaisesATinyBurst(t *testing.T) {
	clock := time.Unix(0, 0)
	l := newTestLimiter(10_000, 1, &clock)
	if !l.Ready(1000) {
		t.Fatal("burst was not raised to a tenth of the rate")
	}
}

func TestPublishLimiterIsSafeForConcurrentUse(t *testing.T) {
	const burst = 1 << 20
	l := NewPublishLimiter(1, burst) // a rate that refills nothing in a test
	var wg sync.WaitGroup
	var spent atomic.Int64
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 1000 {
				if l.Ready(100) {
					l.Charge(100)
					spent.Add(100)
				}
			}
		}()
	}
	wg.Wait()

	if got := spent.Load(); got > burst+8*100 {
		t.Fatalf("the bucket passed %d bytes, more than the %d it holds", got, burst)
	}
	if l.Ready(1 << 20) {
		t.Fatal("the bucket is still full after eight writers spent it")
	}
}

// ai-generated: the rest of the file (olcrtc#26).

// unlimitedSession is an engine session that declares no ceiling: it does not
// implement engine.PublishRateLimited at all.
type unlimitedSession struct{ engine.Session }

// limitedSession declares one.
type limitedSession struct {
	engine.Session
	limit int
}

func (s limitedSession) PublishRateLimit() int { return s.limit }

func TestPublishRateLimitReadsWhatASessionDeclares(t *testing.T) {
	if got := PublishRateLimit(unlimitedSession{}); got != 0 {
		t.Fatalf("a session that declares nothing reported %d, want 0", got)
	}
	if got := PublishRateLimit(limitedSession{limit: 1_200_000}); got != 1_200_000 {
		t.Fatalf("PublishRateLimit = %d, want 1200000", got)
	}
	if got := PublishRateLimit(nil); got != 0 {
		t.Fatalf("a nil session reported %d, want 0", got)
	}
	// What a transport does with each: one is paced, the other is not.
	if l := NewPublishLimiter(PublishRateLimit(unlimitedSession{}), 1000); l != nil {
		t.Fatal("a session that declares nothing produced a limiter")
	}
	if l := NewPublishLimiter(PublishRateLimit(limitedSession{limit: 1_200_000}), 1000); l == nil {
		t.Fatal("a session that declares a ceiling produced no limiter")
	}
}

func TestEngineVideoSessionForwardsThePublishRateLimit(t *testing.T) {
	v := &EngineVideoSession{session: limitedSession{limit: 900_000}}
	if got := v.PublishRateLimit(); got != 900_000 {
		t.Fatalf("EngineVideoSession.PublishRateLimit = %d, want 900000", got)
	}
	plain := &EngineVideoSession{session: unlimitedSession{}}
	if got := plain.PublishRateLimit(); got != 0 {
		t.Fatalf("EngineVideoSession.PublishRateLimit = %d, want 0", got)
	}
}
