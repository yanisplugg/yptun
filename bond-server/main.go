// bond-server is the SERVER side of the olcRTC Stage-2 stream bond. It runs ON the olcRTC host next to
// the panel. The client (olcvpn-client, Chain + multi-room + bond) opens one SOCKS lane THROUGH each
// olcRTC room to this server, announces a shared connID + lane count via a bond Hello, then stripes the
// single Chain→VLESS flow across the lanes. This server groups lanes by connID, reassembles them in
// order, terminates the inner SOCKS5 session and dials the real target — so one flow aggregates the
// bandwidth of all rooms ("many→single→vless").
//
// Topology: each room's SERVER-side SOCKS dials [-listen] (loopback on this host), so the lanes all land
// here. We never expose a public port. Access is already gated by the room SOCKS auth on every lane.
package main

import (
	"context"
	"flag"
	"log"
	"net"
	"sync"
	"time"
)

const maxLaneCount = 16 // sanity bound on a single bonded connection

type session struct {
	want  int
	lanes []net.Conn
	timer *time.Timer
}

type registry struct {
	mu       sync.Mutex
	sessions map[uint64]*session
}

func main() {
	listen := flag.String("listen", "127.0.0.1:7700", "local address lanes land on (room SOCKS dials this)")
	user := flag.String("user", "", "optional SOCKS5 username to enforce on the reassembled stream (empty = accept any)")
	pass := flag.String("pass", "", "optional SOCKS5 password to enforce (used with -user)")
	laneTimeout := flag.Duration("lane-timeout", 15*time.Second, "max wait to collect all lanes of one connID")
	dialTimeout := flag.Duration("dial-timeout", 10*time.Second, "target dial timeout for the inner SOCKS")
	flag.Parse()

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("bond-server: listen %s: %v", *listen, err)
	}
	log.Printf("bond-server: listening on %s (lane-timeout=%s, auth=%v)", *listen, *laneTimeout, *user != "")

	reg := &registry{sessions: make(map[uint64]*session)}
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("bond-server: accept: %v", err)
			continue
		}
		go reg.handleLane(c, *user, *pass, *laneTimeout, *dialTimeout)
	}
}

// handleLane reads the Hello off a freshly accepted lane and registers it under its connID. Once all
// LaneCount lanes of a connID have arrived, the session is reassembled and served.
func (r *registry) handleLane(c net.Conn, user, pass string, laneTimeout, dialTimeout time.Duration) {
	_ = c.SetReadDeadline(time.Now().Add(laneTimeout))
	h, err := ReadHello(c)
	if err != nil {
		log.Printf("bond-server: bad hello: %v", err)
		_ = c.Close()
		return
	}
	_ = c.SetReadDeadline(time.Time{}) // clear; bond IO manages its own deadlines

	want := int(h.LaneCount)
	if want <= 0 || want > maxLaneCount {
		log.Printf("bond-server: rejecting connID %d: bad lane count %d", h.ConnID, want)
		_ = c.Close()
		return
	}

	r.mu.Lock()
	s := r.sessions[h.ConnID]
	if s == nil {
		s = &session{want: want}
		s.timer = time.AfterFunc(laneTimeout, func() { r.expire(h.ConnID) })
		r.sessions[h.ConnID] = s
	}
	s.lanes = append(s.lanes, c)
	complete := len(s.lanes) >= s.want
	if complete {
		delete(r.sessions, h.ConnID)
		if s.timer != nil {
			s.timer.Stop()
		}
	}
	lanes := s.lanes
	r.mu.Unlock()

	if complete {
		serveSession(h.ConnID, lanes, user, pass, dialTimeout)
	}
}

// expire drops a session whose lanes never all arrived, closing whatever did.
func (r *registry) expire(connID uint64) {
	r.mu.Lock()
	s := r.sessions[connID]
	delete(r.sessions, connID)
	r.mu.Unlock()
	if s == nil {
		return
	}
	log.Printf("bond-server: connID %d expired with %d/%d lanes", connID, len(s.lanes), s.want)
	for _, l := range s.lanes {
		_ = l.Close()
	}
}

// serveSession reassembles the lanes into one stream and runs the inner SOCKS5 server over it.
func serveSession(connID uint64, lanes []net.Conn, user, pass string, dialTimeout time.Duration) {
	log.Printf("bond-server: connID %d up with %d lane(s)", connID, len(lanes))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// pa = the "single" side handed to Reassemble; pb = the SOCKS5 server side.
	pa, pb := net.Pipe()

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		Reassemble(ctx, pa, lanes)
		_ = pa.Close()
	}()

	serveSocks(pb, user, pass, dialTimeout)
	cancel()
	_ = pa.Close()
	for _, l := range lanes {
		_ = l.Close()
	}
	wg.Wait()
	log.Printf("bond-server: connID %d closed", connID)
}
