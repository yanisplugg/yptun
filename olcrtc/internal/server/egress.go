package server

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"syscall"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// socksHandshakeTimeout bounds the upstream SOCKS5 negotiation. The proxy is
// not ours: without a deadline a hung or hostile one parks this goroutine and
// its file descriptor for the process lifetime, and shutdown waits on it.
const socksHandshakeTimeout = 15 * time.Second

var (
	// errBlockedTarget is a target the exit refuses to dial for a peer (see
	// blockedRanges), named as a literal or resolved from a name.
	errBlockedTarget = errors.New("blocked target")
	// errResolveTarget is a name the server's resolver could not answer for.
	errResolveTarget = errors.New("resolve target")
	// errNoTargetAddress is a name that resolved to no address to dial.
	errNoTargetAddress = errors.New("resolve target: no address")
)

// blockedRanges is every range an exit refuses a peer's TCP CONNECT or UDP
// flow into: the exit's own host (loopback, 0.0.0.0), its networks (private,
// link-local and cloud metadata, shared CGNAT space) and what no one can be
// reached at (multicast, reserved, documentation, benchmarking). It is the
// list Xray's freedom outbound refuses by default on the same origins
// (xray-core common/geodata, GetPrivateIPMatcher), so an olcRTC peer reaches
// no more of an exit than a VLESS or Hysteria user does.
//
// ai-generated: the table (egress hardening).
var blockedRanges = []netip.Prefix{ //nolint:gochecknoglobals // static lookup table, no state
	netip.MustParsePrefix("0.0.0.0/8"),       // this network; 0.0.0.0 dials the host itself
	netip.MustParsePrefix("10.0.0.0/8"),      // private
	netip.MustParsePrefix("100.64.0.0/10"),   // shared address space (CGNAT)
	netip.MustParsePrefix("127.0.0.0/8"),     // loopback
	netip.MustParsePrefix("169.254.0.0/16"),  // link-local, cloud metadata
	netip.MustParsePrefix("172.16.0.0/12"),   // private
	netip.MustParsePrefix("192.0.0.0/24"),    // IETF protocol assignments
	netip.MustParsePrefix("192.0.2.0/24"),    // documentation
	netip.MustParsePrefix("192.88.99.0/24"),  // 6to4 relay anycast
	netip.MustParsePrefix("192.168.0.0/16"),  // private
	netip.MustParsePrefix("198.18.0.0/15"),   // benchmarking
	netip.MustParsePrefix("198.51.100.0/24"), // documentation
	netip.MustParsePrefix("203.0.113.0/24"),  // documentation
	netip.MustParsePrefix("224.0.0.0/3"),     // multicast, reserved, broadcast
	netip.MustParsePrefix("::/127"),          // unspecified, loopback
	netip.MustParsePrefix("fc00::/7"),        // unique local
	netip.MustParsePrefix("fe80::/10"),       // link-local
	netip.MustParsePrefix("ff00::/8"),        // multicast
}

