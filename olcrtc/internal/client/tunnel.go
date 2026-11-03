package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// connectJob is one CONNECT on its way to an outbound: the target, what the
// SOCKS client already sent while its first bytes were being sniffed, and
// whether the SOCKS reply is already on the wire.
type connectJob struct {
	host    string
	port    int
	head    []byte
	replied bool
}

// fail answers a CONNECT that will not be served: the SOCKS reply when it is
// still owed, nothing when it was sent before the outcome was known.
func (j connectJob) fail(conn net.Conn, reply []byte) {
	if !j.replied {
		_, _ = conn.Write(reply)
	}
}

// open answers success when it is still owed and replays the bytes read
// while sniffing into the outbound, which must see them before anything
// else. It reports false when either write failed.
func (j connectJob) open(conn net.Conn, outbound io.Writer) bool {
	if !j.replied {
		if _, err := conn.Write(replySuccess(j.host)); err != nil {
			return false
		}
	}
	if len(j.head) > 0 {
		if _, err := outbound.Write(j.head); err != nil {
			return false
		}
	}
	return true
}

// tunnelPrep is a tunnel made ready for one CONNECT: a stream the exit has
// acknowledged, or the SOCKS reply the failure deserves.
//
// ai-generated: split out of tunnelWhenReady so the sniff can race it (#35).
type tunnelPrep struct {
	stream *smux.Stream
	reply  []byte
}

// openTunnel opens a stream on session and has the exit dial the target. The
// client's own connection is not touched: what comes back is a tunnel ready
// to carry the CONNECT, or the reply its failure deserves.
//
// ai-generated: split out of tunnel() so the sniff can race it (#35).
func (c *Client) openTunnel(session *smux.Session, job connectJob) tunnelPrep {
	stream, err := session.OpenStream()
	if err != nil {
		logger.Warnf("OpenStream failed: %v", err)
		return tunnelPrep{reply: replyHostUnreachable(job.host)}
	}
	logger.Infof("sid=%d tunnel to %s:%d", stream.ID(), job.host, job.port)
	if err := c.sendConnectRequest(stream, job.host, job.port); err != nil {
		logger.Warnf("sid=%d connect failed: %v", stream.ID(), err)
		c.noteConnectFailure(err, job.host)
		_ = stream.Close()
		return tunnelPrep{reply: replyForConnectError(err, job.host)}
	}
	c.noteConnectSuccess(job.host)
	return tunnelPrep{stream: stream}
}

// pumpTunnel carries one CONNECT over a stream openTunnel prepared.
func (c *Client) pumpTunnel(ctx context.Context, conn net.Conn, stream *smux.Stream, job connectJob) {
	if !job.open(conn, stream) {
		return
	}
	if _, err := tunnelcore.CopyBidirectional(ctx, conn, stream); errors.Is(err, tunnelcore.ErrHalfOpenIdle) {
		logger.Debugf("sid=%d closed: half-open and silent for %s", stream.ID(), tunnelcore.HalfOpenGrace)
	}
}

func (c *Client) sendConnectRequest(stream *smux.Stream, targetAddr string, targetPort int) error {
	request, err := json.Marshal(map[string]any{
		"cmd": "connect", "addr": targetAddr, "port": targetPort,
	})
	if err != nil {
		return fmt.Errorf("sid=%d marshal connect req: %w", stream.ID(), err)
	}
	_ = stream.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if _, err := stream.Write(request); err != nil {
		return fmt.Errorf("sid=%d write connect req: %w", stream.ID(), err)
	}
	_ = stream.SetWriteDeadline(time.Time{})
	ack := make([]byte, 1)
	_ = stream.SetReadDeadline(time.Now().Add(runtime.ConnectAckTimeout()))
	if _, err := io.ReadFull(stream, ack); err != nil {
		return fmt.Errorf("sid=%d: %w (read_err=%w)", stream.ID(), ErrRemoteNotReady, err)
	}
	_ = stream.SetReadDeadline(time.Time{})
	if ack[0] != tunnelcore.ConnectAckOK {
		return &connectAckError{code: ack[0], streamID: stream.ID()}
	}
	return nil
}

// noteConnectFailure counts an IPv6 literal the exit could not reach, and
// latches its lack of an IPv6 route once several in a row have failed with
// none in between succeeding.
//
// It takes several because the exit answers host-unreachable for every dial
// that fails, whatever the reason: a host that is down, a port that is shut,
// a firewall that drops. One of those is an ordinary destination, not a
// missing address family, and latching on it refused every IPv6 target for
// the rest of the session - including the ones that work. The latch also
// lapses rather than holding until the next session, so an exit whose route
// came back, or one wrongly judged, is tried again within the window instead
// of staying cut off.
//
// ai-generated: the threshold and the lapsing latch (review of olcrtc#39);
// the ack-code check is from the port.
func (c *Client) noteConnectFailure(err error, targetAddr string) {
	var ackErr *connectAckError
	if !errors.As(err, &ackErr) || ackErr.code != socksRepHostUnreachable || !isIPv6Literal(targetAddr) {
		return
	}
	if c.ipv6Failures.Add(1) < ipv6FailuresBeforeLatch {
		return
	}
	if c.peerNoIPv6Until.Swap(time.Now().Add(ipv6LatchWindow).UnixNano()) == 0 {
		logger.Infof("exit reports no IPv6 route (%d unreachable literals, last %s) - "+
			"refusing IPv6 targets locally for %s", ipv6FailuresBeforeLatch, targetAddr, ipv6LatchWindow)
	}
}

// noteConnectSuccess clears the run of IPv6 failures, and the latch with it:
// the exit has just routed one, so whatever the earlier refusals were, they
// were about those destinations.
//
// ai-generated: the whole function (review of olcrtc#39).
func (c *Client) noteConnectSuccess(targetAddr string) {
	if !isIPv6Literal(targetAddr) {
		return
	}
	c.ipv6Failures.Store(0)
	c.peerNoIPv6Until.Store(0)
}

// noIPv6Route reports whether the exit is currently held to have no IPv6
// route. ai-generated (review of olcrtc#39).
func (c *Client) noIPv6Route() bool {
	until := c.peerNoIPv6Until.Load()
	if until == 0 {
		return false
	}
	if time.Now().UnixNano() < until {
		return true
	}
	// The window is over: let the next IPv6 target through, and let it
	// re-latch if the exit still cannot route it.
	c.peerNoIPv6Until.CompareAndSwap(until, 0)
	c.ipv6Failures.Store(0)
	return false
}

// isIPv6Literal reports whether target is an IPv6 address literal. Names are
// deliberately excluded: the exit resolves those itself and may pick an A
// record, so short-circuiting them would break hosts that are reachable.
func isIPv6Literal(target string) bool {
	ip := net.ParseIP(target)
	return ip != nil && ip.To4() == nil
}

type connectAckError struct {
	code     byte
	streamID uint32
}

func (e *connectAckError) Error() string {
	return fmt.Sprintf("sid=%d: %s (connect ack=0x%02x)", e.streamID, ErrRemoteNotReady, e.code)
}

func (e *connectAckError) Unwrap() error { return ErrRemoteNotReady }

func replyForConnectError(err error, target string) []byte {
	var ackErr *connectAckError
	if errors.As(err, &ackErr) {
		return socks5Reply(ackErr.code, target)
	}
	return replyHostUnreachable(target)
}
