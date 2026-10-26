// Package client implements the local SOCKS5 client side of the olcrtc tunnel.
package client

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/route"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

var (
	ErrConnectFailed           = errors.New("tunnel connection failed")
	ErrProxyAuth               = errors.New("SOCKS proxy auth failed")
	ErrKeySize                 = runtime.ErrKeySize
	ErrInvalidSOCKSVersion     = errors.New("invalid socks version")
	ErrUnsupportedSOCKSCommand = errors.New("unsupported socks command")
	ErrUnsupportedAddressType  = errors.New("unsupported address type")
	ErrRemoteNotReady          = errors.New("remote not ready")
	ErrSOCKSAuthFailed         = errors.New("SOCKS5 authentication failed")
	ErrSOCKSCredTooLong        = errors.New("socks5 user/pass exceeds 255 bytes")
	ErrEmptySOCKSDomain        = errors.New("empty socks5 domain")
)

const (
	reconnectProvider = "provider"
	reconnectLiveness = "liveness"
	reconnectFallback = "liveness-fallback"
	// reconnectHandshake asks the provider for a new connection after the
	// handshakes over the one it gave went unanswered. ai-generated (olcrtc#19).
	reconnectHandshake = "handshake"
	// reconnectPeerClose is a control stream the peer closed on purpose: the
	// same teardown a liveness death reports, noticed the moment it happens
	// rather than a liveness window later. ai-generated (the port of olcrtc#39).
	reconnectPeerClose = "peer-close"
)

const (
	defaultLivenessFallback = 30 * time.Second

	// maxRecoveryPause and maxRecoveryBackoff bound the wait between rounds
	// of reconnect handshakes. Each consecutive round that ends without a
	// session doubles the fallback window, so a server that is gone, or one
	// that refuses this client, is still retried for as long as the tunnel
	// runs, but at a few rounds an hour instead of one every minute or two:
	// on Jitsi every round asks for a MUC rejoin, and nothing there ever
	// stops the asking (olcrtc#19, review of #20). The shift is bounded so
	// it cannot run off the end of the duration.
	maxRecoveryPause   = 5 * time.Minute
	maxRecoveryBackoff = 8

	// ipv6FailuresBeforeLatch and ipv6LatchWindow shape the judgement that
	// an exit has no IPv6 route. The exit answers host-unreachable for any
	// dial that fails, so one refusal is an ordinary dead destination and
	// says nothing about the address family; a run of them with none
	// succeeding in between is the shape of an exit that cannot route the
	// family at all. The judgement then lapses, so an exit whose route came
	// back - or one this got wrong - costs at most a window of IPv4-only
	// browsing rather than the whole session.
	//
	// ai-generated: both (review of olcrtc#39).
	ipv6FailuresBeforeLatch = 3
	ipv6LatchWindow         = 2 * time.Minute

	// defaultShutdownGrace bounds how long shutdown waits for tracked
	// goroutines after they have been told to stop.
	//
	// It has to be comfortably smaller than the deadline the host gives the
	// whole teardown, and it was not: the mobile runtime allows 5 s for all of
	// shutdown, and this step alone was allowed the same 5 s, so any drain that
	// did not finish immediately pushed the whole stop past its deadline. On a
	// phone that showed up as a tunnel that could not be switched to another
	// room. A goroutine still alive two seconds after being cancelled is stuck
	// rather than busy, and waiting longer for it only delays the tunnel the
	// user asked for next.
	defaultShutdownGrace = 2 * time.Second
)

