package client

import (
	"context"
	crand "crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// SOCKS5 UDP ASSOCIATE relay: each association is a loopback UDP socket the
// SOCKS client sends encapsulated packets to; every (client, target) pair is
// a flow with a random id, sealed with the session key under its own AAD and
// carried on the transport's lossy datagram lane. The server relays it and
// answers on the same flow.
//
// ai-generated: ported from the fork onto the v2 key sets.

// socksRequest is one parsed SOCKS5 request: the command and its target.
type socksRequest struct {
	cmd  byte
	addr string
	port int
}

const (
	socksCmdConnect      byte = 1
	socksCmdUDPAssociate byte = 3
)

// clientUDPFlow is one (association socket, SOCKS client address, target)
// tuple the client relays; the server knows it only by its id.
type clientUDPFlow struct {
	conn       *net.UDPConn
	clientAddr *net.UDPAddr
	target     udpwire.Endpoint
	lastSeen   time.Time
}

type clientUDPFlowKey struct {
	conn       *net.UDPConn
	clientAddr netip.AddrPort
	target     udpwire.Endpoint
}

func randomUDPFlowID() uint64 {
	var b [8]byte
	if _, err := crand.Read(b[:]); err != nil {
		return uint64(time.Now().UnixNano())
	}
	v := binary.BigEndian.Uint64(b[:])
	if v == 0 {
		return 1
	}
	return v
}

func udpAAD() []byte { return []byte(tunnelcore.UDPRecordAAD) }

const (
	// udpReadBufferSize is the read buffer of one SOCKS UDP association,
	// held for the association's life. 64 KB is a datagram's ceiling on a
	// loopback with a 64 KB MTU, which is what a desktop SOCKS client can
	// hand this.
	udpReadBufferSize = 64 * 1024
	// udpConstrainedReadBufferSize is the same buffer on the phone profile.
	// There a tun2socks with a 9000-byte MTU stands in front, so no
	// datagram exceeds ~9 KB, and the count of associations is what the
	// phone pays for: the system's resolver opens a new UDP session per
	// query, so a speed test's lookups alone held 117 associations at once
	// on iOS (olcbox 1.0.428), 7.5 MB of buffers at 64 KB each.
	udpConstrainedReadBufferSize = 16 * 1024
	udpFlowIdleTimeout           = 2 * time.Minute
	udpFlowSweepInterval         = 30 * time.Second
	defaultMaxUDPFlows           = 1024
	// defaultDatagramReadyTimeout bounds how long one datagram waits for the
	// lane to take it. A lane that is there can say no for a moment - over
	// its mark, or still negotiating behind the reliable one - and the wait
	// rides that out; a datagram held longer is stale for the calls and
	// games the lane is for, and the lane is lossy by contract, so it is
	// dropped. ai-generated: the constant (olcrtc#49).
	defaultDatagramReadyTimeout = time.Second
	// datagramPollDelay is how often a waiting datagram asks the lane again.
	datagramPollDelay = 2 * time.Millisecond
)

// udpAssociationReadBufferSize picks the association's read buffer for the
// profile this process runs under.
func udpAssociationReadBufferSize() int {
	if runtime.BuffersAreConstrained() {
		return udpConstrainedReadBufferSize
	}
	return udpReadBufferSize
}

var (
	errSocksUDPShortPacket       = errors.New("short packet")
	errSocksUDPBadReservedBytes  = errors.New("bad reserved bytes")
	errSocksUDPFragmented        = errors.New("fragmentation unsupported")
	errSocksUDPMissingPort       = errors.New("missing port")
	errSocksUDPMissingAddrType   = errors.New("missing address type")
	errSocksUDPShortIPv4         = errors.New("short ipv4 address")
	errSocksUDPMissingDomainSize = errors.New("missing domain length")
	errSocksUDPShortDomain       = errors.New("short domain")
	errSocksUDPShortIPv6         = errors.New("short ipv6 address")
	errTooManyUDPFlows           = errors.New("too many udp flows")
)

type udpAssociationSource struct {
	peerIP    netip.Addr
	requestIP netip.Addr
	port      int
}

// handleUDPAssociate serves one SOCKS5 UDP ASSOCIATE: it answers with a
// loopback relay socket and forwards every packet from the bound source
// until the TCP control connection closes.
func (c *Client) handleUDPAssociate(ctx context.Context, tcpConn net.Conn, req socksRequest) {
	dg, allowedSource, ok := c.prepareUDPAssociate(ctx, tcpConn, req)
	if !ok {
		_, _ = tcpConn.Write(replyHostUnreachable(req.addr))
		return
	}
	udpConn, err := listenUDPAssociate(tcpConn)
	if err != nil {
		logger.Warnf("socks udp associate listen failed: %v", err)
		_, _ = tcpConn.Write(replyHostUnreachable(req.addr))
		return
	}
	defer func() {
		c.removeUDPFlowsForConn(udpConn)
		_ = udpConn.Close()
	}()
	// One sweeper for every association, not one goroutine each: with a
	// tun2socks in front, associations come and go with every resolver
	// query, and a hundred of them held a hundred tickers and stacks.
	c.ensureUDPFlowSweeper(ctx)

	addr, ok := udpConn.LocalAddr().(*net.UDPAddr)
	if !ok {
		_, _ = tcpConn.Write(replyHostUnreachable(req.addr))
		return
	}
	if _, err := tcpConn.Write(replySuccessUDP(addr)); err != nil {
		return
	}
	logger.Infof("SOCKS5 UDP associate listening on %s", addr.String())

	// The association's own context ends when its control connection
	// closes. Everything a datagram of it starts runs under it - a wait for
	// the lane, a DNS query, a direct flow - since an answer has nowhere to
	// go once the socket is closed. The wait for the lane is the one that
	// matters: the read loop sits in it, and on the run's context an
	// association whose lane never opened kept its SOCKS slot until the run
	// ended. The sweeper above is the client's and keeps the run's context.
	//
	// ai-generated: assocCtx in place of the done channel (olcrtc#49).
	assocCtx, endAssoc := context.WithCancel(ctx)
	defer endAssoc()
	go func() {
		_, _ = io.Copy(io.Discard, tcpConn)
		endAssoc()
		_ = udpConn.Close()
	}()

	buf := make([]byte, udpAssociationReadBufferSize())
	for {
		n, src, err := udpConn.ReadFromUDP(buf)
		if err != nil {
			if assocCtx.Err() == nil && !errors.Is(err, net.ErrClosed) {
				logger.Debugf("socks udp read failed: %v", err)
			}
			return
		}
		if !allowedSource.allows(src) {
			logger.Debugf("drop socks udp packet from unbound source: %s", src.String())
			continue
		}
		c.forwardLocalUDP(assocCtx, dg, udpConn, src, buf[:n])
	}
}

// prepareUDPAssociate checks everything an association needs before a
// socket is opened: the relay is on, the transport has the datagram methods,
// the tunnel session is up and the SOCKS client's source is known.
func (c *Client) prepareUDPAssociate(
	ctx context.Context, tcpConn net.Conn, req socksRequest,
) (transport.DatagramTransport, udpAssociationSource, bool) {
	if c.udpDisabled {
		return nil, udpAssociationSource{}, false
	}
	// The methods are not the lane: the datachannel has them whatever its
	// engine. An association over one without a lane (Jitsi) is still
	// accepted, for what it carries off the lane - resolver queries over the
	// stream, direct flows, direct DNS - and forwardLocalUDP drops only what
	// is for the lane. ai-generated: the comment (olcrtc#49).
	dg, ok := c.ln.(transport.DatagramTransport)
	if !ok {
		return nil, udpAssociationSource{}, false
	}
	// With rules on, an association may carry direct flows and direct DNS
	// that need no session at all, and a resolver behind a tun2socks opens
	// one per query: waiting here would stall every Russian name for as long
	// as the room takes to come back. The lane still waits for the transport
	// before it sends.
	if c.rules == nil && !c.waitSessionReady(ctx) {
		return nil, udpAssociationSource{}, false
	}
	allowedSource, err := udpAssociationAllowedSource(tcpConn, req)
	if err != nil {
		logger.Debugf("socks udp associate source invalid: %v", err)
		return nil, udpAssociationSource{}, false
	}
	return dg, allowedSource, true
}

func udpAssociationAllowedSource(tcpConn net.Conn, req socksRequest) (udpAssociationSource, error) {
	remote, ok := tcpConn.RemoteAddr().(*net.TCPAddr)
	if !ok || remote.IP == nil {
		return udpAssociationSource{}, ErrUnsupportedAddressType
	}
	peerIP, err := netip.ParseAddr(remote.IP.String())
	if err != nil {
		return udpAssociationSource{}, fmt.Errorf("parse tcp peer ip: %w", err)
	}
	allowed := udpAssociationSource{peerIP: peerIP.Unmap(), port: req.port}
	if req.addr == "" {
		return allowed, nil
	}
	reqIP, ok := parseUDPRequestIP(req.addr)
	if !ok {
		return allowed, nil
	}
	allowed.requestIP = reqIP.Unmap()
	return allowed, nil
}

func parseUDPRequestIP(addr string) (netip.Addr, bool) {
	reqIP, err := netip.ParseAddr(addr)
	if err != nil || reqIP.IsUnspecified() {
		return netip.Addr{}, false
	}
	return reqIP, true
}

func (s udpAssociationSource) allows(src *net.UDPAddr) bool {
	if src == nil || src.AddrPort().Addr().Unmap() != s.peerIP {
		return false
	}
	if s.requestIP.IsValid() && src.AddrPort().Addr().Unmap() != s.requestIP {
		return false
	}
	return s.port == 0 || src.Port == s.port
}

func listenUDPAssociate(tcpConn net.Conn) (*net.UDPConn, error) {
	ip := net.IPv4(127, 0, 0, 1)
	if addr, ok := tcpConn.LocalAddr().(*net.TCPAddr); ok && addr.IP != nil && !addr.IP.IsUnspecified() {
		ip = addr.IP
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: ip})
	if err != nil {
		return nil, fmt.Errorf("listen udp associate: %w", err)
	}
	return conn, nil
}

