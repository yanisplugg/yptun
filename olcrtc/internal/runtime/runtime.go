// Package runtime holds infrastructure shared by the olcrtc server and
// client: smux tuning, cipher setup, and control-stream health bookkeeping.
// The lifecycle differences between server and client (accept loop / SOCKS5
// dial vs. SOCKS5 listener / tunnel) live in their respective packages.
package runtime

import (
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/xtaci/smux"
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
	smuxMaxReceiveBuffer = 8 * 1024 * 1024
	smuxMaxStreamBuffer  = 512 * 1024
)

// ErrKeyRequired is returned when no encryption key is provided.
var ErrKeyRequired = errors.New("key required (use -key <hex>)")

// ErrKeySize is returned when the encryption key is not 32 bytes.
var ErrKeySize = errors.New("key must be 32 bytes")

// SetupCipher decodes a 64-char hex key and instantiates the AEAD cipher.
func SetupCipher(keyHex string) (*crypto.Cipher, error) {
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
	cipher, err := crypto.NewCipher(string(key))
	if err != nil {
		return nil, fmt.Errorf("failed to create cipher: %w", err)
	}
	return cipher, nil
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
	if maxWirePayload >= MinSmuxWirePayload {
		maxFrameSize := maxWirePayload - SmuxWireOverhead
		if maxFrameSize < cfg.MaxFrameSize {
			cfg.MaxFrameSize = maxFrameSize
		}
	}
	cfg.MaxReceiveBuffer = smuxMaxReceiveBuffer
	cfg.MaxStreamBuffer = smuxMaxStreamBuffer
	// Keep-alive interval is deliberately generous: the underlying KCP
	// transport can go silent for up to ~25s during a goolom publisher-PC
	// reconnect (SFU renegotiation). A tight timeout would tear down the
	// smux session while the carrier is rebuildingitself, forcing an
	// unnecessary second reconnect. 120s gives plenty of headroom while
	// still catching truly dead links.
	cfg.KeepAliveInterval = 10 * time.Second
	cfg.KeepAliveTimeout = 120 * time.Second
	return cfg
}

// ControlSmuxConfig returns a lean smux config for the isolated control-plane
// session. The control session carries only tiny ping/pong frames so we use
// small stream buffers and disable smux keepalives (the olcrtc control.Run
// ping loop handles liveness itself).
func ControlSmuxConfig(maxWirePayload int) *smux.Config {
	cfg := smux.DefaultConfig()
	cfg.Version = 2
	cfg.MaxFrameSize = smuxMaxFrameSize
	if maxWirePayload >= MinSmuxWirePayload {
		maxFrameSize := maxWirePayload - SmuxWireOverhead
		if maxFrameSize < cfg.MaxFrameSize {
			cfg.MaxFrameSize = maxFrameSize
		}
	}
	// Tiny buffers: control frames are at most a few hundred bytes.
	cfg.MaxReceiveBuffer = 256 * 1024
	cfg.MaxStreamBuffer = 32 * 1024
	// Disable smux keepalive - control.Run runs its own ping/pong loop.
	cfg.KeepAliveDisabled = true
	return cfg
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
