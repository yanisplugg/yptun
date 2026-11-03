package gate

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/proxy"
)

// ai-generated: the whole file (TCP and UDP through the tunnel's SOCKS5
// listener, RFC 1928).

var (
	// ErrSocksReply is a SOCKS5 answer the helpers cannot use: a refusal, a
	// version or an address type they do not speak, a fragment.
	ErrSocksReply = errors.New("unexpected SOCKS5 reply")
	// ErrUDPTarget is a datagram destination other than an IPv4 address with
	// a port, which is all an association here carries.
	ErrUDPTarget = errors.New("udp target is not an IPv4 address and port")
	// ErrSocksDialer is a SOCKS5 dialer from x/net/proxy that cannot take a
	// context.
	ErrSocksDialer = errors.New("socks dialer without DialContext")
)

const (
	// socksDialTimeout bounds the TCP connect to the SOCKS listener; the
	// caller's context bounds the rest.
	socksDialTimeout = 10 * time.Second
	// socksIPv4 is the address type of an IPv4 address.
	socksIPv4 = 1
	// udpHeaderLen is a datagram header with an IPv4 address: two reserved
	// bytes, the fragment number, the address type, the address, the port.
	udpHeaderLen = 10
)

// SocksDialer dials TCP through the tunnel's SOCKS5 listener at socksAddr,
// one CONNECT per dial.
func SocksDialer(socksAddr string) (DialFunc, error) {
	if _, _, err := net.SplitHostPort(socksAddr); err != nil {
		return nil, fmt.Errorf("socks address: %w", err)
	}
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, &net.Dialer{Timeout: socksDialTimeout})
	if err != nil {
		return nil, fmt.Errorf("socks dialer: %w", err)
	}
	cd, ok := d.(proxy.ContextDialer)
	if !ok {
		return nil, fmt.Errorf("%T: %w", d, ErrSocksDialer)
	}
	return cd.DialContext, nil
}

// HTTPClient makes every request a fresh connect through dial: no
// keep-alives and so no HTTP/2, because the connect path is what the load
// scenarios measure. timeout bounds a whole request, its body included.
func HTTPClient(dial DialFunc, timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			DialContext:       dial,
			DisableKeepAlives: true,
		},
	}
}

// UDPAssoc is a SOCKS5 UDP ASSOCIATE: the control connection held open and a
// socket connected to the relay the server opened for it, so nothing but the
// relay's datagrams is read.
type UDPAssoc struct {
	control net.Conn
	relay   *net.UDPConn
}

// UDPAssociate opens an association on the tunnel's SOCKS listener. ctx
// bounds the opening, as it bounds a dial; the association lasts until
// Close.
func UDPAssociate(ctx context.Context, socksAddr string) (*UDPAssoc, error) {
	var d net.Dialer
	control, err := d.DialContext(ctx, "tcp4", socksAddr)
	if err != nil {
		return nil, fmt.Errorf("dial socks: %w", err)
	}
	// A context that ends mid-exchange fails the pending read at once.
	stop := context.AfterFunc(ctx, func() { _ = control.SetDeadline(time.Unix(1, 0)) })
	relayTo, err := associate(control)
	if !stop() {
		err = fmt.Errorf("udp associate: %w", ctx.Err())
	}
	if err != nil {
		_ = control.Close()
		return nil, err
	}
	// The engine takes datagrams only from the control connection's peer
	// address, so the relay socket is bound to that same address.
	relay, err := net.DialUDP("udp4", &net.UDPAddr{IP: tcpIP(control.LocalAddr())}, relayTo)
	if err != nil {
		_ = control.Close()
		return nil, fmt.Errorf("relay socket: %w", err)
	}
	return &UDPAssoc{control: control, relay: relay}, nil
}

