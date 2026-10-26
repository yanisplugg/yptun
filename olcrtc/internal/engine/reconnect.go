package engine

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

const (
	reconnectFailureWindow = 5 * time.Minute
	reconnectBackoffStep   = 2 * time.Second
	reconnectBackoffMax    = 30 * time.Second

	// noRouteProbeInitial and noRouteProbeMax pace the probe socket while
	// the host refuses sockets (awaitRoute). noRouteMaxWait bounds the wait,
	// so a protector that refuses for some other reason still lets the
	// attempts run out and end the session.
	noRouteProbeInitial = time.Second
	noRouteProbeMax     = 8 * time.Second
	noRouteMaxWait      = 5 * time.Minute
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
	// RouteProbe says whether a socket can be opened right now; nil means
	// protect.ProbeRoute, which asks the host protector. Set in tests.
	RouteProbe func() bool
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

	routeProbe          func() bool
	noRouteProbeInitial time.Duration
	noRouteProbeMax     time.Duration
	noRouteMaxWait      time.Duration

	queueOnce sync.Once
	queue     chan struct{}
	// queued counts every request that reached the queue, so a drain after a
	// successful attempt can tell one raised before it from one raised
	// during it.
	queued atomic.Uint64

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
	r.routeProbe = cfg.RouteProbe
	if r.routeProbe == nil {
		r.routeProbe = protect.ProbeRoute
	}
	r.noRouteProbeInitial = noRouteProbeInitial
	r.noRouteProbeMax = noRouteProbeMax
	r.noRouteMaxWait = noRouteMaxWait
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
		r.queued.Add(1)
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
		if r.awaitRoute(ctx, done) {
			return true
		}
		count := r.nextRequestCount()
		if count > r.maxAttempts {
			r.reconnectLimitReached()
			return true
		}
		seen := r.queued.Load()
		err := r.reconnect(ctx)
		if err == nil {
			r.dropRequestsOlderThan(seen)
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
		if r.awaitRoute(ctx, done) {
			return true
		}
		failures := r.failureCount()
		if failures > r.maxAttempts {
			r.reconnectLimitReached()
			return true
		}
		backoff := reconnectBackoff(failures)
		seen := r.queued.Load()
		err := r.reconnect(ctx)
		if err == nil || r.isExpectedNonFailure(err) {
			r.resetFailures()
			r.dropRequestsOlderThan(seen)
			return false
		}
		r.reportError(err)
		r.recordFailure()
		if waitReconnect(ctx, done, backoff) {
			return true
		}
	}
}

// awaitRoute holds the next attempt while the host protector refuses
// sockets. A phone that has lost its network has no interface to dial from:
// an attempt there opens a hundred sockets to learn nothing, each one asking
// the host for an interface again, and counts towards the limit that ends
// the session for good (olcbox#37). One probe socket every few seconds says
// when a route is back; the wait is bounded so a protector that refuses for
// some other reason still lets the attempts run out. It reports true when
// ctx or done ended the wait, like waitReconnect.
func (r *Reconnector) awaitRoute(ctx context.Context, done <-chan struct{}) bool {
	probe := r.routeProbe
	if probe == nil || probe() {
		return false
	}
	started := r.currentTime()
	delay := r.noRouteProbeInitial
	logger.Infof("reconnect: no route out, the host refuses sockets; probing again in %s, for up to %s",
		delay, r.noRouteMaxWait)
	for {
		if waitReconnect(ctx, done, delay) {
			return true
		}
		if probe() {
			logger.Infof("reconnect: route is back after %s", r.currentTime().Sub(started).Round(time.Second))
			return false
		}
		if r.currentTime().Sub(started) >= r.noRouteMaxWait {
			logger.Warnf("reconnect: still no route after %s; attempting anyway", r.noRouteMaxWait)
			return false
		}
		if delay < r.noRouteProbeMax {
			delay = min(delay*2, r.noRouteProbeMax)
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
// dropRequestsOlderThan empties the queue unless something asked again while
// the attempt was running.
//
// A reconnect callback is not instant - the server's closes every peer of the
// session it is replacing - and a provider event raised inside that window is
// about a room this attempt never saw. Draining unconditionally threw it away
// and the engine stayed pointed at a room that had moved (#31).
//
// ai-generated: the whole function.
func (r *Reconnector) dropRequestsOlderThan(seen uint64) {
	if r.queued.Load() != seen {
		return
	}
	r.Drain()
}

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
