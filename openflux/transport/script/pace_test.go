package script

import (
	"net"
	"strings"
	"testing"
	"time"

	"github.com/pion/transport/v5/stdnet"
	"github.com/pion/turn/v5"
	"github.com/pion/webrtc/v4"
)

func listenUDP(t *testing.T) *net.UDPConn {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func TestPacedConnSpacesDatagramsOut(t *testing.T) {
	rx := listenUDP(t)
	tx := listenUDP(t)
	cfg := &paceConfig{}
	cfg.rate.Store(100_000) // 100 KB/s: a 972-byte datagram (+28) every 10 ms
	cfg.queue.Store(50)
	cfg.burst.Store(1)
	p := newPacedConn(tx, cfg, "test")
	defer p.Close()

	start := time.Now()
	for i := 0; i < 10; i++ {
		if _, err := p.WriteTo(make([]byte, 972), rx.LocalAddr()); err != nil {
			t.Fatal(err)
		}
	}
	if time.Since(start) > 5*time.Millisecond {
		t.Fatalf("WriteTo blocked for %v; it must only queue", time.Since(start))
	}
	var at []time.Time
	buf := make([]byte, 2000)
	for len(at) < 10 {
		rx.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, _, err := rx.ReadFrom(buf); err != nil {
			t.Fatalf("got %d datagrams: %v", len(at), err)
		}
		at = append(at, time.Now())
	}
	total := at[9].Sub(at[0])
	if total < 80*time.Millisecond || total > 150*time.Millisecond {
		t.Fatalf("10 datagrams arrived within %v, want about 90ms", total)
	}
	for i := 1; i < len(at); i++ {
		if gap := at[i].Sub(at[i-1]); gap < 5*time.Millisecond {
			t.Fatalf("datagram %d followed the previous one after %v", i, gap)
		}
	}
}

func TestPacedConnBurstAndTailDrop(t *testing.T) {
	rx := listenUDP(t)
	tx := listenUDP(t)
	cfg := &paceConfig{}
	cfg.rate.Store(10_000) // slow: 100 ms per datagram, so nothing leaves during the test's burst but the credit
	cfg.queue.Store(3)
	cfg.burst.Store(2)
	p := newPacedConn(tx, cfg, "test")
	defer p.Close()

	for i := 0; i < 10; i++ {
		p.WriteTo([]byte{byte(i)}, rx.LocalAddr())
	}
	// 10 offered, at most queue (3) waiting plus the one the sender already took: the rest is dropped.
	if d := p.dropped.Load(); d < 5 {
		t.Fatalf("dropped %d of 10, want at least 5", d)
	}
	// What got through arrives in order.
	buf := make([]byte, 16)
	last := -1
	for {
		rx.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
		n, _, err := rx.ReadFrom(buf)
		if err != nil {
			break
		}
		if n != 1 || int(buf[0]) <= last {
			t.Fatalf("out of order: %d after %d", buf[0], last)
		}
		last = int(buf[0])
	}
}

func TestPacedConnOffPassesThrough(t *testing.T) {
	rx := listenUDP(t)
	tx := listenUDP(t)
	p := newPacedConn(tx, &paceConfig{}, "test")
	defer p.Close()
	if _, err := p.WriteTo([]byte("hi"), rx.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 8)
	rx.SetReadDeadline(time.Now().Add(time.Second))
	if n, _, err := rx.ReadFrom(buf); err != nil || string(buf[:n]) != "hi" {
		t.Fatalf("got %q, %v", buf[:n], err)
	}
}

func TestParsePace(t *testing.T) {
	c := &paceConfig{}
	parsePace("rate=250 queue=40 burst=2\n", c)
	if c.rate.Load() != 250_000 || c.queue.Load() != 40 || c.burst.Load() != 2 {
		t.Fatalf("got %d %d %d", c.rate.Load(), c.queue.Load(), c.burst.Load())
	}
	parsePace("rate=0", c)
	if c.on() {
		t.Fatal("rate=0 must switch pacing off")
	}
}

// The relay socket of a PeerConnection is the one the pacer has to sit on: a TURN allocation made through
// the connection's network must send through a paced conn, and the allocation itself must work through it.
func TestRelaySocketOfAPeerConnectionIsPaced(t *testing.T) {
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv, err := turn.NewServer(turn.ServerConfig{
		Realm: "test",
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			if ra.Username == "user" {
				return "user", turn.GenerateAuthKey("user", "test", "pass"), true
			}
			return "", nil, false
		},
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn:            udp,
			RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{RelayAddress: net.ParseIP("127.0.0.1"), Address: "127.0.0.1"},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	cfg := &paceConfig{}
	cfg.rate.Store(1_000_000)
	cfg.queue.Store(50)
	cfg.burst.Store(1)
	nw, err := stdnet.NewNet()
	if err != nil {
		t.Fatal(err)
	}
	var se webrtc.SettingEngine
	se.SetNet(&pacedNet{Net: nw, cfg: cfg, name: "test"})
	pc, err := webrtc.NewAPI(webrtc.WithSettingEngine(se)).NewPeerConnection(webrtc.Configuration{
		ICEServers:         []webrtc.ICEServer{{URLs: []string{"turn:" + udp.LocalAddr().String()}, Username: "user", Credential: "pass", CredentialType: webrtc.ICECredentialTypePassword}},
		ICETransportPolicy: webrtc.ICETransportPolicyRelay,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	if _, err := pc.CreateDataChannel("x", nil); err != nil {
		t.Fatal(err)
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-webrtc.GatheringCompletePromise(pc):
	case <-time.After(10 * time.Second):
		t.Fatal("candidate gathering did not finish")
	}
	if !strings.Contains(pc.LocalDescription().SDP, "typ relay") {
		t.Fatalf("no relay candidate:\n%s", pc.LocalDescription().SDP)
	}
	if pacedSockets.Load() == 0 {
		t.Fatal("the TURN client did not go through a paced socket")
	}
}
