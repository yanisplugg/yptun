package client

import (
	"context"
	"crypto/subtle"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	socksVersion               = 5
	socksAddrIPv4              = 1
	socksAddrDomain            = 3
	socksAddrIPv6              = 4
	socksRepSuccess            = 0
	socksRepNetworkUnreachable = 3
	socksRepHostUnreachable    = 4
)

const (
	// socksNegotiationTimeout bounds everything before the target is known.
	// Until then there is nothing legitimate to wait for, and the listener is
	// allowed to be non-loopback when credentials are configured, so a peer
	// that connects and stays silent must not pin a goroutine and an fd.
	socksNegotiationTimeout = 30 * time.Second

	// maxSocksConns caps concurrent SOCKS clients. Each one costs a
	// goroutine, an fd and a tunnel stream.
	maxSocksConns = 512

	// maxParkedRequests caps the requests waiting for a session that is not
	// there. Waiting is right during a rebuild: the app's connections survive
	// a handover that way. But with a tun2socks in front each parked request
	// is a session over there too, with its own stack outside the Go heap,
	// and a phone whose apps retry through a network gap parks twenty-five a
	// second; the extension died at six hundred (olcbox#37). Past the cap the
	// answer is an immediate "network unreachable", which apps take as a
	// reason to back off rather than to try again at once.
	maxParkedRequests = 64

	// defaultSessionReadyTimeout is how long a request waits for the
	// session: long enough for a full liveness rebuild.
	defaultSessionReadyTimeout = 60 * time.Second

	// acceptRetryDelay is the initial backoff after a failed Accept.
	// Retrying immediately turns a temporary fd exhaustion into a hot loop
	// that floods the log.
	acceptRetryDelay    = 10 * time.Millisecond
	maxAcceptRetryDelay = time.Second
)

func (c *Client) acceptLoop(ctx context.Context, listener net.Listener) {
	delay := time.Duration(0)
	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			delay = nextAcceptDelay(delay)
			logger.Warnf("Accept error (retry in %s): %v", delay, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
			}
			continue
		}
		delay = 0
		if !c.registerSocksConn(conn) {
			_ = conn.Close()
			continue
		}
		c.goTracked(func() {
			defer c.unregisterSocksConn(conn)
			c.handleSocks5(ctx, conn)
		})
	}
}

func nextAcceptDelay(current time.Duration) time.Duration {
	if current <= 0 {
		return acceptRetryDelay
	}
	if next := current * 2; next < maxAcceptRetryDelay {
		return next
	}
	return maxAcceptRetryDelay
}

func (c *Client) handleSocks5(ctx context.Context, conn net.Conn) {
	defer func() { _ = conn.Close() }()
	_ = conn.SetDeadline(time.Now().Add(socksNegotiationTimeout))
	if err := c.socks5Handshake(conn); err != nil {
		return
	}
	req, err := c.readSocks5Request(conn)
	if err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	if req.cmd == socksCmdUDPAssociate {
		c.handleUDPAssociate(ctx, conn, req)
		return
	}
	c.serveConnect(ctx, conn, req)
}

// tunnelWhenReady carries a CONNECT through the tunnel once the session is
// up, waiting for it when it is not — as one of at most maxParkedRequests.
func (c *Client) tunnelWhenReady(ctx context.Context, conn net.Conn, job connectJob) {
	prep := c.prepareTunnel(ctx, job)
	if prep.stream == nil {
		job.fail(conn, prep.reply)
		return
	}
	defer func() { _ = prep.stream.Close() }()
	c.pumpTunnel(ctx, conn, prep.stream, job)
}

