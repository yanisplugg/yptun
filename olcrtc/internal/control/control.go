// Package control implements the post-handshake control stream protocol.
//
// The control stream is the first smux stream after the olcrtc handshake. It
// stays inside the encrypted muxconn path, so ping/pong proves that the actual
// tunnel path still round-trips, not merely that the provider connection is up.
//
// Wire format matches the handshake framing: a 4-byte big-endian length
// followed by a JSON message.
package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/framing"
)

const (
	// ProtoVersion identifies the control stream wire format.
	ProtoVersion = 1
	// MaxMessageSize caps one control frame.
	MaxMessageSize = 16 * 1024
	// DefaultInterval is the default interval between ping probes.
	DefaultInterval = 10 * time.Second
	// DefaultTimeout is the default time to wait for a pong. Generous because
	// the ping shares the bulk smux/KCP stream: under a heavy transfer the
	// ping byte can be head-of-line blocked behind queued data for several
	// seconds, which is liveness-OK, not a dead link.
	//
	// Conservative default: a conventional provider (jitsi/datachannel) that
	// goes silent this long is genuinely dead and should reconnect promptly.
	// Video-paced transports with isolated control planes (vp8channel) need a
	// longer window because KCP batching + frame pacing can delay control
	// packets under load (issue #95); that relaxed window is applied per
	// transport via runtime.LivenessTimeout, not by widening this default.
	DefaultTimeout = 15 * time.Second
	// DefaultFailures is the default number of consecutive missed pongs before
	// the stream is marked unhealthy.
	DefaultFailures = 4
	// maxStalledProbes bounds how many probes in a row payload progress may
	// excuse. What bounds the queue a pong can sit behind is not the relay.
	// Neither relay pushes back on a sender - the JVB queues about 2 MB
	// toward a receiver and drops the rest, Sber's LiveKit 1.5.3 queues
	// without a limit - so the Jitsi and SaluteJazz engines keep a window
	// per destination instead. A sender holds back once it has handed the relay
	// a window of bytes for one peer that the peer has not yet echoed.
	// Jitsi's (engine/jitsi/relaywindow.go, olcrtc#15) is 512 KiB, on top
	// of the 256 KB its own send path may hold (bridge.go): under two
	// seconds each way at the ~5 Mbit/s a JVB carries. SaluteJazz's
	// (engine/salutejazz/window.go, olcrtc#49) is 192 KiB, sized so that a
	// ping behind one window and its pong behind another come back inside
	// the 15 s timeout even on the 34 kB/s SFU-to-receiver leg the gate has
	// measured through Sber's relay. On those carriers a pong is therefore
	// never minutes late, and three excused probes - 30 s at the default
	// 10 s interval - is already more than the delay a window can add.
	// Eighteen, plus the four failures that follow, was 220 seconds: the
	// three and a half minutes a client sat on a session the server had
	// already closed while the server's next session kept the counter
	// moving (olcbox#25). Now it is seven probes, 70 seconds.
	//
	// On a transport with no control plane of its own - datachannel, on
	// every carrier, and videochannel and seichannel - the excuse cannot
	// tell a transfer from the peer's own pings. The control stream shares
	// the data session there, and Progress counts payload on every stream
	// of it, the control stream's included, so a link that carries the
	// peer's pings to us but not ours to it has its first three timeouts
	// excused as if a transfer were in flight. vp8channel runs the control
	// stream on a conn of its own (muxconn.NewControl), and Progress reads
	// only the data conn, which the peer's pings never reach. That is a
	// known limitation, and an issue apart from the queue.
	//
	// ai-generated: the account of the windows and of the limitation
	// (olcrtc#49).
	maxStalledProbes = 3
)

// MsgType labels a control message.
type MsgType string

const (
	// TypePing is sent periodically to prove control-stream liveness.
	TypePing MsgType = "CONTROL_PING"
	// TypePong replies to a ping with the same sequence and timestamp.
	TypePong MsgType = "CONTROL_PONG"
	// TypeClose tells the peer this control session is intentionally closing.
	TypeClose MsgType = "CONTROL_CLOSE"
)

