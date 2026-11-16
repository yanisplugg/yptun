package freeturn

import (
	"testing"

	shareuri "github.com/samosvalishe/free-turn-proxy/internal/uri"
)

func TestAutoCaptchaAndAppOverrides(t *testing.T) {
	link := (&shareuri.Config{
		Version: 1, Provider: "vk", Peer: "203.0.113.7:56000", Transport: "tcp",
		Mode: "udp", Listen: "127.0.0.1:9999", N: 99,
	}).String()
	SetAutoCaptcha(false)
	t.Cleanup(func() { SetAutoCaptcha(true) })
	cfg, _, err := buildClientConfig(link, "127.0.0.1:9101", "https://vk.com/call/join/ABC", 7)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.VK.AutoCaptcha {
		t.Fatal("auto captcha remained enabled")
	}
	if cfg.Proxy.Listen != "127.0.0.1:9101" || cfg.TURN.N != 7 {
		t.Fatalf("app overrides lost: listen=%q n=%d", cfg.Proxy.Listen, cfg.TURN.N)
	}
}