// prepareTunnel waits for the session, opens a stream and has the exit dial
// the target, without touching the client's own connection: the caller
// decides whether this tunnel is the one that carries the CONNECT.
func (c *Client) prepareTunnel(ctx context.Context, job connectJob) tunnelPrep {
	// Refused before parking: with the exit known to have no IPv6 route a
	// stream for an IPv6 literal can only come back unreachable, after a
	// round trip, and a dual-stack host sends one per connection.
	if isIPv6Literal(job.host) && c.noIPv6Route() {
		return tunnelPrep{reply: replyHostUnreachable(job.host)}
	}
	readyCtx, cancel := context.WithTimeout(ctx, c.readyTimeout())
	defer cancel()
	parked := false
	defer func() {
		if parked {
			c.unpark()
		}
	}()
	for {
		// The ready channel is taken in the same critical section as the
		// state it describes. Sampling it afterwards subscribes to the next
		// generation and misses the signal that just fired, which stalls the
		// request for the full timeout while the tunnel is up.
		session, sessionID, ready := c.sessionSnapshot()
		if session != nil && !session.IsClosed() && sessionID != "" {
			if parked {
				parked = false
				c.unpark()
			}
			return c.openTunnel(session, job)
		}
		if !parked {
			if !c.park() {
				return tunnelPrep{reply: replyNetworkUnreachable(job.host)}
			}
			parked = true
		}
		select {
		case <-readyCtx.Done():
			return tunnelPrep{reply: replyHostUnreachable(job.host)}
		case <-ready:
		}
	}
}

// readyTimeout is how long a request waits for the session.
func (c *Client) readyTimeout() time.Duration {
	if c.sessionReadyTimeout > 0 {
		return c.sessionReadyTimeout
	}
	return defaultSessionReadyTimeout
}

// park claims a slot among the requests waiting for the session, or reports
// that they are all taken.
func (c *Client) park() bool {
	for {
		n := c.parked.Load()
		if n >= maxParkedRequests {
			if c.parkedSaturated.CompareAndSwap(false, true) {
				logger.Warnf("socks: %d requests parked on a missing session; refusing more until it is back", n)
			}
			return false
		}
		if c.parked.CompareAndSwap(n, n+1) {
			return true
		}
	}
}

// unpark gives a slot back. The warning re-arms once the pool has drained
// halfway, so a session that flaps does not write a line per request.
func (c *Client) unpark() {
	if c.parked.Add(-1) < maxParkedRequests/2 {
		c.parkedSaturated.Store(false)
	}
}

func (c *Client) socks5Handshake(conn net.Conn) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return fmt.Errorf("read socks5 header: %w", err)
	}
	if header[0] != socksVersion {
		return fmt.Errorf("%w: %d", ErrInvalidSOCKSVersion, header[0])
	}
	methods := make([]byte, header[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return fmt.Errorf("read socks5 methods: %w", err)
	}
	if c.socksUser != "" {
		if _, err := conn.Write([]byte{socksVersion, 2}); err != nil {
			return fmt.Errorf("write socks5 auth method: %w", err)
		}
		return c.socks5UserPassAuth(conn)
	}
	if _, err := conn.Write([]byte{socksVersion, 0}); err != nil {
		return fmt.Errorf("write socks5 auth: %w", err)
	}
	return nil
}

func (c *Client) socks5UserPassAuth(conn net.Conn) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return fmt.Errorf("read socks5 auth header: %w", err)
	}
	if header[0] != 1 {
		return fmt.Errorf("%w: expected auth version 1, got %d", ErrInvalidSOCKSVersion, header[0])
	}
	user := make([]byte, header[1])
	if _, err := io.ReadFull(conn, user); err != nil {
		return fmt.Errorf("read socks5 username: %w", err)
	}
	passwordLength := make([]byte, 1)
	if _, err := io.ReadFull(conn, passwordLength); err != nil {
		return fmt.Errorf("read socks5 plen: %w", err)
	}
	password := make([]byte, passwordLength[0])
	if _, err := io.ReadFull(conn, password); err != nil {
		return fmt.Errorf("read socks5 password: %w", err)
	}
	// Both comparisons always run: short-circuiting on the username leaks
	// which half failed through timing.
	userOK := subtle.ConstantTimeCompare(user, []byte(c.socksUser)) == 1
	passOK := subtle.ConstantTimeCompare(password, []byte(c.socksPass)) == 1
	if !userOK || !passOK {
		_, _ = conn.Write([]byte{1, 1})
		return ErrSOCKSAuthFailed
	}
	if _, err := conn.Write([]byte{1, 0}); err != nil {
		return fmt.Errorf("write socks5 auth success: %w", err)
	}
	return nil
}

