package vp8channel

import (
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

const (
	defaultFPS       = 30
	defaultBatchSize = 64
)

// Options tunes the vp8channel transport. Zero values fall back to documented defaults.
type Options struct {
	FPS       int
	BatchSize int
}

// TransportOptions marks Options as belonging to the transport options family.
func (Options) TransportOptions() {}

// withDefaults fills unset Options fields with the package defaults. FPS is
// clamped rather than merely defaulted: the writer loop derives its ticker
// period from it, and both a zero and an absurdly large value produce a
// zero-length tick.
func (o Options) withDefaults() Options {
	o.FPS = transport.NormalizeFPS(o.FPS, defaultFPS)
	if o.BatchSize <= 0 {
		o.BatchSize = defaultBatchSize
	}
	return o
}

func optionsFrom(cfg transport.Config) (Options, error) {
	opts, err := transport.OptionsAs[Options](cfg, "vp8channel")
	if err != nil {
		return Options{}, err
	}
	return opts.withDefaults(), nil
}
