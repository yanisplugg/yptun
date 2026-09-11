package wdtt

// YPtun: Raw mode without an OS TUN.
//
// Upstream's rawtun receives the Android VpnService fd and writes the server's raw IP packets straight
// into it. YPtun's hosts (Android, desktop, iOS) own the TUN themselves and feed every engine through a
// local SOCKS5, so here the raw packets go into a userspace gVisor netstack instead, and that netstack
// is served as a loopback SOCKS5 — CONNECT and UDP ASSOCIATE — exactly the shape of the AmneziaWG exit
// (awgproxy/awg/socks.go, which this SOCKS server is copied from: that module ties it to
// amneziawg-go's netstack type).

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// parseRawConf decodes the server's "RAWCONF:ip|dns,dns|mtu".
func parseRawConf(conf string) (ip netip.Addr, dns []netip.Addr, mtu int, err error) {
	parts := strings.Split(strings.TrimPrefix(conf, "RAWCONF:"), "|")
	if len(parts) != 3 {
		return ip, nil, 0, fmt.Errorf("bad RAWCONF %q", conf)
	}
	if ip, err = netip.ParseAddr(strings.TrimSpace(parts[0])); err != nil {
		return ip, nil, 0, fmt.Errorf("RAWCONF ip: %w", err)
	}
	for _, d := range strings.Split(parts[1], ",") {
		if a, e := netip.ParseAddr(strings.TrimSpace(d)); e == nil {
			dns = append(dns, a)
		}
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	if mtu, err = strconv.Atoi(strings.TrimSpace(parts[2])); err != nil || mtu < 576 {
		mtu = 1280
	}
	return ip, dns, mtu, nil
}

// tunPackets adapts a batch tun.Device to the one-packet Read/Write the dispatcher does on a TUN fd.
type tunPackets struct{ dev tun.Device }

func (t tunPackets) Read(p []byte) (int, error) {
	sizes := []int{0}
	if _, err := t.dev.Read([][]byte{p}, sizes, 0); err != nil {
		return 0, err
	}
	return sizes[0], nil
}

func (t tunPackets) Write(p []byte) (int, error) {
	if _, err := t.dev.Write([][]byte{p}, 0); err != nil {
		return 0, err
	}
	return len(p), nil
}

// startRawSocks raises the netstack from [rawConf], attaches it to [disp] and serves SOCKS5 on
// [listen] until ctx ends. Returns once the listener is up (or failed).
func startRawSocks(ctx context.Context, rawConf string, disp *Dispatcher, listen string) error {
	ip, dns, mtu, err := parseRawConf(rawConf)
	if err != nil {
		return err
	}
	dev, tnet, err := netstack.CreateNetTUN([]netip.Addr{ip}, dns, mtu)
	if err != nil {
		return fmt.Errorf("netstack: %w", err)
	}
	ln, err := net.Listen("tcp", listen)
	if err != nil {
		_ = dev.Close()
		return fmt.Errorf("SOCKS listen %s: %w", listen, err)
	}
	disp.AttachTUN(tunPackets{dev})
	go func() {
		<-ctx.Done()
		_ = ln.Close()
		_ = dev.Close()
	}()
	go serveRawSocks(ln, tnet)
	log.Printf("[RAW] netstack %s (DNS %v, MTU %d), SOCKS5 %s", ip, dns, mtu, listen)
	return nil
}

func serveRawSocks(ln net.Listener, tnet *netstack.Net) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return // listener closed
		}
		go handleRawSocks(c, tnet)
	}
}

func handleRawSocks(client net.Conn, tnet *netstack.Net) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))

	br := make([]byte, 2)
	if _, err := io.ReadFull(client, br); err != nil || br[0] != 0x05 {
		return
	}
	if _, err := io.ReadFull(client, make([]byte, int(br[1]))); err != nil {
		return
	}
	// No-auth only: the listener is loopback and internal to the host's engine chain.
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	head := make([]byte, 4)
	if _, err := io.ReadFull(client, head); err != nil || head[0] != 0x05 {
		return
	}
	host, err := readRawSocksAddr(client, head[3])
	if err != nil {
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBuf))))

	switch head[1] {
	case 0x01: // CONNECT
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		remote, err := tnet.DialContext(ctx, "tcp", target)
		cancel()
		if err != nil {
			_ = writeRawSocksReply(client, 0x05)
			return
		}
		defer remote.Close()
		if writeRawSocksReply(client, 0x00) != nil {
			return
		}
		_ = client.SetDeadline(time.Time{})
		rawPipe(client, remote)
	case 0x03: // UDP ASSOCIATE
		rawUDPAssociate(client, tnet)
	default:
		_ = writeRawSocksReply(client, 0x07)
	}
}

