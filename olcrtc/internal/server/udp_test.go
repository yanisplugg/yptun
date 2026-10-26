package server

import (
	"bytes"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

const (
	testUDPPeerID        = "peer-1"
	testUDPSessionID     = "session-1"
	testUDPDNSGoogle     = "8.8.8.8"
	testUDPDNSCloudflare = "1.1.1.1"
)

func TestCloseUDPFlowReportsTrafficOnce(t *testing.T) {
	left, right := net.Pipe()
	defer func() { _ = right.Close() }()

	key := serverUDPKey{peerID: testUDPPeerID, flowID: 42}
	flow := &serverUDPFlow{
		key:       key,
		conn:      left,
		endpoint:  udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53},
		sessionID: testUDPSessionID,
	}
	flow.bytesIn.Store(11)
	flow.bytesOut.Store(17)

	var calls int
	s := &Server{
		udpFlows: map[serverUDPKey]*serverUDPFlow{key: flow},
		onTraffic: func(sessionID, addr string, bytesIn, bytesOut uint64) {
			calls++
			if sessionID != testUDPSessionID {
				t.Fatalf("sessionID = %q, want %s", sessionID, testUDPSessionID)
			}
			if addr != "8.8.8.8:53" {
				t.Fatalf("addr = %q, want %s:53", addr, testUDPDNSGoogle)
			}
			if bytesIn != 11 || bytesOut != 17 {
				t.Fatalf("traffic = %d/%d, want 11/17", bytesIn, bytesOut)
			}
		},
	}

	s.closeUDPFlow(key)
	s.closeUDPFlow(key)
	s.finishUDPFlow(flow)

	if calls != 1 {
		t.Fatalf("onTraffic calls = %d, want 1", calls)
	}
	if _, ok := s.udpFlows[key]; ok {
		t.Fatal("flow still present after close")
	}
}

func TestGetOrCreateUDPFlowRejectsNewFlowAtLimit(t *testing.T) {
	key := serverUDPKey{peerID: testUDPPeerID, flowID: 1}
	s := &Server{
		maxUDPFlows: 1,
		udpFlows: map[serverUDPKey]*serverUDPFlow{
			key: {
				key:       key,
				conn:      noopConn{},
				endpoint:  udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53},
				sessionID: testUDPSessionID,
			},
		},
	}

	_, err := s.getOrCreateUDPFlow(
		serverUDPKey{peerID: testUDPPeerID, flowID: 2},
		udpwire.Endpoint{Host: testUDPDNSCloudflare, Port: 53},
		testUDPSessionID,
		nil,
	)
	if !errors.Is(err, errTooManyUDPFlows) {
		t.Fatalf("getOrCreateUDPFlow() error = %v, want %v", err, errTooManyUDPFlows)
	}
}

func TestGetOrCreateUDPFlowRejectsPendingAtLimit(t *testing.T) {
	s := &Server{
		maxUDPFlows:     1,
		udpFlows:        map[serverUDPKey]*serverUDPFlow{},
		udpPendingFlows: 1,
	}

	_, err := s.getOrCreateUDPFlow(
		serverUDPKey{peerID: testUDPPeerID, flowID: 2},
		udpwire.Endpoint{Host: testUDPDNSCloudflare, Port: 53},
		testUDPSessionID,
		nil,
	)
	if !errors.Is(err, errTooManyUDPFlows) {
		t.Fatalf("getOrCreateUDPFlow() error = %v, want %v", err, errTooManyUDPFlows)
	}
}

func TestGetOrCreateUDPFlowReusesExistingWhenAtLimit(t *testing.T) {
	key := serverUDPKey{peerID: testUDPPeerID, flowID: 1}
	flow := &serverUDPFlow{
		key:       key,
		conn:      noopConn{},
		endpoint:  udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53},
		sessionID: testUDPSessionID,
	}
	s := &Server{
		maxUDPFlows: 1,
		udpFlows:    map[serverUDPKey]*serverUDPFlow{key: flow},
	}

	got, err := s.getOrCreateUDPFlow(key, flow.endpoint, testUDPSessionID, nil)
	if err != nil {
		t.Fatalf("getOrCreateUDPFlow() error = %v", err)
	}
	if got != flow {
		t.Fatal("getOrCreateUDPFlow() did not reuse existing flow")
	}
}

