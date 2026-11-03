package client

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/udpwire"
)

const dnsTestResolver = "9.9.9.9"

var (
	// A query for "a." type A, and an answer with the same id: enough for the
	// framing to be checked byte for byte; nothing here parses DNS.
	dnsTestQuery    = []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 1, 'a', 0, 0, 1, 0, 1}
	dnsTestResponse = []byte{0x12, 0x34, 0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0, 0, 0, 0, 1, 'a', 0, 0, 1, 0, 1,
		0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, 0, 1}

	errDNSTestBadConnect = errors.New("unexpected connect request")
	errDNSTestBadFraming = errors.New("query framing is not RFC 7766")
)

// dnsTestLane records what would have gone down the datagram lane.
type dnsTestLane struct {
	transport.Transport
	mu   sync.Mutex
	sent [][]byte
}

func (l *dnsTestLane) SendDatagram(data []byte) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sent = append(l.sent, append([]byte(nil), data...))
	return nil
}

func (*dnsTestLane) DatagramCanSend() bool { return true }

func (*dnsTestLane) Features() transport.Features { return transport.Features{Datagram: true} }

func (l *dnsTestLane) count() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.sent)
}

// dnsTestServer answers CONNECT streams the way the server would, with the
// exchange handed to the test: it gets the stream after the ack.
type dnsTestServer struct {
	sess     *smux.Session
	accepted chan *smux.Stream
	errs     chan error
}

func newDNSTestClient(t *testing.T, exchange func(stream *smux.Stream) error) (*Client, *dnsTestServer) {
	t.Helper()
	a, b := net.Pipe()
	serverSess, err := smux.Server(a, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Server() error = %v", err)
	}
	clientSess, err := smux.Client(b, testSmuxCfg())
	if err != nil {
		t.Fatalf("smux.Client() error = %v", err)
	}
	t.Cleanup(func() {
		_ = clientSess.Close()
		_ = serverSess.Close()
		_ = a.Close()
		_ = b.Close()
	})
	srv := &dnsTestServer{sess: serverSess, accepted: make(chan *smux.Stream, 8), errs: make(chan error, 8)}
	go func() {
		for {
			stream, acceptErr := serverSess.AcceptStream()
			if acceptErr != nil {
				return
			}
			srv.accepted <- stream
			go func() {
				defer func() { _ = stream.Close() }()
				var req map[string]any
				if decodeErr := json.NewDecoder(stream).Decode(&req); decodeErr != nil {
					srv.errs <- decodeErr
					return
				}
				if req["cmd"] != "connect" || req["addr"] != dnsTestResolver || req["port"] != float64(dnsPort) {
					srv.errs <- errDNSTestBadConnect
					return
				}
				if _, writeErr := stream.Write([]byte{0x00}); writeErr != nil {
					srv.errs <- writeErr
					return
				}
				if exchangeErr := exchange(stream); exchangeErr != nil {
					srv.errs <- exchangeErr
				}
			}()
		}
	}()
	c := &Client{
		session:   clientSess,
		sessionID: "dns-test-session",
		keys:      newClientTestKeys(t),
		deviceID:  "client-1",
		udpFlows:  map[uint64]clientUDPFlow{},
	}
	return c, srv
}

// readFramedQuery is the server's half of RFC 7766: one length, one message.
func readFramedQuery(stream io.Reader) ([]byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(stream, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	msg := make([]byte, n)
	if _, err := io.ReadFull(stream, msg); err != nil {
		return nil, err
	}
	return msg, nil
}

func writeFramed(stream io.Writer, msg []byte) error {
	out := make([]byte, 2+len(msg))
	binary.BigEndian.PutUint16(out, uint16(len(msg))) //nolint:gosec // test data
	copy(out[2:], msg)
	_, err := stream.Write(out)
	return err
}

// dnsTestSockets is a SOCKS UDP client behind an association socket.
func dnsTestSockets(t *testing.T) (*net.UDPConn, *net.UDPConn) {
	t.Helper()
	assoc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen association: %v", err)
	}
	client, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen client: %v", err)
	}
	t.Cleanup(func() { _ = assoc.Close(); _ = client.Close() })
	return assoc, client
}

func socksDatagram(t *testing.T, host string, port uint16, payload []byte) []byte {
	t.Helper()
	packet, err := buildSocksUDP(udpwire.Endpoint{Host: host, Port: port}, payload)
	if err != nil {
		t.Fatalf("buildSocksUDP: %v", err)
	}
	return packet
}

func waitTracked(t *testing.T, c *Client) {
	t.Helper()
	done := make(chan struct{})
	go func() { c.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("dns goroutine did not end")
	}
}

