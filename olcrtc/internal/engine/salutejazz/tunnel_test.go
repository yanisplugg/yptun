package salutejazz_test

import (
	"bytes"
	"cmp"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
	"github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/engine/salutejazz"
	"github.com/openlibrecommunity/olcrtc/internal/server"
)

// ai-generated: the whole file (the tunnel over the slow leg, olcrtc#49).

// The whole tunnel over the fake SFU's slow legs: the client and the server,
// the datachannel transport, smux and the control stream as they run in the
// field, with only the SFU faked. The window tests in this package drive the
// engine directly; this one shows what the window is for.

const (
	tunnelKeyHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
	// tunnelLeg is what each leg of the SFU carries, in bytes a second.
	tunnelLeg = 128_000
	// tunnelProbeEvery and tunnelPongTimeout are both ends' liveness. The
	// timeout is shorter than the tunnel's 15 s, as the legs are faster than
	// the 34 kB/s one the 15 s is weighed against (window_budget_test.go):
	// still well over what the window lets a pong wait here, and short
	// enough that the queue an engine without it builds on these legs has
	// liveness close the session inside the test.
	tunnelProbeEvery  = 500 * time.Millisecond
	tunnelPongTimeout = 8 * time.Second
	// tunnelWatch is how long the test watches the control stream with both
	// transfers going.
	tunnelWatch = 12 * time.Second
	// tunnelChunk is what a transfer writes at a time.
	tunnelChunk = 32 << 10
)

