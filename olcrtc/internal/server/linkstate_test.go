package server

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// What /stats reports about the carrier session, driven through the very
// callbacks bringUpLink installs. No provider is involved: linkStub is a
// transport whose join, drop and rejoin the test performs by hand.
//
// ai-generated: the whole file.

// errJoinRefused is what a linkStub with no room to join answers with.
var errJoinRefused = errors.New("linkstub: nothing to join")

// errAcceptFailed stands in for the read error that ends a server's accept
// loop when the link underneath it has stopped carrying anything.
var errAcceptFailed = errors.New("linkstub: accept failed")

// linkStub is the bare transport.Transport bringUpLink needs. It implements
// neither ControlPlane nor PeerTransport on purpose, so the server installs
// one broadcast session over it and no control plane of its own.
type linkStub struct {
	// joinErr is what Connect answers. observe, called inside Connect, is
	// the reading /stats would have given while the join was in flight.
	joinErr error
	observe func() LinkState
	during  LinkState

	// rebuildOnce/onRebuild run a test's hook once from inside a rebuild;
	// see duringRebuild.
	rebuildOnce sync.Once

	mu         sync.Mutex
	onEnded    func(string)
	onRejoined func()
	onRebuild  func()
	asked      []string
}

func (l *linkStub) Connect(context.Context) error {
	if l.observe != nil {
		l.during = l.observe()
	}
	return l.joinErr
}

func (l *linkStub) Send([]byte) error                   { return nil }
func (l *linkStub) Close() error                        { return nil }
func (l *linkStub) CanSend() bool                       { return true }
func (l *linkStub) WatchConnection(ctx context.Context) { <-ctx.Done() }
func (l *linkStub) SetShouldReconnect(func() bool)      {}
func (l *linkStub) ResetPeer()                          {}

// Features is asked for the payload cap to size a smux session with, which
// is the one call a rebuild makes on a transport this plain. duringRebuild
// hangs a hook on it, so a test can land something in the middle of a
// rebuild without a sleep to time it.
func (l *linkStub) Features() transport.Features {
	l.mu.Lock()
	hook := l.onRebuild
	l.mu.Unlock()
	if hook != nil {
		l.rebuildOnce.Do(hook)
	}
	return transport.Features{}
}

// duringRebuild arms fn to run once, from inside the next rebuild.
func (l *linkStub) duringRebuild(fn func()) {
	l.mu.Lock()
	l.onRebuild = fn
	l.mu.Unlock()
}

func (l *linkStub) SetEndedCallback(cb func(string)) {
	l.mu.Lock()
	l.onEnded = cb
	l.mu.Unlock()
}

func (l *linkStub) SetReconnectCallback(cb func()) {
	l.mu.Lock()
	l.onRejoined = cb
	l.mu.Unlock()
}

// Reconnect records what the server asked for instead of rebuilding
// anything; rejoin is how the test answers.
func (l *linkStub) Reconnect(reason string) {
	l.mu.Lock()
	l.asked = append(l.asked, reason)
	l.mu.Unlock()
}

// end reports the carrier session as over, the way an engine that has run
// out of reconnect attempts does.
func (l *linkStub) end(reason string) {
	l.mu.Lock()
	cb := l.onEnded
	l.mu.Unlock()
	if cb != nil {
		cb(reason)
	}
}

// rejoin reports a rebuild the engine completed.
func (l *linkStub) rejoin() {
	l.mu.Lock()
	cb := l.onRejoined
	l.mu.Unlock()
	if cb != nil {
		cb()
	}
}

// requests returns the reasons the server asked for a rebuild under.
func (l *linkStub) requests() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.asked...)
}

// linkRig is a server brought up over a linkStub through bringUpLink, the
// way Run does it.
type linkRig struct {
	srv    *Server
	link   *linkStub
	ctx    context.Context
	cancel context.CancelFunc
	err    error
	// stopped records the ended callback asking the run to stop. It
	// cancels ctx as the cancel Run passes does, because what the state
	// does after an end depends on that cancellation.
	stopped atomic.Bool
}