func (c *Client) forwardLocalUDP(
	ctx context.Context,
	dg transport.DatagramTransport,
	udpConn *net.UDPConn,
	src *net.UDPAddr,
	packet []byte,
) {
	target, payload, err := parseSocksUDP(packet)
	if err != nil {
		logger.Debugf("drop malformed socks udp packet: %v", err)
		return
	}
	// A resolver query for a name the rules cover is answered here on the
	// network's own servers; any other takes the reliable stream, not the
	// lossy lane (dns.go). A target the rules cover gets a socket of this
	// process (direct_udp.go). Everything none of them takes rides the lane.
	if c.tryDNSDirect(ctx, udpConn, src, target, payload) || c.tryDNSOverStream(ctx, udpConn, src, target, payload) {
		return
	}
	if c.tryDirectUDP(ctx, udpConn, src, target, payload) {
		return
	}
	// A link without the lane (Jitsi's datachannel, which has the lane's
	// methods whatever its engine) never takes the datagram, and a wait for
	// it held the read loop until the association ended: it is dropped here,
	// at once and before it takes a flow. ai-generated: the check (olcrtc#49).
	if !dg.Features().Datagram {
		logger.Debugf("drop udp packet: the link has no datagram lane")
		return
	}
	flowID, ok := c.udpFlowID(udpConn, src, target)
	if !ok {
		logger.Debugf("drop udp packet: %v", errTooManyUDPFlows)
		return
	}
	frame := udpwire.Frame{
		Type:     udpwire.FrameTypePacket,
		FlowID:   flowID,
		Endpoint: target,
		Payload:  payload,
	}
	wire, err := udpwire.Encode(frame)
	if err != nil {
		logger.Debugf("drop udp packet encode failed: %v", err)
		return
	}
	enc, err := c.keys.SealInto(nil, wire, udpAAD())
	if err != nil {
		logger.Debugf("drop udp packet encrypt failed: %v", err)
		return
	}
	if !waitDatagramReady(ctx, dg, c.datagramWait()) {
		logger.Debugf("drop udp packet: the datagram lane did not take it")
		return
	}
	if err := dg.SendDatagram(enc); err != nil {
		logger.Debugf("send udp datagram failed: %v", err)
	}
}

