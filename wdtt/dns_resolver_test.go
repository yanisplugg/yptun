package wdtt

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestChooseDNSRouteFallsBackToSystemAfterDirectTimeouts(t *testing.T) {
	directAttempts := make([]string, 0, 4)
	systemAttempts := 0

	route, probes := chooseDNSRouteWithProbes(
		context.Background(),
		&net.Resolver{},
		func(_ context.Context, route dnsRoute, _ time.Duration) error {
			directAttempts = append(directAttempts, route.Label)
			return context.DeadlineExceeded
		},
		func(_ context.Context, _ *net.Resolver, _ time.Duration) error {
			systemAttempts++
			return nil
		},
	)

	wantAttempts := []string{
		"77.88.8.8 UDP",
		"77.88.8.1 UDP",
		"77.88.8.8 TCP",
		"77.88.8.1 TCP",
	}
	if len(directAttempts) != len(wantAttempts) {
		t.Fatalf("direct attempts = %v, want %v", directAttempts, wantAttempts)
	}
	for i := range wantAttempts {
		if directAttempts[i] != wantAttempts[i] {
			t.Fatalf("direct attempts = %v, want %v", directAttempts, wantAttempts)
		}
	}
	if !route.System || route.Label != "системный DNS устройства" {
		t.Fatalf("route = %+v, want system resolver", route)
	}
	if systemAttempts != 1 {
		t.Fatalf("system attempts = %d, want 1", systemAttempts)
	}
	if len(probes) != len(wantAttempts)+1 {
		t.Fatalf("probes = %d, want %d", len(probes), len(wantAttempts)+1)
	}
}

func TestLookupHostRequiresDNSResponseFromConnectedSocket(t *testing.T) {
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(context.Context, string, string) (net.Conn, error) {
			client, server := net.Pipe()
			go func() {
				defer server.Close()
				_, _ = io.Copy(io.Discard, server)
			}()
			return client, nil
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()

	if err := lookupHostWithResolver(ctx, resolver, dnsProbeHost); err == nil {
		t.Fatal("connected DNS socket without a response was treated as working")
	}
}

func TestSummarizeDNSFailuresSkipsSystemWhenRequested(t *testing.T) {
	probes := []dnsRouteProbe{
		{Route: dnsRoute{Label: "77.88.8.8 UDP"}, Err: errors.New("timeout")},
		{Route: dnsRoute{Label: "системный DNS устройства", System: true}, Err: nil},
	}
	got := summarizeDNSFailures(probes, false)
	if got != "77.88.8.8 UDP: timeout" {
		t.Fatalf("summary = %q", got)
	}
}

func TestShortDNSErrorLimitsLongText(t *testing.T) {
	long := "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
	got := shortDNSError(errors.New(long))
	if len(got) > 123 {
		t.Fatalf("short error too long: %d", len(got))
	}
}
