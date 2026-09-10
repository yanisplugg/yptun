package main

// YPtun additions to the OpenFlux client, kept out of main.go so a re-vendor only has to redo the few
// hook lines there (see yptunClientSetup).

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"universal-bypass-tool/socks5"
	"universal-bypass-tool/tunnel"
)

// Secrets come from the environment rather than argv: a command line is readable by other processes
// of the same user (Windows) and shows up in diagnostics.
const (
	envMaxToken  = "OPENFLUX_MAX_TOKEN"
	envSocksUser = "OPENFLUX_SOCKS_USER"
	envSocksPass = "OPENFLUX_SOCKS_PASS"
)

func envOr(value, key string) string {
	if value != "" {
		return value
	}
	return os.Getenv(key)
}

// exitWhenStdinCloses ends the process once the parent's end of the stdin pipe closes, i.e. the app
// that started us is gone. Otherwise an orphaned client keeps the SOCKS port (and the MAX call) busy.
func exitWhenStdinCloses() {
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		os.Exit(0)
	}()
}

// yptunClientSetup builds the SOCKS5 server the host expects: authenticated with the session
// credentials, and resolving domains through the tunnel.
func yptunClientSetup(addr string, tun *tunnel.TCPTunnel, dnsServer string) *socks5.SOCKS5Server {
	server := socks5.NewSOCKS5Server(addr, newTunnelResolvingDialer(tun, dnsServer))
	server.SetAuth(os.Getenv(envSocksUser), os.Getenv(envSocksPass))
	return server
}

// tunnelResolvingDialer resolves domain CONNECTs THROUGH the tunnel (DNS over TCP to dnsServer, dialled
// via the exit node) instead of the device resolver. The device DNS leaks every name to the ISP and, in
// Russia, answers blocked sites with spoofed addresses — the very sites the tunnel exists for.
type tunnelResolvingDialer struct {
	tun      *tunnel.TCPTunnel
	resolver *net.Resolver

	mu    sync.Mutex
	cache map[string]cachedIP
}

type cachedIP struct {
	ip      string
	expires time.Time
}

// dnsCacheTTL: every lookup is a round trip through a slow transport, and apps reconnect to the same
// hosts constantly, so a short cache saves most of them.
const dnsCacheTTL = 5 * time.Minute

func newTunnelResolvingDialer(tun *tunnel.TCPTunnel, dnsServer string) socks5.Dialer {
	dnsServer = strings.TrimSpace(dnsServer)
	if dnsServer == "" {
		return tun // upstream behaviour: system resolver
	}
	if !strings.Contains(dnsServer, ":") {
		dnsServer = net.JoinHostPort(dnsServer, "53")
	}
	return &tunnelResolvingDialer{
		tun: tun,
		resolver: &net.Resolver{
			PreferGo: true,
			// A stream conn makes the Go resolver use DNS-over-TCP framing; the tunnel carries TCP only.
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				return tun.DialTCP(dnsServer)
			},
		},
		cache: make(map[string]cachedIP),
	}
}

func (d *tunnelResolvingDialer) DialTCP(address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	if net.ParseIP(host) == nil {
		ip, err := d.lookup(host)
		if err != nil {
			return nil, err
		}
		address = net.JoinHostPort(ip, port)
	}
	return d.tun.DialTCP(address)
}

func (d *tunnelResolvingDialer) lookup(host string) (string, error) {
	now := time.Now()
	d.mu.Lock()
	if c, ok := d.cache[host]; ok && now.Before(c.expires) {
		d.mu.Unlock()
		return c.ip, nil
	}
	d.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	ips, err := d.resolver.LookupIP(ctx, "ip4", host)
	if err != nil {
		return "", fmt.Errorf("resolve %s through the tunnel: %w", host, err)
	}
	if len(ips) == 0 {
		return "", fmt.Errorf("resolve %s through the tunnel: no IPv4 address", host)
	}
	ip := ips[0].String()
	d.mu.Lock()
	d.cache[host] = cachedIP{ip: ip, expires: now.Add(dnsCacheTTL)}
	d.mu.Unlock()
	return ip, nil
}
