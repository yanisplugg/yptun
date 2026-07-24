package server

import (
	"context"
	"net"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport"

	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/xtaci/smux"
)

// mkServerSess builds a server-side smux session over one end of a pipe.
// The far end is closed by the returned cleanup.
func mkServerSess(t *testing.T) (*smux.Session, func()) {
	t.Helper()
	a, b := net.Pipe()
	sess, err := smux.Server(a, smuxConfig(0))
	if err != nil {
		_ = a.Close()
		_ = b.Close()
		t.Fatalf("smux.Server() error = %v", err)
	}
	return sess, func() {
		_ = sess.Close()
		_ = a.Close()
		_ = b.Close()
	}
}

// TestSwapSessionAcceptsControlSessionInPeerRouting is the regression guard for
// issue #95: in peer-routing mode s.session is nil and the handshake/liveness
// loop runs on the control session. When that control loop dies it calls
// reinstallSession(controlSess) -> swapSession(controlSess, r). The old guard
// compared only against s.session, so nil != controlSess discarded the swap,
// acceptHandshake was never re-armed, and every later client hung forever in
// waitPeerHandshake. swapSession must accept a dying session that matches
// s.controlSess even when s.session is nil.
func TestSwapSessionAcceptsControlSessionInPeerRouting(t *testing.T) {
	cipher, err := cryptopkg.NewCipher("01234567890123456789012345678901")
	if err != nil {
		t.Fatalf("NewCipher() error = %v", err)
	}

	deadControl, cleanupDead := mkServerSess(t)
	defer cleanupDead()
	newData, cleanupND := mkServerSess(t)
	defer cleanupND()
	newControl, cleanupNC := mkServerSess(t)
	defer cleanupNC()

	ln := &peerRoutingStub{}
	s := &Server{
		ln:          ln,
		cipher:      cipher,
		session:     nil, // peer-routing: data session is nil
		controlSess: deadControl,
		health:      runtime.NewHealthTracker(nil),
	}

	r := &replacementSession{
		conn:        muxconn.New(ln, cipher),
		sess:        newData,
		controlConn: nil,
		controlSess: newControl,
	}

	if ok := s.swapSession(deadControl, r); !ok {
		t.Fatal("swapSession discarded a control-session reinstall (issue #95 regression): " +
			"peer-routing handshake would never be re-armed")
	}
	s.sessMu.RLock()
	gotCtrl := s.controlSess
	gotData := s.session
	s.sessMu.RUnlock()
	if gotCtrl != newControl {
		t.Fatalf("controlSess not swapped: got %p want %p", gotCtrl, newControl)
	}
	if gotData != newData {
		t.Fatalf("session not swapped: got %p want %p", gotData, newData)
	}
}

// TestSwapSessionDiscardsStaleReinstall confirms the guard still rejects a
// reinstall whose dying session matches neither the live data nor control
// session (another reinstall already won the race).
func TestSwapSessionDiscardsStaleReinstall(t *testing.T) {
	cipher, err := cryptopkg.NewCipher("01234567890123456789012345678901")
	if err != nil {
		t.Fatalf("NewCipher() error = %v", err)
	}
	liveData, cleanupL := mkServerSess(t)
	defer cleanupL()
	stale, cleanupS := mkServerSess(t)
	defer cleanupS()
	newData, cleanupND := mkServerSess(t)
	defer cleanupND()

	ln := &peerRoutingStub{}
	s := &Server{
		ln:      ln,
		cipher:  cipher,
		session: liveData,
		health:  runtime.NewHealthTracker(nil),
	}
	r := &replacementSession{sess: newData, conn: muxconn.New(ln, cipher)}
	if ok := s.swapSession(stale, r); ok {
		t.Fatal("swapSession accepted a stale reinstall that matched no live session")
	}
	s.sessMu.RLock()
	got := s.session
	s.sessMu.RUnlock()
	if got != liveData {
		t.Fatalf("live session was clobbered by a stale reinstall: got %p want %p", got, liveData)
	}
}

// peerRoutingStub is a transport stub that satisfies PeerTransport so the
// server treats it as peer-routing capable.
type peerRoutingStub struct {
	closed bool
}

func (p *peerRoutingStub) Connect(context.Context) error   { return nil }
func (p *peerRoutingStub) Send([]byte) error               { return nil }
func (p *peerRoutingStub) Close() error                    { p.closed = true; return nil }
func (p *peerRoutingStub) SetReconnectCallback(func())     {}
func (p *peerRoutingStub) SetShouldReconnect(func() bool)  {}
func (p *peerRoutingStub) SetEndedCallback(func(string))   {}
func (p *peerRoutingStub) WatchConnection(context.Context) {}
func (p *peerRoutingStub) CanSend() bool                   { return true }
func (p *peerRoutingStub) Features() transport.Features    { return transport.Features{} }
func (p *peerRoutingStub) Reconnect(string)                {}
func (p *peerRoutingStub) SendTo(string, []byte) error     { return nil }
func (p *peerRoutingStub) SupportsPeerRouting() bool       { return true }
