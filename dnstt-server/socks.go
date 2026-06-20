package main

// Minimal no-auth SOCKS5 server used as the dnstt tunnel's built-in upstream. The olcvpn mobile
// dnstt client exposes a local SOCKS5 proxy and pipes that SOCKS5 byte-stream verbatim through each
// tunnel stream to the server's UPSTREAMADDR, so the upstream has to terminate SOCKS5. Rather than
// require the operator to install a separate proxy (microsocks/dante) on the VPS, the server can run
// this tiny CONNECT-only SOCKS5 listener itself (-socks-port) and act as a self-contained exit.
//
// Scope is intentionally small: SOCKS5, "no authentication", CONNECT command only, IPv4/IPv6/domain
// target addresses. BIND/UDP-ASSOCIATE are rejected. It binds 127.0.0.1 so only the local
// dnstt-server can reach it.

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"
)

const socksDialTimeout = 30 * time.Second

// startSocks5 binds a SOCKS5 listener on addr (expected to be 127.0.0.1:<port>) and serves it in the
// background. It returns once the listener is open so the caller can fail fast on a bind error.
func startSocks5(addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				log.Printf("socks5: accept: %v", err)
				return
			}
			go serveSocks5(conn.(*net.TCPConn))
		}
	}()
	return nil
}

func serveSocks5(client *net.TCPConn) {
	defer client.Close()

	target, err := socksHandshake(client)
	if err != nil {
		log.Printf("socks5: %v", err)
		return
	}

	upstream, err := net.DialTimeout("tcp", target, socksDialTimeout)
	if err != nil {
		// 0x05 = connection refused (close enough for any dial failure here).
		_, _ = client.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		log.Printf("socks5: dial %s: %v", target, err)
		return
	}
	defer upstream.Close()

	// Success reply with a dummy BND.ADDR (the client ignores it).
	if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	up := upstream.(*net.TCPConn)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		io.Copy(up, client)
		up.CloseWrite()
		client.CloseRead()
	}()
	go func() {
		defer wg.Done()
		io.Copy(client, up)
		client.CloseWrite()
		up.CloseRead()
	}()
	wg.Wait()
}

// socksHandshake performs the SOCKS5 greeting + CONNECT request and returns the target host:port.
func socksHandshake(client *net.TCPConn) (string, error) {
	// Greeting: VER NMETHODS METHODS...
	header := make([]byte, 2)
	if _, err := io.ReadFull(client, header); err != nil {
		return "", fmt.Errorf("read greeting: %v", err)
	}
	if header[0] != 0x05 {
		return "", fmt.Errorf("unsupported SOCKS version %d", header[0])
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return "", fmt.Errorf("read methods: %v", err)
	}
	// Select "no authentication required".
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return "", fmt.Errorf("write method choice: %v", err)
	}

	// Request: VER CMD RSV ATYP DST.ADDR DST.PORT
	req := make([]byte, 4)
	if _, err := io.ReadFull(client, req); err != nil {
		return "", fmt.Errorf("read request: %v", err)
	}
	if req[0] != 0x05 {
		return "", fmt.Errorf("bad request version %d", req[0])
	}
	if req[1] != 0x01 { // CONNECT only
		_, _ = client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // command not supported
		return "", fmt.Errorf("unsupported command %d", req[1])
	}

	var host string
	switch req[3] {
	case 0x01: // IPv4
		buf := make([]byte, 4)
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", fmt.Errorf("read ipv4: %v", err)
		}
		host = net.IP(buf).String()
	case 0x03: // domain name
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(client, lenBuf); err != nil {
			return "", fmt.Errorf("read domain len: %v", err)
		}
		buf := make([]byte, int(lenBuf[0]))
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", fmt.Errorf("read domain: %v", err)
		}
		host = string(buf)
	case 0x04: // IPv6
		buf := make([]byte, 16)
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", fmt.Errorf("read ipv6: %v", err)
		}
		host = net.IP(buf).String()
	default:
		_, _ = client.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // address type not supported
		return "", fmt.Errorf("unsupported address type %d", req[3])
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return "", fmt.Errorf("read port: %v", err)
	}
	port := binary.BigEndian.Uint16(portBuf)

	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
}
