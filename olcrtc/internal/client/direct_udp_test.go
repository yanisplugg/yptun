package client

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/xtaci/smux"
	"golang.org/x/net/dns/dnsmessage"

	"github.com/openlibrecommunity/olcrtc/internal/fakedns"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/route"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// ai-generated: whole file, cover for direct UDP flows and direct DNS (olcbox#28).

// udpEchoServer answers every datagram with its payload.
func udpEchoServer(t *testing.T) int {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, readErr := conn.ReadFromUDP(buf)
			if readErr != nil {
				return
			}
			_, _ = conn.WriteToUDP(buf[:n], from)
		}
	}()
	return conn.LocalAddr().(*net.UDPAddr).Port
}

func newDirectUDPTestClient(t *testing.T, rules *route.Rules, lookup protect.Lookup) (*Client, *dnsTestLane) {
	t.Helper()
	lane := &dnsTestLane{}
	c := newDirectTestClient(t, rules, lookup)
	c.ln = lane
	c.udpFlows = map[uint64]clientUDPFlow{}
	return c, lane
}

// readSocksUDP reads one SOCKS UDP datagram off the client socket.
func readSocksUDP(t *testing.T, cli *net.UDPConn) (udpwire.Endpoint, []byte) {
	t.Helper()
	_ = cli.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 4096)
	n, _, err := cli.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("read answer: %v", err)
	}
	target, payload, err := parseSocksUDP(buf[:n])
	if err != nil {
		t.Fatalf("parse answer: %v", err)
	}
	return target, payload
}

func TestDirectUDPEchoesThroughTheAssociation(t *testing.T) {
	port := udpEchoServer(t)
	c, lane := newDirectUDPTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	assoc, cli := dnsTestSockets(t)
	src := cli.LocalAddr().(*net.UDPAddr)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	//nolint:gosec // G115: a test port
	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, directTestLoopback, uint16(port), []byte("ping")))
	target, payload := readSocksUDP(t, cli)
	if string(payload) != "ping" || target.Host != directTestLoopback || int(target.Port) != port {
		t.Fatalf("answer = %q from %s:%d", payload, target.Host, target.Port)
	}
	if lane.count() != 0 {
		t.Fatalf("the lane carried %d packets", lane.count())
	}
	if len(c.directUDP) != 1 || len(c.udpFlows) != 0 {
		t.Fatalf("direct flows = %d, lane flows = %d", len(c.directUDP), len(c.udpFlows))
	}
	// The second packet rides the same flow.
	//nolint:gosec // G115: a test port
	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, directTestLoopback, uint16(port), []byte("pong")))
	if _, payload := readSocksUDP(t, cli); string(payload) != "pong" {
		t.Fatalf("second answer = %q", payload)
	}
	if len(c.directUDP) != 1 {
		t.Fatalf("direct flows after two packets = %d", len(c.directUDP))
	}
	cancel()
	waitTracked(t, c)
}

