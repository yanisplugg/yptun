// socks.go is a minimal SOCKS5 server run over the REASSEMBLED bond stream. The client's sing-box speaks
// SOCKS5 (CONNECT <vless-host:port>) into the Chain port; those bytes are striped across rooms and
// reassembled here verbatim, so the reassembled stream begins with that SOCKS5 session. We terminate it
// and dial the real target directly — no dependency on olcRTC's internal SOCKS.
package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"
)

// serveSocks consumes a SOCKS5 client session on conn, dials the requested target and pipes both ways.
// If wantUser != "" username/password auth is required and validated; otherwise no-auth is offered and
// any username/password is accepted (access is already gated by the room SOCKS auth on each lane).
func serveSocks(conn net.Conn, wantUser, wantPass string, dialTimeout time.Duration) {
	defer conn.Close()
	br := conn // simple; SOCKS handshake reads are small

	// Greeting.
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(br, hdr); err != nil {
		return
	}
	if hdr[0] != 0x05 {
		return
	}
	nmethods := int(hdr[1])
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	offersUserPass := false
	offersNoAuth := false
	for _, m := range methods {
		if m == 0x02 {
			offersUserPass = true
		}
		if m == 0x00 {
			offersNoAuth = true
		}
	}

	if wantUser != "" {
		if !offersUserPass {
			_, _ = br.Write([]byte{0x05, 0xFF})
			return
		}
		_, _ = br.Write([]byte{0x05, 0x02})
		if !readAuth(br, wantUser, wantPass, true) {
			return
		}
	} else if offersUserPass {
		// Accept and consume the credentials without validating (room SOCKS already authed the lane).
		_, _ = br.Write([]byte{0x05, 0x02})
		if !readAuth(br, "", "", false) {
			return
		}
	} else if offersNoAuth {
		_, _ = br.Write([]byte{0x05, 0x00})
	} else {
		_, _ = br.Write([]byte{0x05, 0xFF})
		return
	}

	// Request.
	reqHdr := make([]byte, 4)
	if _, err := io.ReadFull(br, reqHdr); err != nil {
		return
	}
	if reqHdr[0] != 0x05 || reqHdr[1] != 0x01 { // only CONNECT
		_, _ = br.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	host, err := readAddr(br, reqHdr[3])
	if err != nil {
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(br, portBuf); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf)
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))

	remote, err := net.DialTimeout("tcp", target, dialTimeout)
	if err != nil {
		_, _ = br.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // connection refused
		return
	}
	defer remote.Close()
	// Success reply (BND.ADDR/PORT 0.0.0.0:0 — clients ignore it for CONNECT).
	if _, err := br.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	pipe(br, remote)
}

func readAuth(conn net.Conn, wantUser, wantPass string, validate bool) bool {
	h := make([]byte, 2)
	if _, err := io.ReadFull(conn, h); err != nil {
		return false
	}
	if h[0] != 0x01 {
		return false
	}
	ulen := int(h[1])
	u := make([]byte, ulen)
	if _, err := io.ReadFull(conn, u); err != nil {
		return false
	}
	pl := make([]byte, 1)
	if _, err := io.ReadFull(conn, pl); err != nil {
		return false
	}
	plen := int(pl[0])
	p := make([]byte, plen)
	if _, err := io.ReadFull(conn, p); err != nil {
		return false
	}
	ok := !validate || (string(u) == wantUser && string(p) == wantPass)
	status := byte(0x00)
	if !ok {
		status = 0x01
	}
	_, _ = conn.Write([]byte{0x01, status})
	return ok
}

func readAddr(conn net.Conn, atyp byte) (string, error) {
	switch atyp {
	case 0x01: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x04: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		return net.IP(b).String(), nil
	case 0x03: // domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return "", err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		return string(b), nil
	default:
		return "", fmt.Errorf("socks: bad atyp %d", atyp)
	}
}

func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(a, b); done <- struct{}{} }()
	go func() { _, _ = io.Copy(b, a); done <- struct{}{} }()
	<-done
}
