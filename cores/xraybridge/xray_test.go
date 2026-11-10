package xraybridge

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMeasureHTTPResponseDelayExcludesTunnelSetup(t *testing.T) {
	const dialDelay = 180 * time.Millisecond
	const responseDelay = 30 * time.Millisecond
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(responseDelay)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	dialer := &net.Dialer{}
	client := &http.Client{
		Timeout: time.Second,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				time.Sleep(dialDelay) // stands in for VLESS/Reality/XHTTP tunnel establishment
				return dialer.DialContext(ctx, network, address)
			},
		},
	}
	req, err := http.NewRequest(http.MethodHead, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}

	wallStart := time.Now()
	measured, err := measureHTTPResponseDelay(client, req)
	wall := time.Since(wallStart)
	if err != nil {
		t.Fatal(err)
	}
	if wall < dialDelay {
		t.Fatalf("test did not include the artificial dial delay: wall=%v", wall)
	}
	if measured < responseDelay/2 {
		t.Fatalf("response leg was not measured: got %v", measured)
	}
	if measured >= dialDelay/2 {
		t.Fatalf("tunnel setup leaked into ping: measured=%v wall=%v", measured, wall)
	}
}