// TestATunnelOnASlowLegKeepsItsControlStreamUnderBulk is olcrtc#49 end to
// end. A download and an upload run through the tunnel at once, on an SFU
// whose legs carry 128 kB/s each way. Without the window the SFU takes
// whatever smux's windows let a sender write - megabytes, tens of seconds of
// it on these legs - and every control ping waits at the back of it, until
// liveness closes a session whose bytes are still arriving. With it a ping
// waits behind at most a window on its way and its pong behind at most a
// window on the way back, so:
//
//   - no pong is missed, the server closes no session and the client never
//     reconnects;
//   - every pong comes back within two windows' worth of leg and a second;
//   - pongs keep coming at both ends: no gap between two is longer than that
//     and a probe interval;
//   - both transfers move, and what arrives is what was sent;
//   - each leg did hold the bulk, at least half a window of it at its
//     peak, and never more than a window.
func TestATunnelOnASlowLegKeepsItsControlStreamUnderBulk(t *testing.T) {
	room := salutejazz.NewFakeRoom(t)
	tunnel := startFakeTunnel(t, room)
	room.SlowLeg(t, tunnel.serverID, tunnelLeg)
	room.SlowLeg(t, tunnel.clientID, tunnelLeg)

	down := startDownload(t, tunnel.socksAddr, startSource(t))
	up := startUpload(t, tunnel.socksAddr, startSink(t))
	eventually(t, 10*time.Second, "both transfers under way", func() bool {
		return down.moved() >= tunnelChunk && up.moved() >= tunnelChunk
	})
	watchFrom, downFrom, upFrom := time.Now(), down.moved(), up.moved()
	time.Sleep(tunnelWatch)
	watchTo, downTo, upTo := time.Now(), down.moved(), up.moved()

	tunnel.checkStillUp(t)
	pongBound := time.Duration(2*salutejazz.RelayWindowBound)*time.Second/tunnelLeg + time.Second
	tunnel.serverLive.check(t, "server", watchFrom, watchTo, pongBound)
	tunnel.clientLive.check(t, "client", watchFrom, watchTo, pongBound)

	watched := watchTo.Sub(watchFrom).Seconds()
	downRate, upRate := float64(downTo-downFrom)/watched, float64(upTo-upFrom)/watched
	t.Logf("in %s: download %.0f kB/s, upload %.0f kB/s on %d kB/s legs; the legs peaked at %d KiB to the client "+
		"and %d KiB to the server", tunnelWatch, downRate/1000, upRate/1000, tunnelLeg/1000,
		room.PeakQueuedTo(tunnel.clientID)>>10, room.PeakQueuedTo(tunnel.serverID)>>10)
	for _, moved := range []struct {
		what string
		rate float64
		bad  error
	}{{"download", downRate, down.fault()}, {"upload", upRate, up.fault()}} {
		if moved.bad != nil {
			t.Fatalf("the %s: %v", moved.what, moved.bad)
		}
		if moved.rate < tunnelLeg/4 {
			t.Fatalf("the %s moved %.0f kB/s, under a quarter of its %d kB/s leg",
				moved.what, moved.rate/1000, tunnelLeg/1000)
		}
	}
	for who, id := range map[string]string{"client": tunnel.clientID, "server": tunnel.serverID} {
		peak := room.PeakQueuedTo(id)
		if peak > salutejazz.RelayWindowBound {
			t.Fatalf("the leg to the %s held %d KiB, over the %d KiB a window allows",
				who, peak>>10, salutejazz.RelayWindowBound>>10)
		}
		// Every check above is a floor on the rates or a ceiling on a
		// queue, and a tunnel with no slow leg under it passes them all.
		// This one says the bulk did queue on the leg.
		if peak < salutejazz.RelayWindow/2 {
			t.Fatalf("the leg to the %s peaked at %d KiB, under half a %d KiB window: the bulk never queued "+
				"on it, so the tunnel did not run on a slow leg", who, peak>>10, salutejazz.RelayWindow>>10)
		}
	}
	if failure := room.LastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// The leg the engine gate measured through Sber's relay on 7b78fd4a (run
// 35862192631), where all six of S2's pulls ended on a liveness close.
const (
	// tunnelSlowestLeg is that leg, SFU to receiver, in bytes a second.
	tunnelSlowestLeg = 26_000
	// tunnelSlowProbeEvery and tunnelSlowPongTimeout are the tunnel's own
	// liveness: on this leg the test is the gate's cell, liveness and all.
	// A faster probe does not fit it: smux writes one record per stream in
	// turn and the control stream is one of the seven, so on this leg it
	// gets a record out about every 2.8 s, and both ends' pings and pongs
	// at a probe a second queue up without end.
	tunnelSlowProbeEvery  = control.DefaultInterval
	tunnelSlowPongTimeout = control.DefaultTimeout
	// tunnelSlowWatch is long enough for four probes at each end.
	tunnelSlowWatch = 4*tunnelSlowProbeEvery + 5*time.Second
	// tunnelSettle is how long the window gets to size itself, and the relay
	// to deliver what went before, before the watch starts.
	tunnelSettle = 15 * time.Second
	// tunnelPulls is how many downloads run at once, as in the gate's S2.
	tunnelPulls = 6
)

// TestSixPullsOnTheSlowestLegKeepTheirPongs is the second half
// of olcrtc#49 end to end: the gate's S2, six pulls at once, on the 26 kB/s leg
// the gate measured, with the tunnel's own liveness. A pong waits behind two
// queues there. One is the relay's: a fixed window is 7.6 s of it on this leg.
// The other is smux's: it writes one record per stream in turn, and the pong's
// stream is one of seven, so it waits for a record from every pull, 2.8 s on
// this leg, whatever the window does. The fixed window's pongs take over 10 s
// here, and a leg a little slower or a few more pulls close the session on
// liveness. The window sizes itself for the leg, so:
//
//   - no pong is missed and the session stays up at both ends;
//   - once the window has settled every pong comes back within the window's
//     queue on the way back, two turns of smux and a second;
//   - the leg toward the client holds well under a whole window, which is
//     what tells this window from the fixed one;
//   - the pulls move, nearly as fast as the leg, and what arrives is what was
//     sent.
func TestSixPullsOnTheSlowestLegKeepTheirPongs(t *testing.T) {
	room := salutejazz.NewFakeRoom(t)
	tunnel := startFakeTunnelLive(t, room, tunnelSlowProbeEvery, tunnelSlowPongTimeout)
	room.SlowLeg(t, tunnel.serverID, tunnelSlowestLeg)
	room.SlowLeg(t, tunnel.clientID, tunnelSlowestLeg)

	source := startSource(t)
	pulls := make([]*transfer, tunnelPulls)
	for i := range pulls {
		pulls[i] = startDownload(t, tunnel.socksAddr, source)
	}
	moved := func() int64 {
		var total int64
		for _, pull := range pulls {
			total += pull.moved()
		}
		return total
	}
	eventually(t, 20*time.Second, "the pulls under way", func() bool { return moved() >= tunnelChunk })
	time.Sleep(tunnelSettle)

	watchFrom, movedFrom := time.Now(), moved()
	peak := 0
	for end := watchFrom.Add(tunnelSlowWatch); time.Now().Before(end); time.Sleep(100 * time.Millisecond) {
		peak = max(peak, room.QueuedTo(tunnel.clientID))
	}
	watchTo, movedTo := time.Now(), moved()

	tunnel.checkStillUp(t)
	// The pulls load only the way back, so a pong waits behind one window's
	// queue, relayHorizon of the leg. smux writes one record per stream in
	// turn, so it also waits for a record from every pull, and may first wait
	// for its own stream's ping to go the same way: two turns and its own
	// record.
	record := time.Duration(salutejazz.SlowLegRecord) * time.Second / tunnelSlowestLeg
	pongBound := salutejazz.RelayHorizon + (2*tunnelPulls+1)*record + time.Second
	tunnel.serverLive.check(t, "server", watchFrom, watchTo, pongBound)
	tunnel.clientLive.check(t, "client", watchFrom, watchTo, pongBound)

	rate := float64(movedTo-movedFrom) / watchTo.Sub(watchFrom).Seconds()
	t.Logf("in %s: %d pulls moved %.1f kB/s on a %d kB/s leg; the leg to the client peaked at %d KiB",
		tunnelSlowWatch, tunnelPulls, rate/1000, tunnelSlowestLeg/1000, peak>>10)
	for i, pull := range pulls {
		if bad := pull.fault(); bad != nil {
			t.Fatalf("pull %d: %v", i, bad)
		}
	}
	// A window sized down must not cost the leg: the pulls keep it nearly
	// full.
	if rate < tunnelSlowestLeg*3/4 {
		t.Fatalf("the pulls moved %.1f kB/s, under three quarters of their %d kB/s leg",
			rate/1000, tunnelSlowestLeg/1000)
	}
	if peak > salutejazz.RelayWindow*3/4 {
		t.Fatalf("the leg to the client held %d KiB under settled pulls, near the whole %d KiB window: "+
			"the window did not size itself for the leg", peak>>10, salutejazz.RelayWindow>>10)
	}
	if failure := room.LastError(); failure != "" {
		t.Fatalf("fake SFU error: %s", failure)
	}
}

// fakeTunnel is a server and a client in one fake room, paired: the client's
// SOCKS address, both ends' identities in the room, and what their liveness
// and sessions have reported.
type fakeTunnel struct {
	socksAddr          string
	serverID, clientID string
	engines            *madeEngines
	serverLive         liveness
	clientLive         liveness

	closedMu     sync.Mutex
	serverClosed []string
	clientOpened atomic.Int64
	clientDown   atomic.Uint64
}

// startFakeTunnel runs a server and then a client over room, both with the
// test's liveness, until the client has a session. Both stop when the test
// ends.
func startFakeTunnel(t *testing.T, room *salutejazz.FakeRoom) *fakeTunnel {
	t.Helper()
	return startFakeTunnelLive(t, room, tunnelProbeEvery, tunnelPongTimeout)
}

// startFakeTunnelLive is startFakeTunnel with both ends pinging every
// probeEvery and waiting pongTimeout for a pong.
func startFakeTunnelLive(t *testing.T, room *salutejazz.FakeRoom, probeEvery, pongTimeout time.Duration) *fakeTunnel {
	t.Helper()
	session.RegisterDefaults()
	provider, engines := registerFakeRoom(t, room)
	x := &fakeTunnel{engines: engines}
	x.serverLive.interval, x.clientLive.interval = probeEvery, probeEvery
	x.serverLive.timeout, x.clientLive.timeout = pongTimeout, pongTimeout
	ctx, cancel := context.WithCancel(context.Background())
	var running []chan error
	t.Cleanup(func() {
		cancel()
		for _, done := range running {
			select {
			case <-done:
			case <-time.After(10 * time.Second):
				t.Errorf("the tunnel was still running 10 s after the test ended")
				return
			}
		}
	})

	serverDone := make(chan error, 1)
	running = append(running, serverDone)
	go func() {
		serverDone <- server.Run(ctx, server.Config{
			Transport: "datachannel", Provider: provider, RoomURL: "room", KeyHex: tunnelKeyHex,
			// ai-generated: the policy lift, the echo target is on loopback (egress hardening).
			DNSServer: "127.0.0.1:53", Liveness: x.serverLive.config(), UnsafeAllowPrivateTargets: true,
			OnSessionClose: func(_, reason string) {
				x.closedMu.Lock()
				defer x.closedMu.Unlock()
				x.serverClosed = append(x.serverClosed, reason)
			},
		})
	}()
	eventually(t, 10*time.Second, "the server in the room", func() bool {
		return engines.count() == 1 && engines.at(0).LocalPeerID() != ""
	})

	clientDone := make(chan error, 1)
	running = append(running, clientDone)
	socks := make(chan string, 1)
	go func() {
		clientDone <- client.RunWithAddress(ctx, client.Config{
			Transport: "datachannel", Provider: provider, RoomURL: "room", KeyHex: tunnelKeyHex,
			DeviceID: "client-1", LocalAddr: "127.0.0.1:0", DNSServer: "127.0.0.1:53",
			Liveness:      x.clientLive.config(),
			OnSessionOpen: func(string) { x.clientOpened.Add(1) },
			OnHealth:      func(st control.Status) { x.clientDown.Store(st.Reconnects + st.UnhealthyEvents) },
		}, func(addr string) { socks <- addr })
	}()
	select {
	case x.socksAddr = <-socks:
	case err := <-clientDone:
		// Put it back for the cleanup above, which waits on this channel
		// and would otherwise wait out its 10 s and report a tunnel that
		// has already stopped.
		clientDone <- err
		t.Fatalf("the client ended before it listened: %v", err)
	case <-time.After(10 * time.Second):
		t.Fatal("the client did not listen within 10 s")
	}
	eventually(t, 15*time.Second, "the client's session", func() bool { return x.clientOpened.Load() == 1 })
	x.serverID, x.clientID = engines.at(0).LocalPeerID(), engines.at(1).LocalPeerID()
	return x
}

// checkStillUp fails the test if the session the client opened has ended at
// either end: the server closed it, or the client reconnected, opened
// another, or had its engine join again.
func (x *fakeTunnel) checkStillUp(t *testing.T) {
	t.Helper()
	x.closedMu.Lock()
	closed := append([]string(nil), x.serverClosed...)
	x.closedMu.Unlock()
	if len(closed) > 0 {
		t.Fatalf("the server closed the client's session under bulk (reason %q)", closed[0])
	}
	if down := x.clientDown.Load(); down > 0 || x.clientOpened.Load() != 1 || x.engines.count() != 2 {
		t.Fatalf("the client reconnected under bulk: %d reconnects or unhealthy events, %d sessions opened, "+
			"%d engine sessions made", down, x.clientOpened.Load(), x.engines.count())
	}
}

// registerFakeRoom registers a provider whose engine is SaluteJazz on room,
// and returns its name and the engine sessions it makes, in order.
func registerFakeRoom(t *testing.T, room *salutejazz.FakeRoom) (string, *madeEngines) {
	t.Helper()
	name := "salutejazz-fake-" + t.Name()
	made := &madeEngines{}
	enginebuiltin.Register(name, func(ctx context.Context, cfg enginebuiltin.Config) (engine.Session, error) {
		sess, err := salutejazz.New(ctx, room.Join(engine.Config{
			Name: cfg.Name, OnData: cfg.OnData, OnPeerData: cfg.OnPeerData,
			OnDatagram: cfg.OnDatagram, OnPeerDatagram: cfg.OnPeerDatagram,
			RequireTargetedPeer: cfg.RequireTargetedPeer,
		}))
		if err != nil {
			return nil, err
		}
		made.add(sess.(*salutejazz.Session))
		return sess, nil
	})
	return name, made
}

// madeEngines is every engine session a provider has made.
type madeEngines struct {
	mu       sync.Mutex
	sessions []*salutejazz.Session
}

func (m *madeEngines) add(s *salutejazz.Session) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sessions = append(m.sessions, s)
}

