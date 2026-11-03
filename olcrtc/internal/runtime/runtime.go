// Package runtime holds infrastructure shared by the olcrtc server and
// client: smux tuning, key setup, and control-stream health bookkeeping.
// The lifecycle differences between server and client (accept loop / SOCKS5
// dial vs. SOCKS5 listener / tunnel) live in their respective packages.
package runtime

import (
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/hostprofile"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

const (
	// SmuxFrameOverhead is the fixed smux frame header size. MaxFrameSize
	// caps only the smux payload, while muxconn encrypts and sends the whole
	// smux frame as one transport message.
	SmuxFrameOverhead = 8
	// SmuxWireOverhead is the non-payload overhead added around each smux
	// frame before it reaches the transport payload limit.
	SmuxWireOverhead = crypto.WireOverhead + SmuxFrameOverhead
	// MinSmuxWirePayload is the smallest useful encrypted transport payload
	// cap that can still carry a non-empty smux frame.
	MinSmuxWirePayload = SmuxWireOverhead + 1

	smuxMaxFrameSize     = 32 * 1024
	smuxMaxReceiveBuffer = 32 * 1024 * 1024
	smuxMaxStreamBuffer  = 4 * 1024 * 1024

	// A mobile VPN extension is a much smaller box than a server. Apple gives
	// a packet tunnel provider roughly 50 MB and terminates it for exceeding
	// that, and the same process also carries sing-box and Xray. The windows
	// above do not fit: 32 MB of session buffer plus 4 MB per stream is the
	// budget on its own, and a speed test with a dozen parallel streams fills
	// it. A window only has to cover the bandwidth-delay product, which on a
	// 5 Mbit/s relay at 300 ms is about 200 KB, so these are still several
	// times larger than the link can keep in flight.
	smuxConstrainedReceiveBuffer = 4 * 1024 * 1024
	smuxConstrainedStreamBuffer  = 512 * 1024
)

// UseConstrainedBuffers shrinks every receive window this process advertises,
// for hosts that are killed rather than swapped when they grow: the iOS
// packet tunnel extension, and the Android VPN service beside it.
//
// Receive windows are local. smux announces its per-stream window to the peer
// with a window update, and KCP carries its receive window in every segment
// header, so a client can shrink unilaterally and an unchanged server simply
// sends less at a time.
//
// The flag itself lives in [hostprofile], a leaf every package can import;
// these are the names the transports and the client library have always
// used for it.
func UseConstrainedBuffers() { hostprofile.UseConstrainedBuffers() }

// BuffersAreConstrained reports the profile chosen for this process.
func BuffersAreConstrained() bool { return hostprofile.BuffersAreConstrained() }

// ResetBufferProfileForTest restores the server profile. Tests only: the
// switch is one-way in a running process, which is what a host that never
// changes shape mid-flight wants. Exported because the transports that read
// the profile test it from their own packages.
func ResetBufferProfileForTest() { hostprofile.ResetForTest() }

func receiveBufferSizes() (int, int) {
	if hostprofile.BuffersAreConstrained() {
		return smuxConstrainedReceiveBuffer, smuxConstrainedStreamBuffer
	}
	return smuxMaxReceiveBuffer, smuxMaxStreamBuffer
}

// ErrKeyRequired is returned when no encryption key is provided.
var ErrKeyRequired = errors.New("key required (use -key <hex>)")

// ErrKeySize is returned when the encryption key is not 32 bytes.
var ErrKeySize = errors.New("key must be 32 bytes")

// SetupKeySet decodes a 64-char hex PSK and creates role-aware v2 keys.
func SetupKeySet(keyHex string, role crypto.Role) (*crypto.KeySet, error) {
	if keyHex == "" {
		return nil, ErrKeyRequired
	}
	key, err := hex.DecodeString(keyHex)
	if err != nil {
		return nil, fmt.Errorf("failed to decode key: %w", err)
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("%w, got %d", ErrKeySize, len(key))
	}
	keys, err := crypto.NewKeySet(key, role)
	if err != nil {
		return nil, fmt.Errorf("failed to create key set: %w", err)
	}
	return keys, nil
}

// SmuxConfig returns the tuned smux config used on both ends. Both peers
// must agree on Version and MaxFrameSize. maxWirePayload, when > 0,
// constrains the smux payload size so the encrypted whole smux frame fits
// under the transport's per-message payload cap.
func SmuxConfig(maxWirePayload int) *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.Version = 2
	cfg.KeepAliveDisabled = false
	cfg.MaxFrameSize = smuxMaxFrameSize
	clampMaxFrameSize(cfg, maxWirePayload)
	cfg.MaxReceiveBuffer, cfg.MaxStreamBuffer = receiveBufferSizes()
	cfg.KeepAliveInterval = 10 * time.Second
	cfg.KeepAliveTimeout = 30 * time.Second
	return cfg
}

