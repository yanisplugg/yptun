package datachannel

import (
	"context"
	"encoding/json"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/muxconn"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// ai-generated: the whole file (the resolver burst of olcrtc#11 through a
// model of the Jitsi bridge).

// The bridge hands every message an endpoint sends to a queue of 50
// (jitsi-videobridge AbstractEndpointMessageTransport.incomingMessageQueue,
// a jitsi-utils PacketQueue) and forwards from it one at a time; when the
// queue is full, the oldest message is dropped. relayStep is how long the
// model takes to forward one message.
const (
	relayQueue = 50
	relayStep  = 2 * time.Millisecond

	burstStreams  = 64
	burstDial     = 5 * time.Millisecond // the exit's dial to the resolver
	burstDeadline = 2 * time.Second      // the resolver closes an idle TCP connection after this
	burstQuery    = 38                   // a framed query, as in S5
	burstAnswer   = 100                  // a framed answer
)

// relayLeg is one direction of the bridge.
type relayLeg struct {
	mu      sync.Mutex
	queue   [][]byte
	dropped int
	wake    chan struct{}
	deliver func([]byte)
}

func newRelayLeg() *relayLeg { return &relayLeg{wake: make(chan struct{}, 1)} }

func (l *relayLeg) push(msg []byte) {
	l.mu.Lock()
	if len(l.queue) == relayQueue {
		l.queue = l.queue[1:]
		l.dropped++
	}
	l.queue = append(l.queue, append([]byte(nil), msg...))
	l.mu.Unlock()
	select {
	case l.wake <- struct{}{}:
	default:
	}
}

func (l *relayLeg) pop() []byte {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.queue) == 0 {
		return nil
	}
	msg := l.queue[0]
	l.queue = l.queue[1:]
	return msg
}

func (l *relayLeg) run(ctx context.Context) {
	for {
		msg := l.pop()
		if msg == nil {
			select {
			case <-l.wake:
				continue
			case <-ctx.Done():
				return
			}
		}
		select {
		case <-time.After(relayStep):
		case <-ctx.Done():
			return
		}
		l.deliver(msg)
	}
}

func (l *relayLeg) drops() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.dropped
}

// relaySession is an engine whose sends go into one leg of the bridge.
type relaySession struct {
	stubSession
	out *relayLeg
}

func (s *relaySession) Send(data []byte) error {
	s.out.push(data)
	return nil
}

// openBurstEnd is one side of the tunnel: a datachannel transport over the
// bridge, the encrypted conn and the smux session on top. The side sends
// into out and hears what in delivers.
func openBurstEnd(ctx context.Context, t *testing.T, provider string, out, in *relayLeg, role crypto.Role) *smux.Session {
	t.Helper()
	enginebuiltin.Register(provider, func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &relaySession{stubSession: stubSession{canSend: true}, out: out}, nil
	})
	tr, err := New(ctx, transport.Config{Provider: provider})
	if err != nil {
		t.Fatalf("New(%s) error = %v", provider, err)
	}
	keys, err := crypto.NewKeySet([]byte("0123456789abcdef0123456789abcdef"), role)
	if err != nil {
		t.Fatalf("NewKeySet() error = %v", err)
	}
	conn := muxconn.New(tr, keys)
	in.deliver = conn.Push
	newSession := smux.Server
	if role == crypto.Client {
		newSession = smux.Client
	}
	sess, err := newSession(conn, runtime.SmuxConfigFor(tr))
	if err != nil {
		t.Fatalf("smux session error = %v", err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	return sess
}

// serveBurst answers every stream the way the exit answers a DNS query over
// the stream: the CONNECT, the dial, the ack, then the query and its answer.
func serveBurst(sess *smux.Session) {
	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			return
		}
		go func() {
			defer func() { _ = stream.Close() }()
			_ = stream.SetDeadline(time.Now().Add(2 * burstDeadline))
			if _, err := stream.Read(make([]byte, 256)); err != nil {
				return
			}
			time.Sleep(burstDial)
			if _, err := stream.Write([]byte{0}); err != nil {
				return
			}
			if _, err := io.ReadFull(stream, make([]byte, burstQuery)); err != nil {
				return
			}
			_, _ = stream.Write(make([]byte, burstAnswer))
		}()
	}
}

// askOverStream is one query of the burst, as the client sends it.
func askOverStream(sess *smux.Session, connect []byte) bool {
	stream, err := sess.OpenStream()
	if err != nil {
		return false
	}
	defer func() { _ = stream.Close() }()
	_ = stream.SetDeadline(time.Now().Add(burstDeadline))
	if _, err = stream.Write(connect); err != nil {
		return false
	}
	if _, err = io.ReadFull(stream, make([]byte, 1)); err != nil {
		return false
	}
	if _, err = stream.Write(make([]byte, burstQuery)); err != nil {
		return false
	}
	_, err = io.ReadFull(stream, make([]byte, burstAnswer))
	return err == nil
}

func TestABurstOfNewStreamsGetsThroughTheBridgeQueue(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	up, down := newRelayLeg(), newRelayLeg()
	client := openBurstEnd(ctx, t, "datachannel-burst-client", up, down, crypto.Client)
	server := openBurstEnd(ctx, t, "datachannel-burst-server", down, up, crypto.Server)
	go up.run(ctx)
	go down.run(ctx)
	go serveBurst(server)

	connect, err := json.Marshal(map[string]any{"cmd": "connect", "addr": "8.8.8.8", "port": 53})
	if err != nil {
		t.Fatalf("marshal connect: %v", err)
	}
	var answered atomic.Int32
	var wg sync.WaitGroup
	for range burstStreams {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if askOverStream(client, connect) {
				answered.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := int(answered.Load()); got != burstStreams {
		t.Fatalf("%d of %d queries answered within %v; the bridge dropped %d messages up and %d down",
			got, burstStreams, burstDeadline, up.drops(), down.drops())
	}
	if up.drops()+down.drops() != 0 {
		t.Fatalf("the bridge dropped %d messages up and %d down", up.drops(), down.drops())
	}
}