func (m *madeEngines) count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.sessions)
}

func (m *madeEngines) at(i int) *salutejazz.Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[i]
}

// liveness is what one end's control loops reported.
type liveness struct {
	// interval and timeout are how often the end pings and how long it waits
	// for a pong: tunnelProbeEvery and tunnelPongTimeout when zero.
	interval, timeout time.Duration

	mu        sync.Mutex
	pongs     []pong
	missed    int
	unhealthy int
}

// pong is one pong: when it came back, and how long after its ping.
type pong struct {
	at  time.Time
	rtt time.Duration
}

// config is the end's liveness, reporting into l.
func (l *liveness) config() control.Config {
	return control.Config{
		Interval: l.probeEvery(),
		Timeout:  cmp.Or(l.timeout, tunnelPongTimeout),
		OnPong: func(h control.Health) {
			l.mu.Lock()
			defer l.mu.Unlock()
			l.pongs = append(l.pongs, pong{at: h.LastSeen, rtt: h.RTT})
		},
		OnMissedPong: func(int) {
			l.mu.Lock()
			defer l.mu.Unlock()
			l.missed++
		},
		OnUnhealthy: func(int) {
			l.mu.Lock()
			defer l.mu.Unlock()
			l.unhealthy++
		},
	}
}

// probeEvery is how often the end pings.
func (l *liveness) probeEvery() time.Duration { return cmp.Or(l.interval, tunnelProbeEvery) }