func (c *Client) onDatagram(ciphertext []byte) {
	if c.udpDisabled {
		return
	}
	wire, err := c.keys.OpenInto(nil, ciphertext, udpAAD())
	if err != nil {
		logger.Debugf("drop udp datagram decrypt failed: %v", err)
		return
	}
	frame, err := udpwire.Decode(wire)
	if err != nil {
		logger.Debugf("drop udp datagram decode failed: %v", err)
		return
	}
	if frame.Type != udpwire.FrameTypePacket {
		return
	}

	c.udpMu.Lock()
	flow, ok := c.udpFlows[frame.FlowID]
	if ok {
		flow.lastSeen = time.Now()
		c.udpFlows[frame.FlowID] = flow
	}
	c.udpMu.Unlock()
	if !ok {
		return
	}
	packet, err := buildSocksUDP(frame.Endpoint, frame.Payload)
	if err != nil {
		logger.Debugf("drop udp response encode failed: %v", err)
		return
	}
	_, _ = flow.conn.WriteToUDP(packet, flow.clientAddr)
}

func (c *Client) udpFlowID(conn *net.UDPConn, src *net.UDPAddr, target udpwire.Endpoint) (uint64, bool) {
	c.udpMu.Lock()
	defer c.udpMu.Unlock()
	now := time.Now()
	c.ensureUDPFlowIndexLocked()
	key := clientUDPFlowIndexKey(conn, src, target)
	if id, ok := c.udpFlowIndex[key]; ok {
		flow := c.udpFlows[id]
		flow.lastSeen = now
		c.udpFlows[id] = flow
		return id, true
	}
	if len(c.udpFlows) >= normalizeMaxUDPFlows(c.maxUDPFlows) {
		return 0, false
	}
	for {
		id := randomUDPFlowID()
		if _, exists := c.udpFlows[id]; !exists {
			c.udpFlows[id] = clientUDPFlow{conn: conn, clientAddr: src, target: target, lastSeen: now}
			c.udpFlowIndex[key] = id
			return id, true
		}
	}
}