func TestDNSQueryTakesTheStream(t *testing.T) {
	gotQuery := make(chan []byte, 1)
	c, srv := newDNSTestClient(t, func(stream *smux.Stream) error {
		q, err := readFramedQuery(stream)
		if err != nil {
			return err
		}
		gotQuery <- q
		return writeFramed(stream, dnsTestResponse)
	})
	lane := &dnsTestLane{}
	assoc, client := dnsTestSockets(t)
	src := client.LocalAddr().(*net.UDPAddr)

	c.forwardLocalUDP(context.Background(), lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, dnsTestQuery))

	_ = client.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 1500)
	n, from, err := client.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no response through the association: %v", err)
	}
	if from.Port != assoc.LocalAddr().(*net.UDPAddr).Port {
		t.Fatalf("response came from %v, want the association socket", from)
	}
	endpoint, payload, err := parseSocksUDP(buf[:n])
	if err != nil {
		t.Fatalf("response is not a SOCKS UDP packet: %v", err)
	}
	if endpoint.Host != dnsTestResolver || endpoint.Port != dnsPort {
		t.Fatalf("response endpoint = %v, want %s:%d", endpoint, dnsTestResolver, dnsPort)
	}
	if !bytes.Equal(payload, dnsTestResponse) {
		t.Fatalf("response payload = %x, want %x", payload, dnsTestResponse)
	}
	select {
	case q := <-gotQuery:
		if !bytes.Equal(q, dnsTestQuery) {
			t.Fatalf("server read query %x, want %x: %v", q, dnsTestQuery, errDNSTestBadFraming)
		}
	case <-time.After(time.Second):
		t.Fatal("server never read the framed query")
	}
	waitTracked(t, c)
	if len(srv.accepted) != 1 {
		t.Fatalf("server accepted %d streams, want 1", len(srv.accepted))
	}
	if lane.count() != 0 {
		t.Fatalf("lane carried %d datagrams, want 0", lane.count())
	}
	select {
	case err := <-srv.errs:
		t.Fatalf("server side: %v", err)
	default:
	}
}

func TestNonDNSDatagramTakesTheLane(t *testing.T) {
	c, srv := newDNSTestClient(t, func(*smux.Stream) error { return nil })
	lane := &dnsTestLane{}
	assoc, client := dnsTestSockets(t)
	src := client.LocalAddr().(*net.UDPAddr)

	c.forwardLocalUDP(context.Background(), lane, assoc, src, socksDatagram(t, dnsTestResolver, 443, dnsTestQuery))

	if lane.count() != 1 {
		t.Fatalf("lane carried %d datagrams, want 1", lane.count())
	}
	select {
	case <-srv.accepted:
		t.Fatal("a non-DNS datagram opened a stream")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestDNSQueryWithoutAnswerEndsWithinTheDeadline(t *testing.T) {
	saved := dnsQueryDeadline
	dnsQueryDeadline = 150 * time.Millisecond
	t.Cleanup(func() { dnsQueryDeadline = saved })

	c, _ := newDNSTestClient(t, func(stream *smux.Stream) error {
		if _, err := readFramedQuery(stream); err != nil {
			return nil //nolint:nilerr // the stream is closed by the deadline; that is the point
		}
		// Never answer; wait for the client to give up.
		_, _ = io.Copy(io.Discard, stream)
		return nil
	})
	lane := &dnsTestLane{}
	assoc, client := dnsTestSockets(t)
	src := client.LocalAddr().(*net.UDPAddr)

	started := time.Now()
	c.forwardLocalUDP(context.Background(), lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, dnsTestQuery))
	waitTracked(t, c)
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("query goroutine lived %v past a %v deadline", elapsed, dnsQueryDeadline)
	}
	if c.dnsInFlight.Load() != 0 {
		t.Fatalf("in-flight count = %d after the deadline, want 0", c.dnsInFlight.Load())
	}
	_ = client.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	if n, _, err := client.ReadFromUDP(make([]byte, 64)); err == nil {
		t.Fatalf("an unanswered query produced %d bytes for the client, want nothing", n)
	}
}

func TestDNSInFlightCapFallsBackToTheLane(t *testing.T) {
	savedCap := dnsMaxInFlight
	dnsMaxInFlight = 1
	t.Cleanup(func() { dnsMaxInFlight = savedCap })

	release := make(chan struct{})
	c, srv := newDNSTestClient(t, func(stream *smux.Stream) error {
		q, err := readFramedQuery(stream)
		if err != nil {
			return err
		}
		<-release
		_ = q
		return writeFramed(stream, dnsTestResponse)
	})
	lane := &dnsTestLane{}
	assoc, client := dnsTestSockets(t)
	src := client.LocalAddr().(*net.UDPAddr)

	c.forwardLocalUDP(context.Background(), lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, dnsTestQuery))
	select {
	case <-srv.accepted:
	case <-time.After(time.Second):
		t.Fatal("first query did not open a stream")
	}
	// Second query while the first is still in flight: the lane.
	c.forwardLocalUDP(context.Background(), lane, assoc, src, socksDatagram(t, dnsTestResolver, dnsPort, dnsTestQuery))
	if lane.count() != 1 {
		t.Fatalf("lane carried %d datagrams with the cap reached, want 1", lane.count())
	}
	close(release)
	waitTracked(t, c)
	if c.dnsInFlight.Load() != 0 {
		t.Fatalf("in-flight count = %d after the answer, want 0", c.dnsInFlight.Load())
	}
}