// check fails the test unless the end missed no pong and every pong in the
// watch (the span the arguments from and to mark) came back within bound, with
// no gap in the watch longer than bound and a probe interval: between its
// start and the first pong, between two pongs, or between the last pong and
// its end.
func (l *liveness) check(t *testing.T, who string, from, to time.Time, bound time.Duration) {
	t.Helper()
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.missed > 0 || l.unhealthy > 0 {
		t.Fatalf("the %s reported missed pongs %d times and went unhealthy %d times", who, l.missed, l.unhealthy)
	}
	var longest, gap time.Duration
	last, count := from, 0
	for _, p := range l.pongs {
		if p.at.Before(from) || p.at.After(to) {
			continue
		}
		count++
		longest = max(longest, p.rtt)
		gap = max(gap, p.at.Sub(last))
		last = p.at
	}
	gap = max(gap, to.Sub(last))
	t.Logf("the %s: %d pongs in %s, the slowest in %s, the longest gap %s",
		who, count, to.Sub(from).Round(time.Millisecond), longest.Round(time.Millisecond), gap.Round(time.Millisecond))
	if longest > bound {
		t.Fatalf("the %s's slowest pong under bulk took %s, over the %s two windows allow", who, longest, bound)
	}
	if gap > bound+l.probeEvery() {
		t.Fatalf("the %s went %s without a pong under bulk, over the %s two windows and a probe allow",
			who, gap.Round(time.Millisecond), bound+l.probeEvery())
	}
}