func TestSocks5UDPPacketRoundTrip(t *testing.T) {
	endpoint := udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53}
	payload := []byte("dns")
	packet, err := encodeSocks5UDPPacket(endpoint, payload)
	if err != nil {
		t.Fatalf("encodeSocks5UDPPacket() error = %v", err)
	}
	gotEndpoint, gotPayload, err := decodeSocks5UDPPacket(packet)
	if err != nil {
		t.Fatalf("decodeSocks5UDPPacket() error = %v", err)
	}
	if gotEndpoint != endpoint {
		t.Fatalf("endpoint = %+v, want %+v", gotEndpoint, endpoint)
	}
	if !bytes.Equal(gotPayload, payload) {
		t.Fatalf("payload = %q, want %q", gotPayload, payload)
	}
}

func TestSocks5UDPConnWrapsPayload(t *testing.T) {
	udpLeft, udpRight := net.Pipe()
	tcpLeft, tcpRight := net.Pipe()
	defer func() { _ = udpRight.Close() }()
	defer func() { _ = tcpRight.Close() }()
	conn := &socks5UDPConn{
		tcpConn:  tcpLeft,
		udpConn:  udpLeft,
		endpoint: udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53},
	}
	defer func() { _ = conn.Close() }()

	assertSocks5UDPWrite(t, conn, udpRight)
	assertSocks5UDPRead(t, conn, udpRight)
}

func assertSocks5UDPWrite(t *testing.T, conn *socks5UDPConn, peer net.Conn) {
	t.Helper()
	writeDone := make(chan error, 1)
	go func() {
		_, err := conn.Write([]byte("query"))
		writeDone <- err
	}()
	buf := make([]byte, 128)
	n, err := peer.Read(buf)
	if err != nil {
		t.Fatalf("read wrapped packet: %v", err)
	}
	endpoint, payload, err := decodeSocks5UDPPacket(buf[:n])
	if err != nil {
		t.Fatalf("decode wrapped packet: %v", err)
	}
	if endpoint.Host != testUDPDNSGoogle || endpoint.Port != 53 || string(payload) != "query" {
		t.Fatalf("wrapped endpoint=%+v payload=%q", endpoint, payload)
	}
	if err := <-writeDone; err != nil {
		t.Fatalf("Write() error = %v", err)
	}
}

func assertSocks5UDPRead(t *testing.T, conn *socks5UDPConn, peer net.Conn) {
	t.Helper()
	packet, err := encodeSocks5UDPPacket(udpwire.Endpoint{Host: testUDPDNSGoogle, Port: 53}, []byte("response"))
	if err != nil {
		t.Fatalf("encode response: %v", err)
	}
	readDone := make(chan []byte, 1)
	go func() {
		readBuf := make([]byte, 128)
		n, readErr := conn.Read(readBuf)
		if readErr != nil {
			readDone <- nil
			return
		}
		readDone <- readBuf[:n]
	}()
	if _, err := peer.Write(packet); err != nil {
		t.Fatalf("write response: %v", err)
	}
	if got := <-readDone; string(got) != "response" {
		t.Fatalf("Read() = %q, want response", got)
	}
}

func TestReadSocks5UDPAssociateReply(t *testing.T) {
	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1, 0x12, 0x34}
	addr, err := readSocks5UDPAssociateReply(bytes.NewReader(reply))
	if err != nil {
		t.Fatalf("readSocks5UDPAssociateReply() error = %v", err)
	}
	if addr.String() != "127.0.0.1:4660" {
		t.Fatalf("addr = %s, want 127.0.0.1:4660", addr.String())
	}
}

