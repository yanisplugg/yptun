// Package hy2 is a gomobile-friendly Hysteria2 client: it builds an apernet/hysteria core client
// and exposes a local SOCKS5 proxy backed by it, mirroring the awgproxy/freeturn pattern so the
// olcvpn client can point a sing-box socks outbound at it. Hysteria2 needs QUIC (apernet/quic-go,
// the same fork xray-core already uses), which is why it lives in its OWN module instead of being
// enabled in sing-box (sing-box's QUIC build tag clashes with xray's quic-go/qpack — see notes).
//
// The package is named `hy2` so gomobile's generated class is hy2.Hy2, not colliding with the other
// bound packages (awg.Awg, mobile.Mobile, freeturn.Freeturn).
package hy2

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/extras/v2/obfs"
	"github.com/apernet/hysteria/extras/v2/transport/udphop"
)

// LogWriter receives log lines; implemented on the Kotlin side.
type LogWriter interface{ WriteLog(line string) }

type logBridge struct{ w LogWriter }

func (b logBridge) Write(p []byte) (int, error) { b.w.WriteLog(string(p)); return len(p), nil }

// Protector protects a socket fd from the VPN (Kotlin VpnService.protect). Mirrors awg.Protector,
// so the Hysteria2 QUIC UDP socket leaves via the underlying network instead of the system TUN
// (otherwise it would loop back into the tunnel it is supposed to provide).
type Protector interface {
	Protect(fd int) bool
}

//nolint:gochecknoglobals // package singleton mirrors awg/freeturn/olcrtc.
var (
	mu          sync.Mutex
	running     atomic.Bool
	cli         client.Client
	listener    net.Listener
	logSink     io.Writer = io.Discard
	debug       atomic.Bool
	protectorMu sync.Mutex
	protector   Protector
)

// clientConfig is the JSON the Kotlin side passes to Start.
type clientConfig struct {
	Server       string `json:"server"`
	Port         int    `json:"port"`
	Ports        string `json:"ports"`        // optional port-hopping spec, e.g. "443,1000-2000"
	Auth         string `json:"auth"`         // password / auth string
	SNI          string `json:"sni"`          // TLS server name
	Insecure     bool   `json:"insecure"`     // skip TLS verification
	Obfs         string `json:"obfs"`         // "" or "salamander"
	ObfsPassword string `json:"obfsPassword"` // Salamander PSK
	UpMbps       int    `json:"upMbps"`       // optional bandwidth hint (Mbps)
	DownMbps     int    `json:"downMbps"`
}

// SetDebug toggles verbose logging.
func SetDebug(enabled bool) { debug.Store(enabled) }

// IsRunning reports whether a Hysteria2 SOCKS proxy is active.
func IsRunning() bool { return running.Load() }

// SetLogWriter routes logs to w (nil → discard).
func SetLogWriter(w LogWriter) {
	if w == nil {
		logSink = io.Discard
		return
	}
	logSink = logBridge{w: w}
}

// SetProtector installs the socket protector. Must be set before Start for protection to take effect.
func SetProtector(p Protector) {
	protectorMu.Lock()
	protector = p
	protectorMu.Unlock()
}

func logf(format string, args ...any) { log.New(logSink, "", 0).Printf(format, args...) }

// connFactory builds the UDP packet conns the QUIC client rides on. Each underlying socket is
// protected from the VPN; Salamander obfuscation and port-hopping wrap it like hysteria's own app.
type connFactory struct {
	psk         []byte
	hopInterval udphop.HopIntervalConfig
}

// listenProtected opens a UDP socket and protects its fd so QUIC packets bypass the system TUN.
func (f connFactory) listenProtected() (net.PacketConn, error) {
	uc, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, err
	}
	protectorMu.Lock()
	p := protector
	protectorMu.Unlock()
	if p != nil {
		if rc, cerr := uc.SyscallConn(); cerr == nil {
			_ = rc.Control(func(fd uintptr) { p.Protect(int(fd)) })
		}
	}
	return uc, nil
}

