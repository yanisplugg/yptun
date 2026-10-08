package main

import (
	"errors"
	"net"
	"net/netip"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// pumper shuttles raw IP packets between the netstack and the client's loopback UDP port.
type pumper struct {
	addr *net.UDPAddr // the client's listener
	mtu  int

	mu   sync.Mutex
	conn *net.UDPConn
	dev  tun.Device
	ip   netip.Addr
}

// attach (re)builds the netstack for the tunnel address the server assigned. It returns nil when the
// address is the one already in use.
func (p *pumper) attach(ip netip.Addr, dns []netip.Addr) (*netstack.Net, error) {
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
		_ = c.SetReadBuffer(4 << 20)
		_ = c.SetWriteBuffer(4 << 20)
		p.conn = c
		go p.fromClient(c)
	}
	if p.dev != nil {
		_ = p.dev.Close() // its reader goroutine ends on the error
	}
	p.dev, p.ip = dev, ip
	go p.toClient(dev, p.conn)
	return tnet, nil
}

// toClient: netstack → client. IPv6 is dropped: the server carries IPv4 only, and the host still
// routes ::/0 into its TUN so nothing leaks past the VPN.
func (p *pumper) toClient(dev tun.Device, c *net.UDPConn) {
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

// fromClient: client → netstack.
func (p *pumper) fromClient(c *net.UDPConn) {
	buf := make([]byte, 65535)
	for {
		n, _, err := c.ReadFromUDP(buf)
		if err != nil {
			if isClosed(err) {
				return
			}
			continue // Windows reports ICMP port-unreachable from before the client listened as a read error
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

func (p *pumper) close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.dev != nil {
		_ = p.dev.Close()
	}
	if p.conn != nil {
		_ = p.conn.Close()
	}
}

func isClosed(err error) bool { return errors.Is(err, net.ErrClosed) }