// The UDP relay goes through the egress policy a TCP CONNECT does: a blocked
// literal is refused, behind an upstream proxy too, where a name is left to
// the proxy. ai-generated: rewritten onto resolveUDPTarget (egress hardening).
func TestResolveUDPTargetBlocksSpecialTargets(t *testing.T) {
	s := &Server{}
	tests := []string{
		"127.0.0.1",
		"10.0.0.1",
		"100.64.0.1",
		"169.254.1.1",
		"224.0.0.1",
		"255.255.255.255",
		"::1",
		"fc00::1",
		"::ffff:127.0.0.1",
		"::ffff:10.0.0.1",
	}
	for _, tc := range tests {
		t.Run(tc, func(t *testing.T) {
			for _, viaProxy := range []bool{false, true} {
				_, err := s.resolveUDPTarget(udpwire.Endpoint{Host: tc, Port: 53}, viaProxy)
				if !errors.Is(err, errBlockedTarget) {
					t.Fatalf("resolveUDPTarget(%s, proxy=%v) error = %v, want %v", tc, viaProxy, err, errBlockedTarget)
				}
			}
		})
	}
}

// A name that resolves to a blocked address is refused as the literal is;
// behind a proxy it is never resolved here. ai-generated (egress hardening).
func TestResolveUDPTargetBlocksNamesResolvingInside(t *testing.T) {
	stub := &stubLookup{answers: map[string][]net.IP{"inside.test": {net.IPv4(127, 0, 0, 1)}}}
	s := &Server{resolver: stub}
	if _, err := s.resolveUDPTarget(udpwire.Endpoint{Host: "inside.test", Port: 53}, false); !errors.Is(err, errBlockedTarget) {
		t.Fatalf("resolveUDPTarget(inside.test) error = %v, want %v", err, errBlockedTarget)
	}
	target, err := s.resolveUDPTarget(udpwire.Endpoint{Host: "inside.test", Port: 53}, true)
	if err != nil || target.endpoint.Host != "inside.test" || stub.calls() != 1 {
		t.Fatalf("via proxy: target = %+v, err = %v, lookups = %d; want the name passed on, unresolved",
			target, err, stub.calls())
	}
}

func TestResolveUDPTargetSelectsNetwork(t *testing.T) {
	s := &Server{}
	const udp4 = "udp4"
	tests := []struct {
		addr        string
		wantNetwork string
		wantHost    string
	}{
		{addr: testUDPDNSGoogle, wantNetwork: udp4, wantHost: testUDPDNSGoogle},
		{addr: "2001:4860:4860::8888", wantNetwork: "udp6", wantHost: "2001:4860:4860::8888"},
		{addr: "::ffff:8.8.8.8", wantNetwork: udp4, wantHost: testUDPDNSGoogle},
	}
	for _, tt := range tests {
		t.Run(tt.addr, func(t *testing.T) {
			got, err := s.resolveUDPTarget(udpwire.Endpoint{Host: tt.addr, Port: 53}, false)
			if err != nil {
				t.Fatalf("resolveUDPTarget() error = %v", err)
			}
			if got.network != tt.wantNetwork || got.host != tt.wantHost || got.endpoint.Port != 53 {
				t.Fatalf("target = %+v, want %s %s:53", got, tt.wantNetwork, tt.wantHost)
			}
		})
	}
}

type noopConn struct{}

func (noopConn) Read(_ []byte) (int, error)         { return 0, net.ErrClosed }
func (noopConn) Write(p []byte) (int, error)        { return len(p), nil }
func (noopConn) Close() error                       { return nil }
func (noopConn) LocalAddr() net.Addr                { return nil }
func (noopConn) RemoteAddr() net.Addr               { return nil }
func (noopConn) SetDeadline(_ time.Time) error      { return nil }
func (noopConn) SetReadDeadline(_ time.Time) error  { return nil }
func (noopConn) SetWriteDeadline(_ time.Time) error { return nil }
