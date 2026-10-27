package tunnelcore

import (
	"context"
	"fmt"
	"io"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// SendControlClose writes the shared control close frame.
func SendControlClose(stream *smux.Stream) error {
	if err := control.SendClose(stream); err != nil {
		return fmt.Errorf("send control close: %w", err)
	}
	return nil
}

// ControlRunner runs one control stream with shared tuning and health tracking.
type ControlRunner struct {
	Transport transport.Transport
	Config    control.Config
	Health    *runtime.HealthTracker
	LogFields func() string
	OnPong    func(control.Health)
	OnDeath   func(error)
	// Progress reports payload received from the peer; see control.Config.
	Progress func() uint64
	// SendStalled reports that the data plane stopped sending; see
	// control.Config.
	SendStalled func() bool
	// BeforeClose tells the peer this side is leaving, while the stream is
	// still open; see control.Config.BeforeClose. Nil leaves the peer to
	// find out through liveness.
	BeforeClose func()
}

// Run blocks until the control stream stops, then invokes OnDeath unless ctx was canceled.
//
// ai-generated: stream is any io.ReadWriteCloser, as control.Run takes it, so
// a caller can keep the close for itself (olcrtc#49).
func (r ControlRunner) Run(ctx context.Context, stream io.ReadWriteCloser) {
	cfg := r.tunedConfig()
	cfg.BeforeClose = r.BeforeClose
	err := control.Run(ctx, stream, cfg)
	if ctx.Err() != nil {
		return
	}
	if err != nil {
		logger.Warnf("control stream ended %s: %v", r.fields(), err)
	}
	if r.OnDeath != nil {
		r.OnDeath(err)
	}
}

func (r ControlRunner) tunedConfig() control.Config {
	cfg := r.Config
	if runtime.IsControlPlane(r.Transport) && cfg.Timeout <= control.DefaultTimeout {
		cfg.Timeout = runtime.LivenessTimeout(r.Transport)
	}
	onPong := cfg.OnPong
	onMissed := cfg.OnMissedPong
	onUnhealthy := cfg.OnUnhealthy
	cfg.OnPong = func(health control.Health) {
		r.Health.RecordPong(health)
		if r.OnPong != nil {
			r.OnPong(health)
		}
		logger.Debugf("control alive %s rtt=%v seq=%d", r.fields(), health.RTT, health.Seq)
		if onPong != nil {
			onPong(health)
		}
	}
	cfg.OnMissedPong = func(missed int) {
		r.Health.RecordMissed(missed)
		logger.Warnf("control missed pong %s missed=%d", r.fields(), missed)
		if onMissed != nil {
			onMissed(missed)
		}
	}
	cfg.Progress = r.Progress
	cfg.OnStalled = func(timedOut int) {
		logger.Warnf("control pong late %s but the peer is still sending — not counting %d as missed", r.fields(), timedOut)
	}
	cfg.SendStalled = r.SendStalled
	cfg.OnSendStalled = func() {
		logger.Warnf("control healthy %s but the data plane has stopped sending — tearing the session down", r.fields())
	}
	cfg.OnUnhealthy = func(missed int) {
		r.Health.RecordUnhealthy(missed)
		logger.Warnf("control unhealthy %s missed=%d", r.fields(), missed)
		if onUnhealthy != nil {
			onUnhealthy(missed)
		}
	}
	return cfg
}

func (r ControlRunner) fields() string {
	if r.LogFields == nil {
		return ""
	}
	return r.LogFields()
}
