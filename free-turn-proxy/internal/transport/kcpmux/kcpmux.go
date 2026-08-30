// Package kcpmux собирает надёжный мультиплексированный транспорт поверх датаграммного
// канала: KCP (ARQ) -> smux (потоки). Нужен, потому что relayed-данные TURN всегда
// датаграммы, даже при -transport tcp, и TCP-поток по ним без ARQ не построить.
package kcpmux

import (
	"fmt"
	"net"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// acceptTimeout ограничивает ожидание первой KCP-датаграммы от клиента на сервере.
const acceptTimeout = 30 * time.Second

// Profile - параметры конгестии KCP; должен совпадать по смыслу с флагами -kcp-*.
type Profile struct {
	NoDelay    int
	Interval   int
	Resend     int
	NC         int
	SndWnd     int
	RcvWnd     int
	MTU        int
	ACKNoDelay bool
}

func DefaultProfile() Profile {
	return Profile{
		NoDelay:    1,
		Interval:   20,
		Resend:     2,
		NC:         1,
		SndWnd:     512,
		RcvWnd:     512,
		MTU:        1200,
		ACKNoDelay: true,
	}
}

// PacketConn адаптирует потоко-ориентированный net.Conn (DTLS) к net.PacketConn для KCP:
// DTLS сохраняет границы записей, поэтому Read/Write отображаются в датаграммы один в один.
type PacketConn struct {
	conn net.Conn
}

func NewPacketConn(conn net.Conn) *PacketConn { return &PacketConn{conn: conn} }

func (d *PacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, err := d.conn.Read(b)
	return n, d.conn.RemoteAddr(), err
}

func (d *PacketConn) WriteTo(b []byte, _ net.Addr) (int, error) {
	return d.conn.Write(b)
}

func (d *PacketConn) Close() error                       { return d.conn.Close() }
func (d *PacketConn) LocalAddr() net.Addr                { return d.conn.LocalAddr() }
func (d *PacketConn) SetDeadline(t time.Time) error      { return d.conn.SetDeadline(t) }
func (d *PacketConn) SetReadDeadline(t time.Time) error  { return d.conn.SetReadDeadline(t) }
func (d *PacketConn) SetWriteDeadline(t time.Time) error { return d.conn.SetWriteDeadline(t) }

// Dial поднимает клиентскую KCP-сессию поверх датаграммного conn.
func Dial(conn net.Conn, profile Profile) (*kcp.UDPSession, error) {
	block, err := noneCrypt()
	if err != nil {
		return nil, err
	}
	sess, err := kcp.NewConn2(conn.RemoteAddr(), block, 0, 0, NewPacketConn(conn))
	if err != nil {
		return nil, fmt.Errorf("kcpmux dial: %w", err)
	}
	apply(sess, profile)
	return sess, nil
}

// ServerSession - принятая сессия вместе с породившим её листенером: листенер держит
// демультиплексор поверх conn, и без его закрытия он пережил бы сессию.
type ServerSession struct {
	*kcp.UDPSession
	listener *kcp.Listener
}

func (s *ServerSession) Close() error {
	err := s.UDPSession.Close()
	if lerr := s.listener.Close(); err == nil {
		err = lerr
	}
	return err
}

// Accept ждёт первую KCP-датаграмму на conn и возвращает серверную сессию.
func Accept(conn net.Conn, profile Profile) (*ServerSession, error) {
	block, err := noneCrypt()
	if err != nil {
		return nil, err
	}
	listener, err := kcp.ServeConn(block, 0, 0, NewPacketConn(conn))
	if err != nil {
		return nil, fmt.Errorf("kcpmux serve: %w", err)
	}
	if err = listener.SetDeadline(time.Now().Add(acceptTimeout)); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("kcpmux accept deadline: %w", err)
	}
	sess, err := listener.AcceptKCP()
	if err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("kcpmux accept: %w", err)
	}
	apply(sess, profile)
	return &ServerSession{UDPSession: sess, listener: listener}, nil
}

// SmuxConfig - параметры smux, общие для клиента и сервера (буферы должны совпадать).
func SmuxConfig() *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.MaxReceiveBuffer = 4 * 1024 * 1024
	cfg.MaxStreamBuffer = 1 * 1024 * 1024
	cfg.KeepAliveInterval = 10 * time.Second
	cfg.KeepAliveTimeout = 30 * time.Second
	return cfg
}

// noneCrypt - шифрование не нужно, канал уже под DTLS.
func noneCrypt() (kcp.BlockCrypt, error) {
	block, err := kcp.NewNoneBlockCrypt(nil)
	if err != nil {
		return nil, fmt.Errorf("kcpmux crypt: %w", err)
	}
	return block, nil
}

func apply(sess *kcp.UDPSession, p Profile) {
	sess.SetNoDelay(p.NoDelay, p.Interval, p.Resend, p.NC)
	sess.SetWindowSize(p.SndWnd, p.RcvWnd)
	sess.SetMtu(p.MTU)
	sess.SetACKNoDelay(p.ACKNoDelay)
}
