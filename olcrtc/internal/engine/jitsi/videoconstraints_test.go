package jitsi

// ai-generated: the whole file (every bridge a session opens establishes the
// receiver constraints, which is what a rejoin used to skip).

import (
	"context"
	"errors"
	"testing"

	"github.com/pion/webrtc/v4"
	"github.com/zarazaex69/j"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// videoSession builds a session that carries video, as the video transports
// configure one.
func videoSession(t *testing.T) *Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{
		URL:   testHost,
		Extra: map[string]string{credentialKeyRoom: testRoom},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	js, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	if err := js.AddVideoTrack(webrtc.TrackLocal(nil)); err != nil {
		t.Fatalf("AddVideoTrack: %v", err)
	}
	return js
}

// TestEveryBridgeOpenAsksForThePeersVideo is issue #9's rejoin: JVB forwards
// a participant's video only to an endpoint that asked for it, and the ask
// goes over the bridge. A rejoin opens a new bridge, so it has to ask again,
// or the conference comes back up carrying nothing.
func TestEveryBridgeOpenAsksForThePeersVideo(t *testing.T) {
	session := videoSession(t)
	asked := 0
	session.askVideo = func(context.Context, *j.Session) error {
		asked++
		return nil
	}

	for _, open := range []string{"first join", "rejoin"} {
		if err := session.openBridge(context.Background(), &j.Session{}, "", "test",
			func(context.Context) error { return nil }); err != nil {
			t.Fatalf("openBridge (%s): %v", open, err)
		}
	}
	if asked != 2 {
		t.Fatalf("two bridge opens asked for video %d times, want 2", asked)
	}
}

// TestBridgeOpenStaysQuietWithoutVideo keeps a bytestream-only session from
// asking JVB for video it never reads.
func TestBridgeOpenStaysQuietWithoutVideo(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{
		URL: testHost, Extra: map[string]string{credentialKeyRoom: testRoom},
		OnData: func([]byte) {},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer func() { _ = sess.Close() }()
	session, ok := sess.(*Session)
	if !ok {
		t.Fatal("sess is not *Session")
	}
	session.askVideo = func(context.Context, *j.Session) error {
		t.Fatal("a bytestream-only session asked for video")
		return nil
	}
	if err := session.openBridge(context.Background(), &j.Session{}, "", "test",
		func(context.Context) error { return nil }); err != nil {
		t.Fatalf("openBridge: %v", err)
	}
}

// TestBridgeOpenSurvivesAVideoRequestFailure keeps a bridge usable when the
// constraints do not go through: the bytes path does not depend on them.
func TestBridgeOpenSurvivesAVideoRequestFailure(t *testing.T) {
	session := videoSession(t)
	session.askVideo = func(context.Context, *j.Session) error {
		return errors.New("bridge refused")
	}
	if err := session.openBridge(context.Background(), &j.Session{}, "", "test",
		func(context.Context) error { return nil }); err != nil {
		t.Fatalf("openBridge: %v", err)
	}
	if !session.bridgeReady.Load() {
		t.Fatal("a refused video request left the bridge closed")
	}
}
