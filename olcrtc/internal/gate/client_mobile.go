//go:build olcrtc_lean

package gate

import (
	"cmp"
	"context"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

// ai-generated: the whole file (the mobile client flavour).

const (
	// mobileSocksHost is where the runtime's SOCKS listener binds: loopback,
	// as both apps bind it.
	mobileSocksHost = "127.0.0.1"
	// startBudgetMillis and stopBudgetMillis are the budgets in the unit
	// mobile.Runtime takes.
	startBudgetMillis = int(startBudget / time.Millisecond)
	stopBudgetMillis  = int(stopBudget / time.Millisecond)
)

type mobileClient struct{}

// MobileClient runs mobile.Runtime the way OlcboxVpnService and the iOS
// packet tunnel do. Only the lean build has it, because only the lean build
// is what the phones run.
func MobileClient() Client { return mobileClient{} }

// Name is the flavour's element of a cell id.
func (mobileClient) Name() string { return mobileFlavour }

// Start configures a fresh runtime from the endpoint, starts it and returns
// once it is ready. The runtime then runs until Stop or until ctx ends; a
// start that fails leaves nothing running.
func (mobileClient) Start(ctx context.Context, ep Endpoint) (*Tunnel, error) {
	rt := mobile.New()
	if err := configureMobile(rt, ep); err != nil {
		return nil, err
	}
	port, err := freePort(ctx)
	if err != nil {
		return nil, err
	}
	if err := rt.SetSocksPort(port); err != nil {
		return nil, fmt.Errorf("mobile socks port: %w", err)
	}
	if err := rt.Start(); err != nil { //nolint:contextcheck // the runtime owns its context; ctx reaches it via AfterFunc
		return nil, fmt.Errorf("mobile start: %w", err)
	}
	stop := sync.OnceFunc(func() { _ = rt.Stop(stopBudgetMillis) })
	unwatch := context.AfterFunc(ctx, stop)
	if err := rt.WaitReady(startBudgetMillis); err != nil {
		unwatch()
		stop()
		if ctx.Err() != nil {
			err = fmt.Errorf("%w: %w", ctx.Err(), err)
		}
		return nil, fmt.Errorf("mobile ready: %w", err)
	}
	return &Tunnel{
		SocksAddr: net.JoinHostPort(mobileSocksHost, strconv.Itoa(port)),
		Stop: func() {
			unwatch()
			stop()
		},
	}, nil
}

// configureMobile hands the endpoint to the runtime with the app's numbers:
// vp8channel at the endpoint's rate and batch (the app's when it names
// none), every other transport at the runtime's defaults, which the app
// leaves alone too. The pair's channel and the client's provider token (the
// max pair's second identity) are the settings the app does not make; the
// SOCKS port is set just before the start.
func configureMobile(rt *mobile.Runtime, ep Endpoint) error {
	steps := []struct {
		name string
		err  error
	}{
		{"provider", rt.SetProvider(ep.Provider)},
		{"transport", rt.SetTransport(ep.Transport)},
		{"room", rt.SetRoom(ep.Room)},
		{"key", rt.SetKey(ep.Key)},
		{"dns", rt.SetDNS(ep.DNS)},
		{"socks host", rt.SetSocksListenHost(mobileSocksHost)},
		{"vp8", rt.SetVP8Options(cmp.Or(ep.VP8FPS, appVP8FPS), cmp.Or(ep.VP8Batch, appVP8Batch))},
		{"dtls profile", rt.SetDTLSProfile(ep.DTLSProfile)},
	}
	for _, s := range steps {
		if s.err != nil {
			return fmt.Errorf("mobile %s: %w", s.name, s.err)
		}
	}
	rt.SetChannel(ep.Channel)
	rt.SetProviderToken(ep.ClientToken)
	rt.SetDeviceID("gate-mobile")
	return nil
}

// freePort asks the kernel for a free loopback port and releases it:
// mobile.Runtime takes a port number and refuses 0.
func freePort(ctx context.Context) (int, error) {
	var lc net.ListenConfig
	ln, err := lc.Listen(ctx, "tcp4", net.JoinHostPort(mobileSocksHost, "0"))
	if err != nil {
		return 0, fmt.Errorf("free port: %w", err)
	}
	addr, err := netip.ParseAddrPort(ln.Addr().String())
	_ = ln.Close()
	if err != nil {
		return 0, fmt.Errorf("free port: %w", err)
	}
	return int(addr.Port()), nil
}
