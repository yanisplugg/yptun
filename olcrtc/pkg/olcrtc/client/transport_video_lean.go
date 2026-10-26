//go:build olcrtc_lean

package client

import "github.com/openlibrecommunity/olcrtc/internal/transport"

// A lean build (see internal/app/session/transport_video_lean.go) does not
// link videochannel. The options type stays, so callers compile either way;
// a session asked for the transport fails to start with ErrTransportNotFound.
func videoTransportOptions(VideoOptions) transport.Options { return nil }
