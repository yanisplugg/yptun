package socks5

import (
	"fmt"
	"io"
	"net"
	"sync"

	"universal-bypass-tool/utils"
)

type Dialer interface {
	DialTCP(address string) (net.Conn, error)
}

type SOCKS5Server struct {
	listenAddr string
	dialer     Dialer
	// YPtun: optional RFC 1929 username/password. The local port is reachable by every app on the
	// device, so the host protects it with per-session credentials. Empty = no auth (upstream).
	user, pass string
}

func NewSOCKS5Server(addr string, dialer Dialer) *SOCKS5Server {
	return &SOCKS5Server{listenAddr: addr, dialer: dialer}
}

// SetAuth requires RFC 1929 username/password authentication (YPtun).
func (s *SOCKS5Server) SetAuth(user, pass string) {
	s.user, s.pass = user, pass
}

// authenticate answers the method selection (and runs RFC 1929 when credentials are set).
func (s *SOCKS5Server) authenticate(conn net.Conn, methods []byte) bool {
	if s.user == "" && s.pass == "" {
		conn.Write([]byte{0x05, 0x00})
		return true
	}
	offered := false
	for _, m := range methods {
		if m == 0x02 {
			offered = true
		}
	}
	if !offered {
		conn.Write([]byte{0x05, 0xFF})
		return false
	}
	conn.Write([]byte{0x05, 0x02})
	// VER(1) ULEN(1) UNAME PLEN(1) PASSWD
	head := make([]byte, 2)
	if _, err := io.ReadFull(conn, head); err != nil || head[0] != 0x01 {
		return false
	}
	user := make([]byte, head[1])
	if _, err := io.ReadFull(conn, user); err != nil {
		return false
	}
	plen := make([]byte, 1)
	if _, err := io.ReadFull(conn, plen); err != nil {
		return false
	}
	pass := make([]byte, plen[0])
	if _, err := io.ReadFull(conn, pass); err != nil {
		return false
	}
	if string(user) != s.user || string(pass) != s.pass {
		conn.Write([]byte{0x01, 0x01})
		return false
	}
	conn.Write([]byte{0x01, 0x00})
	return true
}

func (s *SOCKS5Server) Start() error {
	listener, err := net.Listen("tcp", s.listenAddr)
	if err != nil {
		return err
	}
	defer listener.Close()

	utils.Debugf("[SOCKS5] Listening on %s", s.listenAddr)

	for {
		conn, err := listener.Accept()
		if err != nil {
			utils.Debugf("[SOCKS5] Accept error: %v", err)
			continue
		}
		go s.handleConnection(conn)
	}
}

func (s *SOCKS5Server) handleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	buf := make([]byte, 256)
	n, err := clientConn.Read(buf)
	if err != nil || n < 2 || buf[0] != 0x05 {
		return
	}

	nMethods := int(buf[1])
	if 2+nMethods > n {
		nMethods = n - 2
	}
	if !s.authenticate(clientConn, buf[2:2+nMethods]) {
		return
	}

	n, err = clientConn.Read(buf)
	if err != nil || n < 10 || buf[1] != 0x01 {
		return
	}

	var targetAddr string
	switch buf[3] {
	case 0x01:
		targetAddr = fmt.Sprintf("%d.%d.%d.%d:%d",
			buf[4], buf[5], buf[6], buf[7],
			uint16(buf[8])<<8|uint16(buf[9]))
	case 0x03:
		domainLen := int(buf[4])
		targetAddr = fmt.Sprintf("%s:%d",
			string(buf[5:5+domainLen]),
			uint16(buf[5+domainLen])<<8|uint16(buf[6+domainLen]))
	default:
		return
	}

	utils.Debugf("[SOCKS5] CONNECT %s", targetAddr)

	targetConn, err := s.dialer.DialTCP(targetAddr)
	if err != nil {
		utils.Debugf("[SOCKS5] Dial failed: %v", err)
		clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})
		return
	}
	defer targetConn.Close()

	clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00})

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		defer targetConn.Close()
		io.Copy(targetConn, clientConn)
	}()

	go func() {
		defer wg.Done()
		defer clientConn.Close()
		io.Copy(clientConn, targetConn)
	}()

	wg.Wait()
}
