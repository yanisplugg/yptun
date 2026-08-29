// Package awg is a gomobile-friendly AmneziaWG client: it brings up an amneziawg-go device on a
// userspace gVisor netstack and exposes a local SOCKS5 proxy backed by that tunnel. The olcvpn
// client points a sing-box socks outbound at it, so AmneziaWG works as a normal outbound and as a
// chain hop without touching sing-box's (vanilla) WireGuard engine.
//
// The package is named `awg` (not `mobile`) so gomobile's generated Java class is awg.Awg and does
// not collide with the other bound packages (olcrtc's mobile.Mobile, freeturn.Freeturn).
package awg

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// LogWriter receives log lines; implemented on the Kotlin side.
type LogWriter interface{ WriteLog(line string) }

type logBridge struct{ w LogWriter }

func (b logBridge) Write(p []byte) (int, error) { b.w.WriteLog(string(p)); return len(p), nil }

//nolint:gochecknoglobals // package singleton mirrors freeturn/olcrtc.
var (
	mu       sync.Mutex
	running  atomic.Bool
	dev      *device.Device
	listener net.Listener
	logSink  io.Writer = io.Discard
	debug    atomic.Bool
)

// Protector protects a socket fd from the VPN (implemented in Kotlin via VpnService.protect). It
// mirrors xraybridge.Protector so the AmneziaWG probe/measure sockets bypass the active system TUN
// — otherwise, while connected, the throwaway WG handshake would ride the tunnel and report a
// bogus (tunnel-inflated) latency instead of the real path to the endpoint.
type Protector interface {
	Protect(fd int) bool
}

//nolint:gochecknoglobals // process-wide, mirrors the other bound packages.
var (
	protectorMu sync.Mutex
	protector   Protector
)

// SetProtector installs the socket protector used by Probe/MeasureDelay (and Start). Passing nil
// clears it. Must be set before those calls for protection to take effect.
func SetProtector(p Protector) {
	protectorMu.Lock()
	protector = p
	protectorMu.Unlock()
}

// protectBind protects the UDP socket(s) the WireGuard bind has just opened (after device Up), so
// outbound WG packets leave via the underlying network rather than the system VPN tun. No-op when
// no protector is set or the bind doesn't expose its fd (non-Android builds).
func protectBind(bind conn.Bind) {
	protectorMu.Lock()
	p := protector
	protectorMu.Unlock()
	if p == nil {
		return
	}
	type fdPeeker interface {
		PeekLookAtSocketFd4() (int, error)
		PeekLookAtSocketFd6() (int, error)
	}
	b, ok := bind.(fdPeeker)
	if !ok {
		return
	}
	if fd, err := b.PeekLookAtSocketFd4(); err == nil && fd >= 0 {
		p.Protect(fd)
	}
	if fd, err := b.PeekLookAtSocketFd6(); err == nil && fd >= 0 {
		p.Protect(fd)
	}
}

// SetLogWriter routes logs to w (nil → discard).
func SetLogWriter(w LogWriter) {
	if w == nil {
		logSink = io.Discard
		return
	}
	logSink = logBridge{w: w}
}

// SetDebug toggles verbose device logging.
func SetDebug(enabled bool) { debug.Store(enabled) }

// IsRunning reports whether an AmneziaWG SOCKS proxy is active.
func IsRunning() bool { return running.Load() }

// Start brings up the AmneziaWG tunnel from a wg-quick-style INI config (which also carries the
// Amnezia obfuscation params Jc/Jmin/Jmax/S1/S2/H1..H4) and raises a SOCKS5 proxy on listenAddr
// (e.g. 127.0.0.1:10810). Returns an error for invalid config or if the listener can't bind.
func Start(iniConfig, listenAddr string) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return errors.New("awg already running")
	}

	cfg, err := parseConfig(iniConfig)
	if err != nil {
		return fmt.Errorf("awg config: %w", err)
	}
	uapi, err := cfg.uapi()
	if err != nil {
		return fmt.Errorf("awg uapi: %w", err)
	}

	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return fmt.Errorf("awg netstack: %w", err)
	}

	level := device.LogLevelError
	if debug.Load() {
		level = device.LogLevelVerbose
	}
	logger := device.NewLogger(level, "[awg] ")
	logger.Verbosef = func(format string, args ...any) { log.New(logSink, "", 0).Printf(format, args...) }
	logger.Errorf = func(format string, args ...any) { log.New(logSink, "", 0).Printf(format, args...) }

	bind := bindFor(cfg)
	d := device.NewDevice(tunDev, bind, logger)
	if err := d.IpcSet(uapi); err != nil {
		d.Close()
		return fmt.Errorf("awg ipc: %w", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return fmt.Errorf("awg up: %w", err)
	}
	protectBind(bind)

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		d.Close()
		return fmt.Errorf("awg socks listen %s: %w", listenAddr, err)
	}

	dev = d
	listener = ln
	running.Store(true)
	go serveSocks(ln, tnet)
	log.New(logSink, "", 0).Printf("AmneziaWG SOCKS up on %s", listenAddr)
	return nil
}

