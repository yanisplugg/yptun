package client

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/sniff"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// Direct connections (olcbox#28).
//
// With rules configured, a CONNECT whose target they name is dialed from this
// process over a protected socket - the same pin the provider's own sockets
// get - instead of a tunnel stream. A target given as an address the rules do
// not list may still be a name they do: a tun2socks in front of the client
// hands over addresses only. So the SOCKS reply goes out first and the
// client's first bytes are read for a TLS server name or an HTTP Host, and
// the connection goes where that name says, the bytes replayed into whichever
// outbound wins. A client that stays silent - a protocol where the server
// speaks first - takes the tunnel after sniffTimeout.
//
// ai-generated: the whole file.

// routeDecision is where a CONNECT goes, decided from its target alone.
type routeDecision int

const (
	routeTunnel routeDecision = iota
	routeDirect
	routeSniff
)

const (
	reasonName    = "name"
	reasonPrefix  = "prefix"
	reasonSniffed = "sniffed name"
)

// sniffTimeout bounds how long a client is given to send something worth
// reading. A TLS client sends its hello the moment the SOCKS reply lands;
// this is for the ones that never will.
var sniffTimeout = 300 * time.Millisecond //nolint:gochecknoglobals // test hook

// sniffBufPool serves the sniff reads: one buffer per connection while it is
// being sniffed, returned as soon as the decision is made.
var sniffBufPool = sync.Pool{ //nolint:gochecknoglobals // pooled per-connection buffers
	New: func() any {
		b := make([]byte, sniff.MaxHead)
		return &b
	},
}

// classifyConnect decides from the target alone and says which rule matched.
func (c *Client) classifyConnect(host string) (routeDecision, string) {
	if c.rules == nil {
		return routeTunnel, ""
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if c.rules.MatchIP(ip) {
			return routeDirect, reasonPrefix
		}
		return routeSniff, ""
	}
	if c.rules.MatchDomain(host) {
		return routeDirect, reasonName
	}
	return routeTunnel, ""
}

// serveConnect is a CONNECT after its request has been read.
func (c *Client) serveConnect(ctx context.Context, conn net.Conn, req socksRequest) {
	job := connectJob{host: req.addr, port: req.port}
	decision, reason := c.classifyConnect(req.addr)
	switch decision {
	case routeDirect:
		c.dialDirect(ctx, conn, job, reason, "")
	case routeSniff:
		c.sniffThenRoute(ctx, conn, job)
	case routeTunnel:
		c.tunnelWhenReady(ctx, conn, job)
	}
}

// dialDirect serves a CONNECT from this process. fallback, when set, is the
// address the SOCKS client gave for a target that was sniffed as a name; it
// is dialed when the name does not resolve here.
func (c *Client) dialDirect(ctx context.Context, conn net.Conn, job connectJob, reason, fallback string) {
	logger.Infof("direct to %s:%d (%s)", job.host, job.port, reason)
	port := strconv.Itoa(job.port)
	remote, err := c.dialer.DialContext(ctx, "tcp", net.JoinHostPort(job.host, port))
	if err != nil && fallback != "" {
		logger.Debugf("direct to %s: %v; dialing %s instead", job.host, err, fallback)
		remote, err = c.dialer.DialContext(ctx, "tcp", net.JoinHostPort(fallback, port))
	}
	if err != nil {
		logger.Warnf("direct to %s:%d failed: %v", job.host, job.port, err)
		job.fail(conn, replyHostUnreachable(job.host))
		return
	}
	defer func() { _ = remote.Close() }()
	if !job.open(conn, remote) {
		return
	}
	if _, err := tunnelcore.CopyBidirectional(ctx, conn, remote); errors.Is(err, tunnelcore.ErrHalfOpenIdle) {
		logger.Debugf("direct to %s:%d closed: half-open and silent for %s", job.host, job.port, tunnelcore.HalfOpenGrace)
	}
}

// sniffThenRoute answers the CONNECT, reads what the client sends first and
// routes by the name in it when the rules cover that name.
func (c *Client) sniffThenRoute(ctx context.Context, conn net.Conn, job connectJob) {
	if _, err := conn.Write(replySuccess(job.host)); err != nil {
		return
	}
	job.replied = true
	// The tunnel is made ready while the client is given its window to name
	// a destination. A protocol where the server speaks first says nothing,
	// and without this it paid the whole window before anything was dialed -
	// under hev the SOCKS target is an address, so that was every SSH, SMTP,
	// IMAP or database connection (#35). Nothing here touches the client's
	// connection: the sniff owns its first bytes.
	//
	// ai-generated: the race and its handover.
	prepCtx, cancelPrep := context.WithCancel(ctx)
	defer cancelPrep()
	prepared := make(chan tunnelPrep, 1)
	// A copy: the sniff rewrites job.host and job.head under the preparation,
	// and the exit was asked to dial the address the client gave.
	prepJob := job
	go func() { prepared <- c.prepareTunnel(prepCtx, prepJob) }()

	host, head := sniffHead(conn)
	job.head = head
	if host != "" && c.rules.MatchDomain(host) {
		// The direct path won. Whatever the tunnel has by now is closed as
		// soon as it lands, here rather than left to the session.
		cancelPrep()
		go func() {
			if prep := <-prepared; prep.stream != nil {
				_ = prep.stream.Close()
			}
		}()
		given := job.host
		job.host = host
		c.dialDirect(ctx, conn, job, reasonSniffed, given)
		return
	}
	if host != "" {
		logger.Debugf("sniffed %s for %s:%d: not a direct name", host, job.host, job.port)
	}
	prep := <-prepared
	if prep.stream == nil {
		job.fail(conn, prep.reply)
		return
	}
	defer func() { _ = prep.stream.Close() }()
	c.pumpTunnel(ctx, conn, prep.stream, job)
}

// sniffHead reads the client's first bytes for up to sniffTimeout, or until
// the sniffer has an answer, and returns the name found and a copy of what
// was read, which the outbound must see first.
func sniffHead(conn net.Conn) (string, []byte) {
	bufPtr, ok := sniffBufPool.Get().(*[]byte)
	if !ok {
		return "", nil
	}
	defer sniffBufPool.Put(bufPtr)
	buf := *bufPtr
	_ = conn.SetReadDeadline(time.Now().Add(sniffTimeout))
	defer func() { _ = conn.SetReadDeadline(time.Time{}) }()
	filled := 0
	var host string
	for {
		n, err := conn.Read(buf[filled:])
		filled += n
		var need bool
		host, need = sniff.Host(buf[:filled])
		if err != nil || !need || filled == len(buf) {
			break
		}
	}
	if filled == 0 {
		return host, nil
	}
	return host, append([]byte(nil), buf[:filled]...)
}