func (c *Client) ensureUDPFlowIndexLocked() {
	if c.udpFlowIndex != nil {
		return
	}
	c.udpFlowIndex = make(map[clientUDPFlowKey]uint64, len(c.udpFlows))
	for id, flow := range c.udpFlows {
		c.udpFlowIndex[clientUDPFlowIndexKey(flow.conn, flow.clientAddr, flow.target)] = id
	}
}

func clientUDPFlowIndexKey(
	conn *net.UDPConn,
	src *net.UDPAddr,
	target udpwire.Endpoint,
) clientUDPFlowKey {
	return clientUDPFlowKey{conn: conn, clientAddr: src.AddrPort(), target: target}
}

func (c *Client) removeUDPFlowsForConn(conn *net.UDPConn) {
	var closed []uint64
	c.udpMu.Lock()
	c.ensureUDPFlowIndexLocked()
	for id, flow := range c.udpFlows {
		if flow.conn == conn {
			delete(c.udpFlows, id)
			delete(c.udpFlowIndex, clientUDPFlowIndexKey(flow.conn, flow.clientAddr, flow.target))
			closed = append(closed, id)
		}
	}
	direct := c.takeDirectFlowsLocked(func(flow *directUDPFlow) bool { return flow.assoc == conn })
	c.udpMu.Unlock()
	closeDirectFlows(direct)
	c.sendUDPFlowCloses(closed)
}

