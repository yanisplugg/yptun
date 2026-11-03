package client

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/datachannel"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

// ai-generated: the whole file (a UDP ASSOCIATE that never frees its SOCKS
// slot, olcrtc#49).

// udpLaneTestTarget is a target no rule names and no resolver serves, so a
// datagram for it can only take the lane.
const udpLaneTestTarget = "192.0.2.1"

// heldLane is a datagram lane that exists and does not take anything until
// the test opens it: a SaluteJazz lane over its mark, or one still
// negotiating behind the reliable one.
type heldLane struct {
	transport.Transport
	open  atomic.Bool
	polls atomic.Int32
	sent  atomic.Int32
}

func (*heldLane) Features() transport.Features { return transport.Features{Datagram: true} }

func (l *heldLane) DatagramCanSend() bool {
	l.polls.Add(1)
	return l.open.Load()
}

func (l *heldLane) SendDatagram([]byte) error {
	l.sent.Add(1)
	return nil
}

// jitsiLikeLink is the transport the association used to hang on: a
// datachannel over an engine with no datagram lane. The datachannel has the
// lane's methods whatever its engine, and there DatagramCanSend never turns
// true.
func jitsiLikeLink(t *testing.T) transport.Transport {
	t.Helper()
	room := &listenerTestRoom{sessions: make(map[*listenerTestSession]struct{})}
	provider := "udp-lane-test-" + t.Name()
	enginebuiltin.Register(provider, func(_ context.Context, cfg enginebuiltin.Config) (engine.Session, error) {
		return room.newSession(cfg.OnData), nil
	})
	link, err := datachannel.New(context.Background(), transport.Config{Provider: provider, RoomURL: "room"})
	if err != nil {
		t.Fatalf("datachannel.New() error = %v", err)
	}
	t.Cleanup(func() { _ = link.Close() })
	if err := link.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	dg, ok := link.(transport.DatagramTransport)
	if !ok || dg.DatagramCanSend() || link.Features().Datagram || !link.CanSend() {
		t.Fatal("the link is not a connected datachannel that says it has no lane")
	}
	return link
}

// serveSocksForTest runs c's accept loop, where the SOCKS slots are counted,
// on a loopback listener until the test ends.
func serveSocksForTest(t *testing.T, c *Client) string {
	t.Helper()
	listener, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp4", directTestLoopback+":0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.goTracked(func() { c.acceptLoop(ctx, listener) })
	t.Cleanup(func() {
		cancel()
		_ = listener.Close()
		c.closeSocksConns()
		waitTracked(t, c)
	})
	return listener.Addr().String()
}

// socksSlots is how many SOCKS connections hold a slot.
func socksSlots(c *Client) int {
	c.socksMu.Lock()
	defer c.socksMu.Unlock()
	return len(c.socksConns)
}

// waitSocksSlots waits for the slots held to come down to want.
func waitSocksSlots(t *testing.T, c *Client, want int, within time.Duration, why string) {
	t.Helper()
	deadline := time.Now().Add(within)
	for socksSlots(c) != want {
		if time.Now().After(deadline) {
			t.Fatalf("%d SOCKS slots held %s after %s, want %d", socksSlots(c), within, why, want)
		}
		time.Sleep(time.Millisecond)
	}
}

