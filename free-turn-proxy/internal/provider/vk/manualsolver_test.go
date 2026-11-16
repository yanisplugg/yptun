package vk

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
)

func TestProxyPowCaptureDoesNotWaitBehindManualCaptcha(t *testing.T) {
	proxyManualMu.Lock()
	defer proxyManualMu.Unlock()

	capture := ProxyPowCapture(nil, nil)
	started := time.Now()
	_, err := capture(context.Background(), "http://localhost:8765/not_robot_captcha", net.Dialer{}, browserprofile.Profile{})
	if err == nil {
		t.Fatal("expected busy capture to return an error")
	}
	if elapsed := time.Since(started); elapsed >= time.Second {
		t.Fatalf("busy capture waited %s instead of falling back promptly", elapsed)
	}
}