// blockedAddr reports whether an exit refuses addr as a peer's target. An
// IPv4-mapped IPv6 address is judged as the IPv4 address it reaches, and a
// zoned one, which can only name a link of this host, is refused.
//
// ai-generated: the whole function (egress hardening).
func blockedAddr(addr netip.Addr) bool {
	addr = addr.Unmap()
	if !addr.IsValid() || addr.Zone() != "" {
		return true
	}
	for _, prefix := range blockedRanges {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

// targetAddrs returns the addresses the exit may dial for host: the literal
// itself, or what the server's resolver answers for the name in network
// ("ip" or "ip4"). One refused address refuses the target, so a name cannot
// carry a private address in beside a public one. The caller dials exactly
// what it gets back, so nothing resolves the name again between this check
// and the dial.
//
// ai-generated: the whole function (egress hardening).
func (s *Server) targetAddrs(ctx context.Context, network, host string) ([]netip.Addr, error) {
	if addr, err := netip.ParseAddr(host); err == nil {
		return s.allowTargets([]netip.Addr{addr})
	}
	ips, err := s.lookup().LookupIP(ctx, network, host)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", errResolveTarget, err)
	}
	addrs := make([]netip.Addr, 0, len(ips))
	for _, ip := range ips {
		if addr, ok := netip.AddrFromSlice(ip); ok {
			addrs = append(addrs, addr)
		}
	}
	if len(addrs) == 0 {
		return nil, errNoTargetAddress
	}
	return s.allowTargets(addrs)
}

// allowTargets unmaps addrs in place, or refuses them all when one is an
// address the exit must not reach.
//
// ai-generated: the whole function (egress hardening).
func (s *Server) allowTargets(addrs []netip.Addr) ([]netip.Addr, error) {
	for i, addr := range addrs {
		if !s.unsafeAllowPrivateTargets && blockedAddr(addr) {
			return nil, errBlockedTarget
		}
		addrs[i] = addr.Unmap()
	}
	return addrs, nil
}

// lookup is the server's resolver, or the host's for a Server built without
// one. ai-generated: the whole function (egress hardening).
func (s *Server) lookup() protect.Lookup {
	if s.resolver != nil {
		return s.resolver
	}
	return net.DefaultResolver
}

// isLiteral reports whether host is an IP address rather than a name.
// ai-generated: the whole function (egress hardening).
func isLiteral(host string) bool {
	_, err := netip.ParseAddr(host)
	return err == nil
}

// dialFailure says why a dial failed in words that carry no destination: a
// dial error quotes the address and a lookup error the name, and no line
// above debug may quote either.
//
// ai-generated: the whole function (egress hardening).
func dialFailure(err error) string {
	var dnsErr *net.DNSError
	switch {
	case errors.Is(err, errBlockedTarget):
		return errBlockedTarget.Error()
	case errors.Is(err, ErrInvalidTarget):
		return "invalid target"
	case errors.As(err, &dnsErr) && dnsErr.IsNotFound:
		return "no such host"
	case errors.Is(err, errNoTargetAddress), errors.Is(err, protect.ErrNoAddresses):
		return "no address"
	case errors.Is(err, errResolveTarget):
		return "lookup failed"
	case errors.Is(err, ErrSocks5ConnectFailed), errors.Is(err, ErrSocks5AuthFailed):
		return "refused by the upstream proxy"
	case errors.Is(err, context.Canceled):
		return "canceled"
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, os.ErrDeadlineExceeded):
		return "timeout"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "connection refused"
	case errors.Is(err, syscall.ENETUNREACH), errors.Is(err, syscall.EHOSTUNREACH):
		return "no route"
	default:
		return "failed"
	}
}

// ConnectRequest asks the server to establish a target connection.
type ConnectRequest struct {
	Cmd  string `json:"cmd"`
	Addr string `json:"addr"`
	Port int    `json:"port"`
}

// validate rejects targets that would resolve to something the peer did not
// ask for. An empty host is the important one: net.JoinHostPort("", "80")
// yields ":80", which Go dials as the local machine, so a malformed request
// would turn the exit node into a proxy onto its own loopback.
func (r ConnectRequest) validate() error {
	if r.Addr == "" {
		return fmt.Errorf("%w: empty host", ErrInvalidTarget)
	}
	if r.Port <= 0 || r.Port > 65535 {
		return fmt.Errorf("%w: port %d", ErrInvalidTarget, r.Port)
	}
	return nil
}

