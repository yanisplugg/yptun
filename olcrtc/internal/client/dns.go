package client

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"time"

	"github.com/xtaci/smux"
	"golang.org/x/net/dns/dnsmessage"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// DNS over the stream.
//
// With a tun2socks in front of the client (hev-socks5-tunnel on iOS) every
// UDP packet the phone sends arrives as a SOCKS5 UDP ASSOCIATE datagram, the
// resolver's queries included: the tun advertises a public resolver and the
// system asks it on port 53. The datagram lane those would ride is lossy by
// design, and on vp8channel it loses packets exactly when the link is busy;
// a lost query is a resolver retry seconds later, which a page load feels as
// a stall. So a datagram for port 53 is answered over a smux stream instead:
// the same CONNECT the TCP path sends, to the resolver's port 53, then TCP
// DNS (RFC 7766: a two-byte big-endian length before the message) for one
// query and one response, and the response goes back through the association
// as if it had come down the lane. The server dials the resolver over TCP and
// needs no change.
//
// Bounded on purpose: one goroutine per query, at most dnsMaxInFlight of them,
// each ending within dnsQueryDeadline whatever the peer does. Beyond the cap
// the query takes the lane, so a burst of lookups degrades to the old
// behaviour rather than to nothing.

const (
	dnsPort = 53
	// dnsMaxMessageSize is the largest message RFC 7766 can frame.
	dnsMaxMessageSize = 65535
	// dnsLengthPrefix is the RFC 7766 length field.
	dnsLengthPrefix = 2
)

// dnsQueryDeadline bounds one query end to end: stream open, CONNECT, the
// query, the response. A resolver answers in well under a second; anything
// past this is the resolver's retry to make. Variables so tests can shorten
// them.
var (
	dnsQueryDeadline = 5 * time.Second //nolint:gochecknoglobals // test hook
	dnsMaxInFlight   = int32(64)       //nolint:gochecknoglobals // test hook
)

var errDNSResponseEmpty = errors.New("empty dns response")

// tryDNSOverStream answers a port-53 datagram over a smux stream. It reports
// false when the query should take the datagram lane instead: not a DNS
// port, a message too large to frame, no session, or the in-flight cap.
func (c *Client) tryDNSOverStream(
	ctx context.Context,
	udpConn *net.UDPConn,
	src *net.UDPAddr,
	target udpwire.Endpoint,
	payload []byte,
) bool {
	if target.Port != dnsPort || len(payload) == 0 || len(payload) > dnsMaxMessageSize {
		return false
	}
	sess, sid, _ := c.sessionSnapshot()
	if sess == nil || sess.IsClosed() || sid == "" {
		return false
	}
	if c.dnsInFlight.Add(1) > dnsMaxInFlight {
		c.dnsInFlight.Add(-1)
		return false
	}
	c.announceDNSOverStream(sid, target.Host)
	// The read loop reuses its buffer and its address; the goroutine gets
	// copies.
	query := append([]byte(nil), payload...)
	client := &net.UDPAddr{IP: append(net.IP(nil), src.IP...), Port: src.Port, Zone: src.Zone}
	c.goTracked(func() {
		defer c.dnsInFlight.Add(-1)
		c.dnsQueryOverStream(ctx, sess, udpConn, client, target, query)
	})
	return true
}

// announceDNSOverStream logs once per session that queries take the stream.
// The resolver is the one the tun advertises, never a name being looked up.
func (c *Client) announceDNSOverStream(sessionID, resolver string) {
	c.udpMu.Lock()
	announced := c.dnsAnnouncedSession == sessionID
	if !announced {
		c.dnsAnnouncedSession = sessionID
	}
	c.udpMu.Unlock()
	if !announced {
		logger.Infof("dns over the stream: session=%s resolver=%s:%d", sessionID, resolver, dnsPort)
	}
}

// dnsQueryOverStream carries one query and its response. Every failure is a
// silent drop: the resolver retries, and a reply it did not ask for would
// only confuse it.
func (c *Client) dnsQueryOverStream(
	ctx context.Context,
	sess *smux.Session,
	udpConn *net.UDPConn,
	client *net.UDPAddr,
	target udpwire.Endpoint,
	query []byte,
) {
	stream, err := sess.OpenStream()
	if err != nil {
		logger.Debugf("dns over the stream: open failed: %v", err)
		return
	}
	defer func() { _ = stream.Close() }()
	// One hard stop for the whole exchange, whatever the peer does: the
	// CONNECT ack wait inside sendConnectRequest carries its own, longer
	// deadline, and a closed stream ends that too.
	stop := time.AfterFunc(dnsQueryDeadline, func() { _ = stream.Close() })
	defer stop.Stop()
	cancelWatch := context.AfterFunc(ctx, func() { _ = stream.Close() })
	defer cancelWatch()

	if connectErr := c.sendConnectRequest(stream, target.Host, int(target.Port)); connectErr != nil {
		logger.Debugf("dns over the stream: %v", connectErr)
		return
	}
	response, exchangeErr := exchangeTCPDNS(stream, query)
	if exchangeErr != nil {
		logger.Debugf("dns over the stream: sid=%d %v", stream.ID(), exchangeErr)
		return
	}
	packet, encodeErr := buildSocksUDP(target, response)
	if encodeErr != nil {
		logger.Debugf("dns over the stream: response encode failed: %v", encodeErr)
		return
	}
	_, _ = udpConn.WriteToUDP(packet, client)
}