// Client handles local SOCKS5 connections and tunnels them to the server.
type Client struct {
	ln          transport.Transport
	keys        *crypto.KeySet
	pair        *tunnelcore.SessionPair
	conn        *muxconn.Conn
	controlConn *muxconn.Conn
	session     *smux.Session
	controlSess *smux.Session
	controlStrm *smux.Stream
	controlStop context.CancelFunc
	// controlNotify tells the peer this side is leaving, on the control
	// stream of the current session, once; see startControlLoop.
	controlNotify func()
	sessMu        sync.RWMutex
	reconnectMu   sync.Mutex
	health        *runtime.HealthTracker

	// controlLastPong is independent corroboration for the transport's fast
	// peer-restart heuristic, not a second session reconnect detector.
	controlLastPong  atomic.Value // time.Time
	deviceID         string
	sessionID        string
	claims           map[string]any
	dnsServer        string
	socksUser        string
	socksPass        string
	sessionReady     chan struct{}
	wg               sync.WaitGroup
	socksMu          sync.Mutex
	socksConns       map[net.Conn]struct{}
	socksClosed      bool
	livenessFallback time.Duration
	shutdownGrace    time.Duration

	// ai-generated: recovery, handshakeTimeout and retryDelay (olcrtc#19).
	// recovery is the attempt re-establishing the session, if one is (see
	// recovery.go). handshakeTimeout and retryDelay stand in for
	// handshake.DefaultTimeout and the first pause between reconnect
	// handshakes; zero means the default, and only tests set them.
	recovery         recovery
	handshakeTimeout time.Duration
	retryDelay       time.Duration
	// failedRounds counts the rounds of handshakes since the last session,
	// which is what recoveryPause backs off on. ai-generated (olcrtc#19).
	failedRounds atomic.Int32

	// onSessionOpen hears each session id as the session is established;
	// see Config.OnSessionOpen.
	onSessionOpen SessionOpenFunc

	// endOnEmptyRoom ends the run instead of retrying in a room nobody is
	// in; see Config.EndOnEmptyRoom.
	//
	// ai-generated: the field (the port of olcrtc#39).
	endOnEmptyRoom bool

	// peerNoIPv6Until is when the exit's lack of an IPv6 route stops being
	// assumed, or zero while it is not; ipv6Failures counts the IPv6
	// literals it has refused in a row. A dual-stack host tries IPv6 first
	// for nearly every connection, so against an IPv4-only exit that is the
	// bulk of all streams, each one a stream open and a round trip over the
	// tunnel to learn what the one before it did. While the latch holds,
	// those are refused locally and Happy Eyeballs falls back to IPv4 at
	// once. Cleared per session, since another exit may have IPv6.
	//
	// ai-generated: the latch (these fields, noteConnectFailure,
	// isIPv6Literal and the check in tunnelWhenReady); the counter and the
	// window are from the review of olcrtc#39.
	peerNoIPv6Until atomic.Int64
	ipv6Failures    atomic.Int32

	// parked counts the requests waiting for a session that is not there
	// (tunnelWhenReady, waitSessionReady). With a tun2socks in front every
	// one of them is also a session over there, with a stack of its own that
	// no Go memory limit sees, and a phone whose apps retry through a network
	// gap parks hundreds in seconds (olcbox#37). maxParkedRequests bounds
	// it; parkedSaturated keeps the warning to one line per episode.
	// sessionReadyTimeout is how long a request waits; zero means the default.
	parked              atomic.Int32
	parkedSaturated     atomic.Bool
	sessionReadyTimeout time.Duration

	// UDP relay state: one entry per (association, SOCKS source, target).
	udpMu        sync.Mutex
	udpFlows     map[uint64]clientUDPFlow
	udpFlowIndex map[clientUDPFlowKey]uint64
	// udpSweepOnce starts the one idle-flow sweeper shared by every association.
	udpSweepOnce sync.Once
	udpDisabled  bool
	maxUDPFlows  int
	// datagramReadyTimeout is how long one datagram waits for the lane to
	// take it; zero means the default, and only tests set it.
	//
	// ai-generated: the field (olcrtc#49).
	datagramReadyTimeout time.Duration

	// DNS over the stream (dns.go): queries in flight, and the session the
	// info line was last written for.
	dnsInFlight         atomic.Int32
	dnsAnnouncedSession string

	// rules names the destinations dialed directly (direct.go); nil sends
	// everything through the tunnel. dialer opens those sockets, protected
	// and resolving through the session's lookup; exchanger answers direct
	// names' queries on the resolver ring (dns.go), when the lookup is one;
	// directUDP is the flows this process relays itself (direct_udp.go).
	rules              *route.Rules
	dialer             *protect.Dialer
	exchanger          protect.Exchanger
	directUDP          map[clientUDPFlowKey]*directUDPFlow
	dnsDirectAnnounced atomic.Bool
}

