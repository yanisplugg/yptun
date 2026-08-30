package seichannel

import (
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// Options tunes the seichannel transport. Zero values fall back to documented defaults.
type Options struct {
	FPS          int
	BatchSize    int
	FragmentSize int
	AckTimeoutMS int
}

// TransportOptions marks Options as belonging to the transport options family.
func (Options) TransportOptions() {}

// withDefaults fills unset Options fields with the package defaults.
func (o Options) withDefaults() Options {
	o.FPS = transport.NormalizeFPS(o.FPS, defaultFPS)
	if o.BatchSize <= 0 {
		o.BatchSize = defaultBatchSize
	}
	if o.FragmentSize <= 0 {
		o.FragmentSize = defaultFragmentSize
	}
	if o.AckTimeoutMS <= 0 {
		o.AckTimeoutMS = int(defaultAckTimeout / time.Millisecond)
	}
	return o
}

func optionsFrom(cfg transport.Config) (Options, error) {
	opts, err := transport.OptionsAs[Options](cfg, "seichannel")
	if err != nil {
		return Options{}, err
	}
	return opts.withDefaults(), nil
}
