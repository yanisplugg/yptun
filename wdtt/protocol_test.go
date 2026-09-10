package wdtt

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"
)

func TestParseConfigResponseWorkerPolicyLimit(t *testing.T) {
	config, err := parseConfigResponse("POLICY:max_workers=12")
	if config != "" {
		t.Fatalf("expected no config, got %q", config)
	}
	maxWorkers, limited := workerPolicyLimit(err)
	if !limited || maxWorkers != 12 {
		t.Fatalf("expected worker policy limit 12, got limit=%d error=%v", maxWorkers, err)
	}
}

func TestParseConfigResponseRejectsMalformedWorkerPolicy(t *testing.T) {
	_, err := parseConfigResponse("POLICY:max_workers=none")
	if err == nil || !strings.Contains(err.Error(), "некорректная политика") {
		t.Fatalf("expected malformed policy error, got %v", err)
	}
}

func TestParseConfigResponseKeepsRegularConfig(t *testing.T) {
	const config = "[Interface]\nAddress=10.0.0.2/32"
	parsed, err := parseConfigResponse(config)
	if err != nil || parsed != config {
		t.Fatalf("regular config changed: parsed=%q error=%v", parsed, err)
	}
}

func TestWorkerPolicyRetriesOnlyWhenTheRequestedProfileFits(t *testing.T) {
	if !shouldRetryWorkerPolicy(12, 9) {
		t.Fatal("a nine-worker profile must recover after transient old sessions release")
	}
	if shouldRetryWorkerPolicy(12, 18) {
		t.Fatal("a profile above the server policy must not retry rejected workers forever")
	}
}

func TestRequestConfigIncludesTransportSessionCompatibly(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	payload := make(chan string, 1)
	go func() {
		buffer := make([]byte, 4096)
		n, err := server.Read(buffer)
		if err != nil {
			payload <- "read-error"
			return
		}
		payload <- string(buffer[:n])
		_, _ = server.Write([]byte("NOCONF"))
	}()

	config, err := RequestConfig(
		context.Background(),
		client,
		"9000",
		"android-device",
		"secret",
		`{"name":"Phone"}`,
		"transport-session-0001",
	)
	if err != nil || config != "" {
		t.Fatalf("request failed: config=%q error=%v", config, err)
	}
	if got := <-payload; got != `GETCONF:9000|android-device|secret|{"name":"Phone"}|transport-session-0001` {
		t.Fatalf("unexpected request payload: %q", got)
	}
}

func TestRequestConfigStopsWaitingWhenTransportIsCancelled(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	requestDone := make(chan error, 1)
	go func() {
		_, err := RequestConfig(
			ctx,
			client,
			"9000",
			"android-device",
			"secret",
			"",
			"transport-session-0001",
		)
		requestDone <- err
	}()

	buffer := make([]byte, 4096)
	if _, err := server.Read(buffer); err != nil {
		t.Fatalf("server did not receive GETCONF: %v", err)
	}
	cancel()

	select {
	case err := <-requestDone:
		if err == nil {
			t.Fatal("cancelled GETCONF unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled GETCONF kept waiting")
	}
}

func TestTransportSessionNormalization(t *testing.T) {
	generated := newTransportSession()
	if normalizeTransportSession(generated) != generated {
		t.Fatalf("generated transport session is invalid: %q", generated)
	}
	if normalizeTransportSession("too-short") != "" {
		t.Fatal("short transport session was accepted")
	}
	if normalizeTransportSession("invalid transport session") != "" {
		t.Fatal("transport session with spaces was accepted")
	}
}