// SmuxConfigLong is SmuxConfig with a relaxed keep-alive timeout for
// transports whose provider can legitimately go silent for tens of seconds
// (vp8channel/goolom publisher-PC reconnect + SFU renegotiation). A tight
// timeout would tear down the smux session while the provider is rebuilding
// itself, forcing an unnecessary second reconnect. Only transports that
// implement transport.ControlPlane use this; conventional providers
// (jitsi/datachannel) keep the conservative 30s timeout so a genuinely dead
// link is detected and reconnected promptly.
func SmuxConfigLong(maxWirePayload int) *smux.Config {
	cfg := SmuxConfig(maxWirePayload)
	cfg.KeepAliveTimeout = 120 * time.Second
	return cfg
}

// IsControlPlane reports whether the transport routes control-plane traffic
// on an isolated channel (transport.ControlPlane). The relaxed liveness/
// keep-alive windows are scoped to these transports only.
func IsControlPlane(tr transport.Transport) bool {
	_, ok := tr.(transport.ControlPlane)
	return ok
}

// SmuxConfigFor returns the data-plane smux config appropriate for the
// transport: relaxed keep-alive for ControlPlane providers, conservative
// otherwise.
func SmuxConfigFor(tr transport.Transport) *smux.Config {
	maxWirePayload := MaxPayload(tr)
	if IsControlPlane(tr) {
		return SmuxConfigLong(maxWirePayload)
	}
	return SmuxConfig(maxWirePayload)
}

// LivenessTimeout returns the control-stream pong timeout for a transport:
// a relaxed window for ControlPlane transports (KCP batching + frame pacing
// can delay control packets under load), and the conservative default for
// conventional providers so dead links are detected quickly.
func LivenessTimeout(tr transport.Transport) time.Duration {
	if IsControlPlane(tr) {
		return 45 * time.Second
	}
	return control.DefaultTimeout
}

// The tunnel CONNECT exchange has one deadline on each side: the client waits
// for the one-byte ack, the server waits for the request that follows the
// stream's SYN. Both live here so they cannot drift apart again.
//
// The server's must be the longer of the two. The client is the side that
// gives up: when it does, it closes the stream, and the server's read ends in
// FIN rather than on a timer. A server timer that fires first produces the
// other failure - the stream closed under the client with no ack at all,
// "remote not ready (read_err=EOF)" - which reads like a broken server when
// it is only an impatient one (olcbox#23).
//
// Neither depends on the transport. What the ack waits for is the dial on the
// exit (bounded by the dialer's own timeout, answered with a negative ack on
// failure) plus the queueing of two small frames behind bulk data, once in
// each direction. The old split - 90 s for transports with a control plane,
// 15 s for the rest - keyed the wait on a property that has nothing to do
// with queueing: a datachannel got 15 s because it has no control channel,
// not because it is fast. Measured through a Jitsi SCTP bridge at ~5 Mbit/s
// with the sender's backlog unbounded, every connect opened during a
// download waited out the 15 s and failed, and the backlog took minutes to
// drain. Bounding that backlog is the jitsi engine's job (see
// bridgeBacklogHighWater); the number here only has to be generous, because a
// live link always answers eventually, and a dead one is torn down by
// liveness, which fails the waiting read at once.
const (
	connectAckWait     = 90 * time.Second
	connectRequestWait = 120 * time.Second
)

