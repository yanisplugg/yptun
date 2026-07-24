package e2e

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
	"github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/server"
)

// TestRealMultiClientConcurrent is the reproduction harness for issue #95's
// real-world symptom: multiple clients sharing one server in a single SFU
// room. The single-client matrix (TestRealProviderTransportMatrix) passes, so
// any failure here isolates the multi-peer control-plane defect rather than a
// generic connectivity problem.
//
// Topology: one server + two clients in the same Telemost room, each with its
// own device id and SOCKS listener. We verify:
//
//  1. Both clients reach READY (handshake completes for BOTH, not just the
//     first - the log showed client 2/3 hanging in "read welcome: timeout").
//  2. Both can echo concurrently right after connect.
//  3. After holding the link past one liveness interval (the log showed
//     teardown ~10-15s in via "control missed pong"), both clients STILL
//     echo - i.e. the session was not torn down underneath a healthy link.
//
// Gated behind -olcrtc.real-e2e so it never runs on a normal push.
func TestRealMultiClientConcurrent(t *testing.T) {
	if !*realE2E {
		t.Skip("real provider e2e disabled; pass -olcrtc.real-e2e with provider room flags")
	}

	const (
		carrierName   = "telemost"
		transportName = "vp8channel"
		holdDuration  = 25 * time.Second
	)

	echoAddr := startEchoServer(t)

	roomCtx, cancelRoom := context.WithTimeout(context.Background(), *realE2ETimeout)
	roomURL := requireRealRoom(roomCtx, t, carrierName)
	cancelRoom()

	ctx, cancel := context.WithTimeout(context.Background(), holdDuration+3*(*realE2ETimeout))
	defer cancel()

	session.RegisterDefaults()

	// Shared channel id: server and both clients must bind to the same room
	// instance so the server's peer-routing demux sees both client epochs.
	channelID := fmt.Sprintf("e2e-multi-%d", time.Now().UnixNano())
	liveness := control.Config{Interval: 10 * time.Second, Timeout: 60 * time.Second, Failures: 10}

	serverErr := make(chan error, 1)
	go func() {
		serverErr <- server.Run(ctx, server.Config{
			Transport:        transportName,
			Carrier:          carrierName,
			RoomURL:          roomURL,
			ChannelID:        channelID,
			KeyHex:           testKeyHex,
			DNSServer:        localDNSServer,
			TransportOptions: e2eTransportOptions(transportName),
			Liveness:         liveness,
		})
	}()

	select {
	case err := <-serverErr:
		t.Fatalf("server exited before clients started: %v", err)
	case <-time.After(2 * time.Second):
	}

	clientA := startNamedClient(ctx, t, clientSpec{
		carrier: carrierName, transport: transportName, room: roomURL,
		channelID: channelID, deviceID: "client-A", liveness: liveness,
	})
	clientB := startNamedClient(ctx, t, clientSpec{
		carrier: carrierName, transport: transportName, room: roomURL,
		channelID: channelID, deviceID: "client-B", liveness: liveness,
	})

	// 1. Both clients must reach READY.
	waitForReadyWithin(t, clientA.ready, *realE2ETimeout)
	t.Log("client-A ready")
	waitForReadyWithin(t, clientB.ready, *realE2ETimeout)
	t.Log("client-B ready")

	// 2. Both echo right after connect.
	mustEcho(t, clientA.socksAddr, echoAddr, "A-initial")
	mustEcho(t, clientB.socksAddr, echoAddr, "B-initial")
	t.Log("both clients echoed immediately after connect")

	// 3. Hold past the liveness window, then both must still echo.
	select {
	case err := <-serverErr:
		t.Fatalf("server died during hold: %v", err)
	case err := <-clientA.errc:
		t.Fatalf("client-A died during hold: %v", err)
	case err := <-clientB.errc:
		t.Fatalf("client-B died during hold: %v", err)
	case <-time.After(holdDuration):
	}

	mustEcho(t, clientA.socksAddr, echoAddr, "A-after-hold")
	mustEcho(t, clientB.socksAddr, echoAddr, "B-after-hold")
	t.Logf("both clients still alive and echoing after %s hold", holdDuration)

	cancel()
}

type clientSpec struct {
	carrier   string
	transport string
	room      string
	channelID string
	deviceID  string
	liveness  control.Config
}

type namedClient struct {
	socksAddr string
	ready     chan struct{}
	errc      chan error
}

func startNamedClient(ctx context.Context, t *testing.T, spec clientSpec) namedClient {
	t.Helper()
	socksAddr := freeLocalAddr(ctx, t)
	ready := make(chan struct{})
	errc := make(chan error, 1)
	go func() {
		errc <- client.RunWithReady(ctx, client.Config{
			Transport:        spec.transport,
			Carrier:          spec.carrier,
			RoomURL:          spec.room,
			ChannelID:        spec.channelID,
			KeyHex:           testKeyHex,
			DeviceID:         spec.deviceID,
			LocalAddr:        socksAddr,
			DNSServer:        localDNSServer,
			TransportOptions: e2eTransportOptions(spec.transport),
			Liveness:         spec.liveness,
		}, func() { close(ready) })
	}()
	return namedClient{socksAddr: socksAddr, ready: ready, errc: errc}
}

func mustEcho(t *testing.T, socksAddr, echoAddr, tag string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), *realE2ETimeout)
	defer cancel()

	conn, err := connectViaSOCKSWithin(ctx, socksAddr, echoAddr, *realE2ETimeout)
	if err != nil {
		t.Fatalf("[%s] socks connect: %v", tag, err)
	}
	defer func() { _ = conn.Close() }()

	payload := []byte("olcrtc-multi-" + tag + "\n")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("[%s] write: %v", tag, err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(*realE2ETimeout)); err != nil {
		t.Fatalf("[%s] set deadline: %v", tag, err)
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		t.Fatalf("[%s] read echo: %v", tag, err)
	}
	if !bytes.Equal(line, payload) {
		t.Fatalf("[%s] echo mismatch: got %q want %q", tag, line, payload)
	}
}
