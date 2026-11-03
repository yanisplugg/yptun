package server

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// SOCKS5 UDP relay, server side: every datagram a peer sends is opened with
// the key its pin group settled on, decoded as a udpwire frame and relayed
// to the target as a flow keyed by (peer, flow id); replies travel back
// sealed under the same key. Flows are metered like streams when they close.
//
// ai-generated: ported from the fork onto the v2 key sets.

func udpAAD() []byte { return []byte(tunnelcore.UDPRecordAAD) }

const (
	udpRelayBufferSize = 64 * 1024
	udpFlowIdleTimeout = 2 * time.Minute
	defaultMaxUDPFlows = 1024
	udpLookupTimeout   = 5 * time.Second
)

var (
	errTooManyUDPFlows = errors.New("too many udp flows")
	errSocksUDPReply   = errors.New("bad socks5 udp associate reply")
)

type udpDialTarget struct {
	network  string
	host     string
	endpoint udpwire.Endpoint
}

type socks5UDPConn struct {
	tcpConn  net.Conn
	udpConn  net.Conn
	endpoint udpwire.Endpoint
}

type serverUDPKey struct {
	peerID string
	flowID uint64
}

type serverUDPFlow struct {
	key       serverUDPKey
	conn      net.Conn
	endpoint  udpwire.Endpoint
	sessionID string
	// entry is the ring entry this flow's peer pinned; replies are sealed
	// under it, so a flow always answers in the key that opened it.
	entry     *crypto.RingEntry
	bytesIn   atomic.Uint64
	bytesOut  atomic.Uint64
	closeOnce sync.Once
}

func (s *Server) onDatagram(ciphertext []byte) {
	s.handleDatagram("", ciphertext)
}

func (s *Server) onPeerDatagram(peerID string, ciphertext []byte) {
	s.handleDatagram(peerID, ciphertext)
}

func (s *Server) handleDatagram(peerID string, ciphertext []byte) {
	if s.udpDisabled {
		return
	}
	entry := s.pinnedDatagramEntry(peerID)
	if entry == nil {
		// A datagram can only follow the handshake that pins the peer's key;
		// before that it cannot belong to an authenticated peer, so there is
		// nothing to try it against.
		logger.Debugf("drop udp datagram before key pairing")
		return
	}
	wire, err := entry.Keys.OpenInto(nil, ciphertext, udpAAD())
	if err != nil {
		logger.Debugf("drop udp datagram decrypt failed: %v", err)
		return
	}
	frame, err := udpwire.Decode(wire)
	if err != nil {
		logger.Debugf("drop udp datagram decode failed: %v", err)
		return
	}

	key := serverUDPKey{peerID: peerID, flowID: frame.FlowID}
	if frame.Type == udpwire.FrameTypeClose {
		s.closeUDPFlow(key)
		return
	}
	sessionID := s.udpSessionID(peerID)
	if sessionID == "" {
		logger.Debugf("drop udp datagram without authenticated session")
		return
	}
	flow, err := s.getOrCreateUDPFlow(key, frame.Endpoint, sessionID, entry)
	if err != nil {
		logger.Debugf("drop udp flow create failed target=%s:%d err=%v",
			frame.Endpoint.Host, frame.Endpoint.Port, err)
		return
	}
	n, err := flow.conn.Write(frame.Payload)
	if n > 0 {
		flow.bytesIn.Add(uint64(n))
		flow.touch(time.Now())
	}
	if err != nil {
		logger.Debugf("udp relay write failed target=%s:%d err=%v",
			frame.Endpoint.Host, frame.Endpoint.Port, err)
		s.closeUDPFlow(key)
	}
}

