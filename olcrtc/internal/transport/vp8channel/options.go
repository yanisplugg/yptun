package vp8channel

import (
	"fmt"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

const (
	defaultFPS       = 30
	defaultBatchSize = 64
	// defaultMaxBytesPerSec paces the wire byte-rate just under the Telemost
	// SFU's measured per-slot policer knee. The original 1.2 MiB/s caused the
	// SFU to throttle subscriber forwarding after ~42s; 400 KB/s stays well
	// within the SFU's comfort zone while still giving useful throughput.
	defaultMaxBytesPerSec = 400_000
)

// Options tunes the vp8channel transport. Zero values fall back to documented defaults.
type Options struct {
	FPS       int
	BatchSize int
	// MaxBytesPerSec caps the wire byte-rate fed to the video track. Zero
	// falls back to defaultMaxBytesPerSec.
	MaxBytesPerSec int
}

// TransportOptions marks Options as belonging to the transport options family.
func (Options) TransportOptions() {}

func optionsFrom(cfg transport.Config) (Options, error) {
	if cfg.Options == nil {
		return Options{}, nil
	}
	opts, ok := cfg.Options.(Options)
	if !ok {
		return Options{}, fmt.Errorf("%w: vp8channel: got %T", transport.ErrOptionsTypeMismatch, cfg.Options)
	}
	return opts, nil
}
