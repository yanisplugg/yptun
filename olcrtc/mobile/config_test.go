package mobile

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

type testProtector struct{}

func (testProtector) Protect(int) bool { return true }

func TestSettersBuildPublicClientConfig(t *testing.T) {
	configs := make(chan client.Config, 1)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		configs <- cfg
		onReady(cfg.LocalAddr)
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.SetTransport(transportVideo); err != nil {
		t.Fatalf("SetTransport() error = %v", err)
	}
	runtime.SetChannel("channel")
	runtime.SetProviderToken(" token ")
	runtime.SetDeviceID("device")
	runtime.SetDeviceIDPath("/tmp/device")
	runtime.SetEngine("livekit", "wss://example.test", "engine-token")
	if err := runtime.SetSocksPort(19080); err != nil {
		t.Fatalf("SetSocksPort() error = %v", err)
	}
	if err := runtime.SetSocksCredentials("user", "pass"); err != nil {
		t.Fatalf("SetSocksCredentials() error = %v", err)
	}
	if err := runtime.SetLivenessOptions(2500, 750, 3); err != nil {
		t.Fatalf("SetLivenessOptions() error = %v", err)
	}
	if err := runtime.SetTrafficOptions(4096, 2, 5); err != nil {
		t.Fatalf("SetTrafficOptions() error = %v", err)
	}
	if err := runtime.SetVideoOptions(1080, 1080, 30, 512, "high", "tile", 4, 20); err != nil {
		t.Fatalf("SetVideoOptions() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	cfg := <-configs
	video, ok := cfg.TransportOptions.(client.VideoOptions)
	if !ok {
		t.Fatalf("TransportOptions type = %T, want client.VideoOptions", cfg.TransportOptions)
	}
	if cfg.Provider != "jitsi" || cfg.Transport != transportVideo || cfg.RoomURL != testRoom ||
		cfg.ChannelID != "channel" || cfg.ProviderToken != "token" || cfg.DeviceID != "device" ||
		cfg.DeviceIDPath != "/tmp/device" || cfg.LocalAddr != "127.0.0.1:19080" ||
		cfg.SOCKSUser != "user" || cfg.SOCKSPass != "pass" ||
		cfg.Liveness.Interval != 2500*time.Millisecond || cfg.Liveness.Timeout != 750*time.Millisecond ||
		cfg.Liveness.Failures != 3 || cfg.Traffic.MaxPayloadSize != 4096 ||
		cfg.Traffic.MinDelay != 2*time.Millisecond || cfg.Traffic.MaxDelay != 5*time.Millisecond ||
		video.Codec != "tile" || video.TileRS != 20 {
		t.Fatalf("client config = %#v, video = %#v", cfg, video)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

func TestTransportSettersAndValidation(t *testing.T) {
	runtime := New()
	for _, transport := range []string{transportData, transportVP8, transportSEI, transportVideo} {
		if err := runtime.SetTransport(transport); err != nil {
			t.Errorf("SetTransport(%q) error = %v", transport, err)
		}
	}
	if err := runtime.SetTransport("unknown"); !errors.Is(err, ErrUnsupportedTransport) {
		t.Fatalf("SetTransport(unknown) error = %v", err)
	}
	if err := runtime.SetProvider("unknown"); !errors.Is(err, ErrUnsupportedProvider) {
		t.Fatalf("SetProvider(unknown) error = %v", err)
	}
	if err := runtime.SetVP8Options(60, 8); err != nil {
		t.Fatalf("SetVP8Options() error = %v", err)
	}
	if err := runtime.SetSEIOptions(30, 64, 900, 2000); err != nil {
		t.Fatalf("SetSEIOptions() error = %v", err)
	}
	if err := runtime.SetVideoOptions(1920, 1080, 30, 0, "low", "qrcode", 4, 0); err != nil {
		t.Fatalf("SetVideoOptions() error = %v", err)
	}
	if err := runtime.SetVideoOptions(1920, 1080, 30, 0, "low", "bad", 4, 0); err == nil {
		t.Fatal("SetVideoOptions() accepted an unknown codec")
	}
	runtime.mu.Lock()
	defaults := runtime.defaults
	runtime.mu.Unlock()
	if defaults.vp8.FPS != 60 || defaults.sei.FragmentSize != 900 || defaults.video.Codec != "qrcode" {
		t.Fatalf("transport defaults = %#v", defaults)
	}
}

func TestStartValidatesRequiredConfiguration(t *testing.T) {
	runtime := newRuntime(blockingReadyRunner)
	if err := runtime.Start(); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("Start() error = %v, want %v", err, ErrInvalidConfig)
	}
	if err := runtime.SetProvider("jitsi"); err != nil {
		t.Fatalf("SetProvider() error = %v", err)
	}
	if err := runtime.SetRoom(testRoom); err != nil {
		t.Fatalf("SetRoom() error = %v", err)
	}
	if err := runtime.SetKey("not-hex"); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("SetKey() error = %v, want %v", err, ErrInvalidConfig)
	}
}

func TestDNSResolverIsRuntimeLocal(t *testing.T) {
	runtime := New()
	defaultResolver := net.DefaultResolver
	if err := runtime.SetDNS("9.9.9.9:53"); err != nil {
		t.Fatalf("SetDNS() error = %v", err)
	}
	if net.DefaultResolver != defaultResolver {
		t.Fatal("SetDNS() changed net.DefaultResolver")
	}
	custom := &net.Resolver{PreferGo: true}
	runtime.SetResolver(custom)
	runtime.mu.Lock()
	got := runtime.defaults.resolver
	runtime.mu.Unlock()
	if got != custom {
		t.Fatal("SetResolver() did not retain the custom resolver")
	}
}

func TestDebugAndProtectorProcessState(t *testing.T) {
	runtime := New()
	runtime.SetDebug(true)
	t.Cleanup(func() {
		logger.SetVerbose(false)
		protect.SetProtector(nil)
	})
	if !logger.IsVerbose() {
		t.Fatal("SetDebug(true) did not enable internal verbose logging")
	}
	runtime.SetProtector(testProtector{})
	if !protect.HasProtector() {
		t.Fatal("SetProtector() did not install process-wide protection")
	}
	runtime.SetProtector(nil)
	if protect.HasProtector() {
		t.Fatal("SetProtector(nil) did not clear process-wide protection")
	}
}

func TestSetDNSTakesAListAndAppliesItToTheRuntimeResolver(t *testing.T) {
	runtime := New()
	if err := runtime.SetDNS("10.0.0.53, 10.0.0.54:53"); err != nil {
		t.Fatalf("SetDNS() error = %v", err)
	}
	if err := runtime.SetDNS(""); err == nil {
		t.Fatal("SetDNS(\"\") accepted an empty list")
	}
	if err := runtime.SetDNS("not a server:xyz"); err == nil {
		t.Fatal("SetDNS accepted a bad entry")
	}
	got := runtime.defaults.dns.Servers()
	if len(got) < 2 || got[0] != "10.0.0.53:53" || got[1] != "10.0.0.54:53" {
		t.Fatalf("Servers() = %v, want the list in order with the default port filled in", got)
	}
}

func TestSetUDPReachesTheClientConfig(t *testing.T) {
	for _, tc := range []struct {
		name         string
		set          func(*Runtime)
		wantDisabled bool
	}{
		{name: "on by default", set: func(*Runtime) {}, wantDisabled: false},
		{name: "SetUDP(false) turns it off", set: func(r *Runtime) { r.SetUDP(false) }, wantDisabled: true},
		{name: "SetUDP(true) turns it back on", set: func(r *Runtime) { r.SetUDP(false); r.SetUDP(true) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			configs := make(chan client.Config, 1)
			runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
				configs <- cfg
				onReady(cfg.LocalAddr)
				<-ctx.Done()
				return ctx.Err()
			})
			tc.set(runtime)
			if err := runtime.Start(); err != nil {
				t.Fatalf("Start() error = %v", err)
			}
			if err := runtime.WaitReady(100); err != nil {
				t.Fatalf("WaitReady() error = %v", err)
			}
			if cfg := <-configs; cfg.UDPDisabled != tc.wantDisabled {
				t.Fatalf("UDPDisabled = %v, want %v", cfg.UDPDisabled, tc.wantDisabled)
			}
			_ = runtime.Stop(100)
		})
	}
}