func (s *Server) getOrCreateUDPFlow(
	key serverUDPKey,
	endpoint udpwire.Endpoint,
	sessionID string,
	entry *crypto.RingEntry,
) (*serverUDPFlow, error) {
	s.udpMu.Lock()
	if flow := s.udpFlows[key]; flow != nil {
		s.udpMu.Unlock()
		return flow, nil
	}
	if len(s.udpFlows)+s.udpPendingFlows >= normalizeMaxUDPFlows(s.maxUDPFlows) {
		s.udpMu.Unlock()
		return nil, errTooManyUDPFlows
	}
	s.udpPendingFlows++
	s.udpMu.Unlock()
	defer s.releaseUDPPendingFlow()

	target, err := s.resolveUDPTarget(endpoint, s.socksProxyAddr != "")
	if err != nil {
		return nil, err
	}
	conn, err := s.dialUDPFlow(target)
	if err != nil {
		return nil, err
	}

	flow := &serverUDPFlow{
		key:       key,
		conn:      conn,
		endpoint:  endpoint,
		sessionID: sessionID,
		entry:     entry,
	}
	flow.touch(time.Now())
	s.udpMu.Lock()
	if existing := s.udpFlows[key]; existing != nil {
		s.udpMu.Unlock()
		_ = conn.Close()
		return existing, nil
	}
	if len(s.udpFlows) >= normalizeMaxUDPFlows(s.maxUDPFlows) {
		s.udpMu.Unlock()
		_ = conn.Close()
		return nil, errTooManyUDPFlows
	}
	s.udpFlows[key] = flow
	s.udpMu.Unlock()

	go s.readUDPFlow(flow)
	return flow, nil
}

func (s *Server) dialUDPFlow(target udpDialTarget) (net.Conn, error) {
	if s.socksProxyAddr != "" {
		conn, err := s.socks5UDPAssociate(target.endpoint)
		if err != nil {
			return nil, err
		}
		return conn, nil
	}
	addr := net.JoinHostPort(target.host, strconv.Itoa(int(target.endpoint.Port)))
	conn, err := protect.NewDialer(s.resolver).DialContext(s.udpBaseCtx(), target.network, addr)
	if err != nil {
		return nil, fmt.Errorf("udp dial failed: %w", err)
	}
	return conn, nil
}

func (s *Server) socks5UDPAssociate(endpoint udpwire.Endpoint) (net.Conn, error) {
	proxyAddr := net.JoinHostPort(s.socksProxyAddr, strconv.Itoa(s.socksProxyPort))
	dialer := protect.NewDialer(s.resolver)
	// "tcp", not "tcp4": the proxy address is operator configuration and may
	// be an IPv6 literal; the UDP side picks its family from the target.
	tcpConn, err := dialer.DialContext(s.udpBaseCtx(), "tcp", proxyAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to dial udp proxy: %w", err)
	}
	if err = s.socks5Authenticate(tcpConn); err != nil {
		_ = tcpConn.Close()
		return nil, err
	}
	if _, err = tcpConn.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		_ = tcpConn.Close()
		return nil, fmt.Errorf("failed to write socks5 udp associate req: %w", err)
	}
	relayAddr, err := readSocks5UDPAssociateReply(tcpConn)
	if err != nil {
		_ = tcpConn.Close()
		return nil, err
	}
	udpConn, err := dialer.DialContext(s.udpBaseCtx(), relayAddr.Network(), relayAddr.String())
	if err != nil {
		_ = tcpConn.Close()
		return nil, fmt.Errorf("failed to dial socks5 udp relay: %w", err)
	}
	return &socks5UDPConn{tcpConn: tcpConn, udpConn: udpConn, endpoint: endpoint}, nil
}

func readSocks5UDPAssociateReply(r io.Reader) (*net.UDPAddr, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, fmt.Errorf("failed to read socks5 udp associate reply: %w", err)
	}
	if header[0] != 5 || header[1] != 0 {
		return nil, fmt.Errorf("%w: code=%d", errSocksUDPReply, header[1])
	}
	host, err := readSocks5UDPReplyHost(r, header[3])
	if err != nil {
		return nil, err
	}
	var portBuf [2]byte
	if _, err = io.ReadFull(r, portBuf[:]); err != nil {
		return nil, fmt.Errorf("failed to read socks5 udp associate port: %w", err)
	}
	port := int(binary.BigEndian.Uint16(portBuf[:]))
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("resolve socks5 udp relay: %w", err)
	}
	return addr, nil
}

