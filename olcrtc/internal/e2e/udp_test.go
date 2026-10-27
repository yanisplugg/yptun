package e2e

import (
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/server"
)

func startUDPEchoServer(t *testing.T) string {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen udp echo: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	go func() {
		buf := make([]byte, 64*1024)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			_, _ = conn.WriteToUDP(buf[:n], addr)
		}
	}()
	return conn.LocalAddr().String()
}

// startMemoryTunnel brings up a server and a client over the memory provider
// on transportName. allowPrivate lifts the server's egress policy, so a
// CONNECT or a UDP flow reaches the loopback echo servers. ai-generated: the
// switch covers TCP too (egress hardening).
func startMemoryTunnel(t *testing.T, transportName string, allowPrivate bool) *tunnelRuntime {
	t.Helper()

	providerName, room := registerMemoryProvider(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	socksAddr := freeLocalAddr(ctx, t)

	serverErr := make(chan error, 1)
	go func() {
		serverErr <- server.Run(ctx, server.Config{
			Transport:                 transportName,
			Provider:                  providerName,
			RoomURL:                   testRoom,
			KeyHex:                    testKeyHex,
			DNSServer:                 localDNSServer,
			TransportOptions:          e2eTransportOptions(transportName),
			UnsafeAllowPrivateTargets: allowPrivate,
		})
	}()
	room.waitConnected(t, 1)

	ready := make(chan struct{})
	clientErr := make(chan error, 1)
	go func() {
		clientErr <- client.RunWithReady(ctx, client.Config{
			Transport:        transportName,
			Provider:         providerName,
			RoomURL:          testRoom,
			KeyHex:           testKeyHex,
			DeviceID:         testClientDeviceID,
			LocalAddr:        socksAddr,
			DNSServer:        localDNSServer,
			TransportOptions: e2eTransportOptions(transportName),
		}, func() { close(ready) })
	}()
	waitForReadyWithin(t, ready, 30*time.Second)

	return &tunnelRuntime{
		socksAddr: socksAddr,
		room:      room,
		cancel:    cancel,
		serverErr: serverErr,
		clientErr: clientErr,
		stopWait:  3 * time.Second,
	}
}

// connectViaSOCKSUDP opens a SOCKS5 UDP ASSOCIATE and returns the client's
// UDP socket, the TCP control connection and the relay address to send to.
func connectViaSOCKSUDP(t *testing.T, socksAddr string) (*net.UDPConn, net.Conn, *net.UDPAddr) {
	t.Helper()
	dialer := net.Dialer{Timeout: 5 * time.Second}
	tcpConn, err := dialer.DialContext(context.Background(), "tcp4", socksAddr)
	if err != nil {
		t.Fatalf("dial socks udp tcp control: %v", err)
	}
	socksUDPHandshake(t, tcpConn)
	relay := socksUDPAssociate(t, tcpConn)
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_ = tcpConn.Close()
		t.Fatalf("listen udp client socket: %v", err)
	}
	return udpConn, tcpConn, relay
}

