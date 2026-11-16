package script

import (
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/transport/v5/stdnet"

	"github.com/p1neappleXpress/OpenFlux/utils"
)

// paceConfig is how a PeerConnection's UDP sockets space their datagrams out. It is the same for every
// socket of the connection and can be changed while it lives (the file is re-read, see watch).
//
// A relayed WebRTC path carries bursts badly: SCTP releases whatever its congestion window allows the
// moment an acknowledgement arrives, up to a dozen datagrams back to back, and a TURN relay in front of a
// mobile network keeps the first of them and drops most of the rest. SCTP then loses its window, backs
// its retransmission timer off, and the channel is dead for a minute. The same bytes sent a few
// milliseconds apart arrive.
type paceConfig struct {
	rate  atomic.Int64 // bytes per second, 0 = send at once
	queue atomic.Int64 // datagrams that may wait; more are dropped (a tail drop is the signal TCP inside reacts to)
	burst atomic.Int64 // datagrams that may go back to back

	// dcbuf is the most bytes a data channel may hold unsent or unacknowledged before send() starts dropping
	// (0 = no limit). SCTP's queue has no bound of its own: a download fills it with seconds of data, the
	// TCP inside sees a path that is merely slow and never backs off, and what it sent minutes ago is
	// delivered long after it was retransmitted.
	dcbuf     atomic.Int64
	dcDropped atomic.Int64
}

func (c *paceConfig) on() bool { return c != nil && c.rate.Load() > 0 }

// parsePace reads "rate=<KB/s> queue=<datagrams> burst=<datagrams> dcbuf=<KB>" (any order); missing numbers
// take their defaults.
func parsePace(s string, c *paceConfig) {
	f := strings.Fields(strings.NewReplacer("=", " ", ",", " ", ":", " ").Replace(s))
	val := func(name string, def int64) int64 {
		for i := 0; i+1 < len(f); i++ {
			if f[i] == name {
				if v, err := strconv.ParseInt(f[i+1], 10, 64); err == nil {
					return v
				}
			}
		}
		return def
	}
	c.rate.Store(val("rate", 0) * 1000)
	c.queue.Store(val("queue", 60))
	c.burst.Store(val("burst", 1))
	c.dcbuf.Store(val("dcbuf", 0) * 1000)
}

var paceEnv struct {
	once sync.Once
	c    *paceConfig
}

// paceFromEnv builds the configuration from OPENFLUX_PACE_FILE (a file holding e.g. "rate=250 queue=60
// burst=1", polled twice a second so a test can change it without restarting anything). It returns nil
// when the variable is not set. The configuration is shared by every connection of the process.
func paceFromEnv() *paceConfig {
	path := os.Getenv("OPENFLUX_PACE_FILE")
	if path == "" {
		return nil
	}
	paceEnv.once.Do(func() {
		c := &paceConfig{}
		load := func() {
			if b, err := os.ReadFile(path); err == nil {
				parsePace(string(b), c)
			} else {
				c.rate.Store(0)
			}
		}
		load()
		go func() {
			for range time.Tick(500 * time.Millisecond) {
				load()
			}
		}()
		paceEnv.c = c
	})
	return paceEnv.c
}

// pacedNet is the standard network with one change: the sockets given out by ListenPacket (the ones a TURN
// client sends through) pace what they write.
type pacedNet struct {
	*stdnet.Net
	cfg  *paceConfig
	name string
}

func (n *pacedNet) ListenPacket(network, address string) (net.PacketConn, error) {
	c, err := n.Net.ListenPacket(network, address)
	if err != nil {
		return nil, err
	}
	pacedSockets.Add(1)
	utils.Debugf("[PACE:%s] paced socket %s", n.name, c.LocalAddr())
	return newPacedConn(c, n.cfg, n.name), nil
}

// pacedSockets counts the sockets handed out, for the test that the TURN client really sits on one.
var pacedSockets atomic.Int64

type pacedPkt struct {
	b  []byte
	to net.Addr
	at time.Time
}