func (s *Server) dispatch(ctx context.Context, stream *smux.Stream, request ConnectRequest, sessionID string) {
	// Where a peer connects is logged at debug only: an exit's log is no
	// record of its users' destinations. A failure says why, not where.
	// ai-generated: the log levels and the failure line (egress hardening).
	addr := net.JoinHostPort(request.Addr, strconv.Itoa(request.Port))
	logger.Debugf("sid=%d connect %s", stream.ID(), addr)
	started := time.Now()
	conn, err := s.dial(ctx, request)
	elapsed := time.Since(started)
	if err != nil {
		logger.Infof("sid=%d dial failed (%v): %s", stream.ID(), elapsed, dialFailure(err))
		logger.Debugf("sid=%d dial %s failed: %v", stream.ID(), addr, err)
		_, _ = stream.Write([]byte{tunnelcore.ConnectAckHostUnreachable})
		return
	}
	defer func() { _ = conn.Close() }()
	logger.Debugf("sid=%d connected %s in %v", stream.ID(), addr, elapsed)
	if _, err := stream.Write([]byte{tunnelcore.ConnectAckOK}); err != nil {
		return
	}
	counts, copyErr := tunnelcore.CopyBidirectional(ctx, stream, conn)
	if errors.Is(copyErr, tunnelcore.ErrHalfOpenIdle) {
		logger.Debugf("sid=%d %s closed: half-open and silent for %s", stream.ID(), addr, tunnelcore.HalfOpenGrace)
	}
	s.meter.add(sessionID, counts.LeftToRight, counts.RightToLeft)
	if s.onTraffic != nil {
		s.onTraffic(sessionID, addr, counts.LeftToRight, counts.RightToLeft)
	}
}

func (s *Server) dial(ctx context.Context, request ConnectRequest) (net.Conn, error) {
	if err := request.validate(); err != nil {
		return nil, err
	}
	if s.socksProxyAddr == "" {
		return s.dialDirect(ctx, request)
	}
	// Behind an upstream proxy a name goes out as it is, for the proxy to
	// resolve under its own policy (Tor, WARP), as on the UDP path: only a
	// literal can be judged here. ai-generated: this check (egress hardening).
	if isLiteral(request.Addr) {
		if _, err := s.targetAddrs(ctx, "ip", request.Addr); err != nil {
			return nil, err
		}
	}
	dialer := protect.NewDialer(s.resolver)
	proxyAddr := net.JoinHostPort(s.socksProxyAddr, strconv.Itoa(s.socksProxyPort))
	// "tcp", not "tcp4": the proxy is operator configuration and may be an
	// IPv6 literal; the target dial keeps upstream's tcp4.
	conn, err := dialer.DialContext(ctx, "tcp", proxyAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to dial proxy: %w", err)
	}
	if err := s.socks5Connect(conn, request.Addr, request.Port); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return conn, nil
}

// dialDirect resolves the target once, through the egress policy, and dials
// the addresses that passed, in order, as literals: the dial resolves
// nothing, so a second answer for the name cannot reach past the check.
// Upstream's IPv4-only egress (tcp4) is kept.
//
// ai-generated: the whole function (egress hardening).
func (s *Server) dialDirect(ctx context.Context, request ConnectRequest) (net.Conn, error) {
	addrs, err := s.targetAddrs(ctx, "ip4", request.Addr)
	if err != nil {
		return nil, err
	}
	dial := s.dialTarget
	if dial == nil {
		dial = protect.NewDialer(s.resolver).DialContext
	}
	port := strconv.Itoa(request.Port)
	var firstErr error
	for _, addr := range addrs {
		conn, err := dial(ctx, "tcp4", net.JoinHostPort(addr.String(), port))
		if err == nil {
			return conn, nil
		}
		if firstErr == nil {
			firstErr = err
		}
		if ctx.Err() != nil {
			break
		}
	}
	return nil, fmt.Errorf("dial failed: %w", firstErr)
}

func (s *Server) socks5Connect(conn net.Conn, targetAddr string, targetPort int) error {
	_ = conn.SetDeadline(time.Now().Add(socksHandshakeTimeout))
	defer func() { _ = conn.SetDeadline(time.Time{}) }()
	if err := s.socks5Authenticate(conn); err != nil {
		return err
	}
	if _, err := conn.Write(socks5ConnectRequest(targetAddr, targetPort)); err != nil {
		return fmt.Errorf("failed to write socks5 connect req: %w", err)
	}
	return readSocks5ConnectReply(conn)
}

