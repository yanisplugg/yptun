package engine

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

const (
	reconnectFailureWindow = 5 * time.Minute
	reconnectBackoffStep   = 2 * time.Second
	reconnectBackoffMax    = 30 * time.Second
)

// ReconnectRequest reports how a reconnect request was handled.
type ReconnectRequest uint8

const (
	// ReconnectRejected means the session state or policy rejected the request.
	ReconnectRejected ReconnectRequest = iota
	// ReconnectQueued means the request claimed the reconnect queue slot.
	ReconnectQueued
	// ReconnectCoalesced means another request already owns the queue slot.
	ReconnectCoalesced
)

// ReconnectorConfig configures a reconnect supervisor.
type ReconnectorConfig struct {
	MaxAttempts   int
	CountFailures bool
	Reconnect     func(context.Context) error
	IsNonFailure  func(error) bool
	OnError       func(error)
	OnLimit       func(string)
	LimitReason   string
}

// Reconnector serializes and coalesces reconnect requests for an engine.
type Reconnector struct {
	maxAttempts   int
	countFailures bool
	reconnect     func(context.Context) error
	isNonFailure  func(error) bool
	onError       func(error)
	onLimit       func(string)
	limitReason   string

	queueOnce sync.Once
	queue     chan struct{}

	onReconnect     atomic.Pointer[func()]
	shouldReconnect atomic.Pointer[func() bool]
	onEnded         atomic.Pointer[func(string)]

	counterMu   sync.Mutex
	count       int
	windowStart time.Time
	lastRequest time.Time
	now         func() time.Time
}

// NewReconnector creates a reconnect supervisor with a size-one request queue.
func NewReconnector(cfg ReconnectorConfig) *Reconnector {
	r := &Reconnector{}
	r.Configure(cfg)
	return r
}

// Configure initializes an embedded reconnect supervisor before use.
func (r *Reconnector) Configure(cfg ReconnectorConfig) {
	r.maxAttempts = cfg.MaxAttempts
	r.countFailures = cfg.CountFailures
	r.reconnect = cfg.Reconnect
	r.isNonFailure = cfg.IsNonFailure
	r.onError = cfg.OnError
	r.onLimit = cfg.OnLimit
	r.limitReason = cfg.LimitReason
	r.now = time.Now
	r.queueOnce.Do(func() { r.queue = make(chan struct{}, 1) })
}

// SetReconnectCallback registers a callback for successful reconnects.
func (r *Reconnector) SetReconnectCallback(cb func()) {
	if cb == nil {
		r.onReconnect.Store(nil)
		return
	}
	r.onReconnect.Store(&cb)
}

// SetShouldReconnect registers the reconnect policy callback.
func (r *Reconnector) SetShouldReconnect(fn func() bool) {
	if fn == nil {
		r.shouldReconnect.Store(nil)
		return
	}
	r.shouldReconnect.Store(&fn)
}

// SetEndedCallback registers a callback for terminal reconnect failures.
func (r *Reconnector) SetEndedCallback(cb func(string)) {
	if cb == nil {
		r.onEnded.Store(nil)
		return
	}
	r.onEnded.Store(&cb)
}

// ShouldReconnect reports whether the current reconnect policy allows a retry.
func (r *Reconnector) ShouldReconnect() bool {
	fn := r.shouldReconnect.Load()
	return fn == nil || (*fn)()
}

// Request queues a reconnect unless session state or policy rejects it.
func (r *Reconnector) Request(closed, reconnecting bool) ReconnectRequest {
	if closed || reconnecting || !r.ShouldReconnect() {
		return ReconnectRejected
	}
	select {
	case r.reconnectQueue() <- struct{}{}:
		return ReconnectQueued
	default:
		return ReconnectCoalesced
	}
}

// Watch services reconnect requests until the context or done signal ends.
func (r *Reconnector) Watch(ctx context.Context, done <-chan struct{}) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-done:
			return
		case <-r.reconnectQueue():
			if r.handleAttempt(ctx, done) {
				return
			}
		}
	}
}

func (r *Reconnector) handleAttempt(ctx context.Context, done <-chan struct{}) bool {
	if r.countFailures {
		return r.handleFailureAttempts(ctx, done)
	}
	return r.handleRequestAttempt(ctx, done)
}