func readSocks5UDPReplyHost(r io.Reader, addrType byte) (string, error) {
	switch addrType {
	case 1:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", fmt.Errorf("failed to read socks5 udp relay ipv4: %w", err)
		}
		return net.IP(buf).String(), nil
	case 3:
		var size [1]byte
		if _, err := io.ReadFull(r, size[:]); err != nil {
			return "", fmt.Errorf("failed to read socks5 udp relay domain size: %w", err)
		}
		buf := make([]byte, int(size[0]))
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", fmt.Errorf("failed to read socks5 udp relay domain: %w", err)
		}
		return string(buf), nil
	case 4:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", fmt.Errorf("failed to read socks5 udp relay ipv6: %w", err)
		}
		return net.IP(buf).String(), nil
	default:
		return "", fmt.Errorf("%w: atyp=%d", errSocksUDPReply, addrType)
	}
}

func (s *Server) releaseUDPPendingFlow() {
	s.udpMu.Lock()
	if s.udpPendingFlows > 0 {
		s.udpPendingFlows--
	}
	s.udpMu.Unlock()
}

func (c *socks5UDPConn) Read(p []byte) (int, error) {
	buf := make([]byte, udpRelayBufferSize)
	for {
		n, err := c.udpConn.Read(buf)
		if err != nil {
			return 0, fmt.Errorf("socks5 udp read: %w", err)
		}
		_, payload, err := decodeSocks5UDPPacket(buf[:n])
		if err != nil {
			continue
		}
		return copy(p, payload), nil
	}
}

func (c *socks5UDPConn) Write(p []byte) (int, error) {
	packet, err := encodeSocks5UDPPacket(c.endpoint, p)
	if err != nil {
		return 0, err
	}
	if _, err := c.udpConn.Write(packet); err != nil {
		return 0, fmt.Errorf("socks5 udp write: %w", err)
	}
	return len(p), nil
}

func (c *socks5UDPConn) Close() error {
	err := c.udpConn.Close()
	if tcpErr := c.tcpConn.Close(); err == nil {
		err = tcpErr
	}
	if err != nil {
		return fmt.Errorf("socks5 udp close: %w", err)
	}
	return nil
}

func (c *socks5UDPConn) LocalAddr() net.Addr  { return c.udpConn.LocalAddr() }
func (c *socks5UDPConn) RemoteAddr() net.Addr { return c.udpConn.RemoteAddr() }
func (c *socks5UDPConn) SetDeadline(t time.Time) error {
	return c.wrapDeadline(c.udpConn.SetDeadline(t))
}
func (c *socks5UDPConn) SetReadDeadline(t time.Time) error {
	return c.wrapDeadline(c.udpConn.SetReadDeadline(t))
}

func (c *socks5UDPConn) SetWriteDeadline(t time.Time) error {
	return c.wrapDeadline(c.udpConn.SetWriteDeadline(t))
}

func (c *socks5UDPConn) wrapDeadline(err error) error {
	if err != nil {
		return fmt.Errorf("socks5 udp deadline: %w", err)
	}
	return nil
}

func (s *Server) udpBaseCtx() context.Context {
	if s.baseCtx != nil {
		return s.baseCtx
	}
	return context.Background()
}

func (s *Server) readUDPFlow(flow *serverUDPFlow) {
	buf := make([]byte, udpRelayBufferSize)
	for {
		n, err := flow.conn.Read(buf)
		if err != nil {
			var netErr net.Error
			switch {
			case errors.Is(err, net.ErrClosed):
			case errors.As(err, &netErr) && netErr.Timeout():
				logger.Debugf("udp relay idle timeout target=%s:%d",
					flow.endpoint.Host, flow.endpoint.Port)
			default:
				logger.Debugf("udp relay read ended target=%s:%d err=%v",
					flow.endpoint.Host, flow.endpoint.Port, err)
			}
			s.closeUDPFlow(flow.key)
			return
		}
		if n <= 0 {
			continue
		}
		flow.touch(time.Now())
		frame := udpwire.Frame{
			Type:     udpwire.FrameTypePacket,
			FlowID:   flow.key.flowID,
			Endpoint: flow.endpoint,
			Payload:  buf[:n],
		}
		if s.sendUDPFrame(flow.key.peerID, flow.entry, frame) {
			flow.bytesOut.Add(uint64(n))
		}
	}
}

