package mobile

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

func listeningProbeRunner(ctx context.Context, cfg client.Config, onReady func(string)) error {
	listener, err := net.Listen("tcp4", cfg.LocalAddr)
	if err != nil {
		return fmt.Errorf("listen probe SOCKS: %w", err)
	}
	defer func() { _ = listener.Close() }()
	onReady(listener.Addr().String())
	<-ctx.Done()
	return ctx.Err()
}

func TestCheckLetsListenArbitrateFixedPort(t *testing.T) {
	port := freeProbePort(t)
	configs := make(chan client.Config, 3)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		configs <- cfg
		return listeningProbeRunner(ctx, cfg, onReady)
	})
	if err := runtime.SetSocksPort(port); err != nil {
		t.Fatalf("SetSocksPort() error = %v", err)
	}
	if err := runtime.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.WaitReady(100); err != nil {
		t.Fatalf("WaitReady() error = %v", err)
	}
	<-configs
	_, err := runtime.Check("jitsi", transportVP8, testRoom, "device", testKey, port, 100, 30, 64)
	var opErr *net.OpError
	if !errors.As(err, &opErr) {
		t.Fatalf("Check(active port) error = %v, want bind error", err)
	}
	<-configs
	latency, err := runtime.Check("jitsi", transportVP8, testRoom, "device", testKey, 0, 100, 30, 64)
	if err != nil {
		t.Fatalf("Check(ephemeral port) error = %v", err)
	}
	if latency < 0 {
		t.Fatalf("Check() latency = %d", latency)
	}
	probeConfig := <-configs
	if probeConfig.LocalAddr != "127.0.0.1:0" {
		t.Fatalf("ephemeral probe requested %q, want 127.0.0.1:0", probeConfig.LocalAddr)
	}
	if err := runtime.Stop(100); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
}

func TestCheckSupportsEveryTransport(t *testing.T) {
	configs := make(chan client.Config, 4)
	runtime := configuredRuntime(t, func(ctx context.Context, cfg client.Config, onReady func(string)) error {
		configs <- cfg
		onReady(cfg.LocalAddr)
		<-ctx.Done()
		return ctx.Err()
	})
	for _, transport := range []string{transportData, transportVP8, transportSEI, transportVideo} {
		if _, err := runtime.Check("jitsi", transport, testRoom, "device", testKey, 0, 100, 30, 64); err != nil {
			t.Fatalf("Check(%q) error = %v", transport, err)
		}
		cfg := <-configs
		if cfg.Transport != transport {
			t.Fatalf("Check(%q) transport = %q", transport, cfg.Transport)
		}
	}
}

func TestCheckTimeoutAndRunnerError(t *testing.T) {
	runtime := configuredRuntime(t, func(ctx context.Context, _ client.Config, _ func(string)) error {
		<-ctx.Done()
		return ctx.Err()
	})
	if _, err := runtime.Check("jitsi", transportData, testRoom, "device", testKey, 0, 1, 0, 0); !errors.Is(err, ErrReadyTimeout) {
		t.Fatalf("Check(timeout) error = %v, want %v", err, ErrReadyTimeout)
	}
	runtime.runner = func(context.Context, client.Config, func(string)) error { return errTestRun }
	if _, err := runtime.Check("jitsi", transportData, testRoom, "device", testKey, 0, 100, 0, 0); !errors.Is(err, errTestRun) {
		t.Fatalf("Check(runner error) = %v, want %v", err, errTestRun)
	}
}

func TestPingURLAndHTTPRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(server.Close)
	httpClient := server.Client()
	latency, err := singleHTTPPingRequest(context.Background(), httpClient, server.URL, httpPingSampleTimeout)
	if err != nil {
		t.Fatalf("singleHTTPPingRequest() error = %v", err)
	}
	if latency < 0 {
		t.Fatalf("latency = %d", latency)
	}
	if _, err := normalizeHTTPPingURL("file:///tmp/test"); err == nil {
		t.Fatal("normalizeHTTPPingURL() accepted unsupported scheme")
	}
}

func TestConcurrentEphemeralProbesReportActiveAddresses(t *testing.T) {
	const probeCount = 3
	addresses := make(chan string, probeCount)
	release := make(chan struct{})
	results := make(chan error, probeCount)
	var start sync.WaitGroup
	start.Add(probeCount)

	for range probeCount {
		runtime := configuredRuntime(t, listeningProbeRunner)
		cfg, err := runtime.probeConfig("jitsi", transportData, testRoom, "device", testKey, 0, 0, 0)
		if err != nil {
			t.Fatalf("probeConfig() error = %v", err)
		}
		if cfg.LocalAddr != "127.0.0.1:0" {
			t.Fatalf("probe LocalAddr = %q, want 127.0.0.1:0", cfg.LocalAddr)
		}
		go func() {
			start.Done()
			start.Wait()
			_, runErr := runtime.runProbe(cfg, 2*time.Second, func(_ context.Context, address string) (int64, error) {
				addresses <- address
				<-release
				return 1, nil
			})
			results <- runErr
		}()
	}

	seen := make(map[string]struct{}, probeCount)
	for range probeCount {
		address := waitProbeAddress(t, addresses, results)
		_, port, err := net.SplitHostPort(address)
		if err != nil || port == "0" {
			t.Fatalf("actual probe address = %q, split error = %v", address, err)
		}
		if _, exists := seen[address]; exists {
			t.Fatalf("duplicate active probe address %q", address)
		}
		seen[address] = struct{}{}
		conn, err := net.DialTimeout("tcp4", address, time.Second)
		if err != nil {
			t.Fatalf("dial active probe %q: %v", address, err)
		}
		_ = conn.Close()
	}
	close(release)
	for range probeCount {
		select {
		case err := <-results:
			if err != nil {
				t.Fatalf("runProbe() error = %v", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("timed out waiting for probe shutdown")
		}
	}
}

func waitProbeAddress(t *testing.T, addresses <-chan string, results <-chan error) string {
	t.Helper()
	select {
	case address := <-addresses:
		return address
	case err := <-results:
		t.Fatalf("probe stopped before reporting its address: %v", err)
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for active probe address")
	}
	return ""
}

func freeProbePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve test port: %v", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatalf("release test port: %v", err)
	}
	return port
}