var (
	// ErrUnhealthy is returned when the stream misses too many pong replies.
	ErrUnhealthy = errors.New("control stream unhealthy")
	// ErrClosedByPeer is returned when the peer gracefully closes the control session.
	ErrClosedByPeer = errors.New("control stream closed by peer")
	// ErrProtocolVersion is returned when the peer announces an incompatible version.
	ErrProtocolVersion = errors.New("incompatible control protocol version")
	// ErrUnexpectedMessage is returned for unknown or malformed control message types.
	ErrUnexpectedMessage = errors.New("unexpected control message")
	// ErrFrameTooLarge is returned when a frame exceeds [MaxMessageSize].
	ErrFrameTooLarge = framing.ErrFrameTooLarge
)

// Message is one control-stream frame.
type Message struct {
	Version      int     `json:"version"`
	Type         MsgType `json:"type"`
	Seq          uint64  `json:"seq,omitempty"`
	SentUnixNano int64   `json:"sent_unix_nano,omitempty"`
}

// Health is reported when a ping round trip completes.
type Health struct {
	Seq      uint64
	RTT      time.Duration
	LastSeen time.Time
}

// Status is a point-in-time view of control-stream health maintained by
// callers that embed the control loop.
type Status struct {
	SessionID       string
	LastPong        time.Time
	LastRTT         time.Duration
	MissedPongs     int
	Reconnects      uint64
	UnhealthyEvents uint64
	LastUnhealthy   time.Time
}

// Config controls the liveness loop.
type Config struct {
	Interval time.Duration
	Timeout  time.Duration
	Failures int

	// OnPong is called after a matching pong is received.
	OnPong func(Health)
	// OnMissedPong is called when one or more outstanding pongs time out.
	OnMissedPong func(missed int)
	// OnUnhealthy is called before Run returns [ErrUnhealthy].
	OnUnhealthy func(missed int)
	// Progress reports a monotonic count of payload received from the peer.
	//
	// On a transport with no control plane of its own the ping shares one
	// smux session, and one SCTP association, with every bulk stream: a
	// speedtest queues megabytes ahead of it and the pong comes back tens of
	// seconds late. A peer whose payload is still arriving is not a dead
	// link, so while this counter moves a timed-out probe is not counted as
	// a missed pong — up to [maxStalledProbes] in a row. Nil disables the
	// check and every timeout counts, as it did before.
	Progress func() uint64
	// OnStalled is called when payload progress excuses timed-out probes.
	OnStalled func(timedOut int)
	// SendStalled reports whether the data plane has given up putting bytes
	// on the wire.
	//
	// Liveness is measured on the control plane, and on a transport that has
	// one of its own that plane is a different KCP session with its own send
	// window. So the two directions can disagree completely: pongs arrive on
	// time while the data plane has not accepted a byte for half a minute,
	// and every new stream fails with a write timeout that smux treats as a
	// deadline rather than a fault. The session stays open, the tunnel
	// reports healthy, nothing is carried, and no reconnect is ever
	// triggered, because a reconnect only follows the control stream dying.
	//
	// Consulted on every probe. Nil disables the check.
	SendStalled func() bool
	// OnSendStalled is called before Run returns because the data plane
	// stopped sending.
	OnSendStalled func()
	// BeforeClose is called once, on cancellation, while the stream can
	// still carry a frame, so the caller can tell the peer it is leaving.
	//
	// Cancelling this context is the first thing a deliberate stop does, and
	// the watcher below closes the stream the moment it happens - so a close
	// notice sent afterwards, from the caller's own teardown, is written to a
	// stream that is already gone and never leaves the process. The peer then
	// keeps the session, its streams and its KCP state until liveness gives
	// up on it, which is tens of seconds later.
	//
	// Runs on the watcher's goroutine and is bounded by closeNoticeBudget: a
	// slow notice delays the close, it cannot prevent it.
	BeforeClose func()
}

func (cfg Config) withDefaults() Config {
	if cfg.Interval <= 0 {
		cfg.Interval = DefaultInterval
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = DefaultTimeout
	}
	if cfg.Failures <= 0 {
		cfg.Failures = DefaultFailures
	}
	return cfg
}

