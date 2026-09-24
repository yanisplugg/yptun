package ofclient

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"openflux/socks5"
	"openflux/transport"
	"openflux/transport/cupsonline"
	"openflux/transport/mailru"
	"openflux/transport/oneme"
	"openflux/transport/yandex"
	"openflux/tunnel"
	"openflux/utils"
)

type Config struct {
	Transport string
	DocURL    string
	MaxToken  string
	MaxUID    string
	SocksAddr string
	DNSServer string
	SocksUser string
	SocksPass string
	Debug     bool
}

type Client struct {
	cfg     Config
	mu      sync.Mutex
	running atomic.Bool
	lastErr atomic.Pointer[string]

	server *socks5.SOCKS5Server
	tun    *tunnel.TCPTunnel
	trans  transport.Transport
}

func NewClient(cfg Config) (*Client, error) {
	if cfg.SocksAddr == "" {
		cfg.SocksAddr = "127.0.0.1:1080"
	}
	if cfg.Transport == "" {
		cfg.Transport = "yandex"
	}
	return &Client{cfg: cfg}, nil
}

func (c *Client) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.running.Load() {
		return errors.New("openflux client is already running")
	}

	if c.cfg.Debug {
		utils.SetVerbose(true)
	}

	transportType := c.cfg.Transport
	if strings.Contains(c.cfg.DocURL, "cloud.mail.ru") {
		transportType = "mailru"
	} else if strings.Contains(c.cfg.DocURL, "cups.online") {
		transportType = "cupsonline"
	}

	var inner transport.Transport
	switch transportType {
	case "mailru":
		cfg := transport.DefaultConfig()
		inner = mailru.NewMailruDocsTransport(c.cfg.DocURL, cfg)
	case "cupsonline":
		cfg := transport.DefaultConfig()
		inner = cupsonline.NewCupsonlineTransport(c.cfg.DocURL, cfg, true)
	case "vyandex":
		cfg := transport.DefaultConfig()
		inner = yandex.NewYandexVolgaTransport(c.cfg.DocURL, cfg)
	case "yandex":
		cfg := transport.DefaultConfig()
		inner = yandex.NewYandexDocsTransport(c.cfg.DocURL, cfg)
	case "oneme":
		uidint, err := strconv.ParseInt(c.cfg.MaxUID, 10, 64)
		if err != nil {
			return fmt.Errorf("invalid maxUid: %w", err)
		}
		cfg := transport.DefaultConfig()
		inner = oneme.NewOneMeTransport(false, c.cfg.MaxToken, uidint, cfg)
	default:
		return fmt.Errorf("unknown transport type: %s", transportType)
	}

	trans := transport.NewBatchedTransport(inner)

	if err := trans.Start(); err != nil {
		c.setLastError(err.Error())
		return fmt.Errorf("start transport %s: %w", c.cfg.Transport, err)
	}

	// Wait up to 15 seconds for transport to become connected (e.g. documentOpen / auth result)
	// so tunnel settings and SOCKS5 are ready when the connection actually works.
	readyTimeout := time.After(15 * time.Second)
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()
	connected := false
	for !connected {
		select {
		case <-readyTimeout:
			utils.Debugf("[OFCLIENT] Transport handshake wait reached 15s limit, proceeding")
			connected = true
		case <-ticker.C:
			if inner.IsConnected() {
				utils.Debugf("[OFCLIENT] Transport connected and verified")
				connected = true
			}
		}
	}

	tun := tunnel.NewTCPTunnel(trans, false)
	dialer := NewTunnelResolvingDialer(tun, c.cfg.DNSServer)
	server := socks5.NewSOCKS5Server(c.cfg.SocksAddr, dialer)
	if c.cfg.SocksUser != "" || c.cfg.SocksPass != "" {
		server.SetAuth(c.cfg.SocksUser, c.cfg.SocksPass)
	}

	c.trans = trans
	c.tun = tun
	c.server = server
	c.running.Store(true)

	// Run SOCKS5 server in background
	errCh := make(chan error, 1)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				utils.Debugf("[OFCLIENT] SOCKS5 server panic recovered: %v", r)
				c.setLastError(fmt.Sprintf("panic: %v", r))
			}
			c.running.Store(false)
		}()

		if err := server.Start(); err != nil {
			c.setLastError(err.Error())
			select {
			case errCh <- err:
			default:
			}
			return
		}
	}()

	// Brief check to catch immediate bind failures
	select {
	case err := <-errCh:
		c.running.Store(false)
		_ = server.Close()
		_ = tun.Close()
		_ = trans.Stop()
		return fmt.Errorf("socks5 server start failed: %w", err)
	case <-time.After(50 * time.Millisecond):
		// Listener successfully established
		return nil
	}
}

func (c *Client) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.running.Load() {
		return
	}
	c.running.Store(false)

	if c.server != nil {
		_ = c.server.Close()
		c.server = nil
	}
	if c.tun != nil {
		_ = c.tun.Close()
		c.tun = nil
	}
	if c.trans != nil {
		_ = c.trans.Stop()
		c.trans = nil
	}
}

func (c *Client) IsRunning() bool {
	return c.running.Load()
}

func (c *Client) LastError() string {
	p := c.lastErr.Load()
	if p == nil {
		return ""
	}
	return *p
}

func (c *Client) setLastError(err string) {
	c.lastErr.Store(&err)
}

// Tunnel-resolving dialer
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

const dnsCacheTTL = 5 * time.Minute

func NewTunnelResolvingDialer(tun *tunnel.TCPTunnel, dnsServer string) socks5.Dialer {
	dnsServer = DnsServerAddr(dnsServer)
	if dnsServer == "" {
		return tun
	}
	return &tunnelResolvingDialer{
		tun: tun,
		resolver: &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				return tun.DialTCP(dnsServer)
			},
		},
		cache: make(map[string]cachedIP),
	}
}

func DnsServerAddr(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if _, _, err := net.SplitHostPort(value); err == nil {
		return value
	}
	return net.JoinHostPort(strings.Trim(value, "[]"), "53")
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
