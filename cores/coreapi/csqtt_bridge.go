package coreapi

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type csqttPumper struct {
	addr *net.UDPAddr
	mtu  int

	mu   sync.Mutex
	conn *net.UDPConn
	dev  tun.Device
	ip   netip.Addr
}

func (p *csqttPumper) attach(ip netip.Addr, dns []netip.Addr) (*netstack.Net, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.dev != nil && p.ip == ip {
		return nil, nil
	}
	dev, tnet, err := netstack.CreateNetTUN([]netip.Addr{ip}, dns, p.mtu)
	if err != nil {
		return nil, err
	}
	if p.conn == nil {
		c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			_ = dev.Close()
			return nil, err
		}
		_ = c.SetReadBuffer(1 << 20)
		_ = c.SetWriteBuffer(1 << 20)
		p.conn = c
		go p.fromClient(c)
	}
	if p.dev != nil {
		_ = p.dev.Close()
	}
	p.dev, p.ip = dev, ip
	go p.toClient(dev, p.conn)
	return tnet, nil
}

func (p *csqttPumper) toClient(dev tun.Device, c *net.UDPConn) {
	buf := make([]byte, 65535)
	bufs, sizes := [][]byte{buf}, []int{0}
	for {
		if _, err := dev.Read(bufs, sizes, 0); err != nil {
			return
		}
		n := sizes[0]
		if n == 0 || buf[0]>>4 != 4 {
			continue
		}
		_, _ = c.WriteToUDP(buf[:n], p.addr)
	}
}

func (p *csqttPumper) fromClient(c *net.UDPConn) {
	buf := make([]byte, 65535)
	for {
		n, _, err := c.ReadFromUDP(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		p.mu.Lock()
		dev := p.dev
		p.mu.Unlock()
		if dev != nil && n > 0 {
			pkt := make([]byte, n)
			copy(pkt, buf[:n])
			_, _ = dev.Write([][]byte{pkt}, 0)
		}
	}
}

func (p *csqttPumper) close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.dev != nil {
		_ = p.dev.Close()
	}
	if p.conn != nil {
		_ = p.conn.Close()
	}
}

type csqttSocksServer struct {
	net atomic.Pointer[netstack.Net]
}

func (s *csqttSocksServer) setNet(n *netstack.Net) { s.net.Store(n) }

func (s *csqttSocksServer) listen(ctx context.Context, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	go func() { <-ctx.Done(); _ = ln.Close() }()
	PushLog("csqtt", fmt.Sprintf("[bridge] SOCKS5 listening on %s", ln.Addr()))
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go handleCsqttSocks(c, s.net.Load())
		}
	}()
	return nil
}