// udpAssociate dials addr, asks for a UDP ASSOCIATE from any source and
// returns the control connection with the reply.
func udpAssociate(t *testing.T, addr string) (net.Conn, []byte) {
	t.Helper()
	conn, err := (&net.Dialer{Timeout: time.Second}).DialContext(context.Background(), "tcp4", addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	_ = conn.SetDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write([]byte{socksVersion, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(conn, greeting); err != nil || greeting[1] != 0 {
		t.Fatalf("greeting = %v, %v", greeting, err)
	}
	if _, err := conn.Write([]byte{socksVersion, socksCmdUDPAssociate, 0, socksAddrIPv4, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatalf("no reply to the UDP ASSOCIATE within a second: %v", err)
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, reply
}

// Over a link with no datagram lane - Jitsi's datachannel, which has the
// lane's methods whatever its engine - an association still carries all that
// never touches the lane: a resolver query over the stream (hev in udp mode
// sends every system query that way, and so does the gate's S5 on Jitsi), a
// direct flow. Refusing the association took those with it. Only a datagram
// for the lane is dropped, at once and with no flow of its own: it used to
// wait for the run to end, and the read loop and the SOCKS slot with it.
func TestUDPAssociateWithoutADatagramLaneDropsOnlyWhatIsForTheLane(t *testing.T) {
	t.Run("with the session up, a query takes the stream", func(t *testing.T) {
		c, _ := newDNSTestClient(t, func(stream *smux.Stream) error {
			if _, err := readFramedQuery(stream); err != nil {
				return err
			}
			return writeFramed(stream, dnsTestResponse)
		})
		resolver := udpwire.Endpoint{Host: dnsTestResolver, Port: dnsPort}
		checkLanelessAssociation(t, c, resolver, dnsTestQuery, dnsTestResponse)
	})
	t.Run("with the session down, a direct flow", func(t *testing.T) {
		port := udpEchoServer(t)
		c := newDirectTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
		c.udpFlows = map[uint64]clientUDPFlow{}
		//nolint:gosec // G115: a test port
		echo := udpwire.Endpoint{Host: directTestLoopback, Port: uint16(port)}
		checkLanelessAssociation(t, c, echo, []byte("ping"), []byte("ping"))
	})
}

// checkLanelessAssociation runs c's accept loop over a lane-less link and
// sends, on one association, a datagram for the lane and then one for
// offLane, which is answered without it. The answer comes at once, the
// datagram for the lane leaves no flow, and the SOCKS slot comes back when
// the control connection closes.
func checkLanelessAssociation(t *testing.T, c *Client, offLane udpwire.Endpoint, payload, answer []byte) {
	t.Helper()
	c.ln = jitsiLikeLink(t)
	// A datagram that waited for the lane would hold the read loop, and the
	// answer behind it, for a minute.
	c.datagramReadyTimeout = time.Minute

	conn, reply := udpAssociate(t, serveSocksForTest(t, c))
	if reply[1] != socksRepSuccess {
		t.Fatalf("UDP ASSOCIATE on a lane-less link: REP = %d, want %d", reply[1], socksRepSuccess)
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(binary.BigEndian.Uint16(reply[8:10]))}
	_, cli := dnsTestSockets(t)
	for _, packet := range [][]byte{
		socksDatagram(t, udpLaneTestTarget, 4444, []byte("x")),
		socksDatagram(t, offLane.Host, offLane.Port, payload),
	} {
		if _, err := cli.WriteToUDP(packet, relay); err != nil {
			t.Fatal(err)
		}
	}
	_ = cli.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 4096)
	n, _, err := cli.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no answer from %s:%d within 2 s, behind a datagram for the lane: %v",
			offLane.Host, offLane.Port, err)
	}
	from, got, err := parseSocksUDP(buf[:n])
	if err != nil || from != offLane || !bytes.Equal(got, answer) {
		t.Fatalf("answer = %x from %s:%d (%v), want %x from %s:%d",
			got, from.Host, from.Port, err, answer, offLane.Host, offLane.Port)
	}
	c.udpMu.Lock()
	flows := len(c.udpFlows)
	c.udpMu.Unlock()
	if flows != 0 {
		t.Fatalf("lane flows after a datagram for a link with no lane = %d, want 0", flows)
	}

	_ = conn.Close()
	waitSocksSlots(t, c, 0, 2*time.Second, "the control connection closed")
}

// An association whose lane never opens used to hold its first datagram
// until the whole run ended: the read loop sat in the wait, the closed
// control connection closed a socket nobody read, and the SOCKS slot stayed
// taken. 512 of them refused every new SOCKS connection, TCP included. The
// wait now ends with the association.
func TestAnAssociationWhoseLaneNeverOpensEndsWithItsControlConnection(t *testing.T) {
	lane := &heldLane{}
	c := newDirectTestClient(t, mustRules(t, "10.0.0.0/8\n"), staticLookup{})
	c.ln = lane
	c.udpFlows = map[uint64]clientUDPFlow{}
	// Only the association's end can let the datagram go within this test.
	c.datagramReadyTimeout = time.Minute

	conn, reply := udpAssociate(t, serveSocksForTest(t, c))
	if reply[1] != socksRepSuccess {
		t.Fatalf("UDP ASSOCIATE: REP = %d, want %d", reply[1], socksRepSuccess)
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(binary.BigEndian.Uint16(reply[8:10]))}
	_, cli := dnsTestSockets(t)
	if _, err := cli.WriteToUDP(socksDatagram(t, udpLaneTestTarget, 4444, []byte("x")), relay); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for lane.polls.Load() < 2 {
		if time.Now().After(deadline) {
			t.Fatal("the datagram never came to wait for the lane")
		}
		time.Sleep(time.Millisecond)
	}
	if got := socksSlots(c); got != 1 {
		t.Fatalf("SOCKS slots held by the association = %d, want 1", got)
	}

	_ = conn.Close()
	waitSocksSlots(t, c, 0, 2*time.Second, "the control connection closed")
	if lane.sent.Load() != 0 {
		t.Fatal("a datagram went down a lane that never opened")
	}
}

// The lane is lossy by contract: a datagram waits for it a short while, so a
// lane over its mark for a moment still takes it, and past that it is
// dropped and the association's read loop moves on to the next one.
func TestADatagramWaitsForTheLaneNoLongerThanItsDeadline(t *testing.T) {
	const wait = 150 * time.Millisecond
	lane := &heldLane{}
	c := newDirectTestClient(t, nil, staticLookup{})
	c.ln = lane
	c.udpFlows = map[uint64]clientUDPFlow{}
	c.datagramReadyTimeout = wait
	assoc, cli := dnsTestSockets(t)
	src := cli.LocalAddr().(*net.UDPAddr)
	packet := socksDatagram(t, udpLaneTestTarget, 4444, []byte("x"))
	// The association outlives the test: only the deadline can end the wait.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	start := time.Now()
	done := make(chan struct{})
	go func() {
		c.forwardLocalUDP(ctx, lane, assoc, src, packet)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("a datagram still waits for the lane 2 s into a %s deadline", wait)
	}
	if waited := time.Since(start); waited < wait {
		t.Fatalf("the datagram was dropped after %s, before its %s deadline", waited, wait)
	}
	if lane.sent.Load() != 0 {
		t.Fatal("a datagram went down a lane that never opened")
	}

	// A lane that opens inside the deadline takes the datagram.
	c.datagramReadyTimeout = 5 * time.Second
	opened := time.AfterFunc(50*time.Millisecond, func() { lane.open.Store(true) })
	defer opened.Stop()
	c.forwardLocalUDP(ctx, lane, assoc, src, packet)
	if lane.sent.Load() != 1 {
		t.Fatalf("datagrams sent once the lane opened = %d, want 1", lane.sent.Load())
	}
}

// The sweeper is the client's: handleUDPAssociate starts it once, on the
// run's context, before its read loop. A direct flow runs on its
// association's context, and a sweeper it started would end with the first
// association to close, never to start again behind the sync.Once; idle lane
// flows would then fill the table and idle direct sockets stay open.
func TestADirectFlowLeavesTheSweeperToTheRun(t *testing.T) {
	port := udpEchoServer(t)
	c, lane := newDirectUDPTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	assoc, cli := dnsTestSockets(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	//nolint:gosec // G115: a test port
	c.forwardLocalUDP(ctx, lane, assoc, cli.LocalAddr().(*net.UDPAddr), socksDatagram(t, directTestLoopback, uint16(port), []byte("ping")))
	if _, payload := readSocksUDP(t, cli); string(payload) != "ping" {
		t.Fatalf("direct answer = %q, want ping", payload)
	}
	cancel()
	waitTracked(t, c)

	started := false
	c.udpSweepOnce.Do(func() { started = true })
	if !started {
		t.Fatal("a direct flow started the client's sweeper on its association's context")
	}
}
