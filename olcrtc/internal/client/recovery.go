package client

import (
	"context"
	"sync"
)

// recovery names the one attempt that is re-establishing the session: the
// handshake loop a provider callback starts, or the fallback that stands in
// when that callback is late. A provider callback takes over from whatever
// runs, because it has just replaced the connection that attempt was working
// on. The fallback arms only while nothing has taken over since the session
// was given up, and a callback disarms it, so the two never run one after
// the other on the same outage (olcrtc#19).
//
// ai-generated: the whole file (olcrtc#19).
type recovery struct {
	mu   sync.Mutex
	gen  uint64
	stop context.CancelFunc
}

// generation identifies the current owner. Read before asking the provider
// for a new connection, it tells takeIf whether a callback came in between.
func (r *recovery) generation() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.gen
}

// take stops the current owner and makes the caller the owner. The context
// it returns ends when ctx does or when a later owner takes over.
func (r *recovery) take(ctx context.Context) (context.Context, uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.takeLocked(ctx)
}

// takeIf is take for a caller that acts only if nothing has taken over since
// generation expect. owned says the caller is the owner of that generation,
// renewing its own turn; anyone else is refused while an owner is running,
// even at the generation it read.
//
// Without that second refusal a liveness death that read the generation just
// before a provider callback took the recovery, and then waited for
// reconnectMu behind it, took the recovery back from the callback and left
// its handshake cancelled (review of #20).
func (r *recovery) takeIf(ctx context.Context, expect uint64, owned bool) (context.Context, uint64, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.gen != expect || (r.stop != nil && !owned) {
		return nil, 0, false
	}
	run, gen := r.takeLocked(ctx)
	return run, gen, true
}

func (r *recovery) takeLocked(ctx context.Context) (context.Context, uint64) {
	if r.stop != nil {
		r.stop()
	}
	r.gen++
	run, stop := context.WithCancel(ctx)
	r.stop = stop
	return run, r.gen
}

// release ends owner gen's context once it is done; an owner that was taken
// over has had it ended already.
func (r *recovery) release(gen uint64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.gen == gen && r.stop != nil {
		r.stop()
		r.stop = nil
	}
}
