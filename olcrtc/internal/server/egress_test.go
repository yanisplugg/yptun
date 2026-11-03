package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// ai-generated: the whole file (egress hardening: the private-target block on
// TCP CONNECT, and no destination in any log line above debug).

const (
	testPublicIP     = "93.184.216.34"
	testPublicIPAlt  = "1.1.1.1"
	testTargetPort   = 4321
	testSecretHost   = "secret-destination.test"
	testNowhereHost  = "nowhere.test"
	testRebindHost   = "rebind.test"
	testPublicHost   = "public.test"
	testTwoAddrsHost = "two-addrs.test"
)

// stubLookup answers LookupIP from answers, from later instead for every
// call after a name's first (a resolver that changes its answer), and a
// not-found DNS error for a name it does not know. It counts the calls.
type stubLookup struct {
	answers map[string][]net.IP
	later   map[string][]net.IP

	mu    sync.Mutex
	asked map[string]int
	total int
}

func (l *stubLookup) LookupIP(_ context.Context, network, host string) ([]net.IP, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.total++
	if l.asked == nil {
		l.asked = make(map[string]int)
	}
	l.asked[host]++
	answer, ok := l.answers[host]
	if next, changed := l.later[host]; changed && l.asked[host] > 1 {
		answer, ok = next, true
	}
	if !ok {
		return nil, &net.DNSError{Err: "nxdomain", Name: host, Server: "192.0.2.53:53", IsNotFound: true}
	}
	out := make([]net.IP, 0, len(answer))
	for _, ip := range answer {
		if network == "ip4" && ip.To4() == nil {
			continue
		}
		out = append(out, ip)
	}
	return out, nil
}

func (l *stubLookup) calls() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.total
}

// dialRecorder stands in for the protected dialer: it records every address
// a CONNECT dials and answers with one end of a pipe, or with the error set
// for that address.
type dialRecorder struct {
	mu     sync.Mutex
	dialed []string
	fail   map[string]error
}

func (d *dialRecorder) dial(_ context.Context, network, address string) (net.Conn, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.dialed = append(d.dialed, network+" "+address)
	if err := d.fail[address]; err != nil {
		return nil, err
	}
	local, remote := net.Pipe()
	go func() {
		_, _ = remote.Write([]byte("hi\n"))
		_ = remote.Close()
	}()
	return local, nil
}

func (d *dialRecorder) addresses() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return append([]string(nil), d.dialed...)
}

// lockedLog is a log destination the race detector accepts: the logger
// writes under its lock, the test reads under this one.
type lockedLog struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedLog) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedLog) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// captureLog sends the standard logger, which internal/logger writes
// through, into a buffer for the rest of the test, with debug off.
func captureLog(t *testing.T) *lockedLog {
	t.Helper()
	var buf lockedLog
	oldWriter, oldFlags, oldVerbose := log.Writer(), log.Flags(), logger.IsVerbose()
	log.SetOutput(&buf)
	log.SetFlags(0)
	logger.SetVerbose(false)
	t.Cleanup(func() {
		log.SetOutput(oldWriter)
		log.SetFlags(oldFlags)
		logger.SetVerbose(oldVerbose)
	})
	return &buf
}

func TestBlockedAddr(t *testing.T) {
	blocked := []string{
		"127.0.0.1", "127.8.9.10", "0.0.0.0", "0.1.2.3",
		"10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
		"169.254.169.254", "100.64.0.1", "100.100.100.200", "100.127.255.255",
		"192.0.0.8", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
		"224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
		"::", "::1", "fe80::1", "fe80::1%eth0", "fc00::1", "fd12:3456::1", "ff02::1",
		"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:100.64.0.1", "::ffff:0.0.0.0",
	}
	for _, text := range blocked {
		if !blockedAddr(netip.MustParseAddr(text)) {
			t.Errorf("blockedAddr(%s) = false, want true", text)
		}
	}
	if !blockedAddr(netip.Addr{}) {
		t.Error("blockedAddr(zero Addr) = false, want true")
	}
	allowed := []string{
		testPublicIP, testPublicIPAlt, "8.8.8.8", "100.63.255.255", "100.128.0.0",
		"172.15.255.255", "172.32.0.0", "192.169.0.1", "2606:4700:4700::1111", "2a00:1450:4001::1",
		"::ffff:1.1.1.1",
	}
	for _, text := range allowed {
		if blockedAddr(netip.MustParseAddr(text)) {
			t.Errorf("blockedAddr(%s) = true, want false", text)
		}
	}
}