// socks5Request reads a CONNECT request; any other command is refused.
func (c *Client) socks5Request(conn net.Conn) (string, int, error) {
	req, err := c.readSocks5Request(conn)
	if err != nil {
		return "", 0, err
	}
	if req.cmd != socksCmdConnect {
		return "", 0, fmt.Errorf("%w: %d", ErrUnsupportedSOCKSCommand, req.cmd)
	}
	return req.addr, req.port, nil
}

// readSocks5Request reads one request: CONNECT or UDP ASSOCIATE.
func (c *Client) readSocks5Request(conn net.Conn) (socksRequest, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return socksRequest{}, fmt.Errorf("read socks5 request: %w", err)
	}
	if header[1] != socksCmdConnect && header[1] != socksCmdUDPAssociate {
		return socksRequest{}, fmt.Errorf("%w: %d", ErrUnsupportedSOCKSCommand, header[1])
	}
	addr, err := c.readSocks5Addr(conn, header[3])
	if err != nil {
		return socksRequest{}, err
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return socksRequest{}, fmt.Errorf("read socks5 port: %w", err)
	}
	return socksRequest{cmd: header[1], addr: addr, port: int(binary.BigEndian.Uint16(portBytes))}, nil
}

func (c *Client) readSocks5Addr(conn net.Conn, addrType byte) (string, error) {
	switch addrType {
	case socksAddrIPv4:
		return readIP(conn, net.IPv4len, "ipv4")
	case socksAddrIPv6:
		return readIP(conn, net.IPv6len, "ipv6")
	case socksAddrDomain:
		length := make([]byte, 1)
		if _, err := io.ReadFull(conn, length); err != nil {
			return "", fmt.Errorf("read socks5 domain len: %w", err)
		}
		if length[0] == 0 {
			return "", ErrEmptySOCKSDomain
		}
		buffer := make([]byte, length[0])
		if _, err := io.ReadFull(conn, buffer); err != nil {
			return "", fmt.Errorf("read socks5 domain: %w", err)
		}
		return string(buffer), nil
	default:
		return "", fmt.Errorf("%w: %d", ErrUnsupportedAddressType, addrType)
	}
}

func readIP(conn net.Conn, length int, label string) (string, error) {
	buffer := make([]byte, length)
	if _, err := io.ReadFull(conn, buffer); err != nil {
		return "", fmt.Errorf("read socks5 %s: %w", label, err)
	}
	return net.IP(buffer).String(), nil
}

func socks5Reply(rep byte, target string) []byte {
	addrLen := net.IPv4len
	addrType := byte(socksAddrIPv4)
	if ip := net.ParseIP(target); ip != nil && ip.To4() == nil {
		addrLen = net.IPv6len
		addrType = socksAddrIPv6
	}
	reply := make([]byte, 4+addrLen+2)
	reply[0], reply[1], reply[3] = socksVersion, rep, addrType
	return reply
}

func replySuccess(target string) []byte {
	return socks5Reply(socksRepSuccess, target)
}

func replyHostUnreachable(target string) []byte {
	return socks5Reply(socksRepHostUnreachable, target)
}

// replyNetworkUnreachable is the answer for a request this process will not
// even wait on: nothing is wrong with the host, there is no way out.
func replyNetworkUnreachable(target string) []byte {
	return socks5Reply(socksRepNetworkUnreachable, target)
}
