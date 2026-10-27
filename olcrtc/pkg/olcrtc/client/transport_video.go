//go:build !olcrtc_lean

package client

import (
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/videochannel"
)

func videoTransportOptions(value VideoOptions) transport.Options {
	return videochannel.Options(value)
}
