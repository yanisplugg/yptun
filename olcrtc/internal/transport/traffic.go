package transport

import (
	"errors"
	"fmt"
	"math/rand/v2"
	"sync"
	"time"
)

// ErrTrafficPayloadTooLarge is returned when Send receives a payload above the configured cap.
var ErrTrafficPayloadTooLarge = errors.New("traffic payload exceeds max_payload_size")

// Shaper applies the optional `traffic:` policy - a hard payload cap plus
// randomised inter-send pacing - to a transport's bulk data path.
//
// It is a value owned by each transport rather than a decorator around
// [Transport]: transports expose several optional capability interfaces
// (ControlPlane, PeerControlPlane, PeerReadyTransport, ...) and a wrapper can
// only ever implement a fixed subset of them, silently disabling the rest.
//
// The control plane is deliberately never shaped: liveness and handshake
// frames must not inherit the pacing applied to bulk traffic.
type Shaper struct {
	maxPayloadSize int
	minDelay       time.Duration
	maxDelay       time.Duration
	mu             sync.Mutex
}

// NewShaper returns a Shaper for cfg, or nil when no shaping was requested.
// features clamps MaxPayloadSize to whatever the transport can actually carry.
func NewShaper(cfg TrafficConfig, features Features) *Shaper {
	if cfg.MaxPayloadSize > 0 && features.MaxPayloadSize > 0 && features.MaxPayloadSize < cfg.MaxPayloadSize {
		cfg.MaxPayloadSize = features.MaxPayloadSize
	}

	if cfg.MaxPayloadSize <= 0 && cfg.MinDelay <= 0 && cfg.MaxDelay <= 0 {
		return nil
	}

	return &Shaper{
		maxPayloadSize: cfg.MaxPayloadSize,
		minDelay:       cfg.MinDelay,
		maxDelay:       cfg.MaxDelay,
	}
}

// Send enforces the payload cap, waits out the pacing delay and then calls
// send. A nil Shaper calls send directly, so callers never need a nil check.
func (s *Shaper) Send(send func([]byte) error, data []byte) error {
	if s == nil {
		return send(data)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.maxPayloadSize > 0 && len(data) > s.maxPayloadSize {
		return fmt.Errorf("%w: size=%d max=%d", ErrTrafficPayloadTooLarge, len(data), s.maxPayloadSize)
	}

	if delay := s.nextDelay(); delay > 0 {
		time.Sleep(delay)
	}

	return send(data)
}

// Features narrows f by the shaper's payload cap so upper layers size their
// frames against the effective limit.
func (s *Shaper) Features(f Features) Features {
	if s == nil || s.maxPayloadSize <= 0 {
		return f
	}

	if f.MaxPayloadSize == 0 || s.maxPayloadSize < f.MaxPayloadSize {
		f.MaxPayloadSize = s.maxPayloadSize
	}

	return f
}

func (s *Shaper) nextDelay() time.Duration {
	if s.maxDelay <= s.minDelay {
		return s.minDelay
	}

	//nolint:gosec // G404: non-cryptographic pacing jitter
	return s.minDelay + time.Duration(rand.Int64N(int64(s.maxDelay-s.minDelay)))
}