// ensureUDPFlowSweeper starts the client's one idle-flow sweeper the first
// time an association needs it. It lives as long as ctx, the client's run,
// and looks at every association's flows on each tick. Only
// handleUDPAssociate calls it: everything under an association runs on the
// association's context, and a sweeper started on one would end with it,
// never to start again. ai-generated: the last two sentences (olcrtc#49).
func (c *Client) ensureUDPFlowSweeper(ctx context.Context) {
	c.udpSweepOnce.Do(func() {
		c.goTracked(func() { c.sweepUDPFlows(ctx) })
	})
}

func (c *Client) sweepUDPFlows(ctx context.Context) {
	ticker := time.NewTicker(udpFlowSweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			c.removeIdleUDPFlows(now)
		}
	}
}

// removeIdleUDPFlows drops every flow, on any association, that has been
// silent for udpFlowIdleTimeout, and tells the server.
func (c *Client) removeIdleUDPFlows(now time.Time) {
	c.removeIdleUDPFlowsMatching(now, func(*net.UDPConn) bool { return true })
}

// removeIdleUDPFlowsForConn is removeIdleUDPFlows for one association.
func (c *Client) removeIdleUDPFlowsForConn(conn *net.UDPConn, now time.Time) {
	c.removeIdleUDPFlowsMatching(now, func(owner *net.UDPConn) bool { return owner == conn })
}

func (c *Client) removeIdleUDPFlowsMatching(now time.Time, owns func(*net.UDPConn) bool) {
	var closed []uint64
	c.udpMu.Lock()
	c.ensureUDPFlowIndexLocked()
	for id, flow := range c.udpFlows {
		if owns(flow.conn) && now.Sub(flow.lastSeen) >= udpFlowIdleTimeout {
			delete(c.udpFlows, id)
			delete(c.udpFlowIndex, clientUDPFlowIndexKey(flow.conn, flow.clientAddr, flow.target))
			closed = append(closed, id)
		}
	}
	direct := c.takeDirectFlowsLocked(func(flow *directUDPFlow) bool {
		return owns(flow.assoc) && now.Sub(flow.lastSeen) >= udpFlowIdleTimeout
	})
	c.udpMu.Unlock()
	closeDirectFlows(direct)
	c.sendUDPFlowCloses(closed)
}

func (c *Client) sendUDPFlowCloses(flowIDs []uint64) {
	if c.udpDisabled {
		return
	}
	dg, ok := c.ln.(transport.DatagramTransport)
	if !ok || !dg.DatagramCanSend() {
		return
	}
	for _, flowID := range flowIDs {
		wire, err := udpwire.Encode(udpwire.Frame{Type: udpwire.FrameTypeClose, FlowID: flowID})
		if err != nil {
			continue
		}
		enc, err := c.keys.SealInto(nil, wire, udpAAD())
		if err != nil {
			continue
		}
		_ = dg.SendDatagram(enc)
	}
}

// waitSessionReady blocks until the tunnel session has finished its
// handshake, like a CONNECT does, so a datagram never leaves before the
// server can attribute it to a session. It waits from the same pool of
// maxParkedRequests, and reports failure at once when the pool is full.
func (c *Client) waitSessionReady(ctx context.Context) bool {
	readyCtx, cancel := context.WithTimeout(ctx, c.readyTimeout())
	defer cancel()
	parked := false
	defer func() {
		if parked {
			c.unpark()
		}
	}()
	for {
		sess, sid, ready := c.sessionSnapshot()
		if sess != nil && !sess.IsClosed() && sid != "" {
			return true
		}
		if !parked {
			if !c.park() {
				return false
			}
			parked = true
		}
		select {
		case <-readyCtx.Done():
			return false
		case <-ready:
		}
	}
}

func normalizeMaxUDPFlows(maxFlows int) int {
	if maxFlows <= 0 {
		return defaultMaxUDPFlows
	}
	return maxFlows
}

// datagramWait is how long a datagram waits for the lane.
//
// ai-generated: the method (olcrtc#49).
func (c *Client) datagramWait() time.Duration {
	if c.datagramReadyTimeout > 0 {
		return c.datagramReadyTimeout
	}
	return defaultDatagramReadyTimeout
}