// eventually waits until cond holds, and fails the test after timeout.
func eventually(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timeout after %s waiting for %s", timeout, what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// patternAt is the byte a transfer carries at offset: a sequence a shifted,
// dropped or repeated chunk cannot match.
func patternAt(offset int64) byte {
	return byte(offset % 251) //nolint:gosec // an offset is never negative, so the remainder fits
}

// fillPattern fills b with the pattern from offset.
func fillPattern(b []byte, offset int64) {
	for i := range b {
		b[i] = patternAt(offset + int64(i))
	}
}

// transfer counts what one bulk transfer has moved, and the first thing that
// went wrong with it.
type transfer struct {
	bytes atomic.Int64
	mu    sync.Mutex
	err   error
}

func (x *transfer) moved() int64 { return x.bytes.Load() }

func (x *transfer) fail(err error) {
	x.mu.Lock()
	defer x.mu.Unlock()
	if x.err == nil {
		x.err = err
	}
}

func (x *transfer) fault() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	return x.err
}

// errCorrupt is a transfer that delivered bytes nobody sent.
var errCorrupt = errors.New("bytes arrived that were not sent")

// readPattern reads r until it fails, checking each byte against the
// pattern.
func readPattern(r io.Reader, x *transfer) {
	buf, want := make([]byte, tunnelChunk), make([]byte, tunnelChunk)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			fillPattern(want[:n], x.bytes.Load())
			if !bytes.Equal(buf[:n], want[:n]) {
				x.fail(errCorrupt)
				return
			}
			x.bytes.Add(int64(n))
		}
		if err != nil {
			return
		}
	}
}

