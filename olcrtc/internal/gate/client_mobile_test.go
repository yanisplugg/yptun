//go:build olcrtc_lean

package gate

import (
	"context"
	"errors"
	"net"
	"strconv"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

// ai-generated: whole file, unit cover for the mobile flavour: an endpoint
// the runtime refuses fails the start before anything runs, and the port it
// is handed is one nobody holds.

func TestMobileClientRefusesABadEndpointBeforeStarting(t *testing.T) {
	cases := []struct {
		name string
		edit func(ep *Endpoint)
		want error
	}{
		{"key", func(ep *Endpoint) { ep.Key = "abcd" }, mobile.ErrInvalidConfig},
		{"provider", func(ep *Endpoint) { ep.Provider = "fake-provider" }, mobile.ErrUnsupportedProvider},
		{"transport", func(ep *Endpoint) { ep.Transport = "fake-transport" }, mobile.ErrUnsupportedTransport},
		{"dns", func(ep *Endpoint) { ep.DNS = "" }, mobile.ErrInvalidConfig},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ep := fakeEndpoint("jitsi", "datachannel")
			tc.edit(&ep)
			var err error
			within(t, 5*time.Second, "Start", func() { _, err = MobileClient().Start(context.Background(), ep) })
			if !errors.Is(err, tc.want) {
				t.Fatalf("Start = %v, want %v", err, tc.want)
			}
		})
	}
}

func TestFreePortIsOneNobodyHolds(t *testing.T) {
	port, err := freePort(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	var lc net.ListenConfig
	ln, err := lc.Listen(context.Background(), "tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		t.Fatalf("port %d from freePort is taken: %v", port, err)
	}
	_ = ln.Close()
}
