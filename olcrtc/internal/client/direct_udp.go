package client

import (
	"context"
	"net"
	"strconv"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// Direct UDP flows (olcbox#28): a datagram whose target the rules name is
// relayed over a connected, protected socket of this process instead of the
// lane, one flow per (association, client, target) like the lane keeps. The
// flows share the lane's idle sweeper, its timeout and its cap, and close
// with their association or with the run.
//
// ai-generated: the whole file.

// directUDPFlow is one relay this process runs itself.
type directUDPFlow struct {
	assoc    *net.UDPConn
	remote   *net.UDPConn
	client   *net.UDPAddr
	target   udpwire.Endpoint
	lastSeen time.Time
}

// tryDirectUDP relays a datagram for a target the rules cover. It reports
// false when the packet should take the lane instead: no rules, a target
// they do not name, or a flow table already at its cap. A target the rules
// do name is never sent down the lane: when the socket cannot be opened the
// packet is dropped, and the application retries as it would on any network.
func (c *Client) tryDirectUDP(
	ctx context.Context,
	assoc *net.UDPConn,
	src *net.UDPAddr,
	target udpwire.Endpoint,
	payload []byte,
) bool {
	if c.rules == nil || !c.rules.MatchHost(target.Host) {
		return false
	}
	key := clientUDPFlowIndexKey(assoc, src, target)
	flow, full := c.lookupDirectFlow(key)
	if full {
		logger.Debugf("direct udp to %s:%d: %v", target.Host, target.Port, errTooManyUDPFlows)
		return false
	}
	if flow == nil {
		flow = c.openDirectFlow(ctx, assoc, src, target, key)
		if flow == nil {
			return true
		}
	}
	if _, err := flow.remote.Write(payload); err != nil {
		logger.Debugf("direct udp to %s:%d write failed: %v", target.Host, target.Port, err)
	}
	return true
}

// lookupDirectFlow finds a live flow and marks it seen, or reports that a
// new one would exceed the cap.
func (c *Client) lookupDirectFlow(key clientUDPFlowKey) (*directUDPFlow, bool) {
	c.udpMu.Lock()
	defer c.udpMu.Unlock()
	if flow, ok := c.directUDP[key]; ok {
		flow.lastSeen = time.Now()
		return flow, false
	}
	return nil, len(c.directUDP) >= normalizeMaxUDPFlows(c.maxUDPFlows)
}

// openDirectFlow dials the target - a name resolves through the session's
// lookup - and starts the reader that carries its answers back. Nil when the
// dial failed, which is logged.
func (c *Client) openDirectFlow(
	ctx context.Context,
	assoc *net.UDPConn,
	src *net.UDPAddr,
	target udpwire.Endpoint,
	key clientUDPFlowKey,
) *directUDPFlow {
	addr := net.JoinHostPort(target.Host, strconv.Itoa(int(target.Port)))
	conn, err := c.dialer.DialContext(ctx, "udp", addr)
	if err != nil {
		logger.Warnf("direct udp to %s failed: %v", addr, err)
		return nil
	}
	remote, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil
	}
	flow := &directUDPFlow{
		assoc: assoc, remote: remote, target: target, lastSeen: time.Now(),
		client: &net.UDPAddr{IP: append(net.IP(nil), src.IP...), Port: src.Port, Zone: src.Zone},
	}
	c.udpMu.Lock()
	if existing, raced := c.directUDP[key]; raced {
		// Two packets of a new flow arrived together; the first one's socket
		// carries both.
		c.udpMu.Unlock()
		_ = remote.Close()
		return existing
	}
	if c.directUDP == nil {
		c.directUDP = make(map[clientUDPFlowKey]*directUDPFlow)
	}
	c.directUDP[key] = flow
	c.udpMu.Unlock()
	logger.Infof("direct udp to %s:%d", target.Host, target.Port)
	// The sweeper that closes the flow when it idles is the client's, started
	// by handleUDPAssociate on the run's context; ctx is the association's.
	c.goTracked(func() { c.readDirectFlow(ctx, flow) })
	return flow
}

// readDirectFlow carries the target's datagrams back to the SOCKS client
// until the socket is closed: by the sweeper, with the association, or with
// the run.
func (c *Client) readDirectFlow(ctx context.Context, flow *directUDPFlow) {
	stop := context.AfterFunc(ctx, func() { _ = flow.remote.Close() })
	defer stop()
	buf := make([]byte, udpAssociationReadBufferSize())
	for {
		n, err := flow.remote.Read(buf)
		if err != nil {
			return
		}
		packet, encodeErr := buildSocksUDP(flow.target, buf[:n])
		if encodeErr != nil {
			continue
		}
		c.udpMu.Lock()
		flow.lastSeen = time.Now()
		c.udpMu.Unlock()
		_, _ = flow.assoc.WriteToUDP(packet, flow.client)
	}
}

// takeDirectFlowsLocked removes every direct flow match accepts and returns
// them; the caller closes their sockets outside the lock.
func (c *Client) takeDirectFlowsLocked(match func(*directUDPFlow) bool) []*directUDPFlow {
	var taken []*directUDPFlow
	for key, flow := range c.directUDP {
		if match(flow) {
			delete(c.directUDP, key)
			taken = append(taken, flow)
		}
	}
	return taken
}

func closeDirectFlows(flows []*directUDPFlow) {
	for _, flow := range flows {
		_ = flow.remote.Close()
	}
}
