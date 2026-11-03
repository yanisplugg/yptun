package tunnelcore

import (
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/names"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// LinkConfig contains transport fields shared by server and client roles.
type LinkConfig struct {
	Provider      string
	RoomURL       string
	Engine        string
	URL           string
	Token         string
	ProviderToken string
	ChannelID     string
	DNSServer     string
	DTLSProfile   engine.DTLSProfile
	Options       transport.Options
	Traffic       transport.TrafficConfig
}

// LinkRoleConfig contains transport fields that differ by tunnel role.
type LinkRoleConfig struct {
	DeviceID            string
	OnData              func([]byte)
	OnPeerData          func(string, []byte)
	OnDatagram          func([]byte)
	OnPeerDatagram      func(string, []byte)
	Resolver            protect.Lookup
	ProxyAddr           string
	ProxyPort           int
	RequireTargetedPeer bool
	// DefaultICE is the ICE mode this side asks for where a room link carries no
	// hint (roomhint): the client side TCP, a server nothing. ai-generated.
	DefaultICE string
}

// BuildTransportConfig combines shared link settings with role-specific callbacks.
func BuildTransportConfig(base LinkConfig, role LinkRoleConfig) transport.Config {
	return transport.Config{
		Provider:            base.Provider,
		RoomURL:             base.RoomURL,
		Engine:              base.Engine,
		URL:                 base.URL,
		Token:               base.Token,
		ProviderToken:       base.ProviderToken,
		ChannelID:           base.ChannelID,
		DeviceID:            role.DeviceID,
		Name:                names.Generate(),
		OnData:              role.OnData,
		OnPeerData:          role.OnPeerData,
		OnDatagram:          role.OnDatagram,
		OnPeerDatagram:      role.OnPeerDatagram,
		DNSServer:           base.DNSServer,
		DTLSProfile:         base.DTLSProfile,
		Resolver:            Resolver(role.Resolver, base.DNSServer),
		ProxyAddr:           role.ProxyAddr,
		ProxyPort:           role.ProxyPort,
		RequireTargetedPeer: role.RequireTargetedPeer,
		DefaultICE:          role.DefaultICE,
		Options:             base.Options,
		Traffic:             base.Traffic,
	}
}
