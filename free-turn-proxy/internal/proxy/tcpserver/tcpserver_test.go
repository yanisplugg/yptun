package tcpserver

import (
	"bytes"
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcpmux"
	"github.com/xtaci/smux"
)

// echoBackend отвечает тем же, что получил, - роль локального Xray на сервере.
func echoBackend(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0") //nolint:noctx // тестовый сокет
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer func() { _ = conn.Close() }()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return ln.Addr().String()
}

func TestHandleForwardsStreamToBackend(t *testing.T) {
	t.Parallel()

	backendAddr := echoBackend(t)
	clientConn, serverConn := netconn.DatagramPipe(2048, 1024)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		Handle(ctx, logx.Nop(), serverConn, backendAddr, kcpmux.DefaultProfile())
	}()

	kcpSess, err := kcpmux.Dial(clientConn, kcpmux.DefaultProfile())
	if err != nil {
		t.Fatal(err)
	}
	smuxSess, err := smux.Client(kcpSess, kcpmux.SmuxConfig())
	if err != nil {
		t.Fatal(err)
	}

	for i := range 2 {
		stream, serr := smuxSess.OpenStream()
		if serr != nil {
			t.Fatalf("stream %d: %v", i, serr)
		}
		payload := bytes.Repeat([]byte{byte('a' + i)}, 64*1024)
		go func() { _, _ = stream.Write(payload) }()

		got := make([]byte, len(payload))
		if derr := stream.SetReadDeadline(time.Now().Add(30 * time.Second)); derr != nil {
			t.Fatal(derr)
		}
		if _, rerr := io.ReadFull(stream, got); rerr != nil {
			t.Fatalf("stream %d read: %v", i, rerr)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("stream %d: echo mismatch", i)
		}
		_ = stream.Close()
	}

	_ = smuxSess.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatal("Handle did not return after cancel")
	}
}
