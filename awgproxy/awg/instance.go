package awg

import (
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

// Instance is an INDEPENDENT AmneziaWG SOCKS proxy, separate from the package-level singleton
// (Start/Stop). It lets a SECOND AmneziaWG tunnel run concurrently with the main one — e.g. the
// always-on Telegram-over-WARP proxy alongside a main-VPN AmneziaWG transport — each with its own
// device, listener, logger and protector. Reuses the package config parser (parseConfig) and SOCKS
// server (serveSocks); only the lifecycle state is per-instance, so the proven global path is
// untouched. gomobile binds it as awg.Instance (+ awg.NewInstance()).
type Instance struct {
	mu       sync.Mutex
	running  atomic.Bool
	dev      *device.Device
	listener net.Listener
	logSink  io.Writer
	debug    atomic.Bool

	protMu sync.Mutex
	prot   Protector
}

// NewInstance creates an idle AmneziaWG proxy instance (logs discarded until SetLogWriter).
func NewInstance() *Instance { return &Instance{logSink: io.Discard} }

// SetLogWriter routes this instance's logs to w (nil → discard).
func (i *Instance) SetLogWriter(w LogWriter) {
	i.mu.Lock()
	defer i.mu.Unlock()
	if w == nil {
		i.logSink = io.Discard
		return
	}
	i.logSink = logBridge{w: w}
}

// SetDebug toggles verbose device logging for this instance.
func (i *Instance) SetDebug(enabled bool) { i.debug.Store(enabled) }

// SetProtector installs the socket protector used when the WG bind opens its UDP socket(s), so the
// WARP packets egress the real network rather than looping into an active system VPN tun. Per
// instance — clears with nil.
func (i *Instance) SetProtector(p Protector) {
	i.protMu.Lock()
	i.prot = p
	i.protMu.Unlock()
}

// IsRunning reports whether this instance's SOCKS proxy is active.
func (i *Instance) IsRunning() bool { return i.running.Load() }

func (i *Instance) protectBind(bind conn.Bind) {
	i.protMu.Lock()
	p := i.prot
	i.protMu.Unlock()
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

// Start brings up this instance's AmneziaWG tunnel from a wg-quick-style INI config and raises a
// SOCKS5 proxy on listenAddr (e.g. 127.0.0.1:10810). Returns an error for invalid config or if the
// listener can't bind.
func (i *Instance) Start(iniConfig, listenAddr string) error {
	i.mu.Lock()
	defer i.mu.Unlock()
	if i.running.Load() {
		return errors.New("awg instance already running")
	}
	if i.logSink == nil {
		i.logSink = io.Discard
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
	if i.debug.Load() {
		level = device.LogLevelVerbose
	}
	logger := device.NewLogger(level, "[awgtg] ")
	logger.Verbosef = func(format string, args ...any) { log.New(i.logSink, "", 0).Printf(format, args...) }
	logger.Errorf = func(format string, args ...any) { log.New(i.logSink, "", 0).Printf(format, args...) }

	bind := conn.NewDefaultBind()
	d := device.NewDevice(tunDev, bind, logger)
	if err := d.IpcSet(uapi); err != nil {
		d.Close()
		return fmt.Errorf("awg ipc: %w", err)
	}
	if err := d.Up(); err != nil {
		d.Close()
		return fmt.Errorf("awg up: %w", err)
	}
	i.protectBind(bind)

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		d.Close()
		return fmt.Errorf("awg socks listen %s: %w", listenAddr, err)
	}

	i.dev = d
	i.listener = ln
	i.running.Store(true)
	go serveSocks(ln, tnet)
	log.New(i.logSink, "", 0).Printf("AmneziaWG SOCKS up on %s", listenAddr)
	return nil
}

// Stop tears down this instance's SOCKS listener and AmneziaWG device.
func (i *Instance) Stop() {
	i.mu.Lock()
	defer i.mu.Unlock()
	if i.listener != nil {
		_ = i.listener.Close()
		i.listener = nil
	}
	if i.dev != nil {
		i.dev.Close()
		i.dev = nil
	}
	i.running.Store(false)
}
