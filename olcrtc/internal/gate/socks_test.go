package gate

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

// ai-generated: whole file, cover for the SOCKS5 helpers: the datagram
// header, TCP through a CONNECT, and a UDP ASSOCIATE against a fake server
// that checks where datagrams come from the way the engine does.

func TestUDPHeaderRoundTrip(t *testing.T) {
	dst := &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}
	hdr := encodeUDPHeader(dst)
	if !bytes.Equal(hdr, []byte{0, 0, 0, 1, 8, 8, 8, 8, 0, 53}) {
		t.Fatalf("header = %v", hdr)
	}
	back, payload, err := decodeUDPHeader(append(hdr, 'x', 'y'))
	if err != nil || back.String() != "8.8.8.8:53" || string(payload) != "xy" {
		t.Fatalf("decode = %v %q %v", back, payload, err)
	}
	for _, bad := range [][]byte{
		{0, 0, 1, 1},                         // short
		{0, 0, 1, 1, 8, 8, 8, 8, 0, 53, 'x'}, // a fragment
		{0, 0, 0, 4, 8, 8, 8, 8, 0, 53, 'x'}, // not IPv4
		{0, 7, 0, 1, 8, 8, 8, 8, 0, 53, 'x'}, // reserved bytes set
	} {
		if _, _, err := decodeUDPHeader(bad); !errors.Is(err, ErrSocksReply) {
			t.Fatalf("decodeUDPHeader(%v) = %v, want ErrSocksReply", bad, err)
		}
	}
}

func TestSocksDialerRejectsBadAddress(t *testing.T) {
	if _, err := SocksDialer("not an address"); err == nil {
		t.Fatal("SocksDialer accepted a bad address")
	}
}

func TestHTTPClientConnectsAfreshForEveryRequest(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "ok")
	}))
	defer origin.Close()
	socks := startFakeSocks(t, nil)
	dial, err := SocksDialer(socks.addr())
	if err != nil {
		t.Fatalf("SocksDialer: %v", err)
	}
	client := HTTPClient(dial, 5*time.Second)
	for i := range 3 {
		resp, err := client.Get(origin.URL)
		if err != nil {
			t.Fatalf("request %d: %v", i, err)
		}
		body, err := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if err != nil || string(body) != "ok" {
			t.Fatalf("request %d: body %q, %v", i, body, err)
		}
	}
	if n := socks.connects.Load(); n != 3 {
		t.Fatalf("3 requests made %d CONNECTs, want one each", n)
	}
}

func TestUDPAssociateRelaysDatagramsBothWays(t *testing.T) {
	for _, tc := range []struct {
		name  string
		reply func(relay *net.UDPAddr) []byte
	}{
		{"relay at its own address", func(r *net.UDPAddr) []byte { return udpReply(0, r.IP, r) }},
		{"relay at 0.0.0.0, the server's address", func(r *net.UDPAddr) []byte { return udpReply(0, net.IPv4zero, r) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			assoc := openAssoc(t, startFakeSocks(t, tc.reply))
			dst := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 7), Port: 5353}
			if err := assoc.Send(dst, []byte("ping")); err != nil {
				t.Fatalf("Send: %v", err)
			}
			payload, from, err := assoc.Recv(make([]byte, 512), time.Now().Add(5*time.Second))
			if err != nil || string(payload) != "ping" || from.String() != dst.String() {
				t.Fatalf("Recv = %q from %v, %v; want ping from %v", payload, from, err, dst)
			}
		})
	}
}

func TestUDPAssocRefusesWhatItCannotCarryAndReadsOnlyTheRelay(t *testing.T) {
	assoc := openAssoc(t, startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(0, r.IP, r) }))
	for _, dst := range []*net.UDPAddr{nil, {IP: net.ParseIP("2001:db8::1"), Port: 53}, {IP: net.IPv4(192, 0, 2, 7)}} {
		if err := assoc.Send(dst, []byte("x")); !errors.Is(err, ErrUDPTarget) {
			t.Fatalf("Send to %v = %v, want ErrUDPTarget", dst, err)
		}
	}
	// A well-formed datagram from anyone but the relay is never read.
	stranger, err := net.DialUDP("udp4", nil, assoc.relay.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatalf("stranger: %v", err)
	}
	defer func() { _ = stranger.Close() }()
	spoof := append(encodeUDPHeader(&net.UDPAddr{IP: net.IPv4(192, 0, 2, 7), Port: 53}), "spoof"...)
	if _, err = stranger.Write(spoof); err != nil {
		t.Fatalf("stranger write: %v", err)
	}
	payload, _, err := assoc.Recv(make([]byte, 512), time.Now().Add(100*time.Millisecond))
	if !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("Recv with nothing from the relay = %q, %v; want the deadline", payload, err)
	}
}

func TestAssociateTakesAnUnspecifiedRelayForTheServersAddress(t *testing.T) {
	socks := startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(0, net.IPv4zero, r) })
	control, err := net.Dial("tcp4", socks.addr())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = control.Close() }()
	relayTo, err := associate(control)
	if err != nil || relayTo.IP.IsUnspecified() || !relayTo.IP.Equal(tcpIP(control.RemoteAddr())) {
		t.Fatalf("associate = %v, %v; want the relay at %v", relayTo, err, control.RemoteAddr())
	}
}

