package salutejazz

import (
	"context"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// TestASecondConnectEndsTheAttemptItReplaces pins what publishing a
// connection attempt has to do with the one it replaces. A Connect on a
// session that already has one used to overwrite the pointer and walk away:
// the old attempt kept its socket, its two peer connections and their TURN
// allocations, and the SFU kept relaying for a participant nobody was reading
// any more. reconnect happened to tear the old one down first, so only a
// caller that connected twice paid for it.
func TestASecondConnectEndsTheAttemptItReplaces(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "replace")
	first := sess.current()

	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("second connect: %v (fake: %s)", err, fake.lastError())
	}
	second := sess.current()
	if second == first {
		t.Fatal("the second connect did not publish an attempt of its own")
	}
	if !first.isDone() {
		t.Fatal("the attempt the second connect replaced was left running")
	}
	if first.subPC.Load() != nil || first.pubPC.Load() != nil {
		t.Fatal("the replaced attempt still owns its peer connections")
	}
	waitFor(t, 10*time.Second, "the replaced attempt's connector socket to close", func() bool {
		return fake.liveSockets() == 1
	})
}

// TestAnEndedSessionEndsTheReconnectWatch covers the verdict a session cannot
// reconnect its way out of, from the supervisor's side. Ending the session
// only raised a flag, so a request already dequeued went on failing and
// backing off - ten attempts, a minute and a half of them - for a room the
// session had been told is gone.
func TestAnEndedSessionEndsTheReconnectWatch(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	idle := newIdleSession(t)
	watching := make(chan struct{})
	go func() { idle.WatchConnection(ctx); close(watching) }()
	idle.signalEnded("the room is gone")
	select {
	case <-watching:
	case <-time.After(5 * time.Second):
		t.Fatal("the watch outlived the session that ended")
	}

	// The same thing with an attempt in flight: the supervisor is inside a
	// reconnect it has already dequeued when the verdict lands.
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "ended-watch")
	sess.joinTimeout = 200 * time.Millisecond
	release := fake.holdJoins()
	t.Cleanup(release)

	retrying := make(chan struct{})
	go func() { sess.WatchConnection(ctx); close(retrying) }()
	sess.Reconnect("a liveness probe said so")
	waitFor(t, 10*time.Second, "the rejoin to reach the connector", func() bool {
		return len(fake.joins()) == 2
	})
	sess.signalEnded("the room is gone")
	select {
	case <-retrying:
	case <-time.After(10 * time.Second):
		t.Fatal("the supervisor went on retrying a session that had ended")
	}
}

// TestAnAttemptThatIsGoneAsksForNoReconnect pins the guard readLoop has and
// the other three callers did not: a failure raised by an attempt that has
// already been torn down is a failure of a connection this session has moved
// on from. Asking for a rejoin on its behalf queues one behind the attempt
// that replaced it.
func TestAnAttemptThatIsGoneAsksForNoReconnect(t *testing.T) {
	sess := newIdleSession(t)
	api, err := newWebRTCAPI(nil, "")
	if err != nil {
		t.Fatal(err)
	}

	// The SFU's offer, on an attempt that is gone: nothing can answer it.
	gone := newGeneration(api)
	sess.teardown(gone)
	sess.handleMediaOut(gone, mediaOutJSON(t, map[string]any{"method": methodOffer}))
	if sess.Drain() {
		t.Fatal("an attempt that is gone asked for a rejoin after an offer it could not answer")
	}

	// The publisher negotiation, on an attempt that is gone. Its own select
	// may see the teardown first, so this is asked often enough that the
	// other branch is certain to be taken.
	for range 20 {
		doomed := newGeneration(api)
		engine.CloseSignal(doomed.subReady)
		sess.teardown(doomed)
		sess.negotiate(doomed)
		if sess.Drain() {
			t.Fatal("an attempt that is gone asked for a rejoin after a publisher it could not offer")
		}
	}

	// The keepalive, on an attempt that is gone: its socket was closed by the
	// teardown, so the ping cannot go out.
	sess.pingInterval = time.Millisecond
	for range 20 {
		doomed := newGeneration(api)
		storeString(&doomed.group, "fakegroup")
		sess.teardown(doomed)
		sess.pingLoop(doomed)
		if sess.Drain() {
			t.Fatal("an attempt that is gone asked for a rejoin after a ping it could not send")
		}
	}
}