func TestDirectUDPByNameResolvesThroughTheDialer(t *testing.T) {
	port := udpEchoServer(t)
	c, lane := newDirectUDPTestClient(t, mustRules(t, "full:udp.test\n"),
		staticLookup{"udp.test": {net.IPv4(127, 0, 0, 1)}})
	assoc, cli := dnsTestSockets(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	//nolint:gosec // G115: a test port
	c.forwardLocalUDP(ctx, lane, assoc, cli.LocalAddr().(*net.UDPAddr), socksDatagram(t, "udp.test", uint16(port), []byte("ping")))
	target, payload := readSocksUDP(t, cli)
	if string(payload) != "ping" || target.Host != "udp.test" {
		t.Fatalf("answer = %q from %s", payload, target.Host)
	}
	cancel()
	waitTracked(t, c)
}

func TestDirectUDPUnmatchedTargetTakesTheLane(t *testing.T) {
	c, lane := newDirectUDPTestClient(t, mustRules(t, "10.0.0.0/8\n"), staticLookup{})
	assoc, cli := dnsTestSockets(t)
	c.forwardLocalUDP(context.Background(), lane, assoc, cli.LocalAddr().(*net.UDPAddr),
		socksDatagram(t, "8.8.8.8", 4444, []byte("x")))
	if lane.count() != 1 || len(c.directUDP) != 0 {
		t.Fatalf("lane = %d, direct = %d", lane.count(), len(c.directUDP))
	}
}

func TestDirectUDPTableFullTakesTheLane(t *testing.T) {
	port := udpEchoServer(t)
	c, lane := newDirectUDPTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	c.maxUDPFlows = 1
	assoc, cli := dnsTestSockets(t)
	src := cli.LocalAddr().(*net.UDPAddr)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	//nolint:gosec // G115: a test port
	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, directTestLoopback, uint16(port), []byte("a")))
	readSocksUDP(t, cli)
	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, "127.0.0.2", 5555, []byte("b")))
	if lane.count() != 1 || len(c.directUDP) != 1 {
		t.Fatalf("lane = %d, direct = %d", lane.count(), len(c.directUDP))
	}
	cancel()
	waitTracked(t, c)
}

func TestDirectUDPFlowsAreSweptAndClosedWithTheAssociation(t *testing.T) {
	port := udpEchoServer(t)
	c, lane := newDirectUDPTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	assoc, cli := dnsTestSockets(t)
	src := cli.LocalAddr().(*net.UDPAddr)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	//nolint:gosec // G115: a test port
	packet := socksDatagram(t, directTestLoopback, uint16(port), []byte("a"))

	c.forwardLocalUDP(ctx, lane, assoc, src, packet)
	readSocksUDP(t, cli)
	first := onlyDirectFlow(t, c)
	c.udpMu.Lock()
	first.lastSeen = time.Now().Add(-udpFlowIdleTimeout - time.Second)
	c.udpMu.Unlock()
	c.removeIdleUDPFlows(time.Now())
	if len(c.directUDP) != 0 {
		t.Fatal("the idle direct flow was not swept")
	}
	if _, err := first.remote.Write([]byte("x")); err == nil {
		t.Fatal("the swept flow's socket is still open")
	}

	c.forwardLocalUDP(ctx, lane, assoc, src, packet)
	readSocksUDP(t, cli)
	second := onlyDirectFlow(t, c)
	c.removeUDPFlowsForConn(assoc)
	if len(c.directUDP) != 0 {
		t.Fatal("the direct flow outlived its association")
	}
	if _, err := second.remote.Write([]byte("x")); err == nil {
		t.Fatal("the association's flow socket is still open")
	}
	if lane.count() != 0 {
		t.Fatalf("the lane carried %d packets", lane.count())
	}
	cancel()
	waitTracked(t, c)
}

func onlyDirectFlow(t *testing.T, c *Client) *directUDPFlow {
	t.Helper()
	c.udpMu.Lock()
	defer c.udpMu.Unlock()
	if len(c.directUDP) != 1 {
		t.Fatalf("direct flows = %d, want 1", len(c.directUDP))
	}
	for _, flow := range c.directUDP {
		return flow
	}
	return nil
}

