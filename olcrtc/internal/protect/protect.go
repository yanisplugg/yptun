// Package protect provides functions to protect sockets from VPN routing.
package protect

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

const (
	defaultDialTimeout       = 10 * time.Second
	defaultKeepAlive         = 30 * time.Second
	defaultIdleConnTimeout   = 30 * time.Second
	defaultTLSHandshake      = 10 * time.Second
	defaultResponseHeader    = 10 * time.Second
	defaultWebSocketTimeout  = 10 * time.Second
	defaultHTTPClientTimeout = 30 * time.Second
	defaultStatusBodyLimit   = 1024
)

var (
	errRequestBodyNotReplayable = errors.New("request body is not replayable")
	sensitiveFieldRE            = regexp.MustCompile(
		`(?i)((?:access[_-]?token|room[_-]?token|token|credentials)"?\s*[:=]\s*"?)` +
			`[^",\s}]+`,
	)
	sensitiveBearerRE = regexp.MustCompile(`(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+`)
)

type protectorHolder struct {
	protect func(int) bool
}

//nolint:gochecknoglobals // Android VpnService socket protection is process-wide by kernel fd
var protector atomic.Pointer[protectorHolder]

// SetProtector sets the process-wide Android VpnService socket callback.
func SetProtector(protectFunc func(int) bool) {
	if protectFunc == nil {
		protector.Store(nil)
		return
	}
	protector.Store(&protectorHolder{protect: protectFunc})
}

// HasProtector reports whether Android socket protection is configured.
func HasProtector() bool {
	return protector.Load() != nil
}

func controlFunc(network, _ string, c syscall.RawConn) error {
	current := protector.Load()
	if current == nil {
		return nil
	}
	var err error
	controlErr := c.Control(func(fd uintptr) {
		if !current.protect(int(fd)) {
			err = &net.OpError{Op: "protect", Net: network, Err: net.ErrClosed}
		}
	})
	if controlErr != nil {
		return fmt.Errorf("control failed: %w", controlErr)
	}
	return err
}

// newDialer returns a net.Dialer that protects each new socket.
func newDialer() *net.Dialer {
	return newDialerWithResolver(nil)
}

// newDialerWithResolver returns a protected dialer using resolver for DNS.
func newDialerWithResolver(resolver *net.Resolver) *net.Dialer {
	return &net.Dialer{
		Timeout:   defaultDialTimeout,
		KeepAlive: defaultKeepAlive,
		Control:   controlFunc,
		Resolver:  resolver,
	}
}

// dnsProbeDomain is a stable, widely-resolvable name used to test whether a candidate UDP/53 resolver
// actually answers. A plain dial can't tell — a UDP "connect" succeeds even to a blackholed server —
// so we send a real query and check for a reply.
const dnsProbeDomain = "dns.google"

// NewResolver returns a local Go resolver that sends queries to dnsServer. dnsServer may be a single
// "ip:port" OR a comma-separated list tried in PREFERENCE order. On restrictive IPv4-only mobile
// networks the international resolvers (Cloudflare 1.1.1.1, Google 8.8.8.8) are frequently UDP/53-blocked
// while a RU resolver (Yandex 77.88.8.8) still answers — pinning a single dead server leaves olcRTC unable
// to resolve provider hostnames. So on first use we probe the candidates with a real query and stick to
// the first that responds.
func NewResolver(dnsServer string) *net.Resolver {
	servers := splitDNSServers(dnsServer)
	if len(servers) == 0 {
		return nil
	}

	dialServer := func(ctx context.Context, network, server string) (net.Conn, error) {
		dialer := net.Dialer{Timeout: 3 * time.Second, Control: controlFunc}
		return dialer.DialContext(ctx, network, server)
	}
	if len(servers) == 1 {
		return &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				return dialServer(ctx, network, servers[0])
			},
		}
	}

	var (
		probeOnce sync.Once
		picked    string
	)
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			probeOnce.Do(func() { picked = pickReachableDNS(servers, dialServer) })
			server := picked
			if server == "" {
				server = servers[0] // none probed clean — fall back to the first listed
			}
			return dialServer(ctx, network, server)
		},
	}
}

// splitDNSServers parses a single server or a comma-separated preference list.
func splitDNSServers(dnsServer string) []string {
	servers := make([]string, 0, 4)
	for _, s := range strings.Split(dnsServer, ",") {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, s)
		}
	}
	return servers
}

// pickReachableDNS returns the first server that answers a real probe query (over the same dialer the
// resolver will use), or "" when none answered (the caller then falls back to the first listed server).
func pickReachableDNS(servers []string, dial func(context.Context, string, string) (net.Conn, error)) string {
	for _, server := range servers {
		srv := server
		probe := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				return dial(ctx, network, srv)
			},
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, err := probe.LookupHost(ctx, dnsProbeDomain)
		cancel()
		if err == nil {
			return srv
		}
	}
	return ""
}

// newTLSConfig returns the shared TLS policy for provider HTTP/WebSocket clients.
func newTLSConfig() *tls.Config {
	return &tls.Config{MinVersion: tls.VersionTLS12}
}