// waitDatagramReady holds a datagram until the lane can take it: for at most
// wait, and no longer than ctx, its association, lasts. It reports false
// when the datagram is to be dropped.
//
// ai-generated: the bound, wait and ctx being the association's (olcrtc#49).
func waitDatagramReady(ctx context.Context, dg transport.DatagramTransport, wait time.Duration) bool {
	if dg.DatagramCanSend() {
		return true
	}
	waitCtx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	poll := time.NewTicker(datagramPollDelay)
	defer poll.Stop()
	for {
		select {
		case <-waitCtx.Done():
			return false
		case <-poll.C:
			if dg.DatagramCanSend() {
				return true
			}
		}
	}
}

func parseSocksUDP(packet []byte) (udpwire.Endpoint, []byte, error) {
	if len(packet) < 4 {
		return udpwire.Endpoint{}, nil, errSocksUDPShortPacket
	}
	if packet[0] != 0 || packet[1] != 0 {
		return udpwire.Endpoint{}, nil, errSocksUDPBadReservedBytes
	}
	if packet[2] != 0 {
		return udpwire.Endpoint{}, nil, errSocksUDPFragmented
	}
	host, off, err := parseSocksUDPHost(packet, 3)
	if err != nil {
		return udpwire.Endpoint{}, nil, err
	}
	if len(packet) < off+2 {
		return udpwire.Endpoint{}, nil, errSocksUDPMissingPort
	}
	port := binary.BigEndian.Uint16(packet[off : off+2])
	payload := packet[off+2:]
	return udpwire.Endpoint{Host: host, Port: port}, payload, nil
}

func parseSocksUDPHost(packet []byte, off int) (string, int, error) {
	if len(packet) <= off {
		return "", 0, errSocksUDPMissingAddrType
	}
	switch packet[off] {
	case 1:
		if len(packet) < off+1+4 {
			return "", 0, errSocksUDPShortIPv4
		}
		return net.IP(packet[off+1 : off+1+4]).String(), off + 1 + 4, nil
	case 3:
		if len(packet) < off+2 {
			return "", 0, errSocksUDPMissingDomainSize
		}
		size := int(packet[off+1])
		if size == 0 || len(packet) < off+2+size {
			return "", 0, errSocksUDPShortDomain
		}
		return string(packet[off+2 : off+2+size]), off + 2 + size, nil
	case 4:
		if len(packet) < off+1+16 {
			return "", 0, errSocksUDPShortIPv6
		}
		return net.IP(packet[off+1 : off+1+16]).String(), off + 1 + 16, nil
	default:
		return "", 0, fmt.Errorf("%w: %d", ErrUnsupportedAddressType, packet[off])
	}
}

func buildSocksUDP(endpoint udpwire.Endpoint, payload []byte) ([]byte, error) {
	addrType, addr, err := socksAddrBytes(endpoint.Host)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, 4+len(addr)+2+len(payload))
	out = append(out, 0, 0, 0, addrType)
	if addrType == 3 {
		out = append(out, byte(len(addr))) //nolint:gosec // G115: domain length is capped at 255 by socksAddrBytes.
	}
	out = append(out, addr...)
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], endpoint.Port)
	out = append(out, port[:]...)
	out = append(out, payload...)
	return out, nil
}

func socksAddrBytes(host string) (byte, []byte, error) {
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			return 1, ip4, nil
		}
		if ip16 := ip.To16(); ip16 != nil {
			return 4, ip16, nil
		}
	}
	if host == "" || len(host) > 255 {
		return 0, nil, ErrUnsupportedAddressType
	}
	return 3, []byte(host), nil
}

func replySuccessUDP(addr *net.UDPAddr) []byte {
	ip := addr.IP.To4()
	if ip == nil {
		ip = net.IPv4(127, 0, 0, 1)
	}
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], uint16(addr.Port)) //nolint:gosec // G115: UDP listener ports are 0..65535.
	return []byte{5, 0, 0, 1, ip[0], ip[1], ip[2], ip[3], port[0], port[1]}
}
