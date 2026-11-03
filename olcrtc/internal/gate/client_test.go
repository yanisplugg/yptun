package gate

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	runtimecfg "github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// ai-generated: whole file, unit cover for the two client flavours: the cli
// flavour's config, its start and stop against a fake run, and which flavour
// a build carries.

// fakeEndpoint is a made-up endpoint: no room, channel or key here is real.
func fakeEndpoint(provider, transport string) Endpoint {
	return Endpoint{
		Provider: provider, Transport: transport,
		Room: "https://meet.example.invalid/fake-gate-room", Channel: "gate-fakechannel",
		Key: strings.Repeat("ab", 32), DNS: "192.0.2.53:53", VP8FPS: 60, VP8Batch: 64,
	}
}

// fakeCLI is the cli flavour with run in place of the public client and
// budgets short enough that a run which never listens costs milliseconds.
func fakeCLI(run cliRun) cliClient {
	return cliClient{run: run, start: 200 * time.Millisecond, stop: 200 * time.Millisecond}
}

// within fails the test when fn has not returned after d, instead of letting
// a hang run into the go test timeout.
func within(t *testing.T, d time.Duration, what string, fn func()) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		fn()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(d):
		t.Fatalf("%s did not return within %s", what, d)
	}
}

func TestCLIConfigMirrorsTheEndpoint(t *testing.T) {
	ep := fakeEndpoint("telemost", "vp8channel")
	ep.VP8FPS, ep.VP8Batch = 30, 16
	cfg := cliConfig(ep)
	if cfg.Provider != ep.Provider || cfg.Transport != ep.Transport || cfg.RoomURL != ep.Room ||
		cfg.ChannelID != ep.Channel || cfg.KeyHex != ep.Key {
		t.Fatalf("cfg does not carry the endpoint: %+v", cfg)
	}
	if cfg.LocalAddr != "127.0.0.1:0" || cfg.DNSServer != ep.DNS || cfg.DeviceID != "gate-cli" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if opts, ok := cfg.TransportOptions.(client.VP8Options); !ok || opts != (client.VP8Options{FPS: 30, BatchSize: 16}) {
		t.Fatalf("vp8 options = %#v, want the endpoint's", cfg.TransportOptions)
	}
	if cfg.DTLSProfile != "" {
		t.Fatalf("an endpoint with no profile asks for %q", cfg.DTLSProfile)
	}
	ep.DTLSProfile = "chrome-linux-138-compat-v1"
	if got := string(cliConfig(ep).DTLSProfile); got != ep.DTLSProfile {
		t.Fatalf("dtls profile = %q, want the endpoint's %q", got, ep.DTLSProfile)
	}
	unset := cliConfig(Endpoint{Provider: ep.Provider, Transport: ep.Transport})
	if opts, ok := unset.TransportOptions.(client.VP8Options); !ok || opts != (client.VP8Options{FPS: 60, BatchSize: 64}) {
		t.Fatalf("vp8 options of an endpoint without them = %#v, want the app's 60/64", unset.TransportOptions)
	}
	sei := cliConfig(fakeEndpoint("jitsi", "seichannel")).TransportOptions
	if opts, ok := sei.(client.SEIOptions); !ok ||
		opts != (client.SEIOptions{FPS: 60, BatchSize: 64, FragmentSize: 900, AckTimeoutMS: 2000}) {
		t.Fatalf("sei options = %#v, want the server config's", sei)
	}
	for _, transport := range []string{"datachannel", "videochannel"} {
		if opts := cliConfig(fakeEndpoint("jitsi", transport)).TransportOptions; opts != nil {
			t.Fatalf("%s carries options %#v, want the transport's defaults", transport, opts)
		}
	}
}

func TestCLIClientRefusesABadKeyBeforeAnyNetwork(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ep := fakeEndpoint("jitsi", "datachannel")
	ep.Key = "abcd"
	_, err := CLIClient().Start(ctx, ep)
	if !errors.Is(err, runtimecfg.ErrKeySize) {
		t.Fatalf("Start with a two-byte key = %v, want the engine's key size refusal", err)
	}
}