func handleCsqttSocks(client net.Conn, tnet *netstack.Net) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(30 * time.Second))

	br := make([]byte, 2)
	if _, err := io.ReadFull(client, br); err != nil || br[0] != 0x05 {
		return
	}
	if _, err := io.ReadFull(client, make([]byte, int(br[1]))); err != nil {
		return
	}
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(client, req); err != nil || req[0] != 0x05 {
		return
	}

	var targetHost string
	switch req[3] {
	case 0x01: // IPv4
		ip := make([]byte, 4)
		if _, err := io.ReadFull(client, ip); err != nil {
			return
		}
		targetHost = net.IP(ip).String()
	case 0x03: // Domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(client, l); err != nil {
			return
		}
		domain := make([]byte, int(l[0]))
		if _, err := io.ReadFull(client, domain); err != nil {
			return
		}
		targetHost = string(domain)
	case 0x04: // IPv6
		ip := make([]byte, 16)
		if _, err := io.ReadFull(client, ip); err != nil {
			return
		}
		targetHost = net.IP(ip).String()
	default:
		return
	}

	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(client, portBytes); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(portBytes)
	targetAddr := fmt.Sprintf("%s:%d", targetHost, targetPort)

	switch req[1] {
	case 0x01: // CONNECT
		if tnet == nil {
			_, _ = client.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		_ = client.SetDeadline(time.Time{})
		remote, err := tnet.Dial("tcp", targetAddr)
		if err != nil {
			_, _ = client.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer remote.Close()
		if _, err := client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
		go func() { _, _ = io.Copy(remote, client) }()
		_, _ = io.Copy(client, remote)

	case 0x03: // UDP ASSOCIATE
		handleCsqttUdpAssociate(client, tnet)
	default:
		_, _ = client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	}
}

func handleCsqttUdpAssociate(client net.Conn, tnet *netstack.Net) {
	if tnet == nil {
		_, _ = client.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	relay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_, _ = client.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer relay.Close()

	port := uint16(relay.LocalAddr().(*net.UDPAddr).Port)
	resp := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(port >> 8), byte(port)}
	if _, err := client.Write(resp); err != nil {
		return
	}

	_ = client.SetDeadline(time.Time{})
	tcpClosed := make(chan struct{})
	go func() {
		buf := make([]byte, 1)
		for {
			if _, err := client.Read(buf); err != nil {
				close(tcpClosed)
				_ = relay.Close()
				return
			}
		}
	}()

	var clientAddr atomic.Pointer[net.UDPAddr]
	buf := make([]byte, 65535)
	for {
		n, src, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		clientAddr.Store(src)
		if n < 4 || buf[2] != 0x00 {
			continue
		}
		dest, headerLen := parseCsqttSocksUdpHeader(buf[:n])
		if dest == "" || headerLen >= n {
			continue
		}
		payload := buf[headerLen:n]
		go func(dst string, data []byte) {
			conn, err := tnet.Dial("udp", dst)
			if err != nil {
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			if _, err := conn.Write(data); err != nil {
				return
			}
			respBuf := make([]byte, 65535)
			rn, err := conn.Read(respBuf)
			if err != nil || rn == 0 {
				return
			}
			dstAddr := clientAddr.Load()
			if dstAddr == nil {
				return
			}
			hdr := formatCsqttSocksUdpHeader(dst)
			out := append(hdr, respBuf[:rn]...)
			_, _ = relay.WriteToUDP(out, dstAddr)
		}(dest, payload)
	}
}

func parseCsqttSocksUdpHeader(buf []byte) (string, int) {
	if len(buf) < 4 {
		return "", 0
	}
	switch buf[3] {
	case 0x01: // IPv4
		if len(buf) < 10 {
			return "", 0
		}
		ip := net.IP(buf[4:8]).String()
		port := binary.BigEndian.Uint16(buf[8:10])
		return fmt.Sprintf("%s:%d", ip, port), 10
	case 0x03: // Domain
		if len(buf) < 5 {
			return "", 0
		}
		l := int(buf[4])
		if len(buf) < 5+l+2 {
			return "", 0
		}
		domain := string(buf[5 : 5+l])
		port := binary.BigEndian.Uint16(buf[5+l : 5+l+2])
		return fmt.Sprintf("%s:%d", domain, port), 5 + l + 2
	case 0x04: // IPv6
		if len(buf) < 22 {
			return "", 0
		}
		ip := net.IP(buf[4:20]).String()
		port := binary.BigEndian.Uint16(buf[20:22])
		return fmt.Sprintf("[%s]:%d", ip, port), 22
	}
	return "", 0
}

func formatCsqttSocksUdpHeader(target string) []byte {
	host, portStr, _ := net.SplitHostPort(target)
	portNum, _ := strconv.Atoi(portStr)
	port := uint16(portNum)
	ip := net.ParseIP(host)
	if ip4 := ip.To4(); ip4 != nil {
		hdr := []byte{0, 0, 0, 1}
		hdr = append(hdr, ip4...)
		hdr = append(hdr, byte(port>>8), byte(port))
		return hdr
	}
	hdr := []byte{0, 0, 0, 3, byte(len(host))}
	hdr = append(hdr, []byte(host)...)
	hdr = append(hdr, byte(port>>8), byte(port))
	return hdr
}

var (
	csqttBridgeMu      sync.Mutex
	csqttBridgeRunning atomic.Bool
	csqttPumperInst    *csqttPumper
	csqttSocksInst     *csqttSocksServer
	csqttCancel        context.CancelFunc
	csqttTunnelReadyCh chan struct{}
)

func CsqttBridgeStart(socksAddr, clientUdpAddr string, mtu int) error {
	csqttBridgeMu.Lock()
	defer csqttBridgeMu.Unlock()
	if csqttBridgeRunning.Load() {
		return errors.New("csqtt bridge already running")
	}
	raddr, err := net.ResolveUDPAddr("udp4", clientUdpAddr)
	if err != nil {
		return err
	}
	if mtu <= 0 {
		mtu = 1300
	}
	ctx, cancel := context.WithCancel(context.Background())
	csqttCancel = cancel
	csqttTunnelReadyCh = make(chan struct{})

	pumper := &csqttPumper{addr: raddr, mtu: mtu}
	socks := &csqttSocksServer{}
	if err := socks.listen(ctx, socksAddr); err != nil {
		cancel()
		pumper.close()
		return err
	}
	csqttPumperInst = pumper
	csqttSocksInst = socks
	csqttBridgeRunning.Store(true)
	PushLog("csqtt", fmt.Sprintf("bridge started: socks on %s, client UDP %s", socksAddr, clientUdpAddr))
	return nil
}

func CsqttBridgeSetTunnel(tunConf string) error {
	csqttBridgeMu.Lock()
	pumper := csqttPumperInst
	socks := csqttSocksInst
	csqttBridgeMu.Unlock()
	if pumper == nil || socks == nil {
		return errors.New("bridge not running")
	}

	conf := strings.TrimPrefix(tunConf, "TUNCONF:")
	fields := strings.Split(conf, ":")
	if len(fields) < 2 {
		return fmt.Errorf("invalid TUNCONF format: %s", tunConf)
	}
	ipStr := fields[0]
	dnsStr := fields[1]
	ip, err := netip.ParseAddr(ipStr)
	if err != nil {
		return fmt.Errorf("parse IP %s: %w", ipStr, err)
	}
	var dns []netip.Addr
	for _, d := range strings.Split(dnsStr, ",") {
		if a, err := netip.ParseAddr(strings.TrimSpace(d)); err == nil {
			dns = append(dns, a)
		}
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}

	tnet, err := pumper.attach(ip, dns)
	if err != nil {
		return fmt.Errorf("attach netstack: %w", err)
	}
	if tnet != nil {
		socks.setNet(tnet)
		PushLog("csqtt", fmt.Sprintf("netstack configured: IP %s, DNS %v", ip, dns))
		csqttBridgeMu.Lock()
		if csqttTunnelReadyCh != nil {
			select {
			case <-csqttTunnelReadyCh:
			default:
				close(csqttTunnelReadyCh)
			}
		}
		csqttBridgeMu.Unlock()
	}
	return nil
}

func CsqttBridgeStop() {
	csqttBridgeMu.Lock()
	defer csqttBridgeMu.Unlock()
	if !csqttBridgeRunning.Load() {
		return
	}
	if csqttCancel != nil {
		csqttCancel()
		csqttCancel = nil
	}
	if csqttTunnelReadyCh != nil {
		select {
		case <-csqttTunnelReadyCh:
		default:
			close(csqttTunnelReadyCh)
		}
		csqttTunnelReadyCh = nil
	}
	if csqttPumperInst != nil {
		csqttPumperInst.close()
		csqttPumperInst = nil
	}
	csqttSocksInst = nil
	csqttBridgeRunning.Store(false)
	PushLog("csqtt", "bridge stopped")
}

func CsqttBridgeIsRunning() bool {
	return csqttBridgeRunning.Load()
}

func CsqttBridgeIsTunnelReady() bool {
	csqttBridgeMu.Lock()
	socks := csqttSocksInst
	csqttBridgeMu.Unlock()
	return socks != nil && socks.net.Load() != nil
}

func CsqttBridgeWaitTunnel(timeoutMs int) bool {
	csqttBridgeMu.Lock()
	socks := csqttSocksInst
	ch := csqttTunnelReadyCh
	csqttBridgeMu.Unlock()
	if socks != nil && socks.net.Load() != nil {
		return true
	}
	if ch == nil {
		return false
	}
	select {
	case <-ch:
		return true
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return false
	}
}
