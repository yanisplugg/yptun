package common

import "context"

// LifecycleSession is the provider-lifecycle subset every transport forwards
// verbatim. Both engine.Session and VideoSession satisfy it.
type LifecycleSession interface {
	SetShouldReconnect(fn func() bool)
	SetEndedCallback(cb func(string))
	WatchConnection(ctx context.Context)
	Reconnect(reason string)
}

// Lifecycle supplies the four provider-lifecycle methods every transport has
// to expose but none of them does anything with. Transports embed it so the
// pass-throughs are declared once instead of per package.
type Lifecycle struct {
	session LifecycleSession
}

// NewLifecycle binds the pass-throughs to session.
func NewLifecycle(session LifecycleSession) Lifecycle {
	return Lifecycle{session: session}
}

// SetShouldReconnect configures the reconnect policy.
func (l Lifecycle) SetShouldReconnect(fn func() bool) { l.session.SetShouldReconnect(fn) }

// SetEndedCallback registers end-of-session handling.
func (l Lifecycle) SetEndedCallback(cb func(string)) { l.session.SetEndedCallback(cb) }

// WatchConnection monitors the provider connection lifecycle.
func (l Lifecycle) WatchConnection(ctx context.Context) { l.session.WatchConnection(ctx) }

// Reconnect asks the provider to tear down and re-establish its connection.
func (l Lifecycle) Reconnect(reason string) { l.session.Reconnect(reason) }
