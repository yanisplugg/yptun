// Package freeturn is a gomobile-friendly wrapper around the free-turn-proxy
// client (mirrors cmd/client/main.go). It exposes Start/Stop/IsRunning so the
// olcvpn-client Android app can raise a local WireGuard/Xray entry listener
// that tunnels to a VK TURN backend.
//
// The package is named `freeturn` (not `mobile`) on purpose: gomobile derives
// the generated Java class name from the Go package, and olcrtc already binds a
// package named `mobile` (class mobile.Mobile) into the same .aar — sharing the
// name would collide.
//
// Socket protection is NOT handled here: the Android VpnService binds the whole
// process to the upstream network (ConnectivityManager.bindProcessToNetwork)
// before Start is called, so every socket this client opens egresses outside
// the tun — same approach the app already relies on for sing-box.
package freeturn

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/client/dnsdial"
	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/provider"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/bondclient"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/tcpfwd"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/udprelay"
	"github.com/samosvalishe/free-turn-proxy/internal/transport/dtlsdial"
)

const dtlsHandshakeConcurrency = 3

// LogWriter receives log lines from the freeturn client. Implemented on the
// Kotlin side and registered via SetLogWriter.
type LogWriter interface {
	WriteLog(line string)
}

type logBridge struct{ w LogWriter }

func (b logBridge) Write(p []byte) (int, error) {
	b.w.WriteLog(string(p))
	return len(p), nil
}

//nolint:gochecknoglobals // package-level singleton mirrors olcrtc/mobile.
var (
	mu      sync.Mutex
	running atomic.Bool
	cancel  context.CancelFunc
	done    chan struct{}
	debug   atomic.Bool
	// streams tracks live TURN relay streams of the current session. >0 means the
	// VK TURN path (DTLS + TURN allocation) is up, so the WireGuard outbound can be
	// started against the local listener. Exposed via ConnectedStreams.
	streams atomic.Int32
)

// SetLogWriter routes the freeturn client logs (standard log package) to w.
// Pass nil to restore the default destination.
func SetLogWriter(w LogWriter) {
	if w == nil {
		log.SetOutput(io.Discard)
		return
	}
	log.SetOutput(logBridge{w: w})
}

// SetDebug toggles verbose logging for subsequent Start calls.
func SetDebug(enabled bool) { debug.Store(enabled) }

// IsRunning reports whether a freeturn client is currently active.
func IsRunning() bool { return running.Load() }

// ConnectedStreams reports the number of live TURN relay streams. A value >0 means
// the VK TURN path is established (DTLS handshake + TURN allocation succeeded), so
// the WireGuard outbound dialling the local listener has a working uplink. Callers
// poll this after Start to order WireGuard bring-up behind the relay.
func ConnectedStreams() int { return int(streams.Load()) }

// Start launches the freeturn client described by uri (a freeturn://... share
// link). listenAddr is the local ip:port the client raises (WireGuard/Xray
// entry, e.g. 127.0.0.1:9000); vkLink is the VK Calls join link (per-client,
// not carried in the URI). streams is the number of parallel TURN relay streams
// (-n); pass <=0 to keep the client default (10) — more streams trade VK-call
// churn for throughput. It validates the configuration synchronously and runs
// the blocking relay loop in a background goroutine. Returns an error only for
// invalid configuration; runtime failures are logged and end the session
// (observable via IsRunning).
func Start(uri, listenAddr, vkLink string, nStreams int) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return errors.New("freeturn already running")
	}

	// Flags MUST precede the positional URI: Go's flag package stops parsing flags at
	// the first non-flag argument, so a leading URI would swallow -listen/-link/-debug
	// (ParseClient then fails with "need -link"). Keep the freeturn:// URI last.
	args := []string{"-listen", listenAddr}
	if vkLink != "" {
		args = append(args, "-link", vkLink)
	}
	if nStreams > 0 {
		args = append(args, "-n", strconv.Itoa(nStreams))
	}
	if debug.Load() {
		args = append(args, "-debug")
	}
	args = append(args, uri)

	cfg, err := config.ParseClient(args, io.Discard)
	if err != nil {
		return fmt.Errorf("freeturn config: %w", err)
	}

	ctx, cancelFn := context.WithCancel(context.Background())
	doneCh := make(chan struct{})
	cancel = cancelFn
	done = doneCh
	running.Store(true)

	streams.Store(0)
	go func() {
		defer close(doneCh)
		defer running.Store(false)
		defer streams.Store(0)
		if rerr := run(ctx, cfg); rerr != nil && !errors.Is(rerr, context.Canceled) {
			log.Printf("[freeturn] stopped: %v", rerr)
		}
	}()
	return nil
}

