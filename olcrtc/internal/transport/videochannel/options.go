package videochannel

import (
	grtile "github.com/zarazaex69/gr/tile"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// Package defaults for unset Options fields. They mirror the session-level
// video defaults so a transport built straight from a zero Options behaves
// like one built from the documented config.
const (
	defaultFPS        = 30
	defaultWidth      = 1920
	defaultHeight     = 1080
	defaultTileModule = 4
	defaultTileRS     = 20
	codecTile         = "tile"
)

// Options tunes the videochannel transport. Zero values fall back to documented defaults.
type Options struct {
	Width      int
	Height     int
	FPS        int
	QRSize     int
	QRRecovery string
	Codec      string
	TileModule int
	TileRS     int
}

// TransportOptions marks Options as belonging to the transport options family.
func (Options) TransportOptions() {}

// withDefaults fills unset Options fields with the package defaults. FPS is
// clamped rather than merely defaulted: the writer loop derives its ticker
// period from it, and both a zero and an absurdly large value produce a
// zero-length tick.
func (o Options) withDefaults() Options {
	o.FPS = transport.NormalizeFPS(o.FPS, defaultFPS)
	if o.QRSize <= 0 {
		o.QRSize = defaultFragmentSize
	}
	if o.TileModule <= 0 {
		o.TileModule = defaultTileModule
	}
	// A zero RS budget is a valid choice (no Reed-Solomon parity); only an
	// unset/negative value falls back to the default.
	if o.TileRS < 0 {
		o.TileRS = defaultTileRS
	}
	// The tile codec renders fixed-size frames, so its dimensions are not a
	// free choice - they must match the tile frame or the encoder rejects
	// every sample.
	if o.Codec == codecTile {
		if o.Width <= 0 {
			o.Width = grtile.FrameW
		}
		if o.Height <= 0 {
			o.Height = grtile.FrameH
		}
		return o
	}
	if o.Width <= 0 {
		o.Width = defaultWidth
	}
	if o.Height <= 0 {
		o.Height = defaultHeight
	}
	return o
}

func optionsFrom(cfg transport.Config) (Options, error) {
	opts, err := transport.OptionsAs[Options](cfg, "videochannel")
	if err != nil {
		return Options{}, err
	}
	return opts.withDefaults(), nil
}
