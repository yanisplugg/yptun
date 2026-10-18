package main

// SOCKS5 over the netstack: CONNECT and UDP ASSOCIATE, no auth (the listener is loopback and internal to
// the host's engine chain). Copied from wdtt/raw_socks.go, which in turn follows awgproxy/awg/socks.go.

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// socksServer serves whichever netstack is current (it is replaced if the server re-assigns the tunnel IP).
type socksServer struct {
	net atomic.Pointer[netstack.Net]
}

func (s *socksServer) setNet(n *netstack.Net) { s.net.Store(n) }

func (s *socksServer) listen(ctx context.Context, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	go func() { <-ctx.Done(); _ = ln.Close() }()
	logf("[bridge] SOCKS5 on %s", ln.Addr())
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go handleSocks(c, s.net.Load())
		}
	}()
	return nil
}

func handleSocks(client net.Conn, tnet *netstack.Net) {
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
	host, err := readSocksAddr(client, head[3])
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
			_ = writeSocksReply(client, 0x05)
			return
		}
		defer remote.Close()
		if writeSocksReply(client, 0x00) != nil {
			return
		}
		_ = client.SetDeadline(time.Time{})
		pipe(client, remote)
	case 0x03: // UDP ASSOCIATE
		udpAssociate(client, tnet)
	default:
		_ = writeSocksReply(client, 0x07)
	}
}

// udpAssociate relays SOCKS5 UDP datagrams through the netstack for as long as the TCP control
// connection stays open (hence no deadline — DNS and QUIC ride this).
func udpAssociate(client net.Conn, tnet *netstack.Net) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSocksReply(client, 0x01)
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
		host, port, payload, ok := parseUDPRequest(buf[:n])
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
			go udpReturn(relay, uc, from, host, port)
		}
		_, _ = uc.Write(payload)
	}
}

func udpReturn(relay *net.UDPConn, uc net.Conn, client *net.UDPAddr, host string, port int) {
	buf := make([]byte, 64*1024)
	for {
		_ = uc.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := uc.Read(buf)
		if err != nil {
			return
		}
		if _, err := relay.WriteToUDP(buildUDPReply(host, port, buf[:n]), client); err != nil {
			return
		}
	}
}

func readSocksAddr(r io.Reader, atyp byte) (string, error) {
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

// parseUDPRequest decodes a SOCKS5 UDP datagram: RSV(2) FRAG(1) ATYP ADDR PORT DATA.
func parseUDPRequest(p []byte) (host string, port int, data []byte, ok bool) {
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

func buildUDPReply(host string, port int, data []byte) []byte {
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
