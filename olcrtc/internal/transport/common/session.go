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

// PublishRateLimit forwards the ceiling the engine's service polices, 0 when
// it polices none. ai-generated: this method (olcrtc#26).
func (v *EngineVideoSession) PublishRateLimit() int {
	return PublishRateLimit(v.session)
}

// PublishRateLimit reads the ceiling a session declares, in bytes a second,
// and returns 0 for a session that declares none. ai-generated: this
// function (olcrtc#26).
func PublishRateLimit(session any) int {
	limited, ok := session.(engine.PublishRateLimited)
	if !ok {
		return 0
	}
	return limited.PublishRateLimit()
}

// VideoCodec forwards the codec the engine's service takes, "" when the
// engine names none and the transport's own applies.
func (v *EngineVideoSession) VideoCodec() string {
	return VideoCodec(v.session)
}

// VideoCodec reads the codec a session names for published video, "" for a
// session that names none.
func VideoCodec(session any) string {
	selector, ok := session.(engine.VideoCodecSelector)
	if !ok {
		return ""
	}
	return selector.VideoCodec()
}

// SetKeyframeRequestHandler forwards cb to an engine that reports its relay's
// keyframe requests, and says whether it does.
func (v *EngineVideoSession) SetKeyframeRequestHandler(cb func()) bool {
	return OnKeyframeRequest(v.session, cb)
}

// OnKeyframeRequest registers cb with a session that reports keyframe
// requests and says whether it is one.
func OnKeyframeRequest(session any, cb func()) bool {
	source, ok := session.(engine.KeyframeRequestSource)
	if !ok {
		return false
	}
	source.SetKeyframeRequestHandler(cb)
	return true
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