func socks5ConnectRequest(targetAddr string, targetPort int) []byte {
	request := make([]byte, 0, 4+net.IPv6len+2)
	request = append(request, 5, 1, 0)
	ip := net.ParseIP(targetAddr)
	switch {
	case ip != nil && ip.To4() != nil:
		request = append(request, 1)
		request = append(request, ip.To4()...)
	case ip != nil:
		request = append(request, 4)
		request = append(request, ip.To16()...)
	default:
		if len(targetAddr) > 255 {
			targetAddr = targetAddr[:255]
		}
		request = append(request, 3, byte(len(targetAddr))) //nolint:gosec // length is clamped above
		request = append(request, targetAddr...)
	}
	return append(request, byte(targetPort>>8), byte(targetPort)) //nolint:gosec // wire encoding preserves prior behavior
}

func readSocks5ConnectReply(conn net.Conn) error {
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return fmt.Errorf("failed to read socks5 connect resp: %w", err)
	}
	addrLen, err := socks5ReplyAddrLen(conn, header[3])
	if err != nil {
		return err
	}
	if _, err := io.ReadFull(conn, make([]byte, addrLen+2)); err != nil {
		return fmt.Errorf("failed to read socks5 connect resp address: %w", err)
	}
	if header[0] != 5 || header[1] != 0 {
		return fmt.Errorf("%w: %d", ErrSocks5ConnectFailed, header[1])
	}
	return nil
}

func socks5ReplyAddrLen(conn net.Conn, addrType byte) (int, error) {
	switch addrType {
	case 1:
		return net.IPv4len, nil
	case 4:
		return net.IPv6len, nil
	case 3:
		length := make([]byte, 1)
		if _, err := io.ReadFull(conn, length); err != nil {
			return 0, fmt.Errorf("failed to read socks5 connect resp domain length: %w", err)
		}
		return int(length[0]), nil
	default:
		return 0, fmt.Errorf("%w: address type %d", ErrSocks5ConnectFailed, addrType)
	}
}

func (s *Server) socks5Authenticate(conn net.Conn) error {
	method := byte(0)
	if s.socksProxyUser != "" {
		method = 2
	}
	if _, err := conn.Write([]byte{5, 1, method}); err != nil {
		return fmt.Errorf("failed to write socks5 auth: %w", err)
	}
	response := make([]byte, 2)
	if _, err := io.ReadFull(conn, response); err != nil {
		return fmt.Errorf("failed to read socks5 auth resp: %w", err)
	}
	if response[0] != 5 {
		return ErrSocks5AuthFailed
	}
	switch response[1] {
	case 0:
		if s.socksProxyUser != "" {
			return ErrSocks5AuthFailed
		}
		return nil
	case 2:
		return s.socks5SendCredentials(conn)
	default:
		return ErrSocks5AuthFailed
	}
}

func (s *Server) socks5SendCredentials(conn net.Conn) error {
	user := s.socksProxyUser
	password := s.socksProxyPass
	if len(user) > 255 {
		user = user[:255]
	}
	if len(password) > 255 {
		password = password[:255]
	}
	message := make([]byte, 0, 3+len(user)+len(password))
	message = append(message, 1, byte(len(user))) //nolint:gosec // length is clamped above
	message = append(message, user...)
	message = append(message, byte(len(password))) //nolint:gosec // length is clamped above
	message = append(message, password...)
	if _, err := conn.Write(message); err != nil {
		return fmt.Errorf("failed to write socks5 credentials: %w", err)
	}
	response := make([]byte, 2)
	if _, err := io.ReadFull(conn, response); err != nil {
		return fmt.Errorf("failed to read socks5 credentials resp: %w", err)
	}
	if response[1] != 0 {
		return ErrSocks5AuthFailed
	}
	return nil
}