func TestUDPAssociateReportsARefusal(t *testing.T) {
	socks := startFakeSocks(t, func(r *net.UDPAddr) []byte { return udpReply(4, net.IPv4zero, r) })
	if _, err := UDPAssociate(context.Background(), socks.addr()); !errors.Is(err, ErrSocksReply) {
		t.Fatalf("UDPAssociate = %v, want ErrSocksReply", err)
	}
}

func TestUDPAssociateGivesUpWhenItsContextEnds(t *testing.T) {
	socks := startFakeSocks(t, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := UDPAssociate(ctx, socks.addr())
		done <- err
	}()
	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("UDPAssociate on a silent server = %v, want the context's deadline", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("UDPAssociate still waits 5 s after its context ended")
	}
}

// openAssoc opens an association through socks under a context that ends
// before the association is used, and closes it when the test ends.
func openAssoc(t *testing.T, socks *fakeSocks) *UDPAssoc {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	assoc, err := UDPAssociate(ctx, socks.addr())
	if err != nil {
		t.Fatalf("UDPAssociate: %v", err)
	}
	t.Cleanup(assoc.Close)
	return assoc
}

// udpReply is an answer to a UDP ASSOCIATE: reply code rep and a relay at ip
// with the port of relay.
func udpReply(rep byte, ip net.IP, relay *net.UDPAddr) []byte {
	b := append([]byte{5, rep, 0, 1}, ip.To4()...)
	return binary.BigEndian.AppendUint16(b, relay.AddrPort().Port())
}

// fakeSocks is a SOCKS5 server on loopback without authentication. A CONNECT
// is dialled and spliced. A UDP ASSOCIATE is answered with reply(relay), or
// never when reply is nil; then every datagram from the control connection's
// peer goes back to its sender unchanged, as if the target it names had
// answered, and a datagram from any other address is dropped, as the engine
// drops it.
type fakeSocks struct {
	ln       net.Listener
	reply    func(relay *net.UDPAddr) []byte
	connects atomic.Int32
	// junkFirst has each association send a datagram the client cannot read
	// ahead of its first answer.
	junkFirst atomic.Bool
}

func startFakeSocks(t *testing.T, reply func(relay *net.UDPAddr) []byte) *fakeSocks {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	f := &fakeSocks{ln: ln, reply: reply}
	go f.serve()
	return f
}

func (f *fakeSocks) addr() string { return f.ln.Addr().String() }

func (f *fakeSocks) serve() {
	for {
		conn, err := f.ln.Accept()
		if err != nil {
			return
		}
		go f.handle(conn)
	}
}

func (f *fakeSocks) handle(conn net.Conn) {
	defer func() { _ = conn.Close() }()
	buf := make([]byte, 255)
	if _, err := io.ReadFull(conn, buf[:2]); err != nil {
		return
	}
	if _, err := io.ReadFull(conn, buf[:buf[1]]); err != nil {
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}
	// Both helpers name an IPv4 address, so a request is ten bytes.
	req := make([]byte, 10)
	if _, err := io.ReadFull(conn, req); err != nil || req[0] != 5 || req[3] != 1 {
		return
	}
	switch req[1] {
	case 1:
		f.connects.Add(1)
		f.serveConnect(conn, &net.TCPAddr{IP: net.IP(req[4:8]), Port: int(binary.BigEndian.Uint16(req[8:]))})
	case 3:
		f.serveAssociate(conn)
	}
}

func (f *fakeSocks) serveConnect(conn net.Conn, target *net.TCPAddr) {
	up, err := net.DialTCP("tcp4", nil, target)
	if err != nil {
		_, _ = conn.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer func() { _ = up.Close() }()
	if _, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	go func() { _, _ = io.Copy(up, conn) }()
	_, _ = io.Copy(conn, up)
}

func (f *fakeSocks) serveAssociate(conn net.Conn) {
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return
	}
	defer func() { _ = relay.Close() }()
	if f.reply == nil {
		_, _ = io.Copy(io.Discard, conn) // no answer: hold the request until the client leaves
		return
	}
	if _, err := conn.Write(f.reply(relay.LocalAddr().(*net.UDPAddr))); err != nil {
		return
	}
	go func() {
		_, _ = io.Copy(io.Discard, conn)
		_ = relay.Close()
	}()
	peer := conn.RemoteAddr().(*net.TCPAddr).IP
	junk := f.junkFirst.Load()
	buf := make([]byte, 2048)
	for {
		n, src, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if !src.IP.Equal(peer) {
			continue
		}
		if junk {
			// A fragment, which the client does not reassemble.
			_, _ = relay.WriteToUDP([]byte{0, 0, 1, 1, 127, 0, 0, 1, 0, 53}, src)
			junk = false
		}
		_, _ = relay.WriteToUDP(buf[:n], src)
	}
}