// HealthFunc is called when the client control health snapshot changes.
type HealthFunc func(control.Status)

// SessionOpenFunc is called each time a tunnel session is established - on the
// initial connect and after every reconnect - with the server-assigned session
// id. It runs on the connect path and under the client's reconnect lock, so it
// must return promptly: everything this client would do to recover from the
// next outage waits behind it, and on the reconnect path so does the
// provider's own callback.
//
// ai-generated: the session-open hook (this type, Config.OnSessionOpen and
// notifySessionOpen); the note on the lock is from the review of olcrtc#39.
type SessionOpenFunc func(sessionID string)

// Config holds runtime configuration for [Run], [RunWithReady], and [RunWithAddress].
type Config struct {
	Transport        string
	Provider         string
	RoomURL          string
	ChannelID        string
	KeyHex           string
	LocalAddr        string
	DNSServer        string
	DTLSProfile      engine.DTLSProfile
	Resolver         protect.Lookup
	SOCKSUser        string
	SOCKSPass        string
	TransportOptions transport.Options
	Engine           string
	URL              string
	Token            string
	ProviderToken    string
	Liveness         control.Config
	Traffic          transport.TrafficConfig
	DeviceID         string
	DeviceIDPath     string
	Claims           map[string]any
	OnHealth         HealthFunc
	// UDPDisabled turns the SOCKS5 UDP ASSOCIATE relay off; UDPMaxFlows caps
	// concurrent flows (0 means the default).
	UDPDisabled bool
	UDPMaxFlows int
	// Direct names the destinations dialed from this process instead of
	// through the tunnel; nil, the default, tunnels everything.
	Direct *route.Rules
	// OnSessionOpen, when set, is told each session id as the session is
	// established: on the initial connect and after every reconnect. A host
	// that cannot read the log learns of a room handover this way.
	OnSessionOpen SessionOpenFunc
	// EndOnEmptyRoom makes Run return once a reconnect handshake fails with
	// nothing in the room having sent a frame while it ran - the shape of a
	// room whose server has been retired. Set it when something above the
	// client has another room to try, as a supervisor walking a room list
	// has; leave it off, the default, when this room is the only one, since
	// then the client giving up leaves nobody retrying (olcrtc#19). A peer
	// that is in the room but silent, or one that refuses this client, is
	// never an empty room and is retried either way.
	//
	// ai-generated: the field (the port of olcrtc#39).
	EndOnEmptyRoom bool
}

// Run starts the client with the given configuration.
func Run(ctx context.Context, cfg Config) error {
	return RunWithAddress(ctx, cfg, nil)
}

// RunWithReady starts the client and invokes onReady after the SOCKS listener opens.
func RunWithReady(ctx context.Context, cfg Config, onReady func()) error {
	if onReady == nil {
		return RunWithAddress(ctx, cfg, nil)
	}
	return RunWithAddress(ctx, cfg, func(string) { onReady() })
}