// newHTTPTransport returns an HTTP transport using protected sockets and sane timeouts.
func newHTTPTransport(resolvers ...*net.Resolver) *http.Transport {
	dialer := newDialerWithResolver(firstResolver(resolvers))
	return &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer.DialContext,
		TLSClientConfig:       newTLSConfig(),
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       defaultIdleConnTimeout,
		TLSHandshakeTimeout:   defaultTLSHandshake,
		ResponseHeaderTimeout: defaultResponseHeader,
	}
}

// NewHTTPClient returns an http.Client using protected sockets with DNS retry.
func NewHTTPClient(resolvers ...*net.Resolver) *http.Client {
	return &http.Client{
		Transport: &retryTransport{base: newHTTPTransport(resolvers...)},
		Timeout:   defaultHTTPClientTimeout,
	}
}

// retryTransport retries requests on transient DNS/dial errors.
type retryTransport struct {
	base http.RoundTripper
}

func (t *retryTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	const maxRetries = 3
	var resp *http.Response
	var err error
	for i := range maxRetries {
		if waitErr := waitForRetry(req.Context(), i); waitErr != nil {
			return nil, waitErr
		}
		attempt, requestErr := requestForAttempt(req, i)
		if requestErr != nil {
			return resp, fmt.Errorf("prepare retry: %w", requestErr)
		}
		resp, err = t.base.RoundTrip(attempt)
		if err == nil || !isRetriableError(err) {
			if err != nil {
				return resp, fmt.Errorf("round trip: %w", err)
			}
			return resp, nil
		}
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
	}
	return resp, fmt.Errorf("round trip after %d retries: %w", maxRetries, err)
}

func waitForRetry(ctx context.Context, attempt int) error {
	if attempt == 0 {
		return nil
	}
	timer := time.NewTimer(time.Duration(attempt) * 500 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("retry wait: %w", ctx.Err())
	}
}

func requestForAttempt(req *http.Request, attempt int) (*http.Request, error) {
	if attempt == 0 {
		return req, nil
	}
	retry := req.Clone(req.Context())
	// ЛОКАЛЬНЫЙ ПАТЧ: http.NoBody is a NON-nil body that carries no GetBody, and every provider
	// API call is built as NewRequestWithContext(..., http.MethodGet, u, http.NoBody). Without
	// this case a bodyless GET was declared unreplayable, so the FIRST transient dial timeout
	// turned into a hard startup failure ("prepare retry: request body is not replayable") and
	// the real error was never seen. A bodyless request is always replayable.
	if req.Body == nil || req.Body == http.NoBody {
		return retry, nil
	}
	if req.GetBody == nil {
		return nil, errRequestBodyNotReplayable
	}
	body, err := req.GetBody()
	if err != nil {
		return nil, fmt.Errorf("recreate request body: %w", err)
	}
	retry.Body = body
	return retry, nil
}

func isRetriableError(err error) bool {
	if err == nil {
		return false
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return true
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) {
		return opErr.Timeout() || strings.Contains(opErr.Error(), "connection refused")
	}
	s := err.Error()
	return strings.Contains(s, "no such host") ||
		strings.Contains(s, "connection reset") ||
		strings.Contains(s, "i/o timeout")
}

// NewWebSocketDialer returns a WebSocket dialer using protected sockets and shared TLS policy.
func NewWebSocketDialer(handshakeTimeout time.Duration, resolvers ...*net.Resolver) websocket.Dialer {
	if handshakeTimeout <= 0 {
		handshakeTimeout = defaultWebSocketTimeout
	}
	return websocket.Dialer{
		NetDialContext:   newDialerWithResolver(firstResolver(resolvers)).DialContext,
		Proxy:            http.ProxyFromEnvironment,
		TLSClientConfig:  newTLSConfig(),
		HandshakeTimeout: handshakeTimeout,
	}
}

// StatusError formats an upstream HTTP error while bounding and redacting the body.
func StatusError(base error, resp *http.Response, limit int64) error {
	if limit <= 0 {
		limit = defaultStatusBodyLimit
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, limit))
	bodyText := redactSensitive(strings.TrimSpace(string(body)))
	if bodyText == "" {
		return fmt.Errorf("%w: status %d", base, resp.StatusCode)
	}
	return fmt.Errorf("%w: status %d: %s", base, resp.StatusCode, bodyText)
}

// redactSensitive removes common token-like values from provider error text.
func redactSensitive(text string) string {
	text = sensitiveBearerRE.ReplaceAllString(text, "${1}<redacted>")
	return sensitiveFieldRE.ReplaceAllString(text, "${1}<redacted>")
}

// ProxyDialer implements golang.org/x/net/proxy.Dialer for pion ICE.
type ProxyDialer struct {
	resolver *net.Resolver
}

// Dial connects to the address on the named network using a protected socket.
func (d *ProxyDialer) Dial(network, addr string) (net.Conn, error) {
	conn, err := newDialerWithResolver(d.resolver).Dial(network, addr)
	if err != nil {
		return nil, fmt.Errorf("dial failed: %w", err)
	}
	return conn, nil
}

// NewProxyDialer returns a proxy.Dialer that protects ICE sockets.
func NewProxyDialer(resolvers ...*net.Resolver) *ProxyDialer {
	return &ProxyDialer{resolver: firstResolver(resolvers)}
}

func firstResolver(resolvers []*net.Resolver) *net.Resolver {
	if len(resolvers) == 0 {
		return nil
	}
	return resolvers[0]
}