// ConnectAckTimeout is how long the client waits for the CONNECT ack.
func ConnectAckTimeout() time.Duration { return connectAckWait }

// ConnectRequestTimeout is how long the server waits, after accepting a
// stream, for the CONNECT request to arrive whole. Always longer than
// ConnectAckTimeout; see the note above.
func ConnectRequestTimeout() time.Duration { return connectRequestWait }

// ControlSmuxConfig returns a lean smux config for the isolated control-plane
// session. The control session carries only tiny ping/pong frames so we use
// small stream buffers and disable smux keepalives (the olcrtc control.Run
// ping loop handles liveness itself).
func ControlSmuxConfig(maxWirePayload int) *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.Version = 2
	cfg.MaxFrameSize = smuxMaxFrameSize
	clampMaxFrameSize(cfg, maxWirePayload)
	// Tiny buffers: control frames are at most a few hundred bytes.
	cfg.MaxReceiveBuffer = 256 * 1024
	cfg.MaxStreamBuffer = 32 * 1024
	// Disable smux keepalive - control.Run runs its own ping/pong loop.
	cfg.KeepAliveDisabled = true
	return cfg
}

func clampMaxFrameSize(cfg *smux.Config, maxWirePayload int) {
	if maxWirePayload >= MinSmuxWirePayload {
		maxFrameSize := maxWirePayload - SmuxWireOverhead
		if maxFrameSize < cfg.MaxFrameSize {
			cfg.MaxFrameSize = maxFrameSize
		}
	}
}

// MaxPayload reports the transport's per-message payload limit. Returns 0
// when the transport sets no explicit limit; the caller treats 0 as "use
// SmuxConfig's default frame size".
func MaxPayload(tr transport.Transport) int {
	return tr.Features().MaxPayloadSize
}

// HealthTracker holds the live snapshot of one side's control-stream
// health: last pong time, last RTT, miss counts, reconnect counts.
// Server and client both embed a HealthTracker to avoid open-coding the
// same record* methods on both sides.
type HealthTracker struct {
	mu     sync.RWMutex
	status control.Status
	notify func(control.Status)
}

// NewHealthTracker creates a HealthTracker that publishes the latest
// snapshot through notify whenever it changes. notify may be nil.
func NewHealthTracker(notify func(control.Status)) *HealthTracker {
	if notify == nil {
		notify = func(control.Status) {}
	}
	return &HealthTracker{notify: notify}
}

// Status returns the latest health snapshot. A nil tracker reports a zero
// value, which lets tests instantiate stub Server/Client structs without
// wiring up a real tracker.
func (h *HealthTracker) Status() control.Status {
	if h == nil {
		return control.Status{}
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.status
}

// RecordSession resets miss counters and stamps the session id.
func (h *HealthTracker) RecordSession(id string) {
	h.update(func(s *control.Status) {
		s.SessionID = id
		s.MissedPongs = 0
	})
}

// RecordPong updates LastPong/LastRTT and clears MissedPongs.
func (h *HealthTracker) RecordPong(p control.Health) {
	h.update(func(s *control.Status) {
		s.LastPong = p.LastSeen
		s.LastRTT = p.RTT
		s.MissedPongs = 0
	})
}

// RecordMissed bumps the missed-pong count.
func (h *HealthTracker) RecordMissed(missed int) {
	h.update(func(s *control.Status) {
		s.MissedPongs = missed
	})
}

// RecordUnhealthy bumps the unhealthy-event count and stamps the time.
func (h *HealthTracker) RecordUnhealthy(missed int) {
	h.update(func(s *control.Status) {
		s.MissedPongs = missed
		s.UnhealthyEvents++
		s.LastUnhealthy = time.Now()
	})
}

// RecordReconnect bumps the reconnect counter.
func (h *HealthTracker) RecordReconnect() {
	h.update(func(s *control.Status) {
		s.Reconnects++
	})
}

func (h *HealthTracker) update(mutate func(*control.Status)) {
	if h == nil {
		return
	}
	h.mu.Lock()
	mutate(&h.status)
	snapshot := h.status
	h.mu.Unlock()
	h.notify(snapshot)
}
