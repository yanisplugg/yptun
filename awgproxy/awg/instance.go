package awg

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

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

	splitMu       sync.Mutex
	splitPrefixes []netip.Prefix
}

// NewInstance creates an idle AmneziaWG proxy instance (logs discarded until SetLogWriter).
func NewInstance() *Instance { return &Instance{logSink: io.Discard} }

// SetSplitCIDRs turns this instance into a SPLIT SOCKS: only destinations inside one of the given
// CIDRs (comma-separated, e.g. Telegram's ranges) ride the WARP tunnel; everything else is dialed
// DIRECTLY (real network). Empty (default) = route everything through WARP. Call before Start. This is
// what makes the Telegram-over-WARP proxy carry ONLY Telegram and leave the rest direct, while the
// main VPN's AmneziaWG path (the package singleton) stays a normal full proxy.
func (i *Instance) SetSplitCIDRs(csv string) {
	var prefixes []netip.Prefix
	for _, p := range strings.Split(csv, ",") {
		if p = strings.TrimSpace(p); p == "" {
			continue
		}
		if pre, err := netip.ParsePrefix(p); err == nil {
			prefixes = append(prefixes, pre)
		}
	}
	i.splitMu.Lock()
	i.splitPrefixes = prefixes
	i.splitMu.Unlock()
}

// viaWarp reports whether a destination host should be tunneled through WARP (true) or dialed direct
// (false), per the configured split CIDRs. With no split set, everything goes through WARP. For a
// domain target it resolves via the real network and matches any resolved IP against the split set.
func (i *Instance) viaWarp(host string) bool {
	i.splitMu.Lock()
	prefixes := i.splitPrefixes
	i.splitMu.Unlock()
	if len(prefixes) == 0 {
		return true
	}
	contains := func(addr netip.Addr) bool {
		a := addr.Unmap()
		for _, p := range prefixes {
			if p.Contains(a) {
				return true
			}
		}
		return false
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return contains(addr)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return false // can't resolve → treat as non-Telegram, go direct
	}
	for _, ip := range ips {
		if contains(ip) {
			return true
		}
	}
	return false
}

// serveSocks is the per-instance SOCKS5 server. Unlike the package serveSocks (always-WARP, used by
// the singleton), it consults viaWarp per connection so split instances tunnel only the matched CIDRs.
func (i *Instance) serveSocks(ln net.Listener, tnet *netstack.Net) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go i.handleSocks(c, tnet)
	}
}

func (i *Instance) handleSocks(client net.Conn, tnet *netstack.Net) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))

	br := make([]byte, 2)
	if _, err := io.ReadFull(client, br); err != nil || br[0] != 0x05 {
		return
	}
	methods := make([]byte, int(br[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	head := make([]byte, 4)
	if _, err := io.ReadFull(client, head); err != nil || head[0] != 0x05 {
		return
	}
	host, err := readSocksAddr(client, head[3])
	if err != nil {
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	target := net.JoinHostPort(host, strconv.Itoa(port))

	switch head[1] {
	case 0x01: // CONNECT
		i.socksTCPConnect(client, tnet, host, target)
	case 0x03: // UDP ASSOCIATE
		i.socksUDPAssociate(client, tnet)
	default:
		_ = writeSocksReply(client, 0x07)
	}
}

func (i *Instance) socksTCPConnect(client net.Conn, tnet *netstack.Net, host, target string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	var remote net.Conn
	var err error
	if i.viaWarp(host) {
		remote, err = tnet.DialContext(ctx, "tcp", target)
	} else {
		var d net.Dialer
		remote, err = d.DialContext(ctx, "tcp", target)
	}
	if err != nil {
		log.New(i.logSink, "", 0).Printf("socks connect to %s failed: %v", target, err)
		_ = writeSocksReply(client, 0x05)
		return
	}
	defer remote.Close()
	if err := writeSocksReply(client, 0x00); err != nil {
		return
	}
	_ = client.SetDeadline(time.Time{})
	pipe(client, remote)
}

// socksUDPAssociate mirrors the package handler but picks WARP vs direct per target (Telegram VoIP
// rides WARP; anything else goes direct).
func (i *Instance) socksUDPAssociate(client net.Conn, tnet *netstack.Net) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSocksReply(client, 0x01)
		return
	}
	defer relay.Close()

	bound := relay.LocalAddr().(*net.UDPAddr)
	rep := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(rep[8:], uint16(bound.Port))
	if _, err := client.Write(rep); err != nil {
		return
	}

	conns := make(map[string]net.Conn)
	defer func() {
		for _, c := range conns {
			_ = c.Close()
		}
	}()
	go func() { io.Copy(io.Discard, client); relay.Close() }()

	buf := make([]byte, 64*1024)
	for {
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		dstHost, dstPort, payload, ok := parseUDPRequest(buf[:n])
		if !ok {
			continue
		}
		target := net.JoinHostPort(dstHost, strconv.Itoa(dstPort))
		uc := conns[target]
		if uc == nil {
			var rc net.Conn
			var derr error
			if i.viaWarp(dstHost) {
				rc, derr = tnet.Dial("udp", target)
			} else {
				rc, derr = net.Dial("udp", target)
			}
			if derr != nil {
				continue
			}
			uc = rc
			conns[target] = uc
			go udpReturn(relay, uc, from, dstHost, dstPort)
		}
		_, _ = uc.Write(payload)
	}
}

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
	go i.serveSocks(ln, tnet)
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
