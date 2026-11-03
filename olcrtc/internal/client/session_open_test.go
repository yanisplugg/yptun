// ai-generated: the whole file (the session-open hook against a real client).
package client

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/server"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/datachannel"
)

// The hook fires once, with the id the server assigned, before the SOCKS
// listener is reported ready: a host acting on it can already send through
// the session it names.
func TestOnSessionOpenReportsTheEstablishedSession(t *testing.T) {
	transport.Register("datachannel", datachannel.New)
	room := &listenerTestRoom{sessions: make(map[*listenerTestSession]struct{})}
	providerName := "listener-test-" + t.Name()
	enginebuiltin.Register(providerName, func(_ context.Context, cfg enginebuiltin.Config) (engine.Session, error) {
		return room.newSession(cfg.OnData), nil
	})

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	serverErr := make(chan error, 1)
	go func() {
		serverErr <- server.Run(ctx, server.Config{
			Transport: "datachannel", Provider: providerName, RoomURL: "room", KeyHex: listenerTestKey,
		})
	}()
	room.waitConnected(t, 1)

	var mu sync.Mutex
	var opened []string
	address := make(chan string, 1)
	clientErr := make(chan error, 1)
	go func() {
		clientErr <- RunWithAddress(ctx, Config{
			Transport: "datachannel", Provider: providerName, RoomURL: "room", KeyHex: listenerTestKey,
			LocalAddr: "127.0.0.1:0", DeviceID: "session-open-client",
			OnSessionOpen: func(sessionID string) {
				mu.Lock()
				opened = append(opened, sessionID)
				mu.Unlock()
			},
		}, func(actualAddr string) { address <- actualAddr })
	}()
	waitListenerAddress(t, address, clientErr, serverErr)

	mu.Lock()
	got := append([]string(nil), opened...)
	mu.Unlock()
	if len(got) != 1 || got[0] == "" {
		t.Fatalf("session-open calls = %q, want exactly one carrying the session id", got)
	}

	cancel()
	waitListenerRun(t, "client", clientErr)
	waitListenerRun(t, "server", serverErr)
}
