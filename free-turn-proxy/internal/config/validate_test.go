package config

import (
	"strings"
	"testing"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

func validClient() *Client {
	c := Defaults()
	c.Proxy.Peer = "1.2.3.4:5000"
	c.VK.Links = []string{"abcdef"}
	return &c
}

func TestValidateAcceptsDefaultsPlusRequired(t *testing.T) {
	if err := Validate(validClient()); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRejects(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*Client)
		want   string
	}{
		{"nil peer", func(c *Client) { c.Proxy.Peer = "" }, "peer"},
		{"no links", func(c *Client) { c.VK.Links = nil }, "link"},
		{"streams per cred", func(c *Client) { c.VK.StreamsPerCred = 0 }, "streams-per-cred"},
		{"platform", func(c *Client) { c.VK.Platform = "watch" }, "invalid -platform"},
		{"provider", func(c *Client) { c.Provider.Name = "bogus" }, "invalid -provider"},
		{"dns mode", func(c *Client) { c.DNS.Mode = "bogus" }, "invalid -dns-mode"},
		{"obf profile", func(c *Client) { c.Obf.Profile = "bogus" }, "invalid -obf-profile"},
		{"timing without obf", func(c *Client) { c.Obf.Timing = time.Second }, "-obf-timing"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := validClient()
			tc.mutate(c)
			err := Validate(c)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Validate() error = %v, want mention of %q", err, tc.want)
			}
		})
	}
}

func TestValidateRejectsNil(t *testing.T) {
	if err := Validate(nil); err == nil {
		t.Fatal("Validate(nil) error = nil")
	}
	if err := ValidateServer(nil); err == nil {
		t.Fatal("ValidateServer(nil) error = nil")
	}
}

func TestValidateServer(t *testing.T) {
	s := ServerDefaults()
	if err := ValidateServer(&s); err == nil {
		t.Fatal("ValidateServer() must require -connect")
	}
	s.Proxy.Connect = "127.0.0.1:51820"
	if err := ValidateServer(&s); err != nil {
		t.Fatalf("ValidateServer() error = %v", err)
	}
	s.Obf.Profile = "bogus"
	if err := ValidateServer(&s); err == nil {
		t.Fatal("ValidateServer() must reject unknown obf profile")
	}
}

// Встроенный WG гонит датаграммы - в tcp-режиме такой конфиг молча не работал бы.
func TestValidateTunnelRejectsTCPMode(t *testing.T) {
	c := validClient()
	c.Proxy.Mode = ProxyModeTCP
	c.Tunnel = TunnelOpts{Mode: tunnel.ModeWG, Config: "[Interface]", MTU: 1280}
	if err := Validate(c); err == nil || !strings.Contains(err.Error(), "tunnel requires") {
		t.Errorf("expected tunnel/mode error, got %v", err)
	}
}

// Маршруты к TURN от режима туннеля не зависят.
func TestValidateRoutesAllowedInTCPMode(t *testing.T) {
	c := validClient()
	c.Proxy.Mode = ProxyModeTCP
	c.Routes = true
	if err := Validate(c); err != nil {
		t.Errorf("routes must be allowed in tcp mode: %v", err)
	}
}