// New is called by the QUIC client to obtain the packet conn for [addr]. A *udphop.UDPHopAddr
// triggers port hopping; Salamander (when set) wraps the resulting conn. Mirrors the canonical
// hysteria adaptiveConnFactory (obfs OUTSIDE the hop conn).
func (f connFactory) New(addr net.Addr) (net.PacketConn, error) {
	var base net.PacketConn
	var err error
	if hopAddr, ok := addr.(*udphop.UDPHopAddr); ok {
		base, err = udphop.NewUDPHopPacketConn(hopAddr, f.hopInterval, f.listenProtected)
	} else {
		base, err = f.listenProtected()
	}
	if err != nil {
		return nil, err
	}
	if f.psk != nil {
		return obfs.WrapPacketConnSalamander(base, f.psk)
	}
	return base, nil
}

// Start builds a Hysteria2 client from the JSON config and raises a SOCKS5 proxy on listenAddr
// (e.g. 127.0.0.1:10811). Returns an error for invalid config, a failed handshake, or a bind error.
func Start(configJSON, listenAddr string) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return errors.New("hy2 already running")
	}

	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("hy2 config: %w", err)
	}
	if strings.TrimSpace(cfg.Server) == "" {
		return errors.New("hy2: empty server")
	}

	var psk []byte
	if strings.EqualFold(strings.TrimSpace(cfg.Obfs), "salamander") && cfg.ObfsPassword != "" {
		psk = []byte(cfg.ObfsPassword)
	}

	factory := connFactory{
		psk:         psk,
		hopInterval: udphop.HopIntervalConfig{Min: 30 * time.Second, Max: 30 * time.Second},
	}

	// Server address: a port range ("ports") means port hopping (UDPHopAddr), otherwise a plain UDP addr.
	var serverAddr net.Addr
	if strings.TrimSpace(cfg.Ports) != "" {
		host := cfg.Server
		if strings.Contains(host, ":") { // IPv6 literal
			host = "[" + host + "]"
		}
		hopAddr, err := udphop.ResolveUDPHopAddr(host + ":" + strings.TrimSpace(cfg.Ports))
		if err != nil {
			return fmt.Errorf("hy2 hop addr: %w", err)
		}
		serverAddr = hopAddr
	} else {
		port := cfg.Port
		if port <= 0 || port > 65535 {
			return errors.New("hy2: invalid port")
		}
		udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(cfg.Server, fmt.Sprintf("%d", port)))
		if err != nil {
			return fmt.Errorf("hy2 resolve: %w", err)
		}
		serverAddr = udpAddr
	}

	config := &client.Config{
		ConnFactory: factory,
		ServerAddr:  serverAddr,
		Auth:        cfg.Auth,
		TLSConfig: client.TLSConfig{
			ServerName:         cfg.SNI,
			InsecureSkipVerify: cfg.Insecure,
		},
		BandwidthConfig: client.BandwidthConfig{
			MaxTx: uint64(maxInt(cfg.UpMbps, 0)) * 125000,   // Mbps → bytes/s
			MaxRx: uint64(maxInt(cfg.DownMbps, 0)) * 125000,
		},
		FastOpen: true,
	}

	c, _, err := client.NewClient(config)
	if err != nil {
		return fmt.Errorf("hy2 connect: %w", err)
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		_ = c.Close()
		return fmt.Errorf("hy2 socks listen %s: %w", listenAddr, err)
	}

	cli = c
	listener = ln
	running.Store(true)
	go serveSocks(ln, c)
	logf("Hysteria2 SOCKS up on %s (server %s)", listenAddr, cfg.Server)
	return nil
}

// Stop tears down the SOCKS listener and the Hysteria2 client.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if listener != nil {
		_ = listener.Close()
		listener = nil
	}
	if cli != nil {
		_ = cli.Close()
		cli = nil
	}
	running.Store(false)
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