// rawUDPAssociate relays SOCKS5 UDP datagrams through the netstack for as long as the TCP control
// connection stays open (hence no deadline — DNS and QUIC ride this).
func rawUDPAssociate(client net.Conn, tnet *netstack.Net) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeRawSocksReply(client, 0x01)
		return
	}
	defer relay.Close()
	rep := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(rep[8:], uint16(relay.LocalAddr().(*net.UDPAddr).Port))
	if _, err := client.Write(rep); err != nil {
		return
	}
	_ = client.SetDeadline(time.Time{})

	conns := make(map[string]net.Conn)
	defer func() {
		for _, c := range conns {
			_ = c.Close()
		}
	}()
	go func() { _, _ = io.Copy(io.Discard, client); _ = relay.Close() }()

	buf := make([]byte, 64*1024)
	for {
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		host, port, payload, ok := parseRawUDPRequest(buf[:n])
		if !ok {
			continue
		}
		target := net.JoinHostPort(host, strconv.Itoa(port))
		uc := conns[target]
		if uc == nil {
			if uc, err = tnet.Dial("udp", target); err != nil {
				continue
			}
			conns[target] = uc
			go rawUDPReturn(relay, uc, from, host, port)
		}
		_, _ = uc.Write(payload)
	}
}

func rawUDPReturn(relay *net.UDPConn, uc net.Conn, client *net.UDPAddr, host string, port int) {
	buf := make([]byte, 64*1024)
	for {
		_ = uc.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := uc.Read(buf)
		if err != nil {
			return
		}
		if _, err := relay.WriteToUDP(buildRawUDPReply(host, port, buf[:n]), client); err != nil {
			return
		}
	}
}

func readRawSocksAddr(r io.Reader, atyp byte) (string, error) {
	switch atyp {
	case 0x01, 0x04:
		b := make([]byte, map[byte]int{0x01: 4, 0x04: 16}[atyp])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(r, l); err != nil {
			return "", err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		return string(b), nil
	}
	return "", errors.New("bad atyp")
}

// parseRawUDPRequest decodes a SOCKS5 UDP datagram: RSV(2) FRAG(1) ATYP ADDR PORT DATA.
func parseRawUDPRequest(p []byte) (host string, port int, data []byte, ok bool) {
	if len(p) < 5 || p[2] != 0x00 {
		return "", 0, nil, false
	}
	off := 4
	switch p[3] {
	case 0x01, 0x04:
		l := map[byte]int{0x01: 4, 0x04: 16}[p[3]]
		if len(p) < off+l+2 {
			return "", 0, nil, false
		}
		host = net.IP(p[off : off+l]).String()
		off += l
	case 0x03:
		l := int(p[off])
		off++
		if len(p) < off+l+2 {
			return "", 0, nil, false
		}
		host = string(p[off : off+l])
		off += l
	default:
		return "", 0, nil, false
	}
	return host, int(binary.BigEndian.Uint16(p[off : off+2])), p[off+2:], true
}

func buildRawUDPReply(host string, port int, data []byte) []byte {
	var addr []byte
	if ip, err := netip.ParseAddr(host); err == nil && ip.Is4() {
		b := ip.As4()
		addr = append([]byte{0x01}, b[:]...)
	} else if err == nil {
		b := ip.As16()
		addr = append([]byte{0x04}, b[:]...)
	} else {
		addr = append([]byte{0x03, byte(len(host))}, host...)
	}
	out := append([]byte{0x00, 0x00, 0x00}, addr...)
	out = binary.BigEndian.AppendUint16(out, uint16(port))
	return append(out, data...)
}

func writeRawSocksReply(c net.Conn, rep byte) error {
	_, err := c.Write([]byte{0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

func rawPipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
}

// ipv4Only drops IPv6 on the way in: the Raw server carries IPv4 only, and the host still routes ::/0
// into the TUN so IPv6 cannot leak past the VPN. Sending those packets over VK would only waste the relay.
type ipv4Only struct{ io.ReadWriteCloser }

func (t ipv4Only) Read(p []byte) (int, error) {
	for {
		n, err := t.ReadWriteCloser.Read(p)
		if err != nil || n == 0 || p[0]>>4 != 6 {
			return n, err
		}
	}
}