// Run drives bidirectional ping/pong liveness until ctx is canceled, rw closes,
// or the configured failure threshold is reached.
func Run(ctx context.Context, rw io.ReadWriteCloser, cfg Config) error {
	cfg = cfg.withDefaults()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	state := &state{
		rw:      rw,
		cfg:     cfg,
		pending: make(map[uint64]time.Time),
		now:     time.Now,
		out:     make(chan Message, 16),
	}

	errCh := make(chan error, 3)
	go func() {
		<-ctx.Done()
		announceClosing(cfg.BeforeClose)
		_ = rw.Close()
	}()
	go func() { errCh <- state.readLoop(ctx) }()
	go func() { errCh <- state.probeLoop(ctx) }()
	go func() { errCh <- state.writeLoop(ctx) }()

	err := <-errCh
	cancel()
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return nil
	}
	return err
}

// closeNoticeBudget bounds Config.BeforeClose. Long enough for one frame on a
// link that still works, short enough that a link that does not cannot hold
// the stream open while everything above it waits for the close.
const closeNoticeBudget = 1500 * time.Millisecond

func announceClosing(notify func()) {
	if notify == nil {
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		notify()
	}()
	select {
	case <-done:
	case <-time.After(closeNoticeBudget):
	}
}

type state struct {
	rw  io.ReadWriteCloser
	cfg Config
	now func() time.Time

	out chan Message

	mu           sync.Mutex
	pending      map[uint64]time.Time
	nextSeq      uint64
	failures     int
	stalled      int
	lastProgress uint64
	progressSeen bool
}

func (s *state) readLoop(ctx context.Context) error {
	for {
		raw, err := readFrame(s.rw)
		if err != nil {
			return readLoopErr(ctx, err)
		}
		msg, err := parseMessage(raw)
		if err != nil {
			return err
		}
		if err := s.handleReadMessage(ctx, msg); err != nil {
			return err
		}
	}
}

func readLoopErr(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		return fmt.Errorf("read loop canceled: %w", ctx.Err())
	}
	return err
}

func (s *state) handleReadMessage(ctx context.Context, msg Message) error {
	switch msg.Type {
	case TypePing:
		return s.enqueuePong(ctx, msg)
	case TypePong:
		s.handlePong(msg)
		return nil
	case TypeClose:
		return ErrClosedByPeer
	default:
		return fmt.Errorf("%w: got %q", ErrUnexpectedMessage, msg.Type)
	}
}

func (s *state) enqueuePong(ctx context.Context, ping Message) error {
	err := s.enqueue(ctx, Message{
		Version:      ProtoVersion,
		Type:         TypePong,
		Seq:          ping.Seq,
		SentUnixNano: ping.SentUnixNano,
	})
	if err != nil {
		return readLoopErr(ctx, err)
	}
	return nil
}

func (s *state) probeLoop(ctx context.Context) error {
	ticker := time.NewTicker(s.cfg.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return fmt.Errorf("probe loop canceled: %w", ctx.Err())
		case <-ticker.C:
			if err := s.sendProbe(ctx); err != nil {
				return err
			}
		}
	}
}

func (s *state) sendProbe(ctx context.Context) error {
	now := s.now()
	// Read outside the lock: both are caller-supplied code.
	moved := s.peerSending()
	// A link we cannot send on is dead whatever comes back down it. Checked
	// before the pongs, because the pongs will look fine.
	if s.cfg.SendStalled != nil && s.cfg.SendStalled() {
		if s.cfg.OnSendStalled != nil {
			s.cfg.OnSendStalled()
		}
		return fmt.Errorf("%w: the data plane stopped sending", ErrUnhealthy)
	}

	s.mu.Lock()
	timedOut := s.expireProbes(now)
	stalledNow := 0
	// `moved` alone is not enough to excuse a timeout: a peer can keep
	// sending down a link we can no longer answer on, which is the exact
	// shape SendStalled catches above.
	if timedOut > 0 && moved && s.stalled < maxStalledProbes {
		s.stalled += timedOut
		stalledNow, timedOut = timedOut, 0
	}
	missedNow := timedOut
	s.failures += timedOut
	missed := s.failures
	if s.failures >= s.cfg.Failures {
		s.mu.Unlock()
		if missedNow > 0 && s.cfg.OnMissedPong != nil {
			s.cfg.OnMissedPong(missed)
		}
		if s.cfg.OnUnhealthy != nil {
			s.cfg.OnUnhealthy(missed)
		}
		return fmt.Errorf("%w: missed %d pong(s)", ErrUnhealthy, missed)
	}

	s.nextSeq++
	seq := s.nextSeq
	s.pending[seq] = now
	s.mu.Unlock()
	if missedNow > 0 && s.cfg.OnMissedPong != nil {
		s.cfg.OnMissedPong(missed)
	}
	if stalledNow > 0 && s.cfg.OnStalled != nil {
		s.cfg.OnStalled(stalledNow)
	}

	return s.enqueue(ctx, Message{
		Version:      ProtoVersion,
		Type:         TypePing,
		Seq:          seq,
		SentUnixNano: now.UnixNano(),
	})
}

