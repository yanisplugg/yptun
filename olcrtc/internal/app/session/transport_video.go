//go:build !olcrtc_lean

package session

import (
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/videochannel"
)

// registerVideoTransport adds videochannel, the transport that carries data
// as QR codes inside a video track. See transport_video_lean.go for the
// build that leaves it out.
func registerVideoTransport() {
	transport.Register("videochannel", videochannel.New)
}

func videoTransportOptions(cfg Config) transport.Options {
	return videochannel.Options{
		Width:      cfg.Video.Width,
		Height:     cfg.Video.Height,
		FPS:        cfg.Video.FPS,
		QRSize:     cfg.Video.QRSize,
		QRRecovery: cfg.Video.QRRecovery,
		Codec:      cfg.Video.Codec,
		TileModule: cfg.Video.TileModule,
		TileRS:     cfg.Video.TileRS,
	}
}
