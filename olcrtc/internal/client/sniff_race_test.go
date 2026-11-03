package client

import (
	"io"
	"net"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// The sniff window is for clients that name their destination in their first
// bytes. A protocol where the server speaks first names nothing, and used to
// pay the whole window before anything was dialed - under hev the SOCKS
// target is an address, so that was every SSH, SMTP, IMAP or database
// connection (#35). The tunnel is now prepared while the window runs.
func TestTheTunnelIsOpenedWhileTheClientIsStillSilent(t *testing.T) {
	old := sniffTimeout
	sniffTimeout = 2 * time.Second
	t.Cleanup(func() { sniffTimeout = old })

	errs := make(chan error, 4)
	c, srv := newStreamTestClient(t, mustRules(t, "domain:sni.test\n"), 0, echoAfterHead(t, nil, errs))
	conn := socksConnect(t, c, "10.1.2.3", 22)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}

	start := time.Now()
	select {
	case <-srv.requests:
	case <-time.After(sniffTimeout / 2):
		t.Fatal("the exit was asked to dial only after the sniff window")
	}
	if waited := time.Since(start); waited > 500*time.Millisecond {
		t.Fatalf("the exit waited %s for a client that said nothing", waited)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

// The other side of the race: the client does name a destination the rules
// cover, so the connection goes direct and the tunnel the race opened is
// closed here rather than left to the session.
func TestASniffedDirectNameClosesTheTunnelTheRaceOpened(t *testing.T) {
	hello := clientHello(t, "a.sni.test")
	port, errs := echoServer(t, hello)

	closed := make(chan struct{}, 1)
	c, srv := newStreamTestClient(t, mustRules(t, "domain:sni.test\n"), 0, func(stream *smux.Stream) {
		_, _ = io.Copy(io.Discard, stream)
		select {
		case closed <- struct{}{}:
		default:
		}
	})
	// The name resolves to the echo server; the address the client gave is
	// one nothing listens on, so a dial by address would fail the test.
	c.dialer = protect.NewDialer(staticLookup{"a.sni.test": {net.IPv4(127, 0, 0, 1)}})

	conn := socksConnect(t, c, "127.0.0.2", port)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	if _, err := conn.Write(hello); err != nil {
		t.Fatal(err)
	}
	expectEcho(t, conn)

	select {
	case <-srv.requests:
	case <-time.After(2 * time.Second):
		t.Fatal("the race never opened a tunnel to drop")
	}
	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatal("the tunnel the race opened was left open")
	}
	noErrors(t, errs)
}

// A silent client with no tunnel to take is dropped when the wait runs out,
// as it was before the race: the SOCKS reply went out before the outcome was
// known, so the only thing left to say is nothing.
func TestASilentClientWithNoTunnelIsDropped(t *testing.T) {
	old := sniffTimeout
	sniffTimeout = 50 * time.Millisecond
	t.Cleanup(func() { sniffTimeout = old })

	c := newDirectTestClient(t, mustRules(t, "domain:sni.test\n"), staticLookup{})
	c.sessionReadyTimeout = 100 * time.Millisecond
	conn := socksConnect(t, c, "10.1.2.3", 22)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}

	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Read(make([]byte, 1)); err == nil {
		t.Fatal("the connection outlived the tunnel it was waiting for")
	}
}