type pacedConn struct {
	net.PacketConn
	cfg  *paceConfig
	name string

	mu     sync.Mutex
	q      []pacedPkt
	wake   chan struct{}
	done   chan struct{}
	closed bool

	sent, sentBytes, dropped, passed atomic.Int64
	maxQ                             atomic.Int64
	maxWait                          atomic.Int64 // ns
}

func newPacedConn(c net.PacketConn, cfg *paceConfig, name string) *pacedConn {
	p := &pacedConn{PacketConn: c, cfg: cfg, name: name, wake: make(chan struct{}, 1), done: make(chan struct{})}
	go p.run()
	go p.report()
	return p
}

func (p *pacedConn) WriteTo(b []byte, to net.Addr) (int, error) {
	if !p.cfg.on() {
		p.passed.Add(1)
		return p.PacketConn.WriteTo(b, to)
	}
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return 0, net.ErrClosed
	}
	if int64(len(p.q)) >= p.cfg.queue.Load() {
		p.mu.Unlock()
		p.dropped.Add(1)
		return len(b), nil
	}
	p.q = append(p.q, pacedPkt{b: append([]byte(nil), b...), to: to, at: time.Now()})
	if n := int64(len(p.q)); n > p.maxQ.Load() {
		p.maxQ.Store(n)
	}
	p.mu.Unlock()
	select {
	case p.wake <- struct{}{}:
	default:
	}
	return len(b), nil
}

func (p *pacedConn) run() {
	var next time.Time // the earliest moment the next datagram may leave
	for {
		p.mu.Lock()
		if len(p.q) == 0 {
			p.mu.Unlock()
			select {
			case <-p.wake:
			case <-p.done:
				return
			}
			continue
		}
		pkt := p.q[0]
		p.q[0] = pacedPkt{}
		p.q = p.q[1:]
		p.mu.Unlock()

		rate := p.cfg.rate.Load()
		now := time.Now()
		if rate > 0 {
			// A burst of n datagrams is allowed to leave together: the clock may run n-1 spacings behind.
			slack := time.Duration(p.cfg.burst.Load()-1) * time.Duration(float64(1300+28)/float64(rate)*float64(time.Second))
			if wait := next.Sub(now) - slack; wait > 0 {
				select {
				case <-time.After(wait):
				case <-p.done:
					return
				}
				now = time.Now()
			}
			if next.Before(now.Add(-slack)) {
				next = now.Add(-slack)
			}
			next = next.Add(time.Duration(float64(len(pkt.b)+28) / float64(rate) * float64(time.Second)))
		}
		if w := int64(now.Sub(pkt.at)); w > p.maxWait.Load() {
			p.maxWait.Store(w)
		}
		if _, err := p.PacketConn.WriteTo(pkt.b, pkt.to); err == nil {
			p.sent.Add(1)
			p.sentBytes.Add(int64(len(pkt.b)))
		}
	}
}

// report logs what the pacer did every five seconds while it has anything to say.
func (p *pacedConn) report() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	var lastSent, lastBytes, lastDropped, lastPassed int64
	for {
		select {
		case <-t.C:
			s, b, d, ps := p.sent.Load(), p.sentBytes.Load(), p.dropped.Load(), p.passed.Load()
			dc := p.cfg.dcDropped.Swap(0)
			if s != lastSent || d != lastDropped || ps != lastPassed || dc != 0 {
				p.mu.Lock()
				depth := len(p.q)
				p.mu.Unlock()
				utils.Debugf("[PACE:%s] 5s: paced %d dgrams %d B, unpaced %d, tail-dropped %d, channel-dropped %d, queue now %d (max %d), longest wait %dms, rate %d B/s",
					p.name, s-lastSent, b-lastBytes, ps-lastPassed, d-lastDropped, dc, depth, p.maxQ.Swap(0), p.maxWait.Swap(0)/int64(time.Millisecond), p.cfg.rate.Load())
			}
			lastSent, lastBytes, lastDropped, lastPassed = s, b, d, ps
		case <-p.done:
			return
		}
	}
}

func (p *pacedConn) Close() error {
	p.mu.Lock()
	if !p.closed {
		p.closed = true
		close(p.done)
	}
	p.mu.Unlock()
	return p.PacketConn.Close()
}
