package client

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/route"
)

// ai-generated: whole file, cover for the direct-dial path (olcbox#28).

const directTestLoopback = "127.0.0.1"

var errStaticLookupUnknown = errors.New("static lookup: unknown name")

// staticLookup resolves the names a test hands it and nothing else.
type staticLookup map[string][]net.IP

func (l staticLookup) LookupIP(_ context.Context, network, host string) ([]net.IP, error) {
	ips, ok := l[host]
	if !ok {
		return nil, fmt.Errorf("%w: %s", errStaticLookupUnknown, host)
	}
	if network == "ip6" {
		return nil, nil
	}
	return ips, nil
}

func mustRules(t *testing.T, text string) *route.Rules {
	t.Helper()
	rules, err := route.Parse(text)
	if err != nil {
		t.Fatal(err)
	}
	return rules
}

// echoServer accepts one connection at a time, checks that it opens with
// expectHead when that is set, and echoes everything after.
func echoServer(t *testing.T, expectHead []byte) (int, <-chan error) {
	t.Helper()
	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp4", directTestLoopback+":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	errs := make(chan error, 4)
	go func() {
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			go func() {
				defer func() { _ = conn.Close() }()
				if len(expectHead) > 0 {
					head := make([]byte, len(expectHead))
					if _, readErr := io.ReadFull(conn, head); readErr != nil {
						errs <- readErr
						return
					}
					if !bytes.Equal(head, expectHead) {
						errs <- errors.New("the replayed head differs")
						return
					}
				}
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port
	return port, errs
}

// socksConnect drives handleSocks5 over a pipe: the SOCKS5 greeting, then a
// CONNECT to host:port, and returns the client end once the request is on
// the wire. The reply is read by the caller, since when it arrives is what
// some tests check.
func socksConnect(t *testing.T, c *Client, host string, port int) net.Conn {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	server, client := net.Pipe()
	t.Cleanup(func() { _ = client.Close() })
	go c.handleSocks5(ctx, server)
	if _, err := client.Write([]byte{socksVersion, 1, 0}); err != nil {
		t.Fatal(err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil || greeting[1] != 0 {
		t.Fatalf("greeting = %v, %v", greeting, err)
	}
	req := []byte{socksVersion, socksCmdConnect, 0}
	if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
		req = append(req, socksAddrIPv4)
		req = append(req, ip.To4()...)
	} else {
		req = append(req, socksAddrDomain, byte(len(host))) //nolint:gosec // G115: test names are short
		req = append(req, host...)
	}
	req = binary.BigEndian.AppendUint16(req, uint16(port)) //nolint:gosec // test port
	if _, err := client.Write(req); err != nil {
		t.Fatal(err)
	}
	return client
}

func readReply(t *testing.T, conn net.Conn) byte {
	t.Helper()
	reply := make([]byte, 10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatalf("read reply: %v", err)
	}
	return reply[1]
}

func expectEcho(t *testing.T, conn net.Conn) {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 4)
	if _, err := io.ReadFull(conn, got); err != nil || string(got) != "ping" {
		t.Fatalf("echo = %q, %v", got, err)
	}
}

func clientHello(t *testing.T, serverName string) []byte {
	t.Helper()
	a, b := net.Pipe()
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()
	go func() {
		_ = tls.Client(a, &tls.Config{ServerName: serverName, InsecureSkipVerify: true}).Handshake() //nolint:gosec // test
	}()
	buf := make([]byte, 16*1024)
	n, err := b.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	return buf[:n]
}

func newDirectTestClient(t *testing.T, rules *route.Rules, lookup protect.Lookup) *Client {
	t.Helper()
	return &Client{
		rules: rules, dialer: protect.NewDialer(lookup),
		keys: newClientTestKeys(t), deviceID: "client-1", sessionReady: make(chan struct{}),
	}
}

// streamTestServer answers CONNECT streams like the server would: reads the
// request, waits ackDelay, acks, then hands the stream to serve.
type streamTestServer struct {
	requests chan map[string]any
	errs     chan error
}

func newStreamTestClient(
	t *testing.T, rules *route.Rules, ackDelay time.Duration, serve func(stream *smux.Stream),
) (*Client, *streamTestServer) {
	t.Helper()
	a, b := net.Pipe()
	serverSess, err := smux.Server(a, testSmuxCfg())
	if err != nil {
		t.Fatal(err)
	}
	clientSess, err := smux.Client(b, testSmuxCfg())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = clientSess.Close(); _ = serverSess.Close(); _ = a.Close(); _ = b.Close() })
	srv := &streamTestServer{requests: make(chan map[string]any, 8), errs: make(chan error, 8)}
	go func() {
		for {
			stream, acceptErr := serverSess.AcceptStream()
			if acceptErr != nil {
				return
			}
			go func() {
				defer func() { _ = stream.Close() }()
				var req map[string]any
				if decodeErr := json.NewDecoder(stream).Decode(&req); decodeErr != nil {
					srv.errs <- decodeErr
					return
				}
				srv.requests <- req
				time.Sleep(ackDelay)
				if _, writeErr := stream.Write([]byte{0x00}); writeErr != nil {
					srv.errs <- writeErr
					return
				}
				serve(stream)
			}()
		}
	}()
	c := newDirectTestClient(t, rules, staticLookup{})
	c.session, c.sessionID = clientSess, "direct-test-session"
	return c, srv
}

// echoAfterHead is a stream server that expects head first, then echoes.
func echoAfterHead(t *testing.T, head []byte, errs chan<- error) func(*smux.Stream) {
	t.Helper()
	return func(stream *smux.Stream) {
		if len(head) > 0 {
			got := make([]byte, len(head))
			if _, err := io.ReadFull(stream, got); err != nil {
				errs <- err
				return
			}
			if !bytes.Equal(got, head) {
				errs <- errors.New("the replayed head differs on the stream")
				return
			}
		}
		_, _ = io.Copy(stream, stream)
	}
}