func socksUDPHandshake(t *testing.T, tcpConn net.Conn) {
	t.Helper()
	if _, err := tcpConn.Write([]byte{5, 1, 0}); err != nil {
		_ = tcpConn.Close()
		t.Fatalf("write socks udp greeting: %v", err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(tcpConn, greeting); err != nil {
		_ = tcpConn.Close()
		t.Fatalf("read socks udp greeting: %v", err)
	}
	if !bytes.Equal(greeting, []byte{5, 0}) {
		_ = tcpConn.Close()
		t.Fatalf("socks udp greeting = %v, want [5 0]", greeting)
	}
}

func socksUDPAssociate(t *testing.T, tcpConn net.Conn) *net.UDPAddr {
	t.Helper()
	if _, err := tcpConn.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		_ = tcpConn.Close()
		t.Fatalf("write socks udp associate: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(tcpConn, reply); err != nil {
		_ = tcpConn.Close()
		t.Fatalf("read socks udp associate reply: %v", err)
	}
	if reply[0] != 5 || reply[1] != 0 || reply[3] != 1 {
		_ = tcpConn.Close()
		t.Fatalf("socks udp associate reply = %v, want IPv4 success", reply)
	}
	port := binary.BigEndian.Uint16(reply[8:10])
	return &net.UDPAddr{IP: net.IPv4(reply[4], reply[5], reply[6], reply[7]), Port: int(port)}
}

func buildSocksUDPPacket(t *testing.T, targetAddr string, payload []byte) []byte {
	t.Helper()
	host, portText, err := net.SplitHostPort(targetAddr)
	if err != nil {
		t.Fatalf("split udp target addr: %v", err)
	}
	ip := net.ParseIP(host).To4()
	if ip == nil {
		t.Fatalf("udp target host is not IPv4: %s", host)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatalf("parse udp target port: %v", err)
	}
	packet := make([]byte, 0, 10+len(payload))
	packet = append(packet, 0, 0, 0, 1)
	packet = append(packet, ip...)
	var portBuf [2]byte
	binary.BigEndian.PutUint16(portBuf[:], uint16(port)) //nolint:gosec // G115: a SOCKS5 port is uint16 by definition
	packet = append(packet, portBuf[:]...)
	packet = append(packet, payload...)
	return packet
}

func parseSocksUDPPacket(t *testing.T, packet []byte) (string, []byte) {
	t.Helper()
	if len(packet) < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0 || packet[3] != 1 {
		t.Fatalf("bad socks udp packet: %v", packet)
	}
	ip := net.IP(packet[4:8]).String()
	port := binary.BigEndian.Uint16(packet[8:10])
	return net.JoinHostPort(ip, strconv.Itoa(int(port))), packet[10:]
}

func TestClientServerSOCKSUDPOverMemoryVP8Channel(t *testing.T) {
	echoAddr := startUDPEchoServer(t)
	rt := startMemoryTunnel(t, transportVP8, true)
	defer rt.stop(t)

	udpConn, tcpConn, relayAddr := connectViaSOCKSUDP(t, rt.socksAddr)
	defer func() { _ = udpConn.Close() }()
	defer func() { _ = tcpConn.Close() }()

	payload := []byte("olcrtc-udp-e2e")
	if _, err := udpConn.WriteToUDP(buildSocksUDPPacket(t, echoAddr, payload), relayAddr); err != nil {
		t.Fatalf("write socks udp packet: %v", err)
	}
	if err := udpConn.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatalf("set udp read deadline: %v", err)
	}
	buf := make([]byte, 4096)
	n, _, err := udpConn.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("read socks udp echo: %v", err)
	}
	from, got := parseSocksUDPPacket(t, buf[:n])
	if !bytes.Equal(got, payload) || from != echoAddr {
		t.Fatalf("udp echo = %q from %s, want %q from %s", got, from, payload, echoAddr)
	}
}

func TestClientServerSOCKSUDPBlocksPrivateTargetByDefault(t *testing.T) {
	echoAddr := startUDPEchoServer(t)
	rt := startMemoryTunnel(t, transportVP8, false)
	defer rt.stop(t)

	udpConn, tcpConn, relayAddr := connectViaSOCKSUDP(t, rt.socksAddr)
	defer func() { _ = udpConn.Close() }()
	defer func() { _ = tcpConn.Close() }()

	if _, err := udpConn.WriteToUDP(buildSocksUDPPacket(t, echoAddr, []byte("blocked")), relayAddr); err != nil {
		t.Fatalf("write socks udp packet: %v", err)
	}
	if err := udpConn.SetReadDeadline(time.Now().Add(500 * time.Millisecond)); err != nil {
		t.Fatalf("set udp read deadline: %v", err)
	}
	buf := make([]byte, 4096)
	if n, _, err := udpConn.ReadFromUDP(buf); err == nil {
		t.Fatalf("unexpected udp response from a loopback target: %x", buf[:n])
	}
}

func TestSOCKSUDPAssociateIsRefusedWhenTheRelayIsOff(t *testing.T) {
	providerName, room := registerMemoryProvider(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	socksAddr := freeLocalAddr(ctx, t)
	go func() {
		_ = server.Run(ctx, server.Config{
			Transport: transportData, Provider: providerName, RoomURL: testRoom,
			KeyHex: testKeyHex, DNSServer: localDNSServer,
		})
	}()
	room.waitConnected(t, 1)
	ready := make(chan struct{})
	go func() {
		_ = client.RunWithReady(ctx, client.Config{
			Transport: transportData, Provider: providerName, RoomURL: testRoom,
			KeyHex: testKeyHex, DeviceID: testClientDeviceID, LocalAddr: socksAddr,
			DNSServer: localDNSServer, UDPDisabled: true,
		}, func() { close(ready) })
	}()
	waitForReadyWithin(t, ready, 20*time.Second)

	dialer := net.Dialer{Timeout: 5 * time.Second}
	tcpConn, err := dialer.DialContext(ctx, "tcp4", socksAddr)
	if err != nil {
		t.Fatalf("dial socks: %v", err)
	}
	defer func() { _ = tcpConn.Close() }()
	socksUDPHandshake(t, tcpConn)
	if _, err := tcpConn.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		t.Fatalf("write socks udp associate: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(tcpConn, reply); err != nil {
		t.Fatalf("read socks udp associate reply: %v", err)
	}
	if reply[1] != 4 {
		t.Fatalf("reply code = %d, want 4 (host unreachable) while the relay is off", reply[1])
	}
}