// Every literal the policy refuses is refused before anything is dialed,
// whatever form it takes: IPv4, IPv6, IPv4-mapped IPv6, zoned.
func TestDialRefusesBlockedLiterals(t *testing.T) {
	for _, host := range []string{
		"127.0.0.1", "::1", "10.1.2.3", "169.254.169.254", "100.64.0.1",
		"0.0.0.0", "::ffff:127.0.0.1", "fe80::1%lo", "224.0.0.251",
	} {
		t.Run(host, func(t *testing.T) {
			rec := &dialRecorder{}
			lookup := &stubLookup{}
			s := &Server{resolver: lookup, dialTarget: rec.dial}
			_, err := s.dial(context.Background(), ConnectRequest{Addr: host, Port: 10085})
			if !errors.Is(err, errBlockedTarget) {
				t.Fatalf("dial(%s) error = %v, want %v", host, err, errBlockedTarget)
			}
			if got := rec.addresses(); len(got) != 0 || lookup.calls() != 0 {
				t.Fatalf("dial(%s) reached %v after %d lookups, want nothing", host, got, lookup.calls())
			}
		})
	}
}

// A name is judged by what it resolves to: one refused address among the
// answers refuses the target, and nothing is dialed.
func TestDialRefusesNamesResolvingInside(t *testing.T) {
	lookup := &stubLookup{answers: map[string][]net.IP{
		"loopback.test": {net.IPv4(127, 0, 0, 1)},
		"metadata.test": {net.IPv4(169, 254, 169, 254)},
		"cgnat.test":    {net.IPv4(100, 64, 1, 1)},
		"private.test":  {net.IPv4(10, 0, 0, 5)},
		"mixed.test":    {net.ParseIP(testPublicIP), net.IPv4(192, 168, 0, 1)},
		"mapped.test":   {net.ParseIP("::ffff:127.0.0.1")},
	}}
	for host := range lookup.answers {
		t.Run(host, func(t *testing.T) {
			rec := &dialRecorder{}
			s := &Server{resolver: lookup, dialTarget: rec.dial}
			_, err := s.dial(context.Background(), ConnectRequest{Addr: host, Port: 443})
			if !errors.Is(err, errBlockedTarget) {
				t.Fatalf("dial(%s) error = %v, want %v", host, err, errBlockedTarget)
			}
			if got := rec.addresses(); len(got) != 0 {
				t.Fatalf("dial(%s) reached %v, want nothing", host, got)
			}
		})
	}
}

// A public target still dials, and what is dialed is the checked literal:
// the name is resolved once, by the check, never again by the dialer.
func TestDialPublicTargetDialsTheCheckedAddress(t *testing.T) {
	lookup := &stubLookup{answers: map[string][]net.IP{
		testPublicHost: {net.ParseIP(testPublicIP), net.ParseIP("2606:4700:4700::1111")},
	}}
	tests := []struct {
		host string
		want string
	}{
		{host: testPublicHost, want: "tcp4 " + testPublicIP + ":443"},
		{host: testPublicIPAlt, want: "tcp4 " + testPublicIPAlt + ":443"},
		{host: "::ffff:" + testPublicIPAlt, want: "tcp4 " + testPublicIPAlt + ":443"},
	}
	for _, tt := range tests {
		t.Run(tt.host, func(t *testing.T) {
			rec := &dialRecorder{}
			s := &Server{resolver: lookup, dialTarget: rec.dial}
			conn, err := s.dial(context.Background(), ConnectRequest{Addr: tt.host, Port: 443})
			if err != nil {
				t.Fatalf("dial(%s) error = %v", tt.host, err)
			}
			_ = conn.Close()
			if got := rec.addresses(); len(got) != 1 || got[0] != tt.want {
				t.Fatalf("dial(%s) dialed %v, want [%s]", tt.host, got, tt.want)
			}
		})
	}
	if lookup.calls() != 1 {
		t.Fatalf("lookups = %d, want 1: only the name is resolved, and once", lookup.calls())
	}
}