// Probe measures round-trip latency to the AmneziaWG server WITHOUT a full connection: it brings
// up a throwaway device on a private netstack, forces the WG handshake by dialing a reachable host
// through the tunnel, returns the elapsed ms, and tears everything down. Returns -1 on failure
// (unreachable/blocked/bad config). Safe to call while a real session runs (own socket/device).
func Probe(iniConfig string) int64 {
	cfg, err := parseConfig(iniConfig)
	if err != nil {
		return -1
	}
	uapi, err := cfg.uapi()
	if err != nil {
		return -1
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return -1
	}
	bind := bindFor(cfg)
	d := device.NewDevice(tunDev, bind, device.NewLogger(device.LogLevelError, "[awg-probe] "))
	defer d.Close()
	if err := d.IpcSet(uapi); err != nil {
		return -1
	}
	if err := d.Up(); err != nil {
		return -1
	}
	protectBind(bind)
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	c, err := tnet.DialContext(ctx, "tcp", "1.1.1.1:443")
	if err != nil {
		return -1
	}
	_ = c.Close()
	return time.Since(start).Milliseconds()
}

// MeasureDelay brings up a THROWAWAY AmneziaWG tunnel from [iniConfig], fetches [url] (HTTP
// [method] "GET"/"HEAD") THROUGH it, and returns the round-trip in milliseconds (-1 on failure).
// Mirrors xraybridge.MeasureDelay for the AWG outbound: needs no system VPN/TUN and is independent
// of any running session, so AmneziaWG servers can be URL-tested from the list while disconnected.
func MeasureDelay(iniConfig, url, method string, timeoutMs int) int64 {
	cfg, err := parseConfig(iniConfig)
	if err != nil {
		return -1
	}
	uapi, err := cfg.uapi()
	if err != nil {
		return -1
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return -1
	}
	bind := bindFor(cfg)
	d := device.NewDevice(tunDev, bind, device.NewLogger(device.LogLevelError, "[awg-urltest] "))
	defer d.Close()
	if err := d.IpcSet(uapi); err != nil {
		return -1
	}
	if err := d.Up(); err != nil {
		return -1
	}
	protectBind(bind)

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			// Every connection (incl. DNS) rides the AmneziaWG netstack tunnel.
			DialContext: tnet.DialContext,
		},
	}
	if method == "" {
		method = "HEAD"
	}
	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		return -1
	}
	req.Header.Set("User-Agent", "olcbox-ping")
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1
	}
	_ = resp.Body.Close()
	return time.Since(start).Milliseconds()
}

// Stop tears down the SOCKS listener and the AmneziaWG device.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if listener != nil {
		_ = listener.Close()
		listener = nil
	}
	if dev != nil {
		dev.Close()
		dev = nil
	}
	running.Store(false)
}

// --- config ---

type wgConfig struct {
	privateKeyHex string
	peerPublicHex string
	endpoint      string
	allowedIPs    []string
	keepalive     int
	addresses     []netip.Addr
	dns           []netip.Addr
	mtu           int
	// Amnezia obfuscation knobs (jc/jmin/jmax/s1..s4/h1..h4/i1..i5/j1..j3/itime), preserved in
	// input order with their raw values (e.g. i-packets are "<b 0x...>").
	awgParams [][2]string
	// Cloudflare WARP "reserved" header bytes (the registration client_id). WARP REQUIRES every
	// outgoing WireGuard message to carry these 3 bytes in the otherwise-reserved header field
	// (bytes 1..3); amneziawg-go leaves them zero, so Cloudflare silently drops all data. Stamped
	// on Send by reservedBind. Zero/absent (hasReserved=false) for normal AmneziaWG servers.
	reserved    [3]byte
	hasReserved bool
}

// awgKnobs are the AmneziaWG obfuscation keys passed through verbatim to the device UAPI.
// These are exactly the keys amneziawg-go's UAPI accepts (device/uapi.go). S3/S4 are NOT standard
// AmneziaWG and are rejected by the device — skip them (configs that carry S3/S4 set them to 0).
var awgKnobs = map[string]bool{
	"jc": true, "jmin": true, "jmax": true,
	"s1": true, "s2": true,
	"h1": true, "h2": true, "h3": true, "h4": true,
	"i1": true, "i2": true, "i3": true, "i4": true, "i5": true,
	"j1": true, "j2": true, "j3": true, "itime": true,
}