// expireProbes drops every ping that has waited longer than the timeout and
// reports how many. The caller holds s.mu.
func (s *state) expireProbes(now time.Time) int {
	timedOut := 0
	for seq, sent := range s.pending {
		if now.Sub(sent) < s.cfg.Timeout {
			continue
		}
		delete(s.pending, seq)
		timedOut++
	}
	return timedOut
}

func (s *state) handlePong(msg Message) {
	now := s.now()

	s.mu.Lock()
	sent, ok := s.pending[msg.Seq]
	if ok {
		delete(s.pending, msg.Seq)
		s.failures = 0
		s.stalled = 0
	}
	s.mu.Unlock()

	if !ok || s.cfg.OnPong == nil {
		return
	}
	s.cfg.OnPong(Health{
		Seq:      msg.Seq,
		RTT:      now.Sub(sent),
		LastSeen: now,
	})
}

// peerSending reports whether the peer's payload counter has moved since the
// previous probe. A counter that goes backwards (a session rebuilt under us)
// counts as movement: what matters is that something arrived.
func (s *state) peerSending() bool {
	if s.cfg.Progress == nil {
		return false
	}
	seen := s.cfg.Progress()
	s.mu.Lock()
	defer s.mu.Unlock()
	moved := s.progressSeen && seen != s.lastProgress
	s.lastProgress, s.progressSeen = seen, true
	return moved
}

func (s *state) enqueue(ctx context.Context, msg Message) error {
	select {
	case <-ctx.Done():
		return fmt.Errorf("enqueue canceled: %w", ctx.Err())
	case s.out <- msg:
		return nil
	}
}

func (s *state) writeLoop(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return fmt.Errorf("write loop canceled: %w", ctx.Err())
		case msg := <-s.out:
			if err := writeFrame(s.rw, msg); err != nil {
				if ctx.Err() != nil {
					return fmt.Errorf("write loop canceled: %w", ctx.Err())
				}
				return err
			}
		}
	}
}

func parseMessage(raw []byte) (Message, error) {
	var msg Message
	if err := json.Unmarshal(raw, &msg); err != nil {
		return Message{}, fmt.Errorf("parse control message: %w", err)
	}
	if msg.Version != ProtoVersion {
		return Message{}, fmt.Errorf("%w: peer v%d, local v%d",
			ErrProtocolVersion, msg.Version, ProtoVersion)
	}
	if msg.Type != TypePing && msg.Type != TypePong && msg.Type != TypeClose {
		return Message{}, fmt.Errorf("%w: got %q", ErrUnexpectedMessage, msg.Type)
	}
	return msg, nil
}

// SendClose sends a best-effort graceful close notification on the control stream.
func SendClose(w io.Writer) error {
	return writeFrame(w, Message{Version: ProtoVersion, Type: TypeClose})
}

func writeFrame(w io.Writer, msg Message) error {
	if err := framing.WriteJSON(w, msg, MaxMessageSize); err != nil {
		return fmt.Errorf("control: %w", err)
	}
	return nil
}

func readFrame(r io.Reader) ([]byte, error) {
	body, err := framing.ReadBytes(r, MaxMessageSize)
	if err != nil {
		return nil, fmt.Errorf("control: %w", err)
	}
	return body, nil
}