// A resolver that answers a public address to the check and a private one
// after it (DNS rebinding) cannot move the dial: there is no second lookup
// for one CONNECT, and the next CONNECT is judged on its own answer.
func TestDialResolvesTheNameOnce(t *testing.T) {
	lookup := &stubLookup{
		answers: map[string][]net.IP{testRebindHost: {net.ParseIP(testPublicIP)}},
		later:   map[string][]net.IP{testRebindHost: {net.IPv4(127, 0, 0, 1)}},
	}
	rec := &dialRecorder{}
	s := &Server{resolver: lookup, dialTarget: rec.dial}
	conn, err := s.dial(context.Background(), ConnectRequest{Addr: testRebindHost, Port: 80})
	if err != nil {
		t.Fatalf("first dial error = %v", err)
	}
	_ = conn.Close()
	if got := rec.addresses(); len(got) != 1 || got[0] != "tcp4 "+testPublicIP+":80" || lookup.calls() != 1 {
		t.Fatalf("first dial reached %v after %d lookups, want the checked address after one", got, lookup.calls())
	}
	if _, err := s.dial(context.Background(), ConnectRequest{Addr: testRebindHost, Port: 80}); !errors.Is(err, errBlockedTarget) {
		t.Fatalf("second dial error = %v, want %v", err, errBlockedTarget)
	}
	if got := rec.addresses(); len(got) != 1 {
		t.Fatalf("the rebound answer was dialed: %v", got)
	}
}

// A name with several addresses is dialed as before: each checked address in
// order until one answers.
func TestDialTriesTheNextCheckedAddress(t *testing.T) {
	lookup := &stubLookup{answers: map[string][]net.IP{
		testTwoAddrsHost: {net.ParseIP(testPublicIP), net.ParseIP(testPublicIPAlt)},
	}}
	rec := &dialRecorder{fail: map[string]error{testPublicIP + ":443": syscall.ECONNREFUSED}}
	s := &Server{resolver: lookup, dialTarget: rec.dial}
	conn, err := s.dial(context.Background(), ConnectRequest{Addr: testTwoAddrsHost, Port: 443})
	if err != nil {
		t.Fatalf("dial error = %v", err)
	}
	_ = conn.Close()
	want := []string{"tcp4 " + testPublicIP + ":443", "tcp4 " + testPublicIPAlt + ":443"}
	if got := rec.addresses(); len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("dialed %v, want %v", got, want)
	}
}

// Behind an upstream proxy a blocked literal is refused before the proxy is
// dialed; a name goes to the proxy unresolved, as on the UDP path.
func TestDialViaProxyRefusesBlockedLiteral(t *testing.T) {
	var lc net.ListenConfig
	ln, err := lc.Listen(context.Background(), "tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen() error = %v", err)
	}
	defer func() { _ = ln.Close() }()
	accepted := make(chan struct{}, 4)
	go func() {
		for {
			conn, acceptErr := ln.Accept()
			if acceptErr != nil {
				return
			}
			accepted <- struct{}{}
			_ = conn.Close()
		}
	}()
	proxy, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		t.Fatalf("listener addr type = %T", ln.Addr())
	}
	lookup := &stubLookup{answers: map[string][]net.IP{"inside.test": {net.IPv4(127, 0, 0, 1)}}}
	s := &Server{resolver: lookup, socksProxyAddr: "127.0.0.1", socksProxyPort: proxy.Port}

	_, err = s.dial(context.Background(), ConnectRequest{Addr: "127.0.0.1", Port: 10085})
	if !errors.Is(err, errBlockedTarget) {
		t.Fatalf("dial(127.0.0.1) via proxy error = %v, want %v", err, errBlockedTarget)
	}
	select {
	case <-accepted:
		t.Fatal("a blocked literal reached the proxy")
	case <-time.After(100 * time.Millisecond):
	}

	_, err = s.dial(context.Background(), ConnectRequest{Addr: "inside.test", Port: 443})
	if errors.Is(err, errBlockedTarget) || lookup.calls() != 0 {
		t.Fatalf("a name via proxy: error = %v after %d lookups, want it handed to the proxy", err, lookup.calls())
	}
	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("the name never reached the proxy")
	}
}

// The failure words carry no destination for the errors a dial really
// returns, each of which quotes one.
func TestDialFailureNamesNoDestination(t *testing.T) {
	tests := []struct {
		err  error
		want string
	}{
		{err: errBlockedTarget, want: "blocked target"},
		{err: errNoTargetAddress, want: "no address"},
		{
			err:  errors.Join(errResolveTarget, &net.DNSError{Err: "nxdomain", Name: testSecretHost, IsNotFound: true}),
			want: "no such host",
		},
		{
			err:  errors.Join(errResolveTarget, &net.DNSError{Err: "i/o timeout", Name: testSecretHost, IsTimeout: true}),
			want: "lookup failed",
		},
		{
			err: &net.OpError{Op: "dial", Net: "tcp4", Addr: &net.TCPAddr{IP: net.ParseIP(testPublicIP), Port: testTargetPort},
				Err: syscall.ECONNREFUSED},
			want: "connection refused",
		},
		{
			err: &net.OpError{Op: "dial", Net: "tcp4", Addr: &net.TCPAddr{IP: net.ParseIP(testPublicIP), Port: testTargetPort},
				Err: syscall.EHOSTUNREACH},
			want: "no route",
		},
		{err: context.DeadlineExceeded, want: "timeout"},
		{err: ErrSocks5ConnectFailed, want: "refused by the upstream proxy"},
		{err: io.ErrUnexpectedEOF, want: "failed"},
	}
	for _, tt := range tests {
		got := dialFailure(tt.err)
		if got != tt.want {
			t.Errorf("dialFailure(%v) = %q, want %q", tt.err, got, tt.want)
		}
		for _, leak := range []string{testSecretHost, testPublicIP, "4321"} {
			if strings.Contains(got, leak) {
				t.Errorf("dialFailure(%v) = %q quotes %q", tt.err, got, leak)
			}
		}
	}
}