func (r *Reconnector) handleRequestAttempt(ctx context.Context, done <-chan struct{}) bool {
	// The bound is re-checked every iteration, not once before the loop.
	// Checking it once meant a permanently dead SFU was retried forever at a
	// fixed backoff and the limit callback - the only way the upper layer
	// learns the session is gone - never ran.
	for {
		count := r.nextRequestCount()
		if count > r.maxAttempts {
			r.reconnectLimitReached()
			return true
		}
		err := r.reconnect(ctx)
		if err == nil {
			r.Drain()
			return false
		}
		r.reportError(err)
		if waitReconnect(ctx, done, reconnectBackoff(count)) {
			return true
		}
	}
}

func (r *Reconnector) handleFailureAttempts(ctx context.Context, done <-chan struct{}) bool {
	for {
		failures := r.failureCount()
		if failures > r.maxAttempts {
			r.reconnectLimitReached()
			return true
		}
		backoff := reconnectBackoff(failures)
		err := r.reconnect(ctx)
		if err == nil || r.isExpectedNonFailure(err) {
			r.resetFailures()
			r.Drain()
			return false
		}
		r.reportError(err)
		r.recordFailure()
		if waitReconnect(ctx, done, backoff) {
			return true
		}
	}
}

func (r *Reconnector) nextRequestCount() int {
	r.counterMu.Lock()
	defer r.counterMu.Unlock()
	now := r.currentTime()
	if r.lastRequest.IsZero() || now.Sub(r.lastRequest) > reconnectFailureWindow {
		r.count = 0
	}
	r.count++
	r.lastRequest = now
	return r.count
}

func (r *Reconnector) failureCount() int {
	r.counterMu.Lock()
	defer r.counterMu.Unlock()
	if !r.windowStart.IsZero() && r.currentTime().Sub(r.windowStart) > reconnectFailureWindow {
		r.count = 0
		r.windowStart = time.Time{}
	}
	return r.count
}

func (r *Reconnector) recordFailure() {
	r.counterMu.Lock()
	defer r.counterMu.Unlock()
	r.count++
	if r.windowStart.IsZero() {
		r.windowStart = r.currentTime()
	}
}

func (r *Reconnector) resetFailures() {
	r.counterMu.Lock()
	r.count = 0
	r.windowStart = time.Time{}
	r.counterMu.Unlock()
}

func (r *Reconnector) currentTime() time.Time {
	if r.now != nil {
		return r.now()
	}
	return time.Now()
}

func (r *Reconnector) isExpectedNonFailure(err error) bool {
	return r.isNonFailure != nil && r.isNonFailure(err)
}

func (r *Reconnector) reportError(err error) {
	if r.onError != nil {
		r.onError(err)
	}
}

func (r *Reconnector) reconnectLimitReached() {
	if r.onLimit != nil {
		r.onLimit(r.limitReason)
		return
	}
	r.SignalEnded(r.limitReason)
}

func (r *Reconnector) reconnectQueue() chan struct{} {
	r.queueOnce.Do(func() { r.queue = make(chan struct{}, 1) })
	return r.queue
}

// Drain removes coalesced requests after a successful reconnect.
func (r *Reconnector) Drain() bool {
	drained := false
	for {
		select {
		case <-r.reconnectQueue():
			drained = true
		default:
			return drained
		}
	}
}

// NotifyReconnect invokes the successful reconnect callback.
func (r *Reconnector) NotifyReconnect() {
	if cb := r.onReconnect.Load(); cb != nil {
		(*cb)()
	}
}

// SignalEnded invokes the terminal callback.
func (r *Reconnector) SignalEnded(reason string) {
	if cb := r.onEnded.Load(); cb != nil {
		(*cb)(reason)
	}
}

func reconnectBackoff(count int) time.Duration {
	backoff := time.Duration(count) * reconnectBackoffStep
	if backoff > reconnectBackoffMax {
		return reconnectBackoffMax
	}
	return backoff
}

func waitReconnect(ctx context.Context, done <-chan struct{}, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return true
	case <-done:
		return true
	case <-timer.C:
		return false
	}
}