// newLinkRig builds the parts of a Server that bringUpLink and a rebuild
// touch, and brings it up over link.
func newLinkRig(t *testing.T, link *linkStub) *linkRig {
	t.Helper()
	srv := &Server{
		ring:         crypto.SingleEntry(newServerTestKeys(t), ""),
		meter:        newMeter(),
		health:       runtime.NewHealthTracker(nil),
		onClose:      func(string, string) {},
		peerSessions: make(map[string]*peerSession),
		peerStats:    make(map[string]peerStat),
		done:         make(chan struct{}),
	}
	link.observe = srv.LinkState
	name := "linkstate-" + t.Name()
	transport.Register(name, func(context.Context, transport.Config) (transport.Transport, error) {
		return link, nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	rig := &linkRig{srv: srv, link: link, ctx: ctx, cancel: cancel}
	rig.err = srv.bringUpLink(ctx, Config{Transport: name}, func() {
		rig.stopped.Store(true)
		cancel()
	})
	t.Cleanup(func() {
		cancel()
		srv.shutdown()
		srv.wg.Wait()
	})
	return rig
}

// pair puts a paired client on the server, which is what makes its accept
// loop treat a read error as a dead link rather than an idle one.
func (r *linkRig) pair(sessionID string) {
	r.srv.sessMu.Lock()
	r.srv.sessionID = sessionID
	r.srv.sessMu.Unlock()
}

func (r *linkRig) dataSession() *smux.Session {
	r.srv.sessMu.RLock()
	defer r.srv.sessMu.RUnlock()
	return r.srv.session
}

func TestLinkStateOfAServerThatHasNotStartedIsConnecting(t *testing.T) {
	if got := (&Server{}).LinkState(); got != LinkConnecting {
		t.Fatalf("a server that has not started reports %q, want %q", got, LinkConnecting)
	}
}

// TestLinkStateIsConnectingUntilTheCarrierSessionJoins pins the reading a
// liveness probe was missing: /stats answers from the moment the process
// binds it, which is well before there is a room to pair in.
func TestLinkStateIsConnectingUntilTheCarrierSessionJoins(t *testing.T) {
	rig := newLinkRig(t, &linkStub{})
	if rig.err != nil {
		t.Fatalf("bringUpLink() error = %v", rig.err)
	}
	if rig.link.during != LinkConnecting {
		t.Fatalf("while the join was in flight /stats reported %q, want %q", rig.link.during, LinkConnecting)
	}
	if got := rig.srv.LinkState(); got != LinkUp {
		t.Fatalf("after the join /stats reports %q, want %q", got, LinkUp)
	}
}

// TestLinkStateStaysConnectingWhenTheJoinFails is the room that is gone: a
// server that never joined must never read like one a client can pair with.
func TestLinkStateStaysConnectingWhenTheJoinFails(t *testing.T) {
	rig := newLinkRig(t, &linkStub{joinErr: errJoinRefused})
	if !errors.Is(rig.err, errJoinRefused) {
		t.Fatalf("bringUpLink() error = %v, want the refused join", rig.err)
	}
	if got := rig.srv.LinkState(); got != LinkConnecting {
		t.Fatalf("a server that never joined reports %q, want %q", got, LinkConnecting)
	}
}

// TestLinkStateGoesDownWhenTheCarrierSessionEnds covers the terminal half of
// down: the engine gave up and the run is being stopped.
func TestLinkStateGoesDownWhenTheCarrierSessionEnds(t *testing.T) {
	rig := newLinkRig(t, &linkStub{})
	if rig.err != nil {
		t.Fatalf("bringUpLink() error = %v", rig.err)
	}
	rig.link.end("the carrier ended the session")
	if !rig.stopped.Load() {
		t.Fatal("the ended callback no longer asks the run to stop")
	}
	if got := rig.srv.LinkState(); got != LinkDown {
		t.Fatalf("after the session ended /stats reports %q, want %q", got, LinkDown)
	}
}

// TestLinkStateIsDownWhileTheCarrierSessionIsRebuilt covers the other half:
// the server declared the link dead and the engine is rebuilding it, which
// is the window a probe has to be able to see.
func TestLinkStateIsDownWhileTheCarrierSessionIsRebuilt(t *testing.T) {
	rig := newLinkRig(t, &linkStub{})
	if rig.err != nil {
		t.Fatalf("bringUpLink() error = %v", rig.err)
	}
	rig.pair("paired-session")

	rig.srv.handleAcceptError(rig.ctx, rig.dataSession(), errAcceptFailed)

	if got := rig.srv.LinkState(); got != LinkDown {
		t.Fatalf("while the link is being rebuilt /stats reports %q, want %q", got, LinkDown)
	}
	if asked := rig.link.requests(); len(asked) != 1 || asked[0] != "liveness" {
		t.Fatalf("the rebuild the server asks for changed: %v", asked)
	}

	rig.link.rejoin()

	if got := rig.srv.LinkState(); got != LinkUp {
		t.Fatalf("after the rebuild /stats reports %q, want %q", got, LinkUp)
	}
}

// TestLinkStateStaysDownWhenTheSessionEndsDuringARebuild pins the ordering
// between the two: a rebuild is past the callback's own guard before the end
// arrives, and tearing peers down takes long enough for one to land there.
// The terminal down has to survive it.
func TestLinkStateStaysDownWhenTheSessionEndsDuringARebuild(t *testing.T) {
	rig := newLinkRig(t, &linkStub{})
	if rig.err != nil {
		t.Fatalf("bringUpLink() error = %v", rig.err)
	}
	rig.link.duringRebuild(func() { rig.link.end("the carrier ended the session") })

	rig.link.rejoin()

	if !rig.stopped.Load() {
		t.Fatal("the end never landed inside the rebuild")
	}
	if got := rig.srv.LinkState(); got != LinkDown {
		t.Fatalf("a rebuild that outlived the end reports %q, want %q", got, LinkDown)
	}
}
