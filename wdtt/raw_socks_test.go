package wdtt

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// TestRawSocksOverNetstack wires the client netstack back-to-back with a stand-in for the server's
// network through tunPackets — the same adapter the dispatcher reads/writes — and pushes a TCP echo
// and a UDP echo through the SOCKS5 the Raw mode serves.
func TestRawSocksOverNetstack(t *testing.T) {
	addr := func(s string) []netip.Addr { return []netip.Addr{netip.MustParseAddr(s)} }
	srvDev, srvNet, err := netstack.CreateNetTUN(addr("10.70.0.1"), addr("10.70.0.1"), 1280)
	if err != nil {
		t.Fatal(err)
	}
	cliDev, cliNet, err := netstack.CreateNetTUN(addr("10.70.0.2"), addr("10.70.0.1"), 1280)
	if err != nil {
		t.Fatal(err)
	}
	defer srvDev.Close()
	defer cliDev.Close()
	pump := func(dst, src tunPackets) {
		buf := make([]byte, 2048)
		for {
			n, err := src.Read(buf)
			if err != nil {
				return
			}
			_, _ = dst.Write(append([]byte(nil), buf[:n]...))
		}
	}
	go pump(tunPackets{srvDev}, tunPackets{cliDev})
	go pump(tunPackets{cliDev}, tunPackets{srvDev})

	tl, err := srvNet.ListenTCP(&net.TCPAddr{IP: net.IPv4(10, 70, 0, 1), Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		c, err := tl.Accept()
		if err == nil {
			_, _ = io.Copy(c, c)
		}
	}()
	ul, err := srvNet.ListenUDP(&net.UDPAddr{IP: net.IPv4(10, 70, 0, 1), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		b := make([]byte, 1500)
		for {
			n, from, err := ul.ReadFrom(b)
			if err != nil {
				return
			}
			_, _ = ul.WriteTo(b[:n], from)
		}
	}()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go serveRawSocks(ln, cliNet)

	socks := func(cmd byte, port uint16) (net.Conn, []byte) {
		c, err := net.Dial("tcp", ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		_ = c.SetDeadline(time.Now().Add(10 * time.Second))
		req := []byte{5, 1, 0, 5, cmd, 0, 1, 10, 70, 0, 1, 0, 0}
		binary.BigEndian.PutUint16(req[11:], port)
		if _, err := c.Write(req); err != nil {
			t.Fatal(err)
		}
		rep := make([]byte, 12)
		if _, err := io.ReadFull(c, rep); err != nil || rep[3] != 0 {
			t.Fatalf("socks reply %v %v", rep, err)
		}
		return c, rep[2:]
	}

	c, _ := socks(1, 80)
	defer c.Close()
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 4)
	if _, err := io.ReadFull(c, got); err != nil || string(got) != "ping" {
		t.Fatalf("tcp echo %q %v", got, err)
	}

	ctl, rep := socks(3, 0)
	defer ctl.Close()
	relay := &net.UDPAddr{IP: net.IP(rep[4:8]), Port: int(binary.BigEndian.Uint16(rep[8:10]))}
	u, err := net.DialUDP("udp", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	_ = u.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := u.Write(buildRawUDPReply("10.70.0.1", 53, []byte("dns?"))); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 1500)
	n, err := u.Read(b)
	if err != nil {
		t.Fatal(err)
	}
	if h, p, data, ok := parseRawUDPRequest(b[:n]); !ok || h != "10.70.0.1" || p != 53 || string(data) != "dns?" {
		t.Fatalf("udp echo %q %d %q %v", h, p, data, ok)
	}
}

func TestParseRawConf(t *testing.T) {
	ip, dns, mtu, err := parseRawConf("RAWCONF:10.70.0.2|1.1.1.1, 8.8.8.8|1380")
	if err != nil || ip.String() != "10.70.0.2" || len(dns) != 2 || dns[1].String() != "8.8.8.8" || mtu != 1380 {
		t.Fatalf("got %v %v %d %v", ip, dns, mtu, err)
	}
	// Garbage DNS/MTU fall back instead of failing the tunnel.
	_, dns, mtu, err = parseRawConf("RAWCONF:10.70.0.3|x|0")
	if err != nil || len(dns) != 1 || mtu != 1280 {
		t.Fatalf("fallbacks: %v %d %v", dns, mtu, err)
	}
	if _, _, _, err = parseRawConf("RAWCONF:10.70.0.4|1.1.1.1"); err == nil {
		t.Fatal("two fields must fail")
	}
}

func TestRawUDPRoundTrip(t *testing.T) {
	for _, host := range []string{"1.2.3.4", "2001:db8::1", "example.com"} {
		pkt := buildRawUDPReply(host, 53, []byte("q"))
		h, p, data, ok := parseRawUDPRequest(pkt)
		if !ok || h != host || p != 53 || !bytes.Equal(data, []byte("q")) {
			t.Fatalf("%s: %q %d %q %v", host, h, p, data, ok)
		}
	}
}