// Stop cancels the running client and waits (bounded) for it to unwind.
func Stop() {
	mu.Lock()
	c, d := cancel, done
	cancel, done = nil, nil
	mu.Unlock()
	if c != nil {
		c()
	}
	if d != nil {
		select {
		case <-d:
		case <-time.After(5 * time.Second):
		}
	}
}

// run mirrors cmd/client/main.go's runtime path with a cancelable context.
func run(ctx context.Context, cfg *config.Client) error {
	logger := logx.New(cfg.Log.Debug)
	logger.Infof("freeturn client starting (listen=%s)", cfg.Proxy.Listen)
	dnsdial.SetLogger(logger)

	if cfg.DNS.Servers != nil {
		dnsdial.SetUDPDNSServers(cfg.DNS.Servers)
	}
	appDialer := dnsdial.AppDialer(cfg.DNS.Mode)
	dnsdial.InstallGlobalResolver(cfg.DNS.Mode)

	peer, err := net.ResolveUDPAddr("udp", cfg.Proxy.Peer)
	if err != nil {
		return fmt.Errorf("resolve peer addr: %w", err)
	}

	connectedStreams := &streams
	prov, err := buildProvider(cfg, appDialer, connectedStreams, logger)
	if err != nil {
		return fmt.Errorf("provider init: %w", err)
	}
	logger.Infof("provider=%s", prov.Name())

	getCreds := func(ctx context.Context, streamID int) (string, string, string, error) {
		c, cerr := prov.GetCredentials(ctx, streamID)
		if cerr != nil {
			return "", "", "", cerr
		}
		return c.User, c.Pass, c.ServerAddr, nil
	}

	if cfg.Proxy.Mode != config.ProxyModeUDP {
		tcpDtlsDialer := &dtlsdial.Dialer{
			HandshakeTimeout: 30 * time.Second,
			HandshakeSem:     make(chan struct{}, dtlsHandshakeConcurrency),
		}
		bondH := &bondclient.Handler{Deps: bondclient.Deps{Log: logger}}
		tcpDeps := &tcpfwd.Deps{
			DTLSDialer:  tcpDtlsDialer,
			Log:         logger,
			BondHandler: bondH.Handle,
		}
		tcpParams := &tcpfwd.Params{
			Host:         cfg.TURN.Host,
			Port:         cfg.TURN.Port,
			TransportUDP: cfg.TURN.TransportUDP,
			ObfKey:       cfg.Obf.Key,
			GetCreds:     tcpfwd.GetCredsFunc(getCreds),
			KCPProfile:   cfg.KCP.Profile,
			KCPFEC:       cfg.KCP.FEC,
			ClientID:     cfg.ClientID,
		}
		return tcpfwd.Run(ctx, tcpDeps, tcpParams, peer, cfg.Proxy.Listen, cfg.TURN.N, cfg.Proxy.Mode == config.ProxyModeTCPFwdBond)
	}

	udpDtlsDialer := &dtlsdial.Dialer{
		HandshakeTimeout: 20 * time.Second,
		HandshakeSem:     make(chan struct{}, dtlsHandshakeConcurrency),
	}
	udpParams := &udprelay.Params{
		Host:         cfg.TURN.Host,
		Port:         cfg.TURN.Port,
		TransportUDP: cfg.TURN.TransportUDP,
		ObfKey:       cfg.Obf.Key,
		GetCreds:     udprelay.GetCredsFunc(getCreds),
		ClientID:     cfg.ClientID,
	}
	return udprelay.Run(ctx, udpDtlsDialer, prov, logger, connectedStreams, udpParams, peer, cfg.Proxy.Listen, cfg.TURN.N)
}

// buildProvider mirrors cmd/client/main.go.
func buildProvider(cfg *config.Client, dialer net.Dialer, connected *atomic.Int32, logger logx.Logger) (provider.Provider, error) {
	switch cfg.Provider.Name {
	case config.ProviderVK:
		return vk.New(vk.Config{
			Link:            cfg.VK.Link,
			Dialer:          dialer,
			ManualOnly:      cfg.VK.ManualCaptcha,
			StreamsPerCache: cfg.VK.StreamsPerCred,
			StreamsAlive:    connected.Load,
			Log:             logger,
			Debug:           cfg.Log.Debug,
		}, vk.DefaultManualSolver)
	default:
		return nil, fmt.Errorf("unknown provider %q", cfg.Provider.Name)
	}
}