// exchangeTCPDNS writes one RFC 7766 framed query and reads one framed
// response. The length prefix is checked by the caller; a message that does
// not fit uint16 never gets here.
func exchangeTCPDNS(stream io.ReadWriter, query []byte) ([]byte, error) {
	msg := make([]byte, dnsLengthPrefix+len(query))
	binary.BigEndian.PutUint16(msg, uint16(len(query))) //nolint:gosec // G115: bounded by dnsMaxMessageSize
	copy(msg[dnsLengthPrefix:], query)
	if _, err := stream.Write(msg); err != nil {
		return nil, err //nolint:wrapcheck // logged by the caller with the stream id
	}
	var hdr [dnsLengthPrefix]byte
	if _, err := io.ReadFull(stream, hdr[:]); err != nil {
		return nil, err //nolint:wrapcheck // same
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	if n == 0 {
		return nil, errDNSResponseEmpty
	}
	response := make([]byte, n)
	if _, err := io.ReadFull(stream, response); err != nil {
		return nil, err //nolint:wrapcheck // same
	}
	return response, nil
}

// Direct DNS (olcbox#28).
//
// A query for a name the rules cover must not leave through the exit: the
// answer a Russian resolver gives is the one a Russian site wants dialed, and
// the query itself is the whole point of resolving there. So it is sent as it
// is to the resolver ring - the network's own servers first - over a
// protected socket, and the answer goes back through the association
// untouched: real addresses, real TTLs, every record type. No session is
// involved, so it works while the room is down. The queries share the stream
// path's cap and deadline.
//
// ai-generated: this section.

// serverLister is what an Exchanger says about having anywhere to send to.
type serverLister interface {
	HasServers() bool
}

// tryDNSDirect answers a port-53 datagram for a direct name on the resolver
// ring. It reports false when the query should go on to the stream path:
// rules off, no exchanger or no servers on it, a name the rules do not
// cover, a query that does not parse, or the in-flight cap.
func (c *Client) tryDNSDirect(
	ctx context.Context,
	udpConn *net.UDPConn,
	src *net.UDPAddr,
	target udpwire.Endpoint,
	payload []byte,
) bool {
	if target.Port != dnsPort || c.rules == nil || c.exchanger == nil {
		return false
	}
	if lister, ok := c.exchanger.(serverLister); ok && !lister.HasServers() {
		return false
	}
	name, ok := dnsQuestionName(payload)
	if !ok || !c.rules.MatchDomain(name) {
		return false
	}
	if c.dnsInFlight.Add(1) > dnsMaxInFlight {
		c.dnsInFlight.Add(-1)
		return false
	}
	if c.dnsDirectAnnounced.CompareAndSwap(false, true) {
		logger.Infof("dns direct: queries for direct names go to the resolver ring")
	}
	query := append([]byte(nil), payload...)
	client := &net.UDPAddr{IP: append(net.IP(nil), src.IP...), Port: src.Port, Zone: src.Zone}
	c.goTracked(func() {
		defer c.dnsInFlight.Add(-1)
		c.dnsQueryDirect(ctx, udpConn, client, target, query)
	})
	return true
}

// dnsQueryDirect carries one query to the ring and its answer back. A
// failure is a silent drop, as on the stream path: the resolver retries.
func (c *Client) dnsQueryDirect(
	ctx context.Context,
	udpConn *net.UDPConn,
	client *net.UDPAddr,
	target udpwire.Endpoint,
	query []byte,
) {
	queryCtx, cancel := context.WithTimeout(ctx, dnsQueryDeadline)
	defer cancel()
	response, err := c.exchanger.Exchange(queryCtx, query)
	if err != nil {
		logger.Debugf("dns direct: %v", err)
		return
	}
	packet, encodeErr := buildSocksUDP(target, response)
	if encodeErr != nil {
		logger.Debugf("dns direct: response encode failed: %v", encodeErr)
		return
	}
	_, _ = udpConn.WriteToUDP(packet, client)
}

// dnsQuestionName is the name in a query's first question, without the
// trailing dot, or false when the message does not parse as a query.
func dnsQuestionName(msg []byte) (string, bool) {
	var p dnsmessage.Parser
	header, err := p.Start(msg)
	if err != nil || header.Response {
		return "", false
	}
	question, err := p.Question()
	if err != nil {
		return "", false
	}
	return strings.TrimSuffix(question.Name.String(), "."), true
}
