package client

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

const (
	clientTestDNSCloudflare = "1.1.1.1"
	clientTestDNSGoogle     = "8.8.8.8"
	clientTestDNSQuad9      = "9.9.9.9"
)

func clientTestUDPAddr(port int) *net.UDPAddr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}
}

func TestRemoveIdleUDPFlowsForConn(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer func() { _ = conn.Close() }()
	otherConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen other udp: %v", err)
	}
	defer func() { _ = otherConn.Close() }()

	now := time.Now()
	c := &Client{
		udpFlows: map[uint64]clientUDPFlow{
			1: {
				conn:       conn,
				clientAddr: clientTestUDPAddr(10001),
				target:     udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53},
				lastSeen:   now.Add(-udpFlowIdleTimeout - time.Second),
			},
			2: {
				conn:       conn,
				clientAddr: clientTestUDPAddr(10002),
				target:     udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
				lastSeen:   now,
			},
			3: {
				conn:       otherConn,
				clientAddr: clientTestUDPAddr(10003),
				target:     udpwire.Endpoint{Host: clientTestDNSQuad9, Port: 53},
				lastSeen:   now.Add(-udpFlowIdleTimeout - time.Second),
			},
		},
	}

	c.removeIdleUDPFlowsForConn(conn, now)

	if _, ok := c.udpFlows[1]; ok {
		t.Fatal("idle flow for conn was not removed")
	}
	idleKey := clientUDPFlowIndexKey(
		conn,
		clientTestUDPAddr(10001),
		udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53},
	)
	if _, ok := c.udpFlowIndex[idleKey]; ok {
		t.Fatal("idle flow index entry for conn was not removed")
	}
	if _, ok := c.udpFlows[2]; !ok {
		t.Fatal("active flow for conn was removed")
	}
	activeKey := clientUDPFlowIndexKey(
		conn,
		clientTestUDPAddr(10002),
		udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
	)
	if _, ok := c.udpFlowIndex[activeKey]; !ok {
		t.Fatal("active flow index entry for conn was removed")
	}
	if _, ok := c.udpFlows[3]; !ok {
		t.Fatal("idle flow for another conn was removed")
	}
	otherKey := clientUDPFlowIndexKey(
		otherConn,
		clientTestUDPAddr(10003),
		udpwire.Endpoint{Host: clientTestDNSQuad9, Port: 53},
	)
	if _, ok := c.udpFlowIndex[otherKey]; !ok {
		t.Fatal("idle flow index entry for another conn was removed")
	}
}

func BenchmarkParseSocksUDPIPv4(b *testing.B) {
	payload := bytes.Repeat([]byte{0xab}, udpwire.MaxPayloadSize)
	packet := make([]byte, 0, 10+len(payload))
	packet = append(packet, 0, 0, 0, 1, 8, 8, 8, 8)
	var port [2]byte
	binary.BigEndian.PutUint16(port[:], 53)
	packet = append(packet, port[:]...)
	packet = append(packet, payload...)

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		if _, _, err := parseSocksUDP(packet); err != nil {
			b.Fatalf("parseSocksUDP() error = %v", err)
		}
	}
}

func TestUDPFlowIDReusesExistingWhenAtLimit(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer func() { _ = conn.Close() }()

	src := clientTestUDPAddr(10001)
	target := udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53}
	c := &Client{
		maxUDPFlows: 1,
		udpFlows: map[uint64]clientUDPFlow{
			7: {
				conn:       conn,
				clientAddr: src,
				target:     target,
				lastSeen:   time.Now().Add(-time.Second),
			},
		},
	}

	id, ok := c.udpFlowID(conn, src, target)
	if !ok {
		t.Fatal("udpFlowID rejected existing flow at limit")
	}
	if id != 7 {
		t.Fatalf("udpFlowID = %d, want 7", id)
	}
	if got := len(c.udpFlowIndex); got != 1 {
		t.Fatalf("udpFlowIndex len = %d, want 1", got)
	}
}

func TestUDPAssociationSourceAllowsBoundPeer(t *testing.T) {
	tcpConn := tcpAddrConn{remote: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 5000}}
	src, err := udpAssociationAllowedSource(tcpConn, socksRequest{addr: "127.0.0.1", port: 4000})
	if err != nil {
		t.Fatalf("udpAssociationAllowedSource() error = %v", err)
	}

	if !src.allows(clientTestUDPAddr(4000)) {
		t.Fatal("expected matching UDP source to be allowed")
	}
	if src.allows(clientTestUDPAddr(4001)) {
		t.Fatal("expected wrong UDP source port to be rejected")
	}
	if src.allows(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 2), Port: 4000}) {
		t.Fatal("expected wrong UDP source IP to be rejected")
	}
}