func TestCLIClientStartReturnsTheListenerAndStopEndsTheRun(t *testing.T) {
	var got client.Config
	var ended atomic.Bool
	c := fakeCLI(func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		got = cfg
		onReady("127.0.0.1:1080")
		<-ctx.Done()
		ended.Store(true)
		return nil
	})
	c.start, c.stop = 5*time.Second, 5*time.Second
	ep := fakeEndpoint("jitsi", "datachannel")
	tun, err := c.Start(context.Background(), ep)
	if err != nil {
		t.Fatal(err)
	}
	if tun.SocksAddr != "127.0.0.1:1080" || got.ChannelID != ep.Channel || got.RoomURL != ep.Room {
		t.Fatalf("tunnel on %q from cfg %+v", tun.SocksAddr, got)
	}
	within(t, time.Second, "Stop", tun.Stop)
	if !ended.Load() {
		t.Fatal("Stop returned before the run ended")
	}
	within(t, time.Second, "a second Stop", tun.Stop)
}

func TestCLIClientStartLeavesNoRunBehindWhenItFails(t *testing.T) {
	errRelay := errors.New("relay refused the join")
	untilCancelled := func(ctx context.Context, _ client.Config, _ func(string)) error {
		<-ctx.Done()
		return ctx.Err()
	}
	cases := []struct {
		name      string
		run       cliRun
		cancelled bool // the caller has given up before the start
		want      error
	}{
		{"the run fails", func(context.Context, client.Config, func(string)) error { return errRelay }, false, errRelay},
		{"the run ends clean", func(context.Context, client.Config, func(string)) error { return nil }, false, ErrClientEnded},
		{"the port never listens", untilCancelled, false, ErrClientNotReady},
		{"the caller gave up", untilCancelled, true, context.Canceled},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var ended atomic.Bool
			c := fakeCLI(func(ctx context.Context, cfg client.Config, onReady func(string)) error {
				defer ended.Store(true)
				return tc.run(ctx, cfg, onReady)
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tc.cancelled {
				cancel()
			}
			var err error
			within(t, 5*time.Second, "Start", func() { _, err = c.Start(ctx, fakeEndpoint("jitsi", "datachannel")) })
			if !errors.Is(err, tc.want) {
				t.Fatalf("Start = %v, want %v", err, tc.want)
			}
			if !ended.Load() {
				t.Fatal("Start failed with the run still going")
			}
		})
	}
}

func TestCLIClientRunEndsWithItsContext(t *testing.T) {
	ended := make(chan struct{})
	c := fakeCLI(func(ctx context.Context, _ client.Config, onReady func(string)) error {
		onReady("127.0.0.1:1080")
		<-ctx.Done()
		close(ended)
		return nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	tun, err := c.Start(ctx, fakeEndpoint("jitsi", "datachannel"))
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case <-ended:
	case <-time.After(5 * time.Second):
		t.Fatal("the run outlived the context it was started with")
	}
	within(t, time.Second, "Stop after the run ended", tun.Stop)
}

func TestCLIClientStopGivesUpOnAWedgedTeardown(t *testing.T) {
	release := make(chan struct{})
	t.Cleanup(func() { close(release) })
	c := fakeCLI(func(ctx context.Context, _ client.Config, onReady func(string)) error {
		onReady("127.0.0.1:1080")
		<-ctx.Done()
		<-release // a teardown that does not finish on its own
		return nil
	})
	tun, err := c.Start(context.Background(), fakeEndpoint("jitsi", "datachannel"))
	if err != nil {
		t.Fatal(err)
	}
	within(t, 5*time.Second, "Stop of a wedged run", tun.Stop)
}

func TestMobileClientPresenceFollowsTheTag(t *testing.T) {
	if (MobileClient() != nil) != leanBuild {
		t.Fatalf("MobileClient() present=%v, lean build=%v", MobileClient() != nil, leanBuild)
	}
}

func TestFlavoursAreNamedForTheirCells(t *testing.T) {
	if name := CLIClient().Name(); name != "cli" {
		t.Fatalf("CLIClient().Name() = %q", name)
	}
	if m := MobileClient(); m != nil && m.Name() != "mobile" {
		t.Fatalf("MobileClient().Name() = %q", m.Name())
	}
}