// writePattern writes the pattern to w until a write fails.
func writePattern(w io.Writer, x *transfer) {
	buf := make([]byte, tunnelChunk)
	for {
		fillPattern(buf, x.bytes.Load())
		n, err := w.Write(buf)
		x.bytes.Add(int64(n))
		if err != nil {
			return
		}
	}
}

// startSource listens on loopback for the download's target, which writes
// the pattern to whoever connects for as long as they read.
func startSource(t *testing.T) string {
	t.Helper()
	return serveLoopback(t, func(conn net.Conn) { writePattern(conn, &transfer{}) })
}

// startSink listens on loopback for the upload's target, which reads what
// arrives and checks it.
func startSink(t *testing.T) *sink {
	t.Helper()
	s := &sink{}
	s.addr = serveLoopback(t, func(conn net.Conn) { readPattern(conn, &s.got) })
	return s
}

// sink is the upload's target and what reached it.
type sink struct {
	addr string
	got  transfer
}

// serveLoopback accepts on a loopback port until the test ends and hands
// every connection to serve, closing it when serve returns.
func serveLoopback(t *testing.T, serve func(net.Conn)) string {
	t.Helper()
	var lc net.ListenConfig
	ln, err := lc.Listen(context.Background(), "tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer func() { _ = conn.Close() }()
				serve(conn)
			}()
		}
	}()
	return ln.Addr().String()
}

// startDownload connects to target through the tunnel and reads from it
// until the test ends, checking what arrives.
func startDownload(t *testing.T, socksAddr, target string) *transfer {
	t.Helper()
	conn := socksConnect(t, socksAddr, target)
	x := &transfer{}
	go readPattern(conn, x)
	return x
}

// startUpload connects to the sink through the tunnel and writes to it until
// the test ends. What it reports is what reached the sink.
func startUpload(t *testing.T, socksAddr string, to *sink) *transfer {
	t.Helper()
	conn := socksConnect(t, socksAddr, to.addr)
	go writePattern(conn, &transfer{})
	return &to.got
}

// socksConnect opens a SOCKS5 CONNECT to target through the client, closed
// when the test ends.
func socksConnect(t *testing.T, socksAddr, target string) net.Conn {
	t.Helper()
	dialer := net.Dialer{Timeout: 5 * time.Second}
	conn, err := dialer.DialContext(context.Background(), "tcp4", socksAddr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	request := append([]byte{5, 1, 0, 5, 1, 0, 1}, net.ParseIP(host).To4()...)
	request = binary.BigEndian.AppendUint16(request, uint16(port)) //nolint:gosec // a port fits
	if _, err := conn.Write(request); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2+10)
	if _, err := io.ReadFull(conn, reply); err != nil {
		t.Fatalf("the SOCKS connect: %v", err)
	}
	if !bytes.Equal(reply[:2], []byte{5, 0}) || reply[3] != 0 {
		t.Fatalf("the SOCKS connect was refused: %v", reply)
	}
	return conn
}