// associate runs the greeting and the UDP ASSOCIATE request on control and
// returns the relay address the server answers with.
func associate(control net.Conn) (*net.UDPAddr, error) {
	// Version 5, one method: no authentication.
	if _, err := control.Write([]byte{5, 1, 0}); err != nil {
		return nil, fmt.Errorf("socks greeting: %w", err)
	}
	reply := make([]byte, udpHeaderLen)
	if _, err := io.ReadFull(control, reply[:2]); err != nil {
		return nil, fmt.Errorf("socks greeting reply: %w", err)
	}
	if reply[0] != 5 || reply[1] != 0 {
		return nil, fmt.Errorf("socks greeting answered %v: %w", reply[:2], ErrSocksReply)
	}
	// From 0.0.0.0:0: the source of the datagrams is not known yet, which is
	// what a tun2socks in front of the client sends as well.
	if _, err := control.Write([]byte{5, 3, 0, socksIPv4, 0, 0, 0, 0, 0, 0}); err != nil {
		return nil, fmt.Errorf("udp associate request: %w", err)
	}
	// The head first: a refusal may carry a shorter address than IPv4.
	if _, err := io.ReadFull(control, reply[:4]); err != nil {
		return nil, fmt.Errorf("udp associate reply: %w", err)
	}
	if reply[0] != 5 || reply[1] != 0 || reply[3] != socksIPv4 {
		return nil, fmt.Errorf("udp associate answered %v: %w", reply[:4], ErrSocksReply)
	}
	if _, err := io.ReadFull(control, reply[4:]); err != nil {
		return nil, fmt.Errorf("udp associate relay address: %w", err)
	}
	port := int(binary.BigEndian.Uint16(reply[8:]))
	relayTo := &net.UDPAddr{IP: net.IPv4(reply[4], reply[5], reply[6], reply[7]), Port: port}
	if relayTo.IP.IsUnspecified() {
		// A server bound to every interface may answer 0.0.0.0; SOCKS clients
		// take that as the address the control connection reached.
		relayTo.IP = tcpIP(control.RemoteAddr())
	}
	return relayTo, nil
}

// tcpIP is the IP of a TCP address, nil for any other address.
func tcpIP(addr net.Addr) net.IP {
	if a, ok := addr.(*net.TCPAddr); ok {
		return a.IP
	}
	return nil
}

// Send relays one datagram to dst, an IPv4 address and port (ErrUDPTarget
// otherwise), through the association.
func (u *UDPAssoc) Send(dst *net.UDPAddr, payload []byte) error {
	if dst == nil || dst.IP.To4() == nil || dst.Port <= 0 || dst.Port > math.MaxUint16 {
		return fmt.Errorf("send to %v: %w", dst, ErrUDPTarget)
	}
	if _, err := u.relay.Write(append(encodeUDPHeader(dst), payload...)); err != nil {
		return fmt.Errorf("relay write: %w", err)
	}
	return nil
}

// Recv waits until deadline for one relayed datagram and returns its payload,
// a slice of buf, and the address that sent it.
func (u *UDPAssoc) Recv(buf []byte, deadline time.Time) ([]byte, *net.UDPAddr, error) {
	if err := u.relay.SetReadDeadline(deadline); err != nil {
		return nil, nil, fmt.Errorf("relay deadline: %w", err)
	}
	n, err := u.relay.Read(buf)
	if err != nil {
		return nil, nil, fmt.Errorf("relay read: %w", err)
	}
	from, payload, err := decodeUDPHeader(buf[:n])
	if err != nil {
		return nil, nil, err
	}
	return payload, from, nil
}

// Close ends the association; the server drops its relay with the control
// connection.
func (u *UDPAssoc) Close() {
	_ = u.relay.Close()
	_ = u.control.Close()
}

// encodeUDPHeader is the header of a datagram to dst, an IPv4 address (Send
// checks it).
func encodeUDPHeader(dst *net.UDPAddr) []byte {
	hdr := make([]byte, udpHeaderLen)
	hdr[3] = socksIPv4
	copy(hdr[4:8], dst.IP.To4())
	binary.BigEndian.PutUint16(hdr[8:], dst.AddrPort().Port())
	return hdr
}

// decodeUDPHeader splits a relayed datagram into the address that sent it and
// the payload. IPv4 only and no fragments: the association asks for nothing
// else.
func decodeUDPHeader(pkt []byte) (*net.UDPAddr, []byte, error) {
	if len(pkt) < udpHeaderLen || pkt[0] != 0 || pkt[1] != 0 || pkt[2] != 0 || pkt[3] != socksIPv4 {
		return nil, nil, fmt.Errorf("udp header %v: %w", pkt[:min(len(pkt), 4)], ErrSocksReply)
	}
	from := &net.UDPAddr{IP: net.IPv4(pkt[4], pkt[5], pkt[6], pkt[7]), Port: int(binary.BigEndian.Uint16(pkt[8:]))}
	return from, pkt[udpHeaderLen:], nil
}
