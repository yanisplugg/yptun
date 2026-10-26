// Package server implements the olcrtc tunnel server logic.
package server

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/handshake"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/testhooks"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

const (
	connectCommand = "connect"
	// statsReadHeaderTimeout bounds a slow client of the loopback /stats listener.
	statsReadHeaderTimeout = 5 * time.Second
)

var (
	ErrKeyRequired         = runtime.ErrKeyRequired
	ErrKeySize             = runtime.ErrKeySize
	ErrSocks5AuthFailed    = errors.New("SOCKS5 auth failed")
	ErrSocks5ConnectFailed = errors.New("SOCKS5 connect failed")
	ErrInvalidTarget       = errors.New("invalid connect target")
)

// SessionOpenFunc is called after a successful handshake.
type SessionOpenFunc func(sessionID, deviceID string, claims map[string]any)

// SessionCloseFunc is called when a session is torn down.
type SessionCloseFunc func(sessionID, reason string)

// TrafficFunc is called once per tunnel stream after both copy loops finish.
type TrafficFunc func(sessionID, addr string, bytesIn, bytesOut uint64)

// HealthFunc is called when the server control health snapshot changes.
type HealthFunc func(control.Status)

// Server handles incoming tunnel connections and proxies their traffic.
type Server struct {
	baseCtx context.Context //nolint:containedctx // server-lifetime context for reconnect goroutines
	ln      transport.Transport
	peerLn  transport.PeerTransport
	// ring holds every key a peer may pair under; group is the pin group of
	// the current single-link session generation (nil in peer routing).
	ring  *crypto.KeyRing
	group *muxconn.PinGroup
	pair  *tunnelcore.SessionPair
	conn  *muxconn.Conn

	controlConn *muxconn.Conn
	session     *smux.Session
	controlSess *smux.Session
	controlStrm *smux.Stream
	controlStop context.CancelFunc
	sessMu      sync.RWMutex

	peerSessions map[string]*peerSession
	// retiring is the peer session ended for each peer ID whose teardown has
	// not yet told the transport (retirePeer): whichever comes first, that
	// teardown or a new session for the ID, retires the peer. Guarded by
	// sessMu, made on first use. ai-generated: the field (olcrtc#49).
	retiring map[string]*peerSession
	// peerLimitWarn rate-limits the peer-cap warning.
	peerLimitWarn atomic.Int64
	peersMu       sync.Mutex
	peerStats     map[string]peerStat
	reinstallMu   sync.Mutex
	wg            sync.WaitGroup
	authHook      handshake.AuthFunc
	onOpen        SessionOpenFunc
	onClose       SessionCloseFunc
	onTraffic     TrafficFunc
	deviceID      string
	sessionID     string

	// meter attributes stream bytes to the pinned key of their session for
	// the /stats endpoint; link is the carrier state the same endpoint
	// reports. A nil link reads as LinkConnecting, so a server that never
	// joined never reads as one a client could pair with.
	meter *meter
	link  atomic.Pointer[LinkState]

	// unsafeAllowPrivateTargets lifts the egress policy (blockedRanges) for
	// TCP CONNECT and UDP flows alike; tests only. dialTarget dials a
	// CONNECT's checked ip:port, nil meaning the protected dialer; a test
	// sets it to see which address a CONNECT reaches. ai-generated: both
	// fields (egress hardening; the first was UDP-only).
	unsafeAllowPrivateTargets bool
	dialTarget                func(ctx context.Context, network, address string) (net.Conn, error)

	// UDP relay state (see udp.go). udpPendingFlows counts flows being
	// dialled so the cap holds while a dial is in flight.
	udpDisabled     bool
	maxUDPFlows     int
	udpMu           sync.Mutex
	udpFlows        map[serverUDPKey]*serverUDPFlow
	udpPendingFlows int

	dnsServer      string
	resolver       protect.Lookup
	socksProxyAddr string
	socksProxyPort int
	socksProxyUser string
	socksProxyPass string
	liveness       control.Config
	health         *runtime.HealthTracker
	state          stateGate
	done           chan struct{}
	doneOnce       sync.Once
}

