package awg

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
)

// serveSocks accepts SOCKS5 clients on ln and routes them through the AmneziaWG netstack tnet.
func serveSocks(ln net.Listener, tnet *netstack.Net) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return // listener closed
		}
		go handleSocks(c, tnet)
	}
}

func handleSocks(client net.Conn, tnet *netstack.Net) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))

	br := make([]byte, 2)
	if _, err := io.ReadFull(client, br); err != nil || br[0] != 0x05 {
		return
	}
	methods := make([]byte, int(br[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	// We only offer no-auth (the proxy is loopback-only).
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	head := make([]byte, 4)
	if _, err := io.ReadFull(client, head); err != nil || head[0] != 0x05 {
		return
	}
	host, err := readSocksAddr(client, head[3])
	if err != nil {
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	target := net.JoinHostPort(host, strconv.Itoa(port))

	switch head[1] {
	case 0x01: // CONNECT
		socksTCPConnect(client, tnet, target)
	case 0x03: // UDP ASSOCIATE
		socksUDPAssociate(client, tnet)
	default:
		_ = writeSocksReply(client, 0x07) // command not supported
	}
}

func socksTCPConnect(client net.Conn, tnet *netstack.Net, target string) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	remote, err := tnet.DialContext(ctx, "tcp", target)
	if err != nil {
		// Only failures are logged (success would be one line per connection → journal spam).
		log.New(logSink, "", 0).Printf("socks connect to %s failed: %v", target, err)
		_ = writeSocksReply(client, 0x05) // connection refused
		return
	}
	defer remote.Close()
	if err := writeSocksReply(client, 0x00); err != nil {
		return
	}
	_ = client.SetDeadline(time.Time{})
	pipe(client, remote)
}

// socksUDPAssociate opens a loopback UDP relay socket the client sends datagrams to, parses each
// SOCKS5 UDP header, and forwards the payload to the target through the AmneziaWG netstack.
func socksUDPAssociate(client net.Conn, tnet *netstack.Net) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSocksReply(client, 0x01)
		return
	}
	defer relay.Close()

	bound := relay.LocalAddr().(*net.UDPAddr)
	// Reply with the relay address the client must send UDP to.
	rep := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(rep[8:], uint16(bound.Port))
	if _, err := client.Write(rep); err != nil {
		return
	}
	// Clear the handshake deadline set in handleSocks: a SOCKS5 UDP association lives as long as its
	// TCP control connection stays open (the client holds it idle). Without this the 30s deadline fired,
	// io.Copy(client) below returned, and relay.Close() tore down ALL UDP after 30s — so standalone
	// AmneziaWG lost DNS/QUIC and "only worked" behind a second proxy (which rides a long-lived CONNECT).
	_ = client.SetDeadline(time.Time{})

	// Per-target UDP conns through the tunnel; return packets are wrapped back to the client addr.
	// The netstack Dial returns a gVisor gonet conn (net.Conn), NOT *net.UDPConn — never assert.
	conns := make(map[string]net.Conn)
	defer func() {
		for _, c := range conns {
			_ = c.Close()
		}
	}()

	// Close the relay when the TCP control connection drops.
	go func() { io.Copy(io.Discard, client); relay.Close() }()

	buf := make([]byte, 64*1024)
	for {
		n, from, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		dstHost, dstPort, payload, ok := parseUDPRequest(buf[:n])
		if !ok {
			continue
		}
		target := net.JoinHostPort(dstHost, strconv.Itoa(dstPort))
		uc := conns[target]
		if uc == nil {
			rc, derr := tnet.Dial("udp", target)
			if derr != nil {
				continue
			}
			uc = rc
			conns[target] = uc
			go udpReturn(relay, uc, from, dstHost, dstPort)
		}
		_, _ = uc.Write(payload)
	}
}

// udpReturn reads replies from the tunnel UDP conn and forwards them (SOCKS5-wrapped) to client.
func udpReturn(relay *net.UDPConn, uc net.Conn, client *net.UDPAddr, host string, port int) {
	buf := make([]byte, 64*1024)
	for {
		_ = uc.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := uc.Read(buf)
		if err != nil {
			return
		}
		out := buildUDPReply(host, port, buf[:n])
		if _, err := relay.WriteToUDP(out, client); err != nil {
			return
		}
	}
}

func readSocksAddr(r io.Reader, atyp byte) (string, error) {
	switch atyp {
	case 0x01:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x04:
		b := make([]byte, 16)
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

// parseUDPRequest decodes a SOCKS5 UDP datagram: RSV(2) FRAG(1) ATYP ADDR PORT DATA.
func parseUDPRequest(p []byte) (host string, port int, data []byte, ok bool) {
	if len(p) < 5 || p[2] != 0x00 {
		return "", 0, nil, false
	}
	off := 4
	switch p[3] {
	case 0x01:
		if len(p) < off+4+2 {
			return "", 0, nil, false
		}
		host = net.IP(p[off : off+4]).String()
		off += 4
	case 0x04:
		if len(p) < off+16+2 {
			return "", 0, nil, false
		}
		host = net.IP(p[off : off+16]).String()
		off += 16
	case 0x03:
		if len(p) < off+1 {
			return "", 0, nil, false
		}
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
	port = int(binary.BigEndian.Uint16(p[off : off+2]))
	off += 2
	return host, port, p[off:], true
}

func buildUDPReply(host string, port int, data []byte) []byte {
	var addr []byte
	if ip, err := netip.ParseAddr(host); err == nil && ip.Is4() {
		b := ip.As4()
		addr = append([]byte{0x01}, b[:]...)
	} else if err == nil && ip.Is6() {
		b := ip.As16()
		addr = append([]byte{0x04}, b[:]...)
	} else {
		addr = append([]byte{0x03, byte(len(host))}, []byte(host)...)
	}
	out := []byte{0x00, 0x00, 0x00}
	out = append(out, addr...)
	p := make([]byte, 2)
	binary.BigEndian.PutUint16(p, uint16(port))
	out = append(out, p...)
	return append(out, data...)
}

func writeSocksReply(c net.Conn, rep byte) error {
	_, err := c.Write([]byte{0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

func pipe(a, b net.Conn) {
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