func (flow *serverUDPFlow) touch(now time.Time) {
	_ = flow.conn.SetReadDeadline(now.Add(udpFlowIdleTimeout))
}

// sendUDPFrame seals frame under entry, the ring entry the flow's peer
// pinned, and sends it back over the datagram lane.
func (s *Server) sendUDPFrame(peerID string, entry *crypto.RingEntry, frame udpwire.Frame) bool {
	wire, err := udpwire.Encode(frame)
	if err != nil {
		logger.Debugf("udp relay encode response failed: %v", err)
		return false
	}
	if entry == nil {
		logger.Debugf("udp relay response without pinned key dropped")
		return false
	}
	enc, err := entry.Keys.SealInto(nil, wire, udpAAD())
	if err != nil {
		logger.Debugf("udp relay encrypt response failed: %v", err)
		return false
	}
	if peerID != "" {
		if pdg, ok := s.ln.(transport.PeerDatagramTransport); ok {
			if err := pdg.SendDatagramTo(peerID, enc); err != nil {
				logger.Debugf("udp relay peer send failed: %v", err)
				return false
			}
			return true
		}
	}
	dg, ok := s.ln.(transport.DatagramTransport)
	if !ok {
		return false
	}
	if err := dg.SendDatagram(enc); err != nil {
		logger.Debugf("udp relay send failed: %v", err)
		return false
	}
	return true
}

func (s *Server) closeUDPFlow(key serverUDPKey) {
	s.udpMu.Lock()
	flow := s.udpFlows[key]
	delete(s.udpFlows, key)
	s.udpMu.Unlock()
	if flow != nil {
		s.finishUDPFlow(flow)
	}
}

func (s *Server) closeAllUDPFlows() {
	s.udpMu.Lock()
	flows := s.udpFlows
	s.udpFlows = make(map[serverUDPKey]*serverUDPFlow)
	s.udpMu.Unlock()
	for _, flow := range flows {
		if flow != nil {
			s.finishUDPFlow(flow)
		}
	}
}

func (s *Server) finishUDPFlow(flow *serverUDPFlow) {
	flow.closeOnce.Do(func() {
		_ = flow.conn.Close()
		bytesIn := flow.bytesIn.Load()
		bytesOut := flow.bytesOut.Load()
		if flow.sessionID == "" || (bytesIn == 0 && bytesOut == 0) {
			return
		}
		// Attribute the flow's bytes to the session's key like a stream:
		// up is client to internet, which is bytesIn here.
		s.meter.add(flow.sessionID, bytesIn, bytesOut)
		if s.onTraffic == nil {
			return
		}
		addr := net.JoinHostPort(flow.endpoint.Host, strconv.Itoa(int(flow.endpoint.Port)))
		s.onTraffic(flow.sessionID, addr, bytesIn, bytesOut)
	})
}

func normalizeMaxUDPFlows(maxFlows int) int {
	if maxFlows <= 0 {
		return defaultMaxUDPFlows
	}
	return maxFlows
}

func (s *Server) udpSessionID(peerID string) string {
	if peerID == "" {
		return s.currentSessionID()
	}
	s.sessMu.RLock()
	ps := s.peerSessions[peerID]
	s.sessMu.RUnlock()
	if ps == nil {
		return ""
	}
	return ps.sid()
}

// pinnedDatagramEntry returns the ring entry pinned by peerID's group ("" is
// the single-link group), or nil while pairing is still open. Datagrams
// always follow the handshake that pins the key, so nil means the sender is
// not an authenticated peer.
func (s *Server) pinnedDatagramEntry(peerID string) *crypto.RingEntry {
	s.sessMu.RLock()
	group := s.group
	if peerID != "" {
		group = nil
		if ps := s.peerSessions[peerID]; ps != nil {
			group = ps.group
		}
	}
	s.sessMu.RUnlock()
	if group == nil {
		return nil
	}
	return group.Pinned()
}