// RunWithAddress starts the client and reports the actual SOCKS listener address.
func RunWithAddress(ctx context.Context, cfg Config, onReady func(actualAddr string)) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	keys, err := tunnelcore.SetupKeySet(cfg.KeyHex, crypto.Client)
	if err != nil {
		return fmt.Errorf("setup key set: %w", err)
	}
	deviceID, err := resolveDeviceID(cfg.DeviceID, cfg.DeviceIDPath)
	if err != nil {
		return fmt.Errorf("resolve device id: %w", err)
	}
	client := &Client{
		keys: keys, deviceID: deviceID, claims: cfg.Claims, dnsServer: cfg.DNSServer,
		socksUser: cfg.SOCKSUser, socksPass: cfg.SOCKSPass,
		health: runtime.NewHealthTracker(cfg.OnHealth), sessionReady: make(chan struct{}),
		udpFlows: make(map[uint64]clientUDPFlow), udpFlowIndex: make(map[clientUDPFlowKey]uint64),
		udpDisabled: cfg.UDPDisabled, maxUDPFlows: normalizeMaxUDPFlows(cfg.UDPMaxFlows),
		rules: cfg.Direct, dialer: protect.NewDialer(cfg.Resolver),
		onSessionOpen: cfg.OnSessionOpen, endOnEmptyRoom: cfg.EndOnEmptyRoom,
	}
	if exchanger, ok := cfg.Resolver.(protect.Exchanger); ok {
		client.exchanger = exchanger
	}
	defer func() {
		cancel()
		client.shutdown()
	}()
	if bringUpErr := client.bringUpLink(runCtx, cfg, cancel); bringUpErr != nil {
		return bringUpErr
	}
	listener, err := (&net.ListenConfig{}).Listen(runCtx, "tcp4", cfg.LocalAddr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", cfg.LocalAddr, err)
	}
	defer func() { _ = listener.Close() }()
	actualAddr := listener.Addr().String()
	logger.Infof("SOCKS5 server listening on %s", actualAddr)
	if client.rules != nil {
		logger.Infof("direct rules: %s", client.rules.Summary())
	}
	if onReady != nil {
		onReady(actualAddr)
	}
	client.goTracked(func() { client.acceptLoop(runCtx, listener) })
	<-runCtx.Done()
	return nil
}

// registerSocksConn tracks conn so shutdown can close it, and enforces the
// concurrency cap. It reports false when the cap is reached or the client is
// tearing down; the caller then closes conn itself.
func (c *Client) registerSocksConn(conn net.Conn) bool {
	c.socksMu.Lock()
	defer c.socksMu.Unlock()
	if c.socksClosed {
		return false
	}
	if len(c.socksConns) >= maxSocksConns {
		logger.Warnf("SOCKS5: %d concurrent connections reached, refusing new ones", maxSocksConns)
		return false
	}
	if c.socksConns == nil {
		c.socksConns = make(map[net.Conn]struct{})
	}
	c.socksConns[conn] = struct{}{}
	return true
}

func (c *Client) unregisterSocksConn(conn net.Conn) {
	c.socksMu.Lock()
	delete(c.socksConns, conn)
	c.socksMu.Unlock()
}

// closeSocksConns closes every live SOCKS connection. Without it shutdown
// would have to wait out the negotiation deadline of a client that connected
// and then went quiet.
func (c *Client) closeSocksConns() {
	c.socksMu.Lock()
	conns := c.socksConns
	c.socksConns = nil
	c.socksClosed = true
	c.socksMu.Unlock()
	for conn := range conns {
		_ = conn.Close()
	}
}

func (c *Client) goTracked(fn func()) {
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		fn()
	}()
}

func resolveDeviceID(deviceID, path string) (string, error) {
	if deviceID != "" {
		return deviceID, nil
	}
	if path == "" {
		return uuid.NewString(), nil
	}
	data, err := os.ReadFile(path) // #nosec G304 - path is explicit user configuration
	if err == nil {
		if id := strings.TrimSpace(string(data)); id != "" {
			return id, nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", fmt.Errorf("read device id %s: %w", path, err)
	}
	id := uuid.NewString()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return "", fmt.Errorf("mkdir device id dir: %w", err)
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return "", fmt.Errorf("write device id %s: %w", path, err)
	}
	return id, nil
}