func TestUDPAssociationSourceAllowsZeroRequestEndpoint(t *testing.T) {
	tcpConn := tcpAddrConn{remote: &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 5000}}
	src, err := udpAssociationAllowedSource(tcpConn, socksRequest{addr: "0.0.0.0"})
	if err != nil {
		t.Fatalf("udpAssociationAllowedSource() error = %v", err)
	}

	if !src.allows(clientTestUDPAddr(4000)) {
		t.Fatal("expected TCP peer IP with any UDP port to be allowed")
	}
	if src.allows(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 2), Port: 4000}) {
		t.Fatal("expected different source IP to be rejected")
	}
}

func TestUDPFlowIDRejectsNewFlowAtLimit(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer func() { _ = conn.Close() }()

	c := &Client{
		maxUDPFlows: 1,
		udpFlows: map[uint64]clientUDPFlow{
			7: {
				conn:       conn,
				clientAddr: clientTestUDPAddr(10001),
				target:     udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53},
				lastSeen:   time.Now(),
			},
		},
	}

	_, ok := c.udpFlowID(
		conn,
		clientTestUDPAddr(10002),
		udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
	)
	if ok {
		t.Fatal("udpFlowID accepted new flow at limit")
	}
}

func TestRemoveUDPFlowsForConnRemovesIndexEntries(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer func() { _ = conn.Close() }()
	otherConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen other udp: %v", err)
	}
	defer func() { _ = otherConn.Close() }()

	c := &Client{
		udpFlows: map[uint64]clientUDPFlow{
			1: {
				conn:       conn,
				clientAddr: clientTestUDPAddr(10001),
				target:     udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53},
				lastSeen:   time.Now(),
			},
			2: {
				conn:       otherConn,
				clientAddr: clientTestUDPAddr(10002),
				target:     udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
				lastSeen:   time.Now(),
			},
		},
	}

	c.removeUDPFlowsForConn(conn)

	if _, ok := c.udpFlows[1]; ok {
		t.Fatal("flow for conn was not removed")
	}
	if _, ok := c.udpFlows[2]; !ok {
		t.Fatal("flow for other conn was removed")
	}
	removedKey := clientUDPFlowIndexKey(
		conn,
		clientTestUDPAddr(10001),
		udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53},
	)
	if _, ok := c.udpFlowIndex[removedKey]; ok {
		t.Fatal("flow index entry for conn was not removed")
	}
	keptKey := clientUDPFlowIndexKey(
		otherConn,
		clientTestUDPAddr(10002),
		udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
	)
	if _, ok := c.udpFlowIndex[keptKey]; !ok {
		t.Fatal("flow index entry for other conn was removed")
	}
}

func BenchmarkUDPFlowIDExistingAtLimit(b *testing.B) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		b.Fatalf("listen udp: %v", err)
	}
	defer func() { _ = conn.Close() }()

	src := clientTestUDPAddr(10001)
	target := udpwire.Endpoint{Host: clientTestDNSCloudflare, Port: 53}
	c := &Client{
		maxUDPFlows: defaultMaxUDPFlows,
		udpFlows:    make(map[uint64]clientUDPFlow, defaultMaxUDPFlows),
	}
	c.udpFlows[1] = clientUDPFlow{
		conn:       conn,
		clientAddr: src,
		target:     target,
		lastSeen:   time.Now(),
	}
	for id := uint64(2); id <= defaultMaxUDPFlows; id++ {
		c.udpFlows[id] = clientUDPFlow{
			conn:       conn,
			clientAddr: clientTestUDPAddr(int(10000 + id)),
			target:     udpwire.Endpoint{Host: clientTestDNSGoogle, Port: 53},
			lastSeen:   time.Now(),
		}
	}

	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		if _, ok := c.udpFlowID(conn, src, target); !ok {
			b.Fatal("udpFlowID rejected existing flow")
		}
	}
}

type tcpAddrConn struct {
	remote *net.TCPAddr
}

func (c tcpAddrConn) Read(_ []byte) (int, error)         { return 0, net.ErrClosed }
func (c tcpAddrConn) Write(p []byte) (int, error)        { return len(p), nil }
func (c tcpAddrConn) Close() error                       { return nil }
func (c tcpAddrConn) LocalAddr() net.Addr                { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)} }
func (c tcpAddrConn) RemoteAddr() net.Addr               { return c.remote }
func (c tcpAddrConn) SetDeadline(_ time.Time) error      { return nil }
func (c tcpAddrConn) SetReadDeadline(_ time.Time) error  { return nil }
func (c tcpAddrConn) SetWriteDeadline(_ time.Time) error { return nil }
