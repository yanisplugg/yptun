// Package client exposes the olcrtc SOCKS5 tunnel client as an embeddable Go library.
// New registers the built-in providers, engines, and transports automatically.
// RegisterDefaults is only needed after custom registry manipulation or extension.
package client

import (
	"context"
	"fmt"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
	internalclient "github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/route"
	runtimecfg "github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/seichannel"
	"github.com/openlibrecommunity/olcrtc/internal/transport/vp8channel"
)

// TransportOptions is implemented by the built-in public option structs.
type TransportOptions interface {
	transportOptions()
}

// VideoOptions configures videochannel.
type VideoOptions struct {
	Width      int
	Height     int
	FPS        int
	QRSize     int
	QRRecovery string
	Codec      string
	TileModule int
	TileRS     int
}

func (VideoOptions) transportOptions() {}

// VP8Options configures vp8channel.
type VP8Options struct {
	FPS       int
	BatchSize int
}

func (VP8Options) transportOptions() {}

// SEIOptions configures seichannel.
type SEIOptions struct {
	FPS          int
	BatchSize    int
	FragmentSize int
	AckTimeoutMS int
}

func (SEIOptions) transportOptions() {}

// HealthStatus is a control-stream health snapshot.
type HealthStatus = control.Status

// HealthFunc is called when the control-stream health snapshot changes.
type HealthFunc func(HealthStatus)

// SessionOpenFunc is called each time a tunnel session is established - on the
// initial connect and after every reconnect - with the server-assigned session
// id. It runs on the connect path, and everything the client would do to
// recover from the next outage waits behind it: return promptly and do the
// work elsewhere.
type SessionOpenFunc func(sessionID string)

// LivenessConfig controls control-stream ping and pong checks.
type LivenessConfig struct {
	Interval time.Duration
	Timeout  time.Duration
	Failures int
}

// TrafficConfig controls optional payload limits and send pacing.
type TrafficConfig struct {
	MaxPayloadSize int
	MinDelay       time.Duration
	MaxDelay       time.Duration
}

// Config holds all client tunnel capabilities.
type Config struct {
	Transport        string
	Provider         string
	RoomURL          string
	ChannelID        string
	Engine           string
	URL              string
	Token            string
	ProviderToken    string
	KeyHex           string
	LocalAddr        string
	SOCKSUser        string
	SOCKSPass        string
	DNSServer        string
	DTLSProfile      engine.DTLSProfile
	Resolver         protect.Lookup
	TransportOptions TransportOptions
	Liveness         LivenessConfig
	Traffic          TrafficConfig
	DeviceID         string
	DeviceIDPath     string
	Claims           map[string]any
	OnHealth         HealthFunc
	// UDPDisabled turns the SOCKS5 UDP ASSOCIATE relay off (it is on by
	// default for library users); UDPMaxFlows caps its flows, 0 = default.
	UDPDisabled bool
	UDPMaxFlows int
	// DirectRules names the destinations dialed from this process instead of
	// through the tunnel: one rule per line, `domain:<name>`, `full:<name>`,
	// an address or a CIDR prefix; # comments allowed. Empty, the default,
	// tunnels everything. A line that is not a rule fails Run.
	DirectRules string
	// OnSessionOpen, when set, is told each session id as the session is
	// established; a host that cannot read the log learns of a room
	// handover this way.
	OnSessionOpen SessionOpenFunc
	// EndOnEmptyRoom ends the run when a reconnect handshake finds the room
	// empty, instead of retrying in it for as long as the tunnel lives. Set
	// it only when the caller has another room to try.
	EndOnEmptyRoom bool
}

type runner func(context.Context, internalclient.Config, func(string)) error

// Client is an embeddable SOCKS5 tunnel client.
type Client struct {
	cfg Config
	run runner
}

// New returns a client configured by cfg.
func New(cfg Config) *Client {
	RegisterDefaults()
	return &Client{cfg: cfg, run: internalclient.RunWithAddress}
}

// Run starts the client and blocks until ctx is canceled or the provider ends.
func (c *Client) Run(ctx context.Context) error {
	return c.RunWithAddress(ctx, nil)
}

// RunWithReady starts the client and calls onReady after the SOCKS listener opens.
func (c *Client) RunWithReady(ctx context.Context, onReady func()) error {
	if onReady == nil {
		return c.RunWithAddress(ctx, nil)
	}
	return c.RunWithAddress(ctx, func(string) { onReady() })
}

// RunWithAddress starts the client and reports the actual SOCKS listener address.
func (c *Client) RunWithAddress(ctx context.Context, onReady func(actualAddr string)) error {
	direct, err := route.Parse(c.cfg.DirectRules)
	if err != nil {
		return fmt.Errorf("client: direct rules: %w", err)
	}
	cfg := toClientConfig(c.cfg)
	cfg.Direct = direct
	if err := c.run(ctx, cfg, onReady); err != nil {
		return fmt.Errorf("client: %w", err)
	}
	return nil
}

func toClientConfig(cfg Config) internalclient.Config {
	return internalclient.Config{
		Transport: cfg.Transport, Provider: cfg.Provider, RoomURL: cfg.RoomURL,
		ChannelID: cfg.ChannelID, Engine: cfg.Engine, URL: cfg.URL, Token: cfg.Token,
		ProviderToken: cfg.ProviderToken, KeyHex: cfg.KeyHex, LocalAddr: cfg.LocalAddr,
		SOCKSUser: cfg.SOCKSUser, SOCKSPass: cfg.SOCKSPass, DNSServer: cfg.DNSServer,
		DTLSProfile: cfg.DTLSProfile,
		Resolver:    cfg.Resolver, TransportOptions: toTransportOptions(cfg.TransportOptions),
		Liveness: control.Config{
			Interval: cfg.Liveness.Interval, Timeout: cfg.Liveness.Timeout, Failures: cfg.Liveness.Failures,
		},
		Traffic: transport.TrafficConfig{
			MaxPayloadSize: cfg.Traffic.MaxPayloadSize,
			MinDelay:       cfg.Traffic.MinDelay, MaxDelay: cfg.Traffic.MaxDelay,
		},
		DeviceID: cfg.DeviceID, DeviceIDPath: cfg.DeviceIDPath, Claims: cfg.Claims,
		OnHealth:       internalclient.HealthFunc(cfg.OnHealth),
		OnSessionOpen:  internalclient.SessionOpenFunc(cfg.OnSessionOpen),
		EndOnEmptyRoom: cfg.EndOnEmptyRoom,
		UDPDisabled:    cfg.UDPDisabled, UDPMaxFlows: cfg.UDPMaxFlows,
	}
}

func toTransportOptions(options TransportOptions) transport.Options {
	switch value := options.(type) {
	case VideoOptions:
		return videoTransportOptions(value)
	case VP8Options:
		return vp8channel.Options(value)
	case SEIOptions:
		return seichannel.Options(value)
	default:
		return nil
	}
}

// RegisterDefaults registers the built-in providers, engines, and transports.
// New calls it automatically. Manual calls are only needed after custom registry
// manipulation or extension. It is safe to call multiple times.
func RegisterDefaults() {
	session.RegisterDefaults()
}

// UseConstrainedBuffers shrinks every receive window this process advertises,
// for a host that is killed rather than swapped when it grows — an iOS packet
// tunnel extension is given roughly 50 MB, and the server-sized windows do not
// fit in it. Call it before starting a session; the peer needs no matching
// change, since both smux and KCP announce their receive windows.
func UseConstrainedBuffers() {
	runtimecfg.UseConstrainedBuffers()
}