// TestARefreshThatMovesTheConnectorMovesTheJoin covers what the refresh hook
// is there for: the connector URL is what the preconnect call hands out, and
// the official client makes that call before every join, so a rejoin has to
// land on the node the fresh credentials name and not on the one this session
// was talking to.
func TestARefreshThatMovesTheConnectorMovesTheJoin(t *testing.T) {
	firstURL, first := newFakeConnector(t)
	secondURL, second := newFakeConnector(t)

	sess, err := New(context.Background(), engine.Config{
		URL: firstURL, Token: "passw0rd", Name: "moved",
		Extra: map[string]string{"roomID": "abc123"},
		Refresh: func(context.Context) (engine.Credentials, error) {
			return engine.Credentials{URL: secondURL, Token: "passw0rd",
				Extra: map[string]string{"roomID": "abc123"}}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	if err := sess.Connect(context.Background()); err != nil {
		t.Fatalf("connect: %v (fake: %s)", err, first.lastError())
	}
	if err := sess.(*Session).reconnect(context.Background()); err != nil {
		t.Fatalf("reconnect: %v (fake: %s)", err, second.lastError())
	}

	if joins := len(first.joins()); joins != 1 {
		t.Fatalf("the connector the session started on saw %d joins, want the first one only", joins)
	}
	if joins := len(second.joins()); joins != 1 {
		t.Fatalf("the connector the refresh named saw %d joins, want the rejoin", joins)
	}
	waitFor(t, 10*time.Second, "the first connector's socket to close", func() bool {
		return first.liveSockets() == 0
	})
	if !sess.CanSend() {
		t.Fatal("the session cannot send after moving to the connector the refresh named")
	}
	for _, fake := range []*fakeSFU{first, second} {
		if failure := fake.lastError(); failure != "" {
			t.Fatalf("fake SFU error: %s", failure)
		}
	}
}

// TestAPongForAnotherAttemptDoesNotAnswerThisOne pins what a pong proves. It
// proves the link the ping went out on, and the waiters used to belong to the
// session rather than to the connection attempt that pinged: a frame from an
// attempt this session had moved on from released a ping in flight on the
// live one, and a dead link looked alive for as long as a superseded socket
// kept talking.
func TestAPongForAnotherAttemptDoesNotAnswerThisOne(t *testing.T) {
	url, fake := newFakeConnector(t)
	sess := connectSession(t, url, "pong")
	release := fake.holdPongs()
	t.Cleanup(release)

	pinged := make(chan error, 1)
	go func() { pinged <- sess.pingOnce() }()
	waitFor(t, 5*time.Second, "the ping to reach the connector", func() bool {
		return fake.pingsSeen() >= 1
	})

	stale := newGeneration(nil)
	sess.handleMediaOut(stale, mediaOutJSON(t, map[string]any{
		"method":    methodPong,
		"pong_resp": map[string]any{"lastPingTimestamp": "0", "timestamp": "0"},
	}))
	select {
	case err := <-pinged:
		t.Fatalf("a pong for another attempt answered this attempt's ping: %v", err)
	case <-time.After(300 * time.Millisecond):
	}

	release()
	select {
	case err := <-pinged:
		if err != nil {
			t.Fatalf("ping: %v (fake: %s)", err, fake.lastError())
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the connector's own pong never answered the ping")
	}

	// The round trip belongs to the attempt that measured it, and the one
	// that never pinged has none: the next ping carries the last measurement
	// of the link it goes out on.
	if rtt := sess.current().rtt.Load(); rtt < 100 {
		t.Fatalf("the attempt recorded a round trip of %d ms for a pong held for 300", rtt)
	}
	if rtt := stale.rtt.Load(); rtt != 0 {
		t.Fatalf("an attempt that never pinged recorded a round trip of %d ms", rtt)
	}
}
