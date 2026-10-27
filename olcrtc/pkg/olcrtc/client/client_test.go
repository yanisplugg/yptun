package client

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"
	"time"

	internalclient "github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/transport/seichannel"
)

var errRunner = errors.New("runner")

func TestConfigMapping(t *testing.T) {
	resolver := &net.Resolver{PreferGo: true}
	options := SEIOptions{FPS: 30, BatchSize: 8, FragmentSize: 900, AckTimeoutMS: 2000}
	claims := map[string]any{"role": "client"}
	healthCalled := false
	cfg := Config{
		Transport: "seichannel", Provider: "wbstream", RoomURL: "room", ChannelID: "channel",
		Engine: "livekit", URL: "wss://example", Token: "engine-token",
		ProviderToken: "provider-token", KeyHex: "key", LocalAddr: "127.0.0.1:1080",
		SOCKSUser: "user", SOCKSPass: "pass", DNSServer: "dns", Resolver: resolver,
		TransportOptions: options,
		Liveness:         LivenessConfig{Interval: time.Second, Timeout: 2 * time.Second, Failures: 3},
		Traffic:          TrafficConfig{MaxPayloadSize: 4096, MinDelay: time.Millisecond, MaxDelay: 2 * time.Millisecond},
		DeviceID:         "device", DeviceIDPath: "/tmp/device", Claims: claims,
		OnHealth: func(HealthStatus) { healthCalled = true },
	}

	got := toClientConfig(cfg)
	got.OnHealth(control.Status{})
	if !healthCalled {
		t.Fatal("OnHealth was not mapped")
	}
	if got.Transport != "seichannel" || got.Provider != "wbstream" || got.RoomURL != "room" ||
		got.ChannelID != "channel" || got.Engine != "livekit" || got.URL != "wss://example" ||
		got.Token != "engine-token" || got.ProviderToken != "provider-token" || got.KeyHex != "key" ||
		got.LocalAddr != "127.0.0.1:1080" || got.SOCKSUser != "user" || got.SOCKSPass != "pass" ||
		got.DNSServer != "dns" || got.Resolver != resolver || got.DeviceID != "device" ||
		got.DeviceIDPath != "/tmp/device" || got.Claims["role"] != "client" {
		t.Fatalf("scalar mapping = %#v", got)
	}
	gotOptions, ok := got.TransportOptions.(seichannel.Options)
	if !ok || gotOptions.FPS != 30 || gotOptions.BatchSize != 8 ||
		gotOptions.FragmentSize != 900 || gotOptions.AckTimeoutMS != 2000 {
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

func TestRunWithReadyUsesMappedConfig(t *testing.T) {
	readyCalled := false
	var got internalclient.Config
	client := &Client{
		cfg: Config{Provider: "jitsi", ChannelID: "channel", ProviderToken: "token"},
		run: func(_ context.Context, cfg internalclient.Config, onReady func(string)) error {
			got = cfg
			onReady("127.0.0.1:1080")
			return errRunner
		},
	}
	err := client.RunWithReady(context.Background(), func() { readyCalled = true })
	if !errors.Is(err, errRunner) {
		t.Fatalf("RunWithReady() error = %v, want %v", err, errRunner)
	}
	if !readyCalled {
		t.Fatal("onReady was not forwarded")
	}
	if got.Provider != "jitsi" || got.ChannelID != "channel" || got.ProviderToken != "token" {
		t.Fatalf("runner config = %#v", got)
	}
}

func TestRunWithAddressForwardsActualAddress(t *testing.T) {
	const actualAddr = "127.0.0.1:43210"
	client := &Client{
		run: func(_ context.Context, _ internalclient.Config, onReady func(string)) error {
			onReady(actualAddr)
			return errRunner
		},
	}
	var got string
	err := client.RunWithAddress(context.Background(), func(address string) { got = address })
	if !errors.Is(err, errRunner) {
		t.Fatalf("RunWithAddress() error = %v, want %v", err, errRunner)
	}
	if got != actualAddr {
		t.Fatalf("callback address = %q, want %q", got, actualAddr)
	}
}

func TestRunParsesDirectRules(t *testing.T) {
	var got internalclient.Config
	client := &Client{
		cfg: Config{DirectRules: "domain:ru\n10.0.0.0/8\n"},
		run: func(_ context.Context, cfg internalclient.Config, _ func(string)) error {
			got = cfg
			return errRunner
		},
	}
	if err := client.Run(context.Background()); !errors.Is(err, errRunner) {
		t.Fatalf("Run() error = %v, want %v", err, errRunner)
	}
	if got.Direct == nil || !got.Direct.MatchDomain("a.ru") || !got.Direct.MatchHost("10.1.2.3") {
		t.Fatalf("Direct = %v, want the parsed rules", got.Direct)
	}
}

func TestRunRefusesBadDirectRules(t *testing.T) {
	ran := false
	client := &Client{
		cfg: Config{DirectRules: "domain:ru\nkeyword:x\n"},
		run: func(context.Context, internalclient.Config, func(string)) error {
			ran = true
			return nil
		},
	}
	err := client.Run(context.Background())
	if err == nil || !strings.Contains(err.Error(), "direct rules") || !strings.Contains(err.Error(), "line 2") {
		t.Fatalf("Run() error = %v, want a direct rules error naming line 2", err)
	}
	if ran {
		t.Fatal("the runner ran with rules that do not parse")
	}
}