func noErrors(t *testing.T, errs <-chan error) {
	t.Helper()
	select {
	case err := <-errs:
		t.Fatal(err)
	default:
	}
}

func TestConnectByDirectNameNeedsNoSession(t *testing.T) {
	port, errs := echoServer(t, nil)
	c := newDirectTestClient(t, mustRules(t, "full:direct.test\n"),
		staticLookup{"direct.test": {net.IPv4(127, 0, 0, 1)}})
	conn := socksConnect(t, c, "direct.test", port)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestConnectByPrefixGoesDirect(t *testing.T) {
	port, errs := echoServer(t, nil)
	c := newDirectTestClient(t, mustRules(t, "127.0.0.0/8\n"), staticLookup{})
	conn := socksConnect(t, c, directTestLoopback, port)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestDirectDialFailureAnswersHostUnreachable(t *testing.T) {
	c := newDirectTestClient(t, mustRules(t, "full:gone.test\n"), staticLookup{})
	conn := socksConnect(t, c, "gone.test", 443)
	if rep := readReply(t, conn); rep != socksRepHostUnreachable {
		t.Fatalf("reply = %#x, want host unreachable", rep)
	}
}

func TestSniffedDirectNameIsDialedByNameWithReplay(t *testing.T) {
	hello := clientHello(t, "a.sni.test")
	port, errs := echoServer(t, hello)
	// The name resolves to the echo server; the address the client gave is
	// one nothing listens on, so a dial by address would fail the test.
	c := newDirectTestClient(t, mustRules(t, "domain:sni.test\n"),
		staticLookup{"a.sni.test": {net.IPv4(127, 0, 0, 1)}})
	conn := socksConnect(t, c, "127.0.0.2", port)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	if _, err := conn.Write(hello); err != nil {
		t.Fatal(err)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestSniffedDirectNameFallsBackToTheGivenAddress(t *testing.T) {
	hello := clientHello(t, "a.sni.test")
	port, errs := echoServer(t, hello)
	c := newDirectTestClient(t, mustRules(t, "domain:sni.test\n"), staticLookup{})
	conn := socksConnect(t, c, directTestLoopback, port)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	if _, err := conn.Write(hello); err != nil {
		t.Fatal(err)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestSniffedForeignNameTakesTheTunnelWithReplay(t *testing.T) {
	hello := clientHello(t, "example.com")
	errs := make(chan error, 4)
	c, srv := newStreamTestClient(t, mustRules(t, "domain:sni.test\n"), 0, echoAfterHead(t, hello, errs))
	conn := socksConnect(t, c, directTestLoopback, 443)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	if _, err := conn.Write(hello); err != nil {
		t.Fatal(err)
	}
	select {
	case req := <-srv.requests:
		if req["addr"] != directTestLoopback || req["port"] != float64(443) {
			t.Fatalf("stream connect = %v", req)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no connect on the stream")
	}
	expectEcho(t, conn)
	noErrors(t, errs)
	noErrors(t, srv.errs)
}

func TestSilentClientTakesTheTunnel(t *testing.T) {
	old := sniffTimeout
	sniffTimeout = 50 * time.Millisecond
	t.Cleanup(func() { sniffTimeout = old })
	errs := make(chan error, 4)
	c, srv := newStreamTestClient(t, mustRules(t, "domain:sni.test\n"), 0, echoAfterHead(t, nil, errs))
	conn := socksConnect(t, c, "10.1.2.3", 22)
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	start := time.Now()
	select {
	case <-srv.requests:
	case <-time.After(3 * time.Second):
		t.Fatal("no connect on the stream")
	}
	if waited := time.Since(start); waited > time.Second {
		t.Fatalf("the tunnel waited %s for a client that said nothing", waited)
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestRulesOffRepliesOnlyAfterTheAck(t *testing.T) {
	const ackDelay = 200 * time.Millisecond
	errs := make(chan error, 4)
	c, srv := newStreamTestClient(t, nil, ackDelay, echoAfterHead(t, nil, errs))
	conn := socksConnect(t, c, "10.1.2.3", 443)
	_ = conn.SetReadDeadline(time.Now().Add(ackDelay / 2))
	early := make([]byte, 1)
	if _, err := conn.Read(early); err == nil {
		t.Fatal("a SOCKS reply arrived before the server acked")
	}
	_ = conn.SetReadDeadline(time.Time{})
	if rep := readReply(t, conn); rep != socksRepSuccess {
		t.Fatalf("reply = %#x", rep)
	}
	select {
	case <-srv.requests:
	default:
		t.Fatal("the stream connect did not happen")
	}
	expectEcho(t, conn)
	noErrors(t, errs)
}

func TestClassifyConnect(t *testing.T) {
	c := newDirectTestClient(t, mustRules(t, "domain:ru\n10.0.0.0/8\n"), staticLookup{})
	for host, want := range map[string]routeDecision{
		"ozon.ru": routeDirect, "example.com": routeTunnel, "10.1.1.1": routeDirect, "8.8.8.8": routeSniff,
		"::ffff:10.1.1.1": routeDirect, "2001:db8::1": routeSniff,
	} {
		if got, _ := c.classifyConnect(host); got != want {
			t.Errorf("classifyConnect(%q) = %v, want %v", host, got, want)
		}
	}
	off := newDirectTestClient(t, nil, staticLookup{})
	if got, _ := off.classifyConnect("ozon.ru"); got != routeTunnel {
		t.Errorf("rules off: classifyConnect = %v", got)
	}
}
