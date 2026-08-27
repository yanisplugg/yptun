package common

import (
	"context"
	"fmt"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// VideoSession is the subset of engine.Session + engine.VideoTrackCapable the
// video-track transports rely on. It necessarily mirrors the engine's
// lifecycle + video contract, hence the method count.
type VideoSession interface {
	Connect(ctx context.Context) error
	Close() error
	SetReconnectCallback(cb func())
	SetShouldReconnect(fn func() bool)
	SetEndedCallback(cb func(string))
	WatchConnection(ctx context.Context)
	CanSend() bool
	Reconnect(reason string)
	AddTrack(track webrtc.TrackLocal) error
	SetTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver))
}

// SubscriberVideoSession extends VideoSession with the subscriber-only
// readiness probe. Transports that run a control plane independent of
// publisher negotiation (vp8channel) require it; the ack-based transports do
// not, so it stays an optional extension instead of bloating VideoSession.
type SubscriberVideoSession interface {
	VideoSession
	// SubscriberCanSend reports that the subscriber PC is connected even if
	// the publisher PC has not finished negotiating, so handshake traffic is
	// never blocked behind publisher setup.
	SubscriberCanSend() bool
}

// EngineVideoSession adapts engine.Session + engine.VideoTrackCapable to
// VideoSession. It drops the *webrtc.DataChannel argument from the engine
// reconnect callback (video transports do not use data channels) and exposes
// the video-track helpers under shorter names.
type EngineVideoSession struct {
	session engine.Session
	vt      engine.VideoTrackCapable
}

// NewEngineVideoSession wraps sess for the video transports. It returns
// ErrVideoTrackUnsupported when the engine cannot expose video tracks.
func NewEngineVideoSession(sess engine.Session) (*EngineVideoSession, error) {
	vt, ok := sess.(engine.VideoTrackCapable)
	if !ok {
		_ = sess.Close()
		return nil, ErrVideoTrackUnsupported
	}
	return &EngineVideoSession{session: sess, vt: vt}, nil
}

// Connect brings up the underlying engine session.
func (v *EngineVideoSession) Connect(ctx context.Context) error {
	if err := v.session.Connect(ctx); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	return nil
}

// Close tears down the underlying engine session.
func (v *EngineVideoSession) Close() error {
	if err := v.session.Close(); err != nil {
		return fmt.Errorf("close: %w", err)
	}
	return nil
}

// SetReconnectCallback registers cb for provider reconnects.
func (v *EngineVideoSession) SetReconnectCallback(cb func()) {
	v.session.SetReconnectCallback(cb)
}

// SetShouldReconnect configures the reconnect policy.
func (v *EngineVideoSession) SetShouldReconnect(fn func() bool) { v.session.SetShouldReconnect(fn) }

// SetEndedCallback registers end-of-session handling.
func (v *EngineVideoSession) SetEndedCallback(cb func(string)) { v.session.SetEndedCallback(cb) }

// WatchConnection monitors the provider connection lifecycle.
func (v *EngineVideoSession) WatchConnection(ctx context.Context) { v.session.WatchConnection(ctx) }

// CanSend reports whether the engine is ready to send.
func (v *EngineVideoSession) CanSend() bool { return v.session.CanSend() }

// SubscriberCanSend reports whether the subscriber PC alone is ready.
func (v *EngineVideoSession) SubscriberCanSend() bool { return v.session.SubscriberCanSend() }

// Reconnect asks the engine to rebuild the provider connection.
func (v *EngineVideoSession) Reconnect(reason string) { v.session.Reconnect(reason) }

// AddTrack publishes a local video track.
func (v *EngineVideoSession) AddTrack(track webrtc.TrackLocal) error {
	if err := v.vt.AddVideoTrack(track); err != nil {
		return fmt.Errorf("add track: %w", err)
	}
	return nil
}

// SetTrackHandler registers the remote-track callback.
func (v *EngineVideoSession) SetTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	v.vt.SetVideoTrackHandler(cb)
}