// Config holds runtime configuration for [Run].
type Config struct {
	Transport string
	Provider  string
	RoomURL   string
	ChannelID string
	KeyHex    string
	// Keys is a ring of 64-hex keys the server accepts a peer under; the key
	// that opens a peer's first record is pinned for that peer. Empty means
	// the single KeyHex.
	Keys []string
	// StatsListen, when set, serves GET /stats on this loopback address with
	// per-key byte totals. Empty disables the listener.
	StatsListen string
	// UDPDisabled turns the SOCKS5 UDP relay off; UDPMaxFlows caps concurrent
	// flows (0 means the default). UnsafeAllowPrivateTargets lets TCP CONNECT
	// and UDP flows reach loopback, private, link-local and the other blocked
	// ranges; tests only. ai-generated: the field covers TCP too (egress
	// hardening; it was UnsafeAllowPrivateUDPTargets).
	UDPDisabled               bool
	UDPMaxFlows               int
	UnsafeAllowPrivateTargets bool
	DNSServer                 string
	DTLSProfile               engine.DTLSProfile
	Resolver                  protect.Lookup
	SOCKSProxyAddr            string
	SOCKSProxyPort            int
	SOCKSProxyUser            string
	SOCKSProxyPass            string
	TransportOptions          transport.Options
	Engine                    string
	URL                       string
	Token                     string
	ProviderToken             string
	Liveness                  control.Config
	Traffic                   transport.TrafficConfig
	AuthHook                  handshake.AuthFunc
	OnSessionOpen             SessionOpenFunc
	OnSessionClose            SessionCloseFunc
	OnTraffic                 TrafficFunc
	OnHealth                  HealthFunc
}

// Run starts the server with the given configuration.
func Run(ctx context.Context, cfg Config) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	ring, err := setupRing(cfg)
	if err != nil {
		return fmt.Errorf("setup key ring: %w", err)
	}
	hook := cfg.AuthHook
	if hook == nil {
		hook = defaultAuthHook
	}
	onOpen := cfg.OnSessionOpen
	if onOpen == nil {
		onOpen = func(string, string, map[string]any) {}
	}
	onClose := cfg.OnSessionClose
	if onClose == nil {
		onClose = func(string, string) {}
	}
	onTraffic := cfg.OnTraffic
	if onTraffic == nil {
		onTraffic = func(string, string, uint64, uint64) {}
	}
	s := &Server{
		ring: ring, authHook: hook, onOpen: onOpen, onClose: onClose, onTraffic: onTraffic,
		dnsServer: cfg.DNSServer, resolver: tunnelcore.Resolver(cfg.Resolver, cfg.DNSServer),
		socksProxyAddr: cfg.SOCKSProxyAddr, socksProxyPort: cfg.SOCKSProxyPort,
		socksProxyUser: cfg.SOCKSProxyUser, socksProxyPass: cfg.SOCKSProxyPass,
		liveness: cfg.Liveness, health: runtime.NewHealthTracker(cfg.OnHealth),
		peerSessions: make(map[string]*peerSession), peerStats: make(map[string]peerStat),
		done: make(chan struct{}), meter: newMeter(),
		udpDisabled: cfg.UDPDisabled, maxUDPFlows: normalizeMaxUDPFlows(cfg.UDPMaxFlows),
		// ai-generated: the test hook (egress hardening); a release build
		// has no hook and always keeps the policy.
		unsafeAllowPrivateTargets: cfg.UnsafeAllowPrivateTargets || testhooks.AllowPrivateTargets(),
		udpFlows:                  make(map[serverUDPKey]*serverUDPFlow),
	}
	defer func() {
		s.shutdown()
		s.wg.Wait()
	}()
	if cfg.StatsListen != "" {
		s.serveStats(runCtx, cfg.StatsListen)
	}
	if err := s.bringUpLink(runCtx, cfg, cancel); err != nil {
		return err
	}
	go func() {
		<-runCtx.Done()
		s.closeSession()
	}()
	s.serve(runCtx)
	return nil
}

// serveStats exposes per-key byte totals on a loopback endpoint an operator's
// agent polls. Best effort: a bind failure logs and leaves the meter in
// memory only, never blocking the tunnel. The listener closes with ctx.
func (s *Server) serveStats(ctx context.Context, addr string) {
	statsSrv := &http.Server{
		Addr:              addr,
		Handler:           s.meter.statsHandler(s.LinkState),
		ReadHeaderTimeout: statsReadHeaderTimeout,
	}
	go func() {
		logger.Infof("stats: serving /stats on %s", addr)
		if err := statsSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Warnf("stats: server on %s stopped: %v", addr, err)
		}
	}()
	go func() {
		<-ctx.Done()
		_ = statsSrv.Close()
	}()
}

// setupRing builds the candidate ring: cfg.Keys when given, else the single
// KeyHex as a one-entry ring so a stock config behaves as before.
func setupRing(cfg Config) (*crypto.KeyRing, error) {
	if len(cfg.Keys) == 0 {
		keys, err := tunnelcore.SetupKeySet(cfg.KeyHex, crypto.Server)
		if err != nil {
			return nil, fmt.Errorf("single key: %w", err)
		}
		id, _ := crypto.KeyIDFromHex(cfg.KeyHex)
		return crypto.SingleEntry(keys, id), nil
	}
	ring, err := crypto.NewKeyRing(cfg.Keys, crypto.Server)
	if err != nil {
		return nil, fmt.Errorf("build key ring: %w", err)
	}
	return ring, nil
}