// connectThrough sends one CONNECT for host:port to s over a smux pair and
// returns the ack, reading what follows an OK until the stream ends.
func connectThrough(t *testing.T, s *Server, host string, port int) byte {
	t.Helper()
	serverSess, clientSess, cleanup := smuxPair(t)
	defer cleanup()
	done := make(chan struct{})
	go func() {
		defer close(done)
		stream, err := serverSess.AcceptStream()
		if err == nil {
			s.handleStream(context.Background(), stream, "log-sid")
		}
	}()
	stream, err := clientSess.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream() error = %v", err)
	}
	req, err := json.Marshal(ConnectRequest{Cmd: testConnectCmd, Addr: host, Port: port})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	if _, err := stream.Write(req); err != nil {
		t.Fatalf("Write() error = %v", err)
	}
	_ = stream.SetReadDeadline(time.Now().Add(5 * time.Second))
	ack := make([]byte, 1)
	if _, err := io.ReadFull(stream, ack); err != nil {
		t.Fatalf("read ack: %v", err)
	}
	if ack[0] == tunnelcore.ConnectAckOK {
		_, _ = io.Copy(io.Discard, stream)
	}
	_ = stream.Close()
	<-done
	return ack[0]
}

// Without debug the server's log names no destination: not the host, not the
// port, not the address a name resolved to, for a blocked target, a name
// that does not resolve and a connection that went through. With debug the
// destination is there, as `debug: true` promises.
func TestDispatchLogsNoDestination(t *testing.T) {
	logs := captureLog(t)
	lookup := &stubLookup{answers: map[string][]net.IP{
		testSecretHost:             {net.IPv4(127, 0, 0, 1)},
		"public-" + testSecretHost: {net.ParseIP(testPublicIP)},
	}}
	rec := &dialRecorder{}
	s := &Server{resolver: lookup, dialTarget: rec.dial}

	if ack := connectThrough(t, s, testSecretHost, testTargetPort); ack != tunnelcore.ConnectAckHostUnreachable {
		t.Fatalf("blocked target ack = 0x%02x, want 0x%02x", ack, tunnelcore.ConnectAckHostUnreachable)
	}
	if ack := connectThrough(t, s, testNowhereHost, testTargetPort); ack != tunnelcore.ConnectAckHostUnreachable {
		t.Fatalf("unresolvable target ack = 0x%02x, want 0x%02x", ack, tunnelcore.ConnectAckHostUnreachable)
	}
	if ack := connectThrough(t, s, "public-"+testSecretHost, testTargetPort); ack != tunnelcore.ConnectAckOK {
		t.Fatalf("public target ack = 0x%02x, want ok", ack)
	}
	out := logs.String()
	// ":4321", not "4321": an elapsed time such as 1.432158ms may hold the digits.
	for _, leak := range []string{testSecretHost, testNowhereHost, testPublicIP, ":4321"} {
		if strings.Contains(out, leak) {
			t.Fatalf("the log names a destination (%q):\n%s", leak, out)
		}
	}
	for _, kept := range []string{"dial failed", dialFailure(errBlockedTarget), dialFailure(&net.DNSError{IsNotFound: true})} {
		if !strings.Contains(out, kept) {
			t.Fatalf("the log lost %q:\n%s", kept, out)
		}
	}

	logger.SetVerbose(true)
	if ack := connectThrough(t, s, "public-"+testSecretHost, testTargetPort); ack != tunnelcore.ConnectAckOK {
		t.Fatalf("public target ack with debug = 0x%02x, want ok", ack)
	}
	if !strings.Contains(logs.String(), "connected public-"+testSecretHost+":4321") {
		t.Fatalf("debug does not name the destination:\n%s", logs.String())
	}
}