func TestSetDirectRulesParsesNow(t *testing.T) {
	configs := make(chan client.Config, 1)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		configs <- cfg
		onReady(cfg.LocalAddr)
		<-ctx.Done()
		return ctx.Err()
	})
	if err := runtime.SetDirectRules("domain:ru\nkeyword:x\n"); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("SetDirectRules(bad) error = %v, want ErrInvalidConfig", err)
	}
	const rules = "domain:ru\n10.0.0.0/8\n"
	if err := runtime.SetDirectRules(rules); err != nil {
		t.Fatalf("SetDirectRules() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	if cfg := <-configs; cfg.DirectRules != rules {
		t.Fatalf("DirectRules = %q, want %q", cfg.DirectRules, rules)
	}
	if err := runtime.Stop(1000); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
	// An empty text clears the rules for the next start.
	if err := runtime.SetDirectRules(""); err != nil {
		t.Fatalf("SetDirectRules(empty) error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("second Start() error = %v", err)
	}
	if cfg := <-configs; cfg.DirectRules != "" {
		t.Fatalf("DirectRules after clearing = %q", cfg.DirectRules)
	}
	_ = runtime.Stop(1000)
}

func TestSetDTLSProfileValidatesBeforeStoring(t *testing.T) {
	rt := New()
	if err := rt.SetDTLSProfile("chrome-linux-138-compat-v1"); err != nil {
		t.Fatalf("known profile rejected: %v", err)
	}
	if err := rt.SetDTLSProfile("off"); err != nil {
		t.Fatalf("off rejected: %v", err)
	}
	if err := rt.SetDTLSProfile(""); err != nil {
		t.Fatalf("empty rejected: %v", err)
	}
	for _, bad := range []string{"random", "auto", "chrome-linux-138.0.7204.94"} {
		if err := rt.SetDTLSProfile(bad); err == nil {
			t.Fatalf("invalid profile %q accepted", bad)
		}
	}
}
