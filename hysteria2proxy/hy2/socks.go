package hy2

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

// serveSocks accepts SOCKS5 clients on ln and routes them through the Hysteria2 client hc.
func serveSocks(ln net.Listener, hc client.Client) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return // listener closed
		}
		go handleSocks(c, hc)
	}
}

func handleSocks(conn net.Conn, hc client.Client) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	br := make([]byte, 2)
	if _, err := io.ReadFull(conn, br); err != nil || br[0] != 0x05 {
		return
	}
	methods := make([]byte, int(br[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	// No-auth only (loopback proxy).
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil || head[0] != 0x05 {
		return
	}
	host, err := readSocksAddr(conn, head[3])
	if err != nil {
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	target := net.JoinHostPort(host, strconv.Itoa(port))

	switch head[1] {
	case 0x01: // CONNECT
		socksTCPConnect(conn, hc, target)
	case 0x03: // UDP ASSOCIATE
		socksUDPAssociate(conn, hc)
	default:
		_ = writeSocksReply(conn, 0x07) // command not supported
	}
}

func socksTCPConnect(conn net.Conn, hc client.Client, target string) {
	remote, err := hc.TCP(target)
	if err != nil {
		_ = writeSocksReply(conn, 0x05) // connection refused
		return
	}
	defer remote.Close()
	if err := writeSocksReply(conn, 0x00); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})
	pipe(conn, remote)
}

// socksUDPAssociate opens a loopback UDP relay, multiplexes the client's datagrams over a single
// Hysteria2 UDP session (HyUDPConn), and wraps replies back to the client.
func socksUDPAssociate(ctrl net.Conn, hc client.Client) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = writeSocksReply(ctrl, 0x01)
		return
	}
	defer relay.Close()

	hu, err := hc.UDP()
	if err != nil {
		_ = writeSocksReply(ctrl, 0x01)
		return
	}
	defer hu.Close()

	bound := relay.LocalAddr().(*net.UDPAddr)
	rep := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}
	binary.BigEndian.PutUint16(rep[8:], uint16(bound.Port))
	if _, err := ctrl.Write(rep); err != nil {
		return
	}

	// Track the client's source addr so replies (which arrive by domain/ip target) go back to it.
	var clientAddr udpAddrHolder

	// Close the relay + UDP session when the TCP control connection drops.
	go func() { io.Copy(io.Discard, ctrl); relay.Close(); hu.Close() }()

	// Replies: read from the Hysteria2 UDP session and forward (SOCKS5-wrapped) to the client.
	go func() {
		for {
			data, from, rerr := hu.Receive()
			if rerr != nil {
				return
			}
			ca := clientAddr.get()
			if ca == nil {
				continue
			}
			host, portStr, serr := net.SplitHostPort(from)
			if serr != nil {
				continue
			}
			p, _ := strconv.Atoi(portStr)
			out := buildUDPReply(host, p, data)
			if _, werr := relay.WriteToUDP(out, ca); werr != nil {
				return
			}
		}
	}()

	buf := make([]byte, 64*1024)
	for {
		n, from, rerr := relay.ReadFromUDP(buf)
		if rerr != nil {
			return
		}
		clientAddr.set(from)
		dstHost, dstPort, payload, ok := parseUDPRequest(buf[:n])
		if !ok {
			continue
		}
		if serr := hu.Send(payload, net.JoinHostPort(dstHost, strconv.Itoa(dstPort))); serr != nil {
			return
		}
	}
}

// udpAddrHolder is a tiny mutex-guarded holder for the client's UDP source address.
type udpAddrHolder struct {
	mu sync.Mutex
	a  *net.UDPAddr
}

func (h *udpAddrHolder) set(a *net.UDPAddr) { h.mu.Lock(); h.a = a; h.mu.Unlock() }
func (h *udpAddrHolder) get() *net.UDPAddr  { h.mu.Lock(); defer h.mu.Unlock(); return h.a }

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
