package wdtt

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strings"

	"github.com/armon/go-socks5"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type netstackNameResolver struct {
	tnet *netstack.Net
}

func (r *netstackNameResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	addrs, err := r.tnet.LookupContextHost(ctx, name)
	if err != nil {
		return ctx, nil, err
	}
	// Только IPv4: туннель без IPv6-маршрутов.
	for _, a := range addrs {
		ip := net.ParseIP(a)
		if ip == nil {
			continue
		}
		if v4 := ip.To4(); v4 != nil {
			return ctx, v4, nil
		}
	}
	return ctx, nil, fmt.Errorf("no IPv4 addresses for %s", name)
}

// ipv4OnlyRule отклоняет CONNECT на IPv6 (клиент часто пробует AAAA первым).
type ipv4OnlyRule struct{}

func (ipv4OnlyRule) Allow(ctx context.Context, req *socks5.Request) (context.Context, bool) {
	if req == nil || req.DestAddr == nil {
		return ctx, true
	}
	ip := req.DestAddr.IP
	if ip != nil && ip.To4() == nil {
		return ctx, false
	}
	return ctx, true
}

type socksLogFilter struct{}

func (socksLogFilter) Write(p []byte) (n int, err error) {
	msg := string(p)
	if isBenignSocksNoise(msg) {
		return len(p), nil
	}
	log.Print(strings.TrimRight(msg, "\r\n"))
	return len(p), nil
}

func isBenignSocksNoise(msg string) bool {
	lower := strings.ToLower(msg)
	if strings.Contains(lower, "blocked by rules") {
		return true
	}
	if strings.Contains(lower, "ipv6") {
		return true
	}
	// Connect to [2001:…] / ::ffff:… / no route на v6
	if strings.Contains(msg, "[") && strings.Contains(msg, "]") && strings.Contains(msg, ":") {
		if strings.Contains(lower, "no route") || strings.Contains(lower, "connect to") {
			return true
		}
	}
	if strings.Contains(msg, "::") && (strings.Contains(lower, "no route") || strings.Contains(lower, "failed")) {
		return true
	}
	return false
}

func dialIPv4Only(tnet *netstack.Net) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, _, err := net.SplitHostPort(addr)
		if err == nil {
			if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
				return nil, fmt.Errorf("IPv6 skipped")
			}
		}
		return tnet.DialContext(ctx, network, addr)
	}
}

// runSocks5Server serves SOCKS5 TCP CONNECT via WireGuard netstack until ctx is done.
func runSocks5Server(ctx context.Context, listenAddr string, tnet *netstack.Net, authEnabled bool, username, password string) error {
	conf := &socks5.Config{
		Dial:     dialIPv4Only(tnet),
		Resolver: &netstackNameResolver{tnet: tnet},
		Rules:    ipv4OnlyRule{},
		Logger:   log.New(io.Writer(socksLogFilter{}), "", 0),
	}
	if authEnabled {
		conf.Credentials = socks5.StaticCredentials{username: password}
	}
	server, err := socks5.New(conf)
	if err != nil {
		return err
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", listenAddr, err)
	}

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	log.Printf("[SOCKS] listening %s", listenAddr)
	err = server.Serve(ln)
	if ctx.Err() != nil {
		return nil
	}
	if err != nil && !strings.Contains(err.Error(), "use of closed network connection") {
		return err
	}
	return nil
}
