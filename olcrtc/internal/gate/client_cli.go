package gate

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// ai-generated: the whole file (the cli client flavour and what both
// flavours share).

var (
	// ErrClientNotReady is a cli client whose SOCKS port did not listen
	// within the start budget; the mobile flavour reports that as
	// mobile.ErrReadyTimeout.
	ErrClientNotReady = errors.New("not ready")
	// ErrClientEnded is a cli client run that returned without an error
	// before its SOCKS port listened; mobile.ErrStoppedBeforeReady in the
	// mobile flavour.
	ErrClientEnded = errors.New("ended before it was ready")
)

const (
	// startBudget is how long a flavour may take to report a listening SOCKS
	// port: the relay join, the handshake and its resends.
	startBudget = 60 * time.Second
	// stopBudget is how long a stopped client may take to unwind. One that
	// takes longer is left to finish on its own, the way mobile.Runtime.Stop
	// detaches it, so a wedged teardown cannot hang the run into its go test
	// timeout, which would lose the report.
	stopBudget = 10 * time.Second
	// appVP8FPS and appVP8Batch are the app's vp8channel numbers, for an
	// endpoint that names none.
	appVP8FPS   = 60
	appVP8Batch = 64
	// cliFlavour and mobileFlavour are the flavours' elements of a cell id.
	// Every build plans with both; only the lean build runs mobile.
	cliFlavour    = "cli"
	mobileFlavour = "mobile"
)

// cliRun runs the public client until ctx ends and reports the SOCKS address
// once it listens, as client.(*Client).RunWithAddress does.
type cliRun func(ctx context.Context, cfg client.Config, onReady func(addr string)) error

// cliClient runs the public client in the test process, where the sampler
// measures it.
type cliClient struct {
	run   cliRun
	start time.Duration // startBudget
	stop  time.Duration // stopBudget
}

// CLIClient runs what cmd/olcrtc runs in mode cnc: the public client.
func CLIClient() Client {
	return cliClient{run: runPublicClient, start: startBudget, stop: stopBudget}
}

// Name is the flavour's element of a cell id.
func (cliClient) Name() string { return cliFlavour }

// cliConfig maps an endpoint to the public client's config the way the CLI's
// YAML would, with an ephemeral SOCKS port. The transport options are the
// ones the local server is given: the endpoint's vp8channel numbers (the
// app's when it names none), fixed seichannel ones, and the transport's own
// defaults for the rest, which are also the CLI's.
func cliConfig(ep Endpoint) client.Config {
	cfg := client.Config{
		Transport: ep.Transport, Provider: ep.Provider, RoomURL: ep.Room, ChannelID: ep.Channel,
		KeyHex: ep.Key, LocalAddr: "127.0.0.1:0", DNSServer: ep.DNS, DeviceID: "gate-cli",
		DTLSProfile: engine.DTLSProfile(ep.DTLSProfile), ProviderToken: ep.ClientToken,
	}
	switch ep.Transport {
	case transportVP8:
		cfg.TransportOptions = client.VP8Options{
			FPS: cmp.Or(ep.VP8FPS, appVP8FPS), BatchSize: cmp.Or(ep.VP8Batch, appVP8Batch),
		}
	case transportSEI:
		cfg.TransportOptions = client.SEIOptions{FPS: 60, BatchSize: 64, FragmentSize: 900, AckTimeoutMS: 2000}
	}
	return cfg
}

// Start runs the client and returns once its SOCKS port listens. The client
// then runs until Stop or until ctx ends; a start that fails leaves nothing
// running.
func (c cliClient) Start(ctx context.Context, ep Endpoint) (*Tunnel, error) {
	runCtx, cancel := context.WithCancel(ctx)
	cfg := cliConfig(ep)
	ready := make(chan string, 1)
	done := make(chan error, 1)
	go func() { done <- c.run(runCtx, cfg, func(addr string) { ready <- addr }) }()
	timer := time.NewTimer(c.start)
	defer timer.Stop()
	select {
	case addr := <-ready:
		return &Tunnel{SocksAddr: addr, Stop: sync.OnceFunc(func() { c.stopRun(cancel, done) })}, nil
	case err := <-done:
		cancel()
		if err == nil {
			err = ErrClientEnded
		}
		return nil, fmt.Errorf("cli client: %w", err)
	case <-timer.C:
		c.stopRun(cancel, done)
		return nil, fmt.Errorf("cli client: %w within %s", ErrClientNotReady, c.start)
	}
}

// stopRun cancels a run and waits for it to return, at most the stop budget.
func (c cliClient) stopRun(cancel context.CancelFunc, done <-chan error) {
	cancel()
	timer := time.NewTimer(c.stop)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}

// runPublicClient is the cli flavour's run: the public client itself.
func runPublicClient(ctx context.Context, cfg client.Config, onReady func(addr string)) error {
	if err := client.New(cfg).RunWithAddress(ctx, onReady); err != nil {
		return fmt.Errorf("run public client: %w", err)
	}
	return nil
}
