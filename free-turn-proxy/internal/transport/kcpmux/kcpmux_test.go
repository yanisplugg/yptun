package kcpmux

import (
	"bytes"
	"crypto/rand"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/xtaci/smux"
)

// datagramConn подаёт пары PacketPipe как net.Conn с сохранением границ датаграмм;
// dropEvery роняет каждую N-ю отправку, чтобы проверить ARQ.
type datagramConn struct {
	net.PacketConn
	remote    net.Addr
	dropEvery uint64
	sent      atomic.Uint64
}

func (c *datagramConn) Read(b []byte) (int, error) {
	n, _, err := c.ReadFrom(b)
	return n, err
}

func (c *datagramConn) Write(b []byte) (int, error) {
	if c.dropEvery > 0 && c.sent.Add(1)%c.dropEvery == 0 {
		return len(b), nil
	}
	return c.WriteTo(b, c.remote)
}

func (c *datagramConn) RemoteAddr() net.Addr { return c.remote }

func pipePair(dropEvery uint64) (*datagramConn, *datagramConn) {
	a, b := netconn.PacketPipe(2048, 1024)
	return &datagramConn{PacketConn: a, remote: b.LocalAddr(), dropEvery: dropEvery},
		&datagramConn{PacketConn: b, remote: a.LocalAddr()}
}

func TestRoundTrip(t *testing.T) {
	t.Parallel()
	runRoundTrip(t, 0)
}

func TestRoundTripWithLoss(t *testing.T) {
	t.Parallel()
	runRoundTrip(t, 7)
}

func runRoundTrip(t *testing.T, dropEvery uint64) {
	t.Helper()

	clientConn, serverConn := pipePair(dropEvery)
	profile := DefaultProfile()

	accepted := make(chan *smux.Session, 1)
	serverErr := make(chan error, 1)
	go func() {
		kcpSess, err := Accept(serverConn, profile)
		if err != nil {
			serverErr <- err
			return
		}
		smuxSess, err := smux.Server(kcpSess, SmuxConfig())
		if err != nil {
			serverErr <- err
			return
		}
		accepted <- smuxSess
	}()

	kcpClient, err := Dial(clientConn, profile)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = kcpClient.Close() }()

	smuxClient, err := smux.Client(kcpClient, SmuxConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = smuxClient.Close() }()

	stream, err := smuxClient.OpenStream()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = stream.Close() }()

	payload := make([]byte, 256*1024)
	if _, rerr := rand.Read(payload); rerr != nil {
		t.Fatal(rerr)
	}
	writeErr := make(chan error, 1)
	go func() { _, werr := stream.Write(payload); writeErr <- werr }()

	var smuxServer *smux.Session
	select {
	case smuxServer = <-accepted:
	case serr := <-serverErr:
		t.Fatal(serr)
	case <-time.After(30 * time.Second):
		t.Fatal("server accept timeout")
	}
	defer func() { _ = smuxServer.Close() }()

	srvStream, err := smuxServer.AcceptStream()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = srvStream.Close() }()

	got := make([]byte, len(payload))
	if _, err := io.ReadFull(srvStream, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("payload mismatch client->server")
	}
	if err := <-writeErr; err != nil {
		t.Fatal(err)
	}

	// обратное направление: сервер отвечает по тому же потоку
	back := payload[:4096]
	go func() { _, _ = srvStream.Write(back) }()
	echo := make([]byte, len(back))
	if err := stream.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(stream, echo); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(echo, back) {
		t.Fatal("payload mismatch server->client")
	}
}
