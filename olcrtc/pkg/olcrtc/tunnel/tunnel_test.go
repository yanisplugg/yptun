package tunnel

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/server"
	"github.com/openlibrecommunity/olcrtc/internal/transport/vp8channel"
)

var errRunner = errors.New("runner")

func TestConfigMapping(t *testing.T) {
	resolver := &net.Resolver{PreferGo: true}
	options := VP8Options{FPS: 25, BatchSize: 8}
	cfg := Config{
		Transport: "vp8channel", Provider: "jitsi", RoomURL: "room", ChannelID: "channel",
		Engine: "livekit", URL: "wss://example", Token: "engine-token",
		ProviderToken: "provider-token", KeyHex: "key", DNSServer: "dns", Resolver: resolver,
		SOCKSProxyAddr: "proxy", SOCKSProxyPort: 1080,
		SOCKSProxyUser: "user", SOCKSProxyPass: "pass",
		TransportOptions: options,
		Liveness:         LivenessConfig{Interval: time.Second, Timeout: 2 * time.Second, Failures: 3},
		Traffic:          TrafficConfig{MaxPayloadSize: 4096, MinDelay: time.Millisecond, MaxDelay: 2 * time.Millisecond},
	}
	cfg.AuthHook = func(deviceID string, claims map[string]any) (string, error) {
		return deviceID + claims["key"].(string), nil
	}
	var opened, closed, trafficked, healthy bool
	cfg.OnSessionOpen = func(string, string, map[string]any) { opened = true }
	cfg.OnSessionClose = func(string, string) { closed = true }
	cfg.OnTraffic = func(string, string, uint64, uint64) { trafficked = true }
	cfg.OnHealth = func(HealthStatus) { healthy = true }

	got := toServerConfig(cfg)
	if id, err := got.AuthHook("device", map[string]any{"key": "-claim"}); err != nil || id != "device-claim" {
		t.Fatalf("AuthHook() = (%q, %v)", id, err)
	}
	got.OnSessionOpen("", "", nil)
	got.OnSessionClose("", "")
	got.OnTraffic("", "", 0, 0)
	got.OnHealth(control.Status{})
	if !opened || !closed || !trafficked || !healthy {
		t.Fatal("one or more hooks were not mapped")
	}
	if got.Transport != "vp8channel" || got.Provider != "jitsi" || got.RoomURL != "room" ||
		got.ChannelID != "channel" || got.Engine != "livekit" || got.URL != "wss://example" ||
		got.Token != "engine-token" || got.ProviderToken != "provider-token" || got.KeyHex != "key" ||
		got.DNSServer != "dns" || got.Resolver != resolver || got.SOCKSProxyAddr != "proxy" ||
		got.SOCKSProxyPort != 1080 || got.SOCKSProxyUser != "user" || got.SOCKSProxyPass != "pass" {
		t.Fatalf("scalar mapping = %#v", got)
	}
	gotOptions, ok := got.TransportOptions.(vp8channel.Options)
	if !ok || gotOptions.FPS != 25 || gotOptions.BatchSize != 8 {
		t.Fatalf("transport options = %#v", got.TransportOptions)
	}
	if got.Liveness.Interval != time.Second || got.Liveness.Timeout != 2*time.Second || got.Liveness.Failures != 3 {
		t.Fatalf("liveness = %#v", got.Liveness)
	}
	if got.Traffic.MaxPayloadSize != 4096 || got.Traffic.MinDelay != time.Millisecond ||
		got.Traffic.MaxDelay != 2*time.Millisecond {
		t.Fatalf("traffic = %#v", got.Traffic)
	}
}

func TestRunUsesMappedConfig(t *testing.T) {
	var got server.Config
	srv := &Server{
		cfg: Config{Provider: "jitsi", ChannelID: "channel", ProviderToken: "token"},
		run: func(_ context.Context, cfg server.Config) error {
			got = cfg
			return errRunner
		},
	}
	err := srv.Run(context.Background())
	if !errors.Is(err, errRunner) {
		t.Fatalf("Run() error = %v, want %v", err, errRunner)
	}
	if got.Provider != "jitsi" || got.ChannelID != "channel" || got.ProviderToken != "token" {
		t.Fatalf("runner config = %#v", got)
	}
}