// resolveUDPTarget turns a flow's endpoint into what dialUDPFlow dials,
// through the egress policy a TCP CONNECT goes through (targetAddrs). Behind
// an upstream proxy a name goes out as it is, for the proxy to resolve.
//
// ai-generated: rewritten onto targetAddrs (egress hardening).
func (s *Server) resolveUDPTarget(endpoint udpwire.Endpoint, viaProxy bool) (udpDialTarget, error) {
	if endpoint.Port == 0 || endpoint.Host == "" {
		return udpDialTarget{}, udpwire.ErrInvalidEndpoint
	}
	if viaProxy && !isLiteral(endpoint.Host) {
		return udpDialTarget{endpoint: endpoint}, nil
	}
	ctx, cancel := context.WithTimeout(s.udpBaseCtx(), udpLookupTimeout)
	defer cancel()
	addrs, err := s.targetAddrs(ctx, "ip", endpoint.Host)
	if err != nil {
		return udpDialTarget{}, err
	}
	return udpTargetFromAddr(addrs[0], endpoint.Port), nil
}

func udpTargetFromAddr(addr netip.Addr, port uint16) udpDialTarget {
	endpoint := udpwire.Endpoint{Host: addr.String(), Port: port}
	if addr.Is4() {
		return udpDialTarget{network: "udp4", host: addr.String(), endpoint: endpoint}
	}
	return udpDialTarget{network: "udp6", host: addr.String(), endpoint: endpoint}
}

func encodeSocks5UDPPacket(endpoint udpwire.Endpoint, payload []byte) ([]byte, error) {
	addrType, addr, err := socks5UDPAddr(endpoint.Host)
	if err != nil {
		return nil, err
	}
	packet := make([]byte, 0, 4+len(addr)+2+len(payload))
	packet = append(packet, 0, 0, 0, addrType)
	if addrType == 3 {
		packet = append(packet, byte(len(addr))) //nolint:gosec // domain length is capped by socks5UDPAddr.
	}
	packet = append(packet, addr...)
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], endpoint.Port)
	packet = append(packet, port[:]...)
	packet = append(packet, payload...)
	return packet, nil
}

func decodeSocks5UDPPacket(packet []byte) (udpwire.Endpoint, []byte, error) {
	if len(packet) < 4 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 {
		return udpwire.Endpoint{}, nil, udpwire.ErrInvalidEndpoint
	}
	host, off, err := decodeSocks5UDPHost(packet, 3)
	if err != nil {
		return udpwire.Endpoint{}, nil, err
	}
	if len(packet) < off+2 {
		return udpwire.Endpoint{}, nil, udpwire.ErrInvalidEndpoint
	}
	port := binary.BigEndian.Uint16(packet[off : off+2])
	return udpwire.Endpoint{Host: host, Port: port}, packet[off+2:], nil
}

func socks5UDPAddr(host string) (byte, []byte, error) {
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			return 1, ip4, nil
		}
		if ip16 := ip.To16(); ip16 != nil {
			return 4, ip16, nil
		}
	}
	if host == "" || len(host) > 255 {
		return 0, nil, udpwire.ErrInvalidEndpoint
	}
	return 3, []byte(host), nil
}

func decodeSocks5UDPHost(packet []byte, off int) (string, int, error) {
	if len(packet) <= off {
		return "", 0, udpwire.ErrInvalidEndpoint
	}
	switch packet[off] {
	case 1:
		return decodeSocks5UDPFixedHost(packet, off, 4)
	case 3:
		return decodeSocks5UDPDomainHost(packet, off)
	case 4:
		return decodeSocks5UDPFixedHost(packet, off, 16)
	default:
		return "", 0, udpwire.ErrInvalidEndpoint
	}
}

func decodeSocks5UDPFixedHost(packet []byte, off, size int) (string, int, error) {
	if len(packet) < off+1+size {
		return "", 0, udpwire.ErrInvalidEndpoint
	}
	return net.IP(packet[off+1 : off+1+size]).String(), off + 1 + size, nil
}

func decodeSocks5UDPDomainHost(packet []byte, off int) (string, int, error) {
	if len(packet) < off+2 {
		return "", 0, udpwire.ErrInvalidEndpoint
	}
	size := int(packet[off+1])
	if size == 0 || len(packet) < off+2+size {
		return "", 0, udpwire.ErrInvalidEndpoint
	}
	return string(packet[off+2 : off+2+size]), off + 2 + size, nil
}
