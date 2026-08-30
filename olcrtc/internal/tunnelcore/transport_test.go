package tunnelcore

import (
	"net"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

func TestBuildTransportConfigPreservesRoleFields(t *testing.T) {
	resolver := &net.Resolver{PreferGo: true}
	onData := func([]byte) {}
	onPeerData := func(string, []byte) {}
	cfg := BuildTransportConfig(LinkConfig{
		Provider: "provider", RoomURL: "room", Engine: "engine", URL: "url",
		Token: "token", ProviderToken: "auth-token", ChannelID: "channel",
		DNSServer: "dns", Traffic: transport.TrafficConfig{MaxPayloadSize: 4096},
	}, LinkRoleConfig{
		DeviceID: "device", OnData: onData, OnPeerData: onPeerData,
		Resolver: resolver, ProxyAddr: "proxy", ProxyPort: 1080,
		RequireTargetedPeer: true,
	})
	if cfg.DeviceID != "device" || cfg.Resolver != resolver || !cfg.RequireTargetedPeer {
		t.Fatalf("role config = %+v", cfg)
	}
	if cfg.OnData == nil || cfg.OnPeerData == nil || cfg.ProxyAddr != "proxy" || cfg.ProxyPort != 1080 {
		t.Fatalf("role callbacks/proxy = %+v", cfg)
	}
	if cfg.Provider != "provider" || cfg.RoomURL != "room" || cfg.Traffic.MaxPayloadSize != 4096 {
		t.Fatalf("shared config = %+v", cfg)
	}
}