func parseConfig(ini string) (*wgConfig, error) {
	c := &wgConfig{mtu: 1280, keepalive: 25}
	for _, raw := range strings.Split(ini, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "[") || strings.HasPrefix(line, "#") {
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq <= 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		val := strings.TrimSpace(line[eq+1:])
		switch key {
		case "privatekey":
			h, err := keyToHex(val)
			if err != nil {
				return nil, fmt.Errorf("privatekey: %w", err)
			}
			c.privateKeyHex = h
		case "publickey":
			h, err := keyToHex(val)
			if err != nil {
				return nil, fmt.Errorf("publickey: %w", err)
			}
			c.peerPublicHex = h
		case "endpoint":
			c.endpoint = val
		case "allowedips":
			for _, p := range strings.Split(val, ",") {
				if p = strings.TrimSpace(p); p != "" {
					c.allowedIPs = append(c.allowedIPs, p)
				}
			}
		case "persistentkeepalive":
			c.keepalive, _ = strconv.Atoi(val)
		case "address":
			for _, p := range strings.Split(val, ",") {
				if a, err := netip.ParsePrefix(strings.TrimSpace(p)); err == nil {
					c.addresses = append(c.addresses, a.Addr())
				} else if a, err := netip.ParseAddr(strings.TrimSpace(p)); err == nil {
					c.addresses = append(c.addresses, a)
				}
			}
		case "dns":
			for _, p := range strings.Split(val, ",") {
				if a, err := netip.ParseAddr(strings.TrimSpace(p)); err == nil {
					c.dns = append(c.dns, a)
				}
			}
		case "mtu":
			if m, err := strconv.Atoi(val); err == nil && m > 0 {
				c.mtu = m
			}
		case "reserved":
			// "b0, b1, b2" — three decimal bytes (Cloudflare WARP client_id). Anything else is ignored.
			parts := strings.Split(val, ",")
			if len(parts) == 3 {
				ok := true
				var r [3]byte
				for i, p := range parts {
					n, err := strconv.Atoi(strings.TrimSpace(p))
					if err != nil || n < 0 || n > 255 {
						ok = false
						break
					}
					r[i] = byte(n)
				}
				if ok && r != ([3]byte{}) {
					c.reserved = r
					c.hasReserved = true
				}
			}
		default:
			if awgKnobs[key] && val != "" {
				c.awgParams = append(c.awgParams, [2]string{key, val})
			}
		}
	}
	if c.privateKeyHex == "" || c.peerPublicHex == "" || c.endpoint == "" {
		return nil, errors.New("missing PrivateKey/PublicKey/Endpoint")
	}
	if len(c.addresses) == 0 {
		return nil, errors.New("missing Address")
	}
	if len(c.allowedIPs) == 0 {
		c.allowedIPs = []string{"0.0.0.0/0", "::/0"}
	}
	if len(c.dns) == 0 {
		c.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return c, nil
}

// uapi renders the device IPC config (amneziawg-go accepts hex keys + the awg obfuscation knobs).
func (c *wgConfig) uapi() (string, error) {
	ep, err := resolveEndpoint(c.endpoint)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", c.privateKeyHex)
	// Amnezia params must precede the peer to take effect for the handshake; emit in input order
	// with raw values (i-packets are "<b 0x...>" tokens the device parses itself).
	for _, kv := range c.awgParams {
		fmt.Fprintf(&b, "%s=%s\n", kv[0], kv[1])
	}
	fmt.Fprintf(&b, "public_key=%s\n", c.peerPublicHex)
	fmt.Fprintf(&b, "endpoint=%s\n", ep)
	for _, a := range c.allowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", a)
	}
	if c.keepalive > 0 {
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", c.keepalive)
	}
	return b.String(), nil
}

func keyToHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		// maybe already hex
		if _, herr := hex.DecodeString(b64); herr == nil {
			return strings.ToLower(b64), nil
		}
		return "", err
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("key must be 32 bytes, got %d", len(raw))
	}
	return hex.EncodeToString(raw), nil
}

func resolveEndpoint(ep string) (string, error) {
	host, port, err := net.SplitHostPort(ep)
	if err != nil {
		return "", err
	}
	if ip := net.ParseIP(host); ip != nil {
		return ep, nil
	}
	addrs, err := net.DefaultResolver.LookupIPAddr(context.Background(), host)
	if err != nil || len(addrs) == 0 {
		return "", fmt.Errorf("resolve endpoint %s: %w", host, err)
	}
	return net.JoinHostPort(addrs[0].IP.String(), port), nil
}