// tcpPair is a loopback TCP connection: the association code reads the
// peer's address, which a pipe does not have.
func tcpPair(t *testing.T) net.Conn {
	t.Helper()
	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp4", directTestLoopback+":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	client, err := (&net.Dialer{}).DialContext(context.Background(), "tcp4", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	server, err := ln.Accept()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	return server
}

func TestUDPAssociateWithRulesNeedsNoSession(t *testing.T) {
	server := tcpPair(t)
	c, _ := newDirectUDPTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	if _, _, ok := c.prepareUDPAssociate(ctx, server, socksRequest{cmd: socksCmdUDPAssociate}); !ok {
		t.Fatal("an association with rules on waited for the session")
	}
	off, _ := newDirectUDPTestClient(t, nil, staticLookup{})
	if _, _, ok := off.prepareUDPAssociate(ctx, server, socksRequest{cmd: socksCmdUDPAssociate}); ok {
		t.Fatal("an association with rules off did not wait for the session")
	}
}

// aQueryFor is one A question for name as a resolver would send it.
func aQueryFor(t *testing.T, name string) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 0x4242, RecursionDesired: true})
	if err := b.StartQuestions(); err != nil {
		t.Fatal(err)
	}
	q := dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}
	if err := b.Question(q); err != nil {
		t.Fatal(err)
	}
	msg, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

func firstAnswerA(t *testing.T, msg []byte) net.IP {
	t.Helper()
	var p dnsmessage.Parser
	if _, err := p.Start(msg); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if err := p.SkipAllQuestions(); err != nil {
		t.Fatal(err)
	}
	for {
		rr, err := p.Answer()
		if err != nil {
			return nil
		}
		if a, ok := rr.Body.(*dnsmessage.AResource); ok {
			return net.IP(a.A[:])
		}
	}
}

func TestDNSForADirectNameAsksTheRing(t *testing.T) {
	ring, err := fakedns.Start(map[string]string{"a.ru": "10.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ring.Close() })
	streamed := make(chan struct{}, 4)
	c, srv := newDNSTestClient(t, func(stream *smux.Stream) error {
		streamed <- struct{}{}
		_, readErr := readFramedQuery(stream)
		return readErr
	})
	c.rules = mustRules(t, "domain:ru\n")
	c.exchanger = protect.NewResolver(ring.Addr)
	lane := &dnsTestLane{}
	assoc, cli := dnsTestSockets(t)
	src := cli.LocalAddr().(*net.UDPAddr)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, aQueryFor(t, "a.ru.")))
	target, answer := readSocksUDP(t, cli)
	if ip := firstAnswerA(t, answer); !ip.Equal(net.IPv4(10, 0, 0, 1)) {
		t.Fatalf("answer ip = %v", ip)
	}
	if target.Host != dnsTestResolver || target.Port != dnsPort {
		t.Fatalf("answer came from %s:%d, want the resolver the query named", target.Host, target.Port)
	}
	if ring.Queries() != 1 || len(srv.accepted) != 0 {
		t.Fatalf("ring queries = %d, streams = %d", ring.Queries(), len(srv.accepted))
	}

	// A name the rules do not cover still takes the stream.
	c.forwardLocalUDP(ctx, lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, aQueryFor(t, "example.com.")))
	select {
	case <-streamed:
	case <-time.After(3 * time.Second):
		t.Fatal("the query for a foreign name did not take the stream")
	}
	if ring.Queries() != 1 || lane.count() != 0 {
		t.Fatalf("ring queries = %d, lane = %d", ring.Queries(), lane.count())
	}
	cancel()
	waitTracked(t, c)
}

func TestDNSDirectWithoutServersTakesTheStream(t *testing.T) {
	streamed := make(chan struct{}, 4)
	c, _ := newDNSTestClient(t, func(stream *smux.Stream) error {
		streamed <- struct{}{}
		_, readErr := readFramedQuery(stream)
		return readErr
	})
	c.rules = mustRules(t, "domain:ru\n")
	c.exchanger = protect.NewResolver("")
	assoc, cli := dnsTestSockets(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c.forwardLocalUDP(ctx, &dnsTestLane{}, assoc, cli.LocalAddr().(*net.UDPAddr),
		socksDatagram(t, dnsTestResolver, dnsPort, aQueryFor(t, "a.ru.")))
	select {
	case <-streamed:
	case <-time.After(3 * time.Second):
		t.Fatal("with no servers on the ring the query did not take the stream")
	}
	cancel()
	waitTracked(t, c)
}
