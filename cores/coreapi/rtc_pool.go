package coreapi

// olcRTC multi-room for iOS: N independent rooms (olcrtc/mobile StartRoom) fronted by a round-robin TCP
// balancer on one loopback SOCKS address — the in-process twin of the desktop's DesktopRoomPool and
// Android's OlcrtcRoomManager. Each NEW connection goes to the next healthy room, so parallel flows
// spread over the rooms and their bandwidth adds up; one flow is never split. The balancer only pumps
// bytes, so the SOCKS5 handshake (auth included) reaches the room as is: rooms and balancer share one
// credential pair. RtcStartPool returns once the FIRST room is up; the others keep connecting (and
// retrying) in the background and join as they come up.

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

type rtcPoolRoom struct {
	Carrier   string `json:"carrier"`
	Transport string `json:"transport"`
	Room      string `json:"room"`
	Key       string `json:"key"`
}

type rtcPool struct {
	ln      net.Listener
	stop    chan struct{}
	mu      sync.Mutex
	handles map[int]int // spec index -> room handle
	rr      atomic.Uint32
	conns   sync.Map // net.Conn -> struct{}
}

var (
	rtcPoolMu  sync.Mutex
	rtcPoolCur *rtcPool
)

const rtcPoolRetry = 15 * time.Second

// RtcStartPool raises the rooms of roomsJson (`[{"carrier","transport","room","key"}, …]`, the first is the
// main room) and serves the balancer on listenAddr. Blocks until one room is up (readyTimeoutMs each).
func RtcStartPool(roomsJson, clientID, listenAddr, socksUser, socksPass string, readyTimeoutMs int) error {
	var specs []rtcPoolRoom
	if err := json.Unmarshal([]byte(roomsJson), &specs); err != nil {
		return err
	}
	if len(specs) == 0 {
		return errors.New("olcRTC multi-room: no rooms")
	}
	RtcStopPool()
	mobile.SetLogWriter(tagWriter{"olcrtc"})

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}
	p := &rtcPool{ln: ln, stop: make(chan struct{}), handles: map[int]int{}}

	start := func(i int) bool {
		s := specs[i]
		h, err := mobile.StartRoom(s.Carrier, s.Transport, s.Room, clientID, s.Key, 0, socksUser, socksPass, readyTimeoutMs)
		if err != nil {
			PushLog("olcrtc", "room "+strconv.Itoa(i+1)+" failed: "+err.Error())
			return false
		}
		p.mu.Lock()
		select {
		case <-p.stop: // stopped while connecting
			p.mu.Unlock()
			mobile.StopRoom(h)
			return false
		default:
		}
		p.handles[i] = h
		p.mu.Unlock()
		PushLog("olcrtc", "room "+strconv.Itoa(i+1)+"/"+strconv.Itoa(len(specs))+" up on port "+strconv.Itoa(mobile.RoomPort(h)))
		return true
	}

	// FAST CONNECT: the first room that comes up unblocks the caller.
	first := -1
	for i := range specs {
		if start(i) {
			first = i
			break
		}
	}
	if first < 0 {
		_ = ln.Close()
		return errors.New("olcRTC multi-room: no room came up")
	}

	rtcPoolMu.Lock()
	rtcPoolCur = p
	rtcPoolMu.Unlock()

	go p.serve()
	for i := range specs {
		if i == first {
			continue
		}
		go func(i int) {
			for !start(i) {
				select {
				case <-p.stop:
					return
				case <-time.After(rtcPoolRetry):
				}
			}
		}(i)
	}
	PushLog("olcrtc", "multi-room balancer on "+listenAddr+" ("+strconv.Itoa(len(specs))+" rooms)")
	return nil
}

func (p *rtcPool) ports() []int {
	p.mu.Lock()
	defer p.mu.Unlock()
	var healthy, all []int
	for _, h := range p.handles {
		port := mobile.RoomPort(h)
		if port <= 0 {
			continue
		}
		all = append(all, port)
		if mobile.RoomHealthy(h) {
			healthy = append(healthy, port)
		}
	}
	if len(healthy) > 0 {
		return healthy
	}
	return all
}

func (p *rtcPool) serve() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		go p.handle(c)
	}
}

func (p *rtcPool) handle(c net.Conn) {
	ports := p.ports()
	if len(ports) == 0 {
		_ = c.Close()
		return
	}
	port := ports[int(p.rr.Add(1))%len(ports)]
	b, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 5*time.Second)
	if err != nil {
		_ = c.Close()
		return
	}
	p.conns.Store(c, struct{}{})
	p.conns.Store(b, struct{}{})
	done := make(chan struct{}, 2)
	pump := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if tc, ok := dst.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
		done <- struct{}{}
	}
	go pump(b, c)
	go pump(c, b)
	<-done
	<-done
	_ = c.Close()
	_ = b.Close()
	p.conns.Delete(c)
	p.conns.Delete(b)
}

// RtcStopPool stops the balancer and every room it raised.
func RtcStopPool() {
	rtcPoolMu.Lock()
	p := rtcPoolCur
	rtcPoolCur = nil
	rtcPoolMu.Unlock()
	if p == nil {
		return
	}
	p.mu.Lock()
	close(p.stop)
	handles := p.handles
	p.handles = map[int]int{}
	p.mu.Unlock()
	_ = p.ln.Close()
	p.conns.Range(func(k, _ any) bool {
		_ = k.(net.Conn).Close()
		return true
	})
	for _, h := range handles {
		mobile.StopRoom(h)
	}
	PushLog("olcrtc", "multi-room stopped")
}

// RtcPoolRunning reports whether the balancer is up with at least one room.
func RtcPoolRunning() bool {
	rtcPoolMu.Lock()
	p := rtcPoolCur
	rtcPoolMu.Unlock()
	return p != nil && len(p.ports()) > 0
}

// RtcPoolRooms is the number of rooms currently up in the pool.
func RtcPoolRooms() int {
	rtcPoolMu.Lock()
	p := rtcPoolCur
	rtcPoolMu.Unlock()
	if p == nil {
		return 0
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.handles)
}
